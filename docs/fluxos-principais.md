# Fluxos principais

Workflows centrais do account-service. Decisões e motivações: [Design Doc](design-doc.md). Topologia e CI/CD: [Arquitetura AWS](arquitetura-aws-e-pipeline.md).

**Última atualização:** 2026-08-05 (claim-first ingest, batch ACK, topologia em cache, correlação de envelope, escala multi-réplica).

---

## 1. Visão ponta a ponta

```mermaid
sequenceDiagram
  participant Origem as Sistema de origem
  participant SQS as SQS Standard
  participant App as account-service
  participant PG as PostgreSQL
  participant IdP as IdP OIDC (alvo prod)
  participant GW as API Gateway (alvo prod)
  participant Cliente as Cliente HTTP

  Origem->>SQS: Publica evento financeiro
  Note over App: Topology cache OK<br/>N receivers long-poll
  App->>SQS: ReceiveMessage
  App->>PG: Claim + CAS snapshot + journal
  App->>SQS: DeleteMessageBatch (ACK pós-commit)
  Cliente->>IdP: AuthN (não no MVP)
  Cliente->>GW: GET /balances + JWT
  GW->>App: Request autorizado
  App->>PG: SELECT snapshot PK
  App->>Cliente: 200 BalanceResponse
```

No MVP local o hop IdP/Gateway é omitido; a solução-alvo **exige** AuthN/AuthZ — ver design-doc §3.1.

---

## 2. Ingestão de evento (claim-first)

Caminho quente evita lookups: tenta inserir `processed_transaction` primeiro.

```mermaid
flowchart TD
  A[ReceiveMessage] --> Corr[Resolve correlationId<br/>attr SQS ou HMAC do body]
  Corr --> B{Parse payload}
  B -->|InvalidFinancialEventException| I[Journal INVALID best-effort]
  I --> NoAck1[Sem ACK — broker / DLQ]
  B -->|OK| C["tryInsert processed_transaction<br/>outcome tentativo ACCEPTED"]
  C -->|DUPLICATE_TRANSACTION| D[Journal DUPLICATE + ACK batch]
  C -->|ACCOUNT_TIMESTAMP_TAKEN| E[Resolver ocupante]
  E --> E1{Outra tx no mesmo account+ts?}
  E1 -->|Sim| F[ordering_conflict + claim CONFLICTING + ACK]
  E1 -->|Não / peer irresolvível| F2[Claim CONFLICTING + ACK]
  C -->|INSERTED| K{CAS upsertIfNewer snapshot}
  K -->|Atualizou| L[Journal ACCEPTED / UPDATED + ACK batch]
  K -->|Não atualizou| M[Journal STALE_LOST_RACE + ACK batch]
```

**Regras:**

- Saldo do evento é autoritativo (não há soma de lançamentos).
- Vitória = `source_timestamp` estritamente mais novo no CAS.
- ACK = `DeleteMessageBatch` apenas após persistência durável (`ACCEPTED` / `DUPLICATE` / `STALE` / `CONFLICTING`).
- `INVALID` **não** dá ACK — isolamento pelo `RedrivePolicy`.
- Índice `idx_processed_account_ts_lookup` (Flyway V4) acelera `findOtherTransactionAt` no ramo de conflito.

---

## 3. Falha transitória e esgotamento de retry

```mermaid
flowchart TD
  A[Ingest lança exceção] --> B{receiveCount ≥ broker maxReceiveCount?}
  B -->|Não| C[Sem ACK]
  C --> D[Visibility timeout]
  D --> E[Redelivery SQS]
  E --> A
  B -->|Sim| F[Journal best-effort PERMANENTLY_FAILED]
  F --> G[Sem ACK — broker move para DLQ]
```

**Notas:**

- Parse inválido → `InvalidFinancialEventException` → journal `INVALID` best-effort, **sem ACK**.
- `IllegalArgumentException` de lógica interna permanece **retentável**.
- DLQ de broker = isolamento técnico; journal = auditoria. Ver [Design Doc §3.2](design-doc.md).
- Redrive operacional: IAM de recovery **fora** do pod (`deploy/scripts/`, `deploy/terraform/`).

---

## 4. Consulta de saldo

```mermaid
sequenceDiagram
  participant C as Cliente
  participant IdP as IdP (alvo prod)
  participant GW as Gateway JWT (alvo prod)
  participant API as BalanceController
  participant UC as GetBalanceUseCase
  participant PG as SnapshotPort

  C->>IdP: Obtém JWT
  C->>GW: GET /balances/{accountId} + Bearer
  GW->>GW: Valida assinatura / escopo balance.read
  GW->>API: Request
  API->>API: AccountId.parse
  alt UUID inválido
    API-->>C: 400 VALIDATION_ERROR
  else OK
    API->>UC: getBalance
    UC->>PG: findByAccountId
    alt Sem snapshot
      UC-->>C: 404 ACCOUNT_NOT_FOUND
    else Encontrado
      UC-->>C: 200 id/owner/balance/updated_at
    end
  end
```

A consulta **não** lê journal nem reconstrói histórico. Latência medida em `http.server.requests` (p95/p99 do desafio).

---

## 5. Conflito de timestamp igual

```mermaid
flowchart LR
  T1["Tx A<br/>account=X ts=T"] --> W[Ganha claim / snapshot]
  T2["Tx B<br/>account=X ts=T"] --> C[CONFLICTING]
  C --> OC[ordering_conflict]
  C --> J[journal]
  W --> S[Snapshot permanece com vencedor]
  C -.-> S
```

Não há desempate por ordem de chegada. Recuperação operacional: processo futuro com replay autenticado (IdP + papel `journal.replay`).

---

## 6. Journal interno (acesso negado por padrão)

```mermaid
sequenceDiagram
  participant Op as Chamador
  participant GW as Gateway + IdP (alvo prod)
  participant JC as JournalController
  participant Pol as JournalAccessPolicy
  participant Aud as administrative_journal_action

  Op->>GW: GET /internal/journal/... (+ /ingest-span) + JWT journal.read
  GW->>JC: Autorizado no edge
  JC->>Pol: canRead...?
  JC->>Aud: registra tentativa
  alt Negado
    JC-->>Op: 403 JOURNAL_ACCESS_DENIED
  else Permitido
    JC-->>Op: 200 registros
  end
```

Replay (`POST /internal/journal/replay`): audita, exige permissão e, no estado atual, responde *não implementado* se permitido.

---

## 7. Concorrência do consumer SQS

```mermaid
flowchart TD
  Start[PostConstruct] --> Topo[SqsTopologyValidator<br/>refresh em background]
  Start --> Rec["N pollers virtuais<br/>SQS_RECEIVER_COUNT"]
  Start --> Flush["Ack flusher ~50ms<br/>DeleteMessageBatch"]
  Rec --> Gate{Cache topologia<br/>permite poll?}
  Gate -->|enforce inválido| Pause[Pausa — sem ReceiveMessage]
  Gate -->|ok| Cap["reserva até min(10, permits) *antes* do Receive"]
  Cap -->|0| Wait[Espera breve por permit]
  Cap -->|n| R["ReceiveMessage max=n"]
  R -->|menos que n| Rel[libera reservas não usadas]
  R --> Loop[1 permit já preso por mensagem]
  Loop --> VT[Virtual thread processMessage]
  VT --> Batch[enqueue SqsDeleteBatcher]
  Batch --> Flush
```

- Permits são reservados **antes** de `ReceiveMessage` para vários receivers não super-receberem e devolverem com visibility 0 (isso incrementaria `ApproximateReceiveCount` e poderia ir para a DLQ).
- Se o SQS devolver menos mensagens que o reservado, as reservas ociosas são liberadas imediatamente.
- Validação de DLQ/`maxReceiveCount` **não** está no hot path (cache + health `sqsTopology`).
- Vários pods competem na mesma fila Standard (escala horizontal — [§7.1 do design-doc](design-doc.md)).

---

## 8. Outcomes × efeito no snapshot

| Outcome | Snapshot | ACK? |
| --- | --- | --- |
| `ACCEPTED` | Pode atualizar (`UPDATED`) | Sim (batch) |
| `DUPLICATE` | Inalterado | Sim |
| `STALE` | Inalterado | Sim |
| `CONFLICTING` | Inalterado | Sim |
| `INVALID` | Inalterado | **Não** (DLQ) |
| `PERMANENTLY_FAILED` | Inalterado | **Não** (DLQ) |

Sob carga paralela é esperado misturar `ACCEPTED` e `STALE` (eventos mais antigos perdem o CAS). O saldo final deve coincidir com o evento de **maior** `source_timestamp` por conta (oracle do `deploy/perf`).

---

## 9. Harness de performance

```mermaid
sequenceDiagram
  participant P as run-benchmark.sh
  participant Q as SQS
  participant A as account-service
  participant PG as PostgreSQL
  participant K as k6

  P->>Q: Purge fonte + DLQ
  P->>Q: Seed idx=1 DelaySeconds=0
  A->>Q: Consome seed
  P->>P: Verify 200 / amount=1.00
  P->>Q: Main idx=2..N DelaySeconds=D
  Note over Q: delayed ≈ N — consumer não vê
  P->>P: Espera publish_end + D e delayed=0
  P->>K: Start k6 (SC-003 overlap)
  A->>Q: Consome backlog
  K->>A: GET /balances (simultâneo)
  P->>P: Drain visible+inflight+delayed=0
  P->>P: T1 scrape p95/p99 (gate SC-003)
  P->>PG: MIN/MAX first_processed_at → EPS durável
  P->>P: Wait k6; T2 observacional
  P->>P: Verify oracle final
```

- EPS preferido: `COUNT / (MAX(first_processed_at) − MIN(first_processed_at))` nas contas do run após `T_consume`.
- Publisher msg/s **não** entra no gate.
- Âncora local de sizing: drain **300k** em **16 min 41 s** ⇒ **~300 EPS**/instância; piso para 2k EPS = **7** réplicas (`ceil(2000/300)`). Ainda não prova EKS/RDS. Detalhe: [arquitetura §5](arquitetura-aws-e-pipeline.md)

---

## 10. Referências

- [Modelo de dados](modelo-de-dados.md)
- [Design Doc](design-doc.md)
- [Arquitetura AWS e pipeline](arquitetura-aws-e-pipeline.md)
- [OpenAPI saldo](../src/main/resources/static/openapi-balances.yaml)

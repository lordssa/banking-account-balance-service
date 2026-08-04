# Fluxos principais

Este documento descreve os workflows centrais do account-service com diagramas. Detalhes de decisão: [Design Doc](design-doc.md).

---

## 1. Visão ponta a ponta

```mermaid
sequenceDiagram
  participant Origem as Sistema de origem
  participant SQS as SQS
  participant App as account-service
  participant PG as PostgreSQL
  participant Cliente as Cliente HTTP

  Origem->>SQS: Publica evento financeiro
  App->>SQS: ReceiveMessage
  App->>PG: Transação de ingestão
  App->>SQS: DeleteMessage (ACK pós-commit)
  Cliente->>App: GET /balances/{accountId}
  App->>PG: SELECT snapshot
  App->>Cliente: 200 BalanceResponse
```

---

## 2. Ingestão de evento (caminho feliz)

```mermaid
flowchart TD
  A[ReceiveMessage] --> B{Parse payload}
  B -->|Inválido| I[Journal INVALID + ACK]
  B -->|OK| C{transaction_id já processado?}
  C -->|Sim| D[Journal DUPLICATE + ACK]
  C -->|Não| E{Outra tx no mesmo account+ts?}
  E -->|Sim| F[ordering_conflict + CONFLICTING + ACK]
  E -->|Não| G{Timestamp ≤ snapshot?}
  G -->|Sim| H[Claim STALE + ACK]
  G -->|Não| J[Claim ACCEPTED]
  J --> K{CAS upsert snapshot}
  K -->|Atualizou| L[Journal ACCEPTED/UPDATED + ACK]
  K -->|Não atualizou| M[Journal STALE_LOST_RACE + ACK]
```

**Regras:**

- Saldo do evento é autoritativo (não há soma de lançamentos).
- Vitória = `source_timestamp` estritamente mais novo no CAS.
- ACK = `DeleteMessage` apenas após persistência durável.

---

## 3. Falha transitória e esgotamento de retry

```mermaid
flowchart TD
  A[Ingest lança exceção não-parse] --> B{receiveCount ≥ maxReceiveCount?}
  B -->|Não| C[Sem ACK]
  C --> D[Visibility timeout]
  D --> E[Redelivery SQS]
  E --> A
  B -->|Sim| F[Journal PERMANENTLY_FAILED]
  F --> G[ACK — remove da fila primária]
```

**Notas:**

- Parse inválido usa `InvalidFinancialEventException` → isolamento imediato (não mistura com bugs internos).
- `IllegalArgumentException` de lógica interna permanece **retentável** (não ACK como inválido).
- Sem DLQ de broker neste escopo; quarantine = journal Postgres ([Design Doc §3.2](design-doc.md)).

---

## 4. Consulta de saldo

```mermaid
sequenceDiagram
  participant C as Cliente
  participant API as BalanceController
  participant UC as GetBalanceUseCase
  participant PG as SnapshotPort

  C->>API: GET /balances/{accountId}
  API->>API: AccountId.parse
  alt UUID inválido
    API-->>C: 400 VALIDATION_ERROR
  else OK
    API->>UC: getBalance
    UC->>PG: findByAccountId
    alt Sem snapshot
      UC-->>C: 404 ACCOUNT_NOT_FOUND
    else Encontrado
      UC-->>C: 200 amount/currency/lastUpdatedAt
    end
  end
```

A consulta **não** lê journal nem reconstrói histórico.

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

Não há desempate por ordem de chegada. Recuperação operacional fica para processo futuro (replay autenticado).

---

## 6. Journal interno (acesso negado por padrão)

```mermaid
sequenceDiagram
  participant Op as Chamador
  participant JC as JournalController
  participant Pol as JournalAccessPolicy
  participant Aud as administrative_journal_action

  Op->>JC: GET /internal/journal/...
  JC->>Pol: canRead...?
  JC->>Aud: registra tentativa
  alt Negado
    JC-->>Op: 403 JOURNAL_ACCESS_DENIED
  else Permitido
    JC-->>Op: 200 registros
  end
```

Replay (`POST /internal/journal/replay`): audita, exige permissão e, no estado atual, responde *não implementado* se permitido — ver não-objetivos de auth no [Design Doc](design-doc.md).

---

## 7. Concorrência do consumer SQS

```mermaid
flowchart TD
  P[Poller] --> Cap["capacity = min(10, permits livres)"]
  Cap -->|0| Wait[Espera breve por permit]
  Cap -->|n| R[ReceiveMessage n]
  R --> Loop[Para cada mensagem]
  Loop --> Acq{tryAcquire}
  Acq -->|sim| VT[Virtual thread processa]
  Acq -->|não| Vis["ChangeMessageVisibility 0<br/>devolve à fila"]
```

Evita mensagens recebidas sem worker ficarem invisíveis até o fim do *visibility timeout*.

---

## 8. Outcomes × efeito no snapshot

| Outcome | Snapshot |
|---------|----------|
| `ACCEPTED` | Pode atualizar (`UPDATED`) |
| `DUPLICATE` | Inalterado |
| `STALE` | Inalterado |
| `CONFLICTING` | Inalterado |
| `INVALID` / `PERMANENTLY_FAILED` | Inalterado |

---

## 9. Referências

- [Modelo de dados](modelo-de-dados.md)
- [ADR-002](../specs/001-account-balance-query/adr/ADR-002.md) — CAS latest-wins
- [ADR-005](../specs/001-account-balance-query/adr/ADR-005.md) — ACK/retry
- [Contrato SQS](../specs/001-account-balance-query/contracts/sqs-ingestion.md)
- [OpenAPI saldo](../src/main/resources/static/openapi-balances.yaml)

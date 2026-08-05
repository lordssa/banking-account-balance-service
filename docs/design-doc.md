# Design Doc — Account Balance Service

**Status:** Implementado (escopo do desafio)  
**Autores:** Time account-service  
**Última atualização:** 2026-08-05  
**Audiência:** engenharia, arquitetura e revisores do desafio

---

## 1. Contexto

Instituições financeiras precisam expor o **saldo corrente** de contas a partir de eventos publicados por um sistema de origem (ledger/processamento financeiro). A entrega via **SQS Standard** é *at-least-once*: o consumidor pode receber duplicatas e mensagens fora de ordem.

Este serviço:

1. Consome eventos da fila `transacoes-financeiras-processadas`.
2. Materializa um **snapshot autoritativo** de saldo por conta.
3. Expõe `GET /balances/{accountId}` com leitura apenas do snapshot durável.

O saldo no payload é **autoritativo** — o serviço **não** reconstrói saldo somando créditos e débitos.

**Por que isso importa:** saldo de conta é um **núcleo bancário**. Erros de ordenação, duplicata ou “saldo inventado” no consumidor geram impacto regulatório e de confiança. O desenho privilegia **estabilidade das regras** e **auditoria** sobre atalhos de throughput.

---



## 2. Objetivos (Goals)


| #   | Objetivo                                                                                     |
| --- | -------------------------------------------------------------------------------------------- |
| G1  | Consulta de saldo correta sob duplicata, atraso e ordem invertida                            |
| G2  | Idempotência por `transaction_id` (primeira decisão vence)                                   |
| G3  | *Latest-wins* por `source_timestamp` (microssegundo), não por ordem de chegada               |
| G4  | Isolamento de eventos inválidos / conflitos / falhas permanentes com auditoria (journal)     |
| G5  | Arquitetura limpa (domínio sem Spring/AWS/JPA) e schema versionado (Flyway)                  |
| G6  | Observabilidade mínima: health, métricas de ingestão/DB/consulta                             |
| G7  | Execução local reproduzível (Postgres + LocalStack + Compose)                                |
| G8  | Harness de carga reproduzível (`deploy/perf`); **prova** de 2k EPS só em ambiente alvo (EKS/RDS), não no laptop |


---



## 3. Não-objetivos (Non-goals) do entregável atual

Devido ao **escopo limitado** e à **restrição de tempo** do desafio, os itens abaixo **não estão no código deste repositório**. Permanecem **obrigatórios na solução-alvo de produção** (ver §4 e [arquitetura AWS](arquitetura-aws-e-pipeline.md)).

### 3.1 Identity Provider (IdP) — AuthN / AuthZ

**Não implementado** na API (não há validação de JWT/OIDC no serviço).

**É requisito da solução**, não um “nice to have”:

- Impedir consulta de saldo por cliente/sistema não autorizado.
- Separar papéis: produto (só saldo) vs. suporte (leitura de journal) vs. engenharia (replay/redrive).
- *Least privilege*, auditoria *who-did-what*, conformidade (LGPD / acesso interno).
- Endpoints `/internal/journal/**` exigem identidade forte (hoje: *deny-by-default* + subject placeholder). Flag local `JOURNAL_ALLOW_ANONYMOUS_READ` só para diagnóstico de perf — **proibida em produção**.

**Direção de produção:** IdP corporativo (OIDC — Cognito, Azure AD, Keycloak, etc.) → tokens no **API Gateway / Mesh** → claims no serviço (RBAC). IRSA continua sendo a identidade **AWS** do pod (SQS/Secrets), distinta da identidade **humana/sistema chamador**. Detalhe no [doc de topologia](arquitetura-aws-e-pipeline.md) §2.

### 3.2 Dead Letter Queue (DLQ) de broker

**Implementado:** a fila fonte tem `RedrivePolicy` para `transacoes-financeiras-processadas-dlq` (`maxReceiveCount` inicialmente 5). Falhas técnicas/validação **não** são ACKadas só por esgotamento local; o broker isola o envelope bruto. O journal Postgres permanece trilha de auditoria de negócio e **não** substitui a DLQ.

**Recuperação:** `StartMessageMoveTask` manual, deny-by-default, papel IAM de recovery (não no pod da aplicação), taxa inicial 10 msg/s.

**Ver:** `deploy/terraform/`, `deploy/scripts/`.

### 3.3 BDD (Behavior-Driven Development)

**Não há suíte Cucumber/Gherkin neste repositório.** A especificação usa cenários Given/When/Then em texto; a execução local/CI atual é JUnit/AssertJ/Testcontainers/ArchUnit.

**O pipeline de produção deve contemplar avaliação BDD** (ver [CI/CD](arquitetura-aws-e-pipeline.md) §4): *features* versionadas como critério de aceite executável (latest-wins, duplicata, conflito, 200/404 de saldo, isolamento DLQ). Sem esse gate, regressões de regra de negócio passam só por testes de código que stakeholders de produto não leem.

**Trade-off do desafio:** cobertura automatizada forte em código agora; contrato de pipeline reserva o estágio BDD.

### 3.4 Outros não-objetivos

- Gateway de API / BFF / UI (Gateway entra na topologia-alvo com IdP).
- Multi-região ativa-ativa.
- Cache distribuído como fonte da verdade.
- Replay operacional completo de journal (endpoint existe, implementação negada / *not implemented*).

---



## 4. Arquitetura lógica e motivações

```
                    ┌─────────────────────────┐
                    │  IdP (OIDC) — alvo prod │
                    │  AuthN / AuthZ / RBAC   │
                    └────────────┬────────────┘
                                 │ JWT (não no MVP)
┌─────────────────┐     ┌────────▼─────────┐
│  SQS (ingest)   │     │  HTTP (consulta) │
└────────┬────────┘     └────────┬─────────┘
         │ adapters in           │
         ▼                       ▼
┌────────────────────────────────────────────┐
│           Application (use cases)          │
└────────────────────┬───────────────────────┘
                     │ ports
         ┌───────────┴───────────┐
         ▼                       ▼
┌─────────────────┐     ┌────────────────────┐
│     Domain      │     │ Adapters out (PG,  │
│  (regras puras) │     │  métricas, AWS)    │
└─────────────────┘     └────────────────────┘
```



### 4.1 Clean / Hexagonal Architecture

**Motivação:** saldo de conta é serviço **pivô** do banco. Precisa permanecer estável por anos enquanto mudam broker (SQS → outro), ORM, framework web ou nuvem. Regras (*latest-wins*, idempotência, conflito) vivem em `domain` + `application` **sem** Spring, AWS SDK, JPA ou Jackson.

- Trocar adaptador não reescreve a regra financeira.
- ArchUnit impede dependências indevidas no CI.
- Testes de caso de uso rodam sem LocalStack.



### 4.2 Stack

Java 25, Spring Boot 4, PostgreSQL 16, Flyway, AWS SDK v2 SQS, Micrometer/OTel, virtual threads + semáforo de concorrência, múltiplos *receivers* SQS + `DeleteMessageBatch`.

**Motivação da stack:** ecossistema maduro para JDBC transacional (CAS no banco), observabilidade e operação em EKS — sem reinventar runtime.

### 4.3 Ingestão atual (pós-otimização)

- **Claim-first:** `tryInsert(processed_transaction)` → no sucesso, `upsertIfNewer` do snapshot → journal. Lookups de duplicata/conflito só no *miss* do claim.
- **Topologia em cache:** `SqsTopologyValidator` valida `RedrivePolicy`/DLQ em background; o poller **não** chama `GetQueueAttributes` no caminho quente.
- **Correlação:** prefere atributos SQS `eventCorrelationId` / `correlationId`; senão HMAC-SHA256 do body (`account.sqs.envelope-hmac-secret`).
- **ACK:** `SqsDeleteBatcher` agrupa `DeleteMessageBatch` (≤10) após outcome durável `ACCEPTED` / `DUPLICATE` / `STALE` / `CONFLICTING`.
- **Inválido:** journal `INVALID` best-effort, **sem ACK** (broker isola na DLQ).

---



## 5. Decisões principais, motivações e trade-offs



### 5.1 Banco de dados — PostgreSQL

**Motivação:** verdade financeira exige transação ACID, índices únicos parciais e journal relacional consultável. Conta corrente não pode “perder” o snapshot num failover de cache.


| Alternativa                                | Por que não                                                                                                 |
| ------------------------------------------ | ----------------------------------------------------------------------------------------------------------- |
| DynamoDB como SoR                          | Modelo de conflito/CAS por timestamp e journal relacional ficam mais caros; transações multi-item limitadas |
| Redis como SoR                             | Volatilidade / perda sob failover; inadequado como verdade financeira                                       |
| Somente event store + projeção sob demanda | Consulta p95 sofreria; desafio pede snapshot corrente                                                       |


**Decisão:** Postgres (local) / RDS Multi-AZ (produção) como store autoritativo do snapshot, idempotência, journal e conflitos.

### 5.2 Algoritmo *latest-wins*

**Motivação:** SQS Standard não garante ordem. A regra de negócio (“o evento mais novo no tempo da origem vence”) deve ser **independente da ordem de chegada**.

- Upsert condicional:  
`ON CONFLICT (account_id) DO UPDATE … WHERE source_timestamp < EXCLUDED.source_timestamp`
- Evita *read-modify-write* na aplicação (sujeito a corrida).
- Híbrido JDBC (escritas CAS) + Spring Data JPA (leituras simples).



### 5.3 Idempotência

**Motivação:** *at-least-once* implica reentrega. Sem PK em `transaction_id`, o mesmo crédito atualizaria o snapshot duas vezes (ou pior: após um evento mais novo).

- PK `processed_transaction.transaction_id`.
- Primeira inserção fixa o `first_outcome`; reentrega → `DUPLICATE` sem nova transição de saldo.



### 5.4 Timestamp igual

**Motivação:** dois eventos distintos no mesmo microssegundo não podem ser desempatados por “quem chegou primeiro” — isso seria ordem de rede, não de negócio.

- Dois `transaction_id` com mesmo `(account_id, source_timestamp)` → `CONFLICTING`.
- Snapshot **não** muda por desempate de chegada.
- Índice único parcial impede dois “vencedores” não-conflitantes.



### 5.5 ACK / retry

**Motivação:** ACK antes do commit gera perda silenciosa; ACK de inválido sem DLQ gera *poison* eterno ou some com evidência.

- `DeleteMessage` **somente** após commit durável de `ACCEPTED` / `DUPLICATE` / `STALE` / `CONFLICTING`.
- Falha transitória ou validação permanente → sem ACK → reentrega / DLQ via broker `RedrivePolicy`.
- Observação de limiar de receive → journal best-effort `PERMANENTLY_FAILED` **sem** ACK (DLQ é a cópia recuperável).



### 5.6 Concorrência e horizontal scaling

**Motivação:** um único processo não atinge o pico de 2k eventos/s de forma confiável (evidência §7). Escala-se **réplicas** + concorrência interna alinhada ao pool JDBC.

- Virtual threads + semáforo (`SQS_MAX_CONCURRENT`) alinhado ao pool Hikari.
- `SQS_RECEIVER_COUNT` pollers em long-poll paralelo.
- Recebe no máximo `min(10, permits)` mensagens; sobras devolvem *visibility* 0.



### 5.7 Cache

**Motivação:** cache como SoR viola a premissa de saldo autoritativo durável. Benchmark local (p95 ~14 ms) **não** justifica Redis no caminho de leitura ainda.

- MVP: leitura PK direta no snapshot.
- Sem UNLOGGED / Redis até prova de necessidade em hardware de produção.



### 5.8 Observabilidade

**Motivação:** sem métricas server-side, “passou no k6” não prova o SLO do desafio (p95/p99 de `http.server.requests`).

- Micrometer + Actuator + Prometheus scrape; OTLP opcional.
- Falha de export **não** deve bloquear ingestão/consulta.

---



## 6. Riscos e mitigações


| Risco                               | Mitigação                                                                                |
| ----------------------------------- | ---------------------------------------------------------------------------------------- |
| Poison message trava a fila         | Sem ACK + DLQ broker; journal consultável                                                |
| Deploy ruim afeta 100% dos clientes | Canary / rolling + readiness DB + IdP no edge ([doc AWS](arquitetura-aws-e-pipeline.md)) |
| Corrida equal-timestamp             | Índice único parcial + outcome `CONFLICTING`                                             |
| Ambiguidade de commit vs ACK        | Journal idempotente por `attempt_key`; reprocessamento seguro                            |
| Pico 2k EPS sem evidência EKS | Piso local **7** réplicas (`ceil(2000/300)`); validar multi-pod + SC-003 no alvo (§7.1) |
| AuthZ ausente no MVP                | Deny-by-default no journal; IdP obrigatório na topologia-alvo                            |


---



## 7. Métricas de sucesso (desafio) e evidência

| Critério | Como medir | Estado da evidência |
| --- | --- | --- |
| Ingestão ≥ 2.000 eventos/s | Preferir span durável `MIN/MAX(processed_transaction.first_processed_at)` nas contas do run após `T_consume`; fallback parede `visível→drain`. Publisher msg/s **não** conta. | **Piso local de sizing:** drain de **300k** msgs (consumer off → on) em **16 min 41 s** ⇒ **~300 EPS** por instância. Piso linear para 2k: **`ceil(2000/300) = 7` réplicas**. Ainda **não é prova EKS/RDS** (LocalStack + 1 JVM). Janelas curtas (~650 EPS / 3 s) não usam para sizing. |
| Consulta p95 ≤ 100 ms, p99 ≤ 250 ms **com ingestão ativa** (SC-003) | k6 sobrepõe a janela de consume; scrape **T1** (logo após drain). Histogram Micrometer é **cumulativo**. | Runs anteriores mediram p95/p99 **depois** do drain — **não** validam SC-003. O harness atual sobrepõe k6 ao consume. |
| Queries de carga | HTTP **200** + body (404 falha) | Funcional; não substitui o SLO combinado |
| Testes / ArchUnit | `mvn test` / `verify` | CI local |
| BDD | Estágio de pipeline (alvo prod) | Ainda não no repo — §3.3 |

Procedimento: [`deploy/perf/README.md`](../deploy/perf/README.md).

### 7.1 Dimensionamento de réplicas

**Medição de âncora (1 instância):** 300.000 mensagens pré-carregadas; consumer ligado depois; drain em **16 min 41 s** ⇒ **~300 EPS** sustentados (LocalStack + Postgres local).

**Piso para pico 2.000 EPS:** \(\lceil 2000 / 300 \rceil =\) **7 réplicas**. KEDA: `min=3` (HA), `max ≥ 7` (manifesto atual `max=15`).

**Não usar** `4 × 650 = 2600 EPS` (janela de 3 s). Também **não** tratar `7 × 300` como prova de produção: réplicas compartilham RDS (pool, WAL, índices, locks, rede) — o número no EKS pode ser **maior** que 7.

Lacunas que ainda invalidam fechar o SLO no alvo:

| Lacuna | Por que invalida a conclusão de produção |
| --- | --- |
| 1 processo / LocalStack | Sem contenda multi-pod no RDS |
| Sem SC-003 no drain 300k | Latência de consulta durante ingestão não medida nesse run |
| Escala linear hipotética | Gargalo costuma ser o banco, não a CPU do pod |
| Ambiente laptop | Rede/disco ≠ EKS + RDS Multi-AZ |

**Antes de afirmar capacidade de pico em produção**, executar no alvo (EKS + RDS):

1. Vários pods em paralelo (**≥ 7** no pico).
2. Ingestão e `GET /balances` **simultâneos**.
3. Carga **sustentada 10–15 minutos**.
4. Distribuição de contas/eventos da especificação do desafio.
5. Cenários de backlog-drain, saturação de DB e recuperação de falha.

**Ponto de partida operacional:** 3 réplicas (HA) + **KEDA** (`min=3` / `max=15`, trigger SQS + CPU auxiliar) + PDB `minAvailable=2`; no pico de fila, esperar **≥ 7** pods se cada um se aproximar de ~300 EPS. CPU HPA sozinho **não** acompanha backlog SQS. Manifestos: `deploy/k8s/account-service.yaml`, `deploy/k8s/keda-scaledobject.yaml`. Detalhe: [arquitetura-aws-e-pipeline.md](arquitetura-aws-e-pipeline.md) §5.

---



## 8. Referências

- [Modelo de dados](modelo-de-dados.md)
- [Fluxos principais](fluxos-principais.md)
- [Arquitetura AWS e pipeline](arquitetura-aws-e-pipeline.md)
- [Validação de performance](../deploy/perf/README.md)


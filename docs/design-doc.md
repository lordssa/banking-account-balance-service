# Design Doc — Account Balance Service

**Status:** Implementado (escopo do desafio)  
**Autores:** Time account-service  
**Última atualização:** 2026-08-03  
**Audiência:** engenharia, arquitetura e revisores do desafio

---

## 1. Contexto

Instituições financeiras precisam expor o **saldo corrente** de contas a partir de eventos publicados por um sistema de origem (ledger/processamento financeiro). A entrega via **SQS Standard** é *at-least-once*: o consumidor pode receber duplicatas e mensagens fora de ordem.

Este serviço:

1. Consome eventos da fila `transacoes-financeiras-processadas`.
2. Materializa um **snapshot autoritativo** de saldo por conta.
3. Expõe `GET /balances/{accountId}` com leitura apenas do snapshot durável.

O saldo no payload é **autoritativo** — o serviço **não** reconstrói saldo somando créditos e débitos.

---

## 2. Objetivos (Goals)

| # | Objetivo |
|---|----------|
| G1 | Consulta de saldo correta sob duplicata, atraso e ordem invertida |
| G2 | Idempotência por `transaction_id` (primeira decisão vence) |
| G3 | *Latest-wins* por `source_timestamp` (microssegundo), não por ordem de chegada |
| G4 | Isolamento de eventos inválidos / conflitos / falhas permanentes com auditoria (journal) |
| G5 | Arquitetura limpa (domínio sem Spring/AWS/JPA) e schema versionado (Flyway) |
| G6 | Observabilidade mínima: health, métricas de ingestão/DB/consulta |
| G7 | Execução local reproduzível (Postgres + LocalStack + Compose) |

---

## 3. Não-objetivos (Non-goals)

Devido ao **escopo limitado** e à **restrição de tempo** do desafio, os itens abaixo **não foram implementados** neste entregável. Permanecem como extensões naturais para produção.

### 3.1 Autenticação e autorização

**Não implementado** para a API pública de saldo (assume-se perímetro institucional) e **não implementado de fato** para journal/replay (apenas *deny-by-default* + auditoria de tentativa).

**Problemas que resolveriam:**

- Impedir consulta de saldo por cliente/sistema não autorizado.
- Separar papéis: operador de suporte (leitura de journal) vs. engenharia (replay) vs. sistemas de produto (só saldo).
- Atender requisitos de *least privilege*, auditoria de *who-did-what* e conformidade (LGPD/acesso interno).
- Evitar que endpoints `/internal/journal/**` sejam acionáveis sem identidade forte (hoje o subject é placeholder).

**Direção típica:** OIDC/JWT no edge (API Gateway / Mesh), RBAC no serviço, *service accounts* via IRSA para jobs internos.

### 3.2 Dead Letter Queue (DLQ) de broker

**Não há fila DLQ SQS configurada/consumida.** O isolamento de *poison* é feito **na aplicação**: após `maxReceiveCount`, o evento é journalizado como `PERMANENTLY_FAILED` e a mensagem é ACKada (removida da fila primária).

**Problemas que uma DLQ de broker resolveria:**

- Separar fisicamente mensagens irrecuperáveis da fila quente (backlog/visibilidade).
- Permitir *ops* reprocessarem envelopes brutos sem acoplar ao schema do journal.
- Alarmística simples de “profundidade da DLQ” no CloudWatch.
- Reduzir risco de esgotar *visibility* / *receive count* de forma opaca quando o app estiver fora.

**Trade-off aceito:** journal Postgres como *quarantine store* (consultável, correlacionável) em vez de DLQ SQS, alinhado ao escopo do desafio.

### 3.3 BDD (Behavior-Driven Development)

**Não há suíte Cucumber/Gherkin.** A especificação usa cenários Given/When/Then em texto; a execução é via testes JUnit/AssertJ/Testcontainers/ArchUnit.

**Problemas que BDD poderia resolver:**

- Linguagem compartilhada negócio ↔ engenharia com `.feature` versionados.
- Living documentation executável ligada a critérios de aceite do produto.
- Redução de ambiguidade em regras de conflito/idempotência para stakeholders não-técnicos.

**Trade-off aceito:** cobertura automatizada forte em código (incluindo ArchUnit e ITs) sem ferramenta BDD dedicada.

### 3.4 Outros não-objetivos

- Gateway de API / BFF / UI.
- Multi-região ativa-ativa.
- Cache distribuído como fonte da verdade.
- Replay operacional completo de journal (endpoint existe, implementação negada / *not implemented*).
- Regras de produto ainda `[NEEDS CLARIFICATION]` (retenção, moeda cruzada, conta inativa, etc.).

---

## 4. Arquitetura lógica

```
┌─────────────────┐     ┌──────────────────┐
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

**Clean / Hexagonal Architecture:** `domain` e `application` não dependem de Spring, AWS SDK, JPA ou Jackson. Ver [ADR-002](../specs/001-account-balance-query/adr/ADR-002.md).

**Stack:** Java 25, Spring Boot 4, PostgreSQL 16, Flyway, AWS SDK v2 SQS, Micrometer/OTel, virtual threads + semáforo de concorrência.

---

## 5. Decisões principais e trade-offs

### 5.1 Banco de dados — PostgreSQL ([ADR-001](../specs/001-account-balance-query/adr/ADR-001.md))

| Alternativa | Por que não |
|-------------|-------------|
| DynamoDB como SoR | Modelo de conflito/CAS por timestamp e journal relacional ficam mais caros; transações multi-item limitadas |
| Redis como SoR | Volatilidade / perda sob failover; inadequado como verdade financeira |
| Somente event store + projeção sob demanda | Consulta p95 sofreria; desafio pede snapshot corrente |

**Decisão:** Postgres (local) / RDS Multi-AZ (produção) como store autoritativo do snapshot, idempotência, journal e conflitos.

### 5.2 Algoritmo *latest-wins* ([ADR-002](../specs/001-account-balance-query/adr/ADR-002.md))

- Upsert condicional no banco:  
  `ON CONFLICT (account_id) DO UPDATE … WHERE source_timestamp < EXCLUDED.source_timestamp`
- Evita *read-modify-write* na aplicação (sujeito a corrida).
- Híbrido JDBC (escritas CAS) + Spring Data JPA (leituras simples).

### 5.3 Idempotência

- PK `processed_transaction.transaction_id`.
- Primeira inserção fixa o `first_outcome`; reentrega → `DUPLICATE` sem nova transição de saldo.

### 5.4 Timestamp igual ([ADR-004](../specs/001-account-balance-query/adr/ADR-004.md))

- Dois `transaction_id` distintos com mesmo `(account_id, source_timestamp)` → `CONFLICTING`.
- Snapshot **não** muda por desempate de chegada.
- Índice único parcial impede dois “vencedores” não-conflitantes.

### 5.5 ACK / retry ([ADR-005](../specs/001-account-balance-query/adr/ADR-005.md))

- `DeleteMessage` **somente** após commit durável (sucesso, duplicata, conflito isolado, inválido, esgotamento).
- Falha transitória → sem ACK → reentrega por *visibility timeout*.
- Esgotamento (`ApproximateReceiveCount` ≥ `maxReceiveCount`) → journal `PERMANENTLY_FAILED` + ACK (sem DLQ de broker neste escopo).

### 5.6 Concorrência ([ADR-006](../specs/001-account-balance-query/adr/ADR-006.md), [ADR-007](../specs/001-account-balance-query/adr/ADR-007.md))

- Virtual threads + semáforo (`SQS_MAX_CONCURRENT`) alinhado ao pool Hikari.
- Recebe no máximo `min(10, permits)` mensagens; sobras devolvem *visibility* 0 (não ficam “presas” invisíveis).

### 5.7 Cache ([ADR-008](../specs/001-account-balance-query/adr/ADR-008.md))

- MVP: leitura PK direta no snapshot.
- Sem UNLOGGED / Redis até prova de necessidade por benchmark.

### 5.8 Observabilidade ([ADR-009](../specs/001-account-balance-query/adr/ADR-009.md))

- Micrometer + Actuator; OTLP opcional.
- Falha de export **não** deve bloquear ingestão/consulta.

---

## 6. Riscos e mitigações

| Risco | Mitigação |
|-------|-----------|
| Poison message trava a fila | Isolamento permanente + ACK; journal consultável |
| Deploy ruim afeta 100% dos clientes | Canary / rolling + readiness DB ([doc AWS](arquitetura-aws-e-pipeline.md)) |
| Corrida equal-timestamp | Índice único parcial + outcome `CONFLICTING` |
| Ambiguidade de commit vs ACK | Journal idempotente por `attempt_key`; reprocessamento seguro |

---

## 7. Métricas de sucesso (desafio)

- Ingestão de referência ≥ 2.000 eventos/s (ambiente controlado).
- Consulta: p95 ≤ 100 ms, p99 ≤ 250 ms (carga de referência).
- Cobertura de testes / ArchUnit verdes no CI local (`mvn test` / `verify`).

---

## 8. Referências

- [Modelo de dados](modelo-de-dados.md)
- [Fluxos principais](fluxos-principais.md)
- [Arquitetura AWS e pipeline](arquitetura-aws-e-pipeline.md)
- [plan.md](../specs/001-account-balance-query/plan.md)
- [research.md](../specs/001-account-balance-query/research.md)
- [Índice de ADRs](../specs/001-account-balance-query/adr/README.md)

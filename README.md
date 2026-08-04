# Account Balance Service

Serviço Spring Boot que consome eventos autoritativos de saldo de conta a partir do AWS SQS (entrega *at-least-once*) e expõe o saldo atual via HTTP.

**Ideias centrais**

- O saldo em cada evento é **autoritativo** — o serviço **não** soma créditos/débitos.
- Vence o evento mais recente pelo **timestamp da transação** (precisão de microssegundo), não pela ordem de chegada.
- Reentregas são idempotentes; eventos mais antigos são *stale*; timestamps iguais geram conflito.
- Consultas de saldo leem apenas o snapshot durável.

## Documentação

| Documento | Conteúdo |
|-----------|----------|
| [Design Doc](docs/design-doc.md) | Objetivos, não-objetivos, decisões e trade-offs |
| [Modelo de dados](docs/modelo-de-dados.md) | Tabelas, restrições e DER |
| [Arquitetura AWS e pipeline](docs/arquitetura-aws-e-pipeline.md) | Topologia de produção e estratégia de deploy |
| [Fluxos principais](docs/fluxos-principais.md) | Ingestão, consulta, conflitos e isolamento |

## ADRs (Architecture Decision Records)

| ADR | Título |
|-----|--------|
| [ADR-001](specs/001-account-balance-query/adr/ADR-001.md) | PostgreSQL como store autoritativo de saldo |
| [ADR-002](specs/001-account-balance-query/adr/ADR-002.md) | Algoritmo atômico *latest-event-wins* |
| [ADR-003](specs/001-account-balance-query/adr/ADR-003.md) | Estratégia de idempotência por transação |
| [ADR-004](specs/001-account-balance-query/adr/ADR-004.md) | Política de conflito por timestamp igual |
| [ADR-005](specs/001-account-balance-query/adr/ADR-005.md) | ACK SQS, retry e isolamento de poison |
| [ADR-006](specs/001-account-balance-query/adr/ADR-006.md) | Virtual threads e concorrência limitada |
| [ADR-007](specs/001-account-balance-query/adr/ADR-007.md) | HikariCP e orçamento de conexões |
| [ADR-008](specs/001-account-balance-query/adr/ADR-008.md) | Cache e avaliação de tabelas UNLOGGED |
| [ADR-009](specs/001-account-balance-query/adr/ADR-009.md) | Coleta e exportação OpenTelemetry |
| [ADR-010](specs/001-account-balance-query/adr/ADR-010.md) | Topologia de produção EKS + RDS |
| [ADR-011](specs/001-account-balance-query/adr/ADR-011.md) | Entrega progressiva e rollback |

Índice: [specs/001-account-balance-query/adr/README.md](specs/001-account-balance-query/adr/README.md).

## Pré-requisitos

| Ferramenta | Versão / notas |
|------------|----------------|
| JDK | **25** |
| Maven | Via wrapper (`./mvnw` / `mvnw.cmd`) — instalação global não é obrigatória |
| Docker Desktop | Necessário para Testcontainers e stack Compose opcional |
| (Opcional) curl / k6 | Checagens manuais da API e scripts de carga |

Portas locais livres ao subir dependências: `5432` (Postgres), `4566` (LocalStack), `8080` (app), `4318` (OTel, opcional).

## Clone e build

```bash
git clone <repo-url>
cd account-service

# Windows
mvnw.cmd -DskipTests package

# Linux / macOS
./mvnw -DskipTests package
```

## Configuração

Defaults em `src/main/resources/application.yaml`. Sobrescreva com variáveis de ambiente:

| Variável | Default | Propósito |
|----------|---------|-----------|
| `SERVER_PORT` | `8080` | Porta HTTP |
| `DB_URL` | `jdbc:postgresql://localhost:5432/account` | URL do datasource |
| `DB_USER` / `DB_PASSWORD` | `account` / `account` | Credenciais do banco |
| `DB_POOL_SIZE` | `20` | Tamanho máximo do pool HikariCP |
| `SQS_ENABLED` | `true` | Habilita o consumer SQS |
| `SQS_QUEUE_URL` | _(vazio)_ | URL da fila |
| `AWS_ENDPOINT_OVERRIDE` | _(vazio)_ | Ex.: `http://localhost:4566` para LocalStack |
| `AWS_REGION` | `sa-east-1` | Região AWS |
| `SQS_MAX_CONCURRENT` | `16` | Máximo de mensagens em processamento |
| `SQS_MAX_RECEIVE_COUNT` | `5` | Limite de tentativas antes de isolamento permanente |
| `OTEL_METRICS_ENABLED` | `false` | Exportação de métricas OTLP |

O schema é aplicado automaticamente no startup pelo **Flyway** (`ddl-auto=validate`).

## Execução local (caminho recomendado no dia a dia)

### 1. Subir Postgres

```bash
docker run --name account-pg -d \
  -e POSTGRES_DB=account \
  -e POSTGRES_USER=account \
  -e POSTGRES_PASSWORD=account \
  -p 5432:5432 \
  postgres:16-alpine
```

### 2. Subir a aplicação

```bash
# Windows
mvnw.cmd spring-boot:run

# Linux / macOS
./mvnw spring-boot:run
```

Health:

```bash
curl -sS http://localhost:8080/actuator/health
curl -sS http://localhost:8080/actuator/health/readiness
curl -sS http://localhost:8080/actuator/health/liveness
```

### 3. Consultar saldo

```bash
# Conta conhecida (após ingestão / seed) — 200
curl -sS "http://localhost:8080/balances/<account-uuid>"
```

Formato de resposta de sucesso:

```json
{
  "accountId": "...",
  "ownerId": "...",
  "amount": "100.25",
  "currency": "BRL",
  "lastUpdatedAt": "2023-11-14T22:13:20.000001Z"
}
```

OpenAPI: `src/main/resources/static/openapi-balances.yaml`.

### 4. Opcional — ingestão via LocalStack SQS

O `docker-compose.yml` na raiz sobe LocalStack + o `message-generator` do desafio (fila `transacoes-financeiras-processadas`).

```bash
docker compose up -d localstack
# aguardar healthy e, opcionalmente:
docker compose up message-generator
```

Exemplo com SQS habilitado:

```bash
# Windows PowerShell
$env:SQS_ENABLED="true"
$env:AWS_ENDPOINT_OVERRIDE="http://localhost:4566"
$env:AWS_REGION="sa-east-1"
$env:SQS_QUEUE_URL="http://localhost:4566/000000000000/transacoes-financeiras-processadas"
mvnw.cmd spring-boot:run
```

```bash
# Linux / macOS
export SQS_ENABLED=true
export AWS_ENDPOINT_OVERRIDE=http://localhost:4566
export AWS_REGION=sa-east-1
export SQS_QUEUE_URL=http://sqs.sa-east-1.localhost.localstack.cloud:4566/000000000000/transacoes-financeiras-processadas
./mvnw spring-boot:run
```

### 5. Opcional — stack Compose completa (app + DB + LocalStack + OTel)

```bash
./mvnw -DskipTests package
docker compose -f deploy/compose/docker-compose.yml up --build
```

## Testes

O Docker precisa estar em execução para testes de integração baseados em Testcontainers.

```bash
# Todos os testes
./mvnw test          # ou: mvnw.cmd test

# Feedback mais rápido (sem containers)
./mvnw "-Dtest=DomainValidationTest,IngestBalanceEventUseCaseTest,CleanArchitectureTest" test
```

Cobertura: JaCoCo (check em `verify`). Mutação: PIT nos pacotes de domínio/casos de uso (`./mvnw org.pitest:pitest-maven:mutationCoverage`).

## Endpoints úteis

| Endpoint | Propósito |
|----------|-----------|
| `GET /balances/{accountId}` | Snapshot de saldo atual |
| `GET /actuator/health` | Health agregado |
| `GET /actuator/health/readiness` | Readiness (inclui DB) |
| `GET /actuator/health/liveness` | Liveness |
| `GET /actuator/metrics` | Métricas Micrometer |
| `GET/POST /internal/journal/...` | APIs de journal (*deny-by-default*; tentativas são auditadas) |

## Performance (opcional)

Com ingestão ativa:

```bash
k6 run -e BASE_URL=http://localhost:8080 -e ACCOUNT_ID=<uuid> deploy/perf/k6-balance-query.js
```

Metas de referência: ≥ 2.000 eventos/s na ingestão; consulta p95 ≤ 100 ms, p99 ≤ 250 ms.


# Account Balance Service

Serviço Spring Boot que consome eventos autoritativos de saldo de conta a partir do AWS SQS (entrega *at-least-once*) e expõe o saldo atual via HTTP.

**Ideias centrais**

- O saldo em cada evento é **autoritativo** — o serviço **não** soma créditos/débitos.
- Vence o evento mais recente pelo **timestamp da transação** (precisão de microssegundo), não pela ordem de chegada.
- Reentregas são idempotentes; eventos mais antigos são *stale*; timestamps iguais geram conflito.
- Consultas de saldo leem apenas o snapshot durável.

## Documentação


| Documento                                                        | Conteúdo                                        |
| ---------------------------------------------------------------- | ----------------------------------------------- |
| [Design Doc](docs/design-doc.md)                                 | Objetivos, não-objetivos, decisões e trade-offs |
| [Modelo de dados](docs/modelo-de-dados.md)                       | Tabelas, restrições e DER                       |
| [Arquitetura AWS e pipeline](docs/arquitetura-aws-e-pipeline.md) | Topologia de produção e estratégia de deploy    |
| [Fluxos principais](docs/fluxos-principais.md)                   | Ingestão, consulta, conflitos e isolamento      |
| [Scripts DLQ](deploy/scripts/README.md)                          | Init LocalStack, inspect depth, redrive         |
| [Terraform SQS](deploy/terraform/README.md)                      | Fonte + DLQ + IAM + alarmes                     |




## Pré-requisitos


| Ferramenta           | Versão / notas                                                            |
| -------------------- | ------------------------------------------------------------------------- |
| JDK                  | **25**                                                                    |
| Maven                | Via wrapper (`./mvnw` / `mvnw.cmd`) — instalação global não é obrigatória |
| Docker Desktop       | Necessário para Testcontainers e stack Compose opcional                   |
| (Opcional) curl / k6 | Checagens manuais e suite de carga em `deploy/perf` (`k6-ab.sh` ≈ Apache Bench) |


Portas locais livres ao subir dependências: `5432` (Postgres), `4566` (LocalStack), `8080` (app), `4318` (OTel, opcional).

## Clone e build

```bash
git clone git@github.com:lordssa/banking-account-balance-service.git
cd account-service

# Windows
mvnw.cmd -DskipTests package

# Linux / macOS
./mvnw -DskipTests package
```



## Configuração

Defaults em `src/main/resources/application.yaml`. Sobrescreva com variáveis de ambiente:


| Variável                       | Default                                    | Propósito                                                                                          |
| ------------------------------ | ------------------------------------------ | -------------------------------------------------------------------------------------------------- |
| `SERVER_PORT`                  | `8080`                                     | Porta HTTP                                                                                         |
| `DB_URL`                       | `jdbc:postgresql://localhost:5432/account` | URL do datasource                                                                                  |
| `DB_USER` / `DB_PASSWORD`      | `account` / `account`                      | Credenciais do banco                                                                               |
| `DB_POOL_SIZE`                 | `20`                                       | Tamanho máximo do pool HikariCP                                                                    |
| `SQS_ENABLED`                  | `true`                                     | Habilita o consumer SQS                                                                            |
| `SQS_QUEUE_URL`                | *(URL LocalStack da fila fonte)*           | URL da fila fonte                                                                                  |
| `SQS_EXPECTED_DLQ_ARN`         | *(ARN LocalStack da DLQ)*                  | ARN esperado da DLQ (validação de topologia)                                                       |
| `SQS_MAX_RECEIVE_COUNT`        | `5`                                        | Limiar esperado do `RedrivePolicy` do broker (métricas/validação — **não** autoriza DeleteMessage) |
| `SQS_TOPOLOGY_VALIDATION_MODE` | `observe`                                  | `observe` | `enforce` | `off`                                                                      |
| `AWS_ENDPOINT_OVERRIDE`        | *(vazio)*                                  | Ex.: `http://localhost:4566` para LocalStack                                                       |
| `AWS_REGION`                   | `sa-east-1`                                | Região AWS                                                                                         |
| `SQS_MAX_CONCURRENT`           | `16`                                       | Máximo de mensagens em processamento                                                               |
| `OTEL_METRICS_ENABLED`         | `false`                                    | Exportação de métricas OTLP                                                                        |


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
  "id": "5b19c8b6-0cc4-4c72-a989-0c2ee15fa975",
  "owner": "315e3cfe-f4af-4cd2-b298-a449e614349a",
  "balance": {
    "amount": 183.12,
    "currency": "BRL"
  },
  "updated_at": "2025-07-05T18:04:13.433-03:00"
}
```

OpenAPI: `src/main/resources/static/openapi-balances.yaml`.

### 4. Opcional — ingestão via LocalStack SQS

O `docker-compose.yml` na raiz sobe LocalStack + o `message-generator` do desafio (cria a fila fonte `transacoes-financeiras-processadas`).

**Obrigatório para isolamento DLQ local:** depois do LocalStack saudável (e preferencialmente após a fila fonte existir), execute o script de init. Sem isso a DLQ e o `RedrivePolicy` **não** são criados — o consumer pode falhar a validação de topologia e mensagens poison não vão para a DLQ.

```bash
docker compose up -d localstack
# aguardar healthy; opcionalmente criar/popular a fonte:
docker compose up message-generator

# Windows (Git Bash / WSL) ou Linux / macOS — cria DLQ + anexa RedrivePolicy à fonte
export AWS_ENDPOINT_OVERRIDE=http://localhost:4566
export AWS_REGION=sa-east-1
export AWS_ACCESS_KEY_ID=test
export AWS_SECRET_ACCESS_KEY=test
bash deploy/scripts/localstack-init-queues.sh
```

Outros scripts (inspect depth, redrive controlado): [deploy/scripts/README.md](deploy/scripts/README.md).

Exemplo com SQS habilitado:

```bash
# Windows PowerShell
$env:SQS_ENABLED="true"
$env:AWS_ENDPOINT_OVERRIDE="http://localhost:4566"
$env:AWS_REGION="sa-east-1"
$env:SQS_QUEUE_URL="http://localhost:4566/000000000000/transacoes-financeiras-processadas"
$env:SQS_EXPECTED_DLQ_ARN="arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq"
$env:SQS_MAX_RECEIVE_COUNT="5"
mvnw.cmd spring-boot:run
```

```bash
# Linux / macOS
export SQS_ENABLED=true
export AWS_ENDPOINT_OVERRIDE=http://localhost:4566
export AWS_REGION=sa-east-1
export SQS_QUEUE_URL=http://sqs.sa-east-1.localhost.localstack.cloud:4566/000000000000/transacoes-financeiras-processadas
export SQS_EXPECTED_DLQ_ARN=arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq
export SQS_MAX_RECEIVE_COUNT=5
./mvnw spring-boot:run
```



### 5. Opcional — stack Compose completa (app + DB + LocalStack + OTel + load)

O `deploy/compose/docker-compose.yml` inclui Postgres, LocalStack (com `localstack-init-queues.sh` no ready.d → DLQ + `RedrivePolicy`), OTel, o mesmo `message-generator` da raiz (300k), e `account-service`.

```bash
./mvnw -DskipTests package
docker compose -f deploy/compose/docker-compose.yml up --build
# só popular a fila (mesmo serviço da raiz):
docker compose -f deploy/compose/docker-compose.yml up message-generator
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


| Endpoint                         | Propósito                                                                                                                                                        |
| -------------------------------- | ---------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| `GET /balances/{accountId}`      | Snapshot de saldo atual                                                                                                                                          |
| `GET /actuator/health`           | Health agregado                                                                                                                                                  |
| `GET /actuator/health/readiness` | Readiness (DB + topologia SQS quando ingestão habilitada)                                                                                                        |
| `GET /actuator/health/liveness`  | Liveness                                                                                                                                                         |
| `GET /actuator/metrics`          | Métricas Micrometer                                                                                                                                              |
| `GET/POST /internal/journal/...` | APIs de journal (*deny-by-default*; tentativas são auditadas). `GET /internal/journal/ingest-span` para EPS de perf (local: `JOURNAL_ALLOW_ANONYMOUS_READ=true`) |




## Performance

Metas (design-doc §7 / SC-003):

- Ingestão ≥ **2.000 eventos/s** — preferir `GET /internal/journal/ingest-span` (`receivedAt`); senão span `MIN/MAX(first_processed_at)` no Postgres; fallback parede first-visible→drain. Publisher msg/s é separado.
- Consulta **server-side** `http.server.requests` p95 ≤ 100 ms / p99 ≤ 250 ms **enquanto a ingestão está ativa** (k6 sobrepõe o consume)
- Queries de carga exigem **HTTP 200** (404 não passa)
- Correção dos saldos finais + fila drenada + durabilidade

Procedimento e scripts: `[deploy/perf/README.md](deploy/perf/README.md)`.

Carga estilo Apache Bench (concurrency + total de requests + URL):

```bash
# Git Bash — equivalente a: ab -c 10 -n 20 URL
./deploy/perf/k6-ab.sh -c 10 -n 20 'http://localhost:8080/balances/<account-uuid>'

# Ou direto com env:
URL='http://localhost:8080/internal/journal/accounts/<uuid>' C=10 N=20 \
  k6 run deploy/perf/k6-ab-like.js
```

```bash
export BASE_URL=http://localhost:8080
export AWS_ENDPOINT_OVERRIDE=http://localhost:4566
export SQS_QUEUE_URL=http://localhost:4566/000000000000/transacoes-financeiras-processadas
bash deploy/perf/run-benchmark.sh
```

Resultados em `deploy/perf/results/run-<timestamp>.md`.
# Scripts operacionais da DLQ

| Script | Finalidade |
|--------|------------|
| `localstack-init-queues.sh` | Cria a DLQ e anexa `RedrivePolicy` à fila fonte (cria a fonte só se ela não existir) |
| `inspect-dlq-depth.sh` | Somente atributos da fila — **nunca registra payloads** |
| `start-dlq-redrive.sh` | `StartMessageMoveTask` manual (padrão 10 msg/s); exige digitar `YES` |
| `list-cancel-dlq-redrive.sh` | Lista / cancela tarefas de move |

## Regras

- Recuperação é **deny-by-default**; use o papel IAM de recovery, não o IRSA do pod da aplicação.
- Não inicie redrive automaticamente quando a profundidade da DLQ for > 0.
- No máximo uma tarefa de move ativa por DLQ.
- O redrive nativo do SQS não filtra nem modifica mensagens.
- Não está acoplado a `POST /internal/journal/replay`.

## Fidelidade do LocalStack

- `ApproximateAgeOfOldestMessage` frequentemente não é suportado no LocalStack (`InvalidAttributeName`). Use `inspect-dlq-depth.sh` para profundidade visível/invisível; idade do mais antigo fica para CloudWatch em AWS.
- `StartMessageMoveTask` pode estar incompleto no LocalStack. Documente lacunas; valide move tasks em um sandbox AWS. Não simule semânticas diferentes em silêncio.

## Campos de auditoria da recuperação

Registre: identidade do ator, horário de início (UTC), ARNs de origem/destino, maxPerSecond, correlation id, resultado/motivo do cancelamento.

# Topologia SQS — Account Balance Service (feature 002-broker-dlq-recovery)

Provisiona:

- Fila fonte + DLQ com `RedrivePolicy` (`maxReceiveCount` padrão 5)
- Retenção da DLQ de 14 dias; retenção da fonte ≤ DLQ
- Políticas IAM de menor privilégio (consumidor vs recuperação vs **KEDA scaler**)
- Alarmes CloudWatch (profundidade da DLQ, idade da DLQ ≥ 12 dias, backlog da fonte)

```bash
cd deploy/terraform
terraform init
terraform plan -var="environment=dev"
```

Conecte os outputs ao Kubernetes / `SQS_QUEUE_URL` e `SQS_EXPECTED_DLQ_ARN`.
Anexe `keda_sqs_scaler_policy_arn` à IRSA do **keda-operator** (não ao pod da aplicação).
A aplicação **não** deve criar filas. Autoscaling: `deploy/k8s/keda-scaledobject.yaml`.


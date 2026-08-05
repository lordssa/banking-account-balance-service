#!/usr/bin/env bash
# Inspect DLQ depth without printing message bodies.
# ApproximateAgeOfOldestMessage is AWS/CloudWatch-native; LocalStack often rejects it
# (InvalidAttributeName) — depth metrics still work without it.
set -euo pipefail

ENDPOINT="${AWS_ENDPOINT_OVERRIDE:-http://localhost:4566}"
REGION="${AWS_REGION:-sa-east-1}"
DLQ_URL="${DLQ_URL:-http://sqs.sa-east-1.localhost.localstack.cloud:4566/000000000000/transacoes-financeiras-processadas-dlq}"

AWS=(aws --endpoint-url="$ENDPOINT" --region "$REGION")

echo "[dlq] depth (no payloads) queue=$DLQ_URL"
"${AWS[@]}" sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names ApproximateNumberOfMessages ApproximateNumberOfMessagesNotVisible

if ! "${AWS[@]}" sqs get-queue-attributes \
  --queue-url "$DLQ_URL" \
  --attribute-names ApproximateAgeOfOldestMessage 2>/dev/null; then
  echo "[dlq] ApproximateAgeOfOldestMessage unavailable (common on LocalStack — use CloudWatch in AWS)"
fi

#!/usr/bin/env bash
# Ensure DLQ + RedrivePolicy for LocalStack.
# Mounted by deploy/compose/docker-compose.yml (ready.d). Also usable manually after
# root docker-compose.yml. Creates the source queue if missing; always attaches RedrivePolicy.
set -euo pipefail

ENDPOINT="${AWS_ENDPOINT_OVERRIDE:-http://localhost:4566}"
REGION="${AWS_REGION:-sa-east-1}"
SOURCE_NAME="${SOURCE_QUEUE_NAME:-transacoes-financeiras-processadas}"
DLQ_NAME="${DLQ_NAME:-transacoes-financeiras-processadas-dlq}"
MAX_RECEIVE="${SQS_MAX_RECEIVE_COUNT:-5}"
ACCOUNT_ID="000000000000"

aws_sqs() {
  aws --endpoint-url="$ENDPOINT" --region "$REGION" sqs "$@"
}

# Embed a JSON object as a string value inside another JSON object.
json_string_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

echo "[init] Ensuring DLQ $DLQ_NAME"
DLQ_URL=$(aws_sqs get-queue-url --queue-name "$DLQ_NAME" --query 'QueueUrl' --output text 2>/dev/null || true)
if [[ -z "${DLQ_URL:-}" || "$DLQ_URL" == "None" ]]; then
  DLQ_URL=$(aws_sqs create-queue --queue-name "$DLQ_NAME" \
    --attributes '{"MessageRetentionPeriod":"1209600"}' \
    --query 'QueueUrl' --output text)
  echo "[init] Created DLQ $DLQ_URL"
else
  echo "[init] DLQ already exists $DLQ_URL"
fi

DLQ_ARN=$(aws_sqs get-queue-attributes --queue-url "$DLQ_URL" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

REDRIVE=$(printf '{"deadLetterTargetArn":"%s","maxReceiveCount":"%s"}' "$DLQ_ARN" "$MAX_RECEIVE")
REDRIVE_ESC=$(json_string_escape "$REDRIVE")
SOURCE_ATTRS=$(printf '{"VisibilityTimeout":"60","ReceiveMessageWaitTimeSeconds":"10","MessageRetentionPeriod":"345600","RedrivePolicy":"%s"}' "$REDRIVE_ESC")

echo "[init] Resolving source queue $SOURCE_NAME"
SOURCE_URL=$(aws_sqs get-queue-url --queue-name "$SOURCE_NAME" --query 'QueueUrl' --output text 2>/dev/null || true)
if [[ -z "${SOURCE_URL:-}" || "$SOURCE_URL" == "None" ]]; then
  echo "[init] Source queue missing — creating $SOURCE_NAME with RedrivePolicy"
  SOURCE_URL=$(aws_sqs create-queue --queue-name "$SOURCE_NAME" \
    --attributes "$SOURCE_ATTRS" \
    --query 'QueueUrl' --output text)
else
  echo "[init] Source queue already exists $SOURCE_URL — attaching RedrivePolicy"
  aws_sqs set-queue-attributes --queue-url "$SOURCE_URL" --attributes "$SOURCE_ATTRS"
fi

SOURCE_ARN=$(aws_sqs get-queue-attributes --queue-url "$SOURCE_URL" \
  --attribute-names QueueArn --query 'Attributes.QueueArn' --output text)

ALLOW=$(printf '{"redrivePermission":"byQueue","sourceQueueArns":["%s"]}' "$SOURCE_ARN")
ALLOW_ESC=$(json_string_escape "$ALLOW")
ALLOW_ATTRS=$(printf '{"RedriveAllowPolicy":"%s"}' "$ALLOW_ESC")
aws_sqs set-queue-attributes --queue-url "$DLQ_URL" --attributes "$ALLOW_ATTRS" || true

echo "SOURCE_QUEUE_URL=$SOURCE_URL"
echo "SQS_EXPECTED_DLQ_ARN=$DLQ_ARN"
echo "[init] Done (account $ACCOUNT_ID, maxReceiveCount=$MAX_RECEIVE)"

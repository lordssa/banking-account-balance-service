#!/usr/bin/env bash
# Start a rate-limited SQS message move from DLQ to source. Requires recovery IAM.
# Native StartMessageMoveTask cannot filter/modify messages.
set -euo pipefail

ENDPOINT="${AWS_ENDPOINT_OVERRIDE:-http://localhost:4566}"
REGION="${AWS_REGION:-sa-east-1}"
SOURCE_ARN="${SOURCE_QUEUE_ARN:-arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas}"
DLQ_ARN="${DLQ_ARN:-arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq}"
RATE="${MAX_PER_SECOND:-10}"
CORRELATION_ID="${CORRELATION_ID:-$(uuidgen 2>/dev/null || echo manual-$(date +%s))}"
ACTOR="${USER:-unknown}"

echo "About to StartMessageMoveTask"
echo "  actor=$ACTOR correlationId=$CORRELATION_ID rate=$RATE"
echo "  source=$DLQ_ARN -> destination=$SOURCE_ARN"
read -r -p "Type YES to continue: " confirm
[[ "$confirm" == "YES" ]] || { echo "Aborted"; exit 1; }

AWS=(aws --region "$REGION")
if [[ -n "$ENDPOINT" ]]; then
  AWS+=(--endpoint-url="$ENDPOINT")
fi

"${AWS[@]}" sqs start-message-move-task \
  --source-arn "$DLQ_ARN" \
  --destination-arn "$SOURCE_ARN" \
  --max-number-of-messages-per-second "$RATE"

echo "Started at $(date -u +%Y-%m-%dT%H:%M:%SZ) actor=$ACTOR correlationId=$CORRELATION_ID"
echo "NOTE: LocalStack may not support StartMessageMoveTask faithfully — validate in AWS sandbox if needed."

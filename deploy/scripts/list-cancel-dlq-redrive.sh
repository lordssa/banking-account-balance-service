#!/usr/bin/env bash
# List or cancel SQS message move tasks for a DLQ.
set -euo pipefail

ENDPOINT="${AWS_ENDPOINT_OVERRIDE:-http://localhost:4566}"
REGION="${AWS_REGION:-sa-east-1}"
DLQ_ARN="${DLQ_ARN:-arn:aws:sqs:sa-east-1:000000000000:transacoes-financeiras-processadas-dlq}"
ACTION="${1:-list}" # list | cancel
TASK_HANDLE="${2:-}"

AWS=(aws --region "$REGION")
if [[ -n "$ENDPOINT" ]]; then
  AWS+=(--endpoint-url="$ENDPOINT")
fi

case "$ACTION" in
  list)
    "${AWS[@]}" sqs list-message-move-tasks --source-arn "$DLQ_ARN"
    ;;
  cancel)
    [[ -n "$TASK_HANDLE" ]] || { echo "Usage: $0 cancel <task-handle>"; exit 1; }
    "${AWS[@]}" sqs cancel-message-move-task --task-handle "$TASK_HANDLE"
    echo "Cancel requested at $(date -u +%Y-%m-%dT%H:%M:%SZ) actor=${USER:-unknown}"
    ;;
  *)
    echo "Usage: $0 list|cancel [task-handle]"
    exit 1
    ;;
esac

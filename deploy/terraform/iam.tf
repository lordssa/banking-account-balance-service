data "aws_iam_policy_document" "consumer_sqs" {
  statement {
    sid    = "SourceQueueConsume"
    effect = "Allow"
    actions = [
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:ChangeMessageVisibility",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.source.arn]
  }
}

data "aws_iam_policy_document" "recovery_sqs" {
  statement {
    sid    = "DlqRedrive"
    effect = "Allow"
    actions = [
      "sqs:StartMessageMoveTask",
      "sqs:CancelMessageMoveTask",
      "sqs:ListMessageMoveTasks",
      "sqs:ReceiveMessage",
      "sqs:DeleteMessage",
      "sqs:GetQueueAttributes",
    ]
    resources = [aws_sqs_queue.dlq.arn]
  }

  statement {
    sid       = "SourceSendForMove"
    effect    = "Allow"
    actions   = ["sqs:SendMessage"]
    resources = [aws_sqs_queue.source.arn]
  }
}

resource "aws_iam_policy" "consumer_sqs" {
  name   = "${var.environment}-account-service-sqs-consumer"
  policy = data.aws_iam_policy_document.consumer_sqs.json
}

resource "aws_iam_policy" "recovery_sqs" {
  name   = "${var.environment}-account-service-sqs-recovery"
  policy = data.aws_iam_policy_document.recovery_sqs.json
}

data "aws_iam_policy_document" "keda_sqs_scaler" {
  statement {
    sid    = "QueueDepthForAutoscaling"
    effect = "Allow"
    actions = [
      "sqs:GetQueueAttributes",
      "sqs:GetQueueUrl",
    ]
    resources = [aws_sqs_queue.source.arn]
  }
}

resource "aws_iam_policy" "keda_sqs_scaler" {
  name   = "${var.environment}-account-service-keda-sqs-scaler"
  policy = data.aws_iam_policy_document.keda_sqs_scaler.json
}

# IRSA role ARNs are environment-specific; attach policies to existing roles via variables in a later layer.
# Policy documents above are the least-privilege contracts for consumer vs recovery.

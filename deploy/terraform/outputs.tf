output "source_queue_url" {
  value = aws_sqs_queue.source.url
}

output "source_queue_arn" {
  value = aws_sqs_queue.source.arn
}

output "dlq_url" {
  value = aws_sqs_queue.dlq.url
}

output "dlq_arn" {
  value = aws_sqs_queue.dlq.arn
}

output "expected_max_receive_count" {
  value = var.max_receive_count
}

output "consumer_sqs_policy_arn" {
  value = aws_iam_policy.consumer_sqs.arn
}

output "recovery_sqs_policy_arn" {
  value = aws_iam_policy.recovery_sqs.arn
}

output "keda_sqs_scaler_policy_arn" {
  value       = aws_iam_policy.keda_sqs_scaler.arn
  description = "Attach to the KEDA operator IRSA role (GetQueueAttributes only)."
}

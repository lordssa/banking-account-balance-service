resource "aws_cloudwatch_metric_alarm" "dlq_nonempty" {
  alarm_name          = "${var.environment}-sqs-dlq-visible-gt-0"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = 0
  alarm_description   = "DLQ has visible messages (FR-019)"
  dimensions = {
    QueueName = aws_sqs_queue.dlq.name
  }
}

resource "aws_cloudwatch_metric_alarm" "dlq_expiry_risk" {
  alarm_name          = "${var.environment}-sqs-dlq-age-ge-12d"
  comparison_operator = "GreaterThanOrEqualToThreshold"
  evaluation_periods  = 1
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 300
  statistic           = "Maximum"
  threshold           = 1036800 # 12 days — ≤48h before 14-day retention
  alarm_description   = "DLQ oldest message approaching 14-day retention (FR-022)"
  dimensions = {
    QueueName = aws_sqs_queue.dlq.name
  }
}

resource "aws_cloudwatch_metric_alarm" "source_oldest_age" {
  alarm_name          = "${var.environment}-sqs-source-oldest-age"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 3
  metric_name         = "ApproximateAgeOfOldestMessage"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Maximum"
  threshold           = var.alarm_source_oldest_age_seconds
  alarm_description   = "Source queue oldest message age exceeds ingestion SLO"
  dimensions = {
    QueueName = aws_sqs_queue.source.name
  }
}

resource "aws_cloudwatch_metric_alarm" "source_visible_backlog" {
  alarm_name          = "${var.environment}-sqs-source-visible-high"
  comparison_operator = "GreaterThanThreshold"
  evaluation_periods  = 5
  metric_name         = "ApproximateNumberOfMessagesVisible"
  namespace           = "AWS/SQS"
  period              = 60
  statistic           = "Average"
  threshold           = 1000
  alarm_description   = "Source visible backlog elevated"
  dimensions = {
    QueueName = aws_sqs_queue.source.name
  }
}

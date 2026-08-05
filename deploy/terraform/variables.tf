# Terraform — Account Balance Service SQS topology

terraform {
  required_version = ">= 1.5.0"
  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = ">= 5.0"
    }
  }
}

provider "aws" {
  region = var.aws_region
}

variable "aws_region" {
  type    = string
  default = "sa-east-1"
}

variable "environment" {
  type        = string
  description = "Environment name used to parameterize resource names"
  default     = "dev"
}

variable "source_queue_name" {
  type    = string
  default = "transacoes-financeiras-processadas"
}

variable "dlq_name" {
  type    = string
  default = "transacoes-financeiras-processadas-dlq"
}

variable "max_receive_count" {
  type    = number
  default = 5
}

variable "dlq_retention_seconds" {
  type    = number
  default = 1209600 # 14 days
}

variable "source_retention_seconds" {
  type    = number
  default = 345600 # 4 days (<= DLQ)
}

variable "visibility_timeout_seconds" {
  type    = number
  default = 60
}

variable "receive_wait_time_seconds" {
  type    = number
  default = 10
}

variable "alarm_source_oldest_age_seconds" {
  type        = number
  description = "Source ApproximateAgeOfOldestMessage alarm threshold (ingestion SLO)"
  default     = 60
}

locals {
  source_name = "${var.environment}-${var.source_queue_name}"
  dlq_name    = "${var.environment}-${var.dlq_name}"
}

output "auto_scaling_group_name" {
  description = "Name of the default agent ASG"
  value       = module.buildkite_stack.auto_scaling_group_name
}

output "release_auto_scaling_group_name" {
  description = "Name of the release agent ASG"
  value       = module.buildkite_release_stack.auto_scaling_group_name
}

output "lambda_scaler_arn" {
  description = "ARN of the Lambda scaler function (default queue)"
  value       = module.buildkite_stack.scaler_lambda_function_arn
}

output "release_lambda_scaler_arn" {
  description = "ARN of the Lambda scaler function (release queue)"
  value       = module.buildkite_release_stack.scaler_lambda_function_arn
}

output "vpc_id" {
  description = "VPC ID where agents run (shared by every queue)"
  value       = aws_vpc.agents.id
}

output "dashboard_url" {
  description = "CloudWatch Dashboard URL"
  value       = "https://console.aws.amazon.com/cloudwatch/home?region=${var.region}#dashboards:name=buildkite-mockserver-infrastructure"
}

output "sns_topic_arn" {
  description = "SNS topic ARN for infrastructure alerts"
  value       = aws_sns_topic.buildkite_alerts.arn
}

output "ecr_public_repository_uri" {
  description = "ECR Public repository URI for MockServer Docker images"
  value       = aws_ecrpublic_repository.mockserver.repository_uri
}

output "dependency_cache_bucket" {
  description = "S3 bucket name for CI dependency caching"
  value       = aws_s3_bucket.dependency_cache.id
}

output "ecr_pull_through_cache_registry" {
  description = "ECR registry host that CI resolves Testcontainers images through (set MOCKSERVER_TEST_IMAGE_REGISTRY to this). Contains the account id, so exposed only as an output."
  value       = "${local.account_id}.dkr.ecr.${var.region}.amazonaws.com"
}

output "perf_auto_scaling_group_name" {
  description = "Name of the perf agent ASG"
  value       = module.buildkite_perf_stack.auto_scaling_group_name
}

output "perf_lambda_scaler_arn" {
  description = "ARN of the Lambda scaler function (perf queue)"
  value       = module.buildkite_perf_stack.scaler_lambda_function_arn
}

output "perf_xl_auto_scaling_group_name" {
  description = "Name of the perf-xl agent ASG"
  value       = module.buildkite_perf_xl_stack.auto_scaling_group_name
}

output "perf_xl_lambda_scaler_arn" {
  description = "ARN of the Lambda scaler function (perf-xl queue)"
  value       = module.buildkite_perf_xl_stack.scaler_lambda_function_arn
}

output "perf_results_bucket" {
  description = "S3 bucket name for performance-regression result history"
  value       = aws_s3_bucket.perf_results.id
}

output "default_instance_role_arn" {
  description = "IAM role ARN of the default-queue agent instance role (needed for cross-account binaries bucket policy)"
  value       = module.buildkite_stack.instance_role_arn
}

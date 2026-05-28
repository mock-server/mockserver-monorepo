provider "aws" {
  region  = var.region
  profile = "mockserver-build"
}

module "buildkite_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.8.0"

  stack_name            = "buildkite-mockserver"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "default"

  instance_types          = var.instance_types
  min_size                = var.min_size
  max_size                = var.max_size
  on_demand_percentage    = var.on_demand_percentage
  on_demand_base_capacity = 1

  agents_per_instance         = 1
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  managed_policy_arns         = [aws_iam_policy.read_build_secrets.arn, aws_iam_policy.ecr_public_push.arn]
}

module "buildkite_trigger_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.8.0"

  stack_name            = "buildkite-mockserver-trigger"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "trigger"

  instance_types          = var.trigger_instance_types
  min_size                = var.trigger_min_size
  max_size                = var.trigger_max_size
  on_demand_percentage    = 0
  on_demand_base_capacity = 0

  agents_per_instance         = 4
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  managed_policy_arns         = [aws_iam_policy.read_build_secrets.arn]
}

module "buildkite_release_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.8.0"

  stack_name            = "buildkite-mockserver-release"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "release"

  instance_types          = var.instance_types
  min_size                = var.release_min_size
  max_size                = var.release_max_size
  on_demand_percentage    = 100
  on_demand_base_capacity = 1

  agents_per_instance         = 1
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  managed_policy_arns = [
    aws_iam_policy.read_build_secrets.arn,
    aws_iam_policy.read_release_secrets.arn,
    aws_iam_policy.ecr_public_push.arn,
    aws_iam_policy.release_website_tfstate.arn,
  ]
}

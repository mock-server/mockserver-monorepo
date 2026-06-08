provider "aws" {
  region  = var.region
  profile = "mockserver-build"
}

# IAM policy ARNs constructed from the (statically-known) policy name + account
# id so that each module's `managed_policy_arns` is fully known at PLAN time.
#
# Passing the resource `.arn` directly (a computed attribute, unknown until
# apply) makes the elastic-ci-stack module's internal
# `for_each = toset(var.managed_policy_arns)` fail on a clean apply with
# "Invalid for_each argument" — the set keys can't be determined pre-apply.
# Referencing `.name` (known from config) instead of `.arn` still creates the
# dependency edge that orders policy creation before the module, while keeping
# the value static so `for_each` resolves at plan time.
locals {
  account_id = data.aws_caller_identity.current.account_id
  policy_arn = {
    read_build_secrets_default        = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.read_build_secrets_default.name}"
    read_build_secrets_release        = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.read_build_secrets_release.name}"
    read_release_secrets              = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.read_release_secrets.name}"
    read_dockerhub_secret             = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.read_dockerhub_secret.name}"
    read_dockerhub_release_secret     = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.read_dockerhub_release_secret.name}"
    read_buildkite_api_token          = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.read_buildkite_api_token.name}"
    read_buildkite_api_token_readonly = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.read_buildkite_api_token_readonly.name}"
    ecr_public_push                   = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.ecr_public_push.name}"
    perf_results                      = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.perf_results.name}"
    release_website_tfstate           = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.release_website_tfstate.name}"
    dependency_cache                  = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.dependency_cache.name}"
    imds_hardening                    = "arn:aws:iam::${local.account_id}:policy/${aws_iam_policy.imds_hardening.name}"
  }
}

module "buildkite_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.9.0"

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
  # Lower the IMDS hop limit to 1 at boot so untrusted PR-build containers cannot
  # reach IMDS and steal the agent instance-role credentials (imds-hardening.tf).
  bootstrap_script_url = local.imds_bootstrap_url
  managed_policy_arns = [
    local.policy_arn.read_build_secrets_default,
    local.policy_arn.read_dockerhub_secret,
    local.policy_arn.ecr_public_push,                   # snapshot Docker push (java-docker-push-snapshot.sh) runs on default queue
    local.policy_arn.dependency_cache,                  # read/write the CI dependency cache (Maven/npm/pip/Bundler builds)
    local.policy_arn.read_buildkite_api_token_readonly, # change detection (generate-pipeline.sh -> last-successful-commit.sh)
    local.policy_arn.imds_hardening,                    # fetch the boot script + lower own IMDS hop limit
  ]
}

module "buildkite_trigger_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.9.0"

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
  bootstrap_script_url        = local.imds_bootstrap_url
  managed_policy_arns = [
    local.policy_arn.read_buildkite_api_token,          # trigger-pipeline.sh creates/cancels child builds (write)
    local.policy_arn.read_buildkite_api_token_readonly, # perf-test-guard.sh change detection (read) runs on this queue
    local.policy_arn.imds_hardening,
  ]
}

# Dedicated PERFORMANCE queue. Reproducible numbers matter far more here than
# throughput, so unlike the default queue this is a SINGLE fixed-performance
# instance type (no mixed c5/c5a/m5 microarchitectures), 100% on-demand (no Spot
# reclaim mid-run), max ONE instance (two perf runs never contend), and
# scale-to-zero (min_size 0 — AGENTS.md hard constraint: zero idle cost). The
# daily run launches the box on demand and the ASG terminates it when idle.
module "buildkite_perf_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.9.0"

  stack_name            = "buildkite-mockserver-perf"
  buildkite_agent_token = var.buildkite_agent_token
  buildkite_queue       = "perf"

  instance_types          = var.perf_instance_types
  min_size                = var.perf_min_size # MUST stay 0 (scale to zero)
  max_size                = var.perf_max_size # 1 — never run two perf jobs at once
  on_demand_percentage    = 100               # on-demand only — a Spot reclaim would poison the baseline
  on_demand_base_capacity = 0                 # base 0 so it truly scales to zero

  agents_per_instance         = 1
  associate_public_ip_address = true
  imdsv2_tokens               = "required"
  bootstrap_script_url        = local.imds_bootstrap_url
  managed_policy_arns = [
    local.policy_arn.read_buildkite_api_token,          # Buildkite API token (commit guard / compare)
    local.policy_arn.read_buildkite_api_token_readonly, # read-only token for change-detection comparisons
    local.policy_arn.perf_results,                      # S3 results history bucket
    local.policy_arn.imds_hardening,
  ]
}

module "buildkite_release_stack" {
  source  = "buildkite/elastic-ci-stack-for-aws/buildkite"
  version = "~> 0.9.0"

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
  bootstrap_script_url        = local.imds_bootstrap_url
  managed_policy_arns = [
    local.policy_arn.read_build_secrets_release,
    local.policy_arn.read_release_secrets,
    local.policy_arn.read_dockerhub_release_secret, # release queue reads ONLY the release Docker Hub token
    local.policy_arn.ecr_public_push,
    local.policy_arn.release_website_tfstate,
    local.policy_arn.dependency_cache, # read/write the CI dependency cache (Maven/npm/pip/Bundler builds)
    local.policy_arn.imds_hardening,
  ]
}

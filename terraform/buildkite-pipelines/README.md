# Buildkite Pipelines

Terraform-managed Buildkite pipeline definitions for the MockServer project.

## Overview

This stack manages Buildkite pipelines using the [Buildkite Terraform provider](https://registry.terraform.io/providers/buildkite/buildkite/latest). Adding a new pipeline is done by adding an entry to the `pipelines` local in `pipelines.tf`.

## Pipelines

| Pipeline | File | Trigger | Purpose |
|----------|------|---------|---------|
| MockServer | `.buildkite/pipeline.yml` | Push to branches, PRs | CI build and test |
| Docker Push Maven | `.buildkite/docker-push-maven.yml` | Manual | Build and push `mockserver/mockserver:maven` CI image |

## Schedules

All times are UTC. The perf-related schedules follow `local.perf_schedules_enabled` in `perf-schedules.tf` (currently `false`, so they are off and perf runs are on demand).

| Schedule (resource) | Pipeline | When | What it runs |
|---|---|---|---|
| `snapshot_publish_nightly` | `mockserver-java` | 02:00 daily | Full build and tests, then publishes the Sonatype SNAPSHOT, the `:snapshot` images and the snapshot bundles (`PUBLISH_SNAPSHOT=true`); skipped when nothing changed since the last publish |
| `perf_regression_daily` | `mockserver-performance-test` | 04:00 daily | Perf regression run, commit-gated (perf toggle) |
| `cleanup_daily` | `mockserver-cleanup` | 06:00 daily | Closed-PR build sweep and ECR Public untagged-image purge |
| `perf_soak_weekly` | `mockserver-performance-test` | Sunday 08:00 | 2-hour soak (perf toggle) |
| `perf_xl_weekly` | `mockserver-performance-test` | Saturday 08:00 | `PERF_XL` headline run (perf toggle) |
| `infra_baseline_freshness_daily` | `mockserver-infra` | 16:00 daily | Only the perf baseline freshness steps (perf toggle) |

Merge builds no longer publish snapshots; see [ci-cd.md → Snapshot Publishing](../../docs/infrastructure/ci-cd.md#snapshot-publishing-nightly).

## Prerequisites

- AWS CLI with SSO profile `mockserver-build` authenticated (used to read the provider token from Secrets Manager)
- A Buildkite **classic API Access Token** stored in Secrets Manager (`mockserver-build/buildkite-tf-token`) — the secret must hold a valid token before running `terraform plan`/`apply`:
  - Scope it to org `mockserver` with `read_pipelines`, `write_pipelines`, and GraphQL enabled (create/rotate at https://buildkite.com/user/api-access-tokens)
  - This is **not** the `bk` CLI login (that is an OAuth token the GraphQL/Terraform API rejects)
  - The secret is read only by a local admin running this stack — no build-agent IAM policy grants it
- On macOS with Python 3.14+, set `export DYLD_LIBRARY_PATH="/opt/homebrew/opt/expat/lib"` before running Terraform

## Usage

```bash
aws sso login --profile mockserver-build
cd terraform/buildkite-pipelines
terraform init
terraform plan
terraform apply
```

## Adding a Pipeline

1. Create the pipeline YAML in `.buildkite/`
2. Add an entry to `local.pipelines` in `pipelines.tf`
3. Run `terraform apply`

## State

Remote state is stored in S3 (`buildkite-pipelines/terraform.tfstate`) in the same bucket as the `buildkite-agents` stack.

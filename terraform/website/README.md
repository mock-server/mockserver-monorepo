# Website Infrastructure

Terraform-managed S3, CloudFront, Route 53, and cross-account IAM for `mock-server.com` and every versioned subdomain (`<MAJOR>-<MINOR>.mock-server.com`).

## Architecture

```mermaid
flowchart LR
    USER[User browser] -->|HTTPS| MAIN[CloudFront 'main' distribution]
    USER -->|HTTPS| VER[CloudFront 'site' distribution per version]

    MAIN -->|OAC| LATEST[S3 bucket: latest version]
    VER -->|OAC| BUCKET[S3 bucket: this version]

    DNS["Route 53 zone\nmock-server.com"]
    DNS -->|A alias apex + www| MAIN
    DNS -->|A alias per version| VER

    ACM["ACM cert\n*.mock-server.com\n(us-east-1)"] -.->|TLS| MAIN
    ACM -.->|TLS| VER

    BUILD["Build account\nBuildkite agent role"] -->|sts:AssumeRole| ROLE["IAM role\nmockserver-release-website"]
    ROLE -->|scoped s3 + cloudfront + route53| AWSWEB[Website account resources]
```

## How it works

For each entry in the `sites` map (e.g. `"6-0" = { bucket_name = "aws-website-mockserver-6-0" }`) Terraform creates:

- An S3 bucket (`eu-west-2`, private, public-access-block enabled) with a lifecycle configuration that deletes noncurrent versions after 30 days and aborts incomplete multipart uploads after 7 days
- An S3 bucket policy granting OAC (plus legacy OAI during transition) `s3:GetObject`
- A CloudFront distribution serving `<version>.mock-server.com` with HTTP/2+3, TLSv1.2_2021, gzip, redirect-HTTP-to-HTTPS
- A Route 53 A-alias from `<version>.mock-server.com` to the CloudFront distribution

Plus one **main** CloudFront distribution that serves the apex `mock-server.com` and `www.mock-server.com` from the bucket pointed to by `latest_version`, with a custom 403→200 mapping to `/error403.html` so deep-link refreshes render correctly. The main distribution `depends_on` the per-site distributions so that during a version promotion (changing `latest_version`) the new owner drops the alias before the old one tries to claim it — avoiding CloudFront's "CNAME already associated" error.

The ACM certificate (`*.mock-server.com` in `us-east-1`) is **not** managed by this module — it is provisioned once and renewed automatically by AWS. The ARN is passed in via `acm_certificate_arn`.

The Route 53 hosted zone itself is **not** managed by this module — the zone is created once at account setup and its ID is passed in via `zone_id`. Records inside the zone are managed by this module (apex + `www` + every versioned subdomain).

Promoting a new release to a new versioned subdomain:

```bash
# scripts/release/components/versioned-site.sh does both of these for you:
#   1. Append to the `sites` map in terraform.tfvars (and bump `latest_version`)
#   2. terraform apply
```

## Directory structure

```
terraform/website/
├── README.md
├── backend.tf              # S3 state in build account (use_lockfile = true)
├── versions.tf             # Terraform >= 1.15, AWS provider >= 5.0
├── main.tf                 # Provider config (assume role for CI, AWS_PROFILE for manual)
├── variables.tf            # Input variables
├── outputs.tf              # Distribution IDs, role ARN, main bucket name
├── sites.tf                # S3 + CloudFront + Route 53 (per-version + main)
├── binaries.tf             # downloads.mock-server.com: S3 + CloudFront for SNAPSHOT bundles
├── cross-account-role.tf   # IAM role assumed by the build-account Buildkite agent
├── terraform.tfvars        # Live values (committed; no secrets)
└── terraform.tfvars.example
```

## Prerequisites

- [Terraform](https://www.terraform.io/downloads) >= 1.15
- AWS CLI with SSO profiles configured: `mockserver-build` (state backend + role assumption) and `mockserver-website` (target account, for manual runs without `website_role_arn`)
- Initial account bootstrap done: hosted zone created, ACM certificate validated, S3 backend bucket exists in the build account (handled by `terraform/buildkite-agents/bootstrap/`)

The module provisions resources across **two regions** in the website account: `us-east-1` (CloudFront distributions, Route 53 records, IAM role — CloudFront's TLS layer requires ACM certs in `us-east-1`) and `eu-west-2` via the `aws.eu-west-2` provider alias (the static-site S3 buckets, kept close to the contributor base).

## Getting started

```bash
cd terraform/website
aws sso login --profile mockserver-build
aws sso login --profile mockserver-website  # only needed for manual runs
terraform init
terraform plan       # set TF_VAR_website_role_arn="" for manual; CI sets it from secrets
terraform apply
```

## Variables

| Name | Type | Default | Purpose |
|------|------|---------|---------|
| `domain` | string | `"mock-server.com"` | Root domain |
| `latest_version` | string | (required) | Key in `sites` map serving the apex and `www` |
| `build_account_agent_role_arn` | string | (required) | IAM role in the build account that may assume `mockserver-release-website` |
| `sites` | `map({bucket_name = string})` | (required) | Version key → S3 bucket name. One entry per supported versioned subdomain plus the latest. |
| `zone_id` | string | (required) | Route 53 hosted-zone ID for the root domain |
| `acm_certificate_arn` | string | (required) | ARN of an ACM cert in `us-east-1` covering the root + `*.<domain>` |
| `website_role_arn` | string | `""` | Role ARN to assume in CI. Empty in manual runs (uses the SSO profile chain instead). |
| `cloudfront_price_class` | string | `"PriceClass_100"` | CloudFront price class for every distribution (`PriceClass_100`, `PriceClass_200` or `PriceClass_All`) |
| `role_external_id` | string (sensitive) | `""` | ExternalId for cross-account AssumeRole. Supplied at apply time via `TF_VAR_role_external_id` (from Secrets Manager); never committed. Must match on both the trust policy and the provider assume_role. |

## Outputs

| Name | Use |
|------|-----|
| `main_distribution_id` | Pass to `aws cloudfront create-invalidation` after a release |
| `main_distribution_domain` | Verify the apex is pointing at the right distribution |
| `main_bucket_name` | The release pipeline reads this to know where to `aws s3 sync` |
| `release_website_role_arn` | What the build-account Buildkite agent assumes for website operations |
| `distribution_ids` | Map of version → distribution ID for per-version invalidations |

## Cross-account access

The build account's Buildkite agents need to push releases into the website account. Rather than long-lived credentials, this module creates an IAM role (`mockserver-release-website`) in the website account whose trust policy permits assumption from `build_account_agent_role_arn`, guarded by an `sts:ExternalId` condition when `role_external_id` is supplied. The role's permissions are scoped:

- Specific S3 object and bucket verbs on `arn:aws:s3:::aws-website-mockserver-*` only (ACL and website-configuration verbs removed — buckets use OAC + BucketOwnerEnforced)
- Specific S3 bucket verbs on `arn:aws:s3:::aws-binaries-mockserver` (the `downloads.mock-server.com` bundle host in `binaries.tf`) — including `s3:PutBucketVersioning` and `s3:PutEncryptionConfiguration` (the bucket enables versioning + SSE) and `s3:ListBucket` (backs the provider's `HeadBucket` existence probe — without it the refresh gets a 403 the AWS provider misreads as "bucket deleted" and plans a destructive recreate). See the note below on why the release role needs this.
- Specific CloudFront distribution and invalidation verbs account-wide (CloudFront doesn't support resource-level permissions for distribution-list/create)
- Hosted-zone-scoped Route 53 read + `ChangeResourceRecordSets` on `mock-server.com` (`route53:GetHostedZone`, `ListResourceRecordSets`, `ListTagsForResource`, `ChangeResourceRecordSets`), plus account-wide `route53:GetChange` (needed to poll change propagation status — Route 53 doesn't support resource-level scoping for that one)

> **Whole-state apply — every resource in this module needs release-role coverage.** `versioned-site.sh` runs `terraform plan`/`apply` over the **entire** `terraform/website/` state on each major/minor release. Terraform refreshes (and may create/update) **all** resources in the state, not just the website buckets — so any resource added here (e.g. `binaries.tf`) must have matching permissions in `cross-account-role.tf`, or the release pipeline's Versioned Site step fails with `AccessDenied` during refresh. The `aws-binaries-mockserver` bucket itself is **only written by the SNAPSHOT bundle CI step** (build-account agent, cross-account) — released versions ship via GitHub Releases — but because its Terraform definition lives in this release-applied state, the release role must still be able to manage it.
>
> The release role cannot make changes outside adding a site, such as editing its own policy or deleting resources. So **apply every change to this module by hand right after merging, before the next major/minor release**, and confirm that a final `terraform plan` reports "No changes". Otherwise the release's apply fails partway, and because `versioned-site.sh` repoints the main distribution before it copies content, www can be left serving 403.
>
> **Also plan the release path before merging.** Copy this directory, make the two `terraform.tfvars` edits `versioned-site.sh` makes (add a new `"X-Y"` site entry, set `latest_version` to it), and run a read-only `terraform plan` on the copy. Some mistakes only fail when a site is being added: for example, an attribute that is unknown until the new bucket exists.

The release pipeline (`scripts/release/components/website.sh`, `…/javadoc.sh`, `…/helm.sh`, `…/versioned-site.sh`) calls `sts:AssumeRole` against this role and uses the resulting credentials for the rest of the step.

### ExternalId requirement

When `role_external_id` is set, both the trust policy on `aws_iam_role.release_website` and the provider `assume_role` blocks require a matching ExternalId. Supply it at apply time:

```bash
export TF_VAR_role_external_id="$(aws secretsmanager get-secret-value \
  --profile mockserver-build \
  --secret-id mockserver-release/website-external-id \
  --query SecretString --output text)"
terraform apply
```

The secret must exist in both accounts: the build account (so release steps can pass it) and the website account's trust policy (so the condition matches). Create the secret in the build account's Secrets Manager and wire it into the release steps (`website.sh`, `versioned-site.sh`, `javadoc.sh`, `helm.sh`) before the next apply.

## State

State lives in S3 (`mockserver-terraform-state`, key `website/terraform.tfstate`, region `eu-west-2`, account `mockserver-build`) with native S3 locking (`use_lockfile = true`). This is the same backend bucket as the other modules — see `terraform/buildkite-agents/bootstrap/` for how it was created.

## What this module does NOT manage

| Thing | Where it lives | Why |
|-------|---------------|-----|
| AWS account creation / SSO setup | Manual, one-time | Pre-Terraform bootstrap |
| Route 53 hosted zone (zone itself, not records) | Manual, one-time | Created at account setup; pre-Terraform |
| ACM certificate request and validation | Manual, one-time | Renewals are automatic; initial issuance needs DNS validation outside the cert's lifecycle |
| SES inbound (email) | `terraform/ses-email-forwarding/` | Separate module so email lifecycle is independent of website lifecycle |
| Bucket *contents* (HTML, JS, Javadoc, Helm charts) | `scripts/release/components/website.sh` + `…/javadoc.sh` + `…/helm.sh` | Content is per-release, not per-infrastructure-change |

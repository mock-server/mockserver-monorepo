resource "aws_iam_role" "release_website" {
  name = "mockserver-release-website"
  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [merge(
      {
        Effect    = "Allow"
        Principal = { AWS = var.build_account_agent_role_arn }
        Action    = "sts:AssumeRole"
      },
      # Only emit the Condition key when an external id is supplied. Emitting
      # `Condition = null` produces `"Condition": null` in the policy JSON,
      # which the IAM API normalises away — causing perpetual plan drift. When
      # role_external_id is "" (the default) the trust policy stays as-is so the
      # current cross-account apply keeps working until the external id is wired.
      var.role_external_id != "" ? {
        Condition = { StringEquals = { "sts:ExternalId" = var.role_external_id } }
      } : {}
    )]
  })
}

resource "aws_iam_role_policy" "release_website" {
  name = "website-access"
  role = aws_iam_role.release_website.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Audit finding F-WEB-02: previously `s3:*` — tightened to the specific
        # verbs the release pipeline needs.
        #
        # Routine release (website.sh, javadoc.sh, schema.sh, helm.sh) uses
        # object-level reads/writes against the active site bucket.
        # Major / minor release (versioned-site.sh) creates a NEW versioned
        # bucket via Terraform and so additionally needs bucket-create verbs.
        Sid    = "WebsiteBuckets"
        Effect = "Allow"
        Action = [
          # Object-level (routine release)
          "s3:GetObject",
          "s3:PutObject",
          "s3:DeleteObject",
          "s3:ListBucket",
          "s3:GetBucketLocation",
          "s3:GetBucketVersioning",
          "s3:GetBucketTagging",
          "s3:GetBucketPolicy",
          "s3:GetBucketPublicAccessBlock",
          "s3:GetBucketOwnershipControls",
          # Bucket-level reads Terraform performs when REFRESHING the existing
          # aws_s3_bucket resources in this state (every plan/apply refreshes
          # all managed buckets). Without these, `terraform plan` aborts during
          # refresh with AccessDenied (GetBucketAcl etc.) before it can apply.
          "s3:GetBucketAcl",
          "s3:GetBucketCORS",
          "s3:GetBucketWebsite",
          "s3:GetBucketLogging",
          "s3:GetLifecycleConfiguration",
          "s3:GetReplicationConfiguration",
          "s3:GetEncryptionConfiguration",
          "s3:GetBucketObjectLockConfiguration",
          "s3:GetBucketRequestPayment",
          "s3:GetAccelerateConfiguration",
          # Bucket-level (versioned-site.sh creates new buckets)
          "s3:CreateBucket",
          "s3:PutBucketPolicy",
          "s3:PutPublicAccessBlock",
          "s3:PutBucketPublicAccessBlock",
          "s3:PutBucketOwnershipControls",
          "s3:PutBucketTagging",
          # Each new site bucket gets an aws_s3_bucket_lifecycle_configuration.
          "s3:PutLifecycleConfiguration",
        ]
        Resource = ["arn:aws:s3:::aws-website-mockserver-*", "arn:aws:s3:::aws-website-mockserver-*/*"]
      },
      {
        # The binary download host bucket (binaries.tf -> aws-binaries-mockserver,
        # serving downloads.mock-server.com). versioned-site.sh runs
        # `terraform plan`/`apply` over the WHOLE website state, so the release
        # role must be able to refresh AND create this bucket and its config.
        # Unlike the website buckets, the binaries bucket enables versioning and
        # SSE, so it additionally needs s3:PutBucketVersioning and
        # s3:PutEncryptionConfiguration (not granted by WebsiteBuckets above).
        Sid    = "BinariesBucket"
        Effect = "Allow"
        Action = [
          # s3:ListBucket backs the provider's HeadBucket existence probe. Without
          # it the probe gets a 403 that the AWS provider misreads as "bucket
          # deleted", planning a destructive recreate of the bucket and its
          # config sub-resources (this is what failed the first retry of build
          # #49). The release role manages no objects here, so this is the only
          # ListBucket-class verb it needs.
          "s3:ListBucket",
          # Bucket-level reads Terraform performs on every plan/apply refresh.
          "s3:GetBucketLocation",
          "s3:GetBucketVersioning",
          "s3:GetBucketTagging",
          "s3:GetBucketPolicy",
          "s3:GetBucketPublicAccessBlock",
          "s3:GetBucketOwnershipControls",
          "s3:GetBucketAcl",
          "s3:GetBucketCORS",
          "s3:GetBucketWebsite",
          "s3:GetBucketLogging",
          "s3:GetLifecycleConfiguration",
          "s3:GetReplicationConfiguration",
          "s3:GetEncryptionConfiguration",
          "s3:GetBucketObjectLockConfiguration",
          "s3:GetBucketRequestPayment",
          "s3:GetAccelerateConfiguration",
          # Bucket-level creates/updates (initial provisioning of the bucket).
          "s3:CreateBucket",
          "s3:PutBucketPolicy",
          "s3:PutPublicAccessBlock",
          "s3:PutBucketPublicAccessBlock",
          "s3:PutBucketOwnershipControls",
          "s3:PutBucketTagging",
          "s3:PutBucketVersioning",
          "s3:PutEncryptionConfiguration",
          "s3:PutLifecycleConfiguration",
        ]
        Resource = ["arn:aws:s3:::aws-binaries-mockserver", "arn:aws:s3:::aws-binaries-mockserver/*"]
      },
      {
        # Audit finding F-WEB-02: previously `cloudfront:*` — tightened to the
        # specific actions used by `scripts/release/components/website.sh`,
        # `versioned-site.sh`, `javadoc.sh`, and `schema.sh`. Removes account-
        # wide actions never used by the release pipeline (e.g. cloudfront
        # functions, key groups, public keys, realtime log configs).
        # Create/Delete are needed because versioned-site.sh provisions a new
        # distribution and OAI for every new major/minor release.
        Sid    = "CloudFront"
        Effect = "Allow"
        Action = [
          # Cache invalidation (routine release)
          "cloudfront:CreateInvalidation",
          "cloudfront:GetInvalidation",
          "cloudfront:ListInvalidations",
          # Distribution read + update (routine release)
          "cloudfront:GetDistribution",
          "cloudfront:GetDistributionConfig",
          "cloudfront:ListDistributions",
          "cloudfront:UpdateDistribution",
          # Distribution create/delete (versioned-site.sh)
          "cloudfront:CreateDistribution",
          "cloudfront:DeleteDistribution",
          # OAI create/read/delete (versioned-site.sh)
          "cloudfront:CreateCloudFrontOriginAccessIdentity",
          "cloudfront:GetCloudFrontOriginAccessIdentity",
          "cloudfront:GetCloudFrontOriginAccessIdentityConfig",
          "cloudfront:ListCloudFrontOriginAccessIdentities",
          "cloudfront:DeleteCloudFrontOriginAccessIdentity",
          "cloudfront:UpdateCloudFrontOriginAccessIdentity",
          # Tagging (routine + versioned-site)
          "cloudfront:TagResource",
          "cloudfront:UntagResource",
          "cloudfront:ListTagsForResource",
          # Read-only gets Terraform performs when refreshing the security-
          # hardening CloudFront resources (OAC + response-headers policy) that
          # live in this state. Required for `terraform plan` refresh to succeed.
          "cloudfront:GetOriginAccessControl",
          "cloudfront:GetResponseHeadersPolicy",
        ]
        Resource = "*"
      },
      {
        # Read-only describes Terraform performs when REFRESHING the security-
        # hardening resources added to this state (security-hardening.tf,
        # dnssec.tf): WAFv2 web ACL, IAM Access Analyzer,
        # EBS encryption-by-default, and the DNSSEC KMS key. The release role
        # never creates or modifies these (an admin apply manages them); it only
        # needs to read them so `terraform plan` refresh does not abort with
        # AccessDenied. All actions are read-only.
        Sid    = "SecurityHardeningRefreshReadOnly"
        Effect = "Allow"
        Action = [
          "kms:DescribeKey",
          "kms:GetKeyPolicy",
          "kms:GetKeyRotationStatus",
          "kms:ListResourceTags",
          # kms:ListAliases has no resource-level scoping; needed to refresh
          # aws_kms_alias.dnssec.
          "kms:ListAliases",
          "wafv2:GetWebACL",
          "wafv2:ListTagsForResource",
          "wafv2:GetLoggingConfiguration",
          "access-analyzer:GetAnalyzer",
          "access-analyzer:ListTagsForResource",
          "ec2:GetEbsEncryptionByDefault",
          # route53domains:GetDomainDetail refreshes the registrar-side DNSSEC
          # delegation signer record (aws_route53domains_delegation_signer_record);
          # route53domains is global and not resource-scopable.
          "route53domains:GetDomainDetail",
        ]
        Resource = "*"
      },
      {
        # Route53 alias records for the version subdomains, scoped to the
        # mock-server.com hosted zone.
        Sid    = "Route53Zone"
        Effect = "Allow"
        Action = [
          "route53:ChangeResourceRecordSets",
          "route53:GetHostedZone",
          "route53:ListResourceRecordSets",
          "route53:ListTagsForResource",
          # Refresh of the DNSSEC resources (aws_route53_key_signing_key,
          # aws_route53_hosted_zone_dnssec) reads the zone's DNSSEC status.
          "route53:GetDNSSEC",
        ]
        Resource = "arn:aws:route53:::hostedzone/${var.zone_id}"
      },
      {
        Sid      = "Route53Change"
        Effect   = "Allow"
        Action   = "route53:GetChange"
        Resource = "arn:aws:route53:::change/*"
      },
      {
        # terraform refreshes this role on every apply. Read-only — the role
        # definition itself is only ever changed by an admin apply, never by
        # the release pipeline, so there is no self-escalation path.
        Sid    = "SelfRoleRefresh"
        Effect = "Allow"
        Action = [
          "iam:GetRole",
          "iam:GetRolePolicy",
          "iam:ListRolePolicies",
          "iam:ListAttachedRolePolicies",
          "iam:ListInstanceProfilesForRole",
          "iam:ListRoleTags",
        ]
        Resource = aws_iam_role.release_website.arn
      },
    ]
  })
}

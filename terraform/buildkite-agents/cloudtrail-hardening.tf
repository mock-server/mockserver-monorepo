# CloudTrail hardening — bring the pre-existing trail under Terraform
# management and add KMS encryption, bucket versioning, and access logging.
#
# Audit findings: F-BLD-08 (no KMS CMK), F-BLD-09 (no versioning),
# F-BLD-17 (no access logging on CloudTrail bucket), F-BLD-18 (none on
# state bucket — fix added in this stack too).
#
# Pre-existing resources are imported by name; first `terraform plan` will
# show the imports and the new encryption/versioning/logging settings.

# --- Customer-managed KMS key for CloudTrail ---------------------------------

resource "aws_kms_key" "cloudtrail" {
  description             = "CMK used to encrypt CloudTrail logs and digest files"
  enable_key_rotation     = true
  deletion_window_in_days = 30

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "EnableRootPermissions"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = "kms:*"
        Resource  = "*"
      },
      {
        Sid       = "AllowCloudTrailEncrypt"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "kms:GenerateDataKey*"
        Resource  = "*"
        Condition = {
          StringLike = {
            "kms:EncryptionContext:aws:cloudtrail:arn" = "arn:aws:cloudtrail:*:${data.aws_caller_identity.current.account_id}:trail/*"
          }
        }
      },
      {
        Sid       = "AllowCloudTrailDescribeKey"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "kms:DescribeKey"
        Resource  = "*"
      },
      {
        Sid       = "AllowAccountReadWithCloudTrailContext"
        Effect    = "Allow"
        Principal = { AWS = "arn:aws:iam::${data.aws_caller_identity.current.account_id}:root" }
        Action    = ["kms:Decrypt", "kms:ReEncryptFrom"]
        Resource  = "*"
        Condition = {
          StringEquals = {
            "kms:CallerAccount" = data.aws_caller_identity.current.account_id
          }
          StringLike = {
            "kms:EncryptionContext:aws:cloudtrail:arn" = "arn:aws:cloudtrail:*:${data.aws_caller_identity.current.account_id}:trail/*"
          }
        }
      }
    ]
  })
}

resource "aws_kms_alias" "cloudtrail" {
  name          = "alias/mockserver-cloudtrail"
  target_key_id = aws_kms_key.cloudtrail.key_id
}

# --- Existing CloudTrail bucket — imported, then hardened --------------------

resource "aws_s3_bucket" "cloudtrail" {
  bucket = "mockserver-cloudtrail-logs"
}

resource "aws_s3_bucket_versioning" "cloudtrail" {
  bucket = aws_s3_bucket.cloudtrail.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "cloudtrail" {
  bucket = aws_s3_bucket.cloudtrail.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm     = "aws:kms"
      kms_master_key_id = aws_kms_key.cloudtrail.arn
    }
    bucket_key_enabled = true
  }
}

resource "aws_s3_bucket_public_access_block" "cloudtrail" {
  bucket                  = aws_s3_bucket.cloudtrail.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_logging" "cloudtrail" {
  bucket        = aws_s3_bucket.cloudtrail.id
  target_bucket = aws_s3_bucket.audit_logs.id
  target_prefix = "cloudtrail/"
}

resource "aws_s3_bucket_policy" "cloudtrail" {
  bucket = aws_s3_bucket.cloudtrail.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # SourceArn condition prevents confused-deputy: only the specific trail
        # in this account can use CloudTrail's service identity to write here.
        Sid       = "AWSCloudTrailAclCheck"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "s3:GetBucketAcl"
        Resource  = aws_s3_bucket.cloudtrail.arn
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = "arn:aws:cloudtrail:eu-west-2:${data.aws_caller_identity.current.account_id}:trail/mockserver-management-trail"
          }
        }
      },
      {
        Sid       = "AWSCloudTrailWrite"
        Effect    = "Allow"
        Principal = { Service = "cloudtrail.amazonaws.com" }
        Action    = "s3:PutObject"
        Resource  = "${aws_s3_bucket.cloudtrail.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"
        Condition = {
          StringEquals = {
            "s3:x-amz-acl"  = "bucket-owner-full-control"
            "AWS:SourceArn" = "arn:aws:cloudtrail:eu-west-2:${data.aws_caller_identity.current.account_id}:trail/mockserver-management-trail"
          }
        }
      },
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.cloudtrail.arn, "${aws_s3_bucket.cloudtrail.arn}/*"]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}

# Import the existing pre-Terraform bucket so apply doesn't try to create it.
import {
  to = aws_s3_bucket.cloudtrail
  id = "mockserver-cloudtrail-logs"
}

# --- Existing trail — imported and KMS-encryption applied --------------------

resource "aws_cloudtrail" "management" {
  name                          = "mockserver-management-trail"
  s3_bucket_name                = aws_s3_bucket.cloudtrail.id
  is_multi_region_trail         = true
  include_global_service_events = true
  enable_log_file_validation    = true
  kms_key_id                    = aws_kms_key.cloudtrail.arn

  # Advanced event selectors replace the classic event_selector block.
  # When advanced selectors are used, management events must be explicitly
  # included (they are not implicit like in classic mode).

  # Management events (replaces the classic event_selector).
  #
  # NOTE: Secrets Manager API calls — including GetSecretValue on the build and
  # release secrets — are MANAGEMENT events and are captured here. Secrets
  # Manager is not a supported CloudTrail data-event resource type, so there is
  # no separate data-event selector for it (an "AWS::SecretsManager::Secret"
  # data selector is rejected with InvalidEventSelectorsException).
  advanced_event_selector {
    name = "ManagementEvents"

    field_selector {
      field  = "eventCategory"
      equals = ["Management"]
    }
  }

  # Data events: audit object-level access on the Terraform state bucket
  # (state reads/writes/deletes). Scoped to a single bucket ARN to control
  # CloudTrail data-event cost.
  advanced_event_selector {
    name = "TfStateBucketDataEvents"

    field_selector {
      field  = "eventCategory"
      equals = ["Data"]
    }

    field_selector {
      field  = "resources.type"
      equals = ["AWS::S3::Object"]
    }

    field_selector {
      field       = "resources.ARN"
      starts_with = ["arn:aws:s3:::mockserver-terraform-state/"]
    }
  }
}

import {
  to = aws_cloudtrail.management
  # AWS provider v6+ requires the full ARN for cloudtrail imports
  # (older versions accepted bare trail name).
  id = "arn:aws:cloudtrail:eu-west-2:${data.aws_caller_identity.current.account_id}:trail/mockserver-management-trail"
}

# --- Dedicated audit-logs bucket for S3 access logs --------------------------
#
# Centralised target for S3 server access logs from the CloudTrail bucket and
# the Terraform state bucket. Buckets do not log to themselves (recursion);
# audit-logs bucket has logging disabled.

resource "aws_s3_bucket" "audit_logs" {
  bucket = "mockserver-audit-logs-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_ownership_controls" "audit_logs" {
  bucket = aws_s3_bucket.audit_logs.id
  rule {
    object_ownership = "BucketOwnerPreferred"
  }
}

resource "aws_s3_bucket_acl" "audit_logs" {
  depends_on = [aws_s3_bucket_ownership_controls.audit_logs]
  bucket     = aws_s3_bucket.audit_logs.id
  acl        = "log-delivery-write"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "audit_logs" {
  bucket = aws_s3_bucket.audit_logs.id
  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "audit_logs" {
  bucket = aws_s3_bucket.audit_logs.id
  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "audit_logs" {
  bucket                  = aws_s3_bucket.audit_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_lifecycle_configuration" "audit_logs" {
  bucket = aws_s3_bucket.audit_logs.id

  rule {
    id     = "expire-after-365-days"
    status = "Enabled"

    filter {}

    expiration {
      days = 365
    }
  }
}

resource "aws_s3_bucket_policy" "audit_logs" {
  bucket = aws_s3_bucket.audit_logs.id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource  = [aws_s3_bucket.audit_logs.arn, "${aws_s3_bucket.audit_logs.arn}/*"]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      }
    ]
  })
}

# Audit finding F-BLD-18 (caught by 2026-05-27 re-audit): the Terraform state
# bucket also needs S3 server access logging. The bucket itself is managed
# in the bootstrap stack; the bucket-logging resource lives here because
# that's where the audit-logs target bucket is defined. The cross-stack
# reference is by bucket name (deterministic) rather than by resource
# reference — terraform/buildkite-agents/bootstrap/main.tf owns the state
# bucket resource.
resource "aws_s3_bucket_logging" "terraform_state" {
  bucket        = "mockserver-terraform-state"
  target_bucket = aws_s3_bucket.audit_logs.id
  target_prefix = "terraform-state/"
}

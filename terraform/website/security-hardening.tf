# Security hardening for the mockserver-website account.
#
# Audit findings addressed:
#   F-WEB-04 — WAFv2 web ACL on all CloudFront distributions
#   F-WEB-05 — GuardDuty (accepted risk: detectors removed for cost, see below)
#   F-WEB-07 — IAM Access Analyzer
#   F-WEB-08 — CloudFront access logging (bucket created here, attached in sites.tf)
#   F-WEB-16 — EBS encryption by default in both regions
#
# Run with `terraform apply` from this directory after review.

data "aws_caller_identity" "current" {
  provider = aws
}

# --- F-WEB-04 — WAFv2 web ACL (CLOUDFRONT scope, us-east-1) ------------------

resource "aws_wafv2_web_acl" "cloudfront" {
  provider    = aws # default provider = us-east-1
  name        = "mockserver-cloudfront"
  description = "Default WAF protection for mock-server.com CloudFront distributions"
  scope       = "CLOUDFRONT"

  default_action {
    allow {}
  }

  # AWS-managed common rule set: covers OWASP Top 10 categories.
  rule {
    name     = "AWSManagedCommonRules"
    priority = 0

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesCommonRuleSet"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "common"
      sampled_requests_enabled   = true
    }
  }

  # AWS-managed IP reputation list.
  rule {
    name     = "AWSManagedIpReputation"
    priority = 1

    override_action {
      none {}
    }

    statement {
      managed_rule_group_statement {
        vendor_name = "AWS"
        name        = "AWSManagedRulesAmazonIpReputationList"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "ip-reputation"
      sampled_requests_enabled   = true
    }
  }

  # Rate-limit any single IP to 2000 requests per 5-minute window.
  rule {
    name     = "RateLimitPerIP"
    priority = 2

    action {
      block {}
    }

    statement {
      rate_based_statement {
        limit              = 2000
        aggregate_key_type = "IP"
      }
    }

    visibility_config {
      cloudwatch_metrics_enabled = true
      metric_name                = "rate-limit"
      sampled_requests_enabled   = true
    }
  }

  visibility_config {
    cloudwatch_metrics_enabled = true
    metric_name                = "mockserver-cloudfront"
    sampled_requests_enabled   = true
  }
}

# --- F-WEB-08 — CloudFront access logs bucket --------------------------------

resource "aws_s3_bucket" "cloudfront_logs" {
  provider = aws.eu-west-2
  bucket   = "aws-website-mockserver-cf-logs-${data.aws_caller_identity.current.account_id}"
}

resource "aws_s3_bucket_ownership_controls" "cloudfront_logs" {
  provider = aws.eu-west-2
  bucket   = aws_s3_bucket.cloudfront_logs.id

  rule {
    object_ownership = "BucketOwnerPreferred"
  }
}

resource "aws_s3_bucket_acl" "cloudfront_logs" {
  provider   = aws.eu-west-2
  depends_on = [aws_s3_bucket_ownership_controls.cloudfront_logs]
  bucket     = aws_s3_bucket.cloudfront_logs.id
  acl        = "log-delivery-write"
}

resource "aws_s3_bucket_server_side_encryption_configuration" "cloudfront_logs" {
  provider = aws.eu-west-2
  bucket   = aws_s3_bucket.cloudfront_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "cloudfront_logs" {
  provider = aws.eu-west-2
  bucket   = aws_s3_bucket.cloudfront_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_public_access_block" "cloudfront_logs" {
  provider                = aws.eu-west-2
  bucket                  = aws_s3_bucket.cloudfront_logs.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# The bucket is versioned, so `expiration` alone only adds a delete marker and
# keeps the log as a noncurrent version forever; the noncurrent expiry is what
# actually frees the storage. 30 days keeps a window to recover deleted logs.
# S3 rejects expired_object_delete_marker in the same expiration block as
# days, hence the second rule.
resource "aws_s3_bucket_lifecycle_configuration" "cloudfront_logs" {
  provider   = aws.eu-west-2
  depends_on = [aws_s3_bucket_versioning.cloudfront_logs]
  bucket     = aws_s3_bucket.cloudfront_logs.id

  rule {
    id     = "expire-after-90-days"
    status = "Enabled"
    filter {}
    expiration {
      days = 90
    }
    noncurrent_version_expiration {
      noncurrent_days = 30
    }
    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }

  rule {
    id     = "remove-expired-delete-markers"
    status = "Enabled"
    filter {}
    expiration {
      expired_object_delete_marker = true
    }
  }
}

resource "aws_s3_bucket_policy" "cloudfront_logs" {
  provider = aws.eu-west-2
  bucket   = aws_s3_bucket.cloudfront_logs.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Sid       = "DenyInsecureTransport"
      Effect    = "Deny"
      Principal = "*"
      Action    = "s3:*"
      Resource = [
        aws_s3_bucket.cloudfront_logs.arn,
        "${aws_s3_bucket.cloudfront_logs.arn}/*",
      ]
      Condition = {
        Bool = { "aws:SecureTransport" = "false" }
      }
    }]
  })
}

# --- F-WEB-05 — GuardDuty: deliberately NOT enabled --------------------------
# The owner removed the GuardDuty detectors on cost grounds (2026-11); the
# account's CloudTrail trail is its only security monitoring. Do not re-add
# them without that decision being revisited.

# --- F-WEB-07 — IAM Access Analyzer in both regions --------------------------

resource "aws_accessanalyzer_analyzer" "us_east_1" {
  provider      = aws
  analyzer_name = "mockserver-website-account-analyzer"
  type          = "ACCOUNT"
}

resource "aws_accessanalyzer_analyzer" "eu_west_2" {
  provider      = aws.eu-west-2
  analyzer_name = "mockserver-website-account-analyzer"
  type          = "ACCOUNT"
}

# --- F-WEB-16 — EBS encryption by default in both regions --------------------

resource "aws_ebs_encryption_by_default" "us_east_1" {
  provider = aws
  enabled  = true
}

resource "aws_ebs_encryption_by_default" "eu_west_2" {
  provider = aws.eu-west-2
  enabled  = true
}

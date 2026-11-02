# ---------------------------------------------------------------------------
# Binary download host: downloads.mock-server.com
# ---------------------------------------------------------------------------
# Serves client binary bundles from a DEDICATED private S3 bucket, isolated
# from the website content buckets so the build queue can publish binaries but
# can NEVER write website content.
#
# Object key layout:
#   mockserver-<version>/mockserver-<version>-<os>-<arch>.<ext>
#   mockserver-<version>/mockserver-<version>-<os>-<arch>.<ext>.sha256
#
# The build-account default-queue agent role is granted cross-account
# s3:PutObject/s3:DeleteObject/s3:ListBucket on this bucket ONLY (via the
# bucket policy here + an IAM policy in terraform/buildkite-agents/).
# ---------------------------------------------------------------------------

# --- S3 bucket (eu-west-2, private, OAC) ------------------------------------

resource "aws_s3_bucket" "binaries" {
  provider = aws.eu-west-2
  bucket   = "aws-binaries-mockserver"

  tags = {
    Name    = "downloads.${var.domain}"
    Purpose = "Client binary bundles for downloads.mock-server.com"
  }
}

resource "aws_s3_bucket_public_access_block" "binaries" {
  provider                = aws.eu-west-2
  bucket                  = aws_s3_bucket.binaries.id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_server_side_encryption_configuration" "binaries" {
  provider = aws.eu-west-2
  bucket   = aws_s3_bucket.binaries.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "binaries" {
  provider = aws.eu-west-2
  bucket   = aws_s3_bucket.binaries.id

  versioning_configuration {
    # Versioning is useful for rollback of published binaries.
    status = "Enabled"
  }
}

# Every master merge overwrites the SNAPSHOT bundles, so without this the
# bucket keeps every superseded bundle forever. No newer_noncurrent_versions:
# keeping even one older copy per bundle would double the stored bytes.
resource "aws_s3_bucket_lifecycle_configuration" "binaries" {
  provider   = aws.eu-west-2
  depends_on = [aws_s3_bucket_versioning.binaries]
  bucket     = aws_s3_bucket.binaries.id

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 7
    }

    expiration {
      expired_object_delete_marker = true
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

resource "aws_s3_bucket_policy" "binaries" {
  provider = aws.eu-west-2
  bucket   = aws_s3_bucket.binaries.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Allow CloudFront OAC to read objects for public download.
        Sid       = "AllowOACRead"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.binaries.arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = aws_cloudfront_distribution.binaries.arn
          }
        }
      },
      {
        # Cross-account write access for the build-account default-queue agent.
        # This grants the build role the ability to publish and remove binaries.
        # The matching IAM policy in terraform/buildkite-agents/ also grants
        # these actions — cross-account S3 access requires BOTH the bucket
        # policy AND the principal's IAM policy.
        Sid       = "AllowBuildAgentPublish"
        Effect    = "Allow"
        Principal = { AWS = var.build_account_default_role_arn }
        Action = [
          "s3:PutObject",
          "s3:DeleteObject",
        ]
        Resource = "${aws_s3_bucket.binaries.arn}/*"
      },
      {
        # ListBucket is bucket-level (no /* suffix).
        Sid       = "AllowBuildAgentList"
        Effect    = "Allow"
        Principal = { AWS = var.build_account_default_role_arn }
        Action    = "s3:ListBucket"
        Resource  = aws_s3_bucket.binaries.arn
      },
      {
        # Deny any non-TLS access (defence-in-depth).
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.binaries.arn,
          "${aws_s3_bucket.binaries.arn}/*",
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
    ]
  })
}

# --- CloudFront distribution ------------------------------------------------

resource "aws_cloudfront_distribution" "binaries" {
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = ["downloads.${var.domain}"]
  price_class         = var.cloudfront_price_class
  http_version        = "http2and3"
  comment             = "downloads.${var.domain}"

  # Reuse the existing WAF web ACL from security-hardening.tf.
  web_acl_id = aws_wafv2_web_acl.cloudfront.arn

  origin {
    domain_name              = aws_s3_bucket.binaries.bucket_regional_domain_name
    origin_id                = "S3-${aws_s3_bucket.binaries.id}"
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  # Reuse the existing CloudFront access logs bucket.
  logging_config {
    bucket          = aws_s3_bucket.cloudfront_logs.bucket_regional_domain_name
    prefix          = "downloads/"
    include_cookies = false
  }

  default_cache_behavior {
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    target_origin_id           = "S3-${aws_s3_bucket.binaries.id}"
    viewer_protocol_policy     = "redirect-to-https"
    compress                   = true
    response_headers_policy_id = aws_cloudfront_response_headers_policy.security.id

    forwarded_values {
      query_string = false
      cookies { forward = "none" }
    }
  }

  viewer_certificate {
    acm_certificate_arn      = var.acm_certificate_arn
    ssl_support_method       = "sni-only"
    minimum_protocol_version = "TLSv1.2_2021"
  }

  restrictions {
    geo_restriction { restriction_type = "none" }
  }
}

# --- Route 53 records -------------------------------------------------------

resource "aws_route53_record" "downloads_a" {
  zone_id = var.zone_id
  name    = "downloads.${var.domain}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.binaries.domain_name
    zone_id                = aws_cloudfront_distribution.binaries.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "downloads_aaaa" {
  zone_id = var.zone_id
  name    = "downloads.${var.domain}"
  type    = "AAAA"

  alias {
    name                   = aws_cloudfront_distribution.binaries.domain_name
    zone_id                = aws_cloudfront_distribution.binaries.hosted_zone_id
    evaluate_target_health = false
  }
}

locals {
  main_aliases = [var.domain, "www.${var.domain}"]
}

resource "aws_s3_bucket" "site" {
  provider = aws.eu-west-2
  for_each = var.sites
  bucket   = each.value.bucket_name
  tags = {
    Name = "${each.key}.${var.domain}"
  }
}

resource "aws_s3_bucket_public_access_block" "site" {
  provider                = aws.eu-west-2
  for_each                = var.sites
  bucket                  = aws_s3_bucket.site[each.key].id
  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# Site-bucket versioning is not managed here; nb9hq (the 5.15 archive) had it
# enabled out of band, and a bad upload to it is rolled back from a noncurrent
# version, so keep 30 days of those. Noncurrent expiry is a no-op on the
# unversioned buckets.
resource "aws_s3_bucket_lifecycle_configuration" "site" {
  provider = aws.eu-west-2
  for_each = var.sites
  bucket   = aws_s3_bucket.site[each.key].id
  # nb9hq (5-15) lives in us-east-1 despite the eu-west-2 provider. Use the
  # bucket's `region`, not the computed `bucket_region`: the latter is unknown
  # for a new site, and the provider rejects an unknown `region` at plan time.
  region = aws_s3_bucket.site[each.key].region

  rule {
    id     = "expire-noncurrent-versions"
    status = "Enabled"
    filter {}

    noncurrent_version_expiration {
      noncurrent_days = 30
    }

    expiration {
      expired_object_delete_marker = true
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 7
    }
  }
}

# Audit finding F-WEB-14: every distribution reads its S3 origin through a
# single Origin Access Control (OAC) instance, signing origin requests with
# SigV4. The legacy Origin Access Identity (OAI) resources and the
# "AllowLegacyOAIRead" bucket-policy grant were removed after confirming (live,
# 2026-06-05) that all 9 distributions reference only `origin_access_control_id`
# in their origin config — no distribution presents an OAI principal, so the
# OAI grants were dead code.

resource "aws_cloudfront_origin_access_control" "s3" {
  name                              = "mockserver-website-s3"
  description                       = "OAC for all mockserver website S3 origins"
  origin_access_control_origin_type = "s3"
  signing_behavior                  = "always"
  signing_protocol                  = "sigv4"
}

resource "aws_s3_bucket_policy" "site" {
  provider = aws.eu-west-2
  for_each = var.sites
  bucket   = aws_s3_bucket.site[each.key].id
  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        # Audit finding F-WEB-14: OAC principal — CloudFront service identity,
        # scoped by SourceArn condition to only the distributions that should
        # read from this bucket (the per-version distro plus, for the latest
        # version's bucket, the main distro).
        Sid       = "AllowOACRead"
        Effect    = "Allow"
        Principal = { Service = "cloudfront.amazonaws.com" }
        Action    = "s3:GetObject"
        Resource  = "${aws_s3_bucket.site[each.key].arn}/*"
        Condition = {
          StringEquals = {
            "AWS:SourceArn" = concat(
              [aws_cloudfront_distribution.site[each.key].arn],
              each.key == var.latest_version ? [aws_cloudfront_distribution.main.arn] : []
            )
          }
        }
      },
      {
        # Audit finding F-WEB-09: deny any non-TLS access to the bucket.
        # CloudFront and SDK callers already use HTTPS; this is defence-in-depth.
        Sid       = "DenyInsecureTransport"
        Effect    = "Deny"
        Principal = "*"
        Action    = "s3:*"
        Resource = [
          aws_s3_bucket.site[each.key].arn,
          "${aws_s3_bucket.site[each.key].arn}/*",
        ]
        Condition = {
          Bool = { "aws:SecureTransport" = "false" }
        }
      },
    ]
  })
}

# Audit finding F-WEB-17: attach security response headers to every
# CloudFront distribution. One managed policy serves both distributions.
#
# NOTE: the Content-Security-Policy directive below is conservative and may
# need tuning against the live site (e.g. additional script-src / style-src
# origins for third-party widgets). If breakage is observed after apply,
# temporarily swap content_security_policy for Content-Security-Policy-Report-Only
# semantics (CloudFront has no native report-only field — front it with a
# custom_headers_config "Content-Security-Policy-Report-Only" entry) to collect
# violations without blocking, then tighten once the report is clean.
resource "aws_cloudfront_response_headers_policy" "security" {
  name    = "mockserver-security-headers"
  comment = "Security headers for mock-server.com distributions"

  security_headers_config {
    strict_transport_security {
      access_control_max_age_sec = 31536000
      include_subdomains         = true
      override                   = true
    }

    content_type_options {
      override = true
    }

    frame_options {
      frame_option = "SAMEORIGIN"
      override     = true
    }

    referrer_policy {
      referrer_policy = "strict-origin-when-cross-origin"
      override        = true
    }

    content_security_policy {
      content_security_policy = "default-src 'self'; img-src 'self' data: https:; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; script-src 'self' 'unsafe-inline' https://www.googletagmanager.com https://www.google-analytics.com https://cdn.jsdelivr.net https://cdnjs.cloudflare.com; font-src 'self' data: https://fonts.gstatic.com; connect-src 'self' https://www.google-analytics.com https://*.analytics.google.com; frame-ancestors 'self'"
      override                = true
    }
  }
}

resource "aws_cloudfront_distribution" "site" {
  for_each            = var.sites
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = ["${each.key}.${var.domain}"]
  price_class         = var.cloudfront_price_class
  http_version        = "http2and3"
  comment             = "${each.key}.${var.domain}"

  # Audit finding F-WEB-04: WAF web ACL attached to every distribution.
  web_acl_id = aws_wafv2_web_acl.cloudfront.arn

  origin {
    domain_name              = aws_s3_bucket.site[each.key].bucket_regional_domain_name
    origin_id                = "S3-${each.value.bucket_name}"
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  # Audit finding F-WEB-08: enable standard access logging.
  # `bucket_regional_domain_name` is required when the logs bucket is outside
  # us-east-1 (this bucket is in eu-west-2); the global `bucket_domain_name`
  # silently breaks log delivery.
  logging_config {
    bucket          = aws_s3_bucket.cloudfront_logs.bucket_regional_domain_name
    prefix          = "${each.key}/"
    include_cookies = false
  }

  # SEO finding (GSC coverage 2026-06-14): the bucket is private (OAC), so S3
  # returns 403 for any missing object. Without this mapping the archive
  # subdomains answered deleted/partial URLs with a bare 403 ("Blocked due to
  # access forbidden") or soft-404 instead of a real 404, so Google kept those
  # URLs queued instead of dropping them. Mirror the main distribution: serve
  # the friendly error page (present in every archive bucket) but return a real
  # 404 status. error403.html meta-refreshes to www, so users land on current docs.
  custom_error_response {
    error_code            = 403
    response_code         = 404
    response_page_path    = "/error403.html"
    error_caching_min_ttl = 300
  }

  default_cache_behavior {
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    target_origin_id           = "S3-${each.value.bucket_name}"
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

resource "aws_cloudfront_distribution" "main" {
  # Ensure site distributions drop domain aliases before main claims them (avoids CloudFront CNAME conflict)
  depends_on          = [aws_cloudfront_distribution.site]
  enabled             = true
  is_ipv6_enabled     = true
  default_root_object = "index.html"
  aliases             = local.main_aliases
  price_class         = var.cloudfront_price_class
  http_version        = "http2and3"
  comment             = var.domain

  # Audit finding F-WEB-04: WAF web ACL attached.
  web_acl_id = aws_wafv2_web_acl.cloudfront.arn

  origin {
    domain_name              = aws_s3_bucket.site[var.latest_version].bucket_regional_domain_name
    origin_id                = "S3-${var.sites[var.latest_version].bucket_name}"
    origin_access_control_id = aws_cloudfront_origin_access_control.s3.id
  }

  # Audit finding F-WEB-08: enable standard access logging.
  # `bucket_regional_domain_name` is required when the logs bucket is outside
  # us-east-1 (this bucket is in eu-west-2); the global `bucket_domain_name`
  # silently breaks log delivery.
  logging_config {
    bucket          = aws_s3_bucket.cloudfront_logs.bucket_regional_domain_name
    prefix          = "main/"
    include_cookies = false
  }

  # The bucket is private (OAC), so S3 returns 403 for any missing object.
  # Serve the friendly error page, but preserve a real 404 status so search
  # engines drop deleted URLs (old versioned-site copies, old apidocs) instead
  # of treating thousands of soft-404s as duplicate indexable pages.
  custom_error_response {
    error_code            = 403
    response_code         = 404
    response_page_path    = "/error403.html"
    error_caching_min_ttl = 300
  }

  default_cache_behavior {
    allowed_methods            = ["GET", "HEAD"]
    cached_methods             = ["GET", "HEAD"]
    target_origin_id           = "S3-${var.sites[var.latest_version].bucket_name}"
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

resource "aws_route53_record" "site" {
  for_each = var.sites
  zone_id  = var.zone_id
  name     = "${each.key}.${var.domain}"
  type     = "A"

  alias {
    name                   = aws_cloudfront_distribution.site[each.key].domain_name
    zone_id                = aws_cloudfront_distribution.site[each.key].hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "main" {
  zone_id = var.zone_id
  name    = var.domain
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}

resource "aws_route53_record" "www" {
  zone_id = var.zone_id
  name    = "www.${var.domain}"
  type    = "A"

  alias {
    name                   = aws_cloudfront_distribution.main.domain_name
    zone_id                = aws_cloudfront_distribution.main.hosted_zone_id
    evaluate_target_health = false
  }
}

# Audit finding F-WEB-18: CAA records restrict certificate issuance to Amazon
# (ACM). Prevents a compromised or rogue CA from issuing a cert for the domain.
resource "aws_route53_record" "caa" {
  zone_id = var.zone_id
  name    = var.domain
  type    = "CAA"
  ttl     = 300

  records = [
    "0 issue \"amazon.com\"",
    "0 issuewild \"amazon.com\"",
  ]
}

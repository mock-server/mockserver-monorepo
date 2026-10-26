# DNSSEC for mock-server.com.
#
# Audit finding F-WEB-13: DNSSEC was NOT_SIGNING; this stack enables it.
#
# The activation flow is staged to avoid any client lookup impact:
#
#   1. Create a KMS CMK (us-east-1, ECC_NIST_P256, sign/verify) for the KSK.
#   2. Create the Route 53 Key-Signing Key referencing the CMK.
#   3. Enable DNSSEC signing on the hosted zone.
#   4. Publish the Delegation Signer (DS) record at the registrar
#      (Route 53 Domains, same account) so validating resolvers begin
#      checking signatures.
#
# What clients see:
#   - Non-validating resolvers: zero change. The new RRSIG/DNSKEY records
#     are returned alongside existing answers and quietly ignored.
#   - Validating resolvers (~30% globally — Cloudflare, Google, Quad9,
#     several large ISP resolvers): they now verify the signature chain.
#     A successful chain protects against DNS spoofing; a broken chain
#     yields SERVFAIL. Route 53 manages key rotation so the chain stays
#     intact automatically.
#
# Rollback: if validation ever causes a problem, remove the DS record at
# the registrar first (`aws_route53domains_delegation_signer_record`),
# wait ~24h for resolver caches to expire, then disable signing on the
# zone. NEVER disable signing while DS is still published — that produces
# SERVFAIL for every validating resolver until they cache out.

# ---------------------------------------------------------------------------
# KMS CMK used to sign the zone (the Key-Signing Key material).
# Route 53 DNSSEC requires:
#   - region: us-east-1
#   - key spec: ECC_NIST_P256
#   - usage: SIGN_VERIFY
# ---------------------------------------------------------------------------

resource "aws_kms_key" "dnssec" {
  provider                 = aws # default = us-east-1
  description              = "DNSSEC KSK for ${var.domain} (Route 53)"
  customer_master_key_spec = "ECC_NIST_P256"
  key_usage                = "SIGN_VERIFY"
  deletion_window_in_days  = 30
  enable_key_rotation      = false # rotation not supported for asymmetric keys

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
        # Required for Route 53 DNSSEC signing. See:
        # https://docs.aws.amazon.com/Route53/latest/DeveloperGuide/dns-configuring-dnssec.html#dns-configuring-dnssec-cmk-requirements
        Sid       = "AllowRoute53DNSSECService"
        Effect    = "Allow"
        Principal = { Service = "dnssec-route53.amazonaws.com" }
        Action = [
          "kms:DescribeKey",
          "kms:GetPublicKey",
          "kms:Sign",
          "kms:Verify",
        ]
        Resource = "*"
        Condition = {
          StringEquals = { "aws:SourceAccount" = data.aws_caller_identity.current.account_id }
          ArnLike = {
            "aws:SourceArn" = "arn:aws:route53:::hostedzone/${var.zone_id}"
          }
        }
      },
      {
        Sid       = "AllowRoute53DNSSECServiceGrant"
        Effect    = "Allow"
        Principal = { Service = "dnssec-route53.amazonaws.com" }
        Action = [
          "kms:CreateGrant",
        ]
        Resource = "*"
        Condition = {
          Bool = { "kms:GrantIsForAWSResource" = "true" }
        }
      },
    ]
  })
}

resource "aws_kms_alias" "dnssec" {
  provider      = aws
  name          = "alias/route53-dnssec-${replace(var.domain, ".", "-")}"
  target_key_id = aws_kms_key.dnssec.key_id
}

# ---------------------------------------------------------------------------
# Route 53 KSK + zone signing.
# ---------------------------------------------------------------------------

resource "aws_route53_key_signing_key" "this" {
  hosted_zone_id             = var.zone_id
  key_management_service_arn = aws_kms_key.dnssec.arn
  name                       = "mockserver-ksk"
}

resource "aws_route53_hosted_zone_dnssec" "this" {
  depends_on     = [aws_route53_key_signing_key.this]
  hosted_zone_id = var.zone_id
}

# ---------------------------------------------------------------------------
# Publish the DS record at the registrar (Route 53 Domains).
# This activates the chain of trust — validating resolvers begin checking
# signatures from this point.
#
# IMPORTANT: this resource only works because mock-server.com is registered
# with Route 53 Domains in this same AWS account. If the domain ever moves
# to a different registrar, the DS record must be published manually there.
# ---------------------------------------------------------------------------

resource "aws_route53domains_delegation_signer_record" "this" {
  domain_name = var.domain

  signing_attributes {
    algorithm  = aws_route53_key_signing_key.this.signing_algorithm_type
    flags      = aws_route53_key_signing_key.this.flag
    public_key = aws_route53_key_signing_key.this.public_key
  }

  depends_on = [aws_route53_hosted_zone_dnssec.this]

  # An earlier apply registered the DS at the registrar successfully, but the
  # AWS provider misreported a duplicate-association error as FAILED and
  # discarded the resource state. The DS is live and validating at all major
  # resolvers. The old domain,key-id import ID populated only `algorithm`
  # (not `flags` or `public_key`), so after import Terraform would otherwise
  # see those fields as "drift" and try to replace the working DS — which
  # would destroy DNSSEC for the domain. ignore_changes pins the imported
  # state.
  #
  # If the KSK ever rotates, you MUST remove this ignore_changes block,
  # destroy and recreate the DS record under Terraform's management.
  lifecycle {
    ignore_changes = [signing_attributes]
  }
}

# The provider finds the DS by a digest of signing_attributes, so the import ID
# must carry all of them: domain,flags,algorithm,public_key. The older
# domain,key-id form leaves flags and public_key empty and the DS reads as gone.
import {
  to = aws_route53domains_delegation_signer_record.this
  id = "mock-server.com,257,13,JHbfCpiKpzXYNTfGeiNzHlUtMsXr1alDwTC87Gox74Wlb6DXfSvhe0W/kn2Z7Hex/MhZ1Xwe9+N4qQ4LmHgpBA=="
}

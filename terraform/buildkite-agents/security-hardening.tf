# Security hardening for the mockserver-build account.
#
# Adds account-wide detection controls and EBS default encryption.
# Audit findings addressed: F-BLD-02, F-BLD-04, F-BLD-06
# (GuardDuty, Access Analyzer, EBS default encryption).
#
# Run with `terraform apply` from this directory after review.

# --- F-BLD-02 — Enable GuardDuty ----------------------------------------------
# The `datasources` block on aws_guardduty_detector is deprecated in
# hashicorp/aws v6+ — use aws_guardduty_detector_feature resources instead.
resource "aws_guardduty_detector" "this" {
  enable                       = true
  finding_publishing_frequency = "FIFTEEN_MINUTES"
}

resource "aws_guardduty_detector_feature" "s3_data_events" {
  detector_id = aws_guardduty_detector.this.id
  name        = "S3_DATA_EVENTS"
  status      = "ENABLED"
}

# Other features (RUNTIME_MONITORING, EKS_AUDIT_LOGS, EBS_MALWARE_PROTECTION,
# LAMBDA_NETWORK_LOGS) left at their account defaults. Add explicit
# aws_guardduty_detector_feature resources here to flip individual ones.

# --- F-BLD-04 — Enable Access Analyzer ---------------------------------------
resource "aws_accessanalyzer_analyzer" "account" {
  analyzer_name = "mockserver-build-account-analyzer"
  type          = "ACCOUNT"
}

# --- F-BLD-06 — Enable EBS encryption by default -----------------------------
resource "aws_ebs_encryption_by_default" "this" {
  enabled = true
}

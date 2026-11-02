# Security hardening for the mockserver-build account.
#
# Adds account-wide detection controls and EBS default encryption.
# Audit findings addressed: F-BLD-04, F-BLD-06
# (Access Analyzer, EBS default encryption).
#
# F-BLD-02 (GuardDuty) is accepted risk, not remediated: the owner removed the
# detector in 2026-11 to cut cost, keeping CloudTrail as the security monitor.
#
# Run with `terraform apply` from this directory after review.

# --- F-BLD-04 — Enable Access Analyzer ---------------------------------------
resource "aws_accessanalyzer_analyzer" "account" {
  analyzer_name = "mockserver-build-account-analyzer"
  type          = "ACCOUNT"
}

# --- F-BLD-06 — Enable EBS encryption by default -----------------------------
resource "aws_ebs_encryption_by_default" "this" {
  enabled = true
}

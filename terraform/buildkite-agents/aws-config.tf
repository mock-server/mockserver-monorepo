# AWS Config was removed from the build account (owner cost decision, 2026-11; CloudTrail
# is the retained monitor), destroying its recorder, rules and IAM role.
# Audit finding F-BLD-03 (no Config recorder) is therefore accepted risk.
# The delivery bucket holds the recorded history, so it and its settings are only
# FORGOTTEN: they stay in AWS as configured. Empty and delete the bucket by hand when
# the history is no longer wanted; these blocks can go once that apply has run.

removed {
  from = aws_s3_bucket.config
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_server_side_encryption_configuration.config
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_public_access_block.config
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_versioning.config
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_policy.config
  lifecycle {
    destroy = false
  }
}

removed {
  from = aws_s3_bucket_logging.config
  lifecycle {
    destroy = false
  }
}

# ---------------------------------------------------------------------------
# S3 Dependency Cache for CI Builds
# ---------------------------------------------------------------------------
# Lockfile-keyed Maven and Gradle caches for ephemeral scale-to-zero agents.
# .buildkite/scripts/dependency-cache.sh restores and saves them INSIDE the job
# that uses them (via run-in-docker.sh --cache); see docs/infrastructure/ci-cd.md
# -> Dependency Caching. Every failure is a no-op and the build runs cold.
#
# Trust: any default-queue build may READ; only the maven-build step of the
# SCHEDULED master build of mockserver-java may WRITE (OIDC role below). The release
# queue has no access. Entries are not signed. Objects expire after 14 days.
# ---------------------------------------------------------------------------

resource "aws_s3_bucket" "dependency_cache" {
  bucket = "mockserver-ci-dependency-cache"

  tags = {
    Name    = "mockserver-ci-dependency-cache"
    Purpose = "CI dependency cache for Maven/Gradle"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  rule {
    id     = "expire-stale-cache"
    status = "Enabled"

    expiration {
      days = 14
    }

    # Clean up incomplete multipart uploads after 1 day
    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_versioning" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  versioning_configuration {
    # Cache objects are disposable -- no need for versioning
    status = "Suspended"
  }
}

resource "aws_s3_bucket_server_side_encryption_configuration" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_public_access_block" "dependency_cache" {
  bucket = aws_s3_bucket.dependency_cache.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

# ---------------------------------------------------------------------------
# IAM: agents READ the cache; only the scheduled mockserver-java nightly WRITES
# ---------------------------------------------------------------------------
# Attached to the default queue role only (main.tf). PR and branch builds run
# on that queue with the same role, so the role itself must not be able to write.
# The description still says read/write: it is ForceNew, and replacing a policy
# that is attached to the agent roles fails with DeleteConflict.

resource "aws_iam_policy" "dependency_cache" {
  name        = "buildkite-dependency-cache"
  description = "Allow Buildkite agents to read/write the CI dependency cache bucket"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect   = "Allow"
        Action   = ["s3:ListBucket"]
        Resource = [aws_s3_bucket.dependency_cache.arn]
      },
      {
        Effect   = "Allow"
        Action   = ["s3:GetObject"]
        Resource = ["${aws_s3_bucket.dependency_cache.arn}/*"]
      },
    ]
  })
}

# Writes need a Buildkite-signed OIDC token for the maven-build step of a master
# mockserver-java build with build_source session tag `schedule`. Branch-created builds
# are trigger_job/api; schedules need pipeline-edit rights. PRE-EXISTING GAP: a branch
# build can read the shared cluster agent token from SSM and register a rogue agent that
# takes the nightly job; only a separate cluster closes that, not a dedicated queue.
# See ci-cd.md -> Dependency Caching -> Trust model.
resource "aws_iam_openid_connect_provider" "buildkite" {
  url            = "https://agent.buildkite.com"
  client_id_list = ["sts.amazonaws.com"]
}

resource "aws_iam_role" "dependency_cache_writer" {
  name                 = "buildkite-dependency-cache-writer"
  description          = "Assumed via Buildkite OIDC by the scheduled mockserver-java build to save the CI dependency cache"
  max_session_duration = 3600

  assume_role_policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect    = "Allow"
      Principal = { Federated = aws_iam_openid_connect_provider.buildkite.arn }
      Action    = ["sts:AssumeRoleWithWebIdentity", "sts:TagSession"]
      Condition = {
        StringEquals = {
          "agent.buildkite.com:aud"     = "sts.amazonaws.com"
          "aws:RequestTag/build_source" = "schedule"
        }
        StringLike = {
          "agent.buildkite.com:sub" = "organization:mockserver:pipeline:mockserver-java:ref:refs/heads/master:commit:*:step:maven-build"
        }
      }
    }]
  })
}

resource "aws_iam_role_policy" "dependency_cache_writer" {
  name = "write-dependency-cache"
  role = aws_iam_role.dependency_cache_writer.id

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect   = "Allow"
      Action   = ["s3:PutObject", "s3:AbortMultipartUpload"]
      Resource = ["${aws_s3_bucket.dependency_cache.arn}/maven/*", "${aws_s3_bucket.dependency_cache.arn}/gradle/java/*"]
    }]
  })
}

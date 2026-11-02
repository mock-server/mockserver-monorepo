provider "aws" {
  alias   = "us_east_1"
  region  = "us-east-1"
  profile = "mockserver-build"
}

resource "aws_ecrpublic_repository" "mockserver" {
  provider        = aws.us_east_1
  repository_name = "mockserver"

  catalog_data {
    about_text        = "MockServer - open source HTTP(S) mock server and proxy for testing"
    description       = "MockServer enables easy mocking of any system you integrate with via HTTP or HTTPS"
    operating_systems = ["Linux"]
    architectures     = ["x86-64", "ARM 64"]
  }
}

resource "aws_ecrpublic_repository" "mockserver_webhook" {
  provider        = aws.us_east_1
  repository_name = "mockserver-webhook"

  catalog_data {
    about_text        = "MockServer Admission Webhook - Kubernetes sidecar injection for MockServer transparent proxy"
    description       = "Admission webhook that automatically injects MockServer transparent-proxy sidecars into Kubernetes pods"
    operating_systems = ["Linux"]
    architectures     = ["x86-64", "ARM 64"]
  }
}

resource "aws_iam_policy" "ecr_public_push" {
  name        = "buildkite-ecr-public-push"
  description = "Allow Buildkite agents to push Docker images to ECR Public"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [
      {
        Effect = "Allow"
        Action = [
          "ecr-public:GetAuthorizationToken",
          "ecr-public:BatchCheckLayerAvailability",
          "ecr-public:PutImage",
          "ecr-public:InitiateLayerUpload",
          "ecr-public:UploadLayerPart",
          "ecr-public:CompleteLayerUpload",
          "ecr-public:DescribeRepositories",
        ]
        Resource = "*"
      },
      {
        Effect   = "Allow"
        Action   = "sts:GetServiceBearerToken"
        Resource = "*"
      }
    ]
  })
}

# Daily untagged-image purge (.buildkite/scripts/steps/ecr-public-untagged-purge.sh,
# pipeline-cleanup.yml) on the default queue. IAM cannot limit BatchDeleteImage to
# untagged images; the script's keep-set does that, and this only scopes the repos.
resource "aws_iam_policy" "ecr_public_purge" {
  name        = "buildkite-ecr-public-purge"
  description = "Allow the cleanup pipeline to list and delete untagged images in the MockServer ECR Public repositories"

  policy = jsonencode({
    Version = "2012-10-17"
    Statement = [{
      Effect = "Allow"
      Action = [
        "ecr-public:DescribeRepositories",
        "ecr-public:DescribeImages",
        "ecr-public:BatchDeleteImage",
      ]
      Resource = [
        aws_ecrpublic_repository.mockserver.arn,
        aws_ecrpublic_repository.mockserver_webhook.arn,
      ]
    }]
  })
}

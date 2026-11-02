variable "domain" {
  type        = string
  default     = "mock-server.com"
  description = "Root domain for the website"
}

variable "latest_version" {
  type        = string
  description = "Version key in sites map that serves as the main website (e.g. '5-15')"
}

variable "build_account_agent_role_arn" {
  type        = string
  description = "IAM role ARN of the Buildkite agent in the build account, used in the cross-account trust policy"
}

variable "build_account_default_role_arn" {
  type        = string
  description = "IAM role ARN of the default-queue Buildkite agent in the build account, granted cross-account write access to the binaries bucket"
}

variable "sites" {
  type = map(object({
    bucket_name = string
  }))
  description = "Map of version key (e.g. '5-15') to its S3 bucket name. The latest_version entry serves as the main site."
}

variable "zone_id" {
  type        = string
  description = "Route53 hosted zone ID for mock-server.com"
}

variable "acm_certificate_arn" {
  type        = string
  description = "ACM certificate ARN covering *.mock-server.com and mock-server.com (us-east-1)"
}

variable "website_role_arn" {
  type        = string
  default     = ""
  description = "IAM role ARN the release pipeline assumes for website-account operations. Leave empty when running terraform manually (the AWS_PROFILE credential chain is used instead)."
}

variable "role_external_id" {
  type        = string
  default     = ""
  sensitive   = true
  description = "ExternalId for cross-account AssumeRole. Supplied at apply time from a secret (e.g. TF_VAR_role_external_id); never committed. Must match the condition on aws_iam_role.release_website and the provider assume_role blocks."
}

variable "cloudfront_price_class" {
  type        = string
  default     = "PriceClass_100"
  description = "CloudFront price class for every distribution in this module. PriceClass_100 serves only from North America, Europe and Israel edges (cheapest); viewers elsewhere are served from those edges with higher latency."

  validation {
    condition     = contains(["PriceClass_100", "PriceClass_200", "PriceClass_All"], var.cloudfront_price_class)
    error_message = "cloudfront_price_class must be PriceClass_100, PriceClass_200 or PriceClass_All."
  }
}

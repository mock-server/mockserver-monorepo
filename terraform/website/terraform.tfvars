domain                       = "mock-server.com"
build_account_agent_role_arn = "arn:aws:iam::814548061024:role/buildkite-mockserver-release-95bef2c5-Role"
build_account_default_role_arn = "arn:aws:iam::814548061024:role/buildkite-mockserver-e40b8a59-Role"
zone_id                      = "Z1R2IC6XAWK4Y6"
acm_certificate_arn          = "arn:aws:acm:us-east-1:014848309742:certificate/80ca7e79-1a03-406a-a0ef-d75317459232"
latest_version              = "9-2"

sites = {
  "5-10" = { bucket_name = "aws-website-mockserver-5-10" }
  "5-11" = { bucket_name = "aws-website-mockserver-5-11" }
  "5-12" = { bucket_name = "aws-website-mockserver-5-12" }
  "5-13" = { bucket_name = "aws-website-mockserver-5-13" }
  "5-14" = { bucket_name = "aws-website-mockserver-5-14" }
  "5-15" = { bucket_name = "aws-website-mockserver-nb9hq" }
  "6-0" = { bucket_name = "aws-website-mockserver-6-0" }
  "6-1" = { bucket_name = "aws-website-mockserver-6-1" }
  "7-0" = { bucket_name = "aws-website-mockserver-7-0" }
  "7-1" = { bucket_name = "aws-website-mockserver-7-1" }
  "7-2" = { bucket_name = "aws-website-mockserver-7-2" }
  "7-3" = { bucket_name = "aws-website-mockserver-7-3" }
  "7-4" = { bucket_name = "aws-website-mockserver-7-4" }
  "7-5" = { bucket_name = "aws-website-mockserver-7-5" }
  "7-6" = { bucket_name = "aws-website-mockserver-7-6" }
  "8-0" = { bucket_name = "aws-website-mockserver-8-0" }
  "9-0" = { bucket_name = "aws-website-mockserver-9-0" }
  "9-1" = { bucket_name = "aws-website-mockserver-9-1" }
  "9-2" = { bucket_name = "aws-website-mockserver-9-2" }
}

mcp_dns_public_key = "umXq2Fc19DKCTu8MHO9y9FsReiKju+xc6EUXBHNZBFY="

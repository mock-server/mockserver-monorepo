# One VPC shared by every agent stack, passed into each elastic-ci-stack module.
#
# Left to itself the module creates a VPC per stack, each with three SSM
# interface endpoints in two AZs, billed per endpoint-hour whether or not any
# instance is running. This VPC has no interface endpoints: instances keep a
# public IPv4 address and reach SSM, Buildkite, ECR and S3 through the IGW.
locals {
  agent_subnets = {
    a = { az = "${var.region}a", cidr = "10.0.0.0/20" }
    b = { az = "${var.region}b", cidr = "10.0.16.0/20" }
  }
}

resource "aws_vpc" "agents" {
  cidr_block           = "10.0.0.0/16"
  enable_dns_support   = true
  enable_dns_hostnames = true

  tags = {
    Name = "buildkite-mockserver-agents"
  }
}

resource "aws_internet_gateway" "agents" {
  vpc_id = aws_vpc.agents.id

  tags = {
    Name = "buildkite-mockserver-agents"
  }
}

resource "aws_subnet" "agents" {
  for_each = local.agent_subnets

  vpc_id                  = aws_vpc.agents.id
  availability_zone       = each.value.az
  cidr_block              = each.value.cidr
  map_public_ip_on_launch = true

  tags = {
    Name = "buildkite-mockserver-agents-${each.key}"
  }
}

resource "aws_route_table" "agents" {
  vpc_id = aws_vpc.agents.id

  route {
    cidr_block = "0.0.0.0/0"
    gateway_id = aws_internet_gateway.agents.id
  }

  tags = {
    Name = "buildkite-mockserver-agents"
  }
}

resource "aws_route_table_association" "agents" {
  for_each = aws_subnet.agents

  subnet_id      = each.value.id
  route_table_id = aws_route_table.agents.id
}

# Strip the rules from the VPC's default security group; nothing uses it, and
# each stack's module still creates its own agent security group in this VPC.
resource "aws_default_security_group" "agents" {
  vpc_id = aws_vpc.agents.id
}

locals {
  # The module needs create_vpc = false as well: the VPC id is unknown at plan
  # time, so it cannot infer "use an existing VPC" from a non-empty vpc_id.
  agent_network = {
    vpc_id  = aws_vpc.agents.id
    subnets = [for k in sort(keys(aws_subnet.agents)) : aws_subnet.agents[k].id]
  }
}

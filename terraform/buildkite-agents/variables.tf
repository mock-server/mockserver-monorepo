variable "buildkite_agent_token" {
  description = <<-EOT
    Buildkite agent registration token.

    NEVER write this value to terraform.tfvars. Supply it at apply time via
    an environment variable:

      export TF_VAR_buildkite_agent_token=$(aws ssm get-parameter \
        --name /buildkite/buildkite/agent-token \
        --with-decryption --query Parameter.Value --output text \
        --profile mockserver-build)

    The run.sh wrapper does this automatically.
  EOT
  type        = string
  sensitive   = true
}

variable "region" {
  description = "AWS region"
  type        = string
  default     = "eu-west-2"
}

variable "instance_types" {
  # 8 vCPU / 32 GiB (m5-class), NOT c5.2xlarge (8 vCPU / 16 GiB). The `:maven:
  # build` and `:nexus: deploy snapshot` steps run one container (agents_per_instance
  # = 1) that holds BOTH a 6g-Xmx Maven reactor JVM (mockserver/.mvn/jvm.config,
  # driven to its ceiling by `-T 1C`) AND, in the same cgroup, the dashboard
  # `vite build` spawned by mockserver-netty's frontend-maven-plugin — whose
  # ~1.5-3g peak is rolldown NATIVE memory that no Node heap flag can bound. On
  # 16 GiB those two could not both fit under the 7g container limit and OOM-killed
  # (exit 137) ~half of master's builds. The container limit is raised to 12g
  # (.buildkite/scripts/steps/java-build.sh, java-deploy-snapshot.sh); a 12g
  # container needs a >=32 GiB host to leave the daemon/agent/OS room, so every
  # type here is a same-vCPU 32 GiB variant (keeps the 8-vCPU assumption the perf
  # gates rely on). Keep them ALL 8 vCPU / 32 GiB when editing for Spot diversity.
  #
  # Order matters only for on-demand: the ASG launches on-demand on the FIRST type
  # (the cheapest, m6a), and the stack picks the AMI architecture from it, so it
  # must stay x86_64. Spot is price-capacity-optimized and ignores the order, so a
  # type slow enough to be unwanted must be removed, not demoted.
  description = "Default queue EC2 instance types (comma-separated), all 8 vCPU / 32 GiB, x86_64. First type preferred for on-demand; Spot picks from all of them."
  type        = string
  default     = "m6a.2xlarge,m7i.2xlarge,m6i.2xlarge,m7a.2xlarge"
}

variable "min_size" {
  description = "Minimum number of agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "max_size" {
  description = "Maximum number of agent instances"
  type        = number
  default     = 10
}

variable "on_demand_percentage" {
  description = "Default queue: percentage of on-demand instances (0 = all spot, 100 = all on-demand). There is no on-demand base, so this applies from the first instance."
  type        = number
  default     = 20
}

variable "release_min_size" {
  description = "Minimum number of release agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "release_max_size" {
  description = "Maximum number of release agent instances (release queue)"
  type        = number
  default     = 2
}

variable "release_instance_types" {
  # Separate from instance_types so the default queue's on-demand order can favour
  # price without moving the release queue, which is 100% on-demand and runs on the
  # FIRST type. Same 8 vCPU / 32 GiB, x86_64 rules as instance_types.
  description = "Release queue EC2 instance types (comma-separated), all 8 vCPU / 32 GiB, x86_64. On-demand only, so the first type is used and the rest are capacity fallbacks."
  type        = string
  default     = "m7i.2xlarge,m6a.2xlarge,m6i.2xlarge,m7a.2xlarge"
}

variable "trigger_instance_types" {
  description = "EC2 instance types for trigger queue (cheap, low-CPU — only runs curl/sleep polling loops)"
  type        = string
  default     = "t3.small"
}

variable "trigger_min_size" {
  description = "Minimum number of trigger agent instances (0 = scale to zero when idle)"
  type        = number
  default     = 0
}

variable "trigger_max_size" {
  description = "Maximum number of trigger agent instances"
  type        = number
  default     = 4
}

variable "perf_instance_types" {
  description = "EC2 instance type for the perf queue — a SINGLE fixed-performance type (no comma list) for reproducible benchmark numbers. MUST have enough PHYSICAL cores for the run's cpusets (see below); perf-test-run.sh fails the build if they overlap."
  type        = string
  # 48 vCPU across 24 PHYSICAL cores. The physical count is the binding one, and
  # picking on vCPU alone is what broke the rig: perf-test-run.sh pins server=0-5,
  # upstream=6, k6=8-13, i.e. 13 cores, and the previous c5.4xlarge has 16 vCPU but
  # only EIGHT physical cores. k6 therefore ran on the hyperthread siblings of cores
  # the server was already saturating, so every throughput figure measured a server
  # contending with its own load generator — and it is the main reason "the client
  # saturates first" blocked the 36k knee (k6 read 600.9% against a 600% pin on a
  # run that otherwise passed).
  #
  # 24 physical cores fits the 13 with room to widen k6 for the higher sweep rungs.
  # Single-socket, so the server stays in one NUMA domain, and the same c5 (Cascade
  # Lake) microarchitecture as before, which keeps cross-hardware drift to a minimum.
  # c5.18xlarge and above are dual-socket — do not go there without re-checking NUMA.
  #
  # CHANGING THIS VALUE REQUIRES RE-REVIEWING THE `hw` FLAGS in
  # mockserver-performance-test/perf-budgets.json. Metrics marked `hw` compare only
  # against runs from the same instance type, so they reset their baseline here and
  # sit at `no-baseline` until MIN_BASELINE runs exist on the new box. A metric that
  # SHOULD be marked and is not will instead compare straight across the change —
  # silently, because an absent `hw` is legal and means hardware-independent. The
  # validator cannot catch that omission, and no low-false-positive mechanical rule
  # can either: hw-mixed metric families are the norm, not the exception (behaviours
  # p95 is hw but error_rate is not; laptop threads are but tcp_sockets are not), so
  # both sibling-consistency and name-suffix rules would fire constantly on correct
  # entries. This note is the control instead — a review triggered at the one moment
  # it matters, with no false positives the rest of the time.
  default = "c5.12xlarge"
}

variable "perf_min_size" {
  description = "Minimum perf agent instances. MUST be 0 (scale to zero — zero idle cost; AGENTS.md hard constraint)"
  type        = number
  default     = 0
}

variable "perf_max_size" {
  description = "Maximum perf agent instances. One agent per instance, so each perf job has a whole box to itself and concurrent jobs never share a machine"
  type        = number
  default     = 3
}

variable "perf_xl_instance_types" {
  description = "EC2 instance type for the perf-xl queue — a SINGLE fixed-performance type (no comma list) for reproducible benchmark numbers"
  type        = string
  # 128 vCPU / 64 physical cores across two sockets (two NUMA nodes), unlike the
  # single-socket perf box, so cpusets must be NUMA-aware. Results share the perf
  # S3 history: metrics without an `hw` flag in perf-budgets.json would compare
  # across both queues (see the note on perf_instance_types).
  default = "c6i.32xlarge"
}

variable "perf_xl_min_size" {
  description = "Minimum perf-xl agent instances. MUST be 0 (scale to zero — zero idle cost; AGENTS.md hard constraint)"
  type        = number
  default     = 0
}

variable "perf_xl_max_size" {
  description = "Maximum perf-xl agent instances. One agent per instance; capped at 1 because each box is 128 vCPU of on-demand capacity"
  type        = number
  default     = 1
}

variable "alert_email" {
  description = "Email address for infrastructure alerts (SNS notifications)"
  type        = string
  default     = ""
}

variable "monthly_cost_budget_usd" {
  description = "Monthly AWS cost budget for the build account, in USD. alert_email is notified at 80% actual and 100% forecasted spend"
  type        = number
  default     = 600

  validation {
    condition     = var.monthly_cost_budget_usd > 0
    error_message = "monthly_cost_budget_usd must be greater than 0."
  }
}

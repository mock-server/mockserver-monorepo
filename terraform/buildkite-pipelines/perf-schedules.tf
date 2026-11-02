# false = perf runs are on demand. The only switch for the perf schedules and the 16:00
# freshness schedule, and perf-baseline-freshness.sh reads this line: a local, unlike a
# variable, cannot be overridden by -var, TF_VAR_ or a tfvars file.
locals {
  perf_schedules_enabled = false
}

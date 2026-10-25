#!/usr/bin/env bash
# shellcheck disable=SC2034  # the fixture variables are read by eval'd function bodies and call sites
# Docker-free checks that the main perf SUT runs at the shipped event-log budget, that growth.js gets
# its 256 MiB for the phase through a gauge-confirmed runtime change, and that perf-test-compare.sh
# keys behaviours.*, rig_valid_peak_achieved_rps and tls_handshake.* on the main SUT's budget method.
# The call sites, guards, helpers and compare program are lifted from the real scripts, not copied.
# Run: .buildkite/scripts/test/perf-default-budget-test.sh
#   (PERF_RUN_SCRIPT / PERF_COVERAGE_LIB / PERF_COMPARE_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_COMPARE_SCRIPT PERF_COVERAGE_LIB PERF_RUN_SCRIPT
F="${PERF_RUN_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-run.sh}"
COV="${PERF_COVERAGE_LIB:-$REPO_ROOT/.buildkite/scripts/steps/lib/perf-path-coverage.sh}"
CMP="${PERF_COMPARE_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh}"
BUDGETS="$REPO_ROOT/mockserver-performance-test/perf-budgets.json"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
contains() { # name needle haystack
  if grep -qF -- "$2" <<<"$3"; then ok "$1"; else bad "$1: '$2' not in '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-default-budget-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

extract() { # a top-level function's text, from `name() {` to its closing `}`
  awk -v n="$1" '$0 ~ "^"n"\\(\\) *\\{" {p=1; print; if ($0 ~ /}$/) exit; next} p {print} p && /^}/ {exit}' "$F"
}
block() { # file first_line_regex -> lines from the first match through the next line that is exactly `fi`
  awk -v r="$2" '!p && $0 ~ r {p=1} p {print} p && /^fi$/ {exit}' "$1"
}
call_site() { # file regex -> the first non-comment match from the regex on, cut at its `\` or `||`
  CS_RE="$2" awk '$0 !~ /^[[:space:]]*#/ && match($0, ENVIRON["CS_RE"]) {print substr($0, RSTART); exit}' "$1" \
    | sed -E 's/[[:space:]]+(\\|\|\|.*)$//'
}
load() { local fn body; for fn in "$@"; do body="$(extract "$fn")"; if [ -n "$body" ]; then eval "$body"; else bad "function $fn not found in $F"; fi; done; }

echo "--- 1. which SUTs are handed MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES"
load start_mockserver cpuset_arg require_dns_hostname
TOPO_LIB="$(dirname "$F")/lib/perf-cpu-topology.sh" # numa_mems_flag, which start_mockserver calls
[ -f "$TOPO_LIB" ] || { bad "$TOPO_LIB not found"; echo "FAILED: $FAILS check(s)" >&2; exit 1; }
# shellcheck source=../steps/lib/perf-cpu-topology.sh
. "$TOPO_LIB"
ENV_BLOCK="$(block "$F" '^PERF_MAX_EVENT_LOG_BYTES_SET=')
$(awk '/^HARNESS_FIXED_EVENT_LOG_BYTES=|^GROWTH_EVENT_LOG_BYTES=|^if \[ -n "\$\{PERF_RELEASE_COMPARISON:-\}" \]/' "$F")"
grep -q 'PERF_RELEASE_COMPARISON' <<<"$ENV_BLOCK" || bad "the release-comparison budget line not found in $F"
grep -q '^HARNESS_FIXED_EVENT_LOG_BYTES=' <<<"$ENV_BLOCK" || bad "HARNESS_FIXED_EVENT_LOG_BYTES not found in $F"
SITE_upstream="$(call_site "$F" 'start_mockserver "\$UPSTREAM"')"
SITE_main="$(call_site "$F" 'start_mockserver "\$SERVER"')"
SITE_coverage="$(call_site "$COV" 'start_mockserver "\$name" "\$SERVER_CPUS"')"
SITE_covdl="$(call_site "$COV" 'start_mockserver "\$COV_DL_UPSTREAM"')"
for s in upstream main coverage covdl; do v="SITE_$s"; [ -n "${!v}" ] || bad "call site '$s' not found"; done
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }
diag_jvm_opts() { echo "-Dstub.diag=$1"; }
compose_java_tool_options() { printf '%s' "$2"; }
docker() { if [ "$1" = run ]; then shift; printf '%s\n' "$@" > "$WORK/run.args"; fi; }
budget_of() { # site [VAR=value ...] -> the budget the container is handed, or "absent"
  local site="SITE_$1"; shift
  (
    unset PERF_MAX_EVENT_LOG_BYTES PERF_INFO_MAX_EVENT_LOG_BYTES PERF_RELEASE_COMPARISON
    for kv in "$@"; do export "${kv?}"; done
    eval "$ENV_BLOCK"
    UPSTREAM=up UPSTREAM_ALIAS=mockserver-upstream SERVER=sut name=cov COV_DL_UPSTREAM=covdl fdir="$WORK" UPSTREAM_CPUS="" SERVER_CPUS="" UPSTREAM_PORT=1080 SUT_PORT=1080
    SERVER_ALIAS=mockserver alias=cov SERVER_MEMORY=2g mem=2g FILE_BODY_MOUNT="" mount="" level=ERROR
    NETWORK=n PERF_NETWORK_MODE=bridge DIAG_DIR="$WORK/diag" MOCKSERVER_IMAGE=img SUT_IMAGE_JAVA_TOOL_OPTIONS="" START_EXTRA_ENV=()
    : > "$WORK/run.args"
    eval "${!site}"
    awk -F= '$1 == "MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES" {sub("^[^=]*=", ""); v = $0; found = 1; exit}
      END {print (!found ? "absent" : (v == "" ? "empty" : v))}' "$WORK/run.args"
  )
}
check "main SUT gets no budget by default (shipped default)" "absent" "$(budget_of main)"
check "main SUT follows PERF_MAX_EVENT_LOG_BYTES" "536870912" "$(budget_of main PERF_MAX_EVENT_LOG_BYTES=536870912)"
check "main SUT ignores PERF_INFO_MAX_EVENT_LOG_BYTES" "absent" "$(budget_of main PERF_INFO_MAX_EVENT_LOG_BYTES=104857600)"
check "an empty PERF_MAX_EVENT_LOG_BYTES is the shipped default" "absent" "$(budget_of main PERF_MAX_EVENT_LOG_BYTES=)"
check "a release comparison starts the main SUT at 256 MiB" "268435456" "$(budget_of main PERF_RELEASE_COMPARISON=8.0.0)"
check "a release comparison keeps an explicit override" "536870912" "$(budget_of main PERF_RELEASE_COMPARISON=8.0.0 PERF_MAX_EVENT_LOG_BYTES=536870912)"
for s in upstream coverage covdl; do
  check "$s SUT keeps the fixed 256 MiB" "268435456" "$(budget_of "$s")"
  check "$s SUT follows PERF_MAX_EVENT_LOG_BYTES" "536870912" "$(budget_of "$s" PERF_MAX_EVENT_LOG_BYTES=536870912)"
done
CLU_LINE="$(grep -E '^[[:space:]]*-e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES=' "$F" || true)"
check "the clustered nodes are handed the fixed harness budget" '-e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES="$HARNESS_FIXED_EVENT_LOG_BYTES" \' "$(sed -E 's/^[[:space:]]+//' <<<"$CLU_LINE")"
growth_target() { ( unset PERF_MAX_EVENT_LOG_BYTES PERF_RELEASE_COMPARISON; [ $# -eq 0 ] || export "${1?}"; eval "$ENV_BLOCK"; echo "$GROWTH_EVENT_LOG_BYTES" ); }
check "growth.js targets 256 MiB by default" "268435456" "$(growth_target)"
check "growth.js follows PERF_MAX_EVENT_LOG_BYTES" "536870912" "$(growth_target PERF_MAX_EVENT_LOG_BYTES=536870912)"

echo "--- 2. PERF_MAX_EVENT_LOG_BYTES guard and the tuned profile"
guard_rc() { # value -> exit code of the env block
  local rc=0
  env -u PERF_MAX_EVENT_LOG_BYTES -u PERF_RELEASE_COMPARISON ${1:+PERF_MAX_EVENT_LOG_BYTES="$1"} bash -c "set -euo pipefail; $ENV_BLOCK" >/dev/null 2>&1 || rc=$?
  echo "$rc"
}
check "unset passes" "0" "$(guard_rc "")"
check "a positive integer passes" "0" "$(guard_rc 268435456)"
for v in 0 -1 abc 1e8 "100 MiB"; do check "'$v' refused" "1" "$(guard_rc "$v")"; done
PROFILE_BLOCK="$(block "$F" '^CONFIG_PROFILE="default"$')"
[ -n "$PROFILE_BLOCK" ] || bad "CONFIG_PROFILE trigger block not found"
profile_of() { # [PERF_MAX_EVENT_LOG_BYTES value] -> CONFIG_PROFILE after the env block and trigger
  ( unset PERF_SO_BACKLOG PERF_SERVER_JAVA_OPTS PERF_SERVER_MEMORY PERF_MAX_EVENT_LOG_BYTES PERF_INFO_MAX_EVENT_LOG_BYTES PERF_RELEASE_COMPARISON
    PERF_LARGE_HEAP_PROFILE=false; [ $# -eq 0 ] || export PERF_MAX_EVENT_LOG_BYTES="$1"
    eval "$ENV_BLOCK"; eval "$PROFILE_BLOCK"; echo "$CONFIG_PROFILE" )
}
check "no override is the default profile" "default" "$(profile_of)"
check "an empty override is the default profile" "default" "$(profile_of "")"
check "PERF_MAX_EVENT_LOG_BYTES marks the run tuned" "tuned" "$(profile_of 268435456)"

echo "--- 3. the fail-closed budget guard at config resolution"
load gauge_value info_els_method main_els_guard main_els_json els_default_divisor els_inflight_divisor
check "retention divisor: 20 at ERROR, WARN, INFO and when unset" "20 20 20 20" "$(els_default_divisor ERROR) $(els_default_divisor WARN) $(els_default_divisor INFO) $(els_default_divisor "")"
check "retention divisor: 12 at DEBUG and TRACE" "12 12" "$(els_default_divisor DEBUG) $(els_default_divisor TRACE)"
check "in-flight floor divisor: 7 at ERROR, INFO and unset, 12 at DEBUG and TRACE" "7 7 7 12 12" \
  "$(els_inflight_divisor ERROR) $(els_inflight_divisor INFO) $(els_inflight_divisor "") $(els_inflight_divisor DEBUG) $(els_inflight_divisor TRACE)"
check "no other hard-coded divisor case remains" "0" "$(grep -cE 'ELS_DIVISOR=(7|12|20)|int\(hk/(7|12|20)\)' "$F" || true)"
GUARD_SITE="$(grep -n 'main_els_guard "\$PERF_MAX_EVENT_LOG_BYTES" "\$MAX_EVENT_LOG_VAL" "\$MAIN_ELS_RESOLVED_BYTES"' "$F" | grep -v '^[0-9]*:[[:space:]]*#' || true)"
[ -n "$GUARD_SITE" ] || bad "the guard is not fed the requested, handed and resolved budgets in $F"
GUARD_LOOP="$(awk '/^while IFS= read -r ELS_GUARD_ERROR; do$/ {p=1} p {print} p && /^done/ {exit}' "$F")"
check "the guard feeds CONFIG_ERRORS" "true" "$(grep -q 'CONFIG_ERRORS+=' <<<"$GUARD_LOOP" && echo true || echo false)"
check "the resolved budget is read from the startup scrape" "true" \
  "$(grep -q '^MAIN_ELS_RESOLVED_BYTES="$(gauge_value mock_server_event_log_max_retained_bytes <<<"$CONFIG_METRICS")"$' "$F" && echo true || echo false)"
guard() { main_els_guard "$@" | grep -c . || true; }
check "default, nothing handed, gauge > 0: sound" "0" "$(guard "" "" 135115776)"
check "default but a budget was handed: refused" "1" "$(guard "" 268435456 268435456)"
check "default, gauge absent: refused" "1" "$(guard "" "" "")"
check "default, gauge 0 (bound disabled): refused" "1" "$(guard "" "" 0)"
check "override handed and resolved: sound" "0" "$(guard 268435456 268435456 268435456)"
check "override not handed: refused" "1" "$(guard 268435456 "" 268435456)"
check "override handed but resolved differently: refused" "1" "$(guard 268435456 268435456 135115776)"
check "override handed, gauge absent (a release before the gauge): accepted on the container env" "0" "$(guard 268435456 268435456 "")"
check "override not handed, gauge absent: refused" "1" "$(guard 268435456 "" "")"
contains "the gauge failure names the gauge and the remedy" "mock_server_event_log_max_retained_bytes='' (absent" "$(main_els_guard "" "" "")"
R="$(main_els_json "" 47290368 115456 135115776 966787072 ERROR)"
check "main budget record at the shipped default (heap/20 retention, heap/7 in-flight floor)" \
  '{"method":"shipped-default","requested_bytes":null,"resolved_max_event_log_bytes":47290368,"resolved_max_log_entries":115456,"resolved_max_in_flight_bytes":135115776,"expected_default_bytes":47290368,"expected_in_flight_cap_bytes":135115776}' \
  "$(jq -c '{method, requested_bytes, resolved_max_event_log_bytes, resolved_max_log_entries, resolved_max_in_flight_bytes, expected_default_bytes, expected_in_flight_cap_bytes}' <<<"$R")"
check "main budget record with an override: the in-flight cap follows a larger budget" "fixed-268435456 268435456 observed 268435456" \
  "$(main_els_json 268435456 268435456 115456 268435456 966787072 ERROR | jq -r '"\(.method) \(.requested_bytes) \(.resolved_source) \(.expected_in_flight_cap_bytes)"')"
check "a budget of 0 disables the in-flight cap too" "0" \
  "$(main_els_json 0 0 115456 0 966787072 ERROR | jq -r '.expected_in_flight_cap_bytes')"
check "at INFO retention is heap/20 and the in-flight floor heap/7" "47290368 135115776" \
  "$(main_els_json "" 47290368 115456 135115776 966787072 INFO | jq -r '"\(.expected_default_bytes) \(.expected_in_flight_cap_bytes)"')"
check "at DEBUG both divisors are 12" "78817280 78817280" \
  "$(main_els_json "" 78817280 115456 78817280 966787072 DEBUG | jq -r '"\(.expected_default_bytes) \(.expected_in_flight_cap_bytes)"')"
check "unreadable gauges stay null" "null unavailable null" \
  "$(main_els_json "" "" "" "" "" ERROR | jq -r '"\(.resolved_max_event_log_bytes) \(.resolved_source) \(.expected_in_flight_cap_bytes)"')"

echo "--- 4. the config record carries the budget and survives an absent env"
CFG_BLOCK="$(awk '/^CONFIG_JSON="\$\(jq -n \\$/ {p=1} p {print} p && /^  }'"'"'\)"$/ {exit}' "$F")"
[ -n "$CFG_BLOCK" ] || bad "CONFIG_JSON block not found in $F"
config_of() { # handed -> {max_event_log_size_bytes, event_log_budget.method}
  ( MAX_EVENT_LOG_VAL="$1" MAIN_ELS_JSON="$(main_els_json "$1" 47290368 115456 135115776 966787072 ERROR)"
    HEAP_MAX_BYTES=966787072 IMAGE_MAX_AGE_JSON=7 IMAGE_AGE_DAYS=0 IMAGE_STALE=false PROVENANCE_OK=true
    SERVER_PHYS_CORES=null UPSTREAM_PHYS_CORES=null K6_PHYS_CORES=null K6_CFG_PIN_PCT=400
    MS_VERSION=v MS_GIT_HASH="" MOCKSERVER_IMAGE=i IMAGE_DIGEST=d IMAGE_REVISION="" ATTRIBUTED_COMMIT=c ATTRIBUTED_SRC=s
    HARNESS_COMMIT=h IMAGE_CREATED="" PROVENANCE_GRACE_UNTIL="" PERF_JVM_DIAGNOSTICS=standard JVM_RUNTIME_SRC=observed
    PERF_RELEASE_COMPARISON="" JDK_BUILD=j JAVA_VENDOR=v VM_NAME=n GC_IN_USE=g LOG_LEVEL_VAL=ERROR LOG_LEVEL_SRC=observed
    DISABLE_SYSOUT_VAL=true DISABLE_SYSOUT_SRC=observed SO_BACKLOG_VAL="" CONFIG_PROFILE=default RIG_PROFILE=default
    JAVA_TOOL_OPTS_VAL="" K6_IMAGE=k K6_IMAGE_DIGEST="" SERVER_CPUS="" UPSTREAM_CPUS="" K6_CPUS=""
    eval "$CFG_BLOCK" && jq -c '{max_event_log_size_bytes, method: .event_log_budget.method}' <<<"$CONFIG_JSON" ) 2>&1
}
check "no env handed: null, not a jq error" '{"max_event_log_size_bytes":null,"method":"shipped-default"}' "$(config_of "")"
check "an override is recorded as a number" '{"max_event_log_size_bytes":268435456,"method":"fixed-268435456"}' "$(config_of 268435456)"

echo "--- 5. growth.js: the scoped runtime budget, against a stubbed SUT"
load add_check set_sut_event_log_budget scope_growth_event_log_budget restore_growth_event_log_budget
mkdir -p "$WORK/bin"
# The stub SUT keeps its retention budget in a file and derives the in-flight cap as #51 does:
# max(budget, a fixed heap/7 floor). Modes: fixed (a PUT takes effect), prefix (accepted and ignored,
# as on an image without the configuration fix), putfail, down (metrics unreachable), restorefail
# (only the first PUT takes effect), other (a PUT lands on another value), inflightstuck (the
# in-flight cap keeps the value the first PUT gave it).
cat > "$WORK/bin/curl" <<EOS
#!/bin/sh
mode=\$(cat "$WORK/mode"); url=""; data=""; put=no; prev=""
inflight_of() { f=\$(cat "$WORK/floor"); if [ "\$1" -gt "\$f" ]; then echo "\$1"; else echo "\$f"; fi; }
for a in "\$@"; do case "\$prev" in -d) data="\$a" ;; -X) [ "\$a" = PUT ] && put=yes ;; esac; prev="\$a"; url="\$a"; done
for a in "\$@"; do case "\$a" in http://*) url="\$a" ;; esac; done
case "\$url" in
  */mockserver/configuration)
    [ "\$put" = yes ] || exit 2
    echo "\$data" >> "$WORK/puts"
    [ "\$mode" = putfail ] && exit 22
    n=\$(wc -l < "$WORK/puts")
    v=\$(echo "\$data" | sed -E 's/[^0-9]//g')
    case "\$mode" in
      fixed) echo "\$v" > "$WORK/budget" ;;
      restorefail) [ "\$n" -gt 1 ] || echo "\$v" > "$WORK/budget" ;;
      other) echo 999 > "$WORK/budget" ;;
      inflightstuck) echo "\$v" > "$WORK/budget"; [ "\$n" -gt 1 ] || inflight_of "\$v" > "$WORK/stuck" ;;
    esac ;;
  */mockserver/metrics)
    [ "\$mode" = down ] && exit 7
    echo "mock_server_event_log_max_retained_bytes_total 1.0"
    echo "mock_server_event_log_max_retained_bytes \$(cat "$WORK/budget").0"
    if [ -s "$WORK/stuck" ]; then i=\$(cat "$WORK/stuck"); else i=\$(inflight_of "\$(cat "$WORK/budget")"); fi
    echo "mock_server_event_log_max_in_flight_bytes \$i.0" ;;
  *) exit 7 ;;
esac
EOS
printf '#!/bin/sh\nexit 0\n' > "$WORK/bin/sleep"
chmod +x "$WORK/bin/curl" "$WORK/bin/sleep"
growth_run() { # mode [override] [initial budget] [in-flight floor] -> "<check ok>|<puts sent>|<budget after>|<detail>"
  ( PATH="$WORK/bin:$PATH"; echo "$1" > "$WORK/mode"; echo "${3:-52637696}" > "$WORK/budget"; : > "$WORK/puts"
    echo "${4:-150394880}" > "$WORK/floor"; : > "$WORK/stuck"
    PERF_MAX_EVENT_LOG_BYTES="${2:-}"; GROWTH_EVENT_LOG_BYTES="${2:-268435456}"; VALIDITY_CHECKS=()
    GROWTH_ELS_PRIOR=""; GROWTH_ELS_PRIOR_INFLIGHT=""; GROWTH_ELS_APPLIED=""; GROWTH_ELS_PUT_SENT=false
    GROWTH_ELS_PROBLEM=""; GROWTH_ELS_METHOD=""
    scope_growth_event_log_budget http://127.0.0.1:9 || true
    echo "$(cat "$WORK/budget")" > "$WORK/during"
    echo "$GROWTH_ELS_PRIOR|$GROWTH_ELS_PRIOR_INFLIGHT" > "$WORK/priors"
    restore_growth_event_log_budget http://127.0.0.1:9
    echo "$GROWTH_ELS_METHOD" > "$WORK/method"
    printf '%s|%s|%s|%s\n' "$(jq -r 'select(.name == "growth_event_log_budget_scoped") | .ok' <<<"${VALIDITY_CHECKS[*]}")" \
      "$(grep -c . "$WORK/puts" || true)" "$(cat "$WORK/budget")" \
      "$(jq -r 'select(.name == "growth_event_log_budget_scoped") | .detail' <<<"${VALIDITY_CHECKS[*]}")" )
}
R="$(growth_run fixed)"
check "fixed image: raised for growth" "268435456" "$(cat "$WORK/during")"
check "fixed image: the retention and in-flight priors are read separately" "52637696|150394880" "$(cat "$WORK/priors")"
check "fixed image: check passes, two PUTs, budget restored" "true|2|52637696" "$(cut -d'|' -f1-3 <<<"$R")"
contains "and the in-flight cap is confirmed at its own prior" "with the in-flight cap back at 150394880" "$R"
R="$(growth_run prefix)"
check "image without the fix: check fails, restore still sent" "false|2|52637696" "$(cut -d'|' -f1-3 <<<"$R")"
D="$(cut -d'|' -f4- <<<"$R")"
contains "its failure says the image predates the configuration fix" "predates the runtime-configuration fix" "$D"
contains "its failure names the gauge" "gauge mock_server_event_log_max_retained_bytes still reads '52637696'" "$D"
contains "its failure names the expected value" "expected 268435456" "$D"
R="$(growth_run putfail)"
check "a failed PUT fails the check" "false" "$(cut -d'|' -f1 <<<"$R")"
contains "and says the PUT failed" "failed (HTTP error or unreachable)" "$R"
check "and does not blame the image" "false" "$(grep -q 'predates' <<<"$R" && echo true || echo false)"
R="$(growth_run other)"
check "a PUT that lands on another value fails the check" "false" "$(cut -d'|' -f1 <<<"$R")"
contains "and is reported unconfirmed with what the gauge read" "unconfirmed (gauge mock_server_event_log_max_retained_bytes read '999'" "$R"
check "and does not blame the image" "false" "$(grep -q 'predates' <<<"$R" && echo true || echo false)"
R="$(growth_run inflightstuck)"
check "an in-flight cap left off its pre-growth value fails the check" "false|2|52637696" "$(cut -d'|' -f1-3 <<<"$R")"
contains "and names the in-flight gauge" "mock_server_event_log_max_in_flight_bytes) reads '268435456', not its pre-growth '150394880'" "$R"
R="$(growth_run fixed "" 365000000)"
check "a resolved budget already above the target is never lowered: no PUT, check passes" "true|0|365000000" "$(cut -d'|' -f1-3 <<<"$R")"
check "and is recorded as startup-sufficient" "startup-sufficient" "$(cat "$WORK/method")"
R="$(growth_run fixed "" 52637696 300000000)"
check "an in-flight floor alone above the target does not count: the retention budget is raised" "true|2|52637696" "$(cut -d'|' -f1-3 <<<"$R")"
check "and is recorded as scoped" "scoped" "$(cat "$WORK/method")"
contains "with the in-flight cap confirmed back at its floor" "with the in-flight cap back at 300000000" "$R"
R="$(growth_run down)"
check "an unreadable pre-growth budget sends no PUT and fails the check" "false|0" "$(cut -d'|' -f1-2 <<<"$R")"
R="$(growth_run restorefail)"
check "a restore that does not take effect fails the check" "false|2|268435456" "$(cut -d'|' -f1-3 <<<"$R")"
contains "and says the restore failed, with what the gauge read" "restoring maxEventLogSizeInBytes=52637696 after growth.js did not take effect: gauge mock_server_event_log_max_retained_bytes reads '268435456'" "$R"
R="$(growth_run prefix 536870912)"
check "with PERF_MAX_EVENT_LOG_BYTES set: no PUT, check passes" "true|0" "$(cut -d'|' -f1-2 <<<"$R")"
check "and is recorded as startup" "startup" "$(cat "$WORK/method")"
R="$(growth_run fixed)"
check "a raised and restored budget is recorded as scoped" "scoped" "$(cat "$WORK/method")"
GROWTH_BLOCK="$(awk '/^[[:space:]]*echo "--- growth.js \(sustained load/ {p=1} p {print} p && /^[[:space:]]*restore_growth_event_log_budget/ {exit}' "$F")"
[ -n "$GROWTH_BLOCK" ] || bad "growth.js block not found in $F"
order="$(grep -nE 'scope_growth_event_log_budget "|GROWTH_ELS_T0=|run /k6/growth.js|GROWTH_ELS_T1=|restore_growth_event_log_budget "' <<<"$GROWTH_BLOCK" | cut -d: -f1 | tr '\n' ' ')"
check "growth.js runs between the raise and the restore, inside the sampled window" "5" "$(wc -w <<<"$order" | tr -d ' ')"
check "and in that order" "$(tr ' ' '\n' <<<"$order" | grep . | sort -n | tr '\n' ' ')" "$order"
check "the restore targets the main SUT" "true" "$(grep -qE '^[[:space:]]*GROWTH_SUT_BASE_URL="\$\{SERVER_METRICS_URL%/mockserver/metrics\}"$' <<<"$GROWTH_BLOCK" && echo true || echo false)"

echo "--- 6. event_log_scaling: per-row utilisation, the growth window and drops"
load event_log_window_stats growth_phase_json
diag_row() { # ts dropped in_flight entries bytes max_bytes max_entries evicted scrape_ts -> a 36-column diag row
  local c=() i
  for i in $(seq 1 36); do c[i]=""; done
  c[1]=$1; c[12]=$2; c[15]=$3; c[17]=$4; c[18]=$5; c[19]=$6; c[20]=$7; c[21]=$8; c[33]=$9
  local IFS=,; echo "${c[*]:1}"
}
{ echo "ts,elapsed_s,c3,c4,c5,c6,c7,c8,c9,c10,c11,dropped,c13,c14,in_flight,c16,entries,bytes,max_bytes,max_entries,evicted,c22,c23,c24,c25,c26,c27,c28,c29,c30,c31,c32,scrape_ts,c34,c35,c36"
  diag_row 100 0 1000 500 120000000 135115776 115456 0 101
  diag_row 110 3 4000 90000 130000000 135115776 115456 10 ""
  diag_row 140 3 4000 1000 1000000 268435456 115456 10 160
  diag_row 200 3 9000 115456 150000000 268435456 115456 90000 201
  diag_row 300 5 2000 100 1000 135115776 115456 90000 301
  echo "310,210,,,,,,,,,,5,,,2000,,,,,,"; } > "$WORK/diag.csv"
read -r ME MB PE PB PIF EV ROWS DR CU BU GME GMB GPE GPB GCU GBU GR <<<"$(event_log_window_stats "$WORK/diag.csv" 150 250)"
check "resolved bounds come from rows outside the growth window" "115456 135115776" "$ME $MB"
check "peaks, in-flight, evictions and drops over the run" "115456 150000000 9000 90000 5 5" "$PE $PB $PIF $EV $DR $ROWS"
check "utilisation is per row against that row's bounds" "1.0000 0.9621" "$CU $BU"
check "a row sampled before the PUT but scraped after it is in the window (scrape_ts, else ts)" "115456 268435456 115456 150000000 1.0000 0.5588 2" "$GME $GMB $GPE $GPB $GCU $GBU $GR"
read -r ME MB _ <<<"$(event_log_window_stats "$WORK/diag.csv" "" "")"
check "with no window every row counts as main" "115456 268435456" "$ME $MB"
check "an unreadable file degrades to zeros" "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0" "$(event_log_window_stats "$WORK/none.csv" 1 2)"
phase() { # count_util rows [method] -> count_ring_filled budget_method
  ( GROWTH_ELS_METHOD="${3-scoped}" GROWTH_EVENT_LOG_BYTES=268435456 GROWTH_ELS_PRIOR=135115776 GROWTH_ELS_APPLIED=268435456
    GROWTH_ELS_MAX_ENTRIES=115456 GROWTH_ELS_MAX_BYTES=268435456 GROWTH_ELS_PEAK_ENTRIES=115456 GROWTH_ELS_PEAK_BYTES=150000000
    GROWTH_ELS_COUNT_UTIL="$1" GROWTH_ELS_BYTES_UTIL=0.5588 GROWTH_ELS_ROWS="$2" PERF_EVENT_LOG_APPROACH_RATIO=0.90
    growth_phase_json | jq -r '"\(.count_ring_filled) \(.budget_method) \(.resolved_max_log_entries)"' )
}
check "a full count ring is recorded as filled" "true scoped 115456" "$(phase 1.0000 40)"
check "a byte-bound fill is not" "false scoped 115456" "$(phase 0.3100 40)"
check "no samples in the window: unknown" "null scoped null" "$(phase 0 0)"
check "the method is carried through" "true startup-sufficient 115456" "$(phase 1.0000 40 startup-sufficient)"
check "growth never ran: no method" "null null null" "$(phase 0 0 "")"
ELS_BLOCK="$(awk '/^EVENT_LOG_SCALING_JSON="\$\(jq -n \\$/ {p=1} p {print} p && /^  }'"'"'\)"$/ {exit}' "$F")"
[ -n "$ELS_BLOCK" ] || bad "EVENT_LOG_SCALING_JSON block not found in $F"
scaling_of() { # handed -> event_log_scaling {requested, dropped, growth filled}
  ( HEAP_MAX_BYTES=966787072 LOG_LEVEL_VAL=ERROR ELS_DIVISOR=7 ELS_MAX_ENTRIES=115456 ELS_MAX_BYTES=135115776
    MAX_EVENT_LOG_VAL="$1" ELS_EXPECTED_DEFAULT_BYTES=135115776 ELS_PEAK_ENTRIES=115456 ELS_PEAK_BYTES=150000000
    ELS_PEAK_INFLIGHT=9000 ELS_EVICTED=90000 ELS_COUNT_UTIL=1.0 ELS_BYTES_UTIL=0.96 ELS_MEAN_ENTRY=1299
    ELS_BINDING=count ELS_BOUND_REACHED=true PERF_EVENT_LOG_APPROACH_RATIO=0.90 ELS_LOG_ROWS=4 ELS_LARGE_HEAP_BOOL=false
    ELS_DROPPED=5 GROWTH_PHASE_JSON='{"count_ring_filled":true}'
    eval "$ELS_BLOCK" && jq -c '{requested_max_event_log_bytes, dropped_log_events, filled: .growth_phase.count_ring_filled}' <<<"$EVENT_LOG_SCALING_JSON" ) 2>&1
}
check "event_log_scaling records drops and the growth phase; nothing handed is null" \
  '{"requested_max_event_log_bytes":null,"dropped_log_events":5,"filled":true}' "$(scaling_of "")"
check "event_log_scaling records a handed budget" \
  '{"requested_max_event_log_bytes":268435456,"dropped_log_events":5,"filled":true}' "$(scaling_of 268435456)"

echo "--- 7. perf-test-compare.sh keys main-SUT metrics on the main SUT's budget method"
COMPARE="$(awk '/^COMPARE='"'"'$/ {on = 1; next} on && /^'"'"'$/ {exit} on' "$CMP")"
[ -n "$COMPARE" ] || bad "COMPARE program not found in $CMP"
run() { # method p95 -> a run measuring the main SUT under that budget method
  jq -nc --arg m "$1" --argjson p "$2" '{agent: {instance_type: "c5.12xlarge"},
    behaviours: {match_http: {p95_ms: $p, p99_ms: ($p * 3), error_rate: 0}},
    rig_valid_peak_achieved_rps: (40000 + $p),
    tls_handshake: {tls13: {handshake_p50_ms: $p, error_rate: 0}, jdk: {handshake_p50_ms: ($p + 1), error_rate: 0}},
    growth: {cpu_pct: {ratio: 1.01, ratio_method: "occupancy_window"}, heap_used_bytes: {ratio: 1.0, min_last_window: 1000000}, p95_ms: {ratio: 1.1}},
    forward_guard: {error_rate: 0}}
    + (if $m == "" then {} else {config: {event_log_budget: {method: $m}}} end)'
}
compare() { # head_json baseline_json -> compare result
  jq -n --argjson head "$1" --argjson baseline "$2" --slurpfile b "$BUDGETS" --argjson minbaseline 5 \
    '($b[0].budgets) as $budgets | $head | '"$COMPARE"
}
row() { jq -r --arg n "$2" '.rows[] | select(.name == $n) | if .status == "no-baseline" then "no-baseline" else "\(.baseline)" end' <<<"$1"; }
LEGACY="$(for p in 30 31 29 30 32; do run "" "$p"; done | jq -sc .)"
NEWBASE="$(for p in 5 6 5 6 5; do run shipped-default "$p"; done | jq -sc .)"
R="$(compare "$(run shipped-default 5.3)" "$LEGACY")"
for m in match_http.p95_ms match_http.error_rate rig_valid_peak_achieved_rps tls13.handshake_p50_ms tls13.error_rate; do
  check "against forced-256MiB history: $m has no baseline" "no-baseline" "$(row "$R" "$m")"
done
check "growth.* keeps its history" "1.01" "$(row "$R" growth.cpu_ratio)"
check "the jdk handshake arm (its own SUT) keeps its history" "31" "$(row "$R" jdk.handshake_p50_ms)"
check "forward.error_rate keeps its history" "0" "$(row "$R" forward.error_rate)"
check "a run without the field reads as fixed-268435456" "5" "$(jq -r '.baseline_main_els_other' <<<"$R")"
check "head method reported" "shipped-default" "$(jq -r '.head_main_els_method' <<<"$R")"
R="$(compare "$(run shipped-default 5.3)" "$NEWBASE")"
check "against shipped-default history: behaviour p95 compared" "5" "$(row "$R" match_http.p95_ms)"
check "against shipped-default history: rig-valid peak compared" "40005" "$(row "$R" rig_valid_peak_achieved_rps)"
check "against shipped-default history: handshake compared" "5" "$(row "$R" tls13.handshake_p50_ms)"
R="$(compare "$(run fixed-268435456 30)" "$LEGACY")"
check "an explicit 256 MiB matches the old runs" "30" "$(row "$R" match_http.p95_ms)"
for n in 1 4; do
  R="$(compare "$(run shipped-default 5.3)" "$(jq -c ".[:$n]" <<<"$NEWBASE")")"
  check "error_rate with $n shipped-default run(s) stays new" "no-baseline" "$(row "$R" match_http.error_rate)"
  check "handshake error_rate (all-instance) with $n shipped-default run(s) stays new" "no-baseline" "$(row "$R" tls13.error_rate)"
done
R="$(compare "$(run shipped-default 40)" "$NEWBASE")"
check "a real regression is still flagged" "true" \
  "$(jq -r '.rows[] | select(.name == "match_http.p95_ms") | .regression' <<<"$R")"
ARMS="$(jq -c '.[0].behaviours += {extra_arm: {p95_ms: 1, p99_ms: 1, error_rate: 0}}' <<<"$NEWBASE" | jq -c '.[0]')"
R="$(compare "$(run shipped-default 5.3)" "$(jq -c --argjson a "$ARMS" '.[1:] + [$a]' <<<"$NEWBASE")")"
check "the arm-set fingerprint still applies alongside" "4" \
  "$(jq -r '.baseline_k6_comparable' <<<"$R")"

echo "--- 8. the main-SUT reset annotation"
NOTE_BLOCK="$(awk '/^ELS_COMPARABLE=/ {p=1} p {print} p && /^fi$/ {exit}' "$CMP")"
[ -n "$NOTE_BLOCK" ] || bad "ELS_NOTE block not found in $CMP"
note_of() { ( RESULT_CMP="$1"; MIN_BASELINE=5; eval "$NOTE_BLOCK"; printf '%s' "$ELS_NOTE" ); }
N="$(note_of "$(compare "$(run shipped-default 5.3)" "$LEGACY")")"
contains "reset note shown against old-method history" "Main-SUT baseline reset" "$N"
contains "reset note says the metrics stay new below MIN_BASELINE" 'stay `:new: new`' "$N"
N="$(note_of "$(compare "$(run shipped-default 5.3)" "$(jq -c '. + $n' --argjson n "$NEWBASE" <<<"$LEGACY")")")"
check "past MIN_BASELINE the note says they compare" "true" "$(grep -q 'enough to compare' <<<"$N" && ! grep -q 'stay `:new:' <<<"$N" && echo true || echo false)"
check "no note when every run shares the method" "" "$(note_of "$(compare "$(run shipped-default 5.3)" "$NEWBASE")")"
check "the annotation body renders ELS_NOTE" "true" \
  "$(grep -qF '${ELS_NOTE}' <<<"$(grep -F '${PROVENANCE}' "$CMP")" && echo true || echo false)"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all main-SUT event-log budget checks passed"

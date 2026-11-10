#!/usr/bin/env bash
# shellcheck disable=SC2034  # the fixture variables are read by eval'd function bodies and call sites
# Docker-free checks that the INFO-log-level SUT runs at the shipped event-log budget, that the
# fixed-budget SUTs keep the harness budget, that perf-test-compare.sh keys the info_* baseline on it,
# and that the INFO arm reports its per-rung drops and ring / in-flight peaks (event_log_pressure).
# The main SUT's budget is checked in perf-default-budget-test.sh.
# The call sites, env guard and compare program are lifted from the real scripts, not copied.
# Run: .buildkite/scripts/test/perf-info-budget-test.sh
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
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-info-budget-test.XXXXXX")"
# A subshell signalled just after it forks runs this trap too; only the main shell may remove $WORK.
in_main_shell() { [ "${BASHPID:-$(exec sh -c 'echo "$PPID"')}" = "$$" ]; }
trap '! in_main_shell || rm -rf "$WORK"' EXIT

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

echo "--- 1. which SUTs are handed MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES"
for fn in start_mockserver cpuset_arg require_dns_hostname; do
  body="$(extract "$fn")"
  if [ -z "$body" ]; then bad "function $fn not found in $F"; continue; fi
  eval "$body"
done
MAIN_ENV_BLOCK="$(block "$F" '^PERF_MAX_EVENT_LOG_BYTES_SET=')"
INFO_ENV_BLOCK="$(block "$F" '^PERF_INFO_MAX_EVENT_LOG_BYTES=')"
[ -n "$MAIN_ENV_BLOCK" ] && [ -n "$INFO_ENV_BLOCK" ] || bad "PERF_MAX_EVENT_LOG_BYTES/PERF_INFO_MAX_EVENT_LOG_BYTES block not found"
ENV_BLOCK="$MAIN_ENV_BLOCK
$(awk '/^HARNESS_FIXED_EVENT_LOG_BYTES=|^GROWTH_EVENT_LOG_BYTES=/' "$F")
$INFO_ENV_BLOCK"
SITE_upstream="$(call_site "$F" 'start_mockserver "\$UPSTREAM"')"
SITE_main="$(call_site "$F" 'start_mockserver "\$SERVER"')"
SITE_info="$(call_site "$F" 'start_mockserver "\$INFO_SERVER"')"
SITE_coverage="$(call_site "$COV" 'start_mockserver "\$name" "\$SERVER_CPUS"')"
SITE_covdl="$(call_site "$COV" 'start_mockserver "\$COV_DL_UPSTREAM"')"
for s in upstream main info coverage covdl; do v="SITE_$s"; [ -n "${!v}" ] || bad "call site '$s' not found"; done
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }

# shellcheck source=../steps/lib/perf-cpu-topology.sh
. "$(dirname "$F")/lib/perf-cpu-topology.sh" # numa_mems_flag, which start_mockserver calls
diag_jvm_opts() { echo "-Dstub.diag=$1"; }
compose_java_tool_options() { printf '%s' "$2"; }
docker() { if [ "$1" = run ]; then shift; printf '%s\n' "$@" > "$WORK/run.args"; fi; }
args_of() { # site [VAR=value ...] -> the docker run arguments of that call site, one per line
  local site="SITE_$1"; shift
  (
    unset PERF_MAX_EVENT_LOG_BYTES PERF_INFO_MAX_EVENT_LOG_BYTES
    for kv in "$@"; do export "${kv?}"; done
    eval "$ENV_BLOCK"
    UPSTREAM=up UPSTREAM_ALIAS=mockserver-upstream SERVER=sut INFO_SERVER=info name=cov COV_DL_UPSTREAM=covdl fdir="$WORK" UPSTREAM_CPUS="" SERVER_CPUS="" UPSTREAM_PORT=1080 SUT_PORT=1080
    INFO_SERVER_ALIAS=mockserver-info SERVER_ALIAS=mockserver alias=cov SERVER_MEMORY=2g mem=2g FILE_BODY_MOUNT="" mount="" level=ERROR
    NETWORK=n PERF_NETWORK_MODE=bridge DIAG_DIR="$WORK/diag" MOCKSERVER_IMAGE=img SUT_IMAGE_JAVA_TOOL_OPTIONS="" START_EXTRA_ENV=()
    : > "$WORK/run.args"
    # A helper the call site needs but this test did not lift fails here, not silently.
    eval "${!site}" 2>"$WORK/args.err"
    if grep -q 'command not found' "$WORK/args.err"; then echo "<missing helper: $(grep -m1 'command not found' "$WORK/args.err")>"; exit 1; fi
    cat "$WORK/run.args"
  )
}
budget_of() { # site [VAR=value ...] -> the budget the container is handed, or "absent"
  local a v; a="$(args_of "$@")"
  [ -n "$a" ] || { echo "<no docker run>"; return; }
  case "$a" in "<missing helper"*) echo "$a"; return ;; esac
  v="$(awk -F= '$1 == "MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES" {sub("^[^=]*=", ""); v = $0; found = 1; exit}
    END {print (!found ? "absent" : (v == "" ? "empty" : v))}' <<<"$a")"
  echo "$v"
}
for s in upstream coverage covdl; do
  check "$s SUT keeps the harness 256 MiB" "268435456" "$(budget_of "$s")"
  check "$s SUT follows PERF_MAX_EVENT_LOG_BYTES" "536870912" "$(budget_of "$s" PERF_MAX_EVENT_LOG_BYTES=536870912)"
  check "$s SUT ignores PERF_INFO_MAX_EVENT_LOG_BYTES" "268435456" "$(budget_of "$s" PERF_INFO_MAX_EVENT_LOG_BYTES=104857600)"
done
check "INFO SUT gets no budget by default (shipped default)" "absent" "$(budget_of info)"
check "INFO SUT ignores PERF_MAX_EVENT_LOG_BYTES" "absent" "$(budget_of info PERF_MAX_EVENT_LOG_BYTES=536870912)"
check "INFO SUT takes PERF_INFO_MAX_EVENT_LOG_BYTES" "104857600" "$(budget_of info PERF_INFO_MAX_EVENT_LOG_BYTES=104857600)"
check "INFO SUT publishes a host port for its metrics" "127.0.0.1::1080" \
  "$(args_of info | awk 'prev == "-p" {print; exit} {prev = $0}')"

echo "--- 2. the INFO SUT's resolved budget is read from its own gauges"
body="$(extract gauge_value)"; [ -n "$body" ] && eval "$body" || bad "function gauge_value not found in $F"
METRICS='# HELP mock_server_event_log_max_retained_bytes x
mock_server_event_log_max_retained_bytes_total 1.0
mock_server_event_log_max_retained_bytes 7.8854144E7
mock_server_event_log_max_retained_entries 115456.0
jvm_memory_max_bytes{area="nonheap"} -1.0
jvm_memory_max_bytes{area="heap"} 9.66787072E8'
check "byte budget (scientific notation, not the _total series)" "78854144" "$(gauge_value mock_server_event_log_max_retained_bytes <<<"$METRICS")"
check "entry bound" "115456" "$(gauge_value mock_server_event_log_max_retained_entries <<<"$METRICS")"
check "heap ceiling (labelled series)" "966787072" "$(gauge_value 'jvm_memory_max_bytes{area="heap"}' <<<"$METRICS")"
check "absent gauge is empty" "" "$(gauge_value mock_server_event_log_max_retained_bytes <<<"")"

echo "--- 3. PERF_INFO_MAX_EVENT_LOG_BYTES guard and the tuned profile"
guard_rc() { # value -> exit code of the env block
  local rc=0
  env -u PERF_MAX_EVENT_LOG_BYTES -u PERF_INFO_MAX_EVENT_LOG_BYTES ${1:+PERF_INFO_MAX_EVENT_LOG_BYTES="$1"} \
    bash -c "set -euo pipefail; $ENV_BLOCK" >/dev/null 2>&1 || rc=$?
  echo "$rc"
}
check "unset passes" "0" "$(guard_rc "")"
check "a positive integer passes" "0" "$(guard_rc 104857600)"
for v in 0 -1 abc 1e8 "100 MiB"; do check "'$v' refused" "1" "$(guard_rc "$v")"; done
PROFILE_BLOCK="$(block "$F" '^CONFIG_PROFILE="default"$')"
[ -n "$PROFILE_BLOCK" ] || bad "CONFIG_PROFILE trigger block not found"
profile_of() { # [VAR=value] -> CONFIG_PROFILE
  ( unset PERF_SO_BACKLOG PERF_SERVER_JAVA_OPTS PERF_SERVER_MEMORY PERF_MAX_EVENT_LOG_BYTES_SET PERF_INFO_MAX_EVENT_LOG_BYTES
    PERF_LARGE_HEAP_PROFILE=false; [ $# -eq 0 ] || export "${1?}"; eval "$PROFILE_BLOCK"; echo "$CONFIG_PROFILE" )
}
check "no lever is the default profile" "default" "$(profile_of)"
check "PERF_INFO_MAX_EVENT_LOG_BYTES marks the run tuned" "tuned" "$(profile_of PERF_INFO_MAX_EVENT_LOG_BYTES=104857600)"

echo "--- 4. perf-test-compare.sh keys info_* on the INFO event-log budget method"
COMPARE="$(awk '/^COMPARE='"'"'$/ {on = 1; next} on && /^'"'"'$/ {exit} on' "$CMP")"
[ -n "$COMPARE" ] || bad "COMPARE program not found in $CMP"
run() { # method p95 -> a run whose INFO arm measured match_http under that budget method
  jq -nc --arg m "$1" --argjson p "$2" '{agent: {instance_type: "c5.12xlarge"},
    info_log_level_arm: ({measured: true, behaviours: {match_http: {p95_ms: $p, p99_ms: ($p * 3), error_rate: 0}}}
      + (if $m == "" then {} else {config: {event_log_budget: {method: $m}}} end))}'
}
compare() { # head_json baseline_json -> compare result
  jq -n --argjson head "$1" --argjson baseline "$2" --slurpfile b "$BUDGETS" --argjson minbaseline 5 \
    '($b[0].budgets) as $budgets | $head | '"$COMPARE"
}
LEGACY="$(for p in 30 31 29 30 32; do run "" "$p"; done | jq -sc .)"
NEWBASE="$(for p in 5 6 5 6 5; do run shipped-default "$p"; done | jq -sc .)"
unmeasured() { jq -c '.info_log_level_arm.measured = false' <<<"$1"; }
R="$(compare "$(run shipped-default 5.3)" "$LEGACY")"
check "against forced-256MiB history: info p95 has no baseline" "no-baseline" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .status' <<<"$R")"
check "a run without the field reads as fixed-268435456" "5" "$(jq -r '.baseline_info_other' <<<"$R")"
check "head method reported" "shipped-default" "$(jq -r '.head_info_budget_method' <<<"$R")"
R="$(compare "$(run shipped-default 5.3)" "$NEWBASE")"
check "against shipped-default history: info p95 is compared" "5" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .baseline' <<<"$R")"
check "no other-method runs" "0" "$(jq -r '.baseline_info_other' <<<"$R")"
R="$(compare "$(run fixed-268435456 30)" "$LEGACY")"
check "an explicit 256 MiB matches the old runs" "30" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .baseline' <<<"$R")"
R="$(compare "$(run shipped-default 40)" "$NEWBASE")"
check "a real regression is still flagged" "true" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.p95_ms") | .regression' <<<"$R")"
# error_rate is not hardware-keyed, so it exercises the all-instance INFO baseline and its warm-up.
R="$(compare "$(run shipped-default 5.3)" "$LEGACY")"
check "error_rate against forced-256MiB history has no baseline" "no-baseline" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.error_rate") | .status' <<<"$R")"
for n in 1 2 3 4; do
  R="$(compare "$(run shipped-default 5.3)" "$(jq -c ".[:$n]" <<<"$NEWBASE")")"
  check "error_rate with $n shipped-default run(s) stays new" "no-baseline" \
    "$(jq -r '.rows[] | select(.name == "info_match_http.error_rate") | .status' <<<"$R")"
done
R="$(compare "$(run shipped-default 5.3)" "$NEWBASE")"
check "error_rate with 5 shipped-default runs is compared" "0" \
  "$(jq -r '.rows[] | select(.name == "info_match_http.error_rate") | .baseline' <<<"$R")"

echo "--- 5. the INFO-arm reset annotation"
NOTE_BLOCK="$(awk '/^INFO_COMPARABLE=/ {p=1} p {print} p && /^fi$/ {exit}' "$CMP")"
[ -n "$NOTE_BLOCK" ] || bad "INFO_NOTE block not found in $CMP"
note_of() { # compare_result -> INFO_NOTE
  ( RESULT_CMP="$1"; MIN_BASELINE=5; eval "$NOTE_BLOCK"; printf '%s' "$INFO_NOTE" )
}
MIXED="$(jq -c --argjson n "$NEWBASE" --argjson u "[$(unmeasured "$(run "" 30)"),$(unmeasured "$(run shipped-default 5)"),$(unmeasured "$(run shipped-default 5)")]" \
  '. + $n + $u' <<<"$LEGACY")"
R="$(compare "$(run shipped-default 5.3)" "$MIXED")"
check "unmeasured runs are not counted as comparable" "5" "$(jq -r '.baseline_info_comparable' <<<"$R")"
check "unmeasured runs are not counted as other-method" "5" "$(jq -r '.baseline_info_other' <<<"$R")"
R="$(compare "$(unmeasured "$(run shipped-default 5.3)")" "$LEGACY")"
check "an unmeasured head reports itself" "false" "$(jq -r '.head_info_measured' <<<"$R")"
check "no note for an unmeasured head" "" "$(note_of "$R")"
N="$(note_of "$(compare "$(run shipped-default 5.3)" "$LEGACY")")"
check "reset note shown against old-method history" "true" "$(grep -q 'INFO-arm baseline reset' <<<"$N" && echo true || echo false)"
check "reset note says the metrics stay new below MIN_BASELINE" "true" "$(grep -q 'stay `:new: new`' <<<"$N" && echo true || echo false)"
N="$(note_of "$(compare "$(run shipped-default 5.3)" "$(jq -c '. + $n' --argjson n "$NEWBASE" <<<"$LEGACY")")")"
check "past MIN_BASELINE the note says they compare" "true" "$(grep -q 'enough to compare' <<<"$N" && ! grep -q 'stay `:new:' <<<"$N" && echo true || echo false)"
check "no note when every run shares the method" "" "$(note_of "$(compare "$(run shipped-default 5.3)" "$NEWBASE")")"
check "the annotation body renders INFO_NOTE" "true" \
  "$(grep -qF '${INFO_NOTE}' <<<"$(grep -F '${PROVENANCE}' "$CMP")" && echo true || echo false)"

echo "--- 6. event_log_budget derivation"
for fn in counter_sum info_els_method els_default_divisor event_log_counters event_log_peaks event_log_budget_json event_log_sampler start_info_els_sampler stop_info_els_sampler sweep_rung_windows event_log_pressure_json; do
  body="$(extract "$fn")"; [ -n "$body" ] && eval "$body" || bad "function $fn not found in $F"
done
COLS_LINE="$(grep -m1 '^EVENT_LOG_PRESSURE_COLS=' "$F" || true)"
[ -n "$COLS_LINE" ] && eval "$COLS_LINE" || bad "EVENT_LOG_PRESSURE_COLS not found in $F"
check "no requested budget is shipped-default" "shipped-default" "$(info_els_method "")"
check "a requested budget is fixed-<bytes>" "fixed-104857600" "$(info_els_method 104857600)"
check "counters read by their exposed names, drops summed over every reason" "3|1500" \
  "$(printf 'mock_server_dropped_log_events_total{reason="in_flight_bytes"} 1.0\nmock_server_dropped_log_events_total{reason="ring_full"} 2.0\nmock_server_dropped_log_events_created{reason="ring_full"} 1.7E9\nmock_server_evicted_log_entries_total 1500.0\n' | event_log_counters)"
check "an unlabelled drop counter (older server) still reads" "2|1500" \
  "$(printf 'mock_server_dropped_log_events_total 2.0\nmock_server_dropped_log_events_created 1.7E9\nmock_server_evicted_log_entries_total 1500.0\n' | event_log_counters)"
check "absent counters are empty" "|" "$(event_log_counters <<<"")"
printf '100 5\n300 2\n200 9\n' > "$WORK/peaks"; : > "$WORK/nopeaks"
check "peaks are per column" "300 9" "$(event_log_peaks "$WORK/peaks")"
check "no samples, no peaks" "" "$(event_log_peaks "$WORK/nopeaks")"
els() { # peak_bytes peak_entries dropped evicted -> "bound_reached binding" at an 80000000 B / 100000 entry bound
  event_log_budget_json shipped-default "" container-env 80000000 100000 966787072 78817280 "$1" "$2" "$3" "$4" 0.90 \
    | jq -r '"\(.bound_reached) \(.binding)"'
}
check "nothing known: undecided" "null null" "$(els "" "" "" "")"
check "evictions only: reached, bound unknown" "true null" "$(els "" "" "" 5)"
check "no evictions, no samples: not reached, bound unknown" "false null" "$(els "" "" 0 0)"
check "samples below the ratio, evictions unknown: undecided" "null null" "$(els 20000000 9000 "" "")"
check "below the ratio, no evictions: neither" "false neither" "$(els 20000000 9000 0 0)"
check "byte bound reached" "true bytes" "$(els 78000000 9000 0 0)"
check "count bound reached" "true count" "$(els 20000000 99000 0 0)"
check "both reached: the one nearer its limit" "true count" "$(els 76000000 99000 0 3)"
check "both reached, bytes nearer" "true bytes" "$(els 79500000 92000 0 3)"
check "just under the ratio is not reached" "false neither" "$(els 71999999 89999 0 0)"
check "the count ratio itself is reached" "true count" "$(els 20000000 90000 0 0)"
check "the ratio itself is reached" "true bytes" "$(els 72000000 9000 0 0)"
R="$(event_log_budget_json fixed-104857600 104857600 declared "" "" "" "" "" "" "" "" 0.90)"
check "fields carried through" "fixed-104857600 104857600 unavailable" "$(jq -r '"\(.method) \(.requested_bytes) \(.resolved_source)"' <<<"$R")"

echo "--- 7. the INFO arm starts, reads and stops the sampler around its load"
METRICS_STUB='mock_server_event_log_max_retained_bytes 8.0E7
mock_server_event_log_max_retained_entries 100000.0
mock_server_event_log_retained_bytes 4096.0
mock_server_event_log_retained_entries 7.0
mock_server_event_log_in_flight_bytes 1.0
mock_server_event_log_max_in_flight_bytes 1.0E6
mock_server_event_log_ring_occupancy 12.0
mock_server_event_log_ring_capacity 4096.0
mock_server_dropped_log_events_total{reason="in_flight_bytes"} 0.0
mock_server_dropped_log_events_total{reason="ring_full"} 2.0
mock_server_dropped_log_events_created{reason="ring_full"} 1.7E9
mock_server_evicted_log_entries_total 1500.0
jvm_memory_max_bytes{area="heap"} 9.66787072E8'
# Stubbed as executables on PATH, not functions: bash 3.2 ends a backgrounded function after its
# first call into a function-stubbed sleep.
printf '%s\n' "$METRICS_STUB" > "$WORK/metrics"; mkdir -p "$WORK/bin"
# The first scrape fails as curl does on a timeout (28); the sampler must keep going.
cat > "$WORK/bin/curl" <<EOS
#!/bin/sh
n=\$(cat "$WORK/curl-calls" 2>/dev/null || echo 0); n=\$((n + 1)); echo "\$n" > "$WORK/curl-calls.\$\$"; mv "$WORK/curl-calls.\$\$" "$WORK/curl-calls"
[ "\$n" -gt 1 ] || exit 28
case "\$*" in *" http://127.0.0.1:9/mockserver/metrics") cat "$WORK/metrics" ;;
  *" http://127.0.0.1:10/mockserver/metrics") /bin/sleep 1.1; cat "$WORK/metrics"; date -u +%s >> "$WORK/slow-scrape-done" ;;
  *" http://127.0.0.1:11/mockserver/metrics") printf '%s\n' 'mock_server_event_log_retained_bytes 4096.0' \
      'mock_server_event_log_retained_entries 7.0' 'mock_server_dropped_log_events_total{reason="in_flight_bytes"} 0.0'
    printf '%s' 'mock_server_dropped_log_events_total{reason="ring_full"} 12'; exit 28 ;;
  *) exit 7 ;; esac
EOS
printf '#!/bin/sh\nexec /bin/sleep 0.05\n' > "$WORK/bin/sleep"
chmod +x "$WORK/bin/curl" "$WORK/bin/sleep"; PATH="$WORK/bin:$PATH"
wait_samples() { local i; for i in $(seq 1 60); do [ "$(wc -l < "$1")" -ge "$2" ] && return 0; sleep 0.05; done; return 1; }
bounded_stop() { # stop_info_els_sampler, but a hang is reported and broken after 10 s
  local pid="$INFO_ELS_SAMPLER_PID" dog
  ( /bin/sleep 10 & sp=$!; trap 'kill "$sp" 2>/dev/null; exit 0' TERM; wait "$sp"
    echo hung > "$WORK/stop-hung"; [ -z "$pid" ] || kill "$pid" 2>/dev/null ) >/dev/null 2>&1 &
  dog=$!
  stop_info_els_sampler
  kill "$dog" 2>/dev/null || true; wait "$dog" 2>/dev/null || true
  [ ! -e "$WORK/stop-hung" ] || bad "stop_info_els_sampler did not return within 10 s"
}
INFO_ELS_SAMPLER_PID=""; SAMP="$WORK/samples"; : > "$SAMP"; PRESS="$WORK/pressure.csv"
start_info_els_sampler "127.0.0.1:9" "$SAMP" "$PRESS"
wait_samples "$SAMP" 2 || bad "sampler wrote fewer than 2 samples after a failed first scrape"
wait_samples "$PRESS" 2 || bad "sampler wrote no pressure row"
check "the first scrape failed" "true" "$([ "$(cat "$WORK/curl-calls")" -ge 3 ] && echo true || echo false)"
check "sampler reads the retained gauges" "4096 7" "$(head -1 "$SAMP")"
check "the pressure csv starts with its header" "$EVENT_LOG_PRESSURE_COLS" "$(head -1 "$PRESS")"
check "a pressure row: drops by reason, their sum, ring and in-flight gauges" ",2,0,2,12,4096,1,1000000" \
  "$(sed -n '2s/^[0-9]*//p' "$PRESS")"
check "a pressure row is stamped with an epoch second" "true" "$(grep -qE '^[0-9]{10},' <<<"$(sed -n 2p "$PRESS")" && echo true || echo false)"
check "a failed scrape writes no pressure row" "true" "$(! grep -qE '^[0-9]*,,,,,,,$' "$PRESS" && echo true || echo false)"
PID="$INFO_ELS_SAMPLER_PID"; bounded_stop
check "stop clears the pid" "" "$INFO_ELS_SAMPLER_PID"
check "stop kills the sampler" "dead" "$(kill -0 "$PID" 2>/dev/null && echo alive || echo dead)"
/bin/sleep 0.3; n1="$(wc -l < "$SAMP")"; /bin/sleep 0.4
check "the samples file stops growing" "$n1" "$(wc -l < "$SAMP")"
stop_info_els_sampler; check "stop with no sampler is a no-op" "" "$INFO_ELS_SAMPLER_PID"
one_row() { # metrics_text -> the first pressure row an older or partial server yields, without its timestamp
  local m="$WORK/metrics.keep" r; r="$(mktemp "$WORK/one-row.XXXXXX")"
  cp "$WORK/metrics" "$m"; printf '%s\n' "$1" > "$WORK/metrics"
  start_info_els_sampler "127.0.0.1:9" "$WORK/one-row.samples" "$r"
  wait_samples "$r" 2 || true
  bounded_stop; cp "$m" "$WORK/metrics"
  sed -n '2s/^[0-9]*//p' "$r"
}
check "an unlabelled drop counter (older server): total only, reasons blank" ",,,7,12,4096,," \
  "$(one_row 'mock_server_dropped_log_events_total 7.0
mock_server_dropped_log_events_created 1.7E9
mock_server_event_log_ring_occupancy 12.0
mock_server_event_log_ring_capacity 4096.0')"
check "a complete scrape without any event-log series writes no pressure row" "" \
  "$(one_row 'jvm_memory_max_bytes{area="heap"} 9.66787072E8')"
check "a scrape cut off between the reason lines leaves its drops blank, not a lower total" ",,,,12,4096,," \
  "$(one_row 'mock_server_dropped_log_events_total{reason="in_flight_bytes"} 9.0
mock_server_event_log_ring_occupancy 12.0
mock_server_event_log_ring_capacity 4096.0')"
start_info_els_sampler "127.0.0.1:10" "$WORK/slow.samples" "$WORK/slow.csv"
wait_samples "$WORK/slow.csv" 2 || bad "sampler wrote no row for a slow scrape"
bounded_stop
check "a row is stamped after its scrape returned, not before it started" "true" \
  "$([ "$(sed -n '2s/,.*//p' "$WORK/slow.csv")" -ge "$(head -1 "$WORK/slow-scrape-done" 2>/dev/null || echo 9999999999)" ] 2>/dev/null && echo true || echo false)"
: > "$WORK/cut.samples"; start_info_els_sampler "127.0.0.1:11" "$WORK/cut.samples" "$WORK/cut.csv"
wait_samples "$WORK/cut.samples" 2 || bad "sampler kept no retained samples from a timed-out scrape"
bounded_stop
check "a scrape that timed out mid-number writes no pressure row" "1" "$(wc -l < "$WORK/cut.csv" | tr -d ' ')"
mkdir -p "$WORK/datebin"; printf '#!/bin/sh\necho 2020\n' > "$WORK/datebin/date"; chmod +x "$WORK/datebin/date"
: > "$WORK/stamp.samples"; PATH="$WORK/datebin:$PATH" start_info_els_sampler "127.0.0.1:9" "$WORK/stamp.samples" "$WORK/stamp.csv"
wait_samples "$WORK/stamp.csv" 2 || bad "sampler wrote no row under the stubbed clock"
bounded_stop
check "a row read during second 2020 is stamped 2021, never earlier than the read" "2021" "$(sed -n '2s/,.*//p' "$WORK/stamp.csv")"
check "a sampler without a pressure csv still samples the retained gauges" "4096 7" \
  "$(: > "$WORK/plain.samples"; start_info_els_sampler "127.0.0.1:9" "$WORK/plain.samples"; wait_samples "$WORK/plain.samples" 1 || true; bounded_stop
     head -1 "$WORK/plain.samples")"

ARM_BLOCK="$(awk '/^  if wait_ready "\$INFO_SERVER"; then$/ {p=1} p {print} p && /^  fi$/ {exit}' "$F")"
[ -n "$ARM_BLOCK" ] || bad "INFO arm load block not found in $F"
PRESSURE_PATH_LINE="$(grep -m1 '^  INFO_ELS_PRESSURE=' "$F" || true)"
[ -n "$PRESSURE_PATH_LINE" ] || bad "INFO_ELS_PRESSURE path line not found in $F"
wait_ready() { return 0; }
docker() { if [ "$1 $2 $3" = "port info 1080/tcp" ]; then echo "127.0.0.1:9"; fi; }
LOAD_LOG="$WORK/load"; : > "$LOAD_LOG"
load_step() { # records whether the sampler was alive while a load phase ran
  wait_samples "$INFO_ELS_SAMPLES" 1 || true
  if [ -n "$INFO_ELS_SAMPLER_PID" ] && kill -0 "$INFO_ELS_SAMPLER_PID" 2>/dev/null; then echo "$1 sampled" >> "$LOAD_LOG"; else echo "$1 unsampled" >> "$LOAD_LOG"; fi
}
run_regression() { load_step "regression-$1"; }
run_sweep() { # the load also moves the counters, so the arm must read them after it
  load_step sweep
  printf '{"points":[{"offered_rps":1000,"start_epoch_ms":%s000}]}\n' "$(($(date -u +%s) - 60))" > "$OUT_DIR/info-sweep.json"
  sed -e 's/^mock_server_dropped_log_events_total{reason="ring_full"} .*/mock_server_dropped_log_events_total{reason="ring_full"} 3.0/' \
      -e 's/^mock_server_dropped_log_events_total{reason="in_flight_bytes"} .*/mock_server_dropped_log_events_total{reason="in_flight_bytes"} 2.0/' \
      -e 's/^mock_server_evicted_log_entries_total .*/mock_server_evicted_log_entries_total 2500.0/' \
      "$WORK/metrics" > "$WORK/metrics.new" && mv "$WORK/metrics.new" "$WORK/metrics"
  wait_samples "$INFO_ELS_PRESSURE" $(($(wc -l < "$INFO_ELS_PRESSURE") + 2)) || true
}
arm_run() {
  INFO_SERVER=info INFO_SERVER_ALIAS=mockserver-info OUT_DIR="$WORK" INFO_SWEEP_K6=k6 INFO_MEASURED=false
  PERF_INFO_MAX_EVENT_LOG_BYTES=""; INFO_ELS_SAMPLES="$WORK/arm-samples"; : > "$INFO_ELS_SAMPLES"
  PERF_INFO_HEAP_PROBE=false # perf-info-heap-probe-test.sh covers the probe
  INFO_ELS_SAMPLER_PID=""; DIAG_DIR="$WORK/diag"; mkdir -p "$DIAG_DIR/info"
  eval "$PRESSURE_PATH_LINE"
  eval "$ARM_BLOCK" >/dev/null 2>&1
  printf '%s|%s|%s|%s|%s\n' "${INFO_ELS_SAMPLER_PID:-none}" "$INFO_ELS_MAX_BYTES" "$INFO_HEAP_MAX_BYTES" "$INFO_ELS_DROPPED" "$INFO_ELS_EVICTED"
}
bounded() { # seconds outfile command... -> runs it in the background; a hang is killed and recorded as "hung"
  local secs="$1" out="$2" pid i; shift 2
  "$@" > "$out" 2>/dev/null < /dev/null &
  pid=$!
  for i in $(seq 1 $((secs * 20))); do kill -0 "$pid" 2>/dev/null || break; /bin/sleep 0.05; done
  if kill -0 "$pid" 2>/dev/null; then
    pkill -P "$pid" 2>/dev/null || true; kill "$pid" 2>/dev/null || true; echo hung > "$out"; return 0
  fi
  wait "$pid" || true
}
bounded 20 "$WORK/arm-out" arm_run
LEFT="$(cut -d'|' -f1 "$WORK/arm-out")"; [ "$LEFT" = none ] || kill "$LEFT" 2>/dev/null || true
check "every load phase ran with the sampler alive" "regression-http sampled
regression-https_h2 sampled
sweep sampled" "$(cat "$LOAD_LOG")"
check "sampler stopped, budget read, counters read after the load" "none|80000000|966787072|5|2500" "$(cat "$WORK/arm-out")"
ARM_PRESSURE="$WORK/diag/info/event-log-pressure.csv"
check "the arm's sampler wrote the pressure csv under the INFO diagnostics" "true" \
  "$([ "$(wc -l < "$ARM_PRESSURE" 2>/dev/null || echo 0)" -ge 3 ] && echo true || echo false)"
CLEANUP_BODY="$(extract cleanup)"; [ -n "$CLEANUP_BODY" ] || bad "cleanup() not found in $F"
run_cleanup() { # with_sampler(yes|no) exit_code -> "<rc>|<teardown reached>|<sampler state>"
  local script="$WORK/cleanup-$1.sh" out rc=0
  { echo 'set -euo pipefail'
    printf '%s\n' "$CLEANUP_BODY"
    if [ "$1" = yes ]; then
      for fn in event_log_sampler start_info_els_sampler stop_info_els_sampler; do extract "$fn"; done
    fi
    cat <<'EOS'
capture_sut_diagnostics() { :; }; docker() { :; }; cov_cleanup() { echo TEARDOWN; }
SAMPLER_PID="" SWEEP_SAMPLER_PID="" DIAG_SAMPLER_PID="" LIVE_HISTO_PID="" SUT_LOG_PID="" INFO_LOG_PID=""
SERVER=s UPSTREAM=u SWEEP_K6=k STREAM_K6=k STREAM_SUT=s INFO_SERVER=i INFO_SWEEP_K6=k CLU_CTRL=c CLU_A=a CLU_B=b RUN_ID=r NETWORK=n
INFO_ELS_SAMPLER_PID=""
if declare -F start_info_els_sampler >/dev/null; then start_info_els_sampler 127.0.0.1:9 "$CLEANUP_SAMPLES"; echo "PID $INFO_ELS_SAMPLER_PID"; fi
trap cleanup EXIT
EOS
    echo "exit $2"
  } > "$script"
  # To a file, not $(...): a sampler cleanup failed to stop would hold the substitution open forever.
  CLEANUP_SAMPLES="$WORK/cleanup-samples" bash "$script" > "$WORK/cleanup.out" 2>&1 < /dev/null &
  local cpid=$! i
  for i in $(seq 1 300); do kill -0 "$cpid" 2>/dev/null || break; /bin/sleep 0.05; done
  if kill -0 "$cpid" 2>/dev/null; then
    pkill -P "$cpid" 2>/dev/null || true; kill "$cpid" 2>/dev/null || true; echo "hung|cleanup did not return within 15 s|"; return
  fi
  wait "$cpid" || rc=$?
  out="$(cat "$WORK/cleanup.out")"
  local pid; pid="$(awk '$1 == "PID" {print $2}' <<<"$out")"
  local state=none; [ -z "$pid" ] || { kill -0 "$pid" 2>/dev/null && { state=alive; kill "$pid" 2>/dev/null || true; } || state=stopped; }
  echo "$rc|$(grep -c '^TEARDOWN$' <<<"$out" || true)|$state"
}
check "cleanup without the sampler helper (an early failure) still tears down and keeps the exit code" "3|1|none" "$(run_cleanup no 3)"
check "cleanup stops a running INFO sampler" "0|1|stopped" "$(run_cleanup yes 0)"

TAIL_BLOCK="$(awk '/^  if docker inspect "\$INFO_SERVER" >\/dev\/null 2>&1; then$/ {p=1} p {print} p && /^  jq -e \. .*INFO_ELS_JSON=/ {exit}' "$F")"
[ -n "$TAIL_BLOCK" ] || bad "INFO budget assembly block not found in $F"
assemble() { # handed_env inspect_ok declared -> event_log_budget json
  ( docker() { [ "$1" = inspect ] && [ "$INSPECT_OK" = yes ]; }
    container_env() { [ "$1" = MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES ] && printf '%s' "$HANDED"; }
    HANDED="$1" INSPECT_OK="$2" PERF_INFO_MAX_EVENT_LOG_BYTES="$3" INFO_SERVER=info PERF_EVENT_LOG_APPROACH_RATIO=0.90
    INFO_HEAP_MAX_BYTES=966787072 INFO_ELS_MAX_BYTES=80000000 INFO_ELS_MAX_ENTRIES=100000 INFO_ELS_DROPPED=0 INFO_ELS_EVICTED=0
    INFO_ELS_SAMPLES="$WORK/peaks"
    [ "${BREAK_JSON:-}" != yes ] || event_log_budget_json() { echo "not json"; }
    eval "$TAIL_BLOCK"
    if [ "${BREAK_JSON:-}" = yes ]; then jq -c . <<<"$INFO_ELS_JSON"; else
      jq -c '{method, requested_bytes, requested_source, expected_default_bytes, peak_retained_bytes}' <<<"$INFO_ELS_JSON"; fi )
}
check "no budget handed: shipped-default" \
  '{"method":"shipped-default","requested_bytes":null,"requested_source":"container-env","expected_default_bytes":47290368,"peak_retained_bytes":300}' \
  "$(assemble "" yes "")"
check "a handed budget is its own method" '{"method":"fixed-104857600","requested_bytes":104857600,"requested_source":"container-env","expected_default_bytes":47290368,"peak_retained_bytes":300}' \
  "$(assemble 104857600 yes 104857600)"
check "uninspectable container falls back to the declared value" '{"method":"fixed-104857600","requested_bytes":104857600,"requested_source":"declared","expected_default_bytes":47290368,"peak_retained_bytes":300}' \
  "$(assemble "" no 104857600)"
check "a failed budget record still carries the method, so the run is not keyed as legacy" '{"method":"fixed-104857600"}' \
  "$(BREAK_JSON=yes assemble 104857600 yes 104857600)"

echo "--- 8. event_log_pressure: per-rung drops by reason, first dropping rung, ring / in-flight peaks"
STEP_S=10
cat > "$WORK/p-sweep.json" <<'EOS'
{"points":[{"offered_rps":1000,"start_epoch_ms":1000000},{"offered_rps":2000,"start_epoch_ms":1010000},
 {"offered_rps":3000,"start_epoch_ms":1020000},{"offered_rps":4000,"start_epoch_ms":1030000},{"offered_rps":5000}]}
EOS
# 990-996 are before the ladder; 1000 is rung 1's baseline; the counters restart at 1024.
{ echo "$EVENT_LOG_PRESSURE_COLS"
  cat <<'EOS'
990,0,0,0,10,4096,100,1000000
996,5,0,5,4000,4096,900000,1000000
1000,5,0,5,500,4096,5000,1000000
1004,5,0,5,60,4096,300,1000000
1008,5,0,5,70,4096,400,1000000
1012,25,0,25,3000,4096,500,1000000
1016,45,0,45,4096,4096,600,1000000
1020,65,0,65,2000,4096,700,1000000
1024,2,0,2,100,4096,800,1000000
1028,10,4,14,200,4096,950000,1000000
EOS
} > "$WORK/p.csv"
P="$(event_log_pressure_json "$WORK/p-sweep.json" "$WORK/p.csv")"
rung() { jq -c --argjson r "$1" '.rungs[] | select(.offered_rps == $r) | del(.offered_rps)' <<<"$P"; }
check "a rung without drops reads 0, and its peaks exclude the baseline sample" \
  '{"samples":2,"dropped_log_events":0,"dropped_by_reason":{"ring_full":0,"in_flight_bytes":0},"peak_ring_occupancy":70,"peak_in_flight_bytes":400}' "$(rung 1000)"
check "a rung's drops count from the last sample before it, to the sample on its end" \
  '{"samples":3,"dropped_log_events":60,"dropped_by_reason":{"ring_full":60,"in_flight_bytes":0},"peak_ring_occupancy":4096,"peak_in_flight_bytes":700}' "$(rung 2000)"
check "a counter that falls is read as restarted, not as a negative" \
  '{"samples":2,"dropped_log_events":14,"dropped_by_reason":{"ring_full":10,"in_flight_bytes":4},"peak_ring_occupancy":200,"peak_in_flight_bytes":950000}' "$(rung 3000)"
check "a rung with no sample is unknown, not zero" \
  '{"samples":0,"dropped_log_events":null,"dropped_by_reason":{"ring_full":null,"in_flight_bytes":null},"peak_ring_occupancy":null,"peak_in_flight_bytes":null}' "$(rung 4000)"
check "a rung without a start time is left out" "4" "$(jq -r '.rungs | length' <<<"$P")"
check "the first rung that dropped, overall and per reason" '2000 {"ring_full":2000,"in_flight_bytes":3000}' \
  "$(jq -c -r '"\(.first_drop_rung_rps) \(.first_drop_rung_rps_by_reason)"' <<<"$P")"
check "whole-load drops include those before the ladder" '79 {"ring_full":75,"in_flight_bytes":4}' \
  "$(jq -c -r '"\(.dropped_log_events) \(.dropped_by_reason)"' <<<"$P")"
check "ring peak over every sample, against its capacity" "4096 4096 1" \
  "$(jq -r '"\(.peak_ring_occupancy) \(.ring_capacity) \(.peak_ring_utilisation)"' <<<"$P")"
check "in-flight peak over every sample, against its cap" "950000 1000000 0.95" \
  "$(jq -r '"\(.peak_in_flight_bytes) \(.max_in_flight_bytes) \(.peak_in_flight_utilisation)"' <<<"$P")"
check "sample count and reason split" "10 true 2" "$(jq -r '"\(.samples) \(.reason_split) \(.sample_interval_s)"' <<<"$P")"
# An older server exports one unlabelled counter: totals still read, the reason split is null.
awk -F, 'BEGIN {OFS = ","} NR > 1 {$2 = ""; $3 = ""} {print}' "$WORK/p.csv" > "$WORK/p-old.csv"
P="$(event_log_pressure_json "$WORK/p-sweep.json" "$WORK/p-old.csv")"
check "older server: per-rung totals without a reason split" '{"samples":3,"dropped_log_events":60,"dropped_by_reason":null,"peak_ring_occupancy":4096,"peak_in_flight_bytes":700}' "$(rung 2000)"
check "older server: first dropping rung, no per-reason rung" "false 2000 null null 79" \
  "$(jq -r '"\(.reason_split) \(.first_drop_rung_rps) \(.first_drop_rung_rps_by_reason) \(.dropped_by_reason) \(.dropped_log_events)"' <<<"$P")"
{ echo "$EVENT_LOG_PRESSURE_COLS"; printf '1004,0,0,0,3,4096,10,1000000\n1008,0,0,0,4,4096,20,1000000\n'; } > "$WORK/p-quiet.csv"
P="$(event_log_pressure_json "$WORK/p-sweep.json" "$WORK/p-quiet.csv")"
check "no drops: no first dropping rung" "null 0 0" "$(jq -r '"\(.first_drop_rung_rps) \(.dropped_log_events) \(.rungs[0].dropped_log_events)"' <<<"$P")"
{ echo "$EVENT_LOG_PRESSURE_COLS"; printf '1004,4,3,7,3,4096,10,1000000\n1008,4,3,7,4,4096,20,1000000\n'; } > "$WORK/p-early.csv"
P="$(event_log_pressure_json "$WORK/p-sweep.json" "$WORK/p-early.csv")"
check "drops before the first sample count in the whole-load total, not in a rung" '7 {"ring_full":4,"in_flight_bytes":3} 0 null' \
  "$(jq -c -r '"\(.dropped_log_events) \(.dropped_by_reason) \(.rungs[0].dropped_log_events) \(.first_drop_rung_rps)"' <<<"$P")"
# The shipped ladder: 15 s rungs 20 s apart, starts off the second. Rung 1 drops only in its last
# second (first seen at 2017, in the gap); rung 2 drops through its hold and its backlog drains in
# the gap (2037, 2039); rung 3's drops show at 2057 and 2059, after its end (the sampler stops
# when the sweep returns, so the last rung runs to the last sample).
STEP_S=15
echo '{"points":[{"offered_rps":1000,"start_epoch_ms":2000783},{"offered_rps":2000,"start_epoch_ms":2020783},{"offered_rps":3000,"start_epoch_ms":2040783}]}' > "$WORK/g-sweep.json"
{ echo "$EVENT_LOG_PRESSURE_COLS"
  awk 'BEGIN { for (t = 1999; t <= 2059; t += 2) {
      k = (t - 2021) / 2; if (k < 0) k = 0; if (k > 7) k = 7
      d = (t >= 2017 ? 200 : 0) + 100 * k + (t >= 2037 ? 500 : 0) + (t >= 2039 ? 300 : 0) + (t >= 2057 ? 50 : 0) + (t >= 2059 ? 7 : 0)
      printf "%d,%d,0,%d,5,4096,10,1000000\n", t, d, d } }'
} > "$WORK/g.csv"
P="$(event_log_pressure_json "$WORK/g-sweep.json" "$WORK/g.csv")"
check "gapped ladder: a rung's drops include its tail and the backlog drained in the gap after it" "[200,1500,57]" \
  "$(jq -c '[.rungs[].dropped_log_events]' <<<"$P")"
check "gapped ladder: the rungs sum to the drops sampled over the ladder" "1757 1757" \
  "$(jq -r '"\([.rungs[].dropped_by_reason.ring_full] | add) \(.dropped_log_events)"' <<<"$P")"
check "gapped ladder: drops only in a rung's last second still name that rung first" "1000" "$(jq -r '.first_drop_rung_rps' <<<"$P")"
check "gapped ladder: samples and peaks stay inside the rung's own window" "[8,8,8]" "$(jq -c '[.rungs[].samples]' <<<"$P")"
STEP_S=10
echo '{"points":[{"offered_rps":1000,"start_epoch_ms":100000},{"offered_rps":2000,"start_epoch_ms":110000},{"offered_rps":3000,"start_epoch_ms":120000}]}' > "$WORK/s-sweep.json"
{ echo "$EVENT_LOG_PRESSURE_COLS"; printf '100,0,0,0,1,4096,1,1000000\n122,40,0,40,1,4096,1,1000000\n132,40,0,40,1,4096,1,1000000\n'; } > "$WORK/s.csv"
P="$(event_log_pressure_json "$WORK/s-sweep.json" "$WORK/s.csv")"
check "a rung without samples is unknown and its drops show in the next sampled rung" "[0,0,1] [null,null,40] 3000" \
  "$(jq -r '"\([.rungs[].samples] | tojson) \([.rungs[].dropped_log_events] | tojson) \(.first_drop_rung_rps)"' <<<"$P")"
echo '{"points":[{"offered_rps":1000,"start_epoch_ms":100000},{"offered_rps":2000,"start_epoch_ms":115000},{"offered_rps":3000,"start_epoch_ms":130000}]}' > "$WORK/e-sweep.json"
{ echo "$EVENT_LOG_PRESSURE_COLS"; printf '%s\n' 100,0,0,0,1,4096,1,1000000 105,2000,0,2000,1,4096,1,1000000 112,2000,0,2000,1,4096,1,1000000 \
    128,2000,0,2000,1,4096,1,1000000 135,2040,0,2040,1,4096,1,1000000; } > "$WORK/e.csv"
P="$(event_log_pressure_json "$WORK/e-sweep.json" "$WORK/e.csv")"
check "a rung without samples of its own still gets the drops bounded by gap samples" "[1,0,1] [2000,0,40]" \
  "$(jq -r '"\([.rungs[].samples] | tojson) \([.rungs[].dropped_log_events] | tojson)"' <<<"$P")"
P="$(event_log_pressure_json "$WORK/absent-sweep.json" "$WORK/p.csv")"
check "no sweep result: the peaks and whole-load drops still report, with no rungs" "0 4096 79 null" \
  "$(jq -r '"\(.rungs | length) \(.peak_ring_occupancy) \(.dropped_log_events) \(.first_drop_rung_rps)"' <<<"$P")"
echo "$EVENT_LOG_PRESSURE_COLS" > "$WORK/p-header.csv"; : > "$WORK/p-empty.csv"
check "a header-only csv is null" "null" "$(event_log_pressure_json "$WORK/p-sweep.json" "$WORK/p-header.csv")"
check "an empty csv is null" "null" "$(event_log_pressure_json "$WORK/p-sweep.json" "$WORK/p-empty.csv")"
check "a missing csv is null" "null" "$(event_log_pressure_json "$WORK/p-sweep.json" "$WORK/p-absent.csv")"

RECORD_BLOCK="$(awk '/^  INFO_ELS_PRESSURE_JSON=/ {p=1} p {print} p && /^    }.\)"$/ {exit}' "$F")"
[ -n "$RECORD_BLOCK" ] || bad "INFO arm record block not found in $F"
record() { # pressure_csv -> the INFO arm record assembled from the arm's own files
  ( derive_saturation() { echo '{"saturation_rps":2000}'; }
    OUT_DIR="$WORK" INFO_ELS_PRESSURE="$1" STEP_S=3600 LAST_SWEEP_T0=0 INFO_MEASURED=true INFO_ELS_JSON='{"method":"shipped-default"}' INFO_HEAP_PROBE_JSON=null
    INFO_LOG_LEVEL_VAL=INFO INFO_LOG_LEVEL_SRC=observed MOCKSERVER_IMAGE=img IMAGE_DIGEST=sha SERVER_CPUS="" K6_CPUS=""
    [ "${BREAK_PRESSURE:-}" != yes ] || event_log_pressure_json() { echo "not json"; }
    eval "$RECORD_BLOCK"; printf '%s' "$INFO_ARM_JSON" )
}
echo '{"behaviours":{"match_http":{"p95_ms":5}}}' > "$WORK/info-regression-http.json"; echo '{}' > "$WORK/info-regression-https.json"
R="$(record "$ARM_PRESSURE")"
check "the INFO record carries the pressure block beside the budget and the knee" "true shipped-default 2000" \
  "$(jq -r '"\(.event_log_pressure.reason_split) \(.config.event_log_budget.method) \(.saturation_rps)"' <<<"$R")"
check "the arm's own samples line up with its sweep rung: drops by reason during the load" \
  '{"rps":1000,"dropped":3,"by_reason":{"ring_full":1,"in_flight_bytes":2},"first":1000,"ring":"12/4096"}' \
  "$(jq -c '.event_log_pressure | {rps: .rungs[0].offered_rps, dropped: .rungs[0].dropped_log_events, by_reason: .rungs[0].dropped_by_reason,
      first: .first_drop_rung_rps, ring: "\(.peak_ring_occupancy)/\(.ring_capacity)"}' <<<"$R")"
check "no pressure samples: the block is null and the record still assembles" "null true" \
  "$(record "$WORK/p-absent.csv" | jq -r '"\(.event_log_pressure) \(.measured)"')"
check "an unparsable pressure block degrades to null, not to a lost record" "null true" \
  "$(BREAK_PRESSURE=yes record "$ARM_PRESSURE" | jq -r '"\(.event_log_pressure) \(.measured)"')"

rm -f "$WORK/info-regression-https.json"
check "a missing regression result keeps the other protocol's behaviours and the record" "5 true" \
  "$(record "$ARM_PRESSURE" | jq -r '"\(.behaviours.match_http.p95_ms) \(.measured)"')"
rm -f "$WORK/info-regression-http.json"
check "no regression result at all: empty behaviours, the record still assembles" "{} true" \
  "$(record "$ARM_PRESSURE" | jq -r '"\(.behaviours | tojson) \(.measured)"')"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all INFO event-log budget checks passed"

#!/usr/bin/env bash
# Fixture tests for the weekly soak step (perf-test-soak.sh, lib/perf-soak.sh, lib/perf-soak-drift.jq),
# no Docker needed: sections 1-7 run the helpers on fixtures, section 8 runs the whole step with
# `docker`, `curl`, `cp` and `buildkite-agent` stubbed on PATH. soak.js's own init checks need a k6
# binary and are in k6-soak-init-test.sh.
#   fixtures/soak-637    build 637's own perf-soak.json and samples, and its image's labels and env.
#   fixtures/soak-local  a local MockServer 8.0.1-SNAPSHOT (maxLogEntries=600) under a 40 s run of
#                        soak.js on k6 1.7.1 (scrapes, configuration, k6 result, samples), and a JVM's
#                        output with and without an OutOfMemoryError exit.
# Run: .buildkite/scripts/test/perf-soak-test.sh   (PERF_SOAK_TEST_STEP=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_SOAK_TEST_STEP
STEP="${PERF_SOAK_TEST_STEP:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-soak.sh}"
LIB_DIR="$(dirname "$STEP")/lib"
F637="$REPO_ROOT/.buildkite/scripts/test/fixtures/soak-637"
FLOCAL="$REPO_ROOT/.buildkite/scripts/test/fixtures/soak-local"
# shellcheck source=../steps/lib/perf-soak.sh
. "$LIB_DIR/perf-soak.sh"
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-soak-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
has() { # name needle file
  if grep -qF -- "$2" "$3"; then ok "$1"; else bad "$1: '$2' not found in $3"; fi
}
lacks() { # name needle file
  if grep -qF -- "$2" "$3"; then bad "$1: '$2' found in $3"; else ok "$1"; fi
}
drift() { jq -c --argjson ref_n 3 --argjson late_n 3 -f "$LIB_DIR/perf-soak-drift.jq" "$1"; }
COMMIT_637="$(jq -r .commit "$F637/perf-soak.json")"
REVISION_637="$(jq -r '.config.Labels["org.opencontainers.image.revision"]' "$F637/image-config.json")"

echo "--- 1. a samples.csv row from a real metrics scrape"
check "header" "ts,requests_received,heap_bytes,dropped_log_events,threads,retained_entries,retained_bytes,evicted_log_entries" "$(soak_sample_header)"
check "idle server: nothing retained, nothing evicted" "7,3,77594624,0,15,0,0,0" "$(soak_sample_row 7 < "$FLOCAL/metrics-start.txt")"
check "under load: retained at the entry limit, evictions counted" "9,476,67108864,0,23,600,802200,162" "$(soak_sample_row 9 < "$FLOCAL/metrics-load.txt")"
check "a failed scrape leaves blanks, never zeros (drops excepted)" "5,,,0,,,," "$(soak_sample_row 5 < /dev/null)"
grep -v '^mock_server_dropped_log_events_total' "$FLOCAL/metrics-load.txt" > "$T/labelled.txt"
printf '%s\n' 'mock_server_dropped_log_events_total{reason="ring_full"} 3.0' 'mock_server_dropped_log_events_total{reason="in_flight_bytes"} 4.0' >> "$T/labelled.txt"
check "drops are summed over the reason label" "7" "$(soak_sample_row 1 < "$T/labelled.txt" | cut -d, -f4)"
check "resolved max heap" "268435456" "$(soak_heap_max_bytes < "$FLOCAL/metrics-start.txt")"
check "entry limit in force (exact name, not max_in_flight)" "600" "$(soak_metric_value mock_server_event_log_max_retained_entries < "$FLOCAL/metrics-start.txt")"
check "byte budget in force" "12372992" "$(soak_metric_value mock_server_event_log_max_retained_bytes < "$FLOCAL/metrics-start.txt")"
check "an absent series is empty" "" "$(soak_metric_value mock_server_no_such_series < "$FLOCAL/metrics-start.txt")"

echo "--- 2. end-of-load state from build 637's samples (the old five-column file)"
S637="$(soak_samples_json "$F637/perf-soak-samples.csv.txt" "")"
check "rows" "240" "$(jq -r .rows <<<"$S637")"
check "requests received" "$(jq -r .ring.requests_received_total "$F637/perf-soak.json")" "$(jq -r .requests_received_total <<<"$S637")"
check "heap at the start and end" "79691776 153092096" "$(jq -r '"\(.heap_start_bytes) \(.heap_end_bytes)"' <<<"$S637")"
check "threads at the end" "49" "$(jq -r .threads_end <<<"$S637")"
check "pre_teardown_heap_min_bytes is what 637 published as live_set_floor_bytes" \
  "$(jq -r .ring.live_set_floor_bytes "$F637/perf-soak.json")" "$(jq -r .pre_teardown_heap_min_bytes <<<"$S637")"
check "over 5 samples" "5" "$(jq -r .pre_teardown_heap_min_samples <<<"$S637")"
RUN_MIN="$(awk -F, 'NR > 1 && $3 + 0 > 0 && (m == "" || $3 + 0 < m) {m = $3 + 0} END {printf "%.0f", m}' "$F637/perf-soak-samples.csv.txt")"
check "and it is not a floor: the run sampled a lower used heap" "yes" "$([ "$RUN_MIN" -lt "$(jq -r .pre_teardown_heap_min_bytes <<<"$S637")" ] && echo yes || echo no)"
check "no retained or evicted series in the old file: null, not 0" "null null null" \
  "$(jq -r '"\(.retained_entries_end) \(.retained_bytes_end) \(.evicted_log_entries)"' <<<"$S637")"
{ cat "$F637/perf-soak-samples.csv.txt"; echo "1791108124,0.0,9.0E7,0,49.0"; } > "$T/reset.csv"
check "a row sampled after the teardown reset is not the end of the load" "1521544 153092096" \
  "$(soak_samples_json "$T/reset.csv" "" | jq -r '"\(.requests_received_total) \(.heap_end_bytes)"')"
printf '%s\n' 'ts,requests_received,heap_bytes,dropped_log_events,threads' '1,100,50,0,9' '2,200,70,0,9' '3,300,90,0,9' > "$T/three.csv"
check "three samples: the first one counts towards the heap minimum" "50 3 50 90" \
  "$(soak_samples_json "$T/three.csv" "" | jq -r '"\(.pre_teardown_heap_min_bytes) \(.pre_teardown_heap_min_samples) \(.heap_start_bytes) \(.heap_end_bytes)"')"
printf 'ts,requests_received\n' > "$T/empty.csv"
check "no samples" "0 null null" "$(soak_samples_json "$T/empty.csv" "" | jq -r '"\(.rows) \(.requests_received_total) \(.evicted_log_entries)"')"

echo "--- 3. end-of-load state from the local run's samples (retained and evicted series present)"
SLOCAL="$(soak_samples_json "$FLOCAL/samples.csv.txt" 1791133320)"
check "retained entries and bytes at the last row under load" "600 802200" "$(jq -r '"\(.retained_entries_end) \(.retained_bytes_end)"' <<<"$SLOCAL")"
check "evicted entries" "1000" "$(jq -r .evicted_log_entries <<<"$SLOCAL")"
check "first eviction, seconds after k6 started" "16" "$(jq -r .first_eviction_elapsed_s <<<"$SLOCAL")"
check "without a start time the first eviction is not placed" "null" "$(soak_samples_json "$FLOCAL/samples.csv.txt" "" | jq -r .first_eviction_elapsed_s)"

echo "--- 4. which event-log bound was binding"
elog() { soak_event_log_json "$1" "$2" "$3" | jq -r '"\(.filled) \(.binding) \(.count_utilisation) \(.bytes_utilisation)"'; }
check "local run: the entry count bound" "true count 1 0.0648" "$(elog 600 12372992 "$SLOCAL")"
check "bytes nearer its limit: the byte budget bound" "true bytes 0.3064 1" \
  "$(elog 128512 52637696 '{"retained_entries_end":39370,"retained_bytes_end":52637690,"evicted_log_entries":900000,"dropped_log_events":0}')"
check "byte budget disabled: only the count can bind" "true count 1 null" \
  "$(elog 600 0 '{"retained_entries_end":600,"retained_bytes_end":802200,"evicted_log_entries":5}')"
check "1.5M requests received but nothing evicted: not filled" "false neither 0.3064 0.5" \
  "$(elog 128512 52637696 '{"requests_received_total":1521544,"retained_entries_end":39370,"retained_bytes_end":26318848,"evicted_log_entries":0}')"
check "no eviction counter (build 637's samples): unknown, not filled" "null null null null" "$(elog 128512 52637696 "$S637")"
check "evictions but no retained gauges: binding unknown" "true null null null" "$(elog 600 12372992 '{"evicted_log_entries":5}')"
check "a string limit is compared as a number" "true bytes" \
  "$(soak_event_log_json 1000 900 '{"retained_entries_end":100,"retained_bytes_end":899,"evicted_log_entries":1}' | jq -r '"\(.filled) \(.binding)"')"

echo "--- 5. image revision against the harness commit"
OTHER=35b8e7f74c1f0d3b9a1e2f4a5b6c7d8e9f0a1b2c
check "build 637: the image was built from the harness commit" "match" "$(soak_revision_verdict "$COMMIT_637" "$REVISION_637")"
check "another commit: differs, not a failure verdict" "differs" "$(soak_revision_verdict "$COMMIT_637" "$OTHER")"
check "no label" "absent" "$(soak_revision_verdict "$COMMIT_637" "")"
check "a label that is not a SHA" "malformed" "$(soak_revision_verdict "$COMMIT_637" "master")"
check "an abbreviated SHA is malformed, not a match" "malformed" "$(soak_revision_verdict "$COMMIT_637" "${COMMIT_637:0:10}")"
check "harness commit unknown" "harness-unknown" "$(soak_revision_verdict "HEAD" "$REVISION_637")"
check "a bad label is reported even when the harness commit is unknown" "absent malformed" "$(soak_revision_verdict "HEAD" "") $(soak_revision_verdict "HEAD" "master")"
# A three-commit repository with a side branch, and a depth-1 clone of it: no network, no fetch.
G="$T/repo"; mkdir -p "$G"
gitc() { git -C "$G" -c user.name=soak-test -c user.email=soak-test@example.invalid -c commit.gpgsign=false "$@"; }
gitc init -q; gitc commit -q --allow-empty -m one; C1="$(gitc rev-parse HEAD)"
gitc commit -q --allow-empty -m two; C2="$(gitc rev-parse HEAD)"
gitc checkout -q -b side "$C1"; gitc commit -q --allow-empty -m side; SIDE="$(gitc rev-parse HEAD)"
git clone -q --depth 1 "file://$G" "$T/shallow"; TIP="$(git -C "$T/shallow" rev-parse HEAD)"
check "an earlier commit on the same line is an ancestor" "true" "$(soak_revision_ancestry "$G" "$C2" "$C1")"
check "the same commit" "true" "$(soak_revision_ancestry "$G" "$C2" "$C2")"
check "a later commit is not" "false" "$(soak_revision_ancestry "$G" "$C1" "$C2")"
check "a commit on another branch is not" "false" "$(soak_revision_ancestry "$G" "$C2" "$SIDE")"
check "full history, a revision the repository has never seen: not an ancestor" "false" "$(soak_revision_ancestry "$G" "$C2" "$OTHER")"
check "shallow checkout, the revision cut off by the depth: unknown, not false" "unknown" "$(soak_revision_ancestry "$T/shallow" "$TIP" "$C1")"
check "shallow checkout, the tip itself: still true" "true" "$(soak_revision_ancestry "$T/shallow" "$TIP" "$TIP")"
check "a harness commit the checkout does not have: unknown" "unknown" "$(soak_revision_ancestry "$G" "$OTHER" "$C1")"
check "not a git checkout: unknown" "unknown" "$(soak_revision_ancestry "$T/bin-absent" "$C2" "$C1")"
check "no label: unknown" "unknown" "$(soak_revision_ancestry "$G" "$C2" "")"

echo "--- 6. OutOfMemoryError in the server's output (a real JVM's, started with the soak's OOM flag)"
check "the JVM's own line naming the ExitOnOutOfMemoryError flag is not an OutOfMemoryError" "1 0" \
  "$(grep -c 'ExitOnOutOfMemoryError' "$FLOCAL/server-clean.txt") $(soak_oom_lines "$FLOCAL/server-clean.txt")"
check "a JVM that exited on OutOfMemoryError" "1" "$(soak_oom_lines "$FLOCAL/server-oom.txt")"
printf '%s\n' 'Exception in thread "main" java.lang.OutOfMemoryError: Java heap space' '	at Oom.main(Oom.java:6)' > "$T/stack.txt"
check "an OutOfMemoryError stack trace" "1" "$(soak_oom_lines "$T/stack.txt")"
check "no log file" "0" "$(soak_oom_lines "$T/absent.log")"

echo "--- 7. drift from the per-window series"
check "637 as published: late p99 / early p99, the early window still warming up" "0.0259" \
  "$(jq -r '.soak.match | (.p99_late_ms / .p99_early_ms * 10000 | round) / 10000' "$F637/perf-soak.json")"
# A 2 h run in 24 five-minute windows, built from 637's two measured match p99s: its early window
# (120-420 s) lay in windows 0 and 1, which carry that figure; every other window carries the late one.
series() { # late_factor inflated_until_index -> k6 result
  jq --argjson late "$1" --argjson until "$2" '
    .soak.match.p99_early_ms as $early | .soak.match.p99_late_ms as $settled
    | def windows: [range(0; 24) | . as $i
        | (if $i <= $until then $early elif $i >= 21 then $settled * $late else $settled end) as $p
        | {index: $i, start_s: ($i * 300), end_s: (($i + 1) * 300), samples: 60000, p50_ms: ($p / 2), p95_ms: $p, p99_ms: $p}];
    {soak: (.soak | {warmup_s: 900, match: {windows: windows}, verify: {windows: windows}, retrieve: {windows: windows}})}' "$F637/perf-soak.json"
}
series 1 1 > "$T/flat.json"; series 10 1 > "$T/tenfold.json"; series 10 5 > "$T/inflated-reference.json"
D="$(drift "$T/flat.json")"
check "no late change: both ratios 1" "true 1 1 1" "$(jq -r '.match | "\(.computed) \(.p99.ratio) \(.p99.ratio_worst) \(.p99.reference_over_quietest)"' <<<"$D")"
check "warm-up, reference and late windows" "[0,1,2] [3,4,5] [21,22,23]" "$(jq -r '.match | "\(.warmup_windows | tostring) \(.reference_windows | tostring) \(.late_windows | tostring)"' <<<"$D")"
check "the old formula on a tenfold late rise still reads under 1" "0.2586" \
  "$(jq -r '.soak.match | (.p99_late_ms * 10 / .p99_early_ms * 10000 | round) / 10000' "$F637/perf-soak.json")"
D="$(drift "$T/tenfold.json")"
check "a tenfold late rise reads 10" "10 10" "$(jq -r '.match.p99 | "\(.ratio) \(.ratio_worst)"' <<<"$D")"
check "on p50 too, and on the low-rate arms" "10 10 10" "$(jq -r '"\(.match.p50.ratio) \(.verify.p99.ratio) \(.retrieve.p99.ratio_worst)"' <<<"$D")"
D="$(drift "$T/inflated-reference.json")"
check "a reference still warming up depresses late/reference" "0.2586" "$(jq -r '.match.p99.ratio' <<<"$D")"
check "but not worst late / quietest, and reference / quietest shows it" "10 38.6711" "$(jq -r '.match.p99 | "\(.ratio_worst) \(.reference_over_quietest)"' <<<"$D")"
jq '.soak.match.windows[22].p99_ms = 59.6' "$T/flat.json" > "$T/one-late.json"
check "one slow late window: the median hides it, the worst ratio does not" "1 100" "$(drift "$T/one-late.json" | jq -r '.match.p99 | "\(.ratio) \(.ratio_worst)"')"
D="$(drift "$FLOCAL/k6-1.7.1-soak-result.json")"
check "the real k6 1.7.1 result: computed for all three arms" "true true true" "$(jq -r '"\(.match.computed) \(.verify.computed) \(.retrieve.computed)"' <<<"$D")"
check "its windows" "10 [0,1] [2,3,4] [7,8,9]" "$(jq -r '.match | "\(.window_count) \(.warmup_windows | tostring) \(.reference_windows | tostring) \(.late_windows | tostring)"' <<<"$D")"
jq '.soak.warmup_s = 6000' "$T/flat.json" > "$T/short.json"
check "too few windows after the warm-up: not computed, with the reason" "false 4 window(s) start at or after the 6000 s warm-up" \
  "$(drift "$T/short.json" | jq -r '.match | "\(.computed) \(.reason[0:48])"')"
jq '.soak.match.windows[10] |= (.samples = 0 | .p50_ms = null | .p95_ms = null | .p99_ms = null)' "$T/flat.json" > "$T/gap.json"
check "a window with no samples: not computed, never a zero" "false window(s) 10 after the warm-up carried no samples" "$(drift "$T/gap.json" | jq -r '.match | "\(.computed) \(.reason)"')"
check "the old result shape (no series): not computed" "false" "$(drift "$F637/perf-soak.json" | jq -r '.match.computed')"
jq 'del(.soak.warmup_s)' "$T/flat.json" > "$T/no-warmup.json"
check "no warmup_s: not computed (never treated as 0)" "false the k6 result does not state warmup_s" "$(drift "$T/no-warmup.json" | jq -r '.match | "\(.computed) \(.reason)"')"

echo "--- 8. the whole step, with docker, curl, cp and buildkite-agent stubbed on PATH"
BIN="$T/bin"; mkdir -p "$BIN"
cat > "$BIN/docker" <<'STUB'
#!/usr/bin/env bash
# Answers what perf-test-soak.sh asks of docker, from the fixtures; records every call.
set -euo pipefail
printf '%s\n' "$*" >> "$STUB_DIR/docker.calls"
mount_dir() { # container_path "$@" -> the host dir bind-mounted there
  local want="$1" prev=""; shift
  for a in "$@"; do
    if [ "$prev" = "-v" ]; then case "$a" in *":$want"|*":$want:"*) echo "${a%%:*}"; return 0 ;; esac; fi
    prev="$a"
  done
}
case "$1" in
  pull) exit "${STUB_PULL_RC:-0}" ;;
  network|stop|rm) exit 0 ;;
  port) [ -z "${STUB_PORT_RC:-}" ] || exit "$STUB_PORT_RC"; echo "127.0.0.1:32768" ;;
  logs) [ -z "${STUB_LOGS_RC:-}" ] || { echo "Error response from daemon" >&2; exit "$STUB_LOGS_RC"; }; cat "$STUB_SERVER_LOG" ;;
  image)
    [ -n "${STUB_IMAGE_ID:-}" ] || exit 1
    case "$4" in
      '{{.Id}}') echo "$STUB_IMAGE_ID" ;;
      *RepoDigests*) echo "mockserver/mockserver@sha256:ac25ba6b6bf4dea5fe568357aa66469665ff2387c0367847d9d52dec68acc7e7" ;;
      *image.revision*) echo "${STUB_IMAGE_REVISION-$(jq -r '.config.Labels["org.opencontainers.image.revision"]' "$STUB_IMAGE_CONFIG")}" ;;
      *Config.Env*) jq -r '.config.Env[]' "$STUB_IMAGE_CONFIG" ;;
      *) exit 1 ;;
    esac ;;
  inspect)
    [ -f "$STUB_DIR/sut.args" ] && [ -z "${STUB_INSPECT_RC:-}" ] || { echo "Error: No such object: ${!#}" >&2; exit 1; }
    case "$3" in
      *State.Status*) echo "${STUB_SERVER_STATE:-running false 0}" ;;
      *HostConfig.Memory*) echo "${STUB_MEMORY-2147483648}" ;;
      *Config.Env*) awk 'p {print; p = 0} $0 == "-e" {p = 1}' "$STUB_DIR/sut.args" ;;
      *) exit 1 ;;
    esac ;;
  run)
    if [ "$2" = "-d" ]; then
      printf '%s\n' "$@" > "$STUB_DIR/sut.args"
      [ -n "${STUB_NO_GC_LOG:-}" ] || echo "[0.007s][info][gc,init] Initializing The Z Garbage Collector" > "$(mount_dir /diag "$@")/gc-1.log"
    else
      printf '%s\n' "$@" > "$STUB_DIR/k6.args"
      out="$(mount_dir /out "$@")"
      [ -z "${STUB_K6_RESULT:-}" ] || cp "$STUB_K6_RESULT" "$out/soak-result.json"
      # "Runs" until the step's sampler has written two rows (10 s at most), so no run depends on timing.
      for _ in $(seq 1 100); do
        [ "$(wc -l < "$out/samples.csv" 2>/dev/null || echo 0)" -ge 3 ] && break
        sleep 0.1
      done
      exit "${STUB_K6_EXIT:-0}"
    fi ;;
  *) echo "docker stub: unexpected call: $*" >&2; exit 64 ;;
esac
STUB
cat > "$BIN/curl" <<'STUB'
#!/usr/bin/env bash
# The server's three endpoints. Each metrics scrape reports 1000 more requests received.
set -euo pipefail
case "${!#}" in
  */mockserver/status) echo "$*" > "$STUB_DIR/status.args"; printf '%s' "${STUB_STATUS_CODE:-200}" ;;
  */mockserver/configuration) [ -n "${STUB_CONFIGURATION:-}" ] || exit 22; cat "$STUB_CONFIGURATION" ;;
  */mockserver/metrics)
    n=$(( $(cat "$STUB_DIR/scrapes" 2>/dev/null || echo 0) + 1 )); echo "$n" > "$STUB_DIR/scrapes"
    sed "s/^requests_received_count .*/requests_received_count $((n * 1000)).0/" "$STUB_METRICS" ;;
  *) exit 7 ;;
esac
STUB
cat > "$BIN/buildkite-agent" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
case "$1" in
  artifact) [ "$3" != "${STUB_UPLOAD_FAIL:-}" ] || exit 1; echo "$3" >> "$STUB_DIR/uploads" ;;
  annotate) cat > "$STUB_DIR/annotation.md"; echo "$*" > "$STUB_DIR/annotate.args" ;;
esac
STUB
cat > "$BIN/cp" <<'STUB'
#!/usr/bin/env bash
# The real cp, except that a copy to a file named $STUB_CP_FAIL fails.
dest="${!#}"
if [ "${dest##*/}" = "${STUB_CP_FAIL:-}" ]; then echo "cp: $dest: Permission denied" >&2; exit 1; fi
exec /bin/cp "$@"
STUB
chmod +x "$BIN/docker" "$BIN/curl" "$BIN/buildkite-agent" "$BIN/cp"
grep -vE '^mock_server_(evicted_log_entries_total|event_log_retained_(entries|bytes)) ' "$FLOCAL/metrics-load.txt" > "$T/metrics-old-server.txt"
grep -v '^jvm_memory_max_bytes' "$FLOCAL/metrics-load.txt" > "$T/metrics-no-heap.txt"
sed 's/"logLevel" *: *"ERROR"/"logLevel" : "WARN"/' "$FLOCAL/configuration.json" > "$T/configuration-warn.json"

# run_step <name> [VAR=value...]: runs the step in its own directory; sets RC, RUN (its directory:
# out.log, artifacts/, stub/). The harness commit is build 637's, which its image was built from.
run_step() {
  local name="$1"; shift
  RUN="$T/$name"; mkdir -p "$RUN/artifacts" "$RUN/stub" "$RUN/tmp"
  RC=0
  env PATH="$BIN:$PATH" TMPDIR="$RUN/tmp" STUB_DIR="$RUN/stub" BUILDKITE_COMMIT="$COMMIT_637" BUILDKITE_BRANCH=master \
    PERF_SOAK_ARTIFACT_DIR="$RUN/artifacts" PERF_SAMPLE_INTERVAL=0.1 K6_SOAK_DURATION=40s K6_SOAK_WINDOW=4s K6_SOAK_WARMUP=8s \
    STUB_IMAGE_ID="sha256:0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0" STUB_IMAGE_CONFIG="$F637/image-config.json" \
    STUB_METRICS="$FLOCAL/metrics-load.txt" STUB_CONFIGURATION="$FLOCAL/configuration.json" STUB_SERVER_LOG="$FLOCAL/server-clean.txt" \
    STUB_K6_RESULT="$FLOCAL/k6-1.7.1-soak-result.json" "$@" bash "$STEP" > "$RUN/out.log" 2>&1 || RC=$?
}
fails_with() { # name message [VAR=value...]: the step must exit 1 with this failure_reason
  local name="$1" message="$2"; shift 2
  run_step "$name" "$@"
  check "$name: exits 1" "1" "$RC"
  has "$name: says why" "$message" "$RUN/out.log"
  check "$name: perf-soak.json records the failure" "true false" "$(jq -r '"\(.soak_attempted) \(.soak_ok)"' "$RUN/artifacts/perf-soak.json" 2>/dev/null || echo missing)"
  has "$name: failure annotation posted" "Soak FAILED" "$RUN/stub/annotation.md"
}
removed() { # name: the step removed the container it started, and its network
  local sut; sut="$(awk 'prev == "--name" {print} {prev = $0}' "$RUN/stub/sut.args")"
  check "$1: the server container is removed" "mockserver-soak- 1" "${sut:0:16} $(grep -cxF -- "rm -f $sut" "$RUN/stub/docker.calls" || true)"
  check "$1: and its network" "1" "$(grep -cxF -- "network rm $sut" "$RUN/stub/docker.calls" || true)"
}

run_step ok
check "a sound soak passes" "0" "$RC"
[ "$RC" = 0 ] || tail -5 "$RUN/out.log" >&2
J="$RUN/artifacts/perf-soak.json"
cfg() { jq -r ".config.$1 | tostring" "$J"; }
check "the image is recorded by the digest it resolved to" "mockserver/mockserver@sha256:ac25ba6b6bf4dea5fe568357aa66469665ff2387c0367847d9d52dec68acc7e7" "$(cfg image_digest)"
check "its revision, the harness commit and the verdict" "$REVISION_637 $COMMIT_637 match" "$(jq -r '.config | "\(.image_revision) \(.harness_commit) \(.revision_check)"' "$J")"
check "ancestry is recorded as one of true, false, unknown" "yes" "$(jq -r '.config.image_revision_ancestor_of_harness | if . == "true" or . == "false" or . == "unknown" then "yes" else "no: \(.)" end' "$J")"
lacks "annotation: nothing about the revision when it matches" "is not the harness commit" "$RUN/stub/annotation.md"
check "the server runs from the inspected image id, not the tag" "sha256:0f1e2d3c4b5a69788796a5b4c3d2e1f00f1e2d3c4b5a69788796a5b4c3d2e1f0" "$(awk '$0 == "-serverPort" {print prev} {prev = $0}' "$RUN/stub/sut.args")"
check "container memory limit, declared and as docker reports it" "2g 2147483648" "$(jq -r '.config | "\(.container_memory_limit) \(.container_memory_limit_bytes)"' "$J")"
check "resolved max heap, from the server's metrics" "268435456" "$(cfg heap_max_bytes)"
check "maxLogEntries and the byte budget, from the configuration endpoint" "600 12372992" "$(jq -r '.config | "\(.max_log_entries) \(.max_event_log_bytes)"' "$J")"
check "log level, from the configuration endpoint" "ERROR" "$(cfg log_level)"
check "the image's GC selector survives the added JVM options" "-XX:+UseZGC -XX:+ExitOnOutOfMemoryError -Xlog:gc*,gc+heap=info:file=/diag/gc-%p.log:time,uptime,level,tags:filecount=10,filesize=20m" "$(cfg java_tool_options)"
check "requested rate, duration, gates and drift bound" "200 40s 0.57 8.5 1.64" "$(jq -r '.config.requested | "\(.match_rate_rps) \(.duration) \(.match_p95_gate_ms) \(.match_p99_gate_ms) \(.match_p99_drift_bound)"' "$J")"
check "the rates and gates k6 ran with are kept from its result" "20 150 200" "$(jq -r '"\(.soak.rates_rps.match) \(.soak.gates.match_p95_ms) \(.soak.gates.match_p99_ms)"' "$J")"
check "event log: filled, bound by count, retained and evicted at the end" "true count 600 600 802200 12372992 162" \
  "$(jq -r '.event_log | "\(.filled) \(.binding) \(.retained_entries_end) \(.max_retained_entries) \(.retained_bytes_end) \(.max_retained_bytes) \(.evicted_log_entries)"' "$J")"
check "the heap figure is named for what it is" "true false" "$(jq -r '"\(.ring | has("pre_teardown_heap_min_bytes")) \(.ring | has("live_set_floor_bytes"))"' "$J")"
check "drift computed, with the per-window series kept" "true 10" "$(jq -r '"\(.drift.match.computed) \(.soak.match.windows | length)"' "$J")"
check "server state" "running false 0" "$(jq -r .server_state "$J")"
check "the server container is not started with --rm" "0" "$(grep -c -- '^--rm$' "$RUN/stub/sut.args" || true)"
check "k6 is told the warm-up" "K6_SOAK_WARMUP=8s" "$(grep '^K6_SOAK_WARMUP=' "$RUN/stub/k6.args")"
check "k6 is handed the soak's own gates" "K6_P95_MS=0.57 K6_P99_MS=8.5" "$(grep -E '^K6_P9[59]_MS=' "$RUN/stub/k6.args" | tr '\n' ' ' | sed 's/ $//')"
check "k6 is told how many settled windows drift needs" "K6_SOAK_DRIFT_WINDOWS=6" "$(grep '^K6_SOAK_DRIFT_WINDOWS=' "$RUN/stub/k6.args")"
has "the readiness defaults are stated" "readiness: 60 polls, 2s apart, 5s each at most" "$RUN/out.log"
has "a readiness poll cannot hang" "--max-time 5 " "$RUN/stub/status.args"
removed ok
check "artifacts uploaded" "perf-soak-gc-log.tgz perf-soak-samples.csv perf-soak-server.log perf-soak.json" "$(sort "$RUN/stub/uploads" | tr '\n' ' ' | sed 's/ $//')"
check "the GC log artifact holds the server's GC log" "./gc-1.log" "$(tar -tzf "$RUN/artifacts/perf-soak-gc-log.tgz" | grep gc-1)"
has "annotation: the container limit is called that" "container memory limit 2 GiB, max heap 0.25 GiB" "$RUN/stub/annotation.md"
lacks "annotation: the container limit is not called the heap" "2g heap" "$RUN/stub/annotation.md"
lacks "annotation: no live-set floor claim" "live-set floor" "$RUN/stub/annotation.md"
has "annotation: the heap figure is described as a sample" "samples before teardown" "$RUN/stub/annotation.md"
has "annotation: which bound, from the server's counters" "bound by its **count** limit: 600 of 600 entries" "$RUN/stub/annotation.md"
check "the fixture's drift (2.1502) is judged against the bound" "drift.match.p99.ratio_worst 1.64 2.1502 true" \
  "$(jq -r '.drift_check | "\(.metric) \(.bound) \(.value) \(.exceeded)"' "$J")"
has "annotation: a drift above its bound is named" "Match p99 drift above its bound:** worst late / quietest 2.1502 is at or above 1.64" "$RUN/stub/annotation.md"
has "annotation: warning style for a drift above its bound, and the step still passes" "--style warning" "$RUN/stub/annotate.args"
has "annotation: the gates k6 ran with, and where the defaults come from" "Match gates p95 < 150 ms, p99 < 200 ms (the defaults are derived from eight counting soaks" "$RUN/stub/annotation.md"

run_step drift-within PERF_SOAK_DRIFT_P99_BOUND=3
check "drift under its bound: passes, not exceeded" "0 false 3" "$RC $(jq -r '.drift_check | "\(.exceeded) \(.bound)"' "$RUN/artifacts/perf-soak.json")"
has "drift-within: the annotation says so" "Match p99 drift (worst late / quietest) 2.1502, under its notify-only bound 3." "$RUN/stub/annotation.md"
has "drift-within: info style when k6 passed and drift is within its bound" "--style info" "$RUN/stub/annotate.args"
check "drift-within: an overridden bound is recorded as requested" "3" "$(jq -r .config.requested.match_p99_drift_bound "$RUN/artifacts/perf-soak.json")"

run_step warn STUB_CONFIGURATION="$T/configuration-warn.json"
check "the log level is the server's, not the one the step passed in" "0 WARN" "$RC $(jq -r .config.log_level "$RUN/artifacts/perf-soak.json")"

run_step threshold STUB_K6_EXIT=99
check "a k6 threshold breach is the step's exit code" "99" "$RC"
check "and the result and evidence are still published" "false 4" "$(jq -r .soak_ok "$RUN/artifacts/perf-soak.json") $(wc -l < "$RUN/stub/uploads" | tr -d ' ')"
has "with an error annotation" "--style error" "$RUN/stub/annotate.args"

run_step differs STUB_IMAGE_REVISION="$OTHER"
check "an image that trails the harness is measured, and recorded as differs" "0 differs $OTHER $COMMIT_637" \
  "$RC $(jq -r '.config | "\(.revision_check) \(.image_revision) \(.harness_commit)"' "$RUN/artifacts/perf-soak.json")"
has "differs: the annotation says so in one line" "Image revision $OTHER is not the harness commit $COMMIT_637 (revision check: differs; image revision an ancestor of the harness commit: " "$RUN/stub/annotation.md"
fails_with unlabelled "carries no usable org.opencontainers.image.revision label ('': absent)" STUB_IMAGE_REVISION=
check "unlabelled: no container was started" "0" "$(grep -c '^run ' "$RUN/stub/docker.calls" || true)"
check "unlabelled: the verdict is in the failed artifact" "absent $COMMIT_637" "$(jq -r '.config | "\(.revision_check) \(.harness_commit)"' "$RUN/artifacts/perf-soak.json")"
fails_with malformed-label "carries no usable org.opencontainers.image.revision label ('master': malformed)" STUB_IMAGE_REVISION=master
fails_with no-image "could not be inspected" STUB_IMAGE_ID= STUB_PULL_RC=1
fails_with oom "the SUT logged java.lang.OutOfMemoryError (1 line(s)" STUB_SERVER_LOG="$FLOCAL/server-oom.txt"
has "oom: the server's output is an artifact" "perf-soak-server.log" "$RUN/stub/uploads"
check "oom: the output was read" "true" "$(jq -r .server_log_read "$RUN/artifacts/perf-soak.json")"
removed oom
fails_with oom-killed "was not running at the end of the load (status, OOMKilled, exit code: 'exited true 137')" STUB_SERVER_STATE="exited true 137"
fails_with jvm-exit "was not running at the end of the load" STUB_SERVER_STATE="exited false 3"
fails_with no-gc-log "wrote no GC log" STUB_NO_GC_LOG=1
fails_with no-configuration "not recordable from the running server" STUB_CONFIGURATION=
has "no-configuration: names what could not be read" "maxLogEntries from http://127.0.0.1:32768/mockserver/configuration" "$RUN/out.log"
check "no-configuration: k6 never ran" "0" "$([ -f "$RUN/stub/k6.args" ] && echo 1 || echo 0)"
fails_with no-heap "resolved max heap" STUB_METRICS="$T/metrics-no-heap.txt"
fails_with no-memory-limit "container memory limit (docker inspect HostConfig.Memory: '0')" STUB_MEMORY=0
fails_with never-filled "the event log never reached a bound (filled=false binding=neither retained 0/600 entries" STUB_METRICS="$FLOCAL/metrics-start.txt"
fails_with old-server "do not say whether the log filled or which bound was binding" STUB_METRICS="$T/metrics-old-server.txt"
jq '.soak.match.windows |= .[0:4]' "$FLOCAL/k6-1.7.1-soak-result.json" > "$T/short-k6.json"
fails_with short-run "match-arm drift was not computed: 2 window(s) start at or after the 8 s warm-up" STUB_K6_RESULT="$T/short-k6.json"
fails_with no-result "k6 produced no soak-result.json" STUB_K6_RESULT= STUB_K6_EXIT=107
has "no-result: the samples are still an artifact" "perf-soak-samples.csv" "$RUN/stub/uploads"


printf '%s\n' 'the server said this before it stopped' > "$T/died.txt"
fails_with died-at-start "the SUT container stopped before it became ready (status, OOMKilled, exit code: 'exited false 1')" \
  STUB_PORT_RC=1 STUB_STATUS_CODE=000 STUB_SERVER_STATE="exited false 1" STUB_SERVER_LOG="$T/died.txt" \
  PERF_SOAK_READY_ATTEMPTS=2 PERF_SOAK_READY_INTERVAL=1
has "died-at-start: the reason is in the annotation" "stopped before it became ready" "$RUN/stub/annotation.md"
has "died-at-start: the server's output is uploaded" "perf-soak-server.log" "$RUN/stub/uploads"
has "died-at-start: and it is the server's" "the server said this before it stopped" "$RUN/artifacts/perf-soak-server.log"
check "died-at-start: its state is in the failed artifact" "exited false 1" "$(jq -r .server_state "$RUN/artifacts/perf-soak.json")"
check "died-at-start: k6 never ran" "0" "$([ -f "$RUN/stub/k6.args" ] && echo 1 || echo 0)"
removed died-at-start
fails_with never-ready "did not become ready on http://127.0.0.1:32768/mockserver/status in 2 polls, although its container kept running" \
  STUB_STATUS_CODE=503 PERF_SOAK_READY_ATTEMPTS=2 PERF_SOAK_READY_INTERVAL=1
has "never-ready: the server's output is uploaded" "perf-soak-server.log" "$RUN/stub/uploads"
check "never-ready: k6 never ran" "0" "$([ -f "$RUN/stub/k6.args" ] && echo 1 || echo 0)"
fails_with not-inspectable "could not be inspected before it became ready (it is gone, or docker did not answer), so its output could not be captured" \
  STUB_STATUS_CODE=000 STUB_INSPECT_RC=1 PERF_SOAK_READY_ATTEMPTS=2 PERF_SOAK_READY_INTERVAL=1
lacks "not-inspectable: no server output is claimed" "perf-soak-server.log" "$RUN/stub/uploads"
fails_with bad-ready-interval "PERF_SOAK_READY_ATTEMPTS ('60') and PERF_SOAK_READY_INTERVAL ('0.05') must be whole numbers above 0" PERF_SOAK_READY_INTERVAL=0.05
check "bad-ready-interval: nothing was started" "0" "$(grep -c '^run ' "$RUN/stub/docker.calls" 2>/dev/null || true)"
fails_with bad-ready-attempts "PERF_SOAK_READY_ATTEMPTS ('0')" PERF_SOAK_READY_ATTEMPTS=0
fails_with bad-drift-bound "PERF_SOAK_DRIFT_P99_BOUND ('1.6x') must be a number above 0" PERF_SOAK_DRIFT_P99_BOUND=1.6x
check "bad-drift-bound: nothing was started" "0" "$(grep -c '^run ' "$RUN/stub/docker.calls" 2>/dev/null || true)"
fails_with zero-drift-bound "PERF_SOAK_DRIFT_P99_BOUND ('0.0') must be a number above 0" PERF_SOAK_DRIFT_P99_BOUND=0.0

fails_with logs-unreadable "the SUT's output could not be read ('docker logs' failed)" STUB_LOGS_RC=1
check "logs-unreadable: recorded in the failed artifact" "false" "$(jq -r .server_log_read "$RUN/artifacts/perf-soak.json")"
has "logs-unreadable: the uploaded file says it is not the server's output" "'docker logs' failed" "$RUN/artifacts/perf-soak-server.log"
check "unlabelled: no container, so no state and no output to read" "null null" "$(jq -r '"\(.server_state) \(.server_log_read)"' "$T/unlabelled/artifacts/perf-soak.json")"

run_step result-copy-fails STUB_CP_FAIL=perf-soak.json
check "a result that cannot be copied to the artifact directory fails the step" "1" "$RC"
has "result-copy-fails: says which artifact" "ERROR: artifact perf-soak.json was NOT published: could not copy" "$RUN/out.log"
has "result-copy-fails: failure annotation posted" "perf-soak.json, could not be published" "$RUN/stub/annotation.md"
run_step result-upload-fails STUB_UPLOAD_FAIL=perf-soak.json
check "a result whose upload fails fails the step" "1" "$RC"
has "result-upload-fails: says which artifact" "ERROR: artifact perf-soak.json was NOT published: 'buildkite-agent artifact upload' failed" "$RUN/out.log"
check "result-upload-fails: the measured result is kept, marked failed with the reason" "false true 10 true count true 0" \
  "$(jq -r '"\(.soak_ok) \(.failure_reason | test("could not be published")) \(.soak.match.windows | length) \(.drift.match.computed) \(.event_log.binding) \(.ring | has("heap_end_bytes")) \(.k6_exit)"' "$RUN/artifacts/perf-soak.json")"
run_step evidence-copy-fails STUB_CP_FAIL=perf-soak-server.log
check "an evidence file that cannot be copied does not fail a sound soak" "0" "$RC"
has "evidence-copy-fails: but it is said" "ERROR: artifact perf-soak-server.log was NOT published" "$RUN/out.log"
check "evidence-copy-fails: the rest is uploaded" "perf-soak-gc-log.tgz perf-soak-samples.csv perf-soak.json" "$(sort "$RUN/stub/uploads" | tr '\n' ' ' | sed 's/ $//')"

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS weekly-soak check(s) failed" >&2; exit 1; fi
echo "all weekly-soak checks passed"

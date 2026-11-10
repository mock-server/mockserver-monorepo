#!/usr/bin/env bash
set -euo pipefail

# WEEKLY soak step (perf queue).
#
# Runs soak.js for a long duration (default 2h) against a MockServer with a
# BOUNDED heap, to surface slow degradation that a 2-minute regression run cannot:
# data-plane p99 DRIFT as the event log fills and stays full, connection/fd leaks
# over hours, and the COST of `verify` / `retrieveRecordedRequests`
# against a FULL log (the central-deployment pattern; an O(n) ring regression
# bites hardest here).
#
# Two INDEPENDENT loud-failure mechanisms (a scheduled-but-silent soak is the
# false green this step is built to prevent):
#   1. k6's own thresholds (match p95/p99, error rate, checks) — a breach
#      makes k6 exit non-zero, which this step propagates -> RED build. THIS is the
#      soak gate.
#   2. a PRESENCE ASSERTION after k6: the soak-result.json must exist AND its
#      match / verify / retrieve arms must each carry samples, AND the server's own
#      counters must show the event log reached a bound and evicted. A soak that
#      "ran" but produced no verify/retrieve measurement fails here, LOUD — it is never a
#      green with an empty result block.
#
# The step also refuses to measure an image with no usable revision label, and fails
# on a server that stops before it is ready, a server OutOfMemoryError, a dead or
# OOM-killed server container, unreadable server output, or a missing GC log. An image
# that trails the harness commit is measured and recorded as such.
#
# NOTIFY-ONLY otherwise. The soak result is uploaded as its OWN artifact
# (perf-soak.json) and is DELIBERATELY NOT fed to perf-test-compare.sh: no soak
# budget keys exist, and the daily compare's fail-closed missing-budget rule
# would red the build on a soak metric it cannot resolve. Only the absolute match
# p95/p99 gates below are derived; feeding soak metrics to the baseline compare
# is still deferred.
#
# WHY WEEKLY, OUT OF THE DAILY'S SLOT: a daily regression build can hold every
# perf agent at once, so a 2h soak starting in its 04:00 UTC window would queue
# behind it or delay it. This step is dispatched by a SEPARATE weekly schedule
# (terraform/buildkite-pipelines/pipelines.tf) at a time clear of 04:00 (daily
# regression) and 16:00 (baseline freshness).
#
# Reproduce a SHORT local soak (needs docker):
#   K6_SOAK_DURATION=100s K6_SOAK_WINDOW=10s K6_SOAK_WARMUP=20s PERF_SERVER_MEMORY=1g \
#   MOCKSERVER_MAX_LOG_ENTRIES=2000 PERF_PULL_IMAGE=false \
#   .buildkite/scripts/steps/perf-test-soak.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=lib/perf-java-opts.sh
. "$SCRIPT_DIR/lib/perf-java-opts.sh"
# shellcheck source=lib/perf-soak.sh
. "$SCRIPT_DIR/lib/perf-soak.sh"

# Pinned k6 image — identical ref to perf-test-run.sh / perf-test-lint.sh.
K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
# Plain snapshot: the soak exercises match / create / verify / retrieve only — no
# JavaScript response templates — so the GraalJS variant is not needed here.
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot}"

# Bounded heap so GC actually cycles and a slow leak / live-set floor is
# observable (an unbounded MaxRAMPercentage heap barely GCs and hides a leak).
SERVER_MEMORY="${PERF_SERVER_MEMORY:-2g}"
# Event-log ring: leave at the DEFAULT (never shrink it — a shrunk ring never
# fills at scale and would hide the very O(n) regression the verify/retrieve arms exist to catch).
# Overridable ONLY for a short local proof run (a small ring fills in seconds).
MAX_LOG_ENTRIES="${MOCKSERVER_MAX_LOG_ENTRIES:-}"

# Soak shape (pass-through to soak.js via K6_* — defaults in k6/lib/config.js and
# soak.js). Two hours is the target duration.
SOAK_DURATION="${K6_SOAK_DURATION:-2h}"
SOAK_WINDOW="${K6_SOAK_WINDOW:-5m}"
# Windows starting before this are never a drift reference. Long enough for JIT, the connection
# ramp and the first fill of the event log; the step records when the first eviction was seen.
SOAK_WARMUP="${K6_SOAK_WARMUP:-15m}"
# Drift compares the last LATE windows with the first REF windows after the warm-up.
SOAK_DRIFT_REF_WINDOWS=3
SOAK_DRIFT_LATE_WINDOWS=3
SOAK_RATE="${K6_SOAK_RATE:-200}"
# ABSOLUTE LATENCY THRESHOLDS — soak-specific, not k6/lib/config.js's shared load-test LIMITS.
# Derived from eight counting soaks: the slowest run plus headroom from the runs' spread. Re-derive them, do not hand-edit them: docs/code/performance-measurement.md, Absolute gates.
SOAK_P95_MS="${K6_P95_MS:-0.57}"
SOAK_P99_MS="${K6_P99_MS:-8.5}"
# Notify-only bound on drift.match.p99.ratio_worst, derived the same way. Exceeding it warns, never fails.
SOAK_DRIFT_P99_BOUND="${PERF_SOAK_DRIFT_P99_BOUND:-1.64}"
SOAK_VERIFY_RATE="${K6_SOAK_VERIFY_RATE:-1}"
SOAK_RETRIEVE_RATE="${K6_SOAK_RETRIEVE_RATE:-1}"
SAMPLE_INTERVAL="${PERF_SAMPLE_INTERVAL:-30}"
# Readiness: how many polls, the pause between them and the time one poll may take, in seconds.
READY_ATTEMPTS="${PERF_SOAK_READY_ATTEMPTS:-60}"
READY_INTERVAL="${PERF_SOAK_READY_INTERVAL:-2}"
READY_POLL_TIMEOUT=5
# Where perf-soak.json and the evidence files are written before upload.
ARTIFACT_DIR="${PERF_SOAK_ARTIFACT_DIR:-$REPO_ROOT}"
# An OutOfMemoryError must end the server and reach its stdout: at log level ERROR with system
# out disabled it would otherwise be swallowed. The GC log is per-PID (pid 1 is the server) and
# rotates at 200 MB, which is meant to keep the start of a two-hour run.
SOAK_JAVA_OPTS='-XX:+ExitOnOutOfMemoryError -Xlog:gc*,gc+heap=info:file=/diag/gc-%p.log:time,uptime,level,tags:filecount=10,filesize=20m'

RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$"
NETWORK="mockserver-soak-${RUN_ID}"
SERVER="mockserver-soak-${RUN_ID}"
SERVER_ALIAS="mockserver"

OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-soak.XXXXXX")"
# k6 runs as uid 12345; world-write the /out bind mount so handleSummary can write.
chmod 0777 "$OUT_DIR"
K6_RESULT="$OUT_DIR/soak-result.json"
SAMPLE_LOG="$OUT_DIR/samples.csv"
SOAK_JSON="$OUT_DIR/perf-soak.json"
SERVER_LOG="$OUT_DIR/server.log"
GC_LOG_TGZ="$OUT_DIR/gc-log.tgz"
# The server's JVM (a non-root user in the shipped image) writes its GC log here.
DIAG_DIR="$OUT_DIR/diag"
mkdir -p "$DIAG_DIR" && chmod 0777 "$DIAG_DIR"

HARNESS_COMMIT="${BUILDKITE_COMMIT:-}"
[[ "$HARNESS_COMMIT" =~ ^[0-9a-f]{40}$ ]] || HARNESS_COMMIT="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)"
IMAGE_ID=""; IMAGE_DIGEST=""; IMAGE_REVISION=""; REVISION_VERDICT=""; REVISION_ANCESTOR=""
LOG_LEVEL_VAL=""; CONTAINER_MEMORY_BYTES=""; HEAP_MAX_BYTES=""; MAX_LOG_ENTRIES_VAL=""; MAX_EVENT_LOG_BYTES_VAL=""
JAVA_TOOL_OPTS_VAL=""; SERVER_STATE=""; SERVER_LOG_READ=""; RESULT_ASSEMBLED=""
positive() { [[ "$1" =~ ^[0-9]+$ ]] && [ "$1" -gt 0 ]; }

# What this run measured, as far as it is known when called; an unread value is null.
provenance_json() {
  jq -nc --arg image "$MOCKSERVER_IMAGE" --arg image_id "$IMAGE_ID" --arg image_digest "$IMAGE_DIGEST" \
    --arg image_revision "$IMAGE_REVISION" --arg harness_commit "$HARNESS_COMMIT" \
    --arg revision_check "$REVISION_VERDICT" --arg ancestor "$REVISION_ANCESTOR" \
    --arg log_level "$LOG_LEVEL_VAL" --arg mem "$SERVER_MEMORY" --arg mem_bytes "$CONTAINER_MEMORY_BYTES" \
    --arg heap "$HEAP_MAX_BYTES" --arg mle "$MAX_LOG_ENTRIES_VAL" --arg melb "$MAX_EVENT_LOG_BYTES_VAL" \
    --arg jto "$JAVA_TOOL_OPTS_VAL" --arg duration "$SOAK_DURATION" --arg rate "$SOAK_RATE" \
    --arg window "$SOAK_WINDOW" --arg warmup "$SOAK_WARMUP" --arg p95 "$SOAK_P95_MS" --arg p99 "$SOAK_P99_MS" \
    --arg drift_bound "$SOAK_DRIFT_P99_BOUND" '
    def num: if . == "" then null else (tonumber? // null) end;
    def str: if . == "" then null else . end;
    { image: $image, image_id: ($image_id | str), image_digest: ($image_digest | str),
      image_revision: ($image_revision | str), harness_commit: $harness_commit,
      revision_check: ($revision_check | str), image_revision_ancestor_of_harness: ($ancestor | str),
      log_level: ($log_level | str),
      container_memory_limit: $mem, container_memory_limit_bytes: ($mem_bytes | num),
      heap_max_bytes: ($heap | num), max_log_entries: ($mle | num), max_event_log_bytes: ($melb | num),
      java_tool_options: ($jto | str),
      requested: { duration: $duration, window: $window, warmup: $warmup, match_rate_rps: ($rate | num),
                   match_p95_gate_ms: ($p95 | num), match_p99_gate_ms: ($p99 | num),
                   match_p99_drift_bound: ($drift_bound | num) },
      sources: { image_digest: "docker image inspect", image_revision: "image label",
                 log_level: "configuration endpoint", container_memory_limit_bytes: "docker inspect",
                 heap_max_bytes: "metrics", max_log_entries: "configuration endpoint",
                 max_event_log_bytes: "configuration endpoint", java_tool_options: "container env" } }'
}

# Copy one file into the artifact directory under its published name, and upload it. Returns 1,
# having said so, when the copy or the upload fails; a source file that does not exist is skipped.
publish_artifact() { # source_file artifact_name
  [ -f "$1" ] || return 0
  if ! cp "$1" "$ARTIFACT_DIR/$2"; then
    echo "ERROR: artifact $2 was NOT published: could not copy $1 to $ARTIFACT_DIR/$2" >&2
    return 1
  fi
  if command -v buildkite-agent >/dev/null 2>&1 \
     && ! (cd "$ARTIFACT_DIR" && buildkite-agent artifact upload "$2"); then
    echo "ERROR: artifact $2 was NOT published: 'buildkite-agent artifact upload' failed" >&2
    return 1
  fi
}

# The server's exit state, stdout/stderr and GC log, read while its container still exists (it is
# started without --rm for this). The state is read before the stop, so it is the state under load.
server_state() {
  docker inspect --format '{{.State.Status}} {{.State.OOMKilled}} {{.State.ExitCode}}' "$SERVER" 2>/dev/null || true
}
capture_server_evidence() {
  [ -z "$SERVER_STATE" ] || return 0
  SERVER_STATE="$(server_state)"
  [ -n "$SERVER_STATE" ] || return 0
  docker stop -t 10 "$SERVER" >/dev/null 2>&1 || true
  SERVER_LOG_READ=true
  if ! docker logs "$SERVER" > "$SERVER_LOG" 2>&1; then
    SERVER_LOG_READ=false
    echo "perf-test-soak.sh: 'docker logs' failed; anything above is not the server's whole output" >> "$SERVER_LOG"
  fi
  if [ -n "$(find "$DIAG_DIR" -name 'gc-*.log*' 2>/dev/null | head -1 || true)" ]; then
    tar -czf "$GC_LOG_TGZ" -C "$DIAG_DIR" . 2>/dev/null || true
  fi
}
publish_server_evidence() {
  publish_artifact "$SAMPLE_LOG" "perf-soak-samples.csv" || true
  publish_artifact "$SERVER_LOG" "perf-soak-server.log" || true
  publish_artifact "$GC_LOG_TGZ" "perf-soak-gc-log.tgz" || true
}

SAMPLER_PID=""
cleanup() {
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  docker rm -f "$SERVER" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
}
trap cleanup EXIT

# --- fail loud: assemble a MINIMAL failed artifact + annotation, then exit 1 ----
# Used by the presence assertion so a soak that produced nothing is never a silent
# green. Records soak_attempted:true so a reader can tell "attempted and failed"
# from "not scheduled".
fail_soak() {
  local reason="$1"
  echo "ERROR: $reason" >&2
  # Stop the sampler so samples.csv is complete before we upload it as evidence.
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""
  capture_server_evidence
  if [ -n "$RESULT_ASSEMBLED" ]; then
    # The soak completed: keep what it measured and say why the step failed.
    jq --arg reason "$reason" '. + {soak_ok:false, failure_reason:$reason}' "$SOAK_JSON" > "$SOAK_JSON.failed" 2>/dev/null \
      && mv "$SOAK_JSON.failed" "$SOAK_JSON" || true
  else
    jq -n --arg reason "$reason" --arg state "$SERVER_STATE" --arg log_read "$SERVER_LOG_READ" --argjson config "$(provenance_json)" \
      '{soak_attempted:true, soak_ok:false, failure_reason:$reason, config:$config,
        server_state:(if $state == "" then null else $state end),
        server_log_read:(if $log_read == "" then null else $log_read == "true" end)}' > "$SOAK_JSON" 2>/dev/null || true
  fi
  publish_artifact "$SOAK_JSON" "perf-soak.json" || true
  # Best-effort: the evidence needed to diagnose WHY the soak produced nothing; it otherwise dies
  # in the mktemp dir with the agent.
  publish_server_evidence
  publish_artifact "$K6_RESULT" "perf-soak-partial-result.json" || true
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' ":rotating_light: **Soak FAILED** — ${reason}. The weekly soak was scheduled and attempted but did not produce a valid result; this is a hard failure (a scheduled-but-silent soak must never read as green). Evidence (occupancy samples, server output, GC log and any partial k6 summary) uploaded as artifacts." \
      | buildkite-agent annotate --style error --context perf-soak 2>/dev/null || true
  fi
  exit 1
}

echo "--- soak config"
echo "    image=$MOCKSERVER_IMAGE  memory=$SERVER_MEMORY  maxLogEntries=${MAX_LOG_ENTRIES:-default}"
echo "    duration=$SOAK_DURATION  window=$SOAK_WINDOW  warmup=$SOAK_WARMUP  matchRate=$SOAK_RATE  verifyRate=$SOAK_VERIFY_RATE  retrieveRate=$SOAK_RETRIEVE_RATE"
echo "    readiness: $READY_ATTEMPTS polls, ${READY_INTERVAL}s apart, ${READY_POLL_TIMEOUT}s each at most"
positive "$READY_ATTEMPTS" && positive "$READY_INTERVAL" \
  || fail_soak "PERF_SOAK_READY_ATTEMPTS ('$READY_ATTEMPTS') and PERF_SOAK_READY_INTERVAL ('$READY_INTERVAL') must be whole numbers above 0"
if ! [[ "$SOAK_DRIFT_P99_BOUND" =~ ^(0|[1-9][0-9]*)(\.[0-9]+)?$ ]] || ! awk -v b="$SOAK_DRIFT_P99_BOUND" 'BEGIN{exit !(b + 0 > 0)}'; then
  fail_soak "PERF_SOAK_DRIFT_P99_BOUND ('$SOAK_DRIFT_P99_BOUND') must be a number above 0"
fi

# --- which image: the tag is mutable, so resolve it to an id once and start the server from that
# id (what is inspected is what runs). PERF_PULL_IMAGE=false measures a local image, which needs
# the revision label too.
if [ "${PERF_PULL_IMAGE:-true}" = "true" ]; then
  echo "--- pulling SUT image $MOCKSERVER_IMAGE"
  docker pull "$MOCKSERVER_IMAGE" \
    || echo "WARNING: 'docker pull $MOCKSERVER_IMAGE' failed — using the cached image if present; the revision check below still applies" >&2
fi
IMAGE_ID="$(docker image inspect --format '{{.Id}}' "$MOCKSERVER_IMAGE" 2>/dev/null || true)"
[ -n "$IMAGE_ID" ] || fail_soak "SUT image $MOCKSERVER_IMAGE could not be inspected (pull failed and no cached copy)"
IMAGE_DIGEST="$(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}}{{end}}' "$IMAGE_ID" 2>/dev/null || true)"
IMAGE_REVISION="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$IMAGE_ID" 2>/dev/null || true)"
REVISION_VERDICT="$(soak_revision_verdict "$HARNESS_COMMIT" "$IMAGE_REVISION")"
REVISION_ANCESTOR="$(soak_revision_ancestry "$REPO_ROOT" "$HARNESS_COMMIT" "$IMAGE_REVISION")"
echo "--- image $IMAGE_DIGEST revision='${IMAGE_REVISION}' harness=$HARNESS_COMMIT -> $REVISION_VERDICT (image revision an ancestor of the harness commit: $REVISION_ANCESTOR)"
case "$REVISION_VERDICT" in
  absent|malformed) fail_soak "SUT image $IMAGE_DIGEST carries no usable org.opencontainers.image.revision label ('${IMAGE_REVISION}': $REVISION_VERDICT), so this soak could not be tied to the commit it measured — the snapshot's labelling regressed (SOURCE_COMMIT in java-docker-push-snapshot.sh), or the image is a local build without the label" ;;
esac

docker network create "$NETWORK" >/dev/null
echo "--- starting SUT ($IMAGE_DIGEST, --memory=$SERVER_MEMORY)"
# Passing JAVA_TOOL_OPTIONS replaces the image's own (its GC selector), so prepend that. No --rm:
# a dead container must stay inspectable; cleanup() removes it by name.
SUT_JAVA_TOOL_OPTIONS="$(compose_java_tool_options "$(image_java_tool_options "$IMAGE_ID")" "$SOAK_JAVA_OPTS")"
# shellcheck disable=SC2046
docker run -d --name "$SERVER" --network "$NETWORK" --network-alias "$SERVER_ALIAS" \
  --memory="$SERVER_MEMORY" -p 127.0.0.1::1080 \
  -v "$DIAG_DIR:/diag" \
  -e "JAVA_TOOL_OPTIONS=$SUT_JAVA_TOOL_OPTIONS" \
  -e MOCKSERVER_LOG_LEVEL=ERROR \
  -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
  -e MOCKSERVER_METRICS_ENABLED=true \
  ${MAX_LOG_ENTRIES:+-e MOCKSERVER_MAX_LOG_ENTRIES="$MAX_LOG_ENTRIES"} \
  "$IMAGE_ID" -serverPort 1080 >/dev/null

# Readiness: poll PUT /mockserver/status (unauthenticated, all versions) rather
# than a port open — MockServer accepts-then-resets during init. A container that has
# already stopped has no port and never becomes ready: say so at once, with its output.
SERVER_HOSTPORT="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1 || true)"
STATUS_URL="http://${SERVER_HOSTPORT:-127.0.0.1:1080}/mockserver/status"
METRICS_URL="http://${SERVER_HOSTPORT:-127.0.0.1:1080}/mockserver/metrics"
CONFIGURATION_URL="http://${SERVER_HOSTPORT:-127.0.0.1:1080}/mockserver/configuration"
ready=false
for _ in $(seq 1 "$READY_ATTEMPTS"); do
  if [ "$(curl -s -o /dev/null -w '%{http_code}' --max-time "$READY_POLL_TIMEOUT" -X PUT "$STATUS_URL" 2>/dev/null || echo 000)" = "200" ]; then
    ready=true; break
  fi
  STARTING_STATE="$(server_state)"
  case "$STARTING_STATE" in
    "running "*) ;;
    "") fail_soak "the SUT container could not be inspected before it became ready (it is gone, or docker did not answer), so its output could not be captured" ;;
    *) fail_soak "the SUT container stopped before it became ready (status, OOMKilled, exit code: '$STARTING_STATE'); its output is uploaded as perf-soak-server.log" ;;
  esac
  sleep "$READY_INTERVAL"
done
[ "$ready" = true ] || fail_soak "SUT did not become ready on $STATUS_URL in $READY_ATTEMPTS polls, although its container kept running; its output is uploaded as perf-soak-server.log"
echo "--- SUT ready on $STATUS_URL"

# --- provenance: what the RUNNING server resolved, not what this script meant to set ------------
# Read before the load so an unrecordable configuration costs seconds, not the two-hour run.
START_METRICS="$(curl -sf --max-time 5 "$METRICS_URL" 2>/dev/null || true)"
SERVER_CONFIGURATION="$(curl -sf --max-time 5 "$CONFIGURATION_URL" 2>/dev/null || true)"
config_field() { jq -r --arg k "$1" '.[$k] // empty' <<<"$SERVER_CONFIGURATION" 2>/dev/null || true; }
LOG_LEVEL_VAL="$(config_field logLevel)"
MAX_LOG_ENTRIES_VAL="$(config_field maxLogEntries)"
MAX_EVENT_LOG_BYTES_VAL="$(config_field maxEventLogSizeInBytes)"
HEAP_MAX_BYTES="$(soak_heap_max_bytes <<<"$START_METRICS")"
MAX_RETAINED_ENTRIES="$(soak_metric_value mock_server_event_log_max_retained_entries <<<"$START_METRICS")"
MAX_RETAINED_BYTES="$(soak_metric_value mock_server_event_log_max_retained_bytes <<<"$START_METRICS")"
CONTAINER_MEMORY_BYTES="$(docker inspect --format '{{.HostConfig.Memory}}' "$SERVER" 2>/dev/null || true)"
JAVA_TOOL_OPTS_VAL="$(docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$SERVER" 2>/dev/null \
  | awk -F= '$1=="JAVA_TOOL_OPTIONS"{sub("^[^=]*=",""); print}' || true)"
UNRECORDED=""
unrecorded() { UNRECORDED="${UNRECORDED:+$UNRECORDED; }$1"; }
[ -n "$LOG_LEVEL_VAL" ] || unrecorded "log level (logLevel from $CONFIGURATION_URL)"
[[ "$MAX_LOG_ENTRIES_VAL" =~ ^[0-9]+$ ]] || unrecorded "maxLogEntries from $CONFIGURATION_URL ('$MAX_LOG_ENTRIES_VAL')"
[[ "$MAX_EVENT_LOG_BYTES_VAL" =~ ^-?[0-9]+$ ]] || unrecorded "maxEventLogSizeInBytes from $CONFIGURATION_URL ('$MAX_EVENT_LOG_BYTES_VAL')"
positive "$HEAP_MAX_BYTES" || unrecorded "resolved max heap (jvm_memory_max_bytes{area=\"heap\"}: '$HEAP_MAX_BYTES')"
positive "$MAX_RETAINED_ENTRIES" || unrecorded "event-log entry limit in force (mock_server_event_log_max_retained_entries: '$MAX_RETAINED_ENTRIES')"
[[ "$MAX_RETAINED_BYTES" =~ ^[0-9]+$ ]] || unrecorded "event-log byte budget in force (mock_server_event_log_max_retained_bytes: '$MAX_RETAINED_BYTES')"
positive "$CONTAINER_MEMORY_BYTES" || unrecorded "container memory limit (docker inspect HostConfig.Memory: '$CONTAINER_MEMORY_BYTES')"
[ -n "$IMAGE_DIGEST" ] || unrecorded "image digest"
[ -z "$UNRECORDED" ] || fail_soak "run configuration is not recordable from the running server — refusing to measure what cannot be described: $UNRECORDED"
echo "--- provenance: logLevel=$LOG_LEVEL_VAL heapMax=$HEAP_MAX_BYTES containerLimit=$CONTAINER_MEMORY_BYTES maxLogEntries=$MAX_LOG_ENTRIES_VAL maxEventLogSizeInBytes=$MAX_EVENT_LOG_BYTES_VAL (in force: $MAX_RETAINED_ENTRIES entries, $MAX_RETAINED_BYTES bytes)"

# --- background sampler: log inflow, heap, drops, threads, event-log occupancy -------------------
# CHEAP metrics reads only (no full-log retrieve, which would itself perturb the
# measurement). soak.js resets the server in teardown, so the event log's end-of-load
# state can only come from these samples.
sampler() {
  soak_sample_header > "$SAMPLE_LOG"
  while true; do
    local m
    m="$(curl -s --max-time 5 "$METRICS_URL" 2>/dev/null || echo '')"
    soak_sample_row "$(date -u +%s)" <<<"$m" >> "$SAMPLE_LOG"
    sleep "$SAMPLE_INTERVAL"
  done
}
sampler & SAMPLER_PID=$!

# The retrieve arm's `type=REQUESTS` call scans the whole event log, which at the
# default budgets holds far more entries than a local proof run's small ring. It
# must not perturb the GATED match p99 or the drift figures (see the soak section of
# docs/code/performance-measurement.md); if it ever does, lower K6_SOAK_RETRIEVE_RATE.
echo "--- soak.js ($SOAK_DURATION)"
K6_START_TS="$(date -u +%s)"
SOAK_EXIT=0
# shellcheck disable=SC2046
docker run --rm --network "$NETWORK" \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "PROTO=http" \
  -e "K6_SOAK_DURATION=$SOAK_DURATION" \
  -e "K6_SOAK_WINDOW=$SOAK_WINDOW" \
  -e "K6_SOAK_WARMUP=$SOAK_WARMUP" \
  -e "K6_SOAK_DRIFT_WINDOWS=$((SOAK_DRIFT_REF_WINDOWS + SOAK_DRIFT_LATE_WINDOWS))" \
  -e "K6_SOAK_RATE=$SOAK_RATE" \
  -e "K6_P95_MS=$SOAK_P95_MS" \
  -e "K6_P99_MS=$SOAK_P99_MS" \
  -e "K6_SOAK_VERIFY_RATE=$SOAK_VERIFY_RATE" \
  -e "K6_SOAK_RETRIEVE_RATE=$SOAK_RETRIEVE_RATE" \
  -e "K6_SOAK_RESULT_PATH=/out/soak-result.json" \
  "$K6_IMAGE" run /k6/soak.js || SOAK_EXIT=$?

kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""

# --- the server itself: still running, no OutOfMemoryError, a GC log to read -------------------
capture_server_evidence
case "$SERVER_STATE" in
  "running false "*) ;;
  *) fail_soak "the SUT container was not running at the end of the load (status, OOMKilled, exit code: '${SERVER_STATE:-not inspectable}') — OOMKilled=true is a cgroup kill at the container memory limit; an exit under -XX:+ExitOnOutOfMemoryError is an in-JVM OutOfMemoryError. Everything measured after it died is of a dead server" ;;
esac
[ "$SERVER_LOG_READ" = true ] \
  || fail_soak "the SUT's output could not be read ('docker logs' failed), so it was not checked for OutOfMemoryError"
OOM_LINES="$(soak_oom_lines "$SERVER_LOG")"
[ "$OOM_LINES" -eq 0 ] 2>/dev/null \
  || fail_soak "the SUT logged java.lang.OutOfMemoryError ($OOM_LINES line(s) in its stdout/stderr, uploaded as perf-soak-server.log)"
[ -n "$(find "$DIAG_DIR" -name 'gc-*.log*' -size +0 2>/dev/null | head -1 || true)" ] \
  || fail_soak "the SUT wrote no GC log under /diag (JAVA_TOOL_OPTIONS handed to it: '${JAVA_TOOL_OPTS_VAL}') — the soak cannot show its GC behaviour"

# --- end-of-load state from the sample log (see soak_samples_json) ------------------------------
# dropped_log_events > 0 means the disruptor ring saturated (recorded, not gated).
SAMPLES_JSON='{"rows":0}'
[ -f "$SAMPLE_LOG" ] && SAMPLES_JSON="$(soak_samples_json "$SAMPLE_LOG" "$K6_START_TS")"
SAMPLE_ROWS="$(jq -r '.rows // 0' <<<"$SAMPLES_JSON")"
EVENT_LOG_JSON="$(soak_event_log_json "$MAX_RETAINED_ENTRIES" "$MAX_RETAINED_BYTES" "$SAMPLES_JSON")"

# --- PRESENCE ASSERTION (false-green guard) -------------------------------------
[ -f "$K6_RESULT" ] || fail_soak "k6 produced no soak-result.json (exit=$SOAK_EXIT) — the soak did not run to a summary"
if ! jq empty "$K6_RESULT" >/dev/null 2>&1; then
  fail_soak "soak-result.json is not valid JSON (exit=$SOAK_EXIT)"
fi
MATCH_SAMPLES="$(jq -r '.soak.match.samples // 0' "$K6_RESULT")"
VERIFY_SAMPLES="$(jq -r '.soak.verify.samples // 0' "$K6_RESULT")"
RETRIEVE_SAMPLES="$(jq -r '.soak.retrieve.samples // 0' "$K6_RESULT")"
[ "$MATCH_SAMPLES" -gt 0 ] 2>/dev/null || fail_soak "soak match arm produced 0 samples — the data-plane load never ran"
# The verify/retrieve arms are the point of the soak; an empty one is a
# silent-nothing result, not a pass.
[ "$VERIFY_SAMPLES" -gt 0 ] 2>/dev/null || fail_soak "soak 10b verify arm produced 0 samples — event-log verification cost was NOT measured"
[ "$RETRIEVE_SAMPLES" -gt 0 ] 2>/dev/null || fail_soak "soak 10b retrieve arm produced 0 samples — event-log retrieval cost was NOT measured"
[ "$SAMPLE_ROWS" -gt 0 ] 2>/dev/null || fail_soak "no server metric samples captured — occupancy trajectory is empty"
# The verify/retrieve measurement is only meaningful against a FULL log. "Full" is the server's own word: it
# evicted entries, and its retained gauges say which bound (entry count or byte budget) did it.
EVENT_LOG_STATE="$(jq -r '"filled=\(.filled) binding=\(.binding) retained \(.retained_entries_end)/\(.max_retained_entries) entries, \(.retained_bytes_end)/\(.max_retained_bytes) bytes, evicted \(.evicted_log_entries)"' <<<"$EVENT_LOG_JSON")"
case "$(jq -r '"\(.filled) \(.binding)"' <<<"$EVENT_LOG_JSON")" in
  "true count"|"true bytes") ;;
  "false "*) fail_soak "the event log never reached a bound ($EVENT_LOG_STATE) — the 10b latency was NOT measured against a full log" ;;
  *) fail_soak "the server's event-log counters do not say whether the log filled or which bound was binding ($EVENT_LOG_STATE) — the samples carry no usable retained/evicted series" ;;
esac
# Drift is notify-only, but a soak whose match-arm drift cannot be computed measured nothing about
# whether latency moved over the run.
DRIFT_JSON="$(jq -c --argjson ref_n "$SOAK_DRIFT_REF_WINDOWS" --argjson late_n "$SOAK_DRIFT_LATE_WINDOWS" \
  -f "$SCRIPT_DIR/lib/perf-soak-drift.jq" "$K6_RESULT")"
[ "$(jq -r '.match.computed' <<<"$DRIFT_JSON")" = true ] \
  || fail_soak "match-arm drift was not computed: $(jq -r '.match.reason // "unknown"' <<<"$DRIFT_JSON")"

# --- DIAGNOSTIC GUARD: "the SUT is answering, but with 404s" ---------------------
# A harness that evicts its own seeded /simple match expectation (as an unbounded
# create arm once did) makes the SUT correctly 404 most match requests while every
# presence assertion above still passes. This guard catches that shape: a HIGH match failure rate
# (http_req_failed, which counts completed non-2xx responses) combined with ~ZERO
# transport errors (requests that never completed) means the SUT was UP and
# ANSWERING but returning non-2xx (almost always 404). A genuinely DOWN SUT shows a
# high transport-error rate instead and is left to the k6 error-rate gate; this
# guard deliberately does NOT fire on that shape.
MATCH_ERR_RATE="$(jq -r '.soak.match.error_rate // 0' "$K6_RESULT")"
MATCH_TRANSPORT_ERRORS="$(jq -r '.soak.match.transport_errors // 0' "$K6_RESULT")"
# Float comparison via awk — a shell/jq STRING compare on rates is a known footgun
# ("0.542" <= "0.05" mis-orders lexically). "High" = >=5% (the healthy match arm is
# ~0%, well under the 1% k6 gate); "~zero transport" = transport-error rate <=0.5%.
ANSWERING_WITH_404S="$(awk -v er="$MATCH_ERR_RATE" -v te="$MATCH_TRANSPORT_ERRORS" -v n="$MATCH_SAMPLES" \
  'BEGIN{ ter=(n>0 ? te/n : 0); print (((er+0)>=0.05) && (ter<=0.005)) ? "yes" : "no" }')"
if [ "$ANSWERING_WITH_404S" = "yes" ]; then
  fail_soak "match arm failed ${MATCH_ERR_RATE} of requests with only ${MATCH_TRANSPORT_ERRORS} transport errors across ${MATCH_SAMPLES} samples — the SUT was UP and ANSWERING but returning non-2xx, NOT a transport/connection failure. Read the per-status breakdown in the uploaded partial k6 result before assuming a cause; this guard classifies the SHAPE, it does not diagnose. Two known causes of this shape, in order of likelihood: (1) the harness evicting its own match seed — the build-324 bug, where an unbounded 'create' arm filled maxExpectations and evicted the seeded /simple expectation, giving 404s; check createSimpleExpectation() still uses a stable id and a distinct path (/churn), and do NOT paper over it by raising maxExpectations. (2) the SUT genuinely returning completed 5xx under load, which is a real regression and looks nothing like (1) in the status breakdown."
fi

echo "--- event log: $EVENT_LOG_STATE"

# --- assemble the notify-only soak artifact ------------------------------------
# Wraps the k6 soak block with run metadata + the ring/occupancy block. NOT the
# daily-regression result shape and NOT downloaded by perf-test-compare.sh, so it
# cannot perturb the fail-closed missing-budget rule. When soak metrics graduate
# to the baseline (~8 weekly runs), a `.soak` enumeration in compare.sh maps this
# straight onto soak.<arm>.<metric> budget keys.
BRANCH="${BUILDKITE_BRANCH:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"

jq -n \
  --arg branch "$BRANCH" --arg commit "$HARNESS_COMMIT" --arg ts "$TS" \
  --argjson k6exit "$SOAK_EXIT" --argjson config "$(provenance_json)" \
  --argjson samples "$SAMPLES_JSON" --argjson event_log "$EVENT_LOG_JSON" --argjson drift "$DRIFT_JSON" \
  --argjson drift_bound "$SOAK_DRIFT_P99_BOUND" --arg server_state "$SERVER_STATE" \
  --slurpfile k6 "$K6_RESULT" \
  '{
     soak_attempted: true,
     soak_ok: ($k6exit == 0),
     k6_exit: $k6exit,
     branch: $branch, commit: $commit, timestamp_utc: $ts,
     config: $config,
     server_state: $server_state,
     event_log: $event_log,
     ring: ($samples | { requests_received_total, pre_teardown_heap_min_bytes, pre_teardown_heap_min_samples,
                         heap_start_bytes, heap_end_bytes, dropped_log_events, threads_end, sample_rows: .rows }),
     drift: $drift,
     drift_check: ($drift.match.p99.ratio_worst as $w
       | { metric: "drift.match.p99.ratio_worst", bound: $drift_bound, value: $w,
           exceeded: (if $w == null then null else $w >= $drift_bound end) })
   } + $k6[0]' > "$SOAK_JSON"

echo "--- perf-soak.json"
cat "$SOAK_JSON"

# --- human annotation (NOTIFY-ONLY) --------------------------------------------
# The evidence files go up on EVERY outcome (a k6 threshold exit skips fail_soak), under the same names.
RESULT_ASSEMBLED=true
publish_artifact "$SOAK_JSON" "perf-soak.json" \
  || fail_soak "the soak ran but its result, perf-soak.json, could not be published (see the ERROR line above)"
publish_server_evidence
if command -v buildkite-agent >/dev/null 2>&1; then
  STYLE="info"
  [ "$(jq -r '.drift_check.exceeded' "$SOAK_JSON")" = true ] && STYLE="warning"
  [ "$SOAK_EXIT" -ne 0 ] && STYLE="error"
  soak_annotation_md "$SOAK_JSON" | buildkite-agent annotate --style "$STYLE" --context perf-soak || true
else
  echo "(local run) soak artifacts -> $ARTIFACT_DIR/perf-soak.json (+ perf-soak-samples.csv, perf-soak-server.log, perf-soak-gc-log.tgz)"
fi

# Propagate the k6 threshold verdict: a match p95/p99 or error-rate breach IS the soak
# failure signal and must red the build.
if [ "$SOAK_EXIT" -ne 0 ]; then
  echo "ERROR: soak thresholds crossed (k6 exit=$SOAK_EXIT) — read the k6 'thresholds on metrics' line above for WHICH one; the match arm gates on p95 AND p99 AND error rate, so do not assume p99 (build 340 crossed p95 at 62.6ms against 25ms while p99 passed at 96.1ms against 100ms)" >&2
  exit "$SOAK_EXIT"
fi
echo "--- soak complete (thresholds held)"

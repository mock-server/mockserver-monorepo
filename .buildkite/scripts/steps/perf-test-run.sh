#!/usr/bin/env bash
set -euo pipefail

# Periodic performance-regression RUN step (perf queue). Produces ONE result JSON
# (uploaded as a Buildkite artifact) that perf-test-compare.sh baseline-checks.
#
# Phases:
#   1. start a DEDICATED upstream MockServer + the MockServer under test
#      (metrics enabled, DEFAULT maxLogEntries — never shrink it, see growth)
#   2. regression.js over HTTP, then over HTTPS+H2  -> per-behaviour latency
#   3. growth.js (sustained load) with a background CPU/heap sampler
#      -> resource-growth slope ratios (issue #2329 class)
#   4. assemble result.json {metadata, behaviours, growth, resources}
#
# Co-located load-gen + server: on a >=16 vCPU box the server, upstream and k6
# are core-pinned to disjoint cpusets so they don't steal cycles (the single
# biggest factor in number quality). On a smaller box pinning is skipped with a
# warning — numbers are then noisier but the run still works for local checks.
#
# Durations pass through to k6 via K6_* env (defaults in k6/lib/config.js). The
# MockServer image is MOCKSERVER_IMAGE (default snapshot).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

# --- arm-only mode (the perf-xl steps perf-test-guard.sh dispatches) ----------
# PERF_RUN_ARM=rw_multik6|hw_matrix measures ONE opt-in arm on the provenance-checked SUT and skips
# every other phase. The result keeps the full schema with the other blocks empty, is never
# baseline-eligible, and is uploaded under a PERF_RUN_NAME prefix that the daily compare never
# downloads. The multi-k6 arm's own compare step reads its result by name.
# Arms and workloads inherited from the build env are other measurements, so they are forced off.
PERF_RUN_ARM="${PERF_RUN_ARM:-}"
case "$PERF_RUN_ARM" in
  "") ;;
  rw_multik6) PERF_SERVING_RW_MULTIK6=true; PERF_SERVING_HW_MATRIX=false ;;
  hw_matrix) PERF_SERVING_HW_MATRIX=true; PERF_SERVING_RW_MULTIK6=false ;;
  *) echo "ERROR: PERF_RUN_ARM='$PERF_RUN_ARM' is not one of: rw_multik6 | hw_matrix (empty = the full run)" >&2; exit 1 ;;
esac
if [ -n "$PERF_RUN_ARM" ]; then
  if [ "$PERF_RUN_ARM" = rw_multik6 ] && [ "${PERF_NETWORK_MODE:-bridge}" = host ]; then
    echo "ERROR: PERF_RUN_ARM=rw_multik6 needs bridge networking (PERF_NETWORK_MODE=host would skip the arm and measure nothing)" >&2; exit 1
  fi
  PERF_RUN_NAME="${PERF_RUN_NAME:-arm-${PERF_RUN_ARM}}"
  PERF_INFO_ARM=false; PERF_STREAMING=false; PERF_CLUSTERED=false; PERF_PROXY_PROFILE=false; PERF_PIPELINING=false
  # shellcheck disable=SC2034  # read by lib/perf-path-coverage.sh, sourced below
  PERF_COVERAGE=false
  PERF_LAPTOP_PROFILE=false; PERF_LAPTOP_PARALLEL=false; PERF_LARGE_HEAP_PROFILE=false
  PERF_SERVING_PERCORE=false; PERF_SERVING_MULTIPROC=false; PERF_WORKLOAD=""; PERF_STEADY_RATE=""
  echo "--- arm-only run: PERF_RUN_ARM=$PERF_RUN_ARM (artifacts prefixed ${PERF_RUN_NAME}-, never in the daily baseline)"
elif [ "${PERF_XL:-false}" = "true" ]; then
  # PERF_XL=true runs these two arms as their own perf-xl steps (perf-test-guard.sh), not here.
  PERF_SERVING_RW_MULTIK6=false; PERF_SERVING_HW_MATRIX=false
  echo "--- PERF_XL=true: the multi-k6 arm and the hardware matrix run on the perf-xl queue, not in this step"
fi
arm_only() { [ -n "$PERF_RUN_ARM" ]; }

# shellcheck source=.buildkite/scripts/lib/snapshot-image-inputs.sh
source "$SCRIPT_DIR/../lib/snapshot-image-inputs.sh"

# A UI build or a [perf-run] build asks for its own commit to be measured (perf-test-guard.sh's predicate).
forced_run() { [ "${BUILDKITE_SOURCE:-}" = "ui" ] || [[ "${BUILDKITE_MESSAGE:-}" == *"[perf-run]"* ]]; }

# Prints why a forced master run must not measure an image labelled $1 when the harness is $2;
# prints nothing when it may. Scheduled runs, the weekly [perf-run] perf-xl one included, keep
# attributing an older image to its own revision.
image_revision_refusal() { # image_revision harness_commit
  forced_run || return 0
  [ "${BUILDKITE_SOURCE:-}" != schedule ] || return 0
  [ "${BUILDKITE_BRANCH:-master}" = master ] || return 0
  [ -z "${PERF_RELEASE_COMPARISON:-}" ] || return 0
  snapshot_equivalent "$REPO_ROOT" "$1" "$2" && return 0
  echo "the SUT image ${MOCKSERVER_IMAGE:-} was built from ${1:-<no revision label>}, and the harness commit $2 is not served that image (mockserver-java paths changed since, or the commit is not in this clone)"
}

# The harness commit counts as measured only when it is served the measured image (perf-test-guard.sh
# skips a commit recorded as run, so a run on an older image must leave it unrecorded).
harness_commit_measured() {
  [ "${PROVENANCE_OK:-}" != false ] && snapshot_equivalent "$REPO_ROOT" "$ATTRIBUTED_COMMIT" "$HARNESS_COMMIT"
}

# --- artifact-name prefixing (run identity) -----------------------------------
# Every Buildkite artifact this script uploads is named from PERF_RUN_NAME. UNSET
# (the standard daily `perf-run` step) leaves names byte-identical to before
# (perf-result.json, perf-sweep.json, perf-jvm-diagnostics.tgz, ...), which
# perf-test-compare.sh downloads build-wide by EXACT name and persists to S3, and
# which perf-website-publish.sh reads from S3. A separate, throughput-degrading step
# (perf-test-allocprofile.sh) sets PERF_RUN_NAME=allocprofile so its artifacts
# (allocprofile-perf-result.json, ...) cannot match any consumer's exact-name
# download and so can never enter or poison the rolling baseline.
PERF_RUN_NAME="${PERF_RUN_NAME:-}"
ARTIFACT_PREFIX="${PERF_RUN_NAME:+${PERF_RUN_NAME}-}"

# Upload $1 (a file already at $REPO_ROOT) under ARTIFACT_PREFIX. With no prefix this
# is byte-identical to `buildkite-agent artifact upload "$1" || true`; with a prefix
# the file is copied to the prefixed name first so the STORED artifact carries it.
bk_upload_artifact() {
  local base="$1"
  if [ -z "$ARTIFACT_PREFIX" ]; then
    buildkite-agent artifact upload "$base" || true
    return 0
  fi
  cp "$REPO_ROOT/$base" "$REPO_ROOT/${ARTIFACT_PREFIX}${base}" 2>/dev/null || return 0
  buildkite-agent artifact upload "${ARTIFACT_PREFIX}${base}" || true
}

K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
# --- OPT-IN release comparison (docs/code/performance-measurement.md) ------------
# PERF_RELEASE_COMPARISON=<version> measures that published release on this rig. The run
# is config_profile=release-<version>, so never baselined or published. Relaxed for it
# only: snapshot freshness, the revision-label grace date, and GC/JDK read via jcmd when
# the image predates jvm_runtime_info. The clustered A/B defaults off (its image is the
# snapshot, not the release), and so does the pipelining A/B (a release before the flag has no gate).
PERF_RELEASE_COMPARISON="${PERF_RELEASE_COMPARISON:-}"
if [ -n "$PERF_RELEASE_COMPARISON" ]; then
  if ! [[ "$PERF_RELEASE_COMPARISON" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "ERROR: PERF_RELEASE_COMPARISON='$PERF_RELEASE_COMPARISON' is not a release version (MAJOR.MINOR.PATCH, e.g. 8.0.0)" >&2
    exit 1
  fi
  MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-${PERF_RELEASE_COMPARISON}-graaljs}"
  PERF_CLUSTERED="${PERF_CLUSTERED:-false}"
  PERF_PIPELINING="${PERF_PIPELINING:-false}"
  echo "--- release comparison: measuring MockServer ${PERF_RELEASE_COMPARISON} ($MOCKSERVER_IMAGE); the result is recorded but never baselined or published"
fi
# The SUT runs the -graaljs snapshot variant so the JavaScript response-template
# arm (regression.js) has the GraalVM JS engine available; the engine is
# an OPTIONAL dependency absent from the plain image, and a JS template without it
# fails loud (500). The extra jars are inert for every non-JS arm (loaded lazily
# only when a JS template renders), so the match/forward/velocity/mustache/large
# numbers are unaffected. Overridable; if you point this at a NON-graaljs image,
# also set PERF_JS_TEMPLATE=false or regression.js aborts the run loudly.
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot-graaljs}"
# Enable the JavaScript template arm (default on; the SUT image above carries
# GraalJS), and the server-side path of the file-backed response body.
PERF_JS_TEMPLATE="${PERF_JS_TEMPLATE:-true}"
FILE_BODY_CONTAINER_PATH="/perf-files/large-file-body.json"

# --- OPT-IN workload extension (unmatched-proxy concurrency) --------------------
# The default run measures the mock/forward/template paths and is UNCHANGED by this
# block: PERF_WORKLOAD is empty by default, so every branch guarded on it is skipped
# and the published curve + rolling baseline are byte-for-byte comparable.
#
# When set to "forward" it runs ONE extra, non-baseline pass AFTER the normal
# measurement: it makes the run's upstream SLOW (PERF_UPSTREAM_DELAY_MS) and
# re-drives proxy.js's UNMATCHED-proxy arm (absolute-URI, which is
# handleUnmatchedProxyForward — the path this workload exists for; the MATCHED /forward arm
# is already async and shows nothing) at elevated concurrency, so
# offered_rate x upstream_latency exceeds the blocking action-handler pool and the
# ~poolSize in-flight cap becomes visible as a delivery_ratio drop / p99 blow-up.
# (The default fast upstream keeps in-flight ~0.04, far below the cap, which is why
# the standard proxy arm cannot show it.) It flips config_profile off "default"
# below, so the run is recorded but NEVER persisted to the default-configuration
# baseline (same lever as a tuned run).
PERF_WORKLOAD="${PERF_WORKLOAD:-}"
# Server-side delay (ms) seeded onto the upstream /simple for PERF_WORKLOAD=forward,
# so the unmatched-proxy in-flight cap binds. 0 (fast upstream) cannot reveal it.
PERF_UPSTREAM_DELAY_MS="${PERF_UPSTREAM_DELAY_MS:-0}"
case "$PERF_WORKLOAD" in
  ""|forward) : ;;
  *) echo "ERROR: PERF_WORKLOAD='$PERF_WORKLOAD' is not one of: forward (empty = default run, no extra workload)" >&2; exit 1 ;;
esac
if ! grep -Eq '^[0-9]+$' <<<"$PERF_UPSTREAM_DELAY_MS"; then
  echo "ERROR: PERF_UPSTREAM_DELAY_MS='$PERF_UPSTREAM_DELAY_MS' is not a non-negative integer (milliseconds)" >&2; exit 1
fi
if [ "$PERF_WORKLOAD" = "forward" ] && [ "$PERF_UPSTREAM_DELAY_MS" -eq 0 ]; then
  echo "ERROR: PERF_WORKLOAD=forward needs PERF_UPSTREAM_DELAY_MS > 0 — with a fast upstream the unmatched-proxy concurrency cap (unit 21) stays far below the offered rate and the run would measure nothing new. Set e.g. PERF_UPSTREAM_DELAY_MS=50." >&2
  exit 1
fi
# These feed the result JSON via jq --argjson, so validate as positive integers here
# (a malformed value would otherwise abort result assembly, losing the whole run).
if [ "$PERF_WORKLOAD" = "forward" ]; then
  for _wl_var in PERF_WORKLOAD_FORWARD_RATE PERF_WORKLOAD_FORWARD_VUS; do
    _wl_val="$(eval "printf '%s' \"\${${_wl_var}:-}\"")"
    if [ -n "$_wl_val" ] && ! grep -Eq '^[1-9][0-9]*$' <<<"$_wl_val"; then
      echo "ERROR: ${_wl_var}='${_wl_val}' is not a positive integer" >&2; exit 1
    fi
  done
fi

# --- OPT-IN latency-tail experiment levers (docs/code/performance-measurement.md
# -> "Rung-onset exclusion") ---------
# Both default so a run with neither set is byte-identical to before AND stays
# baseline-eligible; each flips a profile below so an experiment never enters the
# published baseline. PERF_NETWORK_MODE=host puts SUT+upstream+k6 on --network host
# (Linux CI) to take the docker bridge out of the path. One host stack cannot hold
# the multi-SUT secondary phases (INFO/streaming/clustered/forward/handshake) on the
# same port, so host mode runs only the primary path (regression/sweep/growth/steady)
# and disables them here.
PERF_NETWORK_MODE="${PERF_NETWORK_MODE:-bridge}"
case "$PERF_NETWORK_MODE" in
  bridge|host) : ;;
  *) echo "ERROR: PERF_NETWORK_MODE='$PERF_NETWORK_MODE' is not one of: bridge (default) | host" >&2; exit 1 ;;
esac
# PERF_STEADY_RATE=<rps> runs ONE constant-arrival-rate pass at a single rate (no
# per-rung ramp), warm-up excluded (PERF_STEADY_WARMUP) from the reported window.
PERF_STEADY_RATE="${PERF_STEADY_RATE:-}"
if [ -n "$PERF_STEADY_RATE" ] && ! grep -Eq '^[1-9][0-9]*$' <<<"$PERF_STEADY_RATE"; then
  echo "ERROR: PERF_STEADY_RATE='$PERF_STEADY_RATE' is not a positive integer (requests/second)" >&2; exit 1
fi
# JVM diagnostics tier (described at the JVM-internals diagnostics block below).
PERF_JVM_DIAGNOSTICS="${PERF_JVM_DIAGNOSTICS:-standard}"   # standard = tier 1; gc = tier 1 + GC file log; deep = tier 1 + tier 2
case "$PERF_JVM_DIAGNOSTICS" in
  standard|gc|deep) ;;
  *) echo "ERROR: PERF_JVM_DIAGNOSTICS='$PERF_JVM_DIAGNOSTICS' — must be standard, gc or deep" >&2; exit 1 ;;
esac
# k6's NUMA node relative to the SUT's: other (default), or same for the same-socket interference
# A/B, which is never baseline-eligible. The multi-k6 arm and the hardware matrix read it too.
K6_NUMA_NODE="${PERF_K6_NUMA_NODE:-other}"
case "$K6_NUMA_NODE" in other|same) ;; *) echo "ERROR: PERF_K6_NUMA_NODE='$K6_NUMA_NODE' must be other or same" >&2; exit 1 ;; esac
# PERF_SERVER_JAVA_OPTS reaches every start_mockserver container, the upstream included, but only the
# diag SUTs mount /diag, so a /diag file target there stops the upstream JVM at start.
if [[ "${PERF_SERVER_JAVA_OPTS:-}" == *"/diag/"* ]]; then
  echo "ERROR: PERF_SERVER_JAVA_OPTS writes under /diag/, which only the diagnostics SUTs mount — the upstream JVM would exit at start. For a GC file log use PERF_JVM_DIAGNOSTICS=gc (or deep); for GC logging on every container use the stdout form, e.g. '-Xlog:async -Xlog:gc*:stdout:time,uptime,level,tags'." >&2
  exit 1
fi
PERF_STEADY_DURATION="${PERF_STEADY_DURATION:-5m}"
PERF_STEADY_WARMUP="${PERF_STEADY_WARMUP:-30s}"
if [ "$PERF_NETWORK_MODE" = host ]; then
  echo "--- PERF_NETWORK_MODE=host — primary path only (regression, sweep, growth, steady); multi-SUT secondary phases skipped"
  PERF_INFO_ARM=false; PERF_STREAMING=false; PERF_CLUSTERED=false; PERF_PROXY_PROFILE=false; PERF_PIPELINING=false
  # shellcheck disable=SC2034  # read by lib/perf-path-coverage.sh, sourced below
  PERF_COVERAGE=false
fi

RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$"
NETWORK="mockserver-perf-${RUN_ID}"
SERVER="mockserver-perf-${RUN_ID}"
UPSTREAM="mockserver-upstream-${RUN_ID}"
# Clients reach the upstream by this alias, never by $UPSTREAM: once the agent's PID has 7
# digits the container name is 64 characters, over the 63-character DNS label limit, so
# Docker's embedded DNS cannot resolve it (curl exit 6).
UPSTREAM_ALIAS="mockserver-upstream"
# Refuses a hostname (host or host:port) that cannot fit one DNS label.
require_dns_hostname() {
  local host="${1%:*}"
  if [ "${#host}" -gt 63 ]; then
    echo "ERROR: hostname '$host' is ${#host} characters. A DNS label caps at 63 (RFC 1035), so" >&2
    echo "       Docker's embedded DNS cannot resolve it. Reach containers by --network-alias, not by name." >&2
    return 1
  fi
}
# The background k6 that drives the streaming concurrency load, and a
# DEDICATED, deliberately CONSTRAINED SUT it drives (low CPU + a small
# action-handler pool) so the scheduler saturates at a modest, DETERMINISTIC
# concurrency regardless of the agent's core count — otherwise on the pinned
# 6-core CI SUT the match A/B never leaves ~1.0 and the tripwire is placed where
# nothing happens (see the streaming phase's header for the full rationale).
STREAM_K6="mockserver-perf-k6-stream-${RUN_ID}"
STREAM_SUT="mockserver-perf-stream-sut-${RUN_ID}"
# Clustered state under load (within-run A/B). A single in-memory-backend
# control SUT and a two-node Infinispan/JGroups cluster, all on the SAME clustered
# image so the ONLY variable is the state backend; regression.js (unchanged) runs
# against each and the metric is the per-arm ratio (clustered / control), the
# CandidateIndexBenchmark within-run A/B that cancels host/JVM/image noise.
CLU_CTRL="mockserver-perf-clu-ctrl-${RUN_ID}"
CLU_A="mockserver-perf-clu-a-${RUN_ID}"
CLU_B="mockserver-perf-clu-b-${RUN_ID}"
# Short, DNS-resolvable aliases for JGroups discovery - see start_clu.
CLU_A_ALIAS="clu-a"
CLU_B_ALIAS="clu-b"
# The in-order HTTP/1.1 pipelining A/B SUTs: ${PL_SUT_PREFIX}-on and ${PL_SUT_PREFIX}-off.
PL_SUT_PREFIX="mockserver-perf-pl-${RUN_ID}"
# shellcheck source=lib/perf-pipelining-ab.sh
. "$SCRIPT_DIR/lib/perf-pipelining-ab.sh"
# The FILE-body A/B SUT ($FB_SUT) and its phase (fb_ab_phase).
# shellcheck source=lib/perf-file-body-ab.sh
. "$SCRIPT_DIR/lib/perf-file-body-ab.sh"
# The opt-in data-plane hooks A/B SUTs (${DP_SUT_PREFIX}-none, -h1, -h2) and its phase (dp_ab_phase).
# shellcheck source=lib/perf-dataplane-ab.sh
. "$SCRIPT_DIR/lib/perf-dataplane-ab.sh"
# shellcheck source=lib/perf-clustered-ab.sh
. "$SCRIPT_DIR/lib/perf-clustered-ab.sh"
# shellcheck source=lib/perf-path-coverage.sh
. "$SCRIPT_DIR/lib/perf-path-coverage.sh"
cov_validate_env || exit 1

# The clustered image is a MUTABLE snapshot tag published by the SAME job that
# publishes the SUT image (java-docker-push-snapshot.sh), from the same shaded jar
# and the same SOURCE_COMMIT stamp. Resolved HERE, next to MOCKSERVER_IMAGE, rather
# than inside the clustered block ~1700 lines below, because it is pulled UP FRONT
# with the SUT image (see the pull block): pulling both within seconds of each other
# is what keeps the two revision labels paired — a pull deferred to the clustered
# phase ~40 min into the run could pick up a NEWER master snapshot than the SUT
# being measured. Locally, point this at the container-tests
# `mockserver/mockserver:integration_testing_clustered` image and set
# PERF_CLUSTERED_REQUIRE_MATCHING_REVISION=false (that image carries no revision
# label), or set PERF_CLUSTERED=false to skip the A/B entirely.
CLU_IMAGE="${PERF_CLUSTERED_IMAGE:-mockserver/mockserver:mockserver-snapshot-clustered}"
SAMPLE_INTERVAL="${PERF_SAMPLE_INTERVAL:-5}"
# Hard memory bound for the SUT, so growth is measured against a realistic heap.
# Unbounded on a 32 GB box, the GraalJS image's MaxRAMPercentage=45 yields a ~14 GB heap that barely
# GCs, so a slow leak is invisible and the "live set" is unobservable. A bounded
# heap that actually cycles is what the documented central-deployment guidance
# runs, and is what makes the saw-tooth floor (see the live-set ratio below) mean
# something. Applied to the SUT only, never the upstream. Overridable for a re-run.
SERVER_MEMORY="${PERF_SERVER_MEMORY:-2g}"

# --- event-log body-byte budget: the OOM guard that keeps the run alive ---------
# The event log is COUNT-bounded and holds full bodies, so the MB-scale regression arms
# retain rate x residence x body, and residence (maxLogEntries / achieved rps) grows
# without bound as the SUT slows. No per-arm rate keeps that bounded (a run once OOMed
# mid-pass this way); arithmetic in docs/code/performance-measurement.md, "k6 load-shape sizing".
# THE FIX: bound RETENTION, not rate. maxEventLogSizeInBytes is MockServer's own OOM
# guard, on by default (heap/20 at ERROR): it evicts oldest-first by body bytes, so no rate x
# residence product can run away. The main SUT runs at that shipped default (main_els_guard
# confirms it from the gauge); PERF_MAX_EVENT_LOG_BYTES overrides it and marks the run tuned.
# The upstream, path-coverage and clustered SUTs keep HARNESS_FIXED_EVENT_LOG_BYTES (none
# measures the out-of-the-box figure). growth.js must fill the ~115.5k count ring, which
# a heap-derived byte cap stops first, so its phase runs at GROWTH_EVENT_LOG_BYTES (scope_growth_event_log_budget).
PERF_MAX_EVENT_LOG_BYTES_SET="${PERF_MAX_EVENT_LOG_BYTES:+set}"
PERF_MAX_EVENT_LOG_BYTES="${PERF_MAX_EVENT_LOG_BYTES:-}"
if [ -n "$PERF_MAX_EVENT_LOG_BYTES" ] && ! [[ "$PERF_MAX_EVENT_LOG_BYTES" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: PERF_MAX_EVENT_LOG_BYTES='$PERF_MAX_EVENT_LOG_BYTES' is not a positive integer (bytes); unset it for the shipped default" >&2
  exit 1
fi
# A release may predate the budget gauges and the runtime-configuration fix, so a release comparison
# starts the main SUT at the harness budget unless PERF_MAX_EVENT_LOG_BYTES says otherwise.
if [ -n "${PERF_RELEASE_COMPARISON:-}" ] && [ -z "$PERF_MAX_EVENT_LOG_BYTES" ]; then PERF_MAX_EVENT_LOG_BYTES=268435456; fi
HARNESS_FIXED_EVENT_LOG_BYTES="${PERF_MAX_EVENT_LOG_BYTES:-268435456}" # 256 MiB
GROWTH_EVENT_LOG_BYTES="$HARNESS_FIXED_EVENT_LOG_BYTES"
# The INFO SUT gets no budget (the shipped heap/20 default) unless this is set; setting it marks
# the run tuned. PERF_MAX_EVENT_LOG_BYTES deliberately never reaches the INFO SUT.
PERF_INFO_MAX_EVENT_LOG_BYTES="${PERF_INFO_MAX_EVENT_LOG_BYTES:-}"
if [ -n "$PERF_INFO_MAX_EVENT_LOG_BYTES" ] && ! [[ "$PERF_INFO_MAX_EVENT_LOG_BYTES" =~ ^[1-9][0-9]*$ ]]; then
  echo "ERROR: PERF_INFO_MAX_EVENT_LOG_BYTES='$PERF_INFO_MAX_EVENT_LOG_BYTES' is not a positive integer (bytes); unset it for the shipped default" >&2
  exit 1
fi

# Accept-queue depth for the SUT. Unset by default so the headline figure describes the
# shipped configuration; setting it labels the result config_profile "tuned".
PERF_SO_BACKLOG="${PERF_SO_BACKLOG:-}"

# --- large-heap profile: measure the event log against a big heap + a low-pause GC ---------------
# A large-heap run is expressed entirely through the levers that already exist: PERF_SERVER_MEMORY
# (container --memory and hence the MaxRAMPercentage heap), PERF_SERVER_JAVA_OPTS (JAVA_TOOL_OPTIONS
# for every start_mockserver container — the main/INFO/path-coverage SUTs AND the upstreams; not the
# stream, clustered, handshake or percore/hw-matrix SUTs — so carry the GC here, e.g. -XX:+UseZGC,
# and an explicit -Xmx/-XX:MaxRAMPercentage if wanted, but never a /diag file target: for a SUT-only
# GC log use PERF_JVM_DIAGNOSTICS=gc), and PERF_MAX_EVENT_LOG_BYTES (the event-log byte budget).
# Each of these now flips config_profile to "tuned" on its own (see the trigger below), so such a
# run is never baselined.
# PERF_LARGE_HEAP_PROFILE=true additionally turns the event-log proportionality assertion into a
# fail-closed gate (see the event-log scaling block near result assembly): it is the operator's
# declaration that this run exists to prove the log scaled with the heap, so a run that leaves the
# byte budget un-scaled, or never fills the log, must RED rather than pass silently.
PERF_LARGE_HEAP_PROFILE="${PERF_LARGE_HEAP_PROFILE:-false}"
# Fraction of a resolved event-log bound the observed peak must reach for that bound to count as
# "exercised". Below this on BOTH bounds (and with no evictions) the workload never filled the log.
PERF_EVENT_LOG_APPROACH_RATIO="${PERF_EVENT_LOG_APPROACH_RATIO:-0.90}"

OUT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-result.XXXXXX")"
# The k6 image runs as a NON-root user (uid 12345); mktemp -d creates the dir
# 0700 owned by the agent user, so k6's handleSummary() can't write its result
# JSONs into the `/out` bind mount ("permission denied"). World-write the shared
# output dir so the unprivileged container user can write its artifacts. Only the
# k6 result files land here (no secrets), and the dir is per-run + cleaned up.
chmod 0777 "$OUT_DIR"
RESULT_JSON="$OUT_DIR/result.json"
SAMPLE_LOG="$OUT_DIR/samples.csv"

# --- JVM-internals diagnostics (builds 261/264 died with the SUT vanishing under --rm) ----------
# Two tiers, chosen so the tracked baseline is never silently shifted (the log-level lesson):
#
#   TIER 1 — ALWAYS ON, and genuinely near-zero so the tracked baseline stays comparable with
#   months of stored S3 history measured WITHOUT it. Only flags that are INERT until an
#   OutOfMemoryError fires — nothing runs on a healthy request path, so the baseline cannot be
#   silently offset. Everything lands on a HOST-mounted volume ($DIAG_DIR -> /diag) so it survives
#   the container's death, plus host-side capture of the container's post-mortem (docker inspect
#   .State / docker logs) which is the piece --rm destroyed.
#     -XX:+ExitOnOutOfMemoryError  a JVM OOM is then unambiguous + immediate (no half-dead server)
#     -XX:+HeapDumpOnOutOfMemoryError + -XX:HeapDumpPath=/diag  a heap dump that OUTLIVES the container
#     (host-side, no JVM flag) dense sampler + docker inspect .State + streamed docker logs
#   GC FILE LOGGING is NOT tier 1. -Xlog:gc* is always-on and writes a line per GC event to a bind
#   mount; its cost scales with GC frequency (highest on the large-body arms this proof did not
#   exercise) and a single within-noise measurement cannot show it sub-1% — the honest upper bound
#   is ~2%, enough to permanently offset a baseline series compared against months of history. That
#   is the ONE outcome this design must protect against, so GC logging is TIER 2. The coarse heap/RSS
#   trajectory is still captured tier-1 by the host-side sampler at zero SUT cost.
#   NATIVE-vs-HEAP discrimination in tier 1 is done with FREE signals, NOT NMT: the sampler records
#   the CONTAINER RSS (docker stats, host-side) beside the JVM heap, and the failure path records
#   docker inspect .State.OOMKilled. OOMKilled=true with heap headroom => native/cgroup kill (Netty
#   direct buffers a heap dump cannot see); a JVM OutOfMemoryError in the log => heap. NMT itself is
#   documented at 5-10% overhead, so it too would shift the baseline and is TIER 2, not tier 1.
#
#   TIER 2 — OPT-IN via PERF_JVM_DIAGNOSTICS=deep, for an INVESTIGATION run only. Costs throughput,
#   so it MUST be off for the run that writes the baseline (the default 'standard' leaves it off).
#     -Xlog:gc*                    the FINE per-GC-event trajectory to /diag/<sut>/gc-1.log (see the
#                                   heap approach the cliff) — moved here from tier 1, see above
#     JFR profile recording        allocation + CPU-hotspot profiling, chunks in /diag/<sut>/jfr-repo,
#                                   dumped over the load window to /diag/<sut>/load.jfr
#                                   (survive even a hard OOM exit), ~2-8% depending on event settings
#     -XX:NativeMemoryTracking=summary + -XX:+PrintNMTStatistics  precise native breakdown printed to
#                                   stdout at JVM exit (captured by the docker-logs follower), ~5-10%
#     live-heap class histogram     periodic jcmd GC.class_histogram from a JDK sidecar sharing the
#                                   SUT PID namespace -> /diag/<sut>/live-heap-histogram.txt, taken
#                                   only during growth.js, never inside a latency-measuring phase.
#                                   This is the ONLY live-set attribution available here: JFR's
#                                   retention views are empty under ZGC (see live_heap_histo_sampler).
#     ceiling JFR window            the sweep rungs from the knee to the top, cut to /diag/sut/ceiling.jfr
#
#   PERF_JVM_DIAGNOSTICS=gc — tier 1 plus ONLY tier 2's -Xlog:gc* file log, for a run that needs the
#   main SUT's GC trajectory without NMT/JFR. Like deep, it is never baselined.
# PERF_JVM_DIAGNOSTICS is defaulted and validated with the other env checks near the top.
PERF_DIAG_SAMPLE_INTERVAL="${PERF_DIAG_SAMPLE_INTERVAL:-2}" # dense enough to see the cliff APPROACH, not just its aftermath
# Heap dumps are large (a dump can approach the 0.9 GiB heap); upload only when gzipped size is within this
# cap so a diagnostics run never tries to push a multi-GB artifact. The raw dump always stays on the
# volume/artifact-of-last-resort logic below decides upload.
PERF_HEAPDUMP_MAX_UPLOAD_MB="${PERF_HEAPDUMP_MAX_UPLOAD_MB:-512}"
# The dump is too big to upload but a CLASS HISTOGRAM of it is kilobytes and NAMES the retaining class
# — the "what was retained?" answer, not just "heap ran out". The shipped SUT image is distroless with
# no jcmd, and jmap/jhsdb cannot read an .hprof FILE anyway (live pid / core only; jhat was removed in
# JDK 9), so a throwaway JDK sidecar runs a single-pass streaming parser (.buildkite/scripts/lib/
# HprofHisto.java) with /diag mounted. It is memory-bounded (proven: -Xmx256m parses a 2.84 GB dump)
# and self-bounds on a wall-clock deadline so a huge dump can never hang the step. Runs ONLY when a
# heap dump exists (i.e. an OOM already happened), so it costs a healthy run nothing — tier 1.
PERF_HISTO_ENABLED="${PERF_HISTO_ENABLED:-true}"
PERF_HISTO_JDK_IMAGE="${PERF_HISTO_JDK_IMAGE:-eclipse-temurin:21-jdk}"
PERF_HISTO_TOPN="${PERF_HISTO_TOPN:-40}"
# shellcheck source=lib/perf-jfr-image.sh
. "$SCRIPT_DIR/lib/perf-jfr-image.sh"
PERF_HISTO_DEADLINE_S="${PERF_HISTO_DEADLINE_S:-90}" # linear read of a 2.4 GB dump is ~1-2s locally; 90s is huge headroom, and a timeout yields a TRUNCATED top-N rather than nothing
# Live-heap class histogram (deep runs only) — see live_heap_histo_sampler(). Each sample is a
# stop-the-world heap inspection, so it runs only inside LIVE_HISTO_WINDOW.
PERF_LIVE_HISTO_INTERVAL_S="${PERF_LIVE_HISTO_INTERVAL_S:-45}"
PERF_LIVE_HISTO_TOPN="${PERF_LIVE_HISTO_TOPN:-25}"
DIAG_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-diag.XXXXXX")"
# The SUT container's JVM (non-root user in the shipped image) writes gc.log / heap dump / JFR here,
# same reason $OUT_DIR is world-writable for k6. Only diagnostics land here (no secrets).
chmod 0777 "$DIAG_DIR"
mkdir -p "$DIAG_DIR/sut" "$DIAG_DIR/info" && chmod 0777 "$DIAG_DIR/sut" "$DIAG_DIR/info"
DIAG_SAMPLE_LOG="$DIAG_DIR/diag-samples.csv"
# SUT liveness gate. The sampler writes SUT_DEATH_SENTINEL once the SUT container is
# gone; ACTIVE_LOAD_FILE names the in-flight load container so it can be stopped on
# death. Both are files so the backgrounded sampler sees post-fork updates.
SUT_DEATH_SENTINEL="$DIAG_DIR/sut-death.json"
ACTIVE_LOAD_FILE="$DIAG_DIR/active-load-container"
: > "$ACTIVE_LOAD_FILE"

# Tier-1 JVM opts, written to a per-SUT /diag subdir. $1 = subdir name under /diag (sut|info).
# ONLY flags that are genuinely INERT until an OutOfMemoryError fires — no continuous cost on a
# healthy run, so the tracked baseline is not shifted. GC FILE LOGGING is deliberately NOT here: it
# is always-on and writes a line per GC event to a host bind mount, whose cost scales with GC
# frequency (highest on the large-body arms) and cannot be shown sub-threshold by a single
# within-noise measurement — the honest upper bound is ~2%, enough to silently offset a baseline
# series compared against months of history. GC logging therefore lives in tier 2 (below). The
# tier-1 host-side sampler still records the coarse heap/RSS trajectory at zero SUT cost.
tier1_jvm_opts() {
  local sub="$1"
  printf -- '-XX:+ExitOnOutOfMemoryError -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/diag/%s/heapdump.hprof' "$sub"
}
# Tier-2 JVM opts (PERF_JVM_DIAGNOSTICS=deep; =gc takes only the GC log). Adds the throughput-costing
# diagnostics an INVESTIGATION run wants: the per-GC-event trajectory to a file (the fine-grained approach-to-the-cliff
# evidence), NMT, and JFR. JFR repository on the mounted volume so the chunk files survive a hard
# ExitOnOutOfMemoryError exit. No filename/dumponexit: the SUT never exits gracefully (SIGKILL or
# os::_exit), and on images older than the static /mockserver-healthcheck probe the HEALTHCHECK JVM
# inherits JAVA_TOOL_OPTIONS, so a named dump file could hold a sub-second health-check recording.
# dump_load_window_jfr() captures the SUT's. The GC log is per-PID (%p) for the same reason (an older
# image's health-check JVM would rotate a shared log away). The SUT is pid 1, so its log is gc-1.log.
gc_log_jvm_opts() {
  printf -- '-Xlog:gc*,gc+heap=info:file=/diag/%s/gc-%%p.log:time,uptime,level,tags:filecount=5,filesize=20m' "$1"
}
tier2_jvm_opts() {
  local sub="$1"
  # -XX:+UnlockDiagnosticVMOptions MUST precede PrintNMTStatistics (it is a diagnostic flag; the JVM
  # refuses to start otherwise — caught by the local OOM proof). jdk.CPUTimeSample is JDK 25+ on
  # Linux; JDK 17 and 21 ignore an unknown event name here and start normally.
  printf -- '%s -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -XX:StartFlightRecording=name=perfdiag,settings=profile,maxsize=256m,jdk.JavaMonitorEnter#threshold=1ms,jdk.CPUTimeSample#enabled=true -XX:FlightRecorderOptions=repository=/diag/%s/jfr-repo,stackdepth=128' "$(gc_log_jvm_opts "$sub")" "$sub"
}
# Combined diagnostics JVM opts for a diag SUT ($1 = /diag subdir), honouring the tier flag. Only
# start_mockserver's diag_subdir callers get these, and only they mount /diag.
diag_jvm_opts() {
  local sub="$1" opts
  opts="$(tier1_jvm_opts "$sub")"
  case "$PERF_JVM_DIAGNOSTICS" in
    gc) opts="$opts $(gc_log_jvm_opts "$sub")" ;;
    deep) opts="$opts $(tier2_jvm_opts "$sub")" ;;
  esac
  printf '%s' "$opts"
}

# image_java_tool_options / compose_java_tool_options (shared with lib/perf-percore.sh).
# shellcheck source=lib/perf-java-opts.sh
. "$SCRIPT_DIR/lib/perf-java-opts.sh"

# File-backed response body arm. Generate a ~1 MB JSON file on the host
# and mount it read-only into the SUT so a FILE-body expectation can serve it (the
# FileBodyMaterialiser path). World-readable so the container's non-root user can
# read it. Only the SUT gets the mount; the upstream does not need it.
FILE_BODY_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-filebody.XXXXXX")"
chmod 0755 "$FILE_BODY_DIR"
FILE_BODY_HOST_PATH="$FILE_BODY_DIR/large-file-body.json"
awk 'BEGIN{
  printf "{\"marker\":\"large-file\",\"filler\":[";
  n=62000; # ~1 MB of fixed-width quoted tokens (17 bytes each)
  for(i=0;i<n;i++){ if(i)printf ","; printf "\"item-%09d\"", i }
  printf "]}"
}' > "$FILE_BODY_HOST_PATH"
chmod 0644 "$FILE_BODY_HOST_PATH"
FILE_BODY_MOUNT="$FILE_BODY_DIR:/perf-files:ro"
echo "--- file-backed body: $(wc -c < "$FILE_BODY_HOST_PATH") bytes -> ${FILE_BODY_CONTAINER_PATH} (SUT mount)"

SAMPLER_PID=""
SWEEP_SAMPLER_PID=""
DIAG_SAMPLER_PID=""   # dense resource-trajectory sampler (JVM-internals diagnostics)
LIVE_HISTO_PID=""     # live-heap class histogram sampler (deep runs only)
SUT_LOG_PID=""        # docker-logs follower for the main SUT
INFO_LOG_PID=""       # docker-logs follower for the INFO SUT
INFO_ELS_SAMPLER_PID="" # event-log occupancy sampler for the INFO SUT
SUT_DIAG_CAPTURED=""  # one-shot guard so the post-mortem capture runs at most once
SWEEP_K6="k6-sweep-${RUN_ID}"
# Plan open question 5 — the INFO-log-level PUBLICATION arm. The tracked, gated
# baseline is measured at MOCKSERVER_LOG_LEVEL=ERROR (start_mockserver's default)
# and MUST NOT move — every historical S3 run is ERROR, so switching it would break
# comparability. This arm ADDS a second SUT at the SHIPPED-DEFAULT log level (INFO)
# and re-measures ONLY the two PUBLISHED figure families against it — the knee curve
# (sweep.js) and the per-behaviour percentiles (regression.js) — so the site can show
# an honest "out of the box" number ALONGSIDE the (legitimate, but labelled) ERROR
# one. Its output lands under DISTINCT top-level keys (info_log_level_arm.*), never
# under .behaviours / .sweep / rig_valid_peak_achieved_rps, so an INFO number can never be
# confused with, or diffed against, the ERROR baseline series. Set PERF_INFO_ARM=false
# to skip it if the perf run is time-pressed — it is a pure add-on and
# nothing else in the run depends on it.
INFO_SERVER="mockserver-perf-info-${RUN_ID}"
INFO_SERVER_ALIAS="mockserver-info"
INFO_SWEEP_K6="k6-info-sweep-${RUN_ID}"
PERF_INFO_ARM="${PERF_INFO_ARM:-true}"
# After the INFO sweep (which then leaves its log in place), a live-heap histogram before and after a
# log reset records what the retained log costs in heap: info_log_level_arm.live_heap_reset_probe.
# Notify-only, bounded by PERF_INFO_HEAP_PROBE_DEADLINE_S; false restores the sweep's own reset.
PERF_INFO_HEAP_PROBE="${PERF_INFO_HEAP_PROBE:-true}"
PERF_INFO_HEAP_PROBE_DEADLINE_S="${PERF_INFO_HEAP_PROBE_DEADLINE_S:-240}"
# shellcheck source=lib/perf-info-heap-probe.sh
. "$SCRIPT_DIR/lib/perf-info-heap-probe.sh"
# Upload the surviving JVM-internals diagnostics as Buildkite artifacts. Small evidence (GC log,
# the dense resource-trajectory CSV, the followed server logs, docker-inspect .State, any tier-2 NMT
# print + JFR chunks) always goes up as one tarball. Heap dumps are handled separately and SIZE-CAPPED
# (a dump can approach the 0.9 GiB heap): each is gzipped and uploaded only if within PERF_HEAPDUMP_MAX_UPLOAD_MB,
# otherwise it is left named in the log with its size rather than pushing a multi-GB artifact. Safe to
# call more than once (guarded by a marker file). No-op when buildkite-agent is absent (local runs keep
# the evidence in $DIAG_DIR on disk).
# Derive a class histogram from a heap dump on the /diag volume and write it beside the dump (rides
# in the always-uploaded bundle). Distroless SUT => no in-image jcmd, and jmap/jhsdb cannot read an
# .hprof file, so run the streaming parser in a throwaway JDK sidecar with /diag mounted, -Xmx256m
# (memory-bounded), self-bounded on a deadline. $1 = raw hprof path, $2 = /diag subdir (sut|info).
extract_heap_histogram() {
  local hprof="$1" sub="$2"
  local out="$DIAG_DIR/$sub/heap-histogram.txt"
  local parser="$REPO_ROOT/.buildkite/scripts/lib/HprofHisto.java"
  [ "$PERF_HISTO_ENABLED" = "true" ] || { echo "--- heap-histogram extraction disabled (PERF_HISTO_ENABLED=$PERF_HISTO_ENABLED)" >&2; return 0; }
  command -v docker >/dev/null 2>&1 || { echo "WARNING: docker unavailable — cannot extract heap histogram for $sub" >&2; return 0; }
  [ -f "$parser" ] || { echo "WARNING: histogram parser missing ($parser) — skipping $sub histogram" >&2; return 0; }
  echo "--- extracting class histogram from the $sub heap dump ($PERF_HISTO_JDK_IMAGE sidecar, -Xmx256m, deadline ${PERF_HISTO_DEADLINE_S}s)" >&2
  if docker run --rm \
       -v "$DIAG_DIR:/diag" \
       -v "$parser:/HprofHisto.java:ro" \
       "$PERF_HISTO_JDK_IMAGE" \
       java -Xmx256m /HprofHisto.java "/diag/$sub/heapdump.hprof" "$PERF_HISTO_TOPN" "$PERF_HISTO_DEADLINE_S" \
       > "$out" 2>"$out.err"; then
    echo "--- heap histogram ($sub) — top retaining classes (full list in the uploaded bundle):" >&2
    head -14 "$out" >&2
  else
    echo "WARNING: heap-histogram extraction failed for $sub (see $sub/heap-histogram.txt.err in the bundle); the raw dump remains on the volume" >&2
    head -5 "$out.err" >&2 2>/dev/null || true
  fi
}

sut_jvm_uid_gid() { # container -> "uid gid" of its pid-1 JVM (empty on failure)
  docker run --rm --pid="container:$1" --user 0:0 "$PERF_HISTO_JDK_IMAGE" \
    sh -c 'awk "/^Uid:/{u=\$2} /^Gid:/{g=\$2} END{print u, g}" /proc/1/status' 2>/dev/null || true
}

# Deep runs only. Dumps the SUT's own JFR recording, from the first load phase to now, to
# /diag/sut/load.jfr through a jcmd sidecar in its PID namespace (the SUT JVM is pid 1; attach needs
# its exact uid), and records the window in load-window.json for the allocation-per-request figure.
dump_load_window_jfr() {
  [ "$PERF_JVM_DIAGNOSTICS" = "deep" ] && [ -n "${LOAD_WINDOW_START_EPOCH:-}" ] || return 0
  [ -f "$DIAG_DIR/load-window.json" ] && return 0
  local uid gid out="$DIAG_DIR/sut/load.jfr" err="" end_epoch jfr_rel=
  end_epoch="$(date -u +%s)"
  if [ "$(docker inspect --format '{{.State.Running}}' "$SERVER" 2>/dev/null || echo false)" != "true" ]; then
    err="SUT not running at dump time"
  else
    read -r uid gid <<<"$(sut_jvm_uid_gid "$SERVER")"
    if ! grep -qE '^[0-9]+$' <<<"$uid"; then
      err="could not resolve the SUT JVM uid"
    else
      local deadline="${LOAD_JFR_DUMP_DEADLINE_S:-180}" rc=0 jcmd_out
      jcmd_out="$(timeout "$deadline" docker run --rm --pid="container:$SERVER" --user "${uid}:${gid}" "$PERF_HISTO_JDK_IMAGE" \
                  jcmd 1 JFR.dump name=perfdiag filename=/diag/sut/load.jfr begin="$LOAD_WINDOW_START_ISO" 2>&1)" || rc=$?
      if [ "$rc" -eq 124 ]; then
        err="jcmd JFR.dump timed out after ${deadline}s"
      elif [ "$rc" -ne 0 ]; then
        err="jcmd JFR.dump failed (exit $rc): $(printf '%s' "$jcmd_out" | tail -2 | tr '\n' ' ')"
      elif [ ! -s "$out" ]; then
        err="jcmd JFR.dump reported success but wrote no file: $(printf '%s' "$jcmd_out" | tail -2 | tr '\n' ' ')"
      fi
    fi
  fi
  if [ -n "$err" ]; then
    echo "WARNING: no load-window JFR recording — $err" >&2
    # A timed-out dump can leave a truncated file; keep it as evidence but never as the recording.
    [ -e "$out" ] && mv -f "$out" "$DIAG_DIR/sut/load.partial.jfr" 2>/dev/null || true
  else
    jfr_rel="sut/load.jfr"
  fi
  jq -n --argjson start "$LOAD_WINDOW_START_EPOCH" --argjson end "$end_epoch" --arg start_iso "$LOAD_WINDOW_START_ISO" \
    --arg jfr "$jfr_rel" --arg error "$err" \
    '{start_epoch:$start, end_epoch:$end, start_iso:$start_iso, jfr:(if $jfr == "" then null else $jfr end), error:(if $error == "" then null else $error end)}' \
    > "$DIAG_DIR/load-window.json" 2>/dev/null || true
  [ -n "$jfr_rel" ] && echo "--- load-window JFR recording: sut/load.jfr ($(( $(wc -c < "$out") / 1048576 )) MiB, from $LOAD_WINDOW_START_ISO)" >&2
  return 0
}

# GNU tar exits 1 when a file changed while it was being read — the live SUT's gc.log and JFR chunk,
# or (on older images) a health-check JVM's short-lived JFR repository. The archive is still complete, so rc 1 is
# accepted once the archive verifies; any other failure is reported, never swallowed.
package_diag_bundle() { # out_path -> 0 when a verified archive was written
  local out="$1" rc=0 err excludes=(--exclude='*.hprof' --exclude='*.hprof.gz')
  # A successful load-window dump already holds the repository chunks that matter, and the live
  # repository is the main source of mid-read changes. Without one the repository is the fallback.
  [ "$(jq -r '.jfr // empty' "$DIAG_DIR/load-window.json" 2>/dev/null)" = "sut/load.jfr" ] \
    && excludes+=(--exclude='./sut/jfr-repo')
  # Per-PID GC logs other than pid 1 (the SUT) belong to an older image's health-check JVMs.
  local f
  for f in "$DIAG_DIR"/*/gc-*.log*; do
    [ -e "$f" ] || continue
    case "$(basename "$f")" in gc-1.log|gc-1.log.*) ;; *) excludes+=(--exclude="./${f#"$DIAG_DIR"/}") ;; esac
  done
  err="$(mktemp "${TMPDIR:-/tmp}/perf-diag-tar.XXXXXX")"
  ( cd "$DIAG_DIR" && tar czf "$out" "${excludes[@]}" . ) 2>"$err" || rc=$?
  if [ "$rc" -gt 1 ] || ! gzip -t "$out" 2>/dev/null || ! tar tzf "$out" >/dev/null 2>&1; then
    echo "WARNING: diagnostics bundle packaging FAILED (tar rc=$rc): $(head -3 "$err" | tr '\n' ' ')" >&2
    rm -f "$err"; return 1
  fi
  [ "$rc" -eq 1 ] && echo "--- diagnostics bundle: tar saw files change while archiving (archive verified): $(head -2 "$err" | tr '\n' ' ')" >&2
  rm -f "$err"; return 0
}

upload_diag_bundle() {
  [ -f "$DIAG_DIR/.uploaded" ] && return 0
  if ! command -v buildkite-agent >/dev/null 2>&1; then
    package_diag_bundle "$REPO_ROOT/${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz" || true
    echo "--- (local run) JVM diagnostics left in $DIAG_DIR (bundle: ${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz)" >&2
    return 0
  fi
  : > "$DIAG_DIR/.uploaded"
  # For each heap dump: FIRST derive a class histogram from the RAW .hprof (kilobytes, names the
  # retaining class — the point of this whole exercise), THEN size-cap the dump itself. Order matters:
  # the parser reads the raw file, so it must run before the gzip below. The dump is kept on the
  # volume and refused for upload when over the cap; the histogram is what travels.
  # MAKE THE DUMPS HOST-READABLE FIRST. The JVM writes them from INSIDE the SUT container as the
  # image's non-root user, so the file lands owned by that uid with no read bit for the agent user —
  # $DIAG_DIR being 0777 governs the DIRECTORY, not the files created in it. Every host-side read
  # below then fails with "Permission denied", and both failures are SILENT-but-wrong rather than
  # loud: `wc -c` falls through to `|| echo 0` so the log reports a 2 GB dump as "0 MiB", and the
  # gzip fails into `|| continue` so the dump is never uploaded at all. chmod from a throwaway container (running as root, same trick the
  # histogram sidecar already uses) before anything on the host touches them.
  if [ -n "$(ls -d "$DIAG_DIR"/*/heapdump.hprof 2>/dev/null)" ]; then
    docker run --rm -v "$DIAG_DIR:/diag" --entrypoint sh "$PERF_HISTO_JDK_IMAGE" \
      -c 'chmod -R a+r /diag 2>/dev/null; find /diag -type d -exec chmod a+rx {} + 2>/dev/null' \
      >/dev/null 2>&1 \
      || echo "WARNING: could not chmod the heap dump(s) readable — host-side sizing/upload may report 0 MiB and skip the upload" >&2
  fi
  local hprof gz szmb rawmb rawbytes sub
  for hprof in "$DIAG_DIR"/*/heapdump.hprof; do
    [ -f "$hprof" ] || continue
    sub="$(basename "$(dirname "$hprof")")"
    # Fail LOUD rather than reporting a wrong size: an unreadable dump is a diagnostics defect, not a
    # zero-byte dump, and the two must never look alike in the log.
    if ! rawbytes="$(wc -c < "$hprof" 2>/dev/null)"; then
      echo "WARNING: heap dump $hprof exists but is NOT READABLE by this user — cannot size, gzip or upload it (check container-vs-agent uid). The class histogram below is still derived inside a container and is unaffected." >&2
      rawbytes=0
    fi
    rawmb=$(( ( ${rawbytes:-0} + 1048575 ) / 1048576 ))
    extract_heap_histogram "$hprof" "$sub"
    echo "--- heap dump kept on the /diag volume: $hprof (${rawmb} MiB uncompressed) — retrievable while the agent lives" >&2
    gz="$hprof.gz"; gzip -f "$hprof" >/dev/null 2>&1 || continue
    szmb=$(( ( $(wc -c < "$gz" 2>/dev/null || echo 0) + 1048575 ) / 1048576 ))
    if [ "$szmb" -le "$PERF_HEAPDUMP_MAX_UPLOAD_MB" ]; then
      if cp "$gz" "$REPO_ROOT/${ARTIFACT_PREFIX}${sub}-heapdump.hprof.gz" \
         && buildkite-agent artifact upload "${ARTIFACT_PREFIX}${sub}-heapdump.hprof.gz"; then
        echo "--- uploaded heap dump ($sub, ${szmb} MiB gzipped)" >&2
      else
        echo "WARNING: heap dump ($sub, ${szmb} MiB gzipped) was NOT uploaded — see the errors above" >&2
      fi
    else
      echo "--- heap dump $sub is ${szmb} MiB gzipped (> ${PERF_HEAPDUMP_MAX_UPLOAD_MB} MiB cap) — NOT uploaded; the class histogram (in the bundle) names the retaining class, and the raw dump is on the volume" >&2
    fi
  done
  # Everything else (small) as one tarball. Heap dumps are EXCLUDED — they are uploaded (or not)
  # separately above under the size cap, so they must never bloat this always-uploaded bundle.
  local tgz="${ARTIFACT_PREFIX}perf-jvm-diagnostics.tgz" failure=""
  if ! package_diag_bundle "$REPO_ROOT/$tgz"; then
    failure="packaging failed"
  elif ! buildkite-agent artifact upload "$tgz"; then
    failure="buildkite-agent artifact upload failed"
  fi
  if [ -n "$failure" ]; then
    echo "WARNING: $tgz was NOT uploaded ($failure) — see the lines above" >&2
    printf '%s\n' ":warning: **JVM diagnostics bundle \`$tgz\` was not uploaded** ($failure). The measurement is unaffected, but the resource trajectory$([ "$PERF_JVM_DIAGNOSTICS" = deep ] && echo ', GC log, NMT and JFR recording')$([ "$PERF_JVM_DIAGNOSTICS" = gc ] && echo ' and GC log') is unavailable for this run; see the step log." \
      | buildkite-agent annotate --style warning --context "perf-diag-bundle-${PERF_RUN_NAME:-default}" || true
  else
    echo "--- uploaded $tgz ($(( $(wc -c < "$REPO_ROOT/$tgz") / 1024 )) KiB: resource trajectory, server logs, docker-inspect state, heap class-histogram if an OOM occurred$([ "$PERF_JVM_DIAGNOSTICS" = deep ] && echo ', gc log, NMT, load-window JFR, live-heap histogram')$([ "$PERF_JVM_DIAGNOSTICS" = gc ] && echo ', gc log'))" >&2
  fi
}

# Capture the SUT (and INFO SUT) post-mortem BEFORE the container is removed — the single piece --rm
# destroyed. docker inspect .State is the most valuable artifact: OOMKilled / ExitCode / Status / Error
# / FinishedAt distinguish a JVM OutOfMemoryError from a cgroup OOM-kill from a plain crash — which the
# k6 client side cannot tell apart. OOMKilled=true with heap headroom in the GC log => native/direct
# memory (Netty) crossed the cgroup limit BEFORE the JVM reported heap exhaustion; a JVM OutOfMemoryError
# in the followed log => heap. Best-effort throughout; runs at most once.
capture_sut_diagnostics() {
  [ -n "$SUT_DIAG_CAPTURED" ] && return 0
  SUT_DIAG_CAPTURED=1
  echo "--- capturing SUT post-mortem before container removal (JVM-internals diagnostics)" >&2
  # Stop the log followers so the *-server.log files are complete before we read/upload them.
  [ -n "$SUT_LOG_PID" ] && kill "$SUT_LOG_PID" >/dev/null 2>&1 || true; SUT_LOG_PID=""
  [ -n "$INFO_LOG_PID" ] && kill "$INFO_LOG_PID" >/dev/null 2>&1 || true; INFO_LOG_PID=""
  local pair c sub
  # The upstream is kept (no --rm) too, and cleanup() force-removes it right after this.
  for pair in "${SERVER:-}:sut" "${INFO_SERVER:-}:info" "${UPSTREAM:-}:upstream"; do
    c="${pair%%:*}"; sub="${pair##*:}"
    [ -n "$c" ] || continue
    mkdir -p "$DIAG_DIR/$sub" 2>/dev/null || true
    docker inspect --format '{{json .State}}' "$c" > "$DIAG_DIR/$sub/state.json" 2>/dev/null || true
    docker logs --tail 300 "$c" > "$DIAG_DIR/$sub/logs-tail.txt" 2>&1 || true
    # One-line verdict straight into the build log so a reader sees the diagnosis without downloading.
    if [ -s "$DIAG_DIR/$sub/state.json" ]; then
      echo "--- $c .State: $(jq -rc '{OOMKilled,ExitCode,Status,Error,FinishedAt}' "$DIAG_DIR/$sub/state.json" 2>/dev/null || cat "$DIAG_DIR/$sub/state.json")" >&2
    fi
  done
  dump_load_window_jfr
  upload_diag_bundle
}

cleanup() {
  local rc=$?
  declare -F resume_rig >/dev/null && resume_rig
  # On ANY non-zero exit (the mid-load death, an early wait_ready failure, a sampler/sweep abort),
  # grab the SUT post-mortem BEFORE docker rm -f below erases it. This is the whole point: builds
  # 261/264 died here and left nothing because --rm + this rm -f raced the investigator to the body.
  if [ "$rc" -ne 0 ]; then capture_sut_diagnostics; fi
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$SWEEP_SAMPLER_PID" ] && kill "$SWEEP_SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$DIAG_SAMPLER_PID" ] && kill "$DIAG_SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$LIVE_HISTO_PID" ] && kill "$LIVE_HISTO_PID" >/dev/null 2>&1 || true
  [ -n "$SUT_LOG_PID" ] && kill "$SUT_LOG_PID" >/dev/null 2>&1 || true
  [ -n "$INFO_LOG_PID" ] && kill "$INFO_LOG_PID" >/dev/null 2>&1 || true
  declare -F stop_info_els_sampler >/dev/null && stop_info_els_sampler
  [ -n "${HS_CPU_PID:-}" ] && kill "$HS_CPU_PID" >/dev/null 2>&1 || true
  # The handshake SUTs (deterministic names from RUN_ID) — removed here too
  # so an early exit before the proxy block's own cleanup never leaks them.
  docker rm -f "$SERVER" "$UPSTREAM" "$SWEEP_K6" "$STREAM_K6" "$STREAM_SUT" \
    "$INFO_SERVER" "$INFO_SWEEP_K6" "${STEADY_K6:-}" \
    "$CLU_CTRL" "$CLU_A" "$CLU_B" "$PL_SUT_PREFIX-on" "$PL_SUT_PREFIX-off" \
    "mockserver-mtls-${RUN_ID}" "mockserver-jdk-${RUN_ID}" >/dev/null 2>&1 || true
  cov_cleanup
  declare -F fb_ab_cleanup >/dev/null && fb_ab_cleanup
  declare -F dp_ab_cleanup >/dev/null && dp_ab_cleanup
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  [ -n "${FILE_BODY_DIR:-}" ] && rm -rf "$FILE_BODY_DIR" >/dev/null 2>&1 || true
  [ -n "${HS_CERT_DIR:-}" ] && rm -rf "$HS_CERT_DIR" >/dev/null 2>&1 || true
}
# EXIT alone does NOT fire on SIGTERM/SIGINT — and Spot reclamation + Buildkite job cancellation send
# SIGTERM before SIGKILL. On exactly those abnormal paths (the ones that motivated dropping --rm) the
# EXIT trap would never run, so cleanup()'s docker rm -f would not run and the non-rm SUT would linger.
# Trap the signals too so the post-mortem capture + removal still happen. (A SIGKILL still bypasses
# everything — hence the startup sweep below reaps any container a prior killed run left behind.)
trap cleanup EXIT
# On cancellation Buildkite SIGKILLs after a grace period, so the post-mortem JFR dump gets a short deadline.
trap 'LOAD_JFR_DUMP_DEADLINE_S=20 cleanup' INT TERM
# Reap any leftover perf SUTs from a previously-cancelled/killed run before we start — otherwise a
# lingering non-rm container from a SIGKILLed run (or a name collision) would wedge this one. A killed
# perf-test-info-investigation.sh job can leave load containers labelled with this agent's name too.
# shellcheck disable=SC2046  # intentional word-splitting of the id list
docker rm -f $(docker ps -aq --filter name=mockserver-perf- 2>/dev/null) \
  $(docker ps -aq --filter "label=mockserver-perf-inv.agent=${BUILDKITE_AGENT_NAME:-$(hostname 2>/dev/null || echo local)}" 2>/dev/null) >/dev/null 2>&1 || true

# --- validity accumulation (a validity block on every result) -----------------
# Each measurement phase appends a check {name, ok, detail}; the assembled result
# carries a `validity` object, and perf-test-compare.sh REFUSES to baseline a run
# whose validity is absent or false — rather than silently comparing a compromised
# measurement (the inject harness's discipline, generalised: exclude a bad point,
# don't report it).
VALIDITY_CHECKS=()
add_check() { # name  ok(true|false)  detail
  VALIDITY_CHECKS+=("$(jq -nc --arg n "$1" --argjson ok "$2" --arg d "$3" '{name:$n, ok:$ok, detail:$d}')")
}

# Validity block plus run identity only: a dead SUT measured nothing, so metric blocks
# are omitted rather than emitted as misleading nulls. Field names match the success-path
# result so both producers share one schema.
emit_invalid_result() {
  local validity_json
  if [ "${#VALIDITY_CHECKS[@]}" -gt 0 ]; then
    validity_json="$(printf '%s\n' "${VALIDITY_CHECKS[@]}" | jq -sc '{valid: (map(.ok) | all), checks: .}')"
  else
    validity_json='{"valid":false,"checks":[]}'
  fi
  jq -n \
    --arg commit "${COMMIT:-}" --arg harness_commit "${HARNESS_COMMIT:-}" \
    --arg branch "${BRANCH:-}" --arg ts "${TS:-}" \
    --arg build_number "${BUILDKITE_BUILD_NUMBER:-}" --arg build_url "${BUILDKITE_BUILD_URL:-}" \
    --arg image "${MOCKSERVER_IMAGE:-}" --argjson validity "$validity_json" \
    '{schema_version:3, commit:$commit, harness_commit:$harness_commit, branch:$branch,
      timestamp_utc:$ts, build_number:$build_number, build_url:$build_url,
      mockserver_image:$image,
      aborted:"sut_died", baseline_eligible:false, baseline_ineligible_reasons:["sut_died"], validity:$validity}' \
    > "$REPO_ROOT/${ARTIFACT_PREFIX}perf-result.json" 2>/dev/null || true
  command -v buildkite-agent >/dev/null 2>&1 \
    && buildkite-agent artifact upload "${ARTIFACT_PREFIX}perf-result.json" >/dev/null 2>&1 || true
}

# Liveness checkpoint, called at every phase boundary. If the sampler recorded the
# SUT's death, fail the step loudly and immediately: measurements past this point
# are of a dead container. The EXIT trap still captures the post-mortem diagnostics.
abort_if_sut_died() {
  [ -f "$SUT_DEATH_SENTINEL" ] || return 0
  local c ec oom el status detail
  # tostring, NOT `// "?"`, for the fields: jq's `//` falls back on false as well as
  # null, so `.oom_killed // "?"` would print "?" for a genuine OOMKilled=false — the
  # exact in-JVM-OOM case this message must state. `has()` gives the real missing-field guard.
  c="$(jq -r 'if has("container") then (.container|tostring) else "?" end' "$SUT_DEATH_SENTINEL" 2>/dev/null || echo '?')"
  ec="$(jq -r 'if has("exit_code") then (.exit_code|tostring) else "?" end' "$SUT_DEATH_SENTINEL" 2>/dev/null || echo '?')"
  oom="$(jq -r 'if has("oom_killed") then (.oom_killed|tostring) else "?" end' "$SUT_DEATH_SENTINEL" 2>/dev/null || echo '?')"
  el="$(jq -r 'if has("elapsed_s") then (.elapsed_s|tostring) else "?" end' "$SUT_DEATH_SENTINEL" 2>/dev/null || echo '?')"
  status="$(jq -r 'if has("status") then (.status|tostring) else "?" end' "$SUT_DEATH_SENTINEL" 2>/dev/null || echo '?')"
  # With -XX:+ExitOnOutOfMemoryError set, OOMKilled=false + a non-zero exit code is an
  # in-JVM OutOfMemoryError; OOMKilled=true is a cgroup/native (direct-buffer) kill.
  detail="SUT container ${c} died ${el}s into the run (Status=${status}, ExitCode=${ec}, OOMKilled=${oom}). OOMKilled=false with a non-zero ExitCode under -XX:+ExitOnOutOfMemoryError is an in-JVM OutOfMemoryError; OOMKilled=true is a cgroup/native kill. Every measurement after the death is of a dead container, so this run measured nothing baselineable."
  echo "+++ SUT LIVENESS GATE TRIPPED — the run's subject died; aborting" >&2
  echo "--- $detail" >&2
  add_check "sut_alive" false "$detail"
  emit_invalid_result
  exit 1
}

# k6 duration string ("15s","1m30s") -> integer seconds (floor). Handles s/m/h/d;
# the sweep step/gap are seconds, so ms is not expected.
to_secs() {
  awk -v s="$1" 'BEGIN{
    t=0; n="";
    for(i=1;i<=length(s);i++){c=substr(s,i,1);
      if(c ~ /[0-9]/){n=n c}
      else{v=n+0; n="";
        if(c=="s")t+=v; else if(c=="m")t+=v*60; else if(c=="h")t+=v*3600; else if(c=="d")t+=v*86400}}
    printf "%d", t}'
}

# Count cores in a cpuset spec ("8-13" -> 6, "8,9,10" -> 3, "" -> 0). Used to turn
# the k6 container's CPU pin into an absolute percentage ceiling (cores*100%).
k6_core_count() {
  local spec="$1" total=0 part a b
  [ -z "$spec" ] && { echo 0; return; }
  IFS=',' read -ra _parts <<< "$spec"
  for part in "${_parts[@]}"; do
    if [[ "$part" == *-* ]]; then a="${part%%-*}"; b="${part##*-}"; total=$((total + b - a + 1));
    else total=$((total + 1)); fi
  done
  echo "$total"
}

# The physical-core disjointness proof (expand_cpuset / phys_core_key / the
# generalised cpusets_physically_disjoint checker) now lives in a shared lib so the
# multi-process client rig (mockserver-performance-test/scripts/multi-process-sweep.sh)
# uses the SAME implementation and cannot silently reintroduce the "load generator
# shares the server's physical cores" defect. Sourced (not re-implemented); the
# assert_cpusets_physically_disjoint wrapper below still reads this run's three
# cpuset globals so its call site is unchanged. Fail closed if the lib is missing —
# an unprovable benchmark is the thing this guard exists to stop.
if [ -r "$SCRIPT_DIR/lib/perf-cpu-topology.sh" ]; then
  # shellcheck source=lib/perf-cpu-topology.sh
  . "$SCRIPT_DIR/lib/perf-cpu-topology.sh"
  numa_map_prime
else
  echo ":x: CPU-topology guard lib not found at $SCRIPT_DIR/lib/perf-cpu-topology.sh — refusing to run without the physical-core disjointness proof" >&2
  exit 1
fi
if [ -r "$SCRIPT_DIR/lib/perf-sweep-window.sh" ]; then
  # shellcheck source=lib/perf-sweep-window.sh
  . "$SCRIPT_DIR/lib/perf-sweep-window.sh"
else
  echo ":x: sweep latency-window check lib not found at $SCRIPT_DIR/lib/perf-sweep-window.sh" >&2
  exit 1
fi

# THE GUARD. The cpusets below must be disjoint in PHYSICAL cores, not just logical
# cpus: a logical-cpu count cannot tell a server sharing hyperthread siblings with its
# own load generator from a clean rig, which silently caps the measured knee. So the
# run resolves real topology and fails rather than publish a shared-silicon figure.
# Thin wrapper preserving this run's call site (assert_cpusets_physically_disjoint
# || exit 1 below): it hands this run's three cpuset globals to the shared,
# generalised checker in lib/perf-cpu-topology.sh. The proof itself — /sys topology
# resolution, CI fail-closed vs off-CI warn, the reversed-range-is-fatal rule, and
# the bash-3.2-safe seen-cores table (no `local -A`) — lives ONCE in that lib and is
# shared with the multi-process client rig, so the two cannot drift. An unpinned
# global is passed through as an empty spec, which the checker skips.
assert_cpusets_physically_disjoint() {
  cpusets_physically_disjoint server "$SERVER_CPUS" upstream "$UPSTREAM_CPUS" k6 "$K6_CPUS"
}

# --- core pinning --------------------------------------------------------------
numa_log_node_cpulists
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
SERVER_CPUS=""; UPSTREAM_CPUS=""; K6_CPUS=""
if [ "$CORES" -ge 16 ]; then
  # Defaults: server=0-5 upstream=6 k6=7-23. These are LOGICAL cpu ids; the
  # topology guard below is what PROVES they land on distinct PHYSICAL cores — this
  # list alone cannot, and the sibling pairing is NOT assumed (it is read from sysfs
  # at run time by lib/perf-cpu-topology.sh, and the run fails closed if it cannot be
  # read in CI). Every id here is in the lower half (0-23), which on the perf queue's
  # c5.12xlarge is one thread per physical core (24 cores, 48 threads), so k6 never
  # lands on the server's hyperthread siblings — those live in the upper half and are
  # left IDLE, which is also what keeps the published "six real cores" honest.
  #
  # k6 spans cores 7-23 (seventeen cores) so the client keeps headroom above the
  # server's ceiling and the knee measured is the SERVER's, not the load generator's
  # (thirteen client cores proved too few at 48k offered). A ten-vCPU server arm would
  # leave too few client cores on this box, so it needs k6 on another machine. A wider
  # server arm still overrides
  # PERF_SERVER_CPUS and the guard re-proves disjointness for whatever is active.
  #
  # Changing this cpuset breaks comparability of the stored saturation series and
  # nothing in the tooling announces it; see docs/code/performance-measurement.md#saturation-series-comparability-break.
  #
  # In use (6-vCPU arm): 6 (server) + 1 (upstream) + 17 (k6) physical cores, highest
  # id 23, so the box needs 24 physical cores; c5.12xlarge has exactly 24. A smaller
  # box trips the guard (fail-closed) rather than letting k6 share the server's
  # cores. If the perf queue moves to a smaller box, narrow PERF_K6_CPUS to match
  # instead of disabling the guard.
  #
  # Each cpuset is overridable via PERF_SERVER_CPUS / PERF_UPSTREAM_CPUS / PERF_K6_CPUS.
  SERVER_CPUS="${PERF_SERVER_CPUS:-0-5}"; UPSTREAM_CPUS="${PERF_UPSTREAM_CPUS:-6}"; K6_CPUS="${PERF_K6_CPUS:-7-23}"
  # With two or more NUMA nodes 7-23 is on the SUT's node, so k6 keeps the c5 budget (17 physical cores,
  # one thread each) on the other socket, or on the SUT's node for PERF_K6_NUMA_NODE=same.
  if [ -z "${PERF_K6_CPUS:-}" ] && [ "$(numa_node_count)" -ge 2 ]; then
    K6_CPUS="$(numa_single_k6_cpus "$K6_NUMA_NODE" "$SERVER_CPUS" "$UPSTREAM_CPUS" 17)" \
      || { echo ":x: no default k6 cpuset fits this ${CORES}-cpu, $(numa_node_count)-node host (above); set PERF_K6_CPUS" >&2; exit 1; }
  fi
  echo "--- core-pinning enabled (${CORES} logical cpus): server=$SERVER_CPUS upstream=$UPSTREAM_CPUS k6=$K6_CPUS"
  assert_cpusets_physically_disjoint || exit 1
  numa_placement_check "$K6_NUMA_NODE" "$SERVER_CPUS" "$K6_CPUS" || exit 1
  if [ "$(numa_node_count)" -ge 2 ]; then assert_cpuset_single_node upstream "$UPSTREAM_CPUS" || exit 1; fi
else
  echo "--- WARNING: ${CORES} logical cpus (<16) — core-pinning skipped; numbers will be noisier"
fi
cpuset_arg() { [ -n "$1" ] && printf -- '--cpuset-cpus=%s' "$1"; }

# Resolved PHYSICAL-core counts per role (two hyperthread siblings count once),
# read from real sysfs topology — recorded in perf-result.json so a published
# core-scaling figure states real cores, not vCPUs. "null" when topology is
# unreadable (off-CI); the fail-closed check is assert_cpusets_physically_disjoint
# above, not this reporting helper. jq --argjson needs a JSON literal, so an empty
# helper output degrades to null rather than crashing the assembly.
SERVER_PHYS_CORES="$(phys_core_count "${SERVER_CPUS:-}")"; SERVER_PHYS_CORES="${SERVER_PHYS_CORES:-null}"
UPSTREAM_PHYS_CORES="$(phys_core_count "${UPSTREAM_CPUS:-}")"; UPSTREAM_PHYS_CORES="${UPSTREAM_PHYS_CORES:-null}"
K6_PHYS_CORES="$(phys_core_count "${K6_CPUS:-}")"; K6_PHYS_CORES="${K6_PHYS_CORES:-null}"
echo "--- resolved physical cores: server=$SERVER_PHYS_CORES upstream=$UPSTREAM_PHYS_CORES k6=$K6_PHYS_CORES (vcpu ids server=${SERVER_CPUS:-none} upstream=${UPSTREAM_CPUS:-none} k6=${K6_CPUS:-none})"

# host mode shares the agent's network stack, so there is no user-defined bridge to
# create; bridge mode is unchanged.
if [ "$PERF_NETWORK_MODE" != host ]; then
  docker network create "$NETWORK" >/dev/null
fi

# Extra `-e` pairs for the next start_mockserver call only (set by the path-coverage SUTs).
START_EXTRA_ENV=()
start_mockserver() {
  local name="$1" cpus="$2" alias="$3" publish="${4:-}" mem="${5:-}" mount="${6:-}" log_level="${7:-ERROR}" diag_subdir="${8:-}" no_rm="${9:-}" port="${10:-1080}"
  # An EMPTY 11th argument passes no budget env at all (the server's shipped default); omitted = the fixed harness budget.
  local event_log_bytes="${11-$HARNESS_FIXED_EVENT_LOG_BYTES}"
  # log_level defaults to ERROR — the tracked baseline's level, which every existing
  # caller relies on. The INFO publication arm (plan open question 5) passes INFO
  # explicitly; nothing else does, so the ERROR baseline is unaffected.
  # PERF_SERVER_JAVA_OPTS (when set) is passed through as JAVA_TOOL_OPTIONS so a
  # re-run can opt into a tuned JVM (e.g. low-pause GC + a larger heap) for nicer
  # documentation-site throughput/latency figures. Applied to EVERY caller of this
  # function — the SUTs and the upstreams — so the upstream never becomes the bottleneck
  # under those tuned rates; only diag_subdir callers mount /diag, so SUT-only file
  # diagnostics go through diag_jvm_opts (PERF_JVM_DIAGNOSTICS), never this variable.
  # Request logging is deliberately left ON (we don't disable it here) so the growth
  # phase stays meaningful. Built as an array element so the value
  # survives intact as a SINGLE -e pair even though it contains spaces; unset =>
  # the array is empty and no -e flag is added, identical behaviour.
  # When diag_subdir is set (the SUT and INFO SUT only) attach the JVM-internals diagnostics: mount
  # the host $DIAG_DIR so gc.log / heap dump / JFR survive the container's death, append the tier-1
  # (plus the gc or deep tier when PERF_JVM_DIAGNOSTICS asks) JVM opts to JAVA_TOOL_OPTIONS, and —
  # critically — DROP --rm so the dead container lingers long enough for capture_sut_diagnostics() to read its
  # post-mortem (docker inspect .State / docker logs). cleanup() force-removes it by name, so nothing
  # leaks. no_rm drops --rm without diag (the upstream, so a seed failure can inspect its corpse);
  # every OTHER container (stream/clustered/handshake SUTs) keeps --rm unchanged.
  local rm_flag="--rm" diag_mount_arg=() combined_java_opts="${PERF_SERVER_JAVA_OPTS:-}"
  if [ -n "$diag_subdir" ]; then
    rm_flag=""
    diag_mount_arg=(-v "$DIAG_DIR:/diag")
    local diag_opts; diag_opts="$(diag_jvm_opts "$diag_subdir")"
    combined_java_opts="${combined_java_opts:+$combined_java_opts }$diag_opts"
  fi
  [ -n "$no_rm" ] && rm_flag=""
  local java_opts_arg=()
  if [ -n "$combined_java_opts" ]; then
    combined_java_opts="$(compose_java_tool_options "$SUT_IMAGE_JAVA_TOOL_OPTIONS" "$combined_java_opts")"
    java_opts_arg=(-e "JAVA_TOOL_OPTIONS=$combined_java_opts")
  fi
  [ "$PERF_NETWORK_MODE" = host ] || require_dns_hostname "$alias" || return 1
  # host mode: no user-defined bridge, so no network-scoped alias and no port
  # publish (the SUT is already on the host at 127.0.0.1:$port). bridge mode expands
  # to the exact prior tokens.
  local net_flags=(--network "$NETWORK" --network-alias "$alias") publish_flag=(${publish:+-p 127.0.0.1::1080})
  if [ "$PERF_NETWORK_MODE" = host ]; then net_flags=(--network host); publish_flag=(); fi
  # The memory of the cpuset's NUMA node; empty for an unpinned container or an unreadable node map.
  local mems; mems="$(numa_mems_flag "$cpus")"
  # shellcheck disable=SC2046
  docker run -d $rm_flag --name "$name" "${net_flags[@]}" \
    $(cpuset_arg "$cpus") ${mems:+"$mems"} \
    ${mem:+--memory="$mem"} \
    ${mount:+-v "$mount"} \
    ${diag_mount_arg[@]+"${diag_mount_arg[@]}"} \
    ${publish_flag[@]+"${publish_flag[@]}"} \
    ${java_opts_arg[@]+"${java_opts_arg[@]}"} \
    ${START_EXTRA_ENV[@]+"${START_EXTRA_ENV[@]}"} \
    -e MOCKSERVER_LOG_LEVEL="$log_level" \
    -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
    -e MOCKSERVER_METRICS_ENABLED=true \
    ${event_log_bytes:+-e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES="$event_log_bytes"} \
    ${PERF_SO_BACKLOG:+-e MOCKSERVER_SO_BACKLOG="$PERF_SO_BACKLOG"} \
    "$MOCKSERVER_IMAGE" -serverPort "$port" >/dev/null || return
  log_container_cpuset "$alias" "$name"
}

# A container's .State verdict and last 50 log lines, to stderr.
print_container_postmortem() {
  local c="$1" st
  if st="$(docker inspect --format '{{json .State}}' "$c" 2>/dev/null)"; then
    echo "--- $c .State: $(printf '%s' "$st" | jq -rc '{OOMKilled,ExitCode,Status,Error,FinishedAt,Health:(.Health.Status // null)}' 2>/dev/null || printf '%s' "$st")" >&2
    echo "--- $c last 50 log lines:" >&2
    docker logs --tail 50 "$c" >&2 2>&1 || true
  else
    echo "--- container $c no longer exists — cannot inspect" >&2
  fi
}

# A kept (non --rm) container that exits stays inspectable with health "unhealthy", so .State.Status
# must be read too, or a JVM that died at start is polled for the full 120 s.
wait_ready() {
  local name="$1" state status health
  for _ in $(seq 1 60); do
    # Not `$(… || echo missing)`: a failed inspect still prints an empty line to stdout, so that
    # form yields "\nmissing" and the missing branch never matched.
    state="$(docker inspect --format '{{.State.Status}} {{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' "$name" 2>/dev/null)" \
      || state="missing missing"
    status="${state%% *}"; health="${state#* }"
    case "$status" in
      missing) echo "ERROR: container $name exited early" >&2; docker logs "$name" 2>&1 | tail -20 >&2 || true; return 1 ;;
      exited|dead|removing|created) echo "ERROR: container $name is $status before becoming ready" >&2; print_container_postmortem "$name"; return 1 ;;
    esac
    case "$health" in
      healthy) return 0 ;;
      nohealth) sleep 10; return 0 ;;
      unhealthy)
        if [ "$status" != running ]; then
          echo "ERROR: container $name is unhealthy and not running (status=$status)" >&2; print_container_postmortem "$name"; return 1
        fi ;;
    esac
    sleep 2
  done
  echo "ERROR: $name did not become ready" >&2; print_container_postmortem "$name"; return 1
}

# The server's network alias is `mockserver`, which k6's config.js treats as a
# local/private target — so the HTTPS pass auto-trusts the self-signed cert with
# no per-VU TLS warning, and no reliance on an explicit insecure flag.
SERVER_ALIAS="mockserver"

# --- network-mode addressing --------------------------------------------------
# bridge: containers join $NETWORK under their alias and clients reach them by
#   alias:1080 — every value below is the exact string used before this lever.
# host: SUT+upstream+clients share the host stack; the SUT stays on 127.0.0.1:1080
#   and the upstream moves to a distinct port so two servers can coexist.
SUT_PORT=1080
if [ "$PERF_NETWORK_MODE" = host ]; then UPSTREAM_PORT=1081; else UPSTREAM_PORT=1080; fi
# Client-container network flag as an array (byte-identical to `--network "$NETWORK"`
# in bridge). Every primary-path k6/curl container expands this.
if [ "$PERF_NETWORK_MODE" = host ]; then NET_CLIENT=(--network host); else NET_CLIENT=(--network "$NETWORK"); fi
if [ "$PERF_NETWORK_MODE" = host ]; then
  SUT_BASE_HTTP="http://127.0.0.1:${SUT_PORT}"; SUT_BASE_HTTPS="https://127.0.0.1:${SUT_PORT}"
  UPSTREAM_HOSTPORT="127.0.0.1:${UPSTREAM_PORT}"
else
  SUT_BASE_HTTP="http://${SERVER_ALIAS}:1080"; SUT_BASE_HTTPS="https://${SERVER_ALIAS}:1080"
  UPSTREAM_HOSTPORT="${UPSTREAM_ALIAS}:1080"
fi
require_dns_hostname "$UPSTREAM_HOSTPORT" || exit 1

# --- SUT image freshness: pull the mutable tag before measuring ----------------
# MOCKSERVER_IMAGE is a MUTABLE tag (mockserver-snapshot-graaljs) rebuilt on every
# master merge. With no pull, `docker run` silently uses whatever layer set the
# agent happens to have cached, with nothing tying it to the commit under test. Pull ONCE up front rather than
# `--pull always` on each of the ~6 `docker run` calls below: --pull always would
# re-query the registry per container (upstream, SUT, stream SUT, clustered nodes,
# INFO SUT) for the same tag; a single pull freshens the shared local image once.
# Pull, don't skip. Cost is one pull (seconds)
# against a 45-60 min run — negligible — and it does add a registry-availability
# dependency, but the FIRST-ever run already needs the registry, and a registry
# outage is better surfaced loudly here than by silently measuring a stale image.
# Best-effort by design: if the pull fails but a cached image exists the run still
# proceeds, because the provenance check below is the real backstop — it refuses to
# attribute the run if the (cached) image was built from another commit, so a stale
# pull can never masquerade as a fresh measurement. The recorded image_digest
# (RepoDigest) then reflects whatever was actually pulled/used.
# Set PERF_PULL_IMAGE=false for a LOCAL run that is deliberately measuring a
# locally-built image (a pull would otherwise clobber it with the registry copy).
if [ "${PERF_PULL_IMAGE:-true}" = "true" ]; then
  echo "--- pulling SUT image $MOCKSERVER_IMAGE (freshen mutable tag before measuring)"
  docker pull "$MOCKSERVER_IMAGE" \
    || echo "WARNING: 'docker pull $MOCKSERVER_IMAGE' failed — proceeding with the cached image if present; the provenance check is the backstop" >&2
else
  echo "--- PERF_PULL_IMAGE=false — using the cached/local $MOCKSERVER_IMAGE (provenance check still applies)"
fi

# The checkout's full SHA (a UI build's BUILDKITE_COMMIT can be the symbolic HEAD).
HARNESS_COMMIT="$(git -C "$REPO_ROOT" rev-parse --verify -q "${BUILDKITE_COMMIT:-HEAD}^{commit}" 2>/dev/null || echo "${BUILDKITE_COMMIT:-unknown}")"
# A forced run that measured an image this commit is not served would be read as a measurement
# of this one, so it is refused here rather than after an hour of load.
PULLED_REVISION="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$MOCKSERVER_IMAGE" 2>/dev/null || true)"
LAG_REFUSAL="$(image_revision_refusal "$PULLED_REVISION" "$HARNESS_COMMIT")"
# perf-baseline-freshness.sh ignores a build whose perf-run step was refused: it measured nothing.
# Set on every perf-run attempt, so a retry that measures clears an earlier refusal.
if [ -z "${PERF_RUN_NAME:-}" ] && command -v buildkite-agent >/dev/null 2>&1; then
  if [ -n "$LAG_REFUSAL" ]; then REFUSED_FLAG=image_revision; else REFUSED_FLAG=no; fi
  buildkite-agent meta-data set "perf_run_refused" "$REFUSED_FLAG" || true
fi
if [ -n "$LAG_REFUSAL" ]; then
  LAG_MSG="Forced perf run refused: ${LAG_REFUSAL}. Wait until mockserver-java has pushed the :snapshot images for ${HARNESS_COMMIT:0:10}, or for its newest ancestor that changes mockserver/, mockserver-ui/ or test-fixtures/, then start a new mockserver-performance-test build on ${HARNESS_COMMIT:0:10} with [perf-run] in its message. If master has moved past it, measure the new tip instead."
  echo "+++ :x: $LAG_MSG" >&2
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf ':x: %s\n' "$LAG_MSG" | buildkite-agent annotate --style error --context "perf-image-revision" || true
  fi
  exit 1
fi

# --- pull the CLUSTERED image here too, beside the SUT pull -----------
# THE FIX for a harness that never measured: the clustered A/B block guards on
# `docker image inspect "$CLU_IMAGE"`, and NOTHING in the perf flow ever pulled or
# built that image — so on a scale-to-zero perf agent (a fresh box every run) the
# inspect always failed and the block took its absent-image skip on every single
# run. The image is now published as a mutable snapshot tag by the same job that
# publishes the SUT image, so it is obtained the same way the SUT is: one pull of a
# mutable tag, up front, once.
#
# WHY UP HERE and not in the clustered block: the run is ATTRIBUTED to the SUT
# image's revision label, and the clustered arm is only meaningful if it executed the
# SAME commit's code. Both tags move together on a master merge, so pulling them
# seconds apart keeps them paired; pulling the clustered tag ~40 min later (where the
# A/B runs) would let a merge landing mid-run hand us a NEWER clustered image than the
# SUT — a mismatch that produces an arithmetically fine ratio filed under the wrong
# commit. The revision equality check in the clustered block is the backstop that
# catches it either way; this placement just makes the mismatch rare.
#
# Best-effort, exactly like the SUT pull: a failed pull leaves whatever is cached, and
# the absent-image / revision-mismatch guards below decide what to do. It must NOT
# abort the run — the clustered A/B is notify-only and the k6/growth result is not.
if [ "${PERF_CLUSTERED:-true}" = "true" ] && [ "${PERF_PULL_IMAGE:-true}" = "true" ]; then
  echo "--- pulling clustered image $CLU_IMAGE (item 13 A/B; paired with the SUT pull above)"
  docker pull "$CLU_IMAGE" \
    || echo "WARNING: 'docker pull $CLU_IMAGE' failed — the item-13 clustered A/B will fall back to a cached image if present, else SKIP (notify-only)" >&2
fi

# Resolve the SUT image's built-in JAVA_TOOL_OPTIONS once — prepended to every container that
# overrides it (below and the handshake JDK SUT) so the shipped GC survives, and used by the
# default-profile GC guard near "config resolved".
SUT_IMAGE_JAVA_TOOL_OPTIONS="$(image_java_tool_options "$MOCKSERVER_IMAGE")"

echo "--- starting upstream + MockServer ($MOCKSERVER_IMAGE)"
start_mockserver "$UPSTREAM" "$UPSTREAM_CPUS" "$UPSTREAM_ALIAS" "" "" "" "ERROR" "" "keep" "$UPSTREAM_PORT"
start_mockserver "$SERVER" "$SERVER_CPUS" "$SERVER_ALIAS" "publish" "$SERVER_MEMORY" "$FILE_BODY_MOUNT" "ERROR" "sut" "" "$SUT_PORT" "$PERF_MAX_EVENT_LOG_BYTES"
# Follow the SUT's stdout/stderr into a host file from the moment it starts. The follow stream ENDS
# when the container dies, so the file survives the container's reaping and captures the JVM's dying
# words — an OutOfMemoryError stack, a "Terminating due to java.lang.OutOfMemoryError" from
# ExitOnOutOfMemoryError, or (tier 2) the PrintNMTStatistics native-memory summary. This matters
# because the SUT runs at log_level=ERROR with DISABLE_SYSTEM_OUT=true, so normal output is sparse
# and the death message would otherwise be the only signal — and it is exactly what --rm destroyed.
docker logs -f "$SERVER" > "$DIAG_DIR/sut/sut-server.log" 2>&1 &
SUT_LOG_PID=$!
echo "--- SUT started with --memory=$SERVER_MEMORY (bounded heap so GC cycles) + maxEventLogSizeInBytes=${PERF_MAX_EVENT_LOG_BYTES:-<shipped default>} (body-byte OOM guard)"
wait_ready "$UPSTREAM"
wait_ready "$SERVER"

# Host-mapped metrics port so the sampler reads /mockserver/metrics from the host
# (curl on the agent) instead of spawning a container per sample — avoids adding
# CPU noise to the very box being measured. k6 still reaches the server over the
# docker network (container alias), unaffected by this host publish.
# host mode publishes no port (the SUT is directly on the host); bridge mode reads
# the host-mapped port exactly as before.
if [ "$PERF_NETWORK_MODE" = host ]; then
  SERVER_METRICS_URL="http://127.0.0.1:${SUT_PORT}/mockserver/metrics"
else
  SERVER_METRICS="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1)"
  SERVER_METRICS_URL="http://${SERVER_METRICS:-127.0.0.1:1080}/mockserver/metrics"
fi

# --- self-describing config block (make a result record what it WAS) -----------
# The comparison machinery, budget ratchet, hardware-invalidation rule and the
# website provenance line all assume a stored run records HOW it was configured.
# Resolve that from the RUNNING JVM (its own metrics endpoint) and the ACTUAL
# container (docker inspect) — never by echoing the shell variables that were only
# MEANT to set it. Each field is marked `observed` (read back from the live
# process/container) or `declared` (a shell value we could not verify against the
# process). A MANDATORY value that cannot be recorded FAILS the step HERE, early —
# not silently written as a placeholder. The instance_type:"" bug is the worked
# example: a field that exists, is populated, and is WRONG survives review, which
# is worse than an absent field. Runs before the long measurement phases so an
# unrecordable config wastes seconds, not the whole 45-minute run.
CONFIG_METRICS=""
for _ in $(seq 1 15); do
  CONFIG_METRICS="$(curl -sf --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || true)"
  if [ -n "$CONFIG_METRICS" ] && grep -q '^mock_server_build_info' <<<"$CONFIG_METRICS"; then break; fi
  sleep 2
done

# Extract one label's value from a Prometheus info-gauge line (labels are quoted,
# so a comma/space inside a value — e.g. the GC list or the VM name — is safe).
# The label name is anchored to its preceding `{` or `,` delimiter so a query for
# `version` does NOT match inside `major_minor_version` (that substring match would
# silently record the wrong value — precisely the populated-but-wrong trap).
# The trailing `|| true` matters: a no-match here must yield the EMPTY string and
# succeed, so the fail-closed guard below can report *which* field is unrecordable.
# Without it, `set -euo pipefail` would abort the whole step on the failing grep
# (an accidental fail-closed with no diagnostic), turning the guard into dead code.
metric_label() { # metric_name label_name   (reads global CONFIG_METRICS)
  printf '%s' "$CONFIG_METRICS" | grep -oE "^$1\{[^}]*\}" | head -1 \
    | grep -oE "[{,]$2=\"[^\"]*\"" | head -1 | sed -E 's/^[{,][^=]*="//; s/"$//' || true
}
# Resolved max heap in bytes from the RUNNING JVM (reflects the entrypoint's
# -Xmx / MaxRAMPercentage + the container memory limit — the actual heap, not the
# flag that implies it). Normalised to an integer (the client may emit 1.6E9).
metric_heap_max() {
  printf '%s' "$CONFIG_METRICS" \
    | awk -F'} ' '/^jvm_memory_max_bytes\{area="heap"\}/{print $2}' | head -1 \
    | awk '{printf "%d", $1+0}' || true
}
# One sample's value from metrics text on stdin, as an integer; empty when absent. Exact name match.
gauge_value() { # exact_series_name  < metrics_text
  awk -v m="$1" '$1 == m && $2 != "" {printf "%.0f", $2 + 0; exit}' || true
}
counter_sum() { # series_name  < metrics_text -> summed over all its labels; empty when absent
  awk -v m="$1" '($1 == m || index($1, m "{") == 1) && $2 != "" {t += $2; n = 1} END {if (n) printf "%.0f", t}' || true
}
# --- INFO-arm event-log budget helpers (perf-info-budget-test.sh runs each of them) ---
# The info_* compare key: what the INFO SUT was handed, not what it resolved.
info_els_method() { # requested_bytes -> shipped-default | fixed-<bytes>
  if [ -n "$1" ]; then echo "fixed-$1"; else echo "shipped-default"; fi
}
# --- main-SUT event-log budget helpers (perf-default-budget-test.sh runs each of them) ---
# The server's heap divisors for the default retention budget and the in-flight floor
# (ConfigurationProperties.defaultMaxEventLogSizeInBytes / defaultEventLogInFlightBytes); keep in step
# with them. The in-flight cap is max(budget, heap/floor divisor). An unset level is the default, INFO.
els_default_divisor() { # log_level
  case "$1" in DEBUG|TRACE) echo 12 ;; *) echo 20 ;; esac
}
els_inflight_divisor() { # log_level
  case "$1" in DEBUG|TRACE) echo 12 ;; *) echo 7 ;; esac
}
# One CONFIG_ERRORS line per failure, nothing when sound. An override is proven by the container env
# (and by the gauge when the image has one); the shipped default only by the gauge, the JVM's own word.
main_els_guard() { # requested handed resolved
  if [ -n "$1" ]; then
    [ "$2" = "$1" ] || echo "PERF_MAX_EVENT_LOG_BYTES=$1 was declared but the SUT container reports MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES='$2' — this run would be labelled tuned while measuring something else"
    [ -z "$3" ] || [ "$3" = "$1" ] || echo "PERF_MAX_EVENT_LOG_BYTES=$1 was handed to the SUT but its gauge mock_server_event_log_max_retained_bytes reads '$3'"
    return 0
  fi
  [ -z "$2" ] || echo "the SUT must run at the shipped event-log budget but was handed MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES='$2'"
  awk -v v="$3" 'BEGIN{exit !(v+0>0)}' || echo "event-log byte budget not confirmed on the SUT: mock_server_event_log_max_retained_bytes='$3' (absent, unreadable or 0) — a run without a byte bound is at the build-#249 OOM risk; an image that predates the gauge needs PERF_MAX_EVENT_LOG_BYTES"
}
main_els_json() { # handed resolved_bytes resolved_entries resolved_in_flight heap_bytes log_level -> config.event_log_budget
  jq -nc --arg handed "$1" --arg mb "$2" --arg me "$3" --arg mif "$4" --arg heap "$5" \
    --argjson div "$(els_default_divisor "$6")" --argjson ifdiv "$(els_inflight_divisor "$6")" --arg method "$(info_els_method "$1")" '
    def num: if . == "" then null else (tonumber? // null) end;
    ($heap | num) as $h
    | def fraction($d): if $h == null then null
        else ((($h / 1024 | floor) - 20480) as $hk | if $hk < 0 then 0 else (($hk / $d | floor) * 1024) end) end;
    ($mb | num) as $b
    | { method: $method, requested_bytes: ($handed | num), requested_source: "container-env",
        resolved_max_event_log_bytes: $b, resolved_max_log_entries: ($me | num),
        resolved_max_in_flight_bytes: ($mif | num),
        resolved_source: (if $b != null then "observed" else "unavailable" end),
        heap_max_bytes: $h, heap_divisor: $div, in_flight_heap_divisor: $ifdiv,
        expected_default_bytes: fraction($div),
        expected_in_flight_cap_bytes: (if $b == null or fraction($ifdiv) == null then null
          elif $b <= 0 then 0 else ([$b, fraction($ifdiv)] | max) end) }'
}
# PUTs maxEventLogSizeInBytes and confirms it from the SUT's own gauge; ELS_GAUGE_READ holds what
# the gauge last read, so a failure can say what the server actually kept.
set_sut_event_log_budget() { # base_url bytes -> 0 when the gauge reads bytes back
  local i
  ELS_GAUGE_READ=""
  curl -sf --max-time 10 -o /dev/null -X PUT "$1/mockserver/configuration" \
    -H 'Content-Type: application/json' -d "{\"maxEventLogSizeInBytes\": $2}" || { ELS_GAUGE_READ="<PUT failed>"; return 1; }
  for i in 1 2 3; do
    ELS_GAUGE_READ="$( { curl -sf --max-time 4 "$1/mockserver/metrics" 2>/dev/null || true; } | gauge_value mock_server_event_log_max_retained_bytes)"
    [ "$ELS_GAUGE_READ" = "$2" ] && return 0
    [ "$i" -lt 3 ] && sleep 1
  done
  return 1
}
# growth.js needs at least GROWTH_EVENT_LOG_BYTES for its count-ring fill. GROWTH_ELS_METHOD says how
# it got it: "startup" (PERF_MAX_EVENT_LOG_BYTES), "startup-sufficient" (the resolved budget already
# covers it) or "scoped" (raised for the phase, restored after, both gauge-confirmed).
GROWTH_ELS_PRIOR=""; GROWTH_ELS_PRIOR_INFLIGHT=""; GROWTH_ELS_APPLIED=""; GROWTH_ELS_PUT_SENT=false
GROWTH_ELS_PROBLEM=""; GROWTH_ELS_METHOD=""
scope_growth_event_log_budget() { # base_url
  local m
  GROWTH_ELS_PROBLEM=""; GROWTH_ELS_PUT_SENT=false; GROWTH_ELS_APPLIED=""
  if [ -n "$PERF_MAX_EVENT_LOG_BYTES" ]; then
    GROWTH_ELS_METHOD=startup; GROWTH_ELS_PRIOR="$PERF_MAX_EVENT_LOG_BYTES"; GROWTH_ELS_APPLIED="$PERF_MAX_EVENT_LOG_BYTES"; return 0
  fi
  GROWTH_ELS_METHOD=scoped
  m="$(curl -sf --max-time 4 "$1/mockserver/metrics" 2>/dev/null || true)"
  GROWTH_ELS_PRIOR="$(gauge_value mock_server_event_log_max_retained_bytes <<<"$m")"
  GROWTH_ELS_PRIOR_INFLIGHT="$(gauge_value mock_server_event_log_max_in_flight_bytes <<<"$m")"
  if [ -z "$GROWTH_ELS_PRIOR" ]; then
    GROWTH_ELS_PROBLEM="the SUT's pre-growth budget (gauge mock_server_event_log_max_retained_bytes) could not be read, so the budget was not raised; growth.js ran at the shipped budget"
    return 1
  fi
  if [ "$GROWTH_ELS_PRIOR" -ge "$GROWTH_EVENT_LOG_BYTES" ]; then
    GROWTH_ELS_METHOD=startup-sufficient; GROWTH_ELS_APPLIED="$GROWTH_ELS_PRIOR"; return 0
  fi
  GROWTH_ELS_PUT_SENT=true
  if set_sut_event_log_budget "$1" "$GROWTH_EVENT_LOG_BYTES"; then GROWTH_ELS_APPLIED="$GROWTH_EVENT_LOG_BYTES"; return 0; fi
  if [ "$ELS_GAUGE_READ" = "<PUT failed>" ]; then
    GROWTH_ELS_PROBLEM="PUT /mockserver/configuration maxEventLogSizeInBytes=${GROWTH_EVENT_LOG_BYTES} failed (HTTP error or unreachable), so growth.js ran at the shipped budget ${GROWTH_ELS_PRIOR}"
  elif [ "$ELS_GAUGE_READ" = "$GROWTH_ELS_PRIOR" ]; then
    GROWTH_ELS_PROBLEM="PUT /mockserver/configuration maxEventLogSizeInBytes=${GROWTH_EVENT_LOG_BYTES} did not take effect: gauge mock_server_event_log_max_retained_bytes still reads '${ELS_GAUGE_READ}', expected ${GROWTH_EVENT_LOG_BYTES}. The SUT image predates the runtime-configuration fix (one Configuration shared by every MockServer construction path), so growth.js ran at the shipped budget ${GROWTH_ELS_PRIOR} and its byte cap, not the ~115.5k count ring, bounded the log (issue #2329 fill)"
  else
    GROWTH_ELS_PROBLEM="PUT /mockserver/configuration maxEventLogSizeInBytes=${GROWTH_EVENT_LOG_BYTES} unconfirmed (gauge mock_server_event_log_max_retained_bytes read '${ELS_GAUGE_READ}', expected ${GROWTH_EVENT_LOG_BYTES}, before ${GROWTH_ELS_PRIOR})"
  fi
  return 1
}
restore_growth_event_log_budget() { # base_url -> add_check growth_event_log_budget_scoped
  local inflight
  if [ "$GROWTH_ELS_PUT_SENT" = true ]; then
    if ! set_sut_event_log_budget "$1" "$GROWTH_ELS_PRIOR"; then
      GROWTH_ELS_PROBLEM="${GROWTH_ELS_PROBLEM:+$GROWTH_ELS_PROBLEM; }restoring maxEventLogSizeInBytes=${GROWTH_ELS_PRIOR} after growth.js did not take effect: gauge mock_server_event_log_max_retained_bytes reads '${ELS_GAUGE_READ}', expected ${GROWTH_ELS_PRIOR}, so every later phase ran on another budget"
    else
      inflight="$( { curl -sf --max-time 4 "$1/mockserver/metrics" 2>/dev/null || true; } | gauge_value mock_server_event_log_max_in_flight_bytes)"
      [ -n "$inflight" ] && [ "$inflight" = "$GROWTH_ELS_PRIOR_INFLIGHT" ] \
        || GROWTH_ELS_PROBLEM="${GROWTH_ELS_PROBLEM:+$GROWTH_ELS_PROBLEM; }after the restore the in-flight cap (gauge mock_server_event_log_max_in_flight_bytes) reads '${inflight}', not its pre-growth '${GROWTH_ELS_PRIOR_INFLIGHT}', so every later phase ran on another in-flight bound"
    fi
  fi
  if [ -n "$GROWTH_ELS_PROBLEM" ]; then
    add_check "growth_event_log_budget_scoped" false "$GROWTH_ELS_PROBLEM"
  elif [ "$GROWTH_ELS_METHOD" = startup ]; then
    add_check "growth_event_log_budget_scoped" true "growth.js ran at the PERF_MAX_EVENT_LOG_BYTES budget ${PERF_MAX_EVENT_LOG_BYTES} the SUT started with (no runtime change)"
  elif [ "$GROWTH_ELS_METHOD" = startup-sufficient ]; then
    add_check "growth_event_log_budget_scoped" true "growth.js ran at the SUT's resolved budget ${GROWTH_ELS_PRIOR}, already at least ${GROWTH_EVENT_LOG_BYTES} (no runtime change)"
  else
    add_check "growth_event_log_budget_scoped" true "growth.js ran at maxEventLogSizeInBytes=${GROWTH_ELS_APPLIED} (gauge-confirmed), restored to ${GROWTH_ELS_PRIOR} with the in-flight cap back at ${GROWTH_ELS_PRIOR_INFLIGHT}"
  fi
}
# event_log_scaling.growth_phase: the budget growth.js ran at and whether its count ring filled (#2329).
growth_phase_json() { # reads GROWTH_EVENT_LOG_BYTES, GROWTH_ELS_* and PERF_EVENT_LOG_APPROACH_RATIO
  jq -nc --arg method "$GROWTH_ELS_METHOD" \
    --arg target "$GROWTH_EVENT_LOG_BYTES" --arg prior "$GROWTH_ELS_PRIOR" --arg applied "$GROWTH_ELS_APPLIED" \
    --arg me "$GROWTH_ELS_MAX_ENTRIES" --arg mb "$GROWTH_ELS_MAX_BYTES" --arg pe "$GROWTH_ELS_PEAK_ENTRIES" \
    --arg pb "$GROWTH_ELS_PEAK_BYTES" --arg cu "$GROWTH_ELS_COUNT_UTIL" --arg bu "$GROWTH_ELS_BYTES_UTIL" \
    --arg rows "$GROWTH_ELS_ROWS" --argjson thr "$PERF_EVENT_LOG_APPROACH_RATIO" '
    def num: if . == "" then null else (tonumber? // null) end;
    (($rows | num) // 0) as $r | def seen(f): if $r > 0 then f else null end;
    { budget_method: (if $method == "" then null else $method end), target_bytes: ($target | num), prior_bytes: ($prior | num),
        applied_bytes: ($applied | num), samples: $r,
        resolved_max_event_log_bytes: seen($mb | num), resolved_max_log_entries: seen($me | num),
        peak_retained_entries: seen($pe | num), peak_retained_bytes: seen($pb | num),
        count_utilisation: seen($cu | num), bytes_utilisation: seen($bu | num),
        count_ring_filled: (if $r > 0 then (($cu | num) // 0) >= $thr else null end) }' 2>/dev/null || echo null
}
event_log_counters() { # < metrics_text -> "dropped|evicted", each empty when absent
  local m; m="$(cat)"
  printf '%s|%s\n' "$(counter_sum mock_server_dropped_log_events_total <<<"$m")" \
    "$(gauge_value mock_server_evicted_log_entries_total <<<"$m")"
}
# Appends "retained_bytes retained_entries" every 2 s until killed. The load phases reset the
# log, so its occupancy can only be seen by sampling during them. With a pressure csv, each scrape
# also appends the drop counters (by reason; blank on a server that exports no reason label) and
# the ring / in-flight gauges, which event_log_pressure_json lines up with the sweep rungs.
EVENT_LOG_PRESSURE_COLS="ts,dropped_ring_full,dropped_in_flight_bytes,dropped_log_events,ring_occupancy,ring_capacity,in_flight_bytes,max_in_flight_bytes"
event_log_sampler() { # host:port outfile [pressure_csv]
  local csv="${3:-}" m row_csv
  [ -z "$csv" ] || echo "$EVENT_LOG_PRESSURE_COLS" 2>/dev/null > "$csv" || csv=""
  while true; do
    row_csv="$csv"; m="$(curl -sf --max-time 2 "http://$1/mockserver/metrics" 2>/dev/null)" || row_csv=""
    # Only a complete scrape writes a pressure row: a body cut mid-number reads as a counter restart.
    # Stamped with the next whole second, so a row never claims a time before its values were read.
    awk -v ts="$(( $(date -u +%s) + 1 ))" -v csv="$row_csv" '
      function n(v) { return v == "" ? "" : sprintf("%.0f", v) }
      $1 == "mock_server_event_log_retained_bytes" {b = $2} $1 == "mock_server_event_log_retained_entries" {e = $2}
      $1 == "mock_server_dropped_log_events_total" {du = $2; seen = 1}
      index($1, "mock_server_dropped_log_events_total{") == 1 {
        dl += $2; labelled = 1; seen = 1
        if (index($1, "reason=\"ring_full\"")) rf = $2; else if (index($1, "reason=\"in_flight_bytes\"")) ib = $2 }
      $1 == "mock_server_event_log_ring_occupancy" {occ = $2; seen = 1} $1 == "mock_server_event_log_ring_capacity" {cap = $2}
      $1 == "mock_server_event_log_in_flight_bytes" {ifb = $2; seen = 1} $1 == "mock_server_event_log_max_in_flight_bytes" {maxinf = $2}
      END {if (b != "") printf "%.0f %.0f\n", b, e + 0
           # A scrape cut off between the two reason lines would read as a counter restart: leave its drops blank.
           if (labelled && (rf == "" || ib == "")) { rf = ""; ib = ""; dl = "" }
           if (csv != "" && seen) printf "%s,%s,%s,%s,%s,%s,%s,%s\n", ts, n(rf), n(ib), n(labelled ? dl : du), n(occ), n(cap), n(ifb), n(maxinf) >> csv}' <<<"$m" >> "$2" || true
    sleep 2
  done
}
start_info_els_sampler() { # host:port outfile [pressure_csv]
  event_log_sampler "$1" "$2" "${3:-}" & INFO_ELS_SAMPLER_PID=$!
}
stop_info_els_sampler() {
  [ -n "$INFO_ELS_SAMPLER_PID" ] || return 0
  kill "$INFO_ELS_SAMPLER_PID" >/dev/null 2>&1 || true
  wait "$INFO_ELS_SAMPLER_PID" 2>/dev/null || true
  INFO_ELS_SAMPLER_PID=""
}
event_log_peaks() { # samples_file -> "peak_bytes peak_entries", empty when there are no samples
  awk '{if ($1 > b) b = $1; if ($2 > e) e = $2; n++} END {if (n) printf "%.0f %.0f", b, e}' "$1" 2>/dev/null || true
}
# Report-only. Per sweep rung, the events dropped (by reason) and the peak ring occupancy and
# in-flight bytes; null when no sample bounds them. Rungs are separated by a gap, so a rung's drops
# run from the last sample at or before its start to the last sample at or before the next rung's
# start (the last rung: to the last sample, as the sampler stops when the sweep returns). The gap,
# where the rung's backlog drains, counts towards the rung before it, and the rungs sum to the drops
# sampled over the ladder.
# A counter that falls is read as restarted. Peaks are of 2 s samples inside the rung, so floors.
event_log_pressure_json() { # sweep_json pressure_csv -> JSON; reads STEP_S through sweep_rung_windows
  local rungs='[]'
  [ -s "$2" ] || { echo null; return 0; }
  [ ! -s "$1" ] || rungs="$(sweep_rung_windows "$1")"
  jq -Rnc --argjson rungs "${rungs:-[]}" '
    def num: if . == null or . == "" then null else (tonumber? // null) end;
    def grown: map(select(. != null)) | if length < 2 then null
      else [range(1; length) as $i | if .[$i] >= .[$i - 1] then .[$i] - .[$i - 1] else .[$i] end] | add end;
    def peak: map(select(. != null)) | max;
    def frac($p; $c): if $p == null or $c == null or $c <= 0 then null else ($p / $c * 10000 | round / 10000) end;
    def first_rps(f): (map(select((f // 0) > 0)) | .[0].offered_rps) // null;
    [inputs | split(",") | select(.[0] != "ts") | {ts: (.[0] | num), ring_full: (.[1] | num), in_flight: (.[2] | num),
       dropped: (.[3] | num), occ: (.[4] | num), cap: (.[5] | num), inflt: (.[6] | num), maxinflt: (.[7] | num)}
     | select(.ts != null)] as $rows
    | if ($rows | length) == 0 then null else
      ($rows | any(.ring_full != null or .in_flight != null)) as $split
      | ($rows | length) as $n
      | [range(0; $rungs | length) as $i | $rungs[$i] | (.start_epoch_ms / 1000) as $lo | (.end_epoch_ms / 1000) as $hi | .offered_rps as $rps
         | [$rows[] | select(.ts > $lo and .ts <= $hi)] as $in
         | (([range(0; $n) | select($rows[.].ts <= $lo)] | last) // ([range(0; $n) | select($rows[.].ts > $lo)] | first)) as $from
         | (if $i + 1 < ($rungs | length) then ($rungs[$i + 1].start_epoch_ms / 1000) as $next | [range(0; $n) | select($rows[.].ts <= $next)] | last
            else $n - 1 end) as $to
         | (if $from == null or $to == null or $to < $from then [] else $rows[$from:$to + 1] end) as $span
         | {offered_rps: $rps, samples: ($in | length),
            dropped_log_events: ($span | map(.dropped) | grown),
            dropped_by_reason: (if $split then {ring_full: ($span | map(.ring_full) | grown),
                                                in_flight_bytes: ($span | map(.in_flight) | grown)} else null end),
            peak_ring_occupancy: ($in | map(.occ) | peak), peak_in_flight_bytes: ($in | map(.inflt) | peak)}] as $per
      | ($rows | map(.occ) | peak) as $pocc | ($rows | map(.cap) | peak) as $cap
      | ($rows | map(.inflt) | peak) as $pinf | ($rows | map(.maxinflt) | peak) as $maxinf
      | {sample_interval_s: 2, samples: ($rows | length), reason_split: $split,
         dropped_log_events: ([{dropped: 0}] + $rows | map(.dropped) | grown),
         dropped_by_reason: (if $split then {ring_full: ([{ring_full: 0}] + $rows | map(.ring_full) | grown),
                                             in_flight_bytes: ([{in_flight: 0}] + $rows | map(.in_flight) | grown)} else null end),
         peak_ring_occupancy: $pocc, ring_capacity: $cap, peak_ring_utilisation: frac($pocc; $cap),
         peak_in_flight_bytes: $pinf, max_in_flight_bytes: $maxinf, peak_in_flight_utilisation: frac($pinf; $maxinf),
         first_drop_rung_rps: ($per | first_rps(.dropped_log_events)),
         first_drop_rung_rps_by_reason: (if $split then {ring_full: ($per | first_rps(.dropped_by_reason.ring_full)),
                                                         in_flight_bytes: ($per | first_rps(.dropped_by_reason.in_flight_bytes))} else null end),
         rungs: $per} end' "$2" 2>/dev/null || echo null
}
# event_log_budget for the INFO arm record; empty arguments become null. bound_reached is null when
# evictions are unknown and no utilisation reaches the ratio. binding is null without utilisations,
# else "neither" when no bound was reached (as in event_log_scaling), else the bound nearer its limit.
event_log_budget_json() { # method requested requested_src max_bytes max_entries heap expected peak_bytes peak_entries dropped evicted ratio
  jq -nc --arg method "$1" --arg requested "$2" --arg requested_src "$3" --arg mb "$4" --arg me "$5" \
    --arg heap "$6" --arg expected "$7" --arg pb "$8" --arg pe "$9" --arg dropped "${10}" --arg ev "${11}" \
    --argjson ratio "${12}" '
    def num: if . == "" then null else (tonumber? // null) end;
    ($mb | num) as $mb | ($me | num) as $me | ($pb | num) as $pb | ($pe | num) as $pe | ($ev | num) as $ev
    | (if $mb != null and $mb > 0 and $pb != null then $pb / $mb else null end) as $bu
    | (if $me != null and $me > 0 and $pe != null then $pe / $me else null end) as $cu
    | (if ($ev // 0) > 0 or ($bu // 0) >= $ratio or ($cu // 0) >= $ratio then true
       elif $ev == null then null else false end) as $reached
    | {
        method: $method,
        requested_bytes: ($requested | num),
        requested_source: $requested_src,
        resolved_max_event_log_bytes: $mb,
        resolved_max_log_entries: $me,
        resolved_source: (if $mb != null then "observed" else "unavailable" end),
        heap_max_bytes: ($heap | num),
        expected_default_bytes: ($expected | num),
        peak_retained_bytes: $pb,
        peak_retained_entries: $pe,
        bytes_utilisation: (if $bu == null then null else ($bu * 10000 | round / 10000) end),
        count_utilisation: (if $cu == null then null else ($cu * 10000 | round / 10000) end),
        evicted_log_entries: $ev,
        dropped_log_events: ($dropped | num),
        bound_reached: $reached,
        binding: (if $reached == null or ($bu == null and $cu == null) then null
                  elif $reached == false then "neither"
                  elif ($cu // 0) >= ($bu // 0) then "count" else "bytes" end)
      }' 2>/dev/null || echo null
}
# One env var as the SUT container ACTUALLY received it (empty if unset).
# NOTE this is a WEAKER observation than gc/heap_max_bytes, which are read from the JVM's own
# metrics endpoint. This only proves the container was HANDED the value, not that the JVM parsed
# and applied it - hence the config block labels it container-env rather than observed. The
# resolved budgets are read from the gauges separately (event_log_scaling, event_log_budget).
container_env() { # VAR_NAME [container_name=$SERVER]
  # Defaults to the ERROR baseline SUT so every existing caller is unchanged; the
  # INFO publication arm passes its own container name to read that SUT's log level.
  local cname="${2:-$SERVER}"
  docker inspect --format '{{range .Config.Env}}{{println .}}{{end}}' "$cname" 2>/dev/null \
    | awk -F= -v k="$1" '$1==k{sub("^[^=]*=",""); print; exit}' || true
}

MS_VERSION="$(metric_label mock_server_build_info version)"
MS_GIT_HASH="$(metric_label mock_server_build_info git_hash)"
JDK_BUILD="$(metric_label jvm_runtime_info java_runtime_version)"
JAVA_VENDOR="$(metric_label jvm_runtime_info java_vendor)"
VM_NAME="$(metric_label jvm_runtime_info vm_name)"
GC_IN_USE="$(metric_label jvm_runtime_info gc)"
HEAP_MAX_BYTES="$(metric_heap_max)"
JVM_RUNTIME_SRC="observed"

# Release comparison only: a release older than jvm_runtime_info reports JDK and GC
# through jcmd on the running SUT (GC as the -XX:+Use*GC flag, source observed-jcmd).
# Anything still unreadable falls through to the fail-closed check below.
release_jvm_runtime_via_jcmd() {
  local uid gid jcmd_img="$PERF_HISTO_JDK_IMAGE" props flags tmo=()
  command -v timeout >/dev/null 2>&1 && tmo=(timeout 60)
  read -r uid gid <<<"$(sut_jvm_uid_gid "$SERVER")"
  grep -qE '^[0-9]+$' <<<"$uid" || { echo "WARNING: release comparison: could not resolve the SUT JVM uid for jcmd" >&2; return 0; }
  props="$(${tmo[@]+"${tmo[@]}"} docker run --rm --pid="container:$SERVER" --user "${uid}:${gid}" "$jcmd_img" jcmd 1 VM.system_properties 2>/dev/null || true)"
  flags="$(${tmo[@]+"${tmo[@]}"} docker run --rm --pid="container:$SERVER" --user "${uid}:${gid}" "$jcmd_img" jcmd 1 VM.flags 2>/dev/null || true)"
  jcmd_prop() { printf '%s\n' "$props" | awk -v k="$1" 'index($0, k "=") == 1 { sub("^[^=]*=", ""); gsub(/\\/, ""); print; exit }' || true; }
  [ -n "$JDK_BUILD" ] || JDK_BUILD="$(jcmd_prop java.runtime.version)"
  [ -n "$JAVA_VENDOR" ] || JAVA_VENDOR="$(jcmd_prop java.vendor)"
  [ -n "$VM_NAME" ] || VM_NAME="$(jcmd_prop java.vm.name)"
  [ -n "$GC_IN_USE" ] || GC_IN_USE="$(printf '%s' "$flags" | grep -oE -- '-XX:\+Use[A-Za-z0-9]+GC' | head -1 || true)"
  JVM_RUNTIME_SRC="observed-jcmd"
  echo "--- release comparison: JDK/GC read from the running SUT via jcmd (image predates jvm_runtime_info): jdk='${JDK_BUILD}' vendor='${JAVA_VENDOR}' gc='${GC_IN_USE}'"
}
if [ -n "$PERF_RELEASE_COMPARISON" ] && { [ -z "$JDK_BUILD" ] || [ -z "$GC_IN_USE" ]; }; then
  release_jvm_runtime_via_jcmd
fi
# The immutable content id the SUT image actually resolved to (RepoDigest), or the
# local image id when built without a digest (locally-built image). RepoDigests is
# an IMAGE property and does NOT exist on a container, so we must resolve the
# container's image id first (.Image) and inspect the IMAGE — NOT the container.
# Inspecting the container reads a key that is absent there: older Docker CLIs
# (missingkey=invalid) rendered that as empty and silently fell through to the
# container's .Image (the image id, never the RepoDigest), but Docker 29.x
# (missingkey=error) makes the missing key a hard template error, so the value
# came back empty and tripped the fail-closed guard below for a digest that is in
# fact perfectly resolvable. On the image, RepoDigests is always a present key
# (an empty list for a locally-built image), so the {{if}} degrades cleanly to the
# documented .Id fallback rather than erroring. Only a genuine inability to inspect
# the image at all leaves this empty — which is meant to fail closed.
#
# HISTORY: schema_version 2 runs stored BEFORE this fix carry a bare image-config
# ID here, not a repo digest, labelled sources.image_digest="observed". They are
# wrong rather than absent, so do not read a pre-fix image_digest as provenance.
# The failure direction is safe: the field is display-only in the compare step
# today, and a bare-ID-vs-digest mismatch can only SUPPRESS the planned ratchet
# (which requires an identical config across runs), never falsely tighten a budget.
SERVER_IMAGE_ID="$(docker inspect --format '{{.Image}}' "$SERVER" 2>/dev/null || true)"
IMAGE_DIGEST="$(docker image inspect --format '{{if .RepoDigests}}{{index .RepoDigests 0}}{{else}}{{.Id}}{{end}}' "${SERVER_IMAGE_ID:-$MOCKSERVER_IMAGE}" 2>/dev/null || true)"
# Source-provenance label off the SUT IMAGE (org.opencontainers.image.revision =
# the commit the image was BUILT from — stamped by java-docker-push-snapshot.sh).
# `index` on the Labels MAP returns the zero value ("") for a missing key, so this
# is safe under Docker 29.x missingkey=error: that strictness applies to struct
# FIELD access (.Foo on a missing key), not to `index` on a map — an image built
# before the label shipped yields empty here, not a hard template error. Read from
# the IMAGE (labels are an image property, absent on a container) via the resolved
# image id, exactly as the digest above. Empty == absent for the provenance check.
IMAGE_REVISION="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "${SERVER_IMAGE_ID:-$MOCKSERVER_IMAGE}" 2>/dev/null || true)"
# The image's own BUILD TIMESTAMP (.Created, RFC3339Nano). Read from the same IMAGE
# inspect as the digest/revision. Used by the image-freshness check below to detect a
# FROZEN snapshot tag — a snapshot pipeline that has stopped rebuilding the mutable tag,
# which would otherwise be measured as an identical binary every day with provenance_ok
# reading true forever (a silent green measuring nothing new). `.Created` is a struct
# field always present on a real image; empty only if the image cannot be inspected at
# all (which the digest fail-closed guard above already catches).
IMAGE_CREATED="$(docker image inspect --format '{{.Created}}' "${SERVER_IMAGE_ID:-$MOCKSERVER_IMAGE}" 2>/dev/null || true)"
# Log level + system-out suppression are BOTH non-defaults every CI perf run sets,
# and neither was ever recorded. Read them off the container that ran (observed);
# only if absent there fall back to the shell env we exported (declared).
LOG_LEVEL_VAL="$(container_env MOCKSERVER_LOG_LEVEL)"; LOG_LEVEL_SRC="observed"
[ -n "$LOG_LEVEL_VAL" ] || { LOG_LEVEL_VAL="${MOCKSERVER_LOG_LEVEL:-}"; LOG_LEVEL_SRC="declared"; }
DISABLE_SYSOUT_VAL="$(container_env MOCKSERVER_DISABLE_SYSTEM_OUT)"; DISABLE_SYSOUT_SRC="observed"
[ -n "$DISABLE_SYSOUT_VAL" ] || { DISABLE_SYSOUT_VAL="${MOCKSERVER_DISABLE_SYSTEM_OUT:-}"; DISABLE_SYSOUT_SRC="declared"; }
# JAVA_TOOL_OPTIONS is legitimately absent when PERF_SERVER_JAVA_OPTS is unset — an
# empty OBSERVED value here is a true fact (no JVM opts), not a masked placeholder.
JAVA_TOOL_OPTS_VAL="$(container_env JAVA_TOOL_OPTIONS)"
# The budget env the SUT ACTUALLY received (empty at the shipped default) and the budget its JVM
# resolved, from its own gauges; main_els_guard below fails closed on either being wrong, since a run
# without a byte bound is at the build-#249 OOM risk.
MAX_EVENT_LOG_VAL="$(container_env MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES)"
MAIN_ELS_RESOLVED_BYTES="$(gauge_value mock_server_event_log_max_retained_bytes <<<"$CONFIG_METRICS")"
MAIN_ELS_RESOLVED_ENTRIES="$(gauge_value mock_server_event_log_max_retained_entries <<<"$CONFIG_METRICS")"
MAIN_ELS_RESOLVED_IN_FLIGHT="$(gauge_value mock_server_event_log_max_in_flight_bytes <<<"$CONFIG_METRICS")"
# Accept-queue depth the SUT actually received. Absent is normal (shipped default), so
# empty is not an error — but declared-yet-unapplied is, since the run would be labelled
# tuned while measuring a default server.
SO_BACKLOG_VAL="$(container_env MOCKSERVER_SO_BACKLOG)"
# ANY tuning lever flips the profile to "tuned" so a non-default run can never poison the baseline.
# Keyed on whether the user-facing PERF_* env var was SET (not on the resolved SERVER_MEMORY, which
# always holds a default, or PERF_MAX_EVENT_LOG_BYTES, which a release comparison fills), so the daily guard-dispatched run — which
# sets none of these — stays "default", while a run that raises the heap, changes the GC, resizes the
# log budget, sets the backlog, or opts into the large-heap profile is correctly excluded.
# A cpuset override is rig TOPOLOGY, not server configuration, so it is tracked
# separately and reported as its own reason - but it must still exclude the run from the
# baseline. How many cores the server gets is the single largest determinant of
# throughput, so a 2-core cell persisted into the default-configuration series would drag
# its rolling median down and read as a regression on every later 6-core run. Keyed on
# whether the var was SET, because the resolved cpuset always holds a default.
RIG_PROFILE="default"
if [ -n "${PERF_SERVER_CPUS:-}" ] \
   || [ -n "${PERF_UPSTREAM_CPUS:-}" ] \
   || [ -n "${PERF_K6_CPUS:-}" ] \
   || [ "${PERF_CLUSTERED_CPUS:-2}" != 2 ] || [ "${PERF_CLUSTERED_PIN:-true}" != true ] \
   || [ -n "${PERF_CLUSTERED_CTRL_CPUS:-}${PERF_CLUSTERED_A_CPUS:-}${PERF_CLUSTERED_B_CPUS:-}" ]; then
  RIG_PROFILE="pinned"
fi
# host networking is rig TOPOLOGY (a different data path), so — like a cpuset
# override — it excludes the run from the baseline via rig_profile != default.
if [ "$PERF_NETWORK_MODE" = host ]; then
  RIG_PROFILE="host-network"
fi

CONFIG_PROFILE="default"
if [ -n "${PERF_SO_BACKLOG:-}" ] \
   || [ -n "${PERF_SERVER_JAVA_OPTS:-}" ] \
   || [ -n "${PERF_SERVER_MEMORY:-}" ] \
   || [ -n "${PERF_MAX_EVENT_LOG_BYTES_SET:-}" ] \
   || [ -n "${PERF_INFO_MAX_EVENT_LOG_BYTES:-}" ] \
   || [ "$PERF_LARGE_HEAP_PROFILE" = "true" ]; then
  CONFIG_PROFILE="tuned"
fi
# An opt-in workload measures a DIFFERENT path (slow-upstream proxy concurrency)
# than the default curve, so it must never be persisted to the default-configuration
# baseline. Label it distinctly (not just "tuned") so a reader can see WHICH workload
# the result describes; config_profile != default flips baseline_eligible below
# exactly as a tuned run does.
if [ -n "$PERF_WORKLOAD" ]; then
  CONFIG_PROFILE="workload-${PERF_WORKLOAD}"
fi
# The steady-state pass measures a DIFFERENT load shape (one fixed rate, no ladder)
# than the published curve, so it must never be persisted to the baseline. Labelled
# distinctly (as workload is) so a reader sees which experiment the result describes;
# flips baseline_eligible below exactly as a tuned run does.
if [ -n "$PERF_STEADY_RATE" ]; then
  CONFIG_PROFILE="steady-${PERF_STEADY_RATE}"
fi
if [ -n "$PERF_RELEASE_COMPARISON" ]; then
  CONFIG_PROFILE="release-${PERF_RELEASE_COMPARISON}"
fi
# k6 image digest is pinned in the K6_IMAGE ref itself (…@sha256:…).
K6_IMAGE_DIGEST="$(printf '%s' "$K6_IMAGE" | sed -nE 's/.*@(sha256:[0-9a-f]+)$/\1/p')"
# k6 container CPU allocation (cores * 100%) from its cpuset pin.
K6_CFG_CORES="$(k6_core_count "${K6_CPUS:-}")"; K6_CFG_PIN_PCT=$((K6_CFG_CORES * 100))

# Fail closed: an unrecordable MANDATORY value must abort the step, not become a
# placeholder that outlives review. GC + JDK come from jvm_runtime_info, which a
# MockServer image built before that metric shipped will not expose — that is a
# genuine "cannot record what this run was" and is meant to fail here.
CONFIG_ERRORS=()
[ -n "$MS_VERSION" ]        || CONFIG_ERRORS+=("mockserver version (mock_server_build_info{version}) not readable from ${SERVER_METRICS_URL}")
[ -n "$IMAGE_DIGEST" ]      || CONFIG_ERRORS+=("image digest not resolvable — 'docker image inspect' of ${SERVER}'s image (${SERVER_IMAGE_ID:-<unresolved>}) yielded no RepoDigest or .Id")
[ -n "$JDK_BUILD" ]        || CONFIG_ERRORS+=("JDK build (jvm_runtime_info{java_runtime_version}) not readable — image predates the jvm_runtime_info metric?")
[ -n "$GC_IN_USE" ]        || CONFIG_ERRORS+=("GC in use (jvm_runtime_info{gc}) not readable — image predates the jvm_runtime_info metric?")
if [ -n "$PERF_RELEASE_COMPARISON" ] && [ "$MS_VERSION" != "$PERF_RELEASE_COMPARISON" ]; then
  CONFIG_ERRORS+=("PERF_RELEASE_COMPARISON=${PERF_RELEASE_COMPARISON} but the running SUT reports version '${MS_VERSION}' (${MOCKSERVER_IMAGE}) — the result would be labelled as a release it did not measure")
fi
awk -v v="$HEAP_MAX_BYTES" 'BEGIN{exit !(v+0>0)}' || CONFIG_ERRORS+=("resolved heap (jvm_memory_max_bytes{area=\"heap\"}) not a positive value: '${HEAP_MAX_BYTES}'")
[ -n "$LOG_LEVEL_VAL" ]     || CONFIG_ERRORS+=("MOCKSERVER_LOG_LEVEL not recordable (neither container env nor shell)")
[ -n "$DISABLE_SYSOUT_VAL" ] || CONFIG_ERRORS+=("MOCKSERVER_DISABLE_SYSTEM_OUT not recordable (neither container env nor shell)")
while IFS= read -r ELS_GUARD_ERROR; do
  [ -z "$ELS_GUARD_ERROR" ] || CONFIG_ERRORS+=("$ELS_GUARD_ERROR")
done <<<"$(main_els_guard "$PERF_MAX_EVENT_LOG_BYTES" "$MAX_EVENT_LOG_VAL" "$MAIN_ELS_RESOLVED_BYTES")"
if [ -n "$PERF_SO_BACKLOG" ]; then
  awk -v v="$PERF_SO_BACKLOG" 'BEGIN{exit !(v ~ /^[0-9]+$/ && v+0>0)}' \
    || CONFIG_ERRORS+=("PERF_SO_BACKLOG='${PERF_SO_BACKLOG}' is not a positive integer")
  [ "$SO_BACKLOG_VAL" = "$PERF_SO_BACKLOG" ] \
    || CONFIG_ERRORS+=("PERF_SO_BACKLOG=${PERF_SO_BACKLOG} was declared but the SUT container reports MOCKSERVER_SO_BACKLOG='${SO_BACKLOG_VAL}' — this run would be labelled 'tuned' while measuring something else")
fi
if [ "${#CONFIG_ERRORS[@]}" -gt 0 ]; then
  echo "ERROR: run configuration is not fully recordable — refusing to emit a result that misrepresents what it measured:" >&2
  printf '  - %s\n' "${CONFIG_ERRORS[@]}" >&2
  echo "(A populated-but-wrong config field survives review; an absent one does not — see the instance_type:\"\" bug.)" >&2
  exit 1
fi

# Fail closed: a default-profile figure MUST be measured on the image's shipped GC. The SUT's
# always-on diagnostics opts replace JAVA_TOOL_OPTIONS, so start_mockserver re-prepends the image
# default's GC selector; verify the running JVM actually reports it. Skipped when the image sets none.
if [ "$CONFIG_PROFILE" = "default" ]; then
  _img_gc_flag="$(printf '%s' "$SUT_IMAGE_JAVA_TOOL_OPTIONS" | grep -oiE -- '-XX:\+Use[A-Za-z0-9]+GC' | head -1 || true)"
  if [ -n "$_img_gc_flag" ]; then
    case "$_img_gc_flag" in
      *ZGC) _expected_gc="ZGC" ;;
      *G1GC) _expected_gc="G1" ;;
      *ShenandoahGC) _expected_gc="Shenandoah" ;;
      *ParallelGC) _expected_gc="PS " ;;
      *SerialGC) _expected_gc="MarkSweepCompact" ;;
      *) _expected_gc="$(printf '%s' "$_img_gc_flag" | sed -E 's/.*Use([A-Za-z0-9]+)GC/\1/')" ;;
    esac
    if ! grep -qi -- "$_expected_gc" <<<"$GC_IN_USE"; then
      echo "ERROR: default-profile run measured GC '${GC_IN_USE}' but the image default (${_img_gc_flag}) expects '${_expected_gc}' — a default figure on the wrong collector must not be published" >&2
      exit 1
    fi
  fi
fi

# --- source-provenance verdict (parts A/B): can this run be attributed, and to
# --- WHICH commit? -------------------------------------------------------------
# A performance result describes the BINARY THAT WAS MEASURED, not this checkout of
# the harness scripts. So the result is FILED UNDER (and keyed in stored history by)
# the SUT image's OWN commit — its org.opencontainers.image.revision label — whenever
# that label is a trustworthy, well-formed git SHA (ATTRIBUTED_COMMIT below). The
# Buildkite build's commit (this checkout of the perf scripts) is recorded SEPARATELY
# as HARNESS_COMMIT: if the harness changed, the measurement METHOD changed and a
# reader must see both. This deliberately STOPS treating image-lag — a snapshot tag
# that trails master (mockserver-java rebuilds it only for its own paths) — as a failure: a lagging image was
# measured correctly and is attributable to the commit it was built from, which is
# exactly what this result now records. A forced master run is the exception: it is refused at
# the image pull unless the harness commit is served this image (snapshot_equivalent).
# Three outcomes, and provenance STILL fails
# closed in the ones that matter:
#   well-formed label -> provenance_ok=true : ATTRIBUTED_COMMIT = the image revision;
#               attributable; the validity check passes.
#   malformed  label -> provenance_ok=false: the label is present but is NOT a 40-hex
#               git SHA (empty-vs-garbage: an EMPTY label is absence, not garbage, and
#               takes the grace path; a non-empty non-SHA is HOSTILE). The attribution
#               key cannot be trusted, so the run is NOT attributable — recorded as a
#               FAILED validity check so perf-test-compare.sh refuses to persist or
#               compare it AND reds the build, REUSING the existing validity gate. This
#               is the reachable fail state after this change: it fires when the image
#               labeller regresses (a broken SOURCE_COMMIT stamp), regardless of date.
#   absent     -> provenance_ok=null : the image predates the label — TRUE of every
#               cached image until the next labelled snapshot ships. UNCHANGED from
#               before: an absent label PASSES until the time-bounded grace cutoff
#               (PERF_PROVENANCE_GRACE_UNTIL), after which it fails closed (the stamping
#               regressed); a malformed cutoff fails closed. During the grace window the
#               result is filed under HARNESS_COMMIT, exactly as pre-label behaviour.
# HARNESS_COMMIT is resolved beside the SUT image pull, where a forced run checks it against the image.
# A genuine revision label is stamped from `git rev-parse HEAD` at image-build time
# (java-docker-push-snapshot.sh -> SOURCE_COMMIT -> the Dockerfile LABEL), so it is a
# 40-char lowercase-hex SHA. Validate that SHAPE and treat any NON-EMPTY value that is
# not a 40-hex SHA as HOSTILE — filing a result under an unverifiable key is worse than
# not filing it. An EMPTY label is absence (Docker's `index` yields "" for a missing
# key), NOT garbage, and takes the grace path below unchanged.
IMAGE_REVISION_WELLFORMED=false
# Use bash `[[ =~ ]]`, NOT `grep -qE`: grep anchors ^/$ per LINE, so a label carrying
# an embedded newline (<40-hex>\nextra) would match on its first line and slip through
# as "well-formed", after which the multi-line value would be filed as .commit. bash's
# `=~` anchors the WHOLE string, so a mangled multi-line label is rejected — the same
# whole-string guard the instance_type check below relies on for the same reason.
if [[ "$IMAGE_REVISION" =~ ^[0-9a-f]{40}$ ]]; then IMAGE_REVISION_WELLFORMED=true; fi
# Default attribution = the harness commit, used ONLY when the binary's own revision is
# unavailable (absent label, grace-tolerated) or untrustworthy (malformed label — not
# baselined anyway). This preserves pre-label behaviour EXACTLY: during the grace window
# a result is still filed under the harness commit, as it was before this change.
ATTRIBUTED_COMMIT="$HARNESS_COMMIT"; ATTRIBUTED_SRC="declared"
# ISO YYYY-MM-DD compares correctly with lexical `>` (zero-padded => lexical order
# IS chronological order); this is a genuine date comparison, not the `<= "0.1"`
# numeric-vs-string trap — both operands are date strings and the operator is the
# string operator inside [[ ]]. BUT the lexical comparison is only trustworthy if
# the cutoff is a REAL date: an unvalidated override is a fail-OPEN deadline. A
# malformed value (foo/never/tomorrow) sorts below every real date, and — the trap
# a bare `^[0-9]{4}-[0-9]{2}-[0-9]{2}$` check would MISS — so does 9999-99-99
# (format-valid, semantically impossible), so `today > grace` is false forever and
# the tolerated window silently never closes. Same shape as the quoted perf-budget
# floor (`number <= "0.1"` unconditionally true) fixed earlier today. So validate
# real ISO components (month 01-12, day 01-31, which rejects 99-99 and 00-00) and
# fail CLOSED on any non-match — a typo can never disable the deadline; a deliberate
# extension must be a well-formed future date.
PROVENANCE_GRACE_UNTIL="${PERF_PROVENANCE_GRACE_UNTIL:-2026-11-01}"
GRACE_VALID=true
if ! grep -qE '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$' <<<"$PROVENANCE_GRACE_UNTIL"; then
  GRACE_VALID=false
  echo "WARNING: PERF_PROVENANCE_GRACE_UNTIL='${PROVENANCE_GRACE_UNTIL}' is not a valid ISO YYYY-MM-DD date — treating the provenance grace window as EXPIRED (fail closed) so a malformed override cannot silently disable the deadline" >&2
fi
TODAY_UTC="$(date -u +%Y-%m-%d)"
if [ -n "$IMAGE_REVISION" ]; then
  if [ "$IMAGE_REVISION_WELLFORMED" = true ]; then
    # ATTRIBUTABLE: file the result under the measured binary's own commit.
    PROVENANCE_OK="true"
    ATTRIBUTED_COMMIT="$IMAGE_REVISION"; ATTRIBUTED_SRC="observed"
    echo "--- provenance: OK — SUT image carries a well-formed revision label; result is filed under the measured binary's commit (${IMAGE_REVISION:0:10}); harness commit ${HARNESS_COMMIT:0:10}"
    add_check "source_provenance" true "SUT image revision (${IMAGE_REVISION:0:10}) is a well-formed git SHA — result is attributed to, and keyed in history by, the measured binary's own commit; the harness (build) commit ${HARNESS_COMMIT:0:10} is recorded separately as harness_commit"
  else
    # HOSTILE label: present but not a 40-hex SHA — the attribution key cannot be
    # trusted, so the run is NOT attributable. Reddens via the validity gate exactly
    # as an equality mismatch did before; this is the reachable fail state now.
    PROVENANCE_OK="false"
    echo "--- provenance: MALFORMED — SUT image revision label '${IMAGE_REVISION}' is not a 40-hex git SHA; the attribution key is untrustworthy so this run is NOT attributable and will not be baselined" >&2
    add_check "source_provenance" false "SUT image org.opencontainers.image.revision label ('${IMAGE_REVISION}') is present but is NOT a well-formed 40-hex git SHA — the commit the result would be filed under cannot be trusted, so the run is not attributable and must not poison the baseline (the image labeller regressed: check SOURCE_COMMIT stamping in java-docker-push-snapshot.sh)"
  fi
else
  PROVENANCE_OK="null"
  if [ -n "$PERF_RELEASE_COMPARISON" ]; then
    # A release predates the label; it is identified by its version, verified above.
    echo "--- provenance: release comparison — ${MOCKSERVER_IMAGE} carries no revision label; identified by its verified version ${MS_VERSION} instead"
    add_check "source_provenance" true "release comparison: the SUT is the published ${PERF_RELEASE_COMPARISON} image, which carries no org.opencontainers.image.revision label; it is identified by the version the running JVM reports (${MS_VERSION}) and the result is never baselined (config_profile=${CONFIG_PROFILE})"
  elif [ "$GRACE_VALID" != true ]; then
    echo "--- provenance: UNVERIFIED and grace cutoff MALFORMED ('${PROVENANCE_GRACE_UNTIL}') — SUT image carries no revision label; failing closed" >&2
    add_check "source_provenance" false "SUT image carries no org.opencontainers.image.revision label AND the provenance grace cutoff PERF_PROVENANCE_GRACE_UNTIL='${PROVENANCE_GRACE_UNTIL}' is not a valid ISO YYYY-MM-DD date — failing closed so a malformed override cannot silently keep the grace window open forever (set a well-formed future date to extend deliberately)"
  elif [[ "$TODAY_UTC" > "$PROVENANCE_GRACE_UNTIL" ]]; then
    echo "--- provenance: UNVERIFIED and grace window CLOSED (${PROVENANCE_GRACE_UNTIL}) — SUT image carries no revision label; failing closed" >&2
    add_check "source_provenance" false "SUT image carries no org.opencontainers.image.revision label AND the provenance grace window closed on ${PROVENANCE_GRACE_UNTIL} — every snapshot built after the labelling change should carry it, so an absent label now means the stamping regressed (set PERF_PROVENANCE_GRACE_UNTIL to extend deliberately)"
  else
    echo "--- provenance: UNVERIFIED (tolerated until ${PROVENANCE_GRACE_UNTIL}) — SUT image carries no revision label yet (pre-label/cached image)"
    add_check "source_provenance" true "SUT image carries no org.opencontainers.image.revision label yet (pre-label/cached image) — provenance UNVERIFIED but tolerated until the grace cutoff ${PROVENANCE_GRACE_UNTIL}, by when a labelled snapshot will have shipped on a master merge"
  fi
fi

# --- baseline eligibility: keep an INSTRUMENTED run out of the baseline
# PERF_JVM_DIAGNOSTICS=deep (GC file log + NMT + JFR) and =gc (GC file log only) add diagnostics
# that cost throughput BY DESIGN, so a deep run that completes must be RECORDED but
# NOT persisted into the baseline history — otherwise it silently shifts the series,
# the exact contamination the tier split was created to prevent (as gc/jdk/heap were
# already recorded but the tier never was). This is DISTINCT from validity: a deep
# run is a VALID measurement of an instrumented server, not a broken rig, so it must
# stay GREEN (the investigation run was triggered on purpose) — hence a separate
# baseline_eligible flag consumed by compare, NOT a validity check (which would red
# the build). Defensive: ineligible if the DECLARED tier is not standard OR the SUT's
# OBSERVED JAVA_TOOL_OPTIONS actually carries tier-2 instrumentation — the safe
# direction is to over-exclude, never to contaminate the baseline.
BASELINE_ELIGIBLE="true"
# Why the run is not eligible, one token per cause, recorded as .baseline_ineligible_reasons. Compare
# persists a perf-xl arm-only result only when its sole reason is arm_only.
INELIGIBLE_REASONS=""
mark_ineligible() { BASELINE_ELIGIBLE="false"; INELIGIBLE_REASONS="${INELIGIBLE_REASONS} $1"; }
OBSERVED_INSTRUMENTATION="$(grep -o 'StartFlightRecording\|NativeMemoryTracking\|Xlog:gc' <<<"$JAVA_TOOL_OPTS_VAL" | sort -u | paste -sd, - || true)"
if [ "$PERF_JVM_DIAGNOSTICS" != "standard" ] || [ -n "$OBSERVED_INSTRUMENTATION" ]; then
  mark_ineligible jvm_diagnostics
  echo "--- baseline eligibility: NOT eligible (PERF_JVM_DIAGNOSTICS=$PERF_JVM_DIAGNOSTICS${OBSERVED_INSTRUMENTATION:+, SUT JAVA_TOOL_OPTIONS carries $OBSERVED_INSTRUMENTATION}) — this run will be recorded but NOT persisted to the baseline"
fi
# Same reasoning for a TUNED server: a valid measurement, so green, but the baseline
# series tracks the shipped default and a tuned point would raise its rolling median.
if [ "$CONFIG_PROFILE" != "default" ]; then
  mark_ineligible config_profile
  echo "--- baseline eligibility: NOT eligible (config_profile=$CONFIG_PROFILE, soBacklog=$SO_BACKLOG_VAL) — a tuned run is recorded but NOT persisted to the default-configuration baseline"
fi
# The opt-in remote-write arm drives the SUT for several more minutes
# between the published sweep and growth, so a run carrying it is a trial.
if [ "${PERF_SERVING_RW_MULTIK6:-false}" = "true" ] && ! arm_only; then
  mark_ineligible rw_multik6_in_run
  echo "--- baseline eligibility: NOT eligible (PERF_SERVING_RW_MULTIK6=true) — the remote-write multi-k6 trial adds load before growth"
fi
if [ "$K6_NUMA_NODE" = "same" ]; then
  mark_ineligible k6_numa_same
  echo "--- baseline eligibility: NOT eligible (PERF_K6_NUMA_NODE=same) — k6 on the SUT's socket is the interference A/B, not the daily series"
fi
if arm_only; then
  mark_ineligible arm_only
  echo "--- baseline eligibility: NOT eligible (PERF_RUN_ARM=$PERF_RUN_ARM) — an arm-only run measures one arm, not the daily series"
fi
if [ "$RIG_PROFILE" != "default" ]; then
  mark_ineligible rig_profile
  echo "--- baseline eligibility: NOT eligible (rig_profile=$RIG_PROFILE, cpusets server=${SERVER_CPUS} upstream=${UPSTREAM_CPUS} k6=${K6_CPUS}) — a run on a non-default cpuset measures different hardware and is NOT persisted to the baseline"
fi

# --- image freshness: detect a FROZEN snapshot tag (closes the frozen-image false green) ---
# The provenance fix above deliberately STOPS treating image-lag as a failure — a lagging
# image is truthfully filed under its own commit. But that removes the only alarm for a
# distinct failure mode it must not mask: a snapshot pipeline that has STOPPED. If
# java-docker-push-snapshot.sh (master-gated) goes red for a week, or the push breaks, the
# mutable tag freezes; the daily run then pulls the SAME binary every day, files every point
# under the same (truthfully-labelled) old commit, and provenance_ok reads true forever — a
# green chain measuring nothing new. The freshness watchdog (perf-baseline-freshness.sh) does
# NOT catch this: it asserts producer LIVENESS via the Buildkite API and, by its own comment,
# cannot detect "a scheduled build that goes GREEN while writing no fresh baseline" (the
# trigger queue has no perf-bucket S3 read to see the series freeze). So making it fatal is
# the PRODUCER's job, exactly as that watchdog states.
# The signal that needs no git history and no cross-run state: the image's OWN build age
# (.Created). When the snapshot factory is alive, every perf run (which only fires when master
# moved) measures a freshly-built image; a measured image older than the bound means the
# factory stopped. Emitted into the config block; perf-test-compare.sh reds the build on it
# (a stale image is a valid measurement of that binary, but NOT a valid daily-cadence data
# point, and green here would be undetectable per the watchdog gap above). FAIL CLOSED: a
# non-integer threshold, or an unparseable/absent .Created, is treated as STALE — a freshness
# check that cannot date the image must not pass by default (the jq-ordering-trap lesson: guard
# the load-bearing property, not the easy one).
PERF_MAX_IMAGE_AGE_DAYS="${PERF_MAX_IMAGE_AGE_DAYS:-7}"
IMAGE_STALE="true"; IMAGE_AGE_DAYS="unknown"
# JSON-safe copy of the threshold for --argjson below: the real integer when valid, else
# `null` — a malformed override must fail the freshness check closed (IMAGE_STALE stays
# "true"), NOT crash the jq assembly with an unparseable --argjson and abort the whole run.
IMAGE_MAX_AGE_JSON="null"
if ! grep -qE '^[0-9]+$' <<<"$PERF_MAX_IMAGE_AGE_DAYS" || [ "$PERF_MAX_IMAGE_AGE_DAYS" -le 0 ] 2>/dev/null; then
  echo "WARNING: PERF_MAX_IMAGE_AGE_DAYS='${PERF_MAX_IMAGE_AGE_DAYS}' is not a positive integer — treating the SUT image as STALE (fail closed) so a malformed override cannot silently disable the freshness check" >&2
else
  IMAGE_MAX_AGE_JSON="$PERF_MAX_IMAGE_AGE_DAYS"
  # Portable RFC3339(Nano) -> epoch: GNU date (CI/Linux) first, then BSD date (local) after
  # trimming any timezone suffix and fractional seconds. Mirrors to_epoch() in
  # perf-baseline-freshness.sh. An unparseable/empty .Created leaves IMAGE_STALE=true.
  _img_epoch=""
  if [ -n "$IMAGE_CREATED" ]; then
    _img_epoch="$(date -u -d "$IMAGE_CREATED" +%s 2>/dev/null || true)"
    if [ -z "$_img_epoch" ]; then
      _trim="${IMAGE_CREATED%Z}"; _trim="${_trim%.*}"
      _img_epoch="$(date -u -j -f "%Y-%m-%dT%H:%M:%S" "$_trim" +%s 2>/dev/null || true)"
    fi
  fi
  if [ -n "$_img_epoch" ]; then
    IMAGE_AGE_DAYS=$(( ( $(date -u +%s) - _img_epoch ) / 86400 ))
    if [ -n "$PERF_RELEASE_COMPARISON" ]; then
      # Freshness guards the snapshot factory, which a release run does not measure.
      IMAGE_STALE="null"
      echo "--- image freshness: not evaluated for a release comparison (${MOCKSERVER_IMAGE} built ${IMAGE_AGE_DAYS}d ago)"
    # STRICTLY older than the bound is stale: an image built exactly PERF_MAX_IMAGE_AGE_DAYS
    # whole days ago is still within tolerance (a factory that is merely slow, not frozen).
    elif [ "$IMAGE_AGE_DAYS" -gt "$PERF_MAX_IMAGE_AGE_DAYS" ]; then
      IMAGE_STALE="true"
      echo "--- image freshness: STALE — SUT image built ${IMAGE_AGE_DAYS}d ago (${IMAGE_CREATED}), older than PERF_MAX_IMAGE_AGE_DAYS=${PERF_MAX_IMAGE_AGE_DAYS}; the snapshot pipeline may be frozen — compare will RED this run and not baseline it" >&2
    else
      IMAGE_STALE="false"
      echo "--- image freshness: OK — SUT image built ${IMAGE_AGE_DAYS}d ago (<= ${PERF_MAX_IMAGE_AGE_DAYS}d bound)"
    fi
  elif [ -z "$IMAGE_CREATED" ]; then
    echo "WARNING: SUT image .Created was empty ('docker image inspect' returned no build timestamp) — treating the image as STALE (fail closed); compare will RED this run" >&2
  else
    echo "WARNING: could not parse SUT image .Created ('${IMAGE_CREATED}') — treating the image as STALE (fail closed); compare will RED this run" >&2
  fi
fi

MAIN_ELS_JSON="$(main_els_json "$MAX_EVENT_LOG_VAL" "$MAIN_ELS_RESOLVED_BYTES" "$MAIN_ELS_RESOLVED_ENTRIES" "$MAIN_ELS_RESOLVED_IN_FLIGHT" "$HEAP_MAX_BYTES" "$LOG_LEVEL_VAL")"
jq -e . >/dev/null 2>&1 <<<"$MAIN_ELS_JSON" || MAIN_ELS_JSON="$(jq -nc --arg m "$(info_els_method "$MAX_EVENT_LOG_VAL")" '{method: $m}')"
CONFIG_JSON="$(jq -n \
  --arg version "$MS_VERSION" --arg git_hash "$MS_GIT_HASH" \
  --arg image "$MOCKSERVER_IMAGE" --arg image_digest "$IMAGE_DIGEST" \
  --arg image_revision "$IMAGE_REVISION" --arg commit_under_test "$ATTRIBUTED_COMMIT" --arg cut_src "$ATTRIBUTED_SRC" \
  --arg harness_commit "$HARNESS_COMMIT" \
  --arg image_created "$IMAGE_CREATED" --arg image_age_days "$IMAGE_AGE_DAYS" \
  --argjson image_max_age_days "$IMAGE_MAX_AGE_JSON" --arg image_stale "$IMAGE_STALE" \
  --arg provenance_ok "$PROVENANCE_OK" --arg provenance_grace_until "$PROVENANCE_GRACE_UNTIL" \
  --arg jvm_diagnostics "$PERF_JVM_DIAGNOSTICS" --arg jvm_runtime_src "$JVM_RUNTIME_SRC" \
  --arg release_comparison "$PERF_RELEASE_COMPARISON" \
  --arg jdk "$JDK_BUILD" --arg java_vendor "$JAVA_VENDOR" --arg vm_name "$VM_NAME" \
  --arg gc "$GC_IN_USE" --arg heap_max "$HEAP_MAX_BYTES" \
  --arg log_level "$LOG_LEVEL_VAL" --arg log_level_src "$LOG_LEVEL_SRC" \
  --arg disable_sysout "$DISABLE_SYSOUT_VAL" --arg disable_sysout_src "$DISABLE_SYSOUT_SRC" \
  --arg max_event_log "$MAX_EVENT_LOG_VAL" --argjson event_log_budget "$MAIN_ELS_JSON" \
  --arg so_backlog "$SO_BACKLOG_VAL" --arg config_profile "$CONFIG_PROFILE" --arg rig_profile "$RIG_PROFILE" \
  --arg jto "$JAVA_TOOL_OPTS_VAL" --arg psjo "${PERF_SERVER_JAVA_OPTS:-}" \
  --arg k6_image "$K6_IMAGE" --arg k6_digest "$K6_IMAGE_DIGEST" \
  --arg server_cpus "${SERVER_CPUS:-none}" --arg upstream_cpus "${UPSTREAM_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
  --argjson server_phys_cores "$SERVER_PHYS_CORES" --argjson upstream_phys_cores "$UPSTREAM_PHYS_CORES" --argjson k6_phys_cores "$K6_PHYS_CORES" \
  --argjson k6_pin_pct "$K6_CFG_PIN_PCT" \
  '{
    mockserver_version: $version,
    # mockserver_git_hash is the git commit of the RUNNING binary as the JVM reports it via
    # mock_server_build_info{git_hash}. It is DELIBERATELY not cross-checked against
    # image_revision: git_hash is git.commit.id.abbrev (abbrevLength=8 in mockserver-core
    # pom.xml — observed 8-hex, e.g. "412287f2"), whereas image_revision is a full 40-hex
    # OCI label. They are not the same format, so per the "only compare when both are
    # well-formed 40-hex; otherwise record both and check nothing" rule, both are recorded
    # and neither is normalised into the other — a prefix compare would manufacture false
    # reds.
    mockserver_git_hash: (if $git_hash=="" then null else $git_hash end),
    image: $image,
    image_digest: $image_digest,
    # Source provenance: which commit built the measured image (image_revision, from
    # the OCI label — null if absent), the commit this result is ATTRIBUTED to and keyed
    # in history by (commit_under_test == top-level .commit: the image revision when the
    # label is a well-formed SHA, else the harness commit during the grace window), the
    # separate Buildkite build / harness-scripts commit (harness_commit — the commit of
    # the measurement METHOD; if it changed, the method changed), and the verdict
    # (provenance_ok: "true"/"false"/"null"; null = image predates the label, tolerated
    # until provenance_grace_until; false = malformed label OR closed/malformed grace,
    # which also fails the validity block so compare refuses to baseline the run).
    image_revision: (if $image_revision=="" then null else $image_revision end),
    commit_under_test: $commit_under_test,
    harness_commit: $harness_commit,
    provenance_ok: (if $provenance_ok=="null" then null else ($provenance_ok=="true") end),
    provenance_grace_until: $provenance_grace_until,
    # Image freshness: the build timestamp of the measured image (image_created, .Created),
    # its age in whole days (image_age_days; null when .Created was unparseable), the bound
    # (image_max_age_days; null when the PERF_MAX_IMAGE_AGE_DAYS override was malformed), and
    # the verdict (image_stale). image_stale:true means the snapshot tag looks FROZEN — a
    # stale image is a valid measurement of that binary but NOT a fresh daily-cadence data
    # point, so perf-test-compare.sh reds the build on it and does not baseline it. Fails
    # closed: an unparseable .Created or a malformed threshold yields image_stale:true.
    image_created: (if $image_created=="" then null else $image_created end),
    image_age_days: (if $image_age_days=="unknown" then null else ($image_age_days|tonumber) end),
    image_max_age_days: $image_max_age_days,
    image_stale: (if $image_stale=="null" then null else ($image_stale=="true") end),
    # The release this run measured (PERF_RELEASE_COMPARISON), else null.
    release_comparison: (if $release_comparison=="" then null else $release_comparison end),
    # Diagnostics tier this run was measured under. tier 1 ("standard") is inert on a
    # healthy path; "deep" adds throughput-costing tier-2 instrumentation, which is why
    # a deep run is baseline_eligible:false (recorded, never persisted) at the top level.
    jvm_diagnostics: $jvm_diagnostics,
    jdk: $jdk, java_vendor: $java_vendor, vm_name: $vm_name,
    gc: $gc,
    heap_max_bytes: ($heap_max|tonumber),
    log_level: $log_level,
    disable_system_out: $disable_sysout,
    # null = no budget handed (shipped default); event_log_budget.method is the compare key.
    max_event_log_size_bytes: (if $max_event_log=="" then null else ($max_event_log|tonumber) end),
    event_log_budget: $event_log_budget,
    # null = shipped default in force; a number = this run was tuned.
    so_backlog: (if $so_backlog=="" then null else ($so_backlog|tonumber) end),
    config_profile: $config_profile,
    rig_profile: $rig_profile,
    java_tool_options: $jto,
    perf_server_java_opts: $psjo,
    k6_image: $k6_image,
    k6_image_digest: (if $k6_digest=="" then null else $k6_digest end),
    # cpusets are LOGICAL cpu ids; physical_cores is the DISTINCT physical-core count
    # each occupies (hyperthread siblings counted once), resolved from sysfs — this is
    # what a published "on N cores" figure must cite, not the vCPU-id span. null when
    # topology was unreadable (off-CI).
    cpusets: { server: $server_cpus, upstream: $upstream_cpus, k6: $k6_cpus,
               physical_cores: { server: $server_phys_cores, upstream: $upstream_phys_cores, k6: $k6_phys_cores } },
    k6_cpu_pin_pct: $k6_pin_pct,
    sources: {
      mockserver_version:"observed", mockserver_git_hash:"observed",
      image_digest:"observed",
      image_revision:"observed", commit_under_test:$cut_src, harness_commit:"declared", provenance_ok:"observed",
      image_created:"observed", image_age_days:"observed", image_max_age_days:"declared", image_stale:"observed",
      jvm_diagnostics:"declared",
      jdk:$jvm_runtime_src, java_vendor:$jvm_runtime_src, vm_name:$jvm_runtime_src,
      gc:$jvm_runtime_src, heap_max_bytes:"observed",
      log_level:$log_level_src, disable_system_out:$disable_sysout_src,
      max_event_log_size_bytes:"container-env",
      so_backlog:"container-env", config_profile:"declared", rig_profile:"declared",
      java_tool_options:"observed", perf_server_java_opts:"declared",
      k6_image_digest:"observed", cpusets:"declared", k6_cpu_pin_pct:"declared"
    }
  }')"
echo "--- config resolved: ${MS_VERSION} gc='${GC_IN_USE}' heap_max=${HEAP_MAX_BYTES} jdk='${JDK_BUILD}' log_level=${LOG_LEVEL_VAL} maxEventLogSizeInBytes=${MAX_EVENT_LOG_VAL:-<shipped default>}(resolved ${MAIN_ELS_RESOLVED_BYTES:-?}) soBacklog=${SO_BACKLOG_VAL:-<shipped default>} config_profile=${CONFIG_PROFILE} rig_profile=${RIG_PROFILE} provenance_ok=${PROVENANCE_OK} attributed=${ATTRIBUTED_COMMIT:0:10}(${ATTRIBUTED_SRC}) harness=${HARNESS_COMMIT:0:10} image_age_days=${IMAGE_AGE_DAYS} image_stale=${IMAGE_STALE} jvm_diagnostics=${PERF_JVM_DIAGNOSTICS} baseline_eligible=${BASELINE_ELIGIBLE} (schema_version=3)"

echo "--- seeding upstream /simple (forward target)"
# Fail closed: the whole run forwards through this expectation, so an unseeded upstream must abort
# HERE, after printing the upstream's post-mortem (the --rm-dropped container's .State + last logs).
# Prints "<http_code> <curl exit code> <curl error>", so a failure says WHY (exit 6 = unresolved
# host prints http_code 000); one cheap retry covers a transient.
seed_upstream() {
  docker run --rm "${NET_CLIENT[@]}" curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code} %{exitcode} %{errormsg}' -X PUT \
    "http://${UPSTREAM_HOSTPORT}/mockserver/expectation" -H 'Content-Type: application/json' \
    -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"upstream"},"times":{"unlimited":true}}]' || true
}
UPSTREAM_SEED_RESULT="$(seed_upstream)"
if ! grep -qE '^2[0-9][0-9]( |$)' <<<"$UPSTREAM_SEED_RESULT"; then
  echo "WARNING: upstream seed failed (http_code exitcode errormsg: '${UPSTREAM_SEED_RESULT}') — retrying once after 3s" >&2
  sleep 3
  UPSTREAM_SEED_RESULT="$(seed_upstream)"
fi
echo "upstream seed (http_code exitcode errormsg): ${UPSTREAM_SEED_RESULT}"
if ! grep -qE '^2[0-9][0-9]( |$)' <<<"$UPSTREAM_SEED_RESULT"; then
  echo "ERROR: upstream seeding failed (http_code exitcode errormsg: '${UPSTREAM_SEED_RESULT}') — capturing upstream post-mortem before aborting" >&2
  print_container_postmortem "$UPSTREAM"
  exit 1
fi

run_regression() {
  local proto="$1" base_url="$2" insecure="$3" out="$4"
  echo "--- regression.js ($proto)"
  # host mode: the forward arm's upstream is no longer resolvable by alias, so point
  # it at the host port; empty in bridge, keeping the command byte-identical.
  local fwd_env=(); [ "$PERF_NETWORK_MODE" = host ] && fwd_env=(-e "FORWARD_UPSTREAM_HOST=$UPSTREAM_HOSTPORT")
  # shellcheck disable=SC2046
  docker run --rm "${NET_CLIENT[@]}" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$base_url" \
    ${fwd_env[@]+"${fwd_env[@]}"} \
    -e "PROTO=$proto" \
    -e "INSECURE_SKIP_TLS_VERIFY=$insecure" \
    -e "K6_RESULT_PATH=/out/$out" \
    -e "K6_REG_JS_TEMPLATE=$PERF_JS_TEMPLATE" \
    -e "K6_REG_FILE_BODY_PATH=$FILE_BODY_CONTAINER_PATH" \
    ${K6_REG_WARMUP:+-e K6_REG_WARMUP="$K6_REG_WARMUP"} \
    ${K6_REG_DURATION:+-e K6_REG_DURATION="$K6_REG_DURATION"} \
    ${K6_REG_RATE:+-e K6_REG_RATE="$K6_REG_RATE"} \
    ${K6_REG_LARGE_1MB_RATE:+-e K6_REG_LARGE_1MB_RATE="$K6_REG_LARGE_1MB_RATE"} \
    ${K6_REG_LARGE_10MB_RATE:+-e K6_REG_LARGE_10MB_RATE="$K6_REG_LARGE_10MB_RATE"} \
    ${K6_REG_FILE_BODY_RATE:+-e K6_REG_FILE_BODY_RATE="$K6_REG_FILE_BODY_RATE"} \
    "$K6_IMAGE" run /k6/regression.js
}

# --- dense JVM-internals resource-trajectory sampler ---------------------------------------------
# Runs across the WHOLE load window (regression -> sweep -> growth), the phase where builds 261/264
# died at a reproducible 2m20s / ~37k iterations. Denser than the growth sampler (default 2s) because
# a reproducible cliff at a fixed iteration count is a cumulative-memory signature: to attribute it we
# must see the trajectory APPROACHING the cliff, not just its aftermath. Every column is host-side
# (curl + docker stats), so the SUT pays nothing — this is tier 1.
#   container_mem_bytes / limit   the CONTAINER RSS incl. off-heap — climbing while heap is flat is the
#                                 near-zero native-growth signal (no NMT needed)
#   heap_used / nonheap / gc / threads   the JVM levels already scraped elsewhere
#   dropped_log_events            > 0 at death implicates the event-log ring directly
#   ring_occupancy / in_flight_bytes (+ their ceilings)  the IN-FLIGHT event-log site: entries on the
#                                 disruptor ring not yet processed. The ring BACKING UP — the
#                                 product-side gauges added in this same change; blank on an older SUT
#                                 image that predates them (graceful), populated once a snapshot with
#                                 them is built.
#   retained_entries / retained_bytes (+ their ceilings)  the RETAINED event-log site: entries kept in
#                                 the deque AFTER processing. Read alongside the ring columns to
#                                 attribute the heap to the right site — an empty ring with a full
#                                 retained deque means the retained log is holding the heap, not the
#                                 backlog. Same graceful-blank behaviour on an older image.
#   evicted_log_entries           entries dropped once the event log hit its max size — silent
#                                 evidence loss the dropped_log_events counter does NOT cover.
#   req_dur_count/_sum + _le_*ms  the server's OWN request-duration histogram (receipt->response).
#                                 _count is the population; the le=5/10/25/50/100ms cumulative counts
#                                 bracket the tail so a SERVER-side p99 can be reconstructed and set
#                                 against k6's CLIENT p99 — the test of whether the tail is in-server.
#   jvm_allocated_bytes           cumulative allocation counter; its delta over req_dur_count's delta is
#                                 the allocation per request for any window
#   direct_buffer_used_bytes / _count, netty_direct_used_bytes  OFF-HEAP buffer memory (the NIO "direct"
#                                 pool, and Netty's own counter where Netty keeps one), so an allocator
#                                 change can be judged on native memory, not just heap
#   scrape_ts                     when the metrics scrape started, i.e. when docker stats returned, so
#                                 also the end of cpu_pct's ~1 s interval; `ts` precedes it by the
#                                 docker-stats call (1-3 s), so place a row inside a sweep rung by scrape_ts
#   req_transport_count/_sum/_le_5ms  the transport-inclusive histogram (request head decoded -> last
#                                 response byte written), so a tail outside the handler can still be
#                                 placed inside MockServer; blank on an image that predates it
#   req_read_count/_sum/_le_5ms   the read-to-decode histogram (socket read of a request's first bytes ->
#                                 its head decoded, the span just before req_transport); blank before it
# A BLANK JVM-metric column has THREE distinct meanings, all preserved and NOT conflated: (1) the
# metric is absent on an older image; (2) the scrape TIMED OUT (--max-time 4) because the SUT was
# thrashing in GC near death — common in the final rows, and itself a death signal; (3) a genuine
# measured value that happens to be 0 is written as "0", never blank. The host-side container_mem
# columns keep populating through (2), which is exactly when the in-JVM scrape goes dark.
# to_bytes(): docker stats MemUsage units ("1.523GiB" / "512MiB" / "0B") -> integer bytes.
to_bytes() { awk -v s="$1" 'BEGIN{
  n=s; sub(/[A-Za-z]+$/,"",n); u=s; sub(/^[0-9.]+/,"",u);
  m=1; if(u=="B"||u=="")m=1; else if(u=="kB")m=1000; else if(u=="KiB")m=1024;
  else if(u=="MB")m=1000000; else if(u=="MiB")m=1048576;
  else if(u=="GB")m=1000000000; else if(u=="GiB")m=1073741824;
  printf "%d", n*m }'; }
diag_sampler() {
  local t0; t0="$(date -u +%s)"
  echo "ts,elapsed_s,container_mem_bytes,container_mem_limit_bytes,cpu_pct,heap_used_bytes,heap_max_bytes,nonheap_used_bytes,gc_seconds,gc_count,threads,dropped_log_events,ring_occupancy,ring_capacity,in_flight_bytes,max_in_flight_bytes,retained_entries,retained_bytes,max_retained_bytes,max_retained_entries,evicted_log_entries,req_dur_count,req_dur_sum,req_dur_le_5ms,req_dur_le_10ms,req_dur_le_25ms,req_dur_le_50ms,req_dur_le_100ms,jvm_allocated_bytes,direct_buffer_used_bytes,direct_buffer_count,netty_direct_used_bytes,scrape_ts,req_transport_count,req_transport_sum,req_transport_le_5ms,req_read_count,req_read_sum,req_read_le_5ms" > "$DIAG_SAMPLE_LOG"
  while true; do
    local ts stats cpu memu meml metrics heap heapmax nonheap gc gcc threads dropped occ cap inflt maxinflt retent retbytes maxretbytes maxretent
    local evicted hist rq_count rq_sum rq_le5 rq_le10 rq_le25 rq_le50 rq_le100 allocd dbuf_used dbuf_count netty_direct scrape_ts
    local tr_count tr_sum tr_le5
    ts="$(date -u +%s)"
    # Authoritative liveness: docker inspect .State, NOT the metrics scrape (which
    # also fails when the JVM thrashes in GC while alive). Only a readable state with
    # Running=false trips the gate; an inspect that errors is "unknown", never death.
    if [ ! -f "$SUT_DEATH_SENTINEL" ]; then
      local state running
      state="$(docker inspect --format '{{.State.Status}};{{.State.Running}};{{.State.ExitCode}};{{.State.OOMKilled}}' "$SERVER" 2>/dev/null || echo '')"
      if [ -n "$state" ]; then
        running="$(printf '%s' "$state" | cut -d';' -f2)"
        if [ "$running" != "true" ]; then
          # exit_code as a string (not --argjson): an empty field would fail the whole
          # write and leave the gate fail-OPEN. Nothing reads it as a number.
          jq -nc --arg c "$SERVER" \
            --arg status "$(printf '%s' "$state" | cut -d';' -f1)" \
            --arg exit_code "$(printf '%s' "$state" | cut -d';' -f3)" \
            --arg oom "$(printf '%s' "$state" | cut -d';' -f4)" \
            --argjson elapsed_s "$((ts - t0))" \
            '{container:$c, status:$status, exit_code:$exit_code, oom_killed:($oom=="true"), elapsed_s:$elapsed_s}' \
            > "$SUT_DEATH_SENTINEL" 2>/dev/null || true
          echo "+++ SUT container $SERVER is no longer running ($((ts - t0))s in) — liveness gate will abort at the next phase boundary" >&2
          local load; load="$(cat "$ACTIVE_LOAD_FILE" 2>/dev/null || true)"
          [ -n "$load" ] && docker kill "$load" >/dev/null 2>&1 || true
          return 0
        fi
      fi
    fi
    stats="$(docker stats --no-stream --format '{{.CPUPerc}};{{.MemUsage}}' "$SERVER" 2>/dev/null || echo '')"
    cpu="$(printf '%s' "$stats" | sed -n 's/^\([0-9.]*\)%.*/\1/p')"
    memu="$(printf '%s' "$stats" | sed -E 's/^[^;]*;([^ ]+) \/ .*/\1/')"
    meml="$(printf '%s' "$stats" | sed -E 's/^[^;]*;[^ ]+ \/ (.+)$/\1/')"
    scrape_ts="$(date -u +%s)"
    metrics="$(curl -s --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || echo '')"
    heap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}')"
    heapmax="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_max_bytes\{area="heap"\}/{print $2}')"
    nonheap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="nonheap"\}/{print $2}')"
    gc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_seconds_sum/{s+=$2} END{print s}')"
    gcc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_count/{print $2}')"
    threads="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_threads_current/{print $2}')"
    dropped="$(printf '%s' "$metrics" | counter_sum mock_server_dropped_log_events_total)"
    occ="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_ring_occupancy/{print $2}')"
    cap="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_ring_capacity/{print $2}')"
    inflt="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_in_flight_bytes/{print $2}')"
    maxinflt="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_max_in_flight_bytes/{print $2}')"
    retent="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_retained_entries/{print $2}')"
    retbytes="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_retained_bytes/{print $2}')"
    maxretbytes="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_max_retained_bytes/{print $2}')"
    maxretent="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_max_retained_entries/{print $2}')"
    evicted="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_evicted_log_entries_total/{print $2}')"
    allocd="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_allocated_bytes /{print $2}')"
    dbuf_used="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_buffer_pool_used_bytes\{pool="direct"\}/{print $2}')"
    dbuf_count="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_buffer_pool_used_buffers\{pool="direct"\}/{print $2}')"
    netty_direct="$(printf '%s' "$metrics" | awk -F' ' '/^netty_direct_memory_used_bytes /{print $2}')"
    # One awk pass over the already-fetched scrape pulls the whole histogram (no extra HTTP): the
    # population (_count/_sum) plus the cumulative buckets bracketing the tail. le values are matched
    # against the exact strings the classic exposition prints (0.0005 renders as 5.0E-4, hence the
    # 5ms floor uses le="0.005"). "|" is safe — bucket values are integers/floats, never contain it.
    hist="$(printf '%s' "$metrics" | awk -F' ' '
      /^mock_server_request_duration_seconds_count /{c=$2}
      /^mock_server_request_duration_seconds_sum /{s=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.005"\}/{b5=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.01"\}/{b10=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.025"\}/{b25=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.05"\}/{b50=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.1"\}/{b100=$2}
      /^mock_server_request_transport_duration_seconds_count /{tc=$2}
      /^mock_server_request_transport_duration_seconds_sum /{ts=$2}
      /^mock_server_request_transport_duration_seconds_bucket\{le="0\.005"\}/{tb5=$2}
      /^mock_server_request_read_to_decode_duration_seconds_count /{rc=$2}
      /^mock_server_request_read_to_decode_duration_seconds_sum /{rs=$2}
      /^mock_server_request_read_to_decode_duration_seconds_bucket\{le="0\.005"\}/{rb5=$2}
      END{print c"|"s"|"b5"|"b10"|"b25"|"b50"|"b100"|"tc"|"ts"|"tb5"|"rc"|"rs"|"rb5}')"
    IFS='|' read -r rq_count rq_sum rq_le5 rq_le10 rq_le25 rq_le50 rq_le100 tr_count tr_sum tr_le5 rd_count rd_sum rd_le5 <<<"$hist"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "$ts" "$((ts - t0))" \
      "$([ -n "$memu" ] && to_bytes "$memu" || echo '')" \
      "$([ -n "$meml" ] && to_bytes "$meml" || echo '')" \
      "${cpu:-}" "${heap:-}" "${heapmax:-}" "${nonheap:-}" "${gc:-}" "${gcc:-}" "${threads:-}" \
      "${dropped:-}" "${occ:-}" "${cap:-}" "${inflt:-}" "${maxinflt:-}" \
      "${retent:-}" "${retbytes:-}" "${maxretbytes:-}" "${maxretent:-}" \
      "${evicted:-}" "${rq_count:-}" "${rq_sum:-}" \
      "${rq_le5:-}" "${rq_le10:-}" "${rq_le25:-}" "${rq_le50:-}" "${rq_le100:-}" \
      "${allocd:-}" "${dbuf_used:-}" "${dbuf_count:-}" "${netty_direct:-}" "$scrape_ts" \
      "${tr_count:-}" "${tr_sum:-}" "${tr_le5:-}" "${rd_count:-}" "${rd_sum:-}" "${rd_le5:-}" >> "$DIAG_SAMPLE_LOG"
    sleep "$PERF_DIAG_SAMPLE_INTERVAL"
  done
}
echo "--- starting dense resource-trajectory sampler (every ${PERF_DIAG_SAMPLE_INTERVAL}s -> diag-samples.csv)"
diag_sampler & DIAG_SAMPLER_PID=$!

# --- live-heap class histogram sampler (deep runs only) ------------------------------------------
# Answers what the heap RETAINS, which diag-samples.csv cannot: it reports heap_used against the
# event log's own gauges, so anything outside the log is an unattributed remainder. JFR's two
# retention views (object-statistics, memory-leaks-by-class) stay EMPTY under ZGC — the SUT's
# collector — so jcmd GC.class_histogram is the only instrument left. The distroless SUT ships no
# jcmd, hence a JDK sidecar in its PID namespace; the attach handshake demands an exact uid match
# (root is rejected), so the uid is read from the target's own /proc entry rather than assumed.
# Each sample is a stop-the-world GC_HeapInspection, hundreds of ms under load, that becomes the
# p99.9 of any rung it lands in. So it samples only while LIVE_HISTO_WINDOW holds a future epoch
# second: open_live_histo_window opens it for growth.js, which fills the event log and is not a
# regression or sweep phase.
LIVE_HISTO_WINDOW="$DIAG_DIR/live-histo-window"
open_live_histo_window() { # seconds
  [ "$PERF_JVM_DIAGNOSTICS" = "deep" ] && [ "$1" -gt 0 ] || return 0
  echo "$(( $(date -u +%s) + $1 ))" > "$LIVE_HISTO_WINDOW"
}
close_live_histo_window() {
  [ -e "$LIVE_HISTO_WINDOW" ] || return 0
  rm -f "$LIVE_HISTO_WINDOW"
  echo "--- live-heap histogram: $(awk '/^===== /{n++} END{print n+0}' "$DIAG_DIR/sut/live-heap-histogram.txt" 2>/dev/null || echo 0) sample(s) taken during growth.js"
}
live_heap_histo_sampler() { # container, diag_subdir
  local target="$1" out="$DIAG_DIR/$2/live-heap-histogram.txt" uid gid t0 now open_until
  read -r uid gid <<<"$(sut_jvm_uid_gid "$target")"
  if ! grep -qE '^[0-9]+$' <<<"$uid"; then
    echo "WARNING: could not resolve the $target JVM uid from its PID namespace — no live-heap histogram" >&2
    return 0
  fi
  # Prove the attach before the sampling loop starts, so a broken sidecar is one loud line
  # rather than a file of stack traces discovered after the run.
  if ! timeout 60 docker run --rm --pid="container:$target" --user "${uid}:${gid}" \
         "$PERF_HISTO_JDK_IMAGE" jcmd 1 VM.version >/dev/null 2>&1; then
    echo "WARNING: jcmd sidecar cannot attach to $target as uid $uid — no live-heap histogram" >&2
    return 0
  fi
  mkdir -p "$(dirname "$out")"; : > "$out"
  t0="$(date -u +%s)"
  echo "--- live-heap histogram sampler attached to $target as uid $uid (every ${PERF_LIVE_HISTO_INTERVAL_S}s during growth.js -> live-heap-histogram.txt)"
  while true; do
    sleep "$PERF_LIVE_HISTO_INTERVAL_S"
    [ "$(docker inspect --format '{{.State.Running}}' "$target" 2>/dev/null || echo false)" = "true" ] || return 0
    open_until="$(cat "$LIVE_HISTO_WINDOW" 2>/dev/null || true)"; now="$(date -u +%s)"
    case "$open_until" in ''|*[!0-9]*) continue ;; esac
    [ "$now" -lt "$open_until" ] || continue
    {
      echo "===== elapsed_s=$(( now - t0 )) epoch_s=$now"
      timeout 90 docker run --rm --pid="container:$target" --user "${uid}:${gid}" \
        "$PERF_HISTO_JDK_IMAGE" jcmd 1 GC.class_histogram 2>&1 \
        | head -n "$(( PERF_LIVE_HISTO_TOPN + 3 ))"
      echo
    } >> "$out" 2>&1 || true
  done
}
if [ "$PERF_JVM_DIAGNOSTICS" = "deep" ]; then
  live_heap_histo_sampler "$SERVER" "sut" & LIVE_HISTO_PID=$!
  docker pull -q "$PERF_JFR_JDK_IMAGE" >/dev/null 2>&1 || echo "WARNING: could not pre-pull $PERF_JFR_JDK_IMAGE — the ceiling JFR cut will try again" >&2
fi

LOAD_WINDOW_START_EPOCH="$(date -u +%s)"
LOAD_WINDOW_START_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if arm_only; then
  echo '{}' > "$OUT_DIR/regression-http.json"; echo '{}' > "$OUT_DIR/regression-https.json"
else
  run_regression "http" "$SUT_BASE_HTTP" "false" "regression-http.json"
  run_regression "https_h2" "$SUT_BASE_HTTPS" "true" "regression-https.json"
fi
abort_if_sut_died

# --- throughput-vs-latency sweep ----------------------------------------------
# Offers an ascending ladder of fixed arrival rates against the SAME core-pinned
# SUT and records the achieved-throughput / latency-percentile knee curve.
# sweep.js seeds + resets MockServer itself in setup()/teardown(). Durations are
# bounded via K6_SWEEP_* env so it adds only ~3-4 min to the run; the CI-default
# ladder + short steps below override sweep.js's longer interactive defaults.
# The CI ladder now climbs PAST the ~32-36k knee (published saturation) to
# 48k/64k so saturation is actually reached rather than stopping where the server
# is still comfortable (the old default topped out at 16k). Adds ~4 min. A rung is
# only a valid server-ceiling candidate if the k6 CLIENT had headroom there, so we
# sample the k6 container's CPU throughout and read k6's own dropped_iterations per
# rung, then derive saturation_rps from the highest CLEANLY-served rung.
SWEEP_RATES="${K6_SWEEP_RATES:-500,1000,2000,4000,8000,16000,24000,32000,36000,40000,44000,48000,64000}"
SWEEP_STEP="${K6_SWEEP_STEP:-15s}"
SWEEP_GAP="${K6_SWEEP_GAP:-5s}"
SWEEP_CPU_LOG="$OUT_DIR/sweep-k6-cpu.csv"

# Ladder schedule + client-pin constants, derived ONCE and reused by every sweep
# (the ERROR baseline below AND the INFO publication arm). They depend only on the
# static ladder/pin config, never on a sweep's output, so hoisting them here keeps
# the two sweeps' saturation constants identical. The rules are not: only the ERROR
# sweep passes a server CPU log, so derive_saturation's server-headroom client-limited
# test is off for the INFO sweep (see its server_headroom_test). SETTLE_S / err-eps are the same tunables the
# derivation used inline before this was factored out. SETTLE_S also sets sweep.js's
# K6_SWEEP_SETTLE, so both windows skip the same settle length; the latency window is
# anchored at each k6 scenario's start, and so are the CPU windows when the sweep records
# it (start_epoch_ms), else they fall back to the ladder's host-side T0.
STEP_S="$(to_secs "$SWEEP_STEP")"
# shellcheck disable=SC2034  # read by lib/perf-derive-saturation.sh
GAP_S="$(to_secs "$SWEEP_GAP")"
SETTLE_S="${PERF_SWEEP_SETTLE_S:-3}"
K6_CORES="$(k6_core_count "$K6_CPUS")"
K6_PIN_PCT=$((K6_CORES * 100))
# The SUT's CPU pin, for derive_saturation's server-headroom test (0 = unpinned: test off).
# shellcheck disable=SC2034  # read by lib/perf-derive-saturation.sh
SERVER_PIN_PCT=$(( $(k6_core_count "$SERVER_CPUS") * 100 ))
# shellcheck disable=SC2034  # read by lib/perf-derive-saturation.sh
SWEEP_ERR_EPS="${PERF_SWEEP_ERROR_EPS:-0.01}"
# Fractional drop TOLERANCE for rig-validity (see derive_saturation). A rung's
# dropped_iterations are forgiven either when the VU pool was PINNED (a server-side
# knee — see $pool_pinned) OR, when the pool was NOT pinned, only if they are BELOW
# this fraction of the rung's intended iterations — never on the fraction alone.
# 0.01 = 1% is DERIVED, not picked: above the largest recorded harmless blip and
# below the smallest recorded genuine shortfall, each with margin (figures in
# docs/code/performance-measurement.md, "k6 load-shape sizing"). It must stay below the
# smallest genuine shortfall, and it means a rig-valid rung delivered >=99% of offered,
# tighter than the 0.95 knee rule, so it never admits a rung the knee logic calls short.
# shellcheck disable=SC2034  # read by lib/perf-derive-saturation.sh
SWEEP_DROP_TOL="${PERF_SWEEP_DROP_TOL:-0.01}"
# Occupancy KNEE threshold for the drop discriminator (see derive_saturation). A
# dropped iteration means the constant-arrival executor found no free VU (active ==
# pool). When vus_active_p95 / pool_per_rung is AT OR ABOVE this, the pool was the
# binding constraint for the bulk of the rung — VUs were blocked waiting on server
# responses, i.e. the SERVER saturating — so the rung is KEPT and labelled the knee
# rather than discarded. Below it, drops over tolerance with an idle pool are a
# CLIENT-side scheduling stall (the client could not schedule though VUs were free)
# and stay excluded. 0.80 sits in the widest gap of the observed occupancy ladder
# (a pinned rung reads ~0.86-0.95; a client-limited idle pool reads ~0.01-0.02), so
# p95 noise cannot flip a rung across it. Overridable.
# shellcheck disable=SC2034  # read by lib/perf-derive-saturation.sh
SWEEP_OCC_KNEE="${PERF_SWEEP_OCC_KNEE:-0.80}"

# Background sampler of the k6 CLIENT container's CPU while a sweep runs — the
# missing "was the client the bottleneck?" evidence. A rung where k6 is near its
# own CPU pin is a CLIENT ceiling, not MockServer's, and is excluded by
# derive_saturation. Parameterised by container name + output CSV so the ERROR and
# INFO sweeps each get their own sampler against their own k6 container.
sweep_cpu_sampler() { # k6_container_name  out_csv
  local cname="$1" out_csv="$2" first_seen=0
  echo "ts,cpu_pct" > "$out_csv"
  while true; do
    local ts cpu
    # Stamped on return: docker stats reports the ~1 s before it returns (performance-measurement.md, sweep.js).
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$cname" 2>/dev/null | tr -d '% ' || echo '')"
    ts="$(date -u +%s)"
    # DROP the first successful reading: `docker stats --no-stream` computes CPU%
    # over the interval since the container STARTED, so its cold read is inflated
    # and lands in the first rung's window. The k6 container runs ONCE for the whole
    # ladder, so this discards exactly one startup artifact, off the first rung only.
    if [ -n "$cpu" ]; then
      if [ "$first_seen" -eq 0 ]; then first_seen=1;
      else printf '%s,%s\n' "$ts" "$cpu" >> "$out_csv"; fi
    fi
    sleep "${PERF_SWEEP_SAMPLE_INTERVAL:-3}"
  done
}

# Run one throughput-vs-latency sweep against $target_alias, writing k6's result to
# $out_json and the client-CPU trace to $cpu_log. Records the sweep's start epoch in
# the global LAST_SWEEP_T0 (NOT echoed — the docker run's own stdout would pollute a
# captured value) so derive_saturation can line each rung up with its hold window.
# Registers the sampler PID in SWEEP_SAMPLER_PID so cleanup() reaps it on an early
# exit, and clears it once reaped.
run_sweep() { # k6_container_name  target_alias  out_json_host_path  cpu_log_host_path  [scheme=http|https]  [keep_log=false]
  local k6name="$1" target_alias="$2" out_json="$3" cpu_log="$4" scheme="${5:-http}" keep_env=()
  [ "${6:-false}" != true ] || keep_env=(-e "K6_SWEEP_RESET_ON_TEARDOWN=false")
  LAST_SWEEP_T0="$(date -u +%s)"
  sweep_cpu_sampler "$k6name" "$cpu_log" & SWEEP_SAMPLER_PID=$!
  # Register this ladder so the liveness gate can stop it the instant the SUT dies.
  printf '%s' "$k6name" > "$ACTIVE_LOAD_FILE" 2>/dev/null || true
  local sweep_rc=0
  # host mode addresses the SUT on the host; bridge keeps the alias URL unchanged.
  local sweep_base="http://${target_alias}:1080"; [ "$PERF_NETWORK_MODE" = host ] && sweep_base="$SUT_BASE_HTTP"
  # https: the path-coverage h2 ladder (k6 negotiates h2 via ALPN).
  local proto_env=(-e "PROTO=http")
  if [ "$scheme" = https ]; then
    sweep_base="https://${target_alias}:1080"
    proto_env=(-e "PROTO=https_h2" -e "INSECURE_SKIP_TLS_VERIFY=true")
  fi
  # shellcheck disable=SC2046
  docker run --rm --name "$k6name" "${NET_CLIENT[@]}" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$sweep_base" \
    "${proto_env[@]}" ${keep_env[@]+"${keep_env[@]}"} \
    -e "K6_SWEEP_RATES=$SWEEP_RATES" \
    -e "K6_SWEEP_STEP=$SWEEP_STEP" \
    -e "K6_SWEEP_GAP=$SWEEP_GAP" \
    -e "K6_SWEEP_SETTLE=${SETTLE_S}s" \
    -e "K6_SWEEP_RESULT_PATH=/out/$(basename "$out_json")" \
    ${K6_SWEEP_PRE_VUS:+-e K6_SWEEP_PRE_VUS="$K6_SWEEP_PRE_VUS"} \
    ${K6_SWEEP_MAX_VUS:+-e K6_SWEEP_MAX_VUS="$K6_SWEEP_MAX_VUS"} \
    ${K6_SWEEP_VUS_PER_KRPS:+-e K6_SWEEP_VUS_PER_KRPS="$K6_SWEEP_VUS_PER_KRPS"} \
    ${K6_SWEEP_VU_CEILING:+-e K6_SWEEP_VU_CEILING="$K6_SWEEP_VU_CEILING"} \
    ${K6_SWEEP_VU_FLOOR:+-e K6_SWEEP_VU_FLOOR="$K6_SWEEP_VU_FLOOR"} \
    "$K6_IMAGE" run /k6/sweep.js || sweep_rc=$?
  kill "$SWEEP_SAMPLER_PID" >/dev/null 2>&1 || true; SWEEP_SAMPLER_PID=""
  : > "$ACTIVE_LOAD_FILE" 2>/dev/null || true
  # A non-zero k6 that is NOT the gate killing this ladder is a genuine sweep failure —
  # preserve it (set -e aborts at the call site as before); a kill after SUT death
  # returns cleanly so the caller's abort_if_sut_died reports it.
  if [ "$sweep_rc" -ne 0 ] && [ ! -f "$SUT_DEATH_SENTINEL" ]; then
    return "$sweep_rc"
  fi
}

# derive_saturation (rig validity per rung) lives in lib/perf-derive-saturation.sh so the
# opt-in multi-process remote-write harness reuses it rather than re-implementing it.
if [ -r "$SCRIPT_DIR/lib/perf-derive-saturation.sh" ]; then
  # shellcheck source=lib/perf-derive-saturation.sh
  . "$SCRIPT_DIR/lib/perf-derive-saturation.sh"
else
  echo ":x: rig-validity lib not found at $SCRIPT_DIR/lib/perf-derive-saturation.sh" >&2
  exit 1
fi

if arm_only; then
  SATURATION_JSON='{}'; PEAK_ACHIEVED_RPS="n/a"; SATURATION_RPS=""
else
  echo "--- sweep.js (throughput-vs-latency knee curve; ladder=$SWEEP_RATES)"
  run_sweep "$SWEEP_K6" "$SERVER_ALIAS" "$OUT_DIR/sweep.json" "$SWEEP_CPU_LOG"
  abort_if_sut_died
  SWEEP_T0="$LAST_SWEEP_T0"
  SATURATION_JSON="$(derive_saturation "$OUT_DIR/sweep.json" "$SWEEP_CPU_LOG" "$SWEEP_T0" "$DIAG_SAMPLE_LOG")"
  # The k6 CPU trace rides in the diagnostics bundle so rig validity can be re-derived offline.
  cp "$SWEEP_CPU_LOG" "$DIAG_DIR/sweep-k6-cpu.csv" 2>/dev/null || true
  PEAK_ACHIEVED_RPS="$(jq -r '.rig_valid_peak_achieved_rps' <<<"$SATURATION_JSON")"
  SATURATION_RPS="$(jq -r '.saturation_rps' <<<"$SATURATION_JSON")"
  echo "--- rig_valid_peak_achieved_rps=$PEAK_ACHIEVED_RPS saturation_rps=$SATURATION_RPS (client pin=${K6_PIN_PCT}%, cores=$K6_CORES)"

  # Validity: at least one rung was measured with the client sound (headroom, no
  # drops, low errors). If EVERY rung was excluded the rig was compromised
  # throughout and no server figure is trustworthy. PROVOCATION (observed false):
  # a synthetic ladder with the k6 CPU above 85% of pin at every rung yields
  # rig_valid_rungs=0 and this check false.
  #
  # KEYED OFF THE RUNG COUNT, NOT off rig_valid_peak_achieved_rps > 0. Those are not the same
  # question. A rung can be rig-valid and still achieve 0 rps (a server that is down
  # rather than a client that is compromised), and the old form reported that as "every
  # rung excluded" — blaming the rig for a server failure. The count asks what the check
  # is actually named for: did ANY rung measure cleanly?
  #
  # The failure message now REPORTS the per-rung exclude_reason rather than asserting a
  # cause: a fixed message once blamed client CPU on a run whose client was 13.6% busy.
  RIG_VALID_RUNGS="$(jq -r '.rig_valid_rungs // 0' <<<"$SATURATION_JSON")"
  # A server-headroom test that did not run on every rung is said so, not left implicit.
  SRV_TEST="$(jq -r '.server_headroom_test // "off"' <<<"$SATURATION_JSON")"
  SRV_TEST_NOTE=""; [ "$SRV_TEST" = active ] || SRV_TEST_NOTE="; server-headroom client-limited test ${SRV_TEST} (only the k6 CPU test judged the rungs it did not cover)"
  if awk -v v="$RIG_VALID_RUNGS" 'BEGIN{exit !(v+0>0)}'; then
    add_check "sweep_client_had_headroom" true "${RIG_VALID_RUNGS} rung(s) measured with client headroom, no client-limited drops, low errors (rig_valid_peak_achieved_rps=${PEAK_ACHIEVED_RPS})${SRV_TEST_NOTE}"
  else
    SWEEP_EXCLUSIONS="$(jq -r '[.ladder[] | "\(.offered_rps): \(.exclude_reason // "?")"] | join("; ")' <<<"$SATURATION_JSON")"
    add_check "sweep_client_had_headroom" false "every sweep rung was excluded, so no server throughput figure is trustworthy. Per-rung reasons — ${SWEEP_EXCLUSIONS}${SRV_TEST_NOTE}"
  fi

  # Every request of a rung must land in exactly one latency window (lib/perf-sweep-window.sh);
  # otherwise the published percentiles are not the rung's post-settle window.
  if SWEEP_WINDOW_MISMATCH="$(sweep_window_mismatches "$OUT_DIR/sweep.json")"; then
    add_check "sweep_latency_window_accounts_every_request" true "every rung: measured_sample_count + settle_excluded == sample_count"
  else
    add_check "sweep_latency_window_accounts_every_request" false "sweep.js settle/steady windows are inconsistent, so the published percentiles cannot be trusted to cover each rung's post-settle window — ${SWEEP_WINDOW_MISMATCH}"
  fi
fi

# --- per-rung wall-clock windows, tail localisation, ceiling JFR ---------------
# Rung windows come from sweep.js's start_epoch_ms (the k6 scenario's own start). The host-side
# LAST_SWEEP_T0 precedes it by k6 start-up and setup(), several seconds on a large ladder.
sweep_rung_windows() { # sweep_json -> [{offered_rps, start_epoch_ms, end_epoch_ms}]
  jq -c --argjson step "$STEP_S" '[(.points // [])[] | select(.start_epoch_ms != null)
    | {offered_rps, start_epoch_ms, end_epoch_ms: (.start_epoch_ms + $step * 1000)}]' "$1" 2>/dev/null || echo '[]'
}

# Notify-only. Per rung, the share of requests over 5 ms as k6 saw it (post-settle stalls) beside
# the server's shares from histogram deltas between the diag-samples.csv rows inside the rung's
# post-settle window: req_dur times only the request handler (decoded request to response hand-off);
# req_transport runs from the decoded request head to the last response byte written to the socket;
# req_read from the socket read of a request's first bytes to its head being decoded.
sweep_tail_localisation() { # sweep_json diag_csv -> JSON
  local rungs
  rungs="$(jq -r --argjson step "$STEP_S" '(.points // [])[] | select(.start_epoch_ms != null)
    | [.offered_rps, .start_epoch_ms, (.start_epoch_ms + $step * 1000),
       (if has("client_over_5ms_frac") then (.client_over_5ms_frac // "")
        elif (.stall_ms_threshold // 5) != 5 then ""
        elif (.measured_sample_count // 0) > 0 then (if .stalls_post_settle == null then "" else .stalls_post_settle / .measured_sample_count end)
        elif (.sample_count // 0) > 0 then (if .stalls == null then "" else .stalls / .sample_count end)
        else "" end)] | @tsv' "$1" 2>/dev/null || true)"
  if [ -z "$rungs" ] || [ ! -s "$2" ]; then echo '{}'; return 0; fi
  awk -v settle="$SETTLE_S" '
    FNR == NR { split($0, a, "\t"); nr++; off[nr] = a[1]; lo[nr] = a[2] / 1000 + settle; hi[nr] = a[3] / 1000 - 1; cl[nr] = a[4]; next }
    FNR == 1 { n = split($0, h, ","); for (i = 1; i <= n; i++) col[h[i]] = i
               tc = ("scrape_ts" in col) ? col["scrape_ts"] : col["ts"]
               ok = ("req_dur_count" in col) && ("req_dur_le_5ms" in col)
               tok = ("req_transport_count" in col) && ("req_transport_le_5ms" in col)
               rok = ("req_read_count" in col) && ("req_read_le_5ms" in col); next }
    { split($0, f, ","); t = f[tc]
      if (ok) { c = f[col["req_dur_count"]]; b = f[col["req_dur_le_5ms"]]
                if (t != "" && c != "" && b != "") { m++; T[m] = t + 0; C[m] = c + 0; B[m] = b + 0 } }
      if (tok) { c = f[col["req_transport_count"]]; b = f[col["req_transport_le_5ms"]]
                 if (t != "" && c != "" && b != "") { k++; XT[k] = t + 0; XC[k] = c + 0; XB[k] = b + 0 } }
      if (rok) { c = f[col["req_read_count"]]; b = f[col["req_read_le_5ms"]]
                 if (t != "" && c != "" && b != "") { r++; RDT[r] = t + 0; RDC[r] = c + 0; RDB[r] = b + 0 } } }
    END { for (i = 1; i <= nr; i++) {
            first = 0; last = 0
            for (j = 1; j <= m; j++) if (T[j] >= lo[i] && T[j] <= hi[i]) { if (!first) first = j; last = j }
            srv = ""; req = 0; win = 0
            if (first && last > first && C[last] > C[first]) {
              req = C[last] - C[first]; srv = 1 - (B[last] - B[first]) / req; win = T[last] - T[first] }
            first = 0; last = 0
            for (j = 1; j <= k; j++) if (XT[j] >= lo[i] && XT[j] <= hi[i]) { if (!first) first = j; last = j }
            xsrv = ""; xreq = 0
            if (first && last > first && XC[last] > XC[first]) {
              xreq = XC[last] - XC[first]; xsrv = 1 - (XB[last] - XB[first]) / xreq }
            first = 0; last = 0
            for (j = 1; j <= r; j++) if (RDT[j] >= lo[i] && RDT[j] <= hi[i]) { if (!first) first = j; last = j }
            rsrv = ""; rreq = 0
            if (first && last > first && RDC[last] > RDC[first]) {
              rreq = RDC[last] - RDC[first]; rsrv = 1 - (RDB[last] - RDB[first]) / rreq }
            printf "%s\t%s\t%s\t%d\t%d\t%s\t%d\t%s\t%d\n", off[i], cl[i], srv, req, win, xsrv, xreq, rsrv, rreq } }
  ' <(printf '%s\n' "$rungs") "$2" | jq -Rsc '
    def frac: if . == "" then null else (tonumber | . * 100000 | round / 100000) end;
    split("\n") | map(select(length > 0) | split("\t")
      | {offered_rps: (.[0] | tonumber), client_over_5ms_frac: (.[1] | frac), server_over_5ms_frac: (.[2] | frac),
         server_requests: (.[3] | tonumber), server_window_s: (.[4] | tonumber),
         server_transport_over_5ms_frac: (.[5] | frac), server_transport_requests: (.[6] | tonumber),
         server_read_over_5ms_frac: (.[7] | frac), server_read_requests: (.[8] | tonumber)})
    | {threshold_ms: 5, rungs: .}' 2>/dev/null || echo '{}'
}

# Deep runs only: the SUT's JFR from the knee rung (the first at or above saturation_rps; the top
# rung when nothing was clean) to the end of the ladder, starting one rung earlier when that would
# leave only the top rung. JFR.dump bounds by chunk, and a chunk spans minutes, so JfrWindow.java
# then keeps only the events that started inside the window.
dump_ceiling_jfr() { # rung_windows_json saturation_rps
  [ "$PERF_JVM_DIAGNOSTICS" = "deep" ] || return 0
  local win err="" uid gid rc=0 out="$DIAG_DIR/sut/ceiling.jfr" chunks="$DIAG_DIR/sut/ceiling.chunks.jfr" begin_ms end_ms cut=""
  win="$(jq -c --argjson sat "${2:-0}" 'if length == 0 then empty else
      . as $r | (length - 1) as $top
      | ([range(0; length) | select($sat > 0 and $r[.].offered_rps >= $sat)] | .[0] // $top) as $k
      | (if $k == $top and $top > 0 then $top - 1 else $k end) as $i | .[$i] as $first
      | {first_rung_rps: $first.offered_rps, last_rung_rps: .[-1].offered_rps, saturation_rps: $sat,
         begin_epoch_ms: $first.start_epoch_ms, end_epoch_ms: .[-1].end_epoch_ms,
         begin_iso: ($first.start_epoch_ms / 1000 | floor | todate), end_iso: (.[-1].end_epoch_ms / 1000 | ceil | todate)} end' <<<"$1" 2>/dev/null || true)"
  if [ -z "$win" ]; then
    err="sweep.json carries no rung start times"
  elif [ "$(docker inspect --format '{{.State.Running}}' "$SERVER" 2>/dev/null || echo false)" != "true" ]; then
    err="SUT not running at dump time"
  else
    read -r uid gid <<<"$(sut_jvm_uid_gid "$SERVER")"
    begin_ms="$(jq -r '.begin_epoch_ms' <<<"$win")"; end_ms="$(jq -r '.end_epoch_ms' <<<"$win")"
    if ! grep -qE '^[0-9]+$' <<<"$uid"; then
      err="could not resolve the SUT JVM uid"
    elif ! timeout 120 docker run --rm --pid="container:$SERVER" --user "${uid}:${gid}" "$PERF_JFR_JDK_IMAGE" \
           jcmd 1 JFR.dump name=perfdiag filename=/diag/sut/ceiling.chunks.jfr \
           begin="$(jq -r '.begin_iso' <<<"$win")" end="$(jq -r '.end_iso' <<<"$win")" >/dev/null 2>&1 || [ ! -s "$chunks" ]; then
      err="jcmd JFR.dump of the ceiling window failed or wrote nothing"
    else
      cut="$(timeout 180 docker run --rm --user "${uid}:${gid}" -v "$DIAG_DIR:/diag" \
               -v "$REPO_ROOT/.buildkite/scripts/lib/JfrWindow.java:/JfrWindow.java:ro" "$PERF_JFR_JDK_IMAGE" \
               java /JfrWindow.java /diag/sut/ceiling.chunks.jfr /diag/sut/ceiling.jfr "$begin_ms" "$end_ms" 2>&1)" || rc=$?
      [ "$rc" -eq 0 ] && [ -s "$out" ] || { err="cutting the ceiling window failed (exit $rc): $(tail -1 <<<"$cut")"; rm -f "$out"; }
    fi
    rm -f "$chunks"
  fi
  jq -n --argjson win "${win:-null}" --arg err "$err" --arg cut "$cut" \
    '($win // {}) + {jfr: (if $err == "" then "sut/ceiling.jfr" else null end), cut: (if $err == "" then $cut else null end),
                     error: (if $err == "" then null else $err end)}' > "$DIAG_DIR/ceiling-window.json" 2>/dev/null || true
  if [ -n "$err" ]; then echo "WARNING: no ceiling JFR window — $err" >&2
  else echo "--- ceiling JFR window: sut/ceiling.jfr, $(jq -r '"\(.first_rung_rps)-\(.last_rung_rps) rps, \(.begin_iso) to \(.end_iso)"' <<<"$win") ($cut)" >&2; fi
  return 0
}

SWEEP_TAIL_JSON='{}'
if ! arm_only; then
  SWEEP_RUNGS_JSON="$(sweep_rung_windows "$OUT_DIR/sweep.json")"
  printf '%s\n' "$SWEEP_RUNGS_JSON" > "$DIAG_DIR/sweep-rungs.json" 2>/dev/null || true
  SWEEP_TAIL_JSON="$(sweep_tail_localisation "$OUT_DIR/sweep.json" "$DIAG_SAMPLE_LOG" || true)"
  # Exactly one JSON object, or '{}': a partial pipeline can emit two documents, which --argjson rejects.
  jq -se 'length == 1 and (.[0] | type) == "object"' >/dev/null 2>&1 <<<"$SWEEP_TAIL_JSON" || SWEEP_TAIL_JSON='{}'
  echo "--- tail localisation (share of requests over 5 ms; client = k6 post-settle, server = MockServer handler histogram, transport = decoded request to last byte written; notify-only):"
  jq -r '(.rungs // [])[] | "    \(.offered_rps) rps: client \(.client_over_5ms_frac // "n/a") server \(.server_over_5ms_frac // "n/a") transport \(.server_transport_over_5ms_frac // "n/a") (n=\(.server_requests) over \(.server_window_s)s)"' <<<"$SWEEP_TAIL_JSON" || true
  dump_ceiling_jfr "$SWEEP_RUNGS_JSON" "$SATURATION_RPS"
fi

# --- OPT-IN: this ladder from N k6 processes merged in Prometheus
# (scripts/rw-multi-k6-sweep.sh), against THIS SUT so both methods measure one server. On the
# default ladder it runs its own, which continues past 64k; an explicit PERF_RW_RATES, else an
# explicit K6_SWEEP_RATES (e.g. the allocation profile's short ladder), overrides that.
# Excluded from the daily run's validity; the harness exits 2 on its own gates. Published only
# from the perf-xl arm-only step, never from inside this run.
SERVING_RW_MULTIK6_JSON='{}'
SERVING_RW_MULTIK6_ATTEMPTED=false
if [ "${PERF_SERVING_RW_MULTIK6:-false}" = "true" ]; then
  SERVING_RW_MULTIK6_ATTEMPTED=true
  if [ "$PERF_NETWORK_MODE" = host ] || [ -z "$SERVER_CPUS" ]; then
    echo "--- item 31 remote-write multi-k6 skipped: needs bridge networking and a pinned SUT (mode=$PERF_NETWORK_MODE server_cpus=${SERVER_CPUS:-none})"
  else
    echo "--- item 31 remote-write multi-k6 sweep (opt-in; PERF_SERVING_RW_MULTIK6=true)"
    abort_if_sut_died
    rw_rc=0
    # Arm-only: no regression or sweep load warmed this SUT first, so the harness warms it itself.
    rw_warmup="0s"; arm_only && rw_warmup="60s"
    PERF_RW_REPO_ROOT="$REPO_ROOT" PERF_RW_NETWORK="$NETWORK" \
      PERF_RW_TARGET_URL="$SUT_BASE_HTTP" PERF_RW_TARGET_CURL_URL="http://${SERVER_METRICS:-127.0.0.1:1080}" \
      PERF_RW_SUT_CONTAINER="$SERVER" PERF_RW_SERVER_CPUS="$SERVER_CPUS" PERF_RW_UPSTREAM_CPUS="$UPSTREAM_CPUS" \
      PERF_RW_IMAGE="$MOCKSERVER_IMAGE" \
      PERF_RW_RATES="${PERF_RW_RATES:-${K6_SWEEP_RATES:-}}" PERF_RW_STEP="$SWEEP_STEP" PERF_RW_GAP="$SWEEP_GAP" \
      PERF_RW_SETTLE_S="$SETTLE_S" PERF_RW_WARMUP_DURATION="${PERF_RW_WARMUP_DURATION:-$rw_warmup}" \
      PERF_RW_DEBUG_DIR="$OUT_DIR/serving-rw-multik6-work" \
      bash "$REPO_ROOT/mockserver-performance-test/scripts/rw-multi-k6-sweep.sh" "$OUT_DIR/serving-rw-multik6.json" || rw_rc=$?
    SERVING_RW_MULTIK6_JSON="$(cat "$OUT_DIR/serving-rw-multik6.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$SERVING_RW_MULTIK6_JSON" || SERVING_RW_MULTIK6_JSON='{}'
    # Every trial uploads the harness's work files (per-process CPU samples, k6 logs, merges), valid or not.
    if ! tar czf "$REPO_ROOT/serving-rw-multik6-work.tgz" -C "$OUT_DIR" serving-rw-multik6-work 2>/dev/null; then
      echo "WARNING: could not archive $OUT_DIR/serving-rw-multik6-work — no work-files artifact this run" >&2
    elif command -v buildkite-agent >/dev/null 2>&1; then
      bk_upload_artifact "serving-rw-multik6-work.tgz"
    fi
    # Tail localisation for this ladder too: its rungs ran on the same SUT, inside diag-samples.csv.
    jq '{points: (.rung_windows // [])}' <<<"$SERVING_RW_MULTIK6_JSON" > "$OUT_DIR/serving-rw-multik6-rungs.json" 2>/dev/null \
      || echo '{"points":[]}' > "$OUT_DIR/serving-rw-multik6-rungs.json"
    RW_TAIL_JSON="$(sweep_tail_localisation "$OUT_DIR/serving-rw-multik6-rungs.json" "$DIAG_SAMPLE_LOG" || true)"
    jq -se 'length == 1 and (.[0] | type) == "object"' >/dev/null 2>&1 <<<"$RW_TAIL_JSON" || RW_TAIL_JSON='{}'
    SERVING_RW_MULTIK6_JSON="$(jq -c --argjson t "$RW_TAIL_JSON" '. + {tail_localisation: $t}' <<<"$SERVING_RW_MULTIK6_JSON")"
    echo "--- rw multi-k6 tail localisation (share of requests over 5 ms; client = merged k6 histogram, server = MockServer handler histogram, transport = decoded request to last byte written; notify-only):"
    jq -r '(.rungs // [])[] | "    \(.offered_rps) rps: client \(.client_over_5ms_frac // "unavailable") server \(.server_over_5ms_frac // "unavailable") transport \(.server_transport_over_5ms_frac // "unavailable") (n=\(.server_requests) over \(.server_window_s)s)"' <<<"$RW_TAIL_JSON" || true
    echo "--- serving_rw_multik6: rc=$rw_rc valid=$(jq -r '.valid | if . == null then "?" else tostring end' <<<"$SERVING_RW_MULTIK6_JSON") $(jq -r 'if .valid == true then "healthy_ceiling=\(.headline.healthy_ceiling_rps // "?")" else "healthy_ceiling_if_valid=\(.headline_if_valid.healthy_ceiling_rps // "?") (NOT a result: the run is invalid)" end' <<<"$SERVING_RW_MULTIK6_JSON") rig_valid_peak=$(jq -r '.rig_valid_peak_achieved_rps // "?"' <<<"$SERVING_RW_MULTIK6_JSON") (single-process: ${PEAK_ACHIEVED_RPS}) cross_check_equivalent=$(jq -r '.cross_check.equivalent | if . == null then "?" else tostring end' <<<"$SERVING_RW_MULTIK6_JSON") cross_run_agrees=$(jq -r '.cross_check.cross_run.agrees | if . == null then "?" else tostring end' <<<"$SERVING_RW_MULTIK6_JSON")"
    abort_if_sut_died
  fi
fi

# --- OPT-IN steady-state latency-tail experiment (docs/code/performance-measurement.md
# -> "Rung-onset exclusion") --
# One constant-arrival-rate pass at PERF_STEADY_RATE on the main SUT, before growth.
# The server histogram delta approximates the client's measured window: it starts
# WARMUP_S after the k6 container launches (so it may hold a few seconds of warm-up)
# and includes the metric scrapes themselves. Never baseline-eligible; skipped when
# PERF_STEADY_RATE is unset.
STEADY_JSON='{}'
if [ -n "$PERF_STEADY_RATE" ]; then
  echo "+++ opt-in steady-state pass: PERF_STEADY_RATE=$PERF_STEADY_RATE warmup=$PERF_STEADY_WARMUP measure=$PERF_STEADY_DURATION mode=$PERF_NETWORK_MODE (config_profile=$CONFIG_PROFILE, baseline_eligible=$BASELINE_ELIGIBLE)"
  abort_if_sut_died
  STEADY_K6="mockserver-perf-k6-steady-${RUN_ID}"
  for d in "$PERF_STEADY_WARMUP" "$PERF_STEADY_DURATION"; do
    [[ "$d" =~ ^[0-9]+[smh]$ ]] || { echo "PERF_STEADY_WARMUP/DURATION must be <n>s|m|h, got '$d'" >&2; exit 1; }
  done
  STEADY_WARMUP_S="$(to_secs "$PERF_STEADY_WARMUP")"
  # Cumulative server request-duration histogram: count sum le5 le10 le25 le50 le100.
  steady_server_hist() {
    curl -s --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null | awk -F' ' '
      /^mock_server_request_duration_seconds_count /{c=$2}
      /^mock_server_request_duration_seconds_sum /{s=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.005"\}/{b5=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.01"\}/{b10=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.025"\}/{b25=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.05"\}/{b50=$2}
      /^mock_server_request_duration_seconds_bucket\{le="0\.1"\}/{b100=$2}
      END{printf "%s %s %s %s %s %s %s", c+0,s+0,b5+0,b10+0,b25+0,b50+0,b100+0}'
  }
  printf '%s' "$STEADY_K6" > "$ACTIVE_LOAD_FILE" 2>/dev/null || true
  # Background k6 so the shell can snapshot the server histogram at the warm-up
  # boundary and again at the end — the delta is the measured window, warm-up excluded.
  # shellcheck disable=SC2046
  docker run --rm --name "$STEADY_K6" "${NET_CLIENT[@]}" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$SUT_BASE_HTTP" \
    -e "PROTO=http" \
    -e "K6_STEADY_RATE=$PERF_STEADY_RATE" \
    -e "K6_STEADY_WARMUP=$PERF_STEADY_WARMUP" \
    -e "K6_STEADY_DURATION=$PERF_STEADY_DURATION" \
    -e "K6_STEADY_RESULT_PATH=/out/steady.json" \
    "$K6_IMAGE" run /k6/steady.js &
  STEADY_PID=$!
  sleep "$STEADY_WARMUP_S"
  STEADY_HIST_START="$(steady_server_hist)"
  wait "$STEADY_PID" || echo "WARNING: steady.js exited non-zero — steady block may be partial (notify-only)" >&2
  STEADY_HIST_END="$(steady_server_hist)"
  : > "$ACTIVE_LOAD_FILE" 2>/dev/null || true
  abort_if_sut_died
  # Fold the k6 client result together with the server-side window delta + comparison.
  STEADY_CLIENT_JSON="$(cat "$OUT_DIR/steady.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$STEADY_CLIENT_JSON" || STEADY_CLIENT_JSON='{}'
  # le50/le100 buckets are captured but not reported (the tail of interest is <=25ms).
  read -r sc0 ss0 s50 s100 s250 _ _ <<<"$STEADY_HIST_START"
  read -r sc1 ss1 e50 e100 e250 _ _ <<<"$STEADY_HIST_END"
  STEADY_JSON="$(jq -nc \
    --arg mode "$PERF_NETWORK_MODE" \
    --argjson rate "$PERF_STEADY_RATE" \
    --argjson client "$STEADY_CLIENT_JSON" \
    --argjson c0 "${sc0:-0}" --argjson c1 "${sc1:-0}" \
    --argjson su0 "${ss0:-0}" --argjson su1 "${ss1:-0}" \
    --argjson b5_0 "${s50:-0}" --argjson b5_1 "${e50:-0}" \
    --argjson b10_0 "${s100:-0}" --argjson b10_1 "${e100:-0}" \
    --argjson b25_0 "${s250:-0}" --argjson b25_1 "${e250:-0}" '
    (($c1 - $c0)) as $n
    | (($su1 - $su0)) as $sum
    | { network_mode:$mode, steady_rate:$rate,
        client: $client,
        server: {
          # Server request-duration histogram over the SAME measured window (deltas).
          req_dur_count: $n,
          req_dur_mean_ms: (if $n > 0 then (($sum / $n) * 1000 | .*1000|round/1000) else null end),
          frac_over_5ms:  (if $n > 0 then ((1 - (($b5_1 - $b5_0) / $n)) | .*100000|round/100000) else null end),
          frac_over_10ms: (if $n > 0 then ((1 - (($b10_1 - $b10_0) / $n)) | .*100000|round/100000) else null end),
          frac_over_25ms: (if $n > 0 then ((1 - (($b25_1 - $b25_0) / $n)) | .*100000|round/100000) else null end)
        } }
    # Comparison: is the client waiting tail present in the server histogram?
    | .comparison = {
        client_p99_ms: ($client.p99_ms // null),
        client_waiting_p99_ms: ($client.waiting_p99_ms // null),
        server_frac_over_5ms: (.server.frac_over_5ms),
        server_frac_over_10ms: (.server.frac_over_10ms)
      }' 2>/dev/null || echo '{}')"
  echo "--- steady: client p99=$(jq -r '.client.p99_ms//"?"' <<<"$STEADY_JSON")ms waiting_p99=$(jq -r '.client.waiting_p99_ms//"?"' <<<"$STEADY_JSON")ms | server over5ms=$(jq -r '.server.frac_over_5ms//"?"' <<<"$STEADY_JSON") over10ms=$(jq -r '.server.frac_over_10ms//"?"' <<<"$STEADY_JSON") (n=$(jq -r '.server.req_dur_count//"?"' <<<"$STEADY_JSON"))"
fi

# --- INFO-log-level publication arm (plan open question 5) ---------------------
# Re-measure ONLY the two PUBLISHED figure families — the knee curve (sweep.js) and
# the per-behaviour percentiles (regression.js, http + https_h2) — against a SECOND
# SUT running at the SHIPPED-DEFAULT log level (INFO). The owner's decision: the
# tracked baseline stays ERROR (a performance-sensitive user really does set ERROR,
# so publishing it is legitimate and every historical S3 run is ERROR), but the
# INFO figure is published ALONGSIDE it and LABELLED as INFO so nobody is misled
# about the out-of-the-box number.
#
# NON-GATING BY CONSTRUCTION. This arm never fails the step and never touches the
# ERROR baseline: (1) its output lands under DISTINCT keys (info_log_level_arm.*),
# not .behaviours / .sweep / rig_valid_peak_achieved_rps, so it can never be confused with or
# diffed against the ERROR series; (2) it is EXCLUDED from VALIDITY_CHECKS, so an
# INFO hiccup cannot flip validity.valid and block the ERROR baseline from being
# stored; (3) every fallible command is guarded so a failure degrades to
# measured:false, not an aborted run. Plan open question 9: land notify-only,
# observe ten runs, THEN attach a budget — the info_* budget entries carry no
# `gating` (notify-only) and `provisional: true` until that history exists.
#
# The INFO SUT is pinned to the SAME cpuset as the ERROR SUT (SERVER_CPUS) and runs
# HERE, right after the ERROR sweep, while the ERROR SUT is idle — so the INFO knee
# is measured under the same core budget as the ERROR knee (a fair comparison) with
# negligible contention. It is torn down immediately afterwards to free its heap
# before the growth phase. regression.js / sweep.js each seed AND reset the SUT they
# target, so pointing them at the INFO alias keeps the two SUTs isolated.
abort_if_sut_died
INFO_ARM_JSON='{}'
INFO_ARM_ATTEMPTED=false
INFO_HEAP_PROBE_JSON=null
if [ "$PERF_INFO_ARM" = "true" ]; then
  INFO_ARM_ATTEMPTED=true
  INFO_T_START="$(date -u +%s)"
  echo "--- INFO-log-level arm: starting SUT ($MOCKSERVER_IMAGE, MOCKSERVER_LOG_LEVEL=INFO, pinned to $SERVER_CPUS)"
  INFO_MEASURED=false
  # Published so its event-log gauges can be read; same memory, mount and image as the ERROR SUT,
  # but no harness event-log budget. Guarded with `|| true`
  # so a docker-run failure here degrades the (notify-only) INFO arm rather than
  # aborting the ERROR-baseline run under `set -e`; a failed start leaves either no
  # container or a kept one in `created`, both of which wait_ready fails at once, so the
  # arm records measured:false.
  start_mockserver "$INFO_SERVER" "$SERVER_CPUS" "$INFO_SERVER_ALIAS" "publish" "$SERVER_MEMORY" "$FILE_BODY_MOUNT" "INFO" "info" "" 1080 "$PERF_INFO_MAX_EVENT_LOG_BYTES" \
    && { docker logs -f "$INFO_SERVER" > "$DIAG_DIR/info/info-server.log" 2>&1 & INFO_LOG_PID=$!; } \
    || echo "WARNING: INFO SUT failed to start — INFO arm will record measured:false" >&2
  INFO_ELS_MAX_BYTES=""; INFO_ELS_MAX_ENTRIES=""; INFO_HEAP_MAX_BYTES=""; INFO_ELS_DROPPED=""; INFO_ELS_EVICTED=""
  INFO_ELS_SAMPLES="$DIAG_DIR/info/event-log-samples.txt"; : > "$INFO_ELS_SAMPLES" 2>/dev/null || true
  INFO_ELS_PRESSURE="$DIAG_DIR/info/event-log-pressure.csv"
  if wait_ready "$INFO_SERVER"; then
    INFO_MEASURED=true
    INFO_METRICS_HOSTPORT="$(docker port "$INFO_SERVER" 1080/tcp 2>/dev/null | head -1 || true)"
    INFO_METRICS=""
    [ -z "$INFO_METRICS_HOSTPORT" ] || INFO_METRICS="$(curl -sf --max-time 4 "http://${INFO_METRICS_HOSTPORT}/mockserver/metrics" 2>/dev/null || true)"
    INFO_ELS_MAX_BYTES="$(gauge_value mock_server_event_log_max_retained_bytes <<<"$INFO_METRICS")"
    INFO_ELS_MAX_ENTRIES="$(gauge_value mock_server_event_log_max_retained_entries <<<"$INFO_METRICS")"
    INFO_HEAP_MAX_BYTES="$(gauge_value 'jvm_memory_max_bytes{area="heap"}' <<<"$INFO_METRICS")"
    echo "--- INFO SUT event-log budget: maxEventLogSizeInBytes=${INFO_ELS_MAX_BYTES:-<unreadable>} maxLogEntries=${INFO_ELS_MAX_ENTRIES:-<unreadable>} heap_max=${INFO_HEAP_MAX_BYTES:-<unreadable>} (requested: ${PERF_INFO_MAX_EVENT_LOG_BYTES:-shipped default})"
    [ -z "$INFO_METRICS_HOSTPORT" ] || start_info_els_sampler "$INFO_METRICS_HOSTPORT" "$INFO_ELS_SAMPLES" "$INFO_ELS_PRESSURE"
    # Per-behaviour percentiles at INFO (http + https_h2), same durations/arms as the
    # ERROR regression so the two are comparable. Guarded: a k6 failure degrades this
    # arm to measured:false rather than aborting the (ERROR-baseline) run.
    run_regression "http"     "http://${INFO_SERVER_ALIAS}:1080"  "false" "info-regression-http.json"  || { echo "WARNING: INFO regression http failed — INFO arm degraded" >&2; INFO_MEASURED=false; }
    run_regression "https_h2" "https://${INFO_SERVER_ALIAS}:1080" "true"  "info-regression-https.json" || { echo "WARNING: INFO regression https_h2 failed — INFO arm degraded" >&2; INFO_MEASURED=false; }
    # Knee curve at INFO (same ladder as the ERROR sweep; no server-headroom test, see below).
    echo "--- INFO-log-level arm: sweep.js (knee curve at INFO)"
    run_sweep "$INFO_SWEEP_K6" "$INFO_SERVER_ALIAS" "$OUT_DIR/info-sweep.json" "$OUT_DIR/info-sweep-k6-cpu.csv" http "$PERF_INFO_HEAP_PROBE" \
      || { echo "WARNING: INFO sweep failed — INFO knee degraded" >&2; INFO_MEASURED=false; }
    stop_info_els_sampler
    INFO_METRICS=""
    [ -z "$INFO_METRICS_HOSTPORT" ] || INFO_METRICS="$(curl -sf --max-time 4 "http://${INFO_METRICS_HOSTPORT}/mockserver/metrics" 2>/dev/null || true)"
    IFS='|' read -r INFO_ELS_DROPPED INFO_ELS_EVICTED <<<"$(event_log_counters <<<"$INFO_METRICS")" || true
    # After every INFO measurement, so the stop-the-world histograms land in no rung.
    if [ "$PERF_INFO_HEAP_PROBE" = true ] && [ -n "$INFO_METRICS_HOSTPORT" ]; then
      echo "--- INFO-log-level arm: live-heap reset probe (histogram, PUT /mockserver/reset, GC.run, histogram; at most ${PERF_INFO_HEAP_PROBE_DEADLINE_S}s)"
      INFO_HEAP_PROBE_JSON="$(info_heap_probe_run "$INFO_SERVER" "$INFO_METRICS_HOSTPORT" "$DIAG_DIR/info" "$PERF_INFO_HEAP_PROBE_DEADLINE_S")" || true
      jq -e 'type == "object"' >/dev/null 2>&1 <<<"$INFO_HEAP_PROBE_JSON" || INFO_HEAP_PROBE_JSON='{"notify_only":true,"status":"failed","problems":["the probe printed no record"]}'
      echo "--- INFO live-heap reset probe: $(jq -r '"status \(.status); \(.released_entries // "n/a") entries, \(.released_counted_bytes // "n/a") counted bytes released; live heap fell \(.live_heap_delta_bytes // "n/a") bytes = \(.live_heap_per_counted_byte // "n/a")x counted, \(.live_heap_bytes_per_entry // "n/a") per entry"' <<<"$INFO_HEAP_PROBE_JSON" 2>/dev/null || echo unavailable)"
    fi
  else
    echo "WARNING: INFO SUT did not become ready — INFO arm skipped (notify-only, ERROR baseline unaffected)" >&2
  fi

  # Log level the INFO SUT ACTUALLY received (observed from its container env) — the
  # self-describing tag that lets a reader tell an INFO record from an ERROR one by
  # the DATA, not by which key it landed under (plan open question 5, point 4). Read
  # the same way the ERROR baseline's config.log_level is (container_env), i.e. an
  # extension of the existing config-block mechanism, not a parallel one.
  INFO_LOG_LEVEL_VAL="$(container_env MOCKSERVER_LOG_LEVEL "$INFO_SERVER")"; INFO_LOG_LEVEL_SRC="observed"
  [ -n "$INFO_LOG_LEVEL_VAL" ] || { INFO_LOG_LEVEL_VAL="INFO"; INFO_LOG_LEVEL_SRC="declared"; }
  if docker inspect "$INFO_SERVER" >/dev/null 2>&1; then
    INFO_ELS_REQUESTED="$(container_env MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES "$INFO_SERVER")"; INFO_ELS_REQUESTED_SRC="container-env"
  else
    INFO_ELS_REQUESTED="$PERF_INFO_MAX_EVENT_LOG_BYTES"; INFO_ELS_REQUESTED_SRC="declared"
  fi
  INFO_ELS_METHOD="$(info_els_method "$INFO_ELS_REQUESTED")"
  INFO_ELS_EXPECTED_DEFAULT=""
  [ -z "$INFO_HEAP_MAX_BYTES" ] || INFO_ELS_EXPECTED_DEFAULT="$(awk -v h="$INFO_HEAP_MAX_BYTES" -v d="$(els_default_divisor INFO)" 'BEGIN{ hk=int(h/1024)-20480; if (hk<0) hk=0; printf "%.0f", int(hk/d)*1024 }')"
  INFO_ELS_PEAK_BYTES=""; INFO_ELS_PEAK_ENTRIES=""
  read -r INFO_ELS_PEAK_BYTES INFO_ELS_PEAK_ENTRIES <<<"$(event_log_peaks "$INFO_ELS_SAMPLES")" || true
  INFO_ELS_JSON="$(event_log_budget_json "$INFO_ELS_METHOD" "$INFO_ELS_REQUESTED" "$INFO_ELS_REQUESTED_SRC" \
    "$INFO_ELS_MAX_BYTES" "$INFO_ELS_MAX_ENTRIES" "$INFO_HEAP_MAX_BYTES" "$INFO_ELS_EXPECTED_DEFAULT" \
    "$INFO_ELS_PEAK_BYTES" "$INFO_ELS_PEAK_ENTRIES" "$INFO_ELS_DROPPED" "$INFO_ELS_EVICTED" "$PERF_EVENT_LOG_APPROACH_RATIO")"
  jq -e . >/dev/null 2>&1 <<<"$INFO_ELS_JSON" || INFO_ELS_JSON="$(jq -nc --arg m "$INFO_ELS_METHOD" '{method: $m}')"
  INFO_ELS_PRESSURE_JSON="$(event_log_pressure_json "$OUT_DIR/info-sweep.json" "$INFO_ELS_PRESSURE")"
  jq -se 'length == 1' >/dev/null 2>&1 <<<"$INFO_ELS_PRESSURE_JSON" || INFO_ELS_PRESSURE_JSON=null

  # Merge the http + https_h2 behaviours (guarded to {} on any missing/unparsable
  # file), derive the INFO saturation with the SHARED derive_saturation, then assemble the
  # self-describing block. No server CPU log is passed (the diag sampler watches the ERROR
  # SUT), so only the k6 CPU client-limited test applies here, unlike the ERROR arm.
  INFO_BEHAVIOURS="$(jq -sc '(.[0].behaviours // {}) + (.[1].behaviours // {})' \
    "$OUT_DIR/info-regression-http.json" "$OUT_DIR/info-regression-https.json" 2>/dev/null || true)"
  # Exactly one document: with a file missing jq still prints the merge of the rest and exits non-zero.
  jq -se 'length == 1' >/dev/null 2>&1 <<<"$INFO_BEHAVIOURS" || INFO_BEHAVIOURS='{}'
  INFO_SWEEP_JSON="$(cat "$OUT_DIR/info-sweep.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$INFO_SWEEP_JSON" || INFO_SWEEP_JSON='{}'
  if [ -f "$OUT_DIR/info-sweep.json" ]; then
    INFO_SATURATION_JSON="$(derive_saturation "$OUT_DIR/info-sweep.json" "$OUT_DIR/info-sweep-k6-cpu.csv" "$LAST_SWEEP_T0" 2>/dev/null || echo '{}')"
  else
    INFO_SATURATION_JSON='{}'
  fi
  jq -e . >/dev/null 2>&1 <<<"$INFO_SATURATION_JSON" || INFO_SATURATION_JSON='{}'

  INFO_ARM_JSON="$(jq -n \
    --argjson measured "$INFO_MEASURED" \
    --arg log_level "$INFO_LOG_LEVEL_VAL" --arg log_level_src "$INFO_LOG_LEVEL_SRC" \
    --arg image "$MOCKSERVER_IMAGE" --arg image_digest "$IMAGE_DIGEST" \
    --arg server_cpus "${SERVER_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
    --argjson event_log_budget "$INFO_ELS_JSON" \
    --argjson event_log_pressure "$INFO_ELS_PRESSURE_JSON" \
    --argjson live_heap_reset_probe "$INFO_HEAP_PROBE_JSON" \
    --argjson behaviours "$INFO_BEHAVIOURS" \
    --argjson sweep "$INFO_SWEEP_JSON" \
    --argjson saturation "$INFO_SATURATION_JSON" '
    {
      # The log level is carried EXPLICITLY in the record so a reader can tell an
      # INFO number from an ERROR number by the data alone — never by convention or
      # by which key it landed under. The ERROR baseline is self-described the same
      # way at top-level .config.log_level; this is the INFO twin of that tag.
      measured: $measured,
      config: {
        log_level: $log_level,
        log_level_source: $log_level_src,
        image: $image,
        image_digest: $image_digest,
        cpusets: { server: $server_cpus, k6: $k6_cpus },
        # .method is the compare key; runs without it forced 256 MiB.
        event_log_budget: $event_log_budget
      },
      # Per-behaviour percentiles (http + https_h2 merged), SAME shape as the ERROR
      # .behaviours but under a distinct key so it is never fingerprint-matched or
      # diffed against the ERROR series.
      behaviours: $behaviours,
      # The knee curve at INFO + its derived saturation (rig_valid_peak_achieved_rps here
      # is the rig-valid peak — max achieved over rig-valid rungs, a property of the k6 rig,
      # see derive_saturation; saturation_rps is the ladder-quantised knee). Its
      # server_headroom_test is "off", so it is not comparable with the ERROR-arm peak.
      sweep: { proto: ($sweep.proto // "http"), latency_window: ($sweep.latency_window // null), points: ($sweep.points // []) },
      saturation: $saturation,
      # Report-only: per-rung drops by reason, the first rung that dropped, and the ring / in-flight peaks.
      event_log_pressure: $event_log_pressure,
      # Notify-only: live heap the drained log held, per counted byte and per entry; null when not run.
      live_heap_reset_probe: $live_heap_reset_probe,
      rig_valid_peak_achieved_rps: ($saturation.rig_valid_peak_achieved_rps // null),
      saturation_rps: ($saturation.saturation_rps // null)
    }')"
  INFO_PEAK="$(jq -r '.rig_valid_peak_achieved_rps // "n/a"' <<<"$INFO_ARM_JSON")"
  INFO_SAT="$(jq -r '.saturation_rps // "n/a"' <<<"$INFO_ARM_JSON")"
  INFO_T_END="$(date -u +%s)"
  echo "--- INFO event-log pressure: $(jq -r 'if . == null then "no samples" else
      "first drops at the \(.first_drop_rung_rps // "no") rps rung; sampled drops \(.dropped_log_events // "n/a") (ring_full \(.dropped_by_reason.ring_full // "n/a"), in_flight_bytes \(.dropped_by_reason.in_flight_bytes // "n/a")); peak ring \(.peak_ring_occupancy // "n/a")/\(.ring_capacity // "n/a"), peak in-flight \(.peak_in_flight_bytes // "n/a")/\(.max_in_flight_bytes // "n/a") bytes" end' <<<"$INFO_ELS_PRESSURE_JSON" 2>/dev/null || echo unavailable)"
  echo "--- INFO-log-level arm done (log_level=${INFO_LOG_LEVEL_VAL} measured=${INFO_MEASURED} rig_valid_peak_achieved_rps=${INFO_PEAK} saturation_rps=${INFO_SAT}); added wall-clock $((INFO_T_END - INFO_T_START))s"
  # Free the INFO SUT's heap before the growth phase (cleanup() also reaps it).
  docker rm -f "$INFO_SERVER" >/dev/null 2>&1 || true
else
  echo "--- INFO-log-level arm skipped (PERF_INFO_ARM=$PERF_INFO_ARM)"
fi

# --- resource sampler (background) --------------------------------------------
# Append timestamped CPU% (docker stats) + heap bytes + gc seconds (metrics) every
# SAMPLE_INTERVAL seconds. Runs only during the growth phase so the trajectory is
# attributable to the sustained fill load.
sampler() {
  echo "ts,cpu_pct,heap_bytes,gc_seconds,threads" > "$SAMPLE_LOG"
  while true; do
    local cpu metrics heap gc threads ts
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$SERVER" 2>/dev/null | tr -d '% ' || echo '')"
    ts="$(date -u +%s)"
    metrics="$(curl -s --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || echo '')"
    heap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}')"
    gc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_seconds_sum/{s+=$2} END{print s}')"
    threads="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_threads_current/{print $2}')"
    printf '%s,%s,%s,%s,%s\n' "$ts" "${cpu:-}" "${heap:-}" "${gc:-}" "${threads:-}" >> "$SAMPLE_LOG"
    sleep "$SAMPLE_INTERVAL"
  done
}

abort_if_sut_died
if arm_only; then
  echo "ts,cpu_pct,heap_bytes,gc_seconds,threads" > "$SAMPLE_LOG"
else
  echo "--- growth.js (sustained load + resource sampling)"
  GROWTH_SUT_BASE_URL="${SERVER_METRICS_URL%/mockserver/metrics}"
  scope_growth_event_log_budget "$GROWTH_SUT_BASE_URL" || echo "WARNING: $GROWTH_ELS_PROBLEM" >&2
  GROWTH_ELS_T0="$(date -u +%s)"
  sampler & SAMPLER_PID=$!
  # Closes 10 s before the load's scheduled end, so no sample meets growth.js's teardown reset.
  open_live_histo_window "$(( $(to_secs "${K6_GROWTH_DURATION:-6m}") - 10 ))"
  # shellcheck disable=SC2046
  docker run --rm "${NET_CLIENT[@]}" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$SUT_BASE_HTTP" \
    -e "K6_GROWTH_RESULT_PATH=/out/growth.json" \
    ${K6_GROWTH_DURATION:+-e K6_GROWTH_DURATION="$K6_GROWTH_DURATION"} \
    ${K6_GROWTH_RATE:+-e K6_GROWTH_RATE="$K6_GROWTH_RATE"} \
    ${K6_GROWTH_PROBE:+-e K6_GROWTH_PROBE="$K6_GROWTH_PROBE"} \
    "$K6_IMAGE" run /k6/growth.js
  close_live_histo_window
  kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""
  GROWTH_ELS_T1="$(date -u +%s)"
  restore_growth_event_log_budget "$GROWTH_SUT_BASE_URL"
fi

# --- forward.js (forward connection-pool regression guard) --------------------
# Runs the previously-dark forward guard against the dedicated upstream already
# started for the run. Its error-rate threshold is the real gate: with pooling
# regressed the SUT opens a fresh upstream socket per request, exhausts ephemeral
# ports (BindException) and the error rate spikes. k6 exits non-zero on a
# threshold breach (aborting threshold), so the exit is captured tolerantly and
# the verdict folded into the result. A breach is a REAL regression (surfaced by
# compare's forward.error_rate row), NOT a rig-invalidity, so it does not touch
# the validity block. Runs last: forward.js resets the SUT in teardown.
abort_if_sut_died
FORWARD_EXIT=0
# Secondary phase: skipped under host mode (multi-SUT phases do not run there) and arm-only runs.
if [ "$PERF_NETWORK_MODE" != host ] && ! arm_only; then
  echo "--- forward.js (forward connection-pool regression guard)"
  # shellcheck disable=SC2046
  docker run --rm "${NET_CLIENT[@]}" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$SUT_BASE_HTTP" \
    -e "FORWARD_UPSTREAM_HOST=${UPSTREAM_ALIAS}:1080" \
    -e "K6_FORWARD_RESULT_PATH=/out/forward.json" \
    ${K6_FWD_PEAK_RATE:+-e K6_FWD_PEAK_RATE="$K6_FWD_PEAK_RATE"} \
    ${K6_FWD_HOLD:+-e K6_FWD_HOLD="$K6_FWD_HOLD"} \
    "$K6_IMAGE" run /k6/forward.js || FORWARD_EXIT=$?
  if [ "$FORWARD_EXIT" -ne 0 ]; then
    echo "WARNING: forward.js exited $FORWARD_EXIT — forward-pool guard threshold TRIPPED (error rate over limit)" >&2
  fi
fi
FORWARD_JSON="$(cat "$OUT_DIR/forward.json" 2>/dev/null || echo '{}')"
abort_if_sut_died

# --- proxy.js: forward proxy + TLS/mTLS handshake -----------------------------
# ONE run, TWO phases (the handshake phase shares the forward phase's containers and run):
#   forward mode — MockServer AS A PROXY. The k6 container gets HTTP_PROXY /
#       HTTPS_PROXY pointed at the SUT, so an http:// upstream target becomes an
#       absolute-URI forward and an https:// target a CONNECT tunnel carrying TLS
#       through the SUT to the upstream already started for the run (MockServer may
#       terminate that tunnel TLS itself with a generated cert). Emits a
#       .behaviours object (forward_absolute_proxy / forward_connect_proxy) that
#       perf-test-compare.sh picks up via its existing behaviours.* budgets with NO
#       jq change — which DOES grow the k6 arm-set fingerprint and so resets the k6
#       baseline ONCE (intended: the arm set changed). The SUT is NOT reset/seeded
#       for this — an absolute-URI/CONNECT request addressed to the UPSTREAM is
#       proxied regardless of the SUT's own expectations.
#   handshake mode — MockServer's INBOUND TLS handshake cost, which the
#       https_h2 regression run reuses-away to ~0. k6 hits three DISTINCT SUTs with
#       noConnectionReuse (a fresh TCP+TLS handshake every iteration): TLS 1.3
#       server-only (the main SUT), mTLS-required, and native-provider-absent (the
#       Dockerfile's documented JDK-provider fallback, forced via
#       -Dio.netty.handler.ssl.noOpenSsl=true — nothing else exercises it under
#       load). Emits a .tls_handshake object; this step augments each arm with
#       server CPU + JVM allocation per handshake, sampled per-SUT across the run.
# BEST-EFFORT / NON-FATAL (like the laptop profile): a proxy/handshake failure must
# never cost the k6/growth result its place in the baseline. Both blocks default to
# {} and compare is head-driven, so an absent block simply emits zero metrics.
MTLS="mockserver-mtls-${RUN_ID}"
JDK_SUT="mockserver-jdk-${RUN_ID}"
HS_CERT_DIR=""
cleanup_proxy() {
  docker rm -f "$MTLS" "$JDK_SUT" >/dev/null 2>&1 || true
  [ -n "${HS_CERT_DIR:-}" ] && rm -rf "$HS_CERT_DIR" >/dev/null 2>&1 || true
}
PROXY_FWD_JSON='{}'
HANDSHAKE_JSON='{}'
if [ "${PERF_PROXY_PROFILE:-true}" = "true" ]; then
  # -- forward-proxy latency arms (behaviours) --
  echo "--- proxy.js (item 9a: forward-proxy — absolute-URI + CONNECT tunnel)"
  # shellcheck disable=SC2046
  docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "K6_PROXY_MODE=forward" \
    -e "HTTP_PROXY=http://${SERVER_ALIAS}:1080" -e "HTTPS_PROXY=http://${SERVER_ALIAS}:1080" \
    -e "http_proxy=http://${SERVER_ALIAS}:1080" -e "https_proxy=http://${SERVER_ALIAS}:1080" \
    -e "NO_PROXY=" -e "no_proxy=" \
    -e "FORWARD_UPSTREAM_HOST=${UPSTREAM_ALIAS}:1080" \
    -e "K6_PROXY_RESULT_PATH=/out/proxy-forward.json" \
    ${K6_PROXY_RATE:+-e K6_PROXY_RATE="$K6_PROXY_RATE"} \
    ${K6_PROXY_DURATION:+-e K6_PROXY_DURATION="$K6_PROXY_DURATION"} \
    ${K6_PROXY_WARMUP:+-e K6_PROXY_WARMUP="$K6_PROXY_WARMUP"} \
    "$K6_IMAGE" run /k6/proxy.js || echo "WARNING: proxy.js forward mode failed — no proxy behaviours this run (notify-only)" >&2
  PROXY_FWD_JSON="$(cat "$OUT_DIR/proxy-forward.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$PROXY_FWD_JSON" || PROXY_FWD_JSON='{}'

  # -- 14: TLS/mTLS/native-absent handshake arms --
  # openssl generates a throwaway CA + client cert (the mTLS arm presents the
  # client cert; the mTLS SUT trusts the CA). No openssl => skip handshake (the
  # forward arms above still stand). Runs on the agent, not in a container.
  if command -v openssl >/dev/null 2>&1; then
    HS_CERT_DIR="$(mktemp -d "${TMPDIR:-/tmp}/perf-hs-certs.XXXXXX")"
    chmod 0755 "$HS_CERT_DIR"
    (
      cd "$HS_CERT_DIR"
      openssl req -x509 -newkey rsa:2048 -keyout ca.key -out ca.pem -days 2 -nodes -subj "/CN=perf-mtls-ca" 2>/dev/null
      openssl req -newkey rsa:2048 -keyout client.key -out client.csr -nodes -subj "/CN=perf-mtls-client" 2>/dev/null
      openssl x509 -req -in client.csr -CA ca.pem -CAkey ca.key -CAcreateserial -out client.pem -days 2 2>/dev/null
      chmod 0644 ca.pem client.pem client.key
    )
    if [ -s "$HS_CERT_DIR/client.pem" ] && [ -s "$HS_CERT_DIR/ca.pem" ]; then
      echo "--- item 14 handshake SUTs: mTLS (trusts generated CA) + native-absent (noOpenSsl)"
      # mTLS SUT: require + trust our CA. native-absent SUT: force Netty's JDK
      # provider (the documented fallback). Metrics ENABLED on both so the
      # per-handshake CPU/alloc sampling below can read their counters.
      # shellcheck disable=SC2046
      docker run -d --rm --name "$MTLS" --network "$NETWORK" --network-alias mockserver-mtls \
        $(cpuset_arg "$UPSTREAM_CPUS") -v "$HS_CERT_DIR:/hs-certs:ro" \
        -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
        -e MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED=true \
        -e MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN=/hs-certs/ca.pem \
        "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null 2>&1 || true
      # shellcheck disable=SC2046
      docker run -d --rm --name "$JDK_SUT" --network "$NETWORK" --network-alias mockserver-jdk \
        $(cpuset_arg "$UPSTREAM_CPUS") \
        -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
        -e JAVA_TOOL_OPTIONS="$(compose_java_tool_options "$SUT_IMAGE_JAVA_TOOL_OPTIONS" '-Dio.netty.handler.ssl.noOpenSsl=true')" \
        "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null 2>&1 || true
      wait_ready "$MTLS" || true
      wait_ready "$JDK_SUT" || true
      # Seed /simple over PLAIN HTTP on each SUT (port unification: mTLS gates only
      # TLS handshakes, so a plain-HTTP control-plane call needs no client cert).
      # tls13 arm hits the main SUT ($SERVER_ALIAS), which forward.js reset — reseed.
      for alias in "$SERVER_ALIAS" mockserver-mtls mockserver-jdk; do
        docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -X PUT \
          "http://${alias}:1080/mockserver/expectation" -H 'Content-Type: application/json' \
          -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"simple"},"times":{"unlimited":true}}]' \
          -o /dev/null -w "seed ${alias} HTTP %{http_code}\n" || true
      done

      # mTLS NEGATIVE control (committed assertion): a TLS request WITHOUT a client
      # cert MUST be rejected. The k6 mtls arm is positive-only (it always presents a
      # cert), so on its own it cannot distinguish "mTLS enforced + cert accepted"
      # from "mTLS not enforced at all" — a positive-only probe is exactly the shape
      # that has bitten this repo before. Here curl with NO cert must fail the
      # handshake (curl exit 35/56, no 2xx). If it returns a 2xx, mTLS is NOT being
      # enforced and the mtls arm's number is not an mTLS measurement — record that on
      # the arm (mtls_enforced:false) and warn loudly, rather than baselining it as if
      # it were. Non-fatal (the whole handshake phase is notify-only best-effort).
      MTLS_ENFORCED=null   # unknown until positively proven either way
      # Fail CLOSED, not open. A no-cert probe that merely fails to get a 2xx proves
      # nothing: a timeout, a curl image that would not start, or any transport fault
      # would otherwise be recorded as "mTLS enforced" - a control confirming something
      # it never tested. So require POSITIVE evidence of a TLS-layer rejection (curl 35
      # SSL connect error / 56 recv failure) before claiming enforcement, and emit null
      # (unknown) for anything else.
      MTLS_NOCERT_CODE="$(docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 \
        -s -k -o /dev/null -w '%{http_code}' --max-time 8 "https://mockserver-mtls:1080/simple" 2>/dev/null)" || MTLS_NOCERT_RC=$?
      MTLS_NOCERT_RC="${MTLS_NOCERT_RC:-0}"
      if grep -qE '^2..$' <<<"${MTLS_NOCERT_CODE:-000}"; then
        MTLS_ENFORCED=false
        echo "WARNING: mTLS negative control FAILED — https://mockserver-mtls:1080/simple returned ${MTLS_NOCERT_CODE} with NO client cert, so tlsMutualAuthenticationRequired is NOT being enforced; the mtls handshake arm is therefore not a true mTLS measurement this run." >&2
      elif [ "$MTLS_NOCERT_RC" = "35" ] || [ "$MTLS_NOCERT_RC" = "56" ]; then
        MTLS_ENFORCED=true
        echo "--- mTLS negative control OK: no-cert request rejected at the TLS layer (curl exit ${MTLS_NOCERT_RC}), so mutual auth IS enforced"
      else
        MTLS_ENFORCED=null
        echo "WARNING: mTLS negative control INCONCLUSIVE — no-cert probe neither got a 2xx nor failed at the TLS layer (curl exit ${MTLS_NOCERT_RC}, http_code ${MTLS_NOCERT_CODE:-000}); enforcement is UNPROVEN this run, so mtls_enforced is recorded as null rather than assumed true." >&2
      fi

      # Per-SUT resource snapshot: cumulative JVM allocation counter (monotonic —
      # end-start is exact allocation over the window). Null until the SUT image is
      # built after the com.sun shade-relocation fix, which suppressed the metric in
      # every shipped jar - not a sign the arm is broken.
      # Plus requests_received_count (the EXACT handshake
      # denominator, since noConnectionReuse => 1 request per fresh handshake, and a
      # delta matches the same window as the resource delta). Scraped over the
      # network so no host port publishing is needed.
      hs_metric() { # alias  metric_prefix
        docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s --max-time 5 \
          "http://$1:1080/mockserver/metrics" 2>/dev/null \
          | awk -v p="$2" '$1==p {print $2; exit}' || true
      }
      declare -A HS_ALIAS=( [tls13]="$SERVER_ALIAS" [mtls]=mockserver-mtls [jdk]=mockserver-jdk )
      declare -A A0 R0
      for arm in tls13 mtls jdk; do
        A0[$arm]="$(hs_metric "${HS_ALIAS[$arm]}" jvm_memory_allocated_bytes)"
        R0[$arm]="$(hs_metric "${HS_ALIAS[$arm]}" requests_received_count)"
      done

      # Background per-SUT CPU sampler (docker stats — host-side, no container spawn)
      # over the handshake window; integrated to CPU-seconds below.
      HS_CPU_LOG="$OUT_DIR/handshake-cpu.csv"; echo "ts,arm,cpu_pct" > "$HS_CPU_LOG"
      HS_CPU_PID=""
      hs_cpu_sampler() {
        while true; do
          for arm in tls13 mtls jdk; do
            local nm cpu ts
            case "$arm" in tls13) nm="$SERVER";; mtls) nm="$MTLS";; jdk) nm="$JDK_SUT";; esac
            cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$nm" 2>/dev/null | tr -d '% ' || echo '')"
            ts="$(date -u +%s)"
            [ -n "$cpu" ] && printf '%s,%s,%s\n' "$ts" "$arm" "$cpu" >> "$HS_CPU_LOG"
          done
          sleep "${PERF_HS_SAMPLE_INTERVAL:-2}"
        done
      }
      hs_cpu_sampler & HS_CPU_PID=$!
      HS_T0="$(date -u +%s)"

      echo "--- proxy.js (item 14: TLS 1.3 + mTLS + native-absent handshake cost)"
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
        -v "$OUT_DIR:/out" -v "$HS_CERT_DIR:/hs-certs:ro" \
        -e "K6_PROXY_MODE=handshake" \
        -e "K6_HS_TLS13_URL=https://${SERVER_ALIAS}:1080" \
        -e "K6_HS_MTLS_URL=https://mockserver-mtls:1080" \
        -e "K6_HS_JDK_URL=https://mockserver-jdk:1080" \
        -e "K6_HS_CLIENT_CERT=/hs-certs/client.pem" -e "K6_HS_CLIENT_KEY=/hs-certs/client.key" \
        -e "K6_PROXY_RESULT_PATH=/out/proxy-handshake.json" \
        ${K6_HS_RATE:+-e K6_HS_RATE="$K6_HS_RATE"} \
        ${K6_HS_DURATION:+-e K6_HS_DURATION="$K6_HS_DURATION"} \
        "$K6_IMAGE" run /k6/proxy.js || echo "WARNING: proxy.js handshake mode failed — no tls_handshake block this run (notify-only)" >&2

      kill "$HS_CPU_PID" >/dev/null 2>&1 || true
      HS_T1="$(date -u +%s)"; HS_WINDOW_S=$(( HS_T1 - HS_T0 )); [ "$HS_WINDOW_S" -gt 0 ] || HS_WINDOW_S=1

      HANDSHAKE_JSON="$(cat "$OUT_DIR/proxy-handshake.json" 2>/dev/null || echo '{}')"
      jq -e . >/dev/null 2>&1 <<<"$HANDSHAKE_JSON" || HANDSHAKE_JSON='{}'

      # Augment each arm with cpu_ms_per_handshake + alloc_kb_per_handshake.
      # denominator = requests_received delta on that arm's SUT (exact handshakes in
      # the window). alloc delta / handshakes -> bytes; cpu-seconds = mean(cpu%)/100
      # * window / handshakes. Any missing piece (metric absent, zero handshakes)
      # leaves that field null (compare drops nulls) rather than emitting a bogus 0.
      for arm in tls13 mtls jdk; do
        a1="$(hs_metric "${HS_ALIAS[$arm]}" jvm_memory_allocated_bytes)"
        r1="$(hs_metric "${HS_ALIAS[$arm]}" requests_received_count)"
        hs="$(awk -v a="${R0[$arm]:-}" -v b="${r1:-}" 'BEGIN{ if(a!=""&&b!=""&&(b-a)>0) printf "%d", b-a; else print "" }')"
        alloc_kb="$(awk -v a="${A0[$arm]:-}" -v b="${a1:-}" -v n="$hs" 'BEGIN{ if(a!=""&&b!=""&&n!=""&&n+0>0&&(b-a)>0) printf "%.3f", (b-a)/n/1024; else print "null" }')"
        cpu_ms="$(awk -F',' -v arm="$arm" -v w="$HS_WINDOW_S" -v n="$hs" '
          $2==arm { s+=$3; c++ }
          END{ if(c>0 && n!=""&& n+0>0){ mean=s/c; printf "%.4f", (mean/100.0*w*1000.0)/n } else print "null" }' "$HS_CPU_LOG")"
        HANDSHAKE_JSON="$(jq -c --arg arm "$arm" --argjson akb "${alloc_kb:-null}" --argjson cms "${cpu_ms:-null}" --argjson hs "${hs:-null}" '
          if (.tls_handshake[$arm]) then
            .tls_handshake[$arm].alloc_kb_per_handshake = $akb
            | .tls_handshake[$arm].cpu_ms_per_handshake = $cms
            | .tls_handshake[$arm].resource_handshakes = $hs
          else . end' <<<"$HANDSHAKE_JSON" 2>/dev/null || echo "$HANDSHAKE_JSON")"
      done
      # Record the mTLS-enforcement verdict from the negative control on the mtls arm
      # (a boolean, not a budgeted metric — compare ignores it) so a reader can see
      # whether the mtls figure is a true mTLS measurement or (mtls_enforced:false) a
      # server that did not require the cert. The jdk arm carries no equivalent
      # committed assertion: nothing MockServer exposes at runtime reports which Netty
      # SslProvider is active, and io.netty.handler.ssl.OpenSsl availability is cached
      # at class-init, so an in-JVM assertion would need a forked JVM with the property
      # — not cheap. The flip from OPENSSL to JDK under -Dio.netty.handler.ssl.noOpenSsl
      # is a documented Netty invariant (verified out-of-band against this build's
      # Netty), and the container carries the property (see the JDK_SUT run above); the
      # jdk arm's higher handshake cost vs tls13 is the expected corroborating signal.
      HANDSHAKE_JSON="$(jq -c --argjson enf "$MTLS_ENFORCED" 'if (.tls_handshake.mtls) then .tls_handshake.mtls.mtls_enforced = $enf else . end' <<<"$HANDSHAKE_JSON" 2>/dev/null || echo "$HANDSHAKE_JSON")"
      echo "--- tls_handshake augmented: $(jq -c '.tls_handshake | to_entries | map({(.key): {p50:.value.handshake_p50_ms, hps:.value.handshakes_per_s, cpu_ms:.value.cpu_ms_per_handshake, alloc_kb:.value.alloc_kb_per_handshake}})' <<<"$HANDSHAKE_JSON" 2>/dev/null)"
    else
      echo "WARNING: openssl produced no client cert — skipping handshake arms (forward arms still recorded)" >&2
    fi
  else
    echo "WARNING: openssl unavailable — skipping item 14 handshake arms (forward arms still recorded)" >&2
  fi
  cleanup_proxy
fi

abort_if_sut_died
# --- OPT-IN workload: unmatched-proxy concurrency --------------------------------
# Skipped entirely unless PERF_WORKLOAD=forward, so the default run is unchanged.
# Runs AFTER the normal measurement and is never baseline-eligible (the
# config_profile flip above already excludes it). Re-uses the run's existing
# upstream; no new long-lived container.
#
# Make the UNMATCHED-proxy in-flight cap visible. The default proxy arm
# hits a fast upstream (in-flight ~0.04, far below the ~poolSize cap), so it cannot
# show it. Re-seed the upstream /simple with a server-side delay so
# offered_rate x latency exceeds the blocking action-handler pool, then re-drive
# proxy.js's absolute-URI arm (= handleUnmatchedProxyForward, the path that carries the in-flight
# cap) at elevated concurrency. delivery_ratio < 1 / p99 blow-up on the
# forward_absolute arm is the cap; an image A/B across the cap change reads it.
#
# FAIL-CLOSED: adds a validity check asserting the server actually served via the
# FORWARDED path and reached the SLOW upstream — proven by the forward_absolute p50
# meeting a floor of PERF_UPSTREAM_DELAY_MS x 0.8 (a /simple mock on the SUT or a
# fast/shadowed upstream answers in ~0 ms and cannot clear it). A run that silently
# fell back reds the build (add_check ok=false -> validity.valid=false).
WORKLOAD_JSON='{}'
if [ "$PERF_WORKLOAD" = "forward" ] && [ "$PERF_NETWORK_MODE" != host ]; then
  echo "+++ opt-in workload: PERF_WORKLOAD=forward (config_profile=$CONFIG_PROFILE, baseline_eligible=$BASELINE_ELIGIBLE)"
  WL_FWD_RATE="${PERF_WORKLOAD_FORWARD_RATE:-500}"
  WL_FWD_VUS="${PERF_WORKLOAD_FORWARD_VUS:-256}"
  WL_FWD_DURATION="${PERF_WORKLOAD_FORWARD_DURATION:-1m}"
  # CRITICAL — reset the SUT first. A prior phase (the TLS handshake block)
  # seeds a persistent /simple MOCK on the SUT; without this reset the proxied
  # absolute-URI GET /simple MATCHES that mock (HttpActionHandler) and is served
  # locally, so handleUnmatchedProxyForward (the capped path) is never reached and
  # the run measures the mock. Resetting clears it so /simple is UNMATCHED and
  # therefore forwarded. Safe for later phases: each (streaming, clustered,
  # per-core sweep) seeds its own SUT in setup(); none rely on this SUT's state.
  # Codes are checked (curl without -f treats 4xx/5xx as success) so a failed reset
  # reds the run rather than leaving the old fast /simple shadowing the slow one.
  echo "--- resetting SUT + re-seeding upstream /simple with ${PERF_UPSTREAM_DELAY_MS}ms delay (unit-21 slow upstream)"
  SUT_RESET_CODE="$(docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code}' -X PUT "http://${SERVER_ALIAS}:1080/mockserver/reset")"
  UP_RESET_CODE="$(docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code}' -X PUT "http://${UPSTREAM_HOSTPORT}/mockserver/reset")"
  UP_SEED_CODE="$(docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -o /dev/null -w '%{http_code}' -X PUT \
    "http://${UPSTREAM_HOSTPORT}/mockserver/expectation" -H 'Content-Type: application/json' \
    -d "[{\"httpRequest\":{\"path\":\"/simple\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"upstream\",\"delay\":{\"timeUnit\":\"MILLISECONDS\",\"value\":${PERF_UPSTREAM_DELAY_MS}}},\"times\":{\"unlimited\":true}}]")"
  echo "--- SUT reset=${SUT_RESET_CODE} upstream reset=${UP_RESET_CODE} upstream slow-seed=${UP_SEED_CODE}"
  WL_SETUP_OK=true
  [ "$SUT_RESET_CODE" = "200" ] || WL_SETUP_OK=false
  [ "$UP_RESET_CODE" = "200" ]  || WL_SETUP_OK=false
  [ "$UP_SEED_CODE" = "201" ]   || WL_SETUP_OK=false
  WL_FWD_EXIT=0
  if [ "$WL_SETUP_OK" = "true" ]; then
    echo "--- workload proxy.js forward mode (rate=${WL_FWD_RATE}/arm, vus=${WL_FWD_VUS}, upstream_delay=${PERF_UPSTREAM_DELAY_MS}ms)"
    # shellcheck disable=SC2046
    docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
      -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
      -v "$OUT_DIR:/out" \
      -e "K6_PROXY_MODE=forward" \
      -e "HTTP_PROXY=http://${SERVER_ALIAS}:1080" -e "HTTPS_PROXY=http://${SERVER_ALIAS}:1080" \
      -e "http_proxy=http://${SERVER_ALIAS}:1080" -e "https_proxy=http://${SERVER_ALIAS}:1080" \
      -e "NO_PROXY=" -e "no_proxy=" \
      -e "FORWARD_UPSTREAM_HOST=${UPSTREAM_ALIAS}:1080" \
      -e "K6_PROXY_RATE=$WL_FWD_RATE" \
      -e "K6_PROXY_PRE_VUS=$WL_FWD_VUS" -e "K6_PROXY_MAX_VUS=$WL_FWD_VUS" \
      -e "K6_PROXY_DURATION=$WL_FWD_DURATION" \
      -e "K6_PROXY_RESULT_PATH=/out/workload-forward.json" \
      "$K6_IMAGE" run /k6/proxy.js || WL_FWD_EXIT=$?
  else
    echo "WARNING: workload setup failed (SUT reset ${SUT_RESET_CODE}, upstream reset ${UP_RESET_CODE}, upstream seed ${UP_SEED_CODE}) — NOT running proxy.js; the workload check will red" >&2
    WL_FWD_EXIT=-1
  fi
  WL_FWD_RESULT="$(cat "$OUT_DIR/workload-forward.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$WL_FWD_RESULT" || WL_FWD_RESULT='{}'
  # served = the forward_absolute (unmatched-proxy) arm's relayed responses; p50 is
  # the RELAY latency, which must clear the slow-upstream floor to prove it forwarded.
  WL_FWD_SAMPLES="$(jq -r '(.behaviours.forward_absolute_proxy.sample_count // 0)' <<<"$WL_FWD_RESULT" 2>/dev/null || echo 0)"
  WL_FWD_DELIVERY="$(jq -r '(.behaviours.forward_absolute_proxy.delivery_ratio // null)' <<<"$WL_FWD_RESULT" 2>/dev/null || echo null)"
  WL_FWD_P50="$(jq -r '(.behaviours.forward_absolute_proxy.p50_ms // "null")' <<<"$WL_FWD_RESULT" 2>/dev/null || echo null)"
  WL_MIN_P50="$(awk -v d="$PERF_UPSTREAM_DELAY_MS" 'BEGIN{printf "%.3f", d*0.8}')"
  WORKLOAD_JSON="$(jq -nc \
    --arg target forward --argjson delay_ms "$PERF_UPSTREAM_DELAY_MS" \
    --argjson rate "$WL_FWD_RATE" --argjson vus "$WL_FWD_VUS" \
    --argjson k6_exit "$WL_FWD_EXIT" --arg p50_floor_ms "$WL_MIN_P50" \
    --arg sut_reset_code "$SUT_RESET_CODE" --arg upstream_reset_code "$UP_RESET_CODE" --arg upstream_seed_code "$UP_SEED_CODE" \
    --argjson result "$WL_FWD_RESULT" \
    '{target:$target, upstream_delay_ms:$delay_ms, offered_rate_per_arm:$rate, vus:$vus, k6_exit:$k6_exit,
      p50_floor_ms:($p50_floor_ms|tonumber),
      setup:{sut_reset_code:$sut_reset_code, upstream_reset_code:$upstream_reset_code, upstream_seed_code:$upstream_seed_code},
      forward:$result}')"
  if [ "$WL_SETUP_OK" = "true" ] && [ "$WL_FWD_EXIT" -eq 0 ] \
     && awk -v n="$WL_FWD_SAMPLES" 'BEGIN{exit !(n+0>0)}' \
     && awk -v p="$WL_FWD_P50" -v f="$WL_MIN_P50" 'BEGIN{ if(p=="null"||p=="") exit 1; exit !(p+0>=f+0) }'; then
    add_check "workload_forward_served_via_upstream" true "unmatched-proxy (forward_absolute) served ${WL_FWD_SAMPLES} responses; p50=${WL_FWD_P50}ms >= floor ${WL_MIN_P50}ms (= ${PERF_UPSTREAM_DELAY_MS}ms x0.8) proves the relay reached the SLOW upstream (handleUnmatchedProxyForward), not a local /simple mock; delivery_ratio=${WL_FWD_DELIVERY} (below 1.0 => the unit-21 in-flight cap bound)"
  else
    add_check "workload_forward_served_via_upstream" false "PERF_WORKLOAD=forward did not measure the forwarded path: setup_ok=${WL_SETUP_OK} (SUT reset ${SUT_RESET_CODE}, upstream reset ${UP_RESET_CODE}, upstream seed ${UP_SEED_CODE}), k6_exit=${WL_FWD_EXIT}, forward_absolute served=${WL_FWD_SAMPLES}, p50=${WL_FWD_P50}ms vs floor ${WL_MIN_P50}ms. A p50 below the floor means the SUT answered a /simple mock or a fast/shadowed upstream (~0 ms) instead of forwarding to the slow upstream, so unit-21's path was NOT exercised; refusing to record it as healthy"
  fi
fi
abort_if_sut_died

# --- path coverage (lib/perf-path-coverage.sh): dedicated SUTs, notify-only ----------
run_path_coverage
abort_if_sut_died

# --- laptop startup + footprint profile (notify-only) -----------------
# Runs LAST, after every k6 phase, so the box is quiet: the docker sub-items
# (ready-median-of-9, idle RSS + threads at --memory 256m/512m/1g, compressed
# image size) launch their own throwaway containers and need only docker + the SUT
# image. The in-JVM sub-items — the number a MockServerExtension suite
# actually pays per test class, and init-file scaling — need a shaded jar and a
# JDK; the jar is docker-cp'd out of the SUT image (path fixed by the Dockerfile)
# and bench_laptop.py SKIPS the in-JVM sub-items loudly if a jar or `java` is unavailable (e.g. a
# JRE-only agent). Emits a `laptop` block merged into result.json; every metric is
# notify-only (laptop.*.<metric> budgets omit `gating`). NON-FATAL by construction:
# the SUT ($SERVER) is still running-but-idle here, so its per-container cgroup RSS
# never pollutes a laptop container's own `docker stats` reading, and a laptop
# measurement failure must never lose the k6/growth result the run exists for.
LAPTOP_JSON='{}'
# Record INTENT separately from success (values vs presence): `laptop_attempted`
# says the producer tried to measure the profile this run, so compare can tell
# "attempted and failed wholesale" (an empty/incomplete `.laptop` → RED) apart from
# "profile disabled" (never attempted → silent). Without it, `laptop: {}` means both.
LAPTOP_ATTEMPTED=false
if [ "${PERF_LAPTOP_PROFILE:-true}" = "true" ]; then
  LAPTOP_ATTEMPTED=true
  LAPTOP_JAR="${PERF_LAPTOP_JAR:-}"
  if [ -z "$LAPTOP_JAR" ]; then
    _lc="$(docker create "$MOCKSERVER_IMAGE" 2>/dev/null || true)"
    if [ -n "$_lc" ]; then
      # Current images ship the jar split in two (own classes + deps); older ones one fat jar.
      if docker cp "$_lc:/mockserver.jar" "$OUT_DIR/mockserver.jar" >/dev/null 2>&1 \
         && docker cp "$_lc:/mockserver-deps.jar" "$OUT_DIR/mockserver-deps.jar" >/dev/null 2>&1; then
        LAPTOP_JAR="$OUT_DIR/mockserver.jar:$OUT_DIR/mockserver-deps.jar"
      elif docker cp "$_lc:/mockserver-netty-jar-with-dependencies.jar" \
        "$OUT_DIR/mockserver.jar" >/dev/null 2>&1; then
        LAPTOP_JAR="$OUT_DIR/mockserver.jar"
      fi
      docker rm "$_lc" >/dev/null 2>&1 || true
    fi
  fi
  echo "--- item 8 laptop profile (settle ${PERF_LAPTOP_SETTLE:-30}s; jar=${LAPTOP_JAR:-none})"
  # DELIBERATELY NOT an add_check: laptop.* is notify-only, so a laptop-measurement
  # failure must NOT flip .validity.valid false and make compare refuse the whole k6
  # run. On failure the run simply carries no `laptop` block (compare iterates head
  # metrics, so an absent block emits zero laptop metrics — no missing-budget trip).
  if python3 "$REPO_ROOT/scripts/perf/bench_laptop.py" all \
        --jar "$LAPTOP_JAR" --image "$MOCKSERVER_IMAGE" \
        --port "${PERF_LAPTOP_PORT:-23080}" --warmups 1 --runs 9 \
        --settle "${PERF_LAPTOP_SETTLE:-30}" --out "$OUT_DIR/laptop.json"; then
    LAPTOP_JSON="$(cat "$OUT_DIR/laptop.json" 2>/dev/null || echo '{}')"
    # Guard the result-assembly jq: an empty/partial/invalid laptop file must never
    # make `--argjson laptop` fail and lose the whole result. Fall back to {} if the
    # emitted block is not valid JSON.
    jq -e . >/dev/null 2>&1 <<<"$LAPTOP_JSON" || LAPTOP_JSON='{}'
    echo "--- laptop block: $(jq -c '.laptop | keys' <<<"$LAPTOP_JSON" 2>/dev/null)"
  else
    echo "WARNING: laptop profile measurement failed — result carries no laptop block this run (notify-only, does not gate)" >&2
  fi
fi

# --- N parallel instances on one host (laptop / MockServerExtension profile) ---
# OPT-IN (PERF_LAPTOP_PARALLEL=true), OFF by default: this spins {1,4,8,16,32} MockServer
# instances twice (an in-JVM shape and a container shape), so it is a deliberately-scheduled
# research profile, NOT added to every daily run — the serving_percore opt-in pattern. Every
# metric it emits is NOTIFY-ONLY (the laptop.*.<metric> budgets omit `gating`), so a flagged
# regression annotates but never fails the build. Merged into result.json under `.laptop_parallel`
# with two sub-shapes {injvm, container}, each a list of per-N runs; perf-test-compare.sh keys them
# laptop_parallel.<shape>_<N>.<metric> against the laptop.* wildcard budgets. NON-FATAL by
# construction (like the laptop / streaming profiles): the SUT ($SERVER) is idle here, and
# a measurement failure must never cost the k6/growth result its baseline place — on any failure the
# block simply stays {} and compare (head-driven) emits zero laptop_parallel metrics, no
# missing-budget trip. NOT an add_check for the same reason the laptop block is not.
LAPTOP_PARALLEL_JSON='{}'
if [ "${PERF_LAPTOP_PARALLEL:-false}" = "true" ]; then
  echo "--- item 17 parallel-instances profile (opt-in; PERF_LAPTOP_PARALLEL=true)"
  LP_COUNTS="${PERF_LAPTOP_PARALLEL_COUNTS:-1,4,8,16,32}"
  LP_INJVM='{}'
  LP_CONTAINER='{}'
  # in-JVM shape: needs a jar-with-dependencies on the classpath (reuse the one the laptop block
  # already extracted into $LAPTOP_JAR) and a JDK on PATH. Single-file source launch prints the
  # block as `BENCH_JSON {...}` on stdout; a JRE-only agent or a missing jar SKIPS this shape.
  if [ -n "${LAPTOP_JAR:-}" ] && command -v java >/dev/null 2>&1; then
    if LP_INJVM_RAW="$(java -Xmx"${PERF_LAPTOP_PARALLEL_XMX:-2g}" -cp "$LAPTOP_JAR" \
          "$REPO_ROOT/scripts/perf/InJvmParallelBench.java" \
          --counts "$LP_COUNTS" --label injvm 2>/dev/null \
          | sed -n 's/^BENCH_JSON //p' | tail -1)"; then
      if jq -e . >/dev/null 2>&1 <<<"$LP_INJVM_RAW"; then LP_INJVM="$LP_INJVM_RAW"; fi
    fi
    echo "--- laptop_parallel in-JVM runs: $(jq -r '(.runs|length)//0' <<<"$LP_INJVM" 2>/dev/null)"
  else
    echo "--- laptop_parallel in-JVM shape SKIPPED (no jar-with-deps or no java on PATH)" >&2
  fi
  # container shape: needs docker + the SUT image. Writes `{"container":{...,"runs":[...]}}` to --out.
  if command -v docker >/dev/null 2>&1; then
    if python3 "$REPO_ROOT/scripts/perf/parallel_instances.py" \
          --image "$MOCKSERVER_IMAGE" --counts "$LP_COUNTS" \
          --settle "${PERF_LAPTOP_PARALLEL_SETTLE:-6}" \
          --out "$OUT_DIR/laptop-parallel-container.json" >/dev/null 2>&1; then
      LP_CONTAINER_RAW="$(jq -c '.container // {}' "$OUT_DIR/laptop-parallel-container.json" 2>/dev/null || echo '{}')"
      if jq -e . >/dev/null 2>&1 <<<"$LP_CONTAINER_RAW"; then LP_CONTAINER="$LP_CONTAINER_RAW"; fi
    fi
    echo "--- laptop_parallel container runs: $(jq -r '(.runs|length)//0' <<<"$LP_CONTAINER" 2>/dev/null)"
  else
    echo "--- laptop_parallel container shape SKIPPED (no docker on PATH)" >&2
  fi
  LAPTOP_PARALLEL_JSON="$(jq -cn --argjson injvm "$LP_INJVM" --argjson container "$LP_CONTAINER" \
    '{injvm:$injvm, container:$container}' 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$LAPTOP_PARALLEL_JSON" || LAPTOP_PARALLEL_JSON='{}'
  printf '%s' "$LAPTOP_PARALLEL_JSON" > "$OUT_DIR/laptop-parallel.json"
fi

abort_if_sut_died
# --- LLM/SSE streaming under concurrency -----------------------------
# Drive STREAMING.concurrency concurrent SSE streams (streaming.js, constant-vus)
# while measuring the four streaming metrics. Two of them k6 CANNOT see (it buffers
# SSE), so they are taken server-side HERE, aligned to k6's phase timeline:
#   - inter-token delay error: a tiny single-threaded reader
#     (tools/sse-fidelity-reader.py) times consecutive `data:` lines. Run IDLE (the
#     match_baseline phase, no streams open — the client-jitter FLOOR / positive
#     control) and again UNDER LOAD (the stream-load phase). The idle floor proves
#     any error growth is the SERVER (scheduler-thread starvation delaying the
#     per-token writeEvent tasks), not the reader — the reader is identical and
#     unloaded in both. Reported as a DISTRIBUTION (p50/p95/p99), never a mean:
#     the failure mode is a fat TAIL while the median stays on time.
#   - heap per open stream: the heap FLOOR (min jvm_memory_used_bytes{area=heap}
#     over a window, the post-GC saw-tooth valley) with the streams open, minus the
#     idle floor, over the concurrency. INCLUDES the streaming log-ring retention
#     (bounded small by config.js STREAMING's arithmetic), so it is an upper bound
#     on per-connection state, which the report states plainly.
# The other two (match-p95 A/B, stream delivery) come from k6's own result. A
# CallerRunsPolicy counter is NOT emitted: MockServer exposes none, and the
# policy does not fire under load (ScheduledThreadPoolExecutor's DelayedWorkQueue
# is unbounded, so CallerRunsPolicy fires only at shutdown — see the report).
# BEST-EFFORT / NON-FATAL like the proxy/laptop profiles: a failure defaults the
# block to {} and never costs the k6/growth result its baseline place.
STREAMING_JSON='{}'
if [ "${PERF_STREAMING:-true}" = "true" ]; then
  if command -v python3 >/dev/null 2>&1; then
    S_WARMUP="${K6_STREAM_WARMUP:-20s}"; S_BASE="${K6_STREAM_MATCH_BASELINE_DURATION:-45s}"
    # No S_LOAD: k6 receives K6_STREAM_LOAD_DURATION directly below, and only when it is
    # SET — so a script-side default here would never reach k6 (config.js supplies the real
    # default). Keeping one would be a value that looks like configuration and configures nothing.
    S_SETTLE="${K6_STREAM_SETTLE:-10s}"
    # Default concurrency 600 (config.js's 100 is a light local default), at delay 20 ms
    # against the dedicated 1-CPU / 2-scheduler-thread / 2-event-loop-thread SUT below:
    # just past the CI knee, where the match A/B control means something. The knee is
    # box-dependent and sharp on CI, so recalibrate ON CI (not a laptop) after any change
    # that moves per-request allocation. Curve, history and retention arithmetic:
    # docs/code/performance-measurement.md, "k6 load-shape sizing".
    S_CONC="${K6_STREAM_CONCURRENCY:-600}"; S_DELAY="${K6_STREAM_DELAY_MS:-20}"; S_PATH="${K6_STREAM_PATH:-/stream}"
    # No `sl` for S_LOAD on purpose: the under-load sample is taken INSIDE the load
    # window (STREAM_LOAD_START + sst + 3), not after it, so its duration is never needed.
    sw="$(to_secs "$S_WARMUP")"; sb="$(to_secs "$S_BASE")"; sst="$(to_secs "$S_SETTLE")"
    STREAM_LOAD_START=$(( sw + sb ))
    READER="$REPO_ROOT/mockserver-performance-test/k6/tools/sse-fidelity-reader.py"
    # --- dedicated, deliberately-constrained streaming SUT ---------------------
    # WHY a separate SUT and not the shared 6-core $SERVER: on the pinned CI box
    # actionHandlerThreadCount = max(5, 6) = 6, and 200 concurrent streams at 50
    # tokens/s is ~10k trivial writeEvent tasks/s — nowhere near that pool's knee,
    # so match_p95_ratio reads ~1.0 and the tripwire measures nothing (the MAJOR
    # review finding). A dedicated SUT with a SMALL action-handler pool (default 2)
    # and a low CPU quota (default 1) puts the knee at a modest, DETERMINISTIC
    # concurrency independent of the agent size, so a real scheduling regression
    # moves the number. The scheduler (per-token delay) path is what this exercises;
    # a bounded --memory makes the heap-floor measurement meaningful (it GCs).
    # Constrain BOTH pools: the action-handler pool is where per-token delays queue
    # (fidelity), and the event-loop pool is what a concurrent match shares (the
    # A/B). Leaving the event loops at the default 5 let them absorb the streaming
    # writeAndFlush pressure, so the match A/B stayed flaky near ~1x (measured).
    STREAM_SUT_CPUS="${PERF_STREAM_SUT_CPUS:-1}"
    STREAM_SUT_THREADS="${PERF_STREAM_SUT_THREADS:-2}"
    STREAM_SUT_EVENTLOOP_THREADS="${PERF_STREAM_SUT_EVENTLOOP_THREADS:-2}"
    STREAM_SUT_MEMORY="${PERF_STREAM_SUT_MEMORY:-1g}"
    echo "--- streaming SUT (dedicated, constrained: cpus=${STREAM_SUT_CPUS}, action-handler-threads=${STREAM_SUT_THREADS}, event-loop-threads=${STREAM_SUT_EVENTLOOP_THREADS}, mem=${STREAM_SUT_MEMORY})"
    docker run -d --rm --name "$STREAM_SUT" --network "$NETWORK" --network-alias "mockserver-stream" \
      --cpus "$STREAM_SUT_CPUS" --memory "$STREAM_SUT_MEMORY" -p 127.0.0.1::1080 \
      -e MOCKSERVER_LOG_LEVEL=ERROR -e MOCKSERVER_DISABLE_SYSTEM_OUT=true -e MOCKSERVER_METRICS_ENABLED=true \
      -e "MOCKSERVER_ACTION_HANDLER_THREAD_COUNT=$STREAM_SUT_THREADS" \
      -e "MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT=$STREAM_SUT_EVENTLOOP_THREADS" \
      "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null 2>&1 \
      || echo "WARNING: could not start dedicated streaming SUT — no streaming block this run (notify-only)" >&2
    wait_ready "$STREAM_SUT" || echo "WARNING: dedicated streaming SUT not ready" >&2
    # The reader + heap sampler hit the dedicated SUT's HOST-published port (serves
    # /stream AND /mockserver/metrics), so they run on the agent with no extra
    # container CPU and never touch the shared $SERVER's numbers.
    STREAM_HOSTPORT="$(docker port "$STREAM_SUT" 1080/tcp 2>/dev/null | head -1)"
    R_HOST="${STREAM_HOSTPORT%%:*}"; R_PORT="${STREAM_HOSTPORT##*:}"
    [ -n "$R_HOST" ] || R_HOST="127.0.0.1"
    STREAM_METRICS_URL="http://${STREAM_HOSTPORT:-127.0.0.1:1080}/mockserver/metrics"
    # Heap FLOOR: min of N ~1s heap samples. %d avoids the E-notation some images
    # emit (jq --argjson could not parse "1.1E8"); floor tracks the live set far
    # better than an instantaneous sample on the GC saw-tooth.
    stream_heap_floor() {
      local n="$1"
      for _ in $(seq 1 "$n"); do
        curl -s --max-time 4 "$STREAM_METRICS_URL" 2>/dev/null \
          | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}'
        sleep 1
      done | awk 'NF{v=$1+0; if(m==""||v<m)m=v} END{if(m=="")print "";else printf "%d",m}'
    }
    echo "--- streaming.js (item 12: LLM/SSE under concurrency=${S_CONC}, delay=${S_DELAY}ms, dedicated constrained SUT)"
    # shellcheck disable=SC2046
    docker run -d --rm --name "$STREAM_K6" --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
      -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
      -e "BASE_URL=http://mockserver-stream:1080" \
      -e "K6_STREAM_CONCURRENCY=$S_CONC" \
      -e "K6_STREAM_RESULT_PATH=/out/streaming.json" \
      ${K6_STREAM_WARMUP:+-e K6_STREAM_WARMUP="$K6_STREAM_WARMUP"} \
      ${K6_STREAM_MATCH_BASELINE_DURATION:+-e K6_STREAM_MATCH_BASELINE_DURATION="$K6_STREAM_MATCH_BASELINE_DURATION"} \
      ${K6_STREAM_LOAD_DURATION:+-e K6_STREAM_LOAD_DURATION="$K6_STREAM_LOAD_DURATION"} \
      ${K6_STREAM_SETTLE:+-e K6_STREAM_SETTLE="$K6_STREAM_SETTLE"} \
      ${K6_STREAM_TOKENS:+-e K6_STREAM_TOKENS="$K6_STREAM_TOKENS"} \
      ${K6_STREAM_DELAY_MS:+-e K6_STREAM_DELAY_MS="$K6_STREAM_DELAY_MS"} \
      ${K6_STREAM_MATCH_RATE:+-e K6_STREAM_MATCH_RATE="$K6_STREAM_MATCH_RATE"} \
      "$K6_IMAGE" run /k6/streaming.js >/dev/null 2>&1 \
      || echo "WARNING: could not launch streaming.js — no streaming block this run (notify-only)" >&2
    ST0="$(date -u +%s)"
    stream_sleep_until() { local target="$1" now; now=$(( $(date -u +%s) - ST0 )); [ "$target" -gt "$now" ] && sleep $(( target - now )) || true; }
    # IDLE control: inside the match_baseline phase, before any stream opens.
    stream_sleep_until $(( sw + 3 ))
    STREAM_HEAP_IDLE="$(stream_heap_floor 5)"
    python3 "$READER" --host "$R_HOST" --port "$R_PORT" --path "$S_PATH" \
      --delay-ms "$S_DELAY" --streams 3 --max-tokens 60 --label idle \
      --out "$OUT_DIR/fidelity-idle.json" || echo "WARNING: idle fidelity reader failed" >&2
    # UNDER LOAD: after the stream-load phase start + its settle window.
    stream_sleep_until $(( STREAM_LOAD_START + sst + 3 ))
    STREAM_HEAP_LOAD="$(stream_heap_floor 6)"
    python3 "$READER" --host "$R_HOST" --port "$R_PORT" --path "$S_PATH" \
      --delay-ms "$S_DELAY" --streams 3 --max-tokens 60 --label load \
      --out "$OUT_DIR/fidelity-load.json" || echo "WARNING: load fidelity reader failed" >&2
    # Wait for the background k6 to finish writing its result.
    while grep -q "^${STREAM_K6}$" <<<"$(docker ps --format '{{.Names}}' || true)"; do sleep 2; done
    K6_STREAM_OUT="$(cat "$OUT_DIR/streaming.json" 2>/dev/null || echo '{}')"; jq -e . >/dev/null 2>&1 <<<"$K6_STREAM_OUT" || K6_STREAM_OUT='{}'
    FID_IDLE="$(cat "$OUT_DIR/fidelity-idle.json" 2>/dev/null || echo '{}')"; jq -e . >/dev/null 2>&1 <<<"$FID_IDLE" || FID_IDLE='{}'
    FID_LOAD="$(cat "$OUT_DIR/fidelity-load.json" 2>/dev/null || echo '{}')"; jq -e . >/dev/null 2>&1 <<<"$FID_LOAD" || FID_LOAD='{}'
    HEAP_PER_STREAM="$(awk -v a="${STREAM_HEAP_IDLE:-}" -v b="${STREAM_HEAP_LOAD:-}" -v n="$S_CONC" 'BEGIN{ if(a!=""&&b!=""&&n+0>0&&(b-a)>0) printf "%.1f",(b-a)/n; else print "null" }')"
    STREAMING_JSON="$(jq -c \
      --argjson fid "$FID_IDLE" --argjson fl "$FID_LOAD" \
      --argjson hi "${STREAM_HEAP_IDLE:-null}" --argjson hl "${STREAM_HEAP_LOAD:-null}" \
      --argjson hps "${HEAP_PER_STREAM:-null}" '
      (.streaming // {}) as $s | $s + {
        intertoken_error_idle_p50_ms: ($fid.error_p50_ms // null),
        intertoken_error_idle_p95_ms: ($fid.error_p95_ms // null),
        intertoken_error_idle_p99_ms: ($fid.error_p99_ms // null),
        intertoken_error_load_p50_ms: ($fl.error_p50_ms // null),
        intertoken_error_load_p95_ms: ($fl.error_p95_ms // null),
        intertoken_error_load_p99_ms: ($fl.error_p99_ms // null),
        intertoken_error_p95_ratio: (if (($fid.error_p95_ms // 0) > 0 and $fl.error_p95_ms != null) then (($fl.error_p95_ms / $fid.error_p95_ms) * 1000 | round) / 1000 else null end),
        intertoken_reader_streams_idle: ($fid.streams_ok // null),
        intertoken_reader_streams_load: ($fl.streams_ok // null),
        heap_idle_floor_bytes: $hi, heap_streaming_floor_bytes: $hl, heap_bytes_per_stream: $hps
      }' <<<"$K6_STREAM_OUT")"
    jq -e . >/dev/null 2>&1 <<<"$STREAMING_JSON" || STREAMING_JSON='{}'
    echo "--- streaming: $(jq -c '{match_p95_ratio, match_under_stream_p95_ms, intertoken_error_load_p99_ms, heap_bytes_per_stream}' <<<"$STREAMING_JSON" 2>/dev/null)"
  else
    echo "WARNING: python3 not on the agent — streaming fidelity/heap metrics skipped (notify-only, no streaming block)" >&2
  fi
fi

abort_if_sut_died
# --- clustered state under load (within-run A/B) ---------------------
# The StateBackend SPI + Infinispan backend move expectation reads and event-log
# writes onto a network for the central deployment the owner named; no number
# existed for what that costs. This block runs regression.js UNCHANGED against two
# targets IN THE SAME RUN and records the per-arm RATIO (clustered / control):
#
#   control  : ONE MockServer, stateBackend=memory   (the default InMemory backend)
#   candidate: TWO MockServers, stateBackend=infinispan + clusterEnabled=true,
#              sharing state over a JGroups TCP/TCPPING transport (REPL_SYNC)
#
# BOTH targets run the SAME clustered image (PERF_CLUSTERED_IMAGE), so the ONLY
# variable between the arms is the backend — the CandidateIndexBenchmark within-run
# A/B discipline the plan names as the repo's gold standard: the ratio cancels
# host / JVM / GC / image noise almost entirely, which is why .clustered_state
# rides the FULL baseline (NOT the k6 arm-set fingerprint or the image digest) in
# perf-test-compare.sh.
#
# REUSE, DON'T REBUILD: PERF_CLUSTERED_IMAGE defaults to the snapshot-clustered
# image, which java-docker-push-snapshot.sh publishes on every master merge from
# the SAME shaded jar, the SAME commit and the SAME job as the SUT image — so it is
# PULLED here (up front, beside the SUT pull), never built. Locally point it at the
# container-tests `integration_testing_clustered` image, which is assembled from the
# SAME clustered-libs jars the container-tests pipeline already builds (netty fat
# jar + /libs/* = Infinispan + JGroups), and set
# PERF_CLUSTERED_REQUIRE_MATCHING_REVISION=false because that image carries no
# revision label. We do NOT build Infinispan a second time here, and we do NOT run
# Maven on the measurement box — a multi-module reactor build in the measurement job
# immediately before measuring would perturb the very thing being measured.
#
# WHAT THE HOT PATH ACTUALLY TOUCHES (why the ratio is what it is): seedRegression
# seeds every expectation with times:{unlimited:true}, so there is NO per-request
# Times CAS (clusterSharedTimesEnabled fires only on a bounded Times). Matching
# reads hit each node's LOCAL compiled-matcher cache (reconciled via invalidation),
# not a network read per request, and the event log is node-local. So the per-
# request network cost for this read/forward/template mix is ~zero; what the ratio
# captures is the STEADY-STATE overhead of running the clustered stack on the
# request path (Infinispan on the classpath, background JGroups FD_ALL3 heartbeats
# + STABLE gossip stealing a little CPU) plus the per-window seed replication. That
# is the honest, useful answer for the central deployment: how far a node's per-
# request latency moves merely by being a clustered member.
#
# RETENTION (the OOM lesson): the count-bounded event-log ring holds FULL bodies,
# residence = maxLogEntries / total_ACHIEVED_rps (LONGER, without bound, as achieved
# throughput falls). k6 drives ONLY the entry node (control, or cluster node A), so
# ONLY that node's ring fills with request bodies — exactly like the main SUT, and it
# gets the SAME PERF_CLUSTERED_MEMORY (2 GB default -> 0.9 GiB heap,
# maxLogEntries ~= 115,500). The per-arm rate x residence x body figures
# (large_10mb@0.1rps -> ~172 MB, large_1mb@0.5rps -> ~86 MB,
# large-4KB@200rps -> ~137 MB) hold ONLY at residence ~172 s; they run away as this node
# contends. So the clustered nodes get the fixed harness maxEventLogSizeInBytes body-byte OOM
# guard (start_clu, above; HARNESS_FIXED_EVENT_LOG_BYTES), which caps total retained body bytes
# regardless of residence — and applies identically on control and cluster, so it does
# not bias the ratio. The
# SECOND node (B) receives NO request load, so its ring stays ~empty; it holds only
# the seeded expectation set (tiny, re-seeded per window) + bounded JGroups buffers (UFC/MFC
# 2M each, NAKACK2/UNICAST3 send/recv, FRAG2 60K — single-digit MB). REPL_SYNC
# replicates only expectation/scenario WRITES, of which there are ~none beyond each
# window's seed and reset, so node B never accumulates bodies. The clustered arm therefore
# adds no retention risk beyond the control's, per node.
#
# PAIRED, PINNED, THROTTLE-RECORDED (lib/perf-clustered-ab.sh): all three servers stay up on
# disjoint cpusets carved from the idle main SUT's, the arms alternate in adjacent windows, and each
# ratio is the median of the per-pair ratios. With 3 pairs a linear drift leaves a one-window bias
# whose sign flips with build parity, so it averages out across the baseline, not within a run.
# Each window records its containers' cgroup throttling. A missing window, a window over 1% errors,
# a missing ratio or a cluster that is not genuine before AND after the windows gives measured:false,
# and compare then reads no clustered_state rows (as for pipelining_inorder).
#
# BEST-EFFORT / NOTIFY-ONLY like streaming/laptop: a failure defaults the block to
# {} and never costs the k6/growth result its baseline place. BUT an empty block
# must not read as both "off" and "broken". So `clustered_attempted`
# turns true ONLY once the clustered image is present and we start forming the
# cluster; from then a cluster that forms but produces nothing (view < 2, state did
# not cross, or a null ratio) makes the `clustered_metrics_present` validity check
# FALSE and REDS the build (loud), while PERF_CLUSTERED=false or an absent image
# stays a green skip.
#
# WHY A SKIP IS NOW A REAL SIGNAL: the clustered image is published on every master
# merge and pulled up front, so it SHOULD be present on every run. A skip therefore
# means something broke (the publish, the pull, or the commit pairing) rather than
# "not wired yet", and `clustered_skip_reason` names WHICH — it rides the result so
# the compare annotation can say why instead of guessing "probably not published".
CLUSTERED_JSON='{}'
CLUSTERED_ATTEMPTED=false
# "" once attempted; otherwise one of: disabled | image_absent | revision_mismatch.
CLUSTERED_SKIP_REASON="disabled"
CLU_IMAGE_REVISION=""
CLU_CTRL_BEHAVIOURS='{}'
CLU_CAND_BEHAVIOURS='{}'
# --- gate: decide whether the A/B can honestly run, and record WHY not ---------
# Two independent reasons to skip, each with its own reason token. Kept SEPARATE
# from the measurement block below (a flat decision, then one guarded body) so the
# detection logic stays readable and the body keeps its original nesting.
if [ "${PERF_CLUSTERED:-true}" = "true" ]; then
  CLUSTERED_SKIP_REASON="image_absent"
  if docker image inspect "$CLU_IMAGE" >/dev/null 2>&1; then
    # Provenance pairing. The run is filed under the SUT image's revision
    # ($IMAGE_REVISION, resolved far above), so a clustered image from a DIFFERENT
    # commit would produce a perfectly well-formed ratio for code this run was not
    # measuring — the stale-tag failure mode that makes a comparison silently
    # meaningless. Both tags are pushed by the same job from the same commit, so a
    # mismatch is an anomaly (a failed clustered push leaving the tag one merge
    # behind, or a merge landing between the two pulls), not routine.
    #
    # A mismatch SKIPS rather than measures: an honest, counted gap is strictly
    # better than a number filed against code that never ran. It does NOT red the
    # build (this block is notify-only throughout) but it is reported distinctly.
    # Empty == label absent, exactly as the SUT provenance check treats it. When the
    # SUT itself is unlabelled there is nothing to pair against, so the check stands
    # down and the SUT's own provenance verdict governs.
    CLU_IMAGE_REVISION="$(docker image inspect --format '{{index .Config.Labels "org.opencontainers.image.revision"}}' "$CLU_IMAGE" 2>/dev/null || true)"
    if [ "$CLU_IMAGE_REVISION" = "<no value>" ]; then CLU_IMAGE_REVISION=""; fi
    if [ "${PERF_CLUSTERED_REQUIRE_MATCHING_REVISION:-true}" = "true" ] \
       && [ -n "${IMAGE_REVISION:-}" ] && [ "$CLU_IMAGE_REVISION" != "${IMAGE_REVISION:-}" ]; then
      CLUSTERED_SKIP_REASON="revision_mismatch"
      echo "WARNING: clustered image '$CLU_IMAGE' revision '${CLU_IMAGE_REVISION:-<absent>}' does NOT match the" >&2
      echo "         SUT image revision '${IMAGE_REVISION}' this run is attributed to — clustered A/B SKIPPED" >&2
      echo "         (notify-only). Measuring it would file a clustered/in-memory ratio under a commit whose" >&2
      echo "         clustered code the measured binary never contained. Usual cause: the clustered push in" >&2
      echo "         the ':docker: build and push :snapshot' step failed, leaving the tag a merge behind." >&2
      echo "         Set PERF_CLUSTERED_REQUIRE_MATCHING_REVISION=false for a LOCAL run against an" >&2
      echo "         unlabelled image. NOT counted as attempted." >&2
    else
      CLUSTERED_SKIP_REASON=""
    fi
  else
    echo "WARNING: clustered image '$CLU_IMAGE' not present — clustered A/B SKIPPED (notify-only)." >&2
    echo "         This tag is published on every master merge by java-docker-push-snapshot.sh and" >&2
    echo "         pulled up front by this script, so an absent image means the publish or the pull" >&2
    echo "         failed — check the ':docker: build and push :snapshot' step and the pull WARNING" >&2
    echo "         earlier in this log. Locally, set PERF_CLUSTERED_IMAGE to the container-tests" >&2
    echo "         clustered image (plus PERF_CLUSTERED_REQUIRE_MATCHING_REVISION=false), or" >&2
    echo "         PERF_CLUSTERED=false to disable. NOT counted as attempted." >&2
  fi
fi

if [ -z "$CLUSTERED_SKIP_REASON" ]; then
    CLUSTERED_ATTEMPTED=true
    CLU_T_START="$(date -u +%s)"
    CLU_CPUS="${PERF_CLUSTERED_CPUS:-2}"
    CLU_MEM="${PERF_CLUSTERED_MEMORY:-2g}"
    CLU_NAME="perf-cluster-${RUN_ID}"
    # All three servers stay up and the arms alternate in PERF_CLUSTERED_PAIRS pairs of adjacent
    # windows; each ratio is the median of the per-pair ratios (lib/perf-clustered-ab.sh). Pairs
    # after the first reuse warm servers, so they get the shorter K6_CLU_PAIR_WARMUP.
    CLU_PAIRS="${PERF_CLUSTERED_PAIRS:-3}"
    if ! [[ "$CLU_PAIRS" =~ ^[1-9]$ ]]; then
      echo "WARNING: PERF_CLUSTERED_PAIRS='$CLU_PAIRS' is not 1-9; using 3" >&2; CLU_PAIRS=3
    fi
    CLU_WARMUP="${K6_CLU_REG_WARMUP:-20s}"
    CLU_PAIR_WARMUP="${K6_CLU_PAIR_WARMUP:-5s}"
    CLU_DURATION="${K6_CLU_REG_DURATION:-30s}"
    clu_resolve_pinning "${SERVER_CPUS:-}" "$CLU_CPUS"
    echo "--- clustered A/B: image=$CLU_IMAGE mem=$CLU_MEM pairs=$CLU_PAIRS warmup=$CLU_WARMUP then $CLU_PAIR_WARMUP duration=$CLU_DURATION"
    echo "--- clustered A/B: cpu $CLU_PIN_MODE ($CLU_PIN_REASON): control=${CLU_CTRL_CPUS:-$CLU_CPUS cpus} node_a=${CLU_A_CPUS:-$CLU_CPUS cpus} node_b=${CLU_B_CPUS:-$CLU_CPUS cpus}"

    # JGroups TCP + TCPPING transport for two SEPARATE containers (the built-in
    # loopback stack is in-JVM only and would form two clusters of one). initial_hosts
    # lists both node aliases; bind matches the container's eth0 on the docker network.
    # Written world-readable so the image's non-root user can read the mount.
    CLU_JG="$OUT_DIR/jgroups-tcp.xml"
    cat > "$CLU_JG" <<'JGROUPS_XML'
<config xmlns="urn:org:jgroups"
        xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
        xsi:schemaLocation="urn:org:jgroups http://www.jgroups.org/schema/jgroups.xsd">
    <TCP bind_addr="match-interface:eth0,site_local,loopback"
         bind_port="7800"
         thread_pool.min_threads="0"
         thread_pool.max_threads="20"
         thread_pool.keep_alive_time="30000" />
    <org.jgroups.protocols.TCPPING
         initial_hosts="${JGROUPS_TCPPING_INITIAL_HOSTS}"
         port_range="1" />
    <MERGE3 max_interval="30000" min_interval="10000" />
    <FD_ALL3 timeout="40000" interval="5000" />
    <VERIFY_SUSPECT2 timeout="1500" />
    <pbcast.NAKACK2 use_mcast_xmit="false" />
    <UNICAST3 />
    <pbcast.STABLE desired_avg_gossip="50000" max_bytes="4M" />
    <pbcast.GMS print_local_addr="true" join_timeout="5000" />
    <UFC max_credits="2M" min_threshold="0.4" />
    <MFC max_credits="2M" min_threshold="0.4" />
    <FRAG2 frag_size="60K" />
</config>
JGROUPS_XML
    chmod 0644 "$CLU_JG"

    # Start a clustered-image MockServer. backend=memory|infinispan; when clustered,
    # pass the JGroups mount + initial_hosts + cluster name. The GraalJS + file-body
    # arms are OFF for the clustered A/B (the clustered image bundles no GraalJS, and
    # a JS arm would 500 and abort regression.js in setup()); the core arms
    # (match/forward/template/template_mustache/large/large_1mb/large_10mb) run
    # UNCHANGED — only env differs, the sanctioned parameterisation. start_clu also
    # carries the fixed harness maxEventLogSizeInBytes body-byte OOM guard (below), because
    # the MB arms run here too, on a 0.9 GiB clustered
    # heap. The budget is identical on control and cluster, so it evicts symmetrically
    # and cannot bias the within-run clustered/control ratio this profile measures.
    # The JGroups discovery string, and the guard that validates WHAT ACTUALLY GOES TO DNS.
    #
    # It must use the short network ALIASES, not the container names. The names are
    # "mockserver-perf-clu-{a,b}-${RUN_ID}", and in CI RUN_ID carries a 36-char
    # BUILDKITE_BUILD_ID plus a PID, putting them over the 63-character DNS label cap (RFC 1035).
    # Docker's embedded DNS then cannot resolve them, TCPPING finds no peers, and each node forms
    # a cluster of ONE - which reads downstream as a clustered A/B comparing two independent
    # servers. (In LOCAL mode RUN_ID is "local-$$", short enough to resolve, so this reproduces
    # only in CI - which is why it survived so long.) Reproduced both ways against the real
    # clustered image: aliases give memberCount 2, the CI-length names give 1.
    #
    # The guard parses the ASSEMBLED string rather than checking the alias constants. Checking
    # the constants would be checking the easy property: they are 5 characters and can never
    # fail, while the regression this exists to catch is someone reverting this builder to
    # ${CLU_A}[7800] - which the guard must see and reject.
    clu_discovery_hosts() {
      local hosts="${CLU_A_ALIAS}[7800],${CLU_B_ALIAS}[7800]" host
      local IFS=','
      for host in $hosts; do
        host="${host%%\[*}"
        if [ "${#host}" -gt 63 ]; then
          echo "ERROR: JGroups discovery host '$host' is ${#host} characters. A DNS label caps at" >&2
          echo "       63 (RFC 1035), so Docker's embedded DNS cannot resolve it: TCPPING would" >&2
          echo "       find no peers and each node would form a cluster of one, which the" >&2
          echo "       clustered_metrics_present gate then reds an entire run later." >&2
          echo "       Use the short --network-alias names, not the container names." >&2
          return 1
        fi
      done
      printf '%s' "$hosts"
    }

    start_clu() { # name  alias  backend(memory|infinispan)  cluster_name_or_empty  cpuset_or_empty
      local name="$1" alias="$2" backend="$3" cname="${4:-}"
      local extra=()
      clu_cpu_flags "${5:-}"
      if [ "$backend" = "infinispan" ]; then
        extra=(
          -e MOCKSERVER_CLUSTER_ENABLED=true
          -e "MOCKSERVER_CLUSTER_NAME=$cname"
          -e MOCKSERVER_CLUSTER_TRANSPORT_CONFIG=/config/jgroups-tcp.xml
          # Discovery uses the short NETWORK ALIASES, not the container names. The names are
          # "mockserver-perf-clu-a-${RUN_ID}" = 65 characters, and a DNS label is capped at 63
          # (RFC 1035), so Docker's embedded DNS cannot resolve them: TCPPING found no peers and
          # each node formed a cluster of ONE. That is exactly the "two independent servers"
          # false green the clustered_metrics_present gate exists to catch, and it caught it.
          # Reproduced both ways locally - aliases give memberCount 2, the 65-char names give 1.
          -e "JGROUPS_TCPPING_INITIAL_HOSTS=$(clu_discovery_hosts)"
          -v "$CLU_JG:/config/jgroups-tcp.xml:ro"
        )
      fi
      # ${extra[@]+"${extra[@]}"} — safe expansion of a possibly-empty array under
      # `set -u` (the memory-backend control passes no extra flags), matching the
      # start_mockserver java_opts_arg idiom above.
      docker run -d --rm --name "$name" --network "$NETWORK" --network-alias "$alias" \
        "${CLU_CPU_FLAGS[@]}" --memory "$CLU_MEM" -p 127.0.0.1::1080 \
        -e MOCKSERVER_LOG_LEVEL=WARN -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
        -e MOCKSERVER_METRICS_ENABLED=true \
        -e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES="$HARNESS_FIXED_EVENT_LOG_BYTES" \
        -e "MOCKSERVER_STATE_BACKEND=$backend" \
        ${extra[@]+"${extra[@]}"} \
        "$CLU_IMAGE" -serverPort 1080 >/dev/null 2>&1
    }

    clu_hostport() { docker port "$1" 1080/tcp 2>/dev/null | head -1; }
    clu_members()  { curl -s --max-time 4 "http://$1/mockserver/cluster" 2>/dev/null | jq -r '.memberCount // 0' 2>/dev/null || echo 0; }
    clu_clustered(){ curl -s --max-time 4 "http://$1/mockserver/cluster" 2>/dev/null | jq -r '.clustered // false' 2>/dev/null || echo false; }
    # Run regression.js UNCHANGED against one clustered target. Distinct PROTO so its
    # behaviour keys (<op>_<proto>) do not collide with the main run's.
    run_clu_regression() { # proto  base_url  out  warmup
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
        -e "BASE_URL=$2" -e "PROTO=$1" -e "INSECURE_SKIP_TLS_VERIFY=false" \
        -e "K6_RESULT_PATH=/out/$3" \
        -e "K6_REG_JS_TEMPLATE=false" -e "K6_REG_FILE_BODY_PATH=" \
        -e "K6_REG_WARMUP=${4:-$CLU_WARMUP}" -e "K6_REG_DURATION=$CLU_DURATION" \
        ${K6_REG_RATE:+-e K6_REG_RATE="$K6_REG_RATE"} \
        ${K6_CLU_REG_RATE:+-e K6_REG_RATE="$K6_CLU_REG_RATE"} \
        "$K6_IMAGE" run /k6/regression.js >/dev/null 2>&1
    }
    # Run the per-request-CROSSING arm (clustered_crossing.js). regression.js above
    # measures MEMBERSHIP overhead (its unlimited-Times matches never leave the node);
    # this drives a BOUNDED-Times match, whose shared-Times compareAndSet is a local
    # ConcurrentHashMap swap on the memory control but a SYNCHRONOUS REPL_SYNC round
    # trip on the cluster — so its clustered/control ratio is the real per-request
    # NETWORK cost (RequestMatchers shared-Times CAS -> InfinispanKeyValueStore
    # .compareAndSet -> cache.replace). Emits the same behaviours shape (op `crossing`).
    run_clu_crossing() { # proto  base_url  out  warmup
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
        -e "BASE_URL=$2" -e "PROTO=$1" -e "INSECURE_SKIP_TLS_VERIFY=false" \
        -e "K6_CLU_CROSS_RESULT_PATH=/out/$3" \
        -e "K6_CLU_CROSS_WARMUP=${4:-$CLU_WARMUP}" -e "K6_CLU_CROSS_DURATION=$CLU_DURATION" \
        ${K6_CLU_CROSS_RATE:+-e K6_CLU_CROSS_RATE="$K6_CLU_CROSS_RATE"} \
        "$K6_IMAGE" run /k6/clustered_crossing.js >/dev/null 2>&1
    }

    # The streaming SUT is done but unpinned (--cpus), so it could land on the nodes' cpus.
    docker rm -f "$STREAM_SUT" >/dev/null 2>&1 || true

    # --- CONTROL arm: single node, in-memory backend --------------------------
    # It stays up through the cluster windows; its cpuset is disjoint from the cluster nodes'.
    echo "--- clustered A/B: starting CONTROL (stateBackend=memory)"
    if start_clu "$CLU_CTRL" clu-ctrl memory "" "$CLU_CTRL_CPUS"; then wait_ready "$CLU_CTRL" || true; fi
    CTRL_HP="$(clu_hostport "$CLU_CTRL")"
    CTRL_MEMBERS="$(clu_members "$CTRL_HP")"
    CTRL_CLUSTERED="$(clu_clustered "$CTRL_HP")"
    echo "--- clustered A/B: control cluster view members=$CTRL_MEMBERS clustered=$CTRL_CLUSTERED (expect 1 / false)"

    # --- CANDIDATE arm: two-node Infinispan/JGroups cluster -------------------
    # Positive control (degrade-and-confirm-red): PERF_CLU_BREAK=cluster starts node
    # B in a DIFFERENT cluster so the view never reaches 2 — the genuineness gate
    # below must then RED. Proves the check can go red (not a silent two-of-one).
    CLU_B_NAME="$CLU_NAME"
    if [ "${PERF_CLU_BREAK:-}" = "cluster" ]; then
      CLU_B_NAME="${CLU_NAME}-BROKEN"
      echo "--- clustered A/B: PERF_CLU_BREAK=cluster — node B joins '$CLU_B_NAME' (SELF-TEST: view must stay 1)" >&2
    fi
    echo "--- clustered A/B: starting CANDIDATE 2-node cluster (stateBackend=infinispan, JGroups TCPPING)"
    start_clu "$CLU_A" "$CLU_A_ALIAS" infinispan "$CLU_NAME" "$CLU_A_CPUS" || echo "WARNING: cluster node A did not start" >&2
    start_clu "$CLU_B" "$CLU_B_ALIAS" infinispan "$CLU_B_NAME" "$CLU_B_CPUS" || echo "WARNING: cluster node B did not start" >&2
    wait_ready "$CLU_A" || true
    wait_ready "$CLU_B" || true
    A_HP="$(clu_hostport "$CLU_A")"; B_HP="$(clu_hostport "$CLU_B")"
    # Wait (bounded) for the JGroups view to converge to 2 on node A.
    A_MEMBERS=1
    for _ in $(seq 1 30); do
      A_MEMBERS="$(clu_members "$A_HP")"
      [ "${A_MEMBERS:-0}" -ge 2 ] && break
      sleep 2
    done
    B_MEMBERS="$(clu_members "$B_HP")"
    A_CLUSTERED="$(clu_clustered "$A_HP")"
    echo "--- clustered A/B: candidate view members A=$A_MEMBERS B=$B_MEMBERS clustered(A)=$A_CLUSTERED (expect 2 / 2 / true)"
    # DIAGNOSTIC on formation failure: if the view did not converge to 2, dump each
    # node's cluster snapshot + the tail of its JGroups startup logs so a real
    # TCPPING/discovery failure (once the image is wired live) is debuggable rather
    # than a bare "members A=1". The genuineness gate below still REDs — this only
    # explains WHY. (Expected + intentional under the PERF_CLU_BREAK self-test.)
    if [ "${A_MEMBERS:-0}" -lt 2 ]; then
      echo "--- clustered A/B: DIAGNOSTIC — JGroups view did not reach 2; dumping node state" >&2
      echo "    node A /mockserver/cluster: $(curl -s --max-time 4 "http://$A_HP/mockserver/cluster" 2>/dev/null)" >&2
      echo "    node B /mockserver/cluster: $(curl -s --max-time 4 "http://$B_HP/mockserver/cluster" 2>/dev/null)" >&2
      docker logs "$CLU_A" 2>&1 | grep -iE "jgroups|ISPN|view|GMS|TCPPING|cluster" | tail -8 | sed 's/^/    A| /' >&2 || true
      docker logs "$CLU_B" 2>&1 | grep -iE "jgroups|ISPN|view|GMS|TCPPING|cluster" | tail -8 | sed 's/^/    B| /' >&2 || true
    fi

    # PROVE STATE CROSSES THE NETWORK: seed a UNIQUE expectation ONLY on node A, then
    # match it on node B (which never received the seed directly). This is the
    # anti-"two independent in-memory servers" gate — the textbook false green.
    CROSS_TOKEN="cross-$RUN_ID"
    CROSS_CODE=0; NEG_CODE=0
    if [ -n "$A_HP" ] && [ -n "$B_HP" ]; then
      curl -s --max-time 5 -X PUT "http://$A_HP/mockserver/expectation" -H 'Content-Type: application/json' \
        -d "[{\"httpRequest\":{\"path\":\"/$CROSS_TOKEN\"},\"httpResponse\":{\"statusCode\":222,\"body\":\"$CROSS_TOKEN\"},\"times\":{\"unlimited\":true}}]" \
        -o /dev/null 2>/dev/null || true
      # Give REPL_SYNC a beat, then probe node B for the A-only expectation. Body is
      # written under OUT_DIR (per the repo tmp-file convention), not /tmp.
      CROSS_BODY="$OUT_DIR/clu-cross-probe.$$"
      for _ in $(seq 1 10); do
        CROSS_CODE="$(curl -s --max-time 5 -o "$CROSS_BODY" -w '%{http_code}' "http://$B_HP/$CROSS_TOKEN" 2>/dev/null || echo 0)"
        [ "$CROSS_CODE" = 222 ] && grep -q "$CROSS_TOKEN" "$CROSS_BODY" 2>/dev/null && break
        sleep 1
      done
      # Negative control: a path seeded NOWHERE must NOT match on B (proves the 222
      # above is the replicated expectation, not a catch-all).
      NEG_CODE="$(curl -s --max-time 5 -o /dev/null -w '%{http_code}' "http://$B_HP/never-seeded-$RUN_ID" 2>/dev/null || echo 0)"
      rm -f "$CROSS_BODY" 2>/dev/null || true
    fi
    STATE_CROSSED=false
    [ "$CROSS_CODE" = 222 ] && STATE_CROSSED=true
    echo "--- clustered A/B: state-crossed(A->B)=$STATE_CROSSED (probe HTTP $CROSS_CODE, negative-control HTTP $NEG_CODE)"

    # --- the measured windows: control and cluster alternate, pair by pair ------
    CLU_PLACEMENT="$OUT_DIR/clu-placement.ndjson"; : > "$CLU_PLACEMENT"
    log_container_cpuset clu_control "$CLU_CTRL" "$CLU_PLACEMENT"
    log_container_cpuset clu_node_a "$CLU_A" "$CLU_PLACEMENT"
    log_container_cpuset clu_node_b "$CLU_B" "$CLU_PLACEMENT"
    clu_locate_cpu_stats "$CLU_CTRL" "$CLU_A" "$CLU_B"
    clu_run_pairs "$CLU_PAIRS"
    # The up-front proof holds only at its instant: re-read every view, so a member lost mid-phase
    # unmeasures the run instead of filing single-node windows as cluster ones.
    A_MEMBERS_AFTER="$(clu_members "$A_HP")"; B_MEMBERS_AFTER="$(clu_members "$B_HP")"
    CTRL_MEMBERS_AFTER="$(clu_members "$CTRL_HP")"
    echo "--- clustered A/B: view after the windows A=$A_MEMBERS_AFTER B=$B_MEMBERS_AFTER control=$CTRL_MEMBERS_AFTER (expect 2 / 2 / 1)"
    docker rm -f "$CLU_CTRL" "$CLU_A" "$CLU_B" >/dev/null 2>&1 || true
    CLU_ELAPSED_S=$(( $(date -u +%s) - CLU_T_START ))
    echo "--- clustered A/B windows done; added wall-clock ${CLU_ELAPSED_S}s"

    # .clustered_state = the meta below (image, cluster proof, placement) + the per-arm ratios,
    # measured verdict and throttling from clu_assemble_json; the per-window medians land in
    # .behaviours as <op>_clustered_control / <op>_clustered, the keys they always had.
    # NB: no apostrophes in this jq program; it is a single-quoted bash string.
    CLU_META="$(jq -nc \
      --argjson a_members "${A_MEMBERS:-0}" --argjson b_members "${B_MEMBERS:-0}" \
      --argjson ctrl_members "${CTRL_MEMBERS:-0}" \
      --argjson a_after "${A_MEMBERS_AFTER:-0}" --argjson b_after "${B_MEMBERS_AFTER:-0}" \
      --argjson ctrl_after "${CTRL_MEMBERS_AFTER:-0}" \
      --arg a_clustered "${A_CLUSTERED:-false}" --arg ctrl_clustered "${CTRL_CLUSTERED:-false}" \
      --arg state_crossed "$STATE_CROSSED" \
      --argjson cross_code "${CROSS_CODE:-0}" --argjson neg_code "${NEG_CODE:-0}" \
      --arg image "$CLU_IMAGE" --arg image_revision "$CLU_IMAGE_REVISION" \
      --arg mode "$CLU_PIN_MODE" --arg reason "$CLU_PIN_REASON" --arg quota "$CLU_CPUS" \
      --arg ctrl_cpus "$CLU_CTRL_CPUS" --arg a_cpus "$CLU_A_CPUS" --arg b_cpus "$CLU_B_CPUS" \
      --slurpfile observed "$CLU_PLACEMENT" \
      --arg warmup "$CLU_WARMUP" --arg pair_warmup "$CLU_PAIR_WARMUP" --arg duration "$CLU_DURATION" \
      --arg orders "$CLU_PAIR_ORDERS" --argjson elapsed "$CLU_ELAPSED_S" '
      def nz: if . == "" then null else . end;
      {
        image: $image,
        # The clustered image commit, recorded so a stored run can be audited: the gate above refuses
        # to measure unless it equals the SUT image revision. Empty only for a deliberately-unlabelled
        # local image (PERF_CLUSTERED_REQUIRE_MATCHING_REVISION=false).
        image_revision: $image_revision,
        cluster: {
          member_count_node_a: $a_members,
          member_count_node_b: $b_members,
          control_member_count: $ctrl_members,
          member_count_node_a_after: $a_after,
          member_count_node_b_after: $b_after,
          control_member_count_after: $ctrl_after,
          candidate_clustered: ($a_clustered == "true"),
          control_clustered: ($ctrl_clustered == "true"),
          state_crossed_network: ($state_crossed == "true"),
          cross_node_probe_http_code: $cross_code,
          negative_control_http_code: $neg_code
        },
        cpu_pinning: {
          mode: $mode, reason: $reason,
          quota_cpus: (if $mode == "cpu-quota" then $quota else null end),
          control: ($ctrl_cpus | nz), node_a: ($a_cpus | nz), node_b: ($b_cpus | nz),
          observed: $observed
        },
        windows: {warmup_first_pair: $warmup, warmup_later_pairs: $pair_warmup, duration: $duration,
                  pair_orders: ($orders | split(" ") | map(select(. != ""))), elapsed_s: $elapsed}
      }')" || CLU_META='{}'

    CLU_ASSEMBLED="$(clu_assemble_json "$OUT_DIR" "$CLU_PAIRS" "$CLU_META")" || CLU_ASSEMBLED=""
    CLUSTERED_JSON="$(jq -ce '.state' <<<"$CLU_ASSEMBLED" 2>/dev/null)" \
      || CLUSTERED_JSON='{"attempted":true,"measured":false,"problems":["result assembly failed"],"arms":{}}'
    CLU_CTRL_BEHAVIOURS="$(jq -ce '.control_behaviours' <<<"$CLU_ASSEMBLED" 2>/dev/null)" || CLU_CTRL_BEHAVIOURS='{}'
    CLU_CAND_BEHAVIOURS="$(jq -ce '.cluster_behaviours' <<<"$CLU_ASSEMBLED" 2>/dev/null)" || CLU_CAND_BEHAVIOURS='{}'
    jq -e . >/dev/null 2>&1 <<<"$CLUSTERED_JSON" || CLUSTERED_JSON='{}'
    echo "--- clustered A/B result (crossing = per-request NETWORK cost; the rest = membership-only):"
    jq -c '{measured, problems, method, cpu: .cpu_pinning.mode, throttling: (.throttling | {available, material}), cluster,
            crossing_arm: (.arms | to_entries | map(select(.value.crosses_network)) | from_entries | with_entries(.value |= {p50_ratio, p95_ratio, throughput_ratio})),
            membership_arms: (.arms | to_entries | map(select(.value.crosses_network|not)) | from_entries | with_entries(.value |= {p50_ratio, p95_ratio, throughput_ratio}))}' \
      <<<"$CLUSTERED_JSON" 2>/dev/null || echo "$CLUSTERED_JSON"
fi

# --- in-order HTTP/1.1 pipelining cost (within-run A/B, notify-only) ----------
# http1PipeliningInOrder (default true) vs false on two fresh SUTs from this run's image, so the
# on / off ratio is attributed to the same commit as the rest of the run. Like the INFO arm it is
# kept out of VALIDITY_CHECKS: a failed or non-genuine A/B (measured:false) is annotated by compare
# and never costs the run its baseline place. Remove with the flag (lib/perf-pipelining-ab.sh).
abort_if_sut_died
PIPELINING_JSON='{}'
PIPELINING_ATTEMPTED=false
PIPELINING_SKIP_REASON="disabled"
if [ "${PERF_PIPELINING:-true}" = "true" ]; then
  if command -v python3 >/dev/null 2>&1; then
    PIPELINING_ATTEMPTED=true; PIPELINING_SKIP_REASON=""
    PL_T_START="$(date -u +%s)"
    run_pipelining_ab
    echo "--- pipelining A/B done; added wall-clock $(( $(date -u +%s) - PL_T_START ))s"
  else
    PIPELINING_SKIP_REASON="no_python3"
    echo "WARNING: python3 not on the agent — pipelining A/B skipped (notify-only, no pipelining_inorder block)" >&2
  fi
fi

# --- FILE response body cost (within-run A/B, notify-only) ----------------------
# A FILE body vs the identical inline body on one fresh SUT (lib/perf-file-body-ab.sh). Kept out of
# VALIDITY_CHECKS like the pipelining A/B; compare annotates a run that did not measure.
abort_if_sut_died
fb_ab_phase

# --- data-plane hooks cost (opt-in within-run A/B, notify-only) -----------------
# Identity request filter and response transformer against no extension, on three fresh SUTs
# (lib/perf-dataplane-ab.sh). PERF_DATAPLANE_AB=true turns it on; kept out of VALIDITY_CHECKS.
abort_if_sut_died
dp_ab_phase

# --- derive resource slope ratios from the sample log -------------------------
# start = first non-empty sample, end = last, peak = max. The CPU ratio is not end/start: see
# growth_cpu_window below.
# PROVOCATION (observed false): with the metrics port unreachable the sampler
# writes only its header row, wc -l is 1, and this check evaluates false — which
# is exactly the empty-sample-log case the plan wants promoted from a warning to a
# baseline refusal (a run with no resource trajectory cannot assess growth).
if arm_only; then
  : # no growth phase, so no resource trajectory to require
elif [ "$(wc -l < "$SAMPLE_LOG" 2>/dev/null || echo 0)" -le 1 ]; then
  echo "WARNING: resource sample log is empty — growth resource metrics will be 0/null for this run" >&2
  add_check "resource_samples_present" false "resource sample log empty — CPU/heap growth metrics are 0/null (cannot assess growth)"
else
  add_check "resource_samples_present" true "$(( $(wc -l < "$SAMPLE_LOG") - 1 )) resource samples captured during growth"
fi
# HEAP_MIN_FIRST / HEAP_MIN_LAST: minimum heap over the first / last LIVESET_WINDOW_S
# seconds of the sample log — the saw-tooth FLOOR that approximates the live set.
LIVESET_WINDOW_S="${PERF_LIVESET_WINDOW_S:-60}"
read -r CPU_START CPU_END CPU_PEAK HEAP_START HEAP_END HEAP_PEAK GC_DELTA THREADS_PEAK HEAP_MIN_FIRST HEAP_MIN_LAST <<EOF
$(awk -F',' -v W="$LIVESET_WINDOW_S" 'NR>1 && $2!="" {
    if (cs=="") {cs=$2} ce=$2; if ($2+0>cp) cp=$2;
  }
  NR>1 && $3!="" {
    if (hs=="") {hs=$3} he=$3; if ($3+0>hp) hp=$3;
    n++; T[n]=$1+0; H[n]=$3+0;
  }
  NR>1 && $4!="" { if (gcs=="") gcs=$4; gce=$4 }
  NR>1 && $5!="" { if ($5+0>tp) tp=$5 }
  END {
    hminf=""; hminl="";
    if (n>0) {
      ft=T[1]; lt=T[n];
      for (i=1;i<=n;i++) {
        if (T[i] <= ft + W) { if (hminf=="" || H[i] < hminf) hminf=H[i] }
        if (T[i] >= lt - W) { if (hminl=="" || H[i] < hminl) hminl=H[i] }
      }
    }
    printf "%s %s %s %s %s %s %s %s %s %s", cs+0, ce+0, cp+0, hs+0, he+0, hp+0, (gce-gcs)+0, tp+0, hminf+0, hminl+0
  }' "$SAMPLE_LOG")
EOF
ratio() { awk -v a="$1" -v b="$2" 'BEGIN{ if (b+0>0) printf "%.4f", a/b; else print "null" }'; }
# Live-set heap ratio = min heap over the LAST window / min over the FIRST window.
# The post-GC saw-tooth floor tracks the live set far better than an instantaneous
# end/start point sample (which lands at a random point on the GC saw-tooth), with
# no forced GC and much less noise. REPLACES the old instantaneous heap ratio.
HEAP_RATIO="$(ratio "$HEAP_MIN_LAST" "$HEAP_MIN_FIRST")"

# --- event-log scaling: resolved bounds vs observed peak, and which bound bound ------------------
# Both event-log bounds derive from the heap ceiling but scale DIFFERENTLY: maxLogEntries is
# min(heapAvailableKB/8, 250000) and CAPS at 250000 (reached by ~1.9 GiB of heap), while maxEventLogSizeInBytes
# is (heapAvailableKB/divisor)*1024 (the divisor is els_default_divisor's, by log level) and scales with the
# heap without limit. For the perf workload's small bodies (~1.3 KB per entry) the BYTE budget binds
# first up to roughly a 6 GiB heap at INFO and WARN, and the COUNT cap above that — this
# block MEASURES which one binds rather than assuming it. Resolved values come from the
# server's own gauges (max_retained_entries / max_retained_bytes in diag-samples.csv), the EFFECTIVE
# figures the JVM applied, not the requested ones. On an older SUT image without those gauges the
# columns are blank and samples_with_log_data is 0. Reads are safe here: the load phases (regression /
# sweep / growth) are complete, so the retained peak is settled even though the sampler still runs.
# An unset level means the server default, INFO.
ELS_DIVISOR="$(els_default_divisor "$LOG_LEVEL_VAL")"
# Default heap-derived byte budget the server WOULD pick with no override: (heapAvailableKB/divisor)*1024,
# where heapAvailableKB = heapBytes/1024 - 20480 (ConfigurationProperties.computeHeapAvailableInKB).
# On a large-heap run the resolved budget must be at least this, i.e. the log budget scaled with the
# heap at least as fast as the shipped default would have.
ELS_EXPECTED_DEFAULT_BYTES="$(awk -v h="$HEAP_MAX_BYTES" -v d="$ELS_DIVISOR" 'BEGIN{ hk=int(h/1024)-20480; if (hk<0) hk=0; printf "%.0f", int(hk/d)*1024 }')"
# diag-samples.csv columns: 1 ts, 12 dropped, 15 in-flight, 17/18 retained entries/bytes, 19/20 their
# bounds, 21 evicted, 33 scrape_ts. Utilisation is per row against that row's own bounds, because the
# growth window [t0,t1] runs at GROWTH_EVENT_LOG_BYTES; the resolved bounds come from rows outside it
# (all rows if none). A row is placed by when its metrics were scraped (scrape_ts, else ts).
# Prints the whole-run figures (max_entries max_bytes peak_entries peak_bytes peak_in_flight evicted rows
# dropped count_util bytes_util), then the growth window's (max_entries max_bytes peak_entries
# peak_bytes count_util bytes_util rows).
event_log_window_stats() { # samples_csv growth_t0 growth_t1
  awk -F',' -v t0="$2" -v t1="$3" '
    NR == 1 || $20 == "" { next }
    { t = ($33 != "") ? $33 : $1; w = (t0 != "" && t1 != "" && t+0 >= t0+0 && t+0 <= t1+0); rows++
      cu = ($20+0 > 0) ? $17/$20 : 0; bu = ($19+0 > 0) ? $18/$19 : 0
      if ($17+0 > pe) pe = $17; if ($18+0 > pb) pb = $18; if ($15+0 > pif) pif = $15
      if ($21+0 > ev) ev = $21; if ($12+0 > dr) dr = $12; if (cu > mcu) mcu = cu; if (bu > mbu) mbu = bu
      if ($20+0 > ame) ame = $20; if ($19+0 > amb) amb = $19
      if (w) { gr++; if ($20+0 > gme) gme = $20; if ($19+0 > gmb) gmb = $19; if ($17+0 > gpe) gpe = $17
               if ($18+0 > gpb) gpb = $18; if (cu > gcu) gcu = cu; if (bu > gbu) gbu = bu }
      else   { orow++; if ($20+0 > ome) ome = $20; if ($19+0 > omb) omb = $19 } }
    END { if (!orow) { ome = ame; omb = amb }
          printf "%.0f %.0f %.0f %.0f %.0f %.0f %d %.0f %.4f %.4f %.0f %.0f %.0f %.0f %.4f %.4f %d\n",
            ome+0, omb+0, pe+0, pb+0, pif+0, ev+0, rows+0, dr+0, mcu+0, mbu+0,
            gme+0, gmb+0, gpe+0, gpb+0, gcu+0, gbu+0, gr+0 }' "$1" 2>/dev/null \
    || echo "0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0"
}
read -r ELS_MAX_ENTRIES ELS_MAX_BYTES ELS_PEAK_ENTRIES ELS_PEAK_BYTES ELS_PEAK_INFLIGHT ELS_EVICTED ELS_LOG_ROWS ELS_DROPPED \
  ELS_COUNT_UTIL ELS_BYTES_UTIL GROWTH_ELS_MAX_ENTRIES GROWTH_ELS_MAX_BYTES GROWTH_ELS_PEAK_ENTRIES GROWTH_ELS_PEAK_BYTES \
  GROWTH_ELS_COUNT_UTIL GROWTH_ELS_BYTES_UTIL GROWTH_ELS_ROWS \
  <<<"$(event_log_window_stats "$DIAG_SAMPLE_LOG" "${GROWTH_ELS_T0:-}" "${GROWTH_ELS_T1:-}")"
# growth.cpu_ratio = median CPU while the log is full (the last CPU_END_WINDOW_S of growth) / median
# CPU while it is CPU_START_OCC_LO..CPU_START_OCC_HI full. Windows are placed by event-log occupancy
# from diag-samples.csv, not by time: the log fills about 75 s into the load, so a fixed-time start
# window drifts with the fill rate and dilutes a cost that grows with occupancy. Start rows must
# precede the first full row (the teardown reset empties the log again).
CPU_START_OCC_LO="${PERF_CPU_START_OCC_LO:-0.10}"
CPU_START_OCC_HI="${PERF_CPU_START_OCC_HI:-0.30}"
CPU_END_WINDOW_S="${PERF_CPU_END_WINDOW_S:-40}"
if ! [[ "$CPU_START_OCC_LO" =~ ^0?\.[0-9]+$ && "$CPU_START_OCC_HI" =~ ^0?\.[0-9]+$ && "$CPU_END_WINDOW_S" =~ ^[1-9][0-9]*$ ]] \
   || awk -v a="$CPU_START_OCC_LO" -v b="$CPU_START_OCC_HI" 'BEGIN { exit !(a+0 >= b+0) }'; then
  echo "WARNING: PERF_CPU_START_OCC_LO/HI='$CPU_START_OCC_LO'/'$CPU_START_OCC_HI' or PERF_CPU_END_WINDOW_S='$CPU_END_WINDOW_S' unusable; using 0.10 / 0.30 / 40" >&2
  CPU_START_OCC_LO=0.10; CPU_START_OCC_HI=0.30; CPU_END_WINDOW_S=40
fi
growth_cpu_window() { # samples_csv growth_t0 growth_t1 occ_lo occ_hi end_window_s -> "start_median end_median start_n end_n"
  awk -F',' -v t0="$2" -v t1="$3" -v lo="$4" -v hi="$5" -v W="$6" '
    function med(a, k,   i, j, t) {
      for (i = 2; i <= k; i++) { t = a[i]; for (j = i - 1; j >= 1 && a[j] > t; j--) a[j+1] = a[j]; a[j+1] = t }
      return (k % 2) ? a[(k+1)/2] : (a[k/2] + a[k/2+1]) / 2
    }
    NR == 1 || $5 == "" || ($20 == "" && $19 == "") { next }
    { t = ($33 != "") ? $33 : $1
      if (t0 == "" || t1 == "" || t+0 < t0+0 || t+0 > t1+0) next
      cu = ($20+0 > 0) ? $17/$20 : 0; bu = ($19+0 > 0) ? $18/$19 : 0
      n++; T[n] = t+0; C[n] = $5+0; O[n] = (cu > bu) ? cu : bu }
    END {
      first = ""; last = ""
      for (i = 1; i <= n; i++) if (O[i] >= 0.99) { if (first == "") first = T[i]; last = T[i] }
      ks = 0; ke = 0
      if (first != "") for (i = 1; i <= n; i++) {
        if (T[i] < first && O[i] >= lo && O[i] <= hi) S[++ks] = C[i]
        if (O[i] >= 0.99 && T[i] >= last - W) E[++ke] = C[i]
      }
      printf "%s %s %d %d\n", (ks ? sprintf("%.4f", med(S, ks)) : "null"), (ke ? sprintf("%.4f", med(E, ke)) : "null"), ks, ke
    }' "$1" 2>/dev/null || echo "null null 0 0"
}
read -r CPU_START_MEDIAN CPU_END_MEDIAN CPU_START_SAMPLES CPU_END_SAMPLES <<<"$(growth_cpu_window "$DIAG_SAMPLE_LOG" \
  "${GROWTH_ELS_T0:-}" "${GROWTH_ELS_T1:-}" "$CPU_START_OCC_LO" "$CPU_START_OCC_HI" "$CPU_END_WINDOW_S")"
CPU_RATIO=null
[ "${CPU_START_MEDIAN:-null}" != null ] && [ "${CPU_END_MEDIAN:-null}" != null ] && CPU_RATIO="$(ratio "$CPU_END_MEDIAN" "$CPU_START_MEDIAN")"
read -r ELS_MEAN_ENTRY ELS_BINDING ELS_BOUND_REACHED <<<"$(awk -v cu="$ELS_COUNT_UTIL" -v bu="$ELS_BYTES_UTIL" \
    -v pe="$ELS_PEAK_ENTRIES" -v pb="$ELS_PEAK_BYTES" -v ev="$ELS_EVICTED" -v thr="$PERF_EVENT_LOG_APPROACH_RATIO" 'BEGIN{
  mean = (pe>0)? pb/pe : 0;
  reached = (ev>0 || cu>=thr || bu>=thr) ? "true" : "false";
  binding = (reached=="false") ? "neither" : ((cu>=bu)? "count" : "bytes");
  printf "%.0f %s %s", mean, binding, reached }')"
GROWTH_PHASE_JSON="$(growth_phase_json)"
[ "$PERF_LARGE_HEAP_PROFILE" = "true" ] && ELS_LARGE_HEAP_BOOL=true || ELS_LARGE_HEAP_BOOL=false
EVENT_LOG_SCALING_JSON="$(jq -n \
  --argjson heap_max_bytes "${HEAP_MAX_BYTES:-0}" \
  --arg log_level "$LOG_LEVEL_VAL" \
  --argjson heap_divisor "$ELS_DIVISOR" \
  --argjson resolved_max_log_entries "$ELS_MAX_ENTRIES" \
  --argjson resolved_max_event_log_bytes "$ELS_MAX_BYTES" \
  --argjson requested_max_event_log_bytes "${MAX_EVENT_LOG_VAL:-null}" \
  --argjson expected_default_budget_bytes "$ELS_EXPECTED_DEFAULT_BYTES" \
  --argjson peak_retained_entries "$ELS_PEAK_ENTRIES" \
  --argjson peak_retained_bytes "$ELS_PEAK_BYTES" \
  --argjson peak_in_flight_bytes "$ELS_PEAK_INFLIGHT" \
  --argjson evicted_log_entries "$ELS_EVICTED" \
  --argjson count_utilisation "$ELS_COUNT_UTIL" \
  --argjson bytes_utilisation "$ELS_BYTES_UTIL" \
  --argjson mean_entry_bytes "$ELS_MEAN_ENTRY" \
  --arg binding_constraint "$ELS_BINDING" \
  --argjson bound_reached "$ELS_BOUND_REACHED" \
  --argjson approach_ratio "$PERF_EVENT_LOG_APPROACH_RATIO" \
  --argjson samples_with_log_data "$ELS_LOG_ROWS" \
  --argjson large_heap_profile "$ELS_LARGE_HEAP_BOOL" \
  --argjson dropped_log_events "${ELS_DROPPED:-0}" \
  --argjson growth_phase "$GROWTH_PHASE_JSON" \
  '{
    # resolved_* are the EFFECTIVE bounds read from the server gauges; requested_max_event_log_bytes is
    # what the container was handed (MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES). expected_default_budget_bytes
    # is the heap-derived budget the server would pick unbidden — the floor the resolved budget must clear
    # on a large-heap run. binding_constraint is which bound the observed peak actually reached
    # ("count" | "bytes" | "neither"); bound_reached is false when the log never filled (utilisation below
    # approach_ratio on both bounds and no evictions) — which is NOT evidence of proportionality.
    heap_max_bytes: $heap_max_bytes,
    log_level: $log_level,
    heap_divisor: $heap_divisor,
    resolved_max_log_entries: $resolved_max_log_entries,
    resolved_max_event_log_bytes: $resolved_max_event_log_bytes,
    requested_max_event_log_bytes: $requested_max_event_log_bytes,
    expected_default_budget_bytes: $expected_default_budget_bytes,
    peak_retained_entries: $peak_retained_entries,
    peak_retained_bytes: $peak_retained_bytes,
    peak_in_flight_bytes: $peak_in_flight_bytes,
    evicted_log_entries: $evicted_log_entries,
    count_utilisation: $count_utilisation,
    bytes_utilisation: $bytes_utilisation,
    mean_entry_bytes: $mean_entry_bytes,
    binding_constraint: $binding_constraint,
    bound_reached: $bound_reached,
    approach_ratio: $approach_ratio,
    samples_with_log_data: $samples_with_log_data,
    large_heap_profile: $large_heap_profile,
    # Peak of the cumulative dropped-events counter: recorded, never checked.
    dropped_log_events: $dropped_log_events,
    growth_phase: $growth_phase
  }')"
echo "--- event-log scaling: heap=${HEAP_MAX_BYTES}B maxLogEntries=${ELS_MAX_ENTRIES} maxEventLogSizeInBytes=${ELS_MAX_BYTES} (expected_default_floor=${ELS_EXPECTED_DEFAULT_BYTES}) peak_entries=${ELS_PEAK_ENTRIES}(util ${ELS_COUNT_UTIL}) peak_bytes=${ELS_PEAK_BYTES}(util ${ELS_BYTES_UTIL}) evicted=${ELS_EVICTED} dropped=${ELS_DROPPED} binding=${ELS_BINDING} bound_reached=${ELS_BOUND_REACHED} large_heap_profile=${PERF_LARGE_HEAP_PROFILE}; growth phase: $(jq -rc '"budget \(.applied_bytes // "unconfirmed") (\(.budget_method)), peak_entries \(.peak_retained_entries) of \(.resolved_max_log_entries), count_ring_filled \(.count_ring_filled)"' <<<"$GROWTH_PHASE_JSON" 2>/dev/null || echo unavailable)"
# Fail-closed assertions ONLY on an opted-in large-heap run (a normal run records the block and adds
# no check — its log may legitimately never fill). Three ways to red: gauges absent (cannot verify);
# byte budget did not scale with the heap; or neither bound was exercised (measured more headroom, not
# a bigger log). Each is a distinct add_check so compare reddens with the reason.
if [ "$PERF_LARGE_HEAP_PROFILE" = "true" ]; then
  if [ "${ELS_LOG_ROWS:-0}" -eq 0 ]; then
    add_check "event_log_scaling_measurable" false "large-heap profile requested but the SUT exposed no event-log gauges (mock_server_event_log_max_retained_entries/_bytes) in any diag sample — cannot verify proportionality; the image predates the gauges or /mockserver/metrics was unreachable for the whole load window"
  else
    add_check "event_log_scaling_measurable" true "event-log gauges present in ${ELS_LOG_ROWS} sample(s); resolved maxLogEntries=${ELS_MAX_ENTRIES}, maxEventLogSizeInBytes=${ELS_MAX_BYTES}"
    if [ "${HEAP_MAX_BYTES:-0}" -le 0 ]; then
      add_check "event_log_byte_budget_scaled_with_heap" false "heap ceiling unresolved (HEAP_MAX_BYTES=${HEAP_MAX_BYTES:-}), so the heap-derived floor cannot be computed and scaling cannot be verified — a 0 floor would pass trivially"
    elif awk -v b="$ELS_MAX_BYTES" -v e="$ELS_EXPECTED_DEFAULT_BYTES" 'BEGIN{exit !(b+0>=e+0)}'; then
      add_check "event_log_byte_budget_scaled_with_heap" true "resolved byte budget ${ELS_MAX_BYTES} >= heap-derived floor ${ELS_EXPECTED_DEFAULT_BYTES} (heap ${HEAP_MAX_BYTES}B less 20 MiB, / divisor ${ELS_DIVISOR}) — the log byte budget scaled with the heap"
    else
      add_check "event_log_byte_budget_scaled_with_heap" false "resolved byte budget ${ELS_MAX_BYTES} is BELOW the heap-derived floor ${ELS_EXPECTED_DEFAULT_BYTES} (heap ${HEAP_MAX_BYTES}B less 20 MiB, / divisor ${ELS_DIVISOR}) — on a large-heap run the log byte budget MUST scale with the heap (raise PERF_MAX_EVENT_LOG_BYTES), else the run measures more headroom rather than a bigger log"
    fi
    if [ "$ELS_BOUND_REACHED" = "true" ]; then
      add_check "event_log_bound_exercised" true "binding constraint was '${ELS_BINDING}' (count_util=${ELS_COUNT_UTIL}, bytes_util=${ELS_BYTES_UTIL}, evicted=${ELS_EVICTED}, mean_entry=${ELS_MEAN_ENTRY}B) — a bound was actually reached, so the run exercised event-log retention"
    else
      add_check "event_log_bound_exercised" false "neither event-log bound was approached (count_util=${ELS_COUNT_UTIL}, bytes_util=${ELS_BYTES_UTIL} both below approach_ratio ${PERF_EVENT_LOG_APPROACH_RATIO}, evicted=0) — the workload never filled the log, so this large-heap run is NOT evidence of proportionality (raise load/duration or lower the bounds so a bound actually binds)"
    fi
  fi
fi

abort_if_sut_died
# --- req/s per core for the SERVING path -----------------------------
# Pin ONE SUT to C cores in {1,2,4,8,16} and drive the sweep ladder against it
# from a k6 on DISJOINT cores, recording rig_valid_peak_achieved_rps, healthy_ceiling_rps
# and rps_per_core per C (lib/perf-percore.sh does the work + the C=16 feasibility
# handling + the pinning proof). DEFAULT OFF (opt-in): unlike the streaming /
# clustered arms, this does NOT share the main SUT — it spins up and tears down one
# fresh pinned SUT per core-count and runs a full ladder against each, ~4-5 extra
# SUT lifecycles, so it is scheduled deliberately (the plan's Tier-3 classification)
# rather than added to every daily regression run. Enable with PERF_SERVING_PERCORE=true.
# Every serving_percore.* metric is NOTIFY-ONLY (perf-budgets.json). Records INTENT
# (`serving_percore_attempted`) separately from success so compare can tell "attempted
# and produced no points" (RED) apart from "profile disabled" (silent), the same
# two-axis split the laptop profile uses.
SERVING_PERCORE_JSON='{}'
SERVING_PERCORE_ATTEMPTED=false
# A sub-harness's block (lib/perf-percore.sh, multi-process-sweep.sh), or one whose .error names
# why there is none: a failed producer must never read as an empty profile. Also rewrites the file, so the standalone artifact says so.
percore_block() { # out_file exit_code label
  local block
  if jq -e 'type == "object" and (.points | type) == "array"' "$1" >/dev/null 2>&1; then
    block="$(jq -c --argjson rc "$2" --arg l "$3" \
      'if $rc != 0 and (has("error") | not) then . + {error:"producer_failed", error_detail:"\($l) exited \($rc)"} else . end' "$1")"
  else
    block="$(jq -nc --argjson rc "$2" --arg l "$3" --arg size "$(wc -c 2>/dev/null < "$1" | tr -d ' ')" '
      {attempted:true, error:"producer_failed", points:[], skipped:[],
       error_detail:("\($l) exited \($rc) and wrote no JSON block (" + (if $size == "" then "no file" else $size + " bytes" end) + ")")}')"
  fi
  printf '%s\n' "$block" > "$1"
  printf '%s' "$block"
}
if [ "${PERF_SERVING_PERCORE:-false}" = "true" ]; then
  SERVING_PERCORE_ATTEMPTED=true
  echo "--- item 18 serving per-core (opt-in; PERF_SERVING_PERCORE=true)"
  # NOT an add_check on failure by itself: the presence gate lives in compare
  # (serving_percore_attempted + a non-empty points/skipped set), matching laptop.
  pc_rc=0
  MOCKSERVER_IMAGE="$MOCKSERVER_IMAGE" PERF_PERCORE_REPO_ROOT="$REPO_ROOT" \
    bash "$SCRIPT_DIR/lib/perf-percore.sh" "$OUT_DIR/serving-percore.json" || pc_rc=$?
  SERVING_PERCORE_JSON="$(percore_block "$OUT_DIR/serving-percore.json" "$pc_rc" "lib/perf-percore.sh (percore)")"
  if jq -e 'has("error")' <<<"$SERVING_PERCORE_JSON" >/dev/null; then
    echo ":x: serving per-core profile FAILED — $(jq -r '.error_detail // .error' <<<"$SERVING_PERCORE_JSON") (perf-test-compare.sh fails the build on it)" >&2
  else
    echo "--- serving_percore: points=$(jq -r '(.points|length)//0' <<<"$SERVING_PERCORE_JSON") max_cores_measured=$(jq -r '.max_cores_measured//"?"' <<<"$SERVING_PERCORE_JSON") skipped=$(jq -r '(.skipped|length)//0' <<<"$SERVING_PERCORE_JSON")"
  fi
fi

# --- throughput by hardware size (cores x container memory) ----------
# lib/perf-percore.sh in hw_matrix mode (multi-k6 client per point); opt-in (~50 min), manual
# builds only, notify-only.
# Every other container of this run is paused, and its samplers stopped, for the matrix:
# the idle main SUT and upstream sit on the cores the points use. cleanup() resumes them.
SERVING_HW_MATRIX_JSON='{}'
SERVING_HW_MATRIX_ATTEMPTED=false
RIG_PAUSED=(); RIG_STOPPED=()
RIG_PAUSE_COMPLETE=false
pause_rig() {
  local c p err listed=0
  for p in "$SAMPLER_PID" "$DIAG_SAMPLER_PID" "$LIVE_HISTO_PID"; do
    [ -n "$p" ] && kill -STOP "$p" 2>/dev/null && RIG_STOPPED+=("$p")
  done
  for c in $(docker ps --format '{{.Names}}' --filter "name=${RUN_ID}" 2>/dev/null); do
    listed=$((listed + 1))
    if err="$(docker pause "$c" 2>&1)"; then RIG_PAUSED+=("$c")
    else echo "WARNING: could not pause $c for the hardware matrix: $err" >&2; fi
  done
  RIG_PAUSE_COMPLETE=false
  [ "$listed" -gt 0 ] && [ "${#RIG_PAUSED[@]}" -eq "$listed" ] && RIG_PAUSE_COMPLETE=true
  echo "--- paused ${#RIG_PAUSED[@]} of ${listed} container(s) (${RIG_PAUSED[*]:-none}) and ${#RIG_STOPPED[@]} sampler(s) for the matrix; complete=${RIG_PAUSE_COMPLETE}"
}
resume_rig() {
  local c p err
  for c in "${RIG_PAUSED[@]:-}"; do
    [ -n "$c" ] || continue
    err="$(docker unpause "$c" 2>&1)" || echo "WARNING: could not unpause $c: $err" >&2
  done
  for p in "${RIG_STOPPED[@]:-}"; do [ -n "$p" ] && kill -CONT "$p" 2>/dev/null || true; done
  RIG_PAUSED=(); RIG_STOPPED=()
}
if [ "${PERF_SERVING_HW_MATRIX:-false}" = "true" ]; then
  SERVING_HW_MATRIX_ATTEMPTED=true
  echo "--- item 27 hardware matrix (opt-in; PERF_SERVING_HW_MATRIX=true, matrix=${PERF_HW_MATRIX:-default})"
  pause_rig
  hwm_rc=0
  MOCKSERVER_IMAGE="$MOCKSERVER_IMAGE" PERF_PERCORE_REPO_ROOT="$REPO_ROOT" PERF_PERCORE_MODE=hw_matrix \
    PERF_HW_MATRIX_RIG_PAUSED="$RIG_PAUSE_COMPLETE" PERF_HW_MATRIX_DEBUG_DIR="$OUT_DIR/serving-hw-matrix-work" \
    bash "$SCRIPT_DIR/lib/perf-percore.sh" "$OUT_DIR/serving-hw-matrix.json" || hwm_rc=$?
  SERVING_HW_MATRIX_JSON="$(percore_block "$OUT_DIR/serving-hw-matrix.json" "$hwm_rc" "lib/perf-percore.sh (hw_matrix)")"
  if jq -e 'has("error")' <<<"$SERVING_HW_MATRIX_JSON" >/dev/null; then
    echo ":x: hardware matrix FAILED — $(jq -r '.error_detail // .error' <<<"$SERVING_HW_MATRIX_JSON") (perf-test-compare.sh fails the build on it; re-assemble the work files with PERF_HW_MATRIX_REASSEMBLE_DIR)" >&2
  else
    echo "--- serving_hw_matrix: points=$(jq -r '(.points|length)//0' <<<"$SERVING_HW_MATRIX_JSON") skipped=$(jq -r '(.skipped|length)//0' <<<"$SERVING_HW_MATRIX_JSON") $(jq -r '[(.points // [])[] | "\(.key)=\(.healthy_ceiling_rps // "none")(\(.status))"] | join(" ")' <<<"$SERVING_HW_MATRIX_JSON")"
  fi
  # Per-point multi-k6 work files (rw results, k6 logs, CPU samples), valid or not.
  if [ -d "$OUT_DIR/serving-hw-matrix-work" ]; then
    if ! tar czf "$REPO_ROOT/serving-hw-matrix-work.tgz" -C "$OUT_DIR" serving-hw-matrix-work 2>/dev/null; then
      echo "WARNING: could not archive $OUT_DIR/serving-hw-matrix-work — no work-files artifact this run" >&2
    elif command -v buildkite-agent >/dev/null 2>&1; then
      bk_upload_artifact "serving-hw-matrix-work.tgz"
    fi
  fi
  resume_rig
fi
# The matrix records its own eligibility (false when it put k6 on the SUT's socket); the run follows it.
if jq -e '.baseline_eligible == false' <<<"$SERVING_HW_MATRIX_JSON" >/dev/null 2>&1; then
  mark_ineligible hw_matrix_placement
  echo "--- baseline eligibility: NOT eligible (.serving_hw_matrix.baseline_eligible=false) — the hardware matrix marked its placement non-baseline"
fi

# --- multi-process aggregate throughput vs process count (client rig) -
# The single-process serving sweep (serving_percore above, and the main .sweep) is
# CLIENT-LIMITED: one k6 process serialises internally (arrival-rate executor +
# metrics pipeline + Go GC) and cannot saturate the widened client core pool, so
# its clean ceiling is a property of the k6 RIG, not the server. This profile drives
# the SAME offered-rate ladder from N INDEPENDENT k6 processes pinned to DISJOINT
# cores and reports the AGGREGATE client-sound ceiling per process-count, plus a
# scaling verdict that discriminates a per-process CLIENT limit (aggregate ceiling
# rises with N) from a SHARED-PATH / server limit (ceiling flat vs N).
# DEFAULT OFF (opt-in): like serving_percore it spins up and tears down its OWN
# pinned SUT(s) and several k6 processes (many extra container lifecycles), so it is
# scheduled DELIBERATELY, enabled by setting
# PERF_SERVING_MULTIPROC=true on a manual/scheduled build, NOT added to every daily
# regression run. Every serving_multiproc.* metric is NOTIFY-ONLY (perf-budgets.json
# omits `gating`). Records INTENT (`serving_multiproc_attempted`) separately from
# success so compare can tell "attempted and produced no points" (RED) apart from
# "profile disabled" (silent) — the same two-axis split serving_percore uses.
#
# PINNING NOTE: this standalone script does its OWN feasibility check and disjoint
# cpuset assignment (server 0..S-1, each client block above it); it does NOT go
# through, and does NOT use, the main run's SERVER_CPUS/K6_CPUS or the
# assert_cpusets_physically_disjoint guard above (which governs only the main SUT vs
# k6). It pins by LOGICAL cpu id and its feasibility counts LOGICAL cpus, so unlike
# the main guard it does not prove the client blocks land on physical cores disjoint
# from the SUT's — see the header of multi-process-sweep.sh and the perf-budgets.json
# _comment_serving_multiproc block. The client-sound filter still excludes any rung
# where a client process reached its CPU pin, so a client-CPU-bound rung is never
# reported as a server figure.
SERVING_MULTIPROC_JSON='{}'
SERVING_MULTIPROC_ATTEMPTED=false
if [ "${PERF_SERVING_MULTIPROC:-false}" = "true" ]; then
  SERVING_MULTIPROC_ATTEMPTED=true
  echo "--- item 18 serving multi-process (opt-in; PERF_SERVING_MULTIPROC=true)"
  # NOT an add_check on failure by itself: the presence gate lives in compare
  # (serving_multiproc_attempted + a non-empty points/skipped set), matching the
  # serving_percore / laptop presence gates.
  mp_rc=0
  MOCKSERVER_IMAGE="$MOCKSERVER_IMAGE" PERF_MULTI_REPO_ROOT="$REPO_ROOT" \
    bash "$REPO_ROOT/mockserver-performance-test/scripts/multi-process-sweep.sh" "$OUT_DIR/serving-multiproc.json" || mp_rc=$?
  SERVING_MULTIPROC_JSON="$(percore_block "$OUT_DIR/serving-multiproc.json" "$mp_rc" "multi-process-sweep.sh")"
  if jq -e 'has("error")' <<<"$SERVING_MULTIPROC_JSON" >/dev/null; then
    echo ":x: serving multi-process profile FAILED — $(jq -r '.error_detail // .error' <<<"$SERVING_MULTIPROC_JSON") (perf-test-compare.sh fails the build on it)" >&2
  else
    echo "--- serving_multiproc: points=$(jq -r '(.points|length)//0' <<<"$SERVING_MULTIPROC_JSON") procs_measured=$(jq -rc '.procs_measured//"?"' <<<"$SERVING_MULTIPROC_JSON") scales_with_procs=$(jq -r '.scaling.scales_with_procs//"?"' <<<"$SERVING_MULTIPROC_JSON") skipped=$(jq -r '(.skipped|length)//0' <<<"$SERVING_MULTIPROC_JSON")"
  fi
fi

abort_if_sut_died
# --- assemble result JSON -----------------------------------------------------
# .commit is the ATTRIBUTED commit resolved in the provenance block above — the SUT
# image's own revision when its label is a well-formed SHA, else the harness commit
# (grace window). This is what the result is filed under and what perf-test-compare.sh
# keys the stored-history object by. The Buildkite build / harness-scripts commit is
# carried separately as harness_commit so a reader can see both when they differ.
COMMIT="$ATTRIBUTED_COMMIT"
BRANCH="${BUILDKITE_BRANCH:-$(git -C "$REPO_ROOT" rev-parse --abbrev-ref HEAD 2>/dev/null || echo unknown)}"
TS="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
# instance_type attributes every stored point to the HARDWARE it was measured on;
# an empty / placeholder / garbage value silently defeats the cross-hardware baseline
# guard — the instance_type:"" bug the config block cites as its worked example.
#
# The perf agents REQUIRE IMDSv2 (HttpTokens=required), so an unauthenticated GET gets
# a 401 and `curl -s` returns "" at exit 0 — a falsy value no `|| fallback` catches.
#
# The fix is the IMDSv2 two-step: PUT /latest/api/token for a short-lived session
# token, then the metadata GET carrying it in X-aws-ec2-metadata-token. Then VALIDATE
# the body against the EC2 instance-type SHAPE and FAIL CLOSED when it is neither a
# plausible IMDS value NOR an explicitly-declared PERF_INSTANCE_TYPE override (the
# off-EC2 escape hatch — a deliberate human declaration, recorded as source
# "declared"). An unattributable hardware label must be a loud red, never a green run
# carrying a placeholder into the baseline history. `[[ =~ ]]` anchors the WHOLE
# string, so a multi-line mangled body (an HTML/JSON error page) cannot slip a
# matching line past it. A short --max-time on BOTH calls keeps a non-EC2 environment
# failing fast rather than hanging the step on a black-holed link-local address.
IMDS_BASE="http://169.254.169.254/latest"
INSTANCE_TYPE_SRC="observed"
# Step 1: acquire an IMDSv2 session token (60s TTL is ample for one GET). An empty
# result means the token endpoint was unreachable/refused — NOT on EC2, or IMDS
# disabled, or the hop limit blocks this container — distinct from a token that works
# but yields a bad metadata body (diagnosed separately in the failure branch below).
IMDS_TOKEN="$(curl -sf --max-time 2 -X PUT "$IMDS_BASE/api/token" \
  -H 'X-aws-ec2-metadata-token-ttl-seconds: 60' 2>/dev/null || true)"
# Step 2: the authenticated metadata GET, attempted ONLY when a token was obtained.
IMDS_INSTANCE_TYPE=""
if [ -n "$IMDS_TOKEN" ]; then
  IMDS_INSTANCE_TYPE="$(curl -sf --max-time 2 \
    -H "X-aws-ec2-metadata-token: $IMDS_TOKEN" \
    "$IMDS_BASE/meta-data/instance-type" 2>/dev/null || true)"
fi
# The SIZE suffix is constrained to the EC2 vocabulary, not just "some token". A bare
# token.token shape would admit a mangled body that happens to look like one -- `proxy.error`
# passes the loose form and would be stored as the instance type. Naming the real sizes costs
# nothing and removes that class; a genuinely new EC2 size suffix fails LOUD (red, with the
# body echoed) rather than silently recording garbage, and PERF_INSTANCE_TYPE is the escape.
if [[ "$IMDS_INSTANCE_TYPE" =~ ^[a-z][a-z0-9-]*\.(nano|micro|small|medium|large|xlarge|metal|metal-[0-9]+xl|[0-9]+xlarge)$ ]]; then
  INSTANCE_TYPE="$IMDS_INSTANCE_TYPE"
elif [ -n "${PERF_INSTANCE_TYPE:-}" ]; then
  INSTANCE_TYPE="$PERF_INSTANCE_TYPE"; INSTANCE_TYPE_SRC="declared"
else
  # Two distinct diagnoses — say which, because they point at different problems.
  if [ -z "$IMDS_TOKEN" ]; then
    IMDS_DIAG="IMDSv2 token PUT (${IMDS_BASE}/api/token) returned nothing — not an EC2 instance, or IMDS is disabled / the hop limit blocks this container. The metadata GET was not attempted."
  else
    IMDS_DIAG="IMDSv2 token acquired, but the metadata GET (${IMDS_BASE}/meta-data/instance-type) returned '${IMDS_INSTANCE_TYPE}' — reached IMDS, but the body is non-2xx or a mangled/garbage value, not a plausible EC2 instance type (family.size, e.g. c7g.2xlarge)."
  fi
  echo "ERROR: instance_type unrecordable — refusing to emit a result that misattributes the hardware it was measured on:" >&2
  echo "  - ${IMDS_DIAG}" >&2
  echo "  - PERF_INSTANCE_TYPE override is '${PERF_INSTANCE_TYPE:-<unset>}' — not set, so there is no declared fallback" >&2
  echo "  (An empty/placeholder/garbage instance_type silently defeats the cross-hardware baseline guard — the instance_type:\"\" bug, which was LIVE in the stored baseline history. For a deliberate off-EC2 run, export PERF_INSTANCE_TYPE with the real instance-type label.)" >&2
  exit 1
fi
GROWTH_JSON="$(cat "$OUT_DIR/growth.json" 2>/dev/null || echo '{}')"
SWEEP_JSON="$(cat "$OUT_DIR/sweep.json" 2>/dev/null || echo '{}')"

# --- regression-presence validity check + assemble the validity block ---------
# PROVOCATION (observed false): if regression.js fails to seed/reach the SUT it
# writes an empty behaviours object (or no file), the jq -e finds zero non-null
# p95 entries and this check evaluates false — a run with no latency numbers must
# not be baselined as if it had them.
if arm_only; then
  : # the arm's own presence check (below) replaces the regression and growth checks
elif jq -e '((.behaviours // {}) | to_entries | map(select(.value.p95_ms != null)) | length) > 0' \
     "$OUT_DIR/regression-http.json" >/dev/null 2>&1; then
  add_check "regression_metrics_present" true "regression behaviours present with non-null latency percentiles"
else
  add_check "regression_metrics_present" false "regression-http.json missing/empty behaviours — latency measurement failed"
fi
# resource_samples_present keys on ROW COUNT, but rows accrue even when only the
# CPU column is populated (docker stats up, /mockserver/metrics unreachable for
# the whole growth phase). Heap is pushed to H[] only when the heap column is
# non-empty, so in that case HEAP_MIN_FIRST/LAST collapse to 0 and
# growth.live_set_bytes would be a 0 that (as a dir:up metric) never exceeds its
# threshold — silently BASELINED, dragging the rolling median down for every
# future run. So gate on the live-set floor being a plausible non-zero value.
# PROVOCATION (observed false): make /mockserver/metrics unreachable for the whole
# growth phase — CPU rows still accrue but HEAP_MIN_LAST is 0 and this fails.
if arm_only; then
  :
elif awk -v v="$HEAP_MIN_LAST" 'BEGIN{exit !(v+0>0)}'; then
  add_check "growth_heap_sampled" true "live-set floor min_last_window=${HEAP_MIN_LAST} bytes (heap metrics captured)"
else
  add_check "growth_heap_sampled" false "no heap samples during growth (/mockserver/metrics unreachable?) — live-set floor is 0, growth.live_set_bytes would poison the baseline as a zero"
fi
# Arm-only: no compare step reads this result, so the arm's own outcome is the run's validity.
# hw_matrix mirrors perf-test-compare.sh's presence gate (a point with a ceiling, or one that ran out of memory).
if [ "$PERF_RUN_ARM" = rw_multik6 ]; then
  if jq -e '.valid == true' <<<"$SERVING_RW_MULTIK6_JSON" >/dev/null 2>&1; then
    add_check "arm_rw_multik6_valid" true "rw-multi-k6-sweep.sh valid (healthy_ceiling_rps=$(jq -r '.headline.healthy_ceiling_rps // "none"' <<<"$SERVING_RW_MULTIK6_JSON"))"
  else
    add_check "arm_rw_multik6_valid" false "rw-multi-k6-sweep.sh result is not valid: $(jq -r '[(.validity.checks // [])[] | select(.ok == false) | .name] | if length == 0 then "no result block" else join(", ") end' <<<"$SERVING_RW_MULTIK6_JSON" 2>/dev/null || echo "no result block")"
  fi
elif [ "$PERF_RUN_ARM" = hw_matrix ]; then
  HWM_USABLE="$(jq -r '[(.points // [])[] | select(.healthy_ceiling_rps != null or .oom_killed == true or .java_out_of_memory == true
      or (.status // "") == "java_out_of_memory" or ((.status // "") == "sut_died" and ((.sut_state.java_oom_errors // 0) > 0)))] | length' \
      <<<"$SERVING_HW_MATRIX_JSON" 2>/dev/null || echo 0)"
  if ! jq -e 'has("error")' <<<"$SERVING_HW_MATRIX_JSON" >/dev/null 2>&1 && [ "${HWM_USABLE:-0}" -gt 0 ]; then
    add_check "arm_hw_matrix_measured" true "${HWM_USABLE} hardware-matrix point(s) with a healthy ceiling or an out-of-memory verdict"
  else
    add_check "arm_hw_matrix_measured" false "hardware matrix measured no usable point: $(jq -r '.error_detail // .error // ([(.points // [], .skipped // [])[] | (.key // "?") + "=" + (.status // .type // "?")] | join(", "))' <<<"$SERVING_HW_MATRIX_JSON" 2>/dev/null || echo "no result block")"
  fi
fi
# Streaming presence. Only asserted when the profile was ATTEMPTED
# (PERF_STREAMING on AND python3 present); a deliberately-skipped profile is not a
# validity failure. When attempted, the match A/B ratio must be present — a null
# ratio means the streaming run produced no comparable latency and the .streaming
# metrics would baseline as nulls/zeros.
if [ "${PERF_STREAMING:-true}" = "true" ] && command -v python3 >/dev/null 2>&1; then
  # $STREAMING_JSON is the FLAT block (its top-level keys are match_p95_ratio etc.
  # — it was built as `(.streaming // {}) as $s | $s + {…}`, already unwrapped), so
  # query match_p95_ratio at the TOP level, NOT `.streaming.match_p95_ratio`. The
  # sibling regression check reads `.behaviours` because it queries the k6 result
  # FILE (top level {proto, behaviours}); this reads the assembled block — same
  # idiom, different shape.
  if jq -e '(.match_p95_ratio != null)' <<<"$STREAMING_JSON" >/dev/null 2>&1; then
    add_check "streaming_metrics_present" true "streaming match A/B present (ratio=$(jq -r '.match_p95_ratio' <<<"$STREAMING_JSON" 2>/dev/null))"
  else
    add_check "streaming_metrics_present" false "streaming profile attempted but match_p95_ratio is null — streaming.js produced no comparable match latency this run"
  fi
fi
# Clustered A/B presence + GENUINENESS. Only asserted when ATTEMPTED
# (PERF_CLUSTERED on AND the clustered image present) — a disabled/absent-image run
# is a green skip, not a failure. When attempted, ALL of: the candidate cluster
# formed a >=2 view, state actually crossed the network (an A-only expectation
# matched on B), the control was genuinely single-node (view 1), and at least one
# arm yielded a p50 ratio. This is the anti-false-green gate: two independent
# in-memory servers (view 1/1) or a non-crossing state would otherwise pass with an
# empty/degenerate block. Proven both directions by PERF_CLU_BREAK=cluster.
if [ "$CLUSTERED_ATTEMPTED" = "true" ]; then
  if jq -e '
      (.cluster.member_count_node_a >= 2)
      and (.cluster.state_crossed_network == true)
      and (.cluster.control_member_count == 1)
      and ((.arms // {}) | to_entries | map(select(.value.p50_ratio != null)) | length > 0)
    ' <<<"$CLUSTERED_JSON" >/dev/null 2>&1; then
    add_check "clustered_metrics_present" true \
      "clustered A/B genuine: view=$(jq -r '.cluster.member_count_node_a' <<<"$CLUSTERED_JSON") state_crossed=$(jq -r '.cluster.state_crossed_network' <<<"$CLUSTERED_JSON") arms=$(jq -r '(.arms // {}) | length' <<<"$CLUSTERED_JSON")"
  else
    add_check "clustered_metrics_present" false \
      "clustered profile attempted but not genuinely clustered/measured: view_a=$(jq -r '.cluster.member_count_node_a // "?"' <<<"$CLUSTERED_JSON") state_crossed=$(jq -r '.cluster.state_crossed_network // "?"' <<<"$CLUSTERED_JSON") control_members=$(jq -r '.cluster.control_member_count // "?"' <<<"$CLUSTERED_JSON") arms_with_ratio=$(jq -r '(.arms // {}) | to_entries | map(select(.value.p50_ratio != null)) | length' <<<"$CLUSTERED_JSON" 2>/dev/null) — would be a false green (two independent servers or no ratio)"
  fi
fi
if [ "${#VALIDITY_CHECKS[@]}" -gt 0 ]; then
  VALIDITY_JSON="$(printf '%s\n' "${VALIDITY_CHECKS[@]}" | jq -sc '{valid: (map(.ok) | all), checks: .}')"
else
  VALIDITY_JSON='{"valid":false,"checks":[]}'
fi

# The cpusets of an arm's measuring k6 processes, ";"-separated in process order: the multi-k6
# arm's as Docker reported them, the matrix's as configured. Empty unless every process has one.
arm_k6_cpusets() { # rw_multik6_json hw_matrix_json
  case "$PERF_RUN_ARM" in
    rw_multik6) jq -r '[(.placement.observed // [])[] | select((.role // "") | startswith("k6_main_")) | .cpuset_cpus] as $s
      | if ($s | length) > 0 and ($s | length) == (.method.procs // -1) and all($s[]; type == "string" and . != "")
        then $s | join(";") else "" end' <<<"$1" 2>/dev/null || true ;;
    hw_matrix) jq -r '(.client_placement.k6_cpusets // []) as $s
      | if ($s | length) > 0 and all($s[]; type == "string" and . != "") then $s | join(";") else "" end' <<<"$2" 2>/dev/null || true ;;
  esac
}

# --- arm-only k6 placement: no process of an arm-only run used the single-k6 pin resolved above,
# so .agent and .config state the arm's k6 cpusets instead, or null when the arm did not record them.
AGENT_K6_CPUS_JSON="$(jq -nc --arg k "${K6_CPUS:-none}" '$k')"
if arm_only; then
  K6_CPUS="$(arm_k6_cpusets "$SERVING_RW_MULTIK6_JSON" "$SERVING_HW_MATRIX_JSON")"
  K6_PHYS_CORES=null
  if [ -n "$K6_CPUS" ]; then K6_PHYS_CORES="$(phys_core_count "${K6_CPUS//;/,}")"; fi
  AGENT_K6_CPUS_JSON="$(jq -nc --arg k "$K6_CPUS" 'if $k == "" then null else $k end')"
  CONFIG_JSON="$(jq -c --argjson k "$AGENT_K6_CPUS_JSON" --argjson p "$K6_PHYS_CORES" \
    '.cpusets.k6 = $k | .cpusets.physical_cores.k6 = $p | .k6_cpu_pin_pct = null' <<<"$CONFIG_JSON")"
  echo "--- arm-only run: k6 cpusets recorded from the arm: ${K6_CPUS:-none recorded} (physical cores $K6_PHYS_CORES)"
fi

# The sub-harness blocks go to jq as FILES: one of them alone can exceed the kernel's per-argument
# limit (128 KiB on Linux), and an --argjson would then fail the whole result with E2BIG.
RESULT_BLOCKS="$OUT_DIR/result-blocks"; mkdir -p "$RESULT_BLOCKS"
printf '%s\n' "$SERVING_PERCORE_JSON" > "$RESULT_BLOCKS/serving_percore.json"
printf '%s\n' "$SERVING_HW_MATRIX_JSON" > "$RESULT_BLOCKS/serving_hw_matrix.json"
printf '%s\n' "$SERVING_MULTIPROC_JSON" > "$RESULT_BLOCKS/serving_multiproc.json"
printf '%s\n' "$SERVING_RW_MULTIK6_JSON" > "$RESULT_BLOCKS/serving_rw_multik6.json"
jq -n \
  --arg commit "$COMMIT" --arg harness_commit "$HARNESS_COMMIT" --arg branch "$BRANCH" --arg ts "$TS" \
  --arg build_number "${BUILDKITE_BUILD_NUMBER:-}" --arg build_url "${BUILDKITE_BUILD_URL:-}" \
  --arg instance_type "$INSTANCE_TYPE" --arg instance_type_src "$INSTANCE_TYPE_SRC" --arg image "$MOCKSERVER_IMAGE" \
  --arg server_cpus "${SERVER_CPUS:-none}" --argjson k6_cpus "$AGENT_K6_CPUS_JSON" \
  --arg network_mode "$PERF_NETWORK_MODE" --arg queue "${BUILDKITE_AGENT_META_DATA_QUEUE:-perf}" --arg run_arm "$PERF_RUN_ARM" \
  --argjson steady "$STEADY_JSON" \
  --argjson server_phys_cores "$SERVER_PHYS_CORES" --argjson k6_phys_cores "$K6_PHYS_CORES" \
  --slurpfile http "$OUT_DIR/regression-http.json" \
  --slurpfile https "$OUT_DIR/regression-https.json" \
  --argjson growth "$GROWTH_JSON" \
  --argjson sweep "$SWEEP_JSON" \
  --argjson saturation "$SATURATION_JSON" \
  --argjson sweep_tail "$SWEEP_TAIL_JSON" \
  --arg saturation_rps "$SATURATION_RPS" \
  --arg rig_valid_peak_achieved_rps "$PEAK_ACHIEVED_RPS" \
  --argjson forward "$FORWARD_JSON" \
  --arg forward_exit "$FORWARD_EXIT" \
  --argjson proxyfwd "$PROXY_FWD_JSON" \
  --argjson workload "$WORKLOAD_JSON" \
  --argjson handshake "$HANDSHAKE_JSON" \
  --argjson path_coverage "$PATH_COVERAGE_JSON" \
  --argjson streaming "$STREAMING_JSON" \
  --argjson clustered_state "$CLUSTERED_JSON" \
  --argjson clustered_attempted "$CLUSTERED_ATTEMPTED" \
  --arg clustered_skip_reason "$CLUSTERED_SKIP_REASON" \
  --argjson pipelining_inorder "$PIPELINING_JSON" \
  --argjson pipelining_inorder_attempted "$PIPELINING_ATTEMPTED" \
  --arg pipelining_inorder_skip_reason "$PIPELINING_SKIP_REASON" \
  --argjson file_body "$FILE_BODY_AB_JSON" \
  --argjson file_body_attempted "$FILE_BODY_AB_ATTEMPTED" \
  --arg file_body_skip_reason "$FILE_BODY_AB_SKIP_REASON" \
  --argjson dataplane_hooks "$DATAPLANE_AB_JSON" \
  --argjson dataplane_hooks_attempted "$DATAPLANE_AB_ATTEMPTED" \
  --arg dataplane_hooks_skip_reason "$DATAPLANE_AB_SKIP_REASON" \
  --argjson clu_ctrl "$CLU_CTRL_BEHAVIOURS" \
  --argjson clu_cand "$CLU_CAND_BEHAVIOURS" \
  --argjson validity "$VALIDITY_JSON" \
  --argjson baseline_eligible "$BASELINE_ELIGIBLE" \
  --argjson baseline_ineligible_reasons "$(jq -nc --arg r "$INELIGIBLE_REASONS" '$r | split(" ") | map(select(. != ""))')" \
  --argjson config "$CONFIG_JSON" \
  --argjson event_log_scaling "$EVENT_LOG_SCALING_JSON" \
  --argjson laptop "$LAPTOP_JSON" \
  --argjson laptop_attempted "$LAPTOP_ATTEMPTED" \
  --argjson laptop_parallel "$LAPTOP_PARALLEL_JSON" \
  --slurpfile serving_percore_f "$RESULT_BLOCKS/serving_percore.json" \
  --argjson serving_percore_attempted "$SERVING_PERCORE_ATTEMPTED" \
  --slurpfile serving_hw_matrix_f "$RESULT_BLOCKS/serving_hw_matrix.json" \
  --argjson serving_hw_matrix_attempted "$SERVING_HW_MATRIX_ATTEMPTED" \
  --slurpfile serving_multiproc_f "$RESULT_BLOCKS/serving_multiproc.json" \
  --argjson serving_multiproc_attempted "$SERVING_MULTIPROC_ATTEMPTED" \
  --slurpfile serving_rw_multik6_f "$RESULT_BLOCKS/serving_rw_multik6.json" \
  --argjson serving_rw_multik6_attempted "$SERVING_RW_MULTIK6_ATTEMPTED" \
  --argjson info_log_level_arm "$INFO_ARM_JSON" \
  --argjson info_log_level_arm_attempted "$INFO_ARM_ATTEMPTED" \
  --arg cpu_start "$CPU_START" --arg cpu_end "$CPU_END" --arg cpu_peak "$CPU_PEAK" --arg cpu_ratio "$CPU_RATIO" \
  --arg cpu_start_median "$CPU_START_MEDIAN" --arg cpu_end_median "$CPU_END_MEDIAN" \
  --arg cpu_start_samples "$CPU_START_SAMPLES" --arg cpu_end_samples "$CPU_END_SAMPLES" \
  --arg cpu_start_occ "$CPU_START_OCC_LO-$CPU_START_OCC_HI" --arg cpu_end_window_s "$CPU_END_WINDOW_S" \
  --arg heap_start "$HEAP_START" --arg heap_end "$HEAP_END" --arg heap_peak "$HEAP_PEAK" --arg heap_ratio "$HEAP_RATIO" \
  --arg heap_min_first "$HEAP_MIN_FIRST" --arg heap_min_last "$HEAP_MIN_LAST" \
  --arg gc_delta "$GC_DELTA" --arg threads_peak "$THREADS_PEAK" \
  '($serving_percore_f[0]) as $serving_percore | ($serving_hw_matrix_f[0]) as $serving_hw_matrix
  | ($serving_multiproc_f[0]) as $serving_multiproc | ($serving_rw_multik6_f[0]) as $serving_rw_multik6
  | {
    # schema_version 2: a `config` block records what the run WAS (JVM/JDK/GC,
    # resolved heap, log level, image digest, k6 pin) so runs are only ever compared
    # when configured alike. A stored run with schema_version 1 has no config block
    # and predates this guarantee — perf-test-compare.sh annotates that boundary.
    # schema_version 3: `.commit` now means the ATTRIBUTED commit (the revision of the
    # measured binary itself when its label is a well-formed SHA — see the provenance
    # block), NOT the Buildkite build commit as in v2; the build/harness commit moves
    # to the new `harness_commit` field. The bump is the NON-SILENT signal of that
    # meaning change — do not read a v2 `.commit` as the revision of the measured
    # binary. The measured NUMBERS are produced identically, so v2 and v3 points stay directly
    # comparable in a baseline window (compare keys metrics on the k6/JMH fingerprints,
    # never on `.commit`); only the attribution label and the stored filename shift.
    schema_version: 3,
    commit: $commit, harness_commit: $harness_commit, branch: $branch, timestamp_utc: $ts,
    build_number: $build_number, build_url: $build_url,
    agent: { instance_type: $instance_type, instance_type_source: $instance_type_src, queue: $queue,
             server_cpus: $server_cpus, k6_cpus: $k6_cpus, network_mode: $network_mode,
             server_physical_cores: $server_phys_cores, k6_physical_cores: $k6_phys_cores },
    config: $config,
    # Event-log scaling profile: the EFFECTIVE (gauge-resolved) maxLogEntries and
    # maxEventLogSizeInBytes, the heap ceiling, the observed retained peak, and which bound actually
    # bound. Always recorded; the fail-closed proportionality assertions ride .validity and fire only
    # on a PERF_LARGE_HEAP_PROFILE run. Compare reads this head-driven, so it is inert until charted.
    event_log_scaling: $event_log_scaling,
    mockserver_image: $image,
    # regression (http + https_h2) + proxy.js FORWARD arms all live in .behaviours
    # (same shape), so the compare step behaviours.* budgets cover them with no jq
    # change. Adding the two forward_*_proxy arms grows the k6 arm-set fingerprint,
    # so compare resets the k6 baseline ONCE (intended — see the forward-proxy arms). CONSEQUENCE
    # for whoever reads the first post-landing runs: because the fingerprint changed,
    # ALL behaviours.* arms (the existing match/forward/template/large arms included)
    # go :new: / no-baseline and are NOT flagged until MIN_BASELINE arm-set-matching
    # runs accrue — a one-off, expected gap in behaviour-arm coverage, surfaced by the
    # compare step "k6 behaviour baseline reset" note. (These arms are notify-only,
    # so no GATING coverage is lost; forward.error_rate and the JMH gates are
    # unaffected as they are not behaviours.* and not fingerprint-filtered.)
    # The clustered A/B raw per-arm latencies land here too (distinct
    # <op>_clustered / <op>_clustered_control keys), so the existing behaviours.*
    # budgets cover them with no new key. Adding these arms GROWS the k6 arm-set
    # fingerprint, so compare resets the k6 behaviour baseline ONCE — the same
    # self-healing, notify-only reset the forward-proxy arms already trigger
    # (no GATING coverage lost). The headline metric — the RATIO — is in
    # .clustered_state (below), which rides the FULL baseline, not this fingerprint.
    # When the clustered profile is disabled/skipped, both objects are {}, so no arms
    # are added and the fingerprint is unchanged.
    behaviours: (($http[0].behaviours // {}) + ($https[0].behaviours // {}) + ($proxyfwd.behaviours // {}) + $clu_ctrl + $clu_cand),
    # TLS/mTLS/native-absent handshake cost (proxy.js handshake mode +
    # per-SUT CPU/alloc augmentation above). NOT part of .behaviours, so it does not
    # touch the k6 fingerprint; compare reads it as its own non-gating metric family.
    tls_handshake: ($handshake.tls_handshake // {}),
    # Path coverage: .arms, .h2_ladder, .problems; {} when disabled. Not .behaviours.
    path_coverage: $path_coverage,
    # LLM/SSE streaming under concurrency: the match-p95 A/B, the
    # server-side inter-token delay-error distribution (idle vs load) and heap per
    # open stream. NOT part of .behaviours, so it does not touch the k6 fingerprint;
    # compare reads a CURATED subset as its own non-gating metric family.
    streaming: $streaming,
    # Clustered state under load: the per-arm ratio (clustered / in-memory
    # control) measured in this run, plus the cluster-genuineness proof (2-node view,
    # state-crossed-the-network, control-is-single-node). NOT part of .behaviours, so
    # it does not touch the k6 fingerprint; compare reads .clustered_state.arms.* as
    # its own non-gating family on the FULL baseline (the within-run ratio already
    # cancels environment, so it must NOT be keyed on the arm set or image digest).
    # {} when the profile was disabled or the clustered image was absent.
    clustered_state: $clustered_state,
    clustered_attempted: $clustered_attempted,
    # WHY the A/B did not run, when it did not: "" when attempted, else one of
    # disabled | image_absent | revision_mismatch. clustered_attempted:false alone
    # cannot distinguish "deliberately off" from "the image never arrived" from
    # "the image was from the wrong commit", and compare.sh had to GUESS in its
    # annotation. A skip that cannot say why is the same shape of defect as a skip
    # nobody sees.
    clustered_skip_reason: $clustered_skip_reason,
    # In-order HTTP/1.1 pipelining A/B: per-arm on / off ratios and the absolutes of both arms, plus the
    # probe proving each arm ran the configuration it claims. Not .behaviours, so the k6 arm-set
    # fingerprint is untouched; compare reads .arms only when .measured. {} when skipped, and
    # pipelining_inorder_skip_reason says why (disabled | no_python3).
    pipelining_inorder: $pipelining_inorder,
    pipelining_inorder_attempted: $pipelining_inorder_attempted,
    pipelining_inorder_skip_reason: $pipelining_inorder_skip_reason,
    # FILE-body A/B: per-size file / inline ratios (only when measured) and the absolutes of both arms.
    # {} when skipped; file_body_skip_reason says why (disabled | host_network).
    file_body: $file_body,
    file_body_attempted: $file_body_attempted,
    file_body_skip_reason: $file_body_skip_reason,
    # Data-plane hooks A/B (opt-in): per-size identity H1 / H2 against no extension, ratios only when
    # measured. {} when skipped; dataplane_hooks_skip_reason says why.
    dataplane_hooks: $dataplane_hooks,
    dataplane_hooks_attempted: $dataplane_hooks_attempted,
    dataplane_hooks_skip_reason: $dataplane_hooks_skip_reason,
    growth: {
      duration_s: ($growth.duration_s // null),
      p95_ms: ($growth.p95_ms // null),
      # start / end / peak are the raw first, last and highest growth samples; ratio is
      # end_median / start_median from diag-samples.csv (ratio_method), which compare reads.
      cpu_pct: { start: ($cpu_start|tonumber), end: ($cpu_end|tonumber), peak: ($cpu_peak|tonumber),
                 ratio: (try ($cpu_ratio|tonumber) catch null), ratio_method: "occupancy_window",
                 start_median: (try ($cpu_start_median|tonumber) catch null),
                 end_median: (try ($cpu_end_median|tonumber) catch null),
                 start_samples: (try ($cpu_start_samples|tonumber) catch 0),
                 end_samples: (try ($cpu_end_samples|tonumber) catch 0),
                 start_occupancy: $cpu_start_occ, end_window_s: (try ($cpu_end_window_s|tonumber) catch null) },
      heap_used_bytes: {
        start: ($heap_start|tonumber), end: ($heap_end|tonumber), peak: ($heap_peak|tonumber),
        # ratio is now the LIVE-SET floor ratio (min last-window / min first-window),
        # not end/start; min_first/last_window are the saw-tooth floors it divides.
        ratio: (try ($heap_ratio|tonumber) catch null),
        min_first_window: ($heap_min_first|tonumber), min_last_window: ($heap_min_last|tonumber)
      },
      gc_seconds_delta: ($gc_delta|tonumber),
      threads_peak: ($threads_peak|tonumber)
    },
    sweep: $sweep,
    # rig_valid_peak_achieved_rps: the max achieved over RIG-VALID rungs only (see
    # derive_saturation). It is a property of the k6 RIG, not the server — the rig-validity
    # filter caps it at whichever rung the client stops being clean, which on this rig can be
    # below the server ceiling. Named to say so. The published website peak is computed
    # in lib/perf-website-figures.jq and is ALSO restricted to rig-valid rungs, so the two
    # agree on which rungs count while being derived separately.
    rig_valid_peak_achieved_rps: (try ($rig_valid_peak_achieved_rps|tonumber) catch null),
    saturation_rps: (try ($saturation_rps|tonumber) catch null),
    saturation: $saturation,
    # Notify-only: per rung, the share of requests over 5 ms at k6 beside the server histogram.
    sweep_tail: $sweep_tail,
    # forward_guard.status distinguishes an INFRA failure (k6 exited non-zero but
    # produced no error_rate — upstream/container error, the guard did not actually
    # run) from a real BREACH (non-zero exit WITH a high error_rate — the pool
    # regression it exists to catch). Same exit code, opposite meaning; compare
    # surfaces the infra case as a loud "guard did not run" annotation so a
    # silently-absent guard cannot pass unnoticed.
    forward_guard: (($forward.forward_guard // {}) + {
      k6_exit: ($forward_exit|tonumber),
      status: (
        if ($forward_exit|tonumber) == 0 then "passed"
        elif ($forward.forward_guard.error_rate) == null then "infra_error"
        else "breached" end) }),
    # OPT-IN workload result (PERF_WORKLOAD=forward): the slow-upstream
    # proxy concurrency pass. {} on a default run. Its own fail-closed
    # served-via-upstream check rides .validity (added above), so this block is
    # head-driven data for the reader, not a second gate.
    workload: $workload,
    # OPT-IN steady-state pass (PERF_STEADY_RATE): one fixed rate, warm-up excluded,
    # with the client tail and the server request-duration histogram over the same
    # window plus their comparison (docs/code/performance-measurement.md ->
    # "Rung-onset exclusion"). {} on a default run.
    steady_experiment: $steady,
    validity: $validity,
    # Baseline eligibility. false for any non-default run (PERF_JVM_DIAGNOSTICS
    # gc or deep, a tuned server, a non-default rig, …): perf-test-compare.sh records it but
    # refuses to persist/compare it (and stays GREEN — a deliberate run, not a failure), so
    # instrumentation overhead can never silently shift the baseline series. A run with
    # no baseline_eligible field (older producer) is treated as eligible, unchanged.
    baseline_eligible: $baseline_eligible,
    baseline_ineligible_reasons: $baseline_ineligible_reasons,
    # Laptop startup/footprint profile (notify-only). `{}` when the profile
    # was disabled or its measurement failed; compare iterates head metrics, so an
    # empty object simply emits zero laptop.* metrics (no missing-budget trip). The
    # separate `laptop_attempted` flag lets compare RED a wholesale failure (attempted
    # but empty/docker-incomplete) instead of mistaking it for a disabled profile.
    laptop: ($laptop.laptop // {}),
    laptop_attempted: $laptop_attempted,
    # N parallel instances (laptop / MockServerExtension profile), notify-only and
    # OPT-IN (PERF_LAPTOP_PARALLEL). `{}` when disabled or when a shape was skipped/failed;
    # compare iterates .laptop_parallel.<shape>.runs, so an empty object emits zero laptop.*
    # metrics (head-driven, no missing-budget trip) — the serving_percore / streaming pattern.
    # No *_attempted flag by design: unlike the laptop and serving_percore presence gates
    # (which RED a wholesale producer failure), laptop_parallel is a deliberately-scheduled research
    # profile whose absence is the normal daily state, so a presence gate would add a RED path
    # with no daily signal to guard. A shape that runs but measures nothing simply emits nothing.
    laptop_parallel: $laptop_parallel,
    # Req/s per core for the SERVING path. `{}` when the profile was
    # disabled or its measurement failed; compare iterates .serving_percore.points,
    # so an empty object emits zero serving_percore.* metrics (no missing-budget
    # trip). serving_percore_attempted lets compare RED a wholesale failure
    # (attempted but no points) apart from a disabled profile — the laptop split.
    serving_percore: $serving_percore,
    serving_percore_attempted: $serving_percore_attempted,
    # Hardware matrix; `{}` when disabled or failed (serving_percore pattern).
    serving_hw_matrix: $serving_hw_matrix,
    serving_hw_matrix_attempted: $serving_hw_matrix_attempted,
    # Multi-process aggregate throughput vs process count.
    # `{}` when the profile was disabled or its measurement failed; compare reads
    # .serving_multiproc.points / .scaling head-driven, so an empty object emits zero
    # serving_multiproc.* metrics (no missing-budget trip). serving_multiproc_attempted
    # lets compare RED a wholesale failure (attempted but no points and no infeasible
    # skip) apart from a disabled profile — the serving_percore / laptop split.
    serving_multiproc: $serving_multiproc,
    serving_multiproc_attempted: $serving_multiproc_attempted,
    # Opt-in remote-write multi-k6 ladder; `{}` when disabled. Compared and
    # published only from the perf-xl arm-only result, never from a daily run.
    serving_rw_multik6: $serving_rw_multik6,
    serving_rw_multik6_attempted: $serving_rw_multik6_attempted,
    # Plan open question 5 — the INFO-log-level PUBLICATION arm. The two PUBLISHED
    # figure families (knee curve + per-behaviour percentiles) re-measured against a
    # SUT at the shipped-default log level, so the site can show an honest
    # out-of-the-box number ALONGSIDE the (legitimate, labelled) ERROR baseline. Lives
    # here under a DISTINCT key — NOT in .behaviours / .sweep / rig_valid_peak_achieved_rps — so
    # an INFO number can never be confused with or diffed against the ERROR series, and
    # carries its own .config.log_level so it is self-describing in the DATA. `{}` when
    # the arm was disabled (PERF_INFO_ARM=false); the sibling *_attempted flag
    # distinguishes disabled from attempted-but-failed (.info_log_level_arm.measured ==
    # false). NON-GATING and EXCLUDED from validity — it never blocks the ERROR
    # baseline. compare.sh does NOT yet read these keys (publication is sequenced after
    # a run emits both series); the info_* budget entries are staged notify-only /
    # provisional for when it does.
    info_log_level_arm: $info_log_level_arm,
    info_log_level_arm_attempted: $info_log_level_arm_attempted
  }
  # Arm-only: growth and the forward guard never ran, so they carry no zero-filled stand-ins.
  | if $run_arm == "" then . else . + {run_arm: $run_arm, growth: {}, forward_guard: {}} end' > "$RESULT_JSON"

echo "--- result.json"
cat "$RESULT_JSON"

# Standalone sweep artifact (also embedded under .sweep in result.json above).
cp "$OUT_DIR/sweep.json" "$REPO_ROOT/perf-sweep.json" 2>/dev/null || echo '{}' > "$REPO_ROOT/perf-sweep.json"
# Standalone serving per-core artifact (also embedded under .serving_percore).
# Emitted only when the profile ran; the website's serving per-core chart
# and a dated trend read this file, mirroring perf-sweep.json / inject-percore.json.
if [ -f "$OUT_DIR/serving-percore.json" ]; then
  cp "$OUT_DIR/serving-percore.json" "$REPO_ROOT/serving-percore.json" 2>/dev/null || true
fi
# Standalone hardware-matrix artifact (also embedded under .serving_hw_matrix).
if [ -f "$OUT_DIR/serving-hw-matrix.json" ]; then
  cp "$OUT_DIR/serving-hw-matrix.json" "$REPO_ROOT/serving-hw-matrix.json" 2>/dev/null || true
fi
# Standalone multi-process sweep artifact (also embedded under .serving_multiproc).
# Emitted only when the profile ran; a dated trend / the client-rig chart read this
# file, mirroring perf-sweep.json / serving-percore.json.
if [ -f "$OUT_DIR/serving-multiproc.json" ]; then
  cp "$OUT_DIR/serving-multiproc.json" "$REPO_ROOT/serving-multiproc.json" 2>/dev/null || true
fi
if [ -f "$OUT_DIR/serving-rw-multik6.json" ]; then
  cp "$OUT_DIR/serving-rw-multik6.json" "$REPO_ROOT/serving-rw-multik6.json" 2>/dev/null || true
fi

# Stop the diagnostics streams so their files are complete, then upload the JVM-internals bundle on
# the SUCCESS path too (the failure path already uploaded it from capture_sut_diagnostics). A healthy
# run's dense resource trajectory (and, on a deep run, its GC log) is itself the reference the NEXT
# death is read against.
[ -n "$DIAG_SAMPLER_PID" ] && kill "$DIAG_SAMPLER_PID" >/dev/null 2>&1 || true; DIAG_SAMPLER_PID=""
[ -n "$SUT_LOG_PID" ] && kill "$SUT_LOG_PID" >/dev/null 2>&1 || true; SUT_LOG_PID=""
[ -n "$INFO_LOG_PID" ] && kill "$INFO_LOG_PID" >/dev/null 2>&1 || true; INFO_LOG_PID=""
[ -n "$LIVE_HISTO_PID" ] && kill "$LIVE_HISTO_PID" >/dev/null 2>&1 || true; LIVE_HISTO_PID=""
dump_load_window_jfr

# A requested data-plane hooks A/B that measured nothing reds this step, after the uploads below:
# compare never reads the block, so a green step would hide it (lib/perf-dataplane-ab.sh). It must also
# leave the commit unrecorded, or the next daily run would guard-skip a commit compare never saw.
DP_AB_OK=true
dp_ab_require_measured "$RESULT_JSON" || DP_AB_OK=false

if command -v buildkite-agent >/dev/null 2>&1; then
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  bk_upload_artifact "perf-result.json"
  arm_only || bk_upload_artifact "perf-sweep.json"
  upload_diag_bundle
  [ -f "$REPO_ROOT/serving-percore.json" ] && bk_upload_artifact "serving-percore.json" || true
  [ -f "$REPO_ROOT/serving-hw-matrix.json" ] && bk_upload_artifact "serving-hw-matrix.json" || true
  [ -f "$REPO_ROOT/serving-multiproc.json" ] && bk_upload_artifact "serving-multiproc.json" || true
  [ -f "$REPO_ROOT/serving-rw-multik6.json" ] && bk_upload_artifact "serving-rw-multik6.json" || true
  # perf-test-guard.sh (via last_perf_run_commit) compares this to its git HEAD to decide whether
  # master moved since the last real run, so it holds the harness-checkout SHA, and only when that
  # SHA is served the measured image (harness_commit_measured). A run on an image mockserver-java
  # has not yet rebuilt for it leaves the commit unrecorded for the next scheduled run.
  # A release run or an arm-only run must not make the guard skip the next daily run of this commit.
  if [ -z "$PERF_RELEASE_COMPARISON" ] && ! arm_only; then
    if [ "$DP_AB_OK" != true ]; then
      echo "--- perf_regression_ran_commit not recorded: the requested data-plane hooks A/B did not measure, so this step fails and the next scheduled run measures ${HARNESS_COMMIT:0:10}"
    elif harness_commit_measured; then
      buildkite-agent meta-data set "perf_regression_ran_commit" "$HARNESS_COMMIT" || true
    else
      echo "--- perf_regression_ran_commit not recorded: the harness commit ${HARNESS_COMMIT:0:10} is not served the SUT image (${ATTRIBUTED_COMMIT:0:10}), so the next scheduled run measures it"
    fi
  fi
else
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  echo "(local run) result + sweep copied to $REPO_ROOT/perf-result.json, $REPO_ROOT/perf-sweep.json"
  upload_diag_bundle
fi

[ "$DP_AB_OK" = true ] || exit 1

# Arm-only: nothing downstream gates this result, so this step reds on an invalid one.
if arm_only; then
  ARM_SUMMARY="$(jq -r '"\(.run_arm) on \(.agent.instance_type) (queue \(.agent.queue)): valid=\(.validity.valid); "
    + ([.validity.checks[] | "\(.name)=\(.ok)"] | join(", "))' "$RESULT_JSON" 2>/dev/null || echo "result unreadable")"
  if jq -e '.validity.valid == true' "$RESULT_JSON" >/dev/null 2>&1; then
    echo "--- arm-only result: $ARM_SUMMARY"
  else
    echo "+++ :x: arm-only result is NOT valid: $ARM_SUMMARY" >&2
    if command -v buildkite-agent >/dev/null 2>&1; then
      printf '%s\n' ":x: **${PERF_RUN_NAME}** measured nothing trustworthy: ${ARM_SUMMARY}. Artifacts: \`${ARTIFACT_PREFIX}perf-result.json\` and the work files." \
        | buildkite-agent annotate --style error --context "perf-arm-${PERF_RUN_NAME}" || true
    fi
    exit 1
  fi
fi

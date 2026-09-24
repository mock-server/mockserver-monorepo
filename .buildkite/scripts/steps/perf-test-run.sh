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

K6_IMAGE="grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f"
# The SUT runs the -graaljs snapshot variant so the JavaScript response-template
# arm (regression.js item 15a) has the GraalVM JS engine available; the engine is
# an OPTIONAL dependency absent from the plain image, and a JS template without it
# fails loud (500). The extra jars are inert for every non-JS arm (loaded lazily
# only when a JS template renders), so the match/forward/velocity/mustache/large
# numbers are unaffected. Overridable; if you point this at a NON-graaljs image,
# also set PERF_JS_TEMPLATE=false or regression.js aborts the run loudly.
MOCKSERVER_IMAGE="${MOCKSERVER_IMAGE:-mockserver/mockserver:mockserver-snapshot-graaljs}"
# item 15a — enable the JavaScript template arm (default on; the SUT image above
# carries GraalJS). item 15d — server-side path of the file-backed response body.
PERF_JS_TEMPLATE="${PERF_JS_TEMPLATE:-true}"
FILE_BODY_CONTAINER_PATH="/perf-files/large-file-body.json"
RUN_ID="${BUILDKITE_BUILD_ID:-local}-$$"
NETWORK="mockserver-perf-${RUN_ID}"
SERVER="mockserver-perf-${RUN_ID}"
UPSTREAM="mockserver-upstream-${RUN_ID}"
# item 12 — the background k6 that drives the streaming concurrency load, and a
# DEDICATED, deliberately CONSTRAINED SUT it drives (low CPU + a small
# action-handler pool) so the scheduler saturates at a modest, DETERMINISTIC
# concurrency regardless of the agent's core count — otherwise on the pinned
# 6-core CI SUT the match A/B never leaves ~1.0 and the tripwire is placed where
# nothing happens (see the streaming phase's header for the full rationale).
STREAM_K6="mockserver-perf-k6-stream-${RUN_ID}"
STREAM_SUT="mockserver-perf-stream-sut-${RUN_ID}"
# item 13 — clustered state under load (within-run A/B). A single in-memory-backend
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
# Hard memory bound for the SUT (item: measure growth against a realistic heap).
# Unbounded on a 32 GB box, MaxRAMPercentage=75 yields a ~24 GB heap that barely
# GCs, so a slow leak is invisible and the "live set" is unobservable. A bounded
# heap that actually cycles is what the documented central-deployment guidance
# runs, and is what makes the saw-tooth floor (see the live-set ratio below) mean
# something. Applied to the SUT only, never the upstream. Overridable for a re-run.
SERVER_MEMORY="${PERF_SERVER_MEMORY:-2g}"

# --- event-log body-byte budget: the OOM guard that keeps the run alive ---------
# WHY THIS EXISTS (build #249 died here). MockServer records every request AND its
# response in a COUNT-bounded event-log ring of maxLogEntries entries, holding the
# FULL body of each. On the 2 GB SUT the heap is MaxRAMPercentage=75% ~= 1.5 GB, so
# maxLogEntries = min(heapKB/8, 100000) = 100000. An entry lives for the ring's
# residence = maxLogEntries / total_ACHIEVED_insertion_rps, and — this is the trap —
# residence LENGTHENS without bound as achieved throughput FALLS. The MB-scale
# regression arms (large_1mb/large_10mb/large_file, k6 item 15d) then retain, per arm,
# roughly rate x residence x body:
#     arm          rate     body     @ residence 111 s (total ~901 rps, healthy)
#     large_10mb   0.1/s    10 MB    -> 0.1 x 111 x 10 MB  ~= 111 MB
#     large_1mb    0.5/s     1 MB    -> 0.5 x 111 x  1 MB  ~=  55 MB
#     large_file   0.5/s    ~1 MB    -> 0.5 x 111 x  1 MB  ~=  55 MB   (its ~1 MB RESPONSE)
#     large(4KB)   200/s     4 KB    -> 200 x 111 x  4 KB  ~=  89 MB
# ~310 MB of large bodies looks safe under 1.5 GB — but ONLY at residence 111 s. When
# the SUT contends (the pre-fix JavaScript contagion, or the EXTRA body throughput the
# 2026-09-17 dispatch-pool fixes eec183f7e/7fbae1350 now ADMIT), total achieved rps
# collapses, residence 2x/4x/10x, and every figure above scales with it — there is no
# upper bound as achieved rps -> 0. Two back-to-back protocol passes (http then
# https_h2) compound it. That is what pushed retention past the heap in build #249 and
# killed the container mid-run, so the second (https_h2) pass could not even seed
# ("no such host").
# THE FIX: bound RETENTION, not rate. maxEventLogSizeInBytes is MockServer's own OOM
# guard (MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES) — when > 0 the ring ALSO enforces a
# body-byte budget, evicting oldest-first until total logged body bytes fit, in
# addition to the count bound. Retention is then capped at the budget REGARDLESS of
# residence, so no rate x residence product can run away however far throughput falls.
# 256 MiB is the documented starting point for a 2 GB heap; actual live heap is a small
# multiple (headers/metadata) ~= 0.5-1 GB, comfortably under 1.5 GB.
# WHY NOT SHRINK maxLogEntries INSTEAD: growth.js runs on THIS SAME SUT and must fill
# the DEFAULT 100k ring to reproduce the issue #2329 O(n)-eviction slope; a smaller
# ring would never fill and would hide the bug. The byte budget does NOT corrupt growth
# because growth loads only the tiny /simple body (~hundreds of bytes) — its total
# retained bytes across 100k entries stay in the tens of MB, far below 256 MiB, so the
# byte budget never fires for growth and its count-bounded fill is untouched. Applied to
# EVERY SUT that runs regression.js with the MB arms: the main SUT here and the clustered
# A/B nodes (start_clu) — the clustered image runs the same large_1mb/large_10mb arms on
# a 1.5 GB heap and was at the identical risk.
PERF_MAX_EVENT_LOG_BYTES="${PERF_MAX_EVENT_LOG_BYTES:-268435456}" # 256 MiB

# Accept-queue depth for the SUT. Unset by default so the headline figure describes the
# shipped configuration; setting it labels the result config_profile "tuned".
PERF_SO_BACKLOG="${PERF_SO_BACKLOG:-}"

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
#     -Xlog:gc*                    the FINE per-GC-event trajectory to /diag/<sut>/gc.log (see the
#                                   heap approach the cliff) — moved here from tier 1, see above
#     JFR profile recording        allocation + CPU-hotspot profiling, chunks written to /diag/jfr-repo
#                                   (survive even a hard OOM exit), ~2-8% depending on event settings
#     -XX:NativeMemoryTracking=summary + -XX:+PrintNMTStatistics  precise native breakdown printed to
#                                   stdout at JVM exit (captured by the docker-logs follower), ~5-10%
#     periodic jcmd sampling        GC.heap_info / GC.class_histogram / Thread.print / VM.native_memory
#                                   from a JDK sidecar sharing the SUT PID namespace (best-effort; the
#                                   shipped image is distroless with no jcmd of its own)
PERF_JVM_DIAGNOSTICS="${PERF_JVM_DIAGNOSTICS:-standard}"   # standard = tier 1 only; deep = tier 1 + tier 2
PERF_DIAG_SAMPLE_INTERVAL="${PERF_DIAG_SAMPLE_INTERVAL:-2}" # dense enough to see the cliff APPROACH, not just its aftermath
# Heap dumps are large (a 1.5 GiB heap dumps > 1 GiB); upload only when gzipped size is within this
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
PERF_HISTO_DEADLINE_S="${PERF_HISTO_DEADLINE_S:-90}" # linear read of a 2.4 GB dump is ~1-2s locally; 90s is huge headroom, and a timeout yields a TRUNCATED top-N rather than nothing
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
# Tier-2 JVM opts (only when PERF_JVM_DIAGNOSTICS=deep). Adds the throughput-costing diagnostics an
# INVESTIGATION run wants: the per-GC-event trajectory to a file (the fine-grained approach-to-the-cliff
# evidence), NMT, and JFR. JFR repository on the mounted volume so the chunk files survive a hard
# ExitOnOutOfMemoryError exit even if the named .jfr is never finalised.
tier2_jvm_opts() {
  local sub="$1"
  # -XX:+UnlockDiagnosticVMOptions MUST precede PrintNMTStatistics (it is a diagnostic flag; the JVM
  # refuses to start otherwise — caught by the local OOM proof).
  printf -- '-Xlog:gc*,gc+heap=info:file=/diag/%s/gc.log:time,uptime,level,tags:filecount=5,filesize=20m -XX:NativeMemoryTracking=summary -XX:+UnlockDiagnosticVMOptions -XX:+PrintNMTStatistics -XX:StartFlightRecording=name=perfdiag,settings=profile,filename=/diag/%s/recording.jfr,dumponexit=true,maxsize=256m -XX:FlightRecorderOptions=repository=/diag/%s/jfr-repo,stackdepth=128' "$sub" "$sub" "$sub"
}
# Combined diagnostics JVM opts for a diag SUT ($1 = /diag subdir), honouring the tier flag.
diag_jvm_opts() {
  local sub="$1" opts
  opts="$(tier1_jvm_opts "$sub")"
  [ "$PERF_JVM_DIAGNOSTICS" = "deep" ] && opts="$opts $(tier2_jvm_opts "$sub")"
  printf '%s' "$opts"
}

# item 15d — file-backed response body arm. Generate a ~1 MB JSON file on the host
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
SUT_LOG_PID=""        # docker-logs follower for the main SUT
INFO_LOG_PID=""       # docker-logs follower for the INFO SUT
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
# to skip it if the (serialised) perf box is time-pressed — it is a pure add-on and
# nothing else in the run depends on it.
INFO_SERVER="mockserver-perf-info-${RUN_ID}"
INFO_SERVER_ALIAS="mockserver-info"
INFO_SWEEP_K6="k6-info-sweep-${RUN_ID}"
PERF_INFO_ARM="${PERF_INFO_ARM:-true}"
# Upload the surviving JVM-internals diagnostics as Buildkite artifacts. Small evidence (GC log,
# the dense resource-trajectory CSV, the followed server logs, docker-inspect .State, any tier-2 NMT
# print + JFR chunks) always goes up as one tarball. Heap dumps are handled separately and SIZE-CAPPED
# (a 1.5 GiB heap dumps > 1 GiB): each is gzipped and uploaded only if within PERF_HEAPDUMP_MAX_UPLOAD_MB,
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

upload_diag_bundle() {
  [ -f "$DIAG_DIR/.uploaded" ] && return 0
  command -v buildkite-agent >/dev/null 2>&1 || { echo "--- (local run) JVM diagnostics left in $DIAG_DIR" >&2; return 0; }
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
  # gzip fails into `|| continue` so the dump is never uploaded at all. Observed on build 302, where
  # the bundle carried a 0 MiB dump for a real 2.02 GiB file and the liveness question it existed to
  # answer could not be answered. chmod from a throwaway container (running as root, same trick the
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
      cp "$gz" "$REPO_ROOT/${sub}-heapdump.hprof.gz" 2>/dev/null \
        && buildkite-agent artifact upload "${sub}-heapdump.hprof.gz" 2>/dev/null \
        && echo "--- uploaded heap dump ($sub, ${szmb} MiB gzipped)" >&2
    else
      echo "--- heap dump $sub is ${szmb} MiB gzipped (> ${PERF_HEAPDUMP_MAX_UPLOAD_MB} MiB cap) — NOT uploaded; the class histogram (in the bundle) names the retaining class, and the raw dump is on the volume" >&2
    fi
  done
  # Everything else (small) as one tarball. Heap dumps are EXCLUDED — they are uploaded (or not)
  # separately above under the size cap, so they must never bloat this always-uploaded bundle.
  ( cd "$DIAG_DIR" && tar czf "$REPO_ROOT/perf-jvm-diagnostics.tgz" --exclude='*.hprof' --exclude='*.hprof.gz' . 2>/dev/null ) \
    && buildkite-agent artifact upload "perf-jvm-diagnostics.tgz" 2>/dev/null \
    && echo "--- uploaded perf-jvm-diagnostics.tgz (resource trajectory, server logs, docker-inspect state, heap class-histogram if an OOM occurred$([ "$PERF_JVM_DIAGNOSTICS" = deep ] && echo ', gc log, NMT, JFR'))" >&2 || true
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
  for pair in "${SERVER:-}:sut" "${INFO_SERVER:-}:info"; do
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
  upload_diag_bundle
}

cleanup() {
  local rc=$?
  # On ANY non-zero exit (the mid-load death, an early wait_ready failure, a sampler/sweep abort),
  # grab the SUT post-mortem BEFORE docker rm -f below erases it. This is the whole point: builds
  # 261/264 died here and left nothing because --rm + this rm -f raced the investigator to the body.
  if [ "$rc" -ne 0 ]; then capture_sut_diagnostics; fi
  [ -n "$SAMPLER_PID" ] && kill "$SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$SWEEP_SAMPLER_PID" ] && kill "$SWEEP_SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$DIAG_SAMPLER_PID" ] && kill "$DIAG_SAMPLER_PID" >/dev/null 2>&1 || true
  [ -n "$SUT_LOG_PID" ] && kill "$SUT_LOG_PID" >/dev/null 2>&1 || true
  [ -n "$INFO_LOG_PID" ] && kill "$INFO_LOG_PID" >/dev/null 2>&1 || true
  [ -n "${HS_CPU_PID:-}" ] && kill "$HS_CPU_PID" >/dev/null 2>&1 || true
  # The item 14 handshake SUTs (deterministic names from RUN_ID) — removed here too
  # so an early exit before the proxy block's own cleanup never leaks them.
  docker rm -f "$SERVER" "$UPSTREAM" "$SWEEP_K6" "$STREAM_K6" "$STREAM_SUT" \
    "$INFO_SERVER" "$INFO_SWEEP_K6" \
    "$CLU_CTRL" "$CLU_A" "$CLU_B" \
    "mockserver-mtls-${RUN_ID}" "mockserver-jdk-${RUN_ID}" >/dev/null 2>&1 || true
  docker network rm "$NETWORK" >/dev/null 2>&1 || true
  [ -n "${FILE_BODY_DIR:-}" ] && rm -rf "$FILE_BODY_DIR" >/dev/null 2>&1 || true
  [ -n "${HS_CERT_DIR:-}" ] && rm -rf "$HS_CERT_DIR" >/dev/null 2>&1 || true
}
# EXIT alone does NOT fire on SIGTERM/SIGINT — and Spot reclamation + Buildkite job cancellation send
# SIGTERM before SIGKILL. On exactly those abnormal paths (the ones that motivated dropping --rm) the
# EXIT trap would never run, so cleanup()'s docker rm -f would not run and the non-rm SUT would linger.
# Trap the signals too so the post-mortem capture + removal still happen. (A SIGKILL still bypasses
# everything — hence the startup sweep below reaps any container a prior killed run left behind.)
trap cleanup EXIT INT TERM
# Reap any leftover perf SUTs from a previously-cancelled/killed run before we start — otherwise a
# lingering non-rm container from a SIGKILLed run (or a name collision) would wedge this one.
# shellcheck disable=SC2046  # intentional word-splitting of the id list
docker rm -f $(docker ps -aq --filter name=mockserver-perf- 2>/dev/null) >/dev/null 2>&1 || true

# --- validity accumulation (item: validity blocks on every result) ------------
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
      aborted:"sut_died", baseline_eligible:false, validity:$validity}' \
    > "$REPO_ROOT/perf-result.json" 2>/dev/null || true
  command -v buildkite-agent >/dev/null 2>&1 \
    && buildkite-agent artifact upload "perf-result.json" >/dev/null 2>&1 || true
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
else
  echo ":x: CPU-topology guard lib not found at $SCRIPT_DIR/lib/perf-cpu-topology.sh — refusing to run without the physical-core disjointness proof" >&2
  exit 1
fi

# THE GUARD. The cpusets below are chosen to be disjoint — but "disjoint" has to
# mean disjoint in PHYSICAL cores, and for a long time it did not.
#
# On the c5.4xlarge this queue used to run, the defaults asked for 6 (server) + 1
# (upstream) + 6 (k6) = 13 cores on a box with 16 vCPU but only EIGHT physical
# cores. Thirteen into eight does not go, so k6 necessarily ran on the sibling
# threads of cores the server was already saturating. Every throughput figure the
# rig produced was therefore a measurement of a server sharing silicon with its own
# load generator, and it is the main reason "the client saturates first" blocked
# the knee for so long (k6 read 600.9% against its 600% pin on a VALID run).
#
# Nothing caught it because the check above was `_NPROCESSORS_ONLN >= 16`, which
# counts LOGICAL cpus — the one number that cannot detect this. So the check now
# resolves real topology and fails the run rather than quietly producing a figure
# that means something other than it claims.
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
CORES="$(getconf _NPROCESSORS_ONLN 2>/dev/null || echo 0)"
SERVER_CPUS=""; UPSTREAM_CPUS=""; K6_CPUS=""
if [ "$CORES" -ge 16 ]; then
  # Defaults: server=0-5 upstream=6 k6=8-19. NOTE these are LOGICAL cpu ids; the
  # guard below is what establishes they land on distinct physical cores, because
  # this list alone cannot tell you that.
  #
  # k6 holds TWELVE cores, not the six it had on the old box. That is not tuning
  # for its own sake — build 397, the first run on the 24-core c5.12xlarge, came
  # back with only the rungs up to 4,000 rps rig-valid and k6 shedding 203, then
  # 3,908, then 103,558, then 343,971, then 558,068 iterations as the ladder
  # climbed. The server was not refusing that load; the client could not generate
  # it. The box had grown from 8 physical cores to 24 and this line had not moved,
  # so eleven cores sat idle while the measurement stayed client-bound.
  #
  # The SERVER's cpuset is deliberately unchanged at six cores. Widening it would
  # change the subject of the measurement and reset the baseline again; the point
  # here is to let the client saturate the same six-core server, not to measure a
  # bigger one.
  #
  # These defaults now need a box with at least NINETEEN physical cores (6 + 1 +
  # 12). c5.12xlarge has 24. A c5.9xlarge has only 18, so the guard below would
  # fail the run rather than let k6 quietly share the server's cores again —
  # verified against a simulated 18-core topology. If the perf queue is ever moved
  # to a smaller box, narrow PERF_K6_CPUS to match instead of disabling the guard.
  #
  # Each cpuset is overridable via PERF_SERVER_CPUS / PERF_UPSTREAM_CPUS / PERF_K6_CPUS
  # so a re-run can, e.g., hand k6 more cores to drive higher arrival rates.
  SERVER_CPUS="${PERF_SERVER_CPUS:-0-5}"; UPSTREAM_CPUS="${PERF_UPSTREAM_CPUS:-6}"; K6_CPUS="${PERF_K6_CPUS:-8-19}"
  echo "--- core-pinning enabled (${CORES} logical cpus): server=$SERVER_CPUS upstream=$UPSTREAM_CPUS k6=$K6_CPUS"
  assert_cpusets_physically_disjoint || exit 1
else
  echo "--- WARNING: ${CORES} logical cpus (<16) — core-pinning skipped; numbers will be noisier"
fi
cpuset_arg() { [ -n "$1" ] && printf -- '--cpuset-cpus=%s' "$1"; }

docker network create "$NETWORK" >/dev/null

start_mockserver() {
  local name="$1" cpus="$2" alias="$3" publish="${4:-}" mem="${5:-}" mount="${6:-}" log_level="${7:-ERROR}" diag_subdir="${8:-}"
  # log_level defaults to ERROR — the tracked baseline's level, which every existing
  # caller relies on. The INFO publication arm (plan open question 5) passes INFO
  # explicitly; nothing else does, so the ERROR baseline is unaffected.
  # PERF_SERVER_JAVA_OPTS (when set) is passed through as JAVA_TOOL_OPTIONS so a
  # re-run can opt into a tuned JVM (e.g. low-pause GC + a larger heap) for nicer
  # documentation-site throughput/latency figures. Applied to BOTH the SUT and
  # the upstream so the upstream never becomes the bottleneck under those tuned
  # rates. Request logging is deliberately left ON (we don't disable it here) so
  # the growth phase stays meaningful. Built as an array element so the value
  # survives intact as a SINGLE -e pair even though it contains spaces; unset =>
  # the array is empty and no -e flag is added, identical behaviour.
  # When diag_subdir is set (the SUT and INFO SUT only) attach the JVM-internals diagnostics: mount
  # the host $DIAG_DIR so gc.log / heap dump / JFR survive the container's death, append the tier-1
  # (and, when PERF_JVM_DIAGNOSTICS=deep, tier-2) JVM opts to JAVA_TOOL_OPTIONS, and — critically —
  # DROP --rm so the dead container lingers long enough for capture_sut_diagnostics() to read its
  # post-mortem (docker inspect .State / docker logs). cleanup() force-removes it by name, so nothing
  # leaks. Every OTHER container (upstream, stream/clustered/handshake SUTs) keeps --rm unchanged.
  local rm_flag="--rm" diag_mount_arg=() combined_java_opts="${PERF_SERVER_JAVA_OPTS:-}"
  if [ -n "$diag_subdir" ]; then
    rm_flag=""
    diag_mount_arg=(-v "$DIAG_DIR:/diag")
    local diag_opts; diag_opts="$(diag_jvm_opts "$diag_subdir")"
    combined_java_opts="${combined_java_opts:+$combined_java_opts }$diag_opts"
  fi
  local java_opts_arg=()
  [ -n "$combined_java_opts" ] && java_opts_arg=(-e "JAVA_TOOL_OPTIONS=$combined_java_opts")
  # shellcheck disable=SC2046
  docker run -d $rm_flag --name "$name" --network "$NETWORK" --network-alias "$alias" \
    $(cpuset_arg "$cpus") \
    ${mem:+--memory="$mem"} \
    ${mount:+-v "$mount"} \
    ${diag_mount_arg[@]+"${diag_mount_arg[@]}"} \
    ${publish:+-p 127.0.0.1::1080} \
    ${java_opts_arg[@]+"${java_opts_arg[@]}"} \
    -e MOCKSERVER_LOG_LEVEL="$log_level" \
    -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
    -e MOCKSERVER_METRICS_ENABLED=true \
    -e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES="$PERF_MAX_EVENT_LOG_BYTES" \
    ${PERF_SO_BACKLOG:+-e MOCKSERVER_SO_BACKLOG="$PERF_SO_BACKLOG"} \
    "$MOCKSERVER_IMAGE" -serverPort 1080 >/dev/null
}

wait_ready() {
  local name="$1"
  for _ in $(seq 1 60); do
    local status
    status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}nohealth{{end}}' "$name" 2>/dev/null || echo missing)"
    case "$status" in
      healthy) return 0 ;;
      nohealth) sleep 10; return 0 ;;
      missing) echo "ERROR: container $name exited early" >&2; docker logs "$name" 2>&1 | tail -20 >&2 || true; return 1 ;;
    esac
    sleep 2
  done
  echo "ERROR: $name did not become ready" >&2; return 1
}

# The server's network alias is `mockserver`, which k6's config.js treats as a
# local/private target — so the HTTPS pass auto-trusts the self-signed cert with
# no per-VU TLS warning, and no reliance on an explicit insecure flag.
SERVER_ALIAS="mockserver"

# --- SUT image freshness: pull the mutable tag before measuring (part D) --------
# MOCKSERVER_IMAGE is a MUTABLE tag (mockserver-snapshot-graaljs) rebuilt on every
# master merge. With no pull, `docker run` silently uses whatever layer set the
# agent happens to have cached — build #264 measured an entirely cached image with
# nothing tying it to the commit under test. Pull ONCE up front rather than
# `--pull always` on each of the ~6 `docker run` calls below: --pull always would
# re-query the registry per container (upstream, SUT, stream SUT, clustered nodes,
# INFO SUT) for the same tag; a single pull freshens the shared local image once.
# DECISION (part D asked to argue it): pull, don't skip. Cost is one pull (seconds)
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

# --- item 13: pull the CLUSTERED image here too, beside the SUT pull -----------
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

echo "--- starting upstream + MockServer ($MOCKSERVER_IMAGE)"
start_mockserver "$UPSTREAM" "$UPSTREAM_CPUS" "mockserver-upstream"
start_mockserver "$SERVER" "$SERVER_CPUS" "$SERVER_ALIAS" "publish" "$SERVER_MEMORY" "$FILE_BODY_MOUNT" "ERROR" "sut"
# Follow the SUT's stdout/stderr into a host file from the moment it starts. The follow stream ENDS
# when the container dies, so the file survives the container's reaping and captures the JVM's dying
# words — an OutOfMemoryError stack, a "Terminating due to java.lang.OutOfMemoryError" from
# ExitOnOutOfMemoryError, or (tier 2) the PrintNMTStatistics native-memory summary. This matters
# because the SUT runs at log_level=ERROR with DISABLE_SYSTEM_OUT=true, so normal output is sparse
# and the death message would otherwise be the only signal — and it is exactly what --rm destroyed.
docker logs -f "$SERVER" > "$DIAG_DIR/sut/sut-server.log" 2>&1 &
SUT_LOG_PID=$!
echo "--- SUT started with --memory=$SERVER_MEMORY (bounded heap so GC cycles) + maxEventLogSizeInBytes=$PERF_MAX_EVENT_LOG_BYTES (body-byte OOM guard)"
wait_ready "$UPSTREAM"
wait_ready "$SERVER"

# Host-mapped metrics port so the sampler reads /mockserver/metrics from the host
# (curl on the agent) instead of spawning a container per sample — avoids adding
# CPU noise to the very box being measured. k6 still reaches the server over the
# docker network (container alias), unaffected by this host publish.
SERVER_METRICS="$(docker port "$SERVER" 1080/tcp 2>/dev/null | head -1)"
SERVER_METRICS_URL="http://${SERVER_METRICS:-127.0.0.1:1080}/mockserver/metrics"

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
  if [ -n "$CONFIG_METRICS" ] && printf '%s' "$CONFIG_METRICS" | grep -q '^mock_server_build_info'; then break; fi
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
# One env var as the SUT container ACTUALLY received it (empty if unset).
# NOTE this is a WEAKER observation than gc/heap_max_bytes, which are read from the JVM's own
# metrics endpoint. This only proves the container was HANDED the value, not that the JVM parsed
# and applied it - hence the config block labels it container-env rather than observed. If the
# server ever exposes the effective budget as a metric, read it from there instead.
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
# Body-byte OOM guard the SUT ACTUALLY received (observed from the container env), so
# a reader can see the guard was in force for this run — and so the fail-closed check
# below reddens loudly if a future edit ever drops it (a run without the guard is at
# the exact OOM risk build #249 hit, and must never be silently baselined as a healthy
# one). Read back from the container, not echoed from the shell var, on purpose.
MAX_EVENT_LOG_VAL="$(container_env MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES)"
# Accept-queue depth the SUT actually received. Absent is normal (shipped default), so
# empty is not an error — but declared-yet-unapplied is, since the run would be labelled
# tuned while measuring a default server.
SO_BACKLOG_VAL="$(container_env MOCKSERVER_SO_BACKLOG)"
CONFIG_PROFILE="default"; [ -n "$PERF_SO_BACKLOG" ] && CONFIG_PROFILE="tuned"
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
awk -v v="$HEAP_MAX_BYTES" 'BEGIN{exit !(v+0>0)}' || CONFIG_ERRORS+=("resolved heap (jvm_memory_max_bytes{area=\"heap\"}) not a positive value: '${HEAP_MAX_BYTES}'")
[ -n "$LOG_LEVEL_VAL" ]     || CONFIG_ERRORS+=("MOCKSERVER_LOG_LEVEL not recordable (neither container env nor shell)")
[ -n "$DISABLE_SYSOUT_VAL" ] || CONFIG_ERRORS+=("MOCKSERVER_DISABLE_SYSTEM_OUT not recordable (neither container env nor shell)")
awk -v v="$MAX_EVENT_LOG_VAL" 'BEGIN{exit !(v+0>0)}' || CONFIG_ERRORS+=("event-log body-byte OOM guard (MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES) not applied to the SUT, OR the container env could not be read (docker inspect failed) - these are not distinguished here and both fail closed: '${MAX_EVENT_LOG_VAL}' — a run without it is at the build-#249 OOM risk and must not be baselined as healthy")
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

# --- source-provenance verdict (parts A/B): can this run be attributed, and to
# --- WHICH commit? -------------------------------------------------------------
# A performance result describes the BINARY THAT WAS MEASURED, not this checkout of
# the harness scripts. So the result is FILED UNDER (and keyed in stored history by)
# the SUT image's OWN commit — its org.opencontainers.image.revision label — whenever
# that label is a trustworthy, well-formed git SHA (ATTRIBUTED_COMMIT below). The
# Buildkite build's commit (this checkout of the perf scripts) is recorded SEPARATELY
# as HARNESS_COMMIT: if the harness changed, the measurement METHOD changed and a
# reader must see both. This deliberately STOPS treating image-lag — a snapshot tag
# that trails master by one java-pipeline duration — as a failure: a lagging image was
# measured correctly and is attributable to the commit it was built from, which is
# exactly what this result now records (build #284's three-docs-commit lag was a false
# alarm under the old equality check). Three outcomes, and provenance STILL fails
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
HARNESS_COMMIT="${BUILDKITE_COMMIT:-$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || echo unknown)}"
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
if ! printf '%s' "$PROVENANCE_GRACE_UNTIL" | grep -qE '^[0-9]{4}-(0[1-9]|1[0-2])-(0[1-9]|[12][0-9]|3[01])$'; then
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
  if [ "$GRACE_VALID" != true ]; then
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

# --- baseline eligibility (part C): keep an INSTRUMENTED run out of the baseline
# PERF_JVM_DIAGNOSTICS=deep enables tier-2 diagnostics (GC file logging + NMT + JFR)
# that cost throughput BY DESIGN, so a deep run that completes must be RECORDED but
# NOT persisted into the baseline history — otherwise it silently shifts the series,
# the exact contamination the tier split was created to prevent (as gc/jdk/heap were
# already recorded but the tier never was). This is DISTINCT from validity: a deep
# run is a VALID measurement of an instrumented server, not a broken rig, so it must
# stay GREEN (the investigation run was triggered on purpose) — hence a separate
# baseline_eligible flag consumed by compare, NOT a validity check (which would red
# the build). Defensive: ineligible if the DECLARED tier is deep OR the SUT's
# OBSERVED JAVA_TOOL_OPTIONS actually carries tier-2 instrumentation — the safe
# direction is to over-exclude, never to contaminate the baseline.
BASELINE_ELIGIBLE="true"
if [ "$PERF_JVM_DIAGNOSTICS" = "deep" ] \
   || printf '%s' "$JAVA_TOOL_OPTS_VAL" | grep -q 'StartFlightRecording\|NativeMemoryTracking'; then
  BASELINE_ELIGIBLE="false"
  echo "--- baseline eligibility: NOT eligible (PERF_JVM_DIAGNOSTICS=$PERF_JVM_DIAGNOSTICS) — this run will be recorded but NOT persisted to the baseline"
fi
# Same reasoning for a TUNED server: a valid measurement, so green, but the baseline
# series tracks the shipped default and a tuned point would raise its rolling median.
if [ "$CONFIG_PROFILE" != "default" ]; then
  BASELINE_ELIGIBLE="false"
  echo "--- baseline eligibility: NOT eligible (config_profile=$CONFIG_PROFILE, soBacklog=$SO_BACKLOG_VAL) — a tuned run is recorded but NOT persisted to the default-configuration baseline"
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
if ! printf '%s' "$PERF_MAX_IMAGE_AGE_DAYS" | grep -qE '^[0-9]+$' || [ "$PERF_MAX_IMAGE_AGE_DAYS" -le 0 ] 2>/dev/null; then
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
    # STRICTLY older than the bound is stale: an image built exactly PERF_MAX_IMAGE_AGE_DAYS
    # whole days ago is still within tolerance (a factory that is merely slow, not frozen).
    if [ "$IMAGE_AGE_DAYS" -gt "$PERF_MAX_IMAGE_AGE_DAYS" ]; then
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

CONFIG_JSON="$(jq -n \
  --arg version "$MS_VERSION" --arg git_hash "$MS_GIT_HASH" \
  --arg image "$MOCKSERVER_IMAGE" --arg image_digest "$IMAGE_DIGEST" \
  --arg image_revision "$IMAGE_REVISION" --arg commit_under_test "$ATTRIBUTED_COMMIT" --arg cut_src "$ATTRIBUTED_SRC" \
  --arg harness_commit "$HARNESS_COMMIT" \
  --arg image_created "$IMAGE_CREATED" --arg image_age_days "$IMAGE_AGE_DAYS" \
  --argjson image_max_age_days "$IMAGE_MAX_AGE_JSON" --arg image_stale "$IMAGE_STALE" \
  --arg provenance_ok "$PROVENANCE_OK" --arg provenance_grace_until "$PROVENANCE_GRACE_UNTIL" \
  --arg jvm_diagnostics "$PERF_JVM_DIAGNOSTICS" \
  --arg jdk "$JDK_BUILD" --arg java_vendor "$JAVA_VENDOR" --arg vm_name "$VM_NAME" \
  --arg gc "$GC_IN_USE" --arg heap_max "$HEAP_MAX_BYTES" \
  --arg log_level "$LOG_LEVEL_VAL" --arg log_level_src "$LOG_LEVEL_SRC" \
  --arg disable_sysout "$DISABLE_SYSOUT_VAL" --arg disable_sysout_src "$DISABLE_SYSOUT_SRC" \
  --arg max_event_log "$MAX_EVENT_LOG_VAL" \
  --arg so_backlog "$SO_BACKLOG_VAL" --arg config_profile "$CONFIG_PROFILE" \
  --arg jto "$JAVA_TOOL_OPTS_VAL" --arg psjo "${PERF_SERVER_JAVA_OPTS:-}" \
  --arg k6_image "$K6_IMAGE" --arg k6_digest "$K6_IMAGE_DIGEST" \
  --arg server_cpus "${SERVER_CPUS:-none}" --arg upstream_cpus "${UPSTREAM_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
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
    # reds (and the abbrev can resolve to shared-repo HEAD in a linked worktree build).
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
    image_stale: ($image_stale=="true"),
    # Diagnostics tier this run was measured under. tier 1 ("standard") is inert on a
    # healthy path; "deep" adds throughput-costing tier-2 instrumentation, which is why
    # a deep run is baseline_eligible:false (recorded, never persisted) at the top level.
    jvm_diagnostics: $jvm_diagnostics,
    jdk: $jdk, java_vendor: $java_vendor, vm_name: $vm_name,
    gc: $gc,
    heap_max_bytes: ($heap_max|tonumber),
    log_level: $log_level,
    disable_system_out: $disable_sysout,
    max_event_log_size_bytes: ($max_event_log|tonumber),
    # null = shipped default in force; a number = this run was tuned.
    so_backlog: (if $so_backlog=="" then null else ($so_backlog|tonumber) end),
    config_profile: $config_profile,
    java_tool_options: $jto,
    perf_server_java_opts: $psjo,
    k6_image: $k6_image,
    k6_image_digest: (if $k6_digest=="" then null else $k6_digest end),
    cpusets: { server: $server_cpus, upstream: $upstream_cpus, k6: $k6_cpus },
    k6_cpu_pin_pct: $k6_pin_pct,
    sources: {
      mockserver_version:"observed", mockserver_git_hash:"observed",
      image_digest:"observed",
      image_revision:"observed", commit_under_test:$cut_src, harness_commit:"declared", provenance_ok:"observed",
      image_created:"observed", image_age_days:"observed", image_max_age_days:"declared", image_stale:"observed",
      jvm_diagnostics:"declared",
      jdk:"observed", java_vendor:"observed", vm_name:"observed",
      gc:"observed", heap_max_bytes:"observed",
      log_level:$log_level_src, disable_system_out:$disable_sysout_src,
      max_event_log_size_bytes:"container-env",
      so_backlog:"container-env", config_profile:"declared",
      java_tool_options:"observed", perf_server_java_opts:"declared",
      k6_image_digest:"observed", cpusets:"declared", k6_cpu_pin_pct:"declared"
    }
  }')"
echo "--- config resolved: ${MS_VERSION} gc='${GC_IN_USE}' heap_max=${HEAP_MAX_BYTES} jdk='${JDK_BUILD}' log_level=${LOG_LEVEL_VAL} maxEventLogSizeInBytes=${MAX_EVENT_LOG_VAL} soBacklog=${SO_BACKLOG_VAL:-<shipped default>} config_profile=${CONFIG_PROFILE} provenance_ok=${PROVENANCE_OK} attributed=${ATTRIBUTED_COMMIT:0:10}(${ATTRIBUTED_SRC}) harness=${HARNESS_COMMIT:0:10} image_age_days=${IMAGE_AGE_DAYS} image_stale=${IMAGE_STALE} jvm_diagnostics=${PERF_JVM_DIAGNOSTICS} baseline_eligible=${BASELINE_ELIGIBLE} (schema_version=3)"

echo "--- seeding upstream /simple (forward target)"
docker run --rm --network "$NETWORK" curlimages/curl:8.11.1 -s -X PUT \
  "http://${UPSTREAM}:1080/mockserver/expectation" -H 'Content-Type: application/json' \
  -d '[{"httpRequest":{"path":"/simple"},"httpResponse":{"statusCode":200,"body":"upstream"},"times":{"unlimited":true}}]' \
  -o /dev/null -w 'upstream seed HTTP %{http_code}\n'

run_regression() {
  local proto="$1" base_url="$2" insecure="$3" out="$4"
  echo "--- regression.js ($proto)"
  # shellcheck disable=SC2046
  docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=$base_url" \
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
  echo "ts,elapsed_s,container_mem_bytes,container_mem_limit_bytes,cpu_pct,heap_used_bytes,heap_max_bytes,nonheap_used_bytes,gc_seconds,gc_count,threads,dropped_log_events,ring_occupancy,ring_capacity,in_flight_bytes,max_in_flight_bytes,retained_entries,retained_bytes,max_retained_bytes,max_retained_entries,evicted_log_entries,req_dur_count,req_dur_sum,req_dur_le_5ms,req_dur_le_10ms,req_dur_le_25ms,req_dur_le_50ms,req_dur_le_100ms" > "$DIAG_SAMPLE_LOG"
  while true; do
    local ts stats cpu memu meml metrics heap heapmax nonheap gc gcc threads dropped occ cap inflt maxinflt retent retbytes maxretbytes maxretent
    local evicted hist rq_count rq_sum rq_le5 rq_le10 rq_le25 rq_le50 rq_le100
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
    metrics="$(curl -s --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || echo '')"
    heap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}')"
    heapmax="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_max_bytes\{area="heap"\}/{print $2}')"
    nonheap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="nonheap"\}/{print $2}')"
    gc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_seconds_sum/{s+=$2} END{print s}')"
    gcc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_count/{print $2}')"
    threads="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_threads_current/{print $2}')"
    dropped="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_dropped_log_events_total/{print $2}')"
    occ="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_ring_occupancy/{print $2}')"
    cap="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_ring_capacity/{print $2}')"
    inflt="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_in_flight_bytes/{print $2}')"
    maxinflt="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_max_in_flight_bytes/{print $2}')"
    retent="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_retained_entries/{print $2}')"
    retbytes="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_retained_bytes/{print $2}')"
    maxretbytes="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_max_retained_bytes/{print $2}')"
    maxretent="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_event_log_max_retained_entries/{print $2}')"
    evicted="$(printf '%s' "$metrics" | awk -F' ' '/^mock_server_evicted_log_entries_total/{print $2}')"
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
      END{print c"|"s"|"b5"|"b10"|"b25"|"b50"|"b100}')"
    IFS='|' read -r rq_count rq_sum rq_le5 rq_le10 rq_le25 rq_le50 rq_le100 <<<"$hist"
    printf '%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n' \
      "$ts" "$((ts - t0))" \
      "$([ -n "$memu" ] && to_bytes "$memu" || echo '')" \
      "$([ -n "$meml" ] && to_bytes "$meml" || echo '')" \
      "${cpu:-}" "${heap:-}" "${heapmax:-}" "${nonheap:-}" "${gc:-}" "${gcc:-}" "${threads:-}" \
      "${dropped:-}" "${occ:-}" "${cap:-}" "${inflt:-}" "${maxinflt:-}" \
      "${retent:-}" "${retbytes:-}" "${maxretbytes:-}" "${maxretent:-}" \
      "${evicted:-}" "${rq_count:-}" "${rq_sum:-}" \
      "${rq_le5:-}" "${rq_le10:-}" "${rq_le25:-}" "${rq_le50:-}" "${rq_le100:-}" >> "$DIAG_SAMPLE_LOG"
    sleep "$PERF_DIAG_SAMPLE_INTERVAL"
  done
}
echo "--- starting dense resource-trajectory sampler (every ${PERF_DIAG_SAMPLE_INTERVAL}s -> diag-samples.csv)"
diag_sampler & DIAG_SAMPLER_PID=$!

run_regression "http" "http://${SERVER_ALIAS}:1080" "false" "regression-http.json"
run_regression "https_h2" "https://${SERVER_ALIAS}:1080" "true" "regression-https.json"
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
SWEEP_RATES="${K6_SWEEP_RATES:-500,1000,2000,4000,8000,16000,32000,48000,64000}"
SWEEP_STEP="${K6_SWEEP_STEP:-15s}"
SWEEP_GAP="${K6_SWEEP_GAP:-5s}"
SWEEP_CPU_LOG="$OUT_DIR/sweep-k6-cpu.csv"

# Ladder schedule + client-pin constants, derived ONCE and reused by every sweep
# (the ERROR baseline below AND the INFO publication arm). They depend only on the
# static ladder/pin config, never on a sweep's output, so hoisting them here keeps
# the two sweeps' saturation derivation identical (a fair ERROR-vs-INFO comparison
# requires the SAME knee methodology). SETTLE_S / err-eps are the same tunables the
# derivation used inline before this was factored out.
STEP_S="$(to_secs "$SWEEP_STEP")"
GAP_S="$(to_secs "$SWEEP_GAP")"
SETTLE_S="${PERF_SWEEP_SETTLE_S:-3}"
K6_CORES="$(k6_core_count "$K6_CPUS")"
K6_PIN_PCT=$((K6_CORES * 100))
SWEEP_ERR_EPS="${PERF_SWEEP_ERROR_EPS:-0.01}"
# Fractional drop TOLERANCE for rig-validity (see derive_saturation). A rung's
# dropped_iterations are forgiven only when they are BELOW this fraction of the
# rung's intended iterations AND the rung's VU pool had headroom — never on the
# fraction alone. 0.01 = 1% is DERIVED, not picked: it sits above the largest
# recorded blip (build #322's 2,000 rung, 133 drops = 0.44%, client 13.6% CPU) with
# >2x margin and below the smallest recorded genuine shortfall (build #347's 8,000
# rung at 1.83%; native 16k at 2.5%; build #290 16k at 3.3%) with >1.8x margin, and
# it means a rig-valid rung delivered >=99% of offered — 5x tighter than the 0.95
# "clean" (knee) rule, so it can never admit a rung the knee logic would call short.
# NOTE the figures above were recorded under the OLD ramping pool (preAllocatedVUs
# 200 -> maxVUs 4000); the fixed per-rung pool landed later in fa18ac3de. That shift
# only makes this filter MORE conservative - under the new pools build #347's 8,000
# rung HAS headroom (peak 301 < pool 640), so it is the FRACTION term that correctly
# excludes it at 1.83%, which is exactly why this threshold must stay below that.
SWEEP_DROP_TOL="${PERF_SWEEP_DROP_TOL:-0.01}"

# Background sampler of the k6 CLIENT container's CPU while a sweep runs — the
# missing "was the client the bottleneck?" evidence. A rung where k6 is near its
# own CPU pin is a CLIENT ceiling, not MockServer's, and is excluded by
# derive_saturation. Parameterised by container name + output CSV so the ERROR and
# INFO sweeps each get their own sampler against their own k6 container.
sweep_cpu_sampler() { # k6_container_name  out_csv
  local cname="$1" out_csv="$2"
  echo "ts,cpu_pct" > "$out_csv"
  while true; do
    local ts cpu
    ts="$(date -u +%s)"
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$cname" 2>/dev/null | tr -d '% ' || echo '')"
    [ -n "$cpu" ] && printf '%s,%s\n' "$ts" "$cpu" >> "$out_csv"
    sleep "${PERF_SWEEP_SAMPLE_INTERVAL:-3}"
  done
}

# Run one throughput-vs-latency sweep against $target_alias, writing k6's result to
# $out_json and the client-CPU trace to $cpu_log. Records the sweep's start epoch in
# the global LAST_SWEEP_T0 (NOT echoed — the docker run's own stdout would pollute a
# captured value) so derive_saturation can line each rung up with its hold window.
# Registers the sampler PID in SWEEP_SAMPLER_PID so cleanup() reaps it on an early
# exit, and clears it once reaped.
run_sweep() { # k6_container_name  target_alias  out_json_host_path  cpu_log_host_path
  local k6name="$1" target_alias="$2" out_json="$3" cpu_log="$4"
  LAST_SWEEP_T0="$(date -u +%s)"
  sweep_cpu_sampler "$k6name" "$cpu_log" & SWEEP_SAMPLER_PID=$!
  # Register this ladder so the liveness gate can stop it the instant the SUT dies.
  printf '%s' "$k6name" > "$ACTIVE_LOAD_FILE" 2>/dev/null || true
  local sweep_rc=0
  # shellcheck disable=SC2046
  docker run --rm --name "$k6name" --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "BASE_URL=http://${target_alias}:1080" \
    -e "PROTO=http" \
    -e "K6_SWEEP_RATES=$SWEEP_RATES" \
    -e "K6_SWEEP_STEP=$SWEEP_STEP" \
    -e "K6_SWEEP_GAP=$SWEEP_GAP" \
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

# --- derive saturation_rps from a sweep (item: prove the client had headroom) ---
# Per rung: attribute the max k6-container CPU seen during that rung's hold window
# (from the sampler log + the known ladder schedule anchored at $t0), pair it with
# k6's per-rung dropped_iterations, and mark the rung CLEAN iff achieved >=
# 0.95*offered AND the client had CPU headroom (< 85% of its pin) AND k6 dropped no
# iterations. The highest CLEAN rung's offered rate is saturation_rps. If nothing is
# clean, the client was the bottleneck everywhere (the caller flags the run invalid).
# Echoes the SATURATION_JSON object on stdout.
derive_saturation() { # sweep_json_host_path  cpu_log_host_path  t0_epoch
  local sweep_json="$1" cpu_log="$2" t0="$3"
  local cpu_map="{}" i r ws we maxcpu
  local -a rate_arr
  IFS=',' read -ra rate_arr <<< "$SWEEP_RATES"
  for i in "${!rate_arr[@]}"; do
    r="${rate_arr[$i]}"
    ws=$(( t0 + i * (STEP_S + GAP_S) + SETTLE_S ))
    we=$(( t0 + i * (STEP_S + GAP_S) + STEP_S ))
    maxcpu="$(awk -F',' -v a="$ws" -v b="$we" 'NR>1 && $1>=a && $1<=b { if($2+0>m) m=$2+0 } END{ printf "%.1f", m+0 }' "$cpu_log" 2>/dev/null || echo 0)"
    cpu_map="$(jq -c --arg k "$r" --argjson v "${maxcpu:-0}" '. + {($k): $v}' <<<"$cpu_map")"
  done
  # A rung is RIG-VALID when the measurement itself is trustworthy: the k6 client
  # had CPU headroom, dropped no more than a small tolerated FRACTION of iterations
  # (and only when its VU pool had headroom — see the $no_drops derivation below),
  # and the server was not returning fast errors (a rung "achieves" its offered rate
  # even while erroring, so error_rate is part of validity, not just throughput).
  # The BUDGETED metric is rig_valid_peak_achieved_rps = max achieved over rig-valid rungs.
  #
  # rig_valid_peak_achieved_rps IS A PROPERTY OF THE RIG, NOT OF THE SERVER. An earlier version of
  # this comment called it "CONTINUOUS (it moves proportionally with the real ceiling,
  # e.g. 36,324 achieved at 48,000 offered)". The data falsifies that: in build 325 the
  # server achieved 28,377.4 at 48,000 offered with zero errors, and this field still read
  # 2,000.1 — the same value build 306 reported for a different run whose server peak also
  # differed. It CANNOT track the server ceiling, because the rig-validity filter
  # structurally caps it at whichever rung the CLIENT stops being clean, and on this rig k6
  # starts dropping iterations at the 4,000 rung. Two runs agreeing on "peak achieved" while
  # their servers disagree is the tell. A documented rationale contradicted by shipped
  # behaviour is worse than none, because it tells the next reader not to check.
  #
  # BEWARE: two different quantities share this name. THIS one is the rig-valid peak
  # (2,000.1). lib/perf-website-figures.jq independently recomputes max achieved over ALL
  # rungs (28,377.4) and that is what is published to the website. Same name, different
  # subject, different consumer — do not reconcile one against the other.
  #
  # saturation_rps is ladder-QUANTISED (only ever a rung's offered value, 16k/32k/... — its
  # smallest move is a factor of two, so it cannot carry a percentage floor) and is kept as
  # a DESCRIPTIVE figure only (the knee), not budgeted.
  jq -n \
    --slurpfile sweep "$sweep_json" \
    --argjson cpu "$cpu_map" \
    --argjson pin "$K6_PIN_PCT" \
    --argjson cores "$K6_CORES" \
    --argjson err_eps "$SWEEP_ERR_EPS" \
    --argjson drop_tol "$SWEEP_DROP_TOL" '
    ($pin * 0.85) as $cpu_ceiling
    | (($sweep[0].points) // []) as $points
    | (($sweep[0].vus_diagnostics.pool_per_rung) // {}) as $pools
    | [ $points[]
        | ($cpu[(.offered_rps|tostring)]) as $c
        | (.dropped_iterations // 0) as $drops
        | (.sample_count // 0) as $completed
        | (.error_rate // 0) as $err
        | (.offered_rps) as $off | (.achieved_rps // 0) as $ach
        | (.vus_active_max) as $vmax
        | (.vus_active_p95) as $vp95
        | ($pools[($off|tostring)]) as $pool
        | (($cores <= 0) or ($c == null) or ($c <= $cpu_ceiling)) as $headroom
        # Drop fraction = drops / (drops + completed). A dropped iteration is one the
        # constant-arrival-rate executor could not launch because no VU was free, so
        # (drops + completed) is the intended iteration count and this is the exact
        # fraction of the offered load the client failed to deliver.
        | (if ($drops + $completed) > 0 then ($drops / ($drops + $completed)) else 0 end) as $drop_frac
        # VU-pool headroom, keyed off the p95 concurrency rather than the max. vus_active_max
        # is RIGHT-CENSORED: it cannot exceed the pool, so a single stall pileup touching the
        # ceiling makes any rung read as client-limited however idle the pool actually was -
        # build 419 excluded a rung whose p95 was 9 against a pool of 640. The p95 answers the
        # question actually being asked: was the pool the constraint for the bulk of the rung.
        # Falls back to the max when p95 is absent (older artifact), never the other way.
        | (if ($vp95 != null) and ($pool != null) then ($vp95 < $pool)
           elif ($vmax != null) and ($pool != null) then ($vmax < $pool)
           else false end) as $vu_headroom
        # $no_drops (the rig-validity drop clause): forgive drops ONLY when they are a
        # small FRACTION *and* the VU pool had headroom. A tolerance on the fraction
        # ALONE would admit a genuinely VU-starved rung whose achieved rate is a
        # CLIENT ceiling (the exact dishonesty rig_valid exists to prevent), so the
        # corroborating headroom signal is required, not optional. When either signal
        # is unavailable (an older artifact with no vus_active_max, or no pool_per_rung
        # recorded) fall back to the strict zero-drop rule — never lenient on drops we
        # cannot corroborate. This voids build #322 no longer: its 2,000 rung dropped
        # 133 of ~30,000 (0.44% < 1%) with the pool barely touched, a blip; but build
        # #347s 8,000 rung (1.83%) and every high-rung collapse stay excluded on the
        # fraction, and any rung that reached its pool ceiling stays excluded on the
        # headroom term even below tolerance.
        | (if $drops <= 0 then true
           elif $vu_headroom then ($drop_frac <= $drop_tol)
           else false end) as $no_drops
        | ($err <= $err_eps) as $low_err
        # rig_valid: the measurement itself is trustworthy (says nothing about the
        # server verdict). clean: rig_valid AND the server actually kept up (knee).
        | ($headroom and $no_drops and $low_err) as $rig_valid
        | ($rig_valid and ($off > 0) and ($ach >= 0.95 * $off)) as $clean
        | { offered_rps:$off, achieved_rps:$ach, k6_cpu_pct:$c,
            dropped_iterations:$drops, dropped_fraction:($drop_frac|.*100000|round/100000),
            vus_active_max:$vmax, vus_active_p95:$vp95, pool_per_rung:$pool, error_rate:$err,
            rig_valid:$rig_valid, clean:$clean,
            exclude_reason:(
              if $rig_valid then null
              elif ($headroom|not) then "k6 client CPU \($c)% >= 85% of \($pin)% pin (client bottleneck)"
              elif ($no_drops|not) then
                "k6 dropped \($drops) iterations = \(($drop_frac*1000|round)/10)% of offered"
                + (if $vu_headroom then " (> \(($drop_tol*100))% tolerance despite VU-pool headroom - too sustained to be a blip)"
                   elif (($vp95 != null) and ($pool != null)) then " with VU pool exhausted (vus_active_p95 \($vp95) >= pool \($pool), client-limited)"
                   elif (($vmax != null) and ($pool != null)) then " with VU pool exhausted (vus_active_max \($vmax) >= pool \($pool), client-limited)"
                   else " (no VU-pool diagnostics to corroborate a blip; strict zero-drop applied)" end)
              else "server error_rate \($err) > \($err_eps) (fast errors inflate achieved)" end) } ]
    | . as $rungs
    | ([ $rungs[] | select(.rig_valid) | .achieved_rps ] | max // 0) as $peak
    | ([ $rungs[] | select(.clean) | .offered_rps ] | max // 0) as $sat
    | ([ $rungs[] | select(.rig_valid) ] | length) as $rig_valid_rungs
    | { rig_valid_peak_achieved_rps:$peak, saturation_rps:$sat,
        rig_valid_rungs:$rig_valid_rungs,
        client_pin_pct:$pin, client_cores:$cores,
        ladder:$rungs,
        excluded:[ $rungs[] | select(.rig_valid|not)
                   | {offered_rps, achieved_rps, k6_cpu_pct, dropped_iterations, dropped_fraction, vus_active_max, vus_active_p95, pool_per_rung, error_rate, reason:.exclude_reason} ] }'
}

echo "--- sweep.js (throughput-vs-latency knee curve; ladder=$SWEEP_RATES)"
run_sweep "$SWEEP_K6" "$SERVER_ALIAS" "$OUT_DIR/sweep.json" "$SWEEP_CPU_LOG"
abort_if_sut_died
SWEEP_T0="$LAST_SWEEP_T0"
SATURATION_JSON="$(derive_saturation "$OUT_DIR/sweep.json" "$SWEEP_CPU_LOG" "$SWEEP_T0")"
PEAK_ACHIEVED_RPS="$(jq -r '.rig_valid_peak_achieved_rps' <<<"$SATURATION_JSON")"
SATURATION_RPS="$(jq -r '.saturation_rps' <<<"$SATURATION_JSON")"
echo "--- rig_valid_peak_achieved_rps=$PEAK_ACHIEVED_RPS saturation_rps=$SATURATION_RPS (client pin=${K6_PIN_PCT}%, cores=$K6_CORES)"

# Validity: at least one rung was measured with the client sound (headroom, no
# drops, low errors). If EVERY rung was excluded the rig was compromised
# throughout and no server figure is trustworthy. PROVOCATION (observed false):
# a synthetic ladder with the k6 CPU above 85% of pin at every rung yields
# rig_valid_rungs=0 and this check false (see the item's can-it-fail evidence).
#
# KEYED OFF THE RUNG COUNT, NOT off rig_valid_peak_achieved_rps > 0. Those are not the same
# question. A rung can be rig-valid and still achieve 0 rps (a server that is down
# rather than a client that is compromised), and the old form reported that as "every
# rung excluded" — blaming the rig for a server failure. The count asks what the check
# is actually named for: did ANY rung measure cleanly?
#
# The failure message now REPORTS the per-rung exclude_reason rather than asserting a
# cause. The old text named "client CPU-pinned / VU-starved / erroring" unconditionally,
# and that mis-diagnosed build #322: its 2,000 rung dropped 133 iterations (~0.4%), the
# zero-tolerance no_drops clause excluded every rung, and the build failed pointing at
# client CPU that was in fact 13.6% utilised. Each rung already carries the real reason;
# print it instead of guessing.
RIG_VALID_RUNGS="$(jq -r '.rig_valid_rungs // 0' <<<"$SATURATION_JSON")"
if awk -v v="$RIG_VALID_RUNGS" 'BEGIN{exit !(v+0>0)}'; then
  add_check "sweep_client_had_headroom" true "${RIG_VALID_RUNGS} rung(s) measured with client headroom, no dropped iterations, low errors (rig_valid_peak_achieved_rps=${PEAK_ACHIEVED_RPS})"
else
  SWEEP_EXCLUSIONS="$(jq -r '[.ladder[] | "\(.offered_rps): \(.exclude_reason // "?")"] | join("; ")' <<<"$SATURATION_JSON")"
  add_check "sweep_client_had_headroom" false "every sweep rung was excluded, so no server throughput figure is trustworthy. Per-rung reasons — ${SWEEP_EXCLUSIONS}"
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
if [ "$PERF_INFO_ARM" = "true" ]; then
  INFO_ARM_ATTEMPTED=true
  INFO_T_START="$(date -u +%s)"
  echo "--- INFO-log-level arm: starting SUT ($MOCKSERVER_IMAGE, MOCKSERVER_LOG_LEVEL=INFO, pinned to $SERVER_CPUS)"
  INFO_MEASURED=false
  # publish="" (no host port needed — log level is read via docker inspect, not the
  # metrics endpoint); same memory bound + file-body mount + image as the ERROR SUT
  # so the JS-template and file-body arms behave identically. Guarded with `|| true`
  # so a docker-run failure here degrades the (notify-only) INFO arm rather than
  # aborting the ERROR-baseline run under `set -e`; a failed start leaves no
  # container, so wait_ready then reports not-ready and the arm records measured:false.
  start_mockserver "$INFO_SERVER" "$SERVER_CPUS" "$INFO_SERVER_ALIAS" "" "$SERVER_MEMORY" "$FILE_BODY_MOUNT" "INFO" "info" \
    && { docker logs -f "$INFO_SERVER" > "$DIAG_DIR/info/info-server.log" 2>&1 & INFO_LOG_PID=$!; } \
    || echo "WARNING: INFO SUT failed to start — INFO arm will record measured:false" >&2
  if wait_ready "$INFO_SERVER"; then
    INFO_MEASURED=true
    # Per-behaviour percentiles at INFO (http + https_h2), same durations/arms as the
    # ERROR regression so the two are comparable. Guarded: a k6 failure degrades this
    # arm to measured:false rather than aborting the (ERROR-baseline) run.
    run_regression "http"     "http://${INFO_SERVER_ALIAS}:1080"  "false" "info-regression-http.json"  || { echo "WARNING: INFO regression http failed — INFO arm degraded" >&2; INFO_MEASURED=false; }
    run_regression "https_h2" "https://${INFO_SERVER_ALIAS}:1080" "true"  "info-regression-https.json" || { echo "WARNING: INFO regression https_h2 failed — INFO arm degraded" >&2; INFO_MEASURED=false; }
    # Knee curve at INFO (same ladder + saturation methodology as the ERROR sweep).
    echo "--- INFO-log-level arm: sweep.js (knee curve at INFO)"
    run_sweep "$INFO_SWEEP_K6" "$INFO_SERVER_ALIAS" "$OUT_DIR/info-sweep.json" "$OUT_DIR/info-sweep-k6-cpu.csv" \
      || { echo "WARNING: INFO sweep failed — INFO knee degraded" >&2; INFO_MEASURED=false; }
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

  # Merge the http + https_h2 behaviours (guarded to {} on any missing/unparsable
  # file), derive the INFO saturation with the SHARED derive_saturation (identical
  # knee methodology to the ERROR arm), then assemble the self-describing block.
  INFO_BEHAVIOURS="$(jq -sc '(.[0].behaviours // {}) + (.[1].behaviours // {})' \
    "$OUT_DIR/info-regression-http.json" "$OUT_DIR/info-regression-https.json" 2>/dev/null || echo '{}')"
  jq -e . >/dev/null 2>&1 <<<"$INFO_BEHAVIOURS" || INFO_BEHAVIOURS='{}'
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
        cpusets: { server: $server_cpus, k6: $k6_cpus }
      },
      # Per-behaviour percentiles (http + https_h2 merged), SAME shape as the ERROR
      # .behaviours but under a distinct key so it is never fingerprint-matched or
      # diffed against the ERROR series.
      behaviours: $behaviours,
      # The knee curve at INFO + its derived saturation (rig_valid_peak_achieved_rps here
      # is the rig-valid peak — max achieved over rig-valid rungs, a property of the k6 rig,
      # see derive_saturation; saturation_rps is the ladder-quantised knee).
      sweep: { proto: ($sweep.proto // "http"), points: ($sweep.points // []) },
      saturation: $saturation,
      rig_valid_peak_achieved_rps: ($saturation.rig_valid_peak_achieved_rps // null),
      saturation_rps: ($saturation.saturation_rps // null)
    }')"
  INFO_PEAK="$(jq -r '.rig_valid_peak_achieved_rps // "n/a"' <<<"$INFO_ARM_JSON")"
  INFO_SAT="$(jq -r '.saturation_rps // "n/a"' <<<"$INFO_ARM_JSON")"
  INFO_T_END="$(date -u +%s)"
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
    ts="$(date -u +%s)"
    cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$SERVER" 2>/dev/null | tr -d '% ' || echo '')"
    metrics="$(curl -s --max-time 4 "$SERVER_METRICS_URL" 2>/dev/null || echo '')"
    heap="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_memory_used_bytes\{area="heap"\}/{print $2}')"
    gc="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_gc_collection_seconds_sum/{s+=$2} END{print s}')"
    threads="$(printf '%s' "$metrics" | awk -F' ' '/^jvm_threads_current/{print $2}')"
    printf '%s,%s,%s,%s,%s\n' "$ts" "${cpu:-}" "${heap:-}" "${gc:-}" "${threads:-}" >> "$SAMPLE_LOG"
    sleep "$SAMPLE_INTERVAL"
  done
}

abort_if_sut_died
echo "--- growth.js (sustained load + resource sampling)"
sampler & SAMPLER_PID=$!
# shellcheck disable=SC2046
docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "K6_GROWTH_RESULT_PATH=/out/growth.json" \
  ${K6_GROWTH_DURATION:+-e K6_GROWTH_DURATION="$K6_GROWTH_DURATION"} \
  ${K6_GROWTH_RATE:+-e K6_GROWTH_RATE="$K6_GROWTH_RATE"} \
  ${K6_GROWTH_PROBE:+-e K6_GROWTH_PROBE="$K6_GROWTH_PROBE"} \
  "$K6_IMAGE" run /k6/growth.js
kill "$SAMPLER_PID" >/dev/null 2>&1 || true; SAMPLER_PID=""

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
echo "--- forward.js (forward connection-pool regression guard)"
FORWARD_EXIT=0
# shellcheck disable=SC2046
docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
  -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
  -v "$OUT_DIR:/out" \
  -e "BASE_URL=http://${SERVER_ALIAS}:1080" \
  -e "FORWARD_UPSTREAM_HOST=mockserver-upstream:1080" \
  -e "K6_FORWARD_RESULT_PATH=/out/forward.json" \
  ${K6_FWD_PEAK_RATE:+-e K6_FWD_PEAK_RATE="$K6_FWD_PEAK_RATE"} \
  ${K6_FWD_HOLD:+-e K6_FWD_HOLD="$K6_FWD_HOLD"} \
  "$K6_IMAGE" run /k6/forward.js || FORWARD_EXIT=$?
if [ "$FORWARD_EXIT" -ne 0 ]; then
  echo "WARNING: forward.js exited $FORWARD_EXIT — forward-pool guard threshold TRIPPED (error rate over limit)" >&2
fi
FORWARD_JSON="$(cat "$OUT_DIR/forward.json" 2>/dev/null || echo '{}')"
abort_if_sut_died

# --- proxy.js: item 9a (forward proxy) + item 14 (TLS/mTLS handshake) ----------
# ONE run, TWO phases (the plan: item 14 shares item 9a's containers and run):
#   9a  forward mode — MockServer AS A PROXY. The k6 container gets HTTP_PROXY /
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
#   14  handshake mode — MockServer's INBOUND TLS handshake cost, which the
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
  # -- 9a: forward-proxy latency arms (behaviours) --
  echo "--- proxy.js (item 9a: forward-proxy — absolute-URI + CONNECT tunnel)"
  # shellcheck disable=SC2046
  docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
    -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" \
    -v "$OUT_DIR:/out" \
    -e "K6_PROXY_MODE=forward" \
    -e "HTTP_PROXY=http://${SERVER_ALIAS}:1080" -e "HTTPS_PROXY=http://${SERVER_ALIAS}:1080" \
    -e "http_proxy=http://${SERVER_ALIAS}:1080" -e "https_proxy=http://${SERVER_ALIAS}:1080" \
    -e "NO_PROXY=" -e "no_proxy=" \
    -e "FORWARD_UPSTREAM_HOST=mockserver-upstream:1080" \
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
        -e JAVA_TOOL_OPTIONS="-Dio.netty.handler.ssl.noOpenSsl=true" \
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
      if printf '%s' "${MTLS_NOCERT_CODE:-000}" | grep -qE '^2..$'; then
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
          local ts; ts="$(date -u +%s)"
          for arm in tls13 mtls jdk; do
            local nm cpu
            case "$arm" in tls13) nm="$SERVER";; mtls) nm="$MTLS";; jdk) nm="$JDK_SUT";; esac
            cpu="$(docker stats --no-stream --format '{{.CPUPerc}}' "$nm" 2>/dev/null | tr -d '% ' || echo '')"
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
# --- item 8: laptop startup + footprint profile (notify-only) -----------------
# Runs LAST, after every k6 phase, so the box is quiet: the docker sub-items (8a
# ready-median-of-9, idle RSS + threads at --memory 256m/512m/1g, 8d compressed
# image size) launch their own throwaway containers and need only docker + the SUT
# image. The in-JVM sub-items (8b/8c) — the number a MockServerExtension suite
# actually pays per test class, and init-file scaling — need a shaded jar and a
# JDK; the jar is docker-cp'd out of the SUT image (path fixed by the Dockerfile)
# and bench_laptop.py SKIPS 8b/8c loudly if a jar or `java` is unavailable (e.g. a
# JRE-only agent). Emits a `laptop` block merged into result.json; every metric is
# notify-only (laptop.*.<metric> budgets omit `gating`). NON-FATAL by construction:
# the SUT ($SERVER) is still running-but-idle here, so its per-container cgroup RSS
# never pollutes a laptop container's own `docker stats` reading, and a laptop
# measurement failure must never lose the k6/growth result the run exists for.
LAPTOP_JSON='{}'
# Record INTENT separately from success (the 15b two-axis split): `laptop_attempted`
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
      docker cp "$_lc:/mockserver-netty-jar-with-dependencies.jar" \
        "$OUT_DIR/mockserver.jar" >/dev/null 2>&1 && LAPTOP_JAR="$OUT_DIR/mockserver.jar"
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

# --- item 17: N parallel instances on one host (laptop / MockServerExtension profile) ---
# OPT-IN (PERF_LAPTOP_PARALLEL=true), OFF by default: this spins {1,4,8,16,32} MockServer
# instances twice (an in-JVM shape and a container shape), so it is a deliberately-scheduled
# research profile, NOT added to every daily run — the serving_percore opt-in pattern. Every
# metric it emits is NOTIFY-ONLY (the laptop.*.<metric> budgets omit `gating`), so a flagged
# regression annotates but never fails the build. Merged into result.json under `.laptop_parallel`
# with two sub-shapes {injvm, container}, each a list of per-N runs; perf-test-compare.sh keys them
# laptop_parallel.<shape>_<N>.<metric> against the laptop.* wildcard budgets. NON-FATAL by
# construction (like the item 8 laptop / streaming profiles): the SUT ($SERVER) is idle here, and
# a measurement failure must never cost the k6/growth result its baseline place — on any failure the
# block simply stays {} and compare (head-driven) emits zero laptop_parallel metrics, no
# missing-budget trip. NOT an add_check for the same reason the laptop block is not.
LAPTOP_PARALLEL_JSON='{}'
if [ "${PERF_LAPTOP_PARALLEL:-false}" = "true" ]; then
  echo "--- item 17 parallel-instances profile (opt-in; PERF_LAPTOP_PARALLEL=true)"
  LP_COUNTS="${PERF_LAPTOP_PARALLEL_COUNTS:-1,4,8,16,32}"
  LP_INJVM='{}'
  LP_CONTAINER='{}'
  # in-JVM shape: needs a jar-with-dependencies on the classpath (reuse the one the item 8 block
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
# --- item 12: LLM/SSE streaming under concurrency -----------------------------
# Drive STREAMING.concurrency concurrent SSE streams (streaming.js, constant-vus)
# while measuring the four item-12 metrics. Two of them k6 CANNOT see (it buffers
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
# The other two (match-p95 A/B, stream delivery) come from k6's own result. item
# 12's CallerRunsPolicy counter is NOT emitted: MockServer exposes none, and the
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
    # Default concurrency 1200 (not config.js's light-local 100), at delay 20 ms
    # against the dedicated 1-CPU / 2-scheduler-thread / 2-event-loop-thread SUT
    # below. WHY IT IS NOT 300 ANY MORE, which is the useful part of this comment:
    # 300 WAS past the knee and measured 8.0x and 3.9x — until 3d7a2f9c8 ("stop
    # keeping a parsed copy of every logged JSON body") cut retained heap per log
    # entry by roughly 7x (429 MB -> 61 MB over 20,000 entries; 1,840,013 Jackson
    # tree nodes -> 0). Far less allocation means far less GC competing with the two
    # action-handler threads, which moved the knee well beyond 300: the first
    # post-fix CI run (build 290) read a match A/B ratio of 1.056 at concurrency 300
    # — a control proving nothing. The product got faster and the calibration went
    # stale; that is the ONLY reason this number changed.
    #
    # 600 is measured ON CI, which is what makes it trustworthy. The laptop curve
    # (~2.0 at 300, ~2.6 at 600, ~4.5 at 900, min 6.08 at 1200) led to a default of
    # 1200 via an extrapolation that turned out to be WRONG IN SHAPE: CI was assumed
    # to need MORE concurrency than a laptop for the same ratio, because at 300 CI
    # read 1.056 where the laptop read ~2.0. In fact CI has a far SHARPER knee — the
    # laptop's own contention had flattened its curve. Measured on CI:
    #     300  -> 1.056  (below the knee; the control proved nothing)
    #     600  -> 2.536  (just past it — where the design wants to sit)
    #     1200 -> 93.8   (deep in collapse, ~40x past the knee)
    #
    # THE KNEE IS BOX-DEPENDENT, which is exactly why the CI figure governs: those figures come from a developer laptop, where the same concurrency
    # 300 read ~2.0 against CI's 1.056 on identical code. A 1-CPU quota buys
    # different real throughput on a dedicated CI core than on a contended laptop
    # vCPU, so CI needs MORE concurrency than the laptop for the same ratio. 1200
    # was chosen because even at the worst observed box sensitivity (CI reading
    # about half the laptop at matched concurrency) it still extrapolates to ~3x,
    # clear of the ~1.6x near-knee band; 900 would not survive that.
    #
    # RETENTION at 1200/20ms: start rate ~300/s, ~500/s total, maxLogEntries ~96000
    # => residence ~192 s exceeds the 90 s window, so ~300 x 90 x 200 events x ~30 B
    # ~= 166 MB — still far under the ~768 MB heap of the 1 GB SUT (and no OOM was
    # observed at 1200 in any run).
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
      local n="$1" i
      for i in $(seq 1 "$n"); do
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
    while docker ps --format '{{.Names}}' | grep -q "^${STREAM_K6}$"; do sleep 2; done
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
# --- item 13: clustered state under load (within-run A/B) ---------------------
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
# Maven on the measurement box — a multi-module reactor build on the serialized perf
# agent immediately before measuring would perturb the very thing being measured.
#
# WHAT THE HOT PATH ACTUALLY TOUCHES (why the ratio is what it is): seedRegression
# seeds every expectation with times:{unlimited:true}, so there is NO per-request
# Times CAS (clusterSharedTimesEnabled fires only on a bounded Times). Matching
# reads hit each node's LOCAL compiled-matcher cache (reconciled via invalidation),
# not a network read per request, and the event log is node-local. So the per-
# request network cost for this read/forward/template mix is ~zero; what the ratio
# captures is the STEADY-STATE overhead of running the clustered stack on the
# request path (Infinispan on the classpath, background JGroups FD_ALL3 heartbeats
# + STABLE gossip stealing a little CPU) plus the one-time seed replication. That
# is the honest, useful answer for the central deployment: how far a node's per-
# request latency moves merely by being a clustered member.
#
# RETENTION (the OOM lesson): the count-bounded event-log ring holds FULL bodies,
# residence = maxLogEntries / total_ACHIEVED_rps (LONGER, without bound, as achieved
# throughput falls). k6 drives ONLY the entry node (control, or cluster node A), so
# ONLY that node's ring fills with request bodies — exactly like the main SUT, and it
# gets the SAME PERF_CLUSTERED_MEMORY (2 GB default -> ~1.5 GB heap,
# maxLogEntries=100000). The per-arm rate x residence x body figures the config.js
# arithmetic derives (large_10mb@0.1rps -> ~111 MB, large_1mb@0.5rps -> ~55 MB,
# large-4KB@200rps -> ~89 MB) hold ONLY at residence 111 s; they run away as this node
# contends. So the clustered nodes get the SAME maxEventLogSizeInBytes body-byte OOM
# guard (start_clu, above) as the main SUT, which caps total retained body bytes
# regardless of residence — and applies identically on control and cluster, so it does
# not bias the ratio. The
# SECOND node (B) receives NO request load, so its ring stays ~empty; it holds only
# the seeded expectation set (tiny, seeded once) + bounded JGroups buffers (UFC/MFC
# 2M each, NAKACK2/UNICAST3 send/recv, FRAG2 60K — single-digit MB). REPL_SYNC
# replicates only expectation/scenario WRITES, of which there are ~none after the
# one-time seed, so node B never accumulates bodies. The clustered arm therefore
# adds no retention risk beyond the control's, per node.
#
# BEST-EFFORT / NOTIFY-ONLY like streaming/laptop: a failure defaults the block to
# {} and never costs the k6/growth result its baseline place. BUT — item 8's lesson
# — an empty block must not read as both "off" and "broken". So `clustered_attempted`
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
    CLU_CPUS="${PERF_CLUSTERED_CPUS:-2}"
    CLU_MEM="${PERF_CLUSTERED_MEMORY:-2g}"
    CLU_NAME="perf-cluster-${RUN_ID}"
    # Shorter measured window than the main run (the ratio is robust and both arms
    # are measured identically, so a full 2 m window buys little); still long enough
    # for the small-body arms to clear MIN_TAIL_SAMPLES for a p95 ratio.
    CLU_WARMUP="${K6_CLU_REG_WARMUP:-20s}"
    CLU_DURATION="${K6_CLU_REG_DURATION:-45s}"
    echo "--- clustered A/B: image=$CLU_IMAGE cpus=$CLU_CPUS mem=$CLU_MEM warmup=$CLU_WARMUP duration=$CLU_DURATION"

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
    # carries the maxEventLogSizeInBytes body-byte OOM guard (below), for the same
    # reason the main SUT does: these MB arms run here too, on a 1.5 GB clustered
    # heap. The budget is identical on control and cluster, so it evicts symmetrically
    # and cannot bias the within-run clustered/control ratio item 13 measures.
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

    start_clu() { # name  alias  backend(memory|infinispan)  cluster_name_or_empty
      local name="$1" alias="$2" backend="$3" cname="${4:-}"
      local extra=()
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
        --cpus "$CLU_CPUS" --memory "$CLU_MEM" -p 127.0.0.1::1080 \
        -e MOCKSERVER_LOG_LEVEL=WARN -e MOCKSERVER_DISABLE_SYSTEM_OUT=true \
        -e MOCKSERVER_METRICS_ENABLED=true \
        -e MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES="$PERF_MAX_EVENT_LOG_BYTES" \
        -e "MOCKSERVER_STATE_BACKEND=$backend" \
        ${extra[@]+"${extra[@]}"} \
        "$CLU_IMAGE" -serverPort 1080 >/dev/null 2>&1
    }

    clu_hostport() { docker port "$1" 1080/tcp 2>/dev/null | head -1; }
    clu_members()  { curl -s --max-time 4 "http://$1/mockserver/cluster" 2>/dev/null | jq -r '.memberCount // 0' 2>/dev/null || echo 0; }
    clu_clustered(){ curl -s --max-time 4 "http://$1/mockserver/cluster" 2>/dev/null | jq -r '.clustered // false' 2>/dev/null || echo false; }
    # Run regression.js UNCHANGED against one clustered target. Distinct PROTO so its
    # behaviour keys (<op>_<proto>) do not collide with the main run's.
    run_clu_regression() { # proto  base_url  out
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
        -e "BASE_URL=$2" -e "PROTO=$1" -e "INSECURE_SKIP_TLS_VERIFY=false" \
        -e "K6_RESULT_PATH=/out/$3" \
        -e "K6_REG_JS_TEMPLATE=false" -e "K6_REG_FILE_BODY_PATH=" \
        -e "K6_REG_WARMUP=$CLU_WARMUP" -e "K6_REG_DURATION=$CLU_DURATION" \
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
    run_clu_crossing() { # proto  base_url  out
      # shellcheck disable=SC2046
      docker run --rm --network "$NETWORK" $(cpuset_arg "$K6_CPUS") \
        -v "$REPO_ROOT/mockserver-performance-test/k6:/k6:ro" -v "$OUT_DIR:/out" \
        -e "BASE_URL=$2" -e "PROTO=$1" -e "INSECURE_SKIP_TLS_VERIFY=false" \
        -e "K6_CLU_CROSS_RESULT_PATH=/out/$3" \
        -e "K6_CLU_CROSS_WARMUP=$CLU_WARMUP" -e "K6_CLU_CROSS_DURATION=$CLU_DURATION" \
        ${K6_CLU_CROSS_RATE:+-e K6_CLU_CROSS_RATE="$K6_CLU_CROSS_RATE"} \
        "$K6_IMAGE" run /k6/clustered_crossing.js >/dev/null 2>&1
    }

    # --- CONTROL arm: single node, in-memory backend --------------------------
    echo "--- clustered A/B: starting CONTROL (stateBackend=memory)"
    if start_clu "$CLU_CTRL" clu-ctrl memory ""; then wait_ready "$CLU_CTRL" || true; fi
    CTRL_HP="$(clu_hostport "$CLU_CTRL")"
    CTRL_MEMBERS="$(clu_members "$CTRL_HP")"
    CTRL_CLUSTERED="$(clu_clustered "$CTRL_HP")"
    echo "--- clustered A/B: control cluster view members=$CTRL_MEMBERS clustered=$CTRL_CLUSTERED (expect 1 / false)"
    run_clu_regression "clustered_control" "http://clu-ctrl:1080" "clu-control.json" \
      || echo "WARNING: clustered CONTROL regression run failed" >&2
    run_clu_crossing "clustered_control" "http://clu-ctrl:1080" "clu-cross-control.json" \
      || echo "WARNING: clustered CONTROL crossing run failed" >&2
    docker rm -f "$CLU_CTRL" >/dev/null 2>&1 || true

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
    start_clu "$CLU_A" "$CLU_A_ALIAS" infinispan "$CLU_NAME" || echo "WARNING: cluster node A did not start" >&2
    start_clu "$CLU_B" "$CLU_B_ALIAS" infinispan "$CLU_B_NAME" || echo "WARNING: cluster node B did not start" >&2
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

    run_clu_regression "clustered" "http://clu-a:1080" "clu-clustered.json" \
      || echo "WARNING: clustered CANDIDATE regression run failed" >&2
    run_clu_crossing "clustered" "http://clu-a:1080" "clu-cross-clustered.json" \
      || echo "WARNING: clustered CANDIDATE crossing run failed" >&2
    docker rm -f "$CLU_A" "$CLU_B" >/dev/null 2>&1 || true

    # Load both behaviour blocks (guarded to {} on any parse failure), then MERGE in
    # the crossing arm (op `crossing`) so the ratio machinery treats it identically —
    # it becomes clustered_state.arms.crossing, the per-request NETWORK-cost headline,
    # alongside the non-crossing membership arms.
    CLU_CTRL_BEHAVIOURS="$(jq -sc '(.[0].behaviours // {}) + (.[1].behaviours // {})' \
      "$OUT_DIR/clu-control.json" "$OUT_DIR/clu-cross-control.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$CLU_CTRL_BEHAVIOURS" || CLU_CTRL_BEHAVIOURS='{}'
    CLU_CAND_BEHAVIOURS="$(jq -sc '(.[0].behaviours // {}) + (.[1].behaviours // {})' \
      "$OUT_DIR/clu-clustered.json" "$OUT_DIR/clu-cross-clustered.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$CLU_CAND_BEHAVIOURS" || CLU_CAND_BEHAVIOURS='{}'

    # Assemble .clustered_state: the per-arm ratio (the metric), plus the cluster
    # genuineness proof. Ratios pair on the arm op (behaviour key minus its _<proto>
    # suffix). p50 is the primary ratio (robust at low N); p95 only where BOTH arms
    # cleared MIN_TAIL_SAMPLES (non-null p95). throughput_ratio is dir:down.
    CLUSTERED_JSON="$(jq -nc \
      --argjson ctrl "$CLU_CTRL_BEHAVIOURS" --argjson cand "$CLU_CAND_BEHAVIOURS" \
      --argjson a_members "${A_MEMBERS:-0}" --argjson b_members "${B_MEMBERS:-0}" \
      --argjson ctrl_members "${CTRL_MEMBERS:-0}" \
      --arg a_clustered "${A_CLUSTERED:-false}" --arg ctrl_clustered "${CTRL_CLUSTERED:-false}" \
      --arg state_crossed "$STATE_CROSSED" \
      --argjson cross_code "${CROSS_CODE:-0}" --argjson neg_code "${NEG_CODE:-0}" \
      --arg image "$CLU_IMAGE" --arg image_revision "$CLU_IMAGE_REVISION" '
      # strip a trailing _<proto> to recover the arm op
      def op(k; p): (k | sub("_"+p+"$"; ""));
      ($ctrl | to_entries | map({key: op(.key; "clustered_control"), value: .value}) | from_entries) as $C |
      ($cand | to_entries | map({key: op(.key; "clustered"),         value: .value}) | from_entries) as $K |
      {
        attempted: true,
        control_backend: "memory",
        candidate_backend: "infinispan",
        image: $image,
        # The OWN commit of the clustered image. Recorded, not just checked, so a
        # stored run can be audited later: the gate above refuses to measure unless
        # this equals the SUT image revision the run is filed under, and a reader of
        # the history can confirm that rather than take it on trust. Empty only for a
        # deliberately-unlabelled local image (PERF_CLUSTERED_REQUIRE_MATCHING_REVISION=false).
        # NB: no apostrophes in this jq program — it lives inside a single-quoted
        # bash string, so one would terminate it and break the script.
        image_revision: $image_revision,
        cluster: {
          member_count_node_a: $a_members,
          member_count_node_b: $b_members,
          control_member_count: $ctrl_members,
          candidate_clustered: ($a_clustered == "true"),
          control_clustered: ($ctrl_clustered == "true"),
          state_crossed_network: ($state_crossed == "true"),
          cross_node_probe_http_code: $cross_code,
          negative_control_http_code: $neg_code
        },
        arms: (
          [ $K | keys[] | select($C[.] != null) | . as $op |
            { key: $op, value: (
              ($C[$op]) as $c | ($K[$op]) as $k |
              {
                p50_control_ms: $c.p50_ms, p50_clustered_ms: $k.p50_ms,
                p50_ratio: (if ($c.p50_ms // 0) > 0 and $k.p50_ms != null
                            then (($k.p50_ms / $c.p50_ms) * 1000 | round) / 1000 else null end),
                p95_control_ms: $c.p95_ms, p95_clustered_ms: $k.p95_ms,
                p95_ratio: (if ($c.p95_ms // null) != null and ($c.p95_ms // 0) > 0 and ($k.p95_ms // null) != null
                            then (($k.p95_ms / $c.p95_ms) * 1000 | round) / 1000 else null end),
                throughput_control_rps: $c.throughput_rps, throughput_clustered_rps: $k.throughput_rps,
                throughput_ratio: (if ($c.throughput_rps // 0) > 0 and $k.throughput_rps != null
                            then (($k.throughput_rps / $c.throughput_rps) * 1000 | round) / 1000 else null end),
                control_error_rate: $c.error_rate, clustered_error_rate: $k.error_rate,
                sample_count_control: $c.sample_count, sample_count_clustered: $k.sample_count,
                # true ONLY for the bounded-Times `crossing` arm (a per-request REPL_SYNC
                # round trip); the regression arms are node-local membership overhead.
                # So a reader never mistakes a membership ratio for total clustering cost.
                crosses_network: ($k.crosses_network // false)
              }) } ] | from_entries
        )
      }')"
    jq -e . >/dev/null 2>&1 <<<"$CLUSTERED_JSON" || CLUSTERED_JSON='{}'
    echo "--- clustered A/B result (crossing = per-request NETWORK cost; the rest = membership-only):"
    jq -c '{cluster,
            crossing_arm: (.arms | to_entries | map(select(.value.crosses_network)) | from_entries | with_entries(.value |= {p50_ratio, p95_ratio, throughput_ratio})),
            membership_arms: (.arms | to_entries | map(select(.value.crosses_network|not)) | from_entries | with_entries(.value |= {p50_ratio, p95_ratio, throughput_ratio}))}' \
      <<<"$CLUSTERED_JSON" 2>/dev/null || echo "$CLUSTERED_JSON"
fi

# --- derive resource slope ratios from the sample log -------------------------
# start = first non-empty sample, end = last, peak = max. ratio = end/start.
# PROVOCATION (observed false): with the metrics port unreachable the sampler
# writes only its header row, wc -l is 1, and this check evaluates false — which
# is exactly the empty-sample-log case the plan wants promoted from a warning to a
# baseline refusal (a run with no resource trajectory cannot assess growth).
if [ "$(wc -l < "$SAMPLE_LOG" 2>/dev/null || echo 0)" -le 1 ]; then
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
CPU_RATIO="$(ratio "$CPU_END" "$CPU_START")"
# Live-set heap ratio = min heap over the LAST window / min over the FIRST window.
# The post-GC saw-tooth floor tracks the live set far better than an instantaneous
# end/start point sample (which lands at a random point on the GC saw-tooth), with
# no forced GC and much less noise. REPLACES the old instantaneous heap ratio.
HEAP_RATIO="$(ratio "$HEAP_MIN_LAST" "$HEAP_MIN_FIRST")"

abort_if_sut_died
# --- item 18: req/s per core for the SERVING path -----------------------------
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
if [ "${PERF_SERVING_PERCORE:-false}" = "true" ]; then
  SERVING_PERCORE_ATTEMPTED=true
  echo "--- item 18 serving per-core (opt-in; PERF_SERVING_PERCORE=true)"
  # NOT an add_check on failure by itself: the presence gate lives in compare
  # (serving_percore_attempted + a non-empty points/skipped set), matching laptop.
  if MOCKSERVER_IMAGE="$MOCKSERVER_IMAGE" PERF_PERCORE_REPO_ROOT="$REPO_ROOT" \
       bash "$SCRIPT_DIR/lib/perf-percore.sh" "$OUT_DIR/serving-percore.json"; then
    SERVING_PERCORE_JSON="$(cat "$OUT_DIR/serving-percore.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$SERVING_PERCORE_JSON" || SERVING_PERCORE_JSON='{}'
    echo "--- serving_percore: points=$(jq -r '(.points|length)//0' <<<"$SERVING_PERCORE_JSON") max_cores_measured=$(jq -r '.max_cores_measured//"?"' <<<"$SERVING_PERCORE_JSON") skipped=$(jq -r '(.skipped|length)//0' <<<"$SERVING_PERCORE_JSON")"
  else
    echo "WARNING: serving per-core profile failed — result carries no serving_percore points this run (notify-only)" >&2
  fi
fi

# --- item 18 (client rig): multi-process aggregate throughput vs process count -
# The single-process serving sweep (serving_percore above, and the main .sweep) is
# CLIENT-LIMITED: one k6 process serialises internally (arrival-rate executor +
# metrics pipeline + Go GC) and cannot saturate the widened client core pool, so
# its clean ceiling is a property of the k6 RIG, not the server. This profile drives
# the SAME offered-rate ladder from N INDEPENDENT k6 processes pinned to DISJOINT
# cores and reports the AGGREGATE client-sound ceiling per process-count, plus a
# scaling verdict that discriminates a per-process CLIENT limit (aggregate ceiling
# rises with N) from a SHARED-PATH / server limit (ceiling flat vs N). It is the
# instrument the programme owes for "a client rig that can saturate the server".
# DEFAULT OFF (opt-in): like serving_percore it spins up and tears down its OWN
# pinned SUT(s) and several k6 processes (many extra container lifecycles), so it is
# scheduled DELIBERATELY (the plan's Tier-3 classification), enabled by setting
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
  if MOCKSERVER_IMAGE="$MOCKSERVER_IMAGE" PERF_MULTI_REPO_ROOT="$REPO_ROOT" \
       bash "$REPO_ROOT/mockserver-performance-test/scripts/multi-process-sweep.sh" "$OUT_DIR/serving-multiproc.json"; then
    SERVING_MULTIPROC_JSON="$(cat "$OUT_DIR/serving-multiproc.json" 2>/dev/null || echo '{}')"
    jq -e . >/dev/null 2>&1 <<<"$SERVING_MULTIPROC_JSON" || SERVING_MULTIPROC_JSON='{}'
    echo "--- serving_multiproc: points=$(jq -r '(.points|length)//0' <<<"$SERVING_MULTIPROC_JSON") procs_measured=$(jq -rc '.procs_measured//"?"' <<<"$SERVING_MULTIPROC_JSON") scales_with_procs=$(jq -r '.scaling.scales_with_procs//"?"' <<<"$SERVING_MULTIPROC_JSON") skipped=$(jq -r '(.skipped|length)//0' <<<"$SERVING_MULTIPROC_JSON")"
  else
    echo "WARNING: serving multi-process profile failed — result carries no serving_multiproc points this run (notify-only)" >&2
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
# THIS WAS LIVE, NOT HYPOTHETICAL. The perf ASG launch template
# (lt-01f1ac1070d561f50) sets HttpTokens=required, HttpPutResponseHopLimit=2 — the
# agents REQUIRE IMDSv2. The original UNAUTHENTICATED GET therefore got a 401 on
# every perf run: `curl -s` returned "" (empty body, exit 0) and the old
# `|| echo fallback` never fired, so EVERY stored baseline point carries
# instance_type:"" (confirmed on runs/master/2026-09-10T04-15-15Z__42193bc4f6.json).
# The whole series has been unqualified by hardware since inception, invisibly,
# because the failure wrote a falsy value at exit 0 — exactly the defect item 0's
# control exists to catch.
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
if jq -e '((.behaviours // {}) | to_entries | map(select(.value.p95_ms != null)) | length) > 0' \
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
if awk -v v="$HEAP_MIN_LAST" 'BEGIN{exit !(v+0>0)}'; then
  add_check "growth_heap_sampled" true "live-set floor min_last_window=${HEAP_MIN_LAST} bytes (heap metrics captured)"
else
  add_check "growth_heap_sampled" false "no heap samples during growth (/mockserver/metrics unreachable?) — live-set floor is 0, growth.live_set_bytes would poison the baseline as a zero"
fi
# item 12 — streaming presence. Only asserted when the profile was ATTEMPTED
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
# item 13 — clustered A/B presence + GENUINENESS. Only asserted when ATTEMPTED
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

jq -n \
  --arg commit "$COMMIT" --arg harness_commit "$HARNESS_COMMIT" --arg branch "$BRANCH" --arg ts "$TS" \
  --arg build_number "${BUILDKITE_BUILD_NUMBER:-}" --arg build_url "${BUILDKITE_BUILD_URL:-}" \
  --arg instance_type "$INSTANCE_TYPE" --arg instance_type_src "$INSTANCE_TYPE_SRC" --arg image "$MOCKSERVER_IMAGE" \
  --arg server_cpus "${SERVER_CPUS:-none}" --arg k6_cpus "${K6_CPUS:-none}" \
  --slurpfile http "$OUT_DIR/regression-http.json" \
  --slurpfile https "$OUT_DIR/regression-https.json" \
  --argjson growth "$GROWTH_JSON" \
  --argjson sweep "$SWEEP_JSON" \
  --argjson saturation "$SATURATION_JSON" \
  --arg saturation_rps "$SATURATION_RPS" \
  --arg rig_valid_peak_achieved_rps "$PEAK_ACHIEVED_RPS" \
  --argjson forward "$FORWARD_JSON" \
  --arg forward_exit "$FORWARD_EXIT" \
  --argjson proxyfwd "$PROXY_FWD_JSON" \
  --argjson handshake "$HANDSHAKE_JSON" \
  --argjson streaming "$STREAMING_JSON" \
  --argjson clustered_state "$CLUSTERED_JSON" \
  --argjson clustered_attempted "$CLUSTERED_ATTEMPTED" \
  --arg clustered_skip_reason "$CLUSTERED_SKIP_REASON" \
  --argjson clu_ctrl "$CLU_CTRL_BEHAVIOURS" \
  --argjson clu_cand "$CLU_CAND_BEHAVIOURS" \
  --argjson validity "$VALIDITY_JSON" \
  --argjson baseline_eligible "$BASELINE_ELIGIBLE" \
  --argjson config "$CONFIG_JSON" \
  --argjson laptop "$LAPTOP_JSON" \
  --argjson laptop_attempted "$LAPTOP_ATTEMPTED" \
  --argjson laptop_parallel "$LAPTOP_PARALLEL_JSON" \
  --argjson serving_percore "$SERVING_PERCORE_JSON" \
  --argjson serving_percore_attempted "$SERVING_PERCORE_ATTEMPTED" \
  --argjson serving_multiproc "$SERVING_MULTIPROC_JSON" \
  --argjson serving_multiproc_attempted "$SERVING_MULTIPROC_ATTEMPTED" \
  --argjson info_log_level_arm "$INFO_ARM_JSON" \
  --argjson info_log_level_arm_attempted "$INFO_ARM_ATTEMPTED" \
  --arg cpu_start "$CPU_START" --arg cpu_end "$CPU_END" --arg cpu_peak "$CPU_PEAK" --arg cpu_ratio "$CPU_RATIO" \
  --arg heap_start "$HEAP_START" --arg heap_end "$HEAP_END" --arg heap_peak "$HEAP_PEAK" --arg heap_ratio "$HEAP_RATIO" \
  --arg heap_min_first "$HEAP_MIN_FIRST" --arg heap_min_last "$HEAP_MIN_LAST" \
  --arg gc_delta "$GC_DELTA" --arg threads_peak "$THREADS_PEAK" \
  '{
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
    agent: { instance_type: $instance_type, instance_type_source: $instance_type_src, queue: "perf", server_cpus: $server_cpus, k6_cpus: $k6_cpus },
    config: $config,
    mockserver_image: $image,
    # regression (http + https_h2) + proxy.js FORWARD arms all live in .behaviours
    # (same shape), so the compare step behaviours.* budgets cover them with no jq
    # change. Adding the two forward_*_proxy arms grows the k6 arm-set fingerprint,
    # so compare resets the k6 baseline ONCE (intended — see item 9a). CONSEQUENCE
    # for whoever reads the first post-landing runs: because the fingerprint changed,
    # ALL behaviours.* arms (the existing match/forward/template/large arms included)
    # go :new: / no-baseline and are NOT flagged until MIN_BASELINE arm-set-matching
    # runs accrue — a one-off, expected gap in behaviour-arm coverage, surfaced by the
    # compare step "k6 behaviour baseline reset" note. (These arms are notify-only,
    # so no GATING coverage is lost; forward.error_rate and the JMH gates are
    # unaffected as they are not behaviours.* and not fingerprint-filtered.)
    # item 13 — the clustered A/B raw per-arm latencies land here too (distinct
    # <op>_clustered / <op>_clustered_control keys), so the existing behaviours.*
    # budgets cover them with no new key. Adding these arms GROWS the k6 arm-set
    # fingerprint, so compare resets the k6 behaviour baseline ONCE — the same
    # self-healing, notify-only reset the item 9a forward-proxy arms already trigger
    # (no GATING coverage lost). The headline metric — the RATIO — is in
    # .clustered_state (below), which rides the FULL baseline, not this fingerprint.
    # When the clustered profile is disabled/skipped, both objects are {}, so no arms
    # are added and the fingerprint is unchanged.
    behaviours: (($http[0].behaviours // {}) + ($https[0].behaviours // {}) + ($proxyfwd.behaviours // {}) + $clu_ctrl + $clu_cand),
    # item 14 — TLS/mTLS/native-absent handshake cost (proxy.js handshake mode +
    # per-SUT CPU/alloc augmentation above). NOT part of .behaviours, so it does not
    # touch the k6 fingerprint; compare reads it as its own non-gating metric family.
    tls_handshake: ($handshake.tls_handshake // {}),
    # item 12 — LLM/SSE streaming under concurrency: the match-p95 A/B, the
    # server-side inter-token delay-error distribution (idle vs load) and heap per
    # open stream. NOT part of .behaviours, so it does not touch the k6 fingerprint;
    # compare reads a CURATED subset as its own non-gating metric family.
    streaming: $streaming,
    # item 13 — clustered state under load: the per-arm ratio (clustered / in-memory
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
    growth: {
      duration_s: ($growth.duration_s // null),
      p95_ms: ($growth.p95_ms // null),
      cpu_pct: { start: ($cpu_start|tonumber), end: ($cpu_end|tonumber), peak: ($cpu_peak|tonumber), ratio: (try ($cpu_ratio|tonumber) catch null) },
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
    # filter caps it at whichever rung the client stops being clean, which on this rig is far
    # below the server ceiling. Named to say so; the all-rung server peak the website
    # publishes is a DIFFERENT number computed independently in lib/perf-website-figures.jq.
    rig_valid_peak_achieved_rps: (try ($rig_valid_peak_achieved_rps|tonumber) catch null),
    saturation_rps: (try ($saturation_rps|tonumber) catch null),
    saturation: $saturation,
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
    validity: $validity,
    # Part C — baseline eligibility. false ONLY for an instrumented (PERF_JVM_DIAGNOSTICS
    # =deep) run: perf-test-compare.sh records it but refuses to persist/compare it (and
    # stays GREEN — a deep run is a deliberate investigation, not a failure), so tier-2
    # instrumentation overhead can never silently shift the baseline series. A run with
    # no baseline_eligible field (older producer) is treated as eligible, unchanged.
    baseline_eligible: $baseline_eligible,
    # Item 8 laptop startup/footprint profile (notify-only). `{}` when the profile
    # was disabled or its measurement failed; compare iterates head metrics, so an
    # empty object simply emits zero laptop.* metrics (no missing-budget trip). The
    # separate `laptop_attempted` flag lets compare RED a wholesale failure (attempted
    # but empty/docker-incomplete) instead of mistaking it for a disabled profile.
    laptop: ($laptop.laptop // {}),
    laptop_attempted: $laptop_attempted,
    # item 17 — N parallel instances (laptop / MockServerExtension profile), notify-only and
    # OPT-IN (PERF_LAPTOP_PARALLEL). `{}` when disabled or when a shape was skipped/failed;
    # compare iterates .laptop_parallel.<shape>.runs, so an empty object emits zero laptop.*
    # metrics (head-driven, no missing-budget trip) — the serving_percore / streaming pattern.
    # No *_attempted flag by design: unlike the item 8 laptop and serving_percore presence gates
    # (which RED a wholesale producer failure), item 17 is a deliberately-scheduled research
    # profile whose absence is the normal daily state, so a presence gate would add a RED path
    # with no daily signal to guard. A shape that runs but measures nothing simply emits nothing.
    laptop_parallel: $laptop_parallel,
    # item 18 — req/s per core for the SERVING path. `{}` when the profile was
    # disabled or its measurement failed; compare iterates .serving_percore.points,
    # so an empty object emits zero serving_percore.* metrics (no missing-budget
    # trip). serving_percore_attempted lets compare RED a wholesale failure
    # (attempted but no points) apart from a disabled profile — the laptop split.
    serving_percore: $serving_percore,
    serving_percore_attempted: $serving_percore_attempted,
    # item 18 (client rig) — multi-process aggregate throughput vs process count.
    # `{}` when the profile was disabled or its measurement failed; compare reads
    # .serving_multiproc.points / .scaling head-driven, so an empty object emits zero
    # serving_multiproc.* metrics (no missing-budget trip). serving_multiproc_attempted
    # lets compare RED a wholesale failure (attempted but no points and no infeasible
    # skip) apart from a disabled profile — the serving_percore / laptop split.
    serving_multiproc: $serving_multiproc,
    serving_multiproc_attempted: $serving_multiproc_attempted,
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
  }' > "$RESULT_JSON"

echo "--- result.json"
cat "$RESULT_JSON"

# Standalone sweep artifact (also embedded under .sweep in result.json above).
cp "$OUT_DIR/sweep.json" "$REPO_ROOT/perf-sweep.json" 2>/dev/null || echo '{}' > "$REPO_ROOT/perf-sweep.json"
# Standalone serving per-core artifact (also embedded under .serving_percore).
# Emitted only when the profile ran; the website's serving per-core chart (item 19)
# and a dated trend read this file, mirroring perf-sweep.json / inject-percore.json.
if [ -f "$OUT_DIR/serving-percore.json" ]; then
  cp "$OUT_DIR/serving-percore.json" "$REPO_ROOT/serving-percore.json" 2>/dev/null || true
fi
# Standalone multi-process sweep artifact (also embedded under .serving_multiproc).
# Emitted only when the profile ran; a dated trend / the client-rig chart read this
# file, mirroring perf-sweep.json / serving-percore.json.
if [ -f "$OUT_DIR/serving-multiproc.json" ]; then
  cp "$OUT_DIR/serving-multiproc.json" "$REPO_ROOT/serving-multiproc.json" 2>/dev/null || true
fi

# Stop the diagnostics streams so their files are complete, then upload the JVM-internals bundle on
# the SUCCESS path too (the failure path already uploaded it from capture_sut_diagnostics). A healthy
# run's dense resource trajectory (and, on a deep run, its GC log) is itself the reference the NEXT
# death is read against.
[ -n "$DIAG_SAMPLER_PID" ] && kill "$DIAG_SAMPLER_PID" >/dev/null 2>&1 || true; DIAG_SAMPLER_PID=""
[ -n "$SUT_LOG_PID" ] && kill "$SUT_LOG_PID" >/dev/null 2>&1 || true; SUT_LOG_PID=""
[ -n "$INFO_LOG_PID" ] && kill "$INFO_LOG_PID" >/dev/null 2>&1 || true; INFO_LOG_PID=""

if command -v buildkite-agent >/dev/null 2>&1; then
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  buildkite-agent artifact upload "perf-result.json" || true
  buildkite-agent artifact upload "perf-sweep.json" || true
  upload_diag_bundle
  [ -f "$REPO_ROOT/serving-percore.json" ] && buildkite-agent artifact upload "serving-percore.json" || true
  [ -f "$REPO_ROOT/serving-multiproc.json" ] && buildkite-agent artifact upload "serving-multiproc.json" || true
  # Record the HARNESS commit this run executed against — deliberately NOT the
  # attributed (image-revision) `$COMMIT`. perf-test-guard.sh reads this (via
  # last_perf_run_commit) and compares it to the current git HEAD to decide "has
  # master moved since the last real run". HEAD is a harness-checkout SHA, so the
  # stored value must be one too: keying this off the image revision (which lags
  # master by a java-pipeline duration) would make LAST != HEAD on every quiet day
  # and dispatch the heavy run daily forever, defeating the commit-gate economy.
  # Keyed off real runs, NOT the lint build that passes on every push.
  buildkite-agent meta-data set "perf_regression_ran_commit" "$HARNESS_COMMIT" || true
else
  cp "$RESULT_JSON" "$REPO_ROOT/perf-result.json"
  echo "(local run) result + sweep copied to $REPO_ROOT/perf-result.json, $REPO_ROOT/perf-sweep.json"
fi

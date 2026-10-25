#!/usr/bin/env bash
set -euo pipefail

# Lint the k6 performance harness:
#   1. syntax-check the shell run scripts (bash on the agent), and
#   2. validate every k6 entry script in a pinned grafana/k6 container —
#      `k6 inspect` parses the JS, resolves the lib/ imports, and validates the
#      options/scenarios/thresholds without running any load.
#
# Reproduce locally: run this script from the repo root (needs docker).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
PERF_DIR="$REPO_ROOT/mockserver-performance-test"

echo "--- syntax-checking run scripts"
for s in scripts/runMockServer.sh scripts/runK6.sh scripts/runAll.sh; do
  echo "bash -n $s"
  bash -n "$PERF_DIR/$s"
done

echo "--- shellcheck on the perf shell libs (catches mangled embedded jq)"
# WHY THIS IS HERE AND WHY `bash -n` IS NOT ENOUGH.
#
# These scripts embed jq programs as single-quoted bash strings. A possessive in
# a comment INSIDE such a program -- k6's, pool's, the sweep's -- closes the
# string early. If a second one appears later it re-opens it, so the file's
# quoting stays BALANCED and `bash -n` reports it valid. jq then receives
# mangled text and the step dies at that line mid-run.
#
# The worked example: perf-percore.sh carried k6's and pool's on
# adjacent comment lines inside the result-assembly jq program. bash -n passed,
# an authoritative review passed, and the per-core ladder still died with
#   syntax error near unexpected token `$sweep[0].vus_diagnostics'
# AFTER the ladder had run -- so the per-core curve was lost while every check was
# green.
#
# SC1073/SC1072 DO catch it -- verified against that exact broken file, and
# clean on the fixed one -- so the guard is a mature parser rather than a bespoke
# one. (A first attempt here was a hand-written quote-state tracker; it could not
# model command substitution inside double quotes and produced both false
# positives and a false negative, which is a worse guard than none.) -S error
# keeps this to parse-level breakage and will not fail the build on style.
if command -v shellcheck >/dev/null 2>&1; then
  SC_FILES=""
  for f in "$REPO_ROOT"/.buildkite/scripts/steps/lib/perf-*.sh "$REPO_ROOT"/.buildkite/scripts/steps/perf-*.sh \
           "$PERF_DIR"/scripts/multi-process-sweep.sh "$PERF_DIR"/scripts/rw-multi-k6-sweep.sh; do
    [ -f "$f" ] && SC_FILES="$SC_FILES $f"
  done
  # shellcheck disable=SC2086
  shellcheck -S error $SC_FILES
  echo "  shellcheck -S error: clean across the perf shell libs"
else
  echo "  shellcheck absent on this agent — skipping (bash -n above still runs)" >&2
fi

echo "--- hardware-matrix fixture checks (item 27: lower-bound reasons, page fields, publish hold)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-hw-matrix-test.sh"

echo "--- rw-multi-k6 same-requests cross-check fixture checks (item 31: lib/perf-rw-cross-check.jq)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-rw-cross-check-test.sh"

echo "--- rw-multi-k6 interrupted-iteration fixture checks (item 31: lib/perf-k6-interrupted.sh)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-k6-interrupted-test.sh"

echo "--- rw-multi-k6 default k6 runtime fixture checks (item 31: lib/perf-k6-runtime.sh)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-k6-runtime-test.sh"

echo "--- perf fixture tests are hermetic (a build's PERF_*/K6_* knobs never reach their defaults)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-test-env-test.sh"

echo "--- rw-multi-k6 tail attribution fixture checks (item 44: lib/perf-tail-instrument.sh, rw-tail-attribution.py)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-tail-instrument-test.sh"

echo "--- NUMA placement fixture checks (items 31/44: lib/perf-cpu-topology.sh on fake sysfs, both arms' layouts)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-cpu-topology-test.sh"

echo "--- perf-test-run start-up checks (diagnostics tiers, env guards, wait_ready states)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-run-ready-test.sh"

echo "--- perf-test-run upstream alias checks (containers reached by alias, DNS-label guard, seed evidence)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-upstream-alias-test.sh"

echo "--- INFO-arm event-log budget checks (shipped default on the INFO SUT, info_* baseline key)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-info-budget-test.sh"

echo "--- bimodal budget checks (a listed p95 is judged against its head's mode; the real 649-734 history replayed)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-bimodal-budget-test.sh"

echo "--- noise budget checks (windowed growth CPU ratio, alloc min_abs, path-coverage p99 min_samples; the real 643-741 history replayed)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-noise-budget-test.sh"

echo "--- perf-xl dispatch checks (guard YAML with and without PERF_XL, PERF_RUN_ARM switch)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-xl-dispatch-test.sh"

echo "--- perf-xl publish checks (item 44: series membership, compare persists only a member, publish per source, committed page data)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-xl-publish-test.sh"

echo "--- website publish persist-gate checks (publish only from a build whose compare persisted a run)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-publish-persist-gate-test.sh"

echo "--- compare queue-history checks (a perf-xl run never enters or displaces the perf baseline window)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-compare-queue-history-test.sh"

echo "--- main-SUT event-log budget checks (item 51: shipped default on the main SUT, scoped growth budget, main-SUT baseline key)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-default-budget-test.sh"

echo "--- allocation-profile annotation checks (item 28: ceiling views, size cap, degrade notes)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-allocprofile-annotation-test.sh"

echo "--- healthy ceiling rule checks (the climb stops at the first unhealthy measured rung; every copy of the rule agrees)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-healthy-ceiling-test.sh"

echo "--- INFO-arm live-heap reset probe checks (lib/perf-info-heap-probe.sh on stubbed docker and curl, its wiring)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-info-heap-probe-test.sh"

echo "--- SUT receive-queue sampler checks (item 44: lib/perf-sut-recvq.sh and its python engine on a fake /proc, its wiring)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-sut-recvq-test.sh"

echo "--- weekly soak checks (provenance, drift from the per-window series, event-log bound, the step on stubbed docker and curl)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-soak-test.sh"

echo "--- gating JMH time_per_op score checks (lib/perf-microbench-reshape.jq: one slow iteration cannot trip it, a 5-6% slowdown still does)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-microbench-score-test.sh"

echo "--- release perf preflight gate checks (every gating budget is mapped, delegated or premerge; real-shaped run passes, a bad value fails)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-preflight-gate-test.sh"

echo "--- byte-compiling the SSE fidelity reader (item 12)"
# The reader is pure-stdlib python3 run on the perf agent by perf-test-run.sh; a
# syntax error would only surface mid-run, so compile it here. Skip (do not fail)
# if python3 is absent on the lint agent — the run step guards on it too.
if command -v python3 >/dev/null 2>&1; then
  python3 -m py_compile "$PERF_DIR/k6/tools/sse-fidelity-reader.py"
  echo "python3 -m py_compile k6/tools/sse-fidelity-reader.py OK"
  python3 -m py_compile "$PERF_DIR/scripts/rw-tail-attribution.py"
  echo "python3 -m py_compile scripts/rw-tail-attribution.py OK"
  python3 -m py_compile "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-sut-recvq.py"
  echo "python3 -m py_compile .buildkite/scripts/steps/lib/perf-sut-recvq.py OK"
  python3 -m py_compile "$REPO_ROOT/.buildkite/scripts/steps/lib/perf-info-promotion.py"
  echo "python3 -m py_compile .buildkite/scripts/steps/lib/perf-info-promotion.py OK"
  python3 -m unittest discover -s "$PERF_DIR/k6/tools" -p 'test_pipelined_http1_client.py'
  echo "python3 -m unittest k6/tools/test_pipelined_http1_client.py OK"
else
  echo "python3 absent — skipping reader byte-compile (run step guards on it)"
fi

echo "--- in-order pipelining A/B checks (lib/perf-pipelining-ab.sh on stubbed docker, curl and python3; its compare rows)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-pipelining-ab-test.sh"

echo "--- FILE-body A/B checks (lib/perf-file-body-ab.sh on stubbed docker; its compare rows)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-file-body-ab-test.sh"

echo "--- clustered-state A/B checks (lib/perf-clustered-ab.sh: pinning, cgroup throttling, paired windows, assembly; its compare rows)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-clustered-ab-test.sh"

echo "--- INFO investigation step checks (cell verdict, cleanup on cancel, fail-closed exit, on stubbed docker and curl)"
bash "$REPO_ROOT/.buildkite/scripts/test/perf-info-investigation-test.sh"

echo "--- validating k6 scripts (k6 inspect)"
# The remote-write inspects run under that arm's default runtime: gracefulStop 5s (the default gap),
# GOGC 1600 and a GOMEMLIMIT in the MiB form the harness derives (Go aborts on a malformed one); the
# second is the perf-xl ladder per process (N=4) with the VU ceiling the arm derives there.
# The single-quoted -c body is expanded by the container's sh, not the host —
# $f must NOT expand here, so SC2016 is intentional.
# shellcheck disable=SC2016
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i grafana/k6:1.7.1@sha256:4fd3a694926b064d3491d9b02b01cde886583c4931f1223816e3d9a7bdfa7e0f \
  --entrypoint sh \
  -w /build/mockserver-performance-test \
  -- -c 'set -e; for f in k6/smoke.js k6/load.js k6/stress.js k6/soak.js k6/regression.js k6/growth.js k6/sweep.js k6/forward.js k6/proxy.js k6/streaming.js k6/clustered_crossing.js k6/coverage.js; do echo "k6 inspect $f"; k6 inspect "$f" > /dev/null; done; echo "k6 inspect k6/regression.js (pipelining A/B: match + forward only)"; k6 inspect -e K6_REG_ARMS=match,forward k6/regression.js > /dev/null; echo "k6 inspect k6/sweep.js (remote-write multi-k6 mode)"; GOGC=1600 GOMEMLIMIT=12288MiB k6 inspect -e K6_SWEEP_WINDOW_MODE=wallclock -e K6_SWEEP_LEAN_SUMMARY=true -e K6_SWEEP_VU_DIAGNOSTICS=false -e K6_SWEEP_QUIET=5s -e K6_SWEEP_MANAGE_SUT=false -e K6_SWEEP_GRACEFUL_STOP=5s -e K6_SWEEP_START_AT_MS=$(( $(date +%s) * 1000 + 60000 )) k6/sweep.js > /dev/null; echo "k6 inspect k6/sweep.js (multi-k6 on a multi-socket host: per-process top rung 40k, VU ceiling 3200)"; GOGC=1600 GOMEMLIMIT=31725MiB k6 inspect -e K6_SWEEP_WINDOW_MODE=wallclock -e K6_SWEEP_LEAN_SUMMARY=true -e K6_SWEEP_QUIET=5s -e K6_SWEEP_MANAGE_SUT=false -e K6_SWEEP_GRACEFUL_STOP=5s -e K6_SWEEP_START_AT_MS=$(( $(date +%s) * 1000 + 60000 )) -e K6_SWEEP_RATES=125,250,500,1000,2000,4000,6000,8000,9000,10000,11000,12000,14000,16000,18000,20000,24000,26000,28000,30000,32000,34000,36000,38000,40000 -e K6_SWEEP_VU_CEILING=3200 k6/sweep.js > /dev/null; echo "k6 inspect .buildkite/scripts/steps/lib/perf-info-heapfill.js (fill and warm-up modes)"; k6 inspect -e MODE=proxy -e TARGET=http://upstream/echo /build/.buildkite/scripts/steps/lib/perf-info-heapfill.js > /dev/null; k6 inspect -e ITERATIONS=300 -e BODY_BYTES=102400 /build/.buildkite/scripts/steps/lib/perf-info-heapfill.js > /dev/null; echo "soak.js init checks"; sh /build/.buildkite/scripts/test/k6-soak-init-test.sh; echo "file_body_ab.js init checks"; sh /build/.buildkite/scripts/test/k6-file-body-ab-init-test.sh'

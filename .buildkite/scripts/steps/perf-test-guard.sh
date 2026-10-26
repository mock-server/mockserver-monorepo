#!/usr/bin/env bash
set -euo pipefail

# Commit guard + dynamic dispatch for the daily performance-regression run, on the
# cheap `trigger` queue. The heavy steps run only when master moved since the last
# perf run; Buildkite `if:` cannot read runtime state, so this script decides and
# uploads them (the generate-pipeline.sh pattern).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=.buildkite/scripts/lib/last-successful-commit.sh
source "$SCRIPT_DIR/../lib/last-successful-commit.sh"

HEAD_SHA="$(git rev-parse HEAD 2>/dev/null || echo '')"

# --- OPT-IN: load-injection ceiling + scaling sweep ---------------------------
# perf-test-inject.sh (~60m) measures how much load MockServer can GENERATE, not
# how fast it serves, so it is not in the daily dispatch. It runs on PERF_INJECT or
# a `[perf-inject]` message, independent of the new-commit guard.
maybe_dispatch_inject() {
  local want=false
  if [ "${PERF_INJECT:-}" = "true" ] || [ "${PERF_INJECT:-}" = "1" ] \
     || [[ "${BUILDKITE_MESSAGE:-}" == *"[perf-inject]"* ]]; then
    want=true
  fi
  if [ "$want" != true ]; then
    echo "--- :zzz: load-injection sweep not requested (set PERF_INJECT=true or [perf-inject] to enable)"
    return 0
  fi
  if ! command -v buildkite-agent >/dev/null 2>&1; then
    echo "(local run) buildkite-agent unavailable — would dispatch inject sweep"
    return 0
  fi
  echo "--- :rocket: load-injection requested (PERF_INJECT / [perf-inject]) — dispatching inject sweep"
  buildkite-agent pipeline upload <<'YAML'
steps:
  - label: ":envoy: load injection — ceiling + scaling sweep"
    command: ".buildkite/scripts/steps/perf-test-inject.sh"
    timeout_in_minutes: 60
    agents:
      queue: "perf"
YAML
}

# --- OPT-IN: the perf-xl queue (one c6i.32xlarge, two NUMA nodes) -------------
# PERF_XL=true runs the multi-k6 arm, and the hardware matrix when PERF_SERVING_HW_MATRIX=true, as
# arm-only steps on perf-xl (perf-test-run.sh PERF_RUN_ARM); perf-test-run.sh then drops both arms from
# the perf-run step. Their artifacts carry a perfxl- prefix that the daily compare never downloads, so a
# perf-xl result never enters the c5.12xlarge baseline; the multi-k6 arm has its own persist and publish.
PERF_XL_ON=false
if [ "${PERF_XL:-}" = "true" ]; then
  PERF_XL_ON=true
elif [ -n "${PERF_XL:-}" ]; then
  echo "--- :warning: PERF_XL='${PERF_XL}' is not 'true'; perf-xl not dispatched"
fi

perf_xl_yaml() {
  local arms=(rw_multik6) arm name label timeout
  [ "${PERF_SERVING_HW_MATRIX:-false}" = "true" ] && arms+=(hw_matrix)
  echo "steps:"
  for arm in "${arms[@]}"; do
    name="perfxl-${arm//_/-}"
    # rw_multik6: SUT start-up + 60 s warm-up + ~12 min ladder. hw_matrix: ~10 min per point.
    case "$arm" in
      rw_multik6) label=":k6: perf-xl — multi-k6 arm (item 31)"; timeout=45 ;;
      hw_matrix)  label=":straight_ruler: perf-xl — hardware matrix (item 27)"; timeout=130 ;;
    esac
    cat <<YAML
  - label: "${label}"
    key: "${name}"
    command: ".buildkite/scripts/steps/perf-test-run.sh"
    env:
      PERF_RUN_ARM: "${arm}"
      PERF_RUN_NAME: "${name}"
    timeout_in_minutes: ${timeout}
    agents:
      queue: "perf-xl"
    retry:
      automatic:
        - exit_status: -1   # agent lost
          limit: 2
        - exit_status: 255  # agent forced shutdown
          limit: 2
YAML
  done
  # The page headline is published from the arm's result. Its own compare persists it to
  # runs-perf-xl/ when it is a series member, and publish refreshes the page from there. Hard
  # edges: an arm that failed (invalid) is never persisted, and a failed persist never publishes.
  cat <<'YAML'
  - label: ":bar_chart: perf-xl — persist the multi-k6 headline (item 44)"
    key: "perfxl-rw-multik6-persist"
    depends_on: "perfxl-rw-multik6"
    command: ".buildkite/scripts/steps/perf-test-compare.sh"
    env:
      PERF_COMPARE_RESULT_ARTIFACT: "perfxl-rw-multik6-perf-result.json"
    timeout_in_minutes: 10
    agents:
      queue: "perf"
  - label: ":globe_with_meridians: perf-xl — publish the multi-k6 headline to the website (patch)"
    key: "perfxl-rw-multik6-publish"
    depends_on: "perfxl-rw-multik6-persist"
    command: ".buildkite/scripts/steps/perf-website-publish.sh"
    env:
      PERF_PUBLISH_SOURCE: "rw_multik6"
    timeout_in_minutes: 15
    soft_fail: true
    agents:
      queue: "perf"
YAML
}

maybe_dispatch_perf_xl() {
  if [ "$PERF_XL_ON" != true ]; then
    return 0
  fi
  if ! command -v buildkite-agent >/dev/null 2>&1; then
    echo "(local run) buildkite-agent unavailable — would dispatch the perf-xl arm steps"
    return 0
  fi
  echo "--- :rocket: PERF_XL=true — dispatching the perf-xl arm steps"
  perf_xl_yaml | buildkite-agent pipeline upload
}

# A UI build, or an API build whose message carries `[perf-run]`, always dispatches,
# even on the commit last measured. Scheduled runs stay commit-gated.
FORCE_RUN=false
if [ "${BUILDKITE_SOURCE:-}" = "ui" ] || [[ "${BUILDKITE_MESSAGE:-}" == *"[perf-run]"* ]]; then
  FORCE_RUN=true
  echo "--- :rocket: forced run (source=${BUILDKITE_SOURCE:-?}, [perf-run] marker) — skipping new-commit guard"
fi

echo "--- :buildkite: resolving the commit the perf regression last RAN against"
LAST="$(last_perf_run_commit || true)"

NEW_COMMIT=true
if [ "$FORCE_RUN" = false ] && [ -n "$LAST" ]; then
  echo "    last perf run: ${LAST:0:10}  (HEAD: ${HEAD_SHA:0:10})"
  # Equality on the recorded run commit — any new commit on the branch dispatches.
  if [ "$LAST" = "$HEAD_SHA" ]; then
    NEW_COMMIT=false
  fi
elif [ "$FORCE_RUN" = false ]; then
  echo "    no prior perf run recorded — running (first run / conservative)"
fi

if [ "$NEW_COMMIT" = false ]; then
  echo "--- :zzz: no commit since last perf run — skipping"
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' ":zzz: **Perf regression skipped** — no commit on \`${BUILDKITE_BRANCH:-master}\` since the last successful run (\`${LAST:0:10}\`)." \
      | buildkite-agent annotate --style info --context perf-regression || true
  fi
  # The injection sweep is independent of the new-commit guard — honour an
  # explicit opt-in even when the daily regression run is skipped.
  maybe_dispatch_inject
  exit 0
fi

echo "--- :rocket: new commit detected — dispatching perf regression run"
if ! command -v buildkite-agent >/dev/null 2>&1; then
  echo "(local run) buildkite-agent unavailable — would dispatch run + microbench + compare"
  exit 0
fi

# The 90-min base includes ~5 min for the in-order pipelining A/B (PERF_PIPELINING=false drops it),
# ~3.5 min for the FILE-body A/B (PERF_FILE_BODY_AB=false drops it) and ~5.5 min for the clustered
# A/B's paired windows (PERF_CLUSTERED=false drops the whole A/B).
# PERF_SERVING_HW_MATRIX=true (a manual-build opt-in) adds six multi-k6 points of ~10 min each
# (20 rungs x 20 s, a 3-rung cross-check, SUT and Prometheus start-up): ~60 min on that base,
# with ~30 min margin.
# Under PERF_XL=true both arms run on perf-xl instead, so neither extends this step.
PERF_RUN_TIMEOUT=90
if [ "${PERF_SERVING_HW_MATRIX:-false}" = "true" ] && [ "$PERF_XL_ON" != true ]; then
  PERF_RUN_TIMEOUT=180
  echo "--- :straight_ruler: PERF_SERVING_HW_MATRIX=true — run step timeout ${PERF_RUN_TIMEOUT}m"
fi
# PERF_SERVING_RW_MULTIK6=true (opt-in) adds ~12 min; +20 leaves margin.
if [ "${PERF_SERVING_RW_MULTIK6:-false}" = "true" ] && [ "$PERF_XL_ON" != true ]; then
  PERF_RUN_TIMEOUT=$(( PERF_RUN_TIMEOUT + 20 ))
  echo "--- :straight_ruler: PERF_SERVING_RW_MULTIK6=true — run step timeout ${PERF_RUN_TIMEOUT}m"
fi
# PERF_DATAPLANE_AB=true (opt-in) adds ~13 min (three SUTs, 18 paired windows); +20 leaves margin.
if [ "${PERF_DATAPLANE_AB:-false}" = "true" ]; then
  PERF_RUN_TIMEOUT=$(( PERF_RUN_TIMEOUT + 20 ))
  echo "--- :straight_ruler: PERF_DATAPLANE_AB=true — run step timeout ${PERF_RUN_TIMEOUT}m"
fi

sed "s/@PERF_RUN_TIMEOUT@/${PERF_RUN_TIMEOUT}/" <<'YAML' | buildkite-agent pipeline upload
steps:
  # --- DEPENDENCY GRAPH — read before touching the ordering below --------------
  # Edges (─▶ hard / fail-closed; ┄▶ wait-only, a red cannot skip the dependent):
  #     perf-run        ─▶ perf-compare ─▶ publish
  #     perf-microbench ┄▶ perf-compare
  #     perf-h2multiplex┄▶ perf-compare
  #     perf-allocprofile  (no edge: profiled throughput is never gated or baselined)
  # Do NOT replace these with `- wait: ~`: a notify-only red would then skip compare
  # and discard a valid baseline run. Rationale: docs/infrastructure/ci-cd.md.
  - label: ":k6: perf regression — run + sample"
    key: "perf-run"
    command: ".buildkite/scripts/steps/perf-test-run.sh"
    # Computed above. The 90-minute base covers the INFO-level arm (PERF_INFO_ARM=false
    # drops it), the path-coverage phase (PERF_COVERAGE=false or a PERF_COVERAGE_ARMS
    # subset trims it), the pipelining A/B (PERF_PIPELINING=false drops it) and the
    # FILE-body A/B (PERF_FILE_BODY_AB=false drops it).
    timeout_in_minutes: @PERF_RUN_TIMEOUT@
    agents:
      queue: "perf"
  - label: ":microscope: perf regression — micro-benchmark + scaling sweep"
    key: "perf-microbench"
    command: ".buildkite/scripts/steps/perf-test-microbench.sh"
    # Module builds plus every JMH invocation in perf-test-microbench.sh; cloud agents
    # run JMH 2-3x slower than a laptop. Adding benchmarks there needs this budget
    # rechecked (budget history: docs/infrastructure/ci-cd.md).
    timeout_in_minutes: 70
    agents:
      queue: "perf"
  - label: ":racing_car: perf regression — HTTP/2 multiplex (issue #2669)"
    key: "perf-h2multiplex"
    command: ".buildkite/scripts/steps/perf-test-h2multiplex.sh"
    # Builds mockserver-netty, boots a real server, and sweeps N=1,10,100 concurrent
    # streams over one h2c connection. NOTIFY-ONLY, no threshold (recorded only); a
    # NON-zero exit means the harness self-validation failed (bad measurement), not a
    # slowdown, so it surfaces as a red build.
    timeout_in_minutes: 30
    agents:
      queue: "perf"
  - label: ":microscope: perf regression — allocation profile (deep JFR)"
    key: "perf-allocprofile"
    command: ".buildkite/scripts/steps/perf-test-allocprofile.sh"
    # Runs perf-test-run.sh with PERF_JVM_DIAGNOSTICS=deep to answer "what allocates?".
    # It is DELIBERATELY NOT a dependency of perf-compare and NOTHING depends on it: its
    # throughput is depressed by the profiling, so it must never be waited on, gated, or
    # baselined. Its artifacts are name-prefixed (PERF_RUN_NAME=allocprofile), so they
    # cannot match compare's exact-name `perf-result.json` download. soft_fail so a
    # throughput number here never reds the build — only a genuine harness fault shows.
    soft_fail: true
    timeout_in_minutes: 30
    agents:
      queue: "perf"
  # No soft_fail: compare exits non-zero on a GATING regression (that red build IS
  # the notification) or a tooling fault; a notify-only flag annotates and exits 0.
  # soft_fail here would swallow the gating signal.
  - label: ":bar_chart: perf regression — persist + compare baseline"
    key: "perf-compare"
    # Waits for microbench so its artifacts exist before the best-effort merge (else
    # the gating .microbench.* rows silently vanish), but only perf-run can skip it.
    # Inside depends_on the property is `allow_failure`; the step-level
    # `allow_dependency_failure` is rejected here and fails the whole upload.
    # Change only with a fresh proof on a throwaway pipeline.
    depends_on:
      - "perf-run"
      - step: "perf-microbench"
        allow_failure: true
      - step: "perf-h2multiplex"
        allow_failure: true
    command: ".buildkite/scripts/steps/perf-test-compare.sh"
    timeout_in_minutes: 10
    agents:
      queue: "perf"
  # Non-gating tail: when the committed figures are stale or a headline moved, emits
  # the refresh as a git format-patch artifact. The hard edge on perf-compare keeps a
  # regressed or unmeasured run off the public page. soft_fail: every refuse-to-publish
  # path exits 1. The perf queue holds only the S3 perf-results grant; GitHub
  # credentials are release-stack only, so this step cannot push or open a PR.
  - label: ":globe_with_meridians: perf regression — publish figures to website (PR)"
    depends_on: "perf-compare"
    command: ".buildkite/scripts/steps/perf-website-publish.sh"
    timeout_in_minutes: 15
    soft_fail: true
    agents:
      queue: "perf"
YAML

# Honour the load-injection and perf-xl opt-ins alongside the standard regression dispatch.
maybe_dispatch_inject
maybe_dispatch_perf_xl

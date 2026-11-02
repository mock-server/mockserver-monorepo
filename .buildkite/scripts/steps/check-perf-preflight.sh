#!/usr/bin/env bash
# Buildkite step wrapper that turns the CI-agnostic release-preflight PERFORMANCE
# gate (scripts/release/check-perf-preflight.sh) into a hard release gate.
#
# WHY A WRAPPER (same split as check-release-credentials.sh)
#   The probe is deliberately CI-agnostic: it reads env vars, reads S3 + the
#   committed budget file, and emits an exit code — no buildkite-agent calls (see
#   docs/operations/release-principles.md #1). This wrapper adds the two CI-specific
#   concerns the probe leaves out: (1) making sure the checkout has enough git
#   history for the ancestry-based RECENCY check, and (2) turning a non-zero outcome
#   into an actionable red build with a Buildkite annotation naming the outcome.
#
# WHICH QUEUE — the `perf` queue, NOT `release`.
#   The gate reads s3://mockserver-ci-perf-results, whose IAM grant lives on the
#   `perf` queue role (the same grant perf-test-compare.sh uses; see
#   terraform/buildkite-agents and the note in perf-baseline-freshness.sh). The
#   `release` queue holds the mockserver-release/* publishing grants but NOT the
#   perf-results read grant. Running this gate anywhere without that grant fails
#   CLOSED as S3_DENIED (exit 3) — a loud block, never a false green — which is the
#   correct direction, but the intended home is the `perf` queue where it simply works.
#
# EXIT-CODE CONTRACT — inherited verbatim from the probe; this wrapper never
# rewrites a pass into a fail or a fail into a pass, it only annotates and propagates:
#   0   valid + not-stale + no unaccepted gating breach       -> gate PASS
#   1   a FINDING (INVALID / UNVERIFIED / STALE / DIVERGENT / BREACH / NO_HISTORY) -> gate FAIL
#   2   INDETERMINATE / MALFORMED (could not conclude)         -> gate FAIL (fail-closed)
#   3   NO_SESSION / S3_DENIED (nothing could be read)         -> gate FAIL (see note)
#   64  bad probe arguments                                    -> gate FAIL (wiring bug)
#
# NOT soft_fail: a release gate that cannot fail the build is decoration. This is
# the gate where "we shipped a 2x slowdown" is meant to be caught.
set -uo pipefail

STEP_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$STEP_DIR/../../.." && pwd)"
PROBE="$REPO_ROOT/scripts/release/check-perf-preflight.sh"

if [[ ! -x "$PROBE" ]]; then
  # A missing/non-executable probe is a pipeline WIRING bug, not a performance
  # finding — use the contract's 64, not 1 (which the contract reserves for findings).
  echo "--- :x: perf preflight gate: probe not found or not executable: $PROBE (wiring bug)" >&2
  exit 64
fi

# The RECENCY check needs the newest run's (older) master commit present in this
# clone. Buildkite often does a shallow clone, and the run commit can be any number
# of commits back, so a fixed `--deepen=N` could still fall short. UNSHALLOW instead
# (fetch full history) so any ancestor is guaranteed present; fall back to a deep
# fetch only if the server refuses --unshallow. Best-effort: if none of this can run
# (offline / not a repo) the probe still fails CLOSED as INDETERMINATE if the run
# commit is absent, so a missing fetch never becomes a false green.
if [[ "$(git -C "$REPO_ROOT" rev-parse --is-shallow-repository 2>/dev/null)" == "true" ]]; then
  echo "--- :git: unshallowing clone so the perf-run commit (any master ancestor) is available for the ancestry check"
  git -C "$REPO_ROOT" fetch --unshallow origin >/dev/null 2>&1 \
    || git -C "$REPO_ROOT" fetch --deepen=100000 origin >/dev/null 2>&1 \
    || echo "    (could not fetch full history; the probe will report INDETERMINATE if the run commit is absent)"
fi

out_file="$(mktemp "${TMPDIR:-/tmp}/perfgate.XXXXXX")"
ann_file="$out_file.ann"
trap 'rm -f "$out_file" "$ann_file"' EXIT

echo "--- :chart_with_downwards_trend: Release-preflight performance gate (reads S3 + perf-budgets.json)"
"$PROBE" 2>&1 | tee "$out_file"
rc="${PIPESTATUS[0]}"

if [[ "$rc" -eq 0 ]]; then
  echo "--- :white_check_mark: Release perf preflight gate PASSED"
  exit 0
fi

case "$rc" in
  1)  headline="a performance FINDING blocks the release — INVALID / UNVERIFIED / STALE / DIVERGENT / BREACH / NO_HISTORY (see the probe output)" ;;
  2)  headline="the performance picture is INDETERMINATE (a gating metric could not be evaluated, git ancestry could not be resolved, or the budget file is unreadable) — failing closed; resolve it and re-run" ;;
  3)  headline="the perf results could not be read (NO_SESSION / S3_DENIED) — this step must run on the perf queue, which holds the perf-results S3 grant" ;;
  64) headline="the probe rejected its arguments (exit 64) — a pipeline WIRING bug, not a performance problem" ;;
  *)  headline="the probe exited with unexpected status $rc" ;;
esac

# Pull the finding/indeterminate/precondition rows out of the captured output so
# the red build names WHAT is wrong, not just "script failed".
detail="$(grep -E '(^  ✗ |^  \? |^NO_SESSION|^S3_DENIED|^NO_HISTORY|^MALFORMED|^INDETERMINATE|^FAIL:|^INCONCLUSIVE:)' "$out_file" || true)"

{
  echo '### :rotating_light: Release performance preflight gate FAILED'
  echo
  echo "**Outcome:** ${headline}."
  echo
  echo "Probe scripts/release/check-perf-preflight.sh exited ${rc}."
  if [[ -n "$detail" ]]; then
    echo
    echo 'What blocked the release:'
    echo
    echo '```'
    printf '%s\n' "$detail"
    echo '```'
  fi
  echo
  echo 'This gate is intentionally NOT soft_fail. It exists so a release cannot publish on top of a stale, invalid, or unaccepted-regressed performance baseline (the "we shipped a 2x slowdown" failure). Remedies: for STALE, start a mockserver-performance-test build on the release commit with `[perf-run]` in its message once the :snapshot images it is served are pushed (the STALE row gives the exact steps); for UNVERIFIED, fix the fault named in the error annotation of its compare step (for example a laptop or per-core presence gate, a metric with no budget entry, or an unreadable history), then run a [perf-run] build on the release commit again; investigate the flagged metric (BREACH), or record a reviewed acceptance in mockserver-performance-test/perf-accepted-regressions.json. The full probe output is in this step log.'
} > "$ann_file"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent annotate --style error --context "release-perf-preflight-gate" < "$ann_file" || true
else
  echo "--- (buildkite-agent not present — annotation body follows)" >&2
  cat "$ann_file" >&2
fi

echo "--- :x: Release perf preflight gate FAILED (exit ${rc})" >&2
exit "$rc"

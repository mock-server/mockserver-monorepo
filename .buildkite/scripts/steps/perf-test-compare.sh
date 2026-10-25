#!/usr/bin/env bash
set -euo pipefail

# Persist + baseline-compare step (perf queue). Runs post-merge on master, not as
# a PR gate. PER-METRIC GATING: a flagged regression on a metric marked
# `gating:true` fails the build (non-zero exit); a flagged regression on a
# `gating:false` metric is reported exactly as loudly in the annotation but does
# NOT change the exit code (informational-pending-history). This is the
# regression notification: a failing pipeline IS the alert (no webhook / channel).
#
# Flow:
#   1. gather this run's result.json (+ perf-microbench.json) and merge them
#   2. persist to s3://<bucket>/runs/<branch>/<iso>__<sha>.json   (history; runs-<queue>/ off the perf queue)
#      With PERF_COMPARE_RESULT_ARTIFACT naming a perf-xl multi-k6 arm result, the run is persisted to
#      runs-perf-xl/ only when it is a member of the published series (lib/perf-rw-multik6-series.jq).
#   3. pull the last N PRIOR runs; if < MIN_BASELINE, annotate "warming up"
#   4. per metric: rolling baseline = median + MAD; flag a regression when the
#      head value crosses max(median + 3·1.4826·MAD, percent-floor / abs-floor).
#      The per-metric dir / min_pct / abs-floor / gating flags are NOT hardcoded
#      here — they are read from the committed, reviewed
#      mockserver-performance-test/perf-budgets.json, so a floor can only be
#      loosened by a reviewed diff. The annotation names that file's last-changed
#      commit. FAIL CLOSED: a missing/corrupt budget file, an unknown budget
#      provenance commit, or a run metric with no budget entry goes RED (exit 1).
#   5. post a Buildkite annotation table; exit non-zero iff a GATING metric flagged
#
# Only metrics with a derived, trustworthy budget start gating (JMH micro-benchmark
# time/alloc per op, and forward.error_rate — a discriminating pass/fail guard).
# Every other metric (k6 latency percentiles, growth ratios, rig_valid_peak_achieved_rps,
# live_set_bytes) runs notify-only until it has >=10 clean runs of history and a
# budget derived from them — a gate that fires on noise gets switched off, which is
# the failure mode this design avoids. See docs/operations/performance-tuning.md
# -> "Regression thresholds".
#
# Robust stats (median/MAD, not mean/stddev) so a single noisy run doesn't move
# the baseline. Latency/CPU/heap/alloc: higher = worse. Throughput: lower = worse.
# Growth slope ratios also get an ABSOLUTE floor (healthy ≈ 1.0) so steady-state
# badness isn't normalised away. Micro-benchmark uses a tighter floor (low noise).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

BUCKET="${PERF_RESULTS_BUCKET:-mockserver-ci-perf-results}"
BASELINE_N="${PERF_BASELINE_N:-10}"
MIN_BASELINE="${PERF_MIN_BASELINE:-5}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-compare.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# A PERF_XL build runs this step twice: for the daily result and for the multi-k6 arm's own. An
# annotation replaces an earlier one with the same context, so each mode has its own contexts.
ANNOTATE_CONTEXT=perf-regression; PERSISTED_KEY_CONTEXT=perf-persisted-key
if [ "${PERF_COMPARE_RESULT_ARTIFACT:-perf-result.json}" != perf-result.json ]; then
  ANNOTATE_CONTEXT=perf-regression-xl; PERSISTED_KEY_CONTEXT=perf-xl-persisted-key
fi
annotate() { # style, body
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context "$ANNOTATE_CONTEXT" || true
  fi
  printf '\n%s\n' "$2"
}

# --- 0. load committed budget floors ------------------------------------------
# The absolute floors used by the compare below live in a committed, reviewed
# JSON file (mockserver-performance-test/perf-budgets.json), NOT hardcoded in
# this jq, so a floor can only be loosened by a reviewed diff. FAIL CLOSED: a
# missing or unparseable budget file, or a run metric with no budget entry, must
# go RED — a silent default of "no floor" would be a false green.
BUDGETS_FILE="${PERF_BUDGETS_FILE:-$REPO_ROOT/mockserver-performance-test/perf-budgets.json}"
if [ ! -f "$BUDGETS_FILE" ]; then
  annotate "error" ":no_entry: **Perf budget file MISSING — cannot compare** — expected \`${BUDGETS_FILE}\`.

The committed absolute floors could not be found, so the regression gate cannot run. This fails the build deliberately (fail-closed): a missing budget must never silently mean \"no floor\"."
  exit 1
fi
if ! jq empty "$BUDGETS_FILE" >/dev/null 2>&1; then
  annotate "error" ":no_entry: **Perf budget file UNPARSEABLE — cannot compare** — \`${BUDGETS_FILE}\` is not valid JSON.

This fails the build deliberately (fail-closed): a corrupt budget file must never silently mean \"no floor\"."
  exit 1
fi
if [ "$(jq -r '(.budgets | type) // "null"' "$BUDGETS_FILE" 2>/dev/null)" != "object" ]; then
  annotate "error" ":no_entry: **Perf budget file has no \`budgets\` object — cannot compare** — \`${BUDGETS_FILE}\`.

This fails the build deliberately (fail-closed): the floors could not be read."
  exit 1
fi

# The checks above validate that the file is READABLE. They say nothing about the
# property the compare below actually leans on: that each budget's numbers are
# NUMBERS and its direction is one this script branches on. A quoted `floor`
# makes `[$med + 3*$sigma, $floor] | max` return the STRING (jq sorts every string
# above every number), so the threshold becomes a string and `$value > $threshold`
# is false forever — the gate reports green and can never fire again. A missing or
# misspelled `dir` silently takes the bigger-is-better branch. Both are false
# all-clears reachable from a one-character edit, so validate the schema, and
# prove the validator still rejects them on every run rather than trusting it.
source "$SCRIPT_DIR/../lib/perf-budgets-validate.sh"
if ! SELF_TEST_OUT="$(bash "$SCRIPT_DIR/../lib/perf-budgets-validate.sh" --self-test 2>&1)"; then
  annotate "error" ":no_entry: **Perf budget validator SELF-TEST FAILED — cannot trust the gate** — the schema check that keeps a mistyped budget from silently disabling a floor does not itself work.

\`\`\`
${SELF_TEST_OUT}
\`\`\`

This fails the build deliberately (fail-closed): a guard that cannot prove it rejects a bad budget must not be relied on to have accepted a good one."
  exit 1
fi
if ! BUDGET_SCHEMA_PROBLEMS="$(validate_perf_budgets "$BUDGETS_FILE")"; then
  annotate "error" ":no_entry: **Perf budget file has INVALID entries — cannot compare** — \`${BUDGETS_FILE}\`.

\`\`\`
${BUDGET_SCHEMA_PROBLEMS}
\`\`\`

This fails the build deliberately (fail-closed). A quoted number or an unrecognised \`dir\` does not error in jq — it silently turns the budget off, which is a false all-clear rather than a visible failure."
  exit 1
fi

# Name the budget file's last-changed commit in the annotation so a silent
# loosening is visible in the build output, not only in git history. If git
# metadata is unavailable (not a checkout) or the file is uncommitted, we CANNOT
# prove the floors were reviewed, so FAIL LOUDLY rather than print a blank or a
# misleading provenance. Override via PERF_BUDGETS_COMMIT for local testing only.
BUDGETS_COMMIT="${PERF_BUDGETS_COMMIT:-}"
if [ -z "$BUDGETS_COMMIT" ]; then
  BUDGETS_COMMIT="$(git -C "$REPO_ROOT" log -1 --format=%H -- "$BUDGETS_FILE" 2>/dev/null || true)"
fi
if [ -z "$BUDGETS_COMMIT" ]; then
  annotate "error" ":no_entry: **Perf budget provenance UNKNOWN — cannot vouch for the floors** — \`git log\` reported no commit for \`${BUDGETS_FILE}\` (not a git checkout, or the file is uncommitted).

The annotation must name the budget file's last-changed commit so a silent loosening is visible. Without it the floors cannot be proven reviewed, so this fails the build deliberately (fail-closed). Set \`PERF_BUDGETS_COMMIT\` only for local testing."
  exit 1
fi

# --- 1. gather this run's result ----------------------------------------------
RESULT="$WORK/result.json"
# The default is the daily run's result. The perf-xl multi-k6 arm's own compare step names its
# arm-only result instead; the daily run's supplementary artifacts are never merged into it.
RESULT_ARTIFACT="${PERF_COMPARE_RESULT_ARTIFACT:-perf-result.json}"
if ! [[ "$RESULT_ARTIFACT" =~ ^[A-Za-z0-9][A-Za-z0-9._-]*\.json$ ]]; then
  annotate "error" ":no_entry: **Perf compare: unusable result artifact name** — \`PERF_COMPARE_RESULT_ARTIFACT\` is \`${RESULT_ARTIFACT}\`."
  exit 1
fi
DEFAULT_RESULT=false; [ "$RESULT_ARTIFACT" = perf-result.json ] && DEFAULT_RESULT=true
if command -v buildkite-agent >/dev/null 2>&1; then
  # FAIL CLOSED, like every other precondition in this script. A missing result is
  # the most fundamental failure there is — there is nothing to compare, nothing to
  # persist, and no way to tell "the run did not measure" from "the run was fine"
  # except by saying so. perf-test-guard.sh's fail-closed perf-run edge should keep
  # this unreachable, but that rests on Buildkite's dependency semantics, so it is loud.
  if ! buildkite-agent artifact download "$RESULT_ARTIFACT" "$WORK/"; then
    annotate "error" ":no_entry: **Perf compare found NO RESULT to compare** — \`${RESULT_ARTIFACT}\` was not uploaded by this build.

The measurement step produced nothing, so there is no run to gate, baseline or publish. This fails the build deliberately (fail-closed): a missing result must never read as a passing comparison."
    echo "ERROR: no ${RESULT_ARTIFACT} artifact" >&2
    exit 1
  fi
  cp "$WORK/$RESULT_ARTIFACT" "$RESULT"
  buildkite-agent artifact download perf-microbench.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-microbench-extra.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-sweep.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-scaling.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-churn.json "$WORK/" 2>/dev/null || true
  buildkite-agent artifact download perf-h2-multiplex.json "$WORK/" 2>/dev/null || true
else
  cp "${PERF_RESULT_FILE:-$REPO_ROOT/perf-result.json}" "$RESULT"
  [ -f "$REPO_ROOT/perf-microbench.json" ] && cp "$REPO_ROOT/perf-microbench.json" "$WORK/perf-microbench.json" || true
  [ -f "$REPO_ROOT/perf-microbench-extra.json" ] && cp "$REPO_ROOT/perf-microbench-extra.json" "$WORK/perf-microbench-extra.json" || true
  [ -f "$REPO_ROOT/perf-sweep.json" ] && cp "$REPO_ROOT/perf-sweep.json" "$WORK/perf-sweep.json" || true
  [ -f "$REPO_ROOT/perf-scaling.json" ] && cp "$REPO_ROOT/perf-scaling.json" "$WORK/perf-scaling.json" || true
  [ -f "$REPO_ROOT/perf-churn.json" ] && cp "$REPO_ROOT/perf-churn.json" "$WORK/perf-churn.json" || true
  [ -f "$REPO_ROOT/perf-h2-multiplex.json" ] && cp "$REPO_ROOT/perf-h2-multiplex.json" "$WORK/perf-h2-multiplex.json" || true
fi
# Arm mode: the perf-xl multi-k6 arm's result, named explicitly. A daily-named result that is an
# arm-only one, or an arm artifact that is not the multi-k6 arm, fails closed rather than being
# baselined against the wrong series.
if ! RUN_ARM="$(jq -er '.run_arm // ""' "$RESULT" 2>/dev/null)"; then
  annotate "error" ":no_entry: **Perf compare: \`${RESULT_ARTIFACT}\` is not readable JSON** — nothing was persisted or compared."
  exit 1
fi
ARM_MODE=false
if [ "$DEFAULT_RESULT" != true ] || [ -n "$RUN_ARM" ]; then
  if [ "$DEFAULT_RESULT" = true ] || [ "$RUN_ARM" != rw_multik6 ]; then
    annotate "error" ":no_entry: **Perf compare: result and artifact disagree — not persisted** — \`${RESULT_ARTIFACT}\` carries \`run_arm\` \`${RUN_ARM:-none}\`. Only the multi-k6 arm's own artifact may carry an arm-only result, and the daily \`perf-result.json\` may not."
    exit 1
  fi
  ARM_MODE=true
fi
PERSISTED_META_KEY=perf-baseline-persisted-key
[ "$ARM_MODE" = true ] && PERSISTED_META_KEY=perf-xl-persisted-key
# An arm-only result stands alone: none of the daily run's supplementary artifacts is merged into it.
if [ "$ARM_MODE" = true ]; then
  rm -f "$WORK"/perf-microbench.json "$WORK"/perf-microbench-extra.json "$WORK"/perf-sweep.json \
        "$WORK"/perf-scaling.json "$WORK"/perf-churn.json "$WORK"/perf-h2-multiplex.json
fi
# Merge micro-benchmark results into the run object if present.
if [ -f "$WORK/perf-microbench.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-microbench.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
# Merge the promoted dark-benchmark results — same shape, under
# `microbench_extra`. Consumed NON-GATING below (notify-only: no baseline history
# yet), so a regression on one annotates but does NOT fail the build.
if [ -f "$WORK/perf-microbench-extra.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-microbench-extra.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
# Persist the throughput-vs-latency sweep + JMH scaling sweep into the stored run
# so the S3 history keeps them. NOTIFY-ONLY: these are recorded for the doc-site
# knee/scaling charts and ad-hoc inspection — there is no baseline comparison or
# pass/fail gate on them (the regression compare below is unchanged). perf-sweep
# is also already embedded under .sweep by perf-test-run.sh; re-merging the
# standalone artifact is harmless (same object) and robust if the embed is absent.
if [ -f "$WORK/perf-sweep.json" ]; then
  jq -s '.[0] + {sweep: .[1]}' "$RESULT" "$WORK/perf-sweep.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
if [ -f "$WORK/perf-scaling.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-scaling.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
# G1 churn gate. Merges
# {churn:{alloc_ratio_index_n15000, ...}} into the run. UNLIKE .scaling above, this block IS
# enumerated and budgeted below, so churn.alloc_ratio_index_n15000 GATES (a rebuild-on-read
# regression moves the churn/static allocation ratio by ~3 orders of magnitude). Best-effort
# download like the others; if the microbench step failed to produce it, that step reds on its
# own (run-g1-churn.sh / the microbench artifact check) — the value is simply absent here and
# drops out of $headmetrics rather than tripping the fail-closed missing-budget rule.
if [ -f "$WORK/perf-churn.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-churn.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi
# HTTP/2 multiplex benchmark (issue #2669). Persisted into the S3 run history for
# trend visibility. NOTIFY-ONLY on both axes (its own harness self-validation already
# fails the run step loudly on a bad measurement; run-to-run variance on real agents
# is not yet known, so setting a build-FAILING threshold now would be guessing). The
# TWO axes are consumed differently by the `metrics` jq below: the THROUGHPUT axis
# (.h2_multiplex) is recorded but NOT read there, so it never flags; the PER-CONNECTION
# MEMORY axis (.h2_connection_memory) IS read there (surfaced notify-only per
# run against its h2_connection_memory.*.bytes_per_connection budget), so it annotates
# a move but — being non-gating — cannot fail the build.
if [ -f "$WORK/perf-h2-multiplex.json" ]; then
  jq -s '.[0] * .[1]' "$RESULT" "$WORK/perf-h2-multiplex.json" > "$WORK/merged.json" && mv "$WORK/merged.json" "$RESULT"
fi

# `.commit` is the ATTRIBUTED commit (schema_version >= 3: the measured binary's own
# org.opencontainers.image.revision when it is a well-formed SHA, else the harness
# commit during the provenance grace window — see perf-test-run.sh). This is the right
# thing to key stored history by: a result describes the binary it measured. The
# Buildkite build / harness-scripts commit is carried separately as `.harness_commit`.
# The KEY's leading timestamp makes it unique per run, so two runs of the SAME image
# revision (a quiet master re-measured) never collide. Baseline windowing sorts on that
# timestamp too, so the change from a harness-commit to an image-revision suffix does
# not affect which runs are selected. NOTE the meaning boundary: pre-v3 objects carry
# the harness commit here; the metrics stay comparable across it (compare keys metrics
# on the k6/JMH fingerprints, never on `.commit`).
BRANCH="$(jq -r '.branch // "unknown"' "$RESULT")"
COMMIT="$(jq -r '.commit // "unknown"' "$RESULT")"
TS="$(jq -r '.timestamp_utc // "unknown"' "$RESULT")"
# Each agent queue keeps its own history: a run from another queue (perf-xl) is persisted under
# runs-<queue>/ and windows only against that prefix, so it can neither enter nor displace the perf
# queue's window. perf keeps runs/, where perf-website-publish.sh reads. A result with no queue predates the field (perf).
RUN_QUEUE="$(jq -r '.agent.queue // "perf"' "$RESULT")"
if ! [[ "$RUN_QUEUE" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  annotate "error" ":no_entry: **Perf result names an unusable agent queue — build FAILED, not baselined** — \`.agent.queue\` is \`${RUN_QUEUE}\`, which cannot select a history prefix, so the run cannot be kept apart from another queue's baseline."
  exit 1
fi
HISTORY_PREFIX="runs"; [ "$RUN_QUEUE" = "perf" ] || HISTORY_PREFIX="runs-${RUN_QUEUE}"
KEY="${HISTORY_PREFIX}/${BRANCH}/${TS//:/-}__${COMMIT:0:10}.json"

# --- 1b. validity gate: refuse to baseline a compromised measurement ---------
# The run's own `validity` block records whether the measurement RIG was sound
# (client had CPU headroom, k6 dropped no iterations, resource samples captured,
# latency metrics present). Baselining an invalid run would poison the rolling
# median with garbage, so REFUSE to persist or compare it. A run produced by
# perf-test-run.sh always carries the block; an ABSENT block (older producer or a
# truncated run) is treated as invalid on purpose.
#
# FAIL THE BUILD (exit 1), do not exit 0: a failing pipeline IS the notification,
# so a green daily build must mean "measured, and OK", and an invalid run measured
# nothing trustworthy. A transient rig blip can fire an unactionable red, but this is
# a daily run, agent loss is already auto-retried, and a recurring failure is
# actionable; a green invalid run silently stales the baseline, which is worse.
VALID="$(jq -r '.validity.valid // "absent"' "$RESULT")"
if [ "$VALID" != "true" ]; then
  FAILED_CHECKS="$(jq -r '
    (.validity.checks // []) | map(select(.ok != true))
    | if length == 0 then "- (no validity block present in this run)"
      else map("- **\(.name)**: \(.detail)") | join("\n") end' "$RESULT")"
  annotate "error" ":no_entry: **Perf run INVALID — build FAILED, not baselined** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

This run's \`validity\` block is absent or false, so it was **not persisted to the baseline history and not compared** — a compromised measurement must not poison the rolling median (the inject harness's discipline: exclude a bad point, don't report it). **This fails the build** so a run that could not measure anything is loud, not a green square that misrepresents it as OK.

Failing checks:
${FAILED_CHECKS}"
  exit 1
fi

# --- 1b-ii. content-plausibility gate (the freshness rule, applied to the object) ---
# The validity gate above TRUSTS the run's self-declared `.validity.valid` flag. That
# is necessary but not sufficient: a producer bug, a truncated/partial upload, a hand-
# seeded object, or a future producer that forgets a check can set `validity.valid:true`
# on output that is structurally-valid JSON but carries NO measured content. Such an
# object sails through the validity gate, then $headmetrics (all-null, dropped by
# `select(.value != null)`) is empty, $missing is empty, no row is produced, and the
# build goes GREEN — "No performance regressions" against a run that measured nothing —
# AND the empty object is persisted, so it silently enters every later baseline window
# (where bmapof drops its nulls, shrinking the comparison set invisibly). This is the
# exact decay this check exists to catch: a
# freshness check that keys off object age/liveness "passes forever against a producer
# writing valid empty JSON every day", so the newest object must be asserted to contain
# the expected keys with NON-NULL values in PLAUSIBLE RANGES — the same plausibility
# rule the producer applies to itself, applied here independently of what the run
# CLAIMS. The dedicated freshness watchdog (perf-baseline-freshness.sh, pipeline-infra.yml)
# CANNOT do this — the trigger queue has no perf-bucket S3 read — so this reader step,
# which already has the object in hand, is the architecturally correct place for it.
#
# Scoped to the CORE metric family every valid run must carry (the k6 regression
# behaviours' p95 latency — the producer's own `regression_metrics_present` validity
# check guarantees it) plus RANGE sanity on the two headline scalars WHEN present.
# Deliberately NOT asserting the optional profiles (clustered_state / laptop /
# streaming / serving_percore / forward on infra_error) — those legitimately emit
# zero metrics on a skip and must not red here.
if [ "$ARM_MODE" = true ]; then
  # An arm-only result has no behaviours; its content is the arm's headline ceiling.
  PLAUSIBILITY_PROBLEMS="$(jq -r '
    (.serving_rw_multik6.headline.healthy_ceiling_rps // null) as $hc
    | if ($hc | type) != "number" or $hc <= 0 or $hc > 100000000
      then "serving_rw_multik6.headline.healthy_ceiling_rps is \($hc | tojson) (expected a number in 0 < rps <= 1e8)" else empty end' \
    "$RESULT" 2>/dev/null || echo "result.json could not be read for plausibility checking")"
else
PLAUSIBILITY_PROBLEMS="$(jq -r '
  [ # 1. at least one behaviour arm with a plausible p95_ms (0 < p95 < 600000 ms).
    #    An empty/all-null .behaviours yields zero — the structurally-empty case.
    ( ((.behaviours // {}) | to_entries
       | map(select((.value.p95_ms | type) == "number" and .value.p95_ms > 0 and .value.p95_ms < 600000))
       | length) as $ok
      | if $ok == 0 then "no behaviour arm carries a plausible p95_ms (expected 0 < p95 < 600000 ms) — the newest object records no request latency, i.e. it measured nothing" else empty end ),
    # 2. rig_valid_peak_achieved_rps, when present, must be a positive, non-absurd rate.
    ( if (.rig_valid_peak_achieved_rps != null)
         and (((.rig_valid_peak_achieved_rps | type) != "number") or .rig_valid_peak_achieved_rps <= 0 or .rig_valid_peak_achieved_rps > 100000000)
      then "rig_valid_peak_achieved_rps present but implausible: \(.rig_valid_peak_achieved_rps) (expected a number in 0 < rps <= 1e8)" else empty end ),
    # 3. forward_guard.error_rate, when present, must be a fraction in [0,1].
    ( if ((.forward_guard // {}).error_rate != null)
         and (((.forward_guard.error_rate | type) != "number") or .forward_guard.error_rate < 0 or .forward_guard.error_rate > 1)
      then "forward_guard.error_rate present but implausible: \((.forward_guard // {}).error_rate) (expected a number in [0,1])" else empty end )
  ] | .[]' "$RESULT" 2>/dev/null || echo "result.json could not be read for plausibility checking")"
fi
if [ -n "$PLAUSIBILITY_PROBLEMS" ]; then
  PLAUSIBILITY_LIST="$(printf '%s\n' "$PLAUSIBILITY_PROBLEMS" | sed 's/^/- /')"
  annotate "error" ":no_entry: **Perf run IMPLAUSIBLE — build FAILED, not baselined** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

This run's \`validity.valid\` was \`true\`, but the newest result object does **not** contain the expected metric keys with non-null values in plausible ranges, so it was **not persisted to the baseline history and not compared**. A structurally-valid-but-empty object that a producer nonetheless marks valid must never be baselined — it would read GREEN as \"no regressions\" while measuring nothing, then silently shrink every later baseline window (the \"valid empty JSON every day passes forever\" decay this gate exists to stop). This is checked HERE, independently of the run's own \`validity\` flag, because the dedicated freshness watchdog cannot read the object from the trigger queue.

Implausible/absent:
${PLAUSIBILITY_LIST}"
  exit 1
fi

# --- 1b-iii. image-freshness gate (close the frozen-snapshot false green) -----
# The provenance model files a result under the measured binary's own commit, which
# (correctly) means an image that LAGS master is no longer a failure. But that removes
# the only alarm for a distinct failure mode: a FROZEN snapshot tag. If the master-gated
# java-docker-push-snapshot.sh stops rebuilding the mutable tag (pipeline red for days, or
# the push breaks), the daily run measures the SAME binary every day, files every point
# under the same truthfully-labelled old commit, and provenance_ok reads true forever — a
# green chain measuring nothing new. The producer stamps `config.image_stale` from the
# image's own .Created age (PERF_MAX_IMAGE_AGE_DAYS); a stale image is a valid measurement
# of that binary but NOT a fresh daily-cadence data point, so this reds the build and does
# NOT persist it. RED (not baseline_eligible:false which exits green) because the freshness
# watchdog (perf-baseline-freshness.sh) explicitly cannot see a green-but-frozen run — it
# has no perf-bucket S3 read — so a green here would leave this failure mode with no
# reachable alarm. Placed BEFORE the baseline-eligibility green-exit so a stale run reds
# even when it is also a deep-diagnostics run. A run with no `image_stale` field (older
# producer / schema < 3) defaults to not-stale and is exempt — existing behaviour byte-for-
# byte. This is distinct from the validity block (rig soundness) on purpose: the rig was
# sound, the INPUT binary was stale.
IMAGE_STALE="$(jq -r '.config.image_stale // false' "$RESULT")"
if [ "$IMAGE_STALE" = "true" ]; then
  IMG_AGE="$(jq -r '.config.image_age_days // "unknown"' "$RESULT")"
  IMG_MAX="$(jq -r '.config.image_max_age_days // "?"' "$RESULT")"
  IMG_CREATED="$(jq -r '.config.image_created // "?"' "$RESULT")"
  annotate "error" ":no_entry: **Perf run measured a STALE image — build FAILED, not baselined** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The SUT image's own build age (\`.Created\` = \`${IMG_CREATED}\`, **${IMG_AGE}d** old) exceeds the freshness bound (\`PERF_MAX_IMAGE_AGE_DAYS\` = \`${IMG_MAX}\`), OR its age/threshold could not be determined (fail-closed). This is the **frozen-snapshot** signal: the mutable \`mockserver-snapshot-graaljs\` tag is rebuilt on every master merge, so a measured image older than the bound means the snapshot pipeline has **stopped** — the daily run would otherwise measure the same binary every day under a truthfully-labelled old commit and report green while learning nothing new. The run was **not persisted to the baseline history and not compared**. This is checked in the producer because the freshness watchdog cannot see a green-but-frozen run (no perf-bucket S3 read on its queue). **Investigate the \`java\` snapshot pipeline / the image push**, then re-run; set \`PERF_MAX_IMAGE_AGE_DAYS\` higher only for a deliberate slow-factory window."
  exit 1
fi

# --- 1c. baseline eligibility: keep an instrumented run out of the series ---------
# A PERF_JVM_DIAGNOSTICS=deep run is a VALID measurement (it passed the validity gate
# above) but tier-2 instrumentation (GC file logging / NMT / JFR) depresses throughput
# BY DESIGN, so persisting it would silently shift the baseline series the tier split
# exists to protect. Unlike an invalid run this is NOT a failure — the investigation
# run was triggered on purpose — so annotate and exit 0 (GREEN) WITHOUT persisting or
# comparing. Placed AFTER the validity gate so an invalid deep run still reds; a run
# with no baseline_eligible field (older producer) defaults to eligible, unchanged.
# An arm-only result is never baseline_eligible; the multi-k6 arm's is persisted when it is a member
# of the published series instead. A trial, another rule or another rig is recorded, green.
if [ "$ARM_MODE" = true ]; then
  SERIES_JSON="$(jq -c -f "$SCRIPT_DIR/lib/perf-rw-multik6-series.jq" "$RESULT" 2>/dev/null)" || SERIES_JSON=""
  if ! jq -es 'length == 1 and (.[0] | type) == "array"' <<<"$SERIES_JSON" >/dev/null 2>&1; then
    annotate "error" ":no_entry: **Perf compare: the multi-k6 series check failed to run — not persisted** — \`lib/perf-rw-multik6-series.jq\` errored on \`${RESULT_ARTIFACT}\`."
    exit 1
  fi
  # Membership is the empty array itself, not an empty rendering of it.
  if ! jq -e '. == []' <<<"$SERIES_JSON" >/dev/null 2>&1; then
    SERIES_UNMET="$(jq -r '.[] | tostring' <<<"$SERIES_JSON" 2>/dev/null || echo "(unreadable: ${SERIES_JSON})")"
    annotate "info" ":microscope: **Multi-k6 arm recorded, not persisted — not a member of the published series** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The published headline comes only from default, non-trial \`gc_masked_p99\` runs of the arm on \`perf-xl\`, so this run was **not persisted to \`${HISTORY_PREFIX}/${BRANCH}/\`** and cannot reach the website. Unmet:
$(sed 's/^/- /' <<<"$SERIES_UNMET")

Its numbers are in the \`${RESULT_ARTIFACT}\` artifact."
    exit 0
  fi
  # The history holds only what the page may show: a member whose headline disagrees with its own
  # rungs is a producer fault, so it fails here instead of being persisted and refused at publish.
  RW_PROBLEMS_JSON="$(jq -c --arg now "" -f "$SCRIPT_DIR/lib/perf-website-figures-rw.jq" "$RESULT" 2>/dev/null | jq -c '.problems' 2>/dev/null)" || RW_PROBLEMS_JSON=""
  if ! jq -es '. == [[]]' <<<"$RW_PROBLEMS_JSON" >/dev/null 2>&1; then
    annotate "error" ":no_entry: **Multi-k6 result is not self-consistent — build FAILED, not persisted** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

\`lib/perf-website-figures-rw.jq\` could not restate the headline \`${RESULT_ARTIFACT}\` gives from its own rungs, so the run was **not persisted to \`${HISTORY_PREFIX}/${BRANCH}/\`**:
$(jq -rs 'if length == 1 and (.[0] | type) == "array" and (.[0] | length) > 0 then .[0][] | "- \(.)" else error("no list") end' <<<"$RW_PROBLEMS_JSON" 2>/dev/null || echo "- the transform errored or returned no problems list")"
    exit 1
  fi
  ELIGIBLE=true
else
  ELIGIBLE="$(jq -r 'if has("baseline_eligible") then .baseline_eligible else true end' "$RESULT")"
fi
if [ "$ELIGIBLE" != "true" ]; then
  DIAG_TIER="$(jq -r '.config.jvm_diagnostics // "?"' "$RESULT")"
  CFG_PROFILE="$(jq -r '.config.config_profile // "?"' "$RESULT")"
  RIG_PROFILE_HEAD="$(jq -r '.config.rig_profile // "?"' "$RESULT")"
  annotate "info" ":microscope: **Perf run recorded, not baselined — non-default run** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

This run set \`baseline_eligible: false\` (\`PERF_JVM_DIAGNOSTICS=${DIAG_TIER}\`, \`config_profile=${CFG_PROFILE}\`, \`rig_profile=${RIG_PROFILE_HEAD}\`): instrumentation, a tuned server, a non-default rig, an opt-in workload or a past-release comparison each measure something other than the default-configuration series, so it was **not persisted to the baseline history and not compared**. This is expected for a deliberate run, so the build stays **green**; its numbers are in the \`perf-result.json\` artifact."
  exit 0
fi

# --- 2. persist this run to S3 (history) --------------------------------------
# FATAL on failure: a green build that stored nothing silently stops the baseline
# refreshing while every later comparison reports OK against an ever-staler window.
HAVE_AWS=false
if command -v aws >/dev/null 2>&1 && [ -z "${PERF_BASELINE_DIR:-}" ]; then
  HAVE_AWS=true
  if ! aws s3 cp "$RESULT" "s3://${BUCKET}/${KEY}" --only-show-errors; then
    annotate "error" ":no_entry: **Perf run NOT persisted — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

Writing this run to \`s3://${BUCKET}/${KEY}\` failed, so it was **not stored in the baseline history**. Left green, the baseline would stop refreshing while comparisons kept reporting OK against a stale window (silent rot). This fails the build deliberately so the storage failure is fixed, not accumulated. Check S3 permissions / bucket / connectivity from the perf agent."
    exit 1
  fi
fi
# This build's publish step refreshes the website only from a build that persisted a run (the key says which).
if [ "$HAVE_AWS" = true ] && command -v buildkite-agent >/dev/null 2>&1; then
  if ! buildkite-agent meta-data set "$PERSISTED_META_KEY" "$KEY"; then
    printf '%s\n' ":warning: **Perf run persisted, but the publish step was not told** — \`buildkite-agent meta-data set ${PERSISTED_META_KEY}\` failed, so this build's website publish step will not refresh the figures from \`${KEY}\` (in a scheduled build it soft-fails)." \
      | buildkite-agent annotate --style warning --context "$PERSISTED_KEY_CONTEXT" || true
  fi
fi

# --- 2b. laptop profile PRESENCE assertion ---------------------------
# Two axes: laptop VALUES stay notify-only, but
# laptop PRESENCE is loud. `laptop_attempted:true` means the producer tried to
# measure the profile; if `.laptop` is then empty or missing a docker sub-item, the
# measurement failed wholesale (renamed image, docker/create error, producer
# traceback) and would otherwise vanish as a green build — compare is head-driven,
# so zero laptop metrics trip nothing. Unlike microbench_extra (whose drift is caught
# upstream by perf-test-microbench.sh asserting an exact row count), the laptop
# producer only warns to stderr, so THIS is the equivalent catch. The docker
# sub-items have NO JDK dependency, so their absence is unambiguously a rig failure
# and safe to RED on. Runs AFTER the S3 persist above, so a laptop failure never
# costs the k6 result its place in the baseline history. A run with no
# `laptop_attempted` (profile disabled, or an older producer) is exempt.
LAPTOP_INJVM_NOTE=""
if [ "$(jq -r '.laptop_attempted // false' "$RESULT")" = "true" ]; then
  MISSING_DOCKER="$(jq -r '(["docker_ready","mem_256m","mem_512m","mem_1g","image"] - ((.laptop // {}) | keys)) | join(", ")' "$RESULT")"
  if [ -n "$MISSING_DOCKER" ]; then
    annotate "error" ":no_entry: **Laptop profile FAILED to produce — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The run set \`laptop_attempted: true\` but its \`.laptop\` block is missing docker sub-item(s): **${MISSING_DOCKER}**. These have no JDK dependency, so their absence means the laptop measurement (\`bench_laptop.py all\`) failed wholesale — a renamed image, a \`docker create\`/\`docker run\` error, or a producer traceback. Compare is head-driven, so a missing block would otherwise emit zero laptop metrics and pass GREEN with no signal — the false green this gate exists to stop (the programme's thesis: a failing pipeline IS the alert). The run itself was still persisted to the baseline history above, so the k6 result is not lost. Notify-only laptop VALUES are unaffected — this gate is about PRESENCE, not regression."
    exit 1
  fi
  # in-JVM sub-items (injvm/init_*) additionally need a JDK + the image-extracted
  # jar, so they are best-effort: surface their absence VISIBLY in the annotation
  # (a stderr note nobody reads is not surfacing it), but do NOT fail the build.
  MISSING_INJVM="$(jq -r '(["injvm","init_0","init_1000","init_10000"] - ((.laptop // {}) | keys)) | join(", ")' "$RESULT")"
  if [ -n "$MISSING_INJVM" ]; then
    LAPTOP_INJVM_NOTE="

:information_source: **Laptop in-JVM sub-items skipped this run** — \`${MISSING_INJVM}\` absent. \`bench_laptop.py\` skips 8b/8c when a JDK or the image-extracted jar is unavailable on the perf agent; the docker sub-items were present, so this is a best-effort skip, not a failure — but the in-JVM start figure (what a MockServerExtension suite pays per test class) is missing this run."
    echo "$LAPTOP_INJVM_NOTE"
  fi
fi

# --- 2c. serving per-core PRESENCE assertion ------------------------
# Same two-axis split as the laptop gate: serving_percore VALUES stay notify-only,
# but PRESENCE is loud. `serving_percore_attempted:true` means the producer tried
# to run the per-core sweep; if `.serving_percore` then carries NEITHER a measured
# point NOR a skip reason, the whole profile failed wholesale (docker/probe error,
# k6 unreachable, a producer crash) and would otherwise vanish as a green build,
# since compare is head-driven and zero serving_percore metrics trip nothing. A run
# that legitimately measured nothing still records WHY under .skipped (e.g. every C
# infeasible on a small box), so "points + skipped both empty" is the unambiguous
# wholesale-failure signal. Runs AFTER the S3 persist so this never costs the k6
# result its baseline place. A run with no `serving_percore_attempted` (profile
# disabled — the default — or an older producer) is exempt.
# PC_NOTE is BUILT here and folded into EXTRA below (like CLU_NOTE / STREAM_NOTE),
# NOT echo'd — an echo lands only in the raw job log, so a dark skip would read as a
# clean pass in the Buildkite annotation (the exact false green this note prevents).
PC_NOTE=""
if [ "$(jq -r '.serving_percore_attempted // false' "$RESULT")" = "true" ]; then
  PC_POINTS="$(jq -r '(.serving_percore.points // []) | length' "$RESULT")"
  PC_SKIPPED="$(jq -r '(.serving_percore.skipped // []) | length' "$RESULT")"
  # Failure-typed skips (pin proof failed, SUT never ready, sweep produced no points)
  # are RIG FAILURES; infeasible-typed skips (C too big for the box) are expected.
  # A run that measured NOTHING is only benign when every skip is infeasibility —
  # zero points with any failure skip (or with no skip at all) is a broken profile
  # that must go RED, not a green build with the failure buried in stdout.
  PC_FAIL_SKIPS="$(jq -r '[(.serving_percore.skipped // [])[] | select((.type // "") == "failure")] | length' "$RESULT")"
  PC_ERROR="$(jq -r '.serving_percore.error_detail // .serving_percore.error // empty' "$RESULT")"
  if [ "$PC_POINTS" = "0" ] && { [ "$PC_SKIPPED" = "0" ] || [ "$PC_FAIL_SKIPS" != "0" ] || [ -n "$PC_ERROR" ]; }; then
    FAIL_DETAIL="$(jq -r '[(.serving_percore.error_detail // .serving_percore.error // empty), ((.serving_percore.skipped // [])[] | select((.type // "") == "failure") | "C="+(.cores|tostring)+" ("+.reason+")")] | join("; ")' "$RESULT")"
    annotate "error" ":no_entry: **Serving per-core profile FAILED to produce — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The run set \`serving_percore_attempted: true\` but its \`.serving_percore\` block measured NO core-count and its only skips (if any) are RIG FAILURES, not infeasibility${FAIL_DETAIL:+: **${FAIL_DETAIL}**}. That means the per-core sweep (\`lib/perf-percore.sh\`, item 18) failed wholesale — a docker/probe error, an unreachable k6, a pin-proof mismatch, or a producer crash — rather than measuring or DELIBERATELY skipping (infeasible) any core-count. Compare is head-driven, so a missing block would otherwise emit zero serving_percore metrics and pass GREEN with no signal — the false green this gate exists to stop. The run was still persisted to the baseline history above, so the k6 result is not lost. Notify-only serving_percore VALUES are unaffected — this gate is about PRESENCE, not regression."
    exit 1
  fi
  # A curve that stops before C=16 is EXPECTED on a box that cannot host it, but it
  # must be VISIBLE IN THE ANNOTATION, not silent: surface the ceiling of
  # the ladder and any skipped rungs so an early-ending curve never reads as
  # "measured to 16". Any failure-typed skip alongside measured points is called out
  # separately (a rung broke mid-run even though others succeeded).
  PC_MAXC="$(jq -r '.serving_percore.max_cores_measured // 0' "$RESULT")"
  if [ "$PC_SKIPPED" != "0" ]; then
    PC_SKIP_LIST="$(jq -r '[.serving_percore.skipped[] | "C="+(.cores|tostring)+" ["+(.type // "?")+"] ("+.reason+")"] | join("; ")' "$RESULT")"
    PC_NOTE="

:information_source: **Serving per-core curve stops at C=${PC_MAXC}** (item 18) — skipped: ${PC_SKIP_LIST}. Measured ${PC_POINTS} core-count(s). Infeasibility is the item-18 prerequisite (a box cannot pin C server cores AND leave the k6 client disjoint cores once C approaches the host core count)."
    if [ "$PC_FAIL_SKIPS" != "0" ]; then
      PC_NOTE="${PC_NOTE} :warning: ${PC_FAIL_SKIPS} of those are RIG FAILURES, not infeasibility — investigate."
    fi
  fi
fi

# --- 2d. serving multi-process PRESENCE assertion (client rig) -------
# Mirror of the serving_percore presence gate (2c): serving_multiproc VALUES stay
# notify-only, but PRESENCE is loud. `serving_multiproc_attempted:true` means the
# producer tried to run the multi-process sweep; if `.serving_multiproc` then carries
# NEITHER a measured point NOR a skip reason (or its ONLY skips are RIG FAILURES),
# the whole profile failed wholesale (docker/probe error, k6 unreachable, a producer
# crash) and would otherwise vanish as a green build, since compare is head-driven
# and zero serving_multiproc metrics trip nothing. A run that legitimately measured
# nothing still records WHY under .skipped (e.g. every N infeasible on a small box),
# so "points + skipped both empty" (or "points empty with a failure skip") is the
# unambiguous wholesale-failure signal. Runs AFTER the S3 persist so this never costs
# the k6 result its baseline place. A run with no `serving_multiproc_attempted`
# (profile disabled — the default — or an older producer) is exempt. MP_NOTE is BUILT
# here and folded into EXTRA below (like PC_NOTE / CLU_NOTE), NOT echo'd — an echo
# lands only in the raw job log, so a dark skip would read as a clean pass in the
# annotation (the exact false green this note prevents).
MP_NOTE=""
if [ "$(jq -r '.serving_multiproc_attempted // false' "$RESULT")" = "true" ]; then
  MP_POINTS="$(jq -r '(.serving_multiproc.points // []) | length' "$RESULT")"
  MP_SKIPPED="$(jq -r '(.serving_multiproc.skipped // []) | length' "$RESULT")"
  # Failure-typed skips (SUT never ready, no process produced points) are RIG
  # FAILURES; infeasible-typed skips (N too big for the box) are expected. A run that
  # measured NOTHING is only benign when every skip is infeasibility.
  MP_FAIL_SKIPS="$(jq -r '[(.serving_multiproc.skipped // [])[] | select((.type // "") == "failure")] | length' "$RESULT")"
  MP_ERROR="$(jq -r '.serving_multiproc.error_detail // .serving_multiproc.error // empty' "$RESULT")"
  if [ "$MP_POINTS" = "0" ] && { [ "$MP_SKIPPED" = "0" ] || [ "$MP_FAIL_SKIPS" != "0" ] || [ -n "$MP_ERROR" ]; }; then
    MP_FAIL_DETAIL="$(jq -r '[(.serving_multiproc.error_detail // .serving_multiproc.error // empty), ((.serving_multiproc.skipped // [])[] | select((.type // "") == "failure") | "N="+(.procs|tostring)+" ("+.reason+")")] | join("; ")' "$RESULT")"
    annotate "error" ":no_entry: **Serving multi-process profile FAILED to produce — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The run set \`serving_multiproc_attempted: true\` but its \`.serving_multiproc\` block measured NO process-count and its only skips (if any) are RIG FAILURES, not infeasibility${MP_FAIL_DETAIL:+: **${MP_FAIL_DETAIL}**}. That means the multi-process sweep (\`mockserver-performance-test/scripts/multi-process-sweep.sh\`, item 18 client rig) failed wholesale — a docker/probe error, an unreachable k6, or a producer crash — rather than measuring or DELIBERATELY skipping (infeasible) any process-count. Compare is head-driven, so a missing block would otherwise emit zero serving_multiproc metrics and pass GREEN with no signal — the false green this gate exists to stop. The run was still persisted to the baseline history above, so the k6 result is not lost. Notify-only serving_multiproc VALUES are unaffected — this gate is about PRESENCE, not regression."
    exit 1
  fi
  # A sweep that measured points reports its process-count ladder, its scaling
  # verdict and per-N limiter in the annotation so a reader sees WHERE the ceiling
  # came from (and whether it scaled with N) without opening the artifact.
  MP_MEASURED="$(jq -r '(.serving_multiproc.procs_measured // []) | join(",")' "$RESULT")"
  MP_SCALES="$(jq -r '.serving_multiproc.scaling.scales_with_procs // "insufficient_points"' "$RESULT")"
  MP_LIMITED="$(jq -r '[(.serving_multiproc.points // [])[] | "N="+(.procs|tostring)+":"+(.limited_by // "?")] | join(" ")' "$RESULT")"
  MP_NOTE="

:information_source: **Serving multi-process sweep** (item 18 client rig) — measured N=[${MP_MEASURED}], scales_with_procs=${MP_SCALES}. Per-N limiter: ${MP_LIMITED}. Aggregate healthy ceiling + scaling verdict are NOTIFY-ONLY."
  if [ "$MP_SKIPPED" != "0" ]; then
    MP_SKIP_LIST="$(jq -r '[.serving_multiproc.skipped[] | "N="+(.procs|tostring)+" ["+(.type // "?")+"] ("+.reason+")"] | join("; ")' "$RESULT")"
    MP_NOTE="${MP_NOTE} Skipped: ${MP_SKIP_LIST}."
    if [ "$MP_FAIL_SKIPS" != "0" ]; then
      MP_NOTE="${MP_NOTE} :warning: ${MP_FAIL_SKIPS} of those are RIG FAILURES, not infeasibility — investigate."
    fi
  fi
fi

# --- 2e. hardware matrix PRESENCE assertion --------------------------
# Mirror of 2c for .serving_hw_matrix: VALUES are notify-only, PRESENCE is loud. An
# attempted matrix with no measured point and no infeasible-only skip failed
# wholesale; so did one whose points ALL lack a healthy ceiling with none OOM-killed (the
# page would publish nothing). An out-of-memory point (container or Java) is a MEASUREMENT.
HWM_NOTE=""
if [ "$(jq -r '.serving_hw_matrix_attempted // false' "$RESULT")" = "true" ]; then
  HWM_POINTS="$(jq -r '(.serving_hw_matrix.points // []) | length' "$RESULT")"
  HWM_SKIPPED="$(jq -r '(.serving_hw_matrix.skipped // []) | length' "$RESULT")"
  HWM_FAIL_SKIPS="$(jq -r '[(.serving_hw_matrix.skipped // [])[] | select((.type // "") == "failure")] | length' "$RESULT")"
  HWM_USABLE="$(jq -r '[(.serving_hw_matrix.points // [])[] | select(.healthy_ceiling_rps != null or .oom_killed == true or .java_out_of_memory == true
             or (.status // "") == "java_out_of_memory"
             or ((.status // "") == "sut_died" and ((.sut_state.java_oom_errors // 0) > 0)))] | length' "$RESULT")"
  if [ "$HWM_POINTS" != "0" ] && [ "$HWM_USABLE" = "0" ]; then
    annotate "error" ":no_entry: **Hardware matrix measured NOTHING usable — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The run set \`serving_hw_matrix_attempted: true\` and produced ${HWM_POINTS} point(s), but none has a healthy ceiling and none was OOM-killed ($(jq -r '[(.serving_hw_matrix.points // [])[] | .key + "=" + (.status // "?")] | join(", ")' "$RESULT")). Every rung failed the ceiling rule, which points at the rig, not at MockServer, and the website would publish no table. The run was still persisted to the baseline history above."
    exit 1
  fi
  HWM_ERROR="$(jq -r '.serving_hw_matrix.error_detail // .serving_hw_matrix.error // empty' "$RESULT")"
  if [ "$HWM_POINTS" = "0" ] && { [ "$HWM_SKIPPED" = "0" ] || [ "$HWM_FAIL_SKIPS" != "0" ] || [ -n "$HWM_ERROR" ]; }; then
    HWM_FAIL_DETAIL="$(jq -r '[(.serving_hw_matrix.error_detail // .serving_hw_matrix.error // empty), ((.serving_hw_matrix.skipped // [])[] | select((.type // "") == "failure") | (.key // ((.cores|tostring)+"c"))+" ("+.reason+")")] | join("; ")' "$RESULT")"
    annotate "error" ":no_entry: **Hardware matrix profile FAILED to produce — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

The run set \`serving_hw_matrix_attempted: true\` but its \`.serving_hw_matrix\` block measured NO point and its only skips (if any) are RIG FAILURES${HWM_FAIL_DETAIL:+: **${HWM_FAIL_DETAIL}**}. The matrix (\`lib/perf-percore.sh\` in hw_matrix mode, item 27) failed wholesale, so it would otherwise pass GREEN with no figures. The run was still persisted to the baseline history above. Notify-only serving_hw_matrix VALUES are unaffected — this gate is about PRESENCE, not regression."
    exit 1
  fi
  HWM_SUMMARY="$(jq -r '[(.serving_hw_matrix.points // [])[]
      | "\(.key): \(if .healthy_ceiling_rps == null then "no healthy ceiling" else "\(.healthy_ceiling_rps) rps" end) [\(.status)\(if .lower_bound then ", lower bound: " + (.lower_bound_reasons | join("+")) else "" end)\(if .control then ", control" else "" end)]"]
    | join("; ")' "$RESULT")"
  HWM_NOTE="

:information_source: **Hardware matrix** (item 27, notify-only) — ${HWM_SUMMARY:-no point measured}."
  if [ "$HWM_SKIPPED" != "0" ]; then
    HWM_SKIP_LIST="$(jq -r '[.serving_hw_matrix.skipped[] | (.key // ((.cores|tostring)+"c"))+" ["+(.type // "?")+"] ("+.reason+")"] | join("; ")' "$RESULT")"
    HWM_NOTE="${HWM_NOTE} Skipped: ${HWM_SKIP_LIST}."
  fi
  if [ "$(jq -r '[(.serving_hw_matrix.points // [])[] | select(.sut_survived == false)] | length' "$RESULT")" != "0" ]; then
    HWM_NOTE="${HWM_NOTE} :warning: at least one point's SUT did not survive its sweep (OOM or crash) — see its status."
  fi
fi

# --- 3. pull the last N PRIOR runs --------------------------------------------
BASE_DIR="$WORK/baseline"; mkdir -p "$BASE_DIR"
if [ -n "${PERF_BASELINE_DIR:-}" ]; then
  cp "$PERF_BASELINE_DIR"/*.json "$BASE_DIR/" 2>/dev/null || true
elif $HAVE_AWS; then
  # This step has just written $KEY, so a listing that fails or lacks it, or a listed run that
  # cannot be read, is an error: read as fewer prior runs it would report "warming up" instead.
  if ! LS_OUT="$(aws s3 ls "s3://${BUCKET}/${HISTORY_PREFIX}/${BRANCH}/" --recursive 2>"$WORK/ls.err")" \
     || ! awk -v k="$KEY" '$4 == k { found = 1 } END { exit !found }' <<<"$LS_OUT"; then
    annotate "error" ":no_entry: **Perf history could not be listed — build FAILED** — \`${COMMIT:0:10}\` on \`${BRANCH}\`

Listing \`s3://${BUCKET}/${HISTORY_PREFIX}/${BRANCH}/\` failed, or did not return \`${KEY}\`, which this step had just written. The run is persisted, but its baseline could not be read, so it was not compared and is not reported as warming up.
\`\`\`
$(head -c 600 "$WORK/ls.err" 2>/dev/null)
\`\`\`"
    exit 1
  fi
  # Drop the just-written key, take the most recent N by name. grep -vxF: an exact whole-line
  # fixed-string match (the key has dots, which a regex grep would read as wildcards).
  mapfile -t KEYS < <(awk '{print $4}' <<<"$LS_OUT" | grep -vxF "$KEY" | sort | tail -n "$BASELINE_N")
  for k in "${KEYS[@]:-}"; do
    [ -n "$k" ] || continue
    if ! aws s3 cp "s3://${BUCKET}/${k}" "$BASE_DIR/$(basename "$k")" --only-show-errors 2>"$WORK/cp.err"; then
      annotate "error" ":no_entry: **Perf baseline object could not be read — build FAILED** — \`${k}\`

The history listing returned it, but downloading it failed, so the baseline window cannot be trusted. The run is persisted, but it was not compared and is not reported as warming up.
\`\`\`
$(head -c 600 "$WORK/cp.err" 2>/dev/null)
\`\`\`"
      exit 1
    fi
  done
fi

# Belt and braces for the prefix split (and the only split for PERF_BASELINE_DIR): drop any prior run
# from another queue. An object whose queue cannot be read is not guessed at: it fails the step.
for f in "$BASE_DIR"/*.json; do
  [ -e "$f" ] || continue
  if ! base_queue="$(jq -er '.agent.queue // "perf"' "$f" 2>/dev/null)"; then
    annotate "error" ":no_entry: **Perf baseline object UNREADABLE — build FAILED** — \`$(basename "$f")\` from \`${HISTORY_PREFIX}/${BRANCH}/\` is not readable JSON, so its queue cannot be checked and the baseline window cannot be trusted. Inspect or remove that object."
    exit 1
  fi
  [ "$base_queue" = "$RUN_QUEUE" ] && continue
  echo "--- baseline: dropping $(basename "$f") — from queue ${base_queue}, not ${RUN_QUEUE}" >&2
  rm -f "$f"
done
BASE_COUNT="$(find "$BASE_DIR" -name '*.json' | wc -l | tr -d ' ')"
echo "--- baseline: $BASE_COUNT prior run(s) (min $MIN_BASELINE, window $BASELINE_N)"

if [ "$BASE_COUNT" -lt "$MIN_BASELINE" ]; then
  annotate "info" ":hourglass_flowing_sand: **Perf baseline warming up** — ${BASE_COUNT}/${MIN_BASELINE} runs collected. Persisted this run (\`${COMMIT:0:10}\`); regression comparison starts once ${MIN_BASELINE} runs exist.${LAPTOP_INJVM_NOTE}"
  exit 0
fi

# --- 4. compare (median + MAD) ------------------------------------------------
jq -s '.' "$BASE_DIR"/*.json > "$WORK/baseline.json"

# Hardware provenance, for the annotation only — the baseline is NOT filtered
# here. Metrics whose value moves with the machine are routed to a
# same-instance-type subset inside the comparison (see $bmapAllHw and the `hw`
# flag in perf-budgets.json); allocation counts, allocation ratios and error rates
# keep the FULL baseline because they are properties of the code path rather than
# the machine. Note the classification has THREE categories, not two: THREAD counts
# are neither timings nor code-path properties, but resource counts that track the
# core count (actionHandlerThreadCount defaults to max(5, availableProcessors), and
# the GC and JIT pools scale too), so they are marked `hw` as well.
#
# Not every count is core-driven, and the distinction is the whole point:
# `laptop.*.tcp_sockets` is an lsof line count of listen + established sockets
# (InJvmParallelBench.tcpSocketCount), so it tracks INSTANCES AND CONNECTIONS, not
# cores. It stays hardware-independent. "It is a count" is not the test; "does it
# move when the core count moves" is.
#
# Filtering the whole baseline at this point was tried first and was wrong:
# dropping non-matching runs drives BASE_COUNT to zero on a hardware change,
# which trips the global `warming up` early-exit ABOVE and skips every metric.
# Of the four gating metrics THIS script evaluates, three are hardware-independent
# — microbench.*.alloc_bytes_per_op, forward.error_rate and
# churn.alloc_ratio_index_n15000 (the other eight gating budget entries,
# premerge_alloc.*, belong to perf-alloc-gate.sh and never reach this code). So
# filtering wholesale would switch off three quarters of this control's gating
# during precisely the migration when a regression is most likely.
HEAD_INSTANCE="$(jq -r '.agent.instance_type // "" ' "$RESULT" 2>/dev/null || echo "")"
HW_NOTE=""
if [ -n "$HEAD_INSTANCE" ]; then
  HW_MISMATCH="$(jq --arg hi "$HEAD_INSTANCE" '[ .[] | select((.agent.instance_type // "") != $hi) ] | length' "$WORK/baseline.json" 2>/dev/null || echo 0)"
  if [ "${HW_MISMATCH:-0}" -gt 0 ]; then
    HW_NOTE="

:desktop_computer: **${HW_MISMATCH} of ${BASE_COUNT} baseline run(s) are from a different (or unrecorded) machine type.** Hardware-sensitive metrics — timings, saturation throughput and thread counts — compare only against runs from \`${HEAD_INSTANCE}\` and report \`no-baseline\` until ${MIN_BASELINE} of those exist. Metrics that are properties of the code path rather than the machine keep comparing across the change, so the gating floors stay live."
  fi
else
  HW_NOTE="

:warning: **This run records no instance type**, so hardware-sensitive metrics cannot be matched to same-machine history and report \`no-baseline\` rather than compare across an unknown machine."
fi

# Honest history. Every run stored
# before the self-describing-result change has no `config` block (schema_version
# < 2) and an unusable instance_type, so we CANNOT confirm it was configured like
# this run (same JVM/GC/heap/log-level/hardware). Do NOT silently fold such runs
# into the rolling baseline as though comparable — count them so the annotation can
# flag that the comparison spans a configuration boundary. No backfill is attempted.
PRE_CONFIG_COUNT="$(jq '[ .[] | select((.config == null) or ((.schema_version // 1) < 2)) ] | length' "$WORK/baseline.json")"

# Compact provenance line from THIS run's config block — the version/JDK/GC/heap/
# log-level/hardware the figures were produced under (what the website provenance
# line and the hardware-invalidation rule both need a run to carry).
PROVENANCE="$(jq -r '
  (.config // null) as $c
  | if $c == null then "_No config block on this run (schema_version \(.schema_version // 1) — pre self-describing)._"
    else "**Run config** — MockServer \($c.mockserver_version // "?") · JDK \($c.jdk // "?") · GC \($c.gc // "?") · heap_max \((((($c.heap_max_bytes // 0)) / 1048576) | floor)) MiB · log_level \($c.log_level // "?") · disable_system_out \($c.disable_system_out // "?") · instance \(.agent.instance_type // "?") · image \($c.image_digest // "?")"
    end' "$RESULT" 2>/dev/null || echo "")"
PRECFG_NOTE=""
if [ "${PRE_CONFIG_COUNT:-0}" -gt 0 ]; then
  PRECFG_NOTE="

:warning: **${PRE_CONFIG_COUNT} of ${BASE_COUNT} baseline run(s) predate the self-describing result (no \`config\` block).** Those runs cannot be confirmed to share this run's JVM / GC / heap / log level / hardware, so the comparison above crosses a configuration boundary — weigh flagged metrics accordingly and re-derive the baseline once ${BASELINE_N} config-bearing runs exist."
fi

# jq program (single-quoted on purpose — $vars are jq vars, not shell).
# shellcheck disable=SC2016
COMPARE='
def fabs: if . < 0 then -. else . end;
def median: sort | length as $n | if $n==0 then null elif ($n%2==1) then .[($n/2|floor)] else (.[$n/2-1]+.[$n/2])/2 end;
def mad($m): map((. - $m)|fabs) | median;
# A bimodal baseline split at the largest ratio between neighbouring sorted values
# (the first, on a tie). null unless that ratio reaches $r and the lower mode has at
# least $minlo runs; the upper mode may be a single run.
def modesplit($r; $minlo):
  sort as $s
  | reduce (range(0; ($s|length) - 1) | select($s[.] > 0) | {k: ., g: ($s[.+1] / $s[.])}) as $x
      (null; if . == null or $x.g > .g then $x else . end)
  | if . == null or .g < $r or .k + 1 < $minlo then null
    else {lo: $s[0:.k+1], up: $s[.k+1:]} end;
def binomtail($n; $k; $p):
  [range($k; $n + 1) as $i | (reduce range(0; $i) as $j (1; . * ($n - $j) / ($j + 1))) * pow($p; $i) * pow(1 - $p; $n - $i)] | add // 0;
# Mode-share check for a bimodal metric (input: the baseline, oldest first). The per-mode
# band cannot see runs moving INTO the upper mode, so an upper-mode head is also tested:
# is the upper-mode count in the last share_runs runs plus the head improbable (binomial
# tail < share_alpha) at the historical upper-mode share listed for the metric?
def modeshare($v; $bi; $name):
  . as $bv
  | modesplit($bi.split_ratio; 1) as $s1
  | if $s1 == null then {} else
      ((($s1.lo | max) * ($s1.up | min)) | sqrt) as $cut
      | if $v < $cut then {} else
          ($bv[-($bi.share_runs):] + [$v]) as $recent
          | ($recent | length) as $n | ([$recent[] | select(. >= $cut)] | length) as $k
          | binomtail($n; $k; $bi.metrics[$name]) as $p
          | {share: {upper_runs: $k, runs: $n, expected: $bi.metrics[$name], p: $p, flagged: ($p < $bi.share_alpha)}}
        end
    end;

# Flat list of comparable metrics for one run object. Each metric carries a
# `bkey` (budget key) resolved against $budgets (loaded from the committed
# perf-budgets.json). The tail below folds in that entry dir / min_pct /
# floor / gating; a run metric whose bkey is absent from $budgets is a fail-
# closed error (see the tail of this program and the MISSING_COUNT check). The
# wildcard bkeys `behaviours.*.<m>` / `microbench.*.<m>` cover every runtime
# behaviour / benchmark key of that shape. Rationale for WHY each metric gates or
# is notify-only, and how its floor was chosen, now lives in perf-budgets.json.
def metrics:
  ((.behaviours // {}) | to_entries[] | .key as $k | .value as $v |
    # k6 latency percentiles + per-behaviour error_rate. throughput_rps is
    # DELIBERATELY not budgeted (recorded with offered_rps + dropped_iterations +
    # delivery_ratio alongside): a shortfall below offered is AMBIGUOUS (server
    # slower vs client VU-starved). rig_valid_peak_achieved_rps (below) is the
    # rig-valid peak (a property of the k6 rig, not a server ceiling).
    ( {name:($k+".p95_ms"),     value:$v.p95_ms,     bkey:"behaviours.*.p95_ms"},
      {name:($k+".p99_ms"),     value:$v.p99_ms,     bkey:"behaviours.*.p99_ms"},
      {name:($k+".error_rate"), value:$v.error_rate, bkey:"behaviours.*.error_rate"} ) ),
  # The INFO-log-level publication arm (open question 5). The run has measured this
  # since the arm was added and perf-budgets.json has carried info_* entries for just
  # as long, but NOTHING consumed them: this function enumerates HEAD metrics
  # explicitly, so a key it does not name is never compared. The arm was therefore
  # measured, budgeted, and silently discarded every run - and because the fail-closed
  # "metric with no budget" check also only sees what this function emits, the gap could
  # not announce itself either. All four info_* budgets are gating:false, so these are
  # NOTIFY-ONLY: they surface movement at the shipped default log level without being
  # able to red the build.
  #
  # Guarded on .measured: the arm sets it false when a regression or sweep leg fails,
  # and emitting nulls from a degraded arm would look like a metric that collapsed
  # rather than one that was never taken.
  (if ((.info_log_level_arm // {}).measured == true) then
    (((.info_log_level_arm.behaviours) // {}) | to_entries[] | .key as $k | .value as $v |
      ( {name:("info_" + $k + ".p95_ms"),     value:$v.p95_ms,     bkey:"info_behaviours.*.p95_ms"},
        {name:("info_" + $k + ".p99_ms"),     value:$v.p99_ms,     bkey:"info_behaviours.*.p99_ms"},
        {name:("info_" + $k + ".error_rate"), value:$v.error_rate, bkey:"info_behaviours.*.error_rate"} ) ),
    (if ((.info_log_level_arm.rig_valid_peak_achieved_rps) != null) then
       {name:"info_rig_valid_peak_achieved_rps", value:(.info_log_level_arm.rig_valid_peak_achieved_rps), bkey:"info_rig_valid_peak_achieved_rps"}
     else empty end)
   else empty end),
  ((.growth // {}) |
    # cpu_ratio/heap_ratio detect a SLOPE; live_set_bytes detects a STEP a plateaued
    # leak (ratio ~1.0) would hide. p95_ratio is the noisier latency slope.
    # cpu_ratio is read only from occupancy-window runs: a run without ratio_method divided by
    # one first sample, which landed before or after the load ramp at random (ratio ~1 or ~50).
    # growth.js ran but no ratio came out (no full log or no start window): a visible row, not a gap.
    ( {name:"growth.cpu_ratio",      value:(if .cpu_pct.ratio_method == "occupancy_window" then .cpu_pct.ratio else null end),
                                     bkey:"growth.cpu_ratio"}
      + (if .cpu_pct.ratio_method == "occupancy_window" and .cpu_pct.ratio == null and .duration_s != null
         then {unmeasured:true} else {} end),
      {name:"growth.heap_ratio",     value:(.heap_used_bytes.ratio),           bkey:"growth.heap_ratio"},
      {name:"growth.live_set_bytes", value:(.heap_used_bytes.min_last_window), bkey:"growth.live_set_bytes"},
      {name:"growth.p95_ratio",      value:(.p95_ms.ratio),                    bkey:"growth.p95_ratio"} ) ),
  ((.microbench // {}) | to_entries[] | .key as $k | .value as $v |
    # JMH micro-benchmarks: deterministic and hardware-independent (forked JVM,
    # steady-state, low run-to-run noise), the strongest signal in the repo.
    ( {name:($k+".time_per_op"),        value:$v.time_per_op,        bkey:"microbench.*.time_per_op"},
      {name:($k+".alloc_bytes_per_op"), value:$v.alloc_bytes_per_op, bkey:"microbench.*.alloc_bytes_per_op"} ) ),
  ((.microbench_extra // {}) | to_entries[] | .key as $k | .value as $v |
    # Promoted dark benchmarks: identical JMH shape to .microbench, but a
    # SEPARATE budget key so they can be NOTIFY-ONLY while .microbench gates. They
    # have no baseline history yet; their perf-budgets.json entries omit `gating`
    # (-> non-gating), so a flagged regression annotates just as loudly but does NOT
    # fail the build. NOTE: this compare only iterates HEAD metrics, so it CANNOT
    # detect a benchmark that drift silently drops (a baseline-has-key / head-lacks-it
    # gap) — a dropped row just disappears here unnoticed. Drift is caught upstream in
    # the producer (perf-test-microbench.sh), which asserts the EXACT expected row
    # count (EXTRA_EXPECTED) and fails the step on a partial vanish.
    ( {name:($k+".time_per_op"),        value:$v.time_per_op,        bkey:"microbench_extra.*.time_per_op"},
      {name:($k+".alloc_bytes_per_op"), value:$v.alloc_bytes_per_op, bkey:"microbench_extra.*.alloc_bytes_per_op"} ) ),
  ((.laptop // {}) | to_entries[] | .key as $k | .value as $v |
    # Laptop startup / footprint profile, keyed by variant: docker_ready,
    # injvm, mem_256m/512m/1g, init_0/1000/10000, image. Each variant carries only the
    # subset of these metrics it measures; the others are null and drop out via the
    # `select(.value != null)` in $headmetrics (e.g. docker_ready emits no rss_mb,
    # image emits only compressed_bytes). dir:"up" throughout — a slower start, larger
    # idle RSS, more threads, or a bigger image are all worse. NOTIFY-ONLY: the
    # laptop.*.<metric> budgets omit `gating`, so a flag annotates but NEVER fails the
    # build. Their 0.25 min_pct is a WIDE dead band on a laptop-startup baseline — a
    # backstop against a gross loss (AppCDS/warmup), not a fine regression signal. It
    # flags informationally once >=1 prior point exists (laptop.* takes the `else`
    # branch below, $minreq=1, subject to the global BASE_COUNT >= MIN_BASELINE warm-up)
    # and never fails the build. laptop.* rides that `else` (full baseline) branch like
    # growth/peak — not the fingerprint-filtered microbench/behaviours buckets — because these metrics have
    # no methodology fingerprint of their own and are deliberately NOT keyed on the image
    # digest (which changes every snapshot rebuild — the reset trap the k6 unit avoided).
    ( {name:($k+".ready_ms"),         value:$v.ready_ms,         bkey:"laptop.*.ready_ms"},
      {name:($k+".cold_ready_ms"),    value:$v.cold_ready_ms,    bkey:"laptop.*.cold_ready_ms"},
      {name:($k+".rss_mb"),           value:$v.rss_mb,           bkey:"laptop.*.rss_mb"},
      {name:($k+".threads"),          value:$v.threads,          bkey:"laptop.*.threads"},
      {name:($k+".compressed_bytes"), value:$v.compressed_bytes, bkey:"laptop.*.compressed_bytes"} ) ),
  # N parallel MockServer instances on one host (the laptop / MockServerExtension
  # profile), notify-only. TWO shapes under .laptop_parallel, each a list of per-N runs
  # (n in {1,4,8,16,32}); keyed laptop_parallel.<shape>_<N>.<metric> so the SAME laptop.*.<metric>
  # wildcard budgets the laptop profile uses cover every N of every shape — no new namespace. Head-driven
  # like every other optional profile: an absent/disabled .laptop_parallel (opt-in
  # PERF_LAPTOP_PARALLEL, off by default) makes both `// []` fall through, so this emits ZERO
  # metrics rather than a fail-closed missing-budget trip — the serving_percore/streaming pattern.
  # This is the emission BRIDGE: the harness raw field names differ from the snake_case budget
  # leaves (the .java in-JVM shape is camelCase, the .py container shape is snake_case with a
  # _median suffix), so each raw field is mapped to its leaf here, exactly as serving_percore.*
  # maps .healthy_ceiling_rps. dir "up" throughout (more heap / threads / sockets / RSS / latency
  # is worse). NOTIFY-ONLY: the laptop.*.<metric> budgets omit `gating`, so a flag annotates but
  # NEVER fails the build until >=10 runs of history let a MAD-derived floor be set (the laptop-profile rule).
  # IN-JVM shape (InJvmParallelBench.java): one JVM, N instances, stores sized off the whole host.
  ((.laptop_parallel.injvm.runs // []) | .[] | ("laptop_parallel.injvm_" + (.n|tostring)) as $pk |
    ( {name:($pk+".cold_ready_ms"),        value:.coldReadyMs,       bkey:"laptop.*.cold_ready_ms"},
      {name:($pk+".heap_used_mb"),         value:.heapUsedMb,        bkey:"laptop.*.heap_used_mb"},
      {name:($pk+".total_threads"),        value:.totalThreads,      bkey:"laptop.*.total_threads"},
      {name:($pk+".threads_per_instance"), value:.threadsPerInstance,bkey:"laptop.*.threads_per_instance"},
      {name:($pk+".tcp_sockets"),          value:.tcpSockets,        bkey:"laptop.*.tcp_sockets"},
      {name:($pk+".load_p95_median_ms"),   value:.loadP95MedianMs,   bkey:"laptop.*.load_p95_median_ms"},
      {name:($pk+".load_p99_max_ms"),      value:.loadP99MaxMs,      bkey:"laptop.*.load_p99_max_ms"} ) ),
  # CONTAINER shape (parallel_instances.py): N containers, N JVMs, each cgroup-sized; the aggregate
  # cost a laptop actually pays. agg_threads maps to the SAME laptop.*.total_threads leaf as the
  # in-JVM totalThreads (distinct metric NAMES — container_<N> vs injvm_<N> — so no baseline
  # collision); threads_per_container reads the harness _median field.
  ((.laptop_parallel.container.runs // []) | .[] | ("laptop_parallel.container_" + (.n|tostring)) as $pk |
    ( {name:($pk+".cold_ready_ms"),         value:.cold_ready_ms,               bkey:"laptop.*.cold_ready_ms"},
      {name:($pk+".agg_rss_mb"),            value:.agg_rss_mb,                  bkey:"laptop.*.agg_rss_mb"},
      {name:($pk+".rss_mb_per_container"),  value:.rss_mb_per_container,        bkey:"laptop.*.rss_mb_per_container"},
      {name:($pk+".threads_per_container"), value:.threads_per_container_median,bkey:"laptop.*.threads_per_container"},
      {name:($pk+".total_threads"),         value:.agg_threads,                 bkey:"laptop.*.total_threads"} ) ),
  ((.tls_handshake // {}) | to_entries[] | .key as $k | .value as $v |
    # TLS/mTLS/native-absent inbound-handshake cost (proxy.js handshake
    # mode). Keyed by arm: tls13, mtls, jdk. handshake_p50/p95_ms is the TLS
    # handshake time (dir up = worse) — the provider-sensitive COST signal (forcing
    # the JDK provider moves p50 markedly while the handshake RATE does not).
    # cpu_ms_per_handshake + alloc_kb_per_handshake are the per-handshake server cost
    # the run step samples (null on an image predating jvm_memory_allocated_bytes, so
    # they drop out via the select(.value != null) in $headmetrics).
    # KEEP-UP is on delivery_ratio (dir DOWN = worse): throughput/offered, computed by
    # proxy.js — SCALE-FREE (a change to the offered rate K6_HS_RATE cannot invalidate
    # it), so it is the AUTHORITATIVE guard that the server sustained the offered
    # handshake load. handshakes_per_s (dir DOWN) is the RAW achieved rate: it measures
    # the fixed ~50/s offered rate, not capacity, so it is NOT a keep-up %-signal — its
    # budget is a coarse offered-rate-INDEPENDENT liveness floor (a dead arm produces
    # ~0/s) and a defence-in-depth backstop for when delivery_ratio drops out (offered
    # <= 0 => proxy.js emits null => dropped here). error_rate is delivery soundness.
    # All NON-GATING (their perf-budgets.json entries omit gating). Ride the
    # full-baseline else-branch (not fingerprint filtered) like growth/peak: the arm
    # set is stable and the numbers are not keyed on the mutable snapshot image digest.
    ( {name:($k+".handshake_p50_ms"),      value:$v.handshake_p50_ms,      bkey:"tls_handshake.*.handshake_p50_ms"},
      {name:($k+".handshake_p95_ms"),      value:$v.handshake_p95_ms,      bkey:"tls_handshake.*.handshake_p95_ms"},
      {name:($k+".handshakes_per_s"),      value:$v.handshakes_per_s,      bkey:"tls_handshake.*.handshakes_per_s"},
      {name:($k+".delivery_ratio"),        value:$v.delivery_ratio,        bkey:"tls_handshake.*.delivery_ratio"},
      {name:($k+".cpu_ms_per_handshake"),  value:$v.cpu_ms_per_handshake,  bkey:"tls_handshake.*.cpu_ms_per_handshake"},
      {name:($k+".alloc_kb_per_handshake"),value:$v.alloc_kb_per_handshake,bkey:"tls_handshake.*.alloc_kb_per_handshake"},
      {name:($k+".error_rate"),            value:$v.error_rate,            bkey:"tls_handshake.*.error_rate"} ) ),
  # Path coverage: only the NAMED metrics are budgeted (arms carry other descriptive
  # fields). Not .behaviours, so the k6 fingerprint is untouched; all non-gating. An absent
  # arm or null value emits nothing, so a skipped arm never trips the missing-budget check.
  # A budget with min_samples marks the value of an arm that measured fewer requests `insufficient`:
  # it is shown but never judged or baselined (a p99 of 3,500 samples is set by its 35 slowest).
  # A value with no sample count at all cannot be checked, so it is a schema error, not a zero.
  ((.path_coverage.arms // {}) | to_entries[] | select((.value | type) == "object") | .key as $k | .value as $v |
    ($v.sample_count // $v.measured_sample_count) as $n |
    ( ["p50_ms","p99_ms","error_rate","delivery_ratio","handshake_p50_ms","handshake_p99_ms",
       "handshakes_per_s","p50_ratio","p99_ratio","handshake_p50_ratio","dropped_ratio",
       "persisted_ratio","ring_occupancy_peak_ratio","rss_peak_mib","netty_direct_peak_mib",
       "direct_pool_peak_mib","received_mib_per_s"][] as $m
      | select($v | has($m))
      | ("path_coverage.*."+$m) as $bk | (($budgets[$bk] // {}).min_samples) as $ms
      | {name:("path_coverage."+$k+"."+$m), value:$v[$m], bkey:$bk}
        + (if ($ms | type) != "number" or $v[$m] == null then {}
           elif ($n | type) != "number" then {count_error:true}
           elif $n < $ms then {insufficient:{n:$n, min:$ms}} else {} end) ) ),
  {name:"path_coverage.h2_ladder.rig_valid_peak_achieved_rps",
   value:((.path_coverage.h2_ladder // {}).rig_valid_peak_achieved_rps),
   bkey:"path_coverage.h2_ladder.rig_valid_peak_achieved_rps"},
  # HTTP/2 per-connection heap-delta (org.mockserver.benchmark.Http2Connection-
  # MemoryBenchmark), merged into the run under .h2_connection_memory by the h2-multiplex
  # step and keyed by shape: conn_1x1, conn_10x10, conn_100x10 (N connections x M concurrent
  # in-flight streams). The figure is bytes_per_connection = (loaded heap - baseline heap) / N,
  # with the event log cleared before sampling so it measures connection + stream child-channel
  # state, not logged bodies. A wildcard bkey (h2_connection_memory.*.bytes_per_connection)
  # covers every shape, exactly as serving_percore.* / clustered_state.* / tls_handshake.* do
  # for their arm sets. This is an ALLOCATION / heap-delta figure — a property of the code path
  # and JVM object layout, not of CPU speed or core count (allocation is bytes, reproducible
  # across amd64/arm64 under the same JVM) — so it is deliberately NOT `hw`: its perf-budgets.json
  # entry omits `hw`, so it rides the FULL baseline (this else-family) like the other allocation
  # metrics, unfiltered by the k6 arm-set fingerprint or the JMH .config.jmh (it is neither a
  # .behaviours arm nor a JMH microbench). NOTIFY-ONLY (perf-budgets.json omits `gating`):
  # run-to-run variance on real agents is not yet known and the 1x1 shape sits at the edge of
  # GC measurement granularity, so a flag annotates but must NOT fail the build until >=10 clean
  # runs let a MAD-derived floor be set. Head-driven like every other optional profile: a
  # disabled/absent h2 step leaves .h2_connection_memory absent, so this emits ZERO metrics
  # rather than a fail-closed missing-budget error. (The harness self-validates and REDs its OWN
  # step on a bad measurement — distinct from "memory grew", which has no threshold here.)
  # select(...): the block carries METADATA scalars beside the shape objects — `dated_utc` and
  # `method` — and an unfiltered to_entries[] reaches them, so `$v.bytes_per_connection` becomes
  # "Cannot index string with string" and jq exits 5, failing the whole compare step and taking
  # the baseline persist with it. Filter on the shape rather than the key name, so another
  # metadata field added later cannot reintroduce it.
  ((.h2_connection_memory // {}) | to_entries[]
     | select((.value | type) == "object" and (.value | has("bytes_per_connection")))
     | .key as $k | .value as $v |
    ( {name:("h2_connection_memory."+$k+".bytes_per_connection"), value:$v.bytes_per_connection, bkey:"h2_connection_memory.*.bytes_per_connection"} ) ),
  # LLM/SSE streaming under concurrency. The .streaming block is a FLAT
  # object (not per-arm), so a CURATED subset of its scalars is budgeted here with
  # EXACT bkeys (like rig_valid_peak_achieved_rps / forward.error_rate, not the wildcard arm
  # families). All NON-GATING (their perf-budgets.json entries omit gating) and
  # unfiltered by the k6 fingerprint (they are not .behaviours), riding the
  # full-baseline else-branch like tls_handshake. A skipped streaming profile
  # leaves every value null, and nulls drop out of $headmetrics — so a skip emits
  # zero streaming metrics rather than a fail-closed missing-budget error. The four
  # streaming measurements distilled to comparable scalars:
  #   match_*_p95_ms + match_p95_ratio  — the within-run hot-path A/B: does a
  #     concurrent match p95 inflate while streams run (ratio dir up = worse).
  #   intertoken_error_*_p95/p99_ms + _ratio — the fidelity distribution: the
  #     idle run is the client-jitter FLOOR, the load run the server drift, ratio
  #     = load/idle (dir up = worse). The p99 is the headline (the tail, not mean).
  #   heap_bytes_per_stream — heap per open stream, an upper bound (dir up).
  #   *_error_rate — delivery soundness (a broken arm measured nothing).
  # A CallerRunsPolicy counter is deliberately ABSENT — MockServer
  # exposes none and it does not fire under load (see the run step / report).
  ( {name:"streaming.match_baseline_p95_ms",        value:((.streaming // {}).match_baseline_p95_ms),        bkey:"streaming.match_baseline_p95_ms"},
    {name:"streaming.match_under_stream_p95_ms",     value:((.streaming // {}).match_under_stream_p95_ms),     bkey:"streaming.match_under_stream_p95_ms"},
    {name:"streaming.match_p95_ratio",               value:((.streaming // {}).match_p95_ratio),               bkey:"streaming.match_p95_ratio"},
    {name:"streaming.match_under_stream_error_rate", value:((.streaming // {}).match_under_stream_error_rate), bkey:"streaming.match_under_stream_error_rate"},
    {name:"streaming.stream_error_rate",             value:((.streaming // {}).stream_error_rate),             bkey:"streaming.stream_error_rate"},
    {name:"streaming.intertoken_error_idle_p95_ms",  value:((.streaming // {}).intertoken_error_idle_p95_ms),  bkey:"streaming.intertoken_error_idle_p95_ms"},
    {name:"streaming.intertoken_error_load_p95_ms",  value:((.streaming // {}).intertoken_error_load_p95_ms),  bkey:"streaming.intertoken_error_load_p95_ms"},
    {name:"streaming.intertoken_error_load_p99_ms",  value:((.streaming // {}).intertoken_error_load_p99_ms),  bkey:"streaming.intertoken_error_load_p99_ms"},
    {name:"streaming.intertoken_error_p95_ratio",    value:((.streaming // {}).intertoken_error_p95_ratio),    bkey:"streaming.intertoken_error_p95_ratio"},
    {name:"streaming.heap_bytes_per_stream",         value:((.streaming // {}).heap_bytes_per_stream),         bkey:"streaming.heap_bytes_per_stream"} ),
  # Clustered state under load. The headline metric is the per-arm RATIO
  # (clustered / in-memory control), measured within ONE run against two targets that
  # differ ONLY in state backend. Keyed by arm op under .clustered_state.arms, so a
  # wildcard bkey (clustered_state.*.<metric>) covers every arm. Rides the FULL
  # baseline (this else-family, unfiltered by the k6 fingerprint or image digest) BY
  # DESIGN: the within-run A/B already cancels environment, and keying it on the arm
  # set / image would reset it on every snapshot rebuild — the exact trap the
  # fingerprint was chosen to avoid. All NON-GATING (perf-budgets.json omits gating).
  # A disabled/absent-image run leaves .clustered_state {} (no arms), so this emits
  # ZERO metrics rather than a fail-closed missing-budget error — the genuineness of
  # an ATTEMPTED run is enforced by the run clustered_metrics_present validity check,
  # not here. p95_ratio is null (dropped) unless BOTH arms cleared MIN_TAIL_SAMPLES in every pair.
  # Read only when .measured is not false (a missing window or a non-genuine cluster), like
  # pipelining_inorder; a run from before the paired A/B has no .measured and is read as before.
  ((.clustered_state // {}) | select(.measured != false) | (.arms // {}) | to_entries[] | .key as $k | .value as $v |
    ( {name:("clustered_state."+$k+".p50_ratio"),          value:$v.p50_ratio,          bkey:"clustered_state.*.p50_ratio"},
      {name:("clustered_state."+$k+".p95_ratio"),          value:$v.p95_ratio,          bkey:"clustered_state.*.p95_ratio"},
      {name:("clustered_state."+$k+".throughput_ratio"),   value:$v.throughput_ratio,   bkey:"clustered_state.*.throughput_ratio"},
      {name:("clustered_state."+$k+".clustered_error_rate"),value:$v.clustered_error_rate,bkey:"clustered_state.*.clustered_error_rate"} ) ),
  # In-order HTTP/1.1 pipelining A/B (http1PipeliningInOrder on / off, one run, one machine). The
  # ratios ride the FULL baseline like clustered_state.*; the per-arm absolutes are `hw`. Read only
  # when .measured (each arm proved its configuration), so a run where the flag did not take
  # effect never enters the baseline; the PL_NOTE annotation below says why instead. NON-GATING.
  ((.pipelining_inorder // {}) | select(.measured == true) | (.arms // {}) | to_entries[] | .key as $k | .value as $v |
    ( "p50_ratio", "p95_ratio", "p99_ratio", "throughput_ratio", "error_rate_on", "error_rate_off",
      "p95_on_ms", "p95_off_ms", "p99_on_ms", "p99_off_ms", "throughput_on_rps", "throughput_off_rps" ) as $f
    | {name:("pipelining_inorder."+$k+"."+$f), value:$v[$f], bkey:("pipelining_inorder.*."+$f)} ),
  # FILE-body A/B (a FILE body / the identical inline body, one SUT, one run). Read only when
  # .measured; the ratios ride the FULL baseline, the absolutes are `hw`. NON-GATING; FB_NOTE below.
  ((.file_body // {}) | select(.measured == true) | (.sizes // {}) | to_entries[] | .key as $k | .value as $v |
    ( "p50_ratio", "p95_ratio", "p99_ratio", "throughput_ratio", "error_rate_file", "error_rate_inline",
      "p95_file_ms", "p95_inline_ms", "p99_file_ms", "p99_inline_ms", "throughput_file_rps", "throughput_inline_rps" ) as $f
    | {name:("file_body."+$k+"."+$f), value:$v[$f], bkey:("file_body.*."+$f)} ),
  # Req/s per core for the SERVING path. One point per pinned core-count
  # C under .serving_percore.points; keyed serving_percore.<C>c.<metric> so a
  # wildcard bkey (serving_percore.*.<metric>) covers every C. NOT part of
  # .behaviours, so it does not touch the k6 arm-set fingerprint. rig_valid_peak_achieved_rps
  # rides the FULL baseline; the three p50-gated metrics (healthy_ceiling_rps, rps_per_core,
  # healthy_ceiling_p50_ms) are keyed on the sweep latency-window fingerprint (latfam). All
  # NON-GATING (perf-budgets.json omits `gating`). A disabled/failed profile leaves
  # .serving_percore {} (no points), so this emits ZERO metrics rather than a
  # fail-closed missing-budget error — the wholesale-failure case is caught by the
  # serving_percore_attempted PRESENCE gate below, not here. healthy_ceiling_rps
  # (dir DOWN = a drop is worse) is the headline; rps_per_core (DOWN) is the
  # per-core efficiency; rig_valid_peak_achieved_rps (DOWN) is the degraded-overload top;
  # healthy_ceiling_p50_ms (UP) guards the latency at the ceiling.
  ((.serving_percore.points // []) | .[] | ("serving_percore." + (.cores|tostring) + "c") as $pc |
    ( {name:($pc+".healthy_ceiling_rps"),   value:.healthy_ceiling_rps,   bkey:"serving_percore.*.healthy_ceiling_rps"},
      {name:($pc+".rps_per_core"),          value:.rps_per_core,          bkey:"serving_percore.*.rps_per_core"},
      {name:($pc+".rig_valid_peak_achieved_rps"), value:.rig_valid_peak_achieved_rps, bkey:"serving_percore.*.rig_valid_peak_achieved_rps"},
      {name:($pc+".healthy_ceiling_p50_ms"),value:.healthy_ceiling_p50_ms,bkey:"serving_percore.*.healthy_ceiling_p50_ms"} ) ),
  # Hardware matrix, one point per cores x memory limit, keyed
  # serving_hw_matrix.<key>.<metric> (key e.g. 2c-1g). Notify-only; the p50-gated two
  # are keyed on the sweep latency-window fingerprint (latfam), like serving_percore.
  ((.serving_hw_matrix.points // []) | .[] | ("serving_hw_matrix." + (.key // ((.cores|tostring) + "c"))) as $hk |
    ( {name:($hk+".healthy_ceiling_rps"),   value:.healthy_ceiling_rps,   bkey:"serving_hw_matrix.*.healthy_ceiling_rps"},
      {name:($hk+".rig_valid_peak_achieved_rps"), value:.rig_valid_peak_achieved_rps, bkey:"serving_hw_matrix.*.rig_valid_peak_achieved_rps"},
      {name:($hk+".healthy_ceiling_p50_ms"),value:.healthy_ceiling_p50_ms,bkey:"serving_hw_matrix.*.healthy_ceiling_p50_ms"} ) ),
  # Multi-process aggregate throughput vs process count. Unlike
  # serving_percore (one point per pinned core-count), this emits TWO FLAT scalars with
  # EXACT bkeys (like rig_valid_peak_achieved_rps / forward.error_rate, not a wildcard
  # arm family), distilled from the per-N .serving_multiproc.points + .scaling verdict:
  #   aggregate_healthy_ceiling_rps  the BEST client-sound aggregate ceiling the rig
  #     reached across all measured N — the whole point of the multi-process rig (one
  #     k6 process cannot saturate the widened client pool). Each per-N ceiling is
  #     computed to the Finding-1 definition by REUSING lib/perf-website-figures.jq on the
  #     AGGREGATE offered/achieved series, never a third copy of the rule. `max` of the
  #     per-N numbers; [] (no client-sound rung) -> null -> dropped by $headmetrics.
  #     dir DOWN, `hw` (a throughput figure, machine-dependent) -> HW-matched baseline,
  #     keyed on the sweep latency-window fingerprint (latfam) because it is p50-gated.
  #   scales_with_procs  the scaling verdict coerced to 1/0 (true/false); the boolean
  #     is coerced because a raw boolean head value trips the compare not-numeric GATING
  #     path. A null/insufficient-points verdict -> null -> dropped. dir DOWN (1->0, i.e.
  #     stopped scaling, is worse). NOT `hw`: like clustered_state.* it is a WITHIN-run
  #     comparison (min-N vs max-N ceiling) that already cancels the environment. Both
  #     ceilings are p50-gated, so it is keyed on the sweep latency-window fingerprint too.
  # BOTH are NOT part of .behaviours (no k6 fingerprint) and NON-GATING (perf-budgets.json
  # omits `gating`) — the day-one notify-only rule; the two bkeys below MUST exist in
  # perf-budgets.json or the fail-closed missing-budget rule REDs the build. A disabled/
  # failed profile leaves .serving_multiproc {} -> both values null -> zero metrics
  # emitted (no missing-budget trip); the wholesale-failure case is caught by the
  # serving_multiproc_attempted PRESENCE gate (2d above), not here.
  ( {name:"serving_multiproc_aggregate_healthy_ceiling_rps",
     value:([ (.serving_multiproc.points // [])[] | .aggregate_healthy_ceiling_rps | select(type == "number") ] | max),
     bkey:"serving_multiproc_aggregate_healthy_ceiling_rps"},
    {name:"serving_multiproc_scales_with_procs",
     value:((.serving_multiproc.scaling.scales_with_procs) | if . == true then 1 elif . == false then 0 else null end),
     bkey:"serving_multiproc_scales_with_procs"} ),
  # rig_valid_peak_achieved_rps: max achieved over the RIG-VALID rungs (the k6 client
  # had CPU headroom, dropped no iterations, low errors). It is a property of the RIG,
  # not the server: the rig-validity filter caps it at whichever rung the client stops
  # being clean, which on this rig is far below the server ceiling — so it does NOT move
  # continuously with the server ceiling. Non-gating (perf-budgets.json gating:false).
  # saturation_rps (the knee) is ladder-QUANTISED, so it is recorded but NOT budgeted here.
  ( {name:"rig_valid_peak_achieved_rps", value:(.rig_valid_peak_achieved_rps), bkey:"rig_valid_peak_achieved_rps"} ),
  # The published headline: the p99-bounded ceiling of the perf-xl multi-k6 arm, under the rule
  # its .headline_rule names. Only an arm-only result carries one (a daily run leaves {}), so it
  # compares against runs-perf-xl/ alone, keyed on that rule (latfam).
  ( {name:"serving_rw_multik6.healthy_ceiling_rps", value:((.serving_rw_multik6 // {}).headline.healthy_ceiling_rps),
     bkey:"serving_rw_multik6.healthy_ceiling_rps"} ),
  # G1 churn gate — the candidate-index churn/static ALLOCATION ratio at n=15,000, t=1
  # (indexMode=INDEX), emitted by run-g1-churn.sh via perf-test-microbench.sh under .churn.
  # A within-run STATIC-vs-CHURN A/B (the CandidateIndexBenchmark gold-standard shape): it
  # cancels host/JVM/GC noise and is machine-INDEPENDENT — allocation is bytes/op, not timing —
  # so it rides the FULL baseline (this else-family), NOT the JMH .config.jmh fingerprint, and
  # an absolute floor on it is methodology-independent. dir up: a regression that reintroduced
  # rebuild-on-read moves it ~1.06x -> ~1000x+ (three orders of magnitude). It GATES on an
  # absolute floor (perf-budgets.json churn.alloc_ratio_index_n15000, gating:true) — the
  # premerge_alloc.* precedent: a deterministic allocation metric with a provisional absolute
  # floor and huge headroom, honestly labelled, gates from day one because a rolling median
  # would absorb the very slow drift this control exists to catch. EXACT bkey (like
  # rig_valid_peak_achieved_rps / forward.error_rate), not a wildcard family. A producer-step
  # failure leaves .churn absent -> value null -> dropped by $headmetrics (the red is on the
  # microbench step, not a fail-closed missing-budget here).
  ( {name:"churn.alloc_ratio_index_n15000", value:((.churn // {}).alloc_ratio_index_n15000), bkey:"churn.alloc_ratio_index_n15000"} ),
  # forward.error_rate: the forward connection-pool guard (forward.js) — a
  # discriminating pass/fail guard (pool works vs it does not).
  ( {name:"forward.error_rate", value:((.forward_guard // {}).error_rate), bkey:"forward.error_rate"} );

# Build a {name: [values]} baseline map from a set of runs. `add // []` guards the
# EMPTY-set case (e.g. no baseline run matches the head JMH config): [] | add is null,
# and iterating null throws — an empty run set must yield an empty map, not an error.
def bmapof($runs): (($runs | map([metrics]) | add) // [] | map(select(.value != null and .insufficient == null and .count_error == null))
  | group_by(.name) | map({key:.[0].name, value:[.[].value]}) | from_entries);

# Sweep latency-window fingerprint (docs/code/performance-measurement.md, "Rung-onset
# exclusion"). These bkeys derive from sweep p50s (the healthy ceiling is p50-gated), whose
# window changed when sweep.js began excluding the onset of each rung; a block without
# sweep.latency_settle_s predates that. They compare only against runs of the same family
# measured with the same settle, and need MIN_BASELINE of them.
def latfam: if IN("serving_percore.*.healthy_ceiling_rps", "serving_percore.*.rps_per_core",
                  "serving_percore.*.healthy_ceiling_p50_ms") then "serving_percore"
            elif IN("serving_multiproc_aggregate_healthy_ceiling_rps",
                    "serving_multiproc_scales_with_procs") then "serving_multiproc"
            elif IN("serving_hw_matrix.*.healthy_ceiling_rps",
                    "serving_hw_matrix.*.healthy_ceiling_p50_ms") then "serving_hw_matrix"
            elif . == "serving_rw_multik6.healthy_ceiling_rps" then "serving_rw_multik6"
            else null end;
# The hardware matrix also keys on its load client (single k6 or multi-k6): the two measure
# different ceilings, so they never share a baseline. A block without .client was single-k6.
# It keys on the k6 runtime of its points too (rw-multi-k6-sweep.sh .config.k6_runtime): GOGC,
# gracefulStop and where GOMEMLIMIT came from, never the derived MiB, which follows host memory.
# No runtime is k6 and Go defaults, so a run that sets them back explicitly matches older runs.
# The VU ceiling joins the key only when it is not the sweep.js default of 2,048 (absent reads as 2,048).
def latk6rt($f): (first(.[$f].points[]?.measurement.k6_runtime | select(. != null)) // null) as $rt
  | ($rt.gomemlimit // "off") as $mem | ($rt.vu_ceiling // 2048) as $vuc
  | "gogc=\($rt.gogc // "100"),graceful_stop=\($rt.graceful_stop // "30s"),gomemlimit="
    + (if $mem == "off" then "off" else ($rt.source.gomemlimit // "env") end)
    + (if $vuc == 2048 then "" else ",vu_ceiling=\($vuc)" end);
# The multi-k6 headline also keys on the rule that set it and on the SUT diagnostics tier: a
# GC-masked ceiling and a whole-rung one, two bounds, or a run with and without the SUT GC file
# log are different measurements and never share a baseline.
def latrw: (.serving_rw_multik6 // {}) as $r
  | "\($r.sweep.latency_window.settle_s // null)|\($r.headline_rule.name // "unmasked_p99")|\($r.headline_rule.p99_max_ms // $r.headline.p99_max_ms // null)|\($r.gc_masked.min_quiet_s // null)|\(.config.jvm_diagnostics // "standard")";
def latfp($f): if $f == "serving_rw_multik6" then latrw else
  (.[$f].sweep.latency_settle_s // null) as $s
  | if $f == "serving_hw_matrix" and $s != null then "\($s)|\(.[$f].client // "single")|\(latk6rt($f))" else $s end end;
def latpresent($f): if $f == "serving_rw_multik6" then ((.serving_rw_multik6 // {}).headline.healthy_ceiling_rps != null)
  else (((.[$f] // {}).points // []) | length) > 0 end;
# INFO-arm event-log budget fingerprint. The INFO SUT ran with a harness-forced 256 MiB
# budget until it was switched to the shipped default, which changes what info_* measure; info_*
# metrics compare only against runs with the same method. A run without the field forced 256 MiB.
def infofp: ((.info_log_level_arm // {}).config.event_log_budget.method // "fixed-268435456");
def infomeasured: (.info_log_level_arm // {}).measured == true;
# Main-SUT event-log budget fingerprint (the seventh break). The main SUT ran with the harness
# 256 MiB budget until it moved to the shipped default; behaviours.*, rig_valid_peak_achieved_rps and the
# tls13 handshake arm (the only one on the main SUT) compare only against runs with the same method. A run
# without the field forced 256 MiB.
def mainelsfp: ((.config // {}).event_log_budget.method // "fixed-268435456");
def mainelsmetric($m): ($m.bkey | startswith("behaviours.")) or $m.bkey == "rig_valid_peak_achieved_rps"
  or (($m.bkey | startswith("tls_handshake.")) and ($m.name | startswith("tls13.")));
# Clustered A/B method fingerprint. Until the paired A/B each arm was one window under a --cpus
# quota, whose CFS throttling inflated the tails; clustered_state.* and the clustered behaviours
# (<op>_clustered, <op>_clustered_control) compare only against runs with the same method.
def clufp: ((.clustered_state // {}).method // "single-window-cpu-quota");
def clupresent: (((.clustered_state // {}).arms // {}) | length) > 0;
def clumetric($m): ($m.bkey | startswith("clustered_state."))
  or (($m.bkey | startswith("behaviours.")) and ($m.name | test("_clustered(_control)?[.][^.]+$")));

# JMH methodology fingerprint of the HEAD run (baseline-discontinuity guard).
# microbench / microbench_extra metrics are only comparable against baseline runs
# measured under the SAME .config.jmh — a methodology change (e.g. -f1 6s warmup ->
# -f2 4s warmup) can shift absolute timings, which against a differently-measured
# baseline would fire a SPURIOUS gating regression. So the microbench baseline is the
# subset of runs whose fingerprint matches head; a methodology change self-invalidates
# its own baseline (those metrics take the no-baseline branch until history repopulates
# under the new config), and NO false red fires. Historical runs (no .config.jmh, null)
# never match a new fingerprinted head, so they drop out cleanly. All OTHER metrics
# (k6/growth/rps) keep using the full baseline — this filter is microbench-only.
(.config.jmh // null) as $headjmh
# k6 methodology fingerprint (the k6-side analogue of the JMH .config.jmh guard
# above). The k6 behaviour arms are only comparable against baseline runs measured
# under the SAME methodology, and for k6 that signature is the SET OF CONCURRENTLY-
# RUNNING ARMS (the sorted .behaviours keys) — and nothing else. Two reasons this
# is the right and ONLY key:
#   1. It captures the change that actually broke comparability: five arms
#      (template_mustache/javascript + large_1mb/10mb/file) were added that contend
#      on the core-limited SUT and shift the latencies of the historical arms
#      (match/forward/template/large). Comparing the new 9-arm mix against a
#      baseline measured under the old 4-arm mix is a silently-incomparable baseline
#      (the false-green class the JMH fingerprint fixes; the asymmetry the review
#      flagged).
#   2. It ALREADY encodes the image variant: the JavaScript and file arms exist iff
#      the -graaljs / file-body capability is enabled, so stock-vs-graaljs is
#      implicit in the arm set. It must NOT also key on .config.image_digest — that
#      is the RepoDigest of the MUTABLE mockserver-snapshot-graaljs tag, which
#      changes on every snapshot rebuild (≈ every master merge). The daily/dispatched
#      perf job always pulls the freshest snapshot, so the head digest would match
#      ZERO prior runs, permanently emptying the k6 baseline and turning every arm into a
#      perpetual :new: that never gates — resetting PRECISELY when app code changes,
#      the event these arms exist to catch. (Pre-fix runs also stored a bare image ID,
#      not a RepoDigest, so they could never match either.) The arm set is stable
#      run-to-run: it performs the intended ONE-TIME reset across the 4->9-arm
#      transition, re-arms after MIN_BASELINE matching runs, and then compares
#      straight across snapshot rebuilds (the whole point of a baseline).
# This filter is behaviours-only. growth/sweep/forward are single-arm scenarios not
# affected by the regression arm mix, so they keep the FULL baseline and DO compare
# across the stock->graaljs image change unfiltered — acceptable because the extra
# GraalJS jars are inert for non-JS paths (loaded lazily only when a JS template
# renders): growth.* and rig_valid_peak_achieved_rps are non-gating, and forward.error_rate
# (gating) is a connection-pool guard the template engine cannot influence.
| (.agent.instance_type // "") as $headinstance
| ((.behaviours // {}) | keys | sort) as $headarms
| infofp as $headinfofp
| mainelsfp as $headelsfp
| clufp as $headclufp
| $baseline as $ballruns
| [ $ballruns[] | select((.config.jmh // null) == $headjmh) ] as $bmicroruns
| [ $ballruns[] | select(((.behaviours // {}) | keys | sort) == $headarms) ] as $bk6runs
| [ $ballruns[] | select(infofp == $headinfofp) ] as $binforuns
| [ $ballruns[] | select(mainelsfp == $headelsfp) ] as $belsruns
| [ $bk6runs[] | select(mainelsfp == $headelsfp) ] as $bk6elsruns
| bmapof($ballruns) as $bmapAll
| bmapof($bmicroruns) as $bmapMicro
| bmapof($binforuns) as $bmapInfo
| bmapof($belsruns) as $bmapEls
| bmapof($bk6elsruns) as $bmapK6Els
| bmapof([ $ballruns[] | select(clufp == $headclufp) ]) as $bmapClu
| bmapof([ $bk6elsruns[] | select(clufp == $headclufp) ]) as $bmapK6ElsClu
# Hardware-matched subsets, for metrics whose VALUE moves with the machine.
# A timing figure from a different instance type is not a comparable sample, and a
# rolling median that spans a hardware change absorbs the step change instead of
# reporting it. Metrics marked `hw` in perf-budgets.json compare only against runs
# from the same instance type; everything else — allocation bytes, allocation
# ratios, error rates — keeps the FULL baseline, because those are properties of
# the code path rather than the machine.
#
# There are THREE categories here, not two, and missing the third is easy: THREAD
# counts are neither timings nor code-path properties. They track the core count
# (actionHandlerThreadCount defaults to max(5, availableProcessors), and the GC and
# JIT pools scale too), so a 16 -> 48 vCPU move shifts them and they are marked
# `hw` as well.
#
# But do not generalise that to every count. `laptop.*.tcp_sockets` is an lsof line
# count of listen + established sockets, driven by instances and connections rather
# than cores, so it is deliberately NOT marked. The test is "does this move when the
# core count moves", not "is this a count".
#
# The split matters because three of the four gating metrics this script evaluates
# are hardware-INdependent, so filtering the whole baseline would have blinded most
# of its gating during a migration — exactly when a regression is most likely.
| [ $ballruns[] | select(((.agent.instance_type // "") == $headinstance) and ($headinstance != "")) ] as $bhwruns
| [ $bmicroruns[] | select(((.agent.instance_type // "") == $headinstance) and ($headinstance != "")) ] as $bmicrohwruns
| [ $binforuns[] | select(((.agent.instance_type // "") == $headinstance) and ($headinstance != "")) ] as $binfohwruns
| [ $belsruns[] | select(((.agent.instance_type // "") == $headinstance) and ($headinstance != "")) ] as $belshwruns
| [ $bk6elsruns[] | select(((.agent.instance_type // "") == $headinstance) and ($headinstance != "")) ] as $bk6elshwruns
| bmapof($bhwruns) as $bmapAllHw
| bmapof($bmicrohwruns) as $bmapMicroHw
| bmapof($binfohwruns) as $bmapInfoHw
| bmapof($belshwruns) as $bmapElsHw
| bmapof($bk6elshwruns) as $bmapK6ElsHw
| bmapof([ $bhwruns[] | select(clufp == $headclufp) ]) as $bmapCluHw
| bmapof([ $bk6elshwruns[] | select(clufp == $headclufp) ]) as $bmapK6ElsCluHw
| ({serving_percore: latfp("serving_percore"), serving_multiproc: latfp("serving_multiproc"),
    serving_hw_matrix: latfp("serving_hw_matrix"), serving_rw_multik6: latfp("serving_rw_multik6")}) as $headlat
| ($headlat | with_entries(.key as $f | .value as $v | .value = {
    all: bmapof([ $ballruns[] | select(latfp($f) == $v) ]),
    hw: bmapof([ $bhwruns[] | select(latfp($f) == $v) ]),
    comparable: ([ $ballruns[] | select(latpresent($f) and latfp($f) == $v) ] | length),
    other: ([ $ballruns[] | select(latpresent($f) and latfp($f) != $v) ] | length) })) as $bmapLat
| ([ [ . | metrics ][] | select(.value != null or .unmeasured == true) ]) as $headmetrics
# FAIL CLOSED: any run metric with a non-null value whose budget key is absent
# from the committed perf-budgets.json is reported as `missing` (the bash caller
# turns a non-empty `missing` into a red build). A missing budget must never be
# silently skipped — that would default the metric to "no floor".
| ([ $headmetrics[] | select(($budgets[.bkey]) == null) | {name:.name, bkey:.bkey} ]) as $missing
| { baseline_total: ($ballruns|length),
    baseline_microbench_comparable: ($bmicroruns|length),
    baseline_k6_comparable: ($bk6runs|length),
    head_jmh_present: ($headjmh != null),
    head_k6fp_present: (($headarms | length) > 0),
    head_info_measured: infomeasured,
    head_info_budget_method: $headinfofp,
    baseline_info_comparable: ([ $binforuns[] | select(infomeasured) ] | length),
    baseline_info_other: ([ $ballruns[] | select(infomeasured and (infofp != $headinfofp)) ] | length),
    head_main_els_method: $headelsfp,
    baseline_main_els_comparable: ($belsruns | length),
    baseline_main_els_other: ([ $ballruns[] | select(mainelsfp != $headelsfp) ] | length),
    head_clu_method: (if clupresent then $headclufp else null end),
    baseline_clu_comparable: ([ $ballruns[] | select(clupresent and clufp == $headclufp) ] | length),
    baseline_clu_other: ([ $ballruns[] | select(clupresent and clufp != $headclufp) ] | length),
    sweep_latency_reset: ([ $headmetrics[] | .bkey | latfam | select(. != null) ] | unique
      | map(. as $fam | {family: ., head_settle_s: $headlat[.], comparable: $bmapLat[.].comparable, other: $bmapLat[.].other,
             head_window: ($headlat[.] | if $fam == "serving_rw_multik6" then (split("|") | "settle \(.[0])s, rule \(.[1]), p99 bound \(.[2]) ms, min quiet \(.[3]) s, SUT diagnostics \(.[4])")
                                          elif type == "string" then (split("|") | "settle \(.[0])s, \(.[1]) client, k6 \(.[2] // "defaults")")
                                          else "settle \(. // "none")s" end)})
      | map(select(.other > 0))),
    bands_unused: (([ $headmetrics[] | .bkey ] | unique) as $hk
      | [ $budgets | to_entries[] | select((.value | type) == "object" and (.key | IN($hk[])))
          | .key as $bk | ((.value.bands // {}).metrics // {}) | keys[]
          | select(. as $n | [$headmetrics[] | select(.bkey == $bk and .name == $n)] | length == 0) ]) } as $meta
| if ($missing | length) > 0
  then ($meta + { missing:$missing, rows:[], count:0, gating_count:0, nongating_count:0 })
  else
    [ $headmetrics[]
      | . as $m0
      | ($budgets[$m0.bkey]) as $b
      # gating is OPTIONAL in a budget entry: an omitted `gating` means notify-only
      # (a flag annotates but does not fail the build), so default it to false.
      | ($m0 + {dir:$b.dir, min_pct:$b.min_pct, floor:$b.floor, gating:($b.gating // false), hw:($b.hw // false),
                min_abs:(if (($b.min_abs | type) == "number") then $b.min_abs else 0 end),
                bimodal:(if ((($b.bimodal // {}).metrics // {}) | has($m0.name)) then $b.bimodal else null end)}) as $m
      # microbench(_extra) compare only against JMH-config-matching baselines;
      # behaviours (k6 arms) compare only against k6-fingerprint-matching baselines
      # (same image + arm set). growth/sweep/forward keep the full baseline.
      | ($m.bkey | latfam) as $lf
      | (if $lf != null then ($bmapLat[$lf][if $m.hw then "hw" else "all" end][$m.name] // [])
         elif clumetric($m) then
           (if ($m.bkey|startswith("behaviours")) then (if $m.hw then $bmapK6ElsCluHw else $bmapK6ElsClu end)
            else (if $m.hw then $bmapCluHw else $bmapClu end) end)[$m.name] // []
         elif $m.hw then
           (if ($m.bkey|startswith("microbench")) then ($bmapMicroHw[$m.name] // [])
            elif ($m.bkey|startswith("behaviours")) then ($bmapK6ElsHw[$m.name] // [])
            elif mainelsmetric($m) then ($bmapElsHw[$m.name] // [])
            elif ($m.bkey|startswith("info_")) then ($bmapInfoHw[$m.name] // [])
            else ($bmapAllHw[$m.name] // []) end)
         else
           (if ($m.bkey|startswith("microbench")) then ($bmapMicro[$m.name] // [])
            elif ($m.bkey|startswith("behaviours")) then ($bmapK6Els[$m.name] // [])
            elif mainelsmetric($m) then ($bmapEls[$m.name] // [])
            elif ($m.bkey|startswith("info_")) then ($bmapInfo[$m.name] // [])
            else ($bmapAll[$m.name] // []) end)
         end) as $bv
      # Minimum comparable runs before a THRESHOLD is computed. A config/fingerprint
      # reset leaves the filtered baseline with 1..MIN_BASELINE-1 points, where MAD~0
      # collapses the threshold to median*(1+min_pct) and a single >min_pct excursion
      # would fire a SPURIOUS flag (a gating red for microbench; a misleading
      # notify-only flag for the non-gating behaviours). So BOTH the config-filtered
      # families — microbench and behaviours — require the FULL MIN_BASELINE of
      # fingerprint-matching runs before comparison resumes, matching the global
      # BASE_COUNT warm-up (which keys off the UNFILTERED baseline and so does NOT
      # cover these filtered subsets). The remaining metrics (growth/sweep/forward)
      # are never fingerprint-filtered and keep the existing >=1 behaviour.
      | (if $m.hw or ($lf != null) or clumetric($m) then $minbaseline
         elif ($m.bkey|startswith("microbench")) then $minbaseline
         elif ($m.bkey|startswith("behaviours")) then $minbaseline
         elif ($m.bkey|startswith("info_")) then $minbaseline
         elif mainelsmetric($m) then $minbaseline
         else 1 end) as $minreq
      | if $m0.unmeasured == true then {name:$m.name, head:null, gating:$m.gating, status:"not-measured"}
        elif $m0.count_error == true then {name:$m.name, head:$m.value, gating:$m.gating, status:"no-sample-count", regression:true}
        elif $m0.insufficient != null then {name:$m.name, head:$m.value, gating:$m.gating, status:"insufficient-samples",
                                           samples:$m0.insufficient.n, min_samples:$m0.insufficient.min}
        elif ($bv|length) < $minreq then {name:$m.name, head:$m.value, gating:$m.gating, status:"no-baseline"}
        else
          ($bv|median) as $med
          | (($bv|mad($med)) * 1.4826) as $sigma
          # A floor is only usable as a floor if it is a NUMBER. jq orders every
          # string above every number, so a quoted floor would win the `max` and
          # turn the threshold into a string that no value can ever exceed. The
          # budget file is schema-checked before this runs; this guard is the
          # second line, because a predicate can be reached by a path that did
          # not come through that check. Same for the head value and the metric
          # direction: a non-numeric head or an unrecognised dir is reported as a
          # hard error, never silently compared.
          | (($m.floor != null) and (($m.floor|type) == "number")) as $hasFloor
          | if (($m.value|type) != "number") then
              {name:$m.name, head:$m.value, gating:$m.gating, status:"not-numeric",
               regression:true}
            elif ($m.dir != "up" and $m.dir != "down") then
              {name:$m.name, head:$m.value, gating:$m.gating, status:"bad-direction",
               regression:true}
            elif (($m.floor != null) and (($m.floor|type) != "number")) then
              {name:$m.name, head:$m.value, gating:$m.gating, status:"bad-floor",
               regression:true}
            else
            (if $m.dir=="up" and $m.bimodal != null then ($bv | modesplit($m.bimodal.split_ratio; 3)) else null end) as $sp
            # A bimodal metric is judged against the baseline mode its head falls in: the
            # lower mode by the usual band, the upper by its median +/- max(3 sigma,
            # upper_band_pct). A head between the two modes is a level the window has not
            # shown, so it is flagged. A unimodal window falls through to the usual band.
            | (if $sp != null then
              ($sp.lo|median) as $ml | (($sp.lo|mad($ml)) * 1.4826) as $sl
              | (if $hasFloor then ([$ml + 3*$sl, $m.floor]|max) else ([$ml + 3*$sl, $ml*(1+$m.min_pct)]|max) end) as $tl
              | ($sp.up|median) as $mu
              | ([3*(($sp.up|mad($mu)) * 1.4826), $mu*$m.bimodal.upper_band_pct]|max) as $w
              | (if $hasFloor then ([$mu + $w, $m.floor]|max) else $mu + $w end) as $tu
              | {name:$m.name, head:$m.value, gating:$m.gating}
                + (if $m.value <= $tl then {baseline:$ml, threshold:$tl, mode:"lower", regression:false}
                   elif $m.value >= ($mu - $w) then {baseline:$mu, threshold:$tu, mode:"upper", regression:($m.value > $tu)}
                   else {baseline:$ml, threshold:$tl, mode:"between", regression:true} end)
            # min_abs (0 unless budgeted) is the smallest absolute move that can count, for a
            # metric whose median sits so near zero that the relative band is a fraction of a unit.
            elif $m.dir=="up"
              then ([(if $hasFloor then ([$med + 3*$sigma, $m.floor]|max)
                      else ([$med + 3*$sigma, $med*(1+$m.min_pct)]|max) end), $med + $m.min_abs]|max) as $th
                   | {name:$m.name, head:$m.value, baseline:$med, threshold:$th,
                      gating:$m.gating, regression: ($m.value > $th)}
              else ([(if $hasFloor then ([$med - 3*$sigma, $m.floor]|min)
                      else ([$med - 3*$sigma, $med*(1-$m.min_pct)]|min) end), $med - $m.min_abs]|min) as $th
                   | {name:$m.name, head:$m.value, baseline:$med, threshold:$th,
                      gating:$m.gating, regression: ($m.value < $th)} end)
            # A metric listed under `bands` in its budget entry has the relative min_pct term
            # replaced by median + its own history-derived band, never wider than min_pct, and
            # only while the window median is within level_pct of the median it was derived at.
            | ((($b.bands // {}).metrics // {})[$m.name]) as $band
            # A band bypassed by the level guard is marked stale, so lost sensitivity is visible.
            | if $band == null or $m.dir != "up" or (.threshold | type) != "number" then .
              elif ((($med - $band.median) | fabs) <= $b.bands.level_pct * $band.median)
              then ([$med + 3*$sigma, ([$med*(1+$m.min_pct), $med + $band.band]|min), $med + ($m.min_abs // 0)]|max) as $th
                   | . + {threshold:$th, regression:($m.value > $th), band:$band.band}
              else . + {band_stale:{band:$band.band, median:$band.median}} end
            | if $m.dir=="up" and $m.bimodal != null
              then . + ($bv | modeshare($m.value; $m.bimodal; $m.name))
                   | .regression = (.regression or ((.share // {}).flagged == true))
              else . end
            end
        end ] as $rows
    | ($meta + { missing: [],
        count: ([$rows[]|select(.regression==true)]|length),
        # A row whose budget or head value is malformed cannot be judged, so it
        # counts as GATING regardless of the gating flag on that metric: an
        # unjudgeable metric must never be reported as "notify-only ok".
        schema_error_count: ([$rows[]|select(.status=="not-numeric" or .status=="bad-direction" or .status=="bad-floor" or .status=="no-sample-count")]|length),
        gating_count: ([$rows[]|select((.regression==true and .gating==true) or .status=="not-numeric" or .status=="bad-direction" or .status=="bad-floor" or .status=="no-sample-count")]|length),
        nongating_count: ([$rows[]|select(.regression==true and .gating!=true and (.status|IN("not-numeric","bad-direction","bad-floor","no-sample-count")|not))]|length),
        rows: $rows })
  end
'
RESULT_CMP="$(jq -n \
  --slurpfile baselineFile "$WORK/baseline.json" \
  --slurpfile runFile "$RESULT" \
  --slurpfile budgetsFile "$BUDGETS_FILE" \
  --argjson minbaseline "$MIN_BASELINE" \
  '($baselineFile[0]) as $baseline | ($budgetsFile[0].budgets) as $budgets | ($runFile[0]) | '"$COMPARE")"

# FAIL CLOSED: a run metric with no budget entry must go RED, not be skipped.
MISSING_COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.missing | length')"
if [ "$MISSING_COUNT" -gt 0 ]; then
  MISSING_LIST="$(printf '%s' "$RESULT_CMP" | jq -r '.missing[] | "- **\(.name)** (budget key `\(.bkey)`)"')"
  annotate "error" ":no_entry: **Perf budget INCOMPLETE — cannot compare** — ${MISSING_COUNT} metric(s) in this run have no entry in \`$(basename "$BUDGETS_FILE")\` (commit \`${BUDGETS_COMMIT:0:10}\`):

${MISSING_LIST}

This fails the build deliberately (fail-closed): a metric with no committed budget must never be silently skipped. Add a budget entry (a reviewed diff) or remove the metric from the run producer."
  exit 1
fi

COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.count')"
GATING_COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.gating_count')"
NONGATING_COUNT="$(printf '%s' "$RESULT_CMP" | jq -r '.nongating_count')"

# microbench baseline discontinuity: when the head run's JMH methodology
# fingerprint (.config.jmh) differs from some/all baseline runs, only the matching
# subset is comparable for microbench(_extra) metrics. Surface that VISIBLY — a
# skipped comparison that looks like a clean pass is the false green we avoid.
MB_COMPARABLE="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_microbench_comparable // 0')"
BASE_TOTAL_CMP="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_total // 0')"
HEAD_JMH_PRESENT="$(printf '%s' "$RESULT_CMP" | jq -r '.head_jmh_present // false')"
MB_NOTE=""
if [ "$HEAD_JMH_PRESENT" = "true" ] && [ "$MB_COMPARABLE" -lt "$BASE_TOTAL_CMP" ]; then
  MB_NOTE="

:information_source: **microbench baseline reset — JMH methodology changed.** Only ${MB_COMPARABLE}/${BASE_TOTAL_CMP} baseline run(s) were measured under this run's JMH config (\`.config.jmh\`), so \`microbench.*\` / \`microbench_extra.*\` metrics compare ONLY against those (other metrics use the full baseline). Those metrics stay \`:new: new\` (no comparable baseline, NOT flagged) until at least ${MIN_BASELINE} config-matching runs exist — the same warm-up threshold the global baseline uses — so a methodology change self-invalidates its own baseline and no spurious gating regression fires against a differently-measured baseline (or against a 1..$((MIN_BASELINE-1))-point window where MAD~0 would collapse the threshold to the bare floor). Gating resumes once ${MIN_BASELINE} comparable runs have accrued."
fi

# A listed allocation band that did not apply this run: its benchmark has moved more than level_pct
# from the level the band was derived at, or no longer reports under that name (a rename).
BANDS_NOTE="$(printf '%s' "$RESULT_CMP" | jq -r '
  ([.rows[] | select(.band_stale != null) | .name]) as $stale | (.bands_unused // []) as $unused
  | if ($stale | length) + ($unused | length) == 0 then "" else
      "\n\n:information_source: **Per-benchmark allocation bands not applied** — these benchmarks fell back to the 5% band, so their small-change sensitivity is off until the bands are re-derived (docs/operations/performance-tuning.md, Per-benchmark allocation bands)."
      + (if ($stale | length) > 0 then " Level moved past level_pct: \($stale | map("`\(.)`") | join(", "))." else "" end)
      + (if ($unused | length) > 0 then " Listed but not reported this run (renamed or removed?): \($unused | map("`\(.)`") | join(", "))." else "" end)
    end')"

# k6 behaviour baseline discontinuity (MAJOR): when the head run's k6 methodology
# fingerprint (the SET OF BEHAVIOUR ARMS) differs from some/all baseline runs, only
# the matching subset is comparable for the k6 behaviour metrics. Surface that
# VISIBLY for the same reason — a skipped comparison that reads as a clean pass is
# the false green we avoid. NOTE the accuracy caveat: the behaviour metrics are
# NOTIFY-ONLY (they never fail the build), so what the reset prevents is a MISLEADING
# informational flag against an incomparable baseline, not a gating red.
K6_COMPARABLE="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_k6_comparable // 0')"
HEAD_K6FP_PRESENT="$(printf '%s' "$RESULT_CMP" | jq -r '.head_k6fp_present // false')"
K6_NOTE=""
if [ "$HEAD_K6FP_PRESENT" = "true" ] && [ "$K6_COMPARABLE" -lt "$BASE_TOTAL_CMP" ]; then
  K6_NOTE="

:information_source: **k6 behaviour baseline reset — arm set changed.** Only ${K6_COMPARABLE}/${BASE_TOTAL_CMP} baseline run(s) share this run's set of behaviour arms, so the per-behaviour \`*.p95_ms\` / \`*.p99_ms\` / \`*.error_rate\` metrics compare ONLY against those (growth / sweep / forward keep the full baseline). They stay \`:new: new\` (no comparable baseline, NOT flagged) until at least ${MIN_BASELINE} arm-set-matching runs exist — the same warm-up threshold the global baseline uses — so adding or removing an arm (or switching the -graaljs/file capability, which changes which arms run) self-invalidates the k6 baseline window instead of comparing a different arm mix straight across the discontinuity and producing a misleading (notify-only) flag. The fingerprint is the arm set ALONE, which is stable across snapshot rebuilds, so once ${MIN_BASELINE} matching runs have accrued the metrics re-arm and then compare normally across rebuilds. These metrics are notify-only, so this never blocks the build."
fi

# INFO-arm event-log budget discontinuity (docs/code/performance-measurement.md,
# "Saturation-series comparability break", the sixth break). Same visibility rule as above.
INFO_COMPARABLE="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_info_comparable // 0')"
INFO_OTHER="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_info_other // 0')"
INFO_METHOD="$(printf '%s' "$RESULT_CMP" | jq -r '.head_info_budget_method // "unknown"')"
INFO_NOTE=""
if [ "$(printf '%s' "$RESULT_CMP" | jq -r '.head_info_measured // false')" = "true" ] && [ "$INFO_OTHER" -gt 0 ]; then
  if [ "$INFO_COMPARABLE" -lt "$MIN_BASELINE" ]; then
    INFO_STATE="so they stay \`:new: new\` (not flagged) until ${MIN_BASELINE} exist; \`info_*\` re-baselines from the first run after the switch"
  else
    INFO_STATE="which is enough to compare; the other runs are ignored until they leave the baseline window"
  fi
  INFO_NOTE="

:information_source: **INFO-arm baseline reset — event-log budget changed.** This run's INFO SUT used event-log budget \`${INFO_METHOD}\`; ${INFO_OTHER} baseline run(s) measured the INFO arm under a different one (runs before the switch forced the harness's 256 MiB, \`fixed-268435456\`). The \`info_*\` metrics compare ONLY against the ${INFO_COMPARABLE} run(s) with the same budget, ${INFO_STATE}. Notify-only; this never blocks the build."
fi

# Main-SUT event-log budget discontinuity (the seventh break). Same visibility rule as above.
ELS_COMPARABLE="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_main_els_comparable // 0')"
ELS_OTHER="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_main_els_other // 0')"
ELS_METHOD="$(printf '%s' "$RESULT_CMP" | jq -r '.head_main_els_method // "unknown"')"
ELS_NOTE=""
if [ "$ELS_OTHER" -gt 0 ]; then
  if [ "$ELS_COMPARABLE" -lt "$MIN_BASELINE" ]; then
    ELS_STATE="so they stay \`:new: new\` (not flagged) until ${MIN_BASELINE} exist; they re-baseline from the first run after the switch"
  else
    ELS_STATE="which is enough to compare; the other runs are ignored until they leave the baseline window"
  fi
  ELS_NOTE="

:information_source: **Main-SUT baseline reset — event-log budget changed.** This run's main SUT used event-log budget \`${ELS_METHOD}\`; ${ELS_OTHER} baseline run(s) used a different one (runs before the switch forced the harness's 256 MiB, \`fixed-268435456\`). \`behaviours.*\`, \`rig_valid_peak_achieved_rps\` and \`tls_handshake.tls13.*\` compare ONLY against the ${ELS_COMPARABLE} run(s) with the same budget, ${ELS_STATE}. \`growth.*\` keeps its history: growth.js still runs at 256 MiB. Notify-only; this never blocks the build."
fi

# Sweep latency-window discontinuity (docs/code/performance-measurement.md, "Rung-onset
# exclusion"): shown only while the matching history is still below MIN_BASELINE.
SWEEPLAT_NOTE="$(printf '%s' "$RESULT_CMP" | jq -r --argjson minb "$MIN_BASELINE" '
  (.sweep_latency_reset // [])[] | select(.comparable < $minb)
  | "\n\n:information_source: **sweep latency baseline reset — \(.family).** \(.other) baseline run(s) measured its p50-gated healthy-ceiling metrics under a different sweep latency window (this run: \(.head_window); no settle = rung onset included). Those metrics compare only against runs with the same window — \(.comparable) so far — and stay `:new: new` (not flagged) until \($minb) exist. Notify-only; this never blocks the build."')"

# --- 5. render annotation -----------------------------------------------------
# The Status column distinguishes the two kinds of flagged regression:
#   :red_circle: REGRESSION (fails build)  -> a GATING metric crossed its budget
#   :warning: flagged (informational)      -> a non-gating metric crossed its budget
# and the Gate column states, for every row, whether it can fail the build — so a
# reader sees at a glance which flags are build-failing and which are pending-history.
TABLE="$(printf '%s' "$RESULT_CMP" | jq -r '
  "| Metric | Head | Baseline | Threshold | Gate | Status |\n|---|---:|---:|---:|:--|:--|",
  (.rows[] |
    "| \(.name) | \(.head // "n/a") | \(.baseline // "n/a" | if type=="number" then (.*1000|round)/1000 else . end)\(if .mode == null then "" elif .mode == "between" then " (between modes)" else " (\(.mode) mode)" end)\(if (.share // {}).flagged == true then " (\(.share.upper_runs) of last \(.share.runs) runs upper-mode, expected \(.share.expected * 100 | round)%)" else "" end)\(if .band_stale != null then " (band not applied: median moved past level_pct)" else "" end) | \(.threshold // "n/a" | if type=="number" then (.*1000|round)/1000 else . end) | \(if .gating==true then "gating" else "notify-only" end) | \(if .status=="no-baseline" then ":new: new" elif .status=="insufficient-samples" then "not judged: \(.samples) samples, under \(.min_samples)" elif .status=="no-sample-count" then ":red_circle: no sample count (cannot judge)" elif .status=="not-measured" then "not measured this run (see the growth.cpu_pct sample counts)" elif .regression then (if .gating==true then ":red_circle: REGRESSION (fails build)" else ":warning: flagged (informational)" end) else ":white_check_mark: ok" end) |")')"

# Context appended to the table: which sweep rungs were excluded (and why) so an
# excluded top rung cannot masquerade as a server ceiling, and the per-behaviour
# delivery ratio so a throughput shortfall with dropped_iterations>0 reads as a
# CLIENT limit rather than a server regression.
EXTRA="$(jq -r '
  def pct: if . == null then "n/a" else "\(. * 100000 | round / 1000)%" end;
  ([ (.saturation.excluded // [])[]
     | "- offered \(.offered_rps) rps: EXCLUDED — \(.reason)" ]) as $ex
  | ([ ((.behaviours // {}) | to_entries[])
       | select(.value.delivery_ratio != null)
       | "- \(.key): \(.value.throughput_rps)/\(.value.offered_rps) rps (ratio \(.value.delivery_ratio), dropped \(.value.dropped_iterations // 0))" ]) as $dr
  | ((.forward_guard // {}) as $fg
     | if $fg.status == "infra_error"
       then "\n\n:warning: **Forward-pool guard did NOT run this build** (k6 exit \($fg.k6_exit), no error_rate — upstream/container infra error, not a pool breach). The forward.error_rate row is therefore ABSENT, so the pool-exhaustion regression was NOT checked this run — investigate before trusting it."
       else "" end) as $fginfra
  | ([ (.sweep_tail.rungs // [])[] | select(.client_over_5ms_frac != null or .server_over_5ms_frac != null or .server_transport_over_5ms_frac != null)
       | "| \(.offered_rps) | \(.client_over_5ms_frac | pct) | \(.server_over_5ms_frac | pct) | \(.server_transport_over_5ms_frac | pct) |" ]) as $tail
  | ((.saturation.server_headroom_test // null) as $sht
     | if $sht == null or $sht == "active" then ""
       else "\n\n:warning: **Sweep server-headroom test was \($sht)**: rungs without server CPU samples were judged by the k6 CPU test alone, so a client-limited rung may count as rig-valid." end) as $shtnote
  | ((if ($ex|length) > 0 then "\n**Sweep rungs excluded (the measurement is not a server ceiling: load generator, idle-pool drops, errors, or a limit other than server CPU):**\n" + ($ex|join("\n")) else "" end)
    + $shtnote
    + (if ($dr|length) > 0 then "\n\n**Delivery ratio** (throughput/offered; a shortfall with dropped>0 is a CLIENT/VU limit, not a server regression):\n" + ($dr|join("\n")) else "" end)
    + $fginfra
    + (if ($tail|length) > 0 then "\n\n**Where the tail is** (share of requests over 5 ms per rung; notify-only): k6 after the settle window, beside two MockServer histograms: the request handler (decoded request to response hand-off) and the transport (decoded request head to the last response byte written to the socket, so it adds aggregation, encoding, the write and a slow reader). A client tail with no transport tail is outside MockServer once it has read the request: the rig, the network, the kernel, or the event loop not yet reading the socket. Tell those apart with k6 CPU against its pin (the excluded-rung reasons) and per-worker event-loop CPU (the deep run ceiling per-thread table).\n\n| Offered rps | k6 > 5 ms | Handler > 5 ms | Transport > 5 ms |\n|---:|---:|---:|---:|\n" + ($tail|join("\n")) else "" end))
' "$RESULT" 2>/dev/null || echo "")"

# Surface the streaming caveats in the RENDERED annotation, not only in
# source comments (the reviewer's MINOR): heap_bytes_per_stream is an UPPER BOUND
# dominated by log-ring retention, and the match A/B + absolute drift are measured
# against a deliberately-constrained SUT (a relative tripwire near the scheduler
# knee, not an absolute figure). Emitted only when a streaming row is present.
STREAM_NOTE=""
if printf '%s' "$RESULT_CMP" | jq -e '[.rows[]?.name | select(startswith("streaming."))] | length > 0' >/dev/null 2>&1; then
  STREAM_NOTE="

:information_source: **Streaming (item 12) metric notes:** \`streaming.heap_bytes_per_stream\` is an **upper bound** — it includes the streamed response bodies retained in the event-log ring, not just per-connection state. \`streaming.match_*_p95_ms\` / \`match_p95_ratio\` and the absolute \`intertoken_error_*\` are measured against a **deliberately CPU-/thread-constrained** SUT so the scheduler sits near its knee: they are RELATIVE tripwires (a regression pushes the ratio up), not absolute production figures. The idle-vs-load \`intertoken_error_p95_ratio\` normalises against the client-jitter floor, so it stays meaningful regardless of saturation."
fi

# SURFACE a skipped clustered A/B (a dark skip reads as a clean pass). compare is
# head-driven, so when the run took the absent-image / revision-mismatch / disabled
# skip branch it emits ZERO clustered_state metrics and no other signal — the A/B
# could skip on every run for months with only a buried stderr WARNING. It does NOT fail
# the build — it annotates. The attempted-but-produced-nothing case is a DIFFERENT
# gate: it is caught by the run's clustered_metrics_present validity check, which REDs
# before this step. Only fires for the NEW producer's explicit skip
# (clustered_attempted:false); a pre-feature run (key absent) is exempt.
#
# The clustered image is published on every master merge and pulled up front by
# perf-test-run.sh, so a skip means a real breakage; the note REPORTS the run's
# `clustered_skip_reason`. Still notify-only: see the STILL-NOTIFY-ONLY note below.
CLU_NOTE=""
CLU_ATTEMPTED_HEAD="$(jq -r 'if has("clustered_attempted") then (.clustered_attempted|tostring) else "absent" end' "$RESULT" 2>/dev/null || echo absent)"
CLU_ROWS="$(printf '%s' "$RESULT_CMP" | jq '[.rows[]?.name | select(startswith("clustered_state."))] | length' 2>/dev/null || echo 0)"
if [ "$ARM_MODE" != true ] && [ "$CLU_ATTEMPTED_HEAD" = "false" ] && [ "${CLU_ROWS:-0}" -eq 0 ]; then
  # Consecutive recent skips: newest-first, count leading explicit `false` runs,
  # stopping at the first `true` (a real measurement) or absent (pre-feature run).
  # Makes a permanent silent skip visible as a growing number rather than a green.
  # NB: use has()+explicit else, NOT `.clustered_attempted // null` — jq's `//`
  # treats a `false` LHS as empty and would map an explicit skip to null, zeroing
  # the count. has() distinguishes "false" (explicit skip) from "absent" (pre-feature).
  CLU_SKIPS="$(jq '[ .[] | (if has("clustered_attempted") then .clustered_attempted else null end) ] | reverse
    | (reduce .[] as $x ({n:0,stop:false};
        if .stop then . elif $x==false then {n:(.n+1),stop:false} else {n:.n,stop:true} end)).n' \
    "$WORK/baseline.json" 2>/dev/null || echo 0)"
  CLU_SKIP_SUFFIX=""
  [ "${CLU_SKIPS:-0}" -gt 0 ] && CLU_SKIP_SUFFIX=" It has now skipped **${CLU_SKIPS} consecutive** run(s) since the profile last measured — if this keeps growing, the clustered image is not reaching the perf agent."
  # Report the reason the RUN recorded rather than restating the old default guess.
  # "absent" covers a run produced before clustered_skip_reason existed.
  CLU_REASON="$(jq -r 'if has("clustered_skip_reason") then (.clustered_skip_reason // "" | if . == "" then "unrecorded" else . end) else "absent" end' "$RESULT" 2>/dev/null || echo absent)"
  case "$CLU_REASON" in
    image_absent)
      CLU_WHY="the clustered image (\`PERF_CLUSTERED_IMAGE\`, default \`mockserver/mockserver:mockserver-snapshot-clustered\`) was **not present on the perf agent**. That tag is published on every master merge by the \`:docker: build and push :snapshot\` step and pulled up front by \`perf-test-run.sh\`, so this means the **publish or the pull failed** — check both." ;;
    revision_mismatch)
      CLU_WHY="the clustered image was present but its \`org.opencontainers.image.revision\` did **not match the SUT image commit this run is filed under**, so the harness refused to measure it. Running it anyway would have filed a clustered/in-memory ratio against code the measured binary never contained. Usual cause: the **clustered push failed on a recent merge**, leaving \`mockserver-snapshot-clustered\` a commit behind \`mockserver-snapshot-graaljs\`." ;;
    disabled)
      CLU_WHY="the profile was **explicitly disabled** for this run (\`PERF_CLUSTERED=false\`)." ;;
    *)
      CLU_WHY="the run did not record a reason (\`clustered_skip_reason\`: \`${CLU_REASON}\`) — it predates that field, or the producer changed." ;;
  esac
  CLU_NOTE="

:information_source: **Clustered state A/B (item 13) did NOT run this build** — \`clustered_attempted\` is \`false\` and no \`clustered_state.*\` metrics were emitted, so the clustered/in-memory ratio is UNMEASURED this run. Reason: ${CLU_WHY} This does NOT fail the build (the whole clustered family is notify-only), but item 13 is unanswered for this commit — a persistent skip means the measurement is not happening at all.${CLU_SKIP_SUFFIX}"
fi

# A clustered A/B that ran but did not measure (compare reads no clustered_state rows), one whose
# containers were CPU-throttled, one whose throttling could not be read, and a method change in its
# baseline. All notify-only.
CLU_STATE_NOTE="$(jq -r '
  (.clustered_state // {}) as $c | ($c.throttling // {}) as $t
  | (if .clustered_attempted == true and $c.measured == false then
      "\n\n:warning: **Clustered state A/B did NOT measure this build** — no `clustered_state.*` rows were compared. Problems: " + ((($c.problems // []) | join("; ")) | if . == "" then "none recorded" else . end) + ". Notify-only: this does not fail the build."
    else "" end)
  + (if .clustered_attempted == true and (($c.warnings // []) | length) > 0 then
      "\n\n:information_source: **Clustered A/B arms missing from some windows** (their ratios are null): " + ($c.warnings | join("; ")) + "."
    else "" end)
  + (if .clustered_attempted == true and $t.material == true then
      "\n\n:warning: **Clustered A/B containers were CPU-throttled** (CPU mode `\($c.cpu_pinning.mode // "unknown")`): share of CFS periods throttled in each container worst window: " + ([ ($t.by_role // {}) | to_entries[] | "\(.key) \(.value.max_window_throttled_period_pct)%" ] | join(", ")) + " (material at \($t.material_threshold_pct)%). A throttled window inflates its tail, so read the tail ratios of this run with care."
    elif .clustered_attempted == true and ($t | has("available")) and $t.available != true then
      "\n\n:information_source: **Clustered A/B throttling not recorded** — cgroup `cpu.stat` could not be read for every container and window, so whether CFS throttled them is unknown."
    else "" end)' "$RESULT" 2>/dev/null || echo "")"
CLU_COMPARABLE="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_clu_comparable // 0')"
CLU_OTHER="$(printf '%s' "$RESULT_CMP" | jq -r '.baseline_clu_other // 0')"
CLU_METHOD="$(printf '%s' "$RESULT_CMP" | jq -r '.head_clu_method // ""')"
if [ -n "$CLU_METHOD" ] && [ "$CLU_OTHER" -gt 0 ]; then
  if [ "$CLU_COMPARABLE" -lt "$MIN_BASELINE" ]; then
    CLU_STATE="so they stay \`:new: new\` (not flagged) until ${MIN_BASELINE} exist"
  else
    CLU_STATE="which is enough to compare; the other runs are ignored until they leave the baseline window"
  fi
  CLU_STATE_NOTE="${CLU_STATE_NOTE}

:information_source: **Clustered A/B baseline reset — method changed.** This run measured the clustered A/B as \`${CLU_METHOD}\`; ${CLU_OTHER} baseline run(s) used a different method (runs before the paired A/B are \`single-window-cpu-quota\`). \`clustered_state.*\` and the clustered behaviours (\`*_clustered\`, \`*_clustered_control\`) compare ONLY against the ${CLU_COMPARABLE} run(s) with the same method, ${CLU_STATE}. Notify-only; this never blocks the build."
fi

# Surface a pipelining A/B that ran but proved nothing (it is outside the run's validity block, so
# this note is the only signal), or one skipped for a reason other than being switched off.
PL_NOTE="$(jq -r '
  if .pipelining_inorder_attempted == true and (.pipelining_inorder.measured // false) != true then
    "\n\n:warning: **In-order pipelining A/B did NOT measure this build** — no `pipelining_inorder.*` rows were compared. Problems: " + (((.pipelining_inorder.problems // []) | join("; ")) | if . == "" then "none recorded" else . end) + ". Notify-only: this does not fail the build."
  elif .pipelining_inorder_attempted == false and ((.pipelining_inorder_skip_reason // "disabled") != "disabled") then
    "\n\n:information_source: **In-order pipelining A/B skipped** (`\(.pipelining_inorder_skip_reason)`)."
  elif (.pipelining_inorder.measured // false) == true then
    "\n\n:information_source: **In-order pipelining A/B:** `pipelining_inorder.<arm>.*_ratio` is http1PipeliningInOrder on / off on two fresh SUTs in this run. `match` and `forward` are k6 (never pipelined), so they should sit near 1.0; `pipelined` is the only arm where the gate holds requests. Its latency is per batch of pipelined requests from a Python client, so read its ratio, not its absolute figures."
  else "" end' "$RESULT" 2>/dev/null || echo "")"

# The FILE-body A/B is outside the validity block too, so this note is its only failure signal.
FB_NOTE="$(jq -r '
  if .file_body_attempted == true and (.file_body.measured // false) != true then
    "\n\n:warning: **FILE-body A/B did NOT measure this build** — no `file_body.*` rows were compared. Problems: " + (((.file_body.problems // []) | join("; ")) | if . == "" then "none recorded" else . end) + ". Notify-only: this does not fail the build."
  elif .file_body_attempted == false and ((.file_body_skip_reason // "disabled") != "disabled") then
    "\n\n:information_source: **FILE-body A/B skipped** (`\(.file_body_skip_reason)`)."
  elif (.file_body.measured // false) == true then
    "\n\n:information_source: **FILE-body A/B:** `file_body.<size>.*_ratio` is a FILE response body / the byte-identical inline STRING body, measured back to back on one fresh SUT at a fixed offered rate. Above 1.0 is the cost of the per-request file read and the hop to the file read pool. `throughput_ratio` sits near 1.0 unless the FILE arm could not keep up with the offered rate."
  else "" end' "$RESULT" 2>/dev/null || echo "")"

# Fold the provenance line, the pre-config-baseline warning, and the microbench +
# k6 baseline-reset notes into the body so every annotation (regression or clean)
# carries them.
EXTRA="${EXTRA}

${PROVENANCE}${HW_NOTE}${PRECFG_NOTE}${MB_NOTE}${BANDS_NOTE}${K6_NOTE}${INFO_NOTE}${ELS_NOTE}${SWEEPLAT_NOTE}${LAPTOP_INJVM_NOTE}${STREAM_NOTE}${CLU_NOTE}${CLU_STATE_NOTE}${PL_NOTE}${FB_NOTE}${PC_NOTE}${HWM_NOTE}${MP_NOTE}"

HEADER="Perf regression — \`${COMMIT:0:10}\` on \`${BRANCH}\` (baseline: ${BASE_COUNT} runs, median+MAD; budgets @ \`${BUDGETS_COMMIT:0:10}\`)"
# Legend folded into every flagged annotation so a reader knows why the build did
# (or did not) go red, and how a notify-only metric graduates to gating.
LEGEND="_Gating metrics_ (JMH \`*.time_per_op\` / \`*.alloc_bytes_per_op\`, \`forward.error_rate\`) **fail the build** when flagged. _Notify-only_ metrics are reported just as loudly but do NOT fail the build — they graduate to gating once they have >=10 clean runs of history and a budget derived from them (see docs/operations/performance-tuning.md -> \"Regression thresholds\"). A Baseline marked _lower mode_ or _upper mode_ is a bimodal metric judged against the baseline mode its head falls in; _between modes_ is a level the baseline window has not shown; _N of last M runs upper-mode_ means the share of runs in the upper mode has risen improbably far above that metric's historical share."

# Exit non-zero ONLY when at least one GATING metric is flagged — that non-zero
# exit is the regression notification (the pipeline goes red). A non-gating flag
# leaves the exit code at 0. Every other exit path above (invalid run, missing
# artifact, warming up) is unchanged and still exits 0.
EXIT_CODE=0
if [ "$GATING_COUNT" -gt 0 ]; then
  EXIT_CODE=1
  NONGATING_NOTE=""
  if [ "$NONGATING_COUNT" -gt 0 ]; then
    NONGATING_NOTE=" (plus ${NONGATING_COUNT} notify-only metric(s) flagged — informational, see table)"
  fi
  annotate "error" ":red_circle: **${GATING_COUNT} build-failing performance regression(s)** — ${HEADER}${NONGATING_NOTE}

${TABLE}
${EXTRA}

**This build FAILS**: a gating metric crossed its budget. Investigate the \`:red_circle:\`-marked metric(s) against recent commits.
${LEGEND}"
elif [ "$COUNT" -gt 0 ]; then
  annotate "warning" ":chart_with_downwards_trend: **${COUNT} performance regression(s) flagged — all notify-only, build NOT failed** — ${HEADER}

${TABLE}
${EXTRA}

_No gating metric was flagged, so this does not fail the build. Investigate the flagged metric(s) against recent commits._
${LEGEND}"
else
  annotate "success" ":white_check_mark: **No performance regressions** — ${HEADER}

${TABLE}
${EXTRA}"
fi

exit "$EXIT_CODE"

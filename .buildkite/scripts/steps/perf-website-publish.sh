#!/usr/bin/env bash
set -euo pipefail

# Close the loop from S3 back to the website (performance-programme item 19).
#
# WHAT THIS DOES
# --------------
# The daily perf pipeline writes a dated, self-describing run to S3 (item 0).
# The public "Scalability & Latency" page renders its figures from a COMMITTED
# data file (jekyll-www.mock-server.com/_data/perf_figures.json). Nothing joined
# the two, so the published figures drifted into a stale customer-facing claim
# (docs/plans/performance-programme.md, Finding 1). This step regenerates that
# data file from the latest VALID run in S3 and, when the committed figures have
# genuinely gone stale or moved, EMITS THE REFRESH AS BUILD ARTIFACTS: a
# ready-to-apply patch plus the regenerated files themselves.
#
# It runs at the TAIL of the daily run, NON-GATING: a failure here never blocks
# a merge and never fails the perf comparison. But it is FAIL-CLOSED about what
# it publishes (see below) — it will refuse to publish rather than ship a wrong
# or unverifiable number.
#
# WHY A PATCH ARTIFACT, NOT A PUSH / PR
# -------------------------------------
# The figures are a customer-facing claim, so a human should look at a swing
# before it ships — AND a human is required anyway to reconcile the hand-authored
# numbers on the page that this refresh does not touch (see the artifact note).
# This step ALSO cannot push: the `perf` queue's IAM role grants only S3 on the
# perf-results bucket; it holds NO git/gh credentials (those live on the release
# stack alone). So instead of pushing a branch or opening a PR, it commits the
# refresh to a fresh LOCAL branch and emits that commit as a `git format-patch`
# patch (applied with `git am`) plus the regenerated files, all as Buildkite
# artifacts. A stale page therefore surfaces as a downloadable patch on the daily
# build rather than as invisible rot; a human applies it and opens the PR.
#
# WHEN IT EMITS A PATCH (the trigger)
# -----------------------------------
# Only when the committed figure is more than PUBLISH_MAX_AGE_DAYS old (default
# 30) OR a headline metric has moved more than PUBLISH_MOVE_PCT (default 10%).
# Otherwise it exits 0 having changed nothing, so it does not emit a patch every
# day.
#
# It KEYS THE AGE OFF THE COMMITTED FIGURE'S published_utc, NOT off the age of the
# newest S3 object. Producer liveness (has the daily run stopped, or run and
# broken?) is a SEPARATE concern owned by perf-baseline-freshness.sh, which keys
# off Buildkite build liveness precisely because the producer is COMMIT-GATED and
# writes no new object on a quiet master — so an object-age gate cries wolf on a
# healthy-but-quiet master. This step does not re-derive that; it trusts the
# freshness step for producer health and only decides whether the PUBLISHED page
# has drifted from the latest measurement.
#
# WHAT IT PUBLISHES vs WITHHOLDS (fail-closed on honesty)
# -------------------------------------------------------
# The transform in lib/perf-website-figures.jq is the single authority on WHICH
# metrics reach the public page: the knee curve (healthy_ceiling_rps headline +
# peak_achieved_rps labelled degraded, with latency at each rung) and, when the
# run carries them, per-behaviour percentiles from the FIXED regression.js. It
# emits NONE of the internal-only regression detectors (JMH backstops, growth/
# soak live-set, event-log cost, forward-pool guard, AppCDS boolean, leak gate,
# the streaming match-A/B ratio against the constrained SUT, startup median-of-9,
# baseline-freshness). See that file's header and the plan's
# "What to publish versus what to gate internally".
#
# FAIL-CLOSED CONTRACT
# --------------------
# Never republish a stale/unverifiable number, and never blank the page:
#   * S3 unreachable / denied / empty      -> exit non-zero, touch NOTHING.
#   * newest run is not self-describing     -> exit non-zero, touch NOTHING.
#     (schema_version < 2 predates item 0: no config block, so the knee curve's
#      provenance line cannot be recorded — publishing it recreates exactly the
#      un-provenanced claim this item exists to kill. NOTE: schema version and the
#      regression.js fix date are INDEPENDENT axes — a schema>=2 run can still be
#      pre-fix — so the behaviours artefact is gated SEPARATELY by the post-fix
#      marker in lib/perf-website-figures.jq, NOT by schema version.)
#   * newest run is invalid (validity.valid
#     != true) or missing the sweep/knee    -> exit non-zero, touch NOTHING.
#   * transform yields no headline           -> exit non-zero, touch NOTHING.
# In every refuse-to-publish case the COMMITTED page is left exactly as it was
# (last good figures still shown), and the step is loud (non-zero + annotation)
# so the producer problem is fixed rather than silently papering the page.
#
# TEST SEAMS (so this is verifiable without real S3)
#   PERF_PUBLISH_AWS_BIN   aws CLI to use            (default: aws)
#   PERF_PUBLISH_GIT_BIN   git to use                (default: git)
#   PERF_PUBLISH_DRY_RUN   =true: never write files / branch / commit / patch;
#                          just report the decision and the candidate (for local
#                          checks / CI preview). Writes to $PERF_PUBLISH_OUT if
#                          set so the candidate can be inspected.
#   PERF_PUBLISH_OUT       dry-run: write the candidate data file here

BUCKET="${PERF_RESULTS_BUCKET:-mockserver-ci-perf-results}"
BRANCH="${PERF_PUBLISH_SOURCE_BRANCH:-master}"
MAX_AGE_DAYS="${PUBLISH_MAX_AGE_DAYS:-30}"
MOVE_PCT="${PUBLISH_MOVE_PCT:-10}"
LAT_MULT="${PUBLISH_LAT_MULT:-3}"
KEEP_FRAC="${PUBLISH_KEEP_FRAC:-0.95}"
# Date floor for publishing per-behaviour percentiles: the regression.js tail fix
# (Finding 3) merged 2026-09-16, and schema>=2 does NOT imply post-fix, so a run
# older than this is withheld unless it carries an explicit config.regression_js_fixed
# stamp. See lib/perf-website-figures.jq's post-fix gate.
BEH_FIX_DATE="${PUBLISH_REGRESSION_FIX_DATE:-2026-09-16}"
REGION="${AWS_REGION:-eu-west-2}"

AWS_BIN="${PERF_PUBLISH_AWS_BIN:-aws}"
GIT_BIN="${PERF_PUBLISH_GIT_BIN:-git}"
DRY_RUN="${PERF_PUBLISH_DRY_RUN:-false}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JQ_FILTER="${SCRIPT_DIR}/lib/perf-website-figures.jq"
REPO_ROOT="${PERF_PUBLISH_REPO_ROOT:-$("$GIT_BIN" -C "$SCRIPT_DIR" rev-parse --show-toplevel 2>/dev/null || echo "")}"
[ -n "$REPO_ROOT" ] || REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
SITE_DIR="${REPO_ROOT}/jekyll-www.mock-server.com"
DATA_FILE="${SITE_DIR}/_data/perf_figures.json"
IMAGES_DIR="${SITE_DIR}/images"
CHART_DATA_DIR="${IMAGES_DIR}/perf-charts/data"
RENDER="${IMAGES_DIR}/perf-charts/render_perf_charts.py"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-publish.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

annotate() {
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context perf-website-publish || true
  fi
  printf '\n%s\n' "$2"
}

# Upload one SUPPLEMENTARY file as a Buildkite artifact under a clean (basename)
# path. Best-effort on purpose, matching the convention in perf-test-run.sh and
# its siblings: these are diagnostic adjuncts, and losing one must not fail a
# build whose real result is recorded elsewhere. No-ops cleanly when
# buildkite-agent is absent (local runs), like annotate().
#
# Do NOT use this for the patch. See upload_patch_or_fail below for why.
upload_artifact() {
  [ -f "$1" ] || return 0
  if command -v buildkite-agent >/dev/null 2>&1; then
    ( cd "$(dirname "$1")" && buildkite-agent artifact upload "$(basename "$1")" ) || true
  fi
}

# Upload THE PATCH, and fail loudly if it does not land.
#
# The patch is not an adjunct — it is the entire deliverable of this step, and the
# success annotation states as fact that it "is attached to this build". $WORK is
# trap-removed on EXIT, so a swallowed upload failure means the patch exists
# nowhere, while the step still exits 0 and claims delivery. That is a false green
# on the step's defining function, with no observable indicator anywhere: precisely
# the failure this rework exists to avoid, since the whole point of emitting a
# patch is that the refresh surfaces on the build rather than rotting invisibly.
#
# So this one does NOT get `|| true`. A failure here routes through fail(), the
# same path every refuse condition uses, and the step is `soft_fail: true` in the
# pipeline, so being loud costs a soft-failed step rather than a red daily build.
upload_patch_or_fail() {
  [ -s "$1" ] || fail "PATCH EMPTY" \
    "\`git format-patch\` produced an empty or truncated patch at \`$1\`. Nothing usable was generated, so nothing is claimed to have been delivered."
  command -v buildkite-agent >/dev/null 2>&1 || return 0
  ( cd "$(dirname "$1")" && buildkite-agent artifact upload "$(basename "$1")" ) || fail "PATCH UPLOAD FAILED" \
    "The refreshed figures were generated, but \`buildkite-agent artifact upload\` failed for \`$(basename "$1")\`, so the patch is NOT attached to this build and the working copy is discarded on exit. Nothing was shipped and nothing is recoverable from this build — re-run the step. See this job's log for the upload error."
}

fail() {
  annotate "error" ":no_entry: **Website perf publish: FAIL — $1**

$2

_The committed figures were left unchanged (the page still shows the last good numbers). This step publishes ONLY from a valid, self-describing run and never republishes a stale or unverifiable one. Fix the producer / access issue; nothing was shipped._"
  exit 1
}

echo "--- :chart_with_upwards_trend: perf website publish — source s3://${BUCKET}/runs/${BRANCH}/ (age>${MAX_AGE_DAYS}d or move>${MOVE_PCT}% emits a patch artifact)"

[ -f "$JQ_FILTER" ] || fail "TRANSFORM MISSING" "The figures transform \`${JQ_FILTER}\` is missing from the checkout."

# --- 1. find the newest run object in S3 (fail-closed) ------------------------
set +e
LS_OUT="$("$AWS_BIN" s3 ls "s3://${BUCKET}/runs/${BRANCH}/" --recursive --region "$REGION" 2>"$WORK/ls.err")"
LS_RC=$?
set -e
if [ "$LS_RC" -ne 0 ]; then
  fail "S3 UNREACHABLE / DENIED" \
    "Could not list \`s3://${BUCKET}/runs/${BRANCH}/\` (aws exit ${LS_RC}). S3 was unreachable, the bucket/prefix is wrong, or the perf-results grant is missing on this agent.

Detail:
\`\`\`
$(cat "$WORK/ls.err" 2>/dev/null | head -c 600)
\`\`\`"
fi
# Newest by key: keys are runs/<branch>/<ISO>__<sha>.json, ISO-lexicographic == chronological.
# `|| true` so a no-match grep (empty bucket) flows to the explicit check below and
# gets the clear NO_RUNS annotation rather than tripping `set -e` with a bare exit.
NEWEST_KEY="$(printf '%s\n' "$LS_OUT" | awk 'NF>=4 {print $4}' | { grep -E '\.json$' || true; } | sort | tail -n1)"
if [ -z "$NEWEST_KEY" ]; then
  fail "NO RUNS IN S3" \
    "\`s3://${BUCKET}/runs/${BRANCH}/\` contains no run JSON objects. There is nothing to publish from. (If the producer has genuinely never run, that is a producer problem, surfaced by perf-baseline-freshness.sh.)"
fi
echo "--- newest run object: ${NEWEST_KEY}"

set +e
"$AWS_BIN" s3 cp "s3://${BUCKET}/${NEWEST_KEY}" "$WORK/run.json" --only-show-errors --region "$REGION" 2>"$WORK/cp.err"
CP_RC=$?
set -e
[ "$CP_RC" -eq 0 ] && [ -s "$WORK/run.json" ] || fail "S3 DOWNLOAD FAILED" \
  "Could not download the newest run \`${NEWEST_KEY}\` (aws exit ${CP_RC}).

Detail:
\`\`\`
$(cat "$WORK/cp.err" 2>/dev/null | head -c 600)
\`\`\`"

# --- 2. validate the run is publishable (fail-closed) -------------------------
if ! jq -e . "$WORK/run.json" >/dev/null 2>&1; then
  fail "NEWEST RUN IS NOT JSON" "The newest object \`${NEWEST_KEY}\` is not valid JSON. Fails closed rather than publishing garbage."
fi

# `numbers // 1` keeps SCHEMA numeric even if the field is absent/null/non-numeric,
# so the -lt test below can never error out under set -e.
SCHEMA="$(jq -r '(.schema_version | numbers) // 1' "$WORK/run.json")"
if [ "${SCHEMA:-1}" -lt 2 ]; then
  fail "NEWEST RUN IS NOT SELF-DESCRIBING (schema_version=${SCHEMA})" \
    "The newest run predates the self-describing result schema (item 0): it has no \`config\` block, so its JVM / GC / heap / log level / hardware cannot be recorded on the published provenance line, and (for the same era) its regression.js percentiles predate the Finding-3 fix. Publishing it would recreate the un-provenanced claim this item exists to eliminate. Refusing."
fi

VALID="$(jq -r '.validity.valid // false' "$WORK/run.json")"
if [ "$VALID" != "true" ]; then
  FAILED_CHECKS="$(jq -r '[.validity.checks[]? | select(.ok==false) | "  - \(.name): \(.detail)"] | join("\n")' "$WORK/run.json" 2>/dev/null)"
  fail "NEWEST RUN IS INVALID (validity.valid != true)" \
    "The newest run \`${NEWEST_KEY}\` did not pass its own validity checks, so its numbers are not trustworthy to publish. Failing checks:
${FAILED_CHECKS:-  (none reported)}"
fi

if ! jq -e '(.config != null)' "$WORK/run.json" >/dev/null 2>&1; then
  fail "NEWEST RUN HAS NO CONFIG BLOCK" "schema_version claims >= 2 but there is no \`config\` block, so provenance cannot be recorded. Refusing."
fi
if ! jq -e '((.sweep.points // []) | map(select(.p50_ms != null and .offered_rps != null)) | length) > 0' "$WORK/run.json" >/dev/null 2>&1; then
  fail "NEWEST RUN HAS NO USABLE SWEEP" "The run carries no sweep points with latency, so the knee curve — the headline figure — cannot be published. Refusing."
fi

# --- 3. build the candidate figures via the shared transform ------------------
NOW_ISO="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
if ! jq --arg now "$NOW_ISO" --argjson lat_mult "$LAT_MULT" --argjson keep "$KEEP_FRAC" \
        --arg fix_date "$BEH_FIX_DATE" \
        -f "$JQ_FILTER" "$WORK/run.json" > "$WORK/candidate.json" 2>"$WORK/jq.err"; then
  fail "TRANSFORM FAILED" "The figures transform errored on the run:
\`\`\`
$(cat "$WORK/jq.err" 2>/dev/null | head -c 600)
\`\`\`"
fi
if ! jq -e '.headline != null and (.headline.healthy_ceiling_rps != null)' "$WORK/candidate.json" >/dev/null 2>&1; then
  fail "NO HEALTHY CEILING" \
    "No sweep rung qualified as a healthy ceiling (achieved within $(awk -v k="$KEEP_FRAC" 'BEGIN{printf "%.0f", (1-k)*100}')% of offered, zero errors, p50 within ${LAT_MULT}x the flat-region p50). The run may have been overloaded at every rung. Refusing to publish a headline that does not exist."
fi
HC="$(jq -r '.headline.healthy_ceiling_rps' "$WORK/candidate.json")"
PK="$(jq -r '.headline.peak_achieved_rps' "$WORK/candidate.json")"
BEH="$(jq -r 'if .behaviours == null then "withheld (run carries none / pre-fix)" else "\(.behaviours | length) arms" end' "$WORK/candidate.json")"
echo "--- candidate: healthy_ceiling_rps=${HC} peak_achieved_rps=${PK} behaviours=${BEH}"

if [ "$DRY_RUN" = "true" ] && [ -n "${PERF_PUBLISH_OUT:-}" ]; then
  cp "$WORK/candidate.json" "$PERF_PUBLISH_OUT"
  echo "--- dry-run: candidate written to ${PERF_PUBLISH_OUT}"
fi

# --- 4. compare against the COMMITTED figures — decide whether to emit a patch -
# max_move = largest absolute % change across the headline metrics both carry.
# age_days = age of the committed published_utc. Either over threshold triggers.
TRIGGER="no"; REASONS=()
if [ ! -f "$DATA_FILE" ]; then
  TRIGGER="yes"; REASONS+=("no committed figures exist yet")
  AGE_DAYS="n/a"; MAX_MOVE="n/a"
else
  PUB_UTC="$(jq -r '.source.published_utc // empty' "$DATA_FILE")"
  if [ -z "$PUB_UTC" ]; then
    TRIGGER="yes"; REASONS+=("committed figures carry no published_utc (cannot date them)")
    AGE_DAYS="unknown"
  else
    PUB_EPOCH="$(date -u -d "$PUB_UTC" +%s 2>/dev/null || date -u -j -f "%Y-%m-%dT%H:%M:%SZ" "$PUB_UTC" +%s 2>/dev/null || echo "")"
    if [ -z "$PUB_EPOCH" ]; then
      TRIGGER="yes"; REASONS+=("committed published_utc unparseable"); AGE_DAYS="unknown"
    else
      AGE_DAYS=$(( ( $(date -u +%s) - PUB_EPOCH ) / 86400 ))
      [ "$AGE_DAYS" -gt "$MAX_AGE_DAYS" ] && { TRIGGER="yes"; REASONS+=("committed figures are ${AGE_DAYS}d old (> ${MAX_AGE_DAYS}d)"); }
    fi
  fi
  # Percentage move across shared headline metrics.
  MAX_MOVE="$(jq -n --slurpfile a "$DATA_FILE" --slurpfile b "$WORK/candidate.json" '
    ($a[0].headline // {}) as $old | ($b[0].headline // {}) as $new
    | ["healthy_ceiling_rps","healthy_ceiling_p50_ms","peak_achieved_rps"]
    | map( . as $k | ($old[$k]) as $o | ($new[$k]) as $n
           | if ($o != null and $n != null and ($o|type)=="number" and ($n|type)=="number" and $o != 0)
             then (($n - $o) / $o * 100 | fabs) else empty end )
    | (max // 0) | (.*10|round)/10')"
  awk -v m="${MAX_MOVE:-0}" -v t="$MOVE_PCT" 'BEGIN{exit !(m+0 > t+0)}' \
    && { TRIGGER="yes"; REASONS+=("a headline metric moved ${MAX_MOVE}% (> ${MOVE_PCT}%)"); }
fi

if [ "$TRIGGER" != "yes" ]; then
  annotate "info" ":white_check_mark: **Website perf figures are current — no patch emitted.** Committed figures are ${AGE_DAYS}d old (window ${MAX_AGE_DAYS}d) and the largest headline move is ${MAX_MOVE}% (window ${MOVE_PCT}%). Latest run \`${NEWEST_KEY}\` (healthy_ceiling ${HC}, peak ${PK}) agrees closely enough to leave the page as-is."
  echo "OK: no refresh needed (age=${AGE_DAYS}d, max_move=${MAX_MOVE}%)"
  exit 0
fi
REASON_STR="$(printf '%s; ' "${REASONS[@]}")"
echo "--- trigger: ${REASON_STR}"

if [ "$DRY_RUN" = "true" ]; then
  annotate "warning" ":memo: **Website perf publish (dry-run) WOULD emit a patch artifact.** Reason: ${REASON_STR%; }
Candidate healthy_ceiling ${HC}, peak ${PK}, behaviours ${BEH}, from \`${NEWEST_KEY}\`. No files written, no branch, no commit, no patch (dry-run)."
  echo "DRY-RUN: would emit a patch (${REASON_STR%; })"
  exit 0
fi

# --- 5. write the refreshed data + chart data, regenerate charts, emit a patch --
# NEVER a push and NEVER a PR: the `perf` queue holds only the S3 perf-results
# grant, no git/gh credentials. Commit the refresh to a fresh LOCAL branch and
# emit that commit as a ready-to-apply patch (+ the regenerated files) as build
# artifacts; a human applies it and opens the PR (also required to reconcile the
# hand-authored numbers this refresh does not touch — see the annotation).
# Dirty-worktree pre-check: this runs in the CI checkout, and a `git add` of
# explicit paths on a tree with unrelated staged/modified changes could sweep them
# into the commit. Refuse if the tree is dirty BEFORE we touch anything, so a
# refusal still leaves the page untouched.
if [ -n "$("$GIT_BIN" -C "$REPO_ROOT" status --porcelain 2>/dev/null)" ]; then
  fail "WORKTREE NOT CLEAN" \
    "The checkout at \`${REPO_ROOT}\` has uncommitted changes before publishing. Refusing so an unrelated change is not swept into the figures commit. (A fresh CI checkout is clean; investigate what dirtied it.)"
fi
mkdir -p "$(dirname "$DATA_FILE")" "$CHART_DATA_DIR"
cp "$WORK/candidate.json" "$DATA_FILE"
# Chart data the committed renderer reads (perf-sweep.json + perf-result.json).
# The chart plots only rungs the run judged rig-valid, matching the published table -
# a rung excluded by derive_saturation measured the load generator, not the server, so
# plotting it would draw a curve the run declined to stand behind. An artifact with no
# saturation.ladder carries no rig-validity to filter on, so all points are kept.
jq '((.saturation.ladder // []) | map(select(.rig_valid == true) | .offered_rps)) as $rv
    | (((.saturation.ladder // []) | length) == 0) as $no_rig_info
    | {proto: (.sweep.proto // "http"),
       points: [ (.sweep.points // [])[] | select($no_rig_info or (.offered_rps | IN($rv[]))) ]}' \
  "$WORK/run.json" > "$CHART_DATA_DIR/perf-sweep.json"
# The full run record is copied verbatim - it carries its own rig_valid flags and
# exclusion reasons, so it stays complete rather than filtered.
cp "$WORK/run.json" "$CHART_DATA_DIR/perf-result.json"
# Regenerate the PNGs if the renderer's toolchain is present (best-effort: whoever
# applies the patch / CI can rerun it; a missing matplotlib must not fail the step).
if command -v python3 >/dev/null 2>&1 && python3 -c "import matplotlib" >/dev/null 2>&1; then
  # PNGs live in images/ (the renderer's default --out), one level ABOVE the data dir.
  python3 "$RENDER" --data "$CHART_DATA_DIR" --out "$IMAGES_DIR" || echo "WARNING: chart render failed — data files refreshed, PNGs left for whoever applies the patch" >&2
else
  echo "--- matplotlib absent — chart data refreshed; PNGs regenerate when the patch is applied" >&2
fi

WORK_BRANCH="perf/website-figures-$(date -u +%Y%m%d-%H%M%S)"
COMMIT_MSG="docs(perf): refresh published performance figures from ${NEWEST_KEY##*/}"

"$GIT_BIN" -C "$REPO_ROOT" checkout -b "$WORK_BRANCH" || fail "GIT BRANCH FAILED" "Could not create local branch \`${WORK_BRANCH}\`."
# Stage the data file, the chart source data, and any regenerated PNGs in images/.
"$GIT_BIN" -C "$REPO_ROOT" add "$IMAGES_DIR"/*.png 2>/dev/null || true
"$GIT_BIN" -C "$REPO_ROOT" add "$DATA_FILE" "$CHART_DATA_DIR"
"$GIT_BIN" -C "$REPO_ROOT" -c user.name="mockserver-perf-bot" -c user.email="ci@mock-server.com" commit -m "$COMMIT_MSG" \
  || fail "GIT COMMIT FAILED" "Nothing to commit or commit failed on \`${WORK_BRANCH}\`."

# Emit the commit as a ready-to-apply patch. `git format-patch` (applied with
# `git am`) is chosen over `git diff` because it carries the commit message (which
# records the source run) and author, and — with --binary — includes the binary
# PNG diffs, so `git am` recreates the commit exactly. A plain `git diff` would
# lose the message and, without --binary, could not carry the regenerated PNGs.
PATCH_NAME="${WORK_BRANCH##*/}.patch"
PATCH_FILE="$WORK/$PATCH_NAME"
if ! "$GIT_BIN" -C "$REPO_ROOT" format-patch -1 --binary --stdout HEAD > "$PATCH_FILE" 2>"$WORK/patch.err"; then
  fail "PATCH GENERATION FAILED" "\`git format-patch\` failed on \`${WORK_BRANCH}\`:
\`\`\`
$(cat "$WORK/patch.err" 2>/dev/null | head -c 600)
\`\`\`"
fi

# Upload the patch AND the regenerated files as build artifacts, so a reviewer can
# apply the change (the patch) or inspect the numbers without applying anything
# (the files). Each upload no-ops cleanly when buildkite-agent is absent (local).
upload_patch_or_fail "$PATCH_FILE"
upload_artifact "$DATA_FILE"
upload_artifact "$CHART_DATA_DIR/perf-sweep.json"
upload_artifact "$CHART_DATA_DIR/perf-result.json"
for _png in "$IMAGES_DIR"/*.png; do
  upload_artifact "$_png"
done

annotate "success" ":memo: **Website perf figures refreshed — patch emitted as a build artifact (nothing pushed).**

- **Source run:** \`${NEWEST_KEY}\`
- **Trigger:** ${REASON_STR%; } (largest headline move ${MAX_MOVE}%, window ${MOVE_PCT}%)
- **Healthy ceiling:** ${HC} req/s (headline) · **peak achieved:** ${PK} req/s (labelled degraded)
- **Per-behaviour percentiles:** ${BEH}

**Nothing was pushed and no PR was opened** — the \`perf\` queue holds only the S3 perf-results grant, no git/gh credentials. The refresh is attached to this build as \`${PATCH_NAME}\` (a \`git format-patch\` patch) plus the regenerated \`perf_figures.json\`, chart data, and PNGs.

**Apply it locally, then open the PR** (download \`${PATCH_NAME}\` from this build's artifacts first):
\`\`\`
git fetch origin ${BRANCH}
git checkout -b ${WORK_BRANCH} origin/${BRANCH}
git am ${PATCH_NAME}
git push -u origin ${WORK_BRANCH} && gh pr create --fill --base ${BRANCH}
\`\`\`

**A human must reconcile the hand-authored numbers this refresh does NOT touch.**
The patch rewrites only \`_data/perf_figures.json\` (and the chart data/PNGs). The
following in \`mock_server/performance.html\` are hand-authored — if a headline moved,
update them to match before merging:
  - the page \`description\` (front matter) — used as the meta description;
  - the JSON-LD \`schema_faq\` answers (front matter) — Google surfaces these;
  - matcher-scaling figures are a separate JMH source and are expected to differ.
The page body prose (itemprop headline, at-a-glance bullet, per-instance paragraph),
the provenance block, and both result tables are rendered from the data file and are
already updated by the patch."
echo "OK: patch emitted (${PATCH_NAME}); nothing pushed, no PR opened"
exit 0

#!/usr/bin/env bash
set -euo pipefail

# Baseline-freshness assertion for the daily performance-regression control.
#
# WHY THIS EXISTS
# ---------------
# The perf regression control (perf-test-compare.sh) compares every run against a
# rolling baseline in S3, refreshed by the DAILY perf pipeline. If that pipeline
# stops firing, or fires but its run breaks, the baseline silently goes stale and
# every subsequent comparison measures an ancient number while still reporting
# green — the decay this check exists to catch (a JMH backstop in this repo once went
# dark for days unnoticed).
#
# WHAT IT KEYS OFF (and what it deliberately does NOT)
# ----------------------------------------------------
# It asserts PRODUCER LIVENESS via the Buildkite API — that the daily
# `mockserver-performance-test` pipeline is still firing on its schedule and its
# most recent SCHEDULED build completed successfully. It does NOT gate on the raw
# age of the newest S3 object, because that conflates two different things:
#
#   * The producer is COMMIT-GATED: perf-test-guard.sh deliberately writes no new
#     object when master has not moved since the last run. So a genuinely quiet
#     stretch ages the newest object with a perfectly healthy producer — an
#     object-age gate cries wolf on quiet master and, sitting in a different
#     pipeline, dumps that false red on whoever next touches an infra path.
#
#   * A "ran, then skipped because master had not moved" scheduled build is the
#     producer working CORRECTLY. It shows up as a `passed` scheduled build, so
#     liveness treats it as healthy; object age cannot tell it apart from death.
#
# WHAT LIVENESS DETECTS — AND THE RESIDUAL GAP IT DOES NOT
# --------------------------------------------------------
# DETECTED: the schedule stopped firing (STALLED / NO_SCHEDULE), and the most
# recent COMPLETED run did not pass (NOT_PASSED — the run broke, or a gating
# regression fired). "Run" is a daily scheduled build or a MEASURED manual build: a
# non-scheduled build tagged [perf-run] (or started from the UI) with no build env
# overrides, whose run, micro-benchmark and compare steps all executed, or whose
# perf-run step ran and failed (compare is then skipped; it counts as a failed run).
# Whichever of the two is newest gives the verdict, so a passing manual run can clear a
# failed scheduled one and a failing one turns the check red. Only scheduled builds
# prove the cron is firing, so a manual run never rescues STALLED, and a manual build
# that never measured (guard skip, cancelled before perf-run finished, perf-run refused
# for its image revision) is ignored.
#
# NOT DETECTED: a scheduled build that passes without writing a fresh baseline. compare
# exits 1 on an invalid run and on a failed S3 write, but exits 0 without persisting when
# the run is baseline_eligible:false (diagnostics, tuned config, non-default rig, opt-in
# workload, release comparison) or the agent has no aws CLI. Confirming the write would
# need perf-bucket S3 reads, which the trigger queue lacks; a fail-closed read here would
# be a permanent false red. Liveness needs only the API token this queue already has.
#
# WHERE IT RUNS
# -------------
# In `mockserver-infra` (see pipeline-infra.yml) — a DIFFERENT pipeline from the
# producer, on the cheap `trigger` queue, and on its OWN daily Buildkite schedule
# (terraform/buildkite-pipelines/pipelines.tf) offset from the producer's 04:00
# slot. A check that lives inside the system it monitors dies with it; this one
# does not, and its own schedule gives it a guaranteed cadence rather than relying
# on someone happening to touch an infra path.
#
# FAIL-CLOSED CONTRACT
# --------------------
# Every uncertain outcome FAILS (non-zero exit). It never passes by defaulting.
# The classes are kept distinct in the message so the operator knows which one they
# have WITHOUT opening the code:
#   - NO_SCHEDULE : the producer's whole branch history has no daily scheduled build
#                   (the schedule was deleted/disabled, or the pipeline was renamed).
#   - STALLED     : the newest daily scheduled build is older than the window (the
#                   cron stopped firing) — including when none falls in the lookback
#                   and the older history inspected has none either — or none in the
#                   lookback has finished (the producer is wedged).
#   - NOT_PASSED  : the most recent COMPLETED run (scheduled, or a measured manual
#                   [perf-run] build newer than it) did not pass (the producer ran but
#                   broke, or flagged a gating regression — either way a human must
#                   look before the baseline is trusted).
#   - TRUNCATED   : the page cap was reached before the builds needed for a verdict
#                   were seen, so liveness is unknown.
#   - DENIED      : the Buildkite API token is missing/expired or unauthorised.
#   - TRANSPORT   : the Buildkite API was unreachable or returned an unusable body.
#   - CONFIG      : an override (window, lookback, page cap) is malformed, or the
#                   schedule switch (below) cannot be read.
#
# ON-DEMAND MODE: when local.perf_schedules_enabled in terraform/buildkite-pipelines/
# perf-schedules.tf is false there is no producer schedule to assert, so the step exits 0
# with a NOT CHECKED warning annotation and makes no API call.
#
# HOW IT QUERIES
# --------------
# By time (created_from, paged via the Link header), never by "the N newest builds",
# because manual runs crowd scheduled builds off any fixed page. Older history is read
# only when the lookback holds no daily scheduled build, to tell STALLED from NO_SCHEDULE.

PRODUCER_PIPELINE="${PERF_PRODUCER_PIPELINE_SLUG:-mockserver-performance-test}"
PRODUCER_BRANCH="${PERF_PRODUCER_BRANCH:-master}"
# The producer schedule is UNCONDITIONAL daily (perf-test-guard.sh runs every day
# even when it skips the heavy work), so a healthy producer emits a scheduled build
# every ~24h. 30h flags a fully-missed day while tolerating minor cron jitter.
# Unlike the old object-age threshold there is NO legitimate multi-day gap to
# absorb here, which is exactly why keying off build liveness lets the window be
# tight instead of a week.
PRODUCER_MAX_AGE_HOURS="${PERF_PRODUCER_MAX_AGE_HOURS:-30}"
# Must exceed the liveness window, or a recently-dead schedule would only be found by
# the slower older-history scan. +24h keeps at least two daily builds in view.
LOOKBACK_HOURS="${PERF_FRESHNESS_LOOKBACK_HOURS:-}"  # default PRODUCER_MAX_AGE_HOURS + 24, set once validated
# Pages of 100 per scan: bounds run time inside the step's 5 minute timeout.
MAX_PAGES="${PERF_FRESHNESS_MAX_PAGES:-5}"
PER_PAGE=100
ORG="${BUILDKITE_ORGANIZATION_SLUG:-mockserver}"
SECRET_ID="${BUILDKITE_API_TOKEN_SECRET_ID:-mockserver-build/buildkite-api-token-readonly}"
REGION="${AWS_REGION:-eu-west-2}"
# Indirection so tests can inject fakes; defaults to the real tools in CI.
AWS_BIN="${PERF_FRESHNESS_AWS_BIN:-aws}"
CURL_BIN="${PERF_FRESHNESS_CURL_BIN:-curl}"

WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-freshness.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# annotate(style, body): post a Buildkite annotation when on an agent, and always
# echo so the message is visible in a plain local/log run.
annotate() {
  if command -v buildkite-agent >/dev/null 2>&1; then
    printf '%s\n' "$2" | buildkite-agent annotate --style "$1" --context perf-baseline-freshness || true
  fi
  printf '\n%s\n' "$2"
}

# fail(class, human message): emit an error annotation and exit non-zero.
fail() {
  annotate "error" ":no_entry: **Perf baseline freshness: FAIL — $1**

$2

_Producer:_ Buildkite pipeline \`${PRODUCER_PIPELINE}\`, branch \`${PRODUCER_BRANCH}\`, scheduled builds (a newer measured \`[perf-run]\` build with no env overrides also counts for the pass/fail verdict) · _liveness window:_ ${PRODUCER_MAX_AGE_HOURS}h.
_This step lives in \`mockserver-infra\` (a different pipeline from the daily perf producer) and runs on its own schedule so it survives the producer dying. It asserts the daily perf pipeline is still running and passing; investigate that pipeline first._"
  exit 1
}

# to_epoch(iso8601): print UTC epoch seconds for a Buildkite timestamp (e.g.
# 2026-09-16T04:00:05.123Z). Handle GNU date (CI/Linux) and BSD date (local).
to_epoch() {
  local iso="$1" trimmed
  if date -u -d "$iso" +%s 2>/dev/null; then
    return 0
  fi
  trimmed="${iso%+00:00}"
  trimmed="${trimmed%Z}"
  trimmed="${trimmed%.*}"
  if date -u -j -f "%Y-%m-%dT%H:%M:%S" "$trimmed" +%s 2>/dev/null; then
    return 0
  fi
  return 1
}

epoch_to_iso() {
  date -u -d "@$1" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null || date -u -r "$1" +%Y-%m-%dT%H:%M:%SZ
}

# Validate before any $(( )): bash arithmetic reads "abc" as 0 and dies on "1.5".
require_positive_int() {
  if ! grep -qE '^[1-9][0-9]{0,5}$' <<<"${!1}"; then
    fail "CONFIG (${1} is not a positive integer)" "\`${1}\` is \`${!1}\`. Fails closed rather than querying with a malformed bound."
  fi
}
SCHEDULES_TF="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)/terraform/buildkite-pipelines/perf-schedules.tf"
# Test seam only: on an agent (BUILDKITE set) the committed switch is always read.
[ -z "${BUILDKITE:-}" ] && [ -n "${PERF_SCHEDULES_TF:-}" ] && SCHEDULES_TF="$PERF_SCHEDULES_TF"
SCHEDULES_LINES="$(grep -E '^[[:space:]]*perf_schedules_enabled[[:space:]]*=' "$SCHEDULES_TF" 2>/dev/null || true)"
SCHEDULES_ENABLED="$(sed -nE 's/^[[:space:]]*perf_schedules_enabled[[:space:]]*=[[:space:]]*(true|false)[[:space:]]*$/\1/p' <<<"$SCHEDULES_LINES")"
if [ -z "$SCHEDULES_LINES" ] || [ "$(wc -l <<<"$SCHEDULES_LINES")" -ne 1 ] || [ -z "$SCHEDULES_ENABLED" ]; then
  fail "CONFIG (cannot read the perf schedule switch)" "Expected exactly one \`perf_schedules_enabled = true|false\` line in \`${SCHEDULES_TF}\`, found:
\`\`\`
${SCHEDULES_LINES:-<none>}
\`\`\`
Fails closed rather than guessing whether the perf schedules are on."
fi
if [ "$SCHEDULES_ENABLED" = false ]; then
  annotate "warning" ":double_vertical_bar: **Perf baseline freshness: NOT CHECKED — perf schedules are disabled (on-demand mode)**

\`local.perf_schedules_enabled\` is \`false\` in terraform/buildkite-pipelines/perf-schedules.tf, so \`${PRODUCER_PIPELINE}\` has no scheduled producer and there is no cadence to assert. This is **not** a pass: nothing refreshes the perf baseline unless someone starts a \`${PRODUCER_PIPELINE}\` build from the UI or with \`[perf-run]\` in its message. A release needs a \`[perf-run]\` build on the release commit (docs/operations/release-process.md)."
  echo "NOT CHECKED: perf schedules disabled (local.perf_schedules_enabled = false) — producer liveness not asserted"
  exit 0
fi
require_positive_int PRODUCER_MAX_AGE_HOURS
require_positive_int MAX_PAGES
LOOKBACK_HOURS="${LOOKBACK_HOURS:-$(( PRODUCER_MAX_AGE_HOURS + 24 ))}"
require_positive_int LOOKBACK_HOURS
MAX_AGE_SECS=$(( PRODUCER_MAX_AGE_HOURS * 3600 ))

echo "--- :stopwatch: perf baseline freshness — producer liveness of ${PRODUCER_PIPELINE} (window ${PRODUCER_MAX_AGE_HOURS}h, lookback ${LOOKBACK_HOURS}h)"

if [ "$LOOKBACK_HOURS" -le "$PRODUCER_MAX_AGE_HOURS" ]; then
  fail "CONFIG (lookback not longer than the liveness window)" \
    "The lookback (${LOOKBACK_HOURS}h) must exceed the liveness window (${PRODUCER_MAX_AGE_HOURS}h), or a build just past the window could never be seen as STALLED with its real age."
fi

# --- 1. Buildkite API token (fail-closed: no token => DENIED) ------------------
{ set +x; } 2>/dev/null  # never let xtrace echo the secret
TOKEN="$("$AWS_BIN" --cli-connect-timeout 5 --cli-read-timeout 15 secretsmanager get-secret-value --secret-id "$SECRET_ID" --region "$REGION" --query SecretString --output text 2>"$WORK/tok.err" || true)"
if [ -z "$TOKEN" ] || [ "$TOKEN" = "None" ]; then
  fail "DENIED (no Buildkite API token)" \
    "Could not read the Buildkite API token from Secrets Manager (\`${SECRET_ID}\`). Without it, producer liveness cannot be confirmed, so this fails closed rather than assuming the producer is healthy.

Detail:
\`\`\`
$(cat "$WORK/tok.err" 2>/dev/null || true)
\`\`\`"
fi

# --- 2. paged, time-bounded queries of the producer's builds -------------------
# Pass the token via a curl --config file, NOT `-H "Authorization: Bearer ..."` on
# the command line: argv is world-readable at /proc/<pid>/cmdline, and the trigger
# queue packs multiple same-UID builds per host (agents_per_instance), so a
# co-tenant could read an argv token during the call. The config file lives in the
# 0700 mktemp dir under the existing EXIT trap. `set +x` above the token fetch
# already keeps it out of xtrace.
API="https://api.buildkite.com/v2/organizations/${ORG}/pipelines/${PRODUCER_PIPELINE}/builds"
CURL_CFG="$WORK/curl.cfg"
( umask 077; printf 'header = "Authorization: Bearer %s"\n' "$TOKEN" > "$CURL_CFG" )

# Only source==schedule builds prove the cron is alive. The weekly [perf-soak] and
# [perf-xl-weekly] schedules are not the baseline producer: counting either would let a
# fresh or passing weekly build mask a dead or failed daily. Every scan applies this filter.
NOT_WEEKLY='((.message // "") | test("\\[perf-(soak|xl-weekly)\\]") | not)'
DAILY_SCHEDULED_SEL="(.source == \"schedule\" and ${NOT_WEEKLY})"
# A measured manual build: the steps the guard dispatches for a full run all executed
# (passed or failed; a skipped build shows them `broken` or omits them), or `perf-run`
# itself ran and failed, which skips compare but is still a failed run. Build env
# overrides mark an A/B experiment, not the default configuration the daily run measures.
MEASURED_STEPS='["perf-run", "perf-microbench", "perf-compare"]'
FULLY_MEASURED="((.jobs // []) as \$jobs | all(${MEASURED_STEPS}[]; . as \$k | any(\$jobs[]; .step_key == \$k and (.state == \"passed\" or .state == \"failed\"))))"
MEASUREMENT_FAILED='any((.jobs // [])[]; .step_key == "perf-run" and (.state == "failed" or .state == "timed_out"))'
# perf-test-run.sh sets perf_run_refused=image_revision when it refused to measure an image the
# build's commit is not served; such a build measured nothing.
MEASURED_MANUAL_SEL="(.source != \"schedule\" and ${NOT_WEEKLY}
  and (.source == \"ui\" or ((.message // \"\") | test(\"\\\\[perf-run\\\\]\")))
  and ((.env // {}) == {})
  and ((.meta_data // {}).perf_run_refused != \"image_revision\")
  and (${FULLY_MEASURED} or ${MEASUREMENT_FAILED}))"
# A guard-skip (master unchanged) lands as `passed`: the producer working correctly.
IS_TERMINAL='(.state | . == "passed" or . == "failed" or . == "canceled" or . == "skipped" or . == "not_run" or . == "blocked")'
# Every scan keeps the daily scheduled builds and the finished measured manual builds,
# each tagged with its kind and trimmed to the fields read below.
KEEP="[ .[] | {number, source, state, created_at, web_url, message} as \$b
  | if ${DAILY_SCHEDULED_SEL} then \$b + {kind: \"scheduled\"}
    elif (${MEASURED_MANUAL_SEL}) and ${IS_TERMINAL} then \$b + {kind: \"manual\", compare_skipped: (${FULLY_MEASURED} | not)}
    else empty end ]"
SCHEDULED_ONLY='[ .[] | select(.kind == "scheduled") ]'

# api_get(header_file, body_file, curl args...)
api_get() {
  local hdr="$1" body="$2" rc=0
  shift 2
  "$CURL_BIN" -sS --max-time 15 --connect-timeout 5 --config "$CURL_CFG" \
    -D "$hdr" -o "$body" "$@" 2>"$WORK/curl.err" || rc=$?
  if [ "$rc" -ne 0 ]; then
    fail "TRANSPORT (Buildkite API unreachable)" \
      "The Buildkite API request failed at the transport level (curl exit ${rc}: DNS, network, endpoint, or timeout). Producer liveness cannot be confirmed, so this fails closed.

Detail:
\`\`\`
$(cat "$WORK/curl.err" 2>/dev/null || true)
\`\`\`"
  fi
}

# A 401/403 returns a JSON object {"message": ...}, not a builds array: DENIED.
check_body() {
  local msg
  if jq -e 'type == "array"' "$1" >/dev/null 2>&1; then
    return 0
  fi
  msg="$(jq -r '.message // empty' "$1" 2>/dev/null || true)"
  if grep -qiE 'authenticate|authoriz|authoris|token|forbidden|access denied|invalid' <<<"$msg"; then
    fail "DENIED (Buildkite API rejected the token)" \
      "The Buildkite API rejected the request (\"${msg}\"). The token is invalid, expired, or lacks read access to \`${PRODUCER_PIPELINE}\`. Fails closed."
  fi
  fail "TRANSPORT (unusable Buildkite API response)" \
    "The Buildkite API returned a body that is not a builds array and carries no recognisable auth error. Fails closed because producer liveness cannot be read.

First 300 bytes:
\`\`\`
$(head -c 300 "$1")
\`\`\`"
}

# Prints "next:<url>" for a rel="next" link, so an empty <> one fails the endpoint check.
next_link() {
  awk '{ sub(/\r$/, "") }
    tolower($0) ~ /^link:/ {
      n = split(substr($0, 6), parts, ",")
      for (i = 1; i <= n; i++) if (parts[i] ~ /rel="next"/) {
        s = parts[i]; sub(/^[^<]*</, "", s); sub(/>.*$/, "", s); print "next:" s; exit
      }
    }' "$1"
}

# scan(name, time_filter, stop_jq): collect daily scheduled builds, newest first, into
# $WORK/<name>.json (plus measured manual builds, see KEEP) until stop_jq holds, the last page (SCAN_EXHAUSTED=true), or the cap.
scan() {
  local name="$1" time_filter="$2" stop_jq="$3" url="" page=0 hdr body next
  SCAN_EXHAUSTED=false
  SCAN_PAGES=0
  SCAN_BUILDS=0
  printf '[]' > "$WORK/$name.json"
  while [ "$page" -lt "$MAX_PAGES" ]; do
    page=$(( page + 1 ))
    hdr="$WORK/$name.$page.hdr"
    body="$WORK/$name.$page.json"
    : > "$hdr"
    if [ -z "$url" ]; then
      api_get "$hdr" "$body" --get "$API" \
        --data-urlencode "branch=${PRODUCER_BRANCH}" \
        --data-urlencode "per_page=${PER_PAGE}" \
        --data-urlencode "$time_filter"
    else
      api_get "$hdr" "$body" --get "$url"
    fi
    check_body "$body"
    SCAN_PAGES=$page
    SCAN_BUILDS=$(( SCAN_BUILDS + $(jq 'length' "$body") ))
    jq -s ".[0] + (.[1] | ${KEEP})" "$WORK/$name.json" "$body" > "$WORK/$name.acc"
    mv "$WORK/$name.acc" "$WORK/$name.json"
    if jq -e "$stop_jq" "$WORK/$name.json" >/dev/null 2>&1; then
      return 0
    fi
    next="$(next_link "$hdr")"
    if [ -z "$next" ]; then
      SCAN_EXHAUSTED=true
      return 0
    fi
    next="${next#next:}"
    # The token is sent to whatever this names, so follow only this endpoint.
    case "$next" in
      "${API}?"*) url="$next" ;;
      *) fail "TRANSPORT (unexpected pagination link)" \
           "The Buildkite API's next-page link does not point at \`${API}\` (got \`${next:0:200}\`). Not following it with the token; fails closed." ;;
    esac
  done
}

NOW_EPOCH="$(date -u +%s)"
WINDOW_START="$(epoch_to_iso $(( NOW_EPOCH - LOOKBACK_HOURS * 3600 )))"
if [ -z "$WINDOW_START" ]; then
  fail "CONFIG (cannot format the lookback start)" "Neither GNU nor BSD \`date\` could format the lookback start. Fails closed."
fi

# --- 3. the lookback window -------------------------------------------------
# Stops at the first finished scheduled build; the API returns newest first, so every
# measured manual build newer than it has been read by then.
scan window "created_from=${WINDOW_START}" "any(.[]; .kind == \"scheduled\" and ${IS_TERMINAL})"
WINDOW_ALL="$(cat "$WORK/window.json")"
SCHED="$(jq -c "$SCHEDULED_ONLY" <<<"$WINDOW_ALL")"
SCHED_COUNT="$(jq 'length' <<<"$SCHED")"
WINDOW_PAGES=$SCAN_PAGES
WINDOW_BUILDS=$SCAN_BUILDS
WINDOW_EXHAUSTED=$SCAN_EXHAUSTED
INSPECTED="${WINDOW_BUILDS} build(s) on ${WINDOW_PAGES} page(s) created since \`${WINDOW_START}\` (${LOOKBACK_HOURS}h lookback)"

if [ "$SCHED_COUNT" -eq 0 ]; then
  if [ "$WINDOW_EXHAUSTED" != true ]; then
    fail "TRUNCATED (page cap reached inside the lookback window)" \
      "The newest ${INSPECTED} hold no daily scheduled build, but more builds in the window remain unread after ${MAX_PAGES} page(s). Liveness cannot be decided; fails closed. Raise \`PERF_FRESHNESS_MAX_PAGES\` if manual builds legitimately exceed $(( MAX_PAGES * PER_PAGE )) in ${LOOKBACK_HOURS}h."
  fi
  scan older "created_to=${WINDOW_START}" 'any(.[]; .kind == "scheduled")'
  OLDER="$(jq -c "$SCHEDULED_ONLY" "$WORK/older.json")"
  if [ "$(jq 'length' <<<"$OLDER")" -eq 0 ]; then
    if [ "$SCAN_EXHAUSTED" = true ]; then
      fail "NO_SCHEDULE (producer has no scheduled builds)" \
        "The producer pipeline \`${PRODUCER_PIPELINE}\` has NO daily \`source==schedule\` build anywhere in its \`${PRODUCER_BRANCH}\` history (inspected ${INSPECTED}, then all ${SCAN_BUILDS} older build(s)). The daily schedule has been deleted or disabled (or the pipeline was renamed). The perf baseline is no longer being refreshed on any cadence."
    fi
    fail "STALLED (no scheduled build within ${LOOKBACK_HOURS}h)" \
      "The producer pipeline \`${PRODUCER_PIPELINE}\` has no daily \`source==schedule\` build on \`${PRODUCER_BRANCH}\` in the last ${LOOKBACK_HOURS}h (inspected ${INSPECTED}), nor in the ${SCAN_BUILDS} older build(s) read before the ${MAX_PAGES}-page cap. Further older history was not read, so whether the schedule ever existed is unknown — either way it is not firing and the perf baseline is not being refreshed.

Check the \`${PRODUCER_PIPELINE}\` pipeline's schedule in Buildkite / terraform/buildkite-pipelines."
  fi
  SCHED="$OLDER"
fi

# Newest daily scheduled build (any state) — its age proves the cron is still FIRING.
NEWEST_CREATED="$(jq -r '.[0].created_at' <<<"$SCHED")"
NEWEST_NUMBER="$(jq -r '.[0].number' <<<"$SCHED")"
NEWEST_STATE="$(jq -r '.[0].state' <<<"$SCHED")"

CREATED_EPOCH="$(to_epoch "$NEWEST_CREATED" || true)"
if ! grep -qE '^[0-9]+$' <<<"$CREATED_EPOCH"; then
  fail "TRANSPORT (unparseable build timestamp)" \
    "The newest scheduled build (#${NEWEST_NUMBER}) has a created_at that could not be parsed: \`${NEWEST_CREATED}\`. Fails closed because its age cannot be determined."
fi

AGE_SECS=$(( NOW_EPOCH - CREATED_EPOCH ))
AGE_HOURS=$(( AGE_SECS / 3600 ))

if [ "$AGE_SECS" -gt "$MAX_AGE_SECS" ]; then
  fail "STALLED (no scheduled build within ${PRODUCER_MAX_AGE_HOURS}h)" \
    "The newest scheduled build of \`${PRODUCER_PIPELINE}\` (#${NEWEST_NUMBER}) is **${AGE_HOURS}h old** — older than the ${PRODUCER_MAX_AGE_HOURS}h window. The daily schedule has stopped firing. Every perf comparison since then has measured a stale baseline while reporting green.

- newest scheduled build: #${NEWEST_NUMBER} (${NEWEST_STATE}), created \`${NEWEST_CREATED}\` (${AGE_HOURS}h ago)

Check the \`${PRODUCER_PIPELINE}\` pipeline's schedule in Buildkite / terraform/buildkite-pipelines."
fi

# --- 4. most recent COMPLETED scheduled build must have PASSED ----------------
# Judge the most recent build that reached a verdict, so one in flight when this
# check runs does not read as a failure. It must be inside the lookback: an older
# pass says nothing about a producer whose recent builds never finish.
TERMINAL="$(jq -c "[ .[] | select(${IS_TERMINAL}) ][0] // empty" <<<"$SCHED")"
if [ -z "$TERMINAL" ]; then
  if [ "$WINDOW_EXHAUSTED" != true ]; then
    fail "TRUNCATED (page cap reached inside the lookback window)" \
      "Daily scheduled builds were found but none of them has finished in the newest ${INSPECTED}, and more builds in the window remain unread after ${MAX_PAGES} page(s). Fails closed. Raise \`PERF_FRESHNESS_MAX_PAGES\` if manual builds legitimately exceed $(( MAX_PAGES * PER_PAGE )) in ${LOOKBACK_HOURS}h."
  fi
  fail "STALLED (no scheduled build has completed in ${LOOKBACK_HOURS}h — producer wedged)" \
    "\`${PRODUCER_PIPELINE}\` has $(jq 'length' <<<"$SCHED") daily scheduled build(s) on \`${PRODUCER_BRANCH}\` in the last ${LOOKBACK_HOURS}h (newest #${NEWEST_NUMBER}, ${NEWEST_STATE}) but none has reached a terminal state. The producer is wedged (queued or running indefinitely), not producing baselines; an older pass cannot vouch for it. Fails closed."
fi
# The verdict comes from the newest finished run in the window: that scheduled build, or a
# measured manual build created after it (KEEP admits only finished ones, newest first).
SCHED_TERM_NUMBER="$(jq -r '.number' <<<"$TERMINAL")"
VERDICT="$(jq -c --argjson n "$SCHED_TERM_NUMBER" \
  '[ .[] | select(.kind == "manual" or (.kind == "scheduled" and .number == $n)) ][0]' <<<"$WINDOW_ALL")"
if [ "$(jq -r '.kind' <<<"$VERDICT")" = "manual" ]; then TERMINAL="$VERDICT"; fi
# A run whose perf-run step failed never passes, even if the build itself reads `passed`.
TERM_STATE="$(jq -r 'if .compare_skipped == true and .state == "passed" then "failed" else .state end' <<<"$TERMINAL")"
TERM_DETAIL="$(jq -r 'if .compare_skipped == true then "- its `perf-run` measurement step failed, so compare never ran and no baseline was written" else "" end' <<<"$TERMINAL")"
TERM_NUMBER="$(jq -r '.number' <<<"$TERMINAL")"
TERM_URL="$(jq -r '.web_url // ""' <<<"$TERMINAL")"
TERM_CREATED="$(jq -r '.created_at' <<<"$TERMINAL")"
TERM_KIND="$(jq -r 'if .kind == "manual" then "manual [perf-run]" else "scheduled" end' <<<"$TERMINAL")"

if [ "$TERM_STATE" != "passed" ]; then
  fail "NOT_PASSED (last completed ${TERM_KIND} run was '${TERM_STATE}')" \
    "The most recent COMPLETED ${TERM_KIND} build of \`${PRODUCER_PIPELINE}\` (#${TERM_NUMBER}, created \`${TERM_CREATED}\`) is **${TERM_STATE}**, not passed. The producer ran but did not succeed — it either broke or flagged a gating regression. Either way the freshest baseline cannot be trusted until a human looks.

${TERM_DETAIL:+${TERM_DETAIL}
}- build: ${TERM_URL:-#${TERM_NUMBER}}

Investigate that build before relying on the perf regression comparison."
fi

# --- 5. PASS ------------------------------------------------------------------
annotate "success" ":white_check_mark: **Perf baseline producer is live** — the daily \`${PRODUCER_PIPELINE}\` schedule is firing and its last completed run (${TERM_KIND}) passed.

- newest scheduled build: #${NEWEST_NUMBER} (${NEWEST_STATE}), ${AGE_HOURS}h ago (window ${PRODUCER_MAX_AGE_HOURS}h)
- last completed run: #${TERM_NUMBER} (${TERM_KIND}, passed)
- inspected: ${INSPECTED}

A passed scheduled build means the producer either ran and passed, or was correctly skipped by the commit guard because master had not moved — both are healthy."
echo "OK: producer live (newest scheduled #${NEWEST_NUMBER} ${AGE_HOURS}h ago, last completed #${TERM_NUMBER} passed, ${TERM_KIND})"
exit 0

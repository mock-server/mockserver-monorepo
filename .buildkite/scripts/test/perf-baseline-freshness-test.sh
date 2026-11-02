#!/usr/bin/env bash
# Network-free checks for perf-baseline-freshness.sh: every verdict class, against a fake
# Buildkite API that honours per_page/page/created_from/created_to and emits Link headers
# the way the real one does (default per_page 30, newest first).
# Run: .buildkite/scripts/test/perf-baseline-freshness-test.sh
#      (PERF_FRESHNESS_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_FRESHNESS_SCRIPT
F="${PERF_FRESHNESS_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-baseline-freshness.sh}"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-freshness-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
# Failures go to a file, not a variable: each case's `run` sits at the end of a pipe,
# so it runs in a subshell whose variables never reach the final tally.
: > "$WORK/fails"
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; echo "$1" >> "$WORK/fails"; }
BIN="$WORK/bin"
mkdir -p "$BIN"
TOKEN="fake-token-$$"
API_PREFIX="https://api.buildkite.com/v2/organizations/mockserver/pipelines/mockserver-performance-test/builds"

# A no-op buildkite-agent so a CI run of this test posts no annotations of its own.
printf '#!/usr/bin/env bash\ncat >/dev/null\n' > "$BIN/buildkite-agent"
cat > "$BIN/aws" <<'EOF'
#!/usr/bin/env bash
[ "${FAKE_NO_TOKEN:-}" = 1 ] && { echo "ExpiredToken: the SSO session has expired" >&2; exit 255; }
printf '%s\n' "$FAKE_TOKEN"
EOF
cat > "$BIN/curl" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf '%s\n' "$*" >> "$FAKE_LOG"
cfg="" url="" hdr="" out="" params=()
while [ $# -gt 0 ]; do
  case "$1" in
    --config) cfg="$2"; shift 2 ;;
    --get) url="$2"; shift 2 ;;
    --data-urlencode) params+=("$2"); shift 2 ;;
    -D) hdr="$2"; shift 2 ;;
    -o) out="$2"; shift 2 ;;
    --max-time|--connect-timeout) shift 2 ;;
    *) shift ;;
  esac
done
calls=$(wc -l < "$FAKE_LOG" | tr -d ' ')
if [ "${FAKE_TRANSPORT_ON_CALL:-0}" = "$calls" ]; then echo "curl: (7) Failed to connect" >&2; exit 7; fi
emit() { if [ -n "$out" ]; then printf '%s' "$1" > "$out"; else printf '%s' "$1"; fi; }
[ -n "$hdr" ] && : > "$hdr"
if ! grep -qF "Authorization: Bearer $FAKE_TOKEN" "$cfg"; then emit '{"message":"Authentication required. Please supply a valid API Access Token"}'; exit 0; fi
case "${FAKE_MODE:-}" in
  denied) emit '{"message":"Authentication required. Please supply a valid API Access Token"}'; exit 0 ;;
  garbage) emit '<html>502 Bad Gateway</html>'; exit 0 ;;
esac
base="${url%%\?*}"
if [ "$url" != "$base" ]; then IFS='&' read -r -a q <<<"${url#*\?}"; params+=("${q[@]}"); fi
per_page=30 page=1 from="" to="" branch=""
for p in "${params[@]}"; do
  v="${p#*=}"; v="${v//%3A/:}"
  case "${p%%=*}" in
    per_page) per_page="$v" ;; page) page="$v" ;; branch) branch="$v" ;;
    created_from) from="$v" ;; created_to) to="$v" ;;
  esac
done
sel="$(jq -c --arg b "$branch" --arg from "$from" --arg to "$to" '
  def ep: (sub("\\.[0-9]+Z$"; "Z") | fromdateiso8601?) // 1e12;
  [ .[] | select(($b == "" or .branch == $b)
                 and ($from == "" or (.created_at | ep) >= ($from | ep))
                 and ($to == "" or (.created_at | ep) < ($to | ep))) ]
  | sort_by(.created_at | ep) | reverse' "$FAKE_BUILDS")"
total=$(jq 'length' <<<"$sel")
emit "$(jq -c --argjson p "$page" --argjson n "$per_page" '.[($p - 1) * $n : $p * $n]' <<<"$sel")"
if [ -n "$hdr" ] && [ $(( page * per_page )) -lt "$total" ]; then
  q="branch=${branch}${from:+&created_from=${from//:/%3A}}${to:+&created_to=${to//:/%3A}}&page=$(( page + 1 ))&per_page=${per_page}"
  next_base="${FAKE_NEXT_BASE:-$base}"
  last=$(( (total + per_page - 1) / per_page ))
  if [ "${FAKE_EMPTY_NEXT:-}" = 1 ]; then
    printf 'HTTP/2 200\r\nlink: <>; rel="next", <%s?page=%s>; rel="last"\r\n\r\n' "$base" "$last" > "$hdr"
  else
    printf 'HTTP/2 200\r\nlink: <%s?%s>; rel="next", <%s?page=%s>; rel="last"\r\n\r\n' "$next_base" "$q" "$base" "$last" > "$hdr"
  fi
fi
EOF
chmod +x "$BIN"/*
printf 'locals {\n  perf_schedules_enabled = true\n}\n' > "$WORK/schedules-on.tf"

NOW="$(date -u +%s)"
# b(number, source, state, hours_ago[, message]) -> one build object
b() {
  jq -cn --argjson n "$1" --arg s "$2" --arg st "$3" --argjson t $(( NOW - $4 * 3600 - 60 )) --arg m "${5:-}" \
    '{number: $n, source: $s, state: $st, branch: "master", created_at: ($t | todate | sub("Z$"; ".123Z")),
      message: (if $m == "" then (if $s == "schedule" then "Scheduled: daily performance regression" else "[perf-run] manual" end) else $m end),
      web_url: "https://buildkite.com/mockserver/mockserver-performance-test/builds/\($n)"}'
}
# manual(count, start_number, hours_ago_first, step_minutes) -> manual api builds, one every step
manual() {
  jq -cn --argjson c "$1" --argjson s "$2" --argjson h "$3" --argjson st "$4" --argjson now "$NOW" \
    '[ range(0; $c) as $i | {number: ($s + $i), source: "api", state: "passed", branch: "master",
       created_at: (($now - $h * 3600 - $i * $st * 60) | todate | sub("Z$"; ".456Z")),
       message: "[perf-run] experiment \($i)"} ] | .[]'
}

# run(name, expected_rc, expected_substring, env...; builds on stdin) -> runs the real script
run() {
  local name="$1" want_rc="$2" want="$3" rc=0 out
  shift 3
  jq -s '.' > "$WORK/builds.json"
  : > "$WORK/curl.log"
  out="$(env -u BUILDKITE -u PERF_PRODUCER_MAX_AGE_HOURS -u PERF_FRESHNESS_LOOKBACK_HOURS -u PERF_FRESHNESS_MAX_PAGES \
    PATH="$BIN:$PATH" FAKE_BUILDS="$WORK/builds.json" FAKE_LOG="$WORK/curl.log" FAKE_TOKEN="$TOKEN" \
    PERF_FRESHNESS_AWS_BIN="$BIN/aws" PERF_FRESHNESS_CURL_BIN="$BIN/curl" TMPDIR="$WORK" \
    PERF_SCHEDULES_TF="$WORK/schedules-on.tf" "$@" \
    bash "$F" 2>&1)" || rc=$?
  printf '%s\n' "$out" > "$WORK/last.out"
  if [ "$rc" -ne "$want_rc" ]; then
    bad "$name: exit $rc, expected $want_rc"; printf '%s\n' "$out" | tail -8 | sed 's/^/       | /' >&2
  elif ! grep -qF -- "$want" <<<"$out"; then
    bad "$name: output lacks '$want'"; printf '%s\n' "$out" | tail -8 | sed 's/^/       | /' >&2
  else
    ok "$name"
  fi
}
calls() { wc -l < "$WORK/curl.log" | tr -d ' '; }

echo "--- 1. PASS paths — including manual builds that outnumber any fixed page"
{ b 541 schedule passed 2; manual 5 600 1 5; } | run "fresh passing scheduled build" 0 "OK: producer live (newest scheduled #541"
for want in "--get $API_PREFIX" "per_page=100" "created_from=" "branch=master"; do
  grep -qF -- "$want" "$WORK/curl.log" && ok "  ... query carries '$want'" || bad "query lacks '$want'"
done
{ b 541 schedule passed 12; manual 35 600 0 15; b 517 schedule passed 36; } \
  | run "35 manual builds newer than a passing scheduled build" 0 "OK: producer live (newest scheduled #541"
{ b 541 schedule passed 12; manual 250 600 0 2; b 517 schedule passed 36; } \
  | run "250 manual builds: pages through the window" 0 "last completed #541 passed"
[ "$(calls)" -eq 3 ] && ok "  ... read exactly 3 pages of 100" || bad "250 manual builds: expected 3 API calls, got $(calls)"
{ b 541 schedule running 1; b 517 schedule passed 25; manual 10 600 0 5; } \
  | run "in-flight newest, previous passed" 0 "last completed #517 passed"
if grep -qF "$TOKEN" "$WORK/curl.log"; then bad "token appeared in curl argv"; else ok "token never in curl argv"; fi

echo "--- 2. STALLED / NO_SCHEDULE — told apart, with accurate ages"
{ b 500 schedule passed 40; manual 20 600 0 30; } | run "newest scheduled inside the lookback but past the window" 1 "STALLED (no scheduled build within 30h)"
grep -qF "is **40h old**" "$WORK/last.out" && ok "  ... reports its real age" || bad "STALLED in-window: age not 40h"
{ b 400 schedule passed 100; manual 60 600 0 60; } | run "newest scheduled older than the lookback" 1 "**100h old**"
{ manual 30 600 0 60; manual 30 700 60 60; } | run "no scheduled build anywhere in history" 1 "NO_SCHEDULE (producer has no scheduled builds)"
{ manual 20 600 0 60; manual 150 700 60 10; b 300 schedule passed 200; } \
  | run "none in the lookback, page cap reached in older history" 1 "STALLED (no scheduled build within 54h)" PERF_FRESHNESS_MAX_PAGES=1
if grep -qF NO_SCHEDULE "$WORK/last.out"; then bad "capped older scan claimed NO_SCHEDULE"; else ok "  ... and does not claim NO_SCHEDULE"; fi
{ b 541 schedule running 1; manual 10 600 0 60; } | run "scheduled builds exist but none ever finished" 1 "producer wedged"
{ b 541 schedule running 1; b 470 schedule passed 70; manual 40 600 0 30; } \
  | run "in-flight newest, last finished older than the lookback" 1 "STALLED (no scheduled build has completed in 54h — producer wedged)"
{ b 590 schedule scheduled 12; b 589 schedule scheduled 36; b 588 schedule passed 60; manual 30 600 0 60; } \
  | run "two queued dailies and an older pass" 1 "STALLED (no scheduled build has completed in 54h — producer wedged)"
if grep -q 'created_to=' "$WORK/curl.log"; then bad "wedged: reached back past the lookback for a finished build"; else ok "  ... without reaching back past the lookback"; fi

echo "--- 3. NOT_PASSED"
{ b 541 schedule failed 3; b 517 schedule passed 27; } | run "most recent scheduled build failed" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ b 541 schedule running 1; b 517 schedule canceled 25; manual 40 600 0 5; } | run "in-flight newest, previous canceled" 1 "was 'canceled'"

echo "--- 4. [perf-soak] is not the daily producer"
{ b 545 schedule passed 2 "[perf-soak] Scheduled: weekly soak"; b 541 schedule failed 8; } \
  | run "a passing soak does not mask a failed daily" 1 "NOT_PASSED"
{ b 545 schedule passed 2 "[perf-soak] Scheduled: weekly soak"; b 500 schedule passed 40; } \
  | run "a fresh soak does not mask a stalled daily" 1 "STALLED (no scheduled build within 30h)"
{ b 545 schedule passed 2 "[perf-soak] Scheduled: weekly soak"; b 520 schedule passed 100 "[perf-soak] Scheduled: weekly soak"; } \
  | run "only soak builds ever: no daily schedule" 1 "NO_SCHEDULE"
# The message the weekly perf-xl schedule really sends, so a renamed marker fails here.
XLW="$(awk '/^resource "buildkite_pipeline_schedule" "perf_xl_weekly"/ {p=1} p && /^  message/ {print; exit}' \
  "$REPO_ROOT/terraform/buildkite-pipelines/pipelines.tf" | sed -n 's/^  message *= "\(.*\)"$/\1/p')"
[ -n "$XLW" ] || bad "perf_xl_weekly schedule message not found in pipelines.tf"
{ b 546 schedule passed 2 "$XLW"; b 541 schedule failed 8; } \
  | run "a passing weekly perf-xl build does not mask a failed daily" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ b 546 schedule passed 2 "$XLW"; b 500 schedule passed 40; } \
  | run "a fresh weekly perf-xl build does not mask a stalled daily" 1 "STALLED (no scheduled build within 30h)"
{ b 546 schedule failed 2 "$XLW"; b 541 schedule passed 8; } \
  | run "a failed weekly perf-xl build does not fail a passing daily" 0 "last completed #541 passed, scheduled"

echo "--- 5. API and config errors fail closed"
b 541 schedule passed 2 | run "no token" 1 "DENIED (no Buildkite API token)" FAKE_NO_TOKEN=1
b 541 schedule passed 2 | run "token rejected" 1 "DENIED (Buildkite API rejected the token)" FAKE_MODE=denied
b 541 schedule passed 2 | run "unusable body" 1 "TRANSPORT (unusable Buildkite API response)" FAKE_MODE=garbage
b 541 schedule passed 2 | run "unreachable API" 1 "TRANSPORT (Buildkite API unreachable)" FAKE_TRANSPORT_ON_CALL=1
{ manual 150 600 0 2; b 541 schedule passed 12; } | run "transport failure on page 2" 1 "TRANSPORT (Buildkite API unreachable)" FAKE_TRANSPORT_ON_CALL=2
{ manual 150 600 0 2; b 541 schedule passed 12; } \
  | run "next link to another host is not followed" 1 "TRANSPORT (unexpected pagination link)" FAKE_NEXT_BASE="https://evil.example/builds"
[ "$(calls)" -eq 1 ] && ok "  ... and the token was sent nowhere else" || bad "foreign next link: $(calls) calls"
for nb in "https://api.buildkite.com.evil/v2/organizations/mockserver/pipelines/mockserver-performance-test/builds" "http://api.buildkite.com/v2/organizations/mockserver/pipelines/mockserver-performance-test/builds"; do
  { manual 150 600 0 2; b 541 schedule passed 12; } \
    | run "next link $nb is not followed" 1 "TRANSPORT (unexpected pagination link)" FAKE_NEXT_BASE="$nb"
  [ "$(calls)" -eq 1 ] && ok "  ... one call only" || bad "next link $nb: $(calls) calls"
done
{ manual 150 600 0 2; b 541 schedule passed 12; } \
  | run "an empty next link is not read as the last page" 1 "TRANSPORT (unexpected pagination link)" FAKE_EMPTY_NEXT=1
b 541 schedule passed 2 | jq -c '.created_at = "not-a-timestamp"' \
  | run "unparseable created_at" 1 "TRANSPORT (unparseable build timestamp)"
{ manual 150 600 0 2; b 541 schedule passed 12; } | run "page cap inside the window" 1 "TRUNCATED (page cap reached inside the lookback window)" PERF_FRESHNESS_MAX_PAGES=1
{ b 541 schedule running 1; manual 150 600 0 2; b 517 schedule passed 26; } \
  | run "page cap before a finished build in the window" 1 "TRUNCATED" PERF_FRESHNESS_MAX_PAGES=1
b 541 schedule passed 2 | run "lookback not beyond the window" 1 "CONFIG (lookback not longer" PERF_FRESHNESS_LOOKBACK_HOURS=30
b 541 schedule passed 2 | run "malformed page cap" 1 "CONFIG (MAX_PAGES is not a positive integer)" PERF_FRESHNESS_MAX_PAGES=0
for bad_age in abc 1.5; do
  b 541 schedule passed 2 | run "liveness window '$bad_age'" 1 "CONFIG (PRODUCER_MAX_AGE_HOURS is not a positive integer)" PERF_PRODUCER_MAX_AGE_HOURS="$bad_age"
done
b 541 schedule passed 2 | run "malformed lookback" 1 "CONFIG (LOOKBACK_HOURS is not a positive integer)" PERF_FRESHNESS_LOOKBACK_HOURS=2d

echo "--- 6. a measured manual [perf-run] build: the newest finished run gives the verdict"
# mb(number, state, hours_ago, step_state[, source[, message[, env_json]]]) -> a manual build
# whose perf-run, perf-microbench and perf-compare jobs are in step_state (as the API lists them)
mb() {
  local env='{}'
  [ $# -ge 7 ] && env="$7"
  b "$1" "${5:-api}" "$2" "$3" "${6:-[perf-run] confirm after a failed daily}" \
    | jq -c --arg js "$4" --argjson env "$env" '. + {env: $env,
        jobs: [{step_key: null, state: "passed"},
               ({step_key: ("perf-run", "perf-microbench", "perf-compare"), state: $js})]}'
}
{ mb 660 passed 1 passed; b 653 schedule failed 5; } \
  | run "failed scheduled, newer measured manual passed" 0 "last completed #660 passed, manual [perf-run]"
{ b 654 schedule passed 1; mb 660 failed 3 failed; b 653 schedule failed 26; } \
  | run "scheduled newest passed, older manual failed" 0 "last completed #654 passed, scheduled"
{ mb 660 failed 1 failed; b 653 schedule passed 5; } \
  | run "measured manual newest failed" 1 "NOT_PASSED (last completed manual [perf-run] run was 'failed')"
{ mb 660 passed 1 broken; b 653 schedule failed 5; } \
  | run "manual that skipped measurement (steps broken) is ignored" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ b 660 api passed 1 "[perf-run] guard only"; b 653 schedule failed 5; } \
  | run "manual with no measurement jobs is ignored" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ mb 660 passed 1 passed api "re-run without the tag"; b 653 schedule failed 5; } \
  | run "untagged api build is ignored" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ mb 660 passed 1 passed ui "Rebuild from the UI"; b 653 schedule failed 5; } \
  | run "a UI build needs no tag" 0 "last completed #660 passed, manual [perf-run]"
{ mb 660 passed 1 passed api "[perf-run] A/B" '{"PERF_XL":"true"}'; b 653 schedule failed 5; } \
  | run "a manual A/B build with env overrides is ignored" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ mb 660 running 1 passed; b 653 schedule failed 5; } \
  | run "a manual build still running is ignored" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ mb 660 passed 1 passed api "[perf-run] [perf-soak] soak"; b 653 schedule failed 5; } \
  | run "a manual soak build is ignored" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ mb 660 passed 1 passed; b 500 schedule passed 40; } \
  | run "a passing manual run does not rescue a stalled schedule" 1 "STALLED (no scheduled build within 30h)"
{ b 654 schedule running 1; mb 660 passed 2 passed; b 653 schedule failed 5; } \
  | run "in-flight scheduled, measured manual newer than the failed one" 0 "last completed #660 passed, manual [perf-run]"

echo "--- 7. a manual build whose perf-run step failed is a failed run; one that never measured is not"
# ms(number, state, hours_ago, run, microbench, compare[, env_json]) -> a manual [perf-run]
# build with each measurement step's job in its own state ("-" omits that job)
ms() {
  b "$1" api "$2" "$3" "[perf-run] after a fix" \
    | jq -c --arg r "$4" --arg m "$5" --arg c "$6" --argjson env "${7:-"{}"}" '. + {env: $env,
        jobs: ([{step_key: null, state: "passed"}]
          + [ {step_key: "perf-run", state: $r}, {step_key: "perf-microbench", state: $m},
              {step_key: "perf-compare", state: $c} ] | map(select(.state != "-")))}'
}
{ ms 661 failed 1 failed passed waiting_failed; b 653 schedule passed 5; } \
  | run "newest manual: perf-run failed, compare skipped" 1 "NOT_PASSED (last completed manual [perf-run] run was 'failed')"
if grep -qE "^- its .perf-run. measurement step failed, so compare never ran" "$WORK/last.out"; then
  ok "  ... says the measurement failed"
else
  bad "failed measurement: message lacks the reason"
fi
{ ms 661 failed 1 timed_out canceled broken; b 653 schedule passed 5; } \
  | run "newest manual: perf-run timed out" 1 "NOT_PASSED (last completed manual [perf-run] run was 'failed')"
{ ms 661 canceled 1 failed canceled broken; b 653 schedule passed 5; } \
  | run "newest manual: perf-run failed, rest cancelled" 1 "NOT_PASSED (last completed manual [perf-run] run was 'canceled')"
{ ms 661 passed 1 failed passed broken; b 653 schedule passed 5; } \
  | run "a failed perf-run never passes, whatever the build state" 1 "NOT_PASSED (last completed manual [perf-run] run was 'failed')"
{ ms 661 canceled 1 canceled canceled broken; b 653 schedule passed 5; } \
  | run "newest manual cancelled during perf-run is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 failed 1 canceled failed broken; b 653 schedule passed 5; } \
  | run "a failed microbench with perf-run cancelled is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 canceled 1 - - -; b 653 schedule passed 5; } \
  | run "newest manual cancelled before measuring is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 canceled 1 broken broken broken; b 653 schedule passed 5; } \
  | run "newest manual with measurement never started is ignored" 0 "last completed #653 passed, scheduled"
{ b 661 api passed 1 "[perf-run] guard only"; b 653 schedule passed 5; } \
  | run "newest manual guard-only (no measurement jobs) is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 failed 1 failed passed waiting_failed '{"PERF_XL":"true"}'; b 653 schedule passed 5; } \
  | run "a failed-measurement A/B build with env overrides is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 failed 1 failed passed waiting_failed | jq -c '.message = "re-run without the tag"'; b 653 schedule passed 5; } \
  | run "a failed-measurement untagged api build is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 failed 1 failed passed waiting_failed | jq -c '.branch = "perf-experiment"'; b 653 schedule passed 5; } \
  | run "a failed-measurement branch build is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 failing 1 failed running waiting_failed; b 653 schedule passed 5; } \
  | run "a failed-measurement build still running is ignored" 0 "last completed #653 passed, scheduled"
{ b 662 schedule passed 1; ms 661 failed 2 failed passed waiting_failed; b 653 schedule passed 26; } \
  | run "scheduled newest passed, older manual failed measurement" 0 "last completed #662 passed, scheduled"
{ b 662 schedule failed 1; ms 661 failed 2 failed passed waiting_failed; b 653 schedule passed 26; } \
  | run "scheduled newest failed, older manual failed measurement" 1 "NOT_PASSED (last completed scheduled run was 'failed')"
{ ms 663 passed 1 passed passed passed; ms 661 failed 2 failed passed waiting_failed; b 653 schedule passed 5; } \
  | run "a newer passing manual run clears an older failed measurement" 0 "last completed #663 passed, manual [perf-run]"
{ ms 661 failed 1 failed passed waiting_failed | jq -c '.meta_data = {perf_run_refused: "image_revision"}'; b 653 schedule passed 5; } \
  | run "a build whose perf-run refused its image revision is ignored" 0 "last completed #653 passed, scheduled"
{ ms 661 failed 1 failed passed waiting_failed | jq -c '.meta_data = {perf_run_refused: "no"}'; b 653 schedule passed 5; } \
  | run "a build whose perf-run was not refused and failed still counts" 1 "NOT_PASSED (last completed manual [perf-run] run was 'failed')"
{ ms 661 failed 1 failed passed waiting_failed; b 500 schedule passed 40; } \
  | run "a failed measurement does not hide a stalled schedule" 1 "STALLED (no scheduled build within 30h)"

echo "--- 8. on-demand mode: the switch is local.perf_schedules_enabled in perf-schedules.tf"
SW="$WORK/schedules.tf"
sched() { printf '%s\n' 'locals {' "$@" '}' > "$SW"; }
sched '  perf_schedules_enabled = false'
: | run "switch false: NOT CHECKED, exit 0" 0 "NOT CHECKED: perf schedules disabled" PERF_SCHEDULES_TF="$SW"
if [ "$(calls)" -eq 0 ]; then ok "  ... with no API call"; else bad "switch false: $(calls) API call(s)"; fi
if grep -qF "This is **not** a pass" "$WORK/last.out"; then ok "  ... and says it is not a pass"; else bad "switch false: does not say it is not a pass"; fi
if grep -qF "OK: producer live" "$WORK/last.out"; then bad "switch false: reported producer live"; else ok "  ... never reports the producer live"; fi
: | run "switch false: needs no token" 0 "NOT CHECKED" PERF_SCHEDULES_TF="$SW" FAKE_NO_TOKEN=1
manual 30 600 0 60 | run "switch true: no schedule is still NO_SCHEDULE" 1 "NO_SCHEDULE"
manual 30 600 0 60 | run "an env var cannot switch the check off" 1 "NO_SCHEDULE" PERF_SCHEDULES_ENABLED=false
sched '  perf_schedules_enabled = true'
cp "$SW" "$WORK/schedules-true.tf"
sched '  perf_schedules_enabled = false'
COMMITTED_ON="$(grep -cE '^[[:space:]]*perf_schedules_enabled[[:space:]]*=[[:space:]]*true' "$REPO_ROOT/terraform/buildkite-pipelines/perf-schedules.tf" || true)"
if [ "$COMMITTED_ON" = 0 ]; then
  manual 30 600 0 60 | run "on an agent the seam is ignored (committed false wins over a true seam)" 0 "NOT CHECKED" BUILDKITE=true PERF_SCHEDULES_TF="$WORK/schedules-true.tf"
else
  manual 30 600 0 60 | run "on an agent the seam is ignored (committed true wins over a false seam)" 1 "NO_SCHEDULE" BUILDKITE=true PERF_SCHEDULES_TF="$SW"
fi
for bad_line in '  perf_schedules_enabled = "false"' '  perf_schedules_enabled = var.x' '  perf_schedules_enabled = false|  perf_schedules_enabled = true'; do
  IFS='|' read -r -a lines <<<"$bad_line"
  sched "${lines[@]}"
  b 541 schedule passed 2 | run "unreadable switch: $bad_line" 1 "CONFIG (cannot read the perf schedule switch)" PERF_SCHEDULES_TF="$SW"
done
b 541 schedule passed 2 | run "missing switch file" 1 "CONFIG (cannot read the perf schedule switch)" PERF_SCHEDULES_TF="$WORK/absent.tf"

echo "--- 9. the committed switch is the one terraform applies and this step reads"
TF_DIR="$REPO_ROOT/terraform/buildkite-pipelines"
DEFS="$(find "$TF_DIR" -maxdepth 1 -name '*.tf' -exec grep -HE '^[[:space:]]*perf_schedules_enabled[[:space:]]*=' {} + || true)"
if [ "$(grep -c . <<<"$DEFS")" -eq 1 ] && [[ "$DEFS" == "$TF_DIR/perf-schedules.tf:"* ]]; then
  ok "defined once, in perf-schedules.tf"
else
  bad "perf_schedules_enabled must be defined exactly once, in perf-schedules.tf; found: ${DEFS:-<none>}"
fi
OVERRIDES="$(find "$TF_DIR" -maxdepth 1 \( -name '*.tf' -o -name '*.tfvars' -o -name '*.tfvars.json' \) \
  -exec grep -lE 'variable[[:space:]]+"perf_schedules_enabled"|^[[:space:]]*"?perf_schedules_enabled"?[[:space:]]*[=:]' {} + 2>/dev/null \
  | grep -v '/perf-schedules\.tf$' || true)"
if [ -z "$OVERRIDES" ]; then ok "no variable or tfvars can override it"; else bad "an override surface exists: $OVERRIDES"; fi
SCHEDS="$(awk '
  /^resource "buildkite_pipeline_schedule" / { name = $3; gsub(/"/, "", name); perf = 0; on = 0 }
  name != "" && (/pipeline\["perf-test"\]/ || name == "infra_baseline_freshness_daily") { perf = 1 }
  name != "" && /^  enabled *= local\.perf_schedules_enabled$/ { on = 1 }
  name != "" && /^}/ { if (perf) print name, on; name = "" }' "$TF_DIR/pipelines.tf")"
if [ "$(grep -c . <<<"$SCHEDS")" -ge 4 ]; then ok "found $(grep -c . <<<"$SCHEDS") perf schedules"; else bad "expected at least 4 perf schedules, found: $SCHEDS"; fi
while read -r name on; do
  if [ "$on" = 1 ]; then ok "  ... $name follows local.perf_schedules_enabled"; else bad "schedule $name does not set enabled = local.perf_schedules_enabled"; fi
done <<<"$SCHEDS"
COMMITTED="$(sed -nE 's/^[[:space:]]*perf_schedules_enabled[[:space:]]*=[[:space:]]*(true|false)[[:space:]]*$/\1/p' "$TF_DIR/perf-schedules.tf")"
if [ "$COMMITTED" = false ]; then
  : | run "the step reads the committed switch (false): NOT CHECKED" 0 "NOT CHECKED" PERF_SCHEDULES_TF=
else
  b 541 schedule passed 2 | run "the step reads the committed switch (${COMMITTED:-?}): asserts" 0 "OK: producer live" PERF_SCHEDULES_TF=
fi

FAILS=$(wc -l < "$WORK/fails" | tr -d ' ')
[ "$FAILS" -eq 0 ] || { echo "FAILED: $FAILS check(s)" >&2; exit 1; }
echo "OK: all perf-baseline-freshness checks passed"

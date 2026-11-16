#!/usr/bin/env bash
# Tests central_portal_publish (scripts/release/_lib.sh), the step that publishes a
# Central Portal deployment only when the Portal does not report publishing as blocked.
# curl and sleep are stubbed with fixture responses (testdata/central-portal); no network,
# credentials or Docker.
#
#   ./scripts/release/test-central-portal-guard.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
FIXTURES="$SCRIPT_DIR/testdata/central-portal"
# shellcheck source-path=SCRIPTDIR source=_lib.sh
source "$SCRIPT_DIR/_lib.sh"

mkdir -p "$REPO_ROOT/.tmp"
WORK="$(mktemp -d "$REPO_ROOT/.tmp/central-portal-guard.XXXXXX")"
trap 'rm -rf "${WORK:?}"' EXIT

ID="228395f4-bf29-4b2d-af0b-baf6e12bc4ea"
NETRC="$WORK/netrc"
: > "$NETRC"

# Each status poll takes the next "<http-code> <fixture|->" line of $WORK/status-queue,
# repeating the last; publish and drop answer $WORK/publish-code and $WORK/drop-code.
# Every call is appended to $WORK/calls as "<METHOD> <url>".
curl() {
  local method="GET" out="/dev/null" url="" code fixture n
  while [[ $# -gt 0 ]]; do
    case "$1" in
      -X) method="$2"; shift 2 ;;
      -o) out="$2"; shift 2 ;;
      -w|--netrc-file|--connect-timeout|--max-time) shift 2 ;;
      https://*) url="$1"; shift ;;
      *) shift ;;
    esac
  done
  echo "$method $url" >> "$WORK/calls"
  case "$method $url" in
    "POST $CENTRAL_PORTAL_API/status?id=$ID")
      n=$(($(cat "$WORK/polls") + 1)); echo "$n" > "$WORK/polls"
      read -r code fixture < <(sed -n "${n}p" "$WORK/status-queue" | grep . || tail -1 "$WORK/status-queue")
      if [[ "$fixture" == "-" ]]; then printf 'error' > "$out"; else cat "$FIXTURES/$fixture" > "$out"; fi
      printf '%s' "$code" ;;
    "POST $CENTRAL_PORTAL_API/deployment/$ID")
      printf '{"error":"publish rejected"}' > "$out"; cat "$WORK/publish-code" ;;
    "DELETE $CENTRAL_PORTAL_API/deployment/$ID")
      : > "$out"; cat "$WORK/drop-code" ;;
    *) printf '000'; return 7 ;;
  esac
}
sleep() { :; }

failures=0
pass() { echo "  ok   $*"; }
fail() { echo "  FAIL $*" >&2; failures=$((failures + 1)); }

# scenario <description> <publish-code> <drop-code> <status-queue-line>...
# Runs the guard and leaves its exit code in $rc, its output in $WORK/out.
scenario() {
  echo "--- $1"
  printf '%s' "$2" > "$WORK/publish-code"
  printf '%s' "$3" > "$WORK/drop-code"
  shift 3
  printf '%s\n' "$@" > "$WORK/status-queue"
  echo 0 > "$WORK/polls"
  : > "$WORK/calls"
  rc=0
  ( central_portal_publish "$NETRC" "$ID" ${ON_BLOCK:-} ) > "$WORK/out" 2>&1 || rc=$?
}
expect_rc() { # expect_rc <0|nonzero>
  if [[ "$1" == "0" && "$rc" == "0" ]] || [[ "$1" == "nonzero" && "$rc" != "0" ]]; then
    pass "exit $rc"
  else
    fail "exit $rc, want $1:"; sed 's/^/       /' "$WORK/out" >&2
  fi
}
count_calls() { grep -cxF "$1" "$WORK/calls" || true; }
expect_calls() { # expect_calls <METHOD> <path> <count>
  local got; got=$(count_calls "$1 $CENTRAL_PORTAL_API/$2")
  if [[ "$got" == "$3" ]]; then pass "$1 /$2 called $3 time(s)"; else fail "$1 /$2 called $got time(s), want $3"; fi
}
expect_output() { # expect_output <fixed-string> <description>
  if grep -qF -- "$1" "$WORK/out"; then pass "$2"; else fail "$2: '$1' not in output:"; sed 's/^/       /' "$WORK/out" >&2; fi
}

scenario "blocked warning: drop the deployment, publish nothing, fail" 204 204 "200 validated-blocked.json"
expect_rc nonzero
expect_calls POST "deployment/$ID" 0
expect_calls DELETE "deployment/$ID" 1
expect_output "has exceeded its monthly Release Size publishing limit. Publishing is blocked" "the blocking warning is printed"
expect_output "$CENTRAL_PORTAL_USAGE_URL" "the usage URL is printed"
expect_output "Dropped deployment $ID" "the drop is reported"

ON_BLOCK=keep scenario "blocked warning in keep mode (recovery): fail, publish nothing, keep the deployment" 204 204 "200 validated-blocked.json"
expect_rc nonzero
expect_calls POST "deployment/$ID" 0
expect_calls DELETE "deployment/$ID" 0
expect_output "Kept deployment $ID" "the kept deployment is reported"

scenario "blocked warning and the drop fails: still fail, publish nothing" 204 500 "200 validated-blocked.json"
expect_rc nonzero
expect_calls POST "deployment/$ID" 0
expect_output "Could not drop deployment $ID (HTTP 500)" "the failed drop is reported"

scenario "approaching warning: print it and publish" 204 204 "200 validated-approaching.json"
expect_rc 0
expect_calls POST "deployment/$ID" 1
expect_calls DELETE "deployment/$ID" 0
expect_output "is approaching its monthly File Count publishing limit" "the approaching warning is printed"

scenario "no warnings: publish" 200 204 "200 validated-no-warnings.json"
expect_rc 0
expect_calls POST "deployment/$ID" 1
expect_calls DELETE "deployment/$ID" 0

scenario "VALIDATING then VALIDATED: keep polling, then publish" 204 204 \
  "200 validating.json" "500 -" "200 validating.json" "200 validated-no-warnings.json"
expect_rc 0
expect_calls POST "status?id=$ID" 4
expect_calls POST "deployment/$ID" 1

scenario "validation FAILED: fail without publishing or dropping" 204 204 "200 failed.json"
expect_rc nonzero
expect_calls POST "deployment/$ID" 0
expect_calls DELETE "deployment/$ID" 0

scenario "VALIDATED with errors: fail without publishing" 204 204 "200 validated-with-errors.json"
expect_rc nonzero
expect_calls POST "deployment/$ID" 0

scenario "warnings not an array: fail closed without publishing" 204 204 "200 validated-malformed-warnings.json"
expect_rc nonzero
expect_calls POST "deployment/$ID" 0

scenario "already PUBLISHED: nothing to publish, succeed" 204 204 "200 published-blocked-warning.json"
expect_rc 0
expect_calls POST "deployment/$ID" 0
expect_calls DELETE "deployment/$ID" 0

scenario "publish rejected (HTTP 400): fail and show the response" 400 204 "200 validated-no-warnings.json"
expect_rc nonzero
expect_output "Failed to publish deployment $ID (HTTP 400)" "the HTTP status is printed"
expect_output "publish rejected" "the response body is printed"

scenario "status endpoint keeps failing: give up without publishing" 204 204 "503 -"
expect_rc nonzero
expect_calls POST "status?id=$ID" "$CENTRAL_PORTAL_MAX_CONSECUTIVE_ERRORS"
expect_calls POST "deployment/$ID" 0

scenario "never leaves VALIDATING: time out without publishing" 204 204 "200 validating.json"
expect_rc nonzero
expect_calls POST "status?id=$ID" "$CENTRAL_PORTAL_POLL_ATTEMPTS"
expect_calls POST "deployment/$ID" 0

echo "--- the blocking match is case-insensitive and needs only one of its words"
for text in "Publishing is BLOCKED until further notice" "Release Count limit Exceeded"; do
  if grep -qiE "$CENTRAL_PORTAL_BLOCKING_WARNING_REGEX" <<<"$text"; then pass "blocks: $text"; else fail "does not block: $text"; fi
done
if grep -qiE "$CENTRAL_PORTAL_BLOCKING_WARNING_REGEX" <<<"is approaching its monthly File Count publishing limit (798 of 1000 used, 80%)"; then
  fail "an approaching warning would block"
else
  pass "an approaching warning does not block"
fi

if [[ "$failures" -gt 0 ]]; then
  echo "--- :x: $failures check(s) failed" >&2
  exit 1
fi
echo "--- all Central Portal guard checks passed"

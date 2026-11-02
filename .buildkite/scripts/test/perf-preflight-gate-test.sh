#!/usr/bin/env bash
# Network-free checks for scripts/release/check-perf-preflight.sh: its grader self-test, the
# budget-mapping lint on the committed budget file, and the full gate on a run object trimmed
# from a real daily S3 run (fixtures/perf-preflight/), with its commit set to HEAD.
# Run: .buildkite/scripts/test/perf-preflight-gate-test.sh
#      (PERF_PREFLIGHT_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_PREFLIGHT_SCRIPT
GATE="${PERF_PREFLIGHT_SCRIPT:-$REPO_ROOT/scripts/release/check-perf-preflight.sh}"
BUDGETS="$REPO_ROOT/mockserver-performance-test/perf-budgets.json"
FIXTURE="$REPO_ROOT/.buildkite/scripts/test/fixtures/perf-preflight/run-a82cfcb1e6.json"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-preflight-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAILS=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }

# expect <label> <want-rc> <want-text|-> -- <command...>; output goes to $WORK/out
expect() {
  local label="$1" want_rc="$2" want_text="$3" rc=0
  shift 4
  "$@" >"$WORK/out" 2>&1 || rc=$?
  if [ "$rc" != "$want_rc" ]; then
    bad "$label: exit $rc, expected $want_rc"; sed 's/^/       /' "$WORK/out" | tail -n 8 >&2; return 0
  fi
  if [ "$want_text" != "-" ] && ! grep -qF -- "$want_text" "$WORK/out"; then
    bad "$label: output lacks '$want_text'"; sed 's/^/       /' "$WORK/out" | tail -n 8 >&2; return 0
  fi
  ok "$label"
}

HEAD_SHA="$(git -C "$REPO_ROOT" rev-parse HEAD)"
RUN_NAME="2026-01-01T00-00-00Z__fixture.json"
# stage <run.json> <dir>: the run, and the compare verdict VERDICT (default pass; "none" writes none)
stage() {
  mkdir -p "$2/verdicts"
  cp "$1" "$2/$RUN_NAME"
  case "${VERDICT:-pass}" in
    none) ;;
    raw:*) printf '%s' "${VERDICT#raw:}" > "$2/verdicts/$RUN_NAME" ;;
    *) jq -n --arg k "${VERDICT_KEY:-runs/master/$RUN_NAME}" --arg v "${VERDICT:-pass}" '{key: $k, verdict: $v}' > "$2/verdicts/$RUN_NAME" ;;
  esac
}
# run_gate <run.json>: the full gate in local-dir mode, measuring and releasing HEAD.
run_gate() {
  local dir="$WORK/runs-$RANDOM"
  stage "$1" "$dir"
  env PERF_PREFLIGHT_LOCAL_DIR="$dir" PERF_PREFLIGHT_RELEASE_COMMIT="$HEAD_SHA" \
      PERF_ACCEPTED_REGRESSIONS_FILE="${ACCEPT:-$WORK/no-acceptances.json}" "$GATE"
}
run_with() { # <jq filter> <out.json>: the real-shaped fixture, commit set to HEAD, then the filter
  jq --arg c "$HEAD_SHA" ".commit = \$c | $1" "$FIXTURE" > "$2"
}

echo "--- grader self-test"
expect "--self-test passes" 0 "Self-test PASSED" -- "$GATE" --self-test
expect "--self-test and --lint-budgets together are rejected" 64 "separate modes" -- "$GATE" --self-test --lint-budgets

echo "--- budget-mapping lint"
expect "the committed budget file has no unmapped gating budget" 0 "LINT OK" -- "$GATE" --lint-budgets
jq '.budgets["behaviours.new_arm.error_rate"] = {"dir":"up","min_pct":0,"floor":0.01,"gating":true}' "$BUDGETS" > "$WORK/budgets-new.json"
expect "a newly added gating absolute budget with no mapping fails the lint" 1 "behaviours.new_arm.error_rate" -- \
  env PERF_BUDGETS_FILE="$WORK/budgets-new.json" "$GATE" --lint-budgets
jq '.budgets["behaviours.new_arm.error_rate"] = {"dir":"up","min_pct":0,"floor":0.01,"gating":false}' "$BUDGETS" > "$WORK/budgets-advisory.json"
expect "a new NON-gating budget needs no mapping" 0 "LINT OK" -- \
  env PERF_BUDGETS_FILE="$WORK/budgets-advisory.json" "$GATE" --lint-budgets
jq '.budgets["behaviours.new_arm.p99_ms"] = {"dir":"up","min_pct":0.1,"floor":null,"gating":true}' "$BUDGETS" > "$WORK/budgets-relative.json"
expect "a new relative (floor:null) gating budget is delegated, not unmapped" 0 "LINT OK" -- \
  env PERF_BUDGETS_FILE="$WORK/budgets-relative.json" "$GATE" --lint-budgets
jq '.budgets |= with_entries(if (.value | type) == "object" then .value.gating = false else . end)' "$BUDGETS" > "$WORK/budgets-none.json"
expect "a budget file with no gating budget at all fails the lint" 1 "no gating:true budgets" -- \
  env PERF_BUDGETS_FILE="$WORK/budgets-none.json" "$GATE" --lint-budgets
mkdir -p "$WORK/gate-unmapped"
grep -v '"churn.alloc_ratio_index_n15000" then' "$GATE" > "$WORK/gate-unmapped/check-perf-preflight.sh"
expect "the gate with its churn mapping removed fails the lint on the committed budgets" 1 "churn.alloc_ratio_index_n15000" -- \
  env PERF_BUDGETS_FILE="$BUDGETS" bash "$WORK/gate-unmapped/check-perf-preflight.sh" --lint-budgets

echo "--- full gate on a real-shaped daily run object"
run_with '.' "$WORK/good.json"
expect "a valid, current, in-budget run PASSES" 0 "PASS:" -- run_gate "$WORK/good.json"
expect "  ... with both absolute gating budgets evaluated" 0 "BUDGETS ok: 2 gating absolute metric(s)" -- run_gate "$WORK/good.json"
run_with '.churn.alloc_ratio_index_n15000 = 1200' "$WORK/churn-bad.json"
expect "a churn/static allocation ratio over its floor is a BREACH" 1 "BREACH: gating budget churn.alloc_ratio_index_n15000" -- run_gate "$WORK/churn-bad.json"
run_with 'del(.churn)' "$WORK/churn-absent.json"
expect "a run without the churn ratio is INDETERMINATE" 2 "churn.alloc_ratio_index_n15000 could not be evaluated" -- run_gate "$WORK/churn-absent.json"
run_with '.forward_guard.error_rate = 0.5' "$WORK/fwd-bad.json"
expect "a forward error rate over its floor is still a BREACH" 1 "BREACH: gating budget forward.error_rate" -- run_gate "$WORK/fwd-bad.json"

echo "--- COMPARE VERDICT: how the run's own compare ended"
K="runs/master/$RUN_NAME"
fail_verdict() { jq -nc --arg k "$K" --argjson f "$1" '{key: $k, verdict: "fail", gating_flagged: $f}'; }
accept() { # <metric> <max_value> -> an acceptance file for HEAD
  jq -n --arg m "$1" --argjson v "$2" --arg c "${HEAD_SHA:0:10}" \
    '[{metric: $m, commit: $c, max_value: $v, reason: "fixture", accepted_by: "fixture", date: "2026-11-02"}]' > "$WORK/accept-$1-$2.json"
  echo "$WORK/accept-$1-$2.json"
}
MB='MatchingBenchmark.matchExact.time_per_op'
expect "a passing verdict is reported" 0 "COMPARE ok" -- run_gate "$WORK/good.json"
VERDICT=none expect "no verdict at all is UNVERIFIED" 1 "UNVERIFIED: no compare verdict at verdicts/$K" -- run_gate "$WORK/good.json"
VERDICT=warming_up expect "a warming-up verdict passes, noted" 0 "COMPARE warming up" -- run_gate "$WORK/good.json"
VERDICT="raw:$(fail_verdict "[{\"metric\":\"$MB\",\"value\":123}]")" \
  expect "a fail verdict blocks on the metric it flagged" 1 "BREACH (compare): the run's compare step flagged gating metric $MB = 123" -- run_gate "$WORK/good.json"
VERDICT="raw:$(fail_verdict "[{\"metric\":\"$MB\",\"value\":123}]")" ACCEPT="$(accept "$MB" 200)" \
  expect "  ... and passes when a reviewed acceptance covers it" 0 "COMPARE flag accepted: $MB = 123" -- run_gate "$WORK/good.json"
VERDICT="raw:$(fail_verdict "[{\"metric\":\"$MB\",\"value\":123}]")" ACCEPT="$(accept "$MB" 100)" \
  expect "  ... but not when the value is over the acceptance ceiling" 1 "BREACH (compare)" -- run_gate "$WORK/good.json"
VERDICT="raw:$(fail_verdict "[{\"metric\":\"$MB\",\"value\":123},{\"metric\":\"other.time_per_op\",\"value\":9}]")" ACCEPT="$(accept "$MB" 200)" \
  expect "  ... nor when only some flagged metrics are accepted" 1 "flagged gating metric other.time_per_op = 9" -- run_gate "$WORK/good.json"
VERDICT="raw:$(fail_verdict "[{\"metric\":\"$MB\",\"value\":null}]")" ACCEPT="$(accept "$MB" 200)" \
  expect "  ... nor for a flagged metric with no numeric value" 1 "BREACH (compare)" -- run_gate "$WORK/good.json"
VERDICT="raw:$(fail_verdict '[{"metric":"forward.error_rate","value":0.5}]')" ACCEPT="$(accept forward.error_rate 0.6)" \
  expect "an accepted forward.error_rate breach passes the budget and the compare verdict" 0 "COMPARE flag accepted: forward.error_rate = 0.5" -- run_gate "$WORK/fwd-bad.json"
VERDICT="raw:$(fail_verdict '[]')" expect "a fail verdict that lists nothing is INDETERMINATE" 2 "INDETERMINATE (verdict)" -- run_gate "$WORK/good.json"
VERDICT_KEY="runs/master/other.json" expect "a verdict for another run is INDETERMINATE" 2 "not bound to this run" -- run_gate "$WORK/good.json"
VERDICT="raw:{not json" expect "an unreadable verdict is INDETERMINATE" 2 "INDETERMINATE (verdict)" -- run_gate "$WORK/good.json"

echo "--- COMPARE VERDICT read from S3 (stub aws)"
S3="$WORK/s3"; mkdir -p "$S3/bin"
cat > "$S3/bin/aws" <<'STUB'
#!/usr/bin/env bash
case "$1 $2" in
  "sts get-caller-identity") echo "arn:aws:sts::000000000000:assumed-role/fixture" ;;
  "s3 ls") echo "2026-01-01 00:00:00 100 runs/master/$FAKE_RUN_NAME" ;;
  "s3 cp")
    case "$3" in
      */verdicts/*)
        case "$FAKE_VERDICT" in
          pass) jq -n --arg k "runs/master/$FAKE_RUN_NAME" '{key: $k, verdict: "pass"}' > "$4" ;;
          404) echo "fatal error: An error occurred (404) when calling the HeadObject operation: Key \"${3#s3://*/}\" does not exist" >&2; exit 1 ;;
          *) echo "fatal error: An error occurred (403) when calling the HeadObject operation: Forbidden" >&2; exit 1 ;;
        esac ;;
      *) cp "$FAKE_RUN" "$4" ;;
    esac ;;
  *) exit 1 ;;
esac
STUB
chmod +x "$S3/bin/aws"
s3_gate() { # <verdict mode>
  env PATH="$S3/bin:$PATH" PERF_PREFLIGHT_AWS_BIN="$S3/bin/aws" FAKE_RUN="$WORK/good.json" FAKE_RUN_NAME="$RUN_NAME" FAKE_VERDICT="$1" \
      PERF_PREFLIGHT_RELEASE_COMMIT="$HEAD_SHA" PERF_ACCEPTED_REGRESSIONS_FILE="$WORK/no-acceptances.json" "$GATE"
}
expect "S3: a pass verdict passes" 0 "COMPARE ok" -- s3_gate pass
expect "S3: a missing verdict (404) is UNVERIFIED" 1 "UNVERIFIED: no compare verdict at verdicts/$K" -- s3_gate 404
expect "S3: any other read error (403) is INDETERMINATE" 2 "INDETERMINATE (verdict): could not read" -- s3_gate 403

echo "--- RECENCY against a scratch history: which later commits still ship the measured code"
# A throwaway repository, so the real clone gains no objects.
G="$WORK/history"
git init -q "$G"
commit() { # <path> -> the new commit's SHA
  mkdir -p "$G/$(dirname "$1")"; echo "$RANDOM" >> "$G/$1"
  git -C "$G" add -A
  GIT_AUTHOR_NAME=t GIT_AUTHOR_EMAIL=t@t GIT_COMMITTER_NAME=t GIT_COMMITTER_EMAIL=t@t git -C "$G" -c commit.gpgsign=false commit -q -m "$1"
  git -C "$G" rev-parse HEAD
}
MEASURED="$(commit mockserver/core.java)"
DOCS_TIP="$(commit docs/notes.md)"
PLUGIN_TIP="$(commit mockserver/mockserver-maven-plugin/pom.xml)"
JAVA_TIP="$(commit mockserver/later.java)"
git -C "$G" checkout -q "$PLUGIN_TIP"
DOCKER_TIP="$(commit docker/graaljs/Dockerfile)"
# release_gate <run.json> <release commit>: the gate on the scratch history
release_gate() {
  local dir="$WORK/runs-$RANDOM"
  stage "$1" "$dir"
  env PERF_PREFLIGHT_LOCAL_DIR="$dir" PERF_PREFLIGHT_GIT_REPO="$G" PERF_PREFLIGHT_RELEASE_COMMIT="$2" \
      PERF_ACCEPTED_REGRESSIONS_FILE="$WORK/no-acceptances.json" "$GATE"
}
jq --arg c "$MEASURED" --arg h "$DOCS_TIP" '.commit = $c | .harness_commit = $h' "$FIXTURE" > "$WORK/measured.json"
expect "docs-only commits since the measured one: EQUIVALENT, passes" 0 "RECENCY ok (EQUIVALENT)" -- release_gate "$WORK/measured.json" "$DOCS_TIP"
expect "a maven-plugin-only change since: EQUIVALENT, passes" 0 "RECENCY ok (EQUIVALENT)" -- release_gate "$WORK/measured.json" "$PLUGIN_TIP"
expect "a mockserver/ change since: STALE" 1 "STALE:" -- release_gate "$WORK/measured.json" "$JAVA_TIP"
expect "  ... and the remedy names a [perf-run] build on the release commit" 1 "at commit ${JAVA_TIP} with [perf-run] in its message" -- release_gate "$WORK/measured.json" "$JAVA_TIP"
expect "  ... and names the build the run was on" 1 "That run's build was on ${DOCS_TIP:0:12} and was served the :snapshot image built from ${MEASURED:0:12}" -- release_gate "$WORK/measured.json" "$JAVA_TIP"
expect "a docker/ change since: STALE, remedy rebuilds the :snapshot images" 1 "start a mockserver-java build on master at ${DOCKER_TIP}" -- release_gate "$WORK/measured.json" "$DOCKER_TIP"
jq --arg c "$MEASURED" '.commit = $c | .harness_commit = "HEAD"' "$FIXTURE" > "$WORK/symbolic.json"
release_gate "$WORK/symbolic.json" "$JAVA_TIP" > "$WORK/symbolic.out" 2>&1 || true
if grep -qF "was served the :snapshot image" "$WORK/symbolic.out"; then bad "a symbolic harness_commit is reported as a different build"; else ok "a symbolic harness_commit is not reported as a different build"; fi

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS check(s) failed" >&2; exit 1; fi
echo "all perf preflight gate checks passed"

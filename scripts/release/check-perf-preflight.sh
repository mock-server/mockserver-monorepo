#!/usr/bin/env bash
# Release-preflight PERFORMANCE gate for MockServer.
#
# WHY THIS EXISTS
#   A release must not publish on top of a known, unreviewed performance
#   regression, and must not publish "blind" on top of code no perf run has ever
#   measured. The daily perf pipeline already stores every valid run in S3
#   (s3://<bucket>/runs/<branch>/<iso>__<sha>.json) and the committed, reviewed
#   budgets live in mockserver-performance-test/perf-budgets.json. This gate reads
#   the newest successful run for the release branch and the budget file, and
#   refuses the release when the performance picture is stale, invalid, or in an
#   unaccepted gating-budget breach.
#
# THE 8.0.0 LESSON THIS FOLLOWS
#   8.0.0 half-published because a check confirmed a credential *existed* without
#   proving it could *authenticate*, and a state that could not be proven was
#   reported as a pass. This gate applies the same grading discipline as its
#   sibling scripts/release/check-release-credentials.sh: existence != validity !=
#   capability, and an UNPROVABLE state is its own loud outcome, NEVER a green pass.
#
# WHAT IT CHECKS (against the newest successful run for the branch)
#   1. VALIDITY   the run's own `.validity.valid` must be true. A run that could
#                 not measure anything trustworthy is not evidence.
#   1b. VERDICT   verdicts/<run key> records how the run's compare ended: pass passes; a
#                 fail lists the gating metrics it flagged, each a BREACH unless accepted.
#   2. RECENCY   keyed off GIT ANCESTRY, not wall-clock object age. The daily
#                 producer is COMMIT-GATED (it writes no new object when master
#                 has not moved), so object age cries wolf on a quiet master — the
#                 exact trap the sibling baseline-freshness watchdog documents. So
#                 instead: the run's `.commit` must be an ancestor-or-equal of the
#                 release commit. Equal => this exact code was measured (CURRENT).
#                 A proper ancestor whose commits since change nothing that rebuilds
#                 the :snapshot image, nor docker/ => the release ships the measured
#                 code (EQUIVALENT, passes). Any other proper ancestor => the
#                 release contains N commits no perf run measured (STALE, blocks;
#                 remedy: run perf on the release commit,
#                 or record an acceptance). Not an ancestor => the newest run is on
#                 a different history than the release (DIVERGENT, blocks).
#   3. BUDGETS    for every budget marked `gating:true` in perf-budgets.json:
#                 - ABSOLUTE-floored gating budgets are evaluated DIRECTLY against
#                   this run's value (today: forward.error_rate and
#                   churn.alloc_ratio_index_n15000). A breach BLOCKS
#                   unless an acceptance record covers it. A gating metric that is
#                   absent from the run (e.g. the forward guard did not run) is
#                   INDETERMINATE (fail-closed), never skipped.
#                 - RELATIVE-only gating budgets (floor:null, e.g. microbench.*
#                   time/alloc per op) CANNOT be judged from a single run — they
#                   need the rolling baseline the daily compare step owns. This gate
#                   does NOT recompute that math (it belongs to perf-test-compare.sh);
#                   it reports them as a DELEGATED SEAM and says who covers them,
#                   rather than passing them silently. See "RESIDUAL SEAM" below.
#                 - premerge_alloc.* gating budgets are per-MERGE (perf-alloc-gate.sh)
#                   and are structurally not part of a daily run object, so they are
#                   explicitly out of scope here (reported as such, not a finding).
#
# BLOCKING vs ADVISORY — DERIVED, NOT ASSUMED
#   The blocking set is read from the budget file's own `gating` field. Only
#   `gating:true` budgets can block; a `gating:false` / gating-absent budget is
#   notify-only (it has not yet earned a history-derived budget — the
#   "10 runs before a budget" rule) and is NEVER consulted for a release block.
#   The gate cannot be "wrong to block on a notify-only metric" because it reads
#   the same source of truth the daily compare step does.
#
# RESIDUAL SEAM (stated, not hidden — the 8.0.0 discipline)
#   A RELATIVE gating regression (a microbench time/alloc slowdown vs baseline)
#   is not visible from a single S3 object, so this gate does not evaluate it.
#   Coverage for that class is: (a) perf-test-compare.sh exits non-zero on such a
#   regression and writes a `fail` verdict naming the flagged metrics, which this gate
#   reports as BREACH unless accepted; and (b) this gate's RECENCY check blocks a release
#   whose commit the newest run never measured. The baseline-freshness watchdog is NOT
#   part of this coverage while the perf schedules are off (it reports NOT CHECKED).
#   This gate deliberately reads only S3 + the budget file (no Buildkite API, no
#   baseline recompute) to stay simple and locally runnable.
#
# OUTCOMES, KEPT DISTINCT (this is the whole point)
#   PASS         valid + current-or-not-stale + no unaccepted gating breach
#   INVALID      newest run's validity is false/absent          (finding)
#   UNVERIFIED   no verdict at verdicts/<run key>: its compare never finished, failed
#                before judging the run, or predates verdicts (finding)
#   STALE        newest run measured a proper ancestor of the release commit, and
#                the commits since change what the release ships (finding)
#   DIVERGENT    newest run's commit is not an ancestor of the release commit
#                (measured a different line of development)      (finding)
#   BREACH       a gating ABSOLUTE budget is breached, unaccepted (finding)
#   NO_HISTORY   no perf runs exist for the branch at all        (finding)
#   MALFORMED    the newest run object is unparseable / missing required fields
#   INDETERMINATE a gating metric could not be evaluated, git ancestry could not
#                be resolved, or the budget file is missing/corrupt (fail-closed)
#   S3_DENIED    an AWS session exists but the bucket could not be listed
#   NO_SESSION   no usable AWS session, so nothing could be read
#
# EXIT CODES (aligned with scripts/release/check-release-credentials.sh)
#   0  PASS
#   1  a FINDING — INVALID / UNVERIFIED / STALE / DIVERGENT / BREACH / NO_HISTORY
#   2  INDETERMINATE / MALFORMED — could not conclude; fail-closed, never a pass
#   3  precondition — NO_SESSION / S3_DENIED (nothing could be read)
#   64 bad arguments (a wiring bug must never silently no-op into a false green)
#
# INPUTS (env vars only — this script is CI-agnostic, see release-principles.md #1)
#   PERF_RESULTS_BUCKET            S3 bucket (default mockserver-ci-perf-results)
#   PERF_PREFLIGHT_BRANCH          run-history branch (default master)
#   PERF_PREFLIGHT_RELEASE_COMMIT  release candidate commit for the recency check
#                                  (default: git rev-parse HEAD in the repo)
#   PERF_PREFLIGHT_GIT_REPO        repository whose history the recency check reads
#                                  (default: this checkout)
#   PERF_BUDGETS_FILE              default mockserver-performance-test/perf-budgets.json
#   PERF_ACCEPTED_REGRESSIONS_FILE default mockserver-performance-test/perf-accepted-regressions.json
#                                  (ABSENT = nothing accepted — the safe default)
#   AWS_REGION                     default eu-west-2
#   PERF_PREFLIGHT_LOCAL_DIR       TEST-ONLY: read run *.json from this directory
#                                  instead of S3 (newest = last lexical name), so the
#                                  full grading path runs with no AWS. Never set in CI.
#   PERF_PREFLIGHT_AWS_BIN         aws binary (default aws) — test injection point
#
# USAGE
#   scripts/release/check-perf-preflight.sh [--self-test | --lint-budgets]
#   AWS auth comes from the ambient environment, like the other release scripts.
#   --lint-budgets reads only the budget file (no AWS, no run object): it exits 1
#   when a gating budget has no value mapping here and is not a delegated
#   (floor:null) or premerge_alloc.* entry, so that an unmapped gating budget
#   fails CI when it is added, not when a release runs this gate.

set -uo pipefail

# ---------------------------------------------------------------------------
# Config / arguments
# ---------------------------------------------------------------------------
BUCKET="${PERF_RESULTS_BUCKET:-mockserver-ci-perf-results}"
BRANCH="${PERF_PREFLIGHT_BRANCH:-master}"
REGION="${AWS_REGION:-eu-west-2}"
AWS_BIN="${PERF_PREFLIGHT_AWS_BIN:-aws}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
BUDGETS_FILE="${PERF_BUDGETS_FILE:-$REPO_ROOT/mockserver-performance-test/perf-budgets.json}"
ACCEPT_FILE="${PERF_ACCEPTED_REGRESSIONS_FILE:-$REPO_ROOT/mockserver-performance-test/perf-accepted-regressions.json}"

RUN_SELF_TEST=false
RUN_LINT=false
for arg in "$@"; do
  case "$arg" in
    --self-test) RUN_SELF_TEST=true ;;
    --lint-budgets) RUN_LINT=true ;;
    -h|--help)   awk 'NR==1{next} /^#/{sub(/^# ?/,"");print;next} {exit}' "${BASH_SOURCE[0]}"; exit 0 ;;
    *) echo "ERROR: unknown argument: $arg" >&2; exit 64 ;;
  esac
done

log()  { printf '%s\n' "$*"; }
err()  { printf '%s\n' "$*" >&2; }

WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-preflight.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

# ---------------------------------------------------------------------------
# Pure grading helpers (unit-tested by --self-test; no AWS, no network)
# ---------------------------------------------------------------------------

# Does an acceptance record cover this metric+commit+VALUE? A record matches
# only when ALL of these hold (the only way to silence a breach):
#   - .metric == the budget key (or, for a compare-flagged metric, its row name)
#   - .commit is a CONCRETE >=7-char prefix of the run commit. There is NO
#     wildcard: a PASS requires the run commit == release commit (CURRENT), so a
#     commit-bound acceptance covers exactly ONE release and self-expires when
#     the next commit lands — a recurring flap must earn a fresh reviewed entry.
#   - .max_value is present AND the breaching value is <= it, so an acceptance
#     recorded for a small breach cannot silence a later, worse one (even a
#     re-run at the same commit): a worse regression re-blocks.
# TYPE-SAFE by construction: jq total ordering puts every number before every
# string, so `number <= "0.1"` is UNCONDITIONALLY true — a string max_value (or a
# non-string commit reaching startswith) would silently defeat the guard. So each
# comparison asserts the operand type FIRST, and only then compares. This is
# defence-in-depth with the file-boundary pre-validation, which rejects the same
# mistyped fields loudly. Needs $accept (array) and $commit.
ACCEPTED_JQ='
    def accepted($bkey; $value):
      ($accept // []) | any(
        (.commit // "") as $c |
        (.metric == $bkey)
        and (($c | type) == "string") and (($c | length) >= 7)
        and ($commit | startswith($c))
        and ((.max_value | type) == "number")
        and (($value | type) == "number")
        and ($value <= .max_value)
      );
'

# classify_budgets <run.json> <budgets.json> <accept.json|-> <run_commit>
# Emits one TSV row per gating budget: STATUS<TAB>bkey<TAB>detail
# STATUS in: OK | BREACH | ACCEPTED | ABSENT | UNKNOWN_MAPPING | SKIP_PREMERGE | RELATIVE_DELEGATED
# Non-gating budgets are ignored entirely (they can never block).
classify_budgets() {
  local run="$1" budgets="$2" accept="$3" commit="$4"
  local acc_arg='[]'
  if [[ "$accept" != "-" && -f "$accept" ]]; then
    acc_arg="$(cat "$accept")"
  fi
  jq -r \
    --slurpfile run "$run" \
    --argjson accept "$acc_arg" \
    --arg commit "$commit" "$ACCEPTED_JQ"'
    # Locate a gating ABSOLUTE budget metric value in the run object. Only the
    # keys this gate has been TAUGHT resolve; an untaught absolute gating key
    # returns "unknown" so it fails closed (INDETERMINATE) rather than passing.
    def gval($bkey):
      ($run[0]) as $r |
      if   $bkey == "forward.error_rate" then { known:true, value:($r.forward_guard.error_rate) }
      elif $bkey == "churn.alloc_ratio_index_n15000" then { known:true, value:($r.churn.alloc_ratio_index_n15000) }
      else { known:false, value:null } end;

    # NOTE: .budgets can carry non-budget entries whose value is an ARRAY (e.g.
    # "_comment_streaming"), so guard on object type before indexing .gating.
    (.budgets // {}) | to_entries[]
    | select((.value | type) == "object" and .value.gating == true)
    | .key as $bkey | .value as $b
    | if ($bkey | startswith("premerge_alloc.")) then
        "SKIP_PREMERGE\t\($bkey)\tper-merge allocation gate (perf-alloc-gate.sh); not part of a daily run object"
      elif ($b.floor == null) then
        # Relative-only gating budget: not judgeable from a single run.
        "RELATIVE_DELEGATED\t\($bkey)\trelative (floor:null) — needs the rolling baseline; covered by the compare verdict + recency check"
      else
        gval($bkey) as $g
        | if ($g.known | not) then
            "UNKNOWN_MAPPING\t\($bkey)\tgating absolute budget with no value mapping in this gate — teach check-perf-preflight.sh this metric (fail-closed)"
          elif ($g.value == null) then
            "ABSENT\t\($bkey)\tgating absolute budget but the run has no value for it (e.g. producer did not run this arm)"
          elif (($g.value | type) != "number") then
            # A non-numeric run value would make `number vs string` ordering lie; fail closed.
            "ABSENT\t\($bkey)\tgating value is non-numeric (\($g.value | type)) — cannot compare safely (fail-closed)"
          elif (($b.floor | type) != "number") then
            # A non-numeric floor (budget-file typo) would make `value > "0.01"` read
            # false and silence a real breach — the same total-ordering trap. Fail closed.
            "UNKNOWN_MAPPING\t\($bkey)\tbudget floor is non-numeric (\($b.floor | type)) — cannot compare safely (fail-closed)"
          else
            ($g.value) as $v | ($b.floor) as $f | ($b.dir) as $dir
            | (if $dir == "down" then ($v < $f) else ($v > $f) end) as $breached
            | if $breached then
                (if accepted($bkey; $v) then "ACCEPTED" else "BREACH" end) as $st
                | "\($st)\t\($bkey)\thead=\($v) floor=\($f) dir=\($dir)"
              else
                "OK\t\($bkey)\thead=\($v) floor=\($f) dir=\($dir)"
              end
          end
      end
    ' "$budgets"
}

# lint_budget_mappings <budgets.json>
# Grades every gating budget against an EMPTY run object, so only the mapping is
# exercised (a mapped absolute budget reads ABSENT). Returns 1 on any
# UNKNOWN_MAPPING row, a dropped row, a grader fault, or no gating budgets at all.
lint_budget_mappings() {
  local budgets="$1" empty="$WORK/lint-empty-run.json" expected rows rc n unmapped
  echo '{}' > "$empty"
  if ! expected="$(jq -e '[ (.budgets // {}) | to_entries[] | select((.value|type)=="object" and .value.gating==true) ] | length' "$budgets" 2>/dev/null)"; then
    err "LINT FAIL: budget file missing or unparseable: $budgets"
    return 1
  fi
  if [[ "$expected" -eq 0 ]]; then
    err "LINT FAIL: $budgets declares no gating:true budgets"
    return 1
  fi
  rows="$(classify_budgets "$empty" "$budgets" - 0000000)"; rc=$?
  if [[ $rc -ne 0 ]]; then
    err "LINT FAIL: the grader exited rc=$rc on $budgets"
    return 1
  fi
  n="$(printf '%s\n' "$rows" | grep -c . || true)"
  if [[ "$n" -ne "$expected" ]]; then
    err "LINT FAIL: the grader classified $n of $expected gating budget(s)"
    return 1
  fi
  unmapped="$(printf '%s\n' "$rows" | awk -F'\t' '$1=="UNKNOWN_MAPPING"{print "    " $2 ": " $3}')"
  if [[ -n "$unmapped" ]]; then
    err "LINT FAIL: the release perf preflight gate cannot evaluate these gating budget(s):"
    err "$unmapped"
    err "Map each one in gval() in scripts/release/check-perf-preflight.sh to the path where the"
    err "daily run object carries it. Left unmapped, every release preflight fails closed."
    return 1
  fi
  log "LINT OK: all ${expected} gating budget(s) in $budgets are classified:" \
      "$(printf '%s\n' "$rows" | awk -F'\t' '$1=="ABSENT"{a++} $1=="RELATIVE_DELEGATED"{r++} $1=="SKIP_PREMERGE"{p++}
            END{printf "%d absolute mapped, %d relative delegated, %d premerge_alloc out of scope", a, r, p}')"
}

# ---------------------------------------------------------------------------
# Self-test: prove the grader tells good / bad / unprovable apart. No AWS.
# ---------------------------------------------------------------------------
run_self_test() {
  log "Self-test: does the perf-preflight grader distinguish good / bad / unprovable?"
  local fails=0 d="$WORK/selftest"
  mkdir -p "$d"

  # A minimal budget file with the real gating shapes.
  cat > "$d/budgets.json" <<'JSON'
{ "budgets": {
  "forward.error_rate":              { "dir":"up",   "min_pct":0, "floor":0.01, "gating":true },
  "churn.alloc_ratio_index_n15000":  { "dir":"up",   "min_pct":0, "floor":1.5,  "gating":true },
  "microbench.*.time_per_op":        { "dir":"up",   "min_pct":0.05, "floor":null, "gating":true },
  "premerge_alloc.MatchingBenchmark.alloc_bytes_per_op": { "dir":"up","min_pct":0,"floor":1850000,"gating":true },
  "behaviours.*.error_rate":         { "dir":"up",   "min_pct":0, "floor":0.005, "gating":false }
} }
JSON

  expect_status() { # label expected-status bkey rows
    local label="$1" want="$2" bkey="$3" rows="$4" got
    got="$(printf '%s\n' "$rows" | awk -F'\t' -v k="$bkey" '$2==k{print $1; exit}')"
    if [[ "$got" == "$want" ]]; then
      echo "  ✓ $label -> $got"
    else
      echo "  ✗ $label -> ${got:-<none>} (expected $want)"; fails=$((fails+1))
    fi
  }

  # 1) forward.error_rate within floor -> OK
  echo '{"commit":"abc123def456","forward_guard":{"error_rate":0.002}}' > "$d/ok.json"
  local rows_ok; rows_ok="$(classify_budgets "$d/ok.json" "$d/budgets.json" - abc123def456)"
  expect_status "forward.error_rate 0.002 <= 0.01" OK "forward.error_rate" "$rows_ok"
  # relative gating metric is delegated, never OK/BREACH
  expect_status "microbench relative gating" RELATIVE_DELEGATED "microbench.*.time_per_op" "$rows_ok"
  # premerge is out of scope for a daily run object
  expect_status "premerge_alloc out of scope" SKIP_PREMERGE "premerge_alloc.MatchingBenchmark.alloc_bytes_per_op" "$rows_ok"

  # 2) forward.error_rate over floor, no acceptance -> BREACH
  echo '{"commit":"abc123def456","forward_guard":{"error_rate":0.5}}' > "$d/breach.json"
  local rows_breach; rows_breach="$(classify_budgets "$d/breach.json" "$d/budgets.json" - abc123def456)"
  expect_status "forward.error_rate 0.5 > 0.01" BREACH "forward.error_rate" "$rows_breach"

  # 3) same breach WITH a commit-bound acceptance whose value ceiling covers it -> ACCEPTED
  echo '[{"metric":"forward.error_rate","commit":"abc123d","max_value":0.6,"reason":"known infra flap","accepted_by":"perf-owner","date":"2026-09-17"}]' > "$d/accept.json"
  local rows_acc; rows_acc="$(classify_budgets "$d/breach.json" "$d/budgets.json" "$d/accept.json" abc123def456)"
  expect_status "breach with commit+ceiling acceptance" ACCEPTED "forward.error_rate" "$rows_acc"

  # 4) an acceptance for a DIFFERENT commit must NOT silence the breach
  echo '[{"metric":"forward.error_rate","commit":"9999999","max_value":0.6,"reason":"other","accepted_by":"x","date":"2026-09-17"}]' > "$d/accept-wrong.json"
  local rows_wrong; rows_wrong="$(classify_budgets "$d/breach.json" "$d/budgets.json" "$d/accept-wrong.json" abc123def456)"
  expect_status "acceptance for wrong commit" BREACH "forward.error_rate" "$rows_wrong"

  # 4a) a WILDCARD "*" commit is no longer honoured -> BREACH (MAJOR 2: no blanket bypass)
  echo '[{"metric":"forward.error_rate","commit":"*","max_value":0.6,"reason":"blanket","accepted_by":"x","date":"2026-09-17"}]' > "$d/accept-star.json"
  local rows_star; rows_star="$(classify_budgets "$d/breach.json" "$d/budgets.json" "$d/accept-star.json" abc123def456)"
  expect_status "wildcard '*' acceptance rejected" BREACH "forward.error_rate" "$rows_star"

  # 4b) a value EXCEEDING the acceptance ceiling must re-block -> BREACH (breach 0.5 > ceiling 0.1)
  echo '[{"metric":"forward.error_rate","commit":"abc123d","max_value":0.1,"reason":"small flap only","accepted_by":"x","date":"2026-09-17"}]' > "$d/accept-low.json"
  local rows_low; rows_low="$(classify_budgets "$d/breach.json" "$d/budgets.json" "$d/accept-low.json" abc123def456)"
  expect_status "value above acceptance ceiling" BREACH "forward.error_rate" "$rows_low"

  # 4c) an acceptance WITHOUT a max_value ceiling is incomplete -> BREACH (mandatory ceiling)
  echo '[{"metric":"forward.error_rate","commit":"abc123d","reason":"no ceiling","accepted_by":"x","date":"2026-09-17"}]' > "$d/accept-noceil.json"
  local rows_noceil; rows_noceil="$(classify_budgets "$d/breach.json" "$d/budgets.json" "$d/accept-noceil.json" abc123def456)"
  expect_status "acceptance without max_value" BREACH "forward.error_rate" "$rows_noceil"

  # 4d) a STRING max_value must NOT silence a breach -> BREACH. jq total-ordering makes
  #     `number <= "0.1"` always true; the type guard defeats this quoting typo.
  echo '[{"metric":"forward.error_rate","commit":"abc123d","max_value":"0.1","reason":"typo","accepted_by":"x","date":"2026-09-17"}]' > "$d/accept-strceil.json"
  local rows_strceil; rows_strceil="$(classify_budgets "$d/breach.json" "$d/budgets.json" "$d/accept-strceil.json" abc123def456)"
  expect_status "string max_value (quoting typo)" BREACH "forward.error_rate" "$rows_strceil"

  # 4e) a numeric max_value that covers the breach still ACCEPTS (control for 4d)
  echo '[{"metric":"forward.error_rate","commit":"abc123d","max_value":0.6,"reason":"ok","accepted_by":"x","date":"2026-09-17"}]' > "$d/accept-numceil.json"
  local rows_numceil; rows_numceil="$(classify_budgets "$d/breach.json" "$d/budgets.json" "$d/accept-numceil.json" abc123def456)"
  expect_status "numeric max_value control" ACCEPTED "forward.error_rate" "$rows_numceil"

  # 5) gating absolute metric ABSENT from the run -> ABSENT (fail-closed), not OK
  echo '{"commit":"abc123def456","forward_guard":{"status":"infra_error"}}' > "$d/absent.json"
  local rows_absent; rows_absent="$(classify_budgets "$d/absent.json" "$d/budgets.json" - abc123def456)"
  expect_status "gating metric absent" ABSENT "forward.error_rate" "$rows_absent"

  # 6) an untaught gating ABSOLUTE budget -> UNKNOWN_MAPPING (fail-closed)
  cat > "$d/budgets-unknown.json" <<'JSON'
{ "budgets": { "some.new.gating_metric": { "dir":"up","min_pct":0,"floor":5,"gating":true } } }
JSON
  echo '{"commit":"abc123def456"}' > "$d/any.json"
  local rows_unknown; rows_unknown="$(classify_budgets "$d/any.json" "$d/budgets-unknown.json" - abc123def456)"
  expect_status "untaught gating absolute budget" UNKNOWN_MAPPING "some.new.gating_metric" "$rows_unknown"

  # 7) dir:down gating budget (throughput-shaped) breaches when BELOW floor
  cat > "$d/budgets-down.json" <<'JSON'
{ "budgets": { "forward.error_rate": { "dir":"down","min_pct":0,"floor":0.01,"gating":true } } }
JSON
  echo '{"commit":"abc123def456","forward_guard":{"error_rate":0.5}}' > "$d/downhigh.json"
  local rows_down; rows_down="$(classify_budgets "$d/downhigh.json" "$d/budgets-down.json" - abc123def456)"
  expect_status "dir:down 0.5 not below floor 0.01" OK "forward.error_rate" "$rows_down"

  # 8) churn.alloc_ratio_index_n15000 is read from .churn in the daily run object
  echo '{"commit":"abc123def456","forward_guard":{"error_rate":0},"churn":{"alloc_ratio_index_n15000":1.05}}' > "$d/churn-ok.json"
  expect_status "churn ratio 1.05 <= 1.5" OK "churn.alloc_ratio_index_n15000" \
    "$(classify_budgets "$d/churn-ok.json" "$d/budgets.json" - abc123def456)"
  echo '{"commit":"abc123def456","forward_guard":{"error_rate":0},"churn":{"alloc_ratio_index_n15000":1200}}' > "$d/churn-bad.json"
  expect_status "churn ratio 1200 > 1.5" BREACH "churn.alloc_ratio_index_n15000" \
    "$(classify_budgets "$d/churn-bad.json" "$d/budgets.json" - abc123def456)"
  expect_status "churn ratio absent from the run" ABSENT "churn.alloc_ratio_index_n15000" "$rows_ok"

  # 9) the lint accepts a fully classified budget file and rejects an unmapped one
  if lint_budget_mappings "$d/budgets.json" >/dev/null 2>&1; then
    echo "  ✓ lint passes a budget file whose gating budgets are all classified"
  else
    echo "  ✗ lint failed a fully classified budget file"; fails=$((fails+1))
  fi
  if lint_budget_mappings "$d/budgets-unknown.json" >/dev/null 2>&1; then
    echo "  ✗ lint passed a budget file with an unmapped gating budget"; fails=$((fails+1))
  else
    echo "  ✓ lint fails a budget file with an unmapped gating budget"
  fi

  if [[ $fails -eq 0 ]]; then
    log "Self-test PASSED — the grader distinguishes ok, breach, accepted, absent, unmapped"
    return 0
  fi
  err "Self-test FAILED: $fails case(s) misclassified"
  return 1
}

require_cmd() { command -v "$1" >/dev/null 2>&1 || { err "ERROR: required tool not found: $1"; exit 3; }; }

require_cmd jq
require_cmd git

if [[ "$RUN_SELF_TEST" == "true" && "$RUN_LINT" == "true" ]]; then
  err "ERROR: --self-test and --lint-budgets are separate modes; pass one"
  exit 64
fi
if [[ "$RUN_SELF_TEST" == "true" ]]; then
  run_self_test
  exit $?
fi
if [[ "$RUN_LINT" == "true" ]]; then
  lint_budget_mappings "$BUDGETS_FILE"
  exit $?
fi

# ---------------------------------------------------------------------------
# Main
# ---------------------------------------------------------------------------
log "--- Release-preflight performance gate"
log "    bucket=s3://${BUCKET}  branch=${BRANCH}  budgets=${BUDGETS_FILE}"

# Budget file must exist and parse, or we cannot know what gates. Fail-closed.
if [[ ! -f "$BUDGETS_FILE" ]] || ! jq -e '.budgets' "$BUDGETS_FILE" >/dev/null 2>&1; then
  err "INDETERMINATE: budget file missing or unparseable: $BUDGETS_FILE"
  err "Cannot know which metrics gate a release, so this fails closed (not a pass)."
  exit 2
fi

# Exists-and-parses says the file is READABLE, not that it means what it says.
# This gate derives its entire blocking set from `gating:true` (see EXPECTED_GATING
# below), so a hand-edit typo of that key name -- `gatng` -- does not error: the
# entry validates, `.gating` reads null, and that metric quietly stops blocking the
# release. Likewise a quoted `floor` would be compared by jq as a string and accept
# any value at all. Share the same schema check the daily and per-merge gates use,
# so all three consumers of this file reject the same family of silent disables.
BUDGET_VALIDATOR="$REPO_ROOT/.buildkite/scripts/lib/perf-budgets-validate.sh"
if [[ ! -f "$BUDGET_VALIDATOR" ]]; then
  err "INDETERMINATE: budget schema validator not found: $BUDGET_VALIDATOR"
  err "Cannot confirm the budget file means what it says, so this fails closed."
  exit 2
fi
# shellcheck source=/dev/null
source "$BUDGET_VALIDATOR"
if ! VALIDATOR_SELF_TEST="$(bash "$BUDGET_VALIDATOR" --self-test 2>&1)"; then
  err "INDETERMINATE: the budget schema validator fails its own self-test, so it cannot"
  err "be trusted to have accepted this budget file:"
  printf '%s\n' "$VALIDATOR_SELF_TEST" | sed 's/^/    /' >&2
  exit 2
fi
if ! BUDGET_SCHEMA_PROBLEMS="$(validate_perf_budgets "$BUDGETS_FILE")"; then
  err "INDETERMINATE: budget file has invalid entries: $BUDGETS_FILE"
  printf '%s\n' "$BUDGET_SCHEMA_PROBLEMS" | sed 's/^/    /' >&2
  err "A quoted number or a misspelled key does not error in jq -- it silently reverts"
  err "that budget to its default, which for \`gating\` means it stops blocking releases."
  exit 2
fi

# Pre-validate the acceptance file BEFORE it is fed to the grader. It is a
# hand-maintained JSON file, so an empty file, a trailing comma, or a typo is
# entirely plausible — and a malformed file handed to jq via --argjson would abort
# the grader for every budget, silently emptying its output. Left unchecked, a real
# breach would then never be evaluated and a CURRENT run would pass (the 8.0.0
# present-but-dead-input failure, reproduced inside this gate).
#
# Validate the LOAD-BEARING per-entry types, not merely "is an array": each entry must
# carry a string `metric`, a string `commit` of >=7 chars, and a NUMERIC `max_value`.
# A string max_value (`"0.1"` instead of 0.1) would otherwise defeat the value ceiling
# — jq's total ordering makes `number <= "0.1"` unconditionally true — so a quoting
# typo could silence any breach. Checking only the top-level array type (the easy
# property) while leaving the field types (the load-bearing property) unchecked is
# a known false-green shape, so both layers assert it.
if [[ -f "$ACCEPT_FILE" ]]; then
  if ! jq -e 'type == "array"' "$ACCEPT_FILE" >/dev/null 2>&1; then
    err "INDETERMINATE: acceptance file exists but is not a parseable JSON array: $ACCEPT_FILE"
    err "A malformed acceptance file must NOT silently disable budget evaluation."
    err "Fail-closed: fix or remove it, then re-run. This is NOT a pass."
    exit 2
  fi
  if ! jq -e 'all(.[];
        (type == "object")
        and ((.metric | type) == "string")
        and ((.commit | type) == "string") and ((.commit | length) >= 7)
        and ((.max_value | type) == "number"))' "$ACCEPT_FILE" >/dev/null 2>&1; then
    err "INDETERMINATE: an acceptance entry in $ACCEPT_FILE is malformed."
    err "Each entry MUST have: a string \"metric\", a string \"commit\" (>=7 chars), and a NUMERIC \"max_value\""
    err "(a quoted \"max_value\" is the trap — a string ceiling would silently silence any breach)."
    err "Fail-closed: fix the entry, then re-run. This is NOT a pass."
    exit 2
  fi
fi

# --- 1. Fetch the newest run for the branch ---------------------------------
NEWEST="$WORK/newest.json"
NEWEST_KEY=""
if [[ -n "${PERF_PREFLIGHT_LOCAL_DIR:-}" ]]; then
  # TEST-ONLY local mode: newest = last lexical *.json in the dir (ISO-named,
  # exactly like the S3 keys), so the full grading path runs with no AWS.
  log "    (local-dir mode: ${PERF_PREFLIGHT_LOCAL_DIR})"
  # Run files are ISO-named (like the S3 keys), so a lexical sort is chronological.
  # shellcheck disable=SC2012  # controlled, alphanumeric filenames; mirrors the S3 awk|sort path
  NEWEST_KEY="$(ls -1 "${PERF_PREFLIGHT_LOCAL_DIR}"/*.json 2>/dev/null | sort | tail -n1 || true)"
  if [[ -z "$NEWEST_KEY" ]]; then
    err "NO_HISTORY: no run *.json files in ${PERF_PREFLIGHT_LOCAL_DIR}"
    err "A release cannot be judged with zero performance history. This is a finding, not a pass."
    exit 1
  fi
  cp "$NEWEST_KEY" "$NEWEST"
else
  # Real path: require an AWS session (precondition), then list + fetch newest.
  if ! "$AWS_BIN" sts get-caller-identity >/dev/null 2>&1; then
    err "NO_SESSION: no usable AWS session (aws sts get-caller-identity failed)."
    err "Nothing could be read from S3, so the performance picture is UNKNOWN."
    err "Fail-closed: re-authenticate (e.g. aws sso login) and re-run. This is NOT a pass."
    exit 3
  fi
  log "    AWS session OK: $("$AWS_BIN" sts get-caller-identity --query Arn --output text 2>/dev/null)"
  LS_ERR="$WORK/ls.err"
  if ! "$AWS_BIN" s3 ls "s3://${BUCKET}/runs/${BRANCH}/" --recursive --region "$REGION" >"$WORK/ls.out" 2>"$LS_ERR"; then
    if grep -qiE 'AccessDenied|not authorized|Forbidden' "$LS_ERR"; then
      err "S3_DENIED: the AWS session cannot list s3://${BUCKET}/runs/${BRANCH}/ (access denied)."
      err "Distinct from NO_SESSION: a session exists but lacks the perf-results grant."
      err "Detail: $(head -c 300 "$LS_ERR")"
      exit 3
    fi
    err "S3_DENIED: could not list s3://${BUCKET}/runs/${BRANCH}/ (transport/bucket error)."
    err "Detail: $(head -c 300 "$LS_ERR")"
    exit 3
  fi
  NEWEST_KEY="$(awk '{print $4}' "$WORK/ls.out" | grep -E '\.json$' | sort | tail -n1 || true)"
  if [[ -z "$NEWEST_KEY" ]]; then
    err "NO_HISTORY: no perf runs exist under s3://${BUCKET}/runs/${BRANCH}/."
    err "A release cannot be judged with zero performance history. This is a finding, not a pass."
    exit 1
  fi
  if ! "$AWS_BIN" s3 cp "s3://${BUCKET}/${NEWEST_KEY}" "$NEWEST" --only-show-errors --region "$REGION" 2>"$WORK/cp.err"; then
    err "INDETERMINATE: could not download the newest run s3://${BUCKET}/${NEWEST_KEY}."
    err "Detail: $(head -c 300 "$WORK/cp.err")"
    exit 2
  fi
fi

log "    newest run: ${NEWEST_KEY}"

# --- 2. Parse required fields (MALFORMED if unusable) -----------------------
if ! jq -e '.' "$NEWEST" >/dev/null 2>&1; then
  err "MALFORMED: the newest run object is not valid JSON: ${NEWEST_KEY}"
  err "Fail-closed: a run that cannot be parsed proves nothing. This is NOT a pass."
  exit 2
fi
RUN_COMMIT="$(jq -r '.commit // empty' "$NEWEST")"
RUN_VALID="$(jq -r '.validity.valid // "absent"' "$NEWEST")"
RUN_BRANCH="$(jq -r '.branch // empty' "$NEWEST")"
RUN_TS="$(jq -r '.timestamp_utc // empty' "$NEWEST")"
if [[ -z "$RUN_COMMIT" ]]; then
  err "MALFORMED: the newest run has no .commit field: ${NEWEST_KEY}"
  err "Fail-closed: recency cannot be judged without the measured commit. NOT a pass."
  exit 2
fi
log "    run commit=${RUN_COMMIT:0:12} branch=${RUN_BRANCH:-?} ts=${RUN_TS:-?} validity=${RUN_VALID}"

# Accumulate findings/indeterminates; report ALL, then exit on the worst class.
declare -a FINDINGS=() INDETS=() NOTES=()

# --- 3. VALIDITY ------------------------------------------------------------
if [[ "$RUN_VALID" != "true" ]]; then
  FAILED="$(jq -r '(.validity.checks // []) | map(select(.ok != true) | "\(.name): \(.detail)") | join("; ")' "$NEWEST" 2>/dev/null)"
  FINDINGS+=("INVALID: newest run's validity is '${RUN_VALID}' — it measured nothing trustworthy. Failing checks: ${FAILED:-<no validity block>}")
else
  NOTES+=("VALIDITY ok: .validity.valid == true")
fi

# --- 3b. COMPARE VERDICT ------------------------------------------------------
if [[ -n "${PERF_PREFLIGHT_LOCAL_DIR:-}" ]]; then
  RUN_KEY="runs/${BRANCH}/$(basename "$NEWEST_KEY")"
  VERDICT_SRC="${PERF_PREFLIGHT_LOCAL_DIR}/verdicts/$(basename "$NEWEST_KEY")"
  if [[ -f "$VERDICT_SRC" ]]; then cp "$VERDICT_SRC" "$WORK/verdict.json"; else echo "Not Found" > "$WORK/verdict.err"; fi
else
  RUN_KEY="$NEWEST_KEY"
  "$AWS_BIN" s3 cp "s3://${BUCKET}/verdicts/${RUN_KEY}" "$WORK/verdict.json" --only-show-errors --region "$REGION" 2>"$WORK/verdict.err" || true
fi
ACC_JSON='[]'
[[ -f "$ACCEPT_FILE" ]] && ACC_JSON="$(cat "$ACCEPT_FILE")"
VERDICT_ROWS=""
if [[ -f "$WORK/verdict.json" ]]; then
  VERDICT_ROWS="$(jq -r --arg k "$RUN_KEY" --argjson accept "$ACC_JSON" --arg commit "$RUN_COMMIT" "$ACCEPTED_JQ"'
    if type != "object" or .key != $k then "MALFORMED\tthe verdict is not bound to this run (key: \(.key? // "none"))"
    elif .verdict == "pass" then "PASS"
    elif .verdict == "warming_up" then "WARMING_UP"
    elif .verdict == "fail" and ((.gating_flagged | type) == "array") and ((.gating_flagged | length) > 0) then
      .gating_flagged[] | "\(if ((.metric? | type) == "string") and accepted(.metric; .value?) then "ACCEPTED" else "BREACH" end)\t\(.metric?)\t\(.value?)"
    else "MALFORMED\tunknown verdict \(.verdict | tostring) or an empty gating_flagged list" end' "$WORK/verdict.json" 2>/dev/null)" || VERDICT_ROWS=""
  [[ -n "$VERDICT_ROWS" ]] || VERDICT_ROWS="MALFORMED	the verdict object is not readable JSON"
elif grep -qiE '404|Not Found|NoSuchKey' "$WORK/verdict.err" 2>/dev/null; then
  FINDINGS+=("UNVERIFIED: no compare verdict at verdicts/${RUN_KEY}. Its perf build's compare step never finished, failed before judging the run, or predates verdicts, so the run is not evidence of a judged build. Remedy: if that build's compare step failed, fix the fault its compare step's error annotation names (for example a laptop or per-core presence gate, a metric with no budget entry, or an unreadable history), then run a [perf-run] build on the release commit again; if it never finished or predates verdicts, just run it again.")
else
  INDETS+=("INDETERMINATE (verdict): could not read verdicts/${RUN_KEY}: $(head -c 300 "$WORK/verdict.err" 2>/dev/null)")
fi
while IFS=$'\t' read -r vclass vmetric vvalue; do
  case "$vclass" in
    PASS)       NOTES+=("COMPARE ok: the run's compare passed (verdicts/${RUN_KEY})") ;;
    WARMING_UP) NOTES+=("COMPARE warming up: fewer prior runs than the compare needs, so relative budgets were not judged (verdicts/${RUN_KEY})") ;;
    ACCEPTED)   NOTES+=("COMPARE flag accepted: ${vmetric} = ${vvalue} is covered by a reviewed acceptance for ${RUN_COMMIT:0:12}") ;;
    BREACH)     FINDINGS+=("BREACH (compare): the run's compare step flagged gating metric ${vmetric} = ${vvalue}. Investigate it: fix a real regression; re-run the [perf-run] build if the flag is noise; or record a reviewed acceptance in ${ACCEPT_FILE} with metric \"${vmetric}\", a commit prefix of ${RUN_COMMIT:0:12} and max_value >= ${vvalue}.") ;;
    MALFORMED)  INDETS+=("INDETERMINATE (verdict): verdicts/${RUN_KEY}: ${vmetric}") ;;
  esac
done <<<"$VERDICT_ROWS"

# --- 4. RECENCY (git ancestry, not wall-clock) ------------------------------
# A release commit with no change since the measured one to the paths that rebuild the :snapshot
# image (lib/snapshot-image-inputs.sh) ships the measured code. docker/ must be unchanged too: it
# shapes the released image but does not trigger a :snapshot rebuild.
SNAPSHOT_LIB="$REPO_ROOT/.buildkite/scripts/lib/snapshot-image-inputs.sh"
GIT_REPO="${PERF_PREFLIGHT_GIT_REPO:-$REPO_ROOT}"
REL_COMMIT="${PERF_PREFLIGHT_RELEASE_COMMIT:-$(git -C "$GIT_REPO" rev-parse HEAD 2>/dev/null || true)}"
if [[ ! -f "$SNAPSHOT_LIB" ]]; then
  INDETS+=("INDETERMINATE (recency): ${SNAPSHOT_LIB} not found — cannot tell which commits share a :snapshot image.")
elif [[ -z "$REL_COMMIT" ]]; then
  INDETS+=("INDETERMINATE (recency): could not resolve the release commit (set PERF_PREFLIGHT_RELEASE_COMMIT or run inside the release checkout).")
elif ! git -C "$GIT_REPO" cat-file -e "${REL_COMMIT}^{commit}" 2>/dev/null; then
  INDETS+=("INDETERMINATE (recency): release commit ${REL_COMMIT:0:12} is not present in this clone (shallow/unfetched) — cannot judge ancestry.")
elif ! git -C "$GIT_REPO" cat-file -e "${RUN_COMMIT}^{commit}" 2>/dev/null; then
  INDETS+=("INDETERMINATE (recency): the newest run's commit ${RUN_COMMIT:0:12} is not present in this clone — cannot judge ancestry against the release. Fetch it, or run a fresh perf run on the release commit.")
else
  # shellcheck source=/dev/null
  source "$SNAPSHOT_LIB"
  REL_FULL="$(git -C "$GIT_REPO" rev-parse "${REL_COMMIT}^{commit}")"
  RUN_FULL="$(git -C "$GIT_REPO" rev-parse "${RUN_COMMIT}^{commit}")"
  if [[ "$RUN_FULL" == "$REL_FULL" ]]; then
    NOTES+=("RECENCY ok (CURRENT): the newest perf run measured the exact release commit ${REL_FULL:0:12}.")
  elif git -C "$GIT_REPO" merge-base --is-ancestor "$RUN_FULL" "$REL_FULL" 2>/dev/null; then
    N="$(git -C "$GIT_REPO" rev-list --count "${RUN_FULL}..${REL_FULL}" 2>/dev/null || echo '?')"
    DOCKER_CHANGES="$(git -C "$GIT_REPO" diff --name-only "$RUN_FULL" "$REL_FULL" -- docker/ 2>/dev/null || echo unknown)"
    SNAPSHOT_SAME=false
    snapshot_equivalent "$GIT_REPO" "$RUN_FULL" "$REL_FULL" && SNAPSHOT_SAME=true
    if [[ "$SNAPSHOT_SAME" == true && -z "$DOCKER_CHANGES" ]]; then
      NOTES+=("RECENCY ok (EQUIVALENT): the newest perf run measured ${RUN_FULL:0:12}; the ${N} commit(s) up to the release commit ${REL_FULL:0:12} change none of the paths that rebuild the :snapshot image, nor docker/, so the release ships the measured code.")
    else
      RUN_HARNESS="$(jq -r '.harness_commit // empty' "$NEWEST" 2>/dev/null)"
      LAGGED=""
      if [[ "$RUN_HARNESS" =~ ^[0-9a-f]{40}$ && "$RUN_HARNESS" != "$RUN_FULL" ]]; then
        LAGGED=" That run's build was on ${RUN_HARNESS:0:12} and was served the :snapshot image built from ${RUN_FULL:0:12}."
      fi
      if [[ "$SNAPSHOT_SAME" == true ]]; then
        REMEDY="docker/ changed since the measured commit, which does not rebuild the :snapshot images: start a mockserver-java build on ${BRANCH} at ${REL_FULL} so it pushes them, then create a mockserver-performance-test build at that commit with [perf-run] in its message."
      else
        REMEDY="create a mockserver-performance-test build on ${BRANCH} at commit ${REL_FULL} with [perf-run] in its message (without it the commit guard can skip the run), once mockserver-java has pushed the :snapshot images for the release commit or for its newest ancestor that changes mockserver/, mockserver-ui/ or test-fixtures/. A [perf-run] build refuses an image its commit is not served, within minutes."
      fi
      FINDINGS+=("STALE: the newest perf run measured ${RUN_FULL:0:12}, an ancestor of the release commit ${REL_FULL:0:12}, with ${N} commit(s) in between that NO perf run has measured. The release could contain an unmeasured regression.${LAGGED} Remedy: ${REMEDY} Its run log must show 'provenance: OK' with harness commit ${REL_FULL:0:10}. Then re-run this gate. Or record an acceptance in ${ACCEPT_FILE}.")
    fi
  else
    FINDINGS+=("DIVERGENT: the newest perf run's commit ${RUN_FULL:0:12} is NOT an ancestor of the release commit ${REL_FULL:0:12} — it measured a different line of development. The stored baseline does not describe this release.")
  fi
fi

# --- 5. BUDGETS (gating only; blocking set derived from the file) -----------
# FAIL CLOSED on a grader failure. The committed budget file ALWAYS has gating
# entries (at least forward.error_rate, plus the delegated microbench.* and premerge
# families), so an empty or short grader result cannot be a benign "nothing to gate"
# — it means the grader ERRORED (a malformed input, a jq fault) and a real breach was
# never evaluated. So: count the gating budgets the file declares, run the grader
# capturing its exit status (NOT `|| true`, which swallowed the error the review
# caught), and require exactly one row per gating budget. Any non-zero exit, a
# zero-gating budget file, or a row-count shortfall is INDETERMINATE — never a pass.
EXPECTED_GATING="$(jq '[ (.budgets // {}) | to_entries[] | select((.value|type)=="object" and .value.gating==true) ] | length' "$BUDGETS_FILE" 2>/dev/null || echo 0)"
BUDGET_ROWS="$(classify_budgets "$NEWEST" "$BUDGETS_FILE" "$ACCEPT_FILE" "$RUN_COMMIT")"; CB_RC=$?
ROW_COUNT="$(printf '%s' "$BUDGET_ROWS" | grep -c . || true)"
gate_ok=0 gate_breach=0 gate_accepted=0 gate_absent=0 gate_unknown=0 gate_rel=0 gate_premerge=0
if [[ "$CB_RC" -ne 0 ]]; then
  INDETS+=("INDETERMINATE (budget): the gating-budget grader exited non-zero (rc=${CB_RC}) — it could not evaluate the budgets (malformed input or jq fault). Fail-closed; a real breach may not have been checked.")
elif [[ "${EXPECTED_GATING:-0}" -eq 0 ]]; then
  INDETS+=("INDETERMINATE (budget): ${BUDGETS_FILE} declares NO gating:true budgets — this cannot be the committed budget file (which always gates at least forward.error_rate). Fail-closed rather than pass with nothing enforced.")
elif [[ "$ROW_COUNT" -ne "$EXPECTED_GATING" ]]; then
  INDETS+=("INDETERMINATE (budget): the grader produced ${ROW_COUNT} row(s) for ${EXPECTED_GATING} gating budget(s) — it dropped rows (malformed input or jq fault). Fail-closed rather than pass on a partial evaluation.")
else
  while IFS=$'\t' read -r st bkey detail; do
    [[ -z "$st" ]] && continue
    case "$st" in
      OK)        gate_ok=$((gate_ok+1)) ;;
      ACCEPTED)  gate_accepted=$((gate_accepted+1)); NOTES+=("BUDGET ACCEPTED: ${bkey} breached but covered by a commit+value-bound acceptance record (${detail}).") ;;
      BREACH)    gate_breach=$((gate_breach+1));     FINDINGS+=("BREACH: gating budget ${bkey} breached (${detail}). Not covered by any acceptance record — the release is blocked until it is fixed or an acceptance is recorded in ${ACCEPT_FILE}.") ;;
      ABSENT)    gate_absent=$((gate_absent+1));     INDETS+=("INDETERMINATE (budget): gating budget ${bkey} could not be evaluated — ${detail}.") ;;
      UNKNOWN_MAPPING) gate_unknown=$((gate_unknown+1)); INDETS+=("INDETERMINATE (budget): ${bkey} — ${detail}.") ;;
      RELATIVE_DELEGATED) gate_rel=$((gate_rel+1)) ;;
      SKIP_PREMERGE)      gate_premerge=$((gate_premerge+1)) ;;
    esac
  done <<< "$BUDGET_ROWS"
fi

if [[ $gate_rel -gt 0 ]]; then
  NOTES+=("BUDGETS delegated: ${gate_rel} relative gating metric(s) (floor:null) are not judgeable from a single run — covered by that run's compare verdict and the recency check above (see RESIDUAL SEAM in the script header).")
fi
[[ $gate_premerge -gt 0 ]] && NOTES+=("BUDGETS out-of-scope: ${gate_premerge} premerge_alloc.* gating metric(s) are per-merge (perf-alloc-gate.sh), not part of a daily run object.")
[[ $gate_ok -gt 0 ]] && NOTES+=("BUDGETS ok: ${gate_ok} gating absolute metric(s) within floor.")

# ---------------------------------------------------------------------------
# Report + verdict
# ---------------------------------------------------------------------------
echo
echo "=============================== PERF PREFLIGHT ==============================="
echo "Newest run : ${NEWEST_KEY}"
echo "Run commit : ${RUN_COMMIT:0:12}   Release commit : ${REL_COMMIT:0:12}"
echo "Validity   : ${RUN_VALID}"
echo "-----------------------------------------------------------------------------"
for n in "${NOTES[@]:-}";    do [[ -n "$n" ]] && echo "  · $n"; done
for i in "${INDETS[@]:-}";   do [[ -n "$i" ]] && echo "  ? $i"; done
for f in "${FINDINGS[@]:-}"; do [[ -n "$f" ]] && echo "  ✗ $f"; done
echo "============================================================================="
echo

if [[ ${#FINDINGS[@]} -gt 0 ]]; then
  err "FAIL: ${#FINDINGS[@]} finding(s) — do NOT release (see ✗ rows above)."
  [[ ${#INDETS[@]} -gt 0 ]] && err "      (also ${#INDETS[@]} INDETERMINATE item(s))."
  exit 1
fi
if [[ ${#INDETS[@]} -gt 0 ]]; then
  err "INCONCLUSIVE: ${#INDETS[@]} item(s) could not be evaluated (see ? rows above)."
  err "              Fail-closed: resolve them and re-run before releasing. This is NOT a pass."
  exit 2
fi
log "PASS: the newest perf run is valid, its compare passed, it measures the release commit's history, and no gating budget is in an unaccepted breach."
exit 0

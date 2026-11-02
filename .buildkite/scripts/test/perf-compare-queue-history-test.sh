#!/usr/bin/env bash
# perf-test-compare.sh keeps one history per agent queue: a perf-xl result is persisted under
# runs-perf-xl/ and never enters, or displaces, the perf queue's runs/ baseline window.
# Runs compare against a stub aws (a local directory as the bucket) and a stub buildkite-agent.
# Run: .buildkite/scripts/test/perf-compare-queue-history-test.sh   (PERF_COMPARE_SCRIPT=<path> to test another copy)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_COMPARE_SCRIPT
COMPARE="${PERF_COMPARE_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh}"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
# compare needs bash 4+ (mapfile). macOS ships 3.2, so off-CI that skips loudly; in CI it fails.
if [ "$(bash -c 'echo "${BASH_VERSINFO[0]}"')" -lt 4 ]; then
  if [ "${BUILDKITE:-}" = "true" ]; then echo "FAIL: bash on PATH is older than 4; perf-test-compare.sh cannot run" >&2; exit 1; fi
  echo "SKIP: bash on PATH is older than 4 (perf-test-compare.sh needs mapfile); run this under bash 4+, e.g. in a Linux container"
  exit 0
fi
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-compare-queue-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
B="$WORK/bucket"; mkdir -p "$WORK/bin" "$B"

cat > "$WORK/bin/aws" <<'STUB'
#!/usr/bin/env bash
# s3 cp <src> <dst> and s3 ls s3://<bucket>/<prefix> --recursive, against $FAKE_BUCKET.
# FAKE_LS_FAIL=1|empty: the listing fails, or returns nothing. FAKE_CP_FAIL=1: every download fails.
case "$1 $2" in
  "s3 cp")
    if [[ "$4" == s3://*/verdicts/* && -n "${FAKE_VERDICT_FAIL:-}" ]]; then echo "upload failed: $4" >&2; exit 1
    elif [[ "$4" == s3://* ]]; then k="${4#s3://*/}"; mkdir -p "$FAKE_BUCKET/$(dirname "$k")"; cp "$3" "$FAKE_BUCKET/$k"
    elif [ -n "${FAKE_CP_FAIL:-}" ]; then echo "download failed: $3 (403) Forbidden" >&2; exit 1
    else cp "$FAKE_BUCKET/${3#s3://*/}" "$4"; fi ;;
  "s3 ls")
    [ "${FAKE_LS_FAIL:-}" != empty ] || exit 0
    [ -z "${FAKE_LS_FAIL:-}" ] || { echo "An error occurred (AccessDenied) when calling the ListObjectsV2 operation" >&2; exit 254; }
    p="${3#s3://*/}"
    [ -d "$FAKE_BUCKET/$p" ] && (cd "$FAKE_BUCKET" && find "$p" -type f | sort | sed 's/^/2026-10-01 00:00:00 100 /') ;;
  *) exit 1 ;;
esac
STUB
cat > "$WORK/bin/buildkite-agent" <<'STUB'
#!/usr/bin/env bash
if [ "$1 $2" = "artifact download" ]; then
  [ "$3" = perf-result.json ] && cp "$HEAD_RESULT" "$4/perf-result.json" && exit 0
  exit 1
fi
if [ "$1 $2" = "meta-data set" ]; then [ "$META_SET_FAIL" = true ] && exit 1; echo "$3=$4" >> "$META_LOG"; exit 0; fi
[ "$1" = annotate ] && { echo "$*" >> "$META_LOG.annotate"; cat > /dev/null; }
exit 0
STUB
chmod +x "$WORK/bin/aws" "$WORK/bin/buildkite-agent"

result() { # queue instance_type timestamp -> a valid, plausible, baseline-eligible result
  jq -nc --arg q "$1" --arg it "$2" --arg ts "$3" '{
    schema_version: 3, commit: "0123456789abcdef0123456789abcdef01234567", branch: "master", timestamp_utc: $ts,
    agent: {instance_type: $it, instance_type_source: "observed", queue: $q},
    config: {image_stale: false, config_profile: "default", rig_profile: "default", jvm_diagnostics: "standard"},
    validity: {valid: true, checks: [{name: "fixture", ok: true, detail: ""}]}, baseline_eligible: true,
    behaviours: {match: {p95_ms: 1.5}}}'
}
seed() { # key queue instance_type timestamp
  mkdir -p "$B/$(dirname "$1")"; result "$2" "$3" "$4" > "$B/$1"
}
compare() { # name head_result_file -> log in $WORK/<name>.log, exit code in $WORK/<name>.rc
  local rc=0
  : > "$WORK/$1.meta"; : > "$WORK/$1.meta.annotate"
  env -i PATH="$WORK/bin:$PATH" HOME="${HOME:-/tmp}" TMPDIR="$WORK" FAKE_BUCKET="$B" HEAD_RESULT="$2" META_LOG="$WORK/$1.meta" \
    META_SET_FAIL="${META_SET_FAIL:-}" FAKE_LS_FAIL="${FAKE_LS_FAIL:-}" FAKE_CP_FAIL="${FAKE_CP_FAIL:-}" FAKE_VERDICT_FAIL="${FAKE_VERDICT_FAIL:-}" \
    PERF_BUDGETS_COMMIT=fixture bash "$COMPARE" >"$WORK/$1.log" 2>&1 || rc=$?
  echo "$rc" > "$WORK/$1.rc"
}
baseline_count() { sed -n 's/^--- baseline: \([0-9]*\) prior run(s).*/\1/p' "$WORK/$1.log"; }
count() { find "$B/$1" -type f 2>/dev/null | wc -l | tr -d ' '; }

# Six perf runs, then ten NEWER perf-xl runs, and one stray perf-xl object in runs/ (as an older
# compare would have written it), newest of all, so a shared window would have to take it first.
for i in 1 2 3 4 5 6; do seed "runs/master/2026-09-0${i}T04-00-00Z__000000000${i}.json" perf c5.12xlarge "2026-09-0${i}T04:00:00Z"; done
for i in 10 11 12 13 14 15 16 17 18 19; do seed "runs-perf-xl/master/2026-09-${i}T04-00-00Z__00000000${i}.json" perf-xl c6i.32xlarge "2026-09-${i}T04:00:00Z"; done
seed "runs/master/2026-09-29T04-00-00Z__stray00000.json" perf-xl c6i.32xlarge "2026-09-29T04:00:00Z"

echo "--- 1. a perf-xl result is persisted under runs-perf-xl/ and windows only against it"
result perf-xl c6i.32xlarge 2026-09-30T04:00:00Z > "$WORK/head-xl.json"
compare xl "$WORK/head-xl.json"
check "exit 0" "0" "$(cat "$WORK/xl.rc")"
check "persisted under runs-perf-xl/master/" "yes" "$([ -f "$B/runs-perf-xl/master/2026-09-30T04-00-00Z__0123456789.json" ] && echo yes || echo no)"
check "nothing new under runs/master/" "7" "$(count runs/master)"
check "its window is the ten perf-xl runs" "10" "$(baseline_count xl)"

echo "--- 2. the perf window holds only perf runs, however many perf-xl runs are newer"
result perf c5.12xlarge 2026-10-01T04:00:00Z > "$WORK/head-perf.json"
compare perf "$WORK/head-perf.json"
check "exit 0" "0" "$(cat "$WORK/perf.rc")"
check "persisted under runs/master/" "yes" "$([ -f "$B/runs/master/2026-10-01T04-00-00Z__0123456789.json" ] && echo yes || echo no)"
check "its window is the six perf runs (the stray perf-xl object dropped)" "6" "$(baseline_count perf)"
check "the stray is named as dropped" "1" "$(grep -c 'dropping 2026-09-29T04-00-00Z__stray00000.json' "$WORK/perf.log" || true)"
check "the perf window reached the compare" "yes" "$(grep -q 'Perf baseline warming up' "$WORK/perf.log" && echo no || echo yes)"
check "it tells the publish step the key it persisted" "perf-baseline-persisted-key=runs/master/2026-10-01T04-00-00Z__0123456789.json" "$(cat "$WORK/perf.meta")"
check "a perf-xl run's key is its own history's" "perf-baseline-persisted-key=runs-perf-xl/master/2026-09-30T04-00-00Z__0123456789.json" "$(cat "$WORK/xl.meta")"
jq '.baseline_eligible = false | .timestamp_utc = "2026-10-01T04:30:00Z"' "$WORK/head-perf.json" > "$WORK/head-inelig.json"
compare inelig "$WORK/head-inelig.json"
check "an ineligible run: exit 0, nothing persisted, no key for the publish step" "0|0|" \
  "$(cat "$WORK/inelig.rc")|$(find "$B" -name '2026-10-01T04-30-00Z__*' | wc -l | tr -d ' ')|$(cat "$WORK/inelig.meta")"
check "a persisted run sets no warning about the key" "0" "$(grep -c 'perf-persisted-key' "$WORK/perf.meta.annotate" || true)"
jq '.timestamp_utc = "2026-10-01T04:40:00Z"' "$WORK/head-perf.json" > "$WORK/head-metafail.json"
META_SET_FAIL=true compare metafail "$WORK/head-metafail.json"
check "the key cannot be recorded: still exit 0 and persisted, with a warning annotation" "0|1|1" \
  "$(cat "$WORK/metafail.rc")|$(find "$B/runs" -name '2026-10-01T04-40-00Z__*' | wc -l | tr -d ' ')|$(grep -c 'annotate --style warning --context perf-persisted-key' "$WORK/metafail.meta.annotate" || true)"

echo "--- 3. a result with no queue is a perf run; an unusable queue name fails closed"
jq 'del(.agent.queue) | .timestamp_utc = "2026-10-01T05:00:00Z"' "$WORK/head-perf.json" > "$WORK/head-noqueue.json"
compare noqueue "$WORK/head-noqueue.json"
check "no queue: exit 0" "0" "$(cat "$WORK/noqueue.rc")"
check "no queue: persisted under runs/master/" "yes" "$([ -f "$B/runs/master/2026-10-01T05-00-00Z__0123456789.json" ] && echo yes || echo no)"
jq '.agent.queue = "../runs" | .timestamp_utc = "2026-10-01T06:00:00Z"' "$WORK/head-perf.json" > "$WORK/head-badqueue.json"
compare badqueue "$WORK/head-badqueue.json"
check "unusable queue: exit 1" "1" "$(cat "$WORK/badqueue.rc")"
check "unusable queue: nothing persisted" "0" "$(find "$B" -name '2026-10-01T06-00-00Z__*' | wc -l | tr -d ' ')"

echo "--- 4. an unreadable baseline object fails closed instead of being dropped as foreign"
echo '{"agent": {"queue": "perf"' > "$B/runs/master/2026-09-07T04-00-00Z__corrupt000.json"
jq '.timestamp_utc = "2026-10-01T07:00:00Z"' "$WORK/head-perf.json" > "$WORK/head-corrupt.json"
compare corrupt "$WORK/head-corrupt.json"
check "unreadable object: exit 1" "1" "$(cat "$WORK/corrupt.rc")"
check "unreadable object: named as unreadable" "1" "$(grep -c 'UNREADABLE.*2026-09-07T04-00-00Z__corrupt000.json' "$WORK/corrupt.log" || true)"
check "unreadable object: not reported as another queue" "0" "$(grep -c 'dropping 2026-09-07T04-00-00Z__corrupt000.json' "$WORK/corrupt.log" || true)"
check "unreadable object: still in the bucket" "yes" "$([ -f "$B/runs/master/2026-09-07T04-00-00Z__corrupt000.json" ] && echo yes || echo no)"

echo "--- 5. a history that cannot be read fails the daily compare; it is not read as warming up"
rm -f "$B/runs/master/2026-09-07T04-00-00Z__corrupt000.json"
history_fails() { # name timestamp -> "rc|errors|warming up|persisted" (the FAKE_* failure comes from the caller)
  jq --arg ts "$2" '.timestamp_utc = $ts' "$WORK/head-perf.json" > "$WORK/head-$1.json"
  compare "$1" "$WORK/head-$1.json"
  echo "$(cat "$WORK/$1.rc")|$(grep -c 'build FAILED' "$WORK/$1.log" || true)|$(grep -c 'Perf baseline warming up' "$WORK/$1.log" || true)|$(find "$B" -name "${2//:/-}__*" | wc -l | tr -d ' ')"
}
check "the listing fails: exit 1, an error, not warming up, the run still persisted" "1|1|0|1" "$(FAKE_LS_FAIL=1 history_fails lsfail 2026-10-02T01:00:00Z)"
check "the listing lacks the run just written: the same" "1|1|0|1" "$(FAKE_LS_FAIL=empty history_fails lsempty 2026-10-02T02:00:00Z)"
check "a listed run cannot be downloaded: the same" "1|1|0|1" "$(FAKE_CP_FAIL=1 history_fails cpfail 2026-10-02T03:00:00Z)"
check "the listing failure names the history" "1" "$(grep -c 'Perf history could not be listed' "$WORK/lsfail.log" || true)"
check "the download failure names the object" "1" "$(grep -c 'could not be read.*runs/master/' "$WORK/cpfail.log" || true)"
compare readable "$WORK/head-perf.json"
check "control: a readable history still reaches the compare" "0|0" \
  "$(cat "$WORK/readable.rc")|$(grep -c 'Perf baseline warming up' "$WORK/readable.log" || true)"

echo "--- 6. the release preflight's verdict: pass, warming_up, or none when the compare did not judge the run"
verdict() { jq -r '"\(.verdict)|\(.key)"' "$B/verdicts/$1" 2>/dev/null || echo none; }
check "a passing compare records pass, bound to its run" "pass|runs/master/2026-10-01T04-00-00Z__0123456789.json" \
  "$(verdict runs/master/2026-10-01T04-00-00Z__0123456789.json)"
check "a perf-xl run, which the preflight never reads, records none" "none" "$(verdict runs-perf-xl/master/2026-09-30T04-00-00Z__0123456789.json)"
jq '.branch = "warm"' "$WORK/head-perf.json" > "$WORK/head-warm.json"
compare warm "$WORK/head-warm.json"
check "a warming-up compare (no history) records warming_up" "0|warming_up|runs/warm/2026-10-01T04-00-00Z__0123456789.json" \
  "$(cat "$WORK/warm.rc")|$(verdict runs/warm/2026-10-01T04-00-00Z__0123456789.json)"
check "a run persisted, then failed (unreadable history), records none" "none" "$(verdict runs/master/2026-10-02T03-00-00Z__0123456789.json)"
check "the verdicts never enter the run history" "0" "$(find "$B/runs" "$B/runs-perf-xl" -path '*verdicts*' | wc -l | tr -d ' ')"
jq '.timestamp_utc = "2026-10-03T01:00:00Z"' "$WORK/head-perf.json" > "$WORK/head-noverdict.json"
FAKE_VERDICT_FAIL=1 compare noverdict "$WORK/head-noverdict.json"
check "the verdict cannot be written: exit 1, named" "1|1" \
  "$(cat "$WORK/noverdict.rc")|$(grep -c 'Perf compare verdict NOT recorded' "$WORK/noverdict.log" || true)"

echo "--- 7. a compare that goes red on a gating metric records fail, listing what it flagged"
mkdir -p "$B/runs/gate"
for i in 1 2 3 4 5 6; do
  result perf c5.12xlarge "2026-09-0${i}T04:00:00Z" | jq -c '.branch = "gate" | .forward_guard = {error_rate: 0}' \
    > "$B/runs/gate/2026-09-0${i}T04-00-00Z__000000000${i}.json"
done
gate_verdict() { # name error_rate timestamp -> "rc|verdict|metric=value,..."
  jq --arg ts "$3" --argjson e "$2" '.branch = "gate" | .timestamp_utc = $ts | .forward_guard = {error_rate: $e}' "$WORK/head-perf.json" > "$WORK/head-$1.json"
  compare "$1" "$WORK/head-$1.json"
  echo "$(cat "$WORK/$1.rc")|$(jq -r '"\(.verdict)|\(.gating_flagged | map("\(.metric)=\(.value)") | join(","))"' \
    "$B/verdicts/runs/gate/${3//:/-}__0123456789.json" 2>/dev/null || echo "none|")"
}
check "a gating regression: exit 1, a fail verdict naming forward.error_rate" "1|fail|forward.error_rate=0.5" \
  "$(gate_verdict gatered 0.5 2026-10-04T01:00:00Z)"
check "control: the same history and a clean head records pass" "0|pass|" "$(gate_verdict gateok 0 2026-10-04T02:00:00Z)"
# Mutation: a compare that records pass whatever its exit code must be caught by the case above.
M="$WORK/mut"; mkdir -p "$M/.buildkite/scripts/steps"
ln -s "$REPO_ROOT/.buildkite/scripts/lib" "$M/.buildkite/scripts/lib"
ln -s "$REPO_ROOT/.buildkite/scripts/steps/lib" "$M/.buildkite/scripts/steps/lib"
ln -s "$REPO_ROOT/mockserver-performance-test" "$M/mockserver-performance-test"
sed 's/^if \[ "\$EXIT_CODE" -eq 0 \]; then$/if true; then/' "$COMPARE" > "$M/.buildkite/scripts/steps/perf-test-compare.sh"
if cmp -s "$COMPARE" "$M/.buildkite/scripts/steps/perf-test-compare.sh"; then bad "mutation: the verdict branch was not found to mutate"; fi
REAL_COMPARE="$COMPARE"; COMPARE="$M/.buildkite/scripts/steps/perf-test-compare.sh"
MUTANT="$(gate_verdict gatemut 0.5 2026-10-04T03:00:00Z)"
COMPARE="$REAL_COMPARE"
check "mutation: an unconditional pass verdict records pass on a red compare, which the case above rejects" "1|pass|" "$MUTANT"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all compare queue-history checks passed"

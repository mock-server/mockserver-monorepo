#!/usr/bin/env bash
# perf-website-publish.sh refreshes the website only from a build whose compare step persisted a
# run to runs/<branch>/ (meta-data perf-baseline-persisted-key); any other build exits 0 unchanged,
# never judging another build's newest S3 object. Stub aws and buildkite-agent; no S3, no Docker.
# Run: .buildkite/scripts/test/perf-publish-persist-gate-test.sh   (PERF_PUBLISH_SCRIPT=<path> to test another copy;
# it must sit in a copy of .buildkite/scripts/steps, which it reads its lib/ from)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env PERF_PUBLISH_SCRIPT PERF_COMPARE_SCRIPT
PUBLISH="${PERF_PUBLISH_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-website-publish.sh}"
COMPARE="${PERF_COMPARE_SCRIPT:-$REPO_ROOT/.buildkite/scripts/steps/perf-test-compare.sh}"
T="$(mktemp -d "${TMPDIR:-/tmp}/perf-publish-gate-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}

mkdir -p "$T/bin" "$T/noagent"
# aws records every call. It fails, so a build past the gate ends at "S3 UNREACHABLE" (exit 1),
# unless STUB_NEWEST names the newest object to list; its download then fails ("DOWNLOAD").
cat > "$T/aws" <<'STUB'
#!/usr/bin/env bash
echo "$*" >> "$AWS_LOG"
[ -n "${STUB_NEWEST:-}" ] && [ "$1 $2" = "s3 ls" ] && { echo "2026-10-01 00:00:00 100 runs/master/2026-09-01T04-00-00Z__old.json"; echo "2026-10-01 00:00:00 100 $STUB_NEWEST"; exit 0; }
exit 255
STUB
# buildkite-agent: meta-data get prints $STUB_META (or fails when STUB_META_FAIL=true); the rest succeed.
cat > "$T/bin/buildkite-agent" <<'STUB'
#!/usr/bin/env bash
if [ "$1 $2" = "meta-data get" ]; then
  [ "${STUB_META_FAIL:-}" = true ] && exit 1
  printf '%s' "${STUB_META:-}"; exit 0
fi
cat > /dev/null; exit 0
STUB
chmod +x "$T/aws" "$T/bin/buildkite-agent"
publish() { # path_prefix env... -> output in $OUT, exit code in $RC, aws calls in $T/aws.log
  local path="$1"; shift
  : > "$T/aws.log"; RC=0
  OUT="$(env PATH="$path:$PATH" AWS_LOG="$T/aws.log" PERF_PUBLISH_AWS_BIN="$T/aws" PERF_PUBLISH_REPO_ROOT="$REPO_ROOT" "$@" \
    bash "$PUBLISH" 2>&1)" || RC=$?
}
gated() { [ "$RC" = 0 ] && grep -q 'unchanged — this build did not persist a baseline' <<<"$OUT" && [ ! -s "$T/aws.log" ] && echo yes || echo no; }
passed() { [ "$RC" = 1 ] && grep -q 'S3 UNREACHABLE' <<<"$OUT" && [ -s "$T/aws.log" ] && echo yes || echo no; }

echo "--- 1. a build that persisted nothing exits 0 unchanged, and never reads S3"
publish "$T/bin" STUB_META=""
check "no persisted key: unchanged, exit 0, no S3 call" "yes" "$(gated)"
publish "$T/bin" STUB_META_FAIL=true
check "unreadable meta-data: soft-fails (exit 1), never reads S3" "1|yes|no" \
  "$RC|$(grep -q 'PERSISTED KEY UNREADABLE' <<<"$OUT" && echo yes || echo no)|$([ -s "$T/aws.log" ] && echo yes || echo no)"
publish "$T/bin" STUB_META="" BUILDKITE_SOURCE=schedule
check "a scheduled build with no key soft-fails (exit 1), never reads S3" "1|yes|no" \
  "$RC|$(grep -q 'SCHEDULED BUILD PERSISTED NO RUN' <<<"$OUT" && echo yes || echo no)|$([ -s "$T/aws.log" ] && echo yes || echo no)"
publish "$T/bin" STUB_META="runs-perf-xl/master/2026-10-01T04-00-00Z__0123456789.json"
check "a key outside runs/master/ (a perf-xl history): unchanged" "yes" "$(gated)"
publish "$T/bin" STUB_META="runs/master/2026-10-01T04-00-00Z__0123456789.json" PERF_PUBLISH_PERSISTED_KEY=""
check "PERF_PUBLISH_PERSISTED_KEY set empty overrides the meta-data" "yes" "$(gated)"

echo "--- 2. a build that persisted a run publishes as before"
publish "$T/bin" STUB_META="runs/master/2026-10-01T04-00-00Z__0123456789.json"
check "persisted key under runs/master/: past the gate to S3" "yes" "$(passed)"
check "  ... and names the key" "yes" "$(grep -q 'this build persisted: runs/master/2026-10-01T04-00-00Z__0123456789.json' <<<"$OUT" && echo yes || echo no)"
publish "$T/bin" STUB_META="runs/master/2026-10-01T04-00-00Z__0123456789.json" BUILDKITE_SOURCE=schedule
check "a scheduled build that persisted: past the gate" "yes" "$(passed)"
publish "$T/bin" PERF_PUBLISH_SOURCE_BRANCH=release STUB_META="runs/release/x.json"
check "the branch it publishes from is the one checked" "yes" "$(passed)"
publish "$T/bin" PERF_PUBLISH_PERSISTED_KEY="runs/master/fixture.json"
check "PERF_PUBLISH_PERSISTED_KEY under runs/master/: past the gate" "yes" "$(passed)"

echo "--- 3. it judges only this build's own run"
publish "$T/bin" STUB_META="runs/master/2026-10-01T04-00-00Z__mine.json" STUB_NEWEST="runs/master/2026-10-01T05-00-00Z__theirs.json"
check "a newer object (another build's): unchanged, exit 0, never downloaded" "0|yes|0" \
  "$RC|$(grep -q 'a newer run than this build' <<<"$OUT" && echo yes || echo no)|$(grep -c 's3 cp' "$T/aws.log" || true)"
publish "$T/bin" STUB_META="runs/master/2026-10-01T05-00-00Z__mine.json" STUB_NEWEST="runs/master/2026-10-01T05-00-00Z__mine.json"
check "its own run is the newest: it downloads exactly that key" "1|s3://mockserver-ci-perf-results/runs/master/2026-10-01T05-00-00Z__mine.json" \
  "$RC|$(awk '$1 == "s3" && $2 == "cp" { print $3 }' "$T/aws.log")"
# the perf lint's recorder (test/lib/perf-agent-tripwire.sh) is not a real agent, so its dir is dropped
NOAGENT_PATH="$PATH"
first_agent="$(command -v buildkite-agent || true)"
if [ -n "$first_agent" ] && grep -qx '# perf-agent-tripwire recorder' "$first_agent" 2>/dev/null; then
  NOAGENT_PATH="$(printf '%s' "$PATH" | tr ':' '\n' | grep -vxF "$(dirname "$first_agent")" | paste -sd: -)"
fi
if env PATH="$NOAGENT_PATH" bash -c 'command -v buildkite-agent' >/dev/null 2>&1; then
  echo "  SKIP the no-agent case: a real buildkite-agent is on PATH"
else
  SAVED_PATH="$PATH"; PATH="$NOAGENT_PATH"
  publish "$T/noagent"
  PATH="$SAVED_PATH"
  check "no buildkite-agent (a local run): not gated" "yes" "$(passed)"
fi

echo "--- 4. compare records the key only once it has persisted the run"
PERSIST="$(awk '/^# --- 2\. persist this run to S3/ {p = 1} p' "$COMPARE")"
ELIG_EXIT="$(awk '/Perf run recorded, not baselined/ { print NR; exit }' "$COMPARE")"
CP_LINE="$(grep -n 'aws s3 cp "\$RESULT" "s3://\${BUCKET}/\${KEY}"' "$COMPARE" | cut -d: -f1)" || CP_LINE=""
SET_TEXT='buildkite-agent meta-data set "$PERSISTED_META_KEY" "$KEY"'
SET_LINE="$(grep -nF "$SET_TEXT" "$COMPARE" | cut -d: -f1)" || SET_LINE=""
check "compare sets the persisted key's meta-data, once" "1" "$(grep -cF "$SET_TEXT" "$COMPARE" || true)"
check "  ... as perf-baseline-persisted-key, and as perf-xl-persisted-key only for the multi-k6 arm's own result" "1|1" \
  "$(grep -c '^PERSISTED_META_KEY=perf-baseline-persisted-key$' "$COMPARE" || true)|$(grep -c '^\[ "\$ARM_MODE" = true \] && PERSISTED_META_KEY=perf-xl-persisted-key$' "$COMPARE" || true)"
check "  ... and nowhere else" "2" "$(grep -c 'PERSISTED_META_KEY=' "$COMPARE" || true)"
check "  ... after the ineligible exit and the S3 write" "yes" \
  "$([ -n "$SET_LINE" ] && [ -n "$CP_LINE" ] && [ -n "$ELIG_EXIT" ] && [ "$SET_LINE" -gt "$CP_LINE" ] && [ "$SET_LINE" -gt "$ELIG_EXIT" ] && echo yes || echo no)"
# One process, no `| head`: under pipefail an early-closing reader can fail the writer (SIGPIPE).
GUARD_LINE="$(awk -v t="$SET_TEXT" 'index($0, t) { print prev; exit } { prev = $0 }' <<<"$PERSIST")"
check "  ... only when it wrote to S3 (not a PERF_BASELINE_DIR run)" "yes" \
  "$(grep -q 'if \[ "\$HAVE_AWS" = true \]' <<<"$GUARD_LINE" && echo yes || echo no)"

echo
if [ "$FAILS" -gt 0 ]; then echo ":x: $FAILS check(s) failed" >&2; exit 1; fi
echo "all perf-publish persist-gate checks passed"

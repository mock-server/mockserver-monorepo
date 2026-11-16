#!/usr/bin/env bash
# Network-free checks for .buildkite/scripts/steps/check-release-credentials.sh:
# it keeps the probe's exit code and posts the right Buildkite annotation. The
# wrapper runs against a stub probe and a stub buildkite-agent in a scratch tree.
# Run: .buildkite/scripts/test/release-credential-gate-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
WRAPPER="$REPO_ROOT/.buildkite/scripts/steps/check-release-credentials.sh"
WORK="$(mktemp -d "${TMPDIR:-/tmp}/credgate-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
FAILS=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }

# Scratch tree: the wrapper finds the probe at <root>/scripts/release/.
mkdir -p "$WORK/root/.buildkite/scripts/steps" "$WORK/root/scripts/release" "$WORK/bin" "$WORK/nobk"
cp "$WRAPPER" "$WORK/root/.buildkite/scripts/steps/"
cat > "$WORK/root/scripts/release/check-release-credentials.sh" <<'EOF'
#!/usr/bin/env bash
cat "$STUB_OUT"
exit "$STUB_RC"
EOF
cat > "$WORK/bin/buildkite-agent" <<'EOF'
#!/usr/bin/env bash
{ echo "ARGS: $*"; cat; echo "END"; } >> "$ANN_LOG"
exit "${STUB_ANNOTATE_RC:-0}"
EOF
chmod +x "$WORK/root/scripts/release/check-release-credentials.sh" "$WORK/bin/buildkite-agent"
# A PATH with the wrapper's tools but no buildkite-agent, even on a CI agent.
for t in bash env cat grep sed tee mktemp rm dirname; do ln -s "$(command -v "$t")" "$WORK/nobk/$t"; done

PASS_TABLE='✓ VALID         REQ   npm (mockserver-node / client)            mockserver-release/npm-token
                     registry.npmjs.org identified the token'
NUGET_ROW='✗ REJECTED      opt   NuGet (.NET client / TC)                  mockserver-release/nuget
                     nuget.org refused the key (HTTP 403: The specified API key has expired.)'
NUGET_WARN='--- :x: WARNING: NuGet (.NET client / TC): REJECTED — nuget.org refused the key (HTTP 403: The specified API key has expired.)'

# run <rc> <probe output> [path]: run the wrapper; sets RC, OUT (log) and ANN (annotate calls).
run() {
  printf '%s\n' "$2" > "$WORK/probe.out"
  : > "$WORK/ann.log"
  RC=0
  env PATH="${3:-$WORK/bin:$PATH}" STUB_OUT="$WORK/probe.out" STUB_RC="$1" ANN_LOG="$WORK/ann.log" \
      bash "$WORK/root/.buildkite/scripts/steps/check-release-credentials.sh" > "$WORK/log" 2>&1 || RC=$?
  OUT="$(cat "$WORK/log")"; ANN="$(cat "$WORK/ann.log")"
}
check() { # <label> <condition...>
  local label="$1"; shift
  if "$@"; then ok "$label"; else bad "$label"; sed 's/^/       /' "$WORK/log" "$WORK/ann.log" | tail -n 12 >&2; fi
}
has()  { [[ "$1" == *"$2"* ]]; }
lacks() { [[ "$1" != *"$2"* ]]; }

echo "--- pass with nothing advisory to report"
run 0 "$PASS_TABLE"
check "exit 0" [ "$RC" = 0 ]
check "no annotation posted" [ -z "$ANN" ]

echo "--- pass with an advisory WARNING (dead NuGet key)"
run 0 "$PASS_TABLE
$NUGET_ROW
$NUGET_WARN
--- PASS: all required release credentials authenticated (VALID or VALID-SHAPE)."
check "exit stays 0" [ "$RC" = 0 ]
check "one warning annotation, own context" has "$ANN" "ARGS: annotate --style warning --context release-credential-warnings"
check "  ... exactly one annotate call" [ "$(grep -c '^ARGS:' "$WORK/ann.log")" = 1 ]
check "  ... lists the warning without the log prefix" has "$ANN" "WARNING: NuGet (.NET client / TC): REJECTED"
check "  ... and not the raw '--- :x:' prefix" lacks "$ANN" "--- :x:"
check "  ... and not passing table rows" lacks "$ANN" "npm (mockserver-node"
check "the PASSED line still prints" has "$OUT" "Release credential gate PASSED"

echo "--- pass with a WARNING but no buildkite-agent"
run 0 "$NUGET_WARN" "$WORK/nobk"
check "exit stays 0" [ "$RC" = 0 ]
check "annotation body goes to the log" has "$OUT" "annotation body follows"
check "  ... with the warning in it" has "$OUT" "WARNING: NuGet (.NET client / TC)"

echo "--- pass with a WARNING when annotate itself fails"
STUB_ANNOTATE_RC=1 run 0 "$NUGET_WARN"
check "exit stays 0" [ "$RC" = 0 ]

echo "--- required credential fails"
run 1 "$NUGET_ROW
--- :x: FAIL: 1 required credential(s) REJECTED / MALFORMED / ABSENT — do NOT start the release."
check "exit 1 propagated" [ "$RC" = 1 ]
check "error annotation on the gate context" has "$ANN" "ARGS: annotate --style error --context release-credential-gate"

echo "--- inconclusive run that also has a WARNING"
run 2 "$NUGET_WARN
--- :x: INCONCLUSIVE: 1 required credential(s) could not be probed (INDETERMINATE)."
check "exit 2 propagated" [ "$RC" = 2 ]
check "only the error annotation" [ "$(grep -c '^ARGS:' "$WORK/ann.log")" = 1 ]
check "  ... not the warning one" lacks "$ANN" "release-credential-warnings"

if [ "$FAILS" -gt 0 ]; then
  echo "--- :x: $FAILS release credential gate check(s) failed" >&2
  exit 1
fi
echo "--- :white_check_mark: release credential gate checks passed"

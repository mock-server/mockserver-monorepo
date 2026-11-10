#!/usr/bin/env bash
# Checks for lib/perf-agent-tripwire.sh: a self-test that reaches buildkite-agent without its own stub
# fails the perf lint (naming the call and the test) and posts nothing; a test's own stub still wins;
# and perf-test-lint.sh runs every self-test between the install and the check.
# Run: .buildkite/scripts/test/perf-agent-tripwire-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
# shellcheck source=lib/perf-test-env.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-test-env.sh"
perf_test_scrub_env
LINT="$REPO_ROOT/.buildkite/scripts/steps/perf-test-lint.sh"
FAILS=0
ok()   { echo "  ok   $1"; }
bad()  { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { # name expected actual
  if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi
}
WORK="$(mktemp -d "${TMPDIR:-/tmp}/perf-agent-tripwire-test.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT
# shellcheck source=lib/perf-agent-tripwire.sh
. "$REPO_ROOT/.buildkite/scripts/test/lib/perf-agent-tripwire.sh"

cat > "$WORK/unstubbed-test.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
printf 'body\n' | buildkite-agent annotate --style error --context leaked-verdict
buildkite-agent meta-data set leaked-key value < /dev/null
EOF
cat > "$WORK/stubbed-test.sh" <<'EOF'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p "$1/own"
printf '#!/usr/bin/env bash\ncat > /dev/null\n' > "$1/own/buildkite-agent"; chmod +x "$1/own/buildkite-agent"
export PATH="$1/own:$PATH"
printf 'body\n' | buildkite-agent annotate --style error --context own-stub
EOF

echo "--- 1. a self-test that reaches buildkite-agent fails the check, naming each call and the test"
saved_path="$PATH"
perf_agent_tripwire_install "$WORK/t1"
check "the recording stub is first on PATH" "$WORK/t1/bin/buildkite-agent" "$(command -v buildkite-agent)"
check "  ... carrying the marker perf-publish-persist-gate-test.sh looks for" "1" \
  "$(grep -cx '# perf-agent-tripwire recorder' "$WORK/t1/bin/buildkite-agent" || true)"
rc=0; bash "$WORK/unstubbed-test.sh" || rc=$?
check "the test itself still runs to the end" "0" "$rc"
rc=0; perf_agent_tripwire_check 2>"$WORK/t1.err" || rc=$?
check "the check fails" "1" "$rc"
check "  ... naming the annotation and the meta-data call" "1|1" \
  "$(grep -c 'buildkite-agent annotate --style error --context leaked-verdict' "$WORK/t1.err" || true)|$(grep -c 'buildkite-agent meta-data set leaked-key value' "$WORK/t1.err" || true)"
check "  ... and the test that made them" "2" "$(grep -c 'unstubbed-test.sh' "$WORK/t1.err" || true)"
perf_agent_tripwire_remove
check "remove restores PATH" "$saved_path" "$PATH"

echo "--- 2. a self-test with its own stub is not caught; no call passes the check"
perf_agent_tripwire_install "$WORK/t2"
rc=0; bash "$WORK/stubbed-test.sh" "$WORK/t2" || rc=$?
check "the test's own stub wins" "0|0" "$rc|$(grep -c . "$WORK/t2/calls.log" || true)"
rc=0; perf_agent_tripwire_check 2>/dev/null || rc=$?
check "the check passes" "0" "$rc"
perf_agent_tripwire_remove

echo "--- 3. wiring in perf-test-lint.sh"
# A textual check, so a path built without the text "scripts/test" or "-test.sh" escapes it. Outside
# install..check, no code line may mention either, except this test, the tripwire lib and the k6 init
# tests on the exec line (they run in the k6 container, which has no buildkite-agent). Inside, no line
# may set PATH. Prints the first offending line.
misplaced() { # lint_file
  awk '
    /^[[:space:]]*#/ { next }
    /^perf_agent_tripwire_install / { inst = NR; next }
    /^perf_agent_tripwire_check$/ { chk = NR; next }
    /^exec / { ex = NR }
    inst && !chk && /(^|[^A-Za-z0-9_])PATH=/ { print NR": sets PATH inside the tripwire: "$0; exit }
    inst && !chk { next }
    /perf-agent-tripwire-test\.sh"$|scripts\/test\/lib\/perf-agent-tripwire\.sh"$/ { next }
    {
      line = $0
      if (ex) gsub(/sh \/build\/\.buildkite\/scripts\/test\/k6-[a-z0-9-]+-init-test\.sh/, "", line)
      if (line ~ /scripts\/test|-test\.sh/) { print NR": outside the tripwire: "$0; exit }
    }' "$1"
}
check "installed once and checked once" "1|1" "$(grep -c '^perf_agent_tripwire_install ' "$LINT" || true)|$(grep -c '^perf_agent_tripwire_check$' "$LINT" || true)"
install_at="$(grep -n '^perf_agent_tripwire_install ' "$LINT" | cut -d: -f1)"
check_at="$(grep -n '^perf_agent_tripwire_check$' "$LINT" | cut -d: -f1)"
exec_at="$(grep -n '^exec ' "$LINT" | cut -d: -f1)"
check "install, then check, then the k6 inspect exec" "true" \
  "$([ -n "$install_at" ] && [ -n "$check_at" ] && [ -n "$exec_at" ] && [ "$install_at" -lt "$check_at" ] && [ "$check_at" -lt "$exec_at" ] && echo true || echo false)"
check "every self-test the lint runs is inside the tripwire, which nothing resets" "" "$(misplaced "$LINT")"
check "the lint runs at least the data-plane A/B test inside it" "1" "$(grep -c 'scripts/test/perf-dataplane-ab-test.sh' "$LINT" || true)"
mutant() { # anchor_regex line -> a copy of the lint with <line> inserted after the anchor
  awk -v anchor="$1" -v add="$2" '{ print } $0 ~ anchor { print add }' "$LINT" > "$WORK/lint-mutant.sh"
  misplaced "$WORK/lint-mutant.sh"
}
for form in '  bash "$REPO_ROOT/.buildkite/scripts/test/perf-late-test.sh"' \
            'if ! sh "$REPO_ROOT/.buildkite/scripts/test/late-test.sh"; then exit 1; fi' \
            '"$REPO_ROOT"/.buildkite/scripts/test/other-late-test.sh' \
            'for n in a b; do bash "$REPO_ROOT/.buildkite/scripts/test/${n}-test.sh"; done' \
            'T="$REPO_ROOT/.buildkite/scripts/test"; bash "$T/late.sh"' \
            'bash "$T/late-test.sh"'; do
  check "after the check, caught: $form" "1" "$(mutant '^perf_agent_tripwire_remove$' "$form" | grep -c 'outside the tripwire' || true)"
done
check "before the install, caught" "1" \
  "$(mutant '^TRIPWIRE_DIR=' 'bash "$REPO_ROOT/.buildkite/scripts/test/early-test.sh"' | grep -c 'outside the tripwire' || true)"
for form in 'export PATH="/usr/bin:/bin"' '  PATH="$SAVED:$PATH"' 'env PATH=/usr/bin bash x.sh'; do
  check "a PATH reset inside the tripwire, caught: $form" "1" \
    "$(mutant '^perf_agent_tripwire_install ' "$form" | grep -c 'sets PATH inside the tripwire' || true)"
done
check "a loop-composed test inside the tripwire is allowed" "" \
  "$(mutant '^perf_agent_tripwire_install ' 'for n in a b; do bash "$REPO_ROOT/.buildkite/scripts/test/${n}-test.sh"; done')"
check "the lint runs this test" "1" "$(grep -c '^bash "\$REPO_ROOT/.buildkite/scripts/test/perf-agent-tripwire-test.sh"$' "$LINT" || true)"

if [ "$FAILS" -gt 0 ]; then echo "FAILED: $FAILS check(s)" >&2; exit 1; fi
echo "all buildkite-agent tripwire checks passed"

#!/usr/bin/env bash
# Tests scripts/release/version_refs.py, which update-version-references.sh uses to
# bump version references at release while keeping explicitly marked history, then
# runs its check over this repository. Needs only bash, git and python3; no Docker.
#
#   ./scripts/release/test-version-refs.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
TOOL="$SCRIPT_DIR/version_refs.py"
FIXTURE="$SCRIPT_DIR/testdata/version-refs"

mkdir -p "$REPO_ROOT/.tmp"
WORK="$(mktemp -d "$REPO_ROOT/.tmp/version-refs.XXXXXX")"
trap 'rm -rf "${WORK:?}"' EXIT

failures=0
pass() { echo "  ok   $*"; }
fail() { echo "  FAIL $*" >&2; failures=$((failures + 1)); }
same() { # same <want> <got> <description>
  if cmp -s "$1" "$2"; then pass "$3"; else fail "$3:"; diff "$1" "$2" >&2 || true; fi
}
check_exit() { # check_exit <want 0|1> <description> <check args...>
  local want="$1" what="$2"; shift 2
  local got=0
  python3 "$TOOL" check "$@" > "$WORK/check.out" 2>&1 || got=$?
  if [[ "$got" == "$want" ]]; then pass "$what"; else fail "$what (exit $got, want $want):"; cat "$WORK/check.out" >&2; fi
}

echo "--- rewrite bumps by default and keeps only marked lines"
mkdir -p "$WORK/rewrite/scripts/perf"
cp "$FIXTURE/input.txt" "$FIXTURE/probes.txt" "$WORK/rewrite/"
printf '{"image": "mockserver/mockserver:41.0.0"}\n' > "$WORK/rewrite/scripts/perf/startup-variants.json"
cp "$WORK/rewrite/scripts/perf/startup-variants.json" "$WORK/pinned-before.json"
python3 "$TOOL" rewrite "$WORK/rewrite" --old 41.0.0 --new 42.0.0 --old-api 41.0.x --new-api 42.0.x \
  > "$WORK/rewrite.out" 2> "$WORK/rewrite.err"
same "$FIXTURE/expected.txt" "$WORK/rewrite/input.txt" "fixture rewritten to expected.txt"
same "$FIXTURE/probes-expected.txt" "$WORK/rewrite/probes.txt" "every probe occurrence is bumped, none silently kept"
same "$WORK/pinned-before.json" "$WORK/rewrite/scripts/perf/startup-variants.json" "a file in KEEP_FILES is left as written"
warned="$(grep -c '^warning: suspect: input.txt:' "$WORK/rewrite.err" || true)"
if [[ "$warned" == "2" ]]; then
  pass "the two unmarked historical lines are bumped with a warning"
else
  fail "expected 2 suspect warnings for input.txt, got $warned"
fi

echo "--- lockfiles are never rewritten"
mkdir -p "$WORK/locks/scripts/release"
printf 'idna==41.0.0 \\\n    --hash=sha256:00\n' > "$WORK/locks/scripts/release/requirements-publish.txt"
cp "$WORK/locks/scripts/release/requirements-publish.txt" "$WORK/locks-before.txt"
python3 "$TOOL" rewrite "$WORK/locks" --old 41.0.0 --new 42.0.0 > /dev/null 2>&1
same "$WORK/locks-before.txt" "$WORK/locks/scripts/release/requirements-publish.txt" "a hash-pinned requirements-publish.txt is left as written"

echo "--- KEEP_LINES keeps only the occurrences inside its fragment"
mkdir -p "$WORK/lines/.github"
printf '# leaving 8.0.0 without a VS Code release; image mockserver/mockserver:8.0.0\nimage: mockserver/mockserver:8.0.0\n' \
  > "$WORK/lines/.github/dependabot.yml"
python3 "$TOOL" rewrite "$WORK/lines" --old 8.0.0 --new 9.0.0 > /dev/null 2>&1
printf '# leaving 8.0.0 without a VS Code release; image mockserver/mockserver:9.0.0\nimage: mockserver/mockserver:9.0.0\n' \
  > "$WORK/lines-want.yml"
same "$WORK/lines-want.yml" "$WORK/lines/.github/dependabot.yml" "only the occurrence inside the fragment is kept"
printf '# leaving 8.0.0 without a VS Code release\n# leaving 8.0.0 without a VS Code release\n' > "$WORK/lines/.github/dependabot.yml"
check_exit 1 "check fails on an allowlist entry that does not match exactly one line" "$WORK/lines" --version 8.0.0 --allowlist
if grep -q '^.github/dependabot.yml: KEEP_LINES fragment is on 2 lines, not 1' "$WORK/check.out"; then
  pass "the ambiguous fragment is named"
else
  fail "no report of the fragment matching 2 lines:"
  cat "$WORK/check.out" >&2
fi

echo "--- a SHA-pinned action's version comment is never MockServer's"
mkdir -p "$WORK/pinned/.github/workflows"
printf '      - uses: actions/github-script@3a2844b7e9c422d3c10d287c895573f7108da1b3  # v41.0.0\n' > "$WORK/pinned/.github/workflows/label.yml"
cp "$WORK/pinned/.github/workflows/label.yml" "$WORK/pinned-want.yml"
check_exit 0 "check accepts a pinned action comment that equals the release" "$WORK/pinned" --version 41.0.0
python3 "$TOOL" rewrite "$WORK/pinned" --old 41.0.0 --new 42.0.0 > /dev/null 2>&1
same "$WORK/pinned-want.yml" "$WORK/pinned/.github/workflows/label.yml" "a pinned action comment is left as written"
printf 'env:\n  MOCKSERVER_VERSION: 41.0.0\n' > "$WORK/pinned/.github/workflows/label.yml"
check_exit 1 "check fails on a workflow line the release would bump" "$WORK/pinned" --version 41.0.0
if grep -q '^.github/workflows/label.yml:2: workflow:' "$WORK/check.out"; then
  pass "the workflow line is named"
else
  fail "no workflow report:"
  cat "$WORK/check.out" >&2
fi
printf 'env:\n  MOCKSERVER_API: 41.0.x\n' > "$WORK/pinned/.github/workflows/label.yml"
check_exit 1 "check fails on a workflow X.Y.x line the release would bump" "$WORK/pinned" --version 41.0.0 --api-version 41.0.x
printf 'env:\n  MOCKSERVER_VERSION: 41.0.0  # version-ref:keep\n' > "$WORK/pinned/.github/workflows/label.yml"
check_exit 0 "a keep marker settles a workflow line" "$WORK/pinned" --version 41.0.0

echo "--- check fails on unmarked history, only for the old-version token"
mkdir -p "$WORK/check"
printf 'In 41.0.0 it ran on every read\n' > "$WORK/check/notes.md"
check_exit 1 "unmarked historical phrase fails" "$WORK/check" --version 41.0.0
check_exit 0 "--warn-only reports without failing" "$WORK/check" --version 41.0.0 --warn-only
printf 'In 41.0.0 it ran on every read <!-- version-ref:keep -->\n' > "$WORK/check/notes.md"
check_exit 0 "a keep marker settles it" "$WORK/check" --version 41.0.0
printf 'In 41.0.0 it ran on every read <!-- version-ref:bump -->\n' > "$WORK/check/notes.md"
check_exit 0 "a bump marker accepts the bump" "$WORK/check" --version 41.0.0
printf 'Since 40.0.0, pin mockserver/mockserver:41.0.0\nMockServer is currently at 41.0.0\n' > "$WORK/check/notes.md"
check_exit 0 "a phrase on another version does not fire" "$WORK/check" --version 41.0.0
printf 'To keep the 41.0.0 behaviour: set the flag back\n' > "$WORK/check/notes.md"
check_exit 1 "'the X behaviour' is flagged" "$WORK/check" --version 41.0.0
printf 'A blank status code in the 41.0.0 dashboard became 0\n' > "$WORK/check/notes.md"
check_exit 1 "'the X dashboard' is flagged" "$WORK/check" --version 41.0.0
printf 'Fixed in 42.0.x: the decoder\n' > "$WORK/check/notes.md"
check_exit 1 "an unmarked phrase on the coming X.Y.x line fails" "$WORK/check" --version 41.0.0
printf 'Fixed in 43.0.0 and 43.0.x: a later major is out of scope\n' > "$WORK/check/notes.md"
check_exit 0 "a version beyond the next major is not checked" "$WORK/check" --version 41.0.0
printf '<!-- version-ref:keep-begin -->\ndocker pull mockserver/mockserver:41.0.0\n' > "$WORK/check/notes.md"
check_exit 1 "a keep-begin with no keep-end fails" "$WORK/check" --version 41.0.0

echo "--- text about the coming release is checked before it ships, and passes after"
for variant in unmarked marked; do
  dir="$WORK/post-$variant"
  mkdir -p "$dir/jekyll-www.mock-server.com"
  printf 'mockserver_version: 41.0.0\nmockserver_api_version: 41.0.x\n' > "$dir/jekyll-www.mock-server.com/_config.yml"
  marker=""; [[ "$variant" == "marked" ]] && marker=" <!-- version-ref:keep -->"
  printf 'Removed in 42.0.0: the old flag%s\ndocker pull mockserver/mockserver:41.0.0\n' "$marker" > "$dir/notes.md"
done
check_exit 1 "before the release: unmarked text about the coming release fails" "$WORK/post-unmarked"
check_exit 0 "before the release: the marked tree passes" "$WORK/post-marked"
for variant in unmarked marked; do
  python3 "$TOOL" rewrite "$WORK/post-$variant" --old 41.0.0 --new 42.0.0 --old-api 41.0.x --new-api 42.0.x \
    > /dev/null 2>&1
done
check_exit 1 "after the release (config at 42.0.0): the unmarked tree fails" "$WORK/post-unmarked"
check_exit 0 "after the release (config at 42.0.0): the marked tree passes" "$WORK/post-marked"
if grep -q '^mockserver_version: 42.0.0$' "$WORK/post-marked/jekyll-www.mock-server.com/_config.yml"; then
  pass "the release moved the config to 42.0.0"
else
  fail "the simulated release did not move _config.yml to 42.0.0"
fi

echo "--- this repository: no unmarked history, allowlist entries each match one line"
check_exit 0 "repository check" "$REPO_ROOT"

if [[ "$failures" -gt 0 ]]; then
  echo "--- :x: $failures check(s) failed" >&2
  exit 1
fi
echo "--- all version-reference checks passed"

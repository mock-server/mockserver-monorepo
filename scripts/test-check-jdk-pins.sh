#!/usr/bin/env bash
# Tests scripts/check_jdk_pins.py against fixture Dockerfiles and recorded Adoptium API answers
# (scripts/testdata/jdk-pins), then nothing else: the live check needs the network and runs from
# .github/workflows/jdk-pin-freshness.yml. Needs only bash and python3.
#
#   ./scripts/test-check-jdk-pins.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
TOOL="$SCRIPT_DIR/check_jdk_pins.py"
FIXTURE="$SCRIPT_DIR/testdata/jdk-pins"

mkdir -p "$REPO_ROOT/.tmp"
WORK="$(mktemp -d "$REPO_ROOT/.tmp/jdk-pins.XXXXXX")"
trap 'rm -rf "${WORK:?}"' EXIT

failures=0
# expect <want exit> <message the output must contain> <description> <repo dir> <api dir> [<message it must not contain>]
expect() {
  local want="$1" needle="$2" what="$3" absent="${6:-}" got=0
  python3 -I "$TOOL" --repo "$4" --api-dir "$5" > "$WORK/out" 2>&1 || got=$?
  if [[ "$got" == "$want" ]] && grep -qF -- "$needle" "$WORK/out" \
     && { [[ -z "$absent" ]] || ! grep -qF -- "$absent" "$WORK/out"; }; then
    echo "  ok   $what"
  else
    echo "  FAIL $what (exit $got, want $want, output must contain '$needle'${absent:+ and not '$absent'}):" >&2
    sed 's/^/       /' "$WORK/out" >&2
    failures=$((failures + 1))
  fi
}
# scenario <name>: a fresh copy of the current fixture repo and API answers to mutate
scenario() {
  rm -rf "${WORK:?}/$1"
  mkdir -p "$WORK/$1"
  cp -R "$FIXTURE/current" "$WORK/$1/repo"
  cp -R "$FIXTURE/api" "$WORK/$1/api"
}

expect 0 'PASSED: all 4 Temurin JDK pins' 'every pin is the latest build' "$FIXTURE/current" "$FIXTURE/api"

scenario x64-behind
sed -i.bak 's/27\.0\.1\.1_4/27.0.1.2_1/; s/jdk-27\.0\.1\.1+4/jdk-27.0.1.2+1/; s/27\.0\.1\.1%2B4/27.0.1.2%2B1/' "$WORK/x64-behind/api/27-x64.json"
expect 1 'pins Temurin 27 x64 jdk-27.0.1.1+4, but the latest is jdk-27.0.1.2+1' 'a newer x64 build fails' \
  "$WORK/x64-behind/repo" "$WORK/x64-behind/api"

scenario sha-differs
sed -i.bak 's/bb8a93f9/00000000/' "$WORK/sha-differs/api/21-x64.json"
expect 1 'pins Temurin 21 x64 jdk-21.0.13.1+2, but the latest is jdk-21.0.13.1+2' 'a re-published build with a new sha256 fails' \
  "$WORK/sha-differs/repo" "$WORK/sha-differs/api"

scenario no-arm
sed -i.bak '/aarch64|arm64) STAGE_ARCH=arm64; \\/,/JDK_SHA256=5ddacf92/d' "$WORK/no-arm/repo/docker/a/Dockerfile"
expect 1 'docker/a/Dockerfile pins no aarch64 tarball' 'a pin without an aarch64 tarball fails' "$WORK/no-arm/repo" "$WORK/no-arm/api"

scenario not-adoptium
sed -i.bak 's#https://github.com/adoptium/temurin27-binaries/releases/download/jdk-27.0.1.1%2B4/#https://example.com/jdk/#' "$WORK/not-adoptium/repo/docker/a/Dockerfile"
expect 1 'that is not an Adoptium Temurin release tarball' 'a non-Adoptium URL fails' "$WORK/not-adoptium/repo" "$WORK/not-adoptium/api"

scenario no-api
rm "$WORK/no-api/api/21-aarch64.json"
expect 1 'cannot read the latest Temurin 21 aarch64 build' 'an unreadable API answer fails closed' "$WORK/no-api/repo" "$WORK/no-api/api"

scenario wrong-arch-answer
sed -i.bak 's/"architecture": "aarch64"/"architecture": "x64"/' "$WORK/wrong-arch-answer/api/27-aarch64.json"
expect 1 'no linux/aarch64 JDK in the Adoptium response for 27' 'an answer for another arch fails closed' \
  "$WORK/wrong-arch-answer/repo" "$WORK/wrong-arch-answer/api"

scenario wrong-shape-answer
printf '["not an asset"]\n' > "$WORK/wrong-shape-answer/api/27-x64.json"
expect 1 'cannot read the latest Temurin 27 x64 build' 'a wrong-shape API answer fails with a readable line' \
  "$WORK/wrong-shape-answer/repo" "$WORK/wrong-shape-answer/api"

scenario superseded
sed -i.bak 's/"most_recent_feature_release": 27/"most_recent_feature_release": 28/' "$WORK/superseded/api/available_releases.json"
expect 1 'docker/a/Dockerfile pins Temurin 27, which is out of support' 'a superseded non-LTS major fails, an LTS one does not' \
  "$WORK/superseded/repo" "$WORK/superseded/api" 'pins Temurin 21, which is out of support'

scenario lts-only
sed -i.bak 's/"available_lts_releases": \[8, 11, 17, 21, 25\]/"available_lts_releases": [21, 25]/' "$WORK/lts-only/api/available_releases.json"
expect 0 'PASSED: all 4 Temurin JDK pins' 'an LTS major (21) and the most recent feature release (27) both pass' \
  "$WORK/lts-only/repo" "$WORK/lts-only/api"

scenario no-releases
rm "$WORK/no-releases/api/available_releases.json"
expect 1 "cannot read Adoptium's supported releases" 'a missing available_releases answer fails closed' "$WORK/no-releases/repo" "$WORK/no-releases/api"

scenario bad-releases
sed -i.bak 's/"most_recent_feature_release": 27/"most_recent_feature_release": "27"/' "$WORK/bad-releases/api/available_releases.json"
expect 1 'unexpected available_releases shape' 'a malformed available_releases answer fails closed' "$WORK/bad-releases/repo" "$WORK/bad-releases/api"

scenario no-pins
rm -r "$WORK/no-pins/repo/docker/a" "$WORK/no-pins/repo/docker/b"
expect 1 'found no jdk-download pin' 'a tree with no pins fails rather than passing vacuously' "$WORK/no-pins/repo" "$WORK/no-pins/api"

if [[ "$failures" -gt 0 ]]; then
  echo "--- $failures check_jdk_pins test(s) FAILED" >&2
  exit 1
fi
echo "--- all check_jdk_pins tests passed"

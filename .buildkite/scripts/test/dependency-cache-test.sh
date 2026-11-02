#!/usr/bin/env bash
# Fixture tests for dependency-cache.sh and its run-in-docker.sh wiring, with `aws`,
# `buildkite-agent` and `docker` stubbed on PATH over a directory-backed fake S3.
# Nothing touches AWS or Docker.
# Run: .buildkite/scripts/test/dependency-cache-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
CACHE="$REPO_ROOT/.buildkite/scripts/dependency-cache.sh"
RID="$REPO_ROOT/.buildkite/scripts/run-in-docker.sh"
T="$(mktemp -d "${TMPDIR:-/tmp}/dependency-cache-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
export TMPDIR="$T/tmp"   # token files land here, so the test can assert they are removed
mkdir -p "$TMPDIR"
FAILS=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi; }

BUCKET=mockserver-ci-dependency-cache
export FAKE_S3="$T/s3" AWS_LOG="$T/aws.log"
mkdir -p "$T/bin" "$FAKE_S3/$BUCKET"

cat >"$T/bin/aws" <<'EOF'
#!/usr/bin/env bash
echo "$*" >>"$AWS_LOG"
opt() { local want="$1"; shift; while [ $# -gt 0 ]; do [ "$1" = "$want" ] && { echo "$2"; return; }; shift; done; }
case "$1 $2" in
  "s3 cp")
    if [[ "$3" == s3://* ]]; then
      src="$FAKE_S3/${3#s3://}"; [ -f "$src" ] && cp "$src" "$4"
    else
      # IAM stand-in: only the writer role, with a master token tagged build_source=schedule.
      [ -z "${AWS_ACCESS_KEY_ID:-}" ] \
        && [ "$(cat "${AWS_WEB_IDENTITY_TOKEN_FILE:-/nonexistent}" 2>/dev/null)" = "master-token;build_source=schedule" ] \
        && [ "${AWS_ROLE_ARN:-}" = "arn:aws:iam::123456789012:role/buildkite-dependency-cache-writer" ] \
        || { echo "AccessDenied" >&2; exit 1; }
      sleep "${FAKE_UPLOAD_SLEEP:-0}"
      dst="$FAKE_S3/${4#s3://}"; mkdir -p "$(dirname "$dst")" && cp "$3" "$dst"
    fi ;;
  "s3api list-objects-v2")
    dir="$FAKE_S3/$(opt --bucket "$@")"; prefix="$(opt --prefix "$@")"
    newest="$(cd "$dir" && ls -t "${prefix}"*.tar.gz 2>/dev/null | head -1)"
    if [ -n "$newest" ]; then echo "$newest"; else echo None; fi ;;
  "s3api head-object") [ -f "$FAKE_S3/$(opt --bucket "$@")/$(opt --key "$@")" ] ;;
  "sts get-caller-identity") echo 123456789012 ;;
  *) echo "unexpected aws call: $*" >&2; exit 1 ;;
esac
EOF
cat >"$T/bin/buildkite-agent" <<'EOF'
#!/usr/bin/env bash
# Like Buildkite: the session tag carries the job's real build source, only when asked for.
[ "$1 $2" = "oidc request-token" ] && [ -n "${FAKE_OIDC_TOKEN:-}" ] || exit 1
case " $* " in
  *" --aws-session-tag build_source "*) echo "${FAKE_OIDC_TOKEN};build_source=${BUILDKITE_SOURCE:-}" ;;
  *) echo "$FAKE_OIDC_TOKEN" ;;
esac
EOF
# docker stub: writes a downloaded artifact into the host dir mounted at ~/.m2/repository.
cat >"$T/bin/docker" <<'EOF'
#!/usr/bin/env bash
prev=""; for a in "$@"; do
  if [ "$prev" = "-v" ] && [[ "$a" == *:/root/.m2/repository ]]; then
    d="${a%:/root/.m2/repository}"; mkdir -p "$d/com/example/dep/1.0" && echo jar >"$d/com/example/dep/1.0/dep-1.0.jar"
  fi; prev="$a"
done
exit "${FAKE_DOCKER_RC:-0}"
EOF
chmod +x "$T/bin/"*
export PATH="$T/bin:$PATH"

new_checkout() { # dir
  rm -rf "$1"; mkdir -p "$1/mockserver/core" "$1/mockserver-jetbrains/gradle/wrapper"
  echo "<project>root</project>" >"$1/mockserver/pom.xml"
  echo "<project>core</project>" >"$1/mockserver/core/pom.xml"
  echo "distributionUrl=gradle-9.5.1-bin.zip" >"$1/mockserver-jetbrains/gradle/wrapper/gradle-wrapper.properties"
}
trusted_env() {
  export BUILDKITE_BRANCH=master BUILDKITE_PULL_REQUEST=false BUILDKITE_REFSPEC="" \
    BUILDKITE_PIPELINE_SLUG=mockserver-java BUILDKITE_AGENT_META_DATA_QUEUE=default \
    BUILDKITE_BUILD_NUMBER=7 BUILDKITE_SOURCE=schedule FAKE_OIDC_TOKEN=master-token
  unset DEPENDENCY_CACHE_MAX_MB DEPENDENCY_CACHE_DISABLED
}
populate_m2() { # dir
  mkdir -p "$1/org/apache/thing/1.0" "$1/org/mock-server/mockserver-core/9.9-SNAPSHOT" "$1/org/x/2.0"
  echo jar >"$1/org/apache/thing/1.0/thing-1.0.jar"
  echo stale >"$1/org/mock-server/mockserver-core/9.9-SNAPSHOT/mockserver-core-9.9-SNAPSHOT.jar"
  echo failed >"$1/org/x/2.0/x-2.0.jar.lastUpdated"
}
objects() { (cd "$FAKE_S3/$BUCKET" && find . -type f | sed 's|^\./||' | LC_ALL=C sort); }
state() { sed -n "s/^restored=//p" "$CO/.buildkite-cache/.state-$1" 2>/dev/null; }
aws_calls() { [ -f "$AWS_LOG" ] && wc -l <"$AWS_LOG" | tr -d ' ' || echo 0; }

CO="$T/checkout"
export BUILDKITE_BUILD_CHECKOUT_PATH="$CO"
trusted_env

echo "types that are not persisted"
new_checkout "$CO"; : >"$AWS_LOG"
"$CACHE" restore npm; "$CACHE" save npm
check "npm makes no AWS calls" "0" "$(aws_calls)"

echo "cold maven build on master saves a pruned tree"
"$CACHE" restore maven 2>/dev/null
check "a miss is recorded as cold" "none" "$(state maven)"
populate_m2 "$CO/.buildkite-cache/maven"
mkdir -p "$CO/mockserver/core/target/it" && echo "<project>copy</project>" >"$CO/mockserver/core/target/it/pom.xml"
"$CACHE" save maven 2>/dev/null
OBJ="$(objects)"
check "one object saved under maven/" "1" "$(echo "$OBJ" | grep -Ec '^maven/[0-9a-f]{64}\.tar\.gz$' || true)"
LISTING="$(tar -tzf "$FAKE_S3/$BUCKET/$OBJ")"
check "third-party jar kept" "1" "$(grep -c 'org/apache/thing/1.0/thing-1.0.jar$' <<<"$LISTING" || true)"
check "own org.mock-server artefacts excluded" "0" "$(grep -c 'org/mock-server' <<<"$LISTING" || true)"
check "*.lastUpdated excluded" "0" "$(grep -c 'lastUpdated' <<<"$LISTING" || true)"
check "save used the restore-time key, ignoring target/ poms" "maven/$(sed -n 's/^key=//p' "$CO/.buildkite-cache/.state-maven").tar.gz" "$OBJ"

echo "a later job restores the exact key and does not re-save"
new_checkout "$CO"
"$CACHE" restore maven 2>/dev/null
check "exact hit" "exact" "$(state maven)"
check "restored file present" "jar" "$(cat "$CO/.buildkite-cache/maven/org/apache/thing/1.0/thing-1.0.jar" 2>/dev/null)"
new_checkout "$CO"; mkdir -p "$CO/mockserver/core/target/x" && echo "<project>built</project>" >"$CO/mockserver/core/target/x/pom.xml"
"$CACHE" restore maven 2>/dev/null
check "poms under target/ do not change the key" "exact" "$(state maven)"
new_checkout "$CO"; "$CACHE" restore maven 2>/dev/null
: >"$AWS_LOG"; "$CACHE" restore maven 2>/dev/null
check "second restore in the same job is a no-op" "0" "$(aws_calls)"
"$CACHE" save maven 2>/dev/null
check "a warm job does not save" "1" "$(objects | wc -l | tr -d ' ')"

echo "a changed pom falls back to the newest maven object, and is not saved"
new_checkout "$CO"; echo "<project>core 2</project>" >"$CO/mockserver/core/pom.xml"
"$CACHE" restore maven 2>/dev/null
check "fallback hit" "fallback" "$(state maven)"
"$CACHE" save maven 2>/dev/null
check "fallback tree not saved" "1" "$(objects | wc -l | tr -d ' ')"

echo "untrusted builds never write"
for case in "BUILDKITE_BRANCH=feature" "BUILDKITE_PULL_REQUEST=42" "BUILDKITE_REFSPEC=refs/ci/unit" \
            "BUILDKITE_SOURCE=trigger_job" "BUILDKITE_SOURCE=api" "BUILDKITE_SOURCE=webhook"; do
  new_checkout "$CO"; echo "<project>$case</project>" >"$CO/mockserver/pom.xml"
  rm -rf "${FAKE_S3:?}/$BUCKET/maven"
  trusted_env; export "${case?}"
  "$CACHE" restore maven 2>/dev/null; populate_m2 "$CO/.buildkite-cache/maven"
  OUT="$("$CACHE" save maven 2>&1)"
  check "$case: nothing saved" "0" "$(objects | wc -l | tr -d ' ')"
  check "$case: refused before archiving" "1" "$(grep -c 'read-only' <<<"$OUT" || true)"
done
trusted_env

echo "a refused OIDC role, an oversized tree and a release build are no-ops"
new_checkout "$CO"; "$CACHE" restore maven 2>/dev/null; populate_m2 "$CO/.buildkite-cache/maven"
rc=0; FAKE_OIDC_TOKEN=branch-token "$CACHE" save maven 2>/dev/null || rc=$?
check "OIDC refusal exits 0" "0" "$rc"
check "OIDC refusal saves nothing" "0" "$(objects | wc -l | tr -d ' ')"
DEPENDENCY_CACHE_MAX_MB=0 "$CACHE" save maven 2>/dev/null
check "oversized archive not saved" "0" "$(objects | wc -l | tr -d ' ')"
head -c 3145728 /dev/urandom >"$CO/.buildkite-cache/maven/org/mock-server/mockserver-core/9.9-SNAPSHOT/big.jar"
DEPENDENCY_CACHE_MAX_MB=1 "$CACHE" save maven 2>/dev/null
check "the bound is measured after the exclusions" "1" "$(objects | wc -l | tr -d ' ')"
rm -rf "${FAKE_S3:?}/$BUCKET/maven"

echo "an object that appeared meanwhile is not overwritten"
new_checkout "$CO"; "$CACHE" restore maven 2>/dev/null; populate_m2 "$CO/.buildkite-cache/maven"
mkdir -p "$FAKE_S3/$BUCKET/maven" && echo sentinel >"$FAKE_S3/$BUCKET/maven/$(sed -n 's/^key=//p' "$CO/.buildkite-cache/.state-maven").tar.gz"
"$CACHE" save maven 2>/dev/null
check "existing key kept" "sentinel" "$(cat "$FAKE_S3/$BUCKET"/maven/*.tar.gz)"
rm -rf "${FAKE_S3:?}/$BUCKET/maven"
new_checkout "$CO"; : >"$AWS_LOG"
BUILDKITE_AGENT_META_DATA_QUEUE=release "$CACHE" restore maven 2>/dev/null
BUILDKITE_PIPELINE_SLUG=mockserver-release-preflight "$CACHE" restore maven 2>/dev/null
check "release builds make no AWS calls" "0" "$(aws_calls)"

echo "a corrupt tarball is discarded"
new_checkout "$CO"; KEYED="$T/key"
"$CACHE" restore maven 2>/dev/null; sed -n 's/^key=//p' "$CO/.buildkite-cache/.state-maven" >"$KEYED"
# A valid gzip cut short: tar extracts the first member, then fails.
mkdir -p "$T/src" && echo jar >"$T/src/a.jar" && head -c 2097152 /dev/urandom >"$T/src/big.bin"
tar -czf "$T/whole.tar.gz" -C "$T/src" a.jar big.bin
mkdir -p "$FAKE_S3/$BUCKET/maven" && head -c 1048576 "$T/whole.tar.gz" >"$FAKE_S3/$BUCKET/maven/$(cat "$KEYED").tar.gz"
new_checkout "$CO"; "$CACHE" restore maven 2>/dev/null
check "truncated object leaves a cold build" "none" "$(state maven)"
check "truncated object leaves an empty dir" "" "$(ls -A "$CO/.buildkite-cache/maven")"
check "no partial extraction left behind" "absent" "$([ -e "$CO/.buildkite-cache/maven.partial" ] && echo present || echo absent)"
rm -rf "${FAKE_S3:?}/$BUCKET/maven"

echo "gradle is persisted only for the java pipeline, and exact-only"
new_checkout "$CO"
BUILDKITE_PIPELINE_SLUG="" "$CACHE" restore gradle 2>/dev/null
check "no slug, no restore" "" "$(state gradle)"
: >"$AWS_LOG"; BUILDKITE_PIPELINE_SLUG=mockserver-editors "$CACHE" restore gradle 2>/dev/null
check "editors gradle is not persisted" "0:" "$(aws_calls):$(state gradle)"
mkdir -p "$FAKE_S3/$BUCKET/gradle/java" && tar -czf "$FAKE_S3/$BUCKET/gradle/java/old.tar.gz" -C "$T/bin" .
export BUILDKITE_PIPELINE_SLUG=mockserver-java
"$CACHE" restore gradle 2>/dev/null
check "gradle does not fall back" "none" "$(state gradle)"
mkdir -p "$CO/.buildkite-cache/gradle/caches" "$CO/.buildkite-cache/gradle/wrapper-dists"
"$CACHE" save gradle 2>/dev/null
check "empty gradle directories are not saved" "0" "$(objects | grep -c '^gradle/java/[0-9a-f]' || true)"
mkdir -p "$CO/.buildkite-cache/gradle/caches/9.5.1/transforms/x" "$CO/.buildkite-cache/gradle/caches/modules-2" "$CO/.buildkite-cache/gradle/wrapper-dists"
echo t >"$CO/.buildkite-cache/gradle/caches/9.5.1/transforms/x/big"
echo m >"$CO/.buildkite-cache/gradle/caches/modules-2/dep.jar"
echo l >"$CO/.buildkite-cache/gradle/caches/modules-2/modules-2.lock"
"$CACHE" save gradle 2>/dev/null
GOBJ="$(objects | grep -E '^gradle/java/[0-9a-f]{64}\.tar\.gz$' || true)"
check "gradle saved under its pipeline scope" "1" "$(grep -c . <<<"$GOBJ" || true)"
GLIST="$(tar -tzf "$FAKE_S3/$BUCKET/$GOBJ" 2>/dev/null || true)"
check "gradle keeps module files" "1" "$(grep -c 'modules-2/dep.jar$' <<<"$GLIST" || true)"
check "gradle drops transforms and locks" "0" "$(grep -Ec 'transforms|\.lock$' <<<"$GLIST" || true)"
rm -rf "${FAKE_S3:?}/$BUCKET/gradle"
trusted_env

echo "the OIDC token file never outlives a save"
check "no token file left by the saves above" "0" "$(find "$TMPDIR" -name 'dependency-cache-oidc.*' | wc -l | tr -d ' ')"
new_checkout "$CO"; "$CACHE" restore maven 2>/dev/null; populate_m2 "$CO/.buildkite-cache/maven"
FAKE_UPLOAD_SLEEP=2 "$CACHE" save maven 2>/dev/null &
SAVE_PID=$!
for _ in $(seq 100); do [ -n "$(find "$TMPDIR" -name 'dependency-cache-oidc.*')" ] && break; sleep 0.05; done
check "the token file exists during the upload" "1" "$(find "$TMPDIR" -name 'dependency-cache-oidc.*' | wc -l | tr -d ' ')"
kill -TERM "$SAVE_PID"; wait "$SAVE_PID" || true
check "a TERM (timeout kill) removes the token file" "0" "$(find "$TMPDIR" -name 'dependency-cache-oidc.*' | wc -l | tr -d ' ')"
rm -rf "${FAKE_S3:?}/$BUCKET/maven"

echo "run-in-docker.sh restores before and saves after, only for --cache-save on success"
export BUILDKITE=true
rid() { "$RID" -i img --cache maven "$@" -- true >/dev/null 2>&1; }
new_checkout "$CO"; FAKE_DOCKER_RC=0 rid
check "no --cache-save: nothing saved" "0" "$(objects | wc -l | tr -d ' ')"
check "run-in-docker restored first" "none" "$(state maven)"
new_checkout "$CO"; FAKE_DOCKER_RC=1 rid --cache-save || true
check "failed build: nothing saved" "0" "$(objects | wc -l | tr -d ' ')"
new_checkout "$CO"; FAKE_DOCKER_RC=0 rid --cache-save
check "successful --cache-save build saved" "1" "$(objects | grep -c '^maven/' || true)"
new_checkout "$CO"; rc=0; FAKE_DOCKER_RC=3 rid --cache-save || rc=$?
check "the build's exit code is preserved" "3" "$rc"
new_checkout "$CO"; DEPENDENCY_CACHE_DISABLED=true FAKE_DOCKER_RC=0 rid --cache-save
check "disabled cache: no state" "" "$(state maven)"
unset BUILDKITE

echo
if [ "$FAILS" -ne 0 ]; then echo "${FAILS} check(s) failed" >&2; exit 1; fi
echo "all dependency-cache checks passed"

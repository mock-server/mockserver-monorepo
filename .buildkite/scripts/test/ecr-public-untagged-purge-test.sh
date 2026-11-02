#!/usr/bin/env bash
# Fixture tests for steps/ecr-public-untagged-purge.sh with `aws` and `curl` stubbed on PATH,
# so nothing touches AWS or the registry. The fixture repository mixes tagged multi-arch indexes,
# their untagged children, a nested index, orphaned indexes, a recent push and 230 plain orphans.
# Run: .buildkite/scripts/test/ecr-public-untagged-purge-test.sh
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
STEP="$REPO_ROOT/.buildkite/scripts/steps/ecr-public-untagged-purge.sh"
T="$(mktemp -d "${TMPDIR:-/tmp}/ecr-public-purge-test.XXXXXX")"
trap 'rm -rf "$T"' EXIT
FAILS=0
ok()  { echo "  ok   $1"; }
bad() { echo "  FAIL $1" >&2; FAILS=$((FAILS + 1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1: expected '$2', got '$3'"; fi; }

dg() { printf 'sha256:%064d' "$1"; }
NOW="$(date -u +%s)"
# Old pushes carry fractional seconds and a +01:00 offset (as the CLI prints them); recent ones a Z.
OLD="$(jq -nr --argjson t $((NOW - 48 * 3600 + 3600)) '$t | todate | sub("Z$"; ".120000+01:00")')"
RECENT="$(jq -nr --argjson t $((NOW - 3600)) '$t | todate')"
IDX="application/vnd.oci.image.index.v1+json"
# Kinds: IDX index, MAN image manifest, ATT buildx attestation, SIG an OCI referrer of a type
# the purge does not know (a sigstore bundle), which it must keep even when unreferenced.
KINDS='{
  "IDX": {"imageManifestMediaType": "application/vnd.oci.image.index.v1+json"},
  "MAN": {"imageManifestMediaType": "application/vnd.oci.image.manifest.v1+json", "artifactMediaType": "application/vnd.oci.image.config.v1+json"},
  "ATT": {"imageManifestMediaType": "application/vnd.oci.image.manifest.v1+json", "artifactMediaType": "application/vnd.docker.attestation.manifest.v1+json"},
  "SIG": {"imageManifestMediaType": "application/vnd.oci.image.manifest.v1+json", "artifactMediaType": "application/vnd.dev.sigstore.bundle.v0.3+json"}
}'

# digest-number, kind, pushed-at, tags (comma separated or empty)
IMAGES="
1 IDX $OLD latest,1.0
11 MAN $OLD
12 MAN $OLD
13 ATT $OLD
2 MAN $OLD single
3 IDX $OLD nested
31 IDX $OLD
311 MAN $OLD
41 MAN $OLD
42 MAN $OLD
43 ATT $OLD
5 IDX $RECENT
51 MAN $OLD
6 MAN $OLD
7 MAN $RECENT
8 SIG $OLD
4 IDX $OLD
"
# index digest-number -> child digest-numbers
CHILDREN="
1 11 12 13
3 31
31 311
4 41 42 43
5 51
"
KEEP="1 11 12 13 2 3 31 311 5 51 7 8"
DELETE_HEAD="4 41 42 43 6"

make_fixture() { # dir extra-orphans
  local dir="$1" n
  mkdir -p "$dir/manifests"
  {
    echo "$IMAGES" | while read -r num kind at tags; do
      [ -n "$num" ] || continue
      jq -nc --arg d "$(dg "$num")" --arg kind "$kind" --argjson kinds "$KINDS" --arg at "$at" --arg tags "$tags" \
        '{imageDigest: $d, imagePushedAt: $at} + $kinds[$kind]
         + (if $tags == "" then {} else {imageTags: ($tags | split(","))} end)'
    done
    n=1000
    while [ "$n" -lt $((1000 + $2)) ]; do
      jq -nc --arg d "$(dg "$n")" --argjson kinds "$KINDS" --arg at "$OLD" '{imageDigest: $d, imagePushedAt: $at} + $kinds.MAN'
      n=$((n + 1))
    done
  } | jq -s '{imageDetails: .}' >"$dir/images.json"
  echo "$CHILDREN" | while read -r parent kids; do
    [ -n "$parent" ] || continue
    for k in $kids; do jq -nc --arg d "$(dg "$k")" '{digest: $d}'; done \
      | jq -s --arg m "$IDX" '{schemaVersion: 2, mediaType: $m, manifests: .}' >"$T/manifest.tmp"
    mv "$T/manifest.tmp" "$dir/manifests/$(dg "$parent")"
  done
}

mkdir -p "$T/bin"
cat >"$T/bin/aws" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
repo="" ids=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
  case "${args[$i]}" in
    --repository-name|--repository-names) repo="${args[$((i + 1))]}" ;;
    --image-ids) ids="${args[$((i + 1))]#file://}" ;;
  esac
done
case "$2" in
  describe-repositories) echo "public.ecr.aws/alias1/$repo" ;;
  describe-images)
    if [ "$repo" = "${STUB_DESCRIBE_FAIL_REPO:-}" ]; then echo "AccessDenied (stub)" >&2; exit 254; fi
    if [ "$repo" = "${STUB_NEXT_TOKEN_REPO:-}" ]; then
      jq '. + {NextToken: "stub-more"}' "$STUB_DIR/$repo/images.json"
    else
      cat "$STUB_DIR/$repo/images.json"
    fi
    ;;
  batch-delete-image)
    echo "batch $(jq length "$ids")" >>"$STUB_DIR/batches.log"
    if [ -n "${STUB_DELETE_EXIT:-}" ]; then echo "ThrottlingException (stub)" >&2; exit "$STUB_DELETE_EXIT"; fi
    jq -r '.[].imageDigest' "$ids" | sed "s|^|$repo |" >>"$STUB_DIR/deleted.log"
    jq --arg code "${STUB_FAILURE_CODE:-}" \
      'if $code == "" then {imageIds: ., failures: []}
       else {imageIds: [], failures: map({imageId: ., failureCode: $code, failureReason: "stub"})} end' "$ids"
    ;;
  *) echo "unexpected aws call: $*" >&2; exit 2 ;;
esac
STUB
cat >"$T/bin/curl" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail
out="" fmt="" url=""
while [ $# -gt 0 ]; do
  case "$1" in
    -o) out="$2"; shift ;;
    -w) fmt="$2"; shift ;;
    -H|--max-time) shift ;;
    -*) ;;
    *) url="$1" ;;
  esac
  shift
done
case "$url" in
  https://public.ecr.aws/token/*) echo "token" >>"$STUB_DIR/tokens.log"; echo '{"token":"stub-token"}' ;;
  https://public.ecr.aws/v2/alias1/*/manifests/*)
    rest="${url#https://public.ecr.aws/v2/alias1/}"
    repo="${rest%%/*}" digest="${url##*/}"
    if [ -n "${STUB_STATUS_ONCE:-}" ] && [ ! -f "$STUB_DIR/status-once.done" ]; then
      touch "$STUB_DIR/status-once.done"
      echo '{"errors":[]}' >"$out"; code="$STUB_STATUS_ONCE"
    elif [ -f "$STUB_DIR/$repo/manifests/$digest" ] && [ "$digest" != "${STUB_MISSING_MANIFEST:-}" ]; then
      cat "$STUB_DIR/$repo/manifests/$digest" >"$out"; code=200
    else
      echo '{"errors":[]}' >"$out"; code=404
    fi
    [ -n "$fmt" ] && printf '%s' "$code"
    ;;
  *) echo "unexpected curl: $url" >&2; exit 2 ;;
esac
STUB
chmod +x "$T/bin/aws" "$T/bin/curl"

run_step() { # case-dir [env...] -> rc in $RC, output in $OUT
  local dir="$1"; shift
  RC=0
  OUT="$(env PATH="$T/bin:$PATH" STUB_DIR="$dir" ECR_PUBLIC_PURGE_REPOSITORIES="mockserver mockserver-webhook" \
    ECR_PUBLIC_PURGE_FETCH_INTERVAL=0 "$@" "$STEP" 2>&1)" || RC=$?
}
deleted_in() { # case-dir repo -> sorted digest numbers deleted
  [ -f "$1/deleted.log" ] || return 0
  awk -v r="$2" '$1 == r {print $2}' "$1/deleted.log" | sed 's/^sha256:0*//' | sort -n | tr '\n' ' ' | sed 's/ $//'
}
expected_deleted() { # extra-orphans
  { for d in $DELETE_HEAD; do echo "$d"; done; n=1000; while [ "$n" -lt $((1000 + $1)) ]; do echo "$n"; n=$((n + 1)); done; } \
    | sort -n | tr '\n' ' ' | sed 's/ $//'
}

echo "--- 1. a normal run deletes exactly the unreferenced, old, untagged images"
C="$T/c1"; make_fixture "$C/mockserver" 230; make_fixture "$C/mockserver-webhook" 0
run_step "$C"
check "exit status" "0" "$RC"
check "deleted from mockserver" "$(expected_deleted 230)" "$(deleted_in "$C" mockserver)"
check "deleted from mockserver-webhook" "$(expected_deleted 0)" "$(deleted_in "$C" mockserver-webhook)"
DELETED_DIGESTS="$(awk '{print $2}' "$C/deleted.log")"
KEPT_DELETED=0
for k in $KEEP; do
  if grep -qxF "$(dg "$k")" <<<"$DELETED_DIGESTS"; then bad "kept digest $k was deleted"; KEPT_DELETED=1; fi
done
check "no tagged, referenced, nested, recent or unknown-type digest was deleted" "0" "$KEPT_DELETED"
check "batches never exceed 100" "batch 100" "$(sort -t' ' -k2 -n "$C/batches.log" | tail -1)"
first_index="$(awk '$1 == "mockserver" {print $2; exit}' "$C/deleted.log")"
check "the orphaned index is deleted before its children" "$(dg 4)" "$first_index"

echo "--- 2. a rerun where everything is already gone is green"
C="$T/c2"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" STUB_FAILURE_CODE=ImageNotFound
check "exit status" "0" "$RC"

echo "--- 3. an unreadable index skips that repository but not the next one, and fails the step"
C="$T/c3"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" STUB_MISSING_MANIFEST="$(dg 31)"
check "exit status" "1" "$RC"
check "nothing deleted from either repository" "" "$(deleted_in "$C" mockserver)$(deleted_in "$C" mockserver-webhook)"
case "$OUT" in *"keep-set incomplete; NOTHING deleted from mockserver"*) ok "the skip is reported" ;; *) bad "no skip message: $OUT" ;; esac

echo "--- 4. a repository with no tagged images is refused"
C="$T/c4"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
jq '.imageDetails |= map(del(.imageTags))' "$C/mockserver/images.json" >"$T/untagged.json"
mv "$T/untagged.json" "$C/mockserver/images.json"
run_step "$C"
check "exit status" "1" "$RC"
check "nothing deleted from the untagged repository" "" "$(deleted_in "$C" mockserver)"
check "the other repository is still purged" "$(expected_deleted 0)" "$(deleted_in "$C" mockserver-webhook)"

echo "--- 5. a dry run deletes nothing"
C="$T/c5"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" ECR_PUBLIC_PURGE_DRY_RUN=true
check "exit status" "0" "$RC"
check "no batch-delete-image call" "false" "$([ -f "$C/batches.log" ] && echo true || echo false)"
case "$OUT" in *"mockserver: dry run, 10 would be deleted"*) ok "the dry-run count is reported" ;; *) bad "no dry-run count: $OUT" ;; esac

echo "--- 6. an unexpected delete failure fails the step"
C="$T/c6"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" STUB_FAILURE_CODE=KmsError
check "exit status" "1" "$RC"

echo "--- 7. an unparseable push time fails closed"
C="$T/c7"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
jq '.imageDetails[0].imagePushedAt = "yesterday"' "$C/mockserver/images.json" >"$T/badtime.json"
mv "$T/badtime.json" "$C/mockserver/images.json"
run_step "$C"
check "exit status" "1" "$RC"
check "nothing deleted from that repository" "" "$(deleted_in "$C" mockserver)"

echo "--- 8. a tagged index that lists no children fails closed"
C="$T/c8"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
jq '.manifests = []' "$C/mockserver/manifests/$(dg 1)" >"$T/empty.json"
mv "$T/empty.json" "$C/mockserver/manifests/$(dg 1)"
run_step "$C"
check "exit status" "1" "$RC"
check "nothing deleted from that repository" "" "$(deleted_in "$C" mockserver)"

echo "--- 9. an expired registry token is refreshed and a throttled read retried"
for status in 401 429; do
  C="$T/c9-$status"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
  run_step "$C" STUB_STATUS_ONCE="$status"
  check "HTTP $status once: exit status" "0" "$RC"
  check "HTTP $status once: deleted from mockserver" "$(expected_deleted 5)" "$(deleted_in "$C" mockserver)"
done
check "the 401 fetched a second token for that repository" "3" "$(wc -l <"$T/c9-401/tokens.log" | tr -d ' ')"

echo "--- 10. a failed batch-delete-image call reds the step instead of reporting 'deleted 0'"
C="$T/c10"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" STUB_DELETE_EXIT=255
check "exit status" "1" "$RC"
check "the delete was attempted" "true" "$([ -s "$C/batches.log" ] && echo true || echo false)"
case "$OUT" in *"mockserver: deleted 0, already gone 0, still referenced 0, failed 10"*) ok "the failed images are counted as failed" ;; *) bad "failure not counted: $OUT" ;; esac
case "$OUT" in *"batch-delete-image call failed"*) ok "the failed call is reported" ;; *) bad "no failed-call message: $OUT" ;; esac

echo "--- 11. ECR refusing a still-referenced image is counted and left for the next run"
C="$T/c11"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" STUB_FAILURE_CODE=ImageReferencedByManifestList
check "exit status" "0" "$RC"
case "$OUT" in *"mockserver: deleted 0, already gone 0, still referenced 10, failed 0"*) ok "the refusals are counted" ;; *) bad "refusals not counted: $OUT" ;; esac

echo "--- 12. a failed describe-images skips that repository, purges the next, and fails the step"
C="$T/c12"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" STUB_DESCRIBE_FAIL_REPO=mockserver
check "exit status" "1" "$RC"
check "nothing deleted from the unlisted repository" "" "$(deleted_in "$C" mockserver)"
check "the other repository is still purged" "$(expected_deleted 0)" "$(deleted_in "$C" mockserver-webhook)"

echo "--- 13. a truncated listing (NextToken present) fails closed"
C="$T/c13"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" STUB_NEXT_TOKEN_REPO=mockserver
check "exit status" "1" "$RC"
check "nothing deleted from the truncated repository" "" "$(deleted_in "$C" mockserver)"

echo "--- 14. a dry-run value other than exactly true/false is refused before anything runs"
for value in 1 TRUE yes ""; do
  C="$T/c14-${value:-empty}"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
  run_step "$C" ECR_PUBLIC_PURGE_DRY_RUN="$value"
  check "DRY_RUN='$value': exit status" "1" "$RC"
  check "DRY_RUN='$value': no batch-delete-image call" "false" "$([ -f "$C/batches.log" ] && echo true || echo false)"
done
C="$T/c14-false"; make_fixture "$C/mockserver" 5; make_fixture "$C/mockserver-webhook" 0
run_step "$C" ECR_PUBLIC_PURGE_DRY_RUN=false
check "DRY_RUN='false' is a real run" "$(expected_deleted 5)" "$(deleted_in "$C" mockserver)"

if [ "$FAILS" -gt 0 ]; then
  echo "+++ :x: $FAILS check(s) failed" >&2
  exit 1
fi
echo "all ECR Public purge checks passed"

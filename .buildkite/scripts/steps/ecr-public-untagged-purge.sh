#!/usr/bin/env bash
set -euo pipefail

# Deletes untagged ECR Public images that nothing references. Untagged is NOT orphaned:
# a tagged multi-arch index's per-arch and attestation manifests are untagged too, so
# the keep-set is the closure of every tagged image and every image younger than
# MIN_AGE_HOURS; a repository whose keep-set is incomplete is never purged.
# Settings and rationale: docs/infrastructure/ci-cd.md (ECR Public Untagged-Image Purge).

REGION="us-east-1"
REGISTRY_HOST="public.ecr.aws"
read -r -a REPOSITORIES <<<"${ECR_PUBLIC_PURGE_REPOSITORIES:-mockserver mockserver-webhook}"
MIN_AGE_HOURS="${ECR_PUBLIC_PURGE_MIN_AGE_HOURS:-24}"
# Unset means a real run (the scheduled default); anything else but true/false, even empty, is refused.
DRY_RUN="${ECR_PUBLIC_PURGE_DRY_RUN-false}"
FETCH_INTERVAL="${ECR_PUBLIC_PURGE_FETCH_INTERVAL:-1}"
BATCH_SIZE=100
export AWS_RETRY_MODE="${AWS_RETRY_MODE:-standard}"
export AWS_MAX_ATTEMPTS="${AWS_MAX_ATTEMPTS:-10}"

INDEX_TYPES_JSON='["application/vnd.oci.image.index.v1+json","application/vnd.docker.distribution.manifest.list.v2+json"]'
DELETABLE_ARTIFACT_TYPES_JSON='["application/vnd.oci.image.config.v1+json","application/vnd.docker.container.image.v1+json","application/vnd.docker.attestation.manifest.v1+json"]'
MANIFEST_ACCEPT="application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json"

if ! [[ "$MIN_AGE_HOURS" =~ ^[0-9]+$ ]] || [ "$MIN_AGE_HOURS" -lt 1 ]; then
  echo "ECR_PUBLIC_PURGE_MIN_AGE_HOURS must be a whole number >= 1 (got '$MIN_AGE_HOURS')" >&2
  exit 1
fi
if [ "$DRY_RUN" != "true" ] && [ "$DRY_RUN" != "false" ]; then
  echo "ECR_PUBLIC_PURGE_DRY_RUN must be exactly 'true' or 'false' (got '$DRY_RUN'); nothing deleted" >&2
  exit 1
fi

WORK="$(mktemp -d "${TMPDIR:-/tmp}/ecr-public-purge.XXXXXX")"
trap 'rm -rf "$WORK"' EXIT

NOW_EPOCH="$(date -u +%s)"
CUTOFF_EPOCH=$((NOW_EPOCH - MIN_AGE_HOURS * 3600))

# imagePushedAt arrives as ISO-8601 with fractional seconds and a numeric offset
# (or as epoch seconds under cli_timestamp_format=none); jq's fromdateiso8601
# accepts only a bare Z, so normalise. An unparseable value aborts the jq run.
JQ_EPOCH='def epoch:
  if type == "number" then floor
  else (capture("^(?<d>[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2})(\\.[0-9]+)?(?<z>Z|[+-][0-9]{2}:?[0-9]{2})$")
        // error("unparseable imagePushedAt: \(.)")) as $c
    | ($c.d + "Z" | fromdateiso8601)
      - (if $c.z == "Z" then 0
         else ($c.z | gsub(":"; "")) as $o
           | (($o[1:3] | tonumber) * 3600 + ($o[3:5] | tonumber) * 60) * (if $o[0:1] == "+" then 1 else -1 end)
         end)
  end;'

TOTAL_DELETED=0
FAILED_REPOS=()
SUMMARY=()

refresh_registry_token() { # repo_path -> anonymous pull token in $WORK/token
  local out
  out="$(curl -sS --fail --max-time 30 "https://${REGISTRY_HOST}/token/?service=${REGISTRY_HOST}&scope=repository:$1:pull")" || return 1
  jq -er '.token' <<<"$out" >"$WORK/token"
}

fetch_manifest() { # repo_path digest -> manifest JSON on stdout
  local attempt code body="$WORK/manifest.body"
  for attempt in 1 2 3 4 5 6; do
    sleep "$FETCH_INTERVAL"
    code="$(curl -sS --max-time 30 -o "$body" -w '%{http_code}' \
      -H "Authorization: Bearer $(cat "$WORK/token")" -H "Accept: $MANIFEST_ACCEPT" \
      "https://${REGISTRY_HOST}/v2/$1/manifests/$2")" || code="000"
    case "$code" in
      200) cat "$body"; return 0 ;;
      401) refresh_registry_token "$1" || return 1 ;;
      429 | 5?? | 000) sleep $((attempt * 2)) ;;
      *) echo "    manifest $2: HTTP $code" >&2; return 1 ;;
    esac
  done
  echo "    manifest $2: gave up after 6 attempts (last HTTP $code)" >&2
  return 1
}

# Writes the closure of digests reachable from the roots to $WORK/keep.txt.
# Returns 1 if any index cannot be read or lists no children. These functions run
# under `if !`, where set -e is suspended, so every step carries `|| return 1`.
build_keep_set() { # repo_path images.json
  local repo_path="$1" images="$2" digest manifest children
  : >"$WORK/keep.txt"
  jq -r --argjson cutoff "$CUTOFF_EPOCH" "$JQ_EPOCH"'
    .imageDetails[]
    | select(((.imageTags // []) | length) > 0 or (.imagePushedAt | epoch) > $cutoff)
    | .imageDigest' "$images" | sort -u >"$WORK/queue.txt" || return 1
  jq -r --argjson types "$INDEX_TYPES_JSON" \
    '.imageDetails[] | select(.imageManifestMediaType as $m | $types | index($m)) | .imageDigest' \
    "$images" | sort -u >"$WORK/index-digests.txt" || return 1
  refresh_registry_token "$repo_path" || { echo "    could not obtain a registry pull token for $repo_path" >&2; return 1; }

  while [ -s "$WORK/queue.txt" ]; do
    digest="$(head -1 "$WORK/queue.txt")"
    sed '1d' "$WORK/queue.txt" >"$WORK/queue.next" || return 1
    mv "$WORK/queue.next" "$WORK/queue.txt" || return 1
    if grep -qxF "$digest" "$WORK/keep.txt"; then
      continue
    fi
    echo "$digest" >>"$WORK/keep.txt" || return 1
    grep -qxF "$digest" "$WORK/index-digests.txt" || continue
    manifest="$(fetch_manifest "$repo_path" "$digest")" || return 1
    children="$(jq -r '.manifests[]?.digest' <<<"$manifest")" || return 1
    if [ -z "$children" ]; then
      echo "    index $digest lists no child manifests; refusing to build a keep-set from it" >&2
      return 1
    fi
    printf '%s\n' "$children" >>"$WORK/queue.txt" || return 1
  done
  sort -u -o "$WORK/keep.txt" "$WORK/keep.txt"
}

delete_batch() { # repo ids.json -> prints "deleted not_found referenced other"
  local out
  out="$(aws ecr-public batch-delete-image --region "$REGION" --repository-name "$1" \
    --image-ids "file://$2" --output json)" || return 1
  jq -r '.failures // [] | map(select(.failureCode != "ImageNotFound" and .failureCode != "ImageReferencedByManifestList"))
         | .[] | "    failed \(.imageId.imageDigest // "?"): \(.failureCode) \(.failureReason // "")"' <<<"$out" >&2
  jq -r '[(.imageIds // [] | length),
          (.failures // [] | map(select(.failureCode == "ImageNotFound")) | length),
          (.failures // [] | map(select(.failureCode == "ImageReferencedByManifestList")) | length),
          (.failures // [] | map(select(.failureCode != "ImageNotFound" and .failureCode != "ImageReferencedByManifestList")) | length)]
         | @tsv' <<<"$out"
}

purge_repository() { # repo
  local repo="$1" uri repo_path images="$WORK/$1-images.json" tagged untagged candidates
  local deleted=0 not_found=0 referenced=0 other=0 d n r o batch_no=0

  echo "--- :aws: ECR Public $repo: listing images"
  uri="$(aws ecr-public describe-repositories --region "$REGION" --repository-names "$repo" \
    --query 'repositories[0].repositoryUri' --output text)" || return 1
  repo_path="${uri#"${REGISTRY_HOST}"/}"
  if [ "$repo_path" = "$uri" ] || [ -z "$repo_path" ]; then
    echo "    unexpected repository URI '$uri'" >&2
    return 1
  fi
  aws ecr-public describe-images --region "$REGION" --repository-name "$repo" --output json >"$images" || return 1
  jq -e '.imageDetails | type == "array"' "$images" >/dev/null || { echo "    describe-images returned no imageDetails array" >&2; return 1; }
  jq -e 'has("NextToken") | not' "$images" >/dev/null || { echo "    describe-images listing is truncated (NextToken present)" >&2; return 1; }
  jq -e "$JQ_EPOCH"' [.imageDetails[] | .imagePushedAt | epoch] | length >= 0' "$images" >/dev/null \
    || { echo "    an imagePushedAt value could not be parsed" >&2; return 1; }

  tagged="$(jq '[.imageDetails[] | select(((.imageTags // []) | length) > 0)] | length' "$images")" || return 1
  untagged="$(jq '[.imageDetails[] | select(((.imageTags // []) | length) == 0)] | length' "$images")" || return 1
  echo "    $tagged tagged, $untagged untagged image(s)"
  if [ "$tagged" -eq 0 ]; then
    # Every MockServer repository carries tags; none at all means the listing is wrong.
    echo "    no tagged images found; refusing to purge a repository that would be emptied" >&2
    return 1
  fi

  echo "--- :mag: ECR Public $repo: resolving manifests referenced by tagged and recent images"
  build_keep_set "$repo_path" "$images" || { echo "    keep-set incomplete; NOTHING deleted from $repo" >&2; return 1; }

  # Untagged, outside the keep-set (which already holds every recent image), and an
  # index or a plain image/attestation manifest: an unknown artifact type (an OCI
  # referrer such as a signature bundle) is kept. Indexes first, so their children
  # are no longer referenced when deleted.
  candidates="$WORK/$repo-candidates.jsonl"
  jq -c --rawfile keep "$WORK/keep.txt" --argjson types "$INDEX_TYPES_JSON" --argjson known "$DELETABLE_ARTIFACT_TYPES_JSON" '
    ($keep | split("\n") | map(select(length > 0)) | map({(.): true}) | add // {}) as $k
    | [.imageDetails[]
       | select(((.imageTags // []) | length) == 0)
       | select($k[.imageDigest] | not)
       | {imageDigest,
          idx: (.imageManifestMediaType as $m | $types | index($m) != null),
          known: (.artifactMediaType as $a | $known | index($a) != null)}
       | select(.idx or .known)]
    | sort_by(if .idx then 0 else 1 end)
    | .[] | {imageDigest}' "$images" >"$candidates" || return 1
  echo "    keep-set $(wc -l <"$WORK/keep.txt" | tr -d ' ') digest(s); $(wc -l <"$candidates" | tr -d ' ') untagged image(s) to delete"

  if [ "$DRY_RUN" = "true" ]; then
    SUMMARY+=("$repo: dry run, $(wc -l <"$candidates" | tr -d ' ') would be deleted")
    return 0
  fi

  split -l "$BATCH_SIZE" "$candidates" "$WORK/$repo-batch." || return 1
  for chunk in "$WORK/$repo-batch."*; do
    [ -e "$chunk" ] || continue
    batch_no=$((batch_no + 1))
    jq -s '.' "$chunk" >"$chunk.json" || return 1
    if ! IFS=$'\t' read -r d n r o < <(delete_batch "$repo" "$chunk.json"); then
      echo "    batch $batch_no: batch-delete-image call failed" >&2
      other=$((other + $(wc -l <"$chunk" | tr -d ' ')))
      continue
    fi
    deleted=$((deleted + d)); not_found=$((not_found + n)); referenced=$((referenced + r)); other=$((other + o))
    echo "    batch $batch_no: deleted $d, already gone $n, still referenced $r, failed $o"
  done
  TOTAL_DELETED=$((TOTAL_DELETED + deleted))
  SUMMARY+=("$repo: deleted $deleted, already gone $not_found, still referenced $referenced, failed $other")
  [ "$other" -eq 0 ]
}

for repo in "${REPOSITORIES[@]}"; do
  if ! purge_repository "$repo"; then
    echo "^^^ +++"
    echo ":x: ECR Public $repo: purge failed (see above); continuing with the remaining repositories" >&2
    FAILED_REPOS+=("$repo")
  fi
done

echo "--- :bar_chart: ECR Public untagged purge summary (min age ${MIN_AGE_HOURS}h, dry run ${DRY_RUN})"
for line in ${SUMMARY[@]+"${SUMMARY[@]}"}; do
  echo "    $line"
done
echo "    total deleted: $TOTAL_DELETED"

if [ "${#FAILED_REPOS[@]}" -gt 0 ]; then
  if command -v buildkite-agent >/dev/null 2>&1; then
    buildkite-agent annotate --style error --context ecr-public-purge \
      "ECR Public untagged purge failed for: ${FAILED_REPOS[*]}. Untagged images keep accruing storage cost until it passes; see the step log." || true
  fi
  echo ":x: ECR Public untagged purge failed for: ${FAILED_REPOS[*]}" >&2
  exit 1
fi

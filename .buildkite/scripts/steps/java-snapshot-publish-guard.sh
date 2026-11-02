#!/usr/bin/env bash
set -euo pipefail

# Nightly snapshot publish guard: runs only on the scheduled mockserver-java build. It replaces the
# rest of the build with one no-op step when both the last recorded publish and the revision label
# of each pushed :snapshot image cover this commit: the same commit, or an ancestor with no change
# since to the image inputs, docker/ or the publish scripts. Anything it cannot establish publishes.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"
# shellcheck source=.buildkite/scripts/lib/last-successful-commit.sh
source "$SCRIPT_DIR/../lib/last-successful-commit.sh"
# shellcheck source=.buildkite/scripts/lib/snapshot-image-inputs.sh
source "$SCRIPT_DIR/../lib/snapshot-image-inputs.sh"

REGISTRY="${SNAPSHOT_REGISTRY:-public.ecr.aws}"
REGISTRY_REPO="${SNAPSHOT_REGISTRY_REPO:-t2x9c0i6/mockserver}"
IMAGE_TAGS=(snapshot snapshot-graaljs)
# Changes here alter what a publish produces without touching the image inputs.
PUBLISH_PATHS=(docker/ .buildkite/pipeline-java.yml .buildkite/scripts/steps/java-docker-push-snapshot.sh
  .buildkite/scripts/steps/java-deploy-snapshot.sh .buildkite/scripts/steps/java-publish-snapshot-bundles.sh
  scripts/build-all-bundles.sh scripts/buildkite_quick_build.sh)

publish() { echo "--- :rocket: $1 — publishing ${HEAD_SHA:0:10}"; exit 0; }

# image_revision <tag>: the org.opencontainers.image.revision label of the pushed linux/amd64 image.
image_revision() {
  local accept='application/vnd.oci.image.index.v1+json, application/vnd.docker.distribution.manifest.list.v2+json, application/vnd.oci.image.manifest.v1+json, application/vnd.docker.distribution.manifest.v2+json'
  local base="https://${REGISTRY}/v2/${REGISTRY_REPO}" token manifest digest config
  token="$(curl -fsS --max-time 20 "https://${REGISTRY}/token/?service=${REGISTRY}&scope=repository:${REGISTRY_REPO}:pull" | jq -er '.token')" || return 1
  manifest="$(curl -fsS --max-time 20 -H "Authorization: Bearer ${token}" -H "Accept: ${accept}" "${base}/manifests/$1")" || return 1
  digest="$(jq -r '[.manifests[]? | select(.platform.os == "linux" and .platform.architecture == "amd64")][0].digest // empty' <<<"$manifest")"
  if [ -n "$digest" ]; then
    manifest="$(curl -fsS --max-time 20 -H "Authorization: Bearer ${token}" -H "Accept: ${accept}" "${base}/manifests/${digest}")" || return 1
  fi
  digest="$(jq -er '.config.digest' <<<"$manifest")" || return 1
  config="$(curl -fsSL --max-time 20 -H "Authorization: Bearer ${token}" "${base}/blobs/${digest}")" || return 1
  jq -er '.config.Labels["org.opencontainers.image.revision"] | select(test("^[0-9a-f]{40}$"))' <<<"$config"
}

# covers <commit>: the snapshots built from <commit> are what this commit would publish.
covers() {
  local changes
  snapshot_equivalent "$REPO_ROOT" "$1" "$HEAD_SHA" || return 1
  changes="$(git -C "$REPO_ROOT" diff --name-only "$1" "$HEAD_SHA" -- "${PUBLISH_PATHS[@]}" 2>/dev/null)" || return 1
  [ -z "$changes" ]
}

HEAD_SHA="$(git -C "$REPO_ROOT" rev-parse --verify -q "HEAD^{commit}" 2>/dev/null || true)"
[[ "$HEAD_SHA" =~ ^[0-9a-f]{40}$ ]] || { echo "--- :rocket: cannot resolve the checked-out commit — publishing"; exit 0; }

echo "--- :buildkite: resolving what the last snapshot publish pushed"
LAST="$(last_snapshot_publish_commit)" || publish "no earlier publish recorded"
echo "    recorded publish: ${LAST:0:10}  (HEAD: ${HEAD_SHA:0:10})"
covers "$LAST" || publish "the recorded publish ${LAST:0:10} does not cover HEAD"
for tag in "${IMAGE_TAGS[@]}"; do
  rev="$(image_revision "$tag")" || publish "cannot read the revision label of :${tag}"
  echo "    :${tag} built from ${rev:0:10}"
  covers "$rev" || publish ":${tag} (built from ${rev:0:10}) does not cover HEAD"
done

MSG=":zzz: **Nightly snapshot publish skipped** — the snapshots published from \`${LAST:0:10}\` already cover \`${HEAD_SHA:0:10}\` (no change since to the image inputs, \`docker/\` or the publish scripts). Start a build with \`PUBLISH_SNAPSHOT=true\` to publish anyway."
echo "--- :zzz: nothing to publish — replacing the rest of this build with a no-op step"
if ! command -v buildkite-agent >/dev/null 2>&1; then
  echo "(local run) buildkite-agent unavailable — would skip the rest of the build"
  exit 0
fi
SKIP_YAML='steps:
  - label: ":zzz: nightly snapshot publish skipped (already published)"
    command: "true"
    timeout_in_minutes: 5
    agents:
      queue: "trigger"
'
if ! buildkite-agent pipeline upload --replace <<<"$SKIP_YAML"; then
  publish "could not replace the rest of the build"
fi
printf '%s\n' "$MSG" | buildkite-agent annotate --style info --context snapshot-publish || true

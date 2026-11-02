#!/usr/bin/env bash
set -euo pipefail

# Records the commit this build published (meta-data snapshot_published_commit), so the next
# nightly guard (java-snapshot-publish-guard.sh) can skip when nothing has changed since.
# Runs after the Sonatype deploy, the image push and the (soft_fail) bundles step. A failed
# bundles step records nothing, so the next nightly publishes again rather than skipping.

COMMIT="$(git rev-parse --verify -q "${BUILDKITE_COMMIT:-HEAD}^{commit}" 2>/dev/null || true)"
if [[ ! "$COMMIT" =~ ^[0-9a-f]{40}$ ]]; then
  echo "Error: cannot resolve the published commit from BUILDKITE_COMMIT='${BUILDKITE_COMMIT:-}'" >&2
  exit 1
fi
if [ "$(buildkite-agent meta-data get snapshot_bundles_done --default '' 2>/dev/null || true)" != "true" ]; then
  echo "--- :warning: the snapshot bundles were not published — not recording, so the next nightly publishes again"
  printf '%s\n' ":warning: Snapshot bundles were not published for \`${COMMIT:0:10}\`; the next nightly publish will retry everything." \
    | buildkite-agent annotate --style warning --context snapshot-publish-record || true
  exit 0
fi
buildkite-agent meta-data set snapshot_published_commit "$COMMIT"
echo "--- :memo: recorded snapshot_published_commit=${COMMIT}"

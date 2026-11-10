#!/usr/bin/env bash
# One-shot: push every packaged Helm chart in helm/charts/ to GHCR as OCI.
#
# Run once to back-populate the OCI registry with historical chart versions
# (the regular release pipeline publishes each new version via
# components/helm.sh). Idempotent — helm push against an existing tag
# overwrites it with the same bytes, so re-running is safe.
#
# Usage:
#   scripts/release/backfill-helm-oci.sh --dry-run     # list what would push
#   scripts/release/backfill-helm-oci.sh --execute     # actually push
#
# Requires AWS credentials with read access to the secrets
# mockserver-release/ghcr-token (keys: username, token) and
# mockserver-release/cosign-key (keys: key, password). The token needs
# write:packages scope on the mock-server GitHub organization. Every pushed
# chart is cosign-signed; a missing or broken key stops before any push.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
source "$SCRIPT_DIR/_lib.sh"

DRY_RUN="${DRY_RUN:-true}"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_cmd docker

CHARTS_DIR="$REPO_ROOT/helm/charts"
if [[ ! -d "$CHARTS_DIR" ]]; then
  log_error "$CHARTS_DIR does not exist"
  exit 1
fi

# Stable, version-sorted enumeration so the log reads in release order.
# Use a portable read loop (bash 3.2 lacks mapfile) so this can be run from
# a maintainer laptop, not only the CI agents.
TGZS=()
while IFS= read -r f; do TGZS+=("$f"); done \
  < <(find "$CHARTS_DIR" -maxdepth 1 -name 'mockserver-*.tgz' | sort -V)
if [[ ${#TGZS[@]} -eq 0 ]]; then
  log_error "No mockserver-*.tgz files in $CHARTS_DIR"
  exit 1
fi

log_step "Backfill ${#TGZS[@]} chart(s) to oci://ghcr.io/mock-server/charts (dry-run=$DRY_RUN)"
for tgz in "${TGZS[@]}"; do
  log_info "  $(basename "$tgz")"
done

if is_dry_run; then
  log_dry "skip: cosign key check, helm registry login + helm push + cosign sign for ${#TGZS[@]} chart(s)"
  exit 0
fi

require_cosign_key "Helm chart backfill"

GHCR_USERNAME=$(load_secret "mockserver-release/ghcr-token" "username")
GHCR_TOKEN=$(load_secret "mockserver-release/ghcr-token" "token")

# Build a space-separated relative-path list to iterate inside the container.
REL_PATHS=""
for tgz in "${TGZS[@]}"; do
  REL_PATHS+=" helm/charts/$(basename "$tgz")"
done

in_docker "$HELM_IMAGE" --entrypoint sh -w /build \
  -e "GHCR_USERNAME=$GHCR_USERNAME" \
  --secret-env "GHCR_TOKEN=$GHCR_TOKEN" \
  -e "REL_PATHS=$REL_PATHS" \
  -- -ec '
    set +x
    printf "%s" "$GHCR_TOKEN" | helm registry login ghcr.io \
      --username "$GHCR_USERNAME" --password-stdin
    for path in $REL_PATHS; do
      echo "--- pushing $path"
      helm push "$path" oci://ghcr.io/mock-server/charts
    done
  '

VERSIONS=()
for tgz in "${TGZS[@]}"; do
  v="$(basename "$tgz" .tgz)"; VERSIONS+=("${v#mockserver-}")
done
cosign_sign_ghcr_charts "${VERSIONS[@]}" \
  || { log_error "cosign signing failed — re-run to sign the pushed charts"; exit 1; }

log_info "Backfill complete"

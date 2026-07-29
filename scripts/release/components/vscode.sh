#!/usr/bin/env bash
# Publish MockServer VS Code extension to:
#   1. VS Code Marketplace (via vsce)
#   2. Open VSX Registry (via ovsx)
#
# Dry-run: bump version (host edit) + build + package (.vsix), skip publish.
# HARD: a real build/package failure aborts the step. Each registry publish
# HARD-fails on its OWN real error. The build, package and publishes run inside
# a pinned node container (no host `npm`/`vsce`/`ovsx` required) — this replaced
# a host `command -v npm` probe that silently `exit 0`d on the release-queue
# agent (Java/Maven/Docker only), so the extension never actually published.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
source "$SCRIPT_DIR/_lib.sh"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=true; shift ;;
    --execute) DRY_RUN=false; shift ;;
    -h|--help) echo "Usage: $0 [--dry-run|--execute]"; exit 0 ;;
    *) log_error "Unknown arg: $1"; exit 2 ;;
  esac
done

require_release_inputs
skip_unless_release_type "vscode" full,post-maven

log_step "Publish VS Code extension $RELEASE_VERSION (dry-run=$DRY_RUN)"

# Publish the code that was released, not the commit the build was created from.
# Every other publishing component does this; vscode was the sole exception, so it
# shipped whatever was on the build's commit with the version sed'd in, missing
# anything the release's own Prepare / Update Version References commits added.
# That also made the step unretryable: a fix landed on master could not be picked
# up, which is exactly the position build #69 was left in when the
# @types/vscode-vs-engines.vscode mismatch failed the publish.
sync_to_origin_master

COMPONENT_DIR="$REPO_ROOT/mockserver-vscode"
PKG_JSON="$COMPONENT_DIR/package.json"

# The in-container `npm ci` below writes a root-owned node_modules into the
# bind-mounted workspace that the next job's git checkout cannot clean (see
# clean_workspace_node_modules). The EXIT trap fires on every exit path (build
# failure, dry-run early-exit, or publish success) and runs AFTER the build,
# package and vsce/ovsx publish steps that need node_modules, so it never races
# them.
trap 'clean_workspace_node_modules mockserver-vscode' EXIT

# Bump version in package.json. Host edit — package.json is on the bind-mounted
# repo, so the in-container build/package/publish sees the bumped version. Done
# without npm/git (sed) so no host toolchain is required.
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in package.json"
  sed -i.bak -E "s/(\"version\"[[:space:]]*:[[:space:]]*\")[^\"]*(\")/\1${RELEASE_VERSION}\2/" "$PKG_JSON"
  rm -f "$PKG_JSON.bak"
fi

# Build + package the .vsix in a pinned node container — no host toolchain
# required. vsce/ovsx are not in the base node image, so install them in-container.
# HARD: a build or package failure aborts the step (set -e propagates). Retry to
# ride out transient npm registry/network blips during `npm ci` / global install.
log_info "Building + packaging .vsix in $NODE_IMAGE"
retry 3 5 -- in_docker "$NODE_IMAGE" -w /build/mockserver-vscode -- \
  sh -c 'npm i -g @vscode/vsce ovsx && npm ci && npm run compile && vsce package --no-git-tag-version'

if is_dry_run; then
  log_dry "skip: vsce publish + ovsx publish"
  log_info "Built .vsix:"
  ls -la "$COMPONENT_DIR"/*.vsix 2>/dev/null || true
  exit 0
fi

# --- VS Code Marketplace (vsce) ---
# The vsce PAT EXISTS (mockserver-release/vsce, key: token). A genuinely-missing
# secret is a hard failure now. The token is passed via -e (redacted in the
# logged command) and dereferenced INSIDE the single-quoted sh -c body so the
# literal token never lands in the logged command args (run-in-docker does NOT
# redact the command body). HARD-fail on a real publish error, with retry for
# transient blips. Runs independently of the Open VSX publish below — each
# registry hard-fails on its own error.
VSCE_PAT=$(load_secret "mockserver-release/vsce" "token")
if [[ -z "$VSCE_PAT" || "$VSCE_PAT" == "null" ]]; then
  log_error "mockserver-release/vsce secret missing/empty — cannot publish to VS Code Marketplace"
  exit 1
fi
# Idempotent: re-running a release (or a prior build that already shipped the
# extension) makes `vsce publish` fail with "… vX.Y.Z already exists." — that is
# the desired end state, so run_idempotent treats it as success while still
# HARD-failing on any other publish error.
log_info "Publishing to VS Code Marketplace from $NODE_IMAGE"
retry 3 5 -- run_idempotent 'already exists|already published' -- in_docker "$NODE_IMAGE" \
  -e "VSCE_PAT=$VSCE_PAT" \
  -w /build/mockserver-vscode -- \
  sh -c 'npm i -g @vscode/vsce && vsce publish -p "$VSCE_PAT"'

# --- Open VSX (ovsx) ---
# The ovsx PAT EXISTS (mockserver-release/ovsx, key: token). Same hard-fail +
# in-container-deref-of-secret pattern as above. Hard-fails on its own real error.
OVSX_PAT=$(load_secret "mockserver-release/ovsx" "token")
if [[ -z "$OVSX_PAT" || "$OVSX_PAT" == "null" ]]; then
  log_error "mockserver-release/ovsx secret missing/empty — cannot publish to Open VSX"
  exit 1
fi
# Idempotent for the same reason as the Marketplace publish above: ovsx reports
# "already exists" when the version is already on Open VSX — treat as success.
log_info "Publishing to Open VSX from $NODE_IMAGE"
retry 3 5 -- run_idempotent 'already exists|already published' -- in_docker "$NODE_IMAGE" \
  -e "OVSX_PAT=$OVSX_PAT" \
  -w /build/mockserver-vscode -- \
  sh -c 'npm i -g ovsx && ovsx publish -p "$OVSX_PAT"'

# Commit version bump.
git_commit_and_push "release: publish mockserver-vscode $RELEASE_VERSION" \
  "$COMPONENT_DIR/package.json" "$COMPONENT_DIR/package-lock.json" || \
  log_info ":warning: could not commit version bump (non-fatal — publish already succeeded)"

log_info "VS Code extension publish complete"

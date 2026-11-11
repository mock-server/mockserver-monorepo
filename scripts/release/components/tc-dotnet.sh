#!/usr/bin/env bash
# Publish the MockServer.Testcontainers NuGet package (assembly/namespace remain
# Testcontainers.MockServer; the package id differs to avoid NuGet's reserved
# `Testcontainers.*` prefix — see the csproj).
#
# Dry-run: build + pack in-container, skip push.
# HARD: a failed build/pack/push aborts the release. The .NET toolchain runs
# inside the pinned $DOTNET_IMAGE container (the release agent has no host
# `dotnet`) — it used to probe `command -v dotnet`, find nothing, and silently
# `exit 0`, so the Testcontainers module never published despite a green build.

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
skip_unless_release_type "tc-dotnet" full,post-maven

log_step "Publish MockServer.Testcontainers $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-testcontainers/dotnet"
BUILD_PROPS="$COMPONENT_DIR/Directory.Build.props"
# Module path inside the container (repo is mounted at /build).
MODULE_DIR="/build/mockserver-testcontainers/dotnet"
CSPROJ_REL="src/Testcontainers.MockServer/Testcontainers.MockServer.csproj"

# Bump version in Directory.Build.props (host-side string edit; no dotnet needed).
if ! is_dry_run; then
  log_info "Updating MockServerVersion to $RELEASE_VERSION in Directory.Build.props"
  sed -i.bak "s|<MockServerVersion>.*</MockServerVersion>|<MockServerVersion>${RELEASE_VERSION}</MockServerVersion>|" "$BUILD_PROPS"
  rm -f "$BUILD_PROPS.bak"
fi

# Pre-pull the SDK image ONCE with generous backoff — MCR rate-limits anonymous
# pulls with `toomanyrequests` (failed build #51); pulling up front (5x, 30s base)
# outlasts the transient limit and warms the cache so the in_docker runs don't
# re-pull. (If MCR limits persist, mirror the SDK image to our own registry.)
log_info "Pre-pulling $DOTNET_IMAGE (MCR rate-limit resilience)"
retry 5 30 -- docker pull "$DOTNET_IMAGE"

# Restore + build + pack in the pinned .NET SDK container. HARD-fail on error.
# Scope every command to the src csproj — NOT the .sln / module dir. The tests
# project targets net10.0, which the pinned SDK 8.0 image cannot restore, so a
# solution-wide `dotnet restore` aborts; the publishable library targets net8.0
# and only the src csproj is ever packed.
# All three commands MUST run in the SAME `docker run`: restore populates the
# container-local NuGet global-packages folder (~/.nuget/packages), which is
# discarded when the `--rm` container exits. A previous split — `restore` in one
# in_docker, `build`/`pack --no-restore`/`--no-build` in separate containers —
# left the later containers with only obj/project.assets.json on the bind-mounted
# /build but none of the actual package files, so build aborted with
# `NETSDK1064: Package … was not found. It might have been deleted since NuGet
# restore.` Combining them keeps the restored packages in scope. `restore` is the
# network-touching step, so the whole command is wrapped in `retry` to ride out
# transient NuGet outages; a repeated build/pack is deterministic and harmless.
# CSPROJ_REL is passed as $0 to the sh -c body (no secrets, just a path).
# --locked-mode fails the restore unless it matches the committed
# packages.lock.json exactly; the nuspec still carries MockServerClient [7.1.0,).
log_info "Restoring + building + packing in $DOTNET_IMAGE"
retry 3 5 -- in_docker "$DOTNET_IMAGE" -w "$MODULE_DIR" -- \
  sh -c 'dotnet restore "$0" --locked-mode && dotnet build "$0" -c Release --no-restore && dotnet pack "$0" -c Release --no-build -o ./artifacts' "$CSPROJ_REL"

if is_dry_run; then
  log_dry "skip: dotnet nuget push to NuGet.org"
  log_info "Built artifacts:"
  ls -la "$COMPONENT_DIR/artifacts/" 2>/dev/null || true
  exit 0
fi

# Idempotent: skip push if this version is already on NuGet. A non-200 just
# means "not yet published" — proceed. The flat-container URL uses the LOWERCASED
# PackageId (mockserver.testcontainers) — the package id is MockServer.Testcontainers,
# NOT Testcontainers.MockServer, because the latter sits under NuGet's reserved
# `Testcontainers.*` prefix (build #53 403). See the csproj for the full rationale.
http_code=$(curl -s -o /dev/null -w "%{http_code}" \
  "https://api.nuget.org/v3-flatcontainer/mockserver.testcontainers/${RELEASE_VERSION}/mockserver.testcontainers.${RELEASE_VERSION}.nupkg" 2>/dev/null || echo "000")
if [[ "$http_code" == "200" ]]; then
  log_info "MockServer.Testcontainers $RELEASE_VERSION already on NuGet — skipping push"
  exit 0
fi

# The NuGet API key MUST exist — a missing secret is a hard failure now, not a
# silent skip. load_secret aborts (set -e) if the secret can't be read.
NUGET_API_KEY=$(load_secret "mockserver-release/nuget" "api_key")
if [[ -z "$NUGET_API_KEY" || "$NUGET_API_KEY" == "null" ]]; then
  log_error "mockserver-release/nuget api_key is empty — cannot publish MockServer.Testcontainers"
  exit 1
fi

# Push from inside the container. The API key is passed ONLY via in_docker
# --secret-env and dereferenced INSIDE the container by a single-quoted `sh -c`
# body, so the literal key never lands in COMMAND_ARGS (which is NOT redacted).
# dotnet has no env-var alternative to --api-key, so the key is still on the
# in-container dotnet argv. Non-secret nupkg path passed via -e.
# HARD-fail on push error, with retry for transient blips; --skip-duplicate
# makes retry idempotent.
# `dotnet pack` names the nupkg after the PackageId, so the file is
# MockServer.Testcontainers.<ver>.nupkg (the assembly is still
# Testcontainers.MockServer; only the package id differs).
log_info "Pushing MockServer.Testcontainers $RELEASE_VERSION to NuGet.org"
retry 3 5 -- in_docker "$DOTNET_IMAGE" -w "$MODULE_DIR" \
  -e "NUPKG=./artifacts/MockServer.Testcontainers.${RELEASE_VERSION}.nupkg" \
  --secret-env "NUGET_API_KEY=$NUGET_API_KEY" -- \
  sh -c 'dotnet nuget push "$NUPKG" --api-key "$NUGET_API_KEY" --source https://api.nuget.org/v3/index.json --skip-duplicate'

# NuGet indexing is eventually-consistent: a freshly-pushed package can take
# minutes to appear on api.nuget.org. The push above is the real publish (HARD);
# this visibility check is best-effort — retry, then tolerate with a warning.
log_info "Verifying NuGet visibility (eventually-consistent)"
if ! retry 3 10 -- bash -c "[[ \"\$(curl -s -o /dev/null -w '%{http_code}' \
     'https://api.nuget.org/v3-flatcontainer/mockserver.testcontainers/${RELEASE_VERSION}/mockserver.testcontainers.${RELEASE_VERSION}.nupkg')\" == \"200\" ]]"; then
  log_info ":warning: MockServer.Testcontainers $RELEASE_VERSION not yet visible on NuGet — non-fatal (push succeeded; indexing lags)"
fi

# Commit version bump (host-side git; never blocks the publish).
git_commit_and_push "release: publish MockServer.Testcontainers $RELEASE_VERSION to NuGet" \
  "$BUILD_PROPS" || \
  log_info ":warning: could not commit version bump (non-fatal)"

log_info "MockServer.Testcontainers publish complete"

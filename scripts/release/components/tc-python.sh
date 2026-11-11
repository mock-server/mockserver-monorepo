#!/usr/bin/env bash
# Publish testcontainers-mockserver to PyPI.
#
# HARD: a real build/check/upload failure aborts the release. This used to be
# soft (every failure path logged a WARNING and exited 0), which is why build
# #50 went green despite a `403 Forbidden` from PyPI (token scope). The token
# scope is now fixed; a genuine upload error must redden the release.
#
# Build + twine check + twine upload all run in the pinned $PYTHON_IMAGE via
# in_docker (mirroring pypi.sh) — no host python/twine required. The PyPI token
# is passed as TWINE_PASSWORD via in_docker --secret-env, never in the command body.
#
# Dry-run: build + twine check, skip upload.

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

require_cmd docker
require_cmd curl
require_release_inputs
skip_unless_release_type "tc-python" full,post-maven

log_step "Publish testcontainers-mockserver (Python) $RELEASE_VERSION (dry-run=$DRY_RUN)"

COMPONENT_DIR="$REPO_ROOT/mockserver-testcontainers/python"
PYPROJECT="$COMPONENT_DIR/pyproject.toml"

# Bump version in pyproject.toml (real run commits this below; dry-run bumps it
# in-place so the build/check exercises the real version, restoring on exit).
if ! is_dry_run; then
  log_info "Updating version to $RELEASE_VERSION in pyproject.toml"
  sed -i.bak "s/^version = \".*\"/version = \"${RELEASE_VERSION}\"/" "$PYPROJECT"
  rm -f "$PYPROJECT.bak"
else
  CURRENT_TOML_VERSION=$(grep -E '^version\s*=' "$PYPROJECT" | head -1 | sed 's/.*= *"\(.*\)".*/\1/')
  if [[ "$CURRENT_TOML_VERSION" != "$RELEASE_VERSION" ]]; then
    mkdir -p "$REPO_ROOT/.tmp"
    cp "$PYPROJECT" "$REPO_ROOT/.tmp/tc-python-pyproject.toml.bak"
    # shellcheck disable=SC2064  # expand the path now, not at trap-fire time
    trap "cp '$REPO_ROOT/.tmp/tc-python-pyproject.toml.bak' '$PYPROJECT' 2>/dev/null || true" EXIT
    sed -i.bak "s/^version = \".*\"/version = \"${RELEASE_VERSION}\"/" "$PYPROJECT"
    rm -f "$PYPROJECT.bak"
    log_info "dry-run: bumped pyproject.toml version to $RELEASE_VERSION in-place (not committed)"
  fi
fi

# Fail-fast version guard: refuse to build/publish the wrong version.
VERSION=$(grep -E '^version\s*=' "$PYPROJECT" | head -1 | sed 's/.*= *"\(.*\)".*/\1/')
if [[ "$VERSION" != "$RELEASE_VERSION" ]]; then
  log_error "pyproject.toml version ($VERSION) does not match RELEASE_VERSION ($RELEASE_VERSION) — refusing to publish wrong version"
  exit 1
fi

# Idempotent: an already-published version means a prior run did this.
if ! is_dry_run; then
  log_info "Checking PyPI for existing version"
  http_code=$(curl -s -o /dev/null -w "%{http_code}" \
    "https://pypi.org/pypi/testcontainers-mockserver/$RELEASE_VERSION/json")
  case "$http_code" in
    200) log_info "testcontainers-mockserver $RELEASE_VERSION already on PyPI — skipping"; exit 0 ;;
    404) ;;
    *)   log_error "PyPI returned HTTP $http_code"; exit 1 ;;
  esac
fi

rm -rf "$COMPONENT_DIR/dist" "$COMPONENT_DIR/build" "$COMPONENT_DIR"/*.egg-info 2>/dev/null || true

# build, twine and the setuptools/wheel backend come from the hash-pinned
# requirements-publish.txt, and --no-isolation stops `build` downloading an
# unpinned backend; the upload container, which holds the PyPI token, installs
# from the same file.
log_info "Build + validate package (Python in Docker)"
in_docker "$PYTHON_IMAGE" \
  -w /build/mockserver-testcontainers/python \
  -- bash -ec '
    pip install --quiet --no-cache-dir --require-hashes --only-binary=:all: --no-deps -r /build/scripts/release/requirements-publish.txt
    python -m build --no-isolation .
    python -m twine check dist/*
  '

if is_dry_run; then
  log_dry "skip: twine upload to PyPI"
  log_info "Built artifacts: $COMPONENT_DIR/dist/"
  ls -la "$COMPONENT_DIR/dist/" 2>/dev/null || true
  exit 0
fi

# HARD: real upload errors abort. retry rides out transient registry/network
# blips; --skip-existing keeps retries idempotent (a file uploaded by a prior
# attempt is skipped rather than erroring the whole upload).
log_info "Uploading to PyPI"
PYPI_TOKEN=$(load_secret "mockserver-build/pypi" "token")
retry 3 5 -- in_docker "$PYTHON_IMAGE" \
  -w /build/mockserver-testcontainers/python \
  -e "TWINE_USERNAME=__token__" \
  --secret-env "TWINE_PASSWORD=$PYPI_TOKEN" \
  -- bash -ec '
    pip install --quiet --no-cache-dir --require-hashes --only-binary=:all: --no-deps -r /build/scripts/release/requirements-publish.txt
    set +x
    python -m twine upload --skip-existing dist/*
  '

# Commit version bump (best-effort — the publish itself is already done).
git_commit_and_push "release: publish testcontainers-mockserver $RELEASE_VERSION to PyPI" \
  "$PYPROJECT" || \
  log_info "WARNING: could not commit version bump (non-fatal)"

log_info "testcontainers-mockserver (Python) publish complete"

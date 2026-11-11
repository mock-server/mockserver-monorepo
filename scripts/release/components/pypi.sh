#!/usr/bin/env bash
# Publish mockserver-client to PyPI.
#
# Dry-run: build + twine check, skip twine upload.

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
skip_unless_release_type "pypi" full,post-maven

log_step "Publish PyPI $RELEASE_VERSION (dry-run=$DRY_RUN)"
sync_to_origin_master

PYTHON_DIR="$REPO_ROOT/mockserver-client-python"
VERSION=$(grep -E '^version\s*=' "$PYTHON_DIR/pyproject.toml" | head -1 | sed 's/.*= *"\(.*\)".*/\1/')
[[ -n "$VERSION" ]] || { log_error "could not parse version from pyproject.toml"; exit 1; }
log_info "Package version: $VERSION"

# In dry-run, update-version-references (which bumps pyproject.toml) is skipped
# and its bump would never reach this step's fresh checkout anyway, so the file
# still holds the previous version. Bump it in-place to RELEASE_VERSION so the
# dry-run builds/validates the real version; restore on exit (dry-run never commits).
PYPROJECT_FILE="$PYTHON_DIR/pyproject.toml"
if is_dry_run && [[ "$VERSION" != "$RELEASE_VERSION" ]]; then
  mkdir -p "$REPO_ROOT/.tmp"
  cp "$PYPROJECT_FILE" "$REPO_ROOT/.tmp/pyproject.toml.bak"
  # shellcheck disable=SC2064  # expand the path now, not at trap-fire time
  trap "cp '$REPO_ROOT/.tmp/pyproject.toml.bak' '$PYPROJECT_FILE' 2>/dev/null || true" EXIT
  _newtoml="$REPO_ROOT/.tmp/pyproject.toml.new"
  sed "s/^version = \".*\"/version = \"$RELEASE_VERSION\"/" "$PYPROJECT_FILE" > "$_newtoml" && mv "$_newtoml" "$PYPROJECT_FILE"
  grep -qE "^version = \"$RELEASE_VERSION\"" "$PYPROJECT_FILE" \
    || { log_error "dry-run: failed to bump pyproject.toml version (format changed?)"; exit 1; }
  VERSION="$RELEASE_VERSION"
  log_info "dry-run: bumped pyproject.toml version to $RELEASE_VERSION in-place (not committed)"
fi

# Fail-fast version guard. Must run BEFORE the "already on PyPI" idempotency
# check, otherwise a stale pyproject.toml (e.g. prepare.sh didn't bump it)
# would silently skip — masking the bug behind an "already published"
# message. The idempotency check still preserves re-runnability for the
# happy path where the source file IS at $RELEASE_VERSION.
if [[ "$VERSION" != "$RELEASE_VERSION" ]]; then
  log_error "pyproject.toml version ($VERSION) does not match RELEASE_VERSION ($RELEASE_VERSION) — refusing to publish wrong version"
  exit 1
fi

if ! is_dry_run; then
  log_info "Checking PyPI for existing version"
  http_code=$(curl -s -o /dev/null -w "%{http_code}" "https://pypi.org/pypi/mockserver-client/$VERSION/json")
  case "$http_code" in
    # Idempotent: an already-published version means a prior run did this.
    200) log_info "mockserver-client $VERSION already on PyPI - skipping"; exit 0 ;;
    404) ;;
    *)   log_error "PyPI returned HTTP $http_code"; exit 1 ;;
  esac
fi

rm -rf "$PYTHON_DIR/dist" "$PYTHON_DIR/build" "$PYTHON_DIR"/*.egg-info 2>/dev/null || true

# build, twine and the setuptools/wheel backend come from the hash-pinned
# requirements-publish.txt, and --no-isolation stops `build` downloading an
# unpinned backend; the upload container, which holds the PyPI token, installs
# from the same file.
log_info "Build + validate package (Python in Docker)"
in_docker "$PYTHON_IMAGE" \
  -w /build/mockserver-client-python \
  -- bash -ec '
    pip install --quiet --no-cache-dir --require-hashes --only-binary=:all: --no-deps -r /build/scripts/release/requirements-publish.txt
    python -m build --no-isolation .
    python -m twine check dist/*
  '

if is_dry_run; then
  log_dry "skip: twine upload to PyPI"
  log_info "Built artifacts: $PYTHON_DIR/dist/"
  ls -la "$PYTHON_DIR/dist/" 2>/dev/null || true
else
  log_info "Uploading to PyPI"
  PYPI_TOKEN=$(load_secret "mockserver-build/pypi" "token")
  in_docker "$PYTHON_IMAGE" \
    -w /build/mockserver-client-python \
    -e "TWINE_USERNAME=__token__" \
    --secret-env "TWINE_PASSWORD=$PYPI_TOKEN" \
    -- bash -ec '
      pip install --quiet --no-cache-dir --require-hashes --only-binary=:all: --no-deps -r /build/scripts/release/requirements-publish.txt
      set +x
      python -m twine upload dist/*
    '
fi

log_info "PyPI publish complete"

#!/usr/bin/env bash
#
# Legacy local-publish convenience wrapper. The canonical CI release path is
# `scripts/release/components/pypi.sh` which sources `_lib.sh` for shared
# release-context (dry-run handling, release-input validation, secret
# fetching). This wrapper is kept for ad-hoc local manual publishes; if you
# change `_lib.sh`'s `load_secret()` security discipline (e.g. xtrace
# handling, IMDSv2 token usage) MIRROR THE CHANGE in the inline
# `load_secret()` below — they must stay in sync. See audit finding from
# 2026-05-27 re-audit.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
PYTHON_DIR="$REPO_ROOT/mockserver-client-python"
SECRET_ID="mockserver-build/pypi"
REGION="eu-west-2"

for cmd in jq python3 aws curl; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "Missing required command: $cmd" >&2; exit 1; }
done
python3 -m build --help >/dev/null 2>&1 || { echo "Missing required Python package: build (pip install build)" >&2; exit 1; }
python3 -m twine --version >/dev/null 2>&1 || { echo "Missing required Python package: twine (pip install twine)" >&2; exit 1; }

is_ci() { [[ -n "${BUILDKITE:-}" ]]; }

load_secret() {
  local secret_id="$1" key="$2"
  local xtrace_state
  xtrace_state=$(shopt -po xtrace 2>/dev/null || true)
  set +x
  local json
  if is_ci; then
    json=$(aws secretsmanager get-secret-value \
      --secret-id "$secret_id" \
      --region "$REGION" \
      --query SecretString --output text)
  else
    json=$(aws secretsmanager get-secret-value \
      --secret-id "$secret_id" \
      --region "$REGION" \
      --profile "${AWS_PROFILE:-mockserver-build}" \
      --query SecretString --output text)
  fi
  echo "$json" | jq -r ".$key"
  eval "$xtrace_state"
}

echo "--- Reading version from pyproject.toml"
VERSION=$(grep -E '^version\s*=' "$PYTHON_DIR/pyproject.toml" | head -1 | sed 's/.*= *"\(.*\)".*/\1/')
echo "Version: $VERSION"

echo "--- Checking if version already exists on PyPI"
http_code=$(curl -s -o /dev/null -w "%{http_code}" "https://pypi.org/pypi/mockserver-client/$VERSION/json")
case "$http_code" in
  200) echo "ERROR: Version $VERSION already exists on PyPI" >&2; exit 1 ;;
  404) ;;
  *)   echo "ERROR: PyPI returned HTTP $http_code while checking version" >&2; exit 1 ;;
esac

echo "--- Cleaning previous builds"
rm -rf "$PYTHON_DIR/dist" "$PYTHON_DIR/build" "$PYTHON_DIR"/*.egg-info

echo "--- Building Python package"
python3 -m build "$PYTHON_DIR"

echo "--- Verifying package"
python3 -m twine check "$PYTHON_DIR/dist/"*

# PEP 561: the py.typed marker must be INSIDE the built artefacts, not merely present in the
# source tree. setuptools ships non-.py files only when they are declared as package-data, so
# dropping that one line in pyproject.toml would silently publish a package whose types mypy
# ignores entirely ("module is installed, but missing library stubs or py.typed marker") -
# exactly the bug reported in issue #2680. twine check does not look at this. Fails the release.
echo "--- Verifying py.typed ships in the artefacts (PEP 561)"
for _artefact in "$PYTHON_DIR/dist/"*.whl; do
  python3 -c "import sys,zipfile;n=[x for x in zipfile.ZipFile(sys.argv[1]).namelist() if x.endswith('py.typed')];sys.exit(0 if n else 1)" "$_artefact" \
    || { echo "ERROR: py.typed missing from $(basename "$_artefact") - check [tool.setuptools.package-data] in pyproject.toml" >&2; exit 1; }
done
for _artefact in "$PYTHON_DIR/dist/"*.tar.gz; do
  python3 -c "import sys,tarfile;n=[x for x in tarfile.open(sys.argv[1]).getnames() if x.endswith('py.typed')];sys.exit(0 if n else 1)" "$_artefact" \
    || { echo "ERROR: py.typed missing from $(basename "$_artefact") - check [tool.setuptools.package-data] in pyproject.toml" >&2; exit 1; }
done

echo "--- Fetching PyPI token from Secrets Manager"
PYPI_TOKEN=$(load_secret "$SECRET_ID" "token")

echo "--- Uploading to PyPI"
(
  set +x
  TWINE_USERNAME="__token__" \
  TWINE_PASSWORD="$PYPI_TOKEN" \
  python3 -m twine upload "$PYTHON_DIR/dist/"*
)

echo "--- Successfully published mockserver-client $VERSION to PyPI"
echo "    https://pypi.org/project/mockserver-client/$VERSION/"

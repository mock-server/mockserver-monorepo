#!/usr/bin/env bash
set -euo pipefail

# Regenerates the Postman + Bruno API collections from the OpenAPI spec and fails
# if the committed collections are out of date. The OpenAPI spec is the single
# source of truth (scripts/collections/generate_collections.py) — this gate keeps
# examples/postman/** and examples/bruno/** in lock-step with it, so a spec change
# that forgets to regenerate cannot merge.
#
# The drift check is git-independent (plain `diff -r` against a snapshot taken
# before regeneration) so it works the same whether CI checks out a full clone or
# a git worktree (whose .git metadata lives outside the mounted directory).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i python:3.12 \
  -- bash -c '
    set -eu
    pip install --quiet --disable-pip-version-check \
      --require-hashes --only-binary=:all: --no-deps \
      -r .buildkite/scripts/steps/requirements-collections.txt >/dev/null

    snapshot=$(mktemp -d)
    trap "rm -rf \"$snapshot\"" EXIT
    cp -a examples/postman "$snapshot/postman"
    cp -a examples/bruno "$snapshot/bruno"

    echo "=== regenerating collections from the OpenAPI spec ==="
    python3 scripts/collections/generate_collections.py

    echo ""
    echo "=== checking the committed collections match the regenerated output ==="
    drift=0
    diff -r "$snapshot/postman" examples/postman || drift=1
    diff -r "$snapshot/bruno" examples/bruno || drift=1
    if [ "$drift" -ne 0 ]; then
      echo ""
      echo "FAIL: generated Postman/Bruno collections are out of date (see diff above)."
      echo "Run: python3 scripts/collections/generate_collections.py  and commit the result."
      exit 1
    fi

    echo "PASSED: collections are in sync with the OpenAPI spec"
  '

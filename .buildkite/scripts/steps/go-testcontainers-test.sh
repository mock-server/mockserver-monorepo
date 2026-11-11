#!/usr/bin/env bash
# Run the mockserver-testcontainers Go suite against a real MockServer container.
#
# Fails closed: the integration tests t.Skip when Docker is unusable, which is
# right off-CI but reads as green here. TestIntegration_RunAndURL prints
# GO_TESTCONTAINERS_STATUS_OK only after a real container answered
# PUT /mockserver/status with 200, so the step requires that marker.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i golang:1.26 \
  -w /build/mockserver-testcontainers/go \
  --docker-socket \
  -- bash -ec '
    # Ryuk disabled: its control socket is unreliable across the bridge (see the python
    # step), but a killed run may orphan its container until the agent scales in.
    export TESTCONTAINERS_RYUK_DISABLED=true

    go vet ./...

    # set +e so the output is always echoed and the marker guard always runs.
    set +e
    out="$(go test ./... -v -count=1 -timeout 300s 2>&1)"
    rc=$?
    set -e
    echo "$out"

    if [ "$rc" -ne 0 ]; then
      echo "+++ :bangbang: go test exited ${rc} — failing" >&2
      exit "$rc"
    fi
    if ! grep -q "GO_TESTCONTAINERS_STATUS_OK" <<<"$out"; then
      echo "+++ :bangbang: go container-start test did not emit its status marker — no real container was exercised, failing closed" >&2
      exit 1
    fi
  '

#!/usr/bin/env bash
# Run the mockserver-testcontainers .NET suite against a real MockServer container.
#
# Fails closed: the integration tests Skip.IfNot when Docker is unusable, which is
# right off-CI but reads as green here. ContainerStartsAndStatusEndpointResponds
# writes DOTNET_TESTCONTAINERS_STATUS_OK (to the TRX test output) only after a real
# container answered PUT /mockserver/status with 200, so the step requires it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Testcontainers tests require Docker — mount the socket.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mcr.microsoft.com/dotnet/sdk:10.0 \
  -w /build/mockserver-testcontainers/dotnet \
  --docker-socket \
  -- bash -ec '
    # Ryuk disabled: its control socket is unreliable across the bridge (see the python
    # step), but a killed run may orphan its container until the agent scales in.
    export TESTCONTAINERS_RYUK_DISABLED=true

    # Docker Desktop: only host.docker.internal reaches published ports (Linux uses the gateway).
    if getent hosts host.docker.internal >/dev/null 2>&1; then
      export TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
    fi

    # A stale TRX from an earlier run must not satisfy the marker check.
    rm -rf test-reports
    # The release restores this project with --locked-mode: a stale lockfile fails here first.
    dotnet restore src/Testcontainers.MockServer/Testcontainers.MockServer.csproj --locked-mode
    dotnet test --logger "trx;LogFileName=unit.trx" --results-directory test-reports

    if ! grep -q "DOTNET_TESTCONTAINERS_STATUS_OK" test-reports/unit.trx; then
      echo "+++ :bangbang: dotnet container-start test did not emit its status marker — no real container was exercised, failing closed" >&2
      exit 1
    fi
  '

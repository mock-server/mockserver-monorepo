#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The release restores the published project with --locked-mode, so a
# packages.lock.json that no longer matches the csproj must fail here first.

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mcr.microsoft.com/dotnet/sdk:10.0 \
  -w /build/mockserver-client-dotnet \
  -- bash -c "dotnet restore src/MockServer.Client/MockServer.Client.csproj --locked-mode && dotnet test --logger 'trx;LogFileName=unit.trx' --results-directory test-reports"

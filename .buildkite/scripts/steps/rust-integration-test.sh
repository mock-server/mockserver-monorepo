#!/usr/bin/env bash
# Rust client integration tests against a live MockServer built from HEAD.
#
# tests/integration_test.rs is entirely #[ignore]d, and CI never passed
# `-- --ignored`, so cargo test reported success having run none of it.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../with-mockserver.sh
source "$SCRIPT_DIR/../with-mockserver.sh"

# NOT `exec`: exec replaces this shell and would discard the EXIT trap that
# with-mockserver.sh registered, orphaning the server container and network.
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i rust:1 \
  -w /build/mockserver-client-rust \
  --network "$MOCKSERVER_NETWORK" \
  -e "MOCKSERVER_URL=$MOCKSERVER_URL" \
  -e "MOCKSERVER_DNS_PORT=$MOCKSERVER_DNS_PORT" \
  -- bash -c "cargo test --locked --test integration_test -- --ignored --test-threads=1"

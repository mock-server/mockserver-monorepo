#!/usr/bin/env bash
# PHP client integration tests against a live MockServer built from HEAD.
#
# php-unit-test.sh runs `--testsuite Unit` only, so tests/Integration/ never
# ran in CI; and MockServerIntegrationTest skips itself without MOCKSERVER_URL.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
# shellcheck source=../with-mockserver.sh
source "$SCRIPT_DIR/../with-mockserver.sh"

# NOT `exec`: exec replaces this shell and would discard the EXIT trap that
# with-mockserver.sh registered, orphaning the server container and network.
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i composer:2@sha256:0c8e663f14c94d07b3c45b175c51f5a14718508f21fe4dd7e54f09488dd0ed71 \
  -w /build/mockserver-client-php \
  --network "$MOCKSERVER_NETWORK" \
  -e "MOCKSERVER_URL=$MOCKSERVER_URL" \
  -e "MOCKSERVER_REQUIRE_SERVER=true" \
  -- bash -ec '
    composer install --no-interaction --prefer-dist
    vendor/bin/phpunit --testsuite Integration --colors=never
  '

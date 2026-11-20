#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The composer:2 image bundles PHP + composer + git + unzip, so the step needs
# no apt-get (and therefore no root) — it runs as the non-root default. This
# also drops the only step that previously required root for an OS package
# install. Pinned by digest: composer:2 on PHP 8.6 lacks ext-mbstring, which
# phpunit requires (see ci-cd.md, client pipelines).
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i composer:2@sha256:0c8e663f14c94d07b3c45b175c51f5a14718508f21fe4dd7e54f09488dd0ed71 \
  -w /build/mockserver-client-php \
  -- bash -ec '
    composer install --no-interaction --prefer-dist
    vendor/bin/phpunit --testsuite Unit --colors=never
  '

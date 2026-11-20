#!/usr/bin/env bash
#
# Verify the MockServer PHP client using a Dockerised PHP + Composer toolchain,
# so it can be linted and unit-tested locally even when no PHP/Composer is
# installed on the host.
#
# Usage:
#   scripts/verify-php-client.sh                 # lint + full unit test suite
#   scripts/verify-php-client.sh --filter Sre    # pass extra args through to phpunit
#
# Behind a corporate TLS-inspection proxy, Composer cannot verify packagist's
# certificate unless the proxy's CA is trusted inside the container. This script
# auto-detects a host CA bundle from the usual env vars (SSL_CERT_FILE /
# REQUESTS_CA_BUNDLE / CURL_CA_BUNDLE) and mounts it into the container. With no
# such proxy the variables are simply unset and the container uses its own CAs.
#
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLIENT_DIR="${REPO_ROOT}/mockserver-client-php"
IMAGE="${PHP_TOOLCHAIN_IMAGE:-composer:2@sha256:0c8e663f14c94d07b3c45b175c51f5a14718508f21fe4dd7e54f09488dd0ed71}"   # the composer image bundles php-cli + composer

if ! command -v docker >/dev/null 2>&1; then
  echo "ERROR: docker is required but not found on PATH." >&2
  exit 1
fi
if [ ! -d "${CLIENT_DIR}" ]; then
  echo "ERROR: PHP client directory not found: ${CLIENT_DIR}" >&2
  exit 1
fi

# Locate a host CA bundle (first existing wins); used only if behind a TLS proxy.
CA_BUNDLE=""
for candidate in "${SSL_CERT_FILE:-}" "${REQUESTS_CA_BUNDLE:-}" "${CURL_CA_BUNDLE:-}"; do
  if [ -n "${candidate}" ] && [ -f "${candidate}" ]; then CA_BUNDLE="${candidate}"; break; fi
done

CA_ARGS=()
if [ -n "${CA_BUNDLE}" ]; then
  echo "Using host CA bundle for TLS: ${CA_BUNDLE}"
  CA_ARGS=(-v "${CA_BUNDLE}:/ca/bundle.pem:ro" -e SSL_CERT_FILE=/ca/bundle.pem -e CURL_CA_BUNDLE=/ca/bundle.pem)
fi

echo "=== php -l (lint every source/test file) ==="
docker run --rm --entrypoint sh \
  -v "${CLIENT_DIR}":/app -w /app "${IMAGE}" -c \
  'for f in $(find src tests -name "*.php"); do php -l "$f" >/dev/null || { php -l "$f"; exit 1; }; done; echo "lint OK"'

echo "=== composer install + phpunit ==="
# bash-3.2-safe empty-array expansion: ${CA_ARGS[@]+"${CA_ARGS[@]}"} expands to
# nothing (not an "unbound variable" error under `set -u`) when no proxy CA is set.
docker run --rm --entrypoint sh \
  -v "${CLIENT_DIR}":/app -w /app ${CA_ARGS[@]+"${CA_ARGS[@]}"} "${IMAGE}" -c \
  'if [ -n "${CURL_CA_BUNDLE:-}" ]; then composer config --global cafile "${CURL_CA_BUNDLE}" >/dev/null 2>&1 || true; fi
   composer install --no-interaction --no-progress
   vendor/bin/phpunit '"$*"

echo "=== PHP client verification PASSED ==="

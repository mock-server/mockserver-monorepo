#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────────
# Validate the per-language scenario examples against a real MockServer.
#
# For each client collection this:
#   1. starts a freshly-built MockServer (mockserver-under-test:local) in Docker
#      on a private network,
#   2. runs that collection's examples/<lang>/scenario example inside the
#      matching toolchain container, wired to the LOCAL client source (not the
#      published package), with MOCKSERVER_HOST/MOCKSERVER_PORT pointing at the
#      server container,
#   3. asserts the example exits 0.
#
# This is how we validate client example code with confidence even for
# toolchains that are not installed (or too old) on the host (e.g. Ruby, PHP).
#
# Usage:
#   examples/validate/run.sh                 # all clients
#   examples/validate/run.sh curl json node  # a subset
#
# Environment:
#   MOCKSERVER_IMAGE   MockServer image to test (default mockserver-under-test:local;
#                      built via .buildkite/scripts/build-local-mockserver-image.sh if absent)
#   KEEP_GOING         set to "true" to keep running after a client fails
# ──────────────────────────────────────────────────────────────────────────
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"
IMAGE="${MOCKSERVER_IMAGE:-mockserver-under-test:local}"
NET="mockserver-examples-$$"
MS="mockserver-examples-server-$$"

ALL_CLIENTS=(curl json node python ruby go dotnet rust php)
CLIENTS=("${@:-}")
[ -z "${CLIENTS[*]}" ] && CLIENTS=("${ALL_CLIENTS[@]}")

# Toolchain images (override-able via env, e.g. NODE_IMAGE=node:20)
# SH_IMAGE needs bash + curl preinstalled (container egress to package repos is
# not assumed) — buildpack-deps:*-curl is Debian-based and ships both.
SH_IMAGE="${SH_IMAGE:-buildpack-deps:bookworm-curl}"
NODE_IMAGE="${NODE_IMAGE:-node:22}"
PYTHON_IMAGE="${PYTHON_IMAGE:-python:3.12}"
RUBY_IMAGE="${RUBY_IMAGE:-ruby:3.3}"
GO_IMAGE="${GO_IMAGE:-golang:1.23}"
DOTNET_IMAGE="${DOTNET_IMAGE:-mcr.microsoft.com/dotnet/sdk:8.0}"
RUST_IMAGE="${RUST_IMAGE:-rust:1}"
PHP_IMAGE="${PHP_IMAGE:-composer:2@sha256:0c8e663f14c94d07b3c45b175c51f5a14718508f21fe4dd7e54f09488dd0ed71}"

declare -a RESULTS

# Behind a TLS-inspecting corporate proxy, package registries (npm, pypi, the Go
# module proxy, NuGet, crates.io) fail certificate verification inside containers.
# If a combined CA bundle is available on the host (the same one the host already
# trusts via SSL_CERT_FILE), mount it into each container and point every
# toolchain at it. No-op in environments without such a proxy (e.g. CI).
CA_BUNDLE="${MOCKSERVER_VALIDATE_CA:-${SSL_CERT_FILE:-}}"
CA_ARGS=()
if [ -n "$CA_BUNDLE" ] && [ -f "$CA_BUNDLE" ]; then
  echo "--- :lock: mounting CA bundle into containers: $CA_BUNDLE"
  CA_ARGS=(
    -v "$CA_BUNDLE":/certs/ca.pem:ro
    -e NODE_EXTRA_CA_CERTS=/certs/ca.pem
    -e PIP_CERT=/certs/ca.pem
    -e REQUESTS_CA_BUNDLE=/certs/ca.pem
    -e SSL_CERT_FILE=/certs/ca.pem
    -e CARGO_HTTP_CAINFO=/certs/ca.pem
    -e GIT_SSL_CAINFO=/certs/ca.pem
  )
fi

cleanup() {
  docker rm -f "$MS" >/dev/null 2>&1 || true
  docker network rm "$NET" >/dev/null 2>&1 || true
}
trap cleanup EXIT

ensure_image() {
  if ! docker image inspect "$IMAGE" >/dev/null 2>&1; then
    echo "--- :hammer: building $IMAGE"
    MOCKSERVER_IMAGE="$IMAGE" bash "$REPO_ROOT/.buildkite/scripts/build-local-mockserver-image.sh"
  fi
}

start_server() {
  docker network create "$NET" >/dev/null
  docker run -d --name "$MS" --network "$NET" \
    -e MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION=false \
    "$IMAGE" >/dev/null
  echo -n "--- :hourglass: waiting for MockServer "
  for _ in $(seq 1 30); do
    if docker run --rm --network "$NET" curlimages/curl:latest \
        -sf -o /dev/null -X PUT "http://$MS:1080/mockserver/status" >/dev/null 2>&1; then
      echo "up"; return 0
    fi
    echo -n "."; sleep 1
  done
  echo " FAILED to start"; docker logs "$MS" | tail -20; return 1
}

# run <label> <image> <shell-command>
run_client() {
  local label="$1" image="$2" cmd="$3"
  echo ""
  echo "═══════════════════════════════════════════════════════════════"
  echo "▶ $label   ($image)"
  echo "═══════════════════════════════════════════════════════════════"
  if docker run --rm --network "$NET" \
       -v "$REPO_ROOT":/work -w /work \
       -e MOCKSERVER_HOST="$MS" -e MOCKSERVER_PORT=1080 \
       -e HOME=/tmp -e CARGO_HOME=/tmp/cargo -e GOFLAGS=-mod=mod \
       "${CA_ARGS[@]}" \
       "$image" sh -c "$cmd"; then
    echo "✅ PASS: $label"; RESULTS+=("PASS  $label")
  else
    echo "❌ FAIL: $label"; RESULTS+=("FAIL  $label")
    [ "${KEEP_GOING:-}" = "true" ] || { print_summary; exit 1; }
  fi
}

# Which example family to run: "scenario" (default) or "callback".
SUITE="${SUITE:-scenario}"

validate_curl() {
  [ "$SUITE" = scenario ] || { echo "(curl has no $SUITE examples; skipping)"; return 0; }
  run_client "curl" "$SH_IMAGE" "export MOCKSERVER_URL=http://$MS:1080;
    set -e; for f in examples/curl/scenario/*.sh; do echo \"--- \$f\"; bash \"\$f\"; done"
}

validate_json() {
  [ "$SUITE" = scenario ] || { echo "(json has no $SUITE examples; skipping)"; return 0; }
  # PUT each expectation payload and assert it is accepted (2xx); the *.json that
  # are scenario-REST bodies (timed_transition/external_trigger) are exercised by
  # the curl examples, so here we just validate the expectation payloads parse.
  run_client "json" "$SH_IMAGE" "export U=http://$MS:1080;
    set -e
    curl -sf -X PUT \$U/mockserver/reset >/dev/null
    for f in examples/json/scenario/state_machine.json examples/json/scenario/sequential_cycling.json examples/json/scenario/cross_protocol.json examples/json/response_action/sequential_responses.json examples/json/response_action/random_responses.json; do
      code=\$(curl -s -o /dev/null -w '%{http_code}' -X PUT \$U/mockserver/expectation --data-binary @\$f)
      echo \"  \$f -> HTTP \$code\"
      case \$code in 2*) ;; *) echo \"  REJECTED\"; exit 1;; esac
    done
    echo 'all json expectation payloads accepted'"
}

validate_node() {
  local dir; [ "$SUITE" = callback ] && dir=callback_examples || dir=scenario_examples
  run_client "node" "$NODE_IMAGE" "set -e
    cd examples/node/$dir
    npm install --no-audit --no-fund --silent
    npm install --no-audit --no-fund --silent /work/mockserver-client-node
    node scenario.js"
}

validate_python() {
  local f; [ "$SUITE" = callback ] && f=examples/python/callback/callback.py || f=examples/python/scenario/scenario.py
  run_client "python" "$PYTHON_IMAGE" "set -e
    pip install --quiet -e /work/mockserver-client-python
    python /work/$f"
}

validate_ruby() {
  local f; [ "$SUITE" = callback ] && f=examples/ruby/callback/callback.rb || f=examples/ruby/scenario/scenario.rb
  run_client "ruby" "$RUBY_IMAGE" "set -e
    cd /work/mockserver-client-ruby
    bundle install --quiet
    bundle exec ruby -Ilib /work/$f"
}

validate_go() {
  run_client "go" "$GO_IMAGE" "set -e
    cd examples/go/$SUITE
    go run ."
}

validate_dotnet() {
  # .NET on Linux uses the OS trust store, so register the CA there (not just env).
  run_client "dotnet" "$DOTNET_IMAGE" "set -e
    if [ -f /certs/ca.pem ]; then cp /certs/ca.pem /usr/local/share/ca-certificates/corp.crt && update-ca-certificates >/dev/null 2>&1 || true; fi
    cd examples/dotnet/$SUITE
    dotnet run -v quiet"
}

validate_rust() {
  run_client "rust" "$RUST_IMAGE" "set -e
    cd examples/rust/$SUITE
    cargo run --quiet"
}

validate_php() {
  local f; [ "$SUITE" = callback ] && f=examples/php/callback/callback.php || f=examples/php/scenario/scenario.php
  run_client "php" "$PHP_IMAGE" "set -e
    cd /work/mockserver-client-php && composer install --no-interaction --quiet
    php /work/$f"
}

print_summary() {
  echo ""
  echo "════════════════════ SUMMARY ════════════════════"
  for r in "${RESULTS[@]}"; do echo "  $r"; done
  echo "══════════════════════════════════════════════════"
}

ensure_image
start_server || exit 1

for c in "${CLIENTS[@]}"; do
  case "$c" in
    curl)   validate_curl ;;
    json)   validate_json ;;
    node)   validate_node ;;
    python) validate_python ;;
    ruby)   validate_ruby ;;
    go)     validate_go ;;
    dotnet) validate_dotnet ;;
    rust)   validate_rust ;;
    php)    validate_php ;;
    *) echo "unknown client: $c (known: ${ALL_CLIENTS[*]})" ;;
  esac
done

print_summary
if grep -q '^FAIL' <<<"$(printf '%s\n' "${RESULTS[@]}")"; then
  echo "❌ one or more clients failed"; exit 1
fi
echo "🎉 all selected clients passed"

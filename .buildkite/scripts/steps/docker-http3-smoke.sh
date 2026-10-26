#!/usr/bin/env bash
# Prove each image serves HTTP/3, and refuses http3Port with the actionable start-up message when its
# QUIC native is present but cannot load. Pass the -http3 image and the standard image it is built on:
# both must serve HTTP/3.
#
#   docker-http3-smoke.sh <image> [<image> ...]
#
# Each image runs with a read-only root filesystem (only the CA directory writable), so the QUIC
# native can only load from /usr/lib - never by extraction to /tmp. The request is made by the JDK's
# own HTTP/3 client (.buildkite/scripts/lib/Http3Probe.java), which trusts only the server's CA.
set -euo pipefail

if [ "$#" -eq 0 ]; then
  echo "usage: docker-http3-smoke.sh <image> [<image> ...]" >&2
  exit 2
fi
PROBE_JDK_IMAGE="${PROBE_JDK_IMAGE:-eclipse-temurin:26-jdk-noble}"
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../.." && pwd)"
PROBE="$REPO_ROOT/.buildkite/scripts/lib/Http3Probe.java"
# HTTP3_SMOKE_ID lets a caller name (and so clean up) the containers if this script is killed.
SERVER="mockserver-http3-smoke-${HTTP3_SMOKE_ID:-$$}"
REFUSER="mockserver-http3-refuse-${HTTP3_SMOKE_ID:-$$}"

cleanup() {
  docker rm -f "$SERVER" "$REFUSER" >/dev/null 2>&1 || true
}
trap cleanup EXIT
trap 'exit 143' TERM INT

assert_serves() {
  local image="$1"
  echo "--- :docker: HTTP/3 smoke: $image"
  docker rm -f "$SERVER" >/dev/null 2>&1 || true
  docker run -d --name "$SERVER" --read-only --tmpfs /home/nonroot:uid=65532,gid=65532 \
    -e MOCKSERVER_HTTP3_PORT=8443 "$image" >/dev/null
  if ! docker run --rm --network "container:$SERVER" -v "$PROBE:/Http3Probe.java:ro" \
       "$PROBE_JDK_IMAGE" java /Http3Probe.java 1080 8443; then
    echo "HTTP/3 smoke FAILED: no HTTP/3 answer from $image. Server log:" >&2
    docker logs "$SERVER" 2>&1 | tail -40 >&2
    return 1
  fi
  docker rm -f "$SERVER" >/dev/null 2>&1 || true
}

# Runs <image> read-only with http3Port and JAVA_TOOL_OPTIONS, and asserts it refuses to start with
# every expected string, no stack frames, and exactly one "underlying error:" cause line.
assert_refuses() {
  local image="$1" label="$2" java_tool_options="$3"; shift 3
  echo "--- :docker: HTTP/3 refusal: $label"
  docker rm -f "$REFUSER" >/dev/null 2>&1 || true
  docker run -d --name "$REFUSER" --read-only --tmpfs /home/nonroot:uid=65532,gid=65532 \
    -e MOCKSERVER_HTTP3_PORT=8443 -e "JAVA_TOOL_OPTIONS=$java_tool_options" "$image" >/dev/null
  local exit_code=timeout log failed=0 expected
  for _ in $(seq 1 90); do
    if [ "$(docker inspect -f '{{.State.Running}}' "$REFUSER")" = "false" ]; then
      exit_code="$(docker inspect -f '{{.State.ExitCode}}' "$REFUSER")"
      break
    fi
    sleep 1
  done
  log="$(docker logs "$REFUSER" 2>&1 || true)"
  if [ "$exit_code" = "timeout" ] || [ "$exit_code" = "0" ]; then
    echo "HTTP/3 refusal FAILED ($label): exited '$exit_code'; it must refuse to start" >&2
    failed=1
  fi
  for expected in "$@"; do
    if ! grep -qF -- "$expected" <<<"$log"; then
      echo "HTTP/3 refusal FAILED ($label): the start-up message does not say '$expected'" >&2
      failed=1
    fi
  done
  if grep -qE $'^\tat ' <<<"$log"; then
    echo "HTTP/3 refusal FAILED ($label): the start-up message is buried in a stack trace" >&2
    failed=1
  fi
  if [ "$(grep -c 'underlying error: ' <<<"$log" || true)" != "1" ]; then
    echo "HTTP/3 refusal FAILED ($label): expected exactly one 'underlying error:' cause line" >&2
    failed=1
  fi
  if [ "$failed" -ne 0 ]; then
    echo "$log" | tail -40 >&2
    return 1
  fi
  docker rm -f "$REFUSER" >/dev/null 2>&1 || true
  echo "PASS: $label refuses http3Port with the fixes, one cause line and no stack frames"
}

for image in "$@"; do
  assert_serves "$image"
  # Native hidden from java.library.path and /tmp read-only: it must not send users to another image.
  assert_refuses "$image" "$image (native present, unloadable)" "-XX:+UseZGC -Djava.library.path=/nope" \
    "The native library is present but failed to load" "Found: " "remove http3Port"
done

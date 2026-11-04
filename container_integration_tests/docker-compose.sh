#!/usr/bin/env bash

set -euo pipefail

INTEGRATION_TESTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
EXAMPLES_DIR="${INTEGRATION_TESTS_DIR}/../examples/docker-compose"

# Overlays use the `!reset` / `!override` YAML merge tags introduced in Docker
# Compose v2.22 (August 2023). Without these, host port-publishing from the
# base example would leak into CI and cause port clashes between tests, and
# volume overrides would append instead of replacing.
function check-compose-version() {
  local raw
  raw="$(docker-compose version --short 2>/dev/null || true)"
  if [[ -z "${raw}" ]]; then
    echo "docker-compose not found in PATH" >&2
    return 1
  fi
  local v="${raw#v}"
  local major="${v%%.*}"
  local rest="${v#*.}"
  local minor="${rest%%.*}"
  if [[ "${major}" -lt 2 ]] || { [[ "${major}" -eq 2 ]] && [[ "${minor}" -lt 22 ]]; }; then
    echo "docker-compose >= 2.22 required for !reset/!override merge tags; found ${raw}" >&2
    return 1
  fi
}
check-compose-version

# Build the -f arguments for a test case: the canonical example compose
# file is the base; if an overlay (docker-compose.override.yml) exists in
# the test directory it is layered on top. Falls back to a single in-place
# docker-compose.yml in the test directory for any test that has not yet
# been migrated to the overlay pattern.
function compose-files() {
  local case="${1}"
  local base="${EXAMPLES_DIR}/${case}/docker-compose.yml"
  local overlay="${INTEGRATION_TESTS_DIR}/${case}/docker-compose.override.yml"
  local legacy="${INTEGRATION_TESTS_DIR}/${case}/docker-compose.yml"

  if [[ -f "${base}" && -f "${overlay}" ]]; then
    echo "-f ${base} -f ${overlay}"
  elif [[ -f "${legacy}" ]]; then
    echo "-f ${legacy}"
  elif [[ -f "${base}" ]]; then
    echo "-f ${base}"
  else
    echo "no docker-compose.yml found for test case '${case}' (looked in ${base} and ${legacy})" >&2
    return 1
  fi
}

function docker-exec() {
  if [[ -z "${TEST_CASE:-}" ]]; then
    runCommand "docker-compose exec -T ${1} /bin/bash -c \"${2}\""
  else
    runCommand "docker-compose -p ${TEST_CASE} exec -T ${1} /bin/bash -c \"${2}\""
  fi
}

function docker-exec-client() {
  docker-exec "client" "${1}"
}

# Read a file OUT of a compose container (running OR stopped) to stdout.
#
# The mockserver image is distroless (gcr.io/distroless/java-base-debian13:nonroot):
# it has no shell and no coreutils, so `docker-compose exec <svc> cat <file>` does not
# work. It also runs as a non-root uid, so a bind-mounted persisted file lands on the
# host owned by that in-container uid and is not reliably readable by the CI agent user
# — which is exactly why a host-side `cat` aborted these tests under `set -e` (the
# failure only reproduces on Linux; Docker Desktop for macOS remaps the ownership).
#
# `docker cp` goes through the docker daemon (root), so it reads the file regardless of
# in-container tooling or file ownership, and writes the host copy as the calling user.
# Args: <compose-service> <path-in-container>. Returns non-zero (never aborts) if the
# container or file is missing, so callers can guard with `|| true`.
function read_container_file() {
  local service="${1}" path="${2}"
  local cid tmp
  cid="$(docker-compose -p "${TEST_CASE}" ps -aq "${service}" 2>/dev/null | head -1)" || return 1
  [[ -n "${cid}" ]] || return 1
  tmp="$(mktemp)" || return 1
  if docker cp "${cid}:${path}" "${tmp}" 2>/dev/null; then
    cat "${tmp}"
    rm -f "${tmp}"
    return 0
  fi
  rm -f "${tmp}"
  return 1
}

# Dump bounded container state + logs when a compose test is exiting non-zero, BEFORE
# tear-down removes the containers and the evidence with them. Pass the exiting status
# as $1 (call it FIRST in an EXIT trap so `$?` is still the failing status).
#
# Strictly additive to output: it records nothing to the PASS/FAIL logs and never alters
# the exit status. Every command is `|| true`-guarded so the diagnostic can neither abort
# the trap under `set -e` nor mask the real failure. Bounded (`--tail=50`) to stay inside
# the noise budget that 143bce6ca deliberately restored.
function dump_compose_diagnostics_on_failure() {
  local rc="${1:-0}"
  [[ "${rc}" -ne 0 ]] || return 0
  printMessage "--- ${TEST_CASE}: container state + logs (exit ${rc}, captured before tear-down) ---" || true
  docker-compose -p "${TEST_CASE}" ps || true
  docker-compose -p "${TEST_CASE}" logs --tail=50 || true
  return 0
}

function tear-down() {
  local files
  files="$(compose-files "${TEST_CASE}")"
  export OVERRIDE_DIR="${INTEGRATION_TESTS_DIR}/${TEST_CASE}"
  runCommand "docker-compose ${files} -p ${TEST_CASE} down --remove-orphans || true"
}

function start-up() {
  local files
  files="$(compose-files "${TEST_CASE}")"
  # Exported so overlay files can reference the test directory in volume
  # paths via ${OVERRIDE_DIR} — relative paths in an overlay otherwise
  # resolve against the project directory (the base example's directory).
  export OVERRIDE_DIR="${INTEGRATION_TESTS_DIR}/${TEST_CASE}"
  runCommand "docker-compose ${files} -p ${TEST_CASE} up --build -d"
}

function container-logs() {
  printMessage "mockserver logs"
  # Scope to the test's compose project (otherwise the crashed project's logs
  # are not found) and never fail — a missing/crashed project must not abort
  # the caller under `set -e`.
  docker-compose -p "${TEST_CASE}" logs || true
}

# Poll a MockServer instance (by docker-compose service name) via the client
# container until the status endpoint responds, or fail after ~30 s.
# Usage: wait_ready <host> [port]  (port defaults to 1080)
function wait_ready() {
  local host="${1}" port="${2:-1080}"
  for _ in $(seq 1 30); do
    if docker-exec-client "curl -sf -o /dev/null -X PUT http://${host}:${port}/mockserver/status"; then
      return 0
    fi
    sleep 1
  done
  # A container that never came up is exactly when its logs are needed. Dump the target
  # service's recent logs + the project's container state (bounded, so a wedged container
  # cannot flood the build), guarded so the diagnostic itself can never abort the caller.
  printMessage "FAIL: ${host}:${port} did not become ready"
  docker-compose -p "${TEST_CASE}" ps || true
  docker-compose -p "${TEST_CASE}" logs --tail=50 "${host}" || true
  return 1
}

function clean-up-docker-containers() {
  runCommand "docker ps --all | grep mockserver/mockserver:integration_testing | awk '{ print \$1 }' | xargs docker stop"
  runCommand "docker ps --all | grep mockserver/mockserver:integration_testing | awk '{ print \$1 }' | xargs docker rm"
}

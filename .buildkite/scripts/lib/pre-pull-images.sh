#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Pre-pull the container images a Docker-gated Testcontainers suite needs,
# with bounded retry + exponential backoff, BEFORE Maven runs.
#
# WHY THIS EXISTS
#
#   Testcontainers pulls each backing image lazily, inside RemoteDockerImage,
#   behind an Awaitility wait. When a registry is slow or throttled (quay.io
#   from the elastic-ci-stack agents is the observed case) that wait expires
#   and the suite dies ~5 minutes in with an opaque
#
#     org.testcontainers.containers.ContainerFetchException: Can't get Docker
#     image: RemoteDockerImage(imageName=adobe/s3mock:...)
#     Caused by: ...ConditionTimeoutException: ... was not fulfilled within ...
#
#   reported as initializationError before any test logic runs. Pulling the
#   images here first turns that into a fast, clearly-attributed failure that
#   names the image and registry, and — on success — warms the host daemon's
#   image cache so Testcontainers (default PullPolicy pulls only when the image
#   is absent locally) never pulls again and never exercises that wait.
#
# WHY A PRE-PULL AND NOT A RETRY AROUND MAVEN
#
#   Retrying an image *pull* is safe: a pull cannot turn a failing test green —
#   it only populates the daemon's image cache. Retrying the Maven invocation
#   itself is forbidden in this repo because it converts real test failures into
#   false greens; the sibling step headers and assert-suite-ran.sh exist for
#   exactly that reason. This warms the cache, then gets out of the way.
#
# WHY IT MIRRORS run-in-docker.sh's PR-SOCKET GATE
#
#   run-in-docker.sh withholds the Docker socket on PR builds (host-root
#   breakout risk) and exit-0s the socket step. Such a step never pulls anything
#   on a PR, so this pre-pull must skip on a PR too — otherwise it would newly
#   couple PR builds to registry availability and could fail a PR for an image
#   its skipped step never uses. ALLOW_PR_DOCKER_SOCKET=true overrides, exactly
#   as it does in run-in-docker.sh.
#
# WHERE IT RUNS
#
#   On the HOST agent, which owns the docker CLI and daemon. Testcontainers in
#   the run-in-docker container reaches that same daemon through the mounted
#   socket, so images warmed here are already present when the suite starts.
#
# USAGE
#   pre-pull-images.sh <image> [<image> ...]
#
# Tunables (env): PRE_PULL_ATTEMPTS (default 4),
#                 PRE_PULL_BACKOFF_SECONDS (default 5, doubles each retry).
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

if [[ "$#" -eq 0 ]]; then
  echo "pre-pull-images.sh: no images given" >&2
  exit 2
fi

# Same gate run-in-docker.sh applies: no socket on PR builds => the step is
# skipped => there is nothing to pre-pull.
PR="${BUILDKITE_PULL_REQUEST:-false}"
if [[ "$PR" != "false" && "${ALLOW_PR_DOCKER_SOCKET:-false}" != "true" ]]; then
  echo "--- :information_source: Skipping image pre-pull on PR build #${PR} (Docker-socket step is skipped on PRs)"
  exit 0
fi

if ! command -v docker >/dev/null 2>&1; then
  echo "^^^ +++"
  echo "pre-pull-images.sh: docker CLI not found on the agent — cannot pre-pull images" >&2
  exit 1
fi

ATTEMPTS="${PRE_PULL_ATTEMPTS:-4}"
BACKOFF_SECONDS="${PRE_PULL_BACKOFF_SECONDS:-5}"

prePullImage() {
  local image="$1"
  # Registry host is everything before the first '/', but only when it looks
  # like a host (contains a '.' or ':'); otherwise the image is on Docker Hub.
  local registry="${image%%/*}"
  if [[ "$image" != */* || ( "$registry" != *.* && "$registry" != *:* ) ]]; then
    registry="docker.io (Docker Hub)"
  fi

  # Already cached: skip the registry entirely. Testcontainers' default pull policy is
  # pull-if-absent, so an image the daemon already holds would never have been fetched
  # by the suite either. Without this a transient registry outage would fail a step that
  # used to run green off the local cache - turning a mitigation into a new failure mode.
  # Tags here are pinned, so a cached image is the image the suite wants.
  if docker image inspect "$image" >/dev/null 2>&1; then
    echo "--- :docker: ${image} already cached; skipping pull"
    return 0
  fi

  local attempt=1
  local delay="$BACKOFF_SECONDS"
  while true; do
    echo "--- :docker: Pre-pulling ${image} (attempt ${attempt}/${ATTEMPTS})"
    if docker pull -q "$image"; then
      return 0
    fi
    if (( attempt >= ATTEMPTS )); then
      echo "^^^ +++"
      echo "pre-pull-images.sh: FAILED to pull '${image}' from '${registry}' after ${ATTEMPTS} attempts." >&2
      echo "The registry is unreachable, throttling, or the image/tag no longer exists." >&2
      echo "Failing this step now rather than letting Testcontainers time out mid-suite." >&2
      return 1
    fi
    echo "pull of '${image}' failed; retrying in ${delay}s..." >&2
    sleep "$delay"
    attempt=$(( attempt + 1 ))
    delay=$(( delay * 2 ))
  done
}

for image in "$@"; do
  prePullImage "$image"
done

echo "--- :white_check_mark: Pre-pulled $# image(s)"

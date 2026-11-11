#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

IMAGE=""
DOCKER_ARGS=()
COMMAND_ARGS=()
WORKDIR="/build"
MEMORY=""
NETWORK=""
DOCKER_SOCKET=false
ENTRYPOINT=""
CONTAINER_NAME=""
INIT=false
ENV_VARS=()
VOLUMES=()
CACHE_TYPES=()
CACHE_SAVE=false
# Security posture for untrusted PR builds. NOTE: the agents already run Docker
# with user-namespace remapping (DOCKER_USERNS_REMAP=true, elastic-ci-stack
# default), so container-root is already an unprivileged host UID — a breakout
# is not host-root. On top of that:
#  - The Docker socket (-s) is ALWAYS withheld from PR builds (L3) — the highest
#    -value protection and a safe skip (a socket bypasses the userns boundary).
#  - --harden adds defense-in-depth compatible with userns-remap: cap-drop=ALL +
#    no-new-privileges. (We deliberately do NOT add --user: that double-remaps
#    the UID and breaks workspace writes on the agents.) Use --cap-add to restore
#    a capability a step needs.
HARDEN=false
CAP_ADDS=()
BANNER_COMMAND=""

usage() {
  cat <<EOF
Usage: run-in-docker.sh [OPTIONS] -- COMMAND [ARGS...]

Runs a command inside a Docker container with the repo mounted at /build.
Logs the full docker run command at the start for easy local reproduction.

Options:
  -i, --image IMAGE        Docker image to use (required)
  -w, --workdir DIR        Working directory inside container (default: /build)
  -m, --memory SIZE        Memory limit (e.g. 7g)
  -s, --docker-socket      Mount Docker socket into container
  --entrypoint CMD         Override container entrypoint
  --name NAME              Container name (lets the caller docker kill it on cancel)
  --init                   Run tini as PID 1 so the command receives signals (always on in CI)
  -e, --env KEY=VALUE      Pass environment variable to container
  -v, --volume SRC:DST     Additional volume mount
  --cache TYPE             Mount dependency cache (maven|npm|pip|bundler|gradle|go|cargo|nuget)
  --cache-save             This step owns the S3 save of its maven/gradle caches (dependency-cache.sh)
  --network NAME           Docker network to connect to
  --harden                 Add no-new-privileges + cap-drop=ALL (agents already userns-remap)
  --cap-add CAP            With --harden, add a Linux capability back
  --banner-command TEXT    Show TEXT instead of the command in the logged banner
  -h, --help               Show this help

Environment:
  LOCAL_DOCKER_CA_BUNDLE   Local dev only: path to a host PEM bundle (system roots +
                           corporate root). When set, it is mounted read-only and every
                           common toolchain (pip/npm/cargo/composer/gem/go/git/dotnet) is
                           pointed at it so in-container dependency downloads work behind a
                           TLS-inspection proxy. Unset in CI, so CI behaviour is unchanged.

Examples:
  .buildkite/scripts/run-in-docker.sh -i node:22 -w /build/mockserver-ui -- npm ci && npm test
  .buildkite/scripts/run-in-docker.sh -i python:3.12 -s -- bash -c 'cd mockserver-client-python && pytest'
  LOCAL_DOCKER_CA_BUNDLE=~/ca-bundle.pem .buildkite/scripts/steps/php-unit-test.sh
EOF
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -i|--image)   IMAGE="$2"; shift 2 ;;
    -w|--workdir) WORKDIR="$2"; shift 2 ;;
    -m|--memory)  MEMORY="$2"; shift 2 ;;
    -s|--docker-socket) DOCKER_SOCKET=true; shift ;;
    --entrypoint) ENTRYPOINT="$2"; shift 2 ;;
    --name)       CONTAINER_NAME="$2"; shift 2 ;;
    --init)       INIT=true; shift ;;
    -e|--env)     ENV_VARS+=("$2"); shift 2 ;;
    -v|--volume)  VOLUMES+=("$2"); shift 2 ;;
    --cache)      CACHE_TYPES+=("$2"); shift 2 ;;
    --cache-save) CACHE_SAVE=true; shift ;;
    --network)    NETWORK="$2"; shift 2 ;;
    --harden)     HARDEN=true; shift ;;
    --cap-add)    CAP_ADDS+=("$2"); shift 2 ;;
    --banner-command) BANNER_COMMAND="$2"; shift 2 ;;
    -h|--help)    usage ;;
    --)           shift; COMMAND_ARGS=("$@"); break ;;
    *)            COMMAND_ARGS=("$@"); break ;;
  esac
done

if [[ -z "$IMAGE" ]]; then
  echo "Error: --image is required"
  exit 1
fi

if [[ ${#COMMAND_ARGS[@]} -eq 0 ]]; then
  echo "Error: no command specified after --"
  exit 1
fi

DOCKER_ARGS+=(--rm)
DOCKER_ARGS+=(-v "$REPO_ROOT:/build")
DOCKER_ARGS+=(-w "$WORKDIR")

# ---------------------------------------------------------------------------
# Hardening (opt-in via --harden): capability dropping + no privilege escalation
# ---------------------------------------------------------------------------
# IMPORTANT: the build agents already run Docker with user-namespace remapping
# (DOCKER_USERNS_REMAP=true in the elastic-ci-stack), so container-root is
# ALREADY mapped to an unprivileged host UID — a breakout is not host-root. We
# therefore must NOT add `--user` here: a non-root container UID gets a SECOND
# remap and no longer matches the workspace owner, which breaks writes to the
# bind-mounted /build (this is exactly why per-step non-root passed locally but
# failed on CI). --harden adds the defense-in-depth that IS compatible with
# userns-remap: drop all Linux capabilities + block privilege escalation. Add a
# capability back per-step with --cap-add when a tool needs one.
HOME_DIR="/root"
if [[ "$HARDEN" == "true" ]]; then
  DOCKER_ARGS+=(--security-opt no-new-privileges)
  DOCKER_ARGS+=(--cap-drop ALL)
  for cap in "${CAP_ADDS[@]+"${CAP_ADDS[@]}"}"; do
    DOCKER_ARGS+=(--cap-add "$cap")
  done
fi

if [[ -n "$MEMORY" ]]; then
  DOCKER_ARGS+=(--memory="$MEMORY" --memory-swap="$MEMORY")
fi

if [[ -n "$CONTAINER_NAME" ]]; then
  DOCKER_ARGS+=(--name "$CONTAINER_NAME")
fi

# A cancelled job's docker CLI forwards SIGTERM to the container's PID 1, which ignores it
# unless it handles it (bash does not; tini forwards it), so the container outlived the job
# and kept writing into the checkout the next job on this agent builds in. In CI: always
# --init, and remove such a container.
if [[ "${BUILDKITE:-}" == "true" ]]; then
  INIT=true
  DOCKER_ARGS+=(--label "mockserver.ci.checkout=$REPO_ROOT" --label "mockserver.ci.job=${BUILDKITE_JOB_ID:-}")
  while read -r stale_id stale_job; do
    [[ -n "$stale_id" && "$stale_job" != "${BUILDKITE_JOB_ID:-}" ]] || continue
    echo "+++ :warning: removing container $stale_id, left running on this checkout by job ${stale_job:-unknown}" >&2
    docker rm -f "$stale_id" >/dev/null 2>&1 || true
  done < <(docker ps --filter "label=mockserver.ci.checkout=$REPO_ROOT" \
    --format '{{.ID}} {{.Label "mockserver.ci.job"}}' 2>/dev/null || true)
fi

if [[ "$INIT" == "true" ]]; then
  DOCKER_ARGS+=(--init)
fi

if [[ -n "$ENTRYPOINT" ]]; then
  DOCKER_ARGS+=(--entrypoint "$ENTRYPOINT")
fi

if [[ -n "$NETWORK" ]]; then
  DOCKER_ARGS+=(--network "$NETWORK")
fi

if [[ "$DOCKER_SOCKET" == "true" ]]; then
  # L3: the Docker socket gives a container full control of the host daemon
  # (trivial host-root breakout). Untrusted PR code must NOT receive it — these
  # socket-mounting steps (Testcontainers / helm integration) are skipped on PR
  # builds and run only on the trusted default branch (post-merge). Override for
  # a specific trusted PR with ALLOW_PR_DOCKER_SOCKET=true.
  PR="${BUILDKITE_PULL_REQUEST:-false}"
  if [[ "$PR" != "false" && "${ALLOW_PR_DOCKER_SOCKET:-false}" != "true" ]]; then
    {
      echo "+++ :lock: Skipping Docker-socket step on PR build #${PR}"
      echo "The Docker socket is withheld from untrusted PR code (host-root breakout risk)."
      echo "This step runs on the default branch after merge. To force on a trusted PR,"
      echo "set ALLOW_PR_DOCKER_SOCKET=true."
    } >&2
    exit 0
  fi
  DOCKER_ARGS+=(-v /var/run/docker.sock:/var/run/docker.sock)
fi

for env_var in "${ENV_VARS[@]+"${ENV_VARS[@]}"}"; do
  DOCKER_ARGS+=(-e "$env_var")
done

for vol in "${VOLUMES[@]+"${VOLUMES[@]}"}"; do
  DOCKER_ARGS+=(-v "$vol")
done

# ---------------------------------------------------------------------------
# Dependency cache volume mounts (fail-safe: skip silently if dir missing)
# ---------------------------------------------------------------------------
# Each --cache TYPE maps a workspace-local .buildkite-cache/<type> directory
# into the container at the tool's default cache location. In CI, maven and
# gradle are first restored from S3 in THIS job by dependency-cache.sh: the
# workspace is git-cleaned between jobs, so a separate restore job cannot work.
# A miss leaves an empty directory and the build proceeds cold.
# ---------------------------------------------------------------------------
CACHE_BASE="${BUILDKITE_BUILD_CHECKOUT_PATH:-${REPO_ROOT}}/.buildkite-cache"
dependency_cache() { # restore|save TYPE -- time-bounded, never fails the step
  local bound=() seconds=300
  [[ "$1" == "save" ]] && seconds=900
  command -v timeout >/dev/null 2>&1 && bound=(timeout "$seconds")
  "${bound[@]+"${bound[@]}"}" "$SCRIPT_DIR/dependency-cache.sh" "$1" "$2" || true
}
for cache_type in "${CACHE_TYPES[@]+"${CACHE_TYPES[@]}"}"; do
  host_dir="${CACHE_BASE}/${cache_type}"
  if [[ "${BUILDKITE:-}" == "true" ]]; then
    dependency_cache restore "$cache_type"
  fi
  # Ensure the host directory exists (empty is fine -- cold build)
  mkdir -p "$host_dir" 2>/dev/null || true
  # Cache targets follow $HOME so they work whether the container runs as root
  # ($HOME=/root) or non-root ($HOME=/tmp). Bundler installs gems under a
  # GEM_HOME that defaults to a root-only path in the ruby image, so under
  # non-root we redirect it to a writable $HOME/bundle.
  case "$cache_type" in
    maven)   DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.m2/repository") ;;
    npm)     DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.npm") ;;
    pip)     DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.cache/pip") ;;
    gradle)
      # The Gradle wrapper stores TWO things under GRADLE_USER_HOME (~/.gradle):
      # the resolved dependency cache in caches/, and -- crucially -- the
      # downloaded Gradle DISTRIBUTION itself in wrapper/dists/. Mapping only
      # caches/ (the previous behaviour) left the distribution un-cached, so every
      # build re-downloaded gradle-<ver>-bin.zip from services.gradle.org -- the
      # transient CDN stall that timed out the ~25-min reactor in build
      # mockserver-java #2170. Mount BOTH, as sibling subdirs of the single
      # .buildkite-cache/gradle tree so one restore/save tarball covers both. We
      # deliberately do NOT mount ~/.gradle wholesale: it also holds daemon state,
      # logs, and potentially credentials that must not be shared between builds.
      mkdir -p "${host_dir}/caches" "${host_dir}/wrapper-dists" 2>/dev/null || true
      DOCKER_ARGS+=(-v "${host_dir}/caches:${HOME_DIR}/.gradle/caches")
      DOCKER_ARGS+=(-v "${host_dir}/wrapper-dists:${HOME_DIR}/.gradle/wrapper/dists")
      ;;
    go)      DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/go/pkg/mod") ;;
    cargo)   DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.cargo/registry") ;;
    nuget)   DOCKER_ARGS+=(-v "${host_dir}:${HOME_DIR}/.nuget/packages") ;;
    bundler) DOCKER_ARGS+=(-v "${host_dir}:/usr/local/bundle/cache") ;;
    *)       echo "[run-in-docker] WARNING: unknown cache type '${cache_type}' -- ignored" >&2 ;;
  esac
done

# ---------------------------------------------------------------------------
# Local-only TLS CA injection (opt-in via LOCAL_DOCKER_CA_BUNDLE)
# ---------------------------------------------------------------------------
# On a developer machine behind a TLS-inspection proxy (e.g. a corporate MITM
# proxy), in-container dependency downloads (pip / npm / cargo / composer / gem
# / go / nuget) fail TLS verification because the container does not trust the
# proxy's root CA. Point LOCAL_DOCKER_CA_BUNDLE at a host PEM bundle that
# contains the system roots PLUS the corporate root, and this block mounts it
# read-only and points every common toolchain at it. This is purely a local
# convenience so that every non-Java language can be verified through its
# pinned Docker image without installing the toolchain on the host. CI never
# sets this variable, so CI behaviour is completely unchanged.
if [[ -n "${LOCAL_DOCKER_CA_BUNDLE:-}" ]]; then
  if [[ -f "$LOCAL_DOCKER_CA_BUNDLE" ]]; then
    CA_IN_CONTAINER="/etc/ssl/local-ca-bundle.pem"
    DOCKER_ARGS+=(-v "${LOCAL_DOCKER_CA_BUNDLE}:${CA_IN_CONTAINER}:ro")
    DOCKER_ARGS+=(-e "SSL_CERT_FILE=${CA_IN_CONTAINER}")        # OpenSSL: ruby, go, python ssl, curl, dotnet
    DOCKER_ARGS+=(-e "CURL_CA_BUNDLE=${CA_IN_CONTAINER}")       # curl / composer
    DOCKER_ARGS+=(-e "GIT_SSL_CAINFO=${CA_IN_CONTAINER}")       # git clone over https
    DOCKER_ARGS+=(-e "PIP_CERT=${CA_IN_CONTAINER}")             # pip
    DOCKER_ARGS+=(-e "REQUESTS_CA_BUNDLE=${CA_IN_CONTAINER}")   # python requests
    DOCKER_ARGS+=(-e "NODE_EXTRA_CA_CERTS=${CA_IN_CONTAINER}")  # node / npm
    DOCKER_ARGS+=(-e "CARGO_HTTP_CAINFO=${CA_IN_CONTAINER}")    # cargo
    DOCKER_ARGS+=(-e "BUNDLE_SSL_CA_CERT=${CA_IN_CONTAINER}")   # bundler / rubygems
  else
    echo "[run-in-docker] WARNING: LOCAL_DOCKER_CA_BUNDLE='${LOCAL_DOCKER_CA_BUNDLE}' not found -- skipping CA injection" >&2
  fi
fi

quote_arg() {
  if [[ "$1" =~ [[:space:]\&\|\;\$\(\)\{\}\<\>\`\\] ]]; then
    local escaped="${1//\\/\\\\}"
    escaped="${escaped//\"/\\\"}"
    escaped="${escaped//\$/\\\$}"
    escaped="${escaped//\`/\\\`}"
    printf '"%s"' "$escaped"
  else
    printf '%s' "$1"
  fi
}

# Build a redacted display version so the logged command never echoes secrets.
# For `-e KEY=VAL` we keep KEY but replace VAL with `***`. The COMMAND_ARGS
# (typically the bash heredoc body) is not redacted, so callers MUST avoid
# embedding secrets literally in the command body and must instead use env
# vars passed via -e.
DISPLAY_ARGS=()
redact_next=false
for arg in "${DOCKER_ARGS[@]}"; do
  if $redact_next; then
    redact_next=false
    if [[ "$arg" == *=* ]]; then
      DISPLAY_ARGS+=("$(quote_arg "${arg%%=*}=***")")
    else
      DISPLAY_ARGS+=("***")
    fi
    continue
  fi
  if [[ "$arg" == -e || "$arg" == --env ]]; then
    redact_next=true
    DISPLAY_ARGS+=("$(quote_arg "$arg")")
    continue
  fi
  DISPLAY_ARGS+=("$(quote_arg "$arg")")
done
DISPLAY_CMD_ARGS=()
for arg in "${COMMAND_ARGS[@]}"; do
  DISPLAY_CMD_ARGS+=("$(quote_arg "$arg")")
done

FULL_CMD="docker run ${DISPLAY_ARGS[*]} $IMAGE ${DISPLAY_CMD_ARGS[*]}"

# Log to stderr so callers can still capture the wrapped command's stdout.
# --banner-command replaces the command for a wrapped call (in_docker --secret-env)
# whose real command points at a staged directory that is gone after the run.
if [[ -n "$BANNER_COMMAND" ]]; then
  {
    echo "┌──────────────────────────────────────────────────────────────────"
    echo "│ Docker Command (credentials staged by the caller; not reproducible verbatim):"
    echo "│"
    echo "│   docker run ${DISPLAY_ARGS[*]} $IMAGE $BANNER_COMMAND"
    echo "│"
    echo "└──────────────────────────────────────────────────────────────────"
    echo ""
  } >&2
else
  {
    echo "┌──────────────────────────────────────────────────────────────────"
    echo "│ Docker Command (copy to reproduce locally):"
    echo "│"
    echo "│   $FULL_CMD"
    echo "│"
    echo "│ Or from repo root:"
    echo "│   cd $(pwd) && $FULL_CMD"
    echo "│"
    echo "└──────────────────────────────────────────────────────────────────"
    echo ""
  } >&2
fi

# ---------------------------------------------------------------------------
# Transient Maven Central failure -> Buildkite retry signal (CI only)
# ---------------------------------------------------------------------------
# A transient Maven Central network failure (Connection reset / timeout / 5xx)
# during artifact or plugin resolution kills the whole build with exit 1 -- the
# SAME code a genuine test/compile failure produces, so Buildkite cannot safely
# auto-retry on exit 1. To let CI self-heal a flaky-infra window WITHOUT masking
# real regressions, we detect the transient-transfer signature in the build
# output and remap ONLY that failure to a dedicated sentinel exit code
# (MAVEN_TRANSIENT_EXIT_CODE, default 42). The pipeline opts specific steps in to
# retry just that code, capped low (see the `- exit_status: 42` retry entries).
#
# The signature requires a Maven RESOLUTION phrase AND a NETWORK cause on the
# SAME line. That precision matters:
#   * a test that itself logs "Connection reset" (many MockServer tests do) has
#     no Maven-resolution phrase on that line -> NOT misclassified;
#   * a genuine missing-artifact / 404 ("could not be resolved", no network
#     cause) -> NOT retried (a real dependency bug must stay red).
# What a retry CAN mask: a Central outage long enough to also fail the retries
# (still ends red after `limit`). What it must NEVER mask: a test/compile/asset
# regression -- which cannot reach exit 42 because it never prints this line.
#
# Local runs (BUILDKITE unset) keep the original exec semantics and real exit
# code -- the remap is CI-only.
if [[ "${BUILDKITE:-}" != "true" ]]; then
  exec docker run "${DOCKER_ARGS[@]}" "$IMAGE" "${COMMAND_ARGS[@]}"
fi

RID_LOG="$(mktemp "${TMPDIR:-/tmp}/run-in-docker.XXXXXX")"
set +e
# Tee ONLY stdout to the classification log; stderr is left on its own fd so a
# caller that captures the wrapped command's stdout ($(run-in-docker ...) -- e.g.
# a build-classpath) is unaffected. Maven writes its transfer/resolution errors
# ("Could not transfer/resolve ... Connection reset") to STDOUT, so the signature
# is on this stream. A plain pipe is race-free (the shell waits for tee to exit
# before the grep below reads the file, unlike a >(process substitution)).
docker run "${DOCKER_ARGS[@]}" "$IMAGE" "${COMMAND_ARGS[@]}" | tee "$RID_LOG"
RID_RC=${PIPESTATUS[0]}
set -e

RID_SENTINEL="${MAVEN_TRANSIENT_EXIT_CODE:-42}"
# Maven-resolution phrase AND a transient network cause, on the same line.
RID_SIG='(Could not transfer artifact|Failed to read artifact descriptor|Could not resolve (dependencies|plugin)|Non-resolvable [A-Za-z ]*POM|Plugin .* or one of its dependencies could not be resolved).*(Connection reset|Connection timed out|Connection refused|Read timed out|Broken pipe|peer not authenticated|Received fatal alert|Remote host terminated|Premature end of (Content-Length|chunk)|Network is unreachable|Temporary failure in name resolution|Service Unavailable|status code: (429|50[0-9]))'
if [[ "$RID_RC" -ne 0 ]] && grep -Eq "$RID_SIG" "$RID_LOG"; then
  echo "+++ :maven: :recycle: Transient Maven Central transfer failure detected -- remapping exit ${RID_RC} to sentinel ${RID_SENTINEL} so Buildkite can retry this step (this is NOT a test/compile failure)" >&2
  RID_RC="$RID_SENTINEL"
fi
rm -f "$RID_LOG"
if [[ "$CACHE_SAVE" == "true" && "$RID_RC" -eq 0 ]]; then
  for cache_type in "${CACHE_TYPES[@]+"${CACHE_TYPES[@]}"}"; do
    dependency_cache save "$cache_type"
  done
fi
exit "$RID_RC"

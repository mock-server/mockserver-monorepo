#!/usr/bin/env bash
# shellcheck disable=SC2155

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" >/dev/null && pwd)"
source "${SCRIPT_DIR}/logging.sh"
source "${SCRIPT_DIR}/helm-deploy.sh"
source "${SCRIPT_DIR}/docker-compose.sh"

# SKIP_JAVA_BUILD=true ./integration_tests.sh
# SKIP_HELM_TESTS=true SKIP_JAVA_BUILD=true DOCKER_BUILD=true ./container_integration_tests/integration_tests.sh

# Variants whose Dockerfile now `COPY ca-bundle.pem` from the build context.
# A placeholder must exist before `docker build` or the COPY fails. The base
# docker/ context COPYs it too, but the smoke tests only build variant dirs;
# `local` and `aot` COPY it into their jarprep stage.
function variant_copies_ca_bundle() {
  case "$1" in
    root|snapshot|root-snapshot|clustered|graaljs|http3|local|aot) return 0 ;;
    *) return 1 ;;
  esac
}

# Ensure ${variant_dir}/ca-bundle.pem exists before a variant build. If
# MOCKSERVER_LOCAL_CA_BUNDLE points at a readable PEM (corporate proxy), copy it
# in; otherwise leave an empty placeholder (the Dockerfiles' `[ -s ]` guards make
# that a no-op). Echoes "true" if the harness created the file (caller removes it
# afterward) or "false" if it was already present / not needed.
function ensure_variant_ca_bundle() {
  local variant="$1" variant_dir="$2"
  local ca_bundle_path="${variant_dir}/ca-bundle.pem"
  if ! variant_copies_ca_bundle "${variant}" || [[ -f "${ca_bundle_path}" ]]; then
    echo "false"
    return 0
  fi
  if [[ -n "${MOCKSERVER_LOCAL_CA_BUNDLE:-}" && -r "${MOCKSERVER_LOCAL_CA_BUNDLE}" ]]; then
    cp "${MOCKSERVER_LOCAL_CA_BUNDLE}" "${ca_bundle_path}"
  else
    touch "${ca_bundle_path}"
  fi
  echo "true"
}

function build_docker() {
  runCommand "cd ${SCRIPT_DIR}"
  if [[ "${SKIP_JAVA_BUILD:-}" != "true" ]]; then
    runCommand "(cd ${SCRIPT_DIR}/../mockserver && ./mvnw -DskipTests=true package)"
  fi
  if [[ "${SKIP_DOCKER_BUILD_MOCKSERVER:-}" != "true" ]]; then
    runCommand "cp ${SCRIPT_DIR}/../mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar ${SCRIPT_DIR}/../docker/mockserver-netty-jar-with-dependencies.jar"
    # The base docker/Dockerfile now COPYs ca-bundle.pem; stage a placeholder
    # (or the corporate CA via MOCKSERVER_LOCAL_CA_BUNDLE) before building.
    local base_ca_created
    base_ca_created=$(ensure_variant_ca_bundle "root" "${SCRIPT_DIR}/../docker")
    runCommand "docker build --no-cache -t mockserver/mockserver:integration_testing --build-arg source=copy ${SCRIPT_DIR}/../docker"
    runCommand "rm ${SCRIPT_DIR}/../docker/mockserver-netty-jar-with-dependencies.jar"
    if [[ "${base_ca_created}" == "true" ]]; then
      rm -f "${SCRIPT_DIR}/../docker/ca-bundle.pem"
    fi
  fi
  if [[ "${SKIP_DOCKER_BUILD_MOCKSERVER:-}" != "true" ]]; then
    build_clustered_docker
    build_webhook_docker
  fi
  if [[ "${SKIP_DOCKER_REBUILD_CLIENT:-}" != "true" ]]; then
    runCommand "docker build -t mockserver/mockserver:integration_testing_client_curl -f ${SCRIPT_DIR}/client_docker_images/CurlClientDockerfile ${SCRIPT_DIR}/client_docker_images"
  fi
}

# Build the -clustered image variant that includes the Infinispan state
# backend module and its transitive dependencies. The /libs/* classpath
# glob in the ENTRYPOINT picks up these additional JARs at runtime.
function build_clustered_docker() {
  local clustered_dir="${SCRIPT_DIR}/../docker/clustered"
  local libs_dir="${clustered_dir}/libs"

  # The clustered image is assembled from Maven outputs: the locally-built fat
  # jar plus the Infinispan module's runtime classpath resolved via ./mvnw.
  # The CI container-tests step runs on a bare host (Docker only, no JDK, with
  # SKIP_JAVA_BUILD=true) where neither is present, so skip gracefully there —
  # the clustered smoke test then non-blocking-skips (image absent). This still
  # builds and smoke-tests fully in local dev where the reactor + JDK exist.
  if ! command -v java >/dev/null 2>&1; then
    printMessage "clustered: skipping build (no JDK on host — needs Maven to resolve the Infinispan classpath)"
    return 0
  fi

  # Copy fat jar
  local source_jar
  source_jar=$(ls "${SCRIPT_DIR}"/../mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar 2>/dev/null | head -1)
  if [[ -z "${source_jar}" ]]; then
    printMessage "clustered: skipping build (no local mockserver-netty fat jar — run a reactor package first)"
    return 0
  fi
  cp "${source_jar}" "${clustered_dir}/mockserver-netty-jar-with-dependencies.jar"

  # Copy infinispan module jar + its runtime dependencies (excluding org.mock-server)
  rm -rf "${libs_dir}" && mkdir -p "${libs_dir}"
  runCommand "(cd ${SCRIPT_DIR}/../mockserver && ./mvnw -pl mockserver-state-infinispan dependency:copy-dependencies -DincludeScope=runtime -DexcludeGroupIds=org.mock-server -DoutputDirectory=${libs_dir} -q)"
  cp "${SCRIPT_DIR}"/../mockserver/mockserver-state-infinispan/target/mockserver-state-infinispan-*.jar "${libs_dir}/"

  # The clustered Dockerfile's tcnative stage COPYs ca-bundle.pem; stage a
  # placeholder (or the corporate CA via MOCKSERVER_LOCAL_CA_BUNDLE) first.
  local ca_bundle_created
  ca_bundle_created=$(ensure_variant_ca_bundle "clustered" "${clustered_dir}")

  runCommand "docker build --no-cache -t mockserver/mockserver:integration_testing_clustered ${clustered_dir}"

  # Clean up build context
  rm -f "${clustered_dir}/mockserver-netty-jar-with-dependencies.jar"
  rm -rf "${libs_dir}"
  if [[ "${ca_bundle_created}" == "true" ]]; then
    rm -f "${clustered_dir}/ca-bundle.pem"
  fi
}

# Build the admission-webhook image (mockserver/mockserver-webhook) so the
# helm_sidecar_injection e2e runs blocking in local dev too. Symmetric with
# build_clustered_docker: needs the mockserver-k8s-webhook fat jar, which the
# reactor `package` in build_docker() produces. Without this the webhook image
# was never built by the harness at all — so the sidecar case always skipped,
# even locally. docker/webhook is single-stage distroless and does NOT COPY a
# ca-bundle, so no ensure_variant_ca_bundle staging is needed.
function build_webhook_docker() {
  local webhook_dir="${SCRIPT_DIR}/../docker/webhook"

  # Same host-JDK guard as build_clustered_docker: the CI helm step runs with no
  # JDK and builds this image itself from a downloaded jar, so skip here.
  if ! command -v java >/dev/null 2>&1; then
    printMessage "webhook: skipping build (no JDK on host — the CI helm step builds this image from a jar artifact)"
    return 0
  fi

  local source_jar
  source_jar=$(ls "${SCRIPT_DIR}"/../mockserver/mockserver-k8s-webhook/target/mockserver-k8s-webhook-*-jar-with-dependencies.jar 2>/dev/null | head -1)
  if [[ -z "${source_jar}" ]]; then
    printMessage "webhook: skipping build (no local mockserver-k8s-webhook fat jar — run a reactor package first)"
    return 0
  fi
  cp "${source_jar}" "${webhook_dir}/mockserver-webhook.jar"

  runCommand "docker build --no-cache -t ${WEBHOOK_IMAGE_REPO}:integration_testing ${webhook_dir}"

  rm -f "${webhook_dir}/mockserver-webhook.jar"
}

# test <test_case> [non_blocking]
#
# Runs a test case. Blocking by default: an unaccounted-for non-zero exit is
# recorded as a FAILURE. Pass "non_blocking" as the second argument for a test
# that is deliberately advisory (new/known-flaky), which routes the same
# unaccounted-for exit to the WARN log instead.
#
# The opt-out MUST be explicit. A caller-side `|| true` cannot suppress it:
# the record is written as a side effect before this function returns, so the
# caller never gets the chance. Making the mode an argument keeps the intent
# visible at the call site instead of silently defeated by it.
function test() {
  export TEST_CASE="${1}"
  local blocking_mode="${2:-blocking}"
  printMessage "Running Test: \"${TEST_CASE}\""
  local rc=0
  local test_log="${TMPDIR:-/tmp}/mockserver-integration-test-${TEST_CASE}.log"
  runCommand "cd ${SCRIPT_DIR}/${TEST_CASE}" || rc=1
  if [[ ${rc} -eq 0 ]]; then
    # tee keeps the output streaming live to the console (unchanged for a passing run) while
    # ALSO capturing it, so the no-result branch below can show what the test was doing when it
    # crashed. The `{ ...; } || true` wrapper suspends `set -e` across the pipeline so a failing
    # test cannot abort test() before its status is read; PIPESTATUS[0] is runCommand's own exit
    # (tee always succeeds), so the pass/fail signal is exactly what it was before the tee.
    { runCommand "./integration_test.sh" 2>&1 | tee "${test_log}"; rc=${PIPESTATUS[0]}; } || true
  fi
  runCommand "cd ${SCRIPT_DIR}" || true

  # Fail closed. Every per-test result is recorded by the test script itself
  # calling logTestResult. A script that crashed BEFORE reaching that call —
  # missing file, `set -e` abort in start-up, docker unavailable, a bad `cd` —
  # left no record at all: not in PASS_LOG, not in FAIL_LOG. run_all_tests only
  # sets EXIT_CODE from a non-empty FAIL_LOG, so the suite exited 0 having run
  # nothing.
  #
  # The check is on RESULT PRESENCE, not on the exit code. Keying it off a
  # non-zero exit still let a test that exited 0 without recording anything
  # disappear from the summary — the suite stayed green and simply reported one
  # test fewer, which is build #129's failure mode in a narrower form (a run of
  # "PASSED: 1" while another test vanished entirely). A test that produced no
  # result did not pass, whatever it exited with.
  #
  # The trailing delimiter in the pattern stops a name matching a longer one
  # that starts with it (e.g. docker_variant_smoke_root vs ...root-snapshot).
  local recorded=false
  local result_log
  for result_log in "${PASS_LOG_FILE}" "${FAIL_LOG_FILE}" "${SKIP_LOG_FILE}" "${WARN_LOG_FILE}"; do
    if grep -qE -- "- ${TEST_CASE}(:|\$|[^[:alnum:]_-])" "${result_log}" 2>/dev/null; then
      recorded=true
      break
    fi
  done

  if [[ "${recorded}" != "true" ]]; then
    # The script crashed before reaching logTestResult, so the summary alone would say only
    # "exited N without a result". Show the tail of its captured output so the crash
    # self-explains — the failing command is almost always in the last few lines. Strictly
    # additive: this prints only and touches neither the PASS/FAIL logs nor the exit status,
    # so it cannot become a false-green vector. Bounded to 40 lines to respect the noise budget.
    if [[ -s "${test_log:-}" ]]; then
      printMessage "--- last output before \"${TEST_CASE}\" crashed (no result recorded) ---"
      tail -n 40 "${test_log}" || true
      printMessage "--- end of \"${TEST_CASE}\" output ---"
    fi
    local how="exited ${rc}"
    if [[ "${blocking_mode}" == "non_blocking" ]]; then
      printMessageWithColourAndBorders >&2 "Warning (non-blocking): ${TEST_CASE} ${how} without recording a result" "\e[0;33m"
      printMessageWithColour >&2 "  - ${TEST_CASE} (${how} without recording a result)" "\e[0;33m" >>"${WARN_LOG_FILE}" 2>&1
    else
      printFailureMessage "Failed (test script ${how} without recording a result): ${TEST_CASE}"
      printPlainFailureMessage "  - ${TEST_CASE} (${how} without recording a result)" >>"${FAIL_LOG_FILE}" 2>&1
      rc=1
    fi
  fi
  return ${rc}
}

# 5c.4 - build each published variant Dockerfile and confirm it boots and
# responds to /mockserver/status. Catches Dockerfile drift early without
# running the whole test suite per variant.
#
# Uses --build-arg source=copy + a locally-built JAR rather than the default
# source=download path. The download path resolves the latest RELEASE from
# Maven Central, which doesn't exist for in-development versions (e.g.,
# 6.1.1-SNAPSHOT before publication) and would always 404 in CI.
# The -graaljs image exists to evaluate JavaScript response templates. Assert the
# RENDERED body: a template that fails to evaluate can still return a response, so
# only the computed value distinguishes a working engine from a broken one.
# The -aot image exists to start fast from a baked Leyden AOT cache. If the cache is
# rejected the JVM silently falls back to a cold start — the server still works, so no
# readiness or functional check can see it. -Xlog:aot makes the load observable.
function assert_aot_cache_loaded() {
  local container="$1"
  local logs
  logs=$(docker logs "${container}" 2>&1 || true)
  if grep -q "Opened AOT cache" <<<"${logs}"; then
    printPassMessage "aot: baked AOT cache loaded (no cold-start fallback)"
    return 0
  fi
  printFailureMessage "aot: JVM did not report \"Opened AOT cache\" — the baked cache was rejected and the image silently cold-started"
  printMessage "aot-related log lines:"
  grep -i 'aot\|cds\|shared' <<<"${logs}" | head -20 || true
  return 1
}

function assert_javascript_template() {
  local host_port="$1" container="$2"
  local expectation
  expectation='{"httpRequest":{"path":"/js-template"},"httpResponseTemplate":{"templateType":"JAVASCRIPT","template":"return { statusCode: 200, body: request.method + request.path };"}}'
  if ! curl -sf -o /dev/null -X PUT "http://localhost:${host_port}/mockserver/expectation" -d "${expectation}"; then
    printFailureMessage "graaljs: could not create the JavaScript-template expectation"
    runCommand "docker logs ${container} 2>&1 || true"
    return 1
  fi
  local body
  body=$(curl -sf "http://localhost:${host_port}/js-template" 2>/dev/null || true)
  if [[ "${body}" != "GET/js-template" ]]; then
    printFailureMessage "graaljs: JavaScript template did not evaluate - got \"${body}\", expected \"GET/js-template\""
    runCommand "docker logs ${container} 2>&1 || true"
    return 1
  fi
  printPassMessage "graaljs: JavaScript response template evaluated correctly"
  return 0
}

# docker/local is the published standard image, built from the shaded mockserver-netty-docker jar
# (as the release and snapshot steps build it); it fails closed without that jar's QUIC native, which
# the default fat jar lacks. The other variants smoke-test the default fat jar.
function variant_jar_name() {
  if [[ "$1" == "local" ]]; then echo "mockserver-netty-docker"; else echo "mockserver-netty fat"; fi
}

function variant_source_jar() {
  local jar
  if [[ "$1" == "local" ]]; then
    for jar in "${SCRIPT_DIR}"/../mockserver/mockserver-netty-docker/target/mockserver-netty-docker-*.jar; do
      case "$(basename "${jar}")" in *-sources.jar|*-javadoc.jar|original-*) continue ;; esac
      [[ -f "${jar}" ]] && { echo "${jar}"; return 0; }
    done
    return 0
  fi
  jar=$(ls "${SCRIPT_DIR}"/../mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar 2>/dev/null | head -1)
  if [[ -z "${jar}" ]]; then
    jar=$(ls "${SCRIPT_DIR}"/../docker/mockserver-netty-jar-with-dependencies.jar 2>/dev/null | head -1)
  fi
  echo "${jar}"
}

function smoke_test_variant() {
  local variant="$1"
  local tag="mockserver/mockserver:smoke-${variant}"
  local container="smoke-${variant}"
  local variant_dir="${SCRIPT_DIR}/../docker/${variant}"
  local jar_path="${variant_dir}/mockserver-netty-jar-with-dependencies.jar"
  export TEST_CASE="docker_variant_smoke_${variant}"
  printMessage "Smoke test: variant \"${variant}\""

  local exit_code=0
  # The variant's jar (see variant_source_jar); build_docker() already copies the fat jar to
  # docker/ as part of the main image build, so reuse it for variants too.
  local source_jar
  source_jar=$(variant_source_jar "${variant}")
  if [[ -z "${source_jar}" ]]; then
    printFailureMessage "${variant}: no local $(variant_jar_name "${variant}") jar found - build it first"
    logTestResult "1" "${TEST_CASE}"
    return 1
  fi

  cp "${source_jar}" "${jar_path}"
  # local takes the JAR straight from its build context (no source ARG);
  # the other variant Dockerfiles take --build-arg source=copy.
  local build_args=""
  if [[ "${variant}" != "local" ]]; then
    build_args="--build-arg source=copy"
  fi

  # Every variant whose Dockerfile COPYs ca-bundle.pem from the build context
  # (for corporate proxies) needs a placeholder before `docker build`, so the
  # COPY instruction does not fail. Populated from MOCKSERVER_LOCAL_CA_BUNDLE
  # when set, otherwise an empty no-op file.
  local ca_bundle_path="${variant_dir}/ca-bundle.pem"
  local ca_bundle_created
  ca_bundle_created=$(ensure_variant_ca_bundle "${variant}" "${variant_dir}")

  runCommand "docker build ${build_args} -t ${tag} ${variant_dir}" || exit_code=1
  rm -f "${jar_path}"
  if [[ "${ca_bundle_created}" == "true" ]]; then
    rm -f "${ca_bundle_path}"
  fi

  if [[ ${exit_code} -eq 0 ]]; then
    runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
    runCommand "docker run -d --name ${container} -p 0:1080 ${tag}"
    local host_port
    host_port=$(docker port "${container}" 1080 2>/dev/null | head -1 | awk -F: '{print $NF}')
    if [[ -z "${host_port}" ]]; then
      printFailureMessage "${variant}: could not resolve host port for container ${container}"
      exit_code=1
    else
      # poll for readiness up to ~30s instead of a fixed sleep
      local i status
      status=000
      for i in $(seq 1 15); do
        status=$(curl -sf -o /dev/null -w '%{http_code}' -X PUT "http://localhost:${host_port}/mockserver/status" 2>/dev/null || echo "000")
        [[ "${status}" == "200" ]] && break
        sleep 2
      done
      if [[ "${status}" != "200" ]]; then
        printFailureMessage "${variant}: /mockserver/status returned \"${status}\" (expected 200)"
        runCommand "docker logs ${container} 2>&1 || true"
        exit_code=1
      fi
      # Answering /mockserver/status proves the container boots, NOT that the feature
      # the variant exists for works — the clustered image passed readiness while
      # Infinispan could not start at all. Exercise what distinguishes this variant.
      if [[ ${exit_code} -eq 0 ]] && [[ "${variant}" == "graaljs" ]]; then
        assert_javascript_template "${host_port}" "${container}" || exit_code=1
      fi
      # The standard image serves HTTP/3 (read-only, from its /usr/lib QUIC native).
      if [[ ${exit_code} -eq 0 ]] && [[ "${variant}" == "local" ]]; then
        runCommand "${SCRIPT_DIR}/../.buildkite/scripts/steps/docker-http3-smoke.sh ${tag}" || exit_code=1
      fi
    fi
    runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  fi
  runCommand "docker rmi -f ${tag} >/dev/null 2>&1 || true"
  logTestResult "${exit_code}" "${TEST_CASE}"
  return ${exit_code}
}

# Non-blocking wrapper around smoke_test_variant: records pass/fail to the
# warning log instead of the failure log. Used for new/unproven variants
# whose CI behaviour has not yet been validated.
function smoke_test_variant_nonblocking() {
  local variant="$1"
  local tag="mockserver/mockserver:smoke-${variant}"
  local container="smoke-${variant}"
  local variant_dir="${SCRIPT_DIR}/../docker/${variant}"
  local jar_path="${variant_dir}/mockserver-netty-jar-with-dependencies.jar"
  export TEST_CASE="docker_variant_smoke_${variant}"
  printMessage "Smoke test (non-blocking): variant \"${variant}\""

  local exit_code=0
  local source_jar
  source_jar=$(variant_source_jar "${variant}")
  if [[ -z "${source_jar}" ]]; then
    printFailureMessage "${variant}: no local $(variant_jar_name "${variant}") jar found - build it first"
    logTestResultNonBlocking "1" "${TEST_CASE}"
    return 0
  fi

  cp "${source_jar}" "${jar_path}"
  local build_args=""
  if [[ "${variant}" != "local" ]]; then
    build_args="--build-arg source=copy"
  fi

  # Same ca-bundle.pem placeholder requirement as smoke_test_variant.
  local ca_bundle_path="${variant_dir}/ca-bundle.pem"
  local ca_bundle_created
  ca_bundle_created=$(ensure_variant_ca_bundle "${variant}" "${variant_dir}")

  runCommand "docker build ${build_args} -t ${tag} ${variant_dir}" || exit_code=1
  rm -f "${jar_path}"
  if [[ "${ca_bundle_created}" == "true" ]]; then
    rm -f "${ca_bundle_path}"
  fi

  if [[ ${exit_code} -eq 0 ]]; then
    runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
    # -Xlog:aot makes the AOT cache load observable; the entrypoint does not enable it,
    # and without it a rejected cache is indistinguishable from a loaded one.
    local run_env=""
    [[ "${variant}" == "aot" ]] && run_env='-e JAVA_TOOL_OPTIONS=-Xlog:aot=info'
    runCommand "docker run -d --name ${container} -p 0:1080 ${run_env} ${tag}"
    local host_port
    host_port=$(docker port "${container}" 1080 2>/dev/null | head -1 | awk -F: '{print $NF}')
    if [[ -z "${host_port}" ]]; then
      printFailureMessage "${variant}: could not resolve host port for container ${container}"
      exit_code=1
    else
      local i status
      status=000
      for i in $(seq 1 15); do
        status=$(curl -sf -o /dev/null -w '%{http_code}' -X PUT "http://localhost:${host_port}/mockserver/status" 2>/dev/null || echo "000")
        [[ "${status}" == "200" ]] && break
        sleep 2
      done
      if [[ "${status}" != "200" ]]; then
        printFailureMessage "${variant}: /mockserver/status returned \"${status}\" (expected 200)"
        runCommand "docker logs ${container} 2>&1 || true"
        exit_code=1
      fi
      if [[ ${exit_code} -eq 0 ]] && [[ "${variant}" == "aot" ]]; then
        assert_aot_cache_loaded "${container}" || exit_code=1
      fi
    fi
    runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  fi
  runCommand "docker rmi -f ${tag} >/dev/null 2>&1 || true"
  # Non-blocking: warn on failure, never set EXIT_CODE
  logTestResultNonBlocking "${exit_code}" "${TEST_CASE}"
  return 0
}

# Assert that the Docker HEALTHCHECK defined in the Dockerfile (the bundled
# /mockserver-healthcheck probe) transitions the container to "healthy" within a
# reasonable period.
function test_healthcheck() {
  local tag="mockserver/mockserver:integration_testing"
  local container="healthcheck-test"
  export TEST_CASE="docker_healthcheck"
  printMessage "Test: HEALTHCHECK reaches healthy"

  local exit_code=0
  runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  runCommand "docker run -d --name ${container} -p 0:1080 ${tag}"

  # Poll docker inspect for health status; the Dockerfile defines
  # --start-period=120s --interval=10s --retries=3 so we allow up to 180s.
  local i health_status
  health_status="starting"
  for i in $(seq 1 60); do
    health_status=$(docker inspect --format='{{.State.Health.Status}}' "${container}" 2>/dev/null || echo "unknown")
    if [[ "${health_status}" == "healthy" ]]; then
      break
    elif [[ "${health_status}" == "unhealthy" ]]; then
      printFailureMessage "HEALTHCHECK: container reached 'unhealthy' state"
      runCommand "docker logs ${container} 2>&1 || true"
      exit_code=1
      break
    fi
    sleep 3
  done
  if [[ "${exit_code}" -eq 0 && "${health_status}" != "healthy" ]]; then
    printFailureMessage "HEALTHCHECK: container never reached 'healthy' (last status: ${health_status})"
    runCommand "docker logs ${container} 2>&1 || true"
    exit_code=1
  fi

  runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  logTestResult "${exit_code}" "${TEST_CASE}"
  return ${exit_code}
}

# Assert that the default (distroless) image runs as a non-root user.
# The Dockerfile declares USER nonroot but this has never been asserted by a test.
function test_nonroot_user() {
  local tag="mockserver/mockserver:integration_testing"
  local container="nonroot-test"
  export TEST_CASE="docker_nonroot_user"
  printMessage "Test: non-root runtime user"

  local exit_code=0
  # The distroless image has no shell or 'id' binary, so we inspect the
  # image's configured user via docker inspect.
  local configured_user
  configured_user=$(docker inspect --format='{{.Config.User}}' "${tag}" 2>/dev/null || echo "")
  if [[ -z "${configured_user}" || "${configured_user}" == "root" || "${configured_user}" == "0" ]]; then
    printFailureMessage "Non-root user: image configured user is '${configured_user}' (expected non-root)"
    exit_code=1
  fi

  # Also verify at runtime: start the container and check the process UID.
  runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  runCommand "docker run -d --name ${container} -p 0:1080 ${tag}"
  # Wait briefly for the process to start
  sleep 2
  # docker top (default ps format) outputs UID as the first column. On
  # distroless, the nonroot user maps to UID 65532. Reject UID 0 (root).
  local runtime_uid
  runtime_uid=$(docker top "${container}" 2>/dev/null | tail -1 | awk '{print $1}')
  if [[ "${runtime_uid}" == "0" || "${runtime_uid}" == "root" ]]; then
    printFailureMessage "Non-root user: runtime process UID is '${runtime_uid}' (expected non-root)"
    exit_code=1
  fi

  runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  logTestResult "${exit_code}" "${TEST_CASE}"
  return ${exit_code}
}

# Build the main Dockerfile for linux/arm64 via buildx. Release builds publish
# linux/amd64+arm64 via buildx but the test suite only exercises the host arch.
# This build gate catches arch-specific Dockerfile breakage early; booting the
# image would require QEMU user-mode emulation, so a successful BUILD is the
# gate — no runtime assertion.
function test_arm64_build_gate() {
  local docker_dir="${SCRIPT_DIR}/../docker"
  local jar_path="${docker_dir}/mockserver-netty-jar-with-dependencies.jar"
  export TEST_CASE="docker_arm64_build_gate"
  printMessage "Test (non-blocking): arm64 build gate (buildx --platform linux/arm64)"

  local exit_code=0
  # Locate locally-built fat jar
  local source_jar
  source_jar=$(ls "${SCRIPT_DIR}"/../mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar 2>/dev/null | head -1)
  if [[ -z "${source_jar}" ]]; then
    printFailureMessage "arm64 build gate: no local mockserver-netty fat jar found"
    logTestResultNonBlocking "1" "${TEST_CASE}"
    return 0
  fi

  cp "${source_jar}" "${jar_path}"
  # The base docker/Dockerfile now COPYs ca-bundle.pem; stage a placeholder
  # (or the corporate CA via MOCKSERVER_LOCAL_CA_BUNDLE) before building.
  local ca_bundle_created
  ca_bundle_created=$(ensure_variant_ca_bundle "root" "${docker_dir}")
  # Ensure a buildx builder that supports cross-platform builds exists.
  # The default "docker" driver cannot cross-build; create a
  # "docker-container" driver builder matching the release pipeline.
  docker buildx create --use --name multiarch --driver docker-container 2>/dev/null \
    || docker buildx use multiarch 2>/dev/null \
    || true
  # Build-only (no --load / --push) for linux/arm64.
  runCommand "docker buildx build --platform linux/arm64 --build-arg source=copy -t mockserver/mockserver:arm64-gate ${docker_dir}" || exit_code=1
  rm -f "${jar_path}"
  if [[ "${ca_bundle_created}" == "true" ]]; then
    rm -f "${docker_dir}/ca-bundle.pem"
  fi

  # Non-blocking: a failure here warns but does not fail the pipeline.
  logTestResultNonBlocking "${exit_code}" "${TEST_CASE}"
  return 0
}

# The -http3 variant is layered FROM the image under test, so it is built over the default image
# here and exercised with a real HTTP/3 request. Non-blocking until proven on a CI agent (it
# downloads the QUIC native from Maven Central here, because this default image carries no bundled one).
function smoke_test_http3() {
  local tag="mockserver/mockserver:smoke-http3"
  local base="mockserver/mockserver:integration_testing"
  local variant_dir="${SCRIPT_DIR}/../docker/http3"
  export TEST_CASE="docker_variant_smoke_http3"
  printMessage "Smoke test (non-blocking): variant \"http3\""

  local exit_code=0 ca_bundle_created
  ca_bundle_created=$(ensure_variant_ca_bundle "http3" "${variant_dir}")
  runCommand "docker build --build-arg BASE_IMAGE=${base} -t ${tag} ${variant_dir}" || exit_code=1
  if [[ "${ca_bundle_created}" == "true" ]]; then
    rm -f "${variant_dir}/ca-bundle.pem"
  fi
  if [[ ${exit_code} -eq 0 ]]; then
    runCommand "${SCRIPT_DIR}/../.buildkite/scripts/steps/docker-http3-smoke.sh ${tag}" || exit_code=1
  fi
  runCommand "docker rmi -f ${tag} >/dev/null 2>&1 || true"
  logTestResultNonBlocking "${exit_code}" "${TEST_CASE}"
  return 0
}

# Smoke test for the -clustered image variant. Uses the image already built
# by build_clustered_docker() — no rebuild needed. Verifies the container
# starts and /mockserver/status responds 200 (Infinispan boots in LOCAL
# mode when MOCKSERVER_CLUSTER_ENABLED is not set).
function smoke_test_clustered() {
  local tag="mockserver/mockserver:integration_testing_clustered"
  local container="smoke-clustered"
  export TEST_CASE="docker_variant_smoke_clustered"
  printMessage "Smoke test: clustered variant"

  local exit_code=0
  # Verify the image exists (build_clustered_docker should have created it)
  if ! docker image inspect "${tag}" >/dev/null 2>&1; then
    printFailureMessage "clustered: image ${tag} not found — was build_clustered_docker() skipped?"
    logTestResultNonBlocking "1" "${TEST_CASE}"
    return 0
  fi

  runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  # Boot with stateBackend=infinispan but cluster disabled (LOCAL mode) —
  # validates that the Infinispan module is on the classpath and loads.
  runCommand "docker run -d --name ${container} -p 0:1080 -e MOCKSERVER_STATE_BACKEND=infinispan ${tag}"
  local host_port
  host_port=$(docker port "${container}" 1080 2>/dev/null | head -1 | awk -F: '{print $NF}')
  if [[ -z "${host_port}" ]]; then
    printFailureMessage "clustered: could not resolve host port for container ${container}"
    exit_code=1
  else
    local i status
    status=000
    for i in $(seq 1 15); do
      status=$(curl -sf -o /dev/null -w '%{http_code}' -X PUT "http://localhost:${host_port}/mockserver/status" 2>/dev/null || echo "000")
      [[ "${status}" == "200" ]] && break
      sleep 2
    done
    if [[ "${status}" != "200" ]]; then
      printFailureMessage "clustered: /mockserver/status returned \"${status}\" (expected 200)"
      runCommand "docker logs ${container} 2>&1 || true"
      exit_code=1
    fi
  fi
  runCommand "docker rm -f ${container} >/dev/null 2>&1 || true"
  # Non-blocking: new variant, does not fail the pipeline
  logTestResultNonBlocking "${exit_code}" "${TEST_CASE}"
  return 0
}

# Verify every test case declared in expected_tests.manifest that was expected
# to run for THIS invocation actually produced a result, and that every recorded
# result was declared. The manifest is the single source of truth (see the file
# header for the full rationale). This closes a coverage-loss blind spot that
# the per-test result-presence check in test()/logTestResult cannot: that check
# only fires for a test that was INVOKED, so a test case silently DELETED from
# run_all_tests() records nothing and the suite stays green having run one test
# fewer. Called from run_all_tests() in the summary phase while all four result
# logs still exist. Sets EXIT_CODE=1 (fail closed) on any violation.
function assert_manifest_complete() {
  local manifest="${SCRIPT_DIR}/expected_tests.manifest"
  if [[ ! -f "${manifest}" ]]; then
    printFailureMessage "Expected-test manifest not found: ${manifest} — cannot verify test coverage. Failing closed."
    EXIT_CODE=1
    return 0
  fi

  # Which manifest groups were expected to run, derived from the SAME skip flags
  # run_all_tests() branches on, so the guard tracks the harness exactly.
  local docker_active=false docker_variant_active=false
  local helm_active=false helm_clustered_active=false
  if [[ "${SKIP_ALL_TESTS:-}" != "true" ]]; then
    [[ "${SKIP_DOCKER_TESTS:-}" != "true" ]] && docker_active=true
    [[ "${docker_active}" == "true" && "${SKIP_VARIANT_TESTS:-}" != "true" ]] && docker_variant_active=true
    [[ "${SKIP_HELM_TESTS:-}" != "true" ]] && helm_active=true
    [[ "${helm_active}" == "true" && "${SKIP_CLUSTERED_TEST:-}" != "true" ]] && helm_clustered_active=true
  fi

  # All recorded result lines with ANSI colour codes stripped (the logs are
  # written through printf "${COLOUR}...", so raw lines are ESC-wrapped). Used
  # for both the presence checks and the recorded-name extraction below.
  local all_results
  all_results=$(cat "${PASS_LOG_FILE}" "${FAIL_LOG_FILE}" "${SKIP_LOG_FILE}" "${WARN_LOG_FILE}" 2>/dev/null \
    | sed $'s/\x1b\\[[0-9;]*m//g')

  local violations=0
  local declared_names=" "
  local group name group_active

  # ---- Direction 1: every ACTIVE manifest entry produced a result ----
  # Also accumulate the full declared-name set (all groups) for direction 2.
  while read -r group name; do
    [[ -z "${group}" || "${group}" == \#* ]] && continue
    declared_names="${declared_names}${name} "
    case "${group}" in
      docker)          group_active="${docker_active}" ;;
      docker_variant)  group_active="${docker_variant_active}" ;;
      helm)            group_active="${helm_active}" ;;
      helm_clustered)  group_active="${helm_clustered_active}" ;;
      *)
        printFailureMessage "Manifest entry '${name}' has unknown group '${group}' — fix the manifest. Failing closed."
        violations=$((violations + 1))
        continue
        ;;
    esac
    [[ "${group_active}" != "true" ]] && continue
    # Same trailing-delimiter presence test as test(): a name cannot match a
    # longer name that merely starts with it (root vs root-snapshot).
    if ! grep -qE -- "- ${name}(:|\$|[^[:alnum:]_-])" <<<"${all_results}"; then
      printFailureMessage "Manifest-declared test case produced NO result: \"${name}\" (group: ${group}) — a test case was deleted or a skip flag drifted without updating the manifest. Failing closed."
      violations=$((violations + 1))
    fi
  done < "${manifest}"

  # ---- Direction 2: every RECORDED result is declared in the manifest ----
  # A recorded result means the test ran, so it must be declared somewhere in
  # the manifest (regardless of group activeness). Catches a test case added to
  # the harness without a matching manifest line.
  local recorded
  while read -r recorded; do
    [[ -z "${recorded}" ]] && continue
    if [[ "${declared_names}" != *" ${recorded} "* ]]; then
      printFailureMessage "Recorded test result \"${recorded}\" is not declared in the manifest (${manifest}) — add it. Failing closed."
      violations=$((violations + 1))
    fi
  done < <(printf '%s\n' "${all_results}" | sed -n 's/^[[:space:]]*- \([A-Za-z0-9_-]*\).*/\1/p' | sort -u)

  if [[ ${violations} -gt 0 ]]; then
    printFailureMessage "Expected-test manifest check FAILED with ${violations} violation(s)."
    EXIT_CODE=1
  else
    printMessage "Expected-test manifest check passed."
  fi
  return 0
}

# ---------------------------------------------------------------------------
# Image availability + deterministic k3d import helpers.
#
# The clustered (-clustered) and admission-webhook images are Java artefacts
# built only when a JDK + Maven reactor is present (build_clustered_docker and
# the webhook Dockerfile). The CI helm step runs with SKIP_JAVA_BUILD=true on an
# agent WITHOUT a JDK, so neither image is built there. The three k8s cases that
# depend on them (helm_sidecar_injection, helm_clustered_convergence,
# helm_jgroups_dns_ping) therefore record an honest SKIP when their image is
# absent, and run BLOCKING when it is present — a genuine failure reds the suite.
# This keeps them real gates locally (and in any CI that builds the images)
# without a false red on the current CI helm step.
CLUSTERED_IMAGE="mockserver/mockserver:integration_testing_clustered"
WEBHOOK_IMAGE_REPO="mockserver/mockserver-webhook"

# Fail-closed switches for CI. Off by default so a local run WITHOUT the images
# degrades to an honest SKIP (right off-CI). The CI helm step BUILDS both images
# and sets these to true, so an absent image there is a FAILURE, not a skip — a
# skip that masquerades as coverage is exactly what these remove. Belt-and-braces
# with the helm step's own fail-closed jar download: even if the gate flags were
# the only thing standing, a missing image reds the build.
REQUIRE_CLUSTERED_IMAGE="${REQUIRE_CLUSTERED_IMAGE:-false}"
REQUIRE_WEBHOOK_IMAGE="${REQUIRE_WEBHOOK_IMAGE:-false}"

function clustered_image_available() {
  docker image inspect "${CLUSTERED_IMAGE}" >/dev/null 2>&1
}

# Echoes the first concrete (non-<none>) local tag of the webhook handler image,
# or nothing. The webhook image tag tracks the project version, so it is resolved
# from the image itself rather than hard-coded (which would drift every release).
function webhook_image_ref() {
  docker images "${WEBHOOK_IMAGE_REPO}" --format '{{.Repository}}:{{.Tag}}' 2>/dev/null \
    | grep -v ':<none>$' | head -1
}

function webhook_image_available() {
  [[ -n "$(webhook_image_ref)" ]]
}

# Deterministically import a host image into the k3d node and PROVE it landed in
# the node's containerd before any deploy relies on it (pods use pullPolicy=Never
# / IfNotPresent with no registry). Replaces the old
# `k3d image import ... 2>/dev/null || true`, whose swallowed failure was the
# root of the flaky "image-import race": a silently-missing image surfaced later
# and confusingly as ErrImageNeverPull / a formation timeout. Returns non-zero on
# any import or verification failure so the caller can record a real result.
function import_image_into_k3d() {
  local image="$1"
  local node="k3d-${CLUSTER_NAME}-server-0"
  # A concrete tag string to grep for in `crictl images` (unique enough on its
  # own: `integration_testing_clustered`, `7.5.1-SNAPSHOT`, ...).
  local tag="${image##*:}"

  if ! runCommand "k3d image import --cluster ${CLUSTER_NAME} ${image}"; then
    printFailureMessage "k3d image import FAILED for ${image} (not swallowed) — dependent case cannot run"
    return 1
  fi

  # Assert presence in the node's containerd. k3d nodes ship crictl. Poll briefly
  # because import registration can lag the CLI returning.
  local attempts=15
  for _ in $(seq 1 "${attempts}"); do
    if grep -q -- "${tag}" <<<"$(docker exec "${node}" crictl images 2>/dev/null || true)"; then
      printMessage "Verified image present in ${node}: ${image}"
      return 0
    fi
    sleep 1
  done
  printFailureMessage "image ${image} NOT visible in ${node} containerd after import (tag '${tag}')"
  docker exec "${node}" crictl images 2>/dev/null | tail -20 >&2 || true
  return 1
}

# A non-blocking warning never fails the step, so on Buildkite it is also raised as a build
# annotation: a log line alone let a test record no result on every master run unnoticed.
function annotate_warnings() {
  local warn_log="$1" lines
  [[ -n "${BUILDKITE_JOB_ID:-}" ]] && command -v buildkite-agent >/dev/null 2>&1 || return 0
  # Bounded below Buildkite's annotation size limit; backticks swapped so a line cannot close the fence.
  lines="$(sed $'s/\x1b\\[[0-9;]*m//g' "${warn_log}" | tr '`' "'")"
  {
    printf '**%s: %s non-blocking warning(s).** These warnings do not fail the step; each line is a test that failed or measured nothing.\n\n' \
      "${BUILDKITE_LABEL:-container integration tests}" "$(grep -c . <<<"${lines}")"
    printf '```\n%s\n```\n' "$(head -c 60000 <<<"${lines}")"
  } | buildkite-agent annotate --style warning --context "container-integration-warnings-${BUILDKITE_JOB_ID}" || true
}

function run_all_tests() {
  export PASS_LOG_FILE=$(mktemp)
  export FAIL_LOG_FILE=$(mktemp)
  export WARN_LOG_FILE=$(mktemp)
  export SKIP_LOG_FILE=$(mktemp)

  if [[ "${SKIP_ALL_TESTS:-}" != "true" ]]; then
    set +euo pipefail
    if [[ "${SKIP_DOCKER_TESTS:-}" != "true" ]]; then
      # docker compose test
      test "docker_compose_forward_with_override"
      test "docker_compose_remote_host_and_port_by_environment_variable"
      test "docker_compose_server_port_by_command"
      test "docker_compose_server_port_by_environment_variable_long_name"
      test "docker_compose_server_port_by_environment_variable_short_name"
      test "docker_compose_without_server_port"
      test "docker_compose_with_expectation_initialiser"
      test "docker_compose_with_persisted_expectations"
      test "docker_compose_with_server_port_from_default_properties_file"
      test "docker_compose_with_server_port_from_custom_properties_file"
      test "docker_compose_with_mtls"
      test "docker_compose_jvm_options"
      test "docker_compose_appcds_archive_mapped"
      test "docker_compose_libs_classpath"
      test "docker_compose_graceful_shutdown"
      test "docker_compose_metrics"
      # 5c.4 - per-variant smoke tests (root/snapshot/local Dockerfiles).
      # Same gate as docker_compose_* tests: they share the same JAR + docker
      # daemon, and the helm-only CI step (helm-integration-test.sh) sets
      # SKIP_DOCKER_TESTS=true to skip both.
      # HEALTHCHECK and non-root user assertions on the default image.
      test_healthcheck || true
      test_nonroot_user || true
      # The shipped image must survive sustained load at the documented 512 MiB floor.
      # MEMORY_FLOOR_BLOCKING=false turns a failure into a warning.
      if [[ "${MEMORY_FLOOR_BLOCKING:-true}" == "true" ]]; then
        test "docker_memory_floor_512m"
      else
        test "docker_memory_floor_512m" non_blocking
      fi
      if [[ "${SKIP_VARIANT_TESTS:-}" != "true" ]]; then
        smoke_test_variant "root" || true
        smoke_test_variant "snapshot" || true
        smoke_test_variant "local" || true
        smoke_test_variant "graaljs" || true
        # root-snapshot is a new variant not yet proven in CI — non-blocking.
        smoke_test_variant_nonblocking "root-snapshot" || true
        # aot is experimental and its build runs a training JVM that is flaky under
        # arm64 emulation, so it is non-blocking here as it is in the release pipeline.
        smoke_test_variant_nonblocking "aot" || true
        smoke_test_http3 || true
      fi
      # Clustered variant: test that the -clustered image boots and responds
      # to /mockserver/status. The image is already built by build_clustered_docker().
      smoke_test_clustered || true
      # arm64 cross-platform build gate (buildx --platform linux/arm64).
      test_arm64_build_gate || true
      # WAR deployment test (Tomcat container); requires mockserver-war to
      # have been built by the Maven package step.
      test "docker_compose_war_tomcat"
      clean-up-docker-containers
    fi
    if [[ "${SKIP_HELM_TESTS:-}" != "true" ]]; then
      # helm test
      start-up-k8s
      test "helm_default_config"
      test "helm_local_docker_container"
      test "helm_custom_server_port"
      test "helm_remote_host_and_port"
      test "helm_inline_config"
      test "helm_configmap_injection"
      test "helm_mockserver_config_chart"

      # Sidecar-injection admission webhook e2e. Deploys the chart with the
      # MutatingWebhookConfiguration + webhook handler, creates a labelled pod,
      # and asserts the webhook actually MUTATED the pod spec to carry the
      # MockServer sidecar container (a negative-control pod proves it does not
      # inject unconditionally). Needs the webhook handler image, built only with
      # a JDK+Maven reactor — SKIP (honest) when absent, BLOCKING when present.
      if webhook_image_available; then
        WEBHOOK_IMAGE="$(webhook_image_ref)"
        if import_image_into_k3d "${WEBHOOK_IMAGE}"; then
          # The injected sidecar uses this already-imported image (pullPolicy
          # IfNotPresent). Passed to the test so it need not re-resolve versions.
          export MOCKSERVER_WEBHOOK_IMAGE="${WEBHOOK_IMAGE}"
          export MOCKSERVER_SIDECAR_IMAGE="mockserver/mockserver:integration_testing"
          test "helm_sidecar_injection"
          unset MOCKSERVER_WEBHOOK_IMAGE MOCKSERVER_SIDECAR_IMAGE
        else
          logTestResult "1" "helm_sidecar_injection"
        fi
      elif [[ "${REQUIRE_WEBHOOK_IMAGE}" == "true" ]]; then
        # CI: the webhook image is REQUIRED (the helm step built it). Absent here
        # means the build did not produce it — fail closed, never skip.
        printFailureMessage "webhook handler image REQUIRED (REQUIRE_WEBHOOK_IMAGE=true) but absent — the image-build step must produce ${WEBHOOK_IMAGE_REPO}. Failing closed."
        logTestResult "1" "helm_sidecar_injection"
      else
        logTestSkip "helm_sidecar_injection" "webhook handler image absent in this local build (build the mockserver-k8s-webhook image to run this case)"
      fi

      # Clustered state convergence + JGroups DNS_PING discovery e2e. Both need
      # the -clustered image variant. When it is present they run BLOCKING (a
      # genuine failure reds the suite); when absent (CI helm step, no JDK) they
      # record an honest SKIP rather than a misleading warn/pass. The old flow
      # imported with `2>/dev/null || true` and ran non_blocking — a swallowed
      # import failure plus an advisory result meant a broken cluster could not
      # red the build. import_image_into_k3d now imports deterministically and
      # verifies the image is in the node before deploying.
      if [[ "${SKIP_CLUSTERED_TEST:-}" != "true" ]]; then
        if clustered_image_available; then
          if import_image_into_k3d "${CLUSTERED_IMAGE}"; then
            test "helm_clustered_convergence"
            test "helm_jgroups_dns_ping"
          else
            logTestResult "1" "helm_clustered_convergence"
            logTestResult "1" "helm_jgroups_dns_ping"
          fi
        elif [[ "${REQUIRE_CLUSTERED_IMAGE}" == "true" ]]; then
          # CI: the clustered image is REQUIRED (the helm step built it). Absent
          # here means the build did not produce it — fail closed, never skip.
          printFailureMessage "clustered image REQUIRED (REQUIRE_CLUSTERED_IMAGE=true) but absent — the image-build step must produce ${CLUSTERED_IMAGE}. Failing closed."
          logTestResult "1" "helm_clustered_convergence"
          logTestResult "1" "helm_jgroups_dns_ping"
        else
          logTestSkip "helm_clustered_convergence" "clustered image absent in this local build (build the -clustered image to run this case)"
          logTestSkip "helm_jgroups_dns_ping" "clustered image absent in this local build (build the -clustered image to run this case)"
        fi
      fi
      tear-down-k8s
    fi
    set -euo pipefail
  fi

  printMessage "TEST SUMMARY"

  # Fail closed on an empty run. EXIT_CODE is only ever set from a non-empty
  # FAIL_LOG, so a suite where NOTHING was recorded — harness crash, docker
  # daemon down, every test dying before it could log — reported success while
  # testing nothing. A run that recorded no pass and no failure is a failure.
  # (SKIP_ALL_TESTS / SKIP_DOCKER_TESTS+SKIP_HELM_TESTS are explicit opt-outs
  # and remain legitimately green.)
  local recorded_pass=0 recorded_fail=0
  [[ -s "${PASS_LOG_FILE}" ]] && recorded_pass=1
  [[ -s "${FAIL_LOG_FILE}" ]] && recorded_fail=1
  if [[ "${SKIP_ALL_TESTS:-}" != "true" \
     && ! ( "${SKIP_DOCKER_TESTS:-}" == "true" && "${SKIP_HELM_TESTS:-}" == "true" ) \
     && ${recorded_pass} -eq 0 && ${recorded_fail} -eq 0 ]]; then
    printFailureMessage "NO TESTS RECORDED A RESULT — the harness ran nothing. Failing closed."
    EXIT_CODE=1
  fi

  # Manifest coverage gate: assert every test case the manifest declares for
  # this run produced a result (and every result was declared). Runs here while
  # all four result logs still exist, before they are consumed and removed.
  assert_manifest_complete

  if [[ -s "${PASS_LOG_FILE}" ]]; then
    NUMBER_OF_PASSED_TESTS=$(cat "${PASS_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "PASSED: ${NUMBER_OF_PASSED_TESTS}"
    cat "${PASS_LOG_FILE}"
    printf "\n\n"
  fi
  rm -f "${PASS_LOG_FILE}"
  if [[ -s "${SKIP_LOG_FILE}" ]]; then
    NUMBER_OF_SKIPPED_TESTS=$(cat "${SKIP_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "SKIPPED: ${NUMBER_OF_SKIPPED_TESTS}"
    cat "${SKIP_LOG_FILE}"
    printf "\n\n"
  fi
  rm -f "${SKIP_LOG_FILE}"
  if [[ -s "${WARN_LOG_FILE}" ]]; then
    NUMBER_OF_WARNED_TESTS=$(cat "${WARN_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "WARNINGS (non-blocking): ${NUMBER_OF_WARNED_TESTS}"
    cat "${WARN_LOG_FILE}"
    printf "\n\n"
    annotate_warnings "${WARN_LOG_FILE}"
  fi
  rm -f "${WARN_LOG_FILE}"
  if [[ -s "${FAIL_LOG_FILE}" ]]; then
    NUMBER_OF_FAILED_TESTS=$(cat "${FAIL_LOG_FILE}" | wc -l | sed -r 's/( )+//g')
    printMessage "FAILED: ${NUMBER_OF_FAILED_TESTS}"
    cat "${FAIL_LOG_FILE}"
    printf "\n\n"
    EXIT_CODE=1
  fi
  rm -f "${FAIL_LOG_FILE}"

  exit ${EXIT_CODE:-0}
}

# Only build + run when EXECUTED, not when SOURCED. Executing the script keeps the
# original behaviour (BASH_SOURCE[0] == $0); sourcing it (e.g. from a focused test
# that drives run_all_tests with the k3d plumbing stubbed to prove the fail-closed
# image gates) defines the functions without side effects.
if [[ "${BASH_SOURCE[0]}" == "${0}" ]]; then
  build_docker
  run_all_tests
fi

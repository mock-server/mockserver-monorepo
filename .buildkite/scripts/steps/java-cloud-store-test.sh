#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the cloud blob-store contract suites (S3/s3mock, GCS/fake-gcs,
# Azure/Azurite) against real backing services via Testcontainers.
#
# WHY THIS IS A SEPARATE STEP rather than `-s` on java-build.sh:
#
#   run-in-docker.sh withholds the Docker socket from PR builds and
#   `exit 0`s the whole step when one is requested (see the DOCKER_SOCKET
#   block, "Skipping Docker-socket step on PR build"). Adding `-s` to
#   java-build.sh would therefore make the ENTIRE Java build — every unit
#   and integration test in the reactor — silently exit 0 on every PR
#   build. That trades a narrow false-positive for a total one.
#
#   Splitting the Docker-dependent modules into their own step keeps the
#   main build socket-free (so it runs on PRs) and confines the PR-build
#   socket skip to the three cloud modules, where it is announced loudly
#   in the log rather than hidden behind an `Assume`.
#
# These three suites are Docker-gated via
# `Assume.assumeTrue(DockerAvailability.isAvailable(...))`.
# That guard is correct and stays — it makes the suite degrade gracefully
# off-CI. The defect it was masking was that CI never satisfied it, so the
# suites skipped on 100% of builds while reporting green.
#
# WHY TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false:
#
#   Once the socket was mounted the suites ran for real and failed at once:
#     BadRequestException: Status 400: privileged mode is incompatible with
#     user namespaces. You must run the container in the host namespace when
#     running privileged mode
#   Testcontainers-Java starts its Ryuk reaper with Privileged=true by default
#   (TestcontainersConfiguration.isRyukPrivileged() defaults the
#   `ryuk.container.privileged` property to "true"), and the elastic-ci-stack
#   agents run dockerd with user-namespace remapping, which rejects privileged
#   containers outright.
#
#   Ryuk does not need privileged mode to reap: it reaps through the mounted
#   Docker socket. Dropping only the privileged flag keeps the reaper — and so
#   keeps container cleanup on abnormal termination — while satisfying the
#   daemon. Verified locally: with this variable set Ryuk starts with
#   HostConfig.Privileged=false and the probe reports Docker available.
#
#   NOTE the exact variable name. Testcontainers maps a property to an env var
#   by uppercasing and replacing dots, then prefixing TESTCONTAINERS_, so
#   `ryuk.container.privileged` becomes TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED.
#   The intuitive-looking TESTCONTAINERS_RYUK_PRIVILEGED is NOT read by anything
#   and silently has no effect (confirmed locally: Ryuk still came up
#   Privileged=true) — it would have looked like a fix and changed nothing.
#
#   REJECTED — TESTCONTAINERS_RYUK_DISABLED=true: also makes the probe succeed,
#   but turns off the reaper, so containers leak whenever the JVM dies without
#   running its shutdown hooks. The leak is bounded on these ephemeral,
#   scale-to-zero agents, but it is an unnecessary cost when only the privileged
#   flag is actually objectionable to the daemon.
#
#   REJECTED — disabling user-namespace remapping on the agent daemon: userns
#   remap is a deliberate security boundary of the elastic-ci-stack AMI that this
#   repo actively builds around (see run-in-docker.sh --harden, and the ownership
#   workaround in vscode-test.sh). Weakening it repo-wide to satisfy one test step
#   is the wrong trade, and it is upstream-module configuration rather than a
#   repo-level fix.
#
#   REJECTED — running Ryuk in the host namespace (what the error message
#   suggests): Testcontainers-Java exposes no setting for Ryuk's userns mode, so
#   this is not reachable without patching Testcontainers.
#
# WHY -m 7g AND NOT 4g:
#
#   At 4g this step intermittently died with exit 137 (OOM-Killed) — and it died
#   in the FIRST command, the `-am` dependency build, so the cloud contract
#   suites never ran at all (build #1755). A step whose whole purpose is to fail
#   CLOSED on missing coverage was instead losing that coverage before a single
#   test started.
#
#   `mockserver/.mvn/jvm.config` pins the Maven JVM to `-Xms2048m -Xmx6144m`, and
#   mvnw PREPENDS jvm.config to MAVEN_OPTS, so it overrides container ergonomics.
#   Verified inside mockserver/mockserver:maven under `--memory=4g`: ergonomics
#   alone would have picked MaxHeapSize=1073741824 (MaxRAMPercentage=25.0), but
#   with jvm.config applied MaxHeapSize=6442450944 — the JVM is told it may grow
#   to 6g, but inside a 4g cgroup the kernel kills it. `-T 1C`
#   (mockserver/.mvn/maven.config) builds the upstream reactor modules
#   concurrently in that one JVM, so live heap really does climb toward the
#   ceiling, which is why the kill was intermittent rather than constant.
#
#   7g is the limit every other step that runs ./mvnw from mockserver/ already
#   uses (java-build.sh, java-deploy-snapshot.sh, maven-plugin-build.sh,
#   ui-java-codegen-compile.sh, helm-integration-test.sh). It clears the declared
#   6g Xmx plus metaspace/code-cache/native overhead, and the default-queue
#   agents are c5.2xlarge (16 GiB), so it still leaves ample headroom for the
#   sibling Testcontainers containers — those run on the HOST daemon through the
#   mounted socket, outside this container's cgroup.
#
#   REJECTED — capping the heap instead (MAVEN_OPTS=-Xmx3g or
#   -XX:MaxRAMPercentage) so it fits inside 4g: it would work mechanically (env
#   MAVEN_OPTS is appended AFTER jvm.config, and the last -Xmx wins), but it
#   gives this step less heap than the repo declares the reactor needs, trading
#   an OOM-kill for GC thrash or a java.lang.OutOfMemoryError, and it makes this
#   one step diverge from every other Maven step. Raise the container limit to
#   match the declared heap, rather than quietly under-provisioning the build.
#
#   REJECTED — dropping `-T 1C` for this step: parallelism raises the peak but
#   the 6g-Xmx-in-a-4g-cgroup ceiling is a defect at any parallelism, so this
#   would only make the OOM rarer. `-T 1C` is also a repo-wide default, not this
#   step's to override.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

MODULES="mockserver-blob-s3,mockserver-blob-gcs,mockserver-blob-azure"

# Image names are the single source of truth in test-container-images.properties,
# resolved via test-images.sh (so the pre-pull list can no longer drift from the
# suites' *_IMAGE constants — both read the same file). ecr-pull-through-login.sh
# optionally logs the host daemon in to the ECR pull-through cache and sets
# MOCKSERVER_TEST_IMAGE_REGISTRY; when unset (default, forks, cache not yet
# applied) test_image resolves the public names and behaviour is unchanged.
#   s3mock           -> S3BlobStoreContractTest / S3ExpectationPersistenceReloadTest
#   fake-gcs-server  -> GcsBlobStoreContractTest / GcsBlobStoreRegistrarConfigWiringTest
#   azurite          -> AzureBlobStoreContractTest / AzureBlobStoreRegistrarConfigWiringTest
source "$SCRIPT_DIR/../lib/test-images.sh"
source "$SCRIPT_DIR/../lib/ecr-pull-through-login.sh"

# Pre-pull the backing images (with retry + backoff) on the host daemon BEFORE
# Maven runs, so a slow/throttled registry fails fast with a clear message
# instead of surfacing ~5 minutes in as an opaque Testcontainers
# RemoteDockerImage / ContainerFetchException timeout. The suites pull these
# through the mounted socket, so warming the host cache here means they never
# re-pull.
"$SCRIPT_DIR/../lib/pre-pull-images.sh" \
  "$(test_image s3mock)" \
  "$(test_image fake-gcs-server)" \
  "$(test_image azurite)"

# Pass the resolved registry into the container so the Testcontainers suites ask
# for the SAME image names the host just warmed (see TestContainerImages). Empty
# when the pull-through cache is not enabled — the suites then use public names.
IMAGE_REGISTRY_ENV=()
if [[ -n "${MOCKSERVER_TEST_IMAGE_REGISTRY:-}" ]]; then
  IMAGE_REGISTRY_ENV=(-e "MOCKSERVER_TEST_IMAGE_REGISTRY=${MOCKSERVER_TEST_IMAGE_REGISTRY}")
fi

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  --docker-socket \
  -e TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false \
  "${IMAGE_REGISTRY_ENV[@]+"${IMAGE_REGISTRY_ENV[@]}"}" \
  -- bash -ec "
    # Build the modules' dependencies without running their tests — the main
    # build step already covers those, and this step must stay scoped to the
    # cloud contract suites.
    ./mvnw -q -pl ${MODULES} -am install \
      -DskipTests -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      --batch-mode --no-transfer-progress

    ./mvnw -pl ${MODULES} test \
      -Djacoco.skip=true -Dmaven.gitcommitid.skip=true \
      --batch-mode --no-transfer-progress

    # Fail closed. The Assume guards mean a missing/broken Docker socket
    # reports these suites as SKIPPED with Maven still exiting 0 — exactly
    # the false positive this step exists to remove.
    /build/.buildkite/scripts/steps/assert-suite-ran.sh \
      'mockserver-blob-s3/target/surefire-reports/TEST-*BlobStoreContractTest.xml' \
      'mockserver-blob-s3/target/surefire-reports/TEST-*S3ExpectationPersistenceReloadTest.xml' \
      'mockserver-blob-gcs/target/surefire-reports/TEST-*BlobStoreContractTest.xml' \
      'mockserver-blob-gcs/target/surefire-reports/TEST-*RegistrarConfigWiringTest.xml' \
      'mockserver-blob-azure/target/surefire-reports/TEST-*BlobStoreContractTest.xml' \
      'mockserver-blob-azure/target/surefire-reports/TEST-*RegistrarConfigWiringTest.xml'
  "

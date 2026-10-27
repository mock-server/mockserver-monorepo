#!/usr/bin/env bash
# ──────────────────────────────────────────────────────────────────────
# Run the mockserver-testcontainers integration suite
# (MockServerContainerIntegrationTest) against a real MockServer container
# started via Testcontainers.
#
# The main build has no Docker socket, so the suite's Assume guard would skip it
# there while the job stays green. This step supplies the socket and fails closed
# via assert-suite-ran.sh. Separate step, Ryuk flag and -m 7g: same reasons as
# java-async-broker-test.sh (docs/infrastructure/ci-cd.md, "Gates That Must Fail
# Closed").
#
# The suite runs the pinned released image on -SNAPSHOT builds. Any other version
# is a release-window commit whose image may not be published yet (and whose
# major.minor the client may reject), so the image is built from this commit.
# ──────────────────────────────────────────────────────────────────────
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

MODULE="mockserver-testcontainers"
PIN_FILE="$REPO_ROOT/mockserver/mockserver-testcontainers/src/test/java/org/mockserver/testcontainers/TestcontainersImages.java"
LOCAL_IMAGE="mockserver-under-test:local"

project_version="$(grep -m1 -oE '<version>[^<]+</version>' "$REPO_ROOT/mockserver/pom.xml" | sed -E 's#</?version>##g' || true)"
pinned_images="$(grep -oE 'mockserver/mockserver:mockserver-[0-9]+\.[0-9]+\.[0-9]+' "$PIN_FILE" | sort -u || true)"
if [[ -z "$project_version" || "$(grep -c . <<<"$pinned_images")" -ne 1 ]]; then
  echo "Error: cannot read the project version or exactly one pinned image from $PIN_FILE" >&2
  exit 1
fi
pinned_version="${pinned_images#mockserver/mockserver:mockserver-}"
project_major_minor="$(cut -d. -f1,2 <<<"$project_version")"
pinned_major_minor="$(cut -d. -f1,2 <<<"$pinned_version")"

image_property=""
if [[ "$project_version" == *-SNAPSHOT && "$project_major_minor" == "$pinned_major_minor" ]]; then
  echo "--- :whale: Image under test: $pinned_images (pinned release; project version $project_version)"
else
  echo "--- :whale: Project version $project_version is a release-window build (pin $pinned_images) - building the image from this commit"
  MOCKSERVER_IMAGE="$LOCAL_IMAGE" "$REPO_ROOT/.buildkite/scripts/build-local-mockserver-image.sh"
  image_property="-Dtestcontainers.mockserver.image=$LOCAL_IMAGE"
  echo "--- :whale: Image under test: $LOCAL_IMAGE (built from this commit, project version $project_version)"
fi

exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 7g \
  --cache maven \
  --docker-socket \
  -e TESTCONTAINERS_RYUK_CONTAINER_PRIVILEGED=false \
  -- bash -ec "
    if getent hosts host.docker.internal >/dev/null 2>&1; then
      export TESTCONTAINERS_HOST_OVERRIDE=host.docker.internal
    fi

    # Build the module's dependencies without running their tests — the main
    # build step already covers those, and this step must stay scoped to the
    # container integration suite.
    ./mvnw -pl ${MODULE} -am install \
      -DskipTests -DskipITs -Djacoco.skip=true -Dmaven.javadoc.skip=true \
      -Dmaven.gitcommitid.skip=true -P '!build-ui' \
      --batch-mode --no-transfer-progress

    # 'verify' (not 'test') — MockServerContainerIntegrationTest is a Failsafe IT,
    # so it only runs in the integration-test/verify phase.
    ./mvnw -pl ${MODULE} verify ${image_property} \
      -Djacoco.skip=true -Dmaven.gitcommitid.skip=true \
      --batch-mode --no-transfer-progress

    # Fail closed. The Assume guard means a missing/broken Docker socket reports
    # this suite as SKIPPED with Maven still exiting 0 — exactly the false positive
    # this step exists to remove. Note the FAILSAFE report directory: this is an IT,
    # so it does not write to surefire-reports.
    /build/.buildkite/scripts/steps/assert-suite-ran.sh \
      'mockserver-testcontainers/target/failsafe-reports/TEST-*MockServerContainerIntegrationTest.xml'
  "

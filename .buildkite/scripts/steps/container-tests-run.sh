#!/usr/bin/env bash
set -euo pipefail

# The container-integration harness (integration_tests.sh -> build_docker) builds
# docker/Dockerfile with --build-arg source=copy, staging whatever it finds at
# mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar.
# docker/Dockerfile is the public download-mode REFERENCE image: its source=download
# path fetches the DEFAULT fat jar from Maven Central, and its jarprep stage trims it and
# ASSERTS default-fat-jar native contents (this arch's tcnative and stock-named epoll).
# For the copy smoke test to exercise what the download path ships, it MUST be fed the
# SAME artifact: the default mockserver-netty jar-with-dependencies.
#
# NOT a shaded jar: jarprep refuses the mockserver-netty-no-dependencies library jar (relocated
# JNA), and would detect the mockserver-netty-docker jar docker/local consumes as shaded (relocated
# netty, no tcnative) and skip the tcnative assertion; java-docker-push-snapshot.sh covers that. This
# matches how helm-integration-test.sh already stages the default fat jar for docker/clustered.
echo "--- :buildkite: Downloading default fat JAR artifact"
buildkite-agent artifact download --step "${CONTAINER_TEST_JARS_STEP:-container-test-jars}" "mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar" .

shopt -s nullglob
FAT_JAR=""
for f in mockserver/mockserver-netty/target/mockserver-netty-*-jar-with-dependencies.jar; do
  FAT_JAR="$f"
  break
done
shopt -u nullglob
if [ -z "$FAT_JAR" ]; then
  echo "Error: default fat JAR (mockserver-netty-*-jar-with-dependencies.jar) not found after artifact download — the \":maven: container-test jars\" step must upload it"
  exit 1
fi
echo "Default fat JAR present: $FAT_JAR"
# Already at the exact path+name integration_tests.sh globs, so no re-stage is needed.

# docker_variant_smoke_local builds docker/local, the standard image, from the jar the release
# and snapshot steps build it from: the shaded mockserver-netty-docker jar. Only that jar carries
# the QUIC native docker/local installs, so the default fat jar fails its build.
echo "--- :buildkite: Downloading image JAR artifact"
buildkite-agent artifact download --step "${CONTAINER_TEST_JARS_STEP:-container-test-jars}" "mockserver/mockserver-netty-docker/target/mockserver-netty-docker-*.jar" .
shopt -s nullglob
IMAGE_JARS=( mockserver/mockserver-netty-docker/target/mockserver-netty-docker-*.jar )
shopt -u nullglob
if [ ${#IMAGE_JARS[@]} -eq 0 ]; then
  echo "Error: image JAR (mockserver-netty-docker-*.jar) not found after artifact download — the \":maven: container-test jars\" step must upload it"
  exit 1
fi
echo "Image JAR present: ${IMAGE_JARS[0]}"

# The docker_compose_war_tomcat case deploys the WAR into a Tomcat container.
# The WAR is built by the ":maven: container-test jars" step and uploaded as an
# artifact there (Buildkite steps share no filesystem). Download it into the path
# the test globs (mockserver/mockserver-war/target/). Fail closed if it is
# absent — this case exists to guard WAR deployment (a demonstrated weak spot),
# so a missing WAR must red the step, never silently skip.
echo "--- :buildkite: Downloading WAR artifact"
buildkite-agent artifact download --step "${CONTAINER_TEST_JARS_STEP:-container-test-jars}" "mockserver/mockserver-war/target/mockserver-war-*.war" .
shopt -s nullglob
WARS=( mockserver/mockserver-war/target/mockserver-war-*.war )
shopt -u nullglob
if [ ${#WARS[@]} -eq 0 ]; then
  echo "Error: WAR artifact not found after download — docker_compose_war_tomcat cannot run. Failing closed."
  exit 1
fi
echo "WAR artifact present: ${WARS[0]}"

echo "--- :docker: Running container integration tests (Docker Compose only)"
export SKIP_JAVA_BUILD=true
export SKIP_HELM_TESTS=true
export SKIP_DOCKER_REBUILD_CLIENT=false

exec container_integration_tests/integration_tests.sh

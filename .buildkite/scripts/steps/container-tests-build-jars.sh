#!/usr/bin/env bash
set -euo pipefail
# Build, test-free, the artefacts ":docker: container integration tests" consumes - the
# mockserver-netty fat jar, the mockserver-netty-docker jar (docker/local, the standard
# image, is built from it as the release and snapshot steps build it) and the WAR - so that
# step can start alongside
# ":maven: build" instead of after it. Testing the Java is that step's job; this one only
# assembles. Same shape as container-tests-build-images.sh in the container-tests pipeline.
# -m 12g, not 7g: packaging mockserver-netty runs the build-ui profile (npm ci + vite), and
# 6g of Maven heap plus node overran 7g in java-deploy-snapshot.sh.
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -w /build/mockserver \
  -m 12g \
  --cache maven \
  -e "MAVEN_OPTS=-Xms2048m -Xmx6144m" \
  -- ./mvnw -B --no-transfer-progress -T 1C -DskipTests -Dmaven.test.skip=true \
    -pl mockserver-netty,mockserver-netty-docker,mockserver-war -am package

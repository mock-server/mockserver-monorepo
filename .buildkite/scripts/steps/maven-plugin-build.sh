#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# The plugin ships outside the reactor, so the quick build's configuration call-site
# guard never scans it. guard.extraExpectedModules makes the guard FAIL if the
# plugin's target/classes is missing, instead of passing without scanning it.
#
# -m 12g as in java-build.sh: the Maven JVM may grow to the 6g heap in .mvn/jvm.config,
# and the install also forks vite and probe JVMs (assert-shaded-natives-load); 7g OOM-killed.
exec "$SCRIPT_DIR/../run-in-docker.sh" \
  -i mockserver/mockserver:maven \
  -m 12g \
  --cache maven \
  -- bash -c 'cd mockserver \
    && ./mvnw -B --no-transfer-progress clean install -DskipTests -DskipITs \
    && ./mvnw -B --no-transfer-progress -f mockserver-maven-plugin/pom.xml clean verify \
    && ./mvnw -B --no-transfer-progress -pl mockserver-netty jacoco:prepare-agent surefire:test@configuration-callsite-guard \
      -Dguard.extraExpectedModules=mockserver-maven-plugin \
      -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO'

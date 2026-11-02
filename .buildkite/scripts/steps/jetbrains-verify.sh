#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Runs the IntelliJ Plugin Verifier against the recommended IDE set (configured in
# mockserver-jetbrains/build.gradle.kts -> intellijPlatform.pluginVerification). This
# catches internal/deprecated/incompatible IntelliJ Platform API usages before a
# Marketplace upload is rejected. The verifier downloads several IDEs from the
# JetBrains CDN, so this is slower than the unit-test step and gets its own timeout.
#
# Same containerisation rationale as jetbrains-test.sh: runs as root in a plain JDK 17
# image (the Gradle wrapper provides Gradle 9.5.1 for the IntelliJ Platform plugin
# 2.16), and builds inside an in-container copy under /tmp so root-owned Gradle output
# (build/, .gradle/, .kotlin/, .intellijPlatform/) never lands in the mounted
# workspace and breaks the next build's git checkout/clean.
#
# After verification (pass OR fail) the per-IDE verifier report is copied back into the
# mounted workspace at mockserver-jetbrains/build/reports/pluginVerifier so the pipeline
# step's artifact_paths can upload it for diagnosis. The copied tree is chmod'd
# world-writable so the (non-root) agent can git-clean it on the next checkout despite
# being created by root. The verifier's exit code is preserved so a real finding still
# reddens the build.
#
# --cache gradle mounts .buildkite-cache/gradle/{caches,wrapper-dists} at
# ~/.gradle/{caches,wrapper/dists}. With `ides { recommended() }` the Plugin Verifier's
# IDEs are resolved as ORDINARY Gradle dependencies and extracted UNDER ~/.gradle/caches
# (CI logs read every IDE from /root/.gradle/caches/9.5.1/transforms/<hash>/transformed/
# idea*-<ver>) -- i.e. INSIDE GRADLE_USER_HOME, so they sit on this mount alongside the
# Gradle distribution and the resolved SDK/Kotlin/Gson. intellijPlatformIdesCache /
# intellijPlatformIdesCacheEnabled do NOT apply to recommended() and change nothing here.
# recommended() currently resolves to 7 IDEs: a verify downloads ~4.18 GB and leaves
# ~28 GB extracted in ~/.gradle/caches (~10-20 GB compressed). The mount is job-local:
# the S3 dependency cache persists gradle only for the java pipeline (dependency-cache.sh).
# GRADLE_USER_HOME stays at /root/.gradle regardless of the /tmp/jb working dir.
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i eclipse-temurin:17-jdk \
  -w /build \
  --cache gradle \
  -- bash -ec '
    cp -a mockserver-jetbrains /tmp/jb
    cd /tmp/jb
    rc=0
    ./gradlew verifyPlugin --no-daemon || rc=$?
    # Copy the verifier report back into the mounted workspace (best-effort; never
    # let housekeeping mask the verifier exit code). chmod so the non-root agent can
    # remove these root-created files during the next build s git clean.
    if [ -d build/reports/pluginVerifier ]; then
      dest=/build/mockserver-jetbrains/build/reports/pluginVerifier
      mkdir -p "$dest" || true
      cp -a build/reports/pluginVerifier/. "$dest/" || true
      chmod -R a+rwX /build/mockserver-jetbrains/build || true
    fi
    exit $rc
  '

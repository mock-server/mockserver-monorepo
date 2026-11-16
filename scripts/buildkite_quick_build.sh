#!/usr/bin/env bash

set -euo pipefail

log_debug() {
    echo "[$(date -u +"%Y-%m-%d %H:%M:%S UTC")] $*"
}

log_debug "=== BUILD START ==="
log_debug "User: $(whoami)"
log_debug "Memory: $(free -h 2>/dev/null | grep Mem || echo 'free command not available')"
log_debug "Disk: $(df -h /build/mockserver 2>/dev/null | tail -1 || echo 'df command not available')"

cd mockserver

echo
java -version
echo
./mvnw -version
echo
export MAVEN_OPTS="${MAVEN_OPTS:-} -Xms2048m -Xmx6144m"

if test "${BUILDKITE_BRANCH:-}" = "master"; then
    echo "BRANCH: MASTER"
else
    echo "BRANCH: ${CURRENT_BRANCH:-}"
fi

# In CI, mockserver-netty's integration tests run in the parallel ":maven: netty IT" shard
# steps (pipeline-java.yml sets this), so the reactor build skips just those.
PROFILES="clustered-libs"
if [ "${MOCKSERVER_NETTY_ITS_IN_SHARDS:-false}" = "true" ]; then
    PROFILES="${PROFILES},netty-it-skip"
    echo "mockserver-netty integration tests: run by the netty IT shard steps, skipped here"
fi

log_debug "Starting Maven build (foreground)..."
set +e
# -Djava.security.egd is supplied via .mvn/maven.config (file:/dev/./urandom)
# -B --no-transfer-progress: stops the non-TTY CI log filling with progress dots; kept
# out of .mvn/maven.config so local ./mvnw keeps live download progress.
# -P clustered-libs stages mockserver-state-infinispan's runtime classpath into
# target/clustered-libs for the -clustered snapshot image (java-docker-push-snapshot.sh).
# It must run inside this reactor build: a later standalone -pl run would resolve
# siblings from ~/.m2 and fail on a clean agent.
./mvnw -B --no-transfer-progress -T 1C clean install ${1:-} -P "$PROFILES" -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000"
MVN_EXIT=$?
log_debug "Maven exited with code=$MVN_EXIT"

# Complete the clustered /libs hand-off: copy-dependencies deliberately EXCLUDES
# org.mock-server, so the module's own jar is not in clustered-libs. Add it, exactly as
# container-tests-build-images.sh and build_clustered_docker() do, so the uploaded
# clustered-libs/ directory IS the image's /libs and the consumer needs no second glob.
# Never fails the build: an absent jar is caught downstream by the push step's own
# fail-closed lib-count check, and turning a green reactor red here would be wrong.
if [ "$MVN_EXIT" -eq 0 ]; then
    CLUSTERED_LIBS_DIR="$PWD/mockserver-state-infinispan/target/clustered-libs"
    if [ -d "$CLUSTERED_LIBS_DIR" ]; then
        for j in mockserver-state-infinispan/target/mockserver-state-infinispan-*.jar; do
            case "$(basename "$j")" in *-sources.jar|*-javadoc.jar|*-tests.jar|original-*) continue ;; esac
            [ -f "$j" ] && cp "$j" "$CLUSTERED_LIBS_DIR/" && log_debug "  staged $(basename "$j") into clustered-libs"
        done
    else
        log_debug "  WARNING: $CLUSTERED_LIBS_DIR absent - the clustered snapshot image cannot be built from this build"
    fi
fi

# ──────────────────────────────────────────────────────────────────────
# Whole-reactor configuration-reachability guard (ConfigurationCallSiteGuardTest).
#
# It scans compiled classes of EVERY reactor module, so it runs here, after the
# whole reactor is built, not in netty's test phase (under -T 1C that ran before the
# downstream modules existed). Runs only if the reactor passed; its exit folds into
# MVN_EXIT. Still inside the `set +e` region, so a failure lands in GUARD_EXIT.
if [ "$MVN_EXIT" -eq 0 ]; then
    log_debug "Running whole-reactor configuration-callsite guard..."
    # jacoco:prepare-agent only adds the coverage agent: the parent pom defaults argLine to empty,
    # so the guard's fork starts without it.
    ./mvnw -B --no-transfer-progress -pl mockserver-netty jacoco:prepare-agent surefire:test@configuration-callsite-guard \
        -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO
    GUARD_EXIT=$?
    log_debug "configuration-callsite guard exited with code=$GUARD_EXIT"
    if [ "$GUARD_EXIT" -ne 0 ]; then
        MVN_EXIT=$GUARD_EXIT
    fi
fi

# ──────────────────────────────────────────────────────────────────────
# Build the relocated examples/ suite standalone.
#
# examples/java is outside the reactor (a module path escaping Dependabot's
# "/mockserver" directory made it abort every grouped update). It must build in THIS
# container, straight after the reactor install that put its SNAPSHOT dependencies in
# ~/.m2; a separate step would not share that local repo. Runs only if the reactor
# passed; its exit folds into MVN_EXIT so a break turns the build red.
if [ "$MVN_EXIT" -eq 0 ]; then
    log_debug "Building relocated examples/ suite standalone (mvn -f ../examples/java/pom.xml)..."
    ./mvnw -B --no-transfer-progress -f ../examples/java/pom.xml clean install ${1:-} -Dmockserver.testOutput=quiet -DredirectTestOutputToFile=true -Dmockserver.testLogLevel=INFO "-Dmockserver.testArgLine=-Dmockserver.maxLogEntries=10000 -Dmockserver.maxExpectations=5000"
    EXAMPLES_EXIT=$?
    log_debug "examples/ build exited with code=$EXAMPLES_EXIT"
    if [ "$EXAMPLES_EXIT" -ne 0 ]; then
        MVN_EXIT=$EXAMPLES_EXIT
    fi
fi
set -e

trap - SIGTERM SIGINT

# Bundle the per-class jacoco HTML reports into a single tarball so Buildkite's
# artifact_paths can upload one file per build instead of ~28000 small HTML
# pages (which trips the 5000-artifact-per-job cap). The XML data files are
# uploaded separately for downstream tooling.
log_debug "Bundling jacoco HTML reports..."
cd /build/mockserver 2>/dev/null || cd "$(dirname "$0")/../mockserver"
find . -type d \( -name jacoco -o -name jacoco-it \) -path '*/target/site/*' > /tmp/jacoco-dirs.txt 2>/dev/null || true
if [[ -s /tmp/jacoco-dirs.txt ]]; then
    tar czf jacoco-html-reports.tar.gz -T /tmp/jacoco-dirs.txt 2>/dev/null \
      && log_debug "  jacoco-html-reports.tar.gz: $(du -h jacoco-html-reports.tar.gz | cut -f1)" \
      || log_debug "  tar failed - skipping HTML bundle"
fi
rm -f /tmp/jacoco-dirs.txt

log_debug "=== BUILD END (exit $MVN_EXIT) ==="
exit $MVN_EXIT

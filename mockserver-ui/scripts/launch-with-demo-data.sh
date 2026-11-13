#!/usr/bin/env bash
#
# launch-with-demo-data.sh
# ------------------------
# Launch the MockServer backend + the UI dev server and populate a rich demo
# dataset so every dashboard view can be tested by hand. Complements the repo's
# scripts/local_ui_dev.sh — this one lives in the UI folder and loads the much
# larger dataset in scripts/populate-demo-data.mjs (HTTP, forward, every LLM
# provider, conversations, agent-loop sessions, token/cost, predicate pills).
#
# Usage:
#   ./scripts/launch-with-demo-data.sh [OPTIONS]
#   npm run demo
#
# Options:
#   --rebuild       Force rebuild of the MockServer JAR even if one exists
#   --no-browser    Do not auto-open the browser
#   --with-broker   Start a Mosquitto MQTT broker (Docker) so the AsyncAPI panel's
#                   Recorded Messages table populates with a live, ticking feed
#   --with-load-injection
#                   Enable load generation + SLO tracking, register delayed
#                   self-target endpoints, and start a long-running load scenario
#                   so the Performance tab shows a live scenario with latency /
#                   throughput against a real (delayed) target
#   --load-generation
#                   Enable the load-generation control plane but DON'T auto-start the
#                   heavy demo scenarios — lets a caller drive a light scenario itself
#                   (e.g. for a clean Performance screenshot) without saturating the server
#   --port PORT     MockServer port (default: 1080)
#   --ui-port PORT  UI dev server port (default: 3000)
#   --mqtt-port P   MQTT broker port (default: 1883; only with --with-broker)
#   --help          Show this help
#
# Press Ctrl+C to stop both servers.

set -euo pipefail

for cmd in java curl node npm; do
  command -v "$cmd" >/dev/null 2>&1 || { echo "ERROR: '$cmd' is required but not installed"; exit 1; }
done

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
UI_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
REPO_ROOT="$(cd "$UI_DIR/.." && pwd)"

MOCKSERVER_PORT=1080
UI_PORT=3000
REBUILD=false
NO_BROWSER=false
WITH_BROKER=false
WITH_LOAD_INJECTION=false
LOAD_GENERATION=false
MQTT_PORT=1883
MQTT_CONTAINER="mockserver-demo-mqtt"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild) REBUILD=true; shift ;;
    --no-browser) NO_BROWSER=true; shift ;;
    --with-broker) WITH_BROKER=true; shift ;;
    --with-load-injection) WITH_LOAD_INJECTION=true; shift ;;
    --load-generation) LOAD_GENERATION=true; shift ;;
    --port) MOCKSERVER_PORT="$2"; shift 2 ;;
    --ui-port) UI_PORT="$2"; shift 2 ;;
    --mqtt-port) MQTT_PORT="$2"; shift 2 ;;
    --help|-h) sed -n '2,34p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    *) echo "Unknown option: $1 (use --help)"; exit 1 ;;
  esac
done

if [ "$WITH_BROKER" = true ]; then
  command -v docker >/dev/null 2>&1 || { echo "ERROR: '--with-broker' needs Docker (for the Mosquitto MQTT broker)"; exit 1; }
fi

echo "========================================"
echo "MockServer UI + Demo Data"
echo "========================================"

# --- locate or build the runnable MockServer JAR --------------------------
find_jar() {
  # Newest matching JAR wins, so stale builds from other branches don't shadow it.
  local jar
  jar=$(ls -t "$REPO_ROOT"/mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar 2>/dev/null \
    | grep -Ev '(-sources|-javadoc|/original-)' | head -1)
  [ -n "$jar" ] && { echo "$jar"; return 0; } || return 1
}

# Decide whether to (re)build the runnable JAR: forced, missing, or STALE. The jar is
# stale when a server-side Java source (or a pom.xml) under mockserver/ is newer than it.
# That is the common footgun here: an older jar silently lacks an endpoint the
# checked-out populate script calls (e.g. PUT /mockserver/loadScenario/start), so the demo
# 404s mid-run and the launcher tears the servers down. Auto-rebuilding when stale keeps
# the running endpoints in step with the checked-out code. The demo serves the UI from the
# vite dev server (npm run dev), so UI edits do NOT make the jar stale — only server-side
# Java/poms do, which is why the staleness check is scoped to mockserver/ sources.
NEED_BUILD=false
BUILD_REASON=""
if [ "$REBUILD" = true ]; then
  NEED_BUILD=true; BUILD_REASON="--rebuild requested"
elif ! find_jar >/dev/null; then
  NEED_BUILD=true; BUILD_REASON="no MockServer JAR found"
else
  STALE_SRC="$(find "$REPO_ROOT/mockserver" -not -path '*/target/*' \( \( -path '*/src/main/*' -name '*.java' \) -o -name 'pom.xml' \) -newer "$(find_jar)" -print 2>/dev/null | head -1)"
  if [ -n "$STALE_SRC" ]; then
    NEED_BUILD=true
    BUILD_REASON="JAR is stale — $(basename "$STALE_SRC") (and maybe others) changed since it was built; rebuilding so the running endpoints match the checked-out code"
  fi
fi

if [ "$NEED_BUILD" = true ]; then
  BUILD_LOG="$UI_DIR/mockserver-build.log"
  echo "→ Building MockServer JAR ($BUILD_REASON)"
  echo "  (this can take a few minutes)"
  echo "  cmd: (cd mockserver && ./mvnw clean install -DskipTests -DskipITs -pl mockserver-netty-no-dependencies -am)"
  echo "  full log: $BUILD_LOG"
  echo "  progress (Maven reactor — one line per module + result):"
  # Stream the full build to a log, but surface only the reactor "Building <module>
  # [N/M]" progress lines, the BUILD result, and any errors so it is clear the build
  # is advancing rather than hung — without flooding the terminal with full output.
  # PIPESTATUS captures the real Maven exit code (grep/tee would otherwise mask it).
  #
  # BOTH -DskipTests and -DskipITs are required. -DskipTests alone skips only surefire
  # (unit tests); maven-failsafe-plugin keeps running the *IntegrationTest classes, which
  # for this reactor includes the Testcontainers live-broker suites in mockserver-async and
  # the large mockserver-netty integration suite. That turns a "few minutes" jar build into
  # a full verify run that looks hung, so the demo appears to hang before it ever starts.
  set +e
  ( cd "$REPO_ROOT/mockserver" && ./mvnw clean install -DskipTests -DskipITs -pl mockserver-netty-no-dependencies -am ) 2>&1 \
    | tee "$BUILD_LOG" \
    | grep --line-buffered -E '\[INFO\] Building |\[INFO\] BUILD (SUCCESS|FAILURE)|\[ERROR\]' \
    | grep --line-buffered -vE 'npm warn|npm notice|EBADENGINE|vite-reporter|chunks are larger|dynamic import\(\)|codeSplitting|chunkSizeWarningLimit|PLUGIN_TIMINGS|rolldown\.rs|hooks are listed|Additional hook time|slowest hooks'
  build_rc=${PIPESTATUS[0]}
  set -e
  if [ "$build_rc" -ne 0 ]; then
    echo "ERROR: MockServer build failed (exit $build_rc) — last 40 log lines:"
    tail -40 "$BUILD_LOG"
    exit 1
  fi
  echo "✓ Build complete"
fi
MOCKSERVER_JAR="$(find_jar)" || { echo "ERROR: MockServer JAR not found after build"; exit 1; }
echo "✓ MockServer JAR: $(basename "$MOCKSERVER_JAR")"

# --- install UI deps if needed --------------------------------------------
if [ ! -d "$UI_DIR/node_modules" ]; then
  echo "→ Installing UI dependencies..."
  (cd "$UI_DIR" && npm ci)
fi

# --- start MockServer ------------------------------------------------------
MOCKSERVER_LOG="$UI_DIR/mockserver-demo.log"
# Feature flags passed to the JVM. Load injection needs loadGenerationEnabled (the
# /mockserver/loadScenario control plane is 403 otherwise) + sloTrackingEnabled so the
# load run's latency/error samples feed the SLO verdict store the Performance tab reads.
# Cap the JVM heap. Without an explicit -Xmx the HotSpot default max heap is 25% of
# physical RAM — on a large Mac that is many GB, and under --with-load-injection (heavy
# self-targeting load + a flood of log entries) the JVM grows to that ceiling and the OS
# starts SIGKILLing / swapping, making the whole machine unresponsive during a perf test.
# A modest cap keeps the demo + load runs comfortably bounded; override with DEMO_MAX_HEAP
# (e.g. DEMO_MAX_HEAP=2g npm run demo) for heavier scenarios.
DEMO_MAX_HEAP="${DEMO_MAX_HEAP:-1g}"
# Cap the in-memory request event-log ring buffer. Its default size scales with heap up to
# 250,000 entries (min(heapAvailableInKB()/8, 250000)) — at -Xmx1g that resolves to ~128,512
# entries (heapKB/8, below the ceiling), a buffer budgeted to consume much of the heap when it fills.
# The demo load self-targets THIS server, and the load-generated event-log suppression is
# driver-side only (the marker is transient and does not survive the socket hop), so the server
# logs all of its own load traffic. Left unbounded that fills the buffer in ~15-30 min and pushes
# the capped heap into continuous GC, pegging every core and stalling the machine. A demo never
# needs more than a few thousand entries of scrollback; override with DEMO_MAX_LOG_ENTRIES.
DEMO_MAX_LOG_ENTRIES="${DEMO_MAX_LOG_ENTRIES:-5000}"
# controlPlaneAuditEnabled: the audit trail is opt-in (off by default); enable it in the demo so
# the dashboard's Audit view shows the populate script's control-plane changes instead of an
# empty how-to-enable notice.
MOCKSERVER_JVM_ARGS=(-Xmx"$DEMO_MAX_HEAP" -Dmockserver.maxLogEntries="$DEMO_MAX_LOG_ENTRIES" -Dmockserver.metricsEnabled=true -Dmockserver.wasmEnabled=true -Dmockserver.controlPlaneAuditEnabled=true)
# --load-generation enables the load-generation control plane WITHOUT auto-starting the
# heavy demo scenarios (that auto-start is gated on DEMO_WITH_LOAD_INJECTION below). This lets
# a caller drive a deliberately light scenario — e.g. for a clean Performance screenshot —
# without the heavy self-targeting load that saturates the server.
if [ "$WITH_LOAD_INJECTION" = true ] || [ "$LOAD_GENERATION" = true ]; then
  MOCKSERVER_JVM_ARGS+=(-Dmockserver.loadGenerationEnabled=true -Dmockserver.sloTrackingEnabled=true)
fi
echo "→ Starting MockServer on port $MOCKSERVER_PORT (max heap: $DEMO_MAX_HEAP, log: $MOCKSERVER_LOG)..."
java "${MOCKSERVER_JVM_ARGS[@]}" -jar "$MOCKSERVER_JAR" -serverPort "$MOCKSERVER_PORT" -logLevel INFO > "$MOCKSERVER_LOG" 2>&1 &
MOCKSERVER_PID=$!

UI_PID=""
cleanup() {
  echo ""
  echo "→ Stopping servers..."
  [ -n "${UI_PID:-}" ] && kill "$UI_PID" 2>/dev/null || true
  [ -n "${MOCKSERVER_PID:-}" ] && kill "$MOCKSERVER_PID" 2>/dev/null || true
  [ "$WITH_BROKER" = true ] && docker rm -f "$MQTT_CONTAINER" >/dev/null 2>&1 || true
  wait 2>/dev/null || true
  echo "✓ Stopped"
}
trap cleanup INT TERM EXIT

wait_for() {
  # MockServer's control plane answers /mockserver/status only to PUT, so the
  # HTTP method is a parameter (default GET for plain pages like the dashboard).
  local url="$1" name="$2" method="${3:-GET}" timeout=60 elapsed=0
  echo "  Waiting for $name..."
  until curl -sf -X "$method" "$url" >/dev/null 2>&1; do
    [ "$elapsed" -ge "$timeout" ] && { echo "ERROR: $name did not start within ${timeout}s"; return 1; }
    sleep 1; elapsed=$((elapsed + 1))
  done
}

wait_for "http://localhost:$MOCKSERVER_PORT/mockserver/status" "MockServer" PUT
echo "✓ MockServer ready (PID $MOCKSERVER_PID)"

# --- optional MQTT broker (for AsyncAPI Recorded Messages) -----------------
# Starts a throwaway Mosquitto broker with anonymous access so the populate
# script can load the AsyncAPI spec in live-broker mode (publish + consume),
# making the AsyncAPI panel's Recorded Messages table fill with a live feed.
DEMO_MQTT_BROKER_URL=""
if [ "$WITH_BROKER" = true ]; then
  echo "→ Starting Mosquitto MQTT broker on port $MQTT_PORT (Docker container: $MQTT_CONTAINER)..."
  # Remove any leftover demo container from a previous run FIRST, so its port hold does not
  # trip the pre-flight below (the old behaviour silently self-cleaned; keep that).
  docker rm -f "$MQTT_CONTAINER" >/dev/null 2>&1 || true
  # Pre-flight: fail fast if something else already listens on the MQTT port. Without this,
  # `docker run` exits 125 ("port is already allocated"), the demo container sits in Created,
  # and the TCP probe below would pass against the foreign listener — falsely reporting the
  # broker ready and then failing later, deep inside the populate step, with an opaque 400.
  if (exec 3<>"/dev/tcp/localhost/$MQTT_PORT") 2>/dev/null; then
    exec 3>&- 2>/dev/null || true
    echo "ERROR: port $MQTT_PORT is already in use — stop the other MQTT listener (docker ps -a | grep mosquitto; lsof -i :$MQTT_PORT) or pass --mqtt-port <other-port>"
    exit 1
  fi
  # Pull explicitly so a registry/proxy failure is unambiguous and distinct from a startup failure
  # (an implicit pull inside `docker run -d ... >/dev/null` would be silently swallowed).
  if ! docker image inspect eclipse-mosquitto:2 >/dev/null 2>&1; then
    echo "→ Pulling eclipse-mosquitto:2 (first run)..."
    docker pull eclipse-mosquitto:2 >/dev/null || { echo "ERROR: failed to pull eclipse-mosquitto:2 — check Docker Hub access (offline? rate limit? corporate proxy? the Docker daemon must trust the proxy root CA — Docker Desktop Settings > Docker Engine)"; exit 1; }
  fi
  docker run -d --name "$MQTT_CONTAINER" -p "$MQTT_PORT:1883" eclipse-mosquitto:2 \
    sh -c "printf 'listener 1883\nallow_anonymous true\n' > /mosquitto/config/mosquitto.conf && exec /usr/sbin/mosquitto -c /mosquitto/config/mosquitto.conf" >/dev/null \
    || { echo "ERROR: docker run failed for the Mosquitto broker:"; docker logs "$MQTT_CONTAINER" 2>&1 | tail -10; exit 1; }
  # Wait for the broker TCP port to accept connections (bash /dev/tcp — no nc dependency),
  # checking the container is actually running so a crash surfaces its logs immediately.
  broker_elapsed=0
  until (exec 3<>"/dev/tcp/localhost/$MQTT_PORT") 2>/dev/null; do
    if [ "$(docker inspect -f '{{.State.Running}}' "$MQTT_CONTAINER" 2>/dev/null)" != "true" ]; then
      echo "ERROR: the Mosquitto broker container is not running:"; docker logs "$MQTT_CONTAINER" 2>&1 | tail -10; exit 1
    fi
    [ "$broker_elapsed" -ge 30 ] && { echo "ERROR: MQTT broker did not open port $MQTT_PORT within 30s"; docker logs "$MQTT_CONTAINER" 2>&1 | tail -10; exit 1; }
    sleep 1; broker_elapsed=$((broker_elapsed + 1))
  done
  exec 3>&- 2>/dev/null || true
  DEMO_MQTT_BROKER_URL="tcp://localhost:$MQTT_PORT"
  echo "✓ MQTT broker ready ($DEMO_MQTT_BROKER_URL)"
fi

# --- populate demo data ----------------------------------------------------
echo "→ Populating demo data..."
DEMO_MQTT_BROKER_URL="$DEMO_MQTT_BROKER_URL" DEMO_WITH_LOAD_INJECTION="$WITH_LOAD_INJECTION" node "$SCRIPT_DIR/populate-demo-data.mjs" --url "http://localhost:$MOCKSERVER_PORT"

# --- start UI dev server ---------------------------------------------------
echo "→ Starting UI dev server on port $UI_PORT..."
(cd "$UI_DIR" && MOCKSERVER_URL="http://localhost:$MOCKSERVER_PORT" npm run dev -- --port "$UI_PORT" >/dev/null 2>&1) &
UI_PID=$!

# Open the dashboard on the dev-server origin but pointed at MockServer via ?port: the UI
# then calls http://localhost:$MOCKSERVER_PORT directly (cross-origin from the :$UI_PORT dev
# server). MockServer's control plane returns CORS headers on every /mockserver/* response and
# answers the OPTIONS preflight, so this cross-origin path works without the dev proxy — the
# same way the bundled dashboard works when pointed at a different MockServer via host/port.
UI_URL="http://localhost:$UI_PORT/mockserver/dashboard/?port=$MOCKSERVER_PORT"
wait_for "http://localhost:$UI_PORT/mockserver/dashboard/" "UI dev server"
echo "✓ UI dev server ready (PID $UI_PID)"

if [ "$NO_BROWSER" = false ]; then
  if command -v open >/dev/null 2>&1; then open "$UI_URL"
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$UI_URL"
  fi
fi

echo ""
echo "========================================"
echo "✓ Ready — populated demo environment"
echo "========================================"
echo "  UI (dev) : $UI_URL"
echo "  Dashboard: http://localhost:$MOCKSERVER_PORT/mockserver/dashboard"
echo "  MockServer log: $MOCKSERVER_LOG"
echo ""
if [ "$WITH_LOAD_INJECTION" = true ]; then
  echo "  ⚡ Load injection is RUNNING against delayed self-target endpoints."
  echo "     Open the Performance tab to watch / edit the live load scenario and its"
  echo "     latency + throughput metrics:"
  echo "       $UI_URL  (then click the Performance tab)"
  echo "     or the bundled dashboard: http://localhost:$MOCKSERVER_PORT/mockserver/dashboard/?port=$MOCKSERVER_PORT"
  echo ""
fi
echo "  Re-populate at any time:  npm run demo:data"
echo "  Press Ctrl+C to stop both servers."
echo "========================================"

wait

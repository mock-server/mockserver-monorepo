#!/usr/bin/env bash
#
# launch-with-llm-capture.sh
# --------------------------
# One command to manually test LLM-traffic capture and its dashboard UX. Starts
# the MockServer backend (as an HTTPS proxy) + the UI dev server, then optionally
# drops you straight into a real coding-assistant CLI (claude / opencode / tabnine)
# already wired to proxy through MockServer with the proxy CA trusted — so every
# model call the tool makes shows up live in the Traffic, LLM Traces and LLM
# Optimise views.
#
# Sibling of launch-with-demo-data.sh: that one loads a rich SYNTHETIC dataset to
# screenshot every view; this one captures REAL traffic from real tools so you can
# exercise the genuine end-to-end UX. The UI is served from the vite dev server, so
# the latest dashboard code (e.g. provider detection) is live without rebuilding the
# jar; the jar is auto-(re)built only when a server-side Java source changed.
#
# Usage:
#   ./scripts/launch-with-llm-capture.sh [TOOL] [-- TOOL_ARGS...]
#   npm run capture -- [TOOL] [-- TOOL_ARGS...]
#
# TOOL (optional): claude | opencode | tabnine | none
#   Default 'none' — just starts the servers + prints the proxy env block so you
#   can launch any tool yourself (in this or another terminal). When a TOOL is
#   given it is launched interactively in the foreground with the proxy env set;
#   exit the tool (or Ctrl+C) to tear everything down.
#
# Environment:
#   CAPTURE_LOG_LEVEL=DEBUG  Run MockServer at DEBUG to surface the new diagnostics: the
#                            per-response streaming decision (STREAM vs AGGREGATE, why, and the
#                            time-to-first-byte) and the SNI hostname on SSL/decoder-fault logs.
#                            Default INFO.
#
# Options:
#   --rebuild        Force rebuild of the MockServer JAR even if one exists
#   --no-browser     Do not auto-open the dashboard
#   --keep-log       Do NOT clear the recorded request log on start (default: clear,
#                    so you see only your fresh session's traffic)
#   --port PORT      MockServer / proxy port (default: 1080)
#   --ui-port PORT   UI dev server port (default: 3000)
#   --ca PATH        Proxy CA cert the tool must trust (default: MockServer's repo test CA)
#   --reverse-proxy HOST[:PORT]
#                    Run MockServer as a REVERSE proxy (port-forwarding) to a single upstream
#                    (default port 443) instead of a forward/HTTPS_PROXY proxy; the tool points its
#                    BASE URL at MockServer (OPENAI_BASE_URL is set for you). Suits standard-API tools
#                    and isolates whether a timeout is CONNECT-specific. (opencode/Codex can't use it —
#                    its chatgpt.com backend is not base-URL-overridable; use forward mode for opencode.)
#   --help           Show this help
#
# Anything after a literal `--` is passed through to the launched tool.
# Press Ctrl+C (or exit the tool) to stop the servers.

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
KEEP_LOG=false
# --reverse-proxy HOST[:PORT] runs MockServer as a REVERSE proxy (port-forwarding) to a single
# upstream instead of a forward (HTTPS_PROXY) proxy. The tool then points its BASE URL at MockServer
# (no HTTPS_PROXY) — useful for standard-API tools (OPENAI_BASE_URL) and as a CONNECT-bypass test.
REVERSE_PROXY=""
CA_CERT="$REPO_ROOT/mockserver/mockserver-core/src/main/resources/org/mockserver/socket/CertificateAuthorityCertificate.pem"
TOOL="none"
TOOL_ARGS=()

# Parse: first non-flag token is the TOOL; everything after `--` is passed through.
while [[ $# -gt 0 ]]; do
  case "$1" in
    --rebuild) REBUILD=true; shift ;;
    --no-browser) NO_BROWSER=true; shift ;;
    --keep-log) KEEP_LOG=true; shift ;;
    --port) MOCKSERVER_PORT="$2"; shift 2 ;;
    --ui-port) UI_PORT="$2"; shift 2 ;;
    --ca) CA_CERT="$2"; shift 2 ;;
    --reverse-proxy) REVERSE_PROXY="$2"; shift 2 ;;
    --help|-h) sed -n '2,40p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'; exit 0 ;;
    --) shift; TOOL_ARGS+=("$@"); break ;;
    claude|opencode|tabnine|none) TOOL="$1"; shift ;;
    *) echo "Unknown argument: $1 (use --help)"; exit 1 ;;
  esac
done

if [ "$TOOL" != "none" ] && ! command -v "$TOOL" >/dev/null 2>&1; then
  echo "ERROR: tool '$TOOL' is not installed / not on PATH."
  echo "       Install it, or run with no TOOL to just start the servers + print the env block."
  exit 1
fi
[ -f "$CA_CERT" ] || { echo "ERROR: CA cert not found: $CA_CERT (override with --ca)"; exit 1; }

echo "========================================"
echo "MockServer UI + live LLM capture"
echo "========================================"

# --- locate or build the runnable MockServer JAR (rebuild only if a server-side
#     Java source / pom is newer than the jar — UI edits are served live by vite) -----
find_jar() {
  local jar
  jar=$(ls -t "$REPO_ROOT"/mockserver/mockserver-netty-no-dependencies/target/mockserver-netty-no-dependencies-*.jar 2>/dev/null \
    | grep -Ev '(-sources|-javadoc|/original-)' | head -1)
  [ -n "$jar" ] && { echo "$jar"; return 0; } || return 1
}

NEED_BUILD=false; BUILD_REASON=""
if [ "$REBUILD" = true ]; then
  NEED_BUILD=true; BUILD_REASON="--rebuild requested"
elif ! find_jar >/dev/null; then
  NEED_BUILD=true; BUILD_REASON="no MockServer JAR found"
else
  STALE_SRC="$(find "$REPO_ROOT/mockserver" -not -path '*/target/*' \( \( -path '*/src/main/*' -name '*.java' \) -o -name 'pom.xml' \) -newer "$(find_jar)" -print 2>/dev/null | head -1)"
  if [ -n "$STALE_SRC" ]; then
    NEED_BUILD=true
    BUILD_REASON="JAR is stale — $(basename "$STALE_SRC") changed since it was built; rebuilding so server-side detection matches the checked-out code"
  fi
fi

if [ "$NEED_BUILD" = true ]; then
  BUILD_LOG="$UI_DIR/mockserver-build.log"
  echo "→ Building MockServer JAR ($BUILD_REASON)"
  echo "  (this can take a few minutes; full log: $BUILD_LOG)"
  # BOTH -DskipTests and -DskipITs are required — -DskipTests alone leaves maven-failsafe-plugin
  # running the *IntegrationTest suites (incl. Testcontainers live-broker tests), turning this
  # jar build into a full verify run that looks hung. See launch-with-demo-data.sh.
  set +e
  ( cd "$REPO_ROOT/mockserver" && ./mvnw clean install -DskipTests -DskipITs -pl mockserver-netty-no-dependencies -am ) 2>&1 \
    | tee "$BUILD_LOG" \
    | grep --line-buffered -E '\[INFO\] Building |\[INFO\] BUILD (SUCCESS|FAILURE)|\[ERROR\]' \
    | grep --line-buffered -vE 'npm warn|npm notice|EBADENGINE|vite-reporter|chunks are larger|dynamic import\(\)|codeSplitting|chunkSizeWarningLimit|PLUGIN_TIMINGS|rolldown\.rs|hooks are listed|Additional hook time|slowest hooks'
  build_rc=${PIPESTATUS[0]}
  set -e
  if [ "$build_rc" -ne 0 ]; then
    echo "ERROR: MockServer build failed (exit $build_rc) — last 40 log lines:"; tail -40 "$BUILD_LOG"; exit 1
  fi
  echo "✓ Build complete"
fi
MOCKSERVER_JAR="$(find_jar)" || { echo "ERROR: MockServer JAR not found after build"; exit 1; }
echo "✓ MockServer JAR: $(basename "$MOCKSERVER_JAR")"

# --- install UI deps if needed --------------------------------------------
if [ ! -d "$UI_DIR/node_modules" ]; then
  echo "→ Installing UI dependencies..."; (cd "$UI_DIR" && npm ci)
fi

# --- start MockServer (default built-in CA so NODE_EXTRA_CA_CERTS=repo CA is trusted) ---
MOCKSERVER_LOG="$UI_DIR/mockserver-capture.log"
HEAP_DUMP="$UI_DIR/mockserver-capture-heap.hprof"
# Default heap raised to 2g: LLM capture retains whole request/response bodies (tool schemas + growing
# conversation context + accumulated SSE) in the event log; the previous 1g default OOM'd on long sessions.
DEMO_MAX_HEAP="${CAPTURE_MAX_HEAP:-2g}"
DEMO_MAX_LOG_ENTRIES="${CAPTURE_MAX_LOG_ENTRIES:-5000}"
# OOM guard — byte budget for the in-memory event log (mockserver.maxEventLogSizeInBytes). maxLogEntries
# caps the COUNT of entries, not their SIZE, so a few thousand multi-hundred-KB LLM turns can still blow the
# heap. This caps the retained request/response BODY bytes; once exceeded, the oldest entries are evicted.
# It measures primary body bytes only (actual heap retention is a small multiple), so keep it well under the
# heap. Default 256 MB of bodies under the 2g heap. Set 0 to disable (count-only, the old behaviour).
CAPTURE_MAX_EVENT_LOG_BYTES="${CAPTURE_MAX_EVENT_LOG_BYTES:-268435456}"
# Disk capture — append every proxied exchange (FULL bodies) to an NDJSON file as it completes
# (mockserver.persistRecordedRequestsToDisk). This is the durable record of the whole session: even as the
# in-memory window evicts under the byte budget above, nothing is lost — the file keeps the complete history
# for offline processing / LLM-Optimise export. One compact JSON object (request + response) per line.
CAPTURE_RECORDED_REQUESTS_PATH="${CAPTURE_RECORDED_REQUESTS_PATH:-$UI_DIR/recordedRequests.ndjson}"
# Log level (default INFO). Set CAPTURE_LOG_LEVEL=DEBUG to surface the new streaming-decision diagnostics
# (STREAM vs AGGREGATE, the triggering condition, and time-to-first-byte) and the SNI hostname appended to
# SSL/decoder-fault log lines — useful when debugging whether MockServer is streaming a response correctly
# or which target host a failed TLS handshake was for.
CAPTURE_LOG_LEVEL="${CAPTURE_LOG_LEVEL:-INFO}"
# Head-wait budget (ms). Default 300s: a reasoning model on a very large prompt (observed a 74KB / ~73k
# -token Codex turn return NOTHING within 120s) can take minutes to its first token; MockServer must wait
# at least as long as the CLI's own request timeout or it 502s a slow-but-healthy call. Override via env.
CAPTURE_SOCKET_TIMEOUT_MS="${CAPTURE_SOCKET_TIMEOUT_MS:-300000}"
# Reverse-proxy (port-forwarding) mode: when --reverse-proxy HOST[:PORT] is given, MockServer forwards
# ALL traffic to that single upstream (default port 443), so the tool points its BASE URL at MockServer
# instead of setting HTTPS_PROXY. This route goes through the same streaming + forwardProxyHttp2Upgrade
# path as the forward proxy (NOT the CONNECT loopback), so it is also a clean way to isolate whether a
# timeout is CONNECT-specific.
REVERSE_ARGS=()
if [ -n "$REVERSE_PROXY" ]; then
  REVERSE_HOST="${REVERSE_PROXY%%:*}"
  REVERSE_PORT="${REVERSE_PROXY##*:}"; [ "$REVERSE_PORT" = "$REVERSE_PROXY" ] && REVERSE_PORT=443
  REVERSE_ARGS=(-proxyRemoteHost "$REVERSE_HOST" -proxyRemotePort "$REVERSE_PORT")
  echo "→ Starting MockServer (REVERSE proxy → https://$REVERSE_HOST:$REVERSE_PORT) on port $MOCKSERVER_PORT (log: $MOCKSERVER_LOG)..."
else
  echo "→ Starting MockServer (proxy) on port $MOCKSERVER_PORT (max heap: $DEMO_MAX_HEAP, log: $MOCKSERVER_LOG)..."
fi
# forwardProxyHttp2Upgrade: forward the (HTTP/1.1) CLI's TLS-intercepted requests to the upstream over
# HTTP/2 via ALPN, so a streaming SSE backend that streams the response head over HTTP/2 sends it
# immediately. ALPN falls back to HTTP/1.1 if the upstream does not offer HTTP/2, so this is safe for
# every provider.
# maxSocketTimeoutInMillis (head-wait budget, $CAPTURE_SOCKET_TIMEOUT_MS): a reasoning LLM can take far
# longer than the 20s default to produce its FIRST token on a large prompt — on a 74KB/~73k-token Codex
# turn the upstream returned NOTHING within 120s, so MockServer 502'd it (ReadTimeoutException) even though
# opencode's own (longer) timeout would have waited it out. MockServer must wait at least as long as the
# CLI does. Once the head arrives the streaming relay takes over and streamIdleTimeoutSeconds (default 60s)
# governs the per-chunk pace. (This is the only MockServer-side lever for these timeouts — the first-token
# latency itself is the model's compute time; if a CLI's own request timeout is shorter than the upstream
# first token, raise it CLI-side too.)
# Crash diagnostics: all stdout+stderr go to $MOCKSERVER_LOG. HeapDumpOnOutOfMemoryError +
# ExitOnOutOfMemoryError turn a Java heap exhaustion into a captured .hprof and a clean exit with an
# OutOfMemoryError in the log — so if the process dies you can distinguish a real OOM (OutOfMemoryError in
# the log + a heap dump at $HEAP_DUMP) from an EXTERNAL kill (process gone, NO Java error in the log — e.g.
# another tool/session SIGKILLing it, or the OS OOM-killer; check `dmesg` on Linux).
# Fresh archive each session (unless --keep-log): MockServer opens the NDJSON in APPEND mode at startup, so
# truncate it here BEFORE launching, so you see only this session's captured exchanges.
if [ "$KEEP_LOG" = false ]; then : > "$CAPTURE_RECORDED_REQUESTS_PATH" || true; fi
java -Xmx"$DEMO_MAX_HEAP" -Dmockserver.maxLogEntries="$DEMO_MAX_LOG_ENTRIES" \
     -XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath="$HEAP_DUMP" -XX:+ExitOnOutOfMemoryError \
     -Dmockserver.metricsEnabled=true -Dmockserver.wasmEnabled=true \
     -Dmockserver.forwardProxyHttp2Upgrade=true \
     -Dmockserver.maxSocketTimeoutInMillis="$CAPTURE_SOCKET_TIMEOUT_MS" \
     -Dmockserver.maxEventLogSizeInBytes="$CAPTURE_MAX_EVENT_LOG_BYTES" \
     -Dmockserver.persistRecordedRequestsToDisk=true \
     -Dmockserver.persistedRecordedRequestsPath="$CAPTURE_RECORDED_REQUESTS_PATH" \
     -jar "$MOCKSERVER_JAR" -serverPort "$MOCKSERVER_PORT" \
     ${REVERSE_ARGS[@]+"${REVERSE_ARGS[@]}"} \
     -logLevel "$CAPTURE_LOG_LEVEL" > "$MOCKSERVER_LOG" 2>&1 &
MOCKSERVER_PID=$!

UI_PID=""
cleanup() {
  echo ""
  echo "→ Stopping servers..."
  [ -n "${UI_PID:-}" ] && kill "$UI_PID" 2>/dev/null || true
  [ -n "${MOCKSERVER_PID:-}" ] && kill "$MOCKSERVER_PID" 2>/dev/null || true
  wait 2>/dev/null || true
  echo "✓ Stopped"
}
trap cleanup INT TERM EXIT

wait_for() {
  local url="$1" name="$2" method="${3:-GET}" timeout=60 elapsed=0
  echo "  Waiting for $name..."
  until curl -sf -X "$method" "$url" >/dev/null 2>&1; do
    [ "$elapsed" -ge "$timeout" ] && { echo "ERROR: $name did not start within ${timeout}s"; return 1; }
    sleep 1; elapsed=$((elapsed + 1))
  done
}

wait_for "http://localhost:$MOCKSERVER_PORT/mockserver/status" "MockServer" PUT
echo "✓ MockServer ready (PID $MOCKSERVER_PID)"

if [ "$KEEP_LOG" = false ]; then
  curl -s -X PUT "http://localhost:$MOCKSERVER_PORT/mockserver/clear?type=LOG" \
    -H 'Content-Type: application/json' -d '{}' >/dev/null || true
fi

# --- start UI dev server (serves the latest dashboard code, proxied to MockServer) ---
echo "→ Starting UI dev server on port $UI_PORT..."
(cd "$UI_DIR" && MOCKSERVER_URL="http://localhost:$MOCKSERVER_PORT" npm run dev -- --port "$UI_PORT" >/dev/null 2>&1) &
UI_PID=$!
UI_URL="http://localhost:$UI_PORT/mockserver/dashboard/?port=$MOCKSERVER_PORT"
wait_for "http://localhost:$UI_PORT/mockserver/dashboard/" "UI dev server"
echo "✓ UI dev server ready (PID $UI_PID)"

if [ "$NO_BROWSER" = false ]; then
  if command -v open >/dev/null 2>&1; then open "$UI_URL"
  elif command -v xdg-open >/dev/null 2>&1; then xdg-open "$UI_URL"; fi
fi

# --- env every launched tool needs ----------------------------------------
export NODE_EXTRA_CA_CERTS="$CA_CERT" SSL_CERT_FILE="$CA_CERT" REQUESTS_CA_BUNDLE="$CA_CERT"
export NODE_USE_SYSTEM_CA=1
if [ -n "$REVERSE_PROXY" ]; then
  # Reverse mode: the tool points its BASE URL at MockServer (no HTTPS_PROXY). For a standard
  # OpenAI-API tool, OPENAI_BASE_URL is enough; other tools need their own base-URL setting.
  export OPENAI_BASE_URL="https://localhost:$MOCKSERVER_PORT/v1"
  export OPENAI_API_BASE="https://localhost:$MOCKSERVER_PORT/v1"
else
  export HTTPS_PROXY="http://localhost:$MOCKSERVER_PORT" HTTP_PROXY="http://localhost:$MOCKSERVER_PORT"
  export https_proxy="http://localhost:$MOCKSERVER_PORT" http_proxy="http://localhost:$MOCKSERVER_PORT"
  export NODE_USE_ENV_PROXY=1
fi

echo ""
echo "========================================"
echo "✓ Ready — watch traffic appear live"
echo "========================================"
echo "  Dashboard : $UI_URL"
echo "              (Traffic · LLM Traces · LLM Optimise tabs)"
echo "  MockServer log: $MOCKSERVER_LOG"
echo "  Captured exchanges (NDJSON, full bodies): $CAPTURE_RECORDED_REQUESTS_PATH"
echo "  Event-log memory cap: $CAPTURE_MAX_EVENT_LOG_BYTES bytes of bodies (older entries evicted; disk archive keeps all)"
echo ""
if [ -n "$REVERSE_PROXY" ]; then
  echo "  REVERSE proxy → https://$REVERSE_PROXY  (no HTTPS_PROXY). Point your tool's BASE URL here:"
  echo "    export OPENAI_BASE_URL=https://localhost:$MOCKSERVER_PORT/v1"
  echo "    export SSL_CERT_FILE=$CA_CERT   (and NODE_EXTRA_CA_CERTS for node tools)"
  echo "  NOTE: opencode's Codex backend (chatgpt.com) is pinned to its subscription auth and is NOT"
  echo "        base-URL-overridable, so reverse mode suits standard-API tools; for opencode use the"
  echo "        default forward (HTTPS_PROXY) mode."
else
  echo "  To proxy a coding CLI through MockServer in any terminal, export:"
  echo "    export HTTPS_PROXY=http://localhost:$MOCKSERVER_PORT"
  echo "    export NODE_EXTRA_CA_CERTS=$CA_CERT"
  echo "    export SSL_CERT_FILE=$CA_CERT"
  echo "  then run:  claude   |   opencode   |   tabnine --skip-trust"
fi
echo "========================================"
echo ""

if [ "$TOOL" = "none" ]; then
  echo "No tool selected — servers are running. Launch a CLI in another terminal with the"
  echo "env above, or re-run with a tool: npm run capture -- opencode"
  echo "Press Ctrl+C to stop."
  wait
else
  # tabnine refuses to run in an untrusted dir; default it to --skip-trust when the
  # caller passed no args of their own. (if/then, not `&&`, so set -e is not tripped.)
  if [ "$TOOL" = "tabnine" ] && [ "${#TOOL_ARGS[@]}" -eq 0 ]; then
    TOOL_ARGS=(--skip-trust)
  fi
  echo "→ Launching '$TOOL' through the proxy (interactive). Exit it — or press Ctrl+C — to stop the servers."
  echo ""
  # Foreground + interactive so you drive the real UX; the EXIT trap tears down the servers.
  # Guard the array expansion so it is safe on bash 3.2 (macOS) under `set -u`.
  if [ "${#TOOL_ARGS[@]}" -gt 0 ]; then
    "$TOOL" "${TOOL_ARGS[@]}" || true
  else
    "$TOOL" || true
  fi
fi

#!/usr/bin/env bash
set -euo pipefail

# HTTP/2 multiplex benchmark step (perf queue) — answers the open question from
# issue #2669: what does giving every HTTP/2 stream its own child channel cost
# under concurrency? Two attempts to measure this on the dev laptop failed (memory
# pressure), so it belongs on a quiet CI agent, which is here.
#
# Runs org.mockserver.benchmark.Http2StreamChannelBenchmark: N concurrent streams
# over ONE h2c connection, sweeping N = 1, 10, 100 (the server caps
# MAX_CONCURRENT_STREAMS at 100, so 100 is the single-connection ceiling), and
# emits perf-h2-multiplex.json {h2_multiplex:{streams_<N>:{throughput_rps, p50_us,
# p95_us, p99_us, ...}}} as a Buildkite artifact. perf-test-compare.sh persists it
# into the S3 run history so a trend is visible.
#
# NOTIFY-ONLY, and there is deliberately NO pass/fail threshold: run-to-run
# variance on real build agents is unknown, so a threshold now would be guessing.
# It is recorded so a threshold can be added later once the variance is known.
#
# The harness self-validates and exits NON-ZERO (code 2) on any integrity breach
# (incomplete run, non-200/body mismatch, sub-µs latency, impossible throughput).
# That failure is HARNESS-VALIDATION and is intentionally distinct from "perf got
# slower" (which never fails — there is no threshold). A non-zero exit here means
# the measurement is untrustworthy and MUST surface as a red build, so this step
# has no soft_fail. Before the sweep we run the harness `selftest` (server-free)
# to prove every gate still fires.
#
# Heavy (builds mockserver-netty + boots a real server), so it runs only in the
# scheduled/manual perf pipeline on the dedicated box.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../../.." && pwd)"

MAVEN_IMAGE="${MAVEN_IMAGE:-mockserver/mockserver:maven}"

# Sweep + iteration budget (env-tunable). Defaults give N*requestsPerStream total
# requests per point (e.g. 100*500 = 50,000 at N=100) — enough to be meaningful on
# a quiet box while staying inside the step timeout.
H2_BENCH_STREAMS="${H2_BENCH_STREAMS:-1,10,100}"
H2_BENCH_REQUESTS_PER_STREAM="${H2_BENCH_REQUESTS_PER_STREAM:-500}"
H2_BENCH_WARMUP_REQUESTS="${H2_BENCH_WARMUP_REQUESTS:-200}"

OUT_JSON="$REPO_ROOT/perf-h2-multiplex.json"

echo "--- building mockserver-netty, then running the HTTP/2 multiplex benchmark"
# shellcheck disable=SC2016
"$SCRIPT_DIR/../run-in-docker.sh" \
  -i "$MAVEN_IMAGE" \
  -m "${MAVEN_MEMORY:-7g}" \
  --entrypoint bash \
  -w /build \
  -e "H2_BENCH_STREAMS=$H2_BENCH_STREAMS" \
  -e "H2_BENCH_REQUESTS_PER_STREAM=$H2_BENCH_REQUESTS_PER_STREAM" \
  -e "H2_BENCH_WARMUP_REQUESTS=$H2_BENCH_WARMUP_REQUESTS" \
  -- -c '
    set -euo pipefail
    cd /build/mockserver                       # the Maven reactor root (pom.xml lives here, not /build)
    mvn -q -pl mockserver-netty -am install -DskipTests -Djacoco.skip=true -Dcheckstyle.skip=true
    cd mockserver-benchmark
    mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true
    CP="target/classes:$(cat target/classpath.txt)"
    # Prove every validation gate fires BEFORE trusting a measured run (server-free, fast).
    java -cp "$CP" org.mockserver.benchmark.Http2StreamChannelBenchmark selftest
    # The measured sweep. A gate breach here exits non-zero and fails the step loudly.
    java -cp "$CP" org.mockserver.benchmark.Http2StreamChannelBenchmark /build/perf-h2-multiplex.json
  '

if [ ! -f "$OUT_JSON" ]; then
  echo "ERROR: the HTTP/2 multiplex benchmark did not produce $OUT_JSON" >&2
  exit 1
fi

echo "--- perf-h2-multiplex.json"
cat "$OUT_JSON"

if command -v buildkite-agent >/dev/null 2>&1; then
  buildkite-agent artifact upload "perf-h2-multiplex.json" || true
fi

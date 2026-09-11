#!/usr/bin/env bash
#
# Build and run the HTTP/2 multiplex throughput/latency benchmark (issue #2669).
#
# Answers: what does giving every HTTP/2 stream its own child channel cost under
# concurrency? Drives N concurrent streams over ONE h2c connection and reports
# throughput + p50/p95/p99, sweeping N = 1, 10, 100 (the server caps
# MAX_CONCURRENT_STREAMS at 100, so 100 is the single-connection ceiling).
#
#   ./run-h2-multiplex.sh                     # full sweep, writes perf-h2-multiplex.json
#   ./run-h2-multiplex.sh selftest            # prove every validation gate fires (no server)
#   H2_BENCH_STREAMS=1 H2_BENCH_REQUESTS_PER_STREAM=5 ./run-h2-multiplex.sh   # tiny smoke
#
# Tunables (env): H2_BENCH_STREAMS, H2_BENCH_REQUESTS_PER_STREAM, H2_BENCH_WARMUP_REQUESTS,
# H2_BENCH_TIMEOUT_S, H2_BENCH_OUTPUT.
#
# The harness self-validates and exits NON-ZERO (code 2, "HARNESS VALIDATION FAILED")
# on any integrity breach — an incomplete run, a non-200/body mismatch, a sub-µs
# latency, or a physically-impossible throughput. That is deliberately distinct from
# a slow-but-valid run, which exits 0 and simply records slower numbers.
#
# Requires mockserver-netty installed locally first:
#   (cd .. && ./mvnw -pl mockserver-netty -am install -DskipTests)
#
set -euo pipefail
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "${DIR}"

mvn -q compile dependency:build-classpath -Dmdep.outputFile=target/classpath.txt -Djacoco.skip=true

CP="target/classes:$(cat target/classpath.txt)"
exec java -cp "${CP}" org.mockserver.benchmark.Http2StreamChannelBenchmark "$@"

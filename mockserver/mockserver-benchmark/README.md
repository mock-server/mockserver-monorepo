# mockserver-benchmark

JMH (Java Microbenchmark Harness) micro-benchmarks for MockServer's
request-matching **hot path**.

This module is **not part of the default build**. It is deliberately left out of
the parent `pom.xml` `<modules>` list so `mvn install` and CI never compile it —
JMH's annotation processor and the benchmark classpath must not touch production
artifacts. Build and run it on demand with `./run.sh`.

## Why it exists

The Part-A hot-path optimizations (see `docs/plans/…performance…`) are mostly
**allocation** reductions — e.g. a `MatchDifference` (backed by a
`ConcurrentHashMap`) allocated *per matcher, per request*, and a sorted matcher
list rebuilt on every request. k6 (the end-to-end load harness) proves the
*user-visible* throughput/latency win but is too noisy to prove an allocation
reduction. JMH with the GC profiler (`-prof gc`) measures
**bytes-allocated-per-op** for a single method in isolation, handling JIT
warmup, dead-code elimination and constant folding.

**Gate:** no A1/A2 allocation "win" is committed without before/after
`gc.alloc.rate.norm` numbers from this module.

## Running

```bash
# one-time: install the module under test
(cd .. && mvn -o -pl mockserver-core install -DskipTests)

# the important run — allocation profile across scan lengths and matcher shapes
./run.sh -prof gc

# a focused, faster run
./run.sh -prof gc -f 1 -wi 3 -i 5 -p expectationCount=100 -p matcherType=EXACT

# list benchmarks / pass any other JMH option
./run.sh -l
```

## What is measured

`MatchingBenchmark.firstMatchingExpectation_noMatch` calls
`RequestMatchers.firstMatchingExpectation(...)` with a request that matches
**none** of the registered expectations — the worst case that forces a full
scan of all N matchers (every matcher allocates a `MatchDifference` and is
evaluated).

Parameters:

| Param | Values | Meaning |
|-------|--------|---------|
| `expectationCount` | 1, 10, 100, 1000 | number of registered expectations (scan length) |
| `matcherType` | EXACT, REGEX, JSON_BODY | shape of the registered matchers |

It uses the default `Configuration` (metrics off, `detailedMatchFailures` off,
INFO logging off) — the common case Part A optimizes. The headline number for
the allocation work is **`gc.alloc.rate.norm`** (B/op); `ns/op` (shown as µs/op)
is the secondary signal.

## Scaling sweep (`run-scaling.sh`)

`run-scaling.sh` is a sibling of `run.sh` that runs a **fixed** param sweep and
emits a machine-readable `perf-scaling.json` (the documentation-site chart
contract). It is not free-form like `run.sh`: it runs two benchmark sets and
reshapes their JMH JSON with `jq`.

```bash
# full sweep (bounded default iterations), writes <repo-root>/perf-scaling.json
./run-scaling.sh

# fast validation run (seconds-per-combo)
JMH_ARGS_SCALING='-f 1 -wi 1 -i 2 -r 1 -w 1' ./run-scaling.sh

# custom output path
SCALING_RESULT_PATH=/tmp/perf-scaling.json ./run-scaling.sh
```

What it measures and the output shape:

| Set | Benchmark | Sweep | Shows |
|-----|-----------|-------|-------|
| `matching` | `MatchingBenchmark` | `expectationCount={1,10,100,1000}` × `matcherType={EXACT,REGEX}`, `logLevel=WARN`, `-prof gc` | matching time + allocation **grow** with scan length |
| `candidate_index` | `CandidateIndexBenchmark` | `n={1,10,100,1000,5000}` × `indexMode={SCAN,INDEX}`, `outcome=MISS`, `shape=LITERAL` | SCAN grows; **INDEX stays ~flat** |

```json
{
  "scaling": {
    "matching": [
      {"expectations": 1, "matcherType": "EXACT", "time_per_op": 0.42, "time_unit": "us/op", "alloc_bytes_per_op": 120.0}
    ],
    "candidate_index": [
      {"mode": "INDEX", "expectations": 5000, "time_per_op": 0.9, "time_unit": "us/op"}
    ]
  }
}
```

`time_per_op` = `primaryMetric.score`, `time_unit` = `primaryMetric.scoreUnit`,
`alloc_bytes_per_op` = `secondaryMetrics["gc.alloc.rate.norm"].score` (null if
absent; only on the `matching` set). `expectations` is the integer param
(`expectationCount` for `matching`, `n` for `candidate_index`). A validated
sample lives at `fixtures/sample-perf-scaling.json`.

## Workflow for a Part-A change

1. `./run.sh -prof gc | tee before.txt`
2. Make the A1/A2 change in `mockserver-core`, `mvn -o -pl mockserver-core install -DskipTests`.
3. `./run.sh -prof gc | tee after.txt`
4. Compare `gc.alloc.rate.norm` (and `ns/op`). Quote the delta in the commit.

## HTTP/2 multiplex benchmark (`run-h2-multiplex.sh`) — issue #2669

Unlike the JMH benchmarks above (which measure a single hot-path *method* in
isolation), `Http2StreamChannelBenchmark` is an **end-to-end** harness: it boots a
real `MockServer` and drives it over an h2c connection to answer the open question
from issue #2669 — *what does giving every HTTP/2 stream its own child channel cost
under concurrency?* Since #2669 the server's only HTTP/2 pipeline is
`Http2FrameCodec + Http2MultiplexHandler`, so every stream is a child channel with
its own pipeline (strictly more per-stream allocation than a shared connection
pipeline).

Because it boots a full server, this harness depends on `mockserver-netty` (not just
`mockserver-core`); install that first.

```bash
# one-time: install the server under test
(cd .. && ./mvnw -pl mockserver-netty -am install -DskipTests)

# full sweep (N = 1, 10, 100 concurrent streams over ONE connection) -> perf-h2-multiplex.json
./run-h2-multiplex.sh

# tiny local smoke (proves the client completes real round trips)
H2_BENCH_STREAMS=1 H2_BENCH_REQUESTS_PER_STREAM=5 H2_BENCH_WARMUP_REQUESTS=0 ./run-h2-multiplex.sh

# prove every validation gate fires (server-free)
./run-h2-multiplex.sh selftest
```

**Concurrency model.** Each HTTP/2 request gets its *own* stream — that is the unit
whose per-stream cost #2669 changed. Concurrency `N` is realised as `N` driver
threads ("lanes"); each lane issues `H2_BENCH_REQUESTS_PER_STREAM` requests
sequentially, opening a fresh `Http2StreamChannel` per request, so at any instant
there are exactly `N` in-flight streams. Total requests per point = `N ×
requestsPerStream`. The server caps `MAX_CONCURRENT_STREAMS` at 100, so **100 is the
concurrency ceiling on a single connection** — higher concurrency is a
multi-connection question and is intentionally not attempted here.

**Self-validation (mandatory, fails loudly).** A run publishes numbers only if it
passes five harness-integrity gates, otherwise it prints `HARNESS VALIDATION FAILED`
and exits code **2**: (1) the latch counts *total* expected responses (`streams ×
requestsPerStream`) and asserts `completed == expected`; (2) every response is HTTP
200 with the exact body; (3) a ~1 µs latency floor (a loopback round trip cannot be
faster); (4) a throughput ceiling that rejects physically-impossible numbers; (5)
any reset/timeout/non-200 fails the run. These are *integrity* checks, deliberately
distinct from any performance threshold — a gate failure means the measurement is
untrustworthy; a slow-but-valid run exits 0 and simply records slower numbers. Run
`selftest` to see every gate fire against fabricated results.

**Output shape.** `{ "h2_multiplex": { "streams_<N>": {throughput_rps, p50_us,
p95_us, p99_us, min_us, max_us, requests} } }`. `fixtures/sample-perf-h2-multiplex.json`
documents this shape — its **numbers are illustrative placeholders, not a
measurement** (real figures land in the S3 run history from the first CI run). In CI
the notify-only `perf-test-h2multiplex.sh` step (perf queue) records it into the
run-history baseline; there is **no pass/fail threshold yet** because run-to-run
variance on real agents is unknown — setting one now would be guessing.

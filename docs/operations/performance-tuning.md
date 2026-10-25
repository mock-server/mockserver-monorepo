# Performance Tuning

Internal companion to the user-facing [Performance page](https://www.mock-server.com/mock_server/performance.html) and [Performance Configuration include](https://www.mock-server.com/mock_server/configuration_properties.html#performance). Where the website tells users *what knobs exist*, this doc explains *why each knob behaves the way it does*, points at the implementation, and captures the rules-of-thumb that aren't worth publishing externally.

## Where the budget actually goes

Three pools account for almost all of MockServer's CPU and memory under load:

| Pool | Sized by | What it bounds |
|------|----------|----------------|
| Netty event loops (server + outbound client) | `nioEventLoopThreadCount`, `clientNioEventLoopThreadCount` | Concurrent socket I/O — incoming connections, proxied outbound, SSE/WebSocket fan-out |
| Action-handler executor | `actionHandlerThreadCount` | Synchronous response/forward/callback dispatch off the event loop |
| LMAX Disruptor ring buffer + log retention | `maxLogEntries` (auto-derived from heap), `maxExpectations` | Recorded requests, verification log, persistent event store |

Read [docs/code/memory-management.md](../code/memory-management.md) before touching `maxLogEntries` / `maxExpectations` — those defaults are derived from heap size at first read, so changing heap without setting the limits explicitly can quietly shift them by an order of magnitude.

For the request-processing flow itself, [docs/code/request-processing.md](../code/request-processing.md) and [docs/code/netty-pipeline.md](../code/netty-pipeline.md) describe where each handler runs and how requests cross the event-loop / executor boundary.

## Optimization principles

One standing rule governs every performance change in this codebase:

**A performance change must never regress the small/common case in order to speed up the large/rare one.** Fast-path thresholds — the point at which an optimization kicks in, a cache is consulted, or a code path branches — must be chosen **empirically** from the JMH break-even point, not intuition. Always benchmark both tiny-N (the typical unit-test or low-load request) and large-N (the sustained-throughput or high-cardinality case) and gate the change on **no regression in either**. A threshold that is too low hurts the common case; a threshold that is too high means the optimization never fires at the scale it was designed for. The JMH harness in `mockserver-benchmark/` (run via `perf-test-microbench.sh`) and the k6 regression suite in `mockserver-performance-test/` are the canonical verification tools.

## Rules of thumb

These are the heuristics maintainers reach for when tuning real workloads. They are not contractual — measure before you change.

- **CPU-bound matching → grow `nioEventLoopThreadCount`** to `2 × cores` and leave `actionHandlerThreadCount` alone. Useful when most requests resolve from matchers without action dispatch (read-heavy verification workloads).
- **Action-heavy → grow `actionHandlerThreadCount`** to `4 × cores` and keep event loops at default. Useful when actions block on outbound IO (forwards, callbacks, JavaScript template evaluation).
- **Recording-heavy → raise heap before raising `maxLogEntries`.** The default is derived from heap; doubling `maxLogEntries` without giving the JVM more memory just shifts the eviction pressure into GC overhead.
- **Streaming heavy (SSE / WebSocket fan-out) → check the outbound event-loop count first** (`clientNioEventLoopThreadCount` and `webSocketClientEventLoopThreadCount`); the inbound side rarely saturates first.
- **mTLS or per-cert renegotiation in the hot path → enable `proactivelyInitialiseTLS=true`.** Defers nothing to first-connect; turn-on cost is one slow startup, ongoing cost is zero per-connection.
- **Proxy / forward heavy workloads benefit from `forwardConnectionPoolEnabled` (on by default).** MockServer pools and reuses idle HTTP/1.1 keep-alive upstream connections. The k6 forward baseline showed that opening a fresh connection per request can exhaust ephemeral ports under sustained load (21% errors at 750 rps, 68% at 1500 rps, 212k `BindException`s from local port exhaustion); pooling drove this to ~0% errors with no latency regression in a controlled A/B. Pooling is **safe to leave on**: the forward client runs on a dedicated event-loop group disjoint from the server workers (so a loopback object-callback can never self-deadlock a pooled connection) and a connection is only returned to the pool when its HTTP codec is genuinely clean — MockServer's `error()` action (raw bytes / drop connection) and any non-HTTP/malformed upstream reply are never pooled, so a later request can never reuse a corrupted connection. Set `forwardConnectionPoolEnabled=false` to restore the historical fresh-connection-per-request behaviour for unusual upstreams. Pooling applies to HTTP/1.1 keep-alive upstreams and to HTTP/2 upstreams (an HTTP/2 connection is reused with a fresh stream per request, keyed by host/port/scheme/protocol so HTTP/1.1 and HTTP/2 never mix) — this is what stops an HTTPS forward proxy whose client negotiated HTTP/2 in a CONNECT (MITM) tunnel from churning a fresh upstream connection per request. HTTP/3, binary forwarding, streaming responses, proxy-tunnelled connections, `Connection: close` upstreams, and any reply that did not parse as a valid HTTP response always use a fresh connection.
- **Under sustained high-rate, low-latency forwarding/injection, enable `forwardConnectionPoolKeepAlive` (opt-in, default off).** When a fast upstream returns in well under a millisecond, requests are dispatched faster than earlier connections are returned to the pool, so each `acquire` misses, opens a fresh connection, and the surplus is then closed back down to `forwardConnectionPoolMaxIdlePerKey` — requests-per-connection collapses (measured ~210 at 2000 rps falling to ~3.4 at 4000 rps for a trivial GET) and a single instance pegs CPU on connection setup. Raising `forwardConnectionPoolMaxIdlePerKey` alone does not fix this because the bottleneck is the acquire-miss during the in-flight window, not the release cap. `forwardConnectionPoolKeepAlive=true` RETAINS warm connections on release (up to `forwardConnectionPoolMaxTotalPerKey`, default 2000) instead of closing them, so the warm set grows to match the offered concurrency and is reused — eliminating the churn. With keep-warm off the pool's release close-decision is byte-for-byte unchanged. Warm connections are still reaped after `forwardConnectionPoolIdleTimeoutMillis` of inactivity, so the pool drains when load stops (no leak). The acquire hot path is identical in both modes.
- **TCP keepalive on forward/proxy upstream connections is on by default (`forwardSocketKeepAlive`).** The forward client sets `SO_KEEPALIVE` so the OS detects dead/half-open upstream connections faster and keeps NAT/firewall mappings warm. On the native epoll transport the timers are tuned (`forwardSocketKeepAliveIdleSeconds` 60, `forwardSocketKeepAliveIntervalSeconds` 15, `forwardSocketKeepAliveCount` 4 → ~120s dead-peer detection); on NIO only `SO_KEEPALIVE` is set (epoll is required for timer tuning). This is a benign default-on hardening (standard for production HTTP clients; only an occasional probe packet on idle connections) — set `forwardSocketKeepAlive=false` to restore the historical no-keepalive behaviour. **Keepalive complements the idle reaper, it does not replace it:** with the default `forwardConnectionPoolIdleTimeoutMillis` (30s) idle pooled connections are reaped *before* the 60s keepalive idle fires, so the main keepalive wins are (a) half-open detection during active/long-lived/streaming requests (where the reaper does not apply because the connection is in use) and (b) when you raise `forwardConnectionPoolIdleTimeoutMillis` above the keepalive idle. **Stale-connection guidance:** when you enable keep-warm pooling against a real upstream (behind a load balancer/NAT/firewall), keep `forwardConnectionPoolIdleTimeoutMillis` below the upstream/NAT idle-eviction window so idle pooled connections are closed before the path silently drops them; keepalive then catches any that still go half-open during an active request, failing the request cleanly (the caller retries) rather than hanging until the forward timeout.

> **Maintainer verification guard.** The two failure modes this pooling design guards against — the loopback self-deadlock (forward client sharing the server worker group) and the response desync (pooling a dirty keep-alive channel) — only surface in the **failsafe integration phase**, never in a targeted `-Dtest` unit run. This area has regressed **twice** for exactly that reason: per-unit verification skipped the failsafe phase. Any change to the forward connection pool, its quiescence gate (`HttpClientHandler` / `ChannelCleanliness`), or the dedicated forward-client event-loop group (`LifeCycle.forwardClientGroup`, wired via `MockServer.getForwardClientEventLoopGroup()`) **MUST** be verified by running the Extended / Websocket / proxy `*IntegrationTest` classes with pooling on (the default) — for example `ExtendedNettyMockingIntegrationTest`, `WebsocketCallbackRegistryIntegrationTest`, and the proxy integration tests — not just the fast unit guards (`NettyHttpClientConnectionPoolTest`, `ForwardClientEventLoopIsolationTest`, `ForwardConnectionPoolLoopbackCallbackTest`).

## JVM flags

The maven CI build agent invokes the JVM with `-Xms2048m -Xmx6144m` (see `scripts/buildkite_quick_build.sh`). For production-like load testing, set both `-Xms` and `-Xmx` to the same value to avoid heap-resize stalls during the run.

**Docker image heap and GC defaults.** The shipped Docker images cap the JVM heap at 45% of the container memory limit (`-XX:MaxRAMPercentage=45.0` hard-coded in the ENTRYPOINT; 512 MiB is the smallest supported limit (768 MiB for a `-clustered` node with the Infinispan backend) — see [docker.md → Heap Cap](../infrastructure/docker.md#heap-cap)) and default to ZGC via `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC"` (the standard and local images run JDK 26; the root, snapshot and GraalJS images run JDK 25 — ZGC is generational by default on both). The `-clustered` image uses `ENV JAVA_TOOL_OPTIONS="-XX:+UseZGC -XX:+ZGenerational"` (JDK 21 base, where ZGenerational must be set explicitly). The `-aot` image keeps G1 (no `JAVA_TOOL_OPTIONS`; see `docker/aot/Dockerfile`). To override the GC or set an explicit heap, set `JAVA_TOOL_OPTIONS` on the container — it replaces the default entirely, so include `-XX:+UseZGC` if you still want it. Setting `-Xmx` disables `MaxRAMPercentage`; choose one mechanism. Passing a different `MaxRAMPercentage` via `JAVA_TOOL_OPTIONS` does not work: the ENTRYPOINT flag is applied last and wins. For the standalone JAR the JVM default collector (G1) is used unless you pass flags yourself.

A heap dump on OOM is *not* enabled by default; for triage runs add `-XX:+HeapDumpOnOutOfMemoryError -XX:HeapDumpPath=/var/log/mockserver/` via `JAVA_OPTS` (standalone JAR) or `JAVA_TOOL_OPTIONS` (Docker).

### GC selection

ZGC has been production-ready since Java 17. For latency-sensitive deployments — particularly those running with large `maxLogEntries` (deep event ring buffers) — `-XX:+UseZGC` typically holds stop-the-world pauses in the single-digit millisecond range (1–5 ms) regardless of heap size, where G1 (the server-class default) commonly sits in the 50–200 ms range during mixed cycles under sustained allocation.

**Which ZGC you get depends on the JVM.** The Docker images (other than `-clustered`) bundle Java 25 or 26, where ZGC is *generational* and `-XX:+UseZGC` alone selects it. The separate `-XX:+ZGenerational` flag was removed in JDK 24: passing it does no harm but has no effect — the JVM logs `Ignoring option ZGenerational; support was removed in 24.0` and starts normally — so drop it from your flags on the main images. On the `-clustered` image (JDK 21) `-XX:+ZGenerational` is already set. On your own Java 17 JVM, `-XX:+UseZGC` gives the older non-generational collector, whose pauses are single-digit milliseconds rather than the sub-millisecond figures generational ZGC reaches.

These numbers are based on typical GC behaviour, not MockServer-specific benchmarks. Use the `mockserver-performance-test/` k6 harness with `mockserver.outputMemoryUsageCsv=true` to confirm your workload before switching.

Rules of thumb for the **standalone JAR** (Docker images already use ZGC by default):
- **Heap < 2 GB:** stay on G1 (the JDK default). ZGC's fixed overhead is not worth it for small heaps.
- **Heap 2–4 GB:** G1 is fine for almost everything. Switch to ZGC only if you've measured GC pauses on the matcher path.
- **Heap ≥ 4 GB and p99 latency matters:** add `-XX:+UseZGC` via `JAVA_OPTS`. Set `-Xms` and `-Xmx` to the same value (e.g. `-Xms4g -Xmx4g`) so the heap is pre-committed.

In containerised deployments, size the container memory limit at least ~1.5× the `-Xmx` value when using ZGC. The kernel OOM-killer reacts to physical memory (RSS), not virtual address space — what eats RSS beyond `-Xmx` is the JVM's own overhead (code cache, metaspace, JIT, thread stacks) plus Netty's direct buffer pool. ZGC adds a further wrinkle on some cgroup setups: it multi-maps the same physical pages for its coloured-pointer scheme, and under certain RSS-accounting modes those pages are counted multiple times against the cgroup limit, so the kernel can OOM-kill the process even though the actual physical footprint fits. Example: `-Xmx4g` → `--memory=6g`.

Shenandoah is deliberately omitted: it has been production-ready since OpenJDK 15 (JEP 379) and is therefore available in OpenJDK 17, but it is absent from Oracle JDK 17 and not universally available across all JDK distributions. ZGC is the simpler recommendation because it ships in every JDK 17 distribution MockServer supports.

## Measuring

Two enable-it-once-then-leave-it knobs:

- `mockserver.metricsEnabled=true` exposes Prometheus metrics at `GET /mockserver/metrics`. The exposed metrics are listed in [docs/code/metrics.md](../code/metrics.md). Always enable this for any non-trivial perf investigation — Buildkite agents have it off by default to avoid skew.
- `mockserver.outputMemoryUsageCsv=true` writes per-second JVM memory snapshots to `memoryUsage_<yyyy-MM-dd>.csv` in the working directory (or `memoryUsageCsvDirectory` if set). Useful when reproducing a leak: grep for the heap line, plot it, and you get the same data the dashboard summary shows.

The repo has a k6 harness in `mockserver-performance-test/` (`k6/load.js` with thresholds as gates). Use it as a starting point for your own scenarios — don't read the numbers as canonical (they're agent-class-dependent).

MockServer can also drive load itself via a **load scenario** (`PUT /mockserver/loadScenario`, off by default; see [docs/code/load-generation.md](../code/load-generation.md)). This is **dual-purpose with the forward path**: a load scenario sends through the same `NettyHttpClient` the forward/proxy actions use and records into the same forward metrics histograms, so the tuning here (worker threads, the forward client, metrics overhead) applies equally whether the forward traffic comes from a proxied client or from a load scenario. The scenario's own safety caps — `loadGenerationMaxVirtualUsers`, `loadGenerationMaxInFlightRequests`, `loadGenerationMaxRequestsPerSecond` — bound how hard it can push and exist to stop the generator self-DoSing the server, not to tune throughput; raise them deliberately and watch the same metrics you would for any forward load.

## What's deliberately not tuned

These look like knobs but are not — changing them rarely helps and often hurts:

- **Ring buffer size** — directly tied to `maxLogEntries` via `nextPowerOfTwo`; do not try to size them independently. The Disruptor needs the power-of-two for its index masking.
- **`mockserver-core` Surefire two-phase parallel execution** — do not collapse it back to a single phase. The bulk of the suite runs with `parallel=classes` (`threadCount=4`); a small `sequential-tests` execution (`parallel=none`) runs the classes that mutate JVM-global state which cannot be thread-isolated (`ConfigurationProperties` system-property config, the static Prometheus `Metrics` registry, and globally-fixed time for assertions on event-log disruptor-thread timestamps). An earlier single-phase `parallel=classes` attempt deadlocked on a `ConfigurationProperties` ↔ `MockServerLogger` `<clinit>` cycle; that cycle is now fixed (`ClassInitializationDeadlockTest`) and the `UUIDService` / `EpochService` / `TimeService` test-mode switches are thread-scoped, so the parallel phase is stable. `ParallelStaticStateGuardTest` enforces that the parallel-excluded and sequential-included class lists stay in sync.
- **`mockserver-netty` integration tests are NOT fork-parallelised** — and this was tried and deliberately reverted (2026-06-01), so do not re-attempt it without first hardening the tests. Failsafe runs them serially (~328s on a 14-core dev box). Forking the suite (`forkCount=4`, both `reuseForks=true` and `=false` tried, with the 6 `PortFactory`-preallocated-port classes split into a `forkCount=1` sequential bucket) cut wall time to ~180s **but was intermittently flaky** — 4 of 5 validation runs failed. The suite is studded with wall-clock timing assertions in the shared `mockserver-integration-testing` base classes (e.g. `shouldReturnResponseForExpectationWithDelay` bounds a 2s server-side delay at ≤4s; the various `*WithDelay` forward/callback tests are similar) that do not survive the CPU/scheduler contention and per-class JVM churn of parallel forks: observed blow-outs ranged from 8.6s to >1000s, plus TLS-forward timing failures. State isolation is *not* the blocker (port 0 binding, unique temp files, and per-fork `ConfigurationProperties` all hold up) — the timing assertions are. Re-enabling fork parallelism here requires first making those assertions robust (relative/tolerant bounds, or isolating the Scheduler from contention), which is a larger change to shared test infrastructure. Netty *unit* tests are also left single-phase: one timing-bound class (`DashboardWebSocketHandlerTest`, ~30s) dominates and fixed compile overhead swamps any parallel gain.
- **Connection-lifecycle chaos and the preemption cordon** — both are zero-cost on the hot path when no lifecycle chaos is registered and no preemption is active, which is the default state. `NettyResponseWriter.resolveLifecycleProfile()` returns `null` on a single `TcpChaosRegistry.activeCount() == 0` volatile read; the L6 cordon check in `HttpRequestHandler.channelRead0()` returns immediately on a single `PreemptionSimulator.isCordoned()` volatile read. The configuration property `connectionLifecycleChaosEnabled` defaults to `true`, but until a TCP-chaos profile or a preemption is registered these reads always return false/null — no allocation, no branching beyond the initial guard, and no impact on throughput or latency. To eliminate even the volatile reads, set `connectionLifecycleChaosEnabled=false`.
- **`matchersFailFast`** — defaults to `true` (early-exit on first non-matching field) and that is almost always right. Disable only when you specifically need every field's match status in the failure log.
- **`regexMatchingTimeoutMillis`** — defaults to 5000 ms (5 seconds), which hands every regex evaluation off to a thread pool with a `future.get(timeout)` to guard against catastrophic-backtracking (ReDoS). The thread-pool hand-off itself costs a context switch per regex evaluation per matcher per request. If you control all expectations and are confident no regex can back-track catastrophically, set `regexMatchingTimeoutMillis=0` to run the regex inline on the event-loop thread and skip the hand-off entirely. **Do not set this to 0 when expectations come from untrusted sources** — `MatchingTimeoutExecutor` is the ReDoS guard.

## Performance regression pipeline

A daily, notify-only pipeline catches performance regressions automatically by comparing each run against a rolling stored-history baseline. It runs independently of the opt-in `k6 load test` step that already exists in the regular build pipeline (that step stays unchanged).

### What it runs

```mermaid
flowchart TD
    SCHED["Buildkite daily schedule
04:00 UTC"] --> GUARD["perf-test-guard.sh
trigger queue
commit-guard check"]
    GUARD -->|"HEAD == last RUN commit"| SKIP["Annotate: skipped
exit 0"]
    GUARD -->|"new commit since last RUN"| UPLOAD["Dynamic pipeline upload
run + microbench + compare steps"]
    UPLOAD --> RUN["perf-test-run.sh
perf queue  c5.12xlarge"]
    UPLOAD --> MICRO["perf-test-microbench.sh
perf queue  c5.12xlarge"]
    RUN --> ARTIFACTS["Upload perf-result.json
Buildkite artifacts"]
    MICRO --> ARTIFACTS
    ARTIFACTS --> COMPARE["perf-test-compare.sh
perf queue
merge + persist to S3 + compare"]
    COMPARE --> ANNOTATE["Buildkite annotation
markdown table
NOTIFY ONLY — exits 0"]
```

**Commit guard:** `perf-test-guard.sh` resolves the commit the heavy regression run *last actually executed against* (`last_perf_run_commit` in `lib/last-successful-commit.sh`) and dispatches only when `HEAD` differs. Crucially this keys off **real runs, not lint passes**: the perf-test pipeline passes on its lint step on every push, so "last successful build" would almost always be `HEAD` and the guard would skip forever. Instead, `perf-test-run.sh` records `perf_regression_ran_commit` in the build's Buildkite meta-data when it runs, and the guard reads the most recent such value via the Buildkite API. If `HEAD` equals it, the pipeline annotates "skipped" and exits; otherwise it dynamically uploads the run/microbench/compare steps. (The sibling `last_successful_commit` helper — last *passed build* — remains used by `generate-pipeline.sh` for path-based change detection.)

**Run step (`perf-test-run.sh`, `perf` queue):** Starts a dedicated upstream MockServer (needed for the `forward` behaviour) and the server under test with metrics enabled and default `maxLogEntries`. On hosts with 16+ vCPU it core-pins the server, upstream, and k6 to disjoint cpusets for reproducibility; on smaller hosts it skips pinning with a warning. Runs `regression.js` twice — once over plain HTTP, once over HTTPS (ALPN auto-negotiates HTTP/2), then runs `growth.js` with a background sampler collecting CPU (`docker stats`), heap (`jvm_memory_used_bytes{area="heap"}`), GC (`jvm_gc_collection_seconds_sum`), and threads (`jvm_threads_current`) from `/mockserver/metrics` (`PERF_SAMPLE_INTERVAL`, 5 s, plus each scrape's own time, so rows land about 7 s apart). `growth.cpu_ratio` does not use that sampler: it reads the dense `diag-samples.csv` sampler, whose rows land about 3.6 s apart. Assembles and uploads `perf-result.json`.

**Microbench step (`perf-test-microbench.sh`, `perf` queue):** Builds `mockserver-core` inside `mockserver/mockserver:maven`, runs the JMH `MatchingBenchmark` (`mockserver/mockserver-benchmark`) with `-prof gc` over focused params. Reshapes JMH output into `perf-microbench.json`.

**Compare step (`perf-test-compare.sh`, `perf` queue):** Merges the two artifacts; persists the run to `s3://mockserver-ci-perf-results/runs/<branch>/<iso>__<sha>.json`; fetches the last N=10 prior runs. A history listing that fails or omits the run just written, or a listed run that cannot be downloaded, fails the step rather than being read as fewer prior runs. If fewer than MIN_BASELINE=5 runs exist it annotates "baseline warming up" and stops. Otherwise it computes a regression threshold per metric using a rolling **median + MAD** baseline and flags regressions.

### Triggering a run

The heavy run is gated so it does not fire on ordinary commits. There are three ways to start one:

- **Daily schedule (automatic).** A Buildkite schedule (`perf_regression_daily`, `0 4 * * *` UTC, defined in `terraform/buildkite-pipelines/pipelines.tf`) creates a `build.source == 'schedule'` build. The commit guard dispatches the heavy steps only if `master` has moved since the last *actual* run (see the meta-data mechanism above).
- **Manual UI build.** Clicking **New Build** on the `mockserver-performance-test` pipeline creates a `build.source == 'ui'` build. This **force-dispatches** the run regardless of the new-commit check, so you can re-measure the same commit on demand.
- **`[perf-run]` build message (API / CLI).** Any build whose message contains `[perf-run]` force-dispatches the run — the programmatic equivalent of the UI button. The daily schedule and the orchestrator's path-based triggers never carry this marker, so it cannot fire the heavy run by accident. Example:
  ```bash
  curl -H "Authorization: Bearer $BK_TOKEN" \
    -X POST "https://api.buildkite.com/v2/organizations/mockserver/pipelines/mockserver-performance-test/builds" \
    -d '{"commit":"<sha>","branch":"master","message":"[perf-run] manual run"}'
  ```

`ui` and `[perf-run]` set `FORCE_RUN=true` in `perf-test-guard.sh`, bypassing the "new commit since last run" check; a `schedule` build respects it. The guard runs on the cheap `trigger` queue; only the dispatched run/microbench/compare steps consume the `perf` queue (up to three c5.12xlarge agents that scale from zero, so allow a few minutes for an agent to launch).

### Behaviours measured

Four behaviours run in `regression.js`, each as a `constant-arrival-rate` k6 scenario tagged `op:<name>`:

| Tag | What it measures |
|-----|-----------------|
| `match` | Static mock match and response |
| `forward` | Forward action to a dedicated upstream MockServer |
| `template` | Velocity response template rendering |
| `large` | ~4 KB JSON response body |

A warmup scenario (`op:warmup`) always runs first so JIT compilation and GC reach steady state before measurements begin.

Each behaviour is measured twice: `<op>_http` over plain HTTP and `<op>_https_h2` over HTTPS with HTTP/2 (ALPN). Result keys are `<op>_<proto>` (`match_http`, `match_https_h2`, etc.).

`growth.js` covers the separate "resource grows over time" class of regression (validated against [issue #2329](https://github.com/mock-server/mockserver/issues/2329): O(n) request-log eviction once the `maxLogEntries` ring fills — up to 250,000 entries, ~115,500 on the 2 GB GraalJS perf SUT — causes CPU/latency to climb). It runs a sustained `load` scenario on the match path at a rate high enough to fill `maxLogEntries` early, with low-rate latency probes at the start (`window:first`) and end (`window:last`) of the run. It emits first/last-window p95 and their ratio. `growth.cpu_ratio` is the SUT's median CPU while the event log is full (the last 40 s of the load) divided by its median while the log is 10-30% full, read from `diag-samples.csv`; see [Growth CPU ratio window](#growth-cpu-ratio-window).

### Result JSON schema

Two artifacts are produced per run and merged by the compare step before persisting to S3:

- `perf-result.json` — `{metadata, behaviours: {<op>_<proto>: {p50_ms, p95_ms, p99_ms, throughput_rps, error_rate}}, growth: {cpu_peak, heap_start, heap_end, heap_peak, heap_ratio, gc_seconds_delta, threads_peak, p95_start, p95_end, p95_ratio}}`
- `perf-microbench.json` — `{microbench: {<matcherType>_<count>_detailed: {time_per_op, time_per_op_jmh_mean, time_unit, alloc_bytes_per_op}}}` (`time_per_op` is a trimmed mean of the measured iterations, see [performance-measurement.md](../code/performance-measurement.md#how-time_per_op-is-scored-a-trimmed-mean-not-jmhs-mean))

### Regression thresholds

The compare step applies a **rolling median + MAD** baseline over the last N=10 prior runs. A metric is flagged when the head value crosses `max(median + 3 × 1.4826 × MAD, percent-floor)`, or the budget's absolute `floor` in place of the percent floor, and never closer to the median than its `min_abs` ([Absolute minimum move](#absolute-minimum-move)). A `path_coverage` p99 is judged only from arms with enough samples ([Tail sample minimum](#tail-sample-minimum)):

| Metric class | Direction | Min pct floor |
|---|---|---|
| Latency (p50/p95/p99) | Higher is worse | 10% |
| CPU, heap, alloc | Higher is worse | 10% |
| Throughput | Lower is worse | 10% |
| Microbench time/alloc | Higher is worse; the notify-only `microbench_extra` alloc also needs a move of at least 1 B/op | 5% |
| Growth slope (CPU ratio, heap ratio) | Higher is worse; absolute floor 1.30 (constant-load CPU/heap should hold ≈ 1.0) so steady-state badness is not normalised away | 10% |
| Growth slope (p95 latency ratio) | Higher is worse; absolute floor 2.0 (latency is noisier; a #2329-class signal is ~hundreds×) — rolling median+MAD stays the sensitive gate | 10% |
| Error rate | Higher is worse; absolute floor 0.005 | — |

#### Bimodal metrics

**Four notify-only p95s are judged by two checks instead of one band:** `match_http.p95_ms`, `template_http.p95_ms`, `info_match_http.p95_ms` and `info_match_https_h2.p95_ms`. They are listed under `bimodal` in their `perf-budgets.json` entry.

- **Per-mode band:** the head is judged against the baseline mode it falls in.
- **Mode-share check:** a head in the upper mode is also flagged when too many recent runs are in that mode.

Replayed over builds 649–734, the pair cuts these metrics' notify-only flags from 25 to 9 in 154 compared runs. They also catch more regressions:

| Injected into the real history | Single band | Bimodal |
|---|---|---|
| Six-run episode in which every fast run lands in the clump (3.8 ms), all four metrics | 51 of 134 episodes | 101 of 134 |
| Sustained shift into the clump, caught within 10 runs (`match_http` / `info_match_http` / `info_match_https_h2`) | 15/29, 20/30, 12/30 | 29/29 (median 3 runs), 30/30 (6), 30/30 (5) |
| `match_http`: 1.5× slowdown, among runs the rule otherwise passes | 3 of 30 | 19 of 35 |

```mermaid
flowchart TD
  W["baseline window (10 runs, oldest first)"] --> S{"largest ratio between\nneighbouring sorted values\n>= split_ratio, and lower\nmode has >= 3 runs?"}
  S -- no --> U["single median + MAD band"]
  S -- yes --> H{"where is the head?"}
  H -- "<= lower band" --> OK1["ok (lower mode)"]
  H -- "within upper median\n+/- max(3 sigma, upper_band_pct)" --> SH{"upper-mode share of the\nlast share_runs runs + head\nimprobable at the historical\nshare? (binomial < share_alpha)"}
  U --> SH
  SH -- yes --> F3["flagged (mode share)"]
  SH -- no --> OK2["ok"]
  H -- "above the upper band" --> F1["flagged (upper mode)"]
  H -- "between the bands" --> F2["flagged (between modes)"]
```

**Why.** On the 200 rps and 50 rps arms a fixed latency clump of about 4–5 ms holds a run-varying share of requests, either side of 5%. So these p95s read either the fast body (about 0.4–1.0 ms) or the clump (about 3.2–4.6 ms). In the 69 persisted `match_http` runs from build 443, 32% read the clump. Which mode a run reads is random:

- The same image on the same instance takes both modes (for example `i-09affe652699dee0a`: clump, fast, clump, fast, clump).
- Both modes appear on JDK 25.0.4.1 and 25.0.5, in both availability zones and on both AMIs.
- The mode of `match_http` is uncorrelated with the other arms of the same run (phi 0.0–0.2), and barely with the previous run (lag-1 phi 0.16).

One median+MAD band over such a window fails in both directions. When the window is mostly fast, every clump run is flagged. When it is mostly clump, the median jumps to about 3.5 ms and the threshold to 5–9 ms, so the band cannot see a fast-mode regression.

**Per-mode band.**

- The lower mode keeps the usual band: `median + 3 × 1.4826 × MAD`, at least `min_pct`.
- The upper mode allows its median ± `max(3σ, upper_band_pct × median)`. Its 1–3 runs cannot estimate a spread on their own, and the clump spans about −16%/+22% of its median, so `upper_band_pct` is 0.25.
- A head between the two bands is a level the window has not shown, so it is flagged.
- `split_ratio` is 2, well under the clump-to-body ratio of about 7. A rare run between the modes (1.78 ms in build 673) can move the split below it. That run then joins the upper mode and widens its band while it is in the window.

**Mode-share check.** On its own, the per-mode band reads a regression that pushes runs *into* the clump as the upper mode. In the six-run replay above, it flagged 7 of 134 episodes where the single band had flagged 51. The share check closes that gap:

- Runs are classified by the window's largest-ratio split, at the geometric midpoint of the gap. It needs only one run on each side.
- For a head in the upper mode, the check counts the upper-mode runs among the last `share_runs` (5) runs plus the head. The binomial probability of at least that many, at the metric's historical upper-mode share, must stay at or above `share_alpha` (0.05).
- The historical shares are the values under `bimodal.metrics`: 0.32, 0.75, 0.44 and 0.55. `generate-history.py` derives them from every persisted run since build 443.
- At a 0.32 share, 5 of 6 recent runs is flagged and 4 of 6 is not. At 0.44 and 0.55 it takes 6 of 6. At 0.75 (`template_http`) the check can never fire.
- In the real history it adds two flags, builds 655 and 659. Each was the fifth clump run in six, which is improbable at 32% even though no code had changed.

**Rejected alternatives:**

- *Normalising by a same-run reference:* no arm co-moves with `match_http`'s mode.
- *Using p90:* p90 is not recorded, so the history cannot test it. Extrapolating the clump share from p95 and p99 suggests about 15% of runs would still put p90 in the clump.
- *Taking the threshold from the upper mode for every head:* it stops the flags but cannot see a fast-mode regression below about 5 ms.
- *A share check over the whole 10-run window:* it reacts more slowly. At 8–11 false flags it caught 54–59 of 134 episodes, against 101 at 9 for the last 5 runs.

**Limits.**

- `template_http.p95` is in the clump 75% of the time, so a shift into it is barely a change. Neither rule sees one quickly: the single band caught 5 of 33 six-run episodes and this rule catches 1. Its gain is the false flags, which fall from 4 to 0.
- A moderate rise in the clump share goes effectively undetected. For `match_http`, at the historical 32% the share check fires on about 1 run in 80. It fires on about 1 in 11 at 50%, 1 in 4 at 65%, and more than half of runs only from about 80%. The share check is for a shift *into* the clump, not a drift in its frequency.
- **The historical shares must be re-derived** after anything that changes the clump: a k6 rate or harness change, a new instance type, a base-image or JDK change, or the F5 root-cause fix. Re-derive them too if share flags recur on unchanged code. To re-derive:
  1. Download the `perf-result.json` of every persisted build since the change (`bk artifacts list <N>` / `bk artifacts download`, as in `generate-history.py`'s docstring).
  2. Run `generate-history.py <dir> --from <first build>`.
  3. Copy the printed shares into `bimodal.metrics`.
  4. Commit the regenerated fixture with the test's expected flag lists updated.
- A body slowdown of about 6–9× lands inside the upper band. It reads as the upper mode and is flagged only through the share check, once it persists. The clump's own trigger is open in [the backlog](../plans/performance-measurement-backlog.md) (row F5).
- A level seen once in the window becomes a mode. A repeat of a flagged regression at the same level is therefore not flagged again by the band while the flagged run is still in the window.
- Both checks are notify-only. The validator rejects `bimodal` on a gating budget or on a `down` budget.
- A per-request cost of a few microseconds is far below these metrics' run-to-run spread. The U12f credential-masking cost (+6.5 µs/op, +8% in the matching JMH benchmark) did not show in `match_http.p95` under either rule: the same pre-fix image read 1.022 in build 728 and 0.433 in build 729. That class of change is the JMH micro-benchmarks' job.

The evidence is replayed in `.buildkite/scripts/test/perf-bimodal-budget-test.sh`, against the real windows in `.buildkite/scripts/test/fixtures/perf-bimodal/`. Those windows are written by `generate-history.py` in the same directory, from the builds' `perf-result.json` artifacts. Listing another metric needs its history and share regenerated there.

#### Growth CPU ratio window

**`growth.cpu_ratio` is the SUT's median CPU while the event log is full divided by its median while the log is 10-30% full.** Both windows are placed by the log's occupancy in `diag-samples.csv` (the dense sampler: `docker stats` and the log gauges every 2 s, about 3.6 s apart), not by time:

- **The start window** is the rows inside the growth phase that are 10-30% full and come before the first full row. The log fills about 72-76 s into the load (about 1,600 entries a second into 115,456), so this window is about 7-23 s in and holds 3-4 rows. Skipping the first 10% drops the warm-up seconds, which read about 18-22% CPU against about 16.5%.
- **The end window** is the full rows in the last 40 s, about 12 rows. The full-log condition excludes the rows after growth.js's teardown empties the log.
- `PERF_CPU_START_OCC_LO` / `PERF_CPU_START_OCC_HI` / `PERF_CPU_END_WINDOW_S` override 0.10, 0.30 and 40.

Until this change the ratio was the last growth sample divided by the *first*. The sampler starts just before k6, so that sample landed either before the load (about 0.3% CPU) or on it (about 16%), at random, and the ratio read about 1 or about 50 on the same code. A fixed-time start window was tried and rejected: placed after 20 s, it sat at about 54% occupancy, so a cost that grows with the log was diluted about 2x.

Measured with `perf-test-run.sh`'s own `growth_cpu_window` over the real `diag-samples.csv` of 36 valid runs, builds 643-741. The injected cost multiplies each row's CPU by `1 + g × occupancy`:

| | First-sample ratio | Occupancy-window ratio |
|---|---|---|
| Range as measured | 0.95 to 68.16 | 0.93 to 1.03 |
| Flagged, 31 replayed heads | 654, 685, 690, 698, 721, 724 (production flagged 685, 690, 698, 721, 724) | none |
| +30% / +40% / +50% / +75% CPU at a full log, runs above the 1.30 floor | — | 0 / 8 / 33 / 36 of 36 |
| +50% / +75% on the head, compared with the measured history | — | 28 / 31 of 31 heads flagged |

- **The 1.30 floor is the operative bound.** With this history the median is about 0.98 and the MAD small, so the threshold is the floor. A cost that grows with the log is caught reliably from about +50% at a full log. The ratio's noise (0.93-1.03) would support a floor of about 1.15 once 10 occupancy-window runs exist; that is an owner decision.
- **The #2329 shape** (an O(n) cost that starts when eviction starts) is caught too: it moves only the end window.
- **Alternatives measured:** a 5-30% start window (4-5 rows) let the warm-up seconds in (minimum 0.77 in build 729); 10-35% caught +50% in 29 of 36; 5-20% held 2-3 rows. A lower `PERF_SAMPLE_INTERVAL` for the growth sampler (rows about 7 s apart) was not needed, because the dense sampler already runs throughout.
- **Older runs drop out.** Compare reads `growth.cpu_ratio` only from a result whose `growth.cpu_pct.ratio_method` is `occupancy_window`; an older result's first-sample ratio is not a baseline value, so the first run after the change reads `:new:`. `start`, `end` and `peak` stay the raw first, last and highest growth samples; `start_median`, `end_median`, `start_samples`, `end_samples`, `start_occupancy` and `end_window_s` sit beside them. A run that ran growth.js but has no full log or no start window records a null ratio, which compare shows as a `not measured` row rather than dropping it; an arm-only run has no growth phase and no row.

#### Absolute minimum move

**`microbench_extra.*.alloc_bytes_per_op` must move by more than 0.01 B/op to flag** (`min_abs: 0.01`). Four of its benchmarks allocate nothing per operation, so JMH reports only its own amortised allocation:

| Benchmark (builds 643-741) | Range, B/op | Largest head minus window median |
|---|---|---|
| `MetricsIncrementBenchmark.incrementDistinctCounterPerThread` | 0.000243-0.000274 | 0.000015 |
| `MetricsIncrementBenchmark.incrementSharedCounter` | 0.0041-0.0049 | 0.0004 |
| `LocalCallbackDispatchBenchmark.inline` | 0.0000587-0.0000589 | 0.0000001 |
| `SocksHandshakeBenchmark.detect_scenario-SOCKS5_PASSWORD` | 0.0000121-0.0000122 | 0.00000002 |

With no absolute term, 5% of 0.00026 B/op is 0.000013 B/op, so the top third of that noise flagged (production builds 723 and 724).

- **Why 0.01 B/op.** Anything above 0.00002 removes the replayed false flag, but `incrementSharedCounter` alone spreads 0.0008 B/op, so 0.01 is the smallest round value with a 10x margin over every spread seen (12x). It catches a new 16 B object on 1 op in 1,600: the replay flags +16, +1 and +0.05 B/op on all four benchmarks in every head.
- **What it cannot see.** A new allocation on fewer than 1 op in 1,600 of a zero-allocation benchmark (+0.005 B/op is not flagged).
- **It is inert above 0.2 B/op**, where 5% of the median already exceeds 0.01. The smallest other `microbench_extra` benchmark allocates 116 B/op. Replaying builds 643-741, every other row's verdict and threshold is byte-identical.
- **It does not catch the 2026-10-22 `ForwardPathBenchmark.renderSimple` +16 B/op** (5,208 to 5,224, build 724, from `HttpRequestTemplateObject`'s three new fields). The 5% band (260 B) hides it before and after this change; [the backlog](../plans/performance-measurement-backlog.md) tracks that gap.

`min_abs` is a general budget key (the threshold is at least `median + min_abs` for `up`, at most `median - min_abs` for `down`; 0 when absent). The validator requires a number of at least 0 and rejects it alongside `bimodal`, whose per-mode bands do not apply it.

#### Tail sample minimum

**A `path_coverage` p99 (`p99_ms`, `handshake_p99_ms`, `p99_ratio`) is judged only from an arm with at least 5,000 samples** (`min_samples: 5000`), so the p99 rests on at least 50 requests. A smaller arm's value still gets a row, `not judged: <n> samples, under 5000`, but is never compared or used as a baseline value, so the baseline never mixes tail sizes. **A p99 with no sample count at all now fails the build** (`no-sample-count`, counted with the other schema errors such as `not-numeric`), although the metric is notify-only: it cannot be checked, and dropping it silently would end p99 judgement unseen. A producer change that drops or renames `sample_count` / `measured_sample_count` therefore reds the build until fixed. The matcher arms now measure 100 s (10,000 samples) to qualify.

| Arm, builds 644-732 | Samples | p99 robust CV | Runs above 1.25 × median |
|---|---|---|---|
| `jsonpath_100` | 3,501 | 0.20 | 6 of 35 |
| `jsonschema_100` | 3,501 | 0.23 | 6 of 35 |
| `xpath_100` | 3,501 | 0.02 | 0 |
| `download_32mib` | 71 | 0.11 | 1 |
| churn and keepalive arms | 7,001 | 0.01-0.07 | 0 |
| `capture_proxy` | 35,002 | 0.03 | 0 |
| `h2_2000` | 20,002 | 0.03 | 0 |

At 3,501 samples the p99 is the 35th-slowest request, so a short burst of slow requests sets it, while the p50 of the same arms stays within a CV of 0.07. Production flagged `jsonpath_100.p99` in builds 651, 672, 690, 724 and 729, `jsonschema_100.p99` in 731, and `download_32mib.p99` in 651 and 690.

- **The 5,000 line.** Every arm with 7,000 or more samples was stable; both arms that flapped had 3,501. 5,000 puts the line between them, with a 50-request tail.
- **Replay.** Over 31 heads the arms with 5,000 or more samples keep byte-identical verdicts, and a 30% p99 regression injected on `tls_keepalive` still flags every judged head. The 3,501- and 71-sample arms are no longer judged.
- **Window, not rate.** The matcher arms get 10,000 samples from a 100 s measured window, not a higher rate: a slow burst lasts a time, not a request count, so a higher rate fills the tail with the same burst. 300/s would also put `xpath_100` (12 ms each) at about 3.6 of the SUT's 6 cores. The window costs about 65 s per run.
- **What is not yet shown.** No run has measured a 100 s matcher window yet, so whether it steadies those p99s is unproven. They read `:new:` until 5 runs at 10,000 samples exist. [The backlog](../plans/performance-measurement-backlog.md) tracks the check.
- **Coverage given up meanwhile.** `xpath_100.p99` was stable (CV 0.02) and caught an injected +30% in 30 of 30 judged heads; it is not judged until those 5 runs exist, about a week of daily runs. `download_32mib.p99` is never judged again; an injected +30% was caught in 14 of 30 judged heads before.
- **A side effect to watch.** The h2 ladder runs on the same SUT right after the matchers, now 65 s later and about 20,000 requests further into its event log, so watch the `h2_*` figures on the first runs.
- **`download_32mib` is never judged on p99.** At 2/s, 5,000 samples would take 40 minutes, and 71 samples make the p99 the maximum. Its p50 (CV 0.01), `delivery_ratio`, throughput and memory figures are still compared.

`min_samples` is honoured only for `path_coverage.*` budgets, whose arms carry `sample_count` (or `measured_sample_count` on an h2 rung). The validator requires a whole number of at least 1 and rejects the key anywhere else.

The replay evidence for all three is in `.buildkite/scripts/test/perf-noise-budget-test.sh`, against `.buildkite/scripts/test/fixtures/perf-noise/history-643-741.json`. That fixture is written by `generate-history.py` in the same directory from each build's `perf-result.json`, `perf-microbench-extra.json` and `perf-jvm-diagnostics.tgz`.

The pipeline uses **per-metric gating**. A flagged regression on a **gating** metric fails the build (the compare step exits non-zero — and there is no `soft_fail`, so the build goes red). A flagged regression on a **notify-only** metric is reported just as loudly in the annotation but does not change the exit code. The failing build **is** the regression notification — there is no separate webhook or channel.

Only metrics with a derived, trustworthy budget gate today: the **JMH micro-benchmark** metrics (`*.time_per_op`, `*.alloc_bytes_per_op` — deterministic, hardware-independent) and **`forward.error_rate`** (a discriminating pass/fail guard, not a tuned threshold). Every other metric — all k6 latency percentiles, the growth ratios, `rig_valid_peak_achieved_rps` and `live_set_bytes` — runs notify-only. A notify-only metric graduates to gating once it has ≥ 10 clean runs of history and a budget derived from them; the repo owner decides each promotion. Open measurement items are tracked in [docs/plans/performance-measurement-backlog.md](../plans/performance-measurement-backlog.md).

### Reading the annotation table

The annotation shows one row per compared metric with columns: metric key, head value, baseline (median), threshold, **Gate** (`gating` or `notify-only`), and Status. Status is `:red_circle: REGRESSION (fails build)` for a flagged gating metric, `:warning: flagged (informational)` for a flagged notify-only metric, `:new: new` for a metric with no baseline yet, and `:white_check_mark: ok` otherwise. For a [bimodal metric](#bimodal-metrics) the Baseline cell names the mode the head was judged against: `(lower mode)`, `(upper mode)` or `(between modes)`. A mode-share flag adds the count, for example `(6 of last 6 runs upper-mode, expected 32%)`. The "baseline warming up" annotation appears when fewer than 5 prior runs exist on the branch — this is expected when the pipeline is first deployed or after the S3 history is pruned.

### Re-baselining

The rolling baseline is self-healing: as runs accumulate, old outlier runs age out of the N=10 window. To force a clean baseline (e.g. after a deliberate performance improvement), delete the S3 objects under `s3://mockserver-ci-perf-results/runs/master/` for the range you want to drop. The pipeline will annotate "baseline warming up" until 5 new runs accumulate.

### Key files

| File | Purpose |
|------|---------|
| `.buildkite/pipeline-perf-test.yml` | Pipeline definition — guard step gated on `build.source == 'schedule'`/`'ui'` or a `[perf-run]` build message |
| `.buildkite/scripts/lib/last-successful-commit.sh` | Shared helper: `last_successful_commit` (last passed build) for `generate-pipeline.sh` + `last_perf_run_commit` (last actual run, via `perf_regression_ran_commit` meta-data) for the guard |
| `.buildkite/scripts/steps/perf-test-guard.sh` | Commit-guard + dynamic pipeline upload (`trigger` queue) |
| `.buildkite/scripts/steps/perf-test-run.sh` | k6 run + background sampler + result assembly (`perf` queue) |
| `.buildkite/scripts/steps/perf-test-microbench.sh` | JMH microbench + JSON reshape (`perf` queue) |
| `.buildkite/scripts/steps/perf-test-compare.sh` | S3 persistence + median+MAD compare + annotation (`perf` queue) |
| `.buildkite/scripts/lib/perf-budgets-validate.sh` | Schema check for `perf-budgets.json`, shared by the compare and allocation gates. Both gates decide with jq comparisons, and jq orders *every string above every number* — so a quoted `floor` makes the comparison true regardless of value and silently switches the budget off. The validator rejects a non-numeric `floor`/`min_pct`, an unrecognised `dir`, and any unknown or missing key — a typo of `gating` would otherwise pass every value check while quietly turning a build-failing metric into a notify-only one. It carries a `--self-test` the gates run on every build, so the guard proves it still rejects those shapes rather than being trusted |
| `mockserver-performance-test/k6/regression.js` | k6 regression scenarios (4 behaviours × HTTP+HTTPS/H2, warmup) |
| `mockserver-performance-test/k6/growth.js` | k6 growth/slope scenarios (sustained fill + window probes) |
| `s3://mockserver-ci-perf-results/` | Historical run storage (see [AWS Infrastructure](../infrastructure/aws-infrastructure.md)) |

## When perf regresses unexpectedly

1. Check if `metricsEnabled` is on in the affected environment. If not, turn it on and re-run.
2. Look at `mockserver_action_count_total` by action type — a regression in one action category usually points at the responsible code path.
3. Compare ring-buffer drop counters (`mockserver_log_entries_dropped_total`) to baseline. Any non-zero value means log retention is the bottleneck, not the request path.
4. If event loops are pegged, take a thread dump (`jcmd <pid> Thread.print > dump.txt`) and look for stack traces parked on outbound IO — usually a slow downstream, not a MockServer bug.
5. Pull a JFR recording (`-XX:StartFlightRecording=filename=mockserver.jfr,duration=2m`) if you need allocation-level detail. Open in Mission Control or the IntelliJ profiler.

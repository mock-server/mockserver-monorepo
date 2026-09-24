# Metrics & Monitoring

## Prometheus Metrics

MockServer exposes Prometheus-compatible metrics when the `metricsEnabled` configuration property is set to `true`. Metrics are served at the `/mockserver/metrics` endpoint in Prometheus text exposition format.

### Architecture

```mermaid
graph LR
    subgraph "MockServer"
        HA[HttpActionHandler] -->|increment| M[Metrics]
        HS[HttpState] -->|increment| M
        WS[WebSocketClientRegistry] -->|set| M
        M --> CR[PrometheusRegistry]
        BIC[BuildInfoCollector] --> CR
        JMC[JvmMetricsCollector] --> CR
        MH[MetricsHandler] -->|scrape| CR
    end
    P[Prometheus] -->|GET /mockserver/metrics| MH
```

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `metricsEnabled` | `false` | Enable Prometheus metrics collection and the `/mockserver/metrics` endpoint |

When metrics are disabled, the scrape endpoint returns a `404 Not Found` response.

### Metric Names

The `Metrics.Name` enum defines 24 request/action/websocket gauges (all Prometheus `Gauge` type); separate collectors add the build-info and JVM-runtime metrics described below. Five of these gauges are genuinely monotonic counts and nineteen are levels — see [Monotonic counters vs levels](#monotonic-counters-vs-levels) for why the legacy `_count` gauges are retained and how the monotonic five additionally dual-publish proper `_total` Prometheus `Counter`s for correct `rate()`/`increase()`.

#### Request & Expectation Matching

| Metric Name | Description |
|-------------|-------------|
| `requests_received_count` | Total requests received |
| `expectations_not_matched_count` | Requests that did not match any expectation |
| `response_expectations_matched_count` | Requests matched to a response expectation |
| `forward_expectations_matched_count` | Requests matched to a forward expectation |

#### Per-Expectation Match Counter (opt-in)

`mock_server_expectation_matched` is a Prometheus `Counter` labelled by `expectation_id` that increments each time an expectation is matched and a response is served. It is registered only when **both** `metricsEnabled` and `perExpectationMetricsEnabled` are `true`; the default scrape is byte-for-byte unchanged when the property is off. Cardinality is bounded by the number of active expectations. Appears in the exposition output as `mock_server_expectation_matched_total{expectation_id="..."}`.

| Property | Default | Description |
|----------|---------|-------------|
| `perExpectationMetricsEnabled` | `false` | Register the `mock_server_expectation_matched` counter labelled by expectation id (requires `metricsEnabled`) |

Example PromQL (which expectations are never hit?):
```promql
mock_server_expectation_matched_total == 0
```

#### Action Execution (one per action type)

| Metric Name | Description |
|-------------|-------------|
| `forward_actions_count` | Forward actions executed |
| `forward_template_actions_count` | Forward template actions executed |
| `forward_class_callback_actions_count` | Forward class callback actions executed |
| `forward_object_callback_actions_count` | Forward object callback actions executed |
| `forward_replace_actions_count` | Forward replace (override) actions executed |
| `response_actions_count` | Response actions executed |
| `response_template_actions_count` | Response template actions executed |
| `response_class_callback_actions_count` | Response class callback actions executed |
| `response_object_callback_actions_count` | Response object callback actions executed |
| `sse_response_actions_count` | SSE (server-sent events) response actions executed |
| `llm_response_actions_count` | LLM response actions executed |
| `llm_chaos_injected_count` | LLM chaos faults injected |
| `websocket_response_actions_count` | WebSocket response actions executed |
| `grpc_stream_response_actions_count` | gRPC stream response actions executed |
| `binary_response_actions_count` | Binary response actions executed |
| `dns_response_actions_count` | DNS response actions executed |
| `error_actions_count` | Error actions executed |

#### WebSocket Callbacks

| Metric Name | Description |
|-------------|-------------|
| `websocket_callback_clients_count` | Active WebSocket callback client connections |
| `websocket_callback_response_handlers_count` | Registered response callback handlers |
| `websocket_callback_forward_handlers_count` | Registered forward callback handlers |

### Monotonic counters vs levels

Despite every `Metrics.Name` value ending in `_count`, only five are genuinely **monotonic** (ever-increasing totals); the rest are **levels** that go up *and* down. This matters for PromQL: `rate()` / `increase()` are only meaningful on monotonic series.

| Metric | Kind | Why |
|--------|------|-----|
| `requests_received_count` | Monotonic | Incremented once per request received; never decremented |
| `expectations_not_matched_count` | Monotonic | Incremented once per unmatched request |
| `response_expectations_matched_count` | Monotonic | Incremented once per matched response |
| `forward_expectations_matched_count` | Monotonic | Incremented once per matched forward |
| `llm_chaos_injected_count` | Monotonic | Incremented once per injected LLM chaos fault |
| `*_actions_count` (16 series) | Level | Track the number of currently-registered expectations by action type — `increment` on add, `decrement` on remove (see `RequestMatchers`) |
| `websocket_callback_*_count` (3 series) | Level | `set(...)` to the live registry size as callback clients/handlers connect and disconnect |

**Why the legacy `_count` series stays a `Gauge`.** The Prometheus Java client (1.8.0) **forces a mandatory `_total` suffix** onto every `Counter`'s exposition sample line — a counter registered as `requests_received_count` is scraped as `requests_received_count_total` (in both the classic `0.0.4` text format and OpenMetrics; the `# TYPE` line uses the `_total` name in the classic format). Converting these five gauges to counters *in place* would therefore **rename** them from `<name>_count` to `<name>_count_total`, silently breaking every consumer that reads them by name: the dashboard UI (`MetricsView` reads `requests_received_count`, `response_expectations_matched_count`, `expectations_not_matched_count`, `forward_expectations_matched_count`, and the `_actions_count` family by exact name, stripping the `_count` suffix for display) and any existing Grafana dashboard. So the legacy `_count` gauges are **retained unchanged** for back-compat. The naming contract is pinned by `MetricsTest.monotonicCountMetricsKeepExactCountNamesAndAreNotSilentlyRenamed` and the `_total`-suffix constraint by `MetricsTest.prometheusClientForcesTotalSuffixOnCounters`.

**Dual-published `_total` counters (recommended for `rate()`/`increase()`).** Rather than rename, MockServer **additively** publishes a proper Prometheus `Counter` alongside each of the five legacy `_count` gauges, so both series coexist in every scrape:

| Legacy gauge (retained, back-compat) | New counter (use for `rate()`/`increase()`) |
|--------------------------------------|---------------------------------------------|
| `requests_received_count` | `mock_server_requests_received_total` |
| `expectations_not_matched_count` | `mock_server_expectations_not_matched_total` |
| `response_expectations_matched_count` | `mock_server_response_expectations_matched_total` |
| `forward_expectations_matched_count` | `mock_server_forward_expectations_matched_total` |
| `llm_chaos_injected_count` | `mock_server_llm_chaos_injected_total` |

The counters are registered from the single source of truth `Metrics.MONOTONIC_TOTAL_COUNTER_NAMES` (each builder name gains the client-forced `_total` suffix) and incremented in lock-step with the legacy gauge from the same `Metrics.increment(Name)` call site, so the two never diverge on the increment path. They mirror to OTLP as observable monotonic Sums (`OtelMetricsExporter.registerMonotonicTotalCounters`), matching the newer counters. Coexistence is pinned by `MetricsTest.dualPublishesTotalCounterAlongsideLegacyCountGauge`. Prefer the `_total` counters in PromQL — e.g. `rate(mock_server_requests_received_total[5m])` — because they are true monotonic counters that `rate()`/`increase()` model correctly across a scrape.

This mirrors the *newer* metrics (`mock_server_slow_requests`, `mock_server_forward_requests`, `mock_server_http_chaos_injected`, …) which are registered as `Counter`s from the outset and are *designed* around the `_total` suffix — the UI and OTLP mirror reference them as `..._total`.

**Reset semantics.** All 24 gauges (monotonic and level alike) are zeroed by `Metrics.clear()` on a server reset, and the request/expectation subset by `clearRequestAndExpectationMetrics()`. The dual-published `_total` counters follow **counter semantics like the other counters** (`mock_server_slow_requests`, `mock_server_http_chaos_injected`, …): they are *not* zeroed by `clear()`/`clearRequestAndExpectationMetrics()`, only reset on process restart (or `resetAdditionalMetricsForTesting()`). So across a MockServer reset the legacy gauge drops to 0 while the `_total` counter keeps climbing — which is the correct, intended Prometheus counter behaviour and is acceptable: `rate()`/`increase()` on either series stay correct, since a drop to zero is itself modelled as a counter reset.

### Build Info Metric

`BuildInfoCollector` registers a `mock_server_build_info` gauge with labels:

| Label | Description |
|-------|-------------|
| `version` | Full version (e.g. `8.0.0`) |
| `major_minor_version` | Major.minor version (e.g. `6.1`) |
| `group_id` | Maven group ID (`org.mock-server`) |
| `artifact_id` | Maven artifact ID (`mockserver-netty`) |
| `git_hash` | Abbreviated git commit hash the build was produced from, or `unknown` when no git metadata is available |

### JVM Runtime Metrics

`JvmMetricsCollector` registers JVM process-health gauges (read fresh from JDK `java.lang.management` MX beans on each scrape — no extra dependency). Registered once alongside `BuildInfoCollector` when `metricsEnabled`:

| Metric Name | Labels | Description |
|-------------|--------|-------------|
| `jvm_memory_used_bytes` | `area` = `heap` / `nonheap` | Memory currently used |
| `jvm_memory_committed_bytes` | `area` | Memory committed by the JVM |
| `jvm_memory_max_bytes` | `area` | Max memory (`-1` if undefined) |
| `jvm_memory_allocated_bytes` | — | Cumulative bytes allocated across all threads since JVM start (monotonic). A *counter*, unlike the `used`/`committed`/`max` levels, so `end − start` over a window is the exact allocation churn in it — the basis for an allocation-per-operation figure (e.g. TLS-handshake allocation cost). Sourced from HotSpot's `com.sun.management` `ThreadMXBean`; **absent on a JVM that does not implement it** (never a fabricated zero). |
| `jvm_threads_current` | — | Live thread count |
| `jvm_threads_daemon` | — | Daemon thread count |
| `jvm_gc_collection_count` | — | Total GC collections across all collectors |
| `jvm_gc_collection_seconds_sum` | — | Total GC time across all collectors (seconds) |
| `jvm_runtime_info` | `gc`, `java_version`, `java_runtime_version`, `java_vendor`, `vm_name` | Info-style gauge (constant `1`; meaning is in the labels) naming the running JVM's build and the garbage collector(s) actually in use. Mirrors `mock_server_build_info`. |

These let Grafana and the dashboard Metrics view chart heap/GC/thread behaviour alongside the request and action counters.

> **Perf-regression sampler dependency:** `perf-test-run.sh` (the performance regression pipeline's run step) scrapes `/mockserver/metrics` every 5 seconds during a growth run. These three JVM series — `jvm_memory_used_bytes{area="heap"}`, `jvm_gc_collection_seconds_sum`, and `jvm_threads_current` — are read by name, but they are only part of what the sampler reads: its dense resource-trajectory sampler pulls roughly fifteen series in all, including the eight `mock_server_event_log_*` gauges and `mock_server_dropped_log_events_total` (see the Event-Log Ring-Buffer Internals note below). If any of these metric names change, `perf-test-run.sh` must be updated in the same commit.
>
> **Self-describing perf result dependency:** the same step, immediately after the server is ready, reads `mock_server_build_info{version}`, `jvm_runtime_info{gc,java_runtime_version,java_vendor,vm_name}` and `jvm_memory_max_bytes{area="heap"}` to build the `config` block of its result JSON (schema_version 2) — the resolved heap, GC and JDK the run actually used. `jvm_runtime_info` in particular exists so a stored run records its GC and JDK build faithfully; a MockServer image predating this metric makes the config unrecordable and the perf run **fails closed** rather than emitting a placeholder. If these metric names or labels change, update `perf-test-run.sh`'s config gathering in the same commit.

### Request Latency Histogram

`mock_server_request_duration_seconds` is a Prometheus classic histogram of request handling duration (receipt → response), with buckets from 0.5 ms to 10 s. It exposes the usual `_bucket{le="…"}`, `_sum`, and `_count` series, so Grafana/PromQL can derive latency percentiles, e.g.:

```promql
histogram_quantile(0.95, sum by (le) (rate(mock_server_request_duration_seconds_bucket[1m])))
```

It is registered (once) when `metricsEnabled`. Timing is captured per `NettyResponseWriter` (one is created per request, so there is no cross-request race) and **only when metrics are enabled** — `Metrics.observeRequestDurationSeconds(...)` is a no-op otherwise, so the request hot path pays nothing when metrics are off.

### Per-Upstream Forward/Proxy Observability

Three Prometheus metrics give per-upstream visibility into forwarded and proxied requests — which upstream a request hit, how it performed, and which protocol the forward leg negotiated. All are registered once when `metricsEnabled` is `true`.

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `mock_server_forward_request_duration_seconds` | Histogram (classic, 0.5 ms–10 s buckets) | `upstream_host` | Latency of forwarded/proxied requests, by upstream host |
| `mock_server_forward_requests` | Counter | `upstream_host`, `status_class` | Count of forwarded/proxied requests, by upstream host and status class (`1xx`..`5xx`, or `unknown`) |
| `mock_server_forward_upstream_protocol` | Counter | `upstream_host`, `protocol` | Count of forward/proxy upstream connections by upstream host and the protocol actually negotiated to the upstream (`http2` via ALPN, or `http1_1`) |

Recording happens in `HttpActionHandler` on every forward/proxy completion: matched FORWARD actions, the unmatched proxy-pass path (streaming and non-streaming), and `proxyPassMappings` reverse-proxy routes. The latency is taken from the precise client-side `Timing` (`getTotalTimeInMillis()`) already computed by `NettyHttpClient` — it is *not* re-measured — falling back to a coarse wall-clock delta only when no `Timing` is attached.

The protocol counter is incremented in `HttpClientInitializer` at the ALPN-resolution point of each forward client connection (`configureHttp1Pipeline` → `http1_1`, `configureHttp2Pipeline` → `http2`), with a matching DEBUG log (`forward upstream connection to {host} negotiated {protocol}`). It is the authoritative way to confirm whether `forwardProxyHttp2Upgrade` is taking effect, since the *recorded* request only carries the inbound protocol, not the upstream-negotiated one — a forward shown as `http1_1` to a backend that withholds its streaming SSE head over HTTP/1.1 explains a high forward time-to-first-byte.

The `upstream_host` label is resolved from the matched forward action's host (the real upstream even behind an HTTP forward-proxy), falling back to the resolved socket address host; a null/blank host is recorded as `unknown`. **Cardinality is deliberately bounded to the host** (never the full URL or path) plus the five status classes, so the series count scales with the number of distinct upstreams, not with request volume or path variety. `Metrics.observeForwardRequest(host, statusCode, latencySeconds)` is a static no-op when metrics are disabled (the histogram/counter are `null`), so the forward hot path pays nothing when metrics are off.

Example PromQL (p95 forward latency per upstream):
```promql
histogram_quantile(0.95, sum by (le, upstream_host) (rate(mock_server_forward_request_duration_seconds_bucket[5m])))
```

Example PromQL (5xx rate per upstream):
```promql
sum by (upstream_host) (rate(mock_server_forward_requests{status_class="5xx"}[5m]))
```

### Upstream Circuit Breaker Gauge

`mock_server_upstream_circuit_open` is a Prometheus `GaugeWithCallback` reporting the number of upstreams whose forward/proxy circuit breaker is currently **open** (open or half-open state). It backs the per-upstream circuit breaker (`ForwardCircuitBreaker`) that is enabled by `forwardProxyCircuitBreakerEnabled` (see [configuration-reference](configuration-reference.md)). Like the other callback gauges it reads live state at scrape time (`Metrics.getOpenUpstreamCircuitCount()` → `ForwardCircuitBreaker.getInstance().openCircuitCount()`), so half-open recovery is reflected without imperative plumbing. It is registered once when `metricsEnabled` is `true`, and reads **0** whenever the circuit breaker is disabled (the default) or no upstream is currently open.

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `mock_server_upstream_circuit_open` | GaugeWithCallback | — | Number of upstreams whose forward/proxy circuit breaker is currently open |

A non-zero, sustained value means one or more upstreams are being failed fast (a 503 is returned without attempting the forward) because they crossed `forwardProxyCircuitBreakerFailureThreshold` consecutive failures. The breaker state is reset on `HttpState.reset()`.

Example PromQL alert rule:
```promql
mock_server_upstream_circuit_open > 0
```

### HTTP Chaos Fault Counter

`mock_server_http_chaos_injected_total` is a Prometheus `Counter` with a `fault_type` label (values: `"drop"`, `"error"`, `"latency"`, `"truncate"`, `"malformed"`, `"slow"`, `"quota"`, `"graphql"`) that tracks every HTTP chaos fault injected by the chaos profile subsystem. It is registered once when `metricsEnabled` is `true`.

| Label Value | Incremented When |
|-------------|------------------|
| `drop` | A chaos profile drops the TCP connection without sending any response |
| `error` | A chaos profile injects an HTTP error status instead of the normal response |
| `latency` | A chaos profile injects artificial latency into a response |
| `truncate` | A chaos profile truncates the response body |
| `malformed` | A chaos profile emits a malformed/corrupted response |
| `slow` | A chaos profile drip-feeds the response slowly (chunk delay) |
| `quota` | A chaos profile returns a quota/rate-limit fault once the limit in a window is exceeded |
| `graphql` | A chaos profile injects a GraphQL-shaped error response |

`Metrics.incrementHttpChaosInjected(faultType)` is a static no-op when metrics are disabled (the counter is `null`). This counter is surfaced on the dashboard Metrics view as an "HTTP Chaos Faults" section (visible only when the metric is present and has non-zero data).

Example PromQL:

```promql
rate(mock_server_http_chaos_injected_total{fault_type="error"}[5m])
```

### Active Service-Scoped Chaos Gauge

`mock_server_active_service_chaos` is a Prometheus `GaugeWithCallback` with a `fault_type` label (values: `drop`, `error`, `latency`, `truncate`, `malformed`, `slow`, `quota`, `graphql`) reporting, per fault type, the number of currently-active service-scoped chaos profiles (`ServiceChaosRegistry`) configured with that fault. A profile carrying several faults counts under each, so the per-type series can be charted by type. (`slow` and `quota` require their companion fields — chunk-delay, and limit + window — to be counted, matching when they actually fire.) It is a *callback* gauge — the callback reads `Metrics.getActiveServiceChaosCountByFaultType()` → `ServiceChaosRegistry.getInstance().activeCountByFaultType()` at scrape time rather than tracking the value imperatively, so TTL auto-revert (which removes a profile without any `put`/`remove` call) is reflected without extra plumbing. Every fault type is always present (0 when none), giving a stable, complete set of series. It is registered once when `metricsEnabled` is `true`; the counts drop to 0 as profiles are cleared or their TTLs lapse, which makes `sum(mock_server_active_service_chaos) > 0` a natural "chaos still live" alert.

Both chaos metrics are also mirrored over OTLP by `OtelMetricsExporter` (`registerChaosCounter` / `registerActiveServiceChaosGauge`) so OTLP-only consumers can observe them without a Prometheus scrape.

### Chaos Auto-Halt Counter

`mock_server_chaos_auto_halt_total` is a Prometheus `Counter` that increments each time the chaos auto-halt circuit-breaker triggers. The circuit-breaker is a safety mechanism that automatically disables all active service-scoped chaos profiles when the number of **destructive** chaos faults within a sliding window exceeds a configured threshold. This prevents chaos experiments from driving cascading outages.

Only **destructive** fault types contribute to the window: `"error"` (synthetic 5xx), `"drop"` (connection kill), and `"quota"` (429/503). Benign fault types (`"latency"`, `"slow"`, `"truncate"`, `"malformed"`, `"graphql"`) are excluded -- a latency-only experiment will never auto-halt.

The auto-halt feature is controlled by three configuration properties (all off/inert by default):

| Property | Default | Description |
|----------|---------|-------------|
| `chaosAutoHaltEnabled` | `false` | Master switch for the circuit-breaker |
| `chaosAutoHaltErrorThreshold` | `50` | Number of error-class faults (5xx/dropped/quota) in the window that triggers halt |
| `chaosAutoHaltWindowMillis` | `60000` | Sliding window duration in milliseconds |

When the circuit-breaker fires:
1. All active service-scoped chaos profiles are removed via `ServiceChaosRegistry.reset()` (the same path used by TTL expiry)
2. The `mock_server_chaos_auto_halt_total` counter is incremented
3. The `mock_server_active_service_chaos` gauge drops to 0 for all fault types
4. A WARN-level log event is emitted with the error count, window, and threshold
5. The sliding window is cleared so the breaker does not immediately re-trigger

The monitor is also reset by `HttpState.reset()` (alongside `ServiceChaosRegistry.reset()`), so a server reset clears stale errors from the window and prevents them from halting freshly-registered chaos.

The auto-halt is evaluated per chaos fault injection (called from `Metrics.incrementHttpChaosInjected`). It uses a lock-free `ConcurrentLinkedDeque` of timestamps with an `AtomicInteger` window counter (O(1) size check) and an `AtomicBoolean` guard to prevent concurrent double-trigger. When the feature is disabled (`chaosAutoHaltEnabled=false`), the evaluation is a no-op with zero overhead.

Example PromQL alert rule:
```promql
increase(mock_server_chaos_auto_halt_total[5m]) > 0
```

### LLM Token & Cost Counters

Three Prometheus `Counter`s track LLM token usage and estimated cost when `llmMetricsEnabled` is `true` (in addition to `metricsEnabled`). Each is labeled by `provider` (lowercased enum name, e.g. `anthropic`, `openai`) and `model` (the model identifier from the completion). They are incremented on both the mock path (`HttpLlmResponseActionHandler`) and the forward/proxy path (`HttpActionHandler.emitForwardGenAiSpan`) whenever a `Completion` is served or forwarded.

| Metric Name | Description |
|-------------|-------------|
| `mock_server_llm_input_tokens` | Cumulative input tokens across all LLM completions |
| `mock_server_llm_output_tokens` | Cumulative output tokens across all LLM completions |
| `mock_server_llm_cost_usd` | Cumulative estimated cost in USD (via `LlmPricing`) |

Cost estimation uses the static pricing table in `LlmPricing` — it is an estimate, not an invoice. Models with unknown pricing contribute tokens but no cost.

The forward-path response parse (which extracts the `Completion` from the upstream response) is gated on `GenAiSpans.isEnabled() || Metrics.isLlmMetricsActive() || llmCostBudgetUsd > 0`, so token/cost metrics work without requiring full OTLP tracing.

Example PromQL:
```promql
sum(rate(mock_server_llm_cost_usd[1h]))
```

### LLM Optimisation Verdict Gauges

Three Prometheus `GaugeWithCallback` gauges expose the headline figures of the latest **LLM optimisation report** (see [llm-mocking.md → LLM Optimisation Export](llm-mocking.md#llm-optimisation-export)). They are registered once when `metricsEnabled` is `true`.

| Metric Name | Type | Labels | Source |
|-------------|------|--------|--------|
| `mock_server_llm_estimated_waste_usd` | GaugeWithCallback | — | `report.verdict.totalEstimatedSavingUsd` |
| `mock_server_llm_cache_hit_ratio` | GaugeWithCallback | — | `report.totals.cacheHitRatio` (0..1) |
| `mock_server_llm_one_shot_rate` | GaugeWithCallback | — | `report.totals.oneShotRate` (0..1) |

These are **single global gauges with no per-model labels** — deliberately, to avoid the unbounded label cardinality that a per-model breakdown would create (cf. the load-injection `run_id` series-leak lesson). The waste-USD figure reuses the deterministic Wave-1 verdict's `totalEstimatedSavingUsd` (clamped ≤ total spend); it is not re-derived.

**Source of truth: cached snapshot, not a scrape-time rebuild.** The optimisation report is built on demand (REST endpoint / MCP tool) — building it retrieves the recorded request/response pairs from the event log and decodes each, which is too expensive to run on every Prometheus scrape, and the core `Metrics` gauge callback has no access to the netty-side log-retrieval path. So each time a report is built, `LlmOptimisationReportService.build(...)` pushes its three headline figures into a small `AtomicReference` snapshot on `Metrics` (`Metrics.updateLlmOptimisationSnapshot(...)`), and the gauge callbacks read that snapshot at scrape time. **Trade-off:** the gauges reflect the *most recently built* report, not a continuously-live computation — they read `0` until a report has ever been built (no traffic analysed yet) and are reset to `0` on `Metrics.clear()` (server reset). A dashboard/scheduled-export that builds the report periodically keeps the gauges fresh; if no one ever builds a report, the gauges stay at `0`. This is correct-and-cheap, versus building-at-scrape which would be scrape-time-correct but pay the full report cost (bounded by `llmOptimisationMaxCalls`, default 200) on every scrape.

The three gauges are also mirrored to OTLP by `OtelMetricsExporter` (matching the load gauges, which are likewise mirrored), reading the same cached snapshot at collection time, so OTLP-only consumers see the verdict without a Prometheus scrape.

Example PromQL alert (more than $5 of recoverable spend detected):
```promql
mock_server_llm_estimated_waste_usd > 5
```

### LLM Cost Budget Circuit-Breaker

`mock_server_llm_cost_budget_tripped` is a Prometheus `Counter` that increments each time the LLM cost-budget circuit-breaker triggers. The breaker is configured by `mockserver.llmCostBudgetUsd` (a cumulative USD budget); when the running cost total exceeds it, further LLM forwards are blocked with a 429 response. The budget is **enforced on all forward paths**: matched FORWARD actions (FORWARD, FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK, FORWARD_REPLACE, FORWARD_VALIDATE, FORWARD_WITH_FALLBACK), breakpoint-continuation forwards, unmatched proxy-pass forwards, and `proxyPassMappings` reverse-proxy routes. For matched FORWARD actions, the guard resolves the forward target host from the action (e.g. `HttpForward.getHost()`) so the sniffer checks the upstream host, not the inbound request host. The budget is tracked independently of the Prometheus counter (via `LlmCostBudgetMonitor`) so it works even when `metricsEnabled` is false. The breaker is deterministic and fail-open: a negative, unset, or malformed budget never blocks traffic. It resets on `HttpState.reset()`.

The sole excluded forward path is `FORWARD_OBJECT_CALLBACK`, intentionally excluded because its upstream target is determined by arbitrary user callback code at runtime (the same architectural exclusion as chaos injection on that path).

The cost-budget trip shares the same operator-facing observability surface as the chaos auto-halt:
- **Prometheus counter**: `mock_server_llm_cost_budget_tripped`
- **WARN log**: emitted on each trip with cumulative cost and budget values
- **Dashboard**: the MetricsView "Circuit Breakers" section (inside the HTTP Chaos Faults panel) shows both the chaos auto-halt and the LLM cost-budget trip count, with cumulative cost display

| Property | Default | Description |
|----------|---------|-------------|
| `llmMetricsEnabled` | `false` | Enable LLM token/cost Prometheus counters (requires `metricsEnabled`) |
| `llmCostBudgetUsd` | `-1.0` (disabled) | Cumulative cost budget in USD; negative = disabled |

### Async Message Counters

Two Prometheus `Counter`s track broker message flow for the optional `mockserver-async` (AsyncAPI broker-mocking) module, each labelled by `channel` (the broker topic/channel). Both are registered once when `metricsEnabled` is `true`.

| Metric Name | Incremented When |
|-------------|------------------|
| `mock_server_async_messages_published_total` | MockServer publishes an example message to a broker — one increment per message in `AsyncApiMockOrchestrator.publishAll()` (covers both publish-on-load and scheduled publishing), incremented **after** `MessagePublisher.flush()` confirms delivery |
| `mock_server_async_messages_consumed_total` | MockServer records a message consumed from a broker subscription — one increment per message in the `KafkaMessageSubscriber` / `MqttMessageSubscriber` record path |

`mockserver-async` depends on `mockserver-core` (optional scope) and calls the static `Metrics.incrementAsyncMessagePublished(channel)` / `Metrics.incrementAsyncMessageConsumed(channel)` methods directly; both are null-safe no-ops when metrics are disabled, so the async hot paths pay nothing when metrics are off. These counters only move when a real broker is connected (`brokerConfig` with `kafkaBootstrapServers`/`mqttBrokerUrl`); a broker-less spec load leaves them at zero.

**Counted after delivery is confirmed.** Kafka sends asynchronously, so incrementing at the point of `publish()` would count messages the broker might still reject — the counter would over-report success. The increment therefore happens after `flush()` returns.

`publishAll()` contains failures **per message**, so a channel whose publish throws (e.g. an AMQP message reaching no queue) is recorded as a failure and the loop continues to the remaining channels. The cycle is therefore never truncated by one bad channel, and every channel that published successfully is counted.

The one remaining inaccuracy is **a failed `flush()`**: Kafka's `flush()` surfaces only the first delivery failure and carries no per-message attribution, so the messages in that cycle which *did* reach the broker cannot be identified and none of them are counted. That under-count is deliberate. An over-count would make the metric lie in the reassuring direction — the precise failure this module was hardened against — whereas an under-count arrives alongside a thrown exception and a `WARN` log that make the truth recoverable. **Do not "fix" this by moving the increment back before the flush.** Narrowing it further would require per-message delivery attribution from the publisher, not a reordering here.

The dashboard **Metrics** view renders these on a dedicated **"Async message activity (cumulative)"** chart — kept separate from the HTTP **"HTTP request activity"** chart because broker message counts and HTTP request counts have different semantics. The two series (Published, Consumed) are summed across all channels client-side via `gaugeSeriesSum`; the panel is hidden until at least one async counter has data.

### Dropped Log Events Counter

A single Prometheus `Counter` makes the event-log ring-buffer saturation cliff observable. Registered once when `metricsEnabled` is `true`.

| Metric Name | Incremented When |
|-------------|------------------|
| `mock_server_dropped_log_events` | A log event cannot be published because the `MockServerEventLog` disruptor ring buffer is full (`tryPublishEvent` returns `false`) |

Under sustained load the event-log ring buffer can saturate and drop events. Previously only WARN/ERROR drops were logged, so INFO/DEBUG drops were silent and the cliff was undetectable. `MockServerEventLog.add(...)` now counts every drop on an always-available `AtomicLong` (readable via `getDroppedLogEventCount()` regardless of whether metrics are enabled), mirrors it to this Prometheus counter via the null-safe static `Metrics.incrementDroppedLogEvents()` (a no-op when metrics are off), and logs a single WARN on the first drop pointing at `ringBufferSize` / log verbosity as the remedy. A non-zero, growing value means the event log cannot keep up — raise `ringBufferSize` (derived from `maxLogEntries`) or reduce log verbosity.

### Event-Log Ring-Buffer Internals Gauges

Eight Prometheus `GaugeWithCallback` gauges expose the **live** state of the event log's **two** retention sites so a scrape *during* a run shows memory **building** rather than only its aftermath (a non-zero `mock_server_dropped_log_events`), and shows **which** site is holding it. They exist because the question "is the event log the bottleneck?" — and then "is it the in-flight backlog or the retained log?" — could not be answered from outside the JVM when two perf runs (builds 261/264) died mid-load; the retained columns close the specific false-negative where a run whose *retained* deque is filling the heap looked identical to one where the event log was empty. Registered once when `metricsEnabled` is `true`; each reads live at scrape time from `MockServerEventLog` via a supplier `HttpState` installs at startup, and all read **0** before a log is registered (never a fabricated value).

The **in-flight site** is the disruptor ring — entries published but not yet processed:

| Metric Name | Type | Description |
|-------------|------|-------------|
| `mock_server_event_log_ring_occupancy` | GaugeWithCallback | Disruptor ring slots currently occupied (published, not yet consumed). `getBufferSize() − remainingCapacity()`. Approaching `..._ring_capacity` means the single consumer cannot keep up — drops are imminent. |
| `mock_server_event_log_ring_capacity` | GaugeWithCallback | Ring total slot count (`ringBufferSize` in force). |
| `mock_server_event_log_in_flight_bytes` | GaugeWithCallback | Request/response body bytes held by entries published to the ring but not yet processed (the in-flight backlog tracked by commit `49005f5c3`). |
| `mock_server_event_log_max_in_flight_bytes` | GaugeWithCallback | In-flight body-byte budget in force (`maxEventLogSizeInBytes`); `0` means the in-flight bound is disabled. |

The **retained site** is the backing deque — entries kept after processing:

| Metric Name | Type | Description |
|-------------|------|-------------|
| `mock_server_event_log_retained_entries` | GaugeWithCallback | Log entries currently retained after processing (backing `CircularConcurrentLinkedDeque` element count). |
| `mock_server_event_log_retained_bytes` | GaugeWithCallback | Request/response body bytes held by retained entries (the deque's summed weight). Reported whether or not the byte budget is enabled — `maxEventLogSizeInBytes <= 0` disables byte *eviction*, not byte *accounting*, so this stays live (and matters most) when nothing is capping the retained log. The post-processing companion to `..._in_flight_bytes`. |
| `mock_server_event_log_max_retained_bytes` | GaugeWithCallback | Retained body-byte budget in force (`maxEventLogSizeInBytes`); `0` means the retained byte bound is disabled. |
| `mock_server_event_log_max_retained_entries` | GaugeWithCallback | Retained entry-count cap in force (`maxLogEntries`). |

The reads are cheap (volatile/atomic reads plus two ring reads), off the request hot path, so the scrape pays for them only when metrics are enabled. Backing accessors are `MockServerEventLog.getRingBufferOccupancy()` / `getInFlightBytes()` / `getMaxInFlightBytes()` / `getRingBufferSizeInForce()` for the ring and `getRetainedEntryCount()` / `getRetainedBytes()` / `getMaxRetainedBytes()` / `getMaxRetainedEntries()` for the deque (which read `CircularConcurrentLinkedDeque.size()` / `getTotalBytes()` / `getMaxBytes()` / `getMaxSize()`), all surfaced through `Metrics.RingStats` and `Metrics.setEventLogRingStatsSupplier(...)`. Example PromQL (ring filling toward its ceiling):
```promql
mock_server_event_log_ring_occupancy / mock_server_event_log_ring_capacity > 0.8
```

> **Perf-regression JVM-diagnostics dependency:** `perf-test-run.sh`'s dense resource-trajectory sampler reads `mock_server_dropped_log_events_total`, the in-flight ring gauges (`mock_server_event_log_ring_occupancy`, `..._ring_capacity`, `..._in_flight_bytes`, `..._max_in_flight_bytes`) and the retained deque gauges (`mock_server_event_log_retained_entries`, `..._retained_bytes`, `..._max_retained_bytes`, `..._max_retained_entries`) — ordered so the two sites read side by side — plus `mock_server_evicted_log_entries_total` and the `mock_server_request_duration_seconds` histogram (its `_count`, `_sum`, and the cumulative `le` buckets 0.005/0.01/0.025/0.05/0.1, so a **server-side** percentile can be compared against the client's) (alongside the JVM heap/GC/thread series) into `diag-samples.csv`. The event-log ring gauges are tolerated as **absent** on an older SUT image that predates them (the CSV column is simply blank) so the sampler degrades gracefully until a snapshot carrying them is built. If these metric names change, update `perf-test-run.sh`'s `diag_sampler()` in the same commit. A blank cell in that CSV has **three** distinct meanings and they are not conflated: the metric is absent on an old image; the scrape **timed out** under load (`--max-time 4`, common in the rows just before a death and itself a death signal — the host-side `container_mem` columns keep populating through it); or a genuine measured `0`, which is written as `0`, never blank.

### Expectation-Store Byte Gauges

Two `GaugeWithCallback` gauges and one `CounterWithCallback` expose the memory the **expectation store** holds, mirroring the event-log retained-bytes pair. The store is normally bounded only by a count (`maxExpectations`), which is blind to how large each expectation is; `maxExpectationsSizeInBytes` (default `0` = off) adds an optional byte bound. Registered once when `metricsEnabled` is `true`; each reads live at scrape time from `RequestMatchers` via a supplier `HttpState` installs at startup, and all read **0** before a store is registered.

| Metric Name | Type | Description |
|-------------|------|-------------|
| `mock_server_expectations_bytes` | GaugeWithCallback | Estimated retained heap (summed entry weight) held by the expectation store. Tracked whether or not the byte budget is enabled — `maxExpectationsSizeInBytes <= 0` disables byte *eviction*, not byte *accounting* — so this reports a real number by default. |
| `mock_server_max_expectations_bytes` | GaugeWithCallback | Expectation-store byte budget in force (`maxExpectationsSizeInBytes`); `0` means the byte bound is disabled (the default). |
| `mock_server_expectations_byte_evicted_total` | CounterWithCallback | Cumulative expectations evicted specifically to stay within the byte budget (read at scrape time from the store's own `getByteEvictedCount()`; scraped with the client-appended `_total` suffix). |

The reads are cheap (atomic reads) and off the request hot path. Backing accessors are `RequestMatchers.getExpectationBytes()` / `getMaxExpectationBytes()` / `getExpectationByteEvictedCount()`, which delegate to the backend `KeyValueStore` (`InMemoryExpectationKeyValueStore` → `CircularPriorityQueue.getTotalBytes()` / `getMaxBytes()` / `getByteEvictedCount()`), surfaced through `Metrics.ExpectationStoreStats` and `Metrics.setExpectationStoreStatsSupplier(...)`.

### Accept-Queue Backlog Gauges

Two `GaugeWithCallback` gauges expose the TCP **accept queue** — the queue the kernel parks completed handshakes in while MockServer accepts them. It matters only when many clients connect at once; a full queue does not look like a limit (the kernel silently drops the handshake, the client retransmits after ~1s), so the symptom is a median latency near one second with no errors.

| Metric Name | Type | Description |
|-------------|------|-------------|
| `mock_server_accept_queue_backlog_configured` | GaugeWithCallback | Configured accept-queue depth (`soBacklog`). Always emitted. |
| `mock_server_accept_queue_backlog_effective` | GaugeWithCallback | `min(soBacklog, /proc/sys/net/core/somaxconn)` — the kernel ceiling read **once at startup**. **Emitted only when that file is readable (Linux).** On macOS / a restricted container the file is unreadable and this gauge is **omitted entirely** rather than reported as the configured value under an "effective" name (which would misstate the ceiling actually in force). |

The **live accept-queue depth** and **SYN-drop count** are kernel counters (`/proc/net/netstat` `TcpExt: ListenOverflows`), not reachable in-process; they are deliberately not attempted here. Watch the OS-level counter directly if you need the live overflow count.

### Load Injection Metrics (`mock_server_load_*`)

The `mock_server_load_*` family is registered by `Metrics.registerLoadMetrics()` when `metricsEnabled` is `true` (there is no `loadGenerationEnabled` check in `Metrics` registration — that flag only gates the PUT endpoint). All metrics in this family are also mirrored to OTLP by `OtelMetricsExporter` — see [telemetry.md](telemetry.md).

**Per-run series retention.** Each run uses a fresh UUID `run_id` label, so without eviction the Prometheus client would retain every completed run's datapoints in the registry forever (unbounded memory growth, slower scrapes). The orchestrator calls `Metrics.evictLoadRun(previousRunId)` when a *new* run starts, so a completed (or replaced) run's durable series stay scrapeable until the next run begins and are then evicted — at most one completed run's series are retained (bounded). The two observable gauges (`mock_server_load_active_vus`, `mock_server_load_inflight_requests`) are not evicted because they self-clear via the orchestrator's empty-callback readers. On the OTLP side the per-run attribute sets are managed by the OTEL SDK's own aggregation/cardinality handling (the load counters are direct `LongCounter.add`, not callbacks), so they are not manually evicted.

Fixed structured label set for per-request metrics (`LOAD_FIXED_LABELS`):
`scenario`, `run_id`, `step`, `route`, `method`, `status_class`

Optional custom labels (appended after fixed labels) are declared via the `mockserver.loadGenerationMetricLabels` allowlist. The allowlist is captured at registration time because Prometheus requires a fixed schema.

| Metric Name | Type | Labels | Description |
|-------------|------|--------|-------------|
| `mock_server_load_request_duration_seconds` | Histogram | fixed + custom | Round-trip latency per dispatch. Carries a `trace_id` exemplar from the upstream response `traceparent` header when present. |
| `mock_server_load_requests` | Counter | fixed + custom | Completed dispatches |
| `mock_server_load_request_bytes` | Counter (unit: bytes) | fixed + custom | Outbound request bytes |
| `mock_server_load_response_bytes` | Counter (unit: bytes) | fixed + custom | Inbound response bytes |
| `mock_server_load_iterations` | Counter | `scenario`, `run_id` | Full VU iteration completions |
| `mock_server_load_throttled` | Counter | `scenario`, `run_id`, `reason` | Dispatches skipped by the self-load guard (`reason` = `inflight_cap` or `rate_limit`) |
| `mock_server_load_errors` | Counter | `scenario`, `run_id`, `kind` | Failed dispatches (`kind` = `render`, `connection`, `timeout`, `null_response`, `http_5xx`) |
| `mock_server_load_active_vus` | GaugeWithCallback | `scenario`, `run_id` | Virtual users currently running |
| `mock_server_load_inflight_requests` | GaugeWithCallback | `scenario`, `run_id` | Dispatches currently in flight |

The `route` label is auto-templatized by `MetricLabels.routeOf()` (numeric and UUID path segments become `{id}`) to keep cardinality bounded. A step with an explicit `name` field uses that name as `route` directly. See [load-generation.md](load-generation.md) for the full model and custom-label details.

Example PromQL (p95 latency per scenario):
```promql
histogram_quantile(0.95,
  sum by (le, scenario) (
    rate(mock_server_load_request_duration_seconds_bucket[1m])
  )
)
```

Example PromQL (throttle rate — did the scenario reach its setpoint?):
```promql
rate(mock_server_load_throttled_total[1m])
```

### SLO Sample Tracking

Independent of the Prometheus metrics feature, MockServer can record a windowed
sample for every forwarded upstream round-trip so that resilience verdicts can be
computed on demand via `PUT /mockserver/verifySLO` (see
[docs/code/slo-verdicts.md](slo-verdicts.md)).

The recording funnel lives in the **same place** as the forward-metrics funnel —
`HttpActionHandler.recordForwardMetrics(...)` — but has its own independent gate:

```text
recordForwardMetrics(action, response, upstreamAddress, responseTimeMs)
  ├─ SloSampleStore.getInstance().record(now, latency, isError, FORWARD, host)   // gated by sloTrackingEnabled (no-op when off)
  └─ Metrics.observeForwardRequest(host, status, latencySeconds)                 // gated by isForwardMetricsActive()
```

Because `SloSampleStore.record(...)` is a no-op when `sloTrackingEnabled` is
`false` (the default), SLO tracking runs even when forward metrics are inactive and
costs nothing on the hot path when disabled. The store is bounded by
`sloWindowMaxSamples` (count) and `sloWindowRetentionMillis` (age), and is cleared
on server reset. A sample's error flag is set when the upstream status is `null` or
`>= 500` — the same definition used by the forward metrics counters.

### How Metrics Are Incremented

- `HttpActionHandler` calls `metrics.increment(action.getType())` after dispatching each action, which maps the `Action.Type` enum to the corresponding `*_ACTIONS_COUNT` gauge
- `HttpState` increments `REQUESTS_RECEIVED_COUNT` on each request and `EXPECTATIONS_NOT_MATCHED_COUNT` when no expectation matches
- `WebSocketClientRegistry` calls `metrics.set()` to update WebSocket client and handler counts
- `Metrics.clear()` resets all gauges to zero (called during server reset)

### Scrape Endpoint

`MetricsHandler` serves the `/mockserver/metrics` endpoint. It uses `ExpositionFormats` to render all registered metrics from `PrometheusRegistry.defaultRegistry`, respecting the client's `Accept` header for content negotiation.

#### Security: unauthenticated by design, disableable

The scrape endpoint is deliberately **not** routed through `HttpState.controlPlaneRequestAuthenticated` — unlike `/mockserver/dashboard` and `/mockserver/openapi.yaml`, which the same `HttpRequestHandler` dispatch block *does* gate. Prometheus/OTEL scrapers cannot present a control-plane client certificate or bearer token while scraping, so gating the endpoint would break metrics collection. This is intentional and must not be "fixed" by adding auth. (The JSON metrics snapshot `PUT /mockserver/retrieve?type=METRICS` goes through `HttpState.handle` and *is* behind the control-plane auth gate like every other `retrieve` — only the Prometheus scrape endpoint is open.)

Because the endpoint is open, its labels can leak operational metadata to anyone with network reach: the forward/proxy series carry an `upstream_host` label (host only), and the LLM counters (`mock_server_llm_input_tokens`, `mock_server_llm_output_tokens`, `mock_server_llm_cost_usd`) are labelled by `provider`/`model`. To secure it: (a) leave it disabled with `metricsEnabled=false` (the default) — when disabled, `MetricsHandler.renderMetrics` short-circuits to a `404 Not Found` and exposes nothing (pinned by `MetricsHandlerTest.shouldReflectOriginOnDisabledNotFound`); (b) restrict access at the network layer (loopback bind / firewall / Kubernetes `NetworkPolicy`); or (c) prefer PUSH-based export — OTLP metrics (`OtelMetricsExporter`) or Prometheus Remote-Write (`PrometheusRemoteWriteExporter`, both below) — which expose **no** scrape endpoint at all. See the consumer-facing [API Security → Securing the metrics scrape endpoint](../../jekyll-www.mock-server.com/mock_server/control_plane_authorisation.html) note and [tls-and-security.md](tls-and-security.md).

### Grafana Dashboard & Kubernetes Scraping

A standalone, importable Grafana dashboard for the server metric family ships at
[`examples/grafana/mockserver-server.json`](../../examples/grafana/mockserver-server.json)
(see its [README](../../examples/grafana/README.md)). It charts request
throughput and match outcomes (via the `_total` counters), request-latency
percentiles, the `_actions_count` expectation levels, per-upstream forward/proxy
health, dropped-log-events, HTTP/LLM chaos counters, and the JVM runtime gauges —
every panel references a metric documented on this page. It exposes a `datasource`
template variable, so it imports against any Prometheus data source. (This is
distinct from the k6 load-injection dashboard under
`examples/kubernetes/load-injection-observability`, which mixes in load-generator
series.)

In Kubernetes, the MockServer Helm chart can create a **Prometheus Operator
`ServiceMonitor`** for the scrape endpoint — set `serviceMonitor.enabled=true`
(disabled by default; requires the `monitoring.coreos.com/v1` CRDs and
`metricsEnabled=true`). See [helm.md](../infrastructure/helm.md) and
`helm/mockserver/values.yaml`.

## Prometheus Remote Write (push)

As an alternative (or complement) to being scraped, MockServer can **push** its metrics to a Prometheus **Remote-Write** endpoint on an interval. This suits short-lived pods, agentless setups, and vendor endpoints that ingest remote write — Prometheus (`--web.enable-remote-write-receiver`), Grafana Cloud / Mimir, **New Relic**, VictoriaMetrics, and Thanos Receive. It is off by default and fail-soft: a push failure logs one line and never affects request handling.

```mermaid
flowchart LR
    REG["PrometheusRegistry.defaultRegistry"] -->|scrape| RW["PrometheusRemoteWriteExporter"]
    RW --> ENC["RemoteWriteV1Encoder\n(protobuf via CodedOutputStream)"]
    ENC --> SNP["Snappy block compress"]
    SNP --> HTTP["JDK HttpClient POST\nauth + custom headers"]
    HTTP --> BE["Prometheus / New Relic / Mimir /\nVictoriaMetrics / Thanos"]
```

The exporter reuses the **same snapshot** the scrape endpoint serves — `PrometheusRegistry.defaultRegistry.scrape()` — so the pushed series are byte-for-byte the metrics at `/mockserver/metrics` (whole registry, no curated subset). Each snapshot data point becomes one Remote-Write `TimeSeries` (`__name__` + labels + a single sample at push time). Counter→`<name>_total`, gauge→`<name>`, classic histogram→cumulative `<name>_bucket{le}` (incl. `le="+Inf"`) plus `_count`/`_sum`, summary→quantile series plus `_count`/`_sum`; unknown/native-only snapshot types are skipped with a DEBUG log (never dropped silently for the supported types). The `WriteRequest` protobuf is hand-encoded with `com.google.protobuf.CodedOutputStream` (no protoc/codegen is added to the build — the v1 wire schema is frozen), then compressed with the raw **Snappy block** format Remote Write requires (`org.xerial.snappy.Snappy.compress`), and POSTed with the JDK `java.net.http.HttpClient`.

Both Remote-Write **v1** (default, universally supported) and **v2** are selectable via `prometheusRemoteWriteProtocolVersion`. v2 (`RemoteWriteV2Encoder`) interns all label names/values into a per-request `symbols` string table (index 0 is the empty string) referenced by `labels_refs`, and carries per-series `Metadata` (type + help/unit refs); it sends `Content-Type: application/x-protobuf;proto=io.prometheus.write.v2.Request` and `X-Prometheus-Remote-Write-Version: 2.0.0`. Both encoders sort each series' full label set lexicographically by name (required by the spec, or strict receivers reject the series). The encoder is chosen by `PrometheusRemoteWriteExporter.selectEncoder(...)`, which fails safe to v1 on any unknown/blank value.

Remote write is inherently **cumulative** (the Prometheus data model); the OTLP delta option (see [telemetry.md](telemetry.md)) does not apply here.

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `prometheusRemoteWriteEnabled` | `false` | Enable periodic Remote-Write push |
| `prometheusRemoteWriteUrl` | (empty) | Full endpoint URL, e.g. `http://prometheus:9090/api/v1/write`. Enabled-but-blank logs a warning and does nothing |
| `prometheusRemoteWriteProtocolVersion` | `v1` | Remote-Write protocol version: `v1` (universal) or `v2` (symbol-interned, carries metadata). Unknown/blank falls back to `v1` |
| `prometheusRemoteWriteIntervalSeconds` | `60` | Push interval (clamped to ≥ 1) |
| `prometheusRemoteWriteBearerToken` | (empty) | Sends `Authorization: Bearer <token>`; takes precedence over basic auth |
| `prometheusRemoteWriteBasicAuthUsername` | (empty) | HTTP basic-auth username (used when no bearer token) |
| `prometheusRemoteWriteBasicAuthPassword` | (empty) | HTTP basic-auth password |
| `prometheusRemoteWriteHeaders` | (empty) | Extra headers as a `key=value,key2=value2` list (e.g. New Relic `Api-Key=...`, Mimir `X-Scope-OrgID=tenant`); applied after the resolved auth header |

Auth resolution: a bearer token wins if set; otherwise basic auth (if a username is set); then the custom headers are applied last (so a user-supplied header can override). Token/password/header **values are never logged** (the startup line reports only whether auth is configured). Each POST carries `Content-Type: application/x-protobuf`, `Content-Encoding: snappy`, and `X-Prometheus-Remote-Write-Version: 0.1.0`.

Lifecycle mirrors the OTLP exporter: `PrometheusRemoteWriteExporter.startIfEnabled()` is created by `LifeCycle` and stopped on shutdown; a single daemon scheduler pushes with a fixed **delay** (so a slow push cannot pile up).

## Memory Monitoring

`MemoryMonitoring` provides CSV-based memory usage tracking, enabled via the `outputMemoryUsageCsv` configuration property.

### Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `outputMemoryUsageCsv` | `false` | Enable memory usage CSV output |
| `memoryUsageCsvDirectory` | `.` | Directory for CSV output files |

### How It Works

`MemoryMonitoring` implements both `MockServerLogListener` and `MockServerMatcherListener`. It receives notifications when log entries are added or expectations change, and writes memory statistics to a CSV file (named `memoryUsage_YYYY-MM-DD.csv`) every 50 updates.

### CSV Columns

| Column | Description |
|--------|-------------|
| `mockServerPort` | Server port |
| `eventLogSize` | Current log entry count |
| `maxLogEntries` | Configured max log entries |
| `expectationsSize` | Current expectation count |
| `maxExpectations` | Configured max expectations |
| `heapInitialAllocation` | JVM heap initial allocation (bytes) |
| `heapUsed` | JVM heap used (bytes) |
| `heapCommitted` | JVM heap committed (bytes) |
| `heapMaxAllowed` | JVM heap max allowed (bytes) |
| `nonHeapInitialAllocation` | JVM non-heap initial allocation (bytes) |
| `nonHeapUsed` | JVM non-heap used (bytes) |
| `nonHeapCommitted` | JVM non-heap committed (bytes) |
| `nonHeapMaxAllowed` | JVM non-heap max allowed (bytes) |

## Key Classes

| Class | Module | Path |
|-------|--------|------|
| `Metrics` | mockserver-core | `org.mockserver.metrics.Metrics` |
| `Metrics.Name` | mockserver-core | `org.mockserver.metrics.Metrics.Name` (enum) |
| `MetricsHandler` | mockserver-core | `org.mockserver.metrics.MetricsHandler` |
| `BuildInfoCollector` | mockserver-core | `org.mockserver.metrics.BuildInfoCollector` |
| `JvmMetricsCollector` | mockserver-core | `org.mockserver.metrics.JvmMetricsCollector` |
| `ChaosAutoHaltMonitor` | mockserver-core | `org.mockserver.mock.action.http.ChaosAutoHaltMonitor` |
| `LlmCostBudgetMonitor` | mockserver-core | `org.mockserver.mock.action.http.LlmCostBudgetMonitor` |
| `MetricLabels` | mockserver-core | `org.mockserver.metrics.MetricLabels` (route templatizing for load metrics) |
| `OtelMetricsExporter` | mockserver-core | `org.mockserver.metrics.OtelMetricsExporter` (OTLP mirror of load metrics) |
| `PrometheusRemoteWriteExporter` | mockserver-core | `org.mockserver.metrics.PrometheusRemoteWriteExporter` (periodic Remote-Write push) |
| `RemoteWriteV1Encoder` | mockserver-core | `org.mockserver.metrics.remotewrite.RemoteWriteV1Encoder` (MetricSnapshots → Remote-Write v1 protobuf) |
| `RemoteWriteV2Encoder` | mockserver-core | `org.mockserver.metrics.remotewrite.RemoteWriteV2Encoder` (Remote-Write v2 protobuf with symbol table + metadata) |
| `SnappyBlock` | mockserver-core | `org.mockserver.metrics.remotewrite.SnappyBlock` (raw Snappy block compression) |
| `LoadScenarioOrchestrator` | mockserver-core | `org.mockserver.mock.action.http.LoadScenarioOrchestrator` (records load metric samples) |
| `SloSampleStore` | mockserver-core | `org.mockserver.slo.SloSampleStore` (see [slo-verdicts.md](slo-verdicts.md)) |
| `MemoryMonitoring` | mockserver-core | `org.mockserver.memory.MemoryMonitoring` |
| `Summary` | mockserver-core | `org.mockserver.memory.Summary` |
| `Detail` | mockserver-core | `org.mockserver.memory.Detail` |

## Dependencies

| GroupId | ArtifactId | Version | Purpose |
|---------|-----------|---------|---------|
| `io.prometheus` | `prometheus-metrics-core` | 1.7.0 | Prometheus client library (Gauge, MultiCollector, PrometheusRegistry) |
| `io.prometheus` | `prometheus-metrics-exposition-formats` | 1.7.0 | Prometheus exposition format writers |
| `io.prometheus` | `prometheus-metrics-model` | 1.7.0 | Prometheus metric snapshots and labels (also the Remote-Write snapshot source) |
| `com.google.protobuf` | `protobuf-java` | 4.35.1 | `CodedOutputStream` used to hand-encode the Remote-Write `WriteRequest` (already a core dep; no protoc/codegen added) |
| `org.xerial.snappy` | `snappy-java` | 1.1.10.7 | Raw Snappy **block** compression for the Remote-Write body |

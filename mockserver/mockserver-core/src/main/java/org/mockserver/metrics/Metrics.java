package org.mockserver.metrics;

import io.prometheus.metrics.core.metrics.Counter;
import io.prometheus.metrics.core.metrics.CounterWithCallback;
import io.prometheus.metrics.core.metrics.Gauge;
import io.prometheus.metrics.core.metrics.GaugeWithCallback;
import io.prometheus.metrics.core.metrics.Histogram;
import io.prometheus.metrics.model.registry.PrometheusRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.action.http.ChaosAutoHaltMonitor;
import org.mockserver.mock.action.http.ForwardCircuitBreaker;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.model.Action;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.mockserver.log.model.LogEntry.LogMessageType.EXCEPTION;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "FieldMayBeFinal"})
public class Metrics {

    private static final AtomicReference<Boolean> additionalMetricsRegistered = new AtomicReference<>(false);
    // Guards the registration block in the constructor and resetAdditionalMetricsForTesting()
    // so they cannot interleave. Without this, concurrent test classes that reset-then-register
    // race on PrometheusRegistry.defaultRegistry — one thread's clear() can land in the middle
    // of another thread's registration, causing "duplicate metric name" errors.
    private static final Object registrationLock = new Object();
    private static final Map<Name, Gauge> metrics = new ConcurrentHashMap<>();
    // Request-latency histogram. Null until metrics are enabled, so
    // observeRequestDurationSeconds() is a no-op when metrics are off (the
    // caller on the request hot path pays nothing — see the Part A/C tension).
    private static volatile Histogram requestDurationSeconds;
    // Per-route (method-labeled) histogram, registered only when route labels are enabled.
    private static volatile Histogram requestDurationByMethodSeconds;
    // Counter for slow forwarded requests. Null until metrics are enabled.
    private static volatile Counter slowRequestTotal;
    // Counter for log events dropped because the event-log disruptor ring buffer was full.
    // Null until metrics are enabled. Makes the (previously silent for INFO/DEBUG) ring-buffer
    // saturation cliff observable under load.
    private static volatile Counter droppedLogEventsTotal;
    // Counter for event-log entries evicted because the event log reached its maximum size.
    // Null until metrics are enabled. Makes silent loss of retained evidence observable, which
    // matters because an evicted log can no longer prove the absence of a request.
    private static volatile Counter evictedLogEntriesTotal;
    // Per-upstream forwarded-request observability. Histogram of forward/proxy
    // latency labeled by upstream host, plus a count labeled by host + status
    // class. Both null until metrics are enabled. Cardinality is bounded by the
    // number of distinct upstream hosts forwarded to (NOT the full URL/path).
    private static volatile Histogram forwardRequestDurationSeconds;
    private static volatile Counter forwardRequestsTotal;
    // Protocol actually negotiated to the upstream on each forward/proxy connection (http2 via ALPN vs
    // http1_1), labeled by upstream host — lets operators confirm whether forwardProxyHttp2Upgrade is
    // taking effect, since a forward stuck on http1_1 to a backend that withholds its streaming SSE head
    // over HTTP/1.1 is the classic cause of a high forward time-to-first-byte.
    private static volatile Counter forwardUpstreamProtocolTotal;
    // Counter for HTTP chaos faults injected (error or latency). Null until metrics are enabled.
    private static volatile Counter httpChaosInjectedTotal;
    /**
     * The complete, authoritative set of {@code fault_type} label values the HTTP chaos-injection
     * counter ({@code mock_server_http_chaos_injected}) is incremented with. Single source of truth
     * for the per-fault-type OTLP mirror in {@link OtelMetricsExporter} so it can never drift behind
     * the set of fault types {@link #incrementHttpChaosInjected(String)} actually records. Distinct
     * from {@code ServiceChaosRegistry.FAULT_TYPES} (the service-profile set, which has no
     * {@code rateLimit}); these are the injection-counter label values.
     */
    static final java.util.List<String> CHAOS_FAULT_TYPES =
        java.util.List.of("drop", "error", "latency", "truncate", "malformed", "slow", "quota", "graphql", "rateLimit");
    /**
     * The five genuinely-monotonic {@link Name} counts that ALSO dual-publish a proper Prometheus
     * {@link Counter}, mapped to that counter's registration name. The Prometheus client appends the
     * mandatory {@code _total} suffix, so e.g. {@code mock_server_requests_received} is scraped as
     * {@code mock_server_requests_received_total}. The legacy {@code *_count} gauges (registered from
     * the {@link Name} enum) are retained unchanged for back-compat; these counters are the series to
     * use for {@code rate()}/{@code increase()}. Single source of truth for the constructor
     * registration, the {@link #increment(Name)} mirror, and the {@link OtelMetricsExporter} OTLP
     * mirror, so the three can never drift. Insertion-ordered for deterministic registration/export.
     */
    static final Map<Name, String> MONOTONIC_TOTAL_COUNTER_NAMES;
    static {
        Map<Name, String> names = new LinkedHashMap<>();
        names.put(Name.REQUESTS_RECEIVED_COUNT, "mock_server_requests_received");
        names.put(Name.EXPECTATIONS_NOT_MATCHED_COUNT, "mock_server_expectations_not_matched");
        names.put(Name.RESPONSE_EXPECTATIONS_MATCHED_COUNT, "mock_server_response_expectations_matched");
        names.put(Name.FORWARD_EXPECTATIONS_MATCHED_COUNT, "mock_server_forward_expectations_matched");
        names.put(Name.LLM_CHAOS_INJECTED_COUNT, "mock_server_llm_chaos_injected");
        MONOTONIC_TOTAL_COUNTER_NAMES = Collections.unmodifiableMap(names);
    }
    // Counter for chaos auto-halt events. Null until metrics are enabled.
    private static volatile Counter chaosAutoHaltTotal;
    // Counter for MCP tool calls, labeled by tool name. Null until metrics are enabled.
    private static volatile Counter mcpToolCallsTotal;
    // Counters for async/broker messages published to and consumed from a broker,
    // labeled by channel. Null until metrics are enabled. Incremented by the
    // mockserver-async module (publish path + subscriber record path).
    private static volatile Counter asyncMessagesPublishedTotal;
    private static volatile Counter asyncMessagesConsumedTotal;
    // LLM token and cost counters, labeled by provider and model.
    // Null until metrics are enabled AND llmMetricsEnabled is true.
    private static volatile Counter llmInputTokensTotal;
    private static volatile Counter llmOutputTokensTotal;
    private static volatile Counter llmCostUsdTotal;
    // Counter for LLM cost-budget circuit-breaker trips. Null until metrics are enabled.
    private static volatile Counter llmCostBudgetTrippedTotal;
    // Opt-in per-expectation match counter, labeled by the stable expectation id.
    // Null unless metrics are enabled AND configuration.perExpectationMetricsEnabled()
    // is true. OFF by default because per-expectation labels can explode
    // Prometheus cardinality: one time series per distinct expectation id. Using the
    // stable expectation id (not the request path) bounds cardinality to the number of
    // expectations, but operators with very large or churning expectation sets should
    // leave this off. See docs/code/metrics.md.
    private static volatile Counter expectationMatchedTotal;
    // Dual-published proper Prometheus Counters for the five genuinely-monotonic *_count gauges
    // (requests received, not-matched, response/forward matched, LLM chaos injected). Registered
    // ALONGSIDE — never instead of — the legacy _count gauges: the gauges keep their exact names for
    // the dashboard UI + Grafana back-compat, while these counters expose the proper _total series so
    // PromQL rate()/increase() work correctly. Keyed by Name; each counter's exposition name is the
    // map value + the client-forced "_total" suffix (see MONOTONIC_TOTAL_COUNTER_NAMES). Empty until
    // metrics are enabled, so an increment on the request hot path pays only a cheap map lookup.
    private static final Map<Name, Counter> monotonicTotalCounters = new ConcurrentHashMap<>();
    // Supplier of active expectations, set by HttpState at startup so the
    // expectations-by-type GaugeWithCallback can read live state at scrape time
    // without a core->netty dependency.
    private static final AtomicReference<Supplier<List<Expectation>>> activeExpectationsSupplier = new AtomicReference<>();
    // Supplier of the current cluster member count, set by HttpState at startup
    // so the cluster_members GaugeWithCallback can read live membership at scrape
    // time from the StateBackend without Metrics depending on the state package.
    // Defaults to 1 (single local node) until a supplier is registered.
    private static final AtomicReference<Supplier<Integer>> clusterMemberCountSupplier = new AtomicReference<>();
    // Supplier of the event-log ring-buffer live occupancy stats (occupied slots, ring capacity,
    // in-flight body bytes, in-flight byte budget), set by HttpState at startup so the event-log
    // ring GaugeWithCallbacks can read live state at scrape time from MockServerEventLog without
    // Metrics (core) depending on the log package's instance lifecycle. Null until registered; the
    // gauges then read 0 (a run with no event log, or before startup). See RingStats.
    private static final AtomicReference<Supplier<RingStats>> eventLogRingStatsSupplier = new AtomicReference<>();
    // Supplier of the expectation store's live byte figures (total weight, byte budget in force,
    // byte-driven eviction count), set by HttpState at startup so the mock_server_expectations_bytes /
    // _max_expectations_bytes / _byte_evicted gauges can read live state at scrape time from
    // RequestMatchers without Metrics (core) depending on the store instance lifecycle. Null until
    // registered; the gauges then read 0 (before startup / no store). See ExpectationStoreStats.
    private static final AtomicReference<Supplier<ExpectationStoreStats>> expectationStoreStatsSupplier = new AtomicReference<>();
    // Kernel accept-queue ceiling source (Linux). Read ONCE at construction to decide whether the
    // effective-backlog gauge can honestly be emitted; a package-private field only so tests can point
    // it at a temp file (readable) or a missing path (unreadable) to exercise both branches.
    static final Path DEFAULT_SOMAXCONN_PATH = Paths.get("/proc/sys/net/core/somaxconn");
    static volatile Path somaxconnPath = DEFAULT_SOMAXCONN_PATH;
    // OTel histogram for OTLP export. Set by OtelMetricsExporter when enabled; null otherwise.
    private static volatile io.opentelemetry.api.metrics.DoubleHistogram otelRequestDurationHistogram;

    // Latest LLM optimisation verdict/totals snapshot, backing the three single global
    // optimisation gauges (estimated waste USD, cache-hit ratio, one-shot rate). The report is
    // built on demand (REST / MCP), so rather than rebuild it (and re-retrieve the event log) at
    // every scrape, LlmOptimisationReportService.build() pushes the headline figures here and the
    // callback gauges read this snapshot. Null until a report has ever been built — the gauges then
    // read 0 (no traffic analysed yet). These are single global gauges with NO per-model labels, so
    // there is no unbounded label cardinality (cf. the load run_id series leak). Cleared on reset.
    private static final AtomicReference<LlmOptimisationSnapshot> llmOptimisationSnapshot = new AtomicReference<>();

    // --- Load-injection (load scenario) metric family. ADDITIVE to the forward family: the
    // mock_server_load_* metrics give load runs their own scenario/run/step/route dimension so a
    // load injector can be charted next to its system-under-test. All null until metrics are
    // enabled (registered once in the constructor), so a load run with metrics off pays nothing.
    // The classic histogram bucket scheme is reused from the forward histogram. Custom label
    // allowlist (loadGenerationMetricLabels) is captured at registration because Prometheus needs a
    // fixed label-name set; OTEL carries arbitrary custom labels via attributes (see OtelMetricsExporter).
    private static final double[] LOAD_DURATION_BUCKETS = {0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10};
    /** Fixed structured Prometheus label names for the per-request load metrics, in order. */
    static final String[] LOAD_FIXED_LABELS = {"scenario", "run_id", "step", "route", "method", "status_class"};
    /** Allowlisted custom label names appended (in order) after the fixed labels; empty by default. */
    private static volatile String[] loadCustomLabelNames = new String[0];
    private static volatile Histogram loadRequestDurationSeconds;
    private static volatile Counter loadRequestsTotal;
    private static volatile Counter loadRequestBytesTotal;
    private static volatile Counter loadResponseBytesTotal;
    private static volatile Counter loadIterationsTotal;     // labels: scenario, run_id
    private static volatile Counter loadThrottledTotal;      // labels: scenario, run_id, reason
    private static volatile Counter loadErrorsTotal;         // labels: scenario, run_id, kind
    private static volatile Counter loadChecksTotal;         // labels: scenario, run_id, step, outcome
    // GaugeWithCallback live readers, installed by the orchestrator while a run is active.
    private static final AtomicReference<Supplier<Map<LoadGaugeKey, Integer>>> loadActiveVusReader = new AtomicReference<>();
    private static final AtomicReference<Supplier<Map<LoadGaugeKey, Integer>>> loadInflightReader = new AtomicReference<>();
    // OTEL load instruments, set by OtelMetricsExporter when enabled; null otherwise.
    private static volatile io.opentelemetry.api.metrics.DoubleHistogram otelLoadRequestDuration;
    private static volatile io.opentelemetry.api.metrics.LongCounter otelLoadRequests;
    private static volatile io.opentelemetry.api.metrics.LongCounter otelLoadRequestBytes;
    private static volatile io.opentelemetry.api.metrics.LongCounter otelLoadResponseBytes;
    private static volatile io.opentelemetry.api.metrics.LongCounter otelLoadIterations;
    private static volatile io.opentelemetry.api.metrics.LongCounter otelLoadThrottled;
    private static volatile io.opentelemetry.api.metrics.LongCounter otelLoadErrors;
    private static volatile io.opentelemetry.api.metrics.LongCounter otelLoadChecks;

    private final Boolean metricsEnabled;

    public Metrics(Configuration configuration) {
        metricsEnabled = configuration.metricsEnabled();
        if (metricsEnabled) {
            synchronized (registrationLock) {
                if (additionalMetricsRegistered.compareAndSet(false, true)) {
                    PrometheusRegistry.defaultRegistry.register(new BuildInfoCollector());
                    PrometheusRegistry.defaultRegistry.register(new JvmMetricsCollector());
                    Arrays.stream(Name.values()).forEach(Metrics::getOrCreate);
                    // Dual-publish a proper Counter for each of the five monotonic counts alongside
                    // the legacy _count gauge registered just above. Non-breaking: the gauges are
                    // untouched (dashboard UI + Grafana keep working); these add the _total series
                    // that rate()/increase() need. Incremented in lock-step by increment(Name).
                    MONOTONIC_TOTAL_COUNTER_NAMES.forEach((name, counterName) ->
                        monotonicTotalCounters.put(name, Counter.builder()
                            .name(counterName)
                            .help(name.description + " (monotonic counter; use rate()/increase() on this _total series)")
                            .register()));
                    requestDurationSeconds = Histogram.builder()
                        .name("mock_server_request_duration_seconds")
                        .help("MockServer request handling duration in seconds")
                        .classicOnly()
                        .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                        .register();
                    slowRequestTotal = Counter.builder()
                        .name("mock_server_slow_requests")
                        .help("Total number of forwarded requests that exceeded the slow request threshold")
                        .register();
                    droppedLogEventsTotal = Counter.builder()
                        .name("mock_server_dropped_log_events")
                        .help("Total number of log events dropped because the event-log ring buffer was full")
                        .register();
                    evictedLogEntriesTotal = Counter.builder()
                        .name("mock_server_evicted_log_entries")
                        .help("Number of event log entries evicted because the event log reached its maximum size")
                        .register();
                    forwardRequestDurationSeconds = Histogram.builder()
                        .name("mock_server_forward_request_duration_seconds")
                        .help("Latency of forwarded/proxied requests in seconds, labeled by upstream host")
                        .labelNames("upstream_host")
                        .classicOnly()
                        .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                        .register();
                    forwardRequestsTotal = Counter.builder()
                        .name("mock_server_forward_requests")
                        .help("Total forwarded/proxied requests by upstream host and response status class")
                        .labelNames("upstream_host", "status_class")
                        .register();
                    forwardUpstreamProtocolTotal = Counter.builder()
                        .name("mock_server_forward_upstream_protocol")
                        .help("Total forward/proxy upstream connections by upstream host and the protocol actually negotiated to the upstream (http2 via ALPN, or http1_1)")
                        .labelNames("upstream_host", "protocol")
                        .register();
                    httpChaosInjectedTotal = Counter.builder()
                        .name("mock_server_http_chaos_injected")
                        .help("Total HTTP chaos faults injected by type")
                        .labelNames("fault_type")
                        .register();
                    chaosAutoHaltTotal = Counter.builder()
                        .name("mock_server_chaos_auto_halt")
                        .help("Total number of times the chaos auto-halt circuit-breaker triggered")
                        .register();
                    mcpToolCallsTotal = Counter.builder()
                        .name("mock_server_mcp_tool_calls")
                        .help("Total MCP tool calls by tool name")
                        .labelNames("tool")
                        .register();
                    asyncMessagesPublishedTotal = Counter.builder()
                        .name("mock_server_async_messages_published")
                        .help("Total async/broker messages published by channel")
                        .labelNames("channel")
                        .register();
                    asyncMessagesConsumedTotal = Counter.builder()
                        .name("mock_server_async_messages_consumed")
                        .help("Total async/broker messages consumed/recorded by channel")
                        .labelNames("channel")
                        .register();
                    if (Boolean.TRUE.equals(configuration.llmMetricsEnabled())) {
                        llmInputTokensTotal = Counter.builder()
                            .name("mock_server_llm_input_tokens")
                            .help("Total LLM input tokens by provider and model")
                            .labelNames("provider", "model")
                            .register();
                        llmOutputTokensTotal = Counter.builder()
                            .name("mock_server_llm_output_tokens")
                            .help("Total LLM output tokens by provider and model")
                            .labelNames("provider", "model")
                            .register();
                        llmCostUsdTotal = Counter.builder()
                            .name("mock_server_llm_cost_usd")
                            .help("Cumulative estimated LLM cost in USD by provider and model")
                            .labelNames("provider", "model")
                            .register();
                    }
                    llmCostBudgetTrippedTotal = Counter.builder()
                        .name("mock_server_llm_cost_budget_tripped")
                        .help("Total number of times the LLM cost-budget circuit-breaker tripped")
                        .register();
                    if (Boolean.TRUE.equals(configuration.perExpectationMetricsEnabled())) {
                        // Opt-in: registered only when perExpectationMetricsEnabled is true so
                        // the default scrape is byte-for-byte identical to today (no new series).
                        expectationMatchedTotal = Counter.builder()
                            .name("mock_server_expectation_matched")
                            .help("Total expectation matches (matched and responded) labeled by stable expectation id")
                            .labelNames("expectation_id")
                            .register();
                    }
                    // Callback gauge, labeled by action_type: read active expectations at
                    // scrape time and group by action type, so no imperative tracking is needed.
                    GaugeWithCallback.builder()
                        .name("mock_server_expectations_by_type")
                        .help("Number of active expectations grouped by action type")
                        .labelNames("action_type")
                        .callback(callback ->
                            getActiveExpectationCountByType().forEach((actionType, count) ->
                                callback.call(count, actionType)))
                        .register();
                    // Callback gauge, labeled by fault_type: read the live registry at scrape
                    // time rather than tracking it imperatively, so TTL auto-revert (which
                    // removes a profile without a put/remove call) is reflected without any
                    // extra plumbing. One series per fault type so it can be charted by type.
                    GaugeWithCallback.builder()
                        .name("mock_server_active_service_chaos")
                        .help("Number of active service-scoped chaos profiles configured with each fault type")
                        .labelNames("fault_type")
                        .callback(callback ->
                            getActiveServiceChaosCountByFaultType().forEach((faultType, count) ->
                                callback.call(count, faultType)))
                        .register();
                    // Callback gauge: report the live cluster member count at scrape
                    // time. For a single-node / in-memory deployment this is 1; for a
                    // clustered backend it reflects the current fleet size read from
                    // the StateBackend via the registered supplier.
                    GaugeWithCallback.builder()
                        .name("mock_server_cluster_members")
                        .help("Number of members in the MockServer cluster (1 for a single-node deployment)")
                        .callback(callback -> callback.call(getClusterMemberCount()))
                        .register();
                    // Callback gauge: number of upstreams whose forward/proxy circuit breaker is
                    // currently open. Read live at scrape time so half-open recovery is reflected
                    // without imperative plumbing. Always 0 when the breaker is disabled.
                    GaugeWithCallback.builder()
                        .name("mock_server_upstream_circuit_open")
                        .help("Number of upstreams whose forward/proxy circuit breaker is currently open")
                        .callback(callback -> callback.call(getOpenUpstreamCircuitCount()))
                        .register();
                    // Callback gauges: event-log ring-buffer live internals. Read at scrape time from
                    // MockServerEventLog via the registered supplier so a scrape DURING a run shows the
                    // disruptor backlog building — occupancy climbing toward capacity, or in-flight body
                    // bytes climbing toward their budget — rather than only its aftermath (a non-zero
                    // mock_server_dropped_log_events). This is the "is the event log the bottleneck?"
                    // question that builds 261/264 could not answer from k6's side. All read 0 before a
                    // log is registered. The reads are cheap (volatile/atomic reads), off the hot path.
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_ring_occupancy")
                        .help("Event-log disruptor ring-buffer slots currently occupied (published, not yet consumed)")
                        .callback(callback -> callback.call(getEventLogRingStats().occupancy))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_ring_capacity")
                        .help("Event-log disruptor ring-buffer total slot count (ringBufferSize in force)")
                        .callback(callback -> callback.call(getEventLogRingStats().capacity))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_in_flight_bytes")
                        .help("Request/response body bytes held by log entries published to the ring but not yet processed")
                        .callback(callback -> callback.call(getEventLogRingStats().inFlightBytes))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_max_in_flight_bytes")
                        .help("In-flight body-byte budget in force (maxEventLogSizeInBytes); 0 means the in-flight bound is disabled")
                        .callback(callback -> callback.call(getEventLogRingStats().maxInFlightBytes))
                        .register();
                    // Callback gauges: the RETAINED (post-processing) event-log site — the deque that
                    // holds entries after the disruptor consumer has processed them. This is the SECOND
                    // event-log retention site, separate from the ring_*/in_flight_bytes gauges above
                    // (which cover only the in-flight ring). A scrape that reads both sites side by side
                    // can attribute heap growth to the right one: an empty ring with a full deque means
                    // the retained log is holding the heap, not the backlog. Read live at scrape time
                    // via the same supplier; all read 0 before a log is registered.
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_retained_entries")
                        .help("Event-log entries retained after processing (backing deque element count)")
                        .callback(callback -> callback.call(getEventLogRingStats().retainedEntries))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_retained_bytes")
                        .help("Request/response body bytes held by log entries retained after processing (backing deque summed weight)")
                        .callback(callback -> callback.call(getEventLogRingStats().retainedBytes))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_max_retained_bytes")
                        .help("Retained body-byte budget in force (maxEventLogSizeInBytes); 0 means the retained byte bound is disabled")
                        .callback(callback -> callback.call(getEventLogRingStats().maxRetainedBytes))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_event_log_max_retained_entries")
                        .help("Retained entry-count cap in force (maxLogEntries)")
                        .callback(callback -> callback.call(getEventLogRingStats().maxRetainedEntries))
                        .register();
                    // Callback gauges: the expectation store's live byte weight and the byte budget in
                    // force. Mirror the event-log retained-bytes pair: the total is tracked whether or
                    // not the budget is enabled (maxExpectationsSizeInBytes <= 0 disables byte EVICTION,
                    // not byte ACCOUNTING), so _bytes reports a real number by default while
                    // _max_expectations_bytes reads 0 when the byte bound is off. Read live at scrape
                    // time via the supplier HttpState installs; both read 0 before a store is registered.
                    GaugeWithCallback.builder()
                        .name("mock_server_expectations_bytes")
                        .help("Estimated retained heap (summed entry weight) held by the expectation store")
                        .callback(callback -> callback.call(getExpectationStoreStats().totalBytes))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_max_expectations_bytes")
                        .help("Expectation-store byte budget in force (maxExpectationsSizeInBytes); 0 means the byte bound is disabled")
                        .callback(callback -> callback.call(getExpectationStoreStats().maxBytes))
                        .register();
                    // Monotonic byte-driven eviction count, read at scrape time from the store's own
                    // AtomicLong (scraped as mock_server_expectations_byte_evicted_total).
                    CounterWithCallback.builder()
                        .name("mock_server_expectations_byte_evicted")
                        .help("Total expectations evicted to stay within the byte budget (maxExpectationsSizeInBytes)")
                        .callback(callback -> callback.call(getExpectationStoreStats().byteEvictedCount))
                        .register();
                    // Accept-queue backlog. The CONFIGURED depth is always emitted. The EFFECTIVE depth
                    // is min(configured, /proc/sys/net/core/somaxconn), and is emitted ONLY when that
                    // kernel file is readable (Linux). On macOS / a restricted container the file is
                    // unreadable and the gauge is omitted entirely rather than reported as the
                    // configured value under an "effective" name — an "effective" reading that is merely
                    // the configured value would be a lie about the kernel ceiling actually in force.
                    GaugeWithCallback.builder()
                        .name("mock_server_accept_queue_backlog_configured")
                        .help("Configured TCP accept-queue depth (soBacklog)")
                        .callback(callback -> callback.call(configuration.soBacklog()))
                        .register();
                    OptionalLong somaxconn = readSomaxconn();
                    if (somaxconn.isPresent()) {
                        long kernelLimit = somaxconn.getAsLong();
                        GaugeWithCallback.builder()
                            .name("mock_server_accept_queue_backlog_effective")
                            .help("Effective TCP accept-queue depth: min(soBacklog, /proc/sys/net/core/somaxconn read at startup); omitted when somaxconn is unreadable")
                            .callback(callback -> callback.call(Math.min(configuration.soBacklog(), kernelLimit)))
                            .register();
                    }
                    // Callback gauges: the latest LLM optimisation verdict/totals. Single global
                    // gauges (no per-model labels) reading the most-recently-built report's headline
                    // figures from the snapshot at scrape time. They report 0 until a report has been
                    // built (no LLM traffic analysed yet). See LlmOptimisationReportService.build().
                    GaugeWithCallback.builder()
                        .name("mock_server_llm_estimated_waste_usd")
                        .help("Estimated recoverable LLM spend (USD) from the latest optimisation report")
                        .callback(callback -> callback.call(getLlmEstimatedWasteUsd()))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_llm_cache_hit_ratio")
                        .help("Cache-hit ratio (0..1) from the latest LLM optimisation report")
                        .callback(callback -> callback.call(getLlmCacheHitRatio()))
                        .register();
                    GaugeWithCallback.builder()
                        .name("mock_server_llm_one_shot_rate")
                        .help("One-shot rate (0..1, fraction of non-retry calls) from the latest LLM optimisation report")
                        .callback(callback -> callback.call(getLlmOneShotRate()))
                        .register();
                    if (Boolean.TRUE.equals(configuration.metricsRequestDurationRouteLabels())) {
                        requestDurationByMethodSeconds = Histogram.builder()
                            .name("mock_server_request_duration_by_method_seconds")
                            .help("MockServer request handling duration in seconds, labeled by HTTP method")
                            .labelNames("method")
                            .classicOnly()
                            .classicUpperBounds(0.0005, 0.001, 0.0025, 0.005, 0.01, 0.025, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10)
                            .register();
                    }
                    registerLoadMetrics(configuration);
                }
            }
        }
    }

    /**
     * Register the {@code mock_server_load_*} family. The custom-label allowlist
     * ({@code loadGenerationMetricLabels}) is captured here because Prometheus requires a fixed
     * label-name set per metric; the per-request metrics carry the six fixed structured labels plus
     * the allowlisted custom labels (in that order). The two live gauges read the orchestrator via a
     * registered supplier at scrape time (mirrors {@code mock_server_active_service_chaos}), so a run
     * that has ended reports nothing without imperative cleanup.
     */
    private static void registerLoadMetrics(Configuration configuration) {
        java.util.List<String> allowlist = configuration.loadGenerationMetricLabels();
        loadCustomLabelNames = allowlist != null ? allowlist.toArray(new String[0]) : new String[0];
        String[] perRequestLabels = concat(LOAD_FIXED_LABELS, loadCustomLabelNames);

        loadRequestDurationSeconds = Histogram.builder()
            .name("mock_server_load_request_duration_seconds")
            .help("Load-scenario request duration in seconds, labeled by scenario/run/step/route/method/status class")
            .labelNames(perRequestLabels)
            .classicOnly()
            .classicUpperBounds(LOAD_DURATION_BUCKETS)
            .withExemplars()
            .register();
        loadRequestsTotal = Counter.builder()
            .name("mock_server_load_requests")
            .help("Total load-scenario requests by scenario/run/step/route/method/status class")
            .labelNames(perRequestLabels)
            .register();
        loadRequestBytesTotal = Counter.builder()
            .name("mock_server_load_request_bytes")
            .help("Total load-scenario request body bytes by scenario/run/step/route/method/status class")
            .labelNames(perRequestLabels)
            .register();
        loadResponseBytesTotal = Counter.builder()
            .name("mock_server_load_response_bytes")
            .help("Total load-scenario response body bytes by scenario/run/step/route/method/status class")
            .labelNames(perRequestLabels)
            .register();
        loadIterationsTotal = Counter.builder()
            .name("mock_server_load_iterations")
            .help("Total completed load-scenario iterations by scenario and run")
            .labelNames("scenario", "run_id")
            .register();
        loadThrottledTotal = Counter.builder()
            .name("mock_server_load_throttled")
            .help("Total load-scenario dispatches throttled by the self-load guard, by reason (inflight_cap, rate_limit)")
            .labelNames("scenario", "run_id", "reason")
            .register();
        loadErrorsTotal = Counter.builder()
            .name("mock_server_load_errors")
            .help("Total load-scenario request errors by kind (timeout, connection, render, http_5xx, null_response)")
            .labelNames("scenario", "run_id", "kind")
            .register();
        loadChecksTotal = Counter.builder()
            .name("mock_server_load_checks")
            .help("Total per-step load-scenario response checks by scenario/run/step and outcome (pass, fail)")
            .labelNames("scenario", "run_id", "step", "outcome")
            .register();
        GaugeWithCallback.builder()
            .name("mock_server_load_active_vus")
            .help("Number of active virtual users in the running load scenario, by scenario and run")
            .labelNames("scenario", "run_id")
            .callback(callback -> {
                Supplier<Map<LoadGaugeKey, Integer>> reader = loadActiveVusReader.get();
                if (reader != null) {
                    reader.get().forEach((key, count) -> callback.call(count, key.scenario, key.runId));
                }
            })
            .register();
        GaugeWithCallback.builder()
            .name("mock_server_load_inflight_requests")
            .help("Number of in-flight (dispatched, not-yet-completed) load-scenario requests, by scenario and run")
            .labelNames("scenario", "run_id")
            .callback(callback -> {
                Supplier<Map<LoadGaugeKey, Integer>> reader = loadInflightReader.get();
                if (reader != null) {
                    reader.get().forEach((key, count) -> callback.call(count, key.scenario, key.runId));
                }
            })
            .register();
    }

    private static String[] concat(String[] a, String[] b) {
        String[] result = new String[a.length + b.length];
        System.arraycopy(a, 0, result, 0, a.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static Gauge getOrCreate(Name name) {
        synchronized (name) {
            Gauge gauge = metrics.get(name);
            if (gauge == null) {
                try {
                    gauge = Gauge.builder()
                        .name(name.name().toLowerCase())
                        .help(name.description)
                        .register();
                    metrics.put(name, gauge);
                } catch (Throwable throwable) {
                    new MockServerLogger().logEvent(
                        new LogEntry()
                            .setType(EXCEPTION)
                            .setMessageFormat("exception:{} creating metric:{}")
                            .setArguments(throwable.getMessage(), name.name())
                            .setThrowable(throwable)
                    );
                }
            }
            return gauge;
        }
    }

    /**
     * Reset the one-shot registration guard and null all lazily-registered
     * metrics so that a subsequent {@code new Metrics(configuration)} call
     * re-registers them.  Also clears the default Prometheus registry.
     * <p>
     * Public for cross-package test access (e.g. chaos injection tests);
     * intended for test use only to guarantee deterministic test ordering.
     */
    public static void resetAdditionalMetricsForTesting() {
        synchronized (registrationLock) {
            additionalMetricsRegistered.set(false);
            requestDurationSeconds = null;
            requestDurationByMethodSeconds = null;
            slowRequestTotal = null;
            droppedLogEventsTotal = null;
            evictedLogEntriesTotal = null;
            forwardRequestDurationSeconds = null;
            forwardRequestsTotal = null;
            forwardUpstreamProtocolTotal = null;
            httpChaosInjectedTotal = null;
            chaosAutoHaltTotal = null;
            mcpToolCallsTotal = null;
            asyncMessagesPublishedTotal = null;
            asyncMessagesConsumedTotal = null;
            llmInputTokensTotal = null;
            llmOutputTokensTotal = null;
            llmCostUsdTotal = null;
            llmCostBudgetTrippedTotal = null;
            expectationMatchedTotal = null;
            monotonicTotalCounters.clear();
            otelRequestDurationHistogram = null;
            loadRequestDurationSeconds = null;
            loadRequestsTotal = null;
            loadRequestBytesTotal = null;
            loadResponseBytesTotal = null;
            loadIterationsTotal = null;
            loadThrottledTotal = null;
            loadErrorsTotal = null;
            loadChecksTotal = null;
            loadCustomLabelNames = new String[0];
            otelLoadRequestDuration = null;
            otelLoadRequests = null;
            otelLoadRequestBytes = null;
            otelLoadResponseBytes = null;
            otelLoadIterations = null;
            otelLoadThrottled = null;
            otelLoadErrors = null;
            otelLoadChecks = null;
            loadActiveVusReader.set(null);
            loadInflightReader.set(null);
            activeExpectationsSupplier.set(null);
            clusterMemberCountSupplier.set(null);
            expectationStoreStatsSupplier.set(null);
            somaxconnPath = DEFAULT_SOMAXCONN_PATH;
            llmOptimisationSnapshot.set(null);
            metrics.clear();
            PrometheusRegistry.defaultRegistry.clear();
        }
    }

    public static void clear() {
        metrics.forEach((name, gauge) -> gauge.set(0));
        // Drop the stale optimisation snapshot so the gauges read 0 after a reset
        // until a fresh report is built (mirrors the cost-budget/chaos reset convention).
        llmOptimisationSnapshot.set(null);
    }

    public static void clear(Name name) {
        getOrCreate(name).set(0);
    }

    public void set(Name name, Integer value) {
        if (metricsEnabled) {
            getOrCreate(name).set(value);
        }
    }

    public static Integer get(Name name) {
        return (int) getOrCreate(name).get();
    }

    /**
     * Register an OTel histogram for request duration. Called by
     * {@link OtelMetricsExporter} when OTLP export is enabled.
     */
    public static void registerOtelRequestDurationHistogram(io.opentelemetry.api.metrics.DoubleHistogram histogram) {
        otelRequestDurationHistogram = histogram;
    }

    /**
     * Record a request-handling duration (seconds) in the latency histogram.
     * No-op unless metrics are enabled (the histogram is null until then), so a
     * caller on the request hot path pays nothing when metrics are off.
     */
    public static void observeRequestDurationSeconds(double seconds) {
        Histogram histogram = requestDurationSeconds;
        if (histogram != null) {
            histogram.observe(seconds);
        }
        io.opentelemetry.api.metrics.DoubleHistogram otelHistogram = otelRequestDurationHistogram;
        if (otelHistogram != null) {
            otelHistogram.record(seconds);
        }
    }

    /**
     * Record a request-handling duration (seconds) in the per-method labeled histogram.
     * No-op unless route labels are enabled.
     *
     * @param seconds  duration in seconds
     * @param method   the HTTP method (e.g. "GET", "POST")
     */
    public static void observeRequestDurationByMethodSeconds(double seconds, String method) {
        Histogram histogram = requestDurationByMethodSeconds;
        if (histogram != null && method != null) {
            histogram.labelValues(method.toUpperCase()).observe(seconds);
        }
    }

    /**
     * Return the current slow-request count, or 0 if metrics are disabled.
     * Used by {@link OtelMetricsExporter} to mirror the Prometheus counter via OTLP.
     */
    public static long getSlowRequestCount() {
        Counter counter = slowRequestTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the slow request counter. No-op unless metrics are enabled.
     */
    public static void incrementSlowRequestTotal() {
        Counter counter = slowRequestTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Increment the dropped-log-events counter (event-log ring buffer full).
     * No-op unless metrics are enabled (the counter is null otherwise). The
     * authoritative, always-available count is maintained on
     * {@link org.mockserver.log.MockServerEventLog#getDroppedLogEventCount()};
     * this mirrors it to Prometheus when metrics are on.
     */
    public static void incrementDroppedLogEvents() {
        Counter counter = droppedLogEventsTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Return the current dropped-log-events count, or 0 if metrics are disabled.
     */
    public static long getDroppedLogEventCount() {
        Counter counter = droppedLogEventsTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the evicted-log-entries counter (event log reached its maximum
     * size). No-op unless metrics are enabled (the counter is null otherwise).
     */
    public static void incrementEvictedLogEntries() {
        Counter counter = evictedLogEntriesTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Return the current evicted-log-entries count, or 0 if metrics are disabled.
     */
    public static long getEvictedLogEntryCount() {
        Counter counter = evictedLogEntriesTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Record observability for a single forwarded/proxied request: its latency
     * (seconds) in the per-upstream histogram and a count in the per-upstream,
     * per-status-class counter. No-op unless metrics are enabled (both metrics
     * are null until then), so the forward path pays nothing when metrics are off.
     * <p>
     * Cardinality is bounded by the number of distinct upstream hosts (the
     * {@code upstream_host} label is the host only — never the full URL/path) and
     * the five status classes ({@code 1xx}..{@code 5xx}). A null/blank host is
     * recorded as {@code "unknown"}.
     *
     * @param upstreamHost the resolved upstream host (no port, no path)
     * @param statusCode   the upstream response status code, or null if unknown
     * @param latencySeconds the forward latency in seconds (negative values are clamped to 0)
     */
    public static void observeForwardRequest(String upstreamHost, Integer statusCode, double latencySeconds) {
        String hostLabel = upstreamHost != null && !upstreamHost.isEmpty() ? upstreamHost : "unknown";
        Histogram histogram = forwardRequestDurationSeconds;
        if (histogram != null) {
            histogram.labelValues(hostLabel).observe(latencySeconds > 0 ? latencySeconds : 0);
        }
        Counter counter = forwardRequestsTotal;
        if (counter != null) {
            counter.labelValues(hostLabel, statusClass(statusCode)).inc();
        }
    }

    /**
     * Record the protocol a forward/proxy upstream connection actually negotiated to the upstream
     * (e.g. {@code "http2"} via ALPN, or {@code "http1_1"}). Lets operators confirm whether
     * {@code forwardProxyHttp2Upgrade} is taking effect — a forward shown as {@code http1_1} to an
     * upstream that withholds its streaming SSE response head over HTTP/1.1 explains a high forward
     * time-to-first-byte (e.g. the OpenAI Codex backend the opencode CLI uses). No-op when metrics are
     * disabled.
     */
    public static void incrementForwardUpstreamProtocol(String upstreamHost, String protocol) {
        Counter counter = forwardUpstreamProtocolTotal;
        if (counter != null) {
            String hostLabel = upstreamHost != null && !upstreamHost.isEmpty() ? upstreamHost : "unknown";
            counter.labelValues(hostLabel, protocol).inc();
        }
    }

    /**
     * Return the current forward upstream-protocol count for the given host and negotiated protocol,
     * or 0 if metrics are disabled.
     */
    public static long forwardUpstreamProtocolCount(String upstreamHost, String protocol) {
        Counter counter = forwardUpstreamProtocolTotal;
        if (counter == null) {
            return 0;
        }
        return (long) counter.labelValues(upstreamHost, protocol).get();
    }

    /**
     * Map an HTTP status code to its status class label ({@code "1xx"}..{@code "5xx"}).
     * Unknown/out-of-range codes are recorded as {@code "unknown"} so the
     * {@code status_class} label has a small, bounded value set.
     */
    private static String statusClass(Integer statusCode) {
        if (statusCode == null || statusCode < 100 || statusCode >= 600) {
            return "unknown";
        }
        return (statusCode / 100) + "xx";
    }

    /**
     * Return the current forward-request count for the given upstream host and
     * status class, or 0 if metrics are disabled.
     */
    public static long getForwardRequestCount(String upstreamHost, String statusClass) {
        Counter counter = forwardRequestsTotal;
        if (counter == null || upstreamHost == null || statusClass == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(upstreamHost, statusClass).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return true if the per-upstream forward metrics are registered (i.e. metrics are enabled).
     */
    public static boolean isForwardMetricsActive() {
        return forwardRequestsTotal != null;
    }

    /**
     * Return the current chaos-injected count for the given fault type, or 0 if
     * metrics are disabled. Used by {@link OtelMetricsExporter} to mirror the
     * Prometheus counter via OTLP.
     */
    public static long getHttpChaosInjectedCount(String faultType) {
        Counter counter = httpChaosInjectedTotal;
        if (counter == null || faultType == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(faultType).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Increment the HTTP chaos injected counter for the given fault type.
     * No-op when metrics are disabled (counter not registered) or faultType is null.
     *
     * @param faultType one of "drop", "error", "latency", "truncate", "malformed", "slow", "quota", "graphql", or "rateLimit"
     */
    public static void incrementHttpChaosInjected(String faultType) {
        Counter counter = httpChaosInjectedTotal;
        if (counter != null && faultType != null) {
            counter.labelValues(faultType).inc();
        }
        // Evaluate the chaos auto-halt circuit-breaker on every chaos fault injection.
        // ChaosAutoHaltMonitor.recordError() is a no-op when the feature is disabled
        // or when the fault type is non-destructive (e.g. latency, slow).
        ChaosAutoHaltMonitor.getInstance().recordError(faultType);
    }

    /**
     * Increment the chaos auto-halt counter. Called by
     * {@link ChaosAutoHaltMonitor} when the circuit-breaker triggers.
     * No-op when metrics are disabled (counter not registered).
     */
    public static void incrementChaosAutoHalt() {
        Counter counter = chaosAutoHaltTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Return the current chaos auto-halt count, or 0 if metrics are disabled.
     */
    public static long getChaosAutoHaltCount() {
        Counter counter = chaosAutoHaltTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the MCP tool call counter for the given tool name.
     * No-op when metrics are disabled (counter not registered) or toolName is null.
     * Counts each completed tool invocation (called after the tool handler returns).
     *
     * @param toolName the name of the MCP tool invoked (from the bounded tool registry)
     */
    public static void incrementMcpToolCall(String toolName) {
        Counter counter = mcpToolCallsTotal;
        if (counter != null && toolName != null) {
            counter.labelValues(toolName).inc();
        }
    }

    /**
     * Return the current MCP tool call count for the given tool name, or 0 if
     * metrics are disabled.
     */
    public static long getMcpToolCallCount(String toolName) {
        Counter counter = mcpToolCallsTotal;
        if (counter == null || toolName == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(toolName).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Increment the async-messages-published counter for the given channel.
     * No-op when metrics are disabled (counter not registered) or channel is null.
     * Called by the mockserver-async publish path (one increment per message published to a broker).
     *
     * @param channel the broker channel/topic the message was published to
     */
    public static void incrementAsyncMessagePublished(String channel) {
        Counter counter = asyncMessagesPublishedTotal;
        if (counter != null && channel != null) {
            counter.labelValues(channel).inc();
        }
    }

    /**
     * Increment the async-messages-consumed counter for the given channel.
     * No-op when metrics are disabled (counter not registered) or channel is null.
     * Called by the mockserver-async subscriber record path (one increment per message recorded from a broker).
     *
     * @param channel the broker channel/topic the message was consumed from
     */
    public static void incrementAsyncMessageConsumed(String channel) {
        Counter counter = asyncMessagesConsumedTotal;
        if (counter != null && channel != null) {
            counter.labelValues(channel).inc();
        }
    }

    /**
     * Return the current async-messages-published count for the given channel, or 0 if
     * metrics are disabled.
     */
    public static long getAsyncMessagePublishedCount(String channel) {
        Counter counter = asyncMessagesPublishedTotal;
        if (counter == null || channel == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(channel).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return the current async-messages-consumed count for the given channel, or 0 if
     * metrics are disabled.
     */
    public static long getAsyncMessageConsumedCount(String channel) {
        Counter counter = asyncMessagesConsumedTotal;
        if (counter == null || channel == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(channel).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Increment the LLM token and cost counters for a served or forwarded completion.
     * No-op when LLM metrics are not registered (llmMetricsEnabled is false or metrics are disabled).
     * Fail-soft: a null or unresolvable provider/model is recorded as "unknown".
     *
     * @param provider     the LLM provider name (e.g. "ANTHROPIC", "OPENAI")
     * @param model        the model name (e.g. "claude-opus-4")
     * @param inputTokens  number of input tokens (may be 0)
     * @param outputTokens number of output tokens (may be 0)
     * @param costUsd      estimated cost in USD (may be 0.0; null means unknown and is skipped)
     */
    public static void incrementLlmTokens(String provider, String model, long inputTokens, long outputTokens, Double costUsd) {
        String providerLabel = provider != null && !provider.isEmpty() ? provider.toLowerCase() : "unknown";
        String modelLabel = model != null && !model.isEmpty() ? model : "unknown";
        Counter inputCounter = llmInputTokensTotal;
        if (inputCounter != null && inputTokens > 0) {
            inputCounter.labelValues(providerLabel, modelLabel).inc(inputTokens);
        }
        Counter outputCounter = llmOutputTokensTotal;
        if (outputCounter != null && outputTokens > 0) {
            outputCounter.labelValues(providerLabel, modelLabel).inc(outputTokens);
        }
        Counter costCounter = llmCostUsdTotal;
        if (costCounter != null && costUsd != null && costUsd > 0.0) {
            costCounter.labelValues(providerLabel, modelLabel).inc(costUsd);
        }
    }

    /**
     * Return the current LLM input token count for the given provider and model, or 0 if
     * LLM metrics are disabled.
     */
    public static long getLlmInputTokens(String provider, String model) {
        Counter counter = llmInputTokensTotal;
        if (counter == null || provider == null || model == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(provider.toLowerCase(), model).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return the current LLM output token count for the given provider and model, or 0 if
     * LLM metrics are disabled.
     */
    public static long getLlmOutputTokens(String provider, String model) {
        Counter counter = llmOutputTokensTotal;
        if (counter == null || provider == null || model == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(provider.toLowerCase(), model).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return the current cumulative LLM cost in USD for the given provider and model, or 0.0 if
     * LLM metrics are disabled.
     */
    public static double getLlmCostUsd(String provider, String model) {
        Counter counter = llmCostUsdTotal;
        if (counter == null || provider == null || model == null) {
            return 0.0;
        }
        try {
            return counter.labelValues(provider.toLowerCase(), model).get();
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Return the aggregate cumulative LLM cost in USD across all providers and models.
     * Used by the cost-budget circuit-breaker. Returns 0.0 if LLM metrics are disabled.
     */
    public static double getLlmCostUsdTotal() {
        Counter counter = llmCostUsdTotal;
        if (counter == null) {
            return 0.0;
        }
        try {
            // Sum across all label combinations by iterating the data points
            final double[] total = {0.0};
            counter.collect().getDataPoints().forEach(dp -> total[0] += dp.getValue());
            return total[0];
        } catch (Exception e) {
            return 0.0;
        }
    }

    /**
     * Return true if LLM token/cost counters are registered (i.e. both metricsEnabled
     * and llmMetricsEnabled are true).
     */
    public static boolean isLlmMetricsActive() {
        return llmInputTokensTotal != null;
    }

    /**
     * Increment the LLM cost-budget circuit-breaker tripped counter.
     * No-op when metrics are disabled (counter not registered).
     */
    public static void incrementLlmCostBudgetTripped() {
        Counter counter = llmCostBudgetTrippedTotal;
        if (counter != null) {
            counter.inc();
        }
    }

    /**
     * Return the current LLM cost-budget tripped count, or 0 if metrics are disabled.
     */
    public static long getLlmCostBudgetTrippedCount() {
        Counter counter = llmCostBudgetTrippedTotal;
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Increment the per-expectation match counter for the given stable expectation id.
     * <p>
     * No-op unless per-expectation metrics are enabled
     * ({@link Configuration#perExpectationMetricsEnabled()}) AND metrics are enabled
     * (the counter is null otherwise), or when
     * {@code expectationId} is null. Labeled by the stable expectation id (not the
     * request path) so cardinality is bounded by the number of distinct expectations.
     *
     * @param expectationId the stable id of the matched expectation
     */
    public static void incrementExpectationMatched(String expectationId) {
        Counter counter = expectationMatchedTotal;
        if (counter != null && expectationId != null) {
            counter.labelValues(expectationId).inc();
        }
    }

    /**
     * Return the current per-expectation match count for the given expectation id,
     * or 0 if the per-expectation counter is not registered.
     */
    public static long getExpectationMatchedCount(String expectationId) {
        Counter counter = expectationMatchedTotal;
        if (counter == null || expectationId == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(expectationId).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Return true if the per-expectation match counter is registered (i.e. both
     * metricsEnabled and perExpectationMetricsEnabled are on).
     */
    public static boolean isPerExpectationMetricsActive() {
        return expectationMatchedTotal != null;
    }

    /**
     * Return the current value of the dual-published {@code _total} counter for one of the five
     * monotonic counts, or 0 if metrics are disabled (the counter is not registered) or the name is
     * not one of the monotonic five. Used by {@link OtelMetricsExporter} to mirror the Prometheus
     * counter via OTLP. Equal to the legacy {@code _count} gauge value except across a MockServer
     * reset, where the gauge is zeroed but the monotonic counter continues (standard counter
     * semantics — see docs/code/metrics.md).
     */
    public static long getMonotonicTotalCount(Name name) {
        Counter counter = monotonicTotalCounters.get(name);
        return counter != null ? (long) counter.get() : 0L;
    }

    /**
     * Set the supplier of active expectations. Called by HttpState at startup
     * so the expectations-by-type GaugeWithCallback can read live state at
     * scrape time without a core-to-netty dependency.
     *
     * @param supplier returns the list of currently-active expectations
     */
    public static void setActiveExpectationsSupplier(Supplier<List<Expectation>> supplier) {
        activeExpectationsSupplier.set(supplier);
    }

    /**
     * Per-action-type count of currently-active expectations.
     * Backs the {@code mock_server_expectations_by_type} Prometheus gauge;
     * reads the live expectation list at scrape time via the registered supplier.
     */
    public static Map<String, Integer> getActiveExpectationCountByType() {
        Map<String, Integer> counts = new HashMap<>();
        Supplier<List<Expectation>> supplier = activeExpectationsSupplier.get();
        if (supplier != null) {
            try {
                List<Expectation> expectations = supplier.get();
                if (expectations != null) {
                    for (Expectation expectation : expectations) {
                        Action<?> action = expectation.getAction();
                        if (action != null) {
                            String typeName = action.getType().name();
                            counts.merge(typeName, 1, Integer::sum);
                        }
                    }
                }
            } catch (Exception ignored) {
                // fail-soft: return whatever has been accumulated
            }
        }
        return counts;
    }

    /**
     * Per-fault-type count of currently-active service-scoped chaos profiles.
     * Backs the {@code mock_server_active_service_chaos} Prometheus gauge and its
     * OTLP mirror; reads the registry directly (not gated on {@code metricsEnabled},
     * the gauge is only registered when metrics are on).
     */
    public static Map<String, Integer> getActiveServiceChaosCountByFaultType() {
        return ServiceChaosRegistry.getInstance().activeCountByFaultType();
    }

    /**
     * Set the supplier of the current cluster member count. Called by HttpState
     * at startup so the {@code mock_server_cluster_members} GaugeWithCallback can
     * read live membership from the StateBackend at scrape time without Metrics
     * depending on the state package.
     *
     * @param supplier returns the current number of cluster members
     */
    public static void setClusterMemberCountSupplier(Supplier<Integer> supplier) {
        clusterMemberCountSupplier.set(supplier);
    }

    /**
     * Current cluster member count, backing the {@code mock_server_cluster_members}
     * gauge. Returns 1 (single local node) when no supplier is registered or the
     * supplier fails/returns a non-positive value.
     */
    public static int getClusterMemberCount() {
        Supplier<Integer> supplier = clusterMemberCountSupplier.get();
        if (supplier != null) {
            try {
                Integer count = supplier.get();
                if (count != null && count > 0) {
                    return count;
                }
            } catch (Exception ignored) {
                // fail-soft: fall through to the single-node default
            }
        }
        return 1;
    }

    /**
     * Immutable snapshot of the event-log internals read at scrape time, spanning BOTH retention sites
     * (the in-flight ring and the retained deque). Groups the eight figures behind one supplier call so
     * a caller that wants a coherent picture can take them together. All values default to 0 (no event
     * log registered / before startup).
     * <p>
     * NOT atomic across a scrape. Each of the eight gauge callbacks invokes the supplier separately, so
     * one Prometheus response can mix values read microseconds apart; every individual field is a
     * volatile/atomic read, but occupancy and retainedEntries in the same scrape are not guaranteed to
     * describe the same instant. That is fine for trend attribution (which site is growing) and wrong
     * for arithmetic that assumes the eight are a consistent snapshot.
     * <p>
     * There is deliberately ONE constructor, taking every field. A convenience overload covering only
     * the ring would let a caller construct a snapshot whose retained figures read 0 — indistinguishable
     * from a genuinely empty deque, which is precisely the blind spot these fields were added to close.
     * A new retention site must therefore break compilation here rather than report itself as absent.
     */
    public static final class RingStats {
        // In-flight site: entries published to the disruptor ring but not yet processed.
        public final long occupancy;
        public final long capacity;
        public final long inFlightBytes;
        public final long maxInFlightBytes;
        // Retained site: entries kept in the deque after processing — the second, separately-bounded
        // event-log retention site. Distinct from the in-flight figures above.
        public final long retainedEntries;
        public final long retainedBytes;
        public final long maxRetainedBytes;
        public final long maxRetainedEntries;

        public RingStats(long occupancy, long capacity, long inFlightBytes, long maxInFlightBytes,
                         long retainedEntries, long retainedBytes, long maxRetainedBytes, long maxRetainedEntries) {
            this.occupancy = occupancy;
            this.capacity = capacity;
            this.inFlightBytes = inFlightBytes;
            this.maxInFlightBytes = maxInFlightBytes;
            this.retainedEntries = retainedEntries;
            this.retainedBytes = retainedBytes;
            this.maxRetainedBytes = maxRetainedBytes;
            this.maxRetainedEntries = maxRetainedEntries;
        }
    }

    private static final RingStats EMPTY_RING_STATS = new RingStats(0, 0, 0, 0, 0, 0, 0, 0);

    /**
     * Set the supplier of event-log live internals. Called by HttpState at startup so the
     * {@code mock_server_event_log_*} gauge family can read live state at scrape time from
     * {@link org.mockserver.log.MockServerEventLog} without Metrics depending on the log instance
     * lifecycle.
     * <p>
     * The family spans the event log's TWO distinct retention sites, so a scrape can tell which one is
     * holding the heap:
     * <ul>
     *   <li><strong>In-flight site (ring)</strong> — entries published to the disruptor ring but not
     *   yet processed: {@code mock_server_event_log_ring_occupancy},
     *   {@code mock_server_event_log_ring_capacity}, {@code mock_server_event_log_in_flight_bytes},
     *   {@code mock_server_event_log_max_in_flight_bytes}.</li>
     *   <li><strong>Retained site (deque)</strong> — entries kept after processing:
     *   {@code mock_server_event_log_retained_entries}, {@code mock_server_event_log_retained_bytes},
     *   {@code mock_server_event_log_max_retained_bytes},
     *   {@code mock_server_event_log_max_retained_entries}.</li>
     * </ul>
     */
    public static void setEventLogRingStatsSupplier(Supplier<RingStats> supplier) {
        eventLogRingStatsSupplier.set(supplier);
    }

    /**
     * Live event-log ring-buffer internals, backing the {@code mock_server_event_log_*} gauges. Returns
     * an all-zero snapshot when no supplier is registered or the supplier fails (fail-soft — a scrape
     * must never break).
     */
    public static RingStats getEventLogRingStats() {
        Supplier<RingStats> supplier = eventLogRingStatsSupplier.get();
        if (supplier != null) {
            try {
                RingStats stats = supplier.get();
                if (stats != null) {
                    return stats;
                }
            } catch (Exception ignored) {
                // fail-soft: fall through to the empty snapshot
            }
        }
        return EMPTY_RING_STATS;
    }

    /**
     * Immutable snapshot of the expectation store's byte figures, read at scrape time. Groups the three
     * figures behind one supplier call. All default to 0 (no store registered / before startup).
     */
    public static final class ExpectationStoreStats {
        public final long totalBytes;
        public final long maxBytes;
        public final long byteEvictedCount;

        public ExpectationStoreStats(long totalBytes, long maxBytes, long byteEvictedCount) {
            this.totalBytes = totalBytes;
            this.maxBytes = maxBytes;
            this.byteEvictedCount = byteEvictedCount;
        }
    }

    private static final ExpectationStoreStats EMPTY_EXPECTATION_STORE_STATS = new ExpectationStoreStats(0, 0, 0);

    /**
     * Set the supplier of expectation-store byte figures. Called by HttpState at startup so the
     * {@code mock_server_expectations_bytes} / {@code mock_server_max_expectations_bytes} /
     * {@code mock_server_expectations_byte_evicted} gauges can read live state at scrape time from
     * {@link org.mockserver.mock.RequestMatchers} without Metrics depending on the store lifecycle.
     */
    public static void setExpectationStoreStatsSupplier(Supplier<ExpectationStoreStats> supplier) {
        expectationStoreStatsSupplier.set(supplier);
    }

    /**
     * Live expectation-store byte figures, backing the expectation-bytes gauges. Returns an all-zero
     * snapshot when no supplier is registered or the supplier fails (fail-soft — a scrape must never
     * break).
     */
    public static ExpectationStoreStats getExpectationStoreStats() {
        Supplier<ExpectationStoreStats> supplier = expectationStoreStatsSupplier.get();
        if (supplier != null) {
            try {
                ExpectationStoreStats stats = supplier.get();
                if (stats != null) {
                    return stats;
                }
            } catch (Exception ignored) {
                // fail-soft: fall through to the empty snapshot
            }
        }
        return EMPTY_EXPECTATION_STORE_STATS;
    }

    /**
     * Read the kernel accept-queue ceiling from {@link #somaxconnPath} (Linux
     * {@code /proc/sys/net/core/somaxconn}). Returns empty when the file is absent, unreadable, or not
     * a non-negative integer — on which the effective-backlog gauge is not registered at all, rather
     * than emitting the configured value under an "effective" name that would misstate the real ceiling.
     */
    static OptionalLong readSomaxconn() {
        try {
            Path path = somaxconnPath;
            if (path != null && Files.isReadable(path)) {
                String content = new String(Files.readAllBytes(path)).trim();
                if (!content.isEmpty()) {
                    long value = Long.parseLong(content.split("\\s+")[0]);
                    if (value >= 0) {
                        return OptionalLong.of(value);
                    }
                }
            }
        } catch (Exception ignored) {
            // unreadable / non-numeric / non-Linux — effective gauge is omitted
        }
        return OptionalLong.empty();
    }

    /**
     * Number of upstreams whose forward/proxy circuit breaker is currently open, backing the
     * {@code mock_server_upstream_circuit_open} gauge. Reads the live {@link ForwardCircuitBreaker}
     * registry at scrape time; returns 0 when the breaker is disabled or no upstream is open.
     */
    public static int getOpenUpstreamCircuitCount() {
        try {
            return ForwardCircuitBreaker.getInstance().openCircuitCount();
        } catch (Exception ignored) {
            return 0;
        }
    }

    // ----------------------------------------------------------------------------------------
    // LLM optimisation report gauges.
    // ----------------------------------------------------------------------------------------

    /** Immutable headline figures from the most-recently-built LLM optimisation report. */
    private static final class LlmOptimisationSnapshot {
        final double estimatedWasteUsd;
        final double cacheHitRatio;
        final double oneShotRate;

        LlmOptimisationSnapshot(double estimatedWasteUsd, double cacheHitRatio, double oneShotRate) {
            this.estimatedWasteUsd = estimatedWasteUsd;
            this.cacheHitRatio = cacheHitRatio;
            this.oneShotRate = oneShotRate;
        }
    }

    /**
     * Record the headline figures from a freshly-built LLM optimisation report so the optimisation
     * gauges ({@code mock_server_llm_estimated_waste_usd}, {@code mock_server_llm_cache_hit_ratio},
     * {@code mock_server_llm_one_shot_rate}) report scrape-time-correct values without rebuilding the
     * report on each scrape. Called by {@code LlmOptimisationReportService.build(...)} on every build
     * (REST endpoint, MCP tool). Always safe to call — independent of whether metrics are enabled.
     *
     * @param estimatedWasteUsd the verdict's total estimated recoverable spend in USD
     * @param cacheHitRatio     the totals' cache-hit ratio (0..1)
     * @param oneShotRate       the totals' one-shot rate (0..1)
     */
    public static void updateLlmOptimisationSnapshot(double estimatedWasteUsd, double cacheHitRatio, double oneShotRate) {
        llmOptimisationSnapshot.set(new LlmOptimisationSnapshot(estimatedWasteUsd, cacheHitRatio, oneShotRate));
    }

    /** Estimated recoverable LLM spend (USD) from the latest report, or 0 if none built. */
    public static double getLlmEstimatedWasteUsd() {
        LlmOptimisationSnapshot snapshot = llmOptimisationSnapshot.get();
        return snapshot != null ? snapshot.estimatedWasteUsd : 0.0;
    }

    /** Cache-hit ratio (0..1) from the latest report, or 0 if none built. */
    public static double getLlmCacheHitRatio() {
        LlmOptimisationSnapshot snapshot = llmOptimisationSnapshot.get();
        return snapshot != null ? snapshot.cacheHitRatio : 0.0;
    }

    /** One-shot rate (0..1) from the latest report, or 0 if none built. */
    public static double getLlmOneShotRate() {
        LlmOptimisationSnapshot snapshot = llmOptimisationSnapshot.get();
        return snapshot != null ? snapshot.oneShotRate : 0.0;
    }

    // ----------------------------------------------------------------------------------------
    // Load-injection (load scenario) metric family.
    // ----------------------------------------------------------------------------------------

    /** Composite key (scenario + run_id) for the load gauge readers. */
    public static final class LoadGaugeKey {
        public final String scenario;
        public final String runId;

        public LoadGaugeKey(String scenario, String runId) {
            this.scenario = scenario != null ? scenario : "unknown";
            this.runId = runId != null ? runId : "unknown";
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof LoadGaugeKey)) {
                return false;
            }
            LoadGaugeKey that = (LoadGaugeKey) o;
            return scenario.equals(that.scenario) && runId.equals(that.runId);
        }

        @Override
        public int hashCode() {
            return 31 * scenario.hashCode() + runId.hashCode();
        }
    }

    /** True if the load metric family is registered (i.e. metrics are enabled). */
    public static boolean isLoadMetricsActive() {
        return loadRequestsTotal != null;
    }

    /**
     * Evict every durable {@code mock_server_load_*} series whose {@code run_id} label equals the
     * given run id, bounding accumulation of completed-run series. Each load run uses a fresh UUID
     * {@code run_id} label; without eviction the Prometheus client retains those datapoints in the
     * registry forever (memory growth, slower scrapes and {@code loadLatencyPercentileMillis}). The
     * orchestrator calls this for the <em>previous</em> run when a new run starts, so the most-recent
     * completed run stays scrapeable while accumulation is bounded to at most one completed run.
     * <p>
     * The Prometheus client 1.8.0 exposes {@code removeIf(Function<List<String>, Boolean>)} on stateful
     * metrics, keyed by label <em>values</em> in registration order. {@code run_id} is index 1 in every
     * load metric's label schema (the fixed structured labels begin {@code scenario, run_id, …} and the
     * scalar counters are {@code scenario, run_id, …}). The gauges are deliberately untouched — they
     * self-clear via the orchestrator's empty-callback readers. No-op when metrics are disabled (the
     * metrics are null) or {@code runId} is null.
     */
    public static void evictLoadRun(String runId) {
        if (runId == null) {
            return;
        }
        java.util.function.Function<java.util.List<String>, Boolean> matchesRun =
            labelValues -> labelValues.size() > 1 && runId.equals(labelValues.get(1));
        evictLoadSeries(loadRequestDurationSeconds, matchesRun);
        evictLoadSeries(loadRequestsTotal, matchesRun);
        evictLoadSeries(loadRequestBytesTotal, matchesRun);
        evictLoadSeries(loadResponseBytesTotal, matchesRun);
        evictLoadSeries(loadIterationsTotal, matchesRun);
        evictLoadSeries(loadThrottledTotal, matchesRun);
        evictLoadSeries(loadErrorsTotal, matchesRun);
        evictLoadSeries(loadChecksTotal, matchesRun);
    }

    private static void evictLoadSeries(Histogram metric, java.util.function.Function<java.util.List<String>, Boolean> matches) {
        if (metric != null) {
            try {
                metric.removeIf(matches);
            } catch (Exception ignored) {
                // fail-soft: eviction is best-effort and must never break a load run
            }
        }
    }

    private static void evictLoadSeries(Counter metric, java.util.function.Function<java.util.List<String>, Boolean> matches) {
        if (metric != null) {
            try {
                metric.removeIf(matches);
            } catch (Exception ignored) {
                // fail-soft: eviction is best-effort and must never break a load run
            }
        }
    }

    /**
     * Return the allowlisted custom Prometheus label names captured at registration. The
     * orchestrator builds the per-request label-value array in this exact order. Visible for the
     * orchestrator (same package) and tests.
     */
    static String[] loadCustomLabelNames() {
        return loadCustomLabelNames;
    }

    /**
     * Build the fixed structured Prometheus label values, then append the allowlisted custom label
     * values (looked up from {@code customLabels} by the registered allowlist names; missing keys
     * become {@code ""}). The returned array length always matches the registered label-name set.
     */
    private static String[] loadLabelValues(String scenario, String runId, String step, String route,
                                            String method, String statusClass, Map<String, String> customLabels) {
        String[] custom = loadCustomLabelNames;
        String[] values = new String[LOAD_FIXED_LABELS.length + custom.length];
        values[0] = nonNull(scenario, "unknown");
        values[1] = nonNull(runId, "unknown");
        values[2] = nonNull(step, "unknown");
        values[3] = nonNull(route, "/");
        values[4] = nonNull(method, "unknown");
        values[5] = nonNull(statusClass, "unknown");
        for (int i = 0; i < custom.length; i++) {
            String v = customLabels != null ? customLabels.get(custom[i]) : null;
            values[LOAD_FIXED_LABELS.length + i] = v != null ? v : "";
        }
        return values;
    }

    private static String nonNull(String value, String fallback) {
        return value != null && !value.isEmpty() ? value : fallback;
    }

    /**
     * Record one completed load-scenario request: its duration in the histogram (with an optional
     * trace_id exemplar), the request/response body byte counts, and a request count — all labeled
     * by the fixed structured labels plus the allowlisted custom labels. The full custom-label map
     * is mirrored to OTLP as attributes (arbitrary keys). No-op when load metrics are off.
     *
     * @param scenario        the scenario name
     * @param runId           the stable per-run id
     * @param step            the step label (step name or index)
     * @param route           the low-cardinality templatised route label
     * @param method          the HTTP method
     * @param statusCode      the upstream response status code, or null
     * @param latencySeconds  the request latency in seconds (negative clamped to 0)
     * @param requestBytes    request body byte count
     * @param responseBytes   response body byte count
     * @param traceId         the request's trace id for the histogram exemplar, or null
     * @param customLabels    merged scenario+step custom labels (may be null/empty)
     */
    public static void observeLoadRequest(String scenario, String runId, String step, String route,
                                          String method, Integer statusCode, double latencySeconds,
                                          long requestBytes, long responseBytes, String traceId,
                                          Map<String, String> customLabels) {
        String statusClass = statusClass(statusCode);
        double latency = latencySeconds > 0 ? latencySeconds : 0;
        // Compute the per-request structured+custom label values once and reuse for all four
        // Prometheus metrics below (the label values are identical across them). The array is never
        // mutated after creation, so sharing the single instance across the labelValues(...) calls
        // is safe.
        String[] values = loadLabelValues(scenario, runId, step, route, method, statusClass, customLabels);
        Histogram histogram = loadRequestDurationSeconds;
        if (histogram != null) {
            if (traceId != null && !traceId.isEmpty()) {
                try {
                    histogram.labelValues(values).observeWithExemplar(latency,
                        io.prometheus.metrics.model.snapshots.Labels.of("trace_id", traceId));
                } catch (Exception e) {
                    histogram.labelValues(values).observe(latency);
                }
            } else {
                histogram.labelValues(values).observe(latency);
            }
        }
        Counter requests = loadRequestsTotal;
        if (requests != null) {
            requests.labelValues(values).inc();
        }
        Counter reqBytes = loadRequestBytesTotal;
        if (reqBytes != null && requestBytes > 0) {
            reqBytes.labelValues(values).inc(requestBytes);
        }
        Counter respBytes = loadResponseBytesTotal;
        if (respBytes != null && responseBytes > 0) {
            respBytes.labelValues(values).inc(responseBytes);
        }
        // OTLP mirror: structured labels + all custom labels as attributes (arbitrary keys).
        io.opentelemetry.api.common.Attributes otelAttrs =
            loadOtelAttributes(scenario, runId, step, route, method, statusClass, customLabels);
        io.opentelemetry.api.metrics.DoubleHistogram otelHist = otelLoadRequestDuration;
        if (otelHist != null) {
            otelHist.record(latency, otelAttrs);
        }
        io.opentelemetry.api.metrics.LongCounter otelReq = otelLoadRequests;
        if (otelReq != null) {
            otelReq.add(1, otelAttrs);
        }
        io.opentelemetry.api.metrics.LongCounter otelReqBytes = otelLoadRequestBytes;
        if (otelReqBytes != null && requestBytes > 0) {
            otelReqBytes.add(requestBytes, otelAttrs);
        }
        io.opentelemetry.api.metrics.LongCounter otelRespBytes = otelLoadResponseBytes;
        if (otelRespBytes != null && responseBytes > 0) {
            otelRespBytes.add(responseBytes, otelAttrs);
        }
    }

    private static io.opentelemetry.api.common.Attributes loadOtelAttributes(
        String scenario, String runId, String step, String route, String method, String statusClass,
        Map<String, String> customLabels) {
        io.opentelemetry.api.common.AttributesBuilder builder = io.opentelemetry.api.common.Attributes.builder()
            .put("scenario", nonNull(scenario, "unknown"))
            .put("run_id", nonNull(runId, "unknown"))
            .put("step", nonNull(step, "unknown"))
            .put("route", nonNull(route, "/"))
            .put("method", nonNull(method, "unknown"))
            .put("status_class", nonNull(statusClass, "unknown"));
        if (customLabels != null) {
            customLabels.forEach((k, v) -> {
                if (k != null && v != null) {
                    builder.put(k, v);
                }
            });
        }
        return builder.build();
    }

    /** Increment the completed-iteration counter (labels scenario, run_id). No-op when off. */
    public static void incrementLoadIteration(String scenario, String runId) {
        Counter counter = loadIterationsTotal;
        if (counter != null) {
            counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown")).inc();
        }
        io.opentelemetry.api.metrics.LongCounter otel = otelLoadIterations;
        if (otel != null) {
            otel.add(1, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("scenario"), nonNull(scenario, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("run_id"), nonNull(runId, "unknown")));
        }
    }

    /**
     * Increment the throttled-dispatch counter for the given reason.
     *
     * @param reason one of "inflight_cap" or "rate_limit"
     */
    public static void incrementLoadThrottled(String scenario, String runId, String reason) {
        Counter counter = loadThrottledTotal;
        if (counter != null) {
            counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown"), nonNull(reason, "unknown")).inc();
        }
        io.opentelemetry.api.metrics.LongCounter otel = otelLoadThrottled;
        if (otel != null) {
            otel.add(1, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("scenario"), nonNull(scenario, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("run_id"), nonNull(runId, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("reason"), nonNull(reason, "unknown")));
        }
    }

    /**
     * Increment the error counter for the given kind.
     *
     * @param kind one of "timeout", "connection", "render", "http_5xx", "null_response"
     */
    public static void incrementLoadError(String scenario, String runId, String kind) {
        Counter counter = loadErrorsTotal;
        if (counter != null) {
            counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown"), nonNull(kind, "unknown")).inc();
        }
        io.opentelemetry.api.metrics.LongCounter otel = otelLoadErrors;
        if (otel != null) {
            otel.add(1, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("scenario"), nonNull(scenario, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("run_id"), nonNull(runId, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("kind"), nonNull(kind, "unknown")));
        }
    }

    /**
     * Increment the per-step response-check counter for the given outcome.
     *
     * @param step    the step label (step name or index)
     * @param outcome one of "pass" or "fail"
     */
    public static void incrementLoadCheck(String scenario, String runId, String step, String outcome) {
        Counter counter = loadChecksTotal;
        if (counter != null) {
            counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown"),
                nonNull(step, "unknown"), nonNull(outcome, "unknown")).inc();
        }
        io.opentelemetry.api.metrics.LongCounter otel = otelLoadChecks;
        if (otel != null) {
            otel.add(1, io.opentelemetry.api.common.Attributes.of(
                io.opentelemetry.api.common.AttributeKey.stringKey("scenario"), nonNull(scenario, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("run_id"), nonNull(runId, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("step"), nonNull(step, "unknown"),
                io.opentelemetry.api.common.AttributeKey.stringKey("outcome"), nonNull(outcome, "unknown")));
        }
    }

    /**
     * Register the live readers for the active-VU and in-flight load gauges. Called by the
     * orchestrator on start (and cleared with null on stop), so the gauges read the running scenario
     * at scrape time. Mirrors the chaos GaugeWithCallback pattern.
     */
    public static void setLoadGaugeReaders(Supplier<Map<LoadGaugeKey, Integer>> activeVusReader,
                                           Supplier<Map<LoadGaugeKey, Integer>> inflightReader) {
        loadActiveVusReader.set(activeVusReader);
        loadInflightReader.set(inflightReader);
    }

    /**
     * Install the OTel load instruments. Called by {@link OtelMetricsExporter} when OTLP export is
     * enabled; passing null on stop clears them so the load path stops mirroring to OTLP.
     */
    public static void registerOtelLoadInstruments(io.opentelemetry.api.metrics.DoubleHistogram duration,
                                                   io.opentelemetry.api.metrics.LongCounter requests,
                                                   io.opentelemetry.api.metrics.LongCounter requestBytes,
                                                   io.opentelemetry.api.metrics.LongCounter responseBytes,
                                                   io.opentelemetry.api.metrics.LongCounter iterations,
                                                   io.opentelemetry.api.metrics.LongCounter throttled,
                                                   io.opentelemetry.api.metrics.LongCounter errors,
                                                   io.opentelemetry.api.metrics.LongCounter checks) {
        otelLoadRequestDuration = duration;
        otelLoadRequests = requests;
        otelLoadRequestBytes = requestBytes;
        otelLoadResponseBytes = responseBytes;
        otelLoadIterations = iterations;
        otelLoadThrottled = throttled;
        otelLoadErrors = errors;
        otelLoadChecks = checks;
    }

    /** Live readers for the OTEL load observable gauges (active VUs / in-flight). */
    public static Map<LoadGaugeKey, Integer> getLoadActiveVus() {
        Supplier<Map<LoadGaugeKey, Integer>> reader = loadActiveVusReader.get();
        return reader != null ? reader.get() : java.util.Collections.emptyMap();
    }

    public static Map<LoadGaugeKey, Integer> getLoadInflightRequests() {
        Supplier<Map<LoadGaugeKey, Integer>> reader = loadInflightReader.get();
        return reader != null ? reader.get() : java.util.Collections.emptyMap();
    }

    /** Read a per-request load counter value for a fixed-label combination (custom labels empty). Test helper. */
    public static long getLoadRequestCount(String scenario, String runId, String step, String route, String method, String statusClass) {
        Counter counter = loadRequestsTotal;
        if (counter == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(loadLabelValues(scenario, runId, step, route, method, statusClass, null)).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static long getLoadThrottledCount(String scenario, String runId, String reason) {
        Counter counter = loadThrottledTotal;
        if (counter == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown"), nonNull(reason, "unknown")).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static long getLoadErrorCount(String scenario, String runId, String kind) {
        Counter counter = loadErrorsTotal;
        if (counter == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown"), nonNull(kind, "unknown")).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static long getLoadCheckCount(String scenario, String runId, String step, String outcome) {
        Counter counter = loadChecksTotal;
        if (counter == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown"),
                nonNull(step, "unknown"), nonNull(outcome, "unknown")).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    public static long getLoadIterationCount(String scenario, String runId) {
        Counter counter = loadIterationsTotal;
        if (counter == null) {
            return 0L;
        }
        try {
            return (long) counter.labelValues(nonNull(scenario, "unknown"), nonNull(runId, "unknown")).get();
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Derive a latency percentile (millis) for the given fixed-label combination from the load
     * histogram's classic buckets — bounded memory, no reservoir. Returns the upper bound of the
     * bucket in which the requested rank falls (the standard histogram-quantile interpolation point
     * for classic buckets), or 0 when there are no observations. Any percentile is also directly
     * queryable from the histogram in Prometheus via {@code histogram_quantile}.
     *
     * @param pct percentile in 0..100
     */
    public static long loadLatencyPercentileMillis(String scenario, String runId, int pct) {
        Histogram histogram = loadRequestDurationSeconds;
        if (histogram == null) {
            return 0L;
        }
        try {
            io.prometheus.metrics.model.snapshots.HistogramSnapshot snapshot =
                (io.prometheus.metrics.model.snapshots.HistogramSnapshot) histogram.collect();
            // Aggregate buckets across every label combination matching this scenario+run.
            java.util.TreeMap<Double, Long> cumulative = new java.util.TreeMap<>();
            long total = 0;
            String scenarioLabel = nonNull(scenario, "unknown");
            String runLabel = nonNull(runId, "unknown");
            for (io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot dp : snapshot.getDataPoints()) {
                if (!scenarioLabel.equals(dp.getLabels().get("scenario")) || !runLabel.equals(dp.getLabels().get("run_id"))) {
                    continue;
                }
                for (io.prometheus.metrics.model.snapshots.ClassicHistogramBucket bucket : dp.getClassicBuckets()) {
                    // ClassicHistogramBucket counts are per-bucket (de-cumulated) in this snapshot model.
                    long bucketCount = bucket.getCount();
                    cumulative.merge(bucket.getUpperBound(), bucketCount, Long::sum);
                    total += bucketCount;
                }
            }
            if (total == 0) {
                return 0L;
            }
            long rank = (long) Math.ceil((pct / 100.0) * total);
            long running = 0;
            for (Map.Entry<Double, Long> entry : cumulative.entrySet()) {
                running += entry.getValue();
                if (running >= rank) {
                    double upperBound = entry.getKey();
                    if (Double.isInfinite(upperBound)) {
                        // +Inf bucket: fall back to the largest finite bucket bound.
                        Double lower = cumulative.lowerKey(Double.POSITIVE_INFINITY);
                        upperBound = lower != null ? lower : 0;
                    }
                    return (long) (upperBound * 1000.0);
                }
            }
            return 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    public void increment(Name name) {
        if (metricsEnabled) {
            getOrCreate(name).inc();
            // Mirror the increment onto the dual-published _total counter for the five monotonic
            // counts (no-op map lookup for every other Name), so the legacy gauge and the proper
            // counter move together from the same call site.
            Counter counter = monotonicTotalCounters.get(name);
            if (counter != null) {
                counter.inc();
            }
        }
    }

    public void increment(Action.Type type) {
        if (metricsEnabled) {
            increment(Name.valueOf(type.name() + "_ACTIONS_COUNT"));
        }
    }

    public void decrement(Name name) {
        if (metricsEnabled) {
            getOrCreate(name).dec();
        }
    }

    public void decrement(Action.Type type) {
        if (metricsEnabled) {
            decrement(Name.valueOf(type.name() + "_ACTIONS_COUNT"));
        }
    }

    public static void clearRequestAndExpectationMetrics() {
        clear(Name.REQUESTS_RECEIVED_COUNT);
        clear(Name.EXPECTATIONS_NOT_MATCHED_COUNT);
        clear(Name.RESPONSE_EXPECTATIONS_MATCHED_COUNT);
    }

    public static void clearActionMetrics() {
        clear(Name.FORWARD_ACTIONS_COUNT);
        clear(Name.FORWARD_TEMPLATE_ACTIONS_COUNT);
        clear(Name.FORWARD_CLASS_CALLBACK_ACTIONS_COUNT);
        clear(Name.FORWARD_OBJECT_CALLBACK_ACTIONS_COUNT);
        clear(Name.FORWARD_REPLACE_ACTIONS_COUNT);
        clear(Name.FORWARD_VALIDATE_ACTIONS_COUNT);
        clear(Name.FORWARD_WITH_FALLBACK_ACTIONS_COUNT);
        clear(Name.RESPONSE_ACTIONS_COUNT);
        clear(Name.RESPONSE_TEMPLATE_ACTIONS_COUNT);
        clear(Name.RESPONSE_CLASS_CALLBACK_ACTIONS_COUNT);
        clear(Name.RESPONSE_OBJECT_CALLBACK_ACTIONS_COUNT);
        clear(Name.SSE_RESPONSE_ACTIONS_COUNT);
        clear(Name.LLM_RESPONSE_ACTIONS_COUNT);
        clear(Name.LLM_CHAOS_INJECTED_COUNT);
        clear(Name.WEBSOCKET_RESPONSE_ACTIONS_COUNT);
        clear(Name.GRPC_STREAM_RESPONSE_ACTIONS_COUNT);
        clear(Name.GRPC_BIDI_RESPONSE_ACTIONS_COUNT);
        clear(Name.BINARY_RESPONSE_ACTIONS_COUNT);
        clear(Name.DNS_RESPONSE_ACTIONS_COUNT);
        clear(Name.ERROR_ACTIONS_COUNT);
    }

    public static void clearWebSocketMetrics() {
        clear(Name.WEBSOCKET_CALLBACK_CLIENTS_COUNT);
        clear(Name.WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT);
        clear(Name.WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT);
    }

    public enum Name {
        REQUESTS_RECEIVED_COUNT("Total requests received count"),
        EXPECTATIONS_NOT_MATCHED_COUNT("Expectation not matched count"),
        RESPONSE_EXPECTATIONS_MATCHED_COUNT("Response expectation matched count"),
        FORWARD_EXPECTATIONS_MATCHED_COUNT("Forward expectation matched count"),
        FORWARD_ACTIONS_COUNT("Action forward count"),
        FORWARD_TEMPLATE_ACTIONS_COUNT("Action forward template count"),
        FORWARD_CLASS_CALLBACK_ACTIONS_COUNT("Action forward class callback count"),
        FORWARD_OBJECT_CALLBACK_ACTIONS_COUNT("Action forward object callback count"),
        FORWARD_REPLACE_ACTIONS_COUNT("Action forward replace count"),
        FORWARD_VALIDATE_ACTIONS_COUNT("Action forward validate count"),
        FORWARD_WITH_FALLBACK_ACTIONS_COUNT("Action forward with fallback count"),
        RESPONSE_ACTIONS_COUNT("Action response count"),
        RESPONSE_TEMPLATE_ACTIONS_COUNT("Action response template count"),
        RESPONSE_CLASS_CALLBACK_ACTIONS_COUNT("Action response class callback count"),
        RESPONSE_OBJECT_CALLBACK_ACTIONS_COUNT("Action response object callback count"),
        SSE_RESPONSE_ACTIONS_COUNT("Action SSE response count"),
        LLM_RESPONSE_ACTIONS_COUNT("Action LLM response count"),
        LLM_CHAOS_INJECTED_COUNT("Action LLM chaos injected count"),
        WEBSOCKET_RESPONSE_ACTIONS_COUNT("Action WebSocket response count"),
        GRPC_STREAM_RESPONSE_ACTIONS_COUNT("Action gRPC stream response count"),
        GRPC_BIDI_RESPONSE_ACTIONS_COUNT("Action gRPC bidi response count"),
        BINARY_RESPONSE_ACTIONS_COUNT("Action binary response count"),
        DNS_RESPONSE_ACTIONS_COUNT("Action DNS response count"),
        ERROR_ACTIONS_COUNT("Action error count"),
        WEBSOCKET_CALLBACK_CLIENTS_COUNT("Websocket callback client count"),
        WEBSOCKET_CALLBACK_RESPONSE_HANDLERS_COUNT("Websocket callback response handler count"),
        WEBSOCKET_CALLBACK_FORWARD_HANDLERS_COUNT("Websocket callback forward handler count");

        public final String description;

        Name(String description) {
            this.description = description;
        }
    }
}

package org.mockserver.metrics;

import io.prometheus.metrics.model.registry.PrometheusRegistry;
import io.prometheus.metrics.model.snapshots.CounterSnapshot;
import io.prometheus.metrics.model.snapshots.GaugeSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshot;
import io.prometheus.metrics.model.snapshots.MetricSnapshots;
import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.action.http.ServiceChaosRegistry;
import org.mockserver.model.HttpChaosProfile;
import org.mockserver.model.HttpError;
import org.mockserver.model.HttpResponse;

import java.util.Arrays;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpChaosProfile.httpChaosProfile;
import static org.mockserver.model.HttpRequest.request;

public class MetricsTest {

    // Serialise against OtelMetricsExporterTest and the other classes that mutate the process-global
    // PrometheusRegistry / static Metrics state. Under Surefire parallel=classes (the default-test
    // phase a developer hits when running -Dtest=MetricsTest,OtelMetricsExporterTest), two such
    // classes would otherwise run concurrently and race on registry clear()/re-registration,
    // producing order-dependent "metric absent / reads 0" flakes. This class already runs in the
    // sequential CI phase; the class-rule lock additionally makes ad-hoc parallel runs safe.
    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    @Before
    public void resetStaticState() {
        // Reset the process-static one-shot guard and clear the default
        // registry so each test starts with a clean slate — prevents
        // order-dependent failures caused by the CAS guard in Metrics.
        Metrics.resetAdditionalMetricsForTesting();
    }

    @After
    public void clearServiceChaos() {
        ServiceChaosRegistry.getInstance().reset();
        Metrics.setActiveExpectationsSupplier(null);
        Metrics.setClusterMemberCountSupplier(null);
        Metrics.setEventLogRingStatsSupplier(null);
        Metrics.setExpectationStoreStatsSupplier(null);
        Metrics.somaxconnPath = Metrics.DEFAULT_SOMAXCONN_PATH;
        // Drop any optimisation snapshot pushed by a test so the next test starts clean.
        Metrics.clear();
    }

    @Test
    public void registersAndRecordsRequestDurationHistogram() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeRequestDurationSeconds(0.05);

        assertThat(scrapeContains("mock_server_request_duration_seconds"), is(true));
    }

    @Test
    public void observeRequestDurationDoesNotThrow() {
        // safe to call regardless of registration state (no-op when absent)
        Metrics.observeRequestDurationSeconds(0.01);
    }

    @Test
    public void registersSlowRequestCounter() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementSlowRequestTotal();

        assertThat(scrapeContains("mock_server_slow_requests"), is(true));
    }

    @Test
    public void incrementSlowRequestTotalDoesNotThrowWhenDisabled() {
        // safe to call when counter not registered (no-op)
        Metrics.incrementSlowRequestTotal();
    }

    @Test
    public void registersMethodLabeledHistogramWhenEnabled() {
        new Metrics(configuration().metricsEnabled(true).metricsRequestDurationRouteLabels(true));
        Metrics.observeRequestDurationByMethodSeconds(0.05, "GET");

        assertThat(scrapeContains("mock_server_request_duration_by_method_seconds"), is(true));
    }

    @Test
    public void observeRequestDurationByMethodDoesNotThrowWhenDisabled() {
        // safe to call when labeled histogram not registered (no-op)
        Metrics.observeRequestDurationByMethodSeconds(0.01, "POST");
    }

    @Test
    public void registersHttpChaosInjectedCounter() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementHttpChaosInjected("error");

        assertThat(scrapeContains("mock_server_http_chaos_injected"), is(true));
    }

    @Test
    public void httpChaosInjectedCounterIncrementsPerFaultType() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementHttpChaosInjected("error");
        Metrics.incrementHttpChaosInjected("error");
        Metrics.incrementHttpChaosInjected("latency");

        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "error"), is(2.0));
        assertThat(scrapeCounterValue("mock_server_http_chaos_injected", "fault_type", "latency"), is(1.0));
    }

    @Test
    public void incrementHttpChaosInjectedDoesNotThrowWhenDisabled() {
        // safe to call when counter not registered (no-op)
        Metrics.incrementHttpChaosInjected("error");
    }

    @Test
    public void incrementHttpChaosInjectedDoesNotThrowWhenFaultTypeIsNull() {
        new Metrics(configuration().metricsEnabled(true));
        // should not throw
        Metrics.incrementHttpChaosInjected(null);
    }

    @Test
    public void registersActiveServiceChaosGauge() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_active_service_chaos"), is(true));
    }

    @Test
    public void activeServiceChaosGaugeReflectsLiveRegistryByFaultType() {
        new Metrics(configuration().metricsEnabled(true));
        String metric = "mock_server_active_service_chaos";

        assertThat("no chaos registered", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(0.0));

        // a.svc injects error + drop; b.svc injects error only
        ServiceChaosRegistry.getInstance().put("a.svc", httpChaosProfile().withErrorStatus(503).withDropConnectionProbability(0.5));
        ServiceChaosRegistry.getInstance().put("b.svc", httpChaosProfile().withErrorStatus(500));
        assertThat("two profiles inject error", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(2.0));
        assertThat("one profile injects drop", scrapeGaugeValueByLabel(metric, "fault_type", "drop"), is(1.0));
        assertThat("no profile injects latency", scrapeGaugeValueByLabel(metric, "fault_type", "latency"), is(0.0));

        ServiceChaosRegistry.getInstance().remove("a.svc");
        assertThat("error follows removals", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(1.0));
        assertThat("drop follows removals", scrapeGaugeValueByLabel(metric, "fault_type", "drop"), is(0.0));

        ServiceChaosRegistry.getInstance().reset();
        assertThat("drops to zero when cleared", scrapeGaugeValueByLabel(metric, "fault_type", "error"), is(0.0));
    }

    @Test
    public void getActiveServiceChaosCountByFaultTypeDoesNotThrowWhenDisabled() {
        // safe to call regardless of whether metrics are enabled (reads the registry directly)
        assertThat(Metrics.getActiveServiceChaosCountByFaultType().get("error"), is(0));
    }

    // --- per-upstream forward observability tests ---

    @Test
    public void registersForwardObservabilityMetrics() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeForwardRequest("api.example.com", 200, 0.05);

        assertThat(scrapeContains("mock_server_forward_request_duration_seconds"), is(true));
        assertThat(scrapeContains("mock_server_forward_requests"), is(true));
    }

    @Test
    public void forwardRequestsCounterLabelsByHostAndStatusClass() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeForwardRequest("api.example.com", 200, 0.01);
        Metrics.observeForwardRequest("api.example.com", 204, 0.02);
        Metrics.observeForwardRequest("api.example.com", 502, 0.03);
        Metrics.observeForwardRequest("other.example.com", 200, 0.04);

        assertThat(scrapeForwardCount("api.example.com", "2xx"), is(2.0));
        assertThat(scrapeForwardCount("api.example.com", "5xx"), is(1.0));
        assertThat(scrapeForwardCount("other.example.com", "2xx"), is(1.0));
    }

    @Test
    public void forwardObservabilityRecordsLatencyHistogram() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeForwardRequest("api.example.com", 200, 0.05);

        // _count series of the histogram should reflect one observation
        assertThat(scrapeForwardDurationCount("api.example.com"), is(1.0));
    }

    @Test
    public void forwardUpstreamProtocolCounterLabelsByHostAndProtocol() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementForwardUpstreamProtocol("chatgpt.com", "http2");
        Metrics.incrementForwardUpstreamProtocol("chatgpt.com", "http2");
        Metrics.incrementForwardUpstreamProtocol("api.anthropic.com", "http1_1");

        assertThat(scrapeContains("mock_server_forward_upstream_protocol"), is(true));
        assertThat(Metrics.forwardUpstreamProtocolCount("chatgpt.com", "http2"), is(2L));
        assertThat(Metrics.forwardUpstreamProtocolCount("api.anthropic.com", "http1_1"), is(1L));
        assertThat(Metrics.forwardUpstreamProtocolCount("chatgpt.com", "http1_1"), is(0L));
    }

    @Test
    public void incrementForwardUpstreamProtocolDoesNotThrowWhenDisabled() {
        // no-op when metrics are not enabled
        Metrics.incrementForwardUpstreamProtocol("chatgpt.com", "http2");
        assertThat(Metrics.forwardUpstreamProtocolCount("chatgpt.com", "http2"), is(0L));
    }

    @Test
    public void getForwardRequestCountReturnsPerHostStatusClassCount() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeForwardRequest("api.example.com", 200, 0.01);
        Metrics.observeForwardRequest("api.example.com", 201, 0.01);

        assertThat(Metrics.getForwardRequestCount("api.example.com", "2xx"), is(2L));
        assertThat(Metrics.getForwardRequestCount("api.example.com", "5xx"), is(0L));
        assertThat(Metrics.getForwardRequestCount("nonexistent", "2xx"), is(0L));
    }

    @Test
    public void forwardObservabilityNullHostRecordedAsUnknown() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeForwardRequest(null, 200, 0.01);

        assertThat(Metrics.getForwardRequestCount("unknown", "2xx"), is(1L));
    }

    @Test
    public void forwardObservabilityNullStatusRecordedAsUnknownClass() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.observeForwardRequest("api.example.com", null, 0.01);

        assertThat(Metrics.getForwardRequestCount("api.example.com", "unknown"), is(1L));
    }

    @Test
    public void observeForwardRequestDoesNotThrowWhenDisabled() {
        // safe to call when metrics not registered (no-op)
        Metrics.observeForwardRequest("api.example.com", 200, 0.05);
        assertThat(Metrics.isForwardMetricsActive(), is(false));
    }

    private static double scrapeForwardCount(String host, String statusClass) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals("mock_server_forward_requests") && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dataPoint : counterSnapshot.getDataPoints()) {
                    if (host.equals(dataPoint.getLabels().get("upstream_host"))
                        && statusClass.equals(dataPoint.getLabels().get("status_class"))) {
                        return dataPoint.getValue();
                    }
                }
            }
        }
        return 0.0;
    }

    private static double scrapeForwardDurationCount(String host) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals("mock_server_forward_request_duration_seconds")
                && snapshot instanceof io.prometheus.metrics.model.snapshots.HistogramSnapshot histogramSnapshot) {
                for (io.prometheus.metrics.model.snapshots.HistogramSnapshot.HistogramDataPointSnapshot dataPoint : histogramSnapshot.getDataPoints()) {
                    if (host.equals(dataPoint.getLabels().get("upstream_host"))) {
                        return dataPoint.getCount();
                    }
                }
            }
        }
        return 0.0;
    }

    // --- MCP tool call counter tests ---

    @Test
    public void registersMcpToolCallsCounter() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementMcpToolCall("create_expectation");

        assertThat(scrapeContains("mock_server_mcp_tool_calls"), is(true));
    }

    @Test
    public void mcpToolCallsCounterIncrementsPerToolName() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementMcpToolCall("create_expectation");
        Metrics.incrementMcpToolCall("create_expectation");
        Metrics.incrementMcpToolCall("verify_request");
        Metrics.incrementMcpToolCall("list_mock_tools");
        Metrics.incrementMcpToolCall("list_mock_tools");
        Metrics.incrementMcpToolCall("list_mock_tools");

        assertThat(scrapeCounterValue("mock_server_mcp_tool_calls", "tool", "create_expectation"), is(2.0));
        assertThat(scrapeCounterValue("mock_server_mcp_tool_calls", "tool", "verify_request"), is(1.0));
        assertThat(scrapeCounterValue("mock_server_mcp_tool_calls", "tool", "list_mock_tools"), is(3.0));
    }

    @Test
    public void getMcpToolCallCountReturnsPerToolCount() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementMcpToolCall("reset");
        Metrics.incrementMcpToolCall("reset");

        assertThat(Metrics.getMcpToolCallCount("reset"), is(2L));
        assertThat(Metrics.getMcpToolCallCount("nonexistent"), is(0L));
    }

    @Test
    public void incrementMcpToolCallDoesNotThrowWhenDisabled() {
        // safe to call when counter not registered (no-op)
        Metrics.incrementMcpToolCall("create_expectation");
    }

    @Test
    public void incrementMcpToolCallDoesNotThrowWhenToolNameIsNull() {
        new Metrics(configuration().metricsEnabled(true));
        // should not throw
        Metrics.incrementMcpToolCall(null);
    }

    // --- async message counters tests ---

    @Test
    public void registersAsyncMessageCounters() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementAsyncMessagePublished("orders/placed");
        Metrics.incrementAsyncMessageConsumed("orders/placed");

        assertThat(scrapeContains("mock_server_async_messages_published"), is(true));
        assertThat(scrapeContains("mock_server_async_messages_consumed"), is(true));
    }

    @Test
    public void asyncMessageCountersIncrementPerChannel() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementAsyncMessagePublished("orders/placed");
        Metrics.incrementAsyncMessagePublished("orders/placed");
        Metrics.incrementAsyncMessagePublished("payments/processed");
        Metrics.incrementAsyncMessageConsumed("orders/placed");

        assertThat(scrapeCounterValue("mock_server_async_messages_published", "channel", "orders/placed"), is(2.0));
        assertThat(scrapeCounterValue("mock_server_async_messages_published", "channel", "payments/processed"), is(1.0));
        assertThat(scrapeCounterValue("mock_server_async_messages_consumed", "channel", "orders/placed"), is(1.0));
    }

    @Test
    public void getAsyncMessageCountsReturnPerChannelCount() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.incrementAsyncMessagePublished("orders/placed");
        Metrics.incrementAsyncMessagePublished("orders/placed");
        Metrics.incrementAsyncMessageConsumed("orders/placed");

        assertThat(Metrics.getAsyncMessagePublishedCount("orders/placed"), is(2L));
        assertThat(Metrics.getAsyncMessageConsumedCount("orders/placed"), is(1L));
        assertThat(Metrics.getAsyncMessagePublishedCount("nonexistent"), is(0L));
    }

    @Test
    public void incrementAsyncMessageDoesNotThrowWhenDisabled() {
        // safe to call when counters not registered (no-op)
        Metrics.incrementAsyncMessagePublished("orders/placed");
        Metrics.incrementAsyncMessageConsumed("orders/placed");
    }

    @Test
    public void incrementAsyncMessageDoesNotThrowWhenChannelIsNull() {
        new Metrics(configuration().metricsEnabled(true));
        // should not throw
        Metrics.incrementAsyncMessagePublished(null);
        Metrics.incrementAsyncMessageConsumed(null);
    }

    // --- expectations by type gauge tests ---

    @Test
    public void registersExpectationsByTypeGauge() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_expectations_by_type"), is(true));
    }

    @Test
    public void expectationsByTypeGaugeReflectsActiveExpectations() {
        new Metrics(configuration().metricsEnabled(true));
        String metric = "mock_server_expectations_by_type";

        // no supplier set yet -> empty
        assertThat("no supplier", scrapeGaugeValueByLabel(metric, "action_type", "RESPONSE"), is(0.0));

        // set supplier with a mix of action types
        Metrics.setActiveExpectationsSupplier(() -> Arrays.asList(
            when(request().withPath("/a")).thenRespond(HttpResponse.response().withStatusCode(200)),
            when(request().withPath("/b")).thenRespond(HttpResponse.response().withStatusCode(201)),
            when(request().withPath("/c")).thenError(HttpError.error().withDropConnection(true))
        ));

        assertThat("two RESPONSE expectations",
            scrapeGaugeValueByLabel(metric, "action_type", "RESPONSE"), is(2.0));
        assertThat("one ERROR expectation",
            scrapeGaugeValueByLabel(metric, "action_type", "ERROR"), is(1.0));
        assertThat("no FORWARD expectations",
            scrapeGaugeValueByLabel(metric, "action_type", "FORWARD"), is(0.0));

        // update the supplier (simulates adding/removing expectations)
        Metrics.setActiveExpectationsSupplier(() -> Arrays.asList(
            when(request().withPath("/a")).thenRespond(HttpResponse.response().withStatusCode(200))
        ));

        assertThat("follows updates: one RESPONSE",
            scrapeGaugeValueByLabel(metric, "action_type", "RESPONSE"), is(1.0));
        assertThat("follows updates: zero ERROR",
            scrapeGaugeValueByLabel(metric, "action_type", "ERROR"), is(0.0));
    }

    @Test
    public void expectationsByTypeGaugeHandlesNullAction() {
        new Metrics(configuration().metricsEnabled(true));

        // An expectation with no action set
        Metrics.setActiveExpectationsSupplier(() -> Arrays.asList(
            new Expectation(request().withPath("/no-action"))
        ));

        // should not throw; no action_type labels emitted
        Map<String, Integer> counts = Metrics.getActiveExpectationCountByType();
        assertThat("empty map for null-action expectations", counts.isEmpty(), is(true));
    }

    @Test
    public void getActiveExpectationCountByTypeDoesNotThrowWhenNoSupplier() {
        // safe to call regardless of whether supplier is set
        Map<String, Integer> counts = Metrics.getActiveExpectationCountByType();
        assertThat("empty map when no supplier", counts.isEmpty(), is(true));
    }

    // --- cluster members gauge tests ---

    @Test
    public void registersClusterMembersGauge() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_cluster_members"), is(true));
    }

    @Test
    public void clusterMembersGaugeDefaultsToOneWhenNoSupplier() {
        new Metrics(configuration().metricsEnabled(true));

        // single-node default: exactly one member when no supplier is registered
        assertThat(scrapeGaugeValue("mock_server_cluster_members"), is(1.0));
        assertThat(Metrics.getClusterMemberCount(), is(1));
    }

    @Test
    public void clusterMembersGaugeReflectsSupplierValue() {
        new Metrics(configuration().metricsEnabled(true));

        Metrics.setClusterMemberCountSupplier(() -> 3);
        assertThat(scrapeGaugeValue("mock_server_cluster_members"), is(3.0));

        // follows updates (e.g. a node leaves the cluster)
        Metrics.setClusterMemberCountSupplier(() -> 2);
        assertThat(scrapeGaugeValue("mock_server_cluster_members"), is(2.0));
    }

    @Test
    public void clusterMembersGaugeFailsSoftToOne() {
        new Metrics(configuration().metricsEnabled(true));

        // a throwing or non-positive supplier degrades to the single-node default
        Metrics.setClusterMemberCountSupplier(() -> {
            throw new RuntimeException("backend unavailable");
        });
        assertThat(scrapeGaugeValue("mock_server_cluster_members"), is(1.0));

        Metrics.setClusterMemberCountSupplier(() -> 0);
        assertThat(scrapeGaugeValue("mock_server_cluster_members"), is(1.0));
    }

    // --- event-log ring-buffer internals gauge tests ---

    @Test
    public void registersEventLogRingGauges() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_event_log_ring_occupancy"), is(true));
        assertThat(scrapeContains("mock_server_event_log_ring_capacity"), is(true));
        assertThat(scrapeContains("mock_server_event_log_in_flight_bytes"), is(true));
        assertThat(scrapeContains("mock_server_event_log_max_in_flight_bytes"), is(true));
        // the retained (post-processing) site of the same family
        assertThat(scrapeContains("mock_server_event_log_retained_entries"), is(true));
        assertThat(scrapeContains("mock_server_event_log_retained_bytes"), is(true));
        assertThat(scrapeContains("mock_server_event_log_max_retained_bytes"), is(true));
        assertThat(scrapeContains("mock_server_event_log_max_retained_entries"), is(true));
    }

    @Test
    public void eventLogRingGaugesDefaultToZeroWhenNoSupplier() {
        new Metrics(configuration().metricsEnabled(true));

        // no event log registered — every ring gauge reads a truthful 0, never a fabricated value
        assertThat(scrapeGaugeValue("mock_server_event_log_ring_occupancy"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_ring_capacity"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_in_flight_bytes"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_max_in_flight_bytes"), is(0.0));
        // retained site also reads a truthful 0 with no supplier
        assertThat(scrapeGaugeValue("mock_server_event_log_retained_entries"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_retained_bytes"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_max_retained_bytes"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_max_retained_entries"), is(0.0));
    }

    @Test
    public void eventLogRingGaugesReflectSupplierValue() {
        new Metrics(configuration().metricsEnabled(true));

        // every field is stated: there is no ring-only overload, so a retained figure can never be
        // omitted into a plausible-looking 0 (see RingStats' javadoc)
        Metrics.setEventLogRingStatsSupplier(() -> new Metrics.RingStats(
            1200, 16384, 268435456L, 268435456L,
            0, 0, 268435456L, 60000));
        assertThat(scrapeGaugeValue("mock_server_event_log_ring_occupancy"), is(1200.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_ring_capacity"), is(16384.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_in_flight_bytes"), is(268435456.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_max_in_flight_bytes"), is(268435456.0));

        // the backlog builds — a later scrape sees the higher occupancy, so a run can watch the cliff approach
        Metrics.setEventLogRingStatsSupplier(() -> new Metrics.RingStats(
            16000, 16384, 268000000L, 268435456L,
            0, 0, 268435456L, 60000));
        assertThat(scrapeGaugeValue("mock_server_event_log_ring_occupancy"), is(16000.0));
    }

    @Test
    public void eventLogRetainedGaugesReflectSupplierValue() {
        new Metrics(configuration().metricsEnabled(true));

        // the 8-arg RingStats carries the retained (deque) figures alongside the in-flight (ring) ones,
        // so a single scrape can attribute the heap to the right site
        Metrics.setEventLogRingStatsSupplier(() -> new Metrics.RingStats(
            0, 16384, 0, 268435456L,
            5000, 134217728L, 268435456L, 60000));
        assertThat(scrapeGaugeValue("mock_server_event_log_retained_entries"), is(5000.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_retained_bytes"), is(134217728.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_max_retained_bytes"), is(268435456.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_max_retained_entries"), is(60000.0));
        // ring stays empty while the retained deque holds the heap — the exact case the old gauges
        // could not tell apart from "the event log is empty"
        assertThat(scrapeGaugeValue("mock_server_event_log_ring_occupancy"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_in_flight_bytes"), is(0.0));

        // the retained deque grows on a later scrape — attribution tracks it climbing
        Metrics.setEventLogRingStatsSupplier(() -> new Metrics.RingStats(
            0, 16384, 0, 268435456L,
            9000, 260000000L, 268435456L, 60000));
        assertThat(scrapeGaugeValue("mock_server_event_log_retained_bytes"), is(260000000.0));
    }

    @Test
    public void eventLogRingGaugesFailSoftToZero() {
        new Metrics(configuration().metricsEnabled(true));

        // a throwing supplier must never break a scrape — it degrades to the all-zero snapshot
        Metrics.setEventLogRingStatsSupplier(() -> {
            throw new RuntimeException("event log unavailable");
        });
        assertThat(scrapeGaugeValue("mock_server_event_log_ring_occupancy"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_in_flight_bytes"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_retained_entries"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_event_log_retained_bytes"), is(0.0));
    }

    // --- Expectation-store byte gauge tests ---

    @Test
    public void registersExpectationStoreByteGauges() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_expectations_bytes"), is(true));
        assertThat(scrapeContains("mock_server_max_expectations_bytes"), is(true));
        assertThat(scrapeContains("mock_server_expectations_byte_evicted"), is(true));
    }

    @Test
    public void expectationStoreByteGaugesReadZeroWithNoSupplier() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeGaugeValue("mock_server_expectations_bytes"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_max_expectations_bytes"), is(0.0));
        assertThat(scrapeUnlabeledCounterValue("mock_server_expectations_byte_evicted"), is(0.0));
    }

    @Test
    public void expectationStoreByteGaugesReportSupplierValues() {
        new Metrics(configuration().metricsEnabled(true));

        // total bytes is live even with the budget off (0), mirroring the retained-bytes gauge
        Metrics.setExpectationStoreStatsSupplier(() -> new Metrics.ExpectationStoreStats(4096, 0, 0));
        assertThat(scrapeGaugeValue("mock_server_expectations_bytes"), is(4096.0));
        assertThat(scrapeGaugeValue("mock_server_max_expectations_bytes"), is(0.0));
        assertThat(scrapeUnlabeledCounterValue("mock_server_expectations_byte_evicted"), is(0.0));

        // budget enabled and eviction under way — a later scrape tracks it
        Metrics.setExpectationStoreStatsSupplier(() -> new Metrics.ExpectationStoreStats(268435456, 268435456, 17));
        assertThat(scrapeGaugeValue("mock_server_expectations_bytes"), is(268435456.0));
        assertThat(scrapeGaugeValue("mock_server_max_expectations_bytes"), is(268435456.0));
        assertThat(scrapeUnlabeledCounterValue("mock_server_expectations_byte_evicted"), is(17.0));
    }

    @Test
    public void expectationStoreByteGaugesFailSoftToZero() {
        new Metrics(configuration().metricsEnabled(true));

        Metrics.setExpectationStoreStatsSupplier(() -> {
            throw new RuntimeException("expectation store unavailable");
        });
        assertThat(scrapeGaugeValue("mock_server_expectations_bytes"), is(0.0));
        assertThat(scrapeGaugeValue("mock_server_max_expectations_bytes"), is(0.0));
        assertThat(scrapeUnlabeledCounterValue("mock_server_expectations_byte_evicted"), is(0.0));
    }

    // --- Accept-queue backlog gauge tests ---

    @Test
    public void alwaysEmitsConfiguredAcceptQueueBacklog() {
        new Metrics(configuration().metricsEnabled(true).soBacklog(2048));

        assertThat(scrapeContains("mock_server_accept_queue_backlog_configured"), is(true));
        assertThat(scrapeGaugeValue("mock_server_accept_queue_backlog_configured"), is(2048.0));
    }

    @Test
    public void emitsEffectiveAcceptQueueBacklogAsMinWhenSomaxconnReadable() throws Exception {
        java.nio.file.Path somaxconn = java.nio.file.Files.createTempFile("somaxconn", ".txt");
        somaxconn.toFile().deleteOnExit();
        java.nio.file.Files.write(somaxconn, "128\n".getBytes());
        Metrics.somaxconnPath = somaxconn;

        new Metrics(configuration().metricsEnabled(true).soBacklog(1024));

        // kernel ceiling (128) is below the configured depth (1024), so effective is the kernel ceiling
        assertThat(scrapeContains("mock_server_accept_queue_backlog_effective"), is(true));
        assertThat(scrapeGaugeValue("mock_server_accept_queue_backlog_effective"), is(128.0));
    }

    @Test
    public void effectiveAcceptQueueBacklogTakesConfiguredWhenBelowKernelCeiling() throws Exception {
        java.nio.file.Path somaxconn = java.nio.file.Files.createTempFile("somaxconn", ".txt");
        somaxconn.toFile().deleteOnExit();
        java.nio.file.Files.write(somaxconn, "4096\n".getBytes());
        Metrics.somaxconnPath = somaxconn;

        new Metrics(configuration().metricsEnabled(true).soBacklog(512));

        assertThat(scrapeGaugeValue("mock_server_accept_queue_backlog_effective"), is(512.0));
    }

    @Test
    public void omitsEffectiveAcceptQueueBacklogWhenSomaxconnUnreadable() {
        // point at a path that does not exist — the effective gauge must be ABSENT, not the configured
        // value re-labeled "effective" (which would misstate the kernel ceiling actually in force)
        Metrics.somaxconnPath = java.nio.file.Paths.get("/nonexistent/somaxconn/for/test");

        new Metrics(configuration().metricsEnabled(true).soBacklog(1024));

        assertThat(scrapeContains("mock_server_accept_queue_backlog_configured"), is(true));
        assertThat(scrapeContains("mock_server_accept_queue_backlog_effective"), is(false));
    }

    // --- LLM optimisation gauge tests ---

    @Test
    public void registersLlmOptimisationGauges() {
        new Metrics(configuration().metricsEnabled(true));

        assertThat(scrapeContains("mock_server_llm_estimated_waste_usd"), is(true));
        assertThat(scrapeContains("mock_server_llm_cache_hit_ratio"), is(true));
        assertThat(scrapeContains("mock_server_llm_one_shot_rate"), is(true));
    }

    @Test
    public void llmOptimisationGaugesReadZeroBeforeAnyReportBuilt() {
        new Metrics(configuration().metricsEnabled(true));

        // No report built yet (snapshot null) -> all three report 0.
        assertThat(Metrics.getLlmEstimatedWasteUsd(), is(0.0));
        assertThat(Metrics.getLlmCacheHitRatio(), is(0.0));
        assertThat(Metrics.getLlmOneShotRate(), is(0.0));
    }

    @Test
    public void llmOptimisationGaugesReflectLatestSnapshot() {
        new Metrics(configuration().metricsEnabled(true));

        // verdict.totalEstimatedSavingUsd, totals.cacheHitRatio, totals.oneShotRate.
        // The gauge callbacks read the shared snapshot getters at scrape time, so assert the
        // getters directly here — that is the value the GaugeWithCallback emits, and it avoids a
        // cross-class scrape race when this class is also pulled into the parallel test phase by an
        // explicit -Dtest filter (the snapshot is a process-global written by the service tests too).
        Metrics.updateLlmOptimisationSnapshot(1.42, 0.5, 0.6667);
        assertThat(Metrics.getLlmEstimatedWasteUsd(), is(1.42));
        assertThat(Metrics.getLlmCacheHitRatio(), is(0.5));
        assertThat(Metrics.getLlmOneShotRate(), is(0.6667));

        // A subsequent build overwrites the snapshot (latest report wins).
        Metrics.updateLlmOptimisationSnapshot(0.0, 1.0, 1.0);
        assertThat(Metrics.getLlmEstimatedWasteUsd(), is(0.0));
        assertThat(Metrics.getLlmCacheHitRatio(), is(1.0));
        assertThat(Metrics.getLlmOneShotRate(), is(1.0));
    }

    @Test
    public void clearDropsLlmOptimisationSnapshot() {
        new Metrics(configuration().metricsEnabled(true));
        Metrics.updateLlmOptimisationSnapshot(3.0, 0.4, 0.9);
        assertThat(Metrics.getLlmEstimatedWasteUsd(), is(3.0));

        Metrics.clear();

        assertThat("snapshot dropped on reset", Metrics.getLlmEstimatedWasteUsd(), is(0.0));
        assertThat(Metrics.getLlmCacheHitRatio(), is(0.0));
        assertThat(Metrics.getLlmOneShotRate(), is(0.0));
    }

    @Test
    public void updateLlmOptimisationSnapshotIsSafeWhenMetricsDisabled() {
        // No Metrics constructed -> gauges not registered, but the snapshot setter/getters never throw.
        Metrics.updateLlmOptimisationSnapshot(5.0, 0.2, 0.8);
        assertThat(Metrics.getLlmEstimatedWasteUsd(), is(5.0));
    }

    // --- T0.5: monotonic-count naming contract -------------------------------------------------

    /**
     * The genuinely monotonic core counts (requests received, matched / not-matched, forward
     * matched, LLM chaos injected) remain Prometheus {@code Gauge}s exposed under their exact
     * {@code *_count} names. They are deliberately NOT converted to {@code Counter}: the Prometheus
     * 1.8.0 exposition writer appends a mandatory {@code _total} suffix to every counter's sample
     * line (see {@link #prometheusClientForcesTotalSuffixOnCounters()}), which would rename e.g.
     * {@code requests_received_count} -> {@code requests_received_count_total} and break the
     * dashboard UI ({@code MetricsView} reads these by their {@code _count} names) and any Grafana
     * dashboard. This test pins the name/type contract so an accidental future conversion is caught.
     * See docs/code/metrics.md (Monotonic counters vs levels).
     */
    @Test
    public void monotonicCountMetricsKeepExactCountNamesAndAreNotSilentlyRenamed() {
        new Metrics(configuration().metricsEnabled(true));
        String text = scrapeClassicText();

        for (String name : new String[]{
            "requests_received_count",
            "expectations_not_matched_count",
            "response_expectations_matched_count",
            "forward_expectations_matched_count",
            "llm_chaos_injected_count"
        }) {
            assertThat("monotonic metric " + name + " must be exposed under its exact _count name",
                text.contains("# TYPE " + name + " gauge"), is(true));
            assertThat("monotonic metric " + name + " must NOT be silently renamed to _count_total",
                text.contains(name + "_total"), is(false));
        }

        // A genuine level metric (active registered expectations by type / live registry size)
        // is correctly a Gauge and is likewise exposed under its exact name.
        assertThat("level metric forward_actions_count must stay a gauge",
            text.contains("# TYPE forward_actions_count gauge"), is(true));
        assertThat("level metric websocket_callback_clients_count must stay a gauge",
            text.contains("# TYPE websocket_callback_clients_count gauge"), is(true));
    }

    /**
     * Documents WHY the monotonic {@code *_count} metrics above are NOT exposed as {@code Counter}:
     * the Prometheus 1.8.0 exposition writer appends a mandatory {@code _total} suffix to every
     * counter's sample line, so a counter named {@code requests_received_count} appears as
     * {@code requests_received_count_total} — a rename that would break existing consumers. Guards
     * the compat rationale recorded in docs/code/metrics.md.
     */
    @Test
    public void prometheusClientForcesTotalSuffixOnCounters() {
        PrometheusRegistry.defaultRegistry.clear();
        io.prometheus.metrics.core.metrics.Counter.builder()
            .name("requests_received_count").help("probe").register().inc();

        String text = scrapeClassicText();
        assertThat("counter sample line is suffixed with _total",
            text.contains("requests_received_count_total"), is(true));
        assertThat("counter TYPE line reflects the _total-suffixed series name",
            text.contains("# TYPE requests_received_count_total counter"), is(true));

        PrometheusRegistry.defaultRegistry.clear();
    }

    // --- T0.5b: dual-published _total counters alongside the legacy _count gauges ----------------

    /**
     * The five monotonic counts now ALSO publish a proper Prometheus {@code Counter} exposed as
     * {@code mock_server_<base>_total}, so {@code rate()}/{@code increase()} work correctly — while
     * the legacy {@code *_count} gauge is retained unchanged for dashboard/Grafana back-compat. This
     * pins that BOTH series coexist in the exposition: the {@code _total} counter and the legacy
     * {@code _count} gauge, each with the correct {@code # TYPE} line.
     */
    @Test
    public void dualPublishesTotalCounterAlongsideLegacyCountGauge() {
        new Metrics(configuration().metricsEnabled(true));
        String text = scrapeClassicText();

        // legacy _count gauge name -> new _total counter exposition name
        String[][] pairs = {
            {"requests_received_count", "mock_server_requests_received_total"},
            {"expectations_not_matched_count", "mock_server_expectations_not_matched_total"},
            {"response_expectations_matched_count", "mock_server_response_expectations_matched_total"},
            {"forward_expectations_matched_count", "mock_server_forward_expectations_matched_total"},
            {"llm_chaos_injected_count", "mock_server_llm_chaos_injected_total"},
        };
        for (String[] pair : pairs) {
            String legacyGauge = pair[0];
            String totalCounter = pair[1];
            assertThat("legacy gauge " + legacyGauge + " must still be present as a gauge (back-compat)",
                text.contains("# TYPE " + legacyGauge + " gauge"), is(true));
            assertThat("new counter " + totalCounter + " must be present as a counter",
                text.contains("# TYPE " + totalCounter + " counter"), is(true));
        }
    }

    /**
     * The dual-published counter is incremented in lock-step with the legacy gauge from the same
     * {@link Metrics#increment(Metrics.Name)} call site, so both move together.
     */
    @Test
    public void monotonicTotalCounterIncrementsWithLegacyGauge() {
        Metrics metrics = new Metrics(configuration().metricsEnabled(true));

        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        metrics.increment(Metrics.Name.LLM_CHAOS_INJECTED_COUNT);

        // legacy gauge value
        assertThat(Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(2));
        assertThat(Metrics.get(Metrics.Name.LLM_CHAOS_INJECTED_COUNT), is(1));
        // dual-published counter value (moves in lock-step)
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(2L));
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.LLM_CHAOS_INJECTED_COUNT), is(1L));
        // both series render in the exposition with matching values (the counter snapshot's
        // metadata name is the unsuffixed name; the exposition text appends _total)
        assertThat(scrapeUnlabeledCounterValue("mock_server_requests_received"), is(2.0));
        assertThat(scrapeGaugeValue("requests_received_count"), is(2.0));
    }

    @Test
    public void getMonotonicTotalCountReturnsZeroWhenDisabledOrNotMonotonic() {
        // metrics disabled -> counter not registered -> 0
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(0L));

        new Metrics(configuration().metricsEnabled(true));
        // a level (non-monotonic) Name has no dual-published counter -> 0
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.FORWARD_ACTIONS_COUNT), is(0L));
    }

    /**
     * Pins the reset-on-server-reset contract documented in docs/code/metrics.md: on a MockServer
     * reset the legacy {@code _count} gauge is zeroed by {@link Metrics#clear()} /
     * {@link Metrics#clearRequestAndExpectationMetrics()}, but the dual-published {@code _total}
     * counter follows Prometheus counter semantics like the other {@code _total} counters — it is
     * NOT zeroed by a reset and keeps climbing (only a process restart / the testing reset hook
     * clears it). So after a reset the two series diverge by design: gauge 0, counter unchanged.
     */
    @Test
    public void monotonicTotalCounterSurvivesServerResetWhileLegacyGaugeIsZeroed() {
        Metrics metrics = new Metrics(configuration().metricsEnabled(true));
        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        metrics.increment(Metrics.Name.LLM_CHAOS_INJECTED_COUNT);

        // Metrics.clear() — the general server-reset path — zeroes every gauge...
        Metrics.clear();
        assertThat(Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(0));
        assertThat(Metrics.get(Metrics.Name.LLM_CHAOS_INJECTED_COUNT), is(0));
        // ...but leaves the monotonic _total counters untouched (counter semantics).
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(2L));
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.LLM_CHAOS_INJECTED_COUNT), is(1L));

        // The request/expectation subset reset likewise zeroes only the gauge, not the counter.
        metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);
        Metrics.clearRequestAndExpectationMetrics();
        assertThat(Metrics.get(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(0));
        assertThat(Metrics.getMonotonicTotalCount(Metrics.Name.REQUESTS_RECEIVED_COUNT), is(3L));
    }

    /** Render the classic Prometheus text exposition (0.0.4), the format dashboards/scrapers read. */
    private static String scrapeClassicText() {
        try {
            io.prometheus.metrics.expositionformats.ExpositionFormats formats =
                io.prometheus.metrics.expositionformats.ExpositionFormats.init();
            io.prometheus.metrics.expositionformats.ExpositionFormatWriter writer =
                formats.findWriter("text/plain");
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            writer.write(out, PrometheusRegistry.defaultRegistry.scrape());
            return out.toString("UTF-8");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static double scrapeUnlabeledCounterValue(String name) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dataPoint : counterSnapshot.getDataPoints()) {
                    return dataPoint.getValue();
                }
            }
        }
        return 0.0;
    }

    private static double scrapeGaugeValue(String name) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof GaugeSnapshot gaugeSnapshot) {
                for (GaugeSnapshot.GaugeDataPointSnapshot dataPoint : gaugeSnapshot.getDataPoints()) {
                    return dataPoint.getValue();
                }
            }
        }
        return 0.0;
    }

    private static boolean scrapeContains(String name) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name)) {
                return true;
            }
        }
        return false;
    }

    private static double scrapeGaugeValueByLabel(String name, String labelName, String labelValue) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof GaugeSnapshot gaugeSnapshot) {
                for (GaugeSnapshot.GaugeDataPointSnapshot dataPoint : gaugeSnapshot.getDataPoints()) {
                    if (labelValue.equals(dataPoint.getLabels().get(labelName))) {
                        return dataPoint.getValue();
                    }
                }
            }
        }
        return 0.0;
    }

    @Test
    public void shouldHaveActionsCountConstantForEveryActionType() {
        // Metrics builds the per-action counter name reflectively via
        // Name.valueOf(type.name() + "_ACTIONS_COUNT"), so a new Action.Type without a
        // matching Name constant makes expectation registration throw for that action
        // (this happened for GRPC_BIDI_RESPONSE, FORWARD_VALIDATE, and
        // FORWARD_WITH_FALLBACK). This guard fails at build time instead.
        for (org.mockserver.model.Action.Type type : org.mockserver.model.Action.Type.values()) {
            org.mockserver.metrics.Metrics.Name.valueOf(type.name() + "_ACTIONS_COUNT");
        }
    }

    private static double scrapeCounterValue(String name, String labelName, String labelValue) {
        MetricSnapshots snapshots = PrometheusRegistry.defaultRegistry.scrape();
        for (MetricSnapshot snapshot : snapshots) {
            if (snapshot.getMetadata().getName().equals(name) && snapshot instanceof CounterSnapshot counterSnapshot) {
                for (CounterSnapshot.CounterDataPointSnapshot dataPoint : counterSnapshot.getDataPoints()) {
                    if (labelValue.equals(dataPoint.getLabels().get(labelName))) {
                        return dataPoint.getValue();
                    }
                }
            }
        }
        return 0.0;
    }
}

package org.mockserver.netty;

import com.google.common.collect.ImmutableSet;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.CharsetUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.dashboard.DashboardHandler;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.time.EpochService;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.RetrieveType;
import org.mockserver.netty.responsewriter.NettyResponseWriter;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.HttpRequestSerializer;
import org.mockserver.serialization.PortBindingSerializer;
import org.slf4j.event.Level;

import java.net.BindException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.CoreMatchers.endsWith;
import static org.hamcrest.CoreMatchers.startsWith;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LOG_DATE_FORMAT;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.PortBinding.portBinding;
import static org.mockserver.netty.HttpRequestHandler.LOCAL_HOST_HEADERS;
import static org.mockserver.netty.HttpRequestHandler.PROXYING;

/**
 * @author jamesdbloom
 */
public class HttpRequestHandlerTest {

    @Rule
    public ExpectedException exception = ExpectedException.none();
    private HttpState httpStateHandler;
    private final List<HttpState> httpStates = new ArrayList<>();
    protected LifeCycle server;
    private HttpActionHandler mockActionHandler;
    private EmbeddedChannel embeddedChannel;
    @InjectMocks
    private HttpRequestHandler mockServerHandler;
    private final HttpRequestSerializer httpRequestSerializer = new HttpRequestSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializerWithDefaultFields = new ExpectationSerializer(new MockServerLogger(), true);
    private final PortBindingSerializer portBindingSerializer = new PortBindingSerializer(new MockServerLogger());

    @BeforeClass
    public static void fixTime() {
        EpochService.fixedTimeGlobally(true);
    }

    @Before
    public void setupFixture() {
        server = mock(MockServer.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        mockActionHandler = mock(HttpActionHandler.class);

        httpStateHandler = new HttpState(configuration(), new MockServerLogger(), synchronousScheduler());

        httpStates.add(httpStateHandler);
        mockServerHandler = new HttpRequestHandler(configuration(), server, httpStateHandler, null);

        openMocks(this);

        embeddedChannel = new EmbeddedChannel(mockServerHandler);
    }

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    @Test
    public void shouldRetrieveRequests() {
        // given
        httpStateHandler.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(RECEIVED_REQUEST)
        );

        // when
        HttpRequest expectationRetrieveRequestsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );
        embeddedChannel.writeInbound(expectationRetrieveRequestsRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(httpRequestSerializer.serialize(Collections.singletonList(
            request("request_one")
        ))));
    }

    @Test
    public void shouldClear() {
        // given
        httpStateHandler.add(new Expectation(request("request_one")).thenRespond(response("response_one")));
        httpStateHandler.log(
            new LogEntry()
                .setHttpRequest(request("request_one"))
                .setType(EXPECTATION_MATCHED)
        );
        HttpRequest clearRequest = request("/mockserver/clear")
            .withMethod("PUT")
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );

        // when
        embeddedChannel.writeInbound(clearRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(""));
        assertThat(httpStateHandler.firstMatchingExpectation(request("request_one")), is(nullValue()));
        assertThat(httpStateHandler.retrieve(request("/mockserver/retrieve")
            .withMethod("PUT")
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            )), is(response().withBody("[]", MediaType.JSON_UTF_8).withStatusCode(200)));
    }

    @Test
    public void shouldReturnStatus() {
        // given
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1090, 1090));
        HttpRequest statusRequest = request("/mockserver/status").withMethod("PUT");

        // when
        embeddedChannel.writeInbound(statusRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
            portBinding(1090, 1090)
        )));
    }

    @Test
    public void shouldReturnStatusOnCustomPath() {
        String originalStatusPath = ConfigurationProperties.livenessHttpGetPath();
        try {
            // given
            ConfigurationProperties.livenessHttpGetPath("/livenessProbe");
            when(server.getLocalPorts()).thenReturn(Arrays.asList(1090, 1090));
            HttpRequest statusRequest = request("/livenessProbe").withMethod("GET");

            // when
            embeddedChannel.writeInbound(statusRequest);

            // then
            HttpResponse httpResponse = embeddedChannel.readOutbound();
            assertThat(httpResponse.getStatusCode(), is(200));
            assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
                portBinding(1090, 1090)
            )));
        } finally {
            ConfigurationProperties.livenessHttpGetPath(originalStatusPath);
        }
    }

    @Test
    public void shouldReserveMetricsPathWithCORSWhenMetricsDisabled() {
        // given - metrics disabled (the default configuration); the control-plane
        // /metrics path is still reserved (like /dashboard and /openapi.yaml), so a
        // cross-origin dashboard receives a CORS-decorated 404 it can read to show
        // its "metrics disabled" guidance rather than the request falling through
        // to mock matching
        HttpRequest metricsRequest = request("/mockserver/metrics")
            .withMethod("GET")
            .withKeepAlive(true)
            .withHeader("origin", "http://localhost:3000");

        // when
        embeddedChannel.writeInbound(metricsRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(404));
        assertThat(httpResponse.getFirstHeader("access-control-allow-origin"), is("http://localhost:3000"));
    }

    @Test
    public void shouldServeMetricsWithPrometheusBodyWhenMetricsEnabled() {
        // given - a server with metrics ENABLED and one request-received increment recorded, so the
        // Prometheus scrape carries the mock_server_requests_received_total counter with a value > 0.
        // This is the counterpart to shouldReserveMetricsPathWithCORSWhenMetricsDisabled above: it
        // drives GET /mockserver/metrics through the real HttpRequestHandler routing + MetricsHandler
        // and proves the enabled path serves 200 with the exposition body (previously only the
        // disabled 404 path and a mock-ctx unit test asserting content-type!=null existed).
        rebuildWithMetricsEnabled();
        try {
            Metrics.resetAdditionalMetricsForTesting();
            Metrics metrics = new Metrics(configuration().metricsEnabled(true));
            metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);

            HttpRequest metricsRequest = request("/mockserver/metrics")
                .withMethod("GET")
                .withKeepAlive(true);

            // when
            embeddedChannel.writeInbound(metricsRequest);

            // then - the MetricsHandler writes the response object directly to the channel with no
            // explicit status code (null), which the wire encoder maps to 200 OK. Assert against that
            // same encoder so the assertion reflects what actually reaches a scraper, and confirm the
            // incremented counter is present in the Prometheus text body.
            HttpResponse httpResponse = embeddedChannel.readOutbound();
            FullHttpResponse wireResponse = mapToWireResponse(httpResponse);
            assertThat(wireResponse.status().code(), is(200));
            assertThat(wireResponse.content().toString(CharsetUtil.UTF_8),
                containsString("mock_server_requests_received_total"));
        } finally {
            Metrics.resetAdditionalMetricsForTesting();
        }
    }

    @Test
    public void shouldServeDroppedLogEventsLabelledByReason() {
        // given - both reasons are exported from the first scrape, and a drop counts only under its own reason
        rebuildWithMetricsEnabled();
        try {
            Metrics.resetAdditionalMetricsForTesting();
            new Metrics(configuration().metricsEnabled(true));
            Metrics.incrementDroppedLogEvents("in_flight_bytes");

            // when
            embeddedChannel.writeInbound(request("/mockserver/metrics").withMethod("GET").withKeepAlive(true));

            // then
            String body = mapToWireResponse(embeddedChannel.readOutbound()).content().toString(CharsetUtil.UTF_8);
            assertThat(body, containsString("mock_server_dropped_log_events_total{reason=\"in_flight_bytes\"} 1.0"));
            assertThat(body, containsString("mock_server_dropped_log_events_total{reason=\"ring_full\"} 0.0"));
            // no unlabelled sample line remains (HELP/TYPE comment lines still name the series)
            assertThat(body, not(containsString("\nmock_server_dropped_log_events_total ")));
        } finally {
            Metrics.resetAdditionalMetricsForTesting();
        }
    }

    @Test
    public void shouldServeOpenMetricsBodyWhenMetricsEnabledAndOpenMetricsAccept() {
        // given - metrics enabled; the handler selects the exposition writer from the Accept header
        // (MetricsHandler.renderMetrics -> ExpositionFormats.findWriter(Accept)), so an OpenMetrics
        // Accept must yield an OpenMetrics content-type while still serving 200 and the counter.
        rebuildWithMetricsEnabled();
        try {
            Metrics.resetAdditionalMetricsForTesting();
            Metrics metrics = new Metrics(configuration().metricsEnabled(true));
            metrics.increment(Metrics.Name.REQUESTS_RECEIVED_COUNT);

            HttpRequest metricsRequest = request("/mockserver/metrics")
                .withMethod("GET")
                .withKeepAlive(true)
                .withHeader("Accept", "application/openmetrics-text; version=1.0.0; charset=utf-8");

            // when
            embeddedChannel.writeInbound(metricsRequest);

            // then - 200 with the OpenMetrics exposition format negotiated from Accept
            HttpResponse httpResponse = embeddedChannel.readOutbound();
            FullHttpResponse wireResponse = mapToWireResponse(httpResponse);
            assertThat(wireResponse.status().code(), is(200));
            assertThat(httpResponse.getFirstHeader("content-type"), containsString("openmetrics-text"));
            assertThat(wireResponse.content().toString(CharsetUtil.UTF_8),
                containsString("mock_server_requests_received_total"));
        } finally {
            Metrics.resetAdditionalMetricsForTesting();
        }
    }

    /**
     * Rebuilds {@link #httpStateHandler} and the embedded channel handler with metrics ENABLED, so
     * GET /mockserver/metrics is served by {@link org.mockserver.metrics.MetricsHandler} rather than
     * the disabled-state 404. Mirrors {@link #rebuildWithSharedConfiguration()}.
     */
    private void rebuildWithMetricsEnabled() {
        org.mockserver.configuration.Configuration configuration = configuration().metricsEnabled(true);
        httpStateHandler = new HttpState(configuration, new MockServerLogger(), synchronousScheduler());
        httpStates.add(httpStateHandler);
        mockServerHandler = new HttpRequestHandler(configuration, server, httpStateHandler, null);
        embeddedChannel = new EmbeddedChannel(mockServerHandler);
    }

    /**
     * Maps a model {@link HttpResponse} through the same encoder the Netty server uses, so a
     * response with no explicit status code (as the metrics handler writes) is observed as the
     * 200 OK it becomes on the wire, and the exposition body can be read as bytes.
     */
    private FullHttpResponse mapToWireResponse(HttpResponse httpResponse) {
        return (FullHttpResponse) new MockServerHttpResponseToFullHttpResponse(new MockServerLogger())
            .mapMockServerResponseToNettyResponse(httpResponse)
            .get(0);
    }

    @Test
    public void shouldBindNewPorts() {
        // given
        when(server.bindServerPorts(anyList())).thenReturn(Arrays.asList(1090, 1090));
        HttpRequest statusRequest = request("/mockserver/bind")
            .withMethod("PUT")
            .withBody(portBindingSerializer.serialize(
                portBinding(1090, 1090)
            ));

        // when
        embeddedChannel.writeInbound(statusRequest);

        // then
        verify(server).bindServerPorts(Arrays.asList(1090, 1090));
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
            portBinding(1090, 1090)
        )));
    }

    @Test
    public void shouldReturnBindConflictDetailWhenPortIsInUse() {
        // given
        BindException conflict = LifeCycle.explicitPortConflict(1090, new InetSocketAddress("127.0.0.1", 1090));
        when(server.bindServerPorts(anyList())).thenThrow(new RuntimeException("Exception while binding MockServer to port 1090", conflict));
        HttpRequest bindRequest = request("/mockserver/bind")
            .withMethod("PUT")
            .withBody(portBindingSerializer.serialize(portBinding(1090)));

        // when
        embeddedChannel.writeInbound(bindRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(400));
        assertThat(httpResponse.getBodyAsString(), is("Exception while binding MockServer to port 1090 port already in use: " + conflict.getMessage()));
        assertThat(httpResponse.getBodyAsString(), containsString("lsof -nP -iTCP:1090 -sTCP:LISTEN"));
    }

    @Test
    public void shouldAnswerBadRequestWhenBindHasNoPortBinding() {
        // given
        HttpRequest bindRequest = request("/mockserver/bind")
            .withMethod("PUT");

        // when
        embeddedChannel.writeInbound(bindRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(400));
        assertThat(httpResponse.getBodyAsString(), is("invalid or missing port binding"));
        verify(server, never()).bindServerPorts(anyList());
    }

    @Test
    public void shouldStop() throws InterruptedException {
        // given
        HttpRequest statusRequest = request("/mockserver/stop")
            .withMethod("PUT");

        // when
        embeddedChannel.writeInbound(statusRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is((String) null));
        TimeUnit.SECONDS.sleep(1); // ensure stop thread has run
        verify(server).stop();
    }

    @Test
    public void shouldRejectStopWhenControlPlaneAuthEnabledAndNotAuthenticated() throws InterruptedException {
        // given - control-plane auth configured but the request carries no/invalid creds
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        HttpRequest stopRequest = request("/mockserver/stop").withMethod("PUT");

        // when
        embeddedChannel.writeInbound(stopRequest);

        // then - 401 and the server is NOT stopped
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(401));
        assertThat(httpResponse.getBodyAsString(), containsString("Unauthorized for control plane"));
        TimeUnit.SECONDS.sleep(1); // give any (erroneously) spawned stop thread time to run
        verify(server, never()).stop();
    }

    @Test
    public void shouldStopWhenControlPlaneAuthEnabledAndAuthenticated() throws InterruptedException {
        // given - control-plane auth configured and the request is authenticated
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> true);
        HttpRequest stopRequest = request("/mockserver/stop").withMethod("PUT");

        // when
        embeddedChannel.writeInbound(stopRequest);

        // then - 200 and the server is stopped
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        TimeUnit.SECONDS.sleep(1); // ensure stop thread has run
        verify(server).stop();
    }

    @Test
    public void shouldRejectBindWhenControlPlaneAuthEnabledAndNotAuthenticated() {
        // given - control-plane auth configured but the request carries no/invalid creds
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        HttpRequest bindRequest = request("/mockserver/bind")
            .withMethod("PUT")
            .withBody(portBindingSerializer.serialize(
                portBinding(1090, 1090)
            ));

        // when
        embeddedChannel.writeInbound(bindRequest);

        // then - 401 and no ports are (re)bound
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(401));
        assertThat(httpResponse.getBodyAsString(), containsString("Unauthorized for control plane"));
        verify(server, never()).bindServerPorts(anyList());
    }

    @Test
    public void shouldBindWhenControlPlaneAuthEnabledAndAuthenticated() {
        // given - control-plane auth configured and the request is authenticated
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> true);
        when(server.bindServerPorts(anyList())).thenReturn(Arrays.asList(1090, 1090));
        HttpRequest bindRequest = request("/mockserver/bind")
            .withMethod("PUT")
            .withBody(portBindingSerializer.serialize(
                portBinding(1090, 1090)
            ));

        // when
        embeddedChannel.writeInbound(bindRequest);

        // then - 200 and the ports are bound
        verify(server).bindServerPorts(Arrays.asList(1090, 1090));
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
            portBinding(1090, 1090)
        )));
    }

    @Test
    public void shouldForbidStopForReadOnlyPrincipalWhenAuthorizationEnabled() throws InterruptedException {
        // given - control-plane authorization enabled with a verified READ-only principal;
        // PUT /stop shuts the server down, so it must be classified MUTATE and rejected with
        // 403 (not 401) at the Netty layer — a read-only principal cannot stop the server
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("viewer", java.util.Set.of("viewers"));
        HttpRequest stopRequest = request("/mockserver/stop").withMethod("PUT");

        // when
        embeddedChannel.writeInbound(stopRequest);

        // then - 403 and the server is NOT stopped
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(403));
        assertThat(httpResponse.getBodyAsString(), containsString("Forbidden for control plane"));
        TimeUnit.SECONDS.sleep(1); // give any (erroneously) spawned stop thread time to run
        verify(server, never()).stop();
    }

    @Test
    public void shouldReturnStatusWhenControlPlaneAuthEnabledWithoutCredentials() {
        // given - control-plane auth configured and rejecting; /status (liveness) must stay
        // open so k8s liveness/readiness probes never need credentials
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        when(server.getLocalPorts()).thenReturn(Arrays.asList(1090, 1090));
        HttpRequest statusRequest = request("/mockserver/status").withMethod("PUT");

        // when
        embeddedChannel.writeInbound(statusRequest);

        // then - still 200 with port binding, no auth challenge
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(portBindingSerializer.serialize(
            portBinding(1090, 1090)
        )));
    }

    @Test
    public void shouldStopWhenNoControlPlaneAuthConfigured() throws InterruptedException {
        // given - default config: NO control-plane auth handler set. The shared gate is a
        // no-op (returns true), so /stop must still work with no credentials.
        HttpRequest stopRequest = request("/mockserver/stop").withMethod("PUT");

        // when
        embeddedChannel.writeInbound(stopRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        TimeUnit.SECONDS.sleep(1); // ensure stop thread has run
        verify(server).stop();
    }

    @Test
    public void shouldBindWhenNoControlPlaneAuthConfigured() {
        // given - default config: NO control-plane auth handler set. The shared gate is a
        // no-op (returns true), so /bind must still work with no credentials.
        when(server.bindServerPorts(anyList())).thenReturn(Arrays.asList(1090, 1090));
        HttpRequest bindRequest = request("/mockserver/bind")
            .withMethod("PUT")
            .withBody(portBindingSerializer.serialize(
                portBinding(1090, 1090)
            ));

        // when
        embeddedChannel.writeInbound(bindRequest);

        // then
        verify(server).bindServerPorts(Arrays.asList(1090, 1090));
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
    }

    @Test
    public void shouldRetrieveRecordedExpectations() {
        // given
        httpStateHandler.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setHttpRequest(request("request_one"))
                .setHttpResponse(response("response_one"))
                .setExpectation(new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one")))
        );

        // when
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.RECORDED_EXPECTATIONS.name())
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );
        embeddedChannel.writeInbound(expectationRetrieveExpectationsRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(expectationSerializerWithDefaultFields.serialize(Collections.singletonList(
            new Expectation(request("request_one"), Times.once(), TimeToLive.unlimited(), 0).withId("key_one").thenRespond(response("response_one"))
        ))));
    }

    @Test
    public void shouldRetrieveLogMessages() {
        Level originalLevel = ConfigurationProperties.logLevel();
        try {
            // given
            ConfigurationProperties.logLevel("INFO");
            httpStateHandler.add(new Expectation(request("request_one")).withId("key_one").thenRespond(response("response_one")));

            // when
            HttpRequest retrieveLogRequest = request("/mockserver/retrieve")
                .withMethod("PUT")
                .withQueryStringParameter("type", RetrieveType.LOGS.name())
                .withBody(
                    httpRequestSerializer.serialize(request("request_one"))
                );
            embeddedChannel.writeInbound(retrieveLogRequest);

            // then
            HttpResponse response = embeddedChannel.readOutbound();
            assertThat(response.getStatusCode(), is(200));
            assertThat(
                response.getBodyAsString(),
                is(endsWith(LOG_DATE_FORMAT.format(new Date(EpochService.currentTimeMillis())) + " - creating expectation:" + NEW_LINE +
                    NEW_LINE +
                    "  {" + NEW_LINE +
                    "    \"httpRequest\" : {" + NEW_LINE +
                    "      \"path\" : \"request_one\"" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"httpResponse\" : {" + NEW_LINE +
                    "      \"statusCode\" : 200," + NEW_LINE +
                    "      \"reasonPhrase\" : \"OK\"," + NEW_LINE +
                    "      \"body\" : \"response_one\"" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"id\" : \"key_one\"," + NEW_LINE +
                    "    \"priority\" : 0," + NEW_LINE +
                    "    \"timeToLive\" : {" + NEW_LINE +
                    "      \"unlimited\" : true" + NEW_LINE +
                    "    }," + NEW_LINE +
                    "    \"times\" : {" + NEW_LINE +
                    "      \"unlimited\" : true" + NEW_LINE +
                    "    }" + NEW_LINE +
                    "  }" + NEW_LINE +
                    NEW_LINE +
                    " with id:" + NEW_LINE +
                    NEW_LINE +
                    "  key_one" + NEW_LINE +
                    NEW_LINE))
            );
        } finally {
            ConfigurationProperties.logLevel(originalLevel.name());
        }
    }

    @Test
    public void shouldAddExpectation() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        HttpRequest request = request("/mockserver/expectation").withMethod("PUT").withBody(
            expectationSerializer.serialize(expectationOne)
        );

        // when
        embeddedChannel.writeInbound(request);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(201));
        assertThat(httpResponse.getBodyAsString(), containsString("[ {" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"request_one\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200," + NEW_LINE +
            "    \"reasonPhrase\" : \"OK\"," + NEW_LINE +
            "    \"body\" : \"response_one\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"id\" : \""
        ));
        assertThat(httpResponse.getBodyAsString(), containsString("\"," + NEW_LINE +
            "  \"priority\" : 0," + NEW_LINE +
            "  \"timeToLive\" : {" + NEW_LINE +
            "    \"unlimited\" : true" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"unlimited\" : true" + NEW_LINE +
            "  }" + NEW_LINE +
            "} ]"));
        assertThat(httpStateHandler.firstMatchingExpectation(request("request_one")), is(expectationOne));
    }

    @Test
    public void shouldRetrieveActiveExpectations() {
        // given
        Expectation expectationOne = new Expectation(request("request_one")).thenRespond(response("response_one"));
        httpStateHandler.add(expectationOne);
        HttpRequest expectationRetrieveExpectationsRequest = request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", RetrieveType.ACTIVE_EXPECTATIONS.name())
            .withBody(
                httpRequestSerializer.serialize(request("request_one"))
            );

        // when
        embeddedChannel.writeInbound(expectationRetrieveExpectationsRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), is(expectationSerializer.serialize(Collections.singletonList(
            expectationOne
        ))));
    }

    @Test
    public void shouldProxyRequestsWhenProxying() {
        // given
        HttpRequest request = request("request_one");
        InetSocketAddress remoteAddress = new InetSocketAddress(1090);
        embeddedChannel.attr(LOCAL_HOST_HEADERS).set(ImmutableSet.of(
            "local_address:666",
            "localhost:666",
            "127.0.0.1:666"
        ));
        embeddedChannel.attr(PROXYING).set(true);
        embeddedChannel.attr(REMOTE_SOCKET).set(remoteAddress);

        // when
        embeddedChannel.writeInbound(request);

        // then
        verify(mockActionHandler).processAction(
            eq(request),
            any(NettyResponseWriter.class),
            any(ChannelHandlerContext.class),
            eq(ImmutableSet.of(
                "local_address:666",
                "localhost:666",
                "127.0.0.1:666"
            )),
            eq(true),
            eq(false),
            eq(true));
    }

    @Test
    public void shouldReturnOpenAPISpec() {
        // given
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getFirstHeader("content-type"), is("application/yaml; charset=utf-8"));
        assertThat(httpResponse.getBodyAsString(), containsString("openapi: 3.0.0"));
        assertThat(httpResponse.getBodyAsString(), containsString("MockServer API"));
        assertThat(httpResponse.getBodyAsString(), containsString("/mockserver/expectation"));
    }

    @Test
    public void shouldRejectOpenAPISpecWhenAuthEnabled() {
        // given
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(401));
        assertThat(httpResponse.getBodyAsString(), containsString("Unauthorized for control plane"));
    }

    @Test
    public void shouldAllowOpenAPISpecWhenAuthSucceeds() {
        // given
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> true);
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("openapi: 3.0.0"));
    }

    @Test
    public void shouldAllowOpenAPISpecWhenNoAuth() {
        // given - no auth handler set (default)
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("openapi: 3.0.0"));
    }

    @Test
    public void shouldAllowOpenAPISpecForReadOnlyPrincipalWhenAuthorizationEnabled() {
        // given - GET /openapi.yaml is a READ, so a read-only principal is allowed even
        // with authorization enabled (the Netty-layer route now takes the shared authz gate)
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("viewer", java.util.Set.of("viewers"));
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("openapi: 3.0.0"));
    }

    @Test
    public void shouldAllowOpenAPISpecForMutatePrincipalWhenAuthorizationEnabled() {
        // given - a mutate-role principal may read /openapi.yaml (mutate >= read)
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("qa", java.util.Set.of("qa-team"));
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("openapi: 3.0.0"));
    }

    @Test
    public void shouldAllowOpenAPISpecForAdminPrincipalWhenAuthorizationEnabled() {
        // given - an admin-role principal may read /openapi.yaml (admin >= read)
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("root", java.util.Set.of("platform-admins"));
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("openapi: 3.0.0"));
    }

    @Test
    public void shouldForbidOpenAPISpecForUnmappedPrincipalWhenAuthorizationEnabled() {
        // given - a VERIFIED principal whose scopes map to NO role: even a read is denied
        // (fail-closed) with 403 (not 401) because authentication succeeded
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("stranger", java.util.Set.of("no-mapped-group"));
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(403));
        assertThat(httpResponse.getBodyAsString(), containsString("Forbidden for control plane"));
    }

    @Test
    public void shouldAllowOpenAPISpecForUnmappedPrincipalWhenAuthorizationDisabled() {
        // given - authorization disabled: an authenticated principal with no mapped role
        // still reads /openapi.yaml (authn only, no authz) — default-off behaviour unchanged
        rebuildHttpStateWithAuthorization(false);
        authenticateWithScopes("stranger", java.util.Set.of("no-mapped-group"));
        HttpRequest openAPIRequest = request("/mockserver/openapi.yaml").withMethod("GET");

        // when
        embeddedChannel.writeInbound(openAPIRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("openapi: 3.0.0"));
    }

    @Test
    public void shouldRejectOptimisationReportWhenAuthEnabled() {
        // given - legacy boolean authentication handler rejects: 401
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        HttpRequest reportRequest = request("/mockserver/llm/optimisationReport").withMethod("GET");

        // when
        embeddedChannel.writeInbound(reportRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(401));
        assertThat(httpResponse.getBodyAsString(), containsString("Unauthorized for control plane"));
    }

    @Test
    public void shouldAllowOptimisationReportForReadOnlyPrincipalWhenAuthorizationEnabled() {
        // given - GET /llm/optimisationReport is a READ; a read-only principal is allowed
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("viewer", java.util.Set.of("viewers"));
        HttpRequest reportRequest = request("/mockserver/llm/optimisationReport").withMethod("GET");

        // when
        embeddedChannel.writeInbound(reportRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
    }

    @Test
    public void shouldAllowOptimisationReportForAdminPrincipalWhenAuthorizationEnabled() {
        // given - an admin-role principal may read the optimisation report (admin >= read)
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("root", java.util.Set.of("platform-admins"));
        HttpRequest reportRequest = request("/mockserver/llm/optimisationReport").withMethod("GET");

        // when
        embeddedChannel.writeInbound(reportRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
    }

    @Test
    public void shouldForbidOptimisationReportForUnmappedPrincipalWhenAuthorizationEnabled() {
        // given - a VERIFIED principal whose scopes map to NO role: even this read is denied
        // (fail-closed) with 403 + audit FORBIDDEN because authentication succeeded
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("stranger", java.util.Set.of("no-mapped-group"));
        HttpRequest reportRequest = request("/mockserver/llm/optimisationReport").withMethod("GET");

        // when
        embeddedChannel.writeInbound(reportRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(403));
        assertThat(httpResponse.getBodyAsString(), containsString("Forbidden for control plane"));
    }

    @Test
    public void shouldAllowOptimisationReportForUnmappedPrincipalWhenAuthorizationDisabled() {
        // given - authorization disabled: an authenticated principal with no mapped role
        // still reads the optimisation report (authn only) — default-off behaviour unchanged
        rebuildHttpStateWithAuthorization(false);
        authenticateWithScopes("stranger", java.util.Set.of("no-mapped-group"));
        HttpRequest reportRequest = request("/mockserver/llm/optimisationReport").withMethod("GET");

        // when
        embeddedChannel.writeInbound(reportRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
    }

    @Test
    public void shouldReturnConfiguration() {
        // given
        HttpRequest configRequest = request("/mockserver/configuration").withMethod("GET");

        // when
        embeddedChannel.writeInbound(configRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("\"logLevel\""));
        assertThat(httpResponse.getBodyAsString(), containsString("\"maxExpectations\""));
    }

    @Test
    public void shouldUpdateConfiguration() {
        // given
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"logLevel\":\"WARN\"}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("\"logLevel\" : \"WARN\""));
    }

    /**
     * Rebuilds the fixture so the {@link HttpState} and the {@link HttpRequestHandler} share ONE
     * {@link org.mockserver.configuration.Configuration} instance, as production wiring does
     * (LifeCycle / PortUnificationHandler pass the same instance to both). The default fixture gives
     * them separate instances, which would hide whether a PUT reconciles the state the server is
     * actually using.
     *
     * @return the shared configuration
     */
    private org.mockserver.configuration.Configuration rebuildWithSharedConfiguration() {
        org.mockserver.configuration.Configuration configuration = configuration();
        httpStateHandler = new HttpState(configuration, new MockServerLogger(), synchronousScheduler());
        httpStates.add(httpStateHandler);
        mockServerHandler = new HttpRequestHandler(configuration, server, httpStateHandler, null);
        embeddedChannel = new EmbeddedChannel(mockServerHandler);
        return configuration;
    }

    /**
     * Blocks until the asynchronous event log has drained the disruptor. {@code httpStateHandler.log}
     * publishes to a ring buffer consumed on another thread, so asserting the log's size straight
     * after logging is racy — it passed standalone and failed under full-suite load (1 of 5 entries
     * consumed). A retrieve completes only after the in-flight entries have been processed.
     */
    private void drainEventLog() {
        java.util.concurrent.CompletableFuture<java.util.List<LogEntry>> future = new java.util.concurrent.CompletableFuture<>();
        httpStateHandler.getMockServerLog().retrieveMessageLogEntries(null, future::complete);
        try {
            future.get(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("timed out draining the event log", e);
        }
    }

    @Test
    public void shouldResizeEventLogWhenConfigurationUpdateReducesMaxLogEntries() {
        // given - a server sharing one Configuration, holding more log entries than the new bound
        org.mockserver.configuration.Configuration configuration = rebuildWithSharedConfiguration();
        for (int i = 0; i < 5; i++) {
            httpStateHandler.log(new LogEntry().setHttpRequest(request("request_" + i)).setType(RECEIVED_REQUEST));
        }
        drainEventLog();
        assertThat(httpStateHandler.getMockServerLog().size(), is(5));

        // when - maxLogEntries is reduced over the control plane
        embeddedChannel.writeInbound(request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"maxLogEntries\":2}"));

        // then - the PUT succeeds AND the running event log is actually resized (previously the
        // value was accepted, echoed back and then ignored)
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(configuration.maxLogEntries(), is(2));
        drainEventLog();
        assertThat(httpStateHandler.getMockServerLog().size(), is(2));
    }

    @Test
    public void shouldResizeExpectationStoreWhenConfigurationUpdateReducesMaxExpectations() {
        // given - a server sharing one Configuration, holding more expectations than the new bound
        rebuildWithSharedConfiguration();
        for (int i = 0; i < 4; i++) {
            httpStateHandler.getRequestMatchers().add(
                new Expectation(request("/path_" + i)).thenRespond(response("body_" + i)),
                org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API);
        }
        assertThat(httpStateHandler.getRequestMatchers().retrieveActiveExpectations(null).size(), is(4));

        // when
        embeddedChannel.writeInbound(request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"maxExpectations\":2}"));

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpStateHandler.getRequestMatchers().retrieveActiveExpectations(null).size(), is(2));
    }

    @Test
    public void shouldEchoValueInForceForInitOnlyRingBufferSize() {
        // given - ringBufferSize cannot be resized on a running server
        rebuildWithSharedConfiguration();
        int inForce = httpStateHandler.getMockServerLog().getRingBufferSizeInForce();

        // when - a PUT explicitly supplies a different value
        embeddedChannel.writeInbound(request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"ringBufferSize\":" + (inForce * 2) + "}"));

        // then - the request still succeeds (no hard rejection), but the echoed configuration
        // reports the value actually IN FORCE rather than the ignored value that was supplied
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("\"ringBufferSize\" : " + inForce));
        assertThat(httpResponse.getBodyAsString(), not(containsString("\"ringBufferSize\" : " + (inForce * 2))));
    }

    @Test
    public void shouldRejectConfigurationWhenAuthEnabled() {
        // given
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        HttpRequest configRequest = request("/mockserver/configuration").withMethod("GET");

        // when
        embeddedChannel.writeInbound(configRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(401));
        assertThat(httpResponse.getBodyAsString(), containsString("Unauthorized for control plane"));
    }

    @Test
    public void shouldRejectConfigurationUpdateWhenAuthEnabled() {
        // given
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"logLevel\":\"WARN\"}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(401));
    }

    @Test
    public void shouldForbidConfigurationUpdateForReadOnlyPrincipalWhenAuthorizationEnabled() {
        // given - authorization enabled with a read-only principal; PUT /configuration mutates
        // live config, so it must be classified MUTATE and rejected with 403 (not 401) — the
        // Netty-layer /configuration route now takes the same authz decision as HttpState.handle
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("viewer", java.util.Set.of("viewers"));
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"logLevel\":\"WARN\"}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(403));
        assertThat(httpResponse.getBodyAsString(), containsString("Forbidden for control plane"));
    }

    @Test
    public void shouldAllowConfigurationReadForReadOnlyPrincipalWhenAuthorizationEnabled() {
        // given - GET /configuration is a READ, so a read-only principal is allowed
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("viewer", java.util.Set.of("viewers"));
        HttpRequest configRequest = request("/mockserver/configuration").withMethod("GET");

        // when
        embeddedChannel.writeInbound(configRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("\"logLevel\""));
    }

    @Test
    public void shouldAllowConfigurationUpdateForMutatePrincipalWhenAuthorizationEnabled() {
        // given - a mutate-role principal may PUT /configuration
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("qa", java.util.Set.of("qa-team"));
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"logLevel\":\"WARN\"}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("\"logLevel\" : \"WARN\""));
    }

    @Test
    public void shouldAllowConfigurationUpdateForAdminPrincipalWhenAuthorizationEnabled() {
        // given - an admin-role principal may PUT /configuration
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("root", java.util.Set.of("platform-admins"));
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"logLevel\":\"WARN\"}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("\"logLevel\" : \"WARN\""));
    }

    @Test
    public void shouldAllowConfigurationUpdateForReadOnlyPrincipalWhenAuthorizationDisabled() {
        // given - authorization disabled: byte-for-byte Wave-1 behaviour, an authenticated
        // read-only principal can still PUT /configuration (authn only, no authz)
        rebuildHttpStateWithAuthorization(false);
        authenticateWithScopes("viewer", java.util.Set.of("viewers"));
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"logLevel\":\"WARN\"}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(200));
        assertThat(httpResponse.getBodyAsString(), containsString("\"logLevel\" : \"WARN\""));
    }

    @Test
    public void shouldRejectDashboardWhenControlPlaneAuthEnabledAndNotAuthenticated() {
        // given - control-plane auth configured but the request carries no/invalid creds. The
        // dashboard streams all captured traffic, so it must take the same gate as /configuration.
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> false);
        HttpRequest dashboardRequest = request("/mockserver/dashboard").withMethod("GET");

        // when
        embeddedChannel.writeInbound(dashboardRequest);

        // then - 401 and the dashboard content is NOT served
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(401));
        assertThat(httpResponse.getBodyAsString(), containsString("Unauthorized for control plane"));
    }

    @Test
    public void shouldServeDashboardWhenControlPlaneAuthEnabledAndAuthenticated() {
        // given - control-plane auth configured and the request is authenticated
        httpStateHandler.setControlPlaneAuthenticationHandler(request -> true);
        HttpRequest dashboardRequest = request("/mockserver/dashboard").withMethod("GET");

        // when
        embeddedChannel.writeInbound(dashboardRequest);

        // then - the gate lets it reach the dashboard handler
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getBodyAsString(), httpResponse.getStatusCode(), is(expectedDashboardStatus()));
    }

    @Test
    public void shouldServeDashboardWhenNoControlPlaneAuthConfigured() {
        // given - default config: NO control-plane auth handler set. The shared gate is a no-op
        // (returns true), so the dashboard stays open with no credentials (non-breaking default).
        HttpRequest dashboardRequest = request("/mockserver/dashboard").withMethod("GET");

        // when
        embeddedChannel.writeInbound(dashboardRequest);

        // then - served by the dashboard handler, not an auth challenge
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getBodyAsString(), httpResponse.getStatusCode(), is(expectedDashboardStatus()));
    }

    @Test
    public void shouldAllowDashboardForReadOnlyPrincipalWhenAuthorizationEnabled() {
        // given - authorization enabled with a verified READ-only principal; GET /dashboard is a
        // READ, so a read-only control-plane role may view the dashboard
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("viewer", java.util.Set.of("viewers"));
        HttpRequest dashboardRequest = request("/mockserver/dashboard").withMethod("GET");

        // when
        embeddedChannel.writeInbound(dashboardRequest);

        // then - allowed through to the dashboard handler
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getBodyAsString(), httpResponse.getStatusCode(), is(expectedDashboardStatus()));
    }

    // The dashboard handler serves index.html with 200 when the build-ui bundle is on the test
    // classpath and 404 when it is not, so the exact expected status is derived from the classpath.
    private static int expectedDashboardStatus() {
        return DashboardHandler.class.getResource("/org/mockserver/dashboard/index.html") != null ? 200 : 404;
    }

    @Test
    public void shouldForbidDashboardForUnmappedPrincipalWhenAuthorizationEnabled() {
        // given - authorization enabled with a verified principal whose scopes map to NO role;
        // fail-closed, so even a READ (the dashboard) is denied with 403
        rebuildHttpStateWithAuthorization(true);
        authenticateWithScopes("stranger", java.util.Set.of("no-mapped-group"));
        HttpRequest dashboardRequest = request("/mockserver/dashboard").withMethod("GET");

        // when
        embeddedChannel.writeInbound(dashboardRequest);

        // then - 403 and the dashboard content is NOT served
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(403));
        assertThat(httpResponse.getBodyAsString(), containsString("Forbidden for control plane"));
    }

    /**
     * Rebuilds {@link #httpStateHandler} (and re-wires the embedded channel handler) with a
     * configuration that enables/disables control-plane authorization and a fixed scope
     * mapping, so the Netty-layer /configuration route exercises the shared authz gate.
     */
    private void rebuildHttpStateWithAuthorization(boolean authorizationEnabled) {
        java.util.Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> mapping = new java.util.LinkedHashMap<>();
        mapping.put("platform-admins", org.mockserver.authentication.authorization.ControlPlaneRole.ADMIN);
        mapping.put("qa-team", org.mockserver.authentication.authorization.ControlPlaneRole.MUTATE);
        mapping.put("viewers", org.mockserver.authentication.authorization.ControlPlaneRole.READ);
        org.mockserver.configuration.Configuration configuration = configuration()
            .controlPlaneAuthorizationEnabled(authorizationEnabled)
            .controlPlaneScopeMapping(mapping);
        httpStateHandler = new HttpState(configuration, new MockServerLogger(), synchronousScheduler());
        httpStates.add(httpStateHandler);
        mockServerHandler = new HttpRequestHandler(configuration, server, httpStateHandler, null);
        embeddedChannel = new EmbeddedChannel(mockServerHandler);
    }

    /**
     * A mock {@link Scheduler} whose {@code submit(Runnable)} runs the task inline.
     * The control-plane handler offloads the optimisation-report build off the event
     * loop via {@code httpState.getScheduler().submit(...)}; in tests we must execute
     * that runnable so the response is written and {@code readOutbound()} can observe
     * it. All other scheduler methods remain no-op mocks.
     */
    private Scheduler synchronousScheduler() {
        Scheduler scheduler = mock(Scheduler.class);
        doAnswer(invocation -> {
            invocation.getArgument(0, Runnable.class).run();
            return null;
        }).when(scheduler).submit(any(Runnable.class));
        return scheduler;
    }

    private void authenticateWithScopes(String principal, java.util.Set<String> scopes) {
        httpStateHandler.setControlPlaneAuthenticationHandler(new org.mockserver.authentication.AuthenticationHandler() {
            @Override
            public boolean controlPlaneRequestAuthenticated(HttpRequest request) {
                return true;
            }

            @Override
            public org.mockserver.authentication.AuthenticationResult authenticate(HttpRequest request) {
                return org.mockserver.authentication.AuthenticationResult.authenticated(principal, "verified-oidc", java.util.Map.of("sub", principal), scopes);
            }
        });
    }

    @Test
    public void shouldReturnBadRequestForInvalidConfigurationUpdate() {
        // given
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{invalid json}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(400));
    }

    @Test
    public void shouldReturnValidationErrorForInvalidConfigurationValues() {
        // given
        HttpRequest updateRequest = request("/mockserver/configuration")
            .withMethod("PUT")
            .withBody("{\"logLevel\": \"INVALID_LEVEL\"}");

        // when
        embeddedChannel.writeInbound(updateRequest);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat(httpResponse.getStatusCode(), is(400));
        assertThat(httpResponse.getBodyAsString(), containsString("Invalid logLevel"));
    }

    @Test
    public void shouldProxyRequestsWhenNotProxying() {
        // given
        HttpRequest request = request("request_one");
        InetSocketAddress remoteAddress = new InetSocketAddress(1090);
        embeddedChannel.attr(LOCAL_HOST_HEADERS).set(ImmutableSet.of(
            "local_address:666",
            "localhost:666",
            "127.0.0.1:666"
        ));
        embeddedChannel.attr(PROXYING).set(false);
        embeddedChannel.attr(REMOTE_SOCKET).set(remoteAddress);

        // when
        embeddedChannel.writeInbound(request);

        // then
        verify(mockActionHandler).processAction(
            eq(request),
            any(NettyResponseWriter.class),
            any(ChannelHandlerContext.class),
            eq(ImmutableSet.of(
                "local_address:666",
                "localhost:666",
                "127.0.0.1:666"
            )),
            eq(false),
            eq(false),
            eq(true));
    }

    @Test
    public void shouldAnswerWithServerErrorWhenProcessingTheActionThrows() {
        // given
        HttpRequest request = request("request_one").withHeader("origin", "https://elsewhere.example");
        embeddedChannel.attr(LOCAL_HOST_HEADERS).set(ImmutableSet.of("localhost:666"));
        embeddedChannel.attr(PROXYING).set(false);
        doThrow(new IllegalStateException("broken action")).when(mockActionHandler).processAction(
            any(HttpRequest.class), any(), any(), any(), anyBoolean(), anyBoolean(), eq(true));

        // when
        embeddedChannel.writeInbound(request);

        // then
        HttpResponse httpResponse = embeddedChannel.readOutbound();
        assertThat("the client is answered rather than left waiting on an abandoned exchange", httpResponse.getStatusCode(), is(500));
        assertThat(httpResponse.getBodyAsString(), startsWith(ControlPlaneFailureResponse.UNEXPECTED_FAILURE_MESSAGE));
        assertThat("the fault's own text is not sent", httpResponse.getBodyAsString(), not(containsString("broken action")));
        assertThat(httpResponse.getFirstHeader("content-type"), is("text/plain; charset=utf-8"));
        assertThat("a mock response gets CORS headers only when enableCORSForAllResponses is on",
            httpResponse.containsHeader("access-control-allow-origin") || httpResponse.containsHeader("access-control-allow-credentials"), is(false));
    }
}

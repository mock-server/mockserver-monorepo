package org.mockserver.mock.action.http;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.closurecallback.websocketregistry.WebSocketRequestCallback;
import org.mockserver.closurecallback.websocketregistry.WebSocketResponseCallback;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.metrics.MetricsLock;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher;
import org.mockserver.mock.breakpoint.BreakpointMatcherRegistry;
import org.mockserver.mock.breakpoint.BreakpointPhase;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.time.FixedTime;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.log.model.LogEntryMessages.FORWARD_FAILURE_MESSAGE_FORMAT;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for REQUEST- and RESPONSE-phase breakpoints wired through
 * {@link HttpActionHandler} on the matched-forward, matched-mock-response, and
 * unmatched-404 dispatch paths. Uses the same Mockito harness as
 * {@link HttpActionHandlerForwardChaosTest}, with a mocked
 * {@link WebSocketClientRegistry} standing in for a connected callback client so
 * the breakpoint dispatch returns a resolvable future. Resolution is driven by
 * invoking the registered callback handlers (CONTINUE / MODIFY / ABORT); async
 * writes are awaited with Mockito {@code timeout(...)} verification.
 */
public class HttpActionHandlerBreakpointTest {

    private static final String CLIENT_ID = "dashboard-client-1";

    private static Scheduler scheduler;
    @Mock
    private HttpResponseActionHandler mockHttpResponseActionHandler;
    @Mock
    private HttpResponseTemplateActionHandler mockHttpResponseTemplateActionHandler;
    @Mock
    private HttpResponseClassCallbackActionHandler mockHttpResponseClassCallbackActionHandler;
    @Mock
    private HttpResponseObjectCallbackActionHandler mockHttpResponseObjectCallbackActionHandler;
    @Mock
    private HttpForwardActionHandler mockHttpForwardActionHandler;
    @Mock
    private HttpForwardTemplateActionHandler mockHttpForwardTemplateActionHandler;
    @Mock
    private HttpForwardClassCallbackActionHandler mockHttpForwardClassCallbackActionHandler;
    @Mock
    private HttpForwardObjectCallbackActionHandler mockHttpForwardObjectCallbackActionHandler;
    @Mock
    private HttpOverrideForwardedRequestActionHandler mockHttpOverrideForwardedRequestActionHandler;
    @Mock
    private HttpForwardValidateActionHandler mockHttpForwardValidateActionHandler;
    @Mock
    private HttpErrorActionHandler mockHttpErrorActionHandler;
    @Mock
    private ResponseWriter mockResponseWriter;
    @Mock
    private MockServerLogger mockServerLogger;
    @Spy
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
    @Mock
    private NettyHttpClient mockNettyHttpClient;
    @Mock
    private WebSocketClientRegistry mockWebSocketClientRegistry;
    private HttpState mockHttpStateHandler;
    @InjectMocks
    private HttpActionHandler actionHandler;
    private Configuration configuration;

    @ClassRule
    public static final FixedTime fixedTime = new FixedTime();

    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    @AfterClass
    public static void stopScheduler() {
        scheduler.shutdown();
    }

    @Before
    public void setupMocks() {
        Metrics.resetAdditionalMetricsForTesting();
        BreakpointMatcherRegistry.getInstance().clear();
        BreakpointCallbackDispatcher.getInstance().reset();
        configuration = configuration().logLevel(Level.INFO).breakpointTimeoutMillis(30000L).breakpointMaxHeld(50);

        mockHttpStateHandler = mock(HttpState.class);
        scheduler = spy(new Scheduler(configuration, mockServerLogger));
        when(mockHttpStateHandler.getScheduler()).thenReturn(scheduler);
        when(mockHttpStateHandler.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer_" + UUIDService.getUUID());
        when(mockHttpStateHandler.getCrudDispatcher()).thenReturn(new CrudDispatcher());
        actionHandler = new HttpActionHandler(configuration, null, mockHttpStateHandler, null, null);

        openMocks(this);
        // NOTE: @Mock fields are only initialised by openMocks(this); stub the registry getter AFTER it so
        // getWebSocketClientRegistry() returns the real mock (not null). The handler reads it at request time.
        when(mockHttpStateHandler.getWebSocketClientRegistry()).thenReturn(mockWebSocketClientRegistry);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        // a connected callback client — every dispatch succeeds and returns a resolvable future
        when(mockWebSocketClientRegistry.sendClientMessage(anyString(), any(HttpRequest.class), any())).thenReturn(true);
    }

    @After
    public void cleanup() {
        BreakpointMatcherRegistry.getInstance().clear();
        BreakpointCallbackDispatcher.getInstance().reset();
    }

    private void registerBreakpoint(String path, BreakpointPhase phase) {
        BreakpointMatcherRegistry.getInstance().register(
            request().withPath(path), EnumSet.of(phase), CLIENT_ID, configuration, mockServerLogger);
    }

    private void registerResponseConditionalBreakpoint(String path, Integer statusMin, Integer statusMax, String bodyContains) {
        BreakpointMatcherRegistry.getInstance().register(
            request().withPath(path), EnumSet.of(BreakpointPhase.RESPONSE), CLIENT_ID, null,
            statusMin, statusMax, bodyContains, configuration, mockServerLogger);
    }

    private WebSocketRequestCallback captureRequestCallback() {
        ArgumentCaptor<WebSocketRequestCallback> captor = ArgumentCaptor.forClass(WebSocketRequestCallback.class);
        verify(mockWebSocketClientRegistry, timeout(2000)).registerForwardCallbackHandler(anyString(), captor.capture());
        return captor.getValue();
    }

    private WebSocketResponseCallback captureResponseCallback() {
        ArgumentCaptor<WebSocketResponseCallback> captor = ArgumentCaptor.forClass(WebSocketResponseCallback.class);
        verify(mockWebSocketClientRegistry, timeout(2000)).registerResponseCallbackHandler(anyString(), captor.capture());
        return captor.getValue();
    }

    private HttpForwardActionResult completedForwardResult(HttpResponse upstreamResponse) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(upstreamResponse);
        return new HttpForwardActionResult(request(), future, null, null);
    }

    // -------------------------------------------------------------------
    // Matched forward — REQUEST phase
    // -------------------------------------------------------------------

    @Test
    public void requestBreakpointOnMatchedForwardContinuesToForward() {
        HttpRequest request = request("/api/forward");
        HttpResponse upstreamResponse = response("upstream body").withStatusCode(200);
        Expectation expectation = new Expectation(request).thenForward(forward().withHost("localhost").withPort(1090));
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenReturn(completedForwardResult(upstreamResponse));
        registerBreakpoint("/api/forward", BreakpointPhase.REQUEST);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        // forward handler must NOT run until the breakpoint is resolved
        verify(mockHttpForwardActionHandler, never()).handle(any(HttpForward.class), any(HttpRequest.class));
        // resolve CONTINUE (client replies with an error/empty → continueOriginal)
        captureRequestCallback().handleError(response());

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("upstream body"));
        verify(mockHttpForwardActionHandler, times(1)).handle(any(HttpForward.class), any(HttpRequest.class));
    }

    @Test
    public void requestBreakpointOnMatchedForwardAbortsWithoutForwarding() {
        HttpRequest request = request("/api/forward");
        Expectation expectation = new Expectation(request).thenForward(forward().withHost("localhost").withPort(1090));
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        registerBreakpoint("/api/forward", BreakpointPhase.REQUEST);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        // ABORT: client replies with an HttpResponse
        captureResponseCallback().handle(response().withStatusCode(418).withBody("aborted"));

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(418));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("aborted"));
        verify(mockHttpForwardActionHandler, never()).handle(any(HttpForward.class), any(HttpRequest.class));
    }

    // -------------------------------------------------------------------
    // Matched mock response — REQUEST and RESPONSE phases
    // -------------------------------------------------------------------

    @Test
    public void requestBreakpointOnMatchedMockResponseContinues() {
        HttpRequest request = request("/api/mock");
        HttpResponse mockResponse = response("mock body").withStatusCode(201);
        Expectation expectation = new Expectation(request).thenRespond(mockResponse);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(mockResponse);
        registerBreakpoint("/api/mock", BreakpointPhase.REQUEST);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        verify(mockHttpResponseActionHandler, never()).handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class));
        captureRequestCallback().handleError(response()); // CONTINUE

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("mock body"));
    }

    @Test
    public void responseBreakpointOnMatchedMockResponseModifies() {
        HttpRequest request = request("/api/mock");
        HttpResponse mockResponse = response("mock body").withStatusCode(201);
        Expectation expectation = new Expectation(request).thenRespond(mockResponse);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(mockResponse);
        registerBreakpoint("/api/mock", BreakpointPhase.RESPONSE);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        // RESPONSE phase: client replies with the (modified) response
        captureResponseCallback().handle(response("modified body").withStatusCode(202));

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(202));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("modified body"));
    }

    @Test
    public void responseConditionalBreakpointPausesWhenStatusConditionMatches() {
        HttpRequest request = request("/api/mock");
        // mock response is a 500 — matches the >=500 response condition
        HttpResponse mockResponse = response("boom").withStatusCode(500);
        Expectation expectation = new Expectation(request).thenRespond(mockResponse);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(mockResponse);
        registerResponseConditionalBreakpoint("/api/mock", 500, null, null);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        // condition matched → exchange paused → WS dispatch happened; resolve MODIFY
        captureResponseCallback().handle(response("handled").withStatusCode(200));

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(200));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("handled"));
    }

    @Test
    public void responseConditionalBreakpointDoesNotPauseWhenStatusConditionFails() {
        HttpRequest request = request("/api/mock");
        // mock response is a 200 — does NOT match the >=500 response condition
        HttpResponse mockResponse = response("ok body").withStatusCode(200);
        Expectation expectation = new Expectation(request).thenRespond(mockResponse);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(mockResponse);
        registerResponseConditionalBreakpoint("/api/mock", 500, null, null);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        // condition NOT matched → response written directly, no breakpoint pause
        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(200));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("ok body"));
        // no WS response-callback dispatch should have occurred
        verify(mockWebSocketClientRegistry, never()).registerResponseCallbackHandler(anyString(), any());
    }

    @Test
    public void responseConditionalBreakpointPausesWhenBodyConditionMatches() {
        HttpRequest request = request("/api/mock");
        HttpResponse mockResponse = response("error: quota was exceeded").withStatusCode(200);
        Expectation expectation = new Expectation(request).thenRespond(mockResponse);
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenReturn(mockResponse);
        registerResponseConditionalBreakpoint("/api/mock", null, null, "quota.*exceeded");

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        captureResponseCallback().handle(response("retry later").withStatusCode(429));

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(429));
    }

    // -------------------------------------------------------------------
    // Unmatched 404 — REQUEST and RESPONSE phases
    // -------------------------------------------------------------------

    @Test
    public void requestBreakpointOnUnmatched404Aborts() {
        HttpRequest request = request("/foo");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);
        registerBreakpoint("/foo", BreakpointPhase.REQUEST);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        captureResponseCallback().handle(response().withStatusCode(403).withBody("denied")); // ABORT

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getStatusCode(), is(403));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("denied"));
    }

    @Test
    public void responseBreakpointOnUnmatched404Modifies() {
        HttpRequest request = request("/foo");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);
        registerBreakpoint("/foo", BreakpointPhase.RESPONSE);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);

        captureResponseCallback().handle(response("custom not found").withStatusCode(404));

        ArgumentCaptor<HttpResponse> responseCaptor = ArgumentCaptor.forClass(HttpResponse.class);
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), responseCaptor.capture(), eq(false));
        assertThat(responseCaptor.getValue().getBodyAsString(), is("custom not found"));
    }

    // -------------------------------------------------------------------
    // REQUEST-phase continuation failure — explained against the path's expectation
    // -------------------------------------------------------------------

    @Test
    public void requestBreakpointContinuationFailureOnMatchedForwardIsExplainedAsMatchedByTheForwardExpectation() throws Exception {
        HttpRequest request = (HttpRequest) request("/api/forward").withLogCorrelationId("bp-forward");
        Expectation expectation = new Expectation(request("/api/forward")).withId("forward-expectation").thenForward(forward().withHost("localhost").withPort(1090));
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class))).thenThrow(new RuntimeException("forwarder failed"));
        registerBreakpoint("/api/forward", BreakpointPhase.REQUEST);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);
        captureRequestCallback().handleError(response()); // CONTINUE

        LogEntry failure = awaitContinuationFailure(request);
        assertThat(failure.getExpectationId(), is("forward-expectation"));
        JsonNode arrival = explainArrival(request, failure);
        assertThat(arrival.toString(), arrival.path("outcome").asText(), is("MATCHED"));
        assertThat(arrival.path("expectationId").asText(), is("forward-expectation"));
    }

    @Test
    public void requestBreakpointContinuationFailureOnMatchedMockResponseIsExplainedAsMatchedByTheResponseExpectation() throws Exception {
        HttpRequest request = (HttpRequest) request("/api/mock").withLogCorrelationId("bp-mock");
        Expectation expectation = new Expectation(request("/api/mock")).withId("mock-expectation").thenRespond(response("mock body"));
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(expectation);
        when(mockHttpResponseActionHandler.handle(any(HttpResponse.class), any(HttpRequest.class), any(RequestDefinition.class))).thenThrow(new RuntimeException("responder failed"));
        registerBreakpoint("/api/mock", BreakpointPhase.REQUEST);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);
        captureRequestCallback().handleError(response()); // CONTINUE

        LogEntry failure = awaitContinuationFailure(request);
        assertThat(failure.getExpectationId(), is("mock-expectation"));
        JsonNode arrival = explainArrival(request, failure);
        assertThat(arrival.toString(), arrival.path("outcome").asText(), is("MATCHED"));
        assertThat(arrival.path("expectationId").asText(), is("mock-expectation"));
    }

    @Test
    public void requestBreakpointContinuationFailureOnUnmatched404IsNotExplainedAsMatched() throws Exception {
        HttpRequest request = (HttpRequest) request("/foo").withLogCorrelationId("bp-unmatched");
        when(mockHttpStateHandler.firstMatchingExpectation(request)).thenReturn(null);
        doThrow(new RuntimeException("write failed")).when(mockResponseWriter)
            .writeResponse(eq(request), argThat((HttpResponse written) -> written != null && Integer.valueOf(404).equals(written.getStatusCode())), anyBoolean());
        registerBreakpoint("/foo", BreakpointPhase.REQUEST);

        actionHandler.processAction(request, mockResponseWriter, null, new HashSet<>(), false, false);
        captureRequestCallback().handleError(response()); // CONTINUE

        LogEntry failure = awaitContinuationFailure(request);
        assertThat(failure.getExpectationId(), nullValue());
        JsonNode arrival = explainArrival(request, failure);
        assertThat(arrival.toString(), arrival.path("outcome").asText(), is("NO_MATCH"));
        assertThat(arrival.has("expectationId"), is(false));
    }

    /** The 502 written when the REQUEST-phase continuation throws, and the INFO entry recorded for it. */
    private LogEntry awaitContinuationFailure(HttpRequest request) {
        verify(mockResponseWriter, timeout(2000)).writeResponse(eq(request), argThat((HttpResponse written) -> written != null && Integer.valueOf(502).equals(written.getStatusCode())), eq(false));
        ArgumentCaptor<LogEntry> entries = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeastOnce()).logEvent(entries.capture());
        return entries.getAllValues().stream()
            .filter(entry -> FORWARD_FAILURE_MESSAGE_FORMAT.equals(entry.getMessageFormat()))
            .filter(entry -> String.valueOf(entry.getMessage()).contains("breakpoint continuation failed"))
            .reduce((first, second) -> {
                throw new AssertionError("more than one continuation failure logged");
            })
            .orElseThrow(() -> new AssertionError("no continuation failure logged in " + entries.getAllValues()));
    }

    /** Replays the request and every response entry the handler logged into a real event log, then explains it. */
    private JsonNode explainArrival(HttpRequest request, LogEntry failure) throws Exception {
        MockServerLogger realLogger = new MockServerLogger(configuration, HttpActionHandlerBreakpointTest.class);
        HttpState httpState = new HttpState(configuration, realLogger, new Scheduler(configuration, realLogger, true));
        try {
            httpState.log(new LogEntry().setType(RECEIVED_REQUEST).setLogLevel(Level.INFO).setCorrelationId(request.getLogCorrelationId()).setHttpRequest(request));
            ArgumentCaptor<LogEntry> entries = ArgumentCaptor.forClass(LogEntry.class);
            verify(mockServerLogger, atLeastOnce()).logEvent(entries.capture());
            List<LogEntry> logged = entries.getAllValues();
            assertThat(logged.contains(failure), is(true));
            for (LogEntry entry : logged) {
                if (entry.getType() == LogEntry.LogMessageType.NO_MATCH_RESPONSE || entry.getType() == LogEntry.LogMessageType.EXPECTATION_RESPONSE
                    || entry.getType() == LogEntry.LogMessageType.FORWARDED_REQUEST) {
                    httpState.log(entry);
                }
            }
            HttpRequest explainRequest = request("/mockserver/explain").withMethod("PUT").withQueryStringParameter("correlationId", request.getLogCorrelationId());
            long deadline = System.currentTimeMillis() + 5000;
            HttpResponse explained = httpState.explain(explainRequest);
            while (explained.getStatusCode() == 404 && System.currentTimeMillis() < deadline) {
                Thread.sleep(20);
                explained = httpState.explain(explainRequest);
            }
            assertThat(explained.getBodyAsString(), explained.getStatusCode(), is(200));
            return ObjectMapperFactory.createObjectMapper().readTree(explained.getBodyAsString()).path("atArrival");
        } finally {
            httpState.stop();
        }
    }
}

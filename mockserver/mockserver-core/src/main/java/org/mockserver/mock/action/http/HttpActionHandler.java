package org.mockserver.mock.action.http;

import org.mockserver.authentication.ProxyAuthenticationValidator;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.util.AttributeKey;
import org.apache.commons.text.StringEscapeUtils;
import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.file.FileBodyException;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.grpc.GrpcForwardTranslator;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.httpclient.ClientConfigurationException;
import org.mockserver.httpclient.HeaderLimitExceededException;
import org.mockserver.httpclient.UndecodableResponseException;
import org.mockserver.httpclient.UpstreamProxyUnreachableException;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.httpclient.SocketCommunicationException;
import org.mockserver.log.model.DeferredLogArgument;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.llm.client.LlmStreamUsageScanner;
import org.mockserver.model.*;
import org.mockserver.model.StreamingBody;
import org.mockserver.openapi.OpenAPIRequestValidator;
import org.mockserver.openapi.OpenAPIResponseValidator;
import org.mockserver.openapi.OpenApiRuntimeExpressionResolver;
import org.mockserver.proxyconfiguration.ForwardTargetBlockedException;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.mockserver.proxyconfiguration.NoProxyHostsUtils;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.responsewriter.GrpcStreamResponseWriter;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.responsewriter.StreamErrorWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.curl.HttpRequestToCurlSerializer;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.telemetry.GenAiSpans;
import org.mockserver.telemetry.RequestSpans;
import org.mockserver.telemetry.TraceContextAttributes;
import org.mockserver.telemetry.W3CTraceContext;
import org.slf4j.event.Level;

import javax.net.ssl.SSLException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.UnaryOperator;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static io.netty.handler.codec.http.HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.exception.ExceptionHandling.*;
import static org.mockserver.log.model.LogEntry.LogMessageType.*;
import static org.mockserver.log.model.LogEntryMessages.*;
import static org.mockserver.model.HttpResponse.badGatewayResponse;
import static org.mockserver.model.HttpResponse.notFoundResponse;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.scheduler.Scheduler.delayOptional;
import static org.mockserver.scheduler.Scheduler.rejectable;
import static org.mockserver.scheduler.Scheduler.sheddable;
import static org.mockserver.model.HttpStatusCode.SERVICE_UNAVAILABLE_503;
import static org.mockserver.model.NottableString.string;
import static org.slf4j.event.Level.TRACE;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"rawtypes", "FieldMayBeFinal"})
public class HttpActionHandler {

    public static final AttributeKey<InetSocketAddress> REMOTE_SOCKET = AttributeKey.valueOf("REMOTE_SOCKET");

    /**
     * Internal header carrying the upstream round-trip time (in milliseconds) on the LOGGED
     * {@code FORWARDED_REQUEST} response only. Mirrors the {@code x-mockserver-streamed} /
     * {@code x-mockserver-chunk-delays-ms} convention: it is attached to a clone stored in the
     * event log and is NEVER written to the real client. The LLM optimisation report reads it to
     * populate per-call upstream latency.
     */
    public static final String RESPONSE_TIME_HEADER = "x-mockserver-response-time-ms";

    private final Configuration configuration;
    private final HttpState httpStateHandler;
    // Loop-prevention header name/value as NottableStrings, converted once (they are constant for the
    // life of this handler) so the per-request containsEntry check in processAction does not allocate
    // two NottableStrings on every received request.
    private final NottableString loopPreventionHeaderName;
    private final NottableString loopPreventionHeaderValue;
    private final Scheduler scheduler;
    // Drift analyses submitted but not yet finished (queued or running); bounded by maxPendingDriftAnalyses().
    private final AtomicInteger pendingDriftAnalyses = new AtomicInteger();
    static final int MIN_PENDING_DRIFT_ANALYSES = 16;
    static final int PENDING_DRIFT_ANALYSES_PER_THREAD = 4;
    private MockServerLogger mockServerLogger;
    private HttpResponseActionHandler httpResponseActionHandler;
    private FileBodyMaterialiser fileBodyMaterialiser;
    private HttpResponseTemplateActionHandler httpResponseTemplateActionHandler;
    private HttpResponseClassCallbackActionHandler httpResponseClassCallbackActionHandler;
    private HttpResponseObjectCallbackActionHandler httpResponseObjectCallbackActionHandler;
    private HttpForwardActionHandler httpForwardActionHandler;
    private HttpForwardTemplateActionHandler httpForwardTemplateActionHandler;
    private HttpForwardClassCallbackActionHandler httpForwardClassCallbackActionHandler;
    private HttpForwardObjectCallbackActionHandler httpForwardObjectCallbackActionHandler;
    private HttpOverrideForwardedRequestActionHandler httpOverrideForwardedRequestCallbackActionHandler;
    private HttpForwardValidateActionHandler httpForwardValidateActionHandler;
    private HttpForwardWithFallbackActionHandler httpForwardWithFallbackActionHandler;
    private HttpSseResponseActionHandler httpSseResponseActionHandler;
    private HttpLlmResponseActionHandler httpLlmResponseActionHandler;
    private HttpWebSocketResponseActionHandler httpWebSocketResponseActionHandler;
    private GrpcStreamResponseActionHandler grpcStreamResponseActionHandler;
    private HttpErrorActionHandler httpErrorActionHandler;
    private org.mockserver.templates.engine.DelayTemplateResolver delayTemplateResolver;
    private final FinalInformationalResponseWarning finalInformationalResponseWarning = new FinalInformationalResponseWarning();

    // forwarding
    private NettyHttpClient httpClient;
    private final NettySslContextFactory nettySslContextFactory;
    private WebSocketProxyRelayHandler webSocketProxyRelayHandler;
    private HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    private HttpRequestToCurlSerializer httpRequestToCurlSerializer;
    private final org.mockserver.metrics.Metrics metrics;
    private final java.util.function.Function<HttpRequest, java.util.concurrent.CompletableFuture<HttpResponse>> requestSender = request -> {
        try {
            checkOutboundTarget(request);
        } catch (ForwardTargetBlockedException blocked) {
            return java.util.concurrent.CompletableFuture.failedFuture(blocked);
        }
        return getHttpClient().sendRequest(request);
    };

    /**
     * @return the shared {@link Scheduler}. Exposed to the (same-package) local object-callback handlers
     * so they can dispatch a potentially-blocking LOCAL callback off the server worker event loop via
     * {@link Scheduler#scheduleLocalCallback} (the root fix for the pool-on-by-default self-deadlock).
     */
    Scheduler getScheduler() {
        return scheduler;
    }

    public HttpActionHandler(Configuration configuration, java.util.function.Supplier<EventLoopGroup> eventLoopGroupSupplier, HttpState httpStateHandler, List<ProxyConfiguration> proxyConfigurations, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.httpStateHandler = httpStateHandler;
        this.loopPreventionHeaderName = string(httpStateHandler.getUniqueLoopPreventionHeaderName());
        this.loopPreventionHeaderValue = string(httpStateHandler.getUniqueLoopPreventionHeaderValue());
        this.scheduler = httpStateHandler.getScheduler();
        this.mockServerLogger = httpStateHandler.getMockServerLogger();
        this.httpRequestToCurlSerializer = new HttpRequestToCurlSerializer(mockServerLogger);
        // The event-loop group is supplied lazily (resolved on first forward) so a pure-mock server
        // never triggers creation of the disjoint forward-client group. See NettyHttpClient and
        // LifeCycle#getForwardClientEventLoopGroup().
        this.httpClient = new NettyHttpClient(configuration, mockServerLogger, eventLoopGroupSupplier, proxyConfigurations, true, nettySslContextFactory);
        this.nettySslContextFactory = nettySslContextFactory;
        this.metrics = new org.mockserver.metrics.Metrics(configuration);
    }

    private WebSocketProxyRelayHandler getWebSocketProxyRelayHandler() {
        if (webSocketProxyRelayHandler == null) {
            webSocketProxyRelayHandler = new WebSocketProxyRelayHandler(configuration, mockServerLogger, nettySslContextFactory);
        }
        return webSocketProxyRelayHandler;
    }

    /**
     * Attempt to relay a WebSocket upgrade request straight through to a real upstream server (passthrough proxy).
     * Returns {@code true} when the request is a WebSocket upgrade and an upstream address could be resolved, in
     * which case the relay takes over the channel; {@code false} otherwise so the caller falls back to normal HTTP
     * forwarding / matching. Only ever invoked with a live Netty {@code ctx} (never in WAR deployments).
     */
    private boolean attemptWebSocketPassthrough(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx,
                                                final String forwardHost, final Integer forwardPort, final Boolean forwardTls) {
        if (ctx == null || !WebSocketProxyRelayHandler.isWebSocketUpgrade(request)) {
            return false;
        }
        String host;
        int port;
        boolean tls;
        if (isNotBlank(forwardHost)) {
            host = forwardHost;
            port = forwardPort != null ? forwardPort : (Boolean.TRUE.equals(forwardTls) ? 443 : 80);
            tls = Boolean.TRUE.equals(forwardTls);
        } else {
            InetSocketAddress remoteAddress = getRemoteAddressWithFallback(ctx);
            tls = Boolean.TRUE.equals(request.isSecure());
            if (remoteAddress != null) {
                host = remoteAddress.getHostString();
                port = remoteAddress.getPort();
            } else {
                // reverse-proxy by Host header (SUT points its WebSocket client at MockServer as a proxy)
                String hostHeader = request.getFirstHeader(HOST.toString());
                if (isEmpty(hostHeader)) {
                    return false;
                }
                String[] hostParts = HttpRequest.splitHostPort(hostHeader);
                host = hostParts[0];
                if (hostParts.length > 1) {
                    try {
                        port = Integer.parseInt(hostParts[1]);
                    } catch (NumberFormatException e) {
                        // malformed Host header port — fall back to normal forwarding rather than relaying
                        return false;
                    }
                } else {
                    port = tls ? 443 : 80;
                }
            }
        }
        if (isEmpty(host)) {
            return false;
        }
        getWebSocketProxyRelayHandler().relay(request, ctx, host, port, tls, responseWriter.respondingDirectly());
        return true;
    }

    /**
     * Dispatch an early-matched expectation before the request body has been received.
     * Supports {@link Action.Type#RESPONSE} and {@link Action.Type#ERROR} only; other action types
     * are rejected by {@code RequestMatchers.validateRespondBeforeBody} at expectation-add time.
     */
    public void processEarlyAction(final HttpRequest request, final Expectation expectation, final ChannelHandlerContext ctx, final ResponseWriter earlyResponseWriter, final boolean synchronous) {
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(RECEIVED_REQUEST)
                .setLogLevel(Level.INFO)
                .setCorrelationId(request.getLogCorrelationId())
                .setHttpRequest(request)
                .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                .setArguments(request)
        );

        final AtomicBoolean postProcessed = new AtomicBoolean(false);
        Runnable expectationPostProcessor = () -> {
            if (postProcessed.compareAndSet(false, true)) {
                httpStateHandler.postProcess(expectation);
            }
        };

        // force-response-variant: honour the caller-supplied response-sequence index on the early path too.
        // The control header is NOT stripped from the (shared, already-logged) request here — it is omitted
        // only when the outbound forward request is built (MockServerHttpRequestToFullHttpRequest), so
        // recorded traffic deterministically retains it and forwards never carry it.
        final Integer forcedResponseIndex = expectation.forcedResponseIndexFor(request);
        // declarative capture (WS2.2): extract request value(s) into scenario state for early-matched
        // expectations too (header/query/cookie/path sources; body-based sources are typically empty here)
        org.mockserver.mock.CaptureProcessor.process(expectation.getCapture(), request);

        final Action action = expectation.getAction(forcedResponseIndex);
        switch (action.getType()) {
            case RESPONSE -> {
                // capture matchCount before scheduling to avoid race with concurrent requests
                final int capturedMatchCount = expectation.getMatchCount();
                // chaos: gate by the time-based outage window + apply degradation ramp (see effectiveChaos)
                final HttpChaosProfile effectiveChaos = effectiveChaos(expectation);
                final RateLimit rateLimit = expectation.getRateLimit();
                // recovery: apply the same fail-then-succeed selection as the main RESPONSE path (RecoverAfter)
                final HttpResponse selectedResponse = selectRecoveryResponse((HttpResponse) action, expectation, request, capturedMatchCount);
                scheduler.schedule(() -> handleAnyException(request, earlyResponseWriter, synchronous, action, () -> {
                    final HttpResponse response = getHttpResponseActionHandler().handle(selectedResponse, request, expectation.getHttpRequest());
                    // chaos: inject HTTP chaos faults on early mocked responses
                    writeResponseActionResponse(response, earlyResponseWriter, request, action, synchronous, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx, rateLimit);
                }, expectationPostProcessor), synchronous);
            }
            case ERROR -> scheduler.schedule(rejectable(() -> handleAnyException(request, earlyResponseWriter, synchronous, action, () -> {
                dispatchErrorAction((HttpError) action, request, earlyResponseWriter, ctx);
                expectationPostProcessor.run();
            }, expectationPostProcessor), overloadResponse(request, earlyResponseWriter, expectationPostProcessor)), synchronous, combineWithGlobalDelay(getDelayTemplateResolver().resolve(action.getDelay(), request)));
            default ->
                // Other action types are rejected at expectation-add time; nothing to dispatch here.
                expectationPostProcessor.run();
        }
    }

    public void processAction(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, Set<String> localAddresses, boolean proxyingRequest, final boolean synchronous) {
        if (request.getHeaders() == null || !request.getHeaders().containsEntry(loopPreventionHeaderName, loopPreventionHeaderValue)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(RECEIVED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request)
            );
        }

        CrudDispatcher crudDispatcher = httpStateHandler.getCrudDispatcher();
        HttpResponse crudResponse = crudDispatcher.dispatch(request);
        if (crudResponse != null) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXPECTATION_RESPONSE)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(crudResponse)
                    .setMessageFormat("returning CRUD response:{}for request:{}")
                    .setArguments(crudResponse, request)
            );
            responseWriter.writeResponse(request, crudResponse, false);
            return;
        }

        final Expectation expectation = httpStateHandler.firstMatchingExpectation(request);
        final AtomicBoolean postProcessed = new AtomicBoolean(false);
        Runnable expectationPostProcessor = () -> {
            if (postProcessed.compareAndSet(false, true)) {
                httpStateHandler.postProcess(expectation);
                if (expectation != null && expectation.getAfterActions() != null) {
                    for (AfterAction afterAction : expectation.getAfterActions()) {
                        dispatchAfterAction(afterAction, request);
                    }
                }
            }
        };
        final boolean hasConfiguredRemoteProxy = isNotBlank(configuration.proxyRemoteHost()) && configuration.proxyRemotePort() != null;
        final String hostHeader = request.getFirstHeader(HOST.toString());
        final boolean potentiallyHttpProxy = !proxyingRequest && (hasConfiguredRemoteProxy || (configuration.attemptToProxyIfNoMatchingExpectation() && !isEmpty(hostHeader) && !localAddresses.contains(hostHeader) && !NoProxyHostsUtils.isHostOnNoProxyList(hostHeader, configuration.noProxyHosts())));

        if (expectation != null && expectation.getAction() != null) {

            // steps-based dispatch supersedes beforeActions+primary when steps are configured
            if (expectation.getSteps() != null && !expectation.getSteps().isEmpty()) {
                final Expectation matchedExpectation = expectation;
                scheduler.submit(() -> {
                    if (runStepsPreResponder(matchedExpectation, request, responseWriter)) {
                        dispatchPrimaryAction(matchedExpectation, request, responseWriter, ctx, synchronous, () -> {
                            // post-process and dispatch post-responder steps (like after-actions)
                            if (postProcessed.compareAndSet(false, true)) {
                                httpStateHandler.postProcess(matchedExpectation);
                                dispatchPostResponderSteps(matchedExpectation, request);
                                if (matchedExpectation.getAfterActions() != null) {
                                    for (AfterAction afterAction : matchedExpectation.getAfterActions()) {
                                        dispatchAfterAction(afterAction, request);
                                    }
                                }
                            }
                        });
                    } else {
                        expectationPostProcessor.run();
                    }
                }, synchronous);
            } else {
                final List<AfterAction> beforeActions = expectation.getBeforeActions();
                if (beforeActions != null && !beforeActions.isEmpty()) {
                    // run before-actions ahead of the primary action; blocking before-actions may gate
                    // (fail-fast) the response. Wrapped in scheduler.submit so any blocking wait happens
                    // off the event loop (async) or inline (synchronous), mirroring forward-action threading.
                    final Expectation matchedExpectation = expectation;
                    scheduler.submit(() -> {
                        if (runBeforeActions(matchedExpectation, request, responseWriter)) {
                            dispatchPrimaryAction(matchedExpectation, request, responseWriter, ctx, synchronous, expectationPostProcessor);
                        } else {
                            // fail-fast abort: the 502 is the response, so still post-process to clear
                            // responseInProgress, remove exhausted expectations, and fire after-actions
                            // (idempotent via compareAndSet).
                            expectationPostProcessor.run();
                        }
                    }, synchronous);
                } else {
                    dispatchPrimaryAction(expectation, request, responseWriter, ctx, synchronous, expectationPostProcessor);
                }
            }

        } else if (CORSHeaders.isPreflightRequest(configuration, request) && (configuration.enableCORSForAPI() || configuration.enableCORSForAllResponses() || isControlPlanePreflight(request))) {

            responseWriter.writeResponse(request, OK);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(INFO)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("returning CORS response for OPTIONS request")
                );
            }

        } else if (handleProxyPass(request, responseWriter, synchronous)) {

            // handled by proxy pass

        } else if (proxyingRequest || potentiallyHttpProxy) {

            // WebSocket passthrough: a WS upgrade request in proxy mode with no matching mock expectation is
            // relayed straight through to the real upstream server (101 handshake + bidirectional frame relay),
            // rather than being forwarded as a plain (broken) HTTP request. Falls back to normal forwarding when
            // the request is not a WS upgrade or no upstream address can be resolved.
            if (attemptWebSocketPassthrough(request, responseWriter, ctx, null, null, null)) {
                return;
            }
            handleUnmatchedProxyForward(request, responseWriter, ctx, synchronous, potentiallyHttpProxy);

        } else {

            // OpenAI Responses API server-side state: serve GET /v1/responses/{id} from the
            // store of previously-issued responses. Only reached when nothing else matched and
            // the server is not proxying, so user-configured expectations always win; a request
            // for an unknown id returns null here and falls through to the normal 404 path.
            HttpResponse storedResponse = org.mockserver.llm.OpenAiResponsesStore.getInstance()
                .retrievalResponseOrNull(request);
            if (storedResponse != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(storedResponse)
                        .setMessageFormat("returning stored OpenAI Responses API response for request:{}")
                        .setArguments(request)
                );
                responseWriter.writeResponse(request, storedResponse, false);
                return;
            }

            // breakpoint: REQUEST-phase pause on the unmatched-404 path — lets a registered matcher
            // pause / modify / abort even though nothing matched and the server is not proxying.
            if (attemptRequestBreakpoint(request, synchronous, responseWriter, null,
                req -> returnNotFound(responseWriter, req, null, synchronous))) {
                return;
            }
            returnNotFound(responseWriter, request, null, synchronous);

        }
    }

    /**
     * Whether an (unmatched) preflight targets a control-plane / dashboard endpoint, identified by
     * the {@code /mockserver} path prefix the dashboard always uses. Such preflights are answered
     * with a CORS response regardless of {@code enableCORSForAPI}, so the dashboard works
     * cross-origin (e.g. pointed at a different MockServer via its host/port fields) without
     * requiring users to enable CORS explicitly. Scoped to the prefix so unmatched OPTIONS on a
     * user's own mocked paths still fall through to normal matching / not-found handling.
     */
    private boolean isControlPlanePreflight(HttpRequest request) {
        return request.getPath() != null
            && request.getPath().getValue() != null
            && request.getPath().getValue().startsWith(org.mockserver.mock.HttpState.PATH_PREFIX);
    }

    /**
     * Dispatches the matched expectation's primary action to the appropriate per-type handler.
     * Extracted from {@link #processAction} so the high-level request flow stays readable; the
     * action-type switch and secondary-action fan-out live here.
     */
    private void dispatchPrimaryAction(final Expectation expectation, final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, final boolean synchronous, final Runnable expectationPostProcessor) {
        // opt-in (ADV6): an OpenAPI-backed expectation with request validation on is validated against its spec, and
        // a violation rejected with a 400, before the action is dispatched. With request or response validation on,
        // dispatch runs on the scheduler: either may load the spec, which once it has left the cache means a fetch
        // that must not run on the Netty event loop. With both off (the default) behaviour is unchanged.
        final boolean validateRequest = Boolean.TRUE.equals(configuration.validateRequestsAgainstOpenApiSpec());
        if ((validateRequest || Boolean.TRUE.equals(configuration.openAPIResponseValidation()))
            && expectation.getHttpRequest() instanceof OpenAPIDefinition openAPIDefinition
            && isNotBlank(openAPIDefinition.getSpecUrlOrPayload())) {
            scheduler.submit(() -> {
                HttpResponse rejectResponse = validateRequest ? validateMockRequest(openAPIDefinition, request) : null;
                if (rejectResponse != null) {
                    responseWriter.writeResponse(request, rejectResponse, false);
                    expectationPostProcessor.run();
                } else {
                    dispatchPrimaryActionInternal(expectation, request, responseWriter, ctx, synchronous, expectationPostProcessor);
                }
            }, synchronous);
            return;
        }
        dispatchPrimaryActionInternal(expectation, request, responseWriter, ctx, synchronous, expectationPostProcessor);
    }

    /**
     * Validates an incoming request matched by an OpenAPI-backed mock expectation against its spec.
     * Violations are logged as {@code OPENAPI_REQUEST_VALIDATION_FAILED} and a 400 response describing
     * the violations is returned to short-circuit dispatch. Returns {@code null} when the request is
     * valid (or validation could not run) so dispatch proceeds normally.
     *
     * <p>This method may perform an expensive cold-cache OpenAPI parse / JSON-schema validation, so
     * callers MUST invoke it off the Netty event loop (inside a {@code scheduler.submit} block).</p>
     */
    private HttpResponse validateMockRequest(final OpenAPIDefinition openAPIDefinition, final HttpRequest request) {
        try {
            List<String> requestErrors = OpenAPIRequestValidator.validate(openAPIDefinition.getSpecUrlOrPayload(), request, mockServerLogger, null, configuration);
            if (!requestErrors.isEmpty()) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(OPENAPI_REQUEST_VALIDATION_FAILED)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("request matched by OpenAPI-backed expectation does not conform to OpenAPI spec{}errors:{}")
                            .setArguments(request, String.join("; ", requestErrors))
                    );
                }
                return response()
                    .withStatusCode(400)
                    .withBody("OpenAPI request validation failed: " + String.join("; ", requestErrors));
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("failed to validate request against OpenAPI-backed expectation spec{}due to:{}")
                        .setArguments(request, e.getMessage())
                );
            }
        }
        return null;
    }

    /**
     * Dispatches the matched expectation's primary action to the appropriate per-type handler. Extracted
     * from {@link #dispatchPrimaryAction} so the optional OpenAPI request-validation pre-flight can short-circuit
     * dispatch without restructuring the action-type switch.
     */
    private void dispatchPrimaryActionInternal(final Expectation expectation, final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, final boolean synchronous, final Runnable expectationPostProcessor) {
        // force-response-variant: read the caller-supplied 0-based response-sequence index (if any). The
        // control header is deliberately NOT stripped from the shared request object here (it is already
        // referenced by the RECEIVED_REQUEST log entry, and LogEntry stores requests by reference) — that
        // would make recordings non-deterministically lose the header. Instead the header is omitted only
        // when the outbound forward request is built (MockServerHttpRequestToFullHttpRequest), so recorded
        // traffic retains it and forwards never carry it. A null / invalid / out-of-bounds index leaves
        // normal selection in place (see Expectation.getPrimaryAction).
        final Integer forcedResponseIndex = expectation.forcedResponseIndexFor(request);
        // declarative capture (WS2.2): extract value(s) from the matched request into scenario
        // state BEFORE the response is built, so a response template can read them via scenario.get(name)
        org.mockserver.mock.CaptureProcessor.process(expectation.getCapture(), request);
        // fire cross-protocol scenario transitions when this expectation has them
        fireCrossProtocolEvents(expectation, request);
        final Action action = expectation.getAction(forcedResponseIndex);
        // WebSocket passthrough for a matched plain FORWARD expectation: relay the WS upgrade to the forward
        // target (host/port/scheme) instead of forwarding it as a plain HTTP request. Only the plain HttpForward
        // action carries a static host/port/scheme; the template/callback/replace forward variants fall through to
        // their normal handlers (documented boundary).
        if (action instanceof HttpForward && WebSocketProxyRelayHandler.isWebSocketUpgrade(request) && ctx != null) {
            HttpForward forward = (HttpForward) action;
            if (attemptWebSocketPassthrough(request, responseWriter, ctx, forward.getHost(), forward.getPort(),
                forward.getScheme() == HttpForward.Scheme.HTTPS)) {
                expectationPostProcessor.run();
                return;
            }
        }
        // capture matchCount before scheduling to avoid race with concurrent requests
        final int capturedMatchCount = expectation.getMatchCount();
        // chaos: gate by the time-based outage window once per request and apply the
        // degradation ramp (relative to first match, via the controllable clock);
        // outside the window chaos is disabled (see effectiveChaos)
        final HttpChaosProfile effectiveChaos = effectiveChaos(expectation);
        // service-scoped chaos: when a matched FORWARD expectation carries no chaos of its own,
        // fall back to a host-scoped profile (keyed by the request Host header) registered via
        // PUT /mockserver/serviceChaos. An expectation-level chaos profile always takes precedence
        // (even when currently gated off by its outage window). The anonymous/unmatched proxy
        // fall-through path is intentionally not affected.
        final HttpChaosProfile forwardChaos = (effectiveChaos == null && expectation.getChaos() == null && action.getType().name().startsWith("FORWARD"))
            ? ServiceChaosRegistry.getInstance().get(request.getFirstHeader("host"))
            : effectiveChaos;
        // rate limit (declarative, protocol-agnostic): threaded into the RESPONSE / RESPONSE_TEMPLATE /
        // RESPONSE_CLASS_CALLBACK and FORWARD-family write paths so the over-limit 429 is produced once
        // per matched request. Null (no clause) leaves the normal response byte-for-byte unchanged.
        final RateLimit rateLimit = expectation.getRateLimit();
        // WS2.3: resolve an opt-in template delay (duration computed from the request) into a concrete
        // millisecond delay here, where the request is in scope, so it applies to every action type's
        // scheduled delay below. Non-template (static/distribution) delays resolve to themselves, so
        // existing behaviour is byte-for-byte preserved. For the static RESPONSE action the response's own
        // delay (which may differ from the action delay) is additionally resolved in writeResponseActionResponse.
        final Delay actionDelay = getDelayTemplateResolver().resolve(action.getDelay(), request);
        switch (action.getType()) {
            // breakpoint: REQUEST-phase pause gates each mock-response dispatch; chaos faults and the
            // RESPONSE-phase breakpoint are applied inside writeResponseActionResponse. MODIFY feeds the
            // modified request into template/class-callback generation; logging keys off the original request.
            case RESPONSE -> {
                // recovery: serve the failure response for the first K matches, then the configured
                // success response (RecoverAfter). Selection is per-expectation off capturedMatchCount,
                // or per-(expectationId, idempotency-key) when an idempotencyHeader is configured.
                final HttpResponse selectedResponse = selectRecoveryResponse((HttpResponse) action, expectation, request, capturedMatchCount);
                scheduler.schedule(() -> handleAnyException(request, responseWriter, synchronous, action, () ->
                    dispatchMockResponseWithBreakpoint(request, action, synchronous, responseWriter, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx, rateLimit,
                        req -> getHttpResponseActionHandler().handle(selectedResponse, req, expectation.getHttpRequest())), expectationPostProcessor), synchronous);
            }
            // RESPONSE_TEMPLATE rendering is a synchronous, CPU-bound computation. Dispatch via
            // scheduleTemplateAction so it runs on the dedicated BOUNDED template pool in asynchronous mode
            // (never inline on the worker event loop, never on the shared scheduler pool) and inline in
            // synchronous mode — isolating a burst of slow renders from unrelated match/forward traffic. The
            // breakpoint/chaos/rate-limit wrapping and the action delay are unchanged.
            case RESPONSE_TEMPLATE -> scheduler.scheduleTemplateAction(rejectable(() -> handleAnyException(request, responseWriter, synchronous, action, () ->
                dispatchMockResponseWithBreakpoint(request, action, synchronous, responseWriter, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpResponseTemplateActionHandler().handle((HttpTemplate) action, req)), expectationPostProcessor), overloadResponse(request, responseWriter, expectationPostProcessor)), synchronous, actionDelay);
            // RESPONSE_CLASS_CALLBACK is always a LOCAL (in-JVM, reflection-invoked) callback whose user
            // code may make a BLOCKING loopback call back to this server. Dispatch via scheduleLocalCallback
            // so it runs off the server worker event loop (on the dedicated unbounded local-callback pool)
            // in asynchronous mode, and inline in synchronous mode — the root fix for the pool-on-by-default
            // self-deadlock. The breakpoint/chaos/rate-limit wrapping and the action delay are unchanged.
            case RESPONSE_CLASS_CALLBACK -> scheduler.scheduleLocalCallback(rejectableIfDelayed(() -> handleAnyException(request, responseWriter, synchronous, action, () ->
                dispatchMockResponseWithBreakpoint(request, action, synchronous, responseWriter, expectation.getHttpRequest(), expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpResponseClassCallbackActionHandler().handle((HttpClassCallback) action, req)), expectationPostProcessor), actionDelay, request, responseWriter, expectationPostProcessor), synchronous, actionDelay);
            case RESPONSE_OBJECT_CALLBACK -> scheduler.schedule(rejectableIfDelayed(() ->
                    getHttpResponseObjectCallbackActionHandler().handle(HttpActionHandler.this, (HttpObjectCallback) action, request, responseWriter, synchronous, expectationPostProcessor),
                    actionDelay, request, responseWriter, expectationPostProcessor),
                synchronous, actionDelay);
            // chaos: inject HTTP chaos faults on expectation-based forwarded responses (FORWARD, FORWARD_TEMPLATE,
            // FORWARD_CLASS_CALLBACK, FORWARD_REPLACE, FORWARD_VALIDATE). Deferred: FORWARD_OBJECT_CALLBACK has
            // its own write path and the unmatched/anonymous proxy-pass path.
            case FORWARD -> scheduler.schedule(rejectableIfDelayed(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                if (blockIfLlmCostBudgetExceeded(request, action, responseWriter, expectationPostProcessor)) {
                    return;
                }
                // breakpoint: REQUEST-phase pause gates the forward; MODIFY feeds the modified request into the forward handler
                dispatchForwardWithBreakpoint(request, action, synchronous, responseWriter, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpForwardActionHandler().handle((HttpForward) action, req));
            }, expectationPostProcessor), actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
            // FORWARD_TEMPLATE rendering is a synchronous, CPU-bound computation (the subsequent forward
            // itself is already asynchronous). Dispatch via scheduleTemplateAction so the render runs on the
            // dedicated BOUNDED template pool in asynchronous mode (never inline on the worker event loop,
            // never on the shared scheduler pool) and inline in synchronous mode. Wrapping and the action
            // delay are unchanged.
            case FORWARD_TEMPLATE -> scheduler.scheduleTemplateAction(rejectable(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                if (blockIfLlmCostBudgetExceeded(request, action, responseWriter, expectationPostProcessor)) {
                    return;
                }
                dispatchForwardWithBreakpoint(request, action, synchronous, responseWriter, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpForwardTemplateActionHandler().handle((HttpTemplate) action, req));
            }, expectationPostProcessor), overloadResponse(request, responseWriter, expectationPostProcessor)), synchronous, combineWithGlobalDelay(actionDelay));
            // FORWARD_CLASS_CALLBACK is always a LOCAL (in-JVM, reflection-invoked) callback whose user code
            // may make a BLOCKING loopback call back to this server. Dispatch via scheduleLocalCallback so it
            // runs off the server worker event loop in asynchronous mode (and inline in synchronous mode) —
            // the root fix for the pool-on-by-default self-deadlock. Wrapping and the action delay are unchanged.
            case FORWARD_CLASS_CALLBACK -> scheduler.scheduleLocalCallback(rejectableIfDelayed(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                if (blockIfLlmCostBudgetExceeded(request, action, responseWriter, expectationPostProcessor)) {
                    return;
                }
                dispatchForwardWithBreakpoint(request, action, synchronous, responseWriter, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpForwardClassCallbackActionHandler().handle((HttpClassCallback) action, req));
            }, expectationPostProcessor), actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
            // deferred: FORWARD_OBJECT_CALLBACK chaos injection and REQUEST breakpoint — uses its own write path
            case FORWARD_OBJECT_CALLBACK -> scheduler.schedule(rejectableIfDelayed(() ->
                    getHttpForwardObjectCallbackActionHandler().handle(HttpActionHandler.this, (HttpObjectCallback) action, request, responseWriter, synchronous, expectationPostProcessor),
                    actionDelay, request, responseWriter, expectationPostProcessor),
                synchronous, combineWithGlobalDelay(actionDelay));
            case FORWARD_REPLACE -> scheduler.schedule(rejectableIfDelayed(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                if (blockIfLlmCostBudgetExceeded(request, action, responseWriter, expectationPostProcessor)) {
                    return;
                }
                dispatchForwardWithBreakpoint(request, action, synchronous, responseWriter, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpOverrideForwardedRequestCallbackActionHandler().handle((HttpOverrideForwardedRequest) action, req));
            }, expectationPostProcessor), actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
            // submitted first: an undelayed schedule runs inline, and validating loads the spec, which may mean a fetch
            case FORWARD_VALIDATE -> scheduler.submit(() -> scheduler.schedule(rejectableIfDelayed(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                if (blockIfLlmCostBudgetExceeded(request, action, responseWriter, expectationPostProcessor)) {
                    return;
                }
                dispatchForwardWithBreakpoint(request, action, synchronous, responseWriter, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpForwardValidateActionHandler().handle((HttpForwardValidateAction) action, req));
            }, expectationPostProcessor), actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay)), synchronous);
            case FORWARD_WITH_FALLBACK -> scheduler.schedule(rejectableIfDelayed(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                if (blockIfLlmCostBudgetExceeded(request, action, responseWriter, expectationPostProcessor)) {
                    return;
                }
                dispatchForwardWithBreakpoint(request, action, synchronous, responseWriter, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit,
                    req -> getHttpForwardWithFallbackActionHandler().handle((HttpForwardWithFallback) action, req));
            }, expectationPostProcessor), actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
            case SSE_RESPONSE -> {
                // rate limit (declarative, protocol-agnostic): an over-limit request is throttled with
                // the synthetic 429 instead of opening the stream. Records one acquire per matched request.
                final HttpResponse sseRateLimited = rateLimitResponseOrNull(rateLimit, action.getExpectationId());
                if (sseRateLimited != null) {
                    writeResponseActionResponse(sseRateLimited, responseWriter, request, action, synchronous, null, expectationPostProcessor);
                } else if (ctx == null) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("SSE streaming is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else {
                    scheduler.schedule(rejectableIfDelayed(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning SSE response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            getHttpSseResponseActionHandler().handle((HttpSseResponse) action, ctx, request, org.mockserver.llm.StreamingFormat.SSE, responseWriter.respondingDirectly());
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            ctx.close();
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
                }
            }
            case LLM_RESPONSE -> {
                HttpLlmResponse llmAction = (HttpLlmResponse) action;
                // Chaos: a probabilistic provider error short-circuits to a normal
                // (non-streaming) HTTP error response, even for a would-be stream.
                final HttpResponse chaosErrorResponse = getHttpLlmResponseActionHandler().chaosErrorResponseOrNull(llmAction);
                // Strict structured-output enforcement: when enabled and the configured body
                // does not conform to the declared schema, fail loudly with a provider-correct
                // error (also a non-streaming response, so a strict stream never begins). Chaos
                // takes priority — it models a transport-level failure independent of the body.
                final HttpResponse enforcementErrorResponse = chaosErrorResponse == null
                    ? getHttpLlmResponseActionHandler().enforcementErrorResponseOrNull(llmAction, request)
                    : null;
                final HttpResponse preEmptiveErrorResponse = chaosErrorResponse != null ? chaosErrorResponse : enforcementErrorResponse;
                boolean isStreaming = preEmptiveErrorResponse == null && llmAction.getCompletion() != null && Boolean.TRUE.equals(llmAction.getCompletion().getStreaming());
                if (isStreaming) {
                    if (ctx == null) {
                        writeResponseActionResponse(
                            response().withStatusCode(501).withBody("SSE streaming is not supported in WAR deployments"),
                            responseWriter, request, action, synchronous, null, expectationPostProcessor
                        );
                    } else {
                        scheduler.schedule(rejectableIfDelayed(() -> {
                            try {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(EXPECTATION_RESPONSE)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setExpectationId(action.getExpectationId())
                                        .setMessageFormat("returning streaming LLM response for request:{}for action:{}from expectation:{}")
                                        .setArguments(request, action, action.getExpectationId())
                                );
                                java.util.List<SseEvent> sseEvents = getHttpLlmResponseActionHandler().handleStreaming(llmAction, request);
                                if (!sseEvents.isEmpty()
                                    && llmAction.getChaos() != null
                                    && (llmAction.getChaos().getTruncateMode() == org.mockserver.model.LlmChaosProfile.TruncateMode.MID_STREAM
                                    || Boolean.TRUE.equals(llmAction.getChaos().getMalformedSse()))) {
                                    metrics.increment(org.mockserver.metrics.Metrics.Name.LLM_CHAOS_INJECTED_COUNT);
                                }
                                org.mockserver.llm.StreamingFormat streamingFormat = getHttpLlmResponseActionHandler().streamingFormatFor(llmAction.getProvider(), request);
                                String contentType;
                                switch (streamingFormat) {
                                    case NDJSON:
                                        contentType = "application/x-ndjson";
                                        break;
                                    case AWS_EVENT_STREAM:
                                    case AWS_CONVERSE_EVENT_STREAM:
                                        contentType = org.mockserver.llm.codec.BedrockEventStreamEncoder.CONTENT_TYPE;
                                        break;
                                    default:
                                        contentType = "text/event-stream";
                                        break;
                                }
                                HttpSseResponse sseResponse = HttpSseResponse.sseResponse()
                                    .withStatusCode(200)
                                    .withHeader("content-type", contentType)
                                    .withHeader("cache-control", "no-cache")
                                    .withEvents(sseEvents);
                                getHttpSseResponseActionHandler().handle(sseResponse, ctx, request, streamingFormat, responseWriter.respondingDirectly());
                            } catch (Throwable throwable) {
                                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                    mockServerLogger.logEvent(
                                        new LogEntry()
                                            .setType(WARN)
                                            .setLogLevel(Level.INFO)
                                            .setCorrelationId(request.getLogCorrelationId())
                                            .setHttpRequest(request)
                                            .setMessageFormat(throwable.getMessage())
                                            .setThrowable(throwable)
                                    );
                                }
                                ctx.close();
                            } finally {
                                expectationPostProcessor.run();
                            }
                        }, actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
                    }
                } else {
                    scheduler.schedule(rejectableIfDelayed(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning LLM response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            HttpResponse llmResponse = preEmptiveErrorResponse != null
                                ? preEmptiveErrorResponse
                                : getHttpLlmResponseActionHandler().handle(llmAction, request);
                            if (chaosErrorResponse != null) {
                                metrics.increment(org.mockserver.metrics.Metrics.Name.LLM_CHAOS_INJECTED_COUNT);
                            }
                            writeResponseActionResponse(llmResponse, responseWriter, request, action, synchronous, null, expectationPostProcessor);
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            expectationPostProcessor.run();
                        }
                    }, actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
                }
            }
            case WEBSOCKET_RESPONSE -> {
                // rate limit: an over-limit request is throttled with the synthetic 429 instead of
                // completing the WebSocket upgrade / emitting messages. Records one acquire per matched request.
                final HttpResponse wsRateLimited = rateLimitResponseOrNull(rateLimit, action.getExpectationId());
                if (wsRateLimited != null) {
                    writeResponseActionResponse(wsRateLimited, responseWriter, request, action, synchronous, null, expectationPostProcessor);
                } else if (ctx == null) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("WebSocket mocking is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else {
                    scheduler.schedule(rejectableIfDelayed(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning WebSocket response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            getHttpWebSocketResponseActionHandler().handle((HttpWebSocketResponse) action, ctx, request, responseWriter.respondingDirectly());
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            ctx.close();
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
                }
            }
            case GRPC_STREAM_RESPONSE -> {
                // rate limit: an over-limit request is throttled with the synthetic 429 instead of
                // opening the gRPC server-stream. Records one acquire per matched request.
                final HttpResponse grpcRateLimited = rateLimitResponseOrNull(rateLimit, action.getExpectationId());
                if (grpcRateLimited != null) {
                    writeResponseActionResponse(grpcRateLimited, responseWriter, request, action, synchronous, null, expectationPostProcessor);
                } else if (ctx == null && !(responseWriter instanceof GrpcStreamResponseWriter)) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("gRPC streaming is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else if (responseWriter instanceof GrpcStreamResponseWriter) {
                    // HTTP/3 path: a QUIC stream has no HTTP/2 frame codec, so delegate the
                    // server-streaming write to the transport-specific response writer (which
                    // emits initial HEADERS + DATA frames + trailing HEADERS over the QUIC stream).
                    scheduler.schedule(rejectableIfDelayed(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning gRPC stream response over HTTP/3 for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            ((GrpcStreamResponseWriter) responseWriter).writeGrpcStreamResponse((GrpcStreamResponse) action, request);
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
                } else {
                    scheduler.schedule(rejectableIfDelayed(() -> {
                        try {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(EXPECTATION_RESPONSE)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setExpectationId(action.getExpectationId())
                                    .setMessageFormat("returning gRPC stream response for request:{}for action:{}from expectation:{}")
                                    .setArguments(request, action, action.getExpectationId())
                            );
                            getGrpcStreamResponseActionHandler().handle((GrpcStreamResponse) action, ctx, request, responseWriter.respondingDirectly());
                        } catch (Throwable throwable) {
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            ctx.close();
                        } finally {
                            expectationPostProcessor.run();
                        }
                    }, actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
                }
            }
            case GRPC_BIDI_RESPONSE -> {
                if (ctx == null) {
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("gRPC bidi streaming is not supported in WAR deployments"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                } else {
                    // The normal bidi flow is driven by GrpcBidiRouterHandler/GrpcBidiStreamHandler
                    // at the Netty layer when grpcBidiStreamingEnabled is on. If the action reaches
                    // HttpActionHandler (flag off or non-multiplex transport), respond with 501.
                    writeResponseActionResponse(
                        response().withStatusCode(501).withBody("gRPC bidi streaming requires the multiplex pipeline (grpcBidiStreamingEnabled=true)"),
                        responseWriter, request, action, synchronous, null, expectationPostProcessor
                    );
                }
            }
            case ERROR -> scheduler.schedule(rejectableIfDelayed(() -> handleAnyException(request, responseWriter, synchronous, action, () -> {
                dispatchErrorAction((HttpError) action, request, responseWriter, ctx);
                expectationPostProcessor.run();
            }, expectationPostProcessor), actionDelay, request, responseWriter, expectationPostProcessor), synchronous, combineWithGlobalDelay(actionDelay));
        }

        final List<Action> secondaryActions = expectation.getSecondaryActions();
        if (!secondaryActions.isEmpty()) {
            for (final Action secondaryAction : secondaryActions) {
                dispatchSecondaryAction(secondaryAction, request, synchronous);
            }
        }
    }

    /**
     * Forwards a request that matched no expectation through the (reverse or forward) proxy path:
     * loop-prevention, optional proxy authentication, the upstream call, and response/streaming/error
     * handling. Extracted from {@link #processAction} to keep the top-level dispatch chain readable.
     */
    private void handleUnmatchedProxyForward(final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx, final boolean synchronous, final boolean potentiallyHttpProxy) {
        if (request.getHeaders() != null && request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {

            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("received \"x-forwarded-by\" header caused by exploratory HTTP proxy or proxy loop - falling back to no proxy:{}")
                        .setArguments(request)
                );
            }
            returnNotFound(responseWriter, request, null, synchronous);

        } else {

            String username = configuration.proxyAuthenticationUsername();
            String password = configuration.proxyAuthenticationPassword();
            // only authenticate potentiallyHttpProxy because other proxied requests should have already been authenticated (i.e. in CONNECT request)
            // Credential comparison MUST go through ProxyAuthenticationValidator: the previous
            // containsHeader(...) check compared with equalsIgnoreCase, which both accepts case-mutated
            // base64 and short-circuits (timing channel). See ProxyAuthenticationValidator.
            if (potentiallyHttpProxy
                && ProxyAuthenticationValidator.proxyAuthenticationConfigured(username, password)
                && !ProxyAuthenticationValidator.isAuthenticated(request, username, password)) {

                HttpResponse response = response()
                    .withStatusCode(PROXY_AUTHENTICATION_REQUIRED.code())
                    .withHeader(PROXY_AUTHENTICATE.toString(), "Basic realm=\"" + StringEscapeUtils.escapeJava(configuration.proxyAuthenticationRealm()) + "\", charset=\"UTF-8\"");
                responseWriter.writeResponse(request, response, false);
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(AUTHENTICATION_FAILED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setHttpResponse(response)
                            .setExpectation(request, response)
                            .setMessageFormat("proxy authentication failed so returning response:{}for forwarded request:{}")
                            .setArguments(response, request)
                    );
                }

            } else {

                final InetSocketAddress remoteAddress = getRemoteAddressWithFallback(ctx);
                final HttpRequest clonedRequest = hopByHopHeaderFilter.onRequest(request).withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
                adjustHostHeaderForUnmatchedRequest(clonedRequest, remoteAddress);

                // validation proxy: request validation runs inside the scheduler (off the Netty event loop)
                // to avoid blocking I/O threads on cold-cache OpenAPI spec parsing / JSON-schema validation
                final boolean validationEnabled = isValidationProxyEnabled();
                scheduler.submit(() -> {
                    try {
                        // pre-flight request validation (enforce mode blocks with 400 before upstream call)
                        if (validationEnabled) {
                            HttpResponse rejectResponse = validateProxyRequest(request);
                            if (rejectResponse != null) {
                                responseWriter.writeResponse(request, rejectResponse, false);
                                return;
                            }
                        }

                        // LLM cost-budget circuit-breaker: if the request targets an LLM provider
                        // and the cumulative cost exceeds the budget, return 429 immediately.
                        HttpResponse costBudgetResponse = checkLlmCostBudget(request);
                        if (costBudgetResponse != null) {
                            responseWriter.writeResponse(request, costBudgetResponse, false);
                            return;
                        }

                        // breakpoint intercept: pause the request if breakpoints are enabled (async mode only).
                        // IMPORTANT: does NOT block any thread — the decision future's continuation runs
                        // asynchronously on the scheduler executor when the control-plane resolves it (or
                        // when the timeout auto-completes it). This avoids exhausting the scheduler pool
                        // and, via CallerRunsPolicy, the Netty event loop.
                        {
                            org.mockserver.mock.breakpoint.BreakpointMatcher requestBreakpoint =
                                !synchronous ? org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.REQUEST) : null;
                            if (requestBreakpoint != null) {
                                java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
                                    ? scheduler.getExecutorService()
                                    : Runnable::run;

                                // WS-callback dispatch (clientId is always present — required since 7b)
                                java.util.concurrent.CompletableFuture<org.mockserver.mock.breakpoint.BreakpointDecision> decisionFuture =
                                    org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher.getInstance().dispatchRequest(
                                        requestBreakpoint.getClientId(), requestBreakpoint.getId(), request,
                                        httpStateHandler.getWebSocketClientRegistry(),
                                        configuration, mockServerLogger
                                    );
                                // null means cap reached or client disconnected — fall through to normal forward

                                if (decisionFuture != null) {
                                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                        mockServerLogger.logEvent(
                                            new LogEntry()
                                                .setLogLevel(Level.INFO)
                                                .setCorrelationId(request.getLogCorrelationId())
                                                .setHttpRequest(request)
                                                .setMessageFormat("request paused at breakpoint, awaiting resolution for:{}")
                                                .setArguments(request)
                                        );
                                    }
                                    // Chain the forward-and-respond continuation onto the decision future
                                    // asynchronously. The current scheduler worker thread returns immediately.
                                    decisionFuture.thenAcceptAsync(decision -> {
                                        try {
                                            switch (decision.getAction()) {
                                                case ABORT:
                                                    HttpResponse abortResponse = decision.getAbortResponse();
                                                    if (abortResponse == null) {
                                                        abortResponse = response().withStatusCode(503).withReasonPhrase("Breakpoint Aborted");
                                                    }
                                                    responseWriter.writeResponse(request, abortResponse, false);
                                                    return;
                                                case MODIFY:
                                                    HttpRequest modified = decision.getModifiedRequest();
                                                    if (modified != null) {
                                                        HttpRequest modifiedToForward = hopByHopHeaderFilter.onRequest(modified)
                                                            .withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
                                                        adjustHostHeaderForUnmatchedRequest(modifiedToForward, remoteAddress);
                                                        executeUnmatchedForward(modifiedToForward, request, remoteAddress, potentiallyHttpProxy, validationEnabled, responseWriter);
                                                    } else {
                                                        executeUnmatchedForward(clonedRequest, request, remoteAddress, potentiallyHttpProxy, validationEnabled, responseWriter);
                                                    }
                                                    return;
                                                case CONTINUE:
                                                default:
                                                    executeUnmatchedForward(clonedRequest, request, remoteAddress, potentiallyHttpProxy, validationEnabled, responseWriter);
                                                    return;
                                            }
                                        } catch (SocketCommunicationException sce) {
                                            returnBadGateway(responseWriter, request, sce.getMessage());
                                        } catch (Throwable throwable) {
                                            returnBadGateway(responseWriter, request, "breakpoint continuation failed: " + throwable.getMessage());
                                        }
                                    }, continuationExecutor);
                                    // Return immediately — do NOT block the scheduler worker thread
                                    return;
                                }
                            }
                        }

                        // Apply the same HTTP/2-upgrade protocol selection as the breakpoint-continuation
                        // forward (executeUnmatchedForward) and the MATCHED forward path (HttpForwardAction),
                        // so the common (no-breakpoint) transparent-proxy / unmatched forward also honours
                        // forwardProxyHttp2Upgrade. This is the path the opencode CLI actually hits: an
                        // HTTP/1.1 inbound that matches no expectation. Without it the upstream leg stays
                        // HTTP/1.1 and streaming SSE backends (e.g. the OpenAI Codex endpoint) withhold the
                        // response head until completion, collapsing time-to-first-byte to total time. Only
                        // activates for the opt-in flag AND secure requests (ALPN; falls back to HTTP/1.1 if
                        // the upstream declines), so the default path is unchanged.
                        if (configuration != null
                            && Boolean.TRUE.equals(configuration.forwardProxyHttp2Upgrade())
                            && Boolean.TRUE.equals(clonedRequest.isSecure())) {
                            clonedRequest.withProtocol(Protocol.HTTP_2);
                        }
                        long forwardStartNanos = org.mockserver.time.TimeService.nanoTime();
                        // gRPC forward-proxy: capture the decode override from the (still-tagged) request,
                        // then re-encode the JSON body to protobuf frames for the upstream gRPC call.
                        final java.util.function.Function<HttpResponse, HttpResponse> grpcDecode = grpcDecodeOverride(clonedRequest);
                        final HttpRequest requestToSend = grpcEncodeForForward(clonedRequest);
                        if (refusedByPrivateNetworkBlock(requestToSend, remoteAddress, request, responseWriter)) {
                            return;
                        }
                        final HttpForwardActionResult responseFuture = new HttpForwardActionResult(clonedRequest, httpClient.sendRequest(requestToSend, remoteAddress, potentiallyHttpProxy ? 1000 : configuration.socketConnectionTimeoutInMillis()), grpcDecode, remoteAddress);
                        // Consume the upstream response via the scheduler continuation (as the matched path
                        // does) instead of a blocking get, so the pool thread is freed during the round trip
                        // rather than pinned per in-flight forward — the proxy-concurrency cap.
                        scheduler.submit(responseFuture, () -> {
                        try {
                        HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                        long responseTimeMs = (org.mockserver.time.TimeService.nanoTime() - forwardStartNanos) / 1_000_000;
                        if (response == null) {
                            response = badGatewayResponse();
                        }
                        if (response.containsHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
                            response.removeHeader(httpStateHandler.getUniqueLoopPreventionHeaderName());
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(NO_MATCH_RESPONSE)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setHttpResponse(notFoundResponse())
                                        .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                                        .setArguments(request, response)
                                );
                            }
                            responseWriter.writeResponse(request, response, false);
                        } else if (response.getStreamingBody() != null) {
                            // Streaming response: write the head immediately and log
                            // the FORWARDED_REQUEST entry after the stream completes.
                            // Note: enforce mode cannot replace a streaming response since the body
                            // has already been written to the client — violations are logged (report-only).
                            final HttpResponse streamingResponse = response;
                            // OpenTelemetry: emit SERVER span for the unmatched streaming forward path
                            emitRequestSpan(request, streamingResponse, null, ctx, responseTimeMs, remoteAddress);
                            // Metrics: per-upstream forward latency + status for the unmatched proxy path
                            recordForwardMetrics(null, streamingResponse, remoteAddress, responseTimeMs);
                            final LlmStreamUsageScanner streamUsage = observeForwardedLlmStream(clonedRequest, streamingResponse);
                            responseWriter.writeResponse(request, streamingResponse, false);
                            final long streamForwardStartNanos = forwardStartNanos;
                            streamingResponse.getStreamingBody().addCompletionListener(() -> {
                                HttpResponse logResponse = streamingResponse.clone();
                                byte[] captured = streamingResponse.getStreamingBody().capturedBytes();
                                setCapturedStreamingBody(logResponse, captured);
                                attachStreamingHeaders(logResponse, streamingResponse.getStreamingBody());
                                long streamResponseTimeMs = (org.mockserver.time.TimeService.nanoTime() - streamForwardStartNanos) / 1_000_000;
                                logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(streamResponseTimeMs));
                                // validation proxy: validate completed streaming response (report-only)
                                if (validationEnabled) {
                                    validateProxyResponse(request, logResponse, true);
                                }
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(FORWARDED_REQUEST)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setHttpResponse(logResponse)
                                        .setExpectation(request, logResponse)
                                        .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                        .setArguments(logResponse, request, deferredCurl(request, remoteAddress))
                                );
                                // OpenTelemetry: emit GenAI span for the unmatched streaming forward path
                                // after the stream completes
                                emitStreamedForwardGenAiSpan(clonedRequest, logResponse, streamingResponse.getStreamingBody(), streamUsage);
                            });
                        } else {
                            // validation proxy: validate non-streaming response (enforce mode returns 502)
                            if (validationEnabled) {
                                response = validateProxyResponse(request, response, false);
                            }
                            // OpenTelemetry: emit SERVER + GenAI spans for the unmatched non-streaming forward path
                            emitRequestSpan(request, response, null, ctx, responseTimeMs, remoteAddress);
                            // Metrics: per-upstream forward latency + status for the unmatched proxy path
                            recordForwardMetrics(null, response, remoteAddress, responseTimeMs);
                            emitForwardGenAiSpan(clonedRequest, response);
                            // Response breakpoint: hold non-streaming unmatched proxy responses before writing
                            {
                                org.mockserver.mock.breakpoint.BreakpointMatcher responseBreakpoint =
                                    !synchronous ? org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findResponseMatch(request, response, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE) : null;
                                if (responseBreakpoint != null) {
                                    java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
                                        ? scheduler.getExecutorService()
                                        : Runnable::run;
                                    final long breakpointResponseTimeMs = effectiveForwardLatencyMs(response, responseTimeMs);
                                    if (attemptResponseBreakpoint(responseBreakpoint, request, response, null, responseWriter, continuationExecutor, responseToWrite -> {
                                        HttpResponse logResponse = responseToWrite.clone();
                                        logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(breakpointResponseTimeMs));
                                        mockServerLogger.logEvent(
                                            new LogEntry()
                                                .setType(FORWARDED_REQUEST)
                                                .setLogLevel(Level.INFO)
                                                .setCorrelationId(request.getLogCorrelationId())
                                                .setHttpRequest(request)
                                                .setHttpResponse(logResponse)
                                                .setExpectation(request, logResponse)
                                                .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                                .setArguments(logResponse, request, deferredCurl(request, remoteAddress))
                                        );
                                        responseWriter.writeResponse(request, responseToWrite, false);
                                    }, null)) {
                                        return; // do NOT block — continuation is async
                                    }
                                    // cap reached — fall through to normal write
                                }
                            }
                            HttpResponse logResponse = response.clone();
                            logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(effectiveForwardLatencyMs(response, responseTimeMs)));
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(FORWARDED_REQUEST)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setHttpResponse(logResponse)
                                    .setExpectation(request, logResponse)
                                    .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                    .setArguments(logResponse, request, deferredCurl(request, remoteAddress))
                            );
                            responseWriter.writeResponse(request, response, false);
                        }
                        } catch (SocketCommunicationException sce) {
                            returnBadGateway(responseWriter, request, sce.getMessage());
                        } catch (Throwable throwable) {
                            handleUnmatchedForwardFailure(throwable, request, responseWriter, remoteAddress, potentiallyHttpProxy);
                        }
                        }, synchronous, throwable -> false);
                    } catch (SocketCommunicationException sce) {
                        returnBadGateway(responseWriter, request, sce.getMessage());
                    } catch (Throwable throwable) {
                        handleUnmatchedForwardFailure(throwable, request, responseWriter, remoteAddress, potentiallyHttpProxy);
                    }
                }, synchronous);

            }

        }
    }

    /**
     * Maps a forward/proxy failure to its bad-gateway response and matching diagnostic log entry. Shared
     * by the unmatched-proxy and breakpoint-continuation forward paths so error handling is identical
     * whether the failure is raised while issuing the request or delivered later by the response future's
     * async continuation.
     */
    void handleUnmatchedForwardFailure(Throwable throwable, HttpRequest request, ResponseWriter responseWriter, InetSocketAddress remoteAddress, boolean potentiallyHttpProxy) {
        throwable = answeredAsItsCause(throwable);
        if (returnedBlockedTarget(responseWriter, request, throwable, "proxied request")
            || returnedHeaderLimitFailure(responseWriter, request, throwable)
            || returnedUpstreamFailureReason(throwable, request, responseWriter, new LogEntry().setMessageFormat("failed to proxy request{}to remote address{}because:{}"), remoteAddress)) {
            return;
        }
        if (potentiallyHttpProxy && connectionException(throwable)) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("failed to connect to proxied socket due to exploratory HTTP proxy for:{}due to:{}falling back to no proxy")
                        .setArguments(request, throwable.getCause())
                );
            }
            returnBadGateway(responseWriter, request, "failed to connect to proxied socket due to exploratory HTTP proxy");
        } else if (!connectionClosedException(throwable)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXCEPTION)
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(throwable.getMessage())
                    .setThrowable(throwable)
            );
            returnBadGateway(responseWriter, request, "connection closed while proxying request to remote address" + remoteAddress);
        } else {
            returnBadGateway(responseWriter, request, throwable.getMessage());
        }
    }

    /**
     * Executes the actual HTTP forward for an unmatched proxy request and writes the response.
     * Extracted so the breakpoint async continuation can reuse the same forward+response logic
     * without duplicating the streaming/non-streaming/loop-prevention handling.
     *
     * @param requestToForward the (possibly modified) request to send upstream
     * @param originalRequest  the original inbound request (for logging and correlation)
     * @param remoteAddress    the resolved upstream address
     * @param potentiallyHttpProxy whether this is an exploratory HTTP proxy request
     * @param validationEnabled whether validation-proxy mode is active
     * @param responseWriter   the writer to send the response back to the client
     */
    private void executeUnmatchedForward(HttpRequest requestToForward, HttpRequest originalRequest,
                                         InetSocketAddress remoteAddress, boolean potentiallyHttpProxy,
                                         boolean validationEnabled, ResponseWriter responseWriter) {
        try {
            // LLM cost-budget circuit-breaker: re-check after breakpoint resolution
            // because the request may have been modified to target a different host.
            HttpResponse costBudgetResponse = checkLlmCostBudget(requestToForward);
            if (costBudgetResponse != null) {
                responseWriter.writeResponse(originalRequest, costBudgetResponse, false);
                return;
            }

            // Apply the same HTTP/2-upgrade protocol selection the MATCHED forward path uses
            // (HttpForwardAction.sendRequest), so the transparent-proxy (unmatched) path also honours
            // forwardProxyHttp2Upgrade. Without it an HTTP/1.1 inbound (e.g. the opencode CLI) is always
            // forwarded upstream over HTTP/1.1; some streaming backends — notably the OpenAI Codex SSE
            // endpoint (chatgpt.com/backend-api/codex/responses) — withhold the response head over HTTP/1.1
            // and only flush at completion, so MockServer's streaming relay (which DOES engage here) has
            // nothing to relay until the end and time-to-first-byte collapses to total time. Forcing the
            // upstream leg to HTTP/2 via ALPN (TLS only; ALPN falls back to HTTP/1.1 if the upstream
            // declines) lets the backend stream the head immediately. This only activates when the opt-in
            // flag is set AND the request is secure, so the default path is unchanged; the unmatched path
            // already preserves the inbound protocol otherwise, so no explicit downgrade is applied.
            if (configuration != null
                && Boolean.TRUE.equals(configuration.forwardProxyHttp2Upgrade())
                && Boolean.TRUE.equals(requestToForward.isSecure())) {
                requestToForward.withProtocol(Protocol.HTTP_2);
            }
            long forwardStartNanos = org.mockserver.time.TimeService.nanoTime();
            // gRPC forward-proxy: capture the decode override then re-encode to protobuf for upstream.
            final java.util.function.Function<HttpResponse, HttpResponse> grpcDecode = grpcDecodeOverride(requestToForward);
            final HttpRequest requestToSend = grpcEncodeForForward(requestToForward);
            if (refusedByPrivateNetworkBlock(requestToSend, remoteAddress, originalRequest, responseWriter)) {
                return;
            }
            final HttpForwardActionResult responseFuture = new HttpForwardActionResult(
                requestToForward,
                httpClient.sendRequest(requestToSend, remoteAddress,
                    potentiallyHttpProxy ? 1000 : configuration.socketConnectionTimeoutInMillis()),
                grpcDecode, remoteAddress
            );
            // Consume the upstream response via the scheduler continuation (as the matched path does)
            // instead of a blocking get, so the pool thread is freed during the round trip. This path is
            // async-only (reached from the breakpoint continuation), so it never runs synchronously.
            scheduler.submit(responseFuture, () -> {
            try {
            HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
            long responseTimeMs = (org.mockserver.time.TimeService.nanoTime() - forwardStartNanos) / 1_000_000;
            if (response == null) {
                response = badGatewayResponse();
            }
            if (response.containsHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
                response.removeHeader(httpStateHandler.getUniqueLoopPreventionHeaderName());
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(NO_MATCH_RESPONSE)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(originalRequest.getLogCorrelationId())
                            .setHttpRequest(originalRequest)
                            .setHttpResponse(notFoundResponse())
                            .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                            .setArguments(originalRequest, response)
                    );
                }
                responseWriter.writeResponse(originalRequest, response, false);
            } else if (response.getStreamingBody() != null) {
                final HttpResponse streamingResponse = response;
                // OpenTelemetry: emit SERVER span for the breakpoint-continuation streaming forward path
                emitRequestSpan(originalRequest, streamingResponse, null, null, responseTimeMs);
                final LlmStreamUsageScanner streamUsage = observeForwardedLlmStream(requestToForward, streamingResponse);
                responseWriter.writeResponse(originalRequest, streamingResponse, false);
                final long streamForwardStartNanos = forwardStartNanos;
                streamingResponse.getStreamingBody().addCompletionListener(() -> {
                    HttpResponse logResponse = streamingResponse.clone();
                    byte[] captured = streamingResponse.getStreamingBody().capturedBytes();
                    setCapturedStreamingBody(logResponse, captured);
                    attachStreamingHeaders(logResponse, streamingResponse.getStreamingBody());
                    long streamResponseTimeMs = (org.mockserver.time.TimeService.nanoTime() - streamForwardStartNanos) / 1_000_000;
                    logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(streamResponseTimeMs));
                    if (validationEnabled) {
                        validateProxyResponse(originalRequest, logResponse, true);
                    }
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(FORWARDED_REQUEST)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(originalRequest.getLogCorrelationId())
                            .setHttpRequest(originalRequest)
                            .setHttpResponse(logResponse)
                            .setExpectation(originalRequest, logResponse)
                            .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                            .setArguments(logResponse, originalRequest, deferredCurl(originalRequest, remoteAddress))
                    );
                    // OpenTelemetry: emit GenAI span after stream completes
                    emitStreamedForwardGenAiSpan(requestToForward, logResponse, streamingResponse.getStreamingBody(), streamUsage);
                });
            } else {
                if (validationEnabled) {
                    response = validateProxyResponse(originalRequest, response, false);
                }
                // OpenTelemetry: emit SERVER + GenAI spans for the breakpoint-continuation non-streaming forward path
                emitRequestSpan(originalRequest, response, null, null, responseTimeMs);
                emitForwardGenAiSpan(requestToForward, response);
                // Response breakpoint: hold non-streaming unmatched proxy responses before writing
                {
                    org.mockserver.mock.breakpoint.BreakpointMatcher responseBreakpoint2 =
                        org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findResponseMatch(originalRequest, response, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE);
                    if (responseBreakpoint2 != null) {
                        java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
                            ? scheduler.getExecutorService()
                            : Runnable::run;
                        final long breakpointResponseTimeMs = effectiveForwardLatencyMs(response, responseTimeMs);
                        if (attemptResponseBreakpoint(responseBreakpoint2, originalRequest, response, null, responseWriter, continuationExecutor, responseToWrite -> {
                            HttpResponse logResponse = responseToWrite.clone();
                            logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(breakpointResponseTimeMs));
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(FORWARDED_REQUEST)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(originalRequest.getLogCorrelationId())
                                    .setHttpRequest(originalRequest)
                                    .setHttpResponse(logResponse)
                                    .setExpectation(originalRequest, logResponse)
                                    .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                    .setArguments(logResponse, originalRequest, deferredCurl(originalRequest, remoteAddress))
                            );
                            responseWriter.writeResponse(originalRequest, responseToWrite, false);
                        }, null)) {
                            return; // do NOT block — continuation is async
                        }
                        // cap reached — fall through to normal write
                    }
                }
                HttpResponse logResponse = response.clone();
                logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(effectiveForwardLatencyMs(response, responseTimeMs)));
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(originalRequest.getLogCorrelationId())
                        .setHttpRequest(originalRequest)
                        .setHttpResponse(logResponse)
                        .setExpectation(originalRequest, logResponse)
                        .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                        .setArguments(logResponse, originalRequest, deferredCurl(originalRequest, remoteAddress))
                );
                responseWriter.writeResponse(originalRequest, response, false);
            }
            } catch (SocketCommunicationException sce) {
                returnBadGateway(responseWriter, originalRequest, sce.getMessage());
            } catch (Throwable throwable) {
                handleUnmatchedForwardFailure(throwable, originalRequest, responseWriter, remoteAddress, potentiallyHttpProxy);
            }
            }, false, throwable -> false);
        } catch (SocketCommunicationException sce) {
            returnBadGateway(responseWriter, originalRequest, sce.getMessage());
        } catch (Throwable throwable) {
            handleUnmatchedForwardFailure(throwable, originalRequest, responseWriter, remoteAddress, potentiallyHttpProxy);
        }
    }

    private void handleProxyPassFailure(Throwable throwable, HttpRequest request, ResponseWriter responseWriter, String targetUri) {
        if (returnedHeaderLimitFailure(responseWriter, request, throwable)
            || returnedUpstreamFailureReason(throwable, request, responseWriter, new LogEntry().setMessageFormat("failed to proxy pass request{}to{}because:{}"), targetUri)) {
            return;
        }
        returnBadGateway(responseWriter, request, "proxy pass forwarding failed for " + targetUri + ": " + throwable.getMessage());
    }

    private boolean handleProxyPass(final HttpRequest request, final ResponseWriter responseWriter, final boolean synchronous) {
        List<ProxyPassMapping> mappings = configuration.proxyPassMappings();
        if (mappings == null || mappings.isEmpty() || request.getPath() == null) {
            return false;
        }
        String requestPath = request.getPath().getValue();
        if (requestPath == null) {
            return false;
        }
        for (ProxyPassMapping mapping : mappings) {
            if (requestPath.startsWith(mapping.getPathPrefix())) {
                String remainder = requestPath.substring(mapping.getPathPrefix().length());
                String targetPath = mapping.getTargetPath();
                String newPath;
                if (remainder.isEmpty()) {
                    newPath = targetPath.isEmpty() ? "/" : targetPath;
                } else if (remainder.startsWith("/") || targetPath.endsWith("/")) {
                    newPath = targetPath + remainder;
                } else {
                    newPath = targetPath + "/" + remainder;
                }
                HttpRequest clonedRequest = hopByHopHeaderFilter.onRequest(request);
                clonedRequest.withPath(newPath);
                clonedRequest.withSecure(mapping.isTargetSecure());

                if (!mapping.isPreserveHost() && configuration.forwardAdjustHostHeader()) {
                    boolean defaultPort = (mapping.isTargetSecure() && mapping.getTargetPort() == 443)
                        || (!mapping.isTargetSecure() && mapping.getTargetPort() == 80);
                    String hostHeader = defaultPort ? mapping.getTargetHost() : mapping.getTargetHost() + ":" + mapping.getTargetPort();
                    clonedRequest.replaceHeader(new Header("Host", hostHeader));
                }

                // validation proxy: request + response validation runs inside the scheduler
                // (off the Netty event loop) to avoid blocking I/O threads on cold-cache OpenAPI parsing
                final boolean validationEnabled = isValidationProxyEnabled();
                // Unresolved so Netty's event-loop resolver performs the (blocking) DNS lookup rather
                // than resolving synchronously here in the InetSocketAddress constructor.
                InetSocketAddress targetAddress = InetSocketAddress.createUnresolved(mapping.getTargetHost(), mapping.getTargetPort());
                scheduler.submit(() -> {
                    try {
                        // pre-flight request validation (enforce mode blocks with 400 before upstream call)
                        if (validationEnabled) {
                            HttpResponse rejectResponse = validateProxyRequest(request);
                            if (rejectResponse != null) {
                                responseWriter.writeResponse(request, rejectResponse, false);
                                return;
                            }
                        }

                        // LLM cost-budget circuit-breaker: check before upstream send
                        HttpResponse costBudgetResponse = checkLlmCostBudgetByHost(mapping.getTargetHost(), clonedRequest);
                        if (costBudgetResponse != null) {
                            responseWriter.writeResponse(request, costBudgetResponse, false);
                            return;
                        }

                        // Apply the same HTTP/2-upgrade protocol selection as the matched forward and
                        // transparent-proxy paths so the proxyPassMappings reverse-proxy route also honours
                        // forwardProxyHttp2Upgrade. Without it a streaming SSE backend reached via a proxy-pass
                        // mapping (e.g. the OpenAI Codex endpoint) stays on HTTP/1.1, where it withholds the
                        // response head until completion, collapsing time-to-first-byte. Only the opt-in flag +
                        // a secure target triggers it (clonedRequest.isSecure() reflects mapping.isTargetSecure(),
                        // set above), so the default path is unchanged; ALPN falls back to HTTP/1.1 if the
                        // upstream declines.
                        if (configuration != null
                            && Boolean.TRUE.equals(configuration.forwardProxyHttp2Upgrade())
                            && Boolean.TRUE.equals(clonedRequest.isSecure())) {
                            clonedRequest.withProtocol(Protocol.HTTP_2);
                        }
                        long forwardStartNanos = org.mockserver.time.TimeService.nanoTime();
                        // gRPC forward-proxy: capture the decode override then re-encode to protobuf for upstream.
                        final java.util.function.Function<HttpResponse, HttpResponse> grpcDecode = grpcDecodeOverride(clonedRequest);
                        final HttpRequest requestToSend = grpcEncodeForForward(clonedRequest);
                        if (refusedByPrivateNetworkBlock(requestToSend, targetAddress, request, responseWriter)) {
                            return;
                        }
                        final HttpForwardActionResult responseFuture = new HttpForwardActionResult(clonedRequest, httpClient.sendRequest(requestToSend, targetAddress), grpcDecode, targetAddress);
                        // Consume the upstream response via the scheduler continuation (as the matched path
                        // does) instead of a blocking get, so the pool thread is freed during the round trip.
                        scheduler.submit(responseFuture, () -> {
                        try {
                        HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                        long responseTimeMs = (org.mockserver.time.TimeService.nanoTime() - forwardStartNanos) / 1_000_000;
                        if (response == null) {
                            response = badGatewayResponse();
                        }
                        // Metrics: per-upstream forward latency + status for the proxy-pass reverse-proxy route
                        recordForwardMetrics(null, response, targetAddress, responseTimeMs);
                        if (response.getStreamingBody() != null) {
                            // Note: enforce mode cannot replace a streaming response since the body
                            // has already been written to the client — violations are logged (report-only).
                            final HttpResponse streamingResponse = response;
                            responseWriter.writeResponse(request, streamingResponse, false);
                            final long streamForwardStartNanos = forwardStartNanos;
                            streamingResponse.getStreamingBody().addCompletionListener(() -> {
                                HttpResponse logResponse = streamingResponse.clone();
                                byte[] captured = streamingResponse.getStreamingBody().capturedBytes();
                                setCapturedStreamingBody(logResponse, captured);
                                attachStreamingHeaders(logResponse, streamingResponse.getStreamingBody());
                                long streamResponseTimeMs = (org.mockserver.time.TimeService.nanoTime() - streamForwardStartNanos) / 1_000_000;
                                logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(streamResponseTimeMs));
                                // validation proxy: validate completed streaming response (report-only)
                                if (validationEnabled) {
                                    validateProxyResponse(request, logResponse, true);
                                }
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(FORWARDED_REQUEST)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setHttpResponse(logResponse)
                                        .setExpectation(request, logResponse)
                                        .setMessageFormat("returning response:{}for proxy pass forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                        .setArguments(logResponse, request, deferredCurl(request, targetAddress))
                                );
                            });
                        } else {
                            // validation proxy: validate non-streaming response (enforce mode returns 502)
                            if (validationEnabled) {
                                response = validateProxyResponse(request, response, false);
                            }
                            HttpResponse logResponse = response.clone();
                            logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(effectiveForwardLatencyMs(response, responseTimeMs)));
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(FORWARDED_REQUEST)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setHttpResponse(logResponse)
                                    .setExpectation(request, logResponse)
                                    .setMessageFormat("returning response:{}for proxy pass forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}")
                                    .setArguments(logResponse, request, deferredCurl(request, targetAddress))
                            );
                            responseWriter.writeResponse(request, response, false);
                        }
                        } catch (Throwable throwable) {
                            handleProxyPassFailure(throwable, request, responseWriter, mapping.getTargetUri());
                        }
                        }, synchronous, throwable -> false);
                    } catch (Throwable throwable) {
                        handleProxyPassFailure(throwable, request, responseWriter, mapping.getTargetUri());
                    }
                }, synchronous);
                return true;
            }
        }
        return false;
    }

    /**
     * If the matched expectation carries cross-protocol scenario triggers,
     * fire the HTTP_REQUEST event so registered listeners can advance
     * scenario state.
     */
    private void fireCrossProtocolEvents(Expectation expectation, HttpRequest request) {
        if (expectation.getCrossProtocolScenarios() != null && !expectation.getCrossProtocolScenarios().isEmpty()) {
            String path = request.getPath() != null ? request.getPath().getValue() : "/";
            CrossProtocolEventBus.getInstance().fire(CrossProtocolTrigger.HTTP_REQUEST, path);
        }
    }

    private void handleAnyException(HttpRequest request, ResponseWriter responseWriter, boolean synchronous, Action action, Runnable processAction, Runnable postProcessor) {
        try {
            processAction.run();
        } catch (FileBodyException fileBodyException) {
            // A FileBody whose file cannot be read on the static RESPONSE path (materialised inside
            // HttpResponseActionHandler.handle, which throws before the write funnel) becomes the same clean,
            // logged 500 the funnel paths produce — a body that does NOT leak the path, not a misleading 404
            // (issue #2450).
            logFileBodyError(request, fileBodyException);
            writeResponseActionResponse(fileBodyErrorResponse(), responseWriter, request, action, synchronous, null, postProcessor);
        } catch (Throwable throwable) {
            writeResponseActionResponse(notFoundResponse(), responseWriter, request, action, synchronous, null, postProcessor);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(WARN)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    private void dispatchSecondaryAction(final Action secondaryAction, final HttpRequest request, final boolean synchronous) {
        scheduler.submitAsync(sheddable(() -> {
            try {
                switch (secondaryAction.getType()) {
                    case RESPONSE -> getHttpResponseActionHandler().handle((HttpResponse) secondaryAction, request);
                    case RESPONSE_TEMPLATE -> getHttpResponseTemplateActionHandler().handle((HttpTemplate) secondaryAction, request);
                    case RESPONSE_CLASS_CALLBACK -> getHttpResponseClassCallbackActionHandler().handle((HttpClassCallback) secondaryAction, request);
                    case RESPONSE_OBJECT_CALLBACK -> {
                        String clientId = ((HttpObjectCallback) secondaryAction).getClientId();
                        if (LocalCallbackRegistry.responseClientExists(clientId)) {
                            LocalCallbackRegistry.retrieveResponseCallback(clientId).handle(request);
                        }
                    }
                    case FORWARD -> {
                        HttpForwardActionResult result = getHttpForwardActionHandler().handle((HttpForward) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_TEMPLATE -> {
                        HttpForwardActionResult result = getHttpForwardTemplateActionHandler().handle((HttpTemplate) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_CLASS_CALLBACK -> {
                        HttpForwardActionResult result = getHttpForwardClassCallbackActionHandler().handle((HttpClassCallback) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_OBJECT_CALLBACK -> {
                        String clientId = ((HttpObjectCallback) secondaryAction).getClientId();
                        if (LocalCallbackRegistry.forwardClientExists(clientId)) {
                            HttpRequest callbackRequest = LocalCallbackRegistry.retrieveForwardCallback(clientId).handle(request);
                            if (callbackRequest != null) {
                                HttpForwardActionResult result = getHttpForwardObjectCallbackActionHandler().forward(callbackRequest);
                                logForwardResultAsync(result, request, secondaryAction);
                            }
                        }
                    }
                    case FORWARD_REPLACE -> {
                        HttpForwardActionResult result = getHttpOverrideForwardedRequestCallbackActionHandler().handle((HttpOverrideForwardedRequest) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_VALIDATE -> {
                        HttpForwardActionResult result = getHttpForwardValidateActionHandler().handle((HttpForwardValidateAction) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case FORWARD_WITH_FALLBACK -> {
                        HttpForwardActionResult result = getHttpForwardWithFallbackActionHandler().handle((HttpForwardWithFallback) secondaryAction, request);
                        logForwardResultAsync(result, request, secondaryAction);
                    }
                    case ERROR -> { }
                    default -> { }
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception handling secondary action " + secondaryAction.getType() + " - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        }), secondaryAction.getDelay());
    }

    private void logForwardResultAsync(HttpForwardActionResult result, HttpRequest request, Action action) {
        if (result != null && result.getHttpResponse() != null) {
            result.getHttpResponse().whenComplete((response, throwable) -> {
                if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("secondary forward action " + action.getType() + " failed - " + throwable.getMessage())
                            .setThrowable(throwable)
                    );
                }
            });
        }
    }

    /**
     * Applies forwardProxyBlockPrivateNetworks to a request MockServer sends itself, to the destination the request
     * names, as a forward action's target is checked.
     */
    private void checkOutboundTarget(HttpRequest request) {
        InetAddressValidator.validateForwardTarget(configuration, request, null, getHttpClient().sendsThroughHttpProxy(request, null));
    }

    /**
     * @return why forwardProxyBlockPrivateNetworks refuses this webhook, logged here, or null when it may be sent
     */
    private ForwardTargetBlockedException refusedWebhook(HttpRequest webhook, HttpRequest request, String label) {
        try {
            checkOutboundTarget(webhook);
            return null;
        } catch (ForwardTargetBlockedException blocked) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat(label + " webhook blocked by SSRF policy:{}")
                        .setArguments(blocked.getMessage())
                );
            }
            return blocked;
        }
    }

    private void dispatchAfterAction(final AfterAction afterAction, final HttpRequest request) {
        dispatchSideAction(afterAction, request, "after-action");
    }

    /**
     * Fire-and-forget dispatch of a side-effect action (used by after-actions and by
     * non-blocking before-actions). The side-effect's response, if any, is discarded; failures are
     * logged but never propagated to the client. {@code label} only flavours the log messages.
     */
    private void dispatchSideAction(final AfterAction action, final HttpRequest request, final String label) {
        scheduler.submitAsync(sheddable(() -> {
            try {
                if (action.getHttpRequest() != null) {
                    // Resolve OpenAPI runtime expressions (no-op when none present)
                    HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(
                        action.getHttpRequest(), request
                    );
                    if (refusedWebhook(callbackRequest, request, label) != null) {
                        return;
                    }
                    httpClient.sendRequest(callbackRequest)
                        .whenComplete((response, throwable) -> {
                            if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat(label + " webhook failed for request{} - " + throwable.getMessage())
                                        .setArguments(callbackRequest)
                                        .setThrowable(throwable)
                                );
                            }
                        });
                } else if (action.getHttpClassCallback() != null) {
                    getHttpResponseClassCallbackActionHandler().handle(action.getHttpClassCallback(), request);
                } else if (action.getHttpObjectCallback() != null) {
                    HttpObjectCallback callback = action.getHttpObjectCallback();
                    callback.withActionType(Action.Type.RESPONSE_OBJECT_CALLBACK);
                    String clientId = callback.getClientId();
                    if (LocalCallbackRegistry.responseClientExists(clientId)) {
                        LocalCallbackRegistry.retrieveResponseCallback(clientId).handle(request);
                    }
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception dispatching " + label + " - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        }), action.getDelay());
    }

    /**
     * Runs an expectation's before-actions ahead of its primary action.
     *
     * <p>Each before-action is either <em>blocking</em> (default) — the response waits for it to
     * complete — or non-blocking (started but not waited for). Only HTTP-request (webhook)
     * before-actions can be awaited; class/object-callback before-actions are always dispatched
     * fire-and-forget. When a blocking webhook fails or times out its {@code failurePolicy}
     * decides the outcome: {@link FailurePolicy#FAIL_FAST} writes a 502 and aborts (returns
     * {@code false}); {@link FailurePolicy#BEST_EFFORT} (the default) logs and continues.</p>
     *
     * @return {@code true} to proceed to the primary action, {@code false} if a fail-fast
     * before-action already wrote an error response.
     */
    private boolean runBeforeActions(final Expectation expectation, final HttpRequest request, final ResponseWriter responseWriter) {
        for (AfterAction beforeAction : expectation.getBeforeActions()) {
            final boolean blocking = beforeAction.getBlocking() == null || beforeAction.getBlocking();
            final FailurePolicy failurePolicy = beforeAction.getFailurePolicy() == null
                ? FailurePolicy.BEST_EFFORT
                : beforeAction.getFailurePolicy();

            if (!blocking || beforeAction.getHttpRequest() == null) {
                // non-blocking, or a callback before-action (no awaitable result in increment 1):
                // dispatch fire-and-forget, started before the response
                if (blocking && beforeAction.getHttpRequest() == null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("ignoring blocking=true on a callback before-action - only httpRequest (webhook) before-actions can block the response; dispatching fire-and-forget")
                    );
                }
                dispatchSideAction(beforeAction, request, "before-action");
                continue;
            }

            // blocking webhook before-action: send and wait, honouring the optional timeout
            final HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(beforeAction.getHttpRequest(), request);
            final long timeoutMillis = beforeAction.getTimeout() != null
                ? beforeAction.getTimeout().getTimeUnit().toMillis(beforeAction.getTimeout().getValue())
                : configuration.maxSocketTimeoutInMillis();
            ForwardTargetBlockedException refused = refusedWebhook(callbackRequest, request, "before-action");
            if (refused != null) {
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    responseWriter.writeResponse(request, badGatewayResponse().withBody("before-action failed: " + refused.getMessage()), false);
                    return false;
                }
                continue;
            }
            try {
                httpClient.sendRequest(callbackRequest, timeoutMillis, MILLISECONDS);
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("blocking before-action webhook failed for request{} - " + e.getMessage())
                            .setArguments(callbackRequest)
                            .setThrowable(e)
                    );
                }
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    responseWriter.writeResponse(request, badGatewayResponse().withBody("before-action failed: " + e.getMessage()), false);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Runs the pre-responder steps from the expectation's steps list. Each pre-responder step
     * is a side-effect (webhook/callback/forward) that runs before the responder. Follows the
     * same blocking/timeout/failurePolicy semantics as {@link #runBeforeActions}.
     *
     * @return {@code true} to proceed to the responder step, {@code false} if a fail-fast
     * step already wrote an error response
     */
    private boolean runStepsPreResponder(final Expectation expectation, final HttpRequest request, final ResponseWriter responseWriter) {
        List<ExpectationStep> preSteps = expectation.getPreResponderSteps();
        for (ExpectationStep step : preSteps) {
            // Convert step to an AfterAction-like dispatch: reuse the same blocking/timeout/failurePolicy logic
            final boolean blocking = step.getBlocking() == null || step.getBlocking();
            final FailurePolicy failurePolicy = step.getFailurePolicy() == null
                ? FailurePolicy.BEST_EFFORT
                : step.getFailurePolicy();

            if (!blocking || step.getHttpRequest() == null) {
                // non-blocking, or a callback/forward step: dispatch fire-and-forget
                if (blocking && step.getHttpRequest() == null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("ignoring blocking=true on a non-webhook step - only httpRequest (webhook) steps can block the response; dispatching fire-and-forget")
                    );
                }
                dispatchStepSideEffect(step, request);
                continue;
            }

            // blocking webhook step: send and wait
            final HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(step.getHttpRequest(), request);
            final long timeoutMillis = step.getTimeout() != null
                ? step.getTimeout().getTimeUnit().toMillis(step.getTimeout().getValue())
                : configuration.maxSocketTimeoutInMillis();
            ForwardTargetBlockedException refused = refusedWebhook(callbackRequest, request, "step");
            if (refused != null) {
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    responseWriter.writeResponse(request, badGatewayResponse().withBody("step failed: " + refused.getMessage()), false);
                    return false;
                }
                continue;
            }
            try {
                httpClient.sendRequest(callbackRequest, timeoutMillis, MILLISECONDS);
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("blocking step webhook failed for request{} - " + e.getMessage())
                            .setArguments(callbackRequest)
                            .setThrowable(e)
                    );
                }
                if (failurePolicy == FailurePolicy.FAIL_FAST) {
                    responseWriter.writeResponse(request, badGatewayResponse().withBody("step failed: " + e.getMessage()), false);
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Dispatches post-responder steps (steps that come after the responder in the list)
     * as fire-and-forget side-effects, similar to after-actions.
     */
    private void dispatchPostResponderSteps(final Expectation expectation, final HttpRequest request) {
        List<ExpectationStep> postSteps = expectation.getPostResponderSteps();
        for (ExpectationStep step : postSteps) {
            dispatchStepSideEffect(step, request);
        }
    }

    /**
     * Dispatches a single step as a fire-and-forget side-effect. Supports webhook
     * (httpRequest), class callback, object callback, forward, and forward-replace targets.
     */
    private void dispatchStepSideEffect(final ExpectationStep step, final HttpRequest request) {
        scheduler.submitAsync(sheddable(() -> {
            try {
                if (step.getHttpRequest() != null) {
                    HttpRequest callbackRequest = OpenApiRuntimeExpressionResolver.resolve(
                        step.getHttpRequest(), request
                    );
                    if (refusedWebhook(callbackRequest, request, "step") != null) {
                        return;
                    }
                    httpClient.sendRequest(callbackRequest)
                        .whenComplete((response, throwable) -> {
                            if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(WARN)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(request.getLogCorrelationId())
                                        .setHttpRequest(request)
                                        .setMessageFormat("step webhook failed for request{} - " + throwable.getMessage())
                                        .setArguments(callbackRequest)
                                        .setThrowable(throwable)
                                );
                            }
                        });
                } else if (step.getHttpClassCallback() != null) {
                    getHttpResponseClassCallbackActionHandler().handle(step.getHttpClassCallback(), request);
                } else if (step.getHttpObjectCallback() != null) {
                    HttpObjectCallback callback = step.getHttpObjectCallback();
                    callback.withActionType(Action.Type.RESPONSE_OBJECT_CALLBACK);
                    String clientId = callback.getClientId();
                    if (LocalCallbackRegistry.responseClientExists(clientId)) {
                        LocalCallbackRegistry.retrieveResponseCallback(clientId).handle(request);
                    }
                } else if (step.getHttpForward() != null) {
                    getHttpForwardActionHandler().handle(step.getHttpForward(), request);
                } else if (step.getHttpOverrideForwardedRequest() != null) {
                    getHttpOverrideForwardedRequestCallbackActionHandler().handle(step.getHttpOverrideForwardedRequest(), request);
                }
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception dispatching step side-effect - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
        }), step.getDelay());
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, null, null);
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, requestDefinition, null);
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition, final Runnable postProcessor) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, requestDefinition, postProcessor, null, 0);
    }

    /**
     * Returns {@code true} when the chaos profile's drop-connection fault should
     * fire — i.e. the connection should be dropped without sending any response.
     * Uses a derived seed ({@code seed ^ 0x44524F50L}) so the drop draw is
     * independent of (but reproducible alongside) the error draw.
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count from the expectation; used for count-window gating
     */
    boolean shouldDropConnection(final HttpChaosProfile chaos, int matchCount) {
        return chaos != null
            && chaos.getDropConnectionProbability() != null
            && chaos.countWindowEligible(matchCount)
            && ChaosProbability.shouldInject(
                chaos.getDropConnectionProbability(),
                chaos.getSeed() == null ? null : chaos.getSeed() ^ 0x44524F50L
            );
    }

    /**
     * Shared helper: builds the synthetic chaos error response when the chaos
     * profile's error injection should fire, or returns {@code null} when no
     * error should be injected (probability miss, no errorStatus, null chaos,
     * or matchCount outside the count window).
     * Used by both mocked-response and forwarded-response chaos paths.
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count from the expectation; used for count-window gating
     */
    HttpResponse chaosErrorResponseOrNull(final HttpChaosProfile chaos, int matchCount) {
        if (chaos == null || chaos.getErrorStatus() == null
            || !chaos.countWindowEligible(matchCount)
            || !ChaosProbability.shouldInject(chaos.getErrorProbability(), chaos.getSeed())) {
            return null;
        }
        HttpResponse errorResponse = response()
            .withStatusCode(chaos.getErrorStatus())
            .withHeader("content-type", "application/json")
            .withBody("{\"error\":{\"type\":\"chaos_injected\",\"message\":\"injected HTTP chaos error\"}}");
        if (chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()) {
            errorResponse.withHeader("Retry-After", chaos.getRetryAfter());
        }
        return errorResponse;
    }

    /**
     * Resolves the chaos profile to apply to a request: the expectation's profile
     * gated by the time-based outage window, and — when {@code degradationRampMillis}
     * is set — a gradually-degraded copy whose {@code errorProbability} /
     * {@code dropConnectionProbability} are scaled by the ramp factor (0 at first
     * match, rising to full over the ramp window). Returns {@code null} when there is
     * no chaos profile or the request is outside the outage window. Uses the
     * controllable clock, so both the outage window and the degradation ramp are
     * deterministic under clock freeze/advance.
     */
    HttpChaosProfile effectiveChaos(final Expectation expectation) {
        final HttpChaosProfile chaos = expectation.getChaos();
        if (chaos == null) {
            return null;
        }
        final long firstMatch = expectation.getChaosFirstMatchEpochMillis();
        final long now = org.mockserver.time.TimeService.currentTimeMillis();
        if (!chaos.timeWindowEligible(firstMatch, now)) {
            return null;
        }
        if (chaos.getDegradationRampMillis() == null) {
            return chaos;
        }
        final double f = chaos.degradationFactor(firstMatch, now);
        return chaos.copy()
            .withErrorProbability(chaos.getErrorProbability() != null ? chaos.getErrorProbability() * f : null)
            .withDropConnectionProbability(chaos.getDropConnectionProbability() != null ? chaos.getDropConnectionProbability() * f : null)
            .withDegradationRampMillis(null);
    }

    /**
     * Applies the {@link RecoverAfter} retry/backoff recovery primitive: returns the failure
     * response for the first {@code failTimes} matches, otherwise the configured success response.
     *
     * <p>The configured response is returned unchanged (identity) when {@code recoverAfter} is
     * {@code null}, {@code failTimes} is {@code null} or {@code <= 0} — so a response without the
     * recovery clause behaves byte-for-byte as before, with no new state touched.
     *
     * <p>Counting is 1-based (attempt {@code n}). By default {@code n} is the per-expectation
     * {@code capturedMatchCount}. When an {@code idempotencyHeader} is configured AND present on the
     * request, {@code n} is instead the per-{@code (expectationId, header-value)} attempt from the
     * node-local {@link RecoveryAttemptRegistry} (so each idempotency key gets its own window). When
     * the header is configured but absent, it falls back to {@code capturedMatchCount}. The keyed
     * counter increments ONLY on the keyed path, so the default path adds zero overhead.
     *
     * <p>When {@code n <= failTimes} the failure response is served — the configured
     * {@code failResponse}, or a default {@code 503 Service Unavailable} when none is configured.
     */
    HttpResponse selectRecoveryResponse(final HttpResponse action, final Expectation expectation, final HttpRequest request, final int capturedMatchCount) {
        final RecoverAfter recoverAfter = action.getRecoverAfter();
        if (recoverAfter == null || recoverAfter.getFailTimes() == null || recoverAfter.getFailTimes() <= 0) {
            return action;
        }
        final int failTimes = recoverAfter.getFailTimes();
        final String idempotencyHeader = recoverAfter.getIdempotencyHeader();
        final int attempt;
        if (isNotBlank(idempotencyHeader)) {
            final String keyValue = request.getFirstHeader(idempotencyHeader);
            if (isNotBlank(keyValue)) {
                attempt = RecoveryAttemptRegistry.getInstance().nextAttempt(expectation.getId(), keyValue);
            } else {
                // header configured but absent on this request: fall back to the per-expectation count
                attempt = capturedMatchCount;
            }
        } else {
            attempt = capturedMatchCount;
        }
        if (attempt <= failTimes) {
            final HttpResponse failResponse = recoverAfter.getFailResponse();
            return failResponse != null ? failResponse : response().withStatusCode(SERVICE_UNAVAILABLE_503.code()).withReasonPhrase(SERVICE_UNAVAILABLE_503.reasonPhrase());
        }
        return action;
    }

    /**
     * Builds the synthetic quota-exceeded response when the chaos profile's stateful
     * request quota ({@code quotaName} + {@code quotaLimit} + {@code quotaWindowMillis})
     * is exceeded for the current fixed window, or returns {@code null} when the quota
     * is not fully configured, the count window is not eligible, or the request is
     * still within the quota. Counts every eligible matched request against the named
     * quota in {@link HttpQuotaRegistry}.
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count; used for count-window gating
     */
    HttpResponse quotaErrorResponseOrNull(final HttpChaosProfile chaos, int matchCount) {
        if (chaos == null || chaos.getQuotaName() == null || chaos.getQuotaLimit() == null || chaos.getQuotaWindowMillis() == null
            || !chaos.countWindowEligible(matchCount)) {
            return null;
        }
        boolean allowed = HttpQuotaRegistry.getInstance()
            .tryAcquire(chaos.getQuotaName(), chaos.getQuotaLimit(), chaos.getQuotaWindowMillis());
        if (allowed) {
            return null;
        }
        int status = chaos.getQuotaErrorStatus() != null ? chaos.getQuotaErrorStatus() : 429;
        HttpResponse errorResponse = response()
            .withStatusCode(status)
            .withHeader("content-type", "application/json")
            .withBody("{\"error\":{\"type\":\"quota_exceeded\",\"message\":\"HTTP request quota exceeded\"}}");
        if (chaos.getRetryAfter() != null && !chaos.getRetryAfter().isEmpty()) {
            errorResponse.withHeader("Retry-After", chaos.getRetryAfter());
        }
        return errorResponse;
    }

    /**
     * Builds the synthetic rate-limit-exceeded response when the matched expectation's
     * {@link RateLimit} clause is over-limit for the current window, or returns
     * {@code null} when there is no rate limit, the limit is misconfigured, or the
     * request is within the limit (in which case the normal response is returned
     * untouched). Records exactly one acquire against the shared
     * {@link org.mockserver.ratelimit.RateLimitRegistry} — this method must be called
     * from the single write path so each matched request is counted once.
     *
     * <p>On the over-limit response only, sets {@code Retry-After} (literal override
     * else {@code max(1, reset - now)} seconds) and the {@code X-RateLimit-Limit} /
     * {@code X-RateLimit-Remaining} (0) / {@code X-RateLimit-Reset} (unix seconds)
     * headers. Allowed responses are never decorated.
     *
     * @param rateLimit    the declarative rate limit (may be null)
     * @param expectationId the matched expectation id, used as the counter key when {@code rateLimit.getName()} is null
     */
    HttpResponse rateLimitResponseOrNull(final RateLimit rateLimit, final String expectationId) {
        if (rateLimit == null) {
            return null;
        }
        org.mockserver.ratelimit.RateLimitRegistry.Decision decision =
            org.mockserver.ratelimit.RateLimitRegistry.getInstance().tryAcquire(rateLimit, expectationId, configuration);
        if (decision.allowed) {
            return null;
        }
        int status = rateLimit.getErrorStatus() != null ? rateLimit.getErrorStatus() : 429;
        long nowEpochSecond = org.mockserver.time.TimeService.currentTimeMillis() / 1000L;
        HttpResponse errorResponse = response()
            .withStatusCode(status)
            .withHeader("content-type", "application/json")
            .withHeader("X-RateLimit-Limit", String.valueOf(decision.limit))
            .withHeader("X-RateLimit-Remaining", "0")
            .withHeader("X-RateLimit-Reset", String.valueOf(decision.resetEpochSecond))
            .withBody("{\"error\":{\"type\":\"rate_limit_exceeded\",\"message\":\"request rate limit exceeded\"}}");
        String retryAfter = (rateLimit.getRetryAfter() != null && !rateLimit.getRetryAfter().isEmpty())
            ? rateLimit.getRetryAfter()
            : String.valueOf(Math.max(1L, decision.resetEpochSecond - nowEpochSecond));
        errorResponse.withHeader("Retry-After", retryAfter);
        return errorResponse;
    }

    // Appended to a body to make it malformed; an unterminated JSON object so any
    // JSON payload becomes unparseable and any other payload gains clear garbage.
    private static final byte[] MALFORMED_BODY_SUFFIX = "{\"__chaos_malformed__\":".getBytes(StandardCharsets.UTF_8);

    /**
     * Applies "real response" chaos to a non-error response: body corruption
     * ({@code truncateBodyAtFraction} and/or {@code malformedBody}) and/or a slow
     * dribbled response ({@code slowResponseChunkSize} + {@code slowResponseChunkDelay}).
     * Returns the response unchanged when none of those fields is set, the count
     * window is not eligible, or the response is streaming (streaming bodies are
     * out of scope — the LLM response path has its own mid-stream truncation).
     * <ul>
     *   <li>Truncation keeps a leading fraction of the body bytes; malformed-body
     *       appends a broken-JSON fragment. The clone preserves the original
     *       content-type and drops any stale {@code Content-Length}.</li>
     *   <li>Slow response sets {@code chunkSize}/{@code chunkDelay} on a copy of the
     *       connection options so {@code NettyResponseWriter} dribbles the body in
     *       chunks (chunked transfer-encoding, so {@code Content-Length} is dropped).</li>
     * </ul>
     *
     * @param chaos      the chaos profile (may be null)
     * @param matchCount 1-based match count; used for count-window gating
     */
    HttpResponse applyResponseChaos(final HttpResponse response, final HttpChaosProfile chaos, int matchCount) {
        if (response == null || chaos == null || !chaos.countWindowEligible(matchCount)) {
            return response;
        }
        // GraphQL error envelope: when graphqlErrors is true, build a structured GraphQL
        // error body and set status 200. This takes precedence over truncate/malformed body
        // corruption because the envelope IS the intended body -- truncating or appending
        // garbage to it would defeat the purpose of simulating a realistic GraphQL error.
        // Slow-response (dribble) still composes with the GraphQL envelope since it only
        // affects delivery timing, not body content.
        final boolean graphql = Boolean.TRUE.equals(chaos.getGraphqlErrors());
        final Double fraction = chaos.getTruncateBodyAtFraction();
        final boolean malformed = Boolean.TRUE.equals(chaos.getMalformedBody());
        // When graphqlErrors is set, skip truncate/malformed body corruption
        final boolean corruptBody = !graphql && (fraction != null || malformed);
        final boolean slow = chaos.getSlowResponseChunkSize() != null && chaos.getSlowResponseChunkDelay() != null;
        if (!corruptBody && !slow && !graphql) {
            return response;
        }
        if (response.getStreamingBody() != null) {
            return response;
        }
        HttpResponse out = response.clone();
        if (graphql) {
            String envelopeJson = buildGraphqlErrorEnvelope(chaos, response);
            out.withStatusCode(200);
            out.withBody(envelopeJson);
            out.replaceHeader("content-type", "application/json");
            out.removeHeader("content-length");
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("graphql");
        } else if (corruptBody) {
            // getBodyAsRawBytes() returns an empty array (never null) when there is no body
            byte[] corrupted = response.getBodyAsRawBytes();
            if (fraction != null) {
                // fraction is validated to [0.0, 1.0] by withTruncateBodyAtFraction, so
                // keep is always within [0, corrupted.length]
                int keep = (int) Math.floor(corrupted.length * fraction);
                corrupted = java.util.Arrays.copyOf(corrupted, keep);
                org.mockserver.metrics.Metrics.incrementHttpChaosInjected("truncate");
            }
            if (malformed) {
                byte[] combined = java.util.Arrays.copyOf(corrupted, corrupted.length + MALFORMED_BODY_SUFFIX.length);
                System.arraycopy(MALFORMED_BODY_SUFFIX, 0, combined, corrupted.length, MALFORMED_BODY_SUFFIX.length);
                corrupted = combined;
                org.mockserver.metrics.Metrics.incrementHttpChaosInjected("malformed");
            }
            String contentType = response.getFirstHeader("content-type");
            if (!isNotBlank(contentType) && response.getBody() != null) {
                contentType = response.getBody().getContentType();
            }
            out.withBody(corrupted);
            if (isNotBlank(contentType)) {
                out.replaceHeader("content-type", contentType);
            }
            out.removeHeader("content-length");
        }
        if (slow) {
            out.withConnectionOptions(connectionOptionsWithChunking(response.getConnectionOptions(), chaos.getSlowResponseChunkSize(), chaos.getSlowResponseChunkDelay()));
            // chunked transfer-encoding is used when chunkSize is set, so any explicit
            // Content-Length would conflict — drop it and let the encoder chunk
            out.removeHeader("content-length");
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("slow");
        }
        return out;
    }

    /**
     * Builds the JSON body for a GraphQL error envelope. Uses Jackson ObjectMapper
     * so the message and code strings are properly escaped.
     *
     * @param chaos    the chaos profile with graphql fields
     * @param response the original response (used to attempt data preservation when graphqlNullifyData=false)
     * @return the JSON string for the GraphQL error envelope
     */
    private String buildGraphqlErrorEnvelope(final HttpChaosProfile chaos, final HttpResponse response) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper mapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode root = mapper.createObjectNode();

            // data: null (default) or the original body JSON when graphqlNullifyData=false
            boolean nullifyData = !Boolean.FALSE.equals(chaos.getGraphqlNullifyData());
            if (nullifyData) {
                root.putNull("data");
            } else {
                // attempt to preserve original body as JSON data value
                byte[] bodyBytes = response.getBodyAsRawBytes();
                if (bodyBytes != null && bodyBytes.length > 0) {
                    try {
                        com.fasterxml.jackson.databind.JsonNode originalData = mapper.readTree(bodyBytes);
                        root.set("data", originalData);
                    } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                        // original body is not valid JSON — fall back to data:null
                        root.putNull("data");
                    }
                } else {
                    root.putNull("data");
                }
            }

            // errors array with a single error object
            com.fasterxml.jackson.databind.node.ArrayNode errorsArray = root.putArray("errors");
            com.fasterxml.jackson.databind.node.ObjectNode errorObj = errorsArray.addObject();
            String message = chaos.getGraphqlErrorMessage();
            errorObj.put("message", message != null ? message : "simulated GraphQL error");

            // extensions.code only when graphqlErrorCode is set
            if (chaos.getGraphqlErrorCode() != null) {
                com.fasterxml.jackson.databind.node.ObjectNode extensions = errorObj.putObject("extensions");
                extensions.put("code", chaos.getGraphqlErrorCode());
            }

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            // fallback: hand-build a minimal envelope (should never happen with Jackson)
            return "{\"data\":null,\"errors\":[{\"message\":\"simulated GraphQL error\"}]}";
        }
    }

    /**
     * Returns a fresh {@link ConnectionOptions} carrying the chaos chunk settings,
     * copying any other fields from {@code src} so the original (shared) response
     * connection options are not mutated.
     */
    private ConnectionOptions connectionOptionsWithChunking(ConnectionOptions src, Integer chunkSize, Delay chunkDelay) {
        ConnectionOptions out = ConnectionOptions.connectionOptions();
        if (src != null) {
            out.withSuppressContentLengthHeader(src.getSuppressContentLengthHeader())
                .withContentLengthHeaderOverride(src.getContentLengthHeaderOverride())
                .withSuppressConnectionHeader(src.getSuppressConnectionHeader())
                .withKeepAliveOverride(src.getKeepAliveOverride())
                .withCloseSocket(src.getCloseSocket())
                .withCloseSocketDelay(src.getCloseSocketDelay());
        }
        return out.withChunkSize(chunkSize).withChunkDelay(chunkDelay);
    }

    /**
     * Core response-writing choke point. When a non-null {@code chaos} profile is provided,
     * HTTP chaos injection is applied before the response is written:
     * <ol>
     *   <li><b>Error injection</b> — if the chaos probability fires and {@code errorStatus} is set,
     *       the mocked response is replaced with a synthetic error response. When error injection
     *       fires, the original response's action delay is discarded (only chaos latency + global
     *       delay apply).</li>
     *   <li><b>Latency injection</b> — if {@code chaos.getLatency()} is set, it is added to the
     *       delay applied before writing the response (combined with action + global delay).</li>
     * </ol>
     */
    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, requestDefinition, postProcessor, chaos, matchCount, null);
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount, final ChannelHandlerContext ctx) {
        writeResponseActionResponse(response, responseWriter, request, action, synchronous, requestDefinition, postProcessor, chaos, matchCount, ctx, null);
    }

    void writeResponseActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final RequestDefinition requestDefinition, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount, final ChannelHandlerContext ctx, final RateLimit rateLimit) {
        // Chaos: drop connection takes priority over error and latency
        if (shouldDropConnection(chaos, matchCount)) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("drop");
            if (ctx != null) {
                getHttpErrorActionHandler().handle(HttpError.error().withDropConnection(true), ctx);
            }
            if (postProcessor != null) {
                postProcessor.run();
            }
            return;
        }

        // FILE response body: read the referenced file so its CONTENTS (not the filePath string) reach the
        // wire. No-op unless the body is a FileBody — for the static RESPONSE path HttpResponseActionHandler
        // has already materialised it, so this covers the object/class-callback, response-template and (via
        // writeForwardActionResponse) forward-override funnels. A missing file becomes a clean logged 500.
        final HttpResponse materialisedResponse = materialiseFileBodyResponse(response, request);

        // Rate limit (declarative, protocol-agnostic) takes precedence over the chaos quota and the
        // probabilistic chaos error. tryAcquire mutates registry state, so it is invoked exactly once
        // here in the single write path.
        HttpResponse rateLimitError = rateLimitResponseOrNull(rateLimit, action.getExpectationId());
        // Chaos: the deterministic quota (rate limit) takes priority over the probabilistic error
        HttpResponse quotaError = rateLimitError != null ? null : quotaErrorResponseOrNull(chaos, matchCount);
        HttpResponse chaosError = rateLimitError != null ? rateLimitError : (quotaError != null ? quotaError : chaosErrorResponseOrNull(chaos, matchCount));
        final HttpResponse effectiveResponse = chaosError != null ? chaosError : applyResponseChaos(materialisedResponse, chaos, matchCount);
        // Gate latency by the same count window as error injection
        final Delay chaosLatency = chaos != null && chaos.countWindowEligible(matchCount) ? chaos.getLatency() : null;

        // Metrics: record chaos faults only when they actually fire
        if (rateLimitError != null) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("rateLimit");
        } else if (quotaError != null) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("quota");
        } else if (chaosError != null) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("error");
        }
        if (chaosLatency != null) {
            org.mockserver.metrics.Metrics.incrementHttpChaosInjected("latency");
        }

        // WS2.3: resolve an opt-in template delay (duration computed from the request) into a concrete
        // millisecond delay while the request is in scope. Non-template (static/distribution) delays are
        // returned unchanged, so existing behaviour is byte-for-byte preserved.
        Delay resolvedActionDelay = getDelayTemplateResolver().resolve(effectiveResponse.getDelay(), request);
        Delay[] delays = combineWithChaosAndGlobalDelay(resolvedActionDelay, chaosLatency);
        // Injected-vs-real latency waterfall: capture what MockServer is about to inject so the dashboard
        // can distinguish it from real processing time. Sampled once here (exact for static delays; an
        // independent draw for distribution delays) and measured against a nanoTime taken before the
        // injected delays are applied. A few longs + one Timing object per response — no hot-path threads.
        final long timingStartNanos = System.nanoTime();
        final Long injectedChaosLatencyMillis = chaosLatency != null ? chaosLatency.sampleValueMillis() : null;
        final long resolvedActionDelayMillis = resolvedActionDelay != null ? resolvedActionDelay.sampleValueMillis() : 0L;
        final Long configuredGlobalDelayMillis = configuration.globalResponseDelayMillis();
        final long globalDelayMillis = configuredGlobalDelayMillis != null && configuredGlobalDelayMillis > 0 ? configuredGlobalDelayMillis : 0L;
        final long totalInjectedDelayMillis = resolvedActionDelayMillis + globalDelayMillis;
        final Long injectedDelayMillis = totalInjectedDelayMillis > 0 ? totalInjectedDelayMillis : null;
        final Runnable write = () -> {
            // breakpoint: RESPONSE-phase pause for matched mock responses (RESPONSE / RESPONSE_TEMPLATE /
            // RESPONSE_CLASS_CALLBACK only — scoped by action type so the protocol-specific write paths that
            // share this writer, e.g. LLM / gRPC / WebSocket / SSE fall-backs, are NOT intercepted). Holds the
            // post-chaos response before writing; chaos is not re-applied after manual resolution.
            if (!synchronous && isMockResponseBreakpointEligible(action)) {
                final org.mockserver.mock.breakpoint.BreakpointMatcher responseBreakpoint =
                    org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance()
                        .findResponseMatch(request, effectiveResponse, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE);
                if (responseBreakpoint != null) {
                    final java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
                        ? scheduler.getExecutorService() : Runnable::run;
                    // breakpoint: measure how long the exchange is held paused so the waterfall can attribute
                    // the hold to MockServer rather than to real processing time.
                    final long breakpointPauseNanos = System.nanoTime();
                    if (attemptResponseBreakpoint(responseBreakpoint, request, effectiveResponse, action.getExpectationId(), responseWriter, continuationExecutor, responseToWrite -> {
                        final long breakpointHeldMillis = Math.max(0L, (System.nanoTime() - breakpointPauseNanos) / 1_000_000L);
                        attachMockServedTiming(responseToWrite, timingStartNanos, injectedChaosLatencyMillis, injectedDelayMillis, breakpointHeldMillis);
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(EXPECTATION_RESPONSE)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setHttpResponse(responseToWrite)
                                .setExpectationId(action.getExpectationId())
                                .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                                .setArguments(responseToWrite, request, action, action.getExpectationId())
                        );
                        HttpResponse validatedResponse = validateOpenAPIResponse(responseToWrite, request, action, requestDefinition);
                        finalInformationalResponseWarning.warnOnce(mockServerLogger, request, validatedResponse, action);
                        responseWriter.writeResponse(request, validatedResponse, false);
                        emitRequestSpan(request, validatedResponse, action, ctx, 0);
                    }, postProcessor)) {
                        return; // async — postProcessor runs in the breakpoint continuation
                    }
                    // cap reached — fall through to normal write
                }
            }
            try {
                // Injected-vs-real latency waterfall: give the mock response a timing block (measured total +
                // the injected components) before it is logged/written so the dashboard renders a waterfall for
                // mock-served entries too, not just proxied ones. No breakpoint fired on this path.
                attachMockServedTiming(effectiveResponse, timingStartNanos, injectedChaosLatencyMillis, injectedDelayMillis, null);
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(EXPECTATION_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(effectiveResponse)
                        .setExpectationId(action.getExpectationId())
                        .setMessageFormat("returning response:{}for request:{}for action:{}from expectation:{}")
                        .setArguments(effectiveResponse, request, action, action.getExpectationId())
                );
                HttpResponse validatedResponse = validateOpenAPIResponse(effectiveResponse, request, action, requestDefinition);
                finalInformationalResponseWarning.warnOnce(mockServerLogger, request, validatedResponse, action);
                responseWriter.writeResponse(request, validatedResponse, false);
                emitRequestSpan(request, validatedResponse, action, ctx, 0);
            } finally {
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
        };
        // only a delayed write can be refused, so the undelayed hot path allocates no fallback
        scheduler.schedule(delays.length == 0 ? write : rejectable(write, overloadResponse(request, responseWriter, postProcessor)), synchronous, delays);
    }

    /**
     * As {@link Scheduler#rejectable}, but only when the dispatch can actually be delayed (an action delay or
     * {@code globalResponseDelayMillis}), so an undelayed dispatch allocates no fallback.
     */
    private Runnable rejectableIfDelayed(final Runnable task, final Delay actionDelay, final HttpRequest request, final ResponseWriter responseWriter, final Runnable postProcessor) {
        final Long globalDelayMillis = configuration.globalResponseDelayMillis();
        final boolean delayed = actionDelay != null || (globalDelayMillis != null && globalDelayMillis > 0);
        return delayed ? rejectable(task, overloadResponse(request, responseWriter, postProcessor)) : task;
    }

    /**
     * The answer given in place of a delayed or templated action that a full bounded queue refused (see
     * {@link Scheduler#rejectable}): a 503 written directly, so no chaos, rate limit or delay applies to it.
     */
    private Runnable overloadResponse(final HttpRequest request, final ResponseWriter responseWriter, final Runnable postProcessor) {
        return () -> {
            try {
                responseWriter.writeResponse(request, response()
                    .withStatusCode(503)
                    .withReasonPhrase("Service Unavailable")
                    .withHeader("Retry-After", "1")
                    .withBody("MockServer is overloaded, too many delayed or templated responses are already waiting"), false);
            } finally {
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
        };
    }

    /**
     * Whether a matched mock-response action is eligible for the RESPONSE-phase breakpoint. Scoped to the
     * buffered mock-response action types so the protocol-specific paths (LLM, gRPC, WebSocket, SSE) that
     * share {@link #writeResponseActionResponse} are not intercepted.
     */
    private static boolean isMockResponseBreakpointEligible(final Action action) {
        if (action == null || action.getType() == null) {
            return false;
        }
        switch (action.getType()) {
            case RESPONSE:
            case RESPONSE_TEMPLATE:
            case RESPONSE_CLASS_CALLBACK:
                return true;
            default:
                return false;
        }
    }

    private org.mockserver.templates.engine.DelayTemplateResolver getDelayTemplateResolver() {
        if (delayTemplateResolver == null) {
            delayTemplateResolver = new org.mockserver.templates.engine.DelayTemplateResolver(mockServerLogger, configuration);
        }
        return delayTemplateResolver;
    }

    private Delay[] combineWithGlobalDelay(Delay actionDelay) {
        Long globalDelayMillis = configuration.globalResponseDelayMillis();
        if (globalDelayMillis != null && globalDelayMillis > 0) {
            Delay globalDelay = Delay.milliseconds(globalDelayMillis);
            if (actionDelay != null) {
                return new Delay[]{actionDelay, globalDelay};
            }
            return new Delay[]{globalDelay};
        }
        if (actionDelay != null) {
            return new Delay[]{actionDelay};
        }
        return new Delay[0];
    }

    /**
     * Combines the action delay, optional chaos latency, and global delay into a
     * single array of delays to apply before writing the response.
     */
    private Delay[] combineWithChaosAndGlobalDelay(Delay actionDelay, Delay chaosLatency) {
        Delay[] baseDelays = combineWithGlobalDelay(actionDelay);
        if (chaosLatency == null) {
            return baseDelays;
        }
        Delay[] combined = new Delay[baseDelays.length + 1];
        System.arraycopy(baseDelays, 0, combined, 0, baseDelays.length);
        combined[baseDelays.length] = chaosLatency;
        return combined;
    }

    /**
     * Attaches an injected-vs-real latency {@link Timing} block to a mock-served response so the dashboard
     * waterfall is not proxy-only. Mock responses have no upstream connect/TTFB timing, so the block carries
     * the measured total plus the injected components (chaos latency, action delay, breakpoint hold); the UI
     * derives "real" processing time as total minus injected. Timing is metadata only — it is serialised for
     * the dashboard/API but never written to the HTTP wire (mirrors the forwarded-response timing block).
     */
    private static void attachMockServedTiming(final HttpResponse response, final long startNanos, final Long injectedChaosLatencyMillis, final Long injectedDelayMillis, final Long breakpointHeldMillis) {
        if (response == null) {
            return;
        }
        // Only attach a timing block when MockServer actually injected latency (chaos, delay, or a
        // breakpoint hold) and the response does not already carry a timing. A plain mock response with
        // no injection is left untouched, so its serialised form — and therefore recorded log messages
        // and retrieved responses — stays byte-identical to the pre-waterfall behaviour. The waterfall
        // only has something to attribute when there is injected latency, which is exactly this case.
        final boolean hasInjection = injectedChaosLatencyMillis != null
            || injectedDelayMillis != null
            || breakpointHeldMillis != null;
        if (!hasInjection && response.getTiming() == null) {
            return;
        }
        final long totalMillis = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        // Copy rather than mutate any pre-existing Timing: an in-JVM expectation whose response
        // carries a preset Timing would otherwise share (and race on) one instance across requests.
        final Timing existing = response.getTiming();
        Timing timing = Timing.timing();
        if (existing != null) {
            timing
                .withRequestStartedMillis(existing.getRequestStartedMillis())
                .withConnectionEstablishedMillis(existing.getConnectionEstablishedMillis())
                .withResponseReceivedMillis(existing.getResponseReceivedMillis())
                .withConnectionTimeInMillis(existing.getConnectionTimeInMillis())
                .withTimeToFirstByteInMillis(existing.getTimeToFirstByteInMillis())
                .withTotalTimeInMillis(existing.getTotalTimeInMillis());
        }
        response.withTiming(timing
            .withTotalTimeInMillis(totalMillis)
            .withInjectedChaosLatencyMillis(injectedChaosLatencyMillis)
            .withInjectedDelayMillis(injectedDelayMillis)
            .withBreakpointHeldMillis(breakpointHeldMillis));
    }

    /**
     * Validates a mock response against the OpenAPI spec it was generated from when
     * {@code openAPIResponseValidation} is enabled.
     * <p>
     * By default validation is advisory only — violations are logged and the original response is
     * returned unchanged. When {@code enforceResponseValidationForMocks} is also enabled a response
     * that fails validation is replaced with a 502 describing the violations, matching the
     * validation-proxy path's {@code validateProxyEnforce} behaviour.
     *
     * @return the original response (valid, validation disabled, or report-only) or a 502 replacement (enforce mode + violations)
     */
    private HttpResponse validateOpenAPIResponse(final HttpResponse response, final HttpRequest request, final Action action, final RequestDefinition requestDefinition) {
        if (configuration.openAPIResponseValidation() && requestDefinition instanceof OpenAPIDefinition openAPIDefinition) {
            if (isNotBlank(openAPIDefinition.getSpecUrlOrPayload()) && isNotBlank(openAPIDefinition.getOperationId())) {
                List<String> validationErrors = OpenAPIResponseValidator.validate(
                    openAPIDefinition.getSpecUrlOrPayload(),
                    openAPIDefinition.getOperationId(),
                    response,
                    mockServerLogger,
                    configuration
                );
                if (!validationErrors.isEmpty()) {
                    if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(OPENAPI_RESPONSE_VALIDATION_FAILED)
                                .setLogLevel(Level.WARN)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setHttpResponse(response)
                                .setExpectationId(action.getExpectationId())
                                .setMessageFormat("OpenAPI response validation failed for operation " + openAPIDefinition.getOperationId() + ":{}for request:{}for response:{}")
                                .setArguments(String.join(NEW_LINE, validationErrors), request, response)
                        );
                    }
                    if (Boolean.TRUE.equals(configuration.enforceResponseValidationForMocks())) {
                        return response()
                            .withStatusCode(502)
                            .withBody("OpenAPI response validation failed: " + String.join("; ", validationErrors));
                    }
                }
            }
        }
        return response;
    }

    void executeAfterForwardActionResponse(final HttpForwardActionResult responseFuture, final BiConsumer<HttpResponse, Throwable> command, final boolean synchronous) {
        scheduler.submit(responseFuture, command, synchronous);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous) {
        writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, null, null, 0);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final Runnable postProcessor) {
        writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, postProcessor, null, 0);
    }

    /**
     * Forward response choke point with optional HTTP chaos injection.
     * <p>
     * When {@code chaos} is non-null the same drop/error/latency logic used for
     * mocked responses is applied to the upstream response received from the forwarded
     * request. For streaming responses an injected error replaces the stream with a
     * non-streaming synthetic error response; latency is applied before writing.
     * <p>
     * Deferred: {@code FORWARD_OBJECT_CALLBACK}'s own write path and the unmatched /
     * anonymous proxy-pass path are not yet wired and will follow in later slices.
     */
    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount) {
        writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, postProcessor, chaos, matchCount, null);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount, final ChannelHandlerContext ctx) {
        writeForwardActionResponse(responseFuture, responseWriter, request, action, synchronous, postProcessor, chaos, matchCount, ctx, null);
    }

    void writeForwardActionResponse(final HttpForwardActionResult responseFuture, final ResponseWriter responseWriter, final HttpRequest request, final Action action, boolean synchronous, final Runnable postProcessor, final HttpChaosProfile chaos, int matchCount, final ChannelHandlerContext ctx, final RateLimit rateLimit) {
        scheduler.submit(responseFuture, () -> {
            try {
                long forwardStartNanos = org.mockserver.time.TimeService.nanoTime();
                HttpResponse response = responseFuture.getHttpResponse().get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                long responseTimeMs = (org.mockserver.time.TimeService.nanoTime() - forwardStartNanos) / 1_000_000;

                // chaos: drop connection takes priority over error and latency
                if (shouldDropConnection(chaos, matchCount)) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("drop");
                    if (ctx != null) {
                        getHttpErrorActionHandler().handle(HttpError.error().withDropConnection(true), ctx);
                    }
                    if (postProcessor != null) {
                        postProcessor.run();
                    }
                    return;
                }

                // FILE response body from a forward responseOverride: read the referenced file so its
                // CONTENTS (not the filePath string) reach the wire (issue #2450). No-op unless the
                // overridden response body is a FileBody; a missing file becomes a clean logged 500. A
                // body-affecting override already disables streaming, so the response here is fully buffered.
                response = materialiseFileBodyResponse(response, request);

                // Rate limit (declarative, protocol-agnostic) takes precedence over the chaos quota and the
                // probabilistic chaos error. tryAcquire mutates registry state, so it is invoked exactly once here.
                HttpResponse rateLimitError = rateLimitResponseOrNull(rateLimit, action.getExpectationId());
                // chaos: quota (deterministic rate limit) then probabilistic error injection on forwarded responses — replaces the upstream response
                HttpResponse quotaError = rateLimitError != null ? null : quotaErrorResponseOrNull(chaos, matchCount);
                HttpResponse chaosError = rateLimitError != null ? rateLimitError : (quotaError != null ? quotaError : chaosErrorResponseOrNull(chaos, matchCount));
                final HttpResponse effectiveResponse = chaosError != null ? chaosError : applyResponseChaos(response, chaos, matchCount);
                // Gate latency by the same count window as error injection
                final Delay chaosLatency = chaos != null && chaos.countWindowEligible(matchCount) ? chaos.getLatency() : null;
                final boolean chaosErrorInjected = chaosError != null;

                // Metrics: record chaos faults only when they actually fire
                if (rateLimitError != null) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("rateLimit");
                } else if (quotaError != null) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("quota");
                } else if (chaosErrorInjected) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("error");
                }
                if (chaosLatency != null) {
                    org.mockserver.metrics.Metrics.incrementHttpChaosInjected("latency");
                    // Injected-vs-real latency waterfall: annotate the forwarded response's existing timing
                    // block (real connect/TTFB/upstream from NettyHttpClient) with the chaos latency MockServer
                    // is about to inject, so the dashboard can separate injected from real time on proxied flows.
                    if (effectiveResponse != null && effectiveResponse.getTiming() != null) {
                        effectiveResponse.getTiming().withInjectedChaosLatencyMillis(chaosLatency.sampleValueMillis());
                    }
                }

                // Drift detection: asynchronously compare the real upstream response against
                // any response-type stub expectations matching this request.
                // responseTimeMs already captured at line above via nanoTime delta.
                // Gated by driftDetectionEnabled (master switch, default on) and sampled by
                // driftSampleRate (default 1.0 = every eligible forward). ChaosProbability's
                // draw is thread-safe (ThreadLocalRandom) and treats >=1.0 as always / <=0 as
                // never, so out-of-range rates are handled safely without extra clamping here.
                if (configuration.driftDetectionEnabled()
                    && ChaosProbability.shouldInject(configuration.driftSampleRate(), null)) {
                    analyseDrift(request, response, responseTimeMs);
                }

                // OpenTelemetry: emit a request-level span for the forwarded request
                emitRequestSpan(request, effectiveResponse, action, ctx, responseTimeMs, responseFuture.getRemoteAddress());
                // Metrics: per-upstream forward latency + status (uses the precise Timing
                // from the real upstream response, before any chaos replacement)
                recordForwardMetrics(action, response, responseFuture.getRemoteAddress(), responseTimeMs);

                // Determine streaming BEFORE the breakpoint check so GenAI span can
                // be emitted for non-streaming responses ahead of a possible early return.
                boolean isStreaming = !chaosErrorInjected && effectiveResponse != null && effectiveResponse.getStreamingBody() != null;

                // OpenTelemetry: emit GenAI span for non-streaming responses BEFORE the
                // breakpoint check — a breakpoint-held response must still get a GenAI span
                // capturing the original upstream response (mirrors unmatched-proxy path).
                // Streaming GenAI spans are deferred to writeStreamingForwardActionResponse.
                if (!isStreaming) {
                    emitForwardGenAiSpan(responseFuture.getHttpRequest(), response);
                }

                // Response breakpoint: hold non-streaming responses before writing to client.
                // IMPORTANT: does NOT block any thread — chains the client write onto the
                // decision future via thenAcceptAsync (same pattern as request breakpoints).
                {
                    org.mockserver.mock.breakpoint.BreakpointMatcher responseBreakpoint3 =
                        (!synchronous && !isStreaming) ? org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().findResponseMatch(request, effectiveResponse, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE) : null;
                    if (responseBreakpoint3 != null) {
                        java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
                            ? scheduler.getExecutorService()
                            : Runnable::run;
                        final boolean capturedChaosErrorInjected = chaosErrorInjected;
                        final long breakpointResponseTimeMs = effectiveForwardLatencyMs(response, responseTimeMs);
                        if (attemptResponseBreakpoint(responseBreakpoint3, request, effectiveResponse,
                            action != null ? action.getExpectationId() : null, responseWriter, continuationExecutor, responseToWrite -> {
                            HttpResponse logResponse = responseToWrite.clone();
                            logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(breakpointResponseTimeMs));
                            String logMessageFormat = capturedChaosErrorInjected
                                ? "returning chaos-injected error response:{}replacing forwarded response" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}"
                                : "returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}";
                            // Log before write — see the non-streaming writeCommand below for why the
                            // FIFO visibility guarantee requires publishing ahead of the client write.
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(FORWARDED_REQUEST)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setHttpResponse(logResponse)
                                    .setExpectation(request, logResponse)
                                    .setExpectationId(action != null ? action.getExpectationId() : null)
                                    .setMessageFormat(logMessageFormat)
                                    .setArguments(logResponse, responseFuture.getHttpRequest(), deferredCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress()), action, action != null ? action.getExpectationId() : null)
                            );
                            responseWriter.writeResponse(request, responseToWrite, false);
                        }, postProcessor)) {
                            // Return immediately — do NOT block the scheduler worker thread
                            return;
                        }
                        // If cap reached, fall through to normal write
                    }
                }

                // Factor the write (streaming vs non-streaming) into a single command so
                // it can be dispatched either directly or via the non-blocking scheduler.
                final Runnable writeCommand;
                final long capturedForwardStartNanos = forwardStartNanos;
                if (isStreaming) {
                    // Streaming path: GenAI span is deferred to the completion listener
                    // inside writeStreamingForwardActionResponse, once the stream has ended
                    writeCommand = () -> writeStreamingForwardActionResponse(effectiveResponse, responseWriter, request, action, responseFuture, postProcessor, capturedForwardStartNanos);
                } else {
                    // Non-streaming path: GenAI span already emitted above (before breakpoint check)
                    final long nonStreamingResponseTimeMs = effectiveForwardLatencyMs(response, responseTimeMs);
                    writeCommand = () -> {
                        HttpResponse logResponse = effectiveResponse.clone();
                        logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(nonStreamingResponseTimeMs));
                        String logMessageFormat = chaosErrorInjected
                            ? "returning chaos-injected error response:{}replacing forwarded response" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}"
                            : "returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}";
                        // Log BEFORE writing the response, matching the mocked-response path. The
                        // visibility guarantee for verify/retrieve rests on disruptor FIFO ordering:
                        // drainDisruptor can only wait for entries already published, so publishing
                        // after the client has been given the response leaves a window in which a
                        // client that immediately verifies (or retrieves recorded requests) misses
                        // this exchange. Publishing first closes that window.
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(FORWARDED_REQUEST)
                                .setLogLevel(Level.INFO)
                                .setCorrelationId(request.getLogCorrelationId())
                                .setHttpRequest(request)
                                .setHttpResponse(logResponse)
                                .setExpectation(request, logResponse)
                                .setExpectationId(action.getExpectationId())
                                .setMessageFormat(logMessageFormat)
                                .setArguments(logResponse, responseFuture.getHttpRequest(), deferredCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress()), action, action.getExpectationId())
                        );
                        responseWriter.writeResponse(request, effectiveResponse, false);
                        if (postProcessor != null) {
                            postProcessor.run();
                        }
                    };
                }

                // Apply chaos latency via the non-blocking scheduler timer rather than a
                // blocking Thread.sleep — avoids starving the bounded scheduler thread pool.
                // Only chaos latency is scheduled here because the forward path's action +
                // global delay was already applied when the forward handler was dispatched
                // (see combineWithGlobalDelay(actionDelay) in the processAction switch).
                if (chaosLatency != null) {
                    scheduler.schedule(delayOptional(writeCommand), synchronous, chaosLatency);
                } else {
                    writeCommand.run();
                }
            } catch (Throwable throwable) {
                handleExceptionDuringForwardingRequest(action, request, responseWriter, throwable);
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
            // a failure with a reason is logged once, with the request, by handleExceptionDuringForwardingRequest
        }, synchronous, throwable -> upstreamFailureReason(throwable) == null);
    }

    private void writeStreamingForwardActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action, final HttpForwardActionResult responseFuture, final Runnable postProcessor, final long forwardStartNanos) {
        final StreamingBody streamingBody = response.getStreamingBody();

        // Write the response head through the response writer (which will subscribe to the streaming body)
        //
        // NOTE — deliberate exception to the "log before write" ordering used by every other forward
        // path. The FORWARDED_REQUEST entry for a streaming response carries the captured body
        // (streamingBody.capturedBytes()) and the total stream duration, neither of which exists until
        // the stream has completed — so the entry cannot be published ahead of the write without
        // logging an empty, mistimed exchange. The consequence is that a client which starts consuming
        // a stream and verifies before the stream ends may not yet see this entry; that is inherent to
        // streaming (the exchange genuinely is not finished) rather than a lost race. Verifications
        // against streamed exchanges should await stream completion.
        final LlmStreamUsageScanner streamUsage = observeForwardedLlmStream(responseFuture.getHttpRequest(), response);
        responseWriter.writeResponse(request, response, false);

        // Register a completion callback on the streaming body to write the log entry
        // We wrap the existing subscriber's onComplete/onError to add logging after the stream finishes
        final Runnable logAndPostProcess = () -> {
            try {
                HttpResponse logResponse = response.clone();
                byte[] captured = streamingBody.capturedBytes();
                setCapturedStreamingBody(logResponse, captured);
                attachStreamingHeaders(logResponse, streamingBody);
                long streamResponseTimeMs = (org.mockserver.time.TimeService.nanoTime() - forwardStartNanos) / 1_000_000;
                logResponse.withHeader(RESPONSE_TIME_HEADER, String.valueOf(streamResponseTimeMs));
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(FORWARDED_REQUEST)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(logResponse)
                        .setExpectation(request, logResponse)
                        .setExpectationId(action != null ? action.getExpectationId() : null)
                        .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}" + (action != null ? "for action:{}from expectation:{}" : ""))
                        .setArguments(action != null
                            ? new Object[]{logResponse, responseFuture.getHttpRequest(), deferredCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress()), action, action.getExpectationId()}
                            : new Object[]{logResponse, responseFuture.getHttpRequest(), deferredCurl(responseFuture.getHttpRequest(), responseFuture.getRemoteAddress())}
                        )
                );
                // OpenTelemetry: emit GenAI span after stream completes
                emitStreamedForwardGenAiSpan(responseFuture.getHttpRequest(), logResponse, streamingBody, streamUsage);
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(WARN)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("exception logging streaming forward response - " + throwable.getMessage())
                            .setThrowable(throwable)
                    );
                }
            } finally {
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
        };

        streamingBody.addCompletionListener(logAndPostProcess);
    }

    /**
     * The curl form of a forwarded request, rendered when its log entry is rendered rather than stored as a
     * String copy of the request on the retained entry, and from the redacted request when redactSecretsInLog is on.
     */
    private DeferredLogArgument deferredCurl(HttpRequest request, InetSocketAddress remoteAddress) {
        return DeferredLogArgument.curl(httpRequestToCurlSerializer, request, remoteAddress);
    }

    void writeForwardActionResponse(final HttpResponse response, final ResponseWriter responseWriter, final HttpRequest request, final Action action) {
        try {
            // Log before write — the verify/retrieve visibility guarantee depends on the log entry
            // being published to the disruptor before the client can observe the response and act
            // on it (see the non-streaming forward writeCommand for the full rationale).
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(FORWARDED_REQUEST)
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setHttpResponse(response)
                    .setExpectation(request, response)
                    .setExpectationId(action.getExpectationId())
                    .setMessageFormat("returning response:{}for forwarded request" + NEW_LINE + NEW_LINE + " in json:{}" + NEW_LINE + NEW_LINE + " in curl:{}for action:{}from expectation:{}")
                    .setArguments(response, request, deferredCurl(request, null), action, action.getExpectationId())
            );
            responseWriter.writeResponse(request, response, false);
        } catch (Throwable throwable) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXCEPTION)
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(throwable.getMessage())
                    .setThrowable(throwable)
            );
        }
    }

    /**
     * An unreachable upstream proxy reads as its cause inside a future's exception, but not on its own where its class
     * name stands in for a missing message, so it is answered as its cause.
     */
    private static Throwable answeredAsItsCause(Throwable failure) {
        return failure instanceof UpstreamProxyUnreachableException ? failure.getCause() : failure;
    }

    /**
     * Why a forward failed, for a reason the client's {@code 502} names: the connection to the upstream could not be
     * set up from MockServer's configuration, TLS with the upstream failed, the upstream sent an HTTP/2 error, or its
     * HTTP/1.1 response could not be decoded. Each comes from the TLS, HTTP/2, set-up or decoding code and is bounded
     * as a fault's log entry is (256 characters a message, and a non-TLS answer as its byte count), so header values
     * and body bytes are never quoted; an HTTP/2 message or a header the decoder refused can name a header, and a
     * status line that is not HTTP can be quoted. Null for any other failure.
     */
    static String upstreamFailureReason(Throwable failure) {
        return upstreamFailureReason(failure, UnaryOperator.identity());
    }

    /**
     * As {@link #upstreamFailureReason(Throwable)}, with {@code scrub} applied to each message before it is cut.
     */
    static String upstreamFailureReason(Throwable failure, UnaryOperator<String> scrub) {
        UndecodableResponseException undecodable = UndecodableResponseException.in(failure);
        if (undecodable != null) {
            return "response from the upstream could not be decoded: " + boundedFaultDescription(undecodable.getCause() != null ? undecodable.getCause() : undecodable, scrub);
        }
        ClientConfigurationException configurationError = ClientConfigurationException.in(failure);
        if (configurationError != null) {
            // only the top of the cause: one deeper may quote a configured file
            Throwable setUpFailure = configurationError.getCause() != null ? configurationError.getCause() : configurationError;
            return "connection to the upstream could not be set up: " + boundedFaultDescription(setUpFailure, scrub);
        }
        Http2Exception http2Error = Http2CodecUtil.getEmbeddedHttp2Exception(failure);
        if (http2Error != null) {
            return "HTTP/2 error from the upstream: " + http2Error.error() + ": " + boundedFaultMessage(http2Error, scrub);
        }
        SSLException tlsFailure = sslCause(failure);
        if (tlsFailure != null || sslHandshakeException(failure)) {
            return "TLS with the upstream failed: " + boundedFaultDescriptionWithRootCause(tlsFailure != null ? tlsFailure : failure, scrub);
        }
        return null;
    }

    void handleExceptionDuringForwardingRequest(Action action, HttpRequest request, ResponseWriter responseWriter, Throwable exception) {
        exception = answeredAsItsCause(exception);
        if (returnedBlockedTarget(responseWriter, request, exception, "forward")
            || returnedHeaderLimitFailure(responseWriter, request, exception)
            || returnedUpstreamFailureReason(exception, request, responseWriter, new LogEntry().setMessageFormat("failed to forward request{}for action{}because:{}"), action)) {
            return;
        }
        if (connectionException(exception)) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("failed to connect to remote socket while forwarding request{}for action{}")
                        .setArguments(request, action)
                        .setThrowable(exception)
                );
            }
            returnBadGateway(responseWriter, request, "failed to connect to remote socket while forwarding request");
        } else {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(EXCEPTION)
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(exception != null ? isNotBlank(exception.getMessage()) ? exception.getMessage() : exception.getClass().getSimpleName() : null)
                    .setThrowable(exception)
            );
            returnBadGateway(responseWriter, request, exception != null ? exception.getMessage() : null);
        }
    }

    /**
     * Attempts to hold a non-streaming response at a breakpoint (RESPONSE phase).
     * Dispatches over the callback WebSocket to the owning client.
     *
     * @param breakpoint          the matched breakpoint (non-null; clientId always present)
     * @param request             the original request
     * @param response            the upstream response to hold
     * @param expectationId       matched expectation id, or null
     * @param responseWriter      writer for the client channel
     * @param continuationExecutor executor for async continuation
     * @param onResolved          callback receiving the resolved response
     * @param postProcessor       optional post-processing
     * @return true if the breakpoint was activated (caller should return); false if cap reached (fall through)
     */
    private boolean attemptResponseBreakpoint(
        org.mockserver.mock.breakpoint.BreakpointMatcher breakpoint,
        HttpRequest request,
        HttpResponse response,
        String expectationId,
        ResponseWriter responseWriter,
        java.util.concurrent.Executor continuationExecutor,
        java.util.function.Consumer<HttpResponse> onResolved,
        Runnable postProcessor
    ) {
        // WS-callback dispatch (clientId is always present — required since 7b)
        java.util.concurrent.CompletableFuture<org.mockserver.mock.breakpoint.BreakpointDecision> decisionFuture =
            org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher.getInstance().dispatchResponse(
                breakpoint.getClientId(), breakpoint.getId(), request, response,
                httpStateHandler.getWebSocketClientRegistry(),
                configuration, mockServerLogger
            );
        // null means cap reached or client disconnected — fall through

        if (decisionFuture != null) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setMessageFormat("upstream response paused at breakpoint, awaiting resolution for request:{}response:{}")
                        .setArguments(request, response)
                );
            }
            // Chain the CONTINUE/MODIFY/ABORT decision onto the continuation executor.
            final HttpResponse capturedResponse = response;
            decisionFuture.thenAcceptAsync(decision -> {
                try {
                    HttpResponse responseToWrite;
                    switch (decision.getAction()) {
                        case ABORT:
                            responseToWrite = decision.getAbortResponse();
                            if (responseToWrite == null) {
                                responseToWrite = org.mockserver.model.HttpResponse.response().withStatusCode(503).withReasonPhrase("Breakpoint Aborted");
                            }
                            break;
                        case MODIFY:
                            responseToWrite = decision.getModifiedResponse();
                            if (responseToWrite == null) {
                                responseToWrite = capturedResponse;
                            }
                            break;
                        case CONTINUE:
                        default:
                            responseToWrite = capturedResponse;
                            break;
                    }
                    onResolved.accept(responseToWrite);
                    if (postProcessor != null) {
                        postProcessor.run();
                    }
                } catch (Throwable t) {
                    returnBadGateway(responseWriter, request, "response breakpoint continuation failed: " + t.getMessage());
                    if (postProcessor != null) {
                        postProcessor.run();
                    }
                }
            }, continuationExecutor);
            return true; // breakpoint activated
        }

        return false; // cap reached, fall through
    }

    /**
     * REQUEST-phase breakpoint gate shared by matched forwards, matched mock responses, and the
     * unmatched-404 path. Mirrors the unmatched-proxy REQUEST breakpoint in {@link #handleUnmatchedProxyForward}:
     * if a REQUEST breakpoint matches and dispatch to a connected callback client succeeds, the request is
     * paused and, on resolution, {@code onProceed} is invoked with the original request (CONTINUE) or the
     * modified request (MODIFY), or an abort response is written (ABORT); this returns {@code true} and the
     * caller MUST NOT proceed synchronously. Returns {@code false} when synchronous, when no breakpoint
     * matches, or when the cap was reached / client disconnected — the caller then proceeds normally.
     * Non-blocking: the continuation runs asynchronously on the scheduler executor.
     */
    private boolean attemptRequestBreakpoint(
        final HttpRequest request,
        final boolean synchronous,
        final ResponseWriter responseWriter,
        final Runnable postProcessor,
        final java.util.function.Consumer<HttpRequest> onProceed
    ) {
        if (synchronous) {
            return false;
        }
        final org.mockserver.mock.breakpoint.BreakpointMatcher requestBreakpoint =
            org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance()
                .findMatch(request, org.mockserver.mock.breakpoint.BreakpointPhase.REQUEST);
        if (requestBreakpoint == null) {
            return false;
        }
        final java.util.concurrent.CompletableFuture<org.mockserver.mock.breakpoint.BreakpointDecision> decisionFuture =
            org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher.getInstance().dispatchRequest(
                requestBreakpoint.getClientId(), requestBreakpoint.getId(), request,
                httpStateHandler.getWebSocketClientRegistry(), configuration, mockServerLogger
            );
        if (decisionFuture == null) {
            // cap reached or client disconnected — fall through to normal handling
            return false;
        }
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat("request paused at breakpoint, awaiting resolution for:{}")
                    .setArguments(request)
            );
        }
        final java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
            ? scheduler.getExecutorService()
            : Runnable::run;
        decisionFuture.thenAcceptAsync(decision -> {
            try {
                switch (decision.getAction()) {
                    case ABORT:
                        HttpResponse abortResponse = decision.getAbortResponse();
                        if (abortResponse == null) {
                            abortResponse = response().withStatusCode(503).withReasonPhrase("Breakpoint Aborted");
                        }
                        responseWriter.writeResponse(request, abortResponse, false);
                        if (postProcessor != null) {
                            postProcessor.run();
                        }
                        return;
                    case MODIFY:
                        onProceed.accept(decision.getModifiedRequest() != null ? decision.getModifiedRequest() : request);
                        return;
                    case CONTINUE:
                    default:
                        onProceed.accept(request);
                }
            } catch (Throwable throwable) {
                returnBadGateway(responseWriter, request, "breakpoint continuation failed: " + throwable.getMessage());
                if (postProcessor != null) {
                    postProcessor.run();
                }
            }
        }, continuationExecutor);
        return true;
    }

    /**
     * Dispatches a matched forward expectation through the REQUEST-phase breakpoint gate. {@code forwarder}
     * computes the {@link HttpForwardActionResult} from the (possibly modified) request; the RESPONSE-phase
     * breakpoint and logging continue to key off the original {@code request} (mirroring the unmatched-proxy
     * MODIFY semantics). When no REQUEST breakpoint applies, the forward proceeds normally.
     */
    private void dispatchForwardWithBreakpoint(
        final HttpRequest request,
        final Action action,
        final boolean synchronous,
        final ResponseWriter responseWriter,
        final Runnable expectationPostProcessor,
        final HttpChaosProfile forwardChaos,
        final int capturedMatchCount,
        final ChannelHandlerContext ctx,
        final RateLimit rateLimit,
        final java.util.function.Function<HttpRequest, HttpForwardActionResult> forwarder
    ) {
        if (attemptRequestBreakpoint(request, synchronous, responseWriter, expectationPostProcessor,
            req -> writeForwardActionResponse(forwarder.apply(req), responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit))) {
            return;
        }
        writeForwardActionResponse(forwarder.apply(request), responseWriter, request, action, synchronous, expectationPostProcessor, forwardChaos, capturedMatchCount, ctx, rateLimit);
    }

    /**
     * Dispatches a matched mock-response expectation through the REQUEST-phase breakpoint gate. {@code responder}
     * generates the {@link HttpResponse} from the (possibly modified) request (so templates/class-callbacks see
     * the modified request); the response is then written via {@link #writeResponseActionResponse}, where the
     * RESPONSE-phase breakpoint is applied. Logging/response-phase matching key off the original {@code request}.
     */
    private void dispatchMockResponseWithBreakpoint(
        final HttpRequest request,
        final Action action,
        final boolean synchronous,
        final ResponseWriter responseWriter,
        final RequestDefinition requestDefinition,
        final Runnable expectationPostProcessor,
        final HttpChaosProfile effectiveChaos,
        final int capturedMatchCount,
        final ChannelHandlerContext ctx,
        final RateLimit rateLimit,
        final java.util.function.Function<HttpRequest, HttpResponse> responder
    ) {
        if (attemptRequestBreakpoint(request, synchronous, responseWriter, expectationPostProcessor,
            req -> writeResponseActionResponse(responder.apply(req), responseWriter, request, action, synchronous, requestDefinition, expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx, rateLimit))) {
            return;
        }
        writeResponseActionResponse(responder.apply(request), responseWriter, request, action, synchronous, requestDefinition, expectationPostProcessor, effectiveChaos, capturedMatchCount, ctx, rateLimit);
    }

    /**
     * Answers a forward that failed for a reason {@link #upstreamFailureReason} names with a 502 whose body is the
     * reason, logged once at ERROR as {@code entry}, whose format takes the request, {@code target} and the reason.
     *
     * @return whether {@code failure} had such a reason and has been answered
     */
    private boolean returnedUpstreamFailureReason(Throwable failure, HttpRequest request, ResponseWriter responseWriter, LogEntry entry, Object target) {
        if (upstreamFailureReason(failure) == null) {
            return false;
        }
        entry
            .setLogLevel(Level.ERROR)
            .setCorrelationId(request.getLogCorrelationId())
            .setHttpRequest(request);
        UnaryOperator<String> scrub = entry.credentialScrub(configuration);
        String reason = upstreamFailureReason(failure, scrub);
        mockServerLogger.logEvent(
            entry
                .setArguments(request, target, reason)
                .setThrowable(boundedFault(failure, scrub))
        );
        returnBadGateway(responseWriter, request, reason, badGatewayResponse().withBody(reason));
        return true;
    }

    /**
     * Answers a forward that failed for a header limit with a 502 whose body is the reason. The forward client
     * logged the refusal as it raised it, so it is not logged as an unexpected exception here.
     *
     * @return whether {@code failure} was such a failure and has been answered
     */
    private boolean returnedHeaderLimitFailure(ResponseWriter responseWriter, HttpRequest request, Throwable failure) {
        HeaderLimitExceededException headerLimit = HeaderLimitExceededException.in(failure);
        if (headerLimit == null) {
            return false;
        }
        returnBadGateway(responseWriter, request, headerLimit.getMessage(), badGatewayResponse().withBody(headerLimit.getMessage()));
        return true;
    }

    /**
     * Answers with 502 a forward the forward client refused for forwardProxyBlockPrivateNetworks: the address it
     * looked up to connect to was blocked although the name passed the check made before sending. Logged once, at
     * WARN, as a refusal made before sending is.
     *
     * @return whether {@code failure} was such a refusal and has been answered
     */
    private boolean returnedBlockedTarget(ResponseWriter responseWriter, HttpRequest request, Throwable failure, String label) {
        ForwardTargetBlockedException blocked = ForwardTargetBlockedException.in(failure);
        if (blocked == null) {
            return false;
        }
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat(label + " blocked by SSRF policy:{}")
                    .setArguments(blocked.getMessage())
            );
        }
        returnBadGateway(responseWriter, request, blocked.getMessage());
        return true;
    }

    /**
     * Applies forwardProxyBlockPrivateNetworks to a request that matched no expectation, which goes to the
     * destination the client proxied it to, or to proxyRemoteHost or a proxyPassMappings target: every target is
     * checked, as for a forward action. A refused request is answered with 502.
     *
     * @return whether the request was refused and answered
     */
    private boolean refusedByPrivateNetworkBlock(HttpRequest requestToSend, InetSocketAddress remoteAddress, HttpRequest request, ResponseWriter responseWriter) {
        try {
            InetAddressValidator.validateForwardTarget(configuration, requestToSend, remoteAddress, httpClient.sendsThroughHttpProxy(requestToSend, remoteAddress));
            return false;
        } catch (IllegalArgumentException blocked) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat("proxied request blocked by SSRF policy:{}")
                        .setArguments(blocked.getMessage())
                );
            }
            returnBadGateway(responseWriter, request, blocked.getMessage());
            return true;
        }
    }

    private void returnBadGateway(ResponseWriter responseWriter, HttpRequest request, String error) {
        returnBadGateway(responseWriter, request, error, badGatewayResponse());
    }

    private void returnBadGateway(ResponseWriter responseWriter, HttpRequest request, String error, HttpResponse response) {
        if (isNotBlank(error)) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setMessageFormat(FORWARD_FAILURE_MESSAGE_FORMAT)
                        .setArguments(request, error, response)
                );
            }
        } else {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(response)
                        .setMessageFormat(FORWARD_FAILURE_MESSAGE_FORMAT)
                        .setArguments(request, "unknown error", response)
                );
            }
        }
        responseWriter.writeResponse(request, response, false);
    }

    private void returnNotFound(ResponseWriter responseWriter, HttpRequest request, String error, boolean synchronous) {
        HttpResponse response = notFoundResponse();
        if (request.getHeaders() != null && request.getHeaders().containsEntry(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue())) {
            response.withHeader(httpStateHandler.getUniqueLoopPreventionHeaderName(), httpStateHandler.getUniqueLoopPreventionHeaderValue());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(TRACE)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                        .setArguments(request, notFoundResponse())
                );
            }
        } else if (isNotBlank(error)) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(notFoundResponse())
                        .setMessageFormat(NO_MATCH_RESPONSE_ERROR_MESSAGE_FORMAT)
                        .setArguments(error, request, notFoundResponse())
                );
            }
        } else {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(NO_MATCH_RESPONSE)
                        .setLogLevel(Level.INFO)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setHttpRequest(request)
                        .setHttpResponse(notFoundResponse())
                        .setMessageFormat(NO_MATCH_RESPONSE_NO_EXPECTATION_MESSAGE_FORMAT)
                        .setArguments(request, notFoundResponse())
                );
            }
        }
        // Closest-match diagnostics. Up to three consumers can want the closest non-matching
        // expectation: the DEBUG diff log, the verbose response diagnostic (header + JSON body),
        // and the compact hint header. Each previously re-ran the (cold-path but non-trivial)
        // matcher scan independently, so all three active meant scanning every expectation three
        // times. Compute it ONCE here and thread the single result into all consumers — behaviour
        // of each path is unchanged, only the redundant re-scans are removed.
        boolean wantDebugDiffLog = configuration.detailedVerificationFailures() && mockServerLogger.isEnabledForInstance(Level.DEBUG);
        boolean wantVerboseDiagnostic = configuration.attachMismatchDiagnosticToResponse();
        boolean wantHintHeader = configuration.closestMatchHintEnabled();
        if (wantDebugDiffLog || wantVerboseDiagnostic || wantHintHeader) {
            org.mockserver.mock.RequestMatchers.ClosestMatchHint closestMatchHint = null;
            try {
                closestMatchHint = httpStateHandler.findClosestMatchHint(request);
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.TRACE)
                            .setMessageFormat("exception computing closest match for 404 response:{}")
                            .setArguments(e.getMessage())
                            .setThrowable(e)
                    );
                }
            }
            java.util.Map<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> closestDiff =
                closestMatchHint == null ? null : closestMatchHint.getDifferences();
            if (wantDebugDiffLog) {
                logClosestMatchDiff(request, closestDiff);
            }
            if (wantVerboseDiagnostic) {
                attachMismatchDiagnostic(response, closestDiff);
            }
            if (wantHintHeader) {
                attachClosestMatchHint(response, closestMatchHint);
            }
        }
        // breakpoint: RESPONSE-phase pause on the unmatched-404 before writing — lets a registered
        // matcher inspect / modify / abort the not-found response.
        if (!synchronous) {
            final org.mockserver.mock.breakpoint.BreakpointMatcher responseBreakpoint =
                org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance()
                    .findResponseMatch(request, response, org.mockserver.mock.breakpoint.BreakpointPhase.RESPONSE);
            if (responseBreakpoint != null) {
                final java.util.concurrent.Executor continuationExecutor = scheduler.getExecutorService() != null
                    ? scheduler.getExecutorService() : Runnable::run;
                final HttpResponse notFound = response;
                if (attemptResponseBreakpoint(responseBreakpoint, request, notFound, null, responseWriter, continuationExecutor,
                    responseToWrite -> responseWriter.writeResponse(request, responseToWrite, false), null)) {
                    return; // async — continuation writes the resolved response
                }
                // cap reached — fall through to normal write
            }
        }
        responseWriter.writeResponse(request, response, false);
    }

    /**
     * Emit the DEBUG closest-match diff log for an unmatched request using a precomputed diff
     * (shared with the other closest-match consumers in {@link #returnNotFound}).
     */
    private void logClosestMatchDiff(HttpRequest request, java.util.Map<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> closestDiff) {
        try {
            if (closestDiff != null && !closestDiff.isEmpty()) {
                String diffBody = org.mockserver.matchers.MatchDifferenceFormatter.formatDifferences(closestDiff);
                if (isNotBlank(diffBody)) {
                    if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.DEBUG)
                                .setHttpRequest(request)
                                .setMessageFormat("closest match diff for unmatched request:{}")
                                .setArguments(diffBody)
                        );
                    }
                }
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception generating closest match diff for 404 response:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
    }

    private void attachMismatchDiagnostic(HttpResponse response, java.util.Map<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> closestDiff) {
        try {
            if (closestDiff != null && !closestDiff.isEmpty()) {
                String summary = org.mockserver.matchers.MatchDifferenceFormatter.formatDifferences(closestDiff);
                if (isNotBlank(summary)) {
                    // header: concise one-line summary of the mismatched fields
                    String headerValue = closestDiff.keySet().stream()
                        .map(org.mockserver.matchers.MatchDifference.Field::getName)
                        .collect(java.util.stream.Collectors.joining(", "));
                    response.withHeader("x-mockserver-closest-match", "fields differ: " + headerValue);
                    // body: structured JSON diagnostic
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
                    com.fasterxml.jackson.databind.node.ObjectNode diagnosticNode = objectMapper.createObjectNode();
                    diagnosticNode.put("matchedFieldCount", org.mockserver.matchers.MatchDifference.Field.values().length - closestDiff.size());
                    diagnosticNode.put("totalFieldCount", org.mockserver.matchers.MatchDifference.Field.values().length);
                    com.fasterxml.jackson.databind.node.ObjectNode differencesNode = objectMapper.createObjectNode();
                    for (java.util.Map.Entry<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> entry : closestDiff.entrySet()) {
                        com.fasterxml.jackson.databind.node.ArrayNode fieldDiffs = differencesNode.putArray(entry.getKey().getName());
                        for (String diff : entry.getValue()) {
                            fieldDiffs.add(org.mockserver.matchers.MatchDifferenceFormatter.truncateDiffLine(diff));
                        }
                    }
                    diagnosticNode.set("differences", differencesNode);
                    response.withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(diagnosticNode), MediaType.JSON_UTF_8);
                }
            } else {
                response.withHeader("x-mockserver-closest-match", "no expectations configured");
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception attaching mismatch diagnostic to 404 response:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
    }

    /**
     * Maximum length of the closest-match hint header value. The hint is deliberately compact — a single
     * line naming the closest expectation and the first differing field/reason — so it stays well below
     * typical HTTP header-size limits and never carries a meaningful slice of expectation contents.
     */
    private static final int CLOSEST_MATCH_HINT_MAX_LENGTH = 256;

    static final String CLOSEST_MATCH_HINT_HEADER_NAME = "x-mockserver-closest-match-hint";

    /**
     * Attach a single, concise diagnostic header to a genuine data-plane no-match 404 describing why the
     * closest expectation did not match: its id, the first differing field, and a short reason. This is
     * header-only and length-bounded ({@link #CLOSEST_MATCH_HINT_MAX_LENGTH}); unlike
     * {@link #attachMismatchDiagnostic} it never writes a response body, so no large or sensitive
     * expectation contents are leaked. When no expectation came close (or none are configured) no header
     * is added — the 404 is left byte-for-byte unchanged. Gated by {@code closestMatchHintEnabled} and only
     * invoked from {@link #returnNotFound}, so control-plane responses and the handleAnyException error path
     * are unaffected. The {@code hint} is precomputed once in {@link #returnNotFound} and shared with the
     * other closest-match consumers.
     */
    private void attachClosestMatchHint(HttpResponse response, org.mockserver.mock.RequestMatchers.ClosestMatchHint hint) {
        try {
            if (hint == null || hint.getDifferences() == null || hint.getDifferences().isEmpty()) {
                return;
            }
            java.util.Map.Entry<org.mockserver.matchers.MatchDifference.Field, java.util.List<String>> firstDiff =
                hint.getDifferences().entrySet().iterator().next();
            StringBuilder sb = new StringBuilder();
            if (isNotBlank(hint.getExpectationId())) {
                sb.append("expectation ").append(hint.getExpectationId()).append(": ");
            }
            sb.append(firstDiff.getKey().getName()).append(" did not match");
            if (firstDiff.getValue() != null && !firstDiff.getValue().isEmpty() && isNotBlank(firstDiff.getValue().get(0))) {
                sb.append(" (").append(firstDiff.getValue().get(0)).append(")");
            }
            int extraFields = hint.getDifferences().size() - 1;
            if (extraFields > 0) {
                sb.append("; +").append(extraFields).append(" more field(s) differ");
            }
            String headerValue = sb.toString().replaceAll("[\\r\\n]+", " ").trim();
            if (headerValue.length() > CLOSEST_MATCH_HINT_MAX_LENGTH) {
                headerValue = headerValue.substring(0, CLOSEST_MATCH_HINT_MAX_LENGTH - 3) + "...";
            }
            if (isNotBlank(headerValue)) {
                response.withHeader(CLOSEST_MATCH_HINT_HEADER_NAME, headerValue);
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception attaching closest match hint to 404 response:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
    }

    /**
     * Set the captured streaming body on a log response. Textual content types
     * (Server-Sent Events, JSON, XML, ...) are stored as a plain {@link StringBody}
     * so the body is human-readable in the dashboard, the retrieve API and the HAR
     * export; other content is stored as binary. The captured body may be truncated
     * (see {@code maxStreamingCaptureBytes}), so it is stored verbatim as text rather
     * than re-parsed into a structured JsonBody/XmlBody - re-parsing would fail to
     * serialize when the captured JSON is incomplete.
     * <p>
     * When the response carries NO {@code Content-Type} header at all — as the OpenAI
     * Codex backend used by the opencode CLI does for its SSE stream — the captured bytes
     * are sniffed: valid UTF-8 text (SSE/JSON/plain) is stored as a readable STRING so the
     * dashboard and LLM trace/optimise views render it, while genuinely binary streams fall
     * back to BINARY. Without this, a no-Content-Type SSE stream was stored as BINARY and
     * appeared empty/unreadable in the LLM body views.
     */
    private static void setCapturedStreamingBody(HttpResponse logResponse, byte[] captured) {
        // Clear the live StreamingBody reference copied by clone() so the retained log entry
        // does not pin the live ≤256 KB capture buffer, the upstream EventLoop, or the
        // onChunk/requestMore callbacks for the entry's lifetime in the ring buffer. The
        // FIXED captured bytes are stored as the body below; the live sink is no longer needed.
        // Done here (rather than at each call site) so all four streaming-completion paths are
        // covered in one place — this helper is only ever called from those paths.
        logResponse.withStreamingBody(null);
        if (captured.length == 0) {
            return;
        }
        String contentTypeHeader = logResponse.getFirstHeader(CONTENT_TYPE.toString());
        if (contentTypeHeader != null && !contentTypeHeader.isEmpty()) {
            MediaType mediaType = MediaType.parse(contentTypeHeader);
            if (mediaType != null && mediaType.isString()) {
                logResponse.withBody(new String(captured, mediaType.getCharsetOrDefault()));
                return;
            }
            // Explicit non-textual content type (e.g. application/octet-stream): store as binary.
            logResponse.withBody(captured);
            return;
        }
        // No Content-Type header: sniff the captured bytes so text streams stay readable.
        if (isProbablyUtf8Text(captured)) {
            logResponse.withBody(new String(captured, StandardCharsets.UTF_8));
        } else {
            logResponse.withBody(captured);
        }
    }

    /**
     * Heuristic used only when a streamed response has no {@code Content-Type} header, to
     * decide whether the captured bytes should be stored as a readable STRING or as BINARY.
     * Returns true when the bytes look like UTF-8 text (SSE/JSON/plain): they contain no NUL
     * or non-whitespace C0 control bytes (the hallmark of binary data) and decode as UTF-8
     * with at most a single replacement character (tolerating one multi-byte character split
     * by capture truncation). Returns false for empty or binary-looking content.
     */
    private static boolean isProbablyUtf8Text(byte[] bytes) {
        if (bytes.length == 0) {
            return false;
        }
        for (byte b : bytes) {
            int c = b & 0xFF;
            if (c == 0 || (c < 0x20 && c != '\t' && c != '\n' && c != '\r')) {
                return false;
            }
        }
        // Lenient decode: clean text yields no U+FFFD; allow one for a truncated trailing char.
        String decoded = new String(bytes, StandardCharsets.UTF_8);
        int replacements = 0;
        for (int i = 0; i < decoded.length(); i++) {
            if (decoded.charAt(i) == '�' && ++replacements > 1) {
                return false;
            }
        }
        return true;
    }

    /**
     * Attach internal streaming metadata headers to a log response. This must be called
     * consistently from every streaming completion path so that the log entry (and any
     * fixture derived from it) carries the same set of headers.
     *
     * @param logResponse   the cloned response that will be stored in the event log
     * @param streamingBody the streaming body that captured bytes and timestamps
     */
    private static void attachStreamingHeaders(HttpResponse logResponse, StreamingBody streamingBody) {
        logResponse.withHeader("x-mockserver-streamed", "true");
        if (streamingBody.isTruncated()) {
            logResponse.withHeader("x-mockserver-stream-truncated", "true");
        }
        List<Long> interChunkDelays = streamingBody.interChunkDelaysMillis();
        if (interChunkDelays != null && !interChunkDelays.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < interChunkDelays.size(); i++) {
                if (i > 0) {
                    sb.append(',');
                }
                sb.append(interChunkDelays.get(i));
            }
            logResponse.withHeader("x-mockserver-chunk-delays-ms", sb.toString());
        }
    }

    private HttpResponseActionHandler getHttpResponseActionHandler() {
        if (httpResponseActionHandler == null) {
            httpResponseActionHandler = new HttpResponseActionHandler(mockServerLogger, configuration);
        }
        return httpResponseActionHandler;
    }

    private FileBodyMaterialiser getFileBodyMaterialiser() {
        if (fileBodyMaterialiser == null) {
            fileBodyMaterialiser = new FileBodyMaterialiser(mockServerLogger, configuration);
        }
        return fileBodyMaterialiser;
    }

    /**
     * Materialises a {@link FileBody} response body — reading the referenced file so its CONTENTS (not the
     * filePath string) reach the wire — for every response-producing funnel that does NOT already pass
     * through {@link HttpResponseActionHandler} (object/class callbacks, response templates, forward
     * responseOverride). Idempotent for the static response path: {@code HttpResponseActionHandler.handle}
     * has already replaced its FileBody with a String/Binary body, so by the funnel it is no longer a
     * FileBody and this is a no-op — no double read. A templated FileBody is rendered against {@code request}
     * (which may be {@code null}). A missing/unreadable file yields a clean, logged 500 whose body does NOT
     * leak the path, rather than a broken connection or a leaked path string.
     */
    private HttpResponse materialiseFileBodyResponse(final HttpResponse response, final HttpRequest request) {
        if (response == null || !(response.getBody() instanceof FileBody)) {
            return response;
        }
        FileBody fileBody = (FileBody) response.getBody();
        try {
            return response.withBody(getFileBodyMaterialiser().materialise(fileBody, request));
        } catch (FileBodyException fileBodyException) {
            logFileBodyError(request, fileBodyException);
            return fileBodyErrorResponse();
        }
    }

    private void logFileBodyError(final HttpRequest request, final FileBodyException fileBodyException) {
        if (mockServerLogger.isEnabledForInstance(Level.ERROR)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequest(request)
                    .setMessageFormat("unable to read file \"{}\" for response body, returning 500 because:{}")
                    .setArguments(fileBodyException.getFilePath(), fileBodyException.getCause() != null ? fileBodyException.getCause().getMessage() : fileBodyException.getMessage())
                    .setThrowable(fileBodyException)
            );
        }
    }

    /**
     * A clean 500 for an unreadable response-body file. The body is deliberately generic and does NOT
     * contain the file path (the path is logged server-side only via {@link #logFileBodyError}).
     */
    private static HttpResponse fileBodyErrorResponse() {
        return response()
            .withStatusCode(500)
            .withReasonPhrase("Internal Server Error")
            .withBody("Error reading file for response body");
    }

    private HttpResponseTemplateActionHandler getHttpResponseTemplateActionHandler() {
        if (httpResponseTemplateActionHandler == null) {
            httpResponseTemplateActionHandler = new HttpResponseTemplateActionHandler(mockServerLogger, configuration);
        }
        return httpResponseTemplateActionHandler;
    }

    private HttpResponseClassCallbackActionHandler getHttpResponseClassCallbackActionHandler() {
        if (httpResponseClassCallbackActionHandler == null) {
            httpResponseClassCallbackActionHandler = new HttpResponseClassCallbackActionHandler(mockServerLogger);
        }
        return httpResponseClassCallbackActionHandler;
    }

    private HttpResponseObjectCallbackActionHandler getHttpResponseObjectCallbackActionHandler() {
        if (httpResponseObjectCallbackActionHandler == null) {
            httpResponseObjectCallbackActionHandler = new HttpResponseObjectCallbackActionHandler(httpStateHandler);
        }
        return httpResponseObjectCallbackActionHandler;
    }

    /**
     * gRPC forward-proxy (anonymous / unmatched proxy path): re-encode a decoded gRPC request back
     * into gRPC-framed protobuf for the upstream call. No-op (returns the same request) when the
     * request is not a decoded gRPC exchange or no descriptors are loaded.
     */
    private HttpRequest grpcEncodeForForward(HttpRequest request) {
        return GrpcForwardTranslator.encodeRequestForUpstream(request, httpStateHandler.getGrpcDescriptorStore());
    }

    /**
     * Companion to {@link #grpcEncodeForForward}: a response override that decodes the upstream
     * gRPC-framed protobuf response back to JSON and re-stamps the {@code x-grpc-*} headers, so the
     * client response can be re-framed and the recorded {@code FORWARDED_REQUEST} is replayable.
     * Returns {@code null} (no override) when the request is not a gRPC exchange. Must be called with
     * the request BEFORE {@link #grpcEncodeForForward} strips its {@code x-grpc-*} headers.
     */
    private java.util.function.Function<HttpResponse, HttpResponse> grpcDecodeOverride(HttpRequest grpcRequest) {
        GrpcProtoDescriptorStore store = httpStateHandler.getGrpcDescriptorStore();
        if (store == null || !store.hasServices() || !GrpcForwardTranslator.isGrpcForwardRequest(grpcRequest)) {
            return null;
        }
        final String service = grpcRequest.getFirstHeader(GrpcForwardTranslator.SERVICE_HEADER);
        final String method = grpcRequest.getFirstHeader(GrpcForwardTranslator.METHOD_HEADER);
        return resp -> GrpcForwardTranslator.decodeResponseFromUpstream(resp, service, method, store);
    }

    private HttpForwardActionHandler getHttpForwardActionHandler() {
        if (httpForwardActionHandler == null) {
            httpForwardActionHandler = new HttpForwardActionHandler(mockServerLogger, configuration, httpClient);
            httpForwardActionHandler.setGrpcDescriptorStore(httpStateHandler.getGrpcDescriptorStore());
        }
        return httpForwardActionHandler;
    }

    private HttpForwardTemplateActionHandler getHttpForwardTemplateActionHandler() {
        if (httpForwardTemplateActionHandler == null) {
            httpForwardTemplateActionHandler = new HttpForwardTemplateActionHandler(mockServerLogger, configuration, httpClient);
            httpForwardTemplateActionHandler.setGrpcDescriptorStore(httpStateHandler.getGrpcDescriptorStore());
        }
        return httpForwardTemplateActionHandler;
    }

    private HttpForwardClassCallbackActionHandler getHttpForwardClassCallbackActionHandler() {
        if (httpForwardClassCallbackActionHandler == null) {
            httpForwardClassCallbackActionHandler = new HttpForwardClassCallbackActionHandler(mockServerLogger, configuration, httpClient);
            httpForwardClassCallbackActionHandler.setGrpcDescriptorStore(httpStateHandler.getGrpcDescriptorStore());
        }
        return httpForwardClassCallbackActionHandler;
    }

    private HttpForwardObjectCallbackActionHandler getHttpForwardObjectCallbackActionHandler() {
        if (httpForwardObjectCallbackActionHandler == null) {
            httpForwardObjectCallbackActionHandler = new HttpForwardObjectCallbackActionHandler(httpStateHandler, configuration, httpClient);
            httpForwardObjectCallbackActionHandler.setGrpcDescriptorStore(httpStateHandler.getGrpcDescriptorStore());
        }
        return httpForwardObjectCallbackActionHandler;
    }

    private HttpOverrideForwardedRequestActionHandler getHttpOverrideForwardedRequestCallbackActionHandler() {
        if (httpOverrideForwardedRequestCallbackActionHandler == null) {
            httpOverrideForwardedRequestCallbackActionHandler = new HttpOverrideForwardedRequestActionHandler(mockServerLogger, configuration, httpClient);
            httpOverrideForwardedRequestCallbackActionHandler.setGrpcDescriptorStore(httpStateHandler.getGrpcDescriptorStore());
        }
        return httpOverrideForwardedRequestCallbackActionHandler;
    }

    private HttpForwardValidateActionHandler getHttpForwardValidateActionHandler() {
        if (httpForwardValidateActionHandler == null) {
            httpForwardValidateActionHandler = new HttpForwardValidateActionHandler(mockServerLogger, configuration, httpClient);
            httpForwardValidateActionHandler.setGrpcDescriptorStore(httpStateHandler.getGrpcDescriptorStore());
        }
        return httpForwardValidateActionHandler;
    }

    private HttpForwardWithFallbackActionHandler getHttpForwardWithFallbackActionHandler() {
        if (httpForwardWithFallbackActionHandler == null) {
            httpForwardWithFallbackActionHandler = new HttpForwardWithFallbackActionHandler(mockServerLogger, configuration, httpClient);
            httpForwardWithFallbackActionHandler.setGrpcDescriptorStore(httpStateHandler.getGrpcDescriptorStore());
        }
        return httpForwardWithFallbackActionHandler;
    }

    private HttpSseResponseActionHandler getHttpSseResponseActionHandler() {
        if (httpSseResponseActionHandler == null) {
            httpSseResponseActionHandler = new HttpSseResponseActionHandler(mockServerLogger, scheduler, configuration, httpStateHandler.getWebSocketClientRegistry());
        }
        return httpSseResponseActionHandler;
    }

    private HttpLlmResponseActionHandler getHttpLlmResponseActionHandler() {
        if (httpLlmResponseActionHandler == null) {
            httpLlmResponseActionHandler = new HttpLlmResponseActionHandler(mockServerLogger, configuration);
        }
        return httpLlmResponseActionHandler;
    }

    private HttpWebSocketResponseActionHandler getHttpWebSocketResponseActionHandler() {
        if (httpWebSocketResponseActionHandler == null) {
            httpWebSocketResponseActionHandler = new HttpWebSocketResponseActionHandler(mockServerLogger, scheduler, configuration, httpStateHandler.getWebSocketClientRegistry());
        }
        return httpWebSocketResponseActionHandler;
    }

    private GrpcStreamResponseActionHandler getGrpcStreamResponseActionHandler() {
        if (grpcStreamResponseActionHandler == null) {
            grpcStreamResponseActionHandler = new GrpcStreamResponseActionHandler(mockServerLogger, scheduler, httpStateHandler.getGrpcDescriptorStore(), configuration, httpStateHandler.getWebSocketClientRegistry());
        }
        return grpcStreamResponseActionHandler;
    }

    private HttpErrorActionHandler getHttpErrorActionHandler() {
        if (httpErrorActionHandler == null) {
            httpErrorActionHandler = new HttpErrorActionHandler();
        }
        return httpErrorActionHandler;
    }

    /**
     * Apply an {@link HttpError} action and emit its expectation-response log entry. When the error
     * carries a stream error and the active {@link ResponseWriter} can reset its own transport stream
     * (HTTP/3 via {@link StreamErrorWriter}), the reset is delegated to it; otherwise the netty
     * {@link HttpErrorActionHandler} resets the HTTP/2 stream (or drops the connection on HTTP/1.1).
     */
    private void dispatchErrorAction(final HttpError httpError, final HttpRequest request, final ResponseWriter responseWriter, final ChannelHandlerContext ctx) {
        if (httpError.getStreamError() != null && responseWriter instanceof StreamErrorWriter) {
            ((StreamErrorWriter) responseWriter).writeStreamError(httpError.getStreamError());
        } else {
            getHttpErrorActionHandler().handle(httpError, request, ctx, responseWriter.respondingDirectly());
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(EXPECTATION_RESPONSE)
                .setLogLevel(Level.INFO)
                .setCorrelationId(request.getLogCorrelationId())
                .setHttpRequest(request)
                .setHttpError(httpError)
                .setExpectationId(httpError.getExpectationId())
                .setMessageFormat("returning error:{}for request:{}for action:{}from expectation:{}")
                .setArguments(httpError, request, httpError, httpError.getExpectationId())
        );
    }

    public NettyHttpClient getHttpClient() {
        return httpClient;
    }

    /**
     * @return a sender that issues a request with {@link #getHttpClient()}, the same instance for the life of
     * this handler, so that a server can later remove it from the process-wide places it installed it in. A request
     * whose destination forwardProxyBlockPrivateNetworks refuses is not sent: its future fails with a
     * {@link ForwardTargetBlockedException}, which the caller logs.
     */
    public java.util.function.Function<HttpRequest, java.util.concurrent.CompletableFuture<HttpResponse>> getRequestSender() {
        return requestSender;
    }


    public static InetSocketAddress getRemoteAddress(final ChannelHandlerContext ctx) {
        if (ctx != null && ctx.channel() != null && ctx.channel().attr(REMOTE_SOCKET) != null) {
            return ctx.channel().attr(REMOTE_SOCKET).get();
        } else {
            return null;
        }
    }

    private InetSocketAddress getRemoteAddressWithFallback(final ChannelHandlerContext ctx) {
        InetSocketAddress remoteAddress = getRemoteAddress(ctx);
        if (remoteAddress == null && configuration != null) {
            String host = configuration.proxyRemoteHost();
            Integer port = configuration.proxyRemotePort();
            if (isNotBlank(host) && port != null) {
                // Unresolved so Netty's event-loop resolver performs the (blocking) DNS lookup rather
                // than resolving synchronously here in the InetSocketAddress constructor.
                remoteAddress = InetSocketAddress.createUnresolved(host, port);
            }
        }
        return remoteAddress;
    }

    private void adjustHostHeaderForUnmatchedRequest(HttpRequest request, InetSocketAddress remoteAddress) {
        if (configuration != null) {
            String defaultHostHeader = configuration.forwardDefaultHostHeader();
            if (isNotBlank(defaultHostHeader)) {
                request.replaceHeader(new Header("Host", defaultHostHeader));
            } else if (remoteAddress != null && isNotBlank(configuration.proxyRemoteHost())) {
                Integer port = configuration.proxyRemotePort();
                boolean defaultPort = port == null || port == 80 || port == 443;
                String hostHeader = defaultPort ? configuration.proxyRemoteHost() : configuration.proxyRemoteHost() + ":" + port;
                request.replaceHeader(new Header("Host", hostHeader));
            }
        }
    }


    public static void setRemoteAddress(final ChannelHandlerContext ctx, final InetSocketAddress inetSocketAddress) {
        if (ctx != null && ctx.channel() != null) {
            ctx.channel().attr(REMOTE_SOCKET).set(inetSocketAddress);
        }
    }

    /**
     * Asynchronously compares a forwarded upstream response against any response-type
     * stub expectations that match the same request, recording structural drift
     * (status, headers, JSON schema) and performance drift into the
     * {@link org.mockserver.mock.drift.DriftStore}.
     */
    private void analyseDrift(final HttpRequest request, final HttpResponse realResponse, final long responseTimeMs) {
        if (realResponse == null) {
            return;
        }
        // Best-effort: each queued task retains the request and upstream response, and the shared
        // scheduler queue is unbounded, so shed analyses beyond the bound rather than queue them.
        int bound = maxPendingDriftAnalyses();
        if (pendingDriftAnalyses.incrementAndGet() > bound) {
            pendingDriftAnalyses.decrementAndGet();
            org.mockserver.metrics.Metrics.incrementDroppedDriftAnalyses();
            return;
        }
        Runnable analysis = () -> {
            try {
                List<Expectation> matching = httpStateHandler.allMatchingExpectation(request);
                org.mockserver.mock.drift.DriftAnalyzer analyzer = org.mockserver.mock.drift.DriftAnalyzer.getInstance();
                for (Expectation expectation : matching) {
                    if (expectation.getAction() instanceof HttpResponse) {
                        analyzer.analyse(expectation, realResponse);
                        // Record response time and check for performance drift
                        org.mockserver.mock.drift.PercentileTracker.getInstance()
                            .record(expectation.getId(), responseTimeMs);
                        analyzer.checkPerformanceDrift(expectation.getId(), responseTimeMs,
                            org.mockserver.time.TimeService.currentTimeMillis());
                    }
                }
            } catch (Exception e) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(TRACE)
                            .setHttpRequest(request)
                            .setMessageFormat("exception during drift analysis - " + e.getMessage())
                            .setThrowable(e)
                    );
                }
            } finally {
                pendingDriftAnalyses.decrementAndGet();
            }
        };
        try {
            scheduler.submit(analysis);
        } catch (RuntimeException rejected) {
            // never ran, so its finally never releases the slot; best-effort work must not fail the forward
            pendingDriftAnalyses.decrementAndGet();
            org.mockserver.metrics.Metrics.incrementDroppedDriftAnalyses();
        }
    }

    private int maxPendingDriftAnalyses() {
        return Math.max(MIN_PENDING_DRIFT_ANALYSES, PENDING_DRIFT_ANALYSES_PER_THREAD * configuration.actionHandlerThreadCount());
    }

    // -------- validation proxy (OpenAPI contract validation on forwarded traffic) --------

    /**
     * Returns {@code true} when the validation-proxy mode is enabled: a spec has been configured
     * via {@code validateProxyOpenAPISpec}.
     */
    private boolean isValidationProxyEnabled() {
        String spec = configuration.validateProxyOpenAPISpec();
        return spec != null && !spec.isEmpty();
    }

    /**
     * Validates the forwarded request against the configured OpenAPI spec before the request is sent upstream.
     * If violations are found they are logged as {@code OPENAPI_REQUEST_VALIDATION_FAILED}. In enforce mode
     * a 400 response is returned; otherwise {@code null} (meaning "proceed normally").
     *
     * <p>This method may perform an expensive cold-cache OpenAPI parse / JSON-schema validation,
     * so callers MUST invoke it off the Netty event loop (inside a {@code scheduler.submit} block).</p>
     *
     * @return an {@link HttpResponse} to short-circuit with, or {@code null} to proceed
     */
    private HttpResponse validateProxyRequest(HttpRequest request) {
        String spec = configuration.validateProxyOpenAPISpec();
        if (spec == null || spec.isEmpty()) {
            return null;
        }
        try {
            List<String> requestErrors = OpenAPIRequestValidator.validate(spec, request, mockServerLogger, null, configuration);
            if (!requestErrors.isEmpty()) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(OPENAPI_REQUEST_VALIDATION_FAILED)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setMessageFormat("validation proxy: request does not conform to OpenAPI spec{}errors:{}")
                            .setArguments(request, String.join("; ", requestErrors))
                    );
                }
                if (Boolean.TRUE.equals(configuration.validateProxyEnforce())) {
                    return response()
                        .withStatusCode(400)
                        .withBody("OpenAPI request validation failed: " + String.join("; ", requestErrors));
                }
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("validation proxy: failed to validate request against OpenAPI spec{}due to:{}")
                        .setArguments(request, e.getMessage())
                );
            }
        }
        return null;
    }

    /**
     * Validates the upstream response (only) against the configured OpenAPI spec. Violations are logged
     * as {@code OPENAPI_RESPONSE_VALIDATION_FAILED}. In enforce mode a 502 response is returned instead
     * of the upstream response; otherwise the original response is returned unmodified.
     *
     * <p>Unlike the previous implementation this method validates the <em>response only</em> using
     * {@link OpenAPIResponseValidator} directly, avoiding the double request validation that
     * {@link OpenApiTrafficValidator} would perform.</p>
     *
     * <p>For streaming responses the body has already been written to the client before validation
     * runs, so enforce mode cannot replace the response. Streaming responses are therefore validated
     * in report-only fashion (violations logged) even when enforce is enabled.</p>
     *
     * @param request  the forwarded request (used to resolve the matching operation)
     * @param response the upstream response to validate
     * @param streaming {@code true} when the response was a streaming response whose body has already
     *                  been written to the client (enforce mode is ineffective for streaming)
     * @return the original response (if valid or report-only/streaming), or a 502 in enforce mode
     */
    private HttpResponse validateProxyResponse(HttpRequest request, HttpResponse response, boolean streaming) {
        String spec = configuration.validateProxyOpenAPISpec();
        if (spec == null || spec.isEmpty() || response == null) {
            return response;
        }
        try {
            String operationId = resolveOperationId(spec, request);
            if (operationId == null) {
                // could not match the request to a spec operation — skip response validation
                return response;
            }
            List<String> responseErrors = OpenAPIResponseValidator.validate(spec, operationId, response, mockServerLogger, configuration);
            if (!responseErrors.isEmpty()) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(OPENAPI_RESPONSE_VALIDATION_FAILED)
                            .setLogLevel(Level.WARN)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setHttpRequest(request)
                            .setHttpResponse(response)
                            .setMessageFormat("validation proxy: upstream response does not conform to OpenAPI spec{}errors:{}")
                            .setArguments(request, String.join("; ", responseErrors))
                    );
                }
                if (!streaming && Boolean.TRUE.equals(configuration.validateProxyEnforce())) {
                    return response()
                        .withStatusCode(502)
                        .withBody("OpenAPI response validation failed: " + String.join("; ", responseErrors));
                }
            }
        } catch (Exception e) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("validation proxy: failed to validate upstream response against OpenAPI spec{}due to:{}")
                        .setArguments(request, e.getMessage())
                );
            }
        }
        return response;
    }

    /**
     * Resolves the OpenAPI operationId for the given request by matching its path and method
     * against the spec. Returns {@code null} if no matching operation is found.
     */
    private String resolveOperationId(String specUrlOrPayload, HttpRequest request) {
        try {
            io.swagger.v3.oas.models.OpenAPI openAPI = org.mockserver.openapi.OpenAPIParser.buildOpenAPI(specUrlOrPayload, mockServerLogger, configuration);
            String requestPath = request.getPath() != null ? request.getPath().getValue() : "/";
            String requestMethod = request.getMethod() != null ? request.getMethod().getValue().toLowerCase() : "get";
            for (java.util.Map.Entry<String, io.swagger.v3.oas.models.PathItem> entry : openAPI.getPaths().entrySet()) {
                String templatePath = entry.getKey();
                if (pathMatchesTemplate(templatePath, requestPath)) {
                    for (org.apache.commons.lang3.tuple.Pair<String, io.swagger.v3.oas.models.Operation> methodOp : org.mockserver.openapi.OpenAPIParser.mapOperations(entry.getValue())) {
                        if (methodOp.getLeft().equalsIgnoreCase(requestMethod)) {
                            return methodOp.getRight().getOperationId();
                        }
                    }
                }
            }
        } catch (Exception e) {
            // fall through — unable to resolve operation
        }
        return null;
    }

    /**
     * Checks whether a concrete request path matches an OpenAPI path template (e.g. {@code /pets/{petId}}).
     */
    private static boolean pathMatchesTemplate(String templatePath, String actualPath) {
        StringBuilder regex = new StringBuilder();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("\\{[^}]+}").matcher(templatePath);
        int lastEnd = 0;
        while (matcher.find()) {
            regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd, matcher.start())));
            regex.append("[^/]+");
            lastEnd = matcher.end();
        }
        regex.append(java.util.regex.Pattern.quote(templatePath.substring(lastEnd)));
        return actualPath.matches(regex.toString());
    }

    /**
     * Check the LLM cost budget before forwarding. If the outbound request targets
     * an LLM provider (detected by {@link org.mockserver.llm.client.LlmProviderSniffer})
     * and the cumulative cost exceeds the configured budget, return a 429 response.
     * Otherwise return {@code null} (request should proceed). Fail-open on misconfig
     * or detection failure.
     */
    private HttpResponse checkLlmCostBudget(HttpRequest request) {
        return checkLlmCostBudgetByHost(null, request);
    }

    /**
     * Check the LLM cost budget before a matched FORWARD action. Resolves the
     * forward target host from the action (if available) so the sniffer checks
     * the upstream host, not the inbound request host. Falls back to the request
     * host when the action type doesn't carry an explicit host (e.g.
     * FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK). Fail-open on misconfig or
     * detection failure.
     *
     * @param request the inbound HTTP request
     * @param action  the matched forward action
     * @return a 429 response if the budget is exceeded, or {@code null} to proceed
     */
    private HttpResponse checkLlmCostBudgetForForward(HttpRequest request, Action action) {
        String forwardHost = resolveForwardTargetHost(action);
        return checkLlmCostBudgetByHost(forwardHost, request);
    }

    /**
     * Single-call helper used by each matched FORWARD case arm. Checks the LLM
     * cost budget; if exceeded, writes the 429 response, runs the expectation
     * post-processor, and returns {@code true} (caller should {@code return}).
     * Returns {@code false} when the request should proceed. Fail-open: any
     * exception or misconfiguration returns {@code false}.
     */
    private boolean blockIfLlmCostBudgetExceeded(HttpRequest request, Action action,
                                                 ResponseWriter responseWriter,
                                                 Runnable expectationPostProcessor) {
        HttpResponse costBudgetResponse = checkLlmCostBudgetForForward(request, action);
        if (costBudgetResponse != null) {
            responseWriter.writeResponse(request, costBudgetResponse, false);
            expectationPostProcessor.run();
            return true;
        }
        return false;
    }

    /**
     * Shared LLM cost-budget guard. If {@code explicitHost} is non-null, sniffs
     * it (with the request path for the configured-provider fallback gate);
     * otherwise falls back to sniffing the request. Fail-open: a negative/unset
     * budget, a non-LLM target, or any exception returns {@code null} (proceed).
     */
    private HttpResponse checkLlmCostBudgetByHost(String explicitHost, HttpRequest request) {
        try {
            if (configuration.llmCostBudgetUsd() <= 0) {
                return null; // budget not configured — pass through
            }
            java.util.Optional<Provider> providerOpt;
            if (explicitHost != null) {
                String path = request != null && request.getPath() != null
                    ? request.getPath().getValue() : null;
                providerOpt = org.mockserver.llm.client.LlmProviderSniffer.sniffByHostAndPath(explicitHost, path);
            } else {
                providerOpt = org.mockserver.llm.client.LlmProviderSniffer.sniff(request);
            }
            if (providerOpt.isEmpty()) {
                return null; // not LLM traffic
            }
            return LlmCostBudgetMonitor.getInstance().checkBudgetOrNull(configuration);
        } catch (Exception e) {
            // fail-open: never block traffic on a detection/config error
            return null;
        }
    }

    /**
     * Resolve the forward target host from a matched FORWARD action. Returns
     * the explicit host when the action type carries one, or {@code null} to
     * fall back to request-based sniffing.
     */
    private String resolveForwardTargetHost(Action action) {
        if (action == null) {
            return null;
        }
        switch (action.getType()) {
            case FORWARD:
                HttpForward fwd = (HttpForward) action;
                return fwd.getHost();
            case FORWARD_VALIDATE:
                HttpForwardValidateAction fva = (HttpForwardValidateAction) action;
                return fva.getHost();
            case FORWARD_WITH_FALLBACK:
                HttpForwardWithFallback fwf = (HttpForwardWithFallback) action;
                if (fwf.getHttpForward() != null) {
                    return fwf.getHttpForward().getHost();
                }
                return null;
            case FORWARD_REPLACE:
                HttpOverrideForwardedRequest ofr = (HttpOverrideForwardedRequest) action;
                if (ofr.getRequestOverride() != null) {
                    // Check socket address first, then Host header
                    HttpRequest override = ofr.getRequestOverride();
                    if (override.getSocketAddress() != null
                        && override.getSocketAddress().getHost() != null
                        && !override.getSocketAddress().getHost().isEmpty()) {
                        return override.getSocketAddress().getHost();
                    }
                    String hostHeader = override.getFirstHeader("Host");
                    if (hostHeader != null && !hostHeader.isEmpty()) {
                        return stripPortFromHost(hostHeader);
                    }
                }
                return null;
            default:
                // FORWARD_TEMPLATE, FORWARD_CLASS_CALLBACK, FORWARD_OBJECT_CALLBACK:
                // target determined at runtime — fall back to request-based sniffing
                return null;
        }
    }

    /**
     * Strip the port portion from a Host header value, handling both IPv4/DNS
     * hosts ({@code example.com:443}) and bracketed IPv6 addresses
     * ({@code [::1]:8080}). Fail-open: returns the input unchanged on any
     * unexpected format.
     *
     * @param hostHeader the raw Host header value (non-null, non-empty)
     * @return the hostname without the port suffix
     */
    static String stripPortFromHost(String hostHeader) {
        if (hostHeader.startsWith("[")) {
            // Bracketed IPv6: [addr]:port or [addr]
            int closeBracket = hostHeader.indexOf(']');
            if (closeBracket < 0) {
                return hostHeader; // malformed — fail-open
            }
            // Return the address inside the brackets
            return hostHeader.substring(1, closeBracket);
        }
        // Non-bracketed: only strip ":port" when there's exactly one colon
        // (a bare IPv6 like "::1" has multiple colons — don't truncate it)
        int firstColon = hostHeader.indexOf(':');
        if (firstColon >= 0 && firstColon == hostHeader.lastIndexOf(':')) {
            return hostHeader.substring(0, firstColon);
        }
        return hostHeader;
    }

    /**
     * Process a forwarded LLM response: emit a GenAI span, increment token/cost
     * metrics and record cost against the budget monitor. Fail-soft: telemetry and
     * metrics must never affect the served response.
     * <p>
     * The gate is widened beyond just {@code GenAiSpans.isEnabled()} — the parse
     * also runs when LLM metrics are enabled or a cost budget is configured, so
     * token/cost tracking works without requiring full OTLP tracing.
     */
    private void emitForwardGenAiSpan(HttpRequest forwardedRequest, HttpResponse upstreamResponse) {
        emitForwardGenAiSpan(forwardedRequest, upstreamResponse, null, false);
    }

    private boolean isForwardLlmAccountingEnabled() {
        return GenAiSpans.isEnabled()
            || org.mockserver.metrics.Metrics.isLlmMetricsActive()
            || configuration.llmCostBudgetUsd() > 0;
    }

    /**
     * Start reading the usage out of a forwarded LLM response relayed as a stream: the captured body
     * keeps only the start of a stream, and usage is at its end. Call before the response is written.
     *
     * @return the scanner, or null when this is not LLM traffic or nothing would use the result
     */
    private LlmStreamUsageScanner observeForwardedLlmStream(HttpRequest forwardedRequest, HttpResponse streamingResponse) {
        try {
            if (!isForwardLlmAccountingEnabled()
                || org.mockserver.llm.client.LlmProviderSniffer.sniff(forwardedRequest).isEmpty()) {
                return null;
            }
            LlmStreamUsageScanner streamUsage = new LlmStreamUsageScanner();
            streamingResponse.getStreamingBody().observeChunks(streamUsage::accept);
            return streamUsage;
        } catch (Exception e) {
            // fail-soft: telemetry must never affect the served response
            return null;
        }
    }

    private void emitStreamedForwardGenAiSpan(HttpRequest forwardedRequest, HttpResponse logResponse,
                                              StreamingBody streamingBody, LlmStreamUsageScanner streamUsage) {
        if (streamUsage == null) {
            emitForwardGenAiSpan(forwardedRequest, logResponse, null, false);
        } else {
            streamingBody.afterChunksObserved(() -> emitForwardGenAiSpan(forwardedRequest, logResponse, streamUsage, streamingBody.getError() != null));
        }
    }

    /**
     * @param streamUsage  the usage read from the response as it was relayed as a stream, or null
     *                     when the whole body is in {@code upstreamResponse}
     * @param streamCutOff whether the relayed stream ended in an error rather than completing
     */
    private void emitForwardGenAiSpan(HttpRequest forwardedRequest, HttpResponse upstreamResponse, LlmStreamUsageScanner streamUsage, boolean streamCutOff) {
        boolean spanEnabled = GenAiSpans.isEnabled();
        if (!isForwardLlmAccountingEnabled()) {
            return;
        }
        try {
            java.util.Optional<Provider> providerOpt =
                org.mockserver.llm.client.LlmProviderSniffer.sniff(forwardedRequest);
            if (providerOpt.isEmpty()) {
                return;
            }
            Provider provider = providerOpt.get();
            java.util.Optional<org.mockserver.llm.client.LlmClient> clientOpt =
                org.mockserver.llm.client.LlmClientRegistry.getInstance().lookup(provider);
            if (clientOpt.isEmpty()) {
                return;
            }
            org.mockserver.llm.client.LlmClient client = clientOpt.get();
            Completion completion = null;
            LlmStreamUsageScanner scanned = streamUsage;
            if (scanned != null) {
                completion = streamedCompletion(client, scanned);
            } else {
                RuntimeException notOneJsonDocument = null;
                try {
                    completion = client.parseCompletionResponse(upstreamResponse);
                } catch (RuntimeException e) {
                    notOneJsonDocument = e;
                }
                if (completion == null || completion.getUsage() == null) {
                    // an event stream that was aggregated rather than relayed as a stream
                    scanned = scanBody(upstreamResponse);
                    if (scanned != null && (scanned.usage() != null || scanned.streamed())) {
                        completion = streamedCompletion(client, scanned);
                    } else {
                        scanned = null;
                    }
                }
                if (completion == null) {
                    if (notOneJsonDocument != null) {
                        throw notOneJsonDocument;
                    }
                    // a client that returned no completion without failing: nothing to record
                    return;
                }
            }
            // Prefer the model from the parsed completion; fall back to the request body
            String model = completion.getModel();
            if (model == null) {
                model = org.mockserver.llm.client.LlmProviderSniffer.extractModelFromRequest(forwardedRequest);
            }
            if (scanned != null) {
                logStreamedUsageUnavailable(provider, model, forwardedRequest, upstreamResponse, completion.getUsage(), scanned, streamCutOff);
            }
            if (spanEnabled) {
                GenAiSpans.recordCompletion(provider, model, completion);
            }
            // Increment LLM token/cost metrics and record against the budget monitor
            recordLlmUsageMetrics(provider, model, completion);
        } catch (Exception e) {
            // fail-soft: telemetry must never affect the served response
            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception emitting forward-path GenAI span:{}")
                        .setArguments(e.getMessage())
                        .setThrowable(e)
                );
            }
        }
    }

    private static LlmStreamUsageScanner scanBody(HttpResponse upstreamResponse) {
        byte[] body = upstreamResponse.getBodyAsRawBytes();
        if (body == null || body.length == 0) {
            return null;
        }
        LlmStreamUsageScanner scanned = new LlmStreamUsageScanner();
        scanned.accept(body);
        return scanned;
    }

    private static Completion streamedCompletion(org.mockserver.llm.client.LlmClient client, LlmStreamUsageScanner scanned) {
        Completion completion = Completion.completion();
        if (scanned.model() != null) {
            completion.withModel(scanned.model());
        }
        if (scanned.stopReason() != null) {
            completion.withStopReason(scanned.stopReason());
        }
        Usage usage = scanned.usage() != null ? client.parseUsage(scanned.usage()) : null;
        if (usage != null) {
            completion.withUsage(usage);
        }
        return completion;
    }

    static final String STREAMED_USAGE_MISSING_MESSAGE_FORMAT =
        "no token usage in the streamed {} response for model {}, so the call is not counted in LLM token and cost metrics or towards llmCostBudgetUsd";
    static final String STREAMED_USAGE_MISSING_OPENAI_MESSAGE_FORMAT = STREAMED_USAGE_MISSING_MESSAGE_FORMAT
        + " - set stream_options.include_usage to true in the client's request for it to be counted";
    static final String STREAMED_USAGE_INCOMPLETE_MESSAGE_FORMAT =
        "the streamed {} response for model {} ended before its final usage event, so only the tokens reported before it ended are counted in LLM token and cost metrics and towards llmCostBudgetUsd";

    private static final long USAGE_LOG_INTERVAL_NANOS = java.util.concurrent.TimeUnit.MINUTES.toNanos(1);
    private static final int USAGE_LOG_KEYS = 256;
    private final java.util.concurrent.ConcurrentHashMap<String, Long> usageLogTimes = new java.util.concurrent.ConcurrentHashMap<>();

    // nothing is estimated in place of missing usage, so the operator is told the call was not counted
    private void logStreamedUsageUnavailable(Provider provider, String model, HttpRequest forwardedRequest, HttpResponse upstreamResponse,
                                             Usage usage, LlmStreamUsageScanner scanned, boolean streamCutOff) {
        Integer statusCode = upstreamResponse.getStatusCode();
        if (statusCode != null && (statusCode < 200 || statusCode > 299)) {
            return;
        }
        boolean counted = usage != null && (usage.getInputTokens() != null || usage.getOutputTokens() != null);
        // a stream that repeats its counts on every chunk is whole unless it was cut off
        boolean whole = scanned.finalUsageSeen() || (scanned.usageOnEveryChunk() && !streamCutOff);
        if ((counted && whole) || !usageLogDue(provider, model, counted)) {
            return;
        }
        // an uncounted call is a hole in the budget, so it is a warning once a budget is set
        Level level = configuration.llmCostBudgetUsd() > 0 ? Level.WARN : Level.INFO;
        String path = forwardedRequest.getPath() != null ? forwardedRequest.getPath().getValue() : null;
        // Azure serves the Responses API too, where usage is not opt-in
        boolean usageIsOptIn = provider == Provider.OPENAI
            || (provider == Provider.AZURE_OPENAI && (path == null || !path.toLowerCase().contains("/responses")));
        if (mockServerLogger.isEnabledForInstance(level)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(level)
                    .setMessageFormat(counted
                        ? STREAMED_USAGE_INCOMPLETE_MESSAGE_FORMAT
                        : (usageIsOptIn ? STREAMED_USAGE_MISSING_OPENAI_MESSAGE_FORMAT : STREAMED_USAGE_MISSING_MESSAGE_FORMAT))
                    .setArguments(provider, model)
            );
        }
    }

    // one line a minute for each provider and model, so a client that never sends usage does not flood the log
    private boolean usageLogDue(Provider provider, String model, boolean counted) {
        String modelKey = model == null ? "" : model.substring(0, Math.min(model.length(), LlmStreamUsageScanner.MAX_TEXT_CHARS));
        String key = provider.name() + '|' + counted + '|' + modelKey;
        long now = org.mockserver.time.TimeService.nanoTime();
        Long last = usageLogTimes.get(key);
        if (last != null && now - last < USAGE_LOG_INTERVAL_NANOS) {
            return false;
        }
        if (usageLogTimes.size() >= USAGE_LOG_KEYS) {
            usageLogTimes.clear();
        }
        usageLogTimes.put(key, now);
        return true;
    }

    /**
     * Increment LLM token/cost Prometheus counters and record cost against the
     * cost-budget circuit-breaker. Shared by both the forward path and the mock
     * path (via {@link HttpLlmResponseActionHandler}). Fail-soft.
     */
    public static void recordLlmUsageMetrics(Provider provider, String model, Completion completion) {
        if (completion == null) {
            return;
        }
        try {
            Usage usage = completion.getUsage();
            long inputTokens = usage != null && usage.getInputTokens() != null ? usage.getInputTokens().longValue() : 0L;
            long outputTokens = usage != null && usage.getOutputTokens() != null ? usage.getOutputTokens().longValue() : 0L;
            String providerName = provider != null ? provider.name() : "unknown";
            Double costUsd = org.mockserver.llm.cost.LlmPricing.estimateCostUsd(
                provider, model, inputTokens, outputTokens);
            org.mockserver.metrics.Metrics.incrementLlmTokens(providerName, model, inputTokens, outputTokens, costUsd);
            LlmCostBudgetMonitor.getInstance().recordCost(costUsd);
        } catch (Exception ignored) {
            // fail-soft: metrics must never affect the served response
        }
    }

    /**
     * Emit an OpenTelemetry SERVER span for a served HTTP request when
     * {@link RequestSpans} is enabled. Fail-soft: telemetry must never
     * affect the served response. The span is parented to the inbound
     * W3C trace context when available on the channel.
     */
    private void emitRequestSpan(HttpRequest request, HttpResponse response, Action action,
                                 ChannelHandlerContext ctx, long responseTimeMs) {
        emitRequestSpan(request, response, action, ctx, responseTimeMs, null);
    }

    /**
     * Emit a request-level SERVER span, additionally carrying the resolved
     * upstream {@code server.address}/{@code server.port} for forward/proxy paths.
     */
    private void emitRequestSpan(HttpRequest request, HttpResponse response, Action action,
                                 ChannelHandlerContext ctx, long responseTimeMs,
                                 InetSocketAddress upstreamAddress) {
        if (!RequestSpans.isEnabled()) {
            return;
        }
        try {
            String method = request.getMethod() != null ? request.getMethod().getValue() : null;
            String path = request.getPath() != null ? request.getPath().getValue() : null;
            Integer statusCode = response != null ? response.getStatusCode() : null;
            String expectationId = action != null ? action.getExpectationId() : null;
            W3CTraceContext parentContext = null;
            if (ctx != null) {
                parentContext = ctx.channel().attr(TraceContextAttributes.TRACE_CONTEXT).get();
            }
            String serverAddress = resolveUpstreamHost(action, upstreamAddress);
            Integer serverPort = upstreamAddress != null && upstreamAddress.getPort() > 0 ? upstreamAddress.getPort() : null;
            RequestSpans.recordRequest(method, path, statusCode, expectationId, responseTimeMs, parentContext, serverAddress, serverPort);
        } catch (Exception e) {
            // fail-soft: telemetry must never affect the served response
        }
    }

    /**
     * Record per-upstream forward/proxy observability metrics for a completed
     * forward. No-op unless metrics are enabled (the underlying recorder is a
     * static no-op then). Labels by upstream host only (never the full URL/path)
     * to keep Prometheus cardinality bounded. Fail-soft.
     *
     * @param action          the matched forward action, or null on the unmatched proxy path
     * @param response        the upstream response (carries the Timing for latency), or null
     * @param upstreamAddress the resolved upstream socket address
     * @param responseTimeMs  fallback wall-clock latency when the response carries no Timing
     */
    /**
     * Effective upstream round-trip latency in milliseconds for the LOGGED forwarded response.
     * Prefers the precise {@code Timing} measured by {@link NettyHttpClient} (total round-trip),
     * falling back to the supplied wall-clock delta when no Timing is attached. Used to populate
     * {@link #RESPONSE_TIME_HEADER} on the event-log clone (see {@link #recordForwardMetrics} which
     * applies the same precedence for metrics).
     */
    private static long effectiveForwardLatencyMs(HttpResponse response, long fallbackMs) {
        if (response != null && response.getTiming() != null && response.getTiming().getTotalTimeInMillis() != null) {
            return response.getTiming().getTotalTimeInMillis();
        }
        return fallbackMs;
    }

    private void recordForwardMetrics(Action action, HttpResponse response,
                                      InetSocketAddress upstreamAddress, long responseTimeMs) {
        try {
            String host = resolveUpstreamHost(action, upstreamAddress);
            Integer statusCode = response != null ? response.getStatusCode() : null;
            // Prefer the precise Timing measured by NettyHttpClient (total round-trip);
            // fall back to the coarse wall-clock delta when no Timing is attached.
            long latencyMillis = responseTimeMs;
            if (response != null && response.getTiming() != null && response.getTiming().getTotalTimeInMillis() != null) {
                latencyMillis = response.getTiming().getTotalTimeInMillis();
            }
            // SLO sample tracking is independent of the metrics feature: it has its
            // own sloTrackingEnabled gate (a no-op inside record(...) when off), so
            // it must run even when forward metrics are inactive.
            org.mockserver.slo.SloSampleStore.getInstance().record(
                configuration,
                org.mockserver.time.TimeService.currentTimeMillis(),
                latencyMillis,
                statusCode == null || statusCode >= 500,
                org.mockserver.slo.Scope.FORWARD,
                host
            );
            if (org.mockserver.metrics.Metrics.isForwardMetricsActive()) {
                org.mockserver.metrics.Metrics.observeForwardRequest(host, statusCode, latencyMillis / 1000.0);
            }
        } catch (Exception e) {
            // fail-soft: metrics must never affect the served response
        }
    }

    /**
     * Resolve the upstream host label for forward observability: prefer the
     * explicit host from a matched forward action (the real upstream even behind
     * an HTTP forward-proxy), else fall back to the resolved socket address host.
     * Returns null when neither is available.
     */
    private String resolveUpstreamHost(Action action, InetSocketAddress upstreamAddress) {
        String host = resolveForwardTargetHost(action);
        if (host != null && !host.isEmpty()) {
            return host;
        }
        if (upstreamAddress != null) {
            return upstreamAddress.getHostString();
        }
        return null;
    }
}

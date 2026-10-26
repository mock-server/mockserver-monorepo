package org.mockserver.inprocess;

import com.google.common.collect.ImmutableSet;
import io.netty.channel.EventLoopGroup;
import io.netty.handler.codec.http.HttpResponseStatus;
import org.mockserver.authentication.ControlPlaneAuthenticationHandlerFactory;
import org.mockserver.authentication.DataPlaneAuthenticationGate;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RequestDefinition;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.responsewriter.TransformingWrites;
import org.mockserver.responsewriter.TransformingWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.PortBindingSerializer;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.mockserver.verify.VerificationTimes;

import java.io.Closeable;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpResponseStatus.NOT_IMPLEMENTED;
import static io.netty.handler.codec.http.HttpResponseStatus.OK;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isEmpty;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.HttpState.PATH_PREFIX;
import static org.mockserver.model.PortBinding.portBinding;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;
import static org.mockserver.verify.Verification.verification;
import static org.mockserver.verify.VerificationSequence.verificationSequence;

/**
 * MockServer request handling without an HTTP server: each request is passed in as an {@link HttpRequest} and its
 * response returned, with no socket opened. It is the equivalent of WireMock's {@code DirectCallHttpServer}, for
 * places that hand requests over as Java calls (AWS Lambda, an in-JVM test of an HTTP adapter).
 * <p>
 * Requests go through the same {@link HttpState} and {@link HttpActionHandler} as the Netty server and the WAR, so
 * matching, templates, delays, forwards, class callbacks, verification and the event log behave as they do over
 * HTTP, and forwards go through the configured {@code forwardHttpProxy}, {@code forwardHttpsProxy} or
 * {@code forwardSocksProxy}. Most of the REST control plane ({@code /mockserver/...}) is served by {@link #handle}
 * too, with control-plane authentication applied as configured; the routes only the Netty server answers
 * ({@code /configuration}, {@code /ready}, {@code /metrics}, the dashboard) answer 404. The Java methods here are
 * in-JVM calls and are not authenticated. With {@code dataPlaneAuthenticationRequired}, a request {@link #handle} serves
 * from expectations must carry the configured Basic, Bearer or API-key credentials in its headers, as over HTTP; there
 * is no TLS, so {@code tlsMutualAuthenticationRequired} does not apply and a client certificate chain set on the
 * request is never a credential. What needs a connection is not available: object callbacks (they need a
 * WebSocket client), SSE, WebSocket and gRPC streaming, and {@code HttpError} connection faults.
 * <p>
 * Configured extensions start with this instance, with no ports, and stop on {@link #close()}; their admin routes
 * are served by {@link #handle}, inline on the calling thread.
 * <p>
 * The expectations, recorded requests and log are this instance's own; the process-wide registries (rate limits,
 * chaos, quotas and the like) are shared with every MockServer in the JVM, as they are between Netty servers.
 */
public class InProcessMockServer implements Closeable {

    private static final Set<String> IN_PROCESS_ADDRESSES = ImmutableSet.of("localhost", "127.0.0.1");

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final Scheduler scheduler;
    private final HttpState httpState;
    private final HttpActionHandler actionHandler;
    private final PortBindingSerializer portBindingSerializer;
    // null unless extensions are configured, so with none the extension package is never loaded
    private final ExtensionRegistry extensionRegistry;
    // created on the first forward only, as the Netty server does, so a pure-mock instance starts no event loop
    private volatile EventLoopGroup forwardClientGroup;
    private final Object forwardClientGroupLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    public InProcessMockServer() {
        this(null);
    }

    public InProcessMockServer(Configuration configuration) {
        this.configuration = configuration != null ? configuration : configuration();
        ControlPlaneAuthenticationHandlerFactory.requireUsableControlPlaneMutualTls(this.configuration);
        // read before anything is allocated: an invalid combination (SOCKS with HTTP) throws
        List<ProxyConfiguration> proxyConfigurations = proxyConfiguration(this.configuration);
        if (this.configuration.logEventListener() != null) {
            MockServerLogger.setGlobalLogEventListener(this.configuration.logEventListener());
        }
        MockServerLogger logger = new MockServerLogger(this.configuration, MockServerEventLog.class);
        // extensions start before anything else is built, so a refused start leaks no thread
        this.extensionRegistry = this.configuration.extensionsConfigured() ? ExtensionRegistry.start(this.configuration, logger) : null;
        Scheduler createdScheduler = null;
        HttpState createdHttpState = null;
        try {
            this.scheduler = createdScheduler = new Scheduler(this.configuration, logger);
            this.httpState = createdHttpState = new HttpState(this.configuration, logger, this.scheduler, this.extensionRegistry);
            this.mockServerLogger = httpState.getMockServerLogger();
            this.portBindingSerializer = new PortBindingSerializer(mockServerLogger);
            this.actionHandler = new HttpActionHandler(this.configuration, this::getForwardClientEventLoopGroup, httpState, proxyConfigurations, new NettySslContextFactory(this.configuration, mockServerLogger, false));
            this.httpState.installRequestSender(actionHandler.getRequestSender());
            if (this.extensionRegistry != null) {
                // an in-process server has no ports to report
                this.extensionRegistry.notifyStarted(Collections.emptyList());
            }
        } catch (RuntimeException | Error failed) {
            // the caller gets no reference to close, so release what was started
            closed.set(true);
            stopRefusedStart(createdHttpState, createdScheduler, this.extensionRegistry);
            throw failed;
        }
    }

    private static void stopRefusedStart(HttpState httpState, Scheduler scheduler, ExtensionRegistry extensionRegistry) {
        try {
            if (httpState != null) {
                httpState.stop();
            }
        } finally {
            if (scheduler != null) {
                scheduler.shutdown();
            }
            if (extensionRegistry != null) {
                extensionRegistry.stop();
            }
        }
    }

    /**
     * Handles a request and returns its response, as MockServer would answer it over HTTP. A response delay (an
     * action's {@code delay} or {@code globalResponseDelayMillis}) blocks the calling thread for its duration, and a
     * forward blocks it until the upstream answers, for at most {@code maxSocketTimeoutInMillis}. An extension admin
     * route ({@code /mockserver/extensions/<name>/...}) runs on the calling thread with no timeout;
     * {@code maxSocketTimeoutInMillis} does not bound it.
     *
     * @throws IllegalStateException if no response was produced within {@code maxSocketTimeoutInMillis}, or this
     *                               instance is closed
     */
    public HttpResponse handle(HttpRequest request) {
        CompletableFuture<HttpResponse> response = dispatch(request, true);
        try {
            return response.get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the response to " + describe(request), interrupted);
        } catch (TimeoutException timeout) {
            throw new IllegalStateException("no response to " + describe(request) + " was produced within " + configuration.maxSocketTimeoutInMillis() + "ms", timeout);
        } catch (ExecutionException failed) {
            throw new IllegalStateException("failed to handle " + describe(request), failed.getCause());
        }
    }

    /**
     * Handles a request without blocking on delays or forwards: a delayed response is completed on MockServer's
     * scheduler when the delay has elapsed, and a forwarded one when the upstream answers. Matching, and any
     * response with no delay, runs on the calling thread. Apply a timeout to the future if an expectation could
     * leave a request unanswered, and use an async continuation with your own executor for blocking work, as a
     * forwarded response completes the future on MockServer's scheduler. An extension admin route
     * ({@code /mockserver/extensions/<name>/...}) runs on the calling thread, with no timeout, before the future is
     * returned.
     */
    public CompletableFuture<HttpResponse> handleAsync(HttpRequest request) {
        return dispatch(request, false);
    }

    /**
     * Adds or updates expectations, as {@code PUT /mockserver/expectation} does.
     */
    public List<Expectation> upsert(Expectation... expectations) {
        requireOpen();
        return httpState.add(expectations);
    }

    /**
     * Verifies that requests matching {@code requestDefinition} were received {@code times} times.
     *
     * @throws AssertionError describing the mismatch when the verification fails
     */
    public InProcessMockServer verify(RequestDefinition requestDefinition, VerificationTimes times) {
        return verify(verification().withRequest(requestDefinition).withTimes(times));
    }

    /**
     * @throws AssertionError describing the mismatch when the verification fails
     */
    public InProcessMockServer verify(Verification verification) {
        requireOpen();
        assertVerified(httpState.verify(verification));
        return this;
    }

    /**
     * Verifies that requests matching each request definition were received in this order.
     *
     * @throws AssertionError describing the mismatch when the verification fails
     */
    public InProcessMockServer verify(RequestDefinition... requestDefinitions) {
        return verify(verificationSequence().withRequests(requestDefinitions));
    }

    /**
     * @throws AssertionError describing the mismatch when the verification fails
     */
    public InProcessMockServer verify(VerificationSequence verificationSequence) {
        requireOpen();
        assertVerified(httpState.verify(verificationSequence));
        return this;
    }

    /**
     * @return the recorded requests that match {@code requestDefinition}, or every recorded request when it is null
     */
    public RequestDefinition[] retrieveRecordedRequests(RequestDefinition requestDefinition) {
        requireOpen();
        CompletableFuture<List<RequestDefinition>> recorded = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveRequests(requestDefinition, recorded::complete);
        return await(recorded, "retrieving recorded requests").toArray(new RequestDefinition[0]);
    }

    /**
     * Clears every expectation, the recorded requests and the log, as {@code PUT /mockserver/reset} does. Like that
     * reset, it also resets the process-wide registries (rate limits, chaos, quotas and the like) that every
     * MockServer in the JVM shares.
     */
    public InProcessMockServer reset() {
        requireOpen();
        httpState.reset();
        return this;
    }

    /**
     * The state behind this instance, for any control-plane operation not offered here.
     */
    public HttpState getHttpState() {
        return httpState;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Stops the scheduler and its executors, the event log, the forward client's event loop if a forward created it
     * and, last, any extensions, in reverse start order. Their threads end once idle; a delayed response still
     * pending is completed when its delay elapses, and its thread ends then. Every other method then throws
     * {@link IllegalStateException}.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            httpState.stop();
        } finally {
            scheduler.shutdown();
            final EventLoopGroup forwardGroupToStop;
            synchronized (forwardClientGroupLock) {
                forwardGroupToStop = forwardClientGroup;
            }
            try {
                if (forwardGroupToStop != null) {
                    forwardGroupToStop.shutdownGracefully(0, 5, MILLISECONDS).awaitUninterruptibly(5, SECONDS);
                }
            } finally {
                if (extensionRegistry != null) {
                    extensionRegistry.stop();
                }
            }
        }
    }

    private EventLoopGroup getForwardClientEventLoopGroup() {
        EventLoopGroup group = forwardClientGroup;
        if (group != null) {
            return group;
        }
        synchronized (forwardClientGroupLock) {
            if (forwardClientGroup == null) {
                EventLoopGroup created = NettyTransport.newEventLoopGroup(
                    configuration.clientNioEventLoopThreadCount(),
                    new Scheduler.SchedulerThreadFactory(getClass().getSimpleName() + "-forwardClientEventLoop"),
                    configuration.useNativeTransport());
                if (closed.get()) {
                    // close() may already have passed its teardown of this group; never leave one running
                    created.shutdownGracefully(0, 0, MILLISECONDS);
                }
                forwardClientGroup = created;
            }
            return forwardClientGroup;
        }
    }

    private CompletableFuture<HttpResponse> dispatch(HttpRequest originalRequest, boolean synchronous) {
        if (originalRequest == null) {
            throw new IllegalArgumentException("request must not be null");
        }
        requireOpen();
        // a copy, so the correlation id and timestamp set on it never leak into a request object the caller reuses
        HttpRequest request = originalRequest.clone();
        if (request.isKeepAlive() == null) {
            // what an HTTP/1.1 client sends unless it asks otherwise
            request.withKeepAlive(true);
        }
        InProcessResponseWriter responseWriter = httpState.dataPlaneHooks() == null
            ? new InProcessResponseWriter(configuration, mockServerLogger)
            : TransformingInProcessResponseWriter.create(configuration, mockServerLogger, httpState, synchronous);
        boolean dataPlane = false;
        try {
            // warDeployment: control-plane requests are answered before handle() returns, as a servlet needs
            if (!httpState.handle(request, responseWriter, true)) {
                if (request.getPath() != null && "/_mockserver_callback_websocket".equals(request.getPath().getValue())) {
                    responseWriter.writeResponse(request, NOT_IMPLEMENTED, "object callbacks need a WebSocket connection, which an in-process MockServer does not have; use a class callback", "text/plain");
                } else if (request.matches("PUT", PATH_PREFIX + "/status", "/status") ||
                    isNotBlank(configuration.livenessHttpGetPath()) && request.matches("GET", configuration.livenessHttpGetPath())) {
                    responseWriter.writeResponse(request, OK, portBindingSerializer.serialize(portBinding()), "application/json");
                } else if (request.matches("PUT", PATH_PREFIX + "/bind", "/bind") || request.matches("PUT", PATH_PREFIX + "/stop", "/stop")) {
                    responseWriter.writeResponse(request, NOT_IMPLEMENTED);
                } else {
                    dataPlane = true;
                    // authenticate before anything that can match an expectation (and so use up Times or move a
                    // scenario); a data-plane hook such as the request filter runs after this, inside the branch
                    if (DataPlaneAuthenticationGate.isAuthenticated(configuration, mockServerLogger, httpState, request, responseWriter)) {
                        actionHandler.processAction(request, responseWriter, null, localAddresses(request), false, synchronous);
                    }
                }
            }
        } catch (Throwable throwable) {
            if (dataPlane && httpState.dataPlaneHooks() != null) {
                // untransformed, and only if no response or earlier failure has claimed the exchange
                TransformingWriter.failUnanswered(mockServerLogger, responseWriter, request, throwable);
            } else if (dataPlane) {
                String message = ControlPlaneFailureResponse.logUnexpectedFailure(mockServerLogger, request, throwable);
                responseWriter.writeResponse(request, ControlPlaneFailureResponse.dataPlaneFailureResponse(message), false);
            } else {
                ControlPlaneFailureResponse.write(mockServerLogger, responseWriter, request, throwable);
            }
        }
        return responseWriter.response;
    }

    /**
     * Every request is addressed to this instance, so its own Host is local: an unmatched request is never proxied
     * to the host it names, which over HTTP happens only when a client uses MockServer as its proxy.
     */
    private static Set<String> localAddresses(HttpRequest request) {
        String hostHeader = request.getFirstHeader(HOST.toString());
        if (isNotBlank(hostHeader)) {
            return ImmutableSet.<String>builder().addAll(IN_PROCESS_ADDRESSES).add(hostHeader).build();
        }
        return IN_PROCESS_ADDRESSES;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("InProcessMockServer is closed");
        }
    }

    private void assertVerified(Future<String> result) {
        String failure = await(result, "verifying");
        if (isNotBlank(failure)) {
            throw new AssertionError(failure);
        }
    }

    private <T> T await(Future<T> future, String operation) {
        try {
            return future.get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while " + operation, interrupted);
        } catch (TimeoutException | ExecutionException failed) {
            throw new IllegalStateException("failed while " + operation, failed);
        }
    }

    private static String describe(HttpRequest request) {
        return request.getMethod("") + " " + request.getPath();
    }

    private static class InProcessResponseWriter extends ResponseWriter {

        private final CompletableFuture<HttpResponse> response = new CompletableFuture<>();

        InProcessResponseWriter(Configuration configuration, MockServerLogger mockServerLogger) {
            super(configuration, mockServerLogger);
        }

        /**
         * Fills in what the Netty encoder would put on the wire: the 200 status of a response that names none, the
         * standard reason phrase, and a Content-Type taken from the body. The response is the per-write copy
         * {@link ResponseWriter#writeResponse} makes, so an expectation's own response is never changed.
         */
        @Override
        public void sendResponse(HttpRequest request, HttpResponse httpResponse) {
            if (httpResponse.getStatusCode() == null) {
                httpResponse.withStatusCode(OK.code());
            }
            if (isEmpty(httpResponse.getReasonPhrase())) {
                httpResponse.withReasonPhrase(HttpResponseStatus.valueOf(httpResponse.getStatusCode()).reasonPhrase());
            }
            if (isBlank(httpResponse.getFirstHeader(CONTENT_TYPE.toString())) && httpResponse.getBody() != null && httpResponse.getBody().getContentType() != null) {
                httpResponse.withHeader(CONTENT_TYPE.toString(), httpResponse.getBody().getContentType());
            }
            response.complete(httpResponse);
        }
    }

    /**
     * The writer built when an extension request filter or response transformer is loaded: an
     * {@link InProcessResponseWriter} whose data-plane writes follow {@link TransformingWrites}. For {@link #handle}
     * the calling thread waits, for at most the budget, for the response transformers and completes the response
     * itself; for {@link #handleAsync} the extension pool thread that finishes them does.
     */
    private static final class TransformingInProcessResponseWriter extends InProcessResponseWriter implements TransformingWriter {

        private final TransformingWrites writes;

        private TransformingInProcessResponseWriter(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, DataPlaneHooks hooks, boolean synchronous) {
            super(configuration, mockServerLogger);
            this.writes = new TransformingWrites(hooks, scheduler, mockServerLogger, this::writeWithoutHooks, () -> false, synchronous);
        }

        /**
         * Returns the base type, so {@code dispatch} never needs this class unless an extension hook is loaded.
         */
        static InProcessResponseWriter create(Configuration configuration, MockServerLogger mockServerLogger, HttpState httpState, boolean synchronous) {
            return new TransformingInProcessResponseWriter(configuration, mockServerLogger, httpState.getScheduler(), httpState.dataPlaneHooks(), synchronous);
        }

        private void writeWithoutHooks(HttpRequest request, HttpResponse response, boolean apiResponse) {
            super.writeResponse(request, response, apiResponse);
        }

        @Override
        public void writeResponse(HttpRequest request, HttpResponse response, boolean apiResponse) {
            writes.writeResponse(request, response, apiResponse);
        }

        @Override
        public void writeDataPlanePreflightResponse(HttpRequest request) {
            writes.untransformed(() -> super.writeDataPlanePreflightResponse(request));
        }

        @Override
        public void markDataPlane(HttpRequest request) {
            writes.markDataPlane(request);
        }

        @Override
        public void markGenerated() {
            writes.markGenerated();
        }

        @Override
        public boolean isClaimed() {
            return writes.isClaimed();
        }

        @Override
        public void writeUntransformed(HttpRequest request, HttpResponse response) {
            writes.writeUntransformed(request, response);
        }

        @Override
        public void failUnanswered(Throwable throwable) {
            writes.failUnanswered(throwable);
        }
    }
}

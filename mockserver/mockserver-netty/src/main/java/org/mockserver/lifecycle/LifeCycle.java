package org.mockserver.lifecycle;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.util.NetUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.UpcomingDefaultChanges;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.socket.NettyTransport;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.listeners.MockServerMatcherNotifier;
import org.mockserver.netty.unification.ClientTlsHandshakeFailureLog;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.stop.Stoppable;

import java.net.BindException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.mock.HttpState.setPort;
import static org.slf4j.event.Level.*;

/**
 * @author jamesdbloom
 */
public abstract class LifeCycle implements Stoppable {

    // A shadowed explicit port fails startup, so its verdict waits longer before blaming a slow accept
    // loop. A false positive for an operating-system-chosen port only costs a rebind, so that verdict
    // starts short and doubles per attempt, which keeps a stalled accept loop from failing every attempt.
    private static final long SHADOW_PROBE_ACCEPT_TIMEOUT_EXPLICIT_MILLIS = 5000;
    private static final long SHADOW_PROBE_ACCEPT_TIMEOUT_EPHEMERAL_INITIAL_MILLIS = 250;
    static final int SHADOW_PROBE_MAX_EPHEMERAL_ATTEMPTS = 10;
    // well inside the 30 s after which stop() gives up, and the budget the AsyncAPI broker closes get
    static final long EVENT_LOOP_TERMINATION_WAIT_MILLIS = 5_000;

    /**
     * Seam over {@link LoopbackShadowProbe} so tests in this package can force a shadowed verdict or a
     * busy retry port on any operating system.
     */
    interface BindVerifier {
        InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis) throws InterruptedException;

        int nextCandidatePort();
    }

    static final BindVerifier LOOPBACK_SHADOW_PROBE = new BindVerifier() {
        @Override
        public InetSocketAddress findShadowedLoopback(Channel serverChannel, long acceptTimeoutMillis) throws InterruptedException {
            return LoopbackShadowProbe.findShadowedLoopback(serverChannel, acceptTimeoutMillis);
        }

        @Override
        public int nextCandidatePort() {
            return LoopbackShadowProbe.portFreeOnIpv4();
        }
    };

    BindVerifier bindVerifier = LOOPBACK_SHADOW_PROBE;

    protected final MockServerLogger mockServerLogger;
    protected final EventLoopGroup bossGroup;
    protected final EventLoopGroup workerGroup;
    // Dedicated event-loop group for the outbound forward/proxy (loopback) HTTP client. It is kept
    // DISJOINT from the server worker group so that a pooled keep-alive channel reused inside a
    // synchronous local object-callback (which runs ON a server worker thread and makes a blocking
    // loopback call back to this server) is never pinned to the very worker thread that is blocked
    // in the callback — which would self-deadlock the event loop. This disjoint group is what makes
    // forwardConnectionPoolEnabled safe to default on. Sized by clientNioEventLoopThreadCount.
    //
    // VERIFICATION GUARD: this self-deadlock only surfaces in the FAILSAFE integration phase (e.g.
    // WebsocketCallbackRegistryIntegrationTest, ExtendedNettyMockingIntegrationTest, the proxy
    // integration tests), NOT in a targeted `-Dtest` unit run — this area has regressed TWICE because
    // per-unit verification skipped that phase. Any change to this group, its wiring into the forward
    // HttpActionHandler (MockServer.java getForwardClientEventLoopGroup()), or HttpClientHandler's
    // pool-return gate MUST be verified by running those failsafe integration classes with pooling on
    // (the default), not just targeted unit tests. The surefire-phase guards
    // ForwardClientEventLoopIsolationTest / ForwardConnectionPoolLoopbackCallbackTest (mockserver-netty)
    // and NettyHttpClientConnectionPoolTest (mockserver-core) lock the invariant, but the failsafe
    // phase remains the backstop. See docs/operations/performance-tuning.md.
    //
    // LAZY: this group is created on the FIRST forward/proxy use (via getForwardClientEventLoopGroup()),
    // NOT in the constructor — a pure-mock deployment that never forwards never allocates it (saving
    // clientNioEventLoopThreadCount selectors/threads at startup). volatile for safe double-checked-lock
    // publication; all creation and shutdown of this field happen under forwardClientGroupLock so a
    // first-forward racing a stop() can never leak an un-terminated group (see the accessor and
    // stopAsync()). Read reflectively by name in the ForwardClientEventLoop* guard tests.
    protected volatile EventLoopGroup forwardClientGroup;
    private final Object forwardClientGroupLock = new Object();
    protected final HttpState httpState;
    private final Configuration configuration;
    protected ServerBootstrap serverServerBootstrap;
    private final List<Future<Channel>> serverChannelFutures = new CopyOnWriteArrayList<>();
    private final CompletableFuture<String> stopFuture = new CompletableFuture<>();
    private final AtomicBoolean stopping = new AtomicBoolean(false);
    // Number of data-plane HTTP requests currently being processed (incremented when a request
    // starts processing, decremented when its response has been written). Used by stopAsync() to
    // drain in-flight requests before shutting down the event loops (WS7.2 graceful shutdown).
    private final java.util.concurrent.atomic.AtomicInteger requestsInFlight = new java.util.concurrent.atomic.AtomicInteger(0);
    private final java.util.function.IntSupplier requestsInFlightSupplier = requestsInFlight::get;
    // set once stop has stopped waiting for in-flight requests, so requests still held behind a pipelined one are dropped
    private volatile boolean drainEnded;
    private final org.mockserver.codec.HeldRequestAccounting heldRequestAccounting = new org.mockserver.codec.HeldRequestAccounting() {
        @Override
        public void held() {
            requestProcessingStarted();
        }

        @Override
        public void released() {
            requestProcessingComplete();
        }

        @Override
        public boolean discardHeldOnClose() {
            return drainEnded;
        }
    };
    private final Scheduler scheduler;
    private final ClientTlsHandshakeFailureLog clientTlsHandshakeFailureLog = new ClientTlsHandshakeFailureLog();
    // optional OTel exporters — null unless the corresponding config is enabled
    private final org.mockserver.metrics.OtelMetricsExporter otelMetricsExporter;
    private final org.mockserver.telemetry.GenAiSpanExporter genAiSpanExporter;
    // optional Prometheus remote-write push exporter — null unless enabled
    private final org.mockserver.metrics.PrometheusRemoteWriteExporter prometheusRemoteWriteExporter;
    private final DriftWiring driftWiring;
    // null unless extensions are configured, so an unconfigured server loads no extension class
    private final ExtensionRegistry extensionRegistry;

    protected LifeCycle(Configuration configuration) {
        this.configuration = configuration != null ? configuration : configuration();
        this.mockServerLogger = new MockServerLogger(this.configuration, MockServerEventLog.class);
        if (this.configuration.logEventListener() != null) {
            MockServerLogger.setGlobalLogEventListener(this.configuration.logEventListener());
        }
        // extensions start before any event loop, selector or scheduler exists, so a refused start leaks none
        this.extensionRegistry = this.configuration.extensionsConfigured() ? ExtensionRegistry.start(this.configuration, this.mockServerLogger) : null;
        try {
            boolean nativeTransport = this.configuration.useNativeTransport();
            this.bossGroup = NettyTransport.newEventLoopGroup(5, new Scheduler.SchedulerThreadFactory(this.getClass().getSimpleName() + "-bossEventLoop"), nativeTransport);
            this.workerGroup = NettyTransport.newEventLoopGroup(this.configuration.nioEventLoopThreadCount(), new Scheduler.SchedulerThreadFactory(this.getClass().getSimpleName() + "-workerEventLoop"), nativeTransport);
            // NOTE: the outbound forward/proxy (loopback) client's event-loop group (forwardClientGroup) is
            // NOT created here — it is created lazily on first forward via getForwardClientEventLoopGroup(),
            // so a pure-mock server never allocates it. It remains disjoint from workerGroup (see field javadoc).
            this.scheduler = new Scheduler(this.configuration, this.mockServerLogger);
            this.httpState = new HttpState(this.configuration, this.mockServerLogger, this.scheduler, this.extensionRegistry);
            this.otelMetricsExporter = org.mockserver.metrics.OtelMetricsExporter.startIfEnabled(this.configuration);
            this.genAiSpanExporter = org.mockserver.telemetry.GenAiSpanExporter.startIfEnabled(this.configuration);
            this.prometheusRemoteWriteExporter = org.mockserver.metrics.PrometheusRemoteWriteExporter.startIfEnabled(this.configuration);
            installSemanticMatchingIfEnabled(this.workerGroup);
            installLlmCompletionServiceIfAvailable(this.workerGroup);
            this.driftWiring = DriftWiring.start(this.configuration, this.mockServerLogger, this.workerGroup);
        } catch (Throwable throwable) {
            if (this.extensionRegistry != null) {
                this.extensionRegistry.stop();
            }
            throw throwable;
        }
    }

    /**
     * Install the opt-in semantic prompt matcher only when explicitly enabled and
     * a runtime LLM backend resolves. Off by default — the deterministic matcher
     * is never affected unless both conditions hold. Fail-soft.
     */
    private void installSemanticMatchingIfEnabled(EventLoopGroup eventLoopGroup) {
        boolean semanticMatchingEnabled = configuration != null
            ? configuration.llmSemanticMatchingEnabled()
            : org.mockserver.configuration.ConfigurationProperties.llmSemanticMatchingEnabled();
        if (!semanticMatchingEnabled) {
            return;
        }
        try {
            java.util.Optional<org.mockserver.llm.client.LlmBackend> backend =
                new org.mockserver.llm.client.LlmBackendResolver(configuration).resolveDefault();
            if (!backend.isPresent()) {
                return;
            }
            org.mockserver.httpclient.NettyHttpClient httpClient =
                new org.mockserver.httpclient.NettyHttpClient(configuration, mockServerLogger, eventLoopGroup, null, false);
            org.mockserver.llm.client.LlmCompletionService service =
                new org.mockserver.llm.client.LlmCompletionService(new org.mockserver.llm.client.NettyHttpClientLlmTransport(httpClient), configuration);
            org.mockserver.llm.semantic.SemanticMatching.install(
                new org.mockserver.llm.semantic.SemanticPromptMatcher(service, backend.get()));
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .info("semantic prompt matching enabled (backend: {})", backend.get().provider());
        } catch (Exception e) {
            // fail-soft — semantic matching stays off
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .warn("failed to enable semantic prompt matching ({}); continuing without it", e.getMessage());
        }
    }

    /**
     * Wire a shared {@link org.mockserver.llm.client.LlmCompletionService} into
     * {@link org.mockserver.mock.HttpState} so the {@code /generateExpectation}
     * endpoint can call the configured LLM backend. When no backend is available
     * the endpoint falls back to template-based stubs. Fail-soft.
     */
    private void installLlmCompletionServiceIfAvailable(EventLoopGroup eventLoopGroup) {
        try {
            java.util.Optional<org.mockserver.llm.client.LlmBackend> backend =
                new org.mockserver.llm.client.LlmBackendResolver(configuration).resolveDefault();
            if (!backend.isPresent()) {
                return;
            }
            org.mockserver.httpclient.NettyHttpClient httpClient =
                new org.mockserver.httpclient.NettyHttpClient(configuration, mockServerLogger, eventLoopGroup, null, false);
            org.mockserver.llm.client.LlmCompletionService service =
                new org.mockserver.llm.client.LlmCompletionService(new org.mockserver.llm.client.NettyHttpClientLlmTransport(httpClient), configuration);
            httpState.setLlmCompletionService(service, backend.get());
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .info("LLM completion service installed for stub generation (backend: {})", backend.get().provider());
        } catch (Exception e) {
            // fail-soft — stub generation endpoint will use template fallback
            org.slf4j.LoggerFactory.getLogger(LifeCycle.class)
                .warn("failed to install LLM completion service ({}); stub generation will use template fallback", e.getMessage());
        }
    }

    /**
     * Mark that a data-plane request has started processing. Must be paired with exactly one
     * {@link #requestProcessingComplete()} call when the response has been written.
     */
    public void requestProcessingStarted() {
        requestsInFlight.incrementAndGet();
    }

    /**
     * Mark that a data-plane request has finished (its response has been written or the connection
     * has otherwise completed). Callers must guard against double-invocation; the counter is never
     * allowed to go negative.
     */
    public void requestProcessingComplete() {
        requestsInFlight.updateAndGet(current -> current > 0 ? current - 1 : 0);
    }

    /**
     * @return the number of data-plane requests currently being processed
     */
    public int getRequestsInFlight() {
        return requestsInFlight.get();
    }

    /**
     * @return a reader of {@link #getRequestsInFlight()} that does not refer to this server, so a process-wide
     * place it is installed in does not keep a stopped server in memory
     */
    public java.util.function.IntSupplier getRequestsInFlightSupplier() {
        return requestsInFlightSupplier;
    }

    /**
     * @return how HTTP/1.1 connections count the pipelined requests they hold as in flight, so a stop waits for them
     */
    public org.mockserver.codec.HeldRequestAccounting getHeldRequestAccounting() {
        return heldRequestAccounting;
    }

    /**
     * Wait up to {@code stopDrainMillis} for in-flight requests to complete. When the timeout is 0
     * draining is disabled and this returns immediately (pre-7.2 behaviour). If the timeout elapses
     * with requests still in flight a warning is logged and shutdown proceeds anyway.
     */
    private void drainInFlightRequests() {
        long drainMillis = configuration != null
            ? configuration.stopDrainMillis()
            : org.mockserver.configuration.ConfigurationProperties.stopDrainMillis();
        if (drainMillis <= 0 || requestsInFlight.get() <= 0) {
            return;
        }
        long deadline = System.currentTimeMillis() + drainMillis;
        while (requestsInFlight.get() > 0 && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        int remaining = requestsInFlight.get();
        if (remaining > 0 && mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(WARN)
                    .setMessageFormat("graceful shutdown drain timeout of " + drainMillis + "ms elapsed with " + remaining + " request(s) still in flight, proceeding with shutdown")
            );
        }
    }

    public CompletableFuture<String> stopAsync() {
        if (!stopFuture.isDone() && stopping.compareAndSet(false, true)) {
            final String message = "stopped for port" + (getLocalPorts().size() == 1 ? ": " + getLocalPorts().get(0) : "s: " + getLocalPorts());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(INFO)
                        .setMessageFormat(message)
                );
            }
            new Scheduler.SchedulerThreadFactory("Stop").newThread(() -> {
                List<ChannelFuture> collect = serverChannelFutures
                    .stream()
                    .flatMap(channelFuture -> {
                        try {
                            return Stream.of(channelFuture.get());
                        } catch (Throwable throwable) {
                            // best-effort cleanup during shutdown - log and continue
                            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(SERVER_CONFIGURATION)
                                        .setLogLevel(DEBUG)
                                        .setMessageFormat("exception while resolving server channel during shutdown - " + throwable.getMessage())
                                        .setThrowable(throwable)
                                );
                            }
                            return Stream.empty();
                        }
                    })
                    // NOTE: disconnect() really does CLOSE a server channel, closing the listening
                    // socket so no new connections are accepted from here on. This is not obvious
                    // and has been raised twice as a suspected bug, so the reasoning is recorded:
                    //
                    //   AbstractChannelHandlerContext.disconnect(promise) begins
                    //       if (!channel().metadata().hasDisconnect()) { return close(promise); }
                    //   and NioServerSocketChannel's metadata is `new ChannelMetadata(false, 16)`,
                    //   i.e. hasDisconnect == false. So the pipeline translates disconnect into
                    //   close before it ever reaches the channel implementation.
                    //
                    // NioServerSocketChannel.doDisconnect() does throw UnsupportedOperationException,
                    // which is what makes this look broken when that class is read on its own -- but
                    // that method is unreachable through the public disconnect() API used here.
                    // Epoll arrives at the same place by a different route (doDisconnect() delegates
                    // to doClose()), so there is NO platform divergence in observable behaviour.
                    //
                    // Reading a method body proves what it does, not that it runs: the deciding
                    // logic is one layer above, in the pipeline.
                    //
                    // StopAcceptingConnectionsIntegrationTest guards this behaviour, and was
                    // validated by a positive control (replacing this with newSucceededFuture(), so
                    // the socket is never closed, correctly turns it red).
                    .map(ChannelOutboundInvoker::disconnect)
                    .collect(Collectors.toList());
                try {
                    for (ChannelFuture channelFuture : collect) {
                        channelFuture.get();
                    }
                } catch (Throwable throwable) {
                    // best-effort cleanup during shutdown - log and continue
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(SERVER_CONFIGURATION)
                                .setLogLevel(DEBUG)
                                .setMessageFormat("exception while disconnecting server channel during shutdown - " + throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                }

                // Server channels are now disconnected so no new requests are accepted; wait for any
                // requests still being processed to complete before tearing down the event loops
                // (WS7.2 graceful shutdown connection drain). Bounded by stopDrainMillis.
                drainInFlightRequests();
                drainEnded = true;

                httpState.stop();
                if (extensionRegistry != null) {
                    extensionRegistry.stop();
                }
                scheduler.shutdown();
                if (otelMetricsExporter != null) {
                    otelMetricsExporter.stop();
                }
                if (genAiSpanExporter != null) {
                    genAiSpanExporter.stop();
                }
                if (prometheusRemoteWriteExporter != null) {
                    prometheusRemoteWriteExporter.stop();
                }
                org.mockserver.llm.semantic.SemanticMatching.clear();
                driftWiring.stop();

                // The forward-client group is created lazily, so it may never have been created (a
                // pure-mock server). Read it under the same lock the accessor creates it under: this is
                // the synchronization point that prevents a first-forward racing this stop from leaking
                // an un-terminated group — if the accessor wins the lock later it sees stopping==true and
                // shuts its own group down immediately.
                final EventLoopGroup forwardGroupToStop;
                synchronized (forwardClientGroupLock) {
                    forwardGroupToStop = forwardClientGroup;
                }

                // Shut down all event loops to terminate all threads.
                //
                // Quiet period is 0 (not the Netty default of 2s, nor the 5ms once used here). Netty's
                // graceful shutdown sleeps for the whole quiet period whenever tasks are still pending
                // at shutdown (SingleThreadEventExecutor.confirmShutdown does Thread.sleep(quietPeriod)
                // in its poll loop, guarded by an early-out only when quietPeriod == 0), so ANY non-zero
                // quiet period — even 5ms — triggers a fixed ~100ms poll wait per group on this JVM.
                // Across an instance-per-test-method suite that dominated the run (~107ms per stop()).
                //
                // The quiet period is redundant here: stopAsync() has ALREADY drained in-flight data-plane
                // requests explicitly via drainInFlightRequests() (bounded by stopDrainMillis, default
                // 15s) BEFORE this point, so there is no in-flight work for the quiet period to let settle.
                // The timeout is kept non-zero so a task that is genuinely mid-execution still has a bounded
                // window to finish before the loop is forced down. This mirrors the lazily-created forward
                // group's own immediate-shutdown path above (shutdownGracefully(0, 0, ...)).
                //
                // StopDrainIntegrationTest.drainWaitsForRealInFlightRequestWithResponseDelay and
                // .largeDelayedResponseInFlightAcrossStopArrivesIntact prove an in-flight (delayed,
                // multi-write) response still completes intact with the zero quiet period.
                bossGroup.shutdownGracefully(0, 5, MILLISECONDS);
                workerGroup.shutdownGracefully(0, 5, MILLISECONDS);
                if (forwardGroupToStop != null) {
                    forwardGroupToStop.shutdownGracefully(0, 5, MILLISECONDS);
                }

                awaitEventLoopTermination(bossGroup, workerGroup, forwardGroupToStop);

                stopFuture.complete(message);
            }).start();
        }
        return stopFuture;
    }

    /**
     * Waits, at most {@link #EVENT_LOOP_TERMINATION_WAIT_MILLIS} in all, for the event loops to terminate. A loop is
     * held by a task that does not return, such as a TRACE wire-trace line written to a console nobody drains, and
     * the listening sockets are already closed, so {@code stop()} does not wait for it: the loop ends when the task
     * returns. The notice goes straight to the console, as the event log has stopped, from a daemon thread of its
     * own, so a blocked console cannot hold the caller.
     */
    private void awaitEventLoopTermination(EventLoopGroup... groups) {
        long deadline = System.nanoTime() + MILLISECONDS.toNanos(EVENT_LOOP_TERMINATION_WAIT_MILLIS);
        for (EventLoopGroup group : groups) {
            if (group != null && !group.terminationFuture().awaitUninterruptibly(Math.max(0, deadline - System.nanoTime()), NANOSECONDS)) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    MockServerLogger console = new MockServerLogger(configuration, LifeCycle.class);
                    new Scheduler.SchedulerThreadFactory("Stop-notice", true).newThread(() -> console.logEvent(
                        new LogEntry()
                            .setType(SERVER_CONFIGURATION)
                            .setLogLevel(WARN)
                            .setMessageFormat("event loop threads still running " + EVENT_LOOP_TERMINATION_WAIT_MILLIS + "ms after stop, each ends when its current task returns (a task writing to a console nobody reads does not return); the ports are already closed")
                    )).start();
                }
                return;
            }
        }
    }

    public void stop() {
        try {
            // The wait must never be shorter than the graceful-shutdown drain can legitimately take,
            // otherwise stop() returns to the caller before the server has actually shut down. The
            // drain can block up to stopDrainMillis (WS7.2), so allow that plus a buffer for the
            // event-loop teardown that follows. Falls back to a 30s floor when draining is disabled.
            stopAsync().get(stopTimeoutMillis(), MILLISECONDS);
        } catch (Throwable throwable) {
            // a stop that did not stop must not be silent. Logging this at DEBUG meant the first
            // visible symptom was an unrelated BindException later, when something tried to rebind
            // a port this server was in fact still holding.
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(WARN)
                        .setMessageFormat("exception while stopping MockServer, it may still be running and holding its port - "
                            + throwable.getMessage())
                        .setArguments(throwable)
                );
            }
        }
    }

    /**
     * How long {@link #stop()} waits: never shorter than the drain plus a buffer, plus the longest the extensions' own
     * bounded {@code stop()} calls can take.
     */
    long stopTimeoutMillis() {
        long drainMillis = configuration != null
            ? configuration.stopDrainMillis()
            : org.mockserver.configuration.ConfigurationProperties.stopDrainMillis();
        long stopTimeoutMillis = Math.max(30_000L, drainMillis + 10_000L);
        return extensionRegistry != null ? stopTimeoutMillis + extensionRegistry.maximumStopMillis() : stopTimeoutMillis;
    }

    @Override
    public void close() {
        stop();
    }

    protected EventLoopGroup getEventLoopGroup() {
        return workerGroup;
    }

    /**
     * @return the dedicated event-loop group for the outbound forward/proxy (loopback) HTTP client,
     * kept disjoint from the server worker group so a pooled channel reused inside a synchronous
     * local callback is never pinned to a blocked server worker thread (see field javadoc).
     * <p>
     * Created lazily on the first call (double-checked locking on {@link #forwardClientGroupLock}) so a
     * pure-mock server that never forwards never allocates it. The group is always a NEW group sized by
     * {@code clientNioEventLoopThreadCount} — never the {@code workerGroup} — preserving the disjoint-group
     * self-deadlock invariant documented on the field. If the server is already stopping when the first
     * forward arrives, the group is created but immediately shut down so it can never leak past
     * {@link #stopAsync()} (its {@code isShuttingDown()} then makes {@code NettyHttpClient.sendRequest()}
     * no-op cleanly); all creation and the {@code stopAsync()} teardown read/write the field under the
     * same lock, so a first-forward racing a stop can never leak an un-terminated group.
     */
    protected EventLoopGroup getForwardClientEventLoopGroup() {
        EventLoopGroup group = forwardClientGroup;
        if (group != null) {
            return group;
        }
        synchronized (forwardClientGroupLock) {
            if (forwardClientGroup == null) {
                // Always a NEW, distinct group (never workerGroup) — the disjointness invariant.
                EventLoopGroup created = NettyTransport.newEventLoopGroup(
                    configuration.clientNioEventLoopThreadCount(),
                    new Scheduler.SchedulerThreadFactory(getClass().getSimpleName() + "-forwardClientEventLoop"),
                    configuration.useNativeTransport());
                if (stopping.get()) {
                    // stopAsync()'s teardown pass for this group may already have run; shut this one
                    // down immediately so it is never left un-terminated. No thread has started yet
                    // (no task submitted), so this terminates promptly.
                    created.shutdownGracefully(0, 0, MILLISECONDS);
                }
                forwardClientGroup = created;
            }
            return forwardClientGroup;
        }
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    /**
     * @return the extensions this server loaded, or {@code null} when none are configured
     */
    public ExtensionRegistry getExtensionRegistry() {
        return extensionRegistry;
    }

    /**
     * Shared by every TCP and HTTP/3 port of this server, so a client's failed handshake is logged at {@code WARN}
     * once per client address and transport.
     */
    public ClientTlsHandshakeFailureLog getClientTlsHandshakeFailureLog() {
        return clientTlsHandshakeFailureLog;
    }

    /**
     * The single live {@link Configuration} this server runs on: the instance shared by the
     * {@link HttpState}, the event log and the request handlers, and the one that
     * {@code PUT /mockserver/configuration} mutates. When the server was constructed without a
     * configuration this is the default instance built at construction (by the
     * {@code MockServer} constructor, or here when {@code null} is passed), never a second copy.
     */
    public Configuration getConfiguration() {
        return configuration;
    }

    public boolean isRunning() {
        return !bossGroup.isShuttingDown() || !workerGroup.isShuttingDown();
    }

    public List<Integer> getLocalPorts() {
        return getBoundPorts(serverChannelFutures);
    }

    /**
     * @deprecated use getLocalPort instead of getPort
     */
    @Deprecated
    public Integer getPort() {
        return getLocalPort();
    }

    public int getLocalPort() {
        InetSocketAddress bound = getFirstBoundAddress(serverChannelFutures);
        return bound != null ? bound.getPort() : -1;
    }

    /**
     * @return the address the first port is bound to: a wildcard address unless {@code localBoundIP} is set, or null
     * if no port is bound
     */
    public InetAddress getLocalBoundAddress() {
        InetSocketAddress bound = getFirstBoundAddress(serverChannelFutures);
        return bound != null ? bound.getAddress() : null;
    }

    private InetSocketAddress getFirstBoundAddress(List<Future<Channel>> channelFutures) {
        for (Future<Channel> channelOpened : channelFutures) {
            try {
                SocketAddress localAddress = channelOpened.get(15, SECONDS).localAddress();
                if (localAddress instanceof InetSocketAddress) {
                    return (InetSocketAddress) localAddress;
                }
            } catch (Throwable throwable) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(WARN)
                            .setMessageFormat("exception while retrieving port from channel future, ignoring port for this channel - " + throwable.getMessage())
                            .setArguments(throwable)
                    );
                }
            }
        }
        return null;
    }

    private List<Integer> getBoundPorts(List<Future<Channel>> channelFutures) {
        List<Integer> ports = new ArrayList<>();
        for (Future<Channel> channelOpened : channelFutures) {
            try {
                ports.add(((InetSocketAddress) channelOpened.get(3, SECONDS).localAddress()).getPort());
            } catch (Exception e) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(DEBUG)
                            .setMessageFormat("exception while retrieving port from channel future, ignoring port for this channel")
                            .setArguments(e)
                    );
                }
            }
        }
        return ports;
    }

    public List<Integer> bindServerPorts(final List<Integer> requestedPortBindings) {
        return bindPorts(serverServerBootstrap, requestedPortBindings, serverChannelFutures);
    }

    private List<Integer> bindPorts(final ServerBootstrap serverBootstrap, List<Integer> requestedPortBindings, List<Future<Channel>> channelFutures) {
        List<Integer> actualPortBindings = new ArrayList<>();
        final String localBoundIP = configuration.localBoundIP();
        for (final Integer portToBind : requestedPortBindings) {
            try {
                actualPortBindings.add(bindPort(serverBootstrap, localBoundIP, portToBind, channelFutures));
            } catch (Exception e) {
                if (e instanceof InterruptedException) {
                    // this thread's own wait was interrupted (a failure elsewhere arrives as an ExecutionException)
                    Thread.currentThread().interrupt();
                }
                throw new RuntimeException("Exception while binding MockServer to port " + portToBind, e instanceof ExecutionException ? e.getCause() : e);
            }
        }
        return actualPortBindings;
    }

    /**
     * Binds one port and checks that connections to localhost on that port reach this server rather than
     * another application listening on the loopback address (see {@link LoopbackShadowProbe}). An
     * operating-system-chosen port that turns out to be shadowed is released and another one chosen; a
     * shadowed explicit port fails like any other port conflict.
     */
    private int bindPort(final ServerBootstrap serverBootstrap, final String localBoundIP, final Integer portToBind, List<Future<Channel>> channelFutures) throws Exception {
        final boolean operatingSystemChoosesPort = portToBind == null || portToBind == 0;
        Integer nextPort = portToBind;
        for (int attempt = 1; ; attempt++) {
            final Integer port = nextPort;
            final CompletableFuture<Channel> channelOpened = new CompletableFuture<>();
            channelFutures.add(channelOpened);
            new Scheduler.SchedulerThreadFactory("MockServer thread for port: " + portToBind, false).newThread(() -> {
                try {
                    InetSocketAddress inetSocketAddress;
                    if (isBlank(localBoundIP)) {
                        inetSocketAddress = new InetSocketAddress(port);
                    } else {
                        inetSocketAddress = new InetSocketAddress(localBoundIP, port);
                    }
                    serverBootstrap
                        .bind(inetSocketAddress)
                        .addListener((ChannelFutureListener) future -> {
                            if (future.isSuccess()) {
                                channelOpened.complete(future.channel());
                            } else {
                                channelOpened.completeExceptionally(future.cause());
                            }
                        })
                        .channel().closeFuture().syncUninterruptibly();

                } catch (Exception e) {
                    channelOpened.completeExceptionally(new RuntimeException("Exception while binding MockServer to port " + port, e));
                }
            }).start();

            Channel channel;
            try {
                channel = channelOpened.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
            } catch (ExecutionException bindFailed) {
                // a retry's candidate port can be taken between choosing and binding it
                if (!operatingSystemChoosesPort || attempt == 1 || attempt >= SHADOW_PROBE_MAX_EPHEMERAL_ATTEMPTS) {
                    throw bindFailed;
                }
                channelFutures.remove(channelOpened);
                nextPort = bindVerifier.nextCandidatePort();
                continue;
            }
            int boundPort = ((InetSocketAddress) channel.localAddress()).getPort();
            long acceptTimeoutMillis = operatingSystemChoosesPort
                ? Math.min(SHADOW_PROBE_ACCEPT_TIMEOUT_EXPLICIT_MILLIS, SHADOW_PROBE_ACCEPT_TIMEOUT_EPHEMERAL_INITIAL_MILLIS << (attempt - 1))
                : SHADOW_PROBE_ACCEPT_TIMEOUT_EXPLICIT_MILLIS;
            InetSocketAddress shadowed;
            try {
                shadowed = bindVerifier.findShadowedLoopback(channel, acceptTimeoutMillis);
            } catch (InterruptedException interrupted) {
                channelFutures.remove(channelOpened);
                channel.close();
                Thread.currentThread().interrupt();
                throw interrupted;
            } catch (RuntimeException checkFailed) {
                channelFutures.remove(channelOpened);
                channel.close().awaitUninterruptibly(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                throw checkFailed;
            }
            if (shadowed == null) {
                return boundPort;
            }
            channelFutures.remove(channelOpened);
            channel.close().awaitUninterruptibly(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
            String conflict = loopbackConflict(boundPort, shadowed);
            if (!operatingSystemChoosesPort) {
                throw explicitPortConflict(boundPort, shadowed);
            }
            if (attempt >= SHADOW_PROBE_MAX_EPHEMERAL_ATTEMPTS) {
                throw new BindException("no usable free port found after " + attempt + " attempts, the last one because " + conflict);
            }
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
                final String message = conflict + "; binding a different port";
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(INFO)
                        .setMessageFormat(message)
                );
            }
            nextPort = bindVerifier.nextCandidatePort();
        }
    }

    /**
     * The exception thrown when an explicitly requested port is shadowed by another application's loopback
     * listener; public so callers that surface it (such as {@code PUT /mockserver/bind}) can be tested
     * against the real wording.
     */
    public static BindException explicitPortConflict(int port, InetSocketAddress shadowed) {
        return new BindException(loopbackConflict(port, shadowed) + "; stop that application or choose a different port (to find it run: lsof -nP -iTCP:" + port + " -sTCP:LISTEN)");
    }

    private static String loopbackConflict(int port, InetSocketAddress shadowed) {
        return "port " + port + " is already in use by another application listening on " + NetUtil.toSocketAddressString(new InetSocketAddress(shadowed.getAddress(), port))
            + ", so requests to localhost:" + port + " would reach that application instead of MockServer";
    }

    protected void startedServer(List<Integer> ports) {
        final String message = "started on port" + (ports.size() == 1 ? ": " + ports.get(0) : "s: " + ports);
        if (extensionRegistry != null) {
            try {
                extensionRegistry.notifyStarted(ports);
            } catch (RuntimeException notifyFailed) {
                boolean interrupted = Thread.interrupted();
                stop();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw notifyFailed;
            }
        }
        setPort(ports);
        logUpcomingDefaultChanges();
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(SERVER_CONFIGURATION)
                    .setLogLevel(INFO)
                    .setMessageFormat(message)
            );
        }
        logProxySetup(ports);
        startupWarmup(ports);
    }

    /**
     * Warns, once per JVM, about each insecure default left in place that a future major release changes. Fail-soft.
     */
    private void logUpcomingDefaultChanges() {
        try {
            if (configuration == null || mockServerLogger == null || !mockServerLogger.isEnabledForInstance(WARN)
                || !UpcomingDefaultChanges.claimStartupWarning()) {
                return;
            }
            for (String message : UpcomingDefaultChanges.warnings(configuration)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(WARN)
                        .setMessageFormat(message)
                );
            }
        } catch (Throwable throwable) {
            // a start-up notice must never prevent the server from starting
        }
    }

    /**
     * Pay the one-off "first request" cost in the background so the first real request a caller (or a
     * readiness poll such as Testcontainers) makes is fast.
     * <p>
     * The very first request handled by a freshly started server is a few hundred milliseconds slower
     * than every subsequent one because the request-handling path (Netty HTTP codec, Jackson
     * serialisation, response writers) is only loaded/initialised lazily on first use. We exercise that
     * path once, off the start-up thread, by sending a single plain-HTTP {@code PUT /mockserver/status}
     * to the first bound port over loopback. {@code /status} is a control-plane endpoint that answers
     * without touching the mock-matching or recorded-request paths, so it does NOT create any log
     * events, recorded requests, or verification-visible state.
     * <p>
     * Fail-soft by design: this must never delay port binding (it runs on a daemon thread started after
     * bind), never throw, and never leak a hanging thread (short connect/read timeouts). Any failure is
     * swallowed at TRACE — warm-up is a pure latency optimisation, so a failure to warm up is not an
     * error. Gated by {@code startupWarmup} (default true).
     */
    private void startupWarmup(List<Integer> ports) {
        if (configuration == null || !Boolean.TRUE.equals(configuration.startupWarmup())) {
            return;
        }
        if (ports == null || ports.isEmpty() || ports.get(0) == null || ports.get(0) <= 0) {
            return;
        }
        final int port = ports.get(0);
        Thread warmupThread = new Thread(() -> {
            java.net.HttpURLConnection connection = null;
            try {
                // Plain HTTP against the first bound port — MockServer uses unified protocol detection,
                // so /status answers over plain HTTP on any port (including TLS-capable ports). If that
                // assumption ever fails to hold in a given environment the fail-soft catch below covers
                // it: the only cost is that this instance is not warmed up.
                java.net.URL url = new java.net.URL("http", "127.0.0.1", port, "/mockserver/status");
                connection = (java.net.HttpURLConnection) url.openConnection();
                connection.setRequestMethod("PUT");
                connection.setConnectTimeout(2000);
                connection.setReadTimeout(2000);
                connection.setDoOutput(false);
                connection.connect();
                // Read and discard the response body to exercise the full write path and free the socket.
                try (java.io.InputStream inputStream = connection.getResponseCode() < 400
                    ? connection.getInputStream()
                    : connection.getErrorStream()) {
                    if (inputStream != null) {
                        byte[] buffer = new byte[4096];
                        while (inputStream.read(buffer) != -1) {
                            // drain
                        }
                    }
                }
            } catch (Throwable throwable) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(SERVER_CONFIGURATION)
                            .setLogLevel(TRACE)
                            .setMessageFormat("exception during start-up warm-up request (ignored):{}")
                            .setThrowable(throwable)
                    );
                }
            } finally {
                if (connection != null) {
                    connection.disconnect();
                }
            }
        }, "MockServer-startup-warmup");
        warmupThread.setDaemon(true);
        warmupThread.start();
    }

    /**
     * When proxySetupLogging is enabled, materialise the active Certificate Authority certificate to a
     * stable file and print an OS-specific copy-paste block describing how to route TLS traffic through
     * MockServer and trust its CA. When proxySetupLogging is disabled (the default — e.g. embedded
     * {@code ClientAndServer} usage) nothing is written or logged. When the public baked-in CA is in
     * effect the block is logged at WARN with a security warning; with a unique/custom CA it is logged at
     * INFO. Fail-soft: any error here never prevents the server from starting.
     */
    private void logProxySetup(List<Integer> ports) {
        try {
            if (configuration == null || !Boolean.TRUE.equals(configuration.proxySetupLogging())) {
                return;
            }
            org.mockserver.socket.tls.KeyAndCertificateFactory keyAndCertificateFactory =
                org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory(configuration, mockServerLogger);
            String caCertificatePath = keyAndCertificateFactory.writeCertificateAuthorityToDisk();
            org.mockserver.socket.tls.ProxySetupInfo proxySetupInfo =
                new org.mockserver.socket.tls.ProxySetupInfo(caCertificatePath, ports, configuration, System.getProperty("os.name"));
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(proxySetupInfo.usingDefaultCa() ? WARN : INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(proxySetupInfo.usingDefaultCa() ? WARN : INFO)
                        .setMessageFormat(proxySetupInfo.copyPasteText())
                );
            }
        } catch (Throwable throwable) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(DEBUG)
                        .setMessageFormat("exception while preparing proxy setup information:{}")
                        .setThrowable(throwable)
                );
            }
        }
    }

    public LifeCycle registerListener(ExpectationsListener expectationsListener) {
        httpState.getRequestMatchers().registerListener((requestMatchers, cause) -> {
            if (cause == MockServerMatcherNotifier.Cause.API) {
                expectationsListener.updated(requestMatchers.retrieveActiveExpectations(null));
            }
        });
        return this;
    }

}

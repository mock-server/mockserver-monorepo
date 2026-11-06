package org.mockserver.httpclient;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.AttributeKey;
import io.netty.util.NetUtil;
import org.mockserver.configuration.Configuration;
import org.mockserver.filters.HopByHopHeaderFilter;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.logging.RawNetworkTrafficLog;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.*;
import org.mockserver.proxyconfiguration.ForwardTargetBlockedException;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.mockserver.proxyconfiguration.NoProxyHostsUtils;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.NettyAllocator;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.SocketAddresses;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.boundedFaultDescription;
import static org.mockserver.model.HttpResponse.response;

public class NettyHttpClient {

    static final AttributeKey<Boolean> SECURE = AttributeKey.valueOf("SECURE");
    static final AttributeKey<InetSocketAddress> REMOTE_SOCKET = AttributeKey.valueOf("REMOTE_SOCKET");
    static final AttributeKey<CompletableFuture<Message>> RESPONSE_FUTURE = AttributeKey.valueOf("RESPONSE_FUTURE");
    static final AttributeKey<Boolean> ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE = AttributeKey.valueOf("ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE");
    static final AttributeKey<Boolean> DISABLE_RESPONSE_STREAMING = AttributeKey.valueOf("DISABLE_RESPONSE_STREAMING");
    // Set when the OUTGOING request asks for a streamed response, so the response is relayed
    // incrementally even if the upstream omits Content-Type: text/event-stream. Read by
    // StreamingAwareHttpObjectAggregator (same interned key name).
    static final AttributeKey<Boolean> EXPECT_STREAMING_RESPONSE = AttributeKey.valueOf("EXPECT_STREAMING_RESPONSE");
    // A JSON request body that turns on streaming, e.g. {"stream": true} (OpenAI/Anthropic/Codex).
    private static final java.util.regex.Pattern STREAM_TRUE_IN_BODY =
        java.util.regex.Pattern.compile("\"stream\"\\s*:\\s*true");
    static final AttributeKey<AtomicLong> FIRST_BYTE_MILLIS = TimeToFirstByteHandler.FIRST_BYTE_MILLIS;
    /**
     * When set on a channel, {@link HttpClientHandler} returns the channel to this pool (keyed by
     * {@link #POOL_KEY}) after a reusable HTTP/1.1 keep-alive response instead of closing it.
     */
    static final AttributeKey<HttpForwardConnectionPool> CONNECTION_POOL = AttributeKey.valueOf("CONNECTION_POOL");
    static final AttributeKey<String> POOL_KEY = AttributeKey.valueOf("POOL_KEY");
    static final AttributeKey<io.netty.util.concurrent.ScheduledFuture<?>> POOL_IDLE_EVICTION = AttributeKey.valueOf("POOL_IDLE_EVICTION");
    /**
     * Set on an HTTP/2 forward STREAM (child) channel when its response has released the PARENT
     * connection back to the pool for reuse. The child's close listener
     * ({@link Http2ForwardStreamChildInitializer}) reads it and, when set, leaves the parent open (the
     * pool owns its lifecycle); when unset it keeps the historical "close the parent after one stream".
     */
    static final AttributeKey<Boolean> POOL_KEEP_PARENT = AttributeKey.valueOf("POOL_KEEP_PARENT");
    /** The name of the tunnel's handler in a binary relay's upstream pipeline, when it has one. */
    public static final String BINARY_RELAY_TUNNEL = "binary-relay-tunnel";
    private static final HopByHopHeaderFilter hopByHopHeaderFilter = new HopByHopHeaderFilter();
    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    // Supplies the outbound event-loop group lazily. For the forward/proxy client this is the
    // LifeCycle accessor (a method reference to getForwardClientEventLoopGroup()), so the disjoint
    // forward-client group is only created on the FIRST forward/proxy request — a pure-mock
    // deployment that never forwards never pays for it. May be null for callers (chiefly tests) that
    // never forward; eventLoopGroup() then returns null, exactly matching the historical behaviour.
    private final Supplier<EventLoopGroup> eventLoopGroupSupplier;
    private final Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations;
    private final boolean forwardProxyClient;
    private final NettySslContextFactory nettySslContextFactory;
    private final HttpForwardConnectionPool connectionPool;
    // null unless logRawNetworkTraffic was on when this client was built
    private final RawNetworkTrafficLog rawNetworkTrafficLog;
    // upstream proxies already reported as unreachable, so each is logged once
    private final Set<InetSocketAddress> unreachableProxiesReported = ConcurrentHashMap.newKeySet();

    public NettyHttpClient(Configuration configuration, MockServerLogger mockServerLogger, EventLoopGroup eventLoopGroup, List<ProxyConfiguration> proxyConfigurations, boolean forwardProxyClient) {
        this(configuration, mockServerLogger, eventLoopGroup, proxyConfigurations, forwardProxyClient, new NettySslContextFactory(configuration, mockServerLogger, false));
    }

    public NettyHttpClient(Configuration configuration, MockServerLogger mockServerLogger, EventLoopGroup eventLoopGroup, List<ProxyConfiguration> proxyConfigurations, boolean forwardProxyClient, NettySslContextFactory nettySslContextFactory) {
        // Wrap a concrete group as a constant supplier so all call paths share the lazy-resolution
        // seam; a null group stays null (eventLoopGroup() returns null) preserving prior behaviour.
        this(configuration, mockServerLogger, eventLoopGroup == null ? (Supplier<EventLoopGroup>) null : () -> eventLoopGroup, proxyConfigurations, forwardProxyClient, nettySslContextFactory);
    }

    /**
     * Supplier-based constructor: the outbound event-loop group is resolved lazily on first use
     * ({@link #eventLoopGroup()}) rather than captured eagerly at construction. The forward/proxy
     * client passes the {@code LifeCycle} accessor here so the disjoint forward-client group is only
     * created the first time MockServer actually forwards/proxies.
     */
    public NettyHttpClient(Configuration configuration, MockServerLogger mockServerLogger, Supplier<EventLoopGroup> eventLoopGroupSupplier, List<ProxyConfiguration> proxyConfigurations, boolean forwardProxyClient, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.eventLoopGroupSupplier = eventLoopGroupSupplier;
        this.proxyConfigurations = proxyConfigurations != null ? proxyConfigurations.stream().collect(Collectors.toMap(ProxyConfiguration::getType, proxyConfiguration -> proxyConfiguration)) : ImmutableMap.of();
        this.forwardProxyClient = forwardProxyClient;
        this.nettySslContextFactory = nettySslContextFactory;
        this.rawNetworkTrafficLog = RawNetworkTrafficLog.forConfiguration(configuration, RawNetworkTrafficLog.FORWARD_SIDE);
        this.connectionPool = Boolean.TRUE.equals(configuration.forwardConnectionPoolEnabled())
            ? new HttpForwardConnectionPool(
                configuration.forwardConnectionPoolMaxIdlePerKey(),
                configuration.forwardConnectionPoolIdleTimeoutMillis(),
                Boolean.TRUE.equals(configuration.forwardConnectionPoolKeepAlive()),
                configuration.forwardConnectionPoolMaxTotalPerKey())
            : null;
    }

    /**
     * Resolves the outbound event-loop group, triggering its (lazy) creation on the FIRST call for a
     * forward/proxy client. Returns {@code null} when no supplier was provided (callers that never
     * forward), matching the historical behaviour where the group field could be {@code null}. The
     * {@code LifeCycle} accessor memoises the group, so repeated calls return the same instance.
     */
    private EventLoopGroup eventLoopGroup() {
        return eventLoopGroupSupplier == null ? null : eventLoopGroupSupplier.get();
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest) throws SocketConnectionException {
        return sendRequest(httpRequest, null);
    }

    /**
     * Whether the OUTGOING request asks for a streamed (Server-Sent Events style) response, so
     * the upstream response should be relayed to the proxy client incrementally even if it omits
     * {@code Content-Type: text/event-stream}. Some streaming backends do — notably the OpenAI
     * Codex backend used by the opencode CLI ({@code chatgpt.com/backend-api/codex/responses}),
     * whose SSE response carries no content-type at all; without this hint MockServer would
     * aggregate the whole 10–30s stream and the client would time out waiting for response headers.
     * Detected from the client's own intent: an {@code Accept: text/event-stream} header, a JSON
     * request body containing {@code "stream": true}, or a Gemini {@code :streamGenerateContent} path.
     */
    static boolean requestExpectsStreamingResponse(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String accept = request.getFirstHeader("accept");
        if (accept != null && accept.toLowerCase().contains("text/event-stream")) {
            return true;
        }
        if (request.getPath() != null && org.mockserver.codec.StreamingAwareHttpObjectAggregator.isStreamingRequestPath(request.getPath().getValue())) {
            return true;
        }
        String contentType = request.getFirstHeader("content-type");
        if (contentType != null && contentType.toLowerCase().contains("json")) {
            String body = request.getBodyAsString();
            if (body != null && !body.isEmpty() && STREAM_TRUE_IN_BODY.matcher(body).find()) {
                return true;
            }
        }
        return false;
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress) throws SocketConnectionException {
        return sendRequest(httpRequest, remoteAddress, configuration.socketConnectionTimeoutInMillis());
    }

    /**
     * Whether {@link #sendRequest(HttpRequest, InetSocketAddress)} sends the request to {@code forwardHttpProxy}, which
     * is then sent the destination as an absolute URI made from the request's Host header.
     */
    public boolean sendsThroughHttpProxy(HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress) {
        return !Boolean.TRUE.equals(httpRequest.isSecure())
            && upstreamProxiesFor(httpRequest, remoteAddress).containsKey(ProxyConfiguration.Type.HTTP);
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress, Long connectionTimeoutMillis) throws SocketConnectionException {
        return sendRequest(httpRequest, remoteAddress, connectionTimeoutMillis, false);
    }

    public CompletableFuture<HttpResponse> sendRequest(final HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress, Long connectionTimeoutMillis, boolean disableStreaming) throws SocketConnectionException {
        // Resolve (lazily creating on first forward) once per request so the whole request uses one group.
        final EventLoopGroup eventLoopGroup = eventLoopGroup();
        if (!eventLoopGroup.isShuttingDown()) {
            final boolean secure = Boolean.TRUE.equals(httpRequest.isSecure());
            final Map<ProxyConfiguration.Type, ProxyConfiguration> upstreamProxies = upstreamProxiesFor(httpRequest, remoteAddress);
            // where the connection is made; differs from remoteAddress only when a direct forward is checked
            InetSocketAddress connectAddress;
            if (!secure && upstreamProxies.containsKey(ProxyConfiguration.Type.HTTP)) {
                ProxyConfiguration proxyConfiguration = upstreamProxies.get(ProxyConfiguration.Type.HTTP);
                // a proxy given by name is looked up by the bootstrap's resolver for each connection
                remoteAddress = proxyConfiguration.getProxyAddress();
                proxyConfiguration.addProxyAuthenticationHeader(httpRequest);
                connectAddress = remoteAddress;
            } else if (HttpClientInitializer.tunnelProxy(upstreamProxies, secure) != null) {
                remoteAddress = remoteAddress == null ? httpRequest.unresolvedSocketAddressFromHostHeader() : unresolvedUnlessIpLiteral(remoteAddress);
                connectAddress = remoteAddress;
            } else {
                if (remoteAddress == null) {
                    remoteAddress = httpRequest.socketAddressFromHostHeader();
                }
                try {
                    // forwardProxyBlockPrivateNetworks and the host lists: the address checked is the address connected
                    // to, so a name whose DNS answer changes after the caller's check cannot reach a refused address
                    connectAddress = forwardProxyClient ? InetAddressValidator.validateForwardTarget(configuration, remoteAddress) : remoteAddress;
                } catch (ForwardTargetBlockedException blocked) {
                    CompletableFuture<HttpResponse> refused = new CompletableFuture<>();
                    refused.completeExceptionally(blocked);
                    return refused;
                }
            }
            if (Protocol.HTTP_3.equals(httpRequest.getProtocol())) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("HTTP3 (QUIC) cannot be forwarded over a TCP connection so protocol will be negotiated by ALPN (HTTP1 or HTTP2)")
                    );
                }
                httpRequest.withProtocol(null);
            }
            if (Protocol.HTTP_2.equals(httpRequest.getProtocol()) && !Boolean.TRUE.equals(httpRequest.isSecure())) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("HTTP2 requires ALPN but request is not secure (i.e. TLS) so protocol changed to HTTP1")
                    );
                }
                httpRequest.withProtocol(Protocol.HTTP_1_1);
            }

            final CompletableFuture<HttpResponse> httpResponseFuture = new CompletableFuture<>();
            final CompletableFuture<Message> responseFuture = new CompletableFuture<>();
            final Protocol httpProtocol = httpRequest.getProtocol() != null ? httpRequest.getProtocol() : Protocol.HTTP_1_1;

            final long requestStartedMillis = System.currentTimeMillis();
            final AtomicLong connectionEstablishedMillis = new AtomicLong();
            final AtomicLong firstByteMillis = new AtomicLong();

            // Relay the response as a stream (not aggregated) when the client asked for one, so a
            // streaming upstream that omits Content-Type: text/event-stream (e.g. opencode's Codex
            // backend) is not buffered to completion before its headers reach the client.
            final boolean expectStreaming = !disableStreaming && requestExpectsStreamingResponse(httpRequest);
            final InetSocketAddress effectiveRemoteAddress = remoteAddress;
            final InetSocketAddress effectiveConnectAddress = connectAddress;
            // HTTP/1.1 keep-alive connections and HTTP/2 parent connections are pooled and reused
            // (a new stream per request for HTTP/2), keyed by host/port/secure/protocol so the two
            // never mix. HTTP/3, binary forwarding and any connection through an upstream proxy bypass the pool.
            // Streaming responses are excluded automatically because the streaming relay handler removes
            // HttpClientHandler before any pooling return path runs.
            final boolean poolable = connectionPool != null
                && (Protocol.HTTP_1_1.equals(httpProtocol) || Protocol.HTTP_2.equals(httpProtocol))
                && upstreamProxies.isEmpty();
            final String poolKey = poolable ? HttpForwardConnectionPool.keyFor(effectiveRemoteAddress, secure, httpProtocol) : null;

            Channel pooledChannel = poolKey != null ? connectionPool.acquire(poolKey) : null;
            if (pooledChannel != null) {
                // Reuse an idle keep-alive connection: re-arm per-request channel attributes and
                // dispatch directly on the channel's event loop (pipeline is already configured).
                connectionEstablishedMillis.set(requestStartedMillis);
                final Channel reused = pooledChannel;
                // Dispatch the reused connection's request on a private future so a pre-response failure
                // (the upstream closed / sent GOAWAY on the pooled connection in the window after acquire's
                // isActive() check) can be retried ONCE on a fresh connection for an idempotent request,
                // rather than surfacing as a spurious forward error. A non-idempotent request keeps the
                // historical "fail, let the caller retry" contract so it is never silently double-sent.
                final CompletableFuture<Message> reuseAttemptFuture = new CompletableFuture<>();
                reuseAttemptFuture.whenComplete((reuseMessage, reuseThrowable) -> {
                    if (reuseThrowable == null) {
                        responseFuture.complete(reuseMessage);
                    } else if (firstByteMillis.get() == 0 && isRetryableReusedConnectionFailure(reuseThrowable) && isIdempotent(httpRequest)) {
                        connectFresh(httpRequest, effectiveRemoteAddress, effectiveConnectAddress, upstreamProxies, connectionTimeoutMillis, disableStreaming, secure, httpProtocol, poolKey, responseFuture, firstByteMillis, connectionEstablishedMillis, httpResponseFuture);
                    } else if (firstByteMillis.get() == 0 && closedWithoutAnswer(reuseThrowable)) {
                        responseFuture.completeExceptionally(new PooledConnectionClosedException(httpRequest.getMethod("GET"), reuseThrowable));
                    } else {
                        responseFuture.completeExceptionally(reuseThrowable);
                    }
                });
                reused.attr(RESPONSE_FUTURE).set(reuseAttemptFuture);
                reused.attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE).set(true);
                reused.attr(FIRST_BYTE_MILLIS).set(firstByteMillis);
                reused.attr(DISABLE_RESPONSE_STREAMING).set(disableStreaming ? Boolean.TRUE : null);
                reused.attr(EXPECT_STREAMING_RESPONSE).set(expectStreaming ? Boolean.TRUE : null);
                reused.eventLoop().execute(() -> {
                    // The upstream may have closed the pooled connection before this task runs; that close
                    // already resolved reuseAttemptFuture (and retried above), so do not dispatch again.
                    if (reuseAttemptFuture.isDone()) {
                        return;
                    }
                    if (reused.isActive()) {
                        // Guard this in-flight request: a reused idle connection whose upstream has
                        // silently gone away would otherwise hang with no read timeout (pooled channels
                        // carry none while idle). Removed again on return to the pool.
                        armPooledInFlightReadTimeout(reused);
                        reused.writeAndFlush(httpRequest).addListener((ChannelFutureListener) writeFuture -> {
                            if (!writeFuture.isSuccess()) {
                                reuseAttemptFuture.completeExceptionally(writeFuture.cause());
                                reused.close();
                            }
                        });
                    } else {
                        // Raced with a server-side close between acquire and dispatch, before any request
                        // byte was written — fall back to a fresh connection for ANY method (nothing was
                        // sent, so this cannot double-send). Detach the reused channel's future first so its
                        // close does not also trigger the idempotent retry above. Safe from the event loop:
                        // bootstrap.connect() is non-blocking.
                        reused.attr(RESPONSE_FUTURE).set(null);
                        reused.close();
                        connectFresh(httpRequest, effectiveRemoteAddress, effectiveConnectAddress, upstreamProxies, connectionTimeoutMillis, disableStreaming, secure, httpProtocol, poolKey, responseFuture, firstByteMillis, connectionEstablishedMillis, httpResponseFuture);
                    }
                });
            } else {
                connectFresh(httpRequest, remoteAddress, connectAddress, upstreamProxies, connectionTimeoutMillis, disableStreaming, secure, httpProtocol, poolKey, responseFuture, firstByteMillis, connectionEstablishedMillis, httpResponseFuture);
            }

            responseFuture
                .whenComplete((message, throwable) -> {
                    if (throwable == null) {
                        long responseReceivedMillis = System.currentTimeMillis();
                        long firstByte = firstByteMillis.get();
                        long totalTime = responseReceivedMillis - requestStartedMillis;
                        Timing timing = Timing.timing()
                            .withRequestStartedMillis(requestStartedMillis)
                            .withConnectionEstablishedMillis(connectionEstablishedMillis.get())
                            .withResponseReceivedMillis(responseReceivedMillis)
                            .withConnectionTimeInMillis(connectionEstablishedMillis.get() - requestStartedMillis)
                            .withTimeToFirstByteInMillis(firstByte > 0 ? firstByte - requestStartedMillis : null)
                            .withTotalTimeInMillis(totalTime);
                        // Slow-request flagging
                        long threshold = configuration.slowRequestThresholdMillis();
                        if (threshold > 0 && totalTime > threshold) {
                            Metrics.incrementSlowRequestTotal();
                            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(Level.WARN)
                                        .setMessageFormat("slow forwarded request {} took {}ms (threshold {}ms)")
                                        .setArguments(
                                            httpRequest.getMethod("") + " " + httpRequest.getPath(),
                                            totalTime,
                                            threshold
                                        )
                                );
                            }
                        }
                        if (message != null) {
                            HttpResponse response = (HttpResponse) message;
                            response.withTiming(timing);
                            if (forwardProxyClient) {
                                httpResponseFuture.complete(hopByHopHeaderFilter.onResponse(response));
                            } else {
                                httpResponseFuture.complete(response);
                            }
                        } else {
                            httpResponseFuture.complete(response().withTiming(timing));
                        }
                    } else {
                        httpResponseFuture.completeExceptionally(throwable);
                    }
                });

            return httpResponseFuture;
        } else {
            throw new IllegalStateException("Request sent after client has been stopped - the event loop has been shutdown so it is not possible to send a request");
        }
    }

    /**
     * Opens a fresh upstream connection and dispatches the request. When {@code poolKey} is non-null
     * the channel is marked (via {@link #CONNECTION_POOL}/{@link #POOL_KEY}) so that, after a
     * reusable HTTP/1.1 keep-alive response, {@link HttpClientHandler} returns it to the pool instead
     * of closing it. This is the only connection path when pooling is disabled, so that path remains
     * byte-identical to the historical behaviour.
     */
    private void connectFresh(HttpRequest httpRequest, InetSocketAddress remoteAddress, InetSocketAddress connectAddress, Map<ProxyConfiguration.Type, ProxyConfiguration> upstreamProxies, Long connectionTimeoutMillis, boolean disableStreaming, boolean secure, Protocol httpProtocol, String poolKey, CompletableFuture<Message> responseFuture, AtomicLong firstByteMillis, AtomicLong connectionEstablishedMillis, CompletableFuture<HttpResponse> httpResponseFuture) {
        final HttpClientInitializer clientInitializer = new HttpClientInitializer(upstreamProxies, mockServerLogger, forwardProxyClient, nettySslContextFactory, httpProtocol, configuration, rawNetworkTrafficLog);
        final EventLoopGroup eventLoopGroup = eventLoopGroup();
        // What the channel's handlers complete. It only becomes the request's outcome once the connection
        // is established: a channel that failed to connect is torn down too, and that teardown must not
        // be reported in place of the reason the connection failed.
        final CompletableFuture<Message> channelResponseFuture = new CompletableFuture<>();
        Bootstrap bootstrap = new Bootstrap()
            .group(eventLoopGroup)
            .channel(NettyTransport.socketChannelClassFor(eventLoopGroup))
            .option(ChannelOption.AUTO_READ, true)
            .option(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMillis != null ? (int) Math.min(connectionTimeoutMillis, Integer.MAX_VALUE) : null)
            .attr(SECURE, secure)
            .attr(REMOTE_SOCKET, remoteAddress)
            .attr(RESPONSE_FUTURE, channelResponseFuture)
            .attr(ERROR_IF_CHANNEL_CLOSED_WITHOUT_RESPONSE, true)
            .attr(FIRST_BYTE_MILLIS, firstByteMillis)
            .handler(clientInitializer);
        applyForwardSocketKeepAlive(bootstrap);
        resolveAtTunnelProxy(bootstrap, upstreamProxies, secure);
        if (disableStreaming) {
            bootstrap.attr(DISABLE_RESPONSE_STREAMING, true);
        }
        if (!disableStreaming && requestExpectsStreamingResponse(httpRequest)) {
            bootstrap.attr(EXPECT_STREAMING_RESPONSE, true);
        }
        if (poolKey != null) {
            // Mark the fresh channel so HttpClientHandler returns it to the pool after a reusable
            // keep-alive response instead of closing it.
            bootstrap.attr(CONNECTION_POOL, connectionPool);
            bootstrap.attr(POOL_KEY, poolKey);
        }
        bootstrap.connect(connectAddress)
            .addListener((ChannelFutureListener) future -> {
                if (future.isSuccess()) {
                    connectionEstablishedMillis.set(System.currentTimeMillis());
                    relay(channelResponseFuture, responseFuture);
                    clientInitializer.whenComplete((protocol, throwable) -> {
                        if (throwable != null) {
                            httpResponseFuture.completeExceptionally(throwable);
                        } else {
                            // A fresh POOLED channel skipped the build-time read timeout; arm it for this
                            // first in-flight request so a connected-but-silent upstream cannot hang it.
                            // (A non-pooled fresh channel already has its build-time read timeout, so this
                            // is a no-op for it.) Removed again on return to the pool.
                            armPooledInFlightReadTimeout(future.channel());
                            future.channel().writeAndFlush(httpRequest);
                        }
                    });
                } else if (future.cause() instanceof ClosedChannelException && channelResponseFuture.isCompletedExceptionally()) {
                    // The reverse order: the channel was closed first (its pipeline could not be built) and
                    // the connect then failed on the closed channel, so the channel's outcome is the cause.
                    relay(channelResponseFuture, responseFuture);
                } else {
                    httpResponseFuture.completeExceptionally(proxyFailure(upstreamProxies, secure, future.cause()));
                }
            });
    }

    /**
     * The failure to connect, as an {@link UpstreamProxyUnreachableException} when the forward client's connection was
     * to an upstream proxy, which it is for {@code forwardHttpProxy} and, through the tunnel's handler, for
     * {@code forwardHttpsProxy} and {@code forwardSocksProxy}: the target behind the proxy was not contacted. A failure
     * the proxy reports once connected, such as a target it could not reach, is not a failure to connect.
     */
    private Throwable proxyFailure(Map<ProxyConfiguration.Type, ProxyConfiguration> upstreamProxies, boolean secure, Throwable cause) {
        return proxyFailure(!secure && upstreamProxies.containsKey(ProxyConfiguration.Type.HTTP)
            ? upstreamProxies.get(ProxyConfiguration.Type.HTTP)
            : HttpClientInitializer.tunnelProxy(upstreamProxies, secure), cause);
    }

    private Throwable proxyFailure(@Nullable ProxyConfiguration proxy, Throwable cause) {
        if (!forwardProxyClient || proxy == null
            || !(cause instanceof SocketException || cause instanceof UnknownHostException || cause instanceof UnresolvedAddressException)) {
            return cause;
        }
        InetSocketAddress proxyAddress = proxy.getProxyAddress();
        if (unreachableProxiesReported.add(proxyAddress) && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("upstream proxy{}set by{}could not be reached, so forwards through it fail and are neither retried nor counted against their target's circuit breaker; check the proxy is running and its address is correct (logged once per proxy):{}")
                    .setArguments(hostAndPort(proxyAddress), propertyFor(proxy.getType()), boundedFaultDescription(cause))
            );
        }
        return new UpstreamProxyUnreachableException(proxyAddress, propertyFor(proxy.getType()), cause);
    }

    // as configured, without the "/<unresolved>" an unresolved address's toString adds
    private static String hostAndPort(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    private static String propertyFor(ProxyConfiguration.Type type) {
        switch (type) {
            case HTTP:
                return "forwardHttpProxy";
            case HTTPS:
                return "forwardHttpsProxy";
            default:
                return "forwardSocksProxy";
        }
    }

    private static void relay(CompletableFuture<Message> channelResponseFuture, CompletableFuture<Message> responseFuture) {
        channelResponseFuture.whenComplete((message, throwable) -> {
            if (throwable != null) {
                responseFuture.completeExceptionally(throwable);
            } else {
                responseFuture.complete(message);
            }
        });
    }

    /**
     * Arm an in-flight read timeout on a POOLED channel for the duration of one request.
     * <p>
     * Non-pooled channels get their {@link ReadTimeoutHandler} at pipeline-build time
     * ({@link HttpClientInitializer#addReadTimeoutHandlerIfNotPooled}); pooled channels deliberately do
     * NOT, because a blanket read timeout would fire while the connection sits idle in the pool between
     * requests and tear down a healthy keep-alive connection. But a pooled channel with a request IN
     * FLIGHT (a reused connection, or a fresh pooled channel's first request) was left unguarded — a
     * stalled upstream that connects/keep-alives but never sends the response would hang the request
     * future until {@code maxFutureTimeoutInMillis} at best, or indefinitely. We therefore arm the read
     * timeout just before dispatching a request on a pooled channel and remove it again when the channel
     * is returned to the pool ({@link HttpClientHandler#tryReturnToPool}); a streaming response removes
     * it earlier ({@link org.mockserver.codec.StreamingAwareHttpObjectAggregator}, which strips any
     * {@link ReadTimeoutHandler} by type when it switches to streaming, so a long inter-chunk pause is
     * not mistaken for a stall). No-op for non-pooled channels (already guarded) and when the timeout is
     * disabled or already armed.
     */
    private void armPooledInFlightReadTimeout(Channel channel) {
        if (configuration == null || channel.attr(CONNECTION_POOL).get() == null) {
            return;
        }
        // An HTTP/2 parent connection carries its read timeout per stream (child pipeline), never on
        // the parent — a parent-level read timeout would fire during legitimate idle keep-alive between
        // streams and during a long streaming response. Skip it here for a pooled HTTP/2 parent.
        if (channel.pipeline().get(io.netty.handler.codec.http2.Http2FrameCodec.class) != null) {
            return;
        }
        Long readTimeoutMillis = configuration.maxSocketTimeoutInMillis();
        if (readTimeoutMillis != null && readTimeoutMillis > 0 && channel.pipeline().get(ReadTimeoutHandler.class) == null) {
            channel.pipeline().addFirst("pooledInFlightReadTimeout", new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * Applies tuned TCP keepalive to a forward/proxy client bootstrap, when enabled (default on via
     * {@code forwardSocketKeepAlive}). This is robustness hardening that COMPLEMENTS — not replaces —
     * the pool's existing {@code isActive()}/{@code closeFuture}/idle-reaper defences: it lets the OS
     * detect dead or half-open upstream connections faster (most valuable during active/long-lived or
     * streaming requests, and for keep-warm users who raise {@code forwardConnectionPoolIdleTimeoutMillis}
     * above the keepalive idle so the idle reaper no longer pre-empts NAT/firewall mapping drops) and
     * keeps NAT/firewall mappings warm.
     * <p>
     * {@link ChannelOption#SO_KEEPALIVE} alone uses the OS default idle (~2h on Linux), which is useless
     * for NAT, so when the native epoll transport is in use the per-connection keepalive timers are also
     * tuned via {@code EpollChannelOption.TCP_KEEPIDLE}/{@code TCP_KEEPINTVL}/{@code TCP_KEEPCNT} (target
     * ~1–2 min dead-peer detection). Epoll is detected by reusing the codebase's existing group-derived
     * transport selection ({@link NettyTransport#socketChannelClassFor(EventLoopGroup)}), so it always
     * matches the channel class the bootstrap actually uses — including graceful epoll→NIO fallback. On
     * the NIO transport (macOS/Windows, or {@code useNativeTransport=false}) only SO_KEEPALIVE is set;
     * interval tuning requires epoll. The epoll option classes are referenced only inside a guarded
     * helper so this never hard-loads native classes on a non-epoll platform.
     */
    private void applyForwardSocketKeepAlive(Bootstrap bootstrap) {
        applyForwardSocketKeepAlive(
            bootstrap,
            eventLoopGroup(),
            Boolean.TRUE.equals(configuration.forwardSocketKeepAlive()),
            configuration.forwardSocketKeepAliveIdleSeconds(),
            configuration.forwardSocketKeepAliveIntervalSeconds(),
            configuration.forwardSocketKeepAliveCount(),
            mockServerLogger
        );
    }

    /**
     * Package-private seam (so it can be unit-tested deterministically against a constructed
     * {@link Bootstrap} on any platform). When {@code enabled}, sets {@link ChannelOption#SO_KEEPALIVE}
     * and, only when the group selects the native epoll transport, the tuned epoll keepalive timers.
     * Epoll is detected by reusing the codebase's group-derived selection
     * ({@link NettyTransport#socketChannelClassFor(EventLoopGroup)}) so it always matches the channel
     * class the bootstrap actually uses (including graceful epoll→NIO fallback). Values are clamped to
     * at least 1. No-op when disabled, so the historical "no SO_KEEPALIVE" behaviour is exactly
     * restored by {@code forwardSocketKeepAlive=false}.
     */
    static void applyForwardSocketKeepAlive(Bootstrap bootstrap, EventLoopGroup eventLoopGroup, boolean enabled, int idleSeconds, int intervalSeconds, int count, MockServerLogger mockServerLogger) {
        if (!enabled) {
            return;
        }
        bootstrap.option(ChannelOption.SO_KEEPALIVE, true);
        // Tune the keepalive timers only on epoll (the only transport that exposes them). Detected by
        // the same group-derived selection used to pick the channel class, so it can never desync.
        if (isEpollChannelClass(NettyTransport.socketChannelClassFor(eventLoopGroup))) {
            applyEpollKeepAliveOptions(
                bootstrap,
                Math.max(1, idleSeconds),
                Math.max(1, intervalSeconds),
                Math.max(1, count),
                mockServerLogger
            );
        }
    }

    /**
     * True when the selected channel class is the native epoll socket channel. Guarded against
     * {@link NoClassDefFoundError} so it is safe on NIO-only platforms (macOS/Windows) where the epoll
     * API classes may be absent — there it simply returns false.
     */
    private static boolean isEpollChannelClass(Class<? extends Channel> channelClass) {
        try {
            return channelClass == io.netty.channel.epoll.EpollSocketChannel.class;
        } catch (NoClassDefFoundError e) {
            return false;
        }
    }

    /**
     * Sets the epoll-specific keepalive timer options. Isolated into its own method (and guarded
     * against {@link NoClassDefFoundError}) so the {@code EpollChannelOption} references are only
     * resolved when epoll has already been selected — keeping the class loadable on NIO-only platforms.
     */
    private static void applyEpollKeepAliveOptions(Bootstrap bootstrap, int idleSeconds, int intervalSeconds, int count, MockServerLogger mockServerLogger) {
        try {
            bootstrap
                .option(io.netty.channel.epoll.EpollChannelOption.TCP_KEEPIDLE, idleSeconds)
                .option(io.netty.channel.epoll.EpollChannelOption.TCP_KEEPINTVL, intervalSeconds)
                .option(io.netty.channel.epoll.EpollChannelOption.TCP_KEEPCNT, count);
        } catch (NoClassDefFoundError | UnsatisfiedLinkError e) {
            // epoll classes unexpectedly unavailable despite the channel-class match — SO_KEEPALIVE
            // (already set) still applies with OS-default timers; nothing else to do.
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("unable to set epoll TCP keepalive options, falling back to SO_KEEPALIVE with OS-default timers: " + e.getMessage())
                );
            }
        }
    }

    /**
     * Looks up the name of a binary relay's destination, which blocks, so it is called off the event loop for a
     * destination not yet resolved. The address returned is what {@link #connectBinaryRelay} is then given, and it
     * looks nothing up: a destination reached directly is resolved; one that {@code forwardProxyBlockPrivateNetworks}
     * or an address in {@code forwardProxyAllowedHosts} / {@code forwardProxyDeniedHosts} checks is resolved and checked; one tunnelled through an upstream proxy without that check is returned as
     * given, for the proxy to resolve. A resolved address keeps the name, for the tunnel, SNI and the certificate
     * check.
     *
     * @param secure whether the client's connection is TLS from its first byte
     * @throws ForwardTargetBlockedException if forwardProxyBlockPrivateNetworks or the host lists refuse the
     *                                       destination, or it cannot be resolved for them
     * @throws UnknownHostException          if a destination reached directly cannot be resolved
     */
    public InetSocketAddress lookUpBinaryRelayTarget(InetSocketAddress remoteAddress, boolean secure) throws UnknownHostException {
        if (!remoteAddress.isUnresolved()) {
            return remoteAddress;
        }
        InetSocketAddress vetted = InetAddressValidator.validateForwardTarget(configuration, remoteAddress);
        if (!vetted.isUnresolved() || binaryRelayTunnel(upstreamProxiesFor(remoteAddress.getHostString()), secure) != null) {
            return vetted;
        }
        String host = remoteAddress.getHostString();
        return new InetSocketAddress(InetAddress.getByAddress(host, InetAddress.getByName(host).getAddress()), remoteAddress.getPort());
    }

    /**
     * Whether a binary relay's upstream connection to this destination is tunnelled through an upstream proxy given by
     * name, which {@link #lookUpBinaryRelayTunnelProxy} must then look up, off the event loop, before
     * {@link #connectBinaryRelay(EventLoop, InetSocketAddress, boolean, ChannelHandler, InetSocketAddress)}. Looks
     * nothing up.
     *
     * @param secure whether the client's connection is TLS from its first byte
     */
    public boolean binaryRelayTunnelProxyNeedsLookUp(InetSocketAddress remoteAddress, boolean secure) {
        ProxyConfiguration tunnel = binaryRelayTunnel(upstreamProxiesFor(remoteAddress.getHostString()), secure);
        return tunnel != null && tunnel.getProxyAddress().isUnresolved();
    }

    /**
     * Looks up the upstream proxy a binary relay's connection to this destination is tunnelled through, which blocks,
     * so it is called off the event loop. A name that does not resolve is returned unresolved, and the connect to it
     * fails as an unreachable proxy.
     *
     * @param secure whether the client's connection is TLS from its first byte
     * @return the address to connect to the proxy at, or null when the connection is not tunnelled
     */
    @Nullable
    public InetSocketAddress lookUpBinaryRelayTunnelProxy(InetSocketAddress remoteAddress, boolean secure) {
        ProxyConfiguration tunnel = binaryRelayTunnel(upstreamProxiesFor(remoteAddress.getHostString()), secure);
        return tunnel != null ? SocketAddresses.resolvedNow(tunnel.getProxyAddress()) : null;
    }

    /**
     * The proxy a binary relay's upstream connection is tunnelled through, or null for none: as for any connection
     * (for a secure one {@code forwardHttpsProxy} first, otherwise {@code forwardSocksProxy}), else
     * {@code forwardHttpsProxy}, else {@code forwardHttpProxy}. A binary connection is never sent to an HTTP proxy as
     * if it were the target: both HTTP proxies are asked to open a {@code CONNECT} tunnel, which carries any bytes.
     */
    @Nullable
    private static ProxyConfiguration binaryRelayTunnel(Map<ProxyConfiguration.Type, ProxyConfiguration> upstreamProxies, boolean secure) {
        ProxyConfiguration tunnel = HttpClientInitializer.tunnelProxy(upstreamProxies, secure);
        if (tunnel == null) {
            tunnel = upstreamProxies.get(ProxyConfiguration.Type.HTTPS);
        }
        return tunnel != null ? tunnel : upstreamProxies.get(ProxyConfiguration.Type.HTTP);
    }

    /**
     * Opens the upstream connection of a binary connection, which keeps it for its life. It is registered on the
     * given event loop, the client connection's, so both legs run on one thread. It is made directly, or through a
     * tunnel when an upstream proxy applies ({@code forwardSocksProxy}, or {@code CONNECT} through
     * {@code forwardHttpsProxy} or {@code forwardHttpProxy}), whose handler is then first in the pipeline, named
     * {@link #BINARY_RELAY_TUNNEL}, and the connect completes only once the tunnel is open.
     *
     * @param secure  whether the client's connection is TLS from its first byte
     * @param handler the upstream connection's only handler
     * @return the connect, whose channel is the upstream connection
     * @throws IllegalArgumentException if forwardProxyBlockPrivateNetworks or the host lists refuse the target
     */
    public ChannelFuture connectBinaryRelay(EventLoop eventLoop, InetSocketAddress remoteAddress, boolean secure, ChannelHandler handler) {
        return connectBinaryRelay(eventLoop, remoteAddress, secure, handler, null);
    }

    /**
     * As {@link #connectBinaryRelay(EventLoop, InetSocketAddress, boolean, ChannelHandler)}, connecting to the upstream
     * proxy, when there is one, at {@code tunnelProxyAddress}: the address {@link #lookUpBinaryRelayTunnelProxy} found.
     * Null connects to the proxy as configured, so a proxy given by name, not looked up, cannot be reached.
     */
    public ChannelFuture connectBinaryRelay(EventLoop eventLoop, InetSocketAddress remoteAddress, boolean secure, ChannelHandler handler, @Nullable InetSocketAddress tunnelProxyAddress) {
        ProxyConfiguration tunnel = binaryRelayTunnel(upstreamProxiesFor(remoteAddress.getHostString()), secure);
        // checked through a tunnel too, which is then opened by name for the proxy to resolve, as an HTTP forward's is
        InetSocketAddress target = InetAddressValidator.validateForwardTarget(configuration, remoteAddress);
        Long connectionTimeoutMillis = configuration.socketConnectionTimeoutInMillis();
        Bootstrap relayBootstrap = new Bootstrap()
            .group(eventLoop)
            .channel(NettyTransport.socketChannelClassFor(eventLoop))
            .option(ChannelOption.AUTO_READ, true)
            .option(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
            .option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(8 * 1024, 32 * 1024))
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectionTimeoutMillis != null ? (int) Math.min(connectionTimeoutMillis, Integer.MAX_VALUE) : null)
            .handler(handler);
        applyForwardSocketKeepAlive(
            relayBootstrap,
            eventLoop,
            Boolean.TRUE.equals(configuration.forwardSocketKeepAlive()),
            configuration.forwardSocketKeepAliveIdleSeconds(),
            configuration.forwardSocketKeepAliveIntervalSeconds(),
            configuration.forwardSocketKeepAliveCount(),
            mockServerLogger
        );
        if (tunnel == null) {
            return relayBootstrap.connect(target);
        }
        return connectThroughTunnel(relayBootstrap, tunnel, tunnelProxyAddress != null ? tunnelProxyAddress : tunnel.getProxyAddress(), unresolvedUnlessIpLiteral(remoteAddress), connectionTimeoutMillis);
    }

    /**
     * The tunnel's handler must take the connect, which it sends to the proxy, so it is added before the connect is
     * made: registered first, then connected, as {@code Bootstrap.connect} does with a no-op resolver.
     */
    private ChannelFuture connectThroughTunnel(Bootstrap relayBootstrap, ProxyConfiguration tunnel, InetSocketAddress proxyAddress, InetSocketAddress destination, Long connectionTimeoutMillis) {
        ProxyHandler tunnelHandler = HttpClientInitializer.tunnelHandler(tunnel, proxyAddress, mockServerLogger, configuration.maxHeaderSize());
        if (connectionTimeoutMillis != null && connectionTimeoutMillis > 0) {
            tunnelHandler.setConnectTimeoutMillis(connectionTimeoutMillis);
        }
        ChannelFuture registered = relayBootstrap.register();
        Channel channel = registered.channel();
        channel.pipeline().addFirst(BINARY_RELAY_TUNNEL, tunnelHandler);
        ChannelPromise tunnelled = channel.newPromise();
        registered.addListener(registration -> {
            if (!registration.isSuccess()) {
                tunnelled.tryFailure(registration.cause());
                return;
            }
            // added after handlerAdded in practice, so the CONNECT codec is removed before the connect completes on any caller's thread
            tunnelHandler.connectFuture().addListener(opened -> {
                if (opened.isSuccess()) {
                    tunnelled.trySuccess();
                } else {
                    tunnelled.tryFailure(tunnelRefused(tunnel, tunnelHandler, destination, opened.cause()));
                }
            });
            channel.connect(destination).addListener(connected -> {
                if (!connected.isSuccess()) {
                    tunnelled.tryFailure(proxyFailure(tunnel, connected.cause()));
                    channel.close();
                }
            });
        });
        return tunnelled;
    }

    /**
     * A {@code CONNECT} the proxy answered with a status other than 200, as a failure that names the proxy, its
     * setting and the status; any other failure as it is.
     */
    private static Throwable tunnelRefused(ProxyConfiguration tunnel, ProxyHandler tunnelHandler, InetSocketAddress destination, Throwable cause) {
        HttpResponseStatus status = tunnelHandler instanceof HttpConnectProxyHandler ? ((HttpConnectProxyHandler) tunnelHandler).refusedWith() : null;
        if (status == null) {
            return cause;
        }
        String hint = "";
        if (status.code() == HttpResponseStatus.PROXY_AUTHENTICATION_REQUIRED.code()) {
            hint = isNotBlank(tunnel.getUsername()) && isNotBlank(tunnel.getPassword())
                ? ", check forwardProxyAuthenticationUsername and forwardProxyAuthenticationPassword"
                : ", it asks for credentials: set forwardProxyAuthenticationUsername and forwardProxyAuthenticationPassword";
        }
        ConnectException refused = new ConnectException("upstream proxy " + hostAndPort(tunnel.getProxyAddress()) + " set by " + propertyFor(tunnel.getType())
            + " refused to open a CONNECT tunnel to " + hostAndPort(destination) + " with status " + status + hint);
        refused.initCause(cause);
        return refused;
    }

    /**
     * The TLS handler of a binary relay's upstream connection, for a client that started with TLS or turned it on
     * part way through: the forward client's TLS context, so the upstream certificate is checked as for any
     * forwarded TLS, and {@code socketConnectionTimeoutInMillis} as its handshake timeout. No ALPN is offered.
     * <p>
     * The upstream is named (SNI, and the name its certificate is checked against) by the target's host name. A
     * target given only as an address is named by the name the client sent MockServer, if any: that is the
     * upstream's own name when MockServer learned the target from the connection (transparent proxy, PROXY
     * protocol), whereas for a target given by name the client's name is MockServer's.
     *
     * @param clientServerName the name the client sent as SNI in its handshake with MockServer, or null
     */
    public SslHandler newBinaryRelaySslHandler(ByteBufAllocator allocator, InetSocketAddress remoteAddress, String clientServerName) {
        // getHostString, not getHostName: no reverse lookup on the event loop
        String host = remoteAddress.getHostString();
        if (isNotBlank(clientServerName) && (NetUtil.isValidIpV4Address(host) || NetUtil.isValidIpV6Address(host))) {
            host = clientServerName;
        }
        SslHandler sslHandler = nettySslContextFactory.createClientSslContext(forwardProxyClient, false, host).newHandler(allocator, host, remoteAddress.getPort());
        Long handshakeTimeoutMillis = configuration.socketConnectionTimeoutInMillis();
        if (handshakeTimeoutMillis != null && handshakeTimeoutMillis > 0) {
            sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
        }
        return sslHandler;
    }

    public HttpResponse sendRequest(HttpRequest httpRequest, long timeout, TimeUnit unit, boolean ignoreErrors) {
        HttpResponse httpResponse = null;
        try {
            httpResponse = sendRequest(httpRequest).get(timeout, unit);
        } catch (TimeoutException e) {
            if (!ignoreErrors) {
                throw new SocketCommunicationException("Response was not received from MockServer after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to wait longer please use \"mockserver.maxSocketTimeout\" system property or ConfigurationProperties.maxSocketTimeout(long milliseconds)", e.getCause());
            }
        } catch (InterruptedException | ExecutionException ex) {
            if (!ignoreErrors) {
                Throwable cause = ex.getCause() instanceof UpstreamProxyUnreachableException ? ex.getCause().getCause() : ex.getCause();
                if (cause instanceof SocketConnectionException) {
                    throw (SocketConnectionException) cause;
                } else if (cause instanceof ConnectException) {
                    throw new SocketConnectionException("Unable to connect to socket " + httpRequest.socketAddressFromHostHeader(), cause);
                } else if (cause instanceof UnknownHostException) {
                    throw new SocketConnectionException("Unable to resolve host " + httpRequest.socketAddressFromHostHeader(), cause);
                } else if (cause instanceof IOException) {
                    throw new SocketConnectionException(cause.getMessage(), cause);
                } else {
                    throw new RuntimeException("Exception while sending request - " + ex.getMessage(), ex);
                }
            }
        }
        return httpResponse;
    }

    public HttpResponse sendRequest(HttpRequest httpRequest, long timeout, TimeUnit unit) {
        return sendRequest(httpRequest, timeout, unit, false);
    }

    /**
     * Idempotent HTTP methods (RFC 7231 section 4.2.2), for which a request that failed on a REUSED pooled
     * connection before any response byte may be safely retried once on a fresh connection. A request
     * with no explicit method is treated as GET, matching the wire mapping.
     */
    private static final ImmutableSet<String> IDEMPOTENT_METHODS =
        ImmutableSet.of("GET", "HEAD", "PUT", "DELETE", "OPTIONS", "TRACE");

    private static boolean isIdempotent(HttpRequest httpRequest) {
        String method = httpRequest.getMethod("GET");
        return method == null || method.isEmpty() || IDEMPOTENT_METHODS.contains(method.toUpperCase());
    }

    /**
     * True when a request failed on a REUSED pooled connection with a connection-level closure/reset:
     * the stale-connection race (idle keep-alive close, HTTP/2 GOAWAY, upstream restart) that is safe to
     * retry on a fresh connection. Excludes read timeouts (a stalled-but-open upstream, bounded by the
     * in-flight read timeout) and TLS/decode failures, which are genuine and must not be retried.
     */
    private static boolean isRetryableReusedConnectionFailure(Throwable cause) {
        if (cause instanceof SocketConnectionException) {
            return true;
        }
        if (cause instanceof java.net.SocketException) {
            String message = cause.getMessage();
            return message != null
                && (message.contains("Connection reset") || message.contains("Broken pipe") || message.contains("broken pipe"));
        }
        return false;
    }

    /**
     * Whether a request failed only because its connection closed or was reset, as a stale pooled connection does: not
     * for a cause the failure names (TLS, HTTP/2, an unexpected exception), which is reported as that.
     */
    private static boolean closedWithoutAnswer(Throwable cause) {
        return (cause.getClass() == SocketConnectionException.class && cause.getCause() == null)
            || (cause instanceof SocketException && isRetryableReusedConnectionFailure(cause));
    }

    /**
     * A connection tunnelled through an upstream proxy leaves the destination's name to the proxy: Netty's default
     * resolver would look it up here, before the tunnel's handler sees it, and fail where only the proxy can
     * resolve it.
     */
    private static void resolveAtTunnelProxy(Bootstrap bootstrap, Map<ProxyConfiguration.Type, ProxyConfiguration> upstreamProxies, boolean secure) {
        if (HttpClientInitializer.tunnelProxy(upstreamProxies, secure) != null) {
            bootstrap.resolver(NoopAddressResolverGroup.INSTANCE);
        }
    }

    /**
     * The destination as a tunnel proxy is sent it: by name, unresolved, unless it is an IP literal, which is sent
     * as an address.
     */
    private static InetSocketAddress unresolvedUnlessIpLiteral(InetSocketAddress address) {
        return SocketAddresses.unresolvedUnlessIpLiteral(address.getHostString(), address.getPort());
    }

    /**
     * The upstream proxies a connection to this destination may go through: none for a host on {@code noProxyHosts},
     * which is connected to directly. An IP-address entry matches only a destination given as that address.
     */
    private Map<ProxyConfiguration.Type, ProxyConfiguration> upstreamProxiesFor(@Nullable String destinationHost) {
        if (!proxyConfigurations.isEmpty() && NoProxyHostsUtils.isHostOnNoProxyList(destinationHost, configuration.noProxyHosts())) {
            return ImmutableMap.of();
        }
        return proxyConfigurations;
    }

    private Map<ProxyConfiguration.Type, ProxyConfiguration> upstreamProxiesFor(HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress) {
        return proxyConfigurations.isEmpty() ? proxyConfigurations : upstreamProxiesFor(destinationHost(httpRequest, remoteAddress));
    }

    /**
     * The host a request is sent to: {@code remoteAddress} when given, otherwise the request's socket address or Host
     * header, without a lookup; null when it names none.
     */
    @Nullable
    private static String destinationHost(HttpRequest httpRequest, @Nullable InetSocketAddress remoteAddress) {
        if (remoteAddress != null) {
            return remoteAddress.getHostString();
        }
        try {
            return httpRequest.unresolvedSocketAddressFromHostHeader().getHostString();
        } catch (RuntimeException noDestination) {
            return null;
        }
    }
}

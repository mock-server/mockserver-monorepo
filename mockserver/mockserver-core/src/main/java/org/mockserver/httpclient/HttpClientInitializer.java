package org.mockserver.httpclient;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpObjectDecoder;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.timeout.ReadTimeoutHandler;
import org.mockserver.codec.BoundedZstdHttpContentDecompressor;
import org.mockserver.codec.HttpLineEndSplitGuard;
import org.mockserver.codec.MockServerHttpClientCodec;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.LoggingHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.logging.RawNetworkTrafficLog;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.Protocol;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.SocketAddresses;
import org.mockserver.socket.tls.NettySslContextFactory;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.boundedFaultDescription;
import static org.mockserver.httpclient.NettyHttpClient.CONNECTION_POOL;
import static org.mockserver.httpclient.NettyHttpClient.REMOTE_SOCKET;
import static org.mockserver.httpclient.NettyHttpClient.SECURE;
import static org.slf4j.event.Level.DEBUG;
import static org.slf4j.event.Level.ERROR;
import static org.slf4j.event.Level.TRACE;

@ChannelHandler.Sharable
public class HttpClientInitializer extends ChannelInitializer<SocketChannel> {

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final boolean forwardProxyClient;
    private final Protocol httpProtocol;
    private final HttpClientConnectionErrorHandler httpClientConnectionHandler;
    private final CompletableFuture<Protocol> protocolFuture;
    private final HttpClientHandler httpClientHandler;
    // the upstream proxies this connection goes through: none for a host on noProxyHosts
    private final Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations;
    private final NettySslContextFactory nettySslContextFactory;
    private final RawNetworkTrafficLog rawNetworkTrafficLog;

    HttpClientInitializer(Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations, MockServerLogger mockServerLogger, boolean forwardProxyClient, NettySslContextFactory nettySslContextFactory, Protocol httpProtocol, Configuration configuration, RawNetworkTrafficLog rawNetworkTrafficLog) {
        this.rawNetworkTrafficLog = rawNetworkTrafficLog;
        this.proxyConfigurations = proxyConfigurations;
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.forwardProxyClient = forwardProxyClient;
        this.httpProtocol = httpProtocol;
        this.protocolFuture = new CompletableFuture<>();
        this.httpClientHandler = new HttpClientHandler(mockServerLogger);
        this.httpClientConnectionHandler = new HttpClientConnectionErrorHandler();
        this.nettySslContextFactory = nettySslContextFactory;
    }

    public void whenComplete(BiConsumer<? super Protocol, ? super Throwable> action) {
        protocolFuture.whenComplete(action);
    }

    /**
     * The upstream proxy a connection is tunnelled through (HTTP {@code CONNECT} for a secure connection when
     * {@code forwardHttpsProxy} is set, otherwise SOCKS5 when {@code forwardSocksProxy} is set), or null. The
     * tunnel's handler sends the proxy the destination it was connected to, so the connection must reach it with
     * the destination's name unresolved for the proxy to resolve it.
     */
    static ProxyConfiguration tunnelProxy(Map<ProxyConfiguration.Type, ProxyConfiguration> proxyConfigurations, boolean secure) {
        if (proxyConfigurations == null) {
            return null;
        }
        if (secure && proxyConfigurations.containsKey(ProxyConfiguration.Type.HTTPS)) {
            return proxyConfigurations.get(ProxyConfiguration.Type.HTTPS);
        }
        return proxyConfigurations.get(ProxyConfiguration.Type.SOCKS5);
    }

    /**
     * The handler that opens a tunnel through this upstream proxy: {@code CONNECT} for {@code forwardHttpsProxy}, and
     * for {@code forwardHttpProxy} where a binary relay tunnels through it; SOCKS5 for {@code forwardSocksProxy}.
     * <p>
     * The handler connects to {@code proxyAddress} itself, past the bootstrap's resolver, so a proxy given by name must
     * have been looked up: an unresolved address fails the connect with an
     * {@link java.nio.channels.UnresolvedAddressException}.
     */
    static ProxyHandler tunnelHandler(ProxyConfiguration tunnelProxy, InetSocketAddress proxyAddress, MockServerLogger mockServerLogger, int maxHeaderSize) {
        if (tunnelProxy.getType() != ProxyConfiguration.Type.SOCKS5) {
            boolean credentials = isNotBlank(tunnelProxy.getUsername()) && isNotBlank(tunnelProxy.getPassword());
            return new HttpConnectProxyHandler(proxyAddress, credentials ? tunnelProxy.getUsername() : null, credentials ? tunnelProxy.getPassword() : null, mockServerLogger, maxHeaderSize);
        } else if (isNotBlank(tunnelProxy.getUsername()) && isNotBlank(tunnelProxy.getPassword())) {
            return new Socks5ProxyHandler(proxyAddress, tunnelProxy.getUsername(), tunnelProxy.getPassword());
        } else {
            return new Socks5ProxyHandler(proxyAddress);
        }
    }

    @Override
    public void initChannel(SocketChannel channel) {
        try {
            buildPipeline(channel);
        } catch (RuntimeException failure) {
            failToSetUp(channel, failure);
        }
    }

    /**
     * Fails the request the connection was opened for with why its pipeline could not be built, as a configuration
     * error, and closes the connection. Netty would log the failure at {@code WARN} through its own logger and close
     * the connection, and the request would fail only as a closed connection.
     */
    private void failToSetUp(SocketChannel channel, RuntimeException failure) {
        InetSocketAddress upstream = channel.attr(REMOTE_SOCKET).get();
        String message = "connection to " + (upstream != null ? upstream.getHostString() + ":" + upstream.getPort() : "upstream") + " could not be set up: " + boundedFaultDescription(failure);
        if (!HttpClientConnectionErrorHandler.failWaitingRequest(channel, new ClientConfigurationException(message, failure))) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(ERROR)
                    .setMessageFormat("connection to:{}could not be set up")
                    .setArguments(upstream)
                    .setThrowable(boundedFault(failure))
            );
        }
        channel.close();
    }

    private void buildPipeline(SocketChannel channel) {
        ChannelPipeline pipeline = channel.pipeline();
        boolean secure = channel.attr(SECURE) != null && channel.attr(SECURE).get() != null && channel.attr(SECURE).get();

        ProxyConfiguration tunnelProxy = tunnelProxy(proxyConfigurations, secure);
        if (tunnelProxy != null) {
            // looked up for each connection, on the forward client's event loop, as the bootstrap's own resolver looks up
            // a destination reached directly by name
            pipeline.addLast(tunnelHandler(tunnelProxy, SocketAddresses.resolvedNow(tunnelProxy.getProxyAddress()), mockServerLogger, maxHeaderSize()));
        }
        pipeline.addLast(httpClientConnectionHandler);

        if (secure) {
            InetSocketAddress remoteAddress = channel.attr(REMOTE_SOCKET).get();
            SslHandler sslHandler = nettySslContextFactory.createClientSslContext(forwardProxyClient, httpProtocol != null && httpProtocol.equals(Protocol.HTTP_2), remoteAddress.getHostName()).newHandler(channel.alloc(), remoteAddress.getHostName(), remoteAddress.getPort());
            // Bound the TLS handshake by the configured connection timeout instead of Netty's fixed 10s
            // default: the handshake is part of establishing the connection, so socketConnectionTimeout
            // (which also drives the TCP CONNECT_TIMEOUT_MILLIS) covers the whole connect-plus-handshake
            // window, for HTTP and binary forwards, pooled or not.
            Long handshakeTimeoutMillis = configuration.socketConnectionTimeoutInMillis();
            if (handshakeTimeoutMillis != null && handshakeTimeoutMillis > 0) {
                sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
            }
            pipeline.addLast(sslHandler);
        }

        // add logging
        if (mockServerLogger.isEnabledForInstance(TRACE)) {
            pipeline.addLast(new LoggingHandler(HttpClientHandler.class.getName()));
        }

        if (secure) {
            // use ALPN to determine http1 or http2
            pipeline.addLast(new HttpOrHttp2Initializer(mockServerLogger, this::configureHttp1Pipeline, this::configureHttp2Pipeline));
        } else {
            // default to http1 without TLS
            configureHttp1Pipeline(pipeline);
        }
    }

    /**
     * Guards an in-flight upstream read with a {@link ReadTimeoutHandler} sized from
     * {@code maxSocketTimeoutInMillis}, so a stalled upstream that connects but never sends a response
     * times out and completes the response future exceptionally (via {@link HttpClientHandler#exceptionCaught})
     * instead of leaving the channel and {@link java.util.concurrent.CompletableFuture} open forever.
     * <p>
     * The handler is added ONLY on non-pooled channels. A pooled keep-alive channel sits idle in the
     * {@link HttpForwardConnectionPool} between requests (with {@code AUTO_READ} on), where a blanket
     * read timeout would fire during legitimate idle keep-alive and tear the connection down — so for
     * pooled channels the pool's own idle eviction owns the lifecycle and no read timeout is armed. A
     * non-pooled channel writes its single request immediately after connect and is closed after the
     * one response, so arming the read timeout at pipeline-build time is correct for it.
     */
    private void addReadTimeoutHandlerIfNotPooled(ChannelPipeline pipeline) {
        if (pipeline.channel().attr(CONNECTION_POOL).get() != null) {
            return;
        }
        long readTimeoutMillis = configuration.maxSocketTimeoutInMillis();
        if (readTimeoutMillis > 0) {
            pipeline.addLast(new ReadTimeoutHandler(readTimeoutMillis, TimeUnit.MILLISECONDS));
        }
    }

    /**
     * The most an upstream's response headers, or its trailers, may be: read when a connection's codec is built, so a
     * change applies to new connections.
     */
    private int maxHeaderSize() {
        return configuration.maxHeaderSize();
    }

    private void configureHttp1Pipeline(ChannelPipeline pipeline) {
        if (rawNetworkTrafficLog != null) {
            rawNetworkTrafficLog.addTo(pipeline, false);
        }
        addReadTimeoutHandlerIfNotPooled(pipeline);
        int maxHeaderSize = maxHeaderSize();
        // a status line is short, so it and each chunk-size line keep Netty's limit
        HttpClientCodec codec = new HttpClientCodec(HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH, maxHeaderSize, HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE);
        HttpLineEndSplitGuard lineEndSplitGuard = new HttpLineEndSplitGuard(codec);
        pipeline.addLast(lineEndSplitGuard.beforeCodec());
        pipeline.addLast(codec);
        pipeline.addLast(lineEndSplitGuard.afterCodec());
        pipeline.addLast(new ForwardHeaderLimit.Http1Response(mockServerLogger, maxHeaderSize));
        pipeline.addLast(new BoundedZstdHttpContentDecompressor());
        pipeline.addLast(new TimeToFirstByteHandler());
        pipeline.addLast(new StreamingAwareHttpObjectAggregator(configuration.maxResponseBodySize(), configuration, mockServerLogger));
        pipeline.addLast(new MockServerHttpClientCodec(mockServerLogger, proxyConfigurations));
        pipeline.addLast(httpClientHandler);
        recordForwardUpstreamProtocol(pipeline, "http1_1");
        protocolFuture.complete(Protocol.HTTP_1_1);
    }

    /**
     * Record (metric + DEBUG log) the protocol this forward/proxy client connection actually negotiated
     * to the upstream, so operators can confirm whether {@code forwardProxyHttp2Upgrade} is taking effect.
     * A forward shown as {@code http1_1} to a backend that withholds its streaming SSE head over HTTP/1.1
     * (e.g. the OpenAI Codex endpoint) is the classic cause of a high forward time-to-first-byte.
     */
    private void recordForwardUpstreamProtocol(ChannelPipeline pipeline, String protocol) {
        String upstreamHost = "unknown";
        InetSocketAddress remote = pipeline.channel().attr(REMOTE_SOCKET).get();
        if (remote != null) {
            upstreamHost = remote.getHostString();
        }
        Metrics.incrementForwardUpstreamProtocol(upstreamHost, protocol);
        if (mockServerLogger.isEnabledForInstance(DEBUG)) {
            mockServerLogger.logEvent(new LogEntry()
                .setLogLevel(DEBUG)
                .setMessageFormat("forward upstream connection to {} negotiated {}")
                .setArguments(upstreamHost, protocol));
        }
    }

    /**
     * Builds a streaming-capable HTTP/2 client pipeline using the same multiplex stack the server side
     * uses ({@link Http2FrameCodecBuilder#forClient()} + {@link Http2MultiplexHandler}), so that — unlike
     * the previous {@code InboundHttp2ToHttpAdapter} path which aggregated the whole response — a streamed
     * upstream response (Server-Sent Events) is relayed incrementally to the proxy client. This is selected
     * purely by ALPN, so it serves BOTH direct HTTP/2 clients and forward-over-HTTP/2.
     * <p>
     * The MockServer request is written to the parent channel by {@link NettyHttpClient} exactly as for
     * HTTP/1.1; {@link Http2ForwardRequestDispatchHandler} (at the tail) intercepts that write, opens a
     * single outbound stream and dispatches the request to it. The per-stream response pipeline (decode,
     * decompress, streaming-aware aggregate, relay) is built by {@link Http2ForwardStreamChildInitializer}.
     * <p>
     * ROUND-TRIP vs STREAMING / stream correlation: a spec-compliant HTTP/2 upstream answers on the SAME
     * (client-initiated, odd) stream the request was sent on, so the response arrives on the bootstrap
     * child stream and is handled there. However MockServer's OWN non-multiplex HTTP/2 server (the
     * {@code HttpToHttp2ConnectionHandler} path in PortUnificationHandler) does not propagate the request's
     * stream id onto its responses, so it returns responses generally — ordinary aggregated mock responses
     * as well as gRPC reflection and websocket object-callback forwards — on a fresh SERVER-INITIATED (even)
     * stream rather than echoing the request's stream id. A strict multiplex client treats such a stream as an
     * unsolicited inbound stream; the original implementation RST-reset it via {@code ClosedInboundHttp2-
     * StreamHandler}, so the response was discarded and the request's stream never completed (the round
     * trip hung until timeout — the regression this fixes). The old aggregating client tolerated it because
     * {@code InboundHttp2ToHttpAdapter} correlates at the connection level, not by strict stream identity.
     * <p>
     * Because this client disables server push ({@code pushEnabled(false)}), an inbound (server-initiated)
     * stream is never a legitimate push — it can only be such a misattributed response — so the inbound
     * stream is routed through the SAME per-stream response pipeline ({@code childInitializer}) instead of
     * being reset. That completes the response future for these paths while leaving the compliant
     * same-stream path (and the incremental SSE streaming feature) unchanged.
     * <p>
     * ALPN has already proven HTTP/2 by the time this runs, so {@code protocolFuture} is completed with
     * {@link Protocol#HTTP_2} here; the frame codec consumes the SETTINGS frame, so the old
     * {@link Http2SettingsHandler} (which waited for SETTINGS) is not used.
     */
    private void configureHttp2Pipeline(ChannelPipeline pipeline) {
        // NOTE: deliberately NO parent-channel read timeout here. The per-stream ReadTimeoutHandler is
        // armed on the CHILD pipeline (Http2ForwardStreamChildInitializer) and is swapped for the longer
        // stream IdleStateHandler (streamIdleTimeoutSeconds) when a streaming response is detected. A read
        // timeout on the PARENT would never be swapped, so an inter-event pause longer than maxSocketTimeout
        // (default 20s) during a streaming response — e.g. an LLM reasoning gap — would trip it and close the
        // connection, truncating the stream. The pre-dispatch window (connect + TLS handshake) is already
        // covered by the connect timeout and TLS handshake timeout.

        int maxFrameSize = configuration.maxResponseBodySize();
        int maxHeaderSize = maxHeaderSize();
        // the limit an upstream announces for requests is advisory (RFC 9113 section 6.5.2): send, and relay its answer
        Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forClient()
            .initialSettings(forwardClientSettings(maxFrameSize, maxHeaderSize))
            .encoderIgnoreMaxHeaderListSize(true);
        if (rawNetworkTrafficLog != null) {
            frameCodecBuilder.frameLogger(rawNetworkTrafficLog.http2FrameLogger());
        } else if (mockServerLogger.isEnabledForInstance(TRACE)) {
            frameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.TRACE, HttpClientHandler.class.getName()));
        }
        pipeline.addLast(frameCodecBuilder.build());

        Http2ForwardStreamChildInitializer childInitializer = new Http2ForwardStreamChildInitializer(configuration, mockServerLogger, proxyConfigurations, httpClientHandler, httpClientConnectionHandler, maxHeaderSize);
        // Push is disabled, so an inbound (server-initiated) stream is never a push — it is a response
        // MockServer's own non-multiplex HTTP/2 server returned on a server-initiated stream instead of the
        // request's stream. Route it through the same response pipeline (NOT reset) so the round trip
        // completes; a compliant upstream answers on the client-initiated stream opened below and never
        // creates an inbound stream, so this leaves the same-stream / streaming path untouched.
        pipeline.addLast(new Http2MultiplexHandler(childInitializer));
        // Intercepts the MockServer request written to the parent channel and dispatches it on a new stream.
        pipeline.addLast(new Http2ForwardRequestDispatchHandler(childInitializer));
        pipeline.addLast(new ForwardHeaderLimit.Http2Connection(mockServerLogger, maxHeaderSize));
        // last: it takes every exception the header limit's handler passes on
        pipeline.addLast(new Http2ForwardConnectionExceptionHandler(mockServerLogger));

        // ALPN already proved HTTP/2; the frame codec consumes SETTINGS, so complete immediately.
        recordForwardUpstreamProtocol(pipeline, "http2");
        protocolFuture.complete(Protocol.HTTP_2);
    }

    /**
     * We are a client, so never accept server push. The upstream may open one stream of its own at a time: a legacy
     * server answers on one, and every stream it opens gets its own response aggregator. A response's header list,
     * and its trailers, may be up to {@code maxHeaderSize}, which is announced as
     * {@code SETTINGS_MAX_HEADER_LIST_SIZE}.
     */
    static Http2Settings forwardClientSettings(int maxFrameSize, int maxHeaderSize) {
        return Http2Settings.defaultSettings()
            .pushEnabled(false)
            .maxConcurrentStreams(1)
            .maxHeaderListSize(maxHeaderSize)
            .maxFrameSize(maxFrameSize < Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                ? Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                : Math.min(maxFrameSize, Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND));
    }

    /**
     * Tail outbound handler on the parent HTTP/2 connection. {@link NettyHttpClient} writes the MockServer
     * {@link org.mockserver.model.HttpRequest} to the parent channel exactly as it does for HTTP/1.1; this
     * handler intercepts that write, opens a single outbound stream channel (configured by
     * {@link Http2ForwardStreamChildInitializer}) and writes the request to it, where the child's encoder
     * turns it into HEADERS + DATA frames. Keeping the dispatch here means {@link NettyHttpClient} needs no
     * HTTP/2-specific code.
     */
    private static class Http2ForwardRequestDispatchHandler extends io.netty.channel.ChannelOutboundHandlerAdapter {

        private final Http2ForwardStreamChildInitializer childInitializer;

        private Http2ForwardRequestDispatchHandler(Http2ForwardStreamChildInitializer childInitializer) {
            this.childInitializer = childInitializer;
        }

        @Override
        public void write(io.netty.channel.ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
            if (msg instanceof org.mockserver.model.HttpRequest) {
                new Http2StreamChannelBootstrap(ctx.channel())
                    .handler(childInitializer)
                    .open()
                    .addListener((io.netty.util.concurrent.GenericFutureListener<io.netty.util.concurrent.Future<Http2StreamChannel>>) openFuture -> {
                        if (openFuture.isSuccess()) {
                            Http2StreamChannel streamChannel = openFuture.getNow();
                            streamChannel.writeAndFlush(msg).addListener(writeFuture -> {
                                if (writeFuture.isSuccess()) {
                                    promise.setSuccess();
                                } else {
                                    promise.setFailure(writeFuture.cause());
                                    streamChannel.close();
                                }
                            });
                        } else {
                            promise.setFailure(openFuture.cause());
                        }
                    });
            } else {
                super.write(ctx, msg, promise);
            }
        }
    }
}

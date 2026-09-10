package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.codec.http.HttpContentDecompressor;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.*;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.codec.PreserveHeadersNettyRemoves;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.LoggingHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.grpc.GrpcMultiplexChildInitializer;
import org.mockserver.netty.grpc.GrpcToHttpRequestHandler;
import org.mockserver.netty.grpc.GrpcToHttpResponseHandler;
import org.mockserver.netty.proxy.BinaryRequestProxyingHandler;
import org.mockserver.netty.proxy.socks.Socks4ProxyHandler;
import org.mockserver.netty.proxy.socks.Socks5ProxyHandler;
import org.mockserver.netty.proxy.socks.SocksDetector;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.socket.tls.SniHandler;
import org.slf4j.event.Level;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Collections.unmodifiableSet;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.exception.ExceptionHandling.*;
import static org.mockserver.mock.action.http.HttpActionHandler.setRemoteAddress;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.Protocol.HTTP_2;
import static org.mockserver.netty.HttpRequestHandler.LOCAL_HOST_HEADERS;
import static org.mockserver.netty.HttpRequestHandler.setProxyingRequest;
import static org.mockserver.netty.proxy.relay.RelayConnectHandler.*;
import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;
import static org.slf4j.event.Level.TRACE;
import static org.slf4j.event.Level.WARN;

/**
 * @author jamesdbloom
 */
public class PortUnificationHandler extends ReplayingDecoder<Void> {

    /**
     * The HTTP/2 {@code SETTINGS_MAX_CONCURRENT_STREAMS} MockServer advertises on every HTTP/2
     * connection, and which Netty enforces by refusing further streams with {@code REFUSED_STREAM}
     * ({@code AbstractHttp2ConnectionHandlerBuilder.enforceMaxActiveStreams} →
     * {@code connection.remote().maxActiveStreams(...)}).
     * <p>
     * This is set <strong>explicitly</strong>, and deliberately matches the value Netty 4.2 would
     * otherwise supply ({@code Http2CodecUtil.SMALLEST_MAX_CONCURRENT_STREAMS} = 100), so current
     * behaviour is unchanged while the limit becomes MockServer's own rather than an inherited
     * default. It cannot be left implicit: Netty 4.1's {@code Http2Settings.defaultSettings()}
     * advertises no concurrent-stream limit at all (RFC 9113 then permits unbounded streams), and
     * 4.2 added one — so anything depending on the limit would silently change meaning with a
     * Netty upgrade or downgrade.
     * <p>
     * {@link org.mockserver.netty.grpc.GrpcPendingRequests} depends on this bound: it sizes its
     * per-stream record map above this value so eviction can never discard a live stream (which
     * would silently skip gRPC conversion — issue #2419).
     */
    public static final int HTTP2_MAX_CONCURRENT_STREAMS = 100;

    // public so the single AttributeKey instance is the shared source of truth -- ConnectionScopeHandler
    // references these directly to propagate connection-scoped state onto HTTP/2 stream child channels.
    // (HTTP_ENABLED stays private: it is only ever read here, never on a child stream.)
    public static final AttributeKey<Boolean> TLS_ENABLED_UPSTREAM = AttributeKey.valueOf("TLS_ENABLED_UPSTREAM");
    public static final AttributeKey<Boolean> TLS_ENABLED_DOWNSTREAM = AttributeKey.valueOf("TLS_ENABLED_DOWNSTREAM");
    public static final AttributeKey<NettySslContextFactory> NETTY_SSL_CONTEXT_FACTORY = AttributeKey.valueOf("NETTY_SSL_CONTEXT_FACTORY");
    private static final AttributeKey<Boolean> HTTP_ENABLED = AttributeKey.valueOf("HTTP_ENABLED");
    public static final AttributeKey<Boolean> HTTP2_ENABLED = AttributeKey.valueOf("HTTP2_ENABLED");
    private static final Map<PortBinding, Set<String>> localAddressesCache = new ConcurrentHashMap<>();

    protected final MockServerLogger mockServerLogger;
    private final LoggingHandler loggingHandler = new LoggingHandler(PortUnificationHandler.class.getName() + "-first");
    private final HttpContentLengthRemover httpContentLengthRemover = new HttpContentLengthRemover();
    private final PreserveHeadersNettyRemoves preserveHeadersNettyRemoves = new PreserveHeadersNettyRemoves();
    private final Configuration configuration;
    private final LifeCycle server;
    private final HttpState httpState;
    private final HttpActionHandler actionHandler;
    private final NettySslContextFactory nettySslContextFactory;
    private final McpSessionManager mcpSessionManager;
    private final MockServerHttpResponseToFullHttpResponse mockServerHttpResponseToFullHttpResponse;

    public PortUnificationHandler(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler actionHandler, NettySslContextFactory nettySslContextFactory, McpSessionManager mcpSessionManager) {
        this.configuration = configuration;
        this.server = server;
        this.mockServerLogger = httpState.getMockServerLogger();
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.nettySslContextFactory = nettySslContextFactory;
        this.mcpSessionManager = mcpSessionManager;
        this.mockServerHttpResponseToFullHttpResponse = new MockServerHttpResponseToFullHttpResponse(mockServerLogger);
    }

    public static NettySslContextFactory nettySslContextFactory(Channel channel) {
        if (channel.attr(NETTY_SSL_CONTEXT_FACTORY).get() != null) {
            return channel.attr(NETTY_SSL_CONTEXT_FACTORY).get();
        } else {
            throw new RuntimeException("NettySslContextFactory not yet initialised for channel " + channel);
        }
    }

    public static void enableSslUpstreamAndDownstream(Channel channel) {
        channel.attr(TLS_ENABLED_UPSTREAM).set(Boolean.TRUE);
        channel.attr(TLS_ENABLED_DOWNSTREAM).set(Boolean.TRUE);
    }

    public static boolean isSslEnabledUpstream(Channel channel) {
        if (channel.attr(TLS_ENABLED_UPSTREAM).get() != null) {
            return channel.attr(TLS_ENABLED_UPSTREAM).get();
        } else {
            return false;
        }
    }

    public static void enableSslDownstream(Channel channel) {
        channel.attr(TLS_ENABLED_DOWNSTREAM).set(Boolean.TRUE);
    }

    public static void disableSslDownstream(Channel channel) {
        channel.attr(TLS_ENABLED_DOWNSTREAM).set(Boolean.FALSE);
    }

    public static boolean isSslEnabledDownstream(Channel channel) {
        if (channel.attr(TLS_ENABLED_DOWNSTREAM).get() != null) {
            return channel.attr(TLS_ENABLED_DOWNSTREAM).get();
        } else {
            return false;
        }
    }

    public static void httpEnabled(Channel channel) {
        channel.attr(HTTP_ENABLED).set(Boolean.TRUE);
    }

    public static boolean isHttpEnabled(Channel channel) {
        if (channel.attr(HTTP_ENABLED).get() != null) {
            return channel.attr(HTTP_ENABLED).get();
        } else {
            return false;
        }
    }

    public static void http2Enabled(Channel channel) {
        channel.attr(HTTP2_ENABLED).set(Boolean.TRUE);
    }

    public static boolean isHttp2Enabled(Channel channel) {
        if (channel.attr(HTTP2_ENABLED).get() != null) {
            return channel.attr(HTTP2_ENABLED).get();
        } else {
            return false;
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        // Apply any configured connection delay here — before the first inbound bytes are read —
        // rather than blocking inside decode() on the event loop (which would stall every other
        // channel sharing this worker thread). We suppress auto-read, schedule the read to resume
        // after the delay, and then let channelActive propagate. Because no bytes have been read yet
        // there is no ReplayingDecoder cumulation to preserve; protocol detection simply begins once
        // the delayed read delivers the first bytes. Connection delay simulates slow connection
        // establishment, so deferring the first read reproduces it more faithfully than a sleep.
        Delay connectionDelay = configuration.connectionDelay();
        if (connectionDelay == null) {
            long configuredMillis = ConfigurationProperties.connectionDelayMillis();
            if (configuredMillis > 0) {
                connectionDelay = Delay.milliseconds(configuredMillis);
            }
        }
        long delayMillis = connectionDelay != null ? connectionDelay.sampleValueMillis() : 0;
        if (delayMillis > 0) {
            ctx.channel().config().setAutoRead(false);
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.channel().config().setAutoRead(true);
                    ctx.read();
                }
            }, delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        ctx.fireChannelActive();
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        ctx.channel().attr(NETTY_SSL_CONTEXT_FACTORY).set(nettySslContextFactory);
        if (SocksDetector.isSocks4(msg, actualReadableBytes())) {
            logStage(ctx, "adding SOCKS4 decoders");
            enableSocks4(ctx, msg);
        } else if (SocksDetector.isSocks5(msg, actualReadableBytes())) {
            logStage(ctx, "adding SOCKS5 decoders");
            enableSocks5(ctx, msg);
        } else if (isTls(msg)) {
            logStage(ctx, "adding TLS decoders");
            enableTls(ctx, msg);
        } else if (configuration.http2Enabled() && HTTP_2.equals(getALPNProtocol(mockServerLogger, ctx))) {
            logStage(ctx, "adding HTTP2 decoders");
            switchToHttp2(ctx, msg);
        } else if (configuration.http2Enabled() && isH2cPreface(msg)) {
            logStage(ctx, "adding HTTP2 cleartext (h2c) decoders");
            switchToH2c(ctx, msg);
        } else if (isHttp(msg)) {
            logStage(ctx, "adding HTTP decoders");
            switchToHttp(ctx, msg);
        } else if (isProxyConnected(msg)) {
            logStage(ctx, "setting proxy connected");
            switchToProxyConnected(ctx, msg);
        } else if (configuration.assumeAllRequestsAreHttp()) {
            logStage(ctx, "adding HTTP decoders");
            switchToHttp(ctx, msg);
        } else {
            logStage(ctx, "adding binary decoder");
            switchToBinaryRequestProxying(ctx, msg);
        }

        if (mockServerLogger.isEnabledForInstance(TRACE)) {
            loggingHandler.addLoggingHandler(ctx);
        }
    }

    private void logStage(ChannelHandlerContext ctx, String message) {
        if (mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.TRACE)
                    .setMessageFormat(message + " for channel:{}pipeline:{}")
                    .setArguments(ctx.channel().toString(), ctx.pipeline().names())
            );
        }
    }

    private void enableSocks4(ChannelHandlerContext ctx, ByteBuf msg) {
        enableSocks(ctx, msg, new Socks4ServerDecoder(), new Socks4ProxyHandler(configuration, mockServerLogger, server), Socks4ServerEncoder.INSTANCE);
    }

    private void enableSocks5(ChannelHandlerContext ctx, ByteBuf msg) {
        enableSocks(ctx, msg, new Socks5InitialRequestDecoder(), new Socks5ProxyHandler(configuration, mockServerLogger, server), Socks5ServerEncoder.DEFAULT);
    }

    private void enableSocks(ChannelHandlerContext ctx, ByteBuf msg, ReplayingDecoder<?> socksInitialRequestDecoder, ChannelHandler... channelHandlers) {
        ChannelPipeline pipeline = ctx.pipeline();
        for (ChannelHandler channelHandler : channelHandlers) {
            if (isSslEnabledUpstream(ctx.channel())) {
                pipeline.addAfter("SslHandler#0", null, channelHandler);
            } else {
                pipeline.addFirst(channelHandler);
            }
        }
        if (isSslEnabledUpstream(ctx.channel())) {
            pipeline.addAfter("SslHandler#0", null, socksInitialRequestDecoder);
        } else {
            pipeline.addFirst(socksInitialRequestDecoder);
        }

        setProxyingRequest(ctx, Boolean.TRUE);

        // re-unify (with SOCKS5 enabled)
        if (isSslEnabledUpstream(ctx.channel())) {
            pipeline.context("SslHandler#0").fireChannelRead(msg.readBytes(actualReadableBytes()));
        } else {
            ctx.pipeline().fireChannelRead(msg.readBytes(actualReadableBytes()));
        }
    }

    private boolean isTls(ByteBuf buf) {
        return SslHandler.isEncrypted(buf);
    }

    private void enableTls(ChannelHandlerContext ctx, ByteBuf msg) {
        ChannelPipeline pipeline = ctx.pipeline();
        pipeline.addFirst(new SniHandler(configuration, nettySslContextFactory));
        enableSslUpstreamAndDownstream(ctx.channel());

        // re-unify (with SSL enabled)
        ctx.pipeline().fireChannelRead(msg.readBytes(actualReadableBytes()));
    }

    private boolean isHttp(ByteBuf msg) {
        String method = msg.toString(msg.readerIndex(), 8, StandardCharsets.US_ASCII);
        return method.startsWith("GET ") ||
            method.startsWith("POST ") ||
            method.startsWith("PUT ") ||
            method.startsWith("HEAD ") ||
            method.startsWith("OPTIONS ") ||
            method.startsWith("PATCH ") ||
            method.startsWith("DELETE ") ||
            method.startsWith("TRACE ") ||
            method.startsWith("CONNECT ");
    }

    private static final String H2C_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";

    private boolean isH2cPreface(ByteBuf msg) {
        if (actualReadableBytes() < H2C_PREFACE.length()) {
            return false;
        }
        String prefix = msg.toString(msg.readerIndex(), H2C_PREFACE.length(), StandardCharsets.US_ASCII);
        return H2C_PREFACE.equals(prefix);
    }

    private void switchToH2c(ChannelHandlerContext ctx, ByteBuf msg) {
        if (!isHttp2Enabled(ctx.channel())) {
            http2Enabled(ctx.channel());

            // cleartext h2c has no ALPN, so record HTTP/2 as the negotiated protocol on the channel
            // from the trusted server-side preface detection; this lets the request mapper recognise
            // genuine h2c (and capture the HTTP/2 stream id) without trusting a client-supplied header.
            SniHandler.setNegotiatedApplicationProtocol(ctx.channel(), HTTP_2);

            ChannelPipeline pipeline = ctx.pipeline();

            if (TcpChaosRegistry.getInstance().activeCount() > 0) {
                pipeline.addLast("tcp-chaos", new TcpChaosHandler());
            }

            if (configuration.grpcBidiStreamingEnabled()
                && httpState.getGrpcDescriptorStore() != null
                && httpState.getGrpcDescriptorStore().hasServices()) {
                switchToHttp2Multiplex(ctx, pipeline, false, null);
            } else {
                final Http2Connection connection = new DefaultHttp2Connection(true);
                // StreamRoutingHttpToHttp2ConnectionHandler rather than the stock handler: it routes
                // StreamAddressedHttpContent data frames onto the stream id the frame carries, instead
                // of onto the codec's single mutable currentStreamId, so concurrent streaming responses
                // no longer mis-route a later chunk of one stream onto another (GitHub issue #2667).
                final StreamRoutingHttpToHttp2ConnectionHandlerBuilder http2ConnectionHandlerBuilder = new StreamRoutingHttpToHttp2ConnectionHandlerBuilder()
                    // advertise (and have Netty enforce) the concurrent-stream limit explicitly
                    // rather than inheriting Netty's default -- see HTTP2_MAX_CONCURRENT_STREAMS
                    .initialSettings(Http2Settings.defaultSettings()
                        .maxConcurrentStreams(HTTP2_MAX_CONCURRENT_STREAMS))
                    .frameListener(
                        new DelegatingDecompressorFrameListener(
                            connection,
                            new InboundHttp2ToHttpAdapterBuilder(connection)
                                .maxContentLength(configuration.maxRequestBodySize())
                                .propagateSettings(true)
                                .validateHttpHeaders(false)
                                .build()
                        )
                    );
                if (mockServerLogger.isEnabledForInstance(TRACE)) {
                    http2ConnectionHandlerBuilder.frameLogger(new Http2FrameLogger(LogLevel.TRACE, PortUnificationHandler.class.getName()));
                }
                addLastIfNotPresent(pipeline, http2ConnectionHandlerBuilder.connection(connection).build());
                // Immediately downstream of the HTTP/2 connection handler, so every outbound response
                // head from the handlers below passes through it. Warns when a head lacks
                // x-http2-stream-id, which the codec would otherwise silently mis-route onto a new
                // server-initiated stream (a hang the client sees but the server never reports).
                addLastIfNotPresent(pipeline, new Http2StreamIdAuditHandler(mockServerLogger));
                addLastIfNotPresent(pipeline, new CallbackWebSocketServerHandler(httpState));
                addLastIfNotPresent(pipeline, new DashboardWebSocketHandler(httpState, false, false));
                if (configuration.mcpEnabled()) {
                    addLastIfNotPresent(pipeline, new McpStreamableHttpHandler(httpState, server, mcpSessionManager));
                }
                addLastIfNotPresent(pipeline, new MockServerHttpServerCodec(configuration, mockServerLogger, false, null, ctx.channel().localAddress()));
                addLastIfNotPresent(pipeline, new TraceContextHandler(configuration));
                addAltSvcHandlerIfEnabled(pipeline);
                if (httpState.getGrpcDescriptorStore() != null && httpState.getGrpcDescriptorStore().hasServices()) {
                    addLastIfNotPresent(pipeline, new GrpcToHttpResponseHandler(mockServerLogger, httpState.getGrpcDescriptorStore()));
                    addLastIfNotPresent(pipeline, new GrpcToHttpRequestHandler(configuration, mockServerLogger, httpState.getGrpcDescriptorStore()));
                }
                addLastIfNotPresent(pipeline, new HttpRequestHandler(configuration, server, httpState, actionHandler));
            }
            pipeline.remove(this);

            ctx.channel().attr(LOCAL_HOST_HEADERS).set(getLocalAddresses(ctx));

            ctx.fireChannelRead(msg.readBytes(actualReadableBytes()));
        }
    }

    private void switchToHttp2(ChannelHandlerContext ctx, ByteBuf msg) {
        if (!isHttp2Enabled(ctx.channel())) {
            http2Enabled(ctx.channel());

            ChannelPipeline pipeline = ctx.pipeline();

            if (TcpChaosRegistry.getInstance().activeCount() > 0) {
                pipeline.addLast("tcp-chaos", new TcpChaosHandler());
            }

            if (configuration.grpcBidiStreamingEnabled()
                && httpState.getGrpcDescriptorStore() != null
                && httpState.getGrpcDescriptorStore().hasServices()) {
                switchToHttp2Multiplex(ctx, pipeline, isSslEnabledUpstream(ctx.channel()), SniHandler.retrieveClientCertificates(mockServerLogger, ctx));
            } else {
                final Http2Connection connection = new DefaultHttp2Connection(true);
                // StreamRoutingHttpToHttp2ConnectionHandler rather than the stock handler: it routes
                // StreamAddressedHttpContent data frames onto the stream id the frame carries, instead
                // of onto the codec's single mutable currentStreamId, so concurrent streaming responses
                // no longer mis-route a later chunk of one stream onto another (GitHub issue #2667).
                final StreamRoutingHttpToHttp2ConnectionHandlerBuilder http2ConnectionHandlerBuilder = new StreamRoutingHttpToHttp2ConnectionHandlerBuilder()
                    // advertise (and have Netty enforce) the concurrent-stream limit explicitly
                    // rather than inheriting Netty's default -- see HTTP2_MAX_CONCURRENT_STREAMS
                    .initialSettings(Http2Settings.defaultSettings()
                        .maxConcurrentStreams(HTTP2_MAX_CONCURRENT_STREAMS))
                    .frameListener(
                        new DelegatingDecompressorFrameListener(
                            connection,
                            new InboundHttp2ToHttpAdapterBuilder(connection)
                                .maxContentLength(configuration.maxRequestBodySize())
                                .propagateSettings(true)
                                .validateHttpHeaders(false)
                                .build()
                        )
                    );
                if (mockServerLogger.isEnabledForInstance(TRACE)) {
                    http2ConnectionHandlerBuilder.frameLogger(new Http2FrameLogger(LogLevel.TRACE, PortUnificationHandler.class.getName()));
                }
                addLastIfNotPresent(pipeline, http2ConnectionHandlerBuilder.connection(connection).build());
                // Immediately downstream of the HTTP/2 connection handler, so every outbound response
                // head from the handlers below passes through it. Warns when a head lacks
                // x-http2-stream-id, which the codec would otherwise silently mis-route onto a new
                // server-initiated stream (a hang the client sees but the server never reports).
                addLastIfNotPresent(pipeline, new Http2StreamIdAuditHandler(mockServerLogger));
                // TODO(jamesdbloom) issue #2669. Http2MultiplexHandler remains the longer-term direction for this
                //  non-gRPC HTTP/2 path (giving every stream its own child channel). The concurrent
                //  chunk mis-routing that made it urgent is now fixed on the shared-connection
                //  architecture by StreamRoutingHttpToHttp2ConnectionHandler + StreamAddressedHttpContent
                //  (issue #2667), so the migration is no longer a correctness prerequisite.
                addLastIfNotPresent(pipeline, new CallbackWebSocketServerHandler(httpState));
                addLastIfNotPresent(pipeline, new DashboardWebSocketHandler(httpState, isSslEnabledUpstream(ctx.channel()), false));
                if (configuration.mcpEnabled()) {
                    addLastIfNotPresent(pipeline, new McpStreamableHttpHandler(httpState, server, mcpSessionManager));
                }
                addLastIfNotPresent(pipeline, new MockServerHttpServerCodec(configuration, mockServerLogger, isSslEnabledUpstream(ctx.channel()), SniHandler.retrieveClientCertificates(mockServerLogger, ctx), ctx.channel().localAddress()));
                addLastIfNotPresent(pipeline, new TraceContextHandler(configuration));
                addAltSvcHandlerIfEnabled(pipeline);
                if (httpState.getGrpcDescriptorStore() != null && httpState.getGrpcDescriptorStore().hasServices()) {
                    addLastIfNotPresent(pipeline, new GrpcToHttpResponseHandler(mockServerLogger, httpState.getGrpcDescriptorStore()));
                    addLastIfNotPresent(pipeline, new GrpcToHttpRequestHandler(configuration, mockServerLogger, httpState.getGrpcDescriptorStore()));
                }
                addLastIfNotPresent(pipeline, new HttpRequestHandler(configuration, server, httpState, actionHandler));
            }
            pipeline.remove(this);

            ctx.channel().attr(LOCAL_HOST_HEADERS).set(getLocalAddresses(ctx));

            // fire message back through pipeline
            ctx.fireChannelRead(msg.readBytes(actualReadableBytes()));
        }
    }

    private void switchToHttp2Multiplex(ChannelHandlerContext ctx, ChannelPipeline pipeline, boolean sslEnabled, java.security.cert.Certificate[] clientCertificates) {
        // NOTE: this multiplex pipeline carries EVERY stream on the connection -- ordinary HTTP
        // GET/POST/SSE as well as gRPC -- so its per-stream child pipeline mirrors the HTTP/1.1 and
        // connection-adapter paths: it decompresses content-encoding request bodies (via an
        // HttpContentDecompressor installed by GrpcMultiplexChildInitializer.installReAggregatingChain)
        // and it disables inbound header validation (Http2StreamFrameToHttpObjectCodec validateHeaders
        // = false) so unusual request header values are recorded rather than reset. gRPC's own message
        // compression is a separate concern carried by the grpc-encoding header (handled by
        // GrpcFrameCodec), on which HttpContentDecompressor is inert.
        Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forServer()
            .initialSettings(Http2Settings.defaultSettings()
                .maxConcurrentStreams(HTTP2_MAX_CONCURRENT_STREAMS)
                .maxFrameSize(configuration.maxRequestBodySize() < Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                    ? Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                    : Math.min(configuration.maxRequestBodySize(), Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND)));
        if (mockServerLogger.isEnabledForInstance(TRACE)) {
            frameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.TRACE, PortUnificationHandler.class.getName()));
        }
        addLastIfNotPresent(pipeline, frameCodecBuilder.build());
        addLastIfNotPresent(pipeline, new Http2MultiplexHandler(
            new GrpcMultiplexChildInitializer(
                configuration, server, httpState, actionHandler,
                mockServerLogger, mcpSessionManager,
                sslEnabled, clientCertificates
            )
        ));
    }

    private void switchToHttp(ChannelHandlerContext ctx, ByteBuf msg) {
        if (!isHttpEnabled(ctx.channel())) {
            httpEnabled(ctx.channel());

            ChannelPipeline pipeline = ctx.pipeline();

            if (TcpChaosRegistry.getInstance().activeCount() > 0) {
                pipeline.addLast("tcp-chaos", new TcpChaosHandler());
            }
            addLastIfNotPresent(pipeline, new HttpServerCodec(
                configuration.maxInitialLineLength(),
                configuration.maxHeaderSize(),
                configuration.maxChunkSize()
            ));
            addLastIfNotPresent(pipeline, preserveHeadersNettyRemoves);
            addLastIfNotPresent(pipeline, new HttpContentDecompressor());
            addLastIfNotPresent(pipeline, httpContentLengthRemover);
            addLastIfNotPresent(pipeline, new EarlyMatchingHandler(configuration, httpState, actionHandler, isSslEnabledUpstream(ctx.channel())));
            addLastIfNotPresent(pipeline, new HttpObjectAggregator(configuration.maxRequestBodySize()));
            if (configuration.tlsMutualAuthenticationRequired() && configuration.controlPlaneTLSMutualAuthenticationRequired() && !isSslEnabledUpstream(ctx.channel())) {
                HttpResponse httpResponse = response()
                    .withStatusCode(426)
                    .withHeader("Upgrade", "TLS/1.2, HTTP/1.1")
                    .withHeader("Connection", "Upgrade");
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setMessageFormat("no tls for connection:{}returning response:{}")
                            .setArguments(ctx.channel().localAddress(), httpResponse)
                    );
                }
                ctx
                    .channel()
                    .writeAndFlush(mockServerHttpResponseToFullHttpResponse
                        .mapMockServerResponseToNettyResponse(
                            httpResponse
                        ).get(0)
                    )
                    .addListener((ChannelFuture future) -> future.channel().disconnect());
            } else {
                addLastIfNotPresent(pipeline, new CallbackWebSocketServerHandler(httpState));
                addLastIfNotPresent(pipeline, new DashboardWebSocketHandler(httpState, isSslEnabledUpstream(ctx.channel()), false));
                if (configuration.mcpEnabled()) {
                    addLastIfNotPresent(pipeline, new McpStreamableHttpHandler(httpState, server, mcpSessionManager));
                }
                addLastIfNotPresent(pipeline, new MockServerHttpServerCodec(configuration, mockServerLogger, isSslEnabledUpstream(ctx.channel()), SniHandler.retrieveClientCertificates(mockServerLogger, ctx), ctx.channel().localAddress()));
                addLastIfNotPresent(pipeline, new TraceContextHandler(configuration));
                addAltSvcHandlerIfEnabled(pipeline);
                // gRPC-Web works over HTTP/1.1, so add gRPC handlers here too
                if (httpState.getGrpcDescriptorStore() != null && httpState.getGrpcDescriptorStore().hasServices()) {
                    addLastIfNotPresent(pipeline, new GrpcToHttpResponseHandler(mockServerLogger, httpState.getGrpcDescriptorStore()));
                    addLastIfNotPresent(pipeline, new GrpcToHttpRequestHandler(configuration, mockServerLogger, httpState.getGrpcDescriptorStore()));
                }
                addLastIfNotPresent(pipeline, new HttpRequestHandler(configuration, server, httpState, actionHandler));
                pipeline.remove(this);

                ctx.channel().attr(LOCAL_HOST_HEADERS).set(getLocalAddresses(ctx));

                // fire message back through pipeline
                ctx.fireChannelRead(msg.readBytes(actualReadableBytes()));
            }
        }
    }

    private boolean isProxyConnected(ByteBuf msg) {
        return msg.toString(msg.readerIndex(), 8, StandardCharsets.US_ASCII).startsWith(PROXIED);
    }

    private void switchToProxyConnected(ChannelHandlerContext ctx, ByteBuf msg) {
        String message = readMessage(msg);
        if (message.startsWith(PROXIED_SECURE)) {
            String[] hostParts = HttpRequest.splitHostPort(StringUtils.substringAfter(message, PROXIED_SECURE));
            int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : 443;
            enableSslUpstreamAndDownstream(ctx.channel());
            setProxyingRequest(ctx, Boolean.TRUE);
            setRemoteAddress(ctx, new InetSocketAddress(hostParts[0], port));
        } else if (message.startsWith(PROXIED)) {
            String[] hostParts = HttpRequest.splitHostPort(StringUtils.substringAfter(message, PROXIED));
            int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : 80;
            setProxyingRequest(ctx, Boolean.TRUE);
            setRemoteAddress(ctx, new InetSocketAddress(hostParts[0], port));
        }
        ctx.writeAndFlush(Unpooled.copiedBuffer((PROXIED_RESPONSE + message).getBytes(StandardCharsets.UTF_8)));
    }

    private String readMessage(ByteBuf msg) {
        byte[] bytes = new byte[actualReadableBytes()];
        msg.readBytes(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private void switchToBinaryRequestProxying(ChannelHandlerContext ctx, ByteBuf msg) {
        addLastIfNotPresent(ctx.pipeline(), new BinaryRequestProxyingHandler(configuration, httpState.getMockServerLogger(), httpState.getScheduler(), actionHandler.getHttpClient(), httpState));

        // fire message back through pipeline
        ctx.fireChannelRead(msg.readBytes(actualReadableBytes()));
    }

    private Set<String> getLocalAddresses(ChannelHandlerContext ctx) {
        SocketAddress localAddress = ctx.channel().localAddress();
        Set<String> localAddresses = null;
        if (localAddress instanceof InetSocketAddress) {
            InetSocketAddress inetSocketAddress = (InetSocketAddress) localAddress;
            String portExtension = calculatePortExtension(inetSocketAddress, isSslEnabledUpstream(ctx.channel()));
            PortBinding cacheKey = new PortBinding(inetSocketAddress, portExtension);
            localAddresses = localAddressesCache.get(cacheKey);
            if (localAddresses == null) {
                localAddresses = calculateLocalAddresses(inetSocketAddress, portExtension);
                localAddressesCache.put(cacheKey, localAddresses);
            }
        }
        return (localAddresses == null) ? Collections.emptySet() : localAddresses;
    }

    private String calculatePortExtension(InetSocketAddress inetSocketAddress, boolean sslEnabledUpstream) {
        String portExtension;
        if (((inetSocketAddress.getPort() == 443) && sslEnabledUpstream)
            || ((inetSocketAddress.getPort() == 80) && !sslEnabledUpstream)) {
            portExtension = "";
        } else {
            portExtension = ":" + inetSocketAddress.getPort();
        }
        return portExtension;
    }

    private Set<String> calculateLocalAddresses(InetSocketAddress localAddress, String portExtension) {
        InetAddress socketAddress = localAddress.getAddress();
        Set<String> localAddresses = new HashSet<>();
        localAddresses.add(socketAddress.getHostAddress() + portExtension);
        localAddresses.add(socketAddress.getCanonicalHostName() + portExtension);
        localAddresses.add(socketAddress.getHostName() + portExtension);
        localAddresses.add("localhost" + portExtension);
        localAddresses.add("127.0.0.1" + portExtension);
        localAddresses.add("::1" + portExtension);
        localAddresses.add("[::1]" + portExtension);
        localAddresses.add("0:0:0:0:0:0:0:1" + portExtension);
        return unmodifiableSet(localAddresses);
    }

    private void addAltSvcHandlerIfEnabled(ChannelPipeline pipeline) {
        int h3Port = configuration.http3Port();
        if (h3Port > 0 && configuration.http3AdvertiseAltSvc()) {
            addLastIfNotPresent(pipeline, new AltSvcHeaderHandler(h3Port, configuration.http3AltSvcMaxAge()));
        }
    }

    private void addLastIfNotPresent(ChannelPipeline pipeline, ChannelHandler channelHandler) {
        if (pipeline.get(channelHandler.getClass()) == null) {
            pipeline.addLast(channelHandler);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable throwable) {
        if (connectionClosedException(throwable)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by port unification handler -> closing pipeline " + ctx.channel())
                    .setThrowable(throwable)
            );
        } else if (sslHandshakeException(throwable)) {
            String message = throwable.getMessage() != null ? throwable.getMessage() : "";
            String messageLower = message.toLowerCase();
            String certInfo = " Configured x509CertificatePath=\"" + configuration.x509CertificatePath()
                + "\" certificateAuthorityCertificate=\"" + configuration.certificateAuthorityCertificate() + "\".";
            if (messageLower.contains("certificate_unknown") || messageLower.contains("unknown_ca")) {
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("TLS handshake failure:" + NEW_LINE + NEW_LINE + " Client does not trust MockServer Certificate Authority for:{}See https://mock-server.com/mock_server/HTTPS_TLS.html to enable the client to trust MockServer Certificate Authority." + certInfo + NEW_LINE)
                            .setArguments(ctx.channel())
                            .setThrowable(throwable)
                    );
                }
            } else if (!message.contains("close_notify during handshake")) {
                String diagnosticHint;
                if (messageLower.contains("bad_certificate")) {
                    diagnosticHint = "the certificate was rejected by the client - check expiry and chain.";
                } else if (messageLower.contains("handshake_failure")) {
                    diagnosticHint = "TLS protocol or cipher mismatch.";
                } else if (messageLower.contains("no_certificate")) {
                    diagnosticHint = "client did not send a certificate (mTLS may be required).";
                } else {
                    diagnosticHint = "unknown cause.";
                }
                if (mockServerLogger != null) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("TLS handshake failure while a client attempted to connect to " + ctx.channel() + " - " + diagnosticHint + certInfo)
                            .setThrowable(throwable)
                    );
                }
            }
            // The sslHandshakeException branch above covers SSLException-family faults (incl.
            // NotSslRecordException, a subclass of SSLException) but NOT a plain DecoderException;
            // the branch below catches that residual so a decoder fault is not silently dropped.
        } else if (isSslOrDecoderFault(throwable)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("SSL or decoder fault caught by port unification handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel()))
                    .setThrowable(throwable)
            );
        }
        closeOnFlush(ctx.channel());
    }
}

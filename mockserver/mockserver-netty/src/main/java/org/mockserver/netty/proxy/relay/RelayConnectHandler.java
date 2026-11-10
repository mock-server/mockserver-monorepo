package org.mockserver.netty.proxy.relay;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v5.Socks5CommandRequestDecoder;
import org.mockserver.codec.BoundedZstdHttpContentDecompressor;
import org.mockserver.codec.HttpChunkLineLimiter;
import org.mockserver.codec.HttpLineEndSplitGuard;
import org.mockserver.codec.HttpObjectAggregators;
import org.mockserver.socket.NettyAllocator;
import org.mockserver.socket.NettyTransport;
import io.netty.handler.codec.http2.*;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.NetUtil;
import io.netty.util.ReferenceCountUtil;
import io.netty.util.concurrent.ScheduledFuture;
import org.mockserver.codec.StreamingAwareHttpObjectAggregator;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.LoggingHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Protocol;
import org.mockserver.netty.connection.Http2StreamWriteStallHandler;
import org.mockserver.netty.controlplane.ControlPlaneListenerSettings;
import org.mockserver.netty.connection.HttpExchangeTracker;
import org.mockserver.netty.connection.InboundConnectionActivity;
import org.mockserver.netty.connection.TunnelProxyClient;
import org.mockserver.netty.connection.WriteStallTimeoutHandler;
import org.mockserver.netty.unification.Http2RequestHeaderLimit;
import org.mockserver.netty.unification.HttpServerCodecResponsePairing;
import org.mockserver.netty.unification.HttpServerCodecs;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.slf4j.event.Level;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.closeOnFlush;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.model.Protocol.HTTP_2;
import static org.mockserver.netty.unification.PortUnificationHandler.*;
import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;
import static org.slf4j.event.Level.TRACE;

@ChannelHandler.Sharable
public abstract class RelayConnectHandler<T> extends SimpleChannelInboundHandler<T> {

    public static final String PROXIED = "PROXIED_";
    public static final String PROXIED_SECURE = PROXIED + "SECURE_";
    public static final String PROXIED_RESPONSE = "PROXIED_RESPONSE_";
    private static final byte[] PROXIED_RESPONSE_BYTES = PROXIED_RESPONSE.getBytes(StandardCharsets.US_ASCII);
    private static final InetAddress IPV4_LOOPBACK = NetUtil.createInetAddressFromIpAddressString("127.0.0.1");
    private static final String CLIENT_LEG = "client";
    private static final String LOOPBACK_LEG = "loopback";
    private final Configuration configuration;
    private final LifeCycle server;
    private final MockServerLogger mockServerLogger;
    protected final String host;
    protected final int port;

    public RelayConnectHandler(Configuration configuration, LifeCycle server, MockServerLogger mockServerLogger, String host, int port) {
        this.configuration = configuration;
        this.server = server;
        this.mockServerLogger = mockServerLogger;
        this.host = host;
        this.port = port;
    }

    @Override
    public void channelRead0(final ChannelHandlerContext proxyClientCtx, final T request) {
        // MockServer itself even when the connection names a destination (proxyRemoteHost or a transparent-proxy
        // original destination): only MockServer answers the PROXIED_ preamble, and it forwards the tunnelled
        // requests to the CONNECT target, checking each against forwardProxyBlockPrivateNetworks
        final InetSocketAddress remoteSocket = ownListener(server);
        ControlPlaneListenerSettings sourcePolicy = ControlPlaneListenerSettings.of(proxyClientCtx.channel());
        InetSocketAddress originalSource = sourcePolicy != null
            ? sourcePolicy.source(proxyClientCtx.channel())
            : (proxyClientCtx.channel().remoteAddress() instanceof InetSocketAddress peer ? peer : null);
        Bootstrap bootstrap = new Bootstrap()
            .group(proxyClientCtx.channel().eventLoop())
            .channel(NettyTransport.socketChannelClassFor(proxyClientCtx.channel().eventLoop()))
            .option(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
            .handler(new ChannelInboundHandlerAdapter() {
                // confined to the proxy client's event loop, which this bootstrap shares
                private boolean tunnelEstablished;

                @Override
                public void channelActive(final ChannelHandlerContext mockServerCtx) {
                    RelayLoopbackAddresses.register(mockServerCtx.channel(), proxyClientCtx.channel(), originalSource);
                    String hostForMessage = host.contains(":") ? "[" + host + "]" : host;
                    if (isSslEnabledUpstream(proxyClientCtx.channel())) {
                        mockServerCtx.writeAndFlush(Unpooled.copiedBuffer((PROXIED_SECURE + hostForMessage + ":" + port).getBytes(StandardCharsets.UTF_8)));
                    } else {
                        mockServerCtx.writeAndFlush(Unpooled.copiedBuffer((PROXIED + hostForMessage + ":" + port).getBytes(StandardCharsets.UTF_8)));
                    }
                }

                @Override
                public void channelRead(ChannelHandlerContext mockServerCtx, Object msg) {
                    // once the tunnel is set up everything read here is the destination's, whatever it starts with
                    if (!tunnelEstablished && msg instanceof ByteBuf && startsWithProxiedResponse((ByteBuf) msg)) {
                        tunnelEstablished = true;
                        mockServerCtx.pipeline().addLast(new UnconfiguredTunnelLegExceptionHandler(mockServerLogger, LOOPBACK_LEG));
                        // this branch consumes the message (it does not forward it via fireChannelRead), so the
                        // inbound ByteBuf must be released here to avoid leaking one pooled buffer per tunnel setup
                        try {
                            proxyClientCtx
                                .writeAndFlush(successResponse(request))
                                .addListener((ChannelFutureListener) channelFuture -> {
                                    if (!channelFuture.isSuccess()) {
                                        proxyClientCtx.close();
                                        mockServerCtx.close();
                                        return;
                                    }
                                    removeCodecSupport(proxyClientCtx);
                                    // the tunnel's own codec is given a tracker of its own, after it
                                    removeHandler(proxyClientCtx.pipeline(), HttpExchangeTracker.class);
                                    // until the relay handlers are installed nothing else closes the loopback with the client;
                                    // once they are, UpstreamProxyRelayHandler does, after a request still being written
                                    proxyClientCtx.channel().closeFuture().addListener(closed -> {
                                        if (proxyClientCtx.pipeline().get(UpstreamProxyRelayHandler.class) == null) {
                                            RelayLegClose.afterFlush(mockServerCtx.channel());
                                        }
                                    });

                                    // downstream (to proxy client)
                                    ChannelPipeline pipelineToProxyClient = proxyClientCtx.channel().pipeline();

                                    if (isTlsDetectionDeferred(proxyClientCtx.channel())) {
                                        // SOCKS path: the tunnelled protocol was unknown at wiring time (the client's
                                        // ClientHello only arrives after this SOCKS reply). Remove the byte-driven
                                        // handlers the SOCKS setup left in the proxy-client pipeline so nothing races the
                                        // probe: the leftover PortUnificationHandler would re-detect TLS and install a
                                        // second SniHandler (the #2685 failure), and the spent SOCKS command decoder would
                                        // otherwise wrap the first bytes. Then classify the first tunnelled bytes and
                                        // provision the loopback to match - reading the real ALPN result for TLS - instead
                                        // of guessing from the destination port (issue #2685).
                                        removeHandler(pipelineToProxyClient, PortUnificationHandler.class);
                                        removeSocksCommandDecoders(pipelineToProxyClient);
                                        pipelineToProxyClient.addLast(new RelayTlsDetectionHandler(mockServerCtx));
                                        pipelineToProxyClient.addLast(new UnconfiguredTunnelLegExceptionHandler(mockServerLogger, CLIENT_LEG));
                                    } else {
                                        // Unreachable by construction. Every RelayConnectHandler subclass is installed on
                                        // exactly two paths - HttpRequestHandler CONNECT and SocksProxyHandler.forwardConnection
                                        // (SOCKS) - and both call PortUnificationHandler.deferTlsDetection(channel) before wiring
                                        // this handler, so the branch above always runs and the tunnelled protocol is classified
                                        // from the first tunnelled bytes (TLS+ALPN h2/http1.1, cleartext h2c, plaintext HTTP/1.1, or binary).
                                        // Reaching here means a NEW caller installed this handler without deferring detection first.
                                        // This used to fall through to an HTTP/1.1-only tunnel, which silently dropped TLS+ALPN HTTP/2
                                        // and cleartext h2c - the exact silent-downgrade class behind #2641/#2667/#2669/#2683. Log
                                        // loudly and tear the tunnel down deterministically. A bare throw here would NOT do that: this
                                        // runs inside a ChannelFutureListener, whose exceptions Netty's DefaultPromise catches and only
                                        // logs at WARN - it neither fires exceptionCaught nor closes the channel - so the codecs already
                                        // stripped above and the success reply already sent would leave the tunnel half-configured (the
                                        // client hangs and the loopback channel leaks). Closing both legs turns that hang into a prompt
                                        // connection close, so the missing deferTlsDetection(...) call is fixed rather than shipped.
                                        mockServerLogger.logEvent(
                                            new LogEntry()
                                                .setLogLevel(Level.ERROR)
                                                .setMessageFormat("RelayConnectHandler reached tunnel setup with TLS detection NOT deferred; "
                                                    + "the caller that installed this handler must call PortUnificationHandler.deferTlsDetection(channel) "
                                                    + "first (as HttpRequestHandler CONNECT and SocksProxyHandler.forwardConnection do). Closing the tunnel "
                                                    + "rather than silently provisioning an HTTP/1.1-only relay that would drop HTTP/2.")
                                        );
                                        proxyClientCtx.close();
                                        mockServerCtx.close();
                                    }
                                });
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    } else {
                        // ownership of the message passes to the next handler, which is responsible for releasing it
                        mockServerCtx.fireChannelRead(msg);
                    }
                }

                @Override
                public void channelInactive(ChannelHandlerContext mockServerCtx) {
                    // Closed before the tunnel was set up - for example refused by maxInboundConnections.
                    // Fail the client now; nothing else would ever answer its CONNECT/SOCKS request.
                    if (!tunnelEstablished) {
                        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.WARN)
                                    .setMessageFormat("tunnel connection to:{}closed before the tunnel was established, failing the proxy client request")
                                    .setArguments(remoteSocket)
                            );
                        }
                        Channel proxyClientChannel = proxyClientCtx.channel();
                        proxyClientChannel.writeAndFlush(failureResponse(request));
                        closeOnFlush(proxyClientChannel);
                    } else if (mockServerCtx.pipeline().get(DownstreamProxyRelayHandler.class) == null) {
                        // the tunnel's protocol is not yet known, so no relay handler is there to close the client's leg
                        closeOnFlush(proxyClientCtx.channel());
                    }
                    mockServerCtx.fireChannelInactive();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext mockServerCtx, Throwable cause) {
                    // Before set-up the only handler here is this one, so an I/O error (typically a reset
                    // from a refused loopback) would otherwise reach the pipeline tail as a Netty WARN;
                    // channelInactive answers the client. Afterwards UnconfiguredTunnelLegExceptionHandler,
                    // then the relay handlers, own errors.
                    if (tunnelEstablished) {
                        mockServerCtx.fireExceptionCaught(cause);
                    } else {
                        mockServerCtx.close();
                    }
                }
            });

        bootstrap.connect(remoteSocket).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                failure("Connection failed to " + remoteSocket, future.cause(), proxyClientCtx, failureResponse(request));
            }
        });
    }

    static boolean startsWithProxiedResponse(ByteBuf msg) {
        if (msg.readableBytes() < PROXIED_RESPONSE_BYTES.length) {
            return false;
        }
        int start = msg.readerIndex();
        for (int i = 0; i < PROXIED_RESPONSE_BYTES.length; i++) {
            if (msg.getByte(start + i) != PROXIED_RESPONSE_BYTES[i]) {
                return false;
            }
        }
        return true;
    }

    /**
     * MockServer's own listener, at an address it accepts: the address it is bound to ({@code localBoundIP}), or
     * 127.0.0.1 for a wildcard bind, which every wildcard listener (IPv4, or dual-stack IPv6) accepts. Never the
     * wildcard address itself: a dual-stack epoll socket on Linux dials that as ::1, which an IPv4 listener refuses.
     */
    static InetSocketAddress ownListener(LifeCycle server) {
        InetAddress bound = server.getLocalBoundAddress();
        // the port stays a separate getLocalPort() call: ConnectRelayLoopbackCloseIntegrationTest overrides it
        return new InetSocketAddress(bound == null || bound.isAnyLocalAddress() ? IPV4_LOOPBACK : bound, server.getLocalPort());
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        failure("Exception caught by CONNECT proxy handler -> closing pipeline ", cause, ctx, failureResponse(null));
    }

    private void failure(String message, Throwable cause, ChannelHandlerContext ctx, Object response) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(message)
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault -> " + message + sniDescription(ctx.channel()))
                        .setThrowable(boundedFault(cause))
                );
            }
        }
        Channel channel = ctx.channel();
        channel.writeAndFlush(response);
        if (channel.isActive()) {
            channel.writeAndFlush(Unpooled.EMPTY_BUFFER).addListener(ChannelFutureListener.CLOSE);
        }
    }

    protected abstract void removeCodecSupport(ChannelHandlerContext ctx);

    protected abstract Object successResponse(Object request);

    protected abstract Object failureResponse(Object request);

    protected void removeHandler(ChannelPipeline pipeline, Class<? extends ChannelHandler> handlerType) {
        if (pipeline.get(handlerType) != null) {
            pipeline.remove(handlerType);
        }
    }

    protected void removeHandler(ChannelPipeline pipeline, ChannelHandler channelHandler) {
        if (pipeline.toMap().containsValue(channelHandler)) {
            pipeline.remove(channelHandler);
        }
    }

    /**
     * Terminate the proxy client's TLS here, in the relay, so the ALPN-negotiated protocol can be read from
     * THIS handshake and the loopback provisioned to match (h2 vs HTTP/1.1) - the path the CONNECT proxy
     * takes. The leftover PortUnificationHandler is removed first (null-safe no-op if already gone) so this
     * SslHandler is the sole TLS terminator rather than racing a second SniHandler (issue #2685). The
     * SslHandler is added last: on the CONNECT path the client's ClientHello arrives fresh, and on the SOCKS
     * path {@link RelayTlsDetectionHandler} forwards the buffered ClientHello to it when it removes itself.
     */
    private void terminateClientTlsThenConfigure(ChannelPipeline pipelineToMockServer, ChannelPipeline pipelineToProxyClient,
                                                 ChannelHandlerContext mockServerCtx, ChannelHandlerContext proxyClientCtx) {
        removeHandler(pipelineToProxyClient, PortUnificationHandler.class);
        SslHandler sslHandler = nettySslContextFactory(proxyClientCtx.channel()).createServerSslContext().newHandler(proxyClientCtx.alloc());
        // Bound the CONNECT MITM (proxy-client-facing) TLS handshake by the configured connection timeout
        // instead of Netty's fixed 10,000ms default, so a slow or malicious tunnelled client cannot hold an
        // unauthenticated handshake open for the full 10s. A non-positive value keeps Netty's default.
        if (configuration != null) {
            Long handshakeTimeoutMillis = configuration.socketConnectionTimeoutInMillis();
            if (handshakeTimeoutMillis != null && handshakeTimeoutMillis > 0) {
                sslHandler.setHandshakeTimeoutMillis(handshakeTimeoutMillis);
            }
        }
        // after the leg's exception handler: a failed handshake installs the relay's handlers before it is fired on
        pipelineToProxyClient.addLast(sslHandler);

        sslHandler.handshakeFuture().addListener(handshakeFuture -> {
            if (handshakeFuture.isSuccess()) {
                Protocol negotiated = getALPNProtocol(mockServerLogger, proxyClientCtx);
                if (negotiated == null) {
                    String alpn = sslHandler.applicationProtocol();
                    if (alpn != null && alpn.equalsIgnoreCase(ApplicationProtocolNames.HTTP_2)) {
                        negotiated = Protocol.HTTP_2;
                    }
                }
                boolean http2EnabledDownstream = HTTP_2.equals(negotiated);
                configurePipelines(pipelineToMockServer, pipelineToProxyClient, mockServerCtx, proxyClientCtx, http2EnabledDownstream, false);
            } else {
                logFailedClientHandshake(mockServerLogger, handshakeFuture.cause());
                configurePipelines(pipelineToMockServer, pipelineToProxyClient, mockServerCtx, proxyClientCtx, false, false);
            }
        });
    }

    /**
     * Netty fails the handshake with the exception it throws, so bytes that are not a TLS record arrive here as a
     * {@code NotSslRecordException} holding a hex dump of them.
     */
    static void logFailedClientHandshake(MockServerLogger mockServerLogger, Throwable cause) {
        if (mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.TRACE)
                    .setMessageFormat("SSL handshake failed, defaulting to HTTP/1.1")
                    .setThrowable(boundedFault(cause))
            );
        }
    }

    private void removeSocksCommandDecoders(ChannelPipeline pipeline) {
        // the SOCKS command decoder is spent once the CONNECT command has been read; in its terminal state it
        // merely forwards raw bytes, but removing it keeps the TLS probe as the first byte-driven handler.
        removeHandler(pipeline, Socks5CommandRequestDecoder.class);
        removeHandler(pipeline, Socks4ServerDecoder.class);
    }

    /**
     * One-shot probe for the tunnel's protocol, mirroring Netty's {@code OptionalSslHandler}: it classifies the first
     * tunnelled bytes ({@link TunnelFirstBytes}) and provisions the loopback accordingly, then hands the buffered bytes
     * to the handler it installed and removes itself. A client sends nothing through the tunnel until it has the
     * CONNECT or SOCKS reply, so this - not the destination port - is the earliest trustworthy signal of the tunnelled
     * protocol (issue #2685). Four outcomes:
     * <ul>
     *   <li>a TLS record -&gt; terminate the tunnelled TLS here and read its ALPN, so {@code h2} over TLS is
     *       provisioned when the client negotiated it;</li>
     *   <li>the cleartext h2c prior-knowledge preface -&gt; cleartext HTTP/2 on both legs, which MockServer's own
     *       {@link PortUnificationHandler} re-detects as h2c on the loopback, so both legs agree;</li>
     *   <li>an HTTP/1.x request line, whatever its method -&gt; HTTP/1.1;</li>
     *   <li>anything else, or a client that sends nothing for {@code forwardBinaryServerFirstWaitMillis} -&gt; a binary
     *       connection to the tunnel's destination, the bytes passed through as they are.</li>
     * </ul>
     * Bytes that are only the start of one of these are held until the client has been silent for
     * {@link PortUnificationHandler#UNDECIDED_PROTOCOL_WAIT_MILLIS}, as on MockServer's own port.
     * Never {@code @Sharable}: a fresh instance per tunnel, as a {@link ByteToMessageDecoder} requires.
     */
    private final class RelayTlsDetectionHandler extends ByteToMessageDecoder {

        private final ChannelHandlerContext mockServerCtx;
        private ScheduledFuture<?> silence;
        private boolean noMoreToCome;

        private RelayTlsDetectionHandler(ChannelHandlerContext mockServerCtx) {
            this.mockServerCtx = mockServerCtx;
        }

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            Long serverFirstWaitMillis = configuration.forwardBinaryServerFirstWaitMillis();
            if (serverFirstWaitMillis != null && serverFirstWaitMillis > 0 && !configuration.assumeAllRequestsAreHttp()) {
                startWait(ctx, serverFirstWaitMillis);
            }
        }

        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            stopWait();
            TunnelFirstBytes.Protocol protocol = TunnelFirstBytes.classify(in, configuration.http2Enabled(), configuration.assumeAllRequestsAreHttp(), configuration.maxInitialLineLength(), noMoreToCome);
            ChannelPipeline pipelineToProxyClient = ctx.pipeline();
            ChannelPipeline pipelineToMockServer = mockServerCtx.channel().pipeline();
            switch (protocol) {
                case NEED_MORE:
                    startWait(ctx, UNDECIDED_PROTOCOL_WAIT_MILLIS);
                    return;
                case TLS:
                    // terminateClientTlsThenConfigure adds the SslHandler after this decoder, which hands it the
                    // buffered ClientHello below
                    enableSslUpstreamAndDownstream(ctx.channel());
                    terminateClientTlsThenConfigure(pipelineToMockServer, pipelineToProxyClient, mockServerCtx, ctx);
                    break;
                case BINARY:
                    configureBinaryPassthrough(mockServerCtx, ctx);
                    break;
                default:
                    // the codecs are added after this decoder, which hands them the buffered request bytes below
                    configurePipelines(pipelineToMockServer, pipelineToProxyClient, mockServerCtx, ctx, protocol == TunnelFirstBytes.Protocol.H2C, true);
            }
            // Handed on here, not left for handlerRemoved, which follows them with a channelReadComplete in the middle
            // of the socket's read. The tunnel's HTTP/2 handler flushes on that, and a flush to a client that has
            // already gone fails and closes the channel with the rest of its request still unread.
            out.add(in.readRetainedSlice(in.readableBytes()));
            pipelineToProxyClient.remove(this);
        }

        @Override
        protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
            noMoreToCome = true;
            super.decodeLast(ctx, in, out);
        }

        @Override
        protected void handlerRemoved0(ChannelHandlerContext ctx) {
            stopWait();
        }

        /**
         * Waits for the client's silence: before any byte, for a server that speaks first; after some, for the rest of
         * them. Silence is not counted while reads are paused, for what was sent may be waiting unread.
         */
        private void startWait(ChannelHandlerContext ctx, long waitMillis) {
            stopWait();
            silence = ctx.executor().schedule(() -> {
                silence = null;
                if (ctx.isRemoved() || !ctx.channel().isActive()) {
                    return;
                }
                if (!ctx.channel().config().isAutoRead()) {
                    startWait(ctx, waitMillis);
                } else if (internalBuffer().isReadable()) {
                    noMoreToCome = true;
                    try {
                        // an empty read classifies the bytes already held
                        channelRead(ctx, Unpooled.EMPTY_BUFFER);
                        channelReadComplete(ctx);
                    } catch (Exception exception) {
                        ctx.fireExceptionCaught(exception);
                    } finally {
                        noMoreToCome = false;
                    }
                } else {
                    serverSpeaksFirst(ctx, waitMillis);
                }
            }, waitMillis, TimeUnit.MILLISECONDS);
        }

        private void stopWait() {
            if (silence != null) {
                silence.cancel(false);
                silence = null;
            }
        }

        private void serverSpeaksFirst(ChannelHandlerContext ctx, long waitMillis) {
            Channel accepted = TunnelProxyClient.acceptedLoopbackOf(ctx.channel());
            if (accepted == null) {
                return;
            }
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setMessageFormat("client of tunnel from:{}to:{}sent nothing for:{}ms (forwardBinaryServerFirstWaitMillis), relaying it as binary for a server that speaks first")
                        .setArguments(ctx.channel().remoteAddress(), host + ":" + port, waitMillis)
                );
            }
            configureBinaryPassthrough(mockServerCtx, ctx);
            ctx.pipeline().remove(this);
            accepted.eventLoop().execute(() -> accepted.pipeline().fireUserEventTriggered(TunnelProxyClient.ServerSpeaksFirst.INSTANCE));
        }
    }

    /**
     * Both legs pass bytes through as they are, and MockServer's end of the loopback, told by the mark, takes them as a
     * binary connection to the tunnel's destination without looking at them again.
     */
    private void configureBinaryPassthrough(ChannelHandlerContext mockServerCtx, ChannelHandlerContext proxyClientCtx) {
        Channel proxyClient = proxyClientCtx.channel();
        TunnelProxyClient.markCarriesBinary(proxyClient);
        // a database or a broker may legitimately stay silent for long periods
        InboundConnectionActivity.markLongLived(proxyClient);
        ChannelPipeline pipelineToMockServer = mockServerCtx.channel().pipeline();
        removeHandler(pipelineToMockServer, UnconfiguredTunnelLegExceptionHandler.class);
        removeHandler(proxyClientCtx.pipeline(), UnconfiguredTunnelLegExceptionHandler.class);
        pipelineToMockServer.addFirst(new LoopbackResetRelay(proxyClient));
        TunnelBinaryPassthrough.install(proxyClient, mockServerCtx.channel(), mockServerLogger);
    }

    private void configurePipelines(ChannelPipeline pipelineToMockServer, ChannelPipeline pipelineToProxyClient,
                                   ChannelHandlerContext mockServerCtx, ChannelHandlerContext proxyClientCtx,
                                   boolean http2EnabledDownstream, boolean addedWhileReading) {
        // the relay's handlers, added last below, take each leg's exceptions from here on
        removeHandler(pipelineToMockServer, UnconfiguredTunnelLegExceptionHandler.class);
        removeHandler(pipelineToProxyClient, UnconfiguredTunnelLegExceptionHandler.class);
        pipelineToMockServer.addFirst(new LoopbackResetRelay(proxyClientCtx.channel()));
        if (isSslEnabledDownstream(proxyClientCtx.channel())) {
            // the loopback connection mirrors the protocol negotiated with the proxy client: it advertises
            // h2 via ALPN only when the proxy client negotiated HTTP/2, so its TLS layer and its codec
            // always agree and the relay is a transparent passthrough rather than converting between
            // HTTP/1.1 and HTTP/2 (issue #2260)
            pipelineToMockServer.addLast(nettySslContextFactory(proxyClientCtx.channel()).createClientSslContext(true, http2EnabledDownstream).newHandler(mockServerCtx.alloc(), host, port));
        }

        if (wireLoggingAllowed(configuration, proxyClientCtx.channel()) && mockServerLogger.isEnabledForInstance(TRACE)) {
            pipelineToMockServer.addLast(new LoggingHandler(RelayConnectHandler.class.getName() + "-downstream -->"));
        }

        if (http2EnabledDownstream) {
            configureHttp2LoopbackPipeline(pipelineToMockServer, proxyClientCtx);
        } else {
            configureHttp1LoopbackPipeline(pipelineToMockServer, proxyClientCtx);
        }

        if (wireLoggingAllowed(configuration, proxyClientCtx.channel()) && mockServerLogger.isEnabledForInstance(TRACE)) {
            pipelineToProxyClient.addLast(new LoggingHandler(RelayConnectHandler.class.getName() + "-upstream <-- "));
        }

        // The client's request is relayed to MockServer still in its Content-Encoding: MockServer decompresses it
        // as it does any request, so an unchanged forward sends the client's bytes and raw-block snappy is decoded.
        if (http2EnabledDownstream) {
            final Http2Connection connection = new DefaultHttp2Connection(true);
            final Http2FrameListener frameListener = pipelineToMockServer.get(LoopbackHttp2StreamErrorHandler.class).proxyClientFrameListener(
                connection,
                ExpectContinueInboundHttp2ToHttpAdapter.forConnection(connection, configuration.maxRequestBodySize())
            );
            final Http2FrameLogger frameLogger = wireLoggingAllowed(configuration, proxyClientCtx.channel()) && mockServerLogger.isEnabledForInstance(TRACE)
                ? new Http2FrameLogger(LogLevel.TRACE, RelayConnectHandler.class.getName())
                : null;
            // the client's requests are limited here, as on a direct connection. Added as a TLS handshake completes,
            // the handler flushes its SETTINGS at once, for no read of the client's may follow before it waits for them
            pipelineToProxyClient.addLast(Http2RequestHeaderLimit.tunnelServerHandler(configuration, mockServerLogger, connection, frameListener, frameLogger, addedWhileReading));
            pipelineToProxyClient.addLast(new StreamedHttp2ResponseWriter());
            // the loopback is exempt from write-stall watching, so a stream the client stops taking is cut on this leg
            final long writeStallTimeoutMillis = configuration.responseWriteStallTimeoutMillis();
            if (writeStallTimeoutMillis > 0 && !WriteStallTimeoutHandler.isExempt(proxyClientCtx.channel())) {
                pipelineToProxyClient.addLast(new Http2StreamWriteStallHandler(writeStallTimeoutMillis, mockServerLogger));
            }
        } else {
            HttpChunkLineLimiter chunkLineLimiter = new HttpChunkLineLimiter(mockServerLogger);
            HttpServerCodecResponsePairing responsePairing = new HttpServerCodecResponsePairing();
            HttpServerCodec httpServerCodec = HttpServerCodecs.httpServerCodec(configuration);
            HttpLineEndSplitGuard lineEndSplitGuard = new HttpLineEndSplitGuard(httpServerCodec);
            pipelineToProxyClient.addLast(chunkLineLimiter.beforeCodec());
            pipelineToProxyClient.addLast(responsePairing.beforeCodec());
            pipelineToProxyClient.addLast(lineEndSplitGuard.beforeCodec());
            pipelineToProxyClient.addLast(httpServerCodec);
            pipelineToProxyClient.addLast(lineEndSplitGuard.afterCodec());
            pipelineToProxyClient.addLast(responsePairing.afterCodec());
            pipelineToProxyClient.addLast(chunkLineLimiter.afterCodec());
            if (InboundConnectionActivity.isTracked(proxyClientCtx.channel())) {
                // a tunnel with a request being uploaded or a response still being relayed is not idle
                pipelineToProxyClient.addLast(HttpExchangeTracker.INSTANCE);
            }
            pipelineToProxyClient.addLast(HttpObjectAggregators.httpObjectAggregator(configuration.maxRequestBodySize()));
        }

        pipelineToProxyClient.addLast(new UpstreamProxyRelayHandler(mockServerLogger, proxyClientCtx.channel(), mockServerCtx.channel(), host, port, configuration.maxRequestBodySize()));
    }

    private void configureHttp1LoopbackPipeline(ChannelPipeline pipelineToMockServer, ChannelHandlerContext proxyClientCtx) {
        // reads only responses MockServer itself wrote, so the limits on what clients send must not apply
        final LoopbackHttpClientCodec codec = new LoopbackHttpClientCodec(configuration.maxChunkSize());
        // before the codec, which is given none of the bytes MockServer wrote as raw bytes
        pipelineToMockServer.addLast(LoopbackRawResponseSplitter.forTunnel(proxyClientCtx.channel(), codec));
        pipelineToMockServer.addLast(codec);
        pipelineToMockServer.addLast(new BoundedZstdHttpContentDecompressor());
        pipelineToMockServer.addLast(new StreamingAwareHttpObjectAggregator(configuration.maxRequestBodySize(), configuration, mockServerLogger, true));
        pipelineToMockServer.addLast(new LoopbackHttp1ResponseErrorHandler(proxyClientCtx.channel()));
        // a streamed response skips the aggregator, so its bytes not yet written are bounded by the same limit
        pipelineToMockServer.addLast(new DownstreamProxyRelayHandler(mockServerLogger, proxyClientCtx.channel(), configuration.maxRequestBodySize()));
    }

    private void configureHttp2LoopbackPipeline(ChannelPipeline pipelineToMockServer, ChannelHandlerContext proxyClientCtx) {
        final Http2Connection connection = new DefaultHttp2Connection(false);
        final LoopbackHttp2StreamIdRemapper streamIdRemapper = new LoopbackHttp2StreamIdRemapper(mockServerLogger, connection, proxyClientCtx.channel());
        final LoopbackHttp2StreamErrorHandler streamErrorHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, connection, streamIdRemapper, proxyClientCtx.channel());
        // a response of undeclared length is relayed as MockServer writes it, any other once it is whole
        final LoopbackHttp2ResponseStreamer responseStreamer = new LoopbackHttp2ResponseStreamer(connection);
        // reads only responses MockServer itself wrote, so no limit on their headers, as on the HTTP/1.1 loopback
        final HttpToHttp2ConnectionHandler http2ConnectionHandler = Http2RequestHeaderLimit.relayLoopbackHandler(
            mockServerLogger,
            proxyClientCtx.channel(),
            connection,
            streamErrorHandler.frameListener(responseStreamer.relaying(
                LoopbackAggregatingListener.of(connection, configuration.maxRequestBodySize())
            )),
            wireLoggingAllowed(configuration, proxyClientCtx.channel()) && mockServerLogger.isEnabledForInstance(TRACE) ? new Http2FrameLogger(LogLevel.TRACE, RelayConnectHandler.class.getName()) : null
        );
        pipelineToMockServer.addLast(http2ConnectionHandler);
        responseStreamer.widenConnectionWindow(pipelineToMockServer.context(http2ConnectionHandler));
        pipelineToMockServer.addLast(streamErrorHandler);
        pipelineToMockServer.addLast(streamIdRemapper);
        pipelineToMockServer.addLast(new LoopbackHttp2ConnectionCloseHandler(mockServerLogger, connection, proxyClientCtx.channel(), streamIdRemapper));
        pipelineToMockServer.addLast(new DownstreamProxyRelayHandler(mockServerLogger, proxyClientCtx.channel()));
    }

}

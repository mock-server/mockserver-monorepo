package org.mockserver.netty.unification;

import org.mockserver.netty.controlplane.ControlPlaneListenerSettings;
import org.mockserver.netty.controlplane.ManagementSourceGuard;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.ReplayingDecoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http2.*;
import io.netty.handler.codec.socksx.v4.Socks4ServerDecoder;
import io.netty.handler.codec.socksx.v4.Socks4ServerEncoder;
import io.netty.handler.codec.socksx.v5.Socks5InitialRequestDecoder;
import io.netty.handler.codec.socksx.v5.Socks5ServerEncoder;
import io.netty.handler.logging.LogLevel;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.AttributeKey;
import io.netty.util.concurrent.ScheduledFuture;
import org.apache.commons.lang3.StringUtils;
import org.mockserver.codec.Http1PipeliningGate;
import org.mockserver.codec.HttpChunkLineLimiter;
import org.mockserver.authentication.ProxyAuthenticationValidator;
import org.mockserver.codec.HttpLineEndSplitGuard;
import org.mockserver.codec.HttpObjectAggregators;
import org.mockserver.codec.MockServerHttpContentDecompressor;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.codec.PreserveHeadersNettyRemoves;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.configuration.ControlPlaneAuthenticationSettings;
import org.mockserver.configuration.ServerTlsSettings;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.lifecycle.OwnListener;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.LoggingHandler;
import org.mockserver.logging.RawNetworkTrafficLog;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.MockServerHttpResponseToFullHttpResponse;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.controlplane.ListenerRole;
import org.mockserver.netty.controlplane.ManagementRequestGuard;
import org.mockserver.netty.connection.Http2StreamWriteStallHandler;
import org.mockserver.netty.connection.HttpExchangeTracker;
import org.mockserver.netty.connection.HttpTransportTimer;
import org.mockserver.netty.connection.InboundConnectionActivity;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.netty.connection.TunnelProxyClient;
import org.mockserver.netty.connection.WriteStallTimeoutHandler;
import org.mockserver.netty.proxy.relay.LoopbackRelaySignalHandler;
import org.mockserver.netty.proxy.relay.LoopbackResetRelay;
import org.mockserver.netty.proxy.relay.RelayLoopbackAddresses;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.grpc.GrpcToHttpRequestHandler;
import org.mockserver.netty.grpc.GrpcToHttpResponseHandler;
import org.mockserver.netty.proxy.BinaryMessageFramer;
import org.mockserver.netty.proxy.BinaryMessageGatherer;
import org.mockserver.netty.proxy.BinaryRequestProxyingHandler;
import org.mockserver.netty.proxy.socks.Socks4ProxyHandler;
import org.mockserver.netty.proxy.socks.Socks5ProxyHandler;
import org.mockserver.netty.proxy.socks.SocksDetector;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.socket.SocketAddresses;
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
import static org.mockserver.exception.ExceptionHandling.*;
import static org.mockserver.mock.action.http.HttpActionHandler.setRemoteAddress;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.Protocol.HTTP_2;
import static org.mockserver.netty.HttpRequestHandler.LOCAL_HOST_HEADERS;
import static org.mockserver.netty.HttpRequestHandler.setProxyingRequest;
import static org.mockserver.netty.proxy.relay.RelayConnectHandler.*;
import static org.mockserver.socket.tls.SniHandler.getALPNProtocol;
import static org.slf4j.event.Level.TRACE;

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

    /**
     * How long first bytes that are only the start of a known protocol are held once the client falls silent,
     * before they are taken for what they are. Bytes that cannot become a known protocol are never held.
     */
    public static final long UNDECIDED_PROTOCOL_WAIT_MILLIS = 1000;
    // the record content types SslHandler.isEncrypted accepts: change cipher spec (20) to heartbeat (24)
    private static final int TLS_CONTENT_TYPE_FIRST = 20;
    private static final int TLS_CONTENT_TYPE_LAST = 24;
    private static final int TLS_RECORD_HEADER_LENGTH = 5;
    // how a TLS handshake opens: a handshake record (22) of version 3.0 to 3.4, its length, then ClientHello (1)
    private static final int TLS_CONTENT_TYPE_HANDSHAKE = 22;
    private static final int TLS_VERSION_MAJOR = 3;
    private static final int TLS_VERSION_MINOR_LAST = 4;
    private static final int TLS_RECORD_LENGTH_MAX = 16384;
    private static final int TLS_HANDSHAKE_TYPE_CLIENT_HELLO = 1;
    private static final int TLS_CLIENT_HELLO_START_LENGTH = 6;
    private static final String[] HTTP_METHODS = {"GET ", "POST ", "PUT ", "HEAD ", "OPTIONS ", "PATCH ", "DELETE ", "TRACE ", "CONNECT "};

    // public so the single AttributeKey instance is the shared source of truth -- ConnectionScopeHandler
    // references these directly to propagate connection-scoped state onto HTTP/2 stream child channels.
    // (HTTP_ENABLED stays private: it is only ever read here, never on a child stream.)
    public static final AttributeKey<Boolean> TLS_ENABLED_UPSTREAM = AttributeKey.valueOf("TLS_ENABLED_UPSTREAM");
    public static final AttributeKey<Boolean> TLS_ENABLED_DOWNSTREAM = AttributeKey.valueOf("TLS_ENABLED_DOWNSTREAM");
    // Marks a SOCKS tunnel whose carried protocol (TLS vs cleartext) is not yet known: a SOCKS client
    // only sends its ClientHello AFTER it receives the SOCKS success reply, so at relay-wiring time there
    // is no client byte to classify. When set, the relay defers the upstream/downstream TLS decision and
    // classifies the first tunnelled bytes instead of guessing from the destination port (issue #2685).
    public static final AttributeKey<Boolean> TLS_DETECTION_DEFERRED = AttributeKey.valueOf("TLS_DETECTION_DEFERRED");
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
    // Shared server-wide instance (null when MCP is disabled) owned by MockServerUnificationInitializer.
    private final McpStreamableHttpHandler mcpStreamableHttpHandler;
    private final MockServerHttpResponseToFullHttpResponse mockServerHttpResponseToFullHttpResponse;
    private final ClientTlsHandshakeFailureLog clientTlsHandshakeFailureLog;
    private final RawNetworkTrafficLog rawNetworkTrafficLog;
    private ScheduledFuture<?> undecidedProtocolWait;
    private ScheduledFuture<?> serverFirstWait;
    private boolean clientSpoke;
    private boolean takeBytesReceivedAsTheyAre;
    // the connection is binary and in the clear: only a TLS handshake beginning on it is still looked for
    private boolean binaryInTheClear;
    // how many bytes are being held as the possible start of that handshake
    private int heldAsStartOfHandshake;

    public PortUnificationHandler(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler actionHandler, NettySslContextFactory nettySslContextFactory, McpStreamableHttpHandler mcpStreamableHttpHandler) {
        this(configuration, server, httpState, actionHandler, nettySslContextFactory, mcpStreamableHttpHandler, null);
    }

    public PortUnificationHandler(Configuration configuration, LifeCycle server, HttpState httpState, HttpActionHandler actionHandler, NettySslContextFactory nettySslContextFactory, McpStreamableHttpHandler mcpStreamableHttpHandler, RawNetworkTrafficLog rawNetworkTrafficLog) {
        this.rawNetworkTrafficLog = rawNetworkTrafficLog;
        this.configuration = configuration;
        this.server = server;
        this.mockServerLogger = httpState.getMockServerLogger();
        this.httpState = httpState;
        this.actionHandler = actionHandler;
        this.nettySslContextFactory = nettySslContextFactory;
        this.mcpStreamableHttpHandler = mcpStreamableHttpHandler;
        this.mockServerHttpResponseToFullHttpResponse = new MockServerHttpResponseToFullHttpResponse(mockServerLogger);
        ClientTlsHandshakeFailureLog serversLog = server != null ? server.getClientTlsHandshakeFailureLog() : null;
        this.clientTlsHandshakeFailureLog = serversLog != null ? serversLog : new ClientTlsHandshakeFailureLog();
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

    public static void deferTlsDetection(Channel channel) {
        channel.attr(TLS_DETECTION_DEFERRED).set(Boolean.TRUE);
    }

    public static boolean isTlsDetectionDeferred(Channel channel) {
        return Boolean.TRUE.equals(channel.attr(TLS_DETECTION_DEFERRED).get());
    }

    public static void enableSslDownstream(Channel channel) {
        channel.attr(TLS_ENABLED_DOWNSTREAM).set(Boolean.TRUE);
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
            ChannelReadPause.pause(ctx.channel());
            ctx.executor().schedule(() -> ChannelReadPause.resume(ctx.channel()), delayMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        }
        Long serverFirstWaitMillis = configuration.forwardBinaryServerFirstWaitMillis();
        if (ListenerRole.of(ctx.channel()) == ListenerRole.COMBINED && serverFirstWaitMillis != null && serverFirstWaitMillis > 0) {
            startServerFirstWait(ctx, serverFirstWaitMillis);
        }
        ctx.fireChannelActive();
    }

    /**
     * A client that sends nothing for this long may be waiting for a server that speaks first (MySQL, SMTP): a
     * connection with a forward target is then taken as binary and its upstream connection opened, so the server's
     * first bytes reach the client.
     */
    private void startServerFirstWait(ChannelHandlerContext ctx, long waitMillis) {
        serverFirstWait = ctx.executor().schedule(() -> {
            serverFirstWait = null;
            if (clientSpoke || ctx.isRemoved() || !ctx.channel().isActive()) {
                return;
            }
            if (!ctx.channel().config().isAutoRead()) {
                // what the client sent may be waiting unread (connectionDelay), so its silence says nothing yet
                startServerFirstWait(ctx, waitMillis);
                return;
            }
            connectForServerThatSpeaksFirst(ctx, waitMillis);
        }, waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopServerFirstWait() {
        if (serverFirstWait != null) {
            serverFirstWait.cancel(false);
            serverFirstWait = null;
        }
    }

    private void connectForServerThatSpeaksFirst(ChannelHandlerContext ctx, long waitMillis) {
        if (ListenerRole.of(ctx.channel()) != ListenerRole.COMBINED) {
            return;
        }
        InetSocketAddress target = HttpActionHandler.getRemoteAddress(ctx);
        if (target == null || configuration.assumeAllRequestsAreHttp()) {
            // nothing would be relayed: the connection waits for its client
            return;
        }
        if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.DEBUG)
                    .setMessageFormat("client of connection from:{}sent nothing for:{}ms (forwardBinaryServerFirstWaitMillis), opening its upstream connection to:{}for a server that speaks first")
                    .setArguments(ctx.channel().remoteAddress(), waitMillis, target)
            );
        }
        if (!addBinaryRequestProxying(ctx)) {
            ctx.close();
            return;
        }
        ChannelHandlerContext binaryRequestProxying = ctx.pipeline().context(BinaryRequestProxyingHandler.class);
        ((BinaryRequestProxyingHandler) binaryRequestProxying.handler()).connectBeforeClientSpeaks(binaryRequestProxying);
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt == TunnelProxyClient.ServerSpeaksFirst.INSTANCE) {
            // the relay judged the tunnel's client silent; bytes it sent since have already made this connection binary
            if (!binaryInTheClear && ctx.channel().isActive()) {
                takeTunnelledBytesInTheClear(ctx.channel());
                connectForServerThatSpeaksFirst(ctx, configuration.forwardBinaryServerFirstWaitMillis());
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    /**
     * A tunnel's binary bytes cross the loopback as the client sent them, so a TLS proxy connection
     * ({@code PROXIED_SECURE_}) says nothing of them: the destination is reached in the clear unless they turn TLS on.
     */
    private static void takeTunnelledBytesInTheClear(Channel channel) {
        channel.attr(TLS_ENABLED_UPSTREAM).set(Boolean.FALSE);
        channel.attr(TLS_ENABLED_DOWNSTREAM).set(Boolean.FALSE);
    }

    @Override
    protected void callDecode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
        if (in.isReadable()) {
            clientSpoke = true;
            stopServerFirstWait();
        }
        stopUndecidedProtocolWait();
        super.callDecode(ctx, in, out);
        // bytes are left over only when decode asked for more before it could tell what they are
        if (!ctx.isRemoved() && in.isReadable()) {
            startUndecidedProtocolWait(ctx);
        }
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf msg, List<Object> out) {
        ctx.channel().attr(NETTY_SSL_CONTEXT_FACTORY).set(nettySslContextFactory);
        ListenerRole role = ListenerRole.of(ctx.channel());
        int firstByte = msg.getUnsignedByte(msg.readerIndex());
        boolean authenticatedSocks5 = firstByte == 5 && ProxyAuthenticationValidator.proxyAuthenticationConfigured(
            configuration.proxyAuthenticationUsername(), configuration.proxyAuthenticationPassword());
        if ((role == ListenerRole.MANAGEMENT && (firstByte == 4 || firstByte == 5))
            || (role == ListenerRole.PROXY && (firstByte == 4 || (firstByte == 5 && !authenticatedSocks5)))
            || (role != ListenerRole.COMBINED && isProxyConnected(msg)
                && (role == ListenerRole.MANAGEMENT || !RelayLoopbackAddresses.isRelayLoopback(ctx.channel())))) {
            msg.skipBytes(actualReadableBytes());
            ctx.close();
            return;
        }
        if (binaryInTheClear) {
            if (!takeBytesReceivedAsTheyAre && tlsMayStartHere(ctx) && startsTlsClientHello(msg)) {
                // a protocol that turns TLS on part way through: what is decrypted from here on is binary
                logStage(ctx, "adding TLS decoders to a binary connection");
                // what this read loop brought before the handshake is a message sent in the clear: it is handled,
                // and answered or forwarded in the clear, before TLS is turned on
                ctx.fireChannelReadComplete();
                if (endedByWhatWasPassedOn(ctx, msg)) {
                    return;
                }
                enableTls(ctx, msg);
                ctx.pipeline().remove(this);
            } else {
                int held = heldAsStartOfHandshake;
                heldAsStartOfHandshake = 0;
                if (held > 0 && held < actualReadableBytes()) {
                    // what was held turned out to be a message of its own, sent before the bytes that settled it.
                    // Those bytes are looked at next, as the start of a read: they may begin a handshake themselves
                    ctx.fireChannelRead(msg.readBytes(held));
                    // it arrived in an earlier read than what follows it, so it is not joined to that
                    ctx.fireChannelReadComplete();
                    endedByWhatWasPassedOn(ctx, msg);
                    return;
                }
                int beforeTls = bytesBeforeTlsMayStart(ctx, msg);
                if (beforeTls > 0) {
                    // a message after which the client may start its handshake without waiting for a reply: what
                    // follows it is looked at next, as the start of a read
                    ctx.fireChannelRead(msg.readBytes(beforeTls));
                    endedByWhatWasPassedOn(ctx, msg);
                    return;
                }
                ctx.fireChannelRead(msg.readBytes(actualReadableBytes()));
            }
        } else if (TunnelProxyClient.carriesBinary(ctx.channel())) {
            logStage(ctx, "adding binary decoder for a tunnel's binary bytes");
            takeTunnelledBytesInTheClear(ctx.channel());
            switchToBinaryRequestProxying(ctx, msg);
        } else if (takeBytesReceivedAsTheyAre) {
            switchToUnknownProtocol(ctx, msg);
        } else if (SocksDetector.isSocks4(msg, actualReadableBytes())) {
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
        } else if (RelayLoopbackAddresses.isRelayLoopback(ctx.channel()) && isProxyConnected(msg)) {
            // only the loopback leg MockServer's own relay opened, after the CONNECT/SOCKS handshake, may name a
            // tunnel target: from any other connection the preamble would open a relay that skips proxy authentication
            logStage(ctx, "setting proxy connected");
            switchToProxyConnected(ctx, msg);
        } else {
            switchToUnknownProtocol(ctx, msg);
        }

        if (wireLoggingAllowed(ctx) && mockServerLogger.isEnabledForInstance(TRACE)) {
            loggingHandler.addLoggingHandler(ctx);
        }
    }

    /**
     * Whether handling a message passed on part way through a read closed the connection or removed this handler,
     * so the rest of the read must not be looked at. On a closed connection the rest is dropped: nothing would
     * answer it. Where a close completes at once, this handler has been removed and its buffer released by now.
     */
    private boolean endedByWhatWasPassedOn(ChannelHandlerContext ctx, ByteBuf msg) {
        if (ctx.isRemoved()) {
            return true;
        }
        if (ctx.channel().isActive()) {
            return false;
        }
        msg.skipBytes(actualReadableBytes());
        return true;
    }

    private boolean wireLoggingAllowed(ChannelHandlerContext ctx) {
        return wireLoggingAllowed(configuration, ctx.channel());
    }

    /** Whether this connection may install unredacted TRACE byte and protocol-frame loggers. */
    public static boolean wireLoggingAllowed(Configuration configuration, Channel channel) {
        return ListenerRole.of(channel) == ListenerRole.COMBINED
            && !ProxyAuthenticationValidator.proxyAuthenticationConfigured(configuration.proxyAuthenticationUsername(), configuration.proxyAuthenticationPassword());
    }

    private void switchToUnknownProtocol(ChannelHandlerContext ctx, ByteBuf msg) {
        if (ListenerRole.of(ctx.channel()) != ListenerRole.COMBINED) {
            msg.skipBytes(actualReadableBytes());
            ctx.close();
            return;
        }
        if (configuration.assumeAllRequestsAreHttp()) {
            logStage(ctx, "adding HTTP decoders");
            switchToHttp(ctx, msg);
        } else {
            logStage(ctx, "adding binary decoder");
            switchToBinaryRequestProxying(ctx, msg);
        }
    }

    /**
     * Whether the bytes received start with one of the tokens. Answers at once when they do, or can no longer
     * come to; waits for more only while they are the start of one.
     */
    private boolean startsWithAny(ByteBuf msg, String... tokens) {
        int longest = 0;
        for (String token : tokens) {
            longest = Math.max(longest, token.length());
        }
        String received = msg.toString(msg.readerIndex(), Math.min(actualReadableBytes(), longest), StandardCharsets.US_ASCII);
        boolean couldBecomeOne = false;
        for (String token : tokens) {
            if (received.startsWith(token)) {
                return true;
            }
            couldBecomeOne |= token.startsWith(received);
        }
        if (couldBecomeOne) {
            waitForMoreBytes(msg);
        }
        return false;
    }

    private int bytesBeforeTlsMayStart(ChannelHandlerContext ctx, ByteBuf msg) {
        BinaryMessageFramer framer = ctx.pipeline().get(BinaryMessageFramer.class);
        return framer == null ? 0 : framer.bytesBeforeTlsMayStart(msg, actualReadableBytes());
    }

    private static boolean tlsMayStartHere(ChannelHandlerContext ctx) {
        // with a protocol's framing, bytes in the middle of a message are never a handshake, whatever they look like
        BinaryMessageFramer framer = ctx.pipeline().get(BinaryMessageFramer.class);
        return framer == null || framer.tlsMayStartHere();
    }

    /**
     * Whether the bytes received open a TLS handshake: a handshake record of a TLS version and a length a
     * record can have, whose first byte says ClientHello. Answers at once when they do, or no longer can;
     * waits for more only while they are the start of one.
     */
    private boolean startsTlsClientHello(ByteBuf msg) {
        int start = msg.readerIndex();
        int available = Math.min(actualReadableBytes(), TLS_CLIENT_HELLO_START_LENGTH);
        boolean couldBeOne = msg.getUnsignedByte(start) == TLS_CONTENT_TYPE_HANDSHAKE
            && (available < 2 || msg.getUnsignedByte(start + 1) == TLS_VERSION_MAJOR)
            && (available < 3 || msg.getUnsignedByte(start + 2) <= TLS_VERSION_MINOR_LAST)
            && (available < 4 || msg.getUnsignedByte(start + 3) <= TLS_RECORD_LENGTH_MAX >> 8)
            && (available < 5 || (msg.getUnsignedShort(start + 3) >= 1 && msg.getUnsignedShort(start + 3) <= TLS_RECORD_LENGTH_MAX))
            && (available < 6 || msg.getUnsignedByte(start + 5) == TLS_HANDSHAKE_TYPE_CLIENT_HELLO);
        if (couldBeOne && available < TLS_CLIENT_HELLO_START_LENGTH) {
            heldAsStartOfHandshake = available;
            waitForMoreBytes(msg);
        }
        return couldBeOne;
    }

    private void waitForMoreBytes(ByteBuf msg) {
        // reading past what has arrived is how a ReplayingDecoder asks to be called again with more
        msg.getByte(msg.readerIndex() + actualReadableBytes());
    }

    private void startUndecidedProtocolWait(ChannelHandlerContext ctx) {
        stopUndecidedProtocolWait();
        undecidedProtocolWait = ctx.executor().schedule(() -> {
            undecidedProtocolWait = null;
            if (!ctx.channel().config().isAutoRead()) {
                // the rest may have been sent and be waiting unread, so the silence says nothing yet
                startUndecidedProtocolWait(ctx);
                return;
            }
            takeBytesReceivedAsTheyAre = true;
            try {
                // an empty read runs detection again over the bytes already held
                channelRead(ctx, Unpooled.EMPTY_BUFFER);
                channelReadComplete(ctx);
            } catch (Exception exception) {
                exceptionCaught(ctx, exception);
            } finally {
                takeBytesReceivedAsTheyAre = false;
            }
        }, UNDECIDED_PROTOCOL_WAIT_MILLIS, java.util.concurrent.TimeUnit.MILLISECONDS);
    }

    private void stopUndecidedProtocolWait() {
        if (undecidedProtocolWait != null) {
            undecidedProtocolWait.cancel(false);
            undecidedProtocolWait = null;
        }
    }

    @Override
    protected void decodeLast(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        // the client has closed, so the bytes being held are all there will be
        takeBytesReceivedAsTheyAre = true;
        try {
            super.decodeLast(ctx, in, out);
        } finally {
            takeBytesReceivedAsTheyAre = false;
        }
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) throws Exception {
        stopUndecidedProtocolWait();
        stopServerFirstWait();
        super.handlerRemoved0(ctx);
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

        // not marked as proxying yet: SocksProxyHandler.forwardConnection does that once the handshake is accepted

        // re-unify (with SOCKS5 enabled)
        if (isSslEnabledUpstream(ctx.channel())) {
            pipeline.context("SslHandler#0").fireChannelRead(msg.readBytes(actualReadableBytes()));
        } else {
            ctx.pipeline().fireChannelRead(msg.readBytes(actualReadableBytes()));
        }
    }

    private boolean isTls(ByteBuf buf) {
        // SslHandler.isEncrypted answers true for fewer bytes than a record header, whatever they are
        short contentType = buf.getUnsignedByte(buf.readerIndex());
        if (contentType < TLS_CONTENT_TYPE_FIRST || contentType > TLS_CONTENT_TYPE_LAST) {
            return false;
        }
        if (actualReadableBytes() < TLS_RECORD_HEADER_LENGTH) {
            waitForMoreBytes(buf);
        }
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
        return startsWithAny(msg, HTTP_METHODS);
    }

    // The HTTP/2 cleartext (h2c) connection preface, the fixed 24 bytes an h2c prior-knowledge client sends
    // before any frame (RFC 9113 section 3.4). This is the single source of truth for the wire constant:
    // RelayConnectHandler's SOCKS-tunnel detector sniffs for the same preface via isPartialOrCompleteH2cPreface.
    public static final String H2C_PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n";
    public static final int H2C_PREFACE_LENGTH = H2C_PREFACE.length();

    private boolean isH2cPreface(ByteBuf msg) {
        return startsWithAny(msg, H2C_PREFACE);
    }

    /**
     * Whether the {@code readableBytes} at {@code buf}'s reader index are still a viable prefix of the h2c
     * connection preface - {@code true} while they match the preface (whether the full 24 bytes have arrived
     * yet or not), {@code false} as soon as they diverge from it. Reads absolutely, leaving the reader index
     * untouched so a caller decoder consumes nothing. A caller that also needs the preface to be complete
     * compares {@code readableBytes} against {@link #H2C_PREFACE_LENGTH}. Used by the SOCKS relay's one-shot
     * detector, which must tell an h2c prior-knowledge tunnel from an HTTP/1.1 one before either has been
     * fully buffered, without re-declaring the preface bytes.
     */
    public static boolean isPartialOrCompleteH2cPreface(ByteBuf buf, int readableBytes) {
        int compareLength = Math.min(readableBytes, H2C_PREFACE_LENGTH);
        if (compareLength <= 0) {
            return false;
        }
        String prefix = buf.toString(buf.readerIndex(), compareLength, StandardCharsets.US_ASCII);
        return H2C_PREFACE.startsWith(prefix);
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

            // Since issue #2669 the Http2FrameCodec + Http2MultiplexHandler pipeline is the ONLY
            // HTTP/2 server pipeline: every stream gets its own child channel. h2c has no TLS, so
            // sslEnabled=false and no client certificates.
            switchToHttp2Multiplex(ctx, pipeline, false, null);

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

            // Since issue #2669 the Http2FrameCodec + Http2MultiplexHandler pipeline is the ONLY
            // HTTP/2 server pipeline: every stream gets its own child channel. Preserve the upstream
            // TLS state and client certificates (used for mTLS control-plane auth) from the connection.
            switchToHttp2Multiplex(ctx, pipeline, isSslEnabledUpstream(ctx.channel()), SniHandler.retrieveClientCertificates(mockServerLogger, ctx));

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
        // HttpContentDecompressor installed by Http2MultiplexChildInitializer.installReAggregatingChain)
        // and it disables inbound header validation (Http2StreamFrameToHttpObjectCodec validateHeaders
        // = false) so unusual request header values are recorded rather than reset. gRPC's own message
        // compression is a separate concern carried by the grpc-encoding header (handled by
        // GrpcFrameCodec), on which HttpContentDecompressor is inert.
        Http2FrameCodecBuilder frameCodecBuilder = Http2RequestHeaderLimit.frameCodecBuilder(mockServerLogger)
            // Disable INBOUND header validation on the connection-level frame decoder so unusual request
            // header values (leading space, embedded DEL/0x7F, other control characters) are decoded and
            // recorded rather than RST_STREAM'd -- MockServer records malformed traffic so users can test
            // their own clients. This governs the frame codec's Http2HeadersDecoder: as of netty-codec-http2
            // 4.2.18 that decoder validates header VALUES too (earlier versions validated only names), so a
            // value like 0x7F is otherwise rejected with PROTOCOL_ERROR at DECODE time -- before the
            // per-stream LenientInboundHttp2StreamFrameCodec (which relaxes only the HTTP/2->HTTP/1
            // CONVERSION) can accept it.
            //
            // This flag is deliberately load-bearing -- do NOT "tidy" it away. Netty exposes a single
            // validateHeaders flag covering both names and values with no names-only control, so disabling it
            // ALSO disables inbound header-NAME validation. That makes this the ONLY MockServer path with an
            // unvalidated frame reader: the relay/echo connection-adapter paths (RelayConnectHandler,
            // EchoServerInitializer) disable validation only on the InboundHttp2ToHttpAdapter CONVERSION
            // (validateHttpHeaders(false)) while their frame reader keeps the default validateHeaders=true, so
            // they still reject malformed inbound NAMES. The wider name leniency here is an accepted,
            // unavoidable consequence of keeping value leniency. HTTP/2-forbidden connection-specific names
            // (Connection, Transfer-Encoding, ...) are still rejected by the framing layer, and OUTBOUND
            // response/trailer header NAMES remain validated by the per-stream codec.
            .validateHeaders(false)
            .initialSettings((RelayLoopbackAddresses.isRelayLoopback(ctx.channel()) ? Http2RequestHeaderLimit.relayLoopbackSettings() : Http2RequestHeaderLimit.serverSettings(configuration))
                .maxConcurrentStreams(HTTP2_MAX_CONCURRENT_STREAMS)
                .maxFrameSize(configuration.maxRequestBodySize() < Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                    ? Http2CodecUtil.MAX_FRAME_SIZE_LOWER_BOUND
                    : Math.min(configuration.maxRequestBodySize(), Http2CodecUtil.MAX_FRAME_SIZE_UPPER_BOUND)));
        if (rawNetworkTrafficLog != null) {
            frameCodecBuilder.frameLogger(rawNetworkTrafficLog.http2FrameLogger());
        } else if (wireLoggingAllowed(ctx) && mockServerLogger.isEnabledForInstance(TRACE)) {
            frameCodecBuilder.frameLogger(new Http2FrameLogger(LogLevel.TRACE, PortUnificationHandler.class.getName()));
        }
        Http2FrameCodec frameCodec = frameCodecBuilder.build();
        addLastIfNotPresent(pipeline, frameCodec);
        ServerClosedChannels.forgetRelayedHttp2StreamResetsOnClose(ctx.channel(), frameCodec.connection());
        long writeStallTimeoutMillis = configuration.responseWriteStallTimeoutMillis();
        if (writeStallTimeoutMillis > 0 && !WriteStallTimeoutHandler.isExempt(ctx.channel())) {
            addLastIfNotPresent(pipeline, new Http2StreamWriteStallHandler(writeStallTimeoutMillis, mockServerLogger));
        }
        addLastIfNotPresent(pipeline, new Http2MultiplexHandler(
            new Http2MultiplexChildInitializer(
                configuration, server, httpState, actionHandler,
                mockServerLogger, mcpStreamableHttpHandler,
                sslEnabled, clientCertificates
            )
        ));
        // last: it takes whatever the multiplex handler does not hand to a stream
        addLastIfNotPresent(pipeline, new Http2ConnectionExceptionHandler(mockServerLogger));
    }

    private void switchToHttp(ChannelHandlerContext ctx, ByteBuf msg) {
        if (!isHttpEnabled(ctx.channel())) {
            httpEnabled(ctx.channel());

            ChannelPipeline pipeline = ctx.pipeline();

            if (rawNetworkTrafficLog != null) {
                rawNetworkTrafficLog.addTo(pipeline, false);
            }
            HttpTransportTimer transportTimer = Boolean.TRUE.equals(configuration.metricsEnabled()) ? new HttpTransportTimer() : null;
            if (transportTimer != null) {
                addLastIfNotPresent(pipeline, transportTimer.readMarker());
            }
            if (TcpChaosRegistry.getInstance().activeCount() > 0) {
                pipeline.addLast("tcp-chaos", new TcpChaosHandler());
            }
            addLastIfNotPresent(pipeline, new PacedLargeWriteHandler());
            HttpChunkLineLimiter chunkLineLimiter = new HttpChunkLineLimiter(mockServerLogger);
            HttpServerCodecResponsePairing responsePairing = new HttpServerCodecResponsePairing();
            HttpServerCodec httpServerCodec = HttpServerCodecs.httpServerCodec(configuration);
            HttpLineEndSplitGuard lineEndSplitGuard = new HttpLineEndSplitGuard(httpServerCodec);
            addLastIfNotPresent(pipeline, chunkLineLimiter.beforeCodec());
            addLastIfNotPresent(pipeline, responsePairing.beforeCodec());
            addLastIfNotPresent(pipeline, lineEndSplitGuard.beforeCodec());
            addLastIfNotPresent(pipeline, httpServerCodec);
            ControlPlaneListenerSettings sourcePolicy =
                ControlPlaneListenerSettings.of(ctx.channel());
            if (sourcePolicy != null && sourcePolicy.restrictsSources() && ListenerRole.of(ctx.channel()) == ListenerRole.COMBINED) {
                addLastIfNotPresent(pipeline, new ManagementSourceGuard(sourcePolicy, httpState));
            }
            addLastIfNotPresent(pipeline, lineEndSplitGuard.afterCodec());
            addLastIfNotPresent(pipeline, responsePairing.afterCodec());
            addLastIfNotPresent(pipeline, chunkLineLimiter.afterCodec());
            if (InboundConnectionActivity.isTracked(ctx.channel())) {
                addLastIfNotPresent(pipeline, HttpExchangeTracker.INSTANCE);
            }
            if (RelayLoopbackAddresses.isRelayLoopback(ctx.channel())) {
                // the relay must hear where an exchange ends here without an encoded response
                addLastIfNotPresent(pipeline, LoopbackRelaySignalHandler.INSTANCE);
            }
            if (transportTimer != null) {
                addLastIfNotPresent(pipeline, transportTimer);
            }
            // with no server, or none that counts in-flight requests, the gate counts nothing
            addLastIfNotPresent(pipeline, new Http1PipeliningGate(configuration, server != null ? server.getHeldRequestAccounting() : null, mockServerLogger));
            addLastIfNotPresent(pipeline, new ProxyAuthenticationHandler(configuration));
            addLastIfNotPresent(pipeline, preserveHeadersNettyRemoves);
            addLastIfNotPresent(pipeline, new MockServerHttpContentDecompressor(configuration.maxRequestBodySize()));
            addLastIfNotPresent(pipeline, httpContentLengthRemover);
            if (ListenerRole.of(ctx.channel()) == ListenerRole.COMBINED) {
                addLastIfNotPresent(pipeline, new EarlyMatchingHandler(configuration, httpState, actionHandler, isSslEnabledUpstream(ctx.channel())));
            }
            addLastIfNotPresent(pipeline, HttpObjectAggregators.httpObjectAggregator(configuration.maxRequestBodySize()));
            if (Boolean.TRUE.equals(ServerTlsSettings.of(configuration).tlsMutualAuthenticationRequired())
                && Boolean.TRUE.equals(ControlPlaneAuthenticationSettings.of(configuration).controlPlaneTLSMutualAuthenticationRequired())
                && !isSslEnabledUpstream(ctx.channel())) {
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
                if (ListenerRole.of(ctx.channel()) == ListenerRole.MANAGEMENT) {
                    addLastIfNotPresent(pipeline, new ManagementRequestGuard());
                }
                if (ListenerRole.of(ctx.channel()) != ListenerRole.PROXY) {
                    addLastIfNotPresent(pipeline, new CallbackWebSocketServerHandler(httpState));
                    addLastIfNotPresent(pipeline, new DashboardWebSocketHandler(httpState, isSslEnabledUpstream(ctx.channel()), false));
                    if (mcpStreamableHttpHandler != null) {
                        addLastIfNotPresent(pipeline, mcpStreamableHttpHandler);
                    }
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
        return startsWithAny(msg, PROXIED);
    }

    private void switchToProxyConnected(ChannelHandlerContext ctx, ByteBuf msg) {
        // MockServer's own loopback leg of a CONNECT/SOCKS tunnel: the client's leg is the one timed, and closes this one
        InboundConnectionActivity.markLongLived(ctx.channel());
        if (RelayLoopbackAddresses.isRelayLoopback(ctx.channel())) {
            RelayLoopbackAddresses.SourceIdentity source = RelayLoopbackAddresses.sourceIdentityOf(ctx.channel());
            if (source != null && source.address() != null) {
                ctx.channel().attr(SocketAddresses.PROXY_PROTOCOL_SOURCE).set(source.address());
            }
            WriteStallTimeoutHandler.exempt(ctx.channel());
            LoopbackResetRelay.relayResetsOf(ctx.channel());
            RelayLoopbackAddresses.rememberProxyClient(ctx.channel());
        }
        String message = readMessage(msg);
        if (message.startsWith(PROXIED_SECURE)) {
            String[] hostParts = HttpRequest.splitHostPort(StringUtils.substringAfter(message, PROXIED_SECURE));
            int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : 443;
            enableSslUpstreamAndDownstream(ctx.channel());
            setProxyingRequest(ctx, Boolean.TRUE);
            setRemoteAddress(ctx, SocketAddresses.unresolvedUnlessIpLiteral(hostParts[0], port));
        } else if (message.startsWith(PROXIED)) {
            String[] hostParts = HttpRequest.splitHostPort(StringUtils.substringAfter(message, PROXIED));
            int port = hostParts.length > 1 ? Integer.parseInt(hostParts[1]) : 80;
            setProxyingRequest(ctx, Boolean.TRUE);
            setRemoteAddress(ctx, SocketAddresses.unresolvedUnlessIpLiteral(hostParts[0], port));
        }
        ctx.writeAndFlush(Unpooled.copiedBuffer((PROXIED_RESPONSE + message).getBytes(StandardCharsets.UTF_8)));
    }

    private String readMessage(ByteBuf msg) {
        byte[] bytes = new byte[actualReadableBytes()];
        msg.readBytes(bytes);
        return new String(bytes, StandardCharsets.US_ASCII);
    }

    private void switchToBinaryRequestProxying(ChannelHandlerContext ctx, ByteBuf msg) {
        if (!addBinaryRequestProxying(ctx)) {
            msg.skipBytes(actualReadableBytes());
            ctx.close();
            return;
        }
        // fire message back through pipeline, up to where a handshake may follow it in the same read
        int beforeTls = binaryInTheClear ? bytesBeforeTlsMayStart(ctx, msg) : 0;
        ctx.fireChannelRead(msg.readBytes(beforeTls > 0 ? beforeTls : actualReadableBytes()));
        if (beforeTls > 0) {
            endedByWhatWasPassedOn(ctx, msg);
        }
    }

    private boolean addBinaryRequestProxying(ChannelHandlerContext ctx) {
        if (ListenerRole.of(ctx.channel()) != ListenerRole.COMBINED) {
            return false;
        }
        // a raw TCP protocol (a database, a broker) may legitimately stay silent for long periods
        InboundConnectionActivity.markLongLived(ctx.channel());
        // a destination that is MockServer itself would nest one tunnel inside another without end
        OwnListener.remember(ctx.channel(), server);
        if (rawNetworkTrafficLog != null) {
            rawNetworkTrafficLog.addTo(ctx.pipeline(), true);
        }
        BinaryMessageFramer framer = BinaryMessageFramer.forConfiguration(configuration, mockServerLogger);
        addLastIfNotPresent(ctx.pipeline(), framer != null ? framer : new BinaryMessageGatherer());
        addLastIfNotPresent(ctx.pipeline(), new BinaryRequestProxyingHandler(configuration, httpState.getMockServerLogger(), httpState.getScheduler(), actionHandler.getHttpClient(), httpState));
        // what a read loop brings is one message from here on, so no read may be cut short by a buffer sized
        // for earlier ones
        BinaryAwareRecvByteBufAllocator.readWholeMessages(ctx.channel());
        // a binary connection stays binary: detecting again would hold a short message for more bytes, or take
        // one that starts like another protocol for that protocol. Over TLS nothing is left to look for; in
        // the clear this handler stays for one thing, a TLS handshake beginning (see decode)
        if (ctx.pipeline().get(SslHandler.class) != null) {
            ctx.pipeline().remove(this);
        } else {
            binaryInTheClear = true;
        }
        return true;
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
        if (directMemoryLimitReached(throwable)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + throwable.getMessage())
            );
            ServerClosedChannels.markClosed(ctx.channel());
        } else if (connectionClosedException(throwable)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception caught by port unification handler -> closing pipeline " + ctx.channel())
                    .setThrowable(throwable)
            );
            ServerClosedChannels.markClosed(ctx.channel());
        } else if (sslHandshakeException(throwable) || ClientTlsHandshakeFailureLog.isFailedHandshake(throwable)) {
            String message = throwable.getMessage() != null ? throwable.getMessage() : "";
            if (!message.contains("close_notify during handshake")) {
                clientTlsHandshakeFailureLog.log(mockServerLogger, configuration, ClientTlsHandshakeFailureLog.TCP, ctx.channel().remoteAddress(), throwable);
            }
            // bytes that are not TLS (NotSslRecordException) and a plain DecoderException are no failed
            // handshake: the branch below logs them, so a decoder fault is not silently dropped
        } else if (isSslOrDecoderFault(throwable)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("SSL or decoder fault caught by port unification handler -> closing pipeline " + ctx.channel() + sniDescription(ctx.channel()))
                        .setThrowable(boundedFault(throwable))
                );
            }
            ServerClosedChannels.markClosed(ctx.channel());
        }
        // left unmarked: the client closed or reset the connection, or failed its TLS handshake
        closeOnFlush(ctx.channel());
    }
}

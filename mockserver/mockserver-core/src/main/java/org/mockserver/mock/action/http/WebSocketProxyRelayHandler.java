package org.mockserver.mock.action.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.codec.HttpLineEndSplitGuard;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.ForwardCredentialHeaders;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.Expectation;
import org.mockserver.model.CrossProtocolTrigger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.mockserver.socket.ChannelReadPause;
import org.mockserver.socket.LingeringClose;
import org.mockserver.socket.NettyAllocator;
import org.mockserver.socket.NettyTransport;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockserver.exception.ExceptionHandling.boundedFaultDescriptionWithRootCause;
import static org.mockserver.exception.ExceptionHandling.upstreamHandshakeFailure;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpResponse.response;

/**
 * Proxy passthrough relay for WebSocket connections.
 *
 * <p>When a WebSocket upgrade request (HTTP GET with {@code Upgrade: websocket}) arrives in proxy mode and no
 * WebSocket mock expectation matches (or a {@code FORWARD} expectation matches), MockServer opens the upstream
 * WebSocket connection (honouring scheme/TLS), relays the {@code 101 Switching Protocols} handshake, and then relays
 * frames bidirectionally (text, binary, ping, pong, close) until either side closes.</p>
 *
 * <p>The relay reuses the inbound channel's event loop for the upstream connection, so both the client-facing and
 * upstream-facing halves run on the same single thread — there are no cross-thread races between the two frame relays.</p>
 *
 * <p>The upgrade exchange is recorded as a {@code FORWARDED_REQUEST} in the event log (request = the upgrade GET,
 * response = {@code 101} carrying the transcript of relayed frames), so {@code retrieveRecordedRequests} and the
 * dashboard show the WebSocket traffic. The transcript is bounded by
 * {@link Configuration#webSocketProxyMaxRecordedFrames()} frames per connection and each frame payload is capped at
 * {@link #MAX_RECORDED_FRAME_BYTES} bytes, mirroring the {@code maxLogEntries} memory-management philosophy.</p>
 *
 * <p><b>Scope (v1):</b> HTTP/1.1 upgrade relay only (plain and TLS upstream). HTTP/2 extended-CONNECT WebSocket is a
 * documented boundary — see docs/code/netty-pipeline.md.</p>
 */
public class WebSocketProxyRelayHandler {

    /**
     * Per-frame payload byte cap for the recorded transcript. Frames larger than this are relayed in full but their
     * recorded payload is truncated to this many bytes (flagged {@code truncated}). Keeps a single recorded frame from
     * pinning an unbounded buffer while still capturing enough to identify the traffic.
     */
    static final int MAX_RECORDED_FRAME_BYTES = 32 * 1024;
    /**
     * Absolute per-connection cap on the accumulated transcript JSON (the dominant memory consumer). Bounds the
     * worst case independently of the frame-count cap: 1000 frames × 32KB base64 (×1.33) could otherwise pin tens of
     * MB. Once the transcript reaches this size, further frames are relayed but not recorded (transcript truncated).
     */
    static final int MAX_TRANSCRIPT_JSON_CHARS = 8 * 1024 * 1024;
    private static final int MAX_FRAME_PAYLOAD_LENGTH = 65536;
    private static final AttributeKey<Boolean> ENDING = AttributeKey.valueOf("mockserver.webSocketRelayEnding");

    private final Configuration configuration;
    private final MockServerLogger mockServerLogger;
    private final NettySslContextFactory nettySslContextFactory;
    private ForwardLoopGuard forwardLoopGuard;
    private ForwardCredentialHeaders forwardCredentialHeaders;

    public WebSocketProxyRelayHandler(Configuration configuration, MockServerLogger mockServerLogger, NettySslContextFactory nettySslContextFactory) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.nettySslContextFactory = nettySslContextFactory;
    }

    void setForwardLoopGuard(ForwardLoopGuard forwardLoopGuard) {
        this.forwardLoopGuard = forwardLoopGuard;
    }

    void setForwardCredentialHeaders(ForwardCredentialHeaders forwardCredentialHeaders) {
        this.forwardCredentialHeaders = forwardCredentialHeaders;
    }

    /**
     * Whether the request is an HTTP/1.1 WebSocket upgrade handshake (GET + {@code Upgrade: websocket} +
     * {@code Connection: upgrade} + a {@code Sec-WebSocket-Key}).
     */
    public static boolean isWebSocketUpgrade(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
        if (method != null && !"GET".equalsIgnoreCase(method)) {
            return false;
        }
        String upgrade = request.getFirstHeader("Upgrade");
        String connection = request.getFirstHeader("Connection");
        String key = request.getFirstHeader("Sec-WebSocket-Key");
        return upgrade != null && upgrade.toLowerCase(Locale.ROOT).contains("websocket")
            && connection != null && connection.toLowerCase(Locale.ROOT).contains("upgrade")
            && key != null && !key.isEmpty();
    }

    /**
     * Establish the upstream WebSocket connection and, on a successful upstream handshake, relay the 101 downstream
     * and begin bidirectional frame relay. On any failure a 502 is written to the client and both channels are closed.
     */
    public void relay(final HttpRequest request, final ChannelHandlerContext clientCtx,
                      final String upstreamHost, final int upstreamPort, final boolean tlsUpstream) {
        relay(request, clientCtx, upstreamHost, upstreamPort, tlsUpstream, () -> {
        });
    }

    /**
     * As {@link #relay(HttpRequest, ChannelHandlerContext, String, int, boolean)}, running {@code responseEnded} once
     * the client's handshake response has been written. A relay that fails closes the client's connection instead.
     */
    public void relay(final HttpRequest request, final ChannelHandlerContext clientCtx,
                      final String upstreamHost, final int upstreamPort, final boolean tlsUpstream, final Runnable responseEnded) {
        relay(request, clientCtx, upstreamHost, upstreamPort, tlsUpstream, responseEnded, null);
    }

    /**
     * As {@link #relay(HttpRequest, ChannelHandlerContext, String, int, boolean, Runnable)}, counting the hop against
     * {@code forwardLoopMaxHops} when {@code loopRoute} is not null (a forward expectation); the unmatched proxy path
     * passes null and, as for HTTP, neither counts nor sends {@code x-mockserver-forwarded-by}.
     */
    void relay(final HttpRequest request, final ChannelHandlerContext clientCtx,
               final String upstreamHost, final int upstreamPort, final boolean tlsUpstream, final Runnable responseEnded,
               final ForwardLoopGuard.Route loopRoute) {
        final ForwardLoopGuard loopGuard = loopRoute != null ? forwardLoopGuard : null;
        final Channel clientChannel = clientCtx.channel();
        // forwardProxyBlockPrivateNetworks, as for any forward; connecting to the address checked means a second
        // DNS answer for the name cannot reach a blocked address. Unchanged (connect by name) when the setting is off.
        final InetSocketAddress upstreamAddress;
        try {
            upstreamAddress = InetAddressValidator.validateForwardTarget(configuration, InetSocketAddress.createUnresolved(upstreamHost, upstreamPort));
        } catch (IllegalArgumentException e) {
            failClient(clientChannel, request, e.getMessage());
            return;
        }
        // an upgrade relayed back here matches the same forward again, as a forwarded request does
        HttpResponse loopDetected = loopGuard != null ? loopGuard.refuseLoop(request, loopRoute, mockServerLogger) : null;
        if (loopDetected != null) {
            answerClientAndClose(clientChannel, loopDetected);
            return;
        }
        final URI uri;
        try {
            uri = buildUpstreamUri(request, upstreamHost, upstreamPort, tlsUpstream);
        } catch (Exception e) {
            failClient(clientChannel, request, "invalid upstream WebSocket URI: " + e.getMessage());
            return;
        }

        // custom headers to forward to the upstream (everything except the handshake headers Netty sets
        // itself and MockServer control headers that must not leak upstream)
        final HttpHeaders customHeaders = buildUpstreamCustomHeaders(request);
        List<String> forwardedBy = loopGuard != null ? loopGuard.forwardedEntries(request) : null;
        if (forwardedBy != null) {
            customHeaders.set(ForwardLoopGuard.HEADER_NAME, forwardedBy);
        }
        // a handshake carrying forwardCredentialHeadersByHost headers is sent over TLS with their context
        final SslContext credentialSslContext;
        try {
            credentialSslContext = forwardCredentialHeaders != null && forwardCredentialHeaders.addTo(customHeaders, upstreamHost, upstreamPort, tlsUpstream) && tlsUpstream
                ? forwardCredentialHeaders.sslContextFor(false, upstreamHost)
                : null;
        } catch (IllegalStateException noCredentialContext) {
            failClient(clientChannel, request, "unable to connect to upstream WebSocket server " + upstreamHost + ":" + upstreamPort + " - " + noCredentialContext.getMessage());
            return;
        }
        final String subprotocol = request.getFirstHeader("Sec-WebSocket-Protocol");

        final WebSocketClientHandshaker upstreamHandshaker = WebSocketClientHandshakerFactory.newHandshaker(
            uri, WebSocketVersion.V13, subprotocol == null || subprotocol.isEmpty() ? null : subprotocol,
            true, customHeaders, MAX_FRAME_PAYLOAD_LENGTH);

        final FrameTranscript transcript = new FrameTranscript(configuration.webSocketProxyMaxRecordedFrames());
        // read for each relay, so a change applies to the next upgrade
        final int maxHeaderSize = configuration.maxHeaderSize();
        // a handshake response is waited for as a forward's response is: maxSocketTimeout, capped by maxFutureTimeout
        final long maxSocketTimeout = configuration.maxSocketTimeoutInMillis();
        final long maxFutureTimeout = configuration.maxFutureTimeoutInMillis();
        final boolean futureTimeoutApplies = maxFutureTimeout > 0 && (maxSocketTimeout <= 0 || maxFutureTimeout < maxSocketTimeout);
        final long handshakeTimeoutMillis = futureTimeoutApplies ? maxFutureTimeout : maxSocketTimeout;
        final String handshakeTimeoutName = futureTimeoutApplies ? "maxFutureTimeout" : "maxSocketTimeout";

        Bootstrap bootstrap = new Bootstrap()
            .group(clientChannel.eventLoop())
            .channel(NettyTransport.socketChannelClassFor(clientChannel.eventLoop()))
            .option(ChannelOption.ALLOCATOR, NettyAllocator.ALLOCATOR)
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, configuration.socketConnectionTimeoutInMillis().intValue())
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ChannelPipeline pipeline = ch.pipeline();
                    if (tlsUpstream) {
                        // forwardProxyClient=true so the configured forward-proxy trust manager applies
                        // (forwardProxyTLSX509CertificatesTrustManagerType) — matching HttpClientInitializer /
                        // RelayConnectHandler. The false branch trusts only MockServer's own CA, which would fail
                        // wss to any real upstream and silently ignore the configured trust policy.
                        SslHandler sslHandler = (credentialSslContext != null ? credentialSslContext : nettySslContextFactory.createClientSslContext(true, false))
                            .newHandler(ch.alloc(), upstreamHost, upstreamPort);
                        // bounded as every other outbound handshake is, not by Netty's fixed 10 seconds
                        Long tlsHandshakeTimeoutMillis = configuration.socketConnectionTimeoutInMillis();
                        if (tlsHandshakeTimeoutMillis != null && tlsHandshakeTimeoutMillis > 0) {
                            sslHandler.setHandshakeTimeoutMillis(tlsHandshakeTimeoutMillis);
                        }
                        pipeline.addLast(sslHandler);
                    }
                    // a status line is short, so it keeps Netty's limit; the headers follow maxHeaderSize as a forward's do
                    HttpClientCodec codec = new HttpClientCodec(HttpObjectDecoder.DEFAULT_MAX_INITIAL_LINE_LENGTH, maxHeaderSize, HttpObjectDecoder.DEFAULT_MAX_CHUNK_SIZE);
                    HttpLineEndSplitGuard lineEndSplitGuard = new HttpLineEndSplitGuard(codec);
                    pipeline.addLast(lineEndSplitGuard.beforeCodec());
                    pipeline.addLast(codec);
                    pipeline.addLast(lineEndSplitGuard.afterCodec());
                    pipeline.addLast(new HttpObjectAggregator(MAX_FRAME_PAYLOAD_LENGTH));
                    pipeline.addLast(new UpstreamHandshakeHandler(request, clientCtx, upstreamHandshaker, subprotocol, transcript, maxHeaderSize, handshakeTimeoutMillis, handshakeTimeoutName, responseEnded, forwardedBy != null));
                }
            });

        bootstrap.connect(upstreamAddress).addListener((ChannelFutureListener) future -> {
            if (!future.isSuccess()) {
                failClient(clientChannel, request, "unable to connect to upstream WebSocket server " + upstreamHost + ":" + upstreamPort
                    + (future.cause() != null ? " - " + future.cause().getMessage() : ""));
            }
        });
    }

    private URI buildUpstreamUri(HttpRequest request, String host, int port, boolean tls) {
        String scheme = tls ? "wss" : "ws";
        String path = request.getPath() != null && request.getPath().getValue() != null ? request.getPath().getValue() : "/";
        if (!path.startsWith("/")) {
            path = "/" + path;
        }
        StringBuilder query = new StringBuilder();
        if (request.getQueryStringParameters() != null && !request.getQueryStringParameters().isEmpty()) {
            boolean first = true;
            for (org.mockserver.model.Parameter param : request.getQueryStringParameters().getEntries()) {
                for (NottableString value : param.getValues()) {
                    query.append(first ? '?' : '&');
                    query.append(urlEncode(param.getName().getValue())).append('=').append(urlEncode(value.getValue()));
                    first = false;
                }
            }
        }
        return URI.create(scheme + "://" + host + ":" + port + path + query);
    }

    private static String urlEncode(String value) {
        try {
            return java.net.URLEncoder.encode(value, "UTF-8");
        } catch (Exception e) {
            return value;
        }
    }

    /**
     * Builds the set of client request headers to forward on the upstream WebSocket handshake: every header
     * except the ones Netty's handshaker manages itself ({@link #isManagedHandshakeHeader}) and MockServer
     * control headers that must never leak upstream ({@link #isSuppressedRelayHeader}). Package-private and
     * static so the header-copy policy can be unit-tested directly without live networking.
     */
    static HttpHeaders buildUpstreamCustomHeaders(HttpRequest request) {
        HttpHeaders customHeaders = new DefaultHttpHeaders();
        if (request.getHeaderList() != null) {
            for (Header header : request.getHeaderList()) {
                String name = header.getName().getValue();
                if (isManagedHandshakeHeader(name) || isSuppressedRelayHeader(name)) {
                    continue;
                }
                for (NottableString value : header.getValues()) {
                    customHeaders.add(name, value.getValue());
                }
            }
        }
        return customHeaders;
    }

    /**
     * MockServer control headers and proxy headers that must never be relayed to the upstream WebSocket server.
     * Unlike {@link #isManagedHandshakeHeader} (headers Netty's handshaker owns), these apply only to the
     * connection to MockServer: {@link Expectation#FORCE_RESPONSE_INDEX_HEADER}, Proxy-Authorization and
     * Proxy-Connection. The HTTP forward mapper filters them too ({@code MockServerHttpRequestToFullHttpRequest.setHeader}).
     * This passthrough path builds the upstream handshake headers itself, bypassing that mapper, so the
     * filter is applied here too.
     */
    static boolean isSuppressedRelayHeader(String name) {
        return name.equalsIgnoreCase(Expectation.FORCE_RESPONSE_INDEX_HEADER)
            || name.equalsIgnoreCase("Proxy-Authorization")
            || name.equalsIgnoreCase("Proxy-Connection");
    }

    /**
     * Headers Netty's client handshaker sets itself; forwarding them from the client request would duplicate or
     * corrupt the upstream handshake, so they are excluded from the forwarded custom headers.
     */
    private static boolean isManagedHandshakeHeader(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.equals("host")
            || lower.equals("upgrade")
            || lower.equals("connection")
            || lower.equals("sec-websocket-key")
            || lower.equals("sec-websocket-version")
            || lower.equals("sec-websocket-protocol")
            || lower.equals("sec-websocket-extensions")
            || lower.equals("content-length");
    }

    /**
     * The upstream's handshake response headers that the client is answered with: all but the hop-by-hop ones (those
     * the {@code Connection} header names too), {@code Content-Length}, which a {@code 101} does not carry, and the
     * handshake's own {@code Sec-WebSocket-*} fields, which the client's handshake answers from its own request.
     * {@code Sec-WebSocket-Extensions} goes with them: the client's offer is not forwarded upstream and each leg's
     * frames are decoded and encoded again, so no extension is in force end to end.
     */
    static HttpHeaders buildDownstreamResponseHeaders(HttpHeaders upstreamHeaders) {
        Set<String> namedByConnection = new HashSet<>();
        for (String connection : upstreamHeaders.getAll(HttpHeaderNames.CONNECTION)) {
            for (String token : connection.split(",")) {
                namedByConnection.add(token.trim().toLowerCase(Locale.ROOT));
            }
        }
        HttpHeaders headers = new DefaultHttpHeaders();
        for (Map.Entry<String, String> header : upstreamHeaders) {
            String name = header.getKey().toLowerCase(Locale.ROOT);
            if (!isNotRelayedResponseHeader(name) && !namedByConnection.contains(name)) {
                headers.add(header.getKey(), header.getValue());
            }
        }
        return headers;
    }

    private static boolean isNotRelayedResponseHeader(String lowerCaseName) {
        return lowerCaseName.equals("connection")
            || lowerCaseName.equals("upgrade")
            || lowerCaseName.equals("keep-alive")
            || lowerCaseName.equals("transfer-encoding")
            || lowerCaseName.equals("te")
            || lowerCaseName.equals("trailer")
            || lowerCaseName.equals("content-length")
            || lowerCaseName.startsWith("proxy-")
            || lowerCaseName.startsWith("sec-websocket-");
    }

    private void failClient(Channel clientChannel, HttpRequest request, String message) {
        logFailure(request, message);
        answerClientAndClose(clientChannel, response()
            .withStatusCode(HttpResponseStatus.BAD_GATEWAY.code())
            .withBody(message));
    }

    private void logFailure(HttpRequest request, String message) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(request.getLogCorrelationId())
                    .setHttpRequest(request)
                    .setMessageFormat("WebSocket proxy passthrough failed:{}")
                    .setArguments(message)
            );
        }
    }

    private static void answerClientAndClose(Channel clientChannel, HttpResponse response) {
        if (clientChannel.isActive()) {
            clientChannel.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        }
    }

    // ---- upstream handshake handler ---------------------------------------------------------------------------

    /**
     * Drives the upstream client-side WebSocket handshake. On completion it relays the 101 downstream (server-side
     * handshake against the original client), installs the two frame-relay handlers, and records the upgrade exchange.
     */
    private final class UpstreamHandshakeHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

        private final HttpRequest request;
        private final ChannelHandlerContext clientCtx;
        private final WebSocketClientHandshaker handshaker;
        private final String requestedSubprotocol;
        private final FrameTranscript transcript;
        private final int maxHeaderSize;
        private final long handshakeTimeoutMillis;
        private final String handshakeTimeoutName;
        private final Runnable responseEnded;
        // the hop was counted, so an upstream's 508 is a refused loop further on and is relayed
        private final boolean relaysLoopDetected;
        // the client has been answered 502, or has gone: whatever the upstream connection reports after that is not reported
        private boolean failed;
        private ScheduledFuture<?> handshakeTimeout;

        private UpstreamHandshakeHandler(HttpRequest request, ChannelHandlerContext clientCtx,
                                         WebSocketClientHandshaker handshaker, String requestedSubprotocol,
                                         FrameTranscript transcript, int maxHeaderSize, long handshakeTimeoutMillis,
                                         String handshakeTimeoutName, Runnable responseEnded, boolean relaysLoopDetected) {
            this.request = request;
            this.clientCtx = clientCtx;
            this.handshaker = handshaker;
            this.requestedSubprotocol = requestedSubprotocol;
            this.transcript = transcript;
            this.maxHeaderSize = maxHeaderSize;
            this.handshakeTimeoutMillis = handshakeTimeoutMillis;
            this.handshakeTimeoutName = handshakeTimeoutName;
            this.responseEnded = responseEnded;
            this.relaysLoopDetected = relaysLoopDetected;
        }

        private void fail(ChannelHandlerContext ctx, String message) {
            if (!failed) {
                failed = true;
                failClient(clientCtx.channel(), request, message);
            }
            ctx.close();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            // both channels share one event loop, so these callbacks never race the handler's own
            clientCtx.channel().closeFuture().addListener(future -> {
                if (!handshaker.isHandshakeComplete()) {
                    failed = true;
                    ctx.close();
                }
            });
            if (handshakeTimeoutMillis > 0) {
                // from the TCP connection, so it covers a TLS handshake too; cancelled when this handler is removed
                handshakeTimeout = ctx.executor().schedule(() -> fail(ctx,
                        "upstream WebSocket handshake response was not received within " + handshakeTimeoutName + " (" + handshakeTimeoutMillis + " ms)"),
                    handshakeTimeoutMillis, TimeUnit.MILLISECONDS);
            }
            handshaker.handshake(ctx.channel());
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            if (handshakeTimeout != null) {
                handshakeTimeout.cancel(false);
            }
        }

        /**
         * A failed TLS handshake is reported first as this event, and only as that for a timeout or a close: the
         * client is answered with its reason before the connection's close is reported in its place.
         */
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
            if (evt instanceof SslHandshakeCompletionEvent && !((SslHandshakeCompletionEvent) evt).isSuccess()) {
                fail(ctx, "TLS with the upstream failed: " + boundedFaultDescriptionWithRootCause(upstreamHandshakeFailure(((SslHandshakeCompletionEvent) evt).cause())));
            }
            super.userEventTriggered(ctx, evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // upstream closed before the handshake completed
            if (!handshaker.isHandshakeComplete()) {
                fail(ctx, "upstream WebSocket connection closed before handshake completed");
            }
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse msg) {
            if (failed || handshaker.isHandshakeComplete()) {
                return;
            }
            final Channel upstreamChannel = ctx.channel();
            // a response the codec stopped decoding holds only what it had read, and what follows is discarded or,
            // once the codec is replaced, read as frames: it is never a handshake to finish
            Throwable undecoded = msg.decoderResult().cause();
            if (undecoded instanceof TooLongHttpHeaderException) {
                fail(ctx, "upstream WebSocket handshake response headers are larger than maxHeaderSize (" + maxHeaderSize + " bytes)");
                return;
            }
            if (msg.decoderResult().isFailure()) {
                fail(ctx, "upstream WebSocket handshake response could not be read: " + (undecoded.getMessage() != null ? undecoded.getMessage() : undecoded.getClass().getSimpleName()));
                return;
            }
            if (relaysLoopDetected && msg.status().code() == ForwardLoopGuard.LOOP_DETECTED) {
                // a hop further on refused the loop: its 508 is relayed, as a forward relays it
                failed = true;
                String reason = msg.content().toString(StandardCharsets.UTF_8);
                logFailure(request, "upstream answered the WebSocket handshake with 508 Loop Detected: " + reason);
                answerClientAndClose(clientCtx.channel(), response()
                    .withStatusCode(ForwardLoopGuard.LOOP_DETECTED)
                    .withReasonPhrase(msg.status().reasonPhrase())
                    .withBody(reason));
                ctx.close();
                return;
            }
            final String negotiatedSubprotocol;
            try {
                handshaker.finishHandshake(upstreamChannel, msg);
                negotiatedSubprotocol = handshaker.actualSubprotocol();
            } catch (WebSocketHandshakeException e) {
                fail(ctx, "upstream WebSocket handshake failed: " + e.getMessage());
                return;
            }
            // upstream pipeline now speaks WebSocket frames; remove this handshake handler and relay upstream->client
            upstreamChannel.pipeline().remove(this);
            maybeAddIdleHandler(upstreamChannel.pipeline());
            upstreamChannel.pipeline().addLast(new FrameRelayHandler(
                clientCtx.channel(), upstreamChannel, FrameDirection.UPSTREAM_TO_CLIENT, transcript, request));

            // complete the downstream (server-side) handshake to relay the 101 to the original client
            completeDownstreamHandshake(negotiatedSubprotocol, buildDownstreamResponseHeaders(msg.headers()), upstreamChannel);
        }

        private void completeDownstreamHandshake(String negotiatedSubprotocol, HttpHeaders responseHeaders, Channel upstreamChannel) {
            final Channel clientChannel = clientCtx.channel();
            FullHttpRequest nettyRequest = buildNettyUpgradeRequest(request);
            String host = request.getFirstHeader("Host");
            boolean tlsDownstream = clientChannel.pipeline().get(io.netty.handler.ssl.SslHandler.class) != null;
            String wsScheme = tlsDownstream ? "wss" : "ws";
            String wsUrl = wsScheme + "://" + (host != null ? host : "localhost") + request.getPath().getValue();

            WebSocketServerHandshakerFactory wsFactory = new WebSocketServerHandshakerFactory(
                wsUrl, negotiatedSubprotocol, true, MAX_FRAME_PAYLOAD_LENGTH);
            final WebSocketServerHandshaker serverHandshaker = wsFactory.newHandshaker(nettyRequest);
            if (serverHandshaker == null) {
                WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(clientChannel).addListener(future -> responseEnded.run());
                nettyRequest.release();
                upstreamChannel.close();
                return;
            }
            serverHandshaker.handshake(clientChannel, nettyRequest, responseHeaders, clientChannel.newPromise()).addListener((ChannelFutureListener) future -> {
                try {
                    responseEnded.run();
                    if (future.isSuccess()) {
                        removeHttpServerHandlers(clientCtx);
                        maybeAddIdleHandler(clientChannel.pipeline());
                        FrameRelayHandler clientRelay = new FrameRelayHandler(
                            upstreamChannel, clientChannel, FrameDirection.CLIENT_TO_UPSTREAM, transcript, request);
                        clientChannel.pipeline().addLast(clientRelay);
                        // each handler ends the other leg when its own channel closes; one closed already never tells it
                        if (!clientChannel.isActive()) {
                            clientRelay.ownChannelEnded();
                        }
                        transcript.flushOnClose(clientChannel, upstreamChannel, () ->
                            recordUpgrade(request, negotiatedSubprotocol, responseHeaders, transcript));

                        CrossProtocolEventBus.getInstance().fire(
                            CrossProtocolTrigger.WEBSOCKET_CONNECT,
                            request.getPath() != null ? request.getPath().getValue() : "/");

                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(request.getLogCorrelationId())
                                    .setHttpRequest(request)
                                    .setMessageFormat("relaying WebSocket passthrough for request:{}to upstream:{}")
                                    .setArguments(request, upstreamChannel.remoteAddress())
                            );
                        }
                    } else {
                        failClient(clientChannel, request, "downstream WebSocket handshake failed: "
                            + (future.cause() != null ? future.cause().getMessage() : "unknown"));
                        upstreamChannel.close();
                    }
                } finally {
                    nettyRequest.release();
                }
            });
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!handshaker.isHandshakeComplete()) {
                fail(ctx, "upstream WebSocket error: " + cause.getMessage());
            }
            ctx.close();
        }
    }

    // ---- bidirectional frame relay ----------------------------------------------------------------------------

    private enum FrameDirection {
        CLIENT_TO_UPSTREAM, UPSTREAM_TO_CLIENT
    }

    /**
     * Relays WebSocket frames from the channel it is installed on to the peer channel, recording each frame in the
     * shared transcript. A {@link CloseWebSocketFrame} is relayed and then both channels are closed.
     */
    private final class FrameRelayHandler extends SimpleChannelInboundHandler<WebSocketFrame> {

        private final Channel peerChannel;
        private final Channel ownChannel;
        private final FrameDirection direction;
        private final FrameTranscript transcript;
        private final HttpRequest request;
        // whether this half holds a read pause on the peer (released when this channel drains or closes)
        private boolean pausingPeer;
        private boolean closeFrameRelayed;
        private boolean ended;

        private FrameRelayHandler(Channel peerChannel, Channel ownChannel, FrameDirection direction,
                                  FrameTranscript transcript, HttpRequest request) {
            this.peerChannel = peerChannel;
            this.ownChannel = ownChannel;
            this.direction = direction;
            this.transcript = transcript;
            this.request = request;
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, WebSocketFrame frame) {
            if (isEnding(ownChannel) || isEnding(peerChannel)) {
                // a leg is being ended: nothing more is relayed, so what the side still being read sends
                // is not queued for a side that has stopped reading
                return;
            }
            transcript.record(direction, frame);
            WebSocketFrame relayed = cloneFrame(frame);
            if (frame instanceof CloseWebSocketFrame) {
                closeFrameRelayed = true;
                releasePeer();
                endAfterFlush(peerChannel, relayed);
                endAfterFlush(ownChannel, null);
            } else if (peerChannel.isActive()) {
                peerChannel.writeAndFlush(relayed);
            } else {
                relayed.release();
            }
        }

        /**
         * Ends the peer once what this channel sent it has been written, after a close frame: the one this channel sent,
         * or 1001 (going away) for a channel that ended without one.
         */
        private void ownChannelEnded() {
            if (ended) {
                return;
            }
            ended = true;
            releasePeer();
            endAfterFlush(peerChannel, closeFrameRelayed ? null : new CloseWebSocketFrame(WebSocketCloseStatus.ENDPOINT_UNAVAILABLE));
        }

        /**
         * Lets the peer be read again, so that it is read to its end as it ends rather than reset with input unread.
         */
        private void releasePeer() {
            if (pausingPeer) {
                pausingPeer = false;
                ChannelReadPause.resume(peerChannel);
            }
        }

        /**
         * Backpressure: when THIS channel's outbound buffer saturates (the peer that feeds it via its own relay
         * handler is producing faster than this channel can drain), pause reads on that source channel — and resume
         * when it drains. This is the standard Netty proxy pattern; without it a slow peer would grow the other side's
         * {@code ChannelOutboundBuffer} without bound. Both relay halves share the client event loop, so mutating the
         * peer channel config here is thread-safe.
         */
        @Override
        public void channelWritabilityChanged(ChannelHandlerContext ctx) {
            boolean writable = ctx.channel().isWritable();
            if (!writable && !pausingPeer) {
                pausingPeer = true;
                ChannelReadPause.pause(peerChannel);
            } else if (writable && pausingPeer) {
                pausingPeer = false;
                ChannelReadPause.resume(peerChannel);
            }
            ctx.fireChannelWritabilityChanged();
        }

        /**
         * Idle relay reaping (opt-in): when {@code webSocketProxyIdleTimeoutSeconds > 0}, an {@link IdleStateHandler}
         * on each relay channel fires {@code ALL_IDLE} once neither side has sent a frame for the configured period,
         * and the relay is torn down (which flushes the transcript). Default off (0) — long-lived idle WebSocket
         * connections are left to TCP keep-alive and peer-close propagation.
         */
        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof io.netty.handler.timeout.IdleStateEvent
                && ((io.netty.handler.timeout.IdleStateEvent) evt).state() == io.netty.handler.timeout.IdleState.ALL_IDLE) {
                if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.DEBUG)
                            .setCorrelationId(request.getLogCorrelationId())
                            .setMessageFormat("closing idle WebSocket passthrough relay ({})")
                            .setArguments(direction)
                    );
                }
                closeQuietly(peerChannel);
                closeQuietly(ownChannel);
                return;
            }
            ctx.fireUserEventTriggered(evt);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            ownChannelEnded();
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (mockServerLogger.isEnabledForInstance(Level.DEBUG)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.DEBUG)
                        .setCorrelationId(request.getLogCorrelationId())
                        .setMessageFormat("WebSocket relay error ({}):{}")
                        .setArguments(direction, cause.getMessage())
                );
            }
            ctx.close();
        }
    }

    private static WebSocketFrame cloneFrame(WebSocketFrame frame) {
        io.netty.buffer.ByteBuf content = frame.content().retainedDuplicate();
        boolean fin = frame.isFinalFragment();
        int rsv = frame.rsv();
        if (frame instanceof TextWebSocketFrame) {
            return new TextWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof BinaryWebSocketFrame) {
            return new BinaryWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof PingWebSocketFrame) {
            return new PingWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof PongWebSocketFrame) {
            return new PongWebSocketFrame(fin, rsv, content);
        } else if (frame instanceof CloseWebSocketFrame) {
            return new CloseWebSocketFrame(fin, rsv, content);
        } else {
            return new ContinuationWebSocketFrame(fin, rsv, content);
        }
    }

    /**
     * Ends a relay leg once what has been written to it is flushed, the last frame (if any) included: its output is
     * ended, what it still sends is read and dropped, and its socket closes when the other end closes, or
     * {@link LingeringClose#LINGER_MILLIS} after this is called, flushed or not. Closed at once, the frames still queued
     * for it would be dropped, and a socket closed with input unread sends a reset, which discards what the kernel has
     * not yet sent.
     */
    private static void endAfterFlush(Channel channel, WebSocketFrame lastFrame) {
        if (!channel.isActive() || channel.attr(ENDING).setIfAbsent(Boolean.TRUE) != null) {
            ReferenceCountUtil.release(lastFrame);
            return;
        }
        // bounded from now, as a side that has stopped reading would otherwise hold the leg open for ever
        LingeringClose.closeSocketUnlessClosedWithinLinger(channel);
        Object last = lastFrame != null ? lastFrame : Unpooled.EMPTY_BUFFER;
        channel.writeAndFlush(last).addListener(written -> LingeringClose.close(channel));
    }

    private static boolean isEnding(Channel channel) {
        return channel.attr(ENDING).get() != null;
    }

    private static void closeQuietly(Channel channel) {
        if (channel != null && channel.isActive()) {
            channel.close();
        }
    }

    /**
     * Install an {@link io.netty.handler.timeout.IdleStateHandler} that fires {@code ALL_IDLE} (neither side has sent
     * a frame) after {@code webSocketProxyIdleTimeoutSeconds}, so {@code FrameRelayHandler#userEventTriggered} can tear
     * the relay down. Default off (0/negative) — a no-op, leaving idle WebSocket connections to TCP keep-alive.
     */
    private void maybeAddIdleHandler(ChannelPipeline pipeline) {
        int idleSeconds = configuration.webSocketProxyIdleTimeoutSeconds();
        if (idleSeconds > 0) {
            pipeline.addLast(new io.netty.handler.timeout.IdleStateHandler(0, 0, idleSeconds));
        }
    }

    // ---- pipeline surgery -------------------------------------------------------------------------------------

    /**
     * Strip the HTTP server handlers from the client pipeline after the downstream WebSocket handshake, so the
     * client channel now carries only the WebSocket frame codec (installed by the server handshaker) plus the relay
     * handler. Mirrors {@code HttpWebSocketResponseActionHandler.removePipelineHandlers} but removes by simple class
     * name so this core class does not depend on the netty-module handler types.
     */
    // package-private for unit testing of the pipeline-stripping logic
    static void removeHttpServerHandlers(ChannelHandlerContext ctx) {
        removeBySimpleName(ctx, "MockServerHttpServerCodec");
        removeBySimpleName(ctx, "HttpRequestHandler");
        removeBySimpleName(ctx, "HttpContentLengthRemover");
        removeBySimpleName(ctx, "EarlyMatchingHandler");
        removeBySimpleName(ctx, "CallbackWebSocketServerHandler");
        removeBySimpleName(ctx, "DashboardWebSocketHandler");
        removeBySimpleName(ctx, "McpStreamableHttpHandler");
    }

    private static void removeBySimpleName(ChannelHandlerContext ctx, String simpleName) {
        try {
            for (java.util.Map.Entry<String, ChannelHandler> entry : ctx.pipeline()) {
                if (simpleName.equals(entry.getValue().getClass().getSimpleName())) {
                    ctx.pipeline().remove(entry.getKey());
                }
            }
        } catch (Exception ignored) {
            // handler already removed or pipeline mutated concurrently
        }
    }

    private static FullHttpRequest buildNettyUpgradeRequest(HttpRequest request) {
        String uri = request.getPath().getValue();
        if (request.getQueryStringParameters() != null && !request.getQueryStringParameters().isEmpty()) {
            StringBuilder qs = new StringBuilder("?");
            boolean first = true;
            for (org.mockserver.model.Parameter param : request.getQueryStringParameters().getEntries()) {
                for (NottableString value : param.getValues()) {
                    if (!first) {
                        qs.append("&");
                    }
                    qs.append(urlEncode(param.getName().getValue())).append("=").append(urlEncode(value.getValue()));
                    first = false;
                }
            }
            uri = uri + qs;
        }
        String method = request.getMethod() != null ? request.getMethod().getValue() : "GET";
        DefaultFullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.valueOf(method), uri);
        if (request.getHeaderList() != null) {
            for (Header header : request.getHeaderList()) {
                for (NottableString value : header.getValues()) {
                    nettyRequest.headers().add(header.getName().getValue(), value.getValue());
                }
            }
        }
        if (!nettyRequest.headers().contains(HttpHeaderNames.HOST)) {
            nettyRequest.headers().set(HttpHeaderNames.HOST, "localhost");
        }
        return nettyRequest;
    }

    // ---- recording --------------------------------------------------------------------------------------------

    private void recordUpgrade(HttpRequest request, String negotiatedSubprotocol, HttpHeaders responseHeaders, FrameTranscript transcript) {
        HttpResponse logResponse = response()
            .withStatusCode(HttpResponseStatus.SWITCHING_PROTOCOLS.code())
            .withReasonPhrase("Switching Protocols")
            .withHeader("Upgrade", "websocket")
            .withHeader("Connection", "Upgrade")
            .withHeader("x-mockserver-websocket-frames", String.valueOf(transcript.recordedCount()))
            .withHeader("x-mockserver-websocket-transcript-truncated", String.valueOf(transcript.isTruncated()))
            .withBody(transcript.toJson());
        if (negotiatedSubprotocol != null && !negotiatedSubprotocol.isEmpty()) {
            logResponse.withHeader("Sec-WebSocket-Protocol", negotiatedSubprotocol);
        }
        for (String name : responseHeaders.names()) {
            // the upstream cannot add a value to the relay's own recording headers
            if (!name.toLowerCase(Locale.ROOT).startsWith("x-mockserver-websocket-")) {
                logResponse.withHeader(name, responseHeaders.getAll(name).toArray(new String[0]));
            }
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setLogLevel(Level.INFO)
                .setCorrelationId(request.getLogCorrelationId())
                .setHttpRequest(request)
                .setHttpResponse(logResponse)
                .setExpectation(request, logResponse)
                .setMessageFormat("relayed WebSocket passthrough with {} frame(s) for forwarded upgrade request:{}")
                .setArguments(transcript.recordedCount(), request)
        );
    }

    /**
     * Bounded, per-connection capture of relayed WebSocket frames. Records at most {@code maxFrames} frames; each
     * frame payload is truncated to {@link #MAX_RECORDED_FRAME_BYTES}. Text frame payloads are recorded as UTF-8
     * strings, all other opcodes as base64. The transcript is serialised to JSON and flushed to the event log
     * exactly once when the connection closes.
     */
    private static final class FrameTranscript {

        private final int maxFrames;
        private final StringBuilder json = new StringBuilder("[");
        private final AtomicInteger recorded = new AtomicInteger(0);
        private final AtomicBoolean truncated = new AtomicBoolean(false);
        private final AtomicBoolean flushed = new AtomicBoolean(false);

        private FrameTranscript(int maxFrames) {
            this.maxFrames = maxFrames;
        }

        synchronized void record(FrameDirection direction, WebSocketFrame frame) {
            if (maxFrames <= 0) {
                // frame recording disabled (webSocketProxyMaxRecordedFrames=0) — this is NOT truncation; the
                // upgrade handshake is still recorded with an honest (false) truncated flag and a 0 frame count.
                return;
            }
            if (recorded.get() >= maxFrames || json.length() >= MAX_TRANSCRIPT_JSON_CHARS) {
                truncated.set(true);
                return;
            }
            io.netty.buffer.ByteBuf content = frame.content();
            int available = content.readableBytes();
            int len = Math.min(available, MAX_RECORDED_FRAME_BYTES);
            byte[] bytes = new byte[len];
            content.getBytes(content.readerIndex(), bytes);
            boolean framePayloadTruncated = available > len;

            String opcode = opcodeOf(frame);
            boolean isText = frame instanceof TextWebSocketFrame;
            String payloadField;
            String payloadValue;
            if (isText) {
                payloadField = "text";
                payloadValue = new String(bytes, StandardCharsets.UTF_8);
            } else {
                payloadField = "base64";
                payloadValue = Base64.getEncoder().encodeToString(bytes);
            }

            if (recorded.get() > 0) {
                json.append(',');
            }
            json.append("{\"direction\":\"")
                .append(direction == FrameDirection.CLIENT_TO_UPSTREAM ? "CLIENT_TO_UPSTREAM" : "UPSTREAM_TO_CLIENT")
                .append("\",\"opcode\":\"").append(opcode)
                .append("\",\"").append(payloadField).append("\":\"").append(escapeJson(payloadValue))
                .append("\",\"length\":").append(available)
                .append(",\"truncated\":").append(framePayloadTruncated)
                .append('}');
            recorded.incrementAndGet();
        }

        int recordedCount() {
            return recorded.get();
        }

        boolean isTruncated() {
            return truncated.get();
        }

        synchronized String toJson() {
            return json.toString() + "]";
        }

        /**
         * Register close listeners on both channels so that when the FIRST of them closes, the recording callback runs
         * exactly once (guarded by an {@link AtomicBoolean}).
         */
        void flushOnClose(Channel a, Channel b, Runnable flush) {
            ChannelFutureListener listener = f -> {
                if (flushed.compareAndSet(false, true)) {
                    flush.run();
                }
            };
            a.closeFuture().addListener(listener);
            b.closeFuture().addListener(listener);
        }

        private static String opcodeOf(WebSocketFrame frame) {
            if (frame instanceof TextWebSocketFrame) {
                return "TEXT";
            } else if (frame instanceof BinaryWebSocketFrame) {
                return "BINARY";
            } else if (frame instanceof PingWebSocketFrame) {
                return "PING";
            } else if (frame instanceof PongWebSocketFrame) {
                return "PONG";
            } else if (frame instanceof CloseWebSocketFrame) {
                return "CLOSE";
            } else {
                return "CONTINUATION";
            }
        }

        private static String escapeJson(String value) {
            StringBuilder sb = new StringBuilder(value.length() + 16);
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                switch (c) {
                    case '"' -> sb.append("\\\"");
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    case '\b' -> sb.append("\\b");
                    case '\f' -> sb.append("\\f");
                    default -> {
                        if (c < 0x20) {
                            sb.append(String.format("\\u%04x", (int) c));
                        } else {
                            sb.append(c);
                        }
                    }
                }
            }
            return sb.toString();
        }
    }
}

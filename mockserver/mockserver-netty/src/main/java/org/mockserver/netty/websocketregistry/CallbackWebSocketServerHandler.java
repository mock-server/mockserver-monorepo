package org.mockserver.netty.websocketregistry;

import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.codec.HttpChunkLineLimiter;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.netty.unification.Http1RequestCutShort;
import org.mockserver.netty.unification.Http2RequestHeaderLimit;
import org.mockserver.netty.unification.Http2StreamFaults;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import java.util.Arrays;
import java.util.List;

import static com.google.common.net.HttpHeaders.HOST;
import static org.mockserver.closurecallback.websocketclient.WebSocketClient.CLIENT_REGISTRATION_ID_HEADER;
import static org.mockserver.exception.ExceptionHandling.boundedFault;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.DIRECT_MEMORY_LIMIT_REACHED;
import static org.mockserver.exception.ExceptionHandling.directMemoryLimitReached;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
import static org.mockserver.formatting.StringFormatter.escapeControlCharactersForLog;
import static org.mockserver.netty.unification.PortUnificationHandler.isHttp2Enabled;
import static org.mockserver.netty.unification.PortUnificationHandler.isSslEnabledUpstream;

/**
 * @author jamesdbloom
 */
@ChannelHandler.Sharable
public class CallbackWebSocketServerHandler extends ChannelInboundHandlerAdapter {

    private static final AttributeKey<Boolean> CHANNEL_UPGRADED_FOR_CALLBACK_WEB_SOCKET = AttributeKey.valueOf("CHANNEL_UPGRADED_FOR_CALLBACK_WEB_SOCKET");
    // The handshaker is created during the HTTP upgrade and read again on a later CloseWebSocketFrame,
    // so it must span channelRead invocations - but this handler is @Sharable, meaning ONE instance
    // serves every channel it is added to. A shared mutable instance field would let concurrent
    // connections (and, once one instance spans a connection's child streams, concurrent streams)
    // clobber each other's handshake state, so it lives on the channel instead.
    private static final AttributeKey<WebSocketServerHandshaker> HANDSHAKER = AttributeKey.valueOf("CALLBACK_WEB_SOCKET_HANDSHAKER");
    private static final String UPGRADE_CHANNEL_FOR_CALLBACK_WEB_SOCKET_URI = "/_mockserver_callback_websocket";
    private static final String CAPABILITIES_PARAMETER = "capabilities";
    private static final String CAPABILITIES_HEADER = "X-MockServer-Capabilities";
    private static final String BREAKPOINT_RELEASED_CAPABILITY = "breakpointReleased";
    private final HttpState httpState;
    private final MockServerLogger mockServerLogger;
    private final WebSocketClientRegistry webSocketClientRegistry;

    public CallbackWebSocketServerHandler(HttpState httpStateHandler) {
        httpState = httpStateHandler;
        webSocketClientRegistry = httpStateHandler.getWebSocketClientRegistry();
        mockServerLogger = httpStateHandler.getMockServerLogger();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean release = true;
        try {
            if (msg instanceof FullHttpRequest && isCallbackUpgradeUri(((FullHttpRequest) msg).uri())) {
                if (isHttp2Enabled(ctx.channel())) {
                    if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.TRACE)
                                .setMessageFormat("WebSocket upgrade not supported over HTTP/2 for callback connection:{}")
                                .setArguments(ctx.channel().localAddress())
                        );
                    }
                    // This branch fires ONLY on HTTP/2, so the 501 must carry the request's stream
                    // id - otherwise it goes out on a phantom server-initiated stream and the client
                    // hangs instead of being told cleanly that WebSocket upgrade is unsupported.
                    DefaultFullHttpResponse notImplemented = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_IMPLEMENTED, Unpooled.EMPTY_BUFFER);
                    Http2StreamIds.stampFromNettyRequest(notImplemented, (FullHttpRequest) msg);
                    ctx.channel().writeAndFlush(notImplemented);
                } else {
                    ControlPlaneWebSocketUpgradeGate.Admission admission = ControlPlaneWebSocketUpgradeGate.admitUpgrade(ctx, (FullHttpRequest) msg, httpState, mockServerLogger, isSslEnabledUpstream(ctx.channel()), "callback");
                    if (admission != null) {
                        upgradeChannel(ctx, (FullHttpRequest) msg, admission);
                    }
                }
            } else if (msg instanceof WebSocketFrame && Boolean.TRUE.equals(ctx.channel().attr(CHANNEL_UPGRADED_FOR_CALLBACK_WEB_SOCKET).get())) {
                handleWebSocketFrame(ctx, (WebSocketFrame) msg);
            } else {
                release = false;
                ctx.fireChannelRead(msg);
            }
        } finally {
            if (release) {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    /**
     * Whether {@code uri} is the callback WebSocket path, compared undecoded as {@code DashboardWebSocketHandler}
     * compares its own: an encoded form such as {@code /%5Fmockserver_callback_websocket} is not the control plane
     * to the router or the log redaction, so it must not be upgraded here either.
     */
    static boolean isCallbackUpgradeUri(String uri) {
        int pathLength = UPGRADE_CHANNEL_FOR_CALLBACK_WEB_SOCKET_URI.length();
        if (uri == null || uri.length() < pathLength || !uri.regionMatches(0, UPGRADE_CHANNEL_FOR_CALLBACK_WEB_SOCKET_URI, 0, pathLength)) {
            return false;
        }
        if (uri.length() == pathLength) {
            return true;
        }
        char next = uri.charAt(pathLength);
        return next == '?' || next == '#';
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
        // a mid-pipeline handler that swallows channelReadComplete starves Netty's HTTP/2
        // flow-control flush (Http2ConnectionHandler.channelReadComplete -> writePendingBytes),
        // stalling any h2 response larger than the peer's initial window - so propagate the event
        ctx.fireChannelReadComplete();
    }

    private void upgradeChannel(final ChannelHandlerContext ctx, FullHttpRequest httpRequest, ControlPlaneWebSocketUpgradeGate.Admission admission) {
        final WebSocketServerHandshaker handshaker = new WebSocketServerHandshakerFactory(
            (isSslEnabledUpstream(ctx.channel()) ? "wss" : "ws") + "://" + httpRequest.headers().get(HOST) + UPGRADE_CHANNEL_FOR_CALLBACK_WEB_SOCKET_URI,
            null,
            true,
            Integer.MAX_VALUE
        ).newHandshaker(httpRequest);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
            return;
        }
        final String clientId = httpRequest.headers().contains(CLIENT_REGISTRATION_ID_HEADER) ? httpRequest.headers().get(CLIENT_REGISTRATION_ID_HEADER) : UUIDService.getUUID();
        if (LocalCallbackRegistry.responseClientExists(clientId)
            || LocalCallbackRegistry.forwardClientExists(clientId)) {
            // found locally to indicate to client
            HttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.RESET_CONTENT, ctx.channel().alloc().buffer(0));
            HttpUtil.setContentLength(res, 0);
            ctx.channel().writeAndFlush(res, ctx.channel().newPromise());
            return;
        }
        ctx.channel().closeFuture().addListener(future -> webSocketClientRegistry.releaseTakeover(clientId, ctx.channel()));
        claimClientId(ctx, handshaker, httpRequest, clientId, admission);
    }

    /**
     * Upgrades the connection once it may take the client id: at once when the id is free, otherwise
     * after {@link CallbackClientIdClaim} decides. Only one connection at a time may be taking an id
     * over, and the registry is read again once that reservation is held, so a connection that
     * registered meanwhile is decided against rather than silently displaced.
     */
    private void claimClientId(ChannelHandlerContext ctx, WebSocketServerHandshaker handshaker, FullHttpRequest httpRequest, String clientId, ControlPlaneWebSocketUpgradeGate.Admission admission) {
        Channel registered = webSocketClientRegistry.registeredChannel(clientId);
        if (isFree(registered, ctx.channel())) {
            takeOver(ctx, handshaker, httpRequest, clientId, admission, registered, null);
            return;
        }
        httpRequest.retain();
        CallbackClientIdClaim.decide(registered, admission, ctx.executor(), decision -> {
            try {
                if (!ctx.channel().isActive()) {
                    return;
                }
                if (decision.allowed()) {
                    takeOver(ctx, handshaker, httpRequest, clientId, admission, registered, decision.closeRegistered() ? registered : null);
                } else if (decision == CallbackClientIdClaim.Decision.REFUSE_OTHER_PRINCIPAL) {
                    refuse(ctx, clientId, HttpResponseStatus.FORBIDDEN, "registered to an open connection, authenticated as another caller, that answered a ping");
                } else {
                    refuse(ctx, clientId, HttpResponseStatus.CONFLICT, "registered to an open connection that answered a ping");
                }
            } finally {
                httpRequest.release();
            }
        });
    }

    private static boolean isFree(Channel registered, Channel claimant) {
        return registered == null || registered == claimant || !registered.isActive();
    }

    private void takeOver(ChannelHandlerContext ctx, WebSocketServerHandshaker handshaker, FullHttpRequest httpRequest, String clientId, ControlPlaneWebSocketUpgradeGate.Admission admission, Channel decidedAgainst, Channel replaced) {
        if (!webSocketClientRegistry.reserveTakeover(clientId, ctx.channel())) {
            refuse(ctx, clientId, HttpResponseStatus.CONFLICT, "being taken over by another connection");
            return;
        }
        Channel registered = webSocketClientRegistry.registeredChannel(clientId);
        if (registered != decidedAgainst && !isFree(registered, ctx.channel())) {
            webSocketClientRegistry.releaseTakeover(clientId, ctx.channel());
            claimClientId(ctx, handshaker, httpRequest, clientId, admission);
            return;
        }
        handshake(ctx, handshaker, httpRequest, clientId, admission, replaced);
    }

    private void refuse(ChannelHandlerContext ctx, String clientId, HttpResponseStatus status, String reason) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("callback web socket upgrade rejected with status {} - client id {} is {}")
                    .setArguments(status.code(), escapeControlCharactersForLog(clientId), reason)
            );
        }
        ControlPlaneWebSocketUpgradeGate.reject(ctx, status);
    }

    private void handshake(ChannelHandlerContext ctx, WebSocketServerHandshaker handshaker, FullHttpRequest httpRequest, String clientId, ControlPlaneWebSocketUpgradeGate.Admission admission, Channel replaced) {
        ctx.channel().attr(HANDSHAKER).set(handshaker);
        ctx.channel().attr(CHANNEL_UPGRADED_FOR_CALLBACK_WEB_SOCKET).set(true);
        ctx.channel().attr(CallbackClientIdClaim.PRINCIPAL_IDENTITY).set(admission.authenticated() ? admission.principalIdentity() : null);
        final boolean acceptsBreakpointReleased = acceptsBreakpointReleased(httpRequest);
        handshaker
            .handshake(
                ctx.channel(),
                httpRequest,
                new DefaultHttpHeaders().add(CLIENT_REGISTRATION_ID_HEADER, clientId),
                ctx.channel().newPromise()
            )
            .addListener((ChannelFutureListener) future -> {
                ctx.pipeline().remove(DashboardWebSocketHandler.class);
                ctx.pipeline().remove(MockServerHttpServerCodec.class);
                ctx.pipeline().remove(HttpRequestHandler.class);
                if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.TRACE)
                            .setMessageFormat("registering client " + clientId)
                    );
                }
                webSocketClientRegistry.registerClient(clientId, ctx, acceptsBreakpointReleased);
                webSocketClientRegistry.releaseTakeover(clientId, ctx.channel());
                if (replaced != null) {
                    replaced.close();
                }
                final Channel registeredChannel = future.channel();
                registeredChannel.closeFuture().addListener((ChannelFutureListener) closeFuture -> {
                    if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.TRACE)
                                .setMessageFormat("unregistering callback for client " + clientId)
                        );
                    }
                    webSocketClientRegistry.unregisterClient(clientId, registeredChannel);
                });
            });
    }

    /**
     * Whether the client asked to be sent {@code BreakpointReleasedDTO} notices, with an
     * {@code X-MockServer-Capabilities: breakpointReleased} header (language clients) or a
     * {@code ?capabilities=breakpointReleased} query parameter (browsers, which cannot set headers).
     * Clients that do not ask (older clients, which log an unknown message type as an error) are
     * never sent them.
     */
    static boolean acceptsBreakpointReleased(FullHttpRequest httpRequest) {
        List<String> capabilities = new java.util.ArrayList<>(httpRequest.headers().getAll(CAPABILITIES_HEADER));
        List<String> fromQuery = new QueryStringDecoder(httpRequest.uri()).parameters().get(CAPABILITIES_PARAMETER);
        if (fromQuery != null) {
            capabilities.addAll(fromQuery);
        }
        return capabilities.stream()
            .flatMap(value -> Arrays.stream(value.split(",")))
            .anyMatch(capability -> BREAKPOINT_RELEASED_CAPABILITY.equals(capability.trim()));
    }

    private void handleWebSocketFrame(final ChannelHandlerContext ctx, WebSocketFrame frame) {
        if (frame instanceof CloseWebSocketFrame) {
            final WebSocketServerHandshaker handshaker = ctx.channel().attr(HANDSHAKER).get();
            if (handshaker != null) {
                handshaker.close(ctx.channel(), (CloseWebSocketFrame) frame.retain());
            } else {
                ctx.close();
            }
        } else if (frame instanceof TextWebSocketFrame) {
            webSocketClientRegistry.receivedTextWebSocketFrame(((TextWebSocketFrame) frame));
        } else if (frame instanceof PingWebSocketFrame) {
            ctx.write(new PongWebSocketFrame(frame.content().retain()));
        } else if (frame instanceof PongWebSocketFrame) {
            CallbackClientIdClaim.pongReceived(ctx.channel());
        } else {
            throw new UnsupportedOperationException(frame.getClass().getName() + " frame types not supported");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (HttpChunkLineLimiter.isRejectedRequestCutShort(ctx.channel(), cause)) {
            // already logged as the rejection that closed the connection
        } else if (Http2RequestHeaderLimit.isRefusedRequestCutShort(ctx.channel(), cause)) {
            // already logged as the refusal that reset the stream
        } else if (Http2StreamFaults.isRequestCutShort(mockServerLogger, ctx.channel(), cause)) {
            // an HTTP/2 request cut short by its stream's error, its client's cancel or its connection's end: logged as that
        } else if (Http1RequestCutShort.isRequestCutShort(mockServerLogger, ctx, cause)) {
            // an HTTP/1.1 request cut short by its connection's end: logged as that
        } else if (directMemoryLimitReached(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat(DIRECT_MEMORY_LIMIT_REACHED + ctx.channel() + " - " + cause.getMessage())
            );
            ServerClosedChannels.markClosed(ctx.channel());
        } else if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("web socket server caught exception")
                    .setThrowable(cause)
            );
            ServerClosedChannels.markClosed(ctx.channel());
        } else if (isSslOrDecoderFault(cause)) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("web socket server caught SSL or decoder fault" + sniDescription(ctx.channel()))
                        .setThrowable(boundedFault(cause))
                );
            }
            ServerClosedChannels.markClosed(ctx.channel());
        }
        ctx.close();
    }

}

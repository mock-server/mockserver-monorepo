package org.mockserver.netty.websocketregistry;

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
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.Http2StreamIds;
import org.mockserver.mock.HttpState;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.uuid.UUIDService;
import org.slf4j.event.Level;

import static com.google.common.net.HttpHeaders.HOST;
import static org.mockserver.closurecallback.websocketclient.WebSocketClient.CLIENT_REGISTRATION_ID_HEADER;
import static org.mockserver.exception.ExceptionHandling.connectionClosedException;
import static org.mockserver.exception.ExceptionHandling.isSslOrDecoderFault;
import static org.mockserver.exception.ExceptionHandling.sniDescription;
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
    private final MockServerLogger mockServerLogger;
    private final WebSocketClientRegistry webSocketClientRegistry;

    public CallbackWebSocketServerHandler(HttpState httpStateHandler) {
        webSocketClientRegistry = httpStateHandler.getWebSocketClientRegistry();
        mockServerLogger = httpStateHandler.getMockServerLogger();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        boolean release = true;
        try {
            if (msg instanceof FullHttpRequest && ((FullHttpRequest) msg).uri().equals(UPGRADE_CHANNEL_FOR_CALLBACK_WEB_SOCKET_URI)) {
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
                    upgradeChannel(ctx, (FullHttpRequest) msg);
                    ctx.channel().attr(CHANNEL_UPGRADED_FOR_CALLBACK_WEB_SOCKET).set(true);
                }
            } else if (ctx.channel().attr(CHANNEL_UPGRADED_FOR_CALLBACK_WEB_SOCKET).get() != null &&
                ctx.channel().attr(CHANNEL_UPGRADED_FOR_CALLBACK_WEB_SOCKET).get() &&
                msg instanceof WebSocketFrame) {
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

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }

    private void upgradeChannel(final ChannelHandlerContext ctx, FullHttpRequest httpRequest) {
        final WebSocketServerHandshaker handshaker = new WebSocketServerHandshakerFactory(
            (isSslEnabledUpstream(ctx.channel()) ? "wss" : "ws") + "://" + httpRequest.headers().get(HOST) + UPGRADE_CHANNEL_FOR_CALLBACK_WEB_SOCKET_URI,
            null,
            true,
            Integer.MAX_VALUE
        ).newHandshaker(httpRequest);
        if (handshaker == null) {
            WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
        } else {
            ctx.channel().attr(HANDSHAKER).set(handshaker);
            final String clientId = httpRequest.headers().contains(CLIENT_REGISTRATION_ID_HEADER) ? httpRequest.headers().get(CLIENT_REGISTRATION_ID_HEADER) : UUIDService.getUUID();
            if (LocalCallbackRegistry.responseClientExists(clientId)
                || LocalCallbackRegistry.forwardClientExists(clientId)) {
                // found locally to indicate to client
                HttpResponse res = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.RESET_CONTENT, ctx.channel().alloc().buffer(0));
                HttpUtil.setContentLength(res, 0);
                ctx.channel().writeAndFlush(res, ctx.channel().newPromise());
            } else {
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
                        webSocketClientRegistry.registerClient(clientId, ctx);
                        future.channel().closeFuture().addListener((ChannelFutureListener) closeFuture -> {
                            if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(Level.TRACE)
                                        .setMessageFormat("unregistering callback for client " + clientId)
                                );
                            }
                            webSocketClientRegistry.unregisterClient(clientId);
                        });
                    });
            }
        }
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
        } else {
            throw new UnsupportedOperationException(frame.getClass().getName() + " frame types not supported");
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (connectionClosedException(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("web socket server caught exception")
                    .setThrowable(cause)
            );
        } else if (isSslOrDecoderFault(cause)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("web socket server caught SSL or decoder fault" + sniDescription(ctx.channel()))
                    .setThrowable(cause)
            );
        }
        ctx.close();
    }

}

package org.mockserver.netty.unification;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http2.Http2ConnectionHandler;

/**
 * Emits an HTTP/2 {@code GOAWAY} frame on the connection carrying {@code ctx} so the client is told
 * to stop opening new streams and drain — the graceful "this connection is going away" signal a
 * server sends before a shutdown / preemption. In-flight streams are allowed to complete; GOAWAY
 * does not reset them.
 *
 * <p>GOAWAY is a <em>connection-level</em> frame, so it is always written on the connection channel's
 * pipeline where the {@link Http2ConnectionHandler} lives, regardless of which channel {@code emit}
 * was called from:
 * <ul>
 *   <li>On the <b>connection-level (default) HTTP/2 pipeline</b> the handler is on {@code ctx}'s own
 *       pipeline (resolved with the same lookup pattern as
 *       {@code HttpErrorActionHandler.resetHttp2Stream} —
 *       {@code ctx.pipeline().context(Http2ConnectionHandler.class)}), so it works whether called
 *       from a child handler or the connection handler's own context.</li>
 *   <li>On the <b>multiplex pipeline</b> (per-stream child channels, used for gRPC bidi streaming)
 *       the request handlers run on a stream child channel whose pipeline has no
 *       {@code Http2ConnectionHandler} — the {@code Http2FrameCodec} (which
 *       {@code extends Http2ConnectionHandler}) lives on the <b>parent</b> connection channel. When
 *       the local pipeline has no connection handler, {@code emit} walks up to
 *       {@code ctx.channel().parent().pipeline()} and writes the GOAWAY there.</li>
 * </ul>
 *
 * <p>HTTP/1.1 has no GOAWAY concept and no {@code Http2ConnectionHandler} on any pipeline, so
 * {@code emit} returns {@code false} and callers degrade to {@code Connection: close} + 503 instead.
 */
public final class Http2GoAwayEmitter {

    private Http2GoAwayEmitter() {
    }

    /**
     * Emit a GOAWAY on the connection carrying {@code ctx}, whether {@code ctx} is on a
     * connection-level HTTP/2 pipeline or a multiplex stream child channel.
     *
     * @param ctx          a context on the HTTP/2 channel's pipeline — either the connection channel
     *                     or a per-stream child channel
     * @param lastStreamId the {@code lastStreamId} to advertise; when negative the connection
     *                     handler's current last-stream-id is used (passing the max stream id so the
     *                     handler clamps to the connection's actual last-processed stream)
     * @param errorCode    the HTTP/2 error code (0 = NO_ERROR, the graceful-shutdown code)
     * @return {@code true} if a GOAWAY was written (an HTTP/2 connection handler was found on this
     *     pipeline or the parent connection channel's pipeline), {@code false} otherwise (e.g.
     *     HTTP/1.1, or no HTTP/2 connection handler anywhere)
     */
    public static boolean emit(ChannelHandlerContext ctx, long lastStreamId, long errorCode) {
        if (ctx == null) {
            return false;
        }
        ChannelHandlerContext connCtx = ctx.pipeline().context(Http2ConnectionHandler.class);
        if (connCtx == null) {
            // On an HTTP/2 multiplex stream child channel the connection handler lives on the PARENT
            // connection channel's pipeline, not this one. GOAWAY is a connection-level frame, so
            // walk up to the parent and locate the handler there. parent() is null on a
            // non-child channel (the default connection-level pipeline, or HTTP/1.1), so this
            // degrades to a clean false rather than throwing when there is genuinely no HTTP/2
            // connection handler to emit through.
            Channel parent = ctx.channel().parent();
            if (parent != null) {
                connCtx = parent.pipeline().context(Http2ConnectionHandler.class);
            }
        }
        if (connCtx == null) {
            return false;
        }
        Http2ConnectionHandler connectionHandler = (Http2ConnectionHandler) connCtx.handler();
        // A negative lastStreamId means "use the connection's current last stream"; Integer.MAX_VALUE
        // is clamped down to the real last-created stream id by the connection handler, so it is the
        // safe "all streams so far" sentinel.
        int effectiveLastStreamId = lastStreamId < 0 ? Integer.MAX_VALUE : (int) Math.min(lastStreamId, Integer.MAX_VALUE);
        long effectiveErrorCode = errorCode < 0 ? 0L : errorCode;
        connectionHandler.goAway(connCtx, effectiveLastStreamId, effectiveErrorCode, Unpooled.EMPTY_BUFFER, connCtx.newPromise());
        connCtx.flush();
        return true;
    }
}

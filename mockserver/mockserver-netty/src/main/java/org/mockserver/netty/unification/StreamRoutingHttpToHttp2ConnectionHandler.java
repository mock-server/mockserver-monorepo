package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http2.Http2ConnectionDecoder;
import io.netty.handler.codec.http2.Http2ConnectionEncoder;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import io.netty.util.ReferenceCountUtil;
import org.mockserver.codec.StreamAddressedHttpContent;

/**
 * An {@link HttpToHttp2ConnectionHandler} that routes {@link StreamAddressedHttpContent} data frames
 * onto the stream id the frame carries, instead of onto the codec's single mutable
 * {@code currentStreamId}.
 * <p>
 * The stock handler only updates {@code currentStreamId} when an {@code HttpMessage} <em>head</em>
 * is written, then reuses it for every subsequent bare content frame. With a single stream in flight
 * that is fine; with two concurrent streams it mis-routes a later chunk of one stream onto the other
 * stream (which may already have ended), producing {@code IllegalArgumentException: Stream no longer
 * exists} and leaving the first stream's client hanging forever — GitHub issue #2667.
 * <p>
 * The write site ({@code HttpSseResponseActionHandler}) already holds the request's stream id and
 * wraps each per-event chunk and the terminal frame in a {@link StreamAddressedHttpContent}. This
 * handler intercepts that wrapper in {@link #write} and writes the DATA frame directly onto the
 * carried stream id via {@code encoder().writeData(...)}, bypassing {@code currentStreamId}
 * entirely. Every other message — the response head (an {@code HttpMessage}, still stamped with
 * {@code x-http2-stream-id} and handled by the superclass), settings, pings, resets — is delegated
 * unchanged to {@link HttpToHttp2ConnectionHandler#write}, so HTTP/1.1 behaviour and the head-writing
 * path are untouched.
 * <p>
 * As a defensive backstop, any streaming DATA write that fails (for any reason) resets the
 * originating stream, so a client is never left hanging even if a write fails for an unrelated
 * cause. {@code resetStream} is idempotent (it no-ops once a reset has been sent) and safe on an
 * already-closed or unknown stream.
 */
public class StreamRoutingHttpToHttp2ConnectionHandler extends HttpToHttp2ConnectionHandler {

    protected StreamRoutingHttpToHttp2ConnectionHandler(Http2ConnectionDecoder decoder, Http2ConnectionEncoder encoder,
                                                        Http2Settings initialSettings, boolean validateHeaders,
                                                        boolean decoupleCloseAndGoAway, boolean flushPreface) {
        super(decoder, encoder, initialSettings, validateHeaders, decoupleCloseAndGoAway, flushPreface, null);
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
        if (msg instanceof StreamAddressedHttpContent) {
            writeStreamAddressedData(ctx, (StreamAddressedHttpContent) msg, promise);
        } else {
            super.write(ctx, msg, promise);
        }
    }

    private void writeStreamAddressedData(ChannelHandlerContext ctx, StreamAddressedHttpContent addressed, ChannelPromise promise) {
        final int streamId = addressed.streamId();
        final ByteBuf content = addressed.content();
        final boolean endStream = addressed.endStream();

        // Backstop: if this streaming write fails for any reason, reset the originating stream so the
        // client is never left hanging. On the happy path this listener is a no-op.
        promise.addListener(future -> {
            if (!future.isSuccess()) {
                resetOriginatingStream(ctx, streamId);
            }
        });

        try {
            // writeData takes ownership of the buffer (it releases it), exactly as the stock
            // HttpToHttp2ConnectionHandler content branch does after setting release = false.
            encoder().writeData(ctx, streamId, content, 0, endStream, promise);
        } catch (Throwable t) {
            // writeData normally fails the promise rather than throwing; on a synchronous throw the
            // buffer was not consumed, so release it and surface the failure.
            ReferenceCountUtil.safeRelease(content);
            if (!promise.isDone()) {
                promise.setFailure(t);
            }
        }
    }

    private void resetOriginatingStream(ChannelHandlerContext ctx, int streamId) {
        try {
            resetStream(ctx, streamId, Http2Error.INTERNAL_ERROR.code(), ctx.newPromise());
            ctx.flush();
        } catch (Throwable ignore) {
            // best-effort: never let the reset backstop itself throw
        }
    }
}

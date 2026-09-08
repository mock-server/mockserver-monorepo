package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;

/**
 * An {@link HttpContent} chunk that also carries the HTTP/2 stream id it must be written on.
 * <p>
 * MockServer's non-gRPC HTTP/2 server pipeline multiplexes every stream over a single shared
 * {@code HttpToHttp2ConnectionHandler}. That Netty handler picks the outbound target stream from a
 * single mutable {@code currentStreamId} field which it only updates when an {@code HttpMessage}
 * <em>head</em> is written; bare {@code HttpContent}/{@code LastHttpContent} frames are not
 * {@code HttpMessage}s and carry no {@code headers()}, so the codec cannot read a per-chunk stream
 * id and instead reuses whichever stream last wrote a head. When two streams interleave (a slow SSE
 * stream A whose second chunk is emitted after a faster stream B has written its head), A's later
 * chunks are mis-routed onto B's — already-closed — stream, so A hangs forever
 * (GitHub issue #2667).
 * <p>
 * A header cannot fix this because a chunk is not an {@code HttpMessage}. The stream id must travel
 * out-of-band with the chunk itself, and the data write must be addressed explicitly. This wrapper
 * is that out-of-band vehicle: the write site (which still holds the request's stream id) wraps each
 * per-event chunk and the terminal frame in one of these, and
 * {@code StreamRoutingHttpToHttp2ConnectionHandler} recognises it and calls
 * {@code encoder().writeData(ctx, streamId, ...)} directly, bypassing {@code currentStreamId}.
 * <p>
 * It is deliberately a plain {@link HttpContent} (not an {@code HttpMessage} and not a
 * {@code LastHttpContent}) so it passes untouched through the intervening outbound handlers
 * (TraceContextHandler, MockServerHttpServerCodec's response encoder, the WebSocket handlers,
 * Http2StreamIdAuditHandler) — none of which inspect {@code HttpContent} — and reaches the routing
 * handler intact. A channel attribute is unusable here because the channel is shared by every
 * concurrent stream; the id has to ride with the individual frame.
 */
public final class StreamAddressedHttpContent extends DefaultHttpContent {

    private final int streamId;
    private final boolean endStream;

    /**
     * @param content   the chunk payload (may be an empty buffer for a terminal END_STREAM frame)
     * @param streamId  the HTTP/2 stream the chunk must be written on (the request's stream id)
     * @param endStream whether this frame ends the stream (true only for the terminal frame)
     */
    public StreamAddressedHttpContent(ByteBuf content, int streamId, boolean endStream) {
        super(content);
        this.streamId = streamId;
        this.endStream = endStream;
    }

    public int streamId() {
        return streamId;
    }

    public boolean endStream() {
        return endStream;
    }

    @Override
    public HttpContent replace(ByteBuf content) {
        // preserve the routing metadata across copy()/duplicate()/retainedDuplicate()
        return new StreamAddressedHttpContent(content, streamId, endStream);
    }
}

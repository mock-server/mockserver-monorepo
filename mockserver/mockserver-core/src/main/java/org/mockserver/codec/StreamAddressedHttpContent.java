package org.mockserver.codec;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.HttpContent;

/**
 * An {@link HttpContent} chunk that carries two pieces of out-of-band state for a streaming HTTP/2
 * response: whether the frame ends the stream, and (historically) the stream id it belongs to.
 * <p>
 * MockServer's HTTP/2 server pipeline gives every stream its own
 * {@code Http2MultiplexHandler} child channel, so a streaming response is written on the channel that
 * <em>is</em> that stream and no per-chunk stream id is needed to route it — the {@code streamId} field
 * is therefore redundant on the current path and retained only for callers that still supply it.
 * <p>
 * The {@code endStream} flag is what still matters. A bare {@code HttpContent}/{@code LastHttpContent}
 * frame reaches {@link io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec}, whose
 * bare-{@code HttpContent} branch hard-codes {@code endStream=false}; the terminal frame's
 * end-of-stream would be silently dropped and an SSE/NDJSON/AWS-event-stream client would receive every
 * event and then hang until it times out (GitHub issue #2667 / #2669 follow-up). This wrapper carries
 * the flag out-of-band so {@code StreamAddressedContentHandler} on the child channel can translate it
 * into the {@code HttpObject} subtype the codec maps to an END_STREAM DATA frame.
 * <p>
 * It is deliberately a plain {@link HttpContent} (not an {@code HttpMessage} and not a
 * {@code LastHttpContent}) so it passes untouched through the intervening outbound handlers
 * (TraceContextHandler, MockServerHttpServerCodec's response encoder, the WebSocket handlers) — none of
 * which inspect {@code HttpContent} — and reaches {@code StreamAddressedContentHandler} intact.
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

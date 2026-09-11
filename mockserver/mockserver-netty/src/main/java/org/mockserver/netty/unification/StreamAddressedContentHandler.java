package org.mockserver.netty.unification;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import org.mockserver.codec.StreamAddressedHttpContent;

/**
 * Translates a {@link StreamAddressedHttpContent} into the child-channel equivalent when a streaming
 * response is written on an {@link io.netty.handler.codec.http2.Http2MultiplexHandler} child channel.
 * <p>
 * The {@link StreamAddressedHttpContent} wrapper carries two pieces of out-of-band state — a stream id
 * and an {@code endStream()} flag. On the multiplex path every stream has its own
 * {@link io.netty.handler.codec.http2.Http2StreamChannel} — the channel <em>is</em> the stream — so the
 * {@code streamId()} field is redundant here. Only the {@code endStream()} flag still carries
 * information the child pipeline cannot otherwise recover.
 * <p>
 * <strong>Why this handler is required:</strong> without it the wrapper reaches
 * {@link io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec#encode}, whose only
 * bare-{@code HttpContent} branch emits {@code new DefaultHttp2DataFrame(content.retain(), false)} —
 * it hard-codes {@code endStream=false}. The terminal frame's {@code endStream=true} is therefore
 * silently discarded, the stream is never closed, and an SSE/NDJSON/AWS-event-stream client receives
 * every event and then hangs until it times out. Nothing is logged (GitHub issue #2669 follow-up).
 * <p>
 * The translation preserves the flag by picking the {@code HttpObject} subtype the codec maps
 * correctly:
 * <ul>
 *   <li>{@code endStream() == true}  &rarr; {@link DefaultLastHttpContent}. The codec's
 *       {@code encodeLastContent} sets {@code needFiller = true} for a non-{@code FullHttpMessage}
 *       with empty trailers, so it emits {@code DefaultHttp2DataFrame(content, endStream=true)} even
 *       for an empty terminal buffer.</li>
 *   <li>{@code endStream() == false} &rarr; plain {@link DefaultHttpContent}, which the codec maps
 *       to a non-terminal DATA frame exactly as the non-terminal chunks already work today.</li>
 * </ul>
 * <p>
 * <strong>Reference counting:</strong> the replacement content wraps the <em>same</em>
 * {@link io.netty.buffer.ByteBuf}, so buffer ownership transfers with it. The wrapper holds no
 * resource of its own beyond that buffer, so this handler neither retains nor releases anything —
 * it just swaps the object that carries the buffer downstream.
 * <p>
 * The handler is {@link ChannelHandler.Sharable @Sharable} and stateless, so a single {@link #INSTANCE}
 * can be installed on every child pipeline, matching how {@link ConnectionScopeHandler#INSTANCE} is
 * used.
 */
@ChannelHandler.Sharable
public class StreamAddressedContentHandler extends ChannelOutboundHandlerAdapter {

    public static final StreamAddressedContentHandler INSTANCE = new StreamAddressedContentHandler();

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        if (msg instanceof StreamAddressedHttpContent) {
            StreamAddressedHttpContent addressed = (StreamAddressedHttpContent) msg;
            // Transfer the buffer to the child-channel equivalent WITHOUT retain/release: the new
            // content object becomes the sole owner of the same ByteBuf that the wrapper carried.
            Object translated = addressed.endStream()
                ? new DefaultLastHttpContent(addressed.content())
                : new DefaultHttpContent(addressed.content());
            ctx.write(translated, promise);
        } else {
            super.write(ctx, msg, promise);
        }
    }
}

package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec;
import io.netty.util.ReferenceCountUtil;
import org.junit.Test;
import org.mockserver.codec.StreamAddressedHttpContent;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;

/**
 * Unit coverage for {@link StreamAddressedContentHandler} — the outbound handler that translates a
 * {@link StreamAddressedHttpContent} written on an {@code Http2MultiplexHandler} child pipeline into
 * the {@code HttpObject} subtype that {@link Http2StreamFrameToHttpObjectCodec} maps to the correct
 * DATA frame, so that the terminal frame's {@code endStream=true} is not silently discarded (the
 * bug: SSE/NDJSON/AWS-event-stream and therefore all LLM streaming responses never close the stream
 * and the client hangs).
 * <p>
 * The assertion that actually pins the bug is {@link #shouldEmitEndStreamDataFrameThroughRealCodec}:
 * the terminal frame, run through a real {@link Http2StreamFrameToHttpObjectCodec}, must produce an
 * {@link Http2DataFrame} with {@code isEndStream() == true}. Without the handler (or with a
 * pass-through stub) the codec's bare-{@code HttpContent} branch hard-codes {@code endStream=false}
 * and that assertion goes red.
 */
public class StreamAddressedContentHandlerTest {

    private static final int STREAM_ID = 3;

    @Test
    public void shouldTranslateTerminalFrameToLastHttpContentSharingTheSameBuffer() {
        // given - an empty terminal END_STREAM frame, exactly as HttpSseResponseActionHandler emits it
        EmbeddedChannel channel = new EmbeddedChannel(StreamAddressedContentHandler.INSTANCE);
        ByteBuf buffer = Unpooled.EMPTY_BUFFER; // the real terminal buffer; unpooled empty is refCnt-stable
        StreamAddressedHttpContent terminal = new StreamAddressedHttpContent(buffer, STREAM_ID, true);

        // when
        assertThat(channel.writeOutbound(terminal), is(true));

        // then - it is now a LastHttpContent (which the codec maps to endStream=true) wrapping the
        // SAME buffer, and it is NOT still a StreamAddressedHttpContent
        Object out = channel.readOutbound();
        assertThat(out, instanceOf(LastHttpContent.class));
        assertThat(out, not(instanceOf(StreamAddressedHttpContent.class)));
        assertThat(((HttpContent) out).content(), sameInstance(buffer));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldTranslateNonTerminalFrameToPlainHttpContentNotLastHttpContent() {
        // given - a non-terminal per-event chunk with real payload
        EmbeddedChannel channel = new EmbeddedChannel(StreamAddressedContentHandler.INSTANCE);
        ByteBuf buffer = Unpooled.copiedBuffer("event: first\n", StandardCharsets.UTF_8);
        assertThat("precondition", buffer.refCnt(), is(1));
        StreamAddressedHttpContent chunk = new StreamAddressedHttpContent(buffer, STREAM_ID, false);

        // when
        assertThat(channel.writeOutbound(chunk), is(true));

        // then - a plain DefaultHttpContent (NOT a LastHttpContent, so the codec emits a non-terminal
        // DATA frame) wrapping the same buffer, with the reference count exactly preserved (the
        // translation neither retained nor released, it just swapped the carrier object)
        Object out = channel.readOutbound();
        assertThat(out, instanceOf(DefaultHttpContent.class));
        assertThat(out, not(instanceOf(LastHttpContent.class)));
        assertThat(((HttpContent) out).content(), sameInstance(buffer));
        assertThat("buffer ownership must transfer with no retain/release", buffer.refCnt(), is(1));

        ReferenceCountUtil.release(out);
        assertThat("released exactly once leaves it freed", buffer.refCnt(), is(0));
        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldPassNonStreamAddressedOutboundMessagesThroughByIdentity() {
        // given - an ordinary response head (an HttpMessage, not a StreamAddressedHttpContent)
        EmbeddedChannel channel = new EmbeddedChannel(StreamAddressedContentHandler.INSTANCE);
        HttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        // when
        assertThat(channel.writeOutbound(head), is(true));

        // then - it passed through untouched, by identity
        Object out = channel.readOutbound();
        assertThat(out, sameInstance(head));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldPassPlainHttpContentThroughUntouched() {
        // given - a plain DefaultHttpContent (NOT stream-addressed) must not be intercepted
        EmbeddedChannel channel = new EmbeddedChannel(StreamAddressedContentHandler.INSTANCE);
        ByteBuf buffer = Unpooled.copiedBuffer("data\n", StandardCharsets.UTF_8);
        DefaultHttpContent plain = new DefaultHttpContent(buffer);

        // when
        assertThat(channel.writeOutbound(plain), is(true));

        // then - identical object out
        Object out = channel.readOutbound();
        assertThat(out, sameInstance(plain));
        assertThat(buffer.refCnt(), is(1));

        channel.finishAndReleaseAll();
    }

    /**
     * The load-bearing assertion. Compose the handler with a REAL
     * {@link Http2StreamFrameToHttpObjectCodec} (server mode) in one channel — the exact outbound
     * arrangement of the multiplex child pipeline — and assert that the terminal frame emerges as an
     * {@link Http2DataFrame} carrying {@code endStream=true}. This is what does NOT happen without the
     * handler.
     */
    @Test
    public void shouldEmitEndStreamDataFrameThroughRealCodec() {
        EmbeddedChannel channel = new EmbeddedChannel(
            new Http2StreamFrameToHttpObjectCodec(true),
            StreamAddressedContentHandler.INSTANCE
        );

        // a non-terminal chunk first -> non-terminal DATA frame
        ByteBuf chunkBuffer = Unpooled.copiedBuffer("data: one\n\n", StandardCharsets.UTF_8);
        channel.writeOutbound(new StreamAddressedHttpContent(chunkBuffer, STREAM_ID, false));
        Object nonTerminalOut = channel.readOutbound();
        assertThat(nonTerminalOut, instanceOf(Http2DataFrame.class));
        assertThat("non-terminal chunk must NOT end the stream",
            ((Http2DataFrame) nonTerminalOut).isEndStream(), is(false));
        ReferenceCountUtil.release(nonTerminalOut);

        // the terminal (empty) frame -> DATA frame with endStream=true
        channel.writeOutbound(new StreamAddressedHttpContent(Unpooled.EMPTY_BUFFER, STREAM_ID, true));
        Object terminalOut = channel.readOutbound();
        assertThat("the terminal frame must reach the wire as a DATA frame", terminalOut, instanceOf(Http2DataFrame.class));
        assertThat("the terminal frame MUST carry endStream=true (the whole point of the fix)",
            ((Http2DataFrame) terminalOut).isEndStream(), is(true));
        ReferenceCountUtil.release(terminalOut);

        // content-only writes never produce a HEADERS frame, and both DATA frames were drained above
        assertThat("no further outbound frames expected", channel.readOutbound(), is((Object) null));

        channel.finishAndReleaseAll();
    }
}

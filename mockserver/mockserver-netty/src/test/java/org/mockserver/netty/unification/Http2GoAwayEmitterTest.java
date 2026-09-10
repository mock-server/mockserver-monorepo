package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2ConnectionHandler;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * Unit coverage for {@link Http2GoAwayEmitter} — the lazy GOAWAY signal the preemption cordon and the
 * L3 response-path fault both rely on. Proves a connection-level HTTP/2 pipeline actually writes a
 * GOAWAY frame on the wire, and that an HTTP/1.1 (non-HTTP/2) pipeline is a no-op returning
 * {@code false} so the caller can fall back to the 503 + Connection: close path.
 */
public class Http2GoAwayEmitterTest {

    /** HTTP/2 frame type for GOAWAY (RFC 7540 §6.8). */
    private static final int GOAWAY_FRAME_TYPE = 0x07;
    /** HTTP/2 frame header is 9 bytes: 3-byte length, 1-byte type, 1-byte flags, 4-byte stream id. */
    private static final int FRAME_HEADER_LENGTH = 9;

    @Test
    public void shouldEmitGoAwayOnHttp2Pipeline() {
        // given - an embedded channel carrying a connection-level HTTP/2 handler (Http2FrameCodec is an
        // Http2ConnectionHandler, the type Http2GoAwayEmitter resolves on the pipeline)
        Http2FrameCodec frameCodec = Http2FrameCodecBuilder.forServer().build();
        EmbeddedChannel channel = new EmbeddedChannel(frameCodec);
        assertThat(channel.pipeline().context(Http2ConnectionHandler.class), notNullValue());

        // when - a GOAWAY is emitted with the "current last stream" sentinel and NO_ERROR
        boolean emitted = Http2GoAwayEmitter.emit(channel.pipeline().firstContext(), -1L, 0L);
        channel.flushOutbound();

        // then - emit reported success and a GOAWAY frame appears on the outbound wire
        assertThat(emitted, is(true));
        assertThat("a GOAWAY frame (type 0x07) must be written on an HTTP/2 connection",
            outboundContainsGoAwayFrame(channel), is(true));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldBeNoOpAndReturnFalseOnNonHttp2Pipeline() {
        // given - a plain channel with no HTTP/2 connection handler (the HTTP/1.1 case)
        EmbeddedChannel channel = new EmbeddedChannel();

        // when - a GOAWAY is attempted
        boolean emitted = Http2GoAwayEmitter.emit(channel.pipeline().firstContext(), -1L, 0L);

        // then - it is a no-op returning false so the caller degrades to 503 + Connection: close
        assertThat(emitted, is(false));

        channel.finishAndReleaseAll();
    }

    @Test
    public void shouldReturnFalseForNullContext() {
        assertThat(Http2GoAwayEmitter.emit(null, -1L, 0L), is(false));
    }

    @Test
    public void shouldEmitGoAwayViaParentConnectionChannelFromMultiplexStreamChild() {
        // given - a multiplex topology: the connection handler (Http2FrameCodec, an
        // Http2ConnectionHandler) lives on the PARENT connection channel, exactly as
        // PortUnificationHandler.switchToHttp2Multiplex builds it, while the request handlers run on a
        // per-stream CHILD channel whose own pipeline has NO Http2ConnectionHandler.
        final EmbeddedChannel parent = new EmbeddedChannel(Http2FrameCodecBuilder.forServer().build());
        EmbeddedChannel child = new EmbeddedChannel() {
            @Override
            public Channel parent() {
                return parent;
            }
        };
        child.pipeline().addLast(new ChannelInboundHandlerAdapter());
        assertThat("the child pipeline must NOT carry the connection handler",
            child.pipeline().context(Http2ConnectionHandler.class), is((Object) null));

        // when - a GOAWAY is emitted from a context on the child stream channel
        boolean emitted = Http2GoAwayEmitter.emit(child.pipeline().lastContext(), -1L, 0L);
        parent.flushOutbound();
        child.flushOutbound();

        // then - emit walked up to the parent connection channel and wrote the GOAWAY THERE (a
        // connection-level frame belongs on the connection channel, not a stream), and no GOAWAY was
        // written on the child.
        assertThat("emit must succeed from a multiplex stream child channel", emitted, is(true));
        assertThat("the GOAWAY must be written on the PARENT connection channel",
            outboundContainsGoAwayFrame(parent), is(true));
        assertThat("no GOAWAY must be written on the child stream channel",
            outboundContainsGoAwayFrame(child), is(false));

        parent.finishAndReleaseAll();
        child.finishAndReleaseAll();
    }

    @Test
    public void shouldReturnFalseWhenNeitherChildNorParentHasHttp2Handler() {
        // given - a child stream channel whose parent connection channel also has NO
        // Http2ConnectionHandler (e.g. not an HTTP/2 connection at all)
        final EmbeddedChannel parent = new EmbeddedChannel();
        EmbeddedChannel child = new EmbeddedChannel() {
            @Override
            public Channel parent() {
                return parent;
            }
        };
        child.pipeline().addLast(new ChannelInboundHandlerAdapter());

        // when / then - no connection handler anywhere means a clean false, not an exception, so the
        // caller can still degrade to 503 + Connection: close
        assertThat(Http2GoAwayEmitter.emit(child.pipeline().lastContext(), -1L, 0L), is(false));

        parent.finishAndReleaseAll();
        child.finishAndReleaseAll();
    }

    /**
     * Drain every outbound {@link ByteBuf} and walk its HTTP/2 frames, returning {@code true} if any
     * frame is a GOAWAY (type 0x07). The connection handler may also write a SETTINGS frame, so this
     * scans rather than assuming GOAWAY is the only/first frame.
     */
    private static boolean outboundContainsGoAwayFrame(EmbeddedChannel channel) {
        Object outbound;
        while ((outbound = channel.readOutbound()) != null) {
            if (outbound instanceof ByteBuf) {
                ByteBuf buf = ((ByteBuf) outbound).duplicate();
                while (buf.readableBytes() >= FRAME_HEADER_LENGTH) {
                    int length = buf.readUnsignedMedium();
                    int type = buf.readUnsignedByte();
                    buf.skipBytes(1 + 4); // flags + stream id
                    if (type == GOAWAY_FRAME_TYPE) {
                        return true;
                    }
                    if (buf.readableBytes() < length) {
                        break;
                    }
                    buf.skipBytes(length);
                }
            }
        }
        return false;
    }
}

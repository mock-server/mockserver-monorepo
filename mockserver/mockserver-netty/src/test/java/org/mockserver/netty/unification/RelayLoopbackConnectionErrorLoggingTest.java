package org.mockserver.netty.unification;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2FrameWriter;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameAdapter;
import io.netty.handler.codec.http2.Http2FrameListener;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandler;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;

/**
 * The relay's end of an HTTP/2 tunnel's loopback, the client handler {@link Http2RequestHeaderLimit#relayLoopbackHandler}
 * builds, logs a connection error it closes the loopback for once, as a direct connection logs it: Netty's handler
 * answers it with a {@code GOAWAY} and a close and fires nothing down the pipeline.
 */
public class RelayLoopbackConnectionErrorLoggingTest {

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private final MockServerLogger recording = new MockServerLogger(RelayLoopbackConnectionErrorLoggingTest.class) {
        @Override
        public boolean isEnabledForInstance(Level level) {
            return true;
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            logged.add(logEntry);
        }
    };

    @Test
    public void shouldLogAFrameNettyRefusesOnceAsAWarningAndStillSendGoAway() throws Exception {
        EmbeddedChannel loopback = loopback(new Http2FrameAdapter());

        // a DATA frame on stream 0, which must be associated with a stream
        loopback.writeInbound(Unpooled.wrappedBuffer(serverSettings(), Unpooled.wrappedBuffer(new byte[]{0, 0, 0, Http2FrameTypes.DATA, 0, 0, 0, 0, 0})));

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.WARN));
        assertThat(logged.get(0).getMessageFormat(), is("closing HTTP/2 connection from:{}for connection error:{}"));
        assertThat(logged.get(0).getArguments()[1], is(Http2Error.PROTOCOL_ERROR));
        assertThat(logged.get(0).getThrowable(), instanceOf(Http2Exception.class));
        assertThat(sentGoAways(loopback), contains(Http2Error.PROTOCOL_ERROR));
        assertThat(loopback.isOpen(), is(false));
        assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(loopback), is(nullValue()));
        loopback.finishAndReleaseAll();
    }

    @Test
    public void shouldLogAnExceptionAFrameListenerThrowsOnceAsAnErrorWithItsCause() throws Exception {
        IllegalStateException thrown = new IllegalStateException("listener failed");
        EmbeddedChannel loopback = loopback(new Http2FrameAdapter() {
            @Override
            public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
                throw thrown;
            }
        });

        loopback.writeInbound(serverSettings());

        assertThat(logged, hasSize(1));
        assertThat(logged.get(0).getLogLevel(), is(Level.ERROR));
        assertThat(logged.get(0).getMessageFormat(), startsWith("closing HTTP/2 connection "));
        assertThat(logged.get(0).getMessageFormat(), endsWith(" for unexpected exception"));
        assertThat(logged.get(0).getThrowable(), sameInstance(thrown));
        assertThat("Netty's own, once", sentGoAways(loopback), contains(Http2Error.INTERNAL_ERROR));
        assertThat(loopback.isOpen(), is(false));
        assertThat("passed on to the end of the pipeline", reachedTheEndOfThePipeline(loopback), is(nullValue()));
        loopback.finishAndReleaseAll();
    }

    /**
     * A stream error is the relay's stream error handler's to log, and an error raised writing is not the peer's.
     */
    @Test
    public void shouldLogNoStreamErrorAndNoErrorRaisedWriting() throws Exception {
        EmbeddedChannel streamError = loopback(new Http2FrameAdapter());
        HttpToHttp2ConnectionHandler handler = streamError.pipeline().get(HttpToHttp2ConnectionHandler.class);
        handler.onError(streamError.pipeline().context(handler), false, Http2Exception.streamError(3, Http2Error.PROTOCOL_ERROR, "a stream error"));
        streamError.finishAndReleaseAll();

        EmbeddedChannel outbound = loopback(new Http2FrameAdapter());
        handler = outbound.pipeline().get(HttpToHttp2ConnectionHandler.class);
        handler.onError(outbound.pipeline().context(handler), true, Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "raised writing"));
        assertThat("still closed by Netty", sentGoAways(outbound), hasItem(Http2Error.PROTOCOL_ERROR));
        outbound.finishAndReleaseAll();

        assertThat(logged, empty());
    }

    private EmbeddedChannel loopback(Http2FrameListener frameListener) {
        EmbeddedChannel loopback = new EmbeddedChannel(Http2RequestHeaderLimit.relayLoopbackHandler(recording, new EmbeddedChannel(), new DefaultHttp2Connection(false), frameListener, null));
        // the client preface and SETTINGS
        drainOutbound(loopback).release();
        return loopback;
    }

    private static ByteBuf serverSettings() {
        EmbeddedChannel server = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        ChannelHandlerContext ctx = server.pipeline().firstContext();
        new DefaultHttp2FrameWriter().writeSettings(ctx, new Http2Settings(), ctx.newPromise());
        server.flush();
        return drainOutbound(server);
    }

    private static ByteBuf drainOutbound(EmbeddedChannel channel) {
        ByteBuf written = Unpooled.buffer();
        for (Object part = channel.readOutbound(); part != null; part = channel.readOutbound()) {
            written.writeBytes((ByteBuf) part);
            ((ByteBuf) part).release();
        }
        return written;
    }

    private static List<Http2Error> sentGoAways(EmbeddedChannel channel) {
        ByteBuf written = drainOutbound(channel);
        try {
            List<Http2Error> goAways = new ArrayList<>();
            while (written.readableBytes() >= 9) {
                int length = written.readUnsignedMedium();
                byte type = written.readByte();
                written.skipBytes(5);
                ByteBuf payload = written.readSlice(length);
                if (type == Http2FrameTypes.GO_AWAY) {
                    goAways.add(Http2Error.valueOf(payload.getUnsignedInt(4)));
                }
            }
            assertThat("whole frames", written.isReadable(), is(false));
            return goAways;
        } finally {
            written.release();
        }
    }

    private static Throwable reachedTheEndOfThePipeline(EmbeddedChannel channel) {
        try {
            channel.checkException();
            return null;
        } catch (Throwable throwable) {
            return throwable;
        }
    }
}

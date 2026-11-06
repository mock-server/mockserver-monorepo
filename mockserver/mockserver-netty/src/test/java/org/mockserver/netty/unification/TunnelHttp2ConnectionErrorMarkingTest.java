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
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;

import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A connection error Netty's handler reads on either HTTP/2 leg of a CONNECT or SOCKS tunnel, which it answers with a
 * {@code GOAWAY} and a close and fires down no pipeline, marks the tunnel's client leg closed by MockServer, so a
 * response streamed through the tunnel is not recorded as one its client closed. An error raised writing, a stream
 * error and a client leg already closed are left unmarked.
 */
public class TunnelHttp2ConnectionErrorMarkingTest {

    private static final byte[] PREFACE = "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);
    // a DATA frame on stream 0, which must be associated with a stream
    private static final byte[] DATA_ON_STREAM_ZERO = {0, 0, 0, Http2FrameTypes.DATA, 0, 0, 0, 0, 0};

    private final MockServerLogger mockServerLogger = new MockServerLogger(TunnelHttp2ConnectionErrorMarkingTest.class);

    @Test
    public void shouldMarkTheClientLegForAConnectionErrorItsHandlerReads() throws Exception {
        for (Supplier<Http2FrameListener> listener : connectionErrorCausingListeners()) {
            EmbeddedChannel clientLeg = clientLeg(listener.get());

            clientLeg.writeInbound(Unpooled.wrappedBuffer(Unpooled.wrappedBuffer(PREFACE), settings(), Unpooled.wrappedBuffer(DATA_ON_STREAM_ZERO)));

            assertThat(clientLeg.isOpen(), is(false));
            assertThat(ServerClosedChannels.isClosedByServer(clientLeg), is(true));
            clientLeg.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldMarkTheClientLegForAConnectionErrorTheLoopbackHandlerReads() throws Exception {
        for (Supplier<Http2FrameListener> listener : connectionErrorCausingListeners()) {
            EmbeddedChannel proxyClient = new EmbeddedChannel();
            EmbeddedChannel loopback = loopback(proxyClient, listener.get());

            loopback.writeInbound(Unpooled.wrappedBuffer(settings(), Unpooled.wrappedBuffer(DATA_ON_STREAM_ZERO)));

            assertThat(loopback.isOpen(), is(false));
            assertThat(ServerClosedChannels.isClosedByServer(proxyClient), is(true));
            loopback.finishAndReleaseAll();
            proxyClient.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotMarkTheClientLegForAStreamErrorAnErrorRaisedWritingOrAClientLegAlreadyClosed() {
        EmbeddedChannel streamError = clientLeg(new Http2FrameAdapter());
        onError(streamError, false, Http2Exception.streamError(3, Http2Error.PROTOCOL_ERROR, "a stream error"));
        EmbeddedChannel raisedWriting = clientLeg(new Http2FrameAdapter());
        onError(raisedWriting, true, Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "raised writing"));

        EmbeddedChannel loopbacksProxyClient = new EmbeddedChannel();
        EmbeddedChannel loopback = loopback(loopbacksProxyClient, new Http2FrameAdapter());
        onError(loopback, true, Http2Exception.connectionError(Http2Error.PROTOCOL_ERROR, "raised writing"));
        EmbeddedChannel closedProxyClient = new EmbeddedChannel();
        EmbeddedChannel loopbackOfClosed = loopback(closedProxyClient, new Http2FrameAdapter());
        closedProxyClient.close();
        loopbackOfClosed.writeInbound(Unpooled.wrappedBuffer(settings(), Unpooled.wrappedBuffer(DATA_ON_STREAM_ZERO)));

        assertThat("a stream error", ServerClosedChannels.isClosedByServer(streamError), is(false));
        assertThat("raised writing on the client leg", ServerClosedChannels.isClosedByServer(raisedWriting), is(false));
        assertThat("still closed by Netty", raisedWriting.isOpen(), is(false));
        assertThat("raised writing on the loopback", ServerClosedChannels.isClosedByServer(loopbacksProxyClient), is(false));
        assertThat("a client leg already closed", ServerClosedChannels.isClosedByServer(closedProxyClient), is(false));
        for (EmbeddedChannel channel : new EmbeddedChannel[]{streamError, raisedWriting, loopback, loopbacksProxyClient, loopbackOfClosed, closedProxyClient}) {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * Each frame listener lets the DATA frame on stream 0 fail as a protocol error, or throws itself on the SETTINGS
     * ahead of it, as the adapter that aggregates a message can meet the direct memory limit inside Netty's handler.
     */
    private static Supplier<Http2FrameListener>[] connectionErrorCausingListeners() throws Exception {
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        OutOfDirectMemoryError limitReached = constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory");
        @SuppressWarnings("unchecked")
        Supplier<Http2FrameListener>[] listeners = new Supplier[]{
            Http2FrameAdapter::new,
            () -> throwingOnSettings(new IllegalStateException("listener failed")),
            () -> throwingOnSettings(limitReached)
        };
        return listeners;
    }

    private static Http2FrameListener throwingOnSettings(Throwable thrown) {
        return new Http2FrameAdapter() {
            @Override
            public void onSettingsRead(ChannelHandlerContext ctx, Http2Settings settings) {
                if (thrown instanceof Error) {
                    throw (Error) thrown;
                }
                throw (RuntimeException) thrown;
            }
        };
    }

    private EmbeddedChannel clientLeg(Http2FrameListener frameListener) {
        EmbeddedChannel clientLeg = new EmbeddedChannel(Http2RequestHeaderLimit.tunnelServerHandler(configuration(), mockServerLogger, new DefaultHttp2Connection(true), frameListener, null, false));
        drainOutbound(clientLeg).release();
        return clientLeg;
    }

    private EmbeddedChannel loopback(EmbeddedChannel proxyClient, Http2FrameListener frameListener) {
        EmbeddedChannel loopback = new EmbeddedChannel(Http2RequestHeaderLimit.relayLoopbackHandler(mockServerLogger, proxyClient, new DefaultHttp2Connection(false), frameListener, null));
        // the client preface and SETTINGS
        drainOutbound(loopback).release();
        return loopback;
    }

    private static void onError(EmbeddedChannel channel, boolean outbound, Throwable cause) {
        HttpToHttp2ConnectionHandler handler = channel.pipeline().get(HttpToHttp2ConnectionHandler.class);
        handler.onError(channel.pipeline().context(handler), outbound, cause);
    }

    private static ByteBuf settings() {
        EmbeddedChannel peer = new EmbeddedChannel(new ChannelOutboundHandlerAdapter());
        ChannelHandlerContext ctx = peer.pipeline().firstContext();
        new DefaultHttp2FrameWriter().writeSettings(ctx, new Http2Settings(), ctx.newPromise());
        peer.flush();
        return drainOutbound(peer);
    }

    private static ByteBuf drainOutbound(EmbeddedChannel channel) {
        ByteBuf written = Unpooled.buffer();
        for (Object part = channel.readOutbound(); part != null; part = channel.readOutbound()) {
            written.writeBytes((ByteBuf) part);
            ((ByteBuf) part).release();
        }
        return written;
    }
}

package org.mockserver.netty.proxy.relay;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.*;
import io.netty.util.AttributeKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import static io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Which reset {@link LoopbackHttp2StreamErrorHandler} writes to the proxy client's stream when a loopback stream ends
 * without a response, read back as frames from the client's {@link Http2ConnectionHandler}. Run with the handler on
 * either side of {@link LoopbackHttp2StreamIdRemapper}, since neither may depend on the other's position.
 */
@RunWith(Parameterized.class)
public class LoopbackHttp2StreamErrorHandlerTest {

    private static final int STREAM_ID_UNDER_TEST = 3;

    @Parameterized.Parameters(name = "error handler first: {0}")
    public static Collection<Object[]> handlerOrders() {
        return Arrays.asList(new Object[]{true}, new Object[]{false});
    }

    @Parameterized.Parameter
    public boolean errorHandlerFirst;

    private EmbeddedChannel proxyClientChannel;
    private Http2Connection proxyClientConnection;
    private EmbeddedChannel loopbackChannel;
    private Http2Connection loopbackConnection;
    private LoopbackHttp2StreamIdRemapper streamIds;
    private LoopbackHttp2StreamErrorHandler handler;
    private MockServerLogger mockServerLogger;

    @Before
    public void setUp() throws Exception {
        proxyClientConnection = new DefaultHttp2Connection(true);
        proxyClientChannel = new EmbeddedChannel(new HttpToHttp2ConnectionHandlerBuilder()
            .connection(proxyClientConnection)
            .frameListener(new Http2FrameAdapter())
            .build());
        proxyClientConnection.remote().createStream(STREAM_ID_UNDER_TEST, true);
        // the server's SETTINGS
        proxyClientChannel.releaseOutbound();

        loopbackConnection = new DefaultHttp2Connection(false);
        mockServerLogger = mock(MockServerLogger.class);
        when(mockServerLogger.isEnabledForInstance(any())).thenReturn(true);
        streamIds = new LoopbackHttp2StreamIdRemapper(mockServerLogger, loopbackConnection, proxyClientChannel);
        handler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, loopbackConnection, streamIds, proxyClientChannel);
        loopbackChannel = errorHandlerFirst ? new EmbeddedChannel(handler, streamIds) : new EmbeddedChannel(streamIds, handler);
    }

    @After
    public void tearDown() {
        loopbackChannel.finishAndReleaseAll();
        proxyClientChannel.finishAndReleaseAll();
    }

    @Test
    public void shouldRelayTheLoopbackPeersResetCodeAsItIsWithoutLoggingIt() throws Exception {
        Http2Stream loopbackStream = sentStream();
        // a code Http2Error does not know, which InboundHttp2ToHttpAdapter could not report
        handler.frameListener(new Http2FrameAdapter()).onRstStreamRead(null, loopbackStream.id(), 0x77L);
        loopbackStream.close();

        assertThat(resetsWrittenToTheProxyClient(), contains(0x77L));
        verify(mockServerLogger, never()).logEvent(any());
    }

    @Test
    public void shouldResetWithInternalErrorWhenTheRelayItselfFailed() throws Exception {
        sentStream().close();

        assertThat(resetsWrittenToTheProxyClient(), contains(Http2Error.INTERNAL_ERROR.code()));
    }

    @Test
    public void shouldRecordTheLocalFailureAndRethrowIt() throws Exception {
        Http2Stream loopbackStream = sentStream();
        Http2Exception.StreamException decodeFailure = (Http2Exception.StreamException) Http2Exception.streamError(loopbackStream.id(), Http2Error.INTERNAL_ERROR, "corrupt");
        Http2FrameListener listener = handler.frameListener(new Http2FrameAdapter() {
            @Override
            public int onDataRead(ChannelHandlerContext ctx, int streamId, ByteBuf data, int padding, boolean endOfStream) throws Http2Exception {
                throw decodeFailure;
            }
        });
        Http2Exception.StreamException rethrown = assertThrows(Http2Exception.StreamException.class, () -> listener.onDataRead(null, loopbackStream.id(), Unpooled.EMPTY_BUFFER, 0, false));
        assertThat(rethrown, sameInstance(decodeFailure));
        loopbackStream.close();

        assertThat(resetsWrittenToTheProxyClient(), contains(Http2Error.INTERNAL_ERROR.code()));
        ArgumentCaptor<LogEntry> logged = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger).logEvent(logged.capture());
        assertThat(logged.getValue().getThrowable(), sameInstance(decodeFailure));
    }

    @Test
    public void shouldRefuseAStreamWhoseRequestWasNeverSent() throws Exception {
        loopbackConnection.local().createStream(streamIds.pair(STREAM_ID_UNDER_TEST), false).close();

        assertThat(resetsWrittenToTheProxyClient(), contains(Http2Error.REFUSED_STREAM.code()));
    }

    @Test
    public void shouldRefuseAStreamAGoAwaySaysWasNotProcessed() throws Exception {
        sentStream();
        // closes every stream above the last one the peer processed
        loopbackConnection.goAwayReceived(0, Http2Error.NO_ERROR.code(), Unpooled.EMPTY_BUFFER);

        assertThat(resetsWrittenToTheProxyClient(), contains(Http2Error.REFUSED_STREAM.code()));
    }

    @Test
    public void shouldNotResetAStreamWhoseResponseWasRelayed() throws Exception {
        Http2Stream loopbackStream = sentStream();
        loopbackChannel.writeInbound(response(loopbackStream.id(), HttpResponseStatus.OK));
        FullHttpResponse relayed = loopbackChannel.readInbound();
        assertThat(relayed.headers().getInt(STREAM_ID.text()), is(STREAM_ID_UNDER_TEST));
        relayed.release();
        loopbackStream.close();

        assertThat(streamIds.answered(STREAM_ID_UNDER_TEST), is(true));
        assertThat(resetsWrittenToTheProxyClient(), is(empty()));
    }

    @Test
    public void shouldResetAStreamWhoseOnlyRelayedResponseWasInformational() throws Exception {
        Http2Stream loopbackStream = sentStream();
        loopbackChannel.writeInbound(response(loopbackStream.id(), HttpResponseStatus.CONTINUE));
        FullHttpResponse relayed = loopbackChannel.readInbound();
        relayed.release();
        loopbackStream.close();

        assertThat(resetsWrittenToTheProxyClient(), contains(Http2Error.INTERNAL_ERROR.code()));
    }

    @Test
    public void shouldNotResetWhenTheWholeLoopbackIsClosing() throws Exception {
        Http2Stream loopbackStream = sentStream();
        loopbackChannel.close();
        loopbackStream.close();

        assertThat(resetsWrittenToTheProxyClient(), is(empty()));
    }

    @Test
    public void shouldDropAndReleaseAResponseForAClientStreamThatHasEnded() throws Exception {
        Http2Stream loopbackStream = sentStream();
        proxyClientConnection.stream(STREAM_ID_UNDER_TEST).close();
        FullHttpResponse response = response(loopbackStream.id(), HttpResponseStatus.OK);

        loopbackChannel.writeInbound(response);

        assertThat((Object) loopbackChannel.readInbound(), nullValue());
        assertThat(response.refCnt(), is(0));
        loopbackStream.close();
        assertThat("the client's stream is gone, so it is not reset either", resetsWrittenToTheProxyClient(), is(empty()));
    }

    @Test
    public void shouldResetTheLoopbackStreamWhenTheClientResetsItsOwnWithoutAnsweringTheClient() throws Exception {
        Http2Connection connectedLoopbackConnection = new DefaultHttp2Connection(false);
        LoopbackHttp2StreamIdRemapper connectedStreamIds = new LoopbackHttp2StreamIdRemapper(mockServerLogger, connectedLoopbackConnection, proxyClientChannel);
        LoopbackHttp2StreamErrorHandler connectedHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, connectedLoopbackConnection, connectedStreamIds, proxyClientChannel);
        EmbeddedChannel connectedLoopbackChannel = new EmbeddedChannel(new HttpToHttp2ConnectionHandlerBuilder()
            .connection(connectedLoopbackConnection)
            .frameListener(new Http2FrameAdapter())
            .build(), errorHandlerFirst ? connectedHandler : connectedStreamIds, errorHandlerFirst ? connectedStreamIds : connectedHandler);
        try {
            // the client preface and SETTINGS
            connectedLoopbackChannel.releaseOutbound();
            int loopbackStreamId = connectedStreamIds.pair(STREAM_ID_UNDER_TEST);
            connectedLoopbackConnection.local().createStream(loopbackStreamId, false).headersSent(false);

            connectedHandler.proxyClientFrameListener(proxyClientConnection, new Http2FrameAdapter()).onRstStreamRead(null, STREAM_ID_UNDER_TEST, Http2Error.CANCEL.code());

            assertThat(resetsWrittenTo(connectedLoopbackChannel, loopbackStreamId), contains(Http2Error.CANCEL.code()));
            assertThat("the loopback stream closed while the client's still existed", connectedLoopbackConnection.stream(loopbackStreamId), nullValue());
            assertThat(connectedStreamIds.loopbackStreamId(STREAM_ID_UNDER_TEST), nullValue());
            assertThat(resetsWrittenToTheProxyClient(), is(empty()));
        } finally {
            connectedLoopbackChannel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldMarkTheLoopbackStreamAsResetByTheServerWhenMockServerResetTheClientsStreamOnAStall() throws Exception {
        Http2Connection connectedLoopbackConnection = new DefaultHttp2Connection(false);
        LoopbackHttp2StreamIdRemapper connectedStreamIds = new LoopbackHttp2StreamIdRemapper(mockServerLogger, connectedLoopbackConnection, proxyClientChannel);
        LoopbackHttp2StreamErrorHandler connectedHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, connectedLoopbackConnection, connectedStreamIds, proxyClientChannel);
        EmbeddedChannel connectedLoopbackChannel = new EmbeddedChannel(new HttpToHttp2ConnectionHandlerBuilder()
            .connection(connectedLoopbackConnection)
            .frameListener(new Http2FrameAdapter())
            .build(), errorHandlerFirst ? connectedHandler : connectedStreamIds, errorHandlerFirst ? connectedStreamIds : connectedHandler);
        try {
            connectedLoopbackChannel.releaseOutbound();
            int loopbackStreamId = connectedStreamIds.pair(STREAM_ID_UNDER_TEST);
            connectedLoopbackConnection.local().createStream(loopbackStreamId, false).headersSent(false);
            connectedHandler.proxyClientFrameListener(proxyClientConnection, new Http2FrameAdapter());
            ServerClosedChannels.markHttp2StreamReset(proxyClientChannel, STREAM_ID_UNDER_TEST);

            proxyClientConnection.stream(STREAM_ID_UNDER_TEST).close();

            assertThat(resetsWrittenTo(connectedLoopbackChannel, loopbackStreamId), contains(Http2Error.CANCEL.code()));
            assertThat(relayedResets(), contains(loopbackStreamId));
            EmbeddedChannel accepted = new EmbeddedChannel();
            RelayLoopbackAddresses.rememberProxyClient(accepted, proxyClientChannel);
            Http2FrameStream frameStream = mock(Http2FrameStream.class);
            when(frameStream.id()).thenReturn(loopbackStreamId);
            Http2StreamChannel loopbackStream = mock(Http2StreamChannel.class);
            when(loopbackStream.parent()).thenReturn(accepted);
            when(loopbackStream.stream()).thenReturn(frameStream);
            assertThat("the client leg's mark is passed on", ServerClosedChannels.isClosedByServer(loopbackStream), is(true));
            assertThat("the client leg's mark is consumed", ServerClosedChannels.consumeHttp2StreamReset(proxyClientChannel, STREAM_ID_UNDER_TEST), is(false));
        } finally {
            connectedLoopbackChannel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotMarkALoopbackStreamThatHasAlreadyClosedWhenMockServerResetTheClientsStreamOnAStall() throws Exception {
        Http2Connection connectedLoopbackConnection = new DefaultHttp2Connection(false);
        LoopbackHttp2StreamIdRemapper connectedStreamIds = new LoopbackHttp2StreamIdRemapper(mockServerLogger, connectedLoopbackConnection, proxyClientChannel);
        LoopbackHttp2StreamErrorHandler connectedHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, connectedLoopbackConnection, connectedStreamIds, proxyClientChannel);
        EmbeddedChannel connectedLoopbackChannel = new EmbeddedChannel(new HttpToHttp2ConnectionHandlerBuilder()
            .connection(connectedLoopbackConnection)
            .frameListener(new Http2FrameAdapter())
            .build(), errorHandlerFirst ? connectedHandler : connectedStreamIds, errorHandlerFirst ? connectedStreamIds : connectedHandler);
        try {
            connectedLoopbackChannel.releaseOutbound();
            int loopbackStreamId = connectedStreamIds.pair(STREAM_ID_UNDER_TEST);
            Http2Stream loopbackStream = connectedLoopbackConnection.local().createStream(loopbackStreamId, false);
            loopbackStream.headersSent(false);
            connectedHandler.proxyClientFrameListener(proxyClientConnection, new Http2FrameAdapter());
            // the client's stream is reset on a stall, and closes once its RST_STREAM is written
            ServerClosedChannels.markHttp2StreamReset(proxyClientChannel, STREAM_ID_UNDER_TEST);

            // MockServer resets the loopback stream, so the relay resets the client's, which closes as the loopback stream closes
            connectedHandler.frameListener(new Http2FrameAdapter()).onRstStreamRead(null, loopbackStreamId, Http2Error.CANCEL.code());
            loopbackStream.close();

            assertThat(resetsWrittenToTheProxyClient(), contains(Http2Error.CANCEL.code()));
            assertThat("the client's stream has closed", proxyClientConnection.stream(STREAM_ID_UNDER_TEST), nullValue());
            assertThat("no reset answers the loopback's", resetsWrittenTo(connectedLoopbackChannel, loopbackStreamId), is(empty()));
            assertThat("the client leg's mark is consumed", ServerClosedChannels.consumeHttp2StreamReset(proxyClientChannel, STREAM_ID_UNDER_TEST), is(false));
            assertThat("no id is held for a reset never sent", relayedResets(), is(empty()));
        } finally {
            connectedLoopbackChannel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotMarkTheLoopbackStreamWhenTheClientResetsItsOwn() throws Exception {
        Http2Connection connectedLoopbackConnection = new DefaultHttp2Connection(false);
        LoopbackHttp2StreamIdRemapper connectedStreamIds = new LoopbackHttp2StreamIdRemapper(mockServerLogger, connectedLoopbackConnection, proxyClientChannel);
        LoopbackHttp2StreamErrorHandler connectedHandler = new LoopbackHttp2StreamErrorHandler(mockServerLogger, connectedLoopbackConnection, connectedStreamIds, proxyClientChannel);
        EmbeddedChannel connectedLoopbackChannel = new EmbeddedChannel(new HttpToHttp2ConnectionHandlerBuilder()
            .connection(connectedLoopbackConnection)
            .frameListener(new Http2FrameAdapter())
            .build(), errorHandlerFirst ? connectedHandler : connectedStreamIds, errorHandlerFirst ? connectedStreamIds : connectedHandler);
        try {
            connectedLoopbackChannel.releaseOutbound();
            int loopbackStreamId = connectedStreamIds.pair(STREAM_ID_UNDER_TEST);
            connectedLoopbackConnection.local().createStream(loopbackStreamId, false).headersSent(false);
            connectedHandler.proxyClientFrameListener(proxyClientConnection, new Http2FrameAdapter());

            proxyClientConnection.stream(STREAM_ID_UNDER_TEST).close();

            EmbeddedChannel accepted = new EmbeddedChannel();
            RelayLoopbackAddresses.rememberProxyClient(accepted, proxyClientChannel);
            Http2FrameStream frameStream = mock(Http2FrameStream.class);
            when(frameStream.id()).thenReturn(loopbackStreamId);
            Http2StreamChannel loopbackStream = mock(Http2StreamChannel.class);
            when(loopbackStream.parent()).thenReturn(accepted);
            when(loopbackStream.stream()).thenReturn(frameStream);
            assertThat(ServerClosedChannels.isClosedByServer(loopbackStream), is(false));
        } finally {
            connectedLoopbackChannel.finishAndReleaseAll();
        }
    }

    private Set<Integer> relayedResets() {
        Set<Integer> ids = proxyClientChannel.attr(AttributeKey.<Set<Integer>>valueOf("mockserver.relayedResetHttp2Streams")).get();
        return ids != null ? ids : Collections.emptySet();
    }

    private Http2Stream sentStream() throws Http2Exception {
        // a loopback stream id other than the client's, as the remapper gives it
        Http2Stream stream = loopbackConnection.local().createStream(streamIds.pair(STREAM_ID_UNDER_TEST), false);
        stream.headersSent(false);
        return stream;
    }

    private static FullHttpResponse response(int streamId, HttpResponseStatus status) {
        FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.copiedBuffer(new byte[]{1, 2, 3}));
        response.headers().setInt(STREAM_ID.text(), streamId);
        return response;
    }

    private List<Long> resetsWrittenToTheProxyClient() throws Http2Exception {
        return resetsWrittenTo(proxyClientChannel, STREAM_ID_UNDER_TEST);
    }

    private static List<Long> resetsWrittenTo(EmbeddedChannel channel, int expectedStreamId) throws Http2Exception {
        List<Long> resetCodes = new ArrayList<>();
        DefaultHttp2FrameReader reader = new DefaultHttp2FrameReader();
        Http2FrameAdapter listener = new Http2FrameAdapter() {
            @Override
            public void onRstStreamRead(ChannelHandlerContext ctx, int streamId, long errorCode) {
                assertThat(streamId, is(expectedStreamId));
                resetCodes.add(errorCode);
            }
        };
        ByteBuf written;
        while ((written = channel.readOutbound()) != null) {
            try {
                reader.readFrame(null, written, listener);
            } finally {
                written.release();
            }
        }
        reader.close();
        return resetCodes;
    }
}

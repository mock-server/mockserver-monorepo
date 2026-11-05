package org.mockserver.netty.connection;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2Connection;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2LocalFlowController;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2Connection;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Exception;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2FrameStreamEvent;
import io.netty.handler.codec.http2.Http2FrameStreamException;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2RemoteFlowController;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.handler.codec.http2.HttpConversionUtil;
import io.netty.handler.codec.http2.HttpToHttp2ConnectionHandlerBuilder;
import io.netty.handler.codec.http2.InboundHttp2ToHttpAdapterBuilder;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.metrics.Metrics;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Drives {@link Http2StreamWriteStallHandler} through a real HTTP/2 client and server codec joined in memory, so the
 * connection's socket writability can be set directly: a stream whose own window is open waits only for the socket,
 * which is the connection watcher's to time, while a stream whose window is closed waits for its client.
 */
public class Http2StreamWriteStallHandlerTest {

    private static final long TIMEOUT_MILLIS = 200;
    private static final int RESPONSE_BYTES = 16 * 1024;
    private static final String LARGE_RESPONSE_PATH = "/large";
    private static final int LARGE_RESPONSE_BYTES = 4 * Http2CodecUtil.DEFAULT_WINDOW_SIZE;
    private static final String STAGED_RESPONSE_PATH = "/staged";
    private static final int STAGED_FIRST_BYTES = 40_000;
    private static final String TRAILERS_ONLY_PATH = "/trailers";
    private static final String PARTS_RESPONSE_PATH = "/parts";
    private static final int[] PARTS_BYTES = {66_000, 10_000, 150_000};
    private static final int ON_TIME_ATTEMPTS = 5;
    private static final int SOCKET_WRITABILITY = 1;
    private static final int SMALL_WINDOW = 1024;
    private static final long CHECK_INTERVAL_MILLIS = WriteStallTimeoutHandler.checkIntervalMillis(TIMEOUT_MILLIS);
    private static final int CHECKS_PER_TIMEOUT = (int) (TIMEOUT_MILLIS / CHECK_INTERVAL_MILLIS);
    // several timeouts' worth of the watcher's checks
    private static final int CHECKS = 5 * CHECKS_PER_TIMEOUT;
    private static final long DEADLINE_MILLIS = 20 * TIMEOUT_MILLIS;

    private EmbeddedChannel server;
    private EmbeddedChannel client;
    private Socket socket;
    private Http2Connection serverConnection;
    private Http2FrameCodec clientCodec;
    private Client clientHandler;
    private Responder responder;
    private HttpResponder httpResponder;
    private long lastCheckBeganNanos;
    private boolean dropConnectionWindowUpdates;
    private int droppedConnectionWindowUpdates;
    private final Map<Integer, Integer> smallestReturns = new HashMap<>();

    @Before
    public void enableMetrics() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration().metricsEnabled(true));
    }

    @After
    public void close() {
        closeChannels();
        Metrics.resetAdditionalMetricsForTesting();
    }

    private void closeChannels() {
        if (client != null) {
            client.finishAndReleaseAll();
        }
        if (server != null) {
            // the stand-in socket still holds what a test left unwritable, and would hold the GOAWAY closing writes
            socket.discardHeld();
            server.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldNotResetAStreamWithAnOpenWindowWaitingOnlyForTheSocket() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        socketWritable(false);

        ClientStream stream = clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stream was not reset", stream.resetErrorCode, is(nullValue()));
        assertThat("the stream is still open", serverConnection.numActiveStreams(), is(1));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));

        socketWritable(true);
        exchange();
        assertThat("the response was sent once the socket was writable", stream.dataBytes, is(RESPONSE_BYTES));
        assertThat(stream.endStream, is(true));
    }

    @Test
    public void shouldResetAStreamWithAnOpenWindowBehindAnUnwritableSocketWhenNoConnectionWatcherIsPresent() throws Exception {
        // a connection accepted while the timeout was 0 has no connection watcher to cut a socket stall
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE, false);
        socketWritable(false);

        clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamWhoseClientGrantsItNoWindow() throws Exception {
        connect(0);

        ClientStream stream = clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stream was reset", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(serverConnection.numActiveStreams(), is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        assertThat("the reset is recorded as MockServer's own", ServerClosedChannels.consumeHttp2StreamReset(server, stream.frameStream.id()), is(true));
    }

    @Test
    public void shouldResetAStreamWhoseClientGrantsItNoWindowWhileTheSocketIsNotWritableOnce() throws Exception {
        connect(0);
        socketWritable(false);

        ClientStream stream = clientHandler.request("/");
        waitThrough(4 * TIMEOUT_MILLIS);

        // the stream stays active until its RST_STREAM gets past the socket, and is not reset again meanwhile
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        assertThat(serverConnection.numActiveStreams(), is(1));
        serverConnection.forEachActiveStream(serverStream -> {
            assertThat("the stream was reset", serverStream.isResetSent(), is(true));
            return true;
        });

        socketWritable(true);
        exchange();
        assertThat("the reset reached the client once the socket was writable", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(serverConnection.numActiveStreams(), is(0));
    }

    @Test
    public void shouldLetAStreamCarryOnOnceTheStalledStreamHoldingTheConnectionWindowIsReset() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);

        // the client consumes nothing, so the first stream's window's worth also takes the whole connection window
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        assertThat(holder.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(sibling.dataBytes, is(0));

        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", sibling.resetErrorCode, is(nullValue()));
        assertThat("its sibling got the connection window its client released", sibling.dataBytes, is(RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamAfterItsFreshPeriodWhenItsClientReleasesNoConnectionWindow() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        dropConnectionWindowUpdates = true;

        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the connection window it held was released, and discarded", droppedConnectionWindowUpdates, is(greaterThan(0)));

        waitThrough(4 * TIMEOUT_MILLIS);

        assertThat("its sibling, given no window in its fresh period, was reset", sibling.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(sibling.dataBytes, is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        long apartMillis = TimeUnit.NANOSECONDS.toMillis(sibling.resetNanos - holder.resetNanos);
        assertThat("its sibling was reset after a fresh period, not with the stalled stream (" + apartMillis + " ms apart)", apartMillis >= TIMEOUT_MILLIS / 2, is(true));
    }

    @Test
    public void shouldLetAStreamCarryOnOnceAStalledStreamWhoseOwnWindowIsStillOpenIsReset() throws Exception {
        // at default priority the two streams share the connection window; the client takes the holder's data only up
        // to a cut-off, and at some cut-offs the holder's own window is still open when the connection window closes
        boolean holderWindowOpenSeen = false;
        for (int cutOff : new int[]{1, 10_000, 20_000, 33_000, 50_000, 70_000, 100_000, 130_000, 160_000}) {
            closeChannels();
            long countedBefore = Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM);
            connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
            ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH, cutOff);
            ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
            exchange();
            assertThat("the connection window closed at cut-off " + cutOff, serverWindow(0), is(0));
            holderWindowOpenSeen |= serverWindow(holder.frameStream.id()) > 0;

            waitThrough(4 * TIMEOUT_MILLIS);

            assertThat("the holder was reset at cut-off " + cutOff, holder.resetErrorCode, is(Http2Error.CANCEL.code()));
            assertThat("its sibling was not reset at cut-off " + cutOff, sibling.resetErrorCode, is(nullValue()));
            assertThat("its sibling completed at cut-off " + cutOff, sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
            assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(countedBefore + 1));
        }
        assertThat("some cut-off left the holder's own window open", holderWindowOpenSeen, is(true));
    }

    @Test
    public void shouldResetOnlyTheStalledStreamWhenItsSiblingHoldsOneByteLessOfTheConnectionWindow() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);

        // the holder opens once its sibling's data is arriving, and takes what its sibling's client has not yet returned
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        // Netty's codec returns window once more than half of it is consumed, so the most it leaves unreturned is 32,767
        assertThat("the holder's unconsumed data", held(holder), is(32_768));
        assertThat("what the sibling's client consumed and has not returned", held(sibling), is(32_767));

        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldLetEverySiblingCarryOnOnceTheStalledStreamAmongThemIsReset() throws Exception {
        boolean holderWindowOpenSeen = false;
        for (int cutOff : new int[]{32_768, 65_535, 75_090, 80_096}) {
            closeChannels();
            long countedBefore = Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM);
            connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
            ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH, cutOff);
            ClientStream[] siblings = new ClientStream[3];
            for (int i = 0; i < siblings.length; i++) {
                siblings[i] = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
            }
            exchange();
            assertThat("the connection window closed at cut-off " + cutOff, serverWindow(0), is(0));
            holderWindowOpenSeen |= serverWindow(holder.frameStream.id()) > 0;

            waitUntilStallsCounted(countedBefore + 1);

            assertThat("the holder was reset at cut-off " + cutOff, holder.resetErrorCode, is(Http2Error.CANCEL.code()));
            for (ClientStream sibling : siblings) {
                assertThat("no sibling was reset at cut-off " + cutOff, sibling.resetErrorCode, is(nullValue()));
                assertThat("every sibling completed at cut-off " + cutOff, sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
            }
            assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(countedBefore + 1));
        }
        assertThat("some cut-off left the holder's own window open", holderWindowOpenSeen, is(true));
    }

    @Test
    public void shouldResetEveryStreamHoldingTheMostOfTheConnectionWindowWhenTheyHoldTheSame() throws Exception {
        // a stream window above the connection window lets a client leave as much of a stream it consumes unreturned as
        // a stalled stream holds; its client has returned window for neither, so the two cannot be told apart, and
        // resetting both is what surely releases the window
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(80_000);
        exchange();

        ClientStream first = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream second = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        first.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("a consumed sibling holds as much as the holder", held(second), is(held(holder)));
        assertThat("its client has returned no window for it yet", smallestReturn(second), is(0));
        assertThat("the other sibling holds less", held(first) < held(holder), is(true));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(second.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling holding less was not reset", first.resetErrorCode, is(nullValue()));
        assertThat(first.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(first.endStream, is(true));
    }

    @Test
    public void shouldResetOnlyTheStalledStreamWhenAConsumedSiblingHoldsMoreOfTheConnectionWindow() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        resetsOnlyTheStalledStreamWhenAConsumedSiblingHoldsMoreOfTheConnectionWindow();
    }

    @Test
    public void shouldResetOnlyTheStalledStreamInsideATunnelWhenAConsumedSiblingHoldsMoreOfTheConnectionWindow() throws Exception {
        connectThroughRelayHandler(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        resetsOnlyTheStalledStreamWhenAConsumedSiblingHoldsMoreOfTheConnectionWindow();
    }

    private void resetsOnlyTheStalledStreamWhenAConsumedSiblingHoldsMoreOfTheConnectionWindow() throws Exception {
        // a stream window above the connection window: the client leaves more of the stream it consumes unreturned than
        // the stalled stream holds, but less than it has returned for it at once, and has returned none for the holder
        clientHandler.initialStreamWindow(100_000);
        exchange();
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("the consumed sibling holds more than the holder", held(sibling), is(greaterThan(held(holder))));
        assertThat("less than its client has returned for it at once", held(sibling), is(lessThan(smallestReturn(sibling))));
        assertThat("its client has returned nothing for the holder", smallestReturn(holder), is(0));

        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling its client is consuming was not", sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAConsumedSiblingThatHoldsMoreFirstWhenItsClientHasReturnedNoWindowForIt() throws Exception {
        // with a stream window so far above the connection window that the consumed stream has not yet had window
        // returned, the server cannot tell which of the two its client is reading
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(200_000);
        exchange();

        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("the consumed sibling holds more than the holder", held(sibling), is(greaterThan(held(holder))));
        assertThat("its client has returned no window for either", smallestReturn(sibling) + smallestReturn(holder), is(0));

        waitUntilStallsCounted(1);
        assertThat("the sibling was reset first", sibling.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        waitUntilStallsCounted(2);
        assertThat("the stalled stream, which released nothing, was reset too", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        long apartMillis = TimeUnit.NANOSECONDS.toMillis(holder.resetNanos - sibling.resetNanos);
        assertThat("one period later (" + apartMillis + " ms apart)", apartMillis >= TIMEOUT_MILLIS / 2, is(true));
    }

    @Test
    public void shouldResetAConsumedSiblingFirstWhenNoneOfSeveralStalledStreamsHoldsHalfTheConnectionWindow() throws Exception {
        // three stalled streams share what one would hold, so each holds less than the consumed sibling has unreturned,
        // and no more than a stream being consumed may have unreturned before its first return: nothing marks them
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] holders = new ClientStream[3];
        sibling.onFirstData = () -> {
            for (int i = 0; i < holders.length; i++) {
                holders[i] = clientHandler.request(LARGE_RESPONSE_PATH);
            }
        };
        exchange();
        assertThat(serverWindow(0), is(0));
        int heldByHolders = 0;
        for (ClientStream holder : holders) {
            assertThat("the consumed sibling holds more than each stalled stream", held(sibling), is(greaterThan(held(holder))));
            assertThat("each stalled stream holds less than half the connection window", 2 * held(holder), is(lessThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));
            heldByHolders += held(holder);
        }
        assertThat("together they hold more than half of it", 2 * heldByHolders, is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));
        assertThat("the sibling holds less than its client has returned for it at once", held(sibling), is(lessThan(smallestReturn(sibling))));

        waitUntilStallsCounted(1);
        assertThat("the sibling was reset first", sibling.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        waitUntilStallsCounted(2);
        for (ClientStream holder : holders) {
            assertThat("the stalled streams, which released nothing, were reset a period later", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        }
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(4L));
    }

    @Test
    public void shouldResetEachStalledStreamInTurnWhenTheOneHoldingTheConnectionWindowHoldsMoreThanHalfOfIt() throws Exception {
        // two stalled streams at a stream window above the connection window: the first holds more than half the
        // connection window, the second opens behind it and takes the window the first one's reset releases
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(100_000);
        exchange();
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] holders = new ClientStream[2];
        sibling.onFirstData = () -> holders[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        sibling.reachingDataBytes = 80_000;
        sibling.onReachingDataBytes = () -> holders[1] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the consumed sibling holds more than either stalled stream", held(sibling), is(greaterThan(Math.max(held(holders[0]), held(holders[1])))));
        assertThat("less than its client has returned for it at once", held(sibling), is(lessThan(smallestReturn(sibling))));
        assertThat("the first stalled stream holds more than half the connection window", 2 * held(holders[0]), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);
        assertThat("the first stalled stream was reset", holders[0].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling its client is consuming was not", sibling.resetErrorCode, is(nullValue()));
        assertThat("the second stalled stream took the window that released", 2 * held(holders[1]), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(2);
        assertThat("the second stalled stream was reset in its turn", holders[1].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
    }

    @Test
    public void shouldNotPassOverAStalledStreamForAConsumedStreamOneByteShortOfItsFirstReturn() throws Exception {
        // default windows. The stalled stream's client took all it had of it in one go and then stopped; one of the
        // streams its client is consuming has had no window returned and has half a window less one byte unreturned,
        // the most a stream being consumed can have: exactly not more than half of what the connection has out
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.request("/", Integer.MAX_VALUE);
        exchange();
        ClientStream stalled = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream[] consumed = new ClientStream[3];
        for (int i = 0; i < consumed.length; i++) {
            consumed[i] = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        }
        exchange();
        clientHandler.consumeReceived(stalled);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the stalled stream holds less than its client has returned for it at once", held(stalled), is(lessThan(smallestReturn(stalled))));
        int mostWithNothingReturned = 0;
        for (ClientStream stream : consumed) {
            if (smallestReturn(stream) == 0) {
                mostWithNothingReturned = Math.max(mostWithNothingReturned, held(stream));
            }
        }
        assertThat("a consumed stream with nothing returned has half the connection window less one byte unreturned", mostWithNothingReturned, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE / 2));

        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", stalled.resetErrorCode, is(Http2Error.CANCEL.code()));
        for (ClientStream stream : consumed) {
            assertThat("no stream its client is consuming was", stream.resetErrorCode, is(nullValue()));
            assertThat(stream.dataBytes, is(LARGE_RESPONSE_BYTES));
            assertThat(stream.endStream, is(true));
        }
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetOnlyTheStalledStreamWhenSeveralConsumedStreamsHaveHadNoWindowReturnedYet() throws Exception {
        // default windows. The stalled stream's client took a whole window of it in one go and then stopped, so it holds
        // less than was returned for it at once; two of the streams its client is consuming have had no window returned
        // yet, and together have more than half the connection window unreturned
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream stalled = clientHandler.request(PARTS_RESPONSE_PATH);
        exchange();
        clientHandler.consumeReceived(stalled);
        exchange();
        responder.writeRests.get(0).run();
        exchange();
        ClientStream[] consumed = new ClientStream[3];
        for (int i = 0; i < consumed.length; i++) {
            consumed[i] = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        }
        consumed[0].onFirstData = () -> responder.writeRests.get(1).run();
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the stalled stream holds less than its client has returned for it at once", held(stalled), is(lessThan(smallestReturn(stalled))));
        int withNothingReturned = 0;
        int heldWithNothingReturned = 0;
        for (ClientStream stream : consumed) {
            assertThat("the stalled stream holds more than each consumed stream", held(stalled), is(greaterThan(held(stream))));
            if (smallestReturn(stream) == 0) {
                withNothingReturned++;
                heldWithNothingReturned += held(stream);
            }
        }
        assertThat("two consumed streams have had no window returned", withNothingReturned, is(2));
        assertThat("together they have more than half the connection window unreturned", 2 * heldWithNothingReturned, is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", stalled.resetErrorCode, is(Http2Error.CANCEL.code()));
        for (ClientStream stream : consumed) {
            assertThat("no stream its client is consuming was", stream.resetErrorCode, is(nullValue()));
            assertThat(stream.dataBytes, is(LARGE_RESPONSE_BYTES));
            assertThat(stream.endStream, is(true));
        }
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAConsumedStreamWithNoWindowReturnedBeforeAStreamThatStalledHoldingLessThanItsClientReturnedForIt() throws Exception {
        // a known limit, at a stream window above the connection window: the stalled stream's client consumed it for a
        // while, so what it holds is less than was returned for it at once, and the stream being consumed has more than
        // half the connection window unreturned and none returned yet, which is what a stalled stream looks like
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(200_000);
        exchange();
        ClientStream stalled = clientHandler.request(LARGE_RESPONSE_PATH, 140_000);
        ClientStream[] opened = new ClientStream[1];
        stalled.reachingDataBytes = 110_000;
        stalled.onReachingDataBytes = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        exchange();
        ClientStream consumed = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("the stalled stream holds less than its client has returned for it at once", held(stalled), is(lessThan(smallestReturn(stalled))));
        assertThat("and more than the consumed stream", held(stalled), is(greaterThan(held(consumed))));
        assertThat("which has had no window returned", smallestReturn(consumed), is(0));
        assertThat("and has more than half the connection window unreturned", 2 * held(consumed), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);
        assertThat("the consumed stream was reset first", consumed.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        waitUntilStallsCounted(2);
        assertThat("the stalled stream was reset a period later", stalled.resetErrorCode, is(Http2Error.CANCEL.code()));
        long apartMillis = TimeUnit.NANOSECONDS.toMillis(stalled.resetNanos - consumed.resetNanos);
        assertThat("one period later (" + apartMillis + " ms apart)", apartMillis >= TIMEOUT_MILLIS / 2, is(true));
    }

    @Test
    public void shouldResetAStalledStreamItsClientGrantedWindowBeyondTheInitialWindow() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        resetsAStalledStreamItsClientGrantedWindowBeyondTheInitialWindow();
    }

    @Test
    public void shouldResetAStalledStreamInsideATunnelItsClientGrantedWindowBeyondTheInitialWindow() throws Exception {
        connectThroughRelayHandler(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        resetsAStalledStreamItsClientGrantedWindowBeyondTheInitialWindow();
    }

    private void resetsAStalledStreamItsClientGrantedWindowBeyondTheInitialWindow() throws Exception {
        // the grant reaches the server before any of the holder's data can leave, so it returns nothing; the holder's
        // send window then stays above the initial window however much of the connection window it holds
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> {
            opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
            grantWindow(opened[0], 4 * Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        };
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("the holder was sent half the connection window", holder.dataBytes, is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE / 2)));
        assertThat("its send window is above the initial window", serverWindow(holder.frameStream.id()), is(greaterThan(serverInitialWindow())));
        assertThat("the consumed sibling's is below it", serverWindow(sibling.frameStream.id()), is(lessThan(serverInitialWindow())));

        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling its client is consuming was not", sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldNotCountWindowGrantedBeyondWhatAStreamWasSentAsReturned() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(300_000);
        exchange();
        ClientStream granted = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.sendEveryGrant(granted);
        clientHandler.sendEveryConnectionGrant();
        exchange();
        // three times what the stream has been sent: only what it was sent can be a return, the least returned for it
        // at once is that and not the grant, and the rest returns nothing of the data sent after it
        clientHandler.grantWindow(granted, 3 * granted.dataBytes);
        clientHandler.grantConnectionWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();
        ClientStream other = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        clientHandler.grantConnectionWindow(70_000);
        exchange();
        dropConnectionWindowUpdates = true;
        assertThat(serverWindow(0), is(0));
        assertThat("the granted stream's send window is above the initial window", serverWindow(granted.frameStream.id()), is(greaterThan(serverInitialWindow())));
        int sentSinceTheGrant = granted.dataBytes - Http2CodecUtil.DEFAULT_WINDOW_SIZE;
        assertThat("it was sent more since the grant than the other stream was sent at all", sentSinceTheGrant, is(greaterThan(other.dataBytes)));
        assertThat("and more than the grant could return, though less than the grant", sentSinceTheGrant > Http2CodecUtil.DEFAULT_WINDOW_SIZE && sentSinceTheGrant < smallestReturn(granted), is(true));
        assertThat("the other holds more than half the connection window", 2 * other.dataBytes, is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);

        assertThat("the stream holding the most was reset", granted.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the other was given a fresh period", other.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetOnlyTheStalledStreamWhenItsClientReturnsAConsumedSiblingsWindowLate() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        resetsOnlyTheStalledStreamWhenItsClientReturnsAConsumedSiblingsWindowLate();
    }

    @Test
    public void shouldResetOnlyTheStalledStreamInsideATunnelWhenItsClientReturnsAConsumedSiblingsWindowLate() throws Exception {
        connectThroughRelayHandler(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        resetsOnlyTheStalledStreamWhenItsClientReturnsAConsumedSiblingsWindowLate();
    }

    private void resetsOnlyTheStalledStreamWhenItsClientReturnsAConsumedSiblingsWindowLate() throws Exception {
        // the stream and connection windows are the same size; the client leaves more than half the consumed stream's
        // window unreturned, which is more than the stalled stream holds
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        clientHandler.returnWindowLate(sibling);
        ClientStream[] opened = new ClientStream[1];
        sibling.reachingDataBytes = 104_000;
        sibling.onReachingDataBytes = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        ClientStream holder = opened[0];
        assertThat(serverWindow(0), is(0));
        assertThat("the consumed sibling holds more than half its window", held(sibling), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE / 2)));
        assertThat("which is more than the holder", held(sibling), is(greaterThan(held(holder))));
        assertThat("and less than its client has returned for it at once", held(sibling), is(lessThan(smallestReturn(sibling))));

        waitUntilStallsCounted(1);

        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling its client is consuming was not", sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetTheStreamHoldingTheMostWhenTheOthersHoldTooLittleToHaveClosedTheConnectionWindow() throws Exception {
        ClientStream[] streams = twoStreamsOfAClientThatConsumesNeither(30_000);
        ClientStream most = streams[0];
        ClientStream other = streams[1];
        // the client returns most of the first stream's window at once, so what it still holds is less than that
        clientHandler.grantWindow(most, 100_000);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("it holds less than its client has returned for it at once", held(most), is(lessThan(smallestReturn(most))));
        assertThat("and more than the other", held(most), is(greaterThan(held(other))));
        assertThat("which holds less than half the connection window", 2 * held(other), is(lessThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);

        assertThat("the stream holding the most was reset", most.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the other was given a fresh period", other.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldMeasureTheOthersAgainstAConnectionWindowItsClientHasEnlarged() throws Exception {
        // the client doubles the connection window before any data is sent, so twice as much must be out to close it
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(300_000);
        clientHandler.sendEveryConnectionGrant();
        clientHandler.grantConnectionWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();
        ClientStream most = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.sendEveryGrant(most);
        exchange();
        assertThat(most.dataBytes, is(2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        ClientStream other = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        clientHandler.grantConnectionWindow(80_000);
        exchange();
        dropConnectionWindowUpdates = true;
        clientHandler.grantWindow(most, 100_000);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the first stream holds less than its client has returned for it at once", held(most), is(lessThan(smallestReturn(most))));
        assertThat("and more than the other", held(most), is(greaterThan(held(other))));
        assertThat("which holds more than half the initial connection window", 2 * held(other), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));
        assertThat("and less than half the enlarged one", held(other), is(lessThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);

        assertThat("the stream holding the most was reset", most.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the other was given a fresh period", other.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStreamPassedOverOnePeriodLaterWhateverWindowItsClientSendsForIt() throws Exception {
        ClientStream[] streams = twoStreamsOfAClientThatConsumesNeither(70_000);
        ClientStream passedOver = streams[0];
        ClientStream other = streams[1];
        // the client returns all of the first stream's window but as much as the other holds
        clientHandler.grantWindow(passedOver, held(passedOver) - held(other));
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the two hold the same", held(passedOver), is(held(other)));
        assertThat("the first less than its client has returned for it at once", held(passedOver), is(lessThan(smallestReturn(passedOver))));
        assertThat("the other more than half the connection window", 2 * held(other), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        runChecksUntilStallsCounted(1);
        assertThat("the stream with nothing returned was reset", other.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the other was passed over", passedOver.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        // its client takes none of its data, and sends it window before every check
        int checks = 0;
        while (Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM) < 2) {
            assertThat("the stream passed over was reset in time", checks < CHECKS, is(true));
            runChecks(1, () -> clientHandler.windowUpdate(passedOver, 1));
            checks++;
        }
        assertThat(passedOver.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("one timeout after the reset it was passed over for", checks, is(CHECKS_PER_TIMEOUT));
    }

    @Test
    public void shouldGiveAStreamPassedOverNoSecondFreshPeriodWhenOtherStreamsAreResetInTurn() throws Exception {
        // a client that takes nothing: three streams hold more than half the connection window each, a different amount
        // each, and the first holds less than its client has returned for it at once
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(300_000);
        exchange();
        ClientStream passedOver = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.sendEveryGrant(passedOver);
        clientHandler.sendEveryConnectionGrant();
        exchange();
        ClientStream[] others = new ClientStream[3];
        for (int i = 0; i < others.length; i++) {
            others[i] = clientHandler.request(LARGE_RESPONSE_PATH);
            exchange();
            clientHandler.grantConnectionWindow((i + 2) * 40_000);
            exchange();
        }
        dropConnectionWindowUpdates = true;
        clientHandler.grantWindow(passedOver, passedOver.dataBytes - 10_000);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the first stream holds less than its client has returned for it at once", held(passedOver), is(lessThan(smallestReturn(passedOver))));
        for (int i = 0; i < others.length; i++) {
            assertThat("each other holds more than half the connection window", 2 * held(others[i]), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));
            assertThat("and less than the one opened before it", i == 0 || held(others[i]) < held(others[i - 1]), is(true));
        }

        runChecksUntilStallsCounted(1);
        assertThat("the stream holding the most, of those with nothing returned, was reset alone", others[0].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        // the next of them is picked a period later, and the stream passed over before is not passed over again
        runChecks(CHECKS_PER_TIMEOUT, () -> { });
        assertThat("every stream was reset one timeout after the first", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(4L));
        assertThat(passedOver.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    @Test
    public void shouldResetAStreamWhoseOwnWindowIsClosedThoughItHoldsLessThanItsClientReturnedForIt() throws Exception {
        ClientStream[] streams = twoStreamsOfAClientThatConsumesNeither(70_000);
        ClientStream closed = streams[0];
        ClientStream other = streams[1];
        clientHandler.grantWindow(closed, held(closed) - 10_000);
        exchange();
        // the client then takes back every stream's window, so each waits for its own window and not for the other
        clientHandler.initialStreamWindow(0);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the first stream's own window is closed", serverWindow(closed.frameStream.id()), is(lessThan(0)));
        assertThat("it holds less than its client has returned for it at once", held(closed), is(lessThan(smallestReturn(closed))));
        assertThat("the other holds more than half the connection window", 2 * held(other), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(closed.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(other.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    @Test
    public void shouldNotPassOverAStreamForOneHoldingExactlyHalfOfWhatTheConnectionHasOut() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(300_000);
        exchange();
        ClientStream half = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.sendEveryConnectionGrant();
        exchange();
        assertThat(half.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        // served before the first stream, so the first is sent no more
        ClientStream most = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.sendEveryGrant(most);
        clientHandler.dependOn(half, most);
        exchange();
        // twice the connection window at once: only what was out can be a return, so twice as much is out afterwards
        clientHandler.grantConnectionWindow(2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();
        clientHandler.grantConnectionWindow(60_000);
        exchange();
        dropConnectionWindowUpdates = true;
        clientHandler.grantWindow(most, 110_000);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the first stream was sent a connection window's worth and nothing since", half.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat("the connection has twice that out", most.dataBytes, is(2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE + 60_000));
        assertThat("no window has been returned for the first stream", smallestReturn(half), is(0));
        assertThat("the second holds less than its client has returned for it at once", held(most), is(lessThan(smallestReturn(most))));
        assertThat("and more than the first", held(most), is(greaterThan(held(half))));

        waitUntilStallsCounted(1);

        assertThat("the stream holding the most was reset", most.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the other was given a fresh period", half.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldNotPassOverAStreamHoldingAsMuchAsTheLeastItsClientHasReturnedForItAtOnce() throws Exception {
        ClientStream[] streams = twoStreamsOfAClientThatConsumesNeither(70_000);
        ClientStream most = streams[0];
        ClientStream other = streams[1];
        // a large return, and then one of exactly what the stream is left holding
        int left = 40_000;
        clientHandler.grantWindow(most, held(most) - 2 * left);
        exchange();
        clientHandler.grantWindow(most, left);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("it holds as much as the least its client has returned for it at once", held(most), is(smallestReturn(most)));
        assertThat("and more than the other", held(most), is(greaterThan(held(other))));
        assertThat("which holds more than half the connection window", 2 * held(other), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);

        assertThat("the stream holding the most was reset", most.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the other was given a fresh period", other.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldKeepWhatAStreamsClientHasReturnedForItWhenItThenGrantsItWindowWithNothingUnreturned() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(300_000);
        exchange();
        ClientStream passedOver = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.sendEveryGrant(passedOver);
        clientHandler.sendEveryConnectionGrant();
        exchange();
        // the client returns all the stream was sent, and then grants it a little more, which returns nothing
        clientHandler.grantWindow(passedOver, passedOver.dataBytes);
        exchange();
        clientHandler.grantWindow(passedOver, 5_000);
        exchange();
        assertThat(held(passedOver), is(-5_000));
        clientHandler.grantConnectionWindow(10_000);
        exchange();
        ClientStream other = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        clientHandler.grantConnectionWindow(70_000);
        exchange();
        dropConnectionWindowUpdates = true;
        assertThat(serverWindow(0), is(0));
        int sentSinceItsReturn = passedOver.dataBytes - Http2CodecUtil.DEFAULT_WINDOW_SIZE;
        assertThat("the first stream holds more than the other", sentSinceItsReturn, is(greaterThan(other.dataBytes)));
        assertThat("and more than the grant", sentSinceItsReturn, is(greaterThan(smallestReturn(passedOver))));
        assertThat("the other more than half the connection window", 2 * other.dataBytes, is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));

        waitUntilStallsCounted(1);

        assertThat("the stream with nothing returned was reset", other.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the stream holding less than its client returned for it at once was passed over", passedOver.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetEveryStreamWhenNothingMovesInTheFreshPeriodAfterAStalledStreamIsReset() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH, 33_000);
        ClientStream first = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream second = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        exchange();
        // each sibling holds some connection window its client consumed but has not yet returned, a different amount each
        int firstWindow = serverWindow(first.frameStream.id());
        int secondWindow = serverWindow(second.frameStream.id());
        assertThat(firstWindow < Http2CodecUtil.DEFAULT_WINDOW_SIZE && secondWindow < Http2CodecUtil.DEFAULT_WINDOW_SIZE && firstWindow != secondWindow, is(true));
        dropConnectionWindowUpdates = true;

        waitUntilStallsCounted(1);
        assertThat("the stalled stream was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        // nothing moved in the fresh period, so the sibling holding more is not reset alone to give the other another
        waitUntilStallsCounted(2);
        assertThat("both siblings were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(3L));
        assertThat(first.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(second.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    @Test
    public void shouldLetAStreamCarryOnWhenAStalledStreamHoldsTheConnectionWindowAgainOnceTheConnectionHasBeenIdle() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        waitUntilStallsCounted(1);
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(sibling.endStream, is(true));
        // a check finds no stream active
        waitThrough(TIMEOUT_MILLIS);

        ClientStream secondHolder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream secondSibling = clientHandler.request("/");
        exchange();
        assertThat(secondSibling.dataBytes, is(0));
        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(secondHolder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", secondSibling.resetErrorCode, is(nullValue()));
        assertThat(secondSibling.dataBytes, is(RESPONSE_BYTES));
        assertThat(secondSibling.endStream, is(true));
    }

    @Test
    public void shouldLetAStreamCarryOnWhenANewStalledStreamTakesTheConnectionWindowAsSoonAsItIsReleased() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        ClientStream[] opened = new ClientStream[2];
        holder.onReset = () -> {
            opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
            opened[1] = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        };
        exchange();
        waitUntilStallsCounted(1);
        // the first sibling completed and the connection window closed again before the next check, with new streams only
        assertThat(sibling.endStream, is(true));
        assertThat(serverWindow(0), is(0));
        assertThat(opened[1].endStream, is(false));

        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(opened[0].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its sibling was not reset with it", opened[1].resetErrorCode, is(nullValue()));
        assertThat(opened[1].dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(opened[1].endStream, is(true));
    }

    @Test
    public void shouldLetTheSameStreamCarryOnEachTimeAStalledStreamHoldingTheConnectionWindowIsReset() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        holder.onReset = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        waitUntilStallsCounted(1);
        // the sibling took some of the released window, then the second stalled stream closed the connection window again
        assertThat(sibling.dataBytes, is(greaterThan(0)));
        assertThat(sibling.endStream, is(false));
        assertThat(serverWindow(0), is(0));

        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(opened[0].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling was spared a second time", sibling.resetErrorCode, is(nullValue()));
        assertThat(sibling.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(sibling.endStream, is(true));
    }

    @Test
    public void shouldResetEveryStreamBehindAnUnwritableSocketInTheSameCheckWhenNoConnectionWatcherIsPresent() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE, false);
        ClientStream first = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        move(client, server);
        socketWritable(false);
        ClientStream second = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        // the streams wait for the socket, not for connection window one of them holds
        assertThat(serverWindow(0), is(greaterThan(0)));
        assertThat(serverWindow(first.frameStream.id()), is(greaterThan(0)));
        assertThat(held(first), is(greaterThan(0)));
        assertThat(held(second), is(0));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
    }

    @Test
    public void shouldResetAnOpenWindowedStreamWithAClosedWindowStreamWhenTheConnectionWindowIsOpen() throws Exception {
        // behind an unwritable socket nothing times, the open-windowed stream waits for the socket, not for window
        connect(1_000, false);
        ClientStream closed = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        socketWritable(false);
        ClientStream open = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(closed.frameStream.id()), is(0));
        assertThat(serverWindow(open.frameStream.id()), is(greaterThan(0)));
        assertThat(serverWindow(0), is(greaterThan(0)));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
    }

    @Test
    public void shouldLetAStreamCarryOnAgainOnceDataHasMovedSinceItsFreshPeriod() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        // two stalled streams share the connection window, and the sibling is sent nothing
        ClientStream holder = clientHandler.request(STAGED_RESPONSE_PATH);
        ClientStream secondHolder = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        responder.writeRest.run();
        ClientStream sibling = clientHandler.request("/");
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat(held(holder), is(greaterThan(held(secondHolder))));
        assertThat(held(secondHolder), is(greaterThan(0)));
        dropConnectionWindowUpdates = true;

        waitUntilStallsCounted(1);
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        // in the sibling's fresh period the client takes one more byte, of one of the two streams
        int sentBefore = secondHolder.dataBytes + sibling.dataBytes;
        dropConnectionWindowUpdates = false;
        clientHandler.connectionWindowUpdate(1);
        exchange();
        dropConnectionWindowUpdates = true;
        assertThat(secondHolder.dataBytes + sibling.dataBytes, is(sentBefore + 1));
        assertThat(serverWindow(0), is(0));

        waitUntilStallsCounted(2);

        assertThat("only the second stalled stream was reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(secondHolder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the sibling was given a fresh period again", sibling.resetErrorCode, is(nullValue()));
    }

    @Test
    public void shouldGiveAFreshPeriodToAStreamThatHasNotYetTimedOutWhenTheStalledStreamIsReset() throws Exception {
        // a check that runs a whole timeout after the reset finds the fresh period over, which proves nothing either
        // way: the scenario is then run again, and the test fails if no attempt ran its check on time
        for (int attempt = 1; ; attempt++) {
            closeChannels();
            long countedBefore = Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM);
            connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
            ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
            exchange();
            // a check sees the holder before its sibling opens, so the sibling times out one check later
            runChecks(1);
            ClientStream sibling = clientHandler.request("/");
            exchange();

            // the reset stays undelivered through the next check, so nothing is released meanwhile
            long waitingSince = System.nanoTime();
            while (Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM) < countedBefore + 1) {
                assertThat("a stall was counted in time", millisSince(waitingSince) < DEADLINE_MILLIS, is(true));
                runChecks(1);
            }
            long resetCheckBeganNanos = lastCheckBeganNanos;
            runChecks(1);
            if (millisSince(resetCheckBeganNanos) >= TIMEOUT_MILLIS) {
                assertThat("the check after the reset ran within a timeout of it in one of " + ON_TIME_ATTEMPTS + " attempts", attempt < ON_TIME_ATTEMPTS, is(true));
                continue;
            }
            assertThat("the sibling was not reset at its own timeout", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(countedBefore + 1));

            exchange();
            assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
            assertThat(sibling.resetErrorCode, is(nullValue()));
            assertThat(sibling.dataBytes, is(RESPONSE_BYTES));
            assertThat(sibling.endStream, is(true));
            return;
        }
    }

    @Test
    public void shouldNotResetTheStreamHoldingAClosedConnectionWindowWhileTheSocketIsNotWritable() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream sibling = clientHandler.request(LARGE_RESPONSE_PATH, Integer.MAX_VALUE);
        ClientStream[] opened = new ClientStream[1];
        sibling.onFirstData = () -> opened[0] = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat("the holder's own window is open", serverWindow(opened[0].frameStream.id()), is(greaterThan(0)));
        // nothing moves until the check at which the streams would time out, by when the socket is not writable
        runChecks(2);
        socketWritable(false);

        runChecks(4);

        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
        assertThat(serverConnection.numActiveStreams(), is(2));
    }

    @Test
    public void shouldResetTheStreamHoldingTheMostWhenItTimesOutAfterTheOthers() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        // the larger holder has nothing waiting until the rest of its response is written, a check after the others
        ClientStream larger = clientHandler.request(STAGED_RESPONSE_PATH);
        ClientStream smaller = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        assertThat(serverWindow(0), is(0));
        assertThat(held(larger), is(greaterThan(held(smaller))));
        runChecks(1);
        responder.writeRest.run();

        waitUntilStallsCounted(1);

        assertThat("the streams that timed out first waited for it", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
        assertThat(larger.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(smaller.resetErrorCode, is(nullValue()));
        assertThat(sibling.resetErrorCode, is(nullValue()));
    }

    @Test
    public void shouldNotGiveAStreamAFreshPeriodForTheResetOfAStreamThatWasSentNothing() throws Exception {
        // a stream never sent data holds no connection window, so a client cannot keep a stalled stream going by
        // opening, every timeout period, a stream it grants no window
        connect(0);

        ClientStream granted = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.grantWindow(granted, 2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream ungranted = clientHandler.request("/");
        exchange();
        assertThat("the granted stream took the whole connection window", granted.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));

        waitUntilStallsCounted(1);

        assertThat("both were reset in the same check", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        exchange();
        assertThat(ungranted.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(granted.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    @Test
    public void shouldResetAHolderAtItsOwnTimeoutWhenAStreamSentNothingIsResetTheCheckBefore() throws Exception {
        connect(0);
        // granted no window, so sent nothing; a check sees it before the holder opens, so it times out one check sooner
        ClientStream sentNothing = clientHandler.request("/");
        exchange();
        runChecks(1, () -> { });
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.grantWindow(holder, 2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();
        assertThat("the holder took the whole connection window", holder.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(serverWindow(0), is(0));
        assertThat("its own window is open", serverWindow(holder.frameStream.id()), is(greaterThan(0)));
        assertThat(sentNothing.dataBytes, is(0));

        runChecksUntilStallsCounted(1);
        assertThat("the stream sent nothing was reset alone", sentNothing.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        runChecks(1, () -> { });
        assertThat("the holder was reset at its own timeout, a check later, with no fresh period for that reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
    }

    @Test
    public void shouldSeeTheProgressOfAStreamOnAConnectionHandlerThatPassesNoFrames() throws Exception {
        // the stream's window is spent at once each time, so it never visibly changes
        connectThroughRelayHandler(SMALL_WINDOW);

        ClientStream stream = clientHandler.request("/");
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 4 * TIMEOUT_MILLIS) {
            waitThrough(WriteStallTimeoutHandler.checkIntervalMillis(TIMEOUT_MILLIS));
            clientHandler.consumeReceived(stream);
            exchange();
        }

        assertThat("the stream was not reset", stream.resetErrorCode, is(nullValue()));
        assertThat("the response is still in progress", stream.endStream, is(false));
        assertThat("the client took more than its first window", stream.dataBytes > SMALL_WINDOW, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
    }

    @Test
    public void shouldResetAStalledStreamWhoseClientAlternatesItsInitialWindow() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream stream = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(stream.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));

        // each change moves the stream's send window by a byte, and no data with it
        int[] changes = new int[1];
        runChecks(CHECKS, () -> clientHandler.initialStreamWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE - (++changes[0] % 2)));

        assertThat("the server applied the changes", serverInitialWindow(), is(Http2CodecUtil.DEFAULT_WINDOW_SIZE - (changes[0] % 2)));
        assertThat("the stream was reset", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its client had taken none of what was waiting", stream.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStalledStreamWhoseClientSendsItOneByteWindowUpdatesWhileTheConnectionWindowIsClosed() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream stream = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(stream.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(serverWindow(0), is(0));

        int[] streamWindowSeen = new int[1];
        runChecks(CHECKS, () -> {
            if (serverConnection.stream(stream.frameStream.id()) != null) {
                streamWindowSeen[0] = serverWindow(stream.frameStream.id());
            }
            clientHandler.windowUpdate(stream, 1);
        });

        assertThat("the updates opened the stream's own window", streamWindowSeen[0], is(greaterThan(0)));
        assertThat("the stream was reset", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its client had taken none of what was waiting", stream.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetEveryStreamOfAClientThatTakesNothingWhileItSendsOneByteWindowUpdates() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream sibling = clientHandler.request("/");
        exchange();
        assertThat(serverWindow(0), is(0));
        dropConnectionWindowUpdates = true;

        runChecks(2 * CHECKS, () -> {
            clientHandler.windowUpdate(holder, 1);
            clientHandler.windowUpdate(sibling, 1);
        });

        assertThat("the stream holding the connection window was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the open-windowed stream waiting for it was reset", sibling.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(sibling.dataBytes, is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        long apartMillis = TimeUnit.NANOSECONDS.toMillis(sibling.resetNanos - holder.resetNanos);
        assertThat("the waiting stream was still given its fresh period (" + apartMillis + " ms apart)", apartMillis >= TIMEOUT_MILLIS / 2, is(true));
    }

    @Test
    public void shouldResetAStreamBehindAnUnwatchedUnwritableSocketWhoseClientSendsConnectionWindowUpdates() throws Exception {
        // with no connection watcher to time the socket, a connection window that only rises is no sign of data leaving
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE, false);
        socketWritable(false);
        clientHandler.request("/");
        exchange();

        runChecks(CHECKS, () -> clientHandler.connectionWindowUpdate(1));

        assertThat("the updates raised the connection window", serverWindow(0), is(greaterThan(Http2CodecUtil.DEFAULT_WINDOW_SIZE)));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStalledStreamInsideATunnelWhoseClientAlternatesItsInitialWindow() throws Exception {
        connectThroughRelayHandler(SMALL_WINDOW);
        ClientStream stream = clientHandler.request("/");
        exchange();
        assertThat(stream.dataBytes, is(SMALL_WINDOW));

        int[] changes = new int[1];
        runChecks(CHECKS, () -> clientHandler.initialStreamWindow(SMALL_WINDOW - (++changes[0] % 2)));

        assertThat("the server applied the changes", serverInitialWindow(), is(SMALL_WINDOW - (changes[0] % 2)));
        assertThat("the stream was reset", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its client had taken none of what was waiting", stream.dataBytes, is(SMALL_WINDOW));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAStalledStreamInsideATunnelWhoseClientSendsItOneByteWindowUpdatesWhileTheConnectionWindowIsClosed() throws Exception {
        connectThroughRelayHandler(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream stream = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(stream.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(serverWindow(0), is(0));

        runChecks(CHECKS, () -> clientHandler.windowUpdate(stream, 1));

        assertThat("the stream was reset", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("its client had taken none of what was waiting", stream.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldNotResetAStreamWhoseClientTakesItsDataAWindowAtATime() throws Exception {
        // the window is spent as soon as it is returned, so it reads the same at every check
        connect(SMALL_WINDOW);
        ClientStream stream = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(stream.dataBytes, is(SMALL_WINDOW));

        int[] openWindowsSeen = new int[1];
        runChecks(CHECKS, () -> {
            if (serverConnection.stream(stream.frameStream.id()) != null && serverWindow(stream.frameStream.id()) != 0) {
                openWindowsSeen[0]++;
            }
            clientHandler.consumeReceived(stream);
        });

        assertThat("the stream's window was closed before every check", openWindowsSeen[0], is(0));

        assertThat("the stream was not reset", stream.resetErrorCode, is(nullValue()));
        assertThat("the client took a window before every check", stream.dataBytes, is((CHECKS + 1) * SMALL_WINDOW));
        assertThat(stream.endStream, is(false));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
    }

    @Test
    public void shouldNotResetAStreamWhoseClientTakesOneByteOfItBeforeEveryCheck() throws Exception {
        // no minimum rate is asked of a client: here each rise of its initial window lets one more byte leave
        connect(0);
        ClientStream stream = clientHandler.request("/");
        exchange();
        assertThat(stream.dataBytes, is(0));

        int[] initialWindow = new int[1];
        runChecks(CHECKS, () -> clientHandler.initialStreamWindow(++initialWindow[0]));

        assertThat("the stream was not reset", stream.resetErrorCode, is(nullValue()));
        assertThat("the client took a byte before every check", stream.dataBytes, is(CHECKS));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
    }

    @Test
    public void shouldNotResetAStreamWhoseWindowItsClientReopensWhileTheSocketIsNotWritable() throws Exception {
        connect(SMALL_WINDOW);
        ClientStream stream = clientHandler.request("/");
        exchange();
        // a check sees the stream while its window is closed
        runChecks(1, () -> { });
        assertThat(serverWindow(stream.frameStream.id()), is(0));
        socketWritable(false);
        // the stream now waits only for the socket, which the connection watcher times
        clientHandler.consumeReceived(stream);
        exchange();
        assertThat(serverWindow(stream.frameStream.id()), is(SMALL_WINDOW));

        runChecks(CHECKS, () -> { });

        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
        socketWritable(true);
        exchange();
        assertThat("the stream was not reset", stream.resetErrorCode, is(nullValue()));
        assertThat("more of the response was sent once the socket was writable", stream.dataBytes, is(2 * SMALL_WINDOW));
    }

    @Test
    public void shouldResetAStalledStreamOneTimeoutAfterItIsFirstSeenWithDataAlreadySent() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream stream = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(stream.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));

        // what was sent before the first check is not progress at the second
        runChecks(1 + CHECKS_PER_TIMEOUT, () -> { });

        assertThat("the stream was reset", stream.resetErrorCode, is(Http2Error.CANCEL.code()));
    }

    @Test
    public void shouldCloseTheConnectionWhenItsClientOverflowsAnEarlierStreamsWindowWithItsInitialWindow() throws Exception {
        // RFC 9113 6.9.2: a connection error, so no stream is left behind with a window the rise skipped
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        dropConnectionWindowUpdates = true;
        clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream overflowing = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream stalled = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        clientHandler.windowUpdate(overflowing, Integer.MAX_VALUE - Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();

        clientHandler.initialStreamWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE + 1);
        Http2Exception connectionError = assertThrows(Http2Exception.class, this::exchange);
        exchange();

        assertThat(connectionError.error(), is(Http2Error.FLOW_CONTROL_ERROR));
        assertThat("the overflow closed the connection", server.isOpen(), is(false));
        assertThat(clientHandler.goAwayErrorCode, is(Http2Error.FLOW_CONTROL_ERROR.code()));
        assertThat("its client had taken none of it", stalled.dataBytes, is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
    }

    @Test
    public void shouldCloseATunnelsConnectionWhenItsClientOverflowsAnEarlierStreamsWindowWithItsInitialWindow() throws Exception {
        connectThroughRelayHandler(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        dropConnectionWindowUpdates = true;
        clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream overflowing = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream stalled = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        clientHandler.windowUpdate(overflowing, Integer.MAX_VALUE - Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();

        clientHandler.initialStreamWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE + 1);
        exchange();

        assertThat("the overflow closed the connection", server.isOpen(), is(false));
        assertThat(clientHandler.goAwayErrorCode, is(Http2Error.FLOW_CONTROL_ERROR.code()));
        assertThat("the overflowing stream was not reset on its own", overflowing.resetErrorCode, is(nullValue()));
        assertThat("its client had taken none of it", stalled.dataBytes, is(0));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));
    }

    @Test
    public void shouldResetTheStalledStreamsBeforeAStreamWhoseClientReturnedItsWindowAfterTheConnectionWindowClosed() throws Exception {
        connect(20_000);
        ClientStream consumed = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(consumed.frameStream.id()), is(0));
        // its period starts a check before the stalled streams'
        runChecks(1, () -> { });
        ClientStream[] stalled = new ClientStream[3];
        for (int i = 0; i < stalled.length; i++) {
            stalled[i] = clientHandler.request(LARGE_RESPONSE_PATH);
            exchange();
        }
        assertThat(serverWindow(0), is(0));
        runChecks(1, () -> { });
        // the client returns the stream's window; Netty's codec returns the connection's only once half of it is consumed
        clientHandler.consumeReceived(consumed);
        exchange();
        assertThat(held(consumed), is(0));
        assertThat(serverWindow(0), is(0));
        consumed.consumeLimit = Integer.MAX_VALUE;

        runChecksUntilStallsCounted(1);

        assertThat("the stream waiting for the connection window was not reset", consumed.resetErrorCode, is(nullValue()));
        assertThat("the stalled streams whose own windows had closed were reset", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(2L));
        assertThat(stalled[0].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(stalled[1].resetErrorCode, is(Http2Error.CANCEL.code()));

        runChecks(CHECKS, () -> { });

        assertThat(consumed.resetErrorCode, is(nullValue()));
        assertThat("it got the connection window their resets released", consumed.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(consumed.endStream, is(true));
        assertThat("the third stalled stream was reset in its turn", stalled[2].resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(3L));
    }

    @Test
    public void shouldResetTheStalledStreamBeforeAStreamWhoseClientReturnsTheConnectionWindowLater() throws Exception {
        connect(40_000);
        ClientStream consumed = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        runChecks(1, () -> { });
        ClientStream stalled = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        runChecks(1, () -> { });
        // the client returns the consumed stream's window at once, and its connection update arrives later
        dropConnectionWindowUpdates = true;
        clientHandler.consumeReceived(consumed);
        exchange();
        dropConnectionWindowUpdates = false;
        assertThat(droppedConnectionWindowUpdates, is(1));
        assertThat(held(consumed), is(0));
        assertThat(held(stalled), is(greaterThan(0)));
        assertThat(serverWindow(0), is(0));
        consumed.consumeLimit = Integer.MAX_VALUE;

        runChecksUntilStallsCounted(1);

        assertThat("the stream waiting for the connection window was not reset", consumed.resetErrorCode, is(nullValue()));
        assertThat("the stalled stream holding it was", stalled.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));

        clientHandler.connectionWindowUpdate(40_000);
        runChecks(CHECKS, () -> { });

        assertThat(consumed.resetErrorCode, is(nullValue()));
        assertThat(consumed.dataBytes, is(LARGE_RESPONSE_BYTES));
        assertThat(consumed.endStream, is(true));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldNotResetAStreamBeforeAHolderOfTheConnectionWindowThatIsFirstSeenAsItTimesOut() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        // the holder has been sent everything written for it so far, so nothing of it is waiting yet
        ClientStream holder = clientHandler.request(STAGED_RESPONSE_PATH);
        ClientStream waiting = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        int waitingGot = waiting.dataBytes;
        runChecks(CHECKS_PER_TIMEOUT, () -> { });
        responder.writeRest.run();

        runChecks(1, () -> { });

        assertThat("the waiting stream was not reset at its timeout", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));

        runChecks(CHECKS_PER_TIMEOUT, () -> { });

        assertThat("the holder was reset at its own", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(waiting.resetErrorCode, is(nullValue()));
        assertThat("the waiting stream got the connection window the reset released", waiting.dataBytes, is(greaterThan(waitingGot)));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldResetAWaitingStreamThatHoldsTheConnectionWindowOnceTheStreamItWaitedForHasTimedOut() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        dropConnectionWindowUpdates = true;
        // the client consumes all the first stream was sent, and none of the second, which holds the connection window
        ClientStream consumed = clientHandler.request(STAGED_RESPONSE_PATH);
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        clientHandler.consumeReceived(consumed);
        exchange();
        assertThat(held(consumed), is(0));
        assertThat(held(holder), is(greaterThan(0)));
        assertThat(serverWindow(0), is(0));
        runChecks(CHECKS_PER_TIMEOUT, () -> { });
        responder.writeRest.run();

        // the holder waits while the consumed stream, sent data and first seen waiting now, has not timed out
        runChecks(1, () -> { });
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(0L));

        // its period was not restarted by the wait, so it is reset as soon as both have timed out
        runChecks(CHECKS_PER_TIMEOUT, () -> { });
        assertThat("the stream holding the connection window was reset", holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(consumed.resetErrorCode, is(nullValue()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(1L));
    }

    @Test
    public void shouldLetDataQueuedInSeveralWritesLeaveAsOneRunOfFrames() throws Exception {
        // the flow controller merges a stream's queued writes, so the later write fills the frame the earlier left short
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        socketWritable(false);
        ClientStream stream = clientHandler.request(STAGED_RESPONSE_PATH);
        exchange();
        responder.writeRest.run();

        socketWritable(true);
        exchange();

        assertThat(stream.endStream, is(true));
        int frame = Http2CodecUtil.DEFAULT_MAX_FRAME_SIZE;
        assertThat(stream.dataFrames, contains(frame, frame, frame, STAGED_FIRST_BYTES + RESPONSE_BYTES - 3 * frame));
    }

    @Test
    public void shouldResetAStalledStreamWhoseClientOpensAStreamBeforeEveryCheck() throws Exception {
        // a stream sent nothing holds no connection window, so the stalled stream does not wait for it to time out
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        dropConnectionWindowUpdates = true;
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream stalled = clientHandler.request("/");
        exchange();
        assertThat(serverWindow(0), is(0));

        ClientStream[] last = new ClientStream[1];
        runChecks(2 * CHECKS, () -> last[0] = clientHandler.request("/"));

        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the stalled stream was reset", stalled.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(stalled.dataBytes, is(0));
        assertThat("the client was still opening streams", last[0].resetErrorCode, is(nullValue()));
    }

    @Test
    public void shouldNotMakeAStreamWaitForAnotherToTimeOutWhileTheConnectionWindowIsOpen() throws Exception {
        // with no connection watcher both streams wait for the socket, not for connection window either holds
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE, false);
        clientHandler.request(STAGED_RESPONSE_PATH);
        exchange();
        socketWritable(false);
        clientHandler.request("/");
        exchange();
        runChecks(1, () -> { });
        // the first stream, which has been sent data, has data waiting only from the next check
        responder.writeRest.run();

        runChecks(CHECKS_PER_TIMEOUT, () -> { });

        assertThat(serverWindow(0), is(greaterThan(0)));
        assertThat("the second stream was reset at its own timeout", Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(greaterThan(0L)));
    }

    @Test
    public void shouldCloseAStreamOnceItsResponseHasBeenWrittenInFull() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream stream = clientHandler.request("/");
        exchange();

        assertThat(stream.dataBytes, is(RESPONSE_BYTES));
        assertThat(stream.endStream, is(true));
        assertThat("the stream closed with its last frame", serverConnection.numActiveStreams(), is(0));

        closeChannels();
        connectThroughRelayHandler(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        ClientStream tunnelled = clientHandler.request("/");
        exchange();

        assertThat(tunnelled.dataBytes, is(RESPONSE_BYTES));
        assertThat(tunnelled.endStream, is(true));
        assertThat("the tunnelled stream closed with its last frame", serverConnection.numActiveStreams(), is(0));
    }

    @Test
    public void shouldReleaseTheQueuedResponseOfAStreamItResetsAndFailItsWrite() throws Exception {
        connect(0);
        ClientStream stream = clientHandler.request("/");
        exchange();
        assertThat("the body is waiting", responder.bodies.get(0).refCnt(), is(1));
        assertThat(responder.bodyWrites.get(0).isDone(), is(false));

        waitUntilStallsCounted(1);

        assertThat(stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the queued body was released", responder.bodies.get(0).refCnt(), is(0));
        assertThat("its write failed", responder.bodyWrites.get(0).isDone() && !responder.bodyWrites.get(0).isSuccess(), is(true));
    }

    @Test
    public void shouldReleaseTheQueuedResponseOfATunnelledStreamItResetsAndFailItsWrite() throws Exception {
        connectThroughRelayHandler(0);
        ClientStream stream = clientHandler.request("/");
        exchange();
        assertThat("the body is waiting", httpResponder.bodies.get(0).refCnt(), is(1));
        assertThat(httpResponder.bodyWrites.get(0).isDone(), is(false));

        waitUntilStallsCounted(1);

        assertThat(stream.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the queued body was released", httpResponder.bodies.get(0).refCnt(), is(0));
        assertThat("its write failed", httpResponder.bodyWrites.get(0).isDone() && !httpResponder.bodyWrites.get(0).isSuccess(), is(true));
    }

    @Test
    public void shouldNotCountHeadersOrAnEmptyDataFrameAsDataWrittenForAStream() throws Exception {
        // an empty DATA write and the trailers queued behind it leave the flow controller with the connection window
        // closed; they are no sign that the client is taking data, so the stream waiting for that window is still reset
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        dropConnectionWindowUpdates = true;
        ClientStream holder = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream stalled = clientHandler.request("/");
        exchange();
        assertThat(serverWindow(0), is(0));

        ClientStream[] last = new ClientStream[1];
        runChecks(CHECKS, () -> last[0] = clientHandler.request(TRAILERS_ONLY_PATH));

        assertThat("the client got each trailers-only response", last[0].endStream, is(true));
        assertThat(last[0].dataBytes, is(0));
        assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat("the stalled stream was reset", stalled.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(stalled.dataBytes, is(0));
    }

    @Test
    public void shouldTellLaterHandlersThatAStreamIsNotWritableWhenTheSocketIsNot() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        // an open stream with window left and nothing waiting
        clientHandler.request(STAGED_RESPONSE_PATH);
        exchange();
        int toldBefore = responder.streamWritabilityChanges;

        socketWritable(false);

        assertThat(responder.streamWritabilityChanges, is(toldBefore + 1));
    }

    @Test
    public void shouldServeStreamsInTheOrderTheClientsPriorityAsksFor() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        socketWritable(false);
        ClientStream dependent = clientHandler.request(LARGE_RESPONSE_PATH);
        ClientStream parent = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.dependOn(dependent, parent);
        exchange();

        socketWritable(true);
        exchange();

        assertThat("the stream depended on got the whole connection window", parent.dataBytes, is(Http2CodecUtil.DEFAULT_WINDOW_SIZE));
        assertThat(dependent.dataBytes, is(0));
    }

    @Test
    public void shouldCloseTheConnectionWhenTheWriteOfAQueuedResponseFails() throws Exception {
        // the queued frame reports a failed write to the connection through the flow controller's context
        connect(0);
        ClientStream stream = clientHandler.request("/");
        exchange();
        assertThat("the body is waiting", responder.bodyWrites.get(0).isDone(), is(false));
        assertThat(server.isOpen(), is(true));

        socket.failWrites = true;
        clientHandler.grantWindow(stream, RESPONSE_BYTES);
        exchange();
        socket.failWrites = false;
        exchange();

        assertThat("the write failed", responder.bodyWrites.get(0).cause() instanceof IOException, is(true));
        assertThat("the failure closed the connection", server.isOpen(), is(false));
    }

    @Test
    public void shouldWrapEveryMethodOfTheFlowControllerAndOfItsQueuedFrames() {
        // a method Netty adds with a default body would otherwise bypass the wrapper unnoticed
        assertDeclaresEveryMethodOf(WrittenBytesFlowController.class, Http2RemoteFlowController.class);
        Class<?> queuedFrame = null;
        for (Class<?> nested : WrittenBytesFlowController.class.getDeclaredClasses()) {
            if (Http2RemoteFlowController.FlowControlled.class.isAssignableFrom(nested)) {
                queuedFrame = nested;
            }
        }
        assertThat("the wrapper has a queued-frame wrapper", queuedFrame != null, is(true));
        assertDeclaresEveryMethodOf(queuedFrame, Http2RemoteFlowController.FlowControlled.class);
    }

    private static void assertDeclaresEveryMethodOf(Class<?> wrapper, Class<?> wrapped) {
        for (Method method : wrapped.getMethods()) {
            if (Modifier.isStatic(method.getModifiers())) {
                continue;
            }
            try {
                wrapper.getDeclaredMethod(method.getName(), method.getParameterTypes());
            } catch (NoSuchMethodException e) {
                throw new AssertionError(wrapper.getSimpleName() + " does not wrap " + method, e);
            }
        }
    }

    @Test
    public void shouldEndTheWaitForSeveralHoldersOneTimeoutAfterTheLastHasDataWaiting() throws Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        // a doubled connection window takes the first part of three staged responses and less of a fourth stream's
        clientHandler.grantConnectionWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();
        dropConnectionWindowUpdates = true;
        ClientStream[] holders = new ClientStream[3];
        for (int i = 0; i < holders.length; i++) {
            holders[i] = clientHandler.request(STAGED_RESPONSE_PATH);
        }
        ClientStream waiting = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        assertThat(serverWindow(0), is(0));
        for (ClientStream holder : holders) {
            assertThat(held(holder), is(STAGED_FIRST_BYTES));
        }
        assertThat(held(waiting), is(2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE - holders.length * STAGED_FIRST_BYTES));

        // each holder has more waiting from the check at which the one before it times out
        runChecks(1, () -> { });
        for (int i = 0; i < holders.length; i++) {
            if (i > 0) {
                runChecks(CHECKS_PER_TIMEOUT - 1, () -> { });
            }
            responder.writeRests.get(i).run();
            runChecks(1, () -> { });
            assertThat("the waiting stream is not reset at the check that first sees holder " + i, waiting.resetErrorCode, is(nullValue()));
        }

        // the last holder's own timeout, then the one fresh period its reset gives the waiting stream
        runChecks(2 * CHECKS_PER_TIMEOUT, () -> { });

        for (ClientStream holder : holders) {
            assertThat(holder.resetErrorCode, is(Http2Error.CANCEL.code()));
        }
        assertThat("the waiting stream was reset in its turn", waiting.resetErrorCode, is(Http2Error.CANCEL.code()));
        assertThat(Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM), is(4L));
    }

    private void connect(int clientInitialWindowSize) {
        connect(clientInitialWindowSize, true);
    }

    /**
     * Two streams of a client that consumes neither and sends only the window the test tells it to, with stream
     * windows that stay open: the first is sent two connection windows' worth before the second opens, and the
     * connection window granted after that is shared between them.
     */
    private ClientStream[] twoStreamsOfAClientThatConsumesNeither(int grantedAfterTheSecondOpens) throws Http2Exception {
        connect(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        clientHandler.initialStreamWindow(300_000);
        exchange();
        ClientStream first = clientHandler.request(LARGE_RESPONSE_PATH);
        clientHandler.sendEveryGrant(first);
        clientHandler.sendEveryConnectionGrant();
        exchange();
        clientHandler.grantConnectionWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
        exchange();
        ClientStream second = clientHandler.request(LARGE_RESPONSE_PATH);
        exchange();
        clientHandler.grantConnectionWindow(grantedAfterTheSecondOpens);
        exchange();
        dropConnectionWindowUpdates = true;
        return new ClientStream[]{first, second};
    }

    private void grantWindow(ClientStream stream, int bytes) {
        try {
            clientHandler.grantWindow(stream, bytes);
        } catch (Http2Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private void connect(int clientInitialWindowSize, boolean connectionWatcher) {
        Http2FrameCodec serverCodec = Http2FrameCodecBuilder.forServer().build();
        serverConnection = serverCodec.connection();
        socket = new Socket();
        responder = new Responder();
        server = new EmbeddedChannel(socket, serverCodec, new Http2StreamWriteStallHandler(TIMEOUT_MILLIS, null), responder);
        if (connectionWatcher) {
            // its timer never arms here, since the embedded channel's outbound buffer is always emptied by a flush
            server.pipeline().addFirst(new WriteStallTimeoutHandler(60_000, null));
        }
        connectClient(clientInitialWindowSize);
    }

    // the CONNECT relay's client-facing leg: frames become HTTP messages inside the connection handler, which passes
    // none down the pipeline
    private void connectThroughRelayHandler(int clientInitialWindowSize) {
        serverConnection = new DefaultHttp2Connection(true);
        socket = new Socket();
        server = new EmbeddedChannel(
            socket,
            new HttpToHttp2ConnectionHandlerBuilder()
                .connection(serverConnection)
                .frameListener(new InboundHttp2ToHttpAdapterBuilder(serverConnection).maxContentLength(1024 * 1024).build())
                .build(),
            new Http2StreamWriteStallHandler(TIMEOUT_MILLIS, null),
            httpResponder = new HttpResponder()
        );
        connectClient(clientInitialWindowSize);
    }

    private void connectClient(int clientInitialWindowSize) {
        smallestReturns.clear();
        clientCodec = Http2FrameCodecBuilder.forClient().initialSettings(Http2Settings.defaultSettings().initialWindowSize(clientInitialWindowSize)).build();
        clientHandler = new Client(clientCodec);
        client = new EmbeddedChannel(clientCodec, clientHandler);
        client.flush();
        exchange();
    }

    /**
     * Runs that many of the watcher's checks, exactly one check interval apart on a frozen clock, with the client
     * sending its frames before each: every check then sees exactly one round of them.
     */
    private void runChecks(int checks, Runnable clientSendsBeforeEach) throws InterruptedException {
        server.freezeTime();
        for (int ran = 0; ran < checks; ran++) {
            clientSendsBeforeEach.run();
            exchange();
            TimeUnit.MILLISECONDS.sleep(CHECK_INTERVAL_MILLIS);
            server.advanceTimeBy(CHECK_INTERVAL_MILLIS, TimeUnit.MILLISECONDS);
            server.runScheduledPendingTasks();
        }
        server.unfreezeTime();
        exchange();
    }

    private void socketWritable(boolean writable) {
        socket.hold(!writable);
        server.unsafe().outboundBuffer().setUserDefinedWritability(SOCKET_WRITABILITY, writable);
        server.runPendingTasks();
        assertThat(server.isWritable(), is(writable));
    }

    private void waitThrough(long millis) throws InterruptedException {
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < millis) {
            TimeUnit.MILLISECONDS.sleep(WriteStallTimeoutHandler.checkIntervalMillis(TIMEOUT_MILLIS));
            server.runScheduledPendingTasks();
            exchange();
        }
    }

    // runs that many of the watcher's checks, delivering nothing between the client and the server
    private void runChecks(int checks) throws InterruptedException {
        long started = System.nanoTime();
        long untilNextCheck = server.runScheduledPendingTasks();
        for (int ran = 0; ran < checks; ) {
            assertThat("the watcher's checks ran in time", millisSince(started) < checks * DEADLINE_MILLIS, is(true));
            TimeUnit.MILLISECONDS.sleep(5);
            long began = System.nanoTime();
            long untilNext = server.runScheduledPendingTasks();
            // a check that has run schedules the next one a whole interval away
            if (untilNext > untilNextCheck) {
                ran++;
                lastCheckBeganNanos = began;
            }
            untilNextCheck = untilNext;
        }
    }

    // with the server's clock frozen, runs the watcher's checks one interval apart until that many stalls are counted
    private void runChecksUntilStallsCounted(long count) throws InterruptedException {
        for (int checks = 0; Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM) < count; checks++) {
            assertThat("a stall was counted in time", checks < 2 * CHECKS, is(true));
            runChecks(1, () -> { });
        }
    }

    private int serverWindow(int streamId) {
        return serverConnection.remote().flowController().windowSize(serverConnection.stream(streamId));
    }

    private int serverInitialWindow() {
        return serverConnection.remote().flowController().initialWindowSize();
    }

    // the stream's data its client has not returned window for
    private int held(ClientStream stream) {
        return serverInitialWindow() - serverWindow(stream.frameStream.id());
    }

    // the least window the client has sent for the stream in one WINDOW_UPDATE, 0 if it has sent none
    private int smallestReturn(ClientStream stream) {
        return smallestReturns.getOrDefault(stream.frameStream.id(), 0);
    }

    private void waitUntilStallsCounted(long count) throws InterruptedException {
        long started = System.nanoTime();
        while (Metrics.getResponseWriteStallsCount(Metrics.ResponseWriteStall.HTTP2_STREAM) < count) {
            assertThat("a stall was counted in time", millisSince(started) < DEADLINE_MILLIS, is(true));
            TimeUnit.MILLISECONDS.sleep(WriteStallTimeoutHandler.checkIntervalMillis(TIMEOUT_MILLIS) / 4);
            server.runScheduledPendingTasks();
        }
        exchange();
    }

    private void exchange() {
        boolean moved = true;
        for (int rounds = 0; moved; rounds++) {
            assertThat("the client and the server settled", rounds < 100_000, is(true));
            moved = move(client, server) | move(server, client);
        }
    }

    private static long millisSince(long nanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - nanos);
    }

    private boolean move(EmbeddedChannel from, EmbeddedChannel to) {
        boolean moved = false;
        for (Object msg; (msg = from.readOutbound()) != null; ) {
            if (from == client && dropConnectionWindowUpdates && isConnectionWindowUpdate(msg)) {
                droppedConnectionWindowUpdates++;
                ReferenceCountUtil.release(msg);
                continue;
            }
            if (from == client && isWindowUpdate(msg) && !isConnectionWindowUpdate(msg)) {
                ByteBuf frame = (ByteBuf) msg;
                smallestReturns.merge(frame.getInt(frame.readerIndex() + 5), frame.getInt(frame.readerIndex() + Http2CodecUtil.FRAME_HEADER_LENGTH), Math::min);
            }
            to.writeInbound(msg);
            moved = true;
        }
        to.runPendingTasks();
        return moved;
    }

    // Netty's frame writer writes each WINDOW_UPDATE frame in a buffer of its own
    private static boolean isWindowUpdate(Object msg) {
        if (!(msg instanceof ByteBuf) || ((ByteBuf) msg).readableBytes() != Http2CodecUtil.FRAME_HEADER_LENGTH + 4) {
            return false;
        }
        ByteBuf frame = (ByteBuf) msg;
        return frame.getByte(frame.readerIndex() + 3) == Http2FrameTypes.WINDOW_UPDATE;
    }

    private static boolean isConnectionWindowUpdate(Object msg) {
        return isWindowUpdate(msg) && ((ByteBuf) msg).getInt(((ByteBuf) msg).readerIndex() + 5) == Http2CodecUtil.CONNECTION_STREAM_ID;
    }

    /**
     * Stands for the connection's socket: while it is not writable it takes nothing, so writes stay incomplete, as they
     * do behind a client that is not reading.
     */
    private static final class Socket extends ChannelOutboundHandlerAdapter {
        private final Queue<Object> heldMessages = new ArrayDeque<>();
        private final Queue<ChannelPromise> heldPromises = new ArrayDeque<>();
        private ChannelHandlerContext ctx;
        private boolean holding;
        private boolean failWrites;

        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            if (failWrites) {
                ReferenceCountUtil.release(msg);
                promise.setFailure(new IOException("the socket failed the write"));
            } else if (holding) {
                heldMessages.add(msg);
                heldPromises.add(promise);
            } else {
                ctx.write(msg, promise);
            }
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            if (!holding) {
                ctx.flush();
            }
        }

        void discardHeld() {
            holding = false;
            while (!heldMessages.isEmpty()) {
                ReferenceCountUtil.release(heldMessages.poll());
                heldPromises.poll().tryFailure(new ClosedChannelException());
            }
        }

        void hold(boolean hold) {
            holding = hold;
            if (!hold) {
                while (!heldMessages.isEmpty()) {
                    ctx.write(heldMessages.poll(), heldPromises.poll());
                }
                ctx.flush();
            }
        }
    }

    /**
     * Answers every request with a body the size of a few frames, or of a few windows for {@link #LARGE_RESPONSE_PATH}.
     * For {@link #STAGED_RESPONSE_PATH} it writes the first part, and the rest only when {@code writeRest} is run; for
     * {@link #PARTS_RESPONSE_PATH} the first of three parts, and each of the others when its {@code writeRests} entry
     * is run. For {@link #TRAILERS_ONLY_PATH} it writes an empty DATA frame and trailers.
     */
    private static final class Responder extends ChannelInboundHandlerAdapter {
        private final List<Runnable> writeRests = new ArrayList<>();
        private final List<ByteBuf> bodies = new ArrayList<>();
        private final List<ChannelFuture> bodyWrites = new ArrayList<>();
        private Runnable writeRest;
        private int streamWritabilityChanges;

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            if (evt instanceof Http2FrameStreamEvent && ((Http2FrameStreamEvent) evt).type() == Http2FrameStreamEvent.Type.Writability) {
                streamWritabilityChanges++;
            }
            ctx.fireUserEventTriggered(evt);
        }

        // with no multiplex handler after the codec, a stream error stops here and its stream stays open
        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            if (!(cause instanceof Http2FrameStreamException)) {
                ctx.fireExceptionCaught(cause);
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame request = (Http2HeadersFrame) msg;
                    if (STAGED_RESPONSE_PATH.contentEquals(request.headers().path())) {
                        ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")).stream(request.stream()));
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[STAGED_FIRST_BYTES]), false).stream(request.stream()));
                        writeRest = () -> ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[RESPONSE_BYTES]), true).stream(request.stream()));
                        writeRests.add(writeRest);
                        return;
                    }
                    if (PARTS_RESPONSE_PATH.contentEquals(request.headers().path())) {
                        ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")).stream(request.stream()));
                        ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[PARTS_BYTES[0]]), false).stream(request.stream()));
                        writeRests.add(() -> ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[PARTS_BYTES[1]]), false).stream(request.stream())));
                        writeRests.add(() -> ctx.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(new byte[PARTS_BYTES[2]]), true).stream(request.stream())));
                        return;
                    }
                    if (TRAILERS_ONLY_PATH.contentEquals(request.headers().path())) {
                        ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")).stream(request.stream()));
                        ctx.write(new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, false).stream(request.stream()));
                        ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().set("grpc-status", "0"), true).stream(request.stream()));
                        return;
                    }
                    int bytes = LARGE_RESPONSE_PATH.contentEquals(request.headers().path()) ? LARGE_RESPONSE_BYTES : RESPONSE_BYTES;
                    ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().status("200")).stream(request.stream()));
                    ByteBuf body = Unpooled.wrappedBuffer(new byte[bytes]);
                    bodies.add(body);
                    bodyWrites.add(ctx.write(new DefaultHttp2DataFrame(body, true).stream(request.stream())));
                    ctx.flush();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    /**
     * Answers every request, as HTTP messages, with a body the size of a few frames, or of a few windows for
     * {@link #LARGE_RESPONSE_PATH}.
     */
    private static final class HttpResponder extends ChannelInboundHandlerAdapter {
        private final List<ByteBuf> bodies = new ArrayList<>();
        private final List<ChannelFuture> bodyWrites = new ArrayList<>();

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof FullHttpRequest) {
                    int bytes = LARGE_RESPONSE_PATH.equals(((FullHttpRequest) msg).uri()) ? LARGE_RESPONSE_BYTES : RESPONSE_BYTES;
                    DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(new byte[bytes]));
                    String streamId = HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text().toString();
                    response.headers().set(streamId, ((FullHttpRequest) msg).headers().get(streamId));
                    bodies.add(response.content());
                    bodyWrites.add(ctx.writeAndFlush(response));
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }

    private static final class ClientStream {
        private Http2FrameStream frameStream;
        private int consumeLimit;
        private int consumed;
        private Runnable onFirstData;
        private int reachingDataBytes;
        private Runnable onReachingDataBytes;
        private Runnable onReset;
        private long resetNanos;
        private Long resetErrorCode;
        private final List<Integer> dataFrames = new ArrayList<>();
        private int dataBytes;
        private boolean endStream;
    }

    /**
     * Consumes, and so returns window for, only up to each stream's limit (by default nothing), besides what Netty's codec
     * returns for a stream when it closes.
     */
    private static final class Client extends Http2ChannelDuplexHandler {
        private final Http2FrameCodec codec;
        private final Map<Http2FrameStream, ClientStream> streams = new HashMap<>();
        private ChannelHandlerContext ctx;
        private Long goAwayErrorCode;

        private Client(Http2FrameCodec codec) {
            this.codec = codec;
        }

        @Override
        protected void handlerAdded0(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        ClientStream request(String path) {
            return request(path, 0);
        }

        ClientStream request(String path, int consumeLimit) {
            ClientStream stream = new ClientStream();
            stream.consumeLimit = consumeLimit;
            stream.frameStream = newStream();
            streams.put(stream.frameStream, stream);
            ctx.writeAndFlush(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers().method("GET").scheme("http").authority("localhost").path(path), true).stream(stream.frameStream));
            return stream;
        }

        // SETTINGS_INITIAL_WINDOW_SIZE, which leaves the connection window as it is
        void initialStreamWindow(int bytes) {
            ctx.writeAndFlush(new DefaultHttp2SettingsFrame(new Http2Settings().initialWindowSize(bytes)));
        }

        // written past the codec's flow controller, which would hold so small an update back
        void windowUpdate(ClientStream stream, int bytes) {
            writeWindowUpdate(stream.frameStream.id(), bytes);
        }

        void connectionWindowUpdate(int bytes) {
            writeWindowUpdate(Http2CodecUtil.CONNECTION_STREAM_ID, bytes);
        }

        private void writeWindowUpdate(int streamId, int bytes) {
            ChannelHandlerContext codecCtx = ctx.pipeline().context(codec);
            codec.encoder().frameWriter().writeWindowUpdate(codecCtx, streamId, bytes, codecCtx.newPromise());
            codecCtx.flush();
        }

        // PRIORITY: the first stream is to be served only once the second has nothing to send
        void dependOn(ClientStream stream, ClientStream parent) {
            ChannelHandlerContext codecCtx = ctx.pipeline().context(codec);
            codec.encoder().frameWriter().writePriority(codecCtx, stream.frameStream.id(), parent.frameStream.id(), Http2CodecUtil.DEFAULT_PRIORITY_WEIGHT, true, codecCtx.newPromise());
            codecCtx.flush();
        }

        void grantWindow(ClientStream stream, int bytes) throws Http2Exception {
            codec.connection().local().flowController().incrementWindowSize(codec.connection().stream(stream.frameStream.id()), bytes);
            ctx.flush();
        }

        // the stream's window is returned once seven tenths of it are consumed, where Netty's codec returns it at half
        void returnWindowLate(ClientStream stream) throws Http2Exception {
            ((DefaultHttp2LocalFlowController) codec.connection().local().flowController()).windowUpdateRatio(codec.connection().stream(stream.frameStream.id()), 0.3f);
        }

        // Netty's codec holds back a grant that leaves the window less than half used; at this ratio it sends them all
        void sendEveryGrant(ClientStream stream) throws Http2Exception {
            ((DefaultHttp2LocalFlowController) codec.connection().local().flowController()).windowUpdateRatio(codec.connection().stream(stream.frameStream.id()), 0.99f);
        }

        void sendEveryConnectionGrant() throws Http2Exception {
            ((DefaultHttp2LocalFlowController) codec.connection().local().flowController()).windowUpdateRatio(codec.connection().connectionStream(), 0.99f);
        }

        void grantConnectionWindow(int bytes) throws Http2Exception {
            codec.connection().local().flowController().incrementWindowSize(codec.connection().connectionStream(), bytes);
            ctx.flush();
        }

        // returns window for everything the stream has received and not yet returned window for
        void consumeReceived(ClientStream stream) {
            int unconsumed = stream.dataBytes - stream.consumed;
            if (unconsumed > 0) {
                stream.consumed += unconsumed;
                ctx.writeAndFlush(new DefaultHttp2WindowUpdateFrame(unconsumed).stream(stream.frameStream));
            }
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2GoAwayFrame) {
                    goAwayErrorCode = ((Http2GoAwayFrame) msg).errorCode();
                }
                ClientStream stream = msg instanceof Http2StreamFrame ? streams.get(((Http2StreamFrame) msg).stream()) : null;
                if (stream == null) {
                    return;
                }
                if (msg instanceof Http2DataFrame) {
                    Http2DataFrame data = (Http2DataFrame) msg;
                    stream.dataBytes += data.content().readableBytes();
                    stream.dataFrames.add(data.content().readableBytes());
                    stream.endStream |= data.isEndStream();
                    if (stream.onFirstData != null) {
                        Runnable opened = stream.onFirstData;
                        stream.onFirstData = null;
                        opened.run();
                    }
                    if (stream.onReachingDataBytes != null && stream.dataBytes >= stream.reachingDataBytes) {
                        Runnable opened = stream.onReachingDataBytes;
                        stream.onReachingDataBytes = null;
                        opened.run();
                    }
                    int consume = Math.min(data.initialFlowControlledBytes(), stream.consumeLimit - stream.consumed);
                    if (consume > 0 && !data.isEndStream()) {
                        stream.consumed += consume;
                        ctx.writeAndFlush(new DefaultHttp2WindowUpdateFrame(consume).stream(stream.frameStream));
                    }
                } else if (msg instanceof Http2HeadersFrame) {
                    stream.endStream |= ((Http2HeadersFrame) msg).isEndStream();
                } else if (msg instanceof Http2ResetFrame) {
                    stream.resetNanos = System.nanoTime();
                    stream.resetErrorCode = ((Http2ResetFrame) msg).errorCode();
                    if (stream.onReset != null) {
                        stream.onReset.run();
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }
    }
}

package org.mockserver.netty.proxy.relay;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpContent;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The CONNECT relay's loopback passes a streamed response on piece by piece; the pieces not yet written to the proxy
 * client are bounded, because one read of a compressed response can decode to far more than any heap.
 */
public class DownstreamProxyRelayHandlerStreamBoundTest {

    private static final int BOUND = 4 * 1024;

    private final List<ChannelPromise> pendingWrites = new ArrayList<>();
    private final List<Object> pendingMessages = new ArrayList<>();
    private final AtomicInteger reads = new AtomicInteger();
    private EmbeddedChannel proxyClient;
    private EmbeddedChannel loopback;

    @After
    public void releaseEverything() {
        pendingMessages.forEach(ReferenceCountUtil::release);
        if (loopback != null) {
            loopback.finishAndReleaseAll();
        }
        if (proxyClient != null) {
            proxyClient.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldCloseBothLegsAndReleaseThePieceWhenUnwrittenStreamedBytesPassTheBound() {
        givenAProxyClientThatNeverFinishesAWrite();

        for (int i = 0; i < 4; i++) {
            loopback.writeInbound(piece(1024));
        }
        assertThat(proxyClient.isOpen(), is(true));
        assertThat(loopback.isOpen(), is(true));

        HttpContent passesTheBound = piece(1);
        loopback.writeInbound(passesTheBound);
        loopback.runPendingTasks();
        proxyClient.runPendingTasks();

        assertThat(passesTheBound.refCnt(), is(0));
        assertThat(proxyClient.isOpen(), is(false));
        assertThat(loopback.isOpen(), is(false));
        assertThat("only the pieces within the bound were relayed", pendingMessages.size(), is(4));
        assertThat("the abort is recorded as MockServer's own", ServerClosedChannels.isClosedByServer(proxyClient), is(true));
    }

    @Test
    public void shouldNotRecordTheClientLegAsClosedByMockServerWhenTheClientLegHasAlreadyGone() {
        givenAProxyClientThatNeverFinishesAWrite();
        proxyClient.close();

        loopback.writeInbound(piece(1024 * 5));
        loopback.runPendingTasks();

        assertThat(ServerClosedChannels.isClosedByServer(proxyClient), is(false));
    }

    @Test
    public void shouldRelayAStreamManyTimesTheBoundWhileEachPieceIsWritten() {
        proxyClient = new EmbeddedChannel();
        loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, BOUND));

        for (int i = 0; i < 100; i++) {
            loopback.writeInbound(piece(1024));
        }

        assertThat(proxyClient.isOpen(), is(true));
        assertThat(loopback.isOpen(), is(true));
        int relayed = 0;
        for (Object written; (written = proxyClient.readOutbound()) != null; relayed++) {
            ReferenceCountUtil.release(written);
        }
        assertThat(relayed, is(100));
    }

    @Test
    public void shouldPauseReadsForASlowProxyClientAndResumeAsItsWritesComplete() {
        givenAProxyClientThatNeverFinishesAWrite();

        // reads pause above half the bound, well before it aborts
        loopback.writeInbound(piece(1024), piece(1024));
        assertThat(loopback.config().isAutoRead(), is(true));
        loopback.writeInbound(piece(1024));
        assertThat(loopback.config().isAutoRead(), is(false));

        int readsBefore = reads.get();
        pendingWrites.remove(0).setSuccess();
        assertThat(loopback.config().isAutoRead(), is(false));
        assertThat("no read while paused", reads.get(), is(readsBefore));
        pendingWrites.remove(0).setSuccess();
        assertThat("reads resume at a quarter of the bound", loopback.config().isAutoRead(), is(true));
        assertThat(proxyClient.isOpen(), is(true));
        assertThat(loopback.isOpen(), is(true));
    }

    @Test
    public void shouldBoundAStreamedResponseAtARequestBodyLimitOfZero() {
        for (int configured : new int[]{0, -1}) {
            proxyClient = new EmbeddedChannel();
            loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, configuration().maxRequestBodySize(configured).maxRequestBodySize()));

            HttpContent passesTheBound = piece(2);
            loopback.writeInbound(passesTheBound);

            assertThat("limit " + configured, passesTheBound.refCnt(), is(0));
            assertThat(proxyClient.isOpen(), is(false));
            assertThat(loopback.isOpen(), is(false));
            releaseEverything();
        }
    }

    @Test
    public void shouldTreatAConfiguredRequestBodyLimitOfZeroOrLessAsOneByte() {
        for (int configured : new int[]{0, -1}) {
            proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
                @Override
                public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                    pendingMessages.add(msg);
                }
            });
            loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, configuration().maxRequestBodySize(configured).maxRequestBodySize()));

            loopback.writeInbound(piece(1));
            assertThat("limit " + configured, proxyClient.isOpen(), is(true));
            loopback.writeInbound(piece(1));
            assertThat("limit " + configured, proxyClient.isOpen(), is(false));
            releaseEverything();
            pendingMessages.clear();
        }
    }

    @Test
    public void shouldCloseOnTheFirstStreamedByteAtABoundOfZeroOrLessButPassAHeadAndAnAggregatedResponse() {
        for (long bound : new long[]{0, -1}) {
            proxyClient = new EmbeddedChannel();
            loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, bound));

            loopback.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
            loopback.writeInbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.buffer(8).writeZero(8)));
            assertThat("bound " + bound, proxyClient.isOpen(), is(true));
            assertThat("bound " + bound, loopback.config().isAutoRead(), is(true));

            HttpContent firstByte = piece(1);
            loopback.writeInbound(firstByte);

            assertThat("bound " + bound, firstByte.refCnt(), is(0));
            assertThat("bound " + bound, proxyClient.isOpen(), is(false));
            assertThat("bound " + bound, loopback.isOpen(), is(false));
            releaseEverything();
        }
    }

    @Test
    public void shouldNotBoundTheRelayWithoutALimit() {
        proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                pendingMessages.add(msg);
                pendingWrites.add(promise);
            }
        });
        loopback = new EmbeddedChannel(new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient));

        for (int i = 0; i < 1024; i++) {
            loopback.writeInbound(piece(1024));
        }

        assertThat(proxyClient.isOpen(), is(true));
        assertThat(loopback.isOpen(), is(true));
        assertThat("reads are never paused", loopback.config().isAutoRead(), is(true));
        assertThat(pendingMessages.size(), is(1024));
    }

    @Test
    public void shouldNotCountAnAggregatedResponse() {
        givenAProxyClientThatNeverFinishesAWrite();

        // an aggregated response is bounded by its aggregator, whatever its size
        loopback.writeInbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.buffer(2 * BOUND).writeZero(2 * BOUND)));

        assertThat(proxyClient.isOpen(), is(true));
        assertThat(loopback.isOpen(), is(true));
        assertThat(pendingMessages.size(), is(1));
    }

    @Test
    public void shouldDropTheRestOfTheReadOnceAborted() {
        givenAProxyClientThatNeverFinishesAWrite();
        for (int i = 0; i < 4; i++) {
            loopback.writeInbound(piece(1024));
        }

        // as a decoder does, the rest of the same read arrives after the piece that passed the bound
        HttpContent passesTheBound = piece(1024);
        HttpContent afterTheAbort = piece(1024);
        loopback.writeInbound(passesTheBound, afterTheAbort);

        assertThat(passesTheBound.refCnt(), is(0));
        assertThat(afterTheAbort.refCnt(), is(0));
        assertThat(pendingMessages.size(), is(4));
    }

    @Test
    public void shouldNotAbortAStreamedResponseBehindRawBytesStillWaitingToBeWritten() {
        givenAProxyClientThatNeverFinishesAWrite();

        loopback.writeInbound(rawBytes(3 * 1024));
        loopback.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        loopback.writeInbound(piece(2 * 1024));

        assertThat("the raw and streamed bytes together pass the bound, the streamed ones alone do not", proxyClient.isOpen(), is(true));
        assertThat(loopback.isOpen(), is(true));
        assertThat(pendingMessages.size(), is(3));
    }

    @Test
    public void shouldStillAbortAStreamedResponseBehindRawBytesOncePassingTheBoundByItself() {
        givenAProxyClientThatNeverFinishesAWrite();
        loopback.writeInbound(rawBytes(1024));
        for (int i = 0; i < 4; i++) {
            loopback.writeInbound(piece(1024));
        }
        assertThat(proxyClient.isOpen(), is(true));

        HttpContent passesTheBound = piece(1);
        loopback.writeInbound(passesTheBound);

        assertThat(passesTheBound.refCnt(), is(0));
        assertThat(proxyClient.isOpen(), is(false));
        assertThat(loopback.isOpen(), is(false));
    }

    @Test
    public void shouldPauseReadsOnRawAndStreamedBytesTogetherAndResumeOnlyWhenBothHaveDrained() {
        // raw bytes written first, then the streamed ones
        assertPausedUntilBothDrain(1024, 1536, 0);
        releaseEverything();
        pendingMessages.clear();
        pendingWrites.clear();
        // the streamed bytes written first, then the raw ones
        assertPausedUntilBothDrain(1536, 1024, 1);
    }

    private void assertPausedUntilBothDrain(int raw, int streamed, int writtenFirst) {
        givenAProxyClientThatNeverFinishesAWrite();

        loopback.writeInbound(rawBytes(raw));
        assertThat("raw bytes alone are within half the bound", loopback.config().isAutoRead(), is(true));
        loopback.writeInbound(piece(streamed));
        assertThat("together they are above half the bound", loopback.config().isAutoRead(), is(false));

        pendingWrites.remove(writtenFirst).setSuccess();
        assertThat("still above a quarter of the bound unwritten", loopback.config().isAutoRead(), is(false));
        pendingWrites.remove(0).setSuccess();
        assertThat(loopback.config().isAutoRead(), is(true));
        assertThat(proxyClient.isOpen(), is(true));
    }

    private void givenAProxyClientThatNeverFinishesAWrite() {
        proxyClient = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                pendingMessages.add(msg);
                pendingWrites.add(promise);
            }
        });
        loopback = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override
            public void read(ChannelHandlerContext ctx) {
                reads.incrementAndGet();
                ctx.read();
            }
        }, new DownstreamProxyRelayHandler(new MockServerLogger(), proxyClient, BOUND));
    }

    private static RawResponseBytes rawBytes(int size) {
        return new RawResponseBytes(Unpooled.buffer(size).writeZero(size), true);
    }

    private static HttpContent piece(int size) {
        return new DefaultHttpContent(Unpooled.buffer(size).writeZero(size));
    }
}

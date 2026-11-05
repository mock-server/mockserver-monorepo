package org.mockserver.netty.http3;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelId;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicTransportParameters;
import io.netty.util.AttributeKey;
import io.netty.util.DefaultAttributeMap;
import org.junit.After;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItemInArray;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class Http3StreamWriteStallHandlerTest {

    private static final int MAX_WRITE_BYTES = Http3StreamWriteStallHandler.MAX_WRITE_BYTES;

    private final HeldWrites held = new HeldWrites();
    private EmbeddedChannel channel;

    @After
    public void closeChannel() {
        if (channel != null) {
            channel.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldPassALargeWriteOnInPartsOfAtMostTheMaximumSizeWithItsBytesIntactAndInOrder() {
        channel = new EmbeddedChannel(new Http3StreamWriteStallHandler(60_000, new MockServerLogger()));
        byte[] bytes = new byte[3 * MAX_WRITE_BYTES + 100];
        new Random(93).nextBytes(bytes);
        ByteBuf whole = Unpooled.wrappedBuffer(bytes);

        ChannelFuture written = channel.writeAndFlush(whole);

        List<Integer> sizes = new ArrayList<>();
        ByteArrayOutputStream joined = new ByteArrayOutputStream();
        for (ByteBuf part = channel.readOutbound(); part != null; part = channel.readOutbound()) {
            sizes.add(part.readableBytes());
            byte[] partBytes = new byte[part.readableBytes()];
            part.readBytes(partBytes);
            joined.write(partBytes, 0, partBytes.length);
            part.release();
        }
        assertThat(sizes, contains(MAX_WRITE_BYTES, MAX_WRITE_BYTES, MAX_WRITE_BYTES, 100));
        assertThat("the parts carry the bytes intact, in order", Arrays.equals(joined.toByteArray(), bytes), is(true));
        assertThat(written.isSuccess(), is(true));
        assertThat("released once its parts are", whole.refCnt(), is(0));
    }

    @Test
    public void shouldPassAWriteOfTheMaximumSizeOnWhole() {
        channel = new EmbeddedChannel(new Http3StreamWriteStallHandler(60_000, new MockServerLogger()));
        ByteBuf whole = Unpooled.wrappedBuffer(new byte[MAX_WRITE_BYTES]);

        channel.writeAndFlush(whole);

        assertThat(channel.readOutbound(), is(sameInstance(whole)));
        assertThat(channel.readOutbound() == null, is(true));
    }

    @Test
    public void shouldPassOnAWriteMadeWithAVoidPromise() {
        channel = new EmbeddedChannel(new Http3StreamWriteStallHandler(60_000, new MockServerLogger()));
        ByteBuf whole = Unpooled.wrappedBuffer(new byte[100]);

        channel.writeAndFlush(whole, channel.voidPromise());

        assertThat(channel.readOutbound(), is(sameInstance(whole)));
    }

    @Test
    public void shouldPassAMessageThatIsNotABufferOnUnchanged() {
        channel = new EmbeddedChannel(new Http3StreamWriteStallHandler(60_000, new MockServerLogger()));
        Object message = new Object();

        ChannelFuture written = channel.writeAndFlush(message);

        assertThat(channel.readOutbound(), is(sameInstance(message)));
        assertThat(written.isSuccess(), is(true));
    }

    @Test
    public void shouldCompleteALargeWriteOnlyOnceEveryPartIsWritten() {
        channel = new EmbeddedChannel(held, new Http3StreamWriteStallHandler(60_000, new MockServerLogger()));

        ChannelFuture written = channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[2 * MAX_WRITE_BYTES + 1]));

        assertThat(held.promises.size(), is(3));
        held.promises.get(0).setSuccess();
        held.promises.get(1).setSuccess();
        assertThat("the last part is still waiting", written.isDone(), is(false));
        held.promises.get(2).setSuccess();
        assertThat(written.isSuccess(), is(true));
    }

    @Test
    public void shouldFailALargeWriteWhenOneOfItsPartsFails() {
        channel = new EmbeddedChannel(held, new Http3StreamWriteStallHandler(60_000, new MockServerLogger()));
        IllegalStateException failure = new IllegalStateException("part not written");

        ChannelFuture written = channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[2 * MAX_WRITE_BYTES + 1]));

        held.promises.get(0).setSuccess();
        held.promises.get(1).setFailure(failure);
        held.promises.get(2).setSuccess();
        assertThat(written.isDone(), is(true));
        assertThat(written.cause(), is(sameInstance(failure)));
    }

    @Test
    public void shouldCloseAStreamWhoseWritesWaitForTheTimeout() throws InterruptedException {
        channel = new EmbeddedChannel(held, new Http3StreamWriteStallHandler(50, new MockServerLogger()));

        ChannelFuture written = channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[100]));

        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (channel.isOpen() && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(10);
            channel.runScheduledPendingTasks();
        }
        assertThat("the stream was closed", channel.isOpen(), is(false));
        assertThat("the close is recorded as MockServer's own", ServerClosedChannels.isClosedByServer(channel), is(true));
        assertThat("the write was not taken", written.isDone(), is(false));
    }

    @Test
    public void shouldNotTimeAStreamWhoseWriteQuicTakesAtOnce() {
        ChannelOutboundHandlerAdapter takesAtOnce = new ChannelOutboundHandlerAdapter() {
            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                ((ByteBuf) msg).release();
                promise.setSuccess();
            }
        };
        channel = new EmbeddedChannel(takesAtOnce, new Http3StreamWriteStallHandler(50, new MockServerLogger()));

        ChannelFuture written = channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[100]));

        assertThat(written.isSuccess(), is(true));
        assertThat("no timer: nothing is waiting", channel.runScheduledPendingTasks(), is(-1L));
        assertThat(channel.isOpen(), is(true));
    }

    @Test
    public void shouldPassOnAWriteMadeWhileALargeWriteIsPassedOnInPartsOnlyAfterItsLastPart() {
        CompletesTheQueuedWriteInsideTheNext quic = new CompletesTheQueuedWriteInsideTheNext();
        channel = new EmbeddedChannel(quic, new Http3StreamWriteStallHandler(60_000, new MockServerLogger()));
        ChannelFuture[] chained = new ChannelFuture[1];

        // a writer that makes its next write, and flushes it, when QUIC has taken its last
        channel.write(filled('E', 100)).addListener(future -> chained[0] = channel.writeAndFlush(filled('N', 100)));
        ChannelFuture large = channel.write(filled('A', 3 * MAX_WRITE_BYTES));

        assertThat(quic.passedOn, contains("E x 100", "A x 32768", "flush", "A x 32768", "A x 32768", "N x 100", "flush"));
        assertThat(large.isSuccess(), is(true));
        assertThat(chained[0].isSuccess(), is(true));
    }

    @Test
    public void shouldFailAndReleaseAWriteStillHeldWhenTheHandlerIsRemoved() {
        CompletesTheQueuedWriteInsideTheNext quic = new CompletesTheQueuedWriteInsideTheNext();
        Http3StreamWriteStallHandler handler = new Http3StreamWriteStallHandler(60_000, new MockServerLogger());
        channel = new EmbeddedChannel(quic, handler);
        ByteBuf heldBuffer = filled('N', 100);
        ChannelFuture[] chained = new ChannelFuture[1];

        channel.write(filled('E', 100)).addListener(future -> {
            chained[0] = channel.write(heldBuffer);
            channel.pipeline().remove(handler);
        });
        channel.write(filled('A', 3 * MAX_WRITE_BYTES));

        assertThat(quic.passedOn, contains("E x 100", "A x 32768", "A x 32768", "A x 32768"));
        assertThat(chained[0].cause(), is(instanceOf(ClosedChannelException.class)));
        assertThat(heldBuffer.refCnt(), is(0));
    }

    @Test
    public void shouldTimeEveryStreamOfAConnectionByTheTimeoutItsFirstStreamWasGivenAndLogThatTimeout() throws InterruptedException {
        EmbeddedChannel connectionLoop = new EmbeddedChannel();
        QuicChannel connection = mock(QuicChannel.class);
        DefaultAttributeMap attributes = new DefaultAttributeMap();
        when(connection.attr(any())).thenAnswer(invocation -> attributes.attr((AttributeKey<?>) invocation.getArgument(0)));
        when(connection.eventLoop()).thenReturn(connectionLoop.eventLoop());
        MockServerLogger logger = mock(MockServerLogger.class);
        when(logger.isEnabledForInstance(Level.WARN)).thenReturn(true);
        EmbeddedChannel first = stream(connection, new Http3StreamWriteStallHandler(50, logger));
        channel = stream(connection, held, new Http3StreamWriteStallHandler(60_000, logger));
        try {
            channel.writeAndFlush(Unpooled.wrappedBuffer(new byte[100]));

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (channel.isOpen() && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(10);
                connectionLoop.runScheduledPendingTasks();
            }
            assertThat("the second stream was closed after the first stream's timeout", channel.isOpen(), is(false));
            ArgumentCaptor<LogEntry> logged = ArgumentCaptor.forClass(LogEntry.class);
            verify(logger).logEvent(logged.capture());
            assertThat(logged.getValue().getArguments(), hasItemInArray((Object) 50L));
        } finally {
            first.finishAndReleaseAll();
            connectionLoop.finishAndReleaseAll();
        }
    }

    @Test
    public void shouldLeaveEveryStreamToItsOwnClockWhenTheClientsGrantIsUnknown() {
        assertThat(Http3StreamWriteStallHandler.holderBytes(null), is(0L));
    }

    @Test
    public void shouldTakeHalfTheSmallerOfTheConnectionAndStreamGrantsAsWhatAStreamMustBeSentToHoldTheCredit() {
        assertThat(Http3StreamWriteStallHandler.holderBytes(granted(10_000_000, 1_000_000)), is(500_000L));
        assertThat(Http3StreamWriteStallHandler.holderBytes(granted(256 * 1024, 1024 * 1024)), is(128 * 1024L));
        assertThat(Http3StreamWriteStallHandler.holderBytes(granted(2L * MAX_WRITE_BYTES, 1_000_000)), is((long) MAX_WRITE_BYTES));
    }

    @Test
    public void shouldLeaveEveryStreamToItsOwnClockWhenHalfTheGrantIsUnderOneWrite() {
        assertThat(Http3StreamWriteStallHandler.holderBytes(granted(2L * MAX_WRITE_BYTES - 2, 1_000_000)), is(0L));
        assertThat(Http3StreamWriteStallHandler.holderBytes(granted(10_000_000, 16 * 1024)), is(0L));
    }

    private static EmbeddedChannel stream(Channel connection, io.netty.channel.ChannelHandler... handlers) {
        return new EmbeddedChannel(connection, DefaultChannelId.newInstance(), true, false, handlers);
    }

    private static ByteBuf filled(char with, int bytes) {
        byte[] content = new byte[bytes];
        Arrays.fill(content, (byte) with);
        return Unpooled.wrappedBuffer(content);
    }

    private static QuicTransportParameters granted(long connectionBytes, long streamBytes) {
        QuicTransportParameters granted = mock(QuicTransportParameters.class);
        when(granted.initialMaxData()).thenReturn(connectionBytes);
        when(granted.initialMaxStreamDataBidiLocal()).thenReturn(streamBytes);
        return granted;
    }

    /**
     * Stands in for a QUIC stream out of credit: the first write is queued, and the next retries the queue, as
     * {@code QuicheQuicStreamChannel.write()} does, so the queued write completes inside that write. Records what it
     * was passed, in order.
     */
    private static final class CompletesTheQueuedWriteInsideTheNext extends ChannelOutboundHandlerAdapter {
        private final List<String> passedOn = new ArrayList<>();
        private ChannelPromise queued;

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            ByteBuf buffer = (ByteBuf) msg;
            passedOn.add((char) buffer.getByte(buffer.readerIndex()) + " x " + buffer.readableBytes());
            buffer.release();
            if (queued == null) {
                queued = promise;
            } else {
                if (!queued.isDone()) {
                    queued.setSuccess();
                }
                promise.setSuccess();
            }
        }

        @Override
        public void flush(ChannelHandlerContext ctx) {
            passedOn.add("flush");
            ctx.flush();
        }
    }

    /**
     * Stands in for the QUIC stream: keeps each write, and its promise, until the test completes it.
     */
    private static final class HeldWrites extends ChannelOutboundHandlerAdapter {
        private final List<ChannelPromise> promises = new ArrayList<>();
        private final List<Object> messages = new ArrayList<>();

        @Override
        public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
            messages.add(msg);
            promises.add(promise);
        }

        @Override
        public void handlerRemoved(ChannelHandlerContext ctx) {
            messages.forEach(io.netty.util.ReferenceCountUtil::release);
        }
    }
}

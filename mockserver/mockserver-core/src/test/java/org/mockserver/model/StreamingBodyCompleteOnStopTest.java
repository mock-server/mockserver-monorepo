package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.DefaultEventLoop;
import io.netty.channel.EventLoop;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@link StreamingBody#completeOnStop()} ends an open stream as complete, keeping its chunk timing, and closes the
 * upstream, but leaves a stream that has already ended as it was.
 */
public class StreamingBodyCompleteOnStopTest {

    private static StreamingBody bodyWithTwoChunks(AtomicInteger completedSubscriber, AtomicInteger upstreamClosed) {
        StreamingBody body = new StreamingBody(1024, true);
        body.setUpstreamCloser(upstreamClosed::incrementAndGet);
        body.subscribe(chunk -> {
        }, completedSubscriber::incrementAndGet, error -> {
        });
        addChunk(body, "first");
        addChunk(body, "second");
        return body;
    }

    private static void addChunk(StreamingBody body, String text) {
        ByteBuf chunk = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        try {
            body.addChunk(chunk);
        } finally {
            chunk.release();
        }
    }

    @Test
    public void shouldCompleteAnOpenStreamKeepingItsTimingAndCloseTheUpstream() {
        AtomicInteger completedSubscriber = new AtomicInteger();
        AtomicInteger upstreamClosed = new AtomicInteger();
        AtomicInteger listenerRuns = new AtomicInteger();
        StreamingBody body = bodyWithTwoChunks(completedSubscriber, upstreamClosed);
        body.addCompletionListener(listenerRuns::incrementAndGet);

        body.completeOnStop();

        assertThat(body.isCompleted(), is(true));
        assertThat(body.getError(), is(nullValue()));
        assertThat(body.isTruncated(), is(false));
        assertThat(body.isCompletedByStop(), is(true));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        List<Long> delays = body.interChunkDelaysMillis();
        assertThat(delays, is(notNullValue()));
        assertThat(delays, hasSize(2));
        assertThat("the client's response ends normally", completedSubscriber.get(), is(1));
        assertThat(listenerRuns.get(), is(1));
        assertThat(upstreamClosed.get(), is(1));
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("firstsecond"));
    }

    @Test
    public void shouldLeaveAStreamThatAlreadyEndedAsItWas() {
        AtomicInteger completedSubscriber = new AtomicInteger();
        AtomicInteger upstreamClosed = new AtomicInteger();
        StreamingBody completed = bodyWithTwoChunks(completedSubscriber, upstreamClosed);
        completed.complete();

        completed.completeOnStop();

        assertThat(completed.isCompletedByStop(), is(false));
        assertThat(completedSubscriber.get(), is(1));
        assertThat(upstreamClosed.get(), is(0));

        StreamingBody failed = bodyWithTwoChunks(new AtomicInteger(), upstreamClosed);
        failed.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        failed.completeOnStop();

        assertThat(failed.isCompletedByStop(), is(false));
        assertThat(failed.getError(), is(notNullValue()));
        assertThat(upstreamClosed.get(), is(0));
    }

    @Test
    public void shouldNotBeCompletedByStopByDefault() {
        StreamingBody body = new StreamingBody(1024, true);
        body.complete();

        assertThat(body.isCompletedByStop(), is(false));
    }

    @Test
    public void shouldCompleteOnTheUpstreamEventLoop() throws Exception {
        EventLoop eventLoop = new DefaultEventLoop();
        try {
            StreamingBody body = new StreamingBody(1024, true);
            body.setEventLoop(eventLoop);
            AtomicReference<Boolean> completedOnEventLoop = new AtomicReference<>();
            CountDownLatch completed = new CountDownLatch(1);
            body.addCompletionListener(() -> {
                completedOnEventLoop.set(eventLoop.inEventLoop());
                completed.countDown();
            });

            body.completeOnStop();

            assertThat(completed.await(5, TimeUnit.SECONDS), is(true));
            assertThat(completedOnEventLoop.get(), is(true));
            assertThat(body.isCompletedByStop(), is(true));
        } finally {
            eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void shouldCompleteInPlaceOnceTheUpstreamEventLoopHasShutDown() {
        EventLoop eventLoop = new DefaultEventLoop();
        eventLoop.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
        StreamingBody body = new StreamingBody(1024, true);
        body.setEventLoop(eventLoop);

        body.completeOnStop();

        assertThat(body.isCompleted(), is(true));
        assertThat(body.isCompletedByStop(), is(true));
    }
}

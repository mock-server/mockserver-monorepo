package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;

/**
 * A stream the client closed is told apart from one the upstream lost: {@link StreamingBody#closeUpstreamForClientClose()}
 * notes the client, and {@link StreamingBody#completeAfterClientClose()} ends the body complete, keeping chunk timing.
 */
public class StreamingBodyClientCloseTest {

    private static StreamingBody bodyWithTwoChunks() {
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {
        }, () -> {
        }, error -> {
        });
        ByteBuf first = Unpooled.copiedBuffer("first", StandardCharsets.UTF_8);
        ByteBuf second = Unpooled.copiedBuffer("second", StandardCharsets.UTF_8);
        body.addChunk(first);
        body.addChunk(second);
        first.release();
        second.release();
        return body;
    }

    @Test
    public void shouldNotBeClosedByClientByDefault() {
        StreamingBody body = new StreamingBody(1024, true);

        assertThat(body.isClosedByClient(), is(false));
        assertThat(body.isCompletedAfterClientClose(), is(false));
    }

    @Test
    public void shouldNoteTheClientAndRunTheCloserWhenClosedForClientClose() {
        StreamingBody body = new StreamingBody(1024, true);
        AtomicInteger closed = new AtomicInteger();
        body.setUpstreamCloser(closed::incrementAndGet);

        body.closeUpstreamForClientClose();

        assertThat(body.isClosedByClient(), is(true));
        assertThat(closed.get(), is(1));
    }

    @Test
    public void shouldNoteTheClientEvenWithoutACloser() {
        StreamingBody body = new StreamingBody(1024, true);

        body.closeUpstreamForClientClose();

        assertThat(body.isClosedByClient(), is(true));
    }

    @Test
    public void shouldNotNoteTheClientWhenClosedAnyOtherWay() {
        StreamingBody body = new StreamingBody(1024, true);
        AtomicInteger closed = new AtomicInteger();
        body.setUpstreamCloser(closed::incrementAndGet);

        body.closeUpstream();

        assertThat(body.isClosedByClient(), is(false));
        assertThat(closed.get(), is(1));
    }

    @Test
    public void shouldKeepChunkDelaysAndNotTruncateWhenCompletedAfterClientClose() {
        StreamingBody body = bodyWithTwoChunks();

        body.closeUpstreamForClientClose();
        body.completeAfterClientClose();

        assertThat(body.isCompleted(), is(true));
        assertThat(body.isTruncated(), is(false));
        assertThat(body.getError(), is(nullValue()));
        assertThat(body.isCompletedAfterClientClose(), is(true));
        assertThat(body.interChunkDelaysMillis(), is(notNullValue()));
        assertThat(body.interChunkDelaysMillis().size(), is(2));
    }

    @Test
    public void shouldStillDropChunkDelaysAndTruncateOnError() {
        StreamingBody body = bodyWithTwoChunks();

        body.closeUpstreamForClientClose();
        body.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        assertThat(body.isTruncated(), is(true));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
    }

    @Test
    public void shouldLetAnEarlierCompleteWinAndFireListenersOnce() {
        StreamingBody body = bodyWithTwoChunks();
        AtomicInteger fired = new AtomicInteger();
        body.addCompletionListener(fired::incrementAndGet);

        body.complete();
        body.completeAfterClientClose();
        body.error(new StreamingBody.StreamAbortedException("late"));

        assertThat(fired.get(), is(1));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.isTruncated(), is(false));
    }

    @Test
    public void shouldLetAnEarlierClientCloseCompleteWinOverAComplete() {
        StreamingBody body = bodyWithTwoChunks();
        AtomicInteger fired = new AtomicInteger();
        body.addCompletionListener(fired::incrementAndGet);

        body.completeAfterClientClose();
        body.complete();

        assertThat(fired.get(), is(1));
        assertThat(body.isCompletedAfterClientClose(), is(true));
    }
}

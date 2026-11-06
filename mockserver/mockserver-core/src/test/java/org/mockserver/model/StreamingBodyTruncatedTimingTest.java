package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * With {@code keepChunkTimestampsWhenTruncated} a body that becomes truncated keeps the timing of the chunks it
 * captured before the cut and times nothing after it; without it, as before, it drops the timing.
 */
public class StreamingBodyTruncatedTimingTest {

    private static StreamingBody subscribedBody(int maxCaptureBytes, boolean keepWhenTruncated) {
        return subscribedBody(maxCaptureBytes, 0, keepWhenTruncated);
    }

    private static StreamingBody subscribedBody(int maxCaptureBytes, long maxUnwrittenBytes, boolean keepWhenTruncated) {
        StreamingBody body = new StreamingBody(maxCaptureBytes, true, maxUnwrittenBytes, keepWhenTruncated);
        body.subscribe(chunk -> {
        }, () -> {
        }, error -> {
        });
        return body;
    }

    private static boolean add(StreamingBody body, String text) {
        ByteBuf chunk = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
        try {
            return body.addChunk(chunk);
        } finally {
            chunk.release();
        }
    }

    @Test
    public void shouldKeepTimingOfChunksBeforeAnUpstreamFailureWhenOn() throws Exception {
        StreamingBody body = subscribedBody(1024, true);
        add(body, "data: one\n\n");
        Thread.sleep(40);
        add(body, "data: two\n\n");

        body.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        assertThat(body.isTruncated(), is(true));
        List<Long> delays = body.interChunkDelaysMillis();
        assertThat(delays, hasSize(2));
        assertThat(delays.get(0), is(0L));
        assertThat(delays.get(1), greaterThanOrEqualTo(30L));
    }

    @Test
    public void shouldDropTimingOnAnUpstreamFailureWhenOff() {
        StreamingBody body = subscribedBody(1024, false);
        add(body, "data: one\n\n");
        add(body, "data: two\n\n");

        body.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
    }

    @Test
    public void shouldKeepTimingOnAnIdleTimeoutWhenOn() {
        StreamingBody body = subscribedBody(1024, true);
        add(body, "data: one\n\n");

        body.error(new StreamingBody.IdleTimeoutException(1));

        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), contains(0L));
    }

    @Test
    public void shouldDropTimingOnAnIdleTimeoutWhenOff() {
        StreamingBody body = subscribedBody(1024, false);
        add(body, "data: one\n\n");

        body.error(new StreamingBody.IdleTimeoutException(1));

        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
    }

    @Test
    public void shouldKeepTimingUpToAndIncludingTheChunkThatPassesTheCaptureLimitWhenOn() {
        StreamingBody body = subscribedBody(8, true);
        add(body, "12345");
        add(body, "6789A");
        add(body, "BCDEF");

        assertThat(body.isTruncated(), is(true));
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("12345678"));
        assertThat("the two chunks with captured bytes are timed, the one after the cut is not",
            body.interChunkDelaysMillis(), hasSize(2));
    }

    @Test
    public void shouldDropTimingWhenTheCaptureLimitIsPassedWhenOff() {
        StreamingBody body = subscribedBody(8, false);
        add(body, "12345");
        add(body, "6789A");

        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
    }

    @Test
    public void shouldNotTimeAChunkNothingOfWhichWasCapturedWhenOn() {
        StreamingBody body = subscribedBody(0, true);
        add(body, "data: one\n\n");

        assertThat(body.isTruncated(), is(true));
        assertThat(body.capturedBytes().length, is(0));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
    }

    @Test
    public void shouldKeepTimingOfChunksBeforeTheUnwrittenBytesLimitFailsTheStreamWhenOn() {
        StreamingBody body = subscribedBody(1024, 16, true);
        add(body, "0123456789");

        assertThat("the chunk past the bound is refused", add(body, "0123456789"), is(false));

        assertThat(body.getError(), instanceOf(StreamingBody.UnwrittenBytesLimitExceededException.class));
        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), contains(0L));
    }

    @Test
    public void shouldNotGrowTheTimingAfterTheCaptureLimitWhenOn() {
        StreamingBody body = subscribedBody(8, true);
        add(body, "12345");
        add(body, "6789A");

        for (int i = 0; i < 1000; i++) {
            add(body, "more");
        }

        assertThat(body.interChunkDelaysMillis(), hasSize(2));
    }

    @Test
    public void shouldNotGrowTheTimingAfterAnErrorWhenOn() {
        StreamingBody body = subscribedBody(1024, true);
        add(body, "data: one\n\n");
        body.error(new StreamingBody.StreamAbortedException("upstream failed mid-stream"));

        assertThat(add(body, "data: two\n\n"), is(false));

        assertThat(body.interChunkDelaysMillis(), hasSize(1));
    }

    @Test
    public void shouldKeepTimingOfACompleteStreamEitherWay() {
        for (boolean keep : new boolean[]{true, false}) {
            StreamingBody body = subscribedBody(1024, keep);
            add(body, "data: one\n\n");
            add(body, "data: two\n\n");

            body.complete();

            assertThat(body.isTruncated(), is(false));
            assertThat(body.interChunkDelaysMillis(), hasSize(2));
        }
    }

    @Test
    public void shouldDropTimingOnTruncationByDefault() {
        StreamingBody body = new StreamingBody(1024, true, 0);
        add(body, "data: one\n\n");

        body.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
    }
}

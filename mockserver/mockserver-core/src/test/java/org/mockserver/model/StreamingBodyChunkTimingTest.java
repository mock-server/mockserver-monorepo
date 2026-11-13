package org.mockserver.model;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.hamcrest.core.Is.is;

/**
 * Tests for per-chunk timestamp capture in {@link StreamingBody} and the
 * resulting inter-chunk delay computation. These tests verify the recording
 * half of the G14 per-chunk replay timing feature.
 */
public class StreamingBodyChunkTimingTest {

    @Test
    public void shouldNotCaptureTimestampsWhenDisabled() {
        // default constructor does not capture timestamps
        StreamingBody body = new StreamingBody(1024);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk = Unpooled.copiedBuffer("hello", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        body.complete();

        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        chunk.release();
    }

    @Test
    public void shouldNotCaptureTimestampsWhenExplicitlyDisabled() {
        StreamingBody body = new StreamingBody(1024, false);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk = Unpooled.copiedBuffer("data", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        body.complete();

        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        chunk.release();
    }

    @Test
    public void shouldCaptureTimestampsWhenEnabled() {
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk1 = Unpooled.copiedBuffer("first", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("second", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.addChunk(chunk2);
        body.complete();

        List<Long> delays = body.interChunkDelaysMillis();
        assertThat(delays, is(notNullValue()));
        assertThat(delays, hasSize(2));
        // First chunk delay is always 0
        assertThat(delays.get(0), is(0L));
        // Second chunk delay is non-negative (elapsed time between chunk1 and chunk2)
        assertThat(delays.get(1), is(greaterThanOrEqualTo(0L)));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldReturnNullForEmptyTimestampList() {
        StreamingBody body = new StreamingBody(1024, true);
        // No chunks added
        body.subscribe(chunk -> {}, () -> {}, error -> {});
        body.complete();

        // No chunks were added, so interChunkDelaysMillis returns null
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
    }

    @Test
    public void shouldCaptureTimestampsForSingleChunk() {
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk = Unpooled.copiedBuffer("only", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        body.complete();

        List<Long> delays = body.interChunkDelaysMillis();
        assertThat(delays, hasSize(1));
        assertThat(delays.get(0), is(0L));

        chunk.release();
    }

    @Test
    public void shouldNotRecordTimestampsForChunksAfterComplete() {
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk1 = Unpooled.copiedBuffer("before", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.complete();

        ByteBuf chunk2 = Unpooled.copiedBuffer("after", StandardCharsets.UTF_8);
        body.addChunk(chunk2); // should be ignored

        List<Long> delays = body.interChunkDelaysMillis();
        assertThat(delays, hasSize(1));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldCaptureTimestampsForMultipleChunks() {
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        // Add 5 chunks
        ByteBuf[] chunks = new ByteBuf[5];
        for (int i = 0; i < 5; i++) {
            chunks[i] = Unpooled.copiedBuffer("chunk" + i, StandardCharsets.UTF_8);
            body.addChunk(chunks[i]);
        }
        body.complete();

        List<Long> delays = body.interChunkDelaysMillis();
        assertThat(delays, hasSize(5));
        assertThat(delays.get(0), is(0L));
        // All inter-chunk delays should be non-negative
        for (int i = 1; i < 5; i++) {
            assertThat("delay at index " + i, delays.get(i), is(greaterThanOrEqualTo(0L)));
        }

        for (ByteBuf chunk : chunks) {
            chunk.release();
        }
    }

    @Test
    public void shouldStillCaptureBodyBytesWhenTimestampsEnabled() {
        // Ensure timestamp capture does not interfere with the existing capture buffer
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk1 = Unpooled.copiedBuffer("hello ", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("world", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.addChunk(chunk2);
        body.complete();

        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("hello world"));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldStopRecordingTimestampsAfterTruncation() {
        // Small capture buffer — truncation discards timestamps for fixed-delay fallback
        StreamingBody body = new StreamingBody(5, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk1 = Unpooled.copiedBuffer("12345", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("67890", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        // After chunk1 the capture buffer is full (5 bytes), truncation occurs
        assertThat(body.isTruncated(), is(true));

        body.addChunk(chunk2);
        body.complete();

        // Truncated recording should yield null delays (fixed-delay fallback)
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldStopRecordingTimestampsAfterTruncationManyChunks() {
        // Verify no unbounded growth: 100 chunks after truncation should not accumulate timestamps
        StreamingBody body = new StreamingBody(3, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf initial = Unpooled.copiedBuffer("abc", StandardCharsets.UTF_8);
        body.addChunk(initial);
        assertThat(body.isTruncated(), is(true));

        for (int i = 0; i < 100; i++) {
            ByteBuf extra = Unpooled.copiedBuffer("x", StandardCharsets.UTF_8);
            body.addChunk(extra);
            extra.release();
        }
        body.complete();

        // Timestamps must be null after truncation — no unbounded growth
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        initial.release();
    }

    @Test
    public void shouldYieldNullDelaysAfterStreamError() {
        // error() sets truncated=true and should discard timestamps
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});

        ByteBuf chunk = Unpooled.copiedBuffer("data", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        body.error(new RuntimeException("connection reset"));

        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        chunk.release();
    }

    @Test
    public void shouldCaptureTimestampsForPreSubscribeChunks() {
        // Chunks arrive before subscribe -- timestamps should still be captured
        StreamingBody body = new StreamingBody(1024, true);

        ByteBuf chunk1 = Unpooled.copiedBuffer("pre1", StandardCharsets.UTF_8);
        ByteBuf chunk2 = Unpooled.copiedBuffer("pre2", StandardCharsets.UTF_8);
        body.addChunk(chunk1);
        body.addChunk(chunk2);

        body.subscribe(chunk -> {}, () -> {}, error -> {});
        body.complete();

        List<Long> delays = body.interChunkDelaysMillis();
        assertThat(delays, hasSize(2));
        assertThat(delays.get(0), is(0L));
        assertThat(delays.get(1), is(greaterThanOrEqualTo(0L)));

        chunk1.release();
        chunk2.release();
    }

    @Test
    public void shouldRecordTheBytesCapturedFromEachTimedChunk() {
        StreamingBody body = new StreamingBody(8, true, 0, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});
        for (String text : new String[]{"abc", "", "defg", "hij", "klm"}) {
            ByteBuf chunk = Unpooled.copiedBuffer(text, StandardCharsets.UTF_8);
            body.addChunk(chunk);
            chunk.release();
        }

        assertThat("the chunk that passed the capture limit counts only what was captured of it, and later chunks are not timed",
            body.chunkCapturedBytes(), is(List.of(3, 0, 4, 1)));
        assertThat(body.interChunkDelaysMillis().size(), is(4));
    }

    @Test
    public void shouldRecordNoChunkBytesWhenTimingIsNotCaptured() {
        StreamingBody body = new StreamingBody(1024, false);
        body.subscribe(chunk -> {}, () -> {}, error -> {});
        ByteBuf chunk = Unpooled.copiedBuffer("abc", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        chunk.release();

        assertThat(body.chunkCapturedBytes(), is(nullValue()));
    }

    @Test
    public void shouldDropTheChunkBytesWithTheTimingOfATruncatedBody() {
        StreamingBody body = new StreamingBody(4, true);
        body.subscribe(chunk -> {}, () -> {}, error -> {});
        ByteBuf chunk = Unpooled.copiedBuffer("abcdef", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        chunk.release();

        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        assertThat(body.chunkCapturedBytes(), is(nullValue()));
    }
}

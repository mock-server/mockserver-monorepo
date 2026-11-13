package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.model.SseEvent;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;

/**
 * Tests for per-chunk replay timing in {@link SseBodyParser}. These verify that
 * captured inter-chunk delays are correctly applied to parsed SSE events, and that
 * the fallback to fixed delays works when per-chunk timing is absent.
 */
public class SseBodyParserChunkTimingTest {

    @Test
    public void shouldApplyPerChunkDelaysWhenProvided() {
        // given
        SseBodyParser parser = new SseBodyParser(50); // 50ms default fallback
        String sseText =
            "data: first\n\n" +
            "data: second\n\n" +
            "data: third\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 120L, 45L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay(), nullValue()); // first event: no delay
        assertThat(events.get(1).getDelay().getValue(), is(120L)); // captured delay, not 50ms default
        assertThat(events.get(2).getDelay().getValue(), is(45L)); // captured delay, not 50ms default
    }

    @Test
    public void shouldFallBackToFixedDelayWhenNoPerChunkDelays() {
        // given
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n" +
            "data: third\n\n";

        // when -- null perChunkDelays = fallback
        List<SseEvent> events = parser.parse(sseText, null);

        // then
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(50L)); // fixed default
        assertThat(events.get(2).getDelay().getValue(), is(50L)); // fixed default
    }

    @Test
    public void shouldFallBackToFixedDelayWhenPerChunkDelaysTooShort() {
        // given -- only 2 delays but 3 events
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n" +
            "data: third\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 100L); // missing third

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(100L)); // captured
        assertThat(events.get(2).getDelay().getValue(), is(50L)); // fallback to default
    }

    @Test
    public void shouldHandleZeroCapturedDelay() {
        // given -- zero captured delay should result in no delay being set
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 0L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(2));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay(), nullValue()); // 0ms = no delay
    }

    @Test
    public void shouldHandlePerChunkDelaysLongerThanEvents() {
        // given -- more delays than events (extra delays are harmlessly ignored)
        SseBodyParser parser = new SseBodyParser(50);
        String sseText = "data: only\n\n";
        List<Long> perChunkDelays = Arrays.asList(0L, 100L, 200L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(1));
        assertThat(events.get(0).getDelay(), nullValue());
    }

    @Test
    public void shouldPreserveBackwardCompatibilityWithNoArgParse() {
        // given -- calling the original parse(String) method
        SseBodyParser parser = new SseBodyParser(50);
        String sseText =
            "data: first\n\n" +
            "data: second\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then -- same behaviour as before
        assertThat(events, hasSize(2));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(50L));
    }

    @Test
    public void shouldHandleRealisticCapturedTimingValues() {
        // given -- realistic inter-chunk delays from an LLM streaming response
        SseBodyParser parser = new SseBodyParser();
        String sseText =
            "event: message_start\n" +
            "data: {\"type\":\"message_start\"}\n\n" +
            "event: content_block_delta\n" +
            "data: {\"type\":\"delta\",\"text\":\"Hello\"}\n\n" +
            "event: content_block_delta\n" +
            "data: {\"type\":\"delta\",\"text\":\" world\"}\n\n" +
            "event: message_stop\n" +
            "data: {\"type\":\"message_stop\"}\n\n";
        // Realistic timing: initial latency then fast token streaming
        List<Long> perChunkDelays = Arrays.asList(0L, 350L, 15L, 12L);

        // when
        List<SseEvent> events = parser.parse(sseText, perChunkDelays);

        // then
        assertThat(events, hasSize(4));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(350L)); // initial latency
        assertThat(events.get(2).getDelay().getValue(), is(15L));  // fast token
        assertThat(events.get(3).getDelay().getValue(), is(12L));  // fast token
    }

    // the recording in #2946: an event, two heartbeats, an event and a heartbeat, one per chunk
    private static final String EVENTS_AND_HEARTBEATS = "event: a\ndata: 1\n\n:hb\n\n:hb\n\nevent: b\ndata: 2\n\n:hb\n\n";
    private static final List<Long> EVENTS_AND_HEARTBEATS_DELAYS = Arrays.asList(0L, 999L, 1001L, 1000L, 997L);

    @Test
    public void shouldAddTheDelaysOfSkippedCommentBlocksToTheNextEvent() {
        List<SseEvent> events = new SseBodyParser().parse(EVENTS_AND_HEARTBEATS, EVENTS_AND_HEARTBEATS_DELAYS);

        assertThat(events, hasSize(2));
        assertThat(events.get(0).getEvent(), is("a"));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getEvent(), is("b"));
        assertThat("b was sent 3 s after a", events.get(1).getDelay().getValue(), is(3000L));
        assertThat(events.get(1).getComment(), nullValue());
    }

    @Test
    public void shouldKeepEachCommentBlockAsACommentEventWithItsOwnDelay() {
        List<SseEvent> events = new SseBodyParser(SseBodyParser.DEFAULT_INTER_EVENT_DELAY_MS, true).parse(EVENTS_AND_HEARTBEATS, EVENTS_AND_HEARTBEATS_DELAYS);

        assertThat(events, hasSize(5));
        assertThat(events.get(0).getEvent(), is("a"));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1), is(SseEvent.sseEvent().withComment("hb").withDelay(java.util.concurrent.TimeUnit.MILLISECONDS, 999L)));
        assertThat(events.get(2), is(SseEvent.sseEvent().withComment("hb").withDelay(java.util.concurrent.TimeUnit.MILLISECONDS, 1001L)));
        assertThat(events.get(3).getEvent(), is("b"));
        assertThat(events.get(3).getDelay().getValue(), is(1000L));
        assertThat(events.get(4), is(SseEvent.sseEvent().withComment("hb").withDelay(java.util.concurrent.TimeUnit.MILLISECONDS, 997L)));
    }

    @Test
    public void shouldKeepCommentLinesInsideAnEventBlockOnThatEvent() {
        List<SseEvent> events = new SseBodyParser(SseBodyParser.DEFAULT_INTER_EVENT_DELAY_MS, true)
            .parse(": first\n:second \nevent: a\ndata: 1\n\n", null);

        assertThat(events, hasSize(1));
        assertThat("the text after each ':' is kept exactly", events.get(0),
            is(SseEvent.sseEvent().withComment(" first\nsecond ").withEvent("a").withData("1")));
    }

    @Test
    public void shouldAddTheDelayOfABlankBlockToTheNextEvent() {
        // a chunk of just a blank line between two events
        List<SseEvent> events = new SseBodyParser(SseBodyParser.DEFAULT_INTER_EVENT_DELAY_MS, true)
            .parse("data: a\n\n\n\ndata: b\n\n", Arrays.asList(0L, 400L, 600L));

        assertThat(events, hasSize(2));
        assertThat(events.get(1).getData(), is("b"));
        assertThat(events.get(1).getDelay().getValue(), is(1000L));
    }

    @Test
    public void shouldGiveTheFirstEventTheDelaysOfTheCommentsBeforeIt() {
        List<SseEvent> events = new SseBodyParser().parse(":hb\n\n:hb\n\ndata: first\n\n", Arrays.asList(250L, 500L, 500L));

        assertThat(events, hasSize(1));
        assertThat(events.get(0).getDelay().getValue(), is(1250L));
    }

    @Test
    public void shouldCapAReplayedDelay() {
        List<SseEvent> events = new SseBodyParser().parse("data: a\n\n:hb\n\ndata: b\n\n",
            Arrays.asList(Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE));

        assertThat(events.get(0).getDelay().getValue(), is(SseBodyParser.MAX_REPLAYED_DELAY_MS));
        assertThat(events.get(1).getDelay().getValue(), is(SseBodyParser.MAX_REPLAYED_DELAY_MS));
    }

    @Test
    public void shouldNotGiveSkippedCommentBlocksAFallbackDelayWithoutChunkTiming() {
        List<SseEvent> events = new SseBodyParser().parse("data: a\n\n:hb\n\n:hb\n\ndata: b\n\n", null);

        assertThat(events, hasSize(2));
        assertThat(events.get(1).getDelay().getValue(), is(50L));
    }

    @Test
    public void shouldReplayEventsThatShareAChunkTogether() {
        SseBodyParser parser = new SseBodyParser(50);
        String sseText = "data: a\n\ndata: b\n\ndata: c\n\n";

        List<SseEvent> events = parser.parse(sseText, Arrays.asList(10L, 700L), Arrays.asList(18, 9), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay().getValue(), is(10L));
        assertThat("b arrived in a's chunk", events.get(1).getDelay(), nullValue());
        assertThat(events.get(2).getDelay().getValue(), is(700L));
    }

    @Test
    public void shouldReplayAnEventSplitAcrossChunksWhenItsLastChunkArrived() {
        SseBodyParser parser = new SseBodyParser(50);
        String sseText = "data: hello\n\ndata: x\n\n";

        List<SseEvent> events = parser.parse(sseText, Arrays.asList(5L, 300L, 400L), Arrays.asList(9, 4, 9), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(events, hasSize(2));
        assertThat(events.get(0).getData(), is("hello"));
        assertThat(events.get(0).getDelay().getValue(), is(305L));
        assertThat(events.get(1).getDelay().getValue(), is(400L));
    }

    @Test
    public void shouldPlaceEventsByEncodedBytesNotCharacters() {
        SseBodyParser parser = new SseBodyParser(50);
        String sseText = "data: é\n\ndata: z\n\n";

        // "data: é\n\n" is 10 bytes in UTF-8 but 9 characters
        List<SseEvent> events = parser.parse(sseText, Arrays.asList(0L, 200L), Arrays.asList(10, 9), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(200L));
    }

    @Test
    public void shouldAddTheTimeOfASkippedCommentChunkToTheNextEventByChunkBytes() {
        SseBodyParser parser = new SseBodyParser(50);
        String sseText = ": ping\n\ndata: a\n\n";

        List<SseEvent> events = parser.parse(sseText, Arrays.asList(100L, 250L), Arrays.asList(8, 9), java.nio.charset.StandardCharsets.UTF_8);

        assertThat(events, hasSize(1));
        assertThat(events.get(0).getDelay().getValue(), is(350L));
    }

    @Test
    public void shouldFallBackToOneBlockPerChunkWhenTheChunkBytesDoNotCoverTheText() {
        SseBodyParser parser = new SseBodyParser(50);
        String sseText = "data: a\n\ndata: b\n\n";

        List<SseEvent> byBytes = parser.parse(sseText, Arrays.asList(0L, 120L), Arrays.asList(9, 3), java.nio.charset.StandardCharsets.UTF_8);
        List<SseEvent> byBlocks = parser.parse(sseText, Arrays.asList(0L, 120L));

        assertThat(byBytes, is(byBlocks));
        assertThat(byBytes.get(1).getDelay().getValue(), is(120L));
    }
}

package org.mockserver.llm;

import org.junit.Test;
import org.mockserver.model.Delay;
import org.mockserver.model.SseEvent;
import org.mockserver.model.StreamingPhysics;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.SseEvent.sseEvent;

public class StreamingPhysicsExpanderTest {

    @Test
    public void shouldReturnUnchangedEventsWhenPhysicsIsNull() {
        // given
        List<SseEvent> events = createTestEvents(5);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, null);

        // then
        assertThat(result, is(sameInstance(events)));
    }

    @Test
    public void shouldReturnUnchangedEventsWhenAllFieldsNull() {
        // given
        List<SseEvent> events = createTestEvents(5);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics();

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result, is(sameInstance(events)));
    }

    @Test
    public void shouldKeepTheCommentOfEachEvent() {
        List<SseEvent> events = new ArrayList<>();
        events.add(sseEvent().withComment("hb"));
        StreamingPhysics physics = StreamingPhysics.streamingPhysics().withTokensPerSecond(50);

        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        assertThat(result.get(0).getComment(), is("hb"));
    }

    @Test
    public void shouldUseTimeToFirstTokenForFirstEvent() {
        // given
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTimeToFirstToken(new Delay(TimeUnit.MILLISECONDS, 300))
            .withTokensPerSecond(50);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result.size(), is(3));
        assertThat(result.get(0).getDelay().getTimeUnit(), is(TimeUnit.MILLISECONDS));
        assertThat(result.get(0).getDelay().getValue(), is(300L));
    }

    @Test
    public void shouldUseTokensPerSecondForSubsequentEvents() {
        // given
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(100) // base delay = 10ms
            .withJitter(0.0);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        // First event delay = 0 (default timeToFirstToken)
        assertThat(result.get(0).getDelay().getValue(), is(0L));
        // Subsequent events: 1000/100 = 10ms with 0 jitter
        assertThat(result.get(1).getDelay().getValue(), is(10L));
        assertThat(result.get(2).getDelay().getValue(), is(10L));
    }

    @Test
    public void shouldProduceDeterministicDelaysWithZeroJitter() {
        // given
        List<SseEvent> events = createTestEvents(5);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50) // base delay = 20ms
            .withJitter(0.0)
            .withSeed(42L);

        // when
        List<SseEvent> result1 = StreamingPhysicsExpander.applyPhysics(events, physics);
        List<SseEvent> result2 = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — all subsequent events should have exactly 20ms delay
        for (int i = 1; i < result1.size(); i++) {
            assertThat(result1.get(i).getDelay().getValue(), is(20L));
            assertThat(result2.get(i).getDelay().getValue(), is(20L));
        }
    }

    @Test
    public void shouldProduceSameDelaysWithFixedSeedAndJitter() {
        // given
        List<SseEvent> events = createTestEvents(10);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50)
            .withJitter(0.2)
            .withSeed(12345L);

        // when
        List<SseEvent> result1 = StreamingPhysicsExpander.applyPhysics(events, physics);
        List<SseEvent> result2 = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — same seed → same delays
        for (int i = 0; i < result1.size(); i++) {
            assertThat(result1.get(i).getDelay().getValue(), is(result2.get(i).getDelay().getValue()));
        }
    }

    @Test
    public void shouldProduceDelaysWithinJitterBounds() {
        // given
        List<SseEvent> events = createTestEvents(100);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50) // base delay = 20ms
            .withJitter(0.2) // +-20% → [16, 24]
            .withSeed(42L);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — all subsequent events should have delays within +-20% of 20ms
        for (int i = 1; i < result.size(); i++) {
            long delay = result.get(i).getDelay().getValue();
            assertThat("delay " + delay + " at index " + i + " should be >= 16",
                delay, is(greaterThanOrEqualTo(16L)));
            assertThat("delay " + delay + " at index " + i + " should be <= 24",
                delay, is(lessThanOrEqualTo(24L)));
        }
    }

    @Test
    public void shouldPreservePacingAboveOneThousandTokensPerSecond() {
        // given — 2000 tokens/sec → base delay 0.5ms, which integer division would flatten to 0ms
        List<SseEvent> events = createTestEvents(101); // 100 token deltas after the first event
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(2000)
            .withJitter(0.0)
            .withSeed(42L);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — cumulative delay must not collapse to zero; 100 tokens at 0.5ms ≈ 50ms total
        long total = 0;
        for (int i = 1; i < result.size(); i++) {
            total += result.get(i).getDelay().getValue();
        }
        assertThat("total streaming delay should not be flattened to zero", total, is(greaterThan(0L)));
        assertThat("100 tokens at 0.5ms each should accumulate to ~50ms", total, is(50L));
    }

    @Test
    public void shouldPaceFasterStreamAtRoughlyHalfTheDelayOfSlowerStream() {
        // given — same token count, 2000 tok/s vs 1000 tok/s
        List<SseEvent> events = createTestEvents(101);
        StreamingPhysics fast = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(2000).withJitter(0.0).withSeed(7L);
        StreamingPhysics slow = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(1000).withJitter(0.0).withSeed(7L);

        // when
        long fastTotal = totalDelay(StreamingPhysicsExpander.applyPhysics(events, fast));
        long slowTotal = totalDelay(StreamingPhysicsExpander.applyPhysics(events, slow));

        // then — 1000 tok/s → 1ms each → 100ms; 2000 tok/s → 0.5ms each → 50ms (half)
        assertThat(slowTotal, is(100L));
        assertThat(fastTotal, is(50L));
    }

    @Test
    public void shouldEmitSubMillisecondPacingOnAlternateTokens() {
        // given — 2000 tok/s with no jitter carries 0.5ms forward, emitting 1ms every other token
        List<SseEvent> events = createTestEvents(5);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(2000).withJitter(0.0).withSeed(1L);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — the running carry (not the cumulative total) is rounded per step:
        //   token1: carry 0 + 0.5 = 0.5 → round 1 → carry -0.5
        //   token2: carry -0.5 + 0.5 = 0.0 → round 0 → carry 0.0
        //   token3: carry 0 + 0.5 = 0.5 → round 1 → carry -0.5
        //   token4: carry -0.5 + 0.5 = 0.0 → round 0 → carry 0.0
        // emitting 1, 0, 1, 0 (averaging 0.5ms per token)
        assertThat(result.get(1).getDelay().getValue(), is(1L));
        assertThat(result.get(2).getDelay().getValue(), is(0L));
        assertThat(result.get(3).getDelay().getValue(), is(1L));
        assertThat(result.get(4).getDelay().getValue(), is(0L));
    }

    @Test
    public void shouldCarryFractionalDelayForNonIntegralSubOneThousandRate() {
        // given — 3 tok/s → base delay 1000/3 = 333.333ms.
        // Old integer division truncated to a steady 333ms/token (drifting 0.33ms slow per token);
        // the carry now alternates 333/334 so cumulative timing converges to N * 333.33ms.
        int tokenCount = 30; // 30 token deltas after the first event
        List<SseEvent> events = createTestEvents(tokenCount + 1);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(3)
            .withJitter(0.0)
            .withSeed(42L);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — per-event delays are 333 or 334 (no longer a flat 333)
        boolean sawThreeThirtyFour = false;
        for (int i = 1; i < result.size(); i++) {
            long delay = result.get(i).getDelay().getValue();
            assertThat(delay, is(anyOf(equalTo(333L), equalTo(334L))));
            if (delay == 334L) {
                sawThreeThirtyFour = true;
            }
        }
        assertThat("carry should emit 334ms steps, not a flat 333ms", sawThreeThirtyFour, is(true));

        // cumulative total converges to tokenCount * (1000/3), within 1ms of the ideal
        long total = totalDelay(result);
        double ideal = tokenCount * (1000.0 / 3.0);
        assertThat(Math.abs(total - ideal), is(lessThanOrEqualTo(1.0)));
    }

    @Test
    public void shouldPreserveEventDataWhenApplyingPhysics() {
        // given
        List<SseEvent> events = new ArrayList<>();
        events.add(sseEvent().withEvent("test_event").withData("test_data").withId("id1").withRetry(5000));
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result.get(0).getEvent(), is("test_event"));
        assertThat(result.get(0).getData(), is("test_data"));
        assertThat(result.get(0).getId(), is("id1"));
        assertThat(result.get(0).getRetry(), is(5000));
    }

    @Test
    public void shouldNotMutateInputList() {
        // given
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withTokensPerSecond(50);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then
        assertThat(result, is(not(sameInstance(events))));
        // original events should still have no delay
        for (SseEvent event : events) {
            assertThat(event.getDelay(), is(nullValue()));
        }
    }

    @Test
    public void shouldDefaultTokensPerSecondTo50() {
        // given — only seed is set, so physics is non-null but tokensPerSecond is null
        List<SseEvent> events = createTestEvents(3);
        StreamingPhysics physics = StreamingPhysics.streamingPhysics()
            .withSeed(42L);

        // when
        List<SseEvent> result = StreamingPhysicsExpander.applyPhysics(events, physics);

        // then — base delay should be 1000/50 = 20ms
        assertThat(result.get(1).getDelay().getValue(), is(20L));
    }

    private long totalDelay(List<SseEvent> events) {
        long total = 0;
        for (int i = 1; i < events.size(); i++) {
            total += events.get(i).getDelay().getValue();
        }
        return total;
    }

    private List<SseEvent> createTestEvents(int count) {
        List<SseEvent> events = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            events.add(sseEvent().withData("event-" + i));
        }
        return events;
    }
}

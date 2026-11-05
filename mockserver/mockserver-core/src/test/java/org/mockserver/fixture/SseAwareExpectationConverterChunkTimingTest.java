package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;

import java.util.Arrays;
import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for per-chunk replay timing in {@link SseAwareExpectationConverter}.
 * These verify the end-to-end flow: chunk delay header is parsed, delays are
 * applied to the resulting SSE events, and the header itself is stripped from
 * the output.
 */
public class SseAwareExpectationConverterChunkTimingTest {

    private final SseAwareExpectationConverter converter = new SseAwareExpectationConverter();

    @Test
    public void shouldApplyCapturedChunkDelaysFromHeader() {
        // given -- a recorded response with per-chunk timing header
        String sseBody =
            "event: message_start\n" +
            "data: {\"type\":\"message_start\"}\n\n" +
            "event: content_block_delta\n" +
            "data: {\"type\":\"delta\"}\n\n" +
            "event: message_stop\n" +
            "data: {\"type\":\"message_stop\"}\n\n";

        Expectation original = new Expectation(
            request().withMethod("POST").withPath("/v1/messages"),
            Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-chunk-delays-ms", "0,350,15")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then -- SSE events should have captured timing
        assertThat(converted[0].getHttpSseResponse(), notNullValue());
        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        List<SseEvent> events = sseResponse.getEvents();
        assertThat(events, hasSize(3));

        assertThat(events.get(0).getDelay(), nullValue()); // first: no delay
        assertThat(events.get(1).getDelay().getValue(), is(350L)); // captured delay
        assertThat(events.get(2).getDelay().getValue(), is(15L)); // captured delay
    }

    @Test
    public void shouldFallBackToDefaultDelayWhenNoChunkTimingHeader() {
        // given -- no x-mockserver-chunk-delays-ms header
        String sseBody =
            "data: first\n\n" +
            "data: second\n\n";

        Expectation original = new Expectation(
            request(), Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then -- should use default 50ms delay
        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        List<SseEvent> events = sseResponse.getEvents();
        assertThat(events, hasSize(2));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(50L)); // default
    }

    @Test
    public void shouldStripChunkDelaysHeaderFromSseResponse() {
        // given
        String sseBody = "data: test\n\n";

        Expectation original = new Expectation(
            request(), Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-chunk-delays-ms", "0")
                .withHeader("X-Custom", "keep-me")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then -- chunk delays header should be stripped
        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        boolean foundChunkDelays = false;
        boolean foundCustom = false;
        if (sseResponse.getHeaders() != null) {
            for (org.mockserver.model.Header header : sseResponse.getHeaders().getEntries()) {
                String name = header.getName().getValue().toLowerCase();
                if (name.equals("x-mockserver-chunk-delays-ms")) {
                    foundChunkDelays = true;
                }
                if (name.equals("x-custom")) {
                    foundCustom = true;
                }
            }
        }
        assertThat("chunk delays header should be stripped", foundChunkDelays, is(false));
        assertThat("custom header should be preserved", foundCustom, is(true));
    }

    @Test
    public void shouldHandleMalformedChunkDelaysHeader() {
        // given -- malformed header should fall back to default
        String sseBody =
            "data: first\n\n" +
            "data: second\n\n";

        Expectation original = new Expectation(
            request(), Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-chunk-delays-ms", "0,not-a-number,15")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then -- should fall back to default 50ms
        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        List<SseEvent> events = sseResponse.getEvents();
        assertThat(events.get(1).getDelay().getValue(), is(50L)); // fallback
    }

    @Test
    public void shouldParseChunkDelaysHeaderCorrectly() {
        // Test the static parser directly
        assertThat(SseAwareExpectationConverter.parseChunkDelaysHeader(null), is(nullValue()));
        assertThat(SseAwareExpectationConverter.parseChunkDelaysHeader(""), is(nullValue()));
        assertThat(SseAwareExpectationConverter.parseChunkDelaysHeader("0,100,50"),
            is(Arrays.asList(0L, 100L, 50L)));
        assertThat(SseAwareExpectationConverter.parseChunkDelaysHeader("0"), is(Arrays.asList(0L)));
        // Malformed
        assertThat(SseAwareExpectationConverter.parseChunkDelaysHeader("0,abc"), is(nullValue()));
    }

    @Test
    public void shouldHandlePartialChunkDelaysCoverage() {
        // given -- fewer delays than events
        String sseBody =
            "data: first\n\n" +
            "data: second\n\n" +
            "data: third\n\n";

        Expectation original = new Expectation(
            request(), Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-chunk-delays-ms", "0,200")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then -- first covered delay used, rest fall back to default
        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        List<SseEvent> events = sseResponse.getEvents();
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(200L)); // captured
        assertThat(events.get(2).getDelay().getValue(), is(50L)); // fallback
    }
    @Test
    public void shouldNotReplayTheLogOnlyClosedByClientHeader() {
        Expectation original = new Expectation(
            request().withMethod("POST").withPath("/v1/messages"),
            Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-chunk-delays-ms", "0,100")
                .withHeader("x-mockserver-stream-closed-by-client", "true")
                .withHeader("x-kept", "yes")
                .withBody("data: one\n\ndata: two\n\n")
        );

        Expectation[] converted = converter.convert(new Expectation[]{original});

        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        assertThat(sseResponse, notNullValue());
        assertThat(sseResponse.getHeaders().containsEntry("x-mockserver-stream-closed-by-client"), is(false));
        assertThat(sseResponse.getHeaders().containsEntry("x-kept"), is(true));
    }

    @Test
    public void shouldNotReplayTheLogOnlyClosedByClientHeaderOnTheTruncatedFallback() {
        Expectation original = new Expectation(
            request().withMethod("POST").withPath("/v1/messages"),
            Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-stream-truncated", "true")
                .withHeader("x-mockserver-stream-closed-by-client", "true")
                .withBody("data: one\n\n")
        );

        Expectation[] converted = converter.convert(new Expectation[]{original});

        assertThat(converted[0].getHttpResponse().containsHeader("x-mockserver-stream-closed-by-client"), is(false));
    }
}

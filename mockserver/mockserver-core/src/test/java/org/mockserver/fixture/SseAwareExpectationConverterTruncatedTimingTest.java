package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A truncated {@code text/event-stream} recording that kept its chunk timing replays the events captured before the
 * cut with their timing; any other truncated recording keeps the static fallback.
 */
public class SseAwareExpectationConverterTruncatedTimingTest {

    private static final String WARNING_HEADER = "x-mockserver-fixture-warning";

    private final SseAwareExpectationConverter converter = new SseAwareExpectationConverter();

    private static Expectation recorded(HttpResponse response) {
        return new Expectation(request().withMethod("POST").withPath("/v1/messages"), Times.once(), TimeToLive.unlimited(), 0)
            .thenRespond(response);
    }

    private static HttpResponse truncatedSse(String body, String chunkDelays) {
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("x-request-id", "abc")
            .withHeader("x-mockserver-streamed", "true")
            .withHeader("x-mockserver-stream-truncated", "true")
            .withBody(body);
        if (chunkDelays != null) {
            response.withHeader("x-mockserver-chunk-delays-ms", chunkDelays);
        }
        return response;
    }

    private static String header(HttpSseResponse sse, String name) {
        List<String> values = sse.getHeaders() == null ? List.of() : sse.getHeaders().getValues(name);
        return values.isEmpty() ? null : values.get(0);
    }

    private static Long delayOf(SseEvent event) {
        return event.getDelay() == null ? null : event.getDelay().getValue();
    }

    @Test
    public void shouldReplayTruncatedSseWithDelaysAsTimedEventsDroppingThePartialEvent() {
        Expectation[] converted = converter.convert(new Expectation[]{
            recorded(truncatedSse("data: one\n\ndata: two\n\ndata: three\n\ndata: {\"cut\":", "0,120,240,360"))
        });

        HttpSseResponse sse = converted[0].getHttpSseResponse();
        assertThat(sse, notNullValue());
        assertThat(converted[0].getHttpResponse(), nullValue());
        List<SseEvent> events = sse.getEvents();
        assertThat(events, hasSize(3));
        assertThat(events.get(0).getData(), is("one"));
        assertThat(events.get(1).getData(), is("two"));
        assertThat(events.get(2).getData(), is("three"));
        assertThat("each event keeps its own chunk's delay", delayOf(events.get(0)), nullValue());
        assertThat(delayOf(events.get(1)), is(120L));
        assertThat(delayOf(events.get(2)), is(240L));
        assertThat(header(sse, WARNING_HEADER), allOf(containsString("truncated"), containsString("cut off part way")));
        assertThat(header(sse, "x-request-id"), is("abc"));
        assertThat(sse.getHeaders().containsEntry("x-mockserver-streamed"), is(false));
        assertThat(sse.getHeaders().containsEntry("x-mockserver-stream-truncated"), is(false));
        assertThat(sse.getHeaders().containsEntry("x-mockserver-chunk-delays-ms"), is(false));
        assertThat(sse.getCloseConnection(), is(true));
        assertThat(converted[0].getTimes().isUnlimited(), is(true));
    }

    @Test
    public void shouldReplayEveryEventWhenTheCutFellBetweenEvents() {
        Expectation[] converted = converter.convert(new Expectation[]{
            recorded(truncatedSse("data: one\r\n\r\ndata: two\r\n\r\n", "0,75"))
        });

        HttpSseResponse sse = converted[0].getHttpSseResponse();
        assertThat(sse, notNullValue());
        assertThat(sse.getEvents(), hasSize(2));
        assertThat(delayOf(sse.getEvents().get(1)), is(75L));
        assertThat(header(sse, WARNING_HEADER), allOf(containsString("truncated"), not(containsString("cut off part way"))));
    }

    @Test
    public void shouldDropAPartialEventThatHasEndedItsFirstLine() {
        Expectation[] converted = converter.convert(new Expectation[]{
            recorded(truncatedSse("event: a\ndata: one\n\nevent: b\ndata: tw\n", "0,50"))
        });

        HttpSseResponse sse = converted[0].getHttpSseResponse();
        assertThat(sse.getEvents(), hasSize(1));
        assertThat(sse.getEvents().get(0).getEvent(), is("a"));
        assertThat(header(sse, WARNING_HEADER), containsString("cut off part way"));
    }

    @Test
    public void shouldKeepTheStaticFallbackWhenOnlyAPartialEventWasCaptured() {
        Expectation[] converted = converter.convert(new Expectation[]{
            recorded(truncatedSse("data: {\"cut\":", "0"))
        });

        assertThat(converted[0].getHttpSseResponse(), nullValue());
        assertThat(converted[0].getHttpResponse().getFirstHeader(WARNING_HEADER), containsString("truncated"));
    }

    @Test
    public void shouldKeepTheStaticFallbackForATruncatedNonSseRecordingWithDelays() {
        HttpResponse plainText = response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/plain")
            .withHeader("x-mockserver-streamed", "true")
            .withHeader("x-mockserver-stream-truncated", "true")
            .withHeader("x-mockserver-chunk-delays-ms", "0,10")
            .withBody("data: one\n\ndata: tw");

        Expectation[] converted = converter.convert(new Expectation[]{recorded(plainText)});

        assertThat("only text/event-stream replays with timing, whatever the body looks like", converted[0].getHttpSseResponse(), nullValue());
        HttpResponse fallback = converted[0].getHttpResponse();
        assertThat(fallback.getBodyAsString(), is("data: one\n\ndata: tw"));
        assertThat(fallback.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
        assertThat(fallback.getFirstHeader(WARNING_HEADER), containsString("truncated"));
    }

    @Test
    public void shouldConvertATruncatedRecordingWithoutDelaysExactlyAsBefore() {
        HttpResponse recordedResponse = truncatedSse("data: one\n\ndata: tw", null);

        Expectation[] converted = converter.convert(new Expectation[]{recorded(recordedResponse)});

        HttpResponse expected = response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("x-request-id", "abc")
            .withBody("data: one\n\ndata: tw")
            .withHeader(WARNING_HEADER, "SSE stream was truncated during capture; replay may be incomplete. " +
                "Increase maxStreamingCaptureBytes for full capture.");
        assertThat(converted[0].getHttpSseResponse(), nullValue());
        assertThat(converted[0].getHttpResponse(), is(expected));
    }
}

package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A converted fixture replays each event with the delay of its own recorded chunk, waits the recorded time before the
 * first chunk, keeps comments only when asked, and never carries the log-only recording headers to a client.
 */
public class SseAwareExpectationConverterRecordingHeadersTest {

    private static final String[] RECORDING_HEADERS = {
        "x-mockserver-streamed",
        "x-mockserver-stream-truncated",
        "x-mockserver-chunk-delays-ms",
        "x-mockserver-first-chunk-delay-ms",
        "x-mockserver-stream-closed-by-client",
        "x-mockserver-stream-closed-by-mockserver-stop",
        "x-mockserver-response-time-ms"
    };

    private static Expectation recorded(HttpResponse response) {
        return new Expectation(request().withMethod("GET").withPath("/stream"), Times.once(), TimeToLive.unlimited(), 0)
            .thenRespond(response);
    }

    // the recording in #2946: an event, two heartbeats, an event and a heartbeat, one per chunk
    private static HttpResponse heartbeatRecording() {
        return response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("x-mockserver-streamed", "true")
            .withHeader("x-mockserver-chunk-delays-ms", "0,999,1001,1000,997")
            .withHeader("x-mockserver-first-chunk-delay-ms", "2000")
            .withHeader("x-mockserver-response-time-ms", "6001")
            .withHeader("X-Request-Id", "req-1")
            .withBody("event: a\ndata: 1\n\n:hb\n\n:hb\n\nevent: b\ndata: 2\n\n:hb\n\n");
    }

    @Test
    public void shouldGiveEachEventItsOwnChunkDelayAndTheFirstEventTheWaitBeforeTheFirstChunk() {
        Expectation[] converted = new SseAwareExpectationConverter().convert(new Expectation[]{recorded(heartbeatRecording())});

        List<SseEvent> events = converted[0].getHttpSseResponse().getEvents();
        assertThat(events, hasSize(2));
        assertThat(events.get(0), is(SseEvent.sseEvent().withEvent("a").withData("1").withDelay(TimeUnit.MILLISECONDS, 2000L)));
        assertThat(events.get(1), is(SseEvent.sseEvent().withEvent("b").withData("2").withDelay(TimeUnit.MILLISECONDS, 3000L)));
    }

    @Test
    public void shouldKeepCommentsAsCommentEventsWhenAsked() {
        Expectation[] converted = new SseAwareExpectationConverter(SseBodyParser.DEFAULT_INTER_EVENT_DELAY_MS, true)
            .convert(new Expectation[]{recorded(heartbeatRecording())});

        List<SseEvent> events = converted[0].getHttpSseResponse().getEvents();
        assertThat(events, hasSize(5));
        assertThat(events.get(0).getDelay().getValue(), is(2000L));
        assertThat(events.get(1), is(SseEvent.sseEvent().withComment("hb").withDelay(TimeUnit.MILLISECONDS, 999L)));
        assertThat(events.get(2), is(SseEvent.sseEvent().withComment("hb").withDelay(TimeUnit.MILLISECONDS, 1001L)));
        assertThat(events.get(3), is(SseEvent.sseEvent().withEvent("b").withData("2").withDelay(TimeUnit.MILLISECONDS, 1000L)));
        assertThat(events.get(4), is(SseEvent.sseEvent().withComment("hb").withDelay(TimeUnit.MILLISECONDS, 997L)));
    }

    @Test
    public void shouldIgnoreAMalformedFirstChunkDelay() {
        HttpResponse response = heartbeatRecording();
        response.replaceHeader(new org.mockserver.model.Header("x-mockserver-first-chunk-delay-ms", "soon"));

        Expectation[] converted = new SseAwareExpectationConverter().convert(new Expectation[]{recorded(response)});

        List<SseEvent> events = converted[0].getHttpSseResponse().getEvents();
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay().getValue(), is(3000L));
    }

    @Test
    public void shouldCapARecordedWaitSoAFixtureCannotStallAStream() {
        HttpResponse response = heartbeatRecording();
        response.replaceHeader(new org.mockserver.model.Header("x-mockserver-first-chunk-delay-ms", "99999999999"));
        response.replaceHeader(new org.mockserver.model.Header("x-mockserver-chunk-delays-ms", "0,999,1001,99999999999,997"));

        List<SseEvent> events = new SseAwareExpectationConverter().convert(new Expectation[]{recorded(response)})[0].getHttpSseResponse().getEvents();

        assertThat(events.get(0).getDelay().getValue(), is(SseBodyParser.MAX_REPLAYED_DELAY_MS));
        assertThat(events.get(1).getDelay().getValue(), is(SseBodyParser.MAX_REPLAYED_DELAY_MS));
    }

    @Test
    public void shouldNotCopyRecordingHeadersIntoAnSseFixture() {
        HttpResponse response = heartbeatRecording()
            .withHeader("x-mockserver-stream-closed-by-client", "true")
            .withHeader("x-mockserver-stream-closed-by-mockserver-stop", "true");

        HttpSseResponse sseResponse = new SseAwareExpectationConverter().convert(new Expectation[]{recorded(response)})[0].getHttpSseResponse();

        for (String header : RECORDING_HEADERS) {
            assertThat(header, sseResponse.getHeaders().containsEntry(header), is(false));
        }
        assertThat(sseResponse.getHeaders().containsEntry("X-Request-Id"), is(true));
    }

    @Test
    public void shouldRemoveRecordingHeadersFromATruncatedFallback() {
        HttpResponse response = response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("x-mockserver-streamed", "true")
            .withHeader("x-mockserver-stream-truncated", "true")
            .withHeader("x-mockserver-response-time-ms", "12")
            .withBody("event: partial\ndata: {\"incomplete\":");

        HttpResponse fallback = new SseAwareExpectationConverter().convert(new Expectation[]{recorded(response)})[0].getHttpResponse();

        for (String header : RECORDING_HEADERS) {
            assertThat(header, fallback.containsHeader(header), is(false));
        }
        assertThat(fallback.containsHeader("x-mockserver-fixture-warning"), is(true));
    }

    @Test
    public void shouldRemoveTheResponseTimeHeaderFromANonStreamingFixture() {
        Expectation original = recorded(response()
            .withStatusCode(200)
            .withHeader("Content-Type", "application/json")
            .withHeader("x-mockserver-response-time-ms", "42")
            .withBody("{\"ok\":true}"));

        Expectation converted = new SseAwareExpectationConverter().convert(new Expectation[]{original})[0];

        assertThat(converted.getHttpResponse().containsHeader("x-mockserver-response-time-ms"), is(false));
        assertThat(converted.getHttpResponse().getFirstHeader("Content-Type"), is("application/json"));
        assertThat(converted.getHttpResponse().getBodyAsString(), is("{\"ok\":true}"));
        assertThat(converted.getHttpRequest(), is(original.getHttpRequest()));
        assertThat(converted.getTimes(), is(original.getTimes()));
        assertThat("the recording itself is not modified", original.getHttpResponse().containsHeader("x-mockserver-response-time-ms"), is(true));
    }

    @Test
    public void shouldReturnANonStreamingExpectationWithoutRecordingHeadersUnchanged() {
        Expectation original = recorded(response().withStatusCode(200).withBody("plain"));

        assertThat(new SseAwareExpectationConverter().convert(new Expectation[]{original})[0], is(sameInstance(original)));
    }
}

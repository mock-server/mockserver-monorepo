package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Delay;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpSseResponse;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.nullValue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A converted fixture ends its stream when the recording did: the close delay is the recorded total less the delays
 * the events replay with. A stream with no timed end (truncated, closed by the client or by MockServer's stop, or
 * recorded without the timing headers) gets none, so it ends after its last event as before.
 */
public class SseAwareExpectationConverterCloseDelayTest {

    private static Expectation recorded(HttpResponse response) {
        return new Expectation(request().withMethod("GET").withPath("/stream"), Times.once(), TimeToLive.unlimited(), 0)
            .thenRespond(response);
    }

    // the recording in #2947: a 2 s wait, event a, event b at 5 s, a heartbeat at 8 s, and the end at 15 s
    private static HttpResponse issueRecording() {
        return response()
            .withStatusCode(200)
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("x-mockserver-streamed", "true")
            .withHeader("x-mockserver-chunk-delays-ms", "0,3000,3001")
            .withHeader("x-mockserver-first-chunk-delay-ms", "2000")
            .withHeader("x-mockserver-response-time-ms", "15003")
            .withBody("event: a\ndata: 1\n\nevent: b\ndata: 2\n\n:hb\n\n");
    }

    private static HttpSseResponse convert(HttpResponse recording, boolean keepSseComments) {
        return new SseAwareExpectationConverter(SseBodyParser.DEFAULT_INTER_EVENT_DELAY_MS, keepSseComments)
            .convert(new Expectation[]{recorded(recording)})[0].getHttpSseResponse();
    }

    @Test
    public void shouldEndTheReplayWhenTheRecordedStreamEnded() {
        HttpSseResponse sse = convert(issueRecording(), true);

        // a at 2000, b at 5000, the heartbeat at 8001, then 7002 until the end at 15003
        assertThat(sse.getEvents().size(), is(3));
        assertThat(sse.getCloseDelay(), is(Delay.milliseconds(7002)));
    }

    @Test
    public void shouldAddTheTimeOfDroppedTrailingCommentsToTheCloseDelay() {
        HttpSseResponse sse = convert(issueRecording(), false);

        // the dropped heartbeat replays nothing, so its 3001 ms belongs to the wait after b
        assertThat(sse.getEvents().size(), is(2));
        assertThat(sse.getCloseDelay(), is(Delay.milliseconds(10003)));
    }

    @Test
    public void shouldGiveNoCloseDelayWhenTheStreamEndedWithItsLastEvent() {
        HttpResponse recording = issueRecording();
        recording.replaceHeader(new Header("x-mockserver-response-time-ms", "8001"));

        assertThat(convert(recording, true).getCloseDelay(), is(nullValue()));
    }

    @Test
    public void shouldGiveNoCloseDelayToAStreamTheClientClosed() {
        HttpResponse recording = issueRecording().withHeader("x-mockserver-stream-closed-by-client", "true");

        assertThat(convert(recording, true).getCloseDelay(), is(nullValue()));
    }

    @Test
    public void shouldGiveNoCloseDelayToAStreamMockServerClosedWhenItStopped() {
        HttpResponse recording = issueRecording().withHeader("x-mockserver-stream-closed-by-mockserver-stop", "true");

        HttpSseResponse sse = convert(recording, true);

        assertThat(sse.getEvents().size(), is(3));
        assertThat(sse.getCloseDelay(), is(nullValue()));
        assertThat(sse.getKeepStreamOpen(), is(nullValue()));
    }

    @Test
    public void shouldGiveNoCloseDelayToATruncatedStream() {
        HttpResponse recording = issueRecording().withHeader("x-mockserver-stream-truncated", "true");

        HttpSseResponse sse = convert(recording, true);

        assertThat("the truncated stream still replays with its timing", sse.getEvents().size(), is(3));
        assertThat(sse.getCloseDelay(), is(nullValue()));
    }

    @Test
    public void shouldGiveNoCloseDelayWithoutTheFirstChunkDelay() {
        // before the first-chunk delay was recorded, the wait before the first chunk is in the total too
        HttpResponse recording = issueRecording();
        recording.removeHeader("x-mockserver-first-chunk-delay-ms");

        assertThat(convert(recording, true).getCloseDelay(), is(nullValue()));
    }

    @Test
    public void shouldGiveNoCloseDelayWithoutChunkTiming() {
        HttpResponse recording = issueRecording();
        recording.removeHeader("x-mockserver-chunk-delays-ms");

        assertThat(convert(recording, true).getCloseDelay(), is(nullValue()));
    }

    @Test
    public void shouldGiveNoCloseDelayForAMalformedResponseTime() {
        HttpResponse recording = issueRecording();
        recording.replaceHeader(new Header("x-mockserver-response-time-ms", "late"));

        assertThat(convert(recording, true).getCloseDelay(), is(nullValue()));
    }

    @Test
    public void shouldCapTheCloseDelayAtTheLongestReplayedDelay() {
        HttpResponse recording = issueRecording();
        recording.replaceHeader(new Header("x-mockserver-response-time-ms", String.valueOf(10L * SseBodyParser.MAX_REPLAYED_DELAY_MS)));

        assertThat(convert(recording, true).getCloseDelay(), is(Delay.milliseconds(SseBodyParser.MAX_REPLAYED_DELAY_MS)));
    }
}

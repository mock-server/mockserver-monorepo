package org.mockserver.fixture;

import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.mockserver.model.HttpSseResponse.sseResponse;

/**
 * Converts a recorded {@link Expectation} (from a {@code FORWARDED_REQUEST} log entry) into an
 * SSE-aware replay expectation when the forwarded response was a streaming response.
 * <p>
 * Detection is based on:
 * <ul>
 *   <li>The {@code x-mockserver-streamed} header being present (set by the streaming relay)</li>
 *   <li>The {@code Content-Type} header containing {@code text/event-stream}</li>
 * </ul>
 * <p>
 * When a streaming response is detected and the capture was not truncated, the response body
 * is parsed into {@link SseEvent} objects and the expectation's response action is replaced
 * with an {@link HttpSseResponse}.
 * <p>
 * When the capture was truncated ({@code x-mockserver-stream-truncated} header present) but kept its
 * chunk timing ({@code x-mockserver-chunk-delays-ms}, recorded with {@code recordTruncatedStreamTiming}) and is
 * {@code text/event-stream}, the events captured before the cut are replayed with their timing as an
 * {@link HttpSseResponse}; an event cut off part way is dropped, and the {@code x-mockserver-fixture-warning}
 * header says the stream was truncated. Any other truncated capture falls back to a static response with that
 * warning header, since its body is incomplete and has no timing to replay.
 * <p>
 * Non-streaming expectations are returned unchanged.
 */
public class SseAwareExpectationConverter {

    private static final String STREAMED_HEADER = "x-mockserver-streamed";
    private static final String TRUNCATED_HEADER = "x-mockserver-stream-truncated";
    private static final String CHUNK_DELAYS_HEADER = "x-mockserver-chunk-delays-ms";
    private static final String WARNING_HEADER = "x-mockserver-fixture-warning";
    private static final Pattern EVENT_BOUNDARY = Pattern.compile("\\r?\\n\\r?\\n");

    private final SseBodyParser sseBodyParser;

    public SseAwareExpectationConverter() {
        this.sseBodyParser = new SseBodyParser();
    }

    public SseAwareExpectationConverter(long interEventDelayMs) {
        this.sseBodyParser = new SseBodyParser(interEventDelayMs);
    }

    /**
     * Convert an array of expectations, making streaming responses SSE-aware.
     *
     * @param expectations the recorded expectations
     * @return expectations with SSE responses where applicable
     */
    public Expectation[] convert(Expectation[] expectations) {
        if (expectations == null) {
            return new Expectation[0];
        }
        Expectation[] result = new Expectation[expectations.length];
        for (int i = 0; i < expectations.length; i++) {
            result[i] = convertSingle(expectations[i]);
        }
        return result;
    }

    private Expectation convertSingle(Expectation expectation) {
        HttpResponse response = expectation.getHttpResponse();
        if (response == null) {
            return expectation;
        }

        // Check if this was a streamed response
        String streamedValue = response.getFirstHeader(STREAMED_HEADER);
        if (!"true".equalsIgnoreCase(streamedValue)) {
            // Also check content type as a fallback
            String contentType = response.getFirstHeader("Content-Type");
            if (contentType == null || !contentType.toLowerCase().contains("text/event-stream")) {
                return expectation;
            }
        }

        // Check for truncation
        String truncatedValue = response.getFirstHeader(TRUNCATED_HEADER);
        if ("true".equalsIgnoreCase(truncatedValue)) {
            Expectation timedReplay = createTruncatedTimedReplay(expectation, response);
            return timedReplay != null ? timedReplay : createTruncatedFallback(expectation, response);
        }

        // Parse the SSE body, using per-chunk timing if available
        String body = response.getBodyAsText();
        if (body == null || body.isEmpty()) {
            return expectation;
        }

        List<Long> perChunkDelays = parseChunkDelaysHeader(response.getFirstHeader(CHUNK_DELAYS_HEADER));
        List<SseEvent> events = sseBodyParser.parse(body, perChunkDelays);
        if (events.isEmpty()) {
            return expectation;
        }

        return sseExpectation(expectation, response, events, null);
    }

    /**
     * A truncated {@code text/event-stream} capture that kept its chunk timing replays the events that ended before
     * the cut, with their timing. An event the cut ended part way through is dropped; dropping only the last event
     * keeps each earlier event at its own index in the chunk delays.
     *
     * @return the timed replay, or null when the capture is not SSE, has no chunk timing, or has no complete event
     */
    private Expectation createTruncatedTimedReplay(Expectation expectation, HttpResponse response) {
        String contentType = response.getFirstHeader("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("text/event-stream")) {
            return null;
        }
        List<Long> perChunkDelays = parseChunkDelaysHeader(response.getFirstHeader(CHUNK_DELAYS_HEADER));
        String body = response.getBodyAsText();
        if (perChunkDelays == null || body == null || body.isEmpty()) {
            return null;
        }
        int completeEventsEnd = 0;
        Matcher boundary = EVENT_BOUNDARY.matcher(body);
        while (boundary.find()) {
            completeEventsEnd = boundary.end();
        }
        boolean partialEventDropped = !body.substring(completeEventsEnd).trim().isEmpty();
        List<SseEvent> events = sseBodyParser.parse(body.substring(0, completeEventsEnd), perChunkDelays);
        if (events.isEmpty()) {
            return null;
        }
        return sseExpectation(expectation, response, events,
            "SSE stream was truncated during capture; replaying the " + events.size() + (events.size() == 1 ? " event" : " events") + " captured before it ended, with their recorded timing" +
                (partialEventDropped ? ", without the last event, which was cut off part way" : ""));
    }

    private Expectation sseExpectation(Expectation expectation, HttpResponse response, List<SseEvent> events, String warning) {
        HttpSseResponse sseResponse = sseResponse()
            .withStatusCode(response.getStatusCode())
            .withEvents(events)
            .withCloseConnection(true);

        // Copy non-internal headers to the SSE response
        if (response.getHeaderList() != null) {
            for (Header header : response.getHeaderList()) {
                String name = header.getName().getValue();
                String lowerName = name.toLowerCase();
                // Skip internal headers and headers the SSE handler sets automatically
                if (lowerName.equals(STREAMED_HEADER)
                    || lowerName.equals(TRUNCATED_HEADER)
                    || lowerName.equals(CHUNK_DELAYS_HEADER)
                    || lowerName.equals(StreamingBody.CLOSED_BY_CLIENT_HEADER)
                    || lowerName.equals("content-type")
                    || lowerName.equals("transfer-encoding")
                    || lowerName.equals("cache-control")
                    || lowerName.equals("connection")) {
                    continue;
                }
                sseResponse.withHeader(header);
            }
        }
        if (warning != null) {
            sseResponse.withHeader(WARNING_HEADER, warning);
        }

        return new Expectation(
            expectation.getHttpRequest(),
            Times.unlimited(),
            TimeToLive.unlimited(),
            expectation.getPriority()
        ).thenRespondWithSse(sseResponse);
    }

    /**
     * Parse the comma-separated list of per-chunk delays from the header value.
     *
     * @param headerValue the raw header value, e.g. "0,42,15,8"
     * @return list of delays in millis, or null if the header is absent/empty
     */
    static List<Long> parseChunkDelaysHeader(String headerValue) {
        if (headerValue == null || headerValue.isEmpty()) {
            return null;
        }
        String[] parts = headerValue.split(",");
        List<Long> delays = new ArrayList<>(parts.length);
        for (String part : parts) {
            try {
                delays.add(Long.parseLong(part.trim()));
            } catch (NumberFormatException e) {
                // Malformed entry — fall back to no per-chunk timing
                return null;
            }
        }
        return delays.isEmpty() ? null : delays;
    }

    private Expectation createTruncatedFallback(Expectation expectation, HttpResponse response) {
        // Clone and add a warning header
        HttpResponse fallbackResponse = response.clone();
        fallbackResponse.withHeader(WARNING_HEADER,
            "SSE stream was truncated during capture; replay may be incomplete. " +
                "Increase maxStreamingCaptureBytes for full capture.");
        // Remove all internal headers
        fallbackResponse.removeHeader(STREAMED_HEADER);
        fallbackResponse.removeHeader(TRUNCATED_HEADER);
        fallbackResponse.removeHeader(CHUNK_DELAYS_HEADER);
        fallbackResponse.removeHeader(StreamingBody.CLOSED_BY_CLIENT_HEADER);

        return new Expectation(
            expectation.getHttpRequest(),
            Times.unlimited(),
            TimeToLive.unlimited(),
            expectation.getPriority()
        ).thenRespond(fallbackResponse);
    }
}

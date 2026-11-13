package org.mockserver.fixture;

import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
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
 * Each event takes the delay of its own recorded chunk; the first event also waits the recorded time from the start
 * of the forward to the first chunk ({@code x-mockserver-first-chunk-delay-ms}). Comments are dropped unless the
 * converter keeps them, when each comment-only block becomes an event with only a {@code comment}. A complete stream
 * the upstream ended gets a {@link HttpSseResponse#getCloseDelay()}, the recorded time after its last replayed event,
 * so the replay ends when the recording did.
 * <p>
 * The log-only recording headers ({@code x-mockserver-streamed}, {@code x-mockserver-response-time-ms} and the
 * other timing and stream-end markers) are removed from every expectation, so a replay never sends them to a client.
 * Any other non-streaming expectation is returned unchanged.
 */
public class SseAwareExpectationConverter {

    private static final String STREAMED_HEADER = RecordingHeaders.STREAMED;
    private static final String TRUNCATED_HEADER = RecordingHeaders.STREAM_TRUNCATED;
    private static final String CHUNK_DELAYS_HEADER = RecordingHeaders.CHUNK_DELAYS;
    private static final String WARNING_HEADER = "x-mockserver-fixture-warning";
    private static final List<String> RECORDING_HEADERS = RecordingHeaders.ALL;
    private static final Pattern EVENT_BOUNDARY = SseBodyParser.BLOCK_BOUNDARY;

    private final SseBodyParser sseBodyParser;

    public SseAwareExpectationConverter() {
        this.sseBodyParser = new SseBodyParser();
    }

    public SseAwareExpectationConverter(long interEventDelayMs) {
        this.sseBodyParser = new SseBodyParser(interEventDelayMs);
    }

    /**
     * @param interEventDelayMs delay between events when the recording has no chunk timing
     * @param keepSseComments   replay recorded comments ({@code :heartbeat}) as {@link SseEvent#getComment()} instead
     *                          of dropping them
     */
    public SseAwareExpectationConverter(long interEventDelayMs, boolean keepSseComments) {
        this.sseBodyParser = new SseBodyParser(interEventDelayMs, keepSseComments);
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
                return withoutRecordingHeaders(expectation, response);
            }
        }

        Long firstChunkDelayMs = parseMillis(response.getFirstHeader(RecordingHeaders.FIRST_CHUNK_DELAY));
        List<Long> chunkDelays = recordedChunkDelays(response, firstChunkDelayMs);

        // Check for truncation
        String truncatedValue = response.getFirstHeader(TRUNCATED_HEADER);
        if ("true".equalsIgnoreCase(truncatedValue)) {
            Expectation timedReplay = createTruncatedTimedReplay(expectation, response, chunkDelays);
            return timedReplay != null ? timedReplay : createTruncatedFallback(expectation, response);
        }

        // Parse the SSE body, using per-chunk timing if available
        String body = response.getBodyAsText();
        if (body == null || body.isEmpty()) {
            return withoutRecordingHeaders(expectation, response);
        }

        List<SseEvent> events = sseBodyParser.parse(body, chunkDelays, recordedChunkSizes(response), bodyCharset(response));
        if (events.isEmpty()) {
            return withoutRecordingHeaders(expectation, response);
        }

        return sseExpectation(expectation, response, events, null, recordedCloseDelay(response, chunkDelays, firstChunkDelayMs, events));
    }

    /**
     * A truncated {@code text/event-stream} capture that kept its chunk timing replays the events that ended before
     * the cut, with their timing. An event the cut ended part way through is dropped; dropping only the last event
     * keeps each earlier event at its own index in the chunk delays.
     *
     * @return the timed replay, or null when the capture is not SSE, has no chunk timing, or has no complete event
     */
    private Expectation createTruncatedTimedReplay(Expectation expectation, HttpResponse response, List<Long> perChunkDelays) {
        String contentType = response.getFirstHeader("Content-Type");
        if (contentType == null || !contentType.toLowerCase().contains("text/event-stream")) {
            return null;
        }
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
        List<SseEvent> events = sseBodyParser.parse(body.substring(0, completeEventsEnd), perChunkDelays, recordedChunkSizes(response), bodyCharset(response));
        if (events.isEmpty()) {
            return null;
        }
        // the cut is not the stream's end, so there is no close delay to replay
        return sseExpectation(expectation, response, events,
            "SSE stream was truncated during capture; replaying the " + events.size() + (events.size() == 1 ? " event" : " events") + " captured before it ended, with their recorded timing" +
                (partialEventDropped ? ", without the last event, which was cut off part way" : ""), null);
    }

    private Expectation sseExpectation(Expectation expectation, HttpResponse response, List<SseEvent> events, String warning, Delay closeDelay) {
        HttpSseResponse sseResponse = sseResponse()
            .withStatusCode(response.getStatusCode())
            .withEvents(events)
            .withCloseConnection(true)
            .withCloseDelay(closeDelay);

        // Copy non-internal headers to the SSE response
        if (response.getHeaderList() != null) {
            for (Header header : response.getHeaderList()) {
                String name = header.getName().getValue();
                String lowerName = name.toLowerCase();
                // Skip internal headers and headers the SSE handler sets automatically
                if (RECORDING_HEADERS.contains(lowerName)
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
     * @return the recorded chunk delays, with the first one replaced by the recorded wait before the first chunk when
     * there is one, or null when the recording has no usable chunk timing; a missing or malformed first-chunk delay
     * leaves the first chunk without a wait, as before the header existed
     */
    private static List<Long> recordedChunkDelays(HttpResponse response, Long firstChunkDelayMs) {
        List<Long> delays = parseChunkDelaysHeader(response.getFirstHeader(CHUNK_DELAYS_HEADER));
        if (delays != null && firstChunkDelayMs != null) {
            delays.set(0, firstChunkDelayMs);
        }
        return delays;
    }

    /**
     * The recorded time from the last replayed event to the end of the stream: the recorded total
     * ({@code x-mockserver-response-time-ms}) less the delays the events replay with, so a replay ends when the
     * recording did. That also covers chunks that became no event, such as trailing comments that are not kept.
     *
     * @return the close delay, or null when the stream has no timed end to replay: no chunk timing, no first-chunk
     * delay or response time, a stream the client or MockServer's stop closed (the upstream never ended it), or no
     * time left after the last event
     */
    private static Delay recordedCloseDelay(HttpResponse response, List<Long> chunkDelays, Long firstChunkDelayMs, List<SseEvent> events) {
        if (chunkDelays == null
            || "true".equalsIgnoreCase(response.getFirstHeader(RecordingHeaders.STREAM_CLOSED_BY_CLIENT))
            || "true".equalsIgnoreCase(response.getFirstHeader(RecordingHeaders.STREAM_CLOSED_BY_STOP))) {
            return null;
        }
        Long responseTimeMs = parseMillis(response.getFirstHeader(RecordingHeaders.RESPONSE_TIME));
        if (firstChunkDelayMs == null || responseTimeMs == null) {
            return null;
        }
        long replayedMs = 0;
        for (SseEvent event : events) {
            if (event.getDelay() != null) {
                replayedMs += event.getDelay().getTimeUnit().toMillis(event.getDelay().getValue());
            }
        }
        long closeDelayMs = Math.min(SseBodyParser.MAX_REPLAYED_DELAY_MS, responseTimeMs - replayedMs);
        return closeDelayMs > 0 ? Delay.milliseconds(closeDelayMs) : null;
    }

    /**
     * @return the recorded bytes of each chunk ({@code x-mockserver-chunk-sizes-bytes}), or null when absent or
     * malformed, so events fall back to one block per chunk
     */
    private static List<Integer> recordedChunkSizes(HttpResponse response) {
        List<Long> sizes = parseChunkDelaysHeader(response.getFirstHeader(RecordingHeaders.CHUNK_SIZES));
        if (sizes == null) {
            return null;
        }
        List<Integer> chunkSizes = new ArrayList<>(sizes.size());
        for (Long size : sizes) {
            if (size < 0 || size > Integer.MAX_VALUE) {
                return null;
            }
            chunkSizes.add(size.intValue());
        }
        return chunkSizes;
    }

    /** The charset the recorded body was decoded from: its content type's, or UTF-8 for a stream without one. */
    private static Charset bodyCharset(HttpResponse response) {
        String contentType = response.getFirstHeader("Content-Type");
        MediaType mediaType = contentType == null || contentType.isEmpty() ? null : MediaType.parse(contentType);
        Charset charset = mediaType != null ? mediaType.getCharsetOrDefault() : null;
        return charset != null ? charset : StandardCharsets.UTF_8;
    }

    private static Long parseMillis(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Expectation withoutRecordingHeaders(Expectation expectation, HttpResponse response) {
        if (RECORDING_HEADERS.stream().noneMatch(response::containsHeader)) {
            return expectation;
        }
        return expectation.cloneWith(expectation.getHttpRequest(), RecordingHeaders.strip(response.clone()));
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
        RecordingHeaders.strip(fallbackResponse);

        return new Expectation(
            expectation.getHttpRequest(),
            Times.unlimited(),
            TimeToLive.unlimited(),
            expectation.getPriority()
        ).thenRespond(fallbackResponse);
    }
}

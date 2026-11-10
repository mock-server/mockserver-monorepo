package org.mockserver.model;

import java.util.List;

/**
 * The log-only headers MockServer adds to the copy of a forwarded response it records. They are never sent to a
 * client; a fixture made from a recording must not carry them, and a recording must not take them from the upstream.
 */
public final class RecordingHeaders {

    public static final String STREAMED = "x-mockserver-streamed";
    public static final String STREAM_TRUNCATED = "x-mockserver-stream-truncated";
    public static final String STREAM_CLOSED_BY_CLIENT = "x-mockserver-stream-closed-by-client";
    /** Marks a stream that was still open when MockServer stopped, so MockServer ended it (see {@code recordOpenStreamsOnStop}). */
    public static final String STREAM_CLOSED_BY_STOP = "x-mockserver-stream-closed-by-mockserver-stop";
    /** The milliseconds between consecutive chunks; the first value is always 0. */
    public static final String CHUNK_DELAYS = "x-mockserver-chunk-delays-ms";
    /** The milliseconds from the start of the forward to the first chunk, on the clock of {@link #RESPONSE_TIME}. */
    public static final String FIRST_CHUNK_DELAY = "x-mockserver-first-chunk-delay-ms";
    public static final String RESPONSE_TIME = "x-mockserver-response-time-ms";

    public static final List<String> ALL = List.of(STREAMED, STREAM_TRUNCATED, STREAM_CLOSED_BY_CLIENT, STREAM_CLOSED_BY_STOP, CHUNK_DELAYS, FIRST_CHUNK_DELAY, RESPONSE_TIME);

    private RecordingHeaders() {
    }

    /**
     * Removes every recording header from {@code response}, allocating nothing when it has none.
     */
    public static HttpResponse strip(HttpResponse response) {
        for (String name : ALL) {
            if (response.containsHeader(name)) {
                response.removeHeader(name);
            }
        }
        return response;
    }
}

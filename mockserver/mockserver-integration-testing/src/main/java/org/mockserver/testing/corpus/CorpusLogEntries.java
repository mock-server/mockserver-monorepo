package org.mockserver.testing.corpus;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.UnaryOperator;

/**
 * The serialised event-log entries a scenario produced, exactly as the server wrote them.
 * <p>
 * {@link #window} reads the whole log ({@code PUT /mockserver/retrieve?type=LOGS&format=LOG_ENTRIES}, no filter)
 * before the scenario starts; {@link Window#entries} reads it again afterwards and returns everything logged in
 * between, whether or not an entry carries a request. Only {@code RETRIEVED} entries are left out, as they record the
 * measurement rather than the exchange. The log is written asynchronously, so each read is repeated until three reads
 * in a row agree.
 */
public final class CorpusLogEntries {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final int AGREEING_READS = 3;
    private static final long READ_INTERVAL_MILLIS = 100;
    private static final long SETTLE_TIMEOUT_MILLIS = 10_000;
    private static final String ALL_ENTRIES = "";

    private CorpusLogEntries() {
    }

    /**
     * Opens a scenario's window on the log: call it before the scenario's first request, control-plane ones included.
     *
     * @param retrieve returns the body of a LOG_ENTRIES retrieve given its request-filter body
     */
    public static Window window(UnaryOperator<String> retrieve) {
        return new Window(retrieve, settled(retrieve));
    }

    public static final class Window {

        private final UnaryOperator<String> retrieve;
        private final List<String> before;

        private Window(UnaryOperator<String> retrieve, List<String> before) {
            this.retrieve = retrieve;
            this.before = before;
        }

        /**
         * @return each entry logged since the window opened, as the server serialised it, in log order
         */
        public List<String> entries() {
            List<String> after = settled(retrieve);
            if (after.size() < before.size() || !after.subList(0, before.size()).equals(before)) {
                throw new AssertionError("the event log no longer starts with the entries read when the scenario began; was it cleared or did it evict?");
            }
            return new ArrayList<>(after.subList(before.size(), after.size()));
        }
    }

    private static List<String> settled(UnaryOperator<String> retrieve) {
        long deadline = System.currentTimeMillis() + SETTLE_TIMEOUT_MILLIS;
        List<String> previous = null;
        int agreeing = 0;
        while (true) {
            List<String> entries = select(retrieve.apply(ALL_ENTRIES));
            if (entries.equals(previous)) {
                agreeing++;
            } else {
                agreeing = 1;
            }
            if (agreeing == AGREEING_READS) {
                return entries;
            }
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("the event log did not settle within " + SETTLE_TIMEOUT_MILLIS + "ms; last read " + entries);
            }
            previous = entries;
            try {
                Thread.sleep(READ_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted waiting for the event log", e);
            }
        }
    }

    /**
     * Adds each entry to the transcript as its own byte block.
     */
    public static WireTranscript appendTo(WireTranscript transcript, List<String> entries) {
        transcript.section("log entries (" + entries.size() + ")");
        for (int i = 0; i < entries.size(); i++) {
            transcript.section("log entry " + (i + 1)).bytes(entries.get(i).getBytes(StandardCharsets.UTF_8));
        }
        return transcript;
    }

    /**
     * A retrieve made over HTTP/1.1 to a MockServer (Netty or servlet) at {@code baseUrl}, e.g.
     * {@code http://127.0.0.1:1080} or {@code http://127.0.0.1:8080/context}.
     */
    public static UnaryOperator<String> overHttp(String baseUrl) {
        HttpClient client = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(10)).build();
        return requestFilterJson -> {
            try {
                HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(baseUrl + "/mockserver/retrieve?type=LOGS&format=LOG_ENTRIES"))
                        .timeout(Duration.ofSeconds(10))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .PUT(HttpRequest.BodyPublishers.ofString(requestFilterJson, StandardCharsets.UTF_8))
                        .build(),
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
                );
                if (response.statusCode() != 200) {
                    throw new AssertionError("log retrieve answered " + response.statusCode() + ": " + response.body());
                }
                return response.body();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted retrieving the event log", e);
            } catch (java.io.IOException e) {
                throw new AssertionError("could not retrieve the event log from " + baseUrl, e);
            }
        };
    }

    static List<String> select(String logEntriesJson) {
        List<String> entries = new ArrayList<>();
        try (JsonParser parser = OBJECT_MAPPER.getFactory().createParser(logEntriesJson)) {
            if (parser.nextToken() != JsonToken.START_ARRAY) {
                throw new AssertionError("log entries are not a JSON array: " + logEntriesJson);
            }
            while (parser.nextToken() == JsonToken.START_OBJECT) {
                int start = (int) parser.currentTokenLocation().getCharOffset();
                JsonNode entry = OBJECT_MAPPER.readTree(parser);
                int end = (int) parser.currentLocation().getCharOffset();
                String json = logEntriesJson.substring(start, end);
                if (!json.startsWith("{") || !json.endsWith("}")) {
                    throw new AssertionError("could not slice a log entry out of " + logEntriesJson);
                }
                if (!"RETRIEVED".equals(entry.path("type").asText())) {
                    entries.add(json);
                }
            }
        } catch (java.io.IOException e) {
            throw new AssertionError("log entries are not valid JSON: " + logEntriesJson, e);
        }
        return entries;
    }
}

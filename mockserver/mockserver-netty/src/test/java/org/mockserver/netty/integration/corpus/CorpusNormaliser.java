package org.mockserver.netty.integration.corpus;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.mockserver.model.Timing;
import org.mockserver.version.Version;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The only rewrites the differential corpus makes before comparing a run with its golden file. Each one replaces a
 * value that differs from run to run, and nothing else:
 * <ul>
 *     <li>correlation ids become {@code <correlation-N>}, numbered in order of first appearance;</li>
 *     <li>timestamps ({@code epochTime}, {@code timestamp}) and every field of a {@code timing} object become
 *     placeholders;</li>
 *     <li>the values of the {@code Date} and response-time headers become placeholders, and each number of the
 *     streamed {@code x-mockserver-chunk-delays-ms} header becomes {@code <d>}, so the count of delays is kept;</li>
 *     <li>ports a test server or client owns, and any other port from 1024 up, become {@code <port:name>}; a port
 *     below 1024 is fixed, so it is kept;</li>
 *     <li>the ids MockServer generates for the expectation it records with a forwarded response become
 *     {@code <generated-id-N>}, numbered in order of first appearance (an id a test sets is not UUID-shaped, so it
 *     is kept);</li>
 *     <li>the MockServer version, which a preflight response carries, becomes {@code <mockserver-version>}, so a
 *     release does not change the corpus;</li>
 *     <li>a serialised throwable's {@code stackTrace} frames become {@code <stack-trace>}: their line numbers,
 *     Netty transport and JDK version change with unrelated edits and with the build machine. The throwable's
 *     message is kept.</li>
 * </ul>
 */
final class CorpusNormaliser {

    static final String REGENERATE_PROPERTY = "mockserver.corpus.regenerate";

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    // a header, in a wire head or in JSON (quotes escaped or not), whose value is a clock reading
    private static final String TIME_HEADER_NAMES = "date|x-mockserver-response-time-ms|x-mockserver-requesttimestamp";
    private static final Pattern WIRE_TIME_HEADER = Pattern.compile("(?im)^(" + TIME_HEADER_NAMES + "):[^\\r\\n]*");
    private static final Pattern JSON_TIME_HEADER = Pattern.compile("(?i)(\\\\?\"(?:" + TIME_HEADER_NAMES + ")\\\\?\"\\s*:\\s*\\[?\\s*\\\\?\")[^\"\\\\]*(\\\\?\")");
    private static final Pattern CHUNK_DELAYS = Pattern.compile("(?i)(\\\\?\"x-mockserver-chunk-delays-ms\\\\?\"\\s*:\\s*\\[?\\s*\\\\?\")([^\"\\\\]*)(\\\\?\")");
    // frames hold no brackets, so the first ']' closes the array
    private static final Pattern STACK_TRACE = Pattern.compile("(\\\\?\"stackTrace\\\\?\"\\s*:\\s*)\\[[^\\[\\]]*\\]");
    private static final Pattern EPOCH_TIME = Pattern.compile("(\\\\?\"epochTime\\\\?\"\\s*:\\s*)\\d+");
    private static final Pattern TIMESTAMP = Pattern.compile("(\\\\?\"timestamp\\\\?\"\\s*:\\s*\\\\?\")[^\"\\\\]*(\\\\?\")");
    private static final Pattern TIMING_FIELD = Pattern.compile("(\\\\?\"(?:" + String.join("|", timingFieldNames()) + ")\\\\?\"\\s*:\\s*)-?\\d+");
    private static final Pattern HOST_AND_PORT = Pattern.compile("(?<![0-9A-Za-z.])(127\\.0\\.0\\.1|localhost|0\\.0\\.0\\.0|0:0:0:0:0:0:0:1|\\[::1\\]):(\\d{1,5})(?![0-9])");
    private static final Pattern GENERATED_ID = Pattern.compile("(\\\\?\"id\\\\?\"\\s*:\\s*\\\\?\")([0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12})(\\\\?\")");
    private static final Pattern PORT_FIELD = Pattern.compile("(\\\\?\"(?:port|localPort|remotePort)\\\\?\"\\s*:\\s*)(\\d{1,5})(?![0-9])");

    private final Map<Integer, String> knownPorts = new LinkedHashMap<>();

    CorpusNormaliser knownPort(int port, String name) {
        knownPorts.put(port, name);
        return this;
    }

    /**
     * Rewrites the clock-valued headers of a response head, before it is escaped.
     */
    String normaliseHead(String head) {
        return WIRE_TIME_HEADER.matcher(head).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + ": <" + match.group(1).toLowerCase() + ">"));
    }

    /**
     * Rewrites a whole corpus document: the wire transcript and the serialised log entries.
     */
    String normaliseDocument(String document, List<String> correlationIdsInOrder) {
        String normalised = document;
        int index = 1;
        for (String correlationId : correlationIdsInOrder) {
            normalised = normalised.replace(correlationId, "<correlation-" + index++ + ">");
        }
        Map<String, String> generatedIds = new LinkedHashMap<>();
        normalised = GENERATED_ID.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1)
            + generatedIds.computeIfAbsent(match.group(2), id -> "<generated-id-" + (generatedIds.size() + 1) + ">") + match.group(3)));
        normalised = normalised.replace(Version.getVersion(), "<mockserver-version>");
        normalised = EPOCH_TIME.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "<epochTime>"));
        normalised = TIMESTAMP.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "<timestamp>" + match.group(2)));
        normalised = TIMING_FIELD.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "<timing>"));
        normalised = CHUNK_DELAYS.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + match.group(2).replaceAll("\\d+", "<d>") + match.group(3)));
        normalised = STACK_TRACE.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "\"<stack-trace>\""));
        normalised = JSON_TIME_HEADER.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + "<clock-header>" + match.group(2)));
        normalised = HOST_AND_PORT.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + ":" + portName(match.group(2))));
        normalised = PORT_FIELD.matcher(normalised).replaceAll(match -> Matcher.quoteReplacement(match.group(1) + portName(match.group(2))));
        return normalised;
    }

    private String portName(String port) {
        int number = Integer.parseInt(port);
        String name = knownPorts.get(number);
        if (name == null && number < 1024) {
            return port;
        }
        return "<port:" + (name != null ? name : "ephemeral") + ">";
    }

    /**
     * @return each distinct {@code correlationId} of the serialised entries, in order of first appearance
     */
    static List<String> correlationIds(String serialisedLogEntries) {
        Set<String> correlationIds = new LinkedHashSet<>();
        try {
            for (JsonNode entry : OBJECT_MAPPER.readTree(serialisedLogEntries)) {
                JsonNode correlationId = entry.get("correlationId");
                if (correlationId != null && !correlationId.asText().isEmpty()) {
                    correlationIds.add(correlationId.asText());
                }
            }
        } catch (Exception e) {
            throw new IllegalStateException("the serialised log entries are not a JSON array", e);
        }
        return new ArrayList<>(correlationIds);
    }

    private static List<String> timingFieldNames() {
        List<String> names = new ArrayList<>();
        for (Field field : Timing.class.getDeclaredFields()) {
            if (!Modifier.isStatic(field.getModifiers())) {
                names.add(Pattern.quote(field.getName()));
            }
        }
        if (names.isEmpty()) {
            throw new IllegalStateException("Timing has no fields to normalise");
        }
        return names;
    }

    /**
     * Renders bytes as text that shows every byte: printable ASCII as itself, CR and LF as {@code \r} and
     * {@code \n} (with a real line break after each {@code \n}), a backslash as {@code \\}, and anything else as
     * {@code \xHH}.
     */
    static String escape(byte[] bytes) {
        StringBuilder text = render(bytes);
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        return text.toString();
    }

    /**
     * As {@link #escape(byte[])} for text decoded one byte per char (as Netty decodes header names and values), with
     * no line break added at the end.
     */
    static String escapeInline(CharSequence text) {
        return render(text.toString().getBytes(StandardCharsets.ISO_8859_1)).toString();
    }

    /**
     * @return the first character of a corpus document a golden file cannot hold as plain LF text, or -1
     */
    static int firstControlCharacter(String document) {
        for (int i = 0; i < document.length(); i++) {
            char character = document.charAt(i);
            if ((character < 0x20 && character != '\n') || character == 0x7F) {
                return i;
            }
        }
        return -1;
    }

    private static StringBuilder render(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length + 16);
        for (byte value : bytes) {
            int unsigned = value & 0xFF;
            if (unsigned == '\r') {
                text.append("\\r");
            } else if (unsigned == '\n') {
                text.append("\\n\n");
            } else if (unsigned == '\\') {
                text.append("\\\\");
            } else if (unsigned >= 0x20 && unsigned < 0x7F) {
                text.append((char) unsigned);
            } else {
                text.append(String.format("\\x%02X", unsigned));
            }
        }
        return text;
    }
}

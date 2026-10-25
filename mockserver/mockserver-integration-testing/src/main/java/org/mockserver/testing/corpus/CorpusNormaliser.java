package org.mockserver.testing.corpus;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes from a {@link WireTranscript} exactly the values that differ between two runs of the same exchange, and
 * nothing else:
 * <ul>
 *     <li>each correlation id becomes {@code <correlation-id-N>}, numbered by order of first appearance;</li>
 *     <li>{@code epochTime}, {@code timestamp} and each numeric field value of a {@code timing} object become
 *     placeholders;</li>
 *     <li>the value of an {@code x-mockserver-response-time-ms} header and of a {@code Date} header holding an
 *     HTTP-date become placeholders;</li>
 *     <li>each ephemeral port the caller names becomes its placeholder where it is written as a port: a
 *     {@code "port" : N} field, or {@code :N} after an IPv4 address, {@code localhost} or {@code ]}. The same digits
 *     elsewhere, in a body or a length, are left alone.</li>
 * </ul>
 * Anything a rule does not name - header order, header case, status, body bytes, frame boundaries, log entry
 * fields - is left exactly as recorded, which is what makes a byte-identity comparison meaningful.
 */
public final class CorpusNormaliser {

    private static final Pattern CORRELATION_ID = Pattern.compile("\"correlationId\" : \"([^\"]+)\"");
    private static final Pattern EPOCH_TIME = Pattern.compile("(\"epochTime\" : )\\d+");
    private static final Pattern TIMESTAMP = Pattern.compile("(\"timestamp\" : \")[^\"]*(\")");
    private static final Pattern TIMING_OBJECT = Pattern.compile("(\"timing\" : \\{)([^{}]*)(})");
    private static final Pattern TIMING_VALUE = Pattern.compile("(\" : )\\d+");
    private static final String PORT_CONTEXT = "(\"port\" : |(?:\\d{1,3}(?:\\.\\d{1,3}){3}|localhost|\\]):)";
    private static final String HTTP_DATE = "[A-Z][a-z]{2}, \\d{2} [A-Z][a-z]{2} \\d{4} \\d{2}:\\d{2}:\\d{2} GMT";
    private static final Pattern WIRE_DATE_HEADER = Pattern.compile("(?im)^(date: ?)" + HTTP_DATE);
    private static final Pattern JSON_DATE_HEADER = Pattern.compile("(?i)(\"date\" : \\[ \")" + HTTP_DATE + "(\")");
    private static final Pattern WIRE_RESPONSE_TIME_HEADER = Pattern.compile("(?im)^(x-mockserver-response-time-ms: ?)\\d+");
    private static final Pattern JSON_RESPONSE_TIME_HEADER = Pattern.compile("(?i)(\"x-mockserver-response-time-ms\" : \\[ \")\\d+(\")");

    private final Map<Integer, String> ephemeralPorts = new LinkedHashMap<>();

    /**
     * @param port        a port chosen at run time (a listener bound to port 0, a client's local port)
     * @param placeholder what to write instead, e.g. {@code <server-port>}
     */
    public CorpusNormaliser ephemeralPort(int port, String placeholder) {
        if (port > 0) {
            ephemeralPorts.put(port, placeholder);
        }
        return this;
    }

    public String normalise(String transcript) {
        String normalised = correlationIds(transcript);
        normalised = EPOCH_TIME.matcher(normalised).replaceAll("$1<epoch-time>");
        normalised = TIMESTAMP.matcher(normalised).replaceAll("$1<timestamp>$2");
        normalised = timing(normalised);
        normalised = WIRE_DATE_HEADER.matcher(normalised).replaceAll("$1<date>");
        normalised = JSON_DATE_HEADER.matcher(normalised).replaceAll("$1<date>$2");
        normalised = WIRE_RESPONSE_TIME_HEADER.matcher(normalised).replaceAll("$1<response-time>");
        normalised = JSON_RESPONSE_TIME_HEADER.matcher(normalised).replaceAll("$1<response-time>$2");
        for (Map.Entry<Integer, String> port : ephemeralPorts.entrySet()) {
            normalised = normalised.replaceAll(PORT_CONTEXT + port.getKey() + "(?!\\d)", "$1" + Matcher.quoteReplacement(port.getValue()));
        }
        return normalised;
    }

    private static String correlationIds(String transcript) {
        Map<String, String> placeholders = new LinkedHashMap<>();
        Matcher matcher = CORRELATION_ID.matcher(transcript);
        while (matcher.find()) {
            placeholders.computeIfAbsent(matcher.group(1), id -> "<correlation-id-" + (placeholders.size() + 1) + ">");
        }
        String normalised = transcript;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            normalised = normalised.replace(placeholder.getKey(), placeholder.getValue());
        }
        return normalised;
    }

    private static String timing(String transcript) {
        Matcher matcher = TIMING_OBJECT.matcher(transcript);
        StringBuilder normalised = new StringBuilder();
        while (matcher.find()) {
            String fields = TIMING_VALUE.matcher(matcher.group(2)).replaceAll("$1<timing>");
            matcher.appendReplacement(normalised, Matcher.quoteReplacement(matcher.group(1) + fields + matcher.group(3)));
        }
        matcher.appendTail(normalised);
        return normalised.toString();
    }
}

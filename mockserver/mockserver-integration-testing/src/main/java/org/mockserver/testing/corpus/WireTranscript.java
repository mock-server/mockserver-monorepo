package org.mockserver.testing.corpus;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * A byte-exact, line-oriented rendering of what a transport put on the wire: raw bytes, header sections in the
 * order they arrived, and one entry per frame so frame boundaries are part of the record.
 * <p>
 * Printable ASCII is written as itself; a backslash, CR, LF and TAB as {@code \\}, {@code \r}, {@code \n} and
 * {@code \t}, with a real line break after each {@code \n}; every other byte as {@code \xHH}. Two transcripts are
 * therefore equal only when the bytes they record are. A byte block carries no length: it is rendered before
 * normalisation, and a placeholder can stand for a value of varying width.
 */
public final class WireTranscript {

    private final StringBuilder text = new StringBuilder();

    public WireTranscript section(String title) {
        text.append("== ").append(title).append('\n');
        return this;
    }

    public WireTranscript line(String line) {
        text.append(line).append('\n');
        return this;
    }

    /**
     * One {@code name: value} line per field, in iteration order, names and values escaped.
     */
    public WireTranscript headers(Iterable<? extends Map.Entry<? extends CharSequence, ? extends CharSequence>> headers) {
        for (Map.Entry<? extends CharSequence, ? extends CharSequence> header : headers) {
            text.append(escape(header.getKey().toString().getBytes(StandardCharsets.UTF_8)))
                .append(": ")
                .append(escape(header.getValue().toString().getBytes(StandardCharsets.UTF_8)))
                .append('\n');
        }
        return this;
    }

    public WireTranscript bytes(byte[] bytes) {
        String escaped = escape(bytes);
        text.append(escaped);
        if (escaped.isEmpty() || escaped.charAt(escaped.length() - 1) != '\n') {
            text.append('\n');
        }
        text.append("== end of bytes\n");
        return this;
    }

    public static String escape(byte[] bytes) {
        StringBuilder escaped = new StringBuilder(bytes.length);
        for (byte value : bytes) {
            int b = value & 0xFF;
            if (b == '\\') {
                escaped.append("\\\\");
            } else if (b == '\r') {
                escaped.append("\\r");
            } else if (b == '\n') {
                escaped.append("\\n\n");
            } else if (b == '\t') {
                escaped.append("\\t");
            } else if (b >= 0x20 && b < 0x7F) {
                escaped.append((char) b);
            } else {
                escaped.append(String.format("\\x%02X", b));
            }
        }
        return escaped.toString();
    }

    @Override
    public String toString() {
        return text.toString();
    }
}

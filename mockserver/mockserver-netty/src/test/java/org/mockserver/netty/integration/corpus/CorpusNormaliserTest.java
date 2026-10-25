package org.mockserver.netty.integration.corpus;

import org.junit.Test;
import org.mockserver.version.Version;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;

/**
 * The corpus rewrites only values that differ from run to run; anything else it leaves alone would be hidden from
 * the comparison.
 */
public class CorpusNormaliserTest {

    private static final String CORRELATION_A = "0b0e5f8a-1c2d-4e3f-8a9b-0c1d2e3f4a5b";
    private static final String CORRELATION_B = "9f8e7d6c-5b4a-4321-8fed-cba987654321";

    @Test
    public void shouldNumberCorrelationIdsInOrderOfFirstAppearance() {
        String entries = "[ { \"correlationId\" : \"" + CORRELATION_B + "\" }, { \"correlationId\" : \"" + CORRELATION_A + "\" }, { \"correlationId\" : \"" + CORRELATION_B + "\" } ]";

        List<String> correlationIds = CorpusNormaliser.correlationIds(entries);

        assertThat(correlationIds, contains(CORRELATION_B, CORRELATION_A));
        assertThat(new CorpusNormaliser().normaliseDocument("x " + CORRELATION_A + " " + CORRELATION_B, correlationIds), is("x <correlation-2> <correlation-1>"));
    }

    @Test
    public void shouldReplaceTimestampsAndTimingFields() {
        String json = "\"epochTime\" : 1761390000123,\n\"timestamp\" : \"2026-10-25 11:00:00.123\",\n\"timing\" : { \"totalTimeInMillis\" : 12, \"requestStartedMillis\" : 1761390000000 }";

        assertThat(new CorpusNormaliser().normaliseDocument(json, List.of()),
            is("\"epochTime\" : <epochTime>,\n\"timestamp\" : \"<timestamp>\",\n\"timing\" : { \"totalTimeInMillis\" : <timing>, \"requestStartedMillis\" : <timing> }"));
    }

    @Test
    public void shouldReplaceClockHeadersInJsonAndInEscapedJsonKeepingTheNumberOfChunkDelays() {
        String json = "\"date\" : [ \"Sun, 25 Oct 2026 11:00:00 GMT\" ], \"x-mockserver-response-time-ms\" : [\n \"17\"\n], \\\"Date\\\" : [ \\\"Sun\\\" ], \"x-mockserver-chunk-delays-ms\" : [ \"0,97,103\" ]";

        assertThat(new CorpusNormaliser().normaliseDocument(json, List.of()),
            is("\"date\" : [ \"<clock-header>\" ], \"x-mockserver-response-time-ms\" : [\n \"<clock-header>\"\n], \\\"Date\\\" : [ \\\"<clock-header>\\\" ], \"x-mockserver-chunk-delays-ms\" : [ \"<d>,<d>,<d>\" ]"));
    }

    @Test
    public void shouldReplaceClockHeadersInAWireHead() {
        String head = "HTTP/1.1 200 OK\r\nDate: Sun, 25 Oct 2026 11:00:00 GMT\r\nx-mockserver-response-time-ms: 3\r\nx-date-like: kept\r\n\r\n";

        assertThat(new CorpusNormaliser().normaliseHead(head),
            is("HTTP/1.1 200 OK\r\nDate: <date>\r\nx-mockserver-response-time-ms: <x-mockserver-response-time-ms>\r\nx-date-like: kept\r\n\r\n"));
    }

    @Test
    public void shouldNamePortsByOwnerAndLeaveOtherNumbersAlone() {
        CorpusNormaliser normaliser = new CorpusNormaliser().knownPort(50123, "mockserver");
        String json = "\"port\" : 50123, \"remoteAddress\" : \"127.0.0.1:61000\", \"Host\" : [ \"localhost:50123\" ], \"content-length\" : [ \"50123\" ], \"statusCode\" : 404, \"port\" : 443, \"Host\" : [ \"localhost:80\" ]";

        assertThat(normaliser.normaliseDocument(json, List.of()),
            is("\"port\" : <port:mockserver>, \"remoteAddress\" : \"127.0.0.1:<port:ephemeral>\", \"Host\" : [ \"localhost:<port:mockserver>\" ], \"content-length\" : [ \"50123\" ], \"statusCode\" : 404, \"port\" : 443, \"Host\" : [ \"localhost:80\" ]"));
    }

    @Test
    public void shouldNumberGeneratedIdsButKeepIdsATestSets() {
        String json = "\"id\" : \"40d1e4fc-d2bc-424b-91e4-fcd2bc224ba7\", \"id\" : \"forward\", \\\"id\\\" : \\\"40d1e4fc-d2bc-424b-91e4-fcd2bc224ba7\\\", \"id\" : \"3518d0c2-2ca2-4e14-98d0-c22ca2ee1418\"";

        assertThat(new CorpusNormaliser().normaliseDocument(json, List.of()),
            is("\"id\" : \"<generated-id-1>\", \"id\" : \"forward\", \\\"id\\\" : \\\"<generated-id-1>\\\", \"id\" : \"<generated-id-2>\""));
    }

    @Test
    public void shouldReplaceTheMockServerVersion() {
        assertThat(new CorpusNormaliser().normaliseHead("version: " + Version.getVersion() + "\r\n"), is("version: " + Version.getVersion() + "\r\n"));
        assertThat(new CorpusNormaliser().normaliseDocument("version: " + Version.getVersion() + "\\r\\n", List.of()), is("version: <mockserver-version>\\r\\n"));
    }

    @Test
    public void shouldReplaceStackTraceFramesButKeepTheThrowableMessage() {
        String json = "\"throwable\" : {\n  \"localizedMessage\" : \"missing upgrade\",\n  \"message\" : \"missing upgrade\",\n  \"stackTrace\" : [ {\n    \"className\" : \"java.lang.Thread\",\n    \"lineNumber\" : 840,\n    \"moduleVersion\" : \"17.0.20.1\"\n  }, {\n    \"lineNumber\" : 582\n  } ]\n}";

        assertThat(new CorpusNormaliser().normaliseDocument(json, List.of()),
            is("\"throwable\" : {\n  \"localizedMessage\" : \"missing upgrade\",\n  \"message\" : \"missing upgrade\",\n  \"stackTrace\" : \"<stack-trace>\"\n}"));
    }

    @Test
    public void shouldLeaveEverythingElseUnchanged() {
        String document = "HTTP/1.1 200 OK\\r\\n\nx-zulu: 1761390000123\\r\\n\n\"path\" : \"/single\", \"times\" : { \"remainingTimes\" : 1 }, \"body\" : \"café\"";

        assertThat(new CorpusNormaliser().normaliseDocument(document, List.of()), is(document));
    }

    @Test
    public void shouldEscapeEveryByteVisibly() {
        byte[] bytes = "a\\b\r\n".getBytes(StandardCharsets.US_ASCII);
        byte[] withBinary = new byte[bytes.length + 2];
        System.arraycopy(bytes, 0, withBinary, 0, bytes.length);
        withBinary[bytes.length] = (byte) 0x81;
        withBinary[bytes.length + 1] = 0x00;

        assertThat(CorpusNormaliser.escape(withBinary), is("a\\\\b\\r\\n\n\\x81\\x00\n"));
    }

    @Test
    public void shouldEscapeHeaderTextWithoutAddingALineBreak() {
        assertThat(CorpusNormaliser.escapeInline("HTTP/1.1 200 OK\r\nvalue\u0001"), is("HTTP/1.1 200 OK\\r\\n\nvalue\\x01"));
    }

    @Test
    public void shouldFindAControlCharacterAGoldenFileCannotHold() {
        assertThat(CorpusNormaliser.firstControlCharacter("plain\ntext\\r\n"), is(-1));
        assertThat(CorpusNormaliser.firstControlCharacter("raw\r\n"), is(3));
        assertThat(CorpusNormaliser.firstControlCharacter("tab\t"), is(3));
    }
}

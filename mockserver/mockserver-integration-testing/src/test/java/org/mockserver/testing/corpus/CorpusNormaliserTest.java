package org.mockserver.testing.corpus;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class CorpusNormaliserTest {

    @Test
    public void numbersCorrelationIdsByFirstAppearanceAndReplacesEveryOccurrence() {
        String transcript = "\"correlationId\" : \"bbb-2\",\n"
            + "\"correlationId\" : \"aaa-1\",\n"
            + "\"correlationId\" : \"bbb-2\",\n"
            + "message mentions bbb-2\n";

        assertThat(new CorpusNormaliser().normalise(transcript), is("\"correlationId\" : \"<correlation-id-1>\",\n"
            + "\"correlationId\" : \"<correlation-id-2>\",\n"
            + "\"correlationId\" : \"<correlation-id-1>\",\n"
            + "message mentions <correlation-id-1>\n"));
    }

    @Test
    public void replacesEpochTimeAndTimestamp() {
        assertThat(new CorpusNormaliser().normalise("\"epochTime\" : 1761390000123,\n\"timestamp\" : \"2026-10-25 12:00:00.123\",\n"),
            is("\"epochTime\" : <epoch-time>,\n\"timestamp\" : \"<timestamp>\",\n"));
    }

    @Test
    public void replacesOnlyTheNumericValuesOfATimingObject() {
        String transcript = "\"timing\" : {\\n\n  \"phase2Millis\" : 12,\\n\n  \"totalTimeInMillis\" : 345\\n\n},\n\"statusCode\" : 200";

        assertThat(new CorpusNormaliser().normalise(transcript),
            is("\"timing\" : {\\n\n  \"phase2Millis\" : <timing>,\\n\n  \"totalTimeInMillis\" : <timing>\\n\n},\n\"statusCode\" : 200"));
    }

    @Test
    public void replacesADateHeaderOnlyWhenItHoldsAnHttpDate() {
        String transcript = "Date: Sat, 25 Oct 2026 12:00:00 GMT\\r\\n\n"
            + "date: tomorrow\n"
            + "\"Date\" : [ \"Sat, 25 Oct 2026 12:00:00 GMT\" ]\n"
            + "\"date\" : [ \"2026-10-25\" ]\n";

        assertThat(new CorpusNormaliser().normalise(transcript), is("Date: <date>\\r\\n\n"
            + "date: tomorrow\n"
            + "\"Date\" : [ \"<date>\" ]\n"
            + "\"date\" : [ \"2026-10-25\" ]\n"));
    }

    @Test
    public void replacesTheResponseTimeHeaderValue() {
        assertThat(new CorpusNormaliser().normalise("x-mockserver-response-time-ms: 17\n\"x-mockserver-response-time-ms\" : [ \"4\" ]\n"),
            is("x-mockserver-response-time-ms: <response-time>\n\"x-mockserver-response-time-ms\" : [ \"<response-time>\" ]\n"));
    }

    @Test
    public void replacesAPortOnlyWhereItIsWrittenAsAPort() {
        String transcript = "\"port\" : 51234,\n"
            + "\"remoteAddress\" : \"127.0.0.1:51234\"\n"
            + "authority localhost:51234 and [::1]:51234\n"
            + "body 51234 and length 151234 and 512345\n";

        assertThat(new CorpusNormaliser().ephemeralPort(51234, "<server-port>").normalise(transcript), is("\"port\" : <server-port>,\n"
            + "\"remoteAddress\" : \"127.0.0.1:<server-port>\"\n"
            + "authority localhost:<server-port> and [::1]:<server-port>\n"
            + "body 51234 and length 151234 and 512345\n"));
    }

    @Test
    public void leavesEverythingElseExactlyAsRecorded() {
        String transcript = "HTTP/1.1 200 \\r\\n\nX-Zulu: z\\r\\n\nx-alpha: a\\r\\n\n\\r\\n\nh\\xC3\\xA9llo 12345\n"
            + "\"statusCode\" : 200, \"port\" : 1080\n";

        assertThat(new CorpusNormaliser().ephemeralPort(51234, "<server-port>").normalise(transcript), is(transcript));
    }
}

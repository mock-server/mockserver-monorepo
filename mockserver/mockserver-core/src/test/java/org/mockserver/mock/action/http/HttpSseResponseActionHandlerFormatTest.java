package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.SseEvent;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Byte-level conformance of the emitted SSE framing against the WHATWG event-stream grammar.
 *
 * <p>The rule that matters here: a server-sent event stream is split into lines on CRLF, CR
 * <em>or</em> LF. Any of those three appearing inside an emitted {@code data:} line terminates
 * that line, so the remainder is parsed as a fresh — and unrecognised — field and silently
 * dropped by the client.
 */
public class HttpSseResponseActionHandlerFormatTest {

    private final HttpSseResponseActionHandler handler = new HttpSseResponseActionHandler(
        new MockServerLogger(), null, Configuration.configuration());

    private String format(SseEvent event) {
        return handler.formatSseEvent(event);
    }

    /**
     * The reported defect: {@code sanitizeSseFieldValue} guarded {@code id} and {@code event} but
     * was never applied to {@code data}, and {@code data} was split on LF only — so a lone CR
     * went out raw and everything after it was lost.
     */
    @Test
    public void shouldNotEmitALoneCarriageReturnInsideADataLine() {
        String formatted = format(new SseEvent().withData("before\rafter"));

        assertThat("a bare CR inside a data line truncates the event at the client",
            formatted, not(containsString("before\rafter")));
        assertThat(formatted, is("data: before\ndata: after\n\n"));
    }

    @Test
    public void shouldSplitCarriageReturnLineFeedIntoOneDataLineEach() {
        assertThat(format(new SseEvent().withData("one\r\ntwo")),
            is("data: one\ndata: two\n\n"));
    }

    @Test
    public void shouldSplitLineFeedIntoOneDataLineEach() {
        assertThat(format(new SseEvent().withData("one\ntwo")),
            is("data: one\ndata: two\n\n"));
    }

    @Test
    public void shouldSplitMixedLineTerminators() {
        assertThat(format(new SseEvent().withData("a\rb\nc\r\nd")),
            is("data: a\ndata: b\ndata: c\ndata: d\n\n"));
    }

    /**
     * Every line the client will read must be either a recognised field or the blank dispatch
     * line — no stray content may appear at the start of a line.
     */
    @Test
    public void shouldEmitOnlyRecognisedFieldsForDataContainingEveryLineTerminator() {
        String formatted = format(new SseEvent().withData("x\ry\r\nz\nw"));

        for (String line : formatted.split("\r\n|\r|\n")) {
            if (!line.isEmpty()) {
                assertThat("stray line not parseable as an SSE field: '" + line + "'",
                    line, startsWith("data: "));
            }
        }
    }

    // --- the already-guarded fields stay guarded ---

    @Test
    public void shouldStripLineTerminatorsFromIdAndEvent() {
        String formatted = format(new SseEvent()
            .withId("a\rb")
            .withEvent("c\nd")
            .withData("payload"));

        assertThat(formatted, is("id: ab\nevent: cd\ndata: payload\n\n"));
    }

    // --- unaffected shapes ---

    @Test
    public void shouldEmitSingleLineDataUnchanged() {
        assertThat(format(new SseEvent().withData("plain")), is("data: plain\n\n"));
    }

    @Test
    public void shouldEmitRetryAndEventFields() {
        assertThat(format(new SseEvent().withEvent("update").withRetry(3000).withData("v")),
            is("event: update\nretry: 3000\ndata: v\n\n"));
    }

    @Test
    public void shouldEmitBlankLineOnlyWhenNoFieldsSet() {
        assertThat(format(new SseEvent()), is("\n"));
    }

    // --- comments ---

    @Test
    public void shouldEmitCommentOnlyEventAsCommentLineAndBlankLine() {
        assertThat(format(new SseEvent().withComment("hb")), is(":hb\n\n"));
    }

    @Test
    public void shouldReplayRecordedCommentsByteForByte() {
        String recorded = ":hb\n\n: hb\n\n:\n\n:  spaced  \nevent: a\ndata: 1\n\n";
        StringBuilder replayed = new StringBuilder();
        for (SseEvent event : new org.mockserver.fixture.SseBodyParser(0, true).parse(recorded, null)) {
            replayed.append(format(event));
        }

        assertThat(replayed.toString(), is(recorded));
    }

    @Test
    public void shouldEmitACommentExactlyAsWrittenAfterTheColon() {
        assertThat(format(new SseEvent().withComment(" hb")), is(": hb\n\n"));
        assertThat(format(new SseEvent().withComment("  two spaces ")), is(":  two spaces \n\n"));
    }

    @Test
    public void shouldEmitEmptyCommentAsBareColon() {
        assertThat(format(new SseEvent().withComment("")), is(":\n\n"));
    }

    @Test
    public void shouldEmitCommentLinesBeforeOtherFields() {
        assertThat(format(new SseEvent().withId("7").withEvent("update").withRetry(3000).withData("v").withComment(" note")),
            is(": note\nid: 7\nevent: update\nretry: 3000\ndata: v\n\n"));
    }

    @Test
    public void shouldEmitOneCommentLinePerLineOfAMultiLineComment() {
        assertThat(format(new SseEvent().withComment("one\n two")), is(":one\n: two\n\n"));
    }

    @Test
    public void shouldSplitACommentOnUnicodeLineAndParagraphSeparatorsAndNextLine() {
        String comment = "a" + (char) 0x2028 + "data: x" + (char) 0x2029 + "b" + (char) 0x85 + "c";

        assertThat(format(new SseEvent().withComment(comment)), is(":a\n:data: x\n:b\n:c\n\n"));
    }

    @Test
    public void shouldNotLetALineBreakInACommentStartAFieldOrEndTheEvent() {
        String formatted = format(new SseEvent().withComment("x\rdata: injected\r\n\nevent: evil\nid: 9").withData("real"));

        assertThat(formatted, is(":x\n:data: injected\n:\n:event: evil\n:id: 9\ndata: real\n\n"));
        for (String line : formatted.split("\r\n|\r|\n")) {
            if (!line.isEmpty()) {
                assertThat("stray line not parseable as a comment or the data field: '" + line + "'",
                    line.startsWith(":") || line.equals("data: real"), is(true));
            }
        }
    }
}

package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.model.SseEvent;

import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;

public class SseBodyParserTest {

    private final SseBodyParser parser = new SseBodyParser();

    @Test
    public void shouldParseSingleDataEvent() {
        // given
        String sseText = "data: hello world\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getData(), is("hello world"));
        assertThat(events.get(0).getEvent(), nullValue());
        assertThat(events.get(0).getId(), nullValue());
        assertThat(events.get(0).getDelay(), nullValue()); // first event has no delay
    }

    @Test
    public void shouldParseEventWithTypeAndData() {
        // given
        String sseText = "event: message\ndata: {\"text\":\"hi\"}\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getEvent(), is("message"));
        assertThat(events.get(0).getData(), is("{\"text\":\"hi\"}"));
    }

    @Test
    public void shouldParseEventWithIdAndRetry() {
        // given
        String sseText = "id: 42\nretry: 3000\ndata: test\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getId(), is("42"));
        assertThat(events.get(0).getRetry(), is(3000));
        assertThat(events.get(0).getData(), is("test"));
    }

    @Test
    public void shouldParseMultipleEvents() {
        // given
        String sseText =
            "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":0}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"index\":1}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(3));
        assertThat(events.get(0).getEvent(), is("content_block_delta"));
        assertThat(events.get(0).getDelay(), nullValue()); // first event: no delay
        assertThat(events.get(1).getEvent(), is("content_block_delta"));
        assertThat(events.get(1).getDelay(), notNullValue()); // second event: has delay
        assertThat(events.get(2).getEvent(), is("message_stop"));
        assertThat(events.get(2).getDelay(), notNullValue());
    }

    @Test
    public void shouldParseMultiLineData() {
        // given
        String sseText = "data: line1\ndata: line2\ndata: line3\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getData(), is("line1\nline2\nline3"));
    }

    @Test
    public void shouldIgnoreCommentLines() {
        // given
        String sseText = ": this is a comment\ndata: actual data\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getData(), is("actual data"));
    }

    @Test
    public void shouldHandleEmptyInput() {
        // when
        List<SseEvent> events = parser.parse("");

        // then
        assertThat(events.size(), is(0));
    }

    @Test
    public void shouldHandleNullInput() {
        // when
        List<SseEvent> events = parser.parse(null);

        // then
        assertThat(events.size(), is(0));
    }

    @Test
    public void shouldHandleDataWithoutLeadingSpace() {
        // given — SSE spec allows "data:value" without space
        String sseText = "data:no-space\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getData(), is("no-space"));
    }

    @Test
    public void shouldParseAnthropicStyleSseStream() {
        // given — realistic Anthropic Claude streaming response
        String sseText =
            "event: message_start\n" +
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_01\",\"model\":\"claude-3-opus\"}}\n\n" +
                "event: content_block_start\n" +
                "data: {\"type\":\"content_block_start\",\"index\":0}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\"Hello\"}}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"text\":\" world\"}}\n\n" +
                "event: content_block_stop\n" +
                "data: {\"type\":\"content_block_stop\",\"index\":0}\n\n" +
                "event: message_delta\n" +
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(7));
        assertThat(events.get(0).getEvent(), is("message_start"));
        assertThat(events.get(2).getEvent(), is("content_block_delta"));
        assertThat(events.get(2).getData(), containsString("Hello"));
        assertThat(events.get(6).getEvent(), is("message_stop"));
    }

    @Test
    public void shouldParseOpenAiStyleSseStream() {
        // given — OpenAI-style SSE (no event: prefix, just data:)
        String sseText =
            "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\n" +
                "data: {\"id\":\"chatcmpl-1\",\"choices\":[{\"delta\":{\"content\":\" world\"}}]}\n\n" +
                "data: [DONE]\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(3));
        assertThat(events.get(0).getData(), containsString("Hello"));
        assertThat(events.get(2).getData(), is("[DONE]"));
    }

    @Test
    public void shouldHandleCustomInterEventDelay() {
        // given
        SseBodyParser customParser = new SseBodyParser(100);
        String sseText = "data: a\n\ndata: b\n\ndata: c\n\n";

        // when
        List<SseEvent> events = customParser.parse(sseText);

        // then
        assertThat(events.size(), is(3));
        assertThat(events.get(0).getDelay(), nullValue()); // first: no delay
        assertThat(events.get(1).getDelay().getTimeUnit().toMillis(events.get(1).getDelay().getValue()), is(100L));
        assertThat(events.get(2).getDelay().getTimeUnit().toMillis(events.get(2).getDelay().getValue()), is(100L));
    }

    @Test
    public void shouldHandleZeroInterEventDelay() {
        // given
        SseBodyParser zeroDelayParser = new SseBodyParser(0);
        String sseText = "data: a\n\ndata: b\n\n";

        // when
        List<SseEvent> events = zeroDelayParser.parse(sseText);

        // then
        assertThat(events.size(), is(2));
        assertThat(events.get(0).getDelay(), nullValue());
        assertThat(events.get(1).getDelay(), nullValue()); // zero delay means no delay set
    }

    @Test
    public void shouldIgnoreNonNumericRetry() {
        // given
        String sseText = "retry: not-a-number\ndata: test\n\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getRetry(), nullValue());
        assertThat(events.get(0).getData(), is("test"));
    }

    @Test
    public void shouldHandleWindowsLineEndings() {
        // given
        String sseText = "event: test\r\ndata: hello\r\n\r\n";

        // when
        List<SseEvent> events = parser.parse(sseText);

        // then
        assertThat(events.size(), is(1));
        assertThat(events.get(0).getEvent(), is("test"));
        assertThat(events.get(0).getData(), is("hello"));
    }

    @Test
    public void shouldKeepTrailingSpacesOfAFieldValue() {
        List<SseEvent> events = parser.parse("data: x  \n\n");

        assertThat(events.size(), is(1));
        assertThat(events.get(0).getData(), is("x  "));
    }

    @Test
    public void shouldIgnoreAFieldWhoseNameStartsWithWhitespace() {
        assertThat(parser.parse("  data: x\n\n").size(), is(0));
    }
}

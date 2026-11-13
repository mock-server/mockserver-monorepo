package org.mockserver.fixture;

import org.junit.Test;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;

import java.util.List;

import static org.hamcrest.CoreMatchers.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class SseAwareExpectationConverterTest {

    private final SseAwareExpectationConverter converter = new SseAwareExpectationConverter();

    @Test
    public void shouldConvertStreamedSseResponseToHttpSseResponse() {
        // given — a FORWARDED_REQUEST expectation with SSE body and streaming headers
        String sseBody =
            "event: message_start\n" +
                "data: {\"type\":\"message_start\"}\n\n" +
                "event: content_block_delta\n" +
                "data: {\"type\":\"delta\",\"text\":\"Hello\"}\n\n" +
                "event: message_stop\n" +
                "data: {\"type\":\"message_stop\"}\n\n";

        Expectation original = new Expectation(
            request().withMethod("POST").withPath("/v1/messages"),
            Times.once(),
            TimeToLive.unlimited(),
            0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("X-Request-Id", "req-123")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then — should have an SSE response, not a plain response
        assertThat(converted.length, is(1));
        assertThat(converted[0].getHttpResponse(), nullValue());
        assertThat(converted[0].getHttpSseResponse(), notNullValue());

        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        assertThat(sseResponse.getStatusCode(), is(200));
        assertThat(sseResponse.getCloseConnection(), is(true));

        List<SseEvent> events = sseResponse.getEvents();
        assertThat(events.size(), is(3));
        assertThat(events.get(0).getEvent(), is("message_start"));
        assertThat(events.get(1).getEvent(), is("content_block_delta"));
        assertThat(events.get(1).getData(), containsString("Hello"));
        assertThat(events.get(2).getEvent(), is("message_stop"));

        // X-Request-Id should be preserved in SSE headers
        boolean foundRequestId = false;
        if (sseResponse.getHeaders() != null) {
            for (org.mockserver.model.Header header : sseResponse.getHeaders().getEntries()) {
                if (header.getName().getValue().equalsIgnoreCase("X-Request-Id")) {
                    foundRequestId = true;
                }
            }
        }
        assertThat("X-Request-Id should be preserved", foundRequestId, is(true));
    }

    @Test
    public void shouldFallBackToStaticResponseWhenTruncated() {
        // given — a truncated SSE capture
        Expectation original = new Expectation(
            request().withMethod("POST").withPath("/v1/messages"),
            Times.once(),
            TimeToLive.unlimited(),
            0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-stream-truncated", "true")
                .withBody("event: partial\ndata: {\"incomplete\":")
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then — should have a static response with a warning
        assertThat(converted.length, is(1));
        assertThat(converted[0].getHttpResponse(), notNullValue());
        assertThat(converted[0].getHttpSseResponse(), nullValue());
        assertThat(converted[0].getHttpResponse().getFirstHeader("x-mockserver-fixture-warning"),
            containsString("truncated"));
        // internal headers should be removed
        assertThat(converted[0].getHttpResponse().getFirstHeader("x-mockserver-streamed"), is(""));
        assertThat(converted[0].getHttpResponse().getFirstHeader("x-mockserver-stream-truncated"), is(""));
    }

    @Test
    public void shouldLeaveNonStreamingExpectationUnchanged() {
        // given — a normal (non-streaming) forwarded response
        Expectation original = new Expectation(
            request().withMethod("GET").withPath("/api/users"),
            Times.once(),
            TimeToLive.unlimited(),
            0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "application/json")
                .withBody("[{\"id\":1}]")
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then — should be unchanged (same response object)
        assertThat(converted.length, is(1));
        assertThat(converted[0].getHttpResponse(), notNullValue());
        assertThat(converted[0].getHttpSseResponse(), nullValue());
        assertThat(converted[0].getHttpResponse().getBodyAsString(), is("[{\"id\":1}]"));
    }

    @Test
    public void shouldDetectSseByContentTypeWhenStreamedHeaderMissing() {
        // given — has text/event-stream but no x-mockserver-streamed header
        String sseBody = "data: test\n\n";

        Expectation original = new Expectation(
            request().withMethod("GET").withPath("/events"),
            Times.once(),
            TimeToLive.unlimited(),
            0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then — should still convert to SSE
        assertThat(converted[0].getHttpSseResponse(), notNullValue());
        assertThat(converted[0].getHttpSseResponse().getEvents().size(), is(1));
    }

    @Test
    public void shouldHandleNullInput() {
        // when
        Expectation[] converted = converter.convert(null);

        // then
        assertThat(converted.length, is(0));
    }

    @Test
    public void shouldHandleExpectationWithNullResponse() {
        // given
        Expectation original = new Expectation(
            request().withMethod("GET").withPath("/null-resp"),
            Times.once(),
            TimeToLive.unlimited(),
            0
        );
        // no response set

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then — returned as-is
        assertThat(converted.length, is(1));
    }

    @Test
    public void shouldHandleMixedStreamingAndNonStreamingExpectations() {
        // given
        String sseBody = "data: streamed\n\n";

        Expectation streaming = new Expectation(
            request().withMethod("POST").withPath("/stream"),
            Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withBody(sseBody)
        );

        Expectation normal = new Expectation(
            request().withMethod("GET").withPath("/normal"),
            Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response().withStatusCode(200).withBody("ok")
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{streaming, normal});

        // then
        assertThat(converted.length, is(2));
        assertThat(converted[0].getHttpSseResponse(), notNullValue()); // converted to SSE
        assertThat(converted[1].getHttpResponse(), notNullValue()); // left as static
        assertThat(converted[1].getHttpResponse().getBodyAsString(), is("ok"));
    }

    @Test
    public void shouldStripChunkDelaysHeaderFromTruncatedFallback() {
        // given — a truncated SSE capture that also has the chunk-delays header
        Expectation original = new Expectation(
            request().withMethod("POST").withPath("/v1/messages"),
            Times.once(),
            TimeToLive.unlimited(),
            0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("x-mockserver-stream-truncated", "true")
                .withHeader("x-mockserver-chunk-delays-ms", "0,42,15")
                .withBody("event: partial\ndata: {\"incomplete\":")
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then — all internal headers should be removed
        assertThat(converted[0].getHttpResponse(), notNullValue());
        assertThat(converted[0].getHttpResponse().getFirstHeader("x-mockserver-streamed"), is(""));
        assertThat(converted[0].getHttpResponse().getFirstHeader("x-mockserver-stream-truncated"), is(""));
        assertThat(converted[0].getHttpResponse().getFirstHeader("x-mockserver-chunk-delays-ms"), is(""));
        // warning header should be present
        assertThat(converted[0].getHttpResponse().getFirstHeader("x-mockserver-fixture-warning"),
            containsString("truncated"));
    }

    @Test
    public void shouldStripInternalHeadersFromSseResponse() {
        // given
        String sseBody = "data: test\n\n";
        Expectation original = new Expectation(
            request(), Times.once(), TimeToLive.unlimited(), 0
        ).thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("Content-Type", "text/event-stream")
                .withHeader("x-mockserver-streamed", "true")
                .withHeader("Transfer-Encoding", "chunked")
                .withHeader("Cache-Control", "no-cache")
                .withHeader("Connection", "keep-alive")
                .withHeader("X-Custom-Header", "keep-me")
                .withBody(sseBody)
        );

        // when
        Expectation[] converted = converter.convert(new Expectation[]{original});

        // then — internal/auto-set headers stripped, custom header preserved
        HttpSseResponse sseResponse = converted[0].getHttpSseResponse();
        assertThat(sseResponse, notNullValue());
        boolean foundCustom = false;
        if (sseResponse.getHeaders() != null) {
            for (org.mockserver.model.Header header : sseResponse.getHeaders().getEntries()) {
                String name = header.getName().getValue().toLowerCase();
                assertThat("Internal header should be stripped: " + name,
                    name.equals("content-type") || name.equals("transfer-encoding")
                        || name.equals("cache-control") || name.equals("connection")
                        || name.equals("x-mockserver-streamed"),
                    is(false));
                if (name.equals("x-custom-header")) {
                    foundCustom = true;
                }
            }
        }
        assertThat("X-Custom-Header should be preserved", foundCustom, is(true));
    }

    @Test
    public void shouldReplayAStreamStillInAContentCodingAsRecordedWithoutRecordingHeaders() {
        byte[] encoded = {0x1f, (byte) 0x8b, 0x08, 0x00, 0x7f};
        org.mockserver.model.HttpResponse recorded = org.mockserver.model.HttpResponse.response()
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("Content-Encoding", "x-unknown")
            .withHeader("x-mockserver-streamed", "true")
            .withHeader("x-mockserver-chunk-delays-ms", "0")
            .withBody(encoded);

        Expectation converted = new SseAwareExpectationConverter().convert(new Expectation[]{
            new Expectation(org.mockserver.model.HttpRequest.request("/encoded")).thenRespond(recorded)
        })[0];

        assertThat(converted.getHttpSseResponse(), org.hamcrest.Matchers.nullValue());
        assertThat(converted.getHttpResponse().getBodyAsRawBytes(), is(encoded));
        assertThat(converted.getHttpResponse().getFirstHeader("Content-Encoding"), is("x-unknown"));
        assertThat(converted.getHttpResponse().containsHeader("x-mockserver-streamed"), is(false));
        assertThat(converted.getHttpResponse().containsHeader("x-mockserver-chunk-delays-ms"), is(false));
    }
}

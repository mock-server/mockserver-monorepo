package org.mockserver.mock.action.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;
import org.mockserver.time.TimeService;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code x-mockserver-stream-closed-by-client} is written only for a stream completed because the client closed it, and
 * {@code x-mockserver-stream-closed-by-mockserver-stop} only for one MockServer completed as it stopped;
 * every other streamed log response carries the headers it always did.
 */
public class HttpActionHandlerStreamingHeadersTest {

    private static long forwardStartNanos;

    private static StreamingBody bodyWithTwoChunks() {
        forwardStartNanos = TimeService.nanoTime();
        StreamingBody body = new StreamingBody(1024, true);
        body.subscribe(chunk -> {
        }, () -> {
        }, error -> {
        });
        ByteBuf first = Unpooled.copiedBuffer("first", StandardCharsets.UTF_8);
        ByteBuf second = Unpooled.copiedBuffer("second", StandardCharsets.UTF_8);
        body.addChunk(first);
        body.addChunk(second);
        first.release();
        second.release();
        return body;
    }

    private static HttpResponse headersFor(StreamingBody body) throws Exception {
        HttpResponse logResponse = HttpResponse.response();
        HttpActionHandler.attachStreamingHeaders(logResponse, body, forwardStartNanos);
        return logResponse;
    }

    @Test
    public void shouldMarkAStreamCompletedAfterClientClose() throws Exception {
        StreamingBody body = bodyWithTwoChunks();
        body.closeUpstreamForClientClose();
        body.completeAfterClientClose();

        HttpResponse response = headersFor(body);

        assertThat(response.getFirstHeader("x-mockserver-stream-closed-by-client"), is("true"));
        assertThat(response.getFirstHeader("x-mockserver-stream-truncated"), is(""));
        assertThat(response.getFirstHeader("x-mockserver-chunk-delays-ms"), is(notNullValue()));
    }

    @Test
    public void shouldMarkAStreamCompletedByStop() throws Exception {
        StreamingBody body = bodyWithTwoChunks();
        body.completeOnStop();

        HttpResponse response = headersFor(body);

        assertThat(response.getFirstHeader("x-mockserver-stream-closed-by-mockserver-stop"), is("true"));
        assertThat(response.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
        assertThat(response.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertThat(response.getFirstHeader("x-mockserver-chunk-delays-ms"), is(notNullValue()));
    }

    @Test
    public void shouldNotMarkAStreamTheClientClosedThatEndedIncomplete() throws Exception {
        StreamingBody body = bodyWithTwoChunks();
        body.closeUpstreamForClientClose();
        body.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        HttpResponse response = headersFor(body);

        assertThat(response.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
        assertThat(response.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat(response.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
        assertThat(response.containsHeader("x-mockserver-first-chunk-delay-ms"), is(false));
    }

    @Test
    public void shouldNotMarkAStreamThatEndedNormally() throws Exception {
        StreamingBody body = bodyWithTwoChunks();
        body.complete();

        HttpResponse response = headersFor(body);

        assertThat(response.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
        assertThat(response.containsHeader("x-mockserver-stream-closed-by-mockserver-stop"), is(false));
        assertThat(response.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertThat(response.getFirstHeader("x-mockserver-chunk-delays-ms"), is(notNullValue()));
        assertThat(response.getFirstHeader("x-mockserver-streamed"), is("true"));
    }

    @Test
    public void shouldRecordTheWaitFromTheStartOfTheForwardToTheFirstChunk() throws Exception {
        long startNanos = TimeService.nanoTime() - 2_500_000_000L;
        StreamingBody body = bodyWithTwoChunks();
        body.complete();

        HttpResponse logResponse = HttpResponse.response();
        HttpActionHandler.attachStreamingHeaders(logResponse, body, startNanos);

        long firstChunkDelayMs = Long.parseLong(logResponse.getFirstHeader("x-mockserver-first-chunk-delay-ms"));
        assertThat(firstChunkDelayMs >= 2500 && firstChunkDelayMs < 60_000, is(true));
        assertThat("the chunk delays keep their meaning", logResponse.getFirstHeader("x-mockserver-chunk-delays-ms").startsWith("0,"), is(true));
    }

    @Test
    public void shouldReplaceRecordingHeadersTheUpstreamSent() throws Exception {
        StreamingBody body = bodyWithTwoChunks();
        body.complete();
        HttpResponse logResponse = HttpResponse.response()
            .withHeader("X-MockServer-Chunk-Delays-Ms", "0,99999999")
            .withHeader("x-mockserver-first-chunk-delay-ms", "123456789")
            .withHeader("x-mockserver-stream-truncated", "false")
            .withHeader("x-mockserver-stream-closed-by-client", "true")
            .withHeader("x-mockserver-response-time-ms", "1")
            .withHeader("x-upstream", "kept");

        HttpActionHandler.attachStreamingHeaders(logResponse, body, forwardStartNanos);

        assertThat(logResponse.getHeader("x-mockserver-chunk-delays-ms").size(), is(1));
        assertThat(logResponse.getFirstHeader("x-mockserver-chunk-delays-ms").contains("99999999"), is(false));
        assertThat(logResponse.getHeader("x-mockserver-first-chunk-delay-ms").size(), is(1));
        assertThat(Long.parseLong(logResponse.getFirstHeader("x-mockserver-first-chunk-delay-ms")) < 60_000, is(true));
        assertThat(logResponse.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertThat(logResponse.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
        assertThat("the response time is written after this, by the caller", logResponse.containsHeader("x-mockserver-response-time-ms"), is(false));
        assertThat(logResponse.getFirstHeader("x-upstream"), is("kept"));
    }

    @Test
    public void shouldNotRecordAFirstChunkDelayWithoutChunkTiming() throws Exception {
        StreamingBody body = new StreamingBody(1024, false);
        body.subscribe(chunk -> {
        }, () -> {
        }, error -> {
        });
        ByteBuf chunk = Unpooled.copiedBuffer("only", StandardCharsets.UTF_8);
        body.addChunk(chunk);
        chunk.release();
        body.complete();

        HttpResponse logResponse = HttpResponse.response();
        HttpActionHandler.attachStreamingHeaders(logResponse, body, TimeService.nanoTime());

        assertThat(logResponse.containsHeader("x-mockserver-first-chunk-delay-ms"), is(false));
        assertThat(logResponse.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
    }
}

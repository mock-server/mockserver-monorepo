package org.mockserver.httpclient;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.HttpResponseDecoder;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;
import org.mockserver.model.StreamingBody;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * A body with neither a Content-Length nor chunked encoding ends when its connection closes, so the decoder turns
 * any close of the upstream into a last chunk. The recording must still tell a close the upstream made from one
 * MockServer made, as it does for a chunked body.
 */
public class StreamingResponseRelayHandlerCloseDelimitedTest {

    private static EmbeddedChannel upstreamWith(Boolean recordClientClosedStreamsComplete) {
        return new EmbeddedChannel(
            new HttpResponseDecoder(),
            new StreamingResponseRelayHandler(
                Configuration.configuration().recordClientClosedStreamsComplete(recordClientClosedStreamsComplete),
                new MockServerLogger(),
                0
            )
        );
    }

    private static void writeBytes(EmbeddedChannel upstream, String bytes) {
        upstream.writeInbound(Unpooled.copiedBuffer(bytes, StandardCharsets.UTF_8));
    }

    private static StreamingBody startCloseDelimitedStreamWithTwoChunks(EmbeddedChannel upstream) throws Exception {
        CompletableFuture<Message> future = new CompletableFuture<>();
        upstream.attr(RESPONSE_FUTURE).set(future);
        writeBytes(upstream, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n\r\n");
        HttpResponse head = (HttpResponse) future.get(10, TimeUnit.SECONDS);
        assertThat("the body is delimited by the connection's close", head.containsHeader("Transfer-Encoding"), is(false));
        StreamingBody body = head.getStreamingBody();
        body.subscribe(chunk -> body.chunkWritten(chunk.readableBytes()), () -> {
        }, error -> {
        });
        writeBytes(upstream, "data: first\n\n");
        writeBytes(upstream, "data: second\n\n");
        assertThat("the stream has not ended yet", body.isCompleted(), is(false));
        return body;
    }

    @Test
    public void shouldCompleteWithChunkDelaysWhenClientClosedAndPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startCloseDelimitedStreamWithTwoChunks(upstream);

        body.closeUpstreamForClientClose();

        assertThat(upstream.isOpen(), is(false));
        assertThat(body.isCompleted(), is(true));
        assertThat(body.isTruncated(), is(false));
        assertThat(body.getError(), is(nullValue()));
        assertThat("recorded as closed by the client", body.isCompletedAfterClientClose(), is(true));
        assertThat("both chunks are timed", body.interChunkDelaysMillis(), hasSize(2));
        assertThat(new String(body.capturedBytes(), StandardCharsets.UTF_8), is("data: first\n\ndata: second\n\n"));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldTruncateWhenClientClosedAndPropertyOff() throws Exception {
        EmbeddedChannel upstream = upstreamWith(false);
        StreamingBody body = startCloseDelimitedStreamWithTwoChunks(upstream);

        body.closeUpstreamForClientClose();

        assertThat(upstream.isOpen(), is(false));
        assertThat(body.isCompleted(), is(true));
        assertThat("the client cut the stream short, so it is not recorded whole", body.isTruncated(), is(true));
        assertThat(body.getError(), instanceOf(StreamingBody.StreamAbortedException.class));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldTruncateWhenMockServerClosedUpstreamItselfAndPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startCloseDelimitedStreamWithTwoChunks(upstream);

        body.closeUpstream();

        assertThat(upstream.isOpen(), is(false));
        assertThat(body.isClosedByClient(), is(false));
        assertThat("MockServer cut the stream short, so it is not recorded whole", body.isTruncated(), is(true));
        assertThat(body.getError(), instanceOf(StreamingBody.StreamAbortedException.class));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldCompleteWhenUpstreamEndsBodyByClosingAndPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startCloseDelimitedStreamWithTwoChunks(upstream);

        upstream.close();

        assertThat(body.isCompleted(), is(true));
        assertThat("the upstream's close is the body's end", body.isTruncated(), is(false));
        assertThat(body.getError(), is(nullValue()));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldCompleteWhenUpstreamEndsChunkedBodyBeforeRequestedCloseTakesEffect() throws Exception {
        EmbeddedChannel upstream = upstreamWith(false);
        CompletableFuture<Message> future = new CompletableFuture<>();
        upstream.attr(RESPONSE_FUTURE).set(future);
        writeBytes(upstream, "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\n\r\n");
        StreamingBody body = ((HttpResponse) future.get(10, TimeUnit.SECONDS)).getStreamingBody();
        body.subscribe(chunk -> body.chunkWritten(chunk.readableBytes()), () -> {
        }, error -> {
        });
        writeBytes(upstream, "d\r\ndata: first\n\n\r\n");
        // the close runs later on the upstream's event loop, so the terminating chunk can still arrive first
        body.setUpstreamCloser(() -> {
        });
        body.closeUpstream();

        writeBytes(upstream, "0\r\n\r\n");

        assertThat(body.isCompleted(), is(true));
        assertThat("the upstream ended the body itself", body.isTruncated(), is(false));
        assertThat(body.getError(), is(nullValue()));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldCompleteWhenUpstreamEndsBodyByClosingAndPropertyOff() throws Exception {
        EmbeddedChannel upstream = upstreamWith(false);
        StreamingBody body = startCloseDelimitedStreamWithTwoChunks(upstream);

        upstream.close();

        assertThat(body.isCompleted(), is(true));
        assertThat(body.isTruncated(), is(false));
        assertThat(body.getError(), is(nullValue()));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        upstream.finishAndReleaseAll();
    }
}

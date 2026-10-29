package org.mockserver.httpclient;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
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
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * When the upstream goes inactive mid-stream, {@code recordClientClosedStreamsComplete} decides whether a stream the
 * client closed ends complete or incomplete; any other loss of the upstream always ends incomplete.
 */
public class StreamingResponseRelayHandlerClientCloseTest {

    private static StreamingBody startStreamWithTwoChunks(EmbeddedChannel upstream) throws Exception {
        CompletableFuture<Message> future = new CompletableFuture<>();
        upstream.attr(RESPONSE_FUTURE).set(future);
        upstream.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        StreamingBody body = ((HttpResponse) future.get(10, TimeUnit.SECONDS)).getStreamingBody();
        body.subscribe(chunk -> body.chunkWritten(chunk.readableBytes()), () -> {
        }, error -> {
        });
        upstream.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("first", StandardCharsets.UTF_8)));
        upstream.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("second", StandardCharsets.UTF_8)));
        return body;
    }

    private static EmbeddedChannel upstreamWith(Boolean recordClientClosedStreamsComplete) {
        return new EmbeddedChannel(new StreamingResponseRelayHandler(
            Configuration.configuration().recordClientClosedStreamsComplete(recordClientClosedStreamsComplete),
            new MockServerLogger(),
            0
        ));
    }

    @Test
    public void shouldCompleteWithChunkDelaysWhenClientClosedAndPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        body.closeUpstreamForClientClose();

        assertThat(upstream.isOpen(), is(false));
        assertThat(body.isCompleted(), is(true));
        assertThat(body.isTruncated(), is(false));
        assertThat(body.getError(), is(nullValue()));
        assertThat(body.isCompletedAfterClientClose(), is(true));
        assertThat(body.interChunkDelaysMillis(), is(notNullValue()));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldTruncateWhenClientClosedAndPropertyOff() throws Exception {
        EmbeddedChannel upstream = upstreamWith(false);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        body.closeUpstreamForClientClose();

        assertThat(body.isTruncated(), is(true));
        assertThat(body.getError(), instanceOf(StreamingBody.StreamAbortedException.class));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldTruncateWhenUpstreamLostByItselfAndPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        upstream.close();

        assertThat(body.isClosedByClient(), is(false));
        assertThat(body.isTruncated(), is(true));
        assertThat(body.getError(), instanceOf(StreamingBody.StreamAbortedException.class));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepAnEarlierCompleteWhenUpstreamEndsAsClientCloses() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        body.complete();
        body.closeUpstreamForClientClose();

        assertThat(body.isCompleted(), is(true));
        assertThat(body.isTruncated(), is(false));
        assertThat("the upstream's own end decided the outcome", body.isCompletedAfterClientClose(), is(false));
        upstream.finishAndReleaseAll();
    }
}

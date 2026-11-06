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
import static org.hamcrest.Matchers.*;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * {@code recordTruncatedStreamTiming} decides whether a stream the upstream cuts short keeps the timing of the chunks
 * captured before the cut; it is still truncated either way, and a stream the client closed is unaffected.
 */
public class StreamingResponseRelayHandlerTruncatedTimingTest {

    private static EmbeddedChannel upstreamWith(Configuration configuration) {
        return new EmbeddedChannel(new StreamingResponseRelayHandler(configuration, new MockServerLogger(), 0));
    }

    private static EmbeddedChannel upstreamWith(Boolean recordTruncatedStreamTiming) {
        return upstreamWith(Configuration.configuration().recordTruncatedStreamTiming(recordTruncatedStreamTiming));
    }

    private static StreamingBody startStreamWithTwoChunks(EmbeddedChannel upstream) throws Exception {
        CompletableFuture<Message> future = new CompletableFuture<>();
        upstream.attr(RESPONSE_FUTURE).set(future);
        upstream.writeInbound(new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        StreamingBody body = ((HttpResponse) future.get(10, TimeUnit.SECONDS)).getStreamingBody();
        body.subscribe(chunk -> body.chunkWritten(chunk.readableBytes()), () -> {
        }, error -> {
        });
        upstream.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("data: one\n\n", StandardCharsets.UTF_8)));
        upstream.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("data: two\n\n", StandardCharsets.UTF_8)));
        return body;
    }

    @Test
    public void shouldKeepTimingWhenTheUpstreamClosesWithoutItsLastChunkAndPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        upstream.close();

        assertThat(body.isTruncated(), is(true));
        assertThat(body.getError(), instanceOf(StreamingBody.StreamAbortedException.class));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldDropTimingWhenTheUpstreamClosesWithoutItsLastChunkAndPropertyOff() throws Exception {
        EmbeddedChannel upstream = upstreamWith(false);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        upstream.close();

        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldDropTimingByDefault() throws Exception {
        EmbeddedChannel upstream = upstreamWith((Boolean) null);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        upstream.close();

        assertThat(body.interChunkDelaysMillis(), is(nullValue()));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepTimingOnAnIdleTimeoutWhenPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        upstream.pipeline().fireExceptionCaught(new StreamingBody.IdleTimeoutException(1));

        assertThat(body.getError(), instanceOf(StreamingBody.IdleTimeoutException.class));
        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepTimingWhenTheUpstreamFailsAndPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(true);
        StreamingBody body = startStreamWithTwoChunks(upstream);

        upstream.pipeline().fireExceptionCaught(new RuntimeException("connection reset"));

        assertThat(body.isTruncated(), is(true));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepTimingUpToTheCaptureLimitWhenPropertyOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(Configuration.configuration()
            .recordTruncatedStreamTiming(true)
            .maxStreamingCaptureBytes(15));
        StreamingBody body = startStreamWithTwoChunks(upstream);
        upstream.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("data: three\n\n", StandardCharsets.UTF_8)));

        assertThat(body.isTruncated(), is(true));
        assertThat("the chunk after the cut is not timed", body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldStillRecordAStreamTheClientClosedCompleteWhenBothPropertiesOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(Configuration.configuration()
            .recordTruncatedStreamTiming(true)
            .recordClientClosedStreamsComplete(true));
        StreamingBody body = startStreamWithTwoChunks(upstream);

        body.closeUpstreamForClientClose();

        assertThat(body.isTruncated(), is(false));
        assertThat(body.isCompletedAfterClientClose(), is(true));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepAnUpstreamLossTruncatedWhenBothPropertiesOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(Configuration.configuration()
            .recordTruncatedStreamTiming(true)
            .recordClientClosedStreamsComplete(true));
        StreamingBody body = startStreamWithTwoChunks(upstream);

        upstream.close();

        assertThat(body.isTruncated(), is(true));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }

    @Test
    public void shouldKeepTimingWhenTheClientClosesWithOnlyTruncatedTimingOn() throws Exception {
        EmbeddedChannel upstream = upstreamWith(Configuration.configuration()
            .recordTruncatedStreamTiming(true)
            .recordClientClosedStreamsComplete(false));
        StreamingBody body = startStreamWithTwoChunks(upstream);

        body.closeUpstreamForClientClose();

        assertThat("without recordClientClosedStreamsComplete a client close is still truncated", body.isTruncated(), is(true));
        assertThat(body.isCompletedAfterClientClose(), is(false));
        assertThat(body.interChunkDelaysMillis(), hasSize(2));
        upstream.finishAndReleaseAll();
    }
}

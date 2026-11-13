package org.mockserver.httpclient;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.DefaultLastHttpContent;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Message;
import org.mockserver.model.StreamingBody;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.httpclient.NettyHttpClient.RESPONSE_FUTURE;

/**
 * The trailers that end a streamed upstream response (an HTTP/1.1 chunked trailer section, or HTTP/2 trailers, which
 * Netty's codec turns into the same last content) are kept on the stream for the writer and the recording.
 */
public class StreamingResponseRelayHandlerTrailersTest {

    @Test
    public void shouldKeepTheTrailersThatEndTheStream() throws Exception {
        LastHttpContent last = new DefaultLastHttpContent(Unpooled.EMPTY_BUFFER);
        last.trailingHeaders().add("x-checksum", "abc123").add("X-Count", "1").add("X-Count", "2");

        StreamingBody streamingBody = relay(last);

        List<Header> trailers = streamingBody.getTrailers().getEntries();
        assertThat(trailers.size(), is(2));
        assertThat(trailers.get(0), is(new Header("x-checksum", "abc123")));
        assertThat(trailers.get(1), is(new Header("X-Count", "1", "2")));
    }

    @Test
    public void shouldKeepNoTrailersForAStreamEndedWithout() throws Exception {
        assertThat(relay(LastHttpContent.EMPTY_LAST_CONTENT).getTrailers(), is(nullValue()));
    }

    @Test
    public void shouldEndTheStreamWithTheUpstreamTrailersOnlyWhenTheResponseHasNone() {
        StreamingBody streamingBody = new StreamingBody(1024);
        streamingBody.setTrailers(new org.mockserver.model.Headers(new Header("x-upstream", "1")));
        HttpResponse withoutOwn = HttpResponse.response().withStreamingBody(streamingBody);
        HttpResponse withOwn = HttpResponse.response().withStreamingBody(streamingBody).withTrailer("x-own", "2");

        assertThat(StreamingBody.withUpstreamTrailers(withoutOwn).getTrailerList(), is(List.of(new Header("x-upstream", "1"))));
        assertThat(StreamingBody.withUpstreamTrailers(withOwn).getTrailerList(), is(List.of(new Header("x-own", "2"))));
        HttpResponse noStream = HttpResponse.response();
        assertThat(StreamingBody.withUpstreamTrailers(noStream), sameInstance(noStream));
    }

    private static StreamingBody relay(LastHttpContent last) throws Exception {
        EmbeddedChannel upstream = new EmbeddedChannel(new StreamingResponseRelayHandler(Configuration.configuration(), new MockServerLogger(), 0));
        try {
            CompletableFuture<Message> future = new CompletableFuture<>();
            upstream.attr(RESPONSE_FUTURE).set(future);
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            upstream.writeInbound(head);
            StreamingBody streamingBody = ((HttpResponse) future.get(10, TimeUnit.SECONDS)).getStreamingBody();
            CompletableFuture<Void> complete = new CompletableFuture<>();
            streamingBody.subscribe(chunk -> streamingBody.chunkWritten(chunk.readableBytes()), () -> complete.complete(null), complete::completeExceptionally);
            upstream.writeInbound(new DefaultHttpContent(Unpooled.copiedBuffer("data: one\n\n", StandardCharsets.UTF_8)));
            upstream.writeInbound(last);
            upstream.runPendingTasks();
            complete.get(10, TimeUnit.SECONDS);
            assertThat(streamingBody.isCompleted(), is(true));
            return streamingBody;
        } finally {
            upstream.finishAndReleaseAll();
        }
    }
}

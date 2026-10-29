package org.mockserver.mock.action.http;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

/**
 * {@code x-mockserver-stream-closed-by-client} is written only for a stream completed because the client closed it;
 * every other streamed log response carries the headers it always did.
 */
public class HttpActionHandlerStreamingHeadersTest {

    private static StreamingBody bodyWithTwoChunks() {
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
        Method method = HttpActionHandler.class.getDeclaredMethod("attachStreamingHeaders", HttpResponse.class, StreamingBody.class);
        method.setAccessible(true);
        HttpResponse logResponse = HttpResponse.response();
        method.invoke(null, logResponse, body);
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
    public void shouldNotMarkAStreamTheClientClosedThatEndedIncomplete() throws Exception {
        StreamingBody body = bodyWithTwoChunks();
        body.closeUpstreamForClientClose();
        body.error(new StreamingBody.StreamAbortedException("upstream closed mid-stream"));

        HttpResponse response = headersFor(body);

        assertThat(response.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
        assertThat(response.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat(response.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
    }

    @Test
    public void shouldNotMarkAStreamThatEndedNormally() throws Exception {
        StreamingBody body = bodyWithTwoChunks();
        body.complete();

        HttpResponse response = headersFor(body);

        assertThat(response.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
        assertThat(response.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertThat(response.getFirstHeader("x-mockserver-chunk-delays-ms"), is(notNullValue()));
        assertThat(response.getFirstHeader("x-mockserver-streamed"), is("true"));
    }
}

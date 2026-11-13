package org.mockserver.mock.action.http;

import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.StreamingBody;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpResponse.response;

/**
 * Memory-hygiene regression test for the streaming FORWARDED_REQUEST log entry.
 * <p>
 * When a streaming forward completes, the completion listener clones the live response and stores
 * the FIXED captured bytes on the clone. {@code HttpResponse.clone()} copies the live
 * {@link StreamingBody} reference (the live response still needs it), so unless the clone clears it
 * the retained log entry would pin the live ≤256 KB capture buffer, the upstream event loop, and the
 * onChunk/requestMore callbacks for the entry's lifetime in the ring buffer. The fix clears the
 * streaming body inside {@code HttpActionHandler.setCapturedStreamingBody(...)}, the single helper
 * used by all four streaming-completion sites.
 */
public class HttpActionHandlerStreamingLogBodyTest {

    private static void invokeSetCapturedStreamingBody(HttpResponse logResponse, byte[] captured) throws Exception {
        Method method = HttpActionHandler.class.getDeclaredMethod("setCapturedStreamingBody", HttpResponse.class, byte[].class);
        method.setAccessible(true);
        method.invoke(null, logResponse, captured);
    }

    @Test
    public void shouldStoreCapturedBodyAndClearLiveStreamingBodyOnLogClone() throws Exception {
        // Given a live streaming response as produced by the relay handler
        StreamingBody liveBody = new StreamingBody(256 * 1024);
        HttpResponse streamingResponse = response().withStreamingBody(liveBody);

        // When the completion listener clones it (clone copies the live streaming-body reference)
        HttpResponse logResponse = streamingResponse.clone();
        assertThat("clone() must copy the live streaming body for the live response",
            logResponse.getStreamingBody(), is(sameInstance(liveBody)));

        // ...and stores the captured bytes on the clone
        byte[] captured = "data: hello\n\n".getBytes(StandardCharsets.UTF_8);
        invokeSetCapturedStreamingBody(logResponse, captured);

        // Then the stored log copy carries the FIXED captured body
        assertThat(logResponse.getBodyAsString(), is("data: hello\n\n"));
        // ...and no longer pins the live StreamingBody (buffer / event loop / callbacks)
        assertThat(logResponse.getStreamingBody(), is(nullValue()));

        // And the original LIVE response is untouched — clone() must still copy the live body
        assertThat(streamingResponse.getStreamingBody(), is(sameInstance(liveBody)));
    }

    @Test
    public void shouldClearStreamingBodyEvenWhenCapturedBytesEmpty() throws Exception {
        StreamingBody liveBody = new StreamingBody(256 * 1024);
        HttpResponse logResponse = response().withStreamingBody(liveBody).clone();

        // Empty capture (e.g. an immediately-closed stream) must still release the live body
        invokeSetCapturedStreamingBody(logResponse, new byte[0]);

        assertThat(logResponse.getStreamingBody(), is(nullValue()));
    }

    @Test
    public void shouldStoreABodyStillInAContentCodingAsItsBytes() throws Exception {
        byte[] encoded = {0x1f, (byte) 0x8b, 0x08, 0x00, (byte) 0xe9, (byte) 0xff, 0x7f, (byte) 0x80};
        HttpResponse logResponse = response()
            .withHeader("Content-Type", "text/event-stream")
            .withHeader("Content-Encoding", "x-unknown");

        invokeSetCapturedStreamingBody(logResponse, encoded);

        assertThat(logResponse.getBody().getType(), is(org.mockserver.model.Body.Type.BINARY));
        assertThat(logResponse.getBodyAsRawBytes(), is(encoded));
    }

    @Test
    public void shouldStoreANonAsciiEventStreamWithoutACharsetAsUtf8Text() throws Exception {
        HttpResponse logResponse = response().withHeader("Content-Type", "text/event-stream");

        invokeSetCapturedStreamingBody(logResponse, "data: café 🎉\n\n".getBytes(StandardCharsets.UTF_8));

        assertThat(logResponse.getBodyAsString(), is("data: café 🎉\n\n"));
    }
}

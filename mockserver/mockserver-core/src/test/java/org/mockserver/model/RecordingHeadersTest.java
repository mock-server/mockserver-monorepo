package org.mockserver.model;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpResponse.response;

public class RecordingHeadersTest {

    @Test
    public void shouldRemoveEveryRecordingHeaderWhateverItsCase() {
        HttpResponse stripped = RecordingHeaders.strip(response()
            .withHeader("X-MockServer-Streamed", "true")
            .withHeader("x-mockserver-stream-truncated", "true")
            .withHeader("x-mockserver-stream-closed-by-client", "true")
            .withHeader("X-MockServer-Stream-Closed-By-MockServer-Stop", "true")
            .withHeader("x-mockserver-chunk-delays-ms", "0,1")
            .withHeader("x-mockserver-chunk-sizes-bytes", "3,4")
            .withHeader("x-mockserver-first-chunk-delay-ms", "5")
            .withHeader("x-mockserver-response-time-ms", "9")
            .withHeader("x-mockserver-other", "kept")
            .withHeader("Content-Type", "text/plain"));

        for (String name : RecordingHeaders.ALL) {
            assertThat(name, stripped.containsHeader(name), is(false));
        }
        assertThat(stripped.getFirstHeader("x-mockserver-other"), is("kept"));
        assertThat(stripped.getFirstHeader("Content-Type"), is("text/plain"));
    }

    @Test
    public void shouldLeaveAResponseWithoutRecordingHeadersAlone() {
        HttpResponse response = response().withHeader("Content-Type", "text/plain");

        assertThat(RecordingHeaders.strip(response), is(sameInstance(response)));
        assertThat(response.getFirstHeader("Content-Type"), is("text/plain"));
    }
}

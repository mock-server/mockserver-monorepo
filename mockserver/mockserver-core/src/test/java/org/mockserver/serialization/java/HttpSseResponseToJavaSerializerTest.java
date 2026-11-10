package org.mockserver.serialization.java;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.SseEvent.sseEvent;

public class HttpSseResponseToJavaSerializerTest {

    @Test
    public void shouldSerializeAnEventComment() {
        String java = new HttpSseResponseToJavaSerializer().serialize(1,
            sseResponse().withEvent(sseEvent().withComment("heartbeat")));

        assertThat(java, containsString("SseEvent.sseEvent()"));
        assertThat(java, containsString(".withComment(\"heartbeat\")"));
    }

    @Test
    public void shouldNotSerializeAnAbsentComment() {
        String java = new HttpSseResponseToJavaSerializer().serialize(1,
            sseResponse().withEvent(sseEvent().withData("payload")));

        assertThat(java, containsString(".withData(\"payload\")"));
        assertThat(java, not(containsString("withComment")));
    }

    @Test
    public void shouldSerializeTheEndOfTheStream() {
        String java = new HttpSseResponseToJavaSerializer().serialize(1,
            sseResponse().withEvent(sseEvent().withData("payload"))
                .withCloseDelay(org.mockserver.model.Delay.milliseconds(1500))
                .withKeepStreamOpen(true));

        assertThat(java, containsString(".withCloseDelay(new Delay(TimeUnit.MILLISECONDS, 1500))"));
        assertThat(java, containsString(".withKeepStreamOpen(true)"));
    }

    @Test
    public void shouldNotSerializeAnAbsentEndOfTheStream() {
        String java = new HttpSseResponseToJavaSerializer().serialize(1,
            sseResponse().withEvent(sseEvent().withData("payload")));

        assertThat(java, not(containsString("withCloseDelay")));
        assertThat(java, not(containsString("withKeepStreamOpen")));
    }
}

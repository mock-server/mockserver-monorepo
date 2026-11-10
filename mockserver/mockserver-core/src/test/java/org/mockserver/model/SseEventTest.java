package org.mockserver.model;

import org.junit.Test;

import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.SseEvent.sseEvent;

import static org.hamcrest.core.IsSame.sameInstance;
public class SseEventTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(sseEvent(), is(sseEvent()));
        assertThat(sseEvent(), not(sameInstance(sseEvent())));
    }

    @Test
    public void shouldBuildWithAllFields() {
        // when
        SseEvent event = sseEvent()
            .withEvent("msg")
            .withData("hi")
            .withId("1")
            .withRetry(5000);

        // then
        assertThat(event.getEvent(), is("msg"));
        assertThat(event.getData(), is("hi"));
        assertThat(event.getId(), is("1"));
        assertThat(event.getRetry(), is(5000));
    }

    @Test
    public void shouldIncludeCommentInEquality() {
        assertThat(sseEvent().withComment("hb").getComment(), is("hb"));
        assertThat(sseEvent().withComment("hb"), is(sseEvent().withComment("hb")));
        assertThat(sseEvent().withComment("hb"), not(sseEvent().withComment("other")));
        assertThat(sseEvent().withData("d").withComment("hb"), not(sseEvent().withData("d")));
    }

    @Test
    public void shouldHaveNullDefaultValues() {
        // when
        SseEvent event = sseEvent();

        // then
        assertThat(event.getEvent(), is(nullValue()));
        assertThat(event.getData(), is(nullValue()));
        assertThat(event.getId(), is(nullValue()));
        assertThat(event.getRetry(), is(nullValue()));
        assertThat(event.getComment(), is(nullValue()));
        assertThat(event.getDelay(), is(nullValue()));
    }

    @Test
    public void shouldSetDelayWithTimeUnit() {
        // when
        SseEvent event = sseEvent().withDelay(TimeUnit.MILLISECONDS, 100);

        // then
        assertThat(event.getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 100)));
    }

    @Test
    public void shouldSetDelayWithDelayObject() {
        // when
        SseEvent event = sseEvent().withDelay(Delay.milliseconds(100));

        // then
        assertThat(event.getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 100)));
    }

    @Test
    public void shouldReturnEvent() {
        assertThat(sseEvent().withEvent("message").getEvent(), is("message"));
    }

    @Test
    public void shouldReturnData() {
        assertThat(sseEvent().withData("some_data").getData(), is("some_data"));
    }

    @Test
    public void shouldReturnId() {
        assertThat(sseEvent().withId("42").getId(), is("42"));
    }

    @Test
    public void shouldReturnRetry() {
        assertThat(sseEvent().withRetry(3000).getRetry(), is(3000));
    }

    @Test
    public void shouldBeEqualWhenSameValues() {
        // given
        SseEvent eventOne = sseEvent()
            .withEvent("msg")
            .withData("hi")
            .withId("1")
            .withRetry(5000)
            .withDelay(TimeUnit.MILLISECONDS, 100);
        SseEvent eventTwo = sseEvent()
            .withEvent("msg")
            .withData("hi")
            .withId("1")
            .withRetry(5000)
            .withDelay(TimeUnit.MILLISECONDS, 100);

        // then
        assertThat(eventOne, is(eventTwo));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        // given
        SseEvent eventOne = sseEvent()
            .withEvent("msg")
            .withData("hi")
            .withId("1")
            .withRetry(5000);
        SseEvent eventTwo = sseEvent()
            .withEvent("msg")
            .withData("hi")
            .withId("1")
            .withRetry(5000);

        // then
        assertThat(eventOne.hashCode(), is(eventTwo.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentEvent() {
        assertThat(sseEvent().withEvent("msg"), is(not(sseEvent().withEvent("other"))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentData() {
        assertThat(sseEvent().withData("data1"), is(not(sseEvent().withData("data2"))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentId() {
        assertThat(sseEvent().withId("1"), is(not(sseEvent().withId("2"))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentRetry() {
        assertThat(sseEvent().withRetry(1000), is(not(sseEvent().withRetry(2000))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentDelay() {
        assertThat(
            sseEvent().withDelay(TimeUnit.MILLISECONDS, 100),
            is(not(sseEvent().withDelay(TimeUnit.SECONDS, 5)))
        );
    }

    @Test
    public void shouldNotBeEqualToNull() {
        assertThat(sseEvent().withEvent("msg").equals(null), is(false));
    }

    @Test
    public void shouldNotBeEqualToDifferentType() {
        assertThat(sseEvent().withEvent("msg").equals("msg"), is(false));
    }

    @Test
    public void shouldBeEqualToItself() {
        // given
        SseEvent event = sseEvent().withEvent("msg");

        // then
        assertThat(event, is(event));
    }
}

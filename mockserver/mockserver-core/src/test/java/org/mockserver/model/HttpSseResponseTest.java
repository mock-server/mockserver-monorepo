package org.mockserver.model;

import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsNot.not;
import static org.hamcrest.core.IsNull.nullValue;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.SseEvent.sseEvent;

import static org.hamcrest.core.IsSame.sameInstance;
public class HttpSseResponseTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(sseResponse(), is(sseResponse()));
        assertThat(sseResponse(), not(sameInstance(sseResponse())));
    }

    @Test
    public void shouldBuildWithAllFields() {
        // when
        SseEvent event = sseEvent().withEvent("msg").withData("hi");
        HttpSseResponse response = sseResponse()
            .withStatusCode(200)
            .withEvent(event)
            .withCloseConnection(true);

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getEvents(), hasSize(1));
        assertThat(response.getEvents().get(0), is(event));
        assertThat(response.getCloseConnection(), is(true));
    }

    @Test
    public void shouldHaveNullDefaultValues() {
        // when
        HttpSseResponse response = sseResponse();

        // then
        assertThat(response.getStatusCode(), is(nullValue()));
        assertThat(response.getEvents(), is(nullValue()));
        assertThat(response.getHeaders(), is(nullValue()));
        assertThat(response.getCloseConnection(), is(nullValue()));
    }

    @Test
    public void shouldReturnType() {
        assertThat(sseResponse().getType(), is(Action.Type.SSE_RESPONSE));
    }

    @Test
    public void shouldReturnStatusCode() {
        assertThat(sseResponse().withStatusCode(200).getStatusCode(), is(200));
    }

    @Test
    public void shouldReturnCloseConnection() {
        assertThat(sseResponse().withCloseConnection(true).getCloseConnection(), is(true));
        assertThat(sseResponse().withCloseConnection(false).getCloseConnection(), is(false));
    }

    @Test
    public void shouldIncludeTheEndOfTheStreamInEquality() {
        assertThat(sseResponse().withCloseDelay(Delay.seconds(15)).getCloseDelay(), is(Delay.seconds(15)));
        assertThat(sseResponse().withCloseDelay(TimeUnit.SECONDS, 15).getCloseDelay(), is(Delay.seconds(15)));
        assertThat(sseResponse().withKeepStreamOpen(true).getKeepStreamOpen(), is(true));
        assertThat(sseResponse().getCloseDelay(), is(nullValue()));
        assertThat(sseResponse().getKeepStreamOpen(), is(nullValue()));
        assertThat(sseResponse().withCloseDelay(Delay.seconds(15)), is(sseResponse().withCloseDelay(Delay.seconds(15))));
        assertThat(sseResponse().withCloseDelay(Delay.seconds(15)), not(sseResponse().withCloseDelay(Delay.seconds(16))));
        assertThat(sseResponse().withKeepStreamOpen(true), not(sseResponse()));
        HttpSseResponse response = sseResponse().withCloseDelay(Delay.seconds(1));
        int hashBefore = response.hashCode();
        assertThat(response.withCloseDelay(Delay.seconds(2)).hashCode(), not(hashBefore));
    }

    @Test
    public void shouldAddSingleEvent() {
        // given
        SseEvent eventOne = sseEvent().withEvent("msg1").withData("data1");
        SseEvent eventTwo = sseEvent().withEvent("msg2").withData("data2");

        // when
        HttpSseResponse response = sseResponse()
            .withEvent(eventOne)
            .withEvent(eventTwo);

        // then
        assertThat(response.getEvents(), hasSize(2));
        assertThat(response.getEvents(), containsInAnyOrder(eventOne, eventTwo));
    }

    @Test
    public void shouldAddMultipleEventsViaVarargs() {
        // given
        SseEvent eventOne = sseEvent().withEvent("msg1");
        SseEvent eventTwo = sseEvent().withEvent("msg2");

        // when
        HttpSseResponse response = sseResponse()
            .withEvents(eventOne, eventTwo);

        // then
        assertThat(response.getEvents(), hasSize(2));
        assertThat(response.getEvents(), containsInAnyOrder(eventOne, eventTwo));
    }

    @Test
    public void shouldAddMultipleEventsViaList() {
        // given
        SseEvent eventOne = sseEvent().withEvent("msg1");
        SseEvent eventTwo = sseEvent().withEvent("msg2");
        List<SseEvent> events = Arrays.asList(eventOne, eventTwo);

        // when
        HttpSseResponse response = sseResponse()
            .withEvents(events);

        // then
        assertThat(response.getEvents(), hasSize(2));
        assertThat(response.getEvents(), containsInAnyOrder(eventOne, eventTwo));
    }

    @Test
    public void shouldSetHeaderWithNameAndValues() {
        // when
        HttpSseResponse response = sseResponse()
            .withHeader("name", "value");

        // then
        assertThat(response.getHeaders().getEntries(), hasSize(1));
    }

    @Test
    public void shouldSetHeaderWithHeaderObject() {
        // when
        HttpSseResponse response = sseResponse()
            .withHeader(new Header("name", "value"));

        // then
        assertThat(response.getHeaders().getEntries(), hasSize(1));
    }

    @Test
    public void shouldSetHeadersWithHeadersObject() {
        // when
        Headers headers = new Headers(new Header("name1", "value1"), new Header("name2", "value2"));
        HttpSseResponse response = sseResponse()
            .withHeaders(headers);

        // then
        assertThat(response.getHeaders(), is(headers));
    }

    @Test
    public void shouldBeEqualWhenSameValues() {
        // given
        SseEvent event = sseEvent().withEvent("msg").withData("hi");
        HttpSseResponse responseOne = sseResponse()
            .withStatusCode(200)
            .withEvents(event)
            .withCloseConnection(true);
        HttpSseResponse responseTwo = sseResponse()
            .withStatusCode(200)
            .withEvents(event)
            .withCloseConnection(true);

        // then
        assertThat(responseOne, is(responseTwo));
    }

    @Test
    public void shouldHaveSameHashCodeWhenEqual() {
        // given
        SseEvent event = sseEvent().withEvent("msg").withData("hi");
        HttpSseResponse responseOne = sseResponse()
            .withStatusCode(200)
            .withEvents(event)
            .withCloseConnection(true);
        HttpSseResponse responseTwo = sseResponse()
            .withStatusCode(200)
            .withEvents(event)
            .withCloseConnection(true);

        // then
        assertThat(responseOne.hashCode(), is(responseTwo.hashCode()));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentStatusCode() {
        assertThat(sseResponse().withStatusCode(200), is(not(sseResponse().withStatusCode(404))));
    }

    @Test
    public void shouldNotBeEqualWhenDifferentEvents() {
        assertThat(
            sseResponse().withEvents(sseEvent().withEvent("msg1")),
            is(not(sseResponse().withEvents(sseEvent().withEvent("msg2"))))
        );
    }

    @Test
    public void shouldNotBeEqualWhenDifferentCloseConnection() {
        assertThat(
            sseResponse().withCloseConnection(true),
            is(not(sseResponse().withCloseConnection(false)))
        );
    }

    @Test
    public void shouldNotBeEqualToNull() {
        assertThat(sseResponse().withStatusCode(200).equals(null), is(false));
    }

    @Test
    public void shouldNotBeEqualToDifferentType() {
        assertThat(sseResponse().withStatusCode(200).equals("response"), is(false));
    }

    @Test
    public void shouldBeEqualToItself() {
        // given
        HttpSseResponse response = sseResponse().withStatusCode(200);

        // then
        assertThat(response, is(response));
    }

    @Test
    public void shouldSupportDelay() {
        // when
        HttpSseResponse response = sseResponse()
            .withDelay(TimeUnit.SECONDS, 3);

        // then
        assertThat(response.getDelay(), is(new Delay(TimeUnit.SECONDS, 3)));
    }

    @Test
    public void shouldSupportDelayObject() {
        // when
        HttpSseResponse response = sseResponse()
            .withDelay(Delay.milliseconds(500));

        // then
        assertThat(response.getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 500)));
    }
}

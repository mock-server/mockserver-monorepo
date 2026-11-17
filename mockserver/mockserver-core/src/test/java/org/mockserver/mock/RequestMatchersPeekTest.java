package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Tests for {@link RequestMatchers#peekFirstMatchingExpectation}, the side-effect-free
 * match probe used by the gRPC bidi router. Proves that the method returns the first
 * matching expectation WITHOUT consuming Times, transitioning scenarios, setting
 * responseInProgress, or emitting metrics/logs.
 */
public class RequestMatchersPeekTest {

    private RequestMatchers requestMatchers;

    @Before
    public void setup() {
        requestMatchers = new RequestMatchers(
            configuration(), new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    /**
     * Core proof for FIX 1: a times(1) expectation is NOT consumed by peekFirstMatchingExpectation.
     * After peeking, the expectation must still be active (remaining times = 1) and a
     * subsequent firstMatchingExpectation call must still find and consume it.
     */
    @Test
    public void peekShouldNotConsumeTimesOnExpectation() {
        // Arrange: add a times(1) expectation
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/test/service/Method"),
            Times.once(), null, 0
        ).thenRespond(response().withStatusCode(200));

        requestMatchers.add(expectation, API);

        HttpRequest matchingRequest = request()
            .withMethod("POST")
            .withPath("/test/service/Method");

        // Act: peek (should NOT consume)
        Expectation peeked = requestMatchers.peekFirstMatchingExpectation(matchingRequest);

        // Assert: peek found the expectation
        assertThat("peek should find the matching expectation", peeked, is(notNullValue()));
        assertThat("peek should return the correct expectation",
            peeked.getHttpResponse().getStatusCode(), is(200));

        // Assert: the expectation is still active (Times not consumed)
        assertThat("expectation should still be active after peek",
            peeked.isActive(), is(true));
        assertThat("remaining times should still be 1 after peek",
            peeked.getTimes().getRemainingTimes(), is(1));

        // Assert: consuming firstMatchingExpectation still finds and consumes it
        Expectation consumed = requestMatchers.firstMatchingExpectation(matchingRequest);
        assertThat("consuming match should find the expectation after peek",
            consumed, is(notNullValue()));

        // After consuming, the expectation should be exhausted
        assertThat("remaining times should be 0 after consume",
            consumed.getTimes().getRemainingTimes(), is(0));
    }

    /**
     * After peek + consume, a second firstMatchingExpectation call should return null
     * (the times(1) expectation is exhausted).
     */
    @Test
    public void peekThenConsumeThenSecondConsumeShouldReturnNull() {
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/test/path"),
            Times.once(), null, 0
        ).thenRespond(response().withStatusCode(201));

        requestMatchers.add(expectation, API);

        HttpRequest matchingRequest = request()
            .withMethod("POST")
            .withPath("/test/path");

        // Peek: does not consume
        Expectation peeked = requestMatchers.peekFirstMatchingExpectation(matchingRequest);
        assertThat(peeked, is(notNullValue()));

        // Consume: decrements times
        Expectation consumed = requestMatchers.firstMatchingExpectation(matchingRequest);
        assertThat(consumed, is(notNullValue()));

        // Post-process to clear responseInProgress
        requestMatchers.postProcess(consumed);

        // Second consume: expectation exhausted, should return null
        Expectation secondConsume = requestMatchers.firstMatchingExpectation(matchingRequest);
        assertThat("second consume should return null (times exhausted)",
            secondConsume, is(nullValue()));
    }

    /**
     * Peek returns null when no expectation matches.
     */
    @Test
    public void peekShouldReturnNullWhenNoMatch() {
        requestMatchers.add(new Expectation(
            request().withMethod("GET").withPath("/other")
        ).thenRespond(response().withStatusCode(200)), API);

        Expectation peeked = requestMatchers.peekFirstMatchingExpectation(
            request().withMethod("POST").withPath("/no/match"));

        assertThat("peek should return null when no expectation matches",
            peeked, is(nullValue()));
    }

    /**
     * Peek returns null for null input.
     */
    @Test
    public void peekShouldReturnNullForNullInput() {
        requestMatchers.add(new Expectation(
            request().withMethod("GET").withPath("/foo")
        ).thenRespond(response().withStatusCode(200)), API);

        Expectation peeked = requestMatchers.peekFirstMatchingExpectation(null);

        assertThat("peek should return null for null request",
            peeked, is(nullValue()));
    }

    /**
     * Peek returns the highest-priority matching expectation (same priority order
     * as firstMatchingExpectation).
     */
    @Test
    public void peekShouldReturnHighestPriorityMatch() {
        requestMatchers.add(new Expectation(
            request().withMethod("POST").withPath("/multi")
        ).withPriority(0).thenRespond(response().withStatusCode(200)), API);

        requestMatchers.add(new Expectation(
            request().withMethod("POST").withPath("/multi")
        ).withPriority(10).thenRespond(response().withStatusCode(201)), API);

        Expectation peeked = requestMatchers.peekFirstMatchingExpectation(
            request().withMethod("POST").withPath("/multi"));

        assertThat("peek should return the highest-priority match",
            peeked, is(notNullValue()));
        assertThat("highest-priority expectation should be returned",
            peeked.getHttpResponse().getStatusCode(), is(201));
    }

    /**
     * Multiple peeks on the same times(1) expectation should all succeed (no consumption).
     */
    @Test
    public void multiplePeeksShouldNotConsumeExpectation() {
        Expectation expectation = new Expectation(
            request().withMethod("POST").withPath("/stable"),
            Times.once(), null, 0
        ).thenRespond(response().withStatusCode(200));

        requestMatchers.add(expectation, API);

        HttpRequest req = request().withMethod("POST").withPath("/stable");

        // Peek 10 times — none should consume
        for (int i = 0; i < 10; i++) {
            Expectation peeked = requestMatchers.peekFirstMatchingExpectation(req);
            assertThat("peek #" + (i + 1) + " should find the expectation",
                peeked, is(notNullValue()));
        }

        // Times should still be 1
        assertThat("remaining times should still be 1 after 10 peeks",
            expectation.getTimes().getRemainingTimes(), is(1));

        // Consuming match should still work
        Expectation consumed = requestMatchers.firstMatchingExpectation(req);
        assertThat("consuming match should succeed after multiple peeks",
            consumed, is(notNullValue()));
    }

    @Test
    public void peekWritesNoMatchResultEntriesAtInfo() {
        Configuration configuration = configuration().logLevel("INFO");
        RequestMatchersUnmatchedDiagnosticsTest.CapturingLogger logger = new RequestMatchersUnmatchedDiagnosticsTest.CapturingLogger(configuration);
        RequestMatchers matchers = new RequestMatchers(configuration, logger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        for (int i = 0; i < 5; i++) {
            matchers.add(new Expectation(request().withMethod("POST").withPath("/other/" + i)).thenRespond(response().withStatusCode(200)), API);
        }
        matchers.add(new Expectation(request().withMethod("POST").withPath("/peeked")).thenRespond(response().withStatusCode(201)), API);

        logger.rendered.clear();

        Expectation peeked = matchers.peekFirstMatchingExpectation(request().withMethod("POST").withPath("/peeked"));

        assertThat(peeked.getHttpResponse().getStatusCode(), is(201));
        assertThat(logger.rendered, is(empty()));
    }
}

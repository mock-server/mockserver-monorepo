package org.mockserver.inprocess;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RateLimit;
import org.mockserver.ratelimit.RateLimitRegistry;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.inprocess.InProcessMockServerGeneratedResponseTest.assertGenerated;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.RateLimit.rateLimit;
import static org.mockserver.model.SseEvent.sseEvent;

/**
 * The {@code 429} MockServer makes for an expectation over its rate limit reaches a response transformer with
 * {@code isGenerated()} true. The limits live in the JVM-wide {@link RateLimitRegistry}, which a reset clears, so this
 * class is registered in the sequential Surefire phase (see mockserver-core/pom.xml).
 */
public class InProcessMockServerGeneratedRateLimitResponseTest {

    private InProcessMockServer mockServer;

    @Before
    public void start() {
        RateLimitRegistry.getInstance().reset();
        mockServer = new InProcessMockServer(configuration().extensions(InProcessMockServerGeneratedResponseTest.GeneratedRecorder.class));
    }

    @After
    public void close() {
        if (mockServer != null) {
            mockServer.close();
        }
        RateLimitRegistry.getInstance().reset();
    }

    @Test
    public void shouldMarkTheRateLimitResponseOnly() {
        mockServer.upsert(when(request("/generated/rate-limited"))
            .thenRespond(response("within the limit"))
            .withRateLimit(oneRequestPerMinute("in-process-generated-rate-limit")));

        HttpResponse withinTheLimit = mockServer.handle(request("/generated/rate-limited"));
        HttpResponse overTheLimit = mockServer.handle(request("/generated/rate-limited"));

        assertThat(withinTheLimit.getFirstHeader("x-generated"), is("false"));
        assertGenerated(overTheLimit, 429);
    }

    @Test
    public void shouldMarkTheRateLimitResponseForAnSseAction() {
        mockServer.upsert(when(request("/generated/sse-rate-limited"))
            .thenRespondWithSse(sseResponse().withEvent(sseEvent().withData("first")))
            .withRateLimit(oneRequestPerMinute("in-process-generated-sse-rate-limit")));

        mockServer.handle(request("/generated/sse-rate-limited"));

        assertGenerated(mockServer.handle(request("/generated/sse-rate-limited")), 429);
    }

    private static RateLimit oneRequestPerMinute(String name) {
        return rateLimit()
            .withName(name)
            .withAlgorithm(RateLimit.Algorithm.FIXED_WINDOW)
            .withLimit(1)
            .withWindowMillis(60_000L);
    }
}

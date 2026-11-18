package org.mockserver.mock;

import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * {@code maxLoggedMatchFailures} for the early (respondBeforeBody, headers-only) scan: one request's matching is
 * logged once, within the limit. A request the early scan serves logs what that scan evaluated, as the serving scan
 * would; a request it does not serve is logged only by the full-body serving scan that follows. Instance
 * configuration and a capturing logger only, so it runs in the parallel phase.
 */
public class RequestMatchersEarlyMatchLoggingTest {

    private static final int OTHER_EARLY = 5;
    private static final int ORDINARY = 5;

    private static final class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> entries = new ArrayList<>();

        CapturingLogger(Configuration configuration) {
            super(configuration, LoggerFactory.getLogger(CapturingLogger.class));
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                entries.add(logEntry);
            }
        }
    }

    private CapturingLogger logger;

    private RequestMatchers requestMatchers(Configuration configuration, Times uploadTimes) {
        logger = new CapturingLogger(configuration);
        RequestMatchers matchers = new RequestMatchers(configuration, logger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        for (int i = 0; i < OTHER_EARLY; i++) {
            matchers.add(expectation("early-" + i, request().withMethod("POST").withPath("/early-" + i).withRespondBeforeBody(true), Times.unlimited()), API);
        }
        for (int i = 0; i < ORDINARY; i++) {
            matchers.add(expectation("ordinary-" + i, request().withMethod("GET").withPath("/ordinary-" + i), Times.unlimited()), API);
        }
        matchers.add(expectation("upload", request().withMethod("POST").withPath("/upload").withRespondBeforeBody(true), uploadTimes), API);
        logger.entries.clear();
        return matchers;
    }

    @Test
    public void servedEarlyLogsTheMatchedEntryAndDidNotMatchEntriesWithinTheLimit() {
        for (boolean detailed : new boolean[]{true, false}) {
            RequestMatchers matchers = requestMatchers(configuration().logLevel("INFO").maxLoggedMatchFailures(1).detailedMatchFailures(detailed), Times.unlimited());

            Expectation served = matchers.firstMatchingEarlyExpectation(headersOnly("POST", "/upload"));

            assertThat(served.getId(), is("upload"));
            assertThat("detailed=" + detailed, ids(EXPECTATION_NOT_MATCHED), contains("early-0"));
            assertThat("detailed=" + detailed, ids(EXPECTATION_MATCHED), contains("upload"));
        }
    }

    @Test
    public void servedEarlyWithNoLimitLogsEachEvaluatedCandidateOnce() {
        RequestMatchers matchers = requestMatchers(configuration().logLevel("INFO").maxLoggedMatchFailures(-1), Times.unlimited());

        Expectation served = matchers.firstMatchingEarlyExpectation(headersOnly("POST", "/upload"));

        assertThat(served.getId(), is("upload"));
        assertThat(ids(EXPECTATION_NOT_MATCHED), contains("early-0", "early-1", "early-2", "early-3", "early-4"));
        assertThat(ids(EXPECTATION_MATCHED), contains("upload"));
    }

    @Test
    public void servedEarlyByItsLastTimeStillLogsTheMatchedEntry() {
        RequestMatchers matchers = requestMatchers(configuration().logLevel("INFO").maxLoggedMatchFailures(0), Times.once());

        Expectation served = matchers.firstMatchingEarlyExpectation(headersOnly("POST", "/upload"));

        assertThat(served.getId(), is("upload"));
        assertThat(ids(EXPECTATION_NOT_MATCHED), is(empty()));
        assertThat(ids(EXPECTATION_MATCHED), contains("upload"));
    }

    @Test
    public void notServedEarlyIsLoggedOnlyByTheServingScanWithinTheLimit() {
        RequestMatchers matchers = requestMatchers(configuration().logLevel("INFO").maxLoggedMatchFailures(1), Times.unlimited());

        assertThat(matchers.firstMatchingEarlyExpectation(headersOnly("GET", "/ordinary-3")), nullValue());
        assertThat(logger.entries, is(empty()));

        Expectation served = matchers.firstMatchingExpectation(headersOnly("GET", "/ordinary-3"));

        assertThat(served.getId(), is("ordinary-3"));
        assertThat(ids(EXPECTATION_NOT_MATCHED), contains("early-0"));
        assertThat(ids(EXPECTATION_MATCHED), contains("ordinary-3"));
    }

    @Test
    public void servesTheSameExpectationAtEveryLogLevelAndLimit() {
        for (String logLevel : new String[]{"WARN", "INFO", "TRACE"}) {
            for (int limit : new int[]{-1, 0, 1}) {
                String label = logLevel + " limit=" + limit;
                RequestMatchers matchers = requestMatchers(configuration().logLevel(logLevel).maxLoggedMatchFailures(limit), Times.unlimited());

                assertThat(label, matchers.firstMatchingEarlyExpectation(headersOnly("POST", "/upload")).getId(), is("upload"));
                assertThat(label, matchers.firstMatchingEarlyExpectation(headersOnly("POST", "/early-2")).getId(), is("early-2"));
                assertThat(label, matchers.firstMatchingEarlyExpectation(headersOnly("GET", "/ordinary-3")), nullValue());
                assertThat(label, matchers.firstMatchingEarlyExpectation(headersOnly("POST", "/missing")), nullValue());
            }
        }
    }

    @Test
    public void loggingAnEarlierMatchingButSkippedCandidateLeavesTheServedRequestsPathGroups() {
        for (String logLevel : new String[]{"WARN", "INFO"}) {
            Configuration configuration = configuration().logLevel(logLevel).maxLoggedMatchFailures(-1);
            logger = new CapturingLogger(configuration);
            RequestMatchers matchers = new RequestMatchers(configuration, logger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
            // matches the request first, but waits for a scenario state that is never current, so it is skipped
            matchers.add(new Expectation(request().withMethod("POST").withPath("/upload/(?<id>[a-z]+)").withRespondBeforeBody(true), Times.unlimited(), TimeToLive.unlimited(), 10)
                .withId("skipped").withScenarioName("never").withScenarioState("never-current")
                .thenRespond(response().withStatusCode(200)), API);
            matchers.add(expectation("served", request().withMethod("POST").withPath("/upload/(?<name>[a-z]+)").withRespondBeforeBody(true), Times.unlimited()), API);
            HttpRequest request = headersOnly("POST", "/upload/abc");

            Expectation served = matchers.firstMatchingEarlyExpectation(request);

            assertThat(logLevel, served.getId(), is("served"));
            assertThat(logLevel, request.getNamedPathGroups(), is(Map.of("name", "abc")));
            assertThat(logLevel, request.getPathGroups(), contains("/upload/abc", "abc"));
        }
    }

    private List<String> ids(LogEntry.LogMessageType type) {
        return logger.entries.stream()
            .filter(entry -> entry.getType() == type)
            .map(entry -> entry.getExpectation().getId())
            .collect(Collectors.toList());
    }

    private static HttpRequest headersOnly(String method, String path) {
        HttpRequest request = request().withMethod(method).withPath(path);
        request.withLogCorrelationId("early-correlation");
        return request;
    }

    private static Expectation expectation(String id, HttpRequest httpRequest, Times times) {
        return new Expectation(httpRequest, times, TimeToLive.unlimited(), 0).withId(id).thenRespond(response().withStatusCode(200));
    }
}

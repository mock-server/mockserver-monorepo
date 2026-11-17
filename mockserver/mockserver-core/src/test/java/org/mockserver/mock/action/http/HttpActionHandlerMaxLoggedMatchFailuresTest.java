package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpForward;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Issue #3020: {@code maxLoggedMatchFailures} caps the did-not-match entries a request writes, including a request
 * served by a forward expectation, whose drift analysis afterwards looks up every expectation matching the request.
 * That lookup is not the serving scan, so it must write no match-result entries of its own. Drives the real
 * {@link HttpActionHandler} against a real {@link HttpState}, with only the upstream call stubbed; the scheduler is
 * synchronous, so the drift analysis has run when {@code processAction} returns.
 */
public class HttpActionHandlerMaxLoggedMatchFailuresTest {

    private static final int OTHER_EXPECTATIONS = 30;

    private static final class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

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

    @Mock
    private HttpForwardActionHandler mockHttpForwardActionHandler;
    @InjectMocks
    private HttpActionHandler actionHandler;

    private Configuration configuration;
    private CapturingLogger logger;
    private Scheduler scheduler;
    private HttpState httpState;

    @Before
    public void setUp() {
        configuration = configuration().logLevel(Level.INFO).maxLoggedMatchFailures(1)
            .driftDetectionEnabled(true).driftSampleRate(1.0d);
        logger = new CapturingLogger(configuration);
        scheduler = new Scheduler(configuration, logger, true);
        httpState = new HttpState(configuration, logger, scheduler);
        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);
        openMocks(this);
    }

    @After
    public void tearDown() {
        httpState.stop();
        scheduler.shutdown();
    }

    @Test
    public void forwardedRequestWritesNoMoreDidNotMatchEntriesThanTheLimit() {
        // given - many expectations on other paths, and a forward expectation in the middle of them
        for (int i = 0; i < OTHER_EXPECTATIONS; i++) {
            if (i == OTHER_EXPECTATIONS / 2) {
                httpState.add(expectation("forward-store", request().withMethod("POST").withPath("/store/.*/find"))
                    .thenForward(forward().withHost("localhost").withPort(1090)));
            }
            httpState.add(expectation("rule-" + i, request().withMethod("GET").withPath("^/service-" + i + "/([^/]+)$"))
                .thenRespond(response().withStatusCode(200)));
        }
        when(mockHttpForwardActionHandler.handle(any(HttpForward.class), any(HttpRequest.class)))
            .thenReturn(completedForwardResult(response("upstream").withStatusCode(200)));
        logger.entries.clear();

        // when
        HttpRequest request = request().withMethod("POST").withPath("/store/abc/find");
        request.withLogCorrelationId("correlation-3020");
        actionHandler.processAction(request, mock(ResponseWriter.class), null, new HashSet<>(), false, true);

        // then - one did-not-match entry (the limit) and the one matched entry, nothing from the drift lookup
        assertThat(count(EXPECTATION_NOT_MATCHED, "correlation-3020"), is(1L));
        assertThat(count(EXPECTATION_MATCHED, "correlation-3020"), is(1L));
    }

    private long count(LogEntry.LogMessageType type, String correlationId) {
        return logger.entries.stream()
            .filter(entry -> entry.getType() == type && correlationId.equals(entry.getCorrelationId()))
            .count();
    }

    private static Expectation expectation(String id, HttpRequest httpRequest) {
        return new Expectation(httpRequest, Times.unlimited(), TimeToLive.unlimited(), 0).withId(id);
    }

    private static HttpForwardActionResult completedForwardResult(HttpResponse upstreamResponse) {
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        future.complete(upstreamResponse);
        return new HttpForwardActionResult(mock(HttpRequest.class), future, null, new InetSocketAddress(1234));
    }
}

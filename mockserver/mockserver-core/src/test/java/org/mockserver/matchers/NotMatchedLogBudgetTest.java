package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.OpenAPIDefinition;
import org.mockserver.model.RequestDefinition;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_MATCHED;
import static org.mockserver.log.model.LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.ConditionalRequestDefinition.requestIf;
import static org.mockserver.model.DnsRequestDefinition.dnsRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A {@link NotMatchedLogBudget} on a {@link MatchDifference} must count exactly the EXPECTATION_NOT_MATCHED entries
 * a matcher writes, and withhold (and count) them once used up, for every data-plane matcher type, while
 * EXPECTATION_MATCHED, the outcome and the recorded differences stay the same. An evaluation that writes no entry
 * (a request of another protocol, a used-up OpenAPI expectation) must not touch the budget.
 */
public class NotMatchedLogBudgetTest {

    private static final String OPEN_API_SPEC = "---" + NEW_LINE +
        "openapi: 3.0.0" + NEW_LINE +
        "paths:" + NEW_LINE +
        "  \"/somePath\":" + NEW_LINE +
        "    get:" + NEW_LINE +
        "      operationId: getOperation" + NEW_LINE +
        "      responses:" + NEW_LINE +
        "        '200':" + NEW_LINE +
        "          description: ok" + NEW_LINE +
        "    put:" + NEW_LINE +
        "      operationId: putOperation" + NEW_LINE +
        "      responses:" + NEW_LINE +
        "        '200':" + NEW_LINE +
        "          description: ok" + NEW_LINE;

    private static final class CapturingLogger extends MockServerLogger {
        final List<LogEntry.LogMessageType> types = new ArrayList<>();

        CapturingLogger(Configuration configuration) {
            super(configuration, LoggerFactory.getLogger(CapturingLogger.class));
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (isEnabledForInstance(logEntry.getLogLevel())) {
                types.add(logEntry.getType());
            }
        }
    }

    /** {@code entries}: how many did-not-match entries the miss writes with no limit. */
    private record Case(String name, Expectation expectation, RequestDefinition matching, RequestDefinition notMatching, int entries) {
    }

    private static List<Case> cases() {
        return List.of(
            new Case("http",
                new Expectation(request().withMethod("GET").withPath("/a").withHeader("X-Tenant", "t1")).thenRespond(response()),
                request().withMethod("GET").withPath("/a").withHeader("X-Tenant", "t1"),
                request().withMethod("GET").withPath("/a").withHeader("X-Tenant", "t2"), 1),
            new Case("openapi",
                new Expectation(new OpenAPIDefinition().withSpecUrlOrPayload(OPEN_API_SPEC)).thenRespond(response()),
                request().withMethod("GET").withPath("/somePath"),
                request().withMethod("POST").withPath("/somePath"), 2),
            new Case("dns",
                new Expectation(dnsRequest("example.com")),
                dnsRequest("example.com"),
                dnsRequest("other.example.com"), 1),
            new Case("binary",
                new Expectation(binaryRequest(new byte[]{1, 2, 3})),
                binaryRequest(new byte[]{1, 2, 3}),
                binaryRequest(new byte[]{9, 9}), 1),
            new Case("conditional",
                new Expectation(requestIf(request().withHeader("X-Cond", "yes"), request().withPath("/then"), request().withPath("/else"))).thenRespond(response()),
                request().withMethod("GET").withPath("/else"),
                request().withMethod("GET").withPath("/neither"), 2)
        );
    }

    private record Outcome(boolean matched, List<LogEntry.LogMessageType> types, int differences, int withheld) {
    }

    private static Outcome evaluate(Configuration configuration, Expectation expectation, RequestDefinition request, NotMatchedLogBudget budget) {
        CapturingLogger logger = new CapturingLogger(configuration);
        HttpRequestMatcher matcher = new MatcherBuilder(configuration, logger).transformsToMatcher(expectation);
        MatchDifference matchDifference = new MatchDifference(true, request).withNotMatchedLogBudget(budget);
        boolean matched = matcher.matches(matchDifference, request);
        return new Outcome(matched, logger.types, matchDifference.getAllDifferences().size(), budget == null ? 0 : budget.getWithheld());
    }

    private static long notMatched(Outcome outcome) {
        return outcome.types().stream().filter(type -> type == EXPECTATION_NOT_MATCHED).count();
    }

    @Test
    public void budgetCountsWrittenEntriesAndWithholdsThePastItForEveryMatcherType() {
        for (boolean failFast : new boolean[]{true, false}) {
            Configuration configuration = configuration().logLevel("INFO").detailedMatchFailures(true).matchersFailFast(failFast);
            for (Case c : cases()) {
                String label = c.name() + " failFast=" + failFast;
                Outcome unlimited = evaluate(configuration, c.expectation(), c.notMatching(), null);
                assertThat(label + " (miss)", unlimited.matched(), is(false));
                assertThat(label + " (entries with no limit)", notMatched(unlimited), is((long) c.entries()));

                for (int limit = 0; limit <= c.entries() + 1; limit++) {
                    Outcome limited = evaluate(configuration, c.expectation(), c.notMatching(), NotMatchedLogBudget.of(limit));
                    long written = Math.min(limit, c.entries());
                    assertThat(label + " limit=" + limit + " (written)", notMatched(limited), is(written));
                    assertThat(label + " limit=" + limit + " (withheld)", limited.withheld(), is((int) (c.entries() - written)));
                    assertThat(label + " limit=" + limit + " (outcome)", limited.matched(), is(false));
                    assertThat(label + " limit=" + limit + " (differences)", limited.differences(), is(unlimited.differences()));
                }

                Outcome hit = evaluate(configuration, c.expectation(), c.matching(), NotMatchedLogBudget.of(0));
                assertThat(label + " (hit)", hit.matched(), is(true));
                assertThat(label + " (matched still logged)", hit.types().contains(EXPECTATION_MATCHED), is(true));
                assertThat(label + " (no not-matched entry on a hit)", notMatched(hit), is(0L));
            }
        }
    }

    @Test
    public void evaluationsThatWriteNoEntryLeaveTheBudgetAlone() {
        Configuration configuration = configuration().logLevel("INFO");
        List<Case> nothingWritten = List.of(
            new Case("http request against a dns expectation", new Expectation(dnsRequest("example.com")), null, request().withPath("/a"), 0),
            new Case("http request against a binary expectation", new Expectation(binaryRequest(new byte[]{1})), null, request().withPath("/a"), 0),
            new Case("dns request against an http expectation", new Expectation(request().withPath("/a")).thenRespond(response()), null, dnsRequest("example.com"), 0)
        );
        for (Case c : nothingWritten) {
            NotMatchedLogBudget budget = NotMatchedLogBudget.of(0);
            Outcome outcome = evaluate(configuration, c.expectation(), c.notMatching(), budget);
            assertThat(c.name(), outcome.matched(), is(false));
            assertThat(c.name(), outcome.types(), is(empty()));
            assertThat(c.name() + " (withheld)", outcome.withheld(), is(0));
        }

        // a used-up OpenAPI expectation returns before its operations are evaluated
        CapturingLogger logger = new CapturingLogger(configuration);
        Expectation usedUp = new Expectation(new OpenAPIDefinition().withSpecUrlOrPayload(OPEN_API_SPEC), Times.once(), TimeToLive.unlimited(), 0).thenRespond(response());
        HttpRequestMatcher matcher = new MatcherBuilder(configuration, logger).transformsToMatcher(usedUp);
        usedUp.consumeMatch(null);
        NotMatchedLogBudget budget = NotMatchedLogBudget.of(0);
        assertThat(matcher.matches(new MatchDifference(true, request().withMethod("POST").withPath("/somePath")).withNotMatchedLogBudget(budget), request().withMethod("POST").withPath("/somePath")), is(false));
        assertThat(logger.types, is(empty()));
        assertThat(budget.getWithheld(), is(0));
    }

    @Test
    public void suppressedLoggingWritesNothingAndWithholdsNothing() {
        Configuration configuration = configuration().logLevel("INFO");
        for (Case c : cases()) {
            CapturingLogger logger = new CapturingLogger(configuration);
            HttpRequestMatcher matcher = new MatcherBuilder(configuration, logger).transformsToMatcher(c.expectation());
            NotMatchedLogBudget budget = NotMatchedLogBudget.of(5);
            assertThat(matcher.matches(new MatchDifference(true, c.notMatching()).suppressMatchResultLogging().withNotMatchedLogBudget(budget), c.notMatching()), is(false));
            assertThat(c.name(), logger.types, is(empty()));
            assertThat(c.name(), budget.getWithheld(), is(0));
        }
    }

    @Test
    public void negativeOrNullLimitMeansNoBudget() {
        assertThat(NotMatchedLogBudget.of(-1), nullValue());
        assertThat(NotMatchedLogBudget.of(-2), nullValue());
        assertThat(NotMatchedLogBudget.of(null), nullValue());
        assertThat(Collections.singletonList(NotMatchedLogBudget.of(0).getWithheld()), contains(0));
    }
}

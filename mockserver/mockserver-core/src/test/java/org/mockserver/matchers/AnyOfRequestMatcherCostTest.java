package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.lessThan;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.AnyOfRequestDefinition.requestAnyOf;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.testing.AllocationMeasurement.allocatedBytes;
import static org.mockserver.testing.AllocationMeasurement.assumeHttpRequestIsNotInstrumented;
import static org.mockserver.testing.AllocationMeasurement.assumeExpectationIsNotInstrumented;

/**
 * An anyOf expectation is evaluated on the serving path for every request it is a candidate for, so composing its
 * branches must allocate nothing beyond what the branches allocate on their own, and must stop at the first branch
 * that matches. Each bound compares an anyOf with the same branches measured standalone, so it holds whatever a
 * branch itself costs.
 */
public class AnyOfRequestMatcherCostTest {

    private static final int OPERATIONS = 200_000;
    // the least of several measurements: background work can only add bytes, never remove the code's own
    private static final int ATTEMPTS = 3;
    private static final long TOLERANCE_BYTES = 8;
    private final Configuration configuration = configuration();
    private final MockServerLogger mockServerLogger = new MockServerLogger(AnyOfRequestMatcherCostTest.class);

    private static final HttpRequest MISS = request().withMethod("GET").withPath("/a").withHeader("X-Branch", "a");
    private static final HttpRequest HIT = request().withMethod("GET").withPath("/b");

    @Test
    public void shouldAllocateNothingBeyondItsBranches() {
        HttpRequest received = request().withMethod("GET").withPath("/b").withHeader("X-Branch", "b");
        MatchDifference context = new MatchDifference(false, received).suppressMatchResultLogging();
        HttpRequestMatcher miss = matcher(MISS);
        HttpRequestMatcher hit = matcher(HIT);
        HttpRequestMatcher anyOf = matcher(requestAnyOf(MISS, HIT));
        for (int warmUp = 0; warmUp < 3; warmUp++) {
            perMatch(miss, context, received, false);
            perMatch(hit, context, received, true);
            perMatch(anyOf, context, received, true);
        }

        long branches = perMatch(miss, context, received, false) + perMatch(hit, context, received, true);
        long composed = perMatch(anyOf, context, received, true);

        assumeHttpRequestIsNotInstrumented();
        assumeExpectationIsNotInstrumented();
        assertThat("bytes per anyOf match (" + composed + ") beyond its branches standalone (" + branches + ")", Math.abs(composed - branches), lessThan(TOLERANCE_BYTES));
    }

    @Test
    public void shouldAllocateNothingBeyondItsBranchesWhenNoBranchMatches() {
        HttpRequest received = request().withMethod("GET").withPath("/c");
        MatchDifference context = new MatchDifference(false, received).suppressMatchResultLogging();
        HttpRequestMatcher miss = matcher(MISS);
        HttpRequestMatcher otherMiss = matcher(HIT);
        HttpRequestMatcher anyOf = matcher(requestAnyOf(MISS, HIT));
        for (int warmUp = 0; warmUp < 3; warmUp++) {
            perMatch(miss, context, received, false);
            perMatch(otherMiss, context, received, false);
            perMatch(anyOf, context, received, false);
        }

        long branches = perMatch(miss, context, received, false) + perMatch(otherMiss, context, received, false);
        long composed = perMatch(anyOf, context, received, false);

        assumeHttpRequestIsNotInstrumented();
        assumeExpectationIsNotInstrumented();
        assertThat("bytes per anyOf miss (" + composed + ") beyond its branches standalone (" + branches + ")", Math.abs(composed - branches), lessThan(TOLERANCE_BYTES));
    }

    @Test
    public void shouldNotEvaluateBranchesAfterTheFirstMatch() {
        HttpRequest received = request().withMethod("GET").withPath("/b").withHeader("X-Branch", "b");
        MatchDifference context = new MatchDifference(false, received).suppressMatchResultLogging();
        HttpRequestMatcher hit = matcher(HIT);
        HttpRequestMatcher anyOf = matcher(requestAnyOf(HIT, MISS, MISS, MISS));
        for (int warmUp = 0; warmUp < 3; warmUp++) {
            perMatch(hit, context, received, true);
            perMatch(anyOf, context, received, true);
        }

        long first = perMatch(hit, context, received, true);
        long composed = perMatch(anyOf, context, received, true);

        assumeHttpRequestIsNotInstrumented();
        assumeExpectationIsNotInstrumented();
        assertThat("bytes per anyOf match (" + composed + ") beyond its first, matching branch (" + first + ")", Math.abs(composed - first), lessThan(TOLERANCE_BYTES));
    }

    @Test
    public void shouldAllocateNothingBeyondItsBranchesInTheServingDefaultDetailedContext() {
        // the serving scan's default: a fresh detailed, unsuppressed context per candidate; WARN keeps the
        // per-miss INFO entries, identical in both arms, out of the measurement
        Configuration warn = configuration().logLevel("WARN");
        MockServerLogger warnLogger = new MockServerLogger(warn, AnyOfRequestMatcherCostTest.class);
        HttpRequest received = request().withMethod("GET").withPath("/b").withHeader("X-Branch", "b");
        HttpRequestMatcher miss = new MatcherBuilder(warn, warnLogger).transformsToMatcher(new Expectation(MISS));
        HttpRequestMatcher hit = new MatcherBuilder(warn, warnLogger).transformsToMatcher(new Expectation(HIT));
        HttpRequestMatcher anyOf = new MatcherBuilder(warn, warnLogger).transformsToMatcher(new Expectation(requestAnyOf(MISS, HIT)));
        for (int warmUp = 0; warmUp < 3; warmUp++) {
            perDetailedMatch(received, context -> !miss.matches(context, received) && hit.matches(context, received));
            perDetailedMatch(received, context -> anyOf.matches(context, received));
        }

        long branches = perDetailedMatch(received, context -> !miss.matches(context, received) && hit.matches(context, received));
        long composed = perDetailedMatch(received, context -> anyOf.matches(context, received));

        assumeHttpRequestIsNotInstrumented();
        assumeExpectationIsNotInstrumented();
        assertThat("bytes per anyOf match in a detailed context (" + composed + ") beyond its branches standalone (" + branches + ")",
            Math.abs(composed - branches), lessThan(TOLERANCE_BYTES));
    }

    private static long perDetailedMatch(HttpRequest request, java.util.function.Predicate<MatchDifference> evaluation) {
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long before = allocatedBytes();
            for (int i = 0; i < OPERATIONS; i++) {
                if (!evaluation.test(new MatchDifference(true, request))) {
                    throw new AssertionError("expected a match");
                }
            }
            least = Math.min(least, (allocatedBytes() - before) / OPERATIONS);
        }
        return least;
    }

    private HttpRequestMatcher matcher(RequestDefinition requestDefinition) {
        return new MatcherBuilder(configuration, mockServerLogger).transformsToMatcher(new Expectation(requestDefinition));
    }

    private static long perMatch(HttpRequestMatcher matcher, MatchDifference context, HttpRequest request, boolean expected) {
        long least = Long.MAX_VALUE;
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            long before = allocatedBytes();
            for (int i = 0; i < OPERATIONS; i++) {
                if (matcher.matches(context, request) != expected) {
                    throw new AssertionError("expected " + (expected ? "a match" : "no match"));
                }
            }
            least = Math.min(least, (allocatedBytes() - before) / OPERATIONS);
        }
        return least;
    }
}

package org.mockserver.matchers;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;

import java.util.List;
import java.util.function.Consumer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * Locks the fixed-arity {@code addDifference} overloads to byte-identical rendered text against the
 * varargs form they replace at the matcher call sites. The rendered difference is user-visible (it
 * appears in mismatch logs and the explainUnmatched response), so any change to argument handling,
 * ordering or formatting is a behaviour regression, not an optimisation. Reverting an overload to
 * build its Object[] differently (wrong order, dropped argument) reddens the equivalence assertions.
 */
public class MatchDifferenceOverloadTest {

    private static final MockServerLogger NO_LOGGER = null;
    private static final HttpRequest REQUEST = HttpRequest.request().withPath("somePath");

    private MatchDifference recording() {
        return new MatchDifference(true, REQUEST).currentField(MatchDifference.Field.HEADERS);
    }

    private List<String> viaFixed(Consumer<MatchDifference> call) {
        MatchDifference context = recording();
        call.accept(context);
        return context.getDifferences(MatchDifference.Field.HEADERS);
    }

    private List<String> viaVarargs(String messageFormat, Object... arguments) {
        MatchDifference context = recording();
        // arguments is Object[], so this binds to the (MockServerLogger, String, Object...) varargs
        // form directly rather than to any fixed-arity overload.
        context.addDifference(NO_LOGGER, messageFormat, arguments);
        return context.getDifferences(MatchDifference.Field.HEADERS);
    }

    @Test
    public void zeroArgumentOverloadMatchesVarargs() {
        List<String> fixed = viaFixed(c -> c.addDifference(NO_LOGGER, "no argument message"));
        assertThat(fixed, is(viaVarargs("no argument message")));
        // formatLogMessage(1, ...) indents each rendered difference by one level (two spaces).
        assertThat(fixed, contains("  no argument message"));
    }

    @Test
    public void twoArgumentOverloadMatchesVarargs() {
        List<String> fixed = viaFixed(c -> c.addDifference(NO_LOGGER, "match failed expected:{}found:{}", "matcherValue", "matchedValue"));
        assertThat(fixed, is(viaVarargs("match failed expected:{}found:{}", "matcherValue", "matchedValue")));
    }

    @Test
    public void threeArgumentOverloadMatchesVarargs() {
        List<String> fixed = viaFixed(c -> c.addDifference(NO_LOGGER, "match failed expected:{}found:{}because:{}", "a", "b", "c"));
        assertThat(fixed, is(viaVarargs("match failed expected:{}found:{}because:{}", "a", "b", "c")));
    }

    @Test
    public void fourArgumentOverloadMatchesVarargs() {
        List<String> fixed = viaFixed(c -> c.addDifference(NO_LOGGER, "similarity:{}threshold:{}expected:{}found:{}", "0.4", "0.8", "a", "b"));
        assertThat(fixed, is(viaVarargs("similarity:{}threshold:{}expected:{}found:{}", "0.4", "0.8", "a", "b")));
    }

    @Test
    public void throwableZeroArgumentOverloadMatchesVarargs() {
        Throwable throwable = new RuntimeException("boom");
        List<String> fixed = viaFixed(c -> c.addDifference(NO_LOGGER, throwable, "some failure message"));
        // The recorded difference text is derived from messageFormat + arguments only; the throwable
        // is used solely for the TRACE log event, which is off here (null logger).
        assertThat(fixed, is(viaVarargs("some failure message")));
    }

    @Test
    public void throwableThreeArgumentOverloadMatchesVarargs() {
        Throwable throwable = new RuntimeException("boom");
        List<String> fixed = viaFixed(c -> c.addDifference(NO_LOGGER, throwable, "match failed expected:{}found:{}because:{}", "a", "b", "boom"));
        assertThat(fixed, is(viaVarargs("match failed expected:{}found:{}because:{}", "a", "b", "boom")));
    }

    @Test
    public void recordsNothingWhenDetailedMatchFailuresOffAndNoTraceLogger() {
        // The overloads must skip building their Object[] and record nothing when neither TRACE
        // logging nor detailedMatchFailures is active - the same output the varargs forms produced.
        MatchDifference context = new MatchDifference(false, REQUEST).currentField(MatchDifference.Field.HEADERS);
        context.addDifference(NO_LOGGER, "message");
        context.addDifference(NO_LOGGER, "expected:{}found:{}", "a", "b");
        context.addDifference(NO_LOGGER, "expected:{}found:{}because:{}", "a", "b", "c");
        context.addDifference(NO_LOGGER, new RuntimeException("x"), "expected:{}found:{}because:{}", "a", "b", "c");
        assertThat(context.getDifferences(MatchDifference.Field.HEADERS), is(nullValue()));
    }
}

package org.mockserver.testing.corpus;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;

/**
 * The in-process corpus with request filters loaded that change nothing: one returns {@code proceed()} and one the
 * copy it was given. Every scenario must match the goldens recorded with no filter, the returned response and the
 * event-log entries alike.
 */
public class InProcessDataPlaneDifferentialCorpusWithIdentityFilterTest extends InProcessDataPlaneDifferentialCorpusTest {

    @BeforeClass
    public static void neverRegenerate() {
        Assume.assumeFalse("the goldens are recorded with no filter loaded", GoldenCorpus.isRegenerating());
    }

    @Override
    protected Configuration withDataPlaneHooks(Configuration configuration) {
        return configuration.extensions(Proceeds.class, ProceedsWithItsCopy.class);
    }

    public static class Proceeds implements RequestFilter {
        @Override
        public String name() {
            return "in-process-corpus-proceeds";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceed();
        }
    }

    public static class ProceedsWithItsCopy implements RequestFilter {
        @Override
        public String name() {
            return "in-process-corpus-proceeds-with-its-copy";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceedWith(request);
        }
    }
}

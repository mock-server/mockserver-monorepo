package org.mockserver.mockservlet;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;
import org.mockserver.testing.corpus.GoldenCorpus;

/**
 * The servlet corpus with request filters loaded that change nothing: one returns {@code proceed()} and one the copy
 * it was given. Every scenario must match the goldens recorded with no filter, bytes and event-log entries alike.
 */
public class MockServerServletDataPlaneDifferentialCorpusWithIdentityFilterIntegrationTest extends MockServerServletDataPlaneDifferentialCorpusIntegrationTest {

    // hides the superclass's, so JUnit starts the servlet once, with the filters
    @BeforeClass
    public static void startServer() throws Exception {
        Assume.assumeFalse("the goldens are recorded with no filter loaded", GoldenCorpus.isRegenerating());
        startServer(Proceeds.class, ProceedsWithItsCopy.class);
    }

    public static class Proceeds implements RequestFilter {
        @Override
        public String name() {
            return "servlet-corpus-proceeds";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceed();
        }
    }

    public static class ProceedsWithItsCopy implements RequestFilter {
        @Override
        public String name() {
            return "servlet-corpus-proceeds-with-its-copy";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceedWith(request);
        }
    }
}

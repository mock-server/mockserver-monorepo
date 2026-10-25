package org.mockserver.netty.integration.corpus;

import org.junit.BeforeClass;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;

/**
 * The HTTP/1.1 and HTTP/2 data-plane corpus with request filters loaded that change nothing: one that returns
 * {@code proceed()} and one that returns the copy it was given, {@code proceedWith(request)}. Every scenario must
 * match the same golden files, bytes and event-log entries alike, as with no filter loaded.
 */
public class NettyDataPlaneDifferentialCorpusWithIdentityFilterIntegrationTest extends NettyDataPlaneDifferentialCorpusIntegrationTest {

    // hides the superclass's, so JUnit starts the servers once, with the filters
    @BeforeClass
    public static void startServers() throws Exception {
        startServers(Proceeds.class, ProceedsWithItsCopy.class);
    }

    public static class Proceeds implements RequestFilter {
        @Override
        public String name() {
            return "corpus-proceeds";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceed();
        }
    }

    public static class ProceedsWithItsCopy implements RequestFilter {
        @Override
        public String name() {
            return "corpus-proceeds-with-its-copy";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceedWith(request);
        }
    }
}

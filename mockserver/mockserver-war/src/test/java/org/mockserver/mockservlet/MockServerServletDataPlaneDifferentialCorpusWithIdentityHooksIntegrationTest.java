package org.mockserver.mockservlet;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.testing.corpus.GoldenCorpus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Every scenario of {@link MockServerServletDataPlaneDifferentialCorpusIntegrationTest}, against the same golden
 * files, with an extension loaded whose response transformer returns what it is given: the servlet writer built for a
 * loaded hook must put the same bytes on the wire and the same entries in the event log as the writer with none.
 * Never regenerates the golden files.
 */
public class MockServerServletDataPlaneDifferentialCorpusWithIdentityHooksIntegrationTest extends MockServerServletDataPlaneDifferentialCorpusIntegrationTest {

    public static class IdentityHooks implements ResponseTransformer {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String name() {
            return "servlet-corpus-identity-hooks";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            CALLS.incrementAndGet();
            return response;
        }
    }

    // hides the superclass's, so JUnit starts the server once, with the hooks
    @BeforeClass
    public static void startServer() throws Exception {
        Assume.assumeFalse("the goldens are recorded with no hooks loaded", GoldenCorpus.isRegenerating());
        startServer(IdentityHooks.class);
    }

    @AfterClass
    public static void shouldHaveRunTheTransformer() {
        if (!GoldenCorpus.isRegenerating()) {
            assertThat("the corpus must pass through the transforming writer", IdentityHooks.CALLS.get(), greaterThan(0));
        }
    }
}

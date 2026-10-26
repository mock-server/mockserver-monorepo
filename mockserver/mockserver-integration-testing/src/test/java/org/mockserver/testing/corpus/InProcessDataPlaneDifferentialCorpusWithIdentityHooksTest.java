package org.mockserver.testing.corpus;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Every scenario of {@link InProcessDataPlaneDifferentialCorpusTest}, against the same golden files, with an extension
 * loaded whose response transformer returns what it is given: the in-process writer built for a loaded hook must
 * return the same response and log the same entries as the writer with none. Never regenerates the golden files.
 */
public class InProcessDataPlaneDifferentialCorpusWithIdentityHooksTest extends InProcessDataPlaneDifferentialCorpusTest {

    public static class IdentityHooks implements ResponseTransformer {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String name() {
            return "in-process-corpus-identity-hooks";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            CALLS.incrementAndGet();
            return response;
        }
    }

    @Before
    public void neverRegenerate() {
        Assume.assumeFalse("the goldens are recorded with no hooks loaded", GoldenCorpus.isRegenerating());
    }

    @Override
    protected Configuration withDataPlaneHooks(Configuration configuration) {
        return configuration.extensions(IdentityHooks.class);
    }

    @AfterClass
    public static void shouldHaveRunTheTransformer() {
        if (!GoldenCorpus.isRegenerating()) {
            assertThat("the corpus must pass through the transforming writer", IdentityHooks.CALLS.get(), greaterThan(0));
        }
    }
}

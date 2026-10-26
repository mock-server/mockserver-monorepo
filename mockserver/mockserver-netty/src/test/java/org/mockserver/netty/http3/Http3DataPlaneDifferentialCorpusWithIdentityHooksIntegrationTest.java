package org.mockserver.netty.http3;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.testing.corpus.GoldenCorpus;

import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Every scenario of {@link Http3DataPlaneDifferentialCorpusIntegrationTest}, against the same golden files, with an
 * extension loaded whose response transformer returns what it is given: the HTTP/3 and gRPC-over-HTTP/3 writers built
 * for a loaded hook must put the same frames on the wire and the same entries in the event log as the writers with
 * none. Never regenerates the golden files.
 */
public class Http3DataPlaneDifferentialCorpusWithIdentityHooksIntegrationTest extends Http3DataPlaneDifferentialCorpusIntegrationTest {

    public static class IdentityHooks implements ResponseTransformer {
        static final AtomicInteger CALLS = new AtomicInteger();

        @Override
        public String name() {
            return "http3-corpus-identity-hooks";
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
        // zero when QUIC is unavailable and every scenario skipped
        boolean quicAvailable;
        try {
            quicAvailable = Http3Server.isQuicAvailable();
        } catch (Throwable notLoaded) {
            quicAvailable = false;
        }
        if (!GoldenCorpus.isRegenerating() && quicAvailable) {
            assertThat("the corpus must pass through the transforming writers", IdentityHooks.CALLS.get(), greaterThan(0));
        }
    }
}

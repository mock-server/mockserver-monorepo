package org.mockserver.netty.integration.corpus;

import org.junit.BeforeClass;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

/**
 * Every scenario of {@link NettyDataPlaneDifferentialCorpusIntegrationTest}, against the same golden files, with an
 * extension loaded whose response transformer returns what it is given: a server with data-plane hooks that change
 * nothing must put the same bytes on the wire and the same entries in the event log as a server with none. Never
 * regenerates the golden files.
 */
public class NettyDataPlaneDifferentialCorpusWithIdentityHooksIntegrationTest extends NettyDataPlaneDifferentialCorpusIntegrationTest {

    /**
     * Returns the copy it is given unchanged.
     */
    public static class IdentityHooks implements ResponseTransformer {
        @Override
        public String name() {
            return "corpus-identity-hooks";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) {
            return response;
        }
    }

    @BeforeClass
    public static void startServers() throws Exception {
        startServers(IdentityHooks.class);
    }
}

package org.mockserver.netty.http3;

import org.junit.Assume;
import org.junit.BeforeClass;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.integration.corpus.NettyDataPlaneDifferentialCorpusWithIdentityFilterIntegrationTest.Proceeds;
import org.mockserver.netty.integration.corpus.NettyDataPlaneDifferentialCorpusWithIdentityFilterIntegrationTest.ProceedsWithItsCopy;
import org.mockserver.testing.corpus.GoldenCorpus;

/**
 * The HTTP/3 corpus, plain and gRPC, with request filters loaded that change nothing: one returns {@code proceed()}
 * and one the copy it was given. Every scenario must match the goldens recorded with no filter, bytes and event-log
 * entries alike.
 */
public class Http3DataPlaneDifferentialCorpusWithIdentityFilterIntegrationTest extends Http3DataPlaneDifferentialCorpusIntegrationTest {

    @BeforeClass
    public static void neverRegenerate() {
        Assume.assumeFalse("the goldens are recorded with no filter loaded", GoldenCorpus.isRegenerating());
    }

    @Override
    protected Configuration withDataPlaneHooks(Configuration configuration) {
        return configuration.extensions(Proceeds.class, ProceedsWithItsCopy.class);
    }
}

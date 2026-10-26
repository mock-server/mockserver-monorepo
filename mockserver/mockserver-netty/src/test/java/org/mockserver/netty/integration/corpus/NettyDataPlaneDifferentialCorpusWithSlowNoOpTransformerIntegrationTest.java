package org.mockserver.netty.integration.corpus;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.mockserver.netty.integration.extension.SlowNoOpResponseTransformer;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;

/**
 * Every scenario of {@link NettyDataPlaneDifferentialCorpusIntegrationTest}, against the same golden files, with a
 * response transformer that waits before returning what it is given: each response then reaches the wire from the
 * extension pool after the code that wrote it has run on (post-processing, after-actions, spans, the streamed
 * response's log entry), and the bytes and event-log entries must still match a server with no transformer.
 */
public class NettyDataPlaneDifferentialCorpusWithSlowNoOpTransformerIntegrationTest extends NettyDataPlaneDifferentialCorpusIntegrationTest {

    private static long transformedBefore;

    // hides the superclass's, so JUnit starts the servers once, with the transformer
    @BeforeClass
    public static void startServers() throws Exception {
        transformedBefore = SlowNoOpResponseTransformer.transformed();
        startServers(SlowNoOpResponseTransformer.class);
    }

    @AfterClass
    public static void transformerRan() {
        assertThat("the slow transformer saw the corpus responses", SlowNoOpResponseTransformer.transformed(), greaterThan(transformedBefore));
    }
}

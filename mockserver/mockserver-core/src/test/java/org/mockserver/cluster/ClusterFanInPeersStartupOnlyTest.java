package org.mockserver.cluster;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.mockserver.verify.Verification;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * The cluster fan-in sends the peer authentication token to every peer, so {@code PUT /mockserver/configuration}
 * must not be able to choose the peers: a caller with control-plane write access could otherwise point them at a
 * host it owns and collect the token.
 */
public class ClusterFanInPeersStartupOnlyTest {

    private static final String PEER_TOKEN = "Bearer startup-peer-token";

    private static class CapturingMockServerLogger extends MockServerLogger {
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, ClusterFanInPeersStartupOnlyTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (logEntry.getLogLevel() == Level.WARN) {
                warnings.add(String.valueOf(logEntry.getMessage()));
            }
            super.logEvent(logEntry);
        }
    }

    private final List<String> peerAuthorizations = new CopyOnWriteArrayList<>();
    private final List<String> attackerAuthorizations = new CopyOnWriteArrayList<>();
    private HttpServer peer;
    private HttpServer attacker;
    private String peerUrl;
    private String attackerUrl;
    private ScheduledExecutorService schedulerExecutor;
    private Configuration configuration;
    private CapturingMockServerLogger logger;
    private HttpState httpState;

    @Before
    public void startServers() throws IOException {
        peer = stub(peerAuthorizations);
        attacker = stub(attackerAuthorizations);
        peerUrl = "http://127.0.0.1:" + peer.getAddress().getPort();
        attackerUrl = "http://127.0.0.1:" + attacker.getAddress().getPort();

        configuration = configuration()
            .logLevel(Level.WARN)
            .clusterVerifyFanIn(true)
            .clusterVerifyFanInPeers(peerUrl)
            .clusterFanInPeerAuthToken(PEER_TOKEN);
        logger = new CapturingMockServerLogger(configuration);
        schedulerExecutor = Executors.newScheduledThreadPool(2);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, logger, scheduler);
    }

    @After
    public void stopServers() {
        httpState.stop();
        schedulerExecutor.shutdownNow();
        peer.stop(0);
        attacker.stop(0);
    }

    @Test
    public void shouldIgnoreAndWarnWhenAPutRepointsThePeers() throws Exception {
        put(new ConfigurationDTO().setClusterVerifyFanInPeers(attackerUrl));

        assertThat(logger.warnings, hasSize(1));
        assertThat(logger.warnings.get(0), containsString("clusterVerifyFanInPeers is fixed at startup"));
        assertThat(logger.warnings.get(0), containsString(attackerUrl));
        assertThat(logger.warnings.get(0), not(containsString(PEER_TOKEN)));
        assertThat(configuration.clusterVerifyFanInPeers(), is(peerUrl));

        verifyFansIn();

        assertThat("the token must never reach a host a PUT supplied", attackerAuthorizations, is(empty()));
        assertThat(peerAuthorizations, contains(PEER_TOKEN));
    }

    @Test
    public void shouldIgnoreAndWarnWithoutShowingTheTokenWhenAPutReplacesIt() throws Exception {
        put(new ConfigurationDTO().setClusterFanInPeerAuthToken("Bearer put-token"));

        assertThat(logger.warnings, hasSize(1));
        assertThat(logger.warnings.get(0), containsString("clusterFanInPeerAuthToken is fixed at startup"));
        assertThat(logger.warnings.get(0), not(containsString("put-token")));
        assertThat(logger.warnings.get(0), not(containsString(PEER_TOKEN)));
        assertThat(configuration.clusterFanInPeerAuthToken(), is(PEER_TOKEN));

        verifyFansIn();

        assertThat(peerAuthorizations, contains(PEER_TOKEN));
    }

    @Test
    public void shouldNotWarnWhenAPutEchoesThePeersInForce() {
        put(new ConfigurationDTO().setClusterVerifyFanInPeers(peerUrl));

        assertThat(logger.warnings, is(empty()));
    }

    @Test
    public void shouldWarnEvenWhenAPutEchoesTheTokenInForceSoAGuessCannotBeConfirmed() {
        put(new ConfigurationDTO().setClusterFanInPeerAuthToken(PEER_TOKEN));

        assertThat(logger.warnings, hasSize(1));
        assertThat(logger.warnings.get(0), containsString("clusterFanInPeerAuthToken is fixed at startup"));
        assertThat(logger.warnings.get(0), not(containsString(PEER_TOKEN)));
    }

    private void put(ConfigurationDTO supplied) {
        logger.warnings.clear();
        supplied.applyTo(configuration);
        httpState.applyConfigurationUpdate(supplied);
    }

    private void verifyFansIn() throws Exception {
        String result = httpState.verify(new Verification().withRequest(request("/api")).withTimes(exactly(0))).get(30, TimeUnit.SECONDS);
        assertThat(result, is(""));
    }

    private static HttpServer stub(List<String> authorizations) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mockserver/retrieve", exchange -> noMatchingTraffic(exchange, authorizations));
        server.start();
        return server;
    }

    private static void noMatchingTraffic(HttpExchange exchange, List<String> authorizations) throws IOException {
        authorizations.add(String.valueOf(exchange.getRequestHeaders().getFirst("authorization")));
        exchange.getRequestBody().readAllBytes();
        byte[] payload = "[]".getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(200, payload.length);
        try (OutputStream body = exchange.getResponseBody()) {
            body.write(payload);
        }
    }
}

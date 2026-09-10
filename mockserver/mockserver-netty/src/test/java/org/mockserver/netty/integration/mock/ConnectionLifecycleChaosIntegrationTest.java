package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.action.http.PreemptionSimulator;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.PortFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * End-to-end coverage for the connection-lifecycle / graceful-shutdown response-path faults driven
 * over raw {@link Socket}s (so the transport-level behaviour — a TCP RST vs a clean close — is
 * observable):
 * <ul>
 *   <li><b>L1 mid-response RST</b> — a host with {@code resetMidResponse} forces a TCP RST after the
 *       response head; the client observes a {@link SocketException} ("Connection reset"), not a clean
 *       EOF.</li>
 *   <li><b>L6 preemption cordon</b> — once {@code PUT /mockserver/preemption} cordons the server, a
 *       new data-plane exchange is turned away with 503 + {@code Connection: close} + {@code
 *       Retry-After}; the control plane stays reachable; {@code GET} reports state; an explicit
 *       {@code DELETE} (and the TTL dead-man's switch) uncordons.</li>
 * </ul>
 */
public class ConnectionLifecycleChaosIntegrationTest {

    private static final int MOCK_SERVER_PORT = PortFactory.findFreePort();
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(MOCK_SERVER_PORT);
        mockServerClient = new MockServerClient("localhost", MOCK_SERVER_PORT);
    }

    @AfterClass
    public static void stopServer() {
        if (mockServerClient != null) {
            mockServerClient.stop();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
    }

    @Before
    public void seedExpectationAndClearChaos() {
        TcpChaosRegistry.getInstance().reset();
        PreemptionSimulator.getInstance().reset();
        mockServerClient.reset();
        mockServerClient
            .when(request().withPath("/ok"))
            .respond(response().withStatusCode(200).withBody("hello-world"));
    }

    @After
    public void clearChaos() {
        TcpChaosRegistry.getInstance().reset();
        PreemptionSimulator.getInstance().reset();
    }

    // ----- L1: mid-response RST -----

    @Test
    public void shouldResetConnectionMidResponseWhenProfileActive() throws IOException {
        // given - a host-scoped mid-response RST profile keyed on the Host header we will send
        TcpChaosRegistry.getInstance().put("localhost",
            org.mockserver.model.TcpChaosProfile.tcpChaosProfile().withResetMidResponse(true));

        // when - a raw HTTP/1.1 request is sent and we drain the socket to EOF / error
        SocketResult result = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        // then - the socket was reset by the peer (RST), surfaced as a SocketException, NOT a clean EOF.
        // Reading a forcibly-reset connection throws "Connection reset"; a clean FIN would instead
        // return -1 without throwing.
        assertThat("expected a TCP RST (SocketException), not a clean close",
            result.threwConnectionReset, is(true));
    }

    @Test
    public void shouldStillAcceptNewConnectionsAfterMidResponseRstOnHttp1() throws IOException {
        // given - a host-scoped mid-response RST profile: an HTTP/1.1 exchange will be reset
        TcpChaosRegistry.getInstance().put("localhost",
            org.mockserver.model.TcpChaosProfile.tcpChaosProfile().withResetMidResponse(true));

        // when - one exchange triggers the forced TCP RST
        SocketResult reset = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat("the chaos RST must reset the client's own connection",
            reset.threwConnectionReset, is(true));

        // then - the SERVER is still accepting new connections. This is the listening-socket guard:
        // on HTTP/1.1 the accepted socket channel's parent() is the server LISTENING socket, so a
        // parent-walk guarded on parent()!=null (rather than on the Http2StreamChannel type) would
        // have set SO_LINGER 0 on and closed the listening socket, shutting the whole server down on
        // the first chaos fault. If that regression were present, this fresh connection would fail to
        // connect or be reset instead of returning 200.
        TcpChaosRegistry.getInstance().reset();
        SocketResult served = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat("server must still accept new connections after a mid-response RST",
            served.threwConnectionReset, is(false));
        assertThat(served.response, containsString("200"));
        assertThat(served.response, containsString("hello-world"));
    }

    @Test
    public void shouldCloseCleanlyWhenNoChaosActive() throws IOException {
        // given - no TCP chaos registered (control: proves the RST is the fault, not the harness)
        SocketResult result = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");

        // then - a normal response with a clean close, no SocketException
        assertThat(result.threwConnectionReset, is(false));
        assertThat(result.response, containsString("200"));
        assertThat(result.response, containsString("hello-world"));
    }

    // ----- L6: preemption cordon -----

    @Test
    public void shouldRejectNewExchangesWith503WhileCordoned() throws Exception {
        // given - cordon the server with a long TTL so it stays cordoned for the test
        String cordonBody = "{\"mode\":\"reject503\",\"drainMillis\":1000,\"ttlMillis\":60000}";
        controlPlanePut("/mockserver/preemption", cordonBody);

        // GET status reflects the cordon
        String status = controlPlaneGet("/mockserver/preemption").response;
        assertThat(status, anyOf(containsString("draining"), containsString("drained")));

        // when - a new data-plane exchange arrives over a fresh socket
        SocketResult result = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: keep-alive\r\n\r\n");

        // then - it is turned away with 503 + Connection: close + Retry-After
        assertThat(result.response, containsString("503"));
        assertThat(result.response.toLowerCase(), containsString("connection: close"));
        assertThat(result.response.toLowerCase(), containsString("retry-after"));

        // and - the control plane stays reachable: an explicit DELETE uncordons
        controlPlaneDelete("/mockserver/preemption");
        String afterDelete = controlPlaneGet("/mockserver/preemption").response;
        assertThat(afterDelete, containsString("inactive"));

        // and - data plane is served again
        SocketResult served = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat(served.response, containsString("200"));
        assertThat(served.response, containsString("hello-world"));
    }

    @Test
    public void shouldAutoUncordonAfterTtlExpires() throws Exception {
        // given - a very short TTL so the dead-man's switch fires within the test
        controlPlanePut("/mockserver/preemption", "{\"mode\":\"reject503\",\"drainMillis\":50,\"ttlMillis\":300}");
        SocketResult cordoned = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat(cordoned.response, containsString("503"));

        // when - the TTL elapses
        Thread.sleep(500);

        // then - the cordon self-heals and the data plane serves again
        SocketResult served = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat("server must auto-uncordon after TTL", served.response, containsString("200"));
    }

    @Test
    public void shouldReportLiveInFlightCountWhileDraining() throws Exception {
        // given - a slow endpoint so a request stays in-flight long enough to observe, and a cordon
        // that does NOT reject this already-started exchange (goaway mode: HTTP/1.1 still served)
        mockServerClient
            .when(request().withPath("/slow"))
            .respond(response().withStatusCode(200).withBody("slow-done")
                .withDelay(org.mockserver.model.Delay.milliseconds(1500)));

        // fire the slow request on a background thread and leave it draining
        Thread slow = new Thread(() -> {
            try {
                rawHttpExchange("GET /slow HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
            } catch (IOException ignore) {
                // the request may be torn down at test teardown; irrelevant to the assertion
            }
        });
        slow.setDaemon(true);
        slow.start();

        // wait until the slow request has actually reached the server (in-flight gauge > 0)
        long deadline = System.currentTimeMillis() + 2_000L;
        boolean sawInFlight = false;
        while (System.currentTimeMillis() < deadline) {
            controlPlanePut("/mockserver/preemption", "{\"mode\":\"goaway\",\"drainMillis\":1000,\"ttlMillis\":60000}");
            String status = controlPlaneGet("/mockserver/preemption").response;
            if (status.matches("(?s).*\"inFlight\"\\s*:\\s*[1-9].*")) {
                sawInFlight = true;
                break;
            }
            Thread.sleep(50);
        }

        // then - GET /preemption reported a non-zero live in-flight count (the wired LifeCycle gauge),
        // proving inFlight is not the hard-coded 0 the unwired supplier returned before
        assertThat("GET /preemption must report the live in-flight count during a drain",
            sawInFlight, is(true));

        controlPlaneDelete("/mockserver/preemption");
        slow.join(3_000L);
    }

    @Test
    public void goAwayOnlyModeShouldNotReject503OnHttp1() throws Exception {
        // given - goaway-only cordon: HTTP/1.1 has no GOAWAY, and reject503 is NOT requested, so an
        // HTTP/1.1 exchange must still be served (not 503'd). This guards the rejectsNewExchanges gate.
        controlPlanePut("/mockserver/preemption", "{\"mode\":\"goaway\",\"drainMillis\":1000,\"ttlMillis\":60000}");

        SocketResult result = rawHttpExchange("GET /ok HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
        assertThat("goaway-only must not 503 an HTTP/1.1 exchange",
            result.response, containsString("200"));

        controlPlaneDelete("/mockserver/preemption");
    }

    // ----- helpers -----

    private static SocketResult controlPlanePut(String path, String body) throws IOException {
        String req = "PUT " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n"
            + "Content-Type: application/json\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
            + "\r\n\r\n" + body;
        return rawHttpExchange(req);
    }

    private static SocketResult controlPlaneGet(String path) throws IOException {
        return rawHttpExchange("GET " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    private static SocketResult controlPlaneDelete(String path) throws IOException {
        return rawHttpExchange("DELETE " + path + " HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n\r\n");
    }

    private static final class SocketResult {
        private final String response;
        private final boolean threwConnectionReset;

        private SocketResult(String response, boolean threwConnectionReset) {
            this.response = response;
            this.threwConnectionReset = threwConnectionReset;
        }
    }

    /**
     * Send a raw HTTP/1.1 request line+headers over a plain socket and drain the response, recording
     * whether reading the connection threw a "Connection reset" {@link SocketException} (the RST
     * signature) as opposed to a clean EOF.
     */
    private static SocketResult rawHttpExchange(String httpRequest) throws IOException {
        try (Socket socket = new Socket()) {
            socket.setSoTimeout(5_000);
            socket.connect(new InetSocketAddress("localhost", MOCK_SERVER_PORT), 5_000);
            OutputStream out = socket.getOutputStream();
            out.write(httpRequest.getBytes(StandardCharsets.UTF_8));
            out.flush();

            InputStream in = socket.getInputStream();
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[4096];
            boolean reset = false;
            try {
                int read;
                while ((read = in.read(chunk)) != -1) {
                    buffer.write(chunk, 0, read);
                }
            } catch (SocketException e) {
                // "Connection reset" is the RST signature; any other SocketException is recorded too
                // since on some platforms a forced RST surfaces as "Connection reset by peer".
                reset = e.getMessage() != null && e.getMessage().toLowerCase().contains("reset");
            }
            return new SocketResult(buffer.toString(StandardCharsets.UTF_8.name()), reset);
        }
    }
}

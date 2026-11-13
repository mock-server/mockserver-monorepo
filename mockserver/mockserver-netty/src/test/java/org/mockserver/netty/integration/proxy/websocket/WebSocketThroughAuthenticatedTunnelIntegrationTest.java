package org.mockserver.netty.integration.proxy.websocket;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.netty.integration.proxy.websocket.RawWebSocket.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * With proxy authentication configured, a WebSocket reached through a CONNECT or SOCKS5 tunnel: the tunnel is refused
 * without valid credentials (nothing reaches the upstream), and with them the upgrade is relayed (or mocked) with the
 * proxy credential neither forwarded upstream nor recorded.
 */
public class WebSocketThroughAuthenticatedTunnelIntegrationTest {

    private final List<Socket> opened = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** A connection closed after the test, so one an assertion leaves open is not leaked. */
    private Socket connected(int port) throws java.io.IOException {
        Socket socket = connect(port);
        opened.add(socket);
        return socket;
    }

    @After
    public void closeSockets() throws java.io.IOException {
        for (Socket socket : opened) {
            socket.close();
        }
    }

    private static final String USERNAME = "ws-proxy-user";
    private static final String PASSWORD = "ws-proxy-password";
    private static final String RIGHT = basic(USERNAME, PASSWORD);
    private static final String WRONG = basic(USERNAME, "not-the-password");

    private static MockServer proxy;
    private static MockServerClient proxyClient;
    private static RawWebSocketUpstream upstream;

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new RawWebSocketUpstream(false);
        proxy = new MockServer(configuration().logLevel("WARN").startupWarmup(false)
            .proxyAuthenticationUsername(USERNAME)
            .proxyAuthenticationPassword(PASSWORD), 0);
        proxyClient = new MockServerClient("127.0.0.1", proxy.getLocalPort());
    }

    @AfterClass
    public static void stopServers() throws Exception {
        stopQuietly(proxyClient);
        stopQuietly(proxy);
        if (upstream != null) {
            upstream.close();
        }
    }

    @Before
    public void reset() {
        upstream.forget();
        proxyClient.reset();
    }

    @Test
    public void shouldRefuseAConnectTunnelWithoutCredentials() throws Exception {
        try (Socket socket = connected(proxy.getLocalPort())) {
            assertThat(connectTunnel(socket, upstream.authority(), null), is(407));
        }
        assertThat(upstream.handshakes.isEmpty(), is(true));
    }

    @Test
    public void shouldRefuseAConnectTunnelWithAWrongCredential() throws Exception {
        try (Socket socket = connected(proxy.getLocalPort())) {
            assertThat(connectTunnel(socket, upstream.authority(), WRONG), is(407));
        }
        assertThat(upstream.handshakes.isEmpty(), is(true));
    }

    @Test
    public void shouldRelayThroughAConnectTunnelWithTheRightCredentialWithoutForwardingOrRecordingIt() throws Exception {
        Socket socket = connected(proxy.getLocalPort());
        assertThat(connectTunnel(socket, upstream.authority(), RIGHT), is(200));
        // a client may send the credential on every request, the tunnelled upgrade included
        Response response = upgrade(socket, upstream.authority(), "/tunnel-auth/connect", "Proxy-Authorization: " + RIGHT);
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            client.sendText("authenticated");
            assertThat(client.read().describe(), is("TEXT fin \"authenticated\""));
            Map<String, List<String>> handshake = upstream.awaitHandshake();
            assertThat(handshake.get("proxy-authorization"), is(nullValue()));
            client.sendClose(1000, "done");
            assertThat(client.read().describe(), is("CLOSE fin 1000 \"done\""));
        }
        assertNoRecordedRequestCarriesTheCredential();
    }

    @Test
    public void shouldRefuseASocks5TunnelWithoutOrWithAWrongCredential() throws Exception {
        try (Socket socket = connected(proxy.getLocalPort())) {
            assertThat(socks5(socket, upstream.port(), null, null), is(false));
        }
        try (Socket socket = connected(proxy.getLocalPort())) {
            assertThat(socks5(socket, upstream.port(), USERNAME, "not-the-password"), is(false));
        }
        assertThat(upstream.handshakes.isEmpty(), is(true));
    }

    @Test
    public void shouldRelayThroughASocks5TunnelWithTheRightCredential() throws Exception {
        Socket socket = connected(proxy.getLocalPort());
        assertThat(socks5(socket, upstream.port(), USERNAME, PASSWORD), is(true));
        Response response = upgrade(socket, upstream.authority(), "/tunnel-auth/socks", "Proxy-Authorization: " + RIGHT);
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            client.sendText("socks authenticated");
            assertThat(client.read().describe(), is("TEXT fin \"socks authenticated\""));
            assertThat(upstream.awaitHandshake().get("proxy-authorization"), is(nullValue()));
            client.sendClose(1000, "done");
            assertThat(client.read().describe(), is("CLOSE fin 1000 \"done\""));
        }
        assertNoRecordedRequestCarriesTheCredential();
    }

    @Test
    public void shouldServeAWebSocketMockThroughAConnectTunnelWithTheRightCredential() throws Exception {
        proxyClient
            .when(request().withPath("/tunnel-auth/mocked"))
            .respondWithWebSocket(webSocketResponse().withMessage(webSocketMessage("from the mock")));
        Socket socket = connected(proxy.getLocalPort());
        assertThat(connectTunnel(socket, "mocked.example:80", RIGHT), is(200));
        Response response = upgrade(socket, "mocked.example", "/tunnel-auth/mocked");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(client.read().describe(), is("TEXT fin \"from the mock\""));
            assertThat(client.read().describe(), is("CLOSE fin 1000 \"Bye\""));
        }
        assertThat(upstream.handshakes.isEmpty(), is(true));
        assertNoRecordedRequestCarriesTheCredential();
    }

    private static void assertNoRecordedRequestCarriesTheCredential() throws InterruptedException {
        // the relay records the upgrade once its connection closes
        HttpRequest[] recorded = new HttpRequest[0];
        for (int i = 0; i < 50 && recorded.length == 0; i++) {
            recorded = proxyClient.retrieveRecordedRequests(request().withPath("/tunnel-auth/.*"));
            if (recorded.length == 0) {
                Thread.sleep(100);
            }
        }
        assertThat(recorded.length, greaterThan(0));
        for (HttpRequest request : recorded) {
            assertThat(request.toString(), request.getFirstHeader("Proxy-Authorization"), is(""));
            assertThat(request.toString(), not(containsString(Base64.getEncoder().encodeToString((USERNAME + ":" + PASSWORD).getBytes(StandardCharsets.UTF_8)))));
        }
    }

    private static String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }
}

package org.mockserver.netty.integration;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Which browser origins may use the control plane: CORS on API responses and preflights, and the
 * Origin check on the dashboard and callback WebSocket upgrades, against a real server over raw
 * sockets so every header is exactly what a browser (or attacker) would send.
 */
public class ControlPlaneOriginIntegrationTest {

    private static final String FOREIGN = "https://attacker.example";
    private static final String ALLOWED = "https://app.example.com";
    private static MockServer mockServer;
    private static MockServer authenticatedMockServer;
    private static MockServer dataPlaneCorsMockServer;
    private static MockServer allowListMockServer;
    private static MockServer catchAllMockServer;
    private static MockServer trustedSuffixMockServer;

    @BeforeClass
    public static void startServers() {
        mockServer = new MockServer(configuration());
        authenticatedMockServer = new MockServer(configuration().controlPlaneJWTAuthenticationRequired(true));
        dataPlaneCorsMockServer = new MockServer(configuration().enableCORSForAllResponses(true));
        allowListMockServer = new MockServer(configuration().controlPlaneCorsAllowOrigin(ALLOWED).corsAllowOrigin("*"));
        catchAllMockServer = new MockServer(configuration().enableCORSForAllResponses(true));
        trustedSuffixMockServer = new MockServer(configuration().controlPlaneTrustedHostSuffixes("k8s.example"));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServer);
        stopQuietly(authenticatedMockServer);
        stopQuietly(dataPlaneCorsMockServer);
        stopQuietly(allowListMockServer);
        stopQuietly(catchAllMockServer);
        stopQuietly(trustedSuffixMockServer);
    }

    // ---- API responses and preflights ----

    @Test
    public void shouldReflectLoopbackOriginsOnAPreflight() throws IOException {
        for (String origin : new String[]{"http://localhost:3000", "https://localhost:8443", "http://127.0.0.1:5173", "http://[::1]:8080"}) {
            Response response = preflight(mockServer.getLocalPort(), "localhost:" + mockServer.getLocalPort(), origin);

            assertThat(origin, response.status, startsWith("HTTP/1.1 200"));
            assertThat(origin, response.header("access-control-allow-origin"), is(origin));
            assertThat(origin, response.header("access-control-allow-methods") != null, is(true));
        }
    }

    @Test
    public void shouldReflectTheSameHostOriginOnAPreflight() throws IOException {
        for (String name : new String[]{"mockserver", "mockserver.default.svc.cluster.local", "10.0.0.5"}) {
            String host = name + ":" + mockServer.getLocalPort();
            Response response = preflight(mockServer.getLocalPort(), host, "http://" + host);

            assertThat(host, response.header("access-control-allow-origin"), is("http://" + host));
        }
    }

    // ---- DNS rebinding: a same-host page on a public name ----

    @Test
    public void shouldRefuseARebindingPageOnAPublicName() throws IOException {
        for (String name : new String[]{"evil.example.com", "internal.evil.com"}) {
            assertRebindingPageRefused(mockServer.getLocalPort(), name + ":" + mockServer.getLocalPort());
        }
        assertRebindingPageRefused(allowListMockServer.getLocalPort(), "evil.example.com:" + allowListMockServer.getLocalPort());
    }

    private static void assertRebindingPageRefused(int port, String host) throws IOException {
        Response api = send(port,
            "PUT /mockserver/retrieve?type=REQUESTS HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "Origin: http://" + host + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n");

        assertThat(api.status, startsWith("HTTP/1.1 403"));
        assertNoCorsHeaders(api);
        assertNoCorsHeaders(preflight(port, host, "http://" + host));
        for (String path : new String[]{"/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
            assertThat(path, upgradeStatus(port, path, host, "http://" + host, null), startsWith("HTTP/1.1 403"));
        }
    }

    @Test
    public void shouldAllowASameHostPageOnADockerOrKubernetesName() throws IOException {
        int port = mockServer.getLocalPort();
        for (String name : new String[]{"mockserver", "mockserver.default.svc", "mockserver.default.svc.cluster.local", "host.docker.internal", "ip-10-0-0-1.ec2.internal"}) {
            String host = name + ":" + port;
            Response api = send(port,
                "PUT /mockserver/retrieve?type=REQUESTS HTTP/1.1\r\n" +
                    "Host: " + host + "\r\n" +
                    "Origin: http://" + host + "\r\n" +
                    "Content-Length: 0\r\n" +
                    "Connection: close\r\n\r\n");

            assertThat(host, api.status, startsWith("HTTP/1.1 200"));
            assertThat(host, api.header("access-control-allow-origin"), is("http://" + host));
            for (String path : new String[]{"/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
                assertThat(host + path, upgradeStatus(port, path, host, "http://" + host, null), startsWith("HTTP/1.1 101"));
            }
        }
    }

    @Test
    public void shouldAllowASameHostPageOnAConfiguredTrustedSuffix() throws IOException {
        int port = trustedSuffixMockServer.getLocalPort();
        String trusted = "mockserver.ns.svc.k8s.example:" + port;
        String untrusted = "mockserver.k8s.example.evil.com:" + port;

        assertThat(preflight(port, trusted, "http://" + trusted).header("access-control-allow-origin"), is("http://" + trusted));
        assertNoCorsHeaders(preflight(port, untrusted, "http://" + untrusted));
        for (String path : new String[]{"/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
            assertThat(path, upgradeStatus(port, path, trusted, "http://" + trusted, null), startsWith("HTTP/1.1 101"));
            assertThat(path, upgradeStatus(port, path, untrusted, "http://" + untrusted, null), startsWith("HTTP/1.1 403"));
        }
    }

    @Test
    public void shouldAnswerAForeignOriginPreflightWithoutAllowHeaders() throws IOException {
        Response response = preflight(mockServer.getLocalPort(), "localhost:" + mockServer.getLocalPort(), FOREIGN);

        assertThat(response.status, startsWith("HTTP/1.1 200"));
        assertNoCorsHeaders(response);
    }

    @Test
    public void shouldAnswerANullOriginPreflightWithoutAllowHeaders() throws IOException {
        Response response = preflight(mockServer.getLocalPort(), "localhost:" + mockServer.getLocalPort(), "null");

        assertNoCorsHeaders(response);
    }

    @Test
    public void shouldRefuseAnApiRequestFromAForeignOrigin() throws IOException {
        Response response = send(mockServer.getLocalPort(),
            "PUT /mockserver/retrieve?type=REQUESTS HTTP/1.1\r\n" +
                "Host: localhost:" + mockServer.getLocalPort() + "\r\n" +
                "Origin: " + FOREIGN + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n");

        assertThat(response.status, startsWith("HTTP/1.1 403"));
        assertNoCorsHeaders(response);
    }

    @Test
    public void shouldMakeAnApiResponseReadableToALoopbackOrigin() throws IOException {
        Response response = send(mockServer.getLocalPort(),
            "PUT /mockserver/retrieve?type=REQUESTS HTTP/1.1\r\n" +
                "Host: localhost:" + mockServer.getLocalPort() + "\r\n" +
                "Origin: http://localhost:3000\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n");

        assertThat(response.header("access-control-allow-origin"), is("http://localhost:3000"));
    }

    @Test
    public void shouldKeepAllowingANonBrowserClientWithNoOrigin() throws IOException {
        Response response = send(mockServer.getLocalPort(),
            "PUT /mockserver/retrieve?type=REQUESTS HTTP/1.1\r\n" +
                "Host: localhost:" + mockServer.getLocalPort() + "\r\n" +
                "Content-Length: 0\r\n" +
                "Connection: close\r\n\r\n");

        assertThat(response.status, startsWith("HTTP/1.1 200"));
    }

    // ---- WebSocket upgrades ----

    @Test
    public void shouldUpgradeBothWebSocketsForLoopbackSameHostAndMissingOrigins() throws IOException {
        int port = mockServer.getLocalPort();
        for (String path : new String[]{"/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
            assertThat(path, upgradeStatus(port, path, "localhost:" + port, "http://localhost:3000", null), startsWith("HTTP/1.1 101"));
            assertThat(path, upgradeStatus(port, path, "127.0.0.1:" + port, "http://127.0.0.1:" + port, null), startsWith("HTTP/1.1 101"));
            assertThat(path, upgradeStatus(port, path, "mockserver:" + port, "http://mockserver:" + port, null), startsWith("HTTP/1.1 101"));
            assertThat(path, upgradeStatus(port, path, "localhost:" + port, null, null), startsWith("HTTP/1.1 101"));
        }
    }

    @Test
    public void shouldRefuseBothWebSocketsForForeignAndNullOrigins() throws IOException {
        int port = mockServer.getLocalPort();
        for (String path : new String[]{"/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
            assertThat(path, upgradeStatus(port, path, "localhost:" + port, FOREIGN, null), startsWith("HTTP/1.1 403"));
            assertThat(path, upgradeStatus(port, path, "localhost:" + port, "null", null), startsWith("HTTP/1.1 403"));
            assertThat(path, upgradeStatus(port, path, "mockserver:" + port, "http://mockserver:" + (port + 1), null), startsWith("HTTP/1.1 403"));
        }
    }

    @Test
    public void shouldRequireControlPlaneAuthenticationForTheCallbackWebSocket() throws IOException {
        int port = authenticatedMockServer.getLocalPort();

        assertThat(upgradeStatus(port, "/_mockserver_callback_websocket", "localhost:" + port, null, null), startsWith("HTTP/1.1 401"));
        assertThat(upgradeStatus(port, "/_mockserver_ui_websocket", "localhost:" + port, null, null), startsWith("HTTP/1.1 401"));
    }

    @Test
    public void shouldLetACallbackClientReconnectWithItsIdWhileTheOldConnectionLooksOpen() throws Exception {
        int port = mockServer.getLocalPort();
        String clientId = UUID.randomUUID().toString();
        try (Socket stale = connect(port); Socket reconnect = connect(port)) {
            assertThat(upgradeStatus(stale, "/_mockserver_callback_websocket", "localhost:" + port, null, clientId), startsWith("HTTP/1.1 101"));

            assertThat(upgradeStatus(reconnect, "/_mockserver_callback_websocket", "localhost:" + port, null, clientId), startsWith("HTTP/1.1 101"));
        }
    }

    // ---- data plane: mocked responses keep the corsAllowOrigin policy ----

    @Test
    public void shouldKeepReflectingAnyOriginOnMockResponsesWithCorsForAllResponses() throws IOException {
        int port = dataPlaneCorsMockServer.getLocalPort();
        String expectation = "{\"httpRequest\":{\"path\":\"/cors-mock\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"mocked\"}}";
        Response created = send(port,
            "PUT /mockserver/expectation HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + expectation.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n\r\n" + expectation);
        assertThat(created.status, startsWith("HTTP/1.1 201"));

        Response mocked = send(port,
            "GET /cors-mock HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Origin: http://frontend:3000\r\n" +
                "Connection: close\r\n\r\n");
        Response mockPreflight = send(port,
            "OPTIONS /cors-mock-unmatched HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Origin: http://frontend:3000\r\n" +
                "Access-Control-Request-Method: PUT\r\n" +
                "Connection: close\r\n\r\n");

        assertThat(mocked.status, startsWith("HTTP/1.1 200"));
        assertThat(mocked.header("access-control-allow-origin"), is("http://frontend:3000"));
        assertThat(mockPreflight.status, startsWith("HTTP/1.1 200"));
        assertThat(mockPreflight.header("access-control-allow-origin"), is("http://frontend:3000"));
        assertNoCorsHeaders(preflight(port, "localhost:" + port, "http://frontend:3000"));
    }

    @Test
    public void shouldNotLetTheMockCorsPolicyApproveAPreflightForALegacyControlPlaneAlias() throws IOException {
        int port = dataPlaneCorsMockServer.getLocalPort();

        Response response = send(port,
            "OPTIONS /reset HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Origin: " + FOREIGN + "\r\n" +
                "Access-Control-Request-Method: PUT\r\n" +
                "Connection: close\r\n\r\n");

        assertThat(response.status, startsWith("HTTP/1.1 200"));
        assertNoCorsHeaders(response);
    }

    @Test
    public void shouldNotLetACatchAllExpectationApproveAControlPlanePreflightOrReopenTheControlPlane() throws IOException {
        int port = catchAllMockServer.getLocalPort();
        assertThat(putExpectation(port, "{\"httpRequest\":{},\"httpResponse\":{\"statusCode\":200}}").status, startsWith("HTTP/1.1 201"));

        Response preflight = send(port,
            "OPTIONS /mockserver/configuration HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Origin: " + FOREIGN + "\r\n" +
                "Access-Control-Request-Method: PUT\r\n" +
                "Access-Control-Request-Headers: content-type\r\n" +
                "Connection: close\r\n\r\n");
        Response aliasPreflight = send(port,
            "OPTIONS /reset HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Origin: " + FOREIGN + "\r\n" +
                "Access-Control-Request-Method: PUT\r\n" +
                "Connection: close\r\n\r\n");
        String widen = "{\"controlPlaneCorsAllowOrigin\":\"*\"}";
        Response blindWrite = send(port,
            "PUT /mockserver/configuration HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Origin: " + FOREIGN + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + widen.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n\r\n" + widen);
        Response readAfter = send(port,
            "GET /mockserver/configuration HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Origin: " + FOREIGN + "\r\n" +
                "Connection: close\r\n\r\n");

        assertThat(preflight.status, startsWith("HTTP/1.1 200"));
        assertNoCorsHeaders(preflight);
        assertNoCorsHeaders(aliasPreflight);
        assertThat(blindWrite.status, startsWith("HTTP/1.1 403"));
        assertNoCorsHeaders(blindWrite);
        assertThat("a request to the control plane cannot widen controlPlaneCorsAllowOrigin",
            catchAllMockServer.getConfiguration().controlPlaneCorsAllowOrigin(), is(""));
        assertNoCorsHeaders(readAfter);
    }

    @Test
    public void shouldDecideABareAliasPreflightByTheMethodTheControlPlaneServesThere() throws IOException {
        int port = dataPlaneCorsMockServer.getLocalPort();

        Response mockedGraphql = preflight(port, "localhost:" + port, "http://frontend:3000", "/graphql", "POST");
        Response controlPlaneGraphql = preflight(port, "localhost:" + port, "http://frontend:3000", "/graphql", "PUT");
        Response mockedStatus = preflight(port, "localhost:" + port, "http://frontend:3000", "/status", "GET");

        assertThat(mockedGraphql.header("access-control-allow-origin"), is("http://frontend:3000"));
        assertThat("only the requested method, so a cached preflight cannot cover PUT /graphql",
            mockedGraphql.header("access-control-allow-methods"), is("POST"));
        assertNoCorsHeaders(controlPlaneGraphql);
        assertThat(mockedStatus.header("access-control-allow-origin"), is("http://frontend:3000"));
    }

    // ---- preflights are not recorded traffic ----

    @Test
    public void shouldNotRecordAControlPlanePreflightAsAReceivedRequest() throws IOException {
        MockServer server = new MockServer(configuration());
        try {
            int port = server.getLocalPort();
            assertThat(preflight(port, "localhost:" + port, "http://localhost:3000", "/mockserver/status", "PUT").status, startsWith("HTTP/1.1 200"));
            assertThat(preflight(port, "localhost:" + port, "http://localhost:3000", "/mockserver/expectation", "PUT").status, startsWith("HTTP/1.1 200"));

            MockServerClient client = new MockServerClient("localhost", port);
            client.verifyZeroInteractions();
            assertThat(client.retrieveRecordedRequests(null).length, is(0));

            // a data-plane OPTIONS is still traffic
            preflight(port, "localhost:" + port, "http://localhost:3000", "/api/orders", "PUT");
            assertThat(client.retrieveRecordedRequests(null).length, is(1));
        } finally {
            stopQuietly(server);
        }
    }

    // ---- controlPlaneCorsAllowOrigin allow-list, independent of corsAllowOrigin ----

    @Test
    public void shouldAllowOnlyTheConfiguredControlPlaneOrigins() throws IOException {
        int port = allowListMockServer.getLocalPort();

        assertThat(preflight(port, "localhost:" + port, ALLOWED).header("access-control-allow-origin"), is(ALLOWED));
        assertNoCorsHeaders(preflight(port, "localhost:" + port, FOREIGN));
        assertNoCorsHeaders(preflight(port, "localhost:" + port, "http://localhost:3000"));
        for (String path : new String[]{"/_mockserver_ui_websocket", "/_mockserver_callback_websocket"}) {
            assertThat(path, upgradeStatus(port, path, "localhost:" + port, ALLOWED, null), startsWith("HTTP/1.1 101"));
            assertThat(path, upgradeStatus(port, path, "localhost:" + port, FOREIGN, null), startsWith("HTTP/1.1 403"));
            assertThat(path, upgradeStatus(port, path, "localhost:" + port, "http://localhost:" + port, null), startsWith("HTTP/1.1 101"));
        }
    }

    // ---- helpers ----

    private static void assertNoCorsHeaders(Response response) {
        for (String name : response.headers.keySet()) {
            assertThat(name, name.startsWith("access-control-"), is(false));
        }
        assertThat(response.header("access-control-allow-origin"), nullValue());
    }

    private static Response preflight(int port, String host, String origin) throws IOException {
        return preflight(port, host, origin, "/mockserver/expectation", "PUT");
    }

    private static Response putExpectation(int port, String expectation) throws IOException {
        return send(port,
            "PUT /mockserver/expectation HTTP/1.1\r\n" +
                "Host: localhost:" + port + "\r\n" +
                "Content-Type: application/json\r\n" +
                "Content-Length: " + expectation.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
                "Connection: close\r\n\r\n" + expectation);
    }

    private static Response preflight(int port, String host, String origin, String path, String method) throws IOException {
        return send(port,
            "OPTIONS " + path + " HTTP/1.1\r\n" +
                "Host: " + host + "\r\n" +
                "Origin: " + origin + "\r\n" +
                "Access-Control-Request-Method: " + method + "\r\n" +
                "Access-Control-Request-Headers: content-type\r\n" +
                "Connection: close\r\n\r\n");
    }

    private static String upgradeStatus(int port, String path, String host, String origin, String clientId) throws IOException {
        try (Socket socket = connect(port)) {
            return upgradeStatus(socket, path, host, origin, clientId);
        }
    }

    private static String upgradeStatus(Socket socket, String path, String host, String origin, String clientId) throws IOException {
        StringBuilder request = new StringBuilder()
            .append("GET ").append(path).append(" HTTP/1.1\r\n")
            .append("Host: ").append(host).append("\r\n")
            .append("Upgrade: websocket\r\n")
            .append("Connection: Upgrade\r\n")
            .append("Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n")
            .append("Sec-WebSocket-Version: 13\r\n");
        if (origin != null) {
            request.append("Origin: ").append(origin).append("\r\n");
        }
        if (clientId != null) {
            request.append("X-CLIENT-REGISTRATION-ID: ").append(clientId).append("\r\n");
        }
        request.append("\r\n");
        socket.getOutputStream().write(request.toString().getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return readHead(socket.getInputStream()).status;
    }

    private static Socket connect(int port) throws IOException {
        Socket socket = new Socket();
        socket.connect(new InetSocketAddress("localhost", port), 5_000);
        socket.setSoTimeout(30_000);
        return socket;
    }

    private static Response send(int port, String request) throws IOException {
        try (Socket socket = connect(port)) {
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readHead(socket.getInputStream());
        }
    }

    private static Response readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int b;
        while (!head.toString().endsWith("\r\n\r\n") && (b = in.read()) != -1) {
            head.append((char) b);
        }
        String[] lines = head.toString().split("\r\n");
        Map<String, String> headers = new LinkedHashMap<>();
        for (int i = 1; i < lines.length; i++) {
            int colon = lines[i].indexOf(':');
            if (colon > 0) {
                headers.putIfAbsent(lines[i].substring(0, colon).trim().toLowerCase(Locale.ROOT), lines[i].substring(colon + 1).trim());
            }
        }
        return new Response(lines.length > 0 ? lines[0] : "", headers);
    }

    private static final class Response {
        private final String status;
        private final Map<String, String> headers;

        private Response(String status, Map<String, String> headers) {
            this.status = status;
            this.headers = headers;
        }

        private String header(String name) {
            return headers.get(name);
        }
    }
}

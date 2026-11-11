package org.mockserver.netty.proxy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assume.assumeTrue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * A request a proxy client sends through MockServer to another host is forwarded, however much its path reads like
 * one of MockServer's own routes, while a request addressed to MockServer itself, directly or through its own proxy, is
 * still served by its control plane. The other host is reached at a non-loopback address of this machine, because a
 * loopback destination at a port MockServer does not listen on may be relayed to it, so is taken to be MockServer. When
 * the other host is itself a MockServer, it refuses a forwarded control-plane call ({@code 404}) rather than serve it
 * with the forwarding MockServer's identity, but records it like any other request.
 */
public class ProxiedControlPlaneLookalikeIntegrationTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private MockServer proxy;
    private MockServer upstream;
    private MockServerClient proxyClient;
    private MockServerClient upstreamClient;
    private EventLoopGroup eventLoopGroup;
    private String upstreamHost;

    @Before
    public void startServers() {
        upstreamHost = nonLoopbackAddressOfThisHost();
        proxy = new MockServer(configuration().livenessHttpGetPath("/health"));
        upstream = new MockServer();
        proxyClient = new MockServerClient("localhost", proxy.getLocalPort());
        upstreamClient = new MockServerClient("localhost", upstream.getLocalPort());
        proxyClient.when(request().withPath("/proxy")).respond(response().withBody("proxy"));
        upstreamClient.when(request().withPath("/upstream")).respond(response().withBody("upstream"));
    }

    @After
    public void stopServers() {
        if (eventLoopGroup != null) {
            eventLoopGroup.shutdownGracefully();
        }
        proxyClient.close();
        upstreamClient.close();
        proxy.stop();
        upstream.stop();
    }

    @Test
    public void absoluteFormStatusToAnotherHostIsForwarded() throws Exception {
        assumeSiteLocalAddress();
        String upstreamAuthority = upstreamHost + ":" + upstream.getLocalPort();

        String response = exchange(proxy.getLocalPort(), "PUT http://" + upstreamAuthority + "/mockserver/status HTTP/1.1\r\n"
            + "Host: " + upstreamAuthority + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 404"));
        assertThat(upstreamClient.retrieveRecordedRequests(request().withMethod("PUT").withPath("/mockserver/status")).length, is(1));
    }

    @Test
    public void absoluteFormClearToAnotherHostThatIsNotMockServerIsForwarded() throws Exception {
        assumeSiteLocalAddress();
        try (RecordingUpstream recordingUpstream = new RecordingUpstream()) {
            String upstreamAuthority = upstreamHost + ":" + recordingUpstream.port();
            String clear = "PUT http://" + upstreamAuthority + "/mockserver/clear HTTP/1.1";

            String response = exchange(proxy.getLocalPort(), clear + "\r\nHost: " + upstreamAuthority + "\r\nContent-Length: 0\r\n\r\n");

            assertThat(response, startsWith("HTTP/1.1 200"));
            assertThat(recordingUpstream.awaitRequestLine("PUT /mockserver/clear HTTP/1.1"), hasItem("PUT /mockserver/clear HTTP/1.1"));
            assertThat(proxyClient.retrieveActiveExpectations(null).length, is(1));
        }
    }

    @Test
    public void absoluteFormLivenessProbeToAnotherHostIsForwarded() throws Exception {
        assumeSiteLocalAddress();
        String upstreamAuthority = upstreamHost + ":" + upstream.getLocalPort();

        exchange(proxy.getLocalPort(), "GET http://" + upstreamAuthority + "/health HTTP/1.1\r\n"
            + "Host: " + upstreamAuthority + "\r\n\r\n");

        assertThat(upstreamClient.retrieveRecordedRequests(request().withMethod("GET").withPath("/health")).length, is(1));
    }

    @Test
    public void absoluteFormClearToAnotherMockServerIsForwardedButNotServedThere() throws Exception {
        assumeSiteLocalAddress();
        String upstreamAuthority = upstreamHost + ":" + upstream.getLocalPort();

        String response = exchange(proxy.getLocalPort(), "PUT http://" + upstreamAuthority + "/mockserver/clear HTTP/1.1\r\n"
            + "Host: " + upstreamAuthority + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 404"));
        assertThat(upstreamClient.retrieveRecordedRequests(request().withMethod("PUT").withPath("/mockserver/clear")).length, is(1));
        assertThat(upstreamClient.retrieveActiveExpectations(null).length, is(1));
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(1));
    }

    @Test
    public void bareClearInConnectTunnelToAnotherHostIsForwarded() throws Exception {
        assumeSiteLocalAddress();
        String upstreamAuthority = upstreamHost + ":" + upstream.getLocalPort();

        String response = exchangeThroughConnect(proxy.getLocalPort(), upstreamAuthority, "PUT /clear HTTP/1.1\r\n"
            + "Host: " + upstreamAuthority + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 404"));
        assertThat(upstreamClient.retrieveRecordedRequests(request().withMethod("PUT").withPath("/clear")).length, is(1));
        assertThat(upstreamClient.retrieveActiveExpectations(null).length, is(1));
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(1));
    }

    @Test
    public void clearInSocks5TunnelToAnotherHostIsForwarded() throws Exception {
        assumeSiteLocalAddress();
        String upstreamAuthority = upstreamHost + ":" + upstream.getLocalPort();

        try (Socket socket = new Socket("127.0.0.1", proxy.getLocalPort())) {
            socket.setSoTimeout(15_000);
            openSocks5Tunnel(socket, upstreamHost, upstream.getLocalPort());
            socket.getOutputStream().write(("PUT /mockserver/clear HTTP/1.1\r\nHost: " + upstreamAuthority + "\r\nContent-Length: 0\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            assertThat(readResponse(socket.getInputStream()), startsWith("HTTP/1.1 404"));
        }

        assertThat(upstreamClient.retrieveRecordedRequests(request().withMethod("PUT").withPath("/mockserver/clear")).length, is(1));
        assertThat(upstreamClient.retrieveActiveExpectations(null).length, is(1));
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(1));
    }

    @Test
    public void statusInHttp2ConnectTunnelToAnotherHostIsForwarded() throws Exception {
        assumeSiteLocalAddress();
        eventLoopGroup = new NioEventLoopGroup(1);
        int upstreamPort = upstream.getLocalPort();
        try (Http2TestClient client = Http2TestClient.throughConnect(eventLoopGroup, proxy.getLocalPort(), upstreamHost, upstreamPort, false)) {
            Http2TestClient.Exchange exchange = client.send(putStatus(upstreamHost + ":" + upstreamPort), true);

            assertThat(exchange.status(), is(404));
            assertThat(upstreamClient.retrieveRecordedRequests(request().withMethod("PUT").withPath("/mockserver/status")).length, is(1));
        }
    }

    @Test
    public void webSocketAndMcpRoutesInConnectTunnelToAnotherHostAreForwarded() throws Exception {
        assumeSiteLocalAddress();
        try (RecordingUpstream recordingUpstream = new RecordingUpstream()) {
            String upstreamAuthority = upstreamHost + ":" + recordingUpstream.port();
            String upgrade = " HTTP/1.1\r\nHost: " + upstreamAuthority + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";
            String mcpBody = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

            String dashboard = "GET /_mockserver_ui_websocket HTTP/1.1";
            String callback = "GET /_mockserver_callback_websocket HTTP/1.1";
            String mcp = "POST /mockserver/mcp HTTP/1.1";

            assertThat(sendThroughConnect(recordingUpstream, upstreamAuthority, "GET /_mockserver_ui_websocket" + upgrade, dashboard), hasItem(dashboard));
            assertThat(sendThroughConnect(recordingUpstream, upstreamAuthority, "GET /_mockserver_callback_websocket" + upgrade, callback), hasItem(callback));
            assertThat(recordingUpstream.requestHead(dashboard).toLowerCase(Locale.ROOT), containsString("x-forwarded-by: mockserver_proxied"));
            assertThat(sendThroughConnect(recordingUpstream, upstreamAuthority, mcp + "\r\nHost: " + upstreamAuthority
                + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\nContent-Length: " + mcpBody.length() + "\r\n\r\n" + mcpBody, mcp), hasItem(mcp));
        }
    }

    @Test
    public void webSocketControlPlaneUpgradesInConnectTunnelToAnotherMockServerAreNotServedThere() throws Exception {
        assumeSiteLocalAddress();
        String upstreamAuthority = upstreamHost + ":" + upstream.getLocalPort();
        String upgrade = " HTTP/1.1\r\nHost: " + upstreamAuthority + "\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\nSec-WebSocket-Version: 13\r\n\r\n";

        for (String path : List.of("/_mockserver_ui_websocket", "/_mockserver_callback_websocket")) {
            try (Socket socket = new Socket("127.0.0.1", proxy.getLocalPort())) {
                socket.setSoTimeout(15_000);
                openConnectTunnel(socket, upstreamAuthority);
                socket.getOutputStream().write(("GET " + path + upgrade).getBytes(StandardCharsets.US_ASCII));

                // the upstream MockServer refuses the relayed handshake, so the passthrough cannot open
                assertThat(path, readResponse(socket.getInputStream()), startsWith("HTTP/1.1 502"));
            }
            assertThat(path, upstreamClient.retrieveRecordedRequests(request().withMethod("GET").withPath(path)).length, is(1));
        }
    }

    @Test
    public void proxiedRequestsAreRecordedWithoutTheForwardMarker() throws Exception {
        assumeSiteLocalAddress();
        try (RecordingUpstream recordingUpstream = new RecordingUpstream()) {
            String upstreamAuthority = upstreamHost + ":" + recordingUpstream.port();
            String graphql = "POST /graphql HTTP/1.1";
            String clear = "PUT /mockserver/clear HTTP/1.1";

            sendThroughConnect(recordingUpstream, upstreamAuthority, graphql + "\r\nHost: " + upstreamAuthority + "\r\nContent-Length: 2\r\n\r\n{}", graphql);
            sendThroughConnect(recordingUpstream, upstreamAuthority, clear + "\r\nHost: " + upstreamAuthority + "\r\nContent-Length: 0\r\n\r\n", clear);

            // an ordinary request on a bare alias's path is not a control-plane call, so is forwarded as it was sent
            assertThat(recordingUpstream.requestHead(graphql).toLowerCase(Locale.ROOT), not(containsString("mockserver_proxied")));
            assertThat(recordingUpstream.requestHead(clear).toLowerCase(Locale.ROOT), containsString("x-forwarded-by: mockserver_proxied"));
            for (org.mockserver.mock.Expectation recorded : proxyClient.retrieveRecordedExpectations(null)) {
                assertThat(((org.mockserver.model.HttpRequest) recorded.getHttpRequest()).getHeader("x-forwarded-by"), is(empty()));
            }
            assertThat(proxyClient.retrieveRecordedExpectations(null).length, is(2));
        }
    }

    @Test
    public void absoluteFormStatusToMockServerItselfIsServedByItsControlPlane() throws Exception {
        int proxyPort = proxy.getLocalPort();

        String response = exchange(proxyPort, "PUT http://localhost:" + proxyPort + "/mockserver/status HTTP/1.1\r\n"
            + "Host: localhost:" + proxyPort + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 200"));
        assertThat(ports(response), contains(proxyPort));
    }

    @Test
    public void absoluteFormStatusToALoopbackPortRelayedToMockServerIsServedByItsControlPlane() throws Exception {
        int proxyPort = proxy.getLocalPort();
        int relayedPort = upstream.getLocalPort();

        // as through kubectl port-forward or ssh -L: a loopback port MockServer does not listen on may lead to it
        String response = exchange(proxyPort, "PUT http://localhost:" + relayedPort + "/mockserver/status HTTP/1.1\r\n"
            + "Host: localhost:" + relayedPort + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(ports(response), contains(proxyPort));
    }

    @Test
    public void absoluteFormClearToMockServerByNameAtItsPortIsServedByItsControlPlane() throws Exception {
        int proxyPort = proxy.getLocalPort();

        String response = exchange(proxyPort, "PUT http://mockserver.example:" + proxyPort + "/mockserver/clear HTTP/1.1\r\n"
            + "Host: mockserver.example:" + proxyPort + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 200"));
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(0));
        assertThat(upstreamClient.retrieveActiveExpectations(null).length, is(1));
    }

    @Test
    public void clearInConnectTunnelToMockServerItselfIsServedByItsControlPlane() throws Exception {
        int proxyPort = proxy.getLocalPort();

        String response = exchangeThroughConnect(proxyPort, "127.0.0.1:" + proxyPort, "PUT /mockserver/clear HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + proxyPort + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(response, startsWith("HTTP/1.1 200"));
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(0));
        assertThat(upstreamClient.retrieveActiveExpectations(null).length, is(1));
    }

    @Test
    public void statusToABoundExtraPortIsServedByTheControlPlane() throws Exception {
        assumeSiteLocalAddress();
        int proxyPort = proxy.getLocalPort();
        int extraPort = proxyClient.bind(0).get(0);

        String absoluteForm = exchange(proxyPort, "PUT http://" + upstreamHost + ":" + extraPort + "/mockserver/status HTTP/1.1\r\n"
            + "Host: " + upstreamHost + ":" + extraPort + "\r\nContent-Length: 0\r\n\r\n");
        String tunnelled = exchangeThroughConnect(proxyPort, upstreamHost + ":" + extraPort, "PUT /mockserver/status HTTP/1.1\r\n"
            + "Host: " + upstreamHost + ":" + extraPort + "\r\nContent-Length: 0\r\n\r\n");

        assertThat(ports(absoluteForm), containsInAnyOrder(proxyPort, extraPort));
        assertThat(ports(tunnelled), containsInAnyOrder(proxyPort, extraPort));
    }

    @Test
    public void clearSentDirectlyIsServedOverPlainHttpsAndHttp2() throws Exception {
        int proxyPort = proxy.getLocalPort();

        proxyClient.clear(request().withPath("/proxy"));
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(0));

        proxyClient.when(request().withPath("/proxy")).respond(response().withBody("proxy"));
        // not closed: closing a MockServerClient stops the server it is connected to
        MockServerClient secureClient = new MockServerClient("localhost", proxyPort).withSecure(true);
        secureClient.clear(request().withPath("/proxy"));
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(0));

        proxyClient.when(request().withPath("/proxy")).respond(response().withBody("proxy"));
        eventLoopGroup = new NioEventLoopGroup(1);
        try (Http2TestClient client = Http2TestClient.tls(eventLoopGroup, proxyPort)) {
            Http2Headers clear = new DefaultHttp2Headers()
                .method("PUT")
                .scheme("https")
                .authority("localhost:" + proxyPort)
                .path("/mockserver/clear");
            assertThat(client.send(clear, true).status(), is(200));
        }
        assertThat(proxyClient.retrieveActiveExpectations(null).length, is(0));
        assertThat(upstreamClient.retrieveActiveExpectations(null).length, is(1));
    }

    @Test
    public void controlPlaneCallThisServerForwardedBackToItselfIsNotServed() throws Exception {
        assumeSiteLocalAddress();
        try (Relay natToProxy = new Relay(proxy.getLocalPort())) {
            String natAuthority = upstreamHost + ":" + natToProxy.port();

            // the destination is an address and port of MockServer's that it cannot recognise, as behind NAT
            String response = exchangeThroughConnect(proxy.getLocalPort(), natAuthority, "PUT /mockserver/clear HTTP/1.1\r\n"
                + "Host: " + natAuthority + "\r\nContent-Length: 0\r\n\r\n");

            assertThat(response, startsWith("HTTP/1.1 404"));
            assertThat(proxyClient.retrieveActiveExpectations(null).length, is(1));

            String liveness = exchangeThroughConnect(proxy.getLocalPort(), natAuthority, "GET /health HTTP/1.1\r\nHost: " + natAuthority + "\r\n\r\n");

            assertThat(liveness, startsWith("HTTP/1.1 404"));
        }
    }

    @Test
    public void mcpCallThisServerForwardedBackToItselfIsNotServed() throws Exception {
        assumeSiteLocalAddress();
        try (Relay natToProxy = new Relay(proxy.getLocalPort())) {
            String natAuthority = upstreamHost + ":" + natToProxy.port();
            String body = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"}";

            String response = exchangeThroughConnect(proxy.getLocalPort(), natAuthority, "POST /mockserver/mcp HTTP/1.1\r\n"
                + "Host: " + natAuthority + "\r\nContent-Type: application/json\r\nAccept: application/json, text/event-stream\r\n"
                + "Content-Length: " + body.length() + "\r\n\r\n" + body);

            assertThat(response, startsWith("HTTP/1.1 404"));
        }
    }

    private void assumeSiteLocalAddress() {
        assumeTrue("needs a site-local IPv4 address on this host", upstreamHost != null);
    }

    private static String nonLoopbackAddressOfThisHost() {
        try {
            for (NetworkInterface networkInterface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (networkInterface.isUp() && !networkInterface.isLoopback()) {
                    for (InetAddress address : Collections.list(networkInterface.getInetAddresses())) {
                        // site-local, as an address on a VPN or tunnel interface may not accept a connection from this host
                        if (address instanceof Inet4Address && address.isSiteLocalAddress()) {
                            return address.getHostAddress();
                        }
                    }
                }
            }
        } catch (SocketException noInterfaces) {
            return null;
        }
        return null;
    }

    private static Http2Headers putStatus(String authority) {
        return new DefaultHttp2Headers()
            .method("PUT")
            .scheme("http")
            .authority(authority)
            .path("/mockserver/status");
    }

    private static List<Integer> ports(String response) throws IOException {
        return portsOf(response.substring(response.indexOf("\r\n\r\n") + 4));
    }

    private static List<Integer> portsOf(String body) throws IOException {
        List<Integer> ports = new ArrayList<>();
        for (JsonNode port : OBJECT_MAPPER.readTree(body).get("ports")) {
            ports.add(port.asInt());
        }
        return ports;
    }

    private static void openSocks5Tunnel(Socket socket, String ipv4, int port) throws IOException {
        socket.getOutputStream().write(new byte[]{5, 1, 0});
        byte[] method = socket.getInputStream().readNBytes(2);
        assertThat(method[1], is((byte) 0));
        byte[] address = InetAddress.getByName(ipv4).getAddress();
        socket.getOutputStream().write(new byte[]{5, 1, 0, 1, address[0], address[1], address[2], address[3], (byte) (port >> 8), (byte) port});
        byte[] reply = socket.getInputStream().readNBytes(4);
        assertThat(reply[1], is((byte) 0));
        int boundAddressLength = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : socket.getInputStream().read();
        socket.getInputStream().readNBytes(boundAddressLength + 2);
    }

    private static String exchange(int port, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return readResponse(socket.getInputStream());
        }
    }

    private static String exchangeThroughConnect(int port, String target, String request) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(15_000);
            openConnectTunnel(socket, target);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            return readResponse(socket.getInputStream());
        }
    }

    private List<String> sendThroughConnect(RecordingUpstream recordingUpstream, String target, String request, String expectedRequestLine) throws Exception {
        try (Socket socket = new Socket("127.0.0.1", proxy.getLocalPort())) {
            socket.setSoTimeout(15_000);
            openConnectTunnel(socket, target);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return recordingUpstream.awaitRequestLine(expectedRequestLine);
        }
    }

    private static void openConnectTunnel(Socket socket, String target) throws IOException {
        socket.getOutputStream().write(("CONNECT " + target + " HTTP/1.1\r\nHost: " + target + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        assertThat(readResponse(socket.getInputStream()), startsWith("HTTP/1.1 200"));
    }

    private static String readResponse(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int next = in.read();
            if (next < 0) {
                throw new IOException("connection closed before the end of the response headers: " + head);
            }
            head.write(next);
        }
        String headers = head.toString(StandardCharsets.ISO_8859_1);
        int contentLength = 0;
        for (String line : headers.split("\r\n")) {
            if (line.toLowerCase(Locale.ROOT).startsWith("content-length:")) {
                contentLength = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = in.readNBytes(contentLength);
        return headers + new String(body, StandardCharsets.UTF_8);
    }

    /**
     * Relays each connection it accepts, on every address of this host, to a port on loopback.
     */
    private static final class Relay implements AutoCloseable {

        private final ServerSocket serverSocket;

        Relay(int targetPort) throws IOException {
            serverSocket = new ServerSocket(0);
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket accepted = serverSocket.accept();
                        Socket target = new Socket("127.0.0.1", targetPort);
                        pipe(accepted, target);
                        pipe(target, accepted);
                    } catch (IOException closed) {
                        // the next accept fails once the server socket is closed, which ends the loop
                    }
                }
            }, "relay");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        private static void pipe(Socket from, Socket to) {
            Thread pipe = new Thread(() -> {
                try (from; to) {
                    from.getInputStream().transferTo(to.getOutputStream());
                } catch (IOException closed) {
                    // either end closed
                }
            }, "relay-pipe");
            pipe.setDaemon(true);
            pipe.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }

    /**
     * A plain HTTP server that is not MockServer: it records each request line it is sent and answers {@code 200}.
     */
    private static final class RecordingUpstream implements AutoCloseable {

        private final ServerSocket serverSocket;
        private final List<String> requestLines = new CopyOnWriteArrayList<>();
        private final List<String> requestHeads = new CopyOnWriteArrayList<>();

        RecordingUpstream() throws IOException {
            serverSocket = new ServerSocket(0);
            Thread acceptor = new Thread(this::accept, "recording-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        List<String> awaitRequestLine(String requestLine) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
            while (!requestLines.contains(requestLine) && System.nanoTime() < deadline) {
                TimeUnit.MILLISECONDS.sleep(20);
            }
            return requestLines;
        }

        String requestHead(String requestLine) {
            return requestHeads.stream().filter(head -> head.startsWith(requestLine)).findFirst().orElse("");
        }

        private void accept() {
            while (!serverSocket.isClosed()) {
                try (Socket socket = serverSocket.accept()) {
                    socket.setSoTimeout(15_000);
                    ByteArrayOutputStream head = new ByteArrayOutputStream();
                    InputStream in = socket.getInputStream();
                    int next;
                    while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n") && (next = in.read()) >= 0) {
                        head.write(next);
                    }
                    requestHeads.add(head.toString(StandardCharsets.ISO_8859_1));
                    requestLines.add(head.toString(StandardCharsets.ISO_8859_1).split("\r\n", 2)[0]);
                    socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Length: 8\r\nConnection: close\r\n\r\nupstream".getBytes(StandardCharsets.US_ASCII));
                } catch (IOException closed) {
                    // the next accept fails once the server socket is closed, which ends the loop
                }
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

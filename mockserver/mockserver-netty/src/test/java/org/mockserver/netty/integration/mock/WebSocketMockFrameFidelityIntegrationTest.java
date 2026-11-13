package org.mockserver.netty.integration.mock;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.proxy.websocket.RawWebSocket;
import org.mockserver.netty.integration.proxy.websocket.RawWebSocket.Response;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.model.WebSocketMessageMatcher.webSocketMessageMatcher;
import static org.mockserver.netty.integration.proxy.websocket.RawWebSocket.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A mocked WebSocket ({@code httpWebSocketResponse}) seen on the wire: messages the client sends in fragments or larger
 * than 64 KiB reaching the matchers whole, large and empty messages sent to the client, subprotocol and extension
 * negotiation, and the same mock reached through a CONNECT tunnel (plain and TLS) and a SOCKS5 tunnel.
 */
public class WebSocketMockFrameFidelityIntegrationTest {

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

    private static MockServer mockServer;
    private static final List<MockServer> limitedServers = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static final List<MockServerClient> limitedClients = new java.util.concurrent.CopyOnWriteArrayList<>();
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer(configuration().logLevel("WARN").startupWarmup(false), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        limitedServers.forEach(org.mockserver.stop.Stop::stopQuietly);
        // after their servers: stopping a client stops its server
        limitedClients.forEach(org.mockserver.stop.Stop::stopQuietly);
    }

    @Before
    public void reset() {
        mockServerClient.reset();
    }

    private static String self() {
        return "127.0.0.1:" + mockServer.getLocalPort();
    }

    private RawWebSocket upgraded(String path, String... headers) throws Exception {
        Socket socket = connected(mockServer.getLocalPort());
        Response response = upgrade(socket, self(), path, headers);
        assertThat(response.toString(), response.status, is(101));
        return new RawWebSocket(socket, true);
    }

    private void echoMock(String path) {
        mockServerClient
            .when(request().withPath(path))
            .respondWithWebSocket(webSocketResponse()
                .withCloseConnection(false)
                .withMatchers(
                    webSocketMessageMatcher().withText("hello world").withResponse(webSocketMessage("matched hello world")),
                    webSocketMessageMatcher().withTextRegex("a+").withResponse(webSocketMessage("matched a+"))
                ));
    }

    @Test
    public void shouldMatchAMessageTheClientSentInFragmentsOnceAsAWhole() throws Exception {
        echoMock("/mock-fidelity/fragmented");
        try (RawWebSocket client = upgraded("/mock-fidelity/fragmented")) {
            client.send(false, TEXT, "hello".getBytes(StandardCharsets.UTF_8));
            client.send(false, CONTINUATION, " ".getBytes(StandardCharsets.UTF_8));
            client.send(true, CONTINUATION, "world".getBytes(StandardCharsets.UTF_8));
            client.sendText("aaa");

            assertThat(client.read().describe(), is("TEXT fin \"matched hello world\""));
            assertThat(client.read().describe(), is("TEXT fin \"matched a+\""));
        }
    }

    @Test
    public void shouldMatchAMessageLargerThan64KiB() throws Exception {
        echoMock("/mock-fidelity/large-in");
        byte[] large = new byte[100 * 1024];
        Arrays.fill(large, (byte) 'a');
        try (RawWebSocket client = upgraded("/mock-fidelity/large-in")) {
            client.send(true, TEXT, large);

            Frame reply = client.read();
            assertThat(String.valueOf(reply), is("TEXT fin \"matched a+\""));
        }
    }

    @Test
    public void shouldCloseWith1009AMessageSentInFragmentsOverMaxRequestBodySize() throws Exception {
        try (RawWebSocket client = upgradedToLimitedMock("/mock-fidelity/fragments-over-limit")) {
            client.send(false, TEXT, "a".repeat(60 * 1024).getBytes(StandardCharsets.UTF_8));
            client.send(true, CONTINUATION, "a".repeat(60 * 1024).getBytes(StandardCharsets.UTF_8));

            assertThat(client.read().describe(), is("CLOSE fin 1009 \"Max message length of 102400 has been exceeded.\""));
            assertThat(client.read(), is(nullValue()));
        }
    }

    @Test
    public void shouldCloseWith1009AFrameOverMaxRequestBodySize() throws Exception {
        try (RawWebSocket client = upgradedToLimitedMock("/mock-fidelity/frame-over-limit")) {
            client.send(true, TEXT, "a".repeat(100 * 1024 + 1).getBytes(StandardCharsets.UTF_8));

            assertThat(client.read().describe(), is("CLOSE fin 1009 \"Max frame length of 102400 has been exceeded.\""));
            assertThat(client.read(), is(nullValue()));
        }
    }

    /** A mock answering any text, on a MockServer whose maxRequestBodySize is 100 KiB; stopped with the socket. */
    private RawWebSocket upgradedToLimitedMock(String path) throws Exception {
        MockServer limited = new MockServer(configuration().logLevel("WARN").startupWarmup(false).maxRequestBodySize(100 * 1024), 0);
        limitedServers.add(limited);
        MockServerClient limitedClient = new MockServerClient("127.0.0.1", limited.getLocalPort());
        limitedClients.add(limitedClient);
        limitedClient
            .when(request().withPath(path))
            .respondWithWebSocket(webSocketResponse().withCloseConnection(false)
                .withMatcher(webSocketMessageMatcher().withTextRegex("a+").withResponse(webSocketMessage("matched a+"))));
        Socket socket = connected(limited.getLocalPort());
        Response response = upgrade(socket, "127.0.0.1:" + limited.getLocalPort(), path);
        assertThat(response.toString(), response.status, is(101));
        return new RawWebSocket(socket, true);
    }

    @Test
    public void shouldServeTheRequestsThatFollowAMockedStatus101InATunnelAsOnADirectConnection() throws Exception {
        mockServerClient.when(request().withPath("/mock-fidelity/status-101")).respond(response().withStatusCode(101));
        mockServerClient.when(request().withPath("/mock-fidelity/after-101")).respond(response().withBody("after"));
        Socket socket = connected(mockServer.getLocalPort());
        assertThat(connectTunnel(socket, "mocked.example:80", null), is(200));

        write(socket, "GET /mock-fidelity/status-101 HTTP/1.1\r\nHost: mocked.example\r\nContent-Length: 0\r\n\r\n");
        assertThat(readStatusAndBody(socket), is("101"));
        write(socket, "GET /mock-fidelity/after-101 HTTP/1.1\r\nHost: mocked.example\r\nContent-Length: 0\r\n\r\n");
        assertThat(readStatusAndBody(socket), is("200 after"));
        assertThat(mockServerClient.retrieveRecordedRequests(request().withPath("/mock-fidelity/after-101")).length, is(1));
    }

    private static String readStatusAndBody(Socket socket) throws Exception {
        Response response = readResponseHead(socket.getInputStream());
        String length = response.header("content-length");
        String body = length == null ? "" : new String(readFully(socket.getInputStream(), Integer.parseInt(length.trim())), StandardCharsets.UTF_8);
        return response.status + (body.isEmpty() ? "" : " " + body);
    }

    @Test
    public void shouldSendAMessageLargerThan64KiB() throws Exception {
        String large = "b".repeat(200 * 1024);
        mockServerClient
            .when(request().withPath("/mock-fidelity/large-out"))
            .respondWithWebSocket(webSocketResponse().withMessages(webSocketMessage(large), webSocketMessage("after")));
        try (RawWebSocket client = upgraded("/mock-fidelity/large-out")) {
            Frame first = client.read();
            assertThat(first.describe(), is("TEXT fin " + large.length() + " bytes"));
            assertThat(first.text(), is(large));
            assertThat(client.read().describe(), is("TEXT fin \"after\""));
            assertThat(client.read().describe(), is("CLOSE fin 1000 \"Bye\""));
        }
    }

    @Test
    public void shouldSelectTheMocksSubprotocolFromSeveralOffered() throws Exception {
        mockServerClient
            .when(request().withPath("/mock-fidelity/subprotocol"))
            .respondWithWebSocket(webSocketResponse().withSubprotocol("v2.chat").withMessage(webSocketMessage("hi")));
        Socket socket = connected(mockServer.getLocalPort());
        Response response = upgrade(socket, self(), "/mock-fidelity/subprotocol", "Sec-WebSocket-Protocol: v1.chat, v2.chat");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-protocol"), is(List.of("v2.chat")));
            assertThat(client.read().describe(), is("TEXT fin \"hi\""));
        }
    }

    @Test
    public void shouldSelectNoSubprotocolWhenTheMocksIsNotOffered() throws Exception {
        mockServerClient
            .when(request().withPath("/mock-fidelity/subprotocol-not-offered"))
            .respondWithWebSocket(webSocketResponse().withSubprotocol("v2.chat").withMessage(webSocketMessage("hi")));
        Socket socket = connected(mockServer.getLocalPort());
        Response response = upgrade(socket, self(), "/mock-fidelity/subprotocol-not-offered", "Sec-WebSocket-Protocol: v1.chat");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-protocol"), is(nullValue()));
            assertThat(client.read().describe(), is("TEXT fin \"hi\""));
        }
    }

    @Test
    public void shouldNegotiateNoExtensionWhenTheClientOffersPermessageDeflate() throws Exception {
        mockServerClient
            .when(request().withPath("/mock-fidelity/deflate"))
            .respondWithWebSocket(webSocketResponse().withMessage(webSocketMessage("plain")));
        Socket socket = connected(mockServer.getLocalPort());
        Response response = upgrade(socket, self(), "/mock-fidelity/deflate", "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-extensions"), is(nullValue()));
            assertThat(client.read().describe(), is("TEXT fin \"plain\""));
        }
    }

    @Test
    public void shouldServeTheMockThroughAConnectTunnel() throws Exception {
        echoMock("/mock-fidelity/connect");
        Socket socket = connected(mockServer.getLocalPort());
        assertThat(connectTunnel(socket, "mocked.example:80", null), is(200));
        Response response = upgrade(socket, "mocked.example", "/mock-fidelity/connect");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            client.sendText("aa");
            assertThat(client.read().describe(), is("TEXT fin \"matched a+\""));
            client.sendClose(4001, "bye");
            assertThat(client.read().describe(), is("CLOSE fin 4001 \"bye\""));
        }
    }

    @Test
    public void shouldServeTheMockThroughATlsConnectTunnel() throws Exception {
        echoMock("/mock-fidelity/connect-tls");
        Socket socket = connected(mockServer.getLocalPort());
        assertThat(connectTunnel(socket, "mocked.example:443", null), is(200));
        Socket tls = tls(socket, "mocked.example");
        Response response = upgrade(tls, "mocked.example", "/mock-fidelity/connect-tls");
        try (RawWebSocket client = new RawWebSocket(tls, true)) {
            assertThat(response.toString(), response.status, is(101));
            client.sendText("aaaa");
            assertThat(client.read().describe(), is("TEXT fin \"matched a+\""));
        }
    }

    @Test
    public void shouldServeTheMockThroughASocks5Tunnel() throws Exception {
        echoMock("/mock-fidelity/socks");
        Socket socket = connected(mockServer.getLocalPort());
        // the destination is MockServer's own port, whose expectation answers
        assertThat(socks5(socket, mockServer.getLocalPort(), null, null), is(true));
        Response response = upgrade(socket, self(), "/mock-fidelity/socks");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            client.sendText("a");
            assertThat(client.read().describe(), is("TEXT fin \"matched a+\""));
        }
    }
}

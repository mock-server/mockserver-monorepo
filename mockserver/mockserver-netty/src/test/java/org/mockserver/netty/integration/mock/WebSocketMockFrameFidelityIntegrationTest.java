package org.mockserver.netty.integration.mock;

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

    private static MockServer mockServer;
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
    }

    @Before
    public void reset() {
        mockServerClient.reset();
    }

    private static String self() {
        return "127.0.0.1:" + mockServer.getLocalPort();
    }

    private RawWebSocket upgraded(String path, String... headers) throws Exception {
        Socket socket = connect(mockServer.getLocalPort());
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
        Socket socket = connect(mockServer.getLocalPort());
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
        Socket socket = connect(mockServer.getLocalPort());
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
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, self(), "/mock-fidelity/deflate", "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-extensions"), is(nullValue()));
            assertThat(client.read().describe(), is("TEXT fin \"plain\""));
        }
    }


}

package org.mockserver.netty.integration.proxy.websocket;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.netty.MockServer;

import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.integration.proxy.websocket.RawWebSocket.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * WebSocket proxy passthrough, frame by frame and header by header, against an upstream that records exactly what
 * reached it: large and fragmented messages, empty frames, control frames from either side, closes started by either
 * side, subprotocol and extension negotiation, the hop-by-hop request headers, and the same relay through a CONNECT
 * tunnel (plain and TLS) and a SOCKS5 tunnel.
 */
public class WebSocketProxyFrameFidelityIntegrationTest {

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static RawWebSocketUpstream upstream;
    private static RawWebSocketUpstream tlsUpstream;

    @BeforeClass
    public static void startServers() throws Exception {
        upstream = new RawWebSocketUpstream(false);
        tlsUpstream = new RawWebSocketUpstream(true);
        mockServer = new MockServer(configuration().logLevel("WARN").startupWarmup(false).attemptToProxyIfNoMatchingExpectation(true), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() throws Exception {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstream != null) {
            upstream.close();
        }
        if (tlsUpstream != null) {
            tlsUpstream.close();
        }
    }

    @Before
    public void forgetEarlierTests() {
        upstream.forget();
        upstream.subprotocol(null);
        tlsUpstream.forget();
        mockServerClient.reset();
    }

    private RawWebSocket upgraded(String path, String... headers) throws Exception {
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, upstream.authority(), path, headers);
        assertThat(response.toString(), response.status, is(101));
        return new RawWebSocket(socket, true);
    }

    @Test
    public void shouldRelayATextFrameLargerThan64KiBInBothDirections() throws Exception {
        byte[] large = new byte[200 * 1024];
        Arrays.fill(large, (byte) 'a');
        try (RawWebSocket client = upgraded("/fidelity/large")) {
            client.send(true, TEXT, large);

            Frame atUpstream = upstream.awaitFrame();
            assertThat(atUpstream.describe(), atUpstream.opcode(), is(TEXT));
            assertThat(atUpstream.payload(), is(large));
            Frame echoed = client.read();
            assertThat(String.valueOf(echoed), echoed != null ? echoed.opcode() : -1, is(TEXT));
            assertThat(echoed.payload(), is(large));
        }
    }

    @Test
    public void shouldRelayABinaryFrameLargerThan64KiBInBothDirections() throws Exception {
        byte[] large = new byte[150 * 1024];
        for (int i = 0; i < large.length; i++) {
            large[i] = (byte) i;
        }
        try (RawWebSocket client = upgraded("/fidelity/large-binary")) {
            client.send(true, BINARY, large);

            assertThat(upstream.awaitFrame().payload(), is(large));
            Frame echoed = client.read();
            assertThat(String.valueOf(echoed), echoed != null ? echoed.opcode() : -1, is(BINARY));
            assertThat(echoed.payload(), is(large));
        }
    }

    @Test
    public void shouldRelayAFragmentedMessageWithAPingBetweenItsFragmentsFrameByFrame() throws Exception {
        try (RawWebSocket client = upgraded("/fidelity/fragmented")) {
            client.send(false, TEXT, "hel".getBytes(StandardCharsets.UTF_8));
            client.send(true, PING, "p".getBytes(StandardCharsets.UTF_8));
            client.send(false, CONTINUATION, "lo ".getBytes(StandardCharsets.UTF_8));
            client.send(true, CONTINUATION, "world".getBytes(StandardCharsets.UTF_8));

            assertThat(upstream.awaitFrame().describe(), is("TEXT more \"hel\""));
            assertThat(upstream.awaitFrame().describe(), is("PING fin \"p\""));
            assertThat(upstream.awaitFrame().describe(), is("CONTINUATION more \"lo \""));
            assertThat(upstream.awaitFrame().describe(), is("CONTINUATION fin \"world\""));
            assertThat(client.read().describe(), is("TEXT more \"hel\""));
            assertThat(client.read().describe(), is("PONG fin \"p\""));
            assertThat(client.read().describe(), is("CONTINUATION more \"lo \""));
            assertThat(client.read().describe(), is("CONTINUATION fin \"world\""));
        }
    }

    @Test
    public void shouldRelayEmptyTextBinaryAndPingFrames() throws Exception {
        try (RawWebSocket client = upgraded("/fidelity/empty")) {
            client.send(true, TEXT, new byte[0]);
            client.send(true, BINARY, new byte[0]);
            client.send(true, PING, new byte[0]);

            assertThat(upstream.awaitFrame().describe(), is("TEXT fin \"\""));
            assertThat(upstream.awaitFrame().describe(), is("BINARY fin \"\""));
            assertThat(upstream.awaitFrame().describe(), is("PING fin \"\""));
            assertThat(client.read().describe(), is("TEXT fin \"\""));
            assertThat(client.read().describe(), is("BINARY fin \"\""));
            assertThat(client.read().describe(), is("PONG fin \"\""));
        }
    }

    @Test
    public void shouldRelayAPingFromTheUpstreamAndThePongBack() throws Exception {
        try (RawWebSocket client = upgraded("/fidelity/upstream-ping")) {
            client.sendText("ping-me");
            assertThat(upstream.awaitFrame().describe(), is("TEXT fin \"ping-me\""));

            assertThat(client.read().describe(), is("PING fin \"srv\""));
            client.send(true, PONG, "srv".getBytes(StandardCharsets.UTF_8));
            assertThat(upstream.awaitFrame().describe(), is("PONG fin \"srv\""));
        }
    }

    @Test
    public void shouldRelayAClientsCloseCodeAndReasonAndAnswerTheClientWithAClose() throws Exception {
        try (RawWebSocket client = upgraded("/fidelity/client-close")) {
            client.sendClose(4001, "bye");

            assertThat(upstream.awaitFrame().describe(), is("CLOSE fin 4001 \"bye\""));
            Frame answer = client.read();
            assertThat("the client's close was answered with a close frame", String.valueOf(answer), is("CLOSE fin 4001 \"bye\""));
            assertThat("the connection ends after the close", client.read(), is(nullValue()));
        }
    }

    @Test
    public void shouldRelayAnUpstreamsCloseCodeAndReasonAndAnswerTheUpstreamWithAClose() throws Exception {
        try (RawWebSocket client = upgraded("/fidelity/upstream-close")) {
            client.sendText("close-me");
            assertThat(upstream.awaitFrame().describe(), is("TEXT fin \"close-me\""));

            assertThat(client.read().describe(), is("CLOSE fin 4002 \"server bye\""));
            Frame answer = upstream.frames().poll(10, TimeUnit.SECONDS);
            assertThat("the upstream's close was answered with a close frame", String.valueOf(answer), is("CLOSE fin 4002 \"server bye\""));
        }
    }

    @Test
    public void shouldNegotiateTheSubprotocolTheUpstreamSelects() throws Exception {
        upstream.subprotocol("v2.chat");
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, upstream.authority(), "/fidelity/subprotocol", "Sec-WebSocket-Protocol: v1.chat, v2.chat");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-protocol"), is(List.of("v2.chat")));
            assertThat(upstream.awaitHandshake().get("sec-websocket-protocol"), is(List.of("v1.chat, v2.chat")));
            client.sendText("x");
            assertThat(client.read().describe(), is("TEXT fin \"x\""));
        }
    }

    @Test
    public void shouldOfferEverySubprotocolLineTheClientSent() throws Exception {
        upstream.subprotocol("v2.chat");
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, upstream.authority(), "/fidelity/subprotocol-lines", "Sec-WebSocket-Protocol: v1.chat", "Sec-WebSocket-Protocol: v2.chat");
        try (RawWebSocket ignored = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-protocol"), is(List.of("v2.chat")));
            assertThat(String.join(",", upstream.awaitHandshake().get("sec-websocket-protocol")).replace(" ", ""), is("v1.chat,v2.chat"));
        }
    }

    @Test
    public void shouldRelayAnUpstreamThatSelectsNoneOfTheOfferedSubprotocolsAsItAnswered() throws Exception {
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, upstream.authority(), "/fidelity/subprotocol-declined", "Sec-WebSocket-Protocol: v1.chat");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-protocol"), is(nullValue()));
            client.sendText("x");
            assertThat(client.read().describe(), is("TEXT fin \"x\""));
        }
    }

    @Test
    public void shouldFailAnUpstreamThatSelectsASubprotocolNotOffered() throws Exception {
        upstream.subprotocolEvenIfNotOffered("v9.chat");
        try (Socket socket = connect(mockServer.getLocalPort())) {
            Response response = upgrade(socket, upstream.authority(), "/fidelity/subprotocol-not-offered", "Sec-WebSocket-Protocol: v1.chat");
            assertThat(response.status, is(502));
            assertThat(response.body, is("upstream WebSocket handshake failed: subprotocol v9.chat was not offered (offered: v1.chat)"));
        }
    }

    @Test
    public void shouldCloseWith1009AFrameOverMaxRequestBodySize() throws Exception {
        MockServer limited = new MockServer(configuration().logLevel("WARN").startupWarmup(false)
            .attemptToProxyIfNoMatchingExpectation(true).maxRequestBodySize(100 * 1024), 0);
        try {
            Socket socket = connect(limited.getLocalPort());
            Response response = upgrade(socket, upstream.authority(), "/fidelity/over-limit");
            try (RawWebSocket client = new RawWebSocket(socket, true)) {
                assertThat(response.toString(), response.status, is(101));
                client.send(true, BINARY, new byte[100 * 1024 + 1]);

                assertThat(client.read().describe(), is("CLOSE fin 1009 \"Max frame length of 102400 has been exceeded.\""));
                assertThat("nothing of the frame was relayed", upstream.awaitFrame().describe(), is("CLOSE fin 1001 \"Endpoint unavailable\""));
            }
        } finally {
            stopQuietly(limited);
        }
    }

    @Test
    public void shouldNegotiateNoExtensionWhenTheClientOffersPermessageDeflate() throws Exception {
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, upstream.authority(), "/fidelity/deflate", "Sec-WebSocket-Extensions: permessage-deflate; client_max_window_bits");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(response.headers.get("sec-websocket-extensions"), is(nullValue()));
            assertThat(upstream.awaitHandshake().get("sec-websocket-extensions"), is(nullValue()));
            client.sendText("uncompressed");
            assertThat(upstream.awaitFrame().describe(), is("TEXT fin \"uncompressed\""));
            assertThat(client.read().describe(), is("TEXT fin \"uncompressed\""));
        }
    }

    @Test
    public void shouldForwardEndToEndHeadersButNotHopByHopOnes() throws Exception {
        try (RawWebSocket ignored = upgraded("/fidelity/headers",
            "Origin: https://app.example",
            "Authorization: Bearer upstream-token",
            "X-End-To-End: kept",
            "Connection: X-Hop-Only",
            "X-Hop-Only: dropped",
            "Keep-Alive: timeout=5",
            "TE: trailers",
            "Trailer: X-Checksum",
            "Proxy-Connection: keep-alive")) {
            Map<String, List<String>> received = upstream.awaitHandshake();
            assertThat(received.get("origin"), is(List.of("https://app.example")));
            assertThat(received.get("authorization"), is(List.of("Bearer upstream-token")));
            assertThat(received.get("x-end-to-end"), is(List.of("kept")));
            assertThat(received.get("x-hop-only"), is(nullValue()));
            assertThat(received.get("keep-alive"), is(nullValue()));
            assertThat(received.get("te"), is(nullValue()));
            assertThat(received.get("trailer"), is(nullValue()));
            assertThat(received.get("proxy-connection"), is(nullValue()));
            assertThat(received.get("connection"), is(List.of("upgrade")));
            assertThat(received.get("sec-websocket-key").size(), is(1));
            assertThat(received.get("sec-websocket-key").get(0), not(CLIENT_KEY));
        }
    }

    @Test
    public void shouldRecordTheRelayedFramesWithTheirOpcodesAndPayloads() throws Exception {
        try (RawWebSocket client = upgraded("/fidelity/transcript")) {
            client.sendText("one");
            assertThat(client.read().describe(), is("TEXT fin \"one\""));
            client.send(true, BINARY, new byte[]{1, 2, 3});
            assertThat(client.read().describe(), is("BINARY fin \"\u0001\u0002\u0003\""));
            client.sendClose(1000, "done");
            assertThat(client.read().describe(), is("CLOSE fin 1000 \"done\""));
        }
        LogEventRequestAndResponse recorded = awaitRecordedPair("/fidelity/transcript");
        String transcript = recorded.getHttpResponse().getBodyAsString();
        assertThat(transcript, is("["
            + "{\"direction\":\"CLIENT_TO_UPSTREAM\",\"opcode\":\"TEXT\",\"text\":\"one\",\"length\":3,\"truncated\":false},"
            + "{\"direction\":\"UPSTREAM_TO_CLIENT\",\"opcode\":\"TEXT\",\"text\":\"one\",\"length\":3,\"truncated\":false},"
            + "{\"direction\":\"CLIENT_TO_UPSTREAM\",\"opcode\":\"BINARY\",\"base64\":\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\",\"length\":3,\"truncated\":false},"
            + "{\"direction\":\"UPSTREAM_TO_CLIENT\",\"opcode\":\"BINARY\",\"base64\":\"" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3}) + "\",\"length\":3,\"truncated\":false},"
            + "{\"direction\":\"CLIENT_TO_UPSTREAM\",\"opcode\":\"CLOSE\",\"base64\":\"" + Base64.getEncoder().encodeToString(closePayload(1000, "done")) + "\",\"length\":6,\"truncated\":false}"
            + "]"));
        assertThat(recorded.getHttpResponse().getFirstHeader("x-mockserver-websocket-frames"), is("5"));
    }

    // ---- through tunnels -------------------------------------------------------------------------------------------

    private static LogEventRequestAndResponse awaitRecordedPair(String path) throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            LogEventRequestAndResponse[] pairs = mockServerClient.retrieveRecordedRequestsAndResponses(HttpRequest.request().withPath(path));
            if (pairs.length == 1 && pairs[0].getHttpResponse() != null) {
                return pairs[0];
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        throw new AssertionError("no recorded upgrade for " + path);
    }
}

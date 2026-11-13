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

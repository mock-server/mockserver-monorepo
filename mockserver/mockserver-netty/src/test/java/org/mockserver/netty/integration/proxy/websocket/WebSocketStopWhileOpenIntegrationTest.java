package org.mockserver.netty.integration.proxy.websocket;

import org.junit.After;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.netty.MockServer;

import java.net.Socket;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpWebSocketResponse.webSocketResponse;
import static org.mockserver.model.WebSocketMessage.webSocketMessage;
import static org.mockserver.netty.integration.proxy.websocket.RawWebSocket.*;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * MockServer stopping while a WebSocket is open: the stop is not held by the open connection, and the connection
 * ends for the client (and, for a relay, the upstream) rather than being left open.
 */
public class WebSocketStopWhileOpenIntegrationTest {

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private RawWebSocketUpstream upstream;

    @After
    public void stopServers() throws Exception {
        stopQuietly(mockServer);
        // after the server, which the test stops itself: stopping a client stops its server
        stopQuietly(mockServerClient);
        if (upstream != null) {
            upstream.close();
        }
    }

    @Test
    public void shouldEndARelayedWebSocketWhenMockServerStops() throws Exception {
        upstream = new RawWebSocketUpstream(false);
        mockServer = new MockServer(configuration().logLevel("WARN").startupWarmup(false).attemptToProxyIfNoMatchingExpectation(true), 0);
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, upstream.authority(), "/stop/relayed");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            client.sendText("before stop");
            assertThat(client.read().describe(), is("TEXT fin \"before stop\""));
            assertThat(upstream.awaitFrame().describe(), is("TEXT fin \"before stop\""));

            long stopMillis = timedStop();

            assertThat("stop took " + stopMillis + " ms", stopMillis, lessThan(10_000L));
            assertThat(String.valueOf(readToEnd(client)), is("[]"));
            assertThat("the upstream's connection ended", upstream.awaitConnectionEnded(10), is(true));
            assertThat(String.valueOf(upstream.frames().poll()), is("null"));
        }
    }

    @Test
    public void shouldEndAMockedWebSocketWhenMockServerStops() throws Exception {
        mockServer = new MockServer(configuration().logLevel("WARN").startupWarmup(false), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        mockServerClient.when(request().withPath("/stop/mocked"))
            .respondWithWebSocket(webSocketResponse().withCloseConnection(false).withMessage(webSocketMessage("open")));
        Socket socket = connect(mockServer.getLocalPort());
        Response response = upgrade(socket, "127.0.0.1:" + mockServer.getLocalPort(), "/stop/mocked");
        try (RawWebSocket client = new RawWebSocket(socket, true)) {
            assertThat(response.toString(), response.status, is(101));
            assertThat(client.read().describe(), is("TEXT fin \"open\""));

            long stopMillis = timedStop();

            assertThat("stop took " + stopMillis + " ms", stopMillis, lessThan(10_000L));
            assertThat(String.valueOf(readToEnd(client)), is("[]"));
        }
    }

    private long timedStop() {
        long start = System.nanoTime();
        mockServer.stop();
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }

    /** Every frame until the connection ends; a read that times out fails the test, as the connection stayed open. */
    private static java.util.List<String> readToEnd(RawWebSocket client) throws Exception {
        client.socket().setSoTimeout(10_000);
        java.util.List<String> frames = new java.util.ArrayList<>();
        Frame frame;
        while ((frame = client.read()) != null) {
            frames.add(frame.describe());
        }
        return frames;
    }
}

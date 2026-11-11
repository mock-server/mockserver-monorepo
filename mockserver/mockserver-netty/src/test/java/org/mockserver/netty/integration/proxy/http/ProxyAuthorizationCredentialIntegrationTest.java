package org.mockserver.netty.integration.proxy.http;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.model.HttpRequest;
import org.slf4j.event.Level;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * With proxy authentication configured, the client's {@code Proxy-Authorization} credential authenticates the proxy hop
 * only: it is not forwarded upstream, nor kept in recorded requests or the log, whether it was right or wrong. Without
 * proxy authentication configured, the header is recorded as sent, as before.
 */
public class ProxyAuthorizationCredentialIntegrationTest {

    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-password";
    private static final String VALID_TOKEN = encode(USERNAME + ":" + PASSWORD);
    private static final String WRONG_TOKEN = encode(USERNAME + ":near-miss-password");
    private static final int READ_TIMEOUT_MILLIS = 5_000;

    private static ClientAndServer upstream;
    private static ClientAndServer authenticatingProxy;
    private static ClientAndServer openProxy;

    @BeforeClass
    public static void startServers() {
        upstream = startClientAndServer(configuration());
        authenticatingProxy = startClientAndServer(configuration()
            .logLevel(Level.INFO)
            .proxyAuthenticationUsername(USERNAME)
            .proxyAuthenticationPassword(PASSWORD));
        openProxy = startClientAndServer(configuration().logLevel(Level.INFO));
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(openProxy);
        stopQuietly(authenticatingProxy);
        stopQuietly(upstream);
    }

    @Before
    public void reset() {
        authenticatingProxy.reset();
        openProxy.reset();
        upstream.reset();
        upstream.when(request().withPath("/relayed/.*")).respond(response().withStatusCode(200).withBody("from upstream"));
    }

    @Test
    public void shouldNeitherForwardNorRecordAValidCredential() throws Exception {
        // when
        String head = sendThroughProxy(authenticatingProxy, "GET http://127.0.0.1:" + upstream.getLocalPort() + "/relayed/valid HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + upstream.getLocalPort() + "\r\n"
            + "Proxy-Authorization: Basic " + VALID_TOKEN + "\r\n"
            + "Content-Length: 0\r\n\r\n");

        // then
        assertThat(head, startsWith("HTTP/1.1 200"));
        upstream.verify(request().withPath("/relayed/valid"), exactly(1));
        HttpRequest[] receivedUpstream = upstream.retrieveRecordedRequests(request().withPath("/relayed/valid"));
        assertThat(receivedUpstream[0].containsHeader("Proxy-Authorization"), is(false));
        assertThat(Arrays.toString(authenticatingProxy.retrieveRecordedRequests(null)), not(containsString(VALID_TOKEN)));
        assertThat(authenticatingProxy.retrieveRecordedRequestsAndResponses(null).length, is(1));
        assertThat(Arrays.toString(authenticatingProxy.retrieveRecordedRequestsAndResponses(null)), not(containsString(VALID_TOKEN)));
        assertThat(authenticatingProxy.retrieveLogMessages(null), not(containsString(VALID_TOKEN)));
    }

    @Test
    public void shouldNotRecordAWrongCredential() throws Exception {
        // when
        String head = sendThroughProxy(authenticatingProxy, "GET http://127.0.0.1:" + upstream.getLocalPort() + "/relayed/wrong HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + upstream.getLocalPort() + "\r\n"
            + "Proxy-Authorization: Basic " + WRONG_TOKEN + "\r\n"
            + "Content-Length: 0\r\n\r\n");

        // then
        assertThat(head, startsWith("HTTP/1.1 407"));
        upstream.verify(request().withPath("/relayed/wrong"), exactly(0));
        assertThat(Arrays.toString(authenticatingProxy.retrieveRecordedRequests(null)), not(containsString(WRONG_TOKEN)));
        assertThat(authenticatingProxy.retrieveLogMessages(null), not(containsString(WRONG_TOKEN)));
    }

    @Test
    public void shouldNotLogTheCredentialOfARefusedConnect() throws Exception {
        // when
        String head = sendThroughProxy(authenticatingProxy, "CONNECT 127.0.0.1:" + upstream.getLocalPort() + " HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + upstream.getLocalPort() + "\r\n"
            + "Proxy-Authorization: Basic " + WRONG_TOKEN + "\r\n\r\n");

        // then
        assertThat(head, startsWith("HTTP/1.1 407"));
        String logs = authenticatingProxy.retrieveLogMessages(null);
        assertThat(logs, containsString("proxy authentication failed"));
        assertThat(logs, not(containsString(WRONG_TOKEN)));
    }

    @Test
    public void shouldChallengeAWebSocketUpgradeWithAWrongCredentialInsteadOfRelayingIt() throws Exception {
        // when
        String head = sendThroughProxy(authenticatingProxy, "GET http://127.0.0.1:" + upstream.getLocalPort() + "/relayed/websocket HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + upstream.getLocalPort() + "\r\n"
            + "Upgrade: websocket\r\n"
            + "Connection: Upgrade\r\n"
            + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
            + "Sec-WebSocket-Version: 13\r\n"
            + "Proxy-Authorization: Basic " + WRONG_TOKEN + "\r\n\r\n");

        // then
        assertThat(head, startsWith("HTTP/1.1 407"));
        upstream.verify(request().withPath("/relayed/websocket"), exactly(0));
        assertThat(authenticatingProxy.retrieveLogMessages(null), not(containsString(WRONG_TOKEN)));
    }

    @Test
    public void shouldRelayAWebSocketUpgradeWithAValidCredentialWithoutTheCredential() throws Exception {
        try (ServerSocket webSocketUpstream = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // given - an upstream that records the handshake it receives and accepts it
            CompletableFuture<String> upstreamHandshake = CompletableFuture.supplyAsync(() -> acceptWebSocketHandshake(webSocketUpstream));

            // when
            String head = sendThroughProxy(authenticatingProxy, "GET http://127.0.0.1:" + webSocketUpstream.getLocalPort() + "/socket HTTP/1.1\r\n"
                + "Host: 127.0.0.1:" + webSocketUpstream.getLocalPort() + "\r\n"
                + "Upgrade: websocket\r\n"
                + "Connection: Upgrade\r\n"
                + "Sec-WebSocket-Key: dGhlIHNhbXBsZSBub25jZQ==\r\n"
                + "Sec-WebSocket-Version: 13\r\n"
                + "Proxy-Authorization: Basic " + VALID_TOKEN + "\r\n\r\n");

            // then
            assertThat(head, startsWith("HTTP/1.1 101"));
            String relayed = upstreamHandshake.get(READ_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
            assertThat(relayed, containsString("GET /socket"));
            assertThat(relayed.toLowerCase(), not(containsString("proxy-authorization")));
            assertThat(relayed, not(containsString(VALID_TOKEN)));
        }
    }

    @Test
    public void shouldNotRecordTheCredentialOfAnEarlyMatchedRequest() throws Exception {
        // given
        authenticatingProxy.when(request().withPath("/early").withRespondBeforeBody(true)).respond(response().withBody("early"));

        // when
        String head = sendThroughProxy(authenticatingProxy, "POST /early HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + authenticatingProxy.getLocalPort() + "\r\n"
            + "Proxy-Authorization: Basic " + VALID_TOKEN + "\r\n"
            + "Content-Length: 0\r\n\r\n");

        // then
        assertThat(head, startsWith("HTTP/1.1 200"));
        authenticatingProxy.verify(request().withPath("/early"), exactly(1));
        assertThat(Arrays.toString(authenticatingProxy.retrieveRecordedRequests(null)), not(containsString(VALID_TOKEN)));
        assertThat(authenticatingProxy.retrieveLogMessages(null), not(containsString(VALID_TOKEN)));
    }

    @Test
    public void shouldNotEarlyMatchOnTheProxyCredential() throws Exception {
        // given
        authenticatingProxy.when(request().withPath("/early-credential").withHeader("Proxy-Authorization", ".*").withRespondBeforeBody(true)).respond(response().withBody("early"));

        // when
        String head = sendThroughProxy(authenticatingProxy, "POST /early-credential HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + authenticatingProxy.getLocalPort() + "\r\n"
            + "Proxy-Authorization: Basic " + VALID_TOKEN + "\r\n"
            + "Content-Length: 0\r\n\r\n");

        // then
        assertThat(head, startsWith("HTTP/1.1 404"));
    }

    @Test
    public void shouldRecordTheHeaderAsSentWhenProxyAuthenticationIsNotConfigured() throws Exception {
        // when
        String head = sendThroughProxy(openProxy, "GET http://127.0.0.1:" + upstream.getLocalPort() + "/relayed/open HTTP/1.1\r\n"
            + "Host: 127.0.0.1:" + upstream.getLocalPort() + "\r\n"
            + "Proxy-Authorization: Basic " + VALID_TOKEN + "\r\n"
            + "Content-Length: 0\r\n\r\n");

        // then - recorded as before, and, as before, dropped from the forward as a hop-by-hop header
        assertThat(head, startsWith("HTTP/1.1 200"));
        HttpRequest[] recorded = openProxy.retrieveRecordedRequests(request().withPath("/relayed/open"));
        assertThat(recorded[0].getFirstHeader("Proxy-Authorization"), is("Basic " + VALID_TOKEN));
        HttpRequest[] receivedUpstream = upstream.retrieveRecordedRequests(request().withPath("/relayed/open"));
        assertThat(receivedUpstream[0].containsHeader("Proxy-Authorization"), is(false));
    }

    private static String sendThroughProxy(ClientAndServer proxy, String request) throws IOException {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", proxy.getLocalPort()), READ_TIMEOUT_MILLIS);
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            return readHead(socket.getInputStream());
        }
    }

    /** The status line and headers of the next response, with its body read past. */
    private static String readHead(InputStream in) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        while (!head.toString(StandardCharsets.ISO_8859_1).endsWith("\r\n\r\n")) {
            int b = in.read();
            if (b == -1) {
                break;
            }
            head.write(b);
        }
        String text = head.toString(StandardCharsets.ISO_8859_1);
        for (String line : text.split("\r\n")) {
            if (line.toLowerCase().startsWith("content-length:")) {
                in.readNBytes(Integer.parseInt(line.substring("content-length:".length()).trim()));
            }
        }
        return text;
    }

    private static String acceptWebSocketHandshake(ServerSocket server) {
        try (Socket socket = server.accept()) {
            socket.setSoTimeout(READ_TIMEOUT_MILLIS);
            String handshake = readHead(socket.getInputStream());
            String key = "";
            for (String line : handshake.split("\r\n")) {
                if (line.toLowerCase().startsWith("sec-websocket-key:")) {
                    key = line.substring("sec-websocket-key:".length()).trim();
                }
            }
            byte[] digest = MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + Base64.getEncoder().encodeToString(digest) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            // stay open until the relay closes the connection, so the 101 reaches the client first
            try {
                while (socket.getInputStream().read() != -1) {
                    // discard frames
                }
            } catch (IOException closedOrQuiet) {
                // the relay is done either way
            }
            return handshake;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String encode(String credentials) {
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }
}

package org.mockserver.netty.integration.proxy.direct;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.proxy.http.RecordingUpstreamProxy;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * With {@code forwardBinaryServerFirstWaitMillis}, a port-forwarded connection whose client sends nothing has its
 * upstream connection opened, so a protocol in which the server speaks first (an SMTP-like greeting here) can be
 * proxied over real sockets. A client that speaks at once is detected as before.
 */
public class BinaryServerFirstIntegrationTest {

    private static final long WAIT_MILLIS = 200;
    private MockServer mockServer;

    @After
    public void stopMockServer() {
        stopQuietly(mockServer);
    }

    private Socket clientOf(Configuration configuration, int upstreamPort) throws IOException {
        mockServer = new MockServer(configuration, upstreamPort, "127.0.0.1", 0);
        Socket client = new Socket("127.0.0.1", mockServer.getLocalPort());
        client.setSoTimeout(20_000);
        client.setTcpNoDelay(true);
        return client;
    }

    @Test
    public void shouldRelayTheGreetingOfAServerThatSpeaksFirstToAClientThatSendsNothing() throws Exception {
        try (GreetingUpstream upstream = new GreetingUpstream();
             Socket client = clientOf(configuration().forwardBinaryServerFirstWaitMillis(WAIT_MILLIS), upstream.port())) {
            assertThat("the client has sent nothing", readLine(client.getInputStream()), is("220 ready\n"));

            send(client, "HELO client\n");
            assertThat(readLine(client.getInputStream()), is("250 HELO client\n"));
            send(client, "NOOP\n");
            assertThat(readLine(client.getInputStream()), is("250 NOOP\n"));
            assertThat("the whole session is on the connection the greeting came on", upstream.connections.get(), is(1));
        }
    }

    @Test
    public void shouldOpenNoUpstreamConnectionForASilentClientByDefault() throws Exception {
        try (GreetingUpstream upstream = new GreetingUpstream();
             Socket client = clientOf(configuration(), upstream.port())) {
            client.setSoTimeout((int) (WAIT_MILLIS * 5));

            assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
            assertThat(upstream.connections.get(), is(0));
            assertStillServedAsHttp(client);
            assertThat(upstream.connections.get(), is(0));
        }
    }

    @Test
    public void shouldNotConnectToAServerFirstTargetThroughAnUnauthenticatedSeparateProxyListener() throws Exception {
        try (GreetingUpstream upstream = new GreetingUpstream();
             Socket client = clientOf(configuration().controlPlanePort(0).forwardBinaryServerFirstWaitMillis(WAIT_MILLIS)
                 .proxyAuthenticationUsername("proxy").proxyAuthenticationPassword("proxy-secret"), upstream.port())) {
            client.setSoTimeout((int) (WAIT_MILLIS * 5));
            assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
            assertThat("the silent unauthenticated client opened no upstream connection", upstream.connections.get(), is(0));
            send(client, "GET / HTTP/1.1\r\nHost: origin.test\r\n\r\n");
            assertThat("HTTP proxy authentication still runs after the wait", readLine(client.getInputStream()), startsWith("HTTP/1.1 407"));
            assertThat(upstream.connections.get(), is(0));
        }
    }

    @Test
    public void shouldStillServeAnHttpClientThatSpeaksAtOnce() throws Exception {
        try (GreetingUpstream upstream = new GreetingUpstream();
             Socket client = clientOf(configuration().forwardBinaryServerFirstWaitMillis(WAIT_MILLIS), upstream.port())) {
            send(client, "PUT /mockserver/status HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");

            assertThat("answered by MockServer", readLine(client.getInputStream()), startsWith("HTTP/1.1 200"));
            Thread.sleep(WAIT_MILLIS * 3);
            assertThat("nothing was relayed", upstream.connections.get(), is(0));
        }
    }

    @Test
    public void shouldRelayTheGreetingThroughATunnelWhenTheOnlyUpstreamProxyIsForwardHttpProxy() throws Exception {
        try (GreetingUpstream upstream = new GreetingUpstream();
             RecordingUpstreamProxy httpProxy = new RecordingUpstreamProxy(RecordingUpstreamProxy.Protocol.CONNECT, 20)) {
            Configuration onlyAnHttpProxy = configuration()
                .forwardBinaryServerFirstWaitMillis(WAIT_MILLIS)
                .forwardHttpProxy(httpProxy.address());
            try (Socket client = clientOf(onlyAnHttpProxy, upstream.port())) {
                assertThat("the client has sent nothing", readLine(client.getInputStream()), is("220 ready\n"));

                send(client, "HELO client\n");
                assertThat(readLine(client.getInputStream()), is("250 HELO client\n"));
                assertThat("one tunnel, through the proxy, to the target", httpProxy.destinations(), contains("127.0.0.1:" + upstream.port()));
                assertThat(upstream.connections.get(), is(1));
            }
        }
    }

    /** A connection left waiting for its client is still detected from what the client then sends. */
    private static void assertStillServedAsHttp(Socket client) throws IOException {
        client.setSoTimeout(20_000);
        send(client, "PUT /mockserver/status HTTP/1.1\r\nHost: localhost\r\nContent-Length: 0\r\n\r\n");
        assertThat("answered by MockServer", readLine(client.getInputStream()), startsWith("HTTP/1.1 200"));
    }

    private static void send(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.UTF_8));
        socket.getOutputStream().flush();
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int read; (read = input.read()) != -1; ) {
            line.write(read);
            if (read == '\n') {
                break;
            }
        }
        return line.toString(StandardCharsets.UTF_8.name());
    }

    /** Greets each connection at once, then answers each line it is sent with that line. */
    private static final class GreetingUpstream implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        private final AtomicInteger connections = new AtomicInteger();

        GreetingUpstream() throws IOException {
            Thread acceptor = new Thread(this::accept, "greeting-upstream");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void accept() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    connections.incrementAndGet();
                    Thread session = new Thread(() -> converse(socket), "greeting-upstream-session");
                    session.setDaemon(true);
                    session.start();
                } catch (IOException closed) {
                    return;
                }
            }
        }

        private static void converse(Socket socket) {
            try (Socket closing = socket) {
                send(closing, "220 ready\n");
                for (String line; !(line = readLine(closing.getInputStream())).isEmpty(); ) {
                    send(closing, "250 " + line);
                }
            } catch (IOException closed) {
                // the session ended
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
        }
    }
}

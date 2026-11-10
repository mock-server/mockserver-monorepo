package org.mockserver.netty.integration.proxy;

import io.netty.buffer.ByteBufUtil;
import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.integration.ClientAndServer;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.integration.proxy.direct.StartTlsUpstream;

import javax.net.ssl.SSLSocket;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.integration.ClientAndServer.startClientAndServer;
import static org.mockserver.model.BinaryRequestDefinition.binaryRequest;
import static org.mockserver.model.BinaryResponse.binaryResponse;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.integration.proxy.direct.StartTlsUpstream.SSL_REQUEST;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;
import static org.mockserver.test.Retries.tryWaitForSuccess;
import static org.mockserver.testing.tls.SSLSocketFactory.sslSocketFactory;

/**
 * A client that uses MockServer as an HTTP CONNECT or SOCKS proxy for a protocol that is not HTTP (a database, a mail
 * server) has its tunnel's bytes relayed to the tunnel's destination as a binary connection, as on a port-forwarded
 * connection: matched against binary expectations, logged, guarded by the forward-target checks, and upgraded to TLS
 * part way through when the protocol does so. HTTP in a tunnel, whatever its method, is still served as HTTP.
 */
public class BinaryThroughTunnelIntegrationTest {

    private static final byte[] STARTUP = {0, 0, 0, 23, 0, 3, 0, 0, 'u', 's', 'e', 'r', 0, 'p', 'o', 's', 't', 'g', 'r', 'e', 's', 0, 0};
    private static final byte[] AUTHENTICATION_OK_AND_READY = {'R', 0, 0, 0, 8, 0, 0, 0, 0, 'Z', 0, 0, 0, 5, 'I'};
    private static final byte[] QUERY = {'Q', 0, 0, 0, 13, 'S', 'E', 'L', 'E', 'C', 'T', ' ', '1', 0};
    private static final byte[] READY = {'Z', 0, 0, 0, 5, 'I'};
    private static final long SERVER_FIRST_WAIT_MILLIS = 200;

    private enum Tunnel {CONNECT, SOCKS5, SOCKS4}

    private ClientAndServer mockServer;
    private final List<Socket> sockets = new ArrayList<>();

    @After
    public void closeSocketsAndStopMockServer() {
        for (Socket socket : sockets) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // closing what a test may already have closed
            }
        }
        stopQuietly(mockServer);
    }

    private Socket tunnel(Tunnel tunnel, String host, int port) throws IOException {
        return tunnel(tunnel, host, port, false);
    }

    private Socket tunnel(Tunnel tunnel, String host, int port, boolean proxyOverTls) throws IOException {
        Socket plain = new Socket("127.0.0.1", mockServer.getLocalPort());
        plain.setSoTimeout(20_000);
        plain.setTcpNoDelay(true);
        sockets.add(plain);
        Socket socket = proxyOverTls ? sslSocketFactory().wrapSocket(plain) : plain;
        sockets.add(socket);
        switch (tunnel) {
            case CONNECT:
                write(socket, ("CONNECT " + host + ":" + port + " HTTP/1.1\r\nHost: " + host + ":" + port + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                assertThat(readHead(socket.getInputStream()), startsWith("HTTP/1.1 200"));
                break;
            case SOCKS5:
                write(socket, new byte[]{5, 1, 0});
                assertThat(Arrays.toString(socket.getInputStream().readNBytes(2)), is(Arrays.toString(new byte[]{5, 0})));
                byte[] hostBytes = host.getBytes(StandardCharsets.US_ASCII);
                ByteArrayOutputStream command = new ByteArrayOutputStream();
                command.write(new byte[]{5, 1, 0, 3, (byte) hostBytes.length}, 0, 5);
                command.write(hostBytes, 0, hostBytes.length);
                command.write(new byte[]{(byte) (port >> 8), (byte) port}, 0, 2);
                write(socket, command.toByteArray());
                byte[] reply = socket.getInputStream().readNBytes(4);
                assertThat("SOCKS5 succeeded", reply[1], is((byte) 0));
                // the bound address that follows: IPv4 (1), a name (3) or IPv6 (4), then a port
                int addressLength = reply[3] == 1 ? 4 : reply[3] == 4 ? 16 : socket.getInputStream().read();
                socket.getInputStream().readNBytes(addressLength + 2);
                break;
            case SOCKS4:
                byte[] address = InetAddress.getByName(host).getAddress();
                write(socket, new byte[]{4, 1, (byte) (port >> 8), (byte) port, address[0], address[1], address[2], address[3], 'u', 0});
                assertThat("SOCKS4 granted", socket.getInputStream().readNBytes(8)[1], is((byte) 0x5a));
                break;
        }
        return socket;
    }

    @Test
    public void shouldRelayAClientFirstBinaryProtocolThroughEachKindOfTunnel() throws Exception {
        for (Tunnel tunnel : Tunnel.values()) {
            try (StartTlsUpstream upstream = postgresLike().answeringSslRequestWith('N')) {
                mockServer = startClientAndServer(configuration());
                Socket client = tunnel(tunnel, "127.0.0.1", upstream.port());

                exchange(client, SSL_REQUEST, new byte[]{'N'});
                exchange(client, STARTUP, AUTHENTICATION_OK_AND_READY);
                exchange(client, QUERY, READY);

                StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
                assertThat(tunnel + ": one upstream connection", upstream.connections().size(), is(1));
                assertThat(tunnel + ": each message reached the destination as sent", connection.messages(), contains(
                    "clear " + ByteBufUtil.hexDump(SSL_REQUEST),
                    "clear " + ByteBufUtil.hexDump(STARTUP),
                    "clear " + ByteBufUtil.hexDump(QUERY)
                ));
            } finally {
                stopQuietly(mockServer);
            }
        }
    }

    @Test
    public void shouldUpgradeATunnelledBinaryConnectionToTlsPartWayThrough() throws Exception {
        for (Tunnel tunnel : new Tunnel[]{Tunnel.CONNECT, Tunnel.SOCKS5}) {
            try (StartTlsUpstream upstream = postgresLike()) {
                mockServer = startClientAndServer(configuration());
                Socket client = tunnel(tunnel, "127.0.0.1", upstream.port());

                exchange(client, SSL_REQUEST, new byte[]{'S'});
                SSLSocket tls = StartTlsUpstream.startTlsAsClient(client, "127.0.0.1", "TLSv1.3", false);
                sockets.add(tls);
                exchange(tls, STARTUP, AUTHENTICATION_OK_AND_READY);
                exchange(tls, QUERY, READY);

                StartTlsUpstream.Connection connection = upstream.awaitConnection(1, 10_000);
                assertThat(tunnel + ": upgraded upstream", connection.upgraded(), is(true));
                assertThat(tunnel.toString(), connection.messages(), contains(
                    "clear " + ByteBufUtil.hexDump(SSL_REQUEST),
                    "TLS " + ByteBufUtil.hexDump(STARTUP),
                    "TLS " + ByteBufUtil.hexDump(QUERY)
                ));
            } finally {
                stopQuietly(mockServer);
            }
        }
    }

    @Test
    public void shouldRelayTheGreetingOfAServerThatSpeaksFirstThroughATunnel() throws Exception {
        for (Tunnel tunnel : new Tunnel[]{Tunnel.CONNECT, Tunnel.SOCKS5}) {
            try (GreetingUpstream upstream = new GreetingUpstream()) {
                mockServer = startClientAndServer(configuration().forwardBinaryServerFirstWaitMillis(SERVER_FIRST_WAIT_MILLIS));
                Socket client = tunnel(tunnel, "127.0.0.1", upstream.port());

                assertThat(tunnel + ": the client has sent nothing", readLine(client.getInputStream()), is("220 ready\n"));
                write(client, "HELO client\n".getBytes(StandardCharsets.US_ASCII));
                assertThat(tunnel.toString(), readLine(client.getInputStream()), is("250 HELO client\n"));
                assertThat(tunnel + ": one upstream connection", upstream.connections.get(), is(1));
            } finally {
                stopQuietly(mockServer);
            }
        }
    }

    @Test
    public void shouldOpenNoUpstreamConnectionForASilentTunnelByDefault() throws Exception {
        try (GreetingUpstream upstream = new GreetingUpstream()) {
            mockServer = startClientAndServer(configuration());
            Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", upstream.port());
            client.setSoTimeout(1_500);

            assertThrows(SocketTimeoutException.class, () -> client.getInputStream().read());
            assertThat(upstream.connections.get(), is(0));

            client.setSoTimeout(20_000);
            write(client, "HELO client\n".getBytes(StandardCharsets.US_ASCII));
            assertThat("the greeting, once the client's bytes opened the connection", readLine(client.getInputStream()), is("220 ready\n"));
            assertThat(readLine(client.getInputStream()), is("250 HELO client\n"));
        }
    }

    @Test
    public void shouldAnswerATunnelledBinaryMessageFromAnExpectationAndLogIt() throws Exception {
        mockServer = startClientAndServer(configuration().logLevel("INFO").matchBinaryExpectationsWhenProxying(true));
        mockServer.upsert(new Expectation(binaryRequest("ping\n".getBytes(StandardCharsets.UTF_8))).thenRespondWithBinary(binaryResponse("pong\n".getBytes(StandardCharsets.UTF_8))));
        // nothing listens at the destination, so only the expectation can answer
        Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", unusedPort());

        write(client, "ping\n".getBytes(StandardCharsets.UTF_8));

        assertThat(readLine(client.getInputStream()), is("pong\n"));
        String pingHex = ByteBufUtil.hexDump("ping\n".getBytes(StandardCharsets.UTF_8));
        tryWaitForSuccess(() -> assertThat("logged as a binary request", Arrays.stream(mockServer.retrieveLogMessagesArray(null))
            .anyMatch(message -> message.contains("received binary request") && message.contains(pingHex)), is(true)));
    }

    @Test
    public void shouldForwardAndLogATunnelledBinaryMessage() throws Exception {
        try (LineEcho upstream = new LineEcho()) {
            mockServer = startClientAndServer(configuration().logLevel("INFO"));
            Socket client = tunnel(Tunnel.SOCKS5, "127.0.0.1", upstream.port());

            write(client, "hello\n".getBytes(StandardCharsets.UTF_8));

            assertThat(readLine(client.getInputStream()), is("hello\n"));
            String helloHex = ByteBufUtil.hexDump("hello\n".getBytes(StandardCharsets.UTF_8));
            tryWaitForSuccess(() -> assertThat("logged as a binary request", Arrays.stream(mockServer.retrieveLogMessagesArray(null))
                .anyMatch(message -> message.contains("received binary request") && message.contains(helloHex)), is(true)));
        }
    }

    @Test
    public void shouldRefuseATunnelledBinaryConnectionToADestinationTheForwardChecksBlock() throws Exception {
        try (LineEcho upstream = new LineEcho()) {
            mockServer = startClientAndServer(configuration().forwardProxyBlockPrivateNetworks(true));
            Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", upstream.port());

            write(client, "hello\n".getBytes(StandardCharsets.UTF_8));

            assertThat("the client's connection is closed", readAfterClose(client), is("closed"));
            assertThat("nothing reached the destination", upstream.connections.get(), is(0));
        }
    }

    @Test
    public void shouldRefuseATunnelledBinaryConnectionBackIntoMockServerItself() throws Exception {
        mockServer = startClientAndServer(configuration().logLevel("INFO"));
        for (Tunnel tunnel : Tunnel.values()) {
            for (String host : tunnel == Tunnel.SOCKS4 ? new String[]{"127.0.0.1"} : new String[]{"127.0.0.1", "localhost"}) {
                Socket client = tunnel(tunnel, host, mockServer.getLocalPort());

                // a SOCKS5 greeting, to open a tunnel inside this one
                write(client, new byte[]{5, 1, 0});

                assertThat(tunnel + " to " + host + ": the client's connection is closed", readAfterClose(client), is("closed"));
            }
        }
        tryWaitForSuccess(() -> assertThat("logged as refused", Arrays.stream(mockServer.retrieveLogMessagesArray(null))
            .filter(message -> message.contains("cannot be forwarded") && message.contains("MockServer's own listeners")).count(), is(5L)));
    }

    @Test
    public void shouldStillRelayTunnelledBinaryToAnotherMockServer() throws Exception {
        ClientAndServer other = startClientAndServer(configuration());
        try {
            other.upsert(new Expectation(binaryRequest("ping\n".getBytes(StandardCharsets.UTF_8))).thenRespondWithBinary(binaryResponse("pong\n".getBytes(StandardCharsets.UTF_8))));
            mockServer = startClientAndServer(configuration());
            for (Tunnel tunnel : Tunnel.values()) {
                Socket client = tunnel(tunnel, "127.0.0.1", other.getLocalPort());

                write(client, "ping\n".getBytes(StandardCharsets.UTF_8));

                assertThat(tunnel.toString(), readLine(client.getInputStream()), is("pong\n"));
            }
        } finally {
            stopQuietly(other);
        }
    }

    @Test
    public void shouldNeverLookAgainAtTunnelledBytesThatStartLikeAnotherProtocol() throws Exception {
        try (LineEcho upstream = new LineEcho(); LineEcho decoy = new LineEcho()) {
            mockServer = startClientAndServer(configuration());
            List<byte[]> messages = startingLikeAnotherProtocol(decoy.port());
            for (Tunnel tunnel : new Tunnel[]{Tunnel.CONNECT, Tunnel.SOCKS5}) {
                for (byte[] message : messages) {
                    Socket client = tunnel(tunnel, "127.0.0.1", upstream.port());

                    write(client, message);

                    assertThat(tunnel + ": " + ByteBufUtil.hexDump(message), readLine(client.getInputStream()), is(new String(message, StandardCharsets.UTF_8)));
                }
            }
            assertThat("each tunnel reached its own destination", upstream.connections.get(), is(2 * messages.size()));
            assertThat("none was re-targeted", decoy.connections.get(), is(0));
        }
    }

    @Test
    public void shouldNeverLookAgainAtBytesSentAfterTheGreetingOfAServerThatSpeaksFirst() throws Exception {
        try (GreetingUpstream upstream = new GreetingUpstream(); LineEcho decoy = new LineEcho()) {
            mockServer = startClientAndServer(configuration().forwardBinaryServerFirstWaitMillis(SERVER_FIRST_WAIT_MILLIS));
            List<byte[]> messages = startingLikeAnotherProtocol(decoy.port());
            for (Tunnel tunnel : new Tunnel[]{Tunnel.CONNECT, Tunnel.SOCKS5}) {
                for (byte[] message : messages) {
                    Socket client = tunnel(tunnel, "127.0.0.1", upstream.port());
                    assertThat(tunnel + ": the client has sent nothing", readLine(client.getInputStream()), is("220 ready\n"));

                    write(client, message);

                    assertThat(tunnel + ": " + ByteBufUtil.hexDump(message), readLine(client.getInputStream()), is("250 " + new String(message, StandardCharsets.UTF_8)));
                }
            }
            assertThat("none was re-targeted", decoy.connections.get(), is(0));
        }
    }

    /** Lines a tunnel's client may send that start like the preamble, a nested SOCKS5 greeting or a SOCKS4 request. */
    private static List<byte[]> startingLikeAnotherProtocol(int decoyPort) {
        return Arrays.asList(
            ("PROXIED_127.0.0.1:" + decoyPort + "\n").getBytes(StandardCharsets.US_ASCII),
            ("PROXIED_SECURE_127.0.0.1:" + decoyPort + "\n").getBytes(StandardCharsets.US_ASCII),
            "PROXIED_RESPONSE_127.0.0.1\n".getBytes(StandardCharsets.US_ASCII),
            new byte[]{5, 1, 0, '\n'},
            new byte[]{4, 1, 0, 80, 127, 0, 0, 1, 'u', 0, '\n'}
        );
    }

    @Test
    public void shouldRelayTunnelledBinaryInTheClearWhenTheProxyConnectionIsTls() throws Exception {
        try (LineEcho upstream = new LineEcho()) {
            mockServer = startClientAndServer(configuration());
            Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", upstream.port(), true);

            write(client, "hello\n".getBytes(StandardCharsets.UTF_8));

            assertThat(readLine(client.getInputStream()), is("hello\n"));
            assertThat("the destination got the client's bytes as they were", upstream.received(), is("hello\n"));
        }
    }

    @Test
    public void shouldStillRelayHttpWithAnUnusualMethodThroughATunnel() throws Exception {
        try (HttpLikeUpstream upstream = new HttpLikeUpstream()) {
            mockServer = startClientAndServer(configuration());
            for (Tunnel tunnel : Tunnel.values()) {
                Socket client = tunnel(tunnel, "127.0.0.1", upstream.port());

                write(client, "PROPFIND /dav HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                assertThat(tunnel.toString(), readHead(client.getInputStream()), startsWith("HTTP/1.1 207"));
            }
            assertThat(upstream.requestLines, contains("PROPFIND /dav HTTP/1.1\r\n", "PROPFIND /dav HTTP/1.1\r\n", "PROPFIND /dav HTTP/1.1\r\n"));
        }
    }

    @Test
    public void shouldStillServeHttpWhoseRequestLineArrivesSlowly() throws Exception {
        mockServer = startClientAndServer(configuration());
        mockServer.when(request().withPath("/plain")).respond(response().withBody("plain"));
        Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", unusedPort());

        write(client, "GET /pl".getBytes(StandardCharsets.US_ASCII));
        Thread.sleep(1_500);
        write(client, "ain HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

        assertThat(readHead(client.getInputStream()), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void shouldStillServeHttpThroughATunnelWithTheServerFirstWaitOn() throws Exception {
        // long enough that a loaded machine still sends the request before it ends
        mockServer = startClientAndServer(configuration().forwardBinaryServerFirstWaitMillis(5_000L));
        mockServer.when(request().withPath("/plain")).respond(response().withBody("plain"));
        Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", unusedPort());

        write(client, "GET /plain HTTP/1.1\r\nHost: 127.0.0.1\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

        assertThat(readHead(client.getInputStream()), startsWith("HTTP/1.1 200"));
    }

    @Test
    public void shouldTakeEveryTunnelledByteAsHttpWhenAssumeAllRequestsAreHttp() throws Exception {
        try (LineEcho upstream = new LineEcho()) {
            mockServer = startClientAndServer(configuration().assumeAllRequestsAreHttp(true));
            Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", upstream.port());

            write(client, "hello\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

            assertThat(readHead(client.getInputStream()), startsWith("HTTP/1.1 400"));
            assertThat(upstream.connections.get(), is(0));
        }
    }

    @Test
    public void shouldKeepASilentTunnelledBinaryConnectionPastTheIdleTimeout() throws Exception {
        try (LineEcho upstream = new LineEcho()) {
            mockServer = startClientAndServer(configuration().inboundConnectionIdleTimeoutMillis(300L));
            Socket client = tunnel(Tunnel.CONNECT, "127.0.0.1", upstream.port());

            write(client, "first\n".getBytes(StandardCharsets.UTF_8));
            assertThat(readLine(client.getInputStream()), is("first\n"));
            Thread.sleep(1_200);
            write(client, "second\n".getBytes(StandardCharsets.UTF_8));

            assertThat(readLine(client.getInputStream()), is("second\n"));
        }
    }

    private static StartTlsUpstream postgresLike() throws IOException {
        return new StartTlsUpstream()
            .answering(STARTUP, AUTHENTICATION_OK_AND_READY)
            .answering(QUERY, READY);
    }

    private static int unusedPort() {
        return CLOSED_PORT;
    }

    private static void write(Socket socket, byte[] bytes) throws IOException {
        socket.getOutputStream().write(bytes);
        socket.getOutputStream().flush();
    }

    private static void exchange(Socket socket, byte[] message, byte[] expectedReply) throws IOException {
        write(socket, message);
        assertThat("reply to " + ByteBufUtil.hexDump(message), ByteBufUtil.hexDump(socket.getInputStream().readNBytes(expectedReply.length)), is(ByteBufUtil.hexDump(expectedReply)));
    }

    /** What a refused or closed connection gives a reader: an end of stream or an error. */
    private static String readAfterClose(Socket socket) {
        try {
            int read = socket.getInputStream().read();
            return read == -1 ? "closed" : "read " + read;
        } catch (IOException closed) {
            return "closed";
        }
    }

    private static String readHead(InputStream input) throws IOException {
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        for (int read; (read = input.read()) != -1; ) {
            head.write(read);
            if (head.toString(StandardCharsets.US_ASCII.name()).endsWith("\r\n\r\n")) {
                break;
            }
        }
        return head.toString(StandardCharsets.US_ASCII.name());
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

    /** Answers each connection's lines, one at a time, in a thread of its own per connection. */
    private abstract static class LineServer implements AutoCloseable {
        private final ServerSocket serverSocket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
        final AtomicInteger connections = new AtomicInteger();
        private final List<Socket> accepted = new CopyOnWriteArrayList<>();

        LineServer() throws IOException {
            Thread acceptor = new Thread(() -> {
                while (!serverSocket.isClosed()) {
                    try {
                        Socket socket = serverSocket.accept();
                        connections.incrementAndGet();
                        accepted.add(socket);
                        Thread session = new Thread(() -> {
                            try (Socket closing = socket) {
                                converse(closing);
                            } catch (IOException closed) {
                                // the session ended
                            }
                        }, "line-server-session");
                        session.setDaemon(true);
                        session.start();
                    } catch (IOException closed) {
                        return;
                    }
                }
            }, "line-server-accept");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        abstract void converse(Socket socket) throws IOException;

        int port() {
            return serverSocket.getLocalPort();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket socket : accepted) {
                socket.close();
            }
        }
    }

    /** Echoes each line, and keeps what it was sent. */
    private static final class LineEcho extends LineServer {
        private final StringBuffer received = new StringBuffer();

        LineEcho() throws IOException {
            super();
        }

        @Override
        void converse(Socket socket) throws IOException {
            for (String line; !(line = readLine(socket.getInputStream())).isEmpty(); ) {
                received.append(line);
                write(socket, line.getBytes(StandardCharsets.UTF_8));
            }
        }

        String received() {
            return received.toString();
        }
    }

    /** Answers each HTTP request head with {@code 207}, and keeps each request line. */
    private static final class HttpLikeUpstream extends LineServer {
        private final List<String> requestLines = new CopyOnWriteArrayList<>();

        HttpLikeUpstream() throws IOException {
            super();
        }

        @Override
        void converse(Socket socket) throws IOException {
            for (String line; !(line = readLine(socket.getInputStream())).isEmpty(); ) {
                requestLines.add(line);
                String header;
                do {
                    header = readLine(socket.getInputStream());
                } while (!header.isEmpty() && !header.equals("\r\n"));
                write(socket, "HTTP/1.1 207 Multi-Status\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            }
        }
    }

    /** Greets each connection at once, then answers each line with that line. */
    private static final class GreetingUpstream extends LineServer {
        GreetingUpstream() throws IOException {
            super();
        }

        @Override
        void converse(Socket socket) throws IOException {
            write(socket, "220 ready\n".getBytes(StandardCharsets.US_ASCII));
            for (String line; !(line = readLine(socket.getInputStream())).isEmpty(); ) {
                write(socket, ("250 " + line).getBytes(StandardCharsets.US_ASCII));
            }
        }
    }
}

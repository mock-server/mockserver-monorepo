package org.mockserver.netty.integration.proxy.direct;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.tls.KeyStoreFactory;
import org.slf4j.event.Level;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;
import static org.mockserver.test.Retries.tryWaitForSuccess;

/**
 * A proxied binary connection has one upstream connection for its life, over real sockets: a session's messages
 * reach one upstream connection, whatever the upstream sends comes back, and either end closing closes the other.
 */
public class BinaryPersistentRelayIntegrationTest {

    private static final byte[] SSL_REQUEST = {0, 0, 0, 8, 4, (byte) 0xd2, 0x16, 0x2f};

    private final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private MockServer mockServer;

    @After
    public void stopMockServer() {
        MockServerLogger.setGlobalLogEventListener(null);
        stopQuietly(mockServer);
    }

    private static Configuration byDefault() {
        return configuration().logLevel("INFO");
    }

    private Socket clientOf(Configuration configuration, int upstreamPort) throws IOException {
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        mockServer = new MockServer(configuration, upstreamPort, "127.0.0.1", 0);
        return connect();
    }

    private Socket connect() throws IOException {
        Socket client = new Socket("127.0.0.1", mockServer.getLocalPort());
        client.setSoTimeout(20_000);
        client.setTcpNoDelay(true);
        return client;
    }

    private static void send(Socket socket, String line) throws IOException {
        socket.getOutputStream().write(line.getBytes(StandardCharsets.UTF_8));
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

    /** What was logged at WARN about a binary connection; a server logs other things too. */
    private List<LogEntry> warnings() {
        return logged.stream()
            .filter(entry -> entry.getLogLevel() == Level.WARN && String.valueOf(entry.getMessageFormat()).contains("binary connection"))
            .collect(Collectors.toList());
    }

    @Test
    public void shouldCarryAWholeSessionOnOneUpstreamConnection() throws Exception {
        try (Upstream upstream = new Upstream(Upstream::numberEachLineOfTheConnection);
             Socket client = clientOf(byDefault(), upstream.port())) {
            for (int message = 1; message <= 5; message++) {
                send(client, "message\n");
                assertThat("the upstream's count of this connection's messages", readLine(client.getInputStream()), is(message + ":message\n"));
            }

            assertThat(upstream.connections(), is(1));
        }
    }

    @Test
    public void shouldRelayWhatTheUpstreamSendsWithoutBeingAsked() throws Exception {
        CountDownLatch notify = new CountDownLatch(1);
        try (Upstream upstream = new Upstream((socket, received) -> {
            OutputStream output = socket.getOutputStream();
            readLine(socket.getInputStream());
            output.write("ready\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            notify.await();
            output.write("notification\n".getBytes(StandardCharsets.UTF_8));
            output.flush();
            readLine(socket.getInputStream());
        }); Socket client = clientOf(byDefault(), upstream.port())) {
            send(client, "listen\n");
            assertThat(readLine(client.getInputStream()), is("ready\n"));

            // the client sends nothing more
            notify.countDown();

            assertThat(readLine(client.getInputStream()), is("notification\n"));
        }
    }

    @Test
    public void shouldRelayMoreThanOneReadEachWayWholeAndInOrder() throws Exception {
        int length = 2 * 1024 * 1024;
        try (Upstream upstream = new Upstream(Upstream::echo);
             Socket client = clientOf(byDefault(), upstream.port())) {
            AtomicReference<Throwable> writeFailure = new AtomicReference<>();
            Thread writer = new Thread(() -> {
                try {
                    OutputStream output = client.getOutputStream();
                    byte[] chunk = new byte[70_000];
                    for (int sent = 0; sent < length; ) {
                        int size = Math.min(chunk.length, length - sent);
                        for (int i = 0; i < size; i++) {
                            chunk[i] = byteAt(sent + i);
                        }
                        output.write(chunk, 0, size);
                        sent += size;
                    }
                    output.flush();
                } catch (Throwable throwable) {
                    writeFailure.set(throwable);
                }
            }, "relay-client-writer");
            writer.setDaemon(true);
            writer.start();

            InputStream input = client.getInputStream();
            byte[] buffer = new byte[64 * 1024];
            int received = 0;
            int firstByteOutOfOrder = -1;
            while (received < length) {
                int read = input.read(buffer);
                assertThat("the connection stays open until everything is back, got " + received, read, is(not(-1)));
                for (int i = 0; i < read && firstByteOutOfOrder < 0; i++) {
                    if (buffer[i] != byteAt(received + i)) {
                        firstByteOutOfOrder = received + i;
                    }
                }
                received += read;
            }

            writer.join(TimeUnit.SECONDS.toMillis(20));
            assertThat(writeFailure.get(), is(nullValue()));
            assertThat("every byte came back in the order it was sent", firstByteOutOfOrder, is(-1));
            assertThat(upstream.connections(), is(1));
        }
    }

    private static byte byteAt(int position) {
        return (byte) (position * 31 + (position >>> 11));
    }

    @Test
    public void shouldCloseTheClientWhenTheUpstreamClosesAfterWhatItSent() throws Exception {
        try (Upstream upstream = new Upstream((socket, received) -> {
            readLine(socket.getInputStream());
            socket.getOutputStream().write("goodbye\n".getBytes(StandardCharsets.UTF_8));
            socket.close();
        }); Socket client = clientOf(byDefault(), upstream.port())) {
            send(client, "quit\n");

            assertThat(readLine(client.getInputStream()), is("goodbye\n"));
            assertThat("then the client's connection is closed", client.getInputStream().read(), is(-1));
            assertThat("an upstream that closes is not an error", warnings(), hasSize(0));
        }
    }

    @Test
    public void shouldDeliverTheLastMessageOfAClientThatClosesStraightAfterSendingIt() throws Exception {
        try (Upstream upstream = new Upstream(Upstream::readUntilClosed)) {
            try (Socket client = clientOf(byDefault(), upstream.port())) {
                send(client, "terminate\n");
            }

            tryWaitForSuccess(() -> {
                assertThat(upstream.receivedText(), is("terminate\n"));
                assertThat("and then the upstream connection is ended", upstream.endOfStreamSeen(), is(true));
            });
            assertThat(upstream.connections(), is(1));
        }
    }

    @Test
    public void shouldCloseTheUpstreamConnectionWhenTheServerStops() throws Exception {
        try (Upstream upstream = new Upstream(Upstream::numberEachLineOfTheConnection);
             Socket client = clientOf(byDefault(), upstream.port())) {
            send(client, "message\n");
            assertThat(readLine(client.getInputStream()), is("1:message\n"));

            mockServer.stop();

            tryWaitForSuccess(() -> assertThat(upstream.connectionsEnded(), is(1)));
        }
    }

    /**
     * Bytes that could be the start of a TLS handshake are held until more arrive; a client that closes straight
     * after sending them still has them delivered, before the upstream connection is ended.
     */
    @Test
    public void shouldDeliverAHeldLastMessageOfAClientThatClosesStraightAfterSendingIt() throws Exception {
        assertThat(sendAFirstMessageThenThisAndClose(new byte[]{22, 3, 1}), is("a first message\n\u0016\u0003\u0001"));
    }

    @Test
    public void shouldDeliverAPlainLastMessageOfAClientThatClosesStraightAfterSendingIt() throws Exception {
        assertThat(sendAFirstMessageThenThisAndClose("last\n".getBytes(StandardCharsets.ISO_8859_1)), is("a first message\nlast\n"));
    }

    private String sendAFirstMessageThenThisAndClose(byte[] last) throws Exception {
        try (Upstream upstream = new Upstream(Upstream::readUntilClosed)) {
            try (Socket client = clientOf(byDefault(), upstream.port())) {
                send(client, "a first message\n");
                tryWaitForSuccess(() -> assertThat(upstream.receivedText(), is("a first message\n")));
                client.getOutputStream().write(last);
                client.getOutputStream().flush();
            }
            tryWaitForSuccess(() -> assertThat("the upstream connection is ended", upstream.endOfStreamSeen(), is(true)));
            assertThat(upstream.connections(), is(1));
            return new String(upstream.received(), StandardCharsets.ISO_8859_1);
        }
    }

    /** A connection is binary for good: a later message that looks like another protocol is relayed as it is. */
    @Test
    public void shouldRelayALaterMessageThatLooksLikeAnotherProtocolUnchanged() throws Exception {
        String likeHttp = "GET /some/path HTTP/1.1\r\nHost: example.com\r\n\r\n";
        String likeATlsRecord = "\u0016\u0003\u0001\u0000\u0005hello";
        try (Upstream upstream = new Upstream(Upstream::readUntilClosed);
             Socket client = clientOf(byDefault(), upstream.port())) {
            send(client, "a first message\n");
            tryWaitForSuccess(() -> assertThat(upstream.receivedText(), is("a first message\n")));

            client.getOutputStream().write(likeATlsRecord.getBytes(StandardCharsets.ISO_8859_1));
            client.getOutputStream().flush();
            tryWaitForSuccess(() -> assertThat(new String(upstream.received(), StandardCharsets.ISO_8859_1), is("a first message\n" + likeATlsRecord)));
            send(client, likeHttp);
            tryWaitForSuccess(() -> assertThat(new String(upstream.received(), StandardCharsets.ISO_8859_1), is("a first message\n" + likeATlsRecord + likeHttp)));

            assertThat(upstream.connections(), is(1));
            assertThat(warnings(), hasSize(0));
        }
    }

    @Test
    public void shouldCloseTheClientAndSayWhyWhenTheUpstreamRefusesTheConnection() throws Exception {
        List<String> responses = new CopyOnWriteArrayList<>();
        Configuration configuration = byDefault()
            .binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) ->
                binaryResponse.whenComplete((response, failure) -> responses.add(failure != null ? "failed: " + failure.getClass().getSimpleName() : "completed: " + response)));
        try (Socket client = clientOf(configuration, CLOSED_PORT)) {
            send(client, "hello\n");

            assertThat(client.getInputStream().read(), is(-1));
            tryWaitForSuccess(() -> {
                assertThat(warnings(), hasSize(1));
                assertThat(warnings().get(0).getMessageFormat(), containsString("unable to connect to:{}"));
                assertThat("a message that was never sent has no response to wait for", responses, hasSize(1));
                assertThat(responses.get(0), containsString("failed: "));
            });
        }
    }

    @Test
    public void shouldTellTheListenerOfEachMessageInOrderWithTheUpstreamsAnswer() throws Exception {
        List<String> reported = new CopyOnWriteArrayList<>();
        try (Upstream upstream = new Upstream(Upstream::numberEachLineOfTheConnection)) {
            Configuration configuration = byDefault()
                .binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> {
                    String answer;
                    try {
                        // the listener may wait: it is not called on the connection's own thread
                        answer = new String(binaryResponse.get(20, TimeUnit.SECONDS).getBytes(), StandardCharsets.UTF_8);
                    } catch (Exception exception) {
                        answer = exception.toString();
                    }
                    reported.add(new String(binaryRequest.getBytes(), StandardCharsets.UTF_8) + "->" + answer + "@" + ((InetSocketAddress) serverAddress).getPort());
                });
            try (Socket client = clientOf(configuration, upstream.port())) {
                send(client, "one\n");
                assertThat(readLine(client.getInputStream()), is("1:one\n"));
                send(client, "two\n");
                assertThat(readLine(client.getInputStream()), is("2:two\n"));

                tryWaitForSuccess(() -> assertThat(reported, contains(
                    "one\n->1:one\n@" + upstream.port(),
                    "two\n->2:two\n@" + upstream.port()
                )));
            }
        }
    }

    @Test
    public void shouldLogWhatTheUpstreamSendsAgainstTheMessageItFollows() throws Exception {
        try (Upstream upstream = new Upstream(Upstream::numberEachLineOfTheConnection);
             Socket client = clientOf(byDefault(), upstream.port())) {
            send(client, "one\n");
            assertThat(readLine(client.getInputStream()), is("1:one\n"));

            tryWaitForSuccess(() -> {
                List<LogEntry> received = logged.stream().filter(entry -> entry.getType() == RECEIVED_REQUEST).collect(Collectors.toList());
                List<LogEntry> returned = logged.stream().filter(entry -> entry.getType() == FORWARDED_REQUEST).collect(Collectors.toList());
                assertThat(received, hasSize(1));
                assertThat(returned, hasSize(1));
                assertThat(returned.get(0).getMessageFormat(), is("returning binary response:{}from:{}for forwarded binary request:{}"));
                assertThat(returned.get(0).getCorrelationId(), is(received.get(0).getCorrelationId()));
            });
        }
    }

    @Test
    public void shouldCloseTheConnectionWhenTheUpstreamIsAnAddressThatMayNotBeForwardedTo() throws Exception {
        try (Upstream upstream = new Upstream(Upstream::readUntilClosed);
             Socket client = clientOf(byDefault().forwardProxyBlockPrivateNetworks(true), upstream.port())) {
            send(client, "hello\n");

            assertThat(client.getInputStream().read(), is(-1));
            assertThat(upstream.connections(), is(0));
            tryWaitForSuccess(() -> {
                assertThat(warnings(), hasSize(1));
                assertThat((String) warnings().get(0).getArguments()[2], containsString("Forward to loopback address blocked"));
            });
        }
    }

    /**
     * What a client sends after turning TLS on goes on the same upstream connection, after MockServer's own TLS
     * handshake with the upstream, never in the clear. This upstream does not speak TLS, so it sees a handshake
     * begin after the {@code SSLRequest}, and nothing else. (Upstreams that answer it are in
     * {@code BinaryInBandTlsUpgradeProxyIntegrationTest}.)
     */
    @Test
    public void shouldNeverSendUpstreamInTheClearWhatAClientSendsAfterTurningTlsOn() throws Exception {
        String sentOverTls = "secret startup message";
        try (Upstream upstream = new Upstream((socket, received) -> {
            InputStream input = socket.getInputStream();
            byte[] first = input.readNBytes(SSL_REQUEST.length);
            received.write(first);
            if (Arrays.equals(first, SSL_REQUEST)) {
                socket.getOutputStream().write('S');
                socket.getOutputStream().flush();
            }
            Upstream.readUntilClosed(socket, received);
        }); Socket client = clientOf(byDefault(), upstream.port())) {
            client.getOutputStream().write(SSL_REQUEST);
            client.getOutputStream().flush();
            assertThat("the upstream's go-ahead is relayed", client.getInputStream().read(), is((int) 'S'));

            SSLSocket tls = startTls(client);
            tls.getOutputStream().write(sentOverTls.getBytes(StandardCharsets.UTF_8));
            tls.getOutputStream().flush();

            tryWaitForSuccess(() -> assertThat("a TLS handshake follows on the same connection", upstream.receivedBy(0).length > SSL_REQUEST.length && upstream.receivedBy(0)[SSL_REQUEST.length] == 22, is(true)));
            assertThat("and no other connection is opened", upstream.connections(), is(1));
            assertThat(new String(upstream.received(), StandardCharsets.ISO_8859_1), not(containsString(sentOverTls)));
        }
    }

    @Test
    public void shouldForwardTheNextMessageWhileTheListenerStillWaitsOnTheFirst() throws Exception {
        CountDownLatch listenerCalled = new CountDownLatch(1);
        CountDownLatch listenerMayReturn = new CountDownLatch(1);
        List<String> reported = new CopyOnWriteArrayList<>();
        AtomicInteger callsInProgress = new AtomicInteger();
        AtomicInteger mostCallsInProgress = new AtomicInteger();
        try (Upstream upstream = new Upstream(Upstream::readUntilClosed)) {
            Configuration configuration = byDefault()
                .binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> {
                    mostCallsInProgress.accumulateAndGet(callsInProgress.incrementAndGet(), Math::max);
                    reported.add(new String(binaryRequest.getBytes(), StandardCharsets.UTF_8));
                    listenerCalled.countDown();
                    try {
                        listenerMayReturn.await();
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                    } finally {
                        callsInProgress.decrementAndGet();
                    }
                });
            try (Socket client = clientOf(configuration, upstream.port())) {
                send(client, "first message\n");
                assertThat("the listener is called for the first message", listenerCalled.await(10, TimeUnit.SECONDS), is(true));

                // the listener has not returned, as one waiting for a response that never comes would not
                send(client, "second message\n");
                tryWaitForSuccess(() -> assertThat(upstream.receivedText(), is("first message\nsecond message\n")));

                listenerMayReturn.countDown();
                tryWaitForSuccess(() -> assertThat(reported, contains("first message\n", "second message\n")));
                assertThat("one connection's messages are reported one at a time", mostCallsInProgress.get(), is(1));
                assertThat(upstream.connections(), is(1));
            }
        } finally {
            listenerMayReturn.countDown();
        }
    }

    @Test
    public void shouldCloseBothConnectionsWhenTheListenerThrows() throws Exception {
        try (Upstream upstream = new Upstream(Upstream::readUntilClosed)) {
            Configuration configuration = byDefault()
                .binaryProxyListener((binaryRequest, binaryResponse, serverAddress, clientAddress) -> {
                    throw new IllegalStateException("the listener failed");
                });
            try (Socket client = clientOf(configuration, upstream.port())) {
                send(client, "hello\n");

                assertThat("MockServer closes the client's connection", client.getInputStream().read(), is(-1));
                tryWaitForSuccess(() -> assertThat("and the upstream's", upstream.endOfStreamSeen(), is(true)));
            }
        }
    }

    /** Starts TLS over a connection already in use, trusting MockServer's certificate authority. */
    private static SSLSocket startTls(Socket socket) throws Exception {
        KeyStore keyStore = new KeyStoreFactory(configuration(), new MockServerLogger()).loadOrCreateKeyStore();
        KeyStore trusted = KeyStore.getInstance(KeyStore.getDefaultType());
        trusted.load(null, null);
        trusted.setCertificateEntry("mockserver-ca", keyStore.getCertificate(KeyStoreFactory.KEY_STORE_CA_ALIAS));
        TrustManagerFactory trust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trust.init(trusted);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(null, trust.getTrustManagers(), null);
        SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(socket, "127.0.0.1", socket.getPort(), true);
        tls.setUseClientMode(true);
        tls.startHandshake();
        return tls;
    }

    /**
     * An upstream on 127.0.0.1 that serves each connection as the test says, and keeps what each one sent.
     */
    private static final class Upstream implements AutoCloseable {

        interface Behaviour {
            void serve(Socket socket, ByteArrayOutputStream received) throws Exception;
        }

        private final ServerSocket serverSocket;
        private final Behaviour behaviour;
        private final List<Socket> sockets = new CopyOnWriteArrayList<>();
        private final List<ByteArrayOutputStream> receivedByConnection = new CopyOnWriteArrayList<>();
        private final AtomicBoolean servedToTheEnd = new AtomicBoolean();
        private final AtomicInteger connectionsEnded = new AtomicInteger();

        Upstream(Behaviour behaviour) throws IOException {
            this.behaviour = behaviour;
            serverSocket = new ServerSocket(0, 50, InetAddress.getByAddress(new byte[]{127, 0, 0, 1}));
            Thread accept = new Thread(this::acceptConnections, "relay-upstream-accept");
            accept.setDaemon(true);
            accept.start();
        }

        private void acceptConnections() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket socket = serverSocket.accept();
                    ByteArrayOutputStream received = new ByteArrayOutputStream();
                    receivedByConnection.add(received);
                    sockets.add(socket);
                    Thread serve = new Thread(() -> {
                        try (Socket served = socket) {
                            behaviour.serve(served, received);
                            servedToTheEnd.set(true);
                        } catch (Exception closed) {
                            // MockServer, or close(), ended the connection
                        } finally {
                            connectionsEnded.incrementAndGet();
                        }
                    }, "relay-upstream-serve");
                    serve.setDaemon(true);
                    serve.start();
                } catch (IOException closed) {
                    // close() ends the accept
                }
            }
        }

        /** Answers each line with its position among the lines of its connection, so a session is seen to be one. */
        static void numberEachLineOfTheConnection(Socket socket, ByteArrayOutputStream received) throws IOException {
            int lines = 0;
            for (String line; !(line = readLine(socket.getInputStream())).isEmpty(); ) {
                socket.getOutputStream().write((++lines + ":" + line).getBytes(StandardCharsets.UTF_8));
                socket.getOutputStream().flush();
            }
        }

        static void echo(Socket socket, ByteArrayOutputStream received) throws IOException {
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = socket.getInputStream().read(buffer)) != -1; ) {
                socket.getOutputStream().write(buffer, 0, read);
            }
        }

        /** Returns only once the end of the stream has been read. */
        static void readUntilClosed(Socket socket, ByteArrayOutputStream received) throws IOException {
            byte[] buffer = new byte[16 * 1024];
            for (int read; (read = socket.getInputStream().read(buffer)) != -1; ) {
                received.write(buffer, 0, read);
            }
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        int connections() {
            return sockets.size();
        }

        /** Connections whose behaviour has returned, because the other end closed or reset them. */
        int connectionsEnded() {
            return connectionsEnded.get();
        }

        byte[] receivedBy(int connection) {
            return receivedByConnection.get(connection).toByteArray();
        }

        byte[] received() {
            ByteArrayOutputStream all = new ByteArrayOutputStream();
            for (ByteArrayOutputStream received : receivedByConnection) {
                byte[] bytes = received.toByteArray();
                all.write(bytes, 0, bytes.length);
            }
            return all.toByteArray();
        }

        String receivedText() {
            return new String(received(), StandardCharsets.UTF_8);
        }

        /** Whether a connection's behaviour ran to its end, which for {@link #readUntilClosed} is the end of the stream. */
        boolean endOfStreamSeen() {
            return servedToTheEnd.get();
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket socket : sockets) {
                socket.close();
            }
        }
    }
}

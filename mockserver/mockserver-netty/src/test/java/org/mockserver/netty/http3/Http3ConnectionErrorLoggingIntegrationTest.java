package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.http3.Http3Exception;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamType;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.NettyLogCapture;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;
import org.slf4j.event.Level;

import javax.net.ssl.TrustManagerFactory;
import java.net.InetSocketAddress;
import java.security.KeyStore;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * What MockServer logs when an HTTP/3 connection fails, or one of the streams Netty's codec keeps for itself (the
 * control stream and the QPACK streams): one entry in MockServer's own log at a level that fits the cause, and
 * nothing through Netty's logger, which reports whatever reaches the end of a pipeline at {@code WARN} with a stack
 * trace. The connection meets the same end as before. Needs the QUIC native library, and fails without it.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ConnectionErrorLoggingIntegrationTest {

    private static final long WAIT_SECONDS = 15;
    // a stream type RFC 9114 section 6.2.3 reserves for no use, so the server reads and drops what follows
    private static final byte RESERVED_STREAM_TYPE = 0x21;
    private static final byte CONTROL_STREAM_TYPE = 0x00;
    private static final byte SETTINGS_FRAME_TYPE = 0x04;
    private static final byte ENABLE_CONNECT_PROTOCOL = 0x08;
    // PRIORITY in HTTP/2, one of the frame types RFC 9114 section 7.2.8 reserves so that they are never sent
    private static final byte HTTP2_PRIORITY_FRAME_TYPE = 0x02;

    private static final List<LogEntry> logged = new CopyOnWriteArrayList<>();
    private static NettyLogCapture nettysLog;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static NioEventLoopGroup clientGroup;

    private int nettysLogBeforeThisTest;

    @BeforeClass
    public static void startServer() throws Exception {
        nettysLog = new NettyLogCapture(NettyLogCapture.PIPELINE);
        clientGroup = new NioEventLoopGroup(2);
        nettysLog.ignoreThreadsOf(clientGroup);
        // a clone: the event log clears the entry it is handed once it has copied it
        MockServerLogger.setGlobalLogEventListener(logEntry -> logged.add(logEntry.clone()));
        // the test JVM defaults to ERROR, which would drop the entries this class asserts on
        mockServer = startWithHttp3(configuration().http3MaxIdleTimeout(30000L).logLevel("DEBUG"));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        nettysLog.attach();
    }

    @AfterClass
    public static void stopServer() {
        try {
            stopQuietly(mockServerClient);
            stopQuietly(mockServer);
            List<String> loggedByNettyWhileThisClassRan = nettysLog.all();
            nettysLog.assertThatItSeesWhatNettyLogs();
            assertThat("logged by Netty while this class ran", loggedByNettyWhileThisClassRan, empty());
        } finally {
            MockServerLogger.setGlobalLogEventListener(null);
            nettysLog.close();
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServer() {
        logged.clear();
        nettysLogBeforeThisTest = nettysLog.size();
        mockServerClient.reset();
        mockServerClient.when(request().withPath("/served")).respond(response().withBody("served"));
    }

    @Test
    public void shouldLogAClientAddresssFirstFailedHandshakeAsAWarningAndItsLaterOnesAtDebug() throws Exception {
        // the only test of this class whose handshakes fail, so the first is the first from this address
        TrustManagerFactory jdkDefaultTrust = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        jdkDefaultTrust.init((KeyStore) null);
        try (RawQuicClient client = new RawQuicClient(jdkDefaultTrust, Http3.supportedApplicationProtocols())) {
            assertThrows("the client does not trust MockServer's Certificate Authority", ExecutionException.class, client::connect);

            LogEntry entry = theOnlyEntryOf(client);
            assertThat(entry.getLogLevel(), is(Level.WARN));
            String message = entry.getMessage(configuration());
            assertThat(message, containsString("TLS handshake failed on HTTP/3 connection from"));
            assertThat(message, containsString("the client closed the connection with TLS alert"));
            assertThat(message, containsString("https://mock-server.com/mock_server/HTTPS_TLS.html"));
            assertThat(message, containsString("later failed handshakes from this client address are logged at DEBUG"));
        }
        for (int i = 0; i < 2; i++) {
            try (RawQuicClient client = new RawQuicClient(InsecureTrustManagerFactory.INSTANCE, "not-http3")) {
                assertThrows(ExecutionException.class, client::connect);

                LogEntry entry = theOnlyEntryOf(client);
                assertThat(entry.getLogLevel(), is(Level.DEBUG));
                String message = entry.getMessage(configuration());
                assertThat(message, containsString("TLS handshake failed on HTTP/3 connection from"));
                assertThat(message, containsString("NO_APPLICATION_PROTOCOL"));
                assertThat(message, containsString("no application protocol (ALPN)"));
            }
        }
        assertThatHttp3IsStillServed();
    }

    private LogEntry theOnlyEntryOf(RawQuicClient client) throws InterruptedException {
        List<LogEntry> entries = awaitConnectionEntries(client);
        assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
        assertThat(entries.toString(), entries, hasSize(1));
        assertThat(entries.get(0).getThrowable(), is(nullValue()));
        return entries.get(0);
    }

    @Test
    public void shouldLogAFrameTheControlStreamMayNotCarryOnceAsAConnectionErrorAndStillCloseTheConnection() throws Exception {
        try (RawQuicClient client = new RawQuicClient(Http3.supportedApplicationProtocols())) {
            client.connect();

            client.sendOnANewUnidirectionalStream(CONTROL_STREAM_TYPE, HTTP2_PRIORITY_FRAME_TYPE, 0x00);

            QuicConnectionCloseEvent closed = client.closedByServer();
            assertThat(closed.toString(), closed.isApplicationClose(), is(true));
            assertThat(closed.error(), is(Http3ErrorCode.H3_FRAME_UNEXPECTED.code()));

            List<LogEntry> entries = awaitConnectionEntries(client);
            assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
            assertThat(entries.toString(), entries, hasSize(1));
            assertThat(entries.get(0).getLogLevel(), is(Level.WARN));
            assertThat(entries.get(0).getMessageFormat(), is("closing HTTP/3 connection from:{}for connection error:{}"));
            assertThat(Arrays.asList(entries.get(0).getArguments()), hasItem(Http3ErrorCode.H3_FRAME_UNEXPECTED));
            assertThat(entries.get(0).getThrowable(), instanceOf(Http3Exception.class));
        }
        assertThatHttp3IsStillServed();
    }

    @Test
    public void shouldLogAFrameNettyCannotDecodeOnceAsAWarningAndKeepTheConnection() throws Exception {
        try (RawQuicClient client = new RawQuicClient(Http3.supportedApplicationProtocols())) {
            client.connect();

            // SETTINGS with ENABLE_CONNECT_PROTOCOL set to 2, where RFC 9220 allows 0 or 1: Netty's codec throws
            client.sendOnANewUnidirectionalStream(CONTROL_STREAM_TYPE, SETTINGS_FRAME_TYPE, 0x02, ENABLE_CONNECT_PROTOCOL, 0x02);

            List<LogEntry> entries = awaitConnectionEntries(client);
            assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
            assertThat(entries.toString(), entries, hasSize(1));
            assertThat(entries.get(0).getLogLevel(), is(Level.WARN));
            assertThat(entries.get(0).getMessageFormat(), is("SSL or decoder fault on stream of HTTP/3 connection from:{}:{}:{}"));
            assertThat(Arrays.asList(entries.get(0).getArguments()), hasItem("io.netty.handler.codec.DecoderException"));
            assertThat(String.valueOf(entries.get(0).getArguments()[2]), containsString("IllegalArgumentException"));
            assertThat(entries.get(0).getThrowable(), is(nullValue()));
            assertThat("the connection carries on, as it did", client.isOpen(), is(true));
        }
        assertThatHttp3IsStillServed();
    }

    @Test
    public void shouldLogAStreamItsClientResetOnceBelowAWarningAndKeepTheConnection() throws Exception {
        try (RawQuicClient client = new RawQuicClient(Http3.supportedApplicationProtocols())) {
            client.connect();
            QuicStreamChannel stream = client.sendOnANewUnidirectionalStream(RESERVED_STREAM_TYPE, 1, 2, 3);

            stream.shutdownOutput(Http3ErrorCode.H3_REQUEST_CANCELLED.code()).get(WAIT_SECONDS, TimeUnit.SECONDS);

            List<LogEntry> entries = awaitConnectionEntries(client);
            assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
            assertThat(entries.toString(), entries, hasSize(1));
            assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(entries.get(0).getMessageFormat(), is("stream of HTTP/3 connection from:{}closed or reset by its client:{}"));
            assertThat(entries.get(0).getThrowable(), is(nullValue()));
            assertThat("the connection carries on", client.isOpen(), is(true));
        }
        assertThatHttp3IsStillServed();
    }

    @Test
    public void shouldLogARequestStreamItsClientResetPartWayThroughItsBodyOnceBelowAWarningAndKeepTheConnection() throws Exception {
        int loggedBeforeItBound = logged.size();
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange exchange = connection.start(new DefaultHttp3Headers().method("POST").scheme("https").authority("localhost:" + mockServer.getHttp3Port()).path("/served").addInt("content-length", 100));
            exchange.data(new byte[10]);

            exchange.reset(Http3ErrorCode.H3_REQUEST_CANCELLED);

            List<LogEntry> entries = awaitConnectionEntries(connection.localPort(), loggedBeforeItBound);
            assertThat(nettysLog.since(nettysLogBeforeThisTest), empty());
            // about HTTP/3 only: at INFO the JVM's log also holds notices such as the client's TLS warning on first use
            List<LogEntry> warnings = logged.stream()
                .filter(entry -> entry.getLogLevel().toInt() >= Level.WARN.toInt())
                .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("HTTP/3"))
                .collect(Collectors.toList());
            assertThat(warnings.toString(), warnings, empty());
            assertThat(entries.toString(), entries, hasSize(1));
            assertThat(entries.get(0).getLogLevel(), is(Level.DEBUG));
            assertThat(entries.get(0).getMessageFormat(), is("request stream of HTTP/3 connection from:{}closed or reset by its client:{}"));
            assertThat(entries.get(0).getThrowable(), is(nullValue()));

            Http3TestClient.Exchange next = connection.send(new DefaultHttp3Headers().method("GET").scheme("https").authority("localhost:" + mockServer.getHttp3Port()).path("/served"));
            assertThat("the connection carries on", next.status(), is(200));
        }
    }

    private static void assertThatHttp3IsStillServed() throws Exception {
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange exchange = connection.send(new DefaultHttp3Headers().method("GET").scheme("https").authority("localhost:" + mockServer.getHttp3Port()).path("/served"));
            assertThat(exchange.status(), is(200));
            assertThat(exchange.body(), is("served"));
        }
    }

    /**
     * The entries MockServer logged about the HTTP/3 connection from a client's UDP port, as distinct from those
     * about its requests. Only those logged since the client bound the port: the OS hands a closed client's
     * ephemeral port to the next, so an earlier client's entries can carry the same port.
     */
    private static List<LogEntry> connectionEntries(int clientPort, int loggedBeforeItBound) {
        Pattern address = Pattern.compile(":" + clientPort + "(?!\\d)");
        return logged.stream()
            .skip(loggedBeforeItBound)
            .filter(entry -> String.valueOf(entry.getMessageFormat()).contains("HTTP/3 connection"))
            .filter(entry -> address.matcher(entry.getMessageFormat() + Arrays.toString(entry.getArguments())).find())
            .collect(Collectors.toList());
    }

    private List<LogEntry> awaitConnectionEntries(RawQuicClient client) throws InterruptedException {
        return awaitConnectionEntries(client.localPort(), client.loggedBeforeItBound);
    }

    private List<LogEntry> awaitConnectionEntries(int clientPort, int loggedBeforeItBound) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (connectionEntries(clientPort, loggedBeforeItBound).isEmpty() && nettysLog.size() == nettysLogBeforeThisTest && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(20);
        }
        // whatever else the same failure logs follows at once
        TimeUnit.MILLISECONDS.sleep(200);
        return connectionEntries(clientPort, loggedBeforeItBound);
    }

    /**
     * A QUIC connection to MockServer's HTTP/3 port with no HTTP/3 codec on it, so that it can offer any ALPN
     * protocol and send any bytes on a stream.
     */
    private static final class RawQuicClient implements AutoCloseable {

        private final int loggedBeforeItBound = logged.size();
        private final Channel datagramChannel;
        private final CompletableFuture<QuicConnectionCloseEvent> closedByServer = new CompletableFuture<>();
        private QuicChannel quicChannel;

        private RawQuicClient(String... applicationProtocols) throws Exception {
            this(InsecureTrustManagerFactory.INSTANCE, applicationProtocols);
        }

        private RawQuicClient(TrustManagerFactory trustManagerFactory, String... applicationProtocols) throws Exception {
            datagramChannel = new Bootstrap()
                .group(clientGroup)
                .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
                .handler(Http3.newQuicClientCodecBuilder()
                    .sslContext(QuicSslContextBuilder.forClient()
                        .trustManager(trustManagerFactory)
                        .applicationProtocols(applicationProtocols)
                        .build())
                    .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .build())
                .bind(new InetSocketAddress("127.0.0.1", 0))
                .sync()
                .channel();
        }

        private int localPort() {
            return ((InetSocketAddress) datagramChannel.localAddress()).getPort();
        }

        private void connect() throws Exception {
            quicChannel = QuicChannel.newBootstrap(datagramChannel)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        if (evt instanceof QuicConnectionCloseEvent) {
                            closedByServer.complete((QuicConnectionCloseEvent) evt);
                        }
                        ctx.fireUserEventTriggered(evt);
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        // a connection the server closes is observed through closedByServer()
                    }
                })
                // the server's control and QPACK streams
                .streamHandler(new Discard())
                .remoteAddress(new InetSocketAddress("127.0.0.1", mockServer.getHttp3Port()))
                .connect()
                .get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private QuicStreamChannel sendOnANewUnidirectionalStream(int... bytes) throws Exception {
            QuicStreamChannel stream = quicChannel.createStream(QuicStreamType.UNIDIRECTIONAL, new Discard()).get(WAIT_SECONDS, TimeUnit.SECONDS);
            byte[] content = new byte[bytes.length];
            for (int i = 0; i < bytes.length; i++) {
                content[i] = (byte) bytes[i];
            }
            stream.writeAndFlush(Unpooled.wrappedBuffer(content)).get(WAIT_SECONDS, TimeUnit.SECONDS);
            return stream;
        }

        private QuicConnectionCloseEvent closedByServer() throws Exception {
            return closedByServer.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private boolean isOpen() {
            return quicChannel.isActive();
        }

        @Override
        public void close() {
            if (quicChannel != null) {
                quicChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            }
            datagramChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    @ChannelHandler.Sharable
    private static final class Discard extends ChannelInboundHandlerAdapter {
        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ReferenceCountUtil.release(msg);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            // the server's end of this stream is what the class is about
        }
    }
}

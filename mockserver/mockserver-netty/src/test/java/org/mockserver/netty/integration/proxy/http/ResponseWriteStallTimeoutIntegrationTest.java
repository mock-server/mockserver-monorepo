package org.mockserver.netty.integration.proxy.http;

import io.netty.bootstrap.Bootstrap;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.FixedRecvByteBufAllocator;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.LastHttpContent;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2PriorityFrame;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.DefaultHttp2WindowUpdateFrame;
import io.netty.handler.codec.http2.Http2ChannelDuplexHandler;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.proxy.HttpProxyHandler;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameTypes;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2StreamFrame;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.metrics.Metrics;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.NettyTransport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.regex.Pattern;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.metrics.Metrics.ResponseWriteStall.HTTP1_CONNECTION;
import static org.mockserver.metrics.Metrics.ResponseWriteStall.HTTP2_CONNECTION;
import static org.mockserver.metrics.Metrics.ResponseWriteStall.HTTP2_STREAM;
import static org.mockserver.metrics.Metrics.ResponseWriteStall.TUNNEL_CONNECTION;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.testing.tls.SSLSocketFactory.sslSocketFactory;

/**
 * {@code responseWriteStallTimeoutMillis} ends a response whose client takes none of it for the timeout: an HTTP/1.1
 * connection (or the CONNECT tunnel it reads through) is closed, an HTTP/2 stream reset (or, when the client stops
 * reading its socket, the HTTP/2 connection closed), and a streamed response's upstream is closed; each cut is counted.
 * A client that keeps taking some of it at least once per timeout period gets its whole response, a client that only
 * moves an HTTP/2 stream's flow-control window while taking none of its data is reset all the same, and a disabled
 * timeout leaves a stalled client alone.
 * <p>
 * Responses are forwarded from an upstream: {@code /fixed} is a 16 MiB body with a {@code Content-Length}, so it is
 * aggregated; {@code /small} is its first 1 MiB, which the relay's HTTP/2 loopback can aggregate; {@code /big} is 16 MiB
 * of server-sent events in one write, so it is streamed; {@code /trickle} is about 100 KB of events and then nothing,
 * with the upstream left open. Each is far more than the socket buffers between MockServer and a client with a 32 KiB
 * receive buffer, apart from {@code /trickle} and {@code /small}, which are meant for an HTTP/2 client that grants a
 * stream no more flow-control window.
 * <p>
 * The progressing HTTP/1.1 reader pauses for a third of the timeout between reads of up to 1 MiB: a socket shows a
 * slow reader's progress to its writer only in bursts, once a share of the kernel's buffers is free, so reading a
 * few KB at a time would show MockServer nothing for longer than a short test timeout on some platforms.
 */
public class ResponseWriteStallTimeoutIntegrationTest {

    private static final long STALL_MILLIS = 3000;
    private static final long CUT_WITHIN_MILLIS = 3 * STALL_MILLIS + 5000;
    private static final long SLOW_PHASE_MILLIS = 3 * STALL_MILLIS;
    private static final long READ_PAUSE_MILLIS = STALL_MILLIS / 3;
    private static final int READ_BURST_BYTES = 1024 * 1024;
    private static final int SLOW_SOCKET_READ_BYTES = 8 * 1024;
    private static final long SLOW_SOCKET_READ_PAUSE_MILLIS = 200;
    private static final String TERMINATING_CHUNK = "0\r\n\r\n";
    private static final int SMALL_BODY_BYTES = 1024 * 1024;
    private static final int LARGE_STREAM_WINDOW = 1024 * 1024;
    private static final Map<String, Channel> UPSTREAM_CHANNELS = new ConcurrentHashMap<>();
    // Netty's SslHandler default, which MockServer does not change: a close that waited on the client would take this long
    private static final long TLS_CLOSE_NOTIFY_FLUSH_TIMEOUT_MILLIS = 3000;

    private static byte[] fixedBody;
    private static byte[][] events;
    private static EventLoopGroup upstreamGroup;
    private static EventLoopGroup clientGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer disabledMockServer;
    private static MockServerClient disabledMockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        fixedBody = new byte[16 * 1024 * 1024];
        new Random(72).nextBytes(fixedBody);
        events = StreamedEvents.events(1024 * 1024);

        upstreamGroup = new NioEventLoopGroup(1);
        clientGroup = new NioEventLoopGroup(2);
        UpstreamHandler handler = new UpstreamHandler();
        upstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), handler);
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();

        // a stream idle timeout far longer than any test, so only the write-stall timeout can close an upstream; WARN
        // keeps the 16 MiB forwarded bodies out of the event log
        mockServer = new MockServer(configuration()
            .logLevel("WARN")
            .metricsEnabled(true)
            .streamingResponsesEnabled(true)
            .streamIdleTimeoutSeconds(120)
            .responseWriteStallTimeoutMillis(STALL_MILLIS));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        disabledMockServer = new MockServer(configuration()
            .logLevel("WARN")
            .streamingResponsesEnabled(true)
            .streamIdleTimeoutSeconds(120)
            .responseWriteStallTimeoutMillis(0L));
        disabledMockServerClient = new MockServerClient("localhost", disabledMockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(disabledMockServerClient);
        stopQuietly(disabledMockServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetExpectations() {
        for (MockServerClient client : new MockServerClient[]{mockServerClient, disabledMockServerClient}) {
            client.reset();
            client.when(request().withPath("/forward/.*")).forward(forward().withHost("127.0.0.1").withPort(upstreamPort));
        }
    }

    // ---- HTTP/1.1 ----

    @Test
    public void shouldEndAStalledHttp1ReadersAggregatedResponseIncomplete() throws Exception {
        long countedBefore = Metrics.getResponseWriteStallsCount(HTTP1_CONNECTION);
        try (Socket socket = connect(mockServer, "/forward/fixed?test=http1-aggregated-stalled")) {
            TimeUnit.MILLISECONDS.sleep(CUT_WITHIN_MILLIS);
            Received received = read(socket, 0, Received::aggregatedResponseComplete);
            assertThat("the response was cut short", received.isAggregatedResponseComplete(), is(false));
            assertThat("MockServer closed the connection", received.endedBy, is(Ending.CLOSED));
        }
        assertCounted(HTTP1_CONNECTION, countedBefore);
    }

    @Test
    public void shouldEndAStalledHttp1ReadersStreamedResponseIncompleteAndCloseItsUpstream() throws Exception {
        String uri = "/forward/big?test=http1-streamed-stalled";
        try (Socket socket = connect(mockServer, uri)) {
            Channel upstream = awaitUpstream(uri);
            assertThat("the upstream was closed", upstream.closeFuture().await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            Received received = read(socket, 0, Received::streamedResponseComplete);
            assertThat("no terminating chunk made the response look complete", received.text(), not(endsWith(TERMINATING_CHUNK)));
            assertThat("MockServer closed the connection", received.endedBy, is(Ending.CLOSED));
        }
    }

    @Test
    public void shouldEndAStalledTlsReadersSocketAsSoonAsItsStallIsCut() throws Exception {
        // no relay is involved, so only the watcher can end it; a server of its own, so the only connection it counts
        // is this client's
        MockServer server = new MockServer(configuration()
            .logLevel("WARN")
            .metricsEnabled(true)
            .responseWriteStallTimeoutMillis(STALL_MILLIS));
        try {
            forwardOverAClosedConnection(server);
            awaitInboundConnections(server, 0);
            long countedBefore = Metrics.getResponseWriteStallsCount(HTTP1_CONNECTION);
            try (Socket ignored = connectOverTls(server, "/forward/fixed?test=tls-aggregated-stalled")) {
                awaitInboundConnections(server, 1);
                long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CUT_WITHIN_MILLIS);
                while (Metrics.getResponseWriteStallsCount(HTTP1_CONNECTION) == countedBefore) {
                    assertThat("MockServer cut the stalled connection within " + CUT_WITHIN_MILLIS + "ms", System.nanoTime() < deadline, is(true));
                    TimeUnit.MILLISECONDS.sleep(5);
                }
                long cut = System.nanoTime();
                awaitInboundConnections(server, 0);
                long endedAfterMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - cut);
                assertThat("MockServer's socket ended well before a TLS close_notify flush timeout (" + TLS_CLOSE_NOTIFY_FLUSH_TIMEOUT_MILLIS + "ms) could end it",
                    endedAfterMillis, lessThan(TLS_CLOSE_NOTIFY_FLUSH_TIMEOUT_MILLIS - 1000));
            }
        } finally {
            stopQuietly(server);
        }
    }

    // a MockServerClient would keep its connection, and its close() stops the server
    private static void forwardOverAClosedConnection(MockServer server) throws IOException {
        byte[] expectation = ("{\"httpRequest\":{\"path\":\"/forward/.*\"},\"httpForward\":{\"host\":\"127.0.0.1\",\"port\":" + upstreamPort + "}}")
            .getBytes(StandardCharsets.UTF_8);
        try (Socket socket = new Socket("127.0.0.1", server.getLocalPort())) {
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
            socket.getOutputStream().write(("PUT /mockserver/expectation HTTP/1.1\r\nHost: 127.0.0.1:" + server.getLocalPort()
                + "\r\nContent-Type: application/json\r\nContent-Length: " + expectation.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(expectation);
            socket.getOutputStream().flush();
            String response = new String(socket.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertThat(response, startsWith("HTTP/1.1 201"));
        }
    }

    private static void awaitInboundConnections(MockServer server, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CUT_WITHIN_MILLIS);
        while (server.getInboundConnectionCount() != expected) {
            assertThat("open inbound connections reached " + expected + " within " + CUT_WITHIN_MILLIS + "ms", System.nanoTime() < deadline, is(true));
            TimeUnit.MILLISECONDS.sleep(5);
        }
    }

    @Test
    public void shouldDeliverAnAggregatedResponseWholeToASlowButProgressingHttp1Reader() throws Exception {
        try (Socket socket = connect(mockServer, "/forward/fixed?test=http1-aggregated-slow")) {
            Received received = read(socket, SLOW_PHASE_MILLIS, Received::aggregatedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("still in progress after pausing for several timeouts in all", received.bytesAfterSlowPhase < received.bytes.size(), is(true));
            assertThat("the body arrived intact", java.util.Arrays.equals(received.body(), fixedBody), is(true));
        }
    }

    @Test
    public void shouldDeliverAStreamedResponseWholeToASlowButProgressingHttp1Reader() throws Exception {
        try (Socket socket = connect(mockServer, "/forward/big?test=http1-streamed-slow")) {
            Received received = read(socket, SLOW_PHASE_MILLIS, Received::streamedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("still in progress after pausing for several timeouts in all", received.bytesAfterSlowPhase < received.bytes.size(), is(true));
            assertThat(StreamedEvents.dechunk(received.text()).length, is(16 * StreamedEvents.joinedLength(events)));
        }
    }

    @Test
    public void shouldLeaveAStalledHttp1ReaderAloneWhenTheTimeoutIsDisabled() throws Exception {
        try (Socket socket = connect(disabledMockServer, "/forward/fixed?test=http1-aggregated-disabled")) {
            TimeUnit.MILLISECONDS.sleep(CUT_WITHIN_MILLIS);
            Received received = read(socket, 0, Received::aggregatedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("the body arrived intact", java.util.Arrays.equals(received.body(), fixedBody), is(true));
        }
    }

    // ---- HTTP/1.1 inside a CONNECT tunnel ----

    @Test
    public void shouldCloseAStalledConnectTunnelReadersTunnelAndTheStreamedResponsesUpstream() throws Exception {
        String uri = "/forward/big?test=tunnel-streamed-stalled";
        long countedBefore = Metrics.getResponseWriteStallsCount(TUNNEL_CONNECTION);
        try (Socket socket = connectThroughTunnel(mockServer, uri)) {
            Channel upstream = awaitUpstream(uri);
            assertThat("the upstream was closed", upstream.closeFuture().await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            Received received = read(socket, 0, Received::streamedResponseComplete);
            assertThat("no terminating chunk made the response look complete", received.text(), not(endsWith(TERMINATING_CHUNK)));
            assertThat("MockServer closed the tunnel", received.endedBy, is(Ending.CLOSED));
        }
        assertCounted(TUNNEL_CONNECTION, countedBefore);
    }

    @Test
    public void shouldCloseTheTunnelAtOnceForAReaderThatResumesJustAfterItsStallIsCut() throws Exception {
        // the relay holds this client's TLS, and closing through it would leave the tunnel open, refusing the rest of
        // the response, while its close_notify waited behind the bytes not taken
        try (Socket socket = connectThroughTunnel(mockServer, "/forward/big?test=tunnel-streamed-stalled-then-reading")) {
            // resumes as soon as the cut is logged: a fixed pause can end before a loaded host has cut the tunnel
            awaitConnectionCut(socket);
            Received received = read(socket, 0, Received::streamedResponseComplete);
            assertThat("MockServer closed the tunnel", received.endedBy, is(Ending.CLOSED));
            assertThat("no terminating chunk made the response look complete", received.text(), not(endsWith(TERMINATING_CHUNK)));
            assertThat("the relay was not left writing the rest of the response to a tunnel that refuses it",
                mockServerClient.retrieveLogMessages(null), not(containsString("exception while returning writing")));
        }
    }

    @Test
    public void shouldDeliverAStreamedResponseWholeToASlowButProgressingConnectTunnelReader() throws Exception {
        // MockServer's relay reads its loopback only as fast as this client reads, so the loopback must not be cut either
        try (Socket socket = connectThroughTunnel(mockServer, "/forward/big?test=tunnel-streamed-slow")) {
            Received received = read(socket, SLOW_PHASE_MILLIS, Received::streamedResponseComplete);
            assertThat(received.endedBy, is(Ending.COMPLETE));
            assertThat("still in progress after pausing for several timeouts in all", received.bytesAfterSlowPhase < received.bytes.size(), is(true));
            assertThat(StreamedEvents.dechunk(received.text()).length, is(16 * StreamedEvents.joinedLength(events)));
        }
    }

    @Test
    public void shouldNotExemptAClientThatSendsTheRelayLoopbackPreambleItself() throws Exception {
        // only MockServer's own relay loopback is exempt, so a client cannot opt out by sending the loopback's preamble:
        // from any other connection the preamble is not taken as one at all
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", mockServer.getLocalPort()));
            socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(2));
            socket.getOutputStream().write(("PROXIED_127.0.0.1:" + upstreamPort).getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().flush();
            byte[] answer = new byte["PROXIED_RESPONSE_".length()];
            int read = 0;
            try {
                for (int n; read < answer.length && (n = socket.getInputStream().read(answer, read, answer.length - read)) != -1; ) {
                    read += n;
                }
            } catch (SocketTimeoutException | SocketException nothingMore) {
                // nothing more is coming
            }
            String answered = new String(answer, 0, read, StandardCharsets.US_ASCII);
            assertThat("MockServer did not answer the preamble, not even in part: " + answered, read > 0 && "PROXIED_RESPONSE_".startsWith(answered), is(false));
        }
    }

    // ---- HTTP/2 inside a CONNECT tunnel ----

    @Test
    public void shouldResetAStalledHttp2StreamInsideAConnectTunnelWhileAnotherStreamProgresses() throws Exception {
        // the relay terminates the client's HTTP/2 itself and its loopback is exempt, so the client-facing leg cuts the stream
        long countedBefore = Metrics.getResponseWriteStallsCount(HTTP2_STREAM);
        try (Http2Client client = Http2Client.openThroughTunnel(mockServer)) {
            Http2Client.Stream stalled = client.request("/forward/small?test=tunnel-http2-stalled");
            Http2Client.Stream progressing = client.request("/forward/small?test=tunnel-http2-progressing");
            // taken in small steps, well inside the timeout, so a loaded host cannot starve it into a stall of its own,
            // and slowly enough that it is still in progress when the stalled stream is cut
            consumeSlowly(client, progressing, READ_PAUSE_MILLIS / 10, 8 * 1024);
            boolean stalledEnded = stalled.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS);
            assertThat("the stalled stream ended (tunnel active: " + client.channel.isActive() + ", GOAWAY: " + client.goAwayReceived.get() + ")", stalledEnded, is(true));
            assertThat("the stalled stream was reset", stalled.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the progressing stream is still in progress, not reset with " + progressing.resetErrorCode.get(), progressing.ended.getCount(), is(1L));
            client.consumeAll(progressing);
            assertComplete(progressing, SMALL_BODY_BYTES);
            // the reset fails the stalled stream's queued data, which must end only that stream, not the tunnel
            assertTunnelOpen(client);
            Http2Client.Stream after = client.request("/forward/small?test=tunnel-http2-after-the-cut");
            client.consumeAll(after);
            assertComplete(after, SMALL_BODY_BYTES);
            assertTunnelOpen(client);
            String logged = mockServerClient.retrieveLogMessages(null);
            assertThat("the reset stream's failed write was not logged as a failure", logged, not(containsString("exception while returning writing")));
            assertThat(logged, not(containsString("Stream closed before write could take place")));
        }
        assertCounted(HTTP2_STREAM, countedBefore);
    }

    private static void assertTunnelOpen(Http2Client client) {
        assertThat("no GOAWAY was received", client.goAwayReceived.get(), is(false));
        assertThat("the tunnel, whose socket kept being read, stayed open", client.channel.isActive(), is(true));
    }

    // ---- HTTP/2 ----

    @Test
    public void shouldResetAStalledHttp2StreamOfAnAggregatedResponse() throws Exception {
        long countedBefore = Metrics.getResponseWriteStallsCount(HTTP2_STREAM);
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request("/forward/fixed?test=http2-aggregated-stalled");
            assertThat("the stream ended", stream.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("the stream was reset", stream.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the connection, whose socket kept being read, stayed open", client.channel.isActive(), is(true));
        }
        assertCounted(HTTP2_STREAM, countedBefore);
    }

    @Test
    public void shouldCloseAnHttp2ConnectionWhoseClientStopsReadingItsSocketAndTheStreamedResponsesUpstream() throws Exception {
        String uri = "/forward/big?test=http2-socket-stalled";
        long streamsCountedBefore = Metrics.getResponseWriteStallsCount(HTTP2_STREAM);
        long connectionsCountedBefore = Metrics.getResponseWriteStallsCount(HTTP2_CONNECTION);
        try (Http2Client client = Http2Client.openWithOpenWindows(mockServer)) {
            Http2Client.Stream stream = client.request(uri);
            client.stopReading();
            Channel upstream = awaitUpstream(uri);
            assertThat("the upstream was closed", upstream.closeFuture().await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            client.resumeReading();
            assertThat("MockServer closed the connection", client.channel.closeFuture().await(10, TimeUnit.SECONDS), is(true));
            assertThat("the response was cut short", stream.endStream.get(), is(false));
        }
        // the stream's own window stayed open, so it waited only for the socket, which the connection watcher times
        assertCounted(HTTP2_CONNECTION, connectionsCountedBefore);
        assertThat("no stream was reset", Metrics.getResponseWriteStallsCount(HTTP2_STREAM), is(streamsCountedBefore));
    }

    @Test
    public void shouldNotResetAnHttp2StreamOfASlowReaderWhoseSocketIsProgressing() throws Exception {
        // On epoll the connection watcher sees a slow reader's progress in the kernel's lastDataSent, while the send
        // buffer frees too little to wake MockServer's writer, so the stream's open window does not change for longer
        // than the timeout. On NIO the connection watcher cannot see this reader's progress between bursts and cuts it.
        Assume.assumeTrue("needs the native epoll transport", NettyTransport.useNativeTransport(mockServer.getConfiguration().useNativeTransport()));
        long streamsCountedBefore = Metrics.getResponseWriteStallsCount(HTTP2_STREAM);
        long connectionsCountedBefore = Metrics.getResponseWriteStallsCount(HTTP2_CONNECTION);
        try (Http2Client client = Http2Client.openWithOpenWindows(mockServer, SLOW_SOCKET_READ_BYTES)) {
            Http2Client.Stream stream = client.request("/forward/fixed?test=http2-slow-socket-reader");
            client.stopReading();
            long started = System.nanoTime();
            while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < SLOW_PHASE_MILLIS) {
                TimeUnit.MILLISECONDS.sleep(SLOW_SOCKET_READ_PAUSE_MILLIS);
                client.readOnce();
            }
            // a reset would reach this client only after the data queued ahead of it, so it is read from the counters
            assertThat("no stream was reset during the slow phase", Metrics.getResponseWriteStallsCount(HTTP2_STREAM), is(streamsCountedBefore));
            assertThat("the connection was not closed during the slow phase", Metrics.getResponseWriteStallsCount(HTTP2_CONNECTION), is(connectionsCountedBefore));
            client.resumeReading();
            assertCompleteWithFixedBody(stream);
        }
    }

    @Test
    public void shouldResetAStalledHttp2StreamOfAStreamedResponseAndCloseItsQuietUpstream() throws Exception {
        String uri = "/forward/trickle?test=http2-streamed-stalled";
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request(uri);
            Channel upstream = awaitUpstream(uri);
            assertThat("the stream ended", stream.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("the stream was reset", stream.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            // the upstream has sent everything and is quiet, so only closing it with the stream frees it before its idle timeout
            assertThat("the upstream was closed", upstream.closeFuture().await(5, TimeUnit.SECONDS), is(true));
        }
    }

    @Test
    public void shouldDeliverAnAggregatedResponseWholeToASlowButProgressingHttp2Reader() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request("/forward/fixed?test=http2-aggregated-slow");
            consumeSlowly(client, stream);
            assertThat("still in progress after the slow phase", stream.ended.getCount(), is(1L));
            client.consumeAll(stream);
            assertCompleteWithFixedBody(stream);
        }
    }

    @Test
    public void shouldNotResetAnHttp2StreamQueuedBehindAStreamItsClientIsTaking() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream first = client.request("/forward/fixed?test=http2-priority-first");
            // a stream with nothing to send yields to its dependants, so the queued stream is opened only once the first's
            // response is flowing; had its response reached MockServer first, it would be sent a window's worth and stall
            awaitData(first);
            // depends exclusively on the first, so it gets no data, and its window does not change, while the first has any to send
            Http2Client.Stream queued = client.request("/forward/fixed?test=http2-priority-queued", first);
            consumeSlowly(client, first);
            assertThat("the queued stream got nothing while the first had data to send", queued.dataBytes.get(), is(0L));
            assertThat("the queued stream is still in progress, not reset with " + queued.resetErrorCode.get(), queued.ended.getCount(), is(1L));
            assertThat("the first stream is still in progress, not reset with " + first.resetErrorCode.get(), first.ended.getCount(), is(1L));
            client.consumeAll(first, queued);
            assertCompleteWithFixedBody(first);
            assertCompleteWithFixedBody(queued);
        }
    }

    @Test
    public void shouldLetAnHttp2StreamCarryOnOnceTheStalledStreamHoldingTheConnectionWindowIsReset() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream holder = client.request("/forward/fixed?test=http2-window-holder");
            awaitData(holder);
            Http2Client.Stream sibling = queueBehind(client, holder, "/forward/fixed?test=http2-window-holder-sibling");
            // the client stops taking the holder's data, whose window's worth it has not consumed holds the whole
            // connection window, so the sibling, timed from the holder's last movement, gets nothing
            assertThat("the holder was reset", holder.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat(holder.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            // Netty's codec returns the reset stream's unconsumed bytes to the connection window
            assertCompleteWithFixedBody(sibling);
        }
    }

    @Test
    public void shouldLetAnHttp2StreamCarryOnOnceAStalledStreamSharingItsConnectionWindowIsReset() throws Exception {
        // at default priority the two share the connection window, so the holder's own window can still be open when
        // the window its client has not returned for the sibling's consumed data and the holder's unconsumed data fill it
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream holder = client.request("/forward/fixed?test=http2-window-sharer");
            awaitData(holder);
            Http2Client.Stream sibling = client.request("/forward/fixed?test=http2-window-sharer-sibling");
            client.consumeAll(sibling);
            consumeSlowly(client, holder);
            assertThat("the sibling was still in progress when the client stopped taking the holder's data", sibling.ended.getCount(), is(1L));
            assertThat("the holder was reset", holder.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat(holder.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertCompleteWithFixedBody(sibling);
        }
    }

    @Test
    public void shouldResetAnHttp2StreamGivenNoWindowInTheFreshPeriodAfterTheStalledStreamHoldingTheConnectionWindowIsReset() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream holder = client.request("/forward/fixed?test=http2-window-holder-discarded");
            awaitData(holder);
            Http2Client.Stream sibling = queueBehind(client, holder, "/forward/fixed?test=http2-window-holder-discarded-sibling");
            // a client that never returns the reset stream's share of the connection window
            client.discardConnectionWindowUpdates();
            assertThat("the holder was reset", holder.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat(holder.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the sibling was reset", sibling.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat(sibling.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the sibling got nothing", sibling.dataBytes.get(), is(0L));
            assertThat("the client discarded the connection window the holder's reset released", client.discardedConnectionWindowUpdates(), greaterThan(0));
            long resetApartMillis = TimeUnit.NANOSECONDS.toMillis(sibling.resetNanos.get() - holder.resetNanos.get());
            assertThat("the sibling was reset after a fresh period, not with the holder (" + resetApartMillis + " ms apart)", resetApartMillis, greaterThan(STALL_MILLIS / 2));
        }
    }

    @Test
    public void shouldResetOnlyTheStalledHttp2StreamWhenItsClientLeavesMoreOfAConsumedStreamUnreturned() throws Exception {
        long countedBefore = Metrics.getResponseWriteStallsCount(HTTP2_STREAM);
        try (Http2Client client = Http2Client.open(mockServer)) {
            // a stream window of 1 MiB over the connection window left at 65,535 bytes: Netty's codec returns a stream's
            // window only once half of it is consumed, so a consumed stream can have far more unreturned than a stalled
            // stream can hold of the connection window
            client.onEventLoop(() -> client.initialWindow(LARGE_STREAM_WINDOW));
            Http2Client.Stream sibling = client.request("/forward/fixed?test=http2-large-window-sibling");
            // the client has returned the sibling's window once, and has since consumed twice what the connection window
            // holds, with room left below the next return for all it can be sent before the connection window closes
            long siblingUnreturned = 0;
            for (long started = System.nanoTime(); ; ) {
                client.consumeReceived(sibling, 16 * 1024);
                long[] returnedAndUnreturned = client.windowReturned(sibling);
                siblingUnreturned = returnedAndUnreturned[1];
                if (returnedAndUnreturned[0] > 0 && siblingUnreturned >= 2 * Http2CodecUtil.DEFAULT_WINDOW_SIZE) {
                    break;
                }
                assertThat("the sibling's client returned window for it in time", TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) < 30, is(true));
                TimeUnit.MILLISECONDS.sleep(2);
            }
            assertThat("its next return is far off", siblingUnreturned < LARGE_STREAM_WINDOW / 2 - 4 * Http2CodecUtil.DEFAULT_WINDOW_SIZE, is(true));

            // the client takes none of the holder's data, and all of the sibling's once the holder's response is waiting
            Http2Client.Stream holder = client.request("/forward/fixed?test=http2-large-window-holder");
            assertThat("the holder's response began", holder.responseStarted.await(30, TimeUnit.SECONDS), is(true));
            client.consumeAll(sibling);

            assertThat("the holder was reset", holder.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat(holder.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the holder held no more than the connection window", holder.dataBytes.get() <= Http2CodecUtil.DEFAULT_WINDOW_SIZE, is(true));
            assertCompleteWithFixedBody(sibling);
        }
        assertThat("only the holder was reset", Metrics.getResponseWriteStallsCount(HTTP2_STREAM), is(countedBefore + 1));
    }

    @Test
    public void shouldResetAStalledHttp2StreamWhoseClientKeepsChangingItsInitialWindow() throws Exception {
        long countedBefore = Metrics.getResponseWriteStallsCount(HTTP2_STREAM);
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request("/forward/fixed?test=http2-initial-window-changed");
            awaitData(stream);
            // each SETTINGS frame lowers every stream's send window by a byte, so the window differs at every check
            // while no data leaves; the socket keeps being read, so the connection watcher sees nothing either
            AtomicInteger initialWindow = new AtomicInteger(Http2CodecUtil.DEFAULT_WINDOW_SIZE);
            client.every(STALL_MILLIS / 12, () -> client.initialWindow(initialWindow.decrementAndGet()));
            assertThat("the stream ended", stream.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("the stream was reset", stream.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("MockServer applied the client's changes while the stream was stalled", client.settingsAcks.get(), greaterThan(6));
            assertThat("the client was sent no more than its first window", stream.dataBytes.get(), is((long) Http2CodecUtil.DEFAULT_WINDOW_SIZE));
            assertThat("the connection, whose socket kept being read, stayed open", client.channel.isActive(), is(true));
        }
        assertCounted(HTTP2_STREAM, countedBefore);
    }

    @Test
    public void shouldResetAStalledHttp2StreamWhoseClientKeepsSendingItOneByteWindowUpdates() throws Exception {
        long countedBefore = Metrics.getResponseWriteStallsCount(HTTP2_STREAM);
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream stream = client.request("/forward/fixed?test=http2-one-byte-window-updates");
            awaitData(stream);
            // the stream's first window's worth is also the whole connection window, which the client never returns,
            // so the stream window these updates open lets nothing leave
            AtomicInteger updates = new AtomicInteger();
            client.every(STALL_MILLIS / 12, () -> {
                client.windowUpdate(stream, 1);
                updates.incrementAndGet();
            });
            assertThat("the stream ended", stream.ended.await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("the stream was reset", stream.resetErrorCode.get(), is(Http2Error.CANCEL.code()));
            assertThat("the client sent its updates while the stream was stalled", updates.get(), greaterThan(6));
            assertThat("the client was sent no more than its first window", stream.dataBytes.get(), is((long) Http2CodecUtil.DEFAULT_WINDOW_SIZE));
            assertThat("the connection, whose socket kept being read, stayed open", client.channel.isActive(), is(true));
        }
        assertCounted(HTTP2_STREAM, countedBefore);
    }

    @Test
    public void shouldCloseAnHttp2ConnectionWhoseClientOverflowsAnEarlierStreamsWindowWithItsInitialWindow() throws Exception {
        try (Http2Client client = Http2Client.open(mockServer)) {
            Http2Client.Stream holder = client.request("/forward/fixed?test=http2-overflow-holder");
            awaitData(holder);
            // nothing a reset stream held is returned, so the streams opened next are sent nothing throughout
            client.discardConnectionWindowUpdates();
            Http2Client.Stream overflowing = client.request("/forward/fixed?test=http2-overflow-overflowing");
            client.onEventLoop(() -> client.windowUpdate(overflowing, Integer.MAX_VALUE - Http2CodecUtil.DEFAULT_WINDOW_SIZE));
            Http2Client.Stream stalled = client.request("/forward/fixed?test=http2-overflow-stalled");
            // RFC 9113 6.9.2: a connection error, so no stream is left behind with a window the rise skipped
            client.onEventLoop(() -> client.initialWindow(Http2CodecUtil.DEFAULT_WINDOW_SIZE + 1));
            assertThat("MockServer closed the connection", client.channel.closeFuture().await(CUT_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("its GOAWAY named the overflow", client.goAwayErrorCode.get(), is(Http2Error.FLOW_CONTROL_ERROR.code()));
            assertThat("the client was sent none of the stalled stream", stalled.dataBytes.get(), is(0L));
        }
    }

    /**
     * Opens a stream that depends exclusively on {@code holder}, so it gets nothing while the holder has data to send,
     * and then takes the holder's data slowly for several timeouts, so the new stream's response waits all that time.
     * The new stream's data is consumed as it arrives.
     */
    private static Http2Client.Stream queueBehind(Http2Client client, Http2Client.Stream holder, String uri) throws Exception {
        Http2Client.Stream queued = client.request(uri, holder);
        client.consumeAll(queued);
        consumeSlowly(client, holder);
        assertThat("the queued stream got nothing while the holder had data to send", queued.dataBytes.get(), is(0L));
        assertThat("the queued stream is still in progress, not reset with " + queued.resetErrorCode.get(), queued.ended.getCount(), is(1L));
        return queued;
    }

    // the client sends a WINDOW_UPDATE for what it has received about once a second, so the stream gets more window that often
    private static void consumeSlowly(Http2Client client, Http2Client.Stream stream) throws Exception {
        consumeSlowly(client, stream, READ_PAUSE_MILLIS, Integer.MAX_VALUE);
    }

    private static void consumeSlowly(Http2Client client, Http2Client.Stream stream, long pauseMillis, int maxBytes) throws Exception {
        long started = System.nanoTime();
        while (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < SLOW_PHASE_MILLIS) {
            TimeUnit.MILLISECONDS.sleep(pauseMillis);
            client.consumeReceived(stream, maxBytes);
        }
    }

    private static void awaitData(Http2Client.Stream stream) throws InterruptedException {
        for (int i = 0; i < 3000 && stream.dataBytes.get() == 0 && stream.ended.getCount() > 0; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the stream's response is flowing", stream.dataBytes.get(), greaterThan(0L));
    }

    private static void assertCompleteWithFixedBody(Http2Client.Stream stream) throws InterruptedException {
        assertComplete(stream, fixedBody.length);
    }

    private static void assertComplete(Http2Client.Stream stream, int bodyBytes) throws InterruptedException {
        assertThat("the stream ended", stream.ended.await(30, TimeUnit.SECONDS), is(true));
        assertThat(stream.resetErrorCode.get(), is(nullValue()));
        assertThat(stream.endStream.get(), is(true));
        assertThat(stream.dataBytes.get(), is((long) bodyBytes));
    }

    // ---- harness ----

    private static void assertCounted(Metrics.ResponseWriteStall stall, long countedBefore) throws InterruptedException {
        for (int i = 0; i < 500 && Metrics.getResponseWriteStallsCount(stall) == countedBefore; i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the cut was counted as " + stall, Metrics.getResponseWriteStallsCount(stall), greaterThan(countedBefore));
    }

    /**
     * Waits, without reading the socket, until MockServer has logged closing this client's connection for its stall.
     */
    private static void awaitConnectionCut(Socket socket) throws InterruptedException {
        Pattern cut = Pattern.compile("closing connection from:\\s*/?127\\.0\\.0\\.1:" + socket.getLocalPort() + "\\b");
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(CUT_WITHIN_MILLIS);
        while (Arrays.stream(mockServerClient.retrieveLogMessagesArray(null)).noneMatch(message -> cut.matcher(message).find())) {
            assertThat("MockServer cut the stalled connection within " + CUT_WITHIN_MILLIS + "ms", System.nanoTime() < deadline, is(true));
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private static Socket connect(MockServer server, String uri) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(32 * 1024);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
        return get(socket, uri);
    }

    private static Socket connectOverTls(MockServer server, String uri) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(32 * 1024);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
        return get(sslSocketFactory().wrapSocket(socket), uri);
    }

    /**
     * Opens a CONNECT tunnel, which MockServer answers itself through its relay and loopback, and sends the request
     * over TLS inside it.
     */
    private static Socket connectThroughTunnel(MockServer server, String uri) throws IOException {
        Socket socket = new Socket();
        socket.setReceiveBufferSize(32 * 1024);
        socket.connect(new InetSocketAddress("127.0.0.1", server.getLocalPort()));
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(15));
        socket.getOutputStream().write("CONNECT 127.0.0.1:443 HTTP/1.1\r\nHost: 127.0.0.1:443\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        // byte by byte, so nothing of the TLS handshake that follows is consumed
        StringBuilder connectResponse = new StringBuilder();
        InputStream in = socket.getInputStream();
        for (int b; !connectResponse.toString().endsWith("\r\n\r\n") && (b = in.read()) != -1; ) {
            connectResponse.append((char) b);
        }
        assertThat(connectResponse.toString(), startsWith("HTTP/1.1 200"));
        return get(sslSocketFactory().wrapSocket(socket), uri);
    }

    private static Socket get(Socket socket, String uri) throws IOException {
        socket.getOutputStream().write(("GET " + uri + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstreamPort + "\r\nAccept: " + accept(uri) + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    // asking for an event stream makes MockServer stream the response, so only the event-stream paths ask for one
    private static String accept(String uri) {
        return uri.startsWith("/forward/fixed") || uri.startsWith("/forward/small") ? "application/octet-stream" : "text/event-stream";
    }

    private static Channel awaitUpstream(String uri) throws InterruptedException {
        for (int i = 0; i < 1000 && !UPSTREAM_CHANNELS.containsKey(uri); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the upstream received " + uri, UPSTREAM_CHANNELS.containsKey(uri), is(true));
        return UPSTREAM_CHANNELS.get(uri);
    }

    enum Ending {COMPLETE, CLOSED, TIMED_OUT}

    /**
     * For {@code slowPhaseMillis}, pauses for {@link #READ_PAUSE_MILLIS} between reads of up to {@link #READ_BURST_BYTES};
     * then reads as fast as it can, until the response is complete, the connection ends or nothing arrives for the
     * socket timeout.
     */
    private static Received read(Socket socket, long slowPhaseMillis, Predicate<Received> complete) throws InterruptedException {
        Received received = new Received();
        long started = System.nanoTime();
        byte[] buffer = new byte[64 * 1024];
        try {
            InputStream in = socket.getInputStream();
            int burst = 0;
            while (true) {
                int read = in.read(buffer);
                if (read == -1) {
                    received.endedBy = Ending.CLOSED;
                    break;
                }
                received.write(buffer, read);
                if (complete.test(received)) {
                    received.endedBy = Ending.COMPLETE;
                    break;
                }
                if (TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < slowPhaseMillis) {
                    burst += read;
                    if (burst >= READ_BURST_BYTES) {
                        burst = 0;
                        TimeUnit.MILLISECONDS.sleep(READ_PAUSE_MILLIS);
                    }
                    received.bytesAfterSlowPhase = received.bytes.size();
                }
            }
        } catch (SocketTimeoutException timeout) {
            received.endedBy = Ending.TIMED_OUT;
        } catch (IOException reset) {
            received.endedBy = Ending.CLOSED;
        }
        return received;
    }

    private static final class Received {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        private final byte[] tail = new byte[TERMINATING_CHUNK.length()];
        private int headLength = -1;
        private Ending endedBy;
        private int bytesAfterSlowPhase;

        void write(byte[] buffer, int length) {
            bytes.write(buffer, 0, length);
            int fromBuffer = Math.min(length, tail.length);
            int keepOld = tail.length - fromBuffer;
            System.arraycopy(tail, tail.length - keepOld, tail, 0, keepOld);
            System.arraycopy(buffer, length - fromBuffer, tail, keepOld, fromBuffer);
            if (headLength < 0) {
                int end = text(Math.min(bytes.size(), 16 * 1024)).indexOf("\r\n\r\n");
                headLength = end < 0 ? -1 : end + 4;
            }
        }

        private String text(int length) {
            return new String(bytes.toByteArray(), 0, length, StandardCharsets.ISO_8859_1);
        }

        String text() {
            return bytes.toString(StandardCharsets.ISO_8859_1);
        }

        byte[] body() {
            byte[] all = bytes.toByteArray();
            byte[] body = new byte[all.length - headLength];
            System.arraycopy(all, headLength, body, 0, body.length);
            return body;
        }

        boolean isAggregatedResponseComplete() {
            return aggregatedResponseComplete(this);
        }

        static boolean aggregatedResponseComplete(Received received) {
            return received.headLength > 0 && received.bytes.size() >= received.headLength + fixedBody.length;
        }

        static boolean streamedResponseComplete(Received received) {
            return received.bytes.size() >= TERMINATING_CHUNK.length() && new String(received.tail, StandardCharsets.ISO_8859_1).equals(TERMINATING_CHUNK);
        }
    }

    /**
     * An h2c client that consumes (and so grants window for) only what it is told to: until then each stream's window
     * and the connection's run out and MockServer's flow controller holds the rest, while the socket itself keeps being
     * read.
     */
    private static final class Http2Client extends Http2ChannelDuplexHandler implements AutoCloseable {
        private final Map<Http2FrameStream, Stream> streams = new ConcurrentHashMap<>();
        private final ReadGate readGate = new ReadGate();
        private final ConnectionWindowUpdateGate connectionWindowUpdateGate = new ConnectionWindowUpdateGate();
        private final AtomicBoolean goAwayReceived = new AtomicBoolean();
        private final AtomicReference<Long> goAwayErrorCode = new AtomicReference<>();
        private final AtomicInteger settingsAcks = new AtomicInteger();
        private final List<Future<?>> repeating = new CopyOnWriteArrayList<>();
        private Channel channel;
        private ChannelHandlerContext ctx;

        static final class Stream {
            private final AtomicLong dataBytes = new AtomicLong();
            private final AtomicBoolean endStream = new AtomicBoolean();
            private final AtomicReference<Long> resetErrorCode = new AtomicReference<>();
            private final AtomicLong resetNanos = new AtomicLong();
            private final CountDownLatch ended = new CountDownLatch(1);
            private final CountDownLatch responseStarted = new CountDownLatch(1);
            private Http2FrameStream frameStream;
            private int unconsumedBytes;
            private boolean consumeAll;
        }

        static Http2Client open(MockServer server) throws Exception {
            return open(server, Http2FrameCodecBuilder.forClient(), 0, 0);
        }

        /**
         * Grants every stream, and the connection, close to the largest window HTTP/2 allows, so nothing MockServer
         * sends waits for flow-control window, and reads its socket through a 32 KiB receive buffer.
         */
        static Http2Client openWithOpenWindows(MockServer server) throws Exception {
            return openWithOpenWindows(server, 0);
        }

        /**
         * @param readBytes if above 0, each read of the socket takes at most this many bytes
         */
        static Http2Client openWithOpenWindows(MockServer server, int readBytes) throws Exception {
            return open(server, Http2FrameCodecBuilder.forClient().initialSettings(Http2Settings.defaultSettings().initialWindowSize(Http2CodecUtil.MAX_INITIAL_WINDOW_SIZE)),
                Http2CodecUtil.MAX_INITIAL_WINDOW_SIZE - Http2CodecUtil.DEFAULT_WINDOW_SIZE, 32 * 1024, readBytes);
        }

        private static Http2Client open(MockServer server, Http2FrameCodecBuilder codec, int connectionWindowIncrement, int receiveBufferSize) throws Exception {
            return open(server, codec, connectionWindowIncrement, receiveBufferSize, 0);
        }

        /**
         * Speaks cleartext HTTP/2 through a CONNECT tunnel, which MockServer's relay terminates, granting the connection
         * close to the largest window so one stream's unconsumed data cannot hold back another.
         */
        static Http2Client openThroughTunnel(MockServer server) throws Exception {
            return open(server, Http2FrameCodecBuilder.forClient(), Http2CodecUtil.MAX_INITIAL_WINDOW_SIZE - Http2CodecUtil.DEFAULT_WINDOW_SIZE, 0, 0, true);
        }

        private static Http2Client open(MockServer server, Http2FrameCodecBuilder codec, int connectionWindowIncrement, int receiveBufferSize, int readBytes) throws Exception {
            return open(server, codec, connectionWindowIncrement, receiveBufferSize, readBytes, false);
        }

        private static Http2Client open(MockServer server, Http2FrameCodecBuilder codec, int connectionWindowIncrement, int receiveBufferSize, int readBytes, boolean throughTunnel) throws Exception {
            Http2Client client = new Http2Client();
            Bootstrap bootstrap = new Bootstrap()
                .group(clientGroup)
                .channel(NioSocketChannel.class)
                // the tunnel's target is handed to the CONNECT proxy, not resolved here
                .resolver(NoopAddressResolverGroup.INSTANCE);
            if (receiveBufferSize > 0) {
                bootstrap.option(ChannelOption.SO_RCVBUF, receiveBufferSize);
            }
            if (readBytes > 0) {
                bootstrap.option(ChannelOption.RCVBUF_ALLOCATOR, new FixedRecvByteBufAllocator(readBytes));
            }
            client.channel = bootstrap
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(client.connectionWindowUpdateGate, client.readGate);
                        if (throughTunnel) {
                            ch.pipeline().addLast(new HttpProxyHandler(new InetSocketAddress("127.0.0.1", server.getLocalPort())));
                        }
                        ch.pipeline().addLast(codec.build(), client);
                    }
                })
                .connect(throughTunnel ? InetSocketAddress.createUnresolved("127.0.0.1", upstreamPort) : new InetSocketAddress("127.0.0.1", server.getLocalPort())).sync().channel();
            if (connectionWindowIncrement > 0) {
                client.channel.writeAndFlush(new DefaultHttp2WindowUpdateFrame(connectionWindowIncrement)).sync();
            }
            return client;
        }

        void stopReading() throws Exception {
            ctx.executor().submit(() -> {
                readGate.closed = true;
                channel.config().setAutoRead(false);
            }).get(5, TimeUnit.SECONDS);
        }

        void readOnce() throws Exception {
            ctx.executor().submit(() -> {
                readGate.permits++;
                channel.read();
            }).get(5, TimeUnit.SECONDS);
        }

        void resumeReading() throws Exception {
            ctx.executor().submit(() -> {
                readGate.closed = false;
                channel.config().setAutoRead(true);
            }).get(5, TimeUnit.SECONDS);
        }

        /**
         * With auto-read off, Netty's HTTP/2 codec still asks for the next read after every read, so a stopped client
         * also drops those requests here, letting through only the reads it asks for itself.
         */
        private static final class ReadGate extends ChannelOutboundHandlerAdapter {
            private boolean closed;
            private int permits;

            @Override
            public void read(ChannelHandlerContext ctx) {
                if (!closed) {
                    ctx.read();
                } else if (permits > 0) {
                    permits--;
                    ctx.read();
                }
            }
        }

        void discardConnectionWindowUpdates() throws Exception {
            ctx.executor().submit(() -> connectionWindowUpdateGate.discarding = true).get(5, TimeUnit.SECONDS);
        }

        int discardedConnectionWindowUpdates() throws Exception {
            return ctx.executor().submit(() -> connectionWindowUpdateGate.discarded).get(5, TimeUnit.SECONDS);
        }

        /**
         * The window the codec has returned for the stream, and what the client has consumed of it beyond that.
         */
        long[] windowReturned(Stream stream) throws Exception {
            return ctx.executor().submit(() -> {
                long returned = connectionWindowUpdateGate.streamWindowReturned.getOrDefault(stream.frameStream.id(), 0L);
                return new long[]{returned, stream.dataBytes.get() - stream.unconsumedBytes - returned};
            }).get(5, TimeUnit.SECONDS);
        }

        /**
         * Once discarding, drops every connection-level {@code WINDOW_UPDATE} the codec writes, each of which Netty's
         * frame writer writes in a buffer of its own. Adds up those it writes for each stream.
         */
        private static final class ConnectionWindowUpdateGate extends ChannelOutboundHandlerAdapter {
            private final Map<Integer, Long> streamWindowReturned = new HashMap<>();
            private boolean discarding;
            private int discarded;

            @Override
            public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) {
                if (discarding && msg instanceof ByteBuf && isConnectionWindowUpdate((ByteBuf) msg)) {
                    discarded++;
                    ReferenceCountUtil.release(msg);
                    promise.trySuccess();
                } else {
                    if (msg instanceof ByteBuf && isWindowUpdate((ByteBuf) msg) && !isConnectionWindowUpdate((ByteBuf) msg)) {
                        ByteBuf frame = (ByteBuf) msg;
                        streamWindowReturned.merge(frame.getInt(frame.readerIndex() + 5), (long) frame.getInt(frame.readerIndex() + Http2CodecUtil.FRAME_HEADER_LENGTH), Long::sum);
                    }
                    ctx.write(msg, promise);
                }
            }

            private static boolean isWindowUpdate(ByteBuf frame) {
                return frame.readableBytes() == Http2CodecUtil.FRAME_HEADER_LENGTH + 4
                    && frame.getByte(frame.readerIndex() + 3) == Http2FrameTypes.WINDOW_UPDATE;
            }

            private static boolean isConnectionWindowUpdate(ByteBuf frame) {
                return isWindowUpdate(frame) && frame.getInt(frame.readerIndex() + 5) == Http2CodecUtil.CONNECTION_STREAM_ID;
            }
        }

        @Override
        protected void handlerAdded0(ChannelHandlerContext ctx) {
            this.ctx = ctx;
        }

        Stream request(String uri) throws Exception {
            return request(uri, null);
        }

        /**
         * @param dependsOn if not null, the new stream depends exclusively on this one
         */
        Stream request(String uri, Stream dependsOn) throws Exception {
            Stream stream = new Stream();
            ctx.executor().submit(() -> {
                stream.frameStream = newStream();
                streams.put(stream.frameStream, stream);
                ctx.write(new DefaultHttp2HeadersFrame(new DefaultHttp2Headers()
                    .method(HttpMethod.GET.asciiName())
                    .scheme(HttpScheme.HTTP.name())
                    .authority("127.0.0.1:" + upstreamPort)
                    .path(uri)
                    .add("accept", accept(uri)), true).stream(stream.frameStream));
                if (dependsOn != null) {
                    ctx.write(new DefaultHttp2PriorityFrame(dependsOn.frameStream.id(), (short) 16, true).stream(stream.frameStream));
                }
                ctx.flush();
            }).get(5, TimeUnit.SECONDS);
            return stream;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2GoAwayFrame) {
                    goAwayReceived.set(true);
                    goAwayErrorCode.set(((Http2GoAwayFrame) msg).errorCode());
                } else if (msg instanceof Http2SettingsAckFrame) {
                    settingsAcks.incrementAndGet();
                }
                Stream stream = msg instanceof Http2StreamFrame && ((Http2StreamFrame) msg).stream() != null ? streams.get(((Http2StreamFrame) msg).stream()) : null;
                if (stream == null) {
                    return;
                }
                if (msg instanceof Http2HeadersFrame) {
                    stream.responseStarted.countDown();
                }
                if (msg instanceof Http2DataFrame) {
                    Http2DataFrame data = (Http2DataFrame) msg;
                    stream.dataBytes.addAndGet(data.content().readableBytes());
                    stream.unconsumedBytes += data.initialFlowControlledBytes();
                    if (stream.consumeAll) {
                        consume(stream);
                    }
                    if (data.isEndStream()) {
                        stream.endStream.set(true);
                        stream.ended.countDown();
                    }
                } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                    stream.endStream.set(true);
                    stream.ended.countDown();
                } else if (msg instanceof Http2ResetFrame) {
                    stream.resetNanos.set(System.nanoTime());
                    stream.resetErrorCode.set(((Http2ResetFrame) msg).errorCode());
                    stream.ended.countDown();
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        void onEventLoop(Runnable send) throws Exception {
            ctx.executor().submit(send).get(5, TimeUnit.SECONDS);
        }

        // runs on the client's event loop that often until the client is closed
        void every(long millis, Runnable send) {
            repeating.add(ctx.executor().scheduleAtFixedRate(send, millis, millis, TimeUnit.MILLISECONDS));
        }

        // SETTINGS_INITIAL_WINDOW_SIZE, which moves every stream's window and leaves the connection's as it is
        void initialWindow(int bytes) {
            ctx.writeAndFlush(new DefaultHttp2SettingsFrame(new Http2Settings().initialWindowSize(bytes)));
        }

        // written past the codec's flow controller, which would hold so small an update back
        void windowUpdate(Stream stream, int bytes) {
            Http2FrameCodec codec = channel.pipeline().get(Http2FrameCodec.class);
            ChannelHandlerContext codecCtx = channel.pipeline().context(codec);
            codec.encoder().frameWriter().writeWindowUpdate(codecCtx, stream.frameStream.id(), bytes, codecCtx.newPromise());
            codecCtx.flush();
        }

        void consumeReceived(Stream stream, int maxBytes) throws Exception {
            ctx.executor().submit(() -> consume(stream, maxBytes)).get(5, TimeUnit.SECONDS);
        }

        void consumeAll(Stream... toConsume) throws Exception {
            ctx.executor().submit(() -> {
                for (Stream stream : toConsume) {
                    stream.consumeAll = true;
                    consume(stream);
                }
            }).get(5, TimeUnit.SECONDS);
        }

        private void consume(Stream stream) {
            consume(stream, Integer.MAX_VALUE);
        }

        private void consume(Stream stream, int maxBytes) {
            if (stream.unconsumedBytes > 0 && stream.ended.getCount() > 0) {
                int consumed = Math.min(stream.unconsumedBytes, maxBytes);
                ctx.writeAndFlush(new DefaultHttp2WindowUpdateFrame(consumed).stream(stream.frameStream));
                stream.unconsumedBytes -= consumed;
            }
        }

        @Override
        public void close() {
            repeating.forEach(task -> task.cancel(false));
            channel.close();
        }
    }

    /**
     * {@code /fixed}: the fixed body with a {@code Content-Length}; {@code /small}: its first 1 MiB, likewise;
     * {@code /big}: every event 16 times in one write,
     * then the end of the stream; {@code /trickle}: about 100 KB of events, then nothing, with the connection left open.
     */
    @ChannelHandler.Sharable
    private static final class UpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String uri = request.uri();
            UPSTREAM_CHANNELS.put(uri, ctx.channel());
            if (uri.startsWith("/forward/fixed") || uri.startsWith("/forward/small")) {
                int length = uri.startsWith("/forward/small") ? SMALL_BODY_BYTES : fixedBody.length;
                DefaultFullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK, Unpooled.wrappedBuffer(fixedBody, 0, length));
                response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
                HttpUtil.setContentLength(response, length);
                ctx.writeAndFlush(response);
                return;
            }
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.write(head);
            if (uri.startsWith("/forward/trickle")) {
                for (int i = 0, written = 0; written < 100_000; i++) {
                    ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(events[i])));
                    written += events[i].length;
                }
                ctx.flush();
                return;
            }
            for (int i = 0; i < 16; i++) {
                for (byte[] event : events) {
                    ctx.write(new DefaultHttpContent(Unpooled.wrappedBuffer(event)));
                }
            }
            ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT);
        }
    }

    static final class StreamedEvents {

        // events of about 815 bytes with random payloads
        static byte[][] events(int size) {
            Random random = new Random(815);
            java.util.List<byte[]> chunks = new java.util.ArrayList<>();
            byte[] payload = new byte[600];
            for (int id = 0, total = 0; total < size; id++) {
                random.nextBytes(payload);
                byte[] event = ("id: " + id + "\ndata: " + java.util.Base64.getEncoder().encodeToString(payload) + "\n\n").getBytes(StandardCharsets.US_ASCII);
                chunks.add(event);
                total += event.length;
            }
            return chunks.toArray(new byte[0][]);
        }

        static int joinedLength(byte[][] events) {
            int length = 0;
            for (byte[] event : events) {
                length += event.length;
            }
            return length;
        }

        static byte[] dechunk(String response) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            int index = response.indexOf("\r\n\r\n") + 4;
            for (int size; (size = Integer.parseInt(response.substring(index, response.indexOf("\r\n", index)).trim(), 16)) > 0; ) {
                int start = response.indexOf("\r\n", index) + 2;
                byte[] chunk = response.substring(start, start + size).getBytes(StandardCharsets.ISO_8859_1);
                body.write(chunk, 0, chunk.length);
                index = start + size + 2;
            }
            return body.toByteArray();
        }
    }
}

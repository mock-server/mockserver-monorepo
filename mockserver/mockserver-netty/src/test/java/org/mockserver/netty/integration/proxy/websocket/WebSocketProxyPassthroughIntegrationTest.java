package org.mockserver.netty.integration.proxy.websocket;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.test.ClosedPort;

import java.net.URI;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.junit.Assert.assertNotNull;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Integration tests for WebSocket proxy passthrough: a real WebSocket client connects through MockServer (running as a
 * reverse proxy) to a real Netty WebSocket echo upstream, exercising text / binary / ping-pong / close relay plus the
 * event-log frame recording, over both plain and TLS upstreams.
 */
public class WebSocketProxyPassthroughIntegrationTest {

    private static final MockServerLogger MOCK_SERVER_LOGGER = new MockServerLogger(WebSocketProxyPassthroughIntegrationTest.class);

    private static WsEchoServer plainUpstream;
    private static WsEchoServer tlsUpstream;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startFixture() throws Exception {
        plainUpstream = new WsEchoServer(false);
        tlsUpstream = new WsEchoServer(true);

        Configuration configuration = configuration()
            .attemptToProxyIfNoMatchingExpectation(true);
        mockServer = new MockServer(configuration, 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopFixture() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (plainUpstream != null) {
            plainUpstream.stop();
        }
        if (tlsUpstream != null) {
            tlsUpstream.stop();
        }
    }

    @Test
    public void shouldRequireProxyAuthenticationBeforeWebSocketPassthrough() throws Exception {
        MockServer authenticatedProxy = new MockServer(configuration()
            .attemptToProxyIfNoMatchingExpectation(true)
            .proxyAuthenticationUsername("recorder")
            .proxyAuthenticationPassword("test-password"), 0);
        try {
            for (String credential : new String[]{null, "Basic wrong"}) {
                try (WsTestClient client = new WsTestClient(authenticatedProxy.getLocalPort(), false,
                    "127.0.0.1:" + plainUpstream.port, "/relay/auth")) {
                    if (credential != null) {
                        client.customHeaders.set("Proxy-Authorization", credential);
                    }
                    assertThat(client.attemptConnect(), is("HANDSHAKE_FAILED:407"));
                }
            }
            try (WsTestClient client = new WsTestClient(authenticatedProxy.getLocalPort(), false,
                "127.0.0.1:" + plainUpstream.port, "/relay/auth")) {
                client.customHeaders.set("Proxy-Authorization",
                    org.mockserver.authentication.ProxyAuthenticationValidator.expectedProxyAuthorizationHeaderValue("recorder", "test-password"));
                client.connect();
                client.sendText("authenticated");
                assertThat(client.awaitText(), is("echo:authenticated"));
                client.sendClose();
            }
        } finally {
            authenticatedProxy.stop();
        }
    }

    @Test
    public void shouldRelayTextFramesThroughPlainUpstream() throws Exception {
        mockServerClient.reset();
        try (WsTestClient client = new WsTestClient(mockServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/text")) {
            client.connect();

            client.sendText("hello");
            assertThat(client.awaitText(), is("echo:hello"));

            client.sendText("world");
            assertThat(client.awaitText(), is("echo:world"));

            client.sendClose();
        }

        // the upgrade exchange is recorded as a forwarded request; assert it is retrievable
        HttpRequest[] recorded = awaitRecorded("/relay/text");
        assertThat(recorded.length, greaterThanOrEqualTo(1));
        assertThat(recorded[0].getFirstHeader("Upgrade").toLowerCase(), containsString("websocket"));
    }

    @Test
    public void shouldRelayBinaryFramesThroughPlainUpstream() throws Exception {
        mockServerClient.reset();
        byte[] payload = new byte[]{1, 2, 3, 4, 5, 0, -1, 42};
        try (WsTestClient client = new WsTestClient(mockServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/binary")) {
            client.connect();
            client.sendBinary(payload);
            assertThat(client.awaitBinary(), is(payload));
            client.sendClose();
        }
        assertThat(awaitRecorded("/relay/binary").length, greaterThanOrEqualTo(1));
    }

    @Test
    public void shouldRelayPingPongThroughPlainUpstream() throws Exception {
        mockServerClient.reset();
        try (WsTestClient client = new WsTestClient(mockServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/ping")) {
            client.connect();
            client.sendPing(new byte[]{9, 9});
            byte[] pong = client.awaitPong();
            assertThat(pong, is(new byte[]{9, 9}));
            client.sendClose();
        }
    }

    @Test
    public void shouldRecordRelayedFramesInEventLog() throws Exception {
        mockServerClient.reset();
        try (WsTestClient client = new WsTestClient(mockServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/record")) {
            client.connect();
            client.sendText("one");
            assertThat(client.awaitText(), is("echo:one"));
            client.sendText("two");
            assertThat(client.awaitText(), is("echo:two"));
            client.sendClose();
        }

        // the transcript is flushed to the event log on connection close; poll until present
        HttpRequest[] recorded = awaitRecorded("/relay/record");
        assertThat(recorded.length, greaterThanOrEqualTo(1));

        // the recorded response carries the 101 status, a frame count header, and the frame transcript body
        org.mockserver.model.LogEventRequestAndResponse pair = null;
        for (int i = 0; i < 50 && pair == null; i++) {
            org.mockserver.model.LogEventRequestAndResponse[] pairs =
                mockServerClient.retrieveRecordedRequestsAndResponses(HttpRequest.request().withPath("/relay/record"));
            if (pairs.length >= 1 && pairs[0].getHttpResponse() != null) {
                pair = pairs[0];
            } else {
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }
        assertNotNull("expected a recorded request/response pair", pair);
        org.mockserver.model.HttpResponse recordedResponse = pair.getHttpResponse();
        assertThat(recordedResponse.getStatusCode(), is(101));
        assertThat(recordedResponse.getFirstHeader("x-mockserver-websocket-frames"), is(not("0")));
        String body = recordedResponse.getBodyAsString();
        assertThat(body, containsString("CLIENT_TO_UPSTREAM"));
        assertThat(body, containsString("UPSTREAM_TO_CLIENT"));
        assertThat(body, containsString("\"text\":\"one\""));
        assertThat(body, containsString("\"text\":\"echo:one\""));
    }

    @Test
    public void shouldRelayTextFramesThroughTlsUpstream() throws Exception {
        mockServerClient.reset();
        try (WsTestClient client = new WsTestClient(mockServer.getLocalPort(), true, "127.0.0.1:" + tlsUpstream.port, "/relay/tls")) {
            client.connect();
            client.sendText("secure");
            assertThat(client.awaitText(), is("echo:secure"));
            client.sendClose();
        }
        assertThat(awaitRecorded("/relay/tls").length, greaterThanOrEqualTo(1));
    }

    @Test
    public void shouldRelayThroughTlsUpstreamWithCertificateNotSignedByMockServerCa() throws Exception {
        mockServerClient.reset();
        // upstream TLS cert is a fresh self-signed cert — NOT signed by MockServer's own CA. This proves the relay
        // applies the forward-proxy trust manager (default ANY) rather than trusting only MockServer's CA (the old
        // createClientSslContext(false,false) branch, which would fail this handshake). The main mockServer uses the
        // default forwardProxyTLSX509CertificatesTrustManagerType=ANY.
        io.netty.handler.ssl.util.SelfSignedCertificate selfSigned = new io.netty.handler.ssl.util.SelfSignedCertificate();
        SslContext foreignServerCtx = io.netty.handler.ssl.SslContextBuilder
            .forServer(selfSigned.certificate(), selfSigned.privateKey()).build();
        WsEchoServer foreignTlsUpstream = new WsEchoServer(true, foreignServerCtx);
        try (WsTestClient client = new WsTestClient(mockServer.getLocalPort(), true, "127.0.0.1:" + foreignTlsUpstream.port, "/relay/foreign-tls")) {
            client.connect();
            client.sendText("trusted");
            assertThat(client.awaitText(), is("echo:trusted"));
            client.sendClose();
        } finally {
            foreignTlsUpstream.stop();
            selfSigned.delete();
        }
        assertThat(awaitRecorded("/relay/foreign-tls").length, greaterThanOrEqualTo(1));
    }

    @Test
    public void shouldBlockPassthroughToPrivateNetworkWhenConfigured() throws Exception {
        // forwardProxyBlockPrivateNetworks blocks loopback (and RFC1918 / cloud-metadata) targets — the same SSRF
        // guard every matched-forward handler enforces. The upstream is on 127.0.0.1 (loopback), so the relay must be
        // refused with a 502 rather than connecting. A separate MockServer is used so the block does not affect the
        // other tests (which legitimately relay to loopback upstreams).
        Configuration blocking = configuration()
            .attemptToProxyIfNoMatchingExpectation(true)
            .forwardProxyBlockPrivateNetworks(true);
        MockServer blockingServer = new MockServer(blocking, 0);
        try {
            try (WsTestClient client = new WsTestClient(blockingServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/blocked")) {
                String signal = client.attemptConnect();
                assertThat(signal, is("HANDSHAKE_FAILED:502"));
            }
        } finally {
            stopQuietly(blockingServer);
        }
    }

    @Test
    public void shouldReturn502WhenUpstreamRefusesConnection() throws Exception {
        mockServerClient.reset();
        // Host header points at a port with no listener — the upstream connect fails, so the client must get a 502
        // and must NOT hang waiting for a 101.
        try (WsTestClient client = new WsTestClient(mockServer.getLocalPort(), false, "127.0.0.1:" + ClosedPort.CLOSED_PORT, "/relay/refused")) {
            String signal = client.attemptConnect();
            assertThat(signal, is("HANDSHAKE_FAILED:502"));
        }
    }

    @Test
    public void shouldIsolateConcurrentPassthroughConnections() throws Exception {
        mockServerClient.reset();
        try (WsTestClient a = new WsTestClient(mockServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/iso-a");
             WsTestClient b = new WsTestClient(mockServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/iso-b")) {
            a.connect();
            b.connect();
            a.sendText("aaa");
            b.sendText("bbb");
            // each connection only sees its own upstream echo
            assertThat(a.awaitText(), is("echo:aaa"));
            assertThat(b.awaitText(), is("echo:bbb"));
            a.sendClose();
            b.sendClose();
        }
        // each connection is recorded independently under its own path
        assertThat(awaitRecorded("/relay/iso-a").length, greaterThanOrEqualTo(1));
        assertThat(awaitRecorded("/relay/iso-b").length, greaterThanOrEqualTo(1));
    }

    @Test
    public void shouldCloseIdleRelayWhenIdleTimeoutConfigured() throws Exception {
        Configuration idleConfig = configuration()
            .attemptToProxyIfNoMatchingExpectation(true)
            .webSocketProxyIdleTimeoutSeconds(1);
        MockServer idleServer = new MockServer(idleConfig, 0);
        try (WsTestClient client = new WsTestClient(idleServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/idle")) {
            client.connect();
            // send nothing — both directions idle; the relay must be reaped within ~1s (allow slack)
            assertThat("idle relay should be closed by the server", client.awaitClosed(6000), is(true));
        } finally {
            stopQuietly(idleServer);
        }
    }

    @Test
    public void shouldNotFlagTranscriptTruncatedWhenFrameRecordingDisabled() throws Exception {
        Configuration noFrames = configuration()
            .attemptToProxyIfNoMatchingExpectation(true)
            .webSocketProxyMaxRecordedFrames(0);
        MockServer noFramesServer = new MockServer(noFrames, 0);
        MockServerClient noFramesClient = new MockServerClient("127.0.0.1", noFramesServer.getLocalPort());
        try (WsTestClient client = new WsTestClient(noFramesServer.getLocalPort(), false, "127.0.0.1:" + plainUpstream.port, "/relay/norecord")) {
            client.connect();
            client.sendText("x");
            assertThat(client.awaitText(), is("echo:x"));
            client.sendClose();
        }
        org.mockserver.model.LogEventRequestAndResponse pair = null;
        for (int i = 0; i < 50 && pair == null; i++) {
            org.mockserver.model.LogEventRequestAndResponse[] pairs =
                noFramesClient.retrieveRecordedRequestsAndResponses(HttpRequest.request().withPath("/relay/norecord"));
            if (pairs.length >= 1 && pairs[0].getHttpResponse() != null) {
                pair = pairs[0];
            } else {
                TimeUnit.MILLISECONDS.sleep(100);
            }
        }
        assertNotNull("expected a recorded upgrade even when frame recording is disabled", pair);
        // the handshake is still recorded, with an honest 0 frame count and NOT flagged truncated
        assertThat(pair.getHttpResponse().getFirstHeader("x-mockserver-websocket-frames"), is("0"));
        assertThat(pair.getHttpResponse().getFirstHeader("x-mockserver-websocket-transcript-truncated"), is("false"));
        stopQuietly(noFramesClient);
        stopQuietly(noFramesServer);
    }

    private HttpRequest[] awaitRecorded(String path) throws InterruptedException {
        HttpRequest[] recorded = new HttpRequest[0];
        for (int i = 0; i < 50; i++) {
            recorded = mockServerClient.retrieveRecordedRequests(HttpRequest.request().withPath(path));
            if (recorded.length >= 1) {
                return recorded;
            }
            TimeUnit.MILLISECONDS.sleep(100);
        }
        return recorded;
    }

    // ---- test WebSocket client ---------------------------------------------------------------------------------

    /**
     * Minimal Netty WebSocket client that connects to {@code connectPort} but sends {@code hostHeader} as the WS Host,
     * so MockServer (in reverse-proxy mode) relays the upgrade to the upstream identified by that host.
     */
    private static final class WsTestClient implements AutoCloseable {
        private final int connectPort;
        private final boolean tls;
        private final String hostHeader;
        private final String path;
        private final io.netty.handler.codec.http.HttpHeaders customHeaders = new io.netty.handler.codec.http.DefaultHttpHeaders();
        private final NioEventLoopGroup group = new NioEventLoopGroup(1);
        private final BlockingQueue<Object> frames = new LinkedBlockingQueue<>();
        private Channel channel;
        private WebSocketClientHandshaker handshaker;
        private volatile Throwable failure;

        WsTestClient(int connectPort, boolean tls, String hostHeader, String path) {
            this.connectPort = connectPort;
            this.tls = tls;
            this.hostHeader = hostHeader;
            this.path = path;
        }

        void connect() throws Exception {
            String signal = attemptConnect();
            if (!"HANDSHAKE_COMPLETE".equals(signal)) {
                throw new IllegalStateException("handshake did not complete, got: " + signal + (failure != null ? " failure=" + failure : ""));
            }
        }

        /**
         * Connect and return the first handshake signal: {@code "HANDSHAKE_COMPLETE"} on a relayed 101, or
         * {@code "HANDSHAKE_FAILED:<status>"} when MockServer answers the upgrade with a non-101 (e.g. a 502 because
         * the upstream was unreachable or a blocked SSRF target). Never hangs: returns {@code "TIMEOUT"} after 15s.
         */
        String attemptConnect() throws Exception {
            URI uri = new URI((tls ? "wss" : "ws") + "://" + hostHeader + path);
            handshaker = WebSocketClientHandshakerFactory.newHandshaker(
                uri, WebSocketVersion.V13, null, true, customHeaders, 65536);
            final SslContext sslContext = tls
                ? io.netty.handler.ssl.SslContextBuilder.forClient().trustManager(InsecureTrustManagerFactory.INSTANCE).build()
                : null;
            io.netty.bootstrap.Bootstrap bootstrap = new io.netty.bootstrap.Bootstrap()
                .group(group)
                .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (sslContext != null) {
                            pipeline.addLast(sslContext.newHandler(ch.alloc(), "127.0.0.1", connectPort));
                        }
                        pipeline.addLast(new HttpClientCodecPlaceholder());
                        pipeline.addLast(new ClientFrameHandler());
                    }
                });
            channel = bootstrap.connect("127.0.0.1", connectPort).sync().channel();
            handshaker.handshake(channel);
            Object signal = frames.poll(15, TimeUnit.SECONDS);
            return signal == null ? "TIMEOUT" : signal.toString();
        }

        /** Wait up to {@code millis} for MockServer to close the relayed connection (e.g. idle reaping). */
        boolean awaitClosed(long millis) throws InterruptedException {
            return channel.closeFuture().await(millis, TimeUnit.MILLISECONDS);
        }

        void sendText(String text) {
            channel.writeAndFlush(new TextWebSocketFrame(text));
        }

        void sendBinary(byte[] bytes) {
            channel.writeAndFlush(new BinaryWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
        }

        void sendPing(byte[] bytes) {
            channel.writeAndFlush(new PingWebSocketFrame(Unpooled.wrappedBuffer(bytes)));
        }

        String awaitText() throws InterruptedException {
            Object frame = frames.poll(15, TimeUnit.SECONDS);
            assertNotNull("expected a text frame", frame);
            assertThat(frame, instanceOf(String.class));
            return (String) frame;
        }

        byte[] awaitBinary() throws InterruptedException {
            Object frame = frames.poll(15, TimeUnit.SECONDS);
            assertNotNull("expected a binary frame", frame);
            assertThat(frame, instanceOf(byte[].class));
            return (byte[]) frame;
        }

        byte[] awaitPong() throws InterruptedException {
            Object frame = frames.poll(15, TimeUnit.SECONDS);
            assertNotNull("expected a pong frame", frame);
            assertThat(frame, instanceOf(PongMarker.class));
            return ((PongMarker) frame).payload;
        }

        void sendClose() throws InterruptedException {
            if (channel != null && channel.isActive()) {
                channel.writeAndFlush(new CloseWebSocketFrame()).await(5, TimeUnit.SECONDS);
            }
        }

        @Override
        public void close() {
            if (channel != null) {
                channel.close();
            }
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        private final class ClientFrameHandler extends io.netty.channel.SimpleChannelInboundHandler<Object> {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) throws Exception {
                if (!handshaker.isHandshakeComplete()) {
                    io.netty.handler.codec.http.FullHttpResponse response = (io.netty.handler.codec.http.FullHttpResponse) msg;
                    try {
                        handshaker.finishHandshake(ctx.channel(), response);
                        frames.add("HANDSHAKE_COMPLETE");
                    } catch (Exception e) {
                        // non-101 upgrade answer (e.g. 502 upstream-unreachable / blocked SSRF target)
                        frames.add("HANDSHAKE_FAILED:" + response.status().code());
                    }
                    return;
                }
                if (msg instanceof TextWebSocketFrame) {
                    frames.add(((TextWebSocketFrame) msg).text());
                } else if (msg instanceof BinaryWebSocketFrame) {
                    io.netty.buffer.ByteBuf content = ((BinaryWebSocketFrame) msg).content();
                    byte[] bytes = new byte[content.readableBytes()];
                    content.getBytes(content.readerIndex(), bytes);
                    frames.add(bytes);
                } else if (msg instanceof PongWebSocketFrame) {
                    io.netty.buffer.ByteBuf content = ((PongWebSocketFrame) msg).content();
                    byte[] bytes = new byte[content.readableBytes()];
                    content.getBytes(content.readerIndex(), bytes);
                    frames.add(new PongMarker(bytes));
                } else if (msg instanceof CloseWebSocketFrame) {
                    ctx.close();
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                failure = cause;
                ctx.close();
            }
        }
    }

    /**
     * A tiny wrapper so the client handler can add HttpClientCodec + HttpObjectAggregator declaratively without
     * importing them at the call site.
     */
    private static final class HttpClientCodecPlaceholder extends ChannelInboundHandlerAdapter {
        @Override
        public void handlerAdded(ChannelHandlerContext ctx) {
            ctx.pipeline().addBefore(ctx.name(), "httpClientCodec", new io.netty.handler.codec.http.HttpClientCodec());
            ctx.pipeline().addBefore(ctx.name(), "httpAggregator", new HttpObjectAggregator(65536));
            ctx.pipeline().remove(this);
        }
    }

    private static final class PongMarker {
        private final byte[] payload;

        private PongMarker(byte[] payload) {
            this.payload = payload;
        }
    }

    // ---- upstream WebSocket echo server ------------------------------------------------------------------------

    /**
     * A real Netty WebSocket echo server: handshakes on any path, echoes text as {@code echo:<text>}, echoes binary
     * verbatim, replies to ping with pong, and closes on a close frame. Optionally TLS using a MockServer CA-signed
     * server certificate (which MockServer's forward client trusts).
     */
    private static final class WsEchoServer {
        private final NioEventLoopGroup bossGroup = new NioEventLoopGroup(1);
        private final NioEventLoopGroup workerGroup = new NioEventLoopGroup(2);
        private final Channel serverChannel;
        private final int port;

        WsEchoServer(boolean secure) throws Exception {
            this(secure, secure
                ? new NettySslContextFactory(configuration(), MOCK_SERVER_LOGGER, true).createServerSslContext()
                : null);
        }

        WsEchoServer(boolean secure, SslContext sslContextOverride) throws Exception {
            final SslContext sslContext = sslContextOverride;
            ServerBootstrap bootstrap = new ServerBootstrap()
                .group(bossGroup, workerGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ChannelPipeline pipeline = ch.pipeline();
                        if (sslContext != null) {
                            pipeline.addLast(sslContext.newHandler(ch.alloc()));
                        }
                        pipeline.addLast(new HttpServerCodec());
                        pipeline.addLast(new HttpObjectAggregator(65536));
                        pipeline.addLast(new EchoHandler(secure));
                    }
                });
            serverChannel = bootstrap.bind(new java.net.InetSocketAddress("127.0.0.1", 0)).sync().channel();
            port = ((java.net.InetSocketAddress) serverChannel.localAddress()).getPort();
        }

        void stop() {
            serverChannel.close();
            bossGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
            workerGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }

        private static final class EchoHandler extends io.netty.channel.SimpleChannelInboundHandler<Object> {
            private final boolean secure;
            private WebSocketServerHandshaker handshaker;

            private EchoHandler(boolean secure) {
                this.secure = secure;
            }

            @Override
            protected void channelRead0(ChannelHandlerContext ctx, Object msg) {
                if (msg instanceof io.netty.handler.codec.http.FullHttpRequest) {
                    io.netty.handler.codec.http.FullHttpRequest request = (io.netty.handler.codec.http.FullHttpRequest) msg;
                    String scheme = secure ? "wss" : "ws";
                    WebSocketServerHandshakerFactory factory = new WebSocketServerHandshakerFactory(
                        scheme + "://" + request.headers().get("Host") + request.uri(), null, true, 65536);
                    handshaker = factory.newHandshaker(request);
                    if (handshaker == null) {
                        WebSocketServerHandshakerFactory.sendUnsupportedVersionResponse(ctx.channel());
                    } else {
                        handshaker.handshake(ctx.channel(), request);
                    }
                } else if (msg instanceof TextWebSocketFrame) {
                    ctx.writeAndFlush(new TextWebSocketFrame("echo:" + ((TextWebSocketFrame) msg).text()));
                } else if (msg instanceof BinaryWebSocketFrame) {
                    ctx.writeAndFlush(new BinaryWebSocketFrame(((BinaryWebSocketFrame) msg).content().retainedDuplicate()));
                } else if (msg instanceof PingWebSocketFrame) {
                    ctx.writeAndFlush(new PongWebSocketFrame(((PingWebSocketFrame) msg).content().retainedDuplicate()));
                } else if (msg instanceof CloseWebSocketFrame) {
                    handshaker.close(ctx.channel(), ((CloseWebSocketFrame) msg).retainedDuplicate());
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                ctx.close();
            }
        }
    }
}

package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;

/**
 * Real-QUIC end-to-end integration test for the data-plane authentication gate over HTTP/3.
 *
 * <p>This is the HTTP/3 counterpart of {@code DataPlaneAuthenticationIntegrationTest} (HTTP/1.1/2). It
 * proves that the gate added to {@code Http3MockServerHandler} actually rejects unauthenticated mocked
 * requests over QUIC (the path that previously fail-OPEN), serves them with valid credentials, and
 * still lets the control plane through without data-plane credentials.
 *
 * <p>Docker/QUIC-gated exactly like the other Http3 integration tests: {@link #assumeQuicAvailable()}
 * skips gracefully when the native QUIC (BoringSSL) transport is unavailable.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3DataPlaneAuthenticationIntegrationTest {

    private static final String BEARER_TOKEN = "bearer-token-123";
    private static final String API_KEY_HEADER = "x-api-key";
    private static final String API_KEY_VALUE = "key-abc";
    private static final String BASIC_USER = "user";
    private static final String BASIC_PASS = "secret";

    private MockServer mockServer;
    private NioEventLoopGroup clientGroup;

    @Before
    public void setUp() {
        assumeQuicAvailable();
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername(BASIC_USER)
            .dataPlaneBasicAuthenticationPassword(BASIC_PASS)
            .dataPlaneBasicAuthenticationRealm("data-plane-realm")
            .dataPlaneBearerAuthenticationToken(BEARER_TOKEN)
            .dataPlaneApiKeyAuthenticationHeader(API_KEY_HEADER)
            .dataPlaneApiKeyAuthenticationValue(API_KEY_VALUE)
            .http3MaxIdleTimeout(30000L)
            .attemptToProxyIfNoMatchingExpectation(false);
        mockServer = startWithHttp3(configuration);

        // Seed a data-plane mock via the CONTROL PLANE over HTTP/3 — this must succeed with NO
        // data-plane credentials, proving the control plane is reachable while the data plane is locked.
        Http3ResponseCapture create = sendH3("PUT", "/mockserver/expectation",
            "{\"httpRequest\":{\"path\":\"/mocked\"},\"httpResponse\":{\"statusCode\":200,\"body\":\"mocked-body\"}}",
            new ConcurrentHashMap<>());
        assertThat("control-plane expectation creation over HTTP/3 must succeed without data-plane creds",
            create.status, is("201"));
    }

    @After
    public void tearDown() {
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully();
            clientGroup = null;
        }
    }

    @Test
    public void shouldRejectDataPlaneRequestOverHttp3WithNoCredentials() {
        Http3ResponseCapture result = sendH3("GET", "/mocked", null, new ConcurrentHashMap<>());

        assertThat(result.status, is("401"));
        assertThat(result.headers.get("www-authenticate"), containsString("Basic realm=\"data-plane-realm\""));
        assertThat(result.body, is("Unauthorized for data plane"));
    }

    @Test
    public void shouldRejectDataPlaneRequestOverHttp3WithWrongBearerToken() {
        Map<String, String> headers = new ConcurrentHashMap<>();
        headers.put("authorization", "Bearer wrong");
        Http3ResponseCapture result = sendH3("GET", "/mocked", null, headers);

        assertThat(result.status, is("401"));
    }

    @Test
    public void shouldServeMockedResponseOverHttp3WithValidBearerToken() {
        Map<String, String> headers = new ConcurrentHashMap<>();
        headers.put("authorization", "Bearer " + BEARER_TOKEN);
        Http3ResponseCapture result = sendH3("GET", "/mocked", null, headers);

        assertThat(result.status, is("200"));
        assertThat(result.body, is("mocked-body"));
    }

    @Test
    public void shouldServeMockedResponseOverHttp3WithValidApiKey() {
        Map<String, String> headers = new ConcurrentHashMap<>();
        headers.put(API_KEY_HEADER, API_KEY_VALUE);
        Http3ResponseCapture result = sendH3("GET", "/mocked", null, headers);

        assertThat(result.status, is("200"));
        assertThat(result.body, is("mocked-body"));
    }

    @Test
    public void shouldServeMockedResponseOverHttp3WithValidBasicCredentials() {
        Map<String, String> headers = new ConcurrentHashMap<>();
        headers.put("authorization", "Basic " + Base64.getEncoder()
            .encodeToString((BASIC_USER + ':' + BASIC_PASS).getBytes(StandardCharsets.UTF_8)));
        Http3ResponseCapture result = sendH3("GET", "/mocked", null, headers);

        assertThat(result.status, is("200"));
        assertThat(result.body, is("mocked-body"));
    }

    @Test
    public void shouldNotGateControlPlaneOverHttp3WithoutDataPlaneCredentials() {
        // The data-plane auth gate must NOT 401 requests the control plane serves. Two probes:
        //
        // 1) PUT /mockserver/clear is a real control-plane operation dispatched by httpState.handle —
        //    it must succeed (200) with NO data-plane credentials, proving an operator can administer a
        //    locked-down server over HTTP/3.
        Http3ResponseCapture clear = sendH3("PUT", "/mockserver/clear", null, new ConcurrentHashMap<>());
        assertThat("control-plane /clear must be reachable without data-plane creds", clear.status, is("200"));

        // 2) PUT /mockserver/status is served by an HTTP/1.1/HTTP/2-only branch of HttpRequestHandler, not
        //    by httpState.handle, so over HTTP/3 it reaches expectation matching and is gated like any
        //    other mocked endpoint rather than exempted by its /mockserver prefix.
        Http3ResponseCapture status = sendH3("PUT", "/mockserver/status", null, new ConcurrentHashMap<>());
        assertThat("/mockserver/status is not served by the HTTP/3 control plane, so it is gated",
            status.status, is("401"));
    }

    @Test
    public void shouldRefusePathsOnlySharingControlPlanePrefixOverHttp3WithoutCredentials() {
        Http3ResponseCapture catchAll = sendH3("PUT", "/mockserver/expectation",
            "{\"httpRequest\":{},\"httpResponse\":{\"statusCode\":200,\"body\":\"catch-all-body\"}}",
            new ConcurrentHashMap<>());
        assertThat(catchAll.status, is("201"));

        for (String[] methodAndPath : new String[][]{
            {"GET", "/mockserverfoo"}, {"GET", "/mockserver/nope"}, {"GET", "/mockserver"},
            {"PUT", "/mockserver/status"}, {"GET", "/mockserver/ready"}, {"GET", "/ready"}}) {
            Http3ResponseCapture refused = sendH3(methodAndPath[0], methodAndPath[1], null, new ConcurrentHashMap<>());
            assertThat(methodAndPath[0] + " " + methodAndPath[1] + " status", refused.status, is("401"));
            assertThat(methodAndPath[0] + " " + methodAndPath[1] + " body", refused.body, is("Unauthorized for data plane"));
        }

        Map<String, String> headers = new ConcurrentHashMap<>();
        headers.put("authorization", "Bearer " + BEARER_TOKEN);
        Http3ResponseCapture authenticated = sendH3("GET", "/mockserverfoo", null, headers);
        assertThat(authenticated.status, is("200"));
        assertThat(authenticated.body, is("catch-all-body"));

        Http3ResponseCapture clear = sendH3("PUT", "/mockserver/clear", "{\"path\":\"/nothing\"}", new ConcurrentHashMap<>());
        assertThat("the control plane is still served without data-plane credentials", clear.status, is("200"));
    }

    // ---- helpers (mirrors Http3McpIntegrationTest harness) ----

    private Http3ResponseCapture sendH3(String method, String path, String body, Map<String, String> headers) {
        try {
            byte[] bodyBytes = body != null ? body.getBytes(StandardCharsets.UTF_8) : null;
            if (bodyBytes != null && !headers.containsKey("content-type")) {
                headers.put("content-type", "application/json");
            }
            return doSendHttp3Request(mockServer.getHttp3Port(), method, path, bodyBytes, headers);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    static class Http3ResponseCapture {
        final String status;
        final String body;
        final Map<String, String> headers;

        Http3ResponseCapture(String status, String body, Map<String, String> headers) {
            this.status = status;
            this.body = body;
            this.headers = headers;
        }
    }

    private Http3ResponseCapture doSendHttp3Request(
        int port, String method, String path, byte[] body, Map<String, String> extraHeaders
    ) throws Exception {
        if (clientGroup != null) {
            clientGroup.shutdownGracefully().sync();
        }
        clientGroup = new NioEventLoopGroup(1);

        QuicSslContext clientSslContext = QuicSslContextBuilder.forClient()
            .trustManager(InsecureTrustManagerFactory.INSTANCE)
            .applicationProtocols(Http3.supportedApplicationProtocols())
            .build();

        Channel clientChannel = new Bootstrap()
            .group(clientGroup)
            .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
            .handler(Http3.newQuicClientCodecBuilder()
                .sslContext(clientSslContext)
                .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                .initialMaxData(10000000)
                .initialMaxStreamDataBidirectionalLocal(1000000)
                .initialMaxStreamsBidirectional(100)
                .build())
            .bind(0)
            .sync()
            .channel();

        QuicChannel quicChannel = QuicChannel.newBootstrap(clientChannel)
            .handler(new Http3ClientConnectionHandler())
            .remoteAddress(new InetSocketAddress("127.0.0.1", port))
            .connect()
            .get(15, TimeUnit.SECONDS);

        BlockingQueue<String> statusQueue = new LinkedBlockingQueue<>();
        BlockingQueue<String> bodyQueue = new LinkedBlockingQueue<>();
        Map<String, String> responseHeaders = new ConcurrentHashMap<>();

        QuicStreamChannel requestStream = Http3.newRequestStream(
            quicChannel,
            new Http3RequestStreamInboundHandler() {
                private final StringBuilder bodyBuilder = new StringBuilder();

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame headersFrame) {
                    CharSequence status = headersFrame.headers().status();
                    statusQueue.offer(status != null ? status.toString() : "null");
                    headersFrame.headers().forEach(entry -> {
                        String name = entry.getKey().toString();
                        if (!name.startsWith(":")) {
                            responseHeaders.put(name.toLowerCase(), entry.getValue().toString());
                        }
                    });
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame dataFrame) {
                    ByteBuf content = dataFrame.content();
                    bodyBuilder.append(content.toString(StandardCharsets.UTF_8));
                    content.release();
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    bodyQueue.offer(bodyBuilder.toString());
                    ctx.close();
                }
            }
        ).sync().getNow();

        DefaultHttp3HeadersFrame requestHeaders = new DefaultHttp3HeadersFrame();
        requestHeaders.headers().method(method);
        requestHeaders.headers().path(path);
        requestHeaders.headers().scheme("https");
        requestHeaders.headers().authority("127.0.0.1:" + port);

        if (extraHeaders != null) {
            for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                requestHeaders.headers().add(entry.getKey(), entry.getValue());
            }
        }

        if (body != null && body.length > 0) {
            requestStream.writeAndFlush(requestHeaders).sync();
            requestStream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(body)))
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        } else {
            requestStream.writeAndFlush(requestHeaders)
                .addListener(QuicStreamChannel.SHUTDOWN_OUTPUT)
                .sync();
        }

        String status = statusQueue.poll(20, TimeUnit.SECONDS);
        String responseBody = bodyQueue.poll(20, TimeUnit.SECONDS);

        quicChannel.close().sync();
        clientChannel.close().sync();

        return new Http3ResponseCapture(
            status != null ? status : "null",
            responseBody != null ? responseBody : "",
            responseHeaders
        );
    }

    private static void assumeQuicAvailable() {
        try {
            boolean available = io.netty.handler.codec.quic.Quic.isAvailable();
            Assume.assumeTrue(
                "native QUIC transport not available on this platform -- skipping HTTP/3 data-plane auth test",
                available
            );
        } catch (Throwable t) {
            Assume.assumeNoException(
                "native QUIC transport failed to load -- skipping HTTP/3 data-plane auth test",
                t
            );
        }
    }
}

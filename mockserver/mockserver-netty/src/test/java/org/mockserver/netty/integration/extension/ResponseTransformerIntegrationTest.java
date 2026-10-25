package org.mockserver.netty.integration.extension;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http.QueryStringDecoder;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.extension.DataPlaneHooks;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.lifecycle.HttpStateTestAccess;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;
import org.mockserver.netty.integration.PipelinedHttp1Client;
import org.mockserver.netty.integration.PipelinedHttp1Client.Response;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.netty.integration.PipelinedHttp1Client.get;
import static org.mockserver.netty.integration.PipelinedHttp1Client.rawRequest;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An extension response transformer on a Netty server, over HTTP/1.1 and HTTP/2: it sees each data-plane response
 * before CORS and the default headers are added, including MockServer's own {@code 404}, but never a failure
 * response, an API response or the data-plane CORS preflight; a slow transformer keeps pipelined responses in order,
 * a stream reset while it runs leaves the connection serving, and a streamed response it fails on is answered
 * {@code 500} with its upstream closed.
 */
public class ResponseTransformerIntegrationTest {

    private static final Map<String, AtomicInteger> CALLS = new ConcurrentHashMap<>();
    private static final Map<String, Channel> UPSTREAM_CHANNELS = new ConcurrentHashMap<>();
    private static volatile CountDownLatch blockedEntered = new CountDownLatch(1);
    private static volatile CountDownLatch blockedRelease = new CountDownLatch(1);

    private static EventLoopGroup group;
    private static Channel upstreamChannel;
    private static int upstreamPort;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static MockServer upstreamServer;
    private static MockServerClient upstreamClient;

    /**
     * Counts its calls by path, and by path: throws ({@code /throws}, or any streamed response under
     * {@code /stream/fail}), waits ({@code /slow}, {@code /blocked}), replaces the response ({@code /cors}), or adds
     * {@code x-transformed}, {@code x-generated} and {@code x-streamed}.
     */
    public static class Transformer implements ResponseTransformer {
        @Override
        public String name() {
            return "integration-transformer";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception {
            String path = request.getPath().getValue();
            CALLS.computeIfAbsent(path, ignored -> new AtomicInteger()).incrementAndGet();
            if (path.equals("/throws") || context.isStreamed() && path.startsWith("/stream/fail")) {
                throw new IllegalStateException("transformer failed on purpose");
            }
            if (path.equals("/slow")) {
                TimeUnit.MILLISECONDS.sleep(500);
            }
            if (path.equals("/blocked")) {
                blockedEntered.countDown();
                blockedRelease.await(10, TimeUnit.SECONDS);
            }
            if (path.equals("/cors")) {
                return response().withStatusCode(200).withBody("replaced");
            }
            if (path.equals("/forwarded")) {
                return response.withBody(response.getBodyAsString() + "-TRANSFORMED");
            }
            return response
                .withHeader("x-transformed", path)
                .withHeader("x-generated", String.valueOf(context.isGenerated()))
                .withHeader("x-streamed", String.valueOf(context.isStreamed()));
        }
    }

    @BeforeClass
    public static void startServers() throws Exception {
        group = new NioEventLoopGroup(2);
        upstreamChannel = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec(), new HttpObjectAggregator(1024 * 1024), new StreamingUpstream());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();
        upstreamServer = new MockServer(configuration().logLevel("WARN"));
        upstreamClient = new MockServerClient("127.0.0.1", upstreamServer.getLocalPort());
        mockServer = new MockServer(configuration()
            .attemptToProxyIfNoMatchingExpectation(false)
            .enableCORSForAllResponses(true)
            .streamingResponsesEnabled(true)
            .extensionDataPlaneTimeoutMillis(5_000L)
            .extensions(Transformer.class));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        stopQuietly(upstreamClient);
        stopQuietly(upstreamServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (group != null) {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Before
    public void reset() {
        mockServerClient.reset();
        CALLS.clear();
        UPSTREAM_CHANNELS.clear();
        mockServerClient.when(request().withPath("/(mocked|throws|slow|fast|blocked|cors)")).respond(response().withBody("mocked body"));
        mockServerClient.when(request().withPath("/stream/.*")).forward(forward().withHost("127.0.0.1").withPort(upstreamPort));
        upstreamClient.reset();
        upstreamClient.when(request().withPath("/forwarded")).respond(response().withBody("abc"));
        mockServerClient.when(request().withPath("/forwarded")).forward(forward().withHost("127.0.0.1").withPort(upstreamServer.getLocalPort()));
    }

    private static int calls(String path) {
        AtomicInteger calls = CALLS.get(path);
        return calls != null ? calls.get() : 0;
    }

    private static DataPlaneHooks hooks() {
        return HttpStateTestAccess.httpState(mockServer).dataPlaneHooks();
    }

    @Test
    public void shouldTransformAMockedResponse() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/mocked"));
            Response response = client.readResponse();

            assertThat(response.status, is(200));
            assertThat(response.body(), is("mocked body"));
            assertThat(response.header("x-transformed"), is("/mocked"));
            assertThat(response.header("x-generated"), is("false"));
        }
    }

    @Test
    public void shouldTransformTheGeneratedNotFoundResponse() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/nothing-matches"));
            Response response = client.readResponse();

            assertThat(response.status, is(404));
            assertThat(response.header("x-transformed"), is("/nothing-matches"));
            assertThat(response.header("x-generated"), is("true"));
        }
    }

    @Test
    public void shouldNotTransformAFailureResponse() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/throws"));
            Response response = client.readResponse();

            assertThat(response.status, is(500));
            assertThat(response.body(), containsString("extension integration-transformer responseTransformer failed (threw)"));
            assertThat(response.header("x-transformed"), is(nullValue()));
            assertThat("the transformer is not run again on its own failure response", calls("/throws"), is(1));
        }
    }

    @Test
    public void shouldAddCorsHeadersAfterTheTransformer() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(rawRequest("GET", "/cors", "Origin: https://example.com"));
            Response response = client.readResponse();

            assertThat(response.body(), is("replaced"));
            assertThat("CORS is added to the response the transformer returned", response.header("access-control-allow-origin"), not(nullValue()));
        }
    }

    @Test
    public void shouldNotTransformTheDataPlaneCorsPreflight() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(rawRequest("OPTIONS", "/preflight", "Origin: https://example.com", "Access-Control-Request-Method: PUT"));
            Response response = client.readResponse();

            assertThat(response.status, is(200));
            assertThat(response.header("x-transformed"), is(nullValue()));
            assertThat(calls("/preflight"), is(0));
        }
    }

    @Test
    public void shouldNotTransformAnApiResponse() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(rawRequest("OPTIONS", "/mockserver/expectation", "Origin: https://example.com", "Access-Control-Request-Method: PUT"));
            Response response = client.readResponse();

            assertThat(response.status, is(200));
            assertThat(response.header("x-transformed"), is(nullValue()));
            assertThat(calls("/mockserver/expectation"), is(0));
        }
    }

    @Test
    public void shouldKeepPipelinedResponsesInOrderBehindASlowTransformer() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/slow"), get("/fast"), get("/nothing-matches"));
            List<Response> responses = client.readResponses(false, false, false);

            assertThat(responses.get(0).header("x-transformed"), is("/slow"));
            assertThat(responses.get(1).header("x-transformed"), is("/fast"));
            assertThat(responses.get(2).status, is(404));
            assertThat(responses.get(2).header("x-transformed"), is("/nothing-matches"));
        }
    }

    @Test
    public void shouldKeepServingTheConnectionWhenAStreamIsResetDuringTheTransformer() throws Exception {
        blockedEntered = new CountDownLatch(1);
        blockedRelease = new CountDownLatch(1);
        long droppedBefore = hooks().droppedWriteCount();
        try (Http2TestClient client = Http2TestClient.h2c(group, mockServer.getLocalPort())) {
            Http2TestClient.Exchange blocked = client.send(h2Request("/blocked"), true);
            assertThat("the transformer was called", blockedEntered.await(10, TimeUnit.SECONDS), is(true));
            blocked.reset(Http2Error.CANCEL);
            blockedRelease.countDown();

            Http2TestClient.Exchange next = client.send(h2Request("/mocked"), true);
            assertThat(next.status(), is(200));
            assertThat(next.header("x-transformed"), is("/mocked"));
            assertThat(next.body(), is("mocked body"));
            assertThat(client.isOpen(), is(true));
        } finally {
            blockedRelease.countDown();
        }
        assertThat("a write to a reset stream is not a dropped second response", hooks().droppedWriteCount(), is(droppedBefore));
    }

    @Test
    public void shouldFrameAForwardedResponseWhoseBodyTheTransformerChangedOverHttp1KeepAlive() throws Exception {
        try (PipelinedHttp1Client client = PipelinedHttp1Client.plain(mockServer.getLocalPort())) {
            client.pipeline(get("/forwarded"), get("/forwarded"));
            List<Response> responses = client.readResponses(false, false);

            for (Response response : responses) {
                assertThat(response.status, is(200));
                assertThat(response.body(), is("abc-TRANSFORMED"));
                assertThat(response.header("content-length"), is("15"));
            }
            client.pipeline(get("/mocked"));
            assertThat("the connection is still in step", client.readResponse().body(), is("mocked body"));
        }
    }

    @Test
    public void shouldFrameAForwardedResponseWhoseBodyTheTransformerChangedOverHttp2() throws Exception {
        try (Http2TestClient client = Http2TestClient.h2c(group, mockServer.getLocalPort())) {
            Http2TestClient.Exchange forwarded = client.send(h2Request("/forwarded"), true);
            assertThat(forwarded.status(), is(200));
            assertThat(forwarded.body(), is("abc-TRANSFORMED"));

            Http2TestClient.Exchange next = client.send(h2Request("/mocked"), true);
            assertThat(next.body(), is("mocked body"));
        }
    }

    @Test
    public void shouldTransformTheHeadOfAStreamedResponse() throws Exception {
        try (Socket socket = connect("/stream/ok")) {
            String received = readUntil(socket, "data: first");

            assertThat(received, startsWith("HTTP/1.1 200"));
            assertThat(received, containsString("x-streamed: true"));
            assertThat(received, containsString("data: first"));
        }
    }

    @Test
    public void shouldCloseTheUpstreamOfAStreamedResponseTheTransformerFailsOn() throws Exception {
        try (Socket socket = connect("/stream/fail")) {
            String received = readUntil(socket, "\r\n\r\n");

            assertThat(received, startsWith("HTTP/1.1 500"));
            assertThat(received, not(containsString("data: first")));
        }
        for (int i = 0; i < 500 && !UPSTREAM_CHANNELS.containsKey("/stream/fail"); i++) {
            TimeUnit.MILLISECONDS.sleep(10);
        }
        assertThat("the upstream was called", UPSTREAM_CHANNELS.containsKey("/stream/fail"), is(true));
        assertThat("MockServer closed the upstream it will not read", UPSTREAM_CHANNELS.get("/stream/fail").closeFuture().await(8, TimeUnit.SECONDS), is(true));
    }

    private static Http2Headers h2Request(String path) {
        return new DefaultHttp2Headers().method("GET").scheme("http").authority("127.0.0.1:" + mockServer.getLocalPort()).path(path);
    }

    private static Socket connect(String path) throws IOException {
        Socket socket = new Socket("127.0.0.1", mockServer.getLocalPort());
        socket.setSoTimeout((int) TimeUnit.SECONDS.toMillis(20));
        socket.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: 127.0.0.1:" + upstreamPort + "\r\nAccept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
        return socket;
    }

    private static String readUntil(Socket socket, String text) throws IOException {
        StringBuilder received = new StringBuilder();
        InputStream in = socket.getInputStream();
        byte[] buffer = new byte[4096];
        for (int read; !received.toString().contains(text) && (read = in.read(buffer)) != -1; ) {
            received.append(new String(buffer, 0, read, StandardCharsets.ISO_8859_1));
        }
        return received.toString();
    }

    /**
     * Answers {@code text/event-stream} with a first event and then nothing, leaving the connection open, so only
     * MockServer can close it.
     */
    @ChannelHandler.Sharable
    private static final class StreamingUpstream extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            UPSTREAM_CHANNELS.put(new QueryStringDecoder(request.uri()).path(), ctx.channel());
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.write(head);
            ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("data: first\n\n", StandardCharsets.US_ASCII)));
        }
    }
}

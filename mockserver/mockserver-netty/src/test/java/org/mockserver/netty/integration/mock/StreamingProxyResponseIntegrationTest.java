package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.*;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.fixture.SseAwareExpectationConverter;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Body;
import org.mockserver.model.Header;
import org.mockserver.model.HttpOverrideForwardedRequest;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.verify.VerificationTimes;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpOverrideForwardedRequest.forwardOverriddenRequest;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Tests for the streaming response relay feature. When MockServer proxies a streaming
 * response (Server-Sent Events or chunked with no Content-Length), chunks should be
 * relayed incrementally to the client rather than being fully buffered.
 */
public class StreamingProxyResponseIntegrationTest {

    private static MockServerClient mockServerClient;
    private static int mockServerPort;
    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static int upstreamPort;

    // Track the original streaming config so we can restore it
    private static boolean originalStreamingEnabled;
    private static String originalProxyRemoteHost;
    private static Integer originalProxyRemotePort;

    @BeforeClass
    public static void startServers() throws Exception {
        // Remember original config
        originalStreamingEnabled = ConfigurationProperties.streamingResponsesEnabled();
        originalProxyRemoteHost = ConfigurationProperties.proxyRemoteHost();
        originalProxyRemotePort = ConfigurationProperties.proxyRemotePort();

        // Enable streaming responses
        ConfigurationProperties.streamingResponsesEnabled(true);
        ConfigurationProperties.maxStreamingCaptureBytes(256);

        // Start a bare Netty HTTP server as the "upstream" that sends chunked responses
        upstreamGroup = new NioEventLoopGroup(1);
        ServerBootstrap b = new ServerBootstrap();
        b.group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new ChunkedUpstreamHandler());
                }
            });
        upstreamChannel = b.bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        upstreamPort = ((InetSocketAddress) upstreamChannel.localAddress()).getPort();

        // Start MockServer configured to forward to the upstream
        ConfigurationProperties.proxyRemoteHost("127.0.0.1");
        ConfigurationProperties.proxyRemotePort(upstreamPort);
        mockServerPort = new MockServer().getLocalPort();
        mockServerClient = new MockServerClient("localhost", mockServerPort);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully();
        }
        // Restore original config
        ConfigurationProperties.streamingResponsesEnabled(originalStreamingEnabled);
        ConfigurationProperties.proxyRemoteHost(originalProxyRemoteHost != null ? originalProxyRemoteHost : "");
        ConfigurationProperties.proxyRemotePort(originalProxyRemotePort);
        ConfigurationProperties.maxStreamingCaptureBytes(262144);
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
    }

    /**
     * Polls until the condition is true or the deadline (5 seconds) is exceeded.
     */
    private void pollUntilTrue(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5000;
        while (!condition.getAsBoolean()) {
            if (System.currentTimeMillis() > deadline) {
                throw new AssertionError("Timed out waiting for condition to become true");
            }
            Thread.sleep(50);
        }
    }

    /**
     * A simple upstream handler that responds with a chunked text/event-stream or plain
     * chunked response depending on the request path:
     * - /sse: Server-Sent Events with 3 events, each delayed 100ms
     * - /chunked: chunked response with 3 chunks (not SSE)
     * - /normal: a normal non-streaming response
     * - /large-sse: Server-Sent Events that exceed maxStreamingCaptureBytes
     * - /close-mid-stream: starts sending then closes the connection
     * - /sse-endless: Server-Sent Events, one every 100ms for 10 seconds, so only the client ends it
     * - /sse-endless-close-delimited: as /sse-endless, with neither Content-Length nor chunked encoding
     * - /binary-stream: binary data streamed as application/octet-stream
     * - /sse-cut-short: three Server-Sent Events 150ms apart, then part of a fourth, then a close with no last chunk
     */
    @ChannelHandler.Sharable
    static class ChunkedUpstreamHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            String path = request.uri();
            if ("/sse".equals(path)) {
                sendSseResponse(ctx);
            } else if ("/chunked".equals(path)) {
                sendChunkedResponse(ctx);
            } else if ("/normal".equals(path)) {
                sendNormalResponse(ctx);
            } else if ("/large-sse".equals(path)) {
                sendLargeSseResponse(ctx);
            } else if ("/close-mid-stream".equals(path)) {
                sendAndCloseResponse(ctx);
            } else if ("/sse-endless".equals(path)) {
                sendEndlessSseResponse(ctx, true);
            } else if ("/sse-endless-close-delimited".equals(path)) {
                sendEndlessSseResponse(ctx, false);
            } else if ("/binary-stream".equals(path)) {
                sendBinaryStreamResponse(ctx);
            } else if ("/sse-cut-short".equals(path)) {
                sendCutShortSseResponse(ctx);
            } else if ("/sse-long-pause".equals(path)) {
                sendLongPauseSseResponse(ctx);
            } else if ("/codex-stream".equals(path)) {
                sendNoContentTypeSseResponse(ctx);
            } else if ("/codex-binary-stream".equals(path)) {
                sendNoContentTypeBinaryStreamResponse(ctx);
            } else {
                DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer("Not Found", StandardCharsets.UTF_8)
                );
                HttpUtil.setContentLength(resp, resp.content().readableBytes());
                ctx.writeAndFlush(resp).addListener(ChannelFutureListener.CLOSE);
            }
        }

        private void sendSseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            head.headers().set(HttpHeaderNames.CONNECTION, "keep-alive");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            String[] events = {
                "data: event1\n\n",
                "data: event2\n\n",
                "data: event3\n\n"
            };

            scheduleChunks(ctx, events, 0, 100, true);
        }

        private void sendEndlessSseResponse(ChannelHandlerContext ctx, boolean chunked) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            if (chunked) {
                HttpUtil.setTransferEncodingChunked(head, true);
            }
            ctx.writeAndFlush(head);

            String[] events = new String[100];
            for (int i = 0; i < events.length; i++) {
                events[i] = "data: event" + i + "\n\n";
            }
            scheduleChunks(ctx, events, 0, 100, true);
        }

        private void sendCutShortSseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            String[] chunks = {"data: event0\n\n", "data: event1\n\n", "data: event2\n\n", "data: {\"cut\":"};
            scheduleChunksThenClose(ctx, chunks, 0);
        }

        private void scheduleChunksThenClose(ChannelHandlerContext ctx, String[] chunks, int index) {
            if (index >= chunks.length) {
                ctx.close();
                return;
            }
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(chunks[index], StandardCharsets.UTF_8)))
                        .addListener(f -> {
                            if (f.isSuccess()) {
                                scheduleChunksThenClose(ctx, chunks, index + 1);
                            }
                        });
                }
            }, index == 0 ? 0 : 150, TimeUnit.MILLISECONDS);
        }

        private void sendChunkedResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            String[] chunks = {
                "{\"chunk\":1}",
                "{\"chunk\":2}",
                "{\"chunk\":3}"
            };

            scheduleChunks(ctx, chunks, 0, 100, true);
        }

        private void sendNormalResponse(ChannelHandlerContext ctx) {
            byte[] body = "{\"status\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
            DefaultFullHttpResponse resp = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                Unpooled.copiedBuffer(body)
            );
            resp.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json");
            HttpUtil.setContentLength(resp, body.length);
            ctx.writeAndFlush(resp);
        }

        private void sendLargeSseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            // Create events that total > 256 bytes (maxStreamingCaptureBytes in test)
            StringBuilder largeEvent = new StringBuilder("data: ");
            for (int i = 0; i < 300; i++) {
                largeEvent.append("X");
            }
            largeEvent.append("\n\n");

            String[] events = {largeEvent.toString(), "data: final\n\n"};
            scheduleChunks(ctx, events, 0, 50, true);
        }

        private void sendAndCloseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            // Send one chunk then close
            ctx.executor().schedule(() -> {
                ctx.writeAndFlush(new DefaultHttpContent(
                    Unpooled.copiedBuffer("data: before-close\n\n", StandardCharsets.UTF_8)
                )).addListener(f -> {
                    // Close without sending LastHttpContent
                    ctx.close();
                });
            }, 50, TimeUnit.MILLISECONDS);
        }

        private void sendBinaryStreamResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/octet-stream");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);

            // Send binary data (non-text bytes)
            byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
            ctx.executor().schedule(() -> {
                ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(binaryData)))
                    .addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                        .addListener(ChannelFutureListener.CLOSE));
            }, 50, TimeUnit.MILLISECONDS);
        }

        /**
         * Mimics the OpenAI Codex backend used by the opencode CLI: a Server-Sent Events
         * stream served with NO Content-Type header at all. Sends the response head and an
         * early event immediately, then DELAYS the final event + completion by 2s. A correct
         * streaming relay forwards the head + early event to the client at once (so its headers
         * arrive promptly); a buffering proxy withholds everything until the 2s completion,
         * which is what made opencode time out waiting for response headers.
         */
        private void sendNoContentTypeSseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            // Deliberately NO Content-Type (this is the codex-backend behaviour).
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            ctx.writeAndFlush(new DefaultHttpContent(
                Unpooled.copiedBuffer("data: early\n\n", StandardCharsets.UTF_8)));
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttpContent(
                        Unpooled.copiedBuffer("data: late\n\n", StandardCharsets.UTF_8)))
                        .addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                            .addListener(ChannelFutureListener.CLOSE));
                }
            }, 2000, TimeUnit.MILLISECONDS);
        }

        /**
         * A genuine {@code text/event-stream} SSE response with a LONG inter-chunk pause. Sends the
         * response head + an early {@code data:} event immediately, then withholds the late event for
         * 1500ms before completing the stream. The 1500ms gap is deliberately longer than the short
         * {@code maxSocketTimeout} used by
         * {@link #shouldNotTruncateForwardedStreamWhenStreamIdleTimeoutDisabledAndPauseExceedsSocketTimeout()},
         * so that test is decisive: the socket read timeout would fire during the pause unless it is
         * removed when the response switches to streaming.
         */
        private void sendLongPauseSseResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            ctx.writeAndFlush(new DefaultHttpContent(
                Unpooled.copiedBuffer("data: early\n\n", StandardCharsets.UTF_8)));
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttpContent(
                        Unpooled.copiedBuffer("data: late\n\n", StandardCharsets.UTF_8)))
                        .addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                            .addListener(ChannelFutureListener.CLOSE));
                }
            }, 1500, TimeUnit.MILLISECONDS);
        }

        /**
         * Like {@link #sendNoContentTypeSseResponse} but streams genuinely binary bytes (with a
         * NUL/control byte) and still no Content-Type. Guards the no-Content-Type body sniffing:
         * such a stream must be logged as BINARY, not coerced into a STRING.
         */
        private void sendNoContentTypeBinaryStreamResponse(ChannelHandlerContext ctx) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            // Deliberately NO Content-Type.
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            byte[] binaryData = new byte[]{0x00, 0x01, 0x02, (byte) 0xFF, (byte) 0xFE, (byte) 0xFD};
            ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer(binaryData)))
                .addListener(f -> ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT)
                    .addListener(ChannelFutureListener.CLOSE));
        }

        private void scheduleChunks(ChannelHandlerContext ctx, String[] chunks, int index, long delayMs, boolean closeAfterLast) {
            if (index >= chunks.length) {
                ctx.writeAndFlush(LastHttpContent.EMPTY_LAST_CONTENT).addListener(f -> {
                    if (closeAfterLast) {
                        ctx.close();
                    }
                });
                return;
            }
            ctx.executor().schedule(() -> {
                if (ctx.channel().isActive()) {
                    ctx.writeAndFlush(new DefaultHttpContent(
                        Unpooled.copiedBuffer(chunks[index], StandardCharsets.UTF_8)
                    )).addListener(f -> {
                        if (f.isSuccess()) {
                            scheduleChunks(ctx, chunks, index + 1, delayMs, closeAfterLast);
                        }
                    });
                }
            }, delayMs, TimeUnit.MILLISECONDS);
        }
    }

    @Test
    public void shouldStreamSseResponseIncrementally() throws Exception {
        // Send request to MockServer (which will proxy to upstream)
        // The upstream sends 3 SSE events with 100ms delays between them
        long startTime = System.currentTimeMillis();

        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;

        // Verify the response contains all SSE events
        String fullResponse = String.join("\n", receivedLines);
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain event1", fullResponse, containsString("data: event1"));
        assertThat("response should contain event2", fullResponse, containsString("data: event2"));
        assertThat("response should contain event3", fullResponse, containsString("data: event3"));

        // Verify it took at least 200ms (3 events with 100ms delays between them)
        // This proves chunks were relayed incrementally, not buffered
        assertThat("response should take at least 200ms (proving streaming)", elapsed, greaterThanOrEqualTo(200L));
    }

    @Test
    public void shouldAggregateChunkedNonSseResponseNormally() throws Exception {
        // A chunked response without text/event-stream should NOT be detected as streaming.
        // It should be fully aggregated and returned with a complete body (regression guard
        // for WAR deployment where Tomcat uses chunked encoding for all responses).
        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /chunked HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain chunk1", fullResponse, containsString("{\"chunk\":1}"));
        assertThat("response should contain chunk2", fullResponse, containsString("{\"chunk\":2}"));
        assertThat("response should contain chunk3", fullResponse, containsString("{\"chunk\":3}"));
    }

    @Test
    public void shouldNotStreamNormalResponse() throws Exception {
        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(5000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /normal HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain the body", fullResponse, containsString("{\"status\":\"ok\"}"));
    }

    @Test
    public void shouldHandleMidStreamClose() throws Exception {
        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /close-mid-stream HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        String fullResponse = String.join("\n", receivedLines);
        // Should get at least the head and partial content
        assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
        assertThat("response should contain data before close", fullResponse, containsString("data: before-close"));
    }

    @Test
    public void shouldStreamForwardReplaceWhenResponseOverrideIsHeaderOnly() throws Exception {
        // A FORWARD_REPLACE with a HEADER-ONLY response override (adds a header, no body change) must
        // NOT force the streaming upstream to be aggregated: the header is applied to the streamed
        // response HEAD while the body chunks are relayed untouched. Decisive because the upstream
        // /sse-long-pause withholds the late event for 1500ms — with aggregation the head would only
        // arrive after that completion, whereas streaming relays the head + early event promptly.
        mockServerClient
            .when(request().withPath("/replace-header-only"))
            .forward(
                forwardOverriddenRequest(
                    request().withPath("/sse-long-pause").withHeader("Host", "127.0.0.1:" + upstreamPort),
                    response().withHeader("X-Custom-Override", "applied")
                )
            );

        String req = "GET /replace-header-only HTTP/1.1\r\n" +
            "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
            "Connection: close\r\n\r\n";
        TimedResponse r = sendAndMeasure(req, 15000);

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        // The override header must be applied to the streamed head.
        assertThat("response should contain the custom override header",
            r.body, containsString("X-Custom-Override: applied"));
        assertThat("should receive the early event", r.body, containsString("data: early"));
        assertThat("should receive the late event", r.body, containsString("data: late"));
        // The decisive assertion: the head is relayed promptly, proving streaming was preserved for
        // the header-only override rather than aggregating until the 1500ms completion.
        assertThat("head should arrive promptly (streaming preserved for header-only override)",
            r.firstByteMs, lessThan(1000L));
    }

    @Test
    public void shouldAggregateForForwardReplaceWhenResponseOverrideReplacesBody() throws Exception {
        // A FORWARD_REPLACE whose response override REPLACES the body needs the full upstream body,
        // so the streaming upstream must be aggregated (streaming disabled). Decisive: the head only
        // arrives after the upstream's 1500ms completion, and the streamed events are discarded.
        mockServerClient
            .when(request().withPath("/replace-body"))
            .forward(
                forwardOverriddenRequest(
                    request().withPath("/sse-long-pause").withHeader("Host", "127.0.0.1:" + upstreamPort),
                    response().withBody("replaced-body")
                )
            );

        String req = "GET /replace-body HTTP/1.1\r\n" +
            "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
            "Connection: close\r\n\r\n";
        TimedResponse r = sendAndMeasure(req, 15000);

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        assertThat("override body should replace the streamed content",
            r.body, containsString("replaced-body"));
        assertThat("streamed events should be discarded by the body override",
            r.body, not(containsString("data: early")));
        // The decisive assertion: a body override forces aggregation, so the head only arrives after
        // the upstream's 1500ms completion.
        assertThat("body override forces aggregation (head arrives only after the 1500ms completion)",
            r.firstByteMs, greaterThanOrEqualTo(1300L));
    }

    @Test
    public void shouldLogSseStreamingResponseAsStringBody() throws Exception {
        // Send an SSE request through the proxy and wait for the stream to complete
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (reader.readLine() != null) {
                // consume the full response
            }
        }

        // Poll until the completion listener has fired and the log entry is visible
        pollUntilTrue(() -> mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse")).length >= 1);

        // Retrieve the recorded request/response pair via the MockServer API
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse")
        );
        assertThat("should have recorded at least one SSE request", recorded.length, greaterThanOrEqualTo(1));

        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should not be null", loggedResponse, notNullValue());
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());

        // The key assertion: the body should be a STRING type (not BINARY)
        assertThat("SSE streaming body should be logged as STRING, not BINARY",
            loggedResponse.getBody().getType(), is(Body.Type.STRING));

        // The body content should contain the SSE event text
        String bodyString = loggedResponse.getBodyAsString();
        assertThat("logged body should contain SSE event text",
            bodyString, containsString("data: event1"));

        // the upstream sends its first chunk 100 ms after the head, inside the forward's response time
        long firstChunkDelayMs = Long.parseLong(loggedResponse.getFirstHeader("x-mockserver-first-chunk-delay-ms"));
        long responseTimeMs = Long.parseLong(loggedResponse.getFirstHeader("x-mockserver-response-time-ms"));
        assertThat(firstChunkDelayMs, allOf(greaterThanOrEqualTo(90L), lessThanOrEqualTo(responseTimeMs)));
        assertThat("the chunk delays keep their meaning", loggedResponse.getFirstHeader("x-mockserver-chunk-delays-ms"), startsWith("0,"));
    }

    @Test
    public void shouldLogChunkedNonSseResponseWithBody() throws Exception {
        // Chunked JSON responses are NOT detected as streaming (only SSE is).
        // They are aggregated normally and logged with their full body.
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /chunked HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            while (reader.readLine() != null) {
                // consume the full response
            }
        }

        // Poll until the completion listener has fired and the log entry is visible
        pollUntilTrue(() -> mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/chunked")).length >= 1);

        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/chunked")
        );
        assertThat("should have recorded at least one chunked request", recorded.length, greaterThanOrEqualTo(1));

        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should not be null", loggedResponse, notNullValue());
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());

        String bodyString = loggedResponse.getBodyAsString();
        assertThat("logged body should contain JSON chunk text",
            bodyString, containsString("\"chunk\""));
    }

    @Test
    public void shouldLogBinaryChunkedResponseWithBody() throws Exception {
        // Binary chunked responses (application/octet-stream) are NOT detected as
        // streaming (only SSE is). They are aggregated normally and logged with their body.
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /binary-stream HTTP/1.1\r\n" +
                "Host: 127.0.0.1:" + upstreamPort + "\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            // consume the full response (binary, so just read bytes)
            byte[] buf = new byte[4096];
            while (socket.getInputStream().read(buf) != -1) {
                // drain
            }
        }

        // Poll until the completion listener has fired and the log entry is visible
        pollUntilTrue(() -> mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/binary-stream")).length >= 1);

        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/binary-stream")
        );
        assertThat("should have recorded at least one binary-stream request", recorded.length, greaterThanOrEqualTo(1));

        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should not be null", loggedResponse, notNullValue());
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());

        // Binary chunked body should be logged as BINARY type (standard aggregation)
        assertThat("binary chunked body should be logged as BINARY",
            loggedResponse.getBody().getType(), is(Body.Type.BINARY));
    }

    /** A raw response read back with the time (ms) until the first response byte arrived. */
    private static final class TimedResponse {
        final long firstByteMs;
        final String body;
        TimedResponse(long firstByteMs, String body) {
            this.firstByteMs = firstByteMs;
            this.body = body;
        }
    }

    private TimedResponse sendAndMeasure(String rawRequest, int soTimeoutMs) throws Exception {
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(soTimeoutMs);
            OutputStream output = socket.getOutputStream();
            long start = System.currentTimeMillis();
            output.write(rawRequest.getBytes(StandardCharsets.UTF_8));
            output.flush();
            java.io.InputStream in = socket.getInputStream();
            int first = in.read(); // blocks until the first response byte is relayed back
            long firstByteMs = System.currentTimeMillis() - start;
            StringBuilder sb = new StringBuilder();
            if (first != -1) {
                sb.append((char) first);
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) != -1) {
                    sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }
            return new TimedResponse(firstByteMs, sb.toString());
        }
    }

    @Test
    public void shouldStreamWhenClientRequestedStreamEvenWithoutSseContentType() throws Exception {
        // Regression test for the opencode/Codex header-timeout bug. Exercises the FORWARD-action
        // path (HttpActionHandler -> NettyHttpClient), which is the path real coding-CLI proxy
        // traffic takes (it carries the x-mockserver-response-time-ms header). The upstream streams
        // SSE with NO Content-Type and delays completion by 2s. The request body carries
        // "stream": true, so MockServer must relay the response as a stream regardless of the
        // missing content-type — the head + early event must reach the client promptly rather than
        // after the 2s completion (which is what made opencode time out on response headers).
        mockServerClient
            .when(request().withPath("/codex-stream"))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

        String body = "{\"stream\":true,\"model\":\"x\"}";
        String req = "POST /codex-stream HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
            "Connection: close\r\n\r\n" + body;

        TimedResponse r = sendAndMeasure(req, 10000);

        assertThat("response should contain HTTP 200", r.body, containsString("200"));
        assertThat("should receive the early event", r.body, containsString("data: early"));
        assertThat("should receive the late event", r.body, containsString("data: late"));
        // The key assertion: response headers arrive well before the upstream's 2s completion,
        // proving the stream was relayed incrementally (not buffered) despite no content-type.
        assertThat("response headers should arrive promptly (streaming), not after the 2s body delay",
            r.firstByteMs, lessThan(1500L));

        // The recorded body must capture the streamed SSE text even though the upstream sent no
        // Content-Type. Regression guard for the opencode/Codex LLM-trace bug where the streamed
        // forward logged an empty (4-byte BINARY "null") body instead of the SSE event text.
        pollUntilTrue(() -> mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/codex-stream")).length >= 1);
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/codex-stream"));
        assertThat("should have recorded at least one codex-stream request", recorded.length, greaterThanOrEqualTo(1));
        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should not be null", loggedResponse, notNullValue());
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());
        // A text SSE stream with no Content-Type must still be logged as STRING, not BINARY.
        assertThat("no-content-type SSE streaming body should be logged as STRING, not BINARY",
            loggedResponse.getBody().getType(), is(Body.Type.STRING));
        String bodyString = loggedResponse.getBodyAsString();
        assertThat("logged body should contain the early SSE event text",
            bodyString, containsString("data: early"));
        assertThat("logged body should contain the late SSE event text",
            bodyString, containsString("data: late"));
    }

    @Test
    public void shouldLogNoContentTypeBinaryStreamAsBinary() throws Exception {
        // Guard for the no-Content-Type body sniffing: a streamed response with no Content-Type
        // whose bytes are genuinely binary (contain NUL/control bytes) must stay BINARY, not be
        // coerced into a STRING by the text heuristic that recovers SSE/JSON streams.
        mockServerClient
            .when(request().withPath("/codex-binary-stream"))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

        String body = "{\"stream\":true}";
        String req = "POST /codex-binary-stream HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Content-Type: application/json\r\n" +
            "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n" +
            "Connection: close\r\n\r\n" + body;

        sendAndMeasure(req, 10000);

        pollUntilTrue(() -> mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/codex-binary-stream")).length >= 1);
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/codex-binary-stream"));
        assertThat("should have recorded at least one codex-binary-stream request",
            recorded.length, greaterThanOrEqualTo(1));
        HttpResponse loggedResponse = recorded[0].getHttpResponse();
        assertThat("logged response should have a body", loggedResponse.getBody(), notNullValue());
        assertThat("genuinely binary no-content-type stream should be logged as BINARY",
            loggedResponse.getBody().getType(), is(Body.Type.BINARY));
    }

    @Test
    public void shouldAggregateNoContentTypeStreamWhenClientDidNotRequestStream() throws Exception {
        // Same forward path and no-Content-Type upstream, but a plain GET with no streaming intent
        // (no "stream": true, no Accept: text/event-stream). MockServer aggregates as before, so the
        // first byte only arrives after the upstream completes (~2s). Proves the streaming relay is
        // gated on the client's request intent, not merely on the endpoint — so ordinary
        // (non-streaming) forward traffic is unaffected.
        mockServerClient
            .when(request().withPath("/codex-stream"))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

        String req = "GET /codex-stream HTTP/1.1\r\n" +
            "Host: localhost\r\n" +
            "Connection: close\r\n\r\n";

        TimedResponse r = sendAndMeasure(req, 10000);

        assertThat("aggregated response should still contain both events",
            r.body, allOf(containsString("data: early"), containsString("data: late")));
        assertThat("without a streaming request the response is buffered until completion (~2s)",
            r.firstByteMs, greaterThanOrEqualTo(1500L));
    }

    @Test(timeout = 30000)
    public void shouldNotTruncateForwardedStreamWhenStreamIdleTimeoutDisabledAndPauseExceedsSocketTimeout() throws Exception {
        // Regression test for the streamIdleTimeoutSeconds=0 truncation fix (commit 8a803f9a9).
        //
        // streamIdleTimeoutSeconds is documented to REPLACE the per-request socket read timeout
        // (maxSocketTimeout) for streaming responses, with 0 meaning "no idle bound" (unbounded stream).
        // The bug: the socket read timeout was only removed inside the "streamIdleTimeoutSeconds > 0"
        // branch, so setting it to 0 paradoxically left the short socket timeout armed and truncated a
        // long-paused stream.
        //
        // Here a dedicated MockServer is configured with maxSocketTimeout=800ms AND
        // streamIdleTimeoutSeconds=0, forwarding to an SSE upstream that sends an early event
        // immediately then PAUSES 1500ms (comfortably > 800ms) before the late event. The forward path
        // arms a ReadTimeoutHandler(800ms) on the non-pooled upstream channel; once the response switches
        // to streaming the fix removes it, so with idle bound disabled the stream runs unbounded and the
        // late event survives. WITHOUT the fix the 800ms read timeout fires during the 1500ms pause,
        // tears down the upstream connection, and the late event is lost (stream truncated).
        Configuration configuration = configuration()
            .streamingResponsesEnabled(true)
            .streamIdleTimeoutSeconds(0)
            .maxSocketTimeoutInMillis(800L);
        MockServer streamingForwardServer = new MockServer(configuration);
        int streamingForwardPort = streamingForwardServer.getLocalPort();
        MockServerClient streamingForwardClient = new MockServerClient("localhost", streamingForwardPort);
        try {
            streamingForwardClient
                .when(request().withPath("/sse-long-pause"))
                .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

            String req = "GET /sse-long-pause HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Accept: text/event-stream\r\n" +
                "Connection: close\r\n\r\n";

            List<String> receivedLines = new ArrayList<>();
            long startTime = System.currentTimeMillis();
            long firstByteMs;
            try (Socket socket = new Socket("localhost", streamingForwardPort)) {
                socket.setSoTimeout(10000);
                OutputStream output = socket.getOutputStream();
                output.write(req.getBytes(StandardCharsets.UTF_8));
                output.flush();

                java.io.InputStream in = socket.getInputStream();
                int first = in.read(); // blocks until the first relayed byte (head + early event)
                firstByteMs = System.currentTimeMillis() - startTime;
                StringBuilder sb = new StringBuilder();
                if (first != -1) {
                    sb.append((char) first);
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                    }
                }
                for (String line : sb.toString().split("\n")) {
                    receivedLines.add(line);
                }
            }

            String fullResponse = String.join("\n", receivedLines);
            assertThat("response should contain HTTP 200", fullResponse, containsString("200"));
            // Decisive assertions: BOTH events arrive. The late event only survives the 1500ms pause
            // because the 800ms socket read timeout was removed on switching to streaming (the fix).
            assertThat("should receive the early event", fullResponse, containsString("data: early"));
            assertThat("should receive the late event (stream NOT truncated by the socket timeout during the pause)",
                fullResponse, containsString("data: late"));
            // The head + early event are relayed promptly (incrementally), well before the 1500ms
            // completion — proving the relay streams rather than buffering to the end.
            assertThat("response head should arrive promptly (streaming), not after the 1500ms pause",
                firstByteMs, lessThan(1200L));
        } finally {
            stopQuietly(streamingForwardClient);
            stopQuietly(streamingForwardServer);
        }
    }

    @Test
    public void shouldPublishForwardedRequestBeforeClientObservesStreamEndOnStreamingForwardPath() throws Exception {
        // Coverage for the STREAMING forward path (HttpActionHandler.writeStreamingForwardActionResponse):
        // the response head + body chunks are written to the client FIRST, and the FORWARDED_REQUEST log
        // entry is published only when the streaming body completes (it carries the captured body and the
        // total stream duration, neither of which exists until the stream ends). The sibling ordering test
        // HttpActionHandlerForwardLogOrderingTest only covers the direct-write and non-streaming forward
        // paths (its "Path 1 / Path 3" scope), leaving this streaming path unproven.
        //
        // The window this guards: after a client reads a streamed forward to completion, an IMMEDIATE,
        // retry-free retrieve/verify MUST already see the forwarded exchange. That holds because
        // StreamingBody.complete() runs the relay's onComplete (which only SCHEDULES the terminating
        // LastHttpContent write and defers the socket close to that write's completion listener — a later
        // event-loop task) and THEN synchronously fireListeners() -> logEvent(FORWARDED_REQUEST). So the
        // entry is published onto the disruptor before the client can observe stream end. Every other
        // streaming retrieval test in this class polls via pollUntilTrue(...) before retrieving; this one
        // deliberately does NOT, asserting the ordering guarantee rather than eventual visibility.
        //
        // A forward EXPECTATION (not proxy-pass) is used so the request is dispatched through the
        // forward-action streaming path (writeStreamingForwardActionResponse) rather than the
        // unmatched-proxy streaming path.
        mockServerClient
            .when(request().withPath("/sse"))
            .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

        // Read the streamed forward to completion on the client side (Connection: close, so readLine()
        // returns null only once the server has written the terminating chunk and closed the socket).
        List<String> receivedLines = new ArrayList<>();
        try (Socket socket = new Socket("localhost", mockServerPort)) {
            socket.setSoTimeout(10000);
            OutputStream output = socket.getOutputStream();
            output.write(("GET /sse HTTP/1.1\r\n" +
                "Host: localhost\r\n" +
                "Connection: close\r\n" +
                "\r\n").getBytes(StandardCharsets.UTF_8));
            output.flush();

            BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) {
                receivedLines.add(line);
            }
        }

        // Sanity: the client genuinely observed the stream to its end (all three events + close).
        String fullResponse = String.join("\n", receivedLines);
        assertThat("client should have read the full SSE stream", fullResponse, containsString("200"));
        assertThat("client should have observed the final SSE event", fullResponse, containsString("data: event3"));

        // RETRY-FREE assertion — no pollUntilTrue, no eventual-verify (Duration) overload. Immediately
        // after the client sees stream end the forwarded exchange must already be retrievable, proving the
        // stream-completion listener publishes FORWARDED_REQUEST before/consistently-with the client's
        // observation of stream end.
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(
            request().withPath("/sse"));
        assertThat("streaming forward must publish FORWARDED_REQUEST before the client observes stream end "
                + "(retrieved retry-free)", recorded.length, greaterThanOrEqualTo(1));

        // The same guarantee via the verification surface, asserted exactly once with no wait.
        mockServerClient.verify(request().withPath("/sse"), VerificationTimes.once());
    }

    private LogEventRequestAndResponse recordStreamTheClientClosesMidWay(Boolean recordClientClosedStreamsComplete) throws Exception {
        return recordStreamTheClientClosesMidWay("/sse-endless", recordClientClosedStreamsComplete);
    }

    private LogEventRequestAndResponse recordStreamTheClientClosesMidWay(String path, Boolean recordClientClosedStreamsComplete) throws Exception {
        Configuration configuration = configuration()
            .streamingResponsesEnabled(true)
            .maxStreamingCaptureBytes(262144)
            .recordClientClosedStreamsComplete(recordClientClosedStreamsComplete);
        MockServer forwardServer = new MockServer(configuration);
        int forwardPort = forwardServer.getLocalPort();
        MockServerClient forwardClient = new MockServerClient("localhost", forwardPort);
        try {
            forwardClient
                .when(request().withPath(path))
                .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

            // the client reads two events, then goes away while the upstream is still sending
            try (Socket socket = new Socket("localhost", forwardPort)) {
                socket.setSoTimeout(10000);
                OutputStream output = socket.getOutputStream();
                output.write(("GET " + path + " HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.flush();

                java.io.InputStream in = socket.getInputStream();
                StringBuilder received = new StringBuilder();
                byte[] buf = new byte[4096];
                while (!received.toString().contains("data: event1")) {
                    int n = in.read(buf);
                    if (n == -1) {
                        throw new AssertionError("stream ended before the client closed it: " + received);
                    }
                    received.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                }
            }

            pollUntilTrue(() -> forwardClient.retrieveRecordedRequestsAndResponses(request().withPath(path)).length >= 1);
            return forwardClient.retrieveRecordedRequestsAndResponses(request().withPath(path))[0];
        } finally {
            stopQuietly(forwardClient);
            stopQuietly(forwardServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldRecordStreamClosedByClientCompleteWithChunkDelaysWhenRecordClientClosedStreamsCompleteIsOn() throws Exception {
        HttpResponse logged = recordStreamTheClientClosesMidWay(true).getHttpResponse();

        assertThat("the stream is recorded as complete, not truncated", logged.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertChunkTimingKeptWithAGapOfAbout100Millis(logged);
        assertThat("the recording says the client ended the stream", logged.getFirstHeader("x-mockserver-stream-closed-by-client"), is("true"));
        assertThat(logged.getBodyAsString(), containsString("data: event0"));
    }

    @Test(timeout = 30000)
    public void shouldRecordStreamClosedByClientTruncatedWhenRecordClientClosedStreamsCompleteIsOff() throws Exception {
        HttpResponse logged = recordStreamTheClientClosesMidWay(false).getHttpResponse();

        assertThat("the stream is recorded as truncated, as before", logged.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat("the chunk timing is dropped, as before", logged.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
        assertThat(logged.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
    }

    @Test(timeout = 30000)
    public void shouldRecordCloseDelimitedStreamClosedByClientCompleteWhenRecordClientClosedStreamsCompleteIsOn() throws Exception {
        HttpResponse logged = recordStreamTheClientClosesMidWay("/sse-endless-close-delimited", true).getHttpResponse();

        assertThat("the stream is recorded as complete, not truncated", logged.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertChunkTimingKeptWithAGapOfAbout100Millis(logged);
        assertThat("the recording says the client ended the stream", logged.getFirstHeader("x-mockserver-stream-closed-by-client"), is("true"));
        assertThat(logged.getBodyAsString(), containsString("data: event0"));
    }

    @Test(timeout = 30000)
    public void shouldRecordCloseDelimitedStreamClosedByClientTruncatedWhenRecordClientClosedStreamsCompleteIsOff() throws Exception {
        HttpResponse logged = recordStreamTheClientClosesMidWay("/sse-endless-close-delimited", false).getHttpResponse();

        assertThat("closing the upstream did not end the body, so it is recorded truncated", logged.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat(logged.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
        assertThat(logged.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
    }

    private static void assertChunkTimingKeptWithAGapOfAbout100Millis(HttpResponse logged) {
        assertThat("the chunk timing is kept", logged.containsHeader("x-mockserver-chunk-delays-ms"), is(true));
        String[] delays = logged.getFirstHeader("x-mockserver-chunk-delays-ms").split(",");
        assertThat("at least two chunks were timed", delays.length, greaterThanOrEqualTo(2));
        long longestGap = 0;
        for (String delay : delays) {
            longestGap = Math.max(longestGap, Long.parseLong(delay.trim()));
        }
        assertThat("the upstream sends an event about every 100 ms", longestGap, allOf(greaterThanOrEqualTo(50L), lessThan(2000L)));
    }

    private HttpResponse recordHttp2StreamTheClientResets(Boolean recordClientClosedStreamsComplete) throws Exception {
        return recordHttp2StreamEndedAfterTwoEvents(recordClientClosedStreamsComplete, (channel, stream) ->
            channel.writeAndFlush(new io.netty.handler.codec.http2.DefaultHttp2ResetFrame(io.netty.handler.codec.http2.Http2Error.CANCEL).stream(stream)).sync());
    }

    @Test(timeout = 30000)
    public void shouldRecordHttp2StreamCutByAConnectionErrorNettysCodecClosesTheConnectionForTruncated() throws Exception {
        // a DATA frame on stream 0, written past the client's codec: a protocol error Netty answers with GOAWAY and a close
        HttpResponse logged = recordHttp2StreamEndedAfterTwoEvents(true, (channel, stream) ->
            channel.pipeline().firstContext().writeAndFlush(Unpooled.wrappedBuffer(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0})).sync());

        assertThat("recorded as MockServer's close", logged.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat(logged.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
    }

    private interface Http2StreamEnding {
        void end(io.netty.channel.Channel channel, io.netty.handler.codec.http2.Http2FrameStream stream) throws Exception;
    }

    private HttpResponse recordHttp2StreamEndedAfterTwoEvents(Boolean recordClientClosedStreamsComplete, Http2StreamEnding ending) throws Exception {
        Configuration configuration = configuration()
            .streamingResponsesEnabled(true)
            .maxStreamingCaptureBytes(262144)
            .recordClientClosedStreamsComplete(recordClientClosedStreamsComplete);
        MockServer forwardServer = new MockServer(configuration);
        int forwardPort = forwardServer.getLocalPort();
        MockServerClient forwardClient = new MockServerClient("localhost", forwardPort);
        io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
        try {
            forwardClient
                .when(request().withPath("/sse-endless"))
                .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

            java.util.concurrent.CountDownLatch sawSecondEvent = new java.util.concurrent.CountDownLatch(1);
            StringBuffer received = new StringBuffer();
            Http2ReadingHandler reader = new Http2ReadingHandler(received, sawSecondEvent);
            // prior-knowledge cleartext HTTP/2: the client sends the connection preface, then one request stream
            io.netty.channel.Channel channel = new io.netty.bootstrap.Bootstrap()
                .group(group)
                .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                .handler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel ch) {
                        ch.pipeline().addLast(io.netty.handler.codec.http2.Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(reader);
                    }
                })
                .connect("localhost", forwardPort)
                .sync()
                .channel();
            io.netty.handler.codec.http2.Http2FrameStream stream = reader.openStream();
            io.netty.handler.codec.http2.Http2Headers headers = new io.netty.handler.codec.http2.DefaultHttp2Headers()
                .method("GET").scheme("http").authority("localhost:" + forwardPort).path("/sse-endless");
            channel.writeAndFlush(new io.netty.handler.codec.http2.DefaultHttp2HeadersFrame(headers, true).stream(stream)).sync();

            assertThat("the client reads two events before it ends the stream: " + received, sawSecondEvent.await(10, java.util.concurrent.TimeUnit.SECONDS), is(true));
            ending.end(channel, stream);

            pollUntilTrue(() -> forwardClient.retrieveRecordedRequestsAndResponses(request().withPath("/sse-endless")).length >= 1);
            HttpResponse logged = forwardClient.retrieveRecordedRequestsAndResponses(request().withPath("/sse-endless"))[0].getHttpResponse();
            channel.close().sync();
            return logged;
        } finally {
            group.shutdownGracefully();
            stopQuietly(forwardClient);
            stopQuietly(forwardServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldRecordHttp2StreamTheClientResetsCompleteWhenRecordClientClosedStreamsCompleteIsOn() throws Exception {
        HttpResponse logged = recordHttp2StreamTheClientResets(true);

        assertThat("the stream is recorded as complete, not truncated", logged.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertChunkTimingKeptWithAGapOfAbout100Millis(logged);
        assertThat(logged.getFirstHeader("x-mockserver-stream-closed-by-client"), is("true"));
    }

    @Test(timeout = 30000)
    public void shouldRecordHttp2StreamTheClientResetsTruncatedWhenRecordClientClosedStreamsCompleteIsOff() throws Exception {
        HttpResponse logged = recordHttp2StreamTheClientResets(false);

        assertThat("the stream is recorded as truncated, as before", logged.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat(logged.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
        assertThat(logged.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
    }

    private Expectation recordStreamTheUpstreamCutsShort(Boolean recordTruncatedStreamTiming) throws Exception {
        Configuration configuration = configuration()
            .streamingResponsesEnabled(true)
            .maxStreamingCaptureBytes(262144)
            .recordTruncatedStreamTiming(recordTruncatedStreamTiming);
        MockServer forwardServer = new MockServer(configuration);
        int forwardPort = forwardServer.getLocalPort();
        MockServerClient forwardClient = new MockServerClient("localhost", forwardPort);
        try {
            forwardClient
                .when(request().withPath("/sse-cut-short"))
                .forward(forward().withHost("127.0.0.1").withPort(upstreamPort));

            try (Socket socket = new Socket("localhost", forwardPort)) {
                socket.setSoTimeout(10000);
                OutputStream output = socket.getOutputStream();
                output.write(("GET /sse-cut-short HTTP/1.1\r\n" +
                    "Host: localhost\r\n" +
                    "Accept: text/event-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                output.flush();
                java.io.InputStream in = socket.getInputStream();
                byte[] buf = new byte[4096];
                while (in.read(buf) != -1) {
                    // read until MockServer ends the response incomplete by closing the connection
                }
            }

            pollUntilTrue(() -> forwardClient.retrieveRecordedExpectations(request().withPath("/sse-cut-short")).length >= 1);
            return forwardClient.retrieveRecordedExpectations(request().withPath("/sse-cut-short"))[0];
        } finally {
            stopQuietly(forwardClient);
            stopQuietly(forwardServer);
        }
    }

    @Test(timeout = 30000)
    public void shouldKeepChunkTimingOfAStreamTheUpstreamCutsShortAndReplayItAsTimedSseWhenRecordTruncatedStreamTimingIsOn() throws Exception {
        Expectation recorded = recordStreamTheUpstreamCutsShort(true);
        HttpResponse logged = recorded.getHttpResponse();

        assertThat("the stream stays recorded as truncated", logged.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        String[] delays = logged.getFirstHeader("x-mockserver-chunk-delays-ms").split(",");
        assertThat("each of the four chunks captured before the cut is timed", delays.length, is(4));
        for (int i = 1; i < delays.length; i++) {
            assertThat("the upstream sends a chunk every 150 ms", Long.parseLong(delays[i].trim()), allOf(greaterThanOrEqualTo(100L), lessThan(2000L)));
        }

        Expectation replay = new SseAwareExpectationConverter().convert(new Expectation[]{recorded})[0];
        HttpSseResponse sse = replay.getHttpSseResponse();
        assertThat("a truncated SSE recording with timing replays as timed events", sse, notNullValue());
        assertThat("the event cut off part way is dropped", sse.getEvents().size(), is(3));
        assertThat(sse.getEvents().get(2).getData(), is("event2"));
        assertThat(sse.getEvents().get(1).getDelay().getValue(), is(Long.parseLong(delays[1].trim())));
        assertThat(sse.getEvents().get(2).getDelay().getValue(), is(Long.parseLong(delays[2].trim())));
        assertThat(sse.getHeaders().getValues("x-mockserver-fixture-warning").get(0), containsString("cut off part way"));
    }

    @Test(timeout = 30000)
    public void shouldDropChunkTimingOfAStreamTheUpstreamCutsShortWhenRecordTruncatedStreamTimingIsOff() throws Exception {
        Expectation recorded = recordStreamTheUpstreamCutsShort(false);
        HttpResponse logged = recorded.getHttpResponse();

        assertThat(logged.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat("the chunk timing is dropped, as before", logged.containsHeader("x-mockserver-chunk-delays-ms"), is(false));
        Expectation replay = new SseAwareExpectationConverter().convert(new Expectation[]{recorded})[0];
        assertThat("the recording keeps the static fallback", replay.getHttpSseResponse(), nullValue());
        assertThat(replay.getHttpResponse().getBodyAsString(), containsString("data: event2"));
    }

    /**
     * A client's handler that collects the DATA of an HTTP/2 stream and opens that stream.
     */
    private static final class Http2ReadingHandler extends io.netty.handler.codec.http2.Http2ChannelDuplexHandler {
        private final StringBuffer received;
        private final java.util.concurrent.CountDownLatch sawSecondEvent;

        private Http2ReadingHandler(StringBuffer received, java.util.concurrent.CountDownLatch sawSecondEvent) {
            this.received = received;
            this.sawSecondEvent = sawSecondEvent;
        }

        private io.netty.handler.codec.http2.Http2FrameStream openStream() {
            return newStream();
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof io.netty.handler.codec.http2.Http2DataFrame) {
                received.append(((io.netty.handler.codec.http2.Http2DataFrame) msg).content().toString(StandardCharsets.UTF_8));
                if (received.indexOf("data: event1") >= 0) {
                    sawSecondEvent.countDown();
                }
            }
            io.netty.util.ReferenceCountUtil.release(msg);
        }
    }
}

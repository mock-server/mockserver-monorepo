package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.client.LlmMockBuilder;
import org.mockserver.mock.Expectation;
import org.mockserver.model.Completion;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.Provider;
import org.mockserver.model.SseEvent;
import org.mockserver.model.StreamingPhysics;
import org.mockserver.netty.MockServer;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that streaming responses actually reach a real HTTP/2 client.
 * <p>
 * This is the test that would have caught GitHub issue #2419 and its siblings. The defect class is
 * that a handler writes raw Netty objects without stamping the request's HTTP/2 stream id, so
 * {@code HttpToHttp2ConnectionHandler} routes the response onto a fresh <em>server-initiated</em>
 * stream. The server logs a normal successful response; the client's stream receives nothing and it
 * hangs until its own timeout. Every server-side assertion still passes, which is why #2419 shipped
 * and why the SSE and streaming-body instances shipped alongside it.
 * <p>
 * An in-JVM Netty HTTP/2 (h2c) multiplex client is used rather than the shared integration harness
 * for two reasons: the harness's {@code StreamingAwareHttpObjectAggregator} relays
 * {@code text/event-stream} instead of aggregating it (so it cannot assert an SSE body at all), and
 * only a real multiplex client can prove the data arrived on <em>the client's own stream</em> rather
 * than on some other stream. Before the fix these tests fail by timing out with an empty body.
 */
public class Http2SseStreamingIntegrationTest {

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer();
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test
    public void shouldDeliverSseEventsToRealHttp2Client() throws Exception {
        // given - an SSE expectation with two events
        mockServerClient.upsert(
            new Expectation(request().withPath("/http2_sse"))
                .thenRespondWithSse(
                    HttpSseResponse.sseResponse()
                        .withEvents(
                            SseEvent.sseEvent().withEvent("first").withData("sse_event_one"),
                            SseEvent.sseEvent().withEvent("second").withData("sse_event_two")
                        )
                )
        );

        // when - a real HTTP/2 client requests it over h2c
        String body = sendH2cRequestAndCollectBody("/http2_sse");

        // then - every event arrived on the client's own stream. Without the stream id on the
        // response head this is empty, because the whole stream went out on a phantom stream.
        assertThat("SSE body received: <" + body + ">", body, containsString("event: first"));
        assertThat("SSE body received: <" + body + ">", body, containsString("data: sse_event_one"));
        assertThat("SSE body received: <" + body + ">", body, containsString("event: second"));
        assertThat("SSE body received: <" + body + ">", body, containsString("data: sse_event_two"));
    }

    // NOTE - the StreamingBody sibling of this bug (NettyResponseWriter.writeStreamingResponse,
    // which copied only the header multimap onto the Netty head and so dropped the stream id held in
    // a separate field) is NOT covered here, because a StreamingBody cannot be expressed through the
    // client API: it has no serializer or DTO, and the only production code that sets one is
    // StreamingResponseRelayHandler on the proxy/forward relay path. It is covered at unit level by
    // NettyResponseWriterTest.shouldSendStreamingResponseHeadDownTheRequestHttp2Stream, whose
    // assertion was verified to go red when the fix is degraded.
    //
    // End-to-end coverage would mean an HTTP/2-inbound variant of
    // StreamingProxyResponseIntegrationTest (which today relays an upstream SSE stream over an
    // HTTP/1.1 inbound raw socket). That is worth adding - it is the real-world path for streaming
    // LLM responses through the proxy over HTTP/2 - but it needs upstream-server scaffolding beyond
    // this change.

    @Test
    public void shouldKeepHttp2ConnectionOpenForSubsequentStreamAfterAStreamFinishes() throws Exception {
        // GitHub issue #2641, symptom 2. For all non-gRPC HTTP/2 traffic MockServer multiplexes every
        // stream onto ONE connection channel (HttpToHttp2ConnectionHandler; there are no per-stream
        // child channels - see PortUnificationHandler and Http2StreamIds). The streaming handler used
        // to call ctx.close() at end-of-stream, which on that shared channel emits GOAWAY and tears
        // down the WHOLE connection - so a second request on the same connection was impossible.
        //
        // This drives two streaming SSE requests SEQUENTIALLY on a single h2c connection: the second
        // stream is only opened after the first has fully completed. Before the fix the first stream's
        // completion GOAWAYed the connection, so the second request received nothing; after the fix the
        // connection survives (never closed for an HTTP/2 request, and the read is re-armed) and the
        // second stream is served. Fully-interleaved concurrent streaming over this non-multiplex path
        // is now covered too (see shouldDeliverConcurrentInterleavedSseStreams) - the codec no longer
        // routes bare content frames by a single current-stream-id, because StreamAddressedHttpContent
        // carries the stream id explicitly (issue #2667).
        mockServerClient.upsert(
            new Expectation(request().withPath("/http2_sse_reuse"))
                .thenRespondWithSse(
                    HttpSseResponse.sseResponse()
                        .withEvents(
                            SseEvent.sseEvent().withEvent("open").withData("stream_open"),
                            SseEvent.sseEvent().withEvent("done").withData("stream_done")
                        )
                )
        );

        NioEventLoopGroup group = new NioEventLoopGroup();
        AtomicBoolean goAwayReceived = new AtomicBoolean(false);
        try {
            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        }));
                        // connection-level frames (incl. GOAWAY) are fired past the multiplex handler
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                if (msg instanceof Http2GoAwayFrame) {
                                    goAwayReceived.set(true);
                                }
                                ReferenceCountUtil.release(msg);
                            }
                        });
                    }
                });

            Channel parent = bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();

            // when - the first streaming request is opened and fully collected
            String first = openStreamAndCollectBody(parent, "/http2_sse_reuse");
            // then - it completed on its own stream and the shared connection was NOT torn down
            assertThat("first stream body: <" + first + ">", first, containsString("data: stream_open"));
            assertThat("first stream body: <" + first + ">", first, containsString("data: stream_done"));
            assertThat("connection was GOAWAYed after the first stream finished", goAwayReceived.get(), is(false));

            // when - a SECOND streaming request is opened on the SAME connection
            String second = openStreamAndCollectBody(parent, "/http2_sse_reuse");
            // then - it is served, proving the shared HTTP/2 connection survived the first stream.
            // Before the fix the first stream's ctx.close() GOAWAYed the connection, so this was empty.
            assertThat("second stream body: <" + second + ">", second, containsString("data: stream_open"));
            assertThat("second stream body: <" + second + ">", second, containsString("data: stream_done"));
            assertThat("connection was GOAWAYed before the second stream completed", goAwayReceived.get(), is(false));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Open one stream on an existing h2c parent connection, request {@code path}, and collect every
     * DATA frame delivered to that stream, returning once the stream ends. Returns whatever was
     * collected on timeout so a failure reads as "this stream received nothing".
     */
    private String openStreamAndCollectBody(Channel parent, String path) throws Exception {
        StringBuilder collected = new StringBuilder();
        CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
            .handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2DataFrame) {
                            Http2DataFrame data = (Http2DataFrame) msg;
                            synchronized (collected) {
                                collected.append(data.content().toString(StandardCharsets.UTF_8));
                            }
                            if (data.isEndStream()) {
                                bodyFuture.complete(collected.toString());
                            }
                        } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                            bodyFuture.complete(collected.toString());
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    synchronized (collected) {
                        bodyFuture.complete(collected.toString());
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    bodyFuture.completeExceptionally(cause);
                }
            })
            .open()
            .sync()
            .getNow();

        Http2Headers headers = new DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName())
            .scheme(HttpScheme.HTTP.name())
            .authority("localhost:" + mockServer.getLocalPort())
            .path(path);
        streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

        try {
            return bodyFuture.get(10, TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            synchronized (collected) {
                return collected.toString();
            }
        }
    }

    @Test
    public void shouldDeliverConcurrentInterleavedSseStreams() throws Exception {
        // GitHub issue #2667, the reporter's exact scenario. Two concurrent SSE streams share ONE
        // HTTP/2 connection. Stream A's SECOND event is delayed a few seconds; stream B completes
        // immediately inside that window. The stock HttpToHttp2ConnectionHandler routes bare content
        // frames by a single mutable current-stream-id, updated only when a head is written - so the
        // interleave A-head, A-chunk1, B-head, B-chunk, B-last (stream B ends), <delay>, A-chunk2
        // writes A's second chunk onto stream B, which no longer exists. A's client then hangs
        // forever and never receives END_STREAM.
        //
        // The fixed delay makes B finish inside A's window deterministically, so this is not flaky.
        // Asserting END_STREAM per stream (not just a body substring) is what catches the mis-route:
        // before the fix stream A never ends.
        mockServerClient.upsert(
            new Expectation(request().withPath("/http2_sse_a"))
                .thenRespondWithSse(
                    HttpSseResponse.sseResponse()
                        .withEvents(
                            SseEvent.sseEvent().withEvent("a-first").withData("a_one"),
                            SseEvent.sseEvent().withEvent("a-second").withData("a_two").withDelay(Delay.seconds(3))
                        )
                )
        );
        mockServerClient.upsert(
            new Expectation(request().withPath("/http2_sse_b"))
                .thenRespondWithSse(
                    HttpSseResponse.sseResponse()
                        .withEvents(
                            SseEvent.sseEvent().withEvent("b-first").withData("b_one"),
                            SseEvent.sseEvent().withEvent("b-second").withData("b_two")
                        )
                )
        );

        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplexParent(group);
            StringBuilder collectedA = new StringBuilder();
            StringBuilder collectedB = new StringBuilder();

            // open A first (its second event is delayed), then B on the SAME connection
            CompletableFuture<String> futureA = openStreamExpectingEnd(parent, HttpMethod.GET, "/http2_sse_a", null, collectedA);
            CompletableFuture<String> futureB = openStreamExpectingEnd(parent, HttpMethod.GET, "/http2_sse_b", null, collectedB);

            // B finishes well inside A's delay window; A only ends after its delayed second event
            String bodyB = awaitEnd(futureB, collectedB, "B");
            String bodyA = awaitEnd(futureA, collectedA, "A");

            assertThat("A body <" + bodyA + ">", bodyA, containsString("data: a_one"));
            assertThat("A body <" + bodyA + ">", bodyA, containsString("data: a_two"));
            assertThat("B body <" + bodyB + ">", bodyB, containsString("data: b_one"));
            assertThat("B body <" + bodyB + ">", bodyB, containsString("data: b_two"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldDeliverConcurrentInterleavedStreamingLlmResponses() throws Exception {
        // #2667 parity for httpLlmResponse streaming, which is served through the very same
        // HttpSseResponseActionHandler (HttpActionHandler builds an HttpSseResponse from the streamed
        // LLM events), so it was equally affected. Stream A streams SLOWLY (a low tokens-per-second
        // spaces its delta events hundreds of ms apart, the LLM-native equivalent of the SSE delay
        // lever), so B - a short, default-speed streaming completion - finishes inside A's window.
        // Before the fix A's later delta chunks, emitted after B's stream has ended, are mis-routed
        // onto B's finished stream and A hangs. (A per-token Delay lever - timeToFirstToken - would be
        // more direct, but StreamingPhysics.timeToFirstToken cannot be deserialized server-side today;
        // tokensPerSecond is a plain integer and validates, so it is used here.)
        LlmMockBuilder.llmMock("/v1/chat/completions/stream_a")
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .respondingWith(Completion.completion()
                .withText("alpha beta gamma delta epsilon zeta eta theta iota kappa")
                .withStreaming(true)
                .withStreamingPhysics(StreamingPhysics.streamingPhysics()
                    .withTokensPerSecond(3)))
            .applyTo(mockServerClient);
        LlmMockBuilder.llmMock("/v1/chat/completions/stream_b")
            .withProvider(Provider.OPENAI)
            .withModel("gpt-4o")
            .respondingWith(Completion.completion()
                .withText("bravo")
                .withStreaming(true))
            .applyTo(mockServerClient);

        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplexParent(group);
            StringBuilder collectedA = new StringBuilder();
            StringBuilder collectedB = new StringBuilder();
            String reqBody = "{\"model\":\"gpt-4o\",\"stream\":true,\"messages\":[{\"role\":\"user\",\"content\":\"Hi\"}]}";

            // open A first (its first token is delayed), then B on the SAME connection
            CompletableFuture<String> futureA = openStreamExpectingEnd(parent, HttpMethod.POST, "/v1/chat/completions/stream_a", reqBody, collectedA);
            CompletableFuture<String> futureB = openStreamExpectingEnd(parent, HttpMethod.POST, "/v1/chat/completions/stream_b", reqBody, collectedB);

            String bodyB = awaitEnd(futureB, collectedB, "LLM-B");
            String bodyA = awaitEnd(futureA, collectedA, "LLM-A");

            // each stream received its own full OpenAI SSE payload and ended
            assertThat("LLM-A body <" + bodyA + ">", bodyA, containsString("chat.completion.chunk"));
            assertThat("LLM-A body <" + bodyA + ">", bodyA, containsString("[DONE]"));
            assertThat("LLM-B body <" + bodyB + ">", bodyB, containsString("chat.completion.chunk"));
            assertThat("LLM-B body <" + bodyB + ">", bodyB, containsString("[DONE]"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    /**
     * Build an h2c parent connection with a multiplex handler, exactly like the sequential-reuse
     * test, so several {@link Http2StreamChannel}s can be opened on it concurrently.
     */
    private Channel connectMultiplexParent(NioEventLoopGroup group) throws Exception {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                        }
                    }));
                }
            });
        return bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();
    }

    /**
     * Open one stream on {@code parent}, send {@code method} {@code path} (with an optional request
     * body), and return a future that completes with the collected DATA-frame body ONLY when the
     * stream ends (END_STREAM on a DATA or HEADERS frame). A stream that never ends - the #2667
     * symptom - leaves the future incomplete, so {@link #awaitEnd} reports the hang.
     */
    private CompletableFuture<String> openStreamExpectingEnd(Channel parent, HttpMethod method, String path, String body, StringBuilder collected) throws Exception {
        CompletableFuture<String> bodyFuture = new CompletableFuture<>();
        Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
            .handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2DataFrame) {
                            Http2DataFrame data = (Http2DataFrame) msg;
                            synchronized (collected) {
                                collected.append(data.content().toString(StandardCharsets.UTF_8));
                            }
                            if (data.isEndStream()) {
                                bodyFuture.complete(collected.toString());
                            }
                        } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                            bodyFuture.complete(collected.toString());
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    bodyFuture.completeExceptionally(cause);
                }
            })
            .open()
            .sync()
            .getNow();

        boolean hasBody = body != null;
        Http2Headers headers = new DefaultHttp2Headers()
            .method(method.asciiName())
            .scheme(HttpScheme.HTTP.name())
            .authority("localhost:" + mockServer.getLocalPort())
            .path(path);
        if (hasBody) {
            headers.set("content-type", "application/json");
        }
        streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, !hasBody));
        if (hasBody) {
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(
                Unpooled.wrappedBuffer(body.getBytes(StandardCharsets.UTF_8)), true));
        }
        return bodyFuture;
    }

    /**
     * Await a stream's END_STREAM. Returns the collected body if the stream ended within the
     * timeout; fails the test (with what was collected) if it did not - which is precisely the
     * #2667 hang: the stream received a head and some data but never its terminal END_STREAM.
     */
    private String awaitEnd(CompletableFuture<String> future, StringBuilder collected, String label) throws Exception {
        try {
            return future.get(15, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            synchronized (collected) {
                fail("stream " + label + " never ended (END_STREAM not received) - collected: <" + collected + ">");
            }
            return null; // unreachable
        }
    }

    @Test
    public void shouldDeliverStaticResponseToRealHttp2Client() throws Exception {
        // control case: the non-streaming path has always been correct because it writes the model
        // response through the mapper. Its presence here keeps the comparison honest - if this ever
        // fails too, the problem is the harness rather than the streaming paths.
        mockServerClient
            .when(request().withPath("/http2_static"))
            .respond(org.mockserver.model.HttpResponse.response().withBody("static_over_http2"));

        String body = sendH2cRequestAndCollectBody("/http2_static");

        assertThat(body, containsString("static_over_http2"));
    }

    /**
     * Send a GET over h2c on a dedicated stream and collect every DATA frame delivered to that
     * stream, returning once the stream ends (or the connection closes). Returns whatever was
     * collected if the wait times out, so a failure reports "received nothing" rather than an
     * opaque {@link java.util.concurrent.TimeoutException}.
     */
    private String sendH2cRequestAndCollectBody(String path) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            StringBuilder collected = new StringBuilder();
            CompletableFuture<String> bodyFuture = new CompletableFuture<>();

            Bootstrap bootstrap = new Bootstrap()
                .group(group)
                .channel(NioSocketChannel.class)
                .handler(new ChannelInitializer<SocketChannel>() {
                    @Override
                    protected void initChannel(SocketChannel ch) {
                        ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                        ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                            @Override
                            protected void initChannel(Channel ch) {
                                // the server must not initiate streams for a response; if it does
                                // (the exact bug under test) nothing lands on our stream and the
                                // collected body stays empty
                                ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                            }
                        }));
                    }
                });

            Channel parent = bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();

            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        try {
                            if (msg instanceof Http2DataFrame) {
                                Http2DataFrame data = (Http2DataFrame) msg;
                                synchronized (collected) {
                                    collected.append(data.content().toString(StandardCharsets.UTF_8));
                                }
                                if (data.isEndStream()) {
                                    bodyFuture.complete(collected.toString());
                                }
                            } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                                bodyFuture.complete(collected.toString());
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void channelInactive(ChannelHandlerContext ctx) {
                        // SSE closes the connection at end of stream by default
                        synchronized (collected) {
                            bodyFuture.complete(collected.toString());
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        bodyFuture.completeExceptionally(cause);
                    }
                })
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path(path);
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            try {
                return bodyFuture.get(10, TimeUnit.SECONDS);
            } catch (java.util.concurrent.TimeoutException timeout) {
                // report what did arrive - "nothing" is the diagnosis this test exists to deliver
                synchronized (collected) {
                    return collected.toString();
                }
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }
}

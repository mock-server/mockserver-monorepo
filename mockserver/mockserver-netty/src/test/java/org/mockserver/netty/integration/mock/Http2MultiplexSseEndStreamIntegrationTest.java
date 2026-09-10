package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpSseResponse;
import org.mockserver.model.SseEvent;
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
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that an SSE streaming response served on the HTTP/2 <em>multiplex</em> pipeline
 * (gRPC bidi streaming on + a startup-loaded gRPC descriptor with services — the same enablement as
 * {@link Http2MultiplexGoAwayIntegrationTest}) actually <strong>terminates</strong> the client's
 * stream, not merely that its events arrive.
 * <p>
 * On the multiplex path every stream is a per-stream {@link Http2StreamChannel}, so a streaming
 * response's frames — written by {@code HttpSseResponseActionHandler} as
 * {@code StreamAddressedHttpContent} because {@code getStreamId()} is non-null there — flow through
 * {@link io.netty.handler.codec.http2.Http2StreamFrameToHttpObjectCodec} on the child pipeline. That
 * codec's bare-{@code HttpContent} branch hard-codes {@code endStream=false}, so the terminal frame's
 * {@code endStream=true} is silently discarded unless {@code StreamAddressedContentHandler} translates
 * it into a {@code LastHttpContent} first. Without that handler the events all arrive but the stream
 * never ends and the client hangs until it times out — which is exactly what this test asserts
 * against: {@link #shouldEndTheStreamForAnSseResponseOnTheMultiplexPath} completes only on a real
 * END_STREAM DATA frame, so before the fix it fails via the bounded {@code awaitEnd} timeout.
 * <p>
 * An in-JVM Netty h2c multiplex client is used so the client's own stream END_STREAM can be asserted
 * directly (the shared integration harness cannot aggregate {@code text/event-stream}).
 */
public class Http2MultiplexSseEndStreamIntegrationTest {

    // load a gRPC descriptor at startup so the HTTP/2 multiplex path engages (matches
    // Http2MultiplexGoAwayIntegrationTest); the descriptor's presence, not gRPC traffic, selects it.
    private static final String GRPC_DESCRIPTOR_DIRECTORY = "../mockserver-core/src/test/resources/grpc";

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        Configuration configuration = configuration()
            .grpcBidiStreamingEnabled(true)
            .grpcDescriptorDirectory(GRPC_DESCRIPTOR_DIRECTORY);
        mockServer = new MockServer(configuration);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.upsert(
            new Expectation(request().withPath("/http2_multiplex_sse"))
                .thenRespondWithSse(
                    HttpSseResponse.sseResponse()
                        .withEvents(
                            SseEvent.sseEvent().withEvent("first").withData("sse_event_one"),
                            SseEvent.sseEvent().withEvent("second").withData("sse_event_two")
                        )
                )
        );
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test(timeout = 30000)
    public void shouldEndTheStreamForAnSseResponseOnTheMultiplexPath() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplexParent(group);

            StringBuilder collected = new StringBuilder();
            AtomicBoolean endStreamSeen = new AtomicBoolean(false);
            CompletableFuture<String> ended = new CompletableFuture<>();

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
                                    endStreamSeen.set(true);
                                    ended.complete(collected.toString());
                                }
                            } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                                endStreamSeen.set(true);
                                ended.complete(collected.toString());
                            }
                        } finally {
                            ReferenceCountUtil.release(msg);
                        }
                    }

                    @Override
                    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                        ended.completeExceptionally(cause);
                    }
                })
                .open()
                .sync()
                .getNow();

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.GET.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path("/http2_multiplex_sse");
            streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));

            // the stream MUST end. Before the fix the events arrive but END_STREAM never does, so this
            // future stays incomplete and the bounded await below fails with what was collected.
            String body;
            try {
                body = ended.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException timeout) {
                synchronized (collected) {
                    fail("SSE stream on the multiplex path never ended (END_STREAM not received) - "
                        + "events collected: <" + collected + ">");
                }
                return; // unreachable
            }

            // the events arrived (they always did) AND the stream terminated cleanly
            assertThat("the client must observe END_STREAM on its own stream", endStreamSeen.get(), is(true));
            assertThat("SSE body <" + body + ">", body, containsString("event: first"));
            assertThat("SSE body <" + body + ">", body, containsString("data: sse_event_one"));
            assertThat("SSE body <" + body + ">", body, containsString("event: second"));
            assertThat("SSE body <" + body + ">", body, containsString("data: sse_event_two"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

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
}

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
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
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
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that the dashboard is actually delivered to a real HTTP/2 client.
 * <p>
 * {@link org.mockserver.dashboard.DashboardHandler#renderDashboard} builds a model response and
 * writes it straight to the channel, bypassing the ResponseWriter path. If it does not copy the
 * request's HTTP/2 stream id onto that response, {@code HttpToHttp2ConnectionHandler} routes the
 * response head onto a fresh <em>server-initiated</em> stream (see {@code Http2StreamIds}). The
 * server logs a normal successful response; the client's stream receives nothing and hangs until
 * timeout — exactly the shape that {@code Http2StreamIdAuditHandler} warns about. This is the
 * same defect class as GitHub issue #2419 and the SSE/streaming/metrics instances.
 * <p>
 * The test drives a real in-JVM Netty HTTP/2 (h2c) multiplex client, opens a stream, requests a
 * dashboard asset, and asserts that the body arrived on <em>the client's own stream</em> AND that
 * the stream reached END_STREAM. Before the fix the stream never ends and the collected body is
 * empty; the {@link #awaitBody} timeout then fails the test with "received nothing".
 * <p>
 * A synthetic dashboard fixture ({@code multibyte-test.js}) that is always on the test classpath is
 * used rather than the built UI bundle, so this test executes unconditionally rather than skipping
 * when the {@code build-ui} profile has not run.
 */
public class DashboardHttp2IntegrationTest {

    // Synthetic fixture served at /org/mockserver/dashboard on the test classpath (added for issue
    // #2347). Present regardless of whether the real UI bundle was built, so this test never skips.
    private static final String DASHBOARD_ASSET_PATH = "/mockserver/dashboard/multibyte-test.js";
    private static final String EXPECTED_BODY_MARKER = "regression fixture for issue #2347";

    private static MockServer mockServer;

    @BeforeClass
    public static void startServer() {
        mockServer = new MockServer();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
    }

    @Test
    public void shouldDeliverDashboardAssetToRealHttp2Client() throws Exception {
        // given - a real HTTP/2 (h2c) multiplex client requests a dashboard asset on its own stream
        AtomicBoolean endStreamReceived = new AtomicBoolean(false);

        // when
        String body = sendH2cRequestAndCollectBody(DASHBOARD_ASSET_PATH, endStreamReceived);

        // then - the asset arrived on the client's own stream and the stream reached END_STREAM.
        // Before the fix (no stream id stamped on the dashboard response) this body is empty because
        // the whole response went out on a phantom server-initiated stream and the client hung.
        assertThat("dashboard body received: <" + body + ">", body, containsString(EXPECTED_BODY_MARKER));
        assertThat("dashboard response must reach END_STREAM on the client's own stream",
            endStreamReceived.get(), is(true));
    }

    /**
     * Open a GET on a dedicated h2c stream, collect every DATA frame delivered to that stream, and
     * record whether the stream reached END_STREAM. Returns whatever was collected if the wait times
     * out, so a hang reads as "received nothing" rather than an opaque {@link TimeoutException}.
     */
    private String sendH2cRequestAndCollectBody(String path, AtomicBoolean endStreamReceived) throws Exception {
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
                                    endStreamReceived.set(true);
                                    bodyFuture.complete(collected.toString());
                                }
                            } else if (msg instanceof Http2HeadersFrame && ((Http2HeadersFrame) msg).isEndStream()) {
                                endStreamReceived.set(true);
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

            return awaitBody(bodyFuture, collected);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private String awaitBody(CompletableFuture<String> bodyFuture, StringBuilder collected) throws Exception {
        try {
            return bodyFuture.get(10, TimeUnit.SECONDS);
        } catch (TimeoutException timeout) {
            // report what did arrive - "nothing" is the diagnosis this test exists to deliver
            synchronized (collected) {
                fail("dashboard stream never ended (END_STREAM not received) - collected: <" + collected + ">");
            }
            return null; // unreachable
        }
    }
}

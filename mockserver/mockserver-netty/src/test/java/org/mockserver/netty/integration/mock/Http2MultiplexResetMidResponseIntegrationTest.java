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
import org.mockserver.mock.action.http.PreemptionSimulator;
import org.mockserver.mock.action.http.TcpChaosRegistry;
import org.mockserver.model.Delay;
import org.mockserver.model.TcpChaosProfile;
import org.mockserver.netty.MockServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that the two connection-teardown features are correctly SPLIT on the HTTP/2
 * <em>multiplex</em> pipeline (issue #2669), where each stream is its own {@link Http2StreamChannel}
 * child whose parent is the shared TCP connection channel:
 * <ul>
 *   <li><b>{@code resetMidResponse} connection-lifecycle chaos</b> simulates a genuine socket abort,
 *       so it must force a real TCP RST on the <em>parent</em> connection — killing every concurrent
 *       stream on it. If it degraded into an ordinary {@code RST_STREAM} on the one stream child, a
 *       fault whose whole purpose is to abort the socket would silently become something weaker.</li>
 *   <li><b>per-expectation {@code closeSocket}</b> must stay per-stream: ending one HTTP/2 stream is
 *       the correct semantic, and one expectation must not destroy unrelated concurrent requests on
 *       the same connection.</li>
 * </ul>
 *
 * <p>Both behaviours are indistinguishable without a <em>sibling</em> stream on the same connection,
 * so every test opens two concurrent streams and asserts what happens to the sibling / the shared
 * parent connection. The multiplex server branch is engaged by {@code grpcBidiStreamingEnabled(true)}
 * plus a startup-loaded gRPC descriptor with services (its presence, not gRPC traffic, selects the
 * multiplex pipeline) — the same enablement used by {@link Http2MultiplexGoAwayIntegrationTest}. An
 * in-JVM Netty h2c (prior-knowledge) multiplex client is used so the parent connection channel is
 * directly observable.
 *
 * @author jamesdbloom
 */
public class Http2MultiplexResetMidResponseIntegrationTest {

    // load a gRPC descriptor at startup so the HTTP/2 multiplex path engages; the descriptor's
    // presence, not gRPC traffic, is what selects the multiplex pipeline.
    private static final String GRPC_DESCRIPTOR_DIRECTORY = "../mockserver-core/src/test/resources/grpc";

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        Configuration configuration = configuration()
            .grpcBidiStreamingEnabled(true)
            .grpcDescriptorDirectory(GRPC_DESCRIPTOR_DIRECTORY)
            .connectionLifecycleChaosEnabled(true);
        mockServer = new MockServer(configuration);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withPath("/ok"))
            .respond(response().withStatusCode(200).withBody("hello-world"));
        // a slow sibling whose delay far outlasts the test, so it can only ever finish if the
        // connection is torn down for it (never by a normal response arriving)
        mockServerClient
            .when(request().withPath("/slow"))
            .respond(response().withStatusCode(200).withBody("slow-done").withDelay(Delay.seconds(60)));
        // a per-expectation closeSocket response (NOT chaos) — ends its own HTTP/2 stream only
        mockServerClient
            .when(request().withPath("/reset"))
            .respond(response().withStatusCode(200).withBody("bye")
                .withConnectionOptions(connectionOptions().withCloseSocket(true)));
        TcpChaosRegistry.getInstance().reset();
        PreemptionSimulator.getInstance().reset();
    }

    @After
    public void stopServer() {
        TcpChaosRegistry.getInstance().reset();
        PreemptionSimulator.getInstance().reset();
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test(timeout = 30000)
    public void resetMidResponseChaosMustAbortTheWholeConnectionIncludingSiblingStream() throws Exception {
        // given - a host-scoped resetMidResponse chaos profile on the authority the requests carry
        String authority = "localhost:" + mockServer.getLocalPort();
        TcpChaosRegistry.getInstance().put(authority, TcpChaosProfile.tcpChaosProfile().withResetMidResponse(true));

        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplex(group);

            // a slow sibling stream that would NOT have written its own response (and so not fired its
            // own response-path fault) within the test window
            CompletableFuture<Integer> siblingStatus = new CompletableFuture<>();
            openStream(parent, "/slow", authority, siblingStatus);

            // the trigger stream: a normal request that fires resetMidResponse, forcing a real TCP RST
            // on the PARENT connection
            CompletableFuture<Integer> triggerStatus = new CompletableFuture<>();
            openStream(parent, "/ok", authority, triggerStatus);

            // then - the whole TCP connection is torn down. On the buggy per-stream behaviour only the
            // trigger stream's child channel would close (RST_STREAM) and the parent connection - with
            // the sibling still on it - would survive.
            assertThat("the multiplex resetMidResponse chaos must abort the whole TCP connection",
                parent.closeFuture().await(15, TimeUnit.SECONDS), is(true));

            // and - the sibling was killed by the connection RST, not served (its /slow delay far
            // outlasts the RST, so a non-negative status here could only be a normal response)
            assertThat("the sibling stream must be killed by the connection RST, not served",
                siblingStatus.getNow(-1), is(-1));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 30000)
    public void closeSocketMustEndOnlyItsOwnStreamAndLeaveTheSiblingServed() throws Exception {
        // given - NO chaos; a per-expectation closeSocket response on /reset, and a normal sibling
        String authority = "localhost:" + mockServer.getLocalPort();

        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplex(group);

            // the sibling stream: a plain /ok that must complete normally despite the closeSocket on
            // the other stream
            CompletableFuture<Integer> siblingStatus = new CompletableFuture<>();
            openStream(parent, "/ok", authority, siblingStatus);

            // the closeSocket stream
            CompletableFuture<Integer> closeSocketStatus = new CompletableFuture<>();
            openStream(parent, "/reset", authority, closeSocketStatus);

            // then - the sibling still completes normally: closeSocket ends one HTTP/2 stream, it does
            // NOT abort the shared TCP connection
            assertThat("the sibling stream must complete normally under a per-stream closeSocket",
                siblingStatus.get(15, TimeUnit.SECONDS), is(200));

            // and - the shared TCP connection is NOT reset (still alive after the closeSocket)
            assertThat("closeSocket must NOT tear down the shared TCP connection",
                parent.closeFuture().await(1, TimeUnit.SECONDS), is(false));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    // ----- multiplex h2c client harness (mirrors Http2MultiplexGoAwayIntegrationTest) -----

    private Channel connectMultiplex(NioEventLoopGroup group) throws Exception {
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
     * Open one multiplex stream child on {@code parent}, send a {@code GET} for {@code path}, and
     * complete {@code statusFuture} with the {@code :status} of the first response HEADERS frame.
     */
    private void openStream(Channel parent, String path, String authority, CompletableFuture<Integer> statusFuture) throws Exception {
        Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
            .handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    if (msg instanceof Http2HeadersFrame) {
                        CharSequence status = ((Http2HeadersFrame) msg).headers().status();
                        if (status != null && !statusFuture.isDone()) {
                            statusFuture.complete(Integer.parseInt(status.toString()));
                        }
                    }
                    ReferenceCountUtil.release(msg);
                }
            })
            .open()
            .sync()
            .getNow();

        Http2Headers headers = new DefaultHttp2Headers()
            .method(HttpMethod.GET.asciiName())
            .scheme(HttpScheme.HTTP.name())
            .authority(authority)
            .path(path);
        streamChannel.writeAndFlush(new DefaultHttp2HeadersFrame(headers, true));
    }
}

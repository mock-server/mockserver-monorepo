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
import io.netty.handler.codec.http2.Http2GoAwayFrame;
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
import org.mockserver.model.PreemptionRequest;
import org.mockserver.model.TcpChaosProfile;
import org.mockserver.netty.MockServer;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that a connection-level HTTP/2 {@code GOAWAY} actually reaches the client when the
 * request is served on the <em>multiplex</em> pipeline (gRPC bidi streaming on + a startup-loaded gRPC
 * descriptor with services), for both callers that emit one:
 * <ul>
 *   <li><b>{@code http2GoAway} chaos</b> — a host-scoped {@link TcpChaosProfile} with
 *       {@code http2GoAway} set, emitted by {@code NettyResponseWriter} on the response path.</li>
 *   <li><b>preemption drain</b> — a {@code goaway}-mode {@link PreemptionRequest} cordon, emitted by
 *       {@code HttpRequestHandler} to tell HTTP/2 clients to drain and retry elsewhere.</li>
 * </ul>
 *
 * <p>Both signals are emitted through {@code Http2GoAwayEmitter} from a handler running on a per-stream
 * CHILD channel, whose own pipeline has no {@code Http2ConnectionHandler} — that lives on the PARENT
 * connection channel. Before the emitter learned to walk up to the parent, {@code emit} returned
 * {@code false} on the child and both signals were silently dropped: the client never observed a GOAWAY
 * and this test's {@code goAwayErrorCode} future timed out.
 *
 * <p>Uses an in-JVM Netty HTTP/2 (h2c prior-knowledge) multiplex client so the connection-level GOAWAY
 * frame the client observes can be asserted directly. The multiplex server branch is selected for both
 * cleartext (h2c) and TLS (h2) HTTP/2, so h2c is sufficient here and keeps the harness simple.
 *
 * @author jamesdbloom
 */
public class Http2MultiplexGoAwayIntegrationTest {

    // load a gRPC descriptor at startup so the HTTP/2 multiplex path engages (matches
    // Http2MultiplexProxiedForwardIntegrationTest); the descriptor's presence, not gRPC traffic, is
    // what selects the multiplex pipeline.
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
        // clear any leftover chaos / cordon state from another test in the JVM
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
    public void shouldEmitHttp2GoAwayChaosOnMultiplexStream() throws Exception {
        // given - a host-scoped http2GoAway chaos profile with a non-default error code (0xb) on the
        // host the request will carry
        String authority = "localhost:" + mockServer.getLocalPort();
        TcpChaosRegistry.getInstance().put(authority, new TcpChaosProfile()
            .withHttp2GoAway(true)
            .withHttp2GoAwayErrorCode(0xbL));

        // when - a normal request is served over the multiplex pipeline
        GoAwayObservation observation = sendH2cRequestOverMultiplex("/ok", authority);

        // then - the client observed a connection-level GOAWAY carrying the CONFIGURED error code
        // (0xb), which proves both that the emitter reached the connection handler on the parent
        // channel and that the chaos profile's error code was propagated through.
        //
        // Note: the matched response is NOT asserted here. A GOAWAY carrying a NON-ZERO error code
        // makes the Netty client tear the connection down, which races the delivery of the response
        // head on the stream — so observing the response is inherently timing-dependent under a
        // non-graceful error code. The "the in-flight response still completes" semantics of a
        // graceful (NO_ERROR) GOAWAY are covered deterministically by the preemption test below.
        assertThat("the client must observe the http2GoAway chaos GOAWAY with the configured error code",
            observation.goAwayErrorCode, is(0xbL));
    }

    @Test(timeout = 30000)
    public void shouldEmitPreemptionDrainGoAwayOnMultiplexStream() throws Exception {
        // given - the server is cordoned in goaway-only mode: HTTP/2 clients get a GOAWAY drain signal
        // while the in-flight request is still served (no 503)
        PreemptionSimulator.getInstance().start(PreemptionRequest.preemptionRequest()
            .withMode(PreemptionRequest.Mode.goaway)
            .withTtlMillis(60_000L));

        String authority = "localhost:" + mockServer.getLocalPort();

        // when - a new request hits the cordoned connection over the multiplex pipeline
        GoAwayObservation observation = sendH2cRequestOverMultiplex("/ok", authority);

        // then - the client observed a connection-level GOAWAY with NO_ERROR (graceful drain) and the
        // request was still served (goaway-only mode falls through rather than rejecting)
        assertThat("the client must observe a preemption-drain GOAWAY frame",
            observation.goAwayErrorCode, is(0L));
        assertThat("the request must still be served in goaway-only mode", observation.responseStatus, is(200));
    }

    /**
     * Connect an h2c multiplex client, open one stream, send a GET, and capture both the response status
     * (on the stream child channel) and any connection-level GOAWAY frame (on the parent connection
     * channel, downstream of the multiplex handler — where Netty forwards non-stream frames).
     */
    private GoAwayObservation sendH2cRequestOverMultiplex(String path, String authority) throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            CompletableFuture<Long> goAwayErrorCode = new CompletableFuture<>();
            CompletableFuture<Integer> responseStatus = new CompletableFuture<>();

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
                        // connection-level GOAWAY capture: Http2MultiplexHandler forwards non-stream
                        // frames (GOAWAY, SETTINGS) down the parent pipeline, so this handler sees them.
                        ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                if (msg instanceof Http2GoAwayFrame && !goAwayErrorCode.isDone()) {
                                    goAwayErrorCode.complete(((Http2GoAwayFrame) msg).errorCode());
                                }
                                ReferenceCountUtil.release(msg);
                            }
                        });
                    }
                });

            Channel parent = bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();

            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ChannelInboundHandlerAdapter() {
                    @Override
                    public void channelRead(ChannelHandlerContext ctx, Object msg) {
                        if (msg instanceof Http2HeadersFrame) {
                            CharSequence status = ((Http2HeadersFrame) msg).headers().status();
                            if (status != null && !responseStatus.isDone()) {
                                responseStatus.complete(Integer.parseInt(status.toString()));
                            }
                        }
                        ReferenceCountUtil.release(msg);
                    }

                    @Override
                    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                        // Netty may also surface a connection GOAWAY to an affected stream child as a
                        // user event; accept it here too so the capture is robust.
                        if (evt instanceof Http2GoAwayFrame && !goAwayErrorCode.isDone()) {
                            goAwayErrorCode.complete(((Http2GoAwayFrame) evt).errorCode());
                        }
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

            long errorCode = goAwayErrorCode.get(15, TimeUnit.SECONDS);
            // the response head usually arrives before the connection GOAWAY completes the future, but
            // give it a brief grace window so the assertion on it is not racy
            Integer status;
            try {
                status = responseStatus.get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                status = responseStatus.getNow(null);
            }
            return new GoAwayObservation(errorCode, status);
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private static final class GoAwayObservation {
        private final long goAwayErrorCode;
        private final Integer responseStatus;

        private GoAwayObservation(long goAwayErrorCode, Integer responseStatus) {
            this.goAwayErrorCode = goAwayErrorCode;
            this.responseStatus = responseStatus;
        }
    }
}

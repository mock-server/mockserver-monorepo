package org.mockserver.netty.integration.mock;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.netty.MockServer;
import org.mockserver.scheduler.Scheduler;

import java.nio.file.Files;
import java.nio.file.Paths;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * End-to-end proof that connection-scoped channel state survives the HTTP/2 multiplex split onto
 * per-stream child channels. Since issue #2669 {@code PortUnificationHandler.switchToHttp2Multiplex}
 * installs {@code Http2MultiplexHandler} + {@link org.mockserver.netty.unification.Http2MultiplexChildInitializer}
 * for every HTTP/2 connection; with {@code grpcBidiStreamingEnabled} on and a gRPC descriptor with
 * services loaded (as here) the child initializer installs a per-stream {@code GrpcBidiRouterHandler}.
 * <p>
 * A plain (non-gRPC) HTTP request over such a connection is routed by
 * {@code GrpcBidiRouterHandler} onto the re-aggregating child pipeline, where every handler reads
 * connection-scoped state from {@code ctx.channel()} — which, on an {@code Http2StreamChannel}, is
 * the child and does <em>not</em> inherit the parent connection channel's attributes unless
 * {@link org.mockserver.netty.unification.ConnectionScopeHandler} copies them across.
 * <p>
 * Before issue #2669 the existing {@code HTTP2MockingIntegrationTest},
 * {@code ProxyPassMappingHttp2UpgradeIntegrationTest} and
 * {@code UnmatchedForwardHttp2UpgradeIntegrationTest} ran with the multiplex path OFF (default config,
 * no descriptor), so none of them exercised this pipeline — which is why the defects shipped. Since the
 * flip they all run on the multiplex path too, so they now provide the broad end-to-end coverage of it;
 * this test remains the focused proof of the connection-scoped attribute copying itself.
 *
 * @author jamesdbloom
 */
public class Http2MultiplexConnectionScopeIntegrationTest {

    private static final String GREETING_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private EventLoopGroup clientEventLoopGroup;
    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void setUp() throws Exception {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(Http2MultiplexConnectionScopeIntegrationTest.class.getSimpleName() + "-eventLoop"));
        byte[] greetingDescriptorBytes = Files.readAllBytes(Paths.get(GREETING_DESCRIPTOR));

        // grpcBidiStreamingEnabled + a descriptor with services installs the multiplex HTTP/2 pipeline,
        // so every HTTP/2 stream gets its own child channel (this is the pipeline the defects live on).
        mockServer = new MockServer(configuration().grpcBidiStreamingEnabled(true));
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.uploadGrpcDescriptor(greetingDescriptorBytes);
    }

    @After
    public void tearDown() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    private HttpResponse sendSecureHttp2(String path) throws Exception {
        return new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            null,
            false
        ).sendRequest(
            request()
                .withMethod("GET")
                .withPath(path)
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                .withHeader(HOST.toString(), "localhost:" + mockServer.getLocalPort())
        ).get(15, SECONDS);
    }

    /**
     * Row 1 — protocol detection. Two expectations discriminated only by protocol: the request must
     * be recognised as {@code HTTP_2} on the multiplexed child stream. Without
     * {@code ConnectionScopeHandler}, {@code SniHandler.getALPNProtocol} reads {@code null} on the
     * child (no {@code NEGOTIATED_APPLICATION_PROTOCOL} / {@code UPSTREAM_SSL_HANDLER}), so the
     * request never matches {@code withProtocol(HTTP_2)} and this returns the HTTP/1.1 body (or 404).
     */
    @Test(timeout = 30000)
    public void shouldDetectHttp2ProtocolOnMultiplexedChildStream() throws Exception {
        mockServerClient
            .when(request().withPath("/scope").withProtocol(Protocol.HTTP_2))
            .respond(response().withStatusCode(201).withBody("saw_http2"));
        mockServerClient
            .when(request().withPath("/scope").withProtocol(Protocol.HTTP_1_1))
            .respond(response().withStatusCode(200).withBody("saw_http1"));

        HttpResponse response = sendSecureHttp2("/scope");

        assertThat(response.getStatusCode(), is(201));
        assertThat(response.getBodyAsString(), is("saw_http2"));
    }

    /**
     * Row 2 — WebSocket 501. Over HTTP/2 the callback and dashboard WebSocket upgrade endpoints must
     * answer {@code 501 Not Implemented} (WebSocket is not supported over HTTP/2). That branch is
     * gated on {@code PortUnificationHandler.isHttp2Enabled(ctx.channel())}, which reads
     * {@code HTTP2_ENABLED} — absent on a bare child stream, so without the fix the handler instead
     * attempts an HTTP/1.1 WebSocket handshake over the stream.
     */
    @Test(timeout = 30000)
    public void shouldReturn501ForCallbackWebSocketUpgradeOverHttp2() throws Exception {
        assertThat(sendSecureHttp2("/_mockserver_callback_websocket").getStatusCode(), is(501));
    }

    @Test(timeout = 30000)
    public void shouldReturn501ForDashboardWebSocketUpgradeOverHttp2() throws Exception {
        assertThat(sendSecureHttp2("/_mockserver_ui_websocket").getStatusCode(), is(501));
    }
}

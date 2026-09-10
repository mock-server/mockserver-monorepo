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

import java.net.InetSocketAddress;

import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * End-to-end proof that a <em>proxied</em> request over an HTTP/2 multiplexed stream is forwarded to the
 * connection's recorded remote target (the {@code REMOTE_SOCKET} attribute — the CONNECT /
 * original-destination / port-forward target), not to a {@code Host}-header-derived one.
 * <p>
 * {@code REMOTE_SOCKET} is the connection-scoped sibling of {@code PROXYING}: both are set on the
 * connection (parent) channel — here by the port-forwarding server bootstrap — and both are read on the
 * forward path from {@code ctx.channel()}, which on the multiplex pipeline is the per-stream child
 * channel. Propagating only {@code PROXYING} (so the request is still recognised as proxied) while
 * dropping {@code REMOTE_SOCKET} makes {@code HttpActionHandler.getRemoteAddress} return {@code null},
 * and the forward silently falls back to the {@code Host} header — a misroute wherever the two differ.
 * <p>
 * Topology: an HTTP/1.1-over-TLS + HTTP/2 client → a port-forwarding "proxy" MockServer (bidi multiplex
 * on, a gRPC descriptor loaded so the multiplex path is taken) whose fixed remote target is
 * {@code remoteTarget}; the request's {@code Host} header points at a different server, {@code hostTarget}.
 * The two targets return distinct bodies, so the response body proves which one the forward reached.
 *
 * @author jamesdbloom
 */
public class Http2MultiplexProxiedForwardIntegrationTest {

    // load a gRPC descriptor at startup (so the multiplex path engages) via configuration rather than a
    // control-plane upload, which is the reliable way to configure a port-forwarding server.
    private static final String GRPC_DESCRIPTOR_DIRECTORY = "../mockserver-core/src/test/resources/grpc";

    private EventLoopGroup clientEventLoopGroup;
    private MockServer remoteTarget;   // the REMOTE_SOCKET (port-forward) target
    private MockServer hostTarget;     // the Host-header target (must NOT be reached)
    private MockServer proxy;          // port-forwarding to remoteTarget, multiplex on
    private MockServerClient remoteTargetClient;
    private MockServerClient hostTargetClient;

    @Before
    public void setUp() {
        clientEventLoopGroup = new NioEventLoopGroup(3, new Scheduler.SchedulerThreadFactory(Http2MultiplexProxiedForwardIntegrationTest.class.getSimpleName() + "-eventLoop"));

        remoteTarget = new MockServer();
        hostTarget = new MockServer();
        remoteTargetClient = new MockServerClient("localhost", remoteTarget.getLocalPort());
        hostTargetClient = new MockServerClient("localhost", hostTarget.getLocalPort());
        remoteTargetClient.when(request().withPath("/proxied")).respond(response().withStatusCode(200).withBody("reached_via_remote_socket"));
        hostTargetClient.when(request().withPath("/proxied")).respond(response().withStatusCode(200).withBody("reached_via_host_header"));

        // port-forwarding proxy: the server bootstrap stamps REMOTE_SOCKET=remoteTarget and PROXYING=true
        // on every connection channel; bidi + a startup-loaded gRPC descriptor selects the HTTP/2
        // multiplex child pipeline (the path where child channels do not inherit connection attributes).
        proxy = new MockServer(
            configuration()
                .grpcBidiStreamingEnabled(true)
                .grpcDescriptorDirectory(GRPC_DESCRIPTOR_DIRECTORY),
            remoteTarget.getLocalPort(), "127.0.0.1", 0);
    }

    @After
    public void tearDown() {
        stopQuietly(remoteTargetClient);
        stopQuietly(hostTargetClient);
        stopQuietly(proxy);
        stopQuietly(remoteTarget);
        stopQuietly(hostTarget);
        if (clientEventLoopGroup != null) {
            clientEventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
        }
    }

    @Test(timeout = 30000)
    public void shouldForwardProxiedHttp2StreamToRemoteSocketTargetNotHostHeader() throws Exception {
        HttpResponse response = new NettyHttpClient(
            configuration(),
            new MockServerLogger(),
            clientEventLoopGroup,
            null,
            false
        ).sendRequest(
            request()
                .withMethod("GET")
                .withPath("/proxied")
                .withSecure(true)
                .withProtocol(Protocol.HTTP_2)
                // Host points at hostTarget, deliberately DIFFERENT from the REMOTE_SOCKET target
                .withHeader(HOST.toString(), "127.0.0.1:" + hostTarget.getLocalPort()),
            // connect to the PROXY (not the Host-header target) so the request actually traverses the
            // port-forwarding proxy's multiplex pipeline
            new InetSocketAddress("localhost", proxy.getLocalPort())
        ).get(15, SECONDS);

        // forwarded to the REMOTE_SOCKET target, not the Host-header target
        assertThat(response.getBodyAsString(), is("reached_via_remote_socket"));
        remoteTargetClient.verify(request().withPath("/proxied"), exactly(1));
        hostTargetClient.verify(request().withPath("/proxied"), exactly(0));
    }
}

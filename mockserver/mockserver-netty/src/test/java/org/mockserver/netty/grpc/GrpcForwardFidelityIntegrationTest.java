package org.mockserver.netty.grpc;

import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.mockserver.model.HttpForward;
import org.mockserver.netty.MockServer;

import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;

/**
 * The client calls MockServer over h2c and a {@code FORWARD} expectation sends the call to the
 * upstream over TLS.
 */
public class GrpcForwardFidelityIntegrationTest extends AbstractGrpcProxyFidelityIntegrationTest {

    @Override
    protected MockServer startProxy() {
        return new MockServer();
    }

    @Override
    protected void routeToUpstream(int upstreamPort) {
        proxyClient
            .when(request().withMethod("POST").withPath("/" + SERVICE + "/.*"))
            .forward(forward()
                .withHost("127.0.0.1")
                .withPort(upstreamPort)
                .withScheme(HttpForward.Scheme.HTTPS));
    }

    @Override
    protected ManagedChannel newChannel(int upstreamPort) {
        return NettyChannelBuilder.forAddress("localhost", proxy.getLocalPort())
            .usePlaintext()
            .maxInboundMessageSize(8 * 1024 * 1024)
            .build();
    }
}

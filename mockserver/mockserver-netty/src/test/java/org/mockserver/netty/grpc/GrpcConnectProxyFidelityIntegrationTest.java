package org.mockserver.netty.grpc;

import io.grpc.HttpConnectProxiedSocketAddress;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.mockserver.netty.MockServer;

import java.io.InputStream;
import java.net.InetSocketAddress;

import static org.mockserver.configuration.Configuration.configuration;

/**
 * The client sends its call through MockServer as an HTTPS proxy ({@code HTTP CONNECT}), trusting
 * MockServer's certificate authority, and MockServer relays every call it has no expectation for to
 * the upstream ({@code attemptToProxyIfNoMatchingExpectation}) over HTTP/2.
 */
public class GrpcConnectProxyFidelityIntegrationTest extends AbstractGrpcProxyFidelityIntegrationTest {

    private static final String CERTIFICATE_AUTHORITY = "org/mockserver/socket/CertificateAuthorityCertificate.pem";

    @Override
    protected MockServer startProxy() {
        return new MockServer(configuration()
            .attemptToProxyIfNoMatchingExpectation(true)
            .forwardProxyHttp2Enabled(true));
    }

    @Override
    protected void routeToUpstream(int upstreamPort) {
        // every call that matches no expectation is proxied to the CONNECT target
    }

    @Override
    protected ManagedChannel newChannel(int upstreamPort) throws Exception {
        InetSocketAddress proxyAddress = new InetSocketAddress("127.0.0.1", proxy.getLocalPort());
        try (InputStream certificateAuthority = getClass().getClassLoader().getResourceAsStream(CERTIFICATE_AUTHORITY)) {
            return NettyChannelBuilder.forAddress("localhost", upstreamPort)
                .proxyDetector(targetAddress -> HttpConnectProxiedSocketAddress.newBuilder()
                    .setProxyAddress(proxyAddress)
                    .setTargetAddress(new InetSocketAddress("127.0.0.1", upstreamPort))
                    .build())
                .sslContext(GrpcSslContexts.forClient().trustManager(certificateAuthority).build())
                .maxInboundMessageSize(8 * 1024 * 1024)
                .build();
        }
    }
}

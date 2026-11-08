package org.mockserver.mock.action.http;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.ForwardCredentialHeadersTestSupport;
import org.mockserver.httpclient.TlsTestUpstream;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.mockserver.socket.tls.NettySslContextFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * A WebSocket upgrade relayed to an upstream does not go through the forward client, so the relay adds the
 * {@code forwardCredentialHeadersByHost} headers to the upstream handshake itself.
 */
public class WebSocketProxyRelayCredentialHeadersTest {

    private EventLoopGroup group;
    private ServerSocket upstream;
    private final CompletableFuture<String> upstreamHandshake = new CompletableFuture<>();

    @Before
    public void startUpstream() throws IOException {
        group = new NioEventLoopGroup(2);
        upstream = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        Thread acceptor = new Thread(() -> {
            try (Socket socket = upstream.accept()) {
                socket.setSoTimeout(10_000);
                upstreamHandshake.complete(readHead(socket.getInputStream()));
            } catch (IOException e) {
                upstreamHandshake.completeExceptionally(e);
            }
        }, "websocket-credential-upstream");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    @After
    public void stopUpstream() throws IOException {
        upstream.close();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    @Test
    public void shouldSendTheCredentialInTheUpstreamHandshake() throws Exception {
        String handshake = relayUpgradeWith("http://127.0.0.1:" + upstream.getLocalPort() + "=Authorization:WS_TOKEN");

        assertThat(handshake.toLowerCase(), containsString("authorization: bearer ws-secret-token"));
        assertThat(handshake, not(containsString("client-token")));
    }

    @Test
    public void shouldNotSendATlsOnlyCredentialOverPlainHttp() throws Exception {
        String handshake = relayUpgradeWith("127.0.0.1=Authorization:WS_TOKEN");

        assertThat(handshake, not(containsString("ws-secret-token")));
        assertThat(handshake, containsString("client-token"));
    }

    @Test
    public void shouldNotSendTheCredentialToAnotherHost() throws Exception {
        String handshake = relayUpgradeWith("http://api.example.com:" + upstream.getLocalPort() + "=Authorization:WS_TOKEN");

        assertThat(handshake, not(containsString("ws-secret-token")));
        assertThat(handshake, containsString("client-token"));
    }

    @Test
    public void shouldRefuseAWssUpstreamCertificateMockServersOwnCaSigned() throws Exception {
        try (TlsTestUpstream tlsUpstream = new TlsTestUpstream()) {
            relayOverTls(tlsUpstream, configuration()
                .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.JVM), true);

            assertThat(tlsUpstream.received(), is(empty()));
        }
    }

    @Test
    public void shouldSendTheCredentialToAWssUpstreamTrustedAtStartupWhateverTheTrustBecomes() throws Exception {
        try (TlsTestUpstream tlsUpstream = TlsTestUpstream.signedBySeparateCa()) {
            Configuration configuration = configuration()
                .forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM)
                .forwardProxyTLSCustomTrustX509Certificates(TlsTestUpstream.SEPARATE_CA);

            List<TlsTestUpstream.Received> received = relayOverTls(tlsUpstream, configuration, false);

            assertThat(received, hasSize(1));
            assertThat(received.get(0).headers().get("Authorization"), is("Bearer ws-secret-token"));
        }
    }

    // credentials built from configuration at startup, after which its forward trust is set to trust anything
    private List<TlsTestUpstream.Received> relayOverTls(TlsTestUpstream tlsUpstream, Configuration configuration, boolean refused) throws Exception {
        MockServerLogger logger = new MockServerLogger();
        configuration.socketConnectionTimeoutInMillis(5_000L).forwardProxyTLSHostnameVerificationEnabled(true).forwardCredentialHeadersByHost("localhost=Authorization:WS_TOKEN");
        WebSocketProxyRelayHandler relayHandler = new WebSocketProxyRelayHandler(configuration, logger, new NettySslContextFactory(configuration, logger, false));
        relayHandler.setForwardCredentialHeaders(ForwardCredentialHeadersTestSupport.forConfiguration(configuration, Map.of("WS_TOKEN", "Bearer ws-secret-token")::get, logger));
        configuration.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.ANY).forwardProxyTLSHostnameVerificationEnabled(false);
        HttpRequest upgrade = request("/socket")
            .withMethod("GET")
            .withHeader("Host", "localhost:" + tlsUpstream.port())
            .withHeader("Upgrade", "websocket")
            .withHeader("Connection", "Upgrade")
            .withHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .withHeader("Sec-WebSocket-Version", "13");
        Channel relayServer = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    relayHandler.relay(upgrade, ctx, "localhost", tlsUpstream.port(), true);
                }
            })
            .bind(InetAddress.getLoopbackAddress(), 0).syncUninterruptibly().channel();
        try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), ((InetSocketAddress) relayServer.localAddress()).getPort())) {
            if (refused) {
                tlsUpstream.awaitHandshakeFailure();
            } else {
                tlsUpstream.awaitFirstRequest();
            }
            return tlsUpstream.received();
        } finally {
            relayServer.close().syncUninterruptibly();
        }
    }

    private String relayUpgradeWith(String property) throws Exception {
        MockServerLogger logger = new MockServerLogger();
        WebSocketProxyRelayHandler relayHandler = new WebSocketProxyRelayHandler(configuration().socketConnectionTimeoutInMillis(5_000L), logger, null);
        relayHandler.setForwardCredentialHeaders(ForwardCredentialHeadersTestSupport.resolve(property, Map.of("WS_TOKEN", "Bearer ws-secret-token")::get, logger));
        int upstreamPort = upstream.getLocalPort();
        HttpRequest upgrade = request("/socket")
            .withMethod("GET")
            .withHeader("Host", "127.0.0.1:" + upstreamPort)
            .withHeader("Authorization", "Bearer client-token")
            .withHeader("Upgrade", "websocket")
            .withHeader("Connection", "Upgrade")
            .withHeader("Sec-WebSocket-Key", "dGhlIHNhbXBsZSBub25jZQ==")
            .withHeader("Sec-WebSocket-Version", "13");
        Channel relayServer = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    relayHandler.relay(upgrade, ctx, "127.0.0.1", upstreamPort, false);
                }
            })
            .bind(InetAddress.getLoopbackAddress(), 0).syncUninterruptibly().channel();
        try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), ((InetSocketAddress) relayServer.localAddress()).getPort())) {
            return upstreamHandshake.get(30, TimeUnit.SECONDS);
        } finally {
            relayServer.close().syncUninterruptibly();
        }
    }

    private static String readHead(InputStream in) throws IOException {
        StringBuilder head = new StringBuilder();
        int read;
        while (!head.toString().endsWith("\r\n\r\n") && (read = in.read()) != -1) {
            head.append((char) read);
        }
        return head.toString();
    }
}

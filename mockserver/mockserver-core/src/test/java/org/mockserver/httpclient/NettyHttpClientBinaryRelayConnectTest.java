package org.mockserver.httpclient;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoop;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.proxy.ProxyConnectException;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.SslHandler;
import io.netty.util.ReferenceCountUtil;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.proxyconfiguration.MalformedForwardTargetException;
import org.mockserver.proxyconfiguration.ProxyConfiguration;
import org.mockserver.socket.NettyAllocator;
import org.mockserver.socket.tls.NettySslContextFactory;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SNIServerName;
import java.io.DataInputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * The upstream connection of a binary connection that keeps one for its life: made directly, on the event loop it
 * is given, or through the tunnel of forwardSocksProxy or forwardHttpsProxy, and not made at all when it would go
 * around forwardHttpProxy or to an address that is blocked.
 */
public class NettyHttpClientBinaryRelayConnectTest {

    private static final InetAddress LOOPBACK = InetAddress.getLoopbackAddress();
    private static final InetAddress LOOPBACK_ADDRESS = loopbackByAddress();
    private static EventLoopGroup clientConnections;

    @BeforeClass
    public static void startGroup() {
        clientConnections = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopGroup() {
        clientConnections.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }

    private static NettyHttpClient client(Configuration configuration, List<ProxyConfiguration> proxyConfigurations, Supplier<EventLoopGroup> forwardClientGroup) {
        return new NettyHttpClient(configuration, new MockServerLogger(), forwardClientGroup, proxyConfigurations, true, null);
    }

    private static final ChannelHandler NOTHING = new SharableNothing();

    @ChannelHandler.Sharable
    private static final class SharableNothing extends ChannelInboundHandlerAdapter {
    }

    @Test
    public void shouldConnectDirectlyOnTheGivenEventLoopWithTheHandlerItIsGiven() throws Exception {
        AtomicInteger forwardClientGroupsAskedFor = new AtomicInteger();
        CompletableFuture<String> received = new CompletableFuture<>();
        EventLoop eventLoop = clientConnections.next();
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            ChannelFuture connect = client(configuration(), null, () -> {
                forwardClientGroupsAskedFor.incrementAndGet();
                return clientConnections;
            }).connectBinaryRelay(eventLoop, new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), false, new SimpleChannelInboundHandler<ByteBuf>() {
                @Override
                protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                    received.complete(bytes.toString(StandardCharsets.UTF_8));
                }
            });
            Channel channel = connect.channel();
            try (Socket accepted = upstream.accept()) {
                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.isSuccess(), is(true));
                assertThat("both legs of the relay run on one thread", channel.eventLoop(), is(sameInstance(eventLoop)));
                assertThat("the forward client's own threads are not started for it", forwardClientGroupsAskedFor.get(), is(0));
                assertThat(channel.config().getOption(ChannelOption.ALLOCATOR), is(sameInstance(NettyAllocator.ALLOCATOR)));
                assertThat(channel.config().getWriteBufferHighWaterMark(), is(32 * 1024));
                assertThat(channel.config().getWriteBufferLowWaterMark(), is(8 * 1024));

                channel.writeAndFlush(Unpooled.copiedBuffer("to the upstream", StandardCharsets.UTF_8)).sync();
                accepted.setSoTimeout(10_000);
                assertThat(new String(accepted.getInputStream().readNBytes("to the upstream".length()), StandardCharsets.UTF_8), is("to the upstream"));
                accepted.getOutputStream().write("from the upstream".getBytes(StandardCharsets.UTF_8));
                accepted.getOutputStream().flush();
                assertThat("in the clear, with nothing between the socket and the handler", received.get(10, TimeUnit.SECONDS), is("from the upstream"));
            } finally {
                channel.close().sync();
            }
        }
    }

    @Test
    public void shouldUseTheSocketConnectionTimeout() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            ChannelFuture connect = client(configuration().socketConnectionTimeoutInMillis(4_321L), null, null)
                .connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), false, NOTHING);
            try {
                assertThat(connect.channel().config().getConnectTimeoutMillis(), is(4_321));
            } finally {
                connect.channel().close().sync();
            }
        }
    }

    @Test
    public void shouldTunnelThroughForwardHttpProxyWithConnectWhenItIsTheOnlyUpstreamProxy() throws Exception {
        for (boolean secure : new boolean[]{false, true}) {
            try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
                proxy.setSoTimeout(10_000);
                NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.HTTP, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);
                CompletableFuture<String> received = new CompletableFuture<>();

                ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), InetSocketAddress.createUnresolved("db.example.com", 5432), secure, receiving(received));
                Channel channel = connect.channel();
                // called off the event loop: the connect must still complete only once the CONNECT codec is gone
                CompletableFuture<ChannelHandler> firstOnConnect = new CompletableFuture<>();
                connect.addListener(connected -> firstOnConnect.complete(channel.pipeline().first()));
                try (Socket accepted = proxy.accept()) {
                    accepted.setSoTimeout(10_000);
                    String head = readHead(accepted);
                    assertThat("secure=" + secure + ", the name is left to the proxy", head, startsWith("CONNECT db.example.com:5432 HTTP/1.1\r\n"));
                    assertThat("no credentials are set", head.toLowerCase(), not(containsString("proxy-authorization")));
                    assertThat("not connected until the proxy has opened the tunnel", connect.await(200, TimeUnit.MILLISECONDS), is(false));

                    accepted.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                    assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                    assertThat(connect.isSuccess(), is(true));
                    assertThat("the tunnel's handler is first when the connect completes", firstOnConnect.get(10, TimeUnit.SECONDS), is(instanceOf(HttpConnectProxyHandler.class)));
                    assertThat("the tunnel's handler is first", channel.eventLoop().submit(() -> channel.pipeline().first()).get(10, TimeUnit.SECONDS), is(instanceOf(HttpConnectProxyHandler.class)));

                    assertThroughTheTunnel(channel, accepted, received);
                } finally {
                    channel.close().sync();
                }
            }
        }
    }

    @Test
    public void shouldSendTheForwardProxyCredentialsInTheConnectThroughForwardHttpProxy() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
            proxy.setSoTimeout(10_000);
            NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.HTTP, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()), "proxyUser", "proxyPassword")), null);

            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK_ADDRESS, 5432), false, NOTHING);
            try (Socket accepted = proxy.accept()) {
                accepted.setSoTimeout(10_000);
                String head = readHead(accepted);
                assertThat(head, startsWith("CONNECT 127.0.0.1:5432 HTTP/1.1\r\n"));
                String credentials = Base64.getEncoder().encodeToString("proxyUser:proxyPassword".getBytes(StandardCharsets.UTF_8));
                assertThat(head.toLowerCase(), containsString("proxy-authorization: basic " + credentials.toLowerCase() + "\r\n"));

                accepted.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.isSuccess(), is(true));
            } finally {
                connect.channel().close().sync();
            }
        }
    }

    @Test
    public void shouldTunnelThroughForwardHttpsProxyRatherThanForwardHttpProxyWhenBothAreSet() throws Exception {
        try (ServerSocket httpProxy = new ServerSocket(0, 1, LOOPBACK); ServerSocket httpsProxy = new ServerSocket(0, 1, LOOPBACK)) {
            httpProxy.setSoTimeout(200);
            httpsProxy.setSoTimeout(10_000);
            NettyHttpClient client = client(configuration(), Arrays.asList(
                proxyConfiguration(ProxyConfiguration.Type.HTTP, new InetSocketAddress(LOOPBACK, httpProxy.getLocalPort())),
                proxyConfiguration(ProxyConfiguration.Type.HTTPS, new InetSocketAddress(LOOPBACK, httpsProxy.getLocalPort()))
            ), null);

            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK_ADDRESS, 5432), false, NOTHING);
            try (Socket accepted = httpsProxy.accept()) {
                accepted.setSoTimeout(10_000);
                assertThat(readHead(accepted), startsWith("CONNECT 127.0.0.1:5432 HTTP/1.1\r\n"));
                assertThrows("nothing is asked of forwardHttpProxy", java.net.SocketTimeoutException.class, httpProxy::accept);
            } finally {
                connect.channel().close().sync();
            }
        }
    }

    @Test
    public void shouldNameTheProxyItsSettingAndTheStatusWhenForwardHttpProxyRefusesTheTunnel() throws Exception {
        for (boolean credentials : new boolean[]{false, true}) {
            try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
                InetSocketAddress proxyAddress = new InetSocketAddress(LOOPBACK, proxy.getLocalPort());
                proxy.setSoTimeout(10_000);
                NettyHttpClient client = client(configuration(), Collections.singletonList(credentials
                    ? proxyConfiguration(ProxyConfiguration.Type.HTTP, proxyAddress, "proxyUser", "wrong")
                    : proxyConfiguration(ProxyConfiguration.Type.HTTP, proxyAddress)), null);

                ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK_ADDRESS, 5432), false, NOTHING);
                try (Socket accepted = proxy.accept()) {
                    accepted.setSoTimeout(10_000);
                    readHead(accepted);
                    accepted.getOutputStream().write("HTTP/1.1 407 Proxy Authentication Required\r\nProxy-Authenticate: Basic realm=\"proxy\"\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                    assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                    assertThat(connect.cause(), is(instanceOf(java.net.ConnectException.class)));
                    assertThat(connect.cause().getMessage(), is("upstream proxy " + proxyAddress.getHostString() + ":" + proxy.getLocalPort() + " set by forwardHttpProxy refused to open a CONNECT tunnel to 127.0.0.1:5432 with status 407 Proxy Authentication Required, "
                        + (credentials ? "check forwardProxyAuthenticationUsername and forwardProxyAuthenticationPassword" : "it asks for credentials: set forwardProxyAuthenticationUsername and forwardProxyAuthenticationPassword")));
                    assertThat("the proxy's own answer is kept", connect.cause().getCause(), is(instanceOf(ProxyConnectException.class)));
                    assertThat("the tunnel's connection is closed", connect.channel().closeFuture().await(10, TimeUnit.SECONDS), is(true));
                }
            }
        }
    }

    @Test
    public void shouldTunnelThroughForwardSocksProxyAndCompleteOnlyOnceTheTunnelIsOpen() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
            NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);
            CompletableFuture<String> received = new CompletableFuture<>();

            ChannelHandler relayHandler = receiving(received);
            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), InetSocketAddress.createUnresolved("db.example.com", 5432), false, relayHandler);
            Channel channel = connect.channel();
            try (Socket accepted = proxy.accept()) {
                accepted.setSoTimeout(10_000);
                DataInputStream fromRelay = new DataInputStream(accepted.getInputStream());
                assertThat("SOCKS5 greeting", fromRelay.readUnsignedByte(), is(5));
                fromRelay.readFully(new byte[fromRelay.readUnsignedByte()]);
                accepted.getOutputStream().write(new byte[]{5, 0});
                byte[] request = new byte[5];
                fromRelay.readFully(request);
                assertThat("CONNECT, by name", Arrays.copyOf(request, 4), is(new byte[]{5, 1, 0, 3}));
                byte[] name = new byte[request[4]];
                fromRelay.readFully(name);
                assertThat("the name is left to the proxy", new String(name, StandardCharsets.US_ASCII), is("db.example.com"));
                assertThat(fromRelay.readUnsignedShort(), is(5432));
                assertThat("not connected until the proxy has opened the tunnel", connect.await(200, TimeUnit.MILLISECONDS), is(false));
                assertThat(channel.pipeline().get(NettyHttpClient.BINARY_RELAY_TUNNEL), is(instanceOf(Socks5ProxyHandler.class)));
                assertThat("the tunnel's handler is nearer the socket than the relay's", channel.pipeline().names().indexOf(NettyHttpClient.BINARY_RELAY_TUNNEL), is(lessThan(channel.pipeline().names().indexOf(channel.pipeline().context(relayHandler).name()))));

                accepted.getOutputStream().write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.isSuccess(), is(true));

                assertThroughTheTunnel(channel, accepted, received);
            } finally {
                channel.close().sync();
            }
        }
    }

    @Test
    public void shouldAuthenticateToACredentialedForwardSocksProxyBeforeOpeningTheTunnel() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
            NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()), "relay-user", "relay-secret")), null);
            CompletableFuture<String> received = new CompletableFuture<>();

            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), InetSocketAddress.createUnresolved("db.example.com", 5432), false, receiving(received));
            Channel channel = connect.channel();
            try (Socket accepted = proxy.accept()) {
                accepted.setSoTimeout(10_000);
                DataInputStream fromRelay = new DataInputStream(accepted.getInputStream());
                assertThat("SOCKS5 greeting", fromRelay.readUnsignedByte(), is(5));
                byte[] methods = new byte[fromRelay.readUnsignedByte()];
                fromRelay.readFully(methods);
                assertThat("offers username and password (method 2)", Arrays.toString(methods), containsString("2"));
                accepted.getOutputStream().write(new byte[]{5, 2});
                // RFC 1929: version, username length and bytes, password length and bytes
                assertThat(fromRelay.readUnsignedByte(), is(1));
                byte[] username = new byte[fromRelay.readUnsignedByte()];
                fromRelay.readFully(username);
                byte[] password = new byte[fromRelay.readUnsignedByte()];
                fromRelay.readFully(password);
                assertThat(new String(username, StandardCharsets.US_ASCII), is("relay-user"));
                assertThat(new String(password, StandardCharsets.US_ASCII), is("relay-secret"));
                assertThat("not connected before the proxy accepts the credentials", connect.await(200, TimeUnit.MILLISECONDS), is(false));
                accepted.getOutputStream().write(new byte[]{1, 0});
                byte[] request = new byte[5];
                fromRelay.readFully(request);
                assertThat("CONNECT, by name", Arrays.copyOf(request, 4), is(new byte[]{5, 1, 0, 3}));
                byte[] name = new byte[request[4]];
                fromRelay.readFully(name);
                assertThat(new String(name, StandardCharsets.US_ASCII), is("db.example.com"));
                assertThat(fromRelay.readUnsignedShort(), is(5432));

                accepted.getOutputStream().write(new byte[]{5, 0, 0, 1, 0, 0, 0, 0, 0, 0});
                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.isSuccess(), is(true));

                assertThroughTheTunnel(channel, accepted, received);
            } finally {
                channel.close().sync();
            }
        }
    }

    @Test
    public void shouldTunnelAClearConnectionThroughForwardHttpsProxyWithConnect() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
            NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.HTTPS, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);
            CompletableFuture<String> received = new CompletableFuture<>();

            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK_ADDRESS, 5432), false, receiving(received));
            Channel channel = connect.channel();
            try (Socket accepted = proxy.accept()) {
                accepted.setSoTimeout(10_000);
                String head = readHead(accepted);
                assertThat(head, startsWith("CONNECT 127.0.0.1:5432 HTTP/1.1\r\n"));
                assertThat("not connected until the proxy has opened the tunnel", connect.await(200, TimeUnit.MILLISECONDS), is(false));

                accepted.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.isSuccess(), is(true));

                assertThroughTheTunnel(channel, accepted, received);
            } finally {
                channel.close().sync();
            }
        }
    }

    @Test
    public void shouldFailTheConnectWhenTheProxyRefusesTheTunnel() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
            proxy.setSoTimeout(10_000);
            NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.HTTPS, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);

            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, 5432), false, NOTHING);
            try (Socket accepted = proxy.accept()) {
                accepted.setSoTimeout(10_000);
                readHead(accepted);
                accepted.getOutputStream().write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));

                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.cause().getMessage(), is("upstream proxy " + LOOPBACK.getHostName() + ":" + proxy.getLocalPort() + " set by forwardHttpsProxy refused to open a CONNECT tunnel to " + LOOPBACK.getHostName() + ":5432 with status 403 Forbidden"));
                assertThat(connect.cause().getCause(), is(instanceOf(ProxyConnectException.class)));
                assertThat("the tunnel's connection is closed", connect.channel().closeFuture().await(10, TimeUnit.SECONDS), is(true));
            }
        }
    }

    @Test
    public void shouldFailTheConnectAsAnUnreachableProxyWhenTheProxyCannotBeReached() throws Exception {
        int notListening;
        try (ServerSocket closed = new ServerSocket(0, 1, LOOPBACK)) {
            notListening = closed.getLocalPort();
        }
        NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, new InetSocketAddress(LOOPBACK, notListening))), null);

        ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, 5432), true, NOTHING);

        assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
        assertThat(connect.cause(), is(instanceOf(UpstreamProxyUnreachableException.class)));
    }

    @Test
    public void shouldConnectDirectlyToADestinationOnNoProxyHosts() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK); ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
            proxy.setSoTimeout(200);
            NettyHttpClient client = client(configuration().noProxyHosts("127.0.0.1"), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);

            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK_ADDRESS, upstream.getLocalPort()), false, NOTHING);
            try (Socket accepted = upstream.accept()) {
                assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
                assertThat(connect.isSuccess(), is(true));
                assertThat(connect.channel().pipeline().get(NettyHttpClient.BINARY_RELAY_TUNNEL), is(nullValue()));
                assertThrows("nothing goes to the proxy", java.net.SocketTimeoutException.class, proxy::accept);
            } finally {
                connect.channel().close().sync();
            }
        }
    }

    @Test
    public void shouldCheckADestinationThatGoesThroughATunnel() throws Exception {
        try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
            proxy.setSoTimeout(200);
            NettyHttpClient client = client(configuration().forwardProxyBlockPrivateNetworks(true), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, 5432), false, NOTHING));

            assertThat(refused.getMessage(), containsString("loopback"));
            assertThrows("nothing goes to the proxy", java.net.SocketTimeoutException.class, proxy::accept);
        }
    }

    @Test
    public void shouldSayWhetherTheTunnelProxyIsGivenByNameWithoutLookingItUp() {
        InetSocketAddress target = new InetSocketAddress(LOOPBACK, 5432);

        assertThat(client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, "proxy.invalid:1080")), null).binaryRelayTunnelProxyNeedsLookUp(target, false), is(true));
        assertThat(client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, "127.0.0.1:1080")), null).binaryRelayTunnelProxyNeedsLookUp(target, false), is(false));
        assertThat(client(configuration(), null, null).binaryRelayTunnelProxyNeedsLookUp(target, false), is(false));
        assertThat("no tunnel proxy, nothing to look up", client(configuration(), null, null).lookUpBinaryRelayTunnelProxy(target, false), is(nullValue()));
    }

    @Test
    public void shouldTunnelThroughTheAddressLookedUpForAProxyGivenByName() throws Exception {
        InetAddress localhost = InetAddress.getByName("localhost");
        try (ServerSocket proxy = new ServerSocket(0, 1, localhost)) {
            proxy.setSoTimeout(10_000);
            NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.HTTPS, "localhost:" + proxy.getLocalPort())), null);
            InetSocketAddress target = InetSocketAddress.createUnresolved("db.example.com", 5432);

            InetSocketAddress found = client.lookUpBinaryRelayTunnelProxy(target, false);
            assertThat(found.isUnresolved(), is(false));
            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), target, false, receiving(new CompletableFuture<>()), found);
            try (Socket accepted = proxy.accept()) {
                accepted.setSoTimeout(10_000);
                assertThat(readHead(accepted), startsWith("CONNECT db.example.com:5432 HTTP/1.1\r\n"));
            } finally {
                connect.channel().close().sync();
            }
        }
    }

    @Test
    public void shouldNotLookUpATunnelProxyGivenByNameWhenConnecting() throws Exception {
        InetAddress localhost = InetAddress.getByName("localhost");
        try (ServerSocket proxy = new ServerSocket(0, 1, localhost)) {
            proxy.setSoTimeout(500);
            NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, "localhost:" + proxy.getLocalPort())), null);

            ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, 5432), false, NOTHING);

            assertThat(connect.await(10, TimeUnit.SECONDS), is(true));
            assertThat("a proxy not looked up first cannot be reached", connect.isSuccess(), is(false));
            assertThat(UpstreamProxyUnreachableException.in(connect.cause()), is(org.hamcrest.Matchers.notNullValue()));
            assertThrows("nothing goes to the proxy", java.net.SocketTimeoutException.class, proxy::accept);
        }
    }

    @Test
    public void shouldNotOpenATunnelThroughAnyUpstreamProxyToADestinationThatIsNotOneHost() throws Exception {
        for (ProxyConfiguration.Type type : ProxyConfiguration.Type.values()) {
            try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
                proxy.setSoTimeout(200);
                NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(type, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);
                for (String host : new String[]{"user@secret.internal.example", "secret.internal.example/.allowed.com", "secret.internal.example?.allowed.com", "0177.0.0.1", "[fe80::1%25en0]", "fe80::1%en0", "db.example.com:5432/"}) {
                    InetSocketAddress target = InetSocketAddress.createUnresolved(host, 5432);

                    assertThrows(type + " " + host, MalformedForwardTargetException.class, () -> client.lookUpBinaryRelayTarget(target, false));
                    assertThrows(type + " " + host, MalformedForwardTargetException.class, () -> client.connectBinaryRelay(clientConnections.next(), target, false, NOTHING));
                }
                assertThrows(type + ": nothing goes to the proxy", java.net.SocketTimeoutException.class, proxy::accept);
            }
        }
    }

    @Test
    public void shouldOpenATunnelThroughEachUpstreamProxyToAWellFormedDestination() throws Exception {
        for (ProxyConfiguration.Type type : ProxyConfiguration.Type.values()) {
            try (ServerSocket proxy = new ServerSocket(0, 1, LOOPBACK)) {
                proxy.setSoTimeout(10_000);
                NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(type, new InetSocketAddress(LOOPBACK, proxy.getLocalPort()))), null);
                InetSocketAddress target = client.lookUpBinaryRelayTarget(InetSocketAddress.createUnresolved("db.example.com", 5432), false);

                ChannelFuture connect = client.connectBinaryRelay(clientConnections.next(), target, false, NOTHING);
                try (Socket accepted = proxy.accept()) {
                    accepted.setSoTimeout(10_000);
                    if (type == ProxyConfiguration.Type.SOCKS5) {
                        DataInputStream in = new DataInputStream(accepted.getInputStream());
                        assertThat("SOCKS5 greeting", in.readUnsignedByte(), is(5));
                        in.readFully(new byte[in.readUnsignedByte()]);
                        accepted.getOutputStream().write(new byte[]{5, 0});
                        byte[] request = new byte[5];
                        in.readFully(request);
                        byte[] host = new byte[request[4]];
                        in.readFully(host);
                        assertThat("SOCKS5 DOMAIN", request[3], is((byte) 3));
                        assertThat(new String(host, StandardCharsets.US_ASCII), is("db.example.com"));
                    } else {
                        assertThat(type.name(), readHead(accepted), startsWith("CONNECT db.example.com:5432 HTTP/1.1\r\n"));
                    }
                } finally {
                    connect.channel().close().sync();
                }
            }
        }
    }

    @Test
    public void shouldResolveADestinationReachedDirectlyKeepingItsName() throws Exception {
        InetSocketAddress found = client(configuration(), null, null).lookUpBinaryRelayTarget(InetSocketAddress.createUnresolved("localhost", 5432), false);

        assertThat(found.isUnresolved(), is(false));
        assertThat(found.getAddress().isLoopbackAddress(), is(true));
        assertThat("the name is kept for SNI and the certificate check", found.getHostString(), is("localhost"));
        assertThat(found.getPort(), is(5432));
    }

    @Test
    public void shouldLeaveTheNameOfATunnelledDestinationToTheProxyWhenItIsNotChecked() throws Exception {
        InetSocketAddress target = InetSocketAddress.createUnresolved("db.invalid", 5432);
        NettyHttpClient client = client(configuration(), Collections.singletonList(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, new InetSocketAddress(LOOPBACK, 1080))), null);

        assertThat(client.lookUpBinaryRelayTarget(target, false), is(sameInstance(target)));
    }

    @Test
    public void shouldResolveAndCheckADestinationThatMayNotBeForwardedTo() {
        NettyHttpClient client = client(configuration().forwardProxyBlockPrivateNetworks(true), null, null);

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
            () -> client.lookUpBinaryRelayTarget(InetSocketAddress.createUnresolved("localhost", 5432), false));

        assertThat(refused.getMessage(), containsString("loopback"));
    }

    @Test
    public void shouldFailToResolveADestinationReachedDirectlyThatHasNoAddress() {
        assertThrows(java.net.UnknownHostException.class,
            () -> client(configuration(), null, null).lookUpBinaryRelayTarget(InetSocketAddress.createUnresolved("db.invalid", 5432), false));
    }

    @Test
    public void shouldLookNothingUpForADestinationAlreadyResolved() throws Exception {
        InetSocketAddress target = new InetSocketAddress(LOOPBACK, 5432);

        assertThat(client(configuration(), null, null).lookUpBinaryRelayTarget(target, false), is(sameInstance(target)));
    }

    /** The loopback address with no name, so a destination made from it is named by its address. */
    private static InetAddress loopbackByAddress() {
        try {
            return InetAddress.getByAddress(new byte[]{127, 0, 0, 1});
        } catch (java.net.UnknownHostException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static ChannelHandler receiving(CompletableFuture<String> received) {
        return new SimpleChannelInboundHandler<ByteBuf>() {
            @Override
            protected void channelRead0(ChannelHandlerContext ctx, ByteBuf bytes) {
                received.complete(bytes.toString(StandardCharsets.UTF_8));
            }
        };
    }

    private static void assertThroughTheTunnel(Channel channel, Socket accepted, CompletableFuture<String> received) throws Exception {
        channel.writeAndFlush(Unpooled.copiedBuffer("to the upstream", StandardCharsets.UTF_8)).sync();
        assertThat(new String(accepted.getInputStream().readNBytes("to the upstream".length()), StandardCharsets.UTF_8), is("to the upstream"));
        accepted.getOutputStream().write("from the upstream".getBytes(StandardCharsets.UTF_8));
        accepted.getOutputStream().flush();
        assertThat("the handler sees only what came through the tunnel", received.get(10, TimeUnit.SECONDS), is("from the upstream"));
    }

    private static String readHead(Socket socket) throws java.io.IOException {
        StringBuilder head = new StringBuilder();
        while (head.indexOf("\r\n\r\n") < 0) {
            int read = socket.getInputStream().read();
            if (read == -1) {
                throw new java.io.EOFException("closed before the request head ended");
            }
            head.append((char) read);
        }
        return head.toString();
    }

    @Test
    public void shouldNotConnectToAnAddressThatMayNotBeForwardedTo() throws Exception {
        try (ServerSocket upstream = new ServerSocket(0, 1, LOOPBACK)) {
            upstream.setSoTimeout(200);
            NettyHttpClient client = client(configuration().forwardProxyBlockPrivateNetworks(true), null, null);

            IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> client.connectBinaryRelay(clientConnections.next(), new InetSocketAddress(LOOPBACK, upstream.getLocalPort()), false, NOTHING));

            assertThat(refused.getMessage(), containsString("loopback"));
            assertThrows("nothing connects to the upstream", java.net.SocketTimeoutException.class, upstream::accept);
        }
    }

    @Test
    public void shouldBuildTheUpstreamTlsHandlerOfARelayAsAClientOfTheTargetWithTheConnectionTimeout() {
        Configuration configuration = configuration().socketConnectionTimeoutInMillis(4321L);
        NettyHttpClient httpClient = new NettyHttpClient(configuration, new MockServerLogger(), () -> clientConnections, null, true, new NettySslContextFactory(configuration, new MockServerLogger(), false));

        SslHandler sslHandler = httpClient.newBinaryRelaySslHandler(NettyAllocator.ALLOCATOR, InetSocketAddress.createUnresolved("db.example.com", 5432), null);
        try {
            assertThat(sslHandler.engine().getUseClientMode(), is(true));
            assertThat("the name, unresolved and not looked up", sslHandler.engine().getPeerHost(), is("db.example.com"));
            assertThat(sslHandler.engine().getPeerPort(), is(5432));
            assertThat(serverNames(sslHandler), contains("db.example.com"));
            assertThat(sslHandler.getHandshakeTimeoutMillis(), is(4321L));
            assertThat("no ALPN offered", sslHandler.engine().getSSLParameters().getApplicationProtocols().length, is(0));
        } finally {
            ReferenceCountUtil.release(sslHandler.engine());
        }
    }

    @Test
    public void shouldNameATargetGivenByNameAsItselfWhateverNameTheClientAskedMockServerFor() {
        SslHandler sslHandler = relaySslHandler(InetSocketAddress.createUnresolved("db.example.com", 5432), "localhost");
        try {
            assertThat("the client's name is MockServer's, not the upstream's", sslHandler.engine().getPeerHost(), is("db.example.com"));
            assertThat(serverNames(sslHandler), contains("db.example.com"));
        } finally {
            ReferenceCountUtil.release(sslHandler.engine());
        }
    }

    @Test
    public void shouldNameATargetGivenAsAnAddressAsTheClientDid() throws Exception {
        SslHandler sslHandler = relaySslHandler(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 5432), "db.example.com");
        try {
            assertThat(sslHandler.engine().getPeerHost(), is("db.example.com"));
            assertThat(serverNames(sslHandler), contains("db.example.com"));
        } finally {
            ReferenceCountUtil.release(sslHandler.engine());
        }
    }

    @Test
    public void shouldSendNoServerNameForATargetGivenAsAnAddressWhenTheClientSentNone() throws Exception {
        SslHandler sslHandler = relaySslHandler(new InetSocketAddress(InetAddress.getByAddress(new byte[]{127, 0, 0, 1}), 5432), null);
        try {
            assertThat(sslHandler.engine().getPeerHost(), is("127.0.0.1"));
            assertThat(serverNames(sslHandler), is(empty()));
        } finally {
            ReferenceCountUtil.release(sslHandler.engine());
        }
    }

    private static SslHandler relaySslHandler(InetSocketAddress target, String clientServerName) {
        Configuration configuration = configuration();
        NettyHttpClient httpClient = new NettyHttpClient(configuration, new MockServerLogger(), () -> clientConnections, null, true, new NettySslContextFactory(configuration, new MockServerLogger(), false));
        return httpClient.newBinaryRelaySslHandler(NettyAllocator.ALLOCATOR, target, clientServerName);
    }

    private static List<String> serverNames(SslHandler sslHandler) {
        List<SNIServerName> serverNames = sslHandler.engine().getSSLParameters().getServerNames();
        return serverNames == null ? Collections.emptyList() : serverNames.stream().map(name -> ((SNIHostName) name).getAsciiName()).collect(Collectors.toList());
    }
}

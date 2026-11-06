package org.mockserver.httpclient;

import com.google.common.collect.ImmutableMap;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Protocol;
import org.mockserver.proxyconfiguration.ProxyConfiguration;

import java.net.InetSocketAddress;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.proxyconfiguration.ProxyConfiguration.proxyConfiguration;

/**
 * A tunnel's handler connects to the upstream proxy itself, past the bootstrap's resolver, where an unresolved address
 * cannot be connected to: a proxy configured by name must reach it looked up, which a forward's connection does as its
 * pipeline is built.
 */
public class HttpClientInitializerTunnelProxyAddressTest {

    private static final int MAX_HEADER_SIZE = 8192;

    @Test
    public void shouldLookUpTunnelProxyNamedInConfigurationForEachConnection() {
        ProxyConfiguration proxy = proxyConfiguration(ProxyConfiguration.Type.SOCKS5, "localhost:1080");
        assertThat(proxy.getProxyAddress().isUnresolved(), is(true));
        HttpClientInitializer initializer = new HttpClientInitializer(ImmutableMap.of(ProxyConfiguration.Type.SOCKS5, proxy), new MockServerLogger(), true, null, Protocol.HTTP_1_1, configuration(), null);
        NioSocketChannel channel = new NioSocketChannel();
        try {
            initializer.initChannel(channel);

            InetSocketAddress proxyAddress = channel.pipeline().get(Socks5ProxyHandler.class).proxyAddress();
            assertThat("the handler is given an address it can connect to", proxyAddress.isUnresolved(), is(false));
            assertThat(proxyAddress.getAddress().isLoopbackAddress(), is(true));
            assertThat(proxyAddress.getHostString(), is("localhost"));
            assertThat(proxyAddress.getPort(), is(1080));
        } finally {
            channel.unsafe().closeForcibly();
        }
    }

    @Test
    public void shouldConnectToTheProxyAddressItIsGiven() {
        InetSocketAddress found = new InetSocketAddress("127.0.0.1", 3128);

        ProxyHandler connect = HttpClientInitializer.tunnelHandler(proxyConfiguration(ProxyConfiguration.Type.HTTPS, "localhost:3128"), found, new MockServerLogger(), MAX_HEADER_SIZE);
        ProxyHandler socks = HttpClientInitializer.tunnelHandler(proxyConfiguration(ProxyConfiguration.Type.SOCKS5, "localhost:3128", "user", "secret"), found, new MockServerLogger(), MAX_HEADER_SIZE);

        assertThat(connect, instanceOf(HttpConnectProxyHandler.class));
        assertThat(connect.proxyAddress(), is(sameInstance(found)));
        assertThat(((Socks5ProxyHandler) socks).username(), is("user"));
        assertThat(socks.proxyAddress(), is(sameInstance(found)));
    }
}

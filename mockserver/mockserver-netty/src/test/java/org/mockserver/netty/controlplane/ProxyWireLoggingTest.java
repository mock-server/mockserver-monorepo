package org.mockserver.netty.controlplane;

import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.LoggingHandler;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.logging.RawNetworkTrafficLog;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;

public class ProxyWireLoggingTest {
    @Test
    public void explicitWireLoggingMasksLoginCredentialsOnEveryListener() {
        for (ListenerRole role : ListenerRole.values()) {
            Configuration configuration = Configuration.configuration().mcpEnabled(false).alwaysCloseSocketConnections(false).logLevel(Level.TRACE).logRawNetworkTraffic(true)
                .proxyAuthenticationUsername("proxy").proxyAuthenticationPassword("proxy-secret");
            List<String> wireLogs = new ArrayList<>();
            HttpState state = new HttpState(configuration, new MockServerLogger(configuration, ProxyWireLoggingTest.class), mock(Scheduler.class));
            EmbeddedChannel channel = new EmbeddedChannel();
            try {
                channel.attr(ListenerRole.KEY).set(role);
                channel.pipeline().addLast(new MockServerUnificationInitializer(configuration, mock(LifeCycle.class), state,
                    mock(HttpActionHandler.class), null, new RawNetworkTrafficLog("server", () -> null, wireLogs::add)));
                String request = "GET /example HTTP/1.1\r\nHost: origin.test\r\nConnection: keep-alive\r\nProxy-Authorization: Basic cHJveHk6cHJveHktc2VjcmV0\r\n\r\n";
                int splitName = request.indexOf("Authorization");
                int splitValue = request.indexOf("cHJveHktc2VjcmV0");
                channel.writeInbound(Unpooled.copiedBuffer(request.substring(0, splitName), StandardCharsets.US_ASCII));
                channel.writeInbound(Unpooled.copiedBuffer(request.substring(splitName, splitValue), StandardCharsets.US_ASCII));
                channel.writeInbound(Unpooled.copiedBuffer(request.substring(splitValue), StandardCharsets.US_ASCII));
                channel.writeInbound(Unpooled.copiedBuffer(request, StandardCharsets.US_ASCII));
                String logged = String.join("\n", wireLogs);
                assertThat(logged, containsString("GET /example HTTP/1.1"));
                assertThat(logged, containsString("Authorization: " + RawNetworkTrafficLog.REDACTED));
                assertThat(logged, not(containsString("cHJveHk6")));
                assertThat(logged, not(containsString("cHJveHktc2VjcmV0")));
                assertThat(channel.pipeline().get(LoggingHandler.class), is(nullValue()));
            } finally {
                channel.finishAndReleaseAll();
                state.stop();
            }
        }
    }
    @Test
    public void explicitHttp2WireLoggingMasksLoginHeaders() throws Exception {
        Configuration configuration = Configuration.configuration().mcpEnabled(false).logLevel(Level.TRACE).logRawNetworkTraffic(true)
            .proxyAuthenticationUsername("proxy").proxyAuthenticationPassword("proxy-secret");
        List<String> wireLogs = java.util.Collections.synchronizedList(new ArrayList<>());
        HttpState state = new HttpState(configuration, new MockServerLogger(configuration, ProxyWireLoggingTest.class), mock(Scheduler.class));
        HttpActionHandler action = mock(HttpActionHandler.class);
        io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
        java.util.concurrent.atomic.AtomicReference<io.netty.channel.Channel> accepted = new java.util.concurrent.atomic.AtomicReference<>();
        io.netty.channel.Channel listener = null;
        try {
            listener = new io.netty.bootstrap.ServerBootstrap().group(group)
                .channel(io.netty.channel.socket.nio.NioServerSocketChannel.class)
                .childAttr(ListenerRole.KEY, ListenerRole.PROXY)
                .childHandler(new io.netty.channel.ChannelInitializer<io.netty.channel.socket.SocketChannel>() {
                    @Override
                    protected void initChannel(io.netty.channel.socket.SocketChannel channel) {
                        accepted.set(channel);
                        channel.pipeline().addLast(new MockServerUnificationInitializer(configuration, mock(LifeCycle.class), state,
                            action, null, new RawNetworkTrafficLog("server", () -> null, wireLogs::add)));
                    }
                }).bind("127.0.0.1", 0).sync().channel();
            int port = ((java.net.InetSocketAddress) listener.localAddress()).getPort();
            try (org.mockserver.netty.integration.Http2TestClient client = org.mockserver.netty.integration.Http2TestClient.h2c(group, port)) {
                client.send(new io.netty.handler.codec.http2.DefaultHttp2Headers().method("GET").scheme("http").authority("origin.test")
                    .path("/example").add("proxy-authorization", "Basic cHJveHk6cHJveHktc2VjcmV0"), true);
                org.mockito.Mockito.verify(action, org.mockito.Mockito.timeout(3000)).processAction(org.mockito.ArgumentMatchers.any(),
                    org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.anySet(),
                    org.mockito.ArgumentMatchers.anyBoolean(), org.mockito.ArgumentMatchers.eq(false), org.mockito.ArgumentMatchers.eq(false));
                String logged = String.join("\n", wireLogs);
                assertThat(logged, containsString("received HEADERS frame"));
                assertThat(logged, containsString("proxy-authorization: " + RawNetworkTrafficLog.REDACTED));
                assertThat(logged, not(containsString("cHJveHk6cHJveHktc2VjcmV0")));
                assertThat(accepted.get().pipeline().get(LoggingHandler.class), is(nullValue()));
            }
        } finally {
            if (listener != null) {
                listener.close().syncUninterruptibly();
            }
            group.shutdownGracefully(0, 1, java.util.concurrent.TimeUnit.SECONDS).syncUninterruptibly();
            state.stop();
        }
    }
}

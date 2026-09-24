package org.mockserver.netty.unification;

import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.MockServerUnificationInitializer;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Pins that MCP support installs ONE shared {@link McpStreamableHttpHandler} rather than a fresh
 * instance per connection, on BOTH the HTTP/1.1 pipeline and the HTTP/2 stream child channels. The
 * handler is {@code @Sharable}; a new instance per connection retains a full MCP tool/schema registry
 * for the life of every connection.
 */
public class PortUnificationMcpHandlerSharingTest {

    private static final int CONNECTION_COUNT = 20;

    /**
     * Drives {@code CONNECTION_COUNT} independent connections through a SINGLE
     * {@link MockServerUnificationInitializer} — mirroring production, where one {@code @Sharable}
     * initializer instance is the server bootstrap's {@code childHandler} for every connection — and
     * asserts the {@link McpStreamableHttpHandler} added to each connection's pipeline is the exact
     * same instance. Before the fix each connection built its own handler, so this collected
     * {@code CONNECTION_COUNT} distinct identities and failed.
     */
    @Test
    public void shouldShareOneMcpHandlerInstanceAcrossConnections() {
        Configuration configuration = configuration();
        assertThat("test requires MCP enabled (the default)", configuration.mcpEnabled(), is(true));

        HttpState httpState = new HttpState(configuration, new MockServerLogger(), mock(Scheduler.class));
        MockServerUnificationInitializer initializer = new MockServerUnificationInitializer(
            configuration,
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class),
            null
        );

        Set<McpStreamableHttpHandler> distinctHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < CONNECTION_COUNT; i++) {
            McpStreamableHttpHandler handler = mcpHandlerFromHttp1Connection(initializer);
            assertThat("connection " + i + " should have an MCP handler in its pipeline",
                handler, is(notNullValue()));
            distinctHandlers.add(handler);
        }

        assertThat("all connections must share ONE McpStreamableHttpHandler instance",
            distinctHandlers.size(), is(1));
    }

    /**
     * Guards the HTTP/2 half of the same defect: {@link org.mockserver.netty.unification.Http2MultiplexChildInitializer}
     * is built per HTTP/2 connection, so if it constructed its own {@link McpStreamableHttpHandler}
     * (as it did before the fix) every connection's stream child channels would carry a distinct
     * registry. Here the shared handler the server actually built is observed via an HTTP/1.1
     * connection, then two child initializers (standing in for two HTTP/2 connections) each build a
     * real {@link Http2StreamChannel} pipeline; both must install that same shared instance.
     * <p>
     * Reintroducing per-connection construction in {@code Http2MultiplexChildInitializer} alone makes
     * each child channel carry its own handler, so this fails on {@code sameInstance} and on the
     * identity-set size — the case that the HTTP/1.1 test cannot catch.
     */
    @Test
    public void shouldShareOneMcpHandlerAcrossHttp2StreamChildChannels() throws Exception {
        Configuration configuration = configuration();
        assertThat("test requires MCP enabled (the default)", configuration.mcpEnabled(), is(true));

        HttpState httpState = new HttpState(configuration, new MockServerLogger(), mock(Scheduler.class));
        MockServerUnificationInitializer initializer = new MockServerUnificationInitializer(
            configuration,
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class),
            null
        );

        McpStreamableHttpHandler shared = mcpHandlerFromHttp1Connection(initializer);
        assertThat("the server must build a shared MCP handler", shared, is(notNullValue()));

        Set<McpStreamableHttpHandler> distinctHandlers = Collections.newSetFromMap(new IdentityHashMap<>());
        for (int i = 0; i < 2; i++) {
            Http2MultiplexChildInitializer childInitializer = new Http2MultiplexChildInitializer(
                configuration,
                mock(LifeCycle.class),
                httpState,
                mock(HttpActionHandler.class),
                new MockServerLogger(),
                shared,
                false,
                null
            );
            McpStreamableHttpHandler onChild = mcpHandlerOnHttp2StreamChildChannel(childInitializer);
            assertThat("HTTP/2 stream child channel " + i + " should have an MCP handler in its pipeline",
                onChild, is(notNullValue()));
            assertThat("HTTP/2 stream child channel " + i + " must install the shared instance",
                onChild, is(sameInstance(shared)));
            distinctHandlers.add(onChild);
        }

        assertThat("all HTTP/2 stream child channels must share ONE McpStreamableHttpHandler instance",
            distinctHandlers.size(), is(1));
    }

    private McpStreamableHttpHandler mcpHandlerFromHttp1Connection(MockServerUnificationInitializer initializer) {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast(initializer);
        channel.writeInbound(Unpooled.wrappedBuffer(
            "GET /somePath HTTP/1.1\r\nHost: some.random.host\r\n\r\n".getBytes(StandardCharsets.UTF_8)));
        McpStreamableHttpHandler handler = channel.pipeline().get(McpStreamableHttpHandler.class);
        channel.finishAndReleaseAll();
        return handler;
    }

    /**
     * Builds a real {@link Http2StreamChannel} via the multiplex machinery and runs the initializer's
     * child-pipeline build on it exactly as the {@link Http2MultiplexHandler} would for an inbound
     * stream, then returns the MCP handler installed on that child pipeline. {@code initChannel} is
     * {@code protected}, reachable here because this test shares its package.
     */
    private McpStreamableHttpHandler mcpHandlerOnHttp2StreamChildChannel(Http2MultiplexChildInitializer childInitializer) throws Exception {
        EmbeddedChannel connection = new EmbeddedChannel(
            Http2FrameCodecBuilder.forServer().build(),
            new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                }
            })
        );
        connection.runPendingTasks();
        Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(connection)
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                }
            })
            .open().syncUninterruptibly().getNow();

        childInitializer.initChannel(streamChannel);

        McpStreamableHttpHandler handler = streamChannel.pipeline().get(McpStreamableHttpHandler.class);
        connection.finishAndReleaseAll();
        return handler;
    }
}

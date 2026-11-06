package org.mockserver.netty;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.DecoderException;
import io.netty.util.internal.OutOfDirectMemoryError;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.netty.connection.ServerClosedChannels;
import org.mockserver.netty.mcp.McpSessionManager;
import org.mockserver.netty.mcp.McpStreamableHttpHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.scheduler.Scheduler;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.action.http.HttpActionHandler.REMOTE_SOCKET;

/**
 * A close MockServer makes for an exception (the direct memory limit, an unexpected exception, an SSL or decoder fault)
 * is marked on the channel the response writer consults, whichever handler of an HTTP/1.1 pipeline ends the exception;
 * a client's reset is not.
 */
public class ExceptionCloseMarkingTest {

    private static HttpState httpState;
    private final List<EmbeddedChannel> channels = new ArrayList<>();
    private MockServerUnificationInitializer initializer;

    @BeforeClass
    public static void startServerState() {
        httpState = new HttpState(configuration(), new MockServerLogger(), mock(Scheduler.class));
    }

    @AfterClass
    public static void stopServerState() {
        httpState.stop();
        httpState = null;
    }

    @After
    public void releaseChannels() {
        channels.forEach(EmbeddedChannel::finishAndReleaseAll);
        if (initializer != null) {
            initializer.getMcpSessionManager().shutdown();
        }
    }

    private static Throwable[] closedForByMockServer() throws Exception {
        Constructor<OutOfDirectMemoryError> constructor = OutOfDirectMemoryError.class.getDeclaredConstructor(String.class);
        constructor.setAccessible(true);
        return new Throwable[]{
            constructor.newInstance("failed to allocate 4194304 byte(s) of direct memory"),
            new IllegalStateException("unexpected"),
            new DecoderException("bad frame")
        };
    }

    /**
     * A connection set up as MockServer sets one up, switched to HTTP/1.1 by the start of a request line: with no
     * request decoded, its close leaves the codec nothing to report.
     */
    private EmbeddedChannel http1Connection() {
        if (initializer == null) {
            HttpActionHandler actionHandler = mock(HttpActionHandler.class);
            when(actionHandler.getHttpClient()).thenReturn(mock(NettyHttpClient.class));
            initializer = new MockServerUnificationInitializer(configuration().assumeAllRequestsAreHttp(false), mock(LifeCycle.class), httpState, actionHandler, null);
        }
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.attr(REMOTE_SOCKET).set(new InetSocketAddress("127.0.0.1", 1234));
        channel.pipeline().addLast(initializer);
        channels.add(channel);
        channel.writeInbound(Unpooled.copiedBuffer("GET /", StandardCharsets.US_ASCII));
        assertThat("HTTP/1.1 pipeline", channel.pipeline().get(HttpRequestHandler.class), is(notNullValue()));
        return channel;
    }

    @Test
    public void shouldMarkAnHttp1ConnectionForAnExceptionFromAheadOfItsHandlersButNotForItsClientsReset() throws Exception {
        for (Throwable cause : closedForByMockServer()) {
            EmbeddedChannel channel = http1Connection();

            // from the head, as a read, TLS or codec fault arrives
            channel.pipeline().fireExceptionCaught(cause);

            assertThat(cause.toString(), channel.isOpen(), is(false));
            assertThat(cause.toString(), ServerClosedChannels.isClosedByServer(channel), is(true));
        }
        EmbeddedChannel channel = http1Connection();

        channel.pipeline().fireExceptionCaught(new IOException("Connection reset by peer"));

        assertThat(channel.isOpen(), is(false));
        assertThat(ServerClosedChannels.isClosedByServer(channel), is(false));
    }

    @Test
    public void shouldMarkTheChannelForAnExceptionEachHandlerAheadOfTheRequestHandlerClosesForButNotForAClientsReset() throws Exception {
        HttpState mockHttpState = mock(HttpState.class, RETURNS_DEEP_STUBS);
        when(mockHttpState.getMockServerLogger()).thenReturn(mock(MockServerLogger.class));
        LifeCycle server = mock(LifeCycle.class);
        when(server.getScheduler()).thenReturn(mock(Scheduler.class));
        Map<String, Supplier<ChannelHandler>> handlers = new LinkedHashMap<>();
        handlers.put("CallbackWebSocketServerHandler", () -> new CallbackWebSocketServerHandler(mockHttpState));
        handlers.put("DashboardWebSocketHandler", () -> new DashboardWebSocketHandler(mockHttpState, false, false));
        handlers.put("McpStreamableHttpHandler", () -> new McpStreamableHttpHandler(mockHttpState, server, mock(McpSessionManager.class)));

        for (Map.Entry<String, Supplier<ChannelHandler>> handler : handlers.entrySet()) {
            for (Throwable cause : closedForByMockServer()) {
                EmbeddedChannel channel = new EmbeddedChannel(handler.getValue().get());
                channels.add(channel);

                channel.pipeline().fireExceptionCaught(cause);

                assertThat(handler.getKey() + " for " + cause, ServerClosedChannels.isClosedByServer(channel), is(true));
            }
            EmbeddedChannel channel = new EmbeddedChannel(handler.getValue().get());
            channels.add(channel);

            channel.pipeline().fireExceptionCaught(new RuntimeException("Connection reset by peer"));

            assertThat(handler.getKey() + " for a client's reset", ServerClosedChannels.isClosedByServer(channel), is(false));
        }
    }
}

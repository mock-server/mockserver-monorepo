package org.mockserver.netty.unification;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.codec.CoalescingHttpObjectAggregator;
import org.mockserver.codec.MockServerHttpContentDecompressor;
import org.mockserver.codec.MockServerHttpServerCodec;
import org.mockserver.codec.PreserveHeadersNettyRemoves;
import org.mockserver.configuration.Configuration;
import org.mockserver.dashboard.DashboardWebSocketHandler;
import org.mockserver.lifecycle.LifeCycle;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.action.http.HttpActionHandler;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.HttpRequestHandler;
import org.mockserver.netty.websocketregistry.CallbackWebSocketServerHandler;
import org.mockserver.scheduler.Scheduler;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The per-stream child pipeline built by {@link Http2MultiplexChildInitializer} reuses its connection's
 * request and response mappers: every stream of ONE connection shares them, and no two connections do.
 * Each stream still gets its own {@link MockServerHttpServerCodec} (and every other stateful handler), and
 * the handler types and order are unchanged.
 */
public class Http2MultiplexPerConnectionCodecTest {

    private final List<HttpState> httpStates = new ArrayList<>();

    private static final InetSocketAddress SERVER_ADDRESS = new InetSocketAddress("127.0.0.1", 1080);

    private final List<EmbeddedChannel> connections = new ArrayList<>();
    private Http2MultiplexChildInitializer initializerA;
    private Http2MultiplexChildInitializer initializerB;

    @Before
    public void setUp() {
        Configuration configuration = configuration();
        HttpState httpState = new HttpState(configuration, new MockServerLogger(), mock(Scheduler.class));
        httpStates.add(httpState);
        // one initializer per connection, as PortUnificationHandler.switchToHttp2Multiplex builds them
        initializerA = initializer(configuration, httpState);
        initializerB = initializer(configuration, httpState);
    }

    @After
    public void tearDown() {
        for (EmbeddedChannel connection : connections) {
            connection.finishAndReleaseAll();
        }
        httpStates.forEach(HttpState::stop);
    }

    @Test
    public void shouldKeepChildPipelineHandlerTypesAndOrder() throws Exception {
        Http2StreamChannel stream = stream(connection(50_001), initializerA);

        List<Class<?>> handlerTypes = new ArrayList<>();
        for (ChannelHandler handler : stream.pipeline().toMap().values()) {
            handlerTypes.add(handler.getClass());
        }
        assertThat(handlerTypes, is(Arrays.<Class<?>>asList(
            ProxyAuthenticationHandler.class,
            LenientInboundHttp2StreamFrameCodec.class,
            StreamAddressedContentHandler.class,
            PreserveHeadersNettyRemoves.class,
            MockServerHttpContentDecompressor.class,
            CoalescingHttpObjectAggregator.class,
            CallbackWebSocketServerHandler.class,
            DashboardWebSocketHandler.class,
            MockServerHttpServerCodec.class,
            TraceContextHandler.class,
            HttpRequestHandler.class
        )));
    }

    @Test
    public void shouldGiveEveryStreamItsOwnCodec() throws Exception {
        EmbeddedChannel connection = connection(50_001);
        Http2StreamChannel first = stream(connection, initializerA);
        Http2StreamChannel second = stream(connection, initializerA);

        assertThat(first.pipeline().get(MockServerHttpServerCodec.class),
            not(sameInstance(second.pipeline().get(MockServerHttpServerCodec.class))));
    }

    /**
     * The request mapper memoises the connection's address strings; that cache is only reused across
     * requests when the mapper is. A request on another connection in between must neither corrupt the
     * addresses nor evict the first connection's cached strings, which it would if the mapper were shared
     * across connections rather than per connection.
     */
    @Test
    public void shouldShareRequestMapperAcrossStreamsOfOneConnectionOnly() throws Exception {
        EmbeddedChannel connectionA = connection(50_001);
        EmbeddedChannel connectionB = connection(50_002);

        HttpRequest firstOnA = decode(stream(connectionA, initializerA));
        HttpRequest firstOnB = decode(stream(connectionB, initializerB));
        HttpRequest secondOnA = decode(stream(connectionA, initializerA));
        HttpRequest secondOnB = decode(stream(connectionB, initializerB));

        assertThat(firstOnA.getRemoteAddress(), is("127.0.0.1:50001"));
        assertThat(secondOnA.getRemoteAddress(), is("127.0.0.1:50001"));
        assertThat(firstOnB.getRemoteAddress(), is("127.0.0.1:50002"));
        assertThat(secondOnB.getRemoteAddress(), is("127.0.0.1:50002"));
        assertThat(firstOnA.getLocalAddress(), is("127.0.0.1:1080"));
        assertThat(secondOnB.getLocalAddress(), is("127.0.0.1:1080"));

        assertThat("streams of one connection reuse its mapper (and so its cached address string)",
            secondOnA.getRemoteAddress(), sameInstance(firstOnA.getRemoteAddress()));
        assertThat(secondOnB.getRemoteAddress(), sameInstance(firstOnB.getRemoteAddress()));
        assertThat("connections do not share a mapper",
            firstOnB.getLocalAddress(), not(sameInstance(firstOnA.getLocalAddress())));
    }

    private Http2MultiplexChildInitializer initializer(Configuration configuration, HttpState httpState) {
        return new Http2MultiplexChildInitializer(
            configuration,
            mock(LifeCycle.class),
            httpState,
            mock(HttpActionHandler.class),
            new MockServerLogger(),
            null,
            false,
            null
        );
    }

    private EmbeddedChannel connection(int clientPort) {
        EmbeddedChannel connection = new EmbeddedChannel(
            Http2FrameCodecBuilder.forServer().build(),
            new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                }
            })
        ) {
            @Override
            protected SocketAddress localAddress0() {
                return SERVER_ADDRESS;
            }

            @Override
            protected SocketAddress remoteAddress0() {
                return new InetSocketAddress("127.0.0.1", clientPort);
            }
        };
        connection.runPendingTasks();
        connections.add(connection);
        return connection;
    }

    private static Http2StreamChannel stream(EmbeddedChannel connection, Http2MultiplexChildInitializer initializer) throws Exception {
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(connection)
            .handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                }
            })
            .open().syncUninterruptibly().getNow();
        initializer.initChannel(stream);
        return stream;
    }

    /**
     * Feeds a {@code FullHttpRequest} into the stream's {@link MockServerHttpServerCodec} and returns the
     * {@link HttpRequest} it decodes, captured immediately after the codec.
     */
    private static HttpRequest decode(Http2StreamChannel stream) {
        ChannelPipeline pipeline = stream.pipeline();
        List<String> names = pipeline.names();
        String codecName = pipeline.context(MockServerHttpServerCodec.class).name();
        HttpRequest[] captured = new HttpRequest[1];
        pipeline.addAfter(codecName, "capture", new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                captured[0] = (HttpRequest) msg;
            }
        });
        DefaultFullHttpRequest request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/somePath");
        request.headers().set("host", "localhost:1080");
        pipeline.context(names.get(names.indexOf(codecName) - 1)).fireChannelRead(request);
        return captured[0];
    }
}

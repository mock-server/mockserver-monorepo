package org.mockserver.netty.http3;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultHttpContent;
import io.netty.handler.codec.http.DefaultHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpUtil;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3ErrorCode;
import io.netty.handler.codec.quic.Quic;
import io.netty.handler.codec.quic.QuicConnectionCloseEvent;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.LogEventRequestAndResponse;
import org.mockserver.netty.MockServer;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * With {@code recordClientClosedStreamsComplete} on, an HTTP/3 stream cut short because Netty's HTTP/3 codec closed
 * the connection for a connection error is recorded truncated, as MockServer's own close, while a stream its client
 * ends by closing the connection is recorded as closed by the client. QUIC-gated.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ConnectionErrorRecordingIntegrationTest {

    private static final byte CONTROL_STREAM_TYPE = 0x00;

    private static EventLoopGroup upstreamGroup;
    private static Channel upstreamChannel;
    private static NioEventLoopGroup clientGroup;
    private static MockServer mockServer;
    private static MockServerClient mockServerClient;

    @BeforeClass
    public static void startServers() throws Exception {
        assumeQuicAvailable();
        upstreamGroup = new NioEventLoopGroup(1);
        upstreamChannel = new ServerBootstrap()
            .group(upstreamGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(new HttpServerCodec());
                    ch.pipeline().addLast(new HttpObjectAggregator(65536));
                    ch.pipeline().addLast(new EndlessSseUpstream());
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        clientGroup = new NioEventLoopGroup(1);
        mockServer = startWithHttp3(configuration()
            .http3MaxIdleTimeout(30000L)
            .streamingResponsesEnabled(true)
            .maxStreamingCaptureBytes(262144)
            .recordClientClosedStreamsComplete(true));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (upstreamChannel != null) {
            upstreamChannel.close();
        }
        if (upstreamGroup != null) {
            upstreamGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient
            .when(request().withPath("/sse-endless"))
            .forward(forward().withHost("127.0.0.1").withPort(((InetSocketAddress) upstreamChannel.localAddress()).getPort()));
    }

    @Test(timeout = 30000)
    public void shouldRecordAStreamCutByAConnectionErrorNettysCodecClosesTheConnectionForAsTruncated() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            readTwoEvents(client);

            // a second control stream, which Netty's codec closes the connection for without firing an exception
            client.sendOnANewUnidirectionalStream(CONTROL_STREAM_TYPE);

            QuicConnectionCloseEvent closed = client.closedByServer();
            assertThat(closed.error(), is(Http3ErrorCode.H3_STREAM_CREATION_ERROR.code()));
        }
        HttpResponse logged = recorded();

        assertThat("recorded as MockServer's close", logged.getFirstHeader("x-mockserver-stream-truncated"), is("true"));
        assertThat(logged.containsHeader("x-mockserver-stream-closed-by-client"), is(false));
    }

    @Test(timeout = 30000)
    public void shouldRecordAStreamItsClientEndsByClosingTheConnectionAsClosedByTheClient() throws Exception {
        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            readTwoEvents(client);
        }
        HttpResponse logged = recorded();

        assertThat(logged.containsHeader("x-mockserver-stream-truncated"), is(false));
        assertThat(logged.getFirstHeader("x-mockserver-stream-closed-by-client"), is("true"));
    }

    private static void readTwoEvents(Http3TestClient client) throws Exception {
        Http3TestClient.Exchange exchange = client.send(new DefaultHttp3Headers()
            .method("GET").scheme("https").authority("localhost:" + mockServer.getHttp3Port()).path("/sse-endless")
            .add("accept", "text/event-stream"));
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!new String(exchange.receivedByteArray(), StandardCharsets.UTF_8).contains("data: event1")) {
            assertThat("two events within 10 seconds", System.nanoTime() < deadline, is(true));
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    private static HttpResponse recorded() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        LogEventRequestAndResponse[] recorded = mockServerClient.retrieveRecordedRequestsAndResponses(request().withPath("/sse-endless"));
        while (recorded.length == 0 && System.nanoTime() < deadline) {
            TimeUnit.MILLISECONDS.sleep(50);
            recorded = mockServerClient.retrieveRecordedRequestsAndResponses(request().withPath("/sse-endless"));
        }
        assertThat("the stream was recorded", recorded.length, is(1));
        return recorded[0].getHttpResponse();
    }

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue("native QUIC transport not available on this platform", Quic.isAvailable());
        } catch (Throwable t) {
            Assume.assumeNoException("native QUIC transport failed to load", t);
        }
    }

    /**
     * A Server-Sent Events stream of an event every 100 ms that outlasts each test, so only a close ends it.
     */
    @ChannelHandler.Sharable
    private static final class EndlessSseUpstream extends SimpleChannelInboundHandler<FullHttpRequest> {
        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
            DefaultHttpResponse head = new DefaultHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
            head.headers().set(HttpHeaderNames.CONTENT_TYPE, "text/event-stream");
            head.headers().set(HttpHeaderNames.CACHE_CONTROL, "no-cache");
            HttpUtil.setTransferEncodingChunked(head, true);
            ctx.writeAndFlush(head);
            sendEvent(ctx, 0);
        }

        private static void sendEvent(ChannelHandlerContext ctx, int event) {
            if (ctx.channel().isActive() && event < 300) {
                ctx.writeAndFlush(new DefaultHttpContent(Unpooled.copiedBuffer("data: event" + event + "\n\n", StandardCharsets.UTF_8)));
                ctx.executor().schedule(() -> sendEvent(ctx, event + 1), 100, TimeUnit.MILLISECONDS);
            }
        }
    }
}

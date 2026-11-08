package org.mockserver.netty.proxy.relay;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.netty.connection.TunnelProxyClient;
import org.mockserver.netty.unification.PortUnificationHandler;
import org.mockserver.scheduler.Scheduler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A CONNECT/SOCKS tunnel whose client speaks neither TLS nor HTTP: a real {@link RelayConnectHandler} over loopback
 * sockets, with a Netty server standing in for MockServer's end of the loopback, which acknowledges the preamble and
 * then sees the client's bytes as they were sent.
 */
public class RelayConnectBinaryTunnelTest {

    private static final String TUNNEL_ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n";

    private EventLoopGroup eventLoopGroup;
    private Channel proxyServerChannel;
    private Channel loopbackServerChannel;
    private final List<Socket> sockets = new CopyOnWriteArrayList<>();
    private final MockServerEnd mockServerEnd = new MockServerEnd();

    @Before
    public void createEventLoop() {
        eventLoopGroup = new NioEventLoopGroup(2, new Scheduler.SchedulerThreadFactory(RelayConnectBinaryTunnelTest.class.getSimpleName() + "-eventLoop"));
    }

    @After
    public void stopEventLoop() throws IOException {
        for (Socket socket : sockets) {
            socket.close();
        }
        for (Channel channel : new Channel[]{proxyServerChannel, loopbackServerChannel}) {
            if (channel != null) {
                channel.close().syncUninterruptibly();
            }
        }
        eventLoopGroup.shutdownGracefully(0, 0, MILLISECONDS).syncUninterruptibly();
    }

    @Test(timeout = 30000)
    public void shouldRelayBinaryBothWaysAsItWasSent() throws Exception {
        start(configuration());
        Socket client = tunnel();

        write(client, "hello\n");

        awaitTrue("the client's bytes reached MockServer as sent", () -> mockServerEnd.received().equals("hello\n"));
        assertThat("the loopback is marked binary", TunnelProxyClient.carriesBinary(mockServerEnd.accepted), is(true));
        // the destination's reply may start like the loopback's acknowledgement, which is no longer looked for
        mockServerEnd.write("PROXIED_RESPONSE_reply\n");
        assertThat(readLine(client.getInputStream()), is("PROXIED_RESPONSE_reply\n"));
    }

    @Test(timeout = 30000)
    public void shouldTakeBytesStillUndecidedAsBinaryOnceTheClientFallsSilent() throws Exception {
        start(configuration());
        Socket client = tunnel();

        // the start of a method, which an HTTP request line could still follow
        write(client, "ab");

        awaitTrue("relayed once the client has been silent", () -> mockServerEnd.received().equals("ab"));
        assertThat(TunnelProxyClient.carriesBinary(mockServerEnd.accepted), is(true));
    }

    @Test(timeout = 30000)
    public void shouldRelayAsBinaryForAServerThatSpeaksFirst() throws Exception {
        start(configuration().forwardBinaryServerFirstWaitMillis(200L));
        Socket client = tunnel();

        awaitTrue("MockServer's end is told the client is silent", () -> mockServerEnd.events.contains(TunnelProxyClient.ServerSpeaksFirst.INSTANCE));
        mockServerEnd.write("220 ready\n");
        assertThat(readLine(client.getInputStream()), is("220 ready\n"));
        write(client, "HELO\n");

        awaitTrue("the client's reply reached MockServer", () -> mockServerEnd.received().equals("HELO\n"));
        assertThat(mockServerEnd.events, contains(TunnelProxyClient.ServerSpeaksFirst.INSTANCE));
    }

    @Test(timeout = 30000)
    public void shouldNotWaitForAServerThatSpeaksFirstOnceTheClientHasSpoken() throws Exception {
        start(configuration().forwardBinaryServerFirstWaitMillis(5_000L));
        Socket client = tunnel();

        write(client, "hello\n");

        awaitTrue("relayed as binary at once", () -> mockServerEnd.received().equals("hello\n"));
        assertThat("no server-first event", mockServerEnd.events.isEmpty(), is(true));
    }

    @Test
    public void shouldTellTheLoopbackAcknowledgementOnlyByItsWholePrefix() {
        assertThat(startsWithProxiedResponse(RelayConnectHandler.PROXIED_RESPONSE), is(true));
        assertThat(startsWithProxiedResponse(RelayConnectHandler.PROXIED_RESPONSE + "more"), is(true));
        assertThat("too short", startsWithProxiedResponse("PROXIED_RESP"), is(false));
        assertThat("another prefix", startsWithProxiedResponse("PROXIED_RESPONSX_"), is(false));
        ByteBuf offset = Unpooled.copiedBuffer("xx" + RelayConnectHandler.PROXIED_RESPONSE, StandardCharsets.US_ASCII);
        try {
            offset.skipBytes(2);
            assertThat("from the reader index", RelayConnectHandler.startsWithProxiedResponse(offset), is(true));
        } finally {
            offset.release();
        }
    }

    private static boolean startsWithProxiedResponse(String text) {
        ByteBuf buf = Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII);
        try {
            return RelayConnectHandler.startsWithProxiedResponse(buf);
        } finally {
            buf.release();
        }
    }

    @Test(timeout = 30000)
    public void shouldStopReadingOneLegWhileTheOtherCannotTakeMore() throws Exception {
        start(configuration());
        Socket client = tunnel();
        write(client, "go\n");
        awaitTrue(() -> mockServerEnd.received().equals("go\n"));
        TunnelProxyClient.of(mockServerEnd.accepted).config().setWriteBufferWaterMark(new WriteBufferWaterMark(1, 2));

        int total = 4 * 1024 * 1024;
        for (int i = 0; i < total / 8192; i++) {
            mockServerEnd.write("x".repeat(8192));
        }
        Thread.sleep(500);

        byte[] buffer = new byte[65536];
        int read = 0;
        while (read < total) {
            int count = client.getInputStream().read(buffer);
            if (count == -1) {
                break;
            }
            read += count;
        }
        assertThat("every byte reached the client", read, is(total));
    }

    @Test(timeout = 30000)
    public void shouldCloseTheTunnelOnAFaultOnEitherLeg() throws Exception {
        start(configuration());
        Socket client = tunnel();
        write(client, "hello\n");
        awaitTrue(() -> mockServerEnd.received().equals("hello\n"));

        TunnelProxyClient.of(mockServerEnd.accepted).pipeline().fireExceptionCaught(new IOException("a fault"));

        assertThat("the client's connection is closed", client.getInputStream().read(), is(-1));
    }

    private Socket tunnel() throws IOException {
        Socket client = new Socket(InetAddress.getLoopbackAddress(), ((InetSocketAddress) proxyServerChannel.localAddress()).getPort());
        sockets.add(client);
        client.setSoTimeout(10_000);
        byte[] reply = client.getInputStream().readNBytes(TUNNEL_ESTABLISHED.length());
        assertThat(new String(reply, StandardCharsets.US_ASCII), is(TUNNEL_ESTABLISHED));
        return client;
    }

    private void start(Configuration configuration) {
        loopbackServerChannel = new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(mockServerEnd)
            .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .syncUninterruptibly()
            .channel();
        int loopbackPort = ((InetSocketAddress) loopbackServerChannel.localAddress()).getPort();
        mockServerEnd.preamble = RelayConnectHandler.PROXIED + "localhost:" + loopbackPort;
        proxyServerChannel = new ServerBootstrap()
            .group(eventLoopGroup)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    PortUnificationHandler.deferTlsDetection(ch);
                    ch.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelActive(ChannelHandlerContext ctx) {
                            ctx.fireChannelActive();
                            // as the proxy client's CONNECT or SOCKS request
                            ctx.fireChannelRead("CONNECT");
                        }
                    });
                    ch.pipeline().addLast(new TestRelayConnectHandler(configuration, "localhost", loopbackPort));
                }
            })
            .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
            .syncUninterruptibly()
            .channel();
    }

    private static void write(Socket socket, String text) throws IOException {
        socket.getOutputStream().write(text.getBytes(StandardCharsets.US_ASCII));
        socket.getOutputStream().flush();
    }

    private static String readLine(InputStream input) throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream();
        for (int read; (read = input.read()) != -1; ) {
            line.write(read);
            if (read == '\n') {
                break;
            }
        }
        return line.toString(StandardCharsets.US_ASCII.name());
    }

    private static void awaitTrue(Supplier<Boolean> condition) throws InterruptedException {
        awaitTrue("condition", condition);
    }

    private static void awaitTrue(String what, Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(what, condition.get(), is(true));
    }

    /**
     * MockServer's end of the loopback: takes the preamble, acknowledges it, then keeps what follows and the events it
     * is sent.
     */
    @io.netty.channel.ChannelHandler.Sharable
    private static final class MockServerEnd extends ChannelInboundHandlerAdapter {
        private final StringBuffer bytes = new StringBuffer();
        private final List<Object> events = new CopyOnWriteArrayList<>();
        private volatile String preamble;
        private volatile Channel accepted;
        private volatile boolean acknowledged;

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            ByteBuf buf = (ByteBuf) msg;
            try {
                bytes.append(buf.toString(StandardCharsets.US_ASCII));
            } finally {
                buf.release();
            }
            if (!acknowledged && bytes.length() >= preamble.length()) {
                acknowledged = true;
                bytes.delete(0, preamble.length());
                RelayLoopbackAddresses.rememberProxyClient(ctx.channel());
                accepted = ctx.channel();
                ctx.writeAndFlush(Unpooled.copiedBuffer(RelayConnectHandler.PROXIED_RESPONSE, StandardCharsets.US_ASCII));
            }
        }

        @Override
        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
            events.add(evt);
        }

        String received() {
            return acknowledged ? bytes.toString() : "";
        }

        void write(String text) {
            accepted.writeAndFlush(Unpooled.copiedBuffer(text, StandardCharsets.US_ASCII));
        }
    }

    private static class TestRelayConnectHandler extends RelayConnectHandler<String> {

        TestRelayConnectHandler(Configuration configuration, String host, int port) {
            super(configuration, RelayLoopbackServer.listeningOn(port), new MockServerLogger(), host, port);
        }

        @Override
        protected void removeCodecSupport(ChannelHandlerContext ctx) {
            ctx.pipeline().remove(this);
        }

        @Override
        protected Object successResponse(Object request) {
            return Unpooled.copiedBuffer(TUNNEL_ESTABLISHED, StandardCharsets.US_ASCII);
        }

        @Override
        protected Object failureResponse(Object request) {
            return Unpooled.copiedBuffer("HTTP/1.1 502 Bad Gateway\r\n\r\n", StandardCharsets.US_ASCII);
        }
    }
}

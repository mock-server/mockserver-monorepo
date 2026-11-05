package org.mockserver.netty.connection;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import io.netty.handler.ssl.SslProvider;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.ssl.util.SelfSignedCertificate;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.NettySslContextFactory;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A TLS connection whose client stops reading is cut at once. Closed through the pipeline, its TLS handler would queue
 * a close_notify behind the megabytes the client is not taking and hold the socket open until that flushed or its
 * close_notify flush timeout passed; the timeout is raised here so such a close could not pass.
 */
public class WriteStallTimeoutHandlerTlsCloseTest {

    private static final long STALL_MILLIS = 200;
    private static final long CLOSE_NOTIFY_FLUSH_TIMEOUT_MILLIS = 60_000;
    private static final long CLOSED_WITHIN_MILLIS = 10_000;
    private static final int RESPONSE_BYTES = 8 * 1024 * 1024;

    private static EventLoopGroup group;

    @BeforeClass
    public static void startGroup() {
        group = new NioEventLoopGroup(1);
    }

    @AfterClass
    public static void stopGroup() {
        group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
    }

    @Test
    public void shouldEndAStalledReadersSocketWithoutWaitingForCloseNotifyUsingMockServersTls() throws Exception {
        shouldEndAStalledReadersSocketWithoutWaitingForCloseNotify(new NettySslContextFactory(configuration(), new MockServerLogger(), true).createServerSslContext());
    }

    @Test
    public void shouldEndAStalledReadersSocketWithoutWaitingForCloseNotifyUsingJdkTls() throws Exception {
        SelfSignedCertificate certificate = new SelfSignedCertificate();
        try {
            shouldEndAStalledReadersSocketWithoutWaitingForCloseNotify(SslContextBuilder.forServer(certificate.certificate(), certificate.privateKey()).sslProvider(SslProvider.JDK).build());
        } finally {
            certificate.delete();
        }
    }

    private void shouldEndAStalledReadersSocketWithoutWaitingForCloseNotify(SslContext serverTls) throws Exception {
        CompletableFuture<Channel> accepted = new CompletableFuture<>();
        CompletableFuture<Void> responseWritten = new CompletableFuture<>();
        Channel server = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childOption(ChannelOption.SO_SNDBUF, 16 * 1024)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel channel) {
                    SslHandler tls = serverTls.newHandler(channel.alloc());
                    tls.setCloseNotifyFlushTimeoutMillis(CLOSE_NOTIFY_FLUSH_TIMEOUT_MILLIS);
                    channel.pipeline().addLast(tls, new WriteStallTimeoutHandler(STALL_MILLIS, null), new ChannelInboundHandlerAdapter() {
                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                            if (evt instanceof SslHandshakeCompletionEvent && ((SslHandshakeCompletionEvent) evt).isSuccess()) {
                                ctx.writeAndFlush(Unpooled.wrappedBuffer(new byte[RESPONSE_BYTES]));
                                responseWritten.complete(null);
                            }
                            ctx.fireUserEventTriggered(evt);
                        }
                    });
                    accepted.complete(channel);
                }
            })
            .bind(new InetSocketAddress("127.0.0.1", 0)).sync().channel();
        try (Socket socket = new Socket()) {
            socket.setReceiveBufferSize(16 * 1024);
            socket.connect(server.localAddress());
            SSLContext clientTls = SSLContext.getInstance("TLS");
            clientTls.init(null, InsecureTrustManagerFactory.INSTANCE.getTrustManagers(), null);
            SSLSocket tlsSocket = (SSLSocket) clientTls.getSocketFactory().createSocket(socket, "127.0.0.1", ((InetSocketAddress) server.localAddress()).getPort(), false);
            tlsSocket.startHandshake();
            // never reads the response
            Channel connection = accepted.get(CLOSED_WITHIN_MILLIS, TimeUnit.MILLISECONDS);
            // the response is written only once the handshake succeeds, so a failed handshake cannot pass for a cut
            responseWritten.get(CLOSED_WITHIN_MILLIS, TimeUnit.MILLISECONDS);
            assertThat("the stalled connection was cut without waiting for its close_notify to flush",
                connection.closeFuture().await(CLOSED_WITHIN_MILLIS, TimeUnit.MILLISECONDS), is(true));
            assertThat("the cut is recorded as MockServer's own", ServerClosedChannels.isClosedByServer(connection), is(true));
        } finally {
            server.close().sync();
        }
    }
}

package org.mockserver.httpclient;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandshakeCompletionEvent;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.NettySslContextFactory;
import org.mockserver.socket.tls.PEMToFile;

import javax.net.ssl.SSLException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.mockserver.configuration.Configuration.configuration;

/**
 * An HTTPS upstream on {@code localhost} whose certificate MockServer's own (bundled) CA signed, or with
 * {@link #signedBySeparateCa()} one {@link #SEPARATE_CA} signed, recording each request with the client port it arrived
 * from and its position on that connection, and answering each with an empty, keep-alive {@code 200}.
 */
public final class TlsTestUpstream implements AutoCloseable {

    /**
     * A CA that is not MockServer's, as a trust file.
     */
    public static final String SEPARATE_CA = "org/mockserver/authentication/mtls/separateca/ca.pem";

    public record Received(int clientPort, int requestOnConnection, String method, HttpHeaders headers) {
    }

    private final NioEventLoopGroup group = new NioEventLoopGroup(1);
    private final Channel channel;
    private final List<Received> received = new CopyOnWriteArrayList<>();
    private final CompletableFuture<Throwable> handshakeFailure = new CompletableFuture<>();
    private final CompletableFuture<Received> firstRequest = new CompletableFuture<>();

    public TlsTestUpstream() {
        this(new NettySslContextFactory(configuration(), new MockServerLogger(), true).createServerSslContext());
    }

    /**
     * An upstream whose certificate, for {@code localhost} and {@code 127.0.0.1}, {@link #SEPARATE_CA} signed.
     */
    public static TlsTestUpstream signedBySeparateCa() throws SSLException {
        return new TlsTestUpstream(SslContextBuilder
            .forServer(
                PEMToFile.privateKeyFromPEMFile("org/mockserver/authentication/mtls/separateca/leaf-key-pkcs8.pem"),
                PEMToFile.x509ChainFromPEMFile("org/mockserver/authentication/mtls/separateca/leaf-cert-chain.pem")
            )
            .build());
    }

    /**
     * An upstream that presents the certificate {@code sslContext} serves.
     */
    public static TlsTestUpstream servingWith(SslContext sslContext) {
        return new TlsTestUpstream(sslContext);
    }

    private TlsTestUpstream(SslContext sslContext) {
        channel = new ServerBootstrap()
            .group(group)
            .channel(NioServerSocketChannel.class)
            .childHandler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(sslContext.newHandler(ch.alloc()), new HttpServerCodec(), new HttpObjectAggregator(65536), new SimpleChannelInboundHandler<FullHttpRequest>() {
                        private int requests;

                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
                            Received one = new Received(((InetSocketAddress) ctx.channel().remoteAddress()).getPort(), ++requests, request.method().name(), request.headers().copy());
                            received.add(one);
                            firstRequest.complete(one);
                            FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
                            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
                            ctx.writeAndFlush(response);
                        }

                        @Override
                        public void userEventTriggered(ChannelHandlerContext ctx, Object event) throws Exception {
                            if (event instanceof SslHandshakeCompletionEvent && !((SslHandshakeCompletionEvent) event).isSuccess()) {
                                handshakeFailure.complete(((SslHandshakeCompletionEvent) event).cause());
                            }
                            super.userEventTriggered(ctx, event);
                        }

                        @Override
                        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                            ctx.close();
                        }
                    });
                }
            })
            .bind(InetAddress.getLoopbackAddress(), 0)
            .syncUninterruptibly()
            .channel();
    }

    public int port() {
        return ((InetSocketAddress) channel.localAddress()).getPort();
    }

    public List<Received> received() {
        return received;
    }

    /**
     * The cause of the first TLS handshake a client refused, once there is one.
     */
    public Throwable awaitHandshakeFailure() throws Exception {
        return handshakeFailure.get(30, TimeUnit.SECONDS);
    }

    public Received awaitFirstRequest() throws Exception {
        return firstRequest.get(30, TimeUnit.SECONDS);
    }

    @Override
    public void close() {
        channel.close().syncUninterruptibly();
        group.shutdownGracefully(0, 0, TimeUnit.MILLISECONDS).syncUninterruptibly();
    }
}

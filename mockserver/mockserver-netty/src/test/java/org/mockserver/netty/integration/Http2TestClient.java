package org.mockserver.netty.integration;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPromise;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.DefaultHttp2ResetFrame;
import io.netty.handler.codec.http2.DefaultHttp2SettingsFrame;
import io.netty.handler.codec.http2.Http2CodecUtil;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2Error;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2SecurityUtil;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2SettingsAckFrame;
import io.netty.handler.codec.http2.Http2SettingsFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.proxy.ProxyHandler;
import io.netty.handler.proxy.Socks4ProxyHandler;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.ssl.ApplicationProtocolConfig;
import io.netty.handler.ssl.ApplicationProtocolNames;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.SupportedCipherSuiteFilter;
import io.netty.resolver.NoopAddressResolverGroup;
import io.netty.util.ReferenceCountUtil;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.netty.MockServerCaTrustTestSupport.caCertificate;

/**
 * An HTTP/2 connection to MockServer that sends the header lists it is given, whatever limit the server advertises,
 * reads response headers of any size, and reports the server's SETTINGS, each stream's response and reset, and the
 * connection's GOAWAY.
 */
public final class Http2TestClient implements AutoCloseable {

    private static final long WAIT_SECONDS = 15;

    private final Channel connection;
    private final CompletableFuture<Http2Settings> serverSettings = new CompletableFuture<>();
    private final CompletableFuture<Long> goAway = new CompletableFuture<>();
    private final CompletableFuture<String> goAwayDebugData = new CompletableFuture<>();
    private final ConcurrentLinkedQueue<CompletableFuture<Void>> settingsAcks = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<Http2StreamChannel> streamsNotReading = new ConcurrentLinkedQueue<>();

    private Http2TestClient(Channel connection) {
        this.connection = connection;
    }

    /**
     * Cleartext HTTP/2 with prior knowledge.
     */
    public static Http2TestClient h2c(EventLoopGroup group, int port) throws Exception {
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    // filled in once the client exists
                }
            })
            .connect("127.0.0.1", port).sync().channel();
        Http2TestClient client = new Http2TestClient(channel);
        client.addHttp2Handlers();
        return client;
    }

    /**
     * HTTP/2 negotiated by ALPN on a TLS connection made straight to MockServer.
     */
    public static Http2TestClient tls(EventLoopGroup group, int port) throws Exception {
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    // filled in once the client exists
                }
            })
            .connect("127.0.0.1", port).sync().channel();
        return overTls(channel, "localhost", port);
    }

    /**
     * HTTP/2 through MockServer's CONNECT proxy: CONNECT over HTTP/1.1, then TLS with ALPN {@code h2}.
     */
    public static Http2TestClient throughConnect(EventLoopGroup group, int port, String targetHost, int targetPort) throws Exception {
        return throughConnect(group, port, targetHost, targetPort, true);
    }

    /**
     * HTTP/2 through MockServer's CONNECT proxy: CONNECT over HTTP/1.1, then TLS with ALPN {@code h2}, or cleartext
     * with prior knowledge.
     */
    public static Http2TestClient throughConnect(EventLoopGroup group, int port, String targetHost, int targetPort, boolean tls) throws Exception {
        CompletableFuture<Integer> connectStatus = new CompletableFuture<>();
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast("http-codec", new HttpClientCodec());
                    ch.pipeline().addLast("http-aggregator", new HttpObjectAggregator(64 * 1024));
                    ch.pipeline().addLast("connect-response", new SimpleChannelInboundHandler<FullHttpResponse>() {
                        @Override
                        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                            ctx.pipeline().remove("http-codec");
                            ctx.pipeline().remove("http-aggregator");
                            ctx.pipeline().remove(this);
                            connectStatus.complete(response.status().code());
                        }
                    });
                }
            })
            .connect("127.0.0.1", port).sync().channel();
        String target = targetHost + ":" + targetPort;
        DefaultFullHttpRequest connectRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, target);
        connectRequest.headers().set(HttpHeaderNames.HOST, target);
        channel.writeAndFlush(connectRequest);
        assertThat(connectStatus.get(WAIT_SECONDS, TimeUnit.SECONDS), is(200));
        if (tls) {
            return overTls(channel, targetHost, targetPort);
        }
        Http2TestClient client = new Http2TestClient(channel);
        client.addHttp2Handlers();
        return client;
    }

    /**
     * HTTP/2 through MockServer's SOCKS5 proxy, the target passed to it unresolved: over TLS with ALPN {@code h2}, or
     * cleartext with prior knowledge.
     */
    public static Http2TestClient throughSocks5(EventLoopGroup group, int port, String targetHost, int targetPort, boolean tls) throws Exception {
        return throughSocks(group, new Socks5ProxyHandler(new InetSocketAddress("127.0.0.1", port)), InetSocketAddress.createUnresolved(targetHost, targetPort), tls);
    }

    /**
     * HTTP/2 through MockServer's SOCKS4 proxy, which takes an IPv4 address as its target.
     */
    public static Http2TestClient throughSocks4(EventLoopGroup group, int port, String targetAddress, int targetPort, boolean tls) throws Exception {
        return throughSocks(group, new Socks4ProxyHandler(new InetSocketAddress("127.0.0.1", port)), new InetSocketAddress(targetAddress, targetPort), tls);
    }

    private static Http2TestClient throughSocks(EventLoopGroup group, ProxyHandler socks, InetSocketAddress target, boolean tls) throws Exception {
        Channel channel = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            // the SOCKS proxy is given the target as it is
            .resolver(NoopAddressResolverGroup.INSTANCE)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(socks);
                }
            })
            .connect(target).sync().channel();
        assertThat("SOCKS tunnel established", socks.connectFuture().await(WAIT_SECONDS, TimeUnit.SECONDS) && socks.connectFuture().isSuccess(), is(true));
        if (tls) {
            return overTls(channel, target.getHostString(), target.getPort());
        }
        Http2TestClient client = new Http2TestClient(channel);
        client.addHttp2Handlers();
        return client;
    }

    private static Http2TestClient overTls(Channel channel, String host, int port) throws Exception {
        SslContext sslContext = SslContextBuilder.forClient()
            .trustManager(caCertificate())
            .ciphers(Http2SecurityUtil.CIPHERS, SupportedCipherSuiteFilter.INSTANCE)
            .applicationProtocolConfig(new ApplicationProtocolConfig(
                ApplicationProtocolConfig.Protocol.ALPN,
                ApplicationProtocolConfig.SelectorFailureBehavior.NO_ADVERTISE,
                ApplicationProtocolConfig.SelectedListenerFailureBehavior.ACCEPT,
                ApplicationProtocolNames.HTTP_2))
            .build();
        SslHandler sslHandler = sslContext.newHandler(channel.alloc(), host, port);
        Http2TestClient client = new Http2TestClient(channel);
        // h2 is the only protocol offered, and the preface waits in the TLS handler until the handshake is done
        channel.pipeline().addLast(sslHandler);
        client.addHttp2Handlers();
        sslHandler.handshakeFuture().sync();
        assertThat(sslHandler.applicationProtocol(), is(ApplicationProtocolNames.HTTP_2));
        return client;
    }

    private void addHttp2Handlers() {
        connection.pipeline().addLast(Http2FrameCodecBuilder.forClient()
            // or the encoder would apply the server's limit itself, and an over-limit request would never be sent
            .encoderIgnoreMaxHeaderListSize(true)
            // response headers of any size are read
            .initialSettings(Http2Settings.defaultSettings().maxHeaderListSize(Http2CodecUtil.MAX_HEADER_LIST_SIZE))
            .build());
        connection.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel pushedStream) {
                // MockServer does not push
            }
        }));
        connection.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                try {
                    if (msg instanceof Http2SettingsFrame) {
                        serverSettings.complete(((Http2SettingsFrame) msg).settings());
                    } else if (msg instanceof Http2GoAwayFrame) {
                        goAwayDebugData.complete(((Http2GoAwayFrame) msg).content().toString(StandardCharsets.UTF_8));
                        goAway.complete(((Http2GoAwayFrame) msg).errorCode());
                    } else if (msg instanceof Http2SettingsAckFrame) {
                        CompletableFuture<Void> acknowledged = settingsAcks.poll();
                        if (acknowledged != null) {
                            acknowledged.complete(null);
                        }
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                // a connection the server closes is observed through closed() and goAwayErrorCode()
            }
        });
    }

    /**
     * @return the first SETTINGS frame the server sent
     */
    public Http2Settings serverSettings() throws Exception {
        return serverSettings.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * @return the error code of the GOAWAY the server sent
     */
    public long goAwayErrorCode() throws Exception {
        return goAway.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * @return the debug data of the first GOAWAY the server sent
     */
    public String goAwayDebugData() throws Exception {
        return goAwayDebugData.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    public boolean closedWithin(long seconds) throws InterruptedException {
        return connection.closeFuture().await(seconds, TimeUnit.SECONDS);
    }

    public boolean isOpen() {
        return connection.isActive();
    }

    /**
     * Limits the window of each stream opened from now on, so a response body larger than it stays partly unsent
     * until the stream's reader takes what has arrived. Returns once the server has acknowledged the setting.
     */
    public void streamWindow(int bytes) throws Exception {
        CompletableFuture<Void> acknowledged = new CompletableFuture<>();
        settingsAcks.add(acknowledged);
        connection.writeAndFlush(new DefaultHttp2SettingsFrame(new Http2Settings().initialWindowSize(bytes))).sync();
        acknowledged.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * @return the address MockServer sees this connection come from
     */
    public InetSocketAddress localAddress() {
        return (InetSocketAddress) connection.localAddress();
    }

    /**
     * Closes the connection with a TCP reset: no GOAWAY and no TLS close_notify is sent first.
     */
    public void resetConnection() throws Exception {
        connection.config().setOption(ChannelOption.SO_LINGER, 0);
        // the transport's own close, which the HTTP/2 codec and the TLS handler do not see
        connection.eventLoop().submit(() -> connection.unsafe().close(connection.voidPromise())).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(closedWithin(WAIT_SECONDS), is(true));
    }

    /**
     * Closes the connection as a client that goes away does: no GOAWAY and no TLS close_notify first, and no wait
     * for the streams still open.
     */
    public void closeAbruptly() throws Exception {
        connection.eventLoop().submit(() -> connection.unsafe().close(connection.voidPromise())).get(WAIT_SECONDS, TimeUnit.SECONDS);
        assertThat(closedWithin(WAIT_SECONDS), is(true));
    }

    /**
     * Sends bytes as they are, beneath the HTTP/2 codec: a frame the codec would not write.
     */
    public void sendRaw(byte[] bytes) throws Exception {
        connection.pipeline().context(Http2FrameCodec.class).writeAndFlush(Unpooled.wrappedBuffer(bytes)).get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Sends bytes as they are beneath the TLS handler, where the server expects a TLS record, without waiting to
     * learn whether a server that may already have closed the connection took them.
     */
    public void sendBeneathTls(byte[] bytes) throws Exception {
        connection.pipeline().context(SslHandler.class).writeAndFlush(Unpooled.wrappedBuffer(bytes)).await(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /**
     * Sends a request's headers on a new stream, with END_STREAM unless a request body is to follow. A stream left
     * open is how a reset sent after the response is observed: one for a stream both sides have ended is ignored.
     */
    public Exchange send(Http2Headers headers, boolean endStream) throws Exception {
        return send(headers, endStream, false);
    }

    /**
     * As {@link #send(Http2Headers, boolean)}, but reads nothing after the response's headers: the body that has
     * arrived is not taken, so the stream's window is not given back and the rest of the body stays with the server.
     */
    public Exchange sendReadingOnlyTheResponseHeaders(Http2Headers headers, boolean endStream) throws Exception {
        return send(headers, endStream, true);
    }

    private Exchange send(Http2Headers headers, boolean endStream, boolean readOnlyTheResponseHeaders) throws Exception {
        Exchange exchange = new Exchange(connection);
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(connection)
            .option(ChannelOption.AUTO_READ, !readOnlyTheResponseHeaders)
            .handler(new ChannelInboundHandlerAdapter() {
                private boolean readsStopped;

                @Override
                public void channelActive(ChannelHandlerContext ctx) {
                    ctx.read();
                }

                @Override
                public void channelReadComplete(ChannelHandlerContext ctx) {
                    if (readOnlyTheResponseHeaders && !readsStopped) {
                        ctx.read();
                    }
                }

                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2HeadersFrame) {
                            Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                            CharSequence status = headersFrame.headers().status();
                            if (status != null && status.charAt(0) == '1') {
                                exchange.interimEndedStream = headersFrame.isEndStream();
                                exchange.interimStatus.complete(Integer.parseInt(status.toString()));
                            } else if (status != null) {
                                readsStopped = readOnlyTheResponseHeaders;
                                exchange.headersAtNanos = System.nanoTime();
                                exchange.headers.complete(headersFrame.headers());
                                exchange.status.complete(Integer.parseInt(status.toString()));
                            } else {
                                exchange.trailers.complete(headersFrame.headers());
                            }
                            if (headersFrame.isEndStream()) {
                                exchange.endAtNanos = System.nanoTime();
                                exchange.body.complete(exchange.received());
                            }
                        } else if (msg instanceof Http2DataFrame) {
                            Http2DataFrame data = (Http2DataFrame) msg;
                            if (exchange.firstDataAtNanos == 0 && data.content().isReadable()) {
                                exchange.firstDataAtNanos = System.nanoTime();
                            }
                            exchange.received(ByteBufUtil.getBytes(data.content()));
                            if (data.isEndStream()) {
                                exchange.endAtNanos = System.nanoTime();
                                exchange.body.complete(exchange.received());
                            }
                        } else if (msg instanceof Http2ResetFrame) {
                            reset(((Http2ResetFrame) msg).errorCode());
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }

                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                    if (evt instanceof Http2ResetFrame) {
                        reset(((Http2ResetFrame) evt).errorCode());
                    }
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    IllegalStateException closed = new IllegalStateException("stream closed");
                    exchange.interimStatus.completeExceptionally(closed);
                    exchange.headers.completeExceptionally(closed);
                    exchange.status.completeExceptionally(closed);
                    exchange.body.completeExceptionally(closed);
                    exchange.trailers.completeExceptionally(closed);
                    exchange.reset.completeExceptionally(closed);
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    exchange.headers.completeExceptionally(cause);
                    exchange.status.completeExceptionally(cause);
                    exchange.body.completeExceptionally(cause);
                }

                private void reset(long errorCode) {
                    exchange.reset.complete(errorCode);
                    IllegalStateException reset = new IllegalStateException("stream reset with error code " + errorCode);
                    exchange.interimStatus.completeExceptionally(reset);
                    exchange.headers.completeExceptionally(reset);
                    exchange.status.completeExceptionally(reset);
                    exchange.body.completeExceptionally(reset);
                    exchange.trailers.completeExceptionally(reset);
                }
            })
            .open().sync().getNow();
        exchange.stream = stream;
        exchange.sentAtNanos = System.nanoTime();
        if (readOnlyTheResponseHeaders) {
            streamsNotReading.add(stream);
        }
        stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers, endStream)).sync();
        exchange.streamId = stream.stream().id();
        return exchange;
    }

    /**
     * Sends a request body, or the last part of it, on a stream opened by {@link #send(Http2Headers, boolean)} without
     * END_STREAM.
     */
    public void sendData(Exchange exchange, byte[] data, boolean endStream) throws Exception {
        exchange.stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(data), endStream)).sync();
    }

    @Override
    public void close() {
        // a stream that stopped reading still holds what arrived for it, and is not closed with its connection
        for (Http2StreamChannel stream : streamsNotReading) {
            stream.close().syncUninterruptibly();
        }
        connection.close().syncUninterruptibly();
    }

    public static final class Exchange {
        private final Channel connection;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private final CompletableFuture<Integer> interimStatus = new CompletableFuture<>();
        private volatile Http2StreamChannel stream;
        private final CompletableFuture<Http2Headers> headers = new CompletableFuture<>();
        private final CompletableFuture<Integer> status = new CompletableFuture<>();
        private final CompletableFuture<String> body = new CompletableFuture<>();
        private final CompletableFuture<Long> reset = new CompletableFuture<>();
        private final CompletableFuture<Http2Headers> trailers = new CompletableFuture<>();
        private volatile long sentAtNanos;
        private volatile long headersAtNanos;
        private volatile long firstDataAtNanos;
        private volatile long endAtNanos;
        private volatile int streamId;
        private volatile boolean interimEndedStream;

        public int streamId() {
            return streamId;
        }

        /**
         * @return the value of a response header, or null if the response has none of that name
         */
        public String header(String name) throws Exception {
            CharSequence value = headers.get(WAIT_SECONDS, TimeUnit.SECONDS).get(name);
            return value != null ? value.toString() : null;
        }

        /**
         * @return the response's header block as it arrived, pseudo-headers included
         */
        public Http2Headers headers() throws Exception {
            return headers.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        public int status() throws Exception {
            return status.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        public String body() throws Exception {
            return body.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        /**
         * @return the trailers that ended the response, or null when it ended without any
         */
        public Http2Headers trailers() throws Exception {
            body.get(WAIT_SECONDS, TimeUnit.SECONDS);
            // with none, the stream's close completes them exceptionally
            return trailers.isDone() && !trailers.isCompletedExceptionally() ? trailers.join() : null;
        }

        public long resetErrorCode() throws Exception {
            return reset.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        private Exchange(Channel connection) {
            this.connection = connection;
        }

        /**
         * @return the status of the {@code 1xx} response that came before the final one
         */
        public int interimStatus() throws Exception {
            return interimStatus.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        /**
         * @return whether the HEADERS frame of the {@code 1xx} response carried END_STREAM, which RFC 9113 section 8.1
         * calls malformed; waits for the {@code 1xx}
         */
        public boolean interimEndedStream() throws Exception {
            interimStatus();
            return interimEndedStream;
        }

        /**
         * @return the response body received so far
         */
        public String received() {
            synchronized (received) {
                return received.toString(StandardCharsets.UTF_8);
            }
        }

        private void received(byte[] bytes) {
            synchronized (received) {
                received.writeBytes(bytes);
            }
        }

        /**
         * @return whether the response body received so far came to hold {@code text} within {@code seconds}
         */
        public boolean receivedWithin(String text, long seconds) throws InterruptedException {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
            while (!received().contains(text)) {
                if (System.nanoTime() > deadline) {
                    return false;
                }
                Thread.sleep(10);
            }
            return true;
        }

        public boolean isReset() {
            return reset.isDone() && !reset.isCompletedExceptionally();
        }

        /**
         * @return whether the response has ended: its last frame has arrived
         */
        public boolean isComplete() {
            return body.isDone() && !body.isCompletedExceptionally();
        }

        /**
         * @return the value of a response trailer, or null if the trailers have none of that name
         */
        public String trailer(String name) throws Exception {
            CharSequence value = trailers.get(WAIT_SECONDS, TimeUnit.SECONDS).get(name);
            return value != null ? value.toString() : null;
        }

        /**
         * @return the milliseconds from the request's headers being sent to the response's headers arriving
         */
        public long millisToHeaders() throws Exception {
            status();
            return TimeUnit.NANOSECONDS.toMillis(headersAtNanos - sentAtNanos);
        }

        /**
         * @return the milliseconds from the request's headers being sent to the first byte of the response body
         * arriving, waiting for it if it has not yet
         */
        public long millisToFirstData() throws Exception {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
            while (firstDataAtNanos == 0) {
                assertThat("response data within " + WAIT_SECONDS + "s", System.nanoTime() < deadline, is(true));
                Thread.sleep(5);
            }
            return TimeUnit.NANOSECONDS.toMillis(firstDataAtNanos - sentAtNanos);
        }

        /**
         * @return the milliseconds between the first byte of the response body arriving and the response ending,
         * waiting for its end
         */
        public long millisFromFirstDataToEnd() throws Exception {
            body();
            return TimeUnit.NANOSECONDS.toMillis(endAtNanos - firstDataAtNanos);
        }

        /**
         * @return the response body received so far, as bytes
         */
        public byte[] receivedByteArray() {
            synchronized (received) {
                return received.toByteArray();
            }
        }

        /**
         * Sends a DATA frame of the request body.
         */
        public Exchange data(byte[] body, boolean endStream) throws Exception {
            stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), endStream)).sync();
            return this;
        }

        /**
         * Takes the next frame that has arrived on a stream opened with
         * {@link Http2TestClient#sendReadingOnlyTheResponseHeaders}, and gives its window back.
         */
        public Exchange readOneFrame() {
            stream.read();
            return this;
        }

        /**
         * Reads the rest of a stream opened with {@link Http2TestClient#sendReadingOnlyTheResponseHeaders} as it arrives.
         */
        public Exchange readTheRest() {
            stream.config().setAutoRead(true);
            return this;
        }

        /**
         * @return the number of bytes of the response body received so far
         */
        public int receivedBytes() {
            synchronized (received) {
                return received.size();
            }
        }

        /**
         * Sends a DATA frame of the request body.
         */
        public Exchange data(String body, boolean endStream) throws Exception {
            stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.copiedBuffer(body, StandardCharsets.UTF_8), endStream)).sync();
            return this;
        }

        /**
         * Resets the stream, as a client that gives up on a request does.
         */
        public Exchange reset(Http2Error error) throws Exception {
            stream.writeAndFlush(new DefaultHttp2ResetFrame(error)).sync();
            return this;
        }

        /**
         * Sends the request's trailers: a HEADERS frame with END_STREAM, after the request's headers and body.
         */
        public Exchange trailers(Http2Headers trailers) throws Exception {
            stream.writeAndFlush(new DefaultHttp2HeadersFrame(trailers, true)).sync();
            return this;
        }

        /**
         * Sends a HEADERS frame with END_STREAM on this stream whatever its state, as no well-behaved client does on
         * a stream it has already ended: it is written by the frame writer, past the checks of the client's codec.
         */
        public Exchange headersWhateverTheStreamState(Http2Headers headers) throws Exception {
            ChannelHandlerContext codecContext = connection.pipeline().context(Http2FrameCodec.class);
            Http2FrameCodec codec = (Http2FrameCodec) codecContext.handler();
            ChannelPromise written = connection.newPromise();
            connection.eventLoop().execute(() -> {
                codec.encoder().frameWriter().writeHeaders(codecContext, stream.stream().id(), headers, 0, true, written);
                codecContext.flush();
            });
            written.sync();
            return this;
        }
    }
}

package org.mockserver.netty.integration.corpus;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
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
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2GoAwayFrame;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A cleartext HTTP/2 (prior knowledge) connection that records, in arrival order, each frame the server sends on
 * the streams it opens: HEADERS (header order kept), DATA (one entry per frame, so frame boundaries show), trailers,
 * RST_STREAM, and the connection's GOAWAY and close.
 */
final class RecordingHttp2Client implements AutoCloseable {

    private static final long WAIT_SECONDS = 10;

    private final Channel connection;
    private final CorpusNormaliser normaliser;
    private final StringBuffer transcript = new StringBuffer();

    private RecordingHttp2Client(Channel connection, CorpusNormaliser normaliser) {
        this.connection = connection;
        this.normaliser = normaliser;
    }

    static RecordingHttp2Client h2c(EventLoopGroup group, int port, CorpusNormaliser normaliser) throws Exception {
        Channel channel = bootstrap(group, null).connect("127.0.0.1", port).sync().channel();
        normaliser.knownPort(((InetSocketAddress) channel.localAddress()).getPort(), "client");
        RecordingHttp2Client client = new RecordingHttp2Client(channel, normaliser);
        client.addHttp2Handlers();
        return client;
    }

    /**
     * CONNECT over HTTP/1.1, then cleartext HTTP/2 with prior knowledge inside the tunnel.
     */
    static RecordingHttp2Client throughConnect(EventLoopGroup group, int port, String target, CorpusNormaliser normaliser) throws Exception {
        CompletableFuture<FullHttpResponse> connectResponse = new CompletableFuture<>();
        Channel channel = bootstrap(group, connectResponse).connect("127.0.0.1", port).sync().channel();
        normaliser.knownPort(((InetSocketAddress) channel.localAddress()).getPort(), "client");
        DefaultFullHttpRequest connect = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.CONNECT, target);
        connect.headers().set(HttpHeaderNames.HOST, target);
        channel.writeAndFlush(connect);
        FullHttpResponse response = connectResponse.get(WAIT_SECONDS, TimeUnit.SECONDS);
        RecordingHttp2Client client = new RecordingHttp2Client(channel, normaliser);
        StringBuilder head = new StringBuilder("HTTP/1.1 ").append(response.status()).append("\r\n");
        for (Map.Entry<String, String> header : response.headers()) {
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        client.transcript.append("<<< CONNECT response over HTTP/1.1, as decoded\n")
            .append(CorpusNormaliser.escape(normaliser.normaliseHead(head.toString()).getBytes(StandardCharsets.ISO_8859_1)));
        response.release();
        client.addHttp2Handlers();
        return client;
    }

    private static Bootstrap bootstrap(EventLoopGroup group, CompletableFuture<FullHttpResponse> connectResponse) {
        return new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    if (connectResponse != null) {
                        ch.pipeline().addLast("http-codec", new HttpClientCodec());
                        ch.pipeline().addLast("http-aggregator", new HttpObjectAggregator(64 * 1024));
                        ch.pipeline().addLast("connect-response", new SimpleChannelInboundHandler<FullHttpResponse>(false) {
                            @Override
                            protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) {
                                ctx.pipeline().remove("http-codec");
                                ctx.pipeline().remove("http-aggregator");
                                ctx.pipeline().remove(this);
                                connectResponse.complete(response);
                            }
                        });
                    }
                }
            });
    }

    private void addHttp2Handlers() {
        connection.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
        connection.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel pushed) {
                // MockServer does not push
            }
        }));
        connection.pipeline().addLast(new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext ctx, Object msg) {
                try {
                    if (msg instanceof Http2GoAwayFrame) {
                        Http2GoAwayFrame goAway = (Http2GoAwayFrame) msg;
                        transcript.append("<<< connection GOAWAY error=").append(goAway.errorCode())
                            .append(" debug=").append(CorpusNormaliser.escapeInline(goAway.content().toString(StandardCharsets.ISO_8859_1))).append('\n');
                    }
                } finally {
                    ReferenceCountUtil.release(msg);
                }
            }

            @Override
            public void channelInactive(ChannelHandlerContext ctx) {
                transcript.append("<<< connection closed\n");
            }

            @Override
            public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                transcript.append("<<< connection exception ").append(cause.getClass().getSimpleName()).append('\n');
            }
        });
    }

    static Http2Headers h2Request(String method, String path, String... nameValuePairs) {
        Http2Headers headers = new DefaultHttp2Headers()
            .method(method)
            .path(path)
            .scheme("http")
            .authority("localhost");
        for (int i = 0; i < nameValuePairs.length; i += 2) {
            headers.add(nameValuePairs[i], nameValuePairs[i + 1]);
        }
        return headers;
    }

    /**
     * Opens a stream and sends the request headers, then the body if there is one.
     */
    Exchange send(Http2Headers headers, byte[] body, boolean endStream) throws Exception {
        Exchange exchange = new Exchange();
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(connection)
            .handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2HeadersFrame) {
                            Http2HeadersFrame frame = (Http2HeadersFrame) msg;
                            StringBuilder lines = new StringBuilder();
                            for (Map.Entry<CharSequence, CharSequence> header : frame.headers()) {
                                lines.append(CorpusNormaliser.escapeInline(header.getKey())).append(": ").append(CorpusNormaliser.escapeInline(header.getValue())).append('\n');
                            }
                            transcript.append("<<< stream HEADERS endStream=").append(frame.isEndStream()).append('\n')
                                .append(normaliser.normaliseHead(lines.toString()));
                            if (frame.isEndStream()) {
                                exchange.ended.complete(null);
                            }
                        } else if (msg instanceof Http2DataFrame) {
                            Http2DataFrame frame = (Http2DataFrame) msg;
                            byte[] data = ByteBufUtil.getBytes(frame.content());
                            transcript.append("<<< stream DATA length=").append(data.length).append(" endStream=").append(frame.isEndStream()).append('\n')
                                .append(CorpusNormaliser.escape(data));
                            if (frame.isEndStream()) {
                                exchange.ended.complete(null);
                            }
                        } else if (msg instanceof Http2ResetFrame) {
                            reset((Http2ResetFrame) msg);
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }

                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                    if (evt instanceof Http2ResetFrame) {
                        reset((Http2ResetFrame) evt);
                    }
                }

                @Override
                public void channelInactive(ChannelHandlerContext ctx) {
                    if (!exchange.ended.isDone()) {
                        transcript.append("<<< stream closed before it ended\n");
                    }
                    exchange.ended.complete(null);
                }

                private void reset(Http2ResetFrame frame) {
                    transcript.append("<<< stream RST_STREAM error=").append(frame.errorCode()).append('\n');
                    exchange.ended.complete(null);
                }
            })
            .open().sync().getNow();
        boolean hasBody = body != null;
        stream.writeAndFlush(new DefaultHttp2HeadersFrame(headers, endStream && !hasBody)).sync();
        if (hasBody) {
            stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(body), endStream)).sync();
        }
        return exchange;
    }

    /**
     * The transcript so far; taken before closing, so the client's own close is not in it.
     */
    String transcript() {
        return transcript.toString();
    }

    @Override
    public void close() {
        connection.close().syncUninterruptibly();
    }

    final class Exchange {
        private final CompletableFuture<Void> ended = new CompletableFuture<>();

        /**
         * Waits for the stream to end: END_STREAM, RST_STREAM, or the stream or connection closing.
         */
        void awaitEnd() throws Exception {
            ended.get(WAIT_SECONDS, TimeUnit.SECONDS);
        }

        /**
         * For a response that may never end the stream: waits a fixed time, then records that the stream is open.
         */
        void awaitEndOrRecordStillOpen(long millis) throws Exception {
            try {
                ended.get(millis, TimeUnit.MILLISECONDS);
            } catch (TimeoutException stillOpen) {
                transcript.append("<<< stream still open ").append(millis).append(" ms after the request\n");
            }
        }
    }
}

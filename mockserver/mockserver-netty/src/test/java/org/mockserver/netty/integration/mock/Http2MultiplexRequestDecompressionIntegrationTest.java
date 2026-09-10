package org.mockserver.netty.integration.mock;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpScheme;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.mock.Expectation;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Behavioural proof that a {@code content-encoding: gzip} request body is decompressed on the HTTP/2
 * <em>multiplex</em> pipeline (gRPC bidi streaming on + a startup-loaded gRPC descriptor with services
 * — the same enablement as {@link Http2MultiplexConnectionScopeIntegrationTest}), so a body-matching
 * expectation matches exactly as it does on the HTTP/1.1 and connection-adapter HTTP/2 paths.
 * <p>
 * The multiplex pipeline carries every stream on the connection — ordinary HTTP POSTs included, not
 * just gRPC. Before the fix its per-stream child pipeline installed no decompressor, so a gzipped body
 * reached the matchers as compressed bytes (with {@code content-encoding: gzip} still on the request),
 * and a {@code withBody(...)} expectation silently failed to match — MockServer answered 404. The fix
 * inserts an {@link io.netty.handler.codec.http.HttpContentDecompressor} between the stream-frame codec
 * and the aggregator (mirroring the HTTP/1.1 ordering), so the body is decompressed before matching.
 * <p>
 * A raw in-JVM Netty h2c multiplex client is used so the gzipped bytes are put on the wire verbatim
 * (a normal client library would compress/decompress or reject on its own).
 */
public class Http2MultiplexRequestDecompressionIntegrationTest {

    // load a gRPC descriptor at startup so the HTTP/2 multiplex path engages; the descriptor's
    // presence, not gRPC traffic, is what selects it.
    private static final String GRPC_DESCRIPTOR_DIRECTORY = "../mockserver-core/src/test/resources/grpc";

    private static final String DECOMPRESSED_BODY = "hello decompressed multiplex body";

    private MockServer mockServer;
    private MockServerClient mockServerClient;

    @Before
    public void startServer() {
        Configuration configuration = configuration()
            .grpcBidiStreamingEnabled(true)
            .grpcDescriptorDirectory(GRPC_DESCRIPTOR_DIRECTORY);
        mockServer = new MockServer(configuration);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.upsert(
            new Expectation(
                request()
                    .withMethod("POST")
                    .withPath("/http2_multiplex_decompress")
                    .withBody(DECOMPRESSED_BODY)
            ).thenRespond(
                response().withStatusCode(200).withBody("matched_decompressed")
            )
        );
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    @Test(timeout = 30000)
    public void shouldDecompressGzipRequestBodyOnTheMultiplexPath() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            Channel parent = connectMultiplexParent(group);

            CompletableFuture<Response> responseFuture = new CompletableFuture<>();
            Http2StreamChannel streamChannel = new Http2StreamChannelBootstrap(parent)
                .handler(new ResponseCollector(responseFuture))
                .open()
                .sync()
                .getNow();

            byte[] gzippedBody = gzip(DECOMPRESSED_BODY.getBytes(StandardCharsets.UTF_8));

            Http2Headers headers = new DefaultHttp2Headers()
                .method(HttpMethod.POST.asciiName())
                .scheme(HttpScheme.HTTP.name())
                .authority("localhost:" + mockServer.getLocalPort())
                .path("/http2_multiplex_decompress");
            headers.set(HttpHeaderNames.CONTENT_ENCODING, "gzip");
            streamChannel.write(new DefaultHttp2HeadersFrame(headers, false));
            streamChannel.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(gzippedBody), true));

            Response response = responseFuture.get(15, TimeUnit.SECONDS);

            // Before the fix the gzipped body reaches the matchers as compressed bytes, the body-matching
            // expectation never matches, and MockServer returns 404. After the fix the body is
            // decompressed before matching, so the expectation matches and returns 200.
            assertThat("status (404 => body was not decompressed before matching) - body <" + response.body + ">",
                response.status, is("200"));
            assertThat(response.body, is("matched_decompressed"));
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS);
        }
    }

    private static byte[] gzip(byte[] raw) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(out)) {
            gzip.write(raw);
        }
        return out.toByteArray();
    }

    private Channel connectMultiplexParent(NioEventLoopGroup group) throws Exception {
        Bootstrap bootstrap = new Bootstrap()
            .group(group)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel ch) {
                            ch.pipeline().addLast(new ChannelInboundHandlerAdapter());
                        }
                    }));
                }
            });
        return bootstrap.connect("localhost", mockServer.getLocalPort()).sync().channel();
    }

    private static final class Response {
        final String status;
        final String body;

        Response(String status, String body) {
            this.status = status;
            this.body = body;
        }
    }

    private static final class ResponseCollector extends ChannelInboundHandlerAdapter {
        private final CompletableFuture<Response> done;
        private final StringBuilder body = new StringBuilder();
        private volatile String status;

        ResponseCollector(CompletableFuture<Response> done) {
            this.done = done;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            try {
                if (msg instanceof Http2HeadersFrame) {
                    Http2HeadersFrame headersFrame = (Http2HeadersFrame) msg;
                    CharSequence s = headersFrame.headers().status();
                    if (s != null) {
                        status = s.toString();
                    }
                    if (headersFrame.isEndStream()) {
                        done.complete(new Response(status, body.toString()));
                    }
                } else if (msg instanceof Http2DataFrame) {
                    Http2DataFrame dataFrame = (Http2DataFrame) msg;
                    body.append(dataFrame.content().toString(StandardCharsets.UTF_8));
                    if (dataFrame.isEndStream()) {
                        done.complete(new Response(status, body.toString()));
                    }
                }
            } finally {
                ReferenceCountUtil.release(msg);
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            done.completeExceptionally(cause);
        }
    }
}

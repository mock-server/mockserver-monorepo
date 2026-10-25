package org.mockserver.netty.grpc;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2Headers;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2ResetFrame;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.util.ReferenceCountUtil;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.corpus.CorpusLogEntries;
import org.mockserver.testing.corpus.CorpusNormaliser;
import org.mockserver.testing.corpus.GoldenCorpus;
import org.mockserver.testing.corpus.WireTranscript;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Differential corpus for gRPC over HTTP/2 (cleartext, prior knowledge): each response frame - HEADERS with its
 * fields in order and its END_STREAM flag, DATA with its bytes - and the serialised log entries of each scenario,
 * compared byte for byte with the goldens recorded from the baseline. See {@link GoldenCorpus} and
 * {@link CorpusNormaliser}; the HTTP/3 half is {@code Http3DataPlaneDifferentialCorpusIntegrationTest}.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class GrpcHttp2DataPlaneDifferentialCorpusIntegrationTest {

    private static final Path DESCRIPTOR_DIR = Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath();
    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final GoldenCorpus GOLDEN = GoldenCorpus.forSuite(GrpcHttp2DataPlaneDifferentialCorpusIntegrationTest.class);

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private CorpusLogEntries.Window log;
    private NioEventLoopGroup clientGroup;
    private GrpcProtoDescriptorStore descriptorStore;

    @Before
    public void startServer() {
        descriptorStore = new GrpcProtoDescriptorStore(new MockServerLogger());
        descriptorStore.loadDescriptorSetFromPath(DESCRIPTOR_DIR.resolve("greeting.dsc"));
        mockServer = new MockServer(configuration()
            .logLevel("INFO")
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .attemptToProxyIfNoMatchingExpectation(false), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        log = CorpusLogEntries.window(CorpusLogEntries.overHttp("http://127.0.0.1:" + mockServer.getLocalPort()));
        clientGroup = new NioEventLoopGroup(1);
    }

    @After
    public void stopServer() {
        if (mockServerClient != null) {
            mockServerClient.close();
        }
        if (mockServer != null) {
            mockServer.stop();
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
    }

    @Test
    public void grpcUnary() throws Exception {
        mockServerClient.upsert(when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting")).withId("corpus-h2-grpc-unary").thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("x-tenant-id", "acme")
                .withHeader("x-region", "eu-west-2")
                .withTrailer("x-request-cost", "unit=1; tier=gold")
                .withBody("{\"greeting\":\"Hello Wörld\"}")
        ));

        record("grpc-unary", "/" + SERVICE + "/Greeting", greetingFrame("Greeting", "World"));
    }

    @Test
    public void grpcServerStream() throws Exception {
        mockServerClient.upsert(when(request().withMethod("POST").withPath("/" + SERVICE + "/ListGreetings")).withId("corpus-h2-grpc-stream")
            .thenRespondWithGrpcStream(GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\": \"Hello 1\"}")
                .withMessage("{\"greeting\": \"Hello 2\"}")
                .withMessage("{\"greeting\": \"Hello 3\"}")));

        record("grpc-server-stream", "/" + SERVICE + "/ListGreetings", greetingFrame("ListGreetings", "World"));
    }

    @Test
    public void grpcUnimplementedMethod() throws Exception {
        record("grpc-unimplemented", "/" + SERVICE + "/NoSuchMethod", greetingFrame("Greeting", "World"));
    }

    private void record(String scenario, String path, byte[] requestBody) throws Exception {
        Http2Headers requestHeaders = new DefaultHttp2Headers()
            .method("POST").path(path).scheme("http").authority("localhost");
        requestHeaders.add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        requestHeaders.add("te", "trailers");
        Channel connection = connect();
        try {
            WireTranscript transcript = new WireTranscript().section("HTTP/2 response frames");
            for (String frame : exchange(connection, requestHeaders, requestBody)) {
                transcript.line(frame);
            }
            CorpusLogEntries.appendTo(transcript, log.entries());
            GOLDEN.assertMatches(scenario, new CorpusNormaliser()
                .ephemeralPort(mockServer.getLocalPort(), "<server-port>")
                .ephemeralPort(((InetSocketAddress) connection.localAddress()).getPort(), "<client-port>")
                .normalise(transcript.toString()));
        } finally {
            connection.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }

    private Channel connect() throws Exception {
        return new Bootstrap()
            .group(clientGroup)
            .channel(NioSocketChannel.class)
            .handler(new ChannelInitializer<SocketChannel>() {
                @Override
                protected void initChannel(SocketChannel ch) {
                    ch.pipeline().addLast(Http2FrameCodecBuilder.forClient().build());
                    ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                        @Override
                        protected void initChannel(Channel pushedStream) {
                            // MockServer does not push
                        }
                    }));
                }
            })
            .connect("127.0.0.1", mockServer.getLocalPort()).sync().channel();
    }

    private static List<String> exchange(Channel connection, Http2Headers requestHeaders, byte[] requestBody) throws Exception {
        List<String> frames = new ArrayList<>();
        CompletableFuture<List<String>> ended = new CompletableFuture<>();
        Http2StreamChannel stream = new Http2StreamChannelBootstrap(connection)
            .handler(new ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                    try {
                        if (msg instanceof Http2HeadersFrame) {
                            Http2HeadersFrame headers = (Http2HeadersFrame) msg;
                            frames.add(new WireTranscript()
                                .line("HEADERS endStream=" + headers.isEndStream() + " padding=" + headers.padding())
                                .headers(headers.headers()).toString().trim());
                            if (headers.isEndStream()) {
                                ended.complete(frames);
                            }
                        } else if (msg instanceof Http2DataFrame) {
                            Http2DataFrame data = (Http2DataFrame) msg;
                            byte[] bytes = ByteBufUtil.getBytes(data.content());
                            frames.add(new WireTranscript()
                                .line("DATA " + bytes.length + " endStream=" + data.isEndStream() + " padding=" + data.padding())
                                .bytes(bytes).toString().trim());
                            if (data.isEndStream()) {
                                ended.complete(frames);
                            }
                        } else if (msg instanceof Http2ResetFrame) {
                            frames.add("RST_STREAM " + ((Http2ResetFrame) msg).errorCode());
                            ended.complete(frames);
                        }
                    } finally {
                        ReferenceCountUtil.release(msg);
                    }
                }

                @Override
                public void userEventTriggered(ChannelHandlerContext ctx, Object evt) {
                    if (evt instanceof Http2ResetFrame) {
                        frames.add("RST_STREAM " + ((Http2ResetFrame) evt).errorCode());
                        ended.complete(frames);
                    }
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    ended.completeExceptionally(cause);
                }
            })
            .open().sync().getNow();
        stream.write(new DefaultHttp2HeadersFrame(requestHeaders, false));
        stream.writeAndFlush(new DefaultHttp2DataFrame(Unpooled.wrappedBuffer(requestBody), true)).sync();
        return ended.get(15, TimeUnit.SECONDS);
    }

    private byte[] greetingFrame(String method, String name) {
        return GrpcFrameCodec.encode(descriptorStore.getConverter().toProtobuf("{\"name\":\"" + name + "\"}", descriptorStore.getMethod(SERVICE, method).getInputType()));
    }
}

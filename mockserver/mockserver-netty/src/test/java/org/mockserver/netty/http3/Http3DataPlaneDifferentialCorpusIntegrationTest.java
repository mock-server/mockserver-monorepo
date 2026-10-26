package org.mockserver.netty.http3;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import io.netty.handler.codec.http3.Http3;
import io.netty.handler.codec.http3.Http3ClientConnectionHandler;
import io.netty.handler.codec.http3.Http3DataFrame;
import io.netty.handler.codec.http3.Http3Headers;
import io.netty.handler.codec.http3.Http3HeadersFrame;
import io.netty.handler.codec.http3.Http3RequestStreamInboundHandler;
import io.netty.handler.codec.quic.QuicChannel;
import io.netty.handler.codec.quic.QuicSslContext;
import io.netty.handler.codec.quic.QuicSslContextBuilder;
import io.netty.handler.codec.quic.QuicStreamChannel;
import io.netty.handler.codec.quic.QuicStreamResetException;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.GrpcStreamResponse;
import org.mockserver.netty.MockServer;
import org.mockserver.testing.corpus.CorpusLogEntries;
import org.mockserver.testing.corpus.CorpusNormaliser;
import org.mockserver.testing.corpus.GoldenCorpus;
import org.mockserver.testing.corpus.WireTranscript;
import org.mockserver.testing.socket.Ipv4DatagramChannelFactory;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
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
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;

/**
 * Differential corpus for HTTP/3, plain and gRPC: the response frames (each HEADERS frame's fields in order, each DATA
 * frame's bytes, the stream's end) and the serialised log entries of each scenario, compared byte for byte with the
 * goldens recorded from the baseline. See {@link GoldenCorpus} and {@link CorpusNormaliser}.
 * <p>
 * DATA boundaries are as Netty's HTTP/3 codec delivers them, and it can split a frame that spans QUIC packets, so keep
 * each scenario's DATA frames well under one packet (about 1 KB).
 * <p>
 * Needs the native QUIC transport and skips without it; CI fails closed on a skip (assert-suite-ran.sh).
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3DataPlaneDifferentialCorpusIntegrationTest {

    private static final Path DESCRIPTOR_DIR = Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath();
    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final GoldenCorpus GOLDEN = GoldenCorpus.forSuite(Http3DataPlaneDifferentialCorpusIntegrationTest.class);

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private CorpusLogEntries.Window log;
    private NioEventLoopGroup clientGroup;
    private GrpcProtoDescriptorStore descriptorStore;

    @Before
    public void startServer() {
        boolean available;
        try {
            available = Http3Server.isQuicAvailable();
        } catch (Throwable notLoaded) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available on this platform -- skipping HTTP/3 corpus", available);
        descriptorStore = new GrpcProtoDescriptorStore(new MockServerLogger());
        descriptorStore.loadDescriptorSetFromPath(DESCRIPTOR_DIR.resolve("greeting.dsc"));
        mockServer = startWithHttp3(udpPort -> new MockServer(withDataPlaneHooks(configuration()
            .logLevel("INFO")
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .attemptToProxyIfNoMatchingExpectation(false)
            .http3Port(udpPort)
            .http3MaxIdleTimeout(30000L)), 0));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        log = CorpusLogEntries.window(CorpusLogEntries.overHttp("http://127.0.0.1:" + mockServer.getLocalPort()));
        clientGroup = new NioEventLoopGroup(1);
    }

    /**
     * The server's configuration; a subclass loads extension hooks that change nothing (request filters or
     * response transformers), which must match the same goldens.
     */
    protected Configuration withDataPlaneHooks(Configuration configuration) {
        return configuration;
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
    public void mockedResponseKeepsHeaderOrderCaseAndNonAsciiBody() throws Exception {
        mockServerClient.upsert(when(request().withMethod("GET").withPath("/corpus/h3/ordered")).withId("corpus-h3-ordered").thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("X-Zulu", "z")
                .withHeader("x-alpha", "a")
                .withHeader("X-Multi", "one", "two")
                .withHeader("Content-Type", "text/plain; charset=utf-8")
                .withBody("héllo wörld ✓", StandardCharsets.UTF_8)
        ));

        record("mocked-ordered-headers", plainRequest("GET", "/corpus/h3/ordered"), null);
    }

    @Test
    public void mockedResponseWithRequestBodyAndResponseTrailers() throws Exception {
        mockServerClient.upsert(when(request().withMethod("POST").withPath("/corpus/h3/trailers").withBody("ping")).withId("corpus-h3-trailers").thenRespond(
            response()
                .withStatusCode(201)
                .withHeader("x-first", "1")
                .withTrailer("x-checksum", "abc=; q=1")
                .withTrailer("x-second-trailer", "2")
                .withBody("pong")
        ));

        record("mocked-trailers", plainRequest("POST", "/corpus/h3/trailers"), "ping".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    public void mockedBinaryBodyOfEveryByteValue() throws Exception {
        byte[] everyByte = new byte[256];
        for (int i = 0; i < everyByte.length; i++) {
            everyByte[i] = (byte) i;
        }
        mockServerClient.upsert(when(request().withMethod("GET").withPath("/corpus/h3/binary")).withId("corpus-h3-binary").thenRespond(
            response().withStatusCode(200).withHeader("Content-Type", "application/octet-stream").withBody(everyByte)
        ));

        record("mocked-binary", plainRequest("GET", "/corpus/h3/binary"), null);
    }

    @Test
    public void noMatchingExpectation() throws Exception {
        record("no-match-404", plainRequest("GET", "/corpus/h3/missing"), null);
    }

    @Test
    public void grpcUnary() throws Exception {
        mockServerClient.upsert(when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting")).withId("corpus-h3-grpc-unary").thenRespond(
            response()
                .withStatusCode(200)
                .withHeader("x-tenant-id", "acme")
                .withHeader("x-region", "eu-west-2")
                .withTrailer("x-request-cost", "unit=1; tier=gold")
                .withBody("{\"greeting\":\"Hello Wörld\"}")
        ));

        record("grpc-unary", grpcRequest("/" + SERVICE + "/Greeting"), greetingFrame("Greeting", "World"));
    }

    @Test
    public void grpcServerStream() throws Exception {
        mockServerClient.upsert(when(request().withMethod("POST").withPath("/" + SERVICE + "/ListGreetings")).withId("corpus-h3-grpc-stream")
            .thenRespondWithGrpcStream(GrpcStreamResponse.grpcStreamResponse()
                .withStatusName("OK")
                .withMessage("{\"greeting\": \"Hello 1\"}")
                .withMessage("{\"greeting\": \"Hello 2\"}")
                .withMessage("{\"greeting\": \"Hello 3\"}")));

        record("grpc-server-stream", grpcRequest("/" + SERVICE + "/ListGreetings"), greetingFrame("ListGreetings", "World"));
    }

    @Test
    public void grpcUnimplementedMethod() throws Exception {
        record("grpc-unimplemented", grpcRequest("/" + SERVICE + "/NoSuchMethod"), greetingFrame("Greeting", "World"));
    }

    private void record(String scenario, Http3Headers requestHeaders, byte[] requestBody) throws Exception {
        try (RecordingConnection connection = RecordingConnection.open(clientGroup, mockServer.getHttp3Port())) {
            WireTranscript transcript = new WireTranscript().section("HTTP/3 response frames");
            for (String frame : connection.exchange(requestHeaders, requestBody)) {
                transcript.line(frame);
            }
            CorpusLogEntries.appendTo(transcript, log.entries(GOLDEN.expectedLogEntries(scenario)));
            GOLDEN.assertMatches(scenario, new CorpusNormaliser()
                .ephemeralPort(mockServer.getLocalPort(), "<server-port>")
                .ephemeralPort(mockServer.getHttp3Port(), "<http3-port>")
                .ephemeralPort(connection.localPort(), "<client-port>")
                .normalise(transcript.toString()));
        }
    }

    private Http3Headers plainRequest(String method, String path) {
        Http3Headers headers = new DefaultHttp3Headers();
        headers.method(method).path(path).scheme("https").authority("localhost");
        headers.add("accept", "*/*");
        return headers;
    }

    private Http3Headers grpcRequest(String path) {
        Http3Headers headers = new DefaultHttp3Headers();
        headers.method("POST").path(path).scheme("https").authority("localhost");
        headers.add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        headers.add("te", "trailers");
        return headers;
    }

    private byte[] greetingFrame(String method, String name) {
        GrpcJsonMessageConverter converter = descriptorStore.getConverter();
        return GrpcFrameCodec.encode(converter.toProtobuf("{\"name\":\"" + name + "\"}", descriptorStore.getMethod(SERVICE, method).getInputType()));
    }

    /**
     * A QUIC connection whose request stream records each response frame as it arrives, so frame boundaries and the
     * order of fields within each HEADERS frame are part of the record.
     */
    private static final class RecordingConnection implements AutoCloseable {

        private final Channel datagramChannel;
        private final QuicChannel quicChannel;

        private RecordingConnection(Channel datagramChannel, QuicChannel quicChannel) {
            this.datagramChannel = datagramChannel;
            this.quicChannel = quicChannel;
        }

        static RecordingConnection open(NioEventLoopGroup group, int http3Port) throws Exception {
            QuicSslContext sslContext = QuicSslContextBuilder.forClient()
                .trustManager(InsecureTrustManagerFactory.INSTANCE)
                .applicationProtocols(Http3.supportedApplicationProtocols())
                .build();
            Channel datagramChannel = new Bootstrap()
                .group(group)
                .channelFactory(Ipv4DatagramChannelFactory.INSTANCE)
                .handler(Http3.newQuicClientCodecBuilder()
                    .sslContext(sslContext)
                    .maxIdleTimeout(30000, TimeUnit.MILLISECONDS)
                    .initialMaxData(10000000)
                    .initialMaxStreamDataBidirectionalLocal(1000000)
                    .initialMaxStreamsBidirectional(100)
                    .build())
                .bind(0)
                .sync()
                .channel();
            try {
                QuicChannel quicChannel = QuicChannel.newBootstrap(datagramChannel)
                    .handler(new ChannelInitializer<QuicChannel>() {
                        @Override
                        protected void initChannel(QuicChannel ch) {
                            ch.pipeline().addLast(new Http3ClientConnectionHandler());
                        }
                    })
                    .remoteAddress(new InetSocketAddress("127.0.0.1", http3Port))
                    .connect()
                    .get(15, TimeUnit.SECONDS);
                return new RecordingConnection(datagramChannel, quicChannel);
            } catch (Exception notConnected) {
                datagramChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
                throw notConnected;
            }
        }

        int localPort() {
            return ((InetSocketAddress) datagramChannel.localAddress()).getPort();
        }

        List<String> exchange(Http3Headers requestHeaders, byte[] requestBody) throws Exception {
            List<String> frames = new ArrayList<>();
            CompletableFuture<List<String>> ended = new CompletableFuture<>();
            QuicStreamChannel stream = Http3.newRequestStream(quicChannel, new Http3RequestStreamInboundHandler() {
                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3HeadersFrame frame) {
                    WireTranscript headers = new WireTranscript().line("HEADERS").headers(frame.headers());
                    frames.add(headers.toString().trim());
                }

                @Override
                protected void channelRead(ChannelHandlerContext ctx, Http3DataFrame frame) {
                    byte[] bytes = ByteBufUtil.getBytes(frame.content());
                    frame.release();
                    frames.add(new WireTranscript().line("DATA " + bytes.length).bytes(bytes).toString().trim());
                }

                @Override
                protected void channelInputClosed(ChannelHandlerContext ctx) {
                    frames.add("FIN");
                    ended.complete(frames);
                    ctx.close();
                }

                @Override
                public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
                    if (cause instanceof QuicStreamResetException) {
                        frames.add("RESET " + ((QuicStreamResetException) cause).applicationProtocolCode());
                        ended.complete(frames);
                    } else {
                        ended.completeExceptionally(cause);
                    }
                    ctx.close();
                }
            }).sync().getNow();
            if (requestBody == null) {
                stream.writeAndFlush(new DefaultHttp3HeadersFrame(requestHeaders)).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            } else {
                stream.write(new DefaultHttp3HeadersFrame(requestHeaders));
                stream.writeAndFlush(new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(requestBody))).addListener(QuicStreamChannel.SHUTDOWN_OUTPUT);
            }
            return ended.get(15, TimeUnit.SECONDS);
        }

        @Override
        public void close() {
            quicChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
            datagramChannel.close().awaitUninterruptibly(5, TimeUnit.SECONDS);
        }
    }
}

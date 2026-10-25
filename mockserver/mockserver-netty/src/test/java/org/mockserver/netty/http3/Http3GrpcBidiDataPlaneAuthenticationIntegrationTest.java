package org.mockserver.netty.http3;

import com.google.protobuf.Descriptors;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3Headers;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.Times;
import org.mockserver.model.GrpcBidiResponse;
import org.mockserver.model.GrpcBidiRule;
import org.mockserver.netty.MockServer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A true gRPC bidi stream over HTTP/3 is matched when its HEADERS arrive, before the data-plane authentication gate
 * runs. With {@code dataPlaneAuthenticationRequired} on, a stream without credentials must be refused, and must not
 * use up the expectation it would have matched.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3GrpcBidiDataPlaneAuthenticationIntegrationTest {

    private static final String BEARER_TOKEN = "bidi-token";
    private static final Path DESCRIPTOR_DIR = Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath();
    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String CHAT = "/" + SERVICE + "/Chat";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static NioEventLoopGroup clientGroup;
    private static Descriptors.MethodDescriptor chatMethod;
    private static GrpcJsonMessageConverter converter;

    @BeforeClass
    public static void startServer() {
        assumeQuicAvailable();
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(DESCRIPTOR_DIR.resolve("greeting.dsc"));
        chatMethod = store.getMethod(SERVICE, "Chat");
        converter = store.getConverter();
        mockServer = startWithHttp3(configuration()
            .http3MaxIdleTimeout(30000L)
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .grpcBidiStreamingEnabled(true)
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken(BEARER_TOKEN));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        mockServerClient.when(request().withPath(CHAT), Times.once()).respondWithGrpcBidi(
            GrpcBidiResponse.grpcBidiResponse()
                .withStatusName("OK")
                .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*").withResponse("{\"greeting\": \"Hello Alice\"}"))
        );
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldRefuseBidiStreamWithoutCredentialsAndLeaveTheExpectationUnused() throws Exception {
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange refused = connection.start(chatHeaders()).data(alice()).end();
            refused.body();
            String grpcStatus = refused.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER) != null
                ? refused.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString()
                : refused.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER);
            assertThat(grpcStatus, is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNAUTHENTICATED.getCode())));
            assertThat("no mocked message", greetings(refused), is(empty()));
        }

        // the Times.once expectation is still there for a client with credentials
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange chat = connection.start(chatHeaders().set("authorization", "Bearer " + BEARER_TOKEN))
                .data(alice()).end();
            assertThat(chat.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
            assertThat(greetings(chat), contains("Hello Alice"));
        }
    }

    private static Http3Headers chatHeaders() {
        return new DefaultHttp3Headers()
            .method(HttpMethod.POST.asciiName())
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path(CHAT)
            .set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .set("te", "trailers");
    }

    private static byte[] alice() {
        return GrpcFrameCodec.encode(converter.toProtobuf("{\"name\": \"Alice\"}", chatMethod.getInputType()));
    }

    private static List<String> greetings(Http3TestClient.Exchange exchange) {
        return GrpcFrameCodec.decode(exchange.receivedByteArray()).stream()
            .map(message -> converter.toJson(message, chatMethod.getOutputType()))
            .map(json -> json.replaceAll("(?s).*\"greeting\":\\s*\"([^\"]*)\".*", "$1"))
            .collect(Collectors.toList());
    }

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue("native QUIC transport not available on this platform", io.netty.handler.codec.quic.Quic.isAvailable());
        } catch (Throwable t) {
            Assume.assumeNoException("native QUIC transport failed to load", t);
        }
    }
}

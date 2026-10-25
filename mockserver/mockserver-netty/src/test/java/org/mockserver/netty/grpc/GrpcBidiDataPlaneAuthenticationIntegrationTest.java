package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
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
import org.mockserver.netty.integration.Http2TestClient;

import java.nio.file.Files;
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
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * A true gRPC bidi stream over HTTP/2 is matched in {@code GrpcBidiRouterHandler}, ahead of the handler that runs the
 * data-plane authentication gate. With {@code dataPlaneAuthenticationRequired} on, a stream without credentials must
 * be refused, and must not use up the expectation it would have matched.
 */
@RunWith(Parameterized.class)
public class GrpcBidiDataPlaneAuthenticationIntegrationTest {

    private static final String BEARER_TOKEN = "bidi-token";
    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String CHAT = "/" + SERVICE + "/Chat";
    private static final Path DESCRIPTOR = Paths.get("../mockserver-core/src/test/resources/grpc/greeting.dsc");

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static EventLoopGroup clientGroup;
    private static Descriptors.MethodDescriptor chatMethod;
    private static GrpcJsonMessageConverter converter;

    @Parameterized.Parameters(name = "tls={0}")
    public static Object[] routes() {
        return new Object[]{false, true};
    }

    @Parameterized.Parameter
    public boolean tls;

    @BeforeClass
    public static void startServer() throws Exception {
        GrpcProtoDescriptorStore store = new GrpcProtoDescriptorStore(new MockServerLogger());
        store.loadDescriptorSetFromPath(DESCRIPTOR);
        chatMethod = store.getMethod(SERVICE, "Chat");
        converter = store.getConverter();
        mockServer = new MockServer(configuration()
            .grpcBidiStreamingEnabled(true)
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken(BEARER_TOKEN), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        mockServerClient.uploadGrpcDescriptor(Files.readAllBytes(DESCRIPTOR));
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

    @Before
    public void resetServer() {
        mockServerClient.reset();
        mockServerClient.when(request().withPath(CHAT), Times.once()).respondWithGrpcBidi(
            GrpcBidiResponse.grpcBidiResponse()
                .withStatusName("OK")
                .withRule(GrpcBidiRule.grpcBidiRule(".*Alice.*").withResponse("{\"greeting\": \"Hello Alice\"}"))
        );
    }

    @Test
    public void shouldRefuseBidiStreamWithoutCredentialsAndLeaveTheExpectationUnused() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange refused = client.send(chatHeaders(), false).data(message("Alice"), true);
            refused.body();
            String grpcStatus = refused.header(GrpcStatusMapper.GRPC_STATUS_HEADER);
            if (grpcStatus == null) {
                grpcStatus = refused.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER);
            }
            assertThat(grpcStatus, is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNAUTHENTICATED.getCode())));
            assertThat("no mocked message", greetings(refused), is(empty()));
        }

        // the Times.once expectation is still there for a client with credentials
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange chat = client.send(chatHeaders().set("authorization", "Bearer " + BEARER_TOKEN), false)
                .data(message("Alice"), true);
            assertThat(chat.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
            assertThat(greetings(chat), contains("Hello Alice"));
        }
    }

    private Http2TestClient connect() throws Exception {
        return tls ? Http2TestClient.tls(clientGroup, mockServer.getLocalPort()) : Http2TestClient.h2c(clientGroup, mockServer.getLocalPort());
    }

    private Http2Headers chatHeaders() {
        return new DefaultHttp2Headers()
            .method("POST")
            .scheme(tls ? "https" : "http")
            .authority("127.0.0.1:" + mockServer.getLocalPort())
            .path(CHAT)
            .set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .set("te", "trailers");
    }

    private static byte[] message(String name) {
        return GrpcFrameCodec.encode(converter.toProtobuf("{\"name\": \"" + name + "\"}", chatMethod.getInputType()));
    }

    private static List<String> greetings(Http2TestClient.Exchange exchange) {
        return GrpcFrameCodec.decode(exchange.receivedByteArray()).stream()
            .map(message -> converter.toJson(message, chatMethod.getOutputType()))
            .map(json -> json.replaceAll("(?s).*\"greeting\":\\s*\"([^\"]*)\".*", "$1"))
            .collect(Collectors.toList());
    }
}

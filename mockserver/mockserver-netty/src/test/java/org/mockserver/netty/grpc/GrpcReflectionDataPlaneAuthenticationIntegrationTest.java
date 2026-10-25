package org.mockserver.netty.grpc;

import com.google.protobuf.CodedOutputStream;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.mockserver.client.MockServerClient;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * gRPC Server Reflection lists the loaded proto schemas, so with {@code dataPlaneAuthenticationRequired} on it must
 * refuse a client without credentials, whether it is served by the unary handler or, with bidi streaming on, by the
 * streaming handler installed at HEADERS time. The gRPC health service stays open, like {@code /mockserver/status}.
 */
@RunWith(Parameterized.class)
public class GrpcReflectionDataPlaneAuthenticationIntegrationTest {

    private static final String BEARER_TOKEN = "reflection-token";
    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String REFLECTION = "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
    private static final String HEALTH = "/grpc.health.v1.Health/Check";
    private static final String UNAUTHENTICATED = String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNAUTHENTICATED.getCode());

    private static MockServer unaryServer;
    private static MockServer bidiServer;
    private static EventLoopGroup clientGroup;

    @Parameterized.Parameters(name = "bidiStreaming={0}, tls={1}")
    public static Object[][] routes() {
        return new Object[][]{{false, false}, {false, true}, {true, false}, {true, true}};
    }

    @Parameterized.Parameter
    public boolean bidiStreaming;

    @Parameterized.Parameter(1)
    public boolean tls;

    @BeforeClass
    public static void startServers() {
        unaryServer = startServer(false);
        bidiServer = startServer(true);
        clientGroup = new NioEventLoopGroup(2);
    }

    private static MockServer startServer(boolean bidiStreaming) {
        return new MockServer(configuration()
            .grpcDescriptorDirectory(Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath().toString())
            .grpcBidiStreamingEnabled(bidiStreaming)
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken(BEARER_TOKEN), 0);
    }

    @AfterClass
    public static void stopServers() {
        stopQuietly(unaryServer);
        stopQuietly(bidiServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldRefuseReflectionWithoutCredentials() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange refused = client.send(grpcHeaders(REFLECTION), false).data(listServicesRequest(), true);
            refused.body();
            assertThat(grpcStatus(refused), is(UNAUTHENTICATED));
            assertThat("no schema disclosed", received(refused), not(containsString(SERVICE)));
        }
    }

    @Test
    public void shouldServeReflectionWithCredentials() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange reflection = client.send(grpcHeaders(REFLECTION).set("authorization", "Bearer " + BEARER_TOKEN), false)
                .data(listServicesRequest(), true);
            reflection.body();
            assertThat(grpcStatus(reflection), is("0"));
            assertThat(received(reflection), containsString(SERVICE));
        }
    }

    @Test
    public void shouldRefuseUnknownMethodWithoutCredentialsRatherThanSayItIsUnimplemented() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange refused = client.send(grpcHeaders("/" + SERVICE + "/NoSuchMethod"), false)
                .data(GrpcFrameCodec.encode(new byte[0]), true);
            refused.body();
            assertThat(grpcStatus(refused), is(UNAUTHENTICATED));
        }
    }

    @Test
    public void shouldAnswerHealthCheckWithoutCredentials() throws Exception {
        try (Http2TestClient client = connect()) {
            Http2TestClient.Exchange health = client.send(grpcHeaders(HEALTH), false).data(GrpcFrameCodec.encode(new byte[0]), true);
            health.body();
            assertThat(grpcStatus(health), is("0"));
        }
    }

    private Http2TestClient connect() throws Exception {
        int port = (bidiStreaming ? bidiServer : unaryServer).getLocalPort();
        return tls ? Http2TestClient.tls(clientGroup, port) : Http2TestClient.h2c(clientGroup, port);
    }

    private Http2Headers grpcHeaders(String path) {
        int port = (bidiStreaming ? bidiServer : unaryServer).getLocalPort();
        return new DefaultHttp2Headers()
            .method("POST")
            .scheme(tls ? "https" : "http")
            .authority("127.0.0.1:" + port)
            .path(path)
            .set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .set("te", "trailers");
    }

    private static String grpcStatus(Http2TestClient.Exchange exchange) throws Exception {
        String grpcStatus = exchange.header(GrpcStatusMapper.GRPC_STATUS_HEADER);
        return grpcStatus != null ? grpcStatus : exchange.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER);
    }

    private static String received(Http2TestClient.Exchange exchange) {
        return new String(exchange.receivedByteArray(), StandardCharsets.ISO_8859_1);
    }

    private static byte[] listServicesRequest() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        // ServerReflectionRequest field 7: list_services
        output.writeString(7, "*");
        output.flush();
        return GrpcFrameCodec.encode(bytes.toByteArray());
    }
}

package org.mockserver.netty.http3;

import com.google.protobuf.CodedOutputStream;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3Headers;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.netty.MockServer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Paths;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * HTTP/3 does not serve gRPC Server Reflection: it answers {@code UNIMPLEMENTED}, as for any method missing from the
 * loaded descriptors. With {@code dataPlaneAuthenticationRequired} on, a client without credentials must get
 * {@code UNAUTHENTICATED} instead, so it cannot learn from the answer which methods the loaded schemas define.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3GrpcReflectionDataPlaneAuthenticationIntegrationTest {

    private static final String BEARER_TOKEN = "reflection-token";
    private static final String REFLECTION = "/grpc.reflection.v1.ServerReflection/ServerReflectionInfo";
    private static final String UNAUTHENTICATED = String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNAUTHENTICATED.getCode());
    private static final String UNIMPLEMENTED = String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED.getCode());

    private static MockServer mockServer;
    private static NioEventLoopGroup clientGroup;

    @BeforeClass
    public static void startServer() {
        assumeQuicAvailable();
        mockServer = startWithHttp3(configuration()
            .http3MaxIdleTimeout(30000L)
            .grpcDescriptorDirectory(Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath().toString())
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken(BEARER_TOKEN));
        clientGroup = new NioEventLoopGroup(2);
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldRefuseReflectionWithoutCredentials() throws Exception {
        assertThat(grpcStatus(REFLECTION, null), is(UNAUTHENTICATED));
    }

    @Test
    public void shouldRefuseUnknownMethodWithoutCredentials() throws Exception {
        assertThat(grpcStatus("/com.example.grpc.GreetingService/NoSuchMethod", null), is(UNAUTHENTICATED));
    }

    @Test
    public void shouldAnswerReflectionUnimplementedWithCredentials() throws Exception {
        assertThat(grpcStatus(REFLECTION, "Bearer " + BEARER_TOKEN), is(UNIMPLEMENTED));
    }

    private static String grpcStatus(String path, String authorization) throws Exception {
        Http3Headers headers = new DefaultHttp3Headers()
            .method(HttpMethod.POST.asciiName())
            .scheme("https")
            .authority("127.0.0.1:" + mockServer.getHttp3Port())
            .path(path)
            .set("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .set("te", "trailers");
        if (authorization != null) {
            headers.set("authorization", authorization);
        }
        try (Http3TestClient connection = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange exchange = connection.start(headers).data(listServicesRequest()).end();
            exchange.body();
            CharSequence inHeaders = exchange.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER);
            return inHeaders != null ? inHeaders.toString() : exchange.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER);
        }
    }

    private static byte[] listServicesRequest() throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(bytes);
        // ServerReflectionRequest field 7: list_services
        output.writeString(7, "*");
        output.flush();
        return GrpcFrameCodec.encode(bytes.toByteArray());
    }

    private static void assumeQuicAvailable() {
        try {
            Assume.assumeTrue("native QUIC transport not available on this platform", io.netty.handler.codec.quic.Quic.isAvailable());
        } catch (Throwable t) {
            Assume.assumeNoException("native QUIC transport failed to load", t);
        }
    }
}

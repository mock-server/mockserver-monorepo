package org.mockserver.netty.http3;

import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http3.DefaultHttp3Headers;
import io.netty.handler.codec.http3.Http3Headers;
import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.extension.ResponseTransformer;
import org.mockserver.extension.TransformContext;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.MockServer;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.netty.http3.Http3TestServer.startWithHttp3;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * An extension response transformer on HTTP/3: it changes a mocked response and MockServer's own {@code 404}, a
 * transformer that runs out of its budget is answered {@code 500} once, it sees a gRPC response as JSON before it is
 * framed as protobuf, and an elapsed {@code grpc-timeout} wins over a response it is still working on. Needs the
 * native QUIC transport and skips without it.
 */
@SuppressWarnings("deprecation") // NioEventLoopGroup deprecation in Netty 4.2
public class Http3ResponseTransformerIntegrationTest {

    private static final Path DESCRIPTOR_DIR = Paths.get("../mockserver-core/src/test/resources/grpc").toAbsolutePath();
    private static final String SERVICE = "com.example.grpc.GreetingService";

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private NioEventLoopGroup clientGroup;
    private GrpcProtoDescriptorStore descriptorStore;

    public static class Transformer implements ResponseTransformer {
        @Override
        public String name() {
            return "h3-transformer";
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception {
            String path = request.getPath().getValue();
            String body = request.getBodyAsString();
            if (path.equals("/h3/slow") || body != null && body.contains("Slow")) {
                Thread.sleep(path.equals("/h3/slow") ? 5_000 : 1_500);
            }
            if (context.isGrpc()) {
                return response.withBody("{\"greeting\":\"transformed over HTTP/3\"}");
            }
            return response.withHeader("x-transformed", context.isGenerated() ? "generated" : "mocked");
        }
    }

    @Before
    public void startServer() {
        boolean available;
        try {
            available = Http3Server.isQuicAvailable();
        } catch (Throwable notLoaded) {
            available = false;
        }
        Assume.assumeTrue("native QUIC transport not available on this platform -- skipping", available);
        descriptorStore = new GrpcProtoDescriptorStore(new MockServerLogger());
        descriptorStore.loadDescriptorSetFromPath(DESCRIPTOR_DIR.resolve("greeting.dsc"));
        mockServer = startWithHttp3(configuration()
            .grpcDescriptorDirectory(DESCRIPTOR_DIR.toString())
            .attemptToProxyIfNoMatchingExpectation(false)
            .http3MaxIdleTimeout(30000L)
            .extensionDataPlaneTimeoutMillis(3_000L)
            .extensions(Transformer.class));
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        clientGroup = new NioEventLoopGroup(1);
    }

    @After
    public void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).awaitUninterruptibly(10, TimeUnit.SECONDS);
        }
    }

    @Test(timeout = 60_000)
    public void shouldTransformAMockedResponseAndTheGeneratedNotFound() throws Exception {
        mockServerClient.when(request("/h3/mocked")).respond(response("mocked body"));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange mocked = client.send(plainRequest("/h3/mocked"));
            assertThat(mocked.status(), is(200));
            assertThat(mocked.body(), is("mocked body"));
            assertThat(mocked.headers().get("x-transformed").toString(), is("mocked"));

            Http3TestClient.Exchange notFound = client.send(plainRequest("/h3/missing"));
            assertThat(notFound.status(), is(404));
            assertThat(notFound.headers().get("x-transformed").toString(), is("generated"));
        }
    }

    @Test(timeout = 60_000)
    public void shouldAnswerATransformerThatRunsOutOfItsBudgetWithOneFailureResponse() throws Exception {
        mockServerClient.when(request("/h3/slow")).respond(response("never sent"));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange slow = client.send(plainRequest("/h3/slow"));
            assertThat(slow.status(), is(500));
            assertThat(slow.body(), not(containsString("never sent")));
            assertThat(slow.headers().contains("x-transformed"), is(false));

            // the connection keeps serving
            mockServerClient.when(request("/h3/after")).respond(response("after"));
            assertThat(client.send(plainRequest("/h3/after")).body(), is("after"));
        }
    }

    @Test(timeout = 60_000)
    public void shouldTransformAGrpcResponseBeforeItIsFramed() throws Exception {
        mockServerClient.when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting").withBody(json("{\"name\":\"World\"}")))
            .respond(response().withStatusCode(200).withBody("{\"greeting\":\"Hello World\"}"));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange exchange = client.start(grpcRequest("10S")).data(greetingFrame("World")).end();
            exchange.body();

            assertThat(exchange.trailer(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
            List<byte[]> messages = GrpcFrameCodec.decode(exchange.receivedByteArray());
            assertThat(messages.size(), is(1));
            GrpcJsonMessageConverter converter = descriptorStore.getConverter();
            assertThat(converter.toJson(messages.get(0), descriptorStore.getMethod(SERVICE, "Greeting").getOutputType()), containsString("transformed over HTTP/3"));
        }
    }

    @Test(timeout = 60_000)
    public void shouldLetTheGrpcDeadlineWinOverAResponseTheTransformerIsStillWorkingOn() throws Exception {
        mockServerClient.when(request().withMethod("POST").withPath("/" + SERVICE + "/Greeting").withBody(json("{\"name\":\"Slow\"}")))
            .respond(response().withStatusCode(200).withBody("{\"greeting\":\"Hello Slow\"}"));

        try (Http3TestClient client = Http3TestClient.open(clientGroup, mockServer)) {
            Http3TestClient.Exchange exchange = client.start(grpcRequest("300m")).data(greetingFrame("Slow")).end();
            exchange.body();

            assertThat("trailers-only: one terminal HEADERS frame", exchange.receivedTrailers(), is(false));
            assertThat(exchange.headers().get(GrpcStatusMapper.GRPC_STATUS_HEADER).toString(), is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode())));
            assertThat(exchange.receivedByteArray().length, is(0));

            // after the transformer finishes, its response is dropped and the connection keeps serving
            Thread.sleep(1_500);
            mockServerClient.when(request("/h3/after-deadline")).respond(response("after"));
            assertThat(client.send(plainRequest("/h3/after-deadline")).body(), is("after"));
        }
    }

    private Http3Headers plainRequest(String path) {
        Http3Headers headers = new DefaultHttp3Headers();
        headers.method("GET").path(path).scheme("https").authority("127.0.0.1:" + mockServer.getHttp3Port());
        return headers;
    }

    private Http3Headers grpcRequest(String grpcTimeout) {
        Http3Headers headers = new DefaultHttp3Headers();
        headers.method("POST").path("/" + SERVICE + "/Greeting").scheme("https").authority("127.0.0.1:" + mockServer.getHttp3Port());
        headers.add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        headers.add("te", "trailers");
        headers.add("grpc-timeout", grpcTimeout);
        return headers;
    }

    private byte[] greetingFrame(String name) {
        GrpcJsonMessageConverter converter = descriptorStore.getConverter();
        return GrpcFrameCodec.encode(converter.toProtobuf("{\"name\":\"" + name + "\"}", descriptorStore.getMethod(SERVICE, "Greeting").getInputType()));
    }
}

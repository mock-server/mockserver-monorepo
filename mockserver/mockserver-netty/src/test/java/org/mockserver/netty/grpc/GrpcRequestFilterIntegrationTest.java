package org.mockserver.netty.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.Http2Headers;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.integration.Http2TestClient;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * The extension request filter on gRPC unary calls over HTTP/2: the {@code grpc-timeout} deadline still ends a call
 * whose filter is slow, the response matching writes later is dropped rather than written after the deadline's, and a
 * filter may not change which method a call is for.
 */
public class GrpcRequestFilterIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String METHOD = "Greeting";
    private static final String GREETING_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";
    private static final long SLOW_FILTER_MILLIS = 1_500;

    private final GrpcFilter filter = new GrpcFilter();
    private EventLoopGroup clientGroup;
    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private ManagedChannel channel;
    private Descriptors.Descriptor requestType;
    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod;

    @Before
    public void setUp() throws Exception {
        byte[] descriptorBytes = Files.readAllBytes(Paths.get(GREETING_DESCRIPTOR));
        Descriptors.MethodDescriptor greeting = greetingMethod(descriptorBytes);
        requestType = greeting.getInputType();
        grpcMethod = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, METHOD))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(greeting.getInputType())))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(greeting.getOutputType())))
            .build();

        mockServer = new MockServer(configuration()
            .logLevel("INFO")
            .extensionDataPlaneTimeoutMillis(10_000L)
            .extensions(() -> filter), 0);
        mockServerClient = new MockServerClient("127.0.0.1", mockServer.getLocalPort());
        mockServerClient.uploadGrpcDescriptor(descriptorBytes);
        mockServerClient
            .when(request().withPath("/" + SERVICE + "/" + METHOD))
            .respond(response().withStatusCode(200).withBody("{\"greeting\":\"Hello World\"}"));
        clientGroup = new NioEventLoopGroup(1);
        channel = NettyChannelBuilder.forAddress("127.0.0.1", mockServer.getLocalPort()).usePlaintext().build();
    }

    @After
    public void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(10, TimeUnit.SECONDS);
        }
        if (clientGroup != null) {
            clientGroup.shutdownGracefully(0, 1, TimeUnit.SECONDS).syncUninterruptibly();
        }
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    /**
     * Over a raw HTTP/2 stream, so the client does not cancel the call at its deadline itself, as grpc-java does: the
     * server must end it while the filter still runs, and the response matching writes once the slow filter lets the
     * request through must not reach the client.
     */
    @Test
    public void shouldEndTheCallAtItsDeadlineAndDropTheResponseWrittenAfterTheSlowFilter() throws Exception {
        filter.delayMillis = SLOW_FILTER_MILLIS;
        Http2Headers headers = new DefaultHttp2Headers()
            .method("POST").scheme("http").authority("127.0.0.1:" + mockServer.getLocalPort()).path("/" + SERVICE + "/" + METHOD)
            .add("content-type", "application/grpc")
            .add("te", "trailers")
            .add("grpc-timeout", "300m");
        // one length-prefixed, uncompressed message: HelloRequest { name: "World" }
        byte[] message = {0, 0, 0, 0, 7, 0x0A, 5, 'W', 'o', 'r', 'l', 'd'};

        try (Http2TestClient client = Http2TestClient.h2c(clientGroup, mockServer.getLocalPort())) {
            long start = System.nanoTime();
            Http2TestClient.Exchange exchange = client.send(headers, false);
            client.sendData(exchange, message, true);

            assertThat(exchange.status(), is(200));
            exchange.body();
            long endedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
            Http2Headers trailers = exchange.trailers() != null ? exchange.trailers() : exchange.headers();
            assertThat(String.valueOf(trailers.get("grpc-status")), is(String.valueOf(Status.Code.DEADLINE_EXCEEDED.value())));
            assertThat("ended at the deadline, while the filter was still running", endedMillis, lessThan(SLOW_FILTER_MILLIS));

            // the stream closed with the deadline's trailers, so the response matching writes once the filter lets
            // the request through goes nowhere: the connection carries on and the stream got nothing more
            assertThat(filter.finished.await(10, TimeUnit.SECONDS), is(true));
            awaitLogContaining("returning response:");
            String receivedAtTheDeadline = exchange.received();
            filter.delayMillis = 0;
            Http2TestClient.Exchange next = client.send(headers.set("grpc-timeout", "10S"), false);
            client.sendData(next, message, true);
            assertThat(next.status(), is(200));
            next.body();
            Http2Headers nextTrailers = next.trailers() != null ? next.trailers() : next.headers();
            assertThat(String.valueOf(nextTrailers.get("grpc-status")), is(String.valueOf(Status.Code.OK.value())));
            assertThat(client.isOpen(), is(true));
            assertThat(exchange.received(), is(receivedAtTheDeadline));
        }
        assertThat(filter.grpc, contains(true, true));
    }

    @Test
    public void shouldRefuseAFilterThatChangesTheMethodOfAGrpcCall() throws Exception {
        filter.newPath = "/" + SERVICE + "/Other";

        StatusRuntimeException exception = call(CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS));

        assertThat(exception.getStatus().getCode(), not(is(Status.Code.OK)));
        awaitLogContaining("contract_violation");
    }

    @Test
    public void shouldRefuseAFilterThatChangesOnlyTheGrpcMethodHeader() throws Exception {
        filter.newGrpcMethod = "Other";

        StatusRuntimeException exception = call(CallOptions.DEFAULT.withDeadlineAfter(10, TimeUnit.SECONDS));

        assertThat(exception.getStatus().getCode(), not(is(Status.Code.OK)));
        awaitLogContaining("contract_violation");
    }

    private StatusRuntimeException call(CallOptions callOptions) {
        try {
            ClientCalls.blockingUnaryCall(channel, grpcMethod, callOptions,
                DynamicMessage.newBuilder(requestType).setField(requestType.findFieldByName("name"), "World").build());
        } catch (StatusRuntimeException expected) {
            return expected;
        }
        throw new AssertionError("expected the call to fail");
    }

    private void awaitLogContaining(String text) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        List<String> entries = List.of();
        while (System.nanoTime() < deadline) {
            entries = Arrays.asList(mockServerClient.retrieveLogMessagesArray(null));
            if (entries.stream().anyMatch(entry -> entry.contains(text))) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
        throw new AssertionError("no log entry contains \"" + text + "\": " + entries);
    }

    private static Descriptors.MethodDescriptor greetingMethod(byte[] descriptorBytes) throws Exception {
        for (DescriptorProtos.FileDescriptorProto file : DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes).getFileList()) {
            for (Descriptors.ServiceDescriptor service : Descriptors.FileDescriptor.buildFrom(file, new Descriptors.FileDescriptor[0]).getServices()) {
                if (service.getFullName().equals(SERVICE)) {
                    return service.findMethodByName(METHOD);
                }
            }
        }
        throw new IllegalStateException("no " + SERVICE + "/" + METHOD + " in " + GREETING_DESCRIPTOR);
    }

    static class GrpcFilter implements RequestFilter {
        volatile long delayMillis;
        volatile String newPath;
        volatile String newGrpcMethod;
        final List<Boolean> grpc = new CopyOnWriteArrayList<>();
        final CountDownLatch finished = new CountDownLatch(1);

        @Override
        public String name() {
            return "grpc-filter";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            try {
                grpc.add(context.isGrpc());
                if (delayMillis > 0) {
                    Thread.sleep(delayMillis);
                }
                if (newGrpcMethod != null) {
                    // the path is left as it is, so only the header rule can refuse this
                    return FilterResult.proceedWith(request.removeHeader("x-grpc-method").withHeader("x-grpc-method", newGrpcMethod));
                }
                return newPath != null ? FilterResult.proceedWith(request.withPath(newPath)) : FilterResult.proceed();
            } finally {
                finished.countDown();
            }
        }
    }
}

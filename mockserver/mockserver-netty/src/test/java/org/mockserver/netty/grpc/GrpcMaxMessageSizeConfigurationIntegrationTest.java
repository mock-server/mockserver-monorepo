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
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.configuration.Configuration;
import org.mockserver.netty.MockServer;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Regression test for issue #2669: the {@code maxGrpcMessageSize} limit set on a
 * {@link Configuration} <strong>instance</strong> must be honoured for gRPC over HTTP/2.
 * <p>
 * <strong>Why this test exists.</strong> gRPC is always HTTP/2, and with the default
 * {@code grpcBidiStreamingEnabled=false} every stream takes the re-aggregating chain installed by
 * {@code Http2MultiplexChildInitializer}. That initialiser builds the shared
 * {@code GrpcToHttpRequestHandler}; if it is constructed without the live {@link Configuration}, the
 * handler's {@code configuration} field is {@code null} and {@code GrpcFrameCodec.maxMessageSize}
 * silently falls back to the static {@code ConfigurationProperties} store — so a limit set on a
 * {@code Configuration} instance (the standard embedding pattern, {@code new MockServer(
 * configuration().maxGrpcMessageSize(X))}) or via {@code PUT /mockserver/config} would be silently
 * ignored on the DEFAULT gRPC path. A codec-level unit test never stands up the HTTP/2 pipeline and
 * therefore cannot pin this wiring; this test drives a real grpc-java client through the real
 * server.
 * <p>
 * <strong>What makes it a genuine regression proof.</strong> The instance limit
 * ({@value #INSTANCE_MAX_MESSAGE_SIZE} bytes) is deliberately far below the static default (4 MiB),
 * and an expectation is registered that would answer this request with {@code OK}. A request message
 * larger than the instance limit but well under the static default therefore diverges:
 * <ul>
 *   <li>with the fix — the instance limit is enforced and the client sees
 *       {@code RESOURCE_EXHAUSTED} before matching ever runs;</li>
 *   <li>with the bug — the null configuration falls back to the 4 MiB static default, the message is
 *       accepted, the expectation matches and the client sees {@code OK}.</li>
 * </ul>
 * The two outcomes are unambiguous, so the test fails loudly if the {@code configuration} argument
 * is ever dropped again. The limit is set ONLY on the {@code Configuration} instance, never via the
 * static {@code ConfigurationProperties}, which is the entire point.
 */
public class GrpcMaxMessageSizeConfigurationIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String METHOD = "Greeting";
    private static final String GREETING_DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    /**
     * Instance-configured receive limit. Far below the 4 MiB static default so the two cannot be
     * confused, and not a round power-of-two matching any default. A gRPC message larger than this
     * but smaller than 4 MiB is accepted iff the code wrongly reads the static store.
     */
    private static final int INSTANCE_MAX_MESSAGE_SIZE = 1000;

    private MockServer mockServer;
    private MockServerClient mockServerClient;
    private ManagedChannel channel;

    private final Map<String, Descriptors.ServiceDescriptor> services = new LinkedHashMap<>();
    private Descriptors.Descriptor requestType;
    private Descriptors.Descriptor responseType;
    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod;

    @Before
    public void setUp() throws Exception {
        byte[] greetingDescriptorBytes = Files.readAllBytes(Paths.get(GREETING_DESCRIPTOR));
        registerServices(greetingDescriptorBytes);

        Descriptors.MethodDescriptor greeting = method(SERVICE, METHOD);
        requestType = greeting.getInputType();
        responseType = greeting.getOutputType();

        // The limit lives ONLY on this Configuration instance -- never on the static
        // ConfigurationProperties. grpcBidiStreamingEnabled is left at its default (false), so the
        // request takes the default re-aggregating HTTP/2 path where the bug lived.
        Configuration configuration = Configuration.configuration()
            .maxGrpcMessageSize(INSTANCE_MAX_MESSAGE_SIZE);

        mockServer = new MockServer(configuration);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient.uploadGrpcDescriptor(greetingDescriptorBytes);

        channel = NettyChannelBuilder
            .forAddress("localhost", mockServer.getLocalPort())
            .usePlaintext()
            .build();

        grpcMethod = grpcMethodDescriptor(SERVICE, METHOD);
    }

    @After
    public void tearDown() throws Exception {
        if (channel != null) {
            channel.shutdownNow();
            channel.awaitTermination(10, TimeUnit.SECONDS);
            channel = null;
        }
        stopQuietly(mockServerClient);
        mockServerClient = null;
        if (mockServer != null) {
            mockServer.stop();
            mockServer = null;
        }
    }

    /**
     * A unary request whose message exceeds the instance-configured limit must be rejected with
     * {@code RESOURCE_EXHAUSTED}, even though a matching expectation would otherwise answer OK and
     * the message is well under the static 4 MiB default.
     * <p>
     * Bounded with a client deadline so a regression (the limit ignored, the request accepted and
     * matched) fails fast rather than hanging CI.
     */
    @Test(timeout = 30_000)
    public void shouldEnforceInstanceConfiguredMaxGrpcMessageSizeOverHttp2() {
        // Would answer OK if the oversize message were ever accepted -- so with the bug the call
        // succeeds, making the RESOURCE_EXHAUSTED assertion below a true discriminator.
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/" + SERVICE + "/" + METHOD)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withBody("{\"greeting\":\"Hello World\"}")
            );

        // A name field whose length pushes the encoded protobuf message well over the 1000-byte
        // instance limit while staying far under the 4 MiB static default.
        StringBuilder oversizeName = new StringBuilder();
        for (int i = 0; i < 5000; i++) {
            oversizeName.append('x');
        }

        try {
            DynamicMessage reply = ClientCalls.blockingUnaryCall(
                channel, grpcMethod, CallOptions.DEFAULT.withDeadlineAfter(20, TimeUnit.SECONDS),
                helloRequest(oversizeName.toString()));
            throw new AssertionError(
                "an oversize gRPC message must be rejected with RESOURCE_EXHAUSTED when the "
                    + "instance-configured maxGrpcMessageSize is exceeded, but the call succeeded with: "
                    + reply);
        } catch (StatusRuntimeException e) {
            assertThat(
                "the instance-configured maxGrpcMessageSize must be enforced over HTTP/2 -- a "
                    + "non-RESOURCE_EXHAUSTED status means the limit was read from the static store "
                    + "instead of the Configuration instance (issue #2669)",
                e.getStatus().getCode(), is(Status.Code.RESOURCE_EXHAUSTED));
        }
    }

    /**
     * Control: a message comfortably under the instance limit is served normally, so the rejection
     * above is the limit firing and not the pipeline refusing every request.
     */
    @Test(timeout = 30_000)
    public void shouldServeMessageUnderInstanceConfiguredLimitNormally() {
        mockServerClient
            .when(
                request()
                    .withMethod("POST")
                    .withPath("/" + SERVICE + "/" + METHOD)
            )
            .respond(
                response()
                    .withStatusCode(200)
                    .withHeader("grpc-status", "0")
                    .withBody("{\"greeting\":\"Hello World\"}")
            );

        DynamicMessage reply = ClientCalls.blockingUnaryCall(
            channel, grpcMethod, CallOptions.DEFAULT.withDeadlineAfter(20, TimeUnit.SECONDS),
            helloRequest("World"));

        assertThat(
            (String) reply.getField(responseType.findFieldByName("greeting")),
            is("Hello World"));
    }

    // ---- helpers ----

    private DynamicMessage helloRequest(String name) {
        return DynamicMessage.newBuilder(requestType)
            .setField(requestType.findFieldByName("name"), name)
            .build();
    }

    private Descriptors.MethodDescriptor method(String serviceName, String methodName) {
        Descriptors.ServiceDescriptor serviceDescriptor = services.get(serviceName);
        if (serviceDescriptor == null) {
            throw new IllegalStateException("service not found in descriptors: " + serviceName);
        }
        Descriptors.MethodDescriptor methodDescriptor = serviceDescriptor.findMethodByName(methodName);
        if (methodDescriptor == null) {
            throw new IllegalStateException("method not found: " + serviceName + "/" + methodName);
        }
        return methodDescriptor;
    }

    private MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethodDescriptor(String serviceName, String methodName) {
        Descriptors.MethodDescriptor methodDescriptor = method(serviceName, methodName);
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.UNARY)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(serviceName, methodName))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(methodDescriptor.getInputType())))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(methodDescriptor.getOutputType())))
            .build();
    }

    private void registerServices(byte[] descriptorBytes) throws Exception {
        DescriptorProtos.FileDescriptorSet fileDescriptorSet =
            DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes);
        for (DescriptorProtos.FileDescriptorProto fileDescriptorProto : fileDescriptorSet.getFileList()) {
            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(
                fileDescriptorProto, new Descriptors.FileDescriptor[0]);
            for (Descriptors.ServiceDescriptor serviceDescriptor : fileDescriptor.getServices()) {
                services.put(serviceDescriptor.getFullName(), serviceDescriptor);
            }
        }
    }
}

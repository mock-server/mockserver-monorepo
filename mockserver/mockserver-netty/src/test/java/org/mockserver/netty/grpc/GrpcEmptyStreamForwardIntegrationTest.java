package org.mockserver.netty.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerServiceDefinition;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.ServerCalls;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.netty.MockServer;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.is;
import static org.mockserver.model.HttpForward.forward;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * GitHub issue #2988: a gRPC server stream that the upstream ends with no messages must reach the
 * client through MockServer's forward path with no messages, and so must its recorded replay.
 * <p>
 * A real grpc-java server (over TLS, so the forward leg negotiates HTTP/2 with ALPN) answers
 * {@code ListGreetings} with as many messages as the request's {@code name} asks for. A real
 * grpc-java client calls it through a MockServer {@code FORWARD} expectation over h2c, and then
 * calls the recorded expectation with the upstream stopped.
 */
public class GrpcEmptyStreamForwardIntegrationTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String LIST_GREETINGS = "ListGreetings";
    private static final String DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";
    private static final String SERVER_CERTIFICATE = "org/mockserver/socket/CertificateAuthorityCertificate.pem";
    private static final String SERVER_PRIVATE_KEY = "org/mockserver/socket/PKCS8CertificateAuthorityPrivateKey.pem";

    private byte[] descriptorBytes;
    private Descriptors.MethodDescriptor listGreetings;
    private MethodDescriptor<DynamicMessage, DynamicMessage> listGreetingsCall;

    private Server upstream;
    private MockServer proxy;
    private MockServerClient proxyClient;
    private final List<ManagedChannel> channels = new ArrayList<>();

    @Before
    public void setUp() throws Exception {
        descriptorBytes = Files.readAllBytes(Paths.get(DESCRIPTOR));
        listGreetings = method(descriptorBytes, SERVICE, LIST_GREETINGS);
        listGreetingsCall = MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(MethodDescriptor.MethodType.SERVER_STREAMING)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, LIST_GREETINGS))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(listGreetings.getInputType())))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(listGreetings.getOutputType())))
            .build();

        upstream = startUpstream();

        proxy = new MockServer();
        proxyClient = new MockServerClient("localhost", proxy.getLocalPort());
        proxyClient.uploadGrpcDescriptor(descriptorBytes);
        proxyClient
            .when(request().withMethod("POST").withPath("/" + SERVICE + "/" + LIST_GREETINGS))
            .forward(forward()
                .withHost("127.0.0.1")
                .withPort(upstream.getPort())
                .withScheme(org.mockserver.model.HttpForward.Scheme.HTTPS));
    }

    @After
    public void tearDown() throws Exception {
        for (ManagedChannel channel : channels) {
            channel.shutdownNow();
            channel.awaitTermination(10, TimeUnit.SECONDS);
        }
        stopQuietly(proxyClient);
        if (proxy != null) {
            proxy.stop();
        }
        if (upstream != null) {
            upstream.shutdownNow();
            upstream.awaitTermination(10, TimeUnit.SECONDS);
        }
    }

    @Test
    public void shouldRelayEmptyServerStreamWithNoMessages() {
        assertThat(callListGreetings(proxy.getLocalPort(), 0), is(empty()));
    }

    @Test
    public void shouldRelayEveryMessageOfAServerStream() {
        assertThat(callListGreetings(proxy.getLocalPort(), 2), contains("greeting 1", "greeting 2"));
    }

    @Test
    public void shouldReplayRecordedEmptyServerStreamWithNoMessages() throws Exception {
        assertThat(callListGreetings(proxy.getLocalPort(), 0), is(empty()));

        Expectation[] recorded = proxyClient.retrieveRecordedExpectations(
            request().withPath("/" + SERVICE + "/" + LIST_GREETINGS));
        assertThat("expected the forwarded empty stream to be recorded", recorded.length, greaterThanOrEqualTo(1));

        replayOnProxy(recorded);
        assertThat(callListGreetings(proxy.getLocalPort(), 0), is(empty()));
    }

    @Test
    public void shouldReplayRecordedServerStreamWithEveryMessage() throws Exception {
        assertThat(callListGreetings(proxy.getLocalPort(), 2), contains("greeting 1", "greeting 2"));

        Expectation[] recorded = proxyClient.retrieveRecordedExpectations(
            request().withPath("/" + SERVICE + "/" + LIST_GREETINGS));
        assertThat("expected the forwarded stream to be recorded", recorded.length, greaterThanOrEqualTo(1));

        replayOnProxy(recorded);
        assertThat(callListGreetings(proxy.getLocalPort(), 2), contains("greeting 1", "greeting 2"));
    }

    /**
     * Replays the recording with the upstream stopped, so nothing can be forwarded. The recorded
     * request carries the forward target's Host and TLS, so both are dropped, as they would be to
     * replay it on another server.
     */
    private void replayOnProxy(Expectation[] recorded) throws InterruptedException {
        for (Expectation expectation : recorded) {
            ((HttpRequest) expectation.getHttpRequest()).removeHeader("Host").withSecure(null);
        }
        upstream.shutdownNow();
        upstream.awaitTermination(10, TimeUnit.SECONDS);
        proxyClient.reset();
        proxyClient.uploadGrpcDescriptor(descriptorBytes);
        proxyClient.upsert(recorded);
    }

    /**
     * Calls {@code ListGreetings} asking for {@code count} messages and returns the greetings
     * received. A non-OK status throws, so an empty list means the stream ended OK with no messages.
     */
    private List<String> callListGreetings(int port, int count) {
        ManagedChannel channel = NettyChannelBuilder.forAddress("localhost", port).usePlaintext().build();
        channels.add(channel);
        Descriptors.Descriptor requestType = listGreetings.getInputType();
        DynamicMessage request = DynamicMessage.newBuilder(requestType)
            .setField(requestType.findFieldByName("name"), String.valueOf(count))
            .build();
        Iterator<DynamicMessage> replies = ClientCalls.blockingServerStreamingCall(
            channel, listGreetingsCall, CallOptions.DEFAULT, request);
        Descriptors.FieldDescriptor greeting = listGreetings.getOutputType().findFieldByName("greeting");
        List<String> greetings = new ArrayList<>();
        while (replies.hasNext()) {
            greetings.add((String) replies.next().getField(greeting));
        }
        return greetings;
    }

    private Server startUpstream() throws Exception {
        Descriptors.Descriptor responseType = listGreetings.getOutputType();
        Descriptors.FieldDescriptor greeting = responseType.findFieldByName("greeting");
        Descriptors.FieldDescriptor name = listGreetings.getInputType().findFieldByName("name");
        ServerServiceDefinition service = ServerServiceDefinition.builder(SERVICE)
            .addMethod(listGreetingsCall, ServerCalls.asyncServerStreamingCall((request, responseObserver) -> {
                int count = Integer.parseInt((String) request.getField(name));
                for (int i = 1; i <= count; i++) {
                    responseObserver.onNext(DynamicMessage.newBuilder(responseType)
                        .setField(greeting, "greeting " + i)
                        .build());
                }
                responseObserver.onCompleted();
            }))
            .build();
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream certificate = classLoader.getResourceAsStream(SERVER_CERTIFICATE);
             InputStream privateKey = classLoader.getResourceAsStream(SERVER_PRIVATE_KEY)) {
            return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(GrpcSslContexts.forServer(certificate, privateKey).build())
                .addService(service)
                .build()
                .start();
        }
    }

    private static Descriptors.MethodDescriptor method(byte[] descriptorBytes, String serviceName, String methodName) throws Exception {
        DescriptorProtos.FileDescriptorSet fileDescriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes);
        for (DescriptorProtos.FileDescriptorProto fileDescriptorProto : fileDescriptorSet.getFileList()) {
            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(
                fileDescriptorProto, new Descriptors.FileDescriptor[0]);
            for (Descriptors.ServiceDescriptor serviceDescriptor : fileDescriptor.getServices()) {
                if (serviceDescriptor.getFullName().equals(serviceName)) {
                    return serviceDescriptor.findMethodByName(methodName);
                }
            }
        }
        throw new IllegalStateException("method not found: " + serviceName + "/" + methodName);
    }
}

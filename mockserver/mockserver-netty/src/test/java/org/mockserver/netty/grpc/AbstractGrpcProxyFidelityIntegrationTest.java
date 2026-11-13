package org.mockserver.netty.grpc;

import com.google.protobuf.DescriptorProtos;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientInterceptors;
import io.grpc.Context;
import io.grpc.ManagedChannel;
import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerServiceDefinition;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.GrpcSslContexts;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.protobuf.ProtoUtils;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.MetadataUtils;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;
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
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.Assert.fail;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.stop.Stop.stopQuietly;
import static org.mockserver.verify.VerificationTimes.exactly;

/**
 * What a real grpc-java client sees when MockServer relays its calls to a real grpc-java upstream,
 * and when it then replays the recording with the upstream stopped. Every RPC shape is covered with
 * zero, one and several messages (bidi half-duplex only), together with status, message, request
 * metadata, response headers and trailers (text and {@code -bin}), compression, deadlines and a
 * large message.
 * <p>
 * Subclasses decide how the client reaches MockServer and how MockServer is told to forward, so
 * the same assertions run against each proxy path.
 */
public abstract class AbstractGrpcProxyFidelityIntegrationTest {

    protected static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";
    private static final String SERVER_CERTIFICATE = "org/mockserver/socket/CertificateAuthorityCertificate.pem";
    private static final String SERVER_PRIVATE_KEY = "org/mockserver/socket/PKCS8CertificateAuthorityPrivateKey.pem";

    private static final Metadata.Key<String> CLIENT_META = Metadata.Key.of("x-client-meta", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<byte[]> CLIENT_BIN = Metadata.Key.of("x-client-bin", Metadata.BINARY_BYTE_MARSHALLER);
    private static final Metadata.Key<String> INITIAL = Metadata.Key.of("x-initial", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<String> COST = Metadata.Key.of("x-cost", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<byte[]> TRACE_BIN = Metadata.Key.of("x-trace-bin", Metadata.BINARY_BYTE_MARSHALLER);
    private static final Metadata.Key<String> ERROR_REASON = Metadata.Key.of("x-error-reason", Metadata.ASCII_STRING_MARSHALLER);
    private static final Metadata.Key<byte[]> ERROR_BIN = Metadata.Key.of("x-error-bin", Metadata.BINARY_BYTE_MARSHALLER);
    private static final Metadata.Key<String> GRPC_ENCODING = Metadata.Key.of("grpc-encoding", Metadata.ASCII_STRING_MARSHALLER);

    private static final String ERROR_MESSAGE = "no greeting for é at 50%";
    private static final int LARGE_SIZE = 1024 * 1024;

    protected byte[] descriptorBytes;
    private Descriptors.ServiceDescriptor serviceDescriptor;
    private Descriptors.Descriptor requestType;
    private Descriptors.Descriptor responseType;
    private MethodDescriptor<DynamicMessage, DynamicMessage> greetingCall;
    private MethodDescriptor<DynamicMessage, DynamicMessage> listGreetingsCall;
    private MethodDescriptor<DynamicMessage, DynamicMessage> collectGreetingsCall;
    private MethodDescriptor<DynamicMessage, DynamicMessage> chatCall;

    protected Server upstream;
    private int upstreamPort;
    protected MockServer proxy;
    protected MockServerClient proxyClient;
    private final List<ManagedChannel> channels = new ArrayList<>();

    /**
     * Starts MockServer configured for this proxy path.
     */
    protected abstract MockServer startProxy();

    /**
     * Tells MockServer to send every {@code GreetingService} call to the upstream, if this path
     * needs an expectation for that.
     */
    protected abstract void routeToUpstream(int upstreamPort);

    /**
     * A new client channel that reaches the upstream through MockServer.
     */
    protected abstract ManagedChannel newChannel(int upstreamPort) throws Exception;

    /**
     * Whether MockServer has the descriptors, so decodes each call to JSON, or relays the protobuf
     * frames as they are.
     */
    protected boolean hasDescriptors() {
        return true;
    }

    @Before
    public void startServers() throws Exception {
        descriptorBytes = Files.readAllBytes(Paths.get(DESCRIPTOR));
        serviceDescriptor = service(descriptorBytes);
        requestType = serviceDescriptor.findMethodByName("Greeting").getInputType();
        responseType = serviceDescriptor.findMethodByName("Greeting").getOutputType();
        greetingCall = methodDescriptor("Greeting", MethodDescriptor.MethodType.UNARY);
        listGreetingsCall = methodDescriptor("ListGreetings", MethodDescriptor.MethodType.SERVER_STREAMING);
        collectGreetingsCall = methodDescriptor("CollectGreetings", MethodDescriptor.MethodType.CLIENT_STREAMING);
        chatCall = methodDescriptor("Chat", MethodDescriptor.MethodType.BIDI_STREAMING);

        upstream = startUpstream();
        upstreamPort = upstream.getPort();
        proxy = startProxy();
        proxyClient = new MockServerClient("localhost", proxy.getLocalPort());
        if (hasDescriptors()) {
            proxyClient.uploadGrpcDescriptor(descriptorBytes);
        }
        routeToUpstream(upstreamPort);
    }

    @After
    public void stopServers() throws Exception {
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

    // ---- unary ----

    @Test
    public void shouldRelayAndReplayUnaryResponse() throws Exception {
        assertThat(greeting("alice", CallOptions.DEFAULT).reply, is("hello alice"));

        replayRecording("Greeting", 1);

        assertThat(greeting("alice", CallOptions.DEFAULT).reply, is("hello alice"));
    }

    @Test
    public void shouldRelayAndReplayErrorStatusMessageAndTrailingMetadata() throws Exception {
        assertUpstreamError(greeting("error", CallOptions.DEFAULT));

        replayRecording("Greeting", 1);

        assertUpstreamError(greeting("error", CallOptions.DEFAULT));
    }

    private void assertUpstreamError(UnaryResult result) {
        assertThat(result.reply, is(nullValue()));
        assertThat(result.status.getCode(), is(Status.Code.NOT_FOUND));
        assertThat(result.status.getDescription(), is(ERROR_MESSAGE));
        assertThat(result.trailers.get(ERROR_REASON), is("missing"));
        assertThat(Arrays.toString(result.trailers.get(ERROR_BIN)), is(Arrays.toString(new byte[]{1, 2, 3, 4})));
    }

    @Test
    public void shouldRelayAndReplayResponseHeadersAndTrailingMetadataOfASuccessfulCall() throws Exception {
        assertSuccessfulMetadata(greeting("metadata", CallOptions.DEFAULT));

        replayRecording("Greeting", 1);

        assertSuccessfulMetadata(greeting("metadata", CallOptions.DEFAULT));
    }

    private void assertSuccessfulMetadata(UnaryResult result) {
        assertThat(result.status.getCode(), is(Status.Code.OK));
        assertThat(result.reply, is("hello metadata"));
        assertThat(result.headers.get(INITIAL), is("one"));
        assertThat("x-initial is response header metadata, not trailing metadata", result.trailers.get(INITIAL), is(nullValue()));
        assertThat("x-cost is trailing metadata, so it must arrive in the trailers", result.trailers.get(COST), is("7"));
        assertThat(Arrays.toString(result.trailers.get(TRACE_BIN)), is(Arrays.toString(new byte[]{9, 8, 7})));
        assertThat("x-cost is trailing metadata, not response header metadata", result.headers.get(COST), is(nullValue()));
    }

    @Test
    public void shouldForwardRequestMetadataIncludingBinaryMetadata() {
        Metadata metadata = new Metadata();
        metadata.put(CLIENT_META, "abc");
        metadata.put(CLIENT_BIN, new byte[]{1, 2, 3, (byte) 0xFF});

        UnaryResult result = greeting("echo-metadata", CallOptions.DEFAULT, metadata);

        assertThat(result.reply, is("x-client-meta=abc x-client-bin=" + Base64.getEncoder().encodeToString(new byte[]{1, 2, 3, (byte) 0xFF})));
    }

    @Test
    public void shouldRelayAndReplayALargeMessage() throws Exception {
        assertThat(greeting("large", CallOptions.DEFAULT).reply, is(largeGreeting()));

        replayRecording("Greeting", 1);

        assertThat(greeting("large", CallOptions.DEFAULT).reply, is(largeGreeting()));
    }

    @Test
    public void shouldRelayAndReplayACompressedCall() throws Exception {
        CallOptions gzip = CallOptions.DEFAULT.withCompression("gzip");
        UnaryResult relayed = greeting("gzip", gzip);
        assertThat(relayed.status.getCode(), is(Status.Code.OK));
        assertThat(relayed.reply, is("hello gzip compressed=gzip"));

        replayRecording("Greeting", 1);

        UnaryResult replayed = greeting("gzip", gzip);
        assertThat(replayed.status.getCode(), is(Status.Code.OK));
        assertThat(replayed.reply, is("hello gzip compressed=gzip"));
    }

    @Test
    public void shouldPropagateTheClientDeadlineToTheUpstream() {
        UnaryResult result = greeting("deadline", CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS));

        assertThat(result.reply, is("hello deadline hasDeadline=true"));
    }

    @Test
    public void shouldReplayARecordingMadeWithADeadlineForACallWithAnotherDeadline() throws Exception {
        assertThat(greeting("alice", CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS)).reply, is("hello alice"));

        replayRecording("Greeting", 1);

        UnaryResult replayed = greeting("alice", CallOptions.DEFAULT.withDeadlineAfter(20, TimeUnit.SECONDS));
        assertThat(replayed.status.getCode(), is(Status.Code.OK));
        assertThat(replayed.reply, is("hello alice"));
    }

    @Test
    public void shouldVerifyAndRetrieveTheDecodedForwardedRequest() {
        assertThat(greeting("verify-me", CallOptions.DEFAULT).reply, is("hello verify-me"));

        proxyClient.verify(
            request().withPath("/" + SERVICE + "/Greeting").withBody(json("{\"name\":\"verify-me\"}")),
            exactly(1));
        HttpRequest[] recorded = proxyClient.retrieveRecordedRequests(request().withPath("/" + SERVICE + "/Greeting"));
        assertThat(recorded.length, is(1));
        assertThat(recorded[0].getBodyAsString(), is("{\n  \"name\": \"verify-me\"\n}"));
    }

    @Test
    public void shouldReportAnUnreachableUpstreamAsUnavailable() throws Exception {
        upstream.shutdownNow();
        upstream.awaitTermination(10, TimeUnit.SECONDS);

        UnaryResult result = greeting("alice", CallOptions.DEFAULT);

        assertThat(result.reply, is(nullValue()));
        assertThat(result.status.getCode(), is(Status.Code.UNAVAILABLE));
    }

    // ---- server streaming ----

    @Test
    public void shouldRelayAndReplayServerStreamsOfNoneOneAndManyMessages() throws Exception {
        assertThat(listGreetings("0"), is(empty()));
        assertThat(listGreetings("1"), contains("greeting 1"));
        assertThat(listGreetings("50"), is(expectedGreetings(50)));

        replayRecording("ListGreetings", 3);

        assertThat(listGreetings("0"), is(empty()));
        assertThat(listGreetings("1"), contains("greeting 1"));
        assertThat(listGreetings("50"), is(expectedGreetings(50)));
    }

    @Test
    public void shouldRelayAndReplayAServerStreamThatFailsAfterSomeMessages() throws Exception {
        assertMessagesThenAborted(listGreetingsUntilClosed("abort-after-2"));

        replayRecording("ListGreetings", 1);

        assertMessagesThenAborted(listGreetingsUntilClosed("abort-after-2"));
    }

    private void assertMessagesThenAborted(StreamResult result) {
        assertThat(result.messages, contains("greeting 1", "greeting 2"));
        assertThat(result.status.getCode(), is(Status.Code.ABORTED));
        assertThat(result.status.getDescription(), is("stopped after 2"));
    }

    // ---- client streaming ----

    @Test
    public void shouldRelayAndReplayClientStreamsOfNoneOneAndManyMessages() throws Exception {
        assertThat(collectGreetings(), is("0:"));
        assertThat(collectGreetings("a"), is("1:a"));
        assertThat(collectGreetings("a", "b", "c"), is("3:a,b,c"));

        replayRecording("CollectGreetings", 3);

        assertThat(collectGreetings(), is("0:"));
        assertThat(collectGreetings("a"), is("1:a"));
        assertThat(collectGreetings("a", "b", "c"), is("3:a,b,c"));
    }

    // ---- bidirectional streaming (half-duplex: every request is sent before any response) ----

    @Test
    public void shouldRelayAndReplayAHalfDuplexBidiCall() throws Exception {
        assertThat(chat("x", "y"), contains("echo x", "echo y"));

        replayRecording("Chat", 1);

        assertThat(chat("x", "y"), contains("echo x", "echo y"));
    }

    // ---- replay ----

    /**
     * Stops the upstream and installs what MockServer recorded for {@code method}, which must be one
     * recording per call made, in its place.
     * The recorded request carries the forward target's Host and TLS, so both are dropped, as they
     * would be to replay it on another server.
     */
    protected void replayRecording(String method, int expectedRecordings) throws InterruptedException {
        Expectation[] recorded = proxyClient.retrieveRecordedExpectations(request().withPath("/" + SERVICE + "/" + method));
        assertThat("recordings of " + method, recorded.length, is(expectedRecordings));
        for (Expectation expectation : recorded) {
            ((HttpRequest) expectation.getHttpRequest()).removeHeader("Host").withSecure(null);
        }
        upstream.shutdownNow();
        upstream.awaitTermination(10, TimeUnit.SECONDS);
        proxyClient.reset();
        if (hasDescriptors()) {
            proxyClient.uploadGrpcDescriptor(descriptorBytes);
        }
        proxyClient.upsert(recorded);
    }

    // ---- client calls ----

    private static final class UnaryResult {
        String reply;
        Status status;
        Metadata headers = new Metadata();
        Metadata trailers = new Metadata();
    }

    private static final class StreamResult {
        final List<String> messages = new ArrayList<>();
        Status status;
    }

    protected void greetingReply(String name) {
        assertThat(greeting(name, CallOptions.DEFAULT).reply, is("hello " + name));
    }

    private UnaryResult greeting(String name, CallOptions callOptions) {
        return greeting(name, callOptions, new Metadata());
    }

    private UnaryResult greeting(String name, CallOptions callOptions, Metadata requestMetadata) {
        AtomicReference<Metadata> headers = new AtomicReference<>();
        AtomicReference<Metadata> trailers = new AtomicReference<>();
        Channel channel = ClientInterceptors.intercept(channel(),
            MetadataUtils.newAttachHeadersInterceptor(requestMetadata),
            MetadataUtils.newCaptureMetadataInterceptor(headers, trailers));
        UnaryResult result = new UnaryResult();
        try {
            DynamicMessage reply = ClientCalls.blockingUnaryCall(channel, greetingCall,
                callOptions.getDeadline() == null ? callOptions.withDeadlineAfter(30, TimeUnit.SECONDS) : callOptions,
                helloRequest(name));
            result.reply = greetingOf(reply);
            result.status = Status.OK;
        } catch (StatusRuntimeException e) {
            result.status = e.getStatus();
        }
        if (headers.get() != null) {
            result.headers = headers.get();
        }
        if (trailers.get() != null) {
            result.trailers = trailers.get();
        }
        return result;
    }

    private List<String> listGreetings(String name) {
        StreamResult result = listGreetingsUntilClosed(name);
        if (!result.status.isOk()) {
            fail("ListGreetings(" + name + ") failed with " + result.status);
        }
        return result.messages;
    }

    private StreamResult listGreetingsUntilClosed(String name) {
        StreamResult result = new StreamResult();
        Iterator<DynamicMessage> replies = ClientCalls.blockingServerStreamingCall(channel(), listGreetingsCall,
            CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS), helloRequest(name));
        try {
            while (replies.hasNext()) {
                result.messages.add(greetingOf(replies.next()));
            }
            result.status = Status.OK;
        } catch (StatusRuntimeException e) {
            result.status = e.getStatus();
        }
        return result;
    }

    private String collectGreetings(String... names) throws Exception {
        CompletableFuture<String> reply = new CompletableFuture<>();
        StreamObserver<DynamicMessage> requests = ClientCalls.asyncClientStreamingCall(
            channel().newCall(collectGreetingsCall, CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS)),
            completingObserver(reply));
        for (String name : names) {
            requests.onNext(helloRequest(name));
        }
        requests.onCompleted();
        return reply.get(30, TimeUnit.SECONDS);
    }

    private List<String> chat(String... names) throws Exception {
        List<String> replies = new ArrayList<>();
        CompletableFuture<List<String>> done = new CompletableFuture<>();
        StreamObserver<DynamicMessage> requests = ClientCalls.asyncBidiStreamingCall(
            channel().newCall(chatCall, CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS)),
            new StreamObserver<DynamicMessage>() {
                @Override
                public void onNext(DynamicMessage value) {
                    synchronized (replies) {
                        replies.add(greetingOf(value));
                    }
                }

                @Override
                public void onError(Throwable t) {
                    done.completeExceptionally(t);
                }

                @Override
                public void onCompleted() {
                    synchronized (replies) {
                        done.complete(new ArrayList<>(replies));
                    }
                }
            });
        for (String name : names) {
            requests.onNext(helloRequest(name));
        }
        requests.onCompleted();
        return done.get(30, TimeUnit.SECONDS);
    }

    private StreamObserver<DynamicMessage> completingObserver(CompletableFuture<String> reply) {
        return new StreamObserver<DynamicMessage>() {
            @Override
            public void onNext(DynamicMessage value) {
                reply.complete(greetingOf(value));
            }

            @Override
            public void onError(Throwable t) {
                reply.completeExceptionally(t);
            }

            @Override
            public void onCompleted() {
                reply.complete(null);
            }
        };
    }

    private Channel channel() {
        try {
            ManagedChannel channel = newChannel(upstreamPort);
            channels.add(channel);
            return channel;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private DynamicMessage helloRequest(String name) {
        return DynamicMessage.newBuilder(requestType).setField(requestType.findFieldByName("name"), name).build();
    }

    private DynamicMessage helloResponse(String greeting) {
        return DynamicMessage.newBuilder(responseType).setField(responseType.findFieldByName("greeting"), greeting).build();
    }

    private String nameOf(DynamicMessage request) {
        return (String) request.getField(requestType.findFieldByName("name"));
    }

    private String greetingOf(DynamicMessage reply) {
        return (String) reply.getField(responseType.findFieldByName("greeting"));
    }

    private static List<String> expectedGreetings(int count) {
        List<String> greetings = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            greetings.add("greeting " + i);
        }
        return greetings;
    }

    private static String largeGreeting() {
        StringBuilder greeting = new StringBuilder(LARGE_SIZE);
        for (int i = 0; i < LARGE_SIZE; i++) {
            greeting.append((char) ('a' + i % 26));
        }
        return greeting.toString();
    }

    // ---- upstream ----

    private Server startUpstream() throws Exception {
        ServerServiceDefinition service = ServerServiceDefinition.builder(SERVICE)
            .addMethod(greetingCall, new GreetingHandler())
            .addMethod(listGreetingsCall, ServerCalls.asyncServerStreamingCall((request, responseObserver) -> {
                String name = nameOf(request);
                if (name.startsWith("abort-after-")) {
                    int count = Integer.parseInt(name.substring("abort-after-".length()));
                    for (int i = 1; i <= count; i++) {
                        responseObserver.onNext(helloResponse("greeting " + i));
                    }
                    responseObserver.onError(Status.ABORTED.withDescription("stopped after " + count).asRuntimeException());
                    return;
                }
                for (int i = 1; i <= Integer.parseInt(name); i++) {
                    responseObserver.onNext(helloResponse("greeting " + i));
                }
                responseObserver.onCompleted();
            }))
            .addMethod(collectGreetingsCall, ServerCalls.asyncClientStreamingCall(responseObserver -> new StreamObserver<DynamicMessage>() {
                private final List<String> names = new ArrayList<>();

                @Override
                public void onNext(DynamicMessage value) {
                    names.add(nameOf(value));
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    responseObserver.onNext(helloResponse(names.size() + ":" + String.join(",", names)));
                    responseObserver.onCompleted();
                }
            }))
            .addMethod(chatCall, ServerCalls.asyncBidiStreamingCall(responseObserver -> new StreamObserver<DynamicMessage>() {
                @Override
                public void onNext(DynamicMessage value) {
                    responseObserver.onNext(helloResponse("echo " + nameOf(value)));
                }

                @Override
                public void onError(Throwable t) {
                }

                @Override
                public void onCompleted() {
                    responseObserver.onCompleted();
                }
            }))
            .build();
        ClassLoader classLoader = getClass().getClassLoader();
        try (InputStream certificate = classLoader.getResourceAsStream(SERVER_CERTIFICATE);
             InputStream privateKey = classLoader.getResourceAsStream(SERVER_PRIVATE_KEY)) {
            return NettyServerBuilder.forAddress(new InetSocketAddress("127.0.0.1", 0))
                .sslContext(GrpcSslContexts.forServer(certificate, privateKey).build())
                .maxInboundMessageSize(8 * LARGE_SIZE)
                .addService(service)
                .build()
                .start();
        }
    }

    /**
     * The unary method, written against {@link ServerCall} directly so it can send response
     * headers, trailers and compression of its choosing. The request's {@code name} picks what it
     * does.
     */
    private final class GreetingHandler implements ServerCallHandler<DynamicMessage, DynamicMessage> {
        @Override
        public ServerCall.Listener<DynamicMessage> startCall(ServerCall<DynamicMessage, DynamicMessage> call, Metadata requestHeaders) {
            boolean hasDeadline = Context.current().getDeadline() != null;
            call.request(1);
            return new ServerCall.Listener<DynamicMessage>() {
                @Override
                public void onMessage(DynamicMessage message) {
                    String name = nameOf(message);
                    Metadata headers = new Metadata();
                    Metadata trailers = new Metadata();
                    String greeting = "hello " + name;
                    switch (name) {
                        case "error":
                            trailers.put(ERROR_REASON, "missing");
                            trailers.put(ERROR_BIN, new byte[]{1, 2, 3, 4});
                            call.close(Status.NOT_FOUND.withDescription(ERROR_MESSAGE), trailers);
                            return;
                        case "metadata":
                            headers.put(INITIAL, "one");
                            trailers.put(COST, "7");
                            trailers.put(TRACE_BIN, new byte[]{9, 8, 7});
                            break;
                        case "echo-metadata":
                            byte[] binary = requestHeaders.get(CLIENT_BIN);
                            greeting = "x-client-meta=" + requestHeaders.get(CLIENT_META)
                                + " x-client-bin=" + (binary == null ? "null" : Base64.getEncoder().encodeToString(binary));
                            break;
                        case "large":
                            greeting = largeGreeting();
                            break;
                        case "gzip":
                            greeting = "hello gzip compressed=" + requestHeaders.get(GRPC_ENCODING);
                            call.setCompression("gzip");
                            break;
                        case "deadline":
                            greeting = "hello deadline hasDeadline=" + hasDeadline;
                            break;
                        default:
                            break;
                    }
                    call.sendHeaders(headers);
                    call.sendMessage(helloResponse(greeting));
                    call.close(Status.OK, trailers);
                }
            };
        }
    }

    // ---- descriptors ----

    private MethodDescriptor<DynamicMessage, DynamicMessage> methodDescriptor(String method, MethodDescriptor.MethodType type) {
        Descriptors.MethodDescriptor descriptor = serviceDescriptor.findMethodByName(method);
        return MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
            .setType(type)
            .setFullMethodName(MethodDescriptor.generateFullMethodName(SERVICE, method))
            .setRequestMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor.getInputType())))
            .setResponseMarshaller(ProtoUtils.marshaller(DynamicMessage.getDefaultInstance(descriptor.getOutputType())))
            .build();
    }

    private static Descriptors.ServiceDescriptor service(byte[] descriptorBytes) throws Exception {
        DescriptorProtos.FileDescriptorSet fileDescriptorSet = DescriptorProtos.FileDescriptorSet.parseFrom(descriptorBytes);
        for (DescriptorProtos.FileDescriptorProto fileDescriptorProto : fileDescriptorSet.getFileList()) {
            Descriptors.FileDescriptor fileDescriptor = Descriptors.FileDescriptor.buildFrom(fileDescriptorProto, new Descriptors.FileDescriptor[0]);
            for (Descriptors.ServiceDescriptor serviceDescriptor : fileDescriptor.getServices()) {
                if (serviceDescriptor.getFullName().equals(SERVICE)) {
                    return serviceDescriptor;
                }
            }
        }
        throw new IllegalStateException("service not found: " + SERVICE);
    }
}

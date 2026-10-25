package org.mockserver.netty.grpc;

import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.After;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcHealthCheckHandler;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.HttpQuotaRegistry;
import org.mockserver.model.GrpcChaosProfile;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Handler-level coverage for gRPC fault injection in {@link GrpcToHttpRequestHandler}.
 *
 * <p>The registry / decision / profile classes are unit-tested in isolation; this test
 * exercises the actual <em>wiring</em> through the Netty handler via an {@link EmbeddedChannel}
 * — i.e. that a registered gRPC chaos profile causes the handler to short-circuit a matched
 * gRPC request with a {@code grpc-status} error response, and that absent/zero-probability
 * chaos passes the request through untouched. (A bug in exactly this kind of handler wiring
 * — present but never integration-tested — is what previously let mock-drift detection ship
 * broken; this guards the gRPC-chaos equivalent.)
 */
public class GrpcToHttpRequestHandlerChaosTest {

    private final GrpcChaosRegistry chaosRegistry = new GrpcChaosRegistry(System::currentTimeMillis);

    private EmbeddedChannel channelWith(GrpcChaosRegistry registry) {
        GrpcToHttpRequestHandler handler = new GrpcToHttpRequestHandler(
            new MockServerLogger(),
            new GrpcProtoDescriptorStore(new MockServerLogger()),
            new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
            registry,
            HttpQuotaRegistry.getInstance());
        return new EmbeddedChannel(handler);
    }

    private HttpRequest grpcRequest(String path) {
        return request().withMethod("POST").withPath(path)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(new byte[]{0, 0, 0, 0, 0});
    }

    @After
    public void tearDown() {
        chaosRegistry.reset();
    }

    @Test
    public void injectsConfiguredGrpcStatusWhenProbabilityIsOne() {
        chaosRegistry.put("pay.v1.PaymentService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("UNAVAILABLE").withErrorProbability(1.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/pay.v1.PaymentService/Charge"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(200));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("14")); // UNAVAILABLE
        // request must NOT be propagated to normal handling when chaos fires
        assertThat(channel.readInbound(), is(nullValue()));
    }

    @Test
    public void refusesWithoutDataPlaneCredentialsBeforeChaosCountsTheCall() {
        chaosRegistry.put("pay.v1.PaymentService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("UNAVAILABLE").withErrorProbability(1.0));
        EmbeddedChannel channel = new EmbeddedChannel(new GrpcToHttpRequestHandler(
            configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken("chaos-token"),
            new MockServerLogger(),
            new GrpcProtoDescriptorStore(new MockServerLogger()),
            new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
            chaosRegistry,
            HttpQuotaRegistry.getInstance()));

        channel.writeInbound(grpcRequest("/pay.v1.PaymentService/Charge"));

        HttpResponse refused = channel.readOutbound();
        assertThat(firstTrailer(refused, GrpcStatusMapper.GRPC_STATUS_HEADER), is("16")); // UNAUTHENTICATED
        assertThat(refused.getFirstHeader("www-authenticate"), is("Bearer"));
        assertThat("the refused call was not counted", chaosRegistry.incrementMatchCount("pay.v1.PaymentService"), is(1));

        channel.writeInbound(grpcRequest("/pay.v1.PaymentService/Charge").withHeader("authorization", "Bearer chaos-token"));

        assertThat(firstTrailer(channel.readOutbound(), GrpcStatusMapper.GRPC_STATUS_HEADER), is("14")); // UNAVAILABLE
    }

    @Test
    public void injectsCustomStatusCode() {
        chaosRegistry.put("orders.v1.OrderService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("DEADLINE_EXCEEDED").withErrorProbability(1.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/orders.v1.OrderService/Create"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("4")); // DEADLINE_EXCEEDED
    }

    @Test
    public void passesThroughWhenNoChaosRegistered() {
        EmbeddedChannel channel = channelWith(chaosRegistry);

        HttpRequest req = grpcRequest("/free.v1.FreeService/Call");
        channel.writeInbound(req);

        // no chaos and no descriptor → request flows through to normal handling
        assertThat(channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));
    }

    @Test
    public void passesThroughWhenProbabilityIsZero() {
        chaosRegistry.put("quiet.v1.QuietService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("UNAVAILABLE").withErrorProbability(0.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/quiet.v1.QuietService/Call"));

        assertThat(channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));
    }

    @Test
    public void nonGrpcRequestIsUnaffectedByChaos() {
        chaosRegistry.put("pay.v1.PaymentService",
            GrpcChaosProfile.grpcChaosProfile().withErrorStatusCode("UNAVAILABLE").withErrorProbability(1.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // a plain (non-gRPC) request must never be touched by gRPC chaos
        channel.writeInbound(request().withMethod("POST").withPath("/pay.v1.PaymentService/Charge")
            .withHeader("content-type", "application/json"));

        assertThat(channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));
    }

    // --- omitGrpcStatus tests ---

    @Test
    public void omitGrpcStatusProducesResponseWithNoGrpcStatusHeader() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile().withOmitGrpcStatus(true));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(200));
        // grpc-status header must be absent
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
        // request must NOT be propagated
        assertThat(channel.readInbound(), is(nullValue()));
    }

    @Test
    public void omitGrpcStatusCombinedWithErrorProbability() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withOmitGrpcStatus(true)
                .withErrorProbability(1.0)
                .withErrorStatusCode("UNAVAILABLE"));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        // omitGrpcStatus overrides the status code from the fault
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
        assertThat(channel.readInbound(), is(nullValue()));
    }

    // --- corruptGrpcStatus tests ---

    @Test
    public void corruptGrpcStatusProducesResponseWithNonNumericStatusValue() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile().withCorruptGrpcStatus(true));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(200));
        // grpc-status must be an integer per spec; "malformed" is a genuine protocol violation
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("malformed"));
        assertThat(channel.readInbound(), is(nullValue()));
    }

    @Test
    public void corruptGrpcStatusCombinedWithErrorProbability() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withCorruptGrpcStatus(true)
                .withErrorProbability(1.0)
                .withErrorStatusCode("DEADLINE_EXCEEDED")
                .withErrorMessage("timed out"));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        // corruptGrpcStatus replaces the valid status code with non-numeric "malformed"
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("malformed"));
        // message is preserved
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_MESSAGE_HEADER), is("timed out"));
        assertThat(channel.readInbound(), is(nullValue()));
    }

    // --- customTrailers tests ---

    @Test
    public void customTrailersAreInjectedOnFaultResponse() {
        Map<String, String> trailers = new LinkedHashMap<>();
        trailers.put("grpc-retry-pushback-ms", "500");
        trailers.put("x-error-detail", "overloaded");

        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withCustomTrailers(trailers));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, "grpc-retry-pushback-ms"), is("500"));
        assertThat(firstTrailer(response, "x-error-detail"), is("overloaded"));
        assertThat(channel.readInbound(), is(nullValue()));
    }

    @Test
    public void customTrailersCombinedWithErrorProbability() {
        Map<String, String> trailers = Map.of("x-debug", "chaos-injected");

        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withErrorProbability(1.0)
                .withErrorStatusCode("INTERNAL")
                .withCustomTrailers(trailers));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("13")); // INTERNAL
        assertThat(firstTrailer(response, "x-debug"), is("chaos-injected"));
        assertThat(channel.readInbound(), is(nullValue()));
    }

    // --- abortAfterMessages tests ---

    @Test
    public void abortAfterMessagesInjectsAbortedForMultiMessageRequest() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile().withAbortAfterMessages(2));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // build a body with 3 gRPC frames (messages)
        byte[] body = buildMultiFrameBody(3);
        channel.writeInbound(grpcRequest("/svc.v1.Service/StreamMethod", body));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getStatusCode(), is(200));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("10")); // ABORTED
        assertThat(channel.readInbound(), is(nullValue()));
    }

    @Test
    public void abortAfterMessagesPassesThroughWhenUnderThreshold() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile().withAbortAfterMessages(5));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // single message (1 frame) - under the threshold of 5
        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        // no fault response, request passes through
        assertThat(channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));
    }

    @Test
    public void abortAfterMessagesExactThresholdInjectsAborted() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile().withAbortAfterMessages(2));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // exactly 2 messages = threshold
        byte[] body = buildMultiFrameBody(2);
        channel.writeInbound(grpcRequest("/svc.v1.Service/StreamMethod", body));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("10")); // ABORTED
    }

    @Test
    public void abortAfterMessagesCombinedWithOmitGrpcStatus() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withAbortAfterMessages(1)
                .withOmitGrpcStatus(true));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // single message >= threshold 1 -> abort fires, but omitGrpcStatus omits the header
        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
    }

    @Test
    public void abortAfterMessagesCombinedWithCustomTrailers() {
        Map<String, String> trailers = Map.of("x-abort-reason", "too many messages");
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withAbortAfterMessages(2)
                .withCustomTrailers(trailers));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        byte[] body = buildMultiFrameBody(3);
        channel.writeInbound(grpcRequest("/svc.v1.Service/StreamMethod", body));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("10")); // ABORTED
        assertThat(firstTrailer(response, "x-abort-reason"), is("too many messages"));
    }

    @Test
    public void abortAfterMessagesRespectsCountWindow() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withAbortAfterMessages(1)
                .withSucceedFirst(1));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // first request: succeedFirst=1, so match 1 is not eligible for chaos
        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));
        assertThat("first request passes through (succeedFirst)", channel.readOutbound(), is(nullValue()));
        assertThat(channel.readInbound(), is(notNullValue()));

        // second request: match 2 is eligible, single message >= 1
        EmbeddedChannel channel2 = channelWith(chaosRegistry);
        channel2.writeInbound(grpcRequest("/svc.v1.Service/Method"));
        HttpResponse response = channel2.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("10")); // ABORTED
    }

    // --- C1: CRLF header-injection safety in handler (belt-and-braces) ---

    @Test
    public void customTrailersWithCrlfAreRejectedBySetter() {
        // The setter is the primary defense: it throws on CRLF keys/values,
        // making it impossible to construct a profile with malicious trailers
        // through the public API. The handler's defensive skip is belt-and-braces.
        try {
            GrpcChaosProfile.grpcChaosProfile().withCustomTrailers(
                Map.of("x-injected\r\nEvil", "value"));
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("CR or LF"), is(true));
        }
        try {
            GrpcChaosProfile.grpcChaosProfile().withCustomTrailers(
                Map.of("key", "value\r\nEvil: header"));
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage().contains("CR or LF"), is(true));
        }
    }

    // --- M1: abortAfterMessages with malformed/partial frame body ---

    @Test
    public void abortAfterMessagesWithMalformedFrameDoesNotThrow() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile().withAbortAfterMessages(1));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // send a body with partial/malformed gRPC frame data (truncated length prefix)
        byte[] malformedBody = new byte[]{0, 0, 0}; // too short for a gRPC frame
        channel.writeInbound(grpcRequest("/svc.v1.Service/Method", malformedBody));

        // no uncaught exception on the event loop; decode failure means messageCount=0
        // which is below threshold=1, so request passes through (no abort response)
        assertThat("no fault response from failed decode", channel.readOutbound(), is(nullValue()));
        assertThat("request passes through", channel.readInbound(), is(notNullValue()));
    }

    @Test
    public void abortAfterMessagesWithEmptyBodyDoesNotThrow() {
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile().withAbortAfterMessages(1));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        // empty body (0 bytes)
        channel.writeInbound(grpcRequest("/svc.v1.Service/Method", new byte[0]));

        assertThat("no fault response for empty body", channel.readOutbound(), is(nullValue()));
        assertThat("request passes through", channel.readInbound(), is(notNullValue()));
    }

    // --- combination tests ---

    @Test
    public void omitGrpcStatusTakesPriorityOverCorruptGrpcStatus() {
        // when both are set, omit takes priority (checked first)
        chaosRegistry.put("svc.v1.Service",
            GrpcChaosProfile.grpcChaosProfile()
                .withOmitGrpcStatus(true)
                .withCorruptGrpcStatus(true)
                .withErrorProbability(1.0));
        EmbeddedChannel channel = channelWith(chaosRegistry);

        channel.writeInbound(grpcRequest("/svc.v1.Service/Method"));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        // omitGrpcStatus wins: no grpc-status header
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
    }

    // --- helper methods ---

    /**
     * Reads a trailing header value. gRPC delivers {@code grpc-status}/{@code grpc-message}
     * (and chaos custom trailers) in the terminal trailing HEADERS frame, never in the
     * initial response headers, so these assertions read trailers rather than headers.
     */
    private static String firstTrailer(HttpResponse response, String name) {
        return GrpcToHttpResponseHandler.firstTrailer(response, name);
    }

    private HttpRequest grpcRequest(String path, byte[] body) {
        return request().withMethod("POST").withPath(path)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(body);
    }

    /**
     * Builds a body containing the given number of gRPC length-prefixed frames,
     * each with a minimal payload (single zero byte).
     */
    private byte[] buildMultiFrameBody(int messageCount) {
        // Each gRPC frame: 1 byte compressed flag + 4 bytes length + payload
        byte[] singlePayload = new byte[]{0x42}; // arbitrary payload
        byte[] frame = GrpcFrameCodec.encode(singlePayload);
        ByteBuffer buf = ByteBuffer.allocate(frame.length * messageCount);
        for (int i = 0; i < messageCount; i++) {
            buf.put(frame);
        }
        return buf.array();
    }
}

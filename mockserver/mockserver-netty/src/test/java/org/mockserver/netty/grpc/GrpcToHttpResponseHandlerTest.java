package org.mockserver.netty.grpc;

import com.google.protobuf.Descriptors;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcHealthCheckHandler;
import org.mockserver.grpc.GrpcHealthRegistry;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcWebTranslator;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.action.http.GrpcChaosRegistry;
import org.mockserver.mock.action.http.HttpQuotaRegistry;
import org.mockserver.model.GrpcChaosProfile;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpResponse;
import org.mockserver.netty.unification.PortUnificationHandler;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Paths;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.model.HttpRequest.request;

/**
 * Unit coverage for {@link GrpcToHttpResponseHandler}, the encoder that turns a matched
 * (JSON-bodied) expectation response back into gRPC wire format.
 * <p>
 * These tests pin the two contracts that issue #2419 found broken:
 * <ol>
 *   <li><strong>Conversion fires for a normal mock expectation.</strong> The matching pipeline
 *       does not propagate the internal {@code x-grpc-service}/{@code x-grpc-method} request
 *       headers onto the matched response, so conversion must fall back to the channel attribute
 *       recorded by {@link GrpcToHttpRequestHandler}. Before the fix, a documented unary
 *       expectation returned raw JSON on a stream the client expected to be framed protobuf.</li>
 *   <li><strong>{@code grpc-status} is a trailer, not a header.</strong> gRPC-over-HTTP/2
 *       requires the status in a terminal trailing HEADERS frame. Only
 *       {@code content-type: application/grpc} is a real header.</li>
 * </ol>
 * gRPC-Web is the deliberate exception: it carries the status in an in-body trailer frame, so the
 * handler must consume the trailers AND strip them, or the status would be emitted twice (and,
 * if it were only read from headers, would silently default to OK on every error).
 */
public class GrpcToHttpResponseHandlerTest {

    private static final String SERVICE = "com.example.grpc.GreetingService";
    private static final String METHOD = "Greeting";
    private static final String DESCRIPTOR = "../mockserver-core/src/test/resources/grpc/greeting.dsc";

    private final MockServerLogger mockServerLogger = new MockServerLogger();
    private GrpcProtoDescriptorStore descriptorStore;
    private GrpcJsonMessageConverter converter;
    private Descriptors.MethodDescriptor greetingMethod;

    @Before
    public void setUp() {
        descriptorStore = new GrpcProtoDescriptorStore(mockServerLogger);
        descriptorStore.loadDescriptorSetFromPath(Paths.get(DESCRIPTOR));
        converter = descriptorStore.getConverter();
        greetingMethod = descriptorStore.getMethod(SERVICE, METHOD);
    }

    private EmbeddedChannel responseOnlyChannel() {
        return new EmbeddedChannel(new GrpcToHttpResponseHandler(mockServerLogger, descriptorStore));
    }

    private EmbeddedChannel fullPipelineChannel(GrpcChaosRegistry chaosRegistry) {
        return new EmbeddedChannel(
            new GrpcToHttpResponseHandler(mockServerLogger, descriptorStore),
            new GrpcToHttpRequestHandler(
                mockServerLogger, descriptorStore,
                new GrpcHealthCheckHandler(GrpcHealthRegistry.getInstance()),
                chaosRegistry, HttpQuotaRegistry.getInstance())
        );
    }

    private static void recordServiceMethod(EmbeddedChannel channel, String service, String method) {
        recordServiceMethod(channel, null, service, method);
    }

    private static void recordServiceMethod(EmbeddedChannel channel, Integer streamId, String service, String method) {
        GrpcPendingRequests.forChannel(channel).record(streamId, service, method);
    }

    /**
     * Peeks at (does not consume) the recorded service/method for the no-stream-id (HTTP/1.1)
     * slot, so a test can assert that a path deliberately did NOT record one.
     */
    private static String[] recordedServiceMethod(EmbeddedChannel channel) {
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.forChannel(channel);
        String[] recorded = pendingRequests.consume(null);
        if (recorded != null) {
            pendingRequests.record(null, recorded[0], recorded[1]);
        }
        return recorded;
    }

    private static String firstTrailer(HttpResponse response, String name) {
        return GrpcToHttpResponseHandler.firstTrailer(response, name);
    }

    // ---- Defect A: conversion triggered by the channel attribute ----

    /**
     * The exact expectation shape from the documentation and issue #2419: the matcher carries the
     * gRPC headers, the response carries only a status code, {@code grpc-status} and a JSON body.
     * Nothing propagates {@code x-grpc-*} onto the response, so only the channel attribute can
     * make conversion fire.
     */
    @Test
    public void shouldConvertUsingChannelAttributeWhenResponseHasNoGrpcHeaders() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withBody("{\"greeting\":\"Hello World\"}"));

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        assertThat("content-type must be application/grpc",
            result.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));

        List<byte[]> messages = GrpcFrameCodec.decode(result.getBodyAsRawBytes());
        assertThat("body must be a single length-prefixed protobuf frame", messages, hasSize(1));
        assertThat(converter.toJson(messages.get(0), greetingMethod.getOutputType()),
            containsString("Hello World"));
    }

    @Test
    public void shouldEmitGrpcStatusAsTrailerNotHeader() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withBody("{\"greeting\":\"Hello World\"}"));

        HttpResponse result = channel.readOutbound();
        assertThat("grpc-status must NOT be an HTTP header",
            result.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(""));
        assertThat("grpc-status must be a trailer",
            firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
    }

    @Test
    public void shouldPreferExplicitResponseHeadersOverChannelAttribute() {
        EmbeddedChannel channel = responseOnlyChannel();
        // attribute points at a method that does not exist -- if it won, no conversion would happen
        recordServiceMethod(channel, "com.example.grpc.NoSuchService", "Nope");

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader("x-grpc-service", SERVICE)
            .withHeader("x-grpc-method", METHOD)
            .withBody("{\"greeting\":\"explicit wins\"}"));

        HttpResponse result = channel.readOutbound();
        List<byte[]> messages = GrpcFrameCodec.decode(result.getBodyAsRawBytes());
        assertThat(messages, hasSize(1));
        assertThat(converter.toJson(messages.get(0), greetingMethod.getOutputType()),
            containsString("explicit wins"));
        assertThat("internal routing headers must not leak to the client",
            result.getFirstHeader("x-grpc-service"), is(""));
    }

    /**
     * The attribute is consumed-and-cleared, so a stale value cannot convert an unrelated later
     * response on a keep-alive HTTP/1.1 connection.
     */
    @Test
    public void shouldClearChannelAttributeAfterUseSoLaterResponsesAreUnaffected() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withBody("{\"greeting\":\"first\"}"));
        HttpResponse first = channel.readOutbound();
        assertThat(GrpcFrameCodec.decode(first.getBodyAsRawBytes()), hasSize(1));

        // a subsequent, unrelated response on the same connection must pass through untouched
        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withHeader("content-type", "application/json")
            .withBody("{\"plain\":\"json\"}"));
        HttpResponse second = channel.readOutbound();
        assertThat(second.getFirstHeader("content-type"), is("application/json"));
        assertThat(second.getBodyAsString(), is("{\"plain\":\"json\"}"));
        assertThat(firstTrailer(second, GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
    }

    @Test
    public void shouldMapNonOkStatusFromStatusNameHeaderIntoTrailer() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "NOT_FOUND")
            .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "no such greeting"));

        HttpResponse result = channel.readOutbound();
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is("5"));
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_MESSAGE_HEADER), is("no such greeting"));
        assertThat("grpc-status-name is internal and must not reach the client",
            result.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER), is(""));
        assertThat(result.getFirstHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER), is(""));
    }

    /**
     * A user-authored numeric {@code grpc-status} header is honoured when no
     * {@code grpc-status-name} is present, rather than being silently overwritten with OK.
     */
    @Test
    public void shouldHonourExplicitNumericGrpcStatusHeader() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "7"));

        HttpResponse result = channel.readOutbound();
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is("7"));
    }

    /**
     * A numeric status outside the {@code GrpcStatusCode} enum must be emitted verbatim.
     * Round-tripping through {@link GrpcStatusMapper#fromCode} would collapse it to
     * {@code UNKNOWN} ("2"), silently rewriting a user simulating a non-standard or future status.
     * "7" (the case the original test used) happens to be mapped, so it could not catch this.
     */
    @Test
    public void shouldEmitUnmappedNumericGrpcStatusVerbatim() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "42"));

        HttpResponse result = channel.readOutbound();
        assertThat("an unmapped status must not be collapsed to UNKNOWN",
            firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is("42"));
    }

    // ---- per-stream isolation: concurrent HTTP/2 streams on one connection ----

    /**
     * Two responses on the same connection carrying different HTTP/2 stream ids must each pick up
     * their own record. A single-slot channel attribute would serve only one of them -- the failure
     * that made 3 of 4 concurrent real-client calls return unconverted JSON.
     * <p>
     * <strong>This is the test that carries the per-stream isolation guarantee.</strong> Verified
     * by degrading {@link GrpcPendingRequests} to ignore the stream id (reproducing the old
     * single-slot behaviour): this test fails, as does
     * {@link #shouldEvictOldestRecordsFirstAndStillConvertTheMostRecentStreams}. Note that
     * {@link #shouldNotLeakOneStreamsRecordOntoAnotherStream} still PASSES under that degradation
     * -- it guards consume-and-clear, not isolation -- so do not rely on it for this property.
     */
    @Test
    public void shouldResolveEachStreamsServiceAndMethodIndependently() {
        EmbeddedChannel channel = responseOnlyChannel();
        // both requests are recorded BEFORE either response is written, as happens on a real
        // connection with overlapping calls
        recordServiceMethod(channel, 3, SERVICE, METHOD);
        recordServiceMethod(channel, 5, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withStreamId(3).withBody("{\"greeting\":\"stream three\"}"));
        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withStreamId(5).withBody("{\"greeting\":\"stream five\"}"));

        assertThat(decodeSingleGreeting(channel.readOutbound()), containsString("stream three"));
        assertThat(decodeSingleGreeting(channel.readOutbound()), containsString("stream five"));
    }

    /**
     * A record is removed by the response for its own stream, so a later response on a different
     * stream cannot pick it up.
     * <p>
     * Guards consume-and-clear only: this would also pass under the old single-slot implementation.
     * {@link #shouldResolveEachStreamsServiceAndMethodIndependently} is the isolation guard.
     */
    @Test
    public void shouldNotLeakOneStreamsRecordOntoAnotherStream() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, 3, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withStreamId(3).withBody("{\"greeting\":\"converted\"}"));
        assertThat(decodeSingleGreeting(channel.readOutbound()), containsString("converted"));

        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withStreamId(7)
            .withHeader("content-type", "application/json")
            .withBody("{\"plain\":\"json\"}"));
        HttpResponse untouched = channel.readOutbound();
        assertThat(untouched.getFirstHeader("content-type"), is("application/json"));
        assertThat(untouched.getBodyAsString(), is("{\"plain\":\"json\"}"));
    }

    /**
     * Exchanges abandoned before a response is ever encoded (a drop-connection action, an
     * unreleased breakpoint, an exception before the write) must not accumulate without bound on a
     * long-lived HTTP/2 connection.
     */
    @Test
    public void shouldBoundRetainedRecordsForAbandonedStreams() {
        EmbeddedChannel channel = responseOnlyChannel();
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.forChannel(channel);

        for (int streamId = 1; streamId <= GrpcPendingRequests.MAX_PENDING_STREAMS * 3; streamId++) {
            pendingRequests.record(streamId, SERVICE, METHOD);
        }

        assertThat(pendingRequests.pendingStreamCount(), is(GrpcPendingRequests.MAX_PENDING_STREAMS));
    }

    /**
     * Eviction is insertion-ordered, so it discards the OLDEST records first and the most recent
     * {@code MAX_PENDING_STREAMS} streams remain convertible.
     * <p>
     * This pins the property that makes the bound safe. If eviction ever discarded a <em>live</em>
     * stream, that stream's response would silently skip conversion and go out as raw JSON — the
     * exact issue #2419 symptom, but load-dependent and therefore invisible to every sequential
     * test.
     */
    @Test
    public void shouldEvictOldestRecordsFirstAndStillConvertTheMostRecentStreams() {
        EmbeddedChannel channel = responseOnlyChannel();
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.forChannel(channel);

        int overflow = GrpcPendingRequests.MAX_PENDING_STREAMS + 10;
        for (int streamId = 1; streamId <= overflow; streamId++) {
            pendingRequests.record(streamId, SERVICE, METHOD);
        }

        assertThat("the oldest record must be the one evicted",
            pendingRequests.consume(1), is(nullValue()));

        // the newest stream is still recorded, so its response is still converted
        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withStreamId(overflow).withBody("{\"greeting\":\"survived\"}"));
        assertThat(decodeSingleGreeting(channel.readOutbound()), containsString("survived"));
    }

    /**
     * The registry's bound must stay above the concurrent-stream limit MockServer advertises (and
     * Netty enforces with {@code REFUSED_STREAM}), because that is the only reason eviction can
     * never reach a live stream. Pinned here so the two constants cannot drift apart — the bound
     * is derived, not guessed.
     */
    @Test
    public void shouldSizeThePendingBoundAboveTheAdvertisedStreamLimit() {
        assertThat("more streams may be in flight than the registry can hold",
            GrpcPendingRequests.MAX_PENDING_STREAMS,
            greaterThan(PortUnificationHandler.HTTP2_MAX_CONCURRENT_STREAMS));
    }

    /**
     * The HTTP/1.1 slot is single-shot: two overlapping records (pipelining with an asynchronous
     * action) make the slot ambiguous, and neither response is converted.
     * <p>
     * The alternative — letting the second record overwrite the first — would convert the FIRST
     * response against the SECOND request's method, producing a wrong-typed message or a
     * fabricated {@code grpc-status: 13}. Returning unconverted is strictly safer: it is the
     * pre-#2419 behaviour, visible rather than silently wrong.
     */
    @Test
    public void shouldRefuseToConvertWhenTheHttp11SlotIsAmbiguous() {
        EmbeddedChannel channel = responseOnlyChannel();
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.forChannel(channel);

        pendingRequests.record(null, SERVICE, METHOD);
        pendingRequests.record(null, "com.example.grpc.OtherService", "Other");

        assertThat("neither record may be attributed to a response",
            pendingRequests.consume(null), is(nullValue()));
        assertThat("and the slot is left empty, not wedged",
            pendingRequests.consume(null), is(nullValue()));

        // the ambiguity clears, so a later non-overlapping exchange converts normally
        pendingRequests.record(null, SERVICE, METHOD);
        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withBody("{\"greeting\":\"after ambiguity\"}"));
        assertThat(decodeSingleGreeting(channel.readOutbound()), containsString("after ambiguity"));
    }

    /**
     * An ambiguous HTTP/1.1 slot must leave the response untouched rather than convert it against
     * the wrong method.
     */
    @Test
    public void shouldPassPipelinedResponsesThroughUnconvertedRatherThanMisconvert() {
        EmbeddedChannel channel = responseOnlyChannel();
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.forChannel(channel);

        pendingRequests.record(null, SERVICE, METHOD);
        pendingRequests.record(null, "com.example.grpc.OtherService", "Other");

        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withBody("{\"greeting\":\"first\"}"));

        HttpResponse result = channel.readOutbound();
        assertThat("must not be converted against a method it may not belong to",
            result.getBodyAsString(), is("{\"greeting\":\"first\"}"));
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
    }

    // ---- issue #2988: a JSON array body is one message per element, and [] is none ----

    @Test
    public void shouldSendEmptyJsonArrayAsTrailersOnlyStreamWithNoMessagesOnHttp2() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, 5, SERVICE, "ListGreetings");

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withStreamId(5)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withBody("[]"));

        HttpResponse result = channel.readOutbound();
        assertThat("no message frame, not even a zero-length one, and no synthesized example",
            result.getBodyAsRawBytes().length, is(0));
        assertThat("an empty stream is sent Trailers-Only on HTTP/2",
            result.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is(nullValue()));
    }

    @Test
    public void shouldSendEmptyJsonArrayAsNoMessagesWithStatusTrailerOnHttp11() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, "ListGreetings");

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_HEADER, "0")
            .withBody("[]"));

        HttpResponse result = channel.readOutbound();
        assertThat(result.getBodyAsRawBytes().length, is(0));
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        assertThat(result.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
    }

    @Test
    public void shouldSendEachElementOfJsonArrayAsItsOwnMessage() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, 7, SERVICE, "ListGreetings");

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withStreamId(7)
            .withBody("[{\"greeting\":\"one\"},{\"greeting\":\"two\"}]"));

        HttpResponse result = channel.readOutbound();
        List<byte[]> messages = GrpcFrameCodec.decode(result.getBodyAsRawBytes());
        assertThat(messages, hasSize(2));
        assertThat(converter.toJson(messages.get(0), greetingMethod.getOutputType()), containsString("one"));
        assertThat(converter.toJson(messages.get(1), greetingMethod.getOutputType()), containsString("two"));
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
    }

    @Test
    public void shouldSendEmptyJsonArrayToGrpcWebAsTrailerFrameOnly() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, 9, SERVICE, "ListGreetings");

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withStreamId(9)
            .withHeader("x-grpc-web-content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE)
            .withBody("[]"));

        HttpResponse result = channel.readOutbound();
        byte[] body = result.getBodyAsRawBytes();
        assertThat("only the trailer frame, no message frame", body[0], is((byte) 0x80));
        assertThat(trailerFrameText(body), containsString("grpc-status: 0\r\n"));
        assertThat(result.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(""));
    }

    @Test
    public void shouldStillSynthesizeExampleMessageWhenNoBodyIsAuthored() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, "ListGreetings");

        channel.writeOutbound(HttpResponse.response().withStatusCode(200));

        HttpResponse result = channel.readOutbound();
        assertThat(GrpcFrameCodec.decode(result.getBodyAsRawBytes()), hasSize(1));
    }

    // ---- HTTP/2 Trailers-Only must be skipped when a user-authored trailer remains ----

    /**
     * A body-less HTTP/2 response carrying a user-authored trailer must NOT be collapsed into the
     * Trailers-Only form.
     * <p>
     * The collapse hoists {@code grpc-status} into the initial HEADERS frame on the assumption that
     * frame is end-of-stream. A {@code withTrailer(...)} (or the chaos profile's
     * {@code customTrailers}) keeps a separate trailing HEADERS frame alive, so the initial frame is
     * NOT end-of-stream: a real client reads it as ordinary headers, where {@code grpc-status} is
     * ignored, and then finds no status in the terminal frame — the call fails with
     * {@code UNKNOWN: missing GRPC status}. Adding one trailer to an error response destroyed the
     * error.
     * <p>
     * The positive rule is pinned by {@code assertDeadlineReachesTheWire}; this pins its negative.
     */
    @Test
    public void shouldNotCollapseToTrailersOnlyWhenAUserAuthoredTrailerRemains() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, 3, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withStreamId(3)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "NOT_FOUND")
            .withTrailer("x-mock-checksum", "a1b2c3"));

        HttpResponse result = channel.readOutbound();
        assertThat("grpc-status must NOT be hoisted into the initial headers while a trailer remains",
            result.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(""));
        assertThat("the status must stay in the terminal trailing HEADERS frame",
            firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.NOT_FOUND.getCode())));
        assertThat("the user-authored trailer must still reach the client",
            firstTrailer(result, "x-mock-checksum"), is("a1b2c3"));
    }

    // ---- unmatched requests must not be fabricated into a success ----

    /**
     * The 404 {@code notFoundResponse} emitted when nothing matched carries no gRPC status, so
     * resolving it as OK would let the example synthesizer invent a schema-valid body and report
     * success. It must instead surface as {@code UNIMPLEMENTED} (12) — what a real gRPC server
     * returns for an unknown method, and the gRPC spec's mapping for HTTP 404.
     */
    @Test
    public void shouldMapUnmatchedNotFoundResponseToUnimplementedRatherThanSynthesizingSuccess() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.notFoundResponse());

        HttpResponse result = channel.readOutbound();
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED.getCode())));
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_MESSAGE_HEADER), containsString("404"));
        assertThat("no message body may be fabricated for an unmatched request",
            result.getBodyAsRawBytes().length, is(0));
    }

    /**
     * An explicit gRPC status still wins over the HTTP status, so a user can deliberately author a
     * non-200 response alongside a gRPC status.
     */
    @Test
    public void shouldPreferExplicitGrpcStatusOverHttpStatusMapping() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(503)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "RESOURCE_EXHAUSTED"));

        HttpResponse result = channel.readOutbound();
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER),
            is(String.valueOf(GrpcStatusMapper.GrpcStatusCode.RESOURCE_EXHAUSTED.getCode())));
    }

    // ---- direct responses must reply on the request's own HTTP/2 stream ----

    /**
     * A health-check response is written directly by {@link GrpcToHttpRequestHandler} rather than
     * going through the matching engine, so nothing else stamps the HTTP/2 stream id on it.
     * <p>
     * Without it, {@code HttpToHttp2ConnectionHandler.getStreamId} falls back to
     * {@code connection().local().incrementAndGetNextStreamId()} and replies on a fresh
     * server-initiated stream — the client never sees a response on the stream it called on and
     * the RPC hangs until its deadline.
     */
    @Test
    public void shouldReplyOnTheRequestStreamForDirectHealthCheckResponses() {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/grpc.health.v1.Health/Check")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withStreamId(5)
            .withBody(GrpcFrameCodec.encode(new byte[0])));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat("must reply on the stream the request arrived on",
            response.getStreamId(), is(5));
    }

    /**
     * The same applies to a chaos fault response, which is also written directly.
     */
    @Test
    public void shouldReplyOnTheRequestStreamForDirectChaosFaultResponses() {
        GrpcChaosRegistry chaosRegistry = new GrpcChaosRegistry(System::currentTimeMillis);
        chaosRegistry.put(SERVICE, GrpcChaosProfile.grpcChaosProfile()
            .withErrorStatusCode("UNAVAILABLE").withErrorProbability(1.0));
        EmbeddedChannel channel = fullPipelineChannel(chaosRegistry);

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withStreamId(9)
            .withBody(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType()))));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getStreamId(), is(9));
    }

    /**
     * And to a gRPC-Web direct response, whose request is rebuilt by {@code translateGrpcWebRequest}
     * — that uses {@code clone()}, which preserves the stream id, so the same stamping works.
     */
    @Test
    public void shouldReplyOnTheRequestStreamForDirectGrpcWebResponses() {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/grpc.health.v1.Health/Check")
            .withHeader("content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE)
            .withStreamId(11)
            .withBody(GrpcFrameCodec.encode(new byte[0])));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getStreamId(), is(11));
    }

    private String decodeSingleGreeting(HttpResponse response) {
        List<byte[]> messages = GrpcFrameCodec.decode(response.getBodyAsRawBytes());
        assertThat(messages, hasSize(1));
        return converter.toJson(messages.get(0), greetingMethod.getOutputType());
    }

    // ---- gRPC-Web: status travels in the body, and the trailers are stripped ----

    @Test
    public void shouldEmbedStatusInGrpcWebBodyAndLeaveNoRealTrailers() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader("x-grpc-web-content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE)
            .withBody("{\"greeting\":\"Hello Web\"}"));

        HttpResponse result = channel.readOutbound();
        assertThat(result.getFirstHeader("content-type"), is(GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE));
        assertThat("gRPC-Web carries the status in the body, so no real HTTP trailers may remain",
            result.getTrailerMultimap() == null || result.getTrailerMultimap().isEmpty(), is(true));
        assertThat(result.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(""));
        assertThat(trailerFrameText(result.getBodyAsRawBytes()), containsString("grpc-status: 0\r\n"));
    }

    /**
     * Chaos {@code customTrailers} must reach a gRPC-Web client through the in-body trailer frame.
     * <p>
     * Once {@code buildFaultResponse} emits them as real HTTP trailers, they are unreachable on
     * this path unless folded into the frame: browser {@code fetch}/XHR do not expose HTTP
     * trailers, and the frame is the only trailer channel a gRPC-Web client reads. Leaving them as
     * real trailers loses them on both routes at once.
     */
    @Test
    public void shouldFoldCustomTrailersIntoTheGrpcWebTrailerFrame() {
        GrpcChaosRegistry chaosRegistry = new GrpcChaosRegistry(System::currentTimeMillis);
        chaosRegistry.put(SERVICE, GrpcChaosProfile.grpcChaosProfile()
            .withErrorStatusCode("UNAVAILABLE")
            .withErrorProbability(1.0)
            .withCustomTrailers(java.util.Collections.singletonMap("grpc-retry-pushback-ms", "500")));
        EmbeddedChannel channel = fullPipelineChannel(chaosRegistry);

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE)
            .withBody(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType()))));

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        assertThat(result.getFirstHeader("content-type"), is(GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE));

        String trailerFrame = trailerFrameText(result.getBodyAsRawBytes());
        assertThat("the status must be in the frame", trailerFrame, containsString("grpc-status: 14\r\n"));
        assertThat("a custom trailer a gRPC-Web client cannot otherwise read must be in the frame too",
            trailerFrame, containsString("grpc-retry-pushback-ms: 500\r\n"));
        assertThat("nothing may be left as a real HTTP trailer, where it would be unreachable",
            result.getTrailerMultimap() == null || result.getTrailerMultimap().isEmpty(), is(true));
    }

    /**
     * Regression guard: once grpc-status became a trailer, a gRPC-Web path that still read it from
     * headers would find nothing and {@code GrpcWebTranslator.buildTrailerFrame} would default to
     * {@code "0"} -- meaning every gRPC-Web RPC would report OK even on error.
     */
    @Test
    public void shouldPropagateNonOkStatusIntoGrpcWebTrailerFrame() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "PERMISSION_DENIED")
            .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, "denied")
            .withHeader("x-grpc-web-content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE));

        HttpResponse result = channel.readOutbound();
        String trailerText = trailerFrameText(result.getBodyAsRawBytes());
        assertThat("must NOT silently degrade to grpc-status: 0", trailerText, containsString("grpc-status: 7\r\n"));
        assertThat(trailerText, containsString("grpc-message: denied\r\n"));
        assertThat(result.getTrailerMultimap() == null || result.getTrailerMultimap().isEmpty(), is(true));
    }

    /**
     * The message must be percent-encoded EXACTLY ONCE on the gRPC-Web path.
     * <p>
     * {@code setGrpcTrailers} already encodes, and {@code buildTrailerFrame} encodes again, so
     * passing the trailer value straight through double-encoded it: an authored
     * {@code "quota 50% exceeded"} reached the browser as {@code "quota 50%25 exceeded"} after its
     * single decode. Note the two fixtures nearby ({@code "no such greeting"}, {@code "denied"}) are
     * idempotent under encode/decode and so cannot see this at all -- the guard that does is
     * {@code shouldPercentEncodeGrpcWebMessageExactlyOnce} below.
     * <p>
     * This case widens that guard along the axes it does not cover: the {@code %NN} escape form
     * (which a lenient decoder treats differently from a bare {@code %}), astral-plane characters,
     * and a multi-line message -- and it asserts the frame's structural invariants (line count, no
     * {@code US_ASCII} substitution) rather than only the round-trip.
     */
    @Test
    public void shouldEncodeGrpcWebMessageExactlyOnce() {
        assertGrpcWebMessageSurvivesASingleDecode("quota 50% exceeded");
        assertGrpcWebMessageSurvivesASingleDecode("invalid escape %41 in pattern");
        assertGrpcWebMessageSurvivesASingleDecode("paiement refusé — solde insuffisant");
        assertGrpcWebMessageSurvivesASingleDecode("service unavailable 🚫 retry later");
        assertGrpcWebMessageSurvivesASingleDecode("validation failed:\r\n - name is required");
    }

    /**
     * Same contract on the base64 text variant, which browsers use when a proxy would mangle binary.
     */
    @Test
    public void shouldEncodeGrpcWebTextMessageExactlyOnce() {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);
        String message = "quota 50% exceeded — café 🚫";

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "RESOURCE_EXHAUSTED")
            .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, message)
            .withHeader("x-grpc-web-content-type", "application/grpc-web-text"));

        HttpResponse result = channel.readOutbound();
        byte[] decodedBody = java.util.Base64.getDecoder().decode(result.getBodyAsRawBytes());
        String trailerText = trailerFrameText(decodedBody);
        assertThat(GrpcStatusMapper.percentDecodeMessage(rawGrpcMessageFrom(trailerText)), is(message));
    }

    private void assertGrpcWebMessageSurvivesASingleDecode(String message) {
        EmbeddedChannel channel = responseOnlyChannel();
        recordServiceMethod(channel, SERVICE, METHOD);

        channel.writeOutbound(HttpResponse.response()
            .withStatusCode(200)
            .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "PERMISSION_DENIED")
            .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, message)
            .withHeader("x-grpc-web-content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE));

        HttpResponse result = channel.readOutbound();
        String trailerText = trailerFrameText(result.getBodyAsRawBytes());

        assertThat("the status must not be corrupted by the message: " + message,
            trailerText, containsString("grpc-status: 7\r\n"));
        assertThat("a CRLF in the message must not inject a second trailer line: " + message,
            trailerText.split("\r\n").length, is(2));
        assertThat("US_ASCII framing turns unencoded non-ASCII into '?': " + message,
            trailerText, not(containsString("?")));
        assertThat("the client's single percent-decode must recover the authored message exactly",
            GrpcStatusMapper.percentDecodeMessage(rawGrpcMessageFrom(trailerText)), is(message));
    }

    // ---- direct-response paths must not be double-framed ----

    /**
     * Health check, reflection and chaos short-circuit in {@code channelRead0} <em>before</em>
     * {@code convertGrpcRequest}, so they never record the channel attribute and their
     * already-framed bodies pass back through this handler untouched. If the attribute leaked onto
     * those paths, the response would be framed twice.
     */
    @Test
    public void shouldNotConvertHealthCheckDirectResponse() {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/grpc.health.v1.Health/Check")
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(GrpcFrameCodec.encode(new byte[0])));

        assertThat("health check must not reach expectation matching", channel.readInbound(), is(nullValue()));
        assertThat("health check must not record a service/method for response conversion",
            recordedServiceMethod(channel), is(nullValue()));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(response.getFirstHeader("content-type"), is(GrpcStatusMapper.GRPC_CONTENT_TYPE));
        assertThat("grpc-status must be a trailer here too",
            firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
        // exactly one gRPC frame -- a double conversion would nest a frame inside a frame
        assertThat(GrpcFrameCodec.decode(response.getBodyAsRawBytes()), hasSize(1));
    }

    @Test
    public void shouldNotConvertChaosFaultDirectResponse() {
        GrpcChaosRegistry chaosRegistry = new GrpcChaosRegistry(System::currentTimeMillis);
        chaosRegistry.put(SERVICE, GrpcChaosProfile.grpcChaosProfile()
            .withErrorStatusCode("UNAVAILABLE").withErrorProbability(1.0));
        EmbeddedChannel channel = fullPipelineChannel(chaosRegistry);

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType()))));

        assertThat(channel.readInbound(), is(nullValue()));
        assertThat("chaos must not record a service/method for response conversion",
            recordedServiceMethod(channel), is(nullValue()));

        HttpResponse response = channel.readOutbound();
        assertThat(response, is(notNullValue()));
        assertThat(firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER), is("14"));
        assertThat("a fault response carries no message body", response.getBodyAsRawBytes().length, is(0));
    }

    /**
     * The mock-matching path DOES record the attribute -- this is what makes the documented unary
     * expectation work end-to-end.
     */
    @Test
    public void shouldRecordServiceAndMethodOnTheMockMatchingPath() {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withBody(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType()))));

        assertThat("request must reach expectation matching", channel.readInbound(), is(notNullValue()));
        String[] recorded = recordedServiceMethod(channel);
        assertThat(recorded, is(notNullValue()));
        assertThat(recorded[0], is(SERVICE));
        assertThat(recorded[1], is(METHOD));
    }

    // ---- helpers ----

    /**
     * Extracts the text of the gRPC-Web trailer frame (flag byte {@code 0x80}) from a response body.
     */
    private static String trailerFrameText(byte[] body) {
        ByteBuffer buf = ByteBuffer.wrap(body);
        while (buf.remaining() >= 5) {
            byte flag = buf.get();
            int length = buf.getInt();
            if ((flag & 0x80) != 0) {
                byte[] trailerBody = new byte[length];
                buf.get(trailerBody);
                return new String(trailerBody, StandardCharsets.US_ASCII);
            }
            buf.position(buf.position() + length);
        }
        throw new AssertionError("no gRPC-Web trailer frame found in response body");
    }

    /**
     * Reads the still-percent-encoded {@code grpc-message} line from the trailer frame, the way a
     * gRPC-Web client's trailer parser does before applying its own single decode.
     */
    private static String rawGrpcMessageFrom(String trailerText) {
        String prefix = GrpcStatusMapper.GRPC_MESSAGE_HEADER + ": ";
        for (String line : trailerText.split("\r\n")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length());
            }
        }
        throw new AssertionError("no grpc-message trailer line in: " + trailerText);
    }

    // ---- grpc-timeout: the deadline response must actually reach the wire ----

    /**
     * The synthesized DEADLINE_EXCEEDED response must be WRITTEN, not swallowed.
     * <p>
     * {@code GrpcToHttpResponseHandler} sits upstream of {@code GrpcToHttpRequestHandler} on the
     * outbound path, so the deadline response flows through {@code encode()} itself. Without the
     * marker bypass it matched the "drop the late response" check — the deadline response was
     * discarded AND the flag cleared, so the delayed real response was written normally: the exact
     * inverse of the documented behaviour, with no server-side DEADLINE_EXCEEDED ever emitted.
     * <p>
     * The real-client test cannot catch this: grpc-java raises DEADLINE_EXCEEDED locally at the
     * same instant regardless of what the server sends. Only an outbound assertion can.
     */
    @Test
    public void shouldWriteDeadlineExceededToTheWireForUnaryHttp2() {
        assertDeadlineReachesTheWire(3);
    }

    @Test
    public void shouldWriteDeadlineExceededToTheWireForUnaryHttp11() {
        assertDeadlineReachesTheWire(null);
    }

    /**
     * The deadline response must reach a gRPC-Web client in a form it can actually parse.
     * <p>
     * The marker bypass originally returned before the gRPC-Web re-framing tail, so the deadline
     * response went out as {@code content-type: application/grpc} with REAL HTTP trailers (which
     * browsers cannot read), no in-body trailer frame, and the internal
     * {@code x-grpc-web-content-type} header leaked to the client. gRPC-Web runs over HTTP/1.1, so
     * this is the mainstream browser scenario — and it was missed for several rounds because the
     * other deadline tests only ever exercise {@code application/grpc}.
     */
    @Test
    public void shouldWriteDeadlineExceededAsGrpcWebForABrowserClient() {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE)
            .withHeader(org.mockserver.grpc.GrpcTimeout.GRPC_TIMEOUT_HEADER, "100m")
            .withBody(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType()))));
        channel.readInbound();

        channel.advanceTimeBy(300, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        assertThat("a gRPC-Web client must receive a gRPC-Web content-type",
            result.getFirstHeader("content-type"), is(GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE));
        assertThat("the internal gRPC-Web marker must not reach the client",
            result.getFirstHeader("x-grpc-web-content-type"), is(""));
        assertThat("gRPC-Web carries the status in the body, so no real HTTP trailers may remain",
            result.getTrailerMultimap() == null || result.getTrailerMultimap().isEmpty(), is(true));
        assertThat("the status must be in the in-body trailer frame",
            trailerFrameText(result.getBodyAsRawBytes()),
            containsString("grpc-status: " + GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode()));
    }

    private void assertDeadlineReachesTheWire(Integer streamId) {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        org.mockserver.model.HttpRequest request = request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(org.mockserver.grpc.GrpcTimeout.GRPC_TIMEOUT_HEADER, "100m")
            .withBody(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType())));
        if (streamId != null) {
            request.withStreamId(streamId);
        }
        channel.writeInbound(request);
        assertThat("the request must still reach the matching engine", channel.readInbound(), is(notNullValue()));

        channel.advanceTimeBy(300, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        HttpResponse deadlineResponse = channel.readOutbound();
        assertThat("the deadline response must be written, not swallowed",
            deadlineResponse, is(notNullValue()));
        String expectedStatus = String.valueOf(GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode());
        if (streamId != null) {
            // HTTP/2: a body-less gRPC response is collapsed to Trailers-Only, so the status rides
            // in the single end-of-stream HEADERS frame rather than a separate trailing frame
            assertThat("HTTP/2 must use the Trailers-Only form",
                deadlineResponse.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(expectedStatus));
            assertThat("Trailers-Only means no separate trailing HEADERS frame",
                deadlineResponse.getTrailerMultimap() == null || deadlineResponse.getTrailerMultimap().isEmpty(),
                is(true));
        } else {
            // HTTP/1.1 has no Trailers-Only, and grpc-status in the headers is the #2419 defect
            assertThat("HTTP/1.1 must keep grpc-status as a real trailer",
                firstTrailer(deadlineResponse, GrpcStatusMapper.GRPC_STATUS_HEADER), is(expectedStatus));
            assertThat("grpc-status must NOT be an HTTP header on HTTP/1.1",
                deadlineResponse.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER), is(""));
        }
        assertThat("the internal marker must never reach the client",
            deadlineResponse.getFirstHeader(GrpcToHttpResponseHandler.DEADLINE_RESPONSE_HEADER), is(""));

        // the late real response must then be dropped, not written onto the terminated stream
        HttpResponse late = HttpResponse.response().withStatusCode(200)
            .withBody("{\"greeting\":\"too late\"}");
        if (streamId != null) {
            late.withStreamId(streamId);
        }
        channel.writeOutbound(late);
        assertThat("a response arriving after the deadline must be dropped",
            channel.readOutbound(), is(nullValue()));
    }

    /**
     * A streaming exchange must not leave its record behind and poison the next unary call.
     * <p>
     * The streaming path writes raw Netty objects, which {@code MessageToMessageEncoder<HttpResponse>}
     * never matches, so {@code encode()} — and {@code consume()} — never run for it. On HTTP/1.1 the
     * orphaned record made the single-shot slot ambiguous, so the NEXT unary response on the same
     * keep-alive connection was returned as unframed JSON with no grpc-status: issue #2419 again,
     * reachable from gRPC-Web (which runs over HTTP/1.1).
     */
    @Test
    public void shouldNotPoisonTheNextUnaryCallAfterAStreamingExchangeOnHttp11() {
        EmbeddedChannel channel = responseOnlyChannel();
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.forChannel(channel);

        // a streaming RPC records a pending request, then writes RAW Netty objects
        pendingRequests.record(null, SERVICE, METHOD);
        channel.writeAndFlush(new io.netty.handler.codec.http.DefaultHttpResponse(
            io.netty.handler.codec.http.HttpVersion.HTTP_1_1,
            io.netty.handler.codec.http.HttpResponseStatus.OK));
        channel.readOutbound();

        // the next unary call on the same connection must still be converted
        pendingRequests.record(null, SERVICE, METHOD);
        channel.writeOutbound(HttpResponse.response().withStatusCode(200)
            .withBody("{\"greeting\":\"after streaming\"}"));

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        assertThat("the unary response after a streaming exchange must still be framed protobuf",
            decodeSingleGreeting(result), containsString("after streaming"));
        assertThat(firstTrailer(result, GrpcStatusMapper.GRPC_STATUS_HEADER), is("0"));
    }

    // ---- gRPC-Web: grpc-message must be encoded exactly once ----

    /**
     * The trailer frame must carry a SINGLY percent-encoded message, so one decode by the client
     * recovers exactly what was authored.
     * <p>
     * {@code setGrpcTrailers} already encodes, and {@code buildTrailerFrame} encodes again, so
     * passing the trailer straight through double-encoded it: an authored {@code quota 50% exceeded}
     * reached the client as {@code quota 50%25 exceeded}. Every pre-existing gRPC-Web fixture used
     * plain ASCII with no {@code %}, so the test data itself hid the bug.
     */
    @Test
    public void shouldPercentEncodeGrpcWebMessageExactlyOnce() {
        for (String authored : new String[]{
            "quota 50% exceeded",
            "paiement refusé",
            "denied\r\ngrpc-status: 0"
        }) {
            EmbeddedChannel channel = responseOnlyChannel();
            recordServiceMethod(channel, SERVICE, METHOD);

            channel.writeOutbound(HttpResponse.response()
                .withStatusCode(200)
                .withHeader("x-grpc-web-content-type", GrpcWebTranslator.GRPC_WEB_CONTENT_TYPE)
                .withHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER, "RESOURCE_EXHAUSTED")
                .withHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER, authored));

            HttpResponse result = channel.readOutbound();
            String frame = trailerFrameText(result.getBodyAsRawBytes());
            String encoded = frame.substring(frame.indexOf("grpc-message: ") + "grpc-message: ".length());
            encoded = encoded.substring(0, encoded.indexOf("\r\n"));

            assertThat("a single client-side decode must recover the authored message: " + authored,
                GrpcStatusMapper.percentDecodeMessage(encoded), is(authored));
            assertThat("the frame must remain CRLF-safe",
                encoded.indexOf('\r') + encoded.indexOf('\n'), is(-2));
        }
    }

    /**
     * The netty handler's marker constant and the core definition must be the same string.
     * <p>
     * They were independently hard-coded in the two modules with nothing pinning them equal, so a
     * change to one would silently stop the other matching — the marker would no longer be
     * stripped on ingress, or would leak to an HTTP/3 client.
     */
    @Test
    public void shouldPinTheDeadlineMarkerConstantAcrossModules() {
        assertThat(GrpcToHttpResponseHandler.DEADLINE_RESPONSE_HEADER,
            is(org.mockserver.grpc.GrpcResponseStatusResolver.GRPC_DEADLINE_RESPONSE_MARKER));
        assertThat("the marker must be classed as gRPC protocol metadata",
            org.mockserver.grpc.GrpcResponseStatusResolver.isGrpcProtocolMetadata(
                GrpcToHttpResponseHandler.DEADLINE_RESPONSE_HEADER), is(true));
    }

    /**
     * A client-supplied deadline marker must not let a request skip protobuf conversion.
     * <p>
     * {@code encode} takes an early return for the marker, so a request carrying it through to the
     * matched response would bypass conversion entirely. It is stripped on ingress.
     */
    @Test
    public void shouldStripAClientSuppliedDeadlineMarkerFromInboundRequests() {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE)
            .withHeader(GrpcToHttpResponseHandler.DEADLINE_RESPONSE_HEADER, "true")
            .withBody(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType()))));

        org.mockserver.model.HttpRequest converted = channel.readInbound();
        assertThat(converted, is(notNullValue()));
        assertThat("a client-supplied deadline marker must not survive into the matching pipeline",
            converted.getFirstHeader(GrpcToHttpResponseHandler.DEADLINE_RESPONSE_HEADER), is(""));
    }

    /**
     * The {@code -text} gRPC-Web variant takes the same deadline path modulo one base64 boolean.
     */
    @Test
    public void shouldWriteDeadlineExceededAsGrpcWebTextForABrowserClient() {
        EmbeddedChannel channel = fullPipelineChannel(new GrpcChaosRegistry(System::currentTimeMillis));

        channel.writeInbound(request()
            .withMethod("POST")
            .withPath("/" + SERVICE + "/" + METHOD)
            .withHeader("content-type", GrpcWebTranslator.GRPC_WEB_TEXT_CONTENT_TYPE)
            .withHeader(org.mockserver.grpc.GrpcTimeout.GRPC_TIMEOUT_HEADER, "100m")
            .withBody(java.util.Base64.getEncoder().encode(GrpcFrameCodec.encode(
                converter.toProtobuf("{\"name\":\"World\"}", greetingMethod.getInputType())))));
        channel.readInbound();

        channel.advanceTimeBy(300, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();

        HttpResponse result = channel.readOutbound();
        assertThat(result, is(notNullValue()));
        assertThat(result.getFirstHeader("content-type"), is(GrpcWebTranslator.GRPC_WEB_TEXT_CONTENT_TYPE));
        assertThat("the -text variant is base64 encoded end to end",
            trailerFrameText(java.util.Base64.getDecoder().decode(result.getBodyAsRawBytes())),
            containsString("grpc-status: " + GrpcStatusMapper.GrpcStatusCode.DEADLINE_EXCEEDED.getCode()));
    }
}

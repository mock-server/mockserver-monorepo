package org.mockserver.netty.grpc;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageEncoder;
import com.google.protobuf.Descriptors;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcResponseBody;
import org.mockserver.grpc.GrpcResponseStatusResolver;
import org.mockserver.grpc.GrpcStatusMapper;
import org.mockserver.grpc.GrpcWebTranslator;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;
import org.slf4j.event.Level;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Converts a matched (JSON-bodied) {@link HttpResponse} back into a gRPC wire response:
 * a length-prefixed protobuf frame plus a {@code grpc-status} <strong>trailer</strong>.
 * <p>
 * <strong>Service/method resolution.</strong> The gRPC service and method names are taken
 * from {@code x-grpc-service}/{@code x-grpc-method} response headers when present (the
 * forward-proxy path stamps them via
 * {@link org.mockserver.grpc.GrpcForwardTranslator#decodeResponseFromUpstream}, and users may
 * set them explicitly). Otherwise they fall back to the per-connection
 * {@link GrpcPendingRequests} registry populated by {@link GrpcToHttpRequestHandler} when it
 * decoded the request, looked up by the response's HTTP/2 stream id. The matching pipeline does
 * not propagate the internal {@code x-grpc-*} request headers onto the matched response, so
 * without this fallback a normal mock expectation would return raw JSON on a stream the client
 * expects to be framed protobuf (issue #2419). The same trap is documented on the HTTP/3 path in
 * {@link org.mockserver.netty.http3.Http3GrpcResponseWriter}.
 * <p>
 * <strong>Only successful responses are converted.</strong> A non-2xx response that carries no
 * explicit gRPC status did not come from a matched gRPC expectation -- most commonly the 404
 * {@code notFoundResponse} produced when nothing matched. Converting it would invent a
 * schema-valid example body and report {@code OK}, so a typo'd path would return a plausible
 * green response with no client-side indicator. Such responses are instead mapped to a gRPC
 * error status per the gRPC-over-HTTP/2 HTTP-status mapping (404 becomes
 * {@code UNIMPLEMENTED}, which is what a real gRPC server returns for an unknown method).
 * <p>
 * <strong>Trailers, not headers.</strong> Per gRPC-over-HTTP/2 (and HTTP/3), a unary response
 * must deliver {@code grpc-status}/{@code grpc-message} in a terminal trailing HEADERS frame,
 * never in the initial response headers. Only {@code content-type: application/grpc} is a real
 * header. gRPC-Web is the exception: it carries the status in an in-body trailer frame, so
 * {@link #convertToGrpcWebResponse} consumes the trailers and strips them from the response.
 */
@ChannelHandler.Sharable
public class GrpcToHttpResponseHandler extends MessageToMessageEncoder<HttpResponse> {

    /**
     * Internal marker identifying the synthesized DEADLINE_EXCEEDED response, so {@link #encode}
     * lets it through rather than treating it as the late response it is meant to pre-empt.
     * Stripped before the response reaches the client.
     */
    static final String DEADLINE_RESPONSE_HEADER = GrpcResponseStatusResolver.GRPC_DEADLINE_RESPONSE_MARKER;

    private final MockServerLogger mockServerLogger;
    private final GrpcProtoDescriptorStore descriptorStore;

    public GrpcToHttpResponseHandler(MockServerLogger mockServerLogger, GrpcProtoDescriptorStore descriptorStore) {
        this.mockServerLogger = mockServerLogger;
        this.descriptorStore = descriptorStore;
    }

    /**
     * Releases the pending record when a response is written that this encoder will never see.
     * <p>
     * {@code MessageToMessageEncoder<HttpResponse>} only matches the MockServer <em>model</em>
     * type. The gRPC streaming path ({@code GrpcStreamResponseActionHandler}) writes raw Netty
     * {@code DefaultHttpResponse}/{@code HttpContent} objects, so {@link #encode} -- and therefore
     * {@code consume()} -- never runs for a streaming exchange, leaving its record orphaned. On
     * HTTP/1.1 that record then poisons the single-shot slot: the next unary call on the same
     * keep-alive connection is marked ambiguous, {@code consume} returns null, and its response
     * goes out as unframed JSON with no {@code grpc-status} -- the exact issue #2419 shape. It also
     * left the unary deadline timer armed alongside the streaming one, so both could terminate the
     * stream.
     * <p>
     * gRPC-Web runs over HTTP/1.1, so this is reachable in the primary browser scenario.
     */
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, io.netty.channel.ChannelPromise promise) throws Exception {
        if (msg instanceof HttpResponse) {
            // The late response arriving after the client's deadline already terminated this
            // stream. It MUST be discarded here rather than in encode(): MessageToMessageEncoder
            // throws EncoderException("must produce at least one message") when encode() adds
            // nothing to `out`, so dropping there raises an exception on the pipeline instead of
            // silently discarding.
            HttpResponse response = (HttpResponse) msg;
            if (response.getFirstHeader(DEADLINE_RESPONSE_HEADER).isEmpty()) {
                GrpcPendingRequests pendingRequests = GrpcPendingRequests.existingForChannel(ctx.channel());
                if (pendingRequests != null && pendingRequests.consumeDeadlineExceeded(response.getStreamId())) {
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.INFO)
                                .setMessageFormat("dropping gRPC response for a stream that already ended with"
                                    + " DEADLINE_EXCEEDED - the configured delay outlasted the client's grpc-timeout")
                        );
                    }
                    io.netty.util.ReferenceCountUtil.release(msg);
                    promise.trySuccess();
                    return;
                }
            }
        } else if (msg instanceof io.netty.handler.codec.http.HttpResponse) {
            GrpcPendingRequests pendingRequests = GrpcPendingRequests.existingForChannel(ctx.channel());
            if (pendingRequests != null) {
                // the raw Netty response carries the stream id as the HTTP/2 extension header
                String rawStreamId = ((io.netty.handler.codec.http.HttpResponse) msg).headers()
                    .get(io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text());
                Integer streamId = null;
                if (rawStreamId != null) {
                    try {
                        streamId = Integer.valueOf(rawStreamId.trim());
                    } catch (NumberFormatException ignored) {
                        // not a usable stream id -- fall back to the HTTP/1.1 slot
                    }
                }
                pendingRequests.consume(streamId);
            }
        }
        super.write(ctx, msg, promise);
    }

    @Override
    protected void encode(ChannelHandlerContext ctx, HttpResponse response, List<Object> out) {
        String grpcWebContentType = response.getFirstHeader("x-grpc-web-content-type");
        String grpcService = response.getFirstHeader("x-grpc-service");
        String grpcMethod = response.getFirstHeader("x-grpc-method");

        // Always consume the pending record for THIS stream, even when explicit response headers
        // win, so it cannot leak onto a subsequent response. The lookup is keyed by stream id
        // because in the default configuration both gRPC handlers sit on the connection-level
        // pipeline shared by every multiplexed stream -- see GrpcPendingRequests.
        //
        // Deliberately does NOT create the registry: this runs for every outbound response on a
        // gRPC-enabled server, including connections that never carry a gRPC request.
        GrpcPendingRequests pendingRequests = GrpcPendingRequests.existingForChannel(ctx.channel());

        // The synthesized DEADLINE_EXCEEDED response is written from a context UPSTREAM of this
        // handler, so it flows through encode() itself. Strip the internal marker (write() has
        // already let it past the drop check) so it never reaches the client.
        //
        // It still needs the gRPC-Web re-framing tail: returning early here emitted
        // content-type: application/grpc with REAL HTTP trailers and no in-body trailer frame, which
        // no gRPC-Web client can parse -- and leaked the internal x-grpc-web-content-type header to
        // the client. gRPC-Web runs over HTTP/1.1, so that is the mainstream browser scenario.
        if (!response.getFirstHeader(DEADLINE_RESPONSE_HEADER).isEmpty()) {
            response.removeHeader(DEADLINE_RESPONSE_HEADER);
            out.add(isEmpty(grpcWebContentType)
                ? asTrailersOnlyIfHttp2(response)
                : convertToGrpcWebResponse(response, grpcWebContentType));
            return;
        }
        String[] pending = pendingRequests == null ? null : pendingRequests.consume(response.getStreamId());
        if (isEmpty(grpcService) || isEmpty(grpcMethod)) {
            if (pending != null) {
                grpcService = pending[0];
                grpcMethod = pending[1];
            }
        }
        // The gRPC-Web request content-type marker lives on the request only, so a matched-expectation
        // response does not carry it. Recover it from the per-stream record (element 2) so the response
        // is re-framed as gRPC-Web -- grpc-status in an in-body trailer frame the browser client can read
        // -- rather than going out as application/grpc with unreadable HTTP trailers.
        if (isEmpty(grpcWebContentType) && pending != null && pending.length > 2 && !isEmpty(pending[2])) {
            grpcWebContentType = pending[2];
        }

        if (!isEmpty(grpcService) && !isEmpty(grpcMethod)) {
            try {
                HttpResponse converted = convertToGrpcResponse(response, grpcService, grpcMethod);
                if (!isEmpty(grpcWebContentType)) {
                    converted = convertToGrpcWebResponse(converted, grpcWebContentType);
                }
                out.add(converted);
            } catch (Exception e) {
                if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("failed to convert response to gRPC for {}/{}:{}")
                            .setArguments(grpcService, grpcMethod, e.getMessage())
                    );
                }
                // Drop the body. It is whatever failed to convert -- typically unframed JSON --
                // and advertising content-type: application/grpc over it makes a strict client
                // fail deframing BEFORE it reads the trailer, masking the INTERNAL status behind
                // an opaque protocol error. An empty body lets the status be the payload, matching
                // transportFailureResponse.
                HttpResponse errorResponse = response.clone()
                    .withStatusCode(200)
                    .withBody(new org.mockserver.model.BinaryBody(new byte[0]))
                    .replaceHeader(new Header("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE))
                    .removeHeader(GrpcStatusMapper.GRPC_STATUS_HEADER)
                    .removeHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER)
                    .removeHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER)
                    .removeHeader("x-grpc-service")
                    .removeHeader("x-grpc-method")
                    .removeHeader("x-grpc-web-content-type");
                // Generic text to the client: the exception message can carry protobuf field and
                // type names, and filesystem paths for file-backed descriptor loads. The detail is
                // in the WARN log above.
                setGrpcTrailers(
                    errorResponse,
                    String.valueOf(GrpcStatusMapper.GrpcStatusCode.INTERNAL.getCode()),
                    "failed to encode gRPC response"
                );
                if (!isEmpty(grpcWebContentType)) {
                    errorResponse = convertToGrpcWebResponse(errorResponse, grpcWebContentType);
                }
                out.add(errorResponse);
            }
        } else if (!isEmpty(grpcWebContentType)) {
            // gRPC-Web request that bypassed descriptor conversion (e.g. health check, reflection, chaos)
            out.add(convertToGrpcWebResponse(response, grpcWebContentType));
        } else if (isGrpcResponse(response)) {
            // A body-less gRPC response written directly by GrpcToHttpRequestHandler (chaos fault,
            // reflection error, decode error). These bypass convertToGrpcResponse, so they need the
            // Trailers-Only collapse applied here to match the converted paths on HTTP/2.
            out.add(asTrailersOnlyIfHttp2(response));
        } else {
            out.add(response);
        }
    }

    /**
     * Converts a standard gRPC response (with grpc-status/grpc-message as trailers)
     * into a gRPC-Web response (with trailers embedded in the body as a trailer frame).
     * <p>
     * The status is read from the trailers first, falling back to headers for callers that
     * still stamp it as a header. The consumed trailers are then <strong>stripped</strong> so
     * they are not ALSO emitted as real HTTP trailers -- gRPC-Web carries them in the body.
     */
    private HttpResponse convertToGrpcWebResponse(HttpResponse response, String grpcWebContentType) {
        String grpcStatus = firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER);
        if (isEmpty(grpcStatus)) {
            grpcStatus = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER);
        }
        String grpcMessage = firstTrailer(response, GrpcStatusMapper.GRPC_MESSAGE_HEADER);
        if (isEmpty(grpcMessage)) {
            grpcMessage = response.getFirstHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER);
        }
        // Decode before handing to buildTrailerFrame, which percent-encodes. setGrpcTrailers has
        // already encoded this value, so passing it straight through encoded it TWICE: an authored
        // "quota 50% exceeded" reached the client as "quota 50%25 exceeded" after its single
        // decode. Encode exactly once.
        grpcMessage = GrpcStatusMapper.percentDecodeMessage(grpcMessage);
        byte[] messageBody = response.getBodyAsRawBytes();
        boolean isTextVariant = GrpcWebTranslator.isGrpcWebTextContentType(grpcWebContentType);

        // Fold EVERY remaining trailer into the in-body trailer frame, not just
        // grpc-status/grpc-message. A gRPC-Web client cannot read real HTTP trailers at all
        // (browser fetch/XHR do not expose them), so any trailer left on the response is
        // unreachable -- including the chaos profile's customTrailers, which buildFaultResponse
        // now emits as real trailers.
        Map<String, String> customTrailers = remainingTrailers(response);

        byte[] grpcWebBody = GrpcWebTranslator.encodeResponseBody(
            messageBody, isEmpty(grpcStatus) ? null : grpcStatus, grpcMessage, customTrailers, isTextVariant
        );

        HttpResponse grpcWebResponse = response.clone()
            .withBody(new org.mockserver.model.BinaryBody(grpcWebBody))
            .replaceHeader(new Header("content-type", GrpcWebTranslator.responseContentType(grpcWebContentType)))
            .removeHeader(GrpcStatusMapper.GRPC_STATUS_HEADER)
            .removeHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER)
            .removeHeader("x-grpc-web-content-type");
        // every trailer is now in the body frame, so emit none as real HTTP trailers -- this also
        // avoids needlessly forcing chunked transfer-encoding on HTTP/1.1
        grpcWebResponse.withTrailers((Headers) null);
        return grpcWebResponse;
    }

    /**
     * Collects the response's trailers other than {@code grpc-status}/{@code grpc-message}, which
     * are carried separately into the gRPC-Web trailer frame.
     */
    private static Map<String, String> remainingTrailers(HttpResponse response) {
        Headers trailers = response.getTrailers();
        if (trailers == null) {
            return null;
        }
        Map<String, String> remaining = new LinkedHashMap<>();
        for (Header trailer : trailers.getEntries()) {
            String name = trailer.getName().getValue();
            if (GrpcStatusMapper.GRPC_STATUS_HEADER.equalsIgnoreCase(name)
                || GrpcStatusMapper.GRPC_MESSAGE_HEADER.equalsIgnoreCase(name)) {
                continue;
            }
            List<NottableString> values = trailer.getValues();
            if (values != null && !values.isEmpty()) {
                remaining.put(name, values.get(0).getValue());
            }
        }
        return remaining.isEmpty() ? null : remaining;
    }

    private HttpResponse convertToGrpcResponse(HttpResponse response, String serviceName, String methodName) {
        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        if (methodDescriptor == null) {
            // The request handler resolved this method (that is why a pending record exists), but
            // the descriptor is gone by the time the response is encoded -- descriptors were
            // cleared or reloaded mid-exchange. Returning the response unchanged would put raw JSON
            // on a stream the client expects framed, with no terminal status: exactly the #2419
            // shape, reported to the client as "Missing grpc-status". Emit a proper error instead,
            // matching the sibling conversion-failure path in encode().
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("no gRPC descriptor for {}/{} when encoding the response"
                            + " (descriptors changed mid-exchange?) - returning UNIMPLEMENTED")
                        .setArguments(serviceName, methodName)
                );
            }
            HttpResponse missingDescriptorResponse = stripGrpcMetadata(response.clone())
                .withStatusCode(200)
                .withReasonPhrase(null)
                .withBody(new org.mockserver.model.BinaryBody(new byte[0]));
            setGrpcTrailers(
                missingDescriptorResponse,
                String.valueOf(GrpcStatusMapper.GrpcStatusCode.UNIMPLEMENTED.getCode()),
                "no gRPC descriptor loaded for the requested method"
            );
            return asTrailersOnlyIfHttp2(missingDescriptorResponse);
        }

        GrpcResponseStatusResolver.ResolvedStatus resolved = GrpcResponseStatusResolver.resolve(response);
        if (resolved.isTransportFailure()) {
            // Not a matched gRPC expectation -- typically the 404 notFoundResponse. Report a
            // gRPC error rather than synthesizing a fabricated OK response. See the class javadoc.
            return transportFailureResponse(response, resolved);
        }
        String grpcStatus = resolved.code();
        String grpcMessage = resolved.message();

        String bodyString = response.getBodyAsString();
        if (bodyString == null || bodyString.isEmpty()) {
            // No hand-authored response body: for a successful (OK) response, synthesize a
            // schema-valid example message from the loaded descriptor's output type so the
            // client receives a well-formed, type-correct protobuf message rather than an
            // empty frame. Non-OK statuses keep the empty-body behaviour (the status is the
            // payload). If synthesis fails for any reason, fall back to an empty response.
            if (resolved.isOk()) {
                try {
                    String synthesized = descriptorStore.getExampleSynthesizer()
                        .synthesizeJson(methodDescriptor.getOutputType(), descriptorStore.getConverter());
                    if (synthesized != null && !synthesized.isEmpty()) {
                        bodyString = synthesized;
                    }
                } catch (Exception e) {
                    if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setMessageFormat("failed to synthesize gRPC example response for {}/{}:{}")
                                .setArguments(serviceName, methodName, e.getMessage())
                        );
                    }
                }
            }
            if (bodyString == null || bodyString.isEmpty()) {
                HttpResponse emptyResponse = stripGrpcMetadata(response.clone())
                    .withStatusCode(200);
                setGrpcTrailers(emptyResponse, grpcStatus, grpcMessage);
                return asTrailersOnlyIfHttp2(emptyResponse);
            }
        }

        byte[] grpcFrames = GrpcResponseBody.toFrames(bodyString, methodDescriptor.getOutputType(), descriptorStore.getConverter());

        HttpResponse grpcResponse = stripGrpcMetadata(response.clone())
            .withStatusCode(200)
            .withBody(new org.mockserver.model.BinaryBody(grpcFrames));
        setGrpcTrailers(grpcResponse, grpcStatus, grpcMessage);
        // a JSON array with no elements is a stream with no messages
        return grpcFrames.length == 0 ? asTrailersOnlyIfHttp2(grpcResponse) : grpcResponse;
    }

    /**
     * Builds the gRPC response for an HTTP-level failure that carries no gRPC status of its own --
     * overwhelmingly the 404 {@code notFoundResponse} emitted when no expectation matched.
     * <p>
     * The body is dropped rather than converted (there is nothing meaningful to convert, and
     * synthesizing an example message here would fabricate a successful reply for a request that
     * matched nothing), and the HTTP status becomes 200 so the client reads the terminal trailers
     * instead of falling back to its own status inference.
     */
    private static HttpResponse transportFailureResponse(HttpResponse response, GrpcResponseStatusResolver.ResolvedStatus resolved) {
        HttpResponse failureResponse = stripGrpcMetadata(response.clone())
            .withStatusCode(200)
            .withReasonPhrase(null)
            .withBody(new org.mockserver.model.BinaryBody(new byte[0]));
        setGrpcTrailers(failureResponse, resolved.code(), resolved.message());
        return asTrailersOnlyIfHttp2(failureResponse);
    }

    /**
     * Removes the internal routing headers and any caller-supplied gRPC status headers, and
     * sets {@code content-type: application/grpc} as a real (non-duplicated) header.
     */
    private static HttpResponse stripGrpcMetadata(HttpResponse response) {
        return response
            .replaceHeader(new Header("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE))
            // tell the client which message encodings it may use on subsequent calls
            .replaceHeader(new Header(GrpcStatusMapper.GRPC_ACCEPT_ENCODING_HEADER, GrpcFrameCodec.ACCEPT_ENCODING))
            .removeHeader(GrpcStatusMapper.GRPC_STATUS_HEADER)
            .removeHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER)
            .removeHeader(GrpcStatusMapper.GRPC_STATUS_NAME_HEADER)
            .removeHeader("x-grpc-service")
            .removeHeader("x-grpc-method");
    }

    /**
     * Collapses a body-less HTTP/2 gRPC response into the gRPC <strong>Trailers-Only</strong> form:
     * a single end-of-stream HEADERS frame carrying {@code :status}, {@code content-type} and
     * {@code grpc-status}, with no DATA frame and no separate trailing HEADERS frame.
     * <p>
     * Moving the status into the headers makes
     * {@code MockServerHttpResponseToFullHttpResponse} take its no-trailers branch and emit a
     * {@code DefaultFullHttpResponse} with empty content, which
     * {@code HttpToHttp2ConnectionHandler} writes as one HEADERS frame with {@code endStream=true}
     * -- exactly Trailers-Only.
     * <p>
     * Gated on the response carrying an HTTP/2 stream id. Trailers-Only is an HTTP/2 concept, and
     * on HTTP/1.1 putting {@code grpc-status} in the headers is precisely the defect from issue
     * #2419, so HTTP/1.1 keeps real trailers. HTTP/3 already emits Trailers-Only via
     * {@code GrpcHttp3Adapter.buildTrailersOnlyFrame}; this makes HTTP/2 agree with it.
     * <p>
     * <strong>Skipped when the response carries user-authored trailing metadata.</strong>
     * Trailers-Only is only legal when there is nothing else to trail. With a custom trailer
     * present, collapsing moved {@code grpc-status} into the initial HEADERS while the custom
     * trailer kept a separate trailing HEADERS frame alive -- so the initial frame was not
     * end-of-stream, a real client read it as ordinary headers (where {@code grpc-status} is
     * ignored), and the terminal frame then carried no status at all. The call failed with
     * {@code UNKNOWN: missing GRPC status}: adding one trailer to an error response destroyed the
     * error. Keeping the status in the trailers is the correct shape whenever any trailer remains.
     */
    private static HttpResponse asTrailersOnlyIfHttp2(HttpResponse response) {
        if (response.getStreamId() == null) {
            return response;
        }
        byte[] body = response.getBodyAsRawBytes();
        if (body != null && body.length > 0) {
            return response;
        }
        String grpcStatus = firstTrailer(response, GrpcStatusMapper.GRPC_STATUS_HEADER);
        if (grpcStatus == null) {
            return response;
        }
        if (remainingTrailers(response) != null) {
            return response;
        }
        String grpcMessage = firstTrailer(response, GrpcStatusMapper.GRPC_MESSAGE_HEADER);
        removeGrpcTrailers(response);
        response.replaceHeader(new Header(GrpcStatusMapper.GRPC_STATUS_HEADER, grpcStatus));
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            response.replaceHeader(new Header(GrpcStatusMapper.GRPC_MESSAGE_HEADER, grpcMessage));
        }
        return response;
    }

    /**
     * Sets {@code grpc-status} (and {@code grpc-message} when non-empty) as response trailers,
     * replacing any that were already present so the terminal HEADERS frame carries exactly one
     * of each. The message is percent-encoded per the gRPC wire specification.
     */
    static void setGrpcTrailers(HttpResponse response, String grpcStatus, String grpcMessage) {
        removeGrpcTrailers(response);
        if (grpcStatus != null) {
            response.withTrailer(GrpcStatusMapper.GRPC_STATUS_HEADER, grpcStatus);
        }
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            // percent-encoded per the gRPC wire spec -- see GrpcStatusMapper.percentEncodeMessage
            response.withTrailer(GrpcStatusMapper.GRPC_MESSAGE_HEADER,
                GrpcStatusMapper.percentEncodeMessage(grpcMessage));
        }
    }

    private static void removeGrpcTrailers(HttpResponse response) {
        Headers trailers = response.getTrailers();
        if (trailers == null) {
            return;
        }
        trailers.remove(GrpcStatusMapper.GRPC_STATUS_HEADER);
        trailers.remove(GrpcStatusMapper.GRPC_MESSAGE_HEADER);
        if (trailers.isEmpty()) {
            // null out so getTrailerMultimap() is empty and the HTTP/1.1 mapper does not
            // needlessly force chunked transfer-encoding
            response.withTrailers((Headers) null);
        }
    }

    static String firstTrailer(HttpResponse response, String name) {
        return GrpcResponseStatusResolver.firstTrailer(response, name);
    }

    /**
     * Whether this response is one MockServer built as a gRPC response, identified by its
     * content-type. Used to decide whether the HTTP/2 Trailers-Only collapse applies; non-gRPC
     * responses on the same connection must pass through untouched.
     */
    private static boolean isGrpcResponse(HttpResponse response) {
        return GrpcStatusMapper.isGrpcContentType(response.getFirstHeader("content-type"));
    }

    private static boolean isEmpty(String value) {
        return value == null || value.isEmpty();
    }
}

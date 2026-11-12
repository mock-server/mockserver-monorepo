package org.mockserver.netty.http3;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http3.DefaultHttp3DataFrame;
import io.netty.handler.codec.http3.DefaultHttp3HeadersFrame;
import org.mockserver.grpc.GrpcDerivedHeaders;
import org.mockserver.grpc.GrpcException;
import org.mockserver.grpc.GrpcFrameCodec;
import org.mockserver.grpc.GrpcJsonMessageConverter;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcResponseBody;
import org.mockserver.grpc.GrpcResponseStatusResolver;
import org.mockserver.grpc.GrpcStatusMapper;
import com.google.protobuf.Descriptors;
import org.mockserver.model.Header;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.NottableString;

import java.util.List;
import java.util.Locale;

/**
 * Adapter that bridges gRPC request/response framing for the HTTP/3 path.
 * <p>
 * Reuses the existing {@link GrpcFrameCodec}, {@link GrpcProtoDescriptorStore},
 * and {@link GrpcJsonMessageConverter} from mockserver-core -- no logic is
 * duplicated. The adapter handles:
 * <ul>
 *   <li><strong>Inbound:</strong> detecting gRPC content-type, decoding the
 *       5-byte length-prefixed gRPC frame(s) from the request body, converting
 *       protobuf to JSON via the descriptor store, and tagging the request
 *       with {@code x-grpc-service} / {@code x-grpc-method} so the response
 *       path can re-encode.</li>
 *   <li><strong>Outbound:</strong> converting the matched response's JSON body
 *       back to gRPC-framed protobuf, building the initial HTTP/3 HEADERS
 *       frame (without grpc-status), the DATA frame (gRPC framed body), and a
 *       separate trailing HEADERS frame with grpc-status/grpc-message and any
 *       user-authored trailing metadata -- the correct wire framing that gRPC
 *       clients expect over HTTP/3.</li>
 * </ul>
 * <p>
 * This class is stateless and thread-safe; all state is passed via method
 * parameters.
 */
public final class GrpcHttp3Adapter {

    private GrpcHttp3Adapter() {
        // utility class
    }

    /**
     * Detect whether the given content-type indicates a gRPC request.
     * Delegates to {@link GrpcStatusMapper#isGrpcContentType(String)}.
     */
    public static boolean isGrpcRequest(String contentType) {
        return GrpcStatusMapper.isGrpcContentType(contentType);
    }

    /**
     * Transform a gRPC request for the MockServer matching pipeline: decode the
     * gRPC length-prefixed message(s), convert protobuf to JSON via the
     * descriptor store, and tag the request with service/method markers.
     *
     * @param request         the raw HttpRequest with gRPC-framed binary body
     * @param descriptorStore the gRPC proto descriptor store
     * @return a transformed HttpRequest with a JSON body suitable for matching
     * @throws GrpcException if the gRPC path or framing is invalid
     */
    public static HttpRequest transformGrpcRequest(HttpRequest request, GrpcProtoDescriptorStore descriptorStore) {
        String path = request.getPath() != null ? request.getPath().getValue() : "";
        String[] parts = parseGrpcPath(path);
        String serviceName = parts[0];
        String methodName = parts[1];

        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);
        if (methodDescriptor == null) {
            throw new GrpcException("unknown gRPC method: " + serviceName + "/" + methodName);
        }

        byte[] bodyBytes = request.getBodyAsRawBytes();
        if (bodyBytes == null || bodyBytes.length == 0) {
            return GrpcDerivedHeaders.strip(request.clone())
                .withHeader(GrpcDerivedHeaders.SERVICE, serviceName)
                .withHeader(GrpcDerivedHeaders.METHOD, methodName)
                .withHeader(GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE, request.getFirstHeader("content-type"));
        }

        List<byte[]> messages = GrpcFrameCodec.decode(bodyBytes);
        if (messages.isEmpty()) {
            throw new GrpcException("failed to decode gRPC frame from request body");
        }

        GrpcJsonMessageConverter converter = descriptorStore.getConverter();

        if (messages.size() == 1) {
            String json = converter.toJson(messages.get(0), methodDescriptor.getInputType());
            return GrpcDerivedHeaders.strip(request.clone())
                .withBody(json)
                .withHeader(GrpcDerivedHeaders.SERVICE, serviceName)
                .withHeader(GrpcDerivedHeaders.METHOD, methodName)
                .withHeader(GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE, request.getFirstHeader("content-type"));
        } else {
            StringBuilder jsonArray = new StringBuilder("[");
            for (int i = 0; i < messages.size(); i++) {
                if (i > 0) {
                    jsonArray.append(",");
                }
                jsonArray.append(converter.toJson(messages.get(i), methodDescriptor.getInputType()));
            }
            jsonArray.append("]");
            return GrpcDerivedHeaders.strip(request.clone())
                .withBody(jsonArray.toString())
                .withHeader(GrpcDerivedHeaders.SERVICE, serviceName)
                .withHeader(GrpcDerivedHeaders.METHOD, methodName)
                .withHeader(GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE, request.getFirstHeader("content-type"))
                .withHeader(GrpcDerivedHeaders.CLIENT_STREAMING, "true");
        }
    }

    /**
     * Convert the response from the matching pipeline back to a gRPC-framed
     * response. Encodes the JSON body to protobuf, wraps it in a gRPC
     * length-prefixed frame, and determines the grpc-status.
     *
     * @param response        the matched HttpResponse (JSON body, grpc-status-name header)
     * @param serviceName     the gRPC service name from the original request
     * @param methodName      the gRPC method name from the original request
     * @param descriptorStore the gRPC proto descriptor store
     * @return a {@link GrpcResponseParts} with the initial headers, body bytes, and trailing headers
     */
    public static GrpcResponseParts transformGrpcResponse(
        HttpResponse response,
        String serviceName,
        String methodName,
        GrpcProtoDescriptorStore descriptorStore
    ) {
        Descriptors.MethodDescriptor methodDescriptor = descriptorStore.getMethod(serviceName, methodName);

        // Determine grpc-status using the SHARED, transport-independent resolver, so an
        // expectation behaves identically over HTTP/1.1, HTTP/2 and HTTP/3. In particular the
        // status is read from the trailers as well as the headers (consumer docs recommend
        // authoring it as a trailer), and a non-2xx HTTP status maps to a gRPC error rather than
        // silently reporting OK.
        GrpcResponseStatusResolver.ResolvedStatus resolved = GrpcResponseStatusResolver.resolve(response);
        boolean transportFailure = resolved.isTransportFailure();

        // Encode the response body to gRPC framing. A transport failure carries no message --
        // whatever body it has (an error page, a mismatch diagnostic) is not a protobuf message of
        // this method's output type, and trying to convert it would throw.
        byte[] grpcFrame = null;
        if (methodDescriptor != null && !transportFailure) {
            String bodyString = response.getBodyAsString();
            if (bodyString != null && !bodyString.isEmpty()) {
                byte[] grpcFrames = GrpcResponseBody.toFrames(bodyString, methodDescriptor.getOutputType(), descriptorStore.getConverter());
                // a JSON array with no elements is a stream with no messages: no DATA frame
                grpcFrame = grpcFrames.length == 0 ? null : grpcFrames;
            }
        }

        return new GrpcResponseParts(grpcFrame, resolved.code(), resolved.message());
    }

    /**
     * Build an error response (trailers-only pattern) for gRPC over HTTP/3.
     * Used when gRPC request processing fails before matching.
     *
     * @param statusCode the gRPC status code
     * @param message    the error message
     * @return a {@link GrpcResponseParts} with no body and the error status
     */
    public static GrpcResponseParts errorResponse(
        GrpcStatusMapper.GrpcStatusCode statusCode,
        String message
    ) {
        return new GrpcResponseParts(null, String.valueOf(statusCode.getCode()), message);
    }

    /**
     * Build the initial HTTP/3 HEADERS frame for a gRPC response.
     * Contains :status=200 and content-type=application/grpc but NOT grpc-status
     * (which belongs in the trailing HEADERS frame).
     */
    public static DefaultHttp3HeadersFrame buildInitialHeadersFrame() {
        return buildInitialHeadersFrame(null);
    }

    /**
     * As {@link #buildInitialHeadersFrame()}, additionally copying the matched response's own
     * headers through to the client.
     * <p>
     * Without this, a unary HTTP/3 gRPC response silently dropped every header the expectation
     * set -- {@code withHeader("x-tenant-id", "acme")} simply never arrived. HTTP/2 preserves them
     * by cloning the response, and the HTTP/3 server-streaming path already copies them via
     * {@code addConfiguredHeaders}, so unary HTTP/3 was the only path that lost them.
     * gRPC protocol metadata is excluded ({@link GrpcResponseStatusResolver#isGrpcProtocolMetadata})
     * because this frame emits {@code :status} and {@code content-type} itself, and
     * {@code grpc-status}/{@code grpc-message} belong in the trailing HEADERS frame.
     *
     * @param response the matched response, may be {@code null}
     */
    public static DefaultHttp3HeadersFrame buildInitialHeadersFrame(HttpResponse response) {
        DefaultHttp3HeadersFrame headersFrame = new DefaultHttp3HeadersFrame();
        headersFrame.headers().status("200");
        headersFrame.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        headersFrame.headers().add("server", "mockserver-http3");
        addPassThroughHeaders(headersFrame, response);
        return headersFrame;
    }

    private static void addPassThroughHeaders(DefaultHttp3HeadersFrame headersFrame, HttpResponse response) {
        if (response == null) {
            return;
        }
        addEntries(headersFrame, GrpcResponseStatusResolver.passThroughHeaders(response));
    }

    /**
     * Copy the matched response's user-authored <strong>trailers</strong> onto an HTTP/3 HEADERS
     * frame as gRPC trailing metadata.
     * <p>
     * {@link GrpcResponseStatusResolver#passThroughTrailers} excludes
     * {@code grpc-status}/{@code grpc-message}/{@code grpc-status-name}, so a user-authored trailer
     * cannot override or spoof the status the transport resolved and emits itself.
     */
    private static void addPassThroughTrailers(DefaultHttp3HeadersFrame frame, HttpResponse response) {
        if (response == null) {
            return;
        }
        addEntries(frame, GrpcResponseStatusResolver.passThroughTrailers(response));
    }

    /**
     * Add model entries to an HTTP/3 HEADERS frame, normalised for the wire.
     * <p>
     * Field names are lower-cased with {@link Locale#ROOT} because HTTP/3 field names must be
     * lower-case: {@code DefaultHttp3Headers} validates this and throws
     * {@code Http3HeadersValidationException} for an upper-case character, which would abort the
     * whole response rather than merely drop one field -- so an expectation authoring
     * {@code withTrailer("X-Request-Cost", ...)} would otherwise take the client's entire response
     * down. {@code Locale.ROOT} because a locale-sensitive fold (Turkish {@code I}) produces a
     * non-ASCII name that is rejected in turn. CR and LF are stripped from values, mirroring
     * {@code NettyResponseWriter.sanitizeHeaderValue} on the HTTP/1.1 path, so an authored value can
     * never inject additional fields.
     */
    private static void addEntries(DefaultHttp3HeadersFrame frame, Headers entries) {
        for (Header header : entries.getEntries()) {
            String name = header.getName().getValue().toLowerCase(Locale.ROOT);
            for (NottableString value : header.getValues()) {
                frame.headers().add(name, sanitizeValue(value.getValue()));
            }
        }
    }

    private static String sanitizeValue(String value) {
        if (value == null) {
            return null;
        }
        return value.replace("\r", "").replace("\n", "");
    }

    /**
     * Build the trailing HTTP/3 HEADERS frame for a gRPC response.
     * Contains grpc-status and optionally grpc-message. No pseudo-headers
     * (no :status) because this is a trailing HEADERS frame.
     */
    public static DefaultHttp3HeadersFrame buildTrailingHeadersFrame(String grpcStatus, String grpcMessage) {
        return buildTrailingHeadersFrame(grpcStatus, grpcMessage, null);
    }

    /**
     * As {@link #buildTrailingHeadersFrame(String, String)}, additionally emitting the matched
     * response's user-authored trailers as gRPC trailing metadata.
     * <p>
     * Without this, HTTP/3 silently dropped every trailer an expectation set:
     * {@code response().withTrailer("x-request-cost", "42")} and the gRPC chaos profile's
     * {@code customTrailers} simply never reached the client, on both the body and the body-less
     * branch. HTTP/1.1 and HTTP/2 emit them from the response model via
     * {@code MockServerHttpResponseToFullHttpResponse.mapResponseWithTrailers}; the HTTP/3 gRPC
     * writer builds its frames by hand and carried only {@code grpc-status}/{@code grpc-message}.
     * <p>
     * The custom metadata rides the <strong>same terminal frame</strong> as the status, so the
     * framing is unchanged -- there is still exactly one trailing HEADERS frame, written with
     * {@code SHUTDOWN_OUTPUT}. This is why HTTP/3 never had the HTTP/2 Trailers-Only defect that
     * {@code GrpcToHttpResponseHandler.asTrailersOnlyIfHttp2} guards against.
     *
     * @param response the matched response, may be {@code null} for a transport-synthesized status
     *                 (a {@code grpc-timeout} deadline, an encoding failure) that has no
     *                 user-authored trailers
     */
    public static DefaultHttp3HeadersFrame buildTrailingHeadersFrame(String grpcStatus, String grpcMessage, HttpResponse response) {
        DefaultHttp3HeadersFrame trailersFrame = new DefaultHttp3HeadersFrame();
        addPassThroughTrailers(trailersFrame, response);
        trailersFrame.headers().add(GrpcStatusMapper.GRPC_STATUS_HEADER, grpcStatus);
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            trailersFrame.headers().add(GrpcStatusMapper.GRPC_MESSAGE_HEADER,
                GrpcStatusMapper.percentEncodeMessage(grpcMessage));
        }
        return trailersFrame;
    }

    /**
     * Build an HTTP/3 DATA frame from gRPC-framed bytes.
     * Returns null if the frame bytes are null or empty.
     */
    public static DefaultHttp3DataFrame buildDataFrame(byte[] grpcFrameBytes) {
        if (grpcFrameBytes == null || grpcFrameBytes.length == 0) {
            return null;
        }
        return new DefaultHttp3DataFrame(Unpooled.wrappedBuffer(grpcFrameBytes));
    }

    /**
     * Build a "trailers-only" HTTP/3 HEADERS frame that combines :status, content-type,
     * and grpc-status/grpc-message in a single HEADERS frame (no DATA frame follows).
     * This is used for error responses where there is no message body.
     */
    public static DefaultHttp3HeadersFrame buildTrailersOnlyFrame(String grpcStatus, String grpcMessage) {
        return buildTrailersOnlyFrame(grpcStatus, grpcMessage, null);
    }

    /**
     * As {@link #buildTrailersOnlyFrame(String, String)}, additionally copying the matched
     * response's own headers <em>and trailers</em> through -- a trailers-only response is the
     * client's only frame, so dropping either loses it entirely.
     * <p>
     * Folding the user-authored trailers into this frame is the correct gRPC shape rather than a
     * compromise: the Trailers-Only form is defined as {@code HTTP-Status Content-Type Trailers},
     * and {@code Trailers} includes custom metadata. A gRPC client reads this single end-of-stream
     * frame as the call's trailing metadata. Emitting a second frame after it instead would be
     * wrong twice over -- the frame is already written with {@code SHUTDOWN_OUTPUT}, and a
     * non-terminal initial frame carrying {@code grpc-status} is exactly the HTTP/2 defect fixed in
     * {@code GrpcToHttpResponseHandler.asTrailersOnlyIfHttp2}.
     */
    public static DefaultHttp3HeadersFrame buildTrailersOnlyFrame(String grpcStatus, String grpcMessage, HttpResponse response) {
        DefaultHttp3HeadersFrame frame = new DefaultHttp3HeadersFrame();
        frame.headers().status("200");
        frame.headers().add("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE);
        frame.headers().add("server", "mockserver-http3");
        addPassThroughHeaders(frame, response);
        addPassThroughTrailers(frame, response);
        frame.headers().add(GrpcStatusMapper.GRPC_STATUS_HEADER, grpcStatus);
        if (grpcMessage != null && !grpcMessage.isEmpty()) {
            frame.headers().add(GrpcStatusMapper.GRPC_MESSAGE_HEADER,
                GrpcStatusMapper.percentEncodeMessage(grpcMessage));
        }
        return frame;
    }

    /**
     * Parse a gRPC path (e.g., "/package.ServiceName/MethodName") into
     * [serviceName, methodName].
     */
    static String[] parseGrpcPath(String path) {
        if (path == null || path.isEmpty()) {
            return new String[]{"", ""};
        }
        if (path.startsWith("/")) {
            path = path.substring(1);
        }
        int slashIndex = path.lastIndexOf('/');
        if (slashIndex < 1 || slashIndex == path.length() - 1) {
            return new String[]{path, ""};
        }
        return new String[]{path.substring(0, slashIndex), path.substring(slashIndex + 1)};
    }

    /**
     * Holds the parts of a gRPC response for HTTP/3 framing:
     * the gRPC-framed body bytes, the grpc-status value, and
     * an optional grpc-message.
     */
    public static final class GrpcResponseParts {
        private final byte[] grpcFrameBytes;
        private final String grpcStatus;
        private final String grpcMessage;

        public GrpcResponseParts(byte[] grpcFrameBytes, String grpcStatus, String grpcMessage) {
            this.grpcFrameBytes = grpcFrameBytes;
            this.grpcStatus = grpcStatus;
            this.grpcMessage = grpcMessage;
        }

        public byte[] grpcFrameBytes() {
            return grpcFrameBytes;
        }

        public String grpcStatus() {
            return grpcStatus;
        }

        public String grpcMessage() {
            return grpcMessage;
        }

        public boolean hasBody() {
            return grpcFrameBytes != null && grpcFrameBytes.length > 0;
        }
    }
}

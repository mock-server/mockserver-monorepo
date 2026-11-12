package org.mockserver.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;
import org.mockserver.model.BinaryBody;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;

import java.io.ByteArrayOutputStream;
import java.util.Collections;
import java.util.List;

/**
 * Translates a MockServer-internal gRPC exchange (the JSON representation that
 * {@code GrpcToHttpRequestHandler} produces after decoding an inbound protobuf request) to and from
 * the wire form a real upstream gRPC server expects, so that a matched {@code FORWARD}-class
 * expectation — or the anonymous proxy no-match path — can relay a gRPC call to an upstream service
 * and record the decoded exchange.
 *
 * <p>Two symmetric transforms:
 * <ul>
 *   <li>{@link #encodeRequestForUpstream(HttpRequest, GrpcProtoDescriptorStore)} — turns the JSON
 *       request body (single message, or a JSON array for client-streaming) back into gRPC
 *       length-prefixed protobuf frames, sets {@code content-type: application/grpc}, forces
 *       HTTP/2, adds {@code te: trailers}, and strips the internal {@code x-grpc-*} helper headers
 *       so they do not leak upstream.</li>
 *   <li>{@link #decodeResponseFromUpstream(HttpResponse, String, String, GrpcProtoDescriptorStore)}
 *       — decodes the upstream's gRPC-framed protobuf response back to JSON and re-stamps
 *       {@code x-grpc-service}/{@code x-grpc-method} (and {@code grpc-status-name}) onto the
 *       response. The JSON body makes the logged {@code FORWARDED_REQUEST} entry replayable as a
 *       mock, and the stamped headers let {@code GrpcToHttpResponseHandler} re-frame the response to
 *       protobuf for the calling gRPC client.</li>
 * </ul>
 *
 * <p>Both transforms are fail-safe: when the request is not a decoded gRPC exchange, the descriptor
 * store has no matching method, or any conversion error occurs, the original message is returned
 * unchanged so ordinary HTTP forwarding is never disrupted.
 *
 * <p><b>Boundary:</b> unary and client-streaming request bodies (single JSON object / JSON array)
 * and unary or server-streaming responses (one or more frames) are handled. Full bidirectional
 * streaming forward is out of scope here — it is driven by the multiplex bidi pipeline, not the
 * request/response forward path.
 */
public class GrpcForwardTranslator {

    // Defined once in GrpcDerivedHeaders and aliased here, so the set of names this class strips
    // from a forwarded request can never drift from the set the request handlers write and strip.
    public static final String SERVICE_HEADER = GrpcDerivedHeaders.SERVICE;
    public static final String METHOD_HEADER = GrpcDerivedHeaders.METHOD;
    public static final String ORIGINAL_CONTENT_TYPE_HEADER = GrpcDerivedHeaders.ORIGINAL_CONTENT_TYPE;
    public static final String CLIENT_STREAMING_HEADER = GrpcDerivedHeaders.CLIENT_STREAMING;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GrpcForwardTranslator() {
    }

    /**
     * Whether {@code request} is a decoded gRPC exchange eligible for upstream re-encoding — it must
     * carry a non-empty {@code x-grpc-service} and {@code x-grpc-method} and an {@code application/grpc}
     * content-type (the shape {@code GrpcToHttpRequestHandler} produces).
     */
    public static boolean isGrpcForwardRequest(HttpRequest request) {
        if (request == null) {
            return false;
        }
        String service = request.getFirstHeader(SERVICE_HEADER);
        String method = request.getFirstHeader(METHOD_HEADER);
        return service != null && !service.isEmpty()
            && method != null && !method.isEmpty()
            && GrpcStatusMapper.isGrpcContentType(request.getFirstHeader("content-type"));
    }

    /**
     * Re-encodes a decoded gRPC request's JSON body back to gRPC-framed protobuf for an upstream call.
     * Returns the original request unchanged when it is not an eligible gRPC exchange, when the store
     * has no matching method descriptor, or when conversion fails.
     */
    public static HttpRequest encodeRequestForUpstream(HttpRequest request, GrpcProtoDescriptorStore store) {
        if (store == null || !store.hasServices() || !isGrpcForwardRequest(request)) {
            return request;
        }
        String service = request.getFirstHeader(SERVICE_HEADER);
        String method = request.getFirstHeader(METHOD_HEADER);
        Descriptors.MethodDescriptor methodDescriptor = store.getMethod(service, method);
        if (methodDescriptor == null) {
            return request;
        }
        try {
            String bodyString = request.getBodyAsText();
            GrpcJsonMessageConverter converter = store.getConverter();
            byte[] framed;
            if ((bodyString == null || bodyString.isEmpty()) && methodDescriptor.isClientStreaming()) {
                // a client stream that sent no messages stays empty
                framed = new byte[0];
            } else if (bodyString == null || bodyString.isEmpty()) {
                // no body (e.g. an empty unary request) — send a zero-length gRPC frame
                framed = GrpcFrameCodec.encode(new byte[0]);
            } else if (isJsonArray(bodyString)) {
                // client-streaming: one frame per JSON array element
                ByteArrayOutputStream frames = new ByteArrayOutputStream();
                JsonNode array = OBJECT_MAPPER.readTree(bodyString);
                for (JsonNode element : array) {
                    byte[] protobuf = converter.toProtobuf(element.toString(), methodDescriptor.getInputType());
                    frames.write(GrpcFrameCodec.encode(protobuf));
                }
                framed = frames.toByteArray();
            } else {
                byte[] protobuf = converter.toProtobuf(bodyString, methodDescriptor.getInputType());
                framed = GrpcFrameCodec.encode(protobuf);
            }
            return request
                .clone()
                .withBody(new BinaryBody(framed))
                .replaceHeader(new Header("content-type", GrpcStatusMapper.GRPC_CONTENT_TYPE))
                .withHeader("te", "trailers")
                .withProtocol(Protocol.HTTP_2)
                .removeHeader(SERVICE_HEADER)
                .removeHeader(METHOD_HEADER)
                .removeHeader(ORIGINAL_CONTENT_TYPE_HEADER)
                .removeHeader(CLIENT_STREAMING_HEADER);
        } catch (Exception e) {
            // fail-safe: leave the request unchanged rather than break the forward
            return request;
        }
    }

    /**
     * Decodes an upstream gRPC-framed protobuf response back to JSON and stamps the
     * {@code x-grpc-service}/{@code x-grpc-method} headers (and {@code grpc-status-name}) so the
     * response is both replayable (JSON body in the recorded exchange) and re-framable to protobuf by
     * {@code GrpcToHttpResponseHandler} for the calling client. Returns the original response when the
     * store has no matching method descriptor or when conversion fails.
     */
    public static HttpResponse decodeResponseFromUpstream(HttpResponse response, String service, String method, GrpcProtoDescriptorStore store) {
        if (response == null || store == null || !store.hasServices()
            || service == null || service.isEmpty() || method == null || method.isEmpty()) {
            return response;
        }
        Descriptors.MethodDescriptor methodDescriptor = store.getMethod(service, method);
        if (methodDescriptor == null) {
            return response;
        }
        try {
            byte[] body = response.getBodyAsRawBytes();
            List<byte[]> messages = body == null || body.length == 0
                ? Collections.emptyList()
                : GrpcFrameCodec.decode(body);
            String json;
            if (messages.isEmpty() && !GrpcResponseStatusResolver.resolve(response).isOk()) {
                // an error carries no message: its status is the payload
                json = "";
            } else {
                // an OK stream with no messages is "[]", as an empty body would be replaced by an
                // example message when it is relayed or replayed (issue #2988)
                json = GrpcResponseBody.toJson(messages, methodDescriptor.getOutputType(), store.getConverter());
            }
            HttpResponse decoded = response.clone()
                .withBody(json)
                .withHeader(SERVICE_HEADER, service)
                .withHeader(METHOD_HEADER, method);
            // Carry the upstream gRPC status through so GrpcToHttpResponseHandler re-emits the
            // correct grpc-status trailer when re-framing for the client.
            //
            // Keep the NUMERIC status rather than converting it to a status-name: the name lookup
            // is GrpcStatusMapper.fromCode, which is getOrDefault(code, UNKNOWN), so an upstream
            // server returning a non-standard or future status (say 42) would have it silently
            // rewritten to UNKNOWN and rendered as "2". A proxy must not rewrite the upstream's
            // status. GrpcResponseStatusResolver.explicitStatus reads this header and
            // emits it verbatim.
            String grpcStatus = response.getFirstHeader(GrpcStatusMapper.GRPC_STATUS_HEADER);
            if (grpcStatus != null && !grpcStatus.isEmpty()) {
                try {
                    decoded.replaceHeader(new Header(GrpcStatusMapper.GRPC_STATUS_HEADER,
                        String.valueOf(Integer.parseInt(grpcStatus.trim()))));
                } catch (NumberFormatException ignored) {
                    // non-numeric grpc-status (protocol violation) — leave the status unset
                }
            }
            // Percent-DECODE the upstream's grpc-message into the response model. A real gRPC
            // server percent-encodes it per the wire spec, so without decoding here the message
            // would appear escaped ("paiement refus%C3%A9") in the log and in verifications, and
            // would then be double-encoded when re-emitted to the client.
            String grpcMessage = response.getFirstHeader(GrpcStatusMapper.GRPC_MESSAGE_HEADER);
            if (grpcMessage != null && !grpcMessage.isEmpty()) {
                // strip C0 controls: %00 / %0D%0A decode to real NUL / CRLF, and this value reaches
                // the event log, persisted log, verifications and dashboard from an UNTRUSTED
                // upstream, where they would allow forged log lines
                decoded.replaceHeader(new org.mockserver.model.Header(
                    GrpcStatusMapper.GRPC_MESSAGE_HEADER,
                    GrpcStatusMapper.stripControlCharacters(
                        GrpcStatusMapper.percentDecodeMessage(grpcMessage))));
            }
            return decoded;
        } catch (Exception e) {
            // fail-safe: leave the response unchanged rather than break the forward.
            //
            // This catch is deliberately wide, so it must not be silent: it covers protobuf
            // deframing, the protobuf-to-JSON conversion and the grpc-message sanitisation above,
            // and swallowing all of that without a trace makes a genuine decode bug look like
            // "the upstream just returned protobuf". Log at WARN with the service/method so the
            // fallback is at least diagnosable. (Narrowing the catch is a separate change: some of
            // what it currently absorbs may be load-bearing on the proxy path.)
            if (store.getMockServerLogger() != null
                && store.getMockServerLogger().isEnabledForInstance(org.slf4j.event.Level.WARN)) {
                store.getMockServerLogger().logEvent(
                    new org.mockserver.log.model.LogEntry()
                        .setType(org.mockserver.log.model.LogEntry.LogMessageType.WARN)
                        // setType alone does NOT raise the level: LogEntry.logLevel defaults to
                        // INFO and MockServerLogger gates on getLogLevel(), so without this the
                        // WARN is silent at global log level WARN/ERROR — exactly when the
                        // fail-safe most needs to be diagnosable.
                        .setLogLevel(org.slf4j.event.Level.WARN)
                        .setMessageFormat("failed to decode upstream gRPC response for {}/{}, "
                            + "forwarding it unchanged:{}")
                        .setArguments(service, method, e.getMessage())
                );
            }
            return response;
        }
    }

    private static boolean isJsonArray(String body) {
        if (body == null) {
            return false;
        }
        String trimmed = body.trim();
        return !trimmed.isEmpty() && trimmed.charAt(0) == '[';
    }
}

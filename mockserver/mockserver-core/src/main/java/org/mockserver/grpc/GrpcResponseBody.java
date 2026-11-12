package org.mockserver.grpc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.protobuf.Descriptors;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * The JSON form of a gRPC response body, shared by the forward path that records it and the
 * transports that frame it back to protobuf, so a recorded response replays as the stream it was.
 * <p>
 * One message is a JSON object. Any other number of messages is a JSON array with one element per
 * message, so a server stream that ended with no messages is {@code []}. That keeps it distinct from
 * an empty body, which means no message was authored and lets an {@code OK} response be filled in
 * with a synthesized example message.
 * <p>
 * {@code google.protobuf.Value} and {@code google.protobuf.ListValue} print as a JSON array
 * themselves, so for those output types an array body is always one message.
 */
public final class GrpcResponseBody {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private GrpcResponseBody() {
    }

    /**
     * Returns the JSON form of the decoded protobuf {@code messages}.
     */
    public static String toJson(List<byte[]> messages, Descriptors.Descriptor outputType, GrpcJsonMessageConverter converter) {
        if (messages.size() == 1) {
            return converter.toJson(messages.get(0), outputType);
        }
        StringBuilder array = new StringBuilder("[");
        for (int i = 0; i < messages.size(); i++) {
            if (i > 0) {
                array.append(",");
            }
            array.append(converter.toJson(messages.get(i), outputType));
        }
        return array.append("]").toString();
    }

    /**
     * Returns the gRPC length-prefixed frames for a non-empty JSON body, one per message, so a body
     * of {@code []} returns no bytes at all.
     */
    public static byte[] toFrames(String json, Descriptors.Descriptor outputType, GrpcJsonMessageConverter converter) {
        if (!isMessageArray(json, outputType)) {
            return GrpcFrameCodec.encode(converter.toProtobuf(json, outputType));
        }
        try {
            ByteArrayOutputStream frames = new ByteArrayOutputStream();
            for (JsonNode element : OBJECT_MAPPER.readTree(json)) {
                frames.write(GrpcFrameCodec.encode(converter.toProtobuf(element.toString(), outputType)));
            }
            return frames.toByteArray();
        } catch (IOException e) {
            throw new GrpcException("Failed to convert JSON array to gRPC messages", e);
        }
    }

    private static boolean isMessageArray(String json, Descriptors.Descriptor outputType) {
        String fullName = outputType.getFullName();
        if ("google.protobuf.Value".equals(fullName) || "google.protobuf.ListValue".equals(fullName)) {
            return false;
        }
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (!Character.isWhitespace(c)) {
                return c == '[';
            }
        }
        return false;
    }
}

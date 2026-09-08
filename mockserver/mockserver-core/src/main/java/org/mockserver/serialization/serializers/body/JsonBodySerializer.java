package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.model.JsonBody;
import org.mockserver.serialization.ObjectMapperFactory;

import java.io.IOException;
import java.util.Arrays;

/**
 * @author jamesdbloom
 */
public class JsonBodySerializer extends StdSerializer<JsonBody> {

    private static final long serialVersionUID = 1L;

    private static final ObjectMapper OBJECT_MAPPER = ObjectMapperFactory.createObjectMapper()
        .copy()
        .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
        .setNodeFactory(JsonNodeFactory.withExactBigDecimals(true));
    private final boolean serialiseDefaultValues;

    public JsonBodySerializer(boolean serialiseDefaultValues) {
        super(JsonBody.class);
        this.serialiseDefaultValues = serialiseDefaultValues;
    }

    @Override
    public void serialize(JsonBody jsonBody, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        boolean notNonDefault = jsonBody.getNot() != null && jsonBody.getNot();
        boolean optionalNonDefault = jsonBody.getOptional() != null && jsonBody.getOptional();
        boolean contentTypeNonDefault = jsonBody.getContentType() != null && !jsonBody.getContentType().equals(JsonBody.DEFAULT_JSON_CONTENT_TYPE.toString());
        boolean matchTypeNonDefault = jsonBody.getMatchType() != JsonBody.DEFAULT_MATCH_TYPE;
        boolean matchNumbersAsStringsNonDefault = jsonBody.isMatchNumbersAsStrings();
        JsonNode jsonNode = OBJECT_MAPPER.readTree(jsonBody.getValue());
        // the original wire bytes cannot be reconstructed from the serialised JSON value when they differ
        // from the canonical serialisation of the parsed tree (e.g. recorded requests with original spacing) -
        // in that case emit them explicitly so getBodyAsRawBytes()/getBodyAsOriginalRawBytes() round-trip exactly.
        // this is scoped to the recorded-request retrieval path only (via the per-call "emitRawBytes" attribute) so
        // matcher/expectation serialisation and diagnostic mismatch logs stay clean (see #2374); the expensive
        // canonical re-serialisation + comparison is only computed when the attribute is set and rawBytes is present
        boolean rawBytesNonDefault = Boolean.TRUE.equals(provider.getAttribute("emitRawBytes"))
            && jsonBody.getRawBytes() != null
            && !Arrays.equals(jsonBody.getRawBytes(), OBJECT_MAPPER.writeValueAsBytes(jsonNode));
        if (serialiseDefaultValues || notNonDefault || optionalNonDefault || contentTypeNonDefault || matchTypeNonDefault || matchNumbersAsStringsNonDefault || rawBytesNonDefault) {
            jgen.writeStartObject();
            if (notNonDefault) {
                jgen.writeBooleanField("not", jsonBody.getNot());
            }
            if (optionalNonDefault) {
                jgen.writeBooleanField("optional", jsonBody.getOptional());
            }
            if (contentTypeNonDefault) {
                jgen.writeStringField("contentType", jsonBody.getContentType());
            }
            jgen.writeStringField("type", jsonBody.getType().name());
            if (jsonNode.isValueNode()) {
                jgen.writeFieldName("json");
                jgen.writeRawValue(jsonBody.getValue());
            } else {
                jgen.writeObjectField("json", jsonNode);
            }
            if (rawBytesNonDefault) {
                jgen.writeObjectField("rawBytes", jsonBody.getRawBytes());
            }
            if (matchTypeNonDefault) {
                jgen.writeStringField("matchType", jsonBody.getMatchType().name());
            }
            if (matchNumbersAsStringsNonDefault) {
                jgen.writeBooleanField("matchNumbersAsStrings", true);
            }
            jgen.writeEndObject();
        } else {
            if (jsonNode.isValueNode()) {
                jgen.writeRawValue(jsonBody.getValue());
            } else {
                jgen.writeObject(jsonNode);
            }
        }
    }
}

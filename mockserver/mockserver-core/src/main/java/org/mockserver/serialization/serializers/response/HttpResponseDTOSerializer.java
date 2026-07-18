package org.mockserver.serialization.serializers.response;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.ser.std.StdSerializer;
import org.mockserver.serialization.model.*;

import java.io.IOException;

/**
 * @author jamesdbloom
 */
public class HttpResponseDTOSerializer extends StdSerializer<HttpResponseDTO> {
    private static final long serialVersionUID = 1L;

    public HttpResponseDTOSerializer() {
        super(HttpResponseDTO.class);
    }

    @Override
    public void serialize(HttpResponseDTO httpResponseDTO, JsonGenerator jgen, SerializerProvider provider) throws IOException {
        jgen.writeStartObject();
        if (httpResponseDTO.getStatusCode() != null) {
            jgen.writeObjectField("statusCode", httpResponseDTO.getStatusCode());
        }
        if (httpResponseDTO.getStatusCodeRange() != null) {
            jgen.writeObjectField("statusCodeRange", httpResponseDTO.getStatusCodeRange());
        }
        if (httpResponseDTO.getReasonPhrase() != null) {
            jgen.writeObjectField("reasonPhrase", httpResponseDTO.getReasonPhrase());
        }
        if (httpResponseDTO.getHeaders() != null && !httpResponseDTO.getHeaders().isEmpty()) {
            jgen.writeObjectField("headers", httpResponseDTO.getHeaders());
        }
        if (httpResponseDTO.getTrailers() != null && !httpResponseDTO.getTrailers().isEmpty()) {
            jgen.writeObjectField("trailers", httpResponseDTO.getTrailers());
        }
        if (httpResponseDTO.getCookies() != null && !httpResponseDTO.getCookies().isEmpty()) {
            jgen.writeObjectField("cookies", httpResponseDTO.getCookies());
        }
        BodyWithContentTypeDTO body = httpResponseDTO.getBody();
        if (body != null) {
            if (body instanceof StringBodyDTO && !((StringBodyDTO) body).getString().isEmpty()) {
                jgen.writeObjectField("body", body);
            } else if (body instanceof JsonBodyDTO && !((JsonBodyDTO) body).getJson().isEmpty()) {
                jgen.writeObjectField("body", body);
            } else if (body instanceof XmlBodyDTO && !((XmlBodyDTO) body).getXml().isEmpty()) {
                jgen.writeObjectField("body", body);
            } else if (body instanceof BinaryBodyDTO) {
                jgen.writeObjectField("body", body);
            } else if (body instanceof FileBodyDTO && ((FileBodyDTO) body).getFilePath() != null && !((FileBodyDTO) body).getFilePath().isEmpty()) {
                jgen.writeObjectField("body", body);
            } else if (body instanceof LogEntryBodyDTO) {
                jgen.writeObjectField("body", body);
            }
        }
        if (httpResponseDTO.getGenerateFromSchema() != null) {
            jgen.writeObjectField("generateFromSchema", httpResponseDTO.getGenerateFromSchema());
        }
        if (httpResponseDTO.getDelay() != null) {
            jgen.writeObjectField("delay", httpResponseDTO.getDelay());
        }
        if (httpResponseDTO.getConnectionOptions() != null) {
            jgen.writeObjectField("connectionOptions", httpResponseDTO.getConnectionOptions());
        }
        if (httpResponseDTO.getRecoverAfter() != null) {
            jgen.writeObjectField("recoverAfter", httpResponseDTO.getRecoverAfter());
        }
        jgen.writeEndObject();
    }
}

package org.mockserver.serialization.serializers.response;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;
import org.mockserver.model.Cookie;
import org.mockserver.model.Delay;
import org.mockserver.model.FileBody;
import org.mockserver.model.Header;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.HttpResponseDTO;

import java.util.concurrent.TimeUnit;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.JsonBody.json;

public class HttpResponseDTOSerializerTest {

    @Test
    public void shouldReturnFormattedResponseWithNoFieldsSet() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(
            new HttpResponseDTO(
                response()
            )
            ),
            is("{ }"));
    }

    @Test
    public void shouldReturnFormattedResponseWithAllFieldsSet() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(
            new HttpResponseDTO(
                response()
                    .withStatusCode(302)
                    .withReasonPhrase("randomReason")
                    .withBody("some_body")
                    .withHeaders(new Header("header_name", "header_value"))
                    .withCookies(new Cookie("cookie_name", "cookie_value"))
                    .withDelay(new Delay(TimeUnit.MICROSECONDS, 1))
                    .withConnectionOptions(
                        connectionOptions()
                            .withSuppressContentLengthHeader(true)
                            .withContentLengthHeaderOverride(50)
                            .withSuppressConnectionHeader(true)
                            .withChunkSize(100)
                            .withKeepAliveOverride(true)
                            .withCloseSocket(true)
                            .withCloseSocketDelay(new Delay(MILLISECONDS, 100))
                    )
            )
            ),
            is("{" + NEW_LINE +
                "  \"statusCode\" : 302," + NEW_LINE +
                "  \"reasonPhrase\" : \"randomReason\"," + NEW_LINE +
                "  \"headers\" : {" + NEW_LINE +
                "    \"header_name\" : [ \"header_value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"cookies\" : {" + NEW_LINE +
                "    \"cookie_name\" : \"cookie_value\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"body\" : \"some_body\"," + NEW_LINE +
                "  \"delay\" : {" + NEW_LINE +
                "    \"timeUnit\" : \"MICROSECONDS\"," + NEW_LINE +
                "    \"value\" : 1" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"connectionOptions\" : {" + NEW_LINE +
                "    \"chunkSize\" : 100," + NEW_LINE +
                "    \"closeSocket\" : true," + NEW_LINE +
                "    \"closeSocketDelay\" : {" + NEW_LINE +
                "      \"timeUnit\" : \"MILLISECONDS\"," + NEW_LINE +
                "      \"value\" : 100" + NEW_LINE +
                "    }," + NEW_LINE +
                "    \"contentLengthHeaderOverride\" : 50," + NEW_LINE +
                "    \"keepAliveOverride\" : true," + NEW_LINE +
                "    \"suppressConnectionHeader\" : true," + NEW_LINE +
                "    \"suppressContentLengthHeader\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldReturnFormattedResponseWithJsonBodyInToString() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(
            new HttpResponseDTO(
                response()
                    .withStatusCode(302)
                    .withBody(json("{ \"key\": \"some_value\" }"))
                    .withHeaders(new Header("header_name", "header_value"))
                    .withCookies(new Cookie("cookie_name", "cookie_value"))
            )
            ),
            is("{" + NEW_LINE +
                "  \"statusCode\" : 302," + NEW_LINE +
                "  \"headers\" : {" + NEW_LINE +
                "    \"header_name\" : [ \"header_value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"cookies\" : {" + NEW_LINE +
                "    \"cookie_name\" : \"cookie_value\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"body\" : {" + NEW_LINE +
                "    \"key\" : \"some_value\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldReturnFormattedResponseWithFileBody() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(
            new HttpResponseDTO(
                response()
                    .withStatusCode(200)
                    .withBody(new FileBody("/assets/file.xml", MediaType.APPLICATION_XML_UTF_8, HttpTemplate.TemplateType.MUSTACHE))
            )
            ),
            is("{" + NEW_LINE +
                "  \"statusCode\" : 200," + NEW_LINE +
                "  \"body\" : {" + NEW_LINE +
                "    \"contentType\" : \"application/xml; charset=utf-8\"," + NEW_LINE +
                "    \"filePath\" : \"/assets/file.xml\"," + NEW_LINE +
                "    \"templateType\" : \"MUSTACHE\"," + NEW_LINE +
                "    \"type\" : \"FILE\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldReturnFormattedResponseWithDefaultStatusCode() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(
            new HttpResponseDTO(
                response()
                    .withStatusCode(200)
                    .withHeaders(new Header("header_name", "header_value"))
                    .withCookies(new Cookie("cookie_name", "cookie_value"))
            )
            ),
            is("{" + NEW_LINE +
                "  \"statusCode\" : 200," + NEW_LINE +
                "  \"headers\" : {" + NEW_LINE +
                "    \"header_name\" : [ \"header_value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"cookies\" : {" + NEW_LINE +
                "    \"cookie_name\" : \"cookie_value\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}")
        );
    }

    @Test
    public void shouldReturnFormattedResponseWithGenerateFromSchema() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(
            new HttpResponseDTO(
                response()
                    .withStatusCode(200)
                    .withGenerateFromSchema("{\"type\":\"string\"}")
            )
            ),
            is("{" + NEW_LINE +
                "  \"statusCode\" : 200," + NEW_LINE +
                "  \"generateFromSchema\" : \"{\\\"type\\\":\\\"string\\\"}\"" + NEW_LINE +
                "}")
        );
    }

    @Test
    public void shouldReturnFormattedResponseWithStatusCodeRange() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(
            new HttpResponseDTO(
                response()
                    .withStatusCodeRange("2XX")
            )
            ),
            is("{" + NEW_LINE +
                "  \"statusCodeRange\" : \"2XX\"" + NEW_LINE +
                "}")
        );
    }

}

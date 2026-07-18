package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookies;
import org.mockserver.model.Delay;
import org.mockserver.model.FileBody;
import org.mockserver.model.Headers;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.HttpTemplate;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.model.BodyWithContentTypeDTO;
import org.mockserver.serialization.model.DelayDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.validator.jsonschema.JsonSchemaHttpResponseValidator;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.openMocks;
import static org.mockserver.model.Cookie.cookie;
import static org.mockserver.model.Header.header;
import static org.mockserver.model.StringBody.exact;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class HttpResponseSerializerTest {

    private final HttpResponse fullHttpResponse =
        new HttpResponse()
            .withStatusCode(123)
            .withReasonPhrase("randomPhrase")
            .withBody(exact("somebody"))
            .withHeaders(header("headerName", "headerValue"))
            .withCookies(cookie("cookieName", "cookieValue"))
            .withDelay(new Delay(TimeUnit.MICROSECONDS, 3));
    private final HttpResponseDTO fullHttpResponseDTO =
        new HttpResponseDTO()
            .setStatusCode(123)
            .setReasonPhrase("randomPhrase")
            .setBody(BodyWithContentTypeDTO.createWithContentTypeDTO(exact("somebody")))
            .setHeaders(new Headers().withEntries(
                header("headerName", "headerValue")
            ))
            .setCookies(new Cookies().withEntries(
                cookie("cookieName", "cookieValue")
            ))
            .setDelay(new DelayDTO(new Delay(TimeUnit.MICROSECONDS, 3)));

    @Rule
    public ExpectedException thrown = ExpectedException.none();
    @Mock
    private ObjectMapper objectMapper;
    @Mock
    private ObjectWriter objectWriter;
    @Mock
    private JsonSchemaHttpResponseValidator httpResponseValidator;
    @InjectMocks
    private HttpResponseSerializer httpResponseSerializer;

    @Before
    public void setupTestFixture() {
        httpResponseSerializer = spy(new HttpResponseSerializer(new MockServerLogger()));

        openMocks(this);
    }

    @Test
    public void deserialize() throws IOException {
        // given
        when(httpResponseValidator.isValid(eq("responseBytes"))).thenReturn("");
        when(objectMapper.readValue(eq("responseBytes"), same(HttpResponseDTO.class))).thenReturn(fullHttpResponseDTO);

        // when
        HttpResponse httpResponse = httpResponseSerializer.deserialize("responseBytes");

        // then
        assertThat( httpResponse, is(fullHttpResponse));
    }

    @Test
    public void serialize() throws IOException {
        // when
        httpResponseSerializer.serialize(fullHttpResponse);

        // then
        verify(objectWriter).writeValueAsString(fullHttpResponseDTO);
    }

    @Test
    @SuppressWarnings("RedundantArrayCreation")
    public void shouldSerializeArray() throws IOException {
        // when
        httpResponseSerializer.serialize(new HttpResponse[]{fullHttpResponse, fullHttpResponse});

        // then
        verify(objectWriter).writeValueAsString(new HttpResponseDTO[]{fullHttpResponseDTO, fullHttpResponseDTO});
    }

    @Test
    public void shouldRoundTripStatusCodeRangeClassRange() {
        // given a real serializer (not the mock-injected one)
        HttpResponseSerializer serializer = new HttpResponseSerializer(new MockServerLogger());
        HttpResponse original = new HttpResponse().withStatusCodeRange("2XX");

        // when serialised then deserialised
        String json = serializer.serialize(original);
        HttpResponse roundTripped = serializer.deserialize(json);

        // then the status-code range survives the round trip
        assertThat(json.contains("\"statusCodeRange\""), is(true));
        assertThat(roundTripped.getStatusCodeRange(), is("2XX"));
        assertThat(roundTripped, is(original));
    }

    @Test
    public void shouldRoundTripStatusCodeRangeNumericOperator() {
        HttpResponseSerializer serializer = new HttpResponseSerializer(new MockServerLogger());
        HttpResponse original = new HttpResponse().withStatusCode(500).withStatusCodeRange(">= 400");

        String json = serializer.serialize(original);
        HttpResponse roundTripped = serializer.deserialize(json);

        assertThat(roundTripped.getStatusCode(), is(500));
        assertThat(roundTripped.getStatusCodeRange(), is(">= 400"));
        assertThat(roundTripped, is(original));
    }

    @Test
    public void shouldRoundTripGenerateFromSchema() {
        // given a real serializer (not the mock-injected one)
        HttpResponseSerializer serializer = new HttpResponseSerializer(new MockServerLogger());
        HttpResponse original = new HttpResponse()
            .withStatusCode(200)
            .withGenerateFromSchema("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}");

        // when serialised then deserialised
        String json = serializer.serialize(original);
        HttpResponse roundTripped = serializer.deserialize(json);

        // then the inline schema survives the round trip
        assertThat(json.contains("\"generateFromSchema\""), is(true));
        assertThat(roundTripped.getGenerateFromSchema(), is("{\"type\":\"object\",\"properties\":{\"id\":{\"type\":\"integer\"}}}"));
        assertThat(roundTripped, is(original));
    }

    @Test
    public void shouldRoundTripFileBody() {
        HttpResponseSerializer serializer = new HttpResponseSerializer(new MockServerLogger());
        HttpResponse original = new HttpResponse()
            .withStatusCode(200)
            .withBody(new FileBody("/assets/file.xml", MediaType.APPLICATION_XML_UTF_8, HttpTemplate.TemplateType.MUSTACHE));

        String json = serializer.serialize(original);
        HttpResponse roundTripped = serializer.deserialize(json);

        assertThat(roundTripped, is(original));
    }

    @Test
    public void shouldOmitStatusCodeRangeWhenNull() {
        HttpResponseSerializer serializer = new HttpResponseSerializer(new MockServerLogger());
        HttpResponse original = new HttpResponse().withStatusCode(200);

        String json = serializer.serialize(original);

        // a plain exact-status response never emits statusCodeRange (backward-compatible output)
        assertThat(json.contains("statusCodeRange"), is(false));
        assertThat(serializer.deserialize(json).getStatusCodeRange(), is((String) null));
    }

}

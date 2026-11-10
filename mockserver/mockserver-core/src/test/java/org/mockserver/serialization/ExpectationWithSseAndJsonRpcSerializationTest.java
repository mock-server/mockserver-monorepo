package org.mockserver.serialization;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.*;
import org.mockserver.serialization.model.ExpectationDTO;

import java.util.concurrent.TimeUnit;

import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpSseResponse.sseResponse;
import static org.mockserver.model.JsonRpcBody.jsonRpc;
import static org.mockserver.model.SseEvent.sseEvent;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class ExpectationWithSseAndJsonRpcSerializationTest {

    private final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

    @Test
    public void shouldSerializeExpectationWithSseResponse() throws Exception {
        Expectation expectation = Expectation.when(
            request().withMethod("POST").withPath("/events")
        ).thenRespondWithSse(
            sseResponse()
                .withStatusCode(200)
                .withEvent(sseEvent().withEvent("message").withData("{\"hello\": \"world\"}").withId("1"))
                .withEvent(sseEvent().withEvent("update").withData("data2").withDelay(TimeUnit.MILLISECONDS, 100))
                .withCloseConnection(true)
        );

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(new ExpectationDTO(expectation));
        assertThat(json, notNullValue());
        assertThat(json.contains("httpSseResponse"), is(true));
        assertThat(json.contains("message"), is(true));

        ExpectationDTO rebuiltDTO = objectMapper.readValue(json, ExpectationDTO.class);
        Expectation rebuilt = rebuiltDTO.buildObject();

        assertThat(rebuilt.getHttpSseResponse(), notNullValue());
        assertThat( rebuilt.getHttpSseResponse().getStatusCode(), is(Integer.valueOf(200)));
        assertThat( rebuilt.getHttpSseResponse().getEvents().size(), is(2));
        assertThat( rebuilt.getHttpSseResponse().getEvents().get(0).getEvent(), is("message"));
        assertThat( rebuilt.getHttpSseResponse().getEvents().get(0).getData(), is("{\"hello\": \"world\"}"));
        assertThat( rebuilt.getHttpSseResponse().getEvents().get(0).getId(), is("1"));
        assertThat( rebuilt.getHttpSseResponse().getEvents().get(1).getEvent(), is("update"));
        assertThat(rebuilt.getHttpSseResponse().getEvents().get(1).getDelay(), notNullValue());
        assertThat(rebuilt.getHttpSseResponse().getCloseConnection(), is(true));
    }

    @Test
    public void shouldRoundTripASseEventCommentThatIsValidAgainstTheSchema() throws Exception {
        Expectation expectation = Expectation.when(request().withPath("/events")).thenRespondWithSse(
            sseResponse()
                .withEvent(sseEvent().withComment("heartbeat"))
                .withEvent(sseEvent().withComment("note").withData("payload"))
        );

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(new ExpectationDTO(expectation));
        Expectation rebuilt = objectMapper.readValue(json, ExpectationDTO.class).buildObject();

        assertThat(org.mockserver.validator.jsonschema.JsonSchemaExpectationValidator.jsonSchemaExpectationValidator(new org.mockserver.logging.MockServerLogger()).isValid(json), is(""));
        assertThat(rebuilt.getHttpSseResponse().getEvents().get(0), is(sseEvent().withComment("heartbeat")));
        assertThat(rebuilt.getHttpSseResponse().getEvents().get(1), is(sseEvent().withComment("note").withData("payload")));
    }

    @Test
    public void shouldNotWriteACommentFieldForAnEventWithoutOne() throws Exception {
        Expectation expectation = Expectation.when(request().withPath("/events")).thenRespondWithSse(
            sseResponse().withEvent(sseEvent().withData("payload"))
        );

        assertThat(objectMapper.writeValueAsString(new ExpectationDTO(expectation)).contains("comment"), is(false));
    }

    @Test
    public void shouldSerializeExpectationWithJsonRpcBody() throws Exception {
        Expectation expectation = Expectation.when(
            request()
                .withMethod("POST")
                .withPath("/rpc")
                .withBody(jsonRpc("tools/call"))
        ).thenRespond(
            HttpResponse.response()
                .withStatusCode(200)
                .withBody("{\"jsonrpc\": \"2.0\", \"result\": {}, \"id\": 1}")
        );

        String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(new ExpectationDTO(expectation));
        assertThat(json, notNullValue());
        assertThat(json.contains("JSON_RPC"), is(true));

        ExpectationDTO rebuiltDTO = objectMapper.readValue(json, ExpectationDTO.class);
        Expectation rebuilt = rebuiltDTO.buildObject();

        assertThat(rebuilt.getHttpRequest(), notNullValue());
        HttpRequest rebuiltRequest = (HttpRequest) rebuilt.getHttpRequest();
        assertThat(rebuiltRequest.getBody(), notNullValue());
        assertThat( rebuiltRequest.getBody().getType(), is(Body.Type.JSON_RPC));
        assertThat(rebuiltRequest.getBody() instanceof JsonRpcBody, is(true));
        assertThat( ((JsonRpcBody) rebuiltRequest.getBody()).getMethod(), is("tools/call"));
    }

    @Test
    public void shouldSerializeExpectationWithJsonRpcBodyAndParamsSchema() throws Exception {
        String paramsSchema = "{\"type\": \"object\", \"properties\": {\"name\": {\"type\": \"string\"}}, \"required\": [\"name\"]}";
        Expectation expectation = Expectation.when(
            request()
                .withMethod("POST")
                .withPath("/rpc")
                .withBody(jsonRpc("tools/call", paramsSchema))
        ).thenRespond(
            HttpResponse.response().withStatusCode(200)
        );

        String json = objectMapper.writeValueAsString(new ExpectationDTO(expectation));
        assertThat(json, notNullValue());

        ExpectationDTO rebuiltDTO = objectMapper.readValue(json, ExpectationDTO.class);
        Expectation rebuilt = rebuiltDTO.buildObject();

        HttpRequest rebuiltRequest = (HttpRequest) rebuilt.getHttpRequest();
        JsonRpcBody rebuiltBody = (JsonRpcBody) rebuiltRequest.getBody();
        assertThat( rebuiltBody.getMethod(), is("tools/call"));
        assertThat(rebuiltBody.getParamsSchema(), notNullValue());
    }

    @Test
    public void shouldAllowBothSseResponseAndRegularResponse() {
        Expectation expectation = Expectation.when(request())
            .thenRespond(HttpResponse.response().withStatusCode(200))
            .thenRespondWithSse(sseResponse().withEvent(sseEvent().withData("test")));
        assertThat(expectation.getHttpResponse(), notNullValue());
        assertThat(expectation.getHttpSseResponse(), notNullValue());
    }
}

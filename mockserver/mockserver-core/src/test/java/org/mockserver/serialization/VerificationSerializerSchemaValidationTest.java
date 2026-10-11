package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.serialization.model.HttpRequestDTO;
import org.mockserver.serialization.model.HttpResponseDTO;
import org.mockserver.serialization.model.VerificationDTO;
import org.mockserver.serialization.model.VerificationTimesDTO;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationTimes;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.model.OpenAPIDefinition.openAPI;
import static org.mockserver.verify.Verification.verification;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * @author jamesdbloom
 */
public class VerificationSerializerSchemaValidationTest {

    @Test
    public void shouldDeserializeCompleteObject() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"method\" : \"GET\"," + NEW_LINE +
            "    \"path\" : \"somepath\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 2," + NEW_LINE +
            "    \"atMost\" : 3" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verification, is(new VerificationDTO()
            .setHttpRequest(new HttpRequestDTO(request().withMethod("GET").withPath("somepath")))
            .setTimes(new VerificationTimesDTO(VerificationTimes.between(2, 3)))
            .buildObject()));
    }

    @Test
    public void shouldDeserializeOpenAPIRequestMatcher() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"specUrlOrPayload\" : \"org/mockserver/openapi/openapi_petstore_example.json\"," + NEW_LINE +
            "    \"operationId\" : \"listPets\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1," + NEW_LINE +
            "    \"atMost\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat(verification, is(verification()
            .withRequest(openAPI("org/mockserver/openapi/openapi_petstore_example.json", "listPets"))
            .withTimes(VerificationTimes.exactly(1))));
    }

    @Test
    public void shouldRoundTripOpenAPIRequestMatcher() {
        // given
        VerificationSerializer verificationSerializer = new VerificationSerializer(new MockServerLogger());
        Verification verification = verification()
            .withRequest(openAPI("org/mockserver/openapi/openapi_petstore_example.json", "showPetById").withContextPathPrefix("/api"))
            .withTimes(VerificationTimes.atLeast(2));

        // then
        assertThat(verificationSerializer.deserialize(verificationSerializer.serialize(verification)), is(verification));
    }

    @Test
    public void shouldDeserializePartialObject() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat( verification, is(new VerificationDTO()
            .setHttpRequest(new HttpRequestDTO(request()))
            .buildObject()));
    }

    @Test
    public void shouldSerializeCompleteObject() {
        // when
        String jsonExpectation = new VerificationSerializer(new MockServerLogger()).serialize(
            new VerificationDTO()
                .setHttpRequest(new HttpRequestDTO(request().withMethod("GET").withPath("somepath")))
                .setTimes(new VerificationTimesDTO(VerificationTimes.between(2, 3)))
                .buildObject()
        );

        // then
        assertThat( jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"method\" : \"GET\"," + NEW_LINE +
            "    \"path\" : \"somepath\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 2," + NEW_LINE +
            "    \"atMost\" : 3" + NEW_LINE +
            "  }" + NEW_LINE +
            "}"));
    }

    @Test
    public void shouldSerializePartialObject() {
        // when
        String jsonExpectation = new VerificationSerializer(new MockServerLogger()).serialize(
            new VerificationDTO()
                .setHttpRequest(new HttpRequestDTO(request()))
                .buildObject()
        );

        // then
        assertThat( jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequest\" : { }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1," + NEW_LINE +
            "    \"atMost\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}"));
    }

    @Test
    public void shouldDeserializeObjectWithTimeout() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"/some/path\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"timeout\" : 5000" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then (schema accepts the additive timeout field and it rides through to the model)
        assertThat(verification.getHttpRequest(), is(request().withPath("/some/path")));
        assertThat(verification.getTimeout(), is(5000L));
    }

    @Test
    public void shouldSerializeObjectWithTimeout() {
        // when
        String jsonExpectation = new VerificationSerializer(new MockServerLogger()).serialize(
            new VerificationDTO()
                .setHttpRequest(new HttpRequestDTO(request().withPath("/some/path")))
                .setTimes(new VerificationTimesDTO(VerificationTimes.atLeast(1)))
                .setTimeout(5000L)
                .buildObject()
        );

        // then (round-trips back through deserialize, proving the serialized JSON is schema-valid)
        Verification roundTripped = new VerificationSerializer(new MockServerLogger()).deserialize(jsonExpectation);
        assertThat(roundTripped.getHttpRequest(), is(request().withPath("/some/path")));
        assertThat(roundTripped.getTimeout(), is(5000L));
    }

    @Test
    public void shouldDeserializeObjectWithResponse() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"/some/path\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat(verification.getHttpRequest(), is(request().withPath("/some/path")));
        assertThat(verification.getHttpResponse(), is(response().withStatusCode(200)));
    }

    @Test
    public void shouldDeserializeObjectWithResponseOnly() {
        // given
        String requestBytes = "{" + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }" + NEW_LINE +
            "}";

        // when
        Verification verification = new VerificationSerializer(new MockServerLogger()).deserialize(requestBytes);

        // then
        assertThat(verification.getHttpResponse(), is(response().withStatusCode(200)));
    }

    @Test
    public void shouldSerializeObjectWithResponse() {
        // when
        String jsonExpectation = new VerificationSerializer(new MockServerLogger()).serialize(
            new VerificationDTO()
                .setHttpRequest(new HttpRequestDTO(request().withPath("/some/path")))
                .setHttpResponse(new HttpResponseDTO(response().withStatusCode(200)))
                .setTimes(new VerificationTimesDTO(VerificationTimes.atLeast(1)))
                .buildObject()
        );

        // then
        assertThat(jsonExpectation, is("{" + NEW_LINE +
            "  \"httpRequest\" : {" + NEW_LINE +
            "    \"path\" : \"/some/path\"" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"httpResponse\" : {" + NEW_LINE +
            "    \"statusCode\" : 200" + NEW_LINE +
            "  }," + NEW_LINE +
            "  \"times\" : {" + NEW_LINE +
            "    \"atLeast\" : 1" + NEW_LINE +
            "  }" + NEW_LINE +
            "}"));
    }
}

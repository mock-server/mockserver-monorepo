package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;
import org.mockserver.matchers.MatchType;
import org.mockserver.model.JsonBody;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.JsonBodyDTO;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.core.Is.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.JsonBody.json;

public class JsonBodyDTOSerializerTest {

    @Test
    public void shouldSerializeJsonBodyDTO() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}"))),
            is("{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOAsObjectPrettyPrintedWithoutDefaultFields() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(new JsonBodyDTO(json(new JsonBodySerializerTest.TestObject()))),
            equalTo("{" + NEW_LINE +
                "  \"fieldOne\" : \"valueOne\"," + NEW_LINE +
                "  \"fieldTwo\" : \"valueTwo\"" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOAsObjectPrettyPrintedWithDefaultFields() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, true).writeValueAsString(new JsonBodyDTO(json(new JsonBodySerializerTest.TestObject()))),
            equalTo("{" + NEW_LINE +
                "  \"type\" : \"JSON\"," + NEW_LINE +
                "  \"json\" : {" + NEW_LINE +
                "    \"fieldOne\" : \"valueOne\"," + NEW_LINE +
                "    \"fieldTwo\" : \"valueTwo\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithDefaultMatchType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.ONLY_MATCHING_FIELDS))),
            is("{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithNoneDefaultMatchType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT))),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithNoneDefaultMatchTypeAndCharset() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", null, MediaType.create("application", "json").withCharset(StandardCharsets.UTF_16), MatchType.STRICT))),
            is("{\"contentType\":\"application/json; charset=utf-16\",\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithDefaultMatchTypeAndContentType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", null, MediaType.JSON_UTF_8, MatchType.STRICT))),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithNoneDefaultMatchTypeAndContentType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", null, MediaType.parse("application/json; charset=utf-16"), MatchType.STRICT))),
            is("{\"contentType\":\"application/json; charset=utf-16\",\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithNot() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT), true)),
            is("{\"not\":true,\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithOptional() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT)).withOptional(true)),
            is("{\"optional\":true,\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithMatchNumbersAsStrings() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{\"value\":1}", null, null, MatchType.ONLY_MATCHING_FIELDS, true))),
            is("{\"type\":\"JSON\",\"json\":{\"value\":1},\"matchNumbersAsStrings\":true}"));
    }

    @Test
    public void shouldNotSerializeJsonBodyDTOWithMatchNumbersAsStringsFalse() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{\"value\":1}", null, null, MatchType.ONLY_MATCHING_FIELDS, false))),
            is("{\"value\":1}"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithStringPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("\"test\""))),
            is("\"test\""));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithStringPrimitiveAndNonDefaultFields() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("\"test\"", MatchType.STRICT))),
            is("{\"type\":\"JSON\",\"json\":\"test\",\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithNumberPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("42"))),
            is("42"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithBooleanPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("true"))),
            is("true"));
    }

    @Test
    public void shouldSerializeJsonBodyDTOWithNullPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("null"))),
            is("null"));
    }

    @Test
    public void shouldEmitRawBytesWhenEmitRawBytesAttributeSetAndWireBytesDifferFromCanonical() throws JsonProcessingException {
        // the recorded-request retrieval path sets the "emitRawBytes" attribute so the original wire bytes survive
        // the round-trip (#2374) when they differ from the canonical serialisation of the parsed JSON value
        assertThat(ObjectMapperFactory.createObjectMapper().writer().withAttribute("emitRawBytes", Boolean.TRUE)
                .writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT))),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"rawBytes\":\"e2ZpZWxkT25lOiAidmFsdWVPbmUiLCAiZmllbGRUd28iOiAidmFsdWVUd28ifQ==\",\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldNotEmitRawBytesWhenEmitRawBytesAttributeNotSet() throws JsonProcessingException {
        // outside the retrieval path (matcher/expectation serialisation, diagnostic logs) the attribute is unset so
        // rawBytes is never emitted, keeping that output clean and human-readable
        assertThat(ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(new JsonBodyDTO(new JsonBody("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT))),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldNotEmitRawBytesWhenEmitRawBytesAttributeSetButWireBytesMatchCanonical() throws JsonProcessingException {
        // even with the attribute set, a body whose wire bytes already equal the canonical serialisation carries no
        // extra information, so no rawBytes field is emitted (no output bloat)
        assertThat(ObjectMapperFactory.createObjectMapper().writer().withAttribute("emitRawBytes", Boolean.TRUE)
                .writeValueAsString(new JsonBodyDTO(new JsonBody("{\"fieldOne\":\"valueOne\"}", "{\"fieldOne\":\"valueOne\"}".getBytes(StandardCharsets.UTF_8), MediaType.JSON_UTF_8, MatchType.STRICT))),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldPreserveWholeNumberDoubleWhenSerializingJsonBodyDTO() throws JsonProcessingException {
        // #2658 - the client Expectation PUT path (JsonBodyDTOSerializer) must not corrupt 275.0 into a bare 275
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{\"amount\":275.0}"))),
            is("{\"amount\":275.0}"));
    }

    @Test
    public void shouldPreserveTrailingZeroDecimalsWhenSerializingJsonBodyDTO() throws JsonProcessingException {
        // #1740 - trailing-zero decimals such as 0.00 / 1.50 must survive serialisation
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{\"zero\":0.00,\"half\":1.50}"))),
            is("{\"zero\":0.00,\"half\":1.50}"));
    }

    @Test
    public void shouldPreserveWholeNumberDoubleNestedInArrayWhenSerializingJsonBodyDTO() throws JsonProcessingException {
        // the reporter's actual shape - a whole-number double nested inside an array of objects
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{\"payments\":[{\"amount\":275.0,\"currency\":\"GBP\"}]}"))),
            is("{\"payments\":[{\"amount\":275.0,\"currency\":\"GBP\"}]}"));
    }

    @Test
    public void shouldNotEmitBareIntegerForWholeNumberDoubleWhenSerializingJsonBodyDTO() throws JsonProcessingException {
        // the pre-fix behaviour emitted a bare 275 for the double 275.0 - assert that corruption is gone
        String serialized = ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{\"amount\":275.0}")));
        assertThat(serialized.contains("275.0"), is(true));
        assertThat(serialized.equals("{\"amount\":275}"), is(false));
    }

    @Test
    public void shouldLeaveIntegerLiteralUnchangedWhenSerializingJsonBodyDTO() throws JsonProcessingException {
        // non-regression: a genuine integer must NOT be turned into a decimal
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(new JsonBodyDTO(new JsonBody("{\"value\":1}"))),
            is("{\"value\":1}"));
    }
}

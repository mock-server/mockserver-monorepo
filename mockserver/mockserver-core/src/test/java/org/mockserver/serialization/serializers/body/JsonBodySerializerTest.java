package org.mockserver.serialization.serializers.body;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.junit.Test;
import org.mockserver.matchers.MatchType;
import org.mockserver.model.MediaType;
import org.mockserver.serialization.ObjectMapperFactory;

import java.nio.charset.StandardCharsets;

import static junit.framework.TestCase.assertTrue;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.JsonBody.json;
import static org.mockserver.model.Not.not;

public class JsonBodySerializerTest {

    public static class TestObject {
        private String fieldOne = "valueOne";
        private String fieldTwo = "valueTwo";

        public String getFieldOne() {
            return fieldOne;
        }

        public void setFieldOne(String fieldOne) {
            this.fieldOne = fieldOne;
        }

        public String getFieldTwo() {
            return fieldTwo;
        }

        public void setFieldTwo(String fieldTwo) {
            this.fieldTwo = fieldTwo;
        }
    }

    @Test
    public void shouldSerializeJsonBodyAsObject() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json(new TestObject())),
            equalTo("{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyAsObjectPrettyPrintedWithoutDefaultFields() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, false).writeValueAsString(json(new TestObject())),
            equalTo("{" + NEW_LINE +
                "  \"fieldOne\" : \"valueOne\"," + NEW_LINE +
                "  \"fieldTwo\" : \"valueTwo\"" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldSerializeJsonBodyAsObjectPrettyPrintedWithDefaultFields() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper(true, true).writeValueAsString(json(new TestObject())),
            equalTo("{" + NEW_LINE +
                "  \"type\" : \"JSON\"," + NEW_LINE +
                "  \"json\" : {" + NEW_LINE +
                "    \"fieldOne\" : \"valueOne\"," + NEW_LINE +
                "    \"fieldTwo\" : \"valueTwo\"" + NEW_LINE +
                "  }" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldSerializeJsonBody() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}")),
            is("{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithDefaultMatchType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.ONLY_MATCHING_FIELDS)),
            is("{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithMatchType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT)),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithNoneDefaultMatchTypeAndCharset() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", StandardCharsets.UTF_16, MatchType.STRICT)),
            is("{\"contentType\":\"application/json; charset=utf-16\",\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithDefaultMatchTypeAndContentType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MediaType.JSON_UTF_8, MatchType.STRICT)),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithNoneDefaultMatchTypeAndContentType() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MediaType.parse("application/json; charset=utf-16"), MatchType.STRICT)),
            is("{\"contentType\":\"application/json; charset=utf-16\",\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithMatchTypeWithNot() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(not(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT))),
            is("{\"not\":true,\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithMatchTypeWithOptional() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT).withOptional(true)),
            is("{\"optional\":true,\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithMatchNumbersAsStrings() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{\"value\":1}", MatchType.ONLY_MATCHING_FIELDS, true)),
            is("{\"type\":\"JSON\",\"json\":{\"value\":1},\"matchNumbersAsStrings\":true}"));
    }

    @Test
    public void shouldNotSerializeJsonBodyWithMatchNumbersAsStringsFalse() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{\"value\":1}", MatchType.ONLY_MATCHING_FIELDS, false)),
            is("{\"value\":1}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithStringPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("\"test\"")),
            is("\"test\""));
    }

    @Test
    public void shouldSerializeJsonBodyWithStringPrimitiveAndNonDefaultFields() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("\"test\"", MatchType.STRICT)),
            is("{\"type\":\"JSON\",\"json\":\"test\",\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldSerializeJsonBodyWithNumberPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("42")),
            is("42"));
    }

    @Test
    public void shouldSerializeJsonBodyWithBooleanPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("true")),
            is("true"));
    }

    @Test
    public void shouldSerializeJsonBodyWithNullPrimitive() throws JsonProcessingException {
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("null")),
            is("null"));
    }

    @Test
    public void shouldEmitRawBytesWhenEmitRawBytesAttributeSetAndWireBytesDifferFromCanonical() throws JsonProcessingException {
        // the recorded-request retrieval path sets the "emitRawBytes" attribute so the original wire bytes survive
        // the round-trip (#2374) when they differ from the canonical serialisation of the parsed JSON value
        assertThat(ObjectMapperFactory.createObjectMapper().writer().withAttribute("emitRawBytes", Boolean.TRUE)
                .writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT)),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"rawBytes\":\"e2ZpZWxkT25lOiAidmFsdWVPbmUiLCAiZmllbGRUd28iOiAidmFsdWVUd28ifQ==\",\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldNotEmitRawBytesWhenEmitRawBytesAttributeNotSet() throws JsonProcessingException {
        // outside the retrieval path (matcher/expectation serialisation, diagnostic logs) the attribute is unset so
        // rawBytes is never emitted, keeping that output clean and human-readable
        assertThat(ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(json("{fieldOne: \"valueOne\", \"fieldTwo\": \"valueTwo\"}", MatchType.STRICT)),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\",\"fieldTwo\":\"valueTwo\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldNotEmitRawBytesWhenEmitRawBytesAttributeSetButWireBytesMatchCanonical() throws JsonProcessingException {
        // even with the attribute set, a body whose wire bytes already equal the canonical serialisation carries no
        // extra information, so no rawBytes field is emitted (no output bloat)
        assertThat(ObjectMapperFactory.createObjectMapper().writer().withAttribute("emitRawBytes", Boolean.TRUE)
                .writeValueAsString(json("{\"fieldOne\":\"valueOne\"}", MatchType.STRICT)),
            is("{\"type\":\"JSON\",\"json\":{\"fieldOne\":\"valueOne\"},\"matchType\":\"STRICT\"}"));
    }

    @Test
    public void shouldPreserveWholeNumberDoubleWhenSerializingJsonBody() throws JsonProcessingException {
        // #2658 - a whole-number double such as 275.0 must NOT be silently normalised to the bare
        // integer 275 during client-side serialisation; that corruption made byte-identical request
        // bodies (275.0) stop matching an expectation the client had just created
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{\"amount\":275.0}")),
            is("{\"amount\":275.0}"));
    }

    @Test
    public void shouldPreserveTrailingZeroDecimalsWhenSerializingJsonBody() throws JsonProcessingException {
        // #1740 - USE_BIG_DECIMAL_FOR_FLOATS alone never achieved its stated goal because Jackson strips
        // trailing BigDecimal zeroes at node construction; keeping the BigDecimals exact preserves 0.00 / 1.50
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{\"zero\":0.00,\"half\":1.50}")),
            is("{\"zero\":0.00,\"half\":1.50}"));
    }

    @Test
    public void shouldPreserveWholeNumberDoubleNestedInArrayWhenSerializingJsonBody() throws JsonProcessingException {
        // the reporter's actual shape - a whole-number double nested inside an array of objects
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{\"payments\":[{\"amount\":275.0,\"currency\":\"GBP\"}]}")),
            is("{\"payments\":[{\"amount\":275.0,\"currency\":\"GBP\"}]}"));
    }

    @Test
    public void shouldNotEmitBareIntegerForWholeNumberDoubleWhenSerializingJsonBody() throws JsonProcessingException {
        // the pre-fix behaviour emitted a bare 275 (an integer literal) for the double 275.0 - assert that is gone
        String serialized = ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{\"amount\":275.0}"));
        assertThat(serialized.contains("275.0"), is(true));
        assertThat(serialized.equals("{\"amount\":275}"), is(false));
    }

    @Test
    public void shouldLeaveIntegerLiteralUnchangedWhenSerializingJsonBody() throws JsonProcessingException {
        // the key non-regression assertion: a genuine integer must NOT be turned into a decimal (1 stays 1, not 1.0)
        assertThat(ObjectMapperFactory.createObjectMapper().writeValueAsString(json("{\"value\":1}")),
            is("{\"value\":1}"));
    }
}

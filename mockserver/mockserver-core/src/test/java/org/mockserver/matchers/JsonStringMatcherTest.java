package org.mockserver.matchers;

import com.fasterxml.jackson.core.JsonParseException;
import org.hamcrest.BaseMatcher;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.junit.Test;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.logging.MockServerLogger;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.matchers.NotMatcher.notMatcher;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.core.Is.is;
import static org.mockserver.model.HttpRequest.request;

/**
 * @author jamesdbloom
 */
public class JsonStringMatcherTest {

    @Test
    public void shouldMatchExactMatchingJson() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
    }

    @Test
    public void shouldMatchExactMatchingJsonWithPlaceholder() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"optional\": true," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"optional\": \"${json-unit.any-boolean}\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"${json-unit.ignore-element}\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchExactMatchingJson() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(notMatcher(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS)).matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchMatchingSubJson() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
    }

    @Test
    public void shouldMatchMatchingSubJsonWithSomeSubJsonFields() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"glossary\": {" + NEW_LINE +
            "        \"title\": \"example glossary\"," + NEW_LINE +
            "        \"GlossDiv\": {" + NEW_LINE +
            "            \"title\": \"S\"," + NEW_LINE +
            "            \"GlossList\": {" + NEW_LINE +
            "                \"GlossEntry\": {" + NEW_LINE +
            "                    \"ID\": \"SGML\"," + NEW_LINE +
            "                    \"SortAs\": \"SGML\"," + NEW_LINE +
            "                    \"GlossTerm\": \"Standard Generalized Markup Language\"," + NEW_LINE +
            "                    \"Acronym\": \"SGML\"," + NEW_LINE +
            "                    \"Abbrev\": \"ISO 8879:1986\"," + NEW_LINE +
            "                    \"GlossDef\": {" + NEW_LINE +
            "                        \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\"," + NEW_LINE +
            "                        \"GlossSeeAlso\": [" + NEW_LINE +
            "                            \"GML\"," + NEW_LINE +
            "                            \"XML\"" + NEW_LINE +
            "                        ]" + NEW_LINE +
            "                    }, " + NEW_LINE +
            "                    \"GlossSee\": \"markup\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            }" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"glossary\": {" + NEW_LINE +
            "        \"GlossDiv\": {" + NEW_LINE +
            "            \"title\": \"S\"," + NEW_LINE +
            "            \"GlossList\": {" + NEW_LINE +
            "                \"GlossEntry\": {" + NEW_LINE +
            "                    \"ID\": \"SGML\"," + NEW_LINE +
            "                    \"Abbrev\": \"ISO 8879:1986\"," + NEW_LINE +
            "                    \"GlossDef\": {" + NEW_LINE +
            "                        \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\"" + NEW_LINE +
            "                    }, " + NEW_LINE +
            "                    \"GlossSee\": \"markup\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            }" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchNotMatchingSubJsonWithSomeSubJsonFields() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"glossary\": {" + NEW_LINE +
            "        \"title\": \"example glossary\"," + NEW_LINE +
            "        \"GlossDiv\": {" + NEW_LINE +
            "            \"title\": \"S\"," + NEW_LINE +
            "            \"GlossList\": {" + NEW_LINE +
            "                \"GlossEntry\": {" + NEW_LINE +
            "                    \"ID\": \"SGML\"," + NEW_LINE +
            "                    \"SortAs\": \"SGML\"," + NEW_LINE +
            "                    \"GlossTerm\": \"Standard Generalized Markup Language\"," + NEW_LINE +
            "                    \"Acronym\": \"SGML\"," + NEW_LINE +
            "                    \"Abbrev\": \"ISO 8879:1986\"," + NEW_LINE +
            "                    \"GlossDef\": {" + NEW_LINE +
            "                        \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\"," + NEW_LINE +
            "                        \"GlossSeeAlso\": [" + NEW_LINE +
            "                            \"GML\"," + NEW_LINE +
            "                            \"XML\"" + NEW_LINE +
            "                        ]" + NEW_LINE +
            "                    }, " + NEW_LINE +
            "                    \"GlossSee\": \"markup\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            }" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"glossary\": {" + NEW_LINE +
            "        \"GlossDiv\": {" + NEW_LINE +
            "            \"title\": \"S\"," + NEW_LINE +
            "            \"GlossList\": {" + NEW_LINE +
            "                \"GlossEntry\": {" + NEW_LINE +
            "                    \"ID\": \"SGML\"," + NEW_LINE +
            "                    \"Abbrev\": \"ISO 8879:1986\"," + NEW_LINE +
            "                    \"GlossDef\": {" + NEW_LINE +
            "                        \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\"" + NEW_LINE +
            "                    }, " + NEW_LINE +
            "                    \"GlossSee\": \"markup\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            }" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.STRICT).matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchNotMatchingSubJsonWithSomeSubJsonFields() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"glossary\": {" + NEW_LINE +
            "        \"title\": \"example glossary\"," + NEW_LINE +
            "        \"GlossDiv\": {" + NEW_LINE +
            "            \"title\": \"S\"," + NEW_LINE +
            "            \"GlossList\": {" + NEW_LINE +
            "                \"GlossEntry\": {" + NEW_LINE +
            "                    \"ID\": \"SGML\"," + NEW_LINE +
            "                    \"SortAs\": \"SGML\"," + NEW_LINE +
            "                    \"GlossTerm\": \"Standard Generalized Markup Language\"," + NEW_LINE +
            "                    \"Acronym\": \"SGML\"," + NEW_LINE +
            "                    \"Abbrev\": \"ISO 8879:1986\"," + NEW_LINE +
            "                    \"GlossDef\": {" + NEW_LINE +
            "                        \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\"," + NEW_LINE +
            "                        \"GlossSeeAlso\": [" + NEW_LINE +
            "                            \"GML\"," + NEW_LINE +
            "                            \"XML\"" + NEW_LINE +
            "                        ]" + NEW_LINE +
            "                    }, " + NEW_LINE +
            "                    \"GlossSee\": \"markup\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            }" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(notMatcher(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"glossary\": {" + NEW_LINE +
            "        \"GlossDiv\": {" + NEW_LINE +
            "            \"title\": \"S\"," + NEW_LINE +
            "            \"GlossList\": {" + NEW_LINE +
            "                \"GlossEntry\": {" + NEW_LINE +
            "                    \"ID\": \"SGML\"," + NEW_LINE +
            "                    \"Abbrev\": \"ISO 8879:1986\"," + NEW_LINE +
            "                    \"GlossDef\": {" + NEW_LINE +
            "                        \"para\": \"A meta-markup language, used to create markup languages such as DocBook.\"" + NEW_LINE +
            "                    }, " + NEW_LINE +
            "                    \"GlossSee\": \"markup\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            }" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.STRICT)).matches(null, matched), is(true));
    }

    @Test
    public void shouldMatchMatchingSubJsonWithDifferentArrayOrder() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchMatchingSubJsonWithDifferentArrayOrder() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.STRICT).matches(null, matched), is(false));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.STRICT).matches(null, matched), is(false));
    }

    @Test
    public void shouldNotMatchIllegalJson() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "illegal_json", MatchType.ONLY_MATCHING_FIELDS).matches(null, "illegal_json"), is(false));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "illegal_json", MatchType.ONLY_MATCHING_FIELDS).matches(null, "some_other_illegal_json"), is(false));
    }

    @Test
    public void shouldNotMatchNullExpectation() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), null, MatchType.ONLY_MATCHING_FIELDS).matches(null, "some_value"), is(true));
    }

    @Test
    public void shouldNotMatchEmptyExpectation() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "", MatchType.ONLY_MATCHING_FIELDS).matches(null, "some_value"), is(true));
    }

    @Test
    public void shouldNotMatchNonMatchingJson() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"wrong_value\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchJsonForIncorrectArrayOrder() {
        // given
        String matched = "{id:1,pets:[\"dog\",\"cat\",\"fish\"]}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{id:1,pets:[\"cat\",\"dog\",\"fish\"]}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchJsonForIncorrectArrayOrder() {
        // given
        String matched = "{id:1,pets:[\"dog\",\"cat\",\"fish\"]}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{id:1,pets:[\"cat\",\"dog\",\"fish\"]}", MatchType.STRICT).matches(null, matched), is(false));
    }

    @Test
    public void shouldMatchJsonForExtraField() {
        // given
        String matched = "{id:1,pets:[\"dog\",\"cat\",\"fish\"],extraField:\"extraValue\"}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{id:1,pets:[\"dog\",\"cat\",\"fish\"]}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(true));
    }

    @Test
    public void shouldNotMatchJsonForExtraField() {
        // given
        String matched = "{id:1,pets:[\"dog\",\"cat\",\"fish\"],extraField:\"extraValue\"}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{id:1,pets:[\"dog\",\"cat\",\"fish\"]}", MatchType.STRICT).matches(null, matched), is(false));
    }

    @Test
    public void shouldNotMatchNonMatchingSubJson() {
        // given
        String matched = "" +
            "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"File\"," + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}";

        // then
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"id\": \"file\"," + NEW_LINE +
            "        \"value\": \"other_value\"" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(false));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{" + NEW_LINE +
            "    \"menu\": {" + NEW_LINE +
            "        \"popup\": {" + NEW_LINE +
            "            \"menuitem\": [" + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"New\"," + NEW_LINE +
            "                    \"onclick\": \"CreateNewDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Open\"," + NEW_LINE +
            "                    \"onclick\": \"OpenDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }, " + NEW_LINE +
            "                {" + NEW_LINE +
            "                    \"value\": \"Close\"," + NEW_LINE +
            "                    \"onclick\": \"CloseDoc()\"" + NEW_LINE +
            "                }" + NEW_LINE +
            "            ]" + NEW_LINE +
            "        }" + NEW_LINE +
            "    }" + NEW_LINE +
            "}", MatchType.ONLY_MATCHING_FIELDS).matches(null, matched), is(false));
    }

    @Test
    public void shouldNotMatchNullTest() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "some_value", MatchType.ONLY_MATCHING_FIELDS).matches(null, null), is(false));
    }

    @Test
    public void shouldNotMatchEmptyTest() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "some_value", MatchType.ONLY_MATCHING_FIELDS).matches(null, ""), is(false));
    }

    @Test
    public void shouldMatchBasicValues() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "null", MatchType.ONLY_MATCHING_FIELDS).matches(null, "null"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "1", MatchType.ONLY_MATCHING_FIELDS).matches(null, "1"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "true", MatchType.ONLY_MATCHING_FIELDS).matches(null, "true"), is(true));
    }

    @Test
    public void shouldNotMatchIntegerAndDoubleByDefault() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1}", MatchType.ONLY_MATCHING_FIELDS).matches(null, "{\"value\":1.0}"), is(false));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1.0}", MatchType.ONLY_MATCHING_FIELDS).matches(null, "{\"value\":1}"), is(false));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1}", MatchType.STRICT).matches(null, "{\"value\":1.0}"), is(false));
    }

    @Test
    public void shouldMatchIntegerAndDoubleWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":1.0}"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1.0}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":1}"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1}", MatchType.STRICT, true).matches(null, "{\"value\":1.0}"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1.0}", MatchType.STRICT, true).matches(null, "{\"value\":1}"), is(true));
    }

    @Test
    public void shouldMatchIdenticalNumbersWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":1}"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1.5}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":1.5}"), is(true));
    }

    @Test
    public void shouldNotMatchDifferentNumbersWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":2}"), is(false));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1.0}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":1.1}"), is(false));
    }

    @Test
    public void shouldMatchNestedIntegerAndDoubleWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"outer\":{\"value\":1}}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"outer\":{\"value\":1.0}}"), is(true));
    }

    @Test
    public void shouldMatchArrayNumbersWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"values\":[1,2,3]}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"values\":[1.0,2.0,3.0]}"), is(true));
    }

    @Test
    public void shouldMatchNegativeNumbersWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":-1}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":-1.0}"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":-1}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":-2}"), is(false));
    }

    @Test
    public void shouldMatchZeroWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":0}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":0.0}"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":0.0}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":0}"), is(true));
    }

    @Test
    public void shouldMatchScientificNotationWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":100}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":1e2}"), is(true));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1e2}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":100.0}"), is(true));
    }

    @Test
    public void shouldNotMatchStringNumberAgainstNumericWithMatchNumbersAsStrings() {
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":\"1\"}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":1}"), is(false));
        assertThat(new JsonStringMatcher(new MockServerLogger(), "{\"value\":1}", MatchType.ONLY_MATCHING_FIELDS, true).matches(null, "{\"value\":\"1\"}"), is(false));
    }

    @Test
    public void showHaveCorrectEqualsBehaviour() {
        MockServerLogger mockServerLogger = new MockServerLogger();
        assertThat(new JsonStringMatcher(mockServerLogger, "some_value", MatchType.ONLY_MATCHING_FIELDS), is(new JsonStringMatcher(mockServerLogger, "some_value", MatchType.ONLY_MATCHING_FIELDS)));
    }

    @Test
    public void shouldMatchUsingCustomJsonUnitMatcher() {
        String previous = ConfigurationProperties.customJsonUnitMatchersClass();
        try {
            ConfigurationProperties.customJsonUnitMatchersClass(LargerThanMatcherProvider.class.getName());
            CustomJsonUnitMatcherLoader.reset();

            assertThat(new JsonStringMatcher(
                new MockServerLogger(),
                "{\"price\":\"${json-unit.matches:largerThan}\"}",
                MatchType.ONLY_MATCHING_FIELDS
            ).matches(null, "{\"price\":250}"), is(true));
        } finally {
            ConfigurationProperties.customJsonUnitMatchersClass(previous);
            CustomJsonUnitMatcherLoader.reset();
        }
    }

    @Test
    public void shouldNotMatchWhenCustomJsonUnitMatcherRejects() {
        String previous = ConfigurationProperties.customJsonUnitMatchersClass();
        try {
            ConfigurationProperties.customJsonUnitMatchersClass(LargerThanMatcherProvider.class.getName());
            CustomJsonUnitMatcherLoader.reset();

            assertThat(new JsonStringMatcher(
                new MockServerLogger(),
                "{\"price\":\"${json-unit.matches:largerThan}\"}",
                MatchType.ONLY_MATCHING_FIELDS
            ).matches(null, "{\"price\":50}"), is(false));
        } finally {
            ConfigurationProperties.customJsonUnitMatchersClass(previous);
            CustomJsonUnitMatcherLoader.reset();
        }
    }

    @Test
    public void shouldFallBackWhenCustomMatcherClassMisconfigured() {
        String previous = ConfigurationProperties.customJsonUnitMatchersClass();
        try {
            ConfigurationProperties.customJsonUnitMatchersClass("org.mockserver.does.not.Exist");
            CustomJsonUnitMatcherLoader.reset();

            // a plain JSON body that references no custom matcher still matches normally
            assertThat(new JsonStringMatcher(
                new MockServerLogger(),
                "{\"price\":250}",
                MatchType.ONLY_MATCHING_FIELDS
            ).matches(null, "{\"price\":250}"), is(true));
        } finally {
            ConfigurationProperties.customJsonUnitMatchersClass(previous);
            CustomJsonUnitMatcherLoader.reset();
        }
    }

    @Test
    public void shouldReportCauseWhenJsonMatchThrows() {
        // given - a body that cannot be parsed as JSON makes the match throw rather than simply not match
        MatchDifference context = new MatchDifference(true, request().withPath("/some/path"));
        context.currentField(MatchDifference.Field.BODY);

        // when
        assertThat(new JsonStringMatcher(
            new MockServerLogger(),
            "{\"id\":\"abc\"}",
            MatchType.ONLY_MATCHING_FIELDS
        ).matches(context, "this is not json"), is(false));

        // then - the reported difference names the exception, so the failure can be diagnosed
        String differences = String.join(NEW_LINE, context.getDifferences(MatchDifference.Field.BODY));
        assertThat(differences, containsString("exception while perform json match failed"));
        assertThat(differences, containsString("failed because:"));
        assertThat(differences, containsString(JsonParseException.class.getName()));
    }

    @Test
    public void shouldNotIncludeLazilyPopulatedCachesInEquality() {
        // given - two matchers built from the same JSON, only one of which has been used
        JsonStringMatcher used = new JsonStringMatcher(new MockServerLogger(), "{\"id\":\"abc\"}", MatchType.ONLY_MATCHING_FIELDS);
        JsonStringMatcher unused = new JsonStringMatcher(new MockServerLogger(), "{\"id\":\"abc\"}", MatchType.ONLY_MATCHING_FIELDS);

        // when - matching populates the used matcher's lazy caches
        assertThat(used.matches(null, "{\"id\":\"abc\"}"), is(true));

        // then - having matched does not change what the matcher is
        assertThat(used, is(unused));
        assertThat(used.hashCode(), is(unused.hashCode()));
    }

    public static class LargerThanMatcherProvider implements CustomJsonUnitMatcherProvider {

        @Override
        public Map<String, Matcher<?>> jsonUnitMatchers() {
            Map<String, Matcher<?>> matchers = new HashMap<>();
            matchers.put("largerThan", new BaseMatcher<Object>() {
                @Override
                public boolean matches(Object item) {
                    if (item == null) {
                        return false;
                    }
                    try {
                        return new BigDecimal(item.toString()).compareTo(BigDecimal.valueOf(100)) > 0;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }

                @Override
                public void describeTo(Description description) {
                    description.appendText("a number larger than 100");
                }
            });
            return matchers;
        }
    }
}

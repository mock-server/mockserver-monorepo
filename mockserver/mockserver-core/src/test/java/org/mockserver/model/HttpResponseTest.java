package org.mockserver.model;

import org.json.JSONException;
import org.junit.Test;
import org.mockserver.serialization.Base64Converter;
import org.skyscreamer.jsonassert.JSONAssert;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.ConnectionOptions.connectionOptions;
import static org.mockserver.model.HttpResponse.response;

import static org.hamcrest.Matchers.nullValue;
/**
 * @author jamesdbloom
 */
public class HttpResponseTest {

    public void assertJsonEqualsNonStrict(String json1, String json2) {
        try {
            JSONAssert.assertEquals(json1, json2, false);
        } catch (JSONException jse) {
            throw new IllegalArgumentException(jse.getMessage());
        }
    }

    private final Base64Converter base64Converter = new Base64Converter();

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(response(), is(response()));
        assertThat(response(), not(sameInstance(response())));
    }

    @Test
    public void returnsResponseStatusCode() {
        assertThat(new HttpResponse().withStatusCode(200).getStatusCode(), is(new Integer(200)));
    }

    @Test
    public void returnsResponseReasonPhrase() {
        assertThat(new HttpResponse().withReasonPhrase("reasonPhrase").getReasonPhrase(), is("reasonPhrase"));
    }

    @Test
    public void returnsBody() {
        assertThat(new HttpResponse().withBody("somebody".getBytes(UTF_8)).getBodyAsString(), is(base64Converter.bytesToBase64String("somebody".getBytes(UTF_8))));
        assertThat(new HttpResponse().withBody("somebody").getBodyAsString(), is("somebody"));
        assertThat(new HttpResponse().withBody((byte[]) null).getBodyAsString(), nullValue());
        assertThat(new HttpResponse().withBody((String) null).getBodyAsString(), nullValue());
    }

    @Test
    public void returnsHeaders() {
        assertThat(new HttpResponse().withHeaders(new Header("name", "value")).getHeaderList().get(0), is(new Header("name", "value")));
        assertThat(new HttpResponse().withHeaders(Collections.singletonList(new Header("name", "value"))).getHeaderList().get(0), is(new Header("name", "value")));
        assertThat(new HttpRequest().withHeader(new Header("name", "value")).getHeaderList().get(0), is(new Header("name", "value")));
        assertThat(new HttpRequest().withHeader(new Header("name", "value_one")).withHeader(new Header("name", "value_two")).getHeaderList().get(0), is(new Header("name", "value_one", "value_two")));
    }

    @Test
    public void returnsFirstHeaders() {
        assertThat(new HttpResponse().withHeaders(new Header("name", "value1")).getFirstHeader("name"), is("value1"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "value1", "value2")).getFirstHeader("name"), is("value1"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "value1", "value2"), new Header("name", "value3")).getFirstHeader("name"), is("value1"));
    }

    @Test
    public void returnsFirstHeaderIgnoringCase() {
        assertThat(new HttpResponse().withHeaders(new Header("NAME", "value1")).getFirstHeader("name"), is("value1"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "value1", "value2")).getFirstHeader("NAME"), is("value1"));
        assertThat(new HttpResponse().withHeaders(new Header("NAME", "value1", "value2"), new Header("NAME", "value3"), new Header("NAME", "value4")).getFirstHeader("NAME"), is("value1"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "value1", "value2"), new Header("name", "value3"), new Header("name", "value4")).getFirstHeader("NAME"), is("value1"));
        assertThat(new HttpResponse().withHeaders(new Header("NAME", "value1", "value2"), new Header("name", "value3"), new Header("name", "value4")).getFirstHeader("name"), is("value1"));
    }

    @Test
    public void returnsHeaderByName() {
        assertThat(new HttpResponse().withHeaders(new Header("name", "value")).getHeader("name"), containsInAnyOrder("value"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).getHeader("name"), containsInAnyOrder("valueOne", "valueTwo"));
        assertThat(new HttpResponse().withHeader("name", "valueOne", "valueTwo").getHeader("name"), containsInAnyOrder("valueOne", "valueTwo"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).getHeader("otherName"), hasSize(0));
    }

    @Test
    public void containsHeaderIgnoringCase() {
        assertThat(new HttpResponse().withHeaders(new Header("name", "value")).containsHeader("name", "value"), is(true));
        assertThat(new HttpResponse().withHeaders(new Header("name", "value")).containsHeader("name", "VALUE"), is(true));
        assertThat(new HttpResponse().withHeaders(new Header("name", "value")).containsHeader("NAME", "value"), is(true));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).containsHeader("name", "valueOne"), is(true));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).containsHeader("name", "VALUEONE"), is(true));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).containsHeader("NAME", "valueTwo"), is(true));
        assertThat(new HttpResponse().withHeader("name", "valueOne", "valueTwo").containsHeader("name", "ValueOne"), is(true));
        assertThat(new HttpResponse().withHeader("name", "valueOne", "valueTwo").containsHeader("name", "valueOne"), is(true));
        assertThat(new HttpResponse().withHeader("name", "valueOne", "valueTwo").containsHeader("NAME", "ValueOne"), is(true));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).containsHeader("otherName", "valueOne"), is(false));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).containsHeader("name", "value"), is(false));
    }

    @Test
    public void returnsHeaderByNameIgnoringCase() {
        assertThat(new HttpResponse().withHeaders(new Header("Name", "value")).getHeader("name"), containsInAnyOrder("value"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).getHeader("Name"), containsInAnyOrder("valueOne", "valueTwo"));
        assertThat(new HttpResponse().withHeader("NAME", "valueOne", "valueTwo").getHeader("name"), containsInAnyOrder("valueOne", "valueTwo"));
        assertThat(new HttpResponse().withHeaders(new Header("name", "valueOne", "valueTwo")).getHeader("otherName"), hasSize(0));
    }

    @Test
    public void addDuplicateHeader() {
        assertThat(new HttpResponse().withHeader(new Header("name", "valueOne")).withHeader(new Header("name", "valueTwo")).getHeaderList(), containsInAnyOrder(new Header("name", "valueOne", "valueTwo")));
        assertThat(new HttpResponse().withHeader(new Header("name", "valueOne")).withHeader("name", "valueTwo").getHeaderList(), containsInAnyOrder(new Header("name", "valueOne", "valueTwo")));
    }

    @Test
    public void updatesExistingHeader() {
        assertThat(new HttpResponse().withHeader(new Header("name", "valueOne")).replaceHeader(new Header("name", "valueTwo")).getHeaderList(), containsInAnyOrder(new Header("name", "valueTwo")));
        assertThat(new HttpResponse().withHeader(new Header("name", "valueOne")).replaceHeader("name", "valueTwo").getHeaderList(), containsInAnyOrder(new Header("name", "valueTwo")));
    }

    @Test
    public void returnsCookies() {
        assertThat(new HttpResponse().withCookies(new Cookie("name", "value")).getCookieList().get(0), is(new Cookie("name", "value")));
        assertThat(new HttpResponse().withCookies(new Cookie("name", "")).getCookieList().get(0), is(new Cookie("name", "")));
        assertThat(new HttpResponse().withCookies(new Cookie("name", null)).getCookieList().get(0), is(new Cookie("name", null)));

        assertThat(new HttpResponse().withCookies(Collections.singletonList(new Cookie("name", "value"))).getCookieList().get(0), is(new Cookie("name", "value")));

        assertThat(new HttpResponse().withCookie(new Cookie("name", "value")).getCookieList().get(0), is(new Cookie("name", "value")));
        assertThat(new HttpResponse().withCookie("name", "value").getCookieList().get(0), is(new Cookie("name", "value")));
        assertThat(new HttpResponse().withCookie(new Cookie("name", "")).getCookieList().get(0), is(new Cookie("name", "")));
        assertThat(new HttpResponse().withCookie(new Cookie("name", null)).getCookieList().get(0), is(new Cookie("name", null)));
    }

    @Test
    public void setsDelay() {
        assertThat(new HttpResponse().withDelay(new Delay(TimeUnit.MILLISECONDS, 10)).getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 10)));
        assertThat(new HttpResponse().withDelay(TimeUnit.MILLISECONDS, 10).getDelay(), is(new Delay(TimeUnit.MILLISECONDS, 10)));
    }

    @Test
    public void setsConnectionOptions() {
        assertThat(new HttpResponse()
                .withConnectionOptions(
                    new ConnectionOptions()
                        .withContentLengthHeaderOverride(10)
                )
                .getConnectionOptions(), is(new ConnectionOptions()
                .withContentLengthHeaderOverride(10)));
    }

    @Test
    public void shouldReturnFormattedRequestInToString() {
        assertJsonEqualsNonStrict("{" + NEW_LINE +
                "  \"statusCode\" : 666," + NEW_LINE +
                "  \"reasonPhrase\" : \"randomPhrase\"," + NEW_LINE +
                "  \"headers\" : {" + NEW_LINE +
                "    \"some_header\" : [ \"some_header_value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"cookies\" : {" + NEW_LINE +
                "    \"some_cookie\" : \"some_cookie_value\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"body\" : {" + NEW_LINE +
                "    \"type\" : \"STRING\"," + NEW_LINE +
                "    \"string\" : \"some_body\"," + NEW_LINE +
                "    \"contentType\" : \"text/plain; charset=iso-8859-1\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"delay\" : {" + NEW_LINE +
                "    \"timeUnit\" : \"SECONDS\"," + NEW_LINE +
                "    \"value\" : 15" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"connectionOptions\" : {" + NEW_LINE +
                "    \"contentLengthHeaderOverride\" : 10," + NEW_LINE +
                "    \"keepAliveOverride\" : true" + NEW_LINE +
                "  }" + NEW_LINE +
                "}",
            response()
                .withBody("some_body", StandardCharsets.ISO_8859_1)
                .withStatusCode(666)
                .withReasonPhrase("randomPhrase")
                .withHeaders(new Header("some_header", "some_header_value"))
                .withCookies(new Cookie("some_cookie", "some_cookie_value"))
                .withConnectionOptions(
                    connectionOptions()
                        .withContentLengthHeaderOverride(10)
                        .withKeepAliveOverride(true)
                )
                .withDelay(SECONDS, 15)
                .toString()
        );
    }

    @Test
    public void shouldClone() {
        // given
        HttpResponse responseOne = response()
            .withBody("some_body", UTF_8)
            .withStatusCode(666)
            .withReasonPhrase("someReasonPhrase")
            .withHeader("some_header", "some_header_value")
            .withCookie("some_cookie", "some_cookie_value")
            .withConnectionOptions(
                connectionOptions()
                    .withContentLengthHeaderOverride(10)
                    .withKeepAliveOverride(true)
            )
            .withDelay(SECONDS, 15);

        // when
        HttpResponse responseTwo = responseOne.clone();

        // then
        assertThat(responseOne, not(sameInstance(responseTwo)));
        assertThat(responseOne, is(responseTwo));
    }

    @Test
    public void shouldUpdate() {
        // given
        HttpResponse responseOne = response()
            .withStatusCode(123)
            .withReasonPhrase("someReasonPhrase")
            .withBody("some_body")
            .withHeader("some_header", "some_header_value")
            .withCookie("some_cookie", "some_cookie_value")
            .withConnectionOptions(
                connectionOptions()
                    .withContentLengthHeaderOverride(10)
                    .withCloseSocket(true)
                    .withKeepAliveOverride(true)
            );
        HttpResponse responseTwo = response()
            .withStatusCode(321)
            .withReasonPhrase("someReasonPhrase_two")
            .withBody("some_body_two")
            .withHeader("some_header_two", "some_header_value_two")
            .withCookie("some_cookie_two", "some_cookie_value_two")
            .withConnectionOptions(
                connectionOptions()
                    .withContentLengthHeaderOverride(100)
                    .withCloseSocket(false)
                    .withKeepAliveOverride(false)
            );

        // when
        responseOne.update(responseTwo, null);

        // then
        assertThat(responseOne, is(
            response()
                .withStatusCode(321)
                .withReasonPhrase("someReasonPhrase_two")
                .withBody("some_body_two")
                .withHeader("some_header", "some_header_value")
                .withHeader("some_header_two", "some_header_value_two")
                .withCookie("some_cookie", "some_cookie_value")
                .withCookie("some_cookie_two", "some_cookie_value_two")
                .withConnectionOptions(
                    connectionOptions()
                        .withContentLengthHeaderOverride(100)
                        .withCloseSocket(false)
                        .withKeepAliveOverride(false)
                )
        ));
    }

    @Test
    public void shouldUpdateEmptyResponse() {
        // given
        HttpResponse responseOne = response();
        HttpResponse responseTwo = response()
            .withStatusCode(321)
            .withReasonPhrase("someReasonPhrase_two")
            .withBody("some_body_two")
            .withHeader("some_header_two", "some_header_value_two")
            .withCookie("some_cookie_two", "some_cookie_value_two")
            .withConnectionOptions(
                connectionOptions()
                    .withContentLengthHeaderOverride(100)
                    .withCloseSocket(false)
                    .withKeepAliveOverride(false)
            );

        // when
        responseOne.update(responseTwo, null);

        // then
        assertThat(responseOne, is(
            response()
                .withStatusCode(321)
                .withReasonPhrase("someReasonPhrase_two")
                .withBody("some_body_two")
                .withHeader("some_header_two", "some_header_value_two")
                .withCookie("some_cookie_two", "some_cookie_value_two")
                .withConnectionOptions(
                    connectionOptions()
                        .withContentLengthHeaderOverride(100)
                        .withCloseSocket(false)
                        .withKeepAliveOverride(false)
                )
        ));
    }

    @Test
    public void returnsTrailers() {
        assertThat(new HttpResponse().withTrailers(new Header("name", "value")).getTrailerList().get(0), is(new Header("name", "value")));
        assertThat(new HttpResponse().withTrailers(Collections.singletonList(new Header("name", "value"))).getTrailerList().get(0), is(new Header("name", "value")));
        assertThat(new HttpResponse().withTrailer(new Header("name", "value")).getTrailerList().get(0), is(new Header("name", "value")));
        assertThat(new HttpResponse().withTrailer("name", "value1", "value2").getTrailerList().get(0), is(new Header("name", "value1", "value2")));
    }

    @Test
    public void returnsNoTrailersByDefault() {
        assertThat(new HttpResponse().getTrailers(), is(nullValue()));
        assertThat(new HttpResponse().getTrailerList(), hasSize(0));
        assertThat(new HttpResponse().getTrailerMultimap(), is(nullValue()));
    }

    @Test
    public void emptyTrailersAreTreatedAsNoTrailers() {
        assertThat(new HttpResponse().withTrailers(new Headers()).getTrailers(), is(nullValue()));
        assertThat(new HttpResponse().withTrailers((Headers) null).getTrailers(), is(nullValue()));
    }

    @Test
    public void trailersAreIncludedInEqualsAndHashCode() {
        HttpResponse withTrailer = response().withStatusCode(200).withTrailer("x-checksum", "abc123");
        HttpResponse withoutTrailer = response().withStatusCode(200);
        HttpResponse withSameTrailer = response().withStatusCode(200).withTrailer("x-checksum", "abc123");

        assertThat(withTrailer, not(is(withoutTrailer)));
        assertThat(withTrailer, is(withSameTrailer));
        assertThat(withTrailer.hashCode(), is(withSameTrailer.hashCode()));
    }

    @Test
    public void trailersAreCloned() {
        // given
        HttpResponse responseOne = response()
            .withStatusCode(200)
            .withHeader("some_header", "some_header_value")
            .withTrailer("x-checksum", "abc123");

        // when
        HttpResponse responseTwo = responseOne.clone();

        // then
        assertThat(responseOne, not(sameInstance(responseTwo)));
        assertThat(responseOne, is(responseTwo));
        assertThat(responseTwo.getTrailerList().get(0), is(new Header("x-checksum", "abc123")));
        // mutating the clone's trailers must not affect the original
        responseTwo.withTrailer("x-extra", "extra");
        assertThat(responseOne.getTrailerList(), hasSize(1));
    }

    @Test
    public void shouldDropInheritedContentLengthWhenUpdateReplacesTheBody() {
        // given - an upstream response carrying its own Content-Length, as a forwarded response does
        HttpResponse upstream = response()
            .withStatusCode(200)
            .withHeader("Content-Length", "13")
            .withBody("from upstream");

        // when - a responseOverride replaces the body with one of a DIFFERENT length
        upstream.update(response().withBody("a considerably longer replacement body"), null);

        // then - the stale Content-Length is gone so it is recomputed from the bytes actually written;
        // keeping it truncates the response to the old length on the wire
        assertThat(upstream.getFirstHeader("Content-Length"), is(""));
        assertThat(upstream.getBodyAsString(), is("a considerably longer replacement body"));
    }

    @Test
    public void shouldKeepContentLengthDeclaredByTheOverrideItself() {
        // given
        HttpResponse upstream = response()
            .withStatusCode(200)
            .withHeader("Content-Length", "13")
            .withBody("from upstream");

        // when - the override declares its OWN Content-Length, a deliberate choice (e.g. to simulate a
        // mismatched or truncated response), which must be preserved
        upstream.update(
            response().withHeader("Content-Length", "3").withBody("a considerably longer replacement body"),
            null
        );

        // then
        assertThat(upstream.getFirstHeader("Content-Length"), is("3"));
    }

    @Test
    public void shouldLeaveContentLengthAloneWhenUpdateDoesNotReplaceTheBody() {
        // given
        HttpResponse upstream = response()
            .withStatusCode(200)
            .withHeader("Content-Length", "13")
            .withBody("from upstream");

        // when - a header-only override; the body is untouched so its Content-Length is still correct
        upstream.update(response().withHeader("x-added", "yes"), null);

        // then
        assertThat(upstream.getFirstHeader("Content-Length"), is("13"));
        assertThat(upstream.getFirstHeader("x-added"), is("yes"));
    }
}

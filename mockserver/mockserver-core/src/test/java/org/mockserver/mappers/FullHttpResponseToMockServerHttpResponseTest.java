package org.mockserver.mappers;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.*;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpResponse;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.*;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * @author jamesdbloom
 */
public class FullHttpResponseToMockServerHttpResponseTest {

    private final FullHttpResponseToMockServerHttpResponse mapper =
        new FullHttpResponseToMockServerHttpResponse(new MockServerLogger());

    // --- status code ---

    @Test
    public void shouldMapStatusCode200() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(200));
            assertThat(result.getReasonPhrase(), equalTo("OK"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapStatusCode404() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.NOT_FOUND);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(404));
            assertThat(result.getReasonPhrase(), equalTo("Not Found"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapCustomReasonPhrase() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1,
            new HttpResponseStatus(299, "Custom Reason")
        );

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(299));
            assertThat(result.getReasonPhrase(), equalTo("Custom Reason"));
        } finally {
            nettyResponse.release();
        }
    }

    // --- headers ---

    @Test
    public void shouldMapHeaders() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("headerName1", "headerValue1");
        nettyResponse.headers().add("headerName2", "headerValue2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getFirstHeader("headerName1"), equalTo("headerValue1"));
            assertThat(result.getFirstHeader("headerName2"), equalTo("headerValue2"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldFoldTrailersIntoHeaders() {
        // gRPC servers deliver the terminal grpc-status / grpc-message in HTTP trailers; these must
        // survive into the response model (otherwise a non-OK gRPC status is silently lost).
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("content-type", "application/grpc");
        nettyResponse.trailingHeaders().add("grpc-status", "5");
        nettyResponse.trailingHeaders().add("grpc-message", "not found");

        try {
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            assertThat(result.getFirstHeader("content-type"), equalTo("application/grpc"));
            assertThat(result.getFirstHeader("grpc-status"), equalTo("5"));
            assertThat(result.getFirstHeader("grpc-message"), equalTo("not found"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldNotDuplicateTrailerWhenAlsoPresentAsHeader() {
        // a trailer whose name is already a header must not be folded in (no duplicate values)
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("x-dup", "header-value");
        nettyResponse.trailingHeaders().add("x-dup", "trailer-value");

        try {
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            List<Header> headers = result.getHeaderList();
            for (Header h : headers) {
                if (h.getName().getValue().equalsIgnoreCase("x-dup")) {
                    assertThat(h.getValues().size(), equalTo(1));
                    assertThat(h.getValues().get(0).getValue(), equalTo("header-value"));
                }
            }
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapMultipleHeaderValues() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("headerName1", "value1");
        nettyResponse.headers().add("headerName1", "value2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            List<Header> headers = result.getHeaderList();
            boolean found = false;
            for (Header h : headers) {
                if (h.getName().getValue().equals("headerName1")) {
                    assertThat(h.getValues().size(), equalTo(2));
                    found = true;
                }
            }
            assertThat("headerName1 should be present", found, is(true));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldStripHttp2ExtensionHeadersButKeepOrdinaryHeaders() {
        // given - a response decoded from HTTP/2: InboundHttp2ToHttpAdapter injects the synthetic
        // x-http2-* extension-header family (most importantly x-http2-stream-id carrying the UPSTREAM
        // stream id). These must never enter the model or they leak the upstream stream id back onto
        // an HTTP/2 client leg on write-back, causing a PROTOCOL_ERROR / GOAWAY hang (and log noise).
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("x-http2-stream-id", "3");
        nettyResponse.headers().add("x-http2-scheme", "https");
        nettyResponse.headers().add("x-http2-path", "/upstream");
        nettyResponse.headers().add("content-type", "application/json");
        nettyResponse.headers().add("x-custom-header", "customValue");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then - none of the x-http2-* extension headers survive into the model
            assertThat(result.getFirstHeader("x-http2-stream-id"), is(emptyOrNullString()));
            assertThat(result.getFirstHeader("x-http2-scheme"), is(emptyOrNullString()));
            assertThat(result.getFirstHeader("x-http2-path"), is(emptyOrNullString()));
            for (Header header : result.getHeaderList()) {
                assertThat(header.getName().getValue().toLowerCase(), not(startsWith("x-http2-")));
            }
            // and ordinary headers are preserved unchanged
            assertThat(result.getFirstHeader("content-type"), equalTo("application/json"));
            assertThat(result.getFirstHeader("x-custom-header"), equalTo("customValue"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldStripHttp2StreamIdHeaderCaseInsensitively() {
        // given - header names arrive in mixed case; the strip must be case-insensitive
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("X-Http2-Stream-Id", "7");
        nettyResponse.headers().add("headerName1", "headerValue1");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getFirstHeader("X-Http2-Stream-Id"), is(emptyOrNullString()));
            assertThat(result.getFirstHeader("x-http2-stream-id"), is(emptyOrNullString()));
            assertThat(result.getFirstHeader("headerName1"), equalTo("headerValue1"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldHandleNoHeaders() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getHeaderList(), empty());
        } finally {
            nettyResponse.release();
        }
    }

    // --- cookies ---

    @Test
    public void shouldMapSetCookieHeaders() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("Set-Cookie", "cookieName1=cookieValue1");
        nettyResponse.headers().add("Set-Cookie", "cookieName2=cookieValue2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            List<Cookie> cookies = result.getCookieList();
            assertThat(cookies, hasSize(2));
            assertThat(cookies, hasItem(new Cookie("cookieName1", "cookieValue1")));
            assertThat(cookies, hasItem(new Cookie("cookieName2", "cookieValue2")));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapCookieHeaderInResponse() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("Cookie", "cookieName1=cookieValue1; cookieName2=cookieValue2");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            List<Cookie> cookies = result.getCookieList();
            assertThat(cookies, hasSize(2));
            assertThat(cookies, hasItem(new Cookie("cookieName1", "cookieValue1")));
            assertThat(cookies, hasItem(new Cookie("cookieName2", "cookieValue2")));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldHandleNoCookies() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getCookieList(), empty());
        } finally {
            nettyResponse.release();
        }
    }

    // --- body ---

    @Test
    public void shouldMapTextBody() {
        // given
        byte[] bodyBytes = "response body content".getBytes(StandardCharsets.UTF_8);
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.copiedBuffer(bodyBytes)
        );
        nettyResponse.headers().set(CONTENT_TYPE, "text/plain; charset=utf-8");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsString(), equalTo("response body content"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapBinaryBody() {
        // given
        byte[] binaryData = new byte[]{0x10, 0x20, 0x30, 0x40};
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.copiedBuffer(binaryData)
        );
        nettyResponse.headers().set(CONTENT_TYPE, "application/octet-stream");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(binaryData));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapEmptyBody() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(new byte[0]));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapJsonBody() {
        // given
        String json = "{\"key\":\"value\"}";
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
            Unpooled.copiedBuffer(json, StandardCharsets.UTF_8)
        );
        nettyResponse.headers().set(CONTENT_TYPE, "application/json; charset=utf-8");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getBodyAsString(), equalTo(json));
        } finally {
            nettyResponse.release();
        }
    }

    // --- null response ---

    @Test
    public void shouldHandleNullResponse() {
        // when
        HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(null);

        // then
        assertThat(result, is(notNullValue()));
    }

    // --- status codes ---

    @Test
    public void shouldMapStatusCode500() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.INTERNAL_SERVER_ERROR
        );

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(500));
            assertThat(result.getReasonPhrase(), equalTo("Internal Server Error"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapStatusCode302() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
            HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND
        );
        nettyResponse.headers().add("Location", "http://example.com/redirect");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            assertThat(result.getStatusCode(), equalTo(302));
            assertThat(result.getFirstHeader("Location"), equalTo("http://example.com/redirect"));
        } finally {
            nettyResponse.release();
        }
    }

    // --- literal names and values (a real upstream response, not matcher input) ---

    @Test
    public void shouldMapAResponseHeaderNameThatBeginsWithANegationMarkerAsLiteral() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("!foo", "bar");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then - the name is the literal "!foo", NOT a negation of "foo"
            Header header = result.getHeaderList().stream()
                .filter(h -> h.getName().getValue().equals("!foo"))
                .findFirst().orElseThrow(() -> new AssertionError("no header literally named '!foo': " + result.getHeaderList()));
            assertThat(header.getName().isNot(), is(false));
            assertThat(header.getValues().get(0).getValue(), equalTo("bar"));
            assertThat(result.getFirstHeader("foo"), is(emptyString()));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapAResponseHeaderValueThatBeginsWithANegationMarkerAsLiteral() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("X-Tag", "!foo");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then - the value is the literal "!foo", not NOT(foo)
            Header header = result.getHeaderList().stream()
                .filter(h -> h.getName().getValue().equalsIgnoreCase("X-Tag"))
                .findFirst().orElseThrow(AssertionError::new);
            assertThat(header.getValues().get(0).getValue(), equalTo("!foo"));
            assertThat(header.getValues().get(0).isNot(), is(false));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapATrailerNameThatBeginsWithANegationMarkerAsLiteral() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.trailingHeaders().add("!grpc-status", "13");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            Header header = result.getHeaderList().stream()
                .filter(h -> h.getName().getValue().equals("!grpc-status"))
                .findFirst().orElseThrow(() -> new AssertionError("no trailer literally named '!grpc-status': " + result.getHeaderList()));
            assertThat(header.getName().isNot(), is(false));
            assertThat(header.getValues().get(0).getValue(), equalTo("13"));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapASetCookieNameAndValueThatBeginWithANegationMarkerAsLiteral() {
        // given
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add(SET_COOKIE, "!session=!abc");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then - both halves are literal, not negations
            Cookie cookie = result.getCookieList().stream()
                .filter(c -> c.getName().getValue().equals("!session"))
                .findFirst().orElseThrow(() -> new AssertionError("no cookie literally named '!session': " + result.getCookieList()));
            assertThat(cookie.getName().isNot(), is(false));
            assertThat(cookie.getValue().getValue(), equalTo("!abc"));
            assertThat(cookie.getValue().isNot(), is(false));
        } finally {
            nettyResponse.release();
        }
    }

    @Test
    public void shouldMapAResponseHeaderValueThatBeginsWithAnOptionalMarkerAsLiteral() {
        // given - "?" is a legal header VALUE character (unlike a name) and string() strips it
        FullHttpResponse nettyResponse = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK);
        nettyResponse.headers().add("X-Tag", "?foo");

        try {
            // when
            HttpResponse result = mapper.mapFullHttpResponseToMockServerResponse(nettyResponse);

            // then
            Header header = result.getHeaderList().stream()
                .filter(h -> h.getName().getValue().equalsIgnoreCase("X-Tag"))
                .findFirst().orElseThrow(AssertionError::new);
            assertThat(header.getValues().get(0).getValue(), equalTo("?foo"));
        } finally {
            nettyResponse.release();
        }
    }
}

package org.mockserver.mappers;

import io.netty.buffer.Unpooled;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.junit.Test;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.Cookie;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.Protocol;

import org.apache.commons.lang3.Strings;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;

import static io.netty.handler.codec.http.HttpHeaderNames.CONNECTION;
import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.COOKIE;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.NottableString.string;

/**
 * @author jamesdbloom
 */
public class FullHttpRequestToMockServerHttpRequestTest {

    private final MockServerLogger mockServerLogger = new MockServerLogger();

    private FullHttpRequestToMockServerHttpRequest createMapper(boolean isSecure, Integer port) {
        return new FullHttpRequestToMockServerHttpRequest(configuration(), mockServerLogger, isSecure, null, port);
    }

    // --- method mapping ---

    @Test
    public void shouldRecordAnAbsoluteFormRequestTarget() {
        FullHttpRequest absoluteForm = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "http://upstream:8080/mockserver/status?a=b");
        FullHttpRequest originForm = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/mockserver/status");
        try {
            HttpRequest fromAbsoluteForm = createMapper(false, null)
                .mapFullHttpRequestToMockServerRequest(absoluteForm, null, null, null, Protocol.HTTP_1_1);
            HttpRequest fromOriginForm = createMapper(false, null)
                .mapFullHttpRequestToMockServerRequest(originForm, null, null, null, Protocol.HTTP_1_1);

            assertThat(fromAbsoluteForm.getPath().getValue(), equalTo("/mockserver/status"));
            assertThat(fromAbsoluteForm.isAbsoluteFormRequestTarget(), is(true));
            assertThat(fromAbsoluteForm.clone().isAbsoluteFormRequestTarget(), is(true));
            assertThat(fromOriginForm.isAbsoluteFormRequestTarget(), is(false));
        } finally {
            absoluteForm.release();
            originForm.release();
        }
    }

    @Test
    public void shouldMapGetMethod() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getMethod(""), equalTo("GET"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapPostMethod() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/some/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getMethod(""), equalTo("POST"));
        } finally {
            nettyRequest.release();
        }
    }

    // --- URI / path mapping ---

    @Test
    public void shouldMapPath() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getPath(), equalTo(string("/some/path")));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapPathWithQueryString() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET,
            "/some/path?paramOne=valueOne&paramTwo=valueTwo");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getPath(), equalTo(string("/some/path")));
            assertThat(result.getQueryStringParameterList(), hasSize(2));
        } finally {
            nettyRequest.release();
        }
    }

    // --- headers ---

    @Test
    public void shouldMapHeaders() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("headerName1", "headerValue1");
        nettyRequest.headers().add("headerName2", "headerValue2");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getFirstHeader("headerName1"), equalTo("headerValue1"));
            assertThat(result.getFirstHeader("headerName2"), equalTo("headerValue2"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapMultipleHeaderValues() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("headerName1", "headerValue1_1");
        nettyRequest.headers().add("headerName1", "headerValue1_2");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

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
            nettyRequest.release();
        }
    }

    // --- cookies ---

    @Test
    public void shouldMapCookies() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add(COOKIE, "cookieName1=cookieValue1; cookieName2=cookieValue2");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            List<Cookie> cookies = result.getCookieList();
            assertThat(cookies, hasSize(2));
            assertThat(cookies, hasItem(new Cookie("cookieName1", "cookieValue1")));
            assertThat(cookies, hasItem(new Cookie("cookieName2", "cookieValue2")));
        } finally {
            nettyRequest.release();
        }
    }

    // --- literal marker names/values (an actual request is never a matcher) ---
    //
    // A parsed incoming request must record header/cookie names and values verbatim. The plain
    // NottableString form strips a leading '!' (negation) or '?' (optional) on read, so before the
    // fix a header genuinely named "!foo" was recorded as the negation NOT(foo) — matching every
    // request that does NOT carry it, the exact inverse. The discriminating fact these tests assert
    // is that "!foo" and "foo" now map to DIFFERENT names (isNot() == false, value preserved),
    // which is precisely what a fixture using only non-marker names, or only asserting the value,
    // could never see.

    @Test
    public void shouldMapAHeaderNameThatBeginsWithANegationMarkerAsLiteral() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("!foo", "bar");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then — the name is the literal "!foo", NOT a negation of "foo"
            Header header = result.getHeaderList().stream()
                .filter(h -> h.getName().getValue().equals("!foo"))
                .findFirst().orElseThrow(() -> new AssertionError("no header literally named '!foo': " + result.getHeaderList()));
            assertThat(header.getName().isNot(), is(false));
            assertThat(header.getValues().get(0).getValue(), equalTo("bar"));
            // and there is no header named "foo" (the stripped form)
            assertThat(result.getFirstHeader("foo"), is(emptyString()));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapAHeaderValueThatBeginsWithANegationMarkerAsLiteral() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("X-Tag", "!foo");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then — the value is the literal "!foo", not NOT(foo)
            Header header = result.getHeaderList().stream()
                .filter(h -> h.getName().getValue().equalsIgnoreCase("X-Tag"))
                .findFirst().orElseThrow(AssertionError::new);
            assertThat(header.getValues().get(0).getValue(), equalTo("!foo"));
            assertThat(header.getValues().get(0).isNot(), is(false));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapACookieNameThatBeginsWithANegationMarkerAsLiteral() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add(COOKIE, "!c=1; plain=2");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then — literal "!c", built with the non-parsing constructor for comparison
            List<Cookie> cookies = result.getCookieList();
            assertThat(cookies, hasItem(new Cookie(string("!c", false), string("1", false))));
            assertThat(cookies, hasItem(new Cookie("plain", "2")));
            Cookie literal = cookies.stream()
                .filter(c -> c.getName().getValue().equals("!c"))
                .findFirst().orElseThrow(AssertionError::new);
            assertThat(literal.getName().isNot(), is(false));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapACookieValueThatBeginsWithANegationMarkerAsLiteral() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add(COOKIE, "sess=!bar");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            Cookie cookie = result.getCookieList().stream()
                .filter(c -> c.getName().getValue().equals("sess"))
                .findFirst().orElseThrow(AssertionError::new);
            assertThat(cookie.getValue().getValue(), equalTo("!bar"));
            assertThat(cookie.getValue().isNot(), is(false));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldHandleNoCookies() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getCookieList(), empty());
        } finally {
            nettyRequest.release();
        }
    }

    // --- body ---

    @Test
    public void shouldMapTextBody() {
        // given
        byte[] bodyBytes = "some body content".getBytes(StandardCharsets.UTF_8);
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/path",
            Unpooled.copiedBuffer(bodyBytes)
        );
        nettyRequest.headers().set(CONTENT_TYPE, "text/plain; charset=utf-8");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getBodyAsString(), equalTo("some body content"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapBinaryBody() {
        // given
        byte[] binaryData = new byte[]{0x00, 0x01, 0x02, 0x03, 0x7F};
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(
            HttpVersion.HTTP_1_1, HttpMethod.POST, "/path",
            Unpooled.copiedBuffer(binaryData)
        );
        nettyRequest.headers().set(CONTENT_TYPE, "application/octet-stream");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(binaryData));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapEmptyBody() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getBodyAsRawBytes(), equalTo(new byte[0]));
        } finally {
            nettyRequest.release();
        }
    }

    // --- secure / keep-alive / protocol ---

    @Test
    public void shouldMapSecureFlag() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(true, 443)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.isSecure(), is(true));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapNonSecureFlag() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.isSecure(), is(false));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapKeepAlive() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().set(CONNECTION, "keep-alive");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.isKeepAlive(), is(true));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapProtocol() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_2);

            // then
            assertThat(result.getProtocol(), equalTo(Protocol.HTTP_2));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldDefaultToHTTP11WhenProtocolIsNull() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, null);

            // then
            assertThat(result.getProtocol(), equalTo(Protocol.HTTP_1_1));
        } finally {
            nettyRequest.release();
        }
    }

    // --- socket addresses ---

    @Test
    public void shouldMapRemoteAddress() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        InetSocketAddress remoteAddress = new InetSocketAddress("192.168.1.100", 54321);

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, remoteAddress, Protocol.HTTP_1_1);

            // then
            assertThat(result.getRemoteAddress(), containsString("192.168.1.100"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldMapLocalAddress() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        InetSocketAddress localAddress = new InetSocketAddress("127.0.0.1", 8080);

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, localAddress, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getLocalAddress(), containsString("127.0.0.1"));
        } finally {
            nettyRequest.release();
        }
    }

    // --- null request ---

    @Test
    public void shouldHandleNullRequest() {
        // when
        HttpRequest result = createMapper(false, 80)
            .mapFullHttpRequestToMockServerRequest(null, null, null, null, Protocol.HTTP_1_1);

        // then
        assertThat(result, is(notNullValue()));
    }

    // --- preserved headers ---

    @Test
    public void shouldAddPreservedHeaders() {
        // given
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("existingHeader", "existingValue");
        List<Header> preservedHeaders = Collections.singletonList(new Header("preservedHeader", "preservedValue"));

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, preservedHeaders, null, null, Protocol.HTTP_1_1);

            // then
            assertThat(result.getFirstHeader("preservedHeader"), equalTo("preservedValue"));
            assertThat(result.getFirstHeader("existingHeader"), equalTo("existingValue"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldNotDuplicatePreservedHeaderAlreadyInLiveHeaders() {
        // given - the preserved header is still present in the live request headers (e.g. when
        // request decompression is disabled, Content-Encoding is never stripped by Netty)
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        nettyRequest.headers().add("Content-Encoding", "gzip");
        List<Header> preservedHeaders = Collections.singletonList(new Header("content-encoding", "gzip"));

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, preservedHeaders, null, null, Protocol.HTTP_1_1);

            // then - the header appears exactly once, not duplicated
            assertThat(result.getHeader("Content-Encoding"), equalTo(Collections.singletonList("gzip")));
        } finally {
            nettyRequest.release();
        }
    }

    // --- headers-only mapping ---

    @Test
    public void shouldMapHeadersOnlyRequest() {
        // given
        io.netty.handler.codec.http.HttpRequest nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path?key=value");
        nettyRequest.headers().add("headerName", "headerValue");
        nettyRequest.headers().add(COOKIE, "cookieName=cookieValue");

        // when
        HttpRequest result = createMapper(false, 80)
            .mapHeadersOnlyHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

        // then
        assertThat(result.getMethod(""), equalTo("GET"));
        assertThat(result.getPath(), equalTo(string("/path")));
        assertThat(result.getFirstHeader("headerName"), equalTo("headerValue"));
        assertThat(result.getCookieList(), hasSize(1));
        assertThat(result.getQueryStringParameterList(), hasSize(1));
    }

    @Test
    public void shouldHandleNullHeadersOnlyRequest() {
        // when
        HttpRequest result = createMapper(false, 80)
            .mapHeadersOnlyHttpRequestToMockServerRequest(null, null, null, null, Protocol.HTTP_1_1);

        // then
        assertThat(result, is(notNullValue()));
    }

    // --- HTTP/2 stream id capture (SEC-05: only over genuine HTTP/2, never from a spoofed h1 header) ---

    @Test
    public void shouldCaptureStreamIdWhenRequestArrivedOverHttp2() {
        // given - InboundHttp2ToHttpAdapter sets the x-http2-stream-id extension header on h2/h2c requests
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");
        nettyRequest.headers().add(io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), "3");

        try {
            // when - the mapper is told the request genuinely arrived over HTTP/2 (trusted server-side signal)
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_2);

            // then - the stream id is captured so a stream-scoped action (e.g. HttpError reset) can target it
            assertThat(result.getStreamId(), is(3));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldNotCaptureSpoofedStreamIdOnHttp1Request() {
        // given - a plain HTTP/1.1 client forges an x-http2-stream-id header
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");
        nettyRequest.headers().add(io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.STREAM_ID.text(), "99");

        try {
            // when - the request arrived over HTTP/1.1 (no HTTP/2 negotiation)
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then - the forged header is ignored: no stream id is captured on an HTTP/1.1 connection
            assertThat(result.getStreamId(), is(nullValue()));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldLeaveNettysExtensionHeadersOutOfAnHttp2Request() {
        // given - as Netty's conversion builds an HTTP/2 request: scheme and stream id extension headers
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");
        nettyRequest.headers().add("host", "localhost:1080");
        nettyRequest.headers().add("x-http2-scheme", "https");
        nettyRequest.headers().add("x-other", "kept");
        nettyRequest.headers().add("x-http2-not-netty", "kept");
        for (io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames name : io.netty.handler.codec.http2.HttpConversionUtil.ExtensionHeaderNames.values()) {
            nettyRequest.headers().add(name.text().toString().toUpperCase(java.util.Locale.ROOT), "1");
        }
        nettyRequest.headers().set("x-http2-stream-id", "3");

        try {
            // when
            HttpRequest result = createMapper(true, 1080)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_2);

            // then - no extension header in the model, the stream id is kept apart from the headers
            List<String> names = new java.util.ArrayList<>();
            for (Header header : result.getHeaderList()) {
                names.add(header.getName().getValue());
            }
            assertThat(names, contains("host", "x-other", "x-http2-not-netty"));
            assertThat(result.getStreamId(), is(3));
            assertThat(result.isSecure(), is(true));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldKeepAnHttp1ClientsOwnHttp2NamedHeaders() {
        // given - an HTTP/1.1 client sends headers that happen to share Netty's extension header names
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/some/path");
        nettyRequest.headers().add("x-http2-scheme", "https");
        nettyRequest.headers().add("x-http2-stream-id", "99");

        try {
            // when
            HttpRequest result = createMapper(false, 80)
                .mapFullHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

            // then - they are the client's headers, recorded as sent
            assertThat(result.getFirstHeader("x-http2-scheme"), is("https"));
            assertThat(result.getFirstHeader("x-http2-stream-id"), is("99"));
        } finally {
            nettyRequest.release();
        }
    }

    @Test
    public void shouldLeaveNettysExtensionHeadersOutOfAnHttp2RequestsHeadersOnly() {
        // given - the early-response path maps the head before the body is aggregated
        DefaultHttpRequest nettyRequest = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "/some/path");
        nettyRequest.headers().add("x-http2-scheme", "http");
        nettyRequest.headers().add("x-http2-stream-id", "5");

        // when
        HttpRequest result = createMapper(false, 80)
            .mapHeadersOnlyHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_2);

        // then
        assertThat(result.getHeaderList(), is(empty()));
        assertThat(result.getStreamId(), is(5));
    }

    // --- address string memoisation ---

    @Test
    public void shouldMemoiseAddressStringsByInstanceAndRecomputeForNewInstance() throws Exception {
        // given - one mapper reused across requests on the same connection
        FullHttpRequestToMockServerHttpRequest mapper = createMapper(false, 1080);
        // remote has no hostname (leading slash stripped); local has a hostname (inner slash survives)
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(new byte[]{1, 2, 3, 4}), 1080);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress("myhost", new byte[]{5, 6, 7, 8}), 8080);
        String expectedRemote = Strings.CS.removeStart(remote.toString(), "/");
        String expectedLocal = Strings.CS.removeStart(local.toString(), "/");

        // when - mapped twice with the SAME address instances
        HttpRequest first = mapWithAddresses(mapper, local, remote);
        HttpRequest second = mapWithAddresses(mapper, local, remote);

        // then - exact current values preserved, including the surviving inner slash
        assertThat(first.getRemoteAddress(), is(equalTo(expectedRemote)));
        assertThat(first.getLocalAddress(), is(equalTo(expectedLocal)));
        assertThat(first.getLocalAddress(), is(equalTo("myhost/5.6.7.8:8080")));
        // and the memoised String instance is reused across requests on the same connection
        assertThat(second.getRemoteAddress(), is(sameInstance(first.getRemoteAddress())));
        assertThat(second.getLocalAddress(), is(sameInstance(first.getLocalAddress())));

        // when - a different address instance of equal value arrives (remote), local instance unchanged
        InetSocketAddress remoteDifferentInstance = new InetSocketAddress(InetAddress.getByAddress(new byte[]{1, 2, 3, 4}), 1080);
        HttpRequest third = mapWithAddresses(mapper, local, remoteDifferentInstance);

        // then - remote is recomputed (not the cached instance) but exactly equal; local stays memoised
        assertThat(third.getRemoteAddress(), is(not(sameInstance(first.getRemoteAddress()))));
        assertThat(third.getRemoteAddress(), is(equalTo(expectedRemote)));
        assertThat(third.getLocalAddress(), is(sameInstance(first.getLocalAddress())));
    }

    @Test
    public void shouldRecordOnlyTheRemoteAddressTextUntilAClientIpMatcherIsInUse() throws Exception {
        FullHttpRequestToMockServerHttpRequest mapper = createMapper(false, 1080);
        mapper.clientIpInUseForTesting(() -> false);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(new byte[]{5, 6, 7, 8}), 8080);
        InetSocketAddress remote = new InetSocketAddress(InetAddress.getByAddress(new byte[]{10, 1, 2, (byte) 200}), 40000);

        HttpRequest first = mapWithAddresses(mapper, local, remote);
        HttpRequest second = mapWithAddresses(mapper, local, remote);

        assertThat(first.getRemoteAddress(), is("10.1.2.200:40000"));
        assertThat(second.getRemoteAddress(), is(sameInstance(first.getRemoteAddress())));
        // no holder and no address bytes: a connection that never meets a clientIp pays nothing for it
        assertThat(first.getRemoteAddressBytes(), is(nullValue()));

        // once a clientIp matcher exists, the same connection switches to a holder built once for it
        mapper.clientIpInUseForTesting(() -> true);
        HttpRequest third = mapWithAddresses(mapper, local, remote);
        HttpRequest fourth = mapWithAddresses(mapper, local, remote);
        assertThat(third.getRemoteAddress(), is(sameInstance(first.getRemoteAddress())));
        assertThat(third.getRemoteAddressBytes(), is(new byte[]{10, 1, 2, (byte) 200}));
        assertThat(fourth.getRemoteAddressBytes(), is(sameInstance(third.getRemoteAddressBytes())));
    }

    @Test
    public void shouldDeriveRemoteAddressBytesForEachAddressFamily() throws Exception {
        FullHttpRequestToMockServerHttpRequest mapper = createMapper(false, 1080);
        mapper.clientIpInUseForTesting(() -> true);
        InetSocketAddress local = new InetSocketAddress(InetAddress.getByAddress(new byte[]{5, 6, 7, 8}), 8080);

        InetSocketAddress ipv4Mapped = new InetSocketAddress(InetAddress.getByAddress(new byte[]{0, 0, 0, 0, 0, 0, 0, 0, 0, 0, (byte) 0xFF, (byte) 0xFF, 10, 0, 0, 1}), 40000);
        assertThat(mapWithAddresses(mapper, local, ipv4Mapped).getRemoteAddressBytes(), is(new byte[]{10, 0, 0, 1}));
        InetSocketAddress ipv6 = new InetSocketAddress(InetAddress.getByName("2001:db8::1"), 40000);
        assertThat(mapWithAddresses(mapper, local, ipv6).getRemoteAddressBytes().length, is(16));
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("some-host", 40000);
        assertThat(mapWithAddresses(mapper, local, unresolved).getRemoteAddressBytes(), is(nullValue()));
    }

    private HttpRequest mapWithAddresses(FullHttpRequestToMockServerHttpRequest mapper, InetSocketAddress local, InetSocketAddress remote) {
        FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/path");
        try {
            return mapper.mapFullHttpRequestToMockServerRequest(nettyRequest, null, local, remote, Protocol.HTTP_1_1);
        } finally {
            nettyRequest.release();
        }
    }

    // --- request-target form ---

    @Test
    public void shouldRecordEveryRequestTargetThatIsNotOriginFormAsAProxyRequest() {
        for (String uri : new String[]{"HTTP://upstream/x", "https://user@upstream:8443/x", "upstream:443", "upstream/x"}) {
            FullHttpRequest nettyRequest = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, uri);
            try {
                HttpRequest result = createMapper(false, 80)
                    .mapHeadersOnlyHttpRequestToMockServerRequest(nettyRequest, null, null, null, Protocol.HTTP_1_1);

                assertThat(uri, result.isAbsoluteFormRequestTarget(), is(true));
                assertThat(uri, result.deepCopy().isAbsoluteFormRequestTarget(), is(true));
            } finally {
                nettyRequest.release();
            }
        }
        assertThat(FullHttpRequestToMockServerHttpRequest.isOriginOrAsteriskForm("/x?u=http://other"), is(true));
        assertThat(FullHttpRequestToMockServerHttpRequest.isOriginOrAsteriskForm("*"), is(true));
    }
}

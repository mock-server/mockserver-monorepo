package org.mockserver.model;

import org.junit.Test;

import java.net.InetSocketAddress;
import java.util.Collection;
import java.util.Collections;

import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.collection.IsEmptyCollection.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.hamcrest.core.IsSame.sameInstance;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.NottableSchemaString.schemaString;
import static org.mockserver.model.NottableString.string;
import static org.mockserver.model.SocketAddress.socketAddress;

/**
 * @author jamesdbloom
 */
public class HttpRequestTest {

    @Test
    public void shouldAlwaysCreateNewObject() {
        assertThat(HttpRequest.request(), is(request()));
        assertThat(HttpRequest.request(), not(sameInstance(HttpRequest.request())));
    }

    @Test
    public void returnsPath() {
        assertThat(new HttpRequest().withPath("somepath").getPath(), is(string("somepath")));
        assertThat(request("somepath").getPath(), is(string("somepath")));
    }

    @Test
    public void returnsMethod() {
        assertThat(new HttpRequest().withMethod("POST").getMethod(), is(string("POST")));
    }

    @Test
    public void setAndGetSocketAddress() {
        assertThat(new HttpRequest().withSocketAddress(
                new SocketAddress()
                    .withHost("someHost")
                    .withPort(1234)
                    .withScheme(SocketAddress.Scheme.HTTPS)
            ).getSocketAddress(), is(new SocketAddress()
                .withHost("someHost")
                .withPort(1234)
                .withScheme(SocketAddress.Scheme.HTTPS)));
        assertThat(new HttpRequest().withSocketAddress("someHost", 1234, SocketAddress.Scheme.HTTPS).getSocketAddress(), is(new SocketAddress()
                .withHost("someHost")
                .withPort(1234)
                .withScheme(SocketAddress.Scheme.HTTPS)));
        assertThat(new HttpRequest().withSecure(true).withSocketAddress("someHost", 1234).getSocketAddress(), is(new SocketAddress()
                .withHost("someHost")
                .withPort(1234)
                .withScheme(SocketAddress.Scheme.HTTPS)));
        assertThat(new HttpRequest().withSecure(false).withSocketAddress("someHost", 1234).getSocketAddress(), is(new SocketAddress()
                .withHost("someHost")
                .withPort(1234)
                .withScheme(SocketAddress.Scheme.HTTP)));
        assertThat(new HttpRequest().withSocketAddress(true, "someHost", 1234).getSocketAddress(), is(new SocketAddress()
                .withHost("someHost")
                .withPort(1234)
                .withScheme(SocketAddress.Scheme.HTTPS)));
        assertThat(new HttpRequest().withSocketAddress(false, "someHost", 1234).getSocketAddress(), is(new SocketAddress()
                .withHost("someHost")
                .withPort(1234)
                .withScheme(SocketAddress.Scheme.HTTP)));
    }

    @Test
    public void passesSuppliedPortIntegerThroughWithoutReboxing() {
        Integer port = 1080;
        assertThat(new HttpRequest().withSocketAddress(false, "someHost", port).getSocketAddress().getPort(), is(sameInstance(port)));
        assertThat(new HttpRequest().withSocketAddress(true, "someHost", port).getSocketAddress().getPort(), is(sameInstance(port)));
        assertThat(new HttpRequest().withSocketAddress(false, "someHost:9999", port).getSocketAddress().getPort(), is(sameInstance(port)));
    }

    @Test
    public void returnsKeepAlive() {
        assertThat(new HttpRequest().withKeepAlive(true).isKeepAlive(), is(Boolean.TRUE));
        assertThat(new HttpRequest().withKeepAlive(false).isKeepAlive(), is(Boolean.FALSE));
    }

    @Test
    public void returnsSsl() {
        // true secure socket address null
        assertThat(new HttpRequest().withSecure(true).isSecure(), is(Boolean.TRUE));
        // false secure socket address null
        assertThat(new HttpRequest().withSecure(false).isSecure(), is(Boolean.FALSE));
        // false secure scheme HTTP
        assertThat(new HttpRequest()
            .withSecure(false)
            .withSocketAddress(
                socketAddress()
                    .withHost("sdafgh")
                    .withPort(1234)
                    .withScheme(SocketAddress.Scheme.HTTP)
            ).isSecure(), is(Boolean.FALSE));
        // false secure scheme default
        assertThat(new HttpRequest()
            .withSecure(false)
            .withSocketAddress(
                socketAddress()
                    .withHost("sdafgh")
                    .withPort(1234)
            ).isSecure(), is(Boolean.FALSE));
        // false secure scheme HTTPS
        assertThat(new HttpRequest()
            .withSecure(false)
            .withSocketAddress(
                socketAddress()
                    .withHost("sdafgh")
                    .withPort(1234)
                    .withScheme(SocketAddress.Scheme.HTTPS)
            ).isSecure(), is(Boolean.TRUE));
        // true secure scheme HTTPS
        assertThat(new HttpRequest()
            .withSecure(true)
            .withSocketAddress(
                socketAddress()
                    .withHost("sdafgh")
                    .withPort(1234)
                    .withScheme(SocketAddress.Scheme.HTTPS)
            ).isSecure(), is(Boolean.TRUE));
        // null secure scheme HTTPS
        assertThat(new HttpRequest()
            .withSocketAddress(
                socketAddress()
                    .withHost("sdafgh")
                    .withPort(1234)
                    .withScheme(SocketAddress.Scheme.HTTPS)
            ).isSecure(), is(Boolean.TRUE));
        // true secure scheme HTTP
        assertThat(new HttpRequest()
            .withSecure(true)
            .withSocketAddress(
                socketAddress()
                    .withHost("sdafgh")
                    .withPort(1234)
            ).isSecure(), is(Boolean.TRUE));
    }

    @Test
    public void returnsPathParameters() {
        assertThat(new HttpRequest().withPathParameters(new Parameter("name", "value")).getPathParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withPathParameters(Collections.singletonList(new Parameter("name", "value"))).getPathParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withPathParameter(new Parameter("name", "value")).getPathParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withPathParameter("name", "value").getPathParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withSchemaPathParameter("name", "{ \"type\": \"string\" }").getPathParameterList().get(0), is(new Parameter(string("name"), schemaString("{ \"type\": \"string\" }"))));
        assertThat(new HttpRequest().withSchemaPathParameter("name", "{ \"type\": \"string\" }", "{ \"type\": \"integer\" }").getPathParameterList().get(0), is(new Parameter(string("name"), schemaString("{ \"type\": \"string\" }"), schemaString("{ \"type\": \"integer\" }"))));
        assertThat(new HttpRequest().withPathParameter(new Parameter("name", "value_one")).withPathParameter(new Parameter("name", "value_two")).getPathParameterList().get(0), is(new Parameter("name", "value_one", "value_two")));
        assertThat(new HttpRequest().withPathParameter(new Parameter("name", "value_one")).withPathParameter("name", "value_two").getPathParameterList().get(0), is(new Parameter("name", "value_one", "value_two")));
    }

    @Test
    public void returnsQueryStringParameters() {
        assertThat(new HttpRequest().withQueryStringParameters(new Parameter("name", "value")).getQueryStringParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withQueryStringParameters(Collections.singletonList(new Parameter("name", "value"))).getQueryStringParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withQueryStringParameter(new Parameter("name", "value")).getQueryStringParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withQueryStringParameter("name", "value").getQueryStringParameterList().get(0), is(new Parameter("name", "value")));
        assertThat(new HttpRequest().withSchemaQueryStringParameter("name", "{ \"type\": \"string\" }").getQueryStringParameterList().get(0), is(new Parameter(string("name"), schemaString("{ \"type\": \"string\" }"))));
        assertThat(new HttpRequest().withSchemaQueryStringParameter("name", "{ \"type\": \"string\" }", "{ \"type\": \"integer\" }").getQueryStringParameterList().get(0), is(new Parameter(string("name"), schemaString("{ \"type\": \"string\" }"), schemaString("{ \"type\": \"integer\" }"))));
        assertThat(new HttpRequest().withQueryStringParameter(new Parameter("name", "value_one")).withQueryStringParameter(new Parameter("name", "value_two")).getQueryStringParameterList().get(0), is(new Parameter("name", "value_one", "value_two")));
        assertThat(new HttpRequest().withQueryStringParameter(new Parameter("name", "value_one")).withQueryStringParameter("name", "value_two").getQueryStringParameterList().get(0), is(new Parameter("name", "value_one", "value_two")));
    }

    @Test
    public void returnsBody() {
        assertThat(new HttpRequest().withBody(new StringBody("somebody")).getBody(), is(new StringBody("somebody")));
        assertThat(new HttpRequest().withBody("somebody").getBodyAsString(), is("somebody"));
    }

    @Test
    public void nullStringBodyLeavesBodyNull() {
        // a request constructed with no body at all has a null body
        assertThat(new HttpRequest().getBody(), nullValue());
        assertThat(new HttpRequest().getBodyAsString(), nullValue());

        // a null string body must leave the body NULL (mirrors HttpResponse.withBody((String) null)),
        // rather than coercing to an empty StringBody
        assertThat(new HttpRequest().withBody((String) null).getBody(), nullValue());
        assertThat(new HttpRequest().withBody((String) null).getBodyAsString(), nullValue());

        // the charset overload already guarded null and remains so
        assertThat(new HttpRequest().withBody((String) null, java.nio.charset.StandardCharsets.UTF_8).getBody(), nullValue());
        assertThat(new HttpRequest().withBody((String) null, java.nio.charset.StandardCharsets.UTF_8).getBodyAsString(), nullValue());

        // the byte[] overload is intentionally NOT changed: it still constructs a BinaryBody whose
        // value is null, so getBody() is a non-null BinaryBody but getBodyAsString() is null
        assertThat(new HttpRequest().withBody((byte[]) null).getBody(), instanceOf(BinaryBody.class));
        assertThat(new HttpRequest().withBody((byte[]) null).getBodyAsString(), nullValue());
    }

    @Test
    public void returnsHeaders() {
        assertThat(new HttpRequest().withHeaders(new Header("name", "value")).getHeaderList().get(0), is(new Header("name", "value")));
        assertThat(new HttpRequest().withHeaders(Collections.singletonList(new Header("name", "value"))).getHeaderList().get(0), is(new Header("name", "value")));
        assertThat(new HttpRequest().withHeader(new Header("name", "value")).getHeaderList().get(0), is(new Header("name", "value")));
        assertThat(new HttpRequest().withHeader("name", "value").getHeaderList().get(0), is(new Header("name", "value")));
        assertThat(new HttpRequest().withSchemaHeader("name", "{ \"type\": \"string\" }").getHeaderList().get(0), is(new Header(string("name"), schemaString("{ \"type\": \"string\" }"))));
        assertThat(new HttpRequest().withSchemaHeader("name", "{ \"type\": \"string\" }", "{ \"type\": \"integer\" }").getHeaderList().get(0), is(new Header(string("name"), schemaString("{ \"type\": \"string\" }"), schemaString("{ \"type\": \"integer\" }"))));
        assertThat(new HttpRequest().withHeader(string("name")).getHeaderList().get(0), is(new Header("name", ".*")));
        assertThat(new HttpRequest().withHeader("name").getHeaderList().get(0), is(new Header("name", ".*")));
        assertThat(new HttpRequest().withHeader(new Header("name", "value_one")).withHeader(new Header("name", "value_two")).getHeaderList().get(0), is(new Header("name", "value_one", "value_two")));
        assertThat(new HttpRequest().withHeader(new Header("name", "value_one")).withHeader("name", "value_two").getHeaderList().get(0), is(new Header("name", "value_one", "value_two")));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value_one", "value_two")).getHeaderList().get(0), is(new Header("name", "value_one", "value_two")));
        assertThat(new HttpRequest().withHeaders(new Header("name")).getHeaderList().get(0), is(new Header("name", (Collection<String>) null)));
        assertThat(new HttpRequest().withHeaders(new Header("name")).getHeaderList().get(0), is(new Header("name")));
        assertThat(new HttpRequest().withHeaders().getHeaderList(), is(empty()));
    }

    @Test
    public void returnsFirstHeaders() {
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).getFirstHeader("name"), is("value1"));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1", "value2")).getFirstHeader("name"), is("value1"));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1", "value2"), new Header("name", "value3")).getFirstHeader("name"), is("value1"));
    }

    @Test
    public void shouldContainHeaderByName() {
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("name"), is(true));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("names"), is(false));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("value1"), is(false));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader(null), is(false));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader(""), is(false));
    }

    @Test
    public void shouldContainHeaderByNameAndValue() {
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("name", "value1"), is(true));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("names", "value1"), is(false));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("name", "value12"), is(false));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("value1", "name"), is(false));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader(null, null), is(false));
        assertThat(new HttpRequest().withHeaders(new Header("name", "value1")).containsHeader("", ""), is(false));
    }

    @Test
    public void returnsCookies() {
        assertThat(new HttpRequest().withCookies(new Cookie("name", "value")).getCookieList().get(0), is(new Cookie("name", "value")));
        assertThat(new HttpRequest().withCookies(new Cookie("name", "")).getCookieList().get(0), is(new Cookie("name", "")));
        assertThat(new HttpRequest().withCookies(new Cookie("name", null)).getCookieList().get(0), is(new Cookie("name", null)));
        assertThat(new HttpRequest().withCookies(Collections.singletonList(new Cookie("name", "value"))).getCookieList().get(0), is(new Cookie("name", "value")));

        assertThat(new HttpRequest().withCookie(new Cookie("name", "value")).getCookieList().get(0), is(new Cookie("name", "value")));
        assertThat(new HttpRequest().withCookie("name", "value").getCookieList().get(0), is(new Cookie("name", "value")));
        assertThat(new HttpRequest().withSchemaCookie("name", "{ \"type\": \"string\" }").getCookieList().get(0), is(new Cookie(string("name"), schemaString("{ \"type\": \"string\" }"))));
        assertThat(new HttpRequest().withCookie(new Cookie("name", "")).getCookieList().get(0), is(new Cookie("name", "")));
        assertThat(new HttpRequest().withCookie(new Cookie("name", null)).getCookieList().get(0), is(new Cookie("name", null)));
    }

    @Test
    public void shouldReturnFormattedRequestInToString() {
        assertThat(request()
                .withPath("some_path")
                .withBody("some_body")
                .withMethod("METHOD")
                .withHeaders(new Header("some_header", "some_header_value"))
                .withCookies(new Cookie("some_cookie", "some_cookie_value"))
                .withSecure(true)
                .withPathParameters(new Parameter("some_path_parameter", "some_path_parameter_value"))
                .withQueryStringParameters(new Parameter("some_parameter", "some_parameter_value"))
                .withKeepAlive(true)
                .toString(), is("{" + NEW_LINE +
                "  \"method\" : \"METHOD\"," + NEW_LINE +
                "  \"path\" : \"some_path\"," + NEW_LINE +
                "  \"pathParameters\" : {" + NEW_LINE +
                "    \"some_path_parameter\" : [ \"some_path_parameter_value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"queryStringParameters\" : {" + NEW_LINE +
                "    \"some_parameter\" : [ \"some_parameter_value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"headers\" : {" + NEW_LINE +
                "    \"some_header\" : [ \"some_header_value\" ]" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"cookies\" : {" + NEW_LINE +
                "    \"some_cookie\" : \"some_cookie_value\"" + NEW_LINE +
                "  }," + NEW_LINE +
                "  \"keepAlive\" : true," + NEW_LINE +
                "  \"secure\" : true," + NEW_LINE +
                "  \"body\" : \"some_body\"" + NEW_LINE +
                "}"));
    }

    @Test
    public void shouldClone() {
        // given
        HttpRequest requestOne = request()
            .withPath("some_path")
            .withBody("some_body")
            .withMethod("METHOD")
            .withHeader("some_header", "some_header_value")
            .withSecure(true)
            .withCookie("some_cookie", "some_cookie_value")
            .withPathParameter("some_path_parameter", "some_path_parameter_value")
            .withQueryStringParameter("some_parameter", "some_parameter_value")
            .withKeepAlive(true);

        // when
        HttpRequest requestTwo = requestOne.clone();

        // then
        assertThat(requestOne, not(sameInstance(requestTwo)));
        assertThat(requestOne, is(requestTwo));
    }

    @Test
    public void shouldUpdate() {
        // given
        HttpRequest requestOne = request()
            .withPath("some_path")
            .withBody("some_body")
            .withMethod("METHOD")
            .withHeader("some_header", "some_header_value")
            .withSecure(true)
            .withCookie("some_cookie", "some_cookie_value")
            .withPathParameter("some_path_parameter", "some_path_parameter_value")
            .withQueryStringParameter("some_parameter", "some_parameter_value")
            .withKeepAlive(true);
        HttpRequest requestTwo = request()
            .withPath("some_path_two")
            .withBody("some_body_two")
            .withMethod("METHO_TWO")
            .withHeader("some_header_two", "some_header_value_two")
            .withSecure(false)
            .withCookie("some_cookie_two", "some_cookie_value_two")
            .withPathParameter("some_path_parameter_two", "some_path_parameter_value_two")
            .withQueryStringParameter("some_parameter_two", "some_parameter_value_two")
            .withKeepAlive(false);

        // when
        requestOne.update(requestTwo, null);

        // then
        assertThat(requestOne, is(
            request()
                .withPath("some_path_two")
                .withBody("some_body_two")
                .withMethod("METHO_TWO")
                .withHeader("some_header", "some_header_value")
                .withHeader("some_header_two", "some_header_value_two")
                .withSecure(false)
                .withCookie("some_cookie", "some_cookie_value")
                .withCookie("some_cookie_two", "some_cookie_value_two")
                .withPathParameter("some_path_parameter", "some_path_parameter_value")
                .withPathParameter("some_path_parameter_two", "some_path_parameter_value_two")
                .withQueryStringParameter("some_parameter", "some_parameter_value")
                .withQueryStringParameter("some_parameter_two", "some_parameter_value_two")
                .withKeepAlive(false)
        ));
    }

    @Test
    public void shouldUpdateEmptyRequest() {
        // given
        HttpRequest requestOne = request();
        HttpRequest requestTwo = request()
            .withPath("some_path_two")
            .withBody("some_body_two")
            .withMethod("METHO_TWO")
            .withHeader("some_header_two", "some_header_value_two")
            .withSecure(false)
            .withCookie("some_cookie_two", "some_cookie_value_two")
            .withPathParameter("some_path_parameter_two", "some_path_parameter_value_two")
            .withQueryStringParameter("some_parameter_two", "some_parameter_value_two")
            .withKeepAlive(false);

        // when
        requestOne.update(requestTwo, null);

        // then
        assertThat(requestOne, is(
            request()
                .withPath("some_path_two")
                .withBody("some_body_two")
                .withMethod("METHO_TWO")
                .withHeader("some_header_two", "some_header_value_two")
                .withSecure(false)
                .withCookie("some_cookie_two", "some_cookie_value_two")
                .withPathParameter("some_path_parameter_two", "some_path_parameter_value_two")
                .withQueryStringParameter("some_parameter_two", "some_parameter_value_two")
                .withKeepAlive(false)
        ));
    }

    @Test
    public void parsesIpv6AndIpv4HostPort() {
        // IPv6 with port - brackets should be stripped
        String[] hostParts = HttpRequest.splitHostPort("[::1]:32890");
        assertThat(hostParts[0], is("::1"));
        assertThat(hostParts[1], is("32890"));
        
        // Hostname with port
        hostParts = HttpRequest.splitHostPort("localhost:32890");
        assertThat(hostParts[0], is("localhost"));
        assertThat(hostParts[1], is("32890"));
        
        // IPv4 with port
        hostParts = HttpRequest.splitHostPort("127.0.0.1:32890");
        assertThat(hostParts[0], is("127.0.0.1"));
        assertThat(hostParts[1], is("32890"));
        
        // IPv4 without port
        hostParts = HttpRequest.splitHostPort("127.0.0.1");
        assertThat(hostParts[0], is("127.0.0.1"));
        assertThat(hostParts.length, is(1));
        
        // IPv6 without port - brackets should be stripped
        hostParts = HttpRequest.splitHostPort("[::1]");
        assertThat(hostParts[0], is("::1"));
        assertThat(hostParts.length, is(1));
        
        // Full IPv6 address with port
        hostParts = HttpRequest.splitHostPort("[2001:db8::1]:8080");
        assertThat(hostParts[0], is("2001:db8::1"));
        assertThat(hostParts[1], is("8080"));

        // Malformed bracketed input (missing closing ']') — should fall through to plain split
        hostParts = HttpRequest.splitHostPort("[no-closing-bracket");
        assertThat(hostParts[0], is("[no-closing-bracket"));
        assertThat(hostParts.length, is(1));

        // Malformed bracketed IPv6 with colons but no closing ']' — falls through to split(":");
        // callers must read defensively (hostParts[0]/[1]) since splits on all colons.
        hostParts = HttpRequest.splitHostPort("[2001:db8::1");
        assertThat(hostParts[0], is("[2001"));
    }

    @Test
    public void splitHostPortIsIdenticalToSplitOnColonForNonBracketedInputs() {
        // The non-bracketed path replaced hostPort.split(":") with an indexOf-based parse that only takes
        // a shortcut for the two dominant shapes ("host" and "host:port") and falls back to split for
        // everything else. This differential corpus asserts byte-for-byte equality with the original
        // split(":"), adversarial in the dimensions the change touches: no colon, leading/trailing colon,
        // empty tokens, and runs of colons (which exercise split's trailing-empty-token removal).
        String[] corpus = {
            "", ":", "::", ":::", "a", "a:", ":a", "a:b", "a:b:c", "a:b:c:d", "a::b", "::1",
            ":b:c", "a:b:", "a::", "localhost", "127.0.0.1", "localhost:32890", "127.0.0.1:0",
            "host:", ":8080", " ", " : ", "1:2:3:4:5:6:7:8",
        };
        for (String input : corpus) {
            assertThat("splitHostPort differs from split(\":\") for: \"" + input + "\"",
                HttpRequest.splitHostPort(input), is(input.split(":")));
        }
    }

    @Test
    public void shouldNotQuoteTheRequestWhenTheHostHeaderIsMissing() {
        // the message reaches logs and error responses, where the whole request would carry its credentials
        HttpRequest request = request("/some/path")
            .withMethod("POST")
            .withHeader("Authorization", "Bearer AUTHZ-SECRET-1")
            .withQueryStringParameter("key", "QUERY-SECRET-5");

        try {
            request.socketAddressFromHostHeader();
            throw new AssertionError("expected IllegalArgumentException");
        } catch (IllegalArgumentException e) {
            assertThat(e.getMessage(), is("Host header must be provided to determine remote socket address, the request \"POST /some/path\" does not include the \"Host\" header"));
        }
    }

    @Test
    public void shouldParseIpv6SocketAddressFromHostHeader() {
        // Given
        HttpRequest request = request()
            .withHeader("Host", "[::1]:8080");
        
        // When
        InetSocketAddress socketAddress = request.socketAddressFromHostHeader();
        
        // Then
        assertThat(socketAddress.getAddress().getHostAddress(), is("0:0:0:0:0:0:0:1"));
        assertThat(socketAddress.getPort(), is(8080));
    }

    @Test
    public void shouldParseIpv6SocketAddressWithoutPort() {
        // Given
        HttpRequest request = request()
            .withSecure(true)
            .withHeader("Host", "[2001:db8::1]");
        
        // When
        InetSocketAddress socketAddress = request.socketAddressFromHostHeader();
        
        // Then
        assertThat(socketAddress.getAddress().getHostAddress(), is("2001:db8:0:0:0:0:0:1"));
        assertThat(socketAddress.getPort(), is(443));  // Default HTTPS port
    }

    @Test
    public void shouldParseIpv6WithSocketAddress() {
        // Given - When
        HttpRequest request = request()
            .withSocketAddress(false, "[::1]:9090", null);
        
        // Then
        assertThat(request.getSocketAddress().getHost(), is("::1"));
        assertThat(request.getSocketAddress().getPort(), is(Integer.valueOf(9090)));
    }

    @Test
    public void shouldCreateGetRequest() {
        HttpRequest request = HttpRequest.get("/path");
        assertThat(request.getMethod(), is(string("GET")));
        assertThat(request.getPath(), is(string("/path")));
    }

    @Test
    public void shouldCreatePostRequest() {
        HttpRequest request = HttpRequest.post("/path");
        assertThat(request.getMethod(), is(string("POST")));
        assertThat(request.getPath(), is(string("/path")));
    }

    @Test
    public void shouldCreatePutRequest() {
        HttpRequest request = HttpRequest.put("/path");
        assertThat(request.getMethod(), is(string("PUT")));
        assertThat(request.getPath(), is(string("/path")));
    }

    @Test
    public void shouldCreateDeleteRequest() {
        HttpRequest request = HttpRequest.delete("/path");
        assertThat(request.getMethod(), is(string("DELETE")));
        assertThat(request.getPath(), is(string("/path")));
    }

    @Test
    public void shouldCreatePatchRequest() {
        HttpRequest request = HttpRequest.patch("/path");
        assertThat(request.getMethod(), is(string("PATCH")));
        assertThat(request.getPath(), is(string("/path")));
    }

    @Test
    public void shouldCreateHeadRequest() {
        HttpRequest request = HttpRequest.head("/path");
        assertThat(request.getMethod(), is(string("HEAD")));
        assertThat(request.getPath(), is(string("/path")));
    }

    @Test
    public void shouldCreateOptionsRequest() {
        HttpRequest request = HttpRequest.options("/path");
        assertThat(request.getMethod(), is(string("OPTIONS")));
        assertThat(request.getPath(), is(string("/path")));
    }

    // ---- receivedTimestamp ----

    @Test
    public void receivedTimestampUnsetReturnsNull() {
        // the field is a primitive long backed by a sentinel; an unset request must still report null so
        // the null checks on the serve path (HttpState) and breakpoint dispatch keep working.
        HttpRequest request = request().withMethod("GET").withPath("/test");
        assertThat(request.getReceivedTimestamp(), is(nullValue()));
    }

    @Test
    public void receivedTimestampSetToNullReturnsNull() {
        HttpRequest request = request().withMethod("GET").withPath("/test");
        request.withReceivedTimestamp(4242L);
        assertThat(request.getReceivedTimestamp(), is(4242L));
        request.withReceivedTimestamp(null);
        assertThat("clearing the timestamp must round-trip back to null",
            request.getReceivedTimestamp(), is(nullValue()));
    }

    @Test
    public void receivedTimestampExcludedFromEquals() {
        HttpRequest a = request().withMethod("GET").withPath("/test");
        HttpRequest b = request().withMethod("GET").withPath("/test");

        a.withReceivedTimestamp(1000L);
        b.withReceivedTimestamp(2000L);

        assertThat("receivedTimestamp must NOT affect equals",
            a, is(b));
        assertThat("receivedTimestamp must NOT affect hashCode",
            a.hashCode(), is(b.hashCode()));
    }

    @Test
    public void dataPlaneMarkerIsInternalAndPreservedByCloning() throws Exception {
        HttpRequest original = request("/mockserver/expectation").withMethod("PUT")
            .withHeader("Authorization", "Bearer origin-secret").withProxyContext(null, true);

        assertThat(original.clone().isDataPlaneRequest(), is(true));
        assertThat(original.shallowClone().isDataPlaneRequest(), is(true));
        assertThat(original, is(request("/mockserver/expectation").withMethod("PUT").withHeader("Authorization", "Bearer origin-secret")));
        assertThat(original.toString().contains("dataPlaneRequest"), is(false));
        HttpRequest forged = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
            .readValue("{\"method\":\"PUT\",\"path\":\"/mockserver/expectation\",\"dataPlaneRequest\":true}", HttpRequest.class);
        assertThat(forged.isDataPlaneRequest(), is(false));
        HttpRequest restored = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper()
            .readValue(original.toString(), HttpRequest.class);
        assertThat(restored.isDataPlaneRequest(), is(false));
        HttpRequest logged = (HttpRequest) org.mockserver.authentication.CredentialRedaction.forLog(restored);
        assertThat(logged.getFirstHeader("Authorization"), is("***REDACTED***"));
    }

    @Test
    public void receivedTimestampPreservedByClone() {
        HttpRequest original = request().withMethod("POST").withPath("/clone-test");
        original.withReceivedTimestamp(12345L);

        HttpRequest cloned = original.clone();
        assertThat("clone must preserve receivedTimestamp",
            cloned.getReceivedTimestamp(), is(12345L));
    }

    @Test
    public void receivedTimestampPreservedByShallowClone() {
        HttpRequest original = request().withMethod("DELETE").withPath("/shallow");
        original.withReceivedTimestamp(67890L);

        HttpRequest cloned = original.shallowClone();
        assertThat("shallowClone must preserve receivedTimestamp",
            cloned.getReceivedTimestamp(), is(67890L));
    }

    @Test
    public void receivedTimestampExcludedFromJsonSerialization() {
        HttpRequest request = request().withMethod("GET").withPath("/json-test");
        request.withReceivedTimestamp(99999L);

        // The field is annotated @JsonIgnore, so it should not appear in JSON.
        // We verify by checking that the getter returns the value but the field
        // name 'receivedTimestamp' is not in the serialized form.
        String json = request.toString();
        // toString uses Jackson serialization — receivedTimestamp should be absent
        assertThat("receivedTimestamp should not appear in JSON output",
            json.contains("receivedTimestamp"), is(false));
    }
}

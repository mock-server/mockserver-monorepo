package org.mockserver.filters;

import org.junit.Test;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

/**
 * @author jamesdbloom
 */
public class HopByHopHeaderFilterTest {

    @Test
    public void shouldNotForwardHopByHopHeaders() {
        // given
        HttpRequest httpRequest = new HttpRequest();
        httpRequest.withHeaders(
            new Header("some_other_header"),
            new Header("proxy-connection"),
            new Header("connection"),
            new Header("keep-alive"),
            new Header("transfer-encoding"),
            new Header("te"),
            new Header("trailer"),
            new Header("proxy-authorization"),
            new Header("proxy-authenticate"),
            new Header("upgrade")
        );

        // when
        httpRequest = new HopByHopHeaderFilter().onRequest(httpRequest);

        // then
        assertThat(httpRequest.getHeaderList().size(), is(1));
    }

    @Test
    public void shouldNotForwardHopByHopHeadersWhenThereIsNoOtherHeader() {
        // given
        HttpRequest httpRequest = new HttpRequest().withHeaders(
            new Header("Proxy-Authorization", "Basic dXNlcjpwYXNzd29yZA=="),
            new Header("Proxy-Connection", "keep-alive")
        );

        // when
        HttpRequest filtered = new HopByHopHeaderFilter().onRequest(httpRequest);

        // then
        assertThat(filtered.getHeaderList(), is(empty()));
        assertThat("the request itself is not changed", httpRequest.getHeaderList().size(), is(2));
    }

    @Test
    public void shouldKeepARequestWithoutHeadersWithoutHeaders() {
        assertThat(new HopByHopHeaderFilter().onRequest(new HttpRequest().withPath("/none")).getHeaders(), nullValue());
    }

    @Test
    public void shouldKeepTheHeadersOfAResponseWhoseEveryHeaderIsHopByHop() {
        // a HEAD response, or a recorded one, that carries only these must keep its content-length
        HttpResponse httpResponse = new HttpResponse().withHeaders(
            new Header("content-length", "6"),
            new Header("connection", "keep-alive")
        );

        HttpResponse filtered = new HopByHopHeaderFilter().onResponse(httpResponse);

        assertThat(filtered.getFirstHeader("content-length"), is("6"));
        assertThat(filtered.getFirstHeader("connection"), is("keep-alive"));
    }

    @Test
    public void shouldNotHandleNullRequest() {
        assertThat(new HopByHopHeaderFilter().onRequest(null), nullValue());
    }
}

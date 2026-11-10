package org.mockserver.filters;

import org.junit.Test;
import org.mockserver.model.Header;
import org.mockserver.model.HttpRequest;

import static org.hamcrest.MatcherAssert.assertThat;
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
    public void shouldRemoveAllProxyHeadersWithoutMutatingInput() {
        HttpRequest original = new HttpRequest().withHeader("Proxy-Authorization", "Basic secret")
            .withHeader("Proxy-Authenticate", "Basic realm=\"proxy\"");

        HttpRequest forwarded = new HopByHopHeaderFilter().onRequest(original);

        assertThat(forwarded.getHeaderList().isEmpty(), is(true));
        assertThat(original.getFirstHeader("Proxy-Authorization"), is("Basic secret"));
        assertThat(original.getHeaderList().size(), is(2));
    }

    @Test
    public void shouldPreserveOriginCredentialsAndOtherHeadersWithoutMutatingInput() {
        HttpRequest original = new HttpRequest().withHeader("Proxy-Authorization", "Basic proxy-secret")
            .withHeader("Authorization", "Bearer origin-secret").withHeader("X-Trace", "trace");

        HttpRequest forwarded = new HopByHopHeaderFilter().onRequest(original);

        assertThat(forwarded.containsHeader("Proxy-Authorization"), is(false));
        assertThat(forwarded.getFirstHeader("Authorization"), is("Bearer origin-secret"));
        assertThat(forwarded.getFirstHeader("X-Trace"), is("trace"));
        assertThat(original.getFirstHeader("Proxy-Authorization"), is("Basic proxy-secret"));
    }

    @Test
    public void shouldPreserveAbsentHeaders() {
        HttpRequest original = new HttpRequest();

        HttpRequest forwarded = new HopByHopHeaderFilter().onRequest(original);

        assertThat(forwarded.getHeaders(), nullValue());
        assertThat(original.getHeaders(), nullValue());
    }

    @Test
    public void shouldNotHandleNullRequest() {
        assertThat(new HopByHopHeaderFilter().onRequest(null), nullValue());
    }
}

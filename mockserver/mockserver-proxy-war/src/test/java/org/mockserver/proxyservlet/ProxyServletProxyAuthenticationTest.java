package org.mockserver.proxyservlet;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.Base64;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The proxy WAR proxies every request for another host, so with {@code proxyAuthenticationUsername} and
 * {@code proxyAuthenticationPassword} set such a request is refused with {@code 407} before it can match an
 * expectation (and so before it can use up {@code Times}); a request for the WAR itself needs no proxy credentials.
 */
public class ProxyServletProxyAuthenticationTest {

    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-password";

    private ProxyServlet servlet;

    @After
    public void destroyServlet() {
        if (servlet != null) {
            servlet.destroy();
        }
    }

    private void start(Configuration configuration) {
        servlet = new ProxyServlet(configuration);
        MockHttpServletRequest upsert = new MockHttpServletRequest("PUT", "/mockserver/expectation");
        upsert.setContent("{\"httpRequest\":{\"path\":\"/once\"},\"times\":{\"remainingTimes\":1},\"httpResponse\":{\"body\":\"once\"}}".getBytes(UTF_8));
        assertThat(send(upsert).getStatus(), is(201));
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);
        return response;
    }

    private static MockHttpServletRequest once(String host, String password) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/once");
        request.addHeader("Host", host);
        if (password != null) {
            request.addHeader("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString((USERNAME + ":" + password).getBytes(UTF_8)));
        }
        return request;
    }

    private static String body(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), UTF_8);
    }

    @Test
    public void shouldRefuseARequestForAnotherHostWithoutProxyCredentialsBeforeMatching() {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));

        MockHttpServletResponse none = send(once("example.com", null));
        assertThat(none.getStatus(), is(407));
        assertThat(none.getHeader("Proxy-Authenticate"), startsWith("Basic realm="));
        assertThat(send(once("example.com", "not-the-password")).getStatus(), is(407));

        MockHttpServletResponse served = send(once("example.com", PASSWORD));
        assertThat("Times still unused", served.getStatus(), is(200));
        assertThat(body(served), is("once"));
    }

    @Test
    public void shouldServeARequestForItselfWithoutProxyCredentials() {
        start(configuration().proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD));

        MockHttpServletResponse served = send(once("localhost", null));

        assertThat(served.getStatus(), is(200));
        assertThat(body(served), is("once"));
    }

    @Test
    public void shouldServeWithoutProxyCredentialsWhenNoneAreConfigured() {
        start(configuration());

        MockHttpServletResponse served = send(once("example.com", null));

        assertThat(served.getStatus(), is(200));
        assertThat(body(served), is("once"));
    }
}

package org.mockserver.proxyservlet;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.cert.X509Certificate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.servlet.ServletDataPlaneAuthentication.CLIENT_CERTIFICATE_ATTRIBUTE;

/**
 * Data-plane authentication in the proxy WAR, which serves expectations before proxying: an unauthenticated request
 * is refused before it can match one, and so before it can use up {@code Times}.
 */
public class ProxyServletDataPlaneAuthenticationTest {

    private static final String TOKEN = "proxy-war-token";

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

    private static MockHttpServletRequest once() {
        return new MockHttpServletRequest("GET", "/once");
    }

    private static MockHttpServletRequest authenticated(MockHttpServletRequest request) {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private static String body(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), UTF_8);
    }

    @Test
    public void shouldRefuseWithoutCredentialsAndServeWithThemWithoutUsingUpTimes() {
        start(configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken(TOKEN));

        MockHttpServletResponse refused = send(once());
        assertThat(refused.getStatus(), is(401));
        assertThat(refused.getHeader("WWW-Authenticate"), is("Bearer"));

        MockHttpServletResponse served = send(authenticated(once()));
        assertThat(served.getStatus(), is(200));
        assertThat(new String(served.getContentAsByteArray(), UTF_8), is("once"));
    }

    @Test
    public void shouldAnswerTheRoutesThatSkipDataPlaneAuthenticationWithoutMatchingAnExpectation() {
        start(configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken(TOKEN));
        MockHttpServletRequest upsert = new MockHttpServletRequest("PUT", "/mockserver/expectation");
        upsert.setContent("{\"httpRequest\":{},\"times\":{\"remainingTimes\":1},\"httpResponse\":{\"body\":\"caught\"}}".getBytes(UTF_8));
        assertThat(send(upsert).getStatus(), is(201));

        MockHttpServletResponse callback = send(new MockHttpServletRequest("GET", "/_mockserver_callback_websocket"));
        assertThat(callback.getStatus(), is(501));
        assertThat(body(callback), not(containsString("caught")));
        for (String path : new String[]{"/mockserver/bind", "/bind", "/mockserver/stop", "/stop"}) {
            MockHttpServletResponse response = send(new MockHttpServletRequest("PUT", path));
            assertThat(path, response.getStatus(), is(501));
            assertThat(path, body(response), not(containsString("caught")));
        }
        MockHttpServletResponse status = send(new MockHttpServletRequest("PUT", "/status"));
        assertThat(status.getStatus(), is(200));
        assertThat(body(status), not(containsString("caught")));

        assertThat(send(new MockHttpServletRequest("GET", "/anything")).getStatus(), is(401));
        assertThat("Times must still be unused", body(send(authenticated(new MockHttpServletRequest("GET", "/anything")))), is("caught"));
    }

    @Test
    public void shouldServeTheControlPlanePreflightWithoutDataPlaneCredentials() {
        start(configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken(TOKEN));

        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/mockserver/expectation");
        preflight.addHeader("Origin", "http://localhost");
        preflight.addHeader("Access-Control-Request-Method", "PUT");

        assertThat(send(preflight).getStatus(), is(200));
    }

    @Test
    public void shouldServeWithoutCredentialsWhenAuthenticationIsOff() {
        start(configuration().dataPlaneBearerAuthenticationToken(TOKEN));

        MockHttpServletResponse served = send(once());

        assertThat(served.getStatus(), is(200));
        assertThat(new String(served.getContentAsByteArray(), UTF_8), is("once"));
    }

    @Test
    public void shouldRequireAContainerVerifiedClientCertificateWhenMutualTlsIsRequired() {
        start(configuration().tlsMutualAuthenticationRequired(true));

        assertThat(send(once()).getStatus(), is(426));
        MockHttpServletRequest noCertificate = once();
        noCertificate.setSecure(true);
        assertThat(send(noCertificate).getStatus(), is(401));
        MockHttpServletRequest withCertificate = once();
        withCertificate.setSecure(true);
        withCertificate.setAttribute(CLIENT_CERTIFICATE_ATTRIBUTE, new X509Certificate[]{mock(X509Certificate.class)});
        assertThat(send(withCertificate).getStatus(), is(200));
    }
}

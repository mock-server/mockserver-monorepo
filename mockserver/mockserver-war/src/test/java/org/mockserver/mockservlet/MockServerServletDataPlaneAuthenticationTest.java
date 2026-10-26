package org.mockserver.mockservlet;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.security.cert.X509Certificate;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.servlet.ServletDataPlaneAuthentication.CLIENT_CERTIFICATE_ATTRIBUTE;

/**
 * Data-plane authentication in the WAR: a request no control-plane route serves needs the configured credentials
 * (and, with {@code tlsMutualAuthenticationRequired}, a container-verified client certificate) before it can match an
 * expectation, so a refused request changes no {@code Times} or scenario state.
 */
public class MockServerServletDataPlaneAuthenticationTest {

    private static final String TOKEN = "war-token";

    private MockServerServlet servlet;

    @After
    public void destroyServlet() {
        if (servlet != null) {
            servlet.destroy();
        }
    }

    private void start(Configuration configuration) {
        servlet = new MockServerServlet(configuration);
    }

    private static Configuration bearerRequired() {
        return configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken(TOKEN);
    }

    private MockHttpServletResponse send(MockHttpServletRequest request) {
        MockHttpServletResponse response = new MockHttpServletResponse();
        servlet.service(request, response);
        return response;
    }

    private static MockHttpServletRequest get(String path) {
        return new MockHttpServletRequest("GET", path);
    }

    private static MockHttpServletRequest authenticated(MockHttpServletRequest request) {
        request.addHeader("Authorization", "Bearer " + TOKEN);
        return request;
    }

    private void upsert(String json) {
        MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/mockserver/expectation");
        request.setContent(json.getBytes(UTF_8));
        assertThat(send(request).getStatus(), is(201));
    }

    private static String body(MockHttpServletResponse response) {
        return new String(response.getContentAsByteArray(), UTF_8);
    }

    @Test
    public void shouldRefuseADataPlaneRequestWithoutCredentials() {
        start(bearerRequired());
        upsert("{\"httpRequest\":{\"path\":\"/mocked\"},\"httpResponse\":{\"body\":\"mocked\"}}");

        MockHttpServletResponse response = send(get("/mocked"));

        assertThat(response.getStatus(), is(401));
        assertThat(response.getHeader("WWW-Authenticate"), is("Bearer"));
        assertThat(body(response), is("Unauthorized for data plane"));
    }

    @Test
    public void shouldRefuseWrongCredentialsAndAnUnservedControlPlanePath() {
        start(bearerRequired());
        upsert("{\"httpRequest\":{},\"httpResponse\":{\"body\":\"caught\"}}");

        MockHttpServletRequest wrong = get("/mocked");
        wrong.addHeader("Authorization", "Bearer wrong");
        assertThat(send(wrong).getStatus(), is(401));
        assertThat(send(get("/mockserverfoo")).getStatus(), is(401));
        assertThat(send(get("/mockserver/nope")).getStatus(), is(401));
    }

    @Test
    public void shouldServeAnAuthenticatedDataPlaneRequest() {
        start(bearerRequired());
        upsert("{\"httpRequest\":{\"path\":\"/mocked\"},\"httpResponse\":{\"body\":\"mocked\"}}");

        MockHttpServletResponse response = send(authenticated(get("/mocked")));

        assertThat(response.getStatus(), is(200));
        assertThat(body(response), is("mocked"));
    }

    @Test
    public void shouldNotUseUpTimesOrMoveAScenarioForARefusedRequest() {
        start(bearerRequired());
        upsert("[" +
            "{\"httpRequest\":{\"path\":\"/once\"},\"times\":{\"remainingTimes\":1},\"httpResponse\":{\"body\":\"once\"}}," +
            "{\"httpRequest\":{\"path\":\"/advance\"},\"scenarioName\":\"auth\",\"scenarioState\":\"Started\",\"newScenarioState\":\"Advanced\",\"httpResponse\":{\"body\":\"moved\"}}," +
            "{\"httpRequest\":{\"path\":\"/state\"},\"scenarioName\":\"auth\",\"scenarioState\":\"Advanced\",\"httpResponse\":{\"body\":\"advanced\"}}" +
            "]");

        assertThat(send(get("/once")).getStatus(), is(401));
        assertThat(send(get("/advance")).getStatus(), is(401));

        assertThat("Times must still be unused", body(send(authenticated(get("/once")))), is("once"));
        assertThat("the scenario must still be in Started", send(authenticated(get("/state"))).getStatus(), is(404));
        assertThat(body(send(authenticated(get("/advance")))), is("moved"));
        assertThat(body(send(authenticated(get("/state")))), is("advanced"));
    }

    @Test
    public void shouldServeTheControlPlaneAndItsPreflightWithoutDataPlaneCredentials() {
        start(bearerRequired());
        upsert("{\"httpRequest\":{\"path\":\"/added\"},\"httpResponse\":{\"body\":\"added\"}}");

        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/mockserver/expectation");
        preflight.addHeader("Origin", "http://localhost");
        preflight.addHeader("Access-Control-Request-Method", "PUT");
        assertThat(send(preflight).getStatus(), is(200));

        MockHttpServletRequest status = new MockHttpServletRequest("PUT", "/mockserver/status");
        assertThat(send(status).getStatus(), is(200));
    }

    @Test
    public void shouldRefuseADataPlanePreflight() {
        start(bearerRequired());
        upsert("{\"httpRequest\":{},\"httpResponse\":{\"body\":\"caught\"}}");

        MockHttpServletRequest preflight = new MockHttpServletRequest("OPTIONS", "/mocked");
        preflight.addHeader("Origin", "http://localhost");
        preflight.addHeader("Access-Control-Request-Method", "GET");

        assertThat(send(preflight).getStatus(), is(401));
    }

    @Test
    public void shouldServeWithoutCredentialsWhenAuthenticationIsOff() {
        start(configuration().dataPlaneBearerAuthenticationToken(TOKEN));
        upsert("{\"httpRequest\":{\"path\":\"/mocked\"},\"times\":{\"remainingTimes\":1},\"httpResponse\":{\"body\":\"mocked\"}}");

        MockHttpServletResponse response = send(get("/mocked"));

        assertThat(response.getStatus(), is(200));
        assertThat(body(response), is("mocked"));
        assertThat(response.getHeader("WWW-Authenticate"), is(nullValue()));
        assertThat("Times used up as before", send(get("/mocked")).getStatus(), is(404));
    }

    @Test
    public void shouldRequireTlsAndAContainerVerifiedClientCertificateWhenMutualTlsIsRequired() {
        start(configuration().tlsMutualAuthenticationRequired(true));
        upsert("{\"httpRequest\":{\"path\":\"/mocked\"},\"httpResponse\":{\"body\":\"mocked\"}}");

        MockHttpServletResponse plainText = send(get("/mocked"));
        assertThat(plainText.getStatus(), is(426));
        assertThat(plainText.getHeader("Upgrade"), is("TLS/1.2, HTTP/1.1"));

        MockHttpServletRequest noCertificate = get("/mocked");
        noCertificate.setSecure(true);
        assertThat(send(noCertificate).getStatus(), is(401));

        MockHttpServletRequest withCertificate = get("/mocked");
        withCertificate.setSecure(true);
        withCertificate.setAttribute(CLIENT_CERTIFICATE_ATTRIBUTE, new X509Certificate[]{mock(X509Certificate.class)});
        MockHttpServletResponse served = send(withCertificate);
        assertThat(served.getStatus(), is(200));
        assertThat(body(served), is("mocked"));
    }
}

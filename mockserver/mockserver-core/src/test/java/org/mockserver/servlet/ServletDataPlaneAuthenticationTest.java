package org.mockserver.servlet;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.springframework.mock.web.MockHttpServletRequest;

import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.servlet.ServletDataPlaneAuthentication.CLIENT_CERTIFICATE_ATTRIBUTE;

public class ServletDataPlaneAuthenticationTest {

    private final MockServerLogger logger = new MockServerLogger();
    private final List<HttpState> httpStates = new ArrayList<>();

    private static final class CapturingResponseWriter extends ResponseWriter {
        HttpResponse captured;

        CapturingResponseWriter(Configuration configuration, MockServerLogger logger) {
            super(configuration, logger);
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.captured = response;
        }
    }

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    private boolean authenticate(Configuration configuration, MockHttpServletRequest servletRequest, HttpRequest request, CapturingResponseWriter writer) {
        HttpState httpState = new HttpState(configuration, logger, new Scheduler(configuration, logger, true));
        httpStates.add(httpState);
        return ServletDataPlaneAuthentication.isAuthenticated(configuration, logger, httpState, servletRequest, request, writer);
    }

    private static MockHttpServletRequest secure(Object clientCertificates) {
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/mocked");
        servletRequest.setSecure(true);
        if (clientCertificates != null) {
            servletRequest.setAttribute(CLIENT_CERTIFICATE_ATTRIBUTE, clientCertificates);
        }
        return servletRequest;
    }

    @Test
    public void shouldProceedWithoutWritingWhenNothingIsRequired() {
        Configuration configuration = configuration();
        CapturingResponseWriter writer = new CapturingResponseWriter(configuration, logger);

        assertThat(authenticate(configuration, new MockHttpServletRequest("GET", "/mocked"), request("/mocked"), writer), is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    @Test
    public void shouldRefuseWithTheGatesChallengeBeforeCheckingTheClientCertificate() {
        Configuration configuration = configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken("tok").tlsMutualAuthenticationRequired(true);
        CapturingResponseWriter writer = new CapturingResponseWriter(configuration, logger);

        assertThat(authenticate(configuration, new MockHttpServletRequest("GET", "/mocked"), request("/mocked"), writer), is(false));
        assertThat(writer.captured.getStatusCode(), is(401));
        assertThat(writer.captured.getFirstHeader("WWW-Authenticate"), is("Bearer"));
    }

    @Test
    public void shouldAnswerUpgradeRequiredWithoutTlsWhenMutualTlsIsRequired() {
        Configuration configuration = configuration().tlsMutualAuthenticationRequired(true);
        CapturingResponseWriter writer = new CapturingResponseWriter(configuration, logger);

        assertThat(authenticate(configuration, new MockHttpServletRequest("GET", "/mocked"), request("/mocked"), writer), is(false));
        assertThat(writer.captured.getStatusCode(), is(426));
        assertThat(writer.captured.getFirstHeader("Upgrade"), is("TLS/1.2, HTTP/1.1"));
    }

    @Test
    public void shouldRefuseATlsRequestWithNoClientCertificateWhenMutualTlsIsRequired() {
        Configuration configuration = configuration().tlsMutualAuthenticationRequired(true);

        for (Object clientCertificates : new Object[]{null, new X509Certificate[0], new X509Certificate[]{null}, "not a certificate"}) {
            CapturingResponseWriter writer = new CapturingResponseWriter(configuration, logger);
            assertThat(authenticate(configuration, secure(clientCertificates), request("/mocked"), writer), is(false));
            assertThat(writer.captured.getStatusCode(), is(401));
            assertThat(writer.captured.getFirstHeader("WWW-Authenticate"), is(""));
            assertThat(writer.captured.getBodyAsString(), is("Unauthorized for data plane"));
        }
    }

    @Test
    public void shouldProceedWithAContainerVerifiedClientCertificateAndCredentials() {
        Configuration configuration = configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken("tok").tlsMutualAuthenticationRequired(true);
        CapturingResponseWriter writer = new CapturingResponseWriter(configuration, logger);

        boolean authenticated = authenticate(configuration, secure(new X509Certificate[]{mock(X509Certificate.class)}),
            request("/mocked").withHeader("Authorization", "Bearer tok"), writer);

        assertThat(authenticated, is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    @Test
    public void shouldNotRequireAClientCertificateWhenMutualTlsIsOff() {
        Configuration configuration = configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = new CapturingResponseWriter(configuration, logger);

        assertThat(authenticate(configuration, new MockHttpServletRequest("GET", "/mocked"), request("/mocked").withHeader("Authorization", "Bearer tok"), writer), is(true));
        assertThat(writer.captured, is(nullValue()));
    }
}

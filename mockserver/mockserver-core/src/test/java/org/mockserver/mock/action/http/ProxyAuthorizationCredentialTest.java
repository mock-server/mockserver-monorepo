package org.mockserver.mock.action.http;

import org.junit.After;
import org.junit.Test;
import org.mockserver.authentication.DataPlaneAuthenticationGate;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.ExtensionRegistry;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mappers.JDKCertificateToMockServerX509Certificate;
import org.mockserver.mappers.NettyMessageForLog;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.servlet.ServletDataPlaneAuthentication;
import org.springframework.mock.web.MockHttpServletRequest;
import io.netty.handler.codec.http.DefaultHttpRequest;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import org.slf4j.event.Level;

import java.nio.charset.StandardCharsets;
import java.security.PublicKey;
import java.security.cert.Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.test.ClosedPort.CLOSED_PORT;

/**
 * With proxy authentication configured, the {@code Proxy-Authorization} header is the proxy hop's credential: it is
 * checked, then neither matched, logged nor recorded. Without proxy authentication configured, it is an ordinary
 * header, matched and recorded as sent.
 */
public class ProxyAuthorizationCredentialTest {

    private static final String USERNAME = "proxy-user";
    private static final String PASSWORD = "proxy-password";
    private static final String VALID_TOKEN = encode(USERNAME + ":" + PASSWORD);
    private static final String WRONG_TOKEN = encode(USERNAME + ":near-miss-password");
    private static final Set<String> LOCAL_ADDRESSES = Set.of("localhost", "127.0.0.1");

    private final List<HttpState> states = new ArrayList<>();

    @After
    public void stopStates() {
        states.forEach(HttpState::stop);
    }

    @Test
    public void shouldNotRecordOrLogAValidCredentialWhenProxyAuthenticationIsConfigured() {
        // given
        Configuration configuration = configuration().logLevel(Level.INFO).proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD);
        HttpState httpState = httpState(configuration);
        httpState.add(new Expectation(request("/mocked")).thenRespond(response("mocked")));

        // when
        HttpResponse response = process(httpState, request("/mocked").withMethod("GET")
            .withHeader("Host", "localhost")
            .withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN)
            .withHeader("Authorization", "Bearer origin-token"));

        // then
        assertThat(response.getStatusCode(), is(200));
        String recorded = retrieve(httpState, "REQUESTS");
        assertThat(recorded, containsString("/mocked"));
        assertThat(recorded, not(containsString(VALID_TOKEN)));
        assertThat(recorded, not(containsString("Proxy-Authorization")));
        assertThat("the origin's own credential is still recorded", recorded, containsString("origin-token"));
        assertThat(retrieve(httpState, "LOGS"), not(containsString(VALID_TOKEN)));
    }

    @Test
    public void shouldChallengeAndNotLogAWrongCredentialWhenProxyAuthenticationIsConfigured() {
        // given
        Configuration configuration = configuration().logLevel(Level.INFO).proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD);
        HttpState httpState = httpState(configuration);

        // when - an unmatched request for another host is a forward-proxy request
        HttpResponse response = process(httpState, request("/upstream").withMethod("GET")
            .withHeader("Host", "upstream.example:8080")
            .withHeader("Proxy-Authorization", "Basic " + WRONG_TOKEN));

        // then
        assertThat(response.getStatusCode(), is(407));
        assertThat(retrieve(httpState, "REQUESTS"), not(containsString(WRONG_TOKEN)));
        String logs = retrieve(httpState, "LOGS");
        assertThat(logs, containsString("proxy authentication failed"));
        assertThat(logs, not(containsString(WRONG_TOKEN)));
    }

    @Test
    public void shouldNotMatchOnTheProxyCredentialWhenProxyAuthenticationIsConfigured() {
        // given
        Configuration configuration = configuration().logLevel(Level.INFO).proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD);
        HttpState httpState = httpState(configuration);
        httpState.add(new Expectation(request("/mocked").withHeader("Proxy-Authorization", ".*")).thenRespond(response("mocked")));

        // when
        HttpResponse response = process(httpState, request("/mocked").withMethod("GET")
            .withHeader("Host", "localhost")
            .withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN));

        // then
        assertThat(response.getStatusCode(), is(404));
    }

    @Test
    public void shouldRecordAndMatchTheHeaderAsSentWhenProxyAuthenticationIsNotConfigured() {
        // given
        HttpState httpState = httpState(configuration().logLevel(Level.INFO));
        httpState.add(new Expectation(request("/mocked").withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN)).thenRespond(response("mocked")));

        // when
        HttpResponse response = process(httpState, request("/mocked").withMethod("GET")
            .withHeader("Host", "localhost")
            .withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN));

        // then
        assertThat(response.getStatusCode(), is(200));
        assertThat(retrieve(httpState, "REQUESTS"), containsString(VALID_TOKEN));
    }

    @Test
    public void shouldDecideProxyAuthenticationBeforeARequestFilterRunsAndNotRecordTheCredential() {
        // given - a filter that tries to supply the credential itself
        Configuration configuration = proxyAuthentication().extensions(() -> new AddsProxyCredential());
        HttpState httpState = httpState(configuration);

        // when
        HttpResponse refused = process(httpState, request("/upstream").withMethod("GET")
            .withHeader("Host", "upstream.example:8080")
            .withHeader("Proxy-Authorization", "Basic " + WRONG_TOKEN));
        HttpResponse forwarded = process(httpState, request("/upstream").withMethod("GET")
            .withHeader("Host", "127.0.0.1:" + CLOSED_PORT)
            .withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN));

        // then - the valid credential is let through to a forward, which fails only because nothing listens there
        assertThat(refused.getStatusCode(), is(407));
        assertThat(forwarded.getStatusCode(), not(is(407)));
        String recorded = retrieve(httpState, "REQUESTS");
        assertThat(recorded, not(containsString(WRONG_TOKEN)));
        assertThat(recorded, containsString("x-filtered"));
    }

    @Test
    public void shouldNotLogTheProxyCredentialWhenDataPlaneAuthenticationRefusesTheRequest() {
        // given
        Configuration configuration = proxyAuthentication()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("data-user")
            .dataPlaneBasicAuthenticationPassword("data-password");
        HttpState httpState = httpState(configuration);
        HttpRequest request = request("/mocked").withMethod("GET")
            .withHeader("Host", "localhost")
            .withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN);
        CapturingWriter unauthorized = new CapturingWriter(httpState);
        CapturingWriter upgradeRequired = new CapturingWriter(httpState);

        // when
        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(configuration, httpState.getMockServerLogger(), httpState, request, unauthorized);
        DataPlaneAuthenticationGate.writeTlsRequiredResponse(configuration, httpState.getMockServerLogger(), request, upgradeRequired);

        // then
        assertThat(authenticated, is(false));
        assertThat(unauthorized.response.join().getStatusCode(), is(401));
        assertThat(upgradeRequired.response.join().getStatusCode(), is(426));
        String logs = retrieve(httpState, "LOGS");
        assertThat(logs, containsString("data plane authentication failed"));
        assertThat(logs, containsString("no tls for data plane request"));
        assertThat(logs, not(containsString(VALID_TOKEN)));
        assertThat("the request itself keeps the header for the proxy check", request.getFirstHeader("Proxy-Authorization"), is("Basic " + VALID_TOKEN));
    }

    @Test
    public void shouldNotLogTheProxyCredentialWhenAServletRequestHasNoClientCertificate() {
        // given
        Configuration configuration = proxyAuthentication().tlsMutualAuthenticationRequired(true);
        HttpState httpState = httpState(configuration);
        MockHttpServletRequest servletRequest = new MockHttpServletRequest("GET", "/mocked");
        servletRequest.setSecure(true);
        HttpRequest request = request("/mocked").withMethod("GET").withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN);
        CapturingWriter writer = new CapturingWriter(httpState);

        // when
        boolean authenticated = ServletDataPlaneAuthentication.isAuthenticated(configuration, httpState.getMockServerLogger(), httpState, servletRequest, request, writer);

        // then
        assertThat(authenticated, is(false));
        assertThat(writer.response.join().getStatusCode(), is(401));
        String logs = retrieve(httpState, "LOGS");
        assertThat(logs, containsString("has no client certificate"));
        assertThat(logs, not(containsString(VALID_TOKEN)));
    }

    @Test
    public void shouldMaskTheProxyCredentialInTheDiagnosticCopyOfARawDataPlaneRequest() {
        // the copy Http1RequestCutShort and the request mapper's decode failures log
        DefaultHttpRequest raw = new DefaultHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.POST, "http://upstream.example/upload");
        raw.headers().set("Proxy-Authorization", "Basic " + VALID_TOKEN);

        HttpRequest diagnostic = NettyMessageForLog.request(raw);

        assertThat(diagnostic.getFirstHeader("Proxy-Authorization"), is("***REDACTED***"));
        assertThat(diagnostic.toString(), not(containsString(VALID_TOKEN)));
    }

    @Test
    public void shouldNotLogTheProxyCredentialAtTraceWhenARequestIsRefusedBeforeDispatch() {
        // given
        Configuration configuration = proxyAuthentication()
            .logLevel(Level.TRACE)
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("data-token");
        HttpState httpState = httpState(configuration);
        HttpRequest request = request("/mocked").withMethod("GET")
            .withHeader("Host", "localhost")
            .withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN);
        CapturingWriter writer = new CapturingWriter(httpState);

        // when - the transports' order: the control plane declines it, then the data-plane gate refuses it
        boolean handled = httpState.handle(request, writer, false);
        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(configuration, httpState.getMockServerLogger(), httpState, request, writer);

        // then
        assertThat(handled, is(false));
        assertThat(authenticated, is(false));
        String logs = retrieve(httpState, "LOGS");
        assertThat(logs, containsString("received request"));
        assertThat(logs, not(containsString(VALID_TOKEN)));
    }

    @Test
    public void shouldNotLogTheProxyCredentialInFailureEntriesMadeBeforeDispatch() {
        // given
        HttpState httpState = httpState(proxyAuthentication());
        HttpRequest request = request("/mocked").withMethod("GET").withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN);

        // when - MockServer failing while dispatching, and a client certificate it cannot decode
        ControlPlaneFailureResponse.logUnexpectedFailure(httpState.getMockServerLogger(), request, new IllegalStateException("dispatch failed"));
        new JDKCertificateToMockServerX509Certificate(httpState.getMockServerLogger()).setClientCertificates(request, new Certificate[]{new UndecodableCertificate()});

        // then
        String logs = retrieve(httpState, "LOGS");
        assertThat(logs, containsString("unexpected exception processing request"));
        assertThat(logs, containsString("exception decoding client certificate"));
        assertThat(logs, not(containsString(VALID_TOKEN)));
        assertThat("the request itself keeps the header for the proxy check", request.getFirstHeader("Proxy-Authorization"), is("Basic " + VALID_TOKEN));
    }

    @Test
    public void shouldNotLogTheProxyCredentialWhenRefusedBeforeProcessAction() {
        // given - as the proxy WAR refuses a request for another host before calling processAction
        HttpState httpState = httpState(proxyAuthentication());
        HttpRequest request = request("/upstream").withMethod("GET")
            .withHeader("Host", "upstream.example")
            .withHeader("Proxy-Authorization", "Basic " + WRONG_TOKEN);
        CapturingWriter writer = new CapturingWriter(httpState);

        // when
        new HttpActionHandler(httpState.getConfiguration(), null, httpState, null, null).writeProxyAuthenticationRequired(request, writer);

        // then
        assertThat(writer.response.join().getStatusCode(), is(407));
        String logs = retrieve(httpState, "LOGS");
        assertThat(logs, containsString("proxy authentication failed"));
        assertThat(logs, not(containsString(WRONG_TOKEN)));
    }

    @Test
    public void shouldRefuseAnAbsoluteFormRequestWithAWrongCredentialBeforeMatchingWithoutLoggingIt() {
        // given - an expectation the request would otherwise match
        HttpState httpState = httpState(proxyAuthentication());
        httpState.add(new Expectation(request("/mocked")).thenRespond(response("mocked")));
        HttpRequest absoluteForm = request("/mocked").withMethod("GET")
            .withHeader("Host", "upstream.example")
            .withHeader("Proxy-Authorization", "Basic " + WRONG_TOKEN)
            .withAbsoluteFormRequestTarget(true);

        // when
        HttpResponse response = process(httpState, absoluteForm);

        // then
        assertThat(response.getStatusCode(), is(407));
        assertThat(retrieve(httpState, "LOGS"), not(containsString(WRONG_TOKEN)));
    }

    private static Configuration proxyAuthentication() {
        return configuration().logLevel(Level.INFO).proxyAuthenticationUsername(USERNAME).proxyAuthenticationPassword(PASSWORD);
    }

    private HttpState httpState(Configuration configuration) {
        MockServerLogger mockServerLogger = new MockServerLogger(configuration, ProxyAuthorizationCredentialTest.class);
        ExtensionRegistry registry = configuration.extensionsConfigured() ? ExtensionRegistry.start(configuration, mockServerLogger) : null;
        HttpState httpState = new HttpState(configuration, mockServerLogger, new Scheduler(configuration, mockServerLogger, true), registry);
        states.add(httpState);
        return httpState;
    }

    private static final class UndecodableCertificate extends Certificate {
        private UndecodableCertificate() {
            super("X.509");
        }

        @Override
        public byte[] getEncoded() {
            return new byte[]{0x30, 0x03, 0x02, 0x01, 0x00};
        }

        @Override
        public void verify(PublicKey key) {
        }

        @Override
        public void verify(PublicKey key, String sigProvider) {
        }

        @Override
        public String toString() {
            return "undecodable";
        }

        @Override
        public PublicKey getPublicKey() {
            return null;
        }
    }

    private static final class AddsProxyCredential implements RequestFilter {
        @Override
        public String name() {
            return "adds-proxy-credential";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) {
            return FilterResult.proceedWith(request.withHeader("x-filtered", "true").withHeader("Proxy-Authorization", "Basic " + VALID_TOKEN));
        }
    }

    private static HttpResponse process(HttpState httpState, HttpRequest request) {
        CapturingWriter writer = new CapturingWriter(httpState);
        new HttpActionHandler(httpState.getConfiguration(), null, httpState, null, null)
            .processAction(request, writer, null, LOCAL_ADDRESSES, false, true);
        try {
            return writer.response.get(10, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new AssertionError("no response", e);
        }
    }

    private static String retrieve(HttpState httpState, String type) {
        return httpState.retrieve(request("/mockserver/retrieve").withMethod("PUT").withQueryStringParameter("type", type)).getBodyAsString();
    }

    private static String encode(String credentials) {
        return Base64.getEncoder().encodeToString(credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static final class CapturingWriter extends ResponseWriter {
        private final CompletableFuture<HttpResponse> response = new CompletableFuture<>();

        private CapturingWriter(HttpState httpState) {
            super(httpState.getConfiguration(), httpState.getMockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse httpResponse) {
            response.complete(httpResponse);
        }
    }
}

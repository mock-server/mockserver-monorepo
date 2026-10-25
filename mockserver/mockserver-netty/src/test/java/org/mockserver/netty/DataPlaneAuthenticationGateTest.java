package org.mockserver.netty;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;

/**
 * Transport-agnostic unit tests for the shared {@link DataPlaneAuthenticationGate}. These need no QUIC
 * and no running server — they drive the gate directly with a capturing {@link ResponseWriter}, which is
 * exactly what both {@code HttpRequestHandler} and {@code Http3MockServerHandler} invoke. This guarantees
 * the 401 path and its single control-plane preflight exemption hold on EVERY transport even when the QUIC e2e is skipped.
 */
public class DataPlaneAuthenticationGateTest {

    private final MockServerLogger logger = new MockServerLogger();
    private final List<HttpState> httpStates = new ArrayList<>();

    /** Captures the last response written; sendResponse is the single sink both real writers implement. */
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

    private CapturingResponseWriter writer(Configuration configuration) {
        return new CapturingResponseWriter(configuration, logger);
    }

    private HttpState httpState(Configuration configuration) {
        HttpState httpState = new HttpState(configuration, logger, new Scheduler(configuration, logger, true));
        httpStates.add(httpState);
        return httpState;
    }

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    @Test
    public void defaultOffProceedsWithoutWritingResponse() {
        Configuration configuration = configuration();
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request().withPath("/anything"), writer);

        assertThat(authenticated, is(true));
        assertThat("default-off must not write any response", writer.captured, is(nullValue()));
    }

    @Test
    public void unauthorizedResponseIsNullWhenOffOrAuthenticatedAndTheChallengedRefusalOtherwise() {
        assertThat(DataPlaneAuthenticationGate.unauthorizedResponse(configuration(), logger, request().withPath("/mocked")), is(nullValue()));

        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        assertThat(DataPlaneAuthenticationGate.unauthorizedResponse(configuration, logger,
            request().withPath("/mocked").withHeader("Authorization", "Bearer tok")), is(nullValue()));

        HttpResponse refusal = DataPlaneAuthenticationGate.unauthorizedResponse(configuration, logger, request().withPath("/mocked"));
        assertThat(refusal.getStatusCode(), is(401));
        assertThat(refusal.getFirstHeader("WWW-Authenticate"), is("Bearer"));
    }

    @Test
    public void enabledDataPlaneRequestWithNoCredentialsIsRejectedWith401() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request().withPath("/mocked"), writer);

        assertThat(authenticated, is(false));
        assertThat(writer.captured, is(org.hamcrest.Matchers.notNullValue()));
        assertThat(writer.captured.getStatusCode(), is(401));
        assertThat(writer.captured.getBodyAsString(), is("Unauthorized for data plane"));
    }

    @Test
    public void enabledDataPlaneRequestWithValidBearerProceeds() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request().withPath("/mocked").withHeader("Authorization", "Bearer tok"), writer);

        assertThat(authenticated, is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    @Test
    public void basicChallengeIncludesConfiguredRealmOn401() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret")
            .dataPlaneBasicAuthenticationRealm("my-realm");
        CapturingResponseWriter writer = writer(configuration);

        DataPlaneAuthenticationGate.isAuthenticated(configuration, logger, httpState(configuration), request().withPath("/mocked"), writer);

        assertThat(writer.captured.getStatusCode(), is(401));
        assertThat(writer.captured.getFirstHeader("WWW-Authenticate"), containsString("Basic realm=\"my-realm\""));
    }

    @Test
    public void validBasicCredentialsProceed() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret");
        CapturingResponseWriter writer = writer(configuration);
        String header = "Basic " + Base64.getEncoder().encodeToString("user:secret".getBytes(StandardCharsets.UTF_8));

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request().withPath("/mocked").withHeader("Authorization", header), writer);

        assertThat(authenticated, is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    // ---- paths the control plane did not serve (callers gate only after every control-plane route declined) ----

    @Test
    public void pathOnlySharingControlPlanePrefixIsGated() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");

        // reaching the gate means no control-plane route served the request, so it goes to expectation
        // matching: the /mockserver prefix alone must not exempt it
        for (String path : new String[]{
            "/mockserverfoo", "/mockserver", "/mockserver/nope", "/mockserver/status", "/mockserver/ready"}) {
            CapturingResponseWriter writer = writer(configuration);
            boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
                configuration, logger, httpState(configuration), request().withMethod("GET").withPath(path), writer);
            assertThat("must be gated: " + path, authenticated, is(false));
            assertThat("must be 401'd: " + path, writer.captured.getStatusCode(), is(401));
        }
    }

    @Test
    public void livenessPathReachingGateIsGated() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok")
            .livenessHttpGetPath("/liveness/probe");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request().withMethod("POST").withPath("/liveness/probe"), writer);

        assertThat(authenticated, is(false));
        assertThat(writer.captured.getStatusCode(), is(401));
    }

    @Test
    public void controlPlaneCorsPreflightIsNotGated() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request()
                .withMethod("OPTIONS")
                .withPath("/mockserver/expectation")
                .withHeader("Origin", "http://localhost")
                .withHeader("Access-Control-Request-Method", "PUT"), writer);

        assertThat(authenticated, is(true));
        assertThat(writer.captured, is(nullValue()));
    }

    @Test
    public void dataPlaneCorsPreflightIsGated() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request()
                .withMethod("OPTIONS")
                .withPath("/mockserverfoo")
                .withHeader("Origin", "http://localhost")
                .withHeader("Access-Control-Request-Method", "GET"), writer);

        assertThat(authenticated, is(false));
        assertThat(writer.captured.getStatusCode(), is(401));
    }

    @Test
    public void nonControlPlaneDataPlanePathIsStillGated() {
        Configuration configuration = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("tok")
            .livenessHttpGetPath("/liveness/probe");
        CapturingResponseWriter writer = writer(configuration);

        boolean authenticated = DataPlaneAuthenticationGate.isAuthenticated(
            configuration, logger, httpState(configuration), request().withPath("/some/mocked/api"), writer);

        assertThat(authenticated, is(false));
        assertThat(writer.captured.getStatusCode(), is(401));
    }
}

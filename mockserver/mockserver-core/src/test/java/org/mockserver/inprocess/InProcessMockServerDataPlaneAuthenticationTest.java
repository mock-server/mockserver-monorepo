package org.mockserver.inprocess;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Data-plane authentication on an in-process server: a request it serves from expectations needs the configured
 * credentials in its headers, and a refused one changes no expectation or scenario state.
 */
public class InProcessMockServerDataPlaneAuthenticationTest {

    private static final String TOKEN = "in-process-token";

    private InProcessMockServer server;

    @After
    public void closeServer() {
        if (server != null) {
            server.close();
        }
    }

    private InProcessMockServer serverRequiring(Configuration configuration) {
        server = new InProcessMockServer(configuration);
        return server;
    }

    private static Configuration bearerRequired() {
        return configuration().dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken(TOKEN);
    }

    private static HttpRequest authenticated(HttpRequest request) {
        return request.withHeader("Authorization", "Bearer " + TOKEN);
    }

    @Test
    public void shouldRefuseADataPlaneRequestWithoutCredentials() {
        serverRequiring(bearerRequired()).upsert(new Expectation(request("/mocked")).thenRespond(response("mocked")));

        HttpResponse response = server.handle(request("/mocked"));

        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getReasonPhrase(), is("Unauthorized"));
        assertThat(response.getFirstHeader("WWW-Authenticate"), is("Bearer"));
        assertThat(response.getBodyAsString(), is("Unauthorized for data plane"));
    }

    @Test
    public void shouldRefuseWrongCredentialsAndAnUnservedControlPlanePath() {
        serverRequiring(bearerRequired()).upsert(new Expectation(request()).thenRespond(response("caught")));

        assertThat(server.handle(request("/mocked").withHeader("Authorization", "Bearer wrong")).getStatusCode(), is(401));
        assertThat(server.handle(request("/mockserverfoo")).getStatusCode(), is(401));
        assertThat(server.handle(request("/mockserver/nope").withMethod("GET")).getStatusCode(), is(401));
    }

    @Test
    public void shouldServeAnAuthenticatedDataPlaneRequest() {
        serverRequiring(bearerRequired()).upsert(new Expectation(request("/mocked")).thenRespond(response("mocked")));

        HttpResponse response = server.handle(authenticated(request("/mocked")));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("mocked"));
    }

    @Test
    public void shouldAcceptAnApiKeyHeader() {
        serverRequiring(configuration().dataPlaneAuthenticationRequired(true)
            .dataPlaneApiKeyAuthenticationHeader("X-API-Key").dataPlaneApiKeyAuthenticationValue("key"))
            .upsert(new Expectation(request("/mocked")).thenRespond(response("mocked")));

        assertThat(server.handle(request("/mocked")).getStatusCode(), is(401));
        assertThat(server.handle(request("/mocked").withHeader("X-API-Key", "key")).getBodyAsString(), is("mocked"));
    }

    @Test
    public void shouldNotUseUpTimesOrMoveAScenarioForARefusedRequest() {
        serverRequiring(bearerRequired()).upsert(
            new Expectation(request("/once"), Times.once(), TimeToLive.unlimited(), 0).thenRespond(response("once")),
            new Expectation(request("/advance"), Times.unlimited(), TimeToLive.unlimited(), 0)
                .withScenarioName("auth").withScenarioState("Started").withNewScenarioState("Advanced")
                .thenRespond(response("moved")),
            new Expectation(request("/state"), Times.unlimited(), TimeToLive.unlimited(), 0)
                .withScenarioName("auth").withScenarioState("Advanced")
                .thenRespond(response("advanced"))
        );

        assertThat(server.handle(request("/once")).getStatusCode(), is(401));
        assertThat(server.handle(request("/advance")).getStatusCode(), is(401));

        assertThat("Times.once must still be unused", server.handle(authenticated(request("/once"))).getBodyAsString(), is("once"));
        assertThat("the scenario must still be in Started", server.handle(authenticated(request("/state"))).getStatusCode(), is(404));
        assertThat(server.handle(authenticated(request("/advance"))).getBodyAsString(), is("moved"));
        assertThat(server.handle(authenticated(request("/state"))).getBodyAsString(), is("advanced"));
    }

    @Test
    public void shouldServeTheControlPlaneAndItsPreflightWithoutDataPlaneCredentials() {
        serverRequiring(bearerRequired());

        HttpResponse upsert = server.handle(request("/mockserver/expectation").withMethod("PUT")
            .withBody("{\"httpRequest\":{\"path\":\"/added\"},\"httpResponse\":{\"body\":\"added\"}}"));
        HttpResponse preflight = server.handle(request("/mockserver/expectation").withMethod("OPTIONS")
            .withHeader("Origin", "http://localhost")
            .withHeader("Access-Control-Request-Method", "PUT"));

        assertThat(upsert.getStatusCode(), is(201));
        assertThat(preflight.getStatusCode(), is(200));
        assertThat(server.handle(request("/added")).getStatusCode(), is(401));
        assertThat(server.handle(authenticated(request("/added"))).getBodyAsString(), is("added"));
    }

    @Test
    public void shouldRefuseADataPlanePreflight() {
        serverRequiring(bearerRequired()).upsert(new Expectation(request()).thenRespond(response("caught")));

        HttpResponse preflight = server.handle(request("/mocked").withMethod("OPTIONS")
            .withHeader("Origin", "http://localhost")
            .withHeader("Access-Control-Request-Method", "GET"));

        assertThat(preflight.getStatusCode(), is(401));
    }

    @Test
    public void shouldFailClosedWhenRequiredWithNoScheme() {
        serverRequiring(configuration().dataPlaneAuthenticationRequired(true))
            .upsert(new Expectation(request("/mocked")).thenRespond(response("mocked")));

        HttpResponse response = server.handle(authenticated(request("/mocked")));

        assertThat(response.getStatusCode(), is(401));
        assertThat(response.getFirstHeader("WWW-Authenticate"), is(""));
    }

    @Test
    public void shouldServeWithoutCredentialsWhenAuthenticationIsOff() {
        serverRequiring(configuration().dataPlaneBearerAuthenticationToken(TOKEN))
            .upsert(new Expectation(request("/mocked"), Times.once(), TimeToLive.unlimited(), 0).thenRespond(response("mocked")));

        HttpResponse response = server.handle(request("/mocked"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("mocked"));
        assertThat(response.getFirstHeader("WWW-Authenticate"), is(""));
        assertThat("Times.once used up as before", server.handle(request("/mocked")).getStatusCode(), is(404));
    }

    @Test
    public void shouldNotTreatAClientCertificateOnTheRequestAsACredential() {
        // there is no TLS handshake in process, so tlsMutualAuthenticationRequired does not apply and a
        // caller-supplied certificate chain proves nothing
        serverRequiring(bearerRequired().tlsMutualAuthenticationRequired(true))
            .upsert(new Expectation(request("/mocked")).thenRespond(response("mocked")));

        assertThat(server.handle(request("/mocked").withSecure(true).withClientCertificateChain(List.of(new org.mockserver.model.X509Certificate())))
            .getStatusCode(), is(401));
        HttpResponse authenticated = server.handle(authenticated(request("/mocked")));
        assertThat(authenticated.getStatusCode(), is(200));
        assertThat(authenticated.getFirstHeader("Upgrade"), is(""));
    }
}

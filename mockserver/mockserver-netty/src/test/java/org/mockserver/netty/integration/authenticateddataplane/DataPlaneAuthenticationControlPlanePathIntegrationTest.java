package org.mockserver.netty.integration.authenticateddataplane;

import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.matchers.TimeToLive;
import org.mockserver.matchers.Times;
import org.mockserver.netty.MockServer;
import org.mockserver.netty.MockServerCaTrustTestSupport;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.Expectation.when;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.stop.Stop.stopQuietly;

/**
 * Only requests the control plane actually SERVES are exempt from data-plane authentication. A path that
 * merely shares the {@code /mockserver} prefix ({@code /mockserverfoo}, an unrouted {@code /mockserver/nope})
 * reaches expectation matching, so with a catch-all expectation it must be refused like any other mocked
 * endpoint. Run over HTTP/1.1 and HTTP/2 (h2 over TLS); both share {@code HttpRequestHandler}.
 */
public class DataPlaneAuthenticationControlPlanePathIntegrationTest {

    private static final String BEARER_TOKEN = "bearer-token-123";
    private static final String CATCH_ALL_BODY = "catch-all-body";
    private static final String LIVENESS_PATH = "/liveness/probe";
    private static final String EARLY_BODY = "early-body";

    private static MockServer mockServer;
    private static MockServerClient mockServerClient;
    private static HttpClient http1Client;
    private static HttpClient http2Client;

    @BeforeClass
    public static void startServer() throws Exception {
        mockServer = new MockServer(configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken(BEARER_TOKEN)
            .livenessHttpGetPath(LIVENESS_PATH)
            .attemptToProxyIfNoMatchingExpectation(false), 0);
        mockServerClient = new MockServerClient("localhost", mockServer.getLocalPort());
        mockServerClient
            .when(request().withPath("/early").withRespondBeforeBody(true))
            .respond(response().withStatusCode(200).withBody(EARLY_BODY));
        mockServerClient
            .when(request())
            .respond(response().withStatusCode(200).withBody(CATCH_ALL_BODY));
        http1Client = newHttp1Client();
        http2Client = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .sslContext(MockServerCaTrustTestSupport.caTrustingSslContext())
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    @AfterClass
    public static void stopServer() {
        stopQuietly(mockServerClient);
        stopQuietly(mockServer);
    }

    private static HttpResponse<String> send(HttpClient client, String method, String path, String... headers) throws Exception {
        String scheme = client == http2Client ? "https" : "http";
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(scheme + "://localhost:" + mockServer.getLocalPort() + path))
            .timeout(Duration.ofSeconds(15))
            .method(method, HttpRequest.BodyPublishers.noBody());
        if (headers.length > 0) {
            builder.headers(headers);
        }
        HttpResponse<String> response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        assertThat("negotiated protocol", response.version(), is(client.version()));
        return response;
    }

    private static void assertRefused(HttpClient client, String method, String path) throws Exception {
        HttpResponse<String> response = send(client, method, path);
        assertThat(client.version() + " " + method + " " + path + " status", response.statusCode(), is(401));
        assertThat(client.version() + " " + method + " " + path + " body", response.body(), is("Unauthorized for data plane"));
    }

    private static void assertServedByCatchAllWithCredentials(HttpClient client, String method, String path) throws Exception {
        HttpResponse<String> response = send(client, method, path, "Authorization", "Bearer " + BEARER_TOKEN);
        assertThat(client.version() + " " + method + " " + path + " status", response.statusCode(), is(200));
        assertThat(client.version() + " " + method + " " + path + " body", response.body(), is(CATCH_ALL_BODY));
    }

    @Test
    public void shouldRefusePathSharingControlPlanePrefixWithoutCredentials() throws Exception {
        for (HttpClient client : new HttpClient[]{http1Client, http2Client}) {
            assertRefused(client, "GET", "/mockserverfoo");
            assertRefused(client, "POST", "/mockserverfoo/bar");
            assertServedByCatchAllWithCredentials(client, "GET", "/mockserverfoo");
        }
    }

    @Test
    public void shouldRefuseUnroutedControlPlanePathWithoutCredentials() throws Exception {
        for (HttpClient client : new HttpClient[]{http1Client, http2Client}) {
            assertRefused(client, "GET", "/mockserver/nope");
            assertRefused(client, "GET", "/mockserver");
            // a real control-plane path with a method the control plane does not serve
            assertRefused(client, "GET", "/mockserver/status");
            assertServedByCatchAllWithCredentials(client, "GET", "/mockserver/nope");
        }
    }

    @Test
    public void shouldRefuseLivenessPathWithMethodLivenessProbeDoesNotServe() throws Exception {
        for (HttpClient client : new HttpClient[]{http1Client, http2Client}) {
            assertRefused(client, "POST", LIVENESS_PATH);
        }
    }

    @Test
    public void shouldServeControlPlaneAndProbesWithoutDataPlaneCredentials() throws Exception {
        for (HttpClient client : new HttpClient[]{http1Client, http2Client}) {
            HttpResponse<String> status = send(client, "PUT", "/mockserver/status");
            assertThat(client.version() + " /mockserver/status", status.statusCode(), is(200));
            assertThat(status.body(), not(is(CATCH_ALL_BODY)));

            HttpResponse<String> ready = send(client, "GET", "/mockserver/ready");
            assertThat(client.version() + " /mockserver/ready", ready.statusCode(), is(200));
            assertThat(ready.body(), not(is(CATCH_ALL_BODY)));

            HttpResponse<String> liveness = send(client, "GET", LIVENESS_PATH);
            assertThat(client.version() + " liveness", liveness.statusCode(), is(200));
            assertThat(liveness.body(), not(is(CATCH_ALL_BODY)));

            HttpResponse<String> retrieve = send(client, "PUT", "/mockserver/retrieve?type=ACTIVE_EXPECTATIONS");
            assertThat(client.version() + " /mockserver/retrieve", retrieve.statusCode(), is(200));
            assertThat(retrieve.body(), not(is(CATCH_ALL_BODY)));
        }
    }

    @Test
    public void shouldAnswerControlPlaneCorsPreflightWithoutDataPlaneCredentials() throws Exception {
        // a browser never sends credentials on a preflight, so refusing it would lock a cross-origin
        // control-plane client out; processAction answers it before expectation matching
        for (HttpClient client : new HttpClient[]{http1Client, http2Client}) {
            HttpResponse<String> preflight = send(client, "OPTIONS", "/mockserver/expectation",
                "Origin", "http://localhost", "Access-Control-Request-Method", "PUT");
            assertThat(client.version() + " control-plane preflight", preflight.statusCode(), is(200));
            assertThat(preflight.body(), not(is(CATCH_ALL_BODY)));
        }
    }

    @Test
    public void shouldRefuseDataPlaneCorsPreflightWithoutCredentials() throws Exception {
        for (HttpClient client : new HttpClient[]{http1Client, http2Client}) {
            HttpResponse<String> preflight = send(client, "OPTIONS", "/mockserverfoo",
                "Origin", "http://localhost", "Access-Control-Request-Method", "GET");
            assertThat(client.version() + " data-plane preflight", preflight.statusCode(), is(401));
        }
    }

    @Test
    public void shouldRefuseRespondBeforeBodyExpectationWithoutCredentials() throws Exception {
        // HTTP/1.1 answers a respondBeforeBody expectation from the headers of a connection's first
        // request, ahead of the handler that runs the gate, so each request needs a new connection
        assertRefused(newHttp1Client(), "GET", "/early");

        HttpResponse<String> authenticated = send(newHttp1Client(), "GET", "/early", "Authorization", "Bearer " + BEARER_TOKEN);
        assertThat("early with credentials", authenticated.statusCode(), is(200));
        assertThat(authenticated.body(), is(EARLY_BODY));
        assertThat("answered by the early path", authenticated.headers().firstValue("connection").orElse(""), is("close"));
    }

    @Test
    public void shouldNotChangeRespondBeforeBodyStateForRequestWithoutCredentials() throws Exception {
        mockServerClient.upsert(
            when(request().withPath("/early-once").withRespondBeforeBody(true), Times.once(), TimeToLive.unlimited(), 10)
                .thenRespond(response().withBody("early-once-body")),
            when(request().withPath("/early-scenario").withRespondBeforeBody(true), Times.unlimited(), TimeToLive.unlimited(), 10)
                .withScenarioName("early-auth").withScenarioState("Started").withNewScenarioState("Advanced")
                .thenRespond(response().withBody("early-scenario-body")),
            when(request().withPath("/scenario-state"), Times.unlimited(), TimeToLive.unlimited(), 10)
                .withScenarioName("early-auth").withScenarioState("Advanced")
                .thenRespond(response().withBody("state-is-Advanced"))
        );

        // refused requests must neither use up the Times.once unit, nor leave it marked in progress,
        // nor move the scenario on
        assertRefused(newHttp1Client(), "GET", "/early-once");
        assertRefused(newHttp1Client(), "GET", "/early-scenario");

        HttpResponse<String> once = send(newHttp1Client(), "GET", "/early-once", "Authorization", "Bearer " + BEARER_TOKEN);
        assertThat(once.body(), is("early-once-body"));
        HttpResponse<String> state = send(newHttp1Client(), "GET", "/scenario-state", "Authorization", "Bearer " + BEARER_TOKEN);
        assertThat("scenario must still be in Started", state.body(), is(CATCH_ALL_BODY));
    }

    private static HttpClient newHttp1Client() {
        return HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }
}

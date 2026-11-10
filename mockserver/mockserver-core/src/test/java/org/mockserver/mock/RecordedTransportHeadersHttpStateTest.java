package org.mockserver.mock;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.RetrieveType;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ExpectationSerializer;
import org.mockserver.serialization.RequestDefinitionSerializer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.core.Is.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.FORWARDED_REQUEST;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Recorded proxied traffic carries hop-by-hop and transport headers that describe the client's connection, not
 * the request: a curl-style client sends {@code Proxy-Connection}, a Node client {@code Connection}, and often
 * {@code content-length}. None of them may become a required matcher on any surface that turns recorded traffic
 * into an expectation, or the mock only matches a client that sends the same values. {@code Host} is kept in
 * recordings and cassettes, where it tells two upstreams apart, and dropped when recordings are promoted to mocks,
 * which serve applications that call MockServer directly.
 */
public class RecordedTransportHeadersHttpStateTest {

    private final List<HttpState> httpStates = new ArrayList<>();
    private final RequestDefinitionSerializer requestDefinitionSerializer = new RequestDefinitionSerializer(new MockServerLogger());
    private final ExpectationSerializer expectationSerializer = new ExpectationSerializer(new MockServerLogger());

    private static class FakeResponseWriter extends ResponseWriter {
        HttpResponse response;

        FakeResponseWriter() {
            super(configuration(), new MockServerLogger());
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            this.response = response;
        }
    }

    @After
    public void stopHttpStates() {
        httpStates.forEach(HttpState::stop);
    }

    private HttpState newHttpState() {
        Configuration configuration = configuration();
        HttpState httpState = new HttpState(configuration, new MockServerLogger(configuration, MockServerLogger.class), mock(Scheduler.class));
        httpStates.add(httpState);
        return httpState;
    }

    private static HttpRequest proxiedRequest(String path) {
        return request(path)
            .withMethod("POST")
            .withHeader("Host", "127.0.0.1:1134")
            .withHeader("Proxy-Connection", "Keep-Alive")
            .withHeader("Proxy-Authorization", "Basic dXNlcjpwYXNz")
            .withHeader("Connection", "keep-alive, X-Hop")
            .withHeader("Keep-Alive", "timeout=5")
            .withHeader("TE", "trailers")
            .withHeader("X-Hop", "per-connection")
            .withHeader("content-length", "4")
            .withHeader("X-Api-Version", "2")
            .withBody("ping");
    }

    private static void recordForwardedCall(HttpState httpState, HttpRequest request) {
        HttpResponse httpResponse = response("pong").withStatusCode(200);
        httpState.log(
            new LogEntry()
                .setType(FORWARDED_REQUEST)
                .setCorrelationId("correlation-" + request.getPath().getValue())
                .setHttpRequest(request)
                .setHttpResponse(httpResponse)
                .setExpectation(request, httpResponse)
        );
    }

    private static HttpResponse handle(HttpState httpState, HttpRequest controlPlaneRequest) {
        FakeResponseWriter responseWriter = new FakeResponseWriter();
        assertThat(httpState.handle(controlPlaneRequest, responseWriter, false), is(true));
        return responseWriter.response;
    }

    private HttpRequest retrieveRequest(RetrieveType type) {
        return request("/mockserver/retrieve")
            .withMethod("PUT")
            .withQueryStringParameter("type", type.name())
            .withBody(requestDefinitionSerializer.serialize(request()));
    }

    private static List<String> headerNames(HttpRequest request) {
        return request.getHeaderList().stream()
            .map(header -> header.getName().getValue().toLowerCase(Locale.ROOT))
            .collect(Collectors.toList());
    }

    @Test
    public void shouldLeaveTransportHeadersOutOfRetrievedRecordedExpectations() {
        HttpState httpState = newHttpState();
        recordForwardedCall(httpState, proxiedRequest("/recorded"));

        HttpResponse response = handle(httpState, retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS));

        Expectation[] recorded = expectationSerializer.deserializeArray(response.getBodyAsString(), true);
        assertThat(recorded.length, is(1));
        HttpRequest matcher = (HttpRequest) recorded[0].getHttpRequest();
        assertThat(headerNames(matcher), containsInAnyOrder("host", "x-api-version"));
        assertThat(matcher.getBodyAsString(), is("ping"));
    }

    @Test
    public void shouldKeepNonCredentialTransportHeadersAndOriginCredentialsInTheRecordedExchange() {
        HttpState httpState = newHttpState();
        HttpRequest original = proxiedRequest("/recorded")
            .withHeader("Authorization", "Bearer origin-record-token")
            .withCookie("session", "origin-record-cookie");
        recordForwardedCall(httpState, original);
        handle(httpState, retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS));

        HttpResponse response = handle(httpState, retrieveRequest(RetrieveType.REQUEST_RESPONSES));

        String body = response.getBodyAsString();
        for (String name : List.of("Host", "Proxy-Connection", "Connection", "Keep-Alive", "TE", "X-Hop", "content-length")) {
            assertThat(body, containsString("\"" + name + "\""));
        }
        assertThat(body, not(containsString("Proxy-Authorization")));
        assertThat(body, not(containsString("dXNlcjpwYXNz")));
        assertThat(body, containsString("origin-record-token"));
        assertThat(body, containsString("origin-record-cookie"));
        assertThat(original.getFirstHeader("Proxy-Authorization"), is("Basic dXNlcjpwYXNz"));
    }

    @Test
    public void shouldPromoteVerbatimRecordingToAMockThatMatchesADirectRequest() {
        HttpState httpState = newHttpState();
        recordForwardedCall(httpState, proxiedRequest("/verbatim"));

        handle(httpState, request("/mockserver/recordings/promote").withMethod("PUT").withQueryStringParameter("consolidate", "false"));

        HttpRequest direct = request("/verbatim").withMethod("POST").withHeader("Host", "localhost:1124").withHeader("X-Api-Version", "2").withBody("ping");
        assertThat(httpState.firstMatchingExpectation(direct), is(notNullValue()));
        assertThat(httpState.firstMatchingExpectation(request("/verbatim").withMethod("POST").withHeader("Host", "localhost:1124").withBody("ping")), is(nullValue()));
        assertThat(headerNames((HttpRequest) httpState.firstMatchingExpectation(direct).getHttpRequest()), containsInAnyOrder("x-api-version"));
    }

    @Test
    public void shouldKeepRecordedExpectationsOfTwoUpstreamsThatShareAPathDistinct() {
        HttpState httpState = newHttpState();
        for (String upstream : List.of("orders.internal:8080", "billing.internal:8080")) {
            HttpRequest recorded = request("/v1/status").withMethod("GET").withHeader("Host", upstream).withHeader("Proxy-Connection", "Keep-Alive");
            HttpResponse upstreamResponse = response(upstream).withStatusCode(200);
            httpState.log(new LogEntry().setType(FORWARDED_REQUEST).setHttpRequest(recorded).setHttpResponse(upstreamResponse).setExpectation(recorded, upstreamResponse));
        }

        Expectation[] recorded = expectationSerializer.deserializeArray(handle(httpState, retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS)).getBodyAsString(), true);

        assertThat(recorded.length, is(2));
        assertThat(headerNames((HttpRequest) recorded[0].getHttpRequest()), containsInAnyOrder("host"));
        assertThat(((HttpRequest) recorded[0].getHttpRequest()).getFirstHeader("Host"), is("orders.internal:8080"));
        assertThat(((HttpRequest) recorded[1].getHttpRequest()).getFirstHeader("Host"), is("billing.internal:8080"));
    }

    @Test
    public void shouldPromoteConsolidatedRecordingToAMockThatMatchesWithoutProxyConnection() {
        HttpState httpState = newHttpState();
        recordForwardedCall(httpState, request("/consolidated").withMethod("GET")
            .withHeader("Proxy-Connection", "Keep-Alive")
            .withHeader("TE", "trailers")
            .withHeader("X-Tenant", "blue"));

        handle(httpState, request("/mockserver/recordings/promote").withMethod("PUT"));

        Expectation promoted = httpState.firstMatchingExpectation(request("/consolidated").withMethod("GET").withHeader("X-Tenant", "blue"));
        assertThat(promoted, is(notNullValue()));
        assertThat(headerNames((HttpRequest) promoted.getHttpRequest()), containsInAnyOrder("x-tenant"));
    }

    @Test
    public void shouldNotChangeARecordingThatCarriesNoTransportHeaders() {
        HttpState httpState = newHttpState();
        recordForwardedCall(httpState, request("/clean").withMethod("GET").withHeader("X-Api-Version", "2"));

        HttpResponse response = handle(httpState, retrieveRequest(RetrieveType.RECORDED_EXPECTATIONS));

        Expectation[] recorded = expectationSerializer.deserializeArray(response.getBodyAsString(), true);
        assertThat(recorded.length, is(1));
        assertThat(headerNames((HttpRequest) recorded[0].getHttpRequest()), containsInAnyOrder("x-api-version"));
    }
}

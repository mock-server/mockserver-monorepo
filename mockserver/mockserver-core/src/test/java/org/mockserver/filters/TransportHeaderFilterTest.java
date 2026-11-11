package org.mockserver.filters;

import org.junit.Test;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class TransportHeaderFilterTest {

    private static List<String> headerNames(HttpRequest request) {
        return request.getHeaderList().stream()
            .map(header -> header.getName().getValue().toLowerCase(Locale.ROOT))
            .collect(Collectors.toList());
    }

    @Test
    public void shouldTreatHopByHopProxyAndTransportHeadersAsTransportHeadersInAnyCase() {
        for (String name : List.of("Connection", "KEEP-ALIVE", "te", "Trailer", "Transfer-Encoding", "Upgrade",
            "Content-Length", "Proxy-Connection", "proxy-authorization", "Proxy-Authenticate", "X-Forwarded-By")) {
            assertThat(name, TransportHeaderFilter.isTransportHeader(name), is(true));
        }
    }

    @Test
    public void shouldNotTreatRequestHeadersAsTransportHeaders() {
        for (String name : List.of("Host", "Content-Type", "Accept", "Authorization", "X-Api-Version", "Cookie", "Hostname", "X-Proxy-Id")) {
            assertThat(name, TransportHeaderFilter.isTransportHeader(name), is(false));
        }
        assertThat(TransportHeaderFilter.isTransportHeader(null), is(false));
    }

    @Test
    public void shouldStripTransportHeadersAndHeadersTheConnectionHeaderNominates() {
        HttpRequest recorded = request("/x")
            .withHeader("Host", "upstream:1134")
            .withHeader("Proxy-Connection", "Keep-Alive")
            .withHeader("Connection", "keep-alive, X-Per-Hop")
            .withHeader("X-Per-Hop", "1")
            .withHeader("content-length", "0")
            .withHeader("Content-Type", "application/json")
            .withHeader("X-Api-Version", "2");

        HttpRequest stripped = TransportHeaderFilter.withoutTransportHeaders(recorded);

        assertThat(headerNames(stripped), contains("host", "content-type", "x-api-version"));
        assertThat(stripped.getPath().getValue(), is("/x"));
        assertThat("the recorded request is not modified", headerNames(recorded), hasSize(7));
    }

    @Test
    public void shouldAlsoStripHostWhenAsked() {
        HttpRequest recorded = request("/x").withHeader("Host", "upstream:1134").withHeader("X-Api-Version", "2");

        assertThat(headerNames(TransportHeaderFilter.withoutTransportHeaders(recorded, true)), contains("x-api-version"));
        assertThat(TransportHeaderFilter.withoutTransportHeaders(recorded), sameInstance(recorded));
        Expectation promoted = TransportHeaderFilter.withoutTransportHeaders(new Expectation(recorded).thenRespond(response("body")), true);
        assertThat(headerNames((HttpRequest) promoted.getHttpRequest()), contains("x-api-version"));
    }

    @Test
    public void shouldReturnTheSameRequestWhenNothingNeedsStripping() {
        HttpRequest clean = request("/x").withHeader("X-Api-Version", "2");
        assertThat(TransportHeaderFilter.withoutTransportHeaders(clean), sameInstance(clean));
        HttpRequest noHeaders = request("/x");
        assertThat(TransportHeaderFilter.withoutTransportHeaders(noHeaders), sameInstance(noHeaders));
        assertThat(TransportHeaderFilter.withoutTransportHeaders((HttpRequest) null), nullValue());
    }

    @Test
    public void shouldLeaveNoHeadersWhenEveryHeaderIsATransportHeader() {
        HttpRequest stripped = TransportHeaderFilter.withoutTransportHeaders(request("/x").withHeader("Content-Length", "0").withHeader("Connection", "close"));
        assertThat(stripped.getHeaders(), nullValue());
        assertThat(headerNames(stripped), empty());
    }

    @Test
    public void shouldCopyAnExpectationKeepingItsIdAndResponse() {
        Expectation recorded = new Expectation(request("/x").withHeader("Proxy-Connection", "Keep-Alive").withHeader("X-Api-Version", "2"))
            .withId("recorded-id")
            .thenRespond(response("body"));

        Expectation stripped = TransportHeaderFilter.withoutTransportHeaders(recorded);

        assertThat(stripped.getId(), is("recorded-id"));
        assertThat(stripped.getHttpResponse().getBodyAsString(), is("body"));
        assertThat(headerNames((HttpRequest) stripped.getHttpRequest()), contains("x-api-version"));
        assertThat("the recorded expectation is not modified", headerNames((HttpRequest) recorded.getHttpRequest()), hasSize(2));
    }

    @Test
    public void shouldReturnTheSameExpectationWhenNothingNeedsStripping() {
        Expectation clean = new Expectation(request("/x").withHeader("X-Api-Version", "2")).thenRespond(response("body"));
        assertThat(TransportHeaderFilter.withoutTransportHeaders(clean), sameInstance(clean));
        assertThat(TransportHeaderFilter.withoutTransportHeaders((Expectation) null), nullValue());
    }
}

package org.mockserver.mock;

import org.junit.Before;
import org.junit.Test;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;

import java.util.List;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Coverage for {@link RequestMatchers#retrieveExpectationsMatchingRequest(org.mockserver.model.RequestDefinition)},
 * the <em>forward</em>-matching lookup used by drift analysis ("does each expectation
 * match this concrete request?").
 *
 * <p>These tests pin the distinction from {@link RequestMatchers#retrieveActiveExpectations}
 * (filter/reverse matching), which silently broke drift detection: drift analysis was
 * calling the reverse-matching method with a <em>concrete</em> incoming request, and the
 * concrete request's headers (content-type, accept, …) became required match criteria that
 * a bare {@code {method, path}} stub does not carry — so it matched nothing and no drift was
 * ever recorded. A unit test at this layer would have caught that, where the existing
 * isolated DriftAnalyzer tests (which call {@code analyse()} directly) could not.
 */
public class RequestMatchersForwardMatchingTest {

    private RequestMatchers requestMatchers;

    @Before
    public void setup() {
        requestMatchers = new RequestMatchers(
            configuration(), new MockServerLogger(), mock(Scheduler.class), mock(WebSocketClientRegistry.class));
    }

    @Test
    public void forwardMatchingReturnsBareStubForHeaderBearingRequest() {
        // a bare stub carrying no header criteria
        requestMatchers.add(new Expectation(request().withMethod("GET").withPath("/api/users"))
            .thenRespond(response().withStatusCode(200)), API);

        // a concrete incoming request as the proxy/drift path sees it — with headers the stub lacks
        HttpRequest incoming = request().withMethod("GET").withPath("/api/users")
            .withHeader("content-type", "application/json")
            .withHeader("accept", "*/*")
            .withHeader("host", "example.com");

        List<Expectation> matched = requestMatchers.retrieveExpectationsMatchingRequest(incoming);

        // forward matching: the stub matches the request, regardless of the request's extra headers
        assertThat(matched, hasSize(1));
    }

    @Test
    public void reverseFilterMatchingMissesHeaderBearingRequest_documentsTheBug() {
        // same bare stub
        requestMatchers.add(new Expectation(request().withMethod("GET").withPath("/api/users"))
            .thenRespond(response().withStatusCode(200)), API);

        HttpRequest incoming = request().withMethod("GET").withPath("/api/users")
            .withHeader("content-type", "application/json")
            .withHeader("accept", "*/*");

        // retrieveActiveExpectations treats its argument as a FILTER and reverse-matches it
        // against each expectation — the concrete request's headers become required criteria
        // the bare stub does not satisfy, so it returns nothing. This is exactly why drift
        // analysis recorded nothing when it (wrongly) used this method.
        List<Expectation> filterMatched = requestMatchers.retrieveActiveExpectations(incoming);

        assertThat(filterMatched, is(empty()));
    }

    @Test
    public void forwardMatchingReturnsAllMatchingExpectationsNotJustHighestPriority() {
        // a high-priority forward-style stub and a low-priority baseline response stub for the
        // same path — drift needs BOTH returned so the real (forwarded) response can be diffed
        // against the lower-priority baseline.
        requestMatchers.add(new Expectation(request().withMethod("GET").withPath("/orders"))
            .withPriority(10)
            .thenRespond(response().withStatusCode(502)), API);
        requestMatchers.add(new Expectation(request().withMethod("GET").withPath("/orders"))
            .withPriority(0)
            .thenRespond(response().withStatusCode(200)), API);

        HttpRequest incoming = request().withMethod("GET").withPath("/orders")
            .withHeader("content-type", "application/json");

        List<Expectation> matched = requestMatchers.retrieveExpectationsMatchingRequest(incoming);

        assertThat(matched, hasSize(2));
    }

    @Test
    public void forwardMatchingExcludesNonMatchingExpectations() {
        requestMatchers.add(new Expectation(request().withMethod("GET").withPath("/api/users"))
            .thenRespond(response().withStatusCode(200)), API);

        HttpRequest incoming = request().withMethod("GET").withPath("/different/path")
            .withHeader("content-type", "application/json");

        assertThat(requestMatchers.retrieveExpectationsMatchingRequest(incoming), is(empty()));
    }

    @Test
    public void forwardMatchingNullRequestReturnsEmpty() {
        requestMatchers.add(new Expectation(request().withMethod("GET").withPath("/api/users"))
            .thenRespond(response().withStatusCode(200)), API);

        assertThat(requestMatchers.retrieveExpectationsMatchingRequest(null), is(empty()));
    }

    @Test
    public void forwardMatchingWritesNoMatchResultEntriesAtInfo() {
        Configuration configuration = configuration().logLevel("INFO").maxLoggedMatchFailures(1);
        RequestMatchersUnmatchedDiagnosticsTest.CapturingLogger logger = new RequestMatchersUnmatchedDiagnosticsTest.CapturingLogger(configuration);
        RequestMatchers matchers = new RequestMatchers(configuration, logger, mock(Scheduler.class), mock(WebSocketClientRegistry.class));
        for (int i = 0; i < 5; i++) {
            matchers.add(new Expectation(request().withMethod("GET").withPath("/other/" + i)).thenRespond(response().withStatusCode(200)), API);
        }
        matchers.add(new Expectation(request().withMethod("GET").withPath("/api/users")).thenRespond(response().withStatusCode(200)), API);

        logger.rendered.clear();

        List<Expectation> matched = matchers.retrieveExpectationsMatchingRequest(request().withMethod("GET").withPath("/api/users"));

        assertThat(matched, hasSize(1));
        assertThat(logger.rendered, is(empty()));
    }
}

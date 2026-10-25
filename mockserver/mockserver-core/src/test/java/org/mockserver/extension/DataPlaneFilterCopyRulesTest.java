package org.mockserver.extension;

import org.junit.Test;
import org.mockserver.model.Body;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.sameInstance;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertFailed;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertProceeded;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertResponded;
import static org.mockserver.extension.DataPlaneExtensionTestKit.filter;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The filter chain applies the deep-copy rules: a filter gets a deep copy, what it continues with or answers is taken
 * as a fresh copy with the transport's fields re-stamped, and a result equal to its input leaves the input in place.
 */
public class DataPlaneFilterCopyRulesTest {

    private static HttpRequest received() {
        return request().withMethod("GET").withPath("/a").withHeader("x-original", "yes")
            .withSecure(true).withKeepAlive(true).withStreamId(7).withClientIp("10.0.0.1").withRemoteAddress("10.0.0.1:5000")
            .withBody("received body");
    }

    static class Named implements RequestFilter {
        private final String name;
        private final DataPlaneHooksTest.FilterBody body;

        Named(String name, DataPlaneHooksTest.FilterBody body) {
            this.name = name;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            return body.filter(request, context);
        }
    }

    @Test
    public void shouldGiveEachFilterADeepCopy() {
        HttpRequest received = received();
        AtomicReference<HttpRequest> seen = new AtomicReference<>();

        DataPlaneOutcome outcome = filter(received, new Named("edits", (request, context) -> {
            seen.set(request);
            request.withHeader("x-added", "in place");
            return FilterResult.proceed();
        }));

        assertProceeded(outcome);
        assertThat(seen.get(), not(sameInstance(received)));
        assertThat(seen.get().getHeaders(), not(sameInstance(received.getHeaders())));
        assertThat(received.containsHeader("x-added"), is(false));
        assertThat(outcome.request(), sameInstance(received));
        assertThat(outcome.changedBy(), is(empty()));
    }

    @Test
    public void shouldContinueWithTheInputWhenTheResultEqualsIt() {
        HttpRequest received = received();

        DataPlaneOutcome outcome = filter(received, new Named("identity", (request, context) -> FilterResult.proceedWith(request)));

        assertProceeded(outcome);
        assertThat(outcome.request(), sameInstance(received));
        assertThat(outcome.changedBy(), is(empty()));
    }

    @Test
    public void shouldTakeAChangedResultAsAFreshCopyWithTheTransportFieldsRestamped() {
        HttpRequest received = received();
        AtomicReference<HttpRequest> returned = new AtomicReference<>();

        DataPlaneOutcome outcome = filter(received, new Named("changes", (request, context) -> {
            request.withHeader("x-added", "yes").withSecure(false).withStreamId(99).withClientIp("6.6.6.6").withKeepAlive(false);
            returned.set(request);
            return FilterResult.proceedWith(request);
        }));

        assertProceeded(outcome);
        HttpRequest filtered = outcome.request();
        assertThat(filtered, not(sameInstance(returned.get())));
        assertThat(filtered.containsHeader("x-added"), is(true));
        assertThat(filtered.isSecure(), is(true));
        assertThat(filtered.getStreamId(), is(7));
        assertThat(filtered.isKeepAlive(), is(true));
        assertThat(filtered.getClientIp(), is("10.0.0.1"));
        assertThat(outcome.changedBy(), contains("changes"));
        // an edit the extension makes after returning reaches nothing the server holds
        returned.get().withHeader("x-late", "edit");
        assertThat(filtered.containsHeader("x-late"), is(false));
    }

    @Test
    public void shouldTakeAFiltersAnswerAsAFreshCopy() {
        HttpResponse answer = response().withStatusCode(418).withBody("teapot");

        DataPlaneOutcome outcome = filter(received(), new Named("answers", (request, context) -> FilterResult.respond(answer)));

        assertResponded(outcome, 418);
        assertThat(outcome.response(), not(sameInstance(answer)));
        assertThat(outcome.response(), is(answer));
    }

    @Test
    public void shouldRefuseAnInterimResponseAsAFiltersAnswer() {
        DataPlaneOutcome outcome = filter(received(), new Named("interim", (request, context) -> FilterResult.respond(response().withStatusCode(103))));

        assertFailed(outcome, 500, DataPlaneOutcome.CONTRACT_VIOLATION);
        assertThat(outcome.extensionName(), is("interim"));
    }

    @Test
    public void shouldFailAFilterWhoseResultCannotBeCopied() {
        DataPlaneOutcome outcome = filter(received(), new Named("uncopyable", (request, context) -> FilterResult.proceedWith(request.withBody(new UncopyableBody()))));

        assertFailed(outcome, 500, DataPlaneOutcome.THREW);
        assertThat(outcome.extensionName(), is("uncopyable"));
    }

    @Test
    public void shouldContinueAfterAFailOpenFilterWhoseResultCannotBeCopied() {
        HttpRequest received = received();
        RequestFilter failsOpen = new Named("uncopyable", (request, context) -> FilterResult.proceedWith(request.withBody(new UncopyableBody()))) {
            @Override
            public boolean failOpen() {
                return true;
            }
        };

        DataPlaneOutcome outcome = filter(received, failsOpen);

        assertProceeded(outcome);
        assertThat(outcome.request(), sameInstance(received));
    }

    /**
     * A custom body that does not override {@link Body#copy()}.
     */
    static class UncopyableBody extends Body<String> {
        UncopyableBody() {
            super(Type.STRING);
        }

        @Override
        public String getValue() {
            return "uncopyable";
        }
    }
}

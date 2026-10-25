package org.mockserver.extension;

import org.junit.Test;
import org.mockserver.model.HttpRequest;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.extension.DataPlaneExtensionTestKit.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

public class DataPlaneExtensionTestKitTest {

    @Test
    public void shouldRunFiltersInServerOrderAndStopAtTheFirstRespond() {
        List<String> order = new CopyOnWriteArrayList<>();
        DataPlaneOutcome outcome = filter(request().withPath("/a"),
            new DataPlaneHooksTest.Filter("z-last", 9, false, (request, context) -> {
                order.add("z-last");
                return FilterResult.proceed();
            }),
            new DataPlaneHooksTest.Filter("b", 1, false, (request, context) -> {
                order.add("b");
                return FilterResult.respond(response().withStatusCode(401));
            }),
            new DataPlaneHooksTest.Filter("a", 1, false, (request, context) -> {
                order.add("a");
                return FilterResult.proceedWith(request.withHeader("x-a", "1"));
            }));

        assertResponded(outcome, 401);
        assertThat(order, contains("a", "b"));
        assertThat(outcome.request().getFirstHeader("x-a"), is("1"));
    }

    @Test
    public void shouldRunTransformersInReverseOrder() {
        List<String> order = new CopyOnWriteArrayList<>();
        DataPlaneOutcome outcome = transform(request(), response().withStatusCode(200),
            new DataPlaneHooksTest.Transformer("a", 1, false, (request, response, context) -> {
                order.add("a");
                return response.withStatusCode(202);
            }),
            new DataPlaneHooksTest.Transformer("b", 2, false, (request, response, context) -> {
                order.add("b");
                return response;
            }));

        assertTransformed(outcome, 202);
        assertThat(order, contains("b", "a"));
    }

    @Test
    public void shouldApplyTheConfiguredBudget() {
        DataPlaneOutcome outcome = new DataPlaneExtensionTestKit(configuration().extensionDataPlaneTimeoutMillis(50L))
            .filter(request(), filterContext().build(), new DataPlaneHooksTest.Filter("slow", 0, false, (request, context) -> {
                Thread.sleep(10_000);
                return FilterResult.proceed();
            }));

        assertFailed(outcome, 500, DataPlaneOutcome.TIMEOUT);
    }

    @Test
    public void shouldReportChangedTransportFields() {
        HttpRequest original = request().withPath("/a").withSecure(true).withKeepAlive(true).withStreamId(3);

        assertTransportFieldsUnchanged(original, original.clone().withPath("/b"));
        try {
            assertTransportFieldsUnchanged(original, original.clone().withSecure(false).withStreamId(5));
            throw new IllegalStateException("expected an AssertionError");
        } catch (AssertionError expected) {
            assertThat(expected.getMessage(), allOf(containsString("secure"), containsString("streamId")));
        }
    }
}

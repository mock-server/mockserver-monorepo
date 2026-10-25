package org.mockserver.extension;

import org.junit.After;
import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.metrics.MetricsLock;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertFailed;
import static org.mockserver.extension.DataPlaneExtensionTestKit.filterContext;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The data-plane hook counters reach Prometheus. Runs in the sequential phase, because it resets the global metrics.
 */
public class DataPlaneHooksMetricsTest {

    @ClassRule
    public static final MetricsLock metricsLock = new MetricsLock();

    private final Configuration configuration = configuration().logLevel(Level.WARN).metricsEnabled(true);
    private final MockServerLogger mockServerLogger = new MockServerLogger(configuration, DataPlaneHooksMetricsTest.class);
    private DataPlaneHooks hooks;
    private Scheduler scheduler;

    @Before
    public void enableMetrics() {
        Metrics.resetAdditionalMetricsForTesting();
        new Metrics(configuration);
    }

    @After
    public void resetMetrics() {
        if (hooks != null) {
            hooks.stop();
        }
        if (scheduler != null) {
            scheduler.shutdown();
        }
        Metrics.resetAdditionalMetricsForTesting();
    }

    private DataPlaneHooks hooks(int maxInFlight, DataPlaneHooksTest.Filter filter) {
        hooks = new DataPlaneHooks(List.of(new DataPlaneHooks.Hook(new LoadedExtension(filter, filter.name(), filter.priority(), "test"), ExtensionHook.REQUEST_FILTER, filter.failOpen())), List.of(), maxInFlight, 60_000, mockServerLogger);
        return hooks;
    }

    private static HttpRequest aRequest() {
        return request().withPath("/metrics");
    }

    @Test
    public void shouldCountAFailureByExtensionHookAndType() {
        DataPlaneHooks hooks = hooks(2, new DataPlaneHooksTest.Filter("metered", 0, false, (request, context) -> {
            throw new IllegalStateException("boom");
        }));

        assertFailed(hooks.filterAndWait(aRequest(), filterContext().build(), null), 500, DataPlaneOutcome.THREW);

        assertThat(Metrics.getExtensionDataPlaneFailuresCount("metered", "requestFilter", "threw"), is(1L));
        assertThat(Metrics.getExtensionDataPlaneFailuresCount("metered", "requestFilter", "timeout"), is(0L));
    }

    @Test
    public void shouldCountADroppedWriteByReason() {
        DataPlaneHooks hooks = hooks(1, new DataPlaneHooksTest.Filter("any", 0, false, (request, context) -> FilterResult.proceed()));

        hooks.droppedWrite(DataPlaneHooks.DROPPED_DUPLICATE, aRequest(), response().withStatusCode(200));

        assertThat(Metrics.getExtensionDataPlaneDroppedWritesCount(DataPlaneHooks.DROPPED_DUPLICATE), is(1L));
        assertThat(Metrics.getExtensionDataPlaneDroppedWritesCount(DataPlaneHooks.DROPPED_LATE_HOOK_RESULT), is(0L));
    }

    @Test
    public void shouldCountARefusalAtTheCapAsTheExtensionDataPlaneOverloadReason() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DataPlaneHooks hooks = hooks(1, new DataPlaneHooksTest.Filter("holding", 0, false, (request, context) -> {
            entered.countDown();
            release.await();
            return FilterResult.proceed();
        }));
        scheduler = new Scheduler(configuration, mockServerLogger);
        CompletableFuture<DataPlaneOutcome> first = new CompletableFuture<>();
        hooks.filter(aRequest(), filterContext().build(), scheduler, first::complete);
        assertThat(entered.await(30, TimeUnit.SECONDS), is(true));

        assertFailed(hooks.filterAndWait(aRequest(), filterContext().build(), scheduler), 503, DataPlaneOutcome.AT_CAPACITY);

        assertThat(Metrics.getOverloadRejectionsCount("extension_dataplane"), is(1L));
        release.countDown();
        first.get(30, TimeUnit.SECONDS);
    }
}

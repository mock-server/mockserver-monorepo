package org.mockserver.inprocess;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.mock.Expectation;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The extension request filter (H1) in-process: {@code handle} waits for the filter chain for at most its budget on
 * the calling thread, {@code handleAsync} continues on the scheduler, and {@code close} answers a call still pending
 * with {@code 503} before it stops anything that call needs, leaving no extension pool thread behind.
 */
public class InProcessMockServerRequestFilterTest {

    // longer than any of these tests waits, so a 503 or a match can only come from what the test does
    private static final long LONG_BUDGET_MILLIS = 30_000;

    private InProcessMockServer mockServer;

    @After
    public void close() {
        if (mockServer != null) {
            mockServer.close();
        }
    }

    @Test
    public void shouldMatchTheRequestTheFilterLetsThroughOnTheCallingThread() {
        RecordingFilter filter = new RecordingFilter(request -> FilterResult.proceedWith(request.withHeader("x-filtered", "yes")));
        mockServer = new InProcessMockServer(withFilter(filter, LONG_BUDGET_MILLIS));
        mockServer.upsert(new Expectation(request("/filtered").withHeader("x-filtered", "yes")).thenRespond(response("matched the filtered request")));

        HttpResponse response = mockServer.handle(request("/filtered"));

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("matched the filtered request"));
        assertThat(filter.threads, everyItem(startsWith("MockServer-ext-dataplane-")));
    }

    @Test
    public void shouldAnswer500WhenTheFilterOutlastsItsBudget() {
        mockServer = new InProcessMockServer(withFilter(new RecordingFilter(request -> {
            Thread.sleep(5_000);
            return FilterResult.proceed();
        }), 200L));

        long start = System.nanoTime();
        HttpResponse response = mockServer.handle(request("/slow"));

        assertThat(response.getStatusCode(), is(500));
        assertThat(response.getBodyAsString(), containsString("extension in-process-filter requestFilter failed (timeout)"));
        assertThat("answered at the budget, not when the filter returned", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(3_000L));
    }

    @Test
    public void shouldContinueAHandleAsyncCallAfterTheFilter() throws Exception {
        RecordingFilter filter = new RecordingFilter(request -> FilterResult.proceedWith(request.withHeader("x-filtered", "yes")));
        mockServer = new InProcessMockServer(withFilter(filter, LONG_BUDGET_MILLIS));
        mockServer.upsert(new Expectation(request("/filtered-async").withHeader("x-filtered", "yes")).thenRespond(response("matched asynchronously")));

        HttpResponse response = mockServer.handleAsync(request("/filtered-async")).get(10, TimeUnit.SECONDS);

        assertThat(response.getStatusCode(), is(200));
        assertThat(response.getBodyAsString(), is("matched asynchronously"));
    }

    @Test
    public void shouldAnswerAHandleCallWhoseFilterIsRunningWith503WhenClosed() throws Exception {
        BlockingFilter filter = new BlockingFilter();
        mockServer = new InProcessMockServer(withFilter(filter, LONG_BUDGET_MILLIS));
        CompletableFuture<HttpResponse> pending = CompletableFuture.supplyAsync(() -> mockServer.handle(request("/pending")));
        assertThat("the filter was called", filter.entered.await(10, TimeUnit.SECONDS), is(true));

        HttpResponse response = answeredBeforeTheSchedulerStops(mockServer, pending);

        assertStopping(response);
        assertNoPoolThreadRemains(filter.poolPrefix());
    }

    @Test
    public void shouldAnswerAHandleAsyncCallWhoseFilterIsRunningWith503WhenClosed() throws Exception {
        BlockingFilter filter = new BlockingFilter();
        mockServer = new InProcessMockServer(withFilter(filter, LONG_BUDGET_MILLIS));
        CompletableFuture<HttpResponse> pending = mockServer.handleAsync(request("/pending-async"));
        assertThat("the filter was called", filter.entered.await(10, TimeUnit.SECONDS), is(true));

        HttpResponse response = answeredBeforeTheSchedulerStops(mockServer, pending);

        assertStopping(response);
        assertNoPoolThreadRemains(filter.poolPrefix());
    }

    /**
     * A stopped scheduler can neither time the budget nor run the continuation: it silently drops tasks, so the call
     * must be answered rather than left waiting for a task that never runs.
     */
    @Test
    public void shouldAnswer503WhenTheSchedulerHasAlreadyStopped() throws Exception {
        mockServer = new InProcessMockServer(withFilter(new RecordingFilter(request -> FilterResult.proceed()), LONG_BUDGET_MILLIS));
        mockServer.getHttpState().getScheduler().shutdown();

        HttpResponse async = mockServer.handleAsync(request("/after-the-scheduler-stopped")).get(10, TimeUnit.SECONDS);
        HttpResponse sync = mockServer.handle(request("/after-the-scheduler-stopped"));

        assertThat(async.getStatusCode(), is(503));
        assertThat(async.getBodyAsString(), is("MockServer is stopping"));
        assertThat(sync.getStatusCode(), is(503));
        assertThat(sync.getBodyAsString(), is("MockServer is stopping"));
    }

    @Test
    public void shouldStopTheDataPlaneHooksFirstWhenTheStateStops() {
        mockServer = new InProcessMockServer(withFilter(new RecordingFilter(request -> FilterResult.proceed()), LONG_BUDGET_MILLIS));

        mockServer.getHttpState().stop();

        assertThat(mockServer.getHttpState().dataPlaneHooks().isStopped(), is(true));
    }

    /**
     * Closes the server while holding off its scheduler's shutdown, which is {@code synchronized} on the scheduler, so
     * the call is answered only if the close answers it before it stops the scheduler: the order, not a time.
     */
    private static HttpResponse answeredBeforeTheSchedulerStops(InProcessMockServer mockServer, CompletableFuture<HttpResponse> pending) throws Exception {
        Scheduler scheduler = mockServer.getHttpState().getScheduler();
        CompletableFuture<Void> closed;
        HttpResponse response;
        synchronized (scheduler) {
            closed = CompletableFuture.runAsync(mockServer::close);
            try {
                response = pending.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException notAnswered) {
                throw new AssertionError("not answered before the scheduler stopped", notAnswered);
            }
        }
        closed.get(10, TimeUnit.SECONDS);
        return response;
    }

    private static void assertStopping(HttpResponse response) {
        assertThat(response.getStatusCode(), is(503));
        assertThat(response.getBodyAsString(), is("MockServer is stopping"));
    }

    static void assertNoPoolThreadRemains(String prefix) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        long remaining;
        do {
            remaining = Thread.getAllStackTraces().keySet().stream().filter(thread -> thread.isAlive() && thread.getName().startsWith(prefix)).count();
            if (remaining == 0) {
                return;
            }
            TimeUnit.MILLISECONDS.sleep(50);
        } while (System.nanoTime() < deadline);
        assertThat("threads named " + prefix + "* still alive", remaining, is(0L));
    }

    private static Configuration withFilter(RequestFilter filter, long budgetMillis) {
        return configuration()
            .extensionDataPlaneTimeoutMillis(budgetMillis)
            .extensions(() -> filter);
    }

    interface Script {
        FilterResult filter(HttpRequest request) throws Exception;
    }

    static class RecordingFilter implements RequestFilter {
        final List<String> threads = new CopyOnWriteArrayList<>();
        private final Script script;

        RecordingFilter(Script script) {
            this.script = script;
        }

        @Override
        public String name() {
            return "in-process-filter";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            threads.add(Thread.currentThread().getName());
            return script.filter(request);
        }
    }

    /**
     * Blocks, interruptibly, until it is stopped.
     */
    static class BlockingFilter implements RequestFilter {
        final CountDownLatch entered = new CountDownLatch(1);
        volatile String threadName;

        @Override
        public String name() {
            return "in-process-blocking-filter";
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            threadName = Thread.currentThread().getName();
            entered.countDown();
            new CountDownLatch(1).await(LONG_BUDGET_MILLIS, TimeUnit.MILLISECONDS);
            return FilterResult.proceed();
        }

        String poolPrefix() {
            return threadName.substring(0, threadName.lastIndexOf('-') + 1);
        }
    }
}

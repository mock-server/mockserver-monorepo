package org.mockserver.mockservlet;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.extension.FilterContext;
import org.mockserver.extension.FilterResult;
import org.mockserver.extension.RequestFilter;
import org.mockserver.model.HttpRequest;
import org.mockserver.scheduler.Scheduler;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * The extension request filter (H1) in the WAR: the servlet thread waits for the filter chain for at most its budget,
 * and {@code destroy()} answers a request whose filter is still running with {@code 503} before it stops the
 * scheduler, leaving no extension pool thread behind.
 */
public class MockServerServletRequestFilterTest {

    // longer than any of these tests waits, so a 503 or a match can only come from what the test does
    private static final long LONG_BUDGET_MILLIS = 30_000;

    private MockServerServlet servlet;

    @After
    public void destroy() {
        if (servlet != null) {
            servlet.destroy();
        }
    }

    @Test
    public void shouldMatchTheRequestTheFilterLetsThroughOnTheServletThread() throws Exception {
        RecordingFilter filter = new RecordingFilter(request -> FilterResult.proceedWith(request.withHeader("x-filtered", "yes")));
        servlet = new MockServerServlet(withFilter(filter, LONG_BUDGET_MILLIS));
        MockHttpServletResponse created = serve("PUT", "/mockserver/expectation", "{\"httpRequest\": {\"path\": \"/filtered\", \"headers\": {\"x-filtered\": [\"yes\"]}}, \"httpResponse\": {\"body\": \"matched the filtered request\"}}");
        assertThat(created.getStatus(), is(201));

        MockHttpServletResponse response = serve("GET", "/filtered", null);

        assertThat(response.getStatus(), is(200));
        assertThat(response.getContentAsString(), is("matched the filtered request"));
        assertThat(filter.threads, everyItem(startsWith("MockServer-ext-dataplane-")));
    }

    @Test
    public void shouldAnswer500WhenTheFilterOutlastsItsBudget() throws Exception {
        servlet = new MockServerServlet(withFilter(new RecordingFilter(request -> {
            Thread.sleep(5_000);
            return FilterResult.proceed();
        }), 200L));

        long start = System.nanoTime();
        MockHttpServletResponse response = serve("GET", "/slow", null);

        assertThat(response.getStatus(), is(500));
        assertThat(response.getContentAsString(), containsString("extension war-filter requestFilter failed (timeout)"));
        assertThat("answered at the budget, not when the filter returned", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), lessThan(3_000L));
    }

    @Test
    public void shouldAnswerARequestWhoseFilterIsRunningWith503WhenDestroyed() throws Exception {
        BlockingFilter filter = new BlockingFilter();
        servlet = new MockServerServlet(withFilter(filter, LONG_BUDGET_MILLIS));
        CompletableFuture<MockHttpServletResponse> pending = CompletableFuture.supplyAsync(() -> serve("GET", "/pending", null));
        assertThat("the filter was called", filter.entered.await(10, TimeUnit.SECONDS), is(true));

        // holds off the scheduler's shutdown, which is synchronized on the scheduler, so the request is answered only
        // if destroy() answers it before it stops the scheduler: the order, not a time
        Scheduler scheduler = (Scheduler) ReflectionTestUtils.getField(servlet, "scheduler");
        CompletableFuture<Void> destroyed;
        MockHttpServletResponse response;
        synchronized (scheduler) {
            // destroy() is idempotent, so @After destroying it again is harmless
            destroyed = CompletableFuture.runAsync(servlet::destroy);
            try {
                response = pending.get(10, TimeUnit.SECONDS);
            } catch (TimeoutException notAnswered) {
                throw new AssertionError("not answered before the scheduler stopped", notAnswered);
            }
        }
        destroyed.get(10, TimeUnit.SECONDS);

        assertThat(response.getStatus(), is(503));
        assertThat(response.getContentAsString(), is("MockServer is stopping"));
        assertNoPoolThreadRemains(filter.poolPrefix());
    }

    @Test
    public void shouldRefuseAnUnauthenticatedRequestWithoutCallingTheFilter() throws Exception {
        List<String> filtered = new CopyOnWriteArrayList<>();
        servlet = new MockServerServlet(withFilter(new RecordingFilter(request -> {
            filtered.add(request.getPath().getValue());
            return FilterResult.respond(org.mockserver.model.HttpResponse.response().withStatusCode(202).withBody("answered by the filter"));
        }), LONG_BUDGET_MILLIS).dataPlaneAuthenticationRequired(true).dataPlaneBearerAuthenticationToken("filter-token"));

        assertThat(serve(new MockHttpServletRequest("GET", "/gated")).getStatus(), is(401));
        assertThat(filtered, is(empty()));

        MockHttpServletRequest authenticated = new MockHttpServletRequest("GET", "/gated");
        authenticated.addHeader("Authorization", "Bearer filter-token");
        MockHttpServletResponse admitted = serve(authenticated);
        assertThat(admitted.getStatus(), is(202));
        assertThat(admitted.getContentAsString(), is("answered by the filter"));
        assertThat(filtered, contains("/gated"));
    }

    private MockHttpServletResponse serve(String method, String path, String body) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        if (body != null) {
            request.setContent(body.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
        return serve(request);
    }

    private MockHttpServletResponse serve(MockHttpServletRequest request) {
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            servlet.service(request, response);
            return response;
        } catch (Exception e) {
            throw new AssertionError(e);
        }
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
            .attemptToProxyIfNoMatchingExpectation(false)
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
            return "war-filter";
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
            return "war-blocking-filter";
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

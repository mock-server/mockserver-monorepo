package org.mockserver.mock.action.http;

import io.netty.channel.ChannelHandlerContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.NettyHttpClient;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.ProxyPassMapping;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Concurrency regression test for the forwarding client's blocking proxy paths. The unmatched-proxy forward ({@code handleUnmatchedProxyForward}) and the
 * proxy-pass reverse-proxy route ({@code handleProxyPass}) each used to block the issuing scheduler
 * thread on {@code responseFuture.getHttpResponse().get(...)} for the whole upstream round trip. That
 * pins one bounded scheduler-pool thread per in-flight forward, so sustained proxy concurrency was
 * capped at the pool size {@code max(5, cores)} regardless of load — a throughput ceiling, not a
 * correctness bug.
 *
 * <p>The pool is fixed here to a known small size ({@link #POOL_SIZE}) and the upstream is a mock
 * whose response future completes after {@link #UPSTREAM_LATENCY_MS} on a <em>separate</em> executor
 * (never the scheduler pool). {@link #FORWARDS} forwards — comfortably above the pool size — are fired
 * concurrently and the peak number of simultaneously in-flight upstream calls is recorded.
 *
 * <p>With the blocking {@code get()} the peak can never exceed the pool size, because every issuing
 * thread parks until its own round trip finishes. With the async continuation the issuing thread is
 * released as soon as the request is sent, so all {@link #FORWARDS} overlap. The assertion —
 * {@code peak > 2 * POOL_SIZE} — therefore passes only on the fixed code and fails on the old blocking
 * code (where peak equals POOL_SIZE), which is exactly the negative control the unit requires.
 *
 * <p>Global state: builds its own {@link Configuration} and {@link Scheduler}; the mock upstream means
 * no real sockets and no {@code NettyHttpClient} event-loop group is created. No JVM-global statics are
 * mutated (drift detection off; SLO sampling and metrics are no-ops while disabled), so it runs in the
 * parallel Surefire phase.
 */
public class HttpActionHandlerForwardConcurrencyTest {

    private static final int POOL_SIZE = 4;
    private static final int FORWARDS = 24;
    private static final long UPSTREAM_LATENCY_MS = 500;

    private Configuration configuration;
    private Scheduler scheduler;
    private ScheduledExecutorService upstreamCompletionPool;
    private HttpActionHandler actionHandler;
    private ResponseWriter responseWriter;

    private final AtomicInteger inFlight = new AtomicInteger(0);
    private final AtomicInteger peakInFlight = new AtomicInteger(0);
    private CountDownLatch writes;

    @Before
    public void setupTestFixture() throws Exception {
        configuration = configuration()
            .actionHandlerThreadCount(POOL_SIZE)
            .driftDetectionEnabled(false)
            .proxyRemoteHost("upstream.example")
            .proxyRemotePort(8080);
        scheduler = new Scheduler(configuration, new MockServerLogger());

        HttpState httpState = mock(HttpState.class);
        when(httpState.getScheduler()).thenReturn(scheduler);
        when(httpState.getMockServerLogger()).thenReturn(new MockServerLogger());
        when(httpState.getUniqueLoopPreventionHeaderName()).thenReturn("x-forwarded-by");
        when(httpState.getUniqueLoopPreventionHeaderValue()).thenReturn("MockServer");

        actionHandler = new HttpActionHandler(configuration, null, httpState, null, null);

        // The mock upstream: record concurrent in-flight calls, then complete the response future after a
        // fixed latency on a SEPARATE pool so completion never runs on the scheduler pool under test.
        upstreamCompletionPool = Executors.newScheduledThreadPool(FORWARDS);
        NettyHttpClient httpClient = mock(NettyHttpClient.class);
        doAnswer(invocation -> slowUpstream()).when(httpClient).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class), anyLong());
        doAnswer(invocation -> slowUpstream()).when(httpClient).sendRequest(any(HttpRequest.class), any(InetSocketAddress.class));
        setField(actionHandler, "httpClient", httpClient);

        responseWriter = mock(ResponseWriter.class);
        doAnswer(invocation -> {
            writes.countDown();
            return null;
        }).when(responseWriter).writeResponse(any(HttpRequest.class), any(HttpResponse.class), org.mockito.ArgumentMatchers.anyBoolean());
    }

    @After
    public void stopTestFixture() {
        if (scheduler != null) {
            scheduler.shutdown();
        }
        if (upstreamCompletionPool != null) {
            upstreamCompletionPool.shutdownNow();
        }
    }

    private CompletableFuture<HttpResponse> slowUpstream() {
        int now = inFlight.incrementAndGet();
        peakInFlight.accumulateAndGet(now, Math::max);
        CompletableFuture<HttpResponse> future = new CompletableFuture<>();
        upstreamCompletionPool.schedule(() -> {
            inFlight.decrementAndGet();
            future.complete(response().withStatusCode(200).withBody("upstream-ok"));
        }, UPSTREAM_LATENCY_MS, TimeUnit.MILLISECONDS);
        return future;
    }

    @Test
    public void unmatchedProxyForwardShouldOverlapBeyondSchedulerPoolSize() throws Exception {
        writes = new CountDownLatch(FORWARDS);
        Method handleUnmatchedProxyForward = HttpActionHandler.class.getDeclaredMethod(
            "handleUnmatchedProxyForward", HttpRequest.class, ResponseWriter.class, ChannelHandlerContext.class, boolean.class, boolean.class, boolean.class);
        handleUnmatchedProxyForward.setAccessible(true);

        long start = System.nanoTime();
        for (int i = 0; i < FORWARDS; i++) {
            HttpRequest request = request("/proxy/" + i).withHeader("Host", "upstream.example");
            handleUnmatchedProxyForward.invoke(actionHandler, request, responseWriter, null, false, false, true);
        }
        boolean completed = writes.await(20, TimeUnit.SECONDS);
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[unit21] unmatched-proxy-forward: peakInFlight=" + peakInFlight.get()
            + " poolSize=" + POOL_SIZE + " forwards=" + FORWARDS + " wallMs=" + wallMs);
        assertThat("all forwards produced a response", completed, is(true));
        assertThat("forwards must overlap well beyond the scheduler pool size (was capped at the pool size by the blocking get)",
            peakInFlight.get(), greaterThan(2 * POOL_SIZE));
    }

    @Test
    public void proxyPassShouldOverlapBeyondSchedulerPoolSize() throws Exception {
        configuration.proxyPassMappings(Collections.singletonList(
            ProxyPassMapping.proxyPass("/", "http://upstream.example:8080")));
        writes = new CountDownLatch(FORWARDS);
        Method handleProxyPass = HttpActionHandler.class.getDeclaredMethod(
            "handleProxyPass", HttpRequest.class, ResponseWriter.class, boolean.class);
        handleProxyPass.setAccessible(true);

        long start = System.nanoTime();
        for (int i = 0; i < FORWARDS; i++) {
            HttpRequest request = request("/proxy/" + i);
            handleProxyPass.invoke(actionHandler, request, responseWriter, false);
        }
        boolean completed = writes.await(20, TimeUnit.SECONDS);
        long wallMs = (System.nanoTime() - start) / 1_000_000;

        System.out.println("[unit21] proxy-pass: peakInFlight=" + peakInFlight.get()
            + " poolSize=" + POOL_SIZE + " forwards=" + FORWARDS + " wallMs=" + wallMs);
        assertThat("all proxy-pass forwards produced a response", completed, is(true));
        assertThat("proxy-pass forwards must overlap well beyond the scheduler pool size (was capped at the pool size by the blocking get)",
            peakInFlight.get(), greaterThan(2 * POOL_SIZE));
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        Field field = HttpActionHandler.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}

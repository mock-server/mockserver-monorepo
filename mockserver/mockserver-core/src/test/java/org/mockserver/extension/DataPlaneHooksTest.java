package org.mockserver.extension;

import org.junit.After;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.Protocol;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertFailed;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertProceeded;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertResponded;
import static org.mockserver.extension.DataPlaneExtensionTestKit.assertTransformed;
import static org.mockserver.extension.DataPlaneExtensionTestKit.filterContext;
import static org.mockserver.extension.DataPlaneExtensionTestKit.transformContext;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * The data-plane hook executor: every failure row, the cap, loopback, interrupts, the once-guard, chain order, stop
 * and the start-up budget warnings. No server: chains run directly on {@link DataPlaneHooks}.
 */
// @ParallelStateGuardSuppress: HttpState.setPort is a thread-local, restored in a finally block
public class DataPlaneHooksTest {

    private static final long WAIT_SECONDS = 30;

    private final List<DataPlaneHooks> built = new ArrayList<>();
    private final List<Scheduler> schedulers = new ArrayList<>();
    private final CapturingLogger logger = new CapturingLogger();

    @After
    public void stopEverything() {
        built.forEach(DataPlaneHooks::stop);
        schedulers.forEach(Scheduler::shutdown);
    }

    interface FilterBody {
        FilterResult filter(HttpRequest request, FilterContext context) throws Exception;
    }

    interface TransformBody {
        HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception;
    }

    static class Filter implements RequestFilter {
        final String name;
        final int priority;
        final boolean failOpen;
        final FilterBody body;
        final AtomicInteger calls = new AtomicInteger();
        final List<String> threads = new CopyOnWriteArrayList<>();

        Filter(String name, int priority, boolean failOpen, FilterBody body) {
            this.name = name;
            this.priority = priority;
            this.failOpen = failOpen;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean failOpen() {
            return failOpen;
        }

        @Override
        public FilterResult filter(HttpRequest request, FilterContext context) throws Exception {
            calls.incrementAndGet();
            threads.add(Thread.currentThread().getName());
            return body.filter(request, context);
        }
    }

    static class Transformer implements ResponseTransformer {
        final String name;
        final int priority;
        final boolean failOpen;
        final TransformBody body;

        Transformer(String name, int priority, boolean failOpen, TransformBody body) {
            this.name = name;
            this.priority = priority;
            this.failOpen = failOpen;
            this.body = body;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public int priority() {
            return priority;
        }

        @Override
        public boolean failOpen() {
            return failOpen;
        }

        @Override
        public HttpResponse transform(HttpRequest request, HttpResponse response, TransformContext context) throws Exception {
            return body.transform(request, response, context);
        }
    }

    private static Filter filter(String name, FilterBody body) {
        return new Filter(name, 0, false, body);
    }

    private static Filter failOpenFilter(String name, FilterBody body) {
        return new Filter(name, 0, true, body);
    }

    private static DataPlaneHooks.Hook hook(Extension extension, ExtensionHook type, boolean failOpen) {
        return new DataPlaneHooks.Hook(new LoadedExtension(extension, extension.name(), extension.priority(), "test"), type, failOpen);
    }

    private DataPlaneHooks filters(int maxInFlight, long timeoutMillis, Filter... filters) {
        List<DataPlaneHooks.Hook> chain = new ArrayList<>();
        for (Filter filter : filters) {
            chain.add(hook(filter, ExtensionHook.REQUEST_FILTER, filter.failOpen));
        }
        DataPlaneHooks hooks = new DataPlaneHooks(chain, List.of(), maxInFlight, timeoutMillis, logger);
        built.add(hooks);
        return hooks;
    }

    private DataPlaneHooks transformers(int maxInFlight, long timeoutMillis, Transformer... transformers) {
        List<DataPlaneHooks.Hook> chain = new ArrayList<>();
        for (Transformer transformer : transformers) {
            chain.add(hook(transformer, ExtensionHook.RESPONSE_TRANSFORMER, transformer.failOpen));
        }
        DataPlaneHooks hooks = new DataPlaneHooks(List.of(), chain, maxInFlight, timeoutMillis, logger);
        built.add(hooks);
        return hooks;
    }

    private Scheduler scheduler() {
        Scheduler scheduler = new Scheduler(configuration(), logger);
        schedulers.add(scheduler);
        return scheduler;
    }

    private static HttpRequest aRequest() {
        return request().withMethod("GET").withPath("/some/path").withHeader("x-original", "yes");
    }

    private static FilterContext context() {
        return filterContext().build();
    }

    private static DataPlaneOutcome await(CompletableFuture<DataPlaneOutcome> outcome) throws Exception {
        return outcome.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    private static void awaitSlots(DataPlaneHooks hooks, int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (hooks.availableSlots() != expected && System.nanoTime() < deadline) {
            Thread.sleep(5);
        }
        assertThat(hooks.availableSlots(), is(expected));
    }

    // ---- results

    @Test
    public void shouldProceedWithTheRequestBeforeTheFilterWhenItProceeds() throws Exception {
        Filter editsInPlace = filter("edits", (request, context) -> {
            request.withHeader("x-added", "in-place");
            return FilterResult.proceed();
        });
        CompletableFuture<DataPlaneOutcome> outcome = new CompletableFuture<>();

        filters(4, 1000, editsInPlace).filter(aRequest(), context(), scheduler(), outcome::complete);

        DataPlaneOutcome result = await(outcome);
        assertProceeded(result);
        assertThat(result.request().getFirstHeader("x-added"), is(""));
        assertThat(result.changedBy(), is(empty()));
        assertThat(editsInPlace.threads.get(0), startsWith("MockServer-ext-dataplane-"));
    }

    @Test
    public void shouldProceedWithTheReturnedRequestAndRecordWhoChangedIt() throws Exception {
        Filter adds = filter("adds", (request, context) -> FilterResult.proceedWith(request.withHeader("x-added", "a")));
        Filter same = filter("same", (request, context) -> FilterResult.proceedWith(request));

        DataPlaneOutcome result = filters(4, 1000, adds, same).filterAndWait(aRequest(), context(), scheduler());

        assertProceeded(result);
        assertThat(result.request().getFirstHeader("x-added"), is("a"));
        assertThat(result.changedBy(), contains("adds"));
    }

    @Test
    public void shouldStopTheChainAtTheFirstRespond() throws Exception {
        Filter responds = filter("a-responds", (request, context) -> FilterResult.respond(response().withStatusCode(418)));
        Filter later = filter("b-later", (request, context) -> FilterResult.proceed());

        DataPlaneOutcome result = filters(4, 1000, responds, later).filterAndWait(aRequest(), context(), scheduler());

        assertResponded(result, 418);
        assertThat(result.extensionName(), is("a-responds"));
        assertThat(result.request().getFirstHeader("x-original"), is("yes"));
        assertThat(later.calls.get(), is(0));
    }

    // ---- failure rows: request filter

    @Test
    public void shouldAnswer500WhenAFilterThrowsAndNameOnlyTheExtensionAndFailureType() throws Exception {
        Filter throwing = filter("thrower", (request, context) -> {
            throw new IllegalStateException("secret detail");
        });

        DataPlaneOutcome result = filters(4, 1000, throwing).filterAndWait(aRequest(), context(), scheduler());

        assertFailed(result, 500, DataPlaneOutcome.THREW);
        String body = result.response().getBodyAsString();
        assertThat(body, containsString("thrower"));
        assertThat(body, containsString("threw"));
        assertThat(body, containsString(DataPlaneExtensionTestKit.CORRELATION_ID));
        assertThat(body, not(containsString("secret detail")));
        assertThat(result.request().getFirstHeader("x-original"), is("yes"));
        assertThat(logger.warnings(), hasItem(allOf(containsString("thrower"), containsString("threw"), containsString(DataPlaneExtensionTestKit.CORRELATION_ID))));
        assertThat(logger.throwables(), hasSize(1));
    }

    @Test
    public void shouldAnswer500WhenAFilterReturnsNull() throws Exception {
        DataPlaneOutcome result = filters(4, 1000, filter("nuller", (request, context) -> null)).filterAndWait(aRequest(), context(), scheduler());

        assertFailed(result, 500, DataPlaneOutcome.RETURNED_NULL);
    }

    @Test
    public void shouldAnswer500WhenAFilterTimesOutAndInterruptIt() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        Filter slow = filter("slow", (request, context) -> {
            entered.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return FilterResult.proceed();
        });
        DataPlaneHooks hooks = filters(4, 60_000, slow);
        CompletableFuture<DataPlaneOutcome> outcome = new CompletableFuture<>();
        DataPlaneCall call = startWithoutTimer(hooks, outcome);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        call.timeOut();

        assertFailed(await(outcome), 500, DataPlaneOutcome.TIMEOUT);
        assertThat(interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        assertThat(hooks.timeoutCount(), is(1L));
        awaitSlots(hooks, 4);
    }

    @Test
    public void shouldEndTheBudgetForACallerThatWaitsWithNoTimer() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        DataPlaneHooks hooks = filters(4, 200, filter("slow", (request, context) -> {
            release.await();
            return FilterResult.proceed();
        }));

        DataPlaneOutcome result = hooks.filterAndWait(aRequest(), context(), null);

        assertFailed(result, 500, DataPlaneOutcome.TIMEOUT);
        release.countDown();
    }

    @Test
    public void shouldTreatMovingTheRequestToTheControlPlaneAsAContractViolation() throws Exception {
        DataPlaneHooks hooks = filters(4, 1000, filter("mover", (request, context) -> FilterResult.proceedWith(request.withPath("/mockserver/expectation"))));

        assertFailed(hooks.filterAndWait(aRequest(), context(), scheduler()), 500, DataPlaneOutcome.CONTRACT_VIOLATION);
        assertFailed(filters(4, 1000, filter("mover", (request, context) -> FilterResult.proceedWith(request.withPath("/_mockserver_x"))))
            .filterAndWait(aRequest(), context(), scheduler()), 500, DataPlaneOutcome.CONTRACT_VIOLATION);
    }

    @Test
    public void shouldLetAnUnservedControlPlanePathThroughUnmoved() throws Exception {
        DataPlaneHooks hooks = filters(4, 1000, filter("keeps", (request, context) -> FilterResult.proceedWith(request.withHeader("x-seen", "1"))));

        DataPlaneOutcome result = hooks.filterAndWait(request().withPath("/mockserver/x"), context(), scheduler());

        assertProceeded(result);
        assertThat(result.request().getPath().getValue(), is("/mockserver/x"));
    }

    @Test
    public void shouldTreatChangingAGrpcPathOrMethodHeaderAsAContractViolation() throws Exception {
        HttpRequest grpc = request().withMethod("POST").withPath("/pkg.Svc/Call").withHeader("x-grpc-service", "pkg.Svc").withHeader("x-grpc-method", "Call");
        FilterContext grpcContext = filterContext().grpc(true).build();

        assertFailed(filters(4, 1000, filter("path", (request, context) -> FilterResult.proceedWith(request.withPath("/pkg.Svc/Other"))))
            .filterAndWait(grpc, grpcContext, scheduler()), 500, DataPlaneOutcome.CONTRACT_VIOLATION);
        assertFailed(filters(4, 1000, filter("method", (request, context) -> FilterResult.proceedWith(request.replaceHeader(new org.mockserver.model.Header("x-grpc-method", "Other")))))
            .filterAndWait(grpc, grpcContext, scheduler()), 500, DataPlaneOutcome.CONTRACT_VIOLATION);
        assertProceeded(filters(4, 1000, filter("header", (request, context) -> FilterResult.proceedWith(request.withHeader("x-tenant", "t"))))
            .filterAndWait(grpc, grpcContext, scheduler()));
    }

    @Test
    public void shouldContinueWithTheFiltersInputWhenAFailOpenFilterFails() throws Exception {
        Filter first = filter("a-first", (request, context) -> FilterResult.proceedWith(request.withHeader("x-first", "1")));
        Filter failing = failOpenFilter("b-failing", (request, context) -> {
            request.withHeader("x-partial", "edit");
            throw new IllegalStateException("boom");
        });
        Filter nulling = failOpenFilter("c-null", (request, context) -> null);
        Filter last = filter("d-last", (request, context) -> FilterResult.proceedWith(request.withHeader("x-last", "1")));
        DataPlaneHooks hooks = filters(4, 1000, first, failing, nulling, last);

        DataPlaneOutcome result = hooks.filterAndWait(aRequest(), context(), scheduler());

        assertProceeded(result);
        assertThat(result.request().getFirstHeader("x-first"), is("1"));
        assertThat(result.request().getFirstHeader("x-partial"), is(""));
        assertThat(result.request().getFirstHeader("x-last"), is("1"));
        assertThat(result.changedBy(), contains("a-first", "d-last"));
        assertThat(hooks.failureCount(), is(2L));
        assertThat(logger.warnings(), hasItem(allOf(containsString("b-failing"), containsString("fails open"))));
    }

    @Test
    public void shouldProceedWithTheFiltersInputWhenAFailOpenFilterTimesOut() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Filter first = filter("a-first", (request, context) -> FilterResult.proceedWith(request.withHeader("x-first", "1")));
        Filter slow = failOpenFilter("b-slow", (request, context) -> {
            entered.countDown();
            release.await();
            return FilterResult.respond(response().withStatusCode(200));
        });
        DataPlaneHooks hooks = filters(4, 60_000, first, slow);
        CompletableFuture<DataPlaneOutcome> outcome = new CompletableFuture<>();
        DataPlaneCall call = startWithoutTimer(hooks, outcome);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        call.timeOut();

        DataPlaneOutcome result = await(outcome);
        assertProceeded(result);
        assertThat(result.request().getFirstHeader("x-first"), is("1"));
        assertThat(result.changedBy(), contains("a-first"));
        assertThat(hooks.timeoutCount(), is(1L));
        release.countDown();
    }

    @Test
    public void shouldNotSkipAFailClosedFilterAfterAFailOpenOneTimesOut() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Filter slow = failOpenFilter("a-slow", (request, context) -> {
            entered.countDown();
            release.await();
            return FilterResult.proceed();
        });
        Filter guard = filter("b-guard", (request, context) -> FilterResult.proceed());
        CompletableFuture<DataPlaneOutcome> outcome = new CompletableFuture<>();
        DataPlaneCall call = startWithoutTimer(filters(4, 60_000, slow, guard), outcome);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        call.timeOut();

        DataPlaneOutcome result = await(outcome);
        assertFailed(result, 500, DataPlaneOutcome.TIMEOUT);
        assertThat(result.extensionName(), is("a-slow"));
        assertThat(guard.calls.get(), is(0));
        release.countDown();
    }

    @Test
    public void shouldTransformInDescendingOrderAndRecordWhoChangedTheResponse() throws Exception {
        List<String> order = new CopyOnWriteArrayList<>();
        Transformer low = new Transformer("low", 1, false, (request, response, context) -> {
            order.add("low");
            return response.withHeader("x-low", "1");
        });
        Transformer high = new Transformer("high", 2, false, (request, response, context) -> {
            order.add("high");
            return response;
        });
        // the registry hands over transformers already in descending order
        DataPlaneHooks hooks = transformers(4, 1000, high, low);

        DataPlaneOutcome result = hooks.transformAndWait(aRequest(), response().withStatusCode(200), transformContext(), scheduler());

        assertTransformed(result, 200);
        assertThat(order, contains("high", "low"));
        assertThat(result.response().getFirstHeader("x-low"), is("1"));
        assertThat(result.changedBy(), contains("low"));
    }

    @Test
    public void shouldAnswer500WhenATransformerThrowsReturnsNullOrTimesOut() throws Exception {
        assertFailed(transformers(4, 1000, new Transformer("thrower", 0, false, (request, response, context) -> {
            throw new IllegalStateException("boom");
        })).transformAndWait(aRequest(), response().withStatusCode(201), transformContext(), scheduler()), 500, DataPlaneOutcome.THREW);
        assertFailed(transformers(4, 1000, new Transformer("nuller", 0, false, (request, response, context) -> null))
            .transformAndWait(aRequest(), response().withStatusCode(201), transformContext(), scheduler()), 500, DataPlaneOutcome.RETURNED_NULL);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<DataPlaneOutcome> outcome = new CompletableFuture<>();
        transformers(4, 200, new Transformer("slow", 0, false, (request, response, context) -> {
            release.await();
            return response;
        })).transform(aRequest(), response().withStatusCode(201), transformContext(), scheduler(), outcome::complete);
        assertFailed(await(outcome), 500, DataPlaneOutcome.TIMEOUT);
        release.countDown();
    }

    @Test
    public void shouldTreatReplacingAStreamedBodyAsAContractViolation() throws Exception {
        TransformContext streamed = TransformContext.of(context(), true, null, false);
        DataPlaneHooks hooks = transformers(4, 1000, new Transformer("body", 0, false, (request, response, context) -> response.withBody("replaced")));

        assertFailed(hooks.transformAndWait(aRequest(), response().withStatusCode(200), streamed, scheduler()), 500, DataPlaneOutcome.CONTRACT_VIOLATION);
        assertTransformed(transformers(4, 1000, new Transformer("head", 0, false, (request, response, context) -> response.withHeader("x-head", "1")))
            .transformAndWait(aRequest(), response().withStatusCode(200), streamed, scheduler()), 200);
    }

    @Test
    public void shouldWriteTheTransformersInputWhenAFailOpenTransformerFails() throws Exception {
        Transformer first = new Transformer("first", 0, false, (request, response, context) -> response.withHeader("x-first", "1"));
        Transformer failing = new Transformer("failing", 0, true, (request, response, context) -> {
            response.withStatusCode(599);
            throw new IllegalStateException("boom");
        });

        DataPlaneOutcome result = transformers(4, 1000, first, failing).transformAndWait(aRequest(), response().withStatusCode(200), transformContext(), scheduler());

        assertTransformed(result, 200);
        assertThat(result.response().getFirstHeader("x-first"), is("1"));
    }

    @Test
    public void shouldActOnTheOutcomeWithTheCallersPortOnEveryCompletingThread() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        DataPlaneHooks hooks = filters(2, 60_000, filter("ports", (request, context) -> {
            if (request.getFirstHeader("x-block").equals("1")) {
                entered.countDown();
                release.await();
            }
            return FilterResult.proceed();
        }));
        List<Integer> ports = new CopyOnWriteArrayList<>();
        Integer before = HttpState.getPort();
        try {
            HttpState.setPort(1234);
            CompletableFuture<DataPlaneOutcome> onHookThread = new CompletableFuture<>();
            hooks.filter(aRequest(), context(), scheduler(), outcome -> {
                ports.add(HttpState.getPort());
                onHookThread.complete(outcome);
            });
            assertProceeded(await(onHookThread));

            HttpState.setPort(5678);
            CompletableFuture<DataPlaneOutcome> onTimerThread = new CompletableFuture<>();
            DataPlaneCall call = new DataPlaneCall(hooks, true, hooks.requestFilters, aRequest().withHeader("x-block", "1"), null, context(), null, outcome -> {
                ports.add(HttpState.getPort());
                onTimerThread.complete(outcome);
            }, true);
            call.start();
            assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
            AtomicReference<Integer> timerPortAfter = new AtomicReference<>();
            Thread timer = new Thread(() -> {
                HttpState.setPort(4321);
                call.timeOut();
                timerPortAfter.set(HttpState.getPort());
            });
            timer.start();
            timer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            assertFailed(await(onTimerThread), 500, DataPlaneOutcome.TIMEOUT);
            assertThat("the completing thread's own port is restored", timerPortAfter.get(), is(4321));
        } finally {
            release.countDown();
            HttpState.setPort(before);
        }

        assertThat(ports, contains(1234, 5678));
    }

    // ---- cap, loopback, stopping

    @Test
    public void shouldAnswer503AtTheCapAndCountTheOverloadReason() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Filter holding = filter("holding", (request, context) -> {
            entered.countDown();
            release.await();
            return FilterResult.proceed();
        });
        DataPlaneHooks hooks = filters(1, 10_000, holding);
        Scheduler scheduler = scheduler();
        CompletableFuture<DataPlaneOutcome> first = new CompletableFuture<>();
        hooks.filter(aRequest(), context(), scheduler, first::complete);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        CompletableFuture<DataPlaneOutcome> second = new CompletableFuture<>();
        hooks.filter(aRequest(), context(), scheduler, second::complete);

        DataPlaneOutcome refused = second.getNow(null);
        assertThat("answered at once, on the calling thread", refused, notNullValue());
        assertFailed(refused, 503, DataPlaneOutcome.AT_CAPACITY);
        assertThat(refused.response().getFirstHeader("Retry-After"), is("1"));
        assertThat(scheduler.getOverloadRejectionCount(Scheduler.OverloadReason.EXTENSION_DATAPLANE), is(1L));
        assertThat(hooks.rejectionCount(), is(1L));
        assertThat(holding.calls.get(), is(1));
        release.countDown();
        assertProceeded(await(first));
        awaitSlots(hooks, 1);
    }

    @Test
    public void shouldSkipAChainAtTheCapWhenEveryExtensionInItFailsOpen() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Filter holding = failOpenFilter("holding", (request, context) -> {
            entered.countDown();
            release.await();
            return FilterResult.proceed();
        });
        DataPlaneHooks hooks = filters(1, 10_000, holding);
        CompletableFuture<DataPlaneOutcome> first = new CompletableFuture<>();
        hooks.filter(aRequest(), context(), scheduler(), first::complete);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        DataPlaneOutcome skipped = hooks.filterAndWait(aRequest(), context(), scheduler());

        assertProceeded(skipped);
        assertThat(skipped.isSkipped(), is(true));
        release.countDown();
        await(first);
    }

    @Test
    public void shouldRefuseALoopbackCallAtTheCapInsteadOfDeadlocking() throws Exception {
        AtomicReference<DataPlaneHooks> self = new AtomicReference<>();
        AtomicReference<DataPlaneOutcome> nested = new AtomicReference<>();
        Filter loopback = filter("loopback", (request, context) -> {
            if (request.getFirstHeader("x-nested").isEmpty()) {
                nested.set(self.get().filterAndWait(request.clone().withHeader("x-nested", "1"), context, null));
            }
            return FilterResult.proceed();
        });
        DataPlaneHooks hooks = filters(1, 5_000, loopback);
        self.set(hooks);

        DataPlaneOutcome outer = hooks.filterAndWait(aRequest(), context(), scheduler());

        assertProceeded(outer);
        assertFailed(nested.get(), 503, DataPlaneOutcome.AT_CAPACITY);
        assertThat(loopback.calls.get(), is(1));
    }

    @Test
    public void shouldKeepTheSlotOfAHookThatIgnoresItsInterruptUntilItReturns() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger interruptsSeen = new AtomicInteger();
        AtomicBoolean interruptedOnNextCall = new AtomicBoolean(true);
        AtomicInteger call = new AtomicInteger();
        Filter stubborn = filter("stubborn", (request, context) -> {
            if (call.incrementAndGet() > 1) {
                interruptedOnNextCall.set(Thread.currentThread().isInterrupted());
                return FilterResult.proceed();
            }
            entered.countDown();
            while (true) {
                try {
                    if (release.await(10, TimeUnit.MILLISECONDS)) {
                        return FilterResult.proceed();
                    }
                } catch (InterruptedException ignored) {
                    interruptsSeen.incrementAndGet();
                }
            }
        });
        DataPlaneHooks hooks = filters(1, 60_000, stubborn);
        CompletableFuture<DataPlaneOutcome> outcome = new CompletableFuture<>();
        DataPlaneCall stuck = startWithoutTimer(hooks, outcome);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        stuck.timeOut();

        assertFailed(await(outcome), 500, DataPlaneOutcome.TIMEOUT);
        assertThat("the slot stays with the thread still in extension code", hooks.availableSlots(), is(0));
        assertFailed(hooks.filterAndWait(aRequest(), context(), scheduler()), 503, DataPlaneOutcome.AT_CAPACITY);
        release.countDown();
        awaitSlots(hooks, 1);
        assertThat(interruptsSeen.get(), is(1));
        assertThat(hooks.lateResultCount(), is(1L));
        // the pool's one thread runs the next call without the timeout's interrupt
        assertProceeded(hooks.filterAndWait(aRequest(), context(), scheduler()));
        assertThat(interruptedOnNextCall.get(), is(false));
    }

    @Test
    public void shouldActOnExactlyOneOutcomeWhenTheChainAndTheTimerRace() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Filter racing = filter("racing", (request, context) -> {
            entered.countDown();
            release.await();
            return FilterResult.proceedWith(request.withHeader("x-done", "1"));
        });
        DataPlaneHooks hooks = filters(1, 60_000, racing);
        List<DataPlaneOutcome> outcomes = new CopyOnWriteArrayList<>();
        DataPlaneCall call = new DataPlaneCall(hooks, true, hooks.requestFilters, aRequest(), null, context(), null, outcomes::add, true);
        call.start();
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        CyclicBarrier together = new CyclicBarrier(2);
        Thread timer = new Thread(() -> {
            awaitQuietly(together);
            call.timeOut();
        });
        timer.start();
        awaitQuietly(together);
        release.countDown();
        timer.join(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
        awaitSlots(hooks, 1);

        assertThat(outcomes, hasSize(1));
        DataPlaneOutcome outcome = outcomes.get(0);
        if (outcome.isFailure()) {
            assertThat(outcome.failureType(), is(DataPlaneOutcome.TIMEOUT));
            assertThat(hooks.lateResultCount(), is(1L));
        } else {
            assertThat(outcome.request().getFirstHeader("x-done"), is("1"));
            assertThat(hooks.lateResultCount(), is(0L));
        }
    }

    @Test
    public void shouldCompletePendingCallsAs503AndInterruptThemOnStop() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        CountDownLatch interrupted = new CountDownLatch(2);
        Filter blocking = failOpenFilter("blocking", (request, context) -> {
            entered.countDown();
            try {
                Thread.sleep(TimeUnit.SECONDS.toMillis(WAIT_SECONDS));
            } catch (InterruptedException e) {
                interrupted.countDown();
            }
            return FilterResult.proceed();
        });
        DataPlaneHooks hooks = filters(4, 60_000, blocking);
        Scheduler scheduler = scheduler();
        List<DataPlaneOutcome> outcomes = new CopyOnWriteArrayList<>();
        hooks.filter(aRequest(), context(), scheduler, outcomes::add);
        hooks.filter(aRequest(), context(), scheduler, outcomes::add);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        hooks.stop();

        assertThat("completed synchronously by stop()", outcomes, hasSize(2));
        for (DataPlaneOutcome outcome : outcomes) {
            assertFailed(outcome, 503, DataPlaneOutcome.STOPPING);
        }
        assertThat(interrupted.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
        hooks.stop();
        assertThat(outcomes, hasSize(2));
        assertFailed(hooks.filterAndWait(aRequest(), context(), scheduler), 503, DataPlaneOutcome.STOPPING);
        assertThat(blocking.calls.get(), is(2));
        assertThat(hooks.executor.awaitTermination(WAIT_SECONDS, TimeUnit.SECONDS), is(true));
    }

    /**
     * HTTP/3 stops before the TCP drain, so stopping its calls must leave every other call to finish normally and keep
     * admitting new ones; a full {@link DataPlaneHooks#stop()} there would answer HTTP/1.1 and HTTP/2 requests 503.
     */
    @Test
    public void shouldCompleteOnlyThePendingCallsOfTheStoppedProtocolAndKeepAdmittingCalls() throws Exception {
        CountDownLatch entered = new CountDownLatch(4);
        CountDownLatch release = new CountDownLatch(1);
        Filter waiting = filter("waiting", (request, context) -> {
            entered.countDown();
            try {
                release.await(WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                // the stopped call's thread; its outcome is already the 503
            }
            return FilterResult.proceed();
        });
        DataPlaneHooks hooks = filters(8, 60_000, waiting);
        Scheduler scheduler = scheduler();
        CompletableFuture<DataPlaneOutcome> http1 = new CompletableFuture<>();
        CompletableFuture<DataPlaneOutcome> http2 = new CompletableFuture<>();
        CompletableFuture<DataPlaneOutcome> http3 = new CompletableFuture<>();
        CompletableFuture<DataPlaneOutcome> noProtocol = new CompletableFuture<>();
        hooks.filter(aRequest(), filterContext().protocol(Protocol.HTTP_1_1).build(), scheduler, http1::complete);
        hooks.filter(aRequest(), filterContext().protocol(Protocol.HTTP_2).build(), scheduler, http2::complete);
        hooks.filter(aRequest(), filterContext().protocol(Protocol.HTTP_3).build(), scheduler, http3::complete);
        hooks.filter(aRequest(), filterContext().protocol(null).build(), scheduler, noProtocol::complete);
        assertThat(entered.await(WAIT_SECONDS, TimeUnit.SECONDS), is(true));

        hooks.stopCalls(Protocol.HTTP_3);

        assertFailed(http3.getNow(null), 503, DataPlaneOutcome.STOPPING);
        assertThat("the other calls are still pending", http1.isDone() || http2.isDone() || noProtocol.isDone(), is(false));
        assertThat(hooks.isStopping(), is(false));
        CompletableFuture<DataPlaneOutcome> admitted = new CompletableFuture<>();
        hooks.filter(aRequest(), filterContext().protocol(Protocol.HTTP_3).build(), scheduler, admitted::complete);
        release.countDown();
        assertProceeded(await(http1));
        assertProceeded(await(http2));
        assertProceeded(await(noProtocol));
        assertProceeded(await(admitted));
        assertThat(waiting.calls.get(), is(5));
    }

    @Test
    public void shouldAnswer503AsStoppingWhenTheSchedulerRejectsTheTimer() throws Exception {
        Filter never = failOpenFilter("never", (request, context) -> FilterResult.proceed());
        DataPlaneHooks hooks = filters(2, 1000, never);
        Scheduler stopped = scheduler();
        stopped.shutdown();
        CompletableFuture<DataPlaneOutcome> outcome = new CompletableFuture<>();

        hooks.filter(aRequest(), context(), stopped, outcome::complete);

        assertFailed(outcome.getNow(null), 503, DataPlaneOutcome.STOPPING);
        assertThat(never.calls.get(), is(0));
        assertThat(hooks.availableSlots(), is(2));
    }

    @Test
    public void shouldSkipAHookDisabledAfterStartUp() throws Exception {
        Filter disabled = filter("disabled", (request, context) -> FilterResult.respond(response().withStatusCode(403)));
        DataPlaneHooks hooks = filters(2, 1000, disabled);
        hooks.requestFilters.get(0).extension.disable(ExtensionHook.REQUEST_FILTER, "test");

        assertProceeded(hooks.filterAndWait(aRequest(), context(), scheduler()));
        assertThat(disabled.calls.get(), is(0));
    }

    @Test
    public void shouldCountAndLogADroppedWrite() {
        DataPlaneHooks hooks = filters(1, 1000, filter("any", (request, context) -> FilterResult.proceed()));

        hooks.droppedWrite(DataPlaneHooks.DROPPED_DUPLICATE, aRequest(), response().withStatusCode(200));

        assertThat(hooks.droppedWriteCount(), is(1L));
        assertThat(logger.warnings(), hasItem(containsString("duplicate")));
    }

    // ---- start-up

    @Test
    public void shouldWarnWhenTwiceTheBudgetIsNotBelowTheIdleTimeoutOrDrain() {
        DataPlaneHooks hooks = filters(1, 1000, filter("any", (request, context) -> FilterResult.proceed()));

        hooks.logStartup(1500, 15_000);
        assertThat(logger.warnings(), contains(containsString("inboundConnectionIdleTimeoutMillis")));

        logger.clear();
        hooks.logStartup(300_000, 2000);
        assertThat(logger.warnings(), contains(containsString("stopDrainMillis")));

        logger.clear();
        hooks.logStartup(300_000, 15_000);
        assertThat(logger.warnings(), is(empty()));
    }

    @Test
    public void shouldNotWarnWhenTheIdleTimeoutOrDrainIsDisabled() {
        DataPlaneHooks hooks = filters(1, 1000, filter("any", (request, context) -> FilterResult.proceed()));

        hooks.logStartup(0, 0);

        assertThat(logger.warnings(), is(empty()));
    }

    @Test
    public void shouldBuildHooksOnlyWhenAStartedExtensionIsAFilterOrTransformer() {
        Configuration withoutHooks = configuration().extensions(() -> new Extension() {
            @Override
            public String name() {
                return "plain";
            }
        });
        ExtensionRegistry plain = ExtensionRegistry.start(withoutHooks, logger);
        try {
            assertThat(plain.dataPlaneHooks(), nullValue());
        } finally {
            plain.stop();
        }

        AtomicInteger failOpenAsked = new AtomicInteger();
        Filter a = new Filter("a", 5, false, (request, context) -> FilterResult.proceed());
        Filter b = new Filter("b", 1, false, (request, context) -> FilterResult.proceed()) {
            @Override
            public boolean failOpen() {
                failOpenAsked.incrementAndGet();
                return true;
            }
        };
        Transformer t1 = new Transformer("t1", 1, false, (request, response, context) -> response);
        // a distinct class, because the loader keeps one instance per extension class
        Transformer t2 = new Transformer("t2", 2, false, (request, response, context) -> response) {
        };
        Configuration withHooks = configuration().inboundConnectionIdleTimeoutMillis(0L).stopDrainMillis(0L)
            .extensionDataPlaneMaxInFlight(3).extensionDataPlaneTimeoutMillis(250L)
            .extensions(() -> a, () -> b, () -> t1, () -> t2);
        ExtensionRegistry registry = ExtensionRegistry.start(withHooks, logger);
        try {
            DataPlaneHooks hooks = registry.dataPlaneHooks();
            assertThat(hooks, notNullValue());
            assertThat(hooks.requestFilterNames(), contains("b", "a"));
            assertThat(hooks.responseTransformerNames(), contains("t2", "t1"));
            assertThat(hooks.maxInFlight(), is(3));
            assertThat(hooks.timeoutMillis(), is(250L));
            assertThat(failOpenAsked.get(), is(1));
            assertThat(hooks.requestFilters.get(0).failOpen, is(true));
            assertThat("silent with the idle timeout and drain disabled", logger.warnings(), is(empty()));
        } finally {
            registry.stop();
        }
        assertThat(registry.dataPlaneHooks().isStopped(), is(true));
    }

    @Test
    public void shouldRefuseStartUpWhenFailOpenThrows() {
        Filter broken = new Filter("broken", 0, false, (request, context) -> FilterResult.proceed()) {
            @Override
            public boolean failOpen() {
                throw new IllegalStateException("no answer");
            }
        };

        try {
            ExtensionRegistry.start(configuration().extensions(() -> broken), logger);
            throw new AssertionError("expected start-up to be refused");
        } catch (ExtensionException expected) {
            assertThat(expected.getMessage(), containsString("broken failed in failOpen"));
        }
    }

    // ---- helpers

    /**
     * Starts a filter chain with no timer, so a test ends its budget with {@link DataPlaneCall#timeOut()} exactly when
     * the hook is known to be running, rather than racing a short real budget against pool-thread start-up.
     */
    private static DataPlaneCall startWithoutTimer(DataPlaneHooks hooks, CompletableFuture<DataPlaneOutcome> outcome) {
        DataPlaneCall call = new DataPlaneCall(hooks, true, hooks.requestFilters, aRequest(), null, context(), null, outcome::complete, true);
        call.start();
        return call;
    }

    private static void awaitQuietly(CyclicBarrier barrier) {
        try {
            barrier.await(WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final class CapturingLogger extends MockServerLogger {
        private final List<LogEntry> entries = new CopyOnWriteArrayList<>();

        CapturingLogger() {
            super(configuration().logLevel(Level.INFO), DataPlaneHooksTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            entries.add(logEntry);
        }

        List<String> warnings() {
            List<String> warnings = new ArrayList<>();
            for (LogEntry entry : entries) {
                if (entry.getLogLevel() == Level.WARN) {
                    warnings.add(entry.getMessage());
                }
            }
            return warnings;
        }

        List<Throwable> throwables() {
            List<Throwable> throwables = new ArrayList<>();
            for (LogEntry entry : entries) {
                if (entry.getThrowable() != null) {
                    throwables.add(entry.getThrowable());
                }
            }
            return throwables;
        }

        void clear() {
            entries.clear();
        }
    }
}

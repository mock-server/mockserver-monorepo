package org.mockserver.extension;

import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.NottableString;
import org.mockserver.model.Protocol;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.log.model.LogEntry.LogMessageType.WARN;

/**
 * The {@link RequestFilter} and {@link ResponseTransformer} chains of one server, and the capped pool they run on.
 * <p>
 * Built by {@link ExtensionRegistry} only when a started extension implements one of the two hooks, so with none
 * loaded the field holding it stays {@code null} and nothing here is loaded. Each chain run takes one of
 * {@code extensionDataPlaneMaxInFlight} slots without waiting, or is refused at once, and keeps it until its thread
 * returns, so the pool never has more threads than slots however many hooks hang. Each chain run has one budget of
 * {@code extensionDataPlaneTimeoutMillis}, measured from admission.
 */
public final class DataPlaneHooks {

    /**
     * {@code reason} label of {@code mock_server_extension_dataplane_dropped_writes}: a hook result that arrived after
     * its exchange was answered.
     */
    public static final String DROPPED_LATE_HOOK_RESULT = "late_hook_result";
    /**
     * A final response written after the exchange was answered with a failure response.
     */
    public static final String DROPPED_AFTER_FAILURE = "after_failure";
    /**
     * Any other second final response for one exchange.
     */
    public static final String DROPPED_DUPLICATE = "duplicate";

    static final String FAILURE_MESSAGE = ", see the MockServer log for correlation id: ";
    private static final long STACK_TRACE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final AtomicInteger INSTANCES = new AtomicInteger();

    final List<Hook> requestFilters;
    final List<Hook> responseTransformers;
    final MockServerLogger mockServerLogger;
    final int maxInFlight;
    final long timeoutMillis;
    final Semaphore slots;
    final ThreadPoolExecutor executor;
    final Set<DataPlaneCall> active = ConcurrentHashMap.newKeySet();
    private final String threadNamePrefix;
    private final AtomicBoolean stopped = new AtomicBoolean();
    private volatile boolean stopping;
    private final ConcurrentHashMap<String, Long> lastStackTraceNanos = new ConcurrentHashMap<>();
    private final AtomicLong failures = new AtomicLong();
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong lateResults = new AtomicLong();
    private final AtomicLong rejections = new AtomicLong();
    private final AtomicLong droppedWrites = new AtomicLong();

    /**
     * One extension in a chain; {@code failOpen} is asked once, at start-up.
     */
    static final class Hook {
        final LoadedExtension extension;
        final ExtensionHook type;
        final boolean failOpen;

        Hook(LoadedExtension extension, ExtensionHook type, boolean failOpen) {
            this.extension = extension;
            this.type = type;
            this.failOpen = failOpen;
        }

        String name() {
            return extension.name();
        }

        boolean isEnabled() {
            return extension.isHookEnabled(type);
        }

        @Override
        public String toString() {
            return extension.name() + (failOpen ? " (fails open)" : "");
        }
    }

    DataPlaneHooks(List<Hook> requestFilters, List<Hook> responseTransformers, int maxInFlight, long timeoutMillis, MockServerLogger mockServerLogger) {
        this.requestFilters = Collections.unmodifiableList(new ArrayList<>(requestFilters));
        this.responseTransformers = Collections.unmodifiableList(new ArrayList<>(responseTransformers));
        this.mockServerLogger = mockServerLogger;
        this.maxInFlight = Math.max(1, maxInFlight);
        this.timeoutMillis = Math.max(1L, timeoutMillis);
        this.slots = new Semaphore(this.maxInFlight);
        this.threadNamePrefix = "MockServer-ext-dataplane-" + INSTANCES.incrementAndGet() + "-";
        ClassLoader contextClassLoader = Thread.currentThread().getContextClassLoader();
        AtomicInteger threadNumber = new AtomicInteger();
        // as many threads as slots: a task only queues while a finished call's thread returns to the pool
        this.executor = new ThreadPoolExecutor(this.maxInFlight, this.maxInFlight, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), runnable -> {
            Thread thread = new Thread(runnable, threadNamePrefix + threadNumber.incrementAndGet());
            thread.setDaemon(true);
            thread.setContextClassLoader(contextClassLoader);
            return thread;
        });
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @param requestFilters       in the order they run: ascending priority, then name
     * @param responseTransformers in the order they run: descending priority, then descending name
     * @return the hooks, or {@code null} when both chains are empty
     */
    static DataPlaneHooks create(List<Hook> requestFilters, List<Hook> responseTransformers, Configuration configuration, MockServerLogger mockServerLogger) {
        if (requestFilters.isEmpty() && responseTransformers.isEmpty()) {
            return null;
        }
        DataPlaneHooks hooks = new DataPlaneHooks(requestFilters, responseTransformers, configuration.extensionDataPlaneMaxInFlight(), configuration.extensionDataPlaneTimeoutMillis(), mockServerLogger);
        hooks.logStartup(configuration.inboundConnectionIdleTimeoutMillis(), configuration.stopDrainMillis());
        return hooks;
    }

    /**
     * Logs the chain order, and a WARN when twice the budget (a filter chain plus a transformer chain) is not below
     * the idle timeout or the stop drain; each check is skipped when that value is 0 or less, which disables it.
     */
    void logStartup(long inboundConnectionIdleTimeoutMillis, long stopDrainMillis) {
        if (isEnabled(Level.INFO)) {
            mockServerLogger.logEvent(new LogEntry()
                .setType(SERVER_CONFIGURATION)
                .setLogLevel(Level.INFO)
                .setMessageFormat("extension request filters run in the order:{}and response transformers in the order:{}with at most:{}chains in flight and a budget of:{}ms per chain")
                .setArguments(requestFilters, responseTransformers, maxInFlight, timeoutMillis));
        }
        // twice the budget is not below the limit, written so a huge budget cannot overflow
        if (inboundConnectionIdleTimeoutMillis > 0 && timeoutMillis >= inboundConnectionIdleTimeoutMillis - timeoutMillis) {
            warnBudget("inboundConnectionIdleTimeoutMillis", inboundConnectionIdleTimeoutMillis, "a connection whose request waits on extension hooks can be closed as idle");
        }
        if (stopDrainMillis > 0 && timeoutMillis >= stopDrainMillis - timeoutMillis) {
            warnBudget("stopDrainMillis", stopDrainMillis, "a request waiting on extension hooks may not be answered before the server stops");
        }
    }

    private void warnBudget(String property, long value, String consequence) {
        if (isEnabled(Level.WARN)) {
            mockServerLogger.logEvent(new LogEntry()
                .setType(SERVER_CONFIGURATION)
                .setLogLevel(Level.WARN)
                .setMessageFormat("extensionDataPlaneTimeoutMillis:{}can be spent twice on one request (once filtering it and once transforming its response), which is not below:{}of:{}so{}")
                .setArguments(timeoutMillis, property, value, consequence));
        }
    }

    // ---- running chains

    /**
     * Runs the request filter chain on the hook pool and passes the outcome to {@code onDone} exactly once, on the
     * hook thread, the scheduler's timer thread, the thread calling {@link #stop()}, or (when the call is refused at
     * once) this thread, with the calling thread's {@code HttpState} port set. {@code onDone} must not block: on a hook
     * thread it runs after the slot is released, so it would hold a pool thread no slot accounts for.
     *
     * @param scheduler the scheduler whose timer ends the budget; without a timer the call is refused as stopping
     */
    public void filter(HttpRequest request, FilterContext context, Scheduler scheduler, Consumer<DataPlaneOutcome> onDone) {
        if (requestFilters.isEmpty()) {
            onDone.accept(DataPlaneOutcome.proceed(request, null, false));
            return;
        }
        new DataPlaneCall(this, true, requestFilters, request, null, context, scheduler, onDone, false).start();
    }

    /**
     * Runs the request filter chain on the hook pool and waits for its outcome for at most the budget, for a caller
     * that answers on its own thread (a servlet, {@code InProcessMockServer.handle}).
     */
    public DataPlaneOutcome filterAndWait(HttpRequest request, FilterContext context, Scheduler scheduler) {
        if (requestFilters.isEmpty()) {
            return DataPlaneOutcome.proceed(request, null, false);
        }
        CompletableFuture<DataPlaneOutcome> result = new CompletableFuture<>();
        DataPlaneCall call = new DataPlaneCall(this, true, requestFilters, request, null, context, scheduler, result::complete, true);
        call.start();
        return call.await(result);
    }

    /**
     * Runs the response transformer chain on the hook pool and passes the outcome to {@code onDone} exactly once, as
     * for {@link #filter}.
     */
    public void transform(HttpRequest request, HttpResponse response, TransformContext context, Scheduler scheduler, Consumer<DataPlaneOutcome> onDone) {
        if (responseTransformers.isEmpty()) {
            onDone.accept(DataPlaneOutcome.transformed(request, response, null, false));
            return;
        }
        new DataPlaneCall(this, false, responseTransformers, request, response, context, scheduler, onDone, false).start();
    }

    /**
     * Runs the response transformer chain and waits for its outcome for at most the budget, as for
     * {@link #filterAndWait}.
     */
    public DataPlaneOutcome transformAndWait(HttpRequest request, HttpResponse response, TransformContext context, Scheduler scheduler) {
        if (responseTransformers.isEmpty()) {
            return DataPlaneOutcome.transformed(request, response, null, false);
        }
        CompletableFuture<DataPlaneOutcome> result = new CompletableFuture<>();
        DataPlaneCall call = new DataPlaneCall(this, false, responseTransformers, request, response, context, scheduler, result::complete, true);
        call.start();
        return call.await(result);
    }

    /**
     * Stops admitting calls, completes every pending call at once as stopping ({@code 503}), and interrupts the
     * threads that are inside extension code; MockServer's own work on a call is never interrupted. Safe to call more
     * than once.
     */
    public void stop() {
        stopping = true;
        if (!stopped.compareAndSet(false, true)) {
            return;
        }
        executor.shutdown();
        for (DataPlaneCall call : active) {
            call.stopNow();
        }
    }

    /**
     * Completes as stopping ({@code 503}) every pending call for a request that arrived over {@code protocol}, as
     * {@link #stop()} does for every call, but keeps admitting new calls: for a transport that stops before the
     * others drain. Calls made with no context (and so no protocol) are left alone.
     */
    public void stopCalls(Protocol protocol) {
        for (DataPlaneCall call : active) {
            if (protocol != null && protocol == call.protocol()) {
                call.stopNow();
            }
        }
    }

    boolean isStopping() {
        return stopping;
    }

    // ---- what the transports report

    /**
     * Counts and logs a final response that was not written because its exchange had already been answered.
     *
     * @param reason {@link #DROPPED_LATE_HOOK_RESULT}, {@link #DROPPED_AFTER_FAILURE} or {@link #DROPPED_DUPLICATE}
     */
    public void droppedWrite(String reason, HttpRequest request, HttpResponse response) {
        droppedWrites.incrementAndGet();
        Metrics.incrementExtensionDataPlaneDroppedWrites(reason);
        if (isEnabled(Level.WARN)) {
            mockServerLogger.logEvent(new LogEntry()
                .setType(WARN)
                .setLogLevel(Level.WARN)
                .setCorrelationId(request != null ? request.getLogCorrelationId() : null)
                .setHttpRequest(request)
                .setHttpResponse(response)
                .setMessageFormat("dropped the response:{}because its exchange was already answered, reason:{}")
                .setArguments(response, reason));
        }
    }

    // ---- results, failures and logging, for DataPlaneCall

    /**
     * @return why moving {@code before} to {@code after} breaks a request filter's contract, or {@code null}
     */
    static String filterContractViolation(HttpRequest before, HttpRequest after, FilterContext context) {
        String beforePath = path(before);
        String afterPath = path(after);
        boolean pathChanged = !Objects.equals(beforePath, afterPath);
        if (pathChanged && isControlPlanePath(afterPath)) {
            return "moved the request to a control-plane path";
        }
        if (context != null && context.isGrpc()) {
            if (pathChanged) {
                return "changed the path of a gRPC request";
            }
            if (!Objects.equals(before.getFirstHeader("x-grpc-service"), after.getFirstHeader("x-grpc-service"))
                || !Objects.equals(before.getFirstHeader("x-grpc-method"), after.getFirstHeader("x-grpc-method"))) {
                return "changed x-grpc-service or x-grpc-method of a gRPC request";
            }
        }
        return null;
    }

    /**
     * @return why moving {@code before} to {@code after} breaks a response transformer's contract, or {@code null}
     */
    static String transformContractViolation(HttpResponse before, HttpResponse after, TransformContext context) {
        if (context != null && context.isStreamed() && !Objects.equals(before.getBody(), after.getBody())) {
            return "replaced the body of a streamed response";
        }
        return null;
    }

    /**
     * @return true for a {@code 1xx} response, which a request filter may not answer with: it is not a final response
     */
    static boolean isInterim(HttpResponse response) {
        Integer statusCode = response.getStatusCode();
        return statusCode != null && statusCode < 200;
    }

    private static String path(HttpRequest request) {
        NottableString path = request.getPath();
        return path != null ? path.getValue() : null;
    }

    static boolean isControlPlanePath(String path) {
        return path != null && (path.equals("/mockserver") || path.startsWith("/mockserver/") || path.startsWith("/_mockserver_"));
    }

    HttpResponse failureResponse(Hook hook, String failureType, String correlationId) {
        return ControlPlaneFailureResponse.dataPlaneFailureResponse("extension " + hook.name() + " " + hook.type.label() + " failed (" + failureType + ")" + FAILURE_MESSAGE + correlationId);
    }

    static HttpResponse overloadedResponse() {
        return HttpResponse.response()
            .withStatusCode(SERVICE_UNAVAILABLE.code())
            .withReasonPhrase(SERVICE_UNAVAILABLE.reasonPhrase())
            .withHeader("Retry-After", "1")
            .withBody("MockServer is overloaded, too many extension request filter and response transformer calls are already in flight (extensionDataPlaneMaxInFlight)", MediaType.PLAIN_TEXT_UTF_8);
    }

    static HttpResponse stoppingResponse() {
        return HttpResponse.response()
            .withStatusCode(SERVICE_UNAVAILABLE.code())
            .withReasonPhrase(SERVICE_UNAVAILABLE.reasonPhrase())
            .withBody("MockServer is stopping", MediaType.PLAIN_TEXT_UTF_8);
    }

    /**
     * Counts and logs one hook failure: one WARN each time, never rate-limited; only the stack trace is limited, to
     * the first failure per extension per 10 seconds.
     *
     * @param sent the response written because of the failure, or {@code null} when the chain continued (fail-open)
     */
    void recordFailure(Hook hook, String failureType, String correlationId, HttpRequest request, HttpResponse sent, Throwable cause) {
        failures.incrementAndGet();
        if (DataPlaneOutcome.TIMEOUT.equals(failureType)) {
            timeouts.incrementAndGet();
        }
        Metrics.incrementExtensionDataPlaneFailures(hook.name(), hook.type.label(), failureType);
        if (!isEnabled(Level.WARN)) {
            return;
        }
        LogEntry entry = new LogEntry()
            .setType(WARN)
            .setLogLevel(Level.WARN)
            .setCorrelationId(correlationId)
            .setHttpRequest(request);
        if (sent != null) {
            entry.setHttpResponse(sent)
                .setMessageFormat("extension:{}hook:{}failed:{}correlation id:{}and the response sent was:{}")
                .setArguments(hook.name(), hook.type.label(), failureType, correlationId, sent);
        } else {
            entry.setMessageFormat("extension:{}hook:{}failed:{}correlation id:{}and fails open, so the exchange continued as it was before it")
                .setArguments(hook.name(), hook.type.label(), failureType, correlationId);
        }
        if (cause != null && stackTraceDue(hook.name())) {
            entry.setThrowable(cause);
        }
        mockServerLogger.logEvent(entry);
    }

    private boolean stackTraceDue(String extension) {
        long now = System.nanoTime();
        boolean[] due = new boolean[1];
        lastStackTraceNanos.compute(extension, (name, last) -> {
            if (last == null || now - last >= STACK_TRACE_INTERVAL_NANOS) {
                due[0] = true;
                return now;
            }
            return last;
        });
        return due[0];
    }

    void recordRejection() {
        rejections.incrementAndGet();
    }

    void recordLateResult() {
        lateResults.incrementAndGet();
    }

    void logCallbackFailure(Throwable throwable) {
        if (isEnabled(Level.WARN)) {
            mockServerLogger.logEvent(new LogEntry()
                .setType(WARN)
                .setLogLevel(Level.WARN)
                .setMessageFormat("failed to act on the result of the extension request filters or response transformers")
                .setThrowable(throwable));
        }
    }

    private boolean isEnabled(Level level) {
        return mockServerLogger != null && mockServerLogger.isEnabledForInstance(level);
    }

    // ---- state

    public boolean hasRequestFilters() {
        return !requestFilters.isEmpty();
    }

    public boolean hasResponseTransformers() {
        return !responseTransformers.isEmpty();
    }

    /**
     * @return the request filters' names in the order they run
     */
    public List<String> requestFilterNames() {
        return names(requestFilters);
    }

    /**
     * @return the response transformers' names in the order they run
     */
    public List<String> responseTransformerNames() {
        return names(responseTransformers);
    }

    private static List<String> names(List<Hook> hooks) {
        List<String> names = new ArrayList<>();
        for (Hook hook : hooks) {
            names.add(hook.name());
        }
        return Collections.unmodifiableList(names);
    }

    public int maxInFlight() {
        return maxInFlight;
    }

    public long timeoutMillis() {
        return timeoutMillis;
    }

    /**
     * @return slots free now
     */
    public int availableSlots() {
        return slots.availablePermits();
    }

    /**
     * @return the start of the name of every thread these hooks run on
     */
    public String threadNamePrefix() {
        return threadNamePrefix;
    }

    public boolean isStopped() {
        return stopped.get();
    }

    /**
     * @return hook failures of every type, fail-open ones included
     */
    public long failureCount() {
        return failures.get();
    }

    /**
     * @return chains that did not finish within the budget
     */
    public long timeoutCount() {
        return timeouts.get();
    }

    /**
     * @return hook results that arrived after their call had been completed, which were discarded
     */
    public long lateResultCount() {
        return lateResults.get();
    }

    /**
     * @return calls refused because every slot was taken
     */
    public long rejectionCount() {
        return rejections.get();
    }

    /**
     * @return final responses dropped because their exchange had already been answered
     */
    public long droppedWriteCount() {
        return droppedWrites.get();
    }
}

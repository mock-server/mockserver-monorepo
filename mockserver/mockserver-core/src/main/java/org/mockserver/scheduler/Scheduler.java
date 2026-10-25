package org.mockserver.scheduler;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.configuration.Configuration;
import org.mockserver.httpclient.SocketCommunicationException;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.action.http.HttpForwardActionResult;
import org.mockserver.model.BinaryMessage;
import org.mockserver.model.Delay;
import org.mockserver.model.HttpResponse;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.mockserver.log.model.LogEntry.LogMessageType.WARN;
import static org.mockserver.mock.HttpState.getPort;
import static org.mockserver.mock.HttpState.setPort;

/**
 * @author jamesdbloom
 */
public class Scheduler {

    private final Configuration configuration;
    private final ScheduledExecutorService scheduler;
    // Dedicated, UNBOUNDED executor used solely to dispatch LOCAL (in-JVM) object/class callbacks off
    // the server worker event loop. A local callback may make a BLOCKING loopback call back to the same
    // server (e.g. registering a nested expectation via the same MockServerClient); running it inline on
    // the worker event loop, or on the bounded scheduler pool, risks a self-deadlock (the canonical
    // twice-burned regression) or pool starvation under recursion. An unbounded cached pool guarantees
    // that an inner (recursively-triggered) local callback always obtains a fresh thread even when every
    // outer callback thread is blocked waiting on its own loopback — so the wait is always bounded and
    // never a pool-exhaustion deadlock. Threads are reused and reaped after 60s idle, so steady-state
    // cost is the high-water mark of CONCURRENT in-flight blocking callbacks, not a fixed allocation.
    // Null in synchronous mode (callbacks then run inline, preserving WAR/servlet blocking semantics).
    private final ExecutorService localCallbackExecutor;

    // Dedicated, BOUNDED executor used solely to run RESPONSE_TEMPLATE / FORWARD_TEMPLATE rendering off the
    // server worker event loop and off the shared scheduler pool. Template rendering (Velocity/Mustache/
    // JavaScript) is a synchronous, CPU-bound computation that can take tens of milliseconds. Dispatched
    // through the ordinary Scheduler.schedule path it ran INLINE on the Netty worker event-loop thread when
    // there was no delay (see schedule(): zero delay => run() on the calling thread), or on the bounded
    // shared scheduler pool when a delay was configured. Either way a burst of slow renders monopolised a
    // resource shared with every other action type and stalled unrelated plain-match/forward traffic — the
    // single simultaneous all-arm collapse seen in the live incident. Giving templates their own pool
    // isolates that cost: when renders saturate this pool they queue among THEMSELVES, so the worker event
    // loop and the scheduler pool stay free to serve everything else.
    //
    // BOUNDED, unlike the unbounded localCallbackExecutor. That pool is unbounded to defeat a self-deadlock:
    // a local callback may make a BLOCKING loopback call back to this same server and recursively trigger
    // another local callback, so an inner callback must always obtain a fresh thread. Template rendering does
    // NONE of that — it is a self-contained computation with no loopback and no recursion — so the reason to
    // go unbounded simply does not apply here. For CPU-bound work an unbounded pool is actively worse: it
    // would spawn more concurrent render threads than cores (pure context-switch thrash) and, on a
    // memory-constrained SUT, each extra thread's stack brings the separately-diagnosed response-body
    // retention OOM SOONER rather than later. A fixed pool sized like the scheduler pool
    // (actionHandlerThreadCount() = max(5, cores)) gives full CPU parallelism with a fixed memory ceiling.
    // Each queued render retains its request, response writer and channel until drained, so admission is
    // bounded by maxQueuedTemplateActions (see executeTemplateAction). A render over the bound is answered
    // with the caller's overload response rather than run on the caller's thread: CallerRuns would put the
    // render back on the worker event loop, the pathology this pool exists to remove.
    // Null in synchronous mode (renders then run inline, preserving WAR/servlet blocking semantics).
    private final ExecutorService templateActionExecutor;

    // Small BOUNDED pool for blocking file reads (staticFilesDirectory files and FILE response bodies), kept off
    // the event loops and off the shared pool that forwards and delays use. Created on first use, so a server
    // that never reads a file has none; shut down with the other pools. Both kinds share one queue bound.
    static final int FILE_READ_THREADS = 4;
    static final int MAX_QUEUED_FILE_READS = 1000;
    private volatile ThreadPoolExecutor fileReadExecutor;
    private boolean stopped;
    private final AtomicInteger queuedFileReads = new AtomicInteger();
    private volatile int maxQueuedFileReads = MAX_QUEUED_FILE_READS;

    private final boolean synchronous;

    private static final long OVERLOAD_LOG_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final String OVERLOAD_LOG_FORMAT = "overloaded:{}task(s) that were:{}exceeded:{}which has a limit of:{}so they were:{}"
        + "in total since startup:{}raise that property or reduce the request rate; this is logged at most every 10 seconds per reason";
    private static final String FIXED_LIMIT_OVERLOAD_LOG_FORMAT = "overloaded:{}task(s) that were:{}exceeded:{}which has a fixed limit of:{}so they were:{}"
        + "in total since startup:{}reduce the request rate; this is logged at most every 10 seconds per reason";

    // Three independent budgets of the same size, so a backlog of one kind can never refuse another: delayed
    // responses (incl. forwarded responses waiting for chaos latency), delayed side actions, and WebSocket bidi
    // reply frames (admitted or refused a whole reply set at a time). Delays with no fallback are counted apart,
    // never refused: chained SSE/WebSocket/gRPC message delays and close-socket delays (one per stream or
    // connection) and control-plane timed scenario transitions (one per scenario).
    private final AtomicInteger pendingDelayedResponses = new AtomicInteger();
    private final AtomicInteger pendingDelayedSideActions = new AtomicInteger();
    private final AtomicInteger pendingWebSocketReplyFrames = new AtomicInteger();
    private final AtomicInteger pendingUnboundedDelayedTasks = new AtomicInteger();
    private final AtomicLong webSocketReadPauses = new AtomicLong();
    private final AtomicInteger queuedTemplateActions = new AtomicInteger();
    private volatile int maxPendingDelayedResponses = Integer.MAX_VALUE;
    private volatile int maxQueuedTemplateActions = Integer.MAX_VALUE;
    private final OverloadLog[] overloadLogs = new OverloadLog[OverloadReason.values().length];

    {
        for (int i = 0; i < overloadLogs.length; i++) {
            overloadLogs[i] = new OverloadLog();
        }
    }

    /**
     * Why a task was refused and what happened instead; {@link #metricLabel} is the {@code reason} label of
     * {@code mock_server_overload_rejections}.
     */
    public enum OverloadReason {
        DELAYED_RESPONSES("delayed_responses", "maxPendingDelayedResponses", "responses waiting for a configured delay", "answered 503 Service Unavailable"),
        DELAY_SKIPPED("delay_skipped", "maxPendingDelayedResponses", "forwarded responses waiting for chaos latency", "sent at once without the injected latency"),
        SIDE_ACTIONS("side_actions", "maxPendingDelayedResponses", "delayed side actions (after-actions, secondary actions, step side effects)", "dropped"),
        WEBSOCKET_REPLIES("websocket_replies", "maxPendingDelayedResponses", "WebSocket reply frames waiting for their delays", "refused a whole reply set at a time, closing the WebSocket with 1013 Try Again Later"),
        TEMPLATE_ACTIONS("template_actions", "maxQueuedTemplateActions", "template renders waiting for a template thread", "answered 503 Service Unavailable"),
        STATIC_FILES("static_files", "the file read queue", true, "static file reads waiting for a file read thread", "answered 503 Service Unavailable"),
        FILE_BODIES("file_bodies", "the file read queue", true, "FILE response body reads waiting for a file read thread", "answered 503 Service Unavailable"),
        EXTENSION_ADMIN("extension_admin", "extensionAdminMaxInFlight", "extension admin route calls in flight", "answered 503 Service Unavailable"),
        EXTENSION_DATAPLANE("extension_dataplane", "extensionDataPlaneMaxInFlight", "extension request filter and response transformer chains in flight", "answered 503 Service Unavailable");

        private final String metricLabel;
        private final String property;
        private final String description;
        private final String outcome;

        // a fixed limit is not a property, so its log line does not advise raising one
        private final boolean fixedLimit;

        OverloadReason(String metricLabel, String property, String description, String outcome) {
            this(metricLabel, property, false, description, outcome);
        }

        OverloadReason(String metricLabel, String property, boolean fixedLimit, String description, String outcome) {
            this.metricLabel = metricLabel;
            this.property = property;
            this.fixedLimit = fixedLimit;
            this.description = description;
            this.outcome = outcome;
        }
    }

    /**
     * A task that a bounded dispatch may refuse under overload, with what to do instead. Only tasks wrapped
     * this way are subject to the bounds; other delayed tasks are counted apart and always admitted.
     */
    private static final class RejectableTask implements Runnable {
        private final Runnable task;
        private final Runnable onRejected;
        private final OverloadReason reason;

        private RejectableTask(Runnable task, Runnable onRejected, OverloadReason reason) {
            this.task = task;
            this.onRejected = onRejected;
            this.reason = reason;
        }

        @Override
        public void run() {
            task.run();
        }
    }

    /**
     * Wrap a request dispatch so that, when a bounded dispatch is full, {@code onRejected} runs (on the
     * dispatching thread) in its place. {@code onRejected} must answer the request, e.g. with a 503.
     */
    public static Runnable rejectable(Runnable task, Runnable onRejected) {
        return new RejectableTask(task, onRejected, OverloadReason.DELAYED_RESPONSES);
    }

    /**
     * Wrap a write whose delay is optional (chaos latency on an already-forwarded response): when the delayed
     * response budget is full it runs at once, without the delay, rather than being refused — refusing would
     * discard an upstream response whose request has already been sent.
     */
    public static Runnable delayOptional(Runnable write) {
        return new RejectableTask(write, write, OverloadReason.DELAY_SKIPPED);
    }

    /**
     * Wrap a fire-and-forget side action (no client is waiting for it) so that, when its own delayed budget is
     * full, it is dropped and counted rather than held; it never consumes the delayed-response budget. Never
     * wrap one frame of a multi-frame reply: shedding it would deliver the rest of the reply silently.
     */
    public static Runnable sheddable(Runnable sideAction) {
        return new RejectableTask(sideAction, () -> {
        }, OverloadReason.SIDE_ACTIONS);
    }

    private static RejectableTask rejectableOf(Runnable command) {
        return command instanceof RejectableTask ? (RejectableTask) command : null;
    }

    private static final class OverloadLog {
        private final AtomicLong total = new AtomicLong();
        private final AtomicLong rejectedSinceLastLog = new AtomicLong();
        private final AtomicLong nextLogNanos = new AtomicLong();
        private volatile boolean logged;

        /**
         * Record one rejection; returns how many to report when a log line is due (at most one per interval),
         * otherwise 0.
         */
        long recordAndTakeIfDue(long nowNanos) {
            total.incrementAndGet();
            rejectedSinceLastLog.incrementAndGet();
            long next = nextLogNanos.get();
            if ((!logged || nowNanos - next >= 0) && nextLogNanos.compareAndSet(next, nowNanos + OVERLOAD_LOG_INTERVAL_NANOS)) {
                logged = true;
                return rejectedSinceLastLog.getAndSet(0);
            }
            return 0;
        }
    }

    public static class SchedulerThreadFactory implements ThreadFactory {

        private final String name;
        private final boolean daemon;
        private static final AtomicInteger threadInitNumber = new AtomicInteger();

        public SchedulerThreadFactory(String name) {
            this.name = name;
            this.daemon = true;
        }

        public SchedulerThreadFactory(String name, boolean daemon) {
            this.name = name;
            this.daemon = daemon;
        }

        @Override
        @SuppressWarnings("NullableProblems")
        public Thread newThread(Runnable runnable) {
            Thread thread = new Thread(runnable, "MockServer-" + name + threadInitNumber.getAndIncrement());
            thread.setDaemon(daemon);
            return thread;
        }
    }

    private final MockServerLogger mockServerLogger;

    public Scheduler(Configuration configuration, MockServerLogger mockServerLogger) {
        this(configuration, mockServerLogger, false);
    }

    @VisibleForTesting
    public Scheduler(Configuration configuration, MockServerLogger mockServerLogger, boolean synchronous) {
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.synchronous = synchronous;
        if (!this.synchronous) {
            ScheduledThreadPoolExecutor scheduledThreadPoolExecutor = new ScheduledThreadPoolExecutor(
                configuration.actionHandlerThreadCount(),
                new SchedulerThreadFactory("Scheduler"),
                new ThreadPoolExecutor.CallerRunsPolicy()
            );
            // a cancelled task (a replaced timed scenario transition, a cancelled coalescing or eventual-verify
            // timer) leaves the queue at once instead of holding its slot until its delay would have elapsed
            scheduledThreadPoolExecutor.setRemoveOnCancelPolicy(true);
            this.scheduler = scheduledThreadPoolExecutor;
            // Unbounded cached pool for local-callback dispatch (see field javadoc). Core size 0,
            // max Integer.MAX_VALUE, 60s keep-alive — grows on demand and shrinks back to zero when
            // idle, so it never deadlocks a recursive/nested blocking local callback the way a bounded
            // pool would, yet costs nothing at rest.
            ThreadPoolExecutor localCallbackPool = new ThreadPoolExecutor(
                0, Integer.MAX_VALUE,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new SchedulerThreadFactory("LocalCallback")
            );
            this.localCallbackExecutor = localCallbackPool;
            // Dedicated BOUNDED pool for template-action rendering (see field javadoc). Fixed size =
            // actionHandlerThreadCount() (max(5, cores)) with an unbounded LinkedBlockingQueue: full CPU
            // parallelism for renders, a fixed thread/memory ceiling, and no fallback onto the worker event
            // loop. Threads are daemon (via SchedulerThreadFactory) so they never block JVM shutdown.
            int templateThreads = configuration.actionHandlerThreadCount();
            this.templateActionExecutor = new ThreadPoolExecutor(
                templateThreads, templateThreads,
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                new SchedulerThreadFactory("TemplateAction")
            );
        } else {
            this.scheduler = null;
            this.localCallbackExecutor = null;
            this.templateActionExecutor = null;
        }
        applyConfigurationCapacity();
    }

    /**
     * (Re)read {@code maxPendingDelayedResponses} and {@code maxQueuedTemplateActions} from the configuration;
     * a value of zero or less means unbounded. Requests already admitted are unaffected by a lower limit.
     */
    public void applyConfigurationCapacity() {
        this.maxPendingDelayedResponses = limitOrUnbounded(configuration.maxPendingDelayedResponses());
        this.maxQueuedTemplateActions = limitOrUnbounded(configuration.maxQueuedTemplateActions());
    }

    private static int limitOrUnbounded(Integer limit) {
        return limit == null || limit <= 0 ? Integer.MAX_VALUE : limit;
    }

    /**
     * Returns the underlying executor service for use with CompletableFuture
     * async continuations (e.g. thenAcceptAsync). Returns null in synchronous
     * mode — callers must handle that case (run inline).
     */
    public ScheduledExecutorService getExecutorService() {
        return scheduler;
    }

    /**
     * Tasks waiting in the shared scheduler pool's queue (0 in synchronous mode), including delayed tasks
     * whose delay has not elapsed. Delayed request dispatches are admission-bounded by
     * {@code maxPendingDelayedResponses}; undelayed tasks (forward continuations, lazy removals, before-actions,
     * undelayed side actions) are not, because they must not be dropped. Each belongs to a request that is
     * already being served, so they are bounded by the requests in flight (connections x HTTP/2 streams, each
     * forward continuation for at most {@code maxFutureTimeout}). Best-effort work bounds its own submissions
     * (see drift analysis).
     */
    public int getQueuedTaskCount() {
        return scheduler instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) scheduler).getQueue().size() : 0;
    }

    /**
     * Template renders waiting for a template-action thread (0 in synchronous mode).
     */
    public int getQueuedTemplateActionCount() {
        return templateActionExecutor instanceof ThreadPoolExecutor ? ((ThreadPoolExecutor) templateActionExecutor).getQueue().size() : 0;
    }

    /**
     * All delayed tasks admitted whose delay has not yet elapsed, bounded or not (0 in synchronous mode, where
     * delays sleep inline).
     */
    public int getPendingDelayedTaskCount() {
        return pendingDelayedResponses.get() + pendingDelayedSideActions.get() + pendingWebSocketReplyFrames.get() + pendingUnboundedDelayedTasks.get();
    }

    /**
     * WebSocket bidi reply frames admitted whose delay has not yet elapsed.
     */
    public int getPendingWebSocketReplyFrameCount() {
        return pendingWebSocketReplyFrames.get();
    }

    /**
     * Count one WebSocket connection whose reads were paused because too many delayed reply sets were pending on
     * it; kept whether or not metrics are enabled.
     */
    public void recordWebSocketReadPause() {
        webSocketReadPauses.incrementAndGet();
        Metrics.incrementWebSocketReadPauses();
    }

    /**
     * WebSocket read pauses since this scheduler started (see {@link #recordWebSocketReadPause()}).
     */
    public long getWebSocketReadPauseCount() {
        return webSocketReadPauses.get();
    }

    /**
     * Template renders admitted and not yet started, as the {@code maxQueuedTemplateActions} admission counts them.
     */
    int getAdmittedTemplateActionCount() {
        return queuedTemplateActions.get();
    }

    /**
     * Delayed tasks counted against the {@code maxPendingDelayedResponses} response budget.
     */
    public int getPendingDelayedResponseCount() {
        return pendingDelayedResponses.get();
    }

    /**
     * Tasks refused for {@code reason} since this scheduler started; kept whether or not metrics are enabled.
     */
    public long getOverloadRejectionCount(OverloadReason reason) {
        return overloadLogs[reason.ordinal()].total.get();
    }

    private AtomicInteger delayedBudgetFor(RejectableTask rejectable) {
        if (rejectable == null) {
            return pendingUnboundedDelayedTasks;
        }
        return rejectable.reason == OverloadReason.SIDE_ACTIONS ? pendingDelayedSideActions : pendingDelayedResponses;
    }

    private void scheduleAfterDelay(Runnable command, RejectableTask rejectable, long delayMillis, Integer port) {
        AtomicInteger pending = delayedBudgetFor(rejectable);
        if (rejectable == null) {
            pending.incrementAndGet();
        } else if (!tryAdmit(pending, 1, maxPendingDelayedResponses)) {
            rejectForOverload(rejectable.reason, maxPendingDelayedResponses, rejectable.onRejected, port);
            return;
        }
        try {
            scheduler.schedule(() -> {
                pending.decrementAndGet();
                command.run();
            }, delayMillis, MILLISECONDS);
        } catch (RuntimeException exception) {
            pending.decrementAndGet();
            throw exception;
        }
    }

    private void executeTemplateAction(Runnable command, RejectableTask rejectable, Integer port) {
        if (rejectable == null) {
            queuedTemplateActions.incrementAndGet();
        } else if (!tryAdmit(queuedTemplateActions, 1, maxQueuedTemplateActions)) {
            rejectForOverload(OverloadReason.TEMPLATE_ACTIONS, maxQueuedTemplateActions, rejectable.onRejected, port);
            return;
        }
        try {
            templateActionExecutor.execute(() -> {
                queuedTemplateActions.decrementAndGet();
                run(command, port);
            });
        } catch (RuntimeException exception) {
            queuedTemplateActions.decrementAndGet();
            throw exception;
        }
    }

    /**
     * Adds {@code permits} to {@code budget} only while it holds fewer than {@code limit}. A refused task never
     * raises the count, so it cannot inflate a pending count or make a free slot look full to another task.
     */
    private static boolean tryAdmit(AtomicInteger budget, int permits, int limit) {
        int current;
        do {
            current = budget.get();
            if (current >= limit) {
                return false;
            }
        } while (!budget.compareAndSet(current, current + permits));
        return true;
    }

    /**
     * Refuses a task that a bound outside this scheduler turned away: counts it under {@code reason}, logs it at most
     * every 10 seconds, then runs {@code onRejected} on the calling thread, which must answer the request.
     */
    public void refuseForOverload(OverloadReason reason, int limit, Runnable onRejected) {
        rejectForOverload(reason, limit, onRejected, getPort());
    }

    private void rejectForOverload(OverloadReason reason, int limit, Runnable onRejected, Integer port) {
        Metrics.incrementOverloadRejections(reason.metricLabel);
        OverloadLog overloadLog = overloadLogs[reason.ordinal()];
        long rejected = overloadLog.recordAndTakeIfDue(System.nanoTime());
        if (rejected > 0 && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(WARN)
                    .setLogLevel(Level.WARN)
                    .setMessageFormat(reason.fixedLimit ? FIXED_LIMIT_OVERLOAD_LOG_FORMAT : OVERLOAD_LOG_FORMAT)
                    .setArguments(rejected, reason.description, reason.property, limit, reason.outcome, overloadLog.total.get())
            );
        }
        run(onRejected, port);
    }

    /**
     * Runs a blocking static file read on the bounded file read pool: inline when {@code inline} is set or this
     * scheduler is synchronous, and {@code onRejected} instead once {@link #MAX_QUEUED_FILE_READS} reads are
     * waiting or the scheduler has been shut down.
     */
    public void submitStaticFileRead(Runnable command, Runnable onRejected, boolean inline) {
        submitFileRead(OverloadReason.STATIC_FILES, command, onRejected, inline);
    }

    /**
     * As {@link #submitStaticFileRead}, for the response of a matched expectation whose body is a FILE body,
     * so the file is read on the same bounded pool and a refusal is counted as {@code file_bodies}.
     */
    public void submitFileBodyRead(Runnable command, Runnable onRejected, boolean inline) {
        submitFileRead(OverloadReason.FILE_BODIES, command, onRejected, inline);
    }

    private void submitFileRead(OverloadReason reason, Runnable command, Runnable onRejected, boolean inline) {
        Integer port = getPort();
        if (this.synchronous || inline) {
            run(command, port);
            return;
        }
        ThreadPoolExecutor executor = fileReadExecutor();
        int limit = maxQueuedFileReads;
        if (executor == null) {
            run(onRejected, port);
            return;
        }
        if (!tryAdmit(queuedFileReads, 1, limit)) {
            rejectForOverload(reason, limit, onRejected, port);
            return;
        }
        try {
            executor.execute(() -> {
                queuedFileReads.decrementAndGet();
                run(command, port);
            });
        } catch (RejectedExecutionException shutDown) {
            queuedFileReads.decrementAndGet();
            run(onRejected, port);
        }
    }

    private ThreadPoolExecutor fileReadExecutor() {
        ThreadPoolExecutor executor = fileReadExecutor;
        if (executor != null) {
            return executor;
        }
        synchronized (this) {
            if (fileReadExecutor == null && !stopped) {
                fileReadExecutor = new ThreadPoolExecutor(
                    FILE_READ_THREADS, FILE_READ_THREADS,
                    0L, TimeUnit.MILLISECONDS,
                    new LinkedBlockingQueue<>(),
                    new SchedulerThreadFactory("FileRead")
                );
            }
            return fileReadExecutor;
        }
    }

    /**
     * @return the file read pool, or {@code null} before the first file read is submitted
     */
    @VisibleForTesting
    public ExecutorService getFileReadExecutor() {
        return fileReadExecutor;
    }

    @VisibleForTesting
    public void maxQueuedFileReads(int maxQueuedFileReads) {
        this.maxQueuedFileReads = maxQueuedFileReads;
    }

    public synchronized void shutdown() {
        stopped = true;
        if (fileReadExecutor != null && !fileReadExecutor.isShutdown()) {
            // a read still waiting would answer a client of a stopped server; their connections are closing
            fileReadExecutor.shutdownNow();
            try {
                fileReadExecutor.awaitTermination(500, MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore interrupted exception
            }
        }
        // Both executors are null in synchronous mode (WAR/servlet) — guard both so shutdown() is a
        // safe no-op there, matching the localCallbackExecutor guard below.
        if (scheduler != null && !scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                scheduler.awaitTermination(500, MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore interrupted exception
            }
        }
        if (localCallbackExecutor != null && !localCallbackExecutor.isShutdown()) {
            localCallbackExecutor.shutdown();
            try {
                localCallbackExecutor.awaitTermination(500, MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore interrupted exception
            }
        }
        if (templateActionExecutor != null && !templateActionExecutor.isShutdown()) {
            templateActionExecutor.shutdown();
            try {
                templateActionExecutor.awaitTermination(500, MILLISECONDS);
            } catch (InterruptedException ignore) {
                // ignore interrupted exception
            }
        }
    }

    private void run(Runnable command, Integer port) {
        setPort(port);
        try {
            command.run();
        } catch (Throwable throwable) {
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(WARN)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat(throwable.getMessage())
                        .setThrowable(throwable)
                );
            }
        }
    }

    public void submitAsync(Runnable command, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (scheduler != null) {
            if (delayMillis > 0) {
                scheduleAfterDelay(() -> run(command, port), rejectableOf(command), delayMillis, port);
            } else {
                scheduler.submit(() -> run(command, port));
            }
        } else {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while applying delay", ie);
                }
            }
            run(command, port);
        }
    }

    /**
     * Run {@code command} after the combined delay: inline when there is none, otherwise on the shared
     * scheduler once the delay elapses. A {@link #rejectable}, {@link #delayOptional} or {@link #sheddable}
     * command runs its fallback instead when its budget of {@code maxPendingDelayedResponses} is full; the same
     * applies to
     * {@link #scheduleLocalCallback} and {@link #scheduleTemplateAction}, whose template queue is additionally
     * bounded by {@code maxQueuedTemplateActions}.
     */
    public void schedule(Runnable command, boolean synchronous, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while apply delay to response", ie);
                }
            }
            run(command, port);
        } else {
            if (delayMillis > 0) {
                scheduleAfterDelay(() -> run(command, port), rejectableOf(command), delayMillis, port);
            } else {
                run(command, port);
            }
        }
    }

    /**
     * Dispatch a LOCAL (in-JVM) object/class callback so that — in asynchronous (Netty) mode — its
     * potentially-BLOCKING body never runs on the server worker event loop and never consumes the
     * bounded scheduler pool.
     * <p>
     * In asynchronous mode the callback is run on the dedicated, unbounded {@link #localCallbackExecutor}
     * (see its field javadoc): this both moves the blocking loopback off the worker thread (so the
     * loopback's reply can be read on a now-free worker) and guarantees a recursively-triggered inner
     * local callback always gets its own thread, so the only failure mode is a BOUNDED wait, never a
     * pool-exhaustion deadlock. An optional delay is honoured on the shared scheduled executor first
     * (it only occupies a timer thread until it fires), after which the body hops to the cached pool.
     * <p>
     * In synchronous mode (WAR/servlet, or the unit-test {@code synchronous=true} path) the body runs
     * INLINE after any delay, exactly as the equivalent {@link #schedule} call would, so the response is
     * written before the caller returns and blocking-model deployments keep their semantics unchanged.
     * <p>
     * Whatever thread the body ends up on, it runs through {@link #run(Runnable, Integer)} with the
     * captured loop-prevention port restored, so response routing/{@code HttpState.getPort()} behave
     * identically to the existing scheduler paths.
     */
    public void scheduleLocalCallback(Runnable command, boolean synchronous, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while applying delay to local callback", ie);
                }
            }
            run(command, port);
        } else if (delayMillis > 0) {
            scheduleAfterDelay(() -> localCallbackExecutor.execute(() -> run(command, port)), rejectableOf(command), delayMillis, port);
        } else {
            localCallbackExecutor.execute(() -> run(command, port));
        }
    }

    /**
     * Dispatch a template ACTION (RESPONSE_TEMPLATE / FORWARD_TEMPLATE render-and-write) so that — in
     * asynchronous (Netty) mode — its synchronous, CPU-bound render never runs on the server worker event
     * loop and never consumes the bounded shared scheduler pool.
     * <p>
     * In asynchronous mode the body runs on the dedicated, BOUNDED {@link #templateActionExecutor} (see its
     * field javadoc): unlike the ordinary {@link #schedule} path — which runs a zero-delay command inline on
     * the calling worker thread, and a delayed command on the shared scheduler pool — this always moves the
     * whole render off both of those shared resources, so a burst of slow renders can only ever back up
     * against other renders and never stalls unrelated match/forward traffic. This is deliberately the WHOLE
     * action dispatch, not just the render call: offloading only the render and then blocking the calling
     * thread on its result would leave the worker event-loop thread blocked for the render duration and buy
     * nothing. An optional delay is honoured on the shared scheduled executor first (it only occupies a timer
     * thread until it fires), after which the body hops to the bounded template pool — so a templated
     * response with a configured delay still honours that delay exactly.
     * <p>
     * In synchronous mode (WAR/servlet, or the unit-test {@code synchronous=true} path) the body runs INLINE
     * after any delay, exactly as the equivalent {@link #schedule} call would, so the response is written
     * before the caller returns and blocking-model deployments keep their semantics unchanged.
     * <p>
     * Whatever thread the body ends up on, it runs through {@link #run(Runnable, Integer)} with the captured
     * loop-prevention port restored, so response routing/{@code HttpState.getPort()} behave identically to
     * the existing scheduler paths.
     */
    public void scheduleTemplateAction(Runnable command, boolean synchronous, Delay... delays) {
        long delayMillis = sampleCombinedDelayMillis(delays);
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    throw new RuntimeException("InterruptedException while applying delay to template action", ie);
                }
            }
            run(command, port);
        } else if (delayMillis > 0) {
            RejectableTask rejectable = rejectableOf(command);
            scheduleAfterDelay(() -> executeTemplateAction(command, rejectable, port), rejectable, delayMillis, port);
        } else {
            executeTemplateAction(command, rejectableOf(command), port);
        }
    }

    /**
     * Send one WebSocket reply set: each write runs once its own delay (measured from now) has elapsed, strictly
     * in the given order, and never concurrently with another write of the same set. The set's delayed writes
     * are admitted or refused together against their own budget of {@code maxPendingDelayedResponses}: a set is
     * admitted while fewer than that many reply frames are waiting, so a reply is never delivered in part.
     * Writes whose delay has already elapsed run on the calling thread.
     *
     * @param writes       the frame writes, in send order
     * @param delaysMillis each write's delay from now; must be non-decreasing
     * @param stopped      checked before each write; once true the rest of the set is dropped (e.g. socket closed)
     * @param onRefused    runs on the calling thread, instead of any write, when the set is refused
     * @param onFinished   runs once an admitted set has run, or dropped, its last write
     * @return whether the set was admitted
     */
    public boolean scheduleReplySet(List<Runnable> writes, long[] delaysMillis, BooleanSupplier stopped, Runnable onRefused, Runnable onFinished) {
        Integer port = getPort();
        long startNanos = System.nanoTime();
        int delayed = 0;
        for (long delayMillis : delaysMillis) {
            if (delayMillis > 0) {
                delayed++;
            }
        }
        boolean counted = scheduler != null && delayed > 0;
        if (counted && !tryAdmit(pendingWebSocketReplyFrames, delayed, maxPendingDelayedResponses)) {
            rejectForOverload(OverloadReason.WEBSOCKET_REPLIES, maxPendingDelayedResponses, onRefused, port);
            return false;
        }
        new ReplySet(writes, delaysMillis, stopped, onFinished, port, startNanos, counted).runFrom(0);
        return true;
    }

    private final class ReplySet {
        private final List<Runnable> writes;
        private final long[] delaysMillis;
        private final BooleanSupplier stopped;
        private final Runnable onFinished;
        private final Integer port;
        private final long startNanos;
        private final boolean counted;

        private ReplySet(List<Runnable> writes, long[] delaysMillis, BooleanSupplier stopped, Runnable onFinished, Integer port, long startNanos, boolean counted) {
            this.writes = writes;
            this.delaysMillis = delaysMillis;
            this.stopped = stopped;
            this.onFinished = onFinished;
            this.port = port;
            this.startNanos = startNanos;
            this.counted = counted;
        }

        private void runFrom(int index) {
            int next = index;
            while (next < writes.size()) {
                if (stopped.getAsBoolean()) {
                    releaseFrom(next);
                    run(onFinished, port);
                    return;
                }
                long waitNanos = startNanos + MILLISECONDS.toNanos(delaysMillis[next]) - System.nanoTime();
                if (waitNanos > 0) {
                    if (scheduler == null) {
                        sleep(waitNanos);
                    } else {
                        int resumeAt = next;
                        try {
                            scheduler.schedule(() -> runFrom(resumeAt), waitNanos, NANOSECONDS);
                        } catch (RuntimeException exception) {
                            releaseFrom(next);
                            throw exception;
                        }
                        return;
                    }
                }
                if (counted && delaysMillis[next] > 0) {
                    pendingWebSocketReplyFrames.decrementAndGet();
                }
                run(writes.get(next), port);
                next++;
            }
            run(onFinished, port);
        }

        private void releaseFrom(int index) {
            if (counted) {
                int remaining = 0;
                for (int i = index; i < delaysMillis.length; i++) {
                    if (delaysMillis[i] > 0) {
                        remaining++;
                    }
                }
                pendingWebSocketReplyFrames.addAndGet(-remaining);
            }
        }

        private void sleep(long nanos) {
            try {
                NANOSECONDS.sleep(nanos);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("InterruptedException while applying delay to WebSocket reply", ie);
            }
        }
    }

    /**
     * A delayed task that can be cancelled before it runs; see {@link #scheduleCancellable}.
     */
    public static final class PendingTask {
        private final AtomicInteger pendingCount;
        private final AtomicBoolean pending = new AtomicBoolean(true);
        private volatile Future<?> future;

        private PendingTask(AtomicInteger pendingCount) {
            this.pendingCount = pendingCount;
        }

        private boolean claim() {
            if (pending.compareAndSet(true, false)) {
                if (pendingCount != null) {
                    pendingCount.decrementAndGet();
                }
                return true;
            }
            return false;
        }

        /**
         * Stop the task if it has not started; with the scheduler's remove-on-cancel policy its queue slot is
         * freed at once.
         */
        public void cancel() {
            if (claim()) {
                Future<?> scheduled = future;
                if (scheduled != null) {
                    scheduled.cancel(false);
                }
            }
        }
    }

    /**
     * Run {@code command} on the shared scheduler once {@code delayMillis} has elapsed, unless
     * {@link PendingTask#cancel() cancelled} first. Counted in {@link #getPendingDelayedTaskCount()} but never
     * refused: callers keep at most one per key (e.g. one timed transition per scenario) and cancel the one they
     * replace. In synchronous mode it sleeps and runs inline.
     */
    public PendingTask scheduleCancellable(Runnable command, long delayMillis) {
        Integer port = getPort();
        if (scheduler == null) {
            if (delayMillis > 0) {
                try {
                    MILLISECONDS.sleep(delayMillis);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("InterruptedException while applying delay", ie);
                }
            }
            PendingTask task = new PendingTask(null);
            if (task.claim()) {
                run(command, port);
            }
            return task;
        }
        PendingTask task = new PendingTask(pendingUnboundedDelayedTasks);
        pendingUnboundedDelayedTasks.incrementAndGet();
        try {
            task.future = scheduler.schedule(() -> {
                if (task.claim()) {
                    run(command, port);
                }
            }, Math.max(0, delayMillis), MILLISECONDS);
        } catch (RuntimeException exception) {
            task.claim();
            throw exception;
        }
        if (!task.pending.get()) {
            // cancelled by another thread before the future was published
            task.future.cancel(false);
        }
        return task;
    }

    private long sampleCombinedDelayMillis(Delay... delays) {
        if (delays == null || delays.length == 0) {
            return 0;
        } else if (delays.length == 1 && delays[0] != null) {
            return delays[0].sampleValueMillis();
        } else if (delays.length == 2 && delays[0] == delays[1]) {
            return delays[0] != null ? delays[0].sampleValueMillis() : 0;
        } else {
            long timeInMilliseconds = 0;
            for (Delay delay : delays) {
                if (delay != null) {
                    timeInMilliseconds = Math.min(Long.MAX_VALUE, timeInMilliseconds + delay.sampleValueMillis());
                }
            }
            return timeInMilliseconds;
        }
    }

    public void submit(Runnable command) {
        submit(command, false);
    }

    public void submit(Runnable command, boolean synchronous) {
        Integer port = getPort();
        if (this.synchronous || synchronous) {
            run(command, port);
        } else {
            scheduler.submit(() -> run(command, port));
        }
    }

    public void submit(HttpForwardActionResult future, Runnable command, boolean synchronous, Predicate<Throwable> logException) {
        Integer port = getPort();
        if (future != null) {
            if (this.synchronous || synchronous) {
                try {
                    future.getHttpResponse().get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
                } catch (TimeoutException e) {
                    future.getHttpResponse().completeExceptionally(new SocketCommunicationException("Response was not received after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to make the proxy wait longer please use \"mockserver.maxSocketTimeout\" system property or configuration.maxSocketTimeout(long milliseconds)", e.getCause()));
                } catch (InterruptedException | ExecutionException ex) {
                    future.getHttpResponse().completeExceptionally(ex);
                }
                run(command, port);
            } else {
                boundedForwardResponse(future).whenCompleteAsync((httpResponse, throwable) -> {
                    if (throwable != null && mockServerLogger.isEnabledForInstance(Level.INFO) && logException.test(throwable)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(WARN)
                                .setLogLevel(Level.INFO)
                                .setMessageFormat(throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                    run(command, port);
                }, scheduler);
            }
        }
    }

    public void submit(CompletableFuture<BinaryMessage> future, Runnable command, boolean synchronous) {
        Integer port = getPort();
        if (future != null) {
            if (this.synchronous || synchronous) {
                try {
                    future.get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
                } catch (TimeoutException e) {
                    future.completeExceptionally(new SocketCommunicationException("Response was not received after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to make the proxy wait longer please use \"mockserver.maxSocketTimeout\" system property or ConfigurationProperties.maxSocketTimeout(long milliseconds)", e.getCause()));
                } catch (InterruptedException | ExecutionException ex) {
                    future.completeExceptionally(ex);
                }
                run(command, port);
            } else {
                future.whenCompleteAsync((httpResponse, throwable) -> command.run(), scheduler);
            }
        }
    }

    public void submit(HttpForwardActionResult future, BiConsumer<HttpResponse, Throwable> consumer, boolean synchronous) {
        if (future != null) {
            if (this.synchronous || synchronous) {
                HttpResponse httpResponse = null;
                Throwable exception = null;
                try {
                    httpResponse = future.getHttpResponse().get(configuration.maxSocketTimeoutInMillis(), MILLISECONDS);
                } catch (TimeoutException e) {
                    exception = new SocketCommunicationException("Response was not received after " + configuration.maxSocketTimeoutInMillis() + " milliseconds, to make the proxy wait longer please use \"mockserver.maxSocketTimeout\" system property or ConfigurationProperties.maxSocketTimeout(long milliseconds)", e.getCause());
                } catch (InterruptedException | ExecutionException ex) {
                    exception = ex;
                }
                try {
                    consumer.accept(httpResponse, exception);
                } catch (Throwable throwable) {
                    if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setType(WARN)
                                .setLogLevel(Level.INFO)
                                .setMessageFormat(throwable.getMessage())
                                .setThrowable(throwable)
                        );
                    }
                }
            } else {
                boundedForwardResponse(future).whenCompleteAsync(consumer, scheduler);
            }
        }
    }

    /**
     * Backstops the async wait on a forward/proxy response future so a connected-but-silent upstream (which has
     * no read timeout when {@code maxSocketTimeout=0} or on a pooled channel) cannot leave the continuation
     * pending and hang the client forever. It restores, without pinning a pool thread, the completion guarantee
     * the removed blocking {@code get(maxFutureTimeout)} gave: {@code orTimeout} completes the future
     * exceptionally with a {@link TimeoutException} that the caller maps to a 502. The read timeout at
     * {@code maxSocketTimeout} normally completes the future first. Safe for streaming - the future completes at
     * the response head, where {@code orTimeout} is cancelled, so a long SSE body is never truncated.
     */
    private CompletableFuture<HttpResponse> boundedForwardResponse(HttpForwardActionResult future) {
        return future.getHttpResponse().orTimeout(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
    }

}

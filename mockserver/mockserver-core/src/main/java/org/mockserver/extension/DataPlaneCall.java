package org.mockserver.extension;

import org.mockserver.mock.HttpState;
import org.mockserver.model.DataPlaneCopies;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.scheduler.Scheduler;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.mockserver.extension.DataPlaneOutcome.AT_CAPACITY;
import static org.mockserver.extension.DataPlaneOutcome.CONTRACT_VIOLATION;
import static org.mockserver.extension.DataPlaneOutcome.RETURNED_NULL;
import static org.mockserver.extension.DataPlaneOutcome.STOPPING;
import static org.mockserver.extension.DataPlaneOutcome.THREW;
import static org.mockserver.extension.DataPlaneOutcome.TIMEOUT;

/**
 * One run of a request filter chain or a response transformer chain.
 * <p>
 * Admission is a non-blocking {@code tryAcquire} of one slot, so a hook calling its own server at the cap is refused
 * rather than deadlocked. The budget's timer runs on the scheduler, and a timer the scheduler rejects means the server
 * is stopping. Whichever of the chain, the timer and {@link DataPlaneHooks#stop()} completes the call first wins (the
 * once-guard); the others are discarded. The timer and stop interrupt the hook thread only while it is inside extension
 * code, and the slot is released only when that thread returns.
 */
final class DataPlaneCall implements Runnable {

    /**
     * How far the chain has got, published as one object so the timer never sees one hook's position with another's
     * input.
     */
    private static final class Progress {
        final int index;
        final Object input;
        final List<String> changedBy;

        Progress(int index, Object input, List<String> changedBy) {
            this.index = index;
            this.input = input;
            this.changedBy = changedBy;
        }
    }

    private final DataPlaneHooks hooks;
    private final boolean filterChain;
    private final List<DataPlaneHooks.Hook> chain;
    private final HttpRequest request;
    private final HttpResponse response;
    private final FilterContext context;
    private final Scheduler scheduler;
    private final Consumer<DataPlaneOutcome> onDone;
    private final boolean callerWaits;
    private final Integer port;
    private final AtomicBoolean done = new AtomicBoolean();
    private volatile Progress progress;
    private long deadlineNanos;
    // guarded by this
    private Thread worker;
    private boolean inExtensionCode;
    private boolean finished;
    private ScheduledFuture<?> timer;

    DataPlaneCall(DataPlaneHooks hooks, boolean filterChain, List<DataPlaneHooks.Hook> chain, HttpRequest request, HttpResponse response, FilterContext context, Scheduler scheduler, Consumer<DataPlaneOutcome> onDone, boolean callerWaits) {
        this.hooks = hooks;
        this.filterChain = filterChain;
        this.chain = chain;
        this.request = request;
        this.response = response;
        this.context = context;
        this.scheduler = scheduler;
        this.onDone = onDone;
        this.callerWaits = callerWaits;
        this.port = HttpState.getPort();
        this.progress = new Progress(0, filterChain ? request : response, List.of());
    }

    void start() {
        if (hooks.isStopping()) {
            complete(stopping(), null);
            return;
        }
        if (!hooks.slots.tryAcquire()) {
            refuseAtCapacity();
            return;
        }
        deadlineNanos = System.nanoTime() + MILLISECONDS.toNanos(hooks.timeoutMillis);
        hooks.active.add(this);
        ScheduledExecutorService timerService = scheduler != null ? scheduler.getExecutorService() : null;
        if (timerService != null) {
            ScheduledFuture<?> scheduled;
            try {
                scheduled = timerService.schedule(this::timeOut, hooks.timeoutMillis, MILLISECONDS);
            } catch (RejectedExecutionException schedulerStopped) {
                abandon();
                return;
            }
            // the scheduler's CallerRunsPolicy silently discards a task once it is shut down, so check after scheduling
            if (timerService.isShutdown()) {
                scheduled.cancel(false);
                abandon();
                return;
            }
            boolean cancel;
            synchronized (this) {
                timer = scheduled;
                cancel = done.get();
            }
            if (cancel) {
                scheduled.cancel(false);
            }
        } else if (!callerWaits) {
            // nothing would end the budget of a caller that does not wait, so it is refused like a stopped scheduler
            abandon();
            return;
        }
        try {
            hooks.executor.execute(this);
        } catch (RejectedExecutionException poolStopped) {
            abandon();
        }
    }

    private void abandon() {
        hooks.active.remove(this);
        hooks.slots.release();
        complete(stopping(), null);
    }

    private void refuseAtCapacity() {
        hooks.recordRejection();
        DataPlaneOutcome outcome = everyEnabledHookFailsOpenFrom(0) ? unchanged(progress, true) : failed(null, AT_CAPACITY, DataPlaneHooks.overloadedResponse());
        if (scheduler != null) {
            scheduler.refuseForOverload(Scheduler.OverloadReason.EXTENSION_DATAPLANE, hooks.maxInFlight, () -> complete(outcome, null));
        } else {
            complete(outcome, null);
        }
    }

    /**
     * For a caller that answers on its own thread: waits for the outcome for what is left of the budget, then ends
     * the call as timed out.
     */
    DataPlaneOutcome await(CompletableFuture<DataPlaneOutcome> result) {
        try {
            return result.get(Math.max(0L, deadlineNanos - System.nanoTime()), NANOSECONDS);
        } catch (TimeoutException budgetUsed) {
            timeOut();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            stopNow();
        } catch (ExecutionException impossible) {
            throw new IllegalStateException(impossible);
        }
        // complete: either just now, or by the chain finishing as the budget ended
        return result.join();
    }

    @Override
    public void run() {
        synchronized (this) {
            worker = Thread.currentThread();
        }
        HttpState.setPort(port);
        DataPlaneOutcome outcome = null;
        Object[] failure = new Object[1];
        try {
            if (!done.get()) {
                outcome = hooks.isStopping() ? stopping() : filterChain ? runFilters(failure) : runTransformers(failure);
            }
        } catch (Throwable unexpected) {
            outcome = failed(currentHook(), THREW, null);
            failure[0] = unexpected;
        } finally {
            synchronized (this) {
                worker = null;
                finished = true;
            }
            Thread.interrupted();
            HttpState.setPort((Integer) null);
            hooks.active.remove(this);
            // released before the outcome is acted on, so a continuation or a transformer never holds two slots
            hooks.slots.release();
        }
        if (outcome != null && !complete(outcome, (Throwable) failure[0]) && !STOPPING.equals(outcome.failureType())) {
            hooks.recordLateResult();
        }
    }

    private DataPlaneOutcome runFilters(Object[] failure) {
        HttpRequest input = request;
        List<String> changedBy = new ArrayList<>();
        for (int i = 0; i < chain.size(); i++) {
            DataPlaneHooks.Hook hook = chain.get(i);
            if (!hook.isEnabled()) {
                continue;
            }
            progress = new Progress(i, input, List.copyOf(changedBy));
            if (done.get()) {
                return null;
            }
            HttpRequest copy = DataPlaneCopies.copyForFilter(input);
            RequestFilter filter = (RequestFilter) hook.extension.extension();
            FilterResult result = null;
            Throwable thrown = null;
            try {
                result = invoke(() -> filter.filter(copy, context));
            } catch (Throwable throwable) {
                thrown = throwable;
            }
            if (done.get()) {
                hooks.recordLateResult();
                return null;
            }
            String failureType = null;
            HttpRequest next = input;
            HttpResponse answer = null;
            if (thrown != null) {
                failureType = THREW;
            } else if (result == null) {
                failureType = RETURNED_NULL;
            } else {
                try {
                    // accepted as a fresh copy, so the server never holds an object the extension can still reach
                    if (result.isRespond()) {
                        answer = DataPlaneCopies.normalise(result.response());
                    } else if (result.request() != null) {
                        next = DataPlaneCopies.acceptFilteredRequest(result.request(), input, request);
                    }
                } catch (Throwable uncopyable) {
                    failureType = THREW;
                    thrown = uncopyable;
                }
                if (answer != null && DataPlaneHooks.isInterim(answer)) {
                    // an interim response would leave the exchange with no final one
                    answer = null;
                    failureType = CONTRACT_VIOLATION;
                    thrown = new ExtensionException("extension " + hook.name() + " answered with an interim (1xx) response");
                }
                if (answer != null) {
                    progress = new Progress(chain.size(), input, List.copyOf(changedBy));
                    return DataPlaneOutcome.respond(input, answer, hook.name(), changedBy);
                }
                String violation = failureType == null ? DataPlaneHooks.filterContractViolation(input, next, context) : null;
                if (violation != null) {
                    failureType = CONTRACT_VIOLATION;
                    thrown = new ExtensionException("extension " + hook.name() + " " + violation);
                }
            }
            if (failureType != null) {
                if (!hook.failOpen) {
                    failure[0] = thrown;
                    return failed(hook, failureType, null);
                }
                hooks.recordFailure(hook, failureType, correlationId(), request, null, thrown);
                continue;
            }
            // an unchanged result is dropped for its input, which no extension has seen
            if (DataPlaneCopies.changed(next, input)) {
                changedBy.add(hook.name());
                input = next;
            }
        }
        progress = new Progress(chain.size(), input, List.copyOf(changedBy));
        return DataPlaneOutcome.proceed(input, changedBy, false);
    }

    private DataPlaneOutcome runTransformers(Object[] failure) {
        HttpResponse input = response;
        List<String> changedBy = new ArrayList<>();
        TransformContext transformContext = (TransformContext) context;
        for (int i = 0; i < chain.size(); i++) {
            DataPlaneHooks.Hook hook = chain.get(i);
            if (!hook.isEnabled()) {
                continue;
            }
            progress = new Progress(i, input, List.copyOf(changedBy));
            if (done.get()) {
                return null;
            }
            HttpRequest requestCopy = request.deepCopy();
            HttpResponse copy = DataPlaneCopies.copyForTransformer(input);
            ResponseTransformer transformer = (ResponseTransformer) hook.extension.extension();
            HttpResponse result = null;
            Throwable thrown = null;
            try {
                result = invoke(() -> transformer.transform(requestCopy, copy, transformContext));
            } catch (Throwable throwable) {
                thrown = throwable;
            }
            if (done.get()) {
                hooks.recordLateResult();
                return null;
            }
            String failureType = null;
            if (thrown != null) {
                failureType = THREW;
            } else if (result == null) {
                failureType = RETURNED_NULL;
            } else {
                String violation = DataPlaneHooks.transformContractViolation(input, result, transformContext);
                if (violation != null) {
                    failureType = CONTRACT_VIOLATION;
                    thrown = new ExtensionException("extension " + hook.name() + " " + violation);
                }
            }
            if (failureType != null) {
                if (!hook.failOpen) {
                    failure[0] = thrown;
                    return failed(hook, failureType, null);
                }
                hooks.recordFailure(hook, failureType, correlationId(), request, null, thrown);
                continue;
            }
            HttpResponse accepted = DataPlaneCopies.acceptTransformedResponse(result, input);
            // an unchanged result keeps the input itself, so a chain of identity transformers writes what it was given
            if (DataPlaneCopies.changed(accepted, input)) {
                changedBy.add(hook.name());
                input = accepted;
            }
        }
        progress = new Progress(chain.size(), input, List.copyOf(changedBy));
        return DataPlaneOutcome.transformed(request, input, changedBy, false);
    }

    private <T> T invoke(Callable<T> extensionCode) throws Exception {
        synchronized (this) {
            inExtensionCode = true;
        }
        if (done.get()) {
            // the timer or stop() may have completed the call just before it was marked as in extension code
            Thread.currentThread().interrupt();
        }
        try {
            return extensionCode.call();
        } finally {
            synchronized (this) {
                inExtensionCode = false;
            }
            // the interrupt is for the extension code only, never for what MockServer does next on this thread
            Thread.interrupted();
        }
    }

    /**
     * Ends the call when its budget is used: {@code 500}, or the unchanged value when the hook that was running, and
     * every hook after it, fails open. Then interrupts the hook thread if it is in extension code.
     */
    void timeOut() {
        Thread stuck;
        synchronized (this) {
            if (finished) {
                return;
            }
            stuck = worker;
        }
        Progress at = progress;
        if (at.index >= chain.size()) {
            // the chain has returned and its result is about to complete the call
            return;
        }
        DataPlaneHooks.Hook hook = firstEnabledFrom(at.index);
        ExtensionException timedOut = new ExtensionException("extension " + (hook != null ? hook.name() : "") + " did not return within " + hooks.timeoutMillis + "ms (extensionDataPlaneTimeoutMillis)");
        if (stuck != null) {
            timedOut.setStackTrace(stuck.getStackTrace());
        }
        if (hook == null || everyEnabledHookFailsOpenFrom(at.index)) {
            if (complete(unchanged(at, false), null) && hook != null) {
                hooks.recordFailure(hook, TIMEOUT, correlationId(), request, null, timedOut);
            }
        } else {
            complete(failed(hook, TIMEOUT, null), timedOut);
        }
        interruptExtensionCode();
    }

    /**
     * Completes the call as stopping ({@code 503}), whatever its hooks' fail-open choice, and interrupts its thread if
     * it is in extension code.
     */
    void stopNow() {
        complete(stopping(), null);
        interruptExtensionCode();
    }

    private void interruptExtensionCode() {
        synchronized (this) {
            if (inExtensionCode && worker != null) {
                worker.interrupt();
            }
        }
    }

    /**
     * The once-guard: only the first outcome is acted on.
     *
     * @return true when this outcome won
     */
    private boolean complete(DataPlaneOutcome outcome, Throwable cause) {
        if (!done.compareAndSet(false, true)) {
            return false;
        }
        ScheduledFuture<?> timerToCancel;
        synchronized (this) {
            timerToCancel = timer;
        }
        if (timerToCancel != null) {
            timerToCancel.cancel(false);
        }
        // the hook thread, the timer and stop() each complete calls of any server, so the caller's port is set here
        Integer previousPort = HttpState.getPort();
        HttpState.setPort(port);
        try {
            if (outcome.isFailure() && !STOPPING.equals(outcome.failureType()) && !AT_CAPACITY.equals(outcome.failureType())) {
                hooks.recordFailure(hookNamed(outcome.extensionName()), outcome.failureType(), correlationId(), request, outcome.response(), cause);
            }
            onDone.accept(outcome);
        } catch (Throwable throwable) {
            hooks.logCallbackFailure(throwable);
        } finally {
            HttpState.setPort(previousPort);
        }
        return true;
    }

    private DataPlaneOutcome failed(DataPlaneHooks.Hook hook, String failureType, HttpResponse failureResponse) {
        HttpResponse sent = failureResponse != null ? failureResponse : hooks.failureResponse(hook, failureType, correlationId());
        return DataPlaneOutcome.failed(request, sent, hook != null ? hook.name() : null, failureType);
    }

    private DataPlaneOutcome stopping() {
        return DataPlaneOutcome.failed(request, DataPlaneHooks.stoppingResponse(), null, STOPPING);
    }

    private DataPlaneOutcome unchanged(Progress at, boolean skipped) {
        return filterChain
            ? DataPlaneOutcome.proceed((HttpRequest) at.input, at.changedBy, skipped)
            : DataPlaneOutcome.transformed(request, (HttpResponse) at.input, at.changedBy, skipped);
    }

    private boolean everyEnabledHookFailsOpenFrom(int index) {
        for (int i = index; i < chain.size(); i++) {
            DataPlaneHooks.Hook hook = chain.get(i);
            if (hook.isEnabled() && !hook.failOpen) {
                return false;
            }
        }
        return true;
    }

    private DataPlaneHooks.Hook firstEnabledFrom(int index) {
        for (int i = index; i < chain.size(); i++) {
            if (chain.get(i).isEnabled()) {
                return chain.get(i);
            }
        }
        return null;
    }

    private DataPlaneHooks.Hook currentHook() {
        int index = progress.index;
        return index < chain.size() ? chain.get(index) : chain.get(chain.size() - 1);
    }

    private DataPlaneHooks.Hook hookNamed(String name) {
        for (DataPlaneHooks.Hook hook : chain) {
            if (hook.name().equals(name)) {
                return hook;
            }
        }
        return currentHook();
    }

    private String correlationId() {
        if (context != null && context.correlationId() != null) {
            return context.correlationId();
        }
        return request != null ? request.getLogCorrelationId() : null;
    }
}

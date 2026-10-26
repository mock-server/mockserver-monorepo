package org.mockserver.extension;

import org.mockserver.configuration.Configuration;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.Expectation;
import org.mockserver.mock.HttpState;
import org.mockserver.mock.RequestMatchers;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.model.MediaType;
import org.mockserver.model.NottableString;
import org.mockserver.model.RequestDefinition;
import org.mockserver.responsewriter.ControlPlaneFailureResponse;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.slf4j.event.Level;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static io.netty.handler.codec.http.HttpResponseStatus.SERVICE_UNAVAILABLE;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;

/**
 * Serves the {@link AdminApiExtension} routes of one server under {@code /mockserver/extensions/<name>/}.
 * <p>
 * Built by {@link ExtensionRegistry#createAdminRoutes} only when a started extension implements the hook, so with no
 * such extension {@code HttpState} holds {@code null} and checks nothing else. Each call takes one of
 * {@code extensionAdminMaxInFlight} slots, or is answered {@code 503}, and keeps it until the handler's thread
 * returns, so the pool never has more threads than slots, however many handlers hang.
 */
public final class ExtensionAdminRoutes {

    public static final String PATH = HttpState.PATH_PREFIX + "/extensions/";
    private static final AtomicInteger INSTANCES = new AtomicInteger();

    /**
     * The control-plane authentication, authorization and audit decision for one call; on refusal it has already
     * written the 401 or 403.
     */
    public interface ControlPlaneGate {
        /**
         * @param readOnly whether the route only reads state; asked at most once, and only after authentication
         *                 succeeded, because it runs extension code
         */
        boolean allowed(HttpRequest request, ResponseWriter responseWriter, BooleanSupplier readOnly);
    }

    /**
     * Runs one call into extension code; the Netty path marks the thread interruptible by the timeout only for its
     * duration, so the timeout never interrupts MockServer's own authentication, audit or response writing.
     */
    private interface ExtensionInvoker {
        <T> T invoke(Callable<T> extensionCode) throws Exception;
    }

    private static final ExtensionInvoker INLINE = Callable::call;

    private final Map<String, LoadedExtension> extensionsByName;
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final Scheduler scheduler;
    private final ControlPlaneGate gate;
    private final int maxInFlight;
    private final long timeoutMillis;
    private final Semaphore slots;
    private final ThreadPoolExecutor executor;
    private final String threadNamePrefix;
    private final AtomicLong timeouts = new AtomicLong();
    private final AtomicLong lateResults = new AtomicLong();
    private final AtomicLong errors = new AtomicLong();
    private final Set<Call> activeCalls = ConcurrentHashMap.newKeySet();
    private volatile boolean stopping;

    ExtensionAdminRoutes(List<LoadedExtension> extensions, Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler, ControlPlaneGate gate) {
        Map<String, LoadedExtension> byName = new LinkedHashMap<>();
        for (LoadedExtension extension : extensions) {
            byName.put(extension.name(), extension);
        }
        this.extensionsByName = Collections.unmodifiableMap(byName);
        this.configuration = configuration;
        this.mockServerLogger = mockServerLogger;
        this.scheduler = scheduler;
        this.gate = gate;
        this.maxInFlight = configuration.extensionAdminMaxInFlight();
        this.timeoutMillis = configuration.extensionAdminTimeoutMillis();
        this.slots = new Semaphore(maxInFlight);
        // as many threads as slots and no more: a call only queues in the moment a finished call's thread is
        // returning to the pool after releasing its slot
        String poolName = "ext-admin-" + INSTANCES.incrementAndGet() + "-";
        this.threadNamePrefix = "MockServer-" + poolName;
        this.executor = new ThreadPoolExecutor(maxInFlight, maxInFlight, 60L, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), new Scheduler.SchedulerThreadFactory(poolName));
        this.executor.allowCoreThreadTimeOut(true);
    }

    /**
     * @return the names whose routes are served
     */
    public Set<String> names() {
        return extensionsByName.keySet();
    }

    /**
     * Serves the request when its path is under a loaded extension's name; otherwise does nothing. A CORS preflight
     * is left to the control plane's preflight handling, as for every other control-plane route.
     *
     * @return true when the request is (or will be) answered here
     */
    public boolean handle(HttpRequest request, ResponseWriter responseWriter, boolean warDeployment) {
        String path = request.getPath() != null ? request.getPath().getValue() : null;
        if (path == null || !path.startsWith(PATH) || CORSHeaders.isCorsPreflight(request)) {
            return false;
        }
        int nameEnd = path.indexOf('/', PATH.length());
        LoadedExtension extension = extensionsByName.get(nameEnd < 0 ? path.substring(PATH.length()) : path.substring(PATH.length(), nameEnd));
        if (extension == null || !extension.isHookEnabled(ExtensionHook.ADMIN_API)) {
            return false;
        }
        String subPath = nameEnd < 0 ? "/" : path.substring(nameEnd);
        if (!slots.tryAcquire()) {
            refuse(request, responseWriter);
            return true;
        }
        if (warDeployment) {
            try {
                serve(extension, request, subPath, responseWriter, INLINE);
            } finally {
                slots.release();
            }
            return true;
        }
        Call call = new Call(extension, request, subPath, new OnceResponseWriter(responseWriter, configuration, mockServerLogger), HttpState.getPort());
        // armed first: a call that nothing could time out is refused before it takes a thread
        if (!call.armTimeout()) {
            slots.release();
            answerStopping(request, call.responseWriter);
            return true;
        }
        try {
            executor.execute(call);
        } catch (RejectedExecutionException stopped) {
            call.cancelTimeout();
            slots.release();
            answerStopping(request, call.responseWriter);
        }
        return true;
    }

    private void serve(LoadedExtension extension, HttpRequest request, String subPath, ResponseWriter responseWriter, ExtensionInvoker invoker) {
        AdminApiExtension admin = (AdminApiExtension) extension.extension();
        String method = request.getMethod() != null ? request.getMethod().getValue() : "";
        ReadOnlyClassification readOnly = new ReadOnlyClassification(() -> invoker.invoke(() -> admin.isReadOnly(method, subPath)));
        HttpResponse response;
        try {
            if (!gate.allowed(request, responseWriter, readOnly)) {
                return;
            }
            // the audit swallows its own failures, so a throwing isReadOnly is answered here
            readOnly.rethrowFailure();
            if (responseWriter instanceof OnceResponseWriter && ((OnceResponseWriter) responseWriter).isWritten()) {
                return;
            }
            if (stopping) {
                answerStopping(request, responseWriter);
                return;
            }
            response = invoker.invoke(() -> admin.handle(request, subPath));
        } catch (Throwable throwable) {
            errors.incrementAndGet();
            ControlPlaneFailureResponse.writeUnexpectedFailure(mockServerLogger, responseWriter, request, throwable);
            return;
        }
        responseWriter.writeResponse(request, response, true);
    }

    private void refuse(HttpRequest request, ResponseWriter responseWriter) {
        Runnable answer = () -> responseWriter.writeResponse(request, HttpResponse.response()
            .withStatusCode(SERVICE_UNAVAILABLE.code())
            .withReasonPhrase(SERVICE_UNAVAILABLE.reasonPhrase())
            .withHeader("Retry-After", "1")
            .withBody("MockServer is overloaded, too many extension admin route calls are already in flight (extensionAdminMaxInFlight)", MediaType.PLAIN_TEXT_UTF_8), true);
        if (scheduler != null) {
            scheduler.refuseForOverload(Scheduler.OverloadReason.EXTENSION_ADMIN, maxInFlight, answer);
        } else {
            answer.run();
        }
    }

    /**
     * Logs a WARN for each expectation, loaded at start-up, whose path a loaded extension's routes take over, so that
     * expectation no longer matches. Only literal and regex paths are checked.
     */
    public void warnOnShadowedExpectations(RequestMatchers requestMatchers) {
        if (!mockServerLogger.isEnabledForInstance(Level.WARN)) {
            return;
        }
        for (Expectation expectation : requestMatchers.retrieveActiveExpectations(null)) {
            RequestDefinition definition = expectation.getHttpRequest();
            if (!(definition instanceof HttpRequest) || ((HttpRequest) definition).getPath() == null) {
                continue;
            }
            NottableString path = ((HttpRequest) definition).getPath();
            if (path.isNot() || path.getValue() == null) {
                continue;
            }
            for (String name : extensionsByName.keySet()) {
                if (takesOver(path.getValue(), name)) {
                    mockServerLogger.logEvent(new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("expectation:{}with path:{}will not match because extension:{}serves the control-plane routes under " + PATH + " with its name")
                        .setArguments(expectation.getId(), path.getValue(), name));
                }
            }
        }
    }

    static boolean takesOver(String expectationPath, String name) {
        String root = PATH + name;
        if (expectationPath.equals(root) || expectationPath.startsWith(root + "/")) {
            return true;
        }
        try {
            Pattern pattern = Pattern.compile(expectationPath);
            return pattern.matcher(root).matches() || pattern.matcher(root + "/").matches();
        } catch (PatternSyntaxException notARegex) {
            return false;
        }
    }

    /**
     * Stops taking calls and interrupts the calls that are inside extension code. MockServer's own work on a call
     * (authentication, audit, writing the response) is never interrupted, so it cannot, for example, close the audit
     * file sink's channel; a call that has not reached the extension yet is answered {@code 503}.
     */
    public void stop() {
        stopping = true;
        executor.shutdown();
        for (Call call : activeCalls) {
            call.interruptExtensionCode();
        }
    }

    private void answerStopping(HttpRequest request, ResponseWriter responseWriter) {
        responseWriter.writeResponse(request, HttpResponse.response()
            .withStatusCode(SERVICE_UNAVAILABLE.code())
            .withReasonPhrase(SERVICE_UNAVAILABLE.reasonPhrase())
            .withBody("MockServer is stopping", MediaType.PLAIN_TEXT_UTF_8), true);
    }

    /**
     * @return calls answered {@code 500} because the handler had not answered within {@code extensionAdminTimeoutMillis}
     */
    public long timeoutCount() {
        return timeouts.get();
    }

    /**
     * @return responses a handler returned after its call had already been answered, which were discarded
     */
    public long lateResultCount() {
        return lateResults.get();
    }

    /**
     * @return calls whose handler threw
     */
    public long errorCount() {
        return errors.get();
    }

    /**
     * @return the start of the name of every thread this server's admin routes run on
     */
    public String threadNamePrefix() {
        return threadNamePrefix;
    }

    /**
     * @return slots free now
     */
    public int availableSlots() {
        return slots.availablePermits();
    }

    /**
     * The extension's {@link AdminApiExtension#isReadOnly} answer, asked at most once and only when the gate needs
     * it, which is after authentication.
     */
    private static final class ReadOnlyClassification implements BooleanSupplier {
        private final Callable<Boolean> classifier;
        private boolean evaluated;
        private boolean readOnly;
        private Throwable failure;

        private ReadOnlyClassification(Callable<Boolean> classifier) {
            this.classifier = classifier;
        }

        @Override
        public boolean getAsBoolean() {
            if (!evaluated) {
                evaluated = true;
                try {
                    readOnly = Boolean.TRUE.equals(classifier.call());
                } catch (Throwable throwable) {
                    // any throw, an Error included, refuses the call: the audit would otherwise swallow it
                    failure = throwable;
                }
            }
            if (failure != null) {
                throw new ExtensionException("extension isReadOnly threw: " + failure, failure);
            }
            return readOnly;
        }

        void rethrowFailure() throws Throwable {
            if (failure != null) {
                throw failure;
            }
        }
    }

    private final class Call implements Runnable, ExtensionInvoker {
        private final LoadedExtension extension;
        private final HttpRequest request;
        private final String subPath;
        private final OnceResponseWriter responseWriter;
        private final Integer port;
        // guarded by this: the thread running the call, and whether it is inside extension code, so a timeout
        // interrupts only extension code and never MockServer's own authentication, audit or response writing
        private Thread worker;
        private boolean inExtensionCode;
        private boolean finished;
        private ScheduledFuture<?> timer;

        private Call(LoadedExtension extension, HttpRequest request, String subPath, OnceResponseWriter responseWriter, Integer port) {
            this.extension = extension;
            this.request = request;
            this.subPath = subPath;
            this.responseWriter = responseWriter;
            this.port = port;
        }

        @Override
        public void run() {
            synchronized (this) {
                worker = Thread.currentThread();
            }
            activeCalls.add(this);
            HttpState.setPort(port);
            try {
                if (stopping) {
                    answerStopping(request, responseWriter);
                } else if (!responseWriter.isWritten()) {
                    serve(extension, request, subPath, responseWriter, this);
                }
            } finally {
                activeCalls.remove(this);
                ScheduledFuture<?> timerToCancel;
                synchronized (this) {
                    worker = null;
                    finished = true;
                    timerToCancel = timer;
                }
                HttpState.setPort((Integer) null);
                if (timerToCancel != null) {
                    timerToCancel.cancel(false);
                }
                if (responseWriter.discardedLateWrite()) {
                    lateResults.incrementAndGet();
                }
                slots.release();
            }
        }

        @Override
        public <T> T invoke(Callable<T> extensionCode) throws Exception {
            synchronized (this) {
                inExtensionCode = true;
            }
            if (stopping) {
                // stop() may have run just before this call was marked as in extension code
                Thread.currentThread().interrupt();
            }
            try {
                return extensionCode.call();
            } finally {
                synchronized (this) {
                    inExtensionCode = false;
                }
                // a timeout's interrupt is for the extension code only, not for whatever MockServer does next
                Thread.interrupted();
            }
        }

        private void interruptExtensionCode() {
            synchronized (this) {
                if (inExtensionCode && worker != null) {
                    worker.interrupt();
                }
            }
        }

        /**
         * @return false when the scheduler is stopping, so no timer could end the call
         */
        private boolean armTimeout() {
            ScheduledExecutorService timerService = scheduler != null ? scheduler.getExecutorService() : null;
            if (timerService == null) {
                return true;
            }
            ScheduledFuture<?> scheduled = Scheduler.scheduleUnlessShutDown(timerService, this::timeOut, timeoutMillis, TimeUnit.MILLISECONDS);
            if (scheduled == null) {
                return false;
            }
            synchronized (this) {
                timer = scheduled;
            }
            return true;
        }

        private void cancelTimeout() {
            ScheduledFuture<?> scheduled;
            synchronized (this) {
                scheduled = timer;
            }
            if (scheduled != null) {
                scheduled.cancel(false);
            }
        }

        private void timeOut() {
            Thread stuck;
            synchronized (this) {
                if (finished) {
                    return;
                }
                stuck = worker;
            }
            if (!responseWriter.claim()) {
                return;
            }
            timeouts.incrementAndGet();
            ExtensionException timedOut = new ExtensionException("extension " + extension.name() + " did not answer " + subPath + " within " + timeoutMillis + "ms (extensionAdminTimeoutMillis)");
            if (stuck != null) {
                timedOut.setStackTrace(stuck.getStackTrace());
            }
            ControlPlaneFailureResponse.writeUnexpectedFailure(mockServerLogger, responseWriter.claimed(), request, timedOut);
            interruptExtensionCode();
        }
    }

    /**
     * Writes at most one response, so a timeout and a late handler can never both answer the call.
     */
    private static final class OnceResponseWriter extends ResponseWriter {
        private final ResponseWriter delegate;
        private final AtomicBoolean written = new AtomicBoolean();
        private volatile boolean discardedLateWrite;

        private OnceResponseWriter(ResponseWriter delegate, Configuration configuration, MockServerLogger mockServerLogger) {
            super(configuration, mockServerLogger);
            this.delegate = delegate;
        }

        boolean isWritten() {
            return written.get();
        }

        boolean claim() {
            return written.compareAndSet(false, true);
        }

        boolean discardedLateWrite() {
            return discardedLateWrite;
        }

        /**
         * @return the writer to use after {@link #claim()} succeeded
         */
        ResponseWriter claimed() {
            return delegate;
        }

        @Override
        public void writeResponse(HttpRequest request, HttpResponse response, boolean apiResponse) {
            if (claim()) {
                delegate.writeResponse(request, response, apiResponse);
            } else {
                discardedLateWrite = true;
            }
        }

        @Override
        public void sendResponse(HttpRequest request, HttpResponse response) {
            if (claim()) {
                delegate.sendResponse(request, response);
            } else {
                discardedLateWrite = true;
            }
        }

        @Override
        public Runnable respondingDirectly() {
            return delegate.respondingDirectly();
        }
    }
}

package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.helpers.RequestBodyExtractionHelper;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;

import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * Holder class for the GraalVM Polyglot API. Loaded lazily by {@link JavaScriptTemplateEngine#executeTemplate}
 * only when {@code POLYGLOT_AVAILABLE} is true. Keeping the {@code org.graalvm.polyglot.*} static
 * references in a separate class ensures the standard MockServer distribution (which does not bundle
 * GraalVM) can still load {@code JavaScriptTemplateEngine} and degrade gracefully.
 */
final class PolyglotRunner {

    /**
     * Host access policy for template evaluation: everything {@link HostAccess#ALL} permits EXCEPT the
     * members of {@link Class} and {@link ClassLoader}.
     *
     * <p>Real host objects are bound into the guest context (the built-in helpers such as {@code faker},
     * plus {@code jsonPath}/{@code xPath}), and under a plain {@code HostAccess.ALL} a template could walk
     * from any of them to a classloader and load whatever it liked —
     * {@code faker.getClass().getClassLoader().loadClass('java.lang.Runtime')} — reaching {@code Runtime}
     * WITHOUT ever going through {@code allowHostClassLookup}, so the class filter that
     * {@link JavaScriptTemplateEngine} applies would gate nothing. Denying the members of {@code Class} and
     * {@code ClassLoader} closes that walk, leaving host-class lookup as the only route to a Java class and
     * therefore making the class filter the single, complete gate (GHSA-7pwj-xvc2-hfpc).
     *
     * <p>This does not restrict ordinary template use: calling methods on the bound helpers is unaffected,
     * and {@code Java.type(...)} resolves an allowed class through the interop layer rather than through a
     * {@code java.lang.Class} instance.
     */
    private static final HostAccess HOST_ACCESS = HostAccess
        .newBuilder(HostAccess.ALL)
        .denyAccess(Class.class)
        .denyAccess(ClassLoader.class)
        .build();

    /**
     * Shared single-thread daemon scheduler that runs the per-evaluation timeout watchdogs. A
     * watchdog only ever calls {@link Context#close(boolean)} (which is thread-safe and cancels
     * any guest code currently executing), so one scheduler thread is sufficient regardless of how
     * many templates run concurrently. Daemon so it never blocks JVM shutdown.
     */
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "MockServer-JavaScriptTemplateTimeout");
        thread.setDaemon(true);
        return thread;
    });

    private PolyglotRunner() {
    }

    static <T> T run(
        String script,
        boolean includeResponse,
        HttpRequest request,
        HttpResponse response,
        org.mockserver.load.IterationContext iteration,
        Predicate<String> classFilter,
        ObjectMapper objectMapper,
        MockServerLogger mockServerLogger,
        HttpTemplateOutputDeserializer httpTemplateOutputDeserializer,
        Class<? extends DTO<T>> dtoClass,
        long executionTimeoutMillis,
        boolean rawText,
        Object fakerOverride
    ) {
        // rawText (streaming payload templating): coerce the handle(request) return value to text — a
        // returned string is emitted verbatim, any other value is JSON.stringify'd — instead of always
        // JSON.stringify'ing and then deserialising into a response object. rawText always implies the
        // no-response (single-argument) template shape.
        String serialiseFunction;
        if (rawText) {
            serialiseFunction = " function serialise(request) { var r = handle(JSON.parse(request)); return (r === null || r === undefined) ? '' : ((typeof r === 'string') ? r : JSON.stringify(r)); }";
        } else if (includeResponse) {
            serialiseFunction = " function serialise(request, response) { return JSON.stringify(handle(JSON.parse(request), JSON.parse(response)), null, 2); }";
        } else {
            serialiseFunction = " function serialise(request) { return JSON.stringify(handle(JSON.parse(request)), null, 2); }";
        }
        String fullScript = script + serialiseFunction;

        // HOST_ACCESS is HostAccess.ALL (equivalent to the previous JSR-223 polyglot.js.allowHostAccess=true)
        // minus every member of java.lang.Class and java.lang.ClassLoader — see HOST_ACCESS below. Together
        // with allowHostClassLookup(classFilter), which gates which classes Java.type(...) resolves, that is
        // the security boundary. HostAccess.EXPLICIT/CONSTRAINED would narrow the surface further but
        // require annotating template helper classes.
        // Watchdog cancellation: a runaway/malicious template (e.g. an infinite loop) would
        // otherwise pin this worker thread forever (GraalJS runs interpreter-only on stock
        // OpenJDK, so it can't even be JIT-sped). When executionTimeoutMillis > 0 we schedule a
        // watchdog that, on expiry, calls context.close(true) from the scheduler thread. That is
        // thread-safe and cancels the guest code currently executing on this thread, which then
        // throws a PolyglotException with isCancelled()==true — translated below into a clear,
        // logged timeout error. A 0 (or negative) timeout disables the watchdog (unbounded
        // behaviour). watchdogFired distinguishes "we cancelled it" from any other cancellation.
        final AtomicBoolean watchdogFired = new AtomicBoolean(false);

        try (Context context = Context.newBuilder("js")
            .allowHostAccess(HOST_ACCESS)
            .allowHostClassLookup(classFilter)
            .build()) {

            ScheduledFuture<?> watchdog = null;
            if (executionTimeoutMillis > 0) {
                watchdog = WATCHDOG.schedule(() -> {
                    watchdogFired.set(true);
                    try {
                        // cancelIfExecuting=true: abort any guest code currently running in this context
                        context.close(true);
                    } catch (Throwable ignore) {
                        // context may already be closing/closed on the worker thread — nothing to do
                    }
                }, executionTimeoutMillis, TimeUnit.MILLISECONDS);
            }

            try {
                return evaluate(
                    script, fullScript, includeResponse, request, response, iteration,
                    objectMapper, mockServerLogger, httpTemplateOutputDeserializer, dtoClass, context, rawText, fakerOverride
                );
            } catch (PolyglotException polyglotException) {
                if (watchdogFired.get() && (polyglotException.isCancelled() || polyglotException.isInterrupted())) {
                    String message = "JavaScript template execution exceeded the configured timeout of "
                        + executionTimeoutMillis + "ms and was cancelled; "
                        + "increase mockserver.javascriptTemplateExecutionTimeout or set it to 0 to disable the timeout";
                    if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setHttpRequest(request)
                                .setMessageFormat(message)
                        );
                    }
                    throw new JavaScriptTemplateTimeoutException(message, polyglotException);
                }
                throw polyglotException;
            } finally {
                // Cancel the watchdog on the normal-completion path so it can't fire late and so it
                // doesn't leak in the scheduler queue. mayInterruptIfRunning=false: if it has already
                // started closing the context we let it finish cleanly.
                if (watchdog != null) {
                    watchdog.cancel(false);
                }
            }
        }
    }

    private static <T> T evaluate(
        String script,
        String fullScript,
        boolean includeResponse,
        HttpRequest request,
        HttpResponse response,
        org.mockserver.load.IterationContext iteration,
        ObjectMapper objectMapper,
        MockServerLogger mockServerLogger,
        HttpTemplateOutputDeserializer httpTemplateOutputDeserializer,
        Class<? extends DTO<T>> dtoClass,
        Context context,
        boolean rawText,
        Object fakerOverride
    ) {
        {
            // In GraalVM Polyglot, context.getBindings("js") returns the JavaScript global scope
            // (not a separate host bindings layer). Both putMember (to inject host values like
            // BUILT_IN_HELPERS) and getMember (to retrieve JS-defined functions after context.eval)
            // operate on the same JS global object. Do not switch this to context.getPolyglotBindings()
            // — that's a different scope and serialise() would be invisible.
            Value jsBindings = context.getBindings("js");
            // BUILT_IN_FUNCTIONS suppliers are evaluated once per template execution. Previously
            // (via JSR-223 ScriptBindings), they were evaluated lazily on each JS variable access,
            // so a template reading $uuid twice would get two different UUIDs. This is a behavioural
            // change documented in the changelog; templates relying on per-access freshness should
            // call the supplier explicitly via a host helper.
            TemplateFunctions.BUILT_IN_FUNCTIONS.forEach((key, supplier) ->
                jsBindings.putMember(key, supplier.get()));
            TemplateFunctions.BUILT_IN_HELPERS.forEach(jsBindings::putMember);
            // Override the shared unseeded faker with a per-engine seeded faker when a non-zero
            // templateFakerSeed is configured, so faker-driven JS templates generate reproducible fixtures.
            if (fakerOverride != null) {
                jsBindings.putMember("faker", fakerOverride);
            }

            // Expose jsonPath('$.field') and xPath('//field') request-body extraction functions to the
            // script scope, sharing the same extraction logic / error handling as the Mustache and
            // Velocity engines. Registered as java.util.function.Function so GraalJS treats them as
            // callable JS functions. The "jsonPath"/"xPath" names are not used by BUILT_IN_FUNCTIONS or
            // BUILT_IN_HELPERS, so this does not shadow (or get shadowed by) a built-in.
            RequestBodyExtractionHelper bodyExtractionHelper = new RequestBodyExtractionHelper(request, mockServerLogger);
            jsBindings.putMember("jsonPath", (Function<String, Object>) bodyExtractionHelper::jsonPath);
            jsBindings.putMember("xPath", (Function<String, Object>) bodyExtractionHelper::xPath);

            // Load-generation only: expose the per-iteration variable as a JS host object so a
            // load-scenario JavaScript step can read iteration.getIndex() etc. Null for every
            // non-load template execution, so the standard response/forward template path is
            // byte-for-byte unchanged.
            if (iteration != null) {
                jsBindings.putMember("iteration", iteration);
            }

            Source source = Source.create("js", fullScript);
            context.eval(source);

            Value serialiseFunc = jsBindings.getMember("serialise");
            Value stringifiedResult;
            if (includeResponse) {
                stringifiedResult = serialiseFunc.execute(
                    new HttpRequestTemplateObject(request),
                    new HttpResponseTemplateObject(response)
                );
            } else {
                stringifiedResult = serialiseFunc.execute(
                    new HttpRequestTemplateObject(request)
                );
            }

            String stringifiedResponse = stringifiedResult.asString();

            if (rawText) {
                // Streaming payload templating: the serialise() wrapper already coerced the handle()
                // result to text, so return it verbatim without deserialising into a response object.
                if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setType(TEMPLATE_GENERATED)
                            .setLogLevel(Level.INFO)
                            .setHttpRequest(request)
                            .setMessageFormat(TEMPLATE_GENERATED_MESSAGE_FORMAT)
                            .setArguments(stringifiedResponse, script, request)
                    );
                }
                //noinspection unchecked
                return (T) stringifiedResponse;
            }

            JsonNode generatedObject = null;
            try {
                generatedObject = objectMapper.readTree(stringifiedResponse);
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setHttpRequest(request)
                            .setMessageFormat("exception deserialising generated content:{}into json node for request:{}")
                            .setArguments(stringifiedResponse, request)
                    );
                }
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(TEMPLATE_GENERATED)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat(TEMPLATE_GENERATED_MESSAGE_FORMAT)
                        .setArguments(generatedObject != null ? generatedObject : stringifiedResponse, script, request)
                );
            }
            return httpTemplateOutputDeserializer.deserializer(request, stringifiedResponse, dtoClass);
        }
    }
}

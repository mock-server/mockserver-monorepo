package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.HostAccess;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.SourceSection;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyObject;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.regex.Pattern;

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
     * therefore making the class filter the single, complete gate (GHSA-xcr9-3j63-6cgq).
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

    /**
     * A single process-wide GraalVM {@link Engine} shared by every {@link Context} this runner builds.
     *
     * <p><strong>Why this exists (the performance fix).</strong> A GraalVM {@code Context} built with no
     * explicit {@code Engine} gets its own private, throw-away engine, so the parsed/compiled form of a
     * script cannot be reused across invocations: every request paid full engine construction plus a fresh
     * (interpreter-only, on stock OpenJDK) parse of the same template. Driving the JavaScript template path
     * under concurrent load therefore monopolised the small shared dispatch pool and starved every other
     * workload on the server. A shared {@code Engine} is the documented mechanism for cross-{@code Context}
     * code caching: contexts built on it reuse the engine's parsed-{@link Source} cache, so the second and
     * subsequent renders of a given template skip the parse entirely, and building each {@code Context} on an
     * already-constructed engine is far cheaper than constructing engine-and-context together.
     *
     * <p><strong>Thread-safety.</strong> An {@code Engine} is explicitly thread-safe and designed to be
     * shared across threads and contexts; a {@code Context} is NOT — see {@link #run} for how each request
     * still gets its own short-lived {@code Context} for isolation. The engine lives for the JVM lifetime
     * (like {@link #WATCHDOG}) and is never closed. Closing a per-request {@code Context} — including the
     * watchdog's {@code context.close(true)} — affects only that context and never the shared engine.
     *
     * <p>No {@code polyglotimpl.DisableVersionChecks}/{@code engine.WarnInterpreterOnly} tuning is applied:
     * the interpreter-only warning is a real signal (see the class comment on {@code run}) and, now that the
     * engine is shared, it is emitted once for the process rather than once per render.
     */
    private static final Engine ENGINE = Engine.newBuilder("js").build();

    /**
     * Upper bound on the number of distinct parsed {@link Source} objects retained in {@link #SOURCE_CACHE}.
     * Templates come from a bounded set of expectations in normal use, so this cap is generous; it exists
     * only so a workload that generates a very large number of distinct one-off scripts (e.g. streaming
     * payload templates assembled per request) cannot grow the cache without limit. Beyond the cap a fresh
     * {@code Source} is created per call and not cached — correct, just without the parse-cache hit.
     */
    private static final int MAX_CACHED_SOURCES = 500;

    /**
     * Cache of parsed {@link Source} objects keyed by the exact script text. Reusing a {@code Source}
     * instance (rather than calling {@link Source#create} afresh each time) is what lets the shared
     * {@link #ENGINE} serve its cached parse: the engine's code cache is keyed by {@code Source}, so handing
     * it the same instance guarantees the hit and also avoids re-hashing the script text on every call.
     */
    private static final ConcurrentHashMap<String, CachedScript> SOURCE_CACHE = new ConcurrentHashMap<>();

    // plain mapper so a parameter with an empty or null value is kept
    private static final ObjectMapper PARAMETERS_MAPPER = new ObjectMapper();

    /**
     * A parsed script and the {@link TemplateFunctions#ON_DEMAND_HELPERS} its template names, found once per script
     * so a template binds only the helpers it names, and one that names none binds nothing extra.
     */
    private static final class CachedScript {
        private final String script;
        private final Source source;
        private final List<String> namedOnDemandHelpers;
        // whether the template names ext, found on first ask, which only an engine with extension helpers makes
        private volatile Boolean namesExtensionHelpers;

        private CachedScript(String script, Source source, List<String> namedOnDemandHelpers) {
            this.script = script;
            this.source = source;
            this.namedOnDemandHelpers = namedOnDemandHelpers;
        }

        Source source() {
            return source;
        }

        List<String> namedOnDemandHelpers() {
            return namedOnDemandHelpers;
        }

        boolean namesExtensionHelpers() {
            Boolean names = namesExtensionHelpers;
            if (names == null) {
                names = script.contains("ext") && EXTENSION_HELPERS_REFERENCE.matcher(script).find();
                namesExtensionHelpers = names;
            }
            return names;
        }
    }

    // ext as an identifier, not a member (request.ext) or part of a longer name (text)
    private static final Pattern EXTENSION_HELPERS_REFERENCE = Pattern.compile("(?<![\\w$.])ext\\b");

    private PolyglotRunner() {
    }

    /**
     * A level of the {@code ext} namespace (a Map whose lookups throw for a missing name) as a JavaScript object, so
     * {@code ext.acme.Strings} reads each level through the map; a helper itself is passed as a host object.
     */
    private static Object guestValue(Object value) {
        return value instanceof Map<?, ?> map ? new NamespaceObject(map) : value;
    }

    private static final class NamespaceObject implements ProxyObject {
        private final Map<?, ?> namespace;

        private NamespaceObject(Map<?, ?> namespace) {
            this.namespace = namespace;
        }

        @Override
        public Object getMember(String key) {
            return guestValue(namespace.get(key));
        }

        @Override
        public Object getMemberKeys() {
            return ProxyArray.fromList(new ArrayList<>(namespace.keySet()));
        }

        // every name, so reading one always asks the namespace and a missing helper fails loudly
        @Override
        public boolean hasMember(String key) {
            return true;
        }

        @Override
        public void putMember(String key, org.graalvm.polyglot.Value value) {
            throw new UnsupportedOperationException("ext is read-only");
        }
    }

    /**
     * Return the parsed {@link Source} for {@code fullScript}, reusing a cached instance when present so the
     * shared {@link #ENGINE} can serve its cached parse. Bounded by {@link #MAX_CACHED_SOURCES}: once the cap
     * is reached a fresh (uncached) {@code Source} is returned, so the map can never grow without limit.
     */
    private static CachedScript sourceFor(String script, String fullScript) {
        CachedScript cached = SOURCE_CACHE.get(fullScript);
        if (cached != null) {
            return cached;
        }
        CachedScript created = uncachedScript(script, fullScript);
        if (SOURCE_CACHE.size() < MAX_CACHED_SOURCES) {
            CachedScript existing = SOURCE_CACHE.putIfAbsent(fullScript, created);
            return existing != null ? existing : created;
        }
        return created;
    }

    private static CachedScript uncachedScript(String script, String fullScript) {
        List<String> named = new ArrayList<>(0);
        for (String name : TemplateFunctions.ON_DEMAND_HELPERS.keySet()) {
            if (script.contains(name)) {
                named.add(name);
            }
        }
        return new CachedScript(script, Source.create("js", fullScript), named.isEmpty() ? List.of() : List.copyOf(named));
    }

    static boolean sourceCacheContains(String scriptFragment) {
        return SOURCE_CACHE.keySet().stream().anyMatch(script -> script.contains(scriptFragment));
    }

    static boolean sourceCacheIsFull() {
        return SOURCE_CACHE.size() >= MAX_CACHED_SOURCES;
    }

    // JavaScriptTemplateEngine.wrapTemplate puts the template's first line on the script's third line,
    // indented by two spaces
    private static final int TEMPLATE_FIRST_SCRIPT_LINE = 3;
    private static final int TEMPLATE_INDENT = 2;

    /**
     * Where a failed evaluation went wrong, in template coordinates: {@code line} and {@code column} are
     * null when unknown, and {@code syntaxErrorAfterTemplate} is true for a syntax error the engine only
     * detected after the template's last line, which usually means an unbalanced bracket or quote.
     */
    record TemplateLocation(Integer line, Integer column, boolean syntaxErrorAfterTemplate) {
    }

    static TemplateLocation templateLocation(Throwable failure, String template) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof PolyglotException polyglotException) {
                SourceSection section = guestLocation(polyglotException);
                if (section == null) {
                    return null;
                }
                int line = section.getStartLine() - (TEMPLATE_FIRST_SCRIPT_LINE - 1);
                if (line >= 1 && line <= lineCount(template)) {
                    Integer column = section.hasColumns() ? Math.max(1, section.getStartColumn() - TEMPLATE_INDENT) : null;
                    return new TemplateLocation(line, column, false);
                }
                return polyglotException.isSyntaxError() && line > lineCount(template) ? new TemplateLocation(null, null, true) : null;
            }
        }
        return null;
    }

    private static SourceSection guestLocation(PolyglotException polyglotException) {
        SourceSection section = polyglotException.getSourceLocation();
        if (isTemplateSource(section)) {
            return section;
        }
        for (PolyglotException.StackFrame frame : polyglotException.getPolyglotStackTrace()) {
            if (frame.isGuestFrame() && isTemplateSource(frame.getSourceLocation())) {
                return frame.getSourceLocation();
            }
        }
        return null;
    }

    private static boolean isTemplateSource(SourceSection section) {
        return section != null && section.isAvailable() && section.hasLines() && section.getSource() != null && !section.getSource().isInternal();
    }

    private static int lineCount(String template) {
        return template == null ? 0 : template.split("\r\n|\r|\n", -1).length;
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
        Object fakerOverride,
        Map<String, Object> onDemandOverrides,
        Map<String, Object> parameters,
        boolean cacheSource,
        Object extensionHelpers
    ) {
        // rawText (streaming payload templating): coerce the handle(request) return value to text — a
        // returned string is emitted verbatim, any other value is JSON.stringify'd — instead of always
        // JSON.stringify'ing and then deserialising into a response object. rawText always implies the
        // no-response (single-argument) template shape.
        String serialiseFunction;
        if (rawText) {
            serialiseFunction = " function serialise(request) { var r = handle(__mockserverDerivedFields(__mockserverCaseInsensitiveHeaders(JSON.parse(request)), request)); return (r === null || r === undefined) ? '' : ((typeof r === 'string') ? r : JSON.stringify(r)); }";
        } else if (includeResponse) {
            serialiseFunction = " function serialise(request, response) { return JSON.stringify(handle(__mockserverDerivedFields(__mockserverCaseInsensitiveHeaders(JSON.parse(request)), request), JSON.parse(response)), null, 2); }";
        } else {
            serialiseFunction = " function serialise(request) { return JSON.stringify(handle(__mockserverDerivedFields(__mockserverCaseInsensitiveHeaders(JSON.parse(request)), request)), null, 2); }";
        }
        // HTTP field names are case-insensitive (RFC 9110), but request is a JSON.parse'd plain JS
        // object, so request.headers.host is a native property lookup that misses a header the client
        // sent as "Host" (and vice-versa) — the JS side of issue #2575. Wrap headers in a Proxy whose
        // get/has traps take an exact-key hit unchanged (fast path) and only fall back to a
        // case-insensitive key scan on a miss. This is strictly additive: ownKeys is NOT trapped, so
        // Object.keys/JSON.stringify/spread/for-in still enumerate exactly the original keys with no
        // duplicates. It operates purely on the parsed plain JS object — no host object is exposed and
        // no new route to Java.type/Function/eval is created, so the template sandbox is unaffected.
        String headerAccessorFunction = " function __mockserverCaseInsensitiveHeaders(request) {"
            + " if (request && request.headers && typeof request.headers === 'object') {"
            + " request.headers = new Proxy(request.headers, {"
            + " get: function(target, prop, receiver) {"
            + " if (typeof prop === 'string' && !(prop in target)) {"
            + " var lower = prop.toLowerCase();"
            + " var keys = Object.keys(target);"
            + " for (var i = 0; i < keys.length; i++) { if (keys[i].toLowerCase() === lower) { return target[keys[i]]; } } }"
            + " return Reflect.get(target, prop, receiver); },"
            + " has: function(target, prop) {"
            + " if (typeof prop === 'string' && !(prop in target)) {"
            + " var lower = prop.toLowerCase();"
            + " var keys = Object.keys(target);"
            + " for (var i = 0; i < keys.length; i++) { if (keys[i].toLowerCase() === lower) { return true; } } }"
            + " return prop in target; } }); }"
            + " return request; }";
        // formData, parts, host, port, scheme, baseUrl, bodyAsBase64 and id are not in the request JSON (computing
        // them for every render would parse every form and multipart body), so each is a non-enumerable getter that
        // asks the host object on first read and then replaces itself with the value. Each part arrives without its
        // content: body and bodyAsBase64 are getters too, so reading a filename never copies an upload.
        String derivedFieldsFunction = " function __mockserverLazy(target, name, enumerable, read) {"
            + " var define = function(value) { Object.defineProperty(target, name, { value: value, writable: true, configurable: true, enumerable: enumerable }); return value; };"
            + " Object.defineProperty(target, name, { configurable: true, enumerable: enumerable,"
            + " get: function() { return define(read()); }, set: function(value) { define(value); } }); }"
            + " function __mockserverDerivedFields(request, source) {"
            + " if (request && typeof request === 'object') {"
            + " ['formData', 'parts', 'host', 'port', 'scheme', 'baseUrl', 'bodyAsBase64', 'id'].forEach(function(name) {"
            + " __mockserverLazy(request, name, false, function() {"
            + " var value = JSON.parse(source.derivedFieldAsJson(name));"
            + " if (name === 'parts' && value) { Object.keys(value).forEach(function(partName) {"
            + " value[partName].forEach(function(part, index) { ['body', 'bodyAsBase64'].forEach(function(field) {"
            + " __mockserverLazy(part, field, true, function() { return source.partField(partName, index, field); }); }); }); }); }"
            + " return value; }); }); }"
            + " return request; }";
        String fullScript = script + serialiseFunction + headerAccessorFunction + derivedFieldsFunction;

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

        // A fresh Context per request, built on the shared ENGINE. Each Context has its own JavaScript
        // realm (global scope, own copies of the built-in prototypes), so nothing a template writes —
        // an implicit global, a globalThis assignment, a prototype mutation — can survive into the next
        // request: isolation is byte-for-byte identical to building a standalone Context per request, as
        // before. What the shared engine changes is only the COST: the parsed Source (see sourceFor) is
        // cached and compiled code is reused across contexts, and building a Context on an existing engine
        // is far cheaper than building engine-and-context together. The Context is single-threaded, which
        // is respected here because it is created, used and closed entirely within this one call on the
        // calling thread. allowHostClassLookup(classFilter) remains per-context, so engines with different
        // class-filter configurations safely share one ENGINE (the code cache is filter-independent).
        try (Context context = Context.newBuilder("js")
            .engine(ENGINE)
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
                    objectMapper, mockServerLogger, httpTemplateOutputDeserializer, dtoClass, context, rawText, fakerOverride, onDemandOverrides, parameters, cacheSource, extensionHelpers
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
        Object fakerOverride,
        Map<String, Object> onDemandOverrides,
        Map<String, Object> parameters,
        boolean cacheSource,
        Object extensionHelpers
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
            // Reuse the cached, already-parsed Source so the shared ENGINE serves its code cache instead of
            // re-parsing this script (the expensive, interpreter-only step) on every request.
            CachedScript cachedScript = cacheSource ? sourceFor(script, fullScript) : uncachedScript(script, fullScript);
            List<String> namedOnDemandHelpers = cachedScript.namedOnDemandHelpers();
            for (int i = 0; i < namedOnDemandHelpers.size(); i++) {
                String name = namedOnDemandHelpers.get(i);
                Object helper = onDemandOverrides.get(name);
                if (helper == null) {
                    helper = TemplateFunctions.ON_DEMAND_HELPERS.get(name);
                }
                jsBindings.putMember(name, helper instanceof TemplateFunctions function ? function.get() : helper);
            }
            if (extensionHelpers != null && cachedScript.namesExtensionHelpers()) {
                jsBindings.putMember("ext", guestValue(extensionHelpers));
            }
            if (parameters != null && !parameters.isEmpty()) {
                try {
                    jsBindings.putMember("parameters", jsBindings.getMember("JSON").invokeMember("parse", PARAMETERS_MAPPER.writeValueAsString(parameters)));
                } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
                    throw new IllegalArgumentException("template parameters could not be written as JSON: " + notJson.getMessage(), notJson);
                }
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

            context.eval(cachedScript.source());

            Value serialiseFunc = jsBindings.getMember("serialise");
            Value stringifiedResult;
            if (includeResponse) {
                stringifiedResult = serialiseFunc.execute(
                    new HttpRequestTemplateObject(request, mockServerLogger),
                    new HttpResponseTemplateObject(response)
                );
            } else {
                stringifiedResult = serialiseFunc.execute(
                    new HttpRequestTemplateObject(request, mockServerLogger)
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

package org.mockserver.templates.engine.javascript;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.stream.StreamSupport;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.formatting.StringFormatter.formatLogMessage;
import static org.mockserver.formatting.StringFormatter.indentAndToString;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"RedundantSuppression", "FieldMayBeFinal"})
public class JavaScriptTemplateEngine implements TemplateEngine {

    /**
     * The single {@code javascriptAllowedClasses} entry that switches class restrictions off, so a template
     * may resolve ANY class via {@code Java.type(...)}, as earlier versions allowed by default. Note this does not reopen
     * the {@code getClass().getClassLoader()} walk — {@code PolyglotRunner} denies the members of
     * {@code Class}/{@code ClassLoader} unconditionally, since nothing legitimate needs that route.
     */
    static final String UNRESTRICTED = "*";
    /**
     * Cap on distinct refused class names logged per engine, so a hostile template cannot flood the log.
     */
    private static final int MAX_REFUSED_CLASSES_LOGGED = 50;

    private static final boolean POLYGLOT_AVAILABLE;

    static {
        boolean available;
        try {
            Class.forName("org.graalvm.polyglot.Context");
            available = true;
        } catch (ClassNotFoundException e) {
            available = false;
        }
        POLYGLOT_AVAILABLE = available;
    }

    private final ObjectMapper objectMapper;
    private final MockServerLogger mockServerLogger;
    private final HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;
    private final Configuration configuration;
    private final Predicate<String> classFilter;
    private final boolean polyglotAvailable;
    // distinct class names already logged as refused; bounded by MAX_REFUSED_CLASSES_LOGGED
    private final Set<String> refusedClassesLogged = ConcurrentHashMap.newKeySet();
    // Per-engine seeded faker when templateFakerSeed is non-zero, else null (the shared unseeded faker
    // from BUILT_IN_HELPERS is used). Resolved once so the seeded sequence is deterministic across renders.
    private final Object seededFaker;

    public JavaScriptTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this(mockServerLogger, configuration, POLYGLOT_AVAILABLE);
    }

    /**
     * Visible for testing: allows exercising the polyglot-unavailable (fail-loud) path even when the
     * GraalVM Polyglot API is present on the test classpath. Production always uses the public two-arg
     * constructor, which pins {@code polyglotAvailable} to the real classpath probe {@link #POLYGLOT_AVAILABLE}.
     */
    JavaScriptTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration, boolean polyglotAvailable) {
        this.polyglotAvailable = polyglotAvailable;
        this.configuration = (configuration == null) ? configuration() : configuration;
        this.mockServerLogger = mockServerLogger;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        this.objectMapper = ObjectMapperFactory.createObjectMapper();
        this.classFilter = className -> {
            boolean allowed = isClassAllowed(className, this.configuration);
            if (!allowed) {
                logRefusedClass(className);
            }
            return allowed;
        };
        this.seededFaker = this.configuration.templateFakerSeed() != 0L
            ? org.mockserver.templates.engine.TemplateFunctions.resolveFaker(this.configuration.templateFakerSeed())
            : null;
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            if (isUnrestricted(this.configuration.javascriptAllowedClasses())) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("JavaScript template engine class restrictions have been switched off (mockserver.javascriptAllowedClasses=" + UNRESTRICTED + "). Templates can use Java.type(\"...\") to instantiate arbitrary Java classes including Runtime and so execute OS commands in the MockServer process — only do this when every template comes from a source you fully trust, and never when the control plane is reachable by untrusted callers. Prefer listing the classes your templates legitimately need instead.")
                );
            } else if (!isNotBlank(this.configuration.javascriptAllowedClasses())
                && isNotBlank(this.configuration.javascriptDisallowedClasses())) {
                // A deny-list is WEAKER than the default it replaces, so it must be as loud as the
                // wildcard opt-out — otherwise an operator silently downgrades below the safe default
                // with no signal at all.
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("JavaScript templates are restricted by a deny-list (mockserver.javascriptDisallowedClasses), which is WEAKER than the default: every class except those listed can be resolved, and a deny-list cannot enumerate every dangerous class. Prefer mockserver.javascriptAllowedClasses, or unset mockserver.javascriptDisallowedClasses to fall back to the default of allowing no Java class at all.")
                );
            }
        }
    }

    /**
     * Log the first {@link #MAX_REFUSED_CLASSES_LOGGED} distinct classes this engine refuses, so an
     * operator whose legitimate template stopped resolving a class can see exactly which class was
     * refused and which property to set — GraalJS itself only surfaces this as the class resolving to
     * undefined ("... is not a function"). Bounded and de-duplicated so a hostile template looping over
     * class names cannot flood the log.
     */
    private void logRefusedClass(String className) {
        if (mockServerLogger == null
            || !mockServerLogger.isEnabledForInstance(Level.WARN)
            || refusedClassesLogged.size() >= MAX_REFUSED_CLASSES_LOGGED
            || !refusedClassesLogged.add(className)) {
            return;
        }
        mockServerLogger.logEvent(
            new LogEntry()
                .setLogLevel(Level.WARN)
                .setMessageFormat("JavaScript template was refused access to Java class \"" + className + "\". JavaScript templates cannot resolve Java classes unless they are explicitly allowed; add the class (or a package prefix such as \"java.time.*\") to mockserver.javascriptAllowedClasses if this template legitimately needs it.")
        );
    }

    public static boolean isPolyglotAvailable() {
        return POLYGLOT_AVAILABLE;
    }

    /**
     * Decide whether a JavaScript template may resolve {@code className} via {@code Java.type(...)}.
     *
     * <p>Evaluated in this order:
     * <ol>
     *   <li><strong>Allow-list</strong> ({@code javascriptAllowedClasses}) — when set, ONLY entries on the
     *       list resolve and everything else is refused. This is the only form that is safe by
     *       construction and is the recommended setting. The single entry {@code *} lets any class resolve,
     *       as earlier versions did.</li>
     *   <li><strong>Deny-list</strong> ({@code javascriptDisallowedClasses}) — when set (and no allow-list
     *       is), listed entries are refused and everything else resolves. Retained for backwards
     *       compatibility; it is NOT a security boundary (see below).</li>
     *   <li>Otherwise <strong>nothing resolves</strong> — the default. A JavaScript template that has not
     *       been granted a class cannot reach host classes at all, so it cannot reach
     *       {@code java.lang.Runtime}/{@code java.lang.ProcessBuilder} and execute OS commands in the
     *       MockServer process. In earlier versions the default was unrestricted, which made a reachable control
     *       plane plus a JavaScript template an RCE path (GHSA-7pwj-xvc2-hfpc).</li>
     * </ol>
     *
     * <p>Both lists match a class name exactly (case-insensitively, as before) OR as a package prefix when
     * the entry ends in {@code .*} or {@code .} — e.g. {@code java.lang.*}. Prefix support matters because
     * exact-match entries cannot express intent: denying {@code java.lang.Runtime} leaves
     * {@code java.lang.ProcessBuilder} — and reach-through via {@code Class.forName} — wide open, which is
     * exactly why a deny-list should not be relied on as a security boundary.
     *
     * <p>Note the guest context is built with {@code HostAccess.ALL} (see {@link PolyglotRunner}), so this
     * predicate is the ONLY gate on which host classes a template can reach.
     */
    private static boolean isClassAllowed(String className, Configuration configuration) {
        String allowedClasses = configuration.javascriptAllowedClasses();
        if (isNotBlank(allowedClasses)) {
            return isUnrestricted(allowedClasses) || matchesAny(allowedClasses, className);
        }
        String disallowedClasses = configuration.javascriptDisallowedClasses();
        if (isNotBlank(disallowedClasses)) {
            return !matchesAny(disallowedClasses, className);
        }
        // Default deny: a template only reaches host classes an operator has explicitly granted.
        return false;
    }

    /**
     * @return true if {@code allowedClasses} contains the {@code *} escape hatch, which switches class
     * restrictions off entirely. Deliberately an exact entry rather than a pattern so it cannot be
     * triggered accidentally by an ordinary allow-list entry.
     */
    static boolean isUnrestricted(String allowedClasses) {
        if (!isNotBlank(allowedClasses)) {
            return false;
        }
        return StreamSupport
            .stream(Splitter.on(",").trimResults().omitEmptyStrings().split(allowedClasses).spliterator(), false)
            .anyMatch(UNRESTRICTED::equals);
    }

    /**
     * @return true if {@code className} matches any entry in the comma-separated {@code entries}, either
     * exactly (case-insensitive) or as a package prefix for entries ending in {@code .*} or {@code .}
     */
    private static boolean matchesAny(String entries, String className) {
        if (className == null) {
            return false;
        }
        Iterable<String> entryList = Splitter.on(",").trimResults().omitEmptyStrings().split(entries);
        return StreamSupport.stream(entryList.spliterator(), false)
            .anyMatch(entry -> {
                if (entry.endsWith(".*")) {
                    return startsWithIgnoreCase(className, entry.substring(0, entry.length() - 1));
                }
                if (entry.endsWith(".")) {
                    return startsWithIgnoreCase(className, entry);
                }
                return entry.equalsIgnoreCase(className);
            });
    }

    private static boolean startsWithIgnoreCase(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, null, dtoClass, false);
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, response, null, dtoClass, true);
    }

    /**
     * Load-generation only: execute a JavaScript template with a per-iteration variable
     * ({@code iteration}) bound in the script scope. Used by the load executor so a JavaScript
     * load-scenario step can vary its output per iteration. Identical to
     * {@link #executeTemplate(String, HttpRequest, Class)} when {@code iteration} is null.
     */
    public <T> T executeTemplate(String template, HttpRequest request, org.mockserver.load.IterationContext iteration, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, request, null, iteration, dtoClass, false);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request) {
        // JavaScript templates are designed to construct and return a full response object, not a text
        // fragment, so they are not supported for FileBody templating. Use httpResponseTemplate (or
        // httpResponseTemplate with templateFile) with a JavaScript template instead. Streaming payload
        // templating uses renderTemplateText(...) below, which executes the template and coerces its
        // return value to text.
        throw new UnsupportedOperationException("JavaScript templates are not supported for file body templating; use a Velocity or Mustache templateType, or an httpResponseTemplate for JavaScript");
    }

    @Override
    public String renderTemplateText(String template, HttpRequest request) {
        assertPolyglotAvailable(request);
        String script = wrapTemplate(template);
        try {
            validateTemplate(template);
            Long executionTimeout = configuration.javascriptTemplateExecutionTimeout();
            // rawText=true: the template's handle(request) return value is coerced to text (a string is
            // used verbatim, any other value is JSON.stringify'd) rather than deserialised into a response.
            return PolyglotRunner.run(
                script,
                false,
                request,
                null,
                null,
                classFilter,
                objectMapper,
                mockServerLogger,
                httpTemplateOutputDeserializer,
                null,
                executionTimeout == null ? 0L : executionTimeout,
                true,
                seededFaker
            );
        } catch (JavaScriptTemplateTimeoutException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    /**
     * Fail loudly rather than silently degrade. If a JavaScript template is actually used but the
     * GraalVM Polyglot API (GraalJS) is not on the classpath, we cannot render it, so surface a clear,
     * actionable error the same way a template transform failure does (RuntimeException) instead of
     * returning null and producing a confusing empty/degraded response.
     */
    private void assertPolyglotAvailable(HttpRequest request) {
        if (!polyglotAvailable) {
            String message = "JavaScript response templates require the GraalJS engine, which is not on the classpath. " +
                "Add the org.graalvm.polyglot:js (or js-community) dependency, or use the Velocity or Mustache template engine.";
            if (mockServerLogger != null) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(request)
                        .setMessageFormat(message)
                );
            }
            throw new RuntimeException(message);
        }
    }

    private <T> T executeTemplateInternal(String template, HttpRequest request, HttpResponse response, org.mockserver.load.IterationContext iteration, Class<? extends DTO<T>> dtoClass, boolean includeResponse) {
        assertPolyglotAvailable(request);
        String script = includeResponse ? wrapTemplateWithResponse(template) : wrapTemplate(template);
        try {
            validateTemplate(template);
            // Delegate to PolyglotRunner (nested holder class). The JVM only resolves the
            // org.graalvm.polyglot.* references inside PolyglotRunner when this branch is
            // reached, so the standard distribution (no GraalVM on classpath) never triggers a
            // NoClassDefFoundError — that case is handled by the fail-loud guard above.
            Long executionTimeout = configuration.javascriptTemplateExecutionTimeout();
            return PolyglotRunner.run(
                script,
                includeResponse,
                request,
                response,
                iteration,
                classFilter,
                objectMapper,
                mockServerLogger,
                httpTemplateOutputDeserializer,
                dtoClass,
                executionTimeout == null ? 0L : executionTimeout,
                false,
                seededFaker
            );
        } catch (JavaScriptTemplateTimeoutException e) {
            // Surface the timeout as-is (with its clear, already-logged message) rather than wrapping
            // it in the generic transform-failure message, so callers/tests can recognise the cap firing.
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(formatLogMessage("Exception:{}transforming template:{}for request:{}", isNotBlank(e.getMessage()) ? e.getMessage() : e.getClass().getSimpleName(), template, request), e);
        }
    }

    static String wrapTemplate(String template) {
        return "function handle(request) {" + indentAndToString(template)[0] + "}";
    }

    static String wrapTemplateWithResponse(String template) {
        return "function handle(request, response) {" + indentAndToString(template)[0] + "}";
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.javascriptDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.javascriptDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }

}

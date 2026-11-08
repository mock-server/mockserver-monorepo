package org.mockserver.templates.engine.mustache;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.samskivert.mustache.Mustache;
import com.samskivert.mustache.MustacheException;
import com.samskivert.mustache.MustacheParseException;
import com.samskivert.mustache.Template;
import org.mockserver.configuration.Configuration;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.HttpResponse;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.DTO;
import org.mockserver.templates.engine.TemplateEngine;
import org.mockserver.templates.engine.TemplateEvaluationException;
import org.mockserver.templates.engine.TemplateFunctions;
import org.mockserver.templates.engine.TemplateWarning;
import org.mockserver.templates.engine.helpers.RequestBodyExtractionHelper;
import org.mockserver.templates.engine.helpers.ScenarioTemplateHelper;
import org.mockserver.templates.engine.helpers.SystemValueTemplateHelper;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.slf4j.event.Level;

import java.io.IOException;
import java.io.StringWriter;
import java.io.Writer;
import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * See: https://github.com/samskivert/jmustache or http://mustache.github.io/mustache.5.html for syntax
 *
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class MustacheTemplateEngine implements TemplateEngine {

    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private static final MustacheDateTemplateHelper MUSTACHE_DATES = new MustacheDateTemplateHelper();
    private static final MustacheXPathTemplateHelper MUSTACHE_XPATH = new MustacheXPathTemplateHelper();
    private static final MustacheListTemplateHelper MUSTACHE_LISTS = new MustacheListTemplateHelper();
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final Mustache.Compiler compiler;
    // As compiler, but a variable that does not resolve throws instead of rendering as empty text;
    // used only to find those variables for the template dry run, never to serve
    private final Mustache.Compiler unresolvedVariableCompiler;
    private HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;
    // Per-engine seeded faker when templateFakerSeed is non-zero, else null (the shared unseeded faker
    // from BUILT_IN_HELPERS is used). Resolved once so the seeded sequence is deterministic across renders.
    private final Object seededFaker;

    // jmustache cannot invoke a helper method with an argument the way Velocity ($scenario.get('x')) and
    // JavaScript (scenario.get('x')) can, so scenario state is exposed the same way as jsonPath/xPath: as a
    // section lambda whose section body is the argument. "scenario" is a Map holding a "get" lambda, so a
    // template reads captured/scenario state by name with {{#scenario.get}}name{{/scenario.get}}. The map
    // and lambda are stateless (the lambda resolves the live ScenarioManager lazily on each call via
    // ScenarioTemplateHelper) so a single immutable instance is shared safely across all render threads.
    private final Map<String, Object> scenarioMustacheHelper;

    // The request-independent bindings (the ~26 built-in functions/helpers, the mustache scenario helper
    // and any seeded faker) are identical for every render, so they are built once and shared read-only;
    // each render layers a small per-render overlay over them rather than copying all of them into a
    // fresh map. Never mutated after construction, so it is safe for concurrent reads.
    private final Map<String, Object> baseBindings;

    // ----- parsed-template cache (compile once, render many) -----
    // Templates are mostly static for the lifetime of a mock — the same template string is rendered on
    // every matching request. The original path re-compiled the template string into a fresh parsed
    // Template on every render via compiler.compile(template), which dominated render cost. We instead
    // compile each distinct template string at most once and reuse the resulting jmustache Template
    // across renders. A jmustache Template is immutable once compiled and is rendered (Template.execute)
    // concurrently with a fresh per-render data map, so a single shared compiled Template is safe to
    // reuse across request threads — mirroring how the Velocity engine reuses its parsed Templates.
    //
    // The cache is keyed by the template content string itself, so distinct templates can never collide
    // onto the same compiled Template. Bound rationale: distinct template strings are bounded by the
    // number of expectations/actions a user configures, but a misbehaving client (e.g. unique generated
    // templates per request) could otherwise grow the cache without limit. We cap the number of cached
    // templates at PARSED_TEMPLATE_CACHE_MAX (matching the Velocity engine's bound) so memory stays
    // bounded; 1000 comfortably covers realistic mock configurations while remaining cheap. The bound is
    // enforced by compiledTemplates, an access-ordered LRU wrapped in Collections.synchronizedMap so
    // concurrent renders read and insert safely (the same concurrency approach Velocity uses for its
    // parsed-template cache).
    static final int PARSED_TEMPLATE_CACHE_MAX = 1000;
    private final Map<String, Template> compiledTemplates = Collections.synchronizedMap(new LinkedHashMap<String, Template>(256, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Template> eldest) {
            return size() > PARSED_TEMPLATE_CACHE_MAX;
        }
    });

    public MustacheTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this(mockServerLogger, configuration, configuration.extensionTemplateHelperStub());
    }

    /**
     * An engine whose templates can call extension template helpers: {@code extensionHelpers} is bound as
     * {@code ext}. Only the response and forward template handlers build one.
     */
    public MustacheTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration, Object extensionHelpers) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        compiler = Mustache
            .compiler()
            .emptyStringIsFalse(true)
            .zeroIsFalse(true)
            .strictSections(false)
            .defaultValue("")
            .withCollector(new ExtendedCollector());
        unresolvedVariableCompiler = compiler.nullValue("");
        ScenarioTemplateHelper scenarioTemplateHelper = new ScenarioTemplateHelper();
        Map<String, Object> scenario = new LinkedHashMap<>();
        scenario.put("get", (Mustache.Lambda) (frag, out) -> {
            String state = scenarioTemplateHelper.get(frag.execute());
            out.write(state == null ? "" : state);
        });
        this.scenarioMustacheHelper = Collections.unmodifiableMap(scenario);
        this.seededFaker = configuration.templateFakerSeed() != 0L
            ? TemplateFunctions.resolveFaker(configuration.templateFakerSeed())
            : null;
        Map<String, Object> base = new HashMap<>(TemplateFunctions.BUILT_IN_FUNCTIONS.size() + TemplateFunctions.BUILT_IN_HELPERS.size() + TemplateFunctions.ON_DEMAND_HELPERS.size() + 2);
        base.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
        base.putAll(TemplateFunctions.BUILT_IN_HELPERS);
        base.putAll(TemplateFunctions.ON_DEMAND_HELPERS);
        base.put("dates", MUSTACHE_DATES);
        base.put("xpath", MUSTACHE_XPATH);
        base.put("lists", configuration.templateFakerSeed() != 0L ? new MustacheListTemplateHelper(new java.util.Random(configuration.templateFakerSeed())) : MUSTACHE_LISTS);
        if (seededFaker != null) {
            base.put("faker", seededFaker);
        }
        base.put("scenario", scenarioMustacheHelper);
        base.put("systemValue", systemValueMustacheHelper(new SystemValueTemplateHelper(configuration.templateSystemValueAllowList(), configuration.forwardCredentialHeadersByHost(), mockServerLogger)));
        // null unless an extension offers template helpers, so with none the bindings are unchanged
        if (extensionHelpers != null) {
            base.put("ext", extensionHelpers);
        }
        this.baseBindings = Collections.unmodifiableMap(base);
    }

    /**
     * jmustache cannot pass an argument to a method, so, as with {@code scenario.get}, the name is the section body:
     * {@code {{#systemValue.env}}NAME{{/systemValue.env}}} and {@code {{#systemValue.property}}name{{/systemValue.property}}}.
     */
    private static Map<String, Object> systemValueMustacheHelper(SystemValueTemplateHelper systemValue) {
        Map<String, Object> helper = new LinkedHashMap<>();
        helper.put("env", (Mustache.Lambda) (frag, out) -> out.write(systemValue.env(frag.execute().trim())));
        helper.put("property", (Mustache.Lambda) (frag, out) -> out.write(systemValue.property(frag.execute().trim())));
        return Collections.unmodifiableMap(helper);
    }

    /**
     * Return the jmustache {@link Template} for the given template string, compiling it at most once per
     * distinct string and reusing the cached compiled Template on subsequent renders. The compiled
     * Template is immutable and rendered concurrently with a fresh per-render data map, so a single
     * shared instance is safe across request threads. A compilation failure (malformed template) is not
     * cached — it propagates to the caller exactly as {@code compiler.compile(template)} would, so error
     * handling is unchanged.
     */
    private Template compiledTemplate(String template) {
        Template cached = compiledTemplates.get(template);
        if (cached == null) {
            // compile outside the map mutation so a malformed template throws (and is never cached);
            // a benign race where two threads compile the same string concurrently just discards one
            // identical compiled Template, which is harmless.
            cached = compiler.compile(template);
            compiledTemplates.put(template, cached);
        }
        return cached;
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, null, request, null, dtoClass);
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, null, request, response, dtoClass);
    }

    @Override
    public <T> T executeTemplate(String template, Map<String, Object> parameters, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, parameters, request, response, dtoClass);
    }

    /**
     * As {@link #executeTemplate(String, HttpRequest, Class)}, also reporting each variable that did not
     * resolve (and so rendered as empty text) to {@code unresolvedVariables}, with the line of its first
     * use. Used by the template dry run; it renders the template again for each such variable, up to
     * {@link #MAX_UNRESOLVED_VARIABLE_PASSES} more times, and the result is the normal render's.
     */
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass, Consumer<TemplateWarning> unresolvedVariables) {
        T result = executeTemplateInternal(template, null, request, null, dtoClass);
        reportUnresolvedVariables(template, request, unresolvedVariables);
        return result;
    }

    static final int MAX_UNRESOLVED_VARIABLE_PASSES = 20;

    /**
     * jmustache stops at the first variable that does not resolve, so each pass binds the names found so
     * far to empty text at the top level and renders again. A first component found missing (reqest in
     * reqest.path) is replaced by the full name when the next pass reports it on the same line.
     */
    private void reportUnresolvedVariables(String template, HttpRequest request, Consumer<TemplateWarning> unresolvedVariables) {
        Map<String, Integer> unresolved = new LinkedHashMap<>();
        try {
            Template strictTemplate = unresolvedVariableCompiler.compile(template);
            Set<String> boundToEmpty = new HashSet<>();
            for (int pass = 0; pass < MAX_UNRESOLVED_VARIABLE_PASSES; pass++) {
                Map<String, Object> data = renderData(request, null, null, null);
                boundToEmpty.forEach(name -> data.put(name, ""));
                try {
                    strictTemplate.execute(data, Writer.nullWriter());
                    break;
                } catch (MustacheException.Context missing) {
                    if (missing.getCause() != null || missing.key == null || !boundToEmpty.add(missing.key)) {
                        break;
                    }
                    unresolved.entrySet().removeIf(found -> missing.key.startsWith(found.getKey() + ".") && found.getValue() == missing.lineNo);
                    unresolved.put(missing.key, missing.lineNo);
                }
            }
        } catch (RuntimeException otherFailure) {
            // the normal render already reported or survived it; only missing variables are reported here
        }
        unresolved.forEach((name, line) -> unresolvedVariables.accept(new TemplateWarning(
            "{{" + name + "}} did not resolve against this request, so it rendered as empty text",
            line > 0 ? line : null,
            null
        )));
    }

    @Override
    public String renderTemplate(String template, HttpRequest request) {
        return renderTemplate(template, request, null);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request, org.mockserver.load.IterationContext iteration) {
        try {
            validateTemplate(template);
            Writer writer = new StringWriter();
            Template compiledTemplate = compiledTemplate(template);
            Map<String, Object> data = renderData(request, null, iteration, null);
            compiledTemplate.execute(data, writer);
            return writer.toString();
        } catch (Exception e) {
            throw transformFailure(e, template, request);
        }
    }

    private <T> T executeTemplateInternal(String template, Map<String, Object> parameters, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        T result;
        try {
            validateTemplate(template);
            Writer writer = new StringWriter();
            Template compiledTemplate = compiledTemplate(template);
            Map<String, Object> data = renderData(request, response, null, parameters);
            compiledTemplate.execute(data, writer);
            JsonNode generatedObject = null;
            try {
                generatedObject = objectMapper.readTree(writer.toString());
            } catch (Throwable throwable) {
                if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.INFO)
                            .setHttpRequest(request)
                            .setMessageFormat("exception deserialising generated content:{}into json node for request:{}")
                            .setArguments(writer.toString(), request)
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
                        .setArguments(generatedObject != null ? generatedObject : writer.toString(), template, request)
                );
            }
            result = httpTemplateOutputDeserializer.deserializer(request, writer.toString(), dtoClass);
        } catch (Exception e) {
            throw transformFailure(e, template, request);
        }
        return result;
    }

    // jmustache appends " @ line N" to a parse error's message rather than exposing the line
    private static final Pattern PARSE_ERROR_LINE = Pattern.compile(" @ line (\\d{1,9})$");

    /**
     * jmustache reports a line, never a column, and already states it in its message, so the line is only
     * attached to the exception, not appended.
     */
    static TemplateEvaluationException transformFailure(Exception failure, String template, HttpRequest request) {
        Integer line = null;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10 && line == null; depth++, cause = cause.getCause()) {
            if (cause instanceof MustacheException.Context context && context.lineNo > 0) {
                line = context.lineNo;
            } else if (cause instanceof MustacheParseException && cause.getMessage() != null) {
                Matcher matcher = PARSE_ERROR_LINE.matcher(cause.getMessage());
                if (matcher.find()) {
                    line = Integer.parseInt(matcher.group(1));
                }
            }
        }
        return TemplateEvaluationException.transformFailure(failure, template, request, line, null, null, false);
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.mustacheDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.mustacheDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }

    private void evaluateJsonPath(Map<String, Object> data, String jsonPath, HttpRequest request, Writer out) throws IOException {
        // Delegate the actual extraction to the shared helper so the Mustache, Velocity and JavaScript
        // engines all use the same JSONPath/XPath logic and error handling. The Mustache idiom is to
        // store the extracted value under "jsonPathResult" (so it can be iterated in a following section)
        // and emit nothing inline.
        Object jsonPathResult = new RequestBodyExtractionHelper(request, mockServerLogger).jsonPath(jsonPath);
        data.put("jsonPathResult", jsonPathResult);
        out.write("");
    }

    private void evaluatedXPath(String xPath, HttpRequest request, Writer out) throws IOException {
        out.write(new RequestBodyExtractionHelper(request, mockServerLogger).xPath(xPath));
    }

    private Map<String, Object> renderData(HttpRequest request, HttpResponse response, org.mockserver.load.IterationContext iteration, Map<String, Object> parameters) {
        Map<String, Object> data = new RenderContextMap(baseBindings);
        data.put("request", new HttpRequestTemplateObject(request, mockServerLogger));
        if (response != null) {
            data.put("response", new HttpResponseTemplateObject(response));
        }
        if (iteration != null) {
            data.put("iteration", iteration);
        }
        if (parameters != null && !parameters.isEmpty()) {
            data.put("parameters", parameters);
        }
        data.put("xPath", (Mustache.Lambda) (frag, out) -> evaluatedXPath(frag.execute(), request, out));
        data.put("jsonPath", (Mustache.Lambda) (frag, out) -> evaluateJsonPath(data, frag.execute(), request, out));
        return data;
    }

    // A render's data map is the shared request-independent baseBindings with a small per-render overlay
    // layered on top: reads prefer the overlay, and every put during a render (including jsonPathResult)
    // targets only the overlay, so the shared base is never mutated across renders. It must remain a Map
    // because the mustache collector's fall-through fetcher only fires for a Map context.
    private static final class RenderContextMap extends AbstractMap<String, Object> {
        private final Map<String, Object> base;
        private final Map<String, Object> overlay = new HashMap<>();

        private RenderContextMap(Map<String, Object> base) {
            this.base = base;
        }

        @Override
        public Object get(Object key) {
            Object value = overlay.get(key);
            return (value != null || overlay.containsKey(key)) ? value : base.get(key);
        }

        @Override
        public boolean containsKey(Object key) {
            return overlay.containsKey(key) || base.containsKey(key);
        }

        @Override
        public Object put(String key, Object value) {
            return overlay.put(key, value);
        }

        @Override
        public int size() {
            int size = base.size();
            for (String key : overlay.keySet()) {
                if (!base.containsKey(key)) {
                    size++;
                }
            }
            return size;
        }

        @Override
        public Set<Entry<String, Object>> entrySet() {
            Map<String, Object> merged = new LinkedHashMap<>(base);
            merged.putAll(overlay);
            return merged.entrySet();
        }
    }
}

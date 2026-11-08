package org.mockserver.templates.engine.velocity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import org.apache.velocity.Template;
import org.apache.velocity.VelocityContext;
import org.apache.velocity.app.VelocityEngine;
import org.apache.velocity.app.event.EventCartridge;
import org.apache.velocity.app.event.InvalidReferenceEventHandler;
import org.apache.velocity.context.Context;
import org.apache.velocity.exception.ExtendedParseException;
import org.apache.velocity.exception.ParseErrorException;
import org.apache.velocity.runtime.RuntimeConstants;
import org.apache.velocity.runtime.resource.loader.StringResourceLoader;
import org.apache.velocity.runtime.resource.util.StringResourceRepository;
import org.apache.velocity.tools.ToolManager;
import org.apache.velocity.tools.config.ToolConfiguration;
import org.apache.velocity.tools.config.ToolboxConfiguration;
import org.apache.velocity.tools.config.XmlFactoryConfiguration;
import org.apache.velocity.util.introspection.Info;
import org.apache.velocity.util.introspection.SecureUberspector;
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
import org.mockserver.templates.engine.helpers.SystemValueTemplateHelper;
import org.mockserver.templates.engine.model.HttpRequestTemplateObject;
import org.mockserver.templates.engine.model.HttpResponseTemplateObject;
import org.mockserver.templates.engine.serializer.HttpTemplateOutputDeserializer;
import org.mockserver.templates.engine.velocity.directives.Ifnull;
import org.mockserver.templates.engine.velocity.directives.TimeLimitedDefine;
import org.mockserver.templates.engine.velocity.directives.TimeLimitedEvaluate;
import org.mockserver.templates.engine.velocity.directives.TimeLimitedForeach;
import org.mockserver.templates.engine.velocity.directives.TimeLimitedParse;
import org.slf4j.event.Level;

import java.io.StringWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.log.model.LogEntry.LogMessageType.TEMPLATE_GENERATED;
import static org.mockserver.log.model.LogEntryMessages.TEMPLATE_GENERATED_MESSAGE_FORMAT;

/**
 * @author jamesdbloom
 */
@SuppressWarnings("FieldMayBeFinal")
public class VelocityTemplateEngine implements TemplateEngine {

    private static final ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
    private final MockServerLogger mockServerLogger;
    private final Configuration configuration;
    private final long timeoutWhenDisabledMillis;
    private HttpTemplateOutputDeserializer httpTemplateOutputDeserializer;

    // ----- runtime-reconfigurable engine (see EngineHolder) -----
    // velocityDisallowClassLoading installs the SecureUberspector sandbox, but that can only be applied
    // when the VelocityEngine is built. The engine used to be built once in the constructor and every
    // call site caches the engine instance, so toggling the setting at runtime (system property,
    // Configuration setter, or PUT /mockserver/configuration) was completely inert: templates kept
    // being able to instantiate arbitrary Java classes even though the operator was told the setting
    // had been applied. Everything derived from the engine now lives in one immutable EngineHolder held
    // in a single volatile field, rebuilt (under engineRebuildLock) only when the configured flag
    // differs from the flag the current engine was built with. Steady state therefore costs one
    // volatile read plus a boolean compare, so the parse-once/render-many work below is not regressed.
    private volatile EngineHolder engineHolder;
    private final Object engineRebuildLock = new Object();

    // ----- shared, request-independent function/helper bindings (built once, read by every render) -----
    // The BUILT_IN_FUNCTIONS ($uuid, $now, $rand_int, ...) and BUILT_IN_HELPERS ($faker, $strings,
    // $crypto, ...) are identical for every render: they are static final ImmutableMaps holding the SAME
    // stateless value objects for the life of the JVM, and the function/helper objects are already shared
    // and invoked concurrently today (the original path merely re-referenced them from a fresh per-render
    // context). The original path copied all ~26 entries into a fresh VelocityContext on EVERY render via
    // two forEach loops. We instead build the combined map ONCE and reference it (never copy it) per
    // render through Velocity's context chaining, so the per-render work drops to a couple of thin context
    // wrappers with no 26-entry copy.
    //
    // The Velocity TOOLS ($json, $xml, $math, ...) are NOT hoisted: $json/$xml are
    // configured request-scoped and hold per-request parse state (e.g. $json.parse($request.body)), so a
    // fresh ToolContext MUST be created per render — sharing one across render threads cross-contaminates
    // request state (guarded by VelocityTemplateEngineTest.shouldUseRequestScopeToolsInThreadSafeWay).
    //
    // Per-render context chain (outermost first): child(request/iteration/jsonPath/xPath) ->
    //   functionsLayer(own map = the shared sharedFunctionsAndHelpers, read-only here) ->
    //   perRenderToolContext(tools). Reads fall through the chain; every put during a render targets only
    //   the outermost child (AbstractContext.put writes to the outermost map only) so neither the shared
    //   function/helper map nor its layer is ever mutated. functionsLayer's own storage is the SHARED map
    //   passed by reference: nothing ever calls put on functionsLayer, so the map stays read-only and
    //   safe for concurrent reads. Output is byte-for-byte identical to copying every binding into one
    //   flat context, and the per-render-varying functions ($uuid, $now, $rand, $faker, $iteration.*)
    //   still recompute on each get() because the same supplier/helper objects are invoked either way.
    private final Map<String, Object> sharedFunctionsAndHelpers;

    // ----- parsed-template cache (parse once, render many) -----
    // Templates are mostly static for the lifetime of a mock — the same template string is rendered
    // on every matching request. The original path re-parsed the template string into a fresh AST on
    // every render, which dominated render cost. To parse once and render many we register each
    // distinct template string with the engine's own StringResourceLoader and render it through
    // Velocity's native getTemplate(...) + Template.merge(...). getTemplate(...) returns the
    // parsed-and-initialised Template cached in the engine's resource cache, so the AST is built once
    // and shared across concurrent request threads (the same concurrency contract Velocity already
    // relies on for file/classpath templates). Template.merge(...) is Velocity's own render entry
    // point, so #stop, macros, scope control and IOException wrapping behave identically to evaluate.
    //
    // Bound rationale: distinct template strings are bounded by the number of expectations/actions a
    // user configures, but a misbehaving client (e.g. unique generated templates per request) could
    // otherwise grow both caches without limit. We cap the number of cached templates at
    // PARSED_TEMPLATE_CACHE_MAX so memory stays bounded; 1000 comfortably covers realistic mock
    // configurations while remaining cheap. The bound is enforced by templateNames (a
    // synchronized access-ordered LRU) which, on eviction, removes the body from the string
    // repository so the corresponding entry also ages out of the engine's resource cache (itself
    // sized to the same bound). Each distinct template body is registered under a short synthetic name
    // from a per-engine counter (never a hash), so distinct templates can never collide onto one name
    // or cached AST, and getTemplate(...) no longer copies the whole body per render to key that cache.
    // Falls back to the re-parsing evaluate(...) path if the string loader is unavailable, keeping
    // behaviour identical.
    static final int PARSED_TEMPLATE_CACHE_MAX = 1000;
    private static final AtomicLong ENGINE_INSTANCE_COUNTER = new AtomicLong();

    /**
     * Immutable snapshot of a built {@link VelocityEngine} and everything derived from it, so nothing
     * can be left stale when the engine is rebuilt and no reader can observe a torn
     * (engine, toolManager, repository) triple. Published through a single volatile field; a reader
     * takes ONE reference into a local and uses that local for the whole render, so a rebuild part-way
     * through a render can never mix an old engine with a new tool context or repository.
     * <p>
     * {@code templateRepositoryName} is unique per built engine because it keys a STATIC repository
     * registry — every rebuild takes a fresh counter value and a name is never reused.
     * {@code templateNames} is the bounded access-ordered LRU whose eviction hook removes from
     * THIS holder's own repository by synthetic name, keeping that pairing intact across rebuilds.
     */
    private static final class EngineHolder {
        private final VelocityEngine velocityEngine;
        private final ToolManager toolManager;
        private final StringResourceRepository templateRepository;
        private final String templateRepositoryName;
        // maps each distinct template body to the short synthetic resource name it is registered under;
        // access-ordered LRU bounded at PARSED_TEMPLATE_CACHE_MAX, wrapped in Collections.synchronizedMap
        // so concurrent renders read and insert safely.
        private final Map<String, String> templateNames;
        // allocates the synthetic names ("t0", "t1", ...); a counter, never a hash, so two distinct
        // template bodies can never collide onto one name.
        private final AtomicLong templateNameCounter = new AtomicLong();
        // the velocityDisallowClassLoading value this engine was actually built with, compared against
        // the live configuration on every render to decide whether a rebuild is required
        private final boolean disallowClassLoading;

        private EngineHolder(VelocityEngine velocityEngine, ToolManager toolManager, StringResourceRepository templateRepository, String templateRepositoryName, Map<String, String> templateNames, boolean disallowClassLoading) {
            this.velocityEngine = velocityEngine;
            this.toolManager = toolManager;
            this.templateRepository = templateRepository;
            this.templateRepositoryName = templateRepositoryName;
            this.templateNames = templateNames;
            this.disallowClassLoading = disallowClassLoading;
        }
    }

    public VelocityTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration) {
        this(mockServerLogger, configuration, 0L, configuration.extensionTemplateHelperStub());
    }

    /**
     * An engine whose templates can call extension template helpers: {@code extensionHelpers} is bound as
     * {@code $ext}. Only the response and forward template handlers build one.
     */
    public VelocityTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration, Object extensionHelpers) {
        this(mockServerLogger, configuration, 0L, extensionHelpers);
    }

    private VelocityTemplateEngine(MockServerLogger mockServerLogger, Configuration configuration, long timeoutWhenDisabledMillis, Object extensionHelpers) {
        this.mockServerLogger = mockServerLogger;
        this.configuration = configuration;
        this.timeoutWhenDisabledMillis = timeoutWhenDisabledMillis;
        this.httpTemplateOutputDeserializer = new HttpTemplateOutputDeserializer(mockServerLogger);
        this.engineHolder = buildEngineHolder(disallowClassLoadingConfigured());
        // Build the shared, request-independent function/helper map once. It is referenced (not copied)
        // by every render via context chaining and is never mutated after construction, so it is safe for
        // concurrent reads. unmodifiableMap makes that read-only contract explicit and fail-fast.
        Map<String, Object> functionsAndHelpers = new LinkedHashMap<>(
            TemplateFunctions.BUILT_IN_FUNCTIONS.size() + TemplateFunctions.BUILT_IN_HELPERS.size() + TemplateFunctions.ON_DEMAND_HELPERS.size());
        functionsAndHelpers.putAll(TemplateFunctions.BUILT_IN_FUNCTIONS);
        functionsAndHelpers.putAll(TemplateFunctions.BUILT_IN_HELPERS);
        functionsAndHelpers.putAll(TemplateFunctions.ON_DEMAND_HELPERS);
        // When a non-zero templateFakerSeed is configured, replace the shared unseeded $faker with a
        // per-engine seeded faker so faker-driven templates generate reproducible fixtures. Seed 0
        // (the default) keeps the shared unseeded faker, so behaviour is unchanged.
        if (configuration.templateFakerSeed() != 0L) {
            functionsAndHelpers.put("faker", TemplateFunctions.resolveFaker(configuration.templateFakerSeed()));
            functionsAndHelpers.put("lists", TemplateFunctions.resolveLists(configuration.templateFakerSeed()));
        }
        functionsAndHelpers.put("systemValue", new SystemValueTemplateHelper(configuration.templateSystemValueAllowList(), configuration.forwardCredentialHeadersByHost(), mockServerLogger));
        // null unless an extension offers template helpers, so with none the bindings are unchanged
        if (extensionHelpers != null) {
            functionsAndHelpers.put("ext", extensionHelpers);
        }
        this.sharedFunctionsAndHelpers = Collections.unmodifiableMap(functionsAndHelpers);
    }

    /**
     * An engine for the template dry run: identical to a serving engine, except that when
     * {@code velocityTemplateExecutionTimeout} is 0 (disabled) it still stops a template after
     * {@code timeoutWhenDisabledMillis}.
     */
    public static VelocityTemplateEngine forDryRun(MockServerLogger mockServerLogger, Configuration configuration, long timeoutWhenDisabledMillis) {
        return new VelocityTemplateEngine(mockServerLogger, configuration, timeoutWhenDisabledMillis, configuration.extensionTemplateHelperStub());
    }

    private long executionTimeoutMillis() {
        Long configured = configuration.velocityTemplateExecutionTimeout();
        return configured != null && configured > 0 ? configured : timeoutWhenDisabledMillis;
    }

    private boolean disallowClassLoadingConfigured() {
        return Boolean.TRUE.equals(configuration.velocityDisallowClassLoading());
    }

    /**
     * Build a {@link VelocityEngine} for the given sandbox setting together with everything derived
     * from it. The repository name is freshly allocated on every call — it keys a STATIC registry, so
     * names are never reused, and concurrently-constructed or rebuilt engines never share a repository.
     */
    private EngineHolder buildEngineHolder(boolean disallowClassLoading) {
        String repositoryName = "mockserver-velocity-templates-" + ENGINE_INSTANCE_COUNTER.incrementAndGet();
        VelocityEngine newVelocityEngine = buildVelocityEngine(repositoryName, disallowClassLoading);
        ToolManager newToolManager = buildToolManager(newVelocityEngine);
        final StringResourceRepository newTemplateRepository = StringResourceLoader.getRepository(repositoryName);
        Map<String, String> newTemplateNames = Collections.synchronizedMap(new LinkedHashMap<String, String>(256, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, String> eldest) {
                if (size() > PARSED_TEMPLATE_CACHE_MAX) {
                    // drop the evicted body from the string repository BY ITS SYNTHETIC NAME so the
                    // matching parsed Template ages out of the engine's resource cache too — keeps both
                    // caches bounded together. Deliberately bound to THIS holder's own repository, so an
                    // evicted entry can never remove a body from a different (rebuilt) engine's repository.
                    if (newTemplateRepository != null) {
                        newTemplateRepository.removeStringResource(eldest.getValue());
                    }
                    return true;
                }
                return false;
            }
        });
        return new EngineHolder(newVelocityEngine, newToolManager, newTemplateRepository, repositoryName, newTemplateNames, disallowClassLoading);
    }

    /**
     * Return the holder to use for a render, rebuilding the engine first when
     * {@code velocityDisallowClassLoading} has changed since the current engine was built — this is
     * what makes the sandbox setting take effect at runtime rather than only at construction.
     * <p>
     * The steady-state path (the flag has not changed, which is overwhelmingly the common case) is a
     * single volatile read plus a boolean compare; rebuilding is expensive, so it happens only on an
     * actual change. Callers MUST take the returned reference into a local and use that local for the
     * whole render.
     */
    private EngineHolder currentEngineHolder() {
        boolean disallowClassLoading = disallowClassLoadingConfigured();
        EngineHolder currentHolder = engineHolder;
        if (currentHolder.disallowClassLoading == disallowClassLoading) {
            return currentHolder;
        }
        synchronized (engineRebuildLock) {
            // re-check under the lock so concurrent renders observing the same change rebuild once
            currentHolder = engineHolder;
            if (currentHolder.disallowClassLoading == disallowClassLoading) {
                return currentHolder;
            }
            EngineHolder rebuiltHolder = buildEngineHolder(disallowClassLoading);
            engineHolder = rebuiltHolder;
            // release the superseded repository from StringResourceLoader's STATIC registry so
            // repeated toggles cannot grow it without bound. A render already in flight on the old
            // holder keeps its own direct reference to the old repository object, and if the old
            // engine misses its resource cache it simply falls back to the re-parsing evaluate(...)
            // path, so output stays correct.
            StringResourceLoader.removeRepository(currentHolder.templateRepositoryName);
            return rebuiltHolder;
        }
    }

    /**
     * Return Velocity's own parsed-and-initialised {@link Template} for the given template string,
     * parsing it at most once per distinct string. The first time a string is seen it is stored in the
     * engine's {@link StringResourceRepository} under a short synthetic name, and
     * {@code getTemplate(...)} builds and caches the AST in the engine's resource cache; subsequent
     * calls return the same cached Template. The returned Template is rendered concurrently by request
     * threads via {@link Template#merge}, exactly as Velocity renders any cached file/classpath
     * template — so {@code #stop}, macros and scope control behave identically to
     * {@code velocityEngine.evaluate(...)}.
     * <p>
     * Returns null when no string repository is available, or when the template fails to parse/init,
     * in which case callers fall back to the re-parsing {@code evaluate(...)} path. Falling back on a
     * parse failure is deliberate: {@code evaluate(...)} reports parse errors using the logical name
     * {@code "VelocityResponseTemplate"} (rather than the cache key), so the user-facing error message
     * stays byte-for-byte identical to the original path. Invalid templates are rare and never cached,
     * so this fallback costs nothing on the hot path.
     */
    private Template parsedTemplate(EngineHolder holder, String template) {
        StringResourceRepository templateRepository = holder.templateRepository;
        if (templateRepository == null) {
            return null;
        }
        // Register each distinct template body once under a short synthetic name and reuse that name on
        // every later render, so getTemplate(...) keys the resource cache by the short name instead of
        // copying the whole body per render. computeIfAbsent is atomic under the synchronized map and
        // stores the body before publishing the name, so a concurrent render either does not see the name
        // yet (and computes it) or sees a name whose body is already in the repository.
        String templateName = holder.templateNames.computeIfAbsent(template, body -> {
            String name = "t" + holder.templateNameCounter.getAndIncrement();
            templateRepository.putStringResource(name, body, StandardCharsets.UTF_8.name());
            return name;
        });
        try {
            return holder.velocityEngine.getTemplate(templateName, StandardCharsets.UTF_8.name());
        } catch (Exception parseOrLoadFailure) {
            // drop the bad entry so it is neither cached nor counted against the bound, then let the
            // caller reproduce the original error message via evaluate(...)
            holder.templateNames.remove(template);
            templateRepository.removeStringResource(templateName);
            return null;
        }
    }

    /**
     * Render the template into the writer using the parse-once cached {@link Template} when one is
     * available, falling back to the re-parsing {@code evaluate(...)} path otherwise (no string
     * repository, or a template that fails to parse). Both paths use Velocity's own render entry
     * point, so the output — and any surfaced parse error — is byte-for-byte identical to
     * {@code velocityEngine.evaluate(context, writer, "VelocityResponseTemplate", template)}.
     */
    private void renderTemplate(EngineHolder holder, String template, VelocityContext context, Writer writer) throws Exception {
        Template parsed = parsedTemplate(holder, template);
        try (RenderDeadline.Scope ignored = RenderDeadline.start(executionTimeoutMillis())) {
            if (parsed != null) {
                parsed.merge(context, writer);
            } else {
                holder.velocityEngine.evaluate(context, writer, "VelocityResponseTemplate", template);
            }
        }
    }

    /**
     * A render stopped by velocityTemplateExecutionTimeout is logged once at WARN and rethrown, like a
     * JavaScript template timeout, so it is not reported as a generic transform failure. Velocity wraps an
     * exception thrown inside a method call (such as a {@code #define} block's {@code toString()}) in a
     * MethodInvocationException, so the timeout is looked for in the cause chain; otherwise it is a transform failure.
     */
    private RuntimeException renderFailure(Exception failure, String template, HttpRequest request) {
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10; depth++, cause = cause.getCause()) {
            if (cause instanceof VelocityTemplateTimeoutException timeout) {
                return loggedTimeout(explainDryRunTimeout(timeout), request);
            }
        }
        return transformFailure(failure, template, request);
    }

    /**
     * The serving timeout message advises setting the timeout to 0 to disable it, which does not disable a
     * dry run's limit, so a dry-run engine leaves that advice out.
     */
    private VelocityTemplateTimeoutException explainDryRunTimeout(VelocityTemplateTimeoutException timeout) {
        if (timeoutWhenDisabledMillis <= 0) {
            return timeout;
        }
        Long configured = configuration.velocityTemplateExecutionTimeout();
        if (configured == null || configured <= 0) {
            return new VelocityTemplateTimeoutException("Velocity template execution exceeded " + timeoutWhenDisabledMillis
                + "ms and was stopped; mockserver.velocityTemplateExecutionTimeout is 0 or less, so serving does not time out, but a dry run always stops a Velocity template after "
                + timeoutWhenDisabledMillis + "ms");
        }
        return new VelocityTemplateTimeoutException("Velocity template execution exceeded the configured timeout of " + configured
            + "ms and was stopped; increase mockserver.velocityTemplateExecutionTimeout to give it longer");
    }

    private VelocityTemplateTimeoutException loggedTimeout(VelocityTemplateTimeoutException exception, HttpRequest request) {
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setHttpRequest(request)
                    .setMessageFormat(exception.getMessage())
            );
        }
        return exception;
    }

    private VelocityEngine buildVelocityEngine(String templateRepositoryName, boolean disallowClassLoading) {
        VelocityEngine velocityEngine;

        // See: https://velocity.apache.org/engine/2.0/configuration.html
        Properties velocityProperties = new Properties();
        velocityProperties.put(RuntimeConstants.RUNTIME_LOG_REFERENCE_LOG_INVALID, "true");
        velocityProperties.put(RuntimeConstants.RUNTIME_STRING_INTERNING, "true");
        velocityProperties.put(RuntimeConstants.MAX_NUMBER_LOOPS, "-1");
        velocityProperties.put(RuntimeConstants.CHECK_EMPTY_OBJECTS, "true");
        velocityProperties.put(RuntimeConstants.PARSE_DIRECTIVE_MAXDEPTH, "10");
        velocityProperties.put(RuntimeConstants.RUNTIME_REFERENCES_STRICT, "false");
        velocityProperties.put("context.scope_control.template", "false");
        velocityProperties.put("context.scope_control.evaluate", "false");
        velocityProperties.put("context.scope_control.foreach", "true");
        velocityProperties.put("context.scope_control.macro", "false");
        velocityProperties.put("context.scope_control.define", "false");
        velocityProperties.put("directive.set.null.allowed", "true");
        velocityProperties.put(RuntimeConstants.INTERPOLATE_STRINGLITERALS, "true");
        velocityProperties.put(RuntimeConstants.INPUT_ENCODING, "UTF-8");
        velocityProperties.put(RuntimeConstants.PARSER_POOL_CLASS, org.apache.velocity.runtime.ParserPoolImpl.class.getName());
        velocityProperties.put(RuntimeConstants.PARSER_POOL_SIZE, "50");
        velocityProperties.put(RuntimeConstants.SPACE_GOBBLING, "lines");
        velocityProperties.put(RuntimeConstants.PARSER_HYPHEN_ALLOWED, "true");
        // the TimeLimited directives replace the built-in ones so that every construct that can repeat work is bounded by time
        velocityProperties.put(RuntimeConstants.CUSTOM_DIRECTIVES, String.join(",", Ifnull.class.getName(), TimeLimitedForeach.class.getName(),
            TimeLimitedParse.class.getName(), TimeLimitedEvaluate.class.getName(), TimeLimitedDefine.class.getName()));
        velocityProperties.put(RuntimeConstants.RESOURCE_MANAGER_CLASS, org.apache.velocity.runtime.resource.ResourceManagerImpl.class.getName());
        velocityProperties.put(RuntimeConstants.RESOURCE_MANAGER_CACHE_CLASS, org.apache.velocity.runtime.resource.ResourceCacheImpl.class.getName());
        // bound the engine's parsed-Template (AST) cache with a native LRU matching the cache we keep
        // in templateNames, so a flood of distinct templates cannot grow memory without limit.
        velocityProperties.put(RuntimeConstants.RESOURCE_MANAGER_DEFAULTCACHE_SIZE, String.valueOf(PARSED_TEMPLATE_CACHE_MAX));
        velocityProperties.put("resource.loader.file.class", org.apache.velocity.runtime.resource.loader.FileResourceLoader.class.getName());
        // Register a string resource loader so each distinct template string is parsed once and the
        // initialised AST is cached + rendered through Velocity's own getTemplate(...) + Template.merge(...).
        // A unique, static, per-engine repository name avoids any cross-engine key collisions while still
        // letting us obtain the repository through the public StringResourceLoader.getRepository(name) API
        // (no reflection). cache=true keeps the parsed Template in the resource cache;
        // modification_check_interval=0 means a registered body is never re-checked for changes (bodies are
        // immutable once registered), so the cached AST is served directly on every subsequent render.
        velocityProperties.put(RuntimeConstants.RESOURCE_LOADERS, "string");
        velocityProperties.put("resource.loader.string.class", StringResourceLoader.class.getName());
        velocityProperties.put("resource.loader.string.repository.class", org.apache.velocity.runtime.resource.util.StringResourceRepositoryImpl.class.getName());
        velocityProperties.put("resource.loader.string.repository.static", "true");
        velocityProperties.put("resource.loader.string.repository.name", templateRepositoryName);
        velocityProperties.put("resource.loader.string.repository.encoding", "UTF-8");
        velocityProperties.put("resource.loader.string.cache", "true");
        velocityProperties.put("resource.loader.string.modification_check_interval", "0");
        if (disallowClassLoading) {
            velocityProperties.put(RuntimeConstants.UBERSPECT_CLASSNAME, SecureUberspector.class.getName());
        } else if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("Velocity template class loading is enabled (mockserver.velocityDisallowClassLoading=false). Templates can instantiate arbitrary Java classes — only use Velocity templates from trusted sources, or set mockserver.velocityDisallowClassLoading=true.")
            );
        }
        velocityEngine = new VelocityEngine();
        velocityEngine.init(velocityProperties);

        return velocityEngine;
    }

    private ToolManager buildToolManager(VelocityEngine velocityEngine) {
        ToolManager manager = new ToolManager();
        // ToolboxConfiguration for "application" scope
        ToolboxConfiguration applicationToolboxConfiguration = new ToolboxConfiguration();
        applicationToolboxConfiguration.setScope("application");
        ToolConfiguration collectionTool = new ToolConfiguration();
        collectionTool.setClass(org.apache.velocity.tools.generic.CollectionTool.class.getName());
        applicationToolboxConfiguration.addTool(collectionTool);
        ToolConfiguration comparisonDateTool = new ToolConfiguration();
        comparisonDateTool.setClass(org.apache.velocity.tools.generic.ComparisonDateTool.class.getName());
        applicationToolboxConfiguration.addTool(comparisonDateTool);
        ToolConfiguration displayTool = new ToolConfiguration();
        displayTool.setClass(org.apache.velocity.tools.generic.DisplayTool.class.getName());
        applicationToolboxConfiguration.addTool(displayTool);
        ToolConfiguration escapeTool = new ToolConfiguration();
        escapeTool.setClass(org.apache.velocity.tools.generic.EscapeTool.class.getName());
        applicationToolboxConfiguration.addTool(escapeTool);
        ToolConfiguration mathTool = new ToolConfiguration();
        mathTool.setClass(org.apache.velocity.tools.generic.MathTool.class.getName());
        applicationToolboxConfiguration.addTool(mathTool);
        ToolConfiguration numberTool = new ToolConfiguration();
        numberTool.setClass(org.apache.velocity.tools.generic.NumberTool.class.getName());
        applicationToolboxConfiguration.addTool(numberTool);

        // ToolboxConfiguration for "request" scope
        ToolboxConfiguration requestToolboxConfiguration = new ToolboxConfiguration();
        requestToolboxConfiguration.setScope("request");
        ToolConfiguration jsonTool = new ToolConfiguration();
        jsonTool.setClass(org.apache.velocity.tools.generic.JsonTool.class.getName());
        requestToolboxConfiguration.addTool(jsonTool);
        ToolConfiguration xmlTool = new ToolConfiguration();
        xmlTool.setClass(org.apache.velocity.tools.generic.XmlTool.class.getName());
        requestToolboxConfiguration.addTool(xmlTool);
        // SECURITY: ImportTool ($import.read(...)) is deliberately NOT registered — it lets a template
        // fetch an arbitrary URL or read a local file, which is an SSRF / local-file-read vector. Omit it
        // so Velocity templates cannot reach the network or filesystem through $import.

        XmlFactoryConfiguration xmlFactoryConfiguration = new XmlFactoryConfiguration();
        xmlFactoryConfiguration.addToolbox(applicationToolboxConfiguration);
        xmlFactoryConfiguration.addToolbox(requestToolboxConfiguration);
        manager.configure(xmlFactoryConfiguration);
        manager.setVelocityEngine(velocityEngine);

        return manager;
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, null, request, null, dtoClass, null);
    }

    @Override
    public <T> T executeTemplate(String template, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, null, request, response, dtoClass, null);
    }

    @Override
    public <T> T executeTemplate(String template, Map<String, Object> parameters, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass) {
        return executeTemplateInternal(template, parameters, request, response, dtoClass, null);
    }

    /**
     * As {@link #executeTemplate(String, HttpRequest, Class)}, also reporting each reference that did not
     * resolve (and so was rendered as written) to {@code unresolvedReferences}. Used by the template dry run.
     */
    public <T> T executeTemplate(String template, HttpRequest request, Class<? extends DTO<T>> dtoClass, Consumer<TemplateWarning> unresolvedReferences) {
        return executeTemplateInternal(template, null, request, null, dtoClass, unresolvedReferences);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request) {
        return renderTemplate(template, request, null);
    }

    @Override
    public String renderTemplate(String template, HttpRequest request, org.mockserver.load.IterationContext iteration) {
        try {
            validateTemplate(template);
            // read the holder ONCE for the whole render so a concurrent rebuild cannot mix an old
            // engine with a new tool context or repository
            EngineHolder holder = currentEngineHolder();
            Writer writer = new StringWriter();
            VelocityContext context = newRenderContext(holder, request);
            if (iteration != null) {
                context.put("iteration", iteration);
            }
            RequestBodyExtractionHelper bodyExtractionHelper = new RequestBodyExtractionHelper(request, mockServerLogger);
            context.put("jsonPath", new RequestBodyExtractionHelper.JsonPathTool(bodyExtractionHelper));
            context.put("xPath", new RequestBodyExtractionHelper.XPathTool(bodyExtractionHelper));
            renderTemplate(holder, template, context, writer);
            return writer.toString();
        } catch (Exception e) {
            throw renderFailure(e, template, request);
        }
    }

    private <T> T executeTemplateInternal(String template, Map<String, Object> parameters, HttpRequest request, HttpResponse response, Class<? extends DTO<T>> dtoClass, Consumer<TemplateWarning> unresolvedReferences) {
        T result;
        try {
            validateTemplate(template);
            // read the holder ONCE for the whole render so a concurrent rebuild cannot mix an old
            // engine with a new tool context or repository
            EngineHolder holder = currentEngineHolder();
            Writer writer = new StringWriter();
            VelocityContext context = newRenderContext(holder, request);
            if (response != null) {
                context.put("response", new HttpResponseTemplateObject(response));
            }
            if (parameters != null && !parameters.isEmpty()) {
                context.put("parameters", parameters);
            }
            if (unresolvedReferences != null) {
                reportUnresolvedReferences(unresolvedReferences).attachToContext(context);
            }
            RequestBodyExtractionHelper bodyExtractionHelper = new RequestBodyExtractionHelper(request, mockServerLogger);
            context.put("jsonPath", new RequestBodyExtractionHelper.JsonPathTool(bodyExtractionHelper));
            context.put("xPath", new RequestBodyExtractionHelper.XPathTool(bodyExtractionHelper));
            renderTemplate(holder, template, context, writer);
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
            throw renderFailure(e, template, request);
        }
        return result;
    }

    /**
     * Build the per-render context chain for the given request with the {@code request} binding already
     * in place. The chain is, outermost first:
     * <pre>
     *   child(request, + caller adds iteration/response/jsonPath/xPath) -&gt;
     *     functionsLayer(own map = the shared sharedFunctionsAndHelpers, read-only) -&gt;
     *       perRenderToolContext(tools)
     * </pre>
     * Only the per-render bindings live in the outermost child; the stateless function/helper map is
     * referenced (never copied) and the request-scoped tool context is created fresh per render. Reads
     * fall through the chain; every {@code put} during a render targets only the outermost child, so the
     * shared map and its layer are never mutated. Output is byte-for-byte identical to copying every
     * binding into a single flat context.
     */
    private VelocityContext newRenderContext(EngineHolder holder, HttpRequest request) {
        // fresh per render: $json/$xml are request-scoped and hold per-request parse state
        VelocityContext functionsLayer = new VelocityContext(sharedFunctionsAndHelpers, holder.toolManager.createContext());
        VelocityContext context = new MacroCallCheckingContext(functionsLayer);
        context.put("request", new HttpRequestTemplateObject(request, mockServerLogger));
        return context;
    }

    /**
     * Velocity's parse and invocation exceptions carry the template line and column, and its messages
     * already state them, so the location is only attached to the exception, not appended.
     */
    static TemplateEvaluationException transformFailure(Exception failure, String template, HttpRequest request) {
        Integer line = null;
        Integer column = null;
        Throwable cause = failure;
        for (int depth = 0; cause != null && depth < 10 && line == null; depth++, cause = cause.getCause()) {
            int lineNumber = -1;
            int columnNumber = -1;
            if (cause instanceof ParseErrorException parseError) {
                lineNumber = parseError.getLineNumber();
                columnNumber = parseError.getColumnNumber();
            } else if (cause instanceof ExtendedParseException located) {
                lineNumber = located.getLineNumber();
                columnNumber = located.getColumnNumber();
            }
            if (lineNumber > 0) {
                line = lineNumber;
                column = columnNumber > 0 ? columnNumber : null;
            }
        }
        return TemplateEvaluationException.transformFailure(failure, template, request, line, column, null, false);
    }

    private static final String RENDERED_AS_WRITTEN = " did not resolve, so it was rendered as written (write $!{...} where a missing value should render nothing)";

    private static EventCartridge reportUnresolvedReferences(Consumer<TemplateWarning> unresolvedReferences) {
        EventCartridge eventCartridge = new EventCartridge();
        eventCartridge.addEventHandler(new InvalidReferenceEventHandler() {
            @Override
            public Object invalidGetMethod(Context context, String reference, Object object, String property, Info info) {
                report(reference + RENDERED_AS_WRITTEN, info);
                return null;
            }

            @Override
            public boolean invalidSetMethod(Context context, String leftReference, String rightReference, Info info) {
                report(rightReference + " did not resolve, so " + leftReference + " was not set", info);
                return false;
            }

            @Override
            public Object invalidMethod(Context context, String reference, Object object, String method, Info info) {
                report(reference + RENDERED_AS_WRITTEN, info);
                return null;
            }

            private void report(String message, Info info) {
                unresolvedReferences.accept(new TemplateWarning(
                    message,
                    info != null && info.getLine() > 0 ? info.getLine() : null,
                    info != null && info.getColumn() > 0 ? info.getColumn() : null
                ));
            }
        });
        return eventCartridge;
    }

    /**
     * The outermost per-render context. Velocity records every macro call on it, so each call counts
     * towards the render's {@link RenderDeadline} and a recursive macro is stopped like a loop.
     */
    private static final class MacroCallCheckingContext extends VelocityContext {
        private MacroCallCheckingContext(VelocityContext innerContext) {
            super(innerContext);
        }

        @Override
        public void pushCurrentMacroName(String macroName) {
            RenderDeadline.check();
            super.pushCurrentMacroName(macroName);
        }
    }

    private void validateTemplate(String template) {
        if (isNotBlank(template) && isNotBlank(configuration.velocityDisallowedText())) {
            Iterable<String> deniedStrings = Splitter.on(",").trimResults().split(configuration.velocityDisallowedText());
            for (String deniedString : deniedStrings) {
                if (template.contains(deniedString)) {
                    throw new UnsupportedOperationException("Found disallowed string \"" + deniedString + "\" in template: " + template);
                }
            }
        }
    }
}

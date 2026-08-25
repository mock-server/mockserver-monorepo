package org.mockserver.mock;

import com.google.common.annotations.VisibleForTesting;
import org.mockserver.authentication.AuthenticationException;
import org.mockserver.authentication.AuthenticationHandler;
import org.mockserver.authentication.ControlPlaneAuthenticationHandlerFactory;
import org.mockserver.closurecallback.websocketregistry.LocalCallbackRegistry;
import org.mockserver.closurecallback.websocketregistry.WebSocketClientRegistry;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.cors.CORSHeaders;
import org.mockserver.file.FileStore;
import org.mockserver.grpc.GrpcProtoDescriptorStore;
import org.mockserver.grpc.GrpcProtoFileCompiler;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.llm.ParsedMessage;
import org.mockserver.log.MockServerEventLog;
import org.mockserver.mock.crud.CrudActionHandler;
import org.mockserver.mock.crud.CrudDataStore;
import org.mockserver.mock.crud.CrudDispatcher;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.matchers.HttpRequestMatcher;
import org.mockserver.matchers.MatchDifference;
import org.mockserver.matchers.MismatchRemediation;
import org.mockserver.memory.MemoryMonitoring;
import org.mockserver.metrics.Metrics;
import org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause;
import org.mockserver.model.*;
import org.mockserver.openapi.OpenAPIConverter;
import org.mockserver.openapi.OpenApiSyncPlanner;
import org.mockserver.persistence.ExpectationFileSystemPersistence;
import org.mockserver.persistence.RecordedExpectationPostProcessor;
import org.mockserver.proxyconfiguration.InetAddressValidator;
import org.mockserver.persistence.ExpectationFileWatcher;
import org.mockserver.responsewriter.ResponseWriter;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.*;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.java.ExpectationToJavaSerializer;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.mockserver.serialization.YamlToJsonConverter;
import org.mockserver.server.initialize.ExpectationInitializerLoader;
import org.mockserver.cluster.ClusterFanIn;
import org.mockserver.cluster.ClusterFanInException;
import org.mockserver.cluster.HttpClusterPeerAccessor;
import org.mockserver.state.InvalidationListener;
import org.mockserver.state.StateBackend;
import org.mockserver.state.StateBackendFactory;
import org.mockserver.time.TimeService;
import org.mockserver.uuid.UUIDService;
import org.mockserver.verify.Verification;
import org.mockserver.verify.VerificationSequence;
import org.slf4j.event.Level;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import static io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE;
import static io.netty.handler.codec.http.HttpHeaderNames.HOST;
import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.apache.commons.lang3.StringUtils.*;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.CLEARED;
import static org.mockserver.log.model.LogEntry.LogMessageType.RETRIEVED;
import static org.mockserver.log.model.LogEntryMessages.RECEIVED_REQUEST_MESSAGE_FORMAT;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;
import static org.mockserver.openapi.OpenAPIParser.OPEN_API_LOAD_ERROR;
import static org.slf4j.event.Level.TRACE;

/**
 * @author jamesdbloom
 */
public class HttpState {

    public static final String LOG_SEPARATOR = NEW_LINE + "------------------------------------" + NEW_LINE;
    public static final String PATH_PREFIX = "/mockserver";
    private static final ThreadLocal<Integer> LOCAL_PORT = new ThreadLocal<>();
    private final String uniqueLoopPreventionHeaderValue = "MockServer_" + UUIDService.getUUID();
    private final MockServerEventLog mockServerLog;
    // The maxWebSocketExpectations actually in force. LocalCallbackRegistry builds its bounded
    // registries lazily and exactly once, so the value handed to it at construction is the one that
    // sizes them for the process lifetime — see applyConfigurationUpdate().
    private final int maxWebSocketExpectationsInForce;
    private final Scheduler scheduler;
    private ExpectationFileSystemPersistence expectationFileSystemPersistence;
    private org.mockserver.persistence.RecordedExpectationFileSystemPersistence recordedExpectationFileSystemPersistence;
    private org.mockserver.persistence.RecordedRequestsFileSystemPersistence recordedRequestsFileSystemPersistence;
    private ExpectationFileWatcher expectationFileWatcher;
    // mockserver
    private final RequestMatchers requestMatchers;
    // G10 phase 2a: pluggable state backend (default in-memory, clustered in 2b+)
    private final StateBackend stateBackend;
    // ADV3: persisted, named library of reusable chaos experiment profiles
    private final org.mockserver.mock.action.http.ChaosProfileLibrary chaosProfileLibrary;
    private final org.mockserver.mock.action.http.LoadScenarioRegistry loadScenarioRegistry;
    // T1.9: opt-in cluster verify/retrieve fan-in coordinator (default OFF = per-node)
    private ClusterFanIn clusterFanIn;
    private final Configuration configuration;
    // Adds CORS headers to dashboard-facing control-plane responses (e.g. service
    // chaos) so the dashboard works when served from another origin (a dev server),
    // matching the unconditional CORS already applied by the metrics and MCP endpoints.
    private final CORSHeaders corsHeaders;
    private final MockServerLogger mockServerLogger;
    private final WebSocketClientRegistry webSocketClientRegistry;
    // serializers
    private ExpectationIdSerializer expectationIdSerializer;
    private RequestDefinitionSerializer requestDefinitionSerializer;
    private LogEventRequestAndResponseSerializer httpRequestResponseSerializer;
    private ExpectationSerializer expectationSerializer;
    private ExpectationSerializer expectationSerializerThatSerializesBodyDefault;
    private OpenAPIExpectationSerializer openAPIExpectationSerializer;
    private ExpectationToJavaSerializer expectationToJavaSerializer;
    private org.mockserver.serialization.code.ExpectationToJavaScriptSerializer expectationToJavaScriptSerializer;
    private org.mockserver.serialization.code.ExpectationToPythonSerializer expectationToPythonSerializer;
    private org.mockserver.serialization.code.ExpectationToGoSerializer expectationToGoSerializer;
    private org.mockserver.serialization.code.ExpectationToCSharpSerializer expectationToCSharpSerializer;
    private org.mockserver.serialization.code.ExpectationToRubySerializer expectationToRubySerializer;
    private org.mockserver.serialization.code.ExpectationToRustSerializer expectationToRustSerializer;
    private org.mockserver.serialization.code.ExpectationToPhpSerializer expectationToPhpSerializer;
    private org.mockserver.serialization.ExpectationExportSerializer expectationExportSerializer;
    private VerificationSerializer verificationSerializer;
    private VerificationSequenceSerializer verificationSequenceSerializer;
    private SloCriteriaSerializer sloCriteriaSerializer;
    private org.mockserver.serialization.LoadScenarioSerializer loadScenarioSerializer;
    private LogEntrySerializer logEntrySerializer;
    private final MemoryMonitoring memoryMonitoring;
    private OpenAPIConverter openAPIConverter;
    private org.mockserver.serialization.har.HarConverter harConverter;
    private HttpRequestSerializer httpRequestSerializer;
    private HttpResponseSerializer httpResponseSerializer;
    private org.mockserver.serialization.curl.HttpRequestToCurlSerializer httpRequestToCurlSerializer;
    // Explicitly installed control-plane authentication handler. When set (embedded users and tests
    // that supply their own handler) it WINS over the configuration-derived handler below.
    private volatile AuthenticationHandler controlPlaneAuthenticationHandler;
    // Configuration-derived control-plane authentication handler, rebuilt whenever the auth-relevant
    // configuration changes. This is what makes enabling control-plane authentication at RUNTIME (system
    // property, Configuration setter, ConfigurationDTO, or PUT /mockserver/configuration) actually take
    // effect: previously the chain was built once at server bootstrap, so a later enable returned 200,
    // echoed back "true" on GET, and left the handler null — and a null handler means "authenticated",
    // leaving the control plane (including the recorded request log) fully open. Held as one immutable
    // holder behind a single volatile field so a concurrent reader can never observe a torn
    // (handler, signature) pair.
    private volatile DerivedControlPlaneAuthenticationHandler derivedControlPlaneAuthenticationHandler;
    private final Object derivedControlPlaneAuthenticationHandlerLock = new Object();

    private static final class DerivedControlPlaneAuthenticationHandler {
        private final String signature;
        private final AuthenticationHandler handler;

        private DerivedControlPlaneAuthenticationHandler(String signature, AuthenticationHandler handler) {
            this.signature = signature;
            this.handler = handler;
        }
    }

    // Memoized control-plane authorizer, keyed on the raw scope-mapping it was built
    // from, so the mapping is parsed (and the authorizer allocated) once and reused
    // across requests, but stays correct if configuration reload changes the mapping.
    // The (authorizer, mapping) pair is held in a single immutable holder behind ONE
    // volatile field so a concurrent reader can never observe a torn (authorizer,
    // mismatched-mapping) pair — it reads the holder once and compares its own mapping.
    private volatile AuthorizerHolder cachedAuthorizerHolder;

    /**
     * Immutable pairing of a {@link org.mockserver.authentication.authorization.ControlPlaneAuthorizer}
     * with the scope mapping it was built from. Published atomically through one volatile
     * field so a reader always sees a self-consistent pair.
     */
    private static final class AuthorizerHolder {
        final java.util.Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> mapping;
        final org.mockserver.authentication.authorization.ControlPlaneAuthorizer authorizer;

        AuthorizerHolder(java.util.Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> mapping,
                         org.mockserver.authentication.authorization.ControlPlaneAuthorizer authorizer) {
            this.mapping = mapping;
            this.authorizer = authorizer;
        }
    }
    private GrpcProtoDescriptorStore grpcDescriptorStore;
    private final FileStore fileStore = new FileStore();
    private final CrudDispatcher crudDispatcher = new CrudDispatcher();
    // last operating mode explicitly set via PUT /mockserver/mode (so GET round-trips CAPTURE,
    // which shares the proxy-on-no-match flag with SPY); reconciled against the live flag on read
    private volatile MockMode mockMode;
    // optional — set by LifeCycle when a runtime LLM backend is configured
    private volatile org.mockserver.llm.client.LlmCompletionService llmCompletionService;
    private volatile org.mockserver.llm.client.LlmBackend llmBackend;
    // optional — set by the runtime (NettyHttpClient) to enable PUT /mockserver/replay
    private volatile java.util.function.Function<HttpRequest, CompletableFuture<HttpResponse>> replayHandler;
    // readiness flag — flipped true once the constructor (incl. synchronous expectation
    // initializers / OpenAPI seeding) has completed. The liveness/status endpoints answer 200 the
    // instant the port binds, but a readiness probe should stay not-ready until seeding finishes so
    // an orchestrator does not route traffic before the seeded expectations exist.
    private volatile boolean initializationComplete = false;

    public static void setPort(final HttpRequest request) {
        if (request != null && request.getSocketAddress() != null) {
            setPort(request.getSocketAddress().getPort());
            request.withSocketAddress(null);
        }
    }

    public static void setPort(final Integer port) {
        LOCAL_PORT.set(port);
    }

    public static void setPort(final Integer... port) {
        if (port != null && port.length > 0) {
            setPort(port[0]);
        }
    }

    public static void setPort(final List<Integer> port) {
        if (port != null && port.size() > 0) {
            setPort(port.get(0));
        }
    }

    public static Integer getPort() {
        return LOCAL_PORT.get();
    }

    public HttpState(Configuration configuration, MockServerLogger mockServerLogger, Scheduler scheduler) {
        this.configuration = configuration;
        this.corsHeaders = new CORSHeaders(configuration);
        this.mockServerLogger = mockServerLogger.setHttpStateHandler(this);
        this.scheduler = scheduler;
        this.webSocketClientRegistry = new WebSocketClientRegistry(configuration, mockServerLogger);
        LocalCallbackRegistry.setMaxWebSocketExpectations(configuration.maxWebSocketExpectations());
        this.maxWebSocketExpectationsInForce = configuration.maxWebSocketExpectations();
        this.mockServerLog = new MockServerEventLog(configuration, mockServerLogger, scheduler, true);
        // G10 phase 2a: create the pluggable state backend (default in-memory, clustered in 2b+).
        this.stateBackend = StateBackendFactory.create(configuration);
        // ADV3: persisted, named chaos-profile library backed by the state backend's
        // CRUD-entity store (survives reset; replicates across the fleet when clustered).
        this.chaosProfileLibrary = new org.mockserver.mock.action.http.ChaosProfileLibrary(stateBackend);
        // Load Scenario Registry: persisted, named registry of load scenario definitions backed by the
        // state backend's CRUD-entity store (survives reset; replicates across the fleet when clustered;
        // preloadable at startup). Mirrors the saved chaos-profile library.
        this.loadScenarioRegistry = new org.mockserver.mock.action.http.LoadScenarioRegistry(stateBackend);
        // G10 phase 1: obtain the expectation store via the pluggable factory (default = standard
        // in-memory RequestMatchers; an optional clustered backend can register an alternative).
        this.requestMatchers = ExpectationStoreFactory.create(configuration, mockServerLogger, scheduler, webSocketClientRegistry);
        this.requestMatchers.setStateBackend(stateBackend);
        // G10 phase 2c: wire invalidation listener so remote cluster writes
        // trigger a node-local view rebuild (reconcileFromBackend). For
        // single-node/LOCAL backends the listener fires locally only (no-op
        // because the node-local CPQ is already in sync from the local put).
        stateBackend.addInvalidationListener(new InvalidationListener() {
            @Override
            public void onChanged(String key) {
                requestMatchers.reconcileFromBackend();
            }

            @Override
            public void onCleared() {
                requestMatchers.reconcileFromBackend();
            }
        });
        // G11: wire chaos registries to the clustered backend for fleet-wide
        // chaos replication. When the backend is not clustered (default), the
        // setStateBackend calls are no-ops and the registries stay node-local.
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().setStateBackend(stateBackend);
        org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().setStateBackend(stateBackend);
        org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().setStateBackend(stateBackend);
        // Install the live configuration on the chaos auto-halt circuit-breaker. Its only production
        // caller is the static Metrics.incrementHttpChaosInjected(...), which has no Configuration in
        // scope, so the settings must be pushed in here instead. This is the same Configuration
        // instance that PUT /mockserver/configuration mutates, so auto-halt settings applied over the
        // REST config API take effect; unset instance values still fall back to ConfigurationProperties.
        org.mockserver.mock.action.http.ChaosAutoHaltMonitor.getInstance().setConfiguration(configuration);
        // Install the live configuration on the LLM provider sniffer for the same reason: it is a
        // wholly-static utility called from several independent static analysis chains (dataset
        // export, optimisation report building, MCP analysis tools, the forward path) that have no
        // Configuration in scope, so llmProvider/llmBaseUrl must be pushed in here rather than
        // threaded through every caller. Unset instance values still fall back to
        // ConfigurationProperties.
        org.mockserver.llm.client.LlmProviderSniffer.setConfiguration(configuration);
        // G11: register a SEPARATE InvalidationListener for chaos reconciliation
        // so that remote writes to chaos stores trigger the node-local rebuild.
        // This is distinct from the expectations reconcile listener above.
        if (stateBackend.isClustered()) {
            stateBackend.addInvalidationListener(new InvalidationListener() {
                @Override
                public void onChanged(String key) {
                    org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().reconcileFromBackend();
                }

                @Override
                public void onCleared() {
                    org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().reconcileFromBackend();
                    org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().reconcileFromBackend();
                }
            });
        }
        Metrics.setActiveExpectationsSupplier(() -> requestMatchers.retrieveActiveExpectations(null));
        Metrics.setClusterMemberCountSupplier(() -> stateBackend.clusterInfo().members().size());
        if (configuration.persistExpectations()) {
            this.expectationFileSystemPersistence = new ExpectationFileSystemPersistence(configuration, mockServerLogger, requestMatchers, stateBackend.blobs());
        }
        if (configuration.persistRecordedExpectations()) {
            this.recordedExpectationFileSystemPersistence = new org.mockserver.persistence.RecordedExpectationFileSystemPersistence(configuration, mockServerLogger, mockServerLog, stateBackend.blobs());
        }
        if (configuration.persistRecordedRequestsToDisk()) {
            this.recordedRequestsFileSystemPersistence = new org.mockserver.persistence.RecordedRequestsFileSystemPersistence(configuration, mockServerLogger);
            mockServerLog.setRecordedRequestConsumer(recordedRequestsFileSystemPersistence::append);
        }
        if (isNotBlank(configuration.initializationJsonPath()) || isNotBlank(configuration.initializationOpenAPIPath()) || isNotBlank(configuration.initializationClass())) {
            ExpectationInitializerLoader expectationInitializerLoader = new ExpectationInitializerLoader(configuration, mockServerLogger, requestMatchers);
            if ((isNotBlank(configuration.initializationJsonPath()) || isNotBlank(configuration.initializationOpenAPIPath())) && configuration.watchInitializationJson()) {
                this.expectationFileWatcher = new ExpectationFileWatcher(configuration, mockServerLogger, requestMatchers, expectationInitializerLoader);
            }
        }
        // G11 follow-up: wire the cross-protocol event bus to the clustered
        // backend for fleet-wide registration replication. When the backend is
        // not clustered (default), setStateBackend is a no-op and the bus stays
        // node-local. Mirrors the chaos registry wiring pattern above.
        CrossProtocolEventBus.getInstance().setStateBackend(stateBackend);
        if (stateBackend.isClustered()) {
            stateBackend.addInvalidationListener(new InvalidationListener() {
                @Override
                public void onChanged(String key) {
                    CrossProtocolEventBus.getInstance().reconcileFromBackend();
                }

                @Override
                public void onCleared() {
                    CrossProtocolEventBus.getInstance().reconcileFromBackend();
                }
            });
        }
        CrossProtocolEventBus.getInstance().setScenarioManager(requestMatchers.getScenarioManager());
        // T1.9: opt-in cluster verify/retrieve fan-in. The per-node event log means a
        // verify/retrieve behind a load balancer sees only local traffic; when enabled
        // (clusterVerifyFanIn=true + clusterVerifyFanInPeers set) this aggregates across
        // peers. Default OFF = unchanged per-node behaviour. Injectable for tests.
        this.clusterFanIn = new ClusterFanIn(configuration, this.mockServerLogger,
            new HttpClusterPeerAccessor(configuration, this.mockServerLogger));
        // Preload load scenario definitions from a JSON file into the registry (LOADED state, staged but
        // not running). Mirrors the expectation initialization-from-file mechanism.
        preloadLoadScenarios();
        this.memoryMonitoring = new MemoryMonitoring(configuration, this.mockServerLog, this.requestMatchers);
        if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(TRACE)
                    .setMessageFormat("log ring buffer created, with size " + configuration.ringBufferSize())
            );
        }
        initGrpcDescriptorStore();
        // All synchronous startup work (expectation initializers, OpenAPI seeding, gRPC descriptor
        // loading) is now complete — flip the readiness flag so the /mockserver/ready probe reports
        // ready. Set last so a partially-constructed HttpState never reports ready.
        this.initializationComplete = true;
    }

    /**
     * @return true once the HttpState constructor (including synchronous expectation initializers and
     * OpenAPI seeding) has completed. Backs the readiness probe (/mockserver/ready), which returns
     * 503 until this is true and 200 thereafter — distinct from the liveness/status endpoints, which
     * answer 200 the instant the port binds.
     */
    public boolean isInitializationComplete() {
        return initializationComplete;
    }

    private void initGrpcDescriptorStore() {
        this.grpcDescriptorStore = new GrpcProtoDescriptorStore(mockServerLogger);
        if (configuration.grpcEnabled()) {
            String descriptorDir = configuration.grpcDescriptorDirectory();
            if (isNotBlank(descriptorDir)) {
                grpcDescriptorStore.loadDescriptorDirectory(java.nio.file.Paths.get(descriptorDir));
            }
            String protoDir = configuration.grpcProtoDirectory();
            if (isNotBlank(protoDir)) {
                new GrpcProtoFileCompiler(mockServerLogger, configuration.grpcProtocPath()).compileDirectory(java.nio.file.Paths.get(protoDir), grpcDescriptorStore);
            }
        }
    }

    public GrpcProtoDescriptorStore getGrpcDescriptorStore() {
        return grpcDescriptorStore;
    }

    public FileStore getFileStore() {
        return fileStore;
    }

    public CrudDispatcher getCrudDispatcher() {
        return crudDispatcher;
    }

    /**
     * The control-plane authentication handler currently in force, or {@code null} when the control plane
     * is unauthenticated.
     *
     * <p>An explicitly {@link #setControlPlaneAuthenticationHandler installed} handler wins. Otherwise the
     * handler is DERIVED from the live {@link Configuration} and rebuilt whenever the auth-relevant
     * configuration changes, so enabling (or disabling) control-plane authentication at runtime through
     * any configuration route takes effect at the enforcement point instead of being accepted and ignored.
     */
    public AuthenticationHandler getControlPlaneAuthenticationHandler() {
        AuthenticationHandler explicitHandler = controlPlaneAuthenticationHandler;
        if (explicitHandler != null) {
            return explicitHandler;
        }
        if (!ControlPlaneAuthenticationHandlerFactory.authenticationRequired(configuration)) {
            // fast path for the default (unauthenticated) control plane: three boolean reads, no signature
            // built and no handler retained
            return null;
        }
        String signature = ControlPlaneAuthenticationHandlerFactory.signature(configuration);
        DerivedControlPlaneAuthenticationHandler derived = derivedControlPlaneAuthenticationHandler;
        if (derived != null && derived.signature.equals(signature)) {
            return derived.handler;
        }
        synchronized (derivedControlPlaneAuthenticationHandlerLock) {
            derived = derivedControlPlaneAuthenticationHandler;
            if (derived == null || !derived.signature.equals(signature)) {
                derived = new DerivedControlPlaneAuthenticationHandler(
                    signature,
                    ControlPlaneAuthenticationHandlerFactory.build(configuration, mockServerLogger)
                );
                derivedControlPlaneAuthenticationHandler = derived;
            }
            return derived.handler;
        }
    }

    public void setControlPlaneAuthenticationHandler(AuthenticationHandler controlPlaneAuthenticationHandler) {
        this.controlPlaneAuthenticationHandler = controlPlaneAuthenticationHandler;
    }

    /**
     * Install the replay handler that re-issues an {@link HttpRequest} to its
     * target and returns the upstream response. Called by the runtime (e.g.
     * {@code HttpRequestHandler} in the Netty module) after construction,
     * wiring the existing {@code NettyHttpClient} so that
     * {@code PUT /mockserver/replay} can delegate without core depending on
     * the client directly.
     */
    public void setReplayHandler(java.util.function.Function<HttpRequest, CompletableFuture<HttpResponse>> replayHandler) {
        this.replayHandler = replayHandler;
    }

    public Configuration getConfiguration() {
        return configuration;
    }

    /**
     * Install the LLM completion service and default backend for runtime features
     * that call out to an LLM (e.g. AI stub generation). Called by LifeCycle when
     * a backend is configured; null-safe — when not called the stub generation
     * endpoint falls back to template-based stubs.
     */
    public void setLlmCompletionService(org.mockserver.llm.client.LlmCompletionService llmCompletionService,
                                        org.mockserver.llm.client.LlmBackend llmBackend) {
        this.llmCompletionService = llmCompletionService;
        this.llmBackend = llmBackend;
    }

    public MockServerLogger getMockServerLogger() {
        return mockServerLogger;
    }

    public void clear(HttpRequest request) {
        final String logCorrelationId = UUIDService.getUUID();
        // Namespace-scoped clear: ?namespace=T (or the configured namespace header)
        // removes only that tenant's expectations, leaving other namespaces and
        // global expectations intact. Takes precedence over request-matcher / id
        // clearing for expectations; logs are not namespaced so are left untouched.
        String namespaceFilter = resolveNamespaceFilter(request);
        RequestDefinition requestDefinition = null;
        ExpectationId expectationId = null;
        if (isNotBlank(request.getBodyAsString())) {
            String body = request.getBodyAsJsonOrXmlString();
            try {
                expectationId = getExpectationIdSerializer().deserialize(body);
            } catch (Throwable throwable) {
                // assume not expectationId
                requestDefinition = getRequestDefinitionSerializer().deserialize(body);
            }
            if (expectationId != null) {
                requestDefinition = resolveExpectationId(expectationId);
            }
        }
        if (requestDefinition != null) {
            requestDefinition.withLogCorrelationId(logCorrelationId);
        }
        try {
            ClearType type = ClearType.valueOf(defaultIfEmpty(request.getFirstQueryStringParameter("type").toUpperCase(), "ALL"));
            switch (type) {
                case LOG:
                    mockServerLog.clear(requestDefinition);
                    break;
                case EXPECTATIONS:
                    if (isNotBlank(namespaceFilter)) {
                        requestMatchers.clearByNamespace(namespaceFilter, logCorrelationId);
                    } else if (expectationId != null) {
                        requestMatchers.clear(expectationId, logCorrelationId);
                    } else {
                        requestMatchers.clear(requestDefinition);
                    }
                    break;
                case ALL:
                    if (isNotBlank(namespaceFilter)) {
                        // Namespace-scoped: clear only this tenant's expectations.
                        // The event log is not namespaced, so it is intentionally
                        // left intact to avoid clearing other tenants' request logs.
                        requestMatchers.clearByNamespace(namespaceFilter, logCorrelationId);
                    } else {
                        mockServerLog.clear(requestDefinition);
                        if (expectationId != null) {
                            requestMatchers.clear(expectationId, logCorrelationId);
                        } else {
                            requestMatchers.clear(requestDefinition);
                        }
                    }
                    break;
            }
        } catch (IllegalArgumentException iae) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("exception handling request:{}error:{}")
                    .setArguments(request, iae.getMessage())
                    .setThrowable(iae)
            );
            throw new IllegalArgumentException("\"" + request.getFirstQueryStringParameter("type") + "\" is not a valid value for \"type\" parameter, only the following values are supported " + Arrays.stream(ClearType.values()).map(input -> input.name().toLowerCase()).collect(Collectors.toList()));
        }
    }

    /**
     * Resolves the namespace (tenant) filter for a control-plane request (clear / retrieve).
     * The {@code ?namespace=T} query parameter takes precedence; if absent, the configured
     * {@code matchNamespaceHeader} header on the control-plane request is used. Returns null
     * when neither is present (i.e. an unscoped, all-namespaces operation).
     */
    private String resolveNamespaceFilter(HttpRequest request) {
        String namespace = request.getFirstQueryStringParameter("namespace");
        if (isNotBlank(namespace)) {
            return namespace;
        }
        String headerName = configuration.matchNamespaceHeader();
        if (isNotBlank(headerName)) {
            String headerValue = request.getFirstHeader(headerName);
            if (isNotBlank(headerValue)) {
                return headerValue;
            }
        }
        return null;
    }

    private RequestDefinition resolveExpectationId(ExpectationId expectationId) {
        return requestMatchers
            .retrieveRequestDefinitions(Collections.singletonList(expectationId))
            .findFirst()
            .orElse(null);
    }

    private List<RequestDefinition> resolveExpectationIds(List<ExpectationId> expectationIds) {
        return requestMatchers
            .retrieveRequestDefinitions(expectationIds)
            .collect(Collectors.toList());
    }

    public void reset() {
        requestMatchers.reset();
        requestMatchers.getScenarioManager().cancelAllPendingTransitions();
        CrossProtocolEventBus.getInstance().reset();
        mockServerLog.reset();
        webSocketClientRegistry.reset();
        crudDispatcher.reset();
        fileStore.reset();
        org.mockserver.llm.LlmQuotaRegistry.getInstance().reset();
        org.mockserver.llm.OpenAiResponsesStore.getInstance().reset();
        org.mockserver.mock.action.http.HttpQuotaRegistry.getInstance().reset();
        org.mockserver.ratelimit.RateLimitRegistry.getInstance().reset();
        org.mockserver.mock.action.http.RecoveryAttemptRegistry.getInstance().reset();
        org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance().reset();
        org.mockserver.mock.action.http.ChaosAutoHaltMonitor.getInstance().reset();
        org.mockserver.mock.action.http.ChaosExperimentOrchestrator.getInstance().reset();
        org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance().reset();
        org.mockserver.slo.SloSampleStore.getInstance().reset();
        org.mockserver.mock.action.http.LlmCostBudgetMonitor.getInstance().reset();
        org.mockserver.mock.action.http.ForwardCircuitBreaker.getInstance().reset();
        org.mockserver.mock.action.http.TcpChaosRegistry.getInstance().reset();
        org.mockserver.mock.action.http.PreemptionSimulator.getInstance().reset();
        org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance().reset();
        org.mockserver.grpc.GrpcHealthRegistry.getInstance().reset();
        org.mockserver.oidc.OidcAuthorizationStore.getInstance().reset();
        org.mockserver.saml.SamlAssertionStore.getInstance().reset();
        org.mockserver.scim.ScimResourceStore.getInstance().reset();
        org.mockserver.wasm.WasmStore.getInstance().reset();
        org.mockserver.mock.drift.DriftStore.getInstance().clear();
        org.mockserver.mock.audit.AuditStore.getInstance().clear();
        CassetteRegistry.getInstance().reset();
        org.mockserver.mock.dns.DnsIntentRegistry.getInstance().clear();
        org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance().reset();
        org.mockserver.mock.breakpoint.BreakpointCallbackDispatcher.getInstance().reset();
        org.mockserver.mock.breakpoint.StreamFrameCallbackDispatcher.getInstance().reset();
        org.mockserver.mock.breakpoint.StreamFrameBreakpointRegistry.getInstance().reset();
        org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().clear();
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(CLEARED)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request())
                    .setMessageFormat("resetting all expectations and request logs")
            );
        }
        new Scheduler.SchedulerThreadFactory("MockServer Memory Metrics").newThread(() -> {
            try {
                SECONDS.sleep(10);
                memoryMonitoring.logMemoryMetrics();
            } catch (InterruptedException ie) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception handling reset request:{}")
                        .setArguments(ie.getMessage())
                        .setThrowable(ie)
                );
            }
        });
    }

    public List<Expectation> add(OpenAPIExpectation openAPIExpectation) {
        // A spec referenced by URL/file may have changed since it was last parsed and cached (the parse is
        // LRU-cached for up to 30 minutes keyed by the reference string). Re-importing is an explicit signal
        // to pick up the current content, so evict the cache entry first. Inline payloads are keyed by their
        // content, so they need no eviction (a changed payload is a different key).
        if (org.mockserver.openapi.OpenAPIParser.isSpecUrl(openAPIExpectation.getSpecUrlOrPayload())) {
            org.mockserver.openapi.OpenAPIParser.clearCache(openAPIExpectation.getSpecUrlOrPayload());
        }
        List<Expectation> newExpectations = getOpenAPIConverter().buildExpectations(
            openAPIExpectation.getSpecUrlOrPayload(),
            openAPIExpectation.getOperationsAndResponses(),
            openAPIExpectation.getContextPathPrefix()
        );

        // Incremental sync: determine the namespace prefixes covered by this
        // import, find stale expectations in those namespaces, and prune them.
        Set<String> newIds = newExpectations.stream()
            .map(Expectation::getId)
            .collect(Collectors.toSet());
        Set<String> namespacePrefixes = newIds.stream()
            .filter(id -> id.startsWith(OpenApiSyncPlanner.OPENAPI_ID_PREFIX))
            .map(id -> {
                // Extract "openapi:<specKey>:" prefix — everything up to and including the second ':'
                int secondColon = id.indexOf(':', OpenApiSyncPlanner.OPENAPI_ID_PREFIX.length());
                return secondColon >= 0 ? id.substring(0, secondColon + 1) : id + ":";
            })
            .collect(Collectors.toSet());
        if (!namespacePrefixes.isEmpty()) {
            List<String> existingIds = requestMatchers.retrieveActiveExpectations(null).stream()
                .map(Expectation::getId)
                .collect(Collectors.toList());
            Set<String> toPrune = OpenApiSyncPlanner.idsToPrune(existingIds, newIds, namespacePrefixes);
            String logCorrelationId = UUIDService.getUUID();
            for (String pruneId : toPrune) {
                requestMatchers.clear(ExpectationId.expectationId(pruneId), logCorrelationId);
            }
        }

        // Upsert the new expectations (add() does upsert-by-id)
        return newExpectations.stream()
            .map(this::add)
            .flatMap(List::stream)
            .collect(Collectors.toList());
    }

    public List<Expectation> add(Expectation... expectations) {
        List<Expectation> upsertedExpectations = new ArrayList<>();
        for (Expectation expectation : expectations) {
            // validate steps if present
            String stepsError = expectation.validateSteps();
            if (stepsError != null) {
                throw new IllegalArgumentException("invalid expectation steps: " + stepsError);
            }
            RequestDefinition requestDefinition = expectation.getHttpRequest();
            if (requestDefinition instanceof HttpRequest) {
                final String hostHeader = ((HttpRequest) requestDefinition).getFirstHeader(HOST.toString());
                if (isNotBlank(hostHeader)) {
                    scheduler.submit(() -> configuration.addSubjectAlternativeName(hostHeader));
                }
            }
            upsertedExpectations.add(requestMatchers.add(expectation, Cause.API));
        }
        return upsertedExpectations;
    }

    public Expectation firstMatchingExpectation(RequestDefinition request) {
        if (requestMatchers.isEmpty()) {
            return null;
        } else {
            return requestMatchers.firstMatchingExpectation(request);
        }
    }

    /**
     * Side-effect-free probe: returns the first matching expectation WITHOUT consuming the
     * match (no Times decrement, no scenario transition, no responseInProgress, no metrics).
     * Note: the underlying matcher evaluation may still emit INFO-level EXPECTATION_MATCHED /
     * EXPECTATION_NOT_MATCHED diagnostic logs; this method avoids the consuming side-effects
     * only. Used by the gRPC bidi router to inspect the action type before committing
     * to a handler — the real consuming match happens separately on the committed path.
     */
    public Expectation peekFirstMatchingExpectation(RequestDefinition request) {
        if (requestMatchers.isEmpty()) {
            return null;
        }
        return requestMatchers.peekFirstMatchingExpectation(request);
    }

    /**
     * Returns the first expectation whose matcher has respondBeforeBody=true, has no body matcher,
     * and matches the supplied headers-only request. Used by the early-response path that runs
     * before the request body is aggregated.
     */
    public Expectation firstMatchingEarlyExpectation(HttpRequest headersOnly) {
        if (requestMatchers.isEmpty()) {
            return null;
        }
        return requestMatchers.firstMatchingEarlyExpectation(headersOnly);
    }

    @VisibleForTesting
    public List<Expectation> allMatchingExpectation(HttpRequest request) {
        if (requestMatchers.isEmpty()) {
            return Collections.emptyList();
        } else {
            // Forward matching ("does each expectation match this concrete request?"),
            // NOT the filter/reverse semantics of retrieveActiveExpectations — the
            // incoming request carries headers/cookies bare stubs lack, so reverse
            // matching would return nothing (this is what silently broke drift analysis).
            return requestMatchers.retrieveExpectationsMatchingRequest(request);
        }
    }

    public void postProcess(Expectation expectation) {
        requestMatchers.postProcess(expectation);
    }

    public java.util.Map<MatchDifference.Field, java.util.List<String>> findClosestMatchDiff(HttpRequest request) {
        if (requestMatchers.isEmpty()) {
            return null;
        }
        return requestMatchers.findClosestMatchDiff(request);
    }

    public RequestMatchers.ClosestMatchHint findClosestMatchHint(HttpRequest request) {
        if (requestMatchers.isEmpty()) {
            return null;
        }
        return requestMatchers.findClosestMatchHint(request);
    }

    private static final int DEBUG_MISMATCH_MAX_EXPECTATIONS = 100;

    public HttpResponse debugMismatch(HttpRequest request) {
        final String correlationId = UUIDService.getUUID();
        final String timestamp = java.time.Instant.now().toString();
        try {
            final RequestDefinition requestDefinition = isNotBlank(request.getBodyAsString())
                ? getRequestDefinitionSerializer().deserialize(request.getBodyAsJsonOrXmlString())
                : request();
            if (!(requestDefinition instanceof HttpRequest)) {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode errorNode = errorMapper.createObjectNode();
                errorNode.put("error", "debugMismatch only supports HttpRequest definitions");
                errorNode.put("correlationId", correlationId);
                errorNode.put("timestamp", timestamp);
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorNode), MediaType.JSON_UTF_8);
            }
            HttpRequest debugRequest = (HttpRequest) requestDefinition;

            List<HttpRequestMatcher> matchers = requestMatchers.retrieveRequestMatchers(null);
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            com.fasterxml.jackson.databind.node.ArrayNode expectationResults = objectMapper.createArrayNode();

            int closestMatchFailures = Integer.MAX_VALUE;
            String closestMatchId = null;
            int closestMatchedFields = 0;
            // Approximate denominator, deliberately: this is the size of the whole match-field enum,
            // but HttpRequestPropertiesMatcher gates only 12 of those 18 fields. OPERATION, OPENAPI,
            // DNS_NAME, DNS_TYPE, DNS_CLASS and BINARY_BODY belong to other matcher types and are never
            // assessed for an HTTP expectation, yet are counted as matched below. Hard-coding 12 would be
            // no more correct — the evaluated set varies per expectation (PATH_PARAMETERS only when the
            // expectation declares them, JWT only when configured), so an exact denominator would require
            // the matcher to report which fields it actually evaluated. matchedFieldCount is therefore
            // meaningful for comparing expectations against each other, not as an absolute score.
            int totalFields = MatchDifference.Field.values().length;
            boolean truncated = matchers.size() > DEBUG_MISMATCH_MAX_EXPECTATIONS;
            int evaluateCount = Math.min(matchers.size(), DEBUG_MISMATCH_MAX_EXPECTATIONS);

            for (int i = 0; i < evaluateCount; i++) {
                HttpRequestMatcher matcher = matchers.get(i);
                com.fasterxml.jackson.databind.node.ObjectNode matchResult = objectMapper.createObjectNode();
                Expectation expectation = matcher.getExpectation();
                if (expectation != null) {
                    matchResult.put("expectationId", expectation.getId());
                    if (expectation.getHttpRequest() instanceof HttpRequest) {
                        HttpRequest expRequest = (HttpRequest) expectation.getHttpRequest();
                        matchResult.put("expectationPath", expRequest.getPath() != null ? expRequest.getPath().getValue() : "");
                        matchResult.put("expectationMethod", expRequest.getMethod() != null ? expRequest.getMethod().getValue() : "");
                    }
                }

                HttpRequest clonedRequest = debugRequest.clone();
                // collectAllDifferences: rank by closeness, which requires knowing how many fields
                // actually differed. Matching normally fails fast on the first non-matching field,
                // so every mismatched expectation would record exactly one difference, every
                // candidate would tie on the count, and "closest" would collapse to "first
                // registered". The flag is scoped to this evaluation and does not affect the
                // matching path for real requests.
                MatchDifference matchDifference = new MatchDifference(true, clonedRequest)
                    .suppressMatchResultLogging()
                    .collectAllDifferences();
                boolean matches = matcher.matches(matchDifference, clonedRequest);
                matchResult.put("matches", matches);

                if (!matches) {
                    java.util.Map<MatchDifference.Field, List<String>> allDifferences = matchDifference.getAllDifferences();
                    int failures = allDifferences.size();
                    int matchedFields = totalFields - failures;
                    matchResult.put("matchedFieldCount", matchedFields);
                    matchResult.put("totalFieldCount", totalFields);

                    com.fasterxml.jackson.databind.node.ObjectNode differences = objectMapper.createObjectNode();
                    for (java.util.Map.Entry<MatchDifference.Field, List<String>> diffEntry : allDifferences.entrySet()) {
                        com.fasterxml.jackson.databind.node.ArrayNode fieldDiffs = differences.putArray(diffEntry.getKey().getName());
                        for (String diff : diffEntry.getValue()) {
                            fieldDiffs.add(diff);
                        }
                    }
                    matchResult.set("differences", differences);

                    if (failures < closestMatchFailures && expectation != null) {
                        closestMatchFailures = failures;
                        closestMatchId = expectation.getId();
                        closestMatchedFields = matchedFields;
                    }
                } else {
                    matchResult.put("matchedFieldCount", totalFields);
                    matchResult.put("totalFieldCount", totalFields);
                }

                expectationResults.add(matchResult);
            }

            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("correlationId", correlationId);
            resultNode.put("timestamp", timestamp);
            resultNode.put("totalExpectations", matchers.size());
            resultNode.put("evaluatedExpectations", evaluateCount);
            if (truncated) {
                resultNode.put("truncated", true);
                resultNode.put("maxExpectationsEvaluated", DEBUG_MISMATCH_MAX_EXPECTATIONS);
            }
            if (closestMatchId != null) {
                com.fasterxml.jackson.databind.node.ObjectNode closestMatch = objectMapper.createObjectNode();
                closestMatch.put("expectationId", closestMatchId);
                closestMatch.put("matchedFields", closestMatchedFields);
                closestMatch.put("totalFields", totalFields);
                resultNode.set("closestMatch", closestMatch);
            }
            resultNode.set("results", expectationResults);

            return response()
                .withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(correlationId)
                    .setMessageFormat("exception handling debugMismatch request:{}error:{}")
                    .setArguments(request, e.getMessage())
                    .setThrowable(e)
            );
            try {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode errorNode = errorMapper.createObjectNode();
                errorNode.put("error", "failed to debug request mismatch: " + e.getMessage());
                errorNode.put("correlationId", correlationId);
                errorNode.put("timestamp", timestamp);
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorNode), MediaType.JSON_UTF_8);
            } catch (Exception jsonError) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"failed to debug request mismatch\"}", MediaType.JSON_UTF_8);
            }
        }
    }

    private static final int EXPLAIN_UNMATCHED_MAX_EXPECTATIONS = 50;
    static final int EXPLAIN_UNMATCHED_EVALUATION_BUDGET = 500;

    /**
     * Retrieves recent requests that matched no expectation and, for each, computes
     * ranked closest-expectation diagnostics with remediation hints.
     *
     * @param request the control-plane request (body may contain {@code {"limit":N}})
     * @return a JSON response containing an array of unmatched requests with diagnostics
     */
    public HttpResponse explainUnmatched(HttpRequest request) {
        final String correlationId = UUIDService.getUUID();
        final String timestamp = java.time.Instant.now().toString();
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();

            // parse optional limit from body
            int limit = 10;
            if (isNotBlank(request.getBodyAsString())) {
                try {
                    com.fasterxml.jackson.databind.JsonNode body = objectMapper.readTree(request.getBodyAsJsonOrXmlString());
                    if (body.has("limit")) {
                        limit = body.get("limit").asInt(10);
                    }
                } catch (Exception ignored) {
                    // no valid JSON body -- use default
                }
            }

            CompletableFuture<HttpResponse> responseFuture = new CompletableFuture<>();

            mockServerLog.retrieveUnmatchedRequests(limit, unmatchedEntries -> {
                try {
                    com.fasterxml.jackson.databind.node.ArrayNode unmatchedArray = objectMapper.createArrayNode();
                    int totalEvaluations = 0;
                    boolean truncated = false;

                    for (LogEntry entry : unmatchedEntries) {
                        if (truncated) {
                            break;
                        }
                        RequestDefinition requestDef = entry.getHttpRequest();
                        if (!(requestDef instanceof HttpRequest)) {
                            continue;
                        }
                        HttpRequest unmatchedRequest = (HttpRequest) requestDef;
                        com.fasterxml.jackson.databind.node.ObjectNode requestNode = objectMapper.createObjectNode();
                        requestNode.put("timestamp", entry.getTimestamp());
                        requestNode.put("method", unmatchedRequest.getMethod() != null ? unmatchedRequest.getMethod().getValue() : "");
                        requestNode.put("path", unmatchedRequest.getPath() != null ? unmatchedRequest.getPath().getValue() : "");

                        // compute per-expectation diffs, ranked by closeness
                        List<HttpRequestMatcher> matchers = requestMatchers.retrieveRequestMatchers(null);
                        // approximate denominator — see the note in debugMismatch above: six of these
                        // 18 fields are never assessed for an HTTP expectation but count as matched
                        int totalFields = MatchDifference.Field.values().length;
                        int evaluateCount = Math.min(matchers.size(), EXPLAIN_UNMATCHED_MAX_EXPECTATIONS);

                        // collect results with their failure count for sorting
                        List<com.fasterxml.jackson.databind.node.ObjectNode> expResults = new ArrayList<>();

                        for (int i = 0; i < evaluateCount; i++) {
                            if (totalEvaluations >= EXPLAIN_UNMATCHED_EVALUATION_BUDGET) {
                                truncated = true;
                                break;
                            }
                            HttpRequestMatcher matcher = matchers.get(i);
                            Expectation expectation = matcher.getExpectation();
                            if (expectation == null) {
                                continue;
                            }

                            HttpRequest clonedRequest = unmatchedRequest.clone();
                            // collectAllDifferences: these results are sorted by differingFieldCount
                            // below, which is only meaningful if every differing field was counted.
                            // Fail-fast stops at the first one, leaving every mismatched expectation
                            // on a count of exactly 1 — so the sort would tie for all of them and,
                            // being stable, would simply preserve registration order while claiming
                            // to rank by closeness.
                            MatchDifference matchDifference = new MatchDifference(true, clonedRequest)
                                .suppressMatchResultLogging()
                                .collectAllDifferences();
                            boolean matches = matcher.matches(matchDifference, clonedRequest);
                            totalEvaluations++;

                            com.fasterxml.jackson.databind.node.ObjectNode expResult = objectMapper.createObjectNode();
                            expResult.put("expectationId", expectation.getId());
                            if (expectation.getHttpRequest() instanceof HttpRequest) {
                                HttpRequest expReq = (HttpRequest) expectation.getHttpRequest();
                                expResult.put("expectationPath", expReq.getPath() != null ? expReq.getPath().getValue() : "");
                                expResult.put("expectationMethod", expReq.getMethod() != null ? expReq.getMethod().getValue() : "");
                            }
                            expResult.put("matches", matches);

                            java.util.Map<MatchDifference.Field, List<String>> allDifferences = matchDifference.getAllDifferences();
                            int failures = matches ? 0 : allDifferences.size();
                            int matchedFields = totalFields - failures;
                            expResult.put("matchedFieldCount", matchedFields);
                            expResult.put("totalFieldCount", totalFields);
                            expResult.put("differingFieldCount", failures);

                            if (!matches && !allDifferences.isEmpty()) {
                                com.fasterxml.jackson.databind.node.ObjectNode differences = objectMapper.createObjectNode();
                                for (java.util.Map.Entry<MatchDifference.Field, List<String>> diffEntry : allDifferences.entrySet()) {
                                    com.fasterxml.jackson.databind.node.ArrayNode fieldDiffs = differences.putArray(diffEntry.getKey().getName());
                                    for (String diff : diffEntry.getValue()) {
                                        fieldDiffs.add(diff);
                                    }
                                }
                                expResult.set("differences", differences);

                                // add remediation hints
                                java.util.Map<MatchDifference.Field, String> hints = MismatchRemediation.allHints(allDifferences);
                                if (!hints.isEmpty()) {
                                    com.fasterxml.jackson.databind.node.ObjectNode remediationNode = objectMapper.createObjectNode();
                                    for (java.util.Map.Entry<MatchDifference.Field, String> hintEntry : hints.entrySet()) {
                                        remediationNode.put(hintEntry.getKey().getName(), hintEntry.getValue());
                                    }
                                    expResult.set("remediation", remediationNode);
                                }
                            }

                            expResults.add(expResult);
                        }

                        // sort by fewest differing fields first (closest match first)
                        expResults.sort((a, b) -> Integer.compare(
                            a.path("differingFieldCount").asInt(Integer.MAX_VALUE),
                            b.path("differingFieldCount").asInt(Integer.MAX_VALUE)
                        ));

                        com.fasterxml.jackson.databind.node.ArrayNode closestExpectations = objectMapper.createArrayNode();
                        for (com.fasterxml.jackson.databind.node.ObjectNode expResult : expResults) {
                            closestExpectations.add(expResult);
                        }
                        requestNode.set("closestExpectations", closestExpectations);
                        requestNode.put("totalExpectationsEvaluated", expResults.size());

                        unmatchedArray.add(requestNode);
                    }

                    com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
                    resultNode.put("correlationId", correlationId);
                    resultNode.put("timestamp", timestamp);
                    resultNode.put("unmatchedRequestCount", unmatchedArray.size());
                    resultNode.put("truncated", truncated);
                    resultNode.set("unmatchedRequests", unmatchedArray);

                    responseFuture.complete(response()
                        .withStatusCode(OK.code())
                        .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8));
                } catch (Exception e) {
                    responseFuture.completeExceptionally(e);
                }
            });

            return responseFuture.get(configuration.maxFutureTimeoutInMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(correlationId)
                    .setMessageFormat("exception handling explainUnmatched request:{}error:{}")
                    .setArguments(request, e.getMessage())
                    .setThrowable(e)
            );
            try {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                com.fasterxml.jackson.databind.node.ObjectNode errorNode = errorMapper.createObjectNode();
                errorNode.put("error", "failed to explain unmatched requests: " + e.getMessage());
                errorNode.put("correlationId", correlationId);
                errorNode.put("timestamp", timestamp);
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errorNode), MediaType.JSON_UTF_8);
            } catch (Exception jsonError) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"failed to explain unmatched requests\"}", MediaType.JSON_UTF_8);
            }
        }
    }

    public void log(LogEntry logEntry) {
        if (mockServerLog != null) {
            mockServerLog.add(logEntry);
        }
    }

    public HttpResponse retrieve(HttpRequest request) {
        final String logCorrelationId = UUIDService.getUUID();
        CompletableFuture<HttpResponse> httpResponseFuture = new CompletableFuture<>();
        HttpResponse response = response().withStatusCode(OK.code());
        if (request != null) {
            try {
                final RequestDefinition requestDefinition = isNotBlank(request.getBodyAsString()) ? getRequestDefinitionSerializer().deserialize(request.getBodyAsJsonOrXmlString()) : request();
                requestDefinition.withLogCorrelationId(logCorrelationId);
                ExpectationId expectationId = null;
                if (isNotBlank(request.getBodyAsString())) {
                    String body = request.getBodyAsJsonOrXmlString();
                    try {
                        expectationId = getExpectationIdSerializer().deserialize(body);
                    } catch (Throwable throwable) {
                        // assume not expectationId
                        requestDefinition = getRequestDefinitionSerializer().deserialize(body);
                    }
                    if (expectationId != null) {
                        requestDefinition = resolveExpectationId(expectationId);
                    }
                }
                Format format = Format.valueOf(defaultIfEmpty(request.getFirstQueryStringParameter("format").toUpperCase(), "JSON"));
                RetrieveType type = RetrieveType.valueOf(defaultIfEmpty(request.getFirstQueryStringParameter("type").toUpperCase(), "REQUESTS"));
                final String correlationIdFilter = request.getFirstQueryStringParameter("correlationId");
                // Optional namespace (tenant) filter for ACTIVE_EXPECTATIONS retrieval:
                // ?namespace=T (or the configured namespace header) returns only that
                // tenant's expectations plus global (no-namespace) expectations.
                final String namespaceFilter = resolveNamespaceFilter(request);

                // T1.9 cluster verify/retrieve fan-in. When ?fanInLocalOnly=true the caller is a peer
                // fan-in query — serve ONLY this node's log (infinite-recursion guard). Otherwise, when
                // fan-in is enabled and configured, REQUESTS/REQUEST_RESPONSES retrieval aggregates each
                // peer's LOCAL log with this node's before formatting.
                final boolean fanInLocalOnly = Boolean.parseBoolean(request.getFirstQueryStringParameter("fanInLocalOnly"));
                final boolean applyFanIn = !fanInLocalOnly
                    && clusterFanIn != null
                    && clusterFanIn.enabled()
                    && (type == RetrieveType.REQUESTS || type == RetrieveType.REQUEST_RESPONSES);

                // Record-and-forward one-command round-trip (Unit R): when ?forwardUnmatchedTo=<upstream>
                // is supplied, enable record-and-forward of unmatched requests to that upstream for the
                // session. Subsequent traffic that matches no expectation is forwarded to the upstream and
                // captured as a recorded expectation, which the same/next retrieve returns (deduplicated and
                // templatized when deduplicateRecordedExpectations is on) in the requested format. Recording
                // is inherently traffic-driven: this call only arms recording — it does not synthesise traffic.
                final String forwardUnmatchedTo = request.getFirstQueryStringParameter("forwardUnmatchedTo");
                if (isNotBlank(forwardUnmatchedTo)) {
                    final HttpResponse forwardSetupError = enableRecordAndForward(forwardUnmatchedTo, logCorrelationId);
                    if (forwardSetupError != null) {
                        return forwardSetupError;
                    }
                }

                switch (type) {
                    case LOGS: {
                        // Materialize the (cheap) List<LogEntry> on the disruptor consumer thread, then
                        // format the response body on THIS caller thread — formatting potentially many large
                        // captured log entries inside the single log-consumer callback raced the retrieve future
                        // timeout and stalled all further logging (see awaitRetrieve / bug #3).
                        List<LogEntry> logEntries = awaitRetrieve(
                            consumer -> {
                                if (isNotBlank(correlationIdFilter)) {
                                    mockServerLog.retrieveLogEntriesByCorrelationId(correlationIdFilter, consumer);
                                } else {
                                    mockServerLog.retrieveMessageLogEntries(requestDefinition, consumer);
                                }
                            },
                            logCorrelationId, request
                        );
                        if (format == Format.LOG_ENTRIES) {
                            response.withBody(
                                getLogEntrySerializer().serialize(logEntries),
                                MediaType.JSON_UTF_8
                            );
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(RETRIEVED)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(logCorrelationId)
                                        .setHttpRequest(requestDefinition)
                                        .setMessageFormat("retrieved log entries in log_entries format that match:{}")
                                        .setArguments(requestDefinition)
                                );
                            }
                        } else {
                            StringBuilder stringBuffer = new StringBuilder();
                            for (int i = 0; i < logEntries.size(); i++) {
                                LogEntry messageLogEntry = logEntries.get(i);
                                stringBuffer
                                    .append(messageLogEntry.getTimestamp())
                                    .append(" - ")
                                    .append(messageLogEntry.getMessage());
                                if (i < logEntries.size() - 1) {
                                    stringBuffer.append(LOG_SEPARATOR);
                                }
                            }
                            stringBuffer.append(NEW_LINE);
                            response.withBody(stringBuffer.toString(), MediaType.PLAIN_TEXT_UTF_8);
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setType(RETRIEVED)
                                        .setLogLevel(Level.INFO)
                                        .setCorrelationId(logCorrelationId)
                                        .setHttpRequest(requestDefinition)
                                        .setMessageFormat("retrieved logs that match:{}")
                                        .setArguments(requestDefinition)
                                );
                            }
                        }
                        httpResponseFuture.complete(response);
                        break;
                    }
                    case REQUESTS: {
                        LogEntry logEntry = new LogEntry()
                            .setType(RETRIEVED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("retrieved requests in " + format.name().toLowerCase() + " that match:{}")
                            .setArguments(requestDefinition);
                        switch (format) {
                            case JAVA: {
                                List<RequestDefinition> requests = retrieveRequestsPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getRequestDefinitionSerializer().serialize(requests),
                                    MediaType.create("application", "java").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case JSON: {
                                List<RequestDefinition> requests = retrieveRequestsPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getRequestDefinitionSerializer().serializeRecordedRequests(true, requests),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case LOG_ENTRIES: {
                                List<LogEntry> logEntries = awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRequestLogEntries(requestDefinition, consumer),
                                    logCorrelationId, request
                                );
                                response.withBody(
                                    getLogEntrySerializer().serialize(logEntries),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case OPENAPI: {
                                List<RequestDefinition> requests = retrieveRequestsPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getExpectationExportSerializer().serializeRequestsAsOpenApi(requests),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case POSTMAN: {
                                List<RequestDefinition> requests = retrieveRequestsPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getExpectationExportSerializer().serializeRequestsAsPostman(requests),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case BRUNO: {
                                List<RequestDefinition> requests = retrieveRequestsPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response
                                    .withBody(getExpectationExportSerializer().serializeRequestsAsBruno(requests))
                                    .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                    .withHeader("content-disposition", "attachment; filename=\"mockserver-requests.bruno.zip\"");
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case HAR: {
                                List<RequestDefinition> requests = retrieveRequestsPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                java.util.List<org.mockserver.model.LogEventRequestAndResponse> pairs = new java.util.ArrayList<>(requests.size());
                                for (org.mockserver.model.RequestDefinition r : requests) {
                                    if (r instanceof org.mockserver.model.HttpRequest) {
                                        pairs.add(new org.mockserver.model.LogEventRequestAndResponse()
                                            .withHttpRequest((org.mockserver.model.HttpRequest) r));
                                    }
                                }
                                response.withBody(getHarConverter().serialize(pairs), MediaType.JSON_UTF_8);
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case CURL: {
                                List<RequestDefinition> requests = retrieveRequestsPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                List<HttpRequest> httpRequests = new java.util.ArrayList<>(requests.size());
                                for (RequestDefinition r : requests) {
                                    if (r instanceof HttpRequest) {
                                        httpRequests.add((HttpRequest) r);
                                    }
                                }
                                response.withBody(toCurlCommands(httpRequests), MediaType.PLAIN_TEXT_UTF_8);
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case JAVASCRIPT:
                            case PYTHON:
                            case GO:
                            case CSHARP:
                            case RUBY:
                            case RUST:
                            case PHP:
                                response.withBody(format.name() + " not supported for REQUESTS (use RECORDED_EXPECTATIONS)", MediaType.create("text", "plain").withCharset(UTF_8));
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                        }
                        break;
                    }
                    case REQUEST_RESPONSES: {
                        LogEntry logEntry = new LogEntry()
                            .setType(RETRIEVED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("retrieved requests and responses in " + format.name().toLowerCase() + " that match:{}")
                            .setArguments(requestDefinition);
                        switch (format) {
                            case JAVA:
                            case JAVASCRIPT:
                            case PYTHON:
                            case GO:
                            case CSHARP:
                            case RUBY:
                            case RUST:
                            case PHP:
                                response.withBody(format.name() + " not supported for REQUEST_RESPONSES", MediaType.create("text", "plain").withCharset(UTF_8));
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            case JSON: {
                                // Materialize the (cheap) redacted list on the disruptor consumer thread, then
                                // serialize on THIS caller thread — serializing potentially many large captured
                                // bodies inside the single log-consumer callback raced the retrieve future timeout
                                // and stalled all further logging (#3).
                                List<LogEventRequestAndResponse> pairs = retrieveRequestResponsesPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getHttpRequestResponseSerializer().serialize(pairs),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case LOG_ENTRIES: {
                                List<LogEntry> logEntries = awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRequestResponseMessageLogEntries(requestDefinition, consumer),
                                    logCorrelationId, request
                                );
                                response.withBody(
                                    getLogEntrySerializer().serialize(logEntries),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case HAR: {
                                List<LogEventRequestAndResponse> pairs = retrieveRequestResponsesPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getHarConverter().serialize(pairs),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case OPENAPI: {
                                List<LogEventRequestAndResponse> pairs = retrieveRequestResponsesPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getExpectationExportSerializer().serializeRequestResponsesAsOpenApi(pairs),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case POSTMAN: {
                                List<LogEventRequestAndResponse> pairs = retrieveRequestResponsesPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response.withBody(
                                    getExpectationExportSerializer().serializeRequestResponsesAsPostman(pairs),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case BRUNO: {
                                List<LogEventRequestAndResponse> pairs = retrieveRequestResponsesPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                response
                                    .withBody(getExpectationExportSerializer().serializeRequestResponsesAsBruno(pairs))
                                    .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                    .withHeader("content-disposition", "attachment; filename=\"mockserver-traffic.bruno.zip\"");
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case CURL: {
                                List<LogEventRequestAndResponse> pairs = retrieveRequestResponsesPossiblyFanIn(requestDefinition, logCorrelationId, request, applyFanIn);
                                List<HttpRequest> httpRequests = new java.util.ArrayList<>(pairs.size());
                                for (LogEventRequestAndResponse pair : pairs) {
                                    if (pair.getHttpRequest() instanceof HttpRequest) {
                                        httpRequests.add((HttpRequest) pair.getHttpRequest());
                                    }
                                }
                                response.withBody(toCurlCommands(httpRequests), MediaType.PLAIN_TEXT_UTF_8);
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                        }
                        break;
                    }
                    case RECORDED_EXPECTATIONS: {
                        LogEntry logEntry = new LogEntry()
                            .setType(RETRIEVED)
                            .setLogLevel(Level.INFO)
                            .setCorrelationId(logCorrelationId)
                            .setHttpRequest(requestDefinition)
                            .setMessageFormat("retrieved recorded expectations in " + format.name().toLowerCase() + " that match:{}")
                            .setArguments(requestDefinition);
                        switch (format) {
                            case JAVA: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToJavaSerializer().serialize(requests),
                                    MediaType.create("application", "java").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case JAVASCRIPT: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToJavaScriptSerializer().serialize(requests),
                                    MediaType.create("application", "javascript").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case PYTHON: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToPythonSerializer().serialize(requests),
                                    MediaType.create("text", "x-python").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case GO: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToGoSerializer().serialize(requests),
                                    MediaType.create("text", "x-go").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case CSHARP: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToCSharpSerializer().serialize(requests),
                                    MediaType.create("text", "x-csharp").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case RUBY: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToRubySerializer().serialize(requests),
                                    MediaType.create("text", "x-ruby").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case RUST: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToRustSerializer().serialize(requests),
                                    MediaType.create("text", "x-rust").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case PHP: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationToPhpSerializer().serialize(requests),
                                    MediaType.create("application", "x-httpd-php").withCharset(UTF_8)
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case JSON: {
                                List<Expectation> requests = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationSerializerThatSerializesBodyDefault().serialize(requests),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case LOG_ENTRIES: {
                                List<LogEntry> logEntries = awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectationLogEntries(requestDefinition, consumer),
                                    logCorrelationId, request
                                );
                                response.withBody(
                                    getLogEntrySerializer().serialize(logEntries),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case OPENAPI: {
                                List<Expectation> expectations = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationExportSerializer().serializeAsOpenApi(expectations),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case POSTMAN: {
                                List<Expectation> expectations = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getExpectationExportSerializer().serializeAsPostmanCollection(expectations),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case BRUNO: {
                                List<Expectation> expectations = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response
                                    .withBody(getExpectationExportSerializer().serializeAsBrunoCollection(expectations))
                                    .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                    .withHeader("content-disposition", "attachment; filename=\"mockserver-recorded.bruno.zip\"");
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case HAR: {
                                List<Expectation> expectations = postProcessRecordedExpectations(awaitRetrieve(
                                    consumer -> mockServerLog.retrieveRecordedExpectations(requestDefinition, consumer),
                                    logCorrelationId, request
                                ), request);
                                response.withBody(
                                    getHarConverter().serialize(expectationsToLogEvents(expectations)),
                                    MediaType.JSON_UTF_8
                                );
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                            }
                            case CURL:
                                response.withBody("CURL not supported for RECORDED_EXPECTATIONS", MediaType.create("text", "plain").withCharset(UTF_8));
                                mockServerLogger.logEvent(logEntry);
                                httpResponseFuture.complete(response);
                                break;
                        }
                        break;
                    }
                    case ACTIVE_EXPECTATIONS: {
                        List<Expectation> expectations = requestMatchers.retrieveActiveExpectations(requestDefinition);
                        if (isNotBlank(namespaceFilter)) {
                            // Tenant view: keep this namespace's expectations plus global
                            // (no-namespace) expectations; hide other tenants' expectations.
                            expectations = expectations.stream()
                                .filter(expectation -> isBlank(expectation.getNamespace()) || namespaceFilter.equals(expectation.getNamespace()))
                                .collect(Collectors.toList());
                        }
                        switch (format) {
                            case JAVA:
                                response.withBody(getExpectationToJavaSerializer().serialize(expectations), MediaType.create("application", "java").withCharset(UTF_8));
                                break;
                            case JAVASCRIPT:
                                response.withBody(getExpectationToJavaScriptSerializer().serialize(expectations), MediaType.create("application", "javascript").withCharset(UTF_8));
                                break;
                            case PYTHON:
                                response.withBody(getExpectationToPythonSerializer().serialize(expectations), MediaType.create("text", "x-python").withCharset(UTF_8));
                                break;
                            case GO:
                                response.withBody(getExpectationToGoSerializer().serialize(expectations), MediaType.create("text", "x-go").withCharset(UTF_8));
                                break;
                            case CSHARP:
                                response.withBody(getExpectationToCSharpSerializer().serialize(expectations), MediaType.create("text", "x-csharp").withCharset(UTF_8));
                                break;
                            case RUBY:
                                response.withBody(getExpectationToRubySerializer().serialize(expectations), MediaType.create("text", "x-ruby").withCharset(UTF_8));
                                break;
                            case RUST:
                                response.withBody(getExpectationToRustSerializer().serialize(expectations), MediaType.create("text", "x-rust").withCharset(UTF_8));
                                break;
                            case PHP:
                                response.withBody(getExpectationToPhpSerializer().serialize(expectations), MediaType.create("application", "x-httpd-php").withCharset(UTF_8));
                                break;
                            case JSON:
                                response.withBody(getExpectationSerializer().serialize(expectations), MediaType.JSON_UTF_8);
                                break;
                            case LOG_ENTRIES:
                                response.withBody("LOG_ENTRIES not supported for ACTIVE_EXPECTATIONS", MediaType.create("text", "plain").withCharset(UTF_8));
                                break;
                            case OPENAPI:
                                response.withBody(
                                    getExpectationExportSerializer().serializeAsOpenApi(expectations),
                                    MediaType.JSON_UTF_8
                                );
                                break;
                            case POSTMAN:
                                response.withBody(
                                    getExpectationExportSerializer().serializeAsPostmanCollection(expectations),
                                    MediaType.JSON_UTF_8
                                );
                                break;
                            case BRUNO:
                                response
                                    .withBody(getExpectationExportSerializer().serializeAsBrunoCollection(expectations))
                                    .withHeader(io.netty.handler.codec.http.HttpHeaderNames.CONTENT_TYPE.toString(), "application/zip")
                                    .withHeader("content-disposition", "attachment; filename=\"mockserver-expectations.bruno.zip\"");
                                break;
                            case HAR:
                                response.withBody(
                                    getHarConverter().serialize(expectationsToLogEvents(expectations)),
                                    MediaType.JSON_UTF_8
                                );
                                break;
                            case CURL:
                                response.withBody("CURL not supported for ACTIVE_EXPECTATIONS", MediaType.create("text", "plain").withCharset(UTF_8));
                                break;
                        }
                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(RETRIEVED)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(logCorrelationId)
                                    .setHttpRequest(requestDefinition)
                                    .setMessageFormat("retrieved " + expectations.size() + " active expectations in " + format.name().toLowerCase() + " that match:{}")
                                    .setArguments(requestDefinition)
                            );
                        }
                        httpResponseFuture.complete(response);
                        break;
                    }
                    case METRICS: {
                        if (!configuration.metricsEnabled()) {
                            response.withBody("{}", MediaType.JSON_UTF_8);
                        } else {
                            StringBuilder metricsJson = new StringBuilder("{");
                            Metrics.Name[] names = Metrics.Name.values();
                            for (int i = 0; i < names.length; i++) {
                                metricsJson.append("\"").append(names[i].name()).append("\":").append(Metrics.get(names[i]));
                                if (i < names.length - 1) {
                                    metricsJson.append(",");
                                }
                            }
                            metricsJson.append("}");
                            response.withBody(metricsJson.toString(), MediaType.JSON_UTF_8);
                        }
                        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setType(RETRIEVED)
                                    .setLogLevel(Level.INFO)
                                    .setCorrelationId(logCorrelationId)
                                    .setHttpRequest(requestDefinition)
                                    .setMessageFormat("retrieved metrics")
                            );
                        }
                        httpResponseFuture.complete(response);
                        break;
                    }
                }

                try {
                    return httpResponseFuture.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
                } catch (ExecutionException | InterruptedException | TimeoutException ex) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setCorrelationId(logCorrelationId)
                            .setMessageFormat("exception handling request:{}error:{}")
                            .setArguments(request, ex.getMessage())
                            .setThrowable(ex)
                    );
                    throw new RuntimeException("Exception retrieving state for " + request, ex);
                }
            } catch (ClusterFanInException cfe) {
                // fail-closed: a retrieve that cannot reach all cluster peers returns an error
                // (502) rather than a partial result that would silently under-report traffic.
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("cluster retrieve fan-in failed:{}")
                        .setArguments(cfe.getMessage())
                );
                return response()
                    .withStatusCode(BAD_GATEWAY.code())
                    .withBody("{\"error\":\"" + cfe.getMessage().replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
            } catch (IllegalArgumentException iae) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setCorrelationId(logCorrelationId)
                        .setMessageFormat("exception handling request:{}error:{}")
                        .setArguments(request, iae.getMessage())
                        .setThrowable(iae)
                );
                if (iae.getMessage().contains(RetrieveType.class.getSimpleName())) {
                    throw new IllegalArgumentException("\"" + request.getFirstQueryStringParameter("type") + "\" is not a valid value for \"type\" parameter, only the following values are supported " + Arrays.stream(RetrieveType.values()).map(input -> input.name().toLowerCase()).collect(Collectors.toList()));
                }
                if (iae.getMessage().contains(Format.class.getSimpleName())) {
                    throw new IllegalArgumentException("\"" + request.getFirstQueryStringParameter("format") + "\" is not a valid value for \"format\" parameter, only the following values are supported " + Arrays.stream(Format.values()).map(input -> input.name().toLowerCase()).collect(Collectors.toList()));
                }
                throw iae;
            }
        } else {
            return response().withStatusCode(200);
        }
    }

    /**
     * Materialize a retrieve result list off the single disruptor log-consumer thread and return it to the
     * CALLER, so any heavy serialization (JSON/HAR/OpenAPI/etc. over large captured response bodies) runs on
     * the caller thread rather than inside the log-consumer callback. Previously the whole list was serialized
     * inside the consumer callback (on the one log-processing thread): with many large captured streaming
     * bodies that both raced the retrieve future timeout ({@code maxFutureTimeoutInMillis}) AND stalled all
     * further logging (filling the ring buffer and dropping events) — see bug #3. The list the consumer
     * receives is already fully materialized and redacted (cheap to produce), so only its construction runs on
     * the consumer thread; the expensive serialize(...) now runs here. The consumer runs after the disruptor
     * has drained prior log writes, so the list is a consistent snapshot.
     */
    private <T> T awaitRetrieve(Consumer<Consumer<T>> retriever, String logCorrelationId, HttpRequest request) {
        CompletableFuture<T> listFuture = new CompletableFuture<>();
        retriever.accept(listFuture::complete);
        try {
            return listFuture.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
        } catch (ExecutionException | InterruptedException | TimeoutException ex) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("exception handling request:{}error:{}")
                    .setArguments(request, ex.getMessage())
                    .setThrowable(ex)
            );
            throw new RuntimeException("Exception retrieving state for " + request, ex);
        }
    }

    /**
     * T1.9 test seam: inject a {@link ClusterFanIn} coordinator (with a mocked
     * {@link ClusterFanIn.PeerAccessor}) so fan-in merge logic can be unit-tested
     * without a real multi-node cluster.
     */
    public void setClusterFanIn(ClusterFanIn clusterFanIn) {
        this.clusterFanIn = clusterFanIn;
    }

    /**
     * Retrieve this node's local matching requests and, when fan-in applies, concatenate every
     * peer's LOCAL matching requests. Fail-closed: an unreachable peer throws
     * {@link ClusterFanInException} (surfaced as a 502 by {@link #retrieve(HttpRequest)}).
     */
    private List<RequestDefinition> retrieveRequestsPossiblyFanIn(RequestDefinition requestDefinition, String logCorrelationId, HttpRequest request, boolean applyFanIn) {
        List<RequestDefinition> local = awaitRetrieve(
            consumer -> mockServerLog.retrieveRequests(requestDefinition, consumer),
            logCorrelationId, request
        );
        if (!applyFanIn) {
            return local;
        }
        ClusterFanIn.FanInResult<List<RequestDefinition>> remote = clusterFanIn.fanInRequests(requestDefinition);
        if (remote.hasUnreachablePeers()) {
            throw new ClusterFanInException(remote.unreachablePeers());
        }
        List<RequestDefinition> merged = new ArrayList<>(local);
        merged.addAll(remote.merged());
        return merged;
    }

    /**
     * Retrieve this node's local matching request-response pairs and, when fan-in applies,
     * concatenate every peer's LOCAL pairs. Fail-closed like {@link #retrieveRequestsPossiblyFanIn}.
     */
    private List<LogEventRequestAndResponse> retrieveRequestResponsesPossiblyFanIn(RequestDefinition requestDefinition, String logCorrelationId, HttpRequest request, boolean applyFanIn) {
        List<LogEventRequestAndResponse> local = awaitRetrieve(
            consumer -> mockServerLog.retrieveRequestResponses(requestDefinition, consumer),
            logCorrelationId, request
        );
        if (!applyFanIn) {
            return local;
        }
        ClusterFanIn.FanInResult<List<LogEventRequestAndResponse>> remote = clusterFanIn.fanInRequestResponses(requestDefinition);
        if (remote.hasUnreachablePeers()) {
            throw new ClusterFanInException(remote.unreachablePeers());
        }
        List<LogEventRequestAndResponse> merged = new ArrayList<>(local);
        merged.addAll(remote.merged());
        return merged;
    }

    public Future<String> verify(Verification verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(Verification verification, Consumer<String> resultConsumer) {
        if (verification.getExpectationId() != null) {
            // check valid expectation id and populate for error message
            verification.withRequest(resolveExpectationId(verification.getExpectationId()));
        }
        // T1.9 count-based verify fan-in. When enabled, aggregate each peer's LOCAL match
        // count with this node's before evaluating VerificationTimes, so a verify behind a
        // load balancer reflects fleet-wide traffic rather than only the node it hit.
        // Scoped to request-only verification (no httpResponse, no expectationId): response-aware
        // verify and expectationId-based verify aggregation are documented deferred boundaries and
        // stay node-local. Fail-closed: an unreachable peer yields a verification failure rather
        // than a partial (potentially wrong) result.
        if (clusterFanIn != null && clusterFanIn.enabled()
            && verification.getHttpResponse() == null
            && verification.getExpectationId() == null) {
            ClusterFanIn.FanInResult<List<RequestDefinition>> remote = clusterFanIn.fanInRequests(verification.getHttpRequest());
            if (remote.hasUnreachablePeers()) {
                resultConsumer.accept("Verification could not be evaluated cluster-wide: unreachable peer(s) " + remote.unreachablePeers());
                return;
            }
            mockServerLog.verify(verification, remote.merged().size(), resultConsumer);
            return;
        }
        mockServerLog.verify(verification, resultConsumer);
    }

    public Future<String> verify(VerificationSequence verification) {
        CompletableFuture<String> result = new CompletableFuture<>();
        verify(verification, result::complete);
        return result;
    }

    public void verify(VerificationSequence verificationSequence, Consumer<String> resultConsumer) {
        if (verificationSequence.getExpectationIds() != null && !verificationSequence.getExpectationIds().isEmpty()) {
            verificationSequence.withRequests(resolveExpectationIds(verificationSequence.getExpectationIds()));
        }
        mockServerLog.verify(verificationSequence, resultConsumer);
    }

    public boolean handle(HttpRequest request, ResponseWriter responseWriter, boolean warDeployment) {

        request.withLogCorrelationId(UUIDService.getUUID());
        if (request.getReceivedTimestamp() == null) {
            request.withReceivedTimestamp(org.mockserver.time.EpochService.currentTimeMillis());
        }
        setPort(request);

        if (mockServerLogger.isEnabledForInstance(Level.TRACE)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.TRACE)
                    .setHttpRequest(request)
                    .setMessageFormat(RECEIVED_REQUEST_MESSAGE_FORMAT)
                    .setArguments(request)
            );
        }

        if (request.matches("PUT")) {

            CompletableFuture<Boolean> canHandle = new CompletableFuture<>();

            if (request.matches("PUT", PATH_PREFIX + "/expectation", "/expectation")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    List<Expectation> upsertedExpectations = new ArrayList<>();
                    for (Expectation expectation : getExpectationSerializer().deserializeArray(request.getBodyAsJsonOrXmlString(), false)) {
                        if (!warDeployment || validateSupportedFeatures(expectation, request, responseWriter)) {
                            upsertedExpectations.addAll(add(expectation));
                        }
                    }

                    responseWriter.writeResponse(request, response()
                        .withStatusCode(CREATED.code())
                        .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/openapi", "/openapi")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        List<Expectation> upsertedExpectations = new ArrayList<>();
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        String contentType = request.getFirstHeader(CONTENT_TYPE.toString());
                        if (contentType != null) {
                            String baseType = contentType.split(";")[0].trim().toLowerCase();
                            if ("application/yaml".equals(baseType) || "application/x-yaml".equals(baseType) || "text/yaml".equals(baseType)) {
                                requestBody = YamlToJsonConverter.convertYamlToJson(requestBody);
                            }
                        }
                        for (OpenAPIExpectation openAPIExpectation : getOpenAPIExpectationSerializer().deserializeArray(requestBody, false)) {
                            upsertedExpectations.addAll(add(openAPIExpectation));
                        }
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for open api expectation:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            (!iae.getMessage().startsWith(OPEN_API_LOAD_ERROR) ? OPEN_API_LOAD_ERROR + (isNotBlank(iae.getMessage()) ? ", " : "") : "") + iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/wsdl", "/wsdl")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        List<Expectation> upsertedExpectations = add(
                            new org.mockserver.mock.wsdl.WsdlExpectationGenerator()
                                .generate(request.getBodyAsJsonOrXmlString())
                                .toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for wsdl expectation:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/graphql", "/graphql")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String path = request.getFirstQueryStringParameter("path");
                        // SDL / introspection documents are raw text (not JSON/XML), so read the
                        // body verbatim to preserve the exact schema the user submitted.
                        List<Expectation> upsertedExpectations = add(
                            new org.mockserver.graphql.GraphQLExpectationGenerator()
                                .generate(request.getBodyAsString(), path)
                                .toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for graphql expectation:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/oidc", "/oidc")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        org.mockserver.oidc.OidcProviderConfiguration oidcConfig;
                        if (requestBody == null || requestBody.trim().isEmpty()) {
                            oidcConfig = new org.mockserver.oidc.OidcProviderConfiguration();
                        } else {
                            oidcConfig = ObjectMapperFactory.createObjectMapper()
                                .readValue(requestBody, org.mockserver.oidc.OidcProviderConfiguration.class);
                        }
                        List<Expectation> upsertedExpectations = add(
                            new org.mockserver.oidc.OidcProviderGenerator()
                                .generate(oidcConfig)
                                .toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for oidc provider:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for oidc provider:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/saml", "/saml")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        org.mockserver.saml.SamlProviderConfiguration samlConfig;
                        if (requestBody == null || requestBody.trim().isEmpty()) {
                            samlConfig = new org.mockserver.saml.SamlProviderConfiguration();
                        } else {
                            samlConfig = ObjectMapperFactory.createObjectMapper()
                                .readValue(requestBody, org.mockserver.saml.SamlProviderConfiguration.class);
                        }
                        List<Expectation> upsertedExpectations = add(
                            new org.mockserver.saml.SamlProviderGenerator()
                                .generate(samlConfig)
                                .toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for saml provider:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for saml provider:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/scim", "/scim")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        org.mockserver.scim.ScimProviderConfiguration scimConfig;
                        if (requestBody == null || requestBody.trim().isEmpty()) {
                            scimConfig = new org.mockserver.scim.ScimProviderConfiguration();
                        } else {
                            scimConfig = ObjectMapperFactory.createObjectMapper()
                                .readValue(requestBody, org.mockserver.scim.ScimProviderConfiguration.class);
                        }
                        List<Expectation> upsertedExpectations = add(
                            new org.mockserver.scim.ScimProviderGenerator()
                                .generate(scimConfig)
                                .toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for scim provider:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for scim provider:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/import", "/import")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String formatParam = request.getFirstQueryStringParameter("format");
                        org.mockserver.imports.ImportRedaction.Options redactionOptions = buildImportRedactionOptions(request);

                        // Recorded-traffic re-import: reload a persisted NDJSON archive of recorded
                        // request/response pairs back into the event log so they are retrievable like
                        // in-memory recordings. Unlike the expectation importers below this reads NDJSON
                        // (one HttpRequestAndHttpResponse per line) and can source it from disk via
                        // ?source=disk (or when the body is empty), reading the configured
                        // persistedRecordedRequestsPath.
                        if ("recording".equalsIgnoreCase(formatParam)) {
                            String sourceParam = request.getFirstQueryStringParameter("source");
                            String ndjson = request.getBodyAsJsonOrXmlString();
                            boolean fromDisk = "disk".equalsIgnoreCase(sourceParam) || ndjson == null || ndjson.trim().isEmpty();
                            if (fromDisk) {
                                java.nio.file.Path archivePath = java.nio.file.Paths.get(configuration.persistedRecordedRequestsPath());
                                if (!java.nio.file.Files.exists(archivePath)) {
                                    throw new IllegalArgumentException("no persisted recorded requests archive found at " + archivePath.toAbsolutePath() + " (set mockserver.persistedRecordedRequestsPath or supply the archive in the request body)");
                                }
                                ndjson = new String(java.nio.file.Files.readAllBytes(archivePath), java.nio.charset.StandardCharsets.UTF_8);
                            }
                            org.mockserver.imports.RecordedTrafficImporter.Result recordingResult =
                                new org.mockserver.imports.RecordedTrafficImporter(mockServerLogger).importRecordedTraffic(ndjson, redactionOptions);
                            List<org.mockserver.model.HttpRequestAndHttpResponse> pairs = recordingResult.getPairs();
                            for (org.mockserver.model.HttpRequestAndHttpResponse pair : pairs) {
                                mockServerLog.importRecordedRequestResponse(pair.getHttpRequest(), pair.getHttpResponse());
                            }
                            HttpResponse recordingResponse = response()
                                .withStatusCode(CREATED.code())
                                .withBody(new org.mockserver.serialization.HttpRequestAndHttpResponseSerializer(mockServerLogger).serialize(pairs), MediaType.JSON_UTF_8);
                            // surface how many crash-truncated / malformed lines were skipped so a
                            // recovery import is not silently lossy (the intact exchanges still import)
                            if (recordingResult.getSkippedLineCount() > 0) {
                                recordingResponse.withHeader("x-mockserver-recorded-requests-skipped", String.valueOf(recordingResult.getSkippedLineCount()));
                            }
                            responseWriter.writeResponse(request, recordingResponse, true);
                        } else {
                            String requestBody = request.getBodyAsJsonOrXmlString();
                            if (requestBody == null || requestBody.trim().isEmpty()) {
                                throw new IllegalArgumentException("import request body is required — must be a HAR, Postman collection, Pact contract, WireMock stub, Mountebank imposter or Mockoon environment JSON document");
                            }
                            List<Expectation> importedExpectations;
                            // Migration importers (WireMock / Mountebank / Mockoon) return an ImportResult
                            // carrying structured warnings for every foreign construct that could not be
                            // faithfully mapped; when non-null the response body includes those warnings.
                            List<org.mockserver.imports.ImportWarning> importWarnings = null;
                            if ("har".equalsIgnoreCase(formatParam)) {
                                importedExpectations = new org.mockserver.imports.HarImporter().importExpectations(requestBody, redactionOptions);
                            } else if ("postman".equalsIgnoreCase(formatParam)) {
                                importedExpectations = new org.mockserver.imports.PostmanCollectionImporter().importExpectations(requestBody, redactionOptions);
                            } else if ("pact".equalsIgnoreCase(formatParam)) {
                                importedExpectations = new org.mockserver.mock.pact.PactImporter().importExpectations(requestBody, redactionOptions);
                            } else if ("wiremock".equalsIgnoreCase(formatParam)) {
                                org.mockserver.imports.ImportResult result = new org.mockserver.imports.WireMockImporter().importExpectations(requestBody, redactionOptions);
                                importedExpectations = result.getExpectations();
                                importWarnings = result.getWarnings();
                            } else if ("mountebank".equalsIgnoreCase(formatParam)) {
                                org.mockserver.imports.ImportResult result = new org.mockserver.imports.MountebankImporter().importExpectations(requestBody, redactionOptions);
                                importedExpectations = result.getExpectations();
                                importWarnings = result.getWarnings();
                            } else if ("mockoon".equalsIgnoreCase(formatParam)) {
                                org.mockserver.imports.ImportResult result = new org.mockserver.imports.MockoonImporter().importExpectations(requestBody, redactionOptions);
                                importedExpectations = result.getExpectations();
                                importWarnings = result.getWarnings();
                            } else if (formatParam != null && !formatParam.isEmpty()) {
                                throw new IllegalArgumentException("unsupported import format: " + formatParam + " (supported formats: har, postman, pact, wiremock, mountebank, mockoon, recording)");
                            } else {
                                // Auto-detect format from JSON structure
                                com.fasterxml.jackson.databind.JsonNode rootNode = ObjectMapperFactory.createObjectMapper().readTree(requestBody);
                                if (!rootNode.path("log").path("entries").isMissingNode()) {
                                    importedExpectations = new org.mockserver.imports.HarImporter().importExpectations(requestBody, redactionOptions);
                                } else if (!rootNode.path("info").isMissingNode() && !rootNode.path("item").isMissingNode()) {
                                    importedExpectations = new org.mockserver.imports.PostmanCollectionImporter().importExpectations(requestBody, redactionOptions);
                                } else if (!rootNode.path("interactions").isMissingNode() && rootNode.path("interactions").isArray()) {
                                    importedExpectations = new org.mockserver.mock.pact.PactImporter().importExpectations(requestBody, redactionOptions);
                                } else if (rootNode.path("mappings").isArray()
                                    || rootNode.path("request").path("urlPath").isTextual()
                                    || rootNode.path("request").path("urlPathPattern").isTextual()
                                    || rootNode.path("request").path("urlPattern").isTextual()
                                    || rootNode.path("response").path("jsonBody").isObject()
                                    || rootNode.path("response").path("fault").isTextual()) {
                                    org.mockserver.imports.ImportResult result = new org.mockserver.imports.WireMockImporter().importExpectations(requestBody, redactionOptions);
                                    importedExpectations = result.getExpectations();
                                    importWarnings = result.getWarnings();
                                } else if (rootNode.path("imposters").isArray()
                                    || (rootNode.path("protocol").isTextual() && rootNode.path("stubs").isArray())) {
                                    org.mockserver.imports.ImportResult result = new org.mockserver.imports.MountebankImporter().importExpectations(requestBody, redactionOptions);
                                    importedExpectations = result.getExpectations();
                                    importWarnings = result.getWarnings();
                                } else if (rootNode.path("routes").isArray()) {
                                    org.mockserver.imports.ImportResult result = new org.mockserver.imports.MockoonImporter().importExpectations(requestBody, redactionOptions);
                                    importedExpectations = result.getExpectations();
                                    importWarnings = result.getWarnings();
                                } else {
                                    throw new IllegalArgumentException("unable to auto-detect import format — use ?format=har, ?format=postman, ?format=pact, ?format=wiremock, ?format=mountebank, ?format=mockoon or ?format=recording query parameter");
                                }
                            }
                            // Optional consolidation of imported exchanges (e.g. a HAR that
                            // captured the same endpoint many times) into reusable mocks —
                            // ?consolidate=true collapses by request shape into unlimited-times
                            // expectations (sequencing differing responses); ?parameterize=true
                            // additionally generalises volatile path/query/header/body values.
                            if ("true".equalsIgnoreCase(request.getFirstQueryStringParameter("consolidate"))
                                || "true".equalsIgnoreCase(request.getFirstQueryStringParameter("parameterize"))) {
                                boolean parameterizeImport = "true".equalsIgnoreCase(request.getFirstQueryStringParameter("parameterize"));
                                importedExpectations = RecordedExpectationPostProcessor.consolidate(importedExpectations, parameterizeImport);
                            }
                            List<Expectation> upsertedExpectations = add(
                                importedExpectations.toArray(new Expectation[0])
                            );
                            if (importWarnings != null) {
                                // Migration importer: return { "expectations": [...], "warnings": [...] }
                                // so no unmapped foreign construct is ever silently dropped.
                                com.fasterxml.jackson.databind.ObjectMapper importMapper = ObjectMapperFactory.createObjectMapper();
                                com.fasterxml.jackson.databind.node.ObjectNode importBody = importMapper.createObjectNode();
                                importBody.set("expectations", importMapper.readTree(getExpectationSerializer().serialize(upsertedExpectations)));
                                importBody.set("warnings", importMapper.valueToTree(importWarnings));
                                responseWriter.writeResponse(request, response()
                                    .withStatusCode(CREATED.code())
                                    .withBody(importMapper.writerWithDefaultPrettyPrinter().writeValueAsString(importBody), MediaType.JSON_UTF_8), true);
                            } else {
                                responseWriter.writeResponse(request, response()
                                    .withStatusCode(CREATED.code())
                                    .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                            }
                        }
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for import:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            iae.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for import:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/recordings/promote", "/recordings/promote")) {

                // Server-side "promote recordings to active mocks": the REST equivalent of the
                // MCP create_expectations_from_recorded_traffic tool. Retrieves recorded
                // (FORWARDED_REQUEST) exchanges matching an optional request-matcher filter,
                // consolidates them into reusable mocks (unlimited times, path/value
                // parameterization, differing responses sequenced), redacts secrets, and
                // ACTIVATES them (adds to the active expectation set).
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        RequestDefinition filter = request();
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        if (isNotBlank(requestBody)) {
                            filter = getRequestDefinitionSerializer().deserialize(requestBody);
                        }

                        // Redact BEFORE consolidation (on by default; ?redactSensitiveData=false to
                        // disable) so promoted mocks never carry captured credentials. Consolidate by
                        // default (?consolidate=false promotes verbatim); ?parameterize defaults on and
                        // generalises volatile path/query/header/body values so a single recorded id
                        // does not pin the mock. Delegates to the shared promoteRecordings(...) so the
                        // REST endpoint and the promote_recordings MCP tool share one code path.
                        org.mockserver.imports.ImportRedaction.Options redactionOptions = buildImportRedactionOptions(request);
                        boolean consolidate = !"false".equalsIgnoreCase(request.getFirstQueryStringParameter("consolidate"));
                        boolean parameterize = !"false".equalsIgnoreCase(request.getFirstQueryStringParameter("parameterize"));

                        List<Expectation> activated = promoteRecordings(filter, consolidate, parameterize, redactionOptions);
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(activated), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request to promote recordings:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(request, BAD_REQUEST, iae.getMessage(), MediaType.create("text", "plain").toString());
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request to promote recordings:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(request, BAD_REQUEST, e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/baseline/compare", "/baseline/compare")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        if (requestBody == null || requestBody.trim().isEmpty()) {
                            throw new IllegalArgumentException("baseline compare request body is required — must be a JSON document with a \"baseline\" (and optional \"current\") array of expectations");
                        }
                        com.fasterxml.jackson.databind.JsonNode rootNode = ObjectMapperFactory.createObjectMapper().readTree(requestBody);
                        com.fasterxml.jackson.databind.JsonNode baselineNode = rootNode.get("baseline");
                        if (baselineNode == null || baselineNode.isNull()) {
                            throw new IllegalArgumentException("baseline compare request body must contain a \"baseline\" array of expectations");
                        }
                        List<Expectation> baselineExpectations = java.util.Arrays.asList(
                            getExpectationSerializer().deserializeArray(baselineNode.toString(), true));

                        List<Expectation> currentExpectations;
                        com.fasterxml.jackson.databind.JsonNode currentNode = rootNode.get("current");
                        if (currentNode == null || currentNode.isNull()) {
                            // no current supplied — diff against the live recorded expectations
                            currentExpectations = requestMatchers.retrieveActiveExpectations(null);
                        } else {
                            currentExpectations = java.util.Arrays.asList(
                                getExpectationSerializer().deserializeArray(currentNode.toString(), true));
                        }

                        org.mockserver.mock.diff.BaselineDiffReport report =
                            new org.mockserver.mock.diff.BaselineDiffer().diffExpectations(baselineExpectations, currentExpectations);

                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody(ObjectMapperFactory.createObjectMapper().writeValueAsString(report), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for baseline compare:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(request, BAD_REQUEST, iae.getMessage(), MediaType.create("text", "plain").toString());
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for baseline compare:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(request, BAD_REQUEST, e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/contractTest", "/contractTest")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    handleContractTest(request, responseWriter, canHandle);
                } else {
                    canHandle.complete(true);
                }

            } else if (request.matches("PUT", PATH_PREFIX + "/trafficValidate", "/trafficValidate")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    handleTrafficValidate(request, responseWriter, canHandle);
                } else {
                    canHandle.complete(true);
                }

            } else if (request.matches("PUT", PATH_PREFIX + "/pact/import", "/pact/import")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String requestBody = request.getBodyAsJsonOrXmlString();
                        if (requestBody == null || requestBody.trim().isEmpty()) {
                            throw new IllegalArgumentException("Pact import request body is required — must be a Pact v3 contract JSON document");
                        }
                        org.mockserver.imports.ImportRedaction.Options redactionOptions = buildImportRedactionOptions(request);
                        List<Expectation> importedExpectations = new org.mockserver.mock.pact.PactImporter()
                            .importExpectations(requestBody, redactionOptions);
                        List<Expectation> upsertedExpectations = add(
                            importedExpectations.toArray(new Expectation[0])
                        );
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(CREATED.code())
                            .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for pact import:{}error:{}")
                                .setArguments(request, iae.getMessage())
                                .setThrowable(iae)
                        );
                        responseWriter.writeResponse(request, BAD_REQUEST, iae.getMessage(), MediaType.create("text", "plain").toString());
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for pact import:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(request, BAD_REQUEST, e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/pact/verify", "/pact/verify")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, handlePactVerify(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/pact", "/pact")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String consumer = request.getFirstQueryStringParameter("consumer");
                        String provider = request.getFirstQueryStringParameter("provider");
                        String pact = new org.mockserver.mock.pact.PactExporter()
                            .export(requestMatchers.retrieveActiveExpectations(null), consumer, provider);
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody(pact, MediaType.JSON_UTF_8), true);
                    } catch (Exception e) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.ERROR)
                                .setMessageFormat("exception handling request for pact export:{}error:{}")
                                .setArguments(request, e.getMessage())
                                .setThrowable(e)
                        );
                        responseWriter.writeResponse(
                            request,
                            BAD_REQUEST,
                            e.getMessage(),
                            MediaType.create("text", "plain").toString()
                        );
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/mode", "/mode")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        MockMode mode = setMode(MockMode.parse(request.getFirstQueryStringParameter("mode")));
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody("{\"mode\":\"" + mode + "\",\"proxyUnmatchedRequests\":" + mode.proxyUnmatchedRequests() + "}", MediaType.JSON_UTF_8), true);
                    } catch (IllegalArgumentException iae) {
                        responseWriter.writeResponse(request, BAD_REQUEST, iae.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/clear", "/clear")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    clear(request);
                    responseWriter.writeResponse(request, OK);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/reset", "/reset")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    reset();
                    responseWriter.writeResponse(request, OK);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/clock", "/clock")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, handleClockPut(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/cassettes", "/cassettes")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleCassettesPut(request)), true);
                }
                canHandle.complete(true);

            } else if (chaosProfileName(request, "PUT", "/chaosExperiment/profiles/") != null) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosProfileSave(request, chaosProfileName(request, "PUT", "/chaosExperiment/profiles/"))), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/chaosExperiment", "/chaosExperiment")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosExperimentPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/loadScenario/start", "/loadScenario/start")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioStart(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/loadScenario/stop", "/loadScenario/stop")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioStop(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/loadScenario/generateFromOpenAPI", "/loadScenario/generateFromOpenAPI")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioGenerateFromOpenAPI(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/loadScenario/generateFromRecording", "/loadScenario/generateFromRecording")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioGenerateFromRecording(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/loadScenario", "/loadScenario")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/serviceChaos", "/serviceChaos")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleServiceChaosPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/tcpChaos", "/tcpChaos")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleTcpChaosPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/preemption", "/preemption")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handlePreemptionPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpcChaos", "/grpcChaos")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcChaosPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/asyncapi/verify", "/asyncapi/verify")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAsyncApiVerify(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/asyncapi/http", "/asyncapi/http")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAsyncApiHttpImport(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/asyncapi", "/asyncapi")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAsyncApiPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/breakpoint/matcher/remove", "/breakpoint/matcher/remove")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleBreakpointMatcherRemove(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/breakpoint/matcher/clear", "/breakpoint/matcher/clear")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleBreakpointMatcherClear()), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/breakpoint/matchers", "/breakpoint/matchers")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleBreakpointMatcherList()), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/breakpoint/matcher", "/breakpoint/matcher")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleBreakpointMatcherRegister(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/debugMismatch", "/debugMismatch")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, debugMismatch(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/explainUnmatched", "/explainUnmatched")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, explainUnmatched(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/retrieve", "/retrieve")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, retrieve(request), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/verify", "/verify")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    verify(getVerificationSerializer().deserialize(request.getBodyAsJsonOrXmlString()), result -> {
                        if (isEmpty(result)) {
                            responseWriter.writeResponse(request, ACCEPTED);
                        } else {
                            responseWriter.writeResponse(request, NOT_ACCEPTABLE, result, MediaType.create("text", "plain").toString());
                        }
                        canHandle.complete(true);
                    });
                } else {
                    canHandle.complete(true);
                }

            } else if (request.matches("PUT", PATH_PREFIX + "/verifySequence", "/verifySequence")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    verify(getVerificationSequenceSerializer().deserialize(request.getBodyAsJsonOrXmlString()), result -> {
                        if (isEmpty(result)) {
                            responseWriter.writeResponse(request, ACCEPTED);
                        } else {
                            responseWriter.writeResponse(request, NOT_ACCEPTABLE, result, MediaType.create("text", "plain").toString());
                        }
                        canHandle.complete(true);
                    });
                } else {
                    canHandle.complete(true);
                }

            } else if (request.matches("PUT", PATH_PREFIX + "/verifySLO", "/verifySLO")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleVerifySlo(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/crud", "/crud")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        CrudExpectationsDefinition definition = objectMapper.readValue(request.getBodyAsJsonOrXmlString(), CrudExpectationsDefinition.class);
                        if (definition.getBasePath() == null || definition.getBasePath().isEmpty()) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "basePath is required", MediaType.create("text", "plain").toString());
                        } else {
                            CrudDataStore store = new CrudDataStore(
                                definition.getIdField() != null ? definition.getIdField() : "id",
                                definition.getIdStrategy() != null ? definition.getIdStrategy() : CrudExpectationsDefinition.IdStrategy.AUTO_INCREMENT,
                                definition.getInitialData()
                            );
                            CrudActionHandler handler = new CrudActionHandler(store, definition.getBasePath());
                            crudDispatcher.register(definition.getBasePath(), handler);
                            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                                mockServerLogger.logEvent(
                                    new LogEntry()
                                        .setLogLevel(Level.INFO)
                                        .setMessageFormat("registered CRUD resource at base path:{}")
                                        .setArguments(definition.getBasePath())
                                );
                            }
                            com.fasterxml.jackson.databind.node.ObjectNode responseNode = objectMapper.createObjectNode();
                            responseNode.put("basePath", definition.getBasePath());
                            responseNode.put("idField", definition.getIdField() != null ? definition.getIdField() : "id");
                            responseNode.put("idStrategy", (definition.getIdStrategy() != null ? definition.getIdStrategy() : CrudExpectationsDefinition.IdStrategy.AUTO_INCREMENT).name());
                            responseNode.put("itemCount", store.size());
                            responseWriter.writeResponse(request, response()
                                .withStatusCode(CREATED.code())
                                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(responseNode), MediaType.JSON_UTF_8), true);
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to register CRUD resource: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/descriptors", "/grpc/descriptors")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        byte[] bodyBytes = request.getBodyAsRawBytes();
                        if (bodyBytes != null && bodyBytes.length > 0) {
                            grpcDescriptorStore.loadDescriptorSet(bodyBytes);
                            responseWriter.writeResponse(request, response()
                                .withStatusCode(CREATED.code())
                                .withBody("{\"status\":\"loaded\"}", MediaType.JSON_UTF_8), true);
                        } else {
                            responseWriter.writeResponse(request, BAD_REQUEST, "descriptor set body is empty", MediaType.create("text", "plain").toString());
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to load gRPC descriptor: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/services", "/grpc/services")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        com.fasterxml.jackson.databind.node.ArrayNode servicesArray = objectMapper.createArrayNode();
                        for (java.util.Map.Entry<String, com.google.protobuf.Descriptors.ServiceDescriptor> entry : grpcDescriptorStore.getAllServices().entrySet()) {
                            com.fasterxml.jackson.databind.node.ObjectNode serviceNode = objectMapper.createObjectNode();
                            serviceNode.put("name", entry.getKey());
                            com.fasterxml.jackson.databind.node.ArrayNode methodsArray = serviceNode.putArray("methods");
                            for (com.google.protobuf.Descriptors.MethodDescriptor method : entry.getValue().getMethods()) {
                                com.fasterxml.jackson.databind.node.ObjectNode methodNode = objectMapper.createObjectNode();
                                methodNode.put("name", method.getName());
                                methodNode.put("inputType", method.getInputType().getFullName());
                                methodNode.put("outputType", method.getOutputType().getFullName());
                                methodNode.put("clientStreaming", method.isClientStreaming());
                                methodNode.put("serverStreaming", method.isServerStreaming());
                                methodsArray.add(methodNode);
                            }
                            servicesArray.add(serviceNode);
                        }
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(servicesArray), MediaType.JSON_UTF_8), true);
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to list gRPC services: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/health", "/grpc/health")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcHealthPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/grpc/clear", "/grpc/clear")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    grpcDescriptorStore.reset();
                    responseWriter.writeResponse(request, OK);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/wasm/modules", "/wasm/modules")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    if (!configuration.wasmEnabled()) {
                        responseWriter.writeResponse(request, FORBIDDEN, "WASM support is disabled; set wasmEnabled=true to enable", MediaType.create("text", "plain").toString());
                    } else {
                        try {
                            String moduleName = request.getFirstQueryStringParameter("name");
                            if (isBlank(moduleName)) {
                                responseWriter.writeResponse(request, BAD_REQUEST, "query parameter 'name' is required", MediaType.create("text", "plain").toString());
                            } else {
                                byte[] bodyBytes = request.getBodyAsRawBytes();
                                if (bodyBytes != null && bodyBytes.length > 0) {
                                    org.mockserver.wasm.WasmStore.getInstance().put(moduleName, bodyBytes);
                                    responseWriter.writeResponse(request, withDashboardCORS(request, response()
                                        .withStatusCode(CREATED.code())
                                        .withBody("{\"status\":\"loaded\",\"moduleName\":\"" + moduleName + "\"}", MediaType.JSON_UTF_8)), true);
                                } else {
                                    responseWriter.writeResponse(request, BAD_REQUEST, "WASM module body is empty", MediaType.create("text", "plain").toString());
                                }
                            }
                        } catch (Exception e) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "failed to load WASM module: " + e.getMessage(), MediaType.create("text", "plain").toString());
                        }
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/store", "/files/store")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String bodyString = request.getBodyAsJsonOrXmlString();
                        if (isNotBlank(bodyString)) {
                            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(bodyString);
                            if (node.has("name") && node.has("content")) {
                                String fileName = node.get("name").asText();
                                String content = node.get("content").asText();
                                byte[] fileContent;
                                if (node.has("base64") && node.get("base64").asBoolean()) {
                                    fileContent = java.util.Base64.getDecoder().decode(content);
                                } else {
                                    fileContent = content.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                                }
                                fileStore.store(fileName, fileContent);
                                responseWriter.writeResponse(request, response()
                                    .withStatusCode(CREATED.code())
                                    .withBody("{\"name\":\"" + fileName + "\",\"size\":" + fileContent.length + "}", MediaType.JSON_UTF_8), true);
                            } else {
                                responseWriter.writeResponse(request, BAD_REQUEST, "request body must contain 'name' and 'content' fields", MediaType.create("text", "plain").toString());
                            }
                        } else {
                            responseWriter.writeResponse(request, BAD_REQUEST, "request body is empty", MediaType.create("text", "plain").toString());
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to store file: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/retrieve", "/files/retrieve")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String bodyString = request.getBodyAsJsonOrXmlString();
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(bodyString);
                        String fileName = node.has("name") ? node.get("name").asText() : null;
                        if (isBlank(fileName)) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "request body must contain 'name' field", MediaType.create("text", "plain").toString());
                        } else {
                            byte[] content = fileStore.retrieve(fileName);
                            if (content != null) {
                                responseWriter.writeResponse(request, response()
                                    .withStatusCode(OK.code())
                                    .withBody(content), true);
                            } else {
                                responseWriter.writeResponse(request, NOT_FOUND, "file not found: " + fileName, MediaType.create("text", "plain").toString());
                            }
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to retrieve file: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/list", "/files/list")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        responseWriter.writeResponse(request, response()
                            .withStatusCode(OK.code())
                            .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(fileStore.listFiles()), MediaType.JSON_UTF_8), true);
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to list files: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/files/delete", "/files/delete")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    try {
                        String bodyString = request.getBodyAsJsonOrXmlString();
                        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(bodyString);
                        String fileName = node.has("name") ? node.get("name").asText() : null;
                        if (isBlank(fileName)) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "request body must contain 'name' field", MediaType.create("text", "plain").toString());
                        } else if (fileStore.delete(fileName)) {
                            responseWriter.writeResponse(request, OK);
                        } else {
                            responseWriter.writeResponse(request, NOT_FOUND, "file not found: " + fileName, MediaType.create("text", "plain").toString());
                        }
                    } catch (Exception e) {
                        responseWriter.writeResponse(request, BAD_REQUEST, "failed to delete file: " + e.getMessage(), MediaType.create("text", "plain").toString());
                    }
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/generateExpectation", "/generateExpectation")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGenerateExpectation(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT") && request.getPath() != null
                && request.getPath().getValue() != null
                && (request.getPath().getValue().startsWith(PATH_PREFIX + "/scenario/")
                    || request.getPath().getValue().startsWith("/scenario/"))) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleScenarioPut(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/replay", "/replay")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    handleReplay(request, responseWriter, canHandle);
                } else {
                    canHandle.complete(true);
                }

            } else if (request.matches("PUT", PATH_PREFIX + "/diff", "/diff")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleDiff(request)), true);
                }
                canHandle.complete(true);

            } else if (request.matches("PUT", PATH_PREFIX + "/drift/clear", "/drift/clear")) {

                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    org.mockserver.mock.drift.DriftStore.getInstance().clear();
                    responseWriter.writeResponse(request, withDashboardCORS(request, response()
                        .withStatusCode(OK.code())
                        .withBody("{\"status\":\"cleared\"}", MediaType.JSON_UTF_8)), true);
                }
                canHandle.complete(true);

            } else {

                canHandle.complete(false);

            }

            try {
                return canHandle.get(configuration.maxFutureTimeoutInMillis(), MILLISECONDS);
            } catch (InterruptedException | ExecutionException | TimeoutException ex) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception handling request:{}error:{}")
                        .setArguments(request, ex.getMessage())
                        .setThrowable(ex)
                );
                return false;
            }

        } else if (request.matches("GET")) {

            if (request.matches("GET", PATH_PREFIX + "/clock", "/clock")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, handleClockGet(), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/config", "/config")) {
                // Effective configuration: each property's resolved value and the source tier that
                // supplied it, with sensitive values redacted (mirrors the --print-config CLI flag).
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, response()
                        .withStatusCode(OK.code())
                        .withBody(ConfigurationProperties.effectiveConfigurationAsJson(), MediaType.JSON_UTF_8)), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/proxyConfiguration", "/proxyConfiguration")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleProxyConfiguration(request)), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/cassettes", "/cassettes")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleCassettesGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/breakpoint/matchers", "/breakpoint/matchers")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleBreakpointMatcherList()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/chaosExperiment/profiles", "/chaosExperiment/profiles")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosProfileList()), true);
                }
                return true;
            }
            if (chaosProfileName(request, "GET", "/chaosExperiment/profiles/") != null) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosProfileGet(chaosProfileName(request, "GET", "/chaosExperiment/profiles/"))), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/chaosExperiment/history", "/chaosExperiment/history")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosExperimentHistoryGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/chaosExperiment", "/chaosExperiment")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosExperimentGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/loadScenario", "/loadScenario")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioGet()), true);
                }
                return true;
            }
            if (loadScenarioReportName(request) != null) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioReport(loadScenarioReportName(request), request.getFirstQueryStringParameter("format"))), true);
                }
                return true;
            }
            if (loadScenarioName(request, "GET") != null) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioGetOne(loadScenarioName(request, "GET"))), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/serviceChaos", "/serviceChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleServiceChaosGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/tcpChaos", "/tcpChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleTcpChaosGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/preemption", "/preemption")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handlePreemptionGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/grpcChaos", "/grpcChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcChaosGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/mode", "/mode")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    boolean proxyFlag = configuration.attemptToProxyIfNoMatchingExpectation();
                    // report the last explicitly-set mode when it still agrees with the live flag
                    // (so CAPTURE round-trips), otherwise derive the mode from the flag
                    MockMode mode = (mockMode != null && mockMode.proxyUnmatchedRequests() == proxyFlag)
                        ? mockMode
                        : MockMode.fromProxyFlag(proxyFlag);
                    responseWriter.writeResponse(request, response()
                        .withStatusCode(OK.code())
                        .withBody("{\"mode\":\"" + mode + "\",\"proxyUnmatchedRequests\":" + mode.proxyUnmatchedRequests() + "}", MediaType.JSON_UTF_8), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/wasm/modules", "/wasm/modules")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    if (!configuration.wasmEnabled()) {
                        responseWriter.writeResponse(request, FORBIDDEN, "WASM support is disabled; set wasmEnabled=true to enable", MediaType.create("text", "plain").toString());
                    } else {
                        try {
                            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                            com.fasterxml.jackson.databind.node.ArrayNode modulesArray = objectMapper.createArrayNode();
                            for (String name : org.mockserver.wasm.WasmStore.getInstance().listNames()) {
                                modulesArray.add(name);
                            }
                            responseWriter.writeResponse(request, withDashboardCORS(request, response()
                                .withStatusCode(OK.code())
                                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(modulesArray), MediaType.JSON_UTF_8)), true);
                        } catch (Exception e) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "failed to list WASM modules: " + e.getMessage(), MediaType.create("text", "plain").toString());
                        }
                    }
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/asyncapi", "/asyncapi")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAsyncApiGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/drift", "/drift")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleDriftGet(request)), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/audit", "/audit")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleAuditGet(request)), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/grpc/health", "/grpc/health")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcHealthGet()), true);
                }
                return true;
            }
            if (request.matches("GET", PATH_PREFIX + "/cluster", "/cluster")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleClusterGet()), true);
                }
                return true;
            }
            if (request.matches("GET") && request.getPath() != null
                && request.getPath().getValue() != null
                && (request.getPath().getValue().startsWith(PATH_PREFIX + "/scenario/")
                    || request.getPath().getValue().startsWith("/scenario/")
                    || request.getPath().getValue().equals(PATH_PREFIX + "/scenario")
                    || request.getPath().getValue().equals("/scenario"))) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleScenarioGet(request)), true);
                }
                return true;
            }
            return false;

        } else if (request.matches("PATCH")) {

            if (request.matches("PATCH", PATH_PREFIX + "/serviceChaos", "/serviceChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleServiceChaosPatch(request)), true);
                }
                return true;
            }
            if (request.matches("PATCH", PATH_PREFIX + "/tcpChaos", "/tcpChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleTcpChaosPatch(request)), true);
                }
                return true;
            }
            if (request.matches("PATCH", PATH_PREFIX + "/grpcChaos", "/grpcChaos")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleGrpcChaosPatch(request)), true);
                }
                return true;
            }
            return false;

        } else if (request.matches("DELETE")) {

            if (request.matches("DELETE", PATH_PREFIX + "/wasm/modules", "/wasm/modules")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    if (!configuration.wasmEnabled()) {
                        responseWriter.writeResponse(request, FORBIDDEN, "WASM support is disabled; set wasmEnabled=true to enable", MediaType.create("text", "plain").toString());
                    } else {
                        String moduleName = request.getFirstQueryStringParameter("name");
                        if (isBlank(moduleName)) {
                            responseWriter.writeResponse(request, BAD_REQUEST, "query parameter 'name' is required", MediaType.create("text", "plain").toString());
                        } else if (org.mockserver.wasm.WasmStore.getInstance().contains(moduleName)) {
                            org.mockserver.wasm.WasmStore.getInstance().remove(moduleName);
                            responseWriter.writeResponse(request, withDashboardCORS(request, response().withStatusCode(OK.code())), true);
                        } else {
                            responseWriter.writeResponse(request, NOT_FOUND, "WASM module '" + moduleName + "' not found", MediaType.create("text", "plain").toString());
                        }
                    }
                }
                return true;
            }
            if (chaosProfileName(request, "DELETE", "/chaosExperiment/profiles/") != null) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosProfileDelete(chaosProfileName(request, "DELETE", "/chaosExperiment/profiles/"))), true);
                }
                return true;
            }
            if (request.matches("DELETE", PATH_PREFIX + "/chaosExperiment", "/chaosExperiment")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosExperimentDelete()), true);
                }
                return true;
            }
            if (request.matches("DELETE", PATH_PREFIX + "/loadScenario", "/loadScenario")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioDeleteAll()), true);
                }
                return true;
            }
            if (loadScenarioName(request, "DELETE") != null) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleLoadScenarioDeleteOne(loadScenarioName(request, "DELETE"))), true);
                }
                return true;
            }
            if (request.matches("DELETE", PATH_PREFIX + "/cassettes", "/cassettes")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleCassettesDelete(request)), true);
                }
                return true;
            }
            if (request.matches("DELETE", PATH_PREFIX + "/preemption", "/preemption")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handlePreemptionDelete()), true);
                }
                return true;
            }
            return false;

        } else if (request.matches("POST")) {

            if (chaosProfileName(request, "POST", "/chaosExperiment/apply/") != null) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    responseWriter.writeResponse(request, withDashboardCORS(request, handleChaosProfileApply(request, chaosProfileName(request, "POST", "/chaosExperiment/apply/"))), true);
                }
                return true;
            }
            if (request.matches("POST", PATH_PREFIX + "/wasm/test", "/wasm/test")) {
                if (controlPlaneRequestAuthenticated(request, responseWriter)) {
                    if (!configuration.wasmEnabled()) {
                        responseWriter.writeResponse(request, FORBIDDEN, "WASM support is disabled; set wasmEnabled=true to enable", MediaType.create("text", "plain").toString());
                    } else {
                        responseWriter.writeResponse(request, withDashboardCORS(request, handleWasmTest(request)), true);
                    }
                }
                return true;
            }
            return false;

        } else {

            return false;

        }

    }

    /**
     * Test a WASM module against a sample request without a live expectation.
     * <p>
     * Accepts {@code { "module": "<base64>", "request": { method, path, headers, body } }}
     * and returns {@code { "matched": true|false }}, so IDEs/users can validate a module
     * against a sample request. Fails closed: invalid modules report {@code matched=false}.
     */
    private HttpResponse handleWasmTest(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(objectMapper.createObjectNode().put("error", "request body is required with a 'module' field").toString(), MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String moduleField = node.has("module") && !node.get("module").isNull() ? node.get("module").asText() : null;
            byte[] wasmBytes;
            if (isNotBlank(moduleField)) {
                try {
                    wasmBytes = java.util.Base64.getDecoder().decode(moduleField);
                } catch (IllegalArgumentException e) {
                    return response()
                        .withStatusCode(BAD_REQUEST.code())
                        .withBody(objectMapper.createObjectNode().put("error", "'module' must be base64-encoded WASM bytes").toString(), MediaType.JSON_UTF_8);
                }
            } else if (node.has("moduleName") && !node.get("moduleName").isNull()) {
                String moduleName = node.get("moduleName").asText();
                wasmBytes = org.mockserver.wasm.WasmStore.getInstance().get(moduleName);
                if (wasmBytes == null) {
                    return response()
                        .withStatusCode(NOT_FOUND.code())
                        .withBody(objectMapper.createObjectNode().put("error", "WASM module '" + moduleName + "' not found").toString(), MediaType.JSON_UTF_8);
                }
            } else {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(objectMapper.createObjectNode().put("error", "either 'module' (base64) or 'moduleName' (loaded module) is required").toString(), MediaType.JSON_UTF_8);
            }

            com.fasterxml.jackson.databind.JsonNode requestNode = node.get("request");
            String method = "";
            String path = "";
            String sampleBody = null;
            org.mockserver.wasm.WasmRequest wasmRequest;
            if (requestNode != null && requestNode.isObject()) {
                method = requestNode.has("method") && !requestNode.get("method").isNull() ? requestNode.get("method").asText() : "";
                path = requestNode.has("path") && !requestNode.get("path").isNull() ? requestNode.get("path").asText() : "";
                sampleBody = requestNode.has("body") && !requestNode.get("body").isNull() ? requestNode.get("body").asText() : null;
                wasmRequest = new org.mockserver.wasm.WasmRequest(method, path, null, null, null, sampleBody);
                addWasmMultiValued(requestNode.get("headers"), wasmRequest::withHeader);
                addWasmMultiValued(requestNode.get("queryStringParameters"), wasmRequest::withQueryStringParameter);
                com.fasterxml.jackson.databind.JsonNode cookiesNode = requestNode.get("cookies");
                if (cookiesNode != null && cookiesNode.isObject()) {
                    java.util.Iterator<String> cookieNames = cookiesNode.fieldNames();
                    while (cookieNames.hasNext()) {
                        String name = cookieNames.next();
                        com.fasterxml.jackson.databind.JsonNode valueNode = cookiesNode.get(name);
                        wasmRequest.withCookie(name, valueNode == null || valueNode.isNull() ? null : valueNode.asText());
                    }
                }
            } else {
                wasmRequest = org.mockserver.wasm.WasmRequest.ofBody(sampleBody);
            }

            boolean matched = new org.mockserver.wasm.WasmRuntime(wasmBytes).callMatch(wasmRequest);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("matched", matched);
            // Optionally exercise the shape_response export (ABI v3): when the caller supplies a candidate
            // "response", return the shaped response the module would produce (or null when it does not shape
            // / opts out / fails), so IDEs can preview response shaping without a live expectation.
            com.fasterxml.jackson.databind.JsonNode candidateResponseNode = node.get("response");
            if (candidateResponseNode != null && candidateResponseNode.isObject()) {
                addWasmShapedResponse(result, wasmBytes, wasmRequest, candidateResponseNode);
            }
            return response()
                .withStatusCode(OK.code())
                .withBody(result.toString(), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.createObjectNode().put("error", "failed to test WASM module: " + e.getMessage()).toString(), MediaType.JSON_UTF_8);
        }
    }

    /**
     * Exercise a module's {@code shape_response} export against a caller-supplied candidate response and
     * add the shaped result to {@code result} under {@code "shaped"} (or {@code null} when the module does
     * not shape, opts out, or fails — the endpoint stays fail-safe). Used by {@code POST /wasm/test}.
     */
    private static void addWasmShapedResponse(com.fasterxml.jackson.databind.node.ObjectNode result,
                                              byte[] wasmBytes,
                                              org.mockserver.wasm.WasmRequest wasmRequest,
                                              com.fasterxml.jackson.databind.JsonNode responseNode) {
        try {
            Integer statusCode = responseNode.has("statusCode") && responseNode.get("statusCode").isNumber()
                ? responseNode.get("statusCode").intValue() : null;
            String body = responseNode.has("body") && !responseNode.get("body").isNull()
                ? responseNode.get("body").asText() : null;
            java.util.Map<String, java.util.List<String>> headers = new java.util.LinkedHashMap<>();
            com.fasterxml.jackson.databind.JsonNode headersNode = responseNode.get("headers");
            if (headersNode != null && headersNode.isObject()) {
                java.util.Iterator<String> names = headersNode.fieldNames();
                while (names.hasNext()) {
                    String name = names.next();
                    com.fasterxml.jackson.databind.JsonNode valuesNode = headersNode.get(name);
                    java.util.List<String> values = new java.util.ArrayList<>();
                    if (valuesNode != null && valuesNode.isArray()) {
                        for (com.fasterxml.jackson.databind.JsonNode v : valuesNode) {
                            if (!v.isNull()) {
                                values.add(v.asText());
                            }
                        }
                    } else if (valuesNode != null && !valuesNode.isNull()) {
                        values.add(valuesNode.asText());
                    }
                    headers.put(name, values);
                }
            }
            org.mockserver.wasm.WasmResponse shaped = new org.mockserver.wasm.WasmRuntime(wasmBytes)
                .callShape(wasmRequest, new org.mockserver.wasm.WasmResponse(statusCode, headers, body));
            if (shaped == null) {
                result.putNull("shaped");
                return;
            }
            com.fasterxml.jackson.databind.node.ObjectNode shapedNode = result.putObject("shaped");
            if (shaped.getStatusCode() == null) {
                shapedNode.putNull("statusCode");
            } else {
                shapedNode.put("statusCode", shaped.getStatusCode());
            }
            com.fasterxml.jackson.databind.node.ObjectNode shapedHeaders = shapedNode.putObject("headers");
            if (shaped.getHeaders() != null) {
                for (java.util.Map.Entry<String, java.util.List<String>> entry : shaped.getHeaders().entrySet()) {
                    com.fasterxml.jackson.databind.node.ArrayNode values = shapedHeaders.putArray(entry.getKey());
                    if (entry.getValue() != null) {
                        for (String value : entry.getValue()) {
                            values.add(value);
                        }
                    }
                }
            }
            if (shaped.getBody() == null) {
                shapedNode.putNull("body");
            } else {
                shapedNode.put("body", shaped.getBody());
            }
        } catch (Exception e) {
            // fail-safe: a broken shaper never breaks the test endpoint
            result.putNull("shaped");
        }
    }

    /**
     * Add a {@code name -> [values]} (or {@code name -> value}) JSON object from the sample
     * request into the {@link org.mockserver.wasm.WasmRequest} envelope, used for both the
     * {@code headers} and {@code queryStringParameters} fields of the {@code /wasm/test} payload.
     */
    private static void addWasmMultiValued(com.fasterxml.jackson.databind.JsonNode node, java.util.function.BiConsumer<String, String> add) {
        if (node == null || !node.isObject()) {
            return;
        }
        java.util.Iterator<String> names = node.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            com.fasterxml.jackson.databind.JsonNode valuesNode = node.get(name);
            if (valuesNode != null && valuesNode.isArray()) {
                for (com.fasterxml.jackson.databind.JsonNode v : valuesNode) {
                    add.accept(name, v.isNull() ? null : v.asText());
                }
            } else if (valuesNode != null) {
                add.accept(name, valuesNode.isNull() ? null : valuesNode.asText());
            }
        }
    }

    private HttpResponse handleClockPut(HttpRequest request) {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "request body is required with 'action' field")), MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String action = node.has("action") ? node.get("action").asText() : null;
            if (isBlank(action)) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "'action' field is required, must be one of: freeze, advance, reset")), MediaType.JSON_UTF_8);
            }
            switch (action.toLowerCase()) {
                case "freeze": {
                    java.time.Instant instant = null;
                    if (node.has("instant") && !node.get("instant").isNull()) {
                        try {
                            instant = java.time.Instant.parse(node.get("instant").asText());
                        } catch (Exception e) {
                            return response()
                                .withStatusCode(BAD_REQUEST.code())
                                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                                    objectMapper.createObjectNode().put("error", "invalid 'instant' value, must be ISO-8601 format (e.g. 2024-01-01T00:00:00Z)")), MediaType.JSON_UTF_8);
                        }
                    }
                    TimeService.freeze(instant);
                    break;
                }
                case "advance": {
                    long durationMillis = 0;
                    if (node.has("durationMillis") && !node.get("durationMillis").isNull()) {
                        durationMillis = node.get("durationMillis").asLong(0);
                    }
                    if (durationMillis <= 0) {
                        return response()
                            .withStatusCode(BAD_REQUEST.code())
                            .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                                objectMapper.createObjectNode().put("error", "'durationMillis' must be a positive number")), MediaType.JSON_UTF_8);
                    }
                    TimeService.advance(java.time.Duration.ofMillis(durationMillis));
                    break;
                }
                case "reset": {
                    TimeService.reset();
                    break;
                }
                default: {
                    return response()
                        .withStatusCode(BAD_REQUEST.code())
                        .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                            objectMapper.createObjectNode().put("error", "unknown action '" + action + "', must be one of: freeze, advance, reset")), MediaType.JSON_UTF_8);
                }
            }
            // success response
            java.time.Instant currentInstant = TimeService.now();
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("clock " + action.toLowerCase() + ", current instant:{}")
                        .setArguments(currentInstant)
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("status", action.toLowerCase());
            resultNode.put("currentInstant", currentInstant.toString());
            resultNode.put("currentEpochMillis", currentInstant.toEpochMilli());
            return response()
                .withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            try {
                com.fasterxml.jackson.databind.ObjectMapper errorMapper = ObjectMapperFactory.createObjectMapper();
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody(errorMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        errorMapper.createObjectNode().put("error", "failed to process clock request: " + e.getMessage())), MediaType.JSON_UTF_8);
            } catch (Exception jsonError) {
                return response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"failed to process clock request\"}", MediaType.JSON_UTF_8);
            }
        }
    }

    private HttpResponse handleClockGet() {
        try {
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            java.time.Instant currentInstant = TimeService.now();
            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("currentInstant", currentInstant.toString());
            resultNode.put("currentEpochMillis", currentInstant.toEpochMilli());
            resultNode.put("frozen", TimeService.isFrozen());
            return response()
                .withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get clock status\"}", MediaType.JSON_UTF_8);
        }
    }

    /**
     * Serves the copy-paste proxy-setup information — the active Certificate Authority certificate path
     * and PEM (public certificate only, never the private key), the HTTPS proxy URL, and OS-specific
     * environment-variable blocks — the same information printed on startup. Returns the plain-text
     * copy-paste block when the request Accepts text/plain, otherwise a JSON document.
     */
    private HttpResponse handleProxyConfiguration(HttpRequest request) {
        try {
            org.mockserver.socket.tls.KeyAndCertificateFactory keyAndCertificateFactory =
                org.mockserver.socket.tls.KeyAndCertificateFactoryFactory.createKeyAndCertificateFactory(configuration, mockServerLogger);
            String caCertificatePath = keyAndCertificateFactory.writeCertificateAuthorityToDisk();
            List<Integer> ports = getPort() == null ? Collections.emptyList() : Collections.singletonList(getPort());
            org.mockserver.socket.tls.ProxySetupInfo info =
                new org.mockserver.socket.tls.ProxySetupInfo(caCertificatePath, ports, configuration, System.getProperty("os.name"));

            String acceptHeader = request.getFirstHeader("Accept");
            if (acceptHeader != null && acceptHeader.toLowerCase().contains("text/plain")) {
                return response()
                    .withStatusCode(OK.code())
                    .withBody(info.copyPasteText(), MediaType.create("text", "plain").withCharset(UTF_8));
            }

            // public certificate only — the private key is never read or exposed here
            String caCertificatePem = "";
            try {
                caCertificatePem = new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(caCertificatePath)), UTF_8);
            } catch (Exception ignore) {
                // PEM contents are best-effort; the path is still returned
            }

            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode resultNode = objectMapper.createObjectNode();
            resultNode.put("caCertificatePath", info.caCertificatePath());
            resultNode.put("caCertificatePem", caCertificatePem);
            resultNode.put("httpsProxy", info.httpsProxyUrl());
            com.fasterxml.jackson.databind.node.ObjectNode environmentVariables = resultNode.putObject("environmentVariables");
            environmentVariables.put("unix", info.unixEnvBlock());
            environmentVariables.put("powershell", info.powershellEnvBlock());
            resultNode.put("usingDefaultCa", info.usingDefaultCa());
            if (info.warning() != null) {
                resultNode.put("warning", info.warning());
            } else {
                resultNode.putNull("warning");
            }
            return response()
                .withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(resultNode), MediaType.JSON_UTF_8);
        } catch (Throwable throwable) {
            // Throwable (not just Exception) so a third-party custom KeyAndCertificateFactory that throws
            // AbstractMethodError/Error still yields a 400 here rather than propagating (matches the
            // fail-soft LifeCycle.logProxySetup path).
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("exception handling request for proxy configuration:{}")
                    .setArguments(throwable.getMessage())
                    .setThrowable(throwable)
            );
            return response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get proxy configuration\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleServiceChaosPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return serviceChaosError(objectMapper, "request body is required with a 'host' field (and a 'chaos' object), or 'clear':true to clear all");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            boolean clearAll = node.path("clear").asBoolean(false);
            String host = node.path("host").asText(null);
            org.mockserver.mock.action.http.ServiceChaosRegistry registry = org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance();
            // 'clear' (clear all) and 'host' (single-host operation) are mutually exclusive
            if (clearAll && !isBlank(host)) {
                return serviceChaosError(objectMapper, "cannot specify both 'clear' and 'host'");
            }
            // clear all service-scoped chaos
            if (clearAll) {
                registry.reset();
                logServiceChaos(request, "cleared all service-scoped chaos", null);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "cleared");
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            if (isBlank(host)) {
                return serviceChaosError(objectMapper, "'host' field is required");
            }
            // remove the host's chaos when requested or when no chaos object is supplied
            if (node.path("remove").asBoolean(false) || !node.hasNonNull("chaos")) {
                registry.remove(host);
                logServiceChaos(request, "removed service-scoped chaos for host:{}", host);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "removed");
                result.put("host", host);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            // optional time-to-live (auto-revert): the registration auto-expires after this many ms
            long ttlMillis = 0L;
            if (node.hasNonNull("ttlMillis")) {
                ttlMillis = node.path("ttlMillis").asLong(0L);
                if (ttlMillis < 1) {
                    return serviceChaosError(objectMapper, "'ttlMillis' must be >= 1 when supplied");
                }
            }
            // register/replace — deserialize through the DTO so range validation runs
            org.mockserver.serialization.model.HttpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.HttpChaosProfileDTO.class);
            org.mockserver.model.HttpChaosProfile profile = dto.buildObject();
            registry.put(host, profile, ttlMillis);
            logServiceChaos(request, ttlMillis > 0
                ? "registered service-scoped chaos (ttl " + ttlMillis + "ms) for host:{}"
                : "registered service-scoped chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("host", host);
            if (ttlMillis > 0) {
                result.put("ttlMillis", ttlMillis);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            // thrown by HttpChaosProfile validation (e.g. errorStatus out of range)
            return serviceChaosError(objectMapper, "invalid chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return serviceChaosError(objectMapper, "failed to process service chaos request: " + e.getMessage());
        }
    }

    private HttpResponse handleServiceChaosPatch(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return serviceChaosError(objectMapper, "request body is required with 'host' and 'chaos' fields");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String host = node.path("host").asText(null);
            if (isBlank(host)) {
                return serviceChaosError(objectMapper, "'host' field is required");
            }
            if (!node.hasNonNull("chaos")) {
                return serviceChaosError(objectMapper, "'chaos' field is required with at least one field to patch");
            }
            org.mockserver.serialization.model.HttpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.HttpChaosProfileDTO.class);
            org.mockserver.model.HttpChaosProfile partial = dto.buildObject();
            org.mockserver.mock.action.http.ServiceChaosRegistry registry = org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance();
            org.mockserver.model.HttpChaosProfile updated = registry.patch(host, partial);
            logServiceChaos(request, "patched service-scoped chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "patched");
            result.put("host", host);
            if (updated != null) {
                result.set("chaos", objectMapper.valueToTree(new org.mockserver.serialization.model.HttpChaosProfileDTO(updated)));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return serviceChaosError(objectMapper, "invalid chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return serviceChaosError(objectMapper, "failed to process service chaos patch: " + e.getMessage());
        }
    }

    /**
     * Add CORS headers to a dashboard-facing control-plane response unconditionally,
     * so the dashboard works when served from a different origin (e.g. the UI dev
     * server) without requiring {@code enableCORSForAPI} to be set. This mirrors the
     * always-on CORS already applied by the metrics ({@code MetricsHandler}) and MCP
     * endpoints. {@code CORSHeaders.addCORSHeaders} is idempotent
     * ({@code setHeaderIfNotAlreadyExists}), so it composes safely with the
     * conditional CORS that {@code ResponseWriter} may also apply.
     */
    private HttpResponse withDashboardCORS(HttpRequest request, HttpResponse response) {
        corsHeaders.addCORSHeaders(request, response);
        return response;
    }

    private HttpResponse handleServiceChaosGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.ServiceChaosRegistry registry = org.mockserver.mock.action.http.ServiceChaosRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode services = result.putObject("services");
            registry.entries().forEach((host, profile) ->
                services.set(host, objectMapper.valueToTree(new org.mockserver.serialization.model.HttpChaosProfileDTO(profile))));
            // remaining time-to-live (ms) for any TTL-bearing registration, so an operator/orchestrator can see the countdown
            java.util.Map<String, Long> ttlRemaining = registry.ttlRemainingMillis();
            if (!ttlRemaining.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode ttlNode = result.putObject("ttlRemainingMillis");
                ttlRemaining.forEach((h, ms) -> ttlNode.put(h, ms.longValue()));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get service chaos\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logServiceChaos(HttpRequest request, String messageFormat, String host) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            LogEntry entry = new LogEntry()
                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(messageFormat);
            if (host != null) {
                entry.setArguments(host);
            }
            mockServerLogger.logEvent(entry);
        }
    }

    private HttpResponse serviceChaosError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process service chaos request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- Chaos Experiment endpoint helpers ---

    private HttpResponse handleChaosExperimentPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return chaosExperimentError(objectMapper, "request body is required with an experiment definition");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentDefinition definition =
                org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(node);
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator orchestrator =
                org.mockserver.mock.action.http.ChaosExperimentOrchestrator.getInstance();
            String error = orchestrator.start(definition);
            if (error != null) {
                return chaosExperimentError(objectMapper, error);
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("started chaos experiment:{}")
                        .setArguments(definition.name)
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "started");
            result.put("name", definition.name);
            result.put("stages", definition.stages.size());
            result.put("loop", definition.loop);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return chaosExperimentError(objectMapper, "invalid experiment definition: " + e.getMessage());
        } catch (Exception e) {
            return chaosExperimentError(objectMapper, "failed to process chaos experiment request: " + e.getMessage());
        }
    }

    /**
     * Handle {@code PUT /mockserver/verifySLO}: parse the body into an
     * {@link org.mockserver.slo.SloCriteria}, evaluate it against the recorded
     * samples, and respond with the {@link org.mockserver.slo.SloVerdict} JSON.
     *
     * <p>Status mapping: {@code 200 OK} for a PASS or INCONCLUSIVE verdict,
     * {@code 406 NOT_ACCEPTABLE} for a FAIL verdict (so a CI gate can assert on
     * the status code alone), {@code 400 BAD_REQUEST} for a malformed body or
     * when SLO tracking is disabled. The body is always JSON.
     */
    private HttpResponse handleVerifySlo(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            if (!configuration.sloTrackingEnabled()) {
                return sloError(objectMapper, "SLO tracking not enabled (set sloTrackingEnabled=true)");
            }
            org.mockserver.slo.SloCriteria criteria = getSloCriteriaSerializer().deserialize(request.getBodyAsJsonOrXmlString());
            org.mockserver.slo.SloVerdict verdict = new org.mockserver.slo.SloEvaluator().evaluate(criteria);
            int statusCode = verdict.getResult() == org.mockserver.slo.SloVerdict.Result.FAIL
                ? NOT_ACCEPTABLE.code()
                : OK.code();
            return response().withStatusCode(statusCode)
                .withBody(getSloCriteriaSerializer().serialize(verdict), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return sloError(objectMapper, "invalid SLO criteria: " + e.getMessage());
        } catch (Exception e) {
            return sloError(objectMapper, "failed to process SLO verify request: " + e.getMessage());
        }
    }

    private HttpResponse sloError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        com.fasterxml.jackson.databind.node.ObjectNode errorNode = objectMapper.createObjectNode();
        errorNode.put("error", message);
        String body;
        try {
            body = objectMapper.writeValueAsString(errorNode);
        } catch (Exception e) {
            body = "{\"error\":\"failed to render SLO error\"}";
        }
        return response().withStatusCode(BAD_REQUEST.code())
            .withBody(body, MediaType.JSON_UTF_8);
    }

    private HttpResponse handleChaosExperimentGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator orchestrator =
                org.mockserver.mock.action.http.ChaosExperimentOrchestrator.getInstance();
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentStatus status = orchestrator.getStatus();
            if (status == null) {
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "none");
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(status.toJson()), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get chaos experiment status\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleChaosExperimentHistoryGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator orchestrator =
                org.mockserver.mock.action.http.ChaosExperimentOrchestrator.getInstance();
            java.util.List<org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentHistoryEntry> entries =
                orchestrator.getHistory(org.mockserver.mock.action.http.ChaosExperimentOrchestrator.MAX_HISTORY);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("count", entries.size());
            com.fasterxml.jackson.databind.node.ArrayNode historyArray = result.putArray("history");
            for (org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentHistoryEntry entry : entries) {
                historyArray.add(entry.toJson());
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get chaos experiment history\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleChaosExperimentDelete() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator orchestrator =
                org.mockserver.mock.action.http.ChaosExperimentOrchestrator.getInstance();
            orchestrator.stop();
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("stopped chaos experiment")
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "stopped");
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to stop chaos experiment\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse chaosExperimentError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process chaos experiment request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- Load Scenario endpoint helpers ---

    /**
     * Handle {@code PUT /mockserver/loadScenario}: deserialize the body into a
     * {@link org.mockserver.load.LoadScenario} and <em>load</em> (register) it into the registry under
     * its {@code name}. Loading does NOT run the scenario — it is staged in the {@code LOADED} state,
     * ready to be triggered by {@code PUT /mockserver/loadScenario/start}. Loading is allowed even when
     * {@code loadGenerationEnabled} is false (no traffic is generated). Returns 200 with
     * {@code {status:"loaded", name, state:"LOADED"}} on success, or 400 with {@code {error}} when the
     * scenario is invalid or exceeds a configured cap.
     */
    private HttpResponse handleLoadScenarioPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return loadScenarioError(objectMapper, "request body is required with a load scenario definition");
            }
            org.mockserver.load.LoadScenario scenario = getLoadScenarioSerializer().deserialize(body);
            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            orchestrator.setConfiguration(configuration);
            // Validate the definition (name, steps, profile, caps) before registering so a bad scenario
            // fails at load time, not at trigger time.
            String error = orchestrator.validate(scenario);
            if (error != null) {
                return loadScenarioError(objectMapper, error);
            }
            // Store the normalised definition (re-serialised so the registry round-trips the exact
            // author shape, including startDelayMillis) keyed by name; loading the same name replaces.
            com.fasterxml.jackson.databind.JsonNode definition =
                objectMapper.readTree(getLoadScenarioSerializer().serialize(scenario));
            loadScenarioRegistry.load(scenario.getName(), definition);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("loaded load scenario:{}")
                        .setArguments(scenario.getName())
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "loaded");
            result.put("name", scenario.getName());
            result.put("state", loadScenarioStateFor(scenario.getName()).name());
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return loadScenarioError(objectMapper, "invalid load scenario definition: " + e.getMessage());
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to process load scenario request: " + e.getMessage());
        }
    }

    /**
     * Handle {@code PUT /mockserver/loadScenario/generateFromOpenAPI}: seed an editable
     * {@link org.mockserver.load.LoadScenario} from an OpenAPI spec and <em>load</em> (register) it in
     * the {@code LOADED} state — exactly like {@code PUT /mockserver/loadScenario}, this generates no
     * traffic and is allowed even when {@code loadGenerationEnabled} is false. The generated scenario is
     * returned in the response so a client/UI can show and edit it before triggering a run.
     *
     * <p>Body (JSON): {@code { "name": "...", "specUrlOrPayload": <inline spec|url|file>,
     * "target": { "host":..., "port":..., "scheme":... } (optional),
     * "profile": <LoadProfile> (optional) }}. One step is generated per OpenAPI operation. Returns
     * {@code 200 { status:"loaded", name, state:"LOADED", scenario:<generated LoadScenario> }} on
     * success, or {@code 400 { error }} when the spec is missing/unparseable or the generated scenario
     * fails validation.
     */
    private HttpResponse handleLoadScenarioGenerateFromOpenAPI(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return loadScenarioError(objectMapper, "request body is required with a name and OpenAPI specUrlOrPayload");
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
            String name = root.path("name").isMissingNode() ? null : root.path("name").asText(null);
            if (isBlank(name)) {
                return loadScenarioError(objectMapper, "'name' is required");
            }
            String specUrlOrPayload = readOpenApiSpec(root);
            if (isBlank(specUrlOrPayload)) {
                return loadScenarioError(objectMapper, "'specUrlOrPayload' is required (inline OpenAPI spec, URL or file)");
            }
            org.mockserver.load.LoadScenarioFromOpenAPI.Target target = readGenerateTarget(root);
            org.mockserver.load.LoadProfile profile = null;
            if (root.has("profile") && !root.get("profile").isNull()) {
                // Deserialize via the DTO so the profile parses identically to PUT /loadScenario.
                org.mockserver.serialization.model.LoadProfileDTO profileDTO =
                    objectMapper.treeToValue(root.get("profile"), org.mockserver.serialization.model.LoadProfileDTO.class);
                profile = profileDTO != null ? profileDTO.buildObject() : null;
            }

            org.mockserver.load.LoadScenario scenario;
            try {
                scenario = org.mockserver.load.LoadScenarioFromOpenAPI.generate(name, specUrlOrPayload, target, profile, mockServerLogger);
            } catch (IllegalArgumentException e) {
                return loadScenarioError(objectMapper, "failed to generate load scenario from OpenAPI: " + e.getMessage());
            }

            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            orchestrator.setConfiguration(configuration);
            String error = orchestrator.validate(scenario);
            if (error != null) {
                return loadScenarioError(objectMapper, error);
            }
            // Round-trip through the serializer so the registry stores the exact author shape, matching
            // PUT /loadScenario.
            String serializedScenario = getLoadScenarioSerializer().serialize(scenario);
            com.fasterxml.jackson.databind.JsonNode definition = objectMapper.readTree(serializedScenario);
            loadScenarioRegistry.load(scenario.getName(), definition);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("generated and loaded load scenario:{}from OpenAPI with {}steps")
                        .setArguments(scenario.getName(), scenario.getSteps().size())
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "loaded");
            result.put("name", scenario.getName());
            result.put("state", loadScenarioStateFor(scenario.getName()).name());
            result.set("scenario", definition);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return loadScenarioError(objectMapper, "invalid generate-from-OpenAPI request: " + e.getMessage());
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to process generate-from-OpenAPI request: " + e.getMessage());
        }
    }

    /**
     * Reads the OpenAPI spec from a generate-from-OpenAPI request body, accepting it identically to the
     * {@code PUT /mockserver/openapi/expectation} surface: a {@code specUrlOrPayload} field holding an
     * inline JSON/YAML string, a URL or a file/classpath reference. When the field is an embedded JSON
     * object (an inline spec sent as JSON rather than a string) it is re-serialised to a payload string.
     */
    private String readOpenApiSpec(com.fasterxml.jackson.databind.JsonNode root) throws Exception {
        com.fasterxml.jackson.databind.JsonNode specNode = root.get("specUrlOrPayload");
        if (specNode == null || specNode.isNull()) {
            return null;
        }
        if (specNode.isObject()) {
            return ObjectMapperFactory.createObjectMapper().writeValueAsString(specNode);
        }
        return specNode.asText(null);
    }

    /** Reads the optional {@code target} object ({@code host}/{@code port}/{@code scheme}) from a generate request. */
    private org.mockserver.load.LoadScenarioFromOpenAPI.Target readGenerateTarget(com.fasterxml.jackson.databind.JsonNode root) {
        com.fasterxml.jackson.databind.JsonNode targetNode = root.get("target");
        if (targetNode == null || targetNode.isNull() || !targetNode.isObject()) {
            return null;
        }
        String host = targetNode.hasNonNull("host") ? targetNode.get("host").asText() : null;
        Integer port = targetNode.hasNonNull("port") ? targetNode.get("port").asInt() : null;
        String scheme = targetNode.hasNonNull("scheme") ? targetNode.get("scheme").asText() : null;
        return new org.mockserver.load.LoadScenarioFromOpenAPI.Target(host, port, scheme);
    }

    /**
     * Handle {@code PUT /mockserver/loadScenario/generateFromRecording}: seed an editable
     * {@link org.mockserver.load.LoadScenario} from traffic previously recorded by the proxy (the
     * {@code RECEIVED_REQUEST} entries held by the event log) and <em>load</em> (register) it in the
     * {@code LOADED} state — exactly like {@code PUT /mockserver/loadScenario}, this generates no traffic
     * and is allowed even when {@code loadGenerationEnabled} is false. The generated scenario is returned
     * so a client/UI can show and edit it before triggering a run.
     *
     * <p>Body (JSON): {@code { "name": "...", "mode": "VERBATIM"|"TEMPLATIZED" (default VERBATIM),
     * "requestFilter": <HttpRequest matcher> (optional — selects which recorded requests to include),
     * "maxSteps": <int> (optional, VERBATIM only), "target": { "host":..., "port":..., "scheme":... }
     * (optional, applied to every step), "profile": <LoadProfile> (optional) }}. Returns
     * {@code 200 { status:"loaded", name, state:"LOADED", scenario:<generated LoadScenario> }} on
     * success, or {@code 400 { error }} when the name is missing or there are no recorded requests to
     * convert.
     */
    private HttpResponse handleLoadScenarioGenerateFromRecording(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return loadScenarioError(objectMapper, "request body is required with a name");
            }
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(body);
            String name = root.path("name").isMissingNode() ? null : root.path("name").asText(null);
            if (isBlank(name)) {
                return loadScenarioError(objectMapper, "'name' is required");
            }

            org.mockserver.load.LoadScenarioFromRecording.Mode mode = org.mockserver.load.LoadScenarioFromRecording.Mode.VERBATIM;
            if (root.hasNonNull("mode")) {
                String modeText = root.get("mode").asText();
                try {
                    mode = org.mockserver.load.LoadScenarioFromRecording.Mode.valueOf(modeText.trim().toUpperCase());
                } catch (IllegalArgumentException e) {
                    return loadScenarioError(objectMapper, "invalid 'mode' (expected VERBATIM or TEMPLATIZED): " + modeText);
                }
            }
            Integer maxSteps = root.hasNonNull("maxSteps") ? root.get("maxSteps").asInt() : null;
            org.mockserver.load.LoadScenarioFromRecording.Target target = readRecordingTarget(root);
            org.mockserver.load.LoadProfile profile = null;
            if (root.has("profile") && !root.get("profile").isNull()) {
                org.mockserver.serialization.model.LoadProfileDTO profileDTO =
                    objectMapper.treeToValue(root.get("profile"), org.mockserver.serialization.model.LoadProfileDTO.class);
                profile = profileDTO != null ? profileDTO.buildObject() : null;
            }

            // Optional requestFilter narrows which recorded requests are converted; absent = all recorded.
            org.mockserver.model.RequestDefinition requestFilter = null;
            if (root.has("requestFilter") && !root.get("requestFilter").isNull()) {
                requestFilter = getHttpRequestSerializer().deserialize(objectMapper.writeValueAsString(root.get("requestFilter")));
            }

            // Pull the recorded (RECEIVED_REQUEST) requests from the event log, reusing the same
            // retrieval the recorded-requests control plane uses. retrieveRequests resolves on the event
            // log's disruptor thread, so block on the future to obtain the list synchronously here.
            java.util.concurrent.CompletableFuture<java.util.List<org.mockserver.model.RequestDefinition>> recordedFuture =
                new java.util.concurrent.CompletableFuture<>();
            mockServerLog.retrieveRequests(requestFilter, recordedFuture::complete);
            java.util.List<org.mockserver.model.RequestDefinition> recordedRequests =
                recordedFuture.get(configuration.maxFutureTimeoutInMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);

            org.mockserver.load.LoadScenario scenario;
            try {
                scenario = org.mockserver.load.LoadScenarioFromRecording.generate(name, recordedRequests, mode, maxSteps, target, profile);
            } catch (IllegalArgumentException e) {
                return loadScenarioError(objectMapper, "failed to generate load scenario from recording: " + e.getMessage());
            }

            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            orchestrator.setConfiguration(configuration);
            String error = orchestrator.validate(scenario);
            if (error != null) {
                return loadScenarioError(objectMapper, error);
            }
            String serializedScenario = getLoadScenarioSerializer().serialize(scenario);
            com.fasterxml.jackson.databind.JsonNode definition = objectMapper.readTree(serializedScenario);
            loadScenarioRegistry.load(scenario.getName(), definition);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("generated and loaded load scenario:{}from recorded traffic with {}steps")
                        .setArguments(scenario.getName(), scenario.getSteps().size())
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "loaded");
            result.put("name", scenario.getName());
            result.put("state", loadScenarioStateFor(scenario.getName()).name());
            result.set("scenario", definition);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return loadScenarioError(objectMapper, "invalid generate-from-recording request: " + e.getMessage());
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to process generate-from-recording request: " + e.getMessage());
        }
    }

    /** Reads the optional {@code target} object ({@code host}/{@code port}/{@code scheme}) from a generate-from-recording request. */
    private org.mockserver.load.LoadScenarioFromRecording.Target readRecordingTarget(com.fasterxml.jackson.databind.JsonNode root) {
        com.fasterxml.jackson.databind.JsonNode targetNode = root.get("target");
        if (targetNode == null || targetNode.isNull() || !targetNode.isObject()) {
            return null;
        }
        String host = targetNode.hasNonNull("host") ? targetNode.get("host").asText() : null;
        Integer port = targetNode.hasNonNull("port") ? targetNode.get("port").asInt() : null;
        String scheme = targetNode.hasNonNull("scheme") ? targetNode.get("scheme").asText() : null;
        return new org.mockserver.load.LoadScenarioFromRecording.Target(host, port, scheme);
    }

    /**
     * Handle {@code GET /mockserver/loadScenario}: list ALL registered scenarios, each with its
     * lifecycle {@code state}, {@code startDelayMillis}, full {@code definition} and — when active or
     * recently run — the live status fields.
     */
    private HttpResponse handleLoadScenarioGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode scenarios = result.putArray("scenarios");
            for (String name : loadScenarioRegistry.list()) {
                scenarios.add(loadScenarioNode(objectMapper, name));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to list load scenarios\"}", MediaType.JSON_UTF_8);
        }
    }

    /** Handle {@code GET /mockserver/loadScenario/{name}}: one scenario (definition + state + status), 404 if absent. */
    private HttpResponse handleLoadScenarioGetOne(String name) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            if (!loadScenarioRegistry.contains(name)) {
                return response().withStatusCode(NOT_FOUND.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "no load scenario named '" + name + "'")), MediaType.JSON_UTF_8);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    loadScenarioNode(objectMapper, name)), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to get load scenario: " + e.getMessage());
        }
    }

    /**
     * Handle {@code GET /mockserver/loadScenario/{name}/report}: an end-of-run summary report derived
     * from the run's status snapshot (live snapshot for a running run, retained terminal snapshot for a
     * finished one). Returns JSON by default; {@code ?format=junit} returns a JUnit-XML {@code testsuite}
     * with {@code application/xml}. {@code 404} when the scenario is unknown / never ran. Read-only.
     */
    private HttpResponse handleLoadScenarioReport(String name, String format) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.LoadScenarioOrchestrator.LoadScenarioStatus status =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance().statusFor(name);
            if (status == null) {
                return response().withStatusCode(NOT_FOUND.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "no load scenario run for '" + name + "'")), MediaType.JSON_UTF_8);
            }
            if (format != null && format.equalsIgnoreCase("junit")) {
                return response().withStatusCode(OK.code())
                    .withBody(org.mockserver.load.LoadScenarioReport.toJUnitXml(status), MediaType.create("application", "xml"));
            }
            return response().withStatusCode(OK.code())
                .withBody(org.mockserver.load.LoadScenarioReport.toJson(status), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to build load scenario report: " + e.getMessage());
        }
    }

    /** Handle {@code DELETE /mockserver/loadScenario/{name}}: remove from the registry (stop it if running). */
    private HttpResponse handleLoadScenarioDeleteOne(String name) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            orchestrator.stop(name);
            orchestrator.evictTerminalSeries(name);
            boolean removed = loadScenarioRegistry.delete(name);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", removed ? "deleted" : "absent");
            result.put("name", name);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to delete load scenario: " + e.getMessage());
        }
    }

    /** Handle {@code DELETE /mockserver/loadScenario}: clear the whole registry (stop all running). */
    private HttpResponse handleLoadScenarioDeleteAll() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            orchestrator.stopAll();
            for (String name : loadScenarioRegistry.list()) {
                orchestrator.evictTerminalSeries(name);
            }
            loadScenarioRegistry.clear();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "cleared");
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to clear load scenarios: " + e.getMessage());
        }
    }

    /**
     * Handle {@code PUT /mockserver/loadScenario/start}: trigger one or more registered scenarios to
     * run. Body is {@code {"names":["a","b"]}} or {@code {"name":"a"}}. Requires
     * {@code loadGenerationEnabled} (else 403); 404 if a name is not registered; 400 if it would exceed
     * the concurrent-scenario cap. Each scenario honours its own {@code startDelayMillis}.
     */
    private HttpResponse handleLoadScenarioStart(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            if (!configuration.loadGenerationEnabled()) {
                return response().withStatusCode(FORBIDDEN.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "load generation not enabled (set loadGenerationEnabled=true)")), MediaType.JSON_UTF_8);
            }
            java.util.List<String> names = parseLoadScenarioNames(objectMapper, request.getBodyAsJsonOrXmlString(), false);
            if (names.isEmpty()) {
                return loadScenarioError(objectMapper, "request body must specify 'name' or 'names' of registered scenario(s) to start");
            }
            // Validate all names are registered before starting any (all-or-nothing on the unknown-name
            // check, so a typo does not partially start a batch).
            for (String name : names) {
                if (!loadScenarioRegistry.contains(name)) {
                    return response().withStatusCode(NOT_FOUND.code())
                        .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                            objectMapper.createObjectNode().put("error", "no load scenario named '" + name + "'")), MediaType.JSON_UTF_8);
                }
            }
            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            orchestrator.setConfiguration(configuration);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode started = result.putArray("started");
            for (String name : names) {
                org.mockserver.load.LoadScenario scenario =
                    getLoadScenarioSerializer().deserialize(loadScenarioRegistry.get(name).get().toString());
                String error = orchestrator.start(scenario, null);
                if (error != null) {
                    return loadScenarioError(objectMapper, error);
                }
                com.fasterxml.jackson.databind.node.ObjectNode entry = started.addObject();
                entry.put("name", name);
                entry.put("state", loadScenarioStateFor(name).name());
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("started load scenario(s):{}")
                        .setArguments(names)
                );
            }
            result.put("status", "started");
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return loadScenarioError(objectMapper, "invalid load scenario start request: " + e.getMessage());
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to start load scenario(s): " + e.getMessage());
        }
    }

    /**
     * Handle {@code PUT /mockserver/loadScenario/stop}: stop one or more running scenarios. Body is
     * {@code {"names":[...]}}, {@code {"all":true}}, or an empty body (stop all running). Stopped
     * scenarios stay registered (state {@code STOPPED}) and can be re-started.
     */
    private HttpResponse handleLoadScenarioStop(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            String body = request.getBodyAsJsonOrXmlString();
            java.util.List<String> names = parseLoadScenarioNames(objectMapper, body, true);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode stopped = result.putArray("stopped");
            if (names == null) {
                // 'all' or empty body: stop every running scenario.
                for (String name : loadScenarioRegistry.list()) {
                    if (orchestrator.isActive(name)) {
                        orchestrator.stop(name);
                        com.fasterxml.jackson.databind.node.ObjectNode entry = stopped.addObject();
                        entry.put("name", name);
                        entry.put("state", loadScenarioStateFor(name).name());
                    }
                }
            } else {
                for (String name : names) {
                    orchestrator.stop(name);
                    com.fasterxml.jackson.databind.node.ObjectNode entry = stopped.addObject();
                    entry.put("name", name);
                    entry.put("state", loadScenarioStateFor(name).name());
                }
            }
            result.put("status", "stopped");
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return loadScenarioError(objectMapper, "invalid load scenario stop request: " + e.getMessage());
        } catch (Exception e) {
            return loadScenarioError(objectMapper, "failed to stop load scenario(s): " + e.getMessage());
        }
    }

    /**
     * Parse the {@code names}/{@code name}/{@code all} body of a start/stop request. When
     * {@code allowAll} and the body is empty or {@code {"all":true}}, returns {@code null} to signal
     * "all". Otherwise returns the list of names (possibly empty).
     */
    private java.util.List<String> parseLoadScenarioNames(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String body, boolean allowAll) throws Exception {
        if (isBlank(body)) {
            return allowAll ? null : new java.util.ArrayList<>();
        }
        com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
        if (allowAll && node.has("all") && node.get("all").asBoolean(false)) {
            return null;
        }
        java.util.List<String> names = new java.util.ArrayList<>();
        if (node.has("names") && node.get("names").isArray()) {
            node.get("names").forEach(n -> {
                if (n != null && n.isTextual() && !n.asText().isBlank()) {
                    names.add(n.asText());
                }
            });
        } else if (node.has("name") && node.get("name").isTextual()) {
            names.add(node.get("name").asText());
        }
        if (allowAll && names.isEmpty()) {
            return null;
        }
        return names;
    }

    /**
     * Build the JSON node for a single registered scenario: name, lifecycle state, startDelayMillis,
     * the full definition, and the live/terminal status fields when present.
     */
    private com.fasterxml.jackson.databind.node.ObjectNode loadScenarioNode(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String name) throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
        node.put("name", name);
        node.put("state", loadScenarioStateFor(name).name());
        com.fasterxml.jackson.databind.JsonNode definition = loadScenarioRegistry.get(name)
            .<com.fasterxml.jackson.databind.JsonNode>map(d -> d).orElse(null);
        long startDelayMillis = definition != null && definition.has("startDelayMillis")
            ? definition.get("startDelayMillis").asLong(0) : 0;
        node.put("startDelayMillis", startDelayMillis);
        if (definition != null) {
            node.set("definition", definition);
        }
        org.mockserver.mock.action.http.LoadScenarioOrchestrator.LoadScenarioStatus status =
            org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance().statusFor(name);
        if (status != null) {
            node.put("elapsedMillis", status.elapsedMillis);
            node.put("currentVus", status.currentVus);
            if (status.stageIndex >= 0) {
                node.put("stageIndex", status.stageIndex);
            }
            if (status.stageType != null) {
                node.put("stageType", status.stageType);
                node.put("currentTarget", status.currentTarget);
            }
            node.put("requestsSent", status.requestsSent);
            node.put("succeeded", status.succeeded);
            node.put("failed", status.failed);
            node.put("p50Millis", status.p50Millis);
            node.put("p95Millis", status.p95Millis);
            node.put("p99Millis", status.p99Millis);
            node.put("p999Millis", status.p999Millis);
            node.put("droppedIterations", status.droppedIterations);
            if (status.verdict != null) {
                node.put("verdict", status.verdict);
            }
            if (status.abortedByThreshold) {
                node.put("abortedByThreshold", true);
            }
            if (status.thresholdResults != null && !status.thresholdResults.isEmpty()) {
                com.fasterxml.jackson.databind.node.ArrayNode thresholdResultsNode = node.putArray("thresholdResults");
                for (org.mockserver.mock.action.http.LoadScenarioOrchestrator.ThresholdResult result : status.thresholdResults) {
                    com.fasterxml.jackson.databind.node.ObjectNode resultNode = thresholdResultsNode.addObject();
                    if (result.metric != null) {
                        resultNode.put("metric", result.metric);
                    }
                    if (result.comparator != null) {
                        resultNode.put("comparator", result.comparator);
                    }
                    resultNode.put("threshold", result.threshold);
                    resultNode.put("observed", result.observed);
                    resultNode.put("satisfied", result.satisfied);
                }
            }
            if (status.checkResults != null && !status.checkResults.isEmpty()) {
                com.fasterxml.jackson.databind.node.ArrayNode checkResultsNode = node.putArray("checkResults");
                for (org.mockserver.mock.action.http.LoadScenarioOrchestrator.CheckResult result : status.checkResults) {
                    com.fasterxml.jackson.databind.node.ObjectNode resultNode = checkResultsNode.addObject();
                    if (result.step != null) {
                        resultNode.put("step", result.step);
                    }
                    if (result.source != null) {
                        resultNode.put("source", result.source);
                    }
                    if (result.detail != null && !result.detail.isEmpty()) {
                        resultNode.put("detail", result.detail);
                    }
                    if (result.comparator != null) {
                        resultNode.put("comparator", result.comparator);
                    }
                    if (result.value != null) {
                        resultNode.put("value", result.value);
                    }
                    resultNode.put("passed", result.passed);
                    resultNode.put("failed", result.failed);
                }
            }
            node.put("runId", status.runId);
            node.put("startedAt", status.startedAtEpochMillis);
            if (status.endedAtEpochMillis != null) {
                node.put("endedAt", status.endedAtEpochMillis);
            }
            if (status.labels != null && !status.labels.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode labelsNode = node.putObject("labels");
                status.labels.forEach(labelsNode::put);
            }
        }
        return node;
    }

    /**
     * Resolve the lifecycle state of a registered scenario: the live run's state when active or its
     * retained terminal state when recently run, else {@code LOADED} (registered, idle).
     */
    private org.mockserver.load.LoadScenarioState loadScenarioStateFor(String name) {
        org.mockserver.mock.action.http.LoadScenarioOrchestrator.LoadScenarioStatus status =
            org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance().statusFor(name);
        return status != null ? status.state : org.mockserver.load.LoadScenarioState.LOADED;
    }

    /**
     * If {@code request} is the given {@code method} on a path {@code /mockserver/loadScenario/{name}}
     * (with or without the prefix) where {name} is a single non-reserved segment, returns the decoded
     * {name}; otherwise {@code null}. {@code start} and {@code stop} are reserved sub-paths and never
     * matched as a name.
     */
    private String loadScenarioName(HttpRequest request, String method) {
        if (!request.getMethod().getValue().equals(method)) {
            return null;
        }
        String prefix = "/loadScenario/";
        String path = request.getPath().getValue();
        String rest = null;
        if (path.startsWith(PATH_PREFIX + prefix)) {
            rest = path.substring((PATH_PREFIX + prefix).length());
        } else if (path.startsWith(prefix)) {
            rest = path.substring(prefix.length());
        }
        if (rest == null || rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(rest, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = rest;
        }
        if ("start".equals(decoded) || "stop".equals(decoded)) {
            return null;
        }
        return decoded;
    }

    /**
     * If {@code request} is a {@code GET} on {@code /mockserver/loadScenario/{name}/report} (with or
     * without the prefix) where {name} is a single non-reserved segment, returns the decoded {name};
     * otherwise {@code null}. {@code start}/{@code stop} are reserved and never matched as a name.
     */
    private String loadScenarioReportName(HttpRequest request) {
        if (!request.getMethod().getValue().equals("GET")) {
            return null;
        }
        String prefix = "/loadScenario/";
        String suffix = "/report";
        String path = request.getPath().getValue();
        String rest = null;
        if (path.startsWith(PATH_PREFIX + prefix)) {
            rest = path.substring((PATH_PREFIX + prefix).length());
        } else if (path.startsWith(prefix)) {
            rest = path.substring(prefix.length());
        }
        if (rest == null || !rest.endsWith(suffix)) {
            return null;
        }
        String namePart = rest.substring(0, rest.length() - suffix.length());
        if (namePart.isEmpty() || namePart.contains("/")) {
            return null;
        }
        String decoded;
        try {
            decoded = java.net.URLDecoder.decode(namePart, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            decoded = namePart;
        }
        if ("start".equals(decoded) || "stop".equals(decoded)) {
            return null;
        }
        return decoded;
    }

    /**
     * Preload load scenario definitions from {@code loadScenarioInitializationJsonPath} into the
     * registry (LOADED state) at startup. Mirrors the expectation initialization-from-file mechanism.
     * Fail-soft: a malformed file logs a WARN and is skipped rather than aborting startup.
     */
    private void preloadLoadScenarios() {
        String path = configuration.loadScenarioInitializationJsonPath();
        if (isBlank(path)) {
            return;
        }
        try {
            String json = org.mockserver.file.FileReader.readFileFromClassPathOrPath(path);
            if (isBlank(json)) {
                return;
            }
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            com.fasterxml.jackson.databind.JsonNode root = objectMapper.readTree(json);
            java.util.List<com.fasterxml.jackson.databind.JsonNode> definitions = new java.util.ArrayList<>();
            if (root.isArray()) {
                root.forEach(definitions::add);
            } else if (root.isObject()) {
                definitions.add(root);
            }
            org.mockserver.serialization.LoadScenarioSerializer serializer = getLoadScenarioSerializer();
            org.mockserver.mock.action.http.LoadScenarioOrchestrator orchestrator =
                org.mockserver.mock.action.http.LoadScenarioOrchestrator.getInstance();
            orchestrator.setConfiguration(configuration);
            int loaded = 0;
            for (com.fasterxml.jackson.databind.JsonNode def : definitions) {
                try {
                    org.mockserver.load.LoadScenario scenario = serializer.deserialize(def.toString());
                    String error = orchestrator.validate(scenario);
                    if (error != null) {
                        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                            mockServerLogger.logEvent(new LogEntry()
                                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION).setLogLevel(Level.WARN)
                                .setMessageFormat("skipping invalid preloaded load scenario '" + scenario.getName() + "': " + error));
                        }
                        continue;
                    }
                    com.fasterxml.jackson.databind.JsonNode normalised = objectMapper.readTree(serializer.serialize(scenario));
                    loadScenarioRegistry.load(scenario.getName(), normalised);
                    loaded++;
                } catch (Exception inner) {
                    if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                        mockServerLogger.logEvent(new LogEntry()
                            .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION).setLogLevel(Level.WARN)
                            .setMessageFormat("exception while preloading a load scenario, skipping it")
                            .setThrowable(inner));
                    }
                }
            }
            if (loaded > 0 && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION).setLogLevel(Level.INFO)
                    .setMessageFormat("preloaded " + loaded + " load scenario(s) from:{}")
                    .setArguments(path));
            }
        } catch (Throwable throwable) {
            if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
                mockServerLogger.logEvent(new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION).setLogLevel(Level.WARN)
                    .setMessageFormat("exception while preloading load scenarios, ignoring file:{}")
                    .setArguments(path).setThrowable(throwable));
            }
        }
    }

    private HttpResponse loadScenarioError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process load scenario request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- ADV3: saved chaos profile library endpoints ---

    /**
     * If {@code request} is the given {@code method} on a path that is
     * {@code prefix}{name} (with or without the {@code /mockserver} prefix),
     * returns the URL-decoded {name} segment; otherwise returns {@code null}.
     * Returns {@code null} for an empty or multi-segment trailing path so that a
     * bare {@code .../profiles} (no name) does not match a {name} route.
     */
    private String chaosProfileName(HttpRequest request, String method, String prefix) {
        if (!request.getMethod().getValue().equals(method)) {
            return null;
        }
        String path = request.getPath().getValue();
        String rest = null;
        if (path.startsWith(PATH_PREFIX + prefix)) {
            rest = path.substring((PATH_PREFIX + prefix).length());
        } else if (path.startsWith(prefix)) {
            rest = path.substring(prefix.length());
        }
        if (rest == null || rest.isEmpty() || rest.contains("/")) {
            return null;
        }
        try {
            return java.net.URLDecoder.decode(rest, java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            return rest;
        }
    }

    private HttpResponse handleChaosProfileSave(HttpRequest request, String name) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return chaosExperimentError(objectMapper, "request body is required with a chaos profile (experiment) definition");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            // Validate it parses as an experiment definition before saving so a malformed
            // profile fails at save time rather than at apply time.
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(node);
            chaosProfileLibrary.save(name, node);
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("saved chaos profile:{}")
                        .setArguments(name)
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "saved");
            result.put("name", name);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return chaosExperimentError(objectMapper, "invalid chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return chaosExperimentError(objectMapper, "failed to save chaos profile: " + e.getMessage());
        }
    }

    private HttpResponse handleChaosProfileList() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode names = result.putArray("profiles");
            for (String name : chaosProfileLibrary.list()) {
                names.add(name);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return chaosExperimentError(objectMapper, "failed to list chaos profiles: " + e.getMessage());
        }
    }

    private HttpResponse handleChaosProfileGet(String name) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            java.util.Optional<com.fasterxml.jackson.databind.node.ObjectNode> profile = chaosProfileLibrary.get(name);
            if (profile.isEmpty()) {
                return response().withStatusCode(NOT_FOUND.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "no chaos profile named '" + name + "'")), MediaType.JSON_UTF_8);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(profile.get()), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return chaosExperimentError(objectMapper, "failed to get chaos profile: " + e.getMessage());
        }
    }

    private HttpResponse handleChaosProfileDelete(String name) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            boolean removed = chaosProfileLibrary.delete(name);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", removed ? "deleted" : "absent");
            result.put("name", name);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return chaosExperimentError(objectMapper, "failed to delete chaos profile: " + e.getMessage());
        }
    }

    private HttpResponse handleChaosProfileApply(HttpRequest request, String name) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            java.util.Optional<com.fasterxml.jackson.databind.node.ObjectNode> profile = chaosProfileLibrary.get(name);
            if (profile.isEmpty()) {
                return response().withStatusCode(NOT_FOUND.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                        objectMapper.createObjectNode().put("error", "no chaos profile named '" + name + "'")), MediaType.JSON_UTF_8);
            }
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentDefinition definition =
                org.mockserver.mock.action.http.ChaosExperimentOrchestrator.ExperimentDefinition.fromJson(profile.get());
            org.mockserver.mock.action.http.ChaosExperimentOrchestrator orchestrator =
                org.mockserver.mock.action.http.ChaosExperimentOrchestrator.getInstance();
            String error = orchestrator.start(definition);
            if (error != null) {
                return chaosExperimentError(objectMapper, error);
            }
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("applied saved chaos profile:{}")
                        .setArguments(name)
                );
            }
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "started");
            result.put("name", definition.name);
            result.put("stages", definition.stages.size());
            result.put("loop", definition.loop);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return chaosExperimentError(objectMapper, "invalid saved chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return chaosExperimentError(objectMapper, "failed to apply chaos profile: " + e.getMessage());
        }
    }

    // --- TCP Chaos endpoint helpers ---

    private HttpResponse handleTcpChaosPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return tcpChaosError(objectMapper, "request body is required with a 'host' field (and a 'chaos' object), or 'clear':true to clear all");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            boolean clearAll = node.path("clear").asBoolean(false);
            String host = node.path("host").asText(null);
            org.mockserver.mock.action.http.TcpChaosRegistry registry = org.mockserver.mock.action.http.TcpChaosRegistry.getInstance();
            if (clearAll && !isBlank(host)) {
                return tcpChaosError(objectMapper, "cannot specify both 'clear' and 'host'");
            }
            if (clearAll) {
                registry.reset();
                logTcpChaos(request, "cleared all TCP-layer chaos", null);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "cleared");
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            if (isBlank(host)) {
                return tcpChaosError(objectMapper, "'host' field is required");
            }
            if (node.path("remove").asBoolean(false) || !node.hasNonNull("chaos")) {
                registry.remove(host);
                logTcpChaos(request, "removed TCP-layer chaos for host:{}", host);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "removed");
                result.put("host", host);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            long ttlMillis = 0L;
            if (node.hasNonNull("ttlMillis")) {
                ttlMillis = node.path("ttlMillis").asLong(0L);
                if (ttlMillis < 1) {
                    return tcpChaosError(objectMapper, "'ttlMillis' must be >= 1 when supplied");
                }
            }
            org.mockserver.serialization.model.TcpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.TcpChaosProfileDTO.class);
            org.mockserver.model.TcpChaosProfile profile = dto.buildObject();
            registry.put(host, profile, ttlMillis);
            logTcpChaos(request, ttlMillis > 0
                ? "registered TCP-layer chaos (ttl " + ttlMillis + "ms) for host:{}"
                : "registered TCP-layer chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("host", host);
            if (ttlMillis > 0) {
                result.put("ttlMillis", ttlMillis);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return tcpChaosError(objectMapper, "invalid TCP chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return tcpChaosError(objectMapper, "failed to process TCP chaos request: " + e.getMessage());
        }
    }

    private HttpResponse handleTcpChaosPatch(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return tcpChaosError(objectMapper, "request body is required with 'host' and 'chaos' fields");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String host = node.path("host").asText(null);
            if (isBlank(host)) {
                return tcpChaosError(objectMapper, "'host' field is required");
            }
            if (!node.hasNonNull("chaos")) {
                return tcpChaosError(objectMapper, "'chaos' field is required with at least one field to patch");
            }
            org.mockserver.serialization.model.TcpChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.TcpChaosProfileDTO.class);
            org.mockserver.model.TcpChaosProfile partial = dto.buildObject();
            org.mockserver.mock.action.http.TcpChaosRegistry registry = org.mockserver.mock.action.http.TcpChaosRegistry.getInstance();
            org.mockserver.model.TcpChaosProfile updated = registry.patch(host, partial);
            logTcpChaos(request, "patched TCP-layer chaos for host:{}", host);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "patched");
            result.put("host", host);
            if (updated != null) {
                result.set("chaos", objectMapper.valueToTree(new org.mockserver.serialization.model.TcpChaosProfileDTO(updated)));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return tcpChaosError(objectMapper, "invalid TCP chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return tcpChaosError(objectMapper, "failed to process TCP chaos patch: " + e.getMessage());
        }
    }

    private HttpResponse handleTcpChaosGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.TcpChaosRegistry registry = org.mockserver.mock.action.http.TcpChaosRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode hosts = result.putObject("hosts");
            registry.entries().forEach((host, profile) ->
                hosts.set(host, objectMapper.valueToTree(new org.mockserver.serialization.model.TcpChaosProfileDTO(profile))));
            java.util.Map<String, Long> ttlRemaining = registry.ttlRemainingMillis();
            if (!ttlRemaining.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode ttlNode = result.putObject("ttlRemainingMillis");
                ttlRemaining.forEach((h, ms) -> ttlNode.put(h, ms.longValue()));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get TCP chaos\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logTcpChaos(HttpRequest request, String messageFormat, String host) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            LogEntry entry = new LogEntry()
                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(messageFormat);
            if (host != null) {
                entry.setArguments(host);
            }
            mockServerLogger.logEvent(entry);
        }
    }

    private HttpResponse tcpChaosError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process TCP chaos request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- Preemption / SIGTERM simulation endpoint helpers ---

    private HttpResponse handlePreemptionPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.model.PreemptionRequest preemptionRequest;
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                // an empty body starts a preemption with all defaults (drain = stopDrainMillis, mode = both)
                preemptionRequest = org.mockserver.model.PreemptionRequest.preemptionRequest();
            } else {
                preemptionRequest = objectMapper.readValue(body, org.mockserver.model.PreemptionRequest.class);
                if (preemptionRequest == null) {
                    preemptionRequest = org.mockserver.model.PreemptionRequest.preemptionRequest();
                }
            }
            org.mockserver.model.PreemptionRequest effective =
                org.mockserver.mock.action.http.PreemptionSimulator.getInstance().start(configuration, preemptionRequest);
            // No start-time channel orchestration: the cordon state is now authoritative, and an HTTP/2
            // GOAWAY (when the mode includes it) is emitted lazily by HttpRequestHandler on the next
            // request that hits a cordoned connection, so no per-channel registry is required.
            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("started preemption simulation (mode " + effective.getMode()
                        + ", drain " + effective.getDrainMillis() + "ms"
                        + (effective.getTtlMillis() != null && effective.getTtlMillis() > 0 ? ", ttl " + effective.getTtlMillis() + "ms" : "") + ")"));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(preemptionStatusNode(objectMapper)), MediaType.JSON_UTF_8);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"invalid preemption request: " + sanitizeJsonError(e.getMessage()) + "\"}", MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process preemption request\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handlePreemptionGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(preemptionStatusNode(objectMapper)), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get preemption status\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handlePreemptionDelete() {
        // Idempotent uncordon: a 200 whether or not a simulation was active.
        org.mockserver.mock.action.http.PreemptionSimulator.getInstance().uncordon();
        return response().withStatusCode(OK.code())
            .withBody("{\"state\":\"inactive\"}", MediaType.JSON_UTF_8);
    }

    private com.fasterxml.jackson.databind.node.ObjectNode preemptionStatusNode(com.fasterxml.jackson.databind.ObjectMapper objectMapper) {
        org.mockserver.mock.action.http.PreemptionSimulator simulator = org.mockserver.mock.action.http.PreemptionSimulator.getInstance();
        com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
        result.put("state", simulator.state());
        result.put("inFlight", simulator.inFlight());
        result.put("drainRemainingMillis", simulator.drainRemainingMillis());
        org.mockserver.model.PreemptionRequest.Mode mode = simulator.getMode();
        if (mode != null) {
            result.put("mode", mode.name());
        }
        return result;
    }

    private static String sanitizeJsonError(String message) {
        if (message == null) {
            return "unparseable JSON";
        }
        return message.replace("\"", "'").replace("\n", " ").replace("\r", " ");
    }

    // --- gRPC Chaos endpoint helpers ---

    private HttpResponse handleGrpcChaosPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return grpcChaosError(objectMapper, "request body is required with a 'service' field (and a 'chaos' object), or 'clear':true to clear all");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            boolean clearAll = node.path("clear").asBoolean(false);
            String service = node.has("service") ? node.path("service").asText("") : null;
            org.mockserver.mock.action.http.GrpcChaosRegistry registry = org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance();
            if (clearAll && service != null) {
                return grpcChaosError(objectMapper, "cannot specify both 'clear' and 'service'");
            }
            if (clearAll) {
                registry.reset();
                logGrpcChaos(request, "cleared all gRPC chaos", null);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "cleared");
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            if (service == null) {
                return grpcChaosError(objectMapper, "'service' field is required");
            }
            if (node.path("remove").asBoolean(false) || !node.hasNonNull("chaos")) {
                registry.remove(service);
                logGrpcChaos(request, "removed gRPC chaos for service:{}", service);
                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("status", "removed");
                result.put("service", service);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
            long ttlMillis = 0L;
            if (node.hasNonNull("ttlMillis")) {
                ttlMillis = node.path("ttlMillis").asLong(0L);
                if (ttlMillis < 1) {
                    return grpcChaosError(objectMapper, "'ttlMillis' must be >= 1 when supplied");
                }
            }
            org.mockserver.serialization.model.GrpcChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.GrpcChaosProfileDTO.class);
            org.mockserver.model.GrpcChaosProfile profile = dto.buildObject();
            registry.put(service, profile, ttlMillis);
            logGrpcChaos(request, ttlMillis > 0
                ? "registered gRPC chaos (ttl " + ttlMillis + "ms) for service:{}"
                : "registered gRPC chaos for service:{}", service);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("service", service);
            if (ttlMillis > 0) {
                result.put("ttlMillis", ttlMillis);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return grpcChaosError(objectMapper, "invalid gRPC chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return grpcChaosError(objectMapper, "failed to process gRPC chaos request: " + e.getMessage());
        }
    }

    private HttpResponse handleGrpcChaosPatch(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return grpcChaosError(objectMapper, "request body is required with 'service' and 'chaos' fields");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String service = node.has("service") ? node.path("service").asText("") : null;
            if (service == null) {
                return grpcChaosError(objectMapper, "'service' field is required");
            }
            if (!node.hasNonNull("chaos")) {
                return grpcChaosError(objectMapper, "'chaos' field is required with at least one field to patch");
            }
            org.mockserver.serialization.model.GrpcChaosProfileDTO dto =
                objectMapper.treeToValue(node.get("chaos"), org.mockserver.serialization.model.GrpcChaosProfileDTO.class);
            org.mockserver.model.GrpcChaosProfile partial = dto.buildObject();
            org.mockserver.mock.action.http.GrpcChaosRegistry registry = org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance();
            org.mockserver.model.GrpcChaosProfile updated = registry.patch(service, partial);
            logGrpcChaos(request, "patched gRPC chaos for service:{}", service);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "patched");
            result.put("service", service);
            if (updated != null) {
                result.set("chaos", objectMapper.valueToTree(new org.mockserver.serialization.model.GrpcChaosProfileDTO(updated)));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return grpcChaosError(objectMapper, "invalid gRPC chaos profile: " + e.getMessage());
        } catch (Exception e) {
            return grpcChaosError(objectMapper, "failed to process gRPC chaos patch: " + e.getMessage());
        }
    }

    private HttpResponse handleGrpcChaosGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.action.http.GrpcChaosRegistry registry = org.mockserver.mock.action.http.GrpcChaosRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ObjectNode services = result.putObject("services");
            registry.entries().forEach((service, profile) ->
                services.set(service, objectMapper.valueToTree(new org.mockserver.serialization.model.GrpcChaosProfileDTO(profile))));
            java.util.Map<String, Long> ttlRemaining = registry.ttlRemainingMillis();
            if (!ttlRemaining.isEmpty()) {
                com.fasterxml.jackson.databind.node.ObjectNode ttlNode = result.putObject("ttlRemainingMillis");
                ttlRemaining.forEach((s, ms) -> ttlNode.put(s, ms.longValue()));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get gRPC chaos\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logGrpcChaos(HttpRequest request, String messageFormat, String service) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            LogEntry entry = new LogEntry()
                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                .setLogLevel(Level.INFO)
                .setHttpRequest(request)
                .setMessageFormat(messageFormat);
            if (service != null) {
                entry.setArguments(service);
            }
            mockServerLogger.logEvent(entry);
        }
    }

    private HttpResponse grpcChaosError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process gRPC chaos request\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- Scenario endpoint helpers ---

    /**
     * Extracts the scenario name from a request path.
     * Handles both {@code /mockserver/scenario/{name}} and {@code /scenario/{name}} prefixes.
     * Returns the full remaining path after the prefix (which may include "/trigger" suffix).
     */
    private String extractScenarioPath(HttpRequest request) {
        String path = request.getPath().getValue();
        String prefixFull = PATH_PREFIX + "/scenario/";
        String prefixShort = "/scenario/";
        if (path.startsWith(prefixFull)) {
            return path.substring(prefixFull.length());
        } else if (path.startsWith(prefixShort)) {
            return path.substring(prefixShort.length());
        }
        return null;
    }

    /**
     * Handles PUT /mockserver/scenario/{name} and PUT /mockserver/scenario/{name}/trigger.
     * <p>
     * PUT /mockserver/scenario/{name}:
     *   Body: {"state": "Running"} — set state immediately
     *   Body: {"state": "Running", "transitionAfterMs": 5000, "nextState": "Finished"} — set state and schedule timed transition
     * <p>
     * PUT /mockserver/scenario/{name}/trigger:
     *   Body: {"newState": "Step3"} — set state to newState immediately
     */
    private HttpResponse handleScenarioPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String scenarioPath = extractScenarioPath(request);
            if (isBlank(scenarioPath)) {
                return scenarioError(objectMapper, "scenario name is required in the path");
            }

            boolean isTrigger = scenarioPath.endsWith("/trigger");
            String scenarioName = isTrigger ? scenarioPath.substring(0, scenarioPath.length() - "/trigger".length()) : scenarioPath;

            if (isBlank(scenarioName)) {
                return scenarioError(objectMapper, "scenario name is required in the path");
            }

            ScenarioManager scenarioManager = requestMatchers.getScenarioManager();
            String body = request.getBodyAsJsonOrXmlString();

            if (isTrigger) {
                // PUT /mockserver/scenario/{name}/trigger — external trigger to set state
                if (isBlank(body)) {
                    return scenarioError(objectMapper, "request body is required with 'newState' field");
                }
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
                String newState = node.path("newState").asText(null);
                if (isBlank(newState)) {
                    return scenarioError(objectMapper, "'newState' field is required");
                }
                scenarioManager.setState(scenarioName, newState);
                logScenario(request, "triggered scenario state transition for scenario:{} to state:{}", scenarioName, newState);

                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("scenarioName", scenarioName);
                result.put("currentState", newState);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            } else {
                // PUT /mockserver/scenario/{name} — set state, optionally schedule transition
                if (isBlank(body)) {
                    return scenarioError(objectMapper, "request body is required with 'state' field");
                }
                com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
                String state = node.path("state").asText(null);
                if (isBlank(state)) {
                    return scenarioError(objectMapper, "'state' field is required");
                }
                scenarioManager.setState(scenarioName, state);
                logScenario(request, "set scenario state for scenario:{} to state:{}", scenarioName, state);

                // optional timed transition
                Long transitionAfterMs = node.hasNonNull("transitionAfterMs") ? node.get("transitionAfterMs").asLong() : null;
                String nextState = node.path("nextState").asText(null);

                if (transitionAfterMs != null && transitionAfterMs > 0 && isNotBlank(nextState)) {
                    TimedScenarioTransition transition = new TimedScenarioTransition()
                        .withScenarioName(scenarioName)
                        .withCurrentState(state)
                        .withNextState(nextState)
                        .withTransitionAfterMs(transitionAfterMs);
                    scenarioManager.scheduleTransition(transition, scheduler);
                    logScenario(request, "scheduled timed transition for scenario:{} from state:{} to state:{} after {}ms",
                        scenarioName, state, nextState, String.valueOf(transitionAfterMs));
                }

                com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
                result.put("scenarioName", scenarioName);
                result.put("currentState", state);
                if (transitionAfterMs != null && transitionAfterMs > 0 && isNotBlank(nextState)) {
                    result.put("nextState", nextState);
                    result.put("transitionAfterMs", transitionAfterMs);
                }
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
            }
        } catch (Exception e) {
            return scenarioError(objectMapper, "failed to process scenario request: " + e.getMessage());
        }
    }

    /**
     * Handles GET /mockserver/scenario/{name} — returns the current state of a scenario.
     * When no name is supplied (GET /mockserver/scenario), returns the list of all known
     * scenarios and their current states (see {@link #handleScenarioList()}).
     */
    private HttpResponse handleScenarioGet(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String scenarioPath = extractScenarioPath(request);
            if (isBlank(scenarioPath)) {
                return handleScenarioList();
            }

            ScenarioManager scenarioManager = requestMatchers.getScenarioManager();
            String currentState = scenarioManager.getState(scenarioPath);

            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("scenarioName", scenarioPath);
            result.put("currentState", currentState);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return scenarioError(objectMapper, "failed to get scenario state: " + e.getMessage());
        }
    }

    /**
     * Handles GET /mockserver/scenario — returns every known scenario and its current state
     * as {@code { "scenarios": [ { "scenarioName", "currentState" }, ... ] }} so the dashboard
     * can list existing scenarios without the caller having to know their names in advance.
     */
    private HttpResponse handleScenarioList() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            ScenarioManager scenarioManager = requestMatchers.getScenarioManager();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode scenarios = result.putArray("scenarios");
            for (java.util.Map.Entry<String, String> entry : scenarioManager.getAllStates().entrySet()) {
                com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
                node.put("scenarioName", entry.getKey());
                node.put("currentState", entry.getValue());
                scenarios.add(node);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return scenarioError(objectMapper, "failed to list scenarios: " + e.getMessage());
        }
    }

    // --- Cassette registry endpoint helpers ---

    /**
     * Handles GET /mockserver/cassettes — lists every cassette tracked server-side as
     * {@code { "cassettes": [ { "path", "filename", "expectationCount", "origin", "lastUsed" } ] }},
     * most-recently-used first. The dashboard merges this with its per-browser list so cassettes
     * recorded/loaded anywhere (or seeded by automation) are visible across reloads and browsers.
     */
    private HttpResponse handleCassettesGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode cassettes = result.putArray("cassettes");
            for (CassetteRegistry.Entry entry : CassetteRegistry.getInstance().list()) {
                com.fasterxml.jackson.databind.node.ObjectNode node = objectMapper.createObjectNode();
                node.put("path", entry.path);
                node.put("filename", entry.filename);
                node.put("expectationCount", entry.expectationCount);
                node.put("origin", entry.origin);
                node.put("lastUsed", entry.lastUsedEpochMillis);
                cassettes.add(node);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return cassetteError(objectMapper, "failed to list cassettes: " + e.getMessage());
        }
    }

    /**
     * Handles PUT /mockserver/cassettes — registers (or updates) a cassette from a JSON body
     * {@code { "path", "filename"?, "expectationCount"?, "origin"? }}. {@code path} is required.
     */
    private HttpResponse handleCassettesPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return cassetteError(objectMapper, "request body is required with a 'path' field");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String path = node.path("path").asText(null);
            if (isBlank(path)) {
                return cassetteError(objectMapper, "'path' field is required");
            }
            String filename = node.path("filename").asText(null);
            int expectationCount = node.path("expectationCount").asInt(-1);
            String origin = node.path("origin").asText(null);
            CassetteRegistry.Entry entry = CassetteRegistry.getInstance().register(path, filename, expectationCount, origin);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("path", entry.path);
            result.put("filename", entry.filename);
            result.put("expectationCount", entry.expectationCount);
            result.put("origin", entry.origin);
            result.put("lastUsed", entry.lastUsedEpochMillis);
            return response().withStatusCode(CREATED.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return cassetteError(objectMapper, "failed to register cassette: " + e.getMessage());
        }
    }

    /**
     * Handles DELETE /mockserver/cassettes — removes a cassette by path, supplied either as the
     * {@code path} query parameter or a JSON body {@code { "path": "..." }}.
     */
    private HttpResponse handleCassettesDelete(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String path = request.getFirstQueryStringParameter("path");
            if (isBlank(path)) {
                String body = request.getBodyAsJsonOrXmlString();
                if (!isBlank(body)) {
                    path = objectMapper.readTree(body).path("path").asText(null);
                }
            }
            if (isBlank(path)) {
                return cassetteError(objectMapper, "'path' is required (query parameter or body field)");
            }
            boolean removed = CassetteRegistry.getInstance().remove(path);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("removed", removed);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return cassetteError(objectMapper, "failed to remove cassette: " + e.getMessage());
        }
    }

    private HttpResponse cassetteError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writeValueAsString(objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process cassette request\"}", MediaType.JSON_UTF_8);
        }
    }

    private void logScenario(HttpRequest request, String messageFormat, String... args) {
        if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat(messageFormat)
                    .setArguments((Object[]) args)
            );
        }
    }

    private HttpResponse scenarioError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to process scenario request\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleGenerateExpectation(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return generateExpectationError(objectMapper, "request body is required with 'request' field");
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            if (!node.hasNonNull("request")) {
                return generateExpectationError(objectMapper, "'request' field is required (the unmatched HttpRequest)");
            }
            boolean preview = node.path("preview").asBoolean(true);
            int limit = node.path("limit").asInt(1);
            if (limit < 1) {
                limit = 1;
            }
            if (limit > 5) {
                limit = 5;
            }

            // Deserialize the unmatched request
            HttpRequest unmatchedRequest;
            try {
                RequestDefinition rd = getRequestDefinitionSerializer().deserialize(
                    objectMapper.writeValueAsString(node.get("request")));
                if (rd instanceof HttpRequest) {
                    unmatchedRequest = (HttpRequest) rd;
                } else {
                    unmatchedRequest = request().withPath("/");
                }
            } catch (Exception deserializeEx) {
                return generateExpectationError(objectMapper, "failed to parse 'request' field: " + deserializeEx.getMessage());
            }

            // Retrieve context: up to 10 active expectations
            List<Expectation> contextExpectations = requestMatchers.retrieveActiveExpectations(null);
            if (contextExpectations.size() > 10) {
                contextExpectations = contextExpectations.subList(0, 10);
            }

            // Check if LLM is available
            org.mockserver.llm.client.LlmCompletionService service = this.llmCompletionService;
            org.mockserver.llm.client.LlmBackend backend = this.llmBackend;
            if (service == null || backend == null) {
                // Fallback: generate a simple template-based stub without LLM
                Expectation suggestion = generateSimpleStub(unmatchedRequest);
                List<Expectation> suggestions = Collections.singletonList(suggestion);
                if (!preview) {
                    requestMatchers.add(suggestion, Cause.API);
                }
                return buildGenerateExpectationResponse(objectMapper, suggestions, 0.5, preview,
                    "Generated from request pattern (no LLM backend configured)");
            }

            // Build prompt and call LLM
            org.mockserver.llm.StubGenerationPromptBuilder promptBuilder = new org.mockserver.llm.StubGenerationPromptBuilder();
            String prompt = promptBuilder.build(unmatchedRequest, contextExpectations);

            ParsedConversation conversation = ParsedConversation.of(Collections.singletonList(
                new ParsedMessage(ParsedMessage.Role.USER, prompt, null, null)));
            java.util.Optional<org.mockserver.model.Completion> completionOpt = service.complete(backend, conversation);

            if (!completionOpt.isPresent() || isBlank(completionOpt.get().getText())) {
                // LLM call failed or returned empty — fall back to template
                Expectation suggestion = generateSimpleStub(unmatchedRequest);
                List<Expectation> suggestions = Collections.singletonList(suggestion);
                if (!preview) {
                    requestMatchers.add(suggestion, Cause.API);
                }
                return buildGenerateExpectationResponse(objectMapper, suggestions, 0.3, preview,
                    "LLM call returned no result, falling back to template");
            }

            String llmResponse = completionOpt.get().getText();

            // Parse LLM response as Expectation JSON
            List<Expectation> suggestions = new ArrayList<>();
            try {
                String jsonStr = extractJsonFromLlmResponse(llmResponse);
                Expectation[] parsed = getExpectationSerializer().deserializeArray(jsonStr, true);
                for (int i = 0; i < Math.min(parsed.length, limit); i++) {
                    suggestions.add(parsed[i]);
                }
            } catch (Exception parseEx) {
                // fallback to simple stub if LLM response unparseable
                suggestions.add(generateSimpleStub(unmatchedRequest));
            }

            if (!preview && !suggestions.isEmpty()) {
                for (Expectation suggestion : suggestions) {
                    requestMatchers.add(suggestion, Cause.API);
                }
            }

            if (mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request)
                        .setMessageFormat("generated {} expectation suggestion(s) via LLM for path:{}")
                        .setArguments(suggestions.size(),
                            unmatchedRequest.getPath() != null ? unmatchedRequest.getPath().getValue() : "/")
                );
            }

            return buildGenerateExpectationResponse(objectMapper, suggestions, suggestions.isEmpty() ? 0.0 : 0.75, preview, null);
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("failed to generate expectation:{}").setArguments(e.getMessage())
                    .setThrowable(e)
            );
            return generateExpectationError(objectMapper, "failed to generate expectation");
        }
    }

    private HttpResponse buildGenerateExpectationResponse(com.fasterxml.jackson.databind.ObjectMapper objectMapper,
                                                          List<Expectation> suggestions, double confidence,
                                                          boolean preview, String explanation) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            com.fasterxml.jackson.databind.node.ArrayNode suggestionsArray = result.putArray("suggestions");
            for (Expectation suggestion : suggestions) {
                suggestionsArray.add(objectMapper.readTree(getExpectationSerializer().serialize(suggestion)));
            }
            result.put("confidence", confidence);
            result.put("preview", preview);
            if (explanation != null) {
                result.put("explanation", explanation);
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to serialize response\"}", MediaType.JSON_UTF_8);
        }
    }

    private Expectation generateSimpleStub(HttpRequest unmatchedRequest) {
        String method = unmatchedRequest.getMethod() != null ? unmatchedRequest.getMethod().getValue() : "GET";
        int statusCode = "POST".equalsIgnoreCase(method) ? 201 : "DELETE".equalsIgnoreCase(method) ? 204 : 200;
        return new Expectation(
            HttpRequest.request()
                .withMethod(method)
                .withPath(unmatchedRequest.getPath() != null ? unmatchedRequest.getPath().getValue() : "/")
        ).thenRespond(
            HttpResponse.response()
                .withStatusCode(statusCode)
                .withBody("{\"status\":\"ok\"}", MediaType.JSON_UTF_8)
        );
    }

    private static String extractJsonFromLlmResponse(String text) {
        if (text == null) {
            return "{}";
        }
        String stripped = text.trim();
        // Strip markdown code fences if present
        if (stripped.startsWith("```")) {
            int start = stripped.indexOf('\n');
            int end = stripped.lastIndexOf("```");
            if (start > 0 && end > start) {
                stripped = stripped.substring(start + 1, end).trim();
            }
        }
        return stripped;
    }

    private HttpResponse generateExpectationError(com.fasterxml.jackson.databind.ObjectMapper objectMapper, String message) {
        try {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(
                    objectMapper.createObjectNode().put("error", message)), MediaType.JSON_UTF_8);
        } catch (Exception jsonError) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to generate expectation\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleGrpcHealthPut(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body is required with 'service' and 'status' fields\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String service = node.path("service").asText("");
            // The GET response exposes the default (empty-name) override under the "_default"
            // sentinel; map it back so removing/resetting the default row works.
            if ("_default".equals(service)) {
                service = "";
            }
            // A { service, remove: true } request clears that service's override (reverting it to
            // the default; an empty service resets the default itself) — used by the UI Reset button.
            if (node.path("remove").asBoolean(false)) {
                org.mockserver.grpc.GrpcHealthRegistry.getInstance().removeStatus(service);
                com.fasterxml.jackson.databind.node.ObjectNode removed = objectMapper.createObjectNode();
                removed.put("status", "removed");
                removed.put("service", service);
                return response().withStatusCode(OK.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(removed), MediaType.JSON_UTF_8);
            }
            String statusStr = node.path("status").asText(null);
            if (isBlank(statusStr)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"'status' field is required (UNKNOWN, SERVING, NOT_SERVING, SERVICE_UNKNOWN)\"}", MediaType.JSON_UTF_8);
            }
            org.mockserver.grpc.ServingStatus status;
            try {
                status = org.mockserver.grpc.ServingStatus.valueOf(statusStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"invalid status value, must be one of: UNKNOWN, SERVING, NOT_SERVING, SERVICE_UNKNOWN\"}", MediaType.JSON_UTF_8);
            }
            org.mockserver.grpc.GrpcHealthRegistry.getInstance().setStatus(service, status);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "registered");
            result.put("service", service);
            result.put("servingStatus", status.name());
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to set gRPC health status: " + e.getMessage() + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleGrpcHealthGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.grpc.GrpcHealthRegistry registry = org.mockserver.grpc.GrpcHealthRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            registry.entries().forEach((service, status) ->
                result.put(service.isEmpty() ? "_default" : service, status.name()));
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get gRPC health status\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleClusterGet() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.state.ClusterInfo clusterInfo = stateBackend.clusterInfo();
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("clustered", clusterInfo.clustered());
            result.put("nodeId", clusterInfo.nodeId());
            result.put("coordinator", clusterInfo.coordinator());
            if (clusterInfo.clusterName() != null) {
                result.put("clusterName", clusterInfo.clusterName());
            }
            result.put("memberCount", clusterInfo.members().size());
            com.fasterxml.jackson.databind.node.ArrayNode membersArray = objectMapper.createArrayNode();
            for (org.mockserver.state.ClusterInfo.Member member : clusterInfo.members()) {
                com.fasterxml.jackson.databind.node.ObjectNode memberNode = objectMapper.createObjectNode();
                memberNode.put("id", member.id());
                memberNode.put("coordinator", member.coordinator());
                memberNode.put("local", member.local());
                membersArray.add(memberNode);
            }
            result.set("members", membersArray);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get cluster status\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleDriftGet(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String expectationId = request.getFirstQueryStringParameter("expectationId");
            int limit = 50;
            String limitParam = request.getFirstQueryStringParameter("limit");
            if (limitParam != null && !limitParam.isEmpty()) {
                try {
                    limit = Math.min(500, Integer.parseInt(limitParam));
                } catch (NumberFormatException ignored) {
                    // use default
                }
            }
            org.mockserver.mock.drift.DriftStore store = org.mockserver.mock.drift.DriftStore.getInstance();
            List<org.mockserver.mock.drift.DriftRecord> records = (expectationId != null && !expectationId.isEmpty())
                ? store.getByExpectationId(expectationId)
                : store.getRecent(limit);
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("count", records.size());
            result.set("drifts", objectMapper.valueToTree(records));
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to retrieve drift records\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleDiff(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body required with 'expected' and 'actual' fields\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            if (!node.hasNonNull("expected") || !node.hasNonNull("actual")) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"both 'expected' and 'actual' HttpRequest fields are required\"}", MediaType.JSON_UTF_8);
            }
            RequestDefinition expectedDef = getRequestDefinitionSerializer().deserialize(
                objectMapper.writeValueAsString(node.get("expected")));
            RequestDefinition actualDef = getRequestDefinitionSerializer().deserialize(
                objectMapper.writeValueAsString(node.get("actual")));

            if (!(expectedDef instanceof HttpRequest) || !(actualDef instanceof HttpRequest)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"both 'expected' and 'actual' must be HttpRequest objects\"}", MediaType.JSON_UTF_8);
            }

            org.mockserver.mock.diff.TrafficDiffEngine diffEngine = new org.mockserver.mock.diff.TrafficDiffEngine();
            java.util.List<org.mockserver.mock.diff.FieldDiff> diffs = diffEngine.diff(
                (HttpRequest) expectedDef, (HttpRequest) actualDef);

            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("diffCount", diffs.size());
            result.put("identical", diffs.isEmpty());
            com.fasterxml.jackson.databind.node.ArrayNode diffsArray = result.putArray("diffs");
            for (org.mockserver.mock.diff.FieldDiff diff : diffs) {
                diffsArray.add(objectMapper.valueToTree(diff));
            }
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to diff requests: " + e.getMessage() + "\"}", MediaType.JSON_UTF_8);
        }
    }

    /**
     * The single control-plane gate: authenticates the request and, when
     * {@code controlPlaneAuthorizationEnabled} is on, authorizes it (coarse read/mutate
     * role check), auditing the outcome. Returns true to proceed; on failure writes the
     * 401/403 response itself and returns false.
     * <p>
     * Public so control-plane choke points serviced directly in the Netty layer (e.g.
     * {@code PUT /mockserver/configuration}, which mutates live configuration outside
     * {@link #handle}) route through the SAME authn + authz + audit decision rather than
     * calling the legacy boolean authentication SPI directly — which would authenticate
     * but skip Wave-2 authorization, letting a read-only principal mutate. Operations
     * dispatched through {@link #handle} already call this internally.
     */
    public boolean controlPlaneRequestAuthenticated(HttpRequest request, ResponseWriter responseWriter) {
        ControlPlaneAuthDecision decision = evaluateControlPlaneAuthentication(request);
        switch (decision.outcome()) {
            case ALLOWED:
                return true;
            case FORBIDDEN:
                // verified principal, but its scopes/groups do not grant a role that
                // satisfies the operation's required role: deny with a generic 403. The
                // detail (granted vs required role) is logged server-side only so the
                // authorization policy is not disclosed to the client.
                responseWriter.writeResponse(request, FORBIDDEN, "Forbidden for control plane", MediaType.create("text", "plain").toString());
                return false;
            case UNAUTHENTICATED:
            default:
                String message = decision.clientSafeMessage() != null
                    ? "Unauthorized for control plane - " + decision.clientSafeMessage()
                    : "Unauthorized for control plane";
                responseWriter.writeResponse(request, UNAUTHORIZED, message, MediaType.create("text", "plain").toString());
                return false;
        }
    }

    /**
     * The outcome of the control-plane authn + authz decision.
     * <ul>
     *   <li>{@code ALLOWED} — proceed (this is the value returned when no control-plane
     *       authentication is configured, so default behaviour is unchanged).</li>
     *   <li>{@code UNAUTHENTICATED} — no/invalid credentials → 401-equivalent.</li>
     *   <li>{@code FORBIDDEN} — verified principal lacks the required role → 403-equivalent.</li>
     * </ul>
     */
    public enum ControlPlaneAuthOutcome {ALLOWED, UNAUTHENTICATED, FORBIDDEN}

    /**
     * The decision of {@link #evaluateControlPlaneAuthentication(HttpRequest)}: an
     * {@link ControlPlaneAuthOutcome} plus, for the UNAUTHENTICATED case, an optional
     * client-safe message (only when the authentication handler produced one; the OIDC
     * path deliberately withholds detail from the client).
     */
    public static final class ControlPlaneAuthDecision {
        private static final ControlPlaneAuthDecision ALLOWED = new ControlPlaneAuthDecision(ControlPlaneAuthOutcome.ALLOWED, null);
        private static final ControlPlaneAuthDecision FORBIDDEN = new ControlPlaneAuthDecision(ControlPlaneAuthOutcome.FORBIDDEN, null);
        private final ControlPlaneAuthOutcome outcome;
        private final String clientSafeMessage;

        private ControlPlaneAuthDecision(ControlPlaneAuthOutcome outcome, String clientSafeMessage) {
            this.outcome = outcome;
            this.clientSafeMessage = clientSafeMessage;
        }

        public ControlPlaneAuthOutcome outcome() {
            return outcome;
        }

        public String clientSafeMessage() {
            return clientSafeMessage;
        }

        public boolean isAllowed() {
            return outcome == ControlPlaneAuthOutcome.ALLOWED;
        }
    }

    /**
     * Non-writing control-plane authn + authz decision, shared by
     * {@link #controlPlaneRequestAuthenticated(HttpRequest, ResponseWriter)} (which renders
     * a MockServer {@link HttpResponse}) and by control-plane choke points that must render
     * their own transport-specific rejection — notably the dashboard UI WebSocket upgrade,
     * which replies with a raw HTTP handshake response rather than a MockServer response.
     * <p>
     * Identical authn + authz + audit semantics to {@link #controlPlaneRequestAuthenticated}:
     * when no control-plane authentication handler is configured (the default) it returns
     * {@code ALLOWED} so behaviour is unchanged; when one is configured it enforces the SAME
     * authentication and (when enabled) Wave-2 authorization, records the SAME audit entry,
     * and logs the OIDC failure reason server-side only.
     */
    public ControlPlaneAuthDecision evaluateControlPlaneAuthentication(HttpRequest request) {
        try {
            // Resolve through the getter, NOT the raw field: the handler may be derived from the live
            // configuration, so reading the field directly would miss a control-plane authentication
            // mechanism enabled after startup and fall through to the null => "authenticated" branch.
            AuthenticationHandler resolvedControlPlaneAuthenticationHandler = getControlPlaneAuthenticationHandler();
            org.mockserver.authentication.AuthenticationResult authenticationResult =
                resolvedControlPlaneAuthenticationHandler == null
                    ? org.mockserver.authentication.AuthenticationResult.authenticated(null, "none", java.util.Map.of(), java.util.Set.of())
                    : resolvedControlPlaneAuthenticationHandler.authenticate(request);
            if (authenticationResult.isAuthenticated()) {
                if (configuration.controlPlaneAuthorizationEnabled() && !controlPlaneAuthorized(request, authenticationResult)) {
                    recordAudit(request, authenticationResult, "FORBIDDEN");
                    return ControlPlaneAuthDecision.FORBIDDEN;
                }
                recordAudit(request, authenticationResult, "AUTHORIZED");
                return ControlPlaneAuthDecision.ALLOWED;
            }
        } catch (AuthenticationException authenticationException) {
            if (authenticationException.isClientSafeMessage()) {
                return new ControlPlaneAuthDecision(ControlPlaneAuthOutcome.UNAUTHENTICATED, authenticationException.getMessage());
            }
            // OIDC path: log the detailed reason server-side only and withhold detail from
            // the client so the expected issuer/audience/scopes are not disclosed.
            mockServerLogger.logEvent(
                new org.mockserver.log.model.LogEntry()
                    .setLogLevel(org.slf4j.event.Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("control plane request failed authentication:{}")
                    .setArguments(authenticationException.getMessage())
                    .setThrowable(authenticationException)
            );
            return new ControlPlaneAuthDecision(ControlPlaneAuthOutcome.UNAUTHENTICATED, null);
        }
        return new ControlPlaneAuthDecision(ControlPlaneAuthOutcome.UNAUTHENTICATED, null);
    }

    /**
     * Coarse role-based authorization for a control-plane operation that arrives outside the
     * HTTP method/path classification used by {@link #controlPlaneAuthorized} — notably an MCP
     * tool call, which is dispatched as a single JSON-RPC POST so the per-tool read/mutate
     * split cannot be derived from the HTTP verb. The caller supplies the verified principal's
     * scopes (from {@link org.mockserver.authentication.AuthenticationResult#getScopes()}) and a
     * read/mutate classification it computed for the specific operation; this method reuses the
     * SAME {@link org.mockserver.authentication.authorization.ControlPlaneAuthorizer} and role
     * model as the HTTP control plane — it does NOT introduce a new role model.
     * <p>
     * Gated by {@code controlPlaneAuthorizationEnabled}: when authorization is disabled (the
     * default) this always returns true, so behaviour is unchanged. When enabled it is
     * fail-closed exactly like {@link #controlPlaneAuthorized}: a principal with no mapped role
     * is denied every mutation (and every read unless it has a READ-or-higher role).
     *
     * @param verifiedScopes the authenticated principal's verified scopes (may be null/empty)
     * @param isRead         true if the operation only reads control-plane state, false if it mutates
     * @param operation      a short label for the operation (e.g. the MCP tool name) used only in the
     *                       server-side denial log; may be null
     * @return true to allow the operation; false to deny it with a 403-equivalent
     */
    public boolean controlPlaneToolAuthorized(java.util.Set<String> verifiedScopes, boolean isRead, String operation) {
        if (!configuration.controlPlaneAuthorizationEnabled()) {
            return true;
        }
        org.mockserver.authentication.authorization.ControlPlaneAuthorizer authorizer = controlPlaneAuthorizer();
        java.util.Set<String> scopes = verifiedScopes != null ? verifiedScopes : java.util.Set.of();
        boolean authorized = authorizer.isAuthorized(scopes, isRead);
        if (!authorized && mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            // Mirror controlPlaneAuthorized's server-side-only denial log: the granted-vs-required
            // role detail is logged but never disclosed to the client (authorization policy is not leaked).
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("control plane tool call forbidden:{}")
                    .setArguments("principal granted roles " + authorizer.grantedRoles(scopes) + " do not satisfy required role " + authorizer.requiredRole(isRead) + " for tool " + operation)
            );
        }
        return authorized;
    }

    private static final java.util.Set<String> CONTROL_PLANE_READ_PUTS = new java.util.HashSet<>(java.util.Arrays.asList(
        "retrieve", "verify", "verifySequence", "verifySLO", "diff", "explainUnmatched", "debugMismatch", "files/retrieve", "files/list"
    ));

    /**
     * Coarse role-based authorization decision for an already-AUTHENTICATED control-plane
     * request, gated by {@code controlPlaneAuthorizationEnabled}. Maps the verified
     * principal's scopes/groups through {@code controlPlaneScopeMapping} into granted
     * roles, computes the operation's required role from the existing read/mutate split
     * ({@link #isControlPlaneRead}), and returns whether the granted roles satisfy it.
     * <p>
     * Fail-closed: a principal with no mapped role is denied every mutation (and every
     * read unless it has a READ-or-higher role). Authorization therefore requires a
     * verified principal whose scopes are mapped — i.e. control-plane OIDC authentication
     * should be enabled. The denial detail is logged at INFO server-side only.
     */
    private boolean controlPlaneAuthorized(HttpRequest request, org.mockserver.authentication.AuthenticationResult authenticationResult) {
        org.mockserver.authentication.authorization.ControlPlaneAuthorizer authorizer = controlPlaneAuthorizer();
        String method = request.getMethod() != null ? request.getMethod().getValue() : "";
        String operation = auditOperation(request.getPath() != null ? request.getPath().getValue() : "");
        boolean isRead = isControlPlaneRead(method, operation);
        java.util.Set<String> scopes = authenticationResult != null ? authenticationResult.getScopes() : java.util.Set.of();
        boolean authorized = authorizer.isAuthorized(scopes, isRead);
        if (!authorized && mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.INFO)
                    .setHttpRequest(request)
                    .setMessageFormat("control plane request forbidden:{}")
                    .setArguments("principal granted roles " + authorizer.grantedRoles(scopes) + " do not satisfy required role " + authorizer.requiredRole(isRead) + " for " + method + " " + operation)
            );
        }
        return authorized;
    }

    /**
     * Returns the {@link org.mockserver.authentication.authorization.ControlPlaneAuthorizer}
     * for the current scope mapping, parsing the mapping (and allocating the authorizer)
     * once and reusing it across requests. Re-derives only when the mapping the cached
     * authorizer was built from differs (by value) from the current mapping, so a
     * configuration reload that changes the mapping is honoured without re-parsing on
     * every control-plane request. Cheap reference-equality fast path for the common case
     * where {@code controlPlaneScopeMapping()} returns the same instance each call.
     */
    private org.mockserver.authentication.authorization.ControlPlaneAuthorizer controlPlaneAuthorizer() {
        java.util.Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> mapping = configuration.controlPlaneScopeMapping();
        // Read the holder ONCE: its (authorizer, mapping) pair is always self-consistent.
        AuthorizerHolder holder = cachedAuthorizerHolder;
        if (holder != null && (holder.mapping == mapping || (holder.mapping != null && holder.mapping.equals(mapping)))) {
            return holder.authorizer;
        }
        // Mapping changed (or first use): rebuild the immutable holder and publish it
        // atomically through the single volatile field. The rebuild is idempotent and the
        // authorizer immutable, so a concurrent racing rebuild is harmless.
        org.mockserver.authentication.authorization.ControlPlaneAuthorizer authorizer =
            new org.mockserver.authentication.authorization.ControlPlaneAuthorizer(mapping);
        cachedAuthorizerHolder = new AuthorizerHolder(mapping, authorizer);
        return authorizer;
    }

    /**
     * Best-effort, fail-soft audit of a control-plane operation. Records redacted,
     * structural metadata only (never headers or bodies) into the bounded in-memory
     * {@link org.mockserver.mock.audit.AuditStore}. Off by default — when
     * {@code controlPlaneAuditEnabled} is false this is a no-op and the control-plane
     * operation behaves byte-for-byte identically. Never throws into the request path.
     * <p>
     * When the authentication handler produced a VERIFIED principal (e.g. an OIDC-verified
     * {@code sub} with source {@code verified-oidc}), records that principal/source instead
     * of the unverified best-effort extraction. When {@code authenticationResult} is null or
     * carries no principal (e.g. auth disabled, or a legacy boolean handler), falls back to
     * the unchanged {@link #bestEffortPrincipal} behaviour.
     * <p>
     * The {@code outcome} is "AUTHORIZED" for a permitted operation or "FORBIDDEN" when
     * control-plane authorization denied an authenticated principal.
     */
    private void recordAudit(HttpRequest request, org.mockserver.authentication.AuthenticationResult authenticationResult, String outcome) {
        try {
            if (request == null || !configuration.controlPlaneAuditEnabled()) {
                return;
            }
            String method = request.getMethod() != null ? request.getMethod().getValue() : "";
            String rawPath = request.getPath() != null ? request.getPath().getValue() : "";
            String operation = auditOperation(rawPath);
            // Reads are skipped by default (controlPlaneAuditReads), but a FORBIDDEN
            // outcome is a security-relevant denial and is always recorded when auditing
            // is enabled, even for a read.
            if (!"FORBIDDEN".equals(outcome) && !configuration.controlPlaneAuditReads() && isControlPlaneRead(method, operation)) {
                return;
            }
            String sourceAddress = request.getRemoteAddress();
            if (sourceAddress == null || sourceAddress.isEmpty()) {
                sourceAddress = "unknown";
            }
            String[] principalAndSource =
                authenticationResult != null && authenticationResult.getPrincipal() != null
                    ? new String[]{authenticationResult.getPrincipal(), authenticationResult.getPrincipalSource()}
                    : bestEffortPrincipal(request);
            org.mockserver.mock.audit.AuditEntry entry = new org.mockserver.mock.audit.AuditEntry(
                org.mockserver.time.EpochService.currentTimeMillis(),
                method,
                rawPath,
                operation,
                sourceAddress,
                principalAndSource[0],
                principalAndSource[1],
                outcome,
                null
            );
            org.mockserver.mock.audit.AuditStore.getInstance().add(entry);
            // Optional durable NDJSON file sink — observes the same entry the in-memory
            // ring holds, appending one JSON line per record. No-op unless auditLogFile is
            // set; never crashes request handling (it self-disables on IO error).
            org.mockserver.mock.audit.AuditFileSink.getInstance().write(entry, configuration.auditLogFile());
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.INFO)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setHttpRequest(request())
                        .setMessageFormat("control-plane audit{}")
                        .setArguments(" " + method + " " + operation + " from " + sourceAddress + " as " + principalAndSource[0] + " (" + principalAndSource[1] + ") -> " + outcome)
                );
            }
        } catch (Throwable throwable) {
            if (mockServerLogger != null && mockServerLogger.isEnabledForInstance(Level.TRACE)) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.TRACE)
                        .setLogLevel(Level.TRACE)
                        .setMessageFormat("exception recording control-plane audit entry - " + throwable.getMessage())
                );
            }
        }
    }

    /**
     * Derives the logical operation name from a control-plane path: strips
     * {@link #PATH_PREFIX} and any query string, then returns the path remainder
     * with leading slash removed (e.g. {@code /mockserver/clear?type=all} ->
     * {@code clear}). Returns "" if the path is not under the control-plane prefix.
     */
    private static String auditOperation(String rawPath) {
        if (rawPath == null) {
            return "";
        }
        int query = rawPath.indexOf('?');
        String path = query >= 0 ? rawPath.substring(0, query) : rawPath;
        if (path.startsWith(PATH_PREFIX + "/")) {
            return path.substring(PATH_PREFIX.length() + 1);
        }
        if (path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }

    private static boolean isControlPlaneRead(String method, String operation) {
        if ("GET".equalsIgnoreCase(method)) {
            return true;
        }
        return "PUT".equalsIgnoreCase(method) && CONTROL_PLANE_READ_PUTS.contains(operation);
    }

    /**
     * Best-effort, UNVERIFIED principal extraction. From an {@code Authorization:
     * Bearer <jwt>} header it base64-decodes the JWT payload segment and reads
     * {@code sub} (NO signature verification); else from an mTLS client certificate
     * chain it reads the subject CN; else returns {@code anonymous/none}. The raw
     * token is never stored. Any failure yields {@code anonymous/none}.
     *
     * @return a 2-element array: [principal, principalSource]
     */
    private static String[] bestEffortPrincipal(HttpRequest request) {
        try {
            String authorization = request.getFirstHeader("Authorization");
            if (authorization != null && authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
                String token = authorization.substring(7).trim();
                String[] segments = token.split("\\.");
                if (segments.length >= 2) {
                    byte[] payload = java.util.Base64.getUrlDecoder().decode(padBase64(segments[1]));
                    com.fasterxml.jackson.databind.JsonNode node = ObjectMapperFactory.createObjectMapper().readTree(payload);
                    com.fasterxml.jackson.databind.JsonNode sub = node.get("sub");
                    if (sub != null && sub.isTextual() && !sub.asText().isEmpty()) {
                        return new String[]{sub.asText(), "jwt"};
                    }
                }
            }
        } catch (Throwable ignored) {
            // fall through to mTLS / anonymous
        }
        try {
            java.util.List<org.mockserver.model.X509Certificate> chain = request.getClientCertificateChain();
            if (chain != null && !chain.isEmpty()) {
                String dn = chain.get(0).getSubjectDistinguishedName();
                String cn = extractCommonName(dn);
                if (cn != null && !cn.isEmpty()) {
                    return new String[]{cn, "mtls"};
                }
            }
        } catch (Throwable ignored) {
            // fall through to anonymous
        }
        return new String[]{"anonymous", "none"};
    }

    private static String padBase64(String segment) {
        int pad = segment.length() % 4;
        if (pad == 0) {
            return segment;
        }
        StringBuilder builder = new StringBuilder(segment);
        for (int i = pad; i < 4; i++) {
            builder.append('=');
        }
        return builder.toString();
    }

    private static String extractCommonName(String distinguishedName) {
        if (distinguishedName == null) {
            return null;
        }
        for (String part : distinguishedName.split(",")) {
            String trimmed = part.trim();
            if (trimmed.regionMatches(true, 0, "CN=", 0, 3)) {
                return trimmed.substring(3);
            }
        }
        return null;
    }

    /**
     * Handles GET /mockserver/audit — returns the most-recent control-plane audit
     * entries as a JSON array, newest first. Honours {@code ?limit=<n>} (default
     * 200, capped at 1000). Mirrors {@link #handleDriftGet(HttpRequest)}.
     */
    private HttpResponse handleAuditGet(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            int limit = 200;
            String limitParam = request.getFirstQueryStringParameter("limit");
            if (limitParam != null && !limitParam.isEmpty()) {
                try {
                    limit = Math.min(1000, Integer.parseInt(limitParam));
                } catch (NumberFormatException ignored) {
                    // use default
                }
            }
            List<org.mockserver.mock.audit.AuditEntry> entries = org.mockserver.mock.audit.AuditStore.getInstance().getRecent(limit);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(entries), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to retrieve audit entries\"}", MediaType.JSON_UTF_8);
        }
    }

    @SuppressWarnings("rawtypes")
    private boolean validateSupportedFeatures(Expectation expectation, HttpRequest request, ResponseWriter responseWriter) {
        boolean valid = true;
        Action action = expectation.getAction();
        String NOT_SUPPORTED_MESSAGE = " is not supported by MockServer deployed as a WAR due to limitations in the JEE specification; use mockserver-netty to enable these features";
        if (action instanceof HttpResponse && ((HttpResponse) action).getConnectionOptions() != null) {
            valid = false;
            responseWriter.writeResponse(request, response("ConnectionOptions" + NOT_SUPPORTED_MESSAGE), true);
        } else if (action instanceof HttpObjectCallback) {
            valid = false;
            responseWriter.writeResponse(request, response("HttpObjectCallback" + NOT_SUPPORTED_MESSAGE), true);
        } else if (action instanceof HttpError) {
            valid = false;
            responseWriter.writeResponse(request, response("HttpError" + NOT_SUPPORTED_MESSAGE), true);
        }
        return valid;
    }

    public WebSocketClientRegistry getWebSocketClientRegistry() {
        return webSocketClientRegistry;
    }

    public RequestMatchers getRequestMatchers() {
        return requestMatchers;
    }

    public MockServerEventLog getMockServerLog() {
        return mockServerLog;
    }

    /**
     * Reconcile the running server's capacity-bounded subsystems with the {@link Configuration}
     * after it has been mutated (i.e. immediately after a {@code PUT /mockserver/configuration}).
     * <p>
     * Historically these properties were read exactly once, at construction: a PUT that changed
     * them was accepted, echoed back in the response and then silently ignored. This method closes
     * that gap in the two honest ways available:
     * <ul>
     *     <li><b>Resizable properties are resized in place</b> — {@code maxLogEntries} /
     *     {@code maxEventLogSizeInBytes} (event log deque), {@code maxExpectations} (expectation
     *     store) and {@code controlPlaneAuditMaxEntries} (audit ring). A shrink evicts immediately.</li>
     *     <li><b>Genuinely init-only properties are reported, not silently dropped</b> —
     *     {@code ringBufferSize} (the LMAX disruptor ring is a fixed power-of-two array sized at
     *     construction) and {@code maxWebSocketExpectations} (the local callback registries are
     *     built lazily exactly once). A WARN is logged when, and only when, the client EXPLICITLY
     *     supplied the property and its value DIFFERS from the value in force; the configuration
     *     field is then reset to the in-force value so a subsequent
     *     {@code GET /mockserver/configuration} reports the truth rather than a value the server is
     *     not using. The request still succeeds — a whole-blob PUT that echoes back unchanged
     *     values is unaffected and logs nothing.</li>
     * </ul>
     * <p>
     * The init-only check is driven by what the client actually sent ({@code suppliedConfiguration})
     * rather than by the resolved {@link Configuration} getters, because {@code ringBufferSize}
     * DERIVES from {@code maxLogEntries} when it is not set explicitly. Comparing resolved getters
     * would fire a confusing {@code ringBufferSize} warning at a client that only changed
     * {@code maxLogEntries}, and worse would pin {@code ringBufferSize} to a literal value, breaking
     * that derivation for good.
     *
     * @param suppliedConfiguration the properties the client actually sent, or {@code null} for a
     *                              programmatic update where no init-only reporting is wanted
     */
    public void applyConfigurationUpdate(ConfigurationDTO suppliedConfiguration) {
        mockServerLog.applyConfigurationCapacity();
        requestMatchers.applyConfigurationCapacity();
        org.mockserver.mock.audit.AuditStore.getInstance().setMaxSize(configuration.controlPlaneAuditMaxEntries());

        if (suppliedConfiguration == null) {
            return;
        }
        Integer suppliedRingBufferSize = suppliedConfiguration.getRingBufferSize();
        int ringBufferSizeInForce = mockServerLog.getRingBufferSizeInForce();
        if (suppliedRingBufferSize != null && configuration.ringBufferSize() != ringBufferSizeInForce) {
            warnInitOnlyProperty("ringBufferSize", configuration.ringBufferSize(), ringBufferSizeInForce,
                "the log event ring buffer is a fixed-size array allocated when the server started");
            configuration.ringBufferSize(ringBufferSizeInForce);
        }
        Integer suppliedMaxWebSocketExpectations = suppliedConfiguration.getMaxWebSocketExpectations();
        if (suppliedMaxWebSocketExpectations != null && configuration.maxWebSocketExpectations() != maxWebSocketExpectationsInForce) {
            warnInitOnlyProperty("maxWebSocketExpectations", configuration.maxWebSocketExpectations(), maxWebSocketExpectationsInForce,
                "the local callback registries are sized once when first used");
            configuration.maxWebSocketExpectations(maxWebSocketExpectationsInForce);
        }
    }

    /**
     * Reconcile capacity-bounded subsystems with the {@link Configuration} after a programmatic
     * change, without init-only reporting. See {@link #applyConfigurationUpdate(ConfigurationDTO)}.
     */
    public void applyConfigurationUpdate() {
        applyConfigurationUpdate(null);
    }

    /**
     * Emit a single control-plane audit WARN when an incoming {@code PUT /mockserver/configuration}
     * would LOWER the server's TLS posture — the trust manager downgraded to trust-all ANY, mutual
     * (client-certificate) authentication turned off, outbound host-name verification turned off, or the
     * TLS key/certificate/CA material repointed. Control-plane authentication is off by default, so a
     * runtime downgrade would otherwise be silent; this makes it visible in the log/audit trail. It does
     * NOT block the change (that would be an init-only breaking change).
     * <p>
     * MUST be called BEFORE {@link ConfigurationDTO#applyTo(Configuration)} so {@code configuration} still
     * holds the pre-change ("old") values to compare the supplied ("new") values against.
     */
    public void warnIfLoweringTlsPosture(ConfigurationDTO supplied) {
        if (supplied == null) {
            return;
        }
        List<String> lowered = new ArrayList<>();

        String suppliedTrust = supplied.getForwardProxyTLSX509CertificatesTrustManagerType();
        if ("ANY".equalsIgnoreCase(suppliedTrust)) {
            org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager current;
            try {
                current = configuration.forwardProxyTLSX509CertificatesTrustManagerType();
            } catch (RuntimeException ignore) {
                current = null;
            }
            if (current != org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager.ANY) {
                lowered.add("forwardProxyTLSX509CertificatesTrustManagerType " + current + " -> ANY (upstream certificate validation disabled)");
            }
        }
        if (Boolean.FALSE.equals(supplied.getTlsMutualAuthenticationRequired())
            && Boolean.TRUE.equals(configuration.tlsMutualAuthenticationRequired())) {
            lowered.add("tlsMutualAuthenticationRequired true -> false (client certificate no longer required)");
        }
        if (Boolean.FALSE.equals(supplied.getForwardProxyTLSHostnameVerificationEnabled())
            && Boolean.TRUE.equals(configuration.forwardProxyTLSHostnameVerificationEnabled())) {
            lowered.add("forwardProxyTLSHostnameVerificationEnabled true -> false (upstream host name no longer verified)");
        }
        addIfRepointed(lowered, "privateKeyPath", configuration.privateKeyPath(), supplied.getPrivateKeyPath());
        addIfRepointed(lowered, "x509CertificatePath", configuration.x509CertificatePath(), supplied.getX509CertificatePath());
        addIfRepointed(lowered, "certificateAuthorityCertificate", configuration.certificateAuthorityCertificate(), supplied.getCertificateAuthorityCertificate());
        addIfRepointed(lowered, "certificateAuthorityPrivateKey", configuration.certificateAuthorityPrivateKey(), supplied.getCertificateAuthorityPrivateKey());
        addIfRepointed(lowered, "tlsMutualAuthenticationCertificateChain", configuration.tlsMutualAuthenticationCertificateChain(), supplied.getTlsMutualAuthenticationCertificateChain());

        if (!lowered.isEmpty()) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setMessageFormat("control plane PUT /mockserver/configuration changed TLS-sensitive configuration that lowers or alters security posture: {} — verify this was intended, especially as control plane authentication is disabled by default")
                    .setArguments(String.join("; ", lowered))
            );
        }
    }

    private static void addIfRepointed(List<String> lowered, String name, String oldValue, String suppliedValue) {
        if (suppliedValue != null && !java.util.Objects.equals(suppliedValue, oldValue)) {
            lowered.add(name + " repointed (\"" + oldValue + "\" -> \"" + suppliedValue + "\")");
        }
    }

    /**
     * WARN that an init-only configuration property was supplied with a value that cannot take
     * effect on the running server, naming the value in force and how to actually apply the change.
     */
    private void warnInitOnlyProperty(String property, int suppliedValue, int valueInForce, String reason) {
        if (mockServerLogger.isEnabledForInstance(Level.WARN)) {
            mockServerLogger.logEvent(new LogEntry()
                .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                .setLogLevel(Level.WARN)
                .setMessageFormat(
                    property + " is fixed at startup and cannot be changed on a running server (" + reason + ") - "
                        + "the supplied value " + suppliedValue + " has been IGNORED and " + property + " remains "
                        + valueInForce + "; set it before the server starts (system property, environment variable "
                        + "or startup configuration) to change it"
                ));
        }
    }

    /**
     * Set the high-level operating mode (SIMULATE/SPY/CAPTURE), the single behavioural
     * switch behind {@code PUT /mockserver/mode}. Records the mode so {@code GET /mockserver/mode}
     * round-trips it and toggles {@code attemptToProxyIfNoMatchingExpectation} to match. Shared
     * by the REST handler and the {@code set_operating_mode} MCP tool so there is one code path.
     *
     * @param mode the mode to apply (must not be null)
     * @return the applied mode
     */
    public MockMode setMode(MockMode mode) {
        if (mode == null) {
            throw new IllegalArgumentException("mode is required (one of SIMULATE, SPY, CAPTURE)");
        }
        this.mockMode = mode;
        configuration.attemptToProxyIfNoMatchingExpectation(mode.proxyUnmatchedRequests());
        return mode;
    }

    /**
     * Promote traffic already recorded by MockServer's forwarding/proxy mode into ACTIVE mock
     * expectations — the shared implementation behind {@code PUT /mockserver/recordings/promote}
     * and the {@code promote_recordings} MCP tool, so an agent can "record then mock" in one step.
     * Retrieves recorded {@code FORWARDED_REQUEST} exchanges matching the optional filter, redacts
     * secrets (per {@code redactionOptions}), optionally consolidates/parameterizes them into
     * reusable mocks (unlimited times), and ADDs them to the active expectation set.
     *
     * @param filter           optional request-matcher filter (null promotes all recorded traffic)
     * @param consolidate      when true, collapse duplicate exchanges into consolidated mocks;
     *                         when false, promote each recording verbatim (still unlimited times)
     * @param parameterize     when true (and consolidating), generalise volatile path/query/header/
     *                         body values so a single recorded id does not pin the mock
     * @param redactionOptions redaction options (null defaults to enabled with the default lists)
     * @return the activated expectations (with their assigned ids)
     */
    public List<Expectation> promoteRecordings(RequestDefinition filter, boolean consolidate, boolean parameterize, org.mockserver.imports.ImportRedaction.Options redactionOptions) {
        final RequestDefinition promoteFilter = filter != null ? filter : request();
        final String promoteCorrelationId = UUIDService.getUUID();
        List<Expectation> recorded = awaitRetrieve(
            (Consumer<Consumer<List<Expectation>>>) consumer -> mockServerLog.retrieveRecordedExpectations(promoteFilter, consumer),
            promoteCorrelationId, promoteFilter instanceof HttpRequest ? (HttpRequest) promoteFilter : null
        );

        // Redact BEFORE consolidation so promoted mocks never carry captured credentials and so
        // responses that differed only in a secret can collapse together.
        recorded = org.mockserver.imports.ImportRedaction.redact(recorded, redactionOptions != null ? redactionOptions : org.mockserver.imports.ImportRedaction.Options.enabled());

        List<Expectation> produced;
        if (consolidate) {
            produced = RecordedExpectationPostProcessor.consolidate(recorded, parameterize);
        } else {
            produced = new ArrayList<>(recorded.size());
            for (Expectation recordedExpectation : recorded) {
                produced.add(new Expectation(
                    recordedExpectation.getHttpRequest(),
                    org.mockserver.matchers.Times.unlimited(),
                    org.mockserver.matchers.TimeToLive.unlimited(),
                    0
                ).thenRespond(recordedExpectation.getHttpResponse()));
            }
        }

        return add(produced.toArray(new Expectation[0]));
    }

    public Scheduler getScheduler() {
        return scheduler;
    }

    public String getUniqueLoopPreventionHeaderName() {
        return "x-forwarded-by";
    }

    public String getUniqueLoopPreventionHeaderValue() {
        return uniqueLoopPreventionHeaderValue;
    }

    public void stop() {
        if (expectationFileSystemPersistence != null) {
            expectationFileSystemPersistence.stop();
        }
        if (recordedExpectationFileSystemPersistence != null) {
            recordedExpectationFileSystemPersistence.stop();
        }
        if (recordedRequestsFileSystemPersistence != null) {
            recordedRequestsFileSystemPersistence.stop();
        }
        if (expectationFileWatcher != null) {
            expectationFileWatcher.stop();
        }
        if (memoryMonitoring != null) {
            memoryMonitoring.stop();
        }
        // Stop any active AsyncAPI broker connections (Kafka consumers, MQTT clients)
        // so they are not leaked on shutdown; no-op when the async module is absent
        // or nothing is loaded.
        org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance().reset();
        getMockServerLog().stop();
        // G10 phase 2a: close the state backend (no-op for in-memory)
        if (stateBackend != null) {
            stateBackend.close();
        }
    }

    /**
     * Returns the pluggable state backend (G10 phase 2a). The default
     * implementation is in-memory with zero behaviour change.
     */
    public StateBackend getStateBackend() {
        return stateBackend;
    }

    // ---- Replay control-plane ----

    /**
     * Maximum body size (in bytes) allowed for a replayed request to prevent OOM.
     * Requests whose body exceeds this cap are rejected with 413 Payload Too Large.
     */
    private static final int REPLAY_MAX_BODY_SIZE = 10 * 1024 * 1024; // 10 MB

    /**
     * Run OpenAPI contract tests against a live service. The control-plane request body is a JSON
     * document containing:
     * <ul>
     *   <li>{@code spec} (or {@code specUrlOrPayload}) — required; a URL, file path, or inline JSON/YAML OpenAPI spec</li>
     *   <li>{@code baseUrl} — required; the base URL of the service under test e.g. {@code http://localhost:8080}</li>
     *   <li>{@code operationId} — optional; restricts the run to a single operation</li>
     * </ul>
     * <p>For each operation in the spec a representative example request is built, sent to the target
     * service (reusing the wired HTTP client via {@link #replayHandler}), and the response is validated
     * against the spec. A structured pass/fail-per-operation report is returned as JSON.</p>
     * <p>The same SSRF policy applied to the forward/replay path is enforced against the resolved target
     * host before any request is sent.</p>
     */
    private void handleContractTest(HttpRequest controlPlaneRequest, ResponseWriter responseWriter, CompletableFuture<Boolean> canHandle) {
        try {
            if (replayHandler == null) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"contract testing is not available — no HTTP client has been wired\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            String body = controlPlaneRequest.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body is required — must be a JSON document with a \\\"spec\\\" (URL or inline spec) and a \\\"baseUrl\\\"\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            com.fasterxml.jackson.databind.JsonNode rootNode = ObjectMapperFactory.createObjectMapper().readTree(body);
            String spec = textOrNull(rootNode, "spec");
            if (isBlank(spec)) {
                spec = textOrNull(rootNode, "specUrlOrPayload");
            }
            String baseUrl = textOrNull(rootNode, "baseUrl");
            String operationIdFilter = textOrNull(rootNode, "operationId");

            if (isBlank(spec)) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body must contain a \\\"spec\\\" — a URL, file path, or inline OpenAPI spec\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }
            if (isBlank(baseUrl)) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body must contain a \\\"baseUrl\\\" — the base URL of the service under test\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            final java.net.URI target;
            try {
                target = new java.net.URI(baseUrl.trim());
            } catch (java.net.URISyntaxException e) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":" + jsonEncodeString("invalid baseUrl: " + e.getMessage()) + "}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }
            final String targetHost = target.getHost();
            if (isBlank(targetHost)) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"baseUrl must include a host e.g. http://localhost:8080\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            // SSRF protection: validate the target host against the same policy enforced by the
            // normal forward and replay paths.
            try {
                InetAddressValidator.validateForwardTarget(configuration, targetHost);
            } catch (IllegalArgumentException blocked) {
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("contract test blocked by SSRF policy:{}")
                        .setArguments(blocked.getMessage())
                );
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(FORBIDDEN.code())
                    .withBody("{\"error\":" + jsonEncodeString("contract test blocked by SSRF policy: " + blocked.getMessage()) + "}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            final boolean https = "https".equalsIgnoreCase(target.getScheme());
            final int targetPort = target.getPort() != -1 ? target.getPort() : (https ? 443 : 80);
            final String contextPath = target.getRawPath() != null && !"/".equals(target.getRawPath())
                ? org.apache.commons.lang3.StringUtils.removeEnd(target.getRawPath(), "/") : "";
            final long timeoutMillis = configuration.maxSocketTimeoutInMillis();

            final String specRef = spec;

            // The contract-test run drives a per-operation loop, each iteration of which BLOCKS on the
            // wired async HTTP client (.get(timeoutMillis)). This must NOT run on the Netty event-loop
            // (worker) thread: because the outbound NettyHttpClient shares the same workerGroup, the
            // outbound I/O can be assigned to the very thread parked in .get() — self-deadlocking the
            // event loop — and even without that it holds the worker thread for the full
            // maxSocketTimeout × operationCount, starving every connection pinned to that thread.
            // Offload the entire run onto the scheduler's (non-I/O) executor and complete canHandle /
            // write the response from that worker, mirroring the async pattern of handleReplay.
            scheduler.getExecutorService().submit(() -> {
                try {
                    // HTTP sender: targets each example request at the service-under-test and blocks on
                    // the wired async HTTP client. Runs on the off-loop worker thread; no breakpoints apply.
                    java.util.function.Function<HttpRequest, HttpResponse> httpSender = exampleRequest -> {
                        HttpRequest outbound = exampleRequest
                            .withSocketAddress(targetHost, targetPort, https ? SocketAddress.Scheme.HTTPS : SocketAddress.Scheme.HTTP)
                            .withSecure(https)
                            .withHeader(HOST.toString(), targetPort == (https ? 443 : 80) ? targetHost : (targetHost + ":" + targetPort));
                        if (!contextPath.isEmpty()) {
                            String path = outbound.getPath() != null ? outbound.getPath().getValue() : "/";
                            outbound.withPath(contextPath + path);
                        }
                        try {
                            HttpResponse upstream = replayHandler.apply(outbound)
                                .get(timeoutMillis, MILLISECONDS);
                            return upstream != null ? upstream : response().withStatusCode(0);
                        } catch (Exception e) {
                            throw new RuntimeException("failed to send contract-test request to " + baseUrl + ": " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()), e);
                        }
                    };

                    List<org.mockserver.openapi.OpenApiContractTest.ContractTestResult> results =
                        new org.mockserver.openapi.OpenApiContractTest(mockServerLogger)
                            .runContractTests(specRef, baseUrl, operationIdFilter, httpSender);

                    int passed = 0;
                    for (org.mockserver.openapi.OpenApiContractTest.ContractTestResult result : results) {
                        if (result.isPassed()) {
                            passed++;
                        }
                    }
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                    com.fasterxml.jackson.databind.node.ObjectNode reportNode = objectMapper.createObjectNode();
                    reportNode.put("baseUrl", baseUrl);
                    reportNode.put("totalOperations", results.size());
                    reportNode.put("passed", passed);
                    reportNode.put("failed", results.size() - passed);
                    reportNode.put("allPassed", passed == results.size());
                    com.fasterxml.jackson.databind.node.ArrayNode resultsNode = reportNode.putArray("results");
                    for (org.mockserver.openapi.OpenApiContractTest.ContractTestResult result : results) {
                        com.fasterxml.jackson.databind.node.ObjectNode resultNode = resultsNode.addObject();
                        resultNode.put("operationId", result.getOperationId());
                        resultNode.put("method", result.getMethod());
                        resultNode.put("path", result.getPath());
                        resultNode.put("statusCodeReceived", result.getStatusCodeReceived());
                        resultNode.put("passed", result.isPassed());
                        com.fasterxml.jackson.databind.node.ArrayNode errorsNode = resultNode.putArray("validationErrors");
                        if (result.getValidationErrors() != null) {
                            for (String error : result.getValidationErrors()) {
                                errorsNode.add(error);
                            }
                        }
                    }

                    responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                        .withStatusCode(OK.code())
                        .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(reportNode), MediaType.JSON_UTF_8)), true);
                } catch (Exception e) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setHttpRequest(controlPlaneRequest)
                            .setMessageFormat("exception handling contract test request:{}error:{}")
                            .setArguments(controlPlaneRequest, e.getMessage())
                            .setThrowable(e)
                    );
                    responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                        .withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":" + jsonEncodeString(e.getMessage() != null ? e.getMessage() : "unknown error") + "}", MediaType.JSON_UTF_8)), true);
                } finally {
                    canHandle.complete(true);
                }
            });
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequest(controlPlaneRequest)
                    .setMessageFormat("exception handling contract test request:{}error:{}")
                    .setArguments(controlPlaneRequest, e.getMessage())
                    .setThrowable(e)
            );
            responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":" + jsonEncodeString(e.getMessage() != null ? e.getMessage() : "unknown error") + "}", MediaType.JSON_UTF_8)), true);
            canHandle.complete(true);
        }
    }

    /**
     * Package-private test hook: invokes {@link #handleContractTest} directly so tests can observe
     * that the handler offloads its blocking per-operation work off the calling (event-loop) thread
     * rather than running it inline.
     */
    void handleContractTestForTest(HttpRequest controlPlaneRequest, ResponseWriter responseWriter, CompletableFuture<Boolean> canHandle) {
        handleContractTest(controlPlaneRequest, responseWriter, canHandle);
    }

    /**
     * Validate the recorded request/response traffic against a provided OpenAPI specification.
     *
     * <p>The control-plane request body is a JSON document with a {@code "spec"} field — a URL, file
     * path, or inline OpenAPI document. Each recorded request/response pair held in the event log is
     * located against the spec ({@link OpenApiTrafficValidator}), its request validated by the OpenAPI
     * request validator and its response by the OpenAPI response validator, and a structured report is
     * returned mirroring the {@code /contractTest} report shape (per-pair results, pass/fail counts,
     * {@code allPassed}).
     *
     * <p>When {@code spec} is a URL its host is checked against the same SSRF policy enforced by the
     * forward/replay/contract-test paths before the parser is allowed to fetch it.
     */
    private void handleTrafficValidate(HttpRequest controlPlaneRequest, ResponseWriter responseWriter, CompletableFuture<Boolean> canHandle) {
        try {
            String body = controlPlaneRequest.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body is required — must be a JSON document with a \\\"spec\\\" (URL, file path, or inline OpenAPI spec)\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            com.fasterxml.jackson.databind.JsonNode rootNode = ObjectMapperFactory.createObjectMapper().readTree(body);
            String spec = textOrNull(rootNode, "spec");
            if (isBlank(spec)) {
                spec = textOrNull(rootNode, "specUrlOrPayload");
            }
            if (isBlank(spec)) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body must contain a \\\"spec\\\" — a URL, file path, or inline OpenAPI spec\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            // SSRF protection: when the spec is fetched from an http(s) URL, validate its host against
            // the same policy enforced by the forward/replay/contract-test paths before the OpenAPI
            // parser is allowed to dereference it.
            if (org.mockserver.openapi.OpenAPIParser.isSpecUrl(spec)) {
                String specHost = null;
                try {
                    java.net.URI specUri = new java.net.URI(spec.trim());
                    String scheme = specUri.getScheme();
                    if (scheme != null && (scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
                        specHost = specUri.getHost();
                    }
                } catch (java.net.URISyntaxException ignore) {
                    // not a parseable URI — treat as a file path / inline payload, no host to validate
                }
                if (isNotBlank(specHost)) {
                    try {
                        InetAddressValidator.validateForwardTarget(configuration, specHost);
                    } catch (IllegalArgumentException blocked) {
                        mockServerLogger.logEvent(
                            new LogEntry()
                                .setLogLevel(Level.WARN)
                                .setMessageFormat("traffic validation spec fetch blocked by SSRF policy:{}")
                                .setArguments(blocked.getMessage())
                        );
                        responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                            .withStatusCode(FORBIDDEN.code())
                            .withBody("{\"error\":" + jsonEncodeString("traffic validation spec fetch blocked by SSRF policy: " + blocked.getMessage()) + "}", MediaType.JSON_UTF_8)), true);
                        canHandle.complete(true);
                        return;
                    }
                }
            }

            final String specRef = spec;
            // Retrieve the recorded request/response pairs (async, via the event log) and then run the
            // OpenAPI traffic validation off the calling (event-loop) thread: buildOpenAPI may fetch a
            // remote spec URL (blocking I/O) and must not run on the worker event loop.
            mockServerLog.retrieveRequestResponses(null, pairs -> {
              try {
                scheduler.getExecutorService().submit(() -> {
                try {
                    List<org.apache.commons.lang3.tuple.Pair<HttpRequest, HttpResponse>> requestResponsePairs = new java.util.ArrayList<>();
                    for (LogEventRequestAndResponse pair : pairs) {
                        if (pair.getHttpRequest() != null && pair.getHttpResponse() != null) {
                            requestResponsePairs.add(org.apache.commons.lang3.tuple.Pair.of(pair.getHttpRequest(), pair.getHttpResponse()));
                        }
                    }

                    List<org.mockserver.openapi.OpenApiTrafficValidator.TrafficValidationResult> results =
                        new org.mockserver.openapi.OpenApiTrafficValidator(mockServerLogger)
                            .validate(specRef, requestResponsePairs);

                    int passed = 0;
                    for (org.mockserver.openapi.OpenApiTrafficValidator.TrafficValidationResult result : results) {
                        if (result.isPassed()) {
                            passed++;
                        }
                    }
                    com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
                    com.fasterxml.jackson.databind.node.ObjectNode reportNode = objectMapper.createObjectNode();
                    reportNode.put("totalRequests", results.size());
                    reportNode.put("passed", passed);
                    reportNode.put("failed", results.size() - passed);
                    reportNode.put("allPassed", passed == results.size());
                    com.fasterxml.jackson.databind.node.ArrayNode resultsNode = reportNode.putArray("results");
                    for (org.mockserver.openapi.OpenApiTrafficValidator.TrafficValidationResult result : results) {
                        com.fasterxml.jackson.databind.node.ObjectNode resultNode = resultsNode.addObject();
                        resultNode.put("method", result.getRequestMethod());
                        resultNode.put("path", result.getRequestPath());
                        resultNode.put("matchedOperation", result.getMatchedOperation());
                        resultNode.put("passed", result.isPassed());
                        com.fasterxml.jackson.databind.node.ArrayNode requestErrorsNode = resultNode.putArray("requestErrors");
                        if (result.getRequestErrors() != null) {
                            for (String error : result.getRequestErrors()) {
                                requestErrorsNode.add(error);
                            }
                        }
                        com.fasterxml.jackson.databind.node.ArrayNode responseErrorsNode = resultNode.putArray("responseErrors");
                        if (result.getResponseErrors() != null) {
                            for (String error : result.getResponseErrors()) {
                                responseErrorsNode.add(error);
                            }
                        }
                    }

                    responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                        .withStatusCode(OK.code())
                        .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(reportNode), MediaType.JSON_UTF_8)), true);
                } catch (Exception e) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setHttpRequest(controlPlaneRequest)
                            .setMessageFormat("exception handling traffic validation request:{}error:{}")
                            .setArguments(controlPlaneRequest, e.getMessage())
                            .setThrowable(e)
                    );
                    responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                        .withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":" + jsonEncodeString(e.getMessage() != null ? e.getMessage() : "unknown error") + "}", MediaType.JSON_UTF_8)), true);
                } finally {
                    canHandle.complete(true);
                }
                });
              } catch (Exception submitFailure) {
                // The offload itself failed (e.g. RejectedExecutionException during shutdown). This
                // runs on the disruptor consumer thread, outside the outer try/catch, so complete
                // canHandle here to avoid leaving the control-plane request hanging.
                mockServerLogger.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setHttpRequest(controlPlaneRequest)
                        .setMessageFormat("exception offloading traffic validation request:{}error:{}")
                        .setArguments(controlPlaneRequest, submitFailure.getMessage())
                        .setThrowable(submitFailure)
                );
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(SERVICE_UNAVAILABLE.code())
                    .withBody("{\"error\":" + jsonEncodeString("unable to schedule traffic validation: " + (submitFailure.getMessage() != null ? submitFailure.getMessage() : submitFailure.getClass().getSimpleName())) + "}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
              }
            });
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequest(controlPlaneRequest)
                    .setMessageFormat("exception handling traffic validation request:{}error:{}")
                    .setArguments(controlPlaneRequest, e.getMessage())
                    .setThrowable(e)
            );
            responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":" + jsonEncodeString(e.getMessage() != null ? e.getMessage() : "unknown error") + "}", MediaType.JSON_UTF_8)), true);
            canHandle.complete(true);
        }
    }

    /**
     * Returns the text value of a JSON field, or {@code null} if the field is absent, null, or not textual.
     */
    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode rootNode, String fieldName) {
        com.fasterxml.jackson.databind.JsonNode node = rootNode.get(fieldName);
        return node != null && !node.isNull() ? node.asText() : null;
    }

    /**
     * Re-issue a previously recorded/proxied request to its target and return
     * the upstream response. The payload is a standard {@code HttpRequest} JSON;
     * the target host/port is resolved from the {@code Host} header or the
     * explicit {@code socketAddress} field in the JSON (same rules as the
     * regular forward/proxy path).
     */
    private void handleReplay(HttpRequest controlPlaneRequest, ResponseWriter responseWriter, CompletableFuture<Boolean> canHandle) {
        try {
            if (replayHandler == null) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"replay is not available — no HTTP client has been wired\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            String body = controlPlaneRequest.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body must contain an HttpRequest JSON definition\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            HttpRequest requestToReplay = getHttpRequestSerializer().deserialize(body);

            // Safety: enforce body-size cap on outbound request
            byte[] requestBody = requestToReplay.getBodyAsRawBytes();
            if (requestBody != null && requestBody.length > REPLAY_MAX_BODY_SIZE) {
                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                    .withStatusCode(REQUEST_ENTITY_TOO_LARGE.code())
                    .withBody("{\"error\":\"request body exceeds maximum replay size of " + REPLAY_MAX_BODY_SIZE + " bytes\"}", MediaType.JSON_UTF_8)), true);
                canHandle.complete(true);
                return;
            }

            // SSRF protection: validate the target host against the same policy
            // enforced by the normal forward path (HttpForwardActionHandler).
            // Resolves the host from socketAddress (if set) or the Host header,
            // mirroring HttpRequest.socketAddressFromHostHeader().
            String replayTargetHost = resolveReplayTargetHost(requestToReplay);
            if (isNotBlank(replayTargetHost)) {
                try {
                    InetAddressValidator.validateForwardTarget(configuration, replayTargetHost);
                } catch (IllegalArgumentException blocked) {
                    mockServerLogger.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.WARN)
                            .setHttpRequest(requestToReplay)
                            .setMessageFormat("replay blocked by SSRF policy:{}")
                            .setArguments(blocked.getMessage())
                    );
                    responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                        .withStatusCode(FORBIDDEN.code())
                        .withBody("{\"error\":" + jsonEncodeString("replay blocked by SSRF policy: " + blocked.getMessage()) + "}", MediaType.JSON_UTF_8)), true);
                    canHandle.complete(true);
                    return;
                }
            }

            replayHandler.apply(requestToReplay)
                .orTimeout(configuration.maxSocketTimeoutInMillis(), MILLISECONDS)
                .whenComplete((upstreamResponse, throwable) -> {
                    try {
                        if (throwable != null) {
                            String errorMessage = throwable.getMessage() != null ? throwable.getMessage() : throwable.getClass().getSimpleName();
                            mockServerLogger.logEvent(
                                new LogEntry()
                                    .setLogLevel(Level.WARN)
                                    .setHttpRequest(requestToReplay)
                                    .setMessageFormat("exception replaying request:{}error:{}")
                                    .setArguments(requestToReplay, errorMessage)
                                    .setThrowable(throwable)
                            );
                            responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                                .withStatusCode(BAD_GATEWAY.code())
                                .withBody("{\"error\":" + jsonEncodeString("replay failed: " + errorMessage) + "}", MediaType.JSON_UTF_8)), true);
                        } else {
                            // Return the upstream response wrapped in a JSON envelope
                            // so the dashboard can display it alongside the original request.
                            HttpResponse replayResponse = upstreamResponse != null ? upstreamResponse : response().withStatusCode(OK.code());

                            // Safety: enforce body-size cap on upstream response to prevent
                            // OOM from materializing + JSON-serializing an unbounded body.
                            byte[] responseBody = replayResponse.getBodyAsRawBytes();
                            if (responseBody != null && responseBody.length > REPLAY_MAX_BODY_SIZE) {
                                responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                                    .withStatusCode(BAD_GATEWAY.code())
                                    .withBody("{\"error\":\"upstream response body exceeds maximum replay size of " + REPLAY_MAX_BODY_SIZE + " bytes — response too large to return via control plane\"}", MediaType.JSON_UTF_8)), true);
                                return;
                            }

                            String serializedResponse = getHttpResponseSerializer().serialize(replayResponse);
                            responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                                .withStatusCode(OK.code())
                                .withBody(serializedResponse, MediaType.JSON_UTF_8)), true);
                        }
                    } finally {
                        canHandle.complete(true);
                    }
                });
        } catch (Exception e) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setHttpRequest(controlPlaneRequest)
                    .setMessageFormat("exception handling replay request:{}error:{}")
                    .setArguments(controlPlaneRequest, e.getMessage())
                    .setThrowable(e)
            );
            responseWriter.writeResponse(controlPlaneRequest, withDashboardCORS(controlPlaneRequest, response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":" + jsonEncodeString(e.getMessage() != null ? e.getMessage() : "unknown error") + "}", MediaType.JSON_UTF_8)), true);
            canHandle.complete(true);
        }
    }

    /**
     * Resolve the target host from a replay request, using the same precedence
     * as {@link HttpRequest#socketAddressFromHostHeader()}: explicit
     * {@code socketAddress.host} first, then the {@code Host} header.
     */
    private static String resolveReplayTargetHost(HttpRequest request) {
        if (request.getSocketAddress() != null && request.getSocketAddress().getHost() != null) {
            return request.getSocketAddress().getHost();
        }
        String hostHeader = request.getFirstHeader(HOST.toString());
        if (isNotBlank(hostHeader)) {
            return HttpRequest.splitHostPort(hostHeader)[0];
        }
        return null;
    }

    /**
     * Arms record-and-forward of unmatched requests to {@code upstream} for the session — the
     * server-side half of the one-command record round-trip exposed via
     * {@code GET /mockserver/retrieve?type=RECORDED_EXPECTATIONS&forwardUnmatchedTo=<upstream>}.
     * <p>
     * {@code upstream} may be a bare {@code host}, {@code host:port}, or a full URL
     * ({@code http://host:port} / {@code https://host:port}). The host is SSRF-validated against the
     * same policy enforced by the normal forward and replay paths <em>before</em> any state is mutated.
     * On success the proxy-remote host/port and {@code attemptToProxyIfNoMatchingExpectation} flag are
     * set so that subsequent unmatched traffic is forwarded to the upstream and recorded.
     *
     * @return {@code null} on success, or a populated error {@link HttpResponse} (BAD_REQUEST / FORBIDDEN)
     * to return directly when the upstream is malformed or blocked by SSRF policy.
     */
    private HttpResponse enableRecordAndForward(String upstream, String logCorrelationId) {
        final String host;
        final int port;
        final boolean https;
        try {
            final String trimmed = upstream.trim();
            if (trimmed.contains("://")) {
                final java.net.URI uri = new java.net.URI(trimmed);
                host = uri.getHost();
                https = "https".equalsIgnoreCase(uri.getScheme());
                port = uri.getPort() != -1 ? uri.getPort() : (https ? 443 : 80);
            } else {
                final String[] hostPort = HttpRequest.splitHostPort(trimmed);
                host = hostPort[0];
                https = false;
                port = hostPort.length > 1 && isNotBlank(hostPort[1]) ? Integer.parseInt(hostPort[1]) : 80;
            }
        } catch (Exception parseError) {
            return response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":" + jsonEncodeString("invalid forwardUnmatchedTo value: " + parseError.getMessage()) + "}", MediaType.JSON_UTF_8);
        }
        if (isBlank(host)) {
            return response()
                .withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"forwardUnmatchedTo must include a host e.g. localhost:8080 or http://localhost:8080\"}", MediaType.JSON_UTF_8);
        }

        // SSRF protection: validate the upstream host against the same policy enforced by the
        // normal forward and replay paths before mutating any configuration / connecting.
        try {
            InetAddressValidator.validateForwardTarget(configuration, host);
        } catch (IllegalArgumentException blocked) {
            mockServerLogger.logEvent(
                new LogEntry()
                    .setLogLevel(Level.WARN)
                    .setCorrelationId(logCorrelationId)
                    .setMessageFormat("record-and-forward blocked by SSRF policy:{}")
                    .setArguments(blocked.getMessage())
            );
            return response()
                .withStatusCode(FORBIDDEN.code())
                .withBody("{\"error\":" + jsonEncodeString("record-and-forward blocked by SSRF policy: " + blocked.getMessage()) + "}", MediaType.JSON_UTF_8);
        }

        configuration.proxyRemoteHost(host);
        configuration.proxyRemotePort(port);
        configuration.attemptToProxyIfNoMatchingExpectation(true);
        mockServerLogger.logEvent(
            new LogEntry()
                .setType(LogEntry.LogMessageType.INFO)
                .setLogLevel(Level.INFO)
                .setCorrelationId(logCorrelationId)
                .setMessageFormat("enabled record-and-forward of unmatched requests to upstream " + host + ":" + port + (https ? " (https)" : ""))
        );
        return null;
    }

    /**
     * JSON-encode a string value (with surrounding quotes) using Jackson so that
     * special characters (quotes, backslashes, newlines, control chars) are
     * properly escaped — replacing the naive {@code .replace("\"","'")} pattern.
     */
    private static String jsonEncodeString(String value) {
        try {
            return ObjectMapperFactory.createObjectMapper().writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            // Fallback: manual minimal escaping (should never happen for a plain string)
            return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r") + "\"";
        }
    }

    // ---- Lazy serializer getters ----

    private ExpectationIdSerializer getExpectationIdSerializer() {
        if (this.expectationIdSerializer == null) {
            this.expectationIdSerializer = new ExpectationIdSerializer(mockServerLogger);
        }
        return expectationIdSerializer;
    }

    private RequestDefinitionSerializer getRequestDefinitionSerializer() {
        if (this.requestDefinitionSerializer == null) {
            this.requestDefinitionSerializer = new RequestDefinitionSerializer(mockServerLogger);
        }
        return requestDefinitionSerializer;
    }

    private LogEventRequestAndResponseSerializer getHttpRequestResponseSerializer() {
        if (this.httpRequestResponseSerializer == null) {
            this.httpRequestResponseSerializer = new LogEventRequestAndResponseSerializer(mockServerLogger);
        }
        return httpRequestResponseSerializer;
    }

    private ExpectationSerializer getExpectationSerializer() {
        if (this.expectationSerializer == null) {
            this.expectationSerializer = new ExpectationSerializer(mockServerLogger);
        }
        return expectationSerializer;
    }

    private ExpectationSerializer getExpectationSerializerThatSerializesBodyDefault() {
        if (this.expectationSerializerThatSerializesBodyDefault == null) {
            this.expectationSerializerThatSerializesBodyDefault = new ExpectationSerializer(mockServerLogger, true);
        }
        return expectationSerializerThatSerializesBodyDefault;
    }

    private OpenAPIExpectationSerializer getOpenAPIExpectationSerializer() {
        if (this.openAPIExpectationSerializer == null) {
            this.openAPIExpectationSerializer = new OpenAPIExpectationSerializer(mockServerLogger);
        }
        return openAPIExpectationSerializer;
    }

    private ExpectationToJavaSerializer getExpectationToJavaSerializer() {
        if (this.expectationToJavaSerializer == null) {
            this.expectationToJavaSerializer = new ExpectationToJavaSerializer();
        }
        return expectationToJavaSerializer;
    }

    private org.mockserver.serialization.code.ExpectationToJavaScriptSerializer getExpectationToJavaScriptSerializer() {
        if (this.expectationToJavaScriptSerializer == null) {
            this.expectationToJavaScriptSerializer = new org.mockserver.serialization.code.ExpectationToJavaScriptSerializer(getExpectationSerializerThatSerializesBodyDefault());
        }
        return expectationToJavaScriptSerializer;
    }

    private org.mockserver.serialization.code.ExpectationToPythonSerializer getExpectationToPythonSerializer() {
        if (this.expectationToPythonSerializer == null) {
            this.expectationToPythonSerializer = new org.mockserver.serialization.code.ExpectationToPythonSerializer(getExpectationSerializerThatSerializesBodyDefault());
        }
        return expectationToPythonSerializer;
    }

    private org.mockserver.serialization.code.ExpectationToGoSerializer getExpectationToGoSerializer() {
        if (this.expectationToGoSerializer == null) {
            this.expectationToGoSerializer = new org.mockserver.serialization.code.ExpectationToGoSerializer(getExpectationSerializerThatSerializesBodyDefault());
        }
        return expectationToGoSerializer;
    }

    private org.mockserver.serialization.code.ExpectationToCSharpSerializer getExpectationToCSharpSerializer() {
        if (this.expectationToCSharpSerializer == null) {
            this.expectationToCSharpSerializer = new org.mockserver.serialization.code.ExpectationToCSharpSerializer(getExpectationSerializerThatSerializesBodyDefault());
        }
        return expectationToCSharpSerializer;
    }

    private org.mockserver.serialization.code.ExpectationToRubySerializer getExpectationToRubySerializer() {
        if (this.expectationToRubySerializer == null) {
            this.expectationToRubySerializer = new org.mockserver.serialization.code.ExpectationToRubySerializer(getExpectationSerializerThatSerializesBodyDefault());
        }
        return expectationToRubySerializer;
    }

    private org.mockserver.serialization.code.ExpectationToRustSerializer getExpectationToRustSerializer() {
        if (this.expectationToRustSerializer == null) {
            this.expectationToRustSerializer = new org.mockserver.serialization.code.ExpectationToRustSerializer(getExpectationSerializerThatSerializesBodyDefault());
        }
        return expectationToRustSerializer;
    }

    private org.mockserver.serialization.code.ExpectationToPhpSerializer getExpectationToPhpSerializer() {
        if (this.expectationToPhpSerializer == null) {
            this.expectationToPhpSerializer = new org.mockserver.serialization.code.ExpectationToPhpSerializer(getExpectationSerializerThatSerializesBodyDefault());
        }
        return expectationToPhpSerializer;
    }

    private org.mockserver.serialization.ExpectationExportSerializer getExpectationExportSerializer() {
        if (this.expectationExportSerializer == null) {
            this.expectationExportSerializer = new org.mockserver.serialization.ExpectationExportSerializer(mockServerLogger);
        }
        return expectationExportSerializer;
    }

    /**
     * Apply the opt-in recorded-expectation post-processor (deduplicate +
     * templatize) to a retrieved list of recorded expectations when
     * {@code configuration.deduplicateRecordedExpectations()} is enabled. When the
     * flag is off (the default) the input list is returned unchanged, so the
     * retrieved output is byte-for-byte identical to historical behaviour.
     *
     * @param expectations the recorded expectations as retrieved from the event log
     * @return the post-processed list when the flag is on, otherwise the input list
     */
    private List<Expectation> postProcessRecordedExpectations(List<Expectation> expectations, HttpRequest request) {
        List<Expectation> processed = expectations;
        // Per-request opt-in overrides via query parameters take precedence over the
        // config-flag path: ?consolidate=true collapses recorded exchanges by request
        // shape into unlimited-times mocks (sequencing differing responses), and
        // ?parameterize=true additionally generalises volatile path/query/header/body
        // values. Default (neither param, flag off) keeps the historical verbatim output.
        boolean consolidateParam = "true".equalsIgnoreCase(request.getFirstQueryStringParameter("consolidate"));
        boolean parameterizeParam = "true".equalsIgnoreCase(request.getFirstQueryStringParameter("parameterize"));
        if (consolidateParam || parameterizeParam) {
            int inputCount = processed == null ? 0 : processed.size();
            processed = RecordedExpectationPostProcessor.consolidate(processed, parameterizeParam);
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.INFO)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("consolidated recorded expectations from " + inputCount + " to " + processed.size())
            );
        } else if (Boolean.TRUE.equals(configuration.deduplicateRecordedExpectations())) {
            int inputCount = processed == null ? 0 : processed.size();
            boolean templatizeValues = Boolean.TRUE.equals(configuration.templatizeRecordedValues());
            processed = RecordedExpectationPostProcessor.deduplicateAndTemplatize(processed, templatizeValues);
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.INFO)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("deduplicated and templatized recorded expectations from " + inputCount + " to " + processed.size())
            );
        }
        if (Boolean.TRUE.equals(configuration.redactSecretsInRecordedExpectations()) && processed != null && !processed.isEmpty()) {
            Expectation[] redacted = new org.mockserver.fixture.FixtureRedactor()
                .redact(processed.toArray(new Expectation[0]), true);
            processed = new java.util.ArrayList<>(java.util.Arrays.asList(redacted));
            mockServerLogger.logEvent(
                new LogEntry()
                    .setType(LogEntry.LogMessageType.INFO)
                    .setLogLevel(Level.INFO)
                    .setMessageFormat("redacted secrets in " + processed.size() + " recorded expectations")
            );
        }
        return processed;
    }

    /**
     * Build a HAR-shaped request/response list from a list of expectations.
     * Used by the OpenAPI/Postman/Bruno/HAR export branches on the
     * ACTIVE_EXPECTATIONS and RECORDED_EXPECTATIONS paths so all formats
     * share one conversion path. Expectations without an httpResponse
     * (forward / template / callback / error / LLM) are still included so
     * that the request side is exported.
     */
    private java.util.List<org.mockserver.model.LogEventRequestAndResponse> expectationsToLogEvents(java.util.List<Expectation> expectations) {
        java.util.List<org.mockserver.model.LogEventRequestAndResponse> result = new java.util.ArrayList<>(expectations.size());
        for (Expectation expectation : expectations) {
            org.mockserver.model.RequestDefinition req = expectation.getHttpRequest();
            if (!(req instanceof org.mockserver.model.HttpRequest)) {
                continue;
            }
            org.mockserver.model.LogEventRequestAndResponse pair = new org.mockserver.model.LogEventRequestAndResponse()
                .withHttpRequest((org.mockserver.model.HttpRequest) req);
            if (expectation.getHttpResponse() != null) {
                pair.withHttpResponse(expectation.getHttpResponse());
            }
            result.add(pair);
        }
        return result;
    }

    private VerificationSerializer getVerificationSerializer() {
        if (this.verificationSerializer == null) {
            this.verificationSerializer = new VerificationSerializer(mockServerLogger);
        }
        return verificationSerializer;
    }

    private VerificationSequenceSerializer getVerificationSequenceSerializer() {
        if (this.verificationSequenceSerializer == null) {
            this.verificationSequenceSerializer = new VerificationSequenceSerializer(mockServerLogger);
        }
        return verificationSequenceSerializer;
    }

    private SloCriteriaSerializer getSloCriteriaSerializer() {
        if (this.sloCriteriaSerializer == null) {
            this.sloCriteriaSerializer = new SloCriteriaSerializer(mockServerLogger);
        }
        return sloCriteriaSerializer;
    }

    private org.mockserver.serialization.LoadScenarioSerializer getLoadScenarioSerializer() {
        if (this.loadScenarioSerializer == null) {
            this.loadScenarioSerializer = new org.mockserver.serialization.LoadScenarioSerializer(mockServerLogger);
        }
        return loadScenarioSerializer;
    }

    private LogEntrySerializer getLogEntrySerializer() {
        if (this.logEntrySerializer == null) {
            this.logEntrySerializer = new LogEntrySerializer(mockServerLogger, configuration);
        }
        return logEntrySerializer;
    }

    private OpenAPIConverter getOpenAPIConverter() {
        if (this.openAPIConverter == null) {
            // pass the effective Configuration so generateRealisticExampleValues set on the
            // instance (including via PUT /mockserver/configuration) reaches ExampleBuilder
            this.openAPIConverter = new OpenAPIConverter(mockServerLogger, configuration);
        }
        return openAPIConverter;
    }

    private org.mockserver.serialization.har.HarConverter getHarConverter() {
        if (this.harConverter == null) {
            this.harConverter = new org.mockserver.serialization.har.HarConverter();
        }
        return harConverter;
    }

    private HttpRequestSerializer getHttpRequestSerializer() {
        if (this.httpRequestSerializer == null) {
            this.httpRequestSerializer = new HttpRequestSerializer(mockServerLogger);
        }
        return httpRequestSerializer;
    }

    private HttpResponseSerializer getHttpResponseSerializer() {
        if (this.httpResponseSerializer == null) {
            this.httpResponseSerializer = new HttpResponseSerializer(mockServerLogger);
        }
        return httpResponseSerializer;
    }

    private org.mockserver.serialization.curl.HttpRequestToCurlSerializer getHttpRequestToCurlSerializer() {
        if (this.httpRequestToCurlSerializer == null) {
            this.httpRequestToCurlSerializer = new org.mockserver.serialization.curl.HttpRequestToCurlSerializer(mockServerLogger);
        }
        return httpRequestToCurlSerializer;
    }

    /**
     * Render a list of recorded requests as cURL commands, one per request,
     * separated by a blank line.
     */
    private String toCurlCommands(List<HttpRequest> requests) {
        StringBuilder builder = new StringBuilder();
        for (HttpRequest request : requests) {
            if (builder.length() > 0) {
                builder.append(NEW_LINE).append(NEW_LINE);
            }
            builder.append(getHttpRequestToCurlSerializer().toCurl(request));
        }
        builder.append(NEW_LINE);
        return builder.toString();
    }

    // ---- AsyncAPI control-plane ----

    private HttpResponse handleAsyncApiPut(HttpRequest request) {
        try {
            org.mockserver.async.AsyncApiControlPlaneRegistry registry = org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance();
            if (!registry.isAvailable()) {
                return response().withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"AsyncAPI messaging module is not available — mockserver-async is not on the classpath\"}", MediaType.JSON_UTF_8);
            }
            String body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body must contain an AsyncAPI spec (JSON/YAML) or {spec, brokerConfig}\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode result = registry.load(body);
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            return response().withStatusCode(CREATED.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to load AsyncAPI spec: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleAsyncApiHttpImport(HttpRequest request) {
        try {
            org.mockserver.async.AsyncApiControlPlaneRegistry registry = org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance();
            if (!registry.isAvailable()) {
                return response().withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"AsyncAPI messaging module is not available — mockserver-async is not on the classpath\"}", MediaType.JSON_UTF_8);
            }
            String body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body must contain an AsyncAPI spec (JSON/YAML) or {spec, channelPathPrefix}\"}", MediaType.JSON_UTF_8);
            }
            String expectationsJson = registry.generateHttpExpectations(body);
            List<Expectation> upsertedExpectations = add(getExpectationSerializer().deserializeArray(expectationsJson, false));
            return response().withStatusCode(CREATED.code())
                .withBody(getExpectationSerializer().serialize(upsertedExpectations), MediaType.JSON_UTF_8);
        } catch (IllegalArgumentException e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(errorJson(String.valueOf(e.getMessage())), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(errorJson("failed to import AsyncAPI spec as HTTP expectations: " + e.getMessage()), MediaType.JSON_UTF_8);
        }
    }

    /**
     * Build a {@code {"error": "..."}} JSON body, escaping the message via Jackson so that
     * arbitrary exception text (quotes, backslashes, control characters) cannot corrupt the
     * JSON structure.
     */
    private static String errorJson(String message) {
        try {
            return ObjectMapperFactory.createObjectMapper()
                .writeValueAsString(java.util.Collections.singletonMap("error", message));
        } catch (Exception e) {
            return "{\"error\":\"error serializing error message\"}";
        }
    }

    private HttpResponse handleAsyncApiGet() {
        try {
            org.mockserver.async.AsyncApiControlPlaneRegistry registry = org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance();
            if (!registry.isAvailable()) {
                return response().withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"AsyncAPI messaging module is not available — mockserver-async is not on the classpath\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode result = registry.status();
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to get AsyncAPI status: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }

    /**
     * Build {@link org.mockserver.imports.ImportRedaction.Options} from the
     * {@code PUT /mockserver/import} query parameters:
     * <ul>
     *     <li>{@code redactSensitiveData} — boolean, defaults to {@code true}; when
     *     {@code false} the import is kept verbatim (redaction disabled).</li>
     *     <li>{@code additionalRedactedHeaders} — comma-separated extra header names
     *     to redact on top of the defaults.</li>
     *     <li>{@code additionalRedactedBodyFields} — comma-separated extra JSON body
     *     field names to redact on top of the defaults.</li>
     * </ul>
     */
    private static org.mockserver.imports.ImportRedaction.Options buildImportRedactionOptions(HttpRequest request) {
        String redactSensitiveData = request.getFirstQueryStringParameter("redactSensitiveData");
        boolean enabled = !"false".equalsIgnoreCase(redactSensitiveData);
        org.mockserver.imports.ImportRedaction.Options options = enabled
            ? org.mockserver.imports.ImportRedaction.Options.enabled()
            : org.mockserver.imports.ImportRedaction.Options.disabled();
        options.withAdditionalSensitiveHeaders(splitCommaSeparated(request.getFirstQueryStringParameter("additionalRedactedHeaders")));
        options.withAdditionalSensitiveBodyFields(splitCommaSeparated(request.getFirstQueryStringParameter("additionalRedactedBodyFields")));
        return options;
    }

    private static List<String> splitCommaSeparated(String value) {
        if (value == null || value.trim().isEmpty()) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }
        return result;
    }

    private HttpResponse handlePactVerify(HttpRequest request) {
        try {
            String body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"Pact contract JSON must not be empty\"}", MediaType.JSON_UTF_8);
            }
            org.mockserver.mock.pact.PactVerifier verifier = new org.mockserver.mock.pact.PactVerifier();
            org.mockserver.mock.pact.PactVerifier.PactVerificationResult result = verifier.verify(body, requestMatchers);
            if (result.isVerified()) {
                return response().withStatusCode(ACCEPTED.code())
                    .withBody(result.toJson(), MediaType.JSON_UTF_8);
            } else {
                return response().withStatusCode(NOT_ACCEPTABLE.code())
                    .withBody(result.toJson(), MediaType.JSON_UTF_8);
            }
        } catch (IllegalArgumentException e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to verify Pact contract: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }

    private HttpResponse handleAsyncApiVerify(HttpRequest request) {
        try {
            org.mockserver.async.AsyncApiControlPlaneRegistry registry = org.mockserver.async.AsyncApiControlPlaneRegistry.getInstance();
            if (!registry.isAvailable()) {
                return response().withStatusCode(NOT_IMPLEMENTED.code())
                    .withBody("{\"error\":\"AsyncAPI messaging module is not available — mockserver-async is not on the classpath\"}", MediaType.JSON_UTF_8);
            }
            String body = request.getBodyAsString();
            if (body == null || body.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"verification request body must not be empty\"}", MediaType.JSON_UTF_8);
            }
            String result = registry.verify(body);
            if (isEmpty(result)) {
                return response().withStatusCode(ACCEPTED.code());
            } else {
                return response().withStatusCode(NOT_ACCEPTABLE.code())
                    .withBody(result, MediaType.create("text", "plain"));
            }
        } catch (IllegalArgumentException e) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"" + String.valueOf(e.getMessage()).replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        } catch (Exception e) {
            String message = String.valueOf(e.getMessage());
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"failed to verify async messages: " + message.replace("\"", "'") + "\"}", MediaType.JSON_UTF_8);
        }
    }

    // --- breakpoint matcher control endpoints ---

    private HttpResponse handleBreakpointMatcherRegister(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body is required with 'httpRequest' and 'phases' fields\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);

            // validate httpRequest
            com.fasterxml.jackson.databind.JsonNode httpRequestNode = node.get("httpRequest");
            if (httpRequestNode == null || httpRequestNode.isNull() || httpRequestNode.isMissingNode()
                || (httpRequestNode.isObject() && httpRequestNode.isEmpty())) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"'httpRequest' field is required and must not be empty\"}", MediaType.JSON_UTF_8);
            }

            // validate phases
            com.fasterxml.jackson.databind.JsonNode phasesNode = node.get("phases");
            if (phasesNode == null || phasesNode.isNull() || phasesNode.isMissingNode() || !phasesNode.isArray() || phasesNode.isEmpty()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"'phases' field is required and must be a non-empty array\"}", MediaType.JSON_UTF_8);
            }

            java.util.Set<org.mockserver.mock.breakpoint.BreakpointPhase> phases = java.util.EnumSet.noneOf(org.mockserver.mock.breakpoint.BreakpointPhase.class);
            for (com.fasterxml.jackson.databind.JsonNode phaseElement : phasesNode) {
                String phaseName = phaseElement.asText(null);
                if (isBlank(phaseName)) {
                    return response().withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":\"each element in 'phases' must be a non-empty string\"}", MediaType.JSON_UTF_8);
                }
                try {
                    phases.add(org.mockserver.mock.breakpoint.BreakpointPhase.valueOf(phaseName));
                } catch (IllegalArgumentException e) {
                    return response().withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":\"unknown phase '" + phaseName.replace("\"", "'") + "'; valid phases are: "
                            + java.util.Arrays.toString(org.mockserver.mock.breakpoint.BreakpointPhase.values()) + "\"}", MediaType.JSON_UTF_8);
                }
            }

            // deserialize the request matcher
            RequestDefinition requestMatcher = getRequestDefinitionSerializer().deserialize(objectMapper.writeValueAsString(httpRequestNode));

            // clientId is REQUIRED — breakpoints are always dispatched over the callback WS
            com.fasterxml.jackson.databind.JsonNode clientIdNode = node.get("clientId");
            String clientId = (clientIdNode != null && !clientIdNode.isNull() && clientIdNode.isTextual())
                ? clientIdNode.asText(null) : null;
            if (clientId == null || clientId.isBlank()) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"'clientId' field is required (must be the callback WebSocket client id)\"}", MediaType.JSON_UTF_8);
            }

            // optional skipCount — conditional (Nth-hit) breakpoint: do not pause
            // on the first skipCount matching hits; absent/null => pause every time.
            com.fasterxml.jackson.databind.JsonNode skipCountNode = node.get("skipCount");
            Integer skipCount = null;
            if (skipCountNode != null && !skipCountNode.isNull() && !skipCountNode.isMissingNode()) {
                if (!skipCountNode.isIntegralNumber() || !skipCountNode.canConvertToInt() || skipCountNode.asInt() < 0) {
                    return response().withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":\"'skipCount' must be a non-negative integer\"}", MediaType.JSON_UTF_8);
                }
                int sc = skipCountNode.asInt();
                skipCount = sc > 0 ? sc : null;
            }

            // optional maxHits — one-shot / bounded breakpoint: after the breakpoint has
            // paused maxHits times it auto-deregisters and stops intercepting. maxHits=1 is
            // a one-shot breakpoint; absent/null => never auto-deregister (legacy behaviour).
            com.fasterxml.jackson.databind.JsonNode maxHitsNode = node.get("maxHits");
            Integer maxHits = null;
            if (maxHitsNode != null && !maxHitsNode.isNull() && !maxHitsNode.isMissingNode()) {
                if (!maxHitsNode.isIntegralNumber() || !maxHitsNode.canConvertToInt() || maxHitsNode.asInt() < 1) {
                    return response().withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":\"'maxHits' must be a positive integer\"}", MediaType.JSON_UTF_8);
                }
                maxHits = maxHitsNode.asInt();
            }

            // optional response-content conditions — RESPONSE-phase only: pause only when
            // the response status code falls within [responseStatusCodeMin, responseStatusCodeMax]
            // (inclusive) and/or the response body matches the responseBodyContains regex.
            // Absent => pause regardless of response content (legacy behaviour).
            Integer responseStatusCodeMin = null;
            com.fasterxml.jackson.databind.JsonNode minNode = node.get("responseStatusCodeMin");
            if (minNode != null && !minNode.isNull() && !minNode.isMissingNode()) {
                if (!minNode.isIntegralNumber() || !minNode.canConvertToInt()) {
                    return response().withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":\"'responseStatusCodeMin' must be an integer\"}", MediaType.JSON_UTF_8);
                }
                responseStatusCodeMin = minNode.asInt();
            }
            Integer responseStatusCodeMax = null;
            com.fasterxml.jackson.databind.JsonNode maxNode = node.get("responseStatusCodeMax");
            if (maxNode != null && !maxNode.isNull() && !maxNode.isMissingNode()) {
                if (!maxNode.isIntegralNumber() || !maxNode.canConvertToInt()) {
                    return response().withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":\"'responseStatusCodeMax' must be an integer\"}", MediaType.JSON_UTF_8);
                }
                responseStatusCodeMax = maxNode.asInt();
            }
            if (responseStatusCodeMin != null && responseStatusCodeMax != null && responseStatusCodeMin > responseStatusCodeMax) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"'responseStatusCodeMin' must not be greater than 'responseStatusCodeMax'\"}", MediaType.JSON_UTF_8);
            }
            String responseBodyContains = null;
            com.fasterxml.jackson.databind.JsonNode bodyContainsNode = node.get("responseBodyContains");
            if (bodyContainsNode != null && !bodyContainsNode.isNull() && !bodyContainsNode.isMissingNode()) {
                if (!bodyContainsNode.isTextual()) {
                    return response().withStatusCode(BAD_REQUEST.code())
                        .withBody("{\"error\":\"'responseBodyContains' must be a string\"}", MediaType.JSON_UTF_8);
                }
                String bc = bodyContainsNode.asText();
                if (!bc.isEmpty()) {
                    try {
                        java.util.regex.Pattern.compile(bc);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        return response().withStatusCode(BAD_REQUEST.code())
                            .withBody("{\"error\":\"'responseBodyContains' is not a valid regular expression\"}", MediaType.JSON_UTF_8);
                    }
                    responseBodyContains = bc;
                }
            }

            // register
            String id = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance()
                .register(requestMatcher, phases, clientId, skipCount,
                    responseStatusCodeMin, responseStatusCodeMax, responseBodyContains, maxHits, configuration, mockServerLogger);

            // build response
            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("id", id);
            com.fasterxml.jackson.databind.node.ArrayNode phasesArray = objectMapper.createArrayNode();
            for (org.mockserver.mock.breakpoint.BreakpointPhase phase : phases) {
                phasesArray.add(phase.name());
            }
            result.set("phases", phasesArray);
            result.put("clientId", clientId);
            if (skipCount != null) {
                result.put("skipCount", skipCount);
            }
            if (maxHits != null) {
                result.put("maxHits", maxHits);
            }
            if (responseStatusCodeMin != null) {
                result.put("responseStatusCodeMin", responseStatusCodeMin);
            }
            if (responseStatusCodeMax != null) {
                result.put("responseStatusCodeMax", responseStatusCodeMax);
            }
            if (responseBodyContains != null) {
                result.put("responseBodyContains", responseBodyContains);
            }

            return response().withStatusCode(CREATED.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return breakpointErrorResponse(objectMapper, e);
        }
    }

    private HttpResponse handleBreakpointMatcherList() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.breakpoint.BreakpointMatcherRegistry registry = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance();
            com.fasterxml.jackson.databind.node.ArrayNode matchersArray = objectMapper.createArrayNode();
            for (org.mockserver.mock.breakpoint.BreakpointMatcher matcher : registry.entries()) {
                com.fasterxml.jackson.databind.node.ObjectNode matcherNode = objectMapper.createObjectNode();
                matcherNode.put("id", matcher.getId());

                // serialize the request matcher
                String requestJson = getRequestDefinitionSerializer().serialize(true, matcher.getRequestMatcher());
                matcherNode.set("httpRequest", objectMapper.readTree(requestJson));

                com.fasterxml.jackson.databind.node.ArrayNode phasesArray = objectMapper.createArrayNode();
                for (org.mockserver.mock.breakpoint.BreakpointPhase phase : matcher.getPhases()) {
                    phasesArray.add(phase.name());
                }
                matcherNode.set("phases", phasesArray);
                if (matcher.getClientId() != null) {
                    matcherNode.put("clientId", matcher.getClientId());
                }
                if (matcher.getSkipCount() != null) {
                    matcherNode.put("skipCount", matcher.getSkipCount());
                }
                if (matcher.getMaxHits() != null) {
                    matcherNode.put("maxHits", matcher.getMaxHits());
                }
                if (matcher.getResponseStatusCodeMin() != null) {
                    matcherNode.put("responseStatusCodeMin", matcher.getResponseStatusCodeMin());
                }
                if (matcher.getResponseStatusCodeMax() != null) {
                    matcherNode.put("responseStatusCodeMax", matcher.getResponseStatusCodeMax());
                }
                if (matcher.getResponseBodyContains() != null) {
                    matcherNode.put("responseBodyContains", matcher.getResponseBodyContains());
                }
                matchersArray.add(matcherNode);
            }

            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.set("matchers", matchersArray);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return breakpointErrorResponse(objectMapper, e);
        }
    }

    private HttpResponse handleBreakpointMatcherRemove(HttpRequest request) {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            String body = request.getBodyAsJsonOrXmlString();
            if (isBlank(body)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"request body is required with an 'id' field\"}", MediaType.JSON_UTF_8);
            }
            com.fasterxml.jackson.databind.JsonNode node = objectMapper.readTree(body);
            String id = node.path("id").asText(null);
            if (isBlank(id)) {
                return response().withStatusCode(BAD_REQUEST.code())
                    .withBody("{\"error\":\"'id' field is required\"}", MediaType.JSON_UTF_8);
            }

            boolean removed = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance().remove(id);
            if (!removed) {
                com.fasterxml.jackson.databind.node.ObjectNode errNode = objectMapper.createObjectNode();
                errNode.put("error", "breakpoint matcher not found");
                errNode.put("id", id);
                return response().withStatusCode(NOT_FOUND.code())
                    .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(errNode), MediaType.JSON_UTF_8);
            }

            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "removed");
            result.put("id", id);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return breakpointErrorResponse(objectMapper, e);
        }
    }

    private HttpResponse handleBreakpointMatcherClear() {
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = ObjectMapperFactory.createObjectMapper();
        try {
            org.mockserver.mock.breakpoint.BreakpointMatcherRegistry registry = org.mockserver.mock.breakpoint.BreakpointMatcherRegistry.getInstance();
            int count = registry.size();
            registry.clear();

            com.fasterxml.jackson.databind.node.ObjectNode result = objectMapper.createObjectNode();
            result.put("status", "cleared");
            result.put("count", count);
            return response().withStatusCode(OK.code())
                .withBody(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(result), MediaType.JSON_UTF_8);
        } catch (Exception e) {
            return breakpointErrorResponse(objectMapper, e);
        }
    }

    /**
     * Builds a safe JSON error response for breakpoint endpoints using Jackson,
     * avoiding string-concatenation JSON injection.
     */
    private HttpResponse breakpointErrorResponse(com.fasterxml.jackson.databind.ObjectMapper objectMapper, Exception e) {
        try {
            com.fasterxml.jackson.databind.node.ObjectNode errNode = objectMapper.createObjectNode();
            errNode.put("error", String.valueOf(e.getMessage()));
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody(objectMapper.writeValueAsString(errNode), MediaType.JSON_UTF_8);
        } catch (Exception jsonEx) {
            return response().withStatusCode(BAD_REQUEST.code())
                .withBody("{\"error\":\"internal error building response\"}", MediaType.JSON_UTF_8);
        }
    }
}

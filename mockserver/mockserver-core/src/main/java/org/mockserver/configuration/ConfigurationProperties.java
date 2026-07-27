package org.mockserver.configuration;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;
import org.apache.commons.lang3.StringUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.mockserver.file.FileReader;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.memory.MemoryMonitoring;
import org.mockserver.memory.Summary;
import org.mockserver.model.ProxyPassMapping;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.mockserver.socket.tls.KeyAndCertificateFactory;
import org.slf4j.event.Level;

import java.io.*;
import java.lang.management.MemoryType;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.apache.commons.lang3.StringUtils.isBlank;
import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.mockserver.character.Character.NEW_LINE;
import static org.mockserver.log.model.LogEntry.LogMessageType.SERVER_CONFIGURATION;
import static org.mockserver.logging.MockServerLogger.configureLogger;
import static org.slf4j.event.Level.DEBUG;

/**
 * @author jamesdbloom
 */
public class ConfigurationProperties {

    private static final class LoggerHolder {
        private static final MockServerLogger LOGGER = new MockServerLogger(ConfigurationProperties.class);
    }

    private static final String DEFAULT_LOG_LEVEL = "INFO";

    // logging
    private static final String MOCKSERVER_LOG_LEVEL = "mockserver.logLevel";
    private static final String MOCKSERVER_DISABLE_SYSTEM_OUT = "mockserver.disableSystemOut";
    private static final String MOCKSERVER_DISABLE_LOGGING = "mockserver.disableLogging";
    private static final String MOCKSERVER_DETAILED_MATCH_FAILURES = "mockserver.detailedMatchFailures";
    private static final String MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG = "mockserver.launchUIForLogLevelDebug";
    private static final String MOCKSERVER_METRICS_ENABLED = "mockserver.metricsEnabled";
    private static final String MOCKSERVER_DASHBOARD_ANALYTICS_ENABLED = "mockserver.dashboardAnalyticsEnabled";
    private static final String MOCKSERVER_DASHBOARD_ANALYTICS_ENDPOINT = "mockserver.dashboardAnalyticsEndpoint";
    private static final String MOCKSERVER_DASHBOARD_ANALYTICS_KEY = "mockserver.dashboardAnalyticsKey";
    private static final String MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION = "mockserver.dashboardAnalyticsDistribution";
    private static final String MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS = "mockserver.slowRequestThresholdMillis";
    private static final String MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS = "mockserver.metricsRequestDurationRouteLabels";
    private static final String MOCKSERVER_CHAOS_AUTO_HALT_ENABLED = "mockserver.chaosAutoHaltEnabled";
    private static final String MOCKSERVER_CHAOS_AUTO_HALT_ERROR_THRESHOLD = "mockserver.chaosAutoHaltErrorThreshold";
    private static final String MOCKSERVER_CHAOS_AUTO_HALT_WINDOW_MILLIS = "mockserver.chaosAutoHaltWindowMillis";
    private static final String MOCKSERVER_RATE_LIMIT_MAX_NAMED_QUOTAS = "mockserver.rateLimitMaxNamedQuotas";
    private static final String MOCKSERVER_CONNECTION_LIFECYCLE_CHAOS_ENABLED = "mockserver.connectionLifecycleChaosEnabled";
    private static final String MOCKSERVER_PREEMPTION_SIMULATION_MAX_DRAIN_MILLIS = "mockserver.preemptionSimulationMaxDrainMillis";
    private static final String MOCKSERVER_CONNECTION_LIFECYCLE_AUTO_HALT_COUNTS_RST = "mockserver.connectionLifecycleAutoHaltCountsRst";
    private static final String MOCKSERVER_SLO_TRACKING_ENABLED = "mockserver.sloTrackingEnabled";
    private static final String MOCKSERVER_SLO_WINDOW_RETENTION_MILLIS = "mockserver.sloWindowRetentionMillis";
    private static final String MOCKSERVER_SLO_WINDOW_MAX_SAMPLES = "mockserver.sloWindowMaxSamples";
    private static final String MOCKSERVER_LOAD_GENERATION_ENABLED = "mockserver.loadGenerationEnabled";
    private static final String MOCKSERVER_LOAD_GENERATION_SUPPRESS_EVENT_LOG = "mockserver.loadGenerationSuppressEventLog";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_VIRTUAL_USERS = "mockserver.loadGenerationMaxVirtualUsers";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_IN_FLIGHT_REQUESTS = "mockserver.loadGenerationMaxInFlightRequests";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_REQUESTS_PER_SECOND = "mockserver.loadGenerationMaxRequestsPerSecond";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_DURATION_MILLIS = "mockserver.loadGenerationMaxDurationMillis";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_STEPS = "mockserver.loadGenerationMaxSteps";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_RATE = "mockserver.loadGenerationMaxRate";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_STAGES = "mockserver.loadGenerationMaxStages";
    private static final String MOCKSERVER_LOAD_GENERATION_MAX_CONCURRENT_SCENARIOS = "mockserver.loadGenerationMaxConcurrentScenarios";
    private static final String MOCKSERVER_LOAD_GENERATION_METRIC_LABELS = "mockserver.loadGenerationMetricLabels";
    private static final String MOCKSERVER_LOAD_SCENARIO_INITIALIZATION_JSON_PATH = "mockserver.loadScenarioInitializationJsonPath";
    private static final String MOCKSERVER_MCP_ENABLED = "mockserver.mcpEnabled";
    private static final String MOCKSERVER_STOP_DRAIN_MILLIS = "mockserver.stopDrainMillis";
    private static final String MOCKSERVER_BREAKPOINT_TIMEOUT_MILLIS = "mockserver.breakpointTimeoutMillis";
    private static final String MOCKSERVER_BREAKPOINT_MAX_HELD = "mockserver.breakpointMaxHeld";
    private static final String MOCKSERVER_LOG_LEVEL_OVERRIDES = "mockserver.logLevelOverrides";
    private static final String MOCKSERVER_COMPACT_LOG_FORMAT = "mockserver.compactLogFormat";

    // dev mode
    private static final String MOCKSERVER_DEV_MODE = "mockserver.devMode";
    static final int DEV_MODE_MAX_LOG_ENTRIES = 1000;
    static final int DEV_MODE_MAX_EXPECTATIONS = 1000;

    // memory usage
    private static final String MOCKSERVER_MAX_EXPECTATIONS = "mockserver.maxExpectations";
    private static final String MOCKSERVER_MAX_LOG_ENTRIES = "mockserver.maxLogEntries";
    // LLM-capture disk-offload + OOM guard (config plumbing only — eviction/persistence behaviour added later)
    private static final String MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES = "mockserver.maxEventLogSizeInBytes";
    private static final String MOCKSERVER_MAX_LOGGED_BODY_BYTES = "mockserver.maxLoggedBodyBytes";
    private static final String MOCKSERVER_RING_BUFFER_SIZE = "mockserver.ringBufferSize";
    // Default ceiling for the in-flight disruptor ring buffer, decoupling it from maxLogEntries
    // retention. The ring only has to absorb event bursts between the producer (Netty I/O threads)
    // and the single consumer thread — it is NOT the retained history (that is the separate
    // CircularConcurrentLinkedDeque sized at maxLogEntries). 16384 slots (rounded up to the next
    // power of two by Configuration.ringBufferSize) comfortably absorbs realistic bursts while
    // capping the fixed pre-allocated-shell overhead. Without this cap, maxLogEntries=100000 forced a
    // 131072-slot ring (~14.7MB of empty LogEntry shells) purely as a side effect of retention sizing.
    static final int DEFAULT_MAX_RING_BUFFER_SIZE = 16384;
    private static final String MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS = "mockserver.maxWebSocketExpectations";
    private static final String MOCKSERVER_WEB_SOCKET_PROXY_MAX_RECORDED_FRAMES = "mockserver.webSocketProxyMaxRecordedFrames";
    private static final String MOCKSERVER_WEB_SOCKET_PROXY_IDLE_TIMEOUT_SECONDS = "mockserver.webSocketProxyIdleTimeoutSeconds";
    private static final String MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV = "mockserver.outputMemoryUsageCsv";
    private static final String MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY = "mockserver.memoryUsageCsvDirectory";

    // scalability
    private static final String MOCKSERVER_USE_NATIVE_TRANSPORT = "mockserver.useNativeTransport";
    private static final String MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT = "mockserver.nioEventLoopThreadCount";
    private static final String MOCKSERVER_ACTION_HANDLER_THREAD_COUNT = "mockserver.actionHandlerThreadCount";
    private static final String MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT = "mockserver.clientNioEventLoopThreadCount";
    private static final String MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT = "mockserver.webSocketClientEventLoopThreadCount";
    private static final String MOCKSERVER_MAX_FUTURE_TIMEOUT = "mockserver.maxFutureTimeout";
    // Unit-bearing alias matching Configuration.maxFutureTimeoutInMillis() and the /mockserver/configuration
    // JSON key (same footgun and resolution as the socket-timeout aliases above): a synonym for the primary
    // key, honoured rather than silently ignored, with the primary key read first.
    private static final String MOCKSERVER_MAX_FUTURE_TIMEOUT_IN_MILLIS = "mockserver.maxFutureTimeoutInMillis";
    private static final String MOCKSERVER_MATCHERS_FAIL_FAST = "mockserver.matchersFailFast";
    private static final String MOCKSERVER_MATCH_EXACT_CASE = "mockserver.matchExactCase";
    private static final String MOCKSERVER_FORWARD_CONNECTION_POOL_ENABLED = "mockserver.forwardConnectionPoolEnabled";
    private static final String MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_IDLE_PER_KEY = "mockserver.forwardConnectionPoolMaxIdlePerKey";
    private static final String MOCKSERVER_FORWARD_CONNECTION_POOL_IDLE_TIMEOUT_MILLIS = "mockserver.forwardConnectionPoolIdleTimeoutMillis";
    private static final String MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE = "mockserver.forwardConnectionPoolKeepAlive";
    private static final String MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_TOTAL_PER_KEY = "mockserver.forwardConnectionPoolMaxTotalPerKey";
    private static final String MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE = "mockserver.forwardSocketKeepAlive";
    private static final String MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_IDLE_SECONDS = "mockserver.forwardSocketKeepAliveIdleSeconds";
    private static final String MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_INTERVAL_SECONDS = "mockserver.forwardSocketKeepAliveIntervalSeconds";
    private static final String MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_COUNT = "mockserver.forwardSocketKeepAliveCount";
    private static final String MOCKSERVER_FORWARD_PROXY_RETRY_COUNT = "mockserver.forwardProxyRetryCount";
    private static final String MOCKSERVER_FORWARD_PROXY_RETRY_BACKOFF_MILLIS = "mockserver.forwardProxyRetryBackoffMillis";
    private static final String MOCKSERVER_FORWARD_PROXY_HTTP2_ENABLED = "mockserver.forwardProxyHttp2Enabled";
    private static final String MOCKSERVER_FORWARD_PROXY_HTTP2_UPGRADE = "mockserver.forwardProxyHttp2Upgrade";
    private static final String MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_ENABLED = "mockserver.forwardProxyCircuitBreakerEnabled";
    private static final String MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_FAILURE_THRESHOLD = "mockserver.forwardProxyCircuitBreakerFailureThreshold";
    private static final String MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_WINDOW_MILLIS = "mockserver.forwardProxyCircuitBreakerWindowMillis";
    private static final String MOCKSERVER_ENFORCE_RESPONSE_VALIDATION_FOR_MOCKS = "mockserver.enforceResponseValidationForMocks";
    private static final String MOCKSERVER_VALIDATE_REQUESTS_AGAINST_OPENAPI_SPEC = "mockserver.validateRequestsAgainstOpenApiSpec";

    // socket
    private static final String MOCKSERVER_MAX_SOCKET_TIMEOUT = "mockserver.maxSocketTimeout";
    // `InMillis`-suffixed aliases that match Configuration.maxSocketTimeoutInMillis() /
    // socketConnectionTimeoutInMillis() and the keys reported in the /mockserver/configuration JSON, so an
    // operator who sets -Dmockserver.maxSocketTimeoutInMillis (the natural name to reach for) is honoured
    // rather than silently ignored. These are synonyms for the primary keys; the primary key is read first
    // (so a programmatic/runtime value can never be silently overridden by a launch-time alias) and the
    // alias is used only when the primary is unset. Declared as MOCKSERVER_* constants so the reflective
    // recognised-key registry accepts them too.
    private static final String MOCKSERVER_MAX_SOCKET_TIMEOUT_IN_MILLIS = "mockserver.maxSocketTimeoutInMillis";
    private static final String MOCKSERVER_SOCKET_CONNECTION_TIMEOUT = "mockserver.socketConnectionTimeout";
    private static final String MOCKSERVER_SOCKET_CONNECTION_TIMEOUT_IN_MILLIS = "mockserver.socketConnectionTimeoutInMillis";
    private static final String MOCKSERVER_CONNECTION_DELAY_MILLIS = "mockserver.connectionDelayMillis";
    private static final String MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS = "mockserver.alwaysCloseSocketConnections";
    private static final String MOCKSERVER_LOCAL_BOUND_IP = "mockserver.localBoundIP";

    // http request parsing
    private static final String MOCKSERVER_MAX_INITIAL_LINE_LENGTH = "mockserver.maxInitialLineLength";
    private static final String MOCKSERVER_MAX_HEADER_SIZE = "mockserver.maxHeaderSize";
    private static final String MOCKSERVER_MAX_CHUNK_SIZE = "mockserver.maxChunkSize";
    private static final String MOCKSERVER_MAX_REQUEST_BODY_SIZE = "mockserver.maxRequestBodySize";
    private static final String MOCKSERVER_MAX_GRPC_MESSAGE_SIZE = "mockserver.maxGrpcMessageSize";
    private static final String MOCKSERVER_MAX_RESPONSE_BODY_SIZE = "mockserver.maxResponseBodySize";
    private static final String MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE = "mockserver.maxLlmConversationBodySize";
    private static final String MOCKSERVER_LLM_PROVIDER = "mockserver.llmProvider";
    private static final String MOCKSERVER_LLM_API_KEY = "mockserver.llmApiKey";
    private static final String MOCKSERVER_LLM_MODEL = "mockserver.llmModel";
    private static final String MOCKSERVER_LLM_BASE_URL = "mockserver.llmBaseUrl";
    private static final String MOCKSERVER_LLM_BACKENDS_CONFIG = "mockserver.llmBackendsConfig";
    private static final String MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS = "mockserver.llmRequestTimeoutMillis";
    private static final String MOCKSERVER_DRIFT_DETECTION_ENABLED = "mockserver.driftDetectionEnabled";
    private static final String MOCKSERVER_DRIFT_SAMPLE_RATE = "mockserver.driftSampleRate";
    private static final String MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED = "mockserver.driftSemanticAnalysisEnabled";
    private static final String MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS = "mockserver.driftResponseTimeThresholdMs";
    private static final String MOCKSERVER_DRIFT_ALERT_WEBHOOK_ENABLED = "mockserver.driftAlertWebhookEnabled";
    private static final String MOCKSERVER_DRIFT_ALERT_WEBHOOK_URL = "mockserver.driftAlertWebhookUrl";
    private static final String MOCKSERVER_DRIFT_ALERT_SEVERITY_THRESHOLD = "mockserver.driftAlertSeverityThreshold";
    private static final String MOCKSERVER_DRIFT_ALERT_COOLDOWN_MILLIS = "mockserver.driftAlertCooldownMillis";
    private static final String MOCKSERVER_CONTROL_PLANE_AUDIT_ENABLED = "mockserver.controlPlaneAuditEnabled";
    private static final String MOCKSERVER_CONTROL_PLANE_AUDIT_MAX_ENTRIES = "mockserver.controlPlaneAuditMaxEntries";
    private static final String MOCKSERVER_CONTROL_PLANE_AUDIT_READS = "mockserver.controlPlaneAuditReads";
    private static final String MOCKSERVER_AUDIT_LOG_FILE = "mockserver.auditLogFile";
    private static final String MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS = "mockserver.fixtureBodyRedactFields";
    private static final String MOCKSERVER_LLM_VCR_STRICT = "mockserver.llmVcrStrict";
    private static final String MOCKSERVER_LLM_OPTIMISATION_MAX_CALLS = "mockserver.llmOptimisationMaxCalls";
    private static final String MOCKSERVER_OTEL_METRICS_ENABLED = "mockserver.otelMetricsEnabled";
    private static final String MOCKSERVER_OTEL_TRACES_ENABLED = "mockserver.otelTracesEnabled";
    private static final String MOCKSERVER_OTEL_ENDPOINT = "mockserver.otelEndpoint";
    private static final String MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS = "mockserver.otelMetricsExportIntervalSeconds";
    private static final String MOCKSERVER_OTEL_METRICS_TEMPORALITY = "mockserver.otelMetricsTemporality";
    private static final String MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT = "mockserver.otelPropagateTraceContext";
    private static final String MOCKSERVER_OTEL_GENERATE_TRACE_ID = "mockserver.otelGenerateTraceId";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_ENABLED = "mockserver.prometheusRemoteWriteEnabled";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_URL = "mockserver.prometheusRemoteWriteUrl";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_INTERVAL_SECONDS = "mockserver.prometheusRemoteWriteIntervalSeconds";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BEARER_TOKEN = "mockserver.prometheusRemoteWriteBearerToken";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_USERNAME = "mockserver.prometheusRemoteWriteBasicAuthUsername";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_PASSWORD = "mockserver.prometheusRemoteWriteBasicAuthPassword";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_HEADERS = "mockserver.prometheusRemoteWriteHeaders";
    private static final String MOCKSERVER_PROMETHEUS_REMOTE_WRITE_PROTOCOL_VERSION = "mockserver.prometheusRemoteWriteProtocolVersion";
    private static final String MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED = "mockserver.llmSemanticMatchingEnabled";
    private static final String MOCKSERVER_LLM_INFER_USAGE_ENABLED = "mockserver.llmInferUsageEnabled";
    private static final String MOCKSERVER_LLM_METRICS_ENABLED = "mockserver.llmMetricsEnabled";
    private static final String MOCKSERVER_PER_EXPECTATION_METRICS_ENABLED = "mockserver.perExpectationMetricsEnabled";
    // legacy key kept for backward compatibility — the canonical key above adds the conventional "Enabled" suffix
    private static final String MOCKSERVER_PER_EXPECTATION_METRICS = "mockserver.perExpectationMetrics";
    private static final String MOCKSERVER_DEDUPLICATE_RECORDED_EXPECTATIONS = "mockserver.deduplicateRecordedExpectations";
    private static final String MOCKSERVER_TEMPLATIZE_RECORDED_VALUES = "mockserver.templatizeRecordedValues";
    private static final String MOCKSERVER_REDACT_SECRETS_IN_RECORDED_EXPECTATIONS = "mockserver.redactSecretsInRecordedExpectations";
    private static final String MOCKSERVER_REDACT_SECRETS_IN_LOG = "mockserver.redactSecretsInLog";
    private static final String MOCKSERVER_LLM_COST_BUDGET_USD = "mockserver.llmCostBudgetUsd";
    private static final String MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR = "mockserver.useSemicolonAsQueryParameterSeparator";
    private static final String MOCKSERVER_STARTUP_WARMUP = "mockserver.startupWarmup";
    private static final String MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP = "mockserver.assumeAllRequestsAreHttp";
    private static final String MOCKSERVER_HTTP2_ENABLED = "mockserver.http2Enabled";

    // matcher safety
    private static final String MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS = "mockserver.regexMatchingTimeoutMillis";
    private static final String MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS = "mockserver.xpathMatchingTimeoutMillis";

    // body matching extensions
    private static final String MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS = "mockserver.customJsonUnitMatchersClass";

    // WASM
    private static final String MOCKSERVER_WASM_ENABLED = "mockserver.wasmEnabled";
    private static final String MOCKSERVER_WASM_MAX_MEMORY_PAGES = "mockserver.wasmMaxMemoryPages";
    private static final String MOCKSERVER_WASM_EXECUTION_TIMEOUT_MILLIS = "mockserver.wasmExecutionTimeoutMillis";

    // gRPC
    private static final String MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY = "mockserver.grpcDescriptorDirectory";
    private static final String MOCKSERVER_GRPC_PROTO_DIRECTORY = "mockserver.grpcProtoDirectory";
    private static final String MOCKSERVER_GRPC_ENABLED = "mockserver.grpcEnabled";
    private static final String MOCKSERVER_GRPC_PROTOC_PATH = "mockserver.grpcProtocPath";
    private static final String MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED = "mockserver.grpcBidiStreamingEnabled";
    private static final String MOCKSERVER_DNS_ENABLED = "mockserver.dnsEnabled";
    private static final String MOCKSERVER_DNS_PORT = "mockserver.dnsPort";
    private static final String MOCKSERVER_HTTP3_PORT = "mockserver.http3Port";
    private static final String MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT = "mockserver.http3MaxIdleTimeout";
    private static final String MOCKSERVER_HTTP3_INITIAL_MAX_DATA = "mockserver.http3InitialMaxData";
    private static final String MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL = "mockserver.http3InitialMaxStreamDataBidirectional";
    private static final String MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL = "mockserver.http3InitialMaxStreamsBidirectional";
    private static final String MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY = "mockserver.http3QpackMaxTableCapacity";
    private static final String MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED = "mockserver.http3ConnectUdpEnabled";
    private static final String MOCKSERVER_HTTP3_CONNECT_UDP_ALLOWED_TARGETS = "mockserver.http3ConnectUdpAllowedTargets";
    private static final String MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE = "mockserver.http3AltSvcMaxAge";
    private static final String MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC = "mockserver.http3AdvertiseAltSvc";

    // non http proxying
    private static final String MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE = "mockserver.forwardBinaryRequestsWithoutWaitingForResponse";

    // streaming proxy
    private static final String MOCKSERVER_STREAMING_RESPONSES_ENABLED = "mockserver.streamingResponsesEnabled";
    private static final String MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES = "mockserver.maxStreamingCaptureBytes";
    private static final String MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS = "mockserver.streamIdleTimeoutSeconds";

    // CORS
    private static final String MOCKSERVER_ENABLE_CORS_FOR_API = "mockserver.enableCORSForAPI";
    private static final String MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES = "mockserver.enableCORSForAllResponses";
    private static final String MOCKSERVER_CORS_ALLOW_ORIGIN = "mockserver.corsAllowOrigin";
    private static final String MOCKSERVER_CORS_ALLOW_METHODS = "mockserver.corsAllowMethods";
    private static final String MOCKSERVER_CORS_ALLOW_HEADERS = "mockserver.corsAllowHeaders";
    private static final String MOCKSERVER_CORS_ALLOW_CREDENTIALS = "mockserver.corsAllowCredentials";
    private static final String MOCKSERVER_CORS_MAX_AGE_IN_SECONDS = "mockserver.corsMaxAgeInSeconds";

    // default response headers
    private static final String MOCKSERVER_DEFAULT_RESPONSE_HEADERS = "mockserver.defaultResponseHeaders";

    // template restrictions
    private static final String MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES = "mockserver.javascriptDisallowedClasses";
    private static final String MOCKSERVER_JAVASCRIPT_ALLOWED_CLASSES = "mockserver.javascriptAllowedClasses";
    private static final String MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT = "mockserver.javascriptDisallowedText";
    private static final String MOCKSERVER_JAVASCRIPT_TEMPLATE_EXECUTION_TIMEOUT = "mockserver.javascriptTemplateExecutionTimeout";
    private static final String MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING = "mockserver.velocityDisallowClassLoading";
    private static final String MOCKSERVER_VELOCITY_DISALLOWED_TEXT = "mockserver.velocityDisallowedText";
    private static final String MOCKSERVER_MUSTACHE_DISALLOWED_TEXT = "mockserver.mustacheDisallowedText";
    private static final String MOCKSERVER_TEMPLATE_FAKER_SEED = "mockserver.templateFakerSeed";

    // mock initialization
    private static final String MOCKSERVER_INITIALIZATION_CLASS = "mockserver.initializationClass";
    private static final String MOCKSERVER_INITIALIZATION_JSON_PATH = "mockserver.initializationJsonPath";
    private static final String MOCKSERVER_INITIALIZATION_OPENAPI_PATH = "mockserver.initializationOpenAPIPath";
    private static final String MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX = "mockserver.openAPIContextPathPrefix";
    private static final String MOCKSERVER_OPENAPI_RESPONSE_VALIDATION = "mockserver.openAPIResponseValidation";
    private static final String MOCKSERVER_VALIDATE_PROXY_OPENAPI_SPEC = "mockserver.validateProxyOpenAPISpec";
    private static final String MOCKSERVER_VALIDATE_PROXY_ENFORCE = "mockserver.validateProxyEnforce";
    private static final String MOCKSERVER_GENERATE_REALISTIC_EXAMPLE_VALUES = "mockserver.generateRealisticExampleValues";
    private static final String MOCKSERVER_WATCH_INITIALIZATION_JSON = "mockserver.watchInitializationJson";
    private static final String MOCKSERVER_FAIL_ON_INITIALIZATION_ERROR = "mockserver.failOnInitializationError";

    // mock persistence
    private static final String MOCKSERVER_PERSIST_EXPECTATIONS = "mockserver.persistExpectations";
    private static final String MOCKSERVER_PERSISTED_EXPECTATIONS_PATH = "mockserver.persistedExpectationsPath";

    // recorded expectation persistence
    private static final String MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS = "mockserver.persistRecordedExpectations";
    private static final String MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH = "mockserver.persistedRecordedExpectationsPath";

    // recorded request persistence (LLM-capture disk-offload — config plumbing only, behaviour added later)
    private static final String MOCKSERVER_PERSIST_RECORDED_REQUESTS_TO_DISK = "mockserver.persistRecordedRequestsToDisk";
    private static final String MOCKSERVER_PERSISTED_RECORDED_REQUESTS_PATH = "mockserver.persistedRecordedRequestsPath";

    // state backend (G10 phase 2a)
    private static final String MOCKSERVER_STATE_BACKEND = "mockserver.stateBackend";
    private static final String MOCKSERVER_BLOB_STORE_TYPE = "mockserver.blobStoreType";

    // cloud blob store configuration
    private static final String MOCKSERVER_BLOB_STORE_BUCKET = "mockserver.blobStoreBucket";
    private static final String MOCKSERVER_BLOB_STORE_REGION = "mockserver.blobStoreRegion";
    private static final String MOCKSERVER_BLOB_STORE_ENDPOINT = "mockserver.blobStoreEndpoint";
    private static final String MOCKSERVER_BLOB_STORE_KEY_PREFIX = "mockserver.blobStoreKeyPrefix";
    private static final String MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID = "mockserver.blobStoreAccessKeyId";
    private static final String MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY = "mockserver.blobStoreSecretAccessKey";
    private static final String MOCKSERVER_BLOB_STORE_CONTAINER = "mockserver.blobStoreContainer";
    private static final String MOCKSERVER_BLOB_STORE_CONNECTION_STRING = "mockserver.blobStoreConnectionString";
    private static final String MOCKSERVER_BLOB_STORE_PROJECT_ID = "mockserver.blobStoreProjectId";
    private static final String MOCKSERVER_BLOB_STORE_RESTORE_TIMEOUT_SECONDS = "mockserver.blobStoreRestoreTimeoutSeconds";

    // clustering (G10 phase 2c)
    private static final String MOCKSERVER_CLUSTER_ENABLED = "mockserver.clusterEnabled";
    private static final String MOCKSERVER_CLUSTER_NAME = "mockserver.clusterName";
    private static final String MOCKSERVER_CLUSTER_TRANSPORT_CONFIG = "mockserver.clusterTransportConfig";
    private static final String MOCKSERVER_CLUSTER_SHARED_TIMES_ENABLED = "mockserver.clusterSharedTimesEnabled";
    private static final String MOCKSERVER_CLUSTER_VERIFY_FAN_IN = "mockserver.clusterVerifyFanIn";
    private static final String MOCKSERVER_CLUSTER_VERIFY_FAN_IN_PEERS = "mockserver.clusterVerifyFanInPeers";
    private static final String MOCKSERVER_CLUSTER_FAN_IN_PEER_AUTH_TOKEN = "mockserver.clusterFanInPeerAuthToken";

    // verification
    private static final String MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE = "mockserver.maximumNumberOfRequestToReturnInVerificationFailure";
    private static final String MOCKSERVER_DETAILED_VERIFICATION_FAILURES = "mockserver.detailedVerificationFailures";
    private static final String MOCKSERVER_FAIL_VERIFICATION_ON_EVICTED_LOG = "mockserver.failVerificationOnEvictedLog";
    private static final String MOCKSERVER_ATTACH_MISMATCH_DIAGNOSTIC_TO_RESPONSE = "mockserver.attachMismatchDiagnosticToResponse";
    private static final String MOCKSERVER_CLOSEST_MATCH_HINT_ENABLED = "mockserver.closestMatchHintEnabled";

    // proxy
    private static final String MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION = "mockserver.attemptToProxyIfNoMatchingExpectation";
    private static final String MOCKSERVER_FORWARD_HTTP_PROXY = "mockserver.forwardHttpProxy";
    private static final String MOCKSERVER_FORWARD_HTTPS_PROXY = "mockserver.forwardHttpsProxy";
    private static final String MOCKSERVER_FORWARD_SOCKS_PROXY = "mockserver.forwardSocksProxy";
    private static final String MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME = "mockserver.forwardProxyAuthenticationUsername";
    private static final String MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD = "mockserver.forwardProxyAuthenticationPassword";
    private static final String MOCKSERVER_PROXY_SERVER_REALM = "mockserver.proxyAuthenticationRealm";
    private static final String MOCKSERVER_PROXY_AUTHENTICATION_USERNAME = "mockserver.proxyAuthenticationUsername";
    private static final String MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD = "mockserver.proxyAuthenticationPassword";
    // data plane (mocked endpoint) authentication — opt-in, default off
    private static final String MOCKSERVER_DATA_PLANE_AUTHENTICATION_REQUIRED = "mockserver.dataPlaneAuthenticationRequired";
    private static final String MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_USERNAME = "mockserver.dataPlaneBasicAuthenticationUsername";
    private static final String MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_PASSWORD = "mockserver.dataPlaneBasicAuthenticationPassword";
    private static final String MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_REALM = "mockserver.dataPlaneBasicAuthenticationRealm";
    private static final String MOCKSERVER_DATA_PLANE_BEARER_AUTHENTICATION_TOKEN = "mockserver.dataPlaneBearerAuthenticationToken";
    private static final String MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_HEADER = "mockserver.dataPlaneApiKeyAuthenticationHeader";
    private static final String MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_VALUE = "mockserver.dataPlaneApiKeyAuthenticationValue";
    private static final String MOCKSERVER_NO_PROXY_HOSTS = "mockserver.noProxyHosts";
    private static final String MOCKSERVER_PROXY_REMOTE_HOST = "mockserver.proxyRemoteHost";
    private static final String MOCKSERVER_PROXY_REMOTE_PORT = "mockserver.proxyRemotePort";
    private static final String MOCKSERVER_FORWARD_ADJUST_HOST_HEADER = "mockserver.forwardAdjustHostHeader";
    private static final String MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER = "mockserver.forwardDefaultHostHeader";
    private static final String MOCKSERVER_PROXY_PASS = "mockserver.proxyPass";
    private static final String MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS = "mockserver.globalResponseDelayMillis";

    // liveness
    private static final String MOCKSERVER_LIVENESS_HTTP_GET_PATH = "mockserver.livenessHttpGetPath";

    // expectation namespacing / multi-tenancy
    private static final String MOCKSERVER_MATCH_NAMESPACE_HEADER = "mockserver.matchNamespaceHeader";
    private static final String DEFAULT_MATCH_NAMESPACE_HEADER = "X-MockServer-Namespace";

    // control plane authentication
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED = "mockserver.controlPlaneTLSMutualAuthenticationRequired";
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN = "mockserver.controlPlaneTLSMutualAuthenticationCAChain";
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH = "mockserver.controlPlanePrivateKeyPath";
    private static final String MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH = "mockserver.controlPlaneX509CertificatePath";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED = "mockserver.controlPlaneJWTAuthenticationRequired";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE = "mockserver.controlPlaneJWTAuthenticationJWKSource";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE = "mockserver.controlPlaneJWTAuthenticationExpectedAudience";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS = "mockserver.controlPlaneJWTAuthenticationMatchingClaims";
    private static final String MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS = "mockserver.controlPlaneJWTAuthenticationRequiredClaims";
    private static final String MOCKSERVER_CONTROL_PLANE_OIDC_AUTHENTICATION_REQUIRED = "mockserver.controlPlaneOidcAuthenticationRequired";
    private static final String MOCKSERVER_CONTROL_PLANE_OIDC_ISSUER = "mockserver.controlPlaneOidcIssuer";
    private static final String MOCKSERVER_CONTROL_PLANE_OIDC_JWKS_URI = "mockserver.controlPlaneOidcJwksUri";
    private static final String MOCKSERVER_CONTROL_PLANE_OIDC_AUDIENCE = "mockserver.controlPlaneOidcAudience";
    private static final String MOCKSERVER_CONTROL_PLANE_OIDC_REQUIRED_SCOPES = "mockserver.controlPlaneOidcRequiredScopes";
    private static final String MOCKSERVER_CONTROL_PLANE_OIDC_SCOPE_CLAIM = "mockserver.controlPlaneOidcScopeClaim";
    private static final String MOCKSERVER_CONTROL_PLANE_AUTHORIZATION_ENABLED = "mockserver.controlPlaneAuthorizationEnabled";
    private static final String MOCKSERVER_CONTROL_PLANE_SCOPE_MAPPING = "mockserver.controlPlaneScopeMapping";

    // TLS
    private static final String MOCKSERVER_PROACTIVELY_INITIALISE_TLS = "mockserver.proactivelyInitialiseTLS";
    private static final String MOCKSERVER_TLS_PROTOCOLS = "mockserver.tlsProtocols";
    private static final String MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS = "mockserver.tlsAllowInsecureProtocols";

    // inbound - dynamic CA
    private static final String MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE = "mockserver.dynamicallyCreateCertificateAuthorityCertificate";
    private static final String MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE = "mockserver.directoryToSaveDynamicSSLCertificate";

    // proxy setup convenience
    private static final String MOCKSERVER_PROXY_SETUP = "mockserver.proxySetup";
    private static final String MOCKSERVER_PROXY_SETUP_LOGGING = "mockserver.proxySetupLogging";

    // inbound - dynamic private key & x509
    private static final String MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE = "mockserver.preventCertificateDynamicUpdate";
    private static final String MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME = "mockserver.sslCertificateDomainName";
    private static final String MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS = "mockserver.sslSubjectAlternativeNameDomains";
    private static final String MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS = "mockserver.sslSubjectAlternativeNameIps";

    // inbound - fixed CA
    // inbound - fixed CA
    private static final String MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY = "mockserver.certificateAuthorityPrivateKey";
    private static final String MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE = "mockserver.certificateAuthorityCertificate";
    public static final String DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY = "org/mockserver/socket/PKCS8CertificateAuthorityPrivateKey.pem";
    public static final String DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE = "org/mockserver/socket/CertificateAuthorityCertificate.pem";

    // inbound - fixed private key & x509
    private static final String MOCKSERVER_TLS_PRIVATE_KEY_PATH = "mockserver.privateKeyPath";
    private static final String MOCKSERVER_TLS_X509_CERTIFICATE_PATH = "mockserver.x509CertificatePath";

    // inbound - mTLS
    private static final String MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED = "mockserver.tlsMutualAuthenticationRequired";
    private static final String MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN = "mockserver.tlsMutualAuthenticationCertificateChain";

    // outbound - CA
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE = "mockserver.forwardProxyTLSX509CertificatesTrustManagerType";

    // outbound - SSRF protection
    private static final String MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS = "mockserver.forwardProxyBlockPrivateNetworks";

    // outbound - fixed CA
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES = "mockserver.forwardProxyTLSCustomTrustX509Certificates";

    // outbound - fixed private key & x509
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY = "mockserver.forwardProxyPrivateKey";
    private static final String MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN = "mockserver.forwardProxyCertificateChain";
    private static final String MOCKSERVER_FORWARD_PROXY_CLIENT_CERTIFICATES_BY_HOST = "mockserver.forwardProxyClientCertificatesByHost";

    // service mesh / sidecar
    private static final String MOCKSERVER_TRANSPARENT_PROXY_ENABLED = "mockserver.transparentProxyEnabled";
    private static final String MOCKSERVER_TRANSPARENT_PROXY_TPROXY = "mockserver.transparentProxyTproxy";
    private static final String MOCKSERVER_TRANSPARENT_PROXY_EBPF = "mockserver.transparentProxyEbpf";
    private static final String MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH = "mockserver.transparentProxyEbpfMapPath";

    // async messaging defaults
    private static final String MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS = "mockserver.asyncKafkaBootstrapServers";
    private static final String MOCKSERVER_ASYNC_MQTT_BROKER_URL = "mockserver.asyncMqttBrokerUrl";
    private static final String MOCKSERVER_ASYNC_AMQP_URI = "mockserver.asyncAmqpUri";
    private static final String MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES = "mockserver.asyncRecordedMessageMaxEntries";

    // properties file
    private static final String MOCKSERVER_PROPERTY_FILE = "mockserver.propertyFile";

    // Declared BEFORE PROPERTIES on purpose: the PROPERTIES initialiser runs readPropertyFile() during
    // <clinit>, which redacts sensitive values via isSensitivePropertyName() — and that reads these two
    // fields. Java initialises static fields in textual order, so moving these below PROPERTIES leaves
    // them null when readPropertyFile() runs and throws NoClassDefFoundError at startup whenever a
    // property file has entries (regression #2338). Keep them above PROPERTIES.
    //
    // public (was package-private) so ConfigurationDTO in org.mockserver.serialization.model can reuse
    // the SAME mask for write-only credential properties over the configuration control plane, rather
    // than inventing a second, divergent redaction token.
    public static final String REDACTED_VALUE = "***REDACTED***";

    // Matched as substrings of the lower-cased, mockserver.-stripped name — see
    // isSensitivePropertyName. Also reached, via EmbeddedCredentialRedaction.isSensitiveName, by every
    // HEADER name and JSON FIELD name inside a structured value.
    //
    // NOTE the four underscore forms (access_key, api_key, private_key, connection_string) are dead on
    // the header/field path: isSensitiveName strips "-" and "_" before delegating here, so an
    // "api_key" header has already become "apikey". They still match a property FILE key written with
    // underscores, which is why they stay. Do not add another underscore form expecting it to catch a
    // header — add the unpunctuated spelling instead.
    //
    // Names that are credential-bearing as HEADERS but ordinary as PROPERTY names (authentication,
    // jwt) belong in EmbeddedCredentialRedaction.HEADER_ONLY_SENSITIVE_SUBSTRINGS, not here: this set
    // masks property names too, and hiding tlsMutualAuthenticationRequired from --print-config helps
    // nobody. Everything here was checked against all 303 property names for over-matching.
    private static final Set<String> SENSITIVE_SUBSTRINGS = Stream.of(
        "password",
        // the abbreviated spellings are NOT substrings of "password" and do not end in "key", so
        // neither rule in isSensitivePropertyName reached them (the import redactor already lists both)
        "passwd",
        "pwd",
        "secret",
        "accesskey",
        "access_key",
        "apikey",
        "api_key",
        "connectionstring",
        "connection_string",
        "token",
        "bearer",
        "privatekey",
        "private_key",
        "credential",
        "passphrase",
        // HMAC/webhook signing material — X-Signature and X-Hub-Signature-256 are the GitHub webhook
        // convention, and a signing salt is as disclosing as the key it is mixed with
        "signature",
        "hmac",
        "salt",
        // session and one-time-password credentials: a session id is a bearer credential in a cookie
        // or an X-Session-Id header, and an OTP is a single-use one
        "session",
        "otp"
    ).collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * Property names (lower-cased, {@code mockserver.} prefix stripped) that end in {@code key} but are
     * NOT credentials, so the "ends in key" rule in {@link #isSensitivePropertyName} must not redact them.
     * These are connection-pool sizing limits keyed by route — the word "key" refers to the pool key, not
     * to a secret. Keep this list SHORT and justified: everything ending in {@code key} is treated as a
     * credential unless it appears here, which is the safe direction to fail.
     */
    private static final Set<String> NON_SENSITIVE_KEY_SUFFIXED_NAMES = Stream.of(
        "forwardconnectionpoolmaxidleperkey",
        "forwardconnectionpoolmaxtotalperkey"
    ).collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * Properties whose value is a {@code k=v,k2=v2} list that can carry a credential-bearing header.
     * The whole value is not a secret, so it is redacted per header rather than masked whole — see
     * {@link #redactSensitiveValue(String, String)}. Keyed on the normalised (lower-cased,
     * {@code mockserver.}-stripped) name.
     *
     * <p>Declared BEFORE PROPERTIES for the same reason as the two sets above: the PROPERTIES
     * initialiser runs readPropertyFile() during {@code <clinit>}, whose log dump redacts through
     * {@link #redactSensitiveValue(String, String)}, which reads these — a later declaration leaves
     * them null at that point.
     */
    private static final Set<String> HEADER_LIST_CREDENTIAL_PROPERTIES = Stream.of(
        "prometheusremotewriteheaders"
    ).collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * Properties whose value may be a JSON document carrying credential-bearing fields, redacted per
     * field. {@code llmBackendsConfig}'s documented shape is a file PATH (which is not a secret and is
     * returned unchanged); an inline document is redacted as defence-in-depth.
     */
    private static final Set<String> JSON_DOCUMENT_CREDENTIAL_PROPERTIES = Stream.of(
        "llmbackendsconfig"
    ).collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * The properties {@code GET /mockserver/configuration} returns as the bare mask — and ONLY those.
     * For these, and only these, a {@code PUT} carrying the bare mask is the blessed GET-then-PUT round
     * trip rather than an operator error, so it is refused SILENTLY (see
     * {@link #restoreRedactedValue(String, String, String)}).
     *
     * <p>Deliberately NOT {@link #isSensitivePropertyName(String)}. That predicate is far wider: it
     * matches every credential-shaped name, including the ones {@code GET /mockserver/configuration}
     * <em>omits</em> ({@code proxyAuthenticationPassword}, {@code dataPlaneBearerAuthenticationToken},
     * {@code clusterFanInPeerAuthToken}, the blob-store credentials, the private-key paths, …) and the
     * ones it returns in clear ({@code privateKeyPath}, {@code dashboardAnalyticsKey}, …). None of those
     * has a round trip that can produce the bare mask: the only surfaces that print it for them are
     * {@code GET /mockserver/config}, {@code --print-config} and the dashboard's Server Info tab, none
     * of which is a {@code PUT} body. So for them the bare mask is ALWAYS operator error — the very
     * case whose silence made a destroyed credential undiagnosable — and it must warn.
     *
     * <p>Keyed on the normalised (lower-cased, {@code mockserver.}-stripped) name, and declared here
     * for the same {@code <clinit>} ordering reason as the two sets above.
     * {@code ConfigurationDTOCredentialMaskingTest} asserts this set is exactly the set of DTO
     * properties whose getter really does return the bare mask, so the two cannot drift.
     */
    private static final Set<String> WHOLE_VALUE_MASKED_CREDENTIAL_PROPERTIES = Stream.of(
        "llmapikey",
        "prometheusremotewritebearertoken",
        "prometheusremotewritebasicauthpassword"
    ).collect(Collectors.toCollection(LinkedHashSet::new));

    /**
     * True when {@code GET /mockserver/configuration} returns this property as the bare redaction mask,
     * so an incoming bare mask is the untouched round trip rather than a hand-typed value. Exposed for
     * the guard that keeps {@link #WHOLE_VALUE_MASKED_CREDENTIAL_PROPERTIES} in step with the DTO.
     */
    public static boolean isWholeValueMaskedOnRead(String propertyName) {
        return propertyName != null
            && WHOLE_VALUE_MASKED_CREDENTIAL_PROPERTIES.contains(normalisePropertyName(propertyName));
    }

    public static final Properties PROPERTIES = readPropertyFile();

    // --- Unknown-configuration-key detection state (see the section near the end of this class) ---
    // Declared BEFORE the static initializer below because that block calls
    // warnAboutUnknownConfigurationProperties(), which reads these fields during <clinit>; a later
    // declaration would leave them null at that point (the previous primitive boolean tolerated
    // that via its default value, an AtomicBoolean / Set.of(...) does not).

    // Legitimate keys handled by the CLI launcher (org.mockserver.cli.Main / its Arguments enum)
    // or exported by the binary-launcher scripts (scripts/build-binary-bundle.sh), NOT declared as
    // MOCKSERVER_* String constants in this class. Without this allowlist these documented keys
    // would be wrongly flagged as typos. Keep each entry — do not delete without re-checking the
    // referenced source, because that would reintroduce a false-positive warning.
    //
    //   mockserver.serverPort     - Arguments.serverPort.systemPropertyName(); the primary documented
    //                               port knob (-Dmockserver.serverPort / --env MOCKSERVER_SERVER_PORT);
    //                               resolved in Main.startServer, never read via a constant here.
    //   mockserver.mockServerPort - set by System.setProperty in mockserver-maven-plugin
    //                               (MockServerAbstractMojo.mockServerPort) and read by MockServerPort.
    //   mockserver.launcherName   - read by Main.launcherName() to label usage text; Main explicitly
    //                               excludes it from the resolved-config dump.
    private static final Set<String> EXTRA_RECOGNISED_SYSTEM_PROPERTY_KEYS = Set.of(
        "mockserver.serverPort",
        "mockserver.mockServerPort",
        "mockserver.launcherName"
    );

    //   MOCKSERVER_SERVER_PORT - Arguments.serverPort.longEnvironmentVariableName(); the documented
    //                            Docker port env var (the short form SERVER_PORT lacks the MOCKSERVER_
    //                            prefix so is never flagged). Resolved in Main.startServer.
    //   MOCKSERVER_LAUNCHER    - exported by the binary-launcher scripts (build-binary-bundle.sh) as an
    //                            internal usage-text hint; Main.startServer explicitly excludes it.
    //   MOCKSERVER_JAVA_OPTS   - read by the binary-launcher scripts to pass extra JVM options; it
    //                            remains in the launched JVM's environment, so guard against flagging it.
    private static final Set<String> EXTRA_RECOGNISED_ENV_KEYS = Set.of(
        "MOCKSERVER_SERVER_PORT",
        "MOCKSERVER_LAUNCHER",
        "MOCKSERVER_JAVA_OPTS"
    );

    private static final AtomicBoolean unknownConfigurationPropertiesChecked = new AtomicBoolean(false);

    static {
        // Apply the configured log level to java.util.logging once PROPERTIES is loaded.
        // MockServerLogger.<clinit> installs only the default format (it no longer reads
        // ConfigurationProperties), so this is the point at which a configured level
        // (e.g. -Dmockserver.logLevel=DEBUG) is pushed into java.util.logging at startup.
        // The dependency is one-way (ConfigurationProperties -> MockServerLogger), so there
        // is no class-init cycle.
        MockServerLogger.configureLogger();

        // Warn about any mockserver.* system property, MOCKSERVER_* environment variable, or
        // mockserver.* properties-file key that is not a recognised configuration property. This
        // runs once here (during <clinit>, after PROPERTIES is loaded) so it fires regardless of
        // whether a property file is present — env-only / -D-only deployments are checked too.
        warnAboutUnknownConfigurationProperties();
    }

    private static Map<String, String> slf4jOrJavaLoggerToJavaLoggerLevelMapping;

    private static Map<String, String> slf4jOrJavaLoggerToSLF4JLevelMapping;

    private static Map<String, String> getSLF4JOrJavaLoggerToJavaLoggerLevelMapping() {
        if (slf4jOrJavaLoggerToJavaLoggerLevelMapping == null) {
            slf4jOrJavaLoggerToJavaLoggerLevelMapping = ImmutableMap
                .<String, String>builder()
                .put("TRACE", "FINEST")
                .put("DEBUG", "FINE")
                .put("INFO", "INFO")
                .put("WARN", "WARNING")
                .put("ERROR", "SEVERE")
                .put("FINEST", "FINEST")
                .put("FINE", "FINE")
                .put("WARNING", "WARNING")
                .put("SEVERE", "SEVERE")
                .put("OFF", "OFF")
                .build();
        }
        return slf4jOrJavaLoggerToJavaLoggerLevelMapping;
    }

    private static Map<String, String> getSLF4JOrJavaLoggerToSLF4JLevelMapping() {
        if (slf4jOrJavaLoggerToSLF4JLevelMapping == null) {
            slf4jOrJavaLoggerToSLF4JLevelMapping = ImmutableMap
                .<String, String>builder()
                .put("FINEST", "TRACE")
                .put("FINE", "DEBUG")
                .put("INFO", "INFO")
                .put("WARNING", "WARN")
                .put("SEVERE", "ERROR")
                .put("TRACE", "TRACE")
                .put("DEBUG", "DEBUG")
                .put("WARN", "WARN")
                .put("ERROR", "ERROR")
                .put("OFF", "ERROR")
                .build();
        }
        return slf4jOrJavaLoggerToSLF4JLevelMapping;
    }

    private static String propertyFile() {
        if (isNotBlank(System.getProperty(MOCKSERVER_PROPERTY_FILE)) && System.getProperty(MOCKSERVER_PROPERTY_FILE).equals("/config/mockserver.properties")) {
            return isBlank(System.getenv("MOCKSERVER_PROPERTY_FILE")) ? System.getProperty(MOCKSERVER_PROPERTY_FILE) : System.getenv("MOCKSERVER_PROPERTY_FILE");
        } else {
            return System.getProperty(MOCKSERVER_PROPERTY_FILE, isBlank(System.getenv("MOCKSERVER_PROPERTY_FILE")) ? "mockserver.properties" : System.getenv("MOCKSERVER_PROPERTY_FILE"));
        }
    }

    // logging

    public static Level logLevel() {
        String logLevel = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOG_LEVEL, "MOCKSERVER_LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase();
        if (isNotBlank(logLevel)) {
            String slf4jLevel = getSLF4JOrJavaLoggerToSLF4JLevelMapping().get(logLevel);
            if (slf4jLevel == null) {
                throw new IllegalArgumentException("log level \"" + logLevel + "\" is not legal it must be one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\", or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\"");
            }
            if (slf4jLevel.equals("OFF")) {
                return null;
            } else {
                return Level.valueOf(slf4jLevel);
            }
        } else {
            return Level.INFO;
        }
    }

    public static String javaLoggerLogLevel() {
        String logLevel = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOG_LEVEL, "MOCKSERVER_LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase();
        if (isNotBlank(logLevel)) {
            String javaLoggerLevel = getSLF4JOrJavaLoggerToJavaLoggerLevelMapping().get(logLevel);
            if (javaLoggerLevel == null) {
                throw new IllegalArgumentException("log level \"" + logLevel + "\" is not legal it must be one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\", or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\"");
            }
            if (javaLoggerLevel.equals("OFF")) {
                return "OFF";
            } else {
                return javaLoggerLevel;
            }
        } else {
            return "INFO";
        }
    }

    /**
     * Override the default logging level of INFO
     *
     * @param level the log level, which can be TRACE, DEBUG, INFO, WARN, ERROR, OFF, FINEST, FINE, INFO, WARNING, SEVERE
     */
    public static void logLevel(String level) {
        if (isNotBlank(level)) {
            if (!getSLF4JOrJavaLoggerToSLF4JLevelMapping().containsKey(level)) {
                throw new IllegalArgumentException("log level \"" + level + "\" is not legal it must be one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\", or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\"");
            }
            setProperty(MOCKSERVER_LOG_LEVEL, level);
        }
        configureLogger();
    }

    public static void temporaryLogLevel(String level, Runnable runnable) {
        Level originalLogLevel = logLevel();
        try {
            logLevel(level);
            runnable.run();
        } finally {
            if (originalLogLevel != null) {
                logLevel(originalLogLevel.name());
            }
        }
    }

    public static boolean disableSystemOut() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DISABLE_SYSTEM_OUT, "MOCKSERVER_DISABLE_SYSTEM_OUT", "" + false));
    }

    /**
     * Disable printing log to system out for JVM, default is enabled
     *
     * @param disable printing log to system out for JVM
     */
    public static void disableSystemOut(boolean disable) {
        setProperty(MOCKSERVER_DISABLE_SYSTEM_OUT, "" + disable);
        configureLogger();
    }

    public static boolean disableLogging() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DISABLE_LOGGING, "MOCKSERVER_DISABLE_LOGGING", "" + false));
    }

    /**
     * Disable all logging and processing of log events
     * <p>
     * The default is false
     *
     * @param disable disable all logging
     */
    public static void disableLogging(boolean disable) {
        setProperty(MOCKSERVER_DISABLE_LOGGING, "" + disable);
        configureLogger();
    }

    public static boolean detailedMatchFailures() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DETAILED_MATCH_FAILURES, "MOCKSERVER_DETAILED_MATCH_FAILURES", "" + true));
    }

    /**
     * If true (the default) the log event recording that a request matcher did not match will include a detailed reason why each non matching field did not match.
     *
     * @param enable enabled detailed match failure log events
     */
    public static void detailedMatchFailures(boolean enable) {
        setProperty(MOCKSERVER_DETAILED_MATCH_FAILURES, "" + enable);
    }

    public static boolean launchUIForLogLevelDebug() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG, "MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG", "" + false));
    }

    /**
     * If true the ClientAndServer constructor will open the UI in the default browser when the log level is set to DEBUG. Default is false.
     *
     * @param enable enabled ClientAndServer constructor launching UI when log level is DEBUG
     */
    public static void launchUIForLogLevelDebug(boolean enable) {
        setProperty(MOCKSERVER_LAUNCH_UI_FOR_LOG_LEVEL_DEBUG, "" + enable);
    }

    public static boolean metricsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_METRICS_ENABLED, "MOCKSERVER_METRICS_ENABLED", "" + false));
    }

    /**
     * Enable gathering of metrics, default is false
     *
     * @param enable enable metrics
     */
    public static void metricsEnabled(boolean enable) {
        setProperty(MOCKSERVER_METRICS_ENABLED, "" + enable);
    }

    public static boolean dashboardAnalyticsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DASHBOARD_ANALYTICS_ENABLED, "MOCKSERVER_DASHBOARD_ANALYTICS_ENABLED", "" + true));
    }

    /**
     * Master kill switch for browser dashboard usage analytics, default is true.
     * Analytics remains inert until an endpoint and key are also supplied.
     *
     * @param enable enable dashboard analytics
     */
    public static void dashboardAnalyticsEnabled(boolean enable) {
        setProperty(MOCKSERVER_DASHBOARD_ANALYTICS_ENABLED, "" + enable);
    }

    public static String dashboardAnalyticsEndpoint() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DASHBOARD_ANALYTICS_ENDPOINT, "MOCKSERVER_DASHBOARD_ANALYTICS_ENDPOINT", "");
    }

    /**
     * Analytics endpoint (PostHog api_host) the browser dashboard sends usage analytics to.
     * Default is empty, which leaves dashboard analytics disabled.
     *
     * @param endpoint analytics endpoint host
     */
    public static void dashboardAnalyticsEndpoint(String endpoint) {
        setProperty(MOCKSERVER_DASHBOARD_ANALYTICS_ENDPOINT, endpoint);
    }

    public static String dashboardAnalyticsKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DASHBOARD_ANALYTICS_KEY, "MOCKSERVER_DASHBOARD_ANALYTICS_KEY", "");
    }

    /**
     * Write-only analytics project key the browser dashboard uses when sending usage analytics.
     * Default is empty, which leaves dashboard analytics disabled.
     *
     * @param key analytics project key
     */
    public static void dashboardAnalyticsKey(String key) {
        setProperty(MOCKSERVER_DASHBOARD_ANALYTICS_KEY, key);
    }

    public static String dashboardAnalyticsDistribution() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION, "MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION", "");
    }

    /**
     * Label identifying which MockServer distribution/artefact a dashboard analytics event came from.
     * Default is empty.
     *
     * @param distribution analytics distribution label
     */
    public static void dashboardAnalyticsDistribution(String distribution) {
        setProperty(MOCKSERVER_DASHBOARD_ANALYTICS_DISTRIBUTION, distribution);
    }

    public static long slowRequestThresholdMillis() {
        return readLongProperty(MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS, "MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS", 0L);
    }

    /**
     * Threshold in milliseconds for flagging slow forwarded requests. When a forwarded
     * request's total time exceeds this threshold, a WARN-level log entry is emitted and
     * the {@code mock_server_slow_requests_total} Prometheus counter is incremented.
     * <p>
     * Default is 0 (disabled).
     *
     * @param milliseconds threshold in milliseconds, 0 to disable
     */
    public static void slowRequestThresholdMillis(long milliseconds) {
        setProperty(MOCKSERVER_SLOW_REQUEST_THRESHOLD_MILLIS, "" + milliseconds);
    }

    public static boolean metricsRequestDurationRouteLabels() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS, "MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS", "" + false));
    }

    /**
     * Enable per-route (HTTP method) latency metrics. When enabled, an additional histogram
     * {@code mock_server_request_duration_by_method_seconds} is registered with a {@code method}
     * label for the HTTP method (GET, POST, etc.), alongside the unlabelled
     * {@code mock_server_request_duration_seconds}. Default is false (no labelled histogram).
     * <p>
     * Cardinality is bounded to the set of standard HTTP methods.
     *
     * @param enable enable method labels on the request duration histogram
     */
    public static void metricsRequestDurationRouteLabels(boolean enable) {
        setProperty(MOCKSERVER_METRICS_REQUEST_DURATION_ROUTE_LABELS, "" + enable);
    }

    public static boolean chaosAutoHaltEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CHAOS_AUTO_HALT_ENABLED, "MOCKSERVER_CHAOS_AUTO_HALT_ENABLED", "" + false));
    }

    /**
     * Enable the chaos auto-halt circuit-breaker. When enabled, if the number of chaos-injected
     * errors within a sliding window exceeds the configured threshold, all active service-scoped
     * chaos profiles are automatically disabled. Default is false (feature off).
     *
     * @param enable enable chaos auto-halt
     */
    public static void chaosAutoHaltEnabled(boolean enable) {
        setProperty(MOCKSERVER_CHAOS_AUTO_HALT_ENABLED, "" + enable);
    }

    public static long chaosAutoHaltErrorThreshold() {
        return readLongProperty(MOCKSERVER_CHAOS_AUTO_HALT_ERROR_THRESHOLD, "MOCKSERVER_CHAOS_AUTO_HALT_ERROR_THRESHOLD", 50L);
    }

    public static int rateLimitMaxNamedQuotas() {
        return readIntegerProperty(MOCKSERVER_RATE_LIMIT_MAX_NAMED_QUOTAS, "MOCKSERVER_RATE_LIMIT_MAX_NAMED_QUOTAS", 10000);
    }

    /**
     * The maximum number of distinct named rate-limit counters held in the in-process
     * {@link org.mockserver.ratelimit.RateLimitRegistry}. Once this cap is reached a
     * request for a new counter key fails open (is allowed). Default is 10000.
     *
     * @param maxNamedQuotas maximum number of distinct named rate-limit counters
     */
    public static void rateLimitMaxNamedQuotas(int maxNamedQuotas) {
        setProperty(MOCKSERVER_RATE_LIMIT_MAX_NAMED_QUOTAS, "" + maxNamedQuotas);
    }

    /**
     * The number of chaos-injected errors within the sliding window that triggers an
     * automatic halt of all active service-scoped chaos profiles. Default is 50.
     *
     * @param threshold error count threshold
     */
    public static void chaosAutoHaltErrorThreshold(long threshold) {
        setProperty(MOCKSERVER_CHAOS_AUTO_HALT_ERROR_THRESHOLD, "" + threshold);
    }

    public static long chaosAutoHaltWindowMillis() {
        return readLongProperty(MOCKSERVER_CHAOS_AUTO_HALT_WINDOW_MILLIS, "MOCKSERVER_CHAOS_AUTO_HALT_WINDOW_MILLIS", 60_000L);
    }

    /**
     * The sliding window duration in milliseconds over which chaos-injected errors are
     * counted for the auto-halt circuit-breaker. Default is 60000 (60 seconds).
     *
     * @param millis window duration in milliseconds
     */
    public static void chaosAutoHaltWindowMillis(long millis) {
        setProperty(MOCKSERVER_CHAOS_AUTO_HALT_WINDOW_MILLIS, "" + millis);
    }

    public static boolean connectionLifecycleChaosEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONNECTION_LIFECYCLE_CHAOS_ENABLED, "MOCKSERVER_CONNECTION_LIFECYCLE_CHAOS_ENABLED", "" + true));
    }

    /**
     * Master switch for connection-lifecycle / graceful-shutdown fault injection (mid-response RST,
     * host-scoped slow close, HTTP/2 GOAWAY, and the preemption/SIGTERM simulator). Default true.
     * The response-path lookups are gated on the active registration count, so when no
     * connection-lifecycle faults and no preemption are configured the feature adds nothing to the
     * hot path even when enabled — set this to false only to hard-disable the feature.
     *
     * @param enable enable connection-lifecycle chaos
     */
    public static void connectionLifecycleChaosEnabled(boolean enable) {
        setProperty(MOCKSERVER_CONNECTION_LIFECYCLE_CHAOS_ENABLED, "" + enable);
    }

    public static long preemptionSimulationMaxDrainMillis() {
        return Math.max(0L, readLongProperty(MOCKSERVER_PREEMPTION_SIMULATION_MAX_DRAIN_MILLIS, "MOCKSERVER_PREEMPTION_SIMULATION_MAX_DRAIN_MILLIS", 86_400_000L));
    }

    /**
     * Hard upper bound (in milliseconds) on a preemption simulation's drain window and TTL. A
     * {@code PUT /mockserver/preemption} request asking for a larger value is clamped to this cap, so
     * a forgotten or runaway simulation cannot cordon the server indefinitely. Default is 86400000
     * (24 hours).
     *
     * @param millis maximum drain/TTL milliseconds
     */
    public static void preemptionSimulationMaxDrainMillis(long millis) {
        setProperty(MOCKSERVER_PREEMPTION_SIMULATION_MAX_DRAIN_MILLIS, "" + millis);
    }

    public static boolean connectionLifecycleAutoHaltCountsRst() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONNECTION_LIFECYCLE_AUTO_HALT_COUNTS_RST, "MOCKSERVER_CONNECTION_LIFECYCLE_AUTO_HALT_COUNTS_RST", "" + true));
    }

    /**
     * When true, a connection-lifecycle RST (the mid-response RST) counts as a destructive "drop"
     * fault for the chaos auto-halt circuit-breaker, so a RST storm trips the breaker and halts
     * chaos. Default true.
     *
     * @param enable count lifecycle RSTs toward auto-halt
     */
    public static void connectionLifecycleAutoHaltCountsRst(boolean enable) {
        setProperty(MOCKSERVER_CONNECTION_LIFECYCLE_AUTO_HALT_COUNTS_RST, "" + enable);
    }

    public static boolean sloTrackingEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_SLO_TRACKING_ENABLED, "MOCKSERVER_SLO_TRACKING_ENABLED", "" + false));
    }

    /**
     * Enable SLO sample tracking. When enabled, MockServer records a windowed
     * sample (latency, error flag, scope, host) for each forwarded upstream
     * round-trip so that {@code PUT /mockserver/verifySLO} can compute resilience
     * verdicts. Default is false (feature off) — when disabled the forward path
     * records nothing.
     *
     * @param enable enable SLO sample tracking
     */
    public static void sloTrackingEnabled(boolean enable) {
        setProperty(MOCKSERVER_SLO_TRACKING_ENABLED, "" + enable);
    }

    public static long sloWindowRetentionMillis() {
        return readLongProperty(MOCKSERVER_SLO_WINDOW_RETENTION_MILLIS, "MOCKSERVER_SLO_WINDOW_RETENTION_MILLIS", 600_000L);
    }

    /**
     * The maximum age in milliseconds of SLO samples retained for verdict
     * evaluation. Samples older than this (relative to the newest sample) are
     * evicted. Default is 600000 (10 minutes).
     *
     * @param millis sample retention window in milliseconds
     */
    public static void sloWindowRetentionMillis(long millis) {
        setProperty(MOCKSERVER_SLO_WINDOW_RETENTION_MILLIS, "" + millis);
    }

    public static int sloWindowMaxSamples() {
        return readIntegerProperty(MOCKSERVER_SLO_WINDOW_MAX_SAMPLES, "MOCKSERVER_SLO_WINDOW_MAX_SAMPLES", 50_000);
    }

    /**
     * The maximum number of SLO samples retained for verdict evaluation. When the
     * store is full the oldest sample is evicted. Default is 50000.
     *
     * @param maxSamples maximum number of retained samples
     */
    public static void sloWindowMaxSamples(int maxSamples) {
        setProperty(MOCKSERVER_SLO_WINDOW_MAX_SAMPLES, "" + maxSamples);
    }

    public static boolean loadGenerationEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOAD_GENERATION_ENABLED, "MOCKSERVER_LOAD_GENERATION_ENABLED", "" + false));
    }

    /**
     * Enable API-driven load generation. When enabled, {@code PUT /mockserver/loadScenario}
     * starts an in-process load scenario that drives templated request steps at a target
     * concurrency, producing latency/error samples for the SLO verdict feature. Off by
     * default — when disabled the endpoint returns 403 so MockServer never self-loads
     * unless explicitly opted in.
     *
     * @param enable enable load generation
     */
    public static void loadGenerationEnabled(boolean enable) {
        setProperty(MOCKSERVER_LOAD_GENERATION_ENABLED, "" + enable);
    }

    public static boolean loadGenerationSuppressEventLog() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOAD_GENERATION_SUPPRESS_EVENT_LOG, "MOCKSERVER_LOAD_GENERATION_SUPPRESS_EVENT_LOG", "" + true));
    }

    /**
     * Keep the server's own load-generation traffic out of the request event log. When
     * {@code true} (the default) requests generated by an in-process load scenario are
     * flagged with an in-process-only marker so they are skipped by the event log on the
     * driver, leaving the bounded log free for the requests under test. The marker is never
     * serialized to the wire, so it cannot reach an upstream target. Set to {@code false} to
     * record load-generation traffic in the driver's event log as well.
     *
     * @param suppress suppress load-generation traffic in the event log
     */
    public static void loadGenerationSuppressEventLog(boolean suppress) {
        setProperty(MOCKSERVER_LOAD_GENERATION_SUPPRESS_EVENT_LOG, "" + suppress);
    }

    public static int loadGenerationMaxVirtualUsers() {
        return readIntegerProperty(MOCKSERVER_LOAD_GENERATION_MAX_VIRTUAL_USERS, "MOCKSERVER_LOAD_GENERATION_MAX_VIRTUAL_USERS", 50);
    }

    /**
     * Hard cap on the number of concurrent virtual users a load scenario may drive. A
     * scenario profile asking for more is rejected at validation. Default is 50.
     *
     * @param maxVirtualUsers maximum concurrent virtual users
     */
    public static void loadGenerationMaxVirtualUsers(int maxVirtualUsers) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_VIRTUAL_USERS, "" + maxVirtualUsers);
    }

    public static int loadGenerationMaxInFlightRequests() {
        return readIntegerProperty(MOCKSERVER_LOAD_GENERATION_MAX_IN_FLIGHT_REQUESTS, "MOCKSERVER_LOAD_GENERATION_MAX_IN_FLIGHT_REQUESTS", 200);
    }

    /**
     * Hard cap on the number of in-flight (not-yet-completed) requests a load scenario may
     * have outstanding at once. Enforced live by an in-flight semaphore so a slow target
     * cannot let the scenario queue unbounded work. Default is 200.
     *
     * @param maxInFlightRequests maximum concurrent in-flight requests
     */
    public static void loadGenerationMaxInFlightRequests(int maxInFlightRequests) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_IN_FLIGHT_REQUESTS, "" + maxInFlightRequests);
    }

    public static int loadGenerationMaxRequestsPerSecond() {
        return readIntegerProperty(MOCKSERVER_LOAD_GENERATION_MAX_REQUESTS_PER_SECOND, "MOCKSERVER_LOAD_GENERATION_MAX_REQUESTS_PER_SECOND", 500);
    }

    /**
     * Hard cap on the request rate (requests per second) a load scenario may dispatch.
     * Enforced live by a token bucket so a scenario cannot exceed this rate regardless of
     * concurrency. Default is 500.
     *
     * @param maxRequestsPerSecond maximum requests dispatched per second
     */
    public static void loadGenerationMaxRequestsPerSecond(int maxRequestsPerSecond) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_REQUESTS_PER_SECOND, "" + maxRequestsPerSecond);
    }

    public static long loadGenerationMaxDurationMillis() {
        return readLongProperty(MOCKSERVER_LOAD_GENERATION_MAX_DURATION_MILLIS, "MOCKSERVER_LOAD_GENERATION_MAX_DURATION_MILLIS", 3_600_000L);
    }

    /**
     * Hard cap on the duration (in milliseconds) a load scenario may run. A profile asking
     * for a longer run is rejected at validation, so a forgotten scenario cannot drive
     * traffic indefinitely. Default is 3600000 (1 hour).
     *
     * @param millis maximum scenario duration in milliseconds
     */
    public static void loadGenerationMaxDurationMillis(long millis) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_DURATION_MILLIS, "" + millis);
    }

    public static int loadGenerationMaxSteps() {
        return readIntegerProperty(MOCKSERVER_LOAD_GENERATION_MAX_STEPS, "MOCKSERVER_LOAD_GENERATION_MAX_STEPS", 50);
    }

    /**
     * Hard cap on the number of request steps a single load scenario may define. A scenario
     * with more steps is rejected at validation. Default is 50.
     *
     * @param maxSteps maximum number of steps per scenario
     */
    public static void loadGenerationMaxSteps(int maxSteps) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_STEPS, "" + maxSteps);
    }

    public static double loadGenerationMaxRate() {
        return readDoubleProperty(MOCKSERVER_LOAD_GENERATION_MAX_RATE, "MOCKSERVER_LOAD_GENERATION_MAX_RATE", 5000.0);
    }

    /**
     * Hard cap on the arrival rate (iterations per second) a {@code RATE} load stage may request.
     * A stage asking for a higher rate is rejected at validation, so an open-model scenario cannot
     * be told to start work faster than the server can safely sustain. Default is 5000.
     *
     * @param maxRate maximum arrival rate in iterations per second
     */
    public static void loadGenerationMaxRate(double maxRate) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_RATE, "" + maxRate);
    }

    public static int loadGenerationMaxStages() {
        return readIntegerProperty(MOCKSERVER_LOAD_GENERATION_MAX_STAGES, "MOCKSERVER_LOAD_GENERATION_MAX_STAGES", 20);
    }

    /**
     * Hard cap on the number of stages a single load profile may define. A profile with more stages
     * is rejected at validation. Default is 20.
     *
     * @param maxStages maximum number of stages per profile
     */
    public static void loadGenerationMaxStages(int maxStages) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_STAGES, "" + maxStages);
    }

    public static int loadGenerationMaxConcurrentScenarios() {
        return readIntegerProperty(MOCKSERVER_LOAD_GENERATION_MAX_CONCURRENT_SCENARIOS, "MOCKSERVER_LOAD_GENERATION_MAX_CONCURRENT_SCENARIOS", 10);
    }

    /**
     * Hard cap on the number of load scenarios that may be concurrently <em>active</em> (PENDING or
     * RUNNING) at once. A start trigger that would exceed this is rejected. Loading (registering)
     * scenarios is not limited — only how many may run together. Default is 10.
     *
     * @param maxConcurrentScenarios maximum concurrently active load scenarios
     */
    public static void loadGenerationMaxConcurrentScenarios(int maxConcurrentScenarios) {
        setProperty(MOCKSERVER_LOAD_GENERATION_MAX_CONCURRENT_SCENARIOS, "" + maxConcurrentScenarios);
    }

    public static String loadScenarioInitializationJsonPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOAD_SCENARIO_INITIALIZATION_JSON_PATH, "MOCKSERVER_LOAD_SCENARIO_INITIALIZATION_JSON_PATH", "");
    }

    /**
     * Path to a JSON file containing an array of load scenario definitions. At startup each is loaded
     * (registered) into the load-scenario registry in the {@code LOADED} state — staged and ready to be
     * triggered by name, but not running. Empty by default (no preloading). Mirrors
     * {@code initializationJsonPath} for expectations.
     *
     * @param loadScenarioInitializationJsonPath path to the load scenario definitions JSON file
     */
    public static void loadScenarioInitializationJsonPath(String loadScenarioInitializationJsonPath) {
        setProperty(MOCKSERVER_LOAD_SCENARIO_INITIALIZATION_JSON_PATH, loadScenarioInitializationJsonPath == null ? "" : loadScenarioInitializationJsonPath);
    }

    public static java.util.List<String> loadGenerationMetricLabels() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOAD_GENERATION_METRIC_LABELS, "MOCKSERVER_LOAD_GENERATION_METRIC_LABELS", "");
        if (value == null || value.isBlank()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<String> labels = new java.util.ArrayList<>();
        for (String name : value.split(",")) {
            String trimmed = name.trim();
            if (!trimmed.isEmpty()) {
                labels.add(trimmed);
            }
        }
        return labels;
    }

    /**
     * Allowlist of custom load-scenario label names that are added as extra <em>fixed</em>
     * Prometheus labels on the {@code mock_server_load_*} metrics (comma-separated, default empty).
     * Prometheus requires a fixed label-name set, so only the keys named here are carried as
     * Prometheus labels; every custom label is always attached as an OpenTelemetry attribute
     * regardless. When empty, the Prometheus load metrics carry only the fixed structured labels
     * ({@code scenario, run_id, step, route, method, status_class}).
     *
     * @param labels comma-separated custom label names to expose as Prometheus labels
     */
    public static void loadGenerationMetricLabels(String labels) {
        setProperty(MOCKSERVER_LOAD_GENERATION_METRIC_LABELS, labels == null ? "" : labels);
    }

    public static boolean mcpEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_MCP_ENABLED, "MOCKSERVER_MCP_ENABLED", "" + true));
    }

    /**
     * Enable or disable the MCP (Model Context Protocol) endpoint, default is true
     *
     * @param enable enable MCP endpoint
     */
    public static void mcpEnabled(boolean enable) {
        setProperty(MOCKSERVER_MCP_ENABLED, "" + enable);
    }

    public static long stopDrainMillis() {
        return Math.max(0L, readLongProperty(MOCKSERVER_STOP_DRAIN_MILLIS, "MOCKSERVER_STOP_DRAIN_MILLIS", 15_000L));
    }

    /**
     * Maximum time in milliseconds to wait for in-flight requests to complete when the server is
     * stopped (graceful shutdown connection drain). On stop, the server stops accepting new
     * connections and then waits up to this timeout for any requests still being processed to
     * finish before shutting down the event loops. If the timeout elapses a warning is logged with
     * the number of remaining in-flight requests and shutdown proceeds anyway. Default is 15000
     * (15 seconds). Set to 0 to disable draining (stop immediately, the pre-7.2 behaviour).
     *
     * @param millis drain timeout in milliseconds, 0 to disable draining
     */
    public static void stopDrainMillis(long millis) {
        setProperty(MOCKSERVER_STOP_DRAIN_MILLIS, "" + millis);
    }

    public static long breakpointTimeoutMillis() {
        return readLongProperty(MOCKSERVER_BREAKPOINT_TIMEOUT_MILLIS, "MOCKSERVER_BREAKPOINT_TIMEOUT_MILLIS", 30_000L);
    }

    /**
     * Maximum time in milliseconds a request may be held at a breakpoint before it is
     * automatically continued (forwarded with the original request). Prevents forgotten
     * breakpoints from hanging indefinitely. Default is 30000 (30 seconds).
     *
     * @param millis timeout in milliseconds
     */
    public static void breakpointTimeoutMillis(long millis) {
        setProperty(MOCKSERVER_BREAKPOINT_TIMEOUT_MILLIS, "" + millis);
    }

    public static int breakpointMaxHeld() {
        return readIntegerProperty(MOCKSERVER_BREAKPOINT_MAX_HELD, "MOCKSERVER_BREAKPOINT_MAX_HELD", 50);
    }

    /**
     * Maximum number of requests that can be simultaneously held at breakpoints. When
     * this cap is reached, new breakpoint intercepts are skipped and requests are forwarded
     * normally. This is a DoS prevention rail. Default is 50.
     *
     * @param maxHeld maximum concurrent held requests
     */
    public static void breakpointMaxHeld(int maxHeld) {
        setProperty(MOCKSERVER_BREAKPOINT_MAX_HELD, "" + maxHeld);
    }

    public static boolean wasmEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_WASM_ENABLED, "MOCKSERVER_WASM_ENABLED", "" + false));
    }

    public static void wasmEnabled(boolean enable) {
        setProperty(MOCKSERVER_WASM_ENABLED, "" + enable);
    }

    public static int wasmMaxMemoryPages() {
        return readIntegerProperty(MOCKSERVER_WASM_MAX_MEMORY_PAGES, "MOCKSERVER_WASM_MAX_MEMORY_PAGES", 256);
    }

    public static void wasmMaxMemoryPages(int pages) {
        setProperty(MOCKSERVER_WASM_MAX_MEMORY_PAGES, "" + pages);
    }

    public static long wasmExecutionTimeoutMillis() {
        return readLongProperty(MOCKSERVER_WASM_EXECUTION_TIMEOUT_MILLIS, "MOCKSERVER_WASM_EXECUTION_TIMEOUT_MILLIS", 5000L);
    }

    /**
     * Maximum wall-clock time a single WASM module invocation may run before it is aborted and treated as
     * a non-match (fail closed).
     * <p>
     * WASM custom rules run DURING request matching, so a module containing an unbounded loop would
     * otherwise pin the calling matcher thread permanently and can starve the event loop. Set to 0 to
     * disable the limit (unbounded execution — not recommended).
     *
     * @param timeoutMillis maximum milliseconds a WASM invocation may run, or 0 for no limit
     */
    public static void wasmExecutionTimeoutMillis(long timeoutMillis) {
        setProperty(MOCKSERVER_WASM_EXECUTION_TIMEOUT_MILLIS, "" + timeoutMillis);
    }

    public static String grpcDescriptorDirectory() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY, "MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY", "");
    }

    public static void grpcDescriptorDirectory(String directory) {
        setProperty(MOCKSERVER_GRPC_DESCRIPTOR_DIRECTORY, directory);
    }

    public static String grpcProtoDirectory() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_PROTO_DIRECTORY, "MOCKSERVER_GRPC_PROTO_DIRECTORY", "");
    }

    public static void grpcProtoDirectory(String directory) {
        setProperty(MOCKSERVER_GRPC_PROTO_DIRECTORY, directory);
    }

    public static boolean grpcEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_ENABLED, "MOCKSERVER_GRPC_ENABLED", "" + true));
    }

    public static void grpcEnabled(boolean enable) {
        setProperty(MOCKSERVER_GRPC_ENABLED, "" + enable);
    }

    public static String grpcProtocPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_PROTOC_PATH, "MOCKSERVER_GRPC_PROTOC_PATH", "protoc");
    }

    public static void grpcProtocPath(String path) {
        setProperty(MOCKSERVER_GRPC_PROTOC_PATH, path);
    }

    public static boolean grpcBidiStreamingEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED, "MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED", "false"));
    }

    /**
     * If true the HTTP/2 pipeline uses Http2FrameCodec + Http2MultiplexHandler instead of
     * HttpToHttp2ConnectionHandler + InboundHttp2ToHttpAdapter for connections where gRPC
     * descriptors are loaded. This is required for true client-streaming and bidirectional-streaming
     * gRPC in a future phase. In Phase 0 the multiplex branch re-aggregates frames so behaviour
     * is identical to the connection-level adapter.
     * <p>
     * Requires gRPC descriptors to be loaded (grpcEnabled with descriptors present). When false
     * (the default) or when no descriptors are loaded, the existing connection-level adapter is used.
     * <p>
     * Default is false
     *
     * @param enable enable the multiplex HTTP/2 pipeline for gRPC bidi-streaming support
     */
    public static void grpcBidiStreamingEnabled(boolean enable) {
        setProperty(MOCKSERVER_GRPC_BIDI_STREAMING_ENABLED, "" + enable);
    }

    public static boolean dnsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DNS_ENABLED, "MOCKSERVER_DNS_ENABLED", "" + false));
    }

    public static void dnsEnabled(boolean enable) {
        setProperty(MOCKSERVER_DNS_ENABLED, "" + enable);
    }

    public static int dnsPort() {
        return readIntegerProperty(MOCKSERVER_DNS_PORT, "MOCKSERVER_DNS_PORT", 0);
    }

    public static void dnsPort(int port) {
        setProperty(MOCKSERVER_DNS_PORT, "" + port);
    }

    // experimental HTTP/3 (QUIC)

    public static int http3Port() {
        return readIntegerProperty(MOCKSERVER_HTTP3_PORT, "MOCKSERVER_HTTP3_PORT", 0);
    }

    public static void http3Port(int port) {
        setProperty(MOCKSERVER_HTTP3_PORT, "" + port);
    }

    /**
     * Max idle timeout in milliseconds for QUIC connections.
     * Default: 5000 (5 seconds).
     */
    public static long http3MaxIdleTimeout() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT, "MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT", 5000L));
    }

    public static void http3MaxIdleTimeout(long millis) {
        setProperty(MOCKSERVER_HTTP3_MAX_IDLE_TIMEOUT, "" + millis);
    }

    /**
     * Initial maximum data (connection-level flow control) in bytes.
     * Default: 10000000 (10 MB).
     */
    public static long http3InitialMaxData() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_INITIAL_MAX_DATA, "MOCKSERVER_HTTP3_INITIAL_MAX_DATA", 10000000L));
    }

    public static void http3InitialMaxData(long bytes) {
        setProperty(MOCKSERVER_HTTP3_INITIAL_MAX_DATA, "" + bytes);
    }

    /**
     * Initial maximum stream data for bidirectional streams (per-stream flow control)
     * in bytes. Applied to both local and remote bidirectional streams.
     * Default: 1000000 (1 MB).
     */
    public static long http3InitialMaxStreamDataBidirectional() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL, "MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL", 1000000L));
    }

    public static void http3InitialMaxStreamDataBidirectional(long bytes) {
        setProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAM_DATA_BIDIRECTIONAL, "" + bytes);
    }

    /**
     * Initial maximum number of concurrent bidirectional streams.
     * Default: 100.
     */
    public static long http3InitialMaxStreamsBidirectional() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL, "MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL", 100L));
    }

    public static void http3InitialMaxStreamsBidirectional(long maxStreams) {
        setProperty(MOCKSERVER_HTTP3_INITIAL_MAX_STREAMS_BIDIRECTIONAL, "" + maxStreams);
    }

    /**
     * QPACK dynamic table maximum capacity in bytes. Controls the amount of
     * memory allocated for QPACK header compression on the HTTP/3 control stream.
     * Set to 0 to disable the dynamic table entirely.
     * Default: 0 (dynamic table disabled — only static table used).
     */
    public static long http3QpackMaxTableCapacity() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY, "MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY", 0L));
    }

    public static void http3QpackMaxTableCapacity(long bytes) {
        setProperty(MOCKSERVER_HTTP3_QPACK_MAX_TABLE_CAPACITY, "" + bytes);
    }

    /**
     * Enable the CONNECT-UDP (MASQUE, RFC 9298) forward proxy on the HTTP/3 server.
     * When enabled, the server advertises {@code SETTINGS_ENABLE_CONNECT_PROTOCOL}
     * (RFC 9220) and extended-CONNECT requests with {@code :protocol=connect-udp} are
     * relayed: a UDP socket is opened to the target authority and datagrams are forwarded
     * in both directions (one HTTP/3 DATA frame per datagram). This is supported by the
     * mainline {@code io.netty:netty-codec-http3} codec (Netty 4.2). Normal (non-CONNECT)
     * HTTP/3 requests are unaffected.
     * <p>
     * Experimental and <strong>off by default</strong>. When enabled this is an open UDP
     * relay with no target restriction (a client can reach any UDP host:port reachable
     * from the server, including private/loopback/cloud-metadata addresses) — intended
     * for controlled test environments only; do not expose to untrusted clients.
     * Default: false (disabled).
     */
    public static boolean http3ConnectUdpEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED, "MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED", "" + false));
    }

    public static void http3ConnectUdpEnabled(boolean enabled) {
        setProperty(MOCKSERVER_HTTP3_CONNECT_UDP_ENABLED, "" + enabled);
    }

    /**
     * Allowlist of targets the HTTP/3 CONNECT-UDP (MASQUE) relay may reach, as a
     * comma-separated list of {@code host} or {@code host:port} entries (IPv6 literals
     * bracketed, e.g. {@code [::1]:53}). Matching is exact and case-insensitive; an
     * entry without a port permits the host on any port. Only relevant when
     * {@code http3ConnectUdpEnabled=true}.
     * <p>
     * When <strong>empty</strong> (the default) the allowlist is not enforced and the
     * relay may reach any target (subject to {@code forwardProxyBlockPrivateNetworks}).
     * When <strong>non-empty</strong>, a CONNECT-UDP request to a target that does not
     * match any entry is refused (403) and no datagrams are relayed. This constrains the
     * relay's SSRF exposure. Default: "" (not enforced).
     */
    public static String http3ConnectUdpAllowedTargets() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_HTTP3_CONNECT_UDP_ALLOWED_TARGETS, "MOCKSERVER_HTTP3_CONNECT_UDP_ALLOWED_TARGETS", "");
    }

    public static void http3ConnectUdpAllowedTargets(String allowedTargets) {
        setProperty(MOCKSERVER_HTTP3_CONNECT_UDP_ALLOWED_TARGETS, allowedTargets);
    }

    /**
     * Max-age in seconds for the Alt-Svc header advertising HTTP/3 on the TCP
     * response path. Only relevant when {@code http3Port > 0} and
     * {@code http3AdvertiseAltSvc} is {@code true}.
     * Default: 86400 (24 hours).
     */
    public static long http3AltSvcMaxAge() {
        return Math.max(0, readLongProperty(MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE, "MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE", 86400L));
    }

    public static void http3AltSvcMaxAge(long seconds) {
        setProperty(MOCKSERVER_HTTP3_ALT_SVC_MAX_AGE, "" + seconds);
    }

    /**
     * Whether to add an {@code Alt-Svc: h3=":<http3Port>"; ma=<maxAge>} header
     * to every response served over the TCP (HTTP/1.1 and HTTP/2) paths when
     * {@code http3Port > 0}. When {@code false}, no Alt-Svc header is added
     * even when HTTP/3 is enabled (useful for testing without client auto-upgrade).
     * Default: true.
     */
    public static boolean http3AdvertiseAltSvc() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC, "MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC", "" + true));
    }

    public static void http3AdvertiseAltSvc(boolean advertise) {
        setProperty(MOCKSERVER_HTTP3_ADVERTISE_ALT_SVC, "" + advertise);
    }

    // service mesh / sidecar

    public static boolean transparentProxyEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_ENABLED, "MOCKSERVER_TRANSPARENT_PROXY_ENABLED", "" + false));
    }

    public static void transparentProxyEnabled(boolean enable) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_ENABLED, "" + enable);
    }

    /**
     * Enable TPROXY (IP_TRANSPARENT) mode for transparent proxy original destination
     * resolution. When enabled, the listener socket is bound with IP_TRANSPARENT and
     * the original destination is read from the socket's local address (preserved by
     * the TPROXY iptables target). Requires Linux, epoll transport, CAP_NET_ADMIN,
     * and TPROXY iptables rules instead of REDIRECT. Default: false.
     */
    public static boolean transparentProxyTproxy() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_TPROXY, "MOCKSERVER_TRANSPARENT_PROXY_TPROXY", "" + false));
    }

    public static void transparentProxyTproxy(boolean enable) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_TPROXY, "" + enable);
    }

    /**
     * Enable eBPF-based original destination resolution. When enabled, the resolver
     * reads from a pinned BPF hash map (populated by an external cgroup/connect4
     * BPF program) keyed by socket cookie. Requires Linux, CAP_BPF, a BTF-enabled
     * kernel, and the external BPF program. Default: false.
     */
    public static boolean transparentProxyEbpf() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_EBPF, "MOCKSERVER_TRANSPARENT_PROXY_EBPF", "" + false));
    }

    public static void transparentProxyEbpf(boolean enable) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_EBPF, "" + enable);
    }

    /**
     * Path to the pinned BPF map used by the eBPF original destination resolver.
     * The map must be a BPF hash map with u64 key (socket cookie) and 6-byte value
     * (4-byte IPv4 address + 2-byte port in network byte order).
     * Default: /sys/fs/bpf/mockserver_orig_dst.
     */
    public static String transparentProxyEbpfMapPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH, "MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH", "/sys/fs/bpf/mockserver_orig_dst");
    }

    public static void transparentProxyEbpfMapPath(String path) {
        setProperty(MOCKSERVER_TRANSPARENT_PROXY_EBPF_MAP_PATH, path);
    }

    // async messaging defaults

    /**
     * Default Kafka bootstrap servers used when a {@code PUT /mockserver/asyncapi}
     * request body does not include {@code brokerConfig.kafkaBootstrapServers}.
     * Empty string means no default (broker must be specified per-request).
     */
    public static String asyncKafkaBootstrapServers() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS, "MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS", "");
    }

    public static void asyncKafkaBootstrapServers(String servers) {
        setProperty(MOCKSERVER_ASYNC_KAFKA_BOOTSTRAP_SERVERS, servers);
    }

    /**
     * Default MQTT broker URL used when a {@code PUT /mockserver/asyncapi}
     * request body does not include {@code brokerConfig.mqttBrokerUrl}.
     * Empty string means no default (broker must be specified per-request).
     */
    public static String asyncMqttBrokerUrl() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_ASYNC_MQTT_BROKER_URL, "MOCKSERVER_ASYNC_MQTT_BROKER_URL", "");
    }

    public static void asyncMqttBrokerUrl(String url) {
        setProperty(MOCKSERVER_ASYNC_MQTT_BROKER_URL, url);
    }

    /**
     * Default AMQP (RabbitMQ) connection URI used when a {@code PUT /mockserver/asyncapi}
     * request body does not include {@code brokerConfig.amqpUri}
     * (e.g. {@code amqp://guest:guest@localhost:5672/}).
     * Empty string means no default (broker must be specified per-request).
     */
    public static String asyncAmqpUri() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_ASYNC_AMQP_URI, "MOCKSERVER_ASYNC_AMQP_URI", "");
    }

    public static void asyncAmqpUri(String uri) {
        setProperty(MOCKSERVER_ASYNC_AMQP_URI, uri);
    }

    /**
     * Maximum number of recorded messages retained per channel in async
     * messaging subscribers. Default is 1000.
     */
    public static int asyncRecordedMessageMaxEntries() {
        return readIntegerProperty(MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES, "MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES", 1000);
    }

    public static void asyncRecordedMessageMaxEntries(int maxEntries) {
        setProperty(MOCKSERVER_ASYNC_RECORDED_MESSAGE_MAX_ENTRIES, "" + maxEntries);
    }

    public static Map<String, String> logLevelOverrides() {
        String overridesJson = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOG_LEVEL_OVERRIDES, "MOCKSERVER_LOG_LEVEL_OVERRIDES", "");
        if (isNotBlank(overridesJson)) {
            try {
                return ObjectMapperFactory.createObjectMapper().readValue(overridesJson, new TypeReference<Map<String, String>>() {
                });
            } catch (Exception e) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("invalid value for logLevelOverrides, expected JSON map but found:{}")
                        .setArguments(overridesJson)
                );
                return Collections.emptyMap();
            }
        } else {
            return Collections.emptyMap();
        }
    }

    /**
     * Override the log level for specific log message type categories or individual log message types.
     * <p>
     * Keys can be category group names (MATCHING, REQUEST_LIFECYCLE, EXPECTATION_MANAGEMENT, VERIFICATION, SERVER, GENERAL)
     * or individual LogMessageType names (e.g., EXPECTATION_NOT_MATCHED, FORWARDED_REQUEST).
     * Values are SLF4J log level names (TRACE, DEBUG, INFO, WARN, ERROR).
     * Resolution order: individual type override > category group override > global logLevel.
     *
     * @param overrides map of category/type names to log level names
     */
    public static void logLevelOverrides(Map<String, String> overrides) {
        if (overrides != null && !overrides.isEmpty()) {
            try {
                setProperty(MOCKSERVER_LOG_LEVEL_OVERRIDES, ObjectMapperFactory.createObjectMapper().writeValueAsString(overrides));
            } catch (Exception e) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("failed to serialize logLevelOverrides:{}")
                        .setArguments(overrides)
                );
            }
        } else {
            clearProperty(MOCKSERVER_LOG_LEVEL_OVERRIDES);
        }
    }

    public static boolean compactLogFormat() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_COMPACT_LOG_FORMAT, "MOCKSERVER_COMPACT_LOG_FORMAT", "" + false));
    }

    /**
     * When enabled, log messages written to stdout/SLF4J use a compact single-line format showing
     * summary information (e.g., method, path, status code, expectation ID) instead of full
     * JSON-serialized request and response details. The dashboard UI, verification, and log
     * retrieval APIs are not affected.
     *
     * @param enable enable compact log format
     */
    public static void compactLogFormat(boolean enable) {
        setProperty(MOCKSERVER_COMPACT_LOG_FORMAT, "" + enable);
    }

    // dev mode

    /**
     * <p>When true, applies a developer-friendly configuration profile that reduces memory
     * usage for laptop/test-suite workloads. The following defaults are overridden
     * (only for properties the user has not explicitly set via system property,
     * environment variable, or properties file):</p>
     * <ul>
     *     <li>{@code maxLogEntries} &rarr; 1,000 (instead of the heap-based default up to 100,000)</li>
     *     <li>{@code maxExpectations} &rarr; 1,000 (instead of the heap-based default up to 15,000)</li>
     * </ul>
     * <p>Default: {@code false}. Enable via {@code --dev} CLI flag, {@code -Dmockserver.devMode=true},
     * or {@code MOCKSERVER_DEV_MODE=true}.</p>
     */
    public static boolean devMode() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DEV_MODE, "MOCKSERVER_DEV_MODE", "" + false));
    }

    /**
     * Enable or disable dev mode. Dev-mode defaults for {@code maxLogEntries} and
     * {@code maxExpectations} are applied lazily by the getters via
     * {@link #devModeDefaultOrHeapBased} — no eager global-state mutation here.
     *
     * @param enable enable dev mode
     */
    public static void devMode(boolean enable) {
        setProperty(MOCKSERVER_DEV_MODE, "" + enable);
    }

    /**
     * Returns {@code true} when a property has been explicitly configured by the user
     * (as a JVM system property, an environment variable, or in the properties file),
     * as opposed to being at its built-in default.
     */
    static boolean isPropertyExplicitlySet(String systemPropertyKey, String environmentVariableKey) {
        // Check JVM system property (directly, bypassing cache)
        if (isNotBlank(System.getProperty(systemPropertyKey))) {
            return true;
        }
        // Check environment variable
        if (isNotBlank(System.getenv(environmentVariableKey))) {
            return true;
        }
        // Check properties file
        if (PROPERTIES != null && isNotBlank(PROPERTIES.getProperty(systemPropertyKey))) {
            return true;
        }
        return false;
    }

    /**
     * Returns the dev-mode default when {@code devMode()} is {@code true} and the user has NOT
     * explicitly set the given property via system property, environment variable, or properties
     * file. Otherwise returns the normal heap-based default. This lazy approach ensures ALL
     * activation paths (env var, system property, properties file, programmatic setter) work
     * without mutating global state.
     */
    private static int devModeDefaultOrHeapBased(int devDefault, String systemPropertyKey, String envVarKey, int heapBasedDefault) {
        if (devMode() && !isPropertyExplicitlySet(systemPropertyKey, envVarKey)) {
            return devDefault;
        }
        return heapBasedDefault;
    }

    // memory usage

    // Reserved for JVM internals, Netty buffers and thread stacks; subtracted from the free heap
    // before deriving the maxLogEntries / maxExpectations defaults.
    static final long BASE_MEMORY_IN_KB = 20 * 1024L;

    public static long heapAvailableInKB() {
        Summary heap = MemoryMonitoring.getJVMMemory(MemoryType.HEAP);
        Runtime runtime = Runtime.getRuntime();
        return computeHeapAvailableInKB(
            heap.getNet().getMax(),
            heap.getNet().getUsed(),
            runtime.maxMemory(),
            runtime.totalMemory() - runtime.freeMemory()
        );
    }

    /**
     * Compute the available heap (in KB) used to derive the {@code maxLogEntries} and
     * {@code maxExpectations} defaults, robust to environments where the aggregated JMX heap-pool
     * max is undefined.
     * <p>
     * The JMX spec allows {@link java.lang.management.MemoryUsage#getMax()} to return {@code -1}
     * (undefined); some environments (verified: a GraalVM native image of the shaded jar; also
     * possible in exotic JVM/WAR setups) report the heap pools' max as {@code -1}/{@code 0}. When
     * that happens the JMX figures are unusable, so we fall back to {@link Runtime#maxMemory()} /
     * {@code totalMemory() - freeMemory()}. {@code Runtime.maxMemory()} may return
     * {@link Long#MAX_VALUE} when the heap is unbounded — the subtraction stays non-negative, and
     * the (very large) result is clamped by the {@code Math.min(..., cap)} in the callers.
     * <p>
     * The result is floored at {@code 0} so it is never negative (a negative value would produce a
     * non-functional {@code <= 0} store capacity downstream).
     *
     * @param heapMax     aggregated JMX heap-pool max in bytes ({@code <= 0} when undefined)
     * @param heapUsed    aggregated JMX heap-pool used in bytes
     * @param runtimeMax  {@link Runtime#maxMemory()} fallback ceiling in bytes
     * @param runtimeUsed {@link Runtime#totalMemory()} minus {@link Runtime#freeMemory()} in bytes
     * @return available heap in KB, never negative
     */
    static long computeHeapAvailableInKB(long heapMax, long heapUsed, long runtimeMax, long runtimeUsed) {
        long max = heapMax;
        long used = heapUsed;
        if (max <= 0) {
            // JMX heap-pool max undefined (-1); fall back to the Runtime view of the heap.
            max = runtimeMax;
            used = runtimeUsed;
        }
        if (max <= 0) {
            // Still no usable ceiling (both JMX and Runtime undefined) — avoid a garbage result.
            return 0L;
        }
        return Math.max(0L, ((max - used) / 1024L) - BASE_MEMORY_IN_KB);
    }

    public static int maxExpectations() {
        return readIntegerProperty(MOCKSERVER_MAX_EXPECTATIONS, "MOCKSERVER_MAX_EXPECTATIONS", devModeDefaultOrHeapBased(
            DEV_MODE_MAX_EXPECTATIONS, MOCKSERVER_MAX_EXPECTATIONS, "MOCKSERVER_MAX_EXPECTATIONS",
            heapBasedDefaultOrFloor(heapAvailableInKB(), 10, 15000, DEV_MODE_MAX_EXPECTATIONS)
        ));
    }

    /**
     * Derive a heap-based store-size default, flooring at {@code floor} when the heap-derived value
     * computes to {@code <= 0}. {@link #heapAvailableInKB()} can be {@code 0} when the JVM reports an
     * undefined heap max ({@code -1}, e.g. a GraalVM native image); without a floor the resulting
     * capacity would be {@code <= 0}, silently dropping every expectation / log entry. The floor keeps
     * the store minimally functional in that case.
     *
     * @param heapAvailableInKB available heap in KB (never negative)
     * @param perEntryKB        estimated KB per stored entry (heap divided by this)
     * @param cap               upper bound regardless of heap
     * @param floor             minimum functional default when the heap-derived value is {@code <= 0}
     * @return the store-size default, at least {@code min(floor, cap)} and at most {@code cap}
     */
    static int heapBasedDefaultOrFloor(long heapAvailableInKB, long perEntryKB, int cap, int floor) {
        int heapBased = (int) Math.min(heapAvailableInKB / perEntryKB, cap);
        return heapBased > 0 ? heapBased : Math.min(floor, cap);
    }

    /**
     * <p>
     * Maximum number of expectations stored in memory.  Expectations are stored in a circular queue so once this limit is reach the oldest and lowest priority expectations are overwritten
     * </p>
     * <p>
     * The default maximum depends on the available memory in the JVM with an upper limit of 15000
     * </p>
     *
     * @param count maximum number of expectations to store
     */
    public static void maxExpectations(int count) {
        setProperty(MOCKSERVER_MAX_EXPECTATIONS, "" + count);
    }

    public static int maxLogEntries() {
        return readIntegerProperty(MOCKSERVER_MAX_LOG_ENTRIES, "MOCKSERVER_MAX_LOG_ENTRIES", devModeDefaultOrHeapBased(
            DEV_MODE_MAX_LOG_ENTRIES, MOCKSERVER_MAX_LOG_ENTRIES, "MOCKSERVER_MAX_LOG_ENTRIES",
            heapBasedDefaultOrFloor(heapAvailableInKB(), 8, 100000, DEV_MODE_MAX_LOG_ENTRIES)
        ));
    }

    /**
     * <p>
     * Maximum number of log entries stored in memory.  Log entries are stored in a circular queue so once this limit is reach the oldest log entries are overwritten.
     * </p>
     * <p>
     * The default maximum depends on the available memory in the JVM with an upper limit of 100000, but can be overridden using defaultMaxLogEntries
     * </p>
     *
     * @param count maximum number of expectations to store
     */
    public static void maxLogEntries(int count) {
        setProperty(MOCKSERVER_MAX_LOG_ENTRIES, "" + count);
    }

    public static long maxEventLogSizeInBytes() {
        return Math.max(0L, readLongProperty(MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES, "MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES", 0L));
    }

    /**
     * <p>
     * Maximum total size in bytes of the in-memory event log before older entries are evicted from memory (the oldest first).
     * </p>
     * <p>
     * The default is 0, which disables the size-based limit (the event log is bounded only by {@code maxLogEntries}).
     * </p>
     *
     * @param maxEventLogSizeInBytes maximum total size in bytes of the in-memory event log (0 disables the limit)
     */
    public static void maxEventLogSizeInBytes(long maxEventLogSizeInBytes) {
        setProperty(MOCKSERVER_MAX_EVENT_LOG_SIZE_IN_BYTES, "" + maxEventLogSizeInBytes);
    }

    public static int maxLoggedBodyBytes() {
        return Math.max(0, readIntegerProperty(MOCKSERVER_MAX_LOGGED_BODY_BYTES, "MOCKSERVER_MAX_LOGGED_BODY_BYTES", 0));
    }

    /**
     * <p>
     * Maximum number of bytes of a request or response body retained in each log entry; bodies larger than this are truncated.
     * </p>
     * <p>
     * The default is 0, which means unlimited (bodies are retained in full).
     * </p>
     *
     * @param maxLoggedBodyBytes maximum number of body bytes retained per log entry (0 means unlimited)
     */
    public static void maxLoggedBodyBytes(int maxLoggedBodyBytes) {
        setProperty(MOCKSERVER_MAX_LOGGED_BODY_BYTES, "" + maxLoggedBodyBytes);
    }

    public static int ringBufferSize() {
        return resolveRingBufferSize(maxLogEntries());
    }

    /**
     * Resolve the ring-buffer size, defaulting (when no explicit {@code mockserver.ringBufferSize}
     * override is present) to {@code min(maxLogEntriesForDefault, 16384)}. The caller supplies
     * {@code maxLogEntriesForDefault} so an instance {@link Configuration} can base the default on its
     * OWN maxLogEntries rather than the global/static value. Decoupled from maxLogEntries retention:
     * the in-flight disruptor ring only needs to absorb event bursts, not hold the full retained
     * history, so large-retention deployments (e.g. maxLogEntries=100000) no longer pre-allocate a
     * 131072-slot ring. {@link Configuration#ringBufferSize()} rounds the result up to the next power
     * of two as the disruptor requires.
     */
    public static int resolveRingBufferSize(int maxLogEntriesForDefault) {
        // Only an EXPLICIT override (programmatic cache / system property / env var) is honoured; the
        // computed default is NOT cached under the property key. This keeps the default dynamic — it
        // tracks the supplied maxLogEntries on every call — rather than freezing the first computed
        // value (as readIntegerProperty would by caching it), which would otherwise leak a stale
        // default to later readers of this global property.
        String explicit = explicitProperty(MOCKSERVER_RING_BUFFER_SIZE, "MOCKSERVER_RING_BUFFER_SIZE");
        if (explicit != null) {
            try {
                return Integer.parseInt(explicit);
            } catch (NumberFormatException nfe) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("NumberFormatException converting " + MOCKSERVER_RING_BUFFER_SIZE + " with value [" + explicit + "]")
                        .setThrowable(nfe)
                );
            }
        }
        return Math.min(maxLogEntriesForDefault, DEFAULT_MAX_RING_BUFFER_SIZE);
    }

    /**
     * Returns the value of a property if it is EXPLICITLY set — via the programmatic cache, a JVM
     * system property, the {@code mockserver.properties} file, or an environment variable — or
     * {@code null} when only a computed default would apply. Unlike {@link #readPropertyHierarchically}
     * this never injects (nor caches) a default, so callers can compute a dynamic default without
     * polluting the property cache.
     */
    private static String explicitProperty(String systemPropertyKey, String environmentVariableKey) {
        String cached = getPropertyCache().get(systemPropertyKey);
        if (cached != null) {
            return cached;
        }
        String systemOrFile = System.getProperty(systemPropertyKey, PROPERTIES != null ? PROPERTIES.getProperty(systemPropertyKey) : null);
        if (systemOrFile != null) {
            return systemOrFile;
        }
        String env = System.getenv(environmentVariableKey);
        return isNotBlank(env) ? env : null;
    }

    /**
     * <p>
     * Number of slots in the in-memory log event ring buffer (LMAX Disruptor) that buffers log events
     * between the producing Netty I/O threads and the single consumer thread. This is independent of
     * {@code maxLogEntries} (which bounds the retained event history). The ring only needs to absorb
     * short bursts of log events, so it can be much smaller than the retained history.
     * </p>
     * <p>
     * The value is rounded up to the next power of two (a Disruptor requirement). The default is
     * {@code min(maxLogEntries, 16384)}. Increase it only if you see dropped log events under sustained
     * extreme load (see the {@code mock_server_dropped_log_events} metric); decrease it to save memory.
     * </p>
     *
     * @param size number of slots in the log event ring buffer (rounded up to a power of two)
     */
    public static void ringBufferSize(int size) {
        setProperty(MOCKSERVER_RING_BUFFER_SIZE, "" + size);
    }

    public static int maxWebSocketExpectations() {
        return readIntegerProperty(MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS, "MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS", 1500);
    }

    /**
     * <p>
     * Maximum number of remote (not the same JVM) method callbacks (i.e. web sockets) registered for expectations.  The web socket client registry entries are stored in a circular queue so once this limit is reach the oldest are overwritten.
     * </p>
     * <p>
     * The default is 1500
     * </p>
     *
     * @param count maximum number of method callbacks (i.e. web sockets) registered for expectations
     */
    public static void maxWebSocketExpectations(int count) {
        setProperty(MOCKSERVER_MAX_WEB_SOCKET_EXPECTATIONS, "" + count);
    }

    public static int webSocketProxyMaxRecordedFrames() {
        return readIntegerProperty(MOCKSERVER_WEB_SOCKET_PROXY_MAX_RECORDED_FRAMES, "MOCKSERVER_WEB_SOCKET_PROXY_MAX_RECORDED_FRAMES", 1000);
    }

    /**
     * <p>
     * Maximum number of WebSocket frames recorded per proxied (passthrough) WebSocket connection. When MockServer
     * proxies a WebSocket upgrade to a real upstream server (no matching mock expectation), the relayed frames
     * (text, binary, ping, pong, close) are captured into a per-connection transcript that is written to the event
     * log as a {@code FORWARDED_REQUEST} when the connection closes, so {@code retrieveRecordedRequests} and the
     * dashboard show the WebSocket traffic. Once this cap is reached the remaining frames on that connection are
     * relayed but not recorded (the transcript is flagged truncated), bounding memory on long-lived connections.
     * </p>
     * <p>
     * The default is 1000. Set to 0 to disable frame recording (the upgrade handshake is still recorded).
     * </p>
     *
     * @param count maximum number of relayed WebSocket frames recorded per proxied connection
     */
    public static void webSocketProxyMaxRecordedFrames(int count) {
        setProperty(MOCKSERVER_WEB_SOCKET_PROXY_MAX_RECORDED_FRAMES, "" + count);
    }

    public static int webSocketProxyIdleTimeoutSeconds() {
        return readIntegerProperty(MOCKSERVER_WEB_SOCKET_PROXY_IDLE_TIMEOUT_SECONDS, "MOCKSERVER_WEB_SOCKET_PROXY_IDLE_TIMEOUT_SECONDS", 0);
    }

    /**
     * <p>
     * Idle timeout (in seconds) for a proxied (passthrough) WebSocket connection. When set to a positive value, a
     * relayed WebSocket connection whose two directions have both been idle (no frame sent either way) for this many
     * seconds is closed, reaping half-open / abandoned relays.
     * </p>
     * <p>
     * The default is 0, which disables idle reaping — long-lived WebSocket connections that are legitimately idle
     * (e.g. waiting for server-pushed events) are left to TCP keep-alive and normal peer-close propagation. Raise it
     * only if you need MockServer to bound how long an idle passthrough relay is held open.
     * </p>
     *
     * @param seconds idle timeout in seconds for proxied WebSocket relays (0 disables)
     */
    public static void webSocketProxyIdleTimeoutSeconds(int seconds) {
        setProperty(MOCKSERVER_WEB_SOCKET_PROXY_IDLE_TIMEOUT_SECONDS, "" + seconds);
    }

    public static boolean outputMemoryUsageCsv() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV, "MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV", "false"));
    }

    /**
     * <p>Output JVM memory usage metrics to CSV file periodically called <strong>memoryUsage_&lt;yyyy-MM-dd&gt;.csv</strong></p>
     *
     * @param enable output of JVM memory metrics
     */
    public static void outputMemoryUsageCsv(boolean enable) {
        setProperty(MOCKSERVER_OUTPUT_MEMORY_USAGE_CSV, "" + enable);
    }

    public static String memoryUsageCsvDirectory() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY, "MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY", ".");
    }

    /**
     * <p>Directory to output JVM memory usage metrics CSV files to when outputMemoryUsageCsv enabled</p>
     *
     * @param directory directory to save JVM memory metrics CSV files
     */
    public static void memoryUsageCsvDirectory(String directory) {
        fileExists(directory);
        setProperty(MOCKSERVER_MEMORY_USAGE_CSV_DIRECTORY, directory);
    }

    // scalability

    public static boolean useNativeTransport() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_USE_NATIVE_TRANSPORT, "MOCKSERVER_USE_NATIVE_TRANSPORT", "" + true));
    }

    /**
     * If true (the default) MockServer will use the native epoll transport on Linux
     * for higher performance and to enable transparent-proxy SO_ORIGINAL_DST resolution.
     * Set to false to force the NIO transport on all platforms.
     * <p>
     * This property is read at start-up only.
     *
     * @param enable enable native transport when available
     */
    public static void useNativeTransport(boolean enable) {
        setProperty(MOCKSERVER_USE_NATIVE_TRANSPORT, "" + enable);
    }

    public static int nioEventLoopThreadCount() {
        return readIntegerProperty(MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT, "MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT", 5);
    }

    /**
     * <p>Netty worker thread pool size for handling requests and response.  These threads are used for fast non-blocking activities such as, reading and de-serialise all requests and responses.</p>
     *
     * @param count Netty worker thread pool size
     */
    public static void nioEventLoopThreadCount(int count) {
        setProperty(MOCKSERVER_NIO_EVENT_LOOP_THREAD_COUNT, "" + count);
    }

    public static int actionHandlerThreadCount() {
        return readIntegerProperty(MOCKSERVER_ACTION_HANDLER_THREAD_COUNT, "MOCKSERVER_ACTION_HANDLER_THREAD_COUNT", Math.max(5, Runtime.getRuntime().availableProcessors()));
    }

    /**
     * <p>Number of threads for the action handler thread pool</p>
     * <p>These threads are used for handling actions such as:</p>
     *     <ul>
     *         <li>serialising and writing expectation or proxied responses</li>
     *         <li>handling response delays in a non-blocking way (i.e. using a scheduler)</li>
     *         <li>executing class callbacks</li>
     *         <li>handling method / closure callbacks (using web sockets)</li>
     *     </ul>
     * <p>
     * <p>Default is maximum of 5 or available processors count</p>
     *
     * @param count Netty worker thread pool size
     */
    public static void actionHandlerThreadCount(int count) {
        setProperty(MOCKSERVER_ACTION_HANDLER_THREAD_COUNT, "" + count);
    }

    public static int clientNioEventLoopThreadCount() {
        return readIntegerProperty(MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT, "MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT", 5);
    }

    /**
     * <p>Client Netty worker thread pool size for handling requests and response.  These threads handle deserializing and serialising HTTP requests and responses and some other fast logic.</p>
     *
     * <p>Default is 5 threads</p>
     *
     * @param count Client Netty worker thread pool size
     */
    public static void clientNioEventLoopThreadCount(int count) {
        setProperty(MOCKSERVER_CLIENT_NIO_EVENT_LOOP_THREAD_COUNT, "" + count);
    }

    public static int webSocketClientEventLoopThreadCount() {
        return readIntegerProperty(MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT, "MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT", 5);
    }

    /**
     * <p>Web socket thread pool size for expectations with remote (not the same JVM) method callbacks (i.e. web sockets).</p>
     * <p>
     * Default is 5 threads
     *
     * @param count web socket worker thread pool size
     */
    public static void webSocketClientEventLoopThreadCount(int count) {
        setProperty(MOCKSERVER_WEB_SOCKET_CLIENT_EVENT_LOOP_THREAD_COUNT, "" + count);
    }

    public static long maxFutureTimeout() {
        return readLongPropertyWithMillisAlias(MOCKSERVER_MAX_FUTURE_TIMEOUT, "MOCKSERVER_MAX_FUTURE_TIMEOUT",
            MOCKSERVER_MAX_FUTURE_TIMEOUT_IN_MILLIS, "MOCKSERVER_MAX_FUTURE_TIMEOUT_IN_MILLIS", TimeUnit.SECONDS.toMillis(90));
    }

    /**
     * Maximum time allowed in milliseconds for any future to wait, for example when waiting for a response over a web socket callback.
     * <p>
     * Default is 90,000 ms
     *
     * @param milliseconds maximum time allowed in milliseconds
     */
    public static void maxFutureTimeout(long milliseconds) {
        setProperty(MOCKSERVER_MAX_FUTURE_TIMEOUT, "" + milliseconds);
    }

    public static boolean matchersFailFast() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_MATCHERS_FAIL_FAST, "MOCKSERVER_MATCHERS_FAIL_FAST", "" + true));
    }

    /**
     * If true (the default) request matchers will fail on the first non-matching field, if false request matchers will compare all fields.
     * This is useful to see all mismatching fields in the log event recording that a request matcher did not match.
     *
     * @param enable enabled request matchers failing fast
     */
    public static void matchersFailFast(boolean enable) {
        setProperty(MOCKSERVER_MATCHERS_FAIL_FAST, "" + enable);
    }

    public static boolean matchExactCase() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_MATCH_EXACT_CASE, "MOCKSERVER_MATCH_EXACT_CASE", "" + false));
    }

    /**
     * If false (the default) request matching for the method, path and string body is case-insensitive,
     * matching the historical behaviour. If true matching of those three fields becomes case-sensitive
     * (exact case). Header names and values, cookie names and values, and query string parameters are
     * always matched case-insensitively regardless of this setting.
     *
     * @param enable enabled exact-case (case-sensitive) matching of method, path and string body
     */
    public static void matchExactCase(boolean enable) {
        setProperty(MOCKSERVER_MATCH_EXACT_CASE, "" + enable);
    }

    public static boolean forwardConnectionPoolEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_CONNECTION_POOL_ENABLED, "MOCKSERVER_FORWARD_CONNECTION_POOL_ENABLED", "" + true));
    }

    /**
     * If true (the default) idle keep-alive HTTP/1.1 upstream connections are pooled (keyed by host,
     * port and scheme) and reused for subsequent requests to the same upstream, eliminating repeated
     * connection and TLS handshakes for proxy-heavy workloads and avoiding ephemeral-port exhaustion
     * under sustained forward load. If false every forwarded or proxied request opens a fresh upstream
     * TCP (and TLS) connection that is closed once the response is received, restoring the historical
     * behaviour of a fresh upstream connection per request (the opt-out for unusual upstreams).
     * <p>
     * Pooling is safe to default on because two independent guards close the only ways a reused
     * channel could be corrupt: (1) the outbound forward/proxy HTTP client runs on its own dedicated
     * event-loop group, disjoint from the server worker group, so a pooled channel reused inside a
     * synchronous local object-callback is never pinned to a server worker thread that is blocked in
     * that callback (which would otherwise self-deadlock the event loop); and (2) a channel is only
     * returned to the pool when its client codec is genuinely quiescent — a valid in-range status is
     * necessary but not sufficient, so the decoder must also have zero leftover undecoded bytes. Any
     * uncertainty fails closed (the channel is closed, not pooled). MockServer's {@code error()}
     * action (HttpError), which writes raw non-HTTP bytes and/or drops the connection, is therefore
     * never pooled.
     * <p>
     * Only plain HTTP/1.1 keep-alive connections are pooled. HTTP/2 and HTTP/3 (which multiplex
     * differently), binary forwarding, streaming responses, proxy-tunnelled connections, any
     * connection the upstream closed or that returned "Connection: close", and any reply that did not
     * parse as a valid HTTP response are never pooled and fall back to a fresh connection.
     *
     * @param enable enable pooling and reuse of idle keep-alive upstream HTTP/1.1 connections
     */
    public static void forwardConnectionPoolEnabled(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_ENABLED, "" + enable);
    }

    public static int forwardConnectionPoolMaxIdlePerKey() {
        return Math.max(1, readIntegerProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_IDLE_PER_KEY, "MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_IDLE_PER_KEY", 8));
    }

    /**
     * Maximum number of idle keep-alive upstream connections retained per upstream (host, port,
     * scheme). Surplus connections are closed rather than pooled, so the pool degrades gracefully
     * under saturation. Only relevant when {@code forwardConnectionPoolEnabled} is true.
     * <p>
     * Default is 8.
     *
     * @param maxIdlePerKey maximum idle connections retained per upstream
     */
    public static void forwardConnectionPoolMaxIdlePerKey(int maxIdlePerKey) {
        setProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_IDLE_PER_KEY, "" + maxIdlePerKey);
    }

    public static long forwardConnectionPoolIdleTimeoutMillis() {
        return Math.max(0L, readLongProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_IDLE_TIMEOUT_MILLIS, "MOCKSERVER_FORWARD_CONNECTION_POOL_IDLE_TIMEOUT_MILLIS", 30_000L));
    }

    /**
     * How long in milliseconds an idle pooled upstream connection is retained before it is closed
     * and evicted. Only relevant when {@code forwardConnectionPoolEnabled} is true.
     * <p>
     * Default is 30,000 ms. Set to 0 to disable idle eviction (connections are still discarded
     * when the upstream closes them).
     *
     * @param idleTimeoutMillis idle retention time in milliseconds, 0 to disable eviction
     */
    public static void forwardConnectionPoolIdleTimeoutMillis(long idleTimeoutMillis) {
        setProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_IDLE_TIMEOUT_MILLIS, "" + idleTimeoutMillis);
    }

    public static boolean forwardConnectionPoolKeepAlive() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE, "MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE", "" + false));
    }

    /**
     * If true, idle keep-alive upstream connections are RETAINED on release (kept warm) up to
     * {@code forwardConnectionPoolMaxTotalPerKey} per upstream, instead of being closed back down to
     * {@code forwardConnectionPoolMaxIdlePerKey}. This eliminates the connection churn that otherwise
     * caps a single instance's throughput under sustained high-rate, low-latency forwarding or load
     * injection: under fast turnover a burst of concurrent requests would otherwise each open a fresh
     * connection (because releases lag the dispatch) and the surplus would be closed back to the idle
     * cap, collapsing requests-per-connection. With keep-warm on, the warm set grows to match the
     * offered concurrency and is then reused. Truly idle warm connections are still reaped by the
     * {@code forwardConnectionPoolIdleTimeoutMillis} reaper, so the pool drains when load stops.
     * <p>
     * If false (the default) the pool's release-time close decision is byte-for-byte unchanged from
     * the historical behaviour (surplus over {@code forwardConnectionPoolMaxIdlePerKey} is closed).
     * Only relevant when {@code forwardConnectionPoolEnabled} is true.
     *
     * @param enable enable keep-warm retention of idle upstream connections under sustained load
     */
    public static void forwardConnectionPoolKeepAlive(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_KEEP_ALIVE, "" + enable);
    }

    public static int forwardConnectionPoolMaxTotalPerKey() {
        return Math.max(1, readIntegerProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_TOTAL_PER_KEY, "MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_TOTAL_PER_KEY", 2000));
    }

    /**
     * Maximum number of warm (idle) keep-alive upstream connections retained per upstream (host,
     * port, scheme) when {@code forwardConnectionPoolKeepAlive} is true. This bounds the warm set so
     * it cannot grow without limit; connections offered beyond this ceiling are closed. Has no effect
     * unless BOTH {@code forwardConnectionPoolEnabled} and {@code forwardConnectionPoolKeepAlive} are
     * true (it is never consulted when keep-warm is off, so the default-off path is unaffected).
     * <p>
     * Default is 2000. The effective ceiling is never below {@code forwardConnectionPoolMaxIdlePerKey}
     * (keep-warm only ever raises retention, never lowers it).
     *
     * @param maxTotalPerKey maximum warm idle connections retained per upstream under keep-warm
     */
    public static void forwardConnectionPoolMaxTotalPerKey(int maxTotalPerKey) {
        setProperty(MOCKSERVER_FORWARD_CONNECTION_POOL_MAX_TOTAL_PER_KEY, "" + maxTotalPerKey);
    }

    public static boolean forwardSocketKeepAlive() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE, "MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE", "" + true));
    }

    /**
     * If true (the default) the forward/proxy HTTP client enables TCP keepalive (SO_KEEPALIVE) on its
     * upstream connections, so the OS can detect dead or half-open upstream connections faster and keep
     * NAT/firewall mappings warm. On the native epoll transport the keepalive timers are tuned (see
     * {@code forwardSocketKeepAliveIdleSeconds}/{@code IntervalSeconds}/{@code Count}); on NIO only
     * SO_KEEPALIVE is enabled (timer tuning requires epoll).
     * <p>
     * This is a benign default behaviour change from older versions (which set no SO_KEEPALIVE): it is
     * the standard for production HTTP clients, costs only an occasional probe packet on otherwise-idle
     * connections, and complements (does not replace) the connection pool's existing liveness checks.
     * Set to false to restore the historical behaviour of no socket keepalive.
     *
     * @param enable enable TCP keepalive on forward/proxy upstream connections
     */
    public static void forwardSocketKeepAlive(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE, "" + enable);
    }

    public static int forwardSocketKeepAliveIdleSeconds() {
        return Math.max(1, readIntegerProperty(MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_IDLE_SECONDS, "MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_IDLE_SECONDS", 60));
    }

    /**
     * Seconds a forward/proxy upstream connection may sit idle before the first TCP keepalive probe is
     * sent (epoll {@code TCP_KEEPIDLE}). Only applied on the native epoll transport when
     * {@code forwardSocketKeepAlive} is true. Default 60.
     *
     * @param idleSeconds idle time in seconds before the first keepalive probe
     */
    public static void forwardSocketKeepAliveIdleSeconds(int idleSeconds) {
        setProperty(MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_IDLE_SECONDS, "" + idleSeconds);
    }

    public static int forwardSocketKeepAliveIntervalSeconds() {
        return Math.max(1, readIntegerProperty(MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_INTERVAL_SECONDS, "MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_INTERVAL_SECONDS", 15));
    }

    /**
     * Seconds between successive TCP keepalive probes once probing has started (epoll
     * {@code TCP_KEEPINTVL}). Only applied on the native epoll transport when
     * {@code forwardSocketKeepAlive} is true. Default 15.
     *
     * @param intervalSeconds interval in seconds between keepalive probes
     */
    public static void forwardSocketKeepAliveIntervalSeconds(int intervalSeconds) {
        setProperty(MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_INTERVAL_SECONDS, "" + intervalSeconds);
    }

    public static int forwardSocketKeepAliveCount() {
        return Math.max(1, readIntegerProperty(MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_COUNT, "MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_COUNT", 4));
    }

    /**
     * Number of unacknowledged TCP keepalive probes after which the upstream connection is considered
     * dead and closed (epoll {@code TCP_KEEPCNT}). Only applied on the native epoll transport when
     * {@code forwardSocketKeepAlive} is true. Default 4. With the defaults (idle 60s, interval 15s,
     * count 4) a dead peer is detected roughly 60 + 4&times;15 = 120s after it goes idle.
     *
     * @param count number of failed keepalive probes before the connection is dropped
     */
    public static void forwardSocketKeepAliveCount(int count) {
        setProperty(MOCKSERVER_FORWARD_SOCKET_KEEP_ALIVE_COUNT, "" + count);
    }

    public static int forwardProxyRetryCount() {
        return Math.max(0, readIntegerProperty(MOCKSERVER_FORWARD_PROXY_RETRY_COUNT, "MOCKSERVER_FORWARD_PROXY_RETRY_COUNT", 0));
    }

    /**
     * The maximum number of times a forwarded or proxied request to an upstream is retried after a
     * transient failure (connection refused, connection reset, timeout, or a 502/503/504 from the
     * upstream). Only requests using an idempotent HTTP method (GET, HEAD, OPTIONS, PUT, DELETE,
     * TRACE) are retried; non-idempotent methods (POST, PATCH) are never retried so a request is
     * not silently executed twice.
     * <p>
     * Default is 0, which preserves the historical behaviour of forwarding each request exactly
     * once with no retry. Negative values are treated as 0.
     *
     * @param retryCount maximum number of retries for idempotent forwarded/proxied requests, 0 to disable
     */
    public static void forwardProxyRetryCount(int retryCount) {
        setProperty(MOCKSERVER_FORWARD_PROXY_RETRY_COUNT, "" + retryCount);
    }

    public static long forwardProxyRetryBackoffMillis() {
        return Math.max(0L, readLongProperty(MOCKSERVER_FORWARD_PROXY_RETRY_BACKOFF_MILLIS, "MOCKSERVER_FORWARD_PROXY_RETRY_BACKOFF_MILLIS", 100L));
    }

    /**
     * The base back-off in milliseconds applied between forward/proxy retry attempts. The delay
     * grows linearly with the attempt number (attempt 1 waits one base delay, attempt 2 waits two,
     * and so on) so a flaky upstream is not hammered. Only relevant when
     * {@code forwardProxyRetryCount} is greater than 0.
     * <p>
     * Default is 100 ms. Set to 0 to retry immediately with no back-off. Negative values are
     * treated as 0.
     *
     * @param backoffMillis base linear back-off in milliseconds between retry attempts, 0 to disable
     */
    public static void forwardProxyRetryBackoffMillis(long backoffMillis) {
        setProperty(MOCKSERVER_FORWARD_PROXY_RETRY_BACKOFF_MILLIS, "" + backoffMillis);
    }

    public static boolean forwardProxyHttp2Enabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_HTTP2_ENABLED, "MOCKSERVER_FORWARD_PROXY_HTTP2_ENABLED", "" + false));
    }

    /**
     * If false (the default) every forwarded or proxied request is sent to its upstream over HTTP/1.1,
     * regardless of the protocol the inbound request used, matching the historical behaviour. If true
     * the inbound request's protocol is preserved when forwarding, so an HTTP/2 inbound request is
     * forwarded to the upstream as HTTP/2.
     * <p>
     * Limitations: HTTP/2 upstream forwarding only happens over TLS with ALPN negotiation (there is no
     * h2c prior-knowledge / cleartext path — a non-secure HTTP/2 request is automatically downgraded to
     * HTTP/1.1). HTTP/2 forward connections are not pooled or multiplexed across forwards; the forward
     * connection pool remains HTTP/1.1-only.
     *
     * @param enable preserve the inbound request protocol (e.g. HTTP/2) when forwarding upstream
     */
    public static void forwardProxyHttp2Enabled(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_PROXY_HTTP2_ENABLED, "" + enable);
    }

    public static boolean forwardProxyHttp2Upgrade() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_HTTP2_UPGRADE, "MOCKSERVER_FORWARD_PROXY_HTTP2_UPGRADE", "" + false));
    }

    /**
     * If false (the default) a forwarded or proxied request is only sent upstream over HTTP/2 when the
     * inbound request itself used HTTP/2 and {@code forwardProxyHttp2Enabled} is set. If true, a secure
     * (TLS) forward is sent upstream over HTTP/2 via ALPN even when the inbound client is HTTP/1.1, with
     * automatic fallback to HTTP/1.1 if the upstream does not negotiate h2.
     * <p>
     * This is useful when an upstream sends a streaming (Server-Sent Events) response head immediately
     * over HTTP/2 but withholds it over HTTP/1.1 — forwarding over HTTP/2 lets MockServer relay the head
     * to the client promptly. HTTP/2 only flows over TLS+ALPN (there is no h2c cleartext path — a
     * non-secure request is unaffected and stays HTTP/1.1). HTTP/2 forward connections are not pooled.
     *
     * @param enable forward a secure request upstream over HTTP/2 even when the inbound client is HTTP/1.1
     */
    public static void forwardProxyHttp2Upgrade(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_PROXY_HTTP2_UPGRADE, "" + enable);
    }

    public static boolean forwardProxyCircuitBreakerEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_ENABLED, "MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_ENABLED", "" + false));
    }

    /**
     * If false (the default) every forwarded or proxied request is attempted against its upstream
     * regardless of how many previous requests to that upstream failed, matching the historical
     * behaviour. If true a per-upstream circuit breaker (keyed by host and port) trips open after
     * {@code forwardProxyCircuitBreakerFailureThreshold} consecutive failures, failing subsequent
     * requests fast with a 503 for {@code forwardProxyCircuitBreakerWindowMillis} milliseconds
     * before allowing a single trial (half-open) request through. A successful trial closes the
     * breaker; a failed trial re-opens it for another window.
     *
     * @param enable enable the per-upstream forward/proxy circuit breaker
     */
    public static void forwardProxyCircuitBreakerEnabled(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_ENABLED, "" + enable);
    }

    public static int forwardProxyCircuitBreakerFailureThreshold() {
        return Math.max(1, readIntegerProperty(MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_FAILURE_THRESHOLD, "MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_FAILURE_THRESHOLD", 5));
    }

    /**
     * The number of consecutive failures to a single upstream (host and port) that trips the
     * forward/proxy circuit breaker open. Only relevant when
     * {@code forwardProxyCircuitBreakerEnabled} is true.
     * <p>
     * Default is 5. Values below 1 are treated as 1.
     *
     * @param failureThreshold consecutive failures that open the breaker for an upstream
     */
    public static void forwardProxyCircuitBreakerFailureThreshold(int failureThreshold) {
        setProperty(MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_FAILURE_THRESHOLD, "" + failureThreshold);
    }

    public static long forwardProxyCircuitBreakerWindowMillis() {
        return Math.max(1L, readLongProperty(MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_WINDOW_MILLIS, "MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_WINDOW_MILLIS", 30_000L));
    }

    /**
     * How long in milliseconds the forward/proxy circuit breaker stays open (failing requests fast
     * with a 503) for an upstream before it transitions to half-open and lets a single trial
     * request through. Only relevant when {@code forwardProxyCircuitBreakerEnabled} is true.
     * <p>
     * Default is 30,000 ms. Values below 1 are treated as 1.
     *
     * @param windowMillis open-state duration in milliseconds before a half-open trial
     */
    public static void forwardProxyCircuitBreakerWindowMillis(long windowMillis) {
        setProperty(MOCKSERVER_FORWARD_PROXY_CIRCUIT_BREAKER_WINDOW_MILLIS, "" + windowMillis);
    }

    public static boolean enforceResponseValidationForMocks() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ENFORCE_RESPONSE_VALIDATION_FOR_MOCKS, "MOCKSERVER_ENFORCE_RESPONSE_VALIDATION_FOR_MOCKS", "" + false));
    }

    /**
     * If false (the default) OpenAPI response validation of mock responses is advisory only —
     * validation failures are logged but the response is still returned to the client (matching the
     * historical behaviour of {@code openAPIResponseValidation}). If true a mock response that fails
     * OpenAPI response validation is replaced with a 502 error describing the violations, matching the
     * enforcement already available on the validation-proxy path ({@code validateProxyEnforce}).
     * <p>
     * This flag only has any effect when {@code openAPIResponseValidation} is also enabled.
     *
     * @param enable enable enforcement (fail/replace) of OpenAPI response validation for mock responses
     */
    public static void enforceResponseValidationForMocks(boolean enable) {
        setProperty(MOCKSERVER_ENFORCE_RESPONSE_VALIDATION_FOR_MOCKS, "" + enable);
    }

    public static boolean validateRequestsAgainstOpenApiSpec() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_VALIDATE_REQUESTS_AGAINST_OPENAPI_SPEC, "MOCKSERVER_VALIDATE_REQUESTS_AGAINST_OPENAPI_SPEC", "" + false));
    }

    /**
     * If false (the default) incoming requests matched by an OpenAPI-backed mock expectation are not
     * validated against the spec — behaviour is exactly as before. If true, when a request matches an
     * expectation created from an OpenAPI spec ({@code specUrlOrPayload}), the incoming request is
     * validated against that spec before the matched action is dispatched; a request that violates the
     * spec is rejected with a 400 status code describing the violations instead of the mock response.
     *
     * @param enable enable OpenAPI request validation for requests matched by OpenAPI-backed mocks
     */
    public static void validateRequestsAgainstOpenApiSpec(boolean enable) {
        setProperty(MOCKSERVER_VALIDATE_REQUESTS_AGAINST_OPENAPI_SPEC, "" + enable);
    }

    // socket

    public static long maxSocketTimeout() {
        return readLongPropertyWithMillisAlias(MOCKSERVER_MAX_SOCKET_TIMEOUT, "MOCKSERVER_MAX_SOCKET_TIMEOUT",
            MOCKSERVER_MAX_SOCKET_TIMEOUT_IN_MILLIS, "MOCKSERVER_MAX_SOCKET_TIMEOUT_IN_MILLIS", TimeUnit.SECONDS.toMillis(20));
    }

    /**
     * Maximum time in milliseconds allowed for a response from a socket
     * <p>
     * Default is 20,000 ms
     *
     * @param milliseconds maximum time in milliseconds allowed
     */
    public static void maxSocketTimeout(long milliseconds) {
        setProperty(MOCKSERVER_MAX_SOCKET_TIMEOUT, "" + milliseconds);
    }

    public static long socketConnectionTimeout() {
        return readLongPropertyWithMillisAlias(MOCKSERVER_SOCKET_CONNECTION_TIMEOUT, "MOCKSERVER_SOCKET_CONNECTION_TIMEOUT",
            MOCKSERVER_SOCKET_CONNECTION_TIMEOUT_IN_MILLIS, "MOCKSERVER_SOCKET_CONNECTION_TIMEOUT_IN_MILLIS", TimeUnit.SECONDS.toMillis(20));
    }

    /**
     * Maximum time in milliseconds allowed to connect to a socket
     * <p>
     * Default is 20,000 ms
     *
     * @param milliseconds maximum time allowed in milliseconds
     */
    public static void socketConnectionTimeout(long milliseconds) {
        setProperty(MOCKSERVER_SOCKET_CONNECTION_TIMEOUT, "" + milliseconds);
    }

    public static long connectionDelayMillis() {
        return readLongProperty(MOCKSERVER_CONNECTION_DELAY_MILLIS, "MOCKSERVER_CONNECTION_DELAY_MILLIS", 0L);
    }

    public static void connectionDelayMillis(long milliseconds) {
        setProperty(MOCKSERVER_CONNECTION_DELAY_MILLIS, "" + milliseconds);
    }

    /**
     * <p>If true socket connections will always be closed after a response is returned, if false connection is only closed if request header indicate connection should be closed.</p>
     * <p>
     * Default is false
     *
     * @param alwaysClose true socket connections will always be closed after a response is returned
     */
    public static void alwaysCloseSocketConnections(boolean alwaysClose) {
        setProperty(MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS, "" + alwaysClose);
    }

    public static boolean alwaysCloseSocketConnections() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS, "MOCKSERVER_ALWAYS_CLOSE_SOCKET_CONNECTIONS", "false"));
    }

    // streaming proxy

    public static boolean streamingResponsesEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_STREAMING_RESPONSES_ENABLED, "MOCKSERVER_STREAMING_RESPONSES_ENABLED", "true"));
    }

    /**
     * If true (the default) streaming responses (Server-Sent Events with {@code Content-Type: text/event-stream})
     * received while proxying are relayed to the client incrementally as they arrive, instead of being fully
     * buffered before being forwarded. This keeps streaming APIs (such as LLM APIs) responsive when proxied.
     * Only SSE responses are detected as streaming; ordinary chunked responses are aggregated normally.
     * <p>
     * Default is true
     *
     * @param enable enable incremental relay of streaming responses while proxying
     */
    public static void streamingResponsesEnabled(boolean enable) {
        setProperty(MOCKSERVER_STREAMING_RESPONSES_ENABLED, "" + enable);
    }

    public static int maxStreamingCaptureBytes() {
        return Math.max(0, readIntegerProperty(MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES, "MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES", 262144));
    }

    /**
     * The maximum number of bytes of a streaming response body captured into the event log while relaying it.
     * The full stream is always relayed to the client; this only bounds how much is retained for the dashboard
     * and retrieve API. Once exceeded the logged body is truncated and flagged.
     * <p>
     * Default is 262144 (256 KB)
     *
     * @param bytes maximum number of streaming response body bytes captured into the event log
     */
    public static void maxStreamingCaptureBytes(int bytes) {
        setProperty(MOCKSERVER_MAX_STREAMING_CAPTURE_BYTES, "" + bytes);
    }

    public static int streamIdleTimeoutSeconds() {
        return Math.max(0, readIntegerProperty(MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS, "MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS", 60));
    }

    /**
     * The maximum time in seconds a streaming response connection may be idle (no chunk received) before it is
     * considered dead and closed. This replaces the fixed socket timeout for streaming responses, which would
     * otherwise terminate long-lived streams.
     * <p>
     * Default is 60 seconds
     *
     * @param seconds maximum idle time in seconds between streaming response chunks
     */
    public static void streamIdleTimeoutSeconds(int seconds) {
        setProperty(MOCKSERVER_STREAM_IDLE_TIMEOUT_SECONDS, "" + seconds);
    }

    public static String localBoundIP() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LOCAL_BOUND_IP, "MOCKSERVER_LOCAL_BOUND_IP", "");
    }

    /**
     * The local IP address to bind to for accepting new socket connections
     * <p>
     * Default is 0.0.0.0
     *
     * @param localBoundIP local IP address to bind to for accepting new socket connections
     */
    public static void localBoundIP(String localBoundIP) {
        if (isNotBlank(localBoundIP)) {
            setProperty(MOCKSERVER_LOCAL_BOUND_IP, InetAddresses.forString(localBoundIP).getHostAddress());
        }
    }

    // http request parsing

    public static int maxInitialLineLength() {
        return readIntegerProperty(MOCKSERVER_MAX_INITIAL_LINE_LENGTH, "MOCKSERVER_MAX_INITIAL_LINE_LENGTH", Integer.MAX_VALUE);
    }

    /**
     * Maximum size of the first line of an HTTP request
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param length maximum size of the first line of an HTTP request
     */
    public static void maxInitialLineLength(int length) {
        setProperty(MOCKSERVER_MAX_INITIAL_LINE_LENGTH, "" + length);
    }

    public static int maxHeaderSize() {
        return readIntegerProperty(MOCKSERVER_MAX_HEADER_SIZE, "MOCKSERVER_MAX_HEADER_SIZE", Integer.MAX_VALUE);
    }

    /**
     * Maximum size of HTTP request headers
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param size maximum size of HTTP request headers
     */
    public static void maxHeaderSize(int size) {
        setProperty(MOCKSERVER_MAX_HEADER_SIZE, "" + size);
    }

    public static int maxChunkSize() {
        return readIntegerProperty(MOCKSERVER_MAX_CHUNK_SIZE, "MOCKSERVER_MAX_CHUNK_SIZE", Integer.MAX_VALUE);
    }

    /**
     * Maximum size of HTTP chunks in request or responses
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param size maximum size of HTTP chunks in request or responses
     */
    public static void maxChunkSize(int size) {
        setProperty(MOCKSERVER_MAX_CHUNK_SIZE, "" + size);
    }

    public static int maxRequestBodySize() {
        return readIntegerProperty(MOCKSERVER_MAX_REQUEST_BODY_SIZE, "MOCKSERVER_MAX_REQUEST_BODY_SIZE", 10 * 1024 * 1024);
    }

    /**
     * Maximum aggregated body size (in bytes) accepted on inbound HTTP/1.1 and HTTP/2 requests
     * before MockServer responds with 413 Payload Too Large.
     * <p>
     * The default is 10,485,760 bytes (10 MiB). Raise this only if you intentionally mock
     * large uploads; very large limits make MockServer susceptible to memory exhaustion.
     *
     * @param size maximum inbound request body size in bytes
     */
    public static void maxRequestBodySize(int size) {
        setProperty(MOCKSERVER_MAX_REQUEST_BODY_SIZE, "" + size);
    }

    public static int maxGrpcMessageSize() {
        return readIntegerProperty(MOCKSERVER_MAX_GRPC_MESSAGE_SIZE, "MOCKSERVER_MAX_GRPC_MESSAGE_SIZE", 4 * 1024 * 1024);
    }

    /**
     * Maximum size (in bytes) of a single decoded gRPC message, before and after decompression.
     * <p>
     * A request message larger than this is rejected with {@code grpc-status: 8 RESOURCE_EXHAUSTED},
     * which is the status the gRPC specification (and grpc-java and grpc-go) use for exceeding the
     * receive-message-size limit.
     * <p>
     * The default is 4,194,304 bytes (4 MiB), matching grpc-java's and grpc-go's default
     * {@code maxReceiveMessageSize}. Raise it only if you intentionally mock large gRPC messages;
     * the limit is what stops a declared frame length from allocating unbounded memory.
     *
     * @param size maximum decoded gRPC message size in bytes
     */
    public static void maxGrpcMessageSize(int size) {
        setProperty(MOCKSERVER_MAX_GRPC_MESSAGE_SIZE, "" + size);
    }

    public static int maxResponseBodySize() {
        return readIntegerProperty(MOCKSERVER_MAX_RESPONSE_BODY_SIZE, "MOCKSERVER_MAX_RESPONSE_BODY_SIZE", 50 * 1024 * 1024);
    }

    /**
     * Maximum aggregated body size (in bytes) accepted on responses received from upstream
     * servers when MockServer is acting as a proxy or forwarder.
     * <p>
     * The default is 52,428,800 bytes (50 MiB).
     *
     * @param size maximum upstream response body size in bytes
     */
    public static void maxResponseBodySize(int size) {
        setProperty(MOCKSERVER_MAX_RESPONSE_BODY_SIZE, "" + size);
    }

    public static int maxLlmConversationBodySize() {
        int value = readIntegerProperty(MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE, "MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE", 1048576);
        if (value < 16384) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("maxLlmConversationBodySize value {} is below minimum, clamping to 16384")
                        .setArguments(value)
                );
            }
            return 16384;
        }
        if (value > 67108864) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat("maxLlmConversationBodySize value {} is above maximum, clamping to 67108864")
                        .setArguments(value)
                );
            }
            return 67108864;
        }
        return value;
    }

    /**
     * Maximum body size (in bytes) for LLM conversation request bodies.
     * <p>
     * The default is 1,048,576 bytes (1 MiB). Valid range is [16384, 67108864].
     * Values outside this range are silently clamped.
     *
     * @param size maximum LLM conversation body size in bytes
     */
    public static void maxLlmConversationBodySize(int size) {
        setProperty(MOCKSERVER_MAX_LLM_CONVERSATION_BODY_SIZE, "" + size);
    }

    /**
     * Provider type for the default runtime-LLM backend (one of the
     * {@link org.mockserver.model.Provider} enum names). Runtime-LLM features
     * (drift detection, semantic matching) are off unless a backend resolves;
     * this is layer 2 of backend resolution (single default backend). Empty by
     * default.
     */
    public static String llmProvider() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_PROVIDER, "MOCKSERVER_LLM_PROVIDER", "");
    }

    public static void llmProvider(String provider) {
        setProperty(MOCKSERVER_LLM_PROVIDER, provider);
    }

    /**
     * API key (secret) for the default runtime-LLM backend. Never logged or
     * emitted in config dumps — see {@link org.mockserver.llm.client.LlmBackend}.
     */
    public static String llmApiKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_API_KEY, "MOCKSERVER_LLM_API_KEY", "");
    }

    public static void llmApiKey(String apiKey) {
        setProperty(MOCKSERVER_LLM_API_KEY, apiKey);
    }

    /**
     * Model for the default runtime-LLM backend; empty means the per-provider
     * default applies.
     */
    public static String llmModel() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_MODEL, "MOCKSERVER_LLM_MODEL", "");
    }

    public static void llmModel(String model) {
        setProperty(MOCKSERVER_LLM_MODEL, model);
    }

    /**
     * Base URL override for the default runtime-LLM backend; empty means the
     * per-provider default applies.
     */
    public static String llmBaseUrl() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_BASE_URL, "MOCKSERVER_LLM_BASE_URL", "");
    }

    public static void llmBaseUrl(String baseUrl) {
        setProperty(MOCKSERVER_LLM_BASE_URL, baseUrl);
    }

    /**
     * Path to a JSON file declaring named runtime-LLM backends (layer 3 of
     * backend resolution). Empty by default.
     */
    public static String llmBackendsConfig() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_BACKENDS_CONFIG, "MOCKSERVER_LLM_BACKENDS_CONFIG", "");
    }

    public static void llmBackendsConfig(String path) {
        setProperty(MOCKSERVER_LLM_BACKENDS_CONFIG, path);
    }

    /**
     * Per-request timeout (milliseconds) for outbound runtime-LLM calls. A
     * backend's own {@code timeoutMillis} overrides this. Default 30000.
     */
    public static long llmRequestTimeoutMillis() {
        return readLongProperty(MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS, "MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS", 30000L);
    }

    public static void llmRequestTimeoutMillis(long millis) {
        setProperty(MOCKSERVER_LLM_REQUEST_TIMEOUT_MILLIS, "" + millis);
    }

    /**
     * Master switch for mock-drift analysis on forwarded responses. When {@code true}
     * (the default), every eligible forwarded upstream response is compared — on a
     * background scheduler thread — against any response-type stub expectations that
     * match the same request, recording structural and performance drift. When
     * {@code false}, the drift analysis (and the extra {@code allMatchingExpectation}
     * lookup it performs per forward) is skipped entirely, removing its cost from the
     * forward path. Default {@code true} to preserve the historical always-on behaviour.
     */
    public static boolean driftDetectionEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_DRIFT_DETECTION_ENABLED, "MOCKSERVER_DRIFT_DETECTION_ENABLED", "true"));
    }

    public static void driftDetectionEnabled(boolean enabled) {
        setProperty(MOCKSERVER_DRIFT_DETECTION_ENABLED, "" + enabled);
    }

    /**
     * Fraction of eligible forwarded responses to analyse for drift, in the range
     * {@code [0.0, 1.0]}. Default {@code 1.0} (analyse every eligible forward,
     * preserving the historical behaviour). A value below {@code 1.0} analyses only
     * that fraction of forwarded responses (sampled with a thread-safe random draw),
     * trading drift coverage for lower overhead under high forward volume. Values
     * outside the range are clamped to the nearest bound ({@code < 0} → {@code 0},
     * {@code > 1} → {@code 1}) with a WARN. Has no effect when
     * {@code driftDetectionEnabled} is {@code false}.
     */
    public static double driftSampleRate() {
        double value = readDoubleProperty(MOCKSERVER_DRIFT_SAMPLE_RATE, "MOCKSERVER_DRIFT_SAMPLE_RATE", 1.0d);
        if (value < 0.0d) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("driftSampleRate value {} is below minimum, clamping to 0.0")
                        .setArguments(value)
                );
            }
            return 0.0d;
        }
        if (value > 1.0d) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setType(LogEntry.LogMessageType.SERVER_CONFIGURATION)
                        .setLogLevel(Level.WARN)
                        .setMessageFormat("driftSampleRate value {} is above maximum, clamping to 1.0")
                        .setArguments(value)
                );
            }
            return 1.0d;
        }
        return value;
    }

    public static void driftSampleRate(double rate) {
        setProperty(MOCKSERVER_DRIFT_SAMPLE_RATE, "" + rate);
    }

    /**
     * Whether to enable LLM-powered semantic drift analysis. When enabled and a
     * runtime LLM backend is available, each structural drift record is enriched
     * with a severity classification (BREAKING / WARNING / INFORMATIONAL) and an
     * explanation from the LLM. Default false (opt-in).
     */
    public static boolean driftSemanticAnalysisEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED, "MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED", "false"));
    }

    public static void driftSemanticAnalysisEnabled(boolean enabled) {
        setProperty(MOCKSERVER_DRIFT_SEMANTIC_ANALYSIS_ENABLED, "" + enabled);
    }

    /**
     * Whether to record an append-only, bounded, in-memory audit log of
     * control-plane mutations (who/what/when/where/outcome). Off by default. When
     * disabled, control-plane operations behave byte-for-byte identically and no
     * audit entries are stored. The audit log never stores request headers or
     * bodies — only redacted, structural metadata. Retrieve via
     * {@code GET /mockserver/audit}.
     */
    public static boolean controlPlaneAuditEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_CONTROL_PLANE_AUDIT_ENABLED, "MOCKSERVER_CONTROL_PLANE_AUDIT_ENABLED", "false"));
    }

    public static void controlPlaneAuditEnabled(boolean enabled) {
        setProperty(MOCKSERVER_CONTROL_PLANE_AUDIT_ENABLED, "" + enabled);
    }

    /**
     * Maximum number of control-plane audit entries retained in the bounded
     * in-memory ring buffer. Once full, the oldest entry is evicted on each new
     * record. Default 1000. The {@code AuditStore} singleton reads this value
     * once at construction (fixed capacity, like the drift store).
     */
    public static int controlPlaneAuditMaxEntries() {
        return readIntegerProperty(MOCKSERVER_CONTROL_PLANE_AUDIT_MAX_ENTRIES, "MOCKSERVER_CONTROL_PLANE_AUDIT_MAX_ENTRIES", 1000);
    }

    public static void controlPlaneAuditMaxEntries(int maxEntries) {
        setProperty(MOCKSERVER_CONTROL_PLANE_AUDIT_MAX_ENTRIES, "" + maxEntries);
    }

    /**
     * Whether to also audit control-plane READ operations (e.g. GET requests and
     * read-only PUTs such as {@code /retrieve} and {@code /verify}). Default
     * false — only mutations (and {@code reset}) are audited. Has no effect
     * unless control-plane audit logging is enabled.
     */
    public static boolean controlPlaneAuditReads() {
        return Boolean.parseBoolean(readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_CONTROL_PLANE_AUDIT_READS, "MOCKSERVER_CONTROL_PLANE_AUDIT_READS", "false"));
    }

    public static void controlPlaneAuditReads(boolean enabled) {
        setProperty(MOCKSERVER_CONTROL_PLANE_AUDIT_READS, "" + enabled);
    }

    /**
     * Optional path to a durable control-plane audit log file. When set (and
     * {@code controlPlaneAuditEnabled} is true), every recorded {@link org.mockserver.mock.audit.AuditEntry}
     * is additionally appended as one JSON object per line ("NDJSON") to this file,
     * giving a restart-surviving audit trail that outlives the bounded in-memory
     * ring buffer (which is wiped by the very {@code reset} it records). Empty
     * default = off (behaviour unchanged). The path is resolved once on the first
     * entry written; parent directories are created if missing. The file grows
     * append-only — rotation is out of scope, use external log rotation if needed.
     */
    public static String auditLogFile() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_AUDIT_LOG_FILE, "MOCKSERVER_AUDIT_LOG_FILE", "");
    }

    public static void auditLogFile(String path) {
        setProperty(MOCKSERVER_AUDIT_LOG_FILE, path == null ? "" : path);
    }

    /**
     * p95 response time threshold (in milliseconds) for performance drift detection.
     * When set to a positive value, a PERFORMANCE drift record is emitted whenever
     * the p95 response time for an expectation exceeds this threshold. Default 0
     * (disabled).
     */
    public static long driftResponseTimeThresholdMs() {
        return readLongProperty(MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS, "MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS", 0L);
    }

    public static void driftResponseTimeThresholdMs(long thresholdMs) {
        setProperty(MOCKSERVER_DRIFT_RESPONSE_TIME_THRESHOLD_MS, "" + thresholdMs);
    }

    /**
     * Whether to fire a fire-and-forget HTTP POST webhook when a drift record of sufficient
     * severity is stored. Off by default (opt-in). A webhook failure never affects drift
     * analysis or the served response.
     */
    public static boolean driftAlertWebhookEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_DRIFT_ALERT_WEBHOOK_ENABLED, "MOCKSERVER_DRIFT_ALERT_WEBHOOK_ENABLED", "false"));
    }

    public static void driftAlertWebhookEnabled(boolean enabled) {
        setProperty(MOCKSERVER_DRIFT_ALERT_WEBHOOK_ENABLED, "" + enabled);
    }

    /**
     * The URL the drift-alert webhook POSTs to. Empty by default; an empty URL leaves the webhook
     * disabled even when enabled is true.
     */
    public static String driftAlertWebhookUrl() {
        return readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_DRIFT_ALERT_WEBHOOK_URL, "MOCKSERVER_DRIFT_ALERT_WEBHOOK_URL", "");
    }

    public static void driftAlertWebhookUrl(String url) {
        setProperty(MOCKSERVER_DRIFT_ALERT_WEBHOOK_URL, url == null ? "" : url);
    }

    /**
     * Minimum effective severity (BREAKING, WARNING or INFORMATIONAL) at which a stored drift record
     * fires the webhook. BREAKING is the most severe; INFORMATIONAL fires on every drift. Default
     * BREAKING.
     */
    public static String driftAlertSeverityThreshold() {
        return readPropertyHierarchically(
            PROPERTIES, MOCKSERVER_DRIFT_ALERT_SEVERITY_THRESHOLD, "MOCKSERVER_DRIFT_ALERT_SEVERITY_THRESHOLD", "BREAKING");
    }

    public static void driftAlertSeverityThreshold(String severity) {
        setProperty(MOCKSERVER_DRIFT_ALERT_SEVERITY_THRESHOLD, severity == null ? "BREAKING" : severity);
    }

    /**
     * De-dup cooldown window in milliseconds: a webhook fires at most once per
     * expectation/driftType/field signature within this window. Default 60000 (60s).
     */
    public static long driftAlertCooldownMillis() {
        return readLongProperty(MOCKSERVER_DRIFT_ALERT_COOLDOWN_MILLIS, "MOCKSERVER_DRIFT_ALERT_COOLDOWN_MILLIS", 60000L);
    }

    public static void driftAlertCooldownMillis(long cooldownMillis) {
        setProperty(MOCKSERVER_DRIFT_ALERT_COOLDOWN_MILLIS, "" + cooldownMillis);
    }

    /**
     * Comma-separated JSON field names whose values are redacted from recorded
     * fixture request/response bodies (in addition to the always-redacted
     * sensitive headers). Empty by default. Used by {@code record_llm_fixtures}.
     */
    public static String fixtureBodyRedactFields() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS, "MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS", "");
    }

    public static void fixtureBodyRedactFields(String fields) {
        setProperty(MOCKSERVER_FIXTURE_BODY_REDACT_FIELDS, fields);
    }

    /**
     * When true, loading LLM fixtures in strict VCR mode registers a low-priority
     * catch-all per cassette path so a request that matches no recorded entry
     * fails loudly (HTTP 599) instead of falling through. Default false.
     */
    public static boolean llmVcrStrict() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_VCR_STRICT, "MOCKSERVER_LLM_VCR_STRICT", "" + false));
    }

    public static void llmVcrStrict(boolean strict) {
        setProperty(MOCKSERVER_LLM_VCR_STRICT, "" + strict);
    }

    /**
     * Upper bound on the number of captured LLM calls included in an
     * optimisation report / brief ({@code GET /mockserver/llm/optimisationReport}
     * and the {@code export_optimisation_report} MCP tool). Bounds the report
     * size for very long sessions; the most recent calls are kept. Default 200.
     */
    public static int llmOptimisationMaxCalls() {
        return readIntegerProperty(MOCKSERVER_LLM_OPTIMISATION_MAX_CALLS, "MOCKSERVER_LLM_OPTIMISATION_MAX_CALLS", 200);
    }

    public static void llmOptimisationMaxCalls(int maxCalls) {
        setProperty(MOCKSERVER_LLM_OPTIMISATION_MAX_CALLS, "" + maxCalls);
    }

    /**
     * When true, MockServer's explicitly-defined metrics (the same gauges exposed
     * for Prometheus) are also exported via OpenTelemetry OTLP. Off by default.
     * No spans or auto-instrumentation are added — metrics only.
     */
    public static boolean otelMetricsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_METRICS_ENABLED, "MOCKSERVER_OTEL_METRICS_ENABLED", "" + false));
    }

    public static void otelMetricsEnabled(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_METRICS_ENABLED, "" + enabled);
    }

    /**
     * When true, MockServer emits explicit GenAI semantic-convention spans for LLM
     * traffic it serves (one span per completion, carrying provider, model, token
     * usage and finish reason) via OpenTelemetry OTLP. Off by default. These are
     * spans MockServer codes deliberately — no auto-instrumentation is added.
     */
    public static boolean otelTracesEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_TRACES_ENABLED, "MOCKSERVER_OTEL_TRACES_ENABLED", "" + false));
    }

    public static void otelTracesEnabled(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_TRACES_ENABLED, "" + enabled);
    }

    /**
     * Base OTLP HTTP endpoint for the collector (e.g. {@code http://localhost:4318}).
     * The {@code /v1/metrics} and {@code /v1/traces} paths are appended per signal.
     * Empty uses the OTLP exporter defaults ({@code http://localhost:4318}). A value
     * that already ends in {@code /v1/metrics} or {@code /v1/traces} is accepted and
     * normalised to the base.
     * <p>
     * When the MockServer-specific property/env ({@code mockserver.otelEndpoint} /
     * {@code MOCKSERVER_OTEL_ENDPOINT}) is unset, the OpenTelemetry-standard
     * {@code OTEL_EXPORTER_OTLP_ENDPOINT} environment variable is honoured as a
     * fallback so existing OTel deployments work without extra configuration. The
     * MockServer-specific value always takes precedence. A blank/whitespace-only
     * {@code MOCKSERVER_OTEL_ENDPOINT} env var is treated as unset (per the
     * {@code isNotBlank} env-var semantics in {@link #readPropertyHierarchically}),
     * so the standard-env fallback still applies.
     */
    public static String otelEndpoint() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_ENDPOINT, "MOCKSERVER_OTEL_ENDPOINT", otelEndpointFallbackDefault(System.getenv("OTEL_EXPORTER_OTLP_ENDPOINT")));
    }

    /**
     * The default returned by {@link #otelEndpoint()} when neither the MockServer-specific
     * property nor env var is set: the OpenTelemetry-standard {@code OTEL_EXPORTER_OTLP_ENDPOINT}
     * value when present, otherwise empty. Package-private and parameterised so the fallback
     * precedence can be unit-tested without mutating the process environment.
     */
    static String otelEndpointFallbackDefault(String standardEndpointEnvValue) {
        return isNotBlank(standardEndpointEnvValue) ? standardEndpointEnvValue : "";
    }

    public static void otelEndpoint(String endpoint) {
        setProperty(MOCKSERVER_OTEL_ENDPOINT, endpoint);
    }

    /**
     * How often (seconds) OTel metrics are exported. Default 60.
     */
    public static long otelMetricsExportIntervalSeconds() {
        // clamp to >= 1s; a zero/negative interval would make PeriodicMetricReader throw
        return Math.max(1L, readLongProperty(MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS, "MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS", 60L));
    }

    public static void otelMetricsExportIntervalSeconds(long seconds) {
        setProperty(MOCKSERVER_OTEL_METRICS_EXPORT_INTERVAL_SECONDS, "" + seconds);
    }

    /**
     * OTLP aggregation temporality for counter/histogram instruments: {@code cumulative} (default,
     * exactly the previous behaviour) or {@code delta}. Backends such as New Relic prefer delta.
     * Kept as a plain string here; the exporter parses it and fails safe to cumulative on any
     * unknown/blank value. OTLP-only — Prometheus scrape/remote-write are inherently cumulative.
     */
    public static String otelMetricsTemporality() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_METRICS_TEMPORALITY, "MOCKSERVER_OTEL_METRICS_TEMPORALITY", "cumulative");
    }

    public static void otelMetricsTemporality(String temporality) {
        setProperty(MOCKSERVER_OTEL_METRICS_TEMPORALITY, temporality);
    }

    /**
     * When true, MockServer periodically pushes the same Prometheus metrics served at
     * {@code /mockserver/metrics} to a Prometheus Remote-Write v1 endpoint (Prometheus with
     * {@code --web.enable-remote-write-receiver}, Grafana Mimir, New Relic, VictoriaMetrics,
     * Thanos Receive). Off by default. Fail-soft — remote-write export never affects request
     * handling. Remote write is inherently cumulative (the Prometheus model); there is no delta.
     */
    public static boolean prometheusRemoteWriteEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROMETHEUS_REMOTE_WRITE_ENABLED, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_ENABLED", "" + false));
    }

    public static void prometheusRemoteWriteEnabled(boolean enabled) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_ENABLED, "" + enabled);
    }

    /**
     * The Prometheus Remote-Write v1 endpoint URL to POST to (e.g.
     * {@code http://localhost:9090/api/v1/write}). Empty by default; when remote-write is
     * enabled but this is blank the exporter logs a warning and does nothing.
     */
    public static String prometheusRemoteWriteUrl() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROMETHEUS_REMOTE_WRITE_URL, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_URL", "");
    }

    public static void prometheusRemoteWriteUrl(String url) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_URL, url);
    }

    /**
     * How often (seconds) metrics are pushed to the remote-write endpoint. Default 60. Clamped
     * to a minimum of 1 second.
     */
    public static long prometheusRemoteWriteIntervalSeconds() {
        return Math.max(1L, readLongProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_INTERVAL_SECONDS, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_INTERVAL_SECONDS", 60L));
    }

    public static void prometheusRemoteWriteIntervalSeconds(long seconds) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_INTERVAL_SECONDS, "" + seconds);
    }

    /**
     * Optional bearer token for the remote-write endpoint. When set, the exporter sends an
     * {@code Authorization: Bearer <token>} header. Takes precedence over basic auth.
     */
    public static String prometheusRemoteWriteBearerToken() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BEARER_TOKEN, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BEARER_TOKEN", "");
    }

    public static void prometheusRemoteWriteBearerToken(String token) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BEARER_TOKEN, token);
    }

    /**
     * Optional HTTP basic-auth username for the remote-write endpoint. Used only when no bearer
     * token is set; combined with {@link #prometheusRemoteWriteBasicAuthPassword()}.
     */
    public static String prometheusRemoteWriteBasicAuthUsername() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_USERNAME, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_USERNAME", "");
    }

    public static void prometheusRemoteWriteBasicAuthUsername(String username) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_USERNAME, username);
    }

    /**
     * Optional HTTP basic-auth password for the remote-write endpoint. Paired with
     * {@link #prometheusRemoteWriteBasicAuthUsername()}.
     */
    public static String prometheusRemoteWriteBasicAuthPassword() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_PASSWORD, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_PASSWORD", "");
    }

    public static void prometheusRemoteWriteBasicAuthPassword(String password) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_BASIC_AUTH_PASSWORD, password);
    }

    /**
     * Optional extra HTTP headers to add to each remote-write POST, as a comma-separated
     * {@code key=value} list (e.g. {@code X-Scope-OrgID=tenant-a,X-Custom=abc}). Applied after
     * the resolved auth header, so a user-supplied {@code Authorization} header may override it.
     * The raw string is returned here; the exporter parses it.
     */
    public static String prometheusRemoteWriteHeaders() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROMETHEUS_REMOTE_WRITE_HEADERS, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_HEADERS", "");
    }

    public static void prometheusRemoteWriteHeaders(String headers) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_HEADERS, headers);
    }

    /**
     * The Prometheus remote-write protocol version to encode and push: {@code v1} (the original
     * {@code prometheus.WriteRequest}, the default) or {@code v2} (the newer, symbol-interned
     * {@code io.prometheus.write.v2.Request}). Any unrecognised or blank value falls back to
     * {@code v1}. Choose {@code v2} only when the receiver advertises Remote-Write 2.0 support.
     */
    public static String prometheusRemoteWriteProtocolVersion() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROMETHEUS_REMOTE_WRITE_PROTOCOL_VERSION, "MOCKSERVER_PROMETHEUS_REMOTE_WRITE_PROTOCOL_VERSION", "v1");
    }

    public static void prometheusRemoteWriteProtocolVersion(String version) {
        setProperty(MOCKSERVER_PROMETHEUS_REMOTE_WRITE_PROTOCOL_VERSION, version);
    }

    /**
     * When true, MockServer copies the incoming W3C {@code traceparent} and
     * {@code tracestate} headers into mock responses. Off by default so
     * responses are not modified unless the user opts in.
     */
    public static boolean otelPropagateTraceContext() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT, "MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT", "" + false));
    }

    public static void otelPropagateTraceContext(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_PROPAGATE_TRACE_CONTEXT, "" + enabled);
    }

    /**
     * When true, MockServer generates a new W3C trace ID for incoming requests
     * that do not carry a {@code traceparent} header. Off by default.
     */
    public static boolean otelGenerateTraceId() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OTEL_GENERATE_TRACE_ID, "MOCKSERVER_OTEL_GENERATE_TRACE_ID", "" + false));
    }

    public static void otelGenerateTraceId(boolean enabled) {
        setProperty(MOCKSERVER_OTEL_GENERATE_TRACE_ID, "" + enabled);
    }

    /**
     * Opt-in switch for fuzzy, LLM-judged semantic prompt matching (the
     * {@code semanticMatch} conversation predicate). Off by default. Even when
     * on, it only activates if a runtime LLM backend resolves; otherwise the
     * predicate is ignored. Non-deterministic by nature — exploratory only,
     * never for CI assertions. See {@link org.mockserver.llm.semantic.SemanticMatching}.
     */
    public static boolean llmSemanticMatchingEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED, "MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED", "" + false));
    }

    public static void llmSemanticMatchingEnabled(boolean enabled) {
        setProperty(MOCKSERVER_LLM_SEMANTIC_MATCHING_ENABLED, "" + enabled);
    }

    /**
     * Opt-in switch for approximate LLM token-usage inference. When {@code true},
     * a mocked completion ({@code httpLlmResponse}) that does not declare
     * {@code usage} has approximate {@code prompt_tokens} / {@code completion_tokens}
     * filled in from the request and response text via
     * {@link org.mockserver.llm.TokenCounter} before the response is encoded. The
     * counts are an <strong>estimate</strong> (a character/word heuristic, not a real
     * tokenizer), not a provider's exact billing. Off by default, so existing
     * responses are unchanged (an absent {@code usage} continues to encode as zeros)
     * unless a user opts in. A completion that already declares {@code usage} is never
     * altered.
     */
    public static boolean llmInferUsageEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_INFER_USAGE_ENABLED, "MOCKSERVER_LLM_INFER_USAGE_ENABLED", "" + false));
    }

    public static void llmInferUsageEnabled(boolean enabled) {
        setProperty(MOCKSERVER_LLM_INFER_USAGE_ENABLED, "" + enabled);
    }

    public static boolean llmMetricsEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_METRICS_ENABLED, "MOCKSERVER_LLM_METRICS_ENABLED", "" + false));
    }

    /**
     * Enable LLM token and cost metrics. When enabled, MockServer parses forwarded LLM responses
     * to extract token usage and estimated cost, incrementing Prometheus counters labeled by provider
     * and model. The parse is the same one used for GenAI span export; enabling this property
     * activates the forward-path response parse even when OTLP tracing is off.
     * <p>
     * Default is false (off) to avoid parsing forwarded response bodies unless asked.
     *
     * @param enabled enable LLM metrics
     */
    public static void llmMetricsEnabled(boolean enabled) {
        setProperty(MOCKSERVER_LLM_METRICS_ENABLED, "" + enabled);
    }

    public static boolean perExpectationMetricsEnabled() {
        // Read the legacy "mockserver.perExpectationMetrics" key first (default false), then the
        // canonical "mockserver.perExpectationMetricsEnabled" key, using the legacy value as its
        // default so the canonical key wins when set and the legacy key still works when it is not.
        String legacy = readPropertyHierarchically(PROPERTIES, MOCKSERVER_PER_EXPECTATION_METRICS, "MOCKSERVER_PER_EXPECTATION_METRICS", "" + false);
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PER_EXPECTATION_METRICS_ENABLED, "MOCKSERVER_PER_EXPECTATION_METRICS_ENABLED", legacy));
    }

    /**
     * Enable the opt-in per-expectation Prometheus match counter
     * ({@code mock_server_expectation_matched}), labeled by the stable expectation id.
     * When enabled, MockServer registers one Prometheus time series per distinct
     * matched expectation id and increments it on every match. OFF by default because
     * per-expectation labels can explode Prometheus cardinality on large or churning
     * expectation sets; using the stable expectation id (not the request path) bounds
     * cardinality to the number of expectations. See docs/code/metrics.md.
     * <p>
     * Default is false (off) so the default scrape output is byte-for-byte unchanged.
     *
     * @param enabled enable per-expectation metrics
     */
    public static void perExpectationMetricsEnabled(boolean enabled) {
        setProperty(MOCKSERVER_PER_EXPECTATION_METRICS_ENABLED, "" + enabled);
    }

    public static boolean deduplicateRecordedExpectations() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DEDUPLICATE_RECORDED_EXPECTATIONS, "MOCKSERVER_DEDUPLICATE_RECORDED_EXPECTATIONS", "" + false));
    }

    /**
     * Enable opt-in post-processing of retrieved recorded (proxy SPY/CAPTURE) expectations
     * that deduplicates structurally-identical recorded request/response pairs and
     * templatizes variable id path segments (so that recorded calls to {@code /users/1},
     * {@code /users/2}, {@code /users/3} collapse into a single {@code /users/{id}}
     * expectation). The post-processor is conservative: it never merges differing
     * responses, never over-widens a single recorded id, and preserves order.
     * <p>
     * Default is false (off) so retrieved recorded expectations are byte-for-byte unchanged.
     *
     * @param enable enable deduplication and templatization of recorded expectations
     */
    public static void deduplicateRecordedExpectations(boolean enable) {
        setProperty(MOCKSERVER_DEDUPLICATE_RECORDED_EXPECTATIONS, "" + enable);
    }

    public static boolean templatizeRecordedValues() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TEMPLATIZE_RECORDED_VALUES, "MOCKSERVER_TEMPLATIZE_RECORDED_VALUES", "" + false));
    }

    /**
     * Enable opt-in generalization of volatile-looking query parameter, header and JSON
     * body leaf values in retrieved recorded (proxy SPY/CAPTURE) expectations. When enabled,
     * values that look like ids, UUIDs, ISO-8601 / epoch-millis timestamps, JWTs or long
     * opaque tokens are replaced with regex matchers (query/header values become {@code .+};
     * JSON body leaves become a {@code ${json-unit.regex}} placeholder) so the recorded
     * expectation is reusable rather than pinned to one captured value. Stable values
     * (short strings, words, booleans, small numbers) are preserved verbatim.
     * <p>
     * Only takes effect when {@link #deduplicateRecordedExpectations()} is also enabled,
     * because the post-processor only runs then. Default is false (off) so retrieved
     * recorded expectations are byte-for-byte unchanged unless explicitly enabled.
     *
     * @param enable enable value templatization of recorded expectations
     */
    public static void templatizeRecordedValues(boolean enable) {
        setProperty(MOCKSERVER_TEMPLATIZE_RECORDED_VALUES, "" + enable);
    }

    public static boolean redactSecretsInRecordedExpectations() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_REDACT_SECRETS_IN_RECORDED_EXPECTATIONS, "MOCKSERVER_REDACT_SECRETS_IN_RECORDED_EXPECTATIONS", "" + false));
    }

    /**
     * Enable opt-in redaction of secrets in retrieved recorded (proxy SPY/CAPTURE) expectations.
     * When enabled, sensitive header values ({@code Authorization}, {@code Proxy-Authorization},
     * {@code Cookie}, {@code Set-Cookie}, {@code x-api-key}, {@code api-key} — which also covers
     * bearer/token credentials carried in those headers) are masked with a placeholder before the
     * recorded expectations are returned, generated as client code, or persisted to JSON. This
     * prevents proxied credentials leaking into shared recordings.
     * <p>
     * Trade-off: a redacted recorded expectation can no longer replay against an upstream that
     * requires that credential, so redaction is opt-in and off by default.
     * <p>
     * Default is false (off) so retrieved recorded expectations are byte-for-byte unchanged.
     *
     * @param enable enable redaction of secrets in recorded expectations
     */
    public static void redactSecretsInRecordedExpectations(boolean enable) {
        setProperty(MOCKSERVER_REDACT_SECRETS_IN_RECORDED_EXPECTATIONS, "" + enable);
    }

    public static boolean redactSecretsInLog() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_REDACT_SECRETS_IN_LOG, "MOCKSERVER_REDACT_SECRETS_IN_LOG", "" + false));
    }

    /**
     * Enable opt-in redaction of secrets in the live event log and dashboard. When enabled,
     * sensitive request/response header values ({@code Authorization}, {@code Proxy-Authorization},
     * {@code Cookie}, {@code Set-Cookie}, {@code x-api-key}, {@code api-key} — which also covers
     * bearer/token credentials carried in those headers) are masked with a placeholder in the
     * logged requests shown by {@code retrieveLogMessages}/{@code retrieveRecordedRequests} and in
     * the dashboard event view. JSON body fields named in {@link #fixtureBodyRedactFields()} are
     * masked too. This prevents proxied or received credentials leaking into a shared dashboard or
     * exported log.
     * <p>
     * Redaction is applied only to the copies that are serialised for display/retrieval — request
     * matching and verification continue to see the original, unredacted values, so enabling this
     * does not change matching behaviour.
     * <p>
     * Default is false (off) so the event log and dashboard are byte-for-byte unchanged.
     *
     * @param enable enable redaction of secrets in the event log and dashboard
     */
    public static void redactSecretsInLog(boolean enable) {
        setProperty(MOCKSERVER_REDACT_SECRETS_IN_LOG, "" + enable);
    }

    /**
     * Return the configured LLM cost budget in USD, or a negative value (the default)
     * when no budget is set (disabled).
     */
    public static double llmCostBudgetUsd() {
        String raw = readPropertyHierarchically(PROPERTIES, MOCKSERVER_LLM_COST_BUDGET_USD, "MOCKSERVER_LLM_COST_BUDGET_USD", "-1.0");
        try {
            return Double.parseDouble(raw);
        } catch (NumberFormatException e) {
            return -1.0;
        }
    }

    /**
     * Set a cumulative LLM cost budget in USD. When the cumulative cost of all LLM completions
     * (mocked and forwarded) exceeds this budget, further LLM forwarding is halted with a 429
     * response. Set to a negative value or leave unset to disable the budget (the default).
     * <p>
     * The budget resets on {@code HttpState.reset()}.
     *
     * @param budgetUsd the budget in USD, or negative to disable
     */
    public static void llmCostBudgetUsd(double budgetUsd) {
        setProperty(MOCKSERVER_LLM_COST_BUDGET_USD, "" + budgetUsd);
    }

    public static long regexMatchingTimeoutMillis() {
        return readLongProperty(MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS, "MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS", 5000L);
    }

    /**
     * Maximum time (in milliseconds) allowed for evaluating a single regular expression
     * during request matching. A pathological pattern that exceeds this budget is treated
     * as a non-match (and a WARN log entry is written) so the server cannot be wedged by
     * exponential regex backtracking from an attacker-controlled expectation or input.
     * <p>
     * The default is 5000 milliseconds. The headroom over typical matching time keeps
     * normal patterns well clear of the cutoff while still bounding pathological
     * backtracking (which takes minutes to hours). Set to 0 or a negative value to
     * disable the timeout.
     *
     * @param milliseconds regex evaluation timeout in milliseconds
     */
    public static void regexMatchingTimeoutMillis(long milliseconds) {
        setProperty(MOCKSERVER_REGEX_MATCHING_TIMEOUT_MILLIS, "" + milliseconds);
    }

    public static long xpathMatchingTimeoutMillis() {
        return readLongProperty(MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS, "MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS", 5000L);
    }

    /**
     * Maximum time (in milliseconds) allowed for evaluating a single XPath expression
     * against an XML document during request matching. Exceeding this budget is treated as
     * a non-match and a WARN log entry is written, protecting MockServer from XPath-based
     * denial-of-service.
     * <p>
     * The default is 5000 milliseconds, well above typical XPath evaluation time, so the
     * timeout only fires on truly pathological expressions or documents. Set to 0 or a
     * negative value to disable the timeout.
     *
     * @param milliseconds XPath evaluation timeout in milliseconds
     */
    public static void xpathMatchingTimeoutMillis(long milliseconds) {
        setProperty(MOCKSERVER_XPATH_MATCHING_TIMEOUT_MILLIS, "" + milliseconds);
    }

    public static String customJsonUnitMatchersClass() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS, "MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS", "");
    }

    /**
     * Fully qualified name of a class implementing {@code org.mockserver.matchers.CustomJsonUnitMatcherProvider}.
     * When set, the class is instantiated via its public no-arg constructor and the matchers it
     * returns are registered with the json-unit configuration used for JSON body matching, so
     * expectations can reference them via the {@code ${json-unit.matches:name}} placeholder
     * (e.g. {@code { "price": "${json-unit.matches:largerThan}" }}).
     * <p>
     * Misconfigured providers (class not found, wrong type, constructor failure) are logged at
     * WARN and ignored - JSON body matching falls back to the built-in behaviour. Changing the
     * property at runtime causes the provider to be reloaded on the next match.
     * <p>
     * The default is the empty string (no custom matchers).
     *
     * @param customJsonUnitMatchersClass fully qualified provider class name
     */
    public static void customJsonUnitMatchersClass(String customJsonUnitMatchersClass) {
        setProperty(MOCKSERVER_CUSTOM_JSON_UNIT_MATCHERS_CLASS, customJsonUnitMatchersClass);
    }

    /**
     * If true semicolons are treated as a separator for a query parameter string, if false the semicolon is treated as a normal character that is part of a query parameter value.
     * <p>
     * The default is true
     *
     * @param useAsQueryParameterSeparator true semicolons are treated as a separator for a query parameter string
     */
    public static void useSemicolonAsQueryParameterSeparator(boolean useAsQueryParameterSeparator) {
        setProperty(MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR, "" + useAsQueryParameterSeparator);
    }

    public static boolean useSemicolonAsQueryParameterSeparator() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR, "MOCKSERVER_USE_SEMICOLON_AS_QUERY_PARAMETER_SEPARATOR", "true"));
    }

    /**
     * If true (the default) MockServer sends itself a single warm-up request immediately after it starts listening, on a background thread.
     * <p>
     * The very first request handled by a freshly started MockServer is noticeably slower than every request after it (typically a few hundred milliseconds) because the request-handling code (the HTTP codec, JSON serialisation and response writers) is only loaded and initialised when it is first used. The warm-up request pays that one-off cost in the background so the first request from your test or application is fast.
     * <p>
     * The warm-up runs in the background and never delays start up. Disable it (set to false) only if you want to avoid the single extra loopback request during start up — for example in a tightly locked-down environment where MockServer must not connect to itself.
     *
     * @param enable true (the default) to send a background warm-up request after start up
     */
    public static void startupWarmup(boolean enable) {
        setProperty(MOCKSERVER_STARTUP_WARMUP, "" + enable);
    }

    public static boolean startupWarmup() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_STARTUP_WARMUP, "MOCKSERVER_STARTUP_WARMUP", "true"));
    }

    /**
     * If true requests are assumed as binary if the method isn't one of "GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE" or "CONNECT"
     * <p>
     * The default is true
     *
     * @param assumeAllRequestsAreHttp if true requests are assumed as binary if the method isn't one of "GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE" or "CONNECT"
     */
    public static void assumeAllRequestsAreHttp(boolean assumeAllRequestsAreHttp) {
        setProperty(MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP, "" + assumeAllRequestsAreHttp);
    }

    public static boolean assumeAllRequestsAreHttp() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP, "MOCKSERVER_ASSUME_ALL_REQUESTS_ARE_HTTP", "false"));
    }

    /**
     * If false HTTP/2 is disabled and ALPN no longer advertises h2, so HTTP/2 capable clients are
     * forced to use HTTP/1.1 (and the HTTP/2 cleartext h2c upgrade is not detected)
     * <p>
     * The default is true
     *
     * @param http2Enabled if false HTTP/2 is disabled and clients are forced to use HTTP/1.1
     */
    public static void http2Enabled(boolean http2Enabled) {
        setProperty(MOCKSERVER_HTTP2_ENABLED, "" + http2Enabled);
    }

    public static boolean http2Enabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_HTTP2_ENABLED, "MOCKSERVER_HTTP2_ENABLED", "true"));
    }

    /**
     * If true the BinaryRequestProxyingHandler.binaryExchangeCallback is called before a response is received from the
     * remote host. This enables the proxying of messages without a response.
     * <p>
     * The default is false
     *
     * @param forwardBinaryRequestsAsynchronously target value
     */
    public static void forwardBinaryRequestsWithoutWaitingForResponse(boolean forwardBinaryRequestsAsynchronously) {
        setProperty(MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE, "" + forwardBinaryRequestsAsynchronously);
    }

    public static boolean forwardBinaryRequestsWithoutWaitingForResponse() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE, "MOCKSERVER_FORWARD_BINARY_REQUESTS_WITHOUT_WAITING_FOR_RESPONSE", "false"));
    }

    // CORS

    public static boolean enableCORSForAPI() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ENABLE_CORS_FOR_API, "MOCKSERVER_ENABLE_CORS_FOR_API", "false"));
    }

    /**
     * Enable CORS for MockServer REST API so that the API can be used for javascript running in browsers, such as selenium
     * <p>
     * The default is false
     *
     * @param enable CORS for MockServer REST API
     */
    public static void enableCORSForAPI(boolean enable) {
        setProperty(MOCKSERVER_ENABLE_CORS_FOR_API, "" + enable);
    }

    public static boolean enableCORSForAllResponses() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES, "MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES", "false"));
    }

    /**
     * Enable CORS for all responses from MockServer, including the REST API and expectation responses
     * <p>
     * The default is false
     *
     * @param enable CORS for all responses from MockServer
     */
    public static void enableCORSForAllResponses(boolean enable) {
        setProperty(MOCKSERVER_ENABLE_CORS_FOR_ALL_RESPONSES, "" + enable);
    }

    public static String corsAllowOrigin() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_ORIGIN, "MOCKSERVER_CORS_ALLOW_ORIGIN", "");
    }

    /**
     * <p>the value used for CORS in the access-control-allow-origin header.</p>
     * <p>The default is ""</p>
     *
     * @param corsAllowOrigin the value used for CORS in the access-control-allow-methods header
     */
    public static void corsAllowOrigin(String corsAllowOrigin) {
        setProperty(MOCKSERVER_CORS_ALLOW_ORIGIN, corsAllowOrigin);
    }

    public static String corsAllowMethods() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_METHODS, "MOCKSERVER_CORS_ALLOW_METHODS", "");
    }

    /**
     * <p>The value used for CORS in the access-control-allow-methods header.</p>
     * <p>The property default is blank; when blank, MockServer applies "CONNECT, DELETE, GET, HEAD, OPTIONS, POST, PUT, PATCH, TRACE" as a built-in fallback (see CORSHeaders).</p>
     *
     * @param corsAllowMethods the value used for CORS in the access-control-allow-methods header
     */
    public static void corsAllowMethods(String corsAllowMethods) {
        setProperty(MOCKSERVER_CORS_ALLOW_METHODS, corsAllowMethods);
    }

    public static String corsAllowHeaders() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_HEADERS, "MOCKSERVER_CORS_ALLOW_HEADERS", "");
    }

    /**
     * <p>the value used for CORS in the access-control-allow-headers and access-control-expose-headers headers.</p>
     * <p>In addition to this default value any headers specified in the request header access-control-request-headers also get added to access-control-allow-headers and access-control-expose-headers headers in a CORS response.</p>
     * <p>The property default is blank; when blank, MockServer applies "Allow, Content-Encoding, Content-Length, Content-Type, ETag, Expires, Last-Modified, Location, Server, Vary, Authorization" as a built-in fallback (see CORSHeaders).</p>
     *
     * @param corsAllowHeaders the value used for CORS in the access-control-allow-headers and access-control-expose-headers headers
     */
    public static void corsAllowHeaders(String corsAllowHeaders) {
        setProperty(MOCKSERVER_CORS_ALLOW_HEADERS, corsAllowHeaders);
    }

    public static boolean corsAllowCredentials() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CORS_ALLOW_CREDENTIALS, "MOCKSERVER_CORS_ALLOW_CREDENTIALS", "false"));
    }

    /**
     * The value used for CORS in the access-control-allow-credentials header.
     * <p>
     * The default is false
     *
     * @param allow the value used for CORS in the access-control-allow-credentials header
     */
    public static void corsAllowCredentials(boolean allow) {
        setProperty(MOCKSERVER_CORS_ALLOW_CREDENTIALS, "" + allow);
    }

    public static int corsMaxAgeInSeconds() {
        return readIntegerProperty(MOCKSERVER_CORS_MAX_AGE_IN_SECONDS, "MOCKSERVER_CORS_MAX_AGE_IN_SECONDS", 0);
    }

    /**
     * The value used for CORS in the access-control-max-age header.
     * <p>
     * The default is 0
     *
     * @param ageInSeconds the value used for CORS in the access-control-max-age header.
     */
    public static void corsMaxAgeInSeconds(int ageInSeconds) {
        setProperty(MOCKSERVER_CORS_MAX_AGE_IN_SECONDS, "" + ageInSeconds);
    }

    // default response headers

    public static String defaultResponseHeaders() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DEFAULT_RESPONSE_HEADERS, "MOCKSERVER_DEFAULT_RESPONSE_HEADERS", "");
    }

    /**
     * <p>Default response headers that MockServer stamps onto every response it returns (mock responses, control-plane / dashboard responses, and forwarded / proxied responses) using add-if-absent semantics, so a header explicitly set on the matched response always wins.</p>
     * <p>The format is a pipe (<code>|</code>) separated list of <code>name=value</code> pairs, e.g. <code>Server=MockServer|X-Trace-Id=abc123</code>. A header value may itself contain commas (e.g. <code>Cache-Control=no-cache, no-store</code>); only <code>|</code> separates headers and only the first <code>=</code> in each pair separates the name from the value.</p>
     * <p>The default is "" (no default response headers are added, so behaviour is unchanged).</p>
     *
     * @param defaultResponseHeaders pipe separated list of name=value header pairs added to responses if not already present
     */
    public static void defaultResponseHeaders(String defaultResponseHeaders) {
        setProperty(MOCKSERVER_DEFAULT_RESPONSE_HEADERS, defaultResponseHeaders);
    }

    // template restrictions

    public static String javascriptDisallowedClasses() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES, "MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES", "");
    }

    /**
     * Set comma separate list of classes not allowed to be used by javascript templates
     * <p>
     * The default is empty. Since 7.4.1 an empty value means NO class is resolvable (see
     * {@link #javascriptAllowedClasses(String)}), not "all allowed"; setting a deny-list widens that
     * default to "everything except these" and is not a security boundary — a deny-list cannot enumerate
     * every dangerous class, so denying {@code java.lang.Runtime} still leaves {@code java.lang.ProcessBuilder}
     * and {@code Class.forName} reach-through available. Prefer {@code javascriptAllowedClasses}.
     *
     * @param javascriptDisallowedClasses comma separated list of classes not allowed to be used
     */
    public static void javascriptDisallowedClasses(String javascriptDisallowedClasses) {
        setProperty(MOCKSERVER_JAVASCRIPT_DISALLOWED_CLASSES, javascriptDisallowedClasses);
    }

    public static String javascriptAllowedClasses() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_JAVASCRIPT_ALLOWED_CLASSES, "MOCKSERVER_JAVASCRIPT_ALLOWED_CLASSES", "");
    }

    /**
     * Set comma separated ALLOW-list of classes (or package prefixes ending in {@code .*}) that javascript
     * templates may resolve via {@code Java.type(...)}.
     * <p>
     * When set, this takes precedence over {@code javascriptDisallowedClasses} and NOTHING outside the list
     * can be resolved. An allow-list is the only form that is safe by construction: a deny-list cannot
     * enumerate every dangerous class, so denying {@code java.lang.Runtime} still leaves
     * {@code java.lang.ProcessBuilder} and {@code Class.forName} reach-through available.
     * <p>
     * The default is empty, which since 7.4.1 means NO class can be resolved by a JavaScript template —
     * templates render normally but cannot reach host classes at all. Grant only the classes your templates
     * legitimately need. The single entry {@code *} switches class restrictions off, letting a template
     * resolve any class as it could before 7.4.1; that makes a reachable control plane an RCE path
     * (GHSA-7pwj-xvc2-hfpc), so use it only when every template comes from a source you fully trust.
     *
     * @param javascriptAllowedClasses comma separated list of classes / package prefixes templates may use
     */
    public static void javascriptAllowedClasses(String javascriptAllowedClasses) {
        setProperty(MOCKSERVER_JAVASCRIPT_ALLOWED_CLASSES, javascriptAllowedClasses);
    }

    public static String javascriptDisallowedText() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT, "MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT", "");
    }

    /**
     * Set comma separate list of text not allowed to be contained in javascript templates
     * <p>
     * The default is all allowed
     *
     * @param javascriptDisallowedText comma separated list of text not allowed to be contained in javascript templates
     */
    public static void javascriptDisallowedText(String javascriptDisallowedText) {
        setProperty(MOCKSERVER_JAVASCRIPT_DISALLOWED_TEXT, javascriptDisallowedText);
    }

    public static long javascriptTemplateExecutionTimeout() {
        return readLongProperty(MOCKSERVER_JAVASCRIPT_TEMPLATE_EXECUTION_TIMEOUT, "MOCKSERVER_JAVASCRIPT_TEMPLATE_EXECUTION_TIMEOUT", 5_000L);
    }

    /**
     * Maximum time in milliseconds a JavaScript response template is allowed to run before it is
     * cancelled. A runaway or malicious template (for example one containing an infinite loop) would
     * otherwise pin the data-plane worker thread indefinitely; this cap aborts the evaluation with a
     * timeout error instead. Default is 5000 (5 seconds), which is far longer than any legitimate
     * template needs. Set to 0 (or a negative value) to disable the timeout and restore the previous
     * unbounded behaviour.
     *
     * @param millis template execution timeout in milliseconds, 0 or negative to disable
     */
    public static void javascriptTemplateExecutionTimeout(long millis) {
        setProperty(MOCKSERVER_JAVASCRIPT_TEMPLATE_EXECUTION_TIMEOUT, "" + millis);
    }


    public static boolean velocityDisallowClassLoading() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING, "MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING", "" + true));
    }

    /**
     * If true class loading is not allowed in velocity templates
     * <p>
     * The default is true — Velocity templates are sandboxed with Velocity's own SecureUberspector, so a
     * template cannot reach arbitrary Java classes (for example via {@code $request.class.classLoader
     * .loadClass(...)}) and from there execute OS commands. Set it to false ONLY when every template
     * rendered by this instance comes from a source you fully trust, and never when the control plane is
     * reachable by untrusted callers — an attacker who can register an expectation can then run code in
     * the MockServer process.
     *
     * @param velocityDisallowClassLoading class loading is not allowed in velocity templates
     */
    public static void velocityDisallowClassLoading(boolean velocityDisallowClassLoading) {
        setProperty(MOCKSERVER_VELOCITY_DISALLOW_CLASS_LOADING, "" + velocityDisallowClassLoading);
    }

    public static String velocityDisallowedText() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_VELOCITY_DISALLOWED_TEXT, "MOCKSERVER_VELOCITY_DISALLOWED_TEXT", "");
    }

    /**
     * Set comma separate list of text not allowed to be contained in velocity templates
     * <p>
     * The default is all allowed
     *
     * @param velocityDisallowedText comma separated list of text not allowed to be contained in velocity templates
     */
    public static void velocityDisallowedText(String velocityDisallowedText) {
        setProperty(MOCKSERVER_VELOCITY_DISALLOWED_TEXT, velocityDisallowedText);
    }

    public static String mustacheDisallowedText() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_MUSTACHE_DISALLOWED_TEXT, "MOCKSERVER_MUSTACHE_DISALLOWED_TEXT", "");
    }

    /**
     * Set comma separate list of text not allowed to be contained in mustache templates
     * <p>
     * The default is all allowed
     *
     * @param mustacheDisallowedText comma separated list of text not allowed to be contained in mustache templates
     */
    public static void mustacheDisallowedText(String mustacheDisallowedText) {
        setProperty(MOCKSERVER_MUSTACHE_DISALLOWED_TEXT, mustacheDisallowedText);
    }

    public static long templateFakerSeed() {
        return readLongProperty(MOCKSERVER_TEMPLATE_FAKER_SEED, "MOCKSERVER_TEMPLATE_FAKER_SEED", 0L);
    }

    /**
     * Seed for the template {@code faker} sample-data helper (net.datafaker).
     * <p>
     * The default is 0, which leaves faker unseeded so it produces different, time/random-based
     * sample values on every render (behaviour unchanged). Set a non-zero value to seed faker
     * deterministically so faker-driven templates generate reproducible fixtures across runs — the
     * template analogue of the OpenAPI example generator's fixed-seed model. The seed initialises a
     * per-engine faker whose sequence is deterministic for a given order of renders; determinism is
     * strongest for sequential (single-threaded) fixture generation.
     *
     * @param seed faker seed, 0 to leave faker unseeded (random)
     */
    public static void templateFakerSeed(long seed) {
        setProperty(MOCKSERVER_TEMPLATE_FAKER_SEED, "" + seed);
    }

    // mock initialization

    public static String initializationClass() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_INITIALIZATION_CLASS, "MOCKSERVER_INITIALIZATION_CLASS", "");
    }

    /**
     * The class (and package) used to initialize expectations in MockServer at startup, if set MockServer will load and call this class to initialise expectations when is starts.
     * <p>
     * The default is null
     *
     * @param initializationClass class (and package) used to initialize expectations in MockServer at startup
     */
    public static void initializationClass(String initializationClass) {
        setProperty(MOCKSERVER_INITIALIZATION_CLASS, initializationClass);
    }

    public static String initializationJsonPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_INITIALIZATION_JSON_PATH, "MOCKSERVER_INITIALIZATION_JSON_PATH", "");
    }

    /**
     * <p>The path to the json file used to initialize expectations in MockServer at startup, if set MockServer will load this file and initialise expectations for each item in the file when is starts.</p>
     * <p>The expected format of the file is a JSON array of expectations, as per the <a target="_blank" href="https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.15.x#/Expectations" target="_blank">REST API format</a></p>
     * <p>To watch multiple files use a file globs as documented here: https://mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_json_glob_patterns</p>
     *
     * @param initializationJsonPath path to the json file used to initialize expectations in MockServer at startup
     */
    public static void initializationJsonPath(String initializationJsonPath) {
        setProperty(MOCKSERVER_INITIALIZATION_JSON_PATH, initializationJsonPath);
    }

    public static String initializationOpenAPIPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_INITIALIZATION_OPENAPI_PATH, "MOCKSERVER_INITIALIZATION_OPENAPI_PATH", "");
    }

    /**
     * <p>The path to the OpenAPI spec file used to initialize expectations in MockServer at startup, if set MockServer will load this file and create expectations for each operation when it starts.</p>
     * <p>The file can be a YAML (.yaml, .yml) or JSON (.json) OpenAPI v3 specification.</p>
     * <p>To watch multiple files use file globs as documented here: https://mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_json_glob_patterns</p>
     *
     * @param initializationOpenAPIPath path to the OpenAPI spec file used to initialize expectations in MockServer at startup
     */
    public static void initializationOpenAPIPath(String initializationOpenAPIPath) {
        setProperty(MOCKSERVER_INITIALIZATION_OPENAPI_PATH, initializationOpenAPIPath);
    }

    public static String openAPIContextPathPrefix() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX, "MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX", "");
    }

    /**
     * <p>A path prefix to add to all paths generated from OpenAPI specifications.</p>
     * <p>For example, if set to "/api/v1" then a path "/pets" from the spec becomes "/api/v1/pets".</p>
     *
     * @param openAPIContextPathPrefix the path prefix to add to OpenAPI paths
     */
    public static void openAPIContextPathPrefix(String openAPIContextPathPrefix) {
        setProperty(MOCKSERVER_OPENAPI_CONTEXT_PATH_PREFIX, openAPIContextPathPrefix);
    }

    public static boolean openAPIResponseValidation() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_OPENAPI_RESPONSE_VALIDATION, "MOCKSERVER_OPENAPI_RESPONSE_VALIDATION", "" + false));
    }

    /**
     * <p>If enabled MockServer will validate that mock responses conform to the OpenAPI spec schema they were generated from.</p>
     * <p>Validation is advisory only - responses are still returned to the client even if validation fails.</p>
     *
     * <p>The default is false</p>
     *
     * @param enable if enabled mock responses will be validated against the OpenAPI spec schema
     */
    public static void openAPIResponseValidation(boolean enable) {
        setProperty(MOCKSERVER_OPENAPI_RESPONSE_VALIDATION, "" + enable);
    }

    public static String validateProxyOpenAPISpec() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_VALIDATE_PROXY_OPENAPI_SPEC, "MOCKSERVER_VALIDATE_PROXY_OPENAPI_SPEC", "");
    }

    /**
     * <p>When set to an OpenAPI spec URL, file path, or inline JSON/YAML, MockServer validates every forwarded/proxied
     * request and its upstream response against the spec and records violations as log events of type
     * {@code OPENAPI_RESPONSE_VALIDATION_FAILED}. By default, validation is report-only (traffic is not blocked).</p>
     *
     * <p>The default is empty (disabled)</p>
     *
     * @param specUrlOrPayload the OpenAPI spec URL, file path, or inline payload to validate against
     */
    public static void validateProxyOpenAPISpec(String specUrlOrPayload) {
        setProperty(MOCKSERVER_VALIDATE_PROXY_OPENAPI_SPEC, specUrlOrPayload);
    }

    public static boolean validateProxyEnforce() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_VALIDATE_PROXY_ENFORCE, "MOCKSERVER_VALIDATE_PROXY_ENFORCE", "" + false));
    }

    /**
     * <p>When enabled (and {@code validateProxyOpenAPISpec} is set), forwarded requests that violate the OpenAPI spec
     * are rejected with a 400 status code, and upstream responses that violate the spec are replaced with a 502.
     * When disabled (the default), violations are logged but traffic flows unmodified.</p>
     *
     * <p>The default is false</p>
     *
     * @param enable if enabled, non-conformant forwarded traffic is blocked
     */
    public static void validateProxyEnforce(boolean enable) {
        setProperty(MOCKSERVER_VALIDATE_PROXY_ENFORCE, "" + enable);
    }

    public static boolean generateRealisticExampleValues() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_GENERATE_REALISTIC_EXAMPLE_VALUES, "MOCKSERVER_GENERATE_REALISTIC_EXAMPLE_VALUES", "" + false));
    }

    /**
     * <p>If enabled, OpenAPI example generation uses realistic, schema/format-aware values (via Datafaker) instead of static placeholder strings.</p>
     * <p>When disabled (the default), the existing static example values are used (e.g. "some_string_value", "some_email@mockserver.com").</p>
     *
     * <p>The default is false</p>
     *
     * @param enable if enabled OpenAPI examples will use realistic generated values
     */
    public static void generateRealisticExampleValues(boolean enable) {
        setProperty(MOCKSERVER_GENERATE_REALISTIC_EXAMPLE_VALUES, "" + enable);
    }

    public static boolean watchInitializationJson() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_WATCH_INITIALIZATION_JSON, "MOCKSERVER_WATCH_INITIALIZATION_JSON", "" + false));
    }

    /**
     * <p>If enabled the initialization json file will be watched for changes, any changes found will result in expectations being created, remove or updated by matching against their key.</p>
     * <p>If duplicate keys exist only the last duplicate key in the file will be processed and all duplicates except the last duplicate will be removed.</p>
     * <p>The order of expectations in the file is the order in which they are created if they are new, however, re-ordering existing expectations does not change the order they are matched against incoming requests.</p>
     *
     * <p>The default is false</p>
     *
     * @param enable if enabled the initialization json file will be watched for changes
     */
    public static void watchInitializationJson(boolean enable) {
        setProperty(MOCKSERVER_WATCH_INITIALIZATION_JSON, "" + enable);
    }

    public static boolean failOnInitializationError() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FAIL_ON_INITIALIZATION_ERROR, "MOCKSERVER_FAIL_ON_INITIALIZATION_ERROR", "" + false));
    }

    /**
     * <p>If enabled a failure to load any expectation initializer (a malformed initialization JSON / OpenAPI file or a broken initialization class) will fail server startup with an exception rather than logging a warning and continuing with zero expectations from that source.</p>
     *
     * <p>The default is false (a failed initializer is logged at WARN and startup continues).</p>
     *
     * @param enable if enabled a failed expectation initializer load fails server startup
     */
    public static void failOnInitializationError(boolean enable) {
        setProperty(MOCKSERVER_FAIL_ON_INITIALIZATION_ERROR, "" + enable);
    }

    // mock persistence

    public static boolean persistExpectations() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSIST_EXPECTATIONS, "MOCKSERVER_PERSIST_EXPECTATIONS", "" + false));
    }

    /**
     * Enable the persisting of expectations as json, which is updated whenever the expectation state is updated (i.e. add, clear, expires, etc)
     * <p>
     * The default is false
     *
     * @param enable the persisting of expectations as json
     */
    public static void persistExpectations(boolean enable) {
        setProperty(MOCKSERVER_PERSIST_EXPECTATIONS, "" + enable);
    }

    public static String persistedExpectationsPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSISTED_EXPECTATIONS_PATH, "MOCKSERVER_PERSISTED_EXPECTATIONS_PATH", "persistedExpectations.json");
    }

    /**
     * The file path used to save persisted expectations as json, which is updated whenever the expectation state is updated (i.e. add, clear, expires, etc)
     * <p>
     * The default is "persistedExpectations.json"
     *
     * @param persistedExpectationsPath file path used to save persisted expectations as json
     */
    public static void persistedExpectationsPath(String persistedExpectationsPath) {
        setProperty(MOCKSERVER_PERSISTED_EXPECTATIONS_PATH, persistedExpectationsPath);
    }

    // recorded expectation persistence

    public static boolean persistRecordedExpectations() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS, "MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS", "" + false));
    }

    /**
     * Enable the persisting of recorded expectations (proxy traffic) as json, which is updated whenever a new request is forwarded
     * <p>
     * The default is false
     *
     * @param enable the persisting of recorded expectations as json
     */
    public static void persistRecordedExpectations(boolean enable) {
        setProperty(MOCKSERVER_PERSIST_RECORDED_EXPECTATIONS, "" + enable);
    }

    public static String persistedRecordedExpectationsPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH, "MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH", "persistedRecordedExpectations.json");
    }

    /**
     * The file path used to save persisted recorded expectations as json, which is updated whenever a new request is forwarded
     * <p>
     * The default is "persistedRecordedExpectations.json"
     *
     * @param persistedRecordedExpectationsPath file path used to save persisted recorded expectations as json
     */
    public static void persistedRecordedExpectationsPath(String persistedRecordedExpectationsPath) {
        setProperty(MOCKSERVER_PERSISTED_RECORDED_EXPECTATIONS_PATH, persistedRecordedExpectationsPath);
    }

    // recorded request persistence (LLM-capture disk-offload)

    public static boolean persistRecordedRequestsToDisk() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSIST_RECORDED_REQUESTS_TO_DISK, "MOCKSERVER_PERSIST_RECORDED_REQUESTS_TO_DISK", "" + false));
    }

    /**
     * Enable the persisting of recorded requests (captured traffic) to disk
     * <p>
     * The default is false
     *
     * @param enable the persisting of recorded requests to disk
     */
    public static void persistRecordedRequestsToDisk(boolean enable) {
        setProperty(MOCKSERVER_PERSIST_RECORDED_REQUESTS_TO_DISK, "" + enable);
    }

    public static String persistedRecordedRequestsPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PERSISTED_RECORDED_REQUESTS_PATH, "MOCKSERVER_PERSISTED_RECORDED_REQUESTS_PATH", "recordedRequests.ndjson");
    }

    /**
     * The file path used to save persisted recorded requests, which is updated whenever a new request is captured
     * <p>
     * The default is "recordedRequests.ndjson"
     *
     * @param persistedRecordedRequestsPath file path used to save persisted recorded requests
     */
    public static void persistedRecordedRequestsPath(String persistedRecordedRequestsPath) {
        setProperty(MOCKSERVER_PERSISTED_RECORDED_REQUESTS_PATH, persistedRecordedRequestsPath);
    }

    // state backend (G10 phase 2a)

    /**
     * Returns the state backend type. Currently only "memory" is supported
     * (default). Phase 2b will add "infinispan" for clustered state.
     */
    public static String stateBackend() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_STATE_BACKEND, "MOCKSERVER_STATE_BACKEND", "memory");
    }

    /**
     * Sets the state backend type. Currently only "memory" is supported.
     *
     * @param stateBackend the backend type (e.g. "memory")
     */
    public static void stateBackend(String stateBackend) {
        setProperty(MOCKSERVER_STATE_BACKEND, stateBackend);
    }

    /**
     * Returns the blob store type. "filesystem" (default) delegates to the
     * existing file persistence paths so on-disk behaviour is unchanged;
     * "memory" keeps blobs in-memory only (lost on process exit).
     */
    public static String blobStoreType() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_TYPE, "MOCKSERVER_BLOB_STORE_TYPE", "filesystem");
    }

    /**
     * Sets the blob store type.
     *
     * @param blobStoreType the blob store type (e.g. "memory", "filesystem")
     */
    public static void blobStoreType(String blobStoreType) {
        setProperty(MOCKSERVER_BLOB_STORE_TYPE, blobStoreType);
    }

    // cloud blob store configuration

    /**
     * Returns the cloud blob store bucket name (S3 or GCS bucket).
     */
    public static String blobStoreBucket() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_BUCKET, "MOCKSERVER_BLOB_STORE_BUCKET", "");
    }

    public static void blobStoreBucket(String blobStoreBucket) {
        setProperty(MOCKSERVER_BLOB_STORE_BUCKET, blobStoreBucket);
    }

    /**
     * Returns the cloud blob store region (e.g. "us-east-1" for S3).
     */
    public static String blobStoreRegion() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_REGION, "MOCKSERVER_BLOB_STORE_REGION", "");
    }

    public static void blobStoreRegion(String blobStoreRegion) {
        setProperty(MOCKSERVER_BLOB_STORE_REGION, blobStoreRegion);
    }

    /**
     * Returns the cloud blob store endpoint override URL.
     */
    public static String blobStoreEndpoint() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_ENDPOINT, "MOCKSERVER_BLOB_STORE_ENDPOINT", "");
    }

    public static void blobStoreEndpoint(String blobStoreEndpoint) {
        setProperty(MOCKSERVER_BLOB_STORE_ENDPOINT, blobStoreEndpoint);
    }

    /**
     * Returns the key prefix for cloud blob store objects.
     */
    public static String blobStoreKeyPrefix() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_KEY_PREFIX, "MOCKSERVER_BLOB_STORE_KEY_PREFIX", "");
    }

    public static void blobStoreKeyPrefix(String blobStoreKeyPrefix) {
        setProperty(MOCKSERVER_BLOB_STORE_KEY_PREFIX, blobStoreKeyPrefix);
    }

    /**
     * Returns the explicit access key ID for cloud blob store authentication.
     */
    public static String blobStoreAccessKeyId() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID, "MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID", "");
    }

    public static void blobStoreAccessKeyId(String blobStoreAccessKeyId) {
        setProperty(MOCKSERVER_BLOB_STORE_ACCESS_KEY_ID, blobStoreAccessKeyId);
    }

    /**
     * Returns the explicit secret access key for cloud blob store authentication.
     */
    public static String blobStoreSecretAccessKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY, "MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY", "");
    }

    public static void blobStoreSecretAccessKey(String blobStoreSecretAccessKey) {
        setProperty(MOCKSERVER_BLOB_STORE_SECRET_ACCESS_KEY, blobStoreSecretAccessKey);
    }

    /**
     * Returns the Azure Blob Storage container name.
     */
    public static String blobStoreContainer() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_CONTAINER, "MOCKSERVER_BLOB_STORE_CONTAINER", "");
    }

    public static void blobStoreContainer(String blobStoreContainer) {
        setProperty(MOCKSERVER_BLOB_STORE_CONTAINER, blobStoreContainer);
    }

    /**
     * Returns the Azure Blob Storage connection string.
     */
    public static String blobStoreConnectionString() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_CONNECTION_STRING, "MOCKSERVER_BLOB_STORE_CONNECTION_STRING", "");
    }

    public static void blobStoreConnectionString(String blobStoreConnectionString) {
        setProperty(MOCKSERVER_BLOB_STORE_CONNECTION_STRING, blobStoreConnectionString);
    }

    /**
     * Returns the GCS project ID.
     */
    public static String blobStoreProjectId() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_BLOB_STORE_PROJECT_ID, "MOCKSERVER_BLOB_STORE_PROJECT_ID", "");
    }

    public static void blobStoreProjectId(String blobStoreProjectId) {
        setProperty(MOCKSERVER_BLOB_STORE_PROJECT_ID, blobStoreProjectId);
    }

    /**
     * Returns the maximum number of seconds MockServer will wait, during startup, for the
     * persisted-expectations document to be read back from a cloud blob store (S3, GCS or
     * Azure) before giving up and continuing to start.
     * <p>
     * This read happens before any listening port is bound, so an unreachable or
     * packet-dropping blob-store endpoint would otherwise delay startup for the cloud SDK's
     * full retry budget (for AWS SDK v2 that is 4 attempts x a 30 second socket timeout,
     * measured at roughly 120 seconds), long enough to fail readiness probes and container
     * wait strategies. The deadline bounds that: when it expires MockServer logs a WARN and
     * starts with no restored expectations.
     * <p>
     * The default is 10 seconds. Set to 0 (or a negative value) to skip the startup restore
     * entirely.
     */
    public static Integer blobStoreRestoreTimeoutSeconds() {
        return readIntegerProperty(MOCKSERVER_BLOB_STORE_RESTORE_TIMEOUT_SECONDS, "MOCKSERVER_BLOB_STORE_RESTORE_TIMEOUT_SECONDS", 10);
    }

    /**
     * Sets the maximum number of seconds MockServer will wait, during startup, for the
     * persisted-expectations document to be read back from a cloud blob store.
     * <p>
     * The default is 10 seconds. Set to 0 (or a negative value) to skip the startup restore
     * entirely.
     *
     * @param blobStoreRestoreTimeoutSeconds startup blob-store restore deadline, in seconds
     */
    public static void blobStoreRestoreTimeoutSeconds(int blobStoreRestoreTimeoutSeconds) {
        setProperty(MOCKSERVER_BLOB_STORE_RESTORE_TIMEOUT_SECONDS, "" + blobStoreRestoreTimeoutSeconds);
    }

    // --- clustering (G10 phase 2c) ---

    /**
     * Returns whether clustering is enabled. Default is {@code false}.
     */
    public static boolean clusterEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_ENABLED, "MOCKSERVER_CLUSTER_ENABLED", "false"));
    }

    /**
     * Enables or disables clustering.
     *
     * @param clusterEnabled true to enable JGroups transport
     */
    public static void clusterEnabled(boolean clusterEnabled) {
        setProperty(MOCKSERVER_CLUSTER_ENABLED, String.valueOf(clusterEnabled));
    }

    /**
     * Returns the JGroups cluster name. Default is {@code "mockserver-cluster"}.
     */
    public static String clusterName() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_NAME, "MOCKSERVER_CLUSTER_NAME", "mockserver-cluster");
    }

    /**
     * Sets the JGroups cluster name.
     *
     * @param clusterName the cluster identifier
     */
    public static void clusterName(String clusterName) {
        setProperty(MOCKSERVER_CLUSTER_NAME, clusterName);
    }

    /**
     * Returns the optional path to a JGroups XML transport configuration.
     * Default is empty string (use the built-in embedded stack). Empty
     * string is used instead of {@code null} because the property cache
     * is a {@code ConcurrentHashMap} which does not permit null values.
     */
    public static String clusterTransportConfig() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_TRANSPORT_CONFIG, "MOCKSERVER_CLUSTER_TRANSPORT_CONFIG", "");
        return value != null && !value.isEmpty() ? value : null;
    }

    /**
     * Sets the path to a custom JGroups XML transport configuration.
     *
     * @param clusterTransportConfig path to JGroups XML, or null for default
     */
    public static void clusterTransportConfig(String clusterTransportConfig) {
        // Guard against null: System.setProperty (called by setProperty)
        // throws NPE for null values. Store empty string to mirror other
        // nullable string properties.
        setProperty(MOCKSERVER_CLUSTER_TRANSPORT_CONFIG, clusterTransportConfig != null ? clusterTransportConfig : "");
    }

    /**
     * Returns whether per-expectation {@code Times} limits are enforced
     * cluster-wide via a shared backend compare-and-set (CAS). Default is
     * {@code true} (a {@code Times.exactly(N)} expectation serves exactly N
     * times across the whole fleet).
     * <p>
     * Only relevant when a clustered backend is active. When set to
     * {@code false}, limited-{@code Times} matching falls back to the
     * node-local fast path: each node enforces {@code Times} independently
     * (no synchronous backend round-trip on the request worker thread).
     * This trades the fleet-wide exactly-N guarantee for lower, more
     * predictable matching latency in latency-sensitive clustered
     * deployments. See {@code RequestMatchers.consumeTimesViaBackendCas}.
     */
    public static boolean clusterSharedTimesEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_SHARED_TIMES_ENABLED, "MOCKSERVER_CLUSTER_SHARED_TIMES_ENABLED", "true"));
    }

    /**
     * Enables or disables cluster-wide shared-{@code Times} CAS enforcement.
     *
     * @param clusterSharedTimesEnabled {@code true} (default) to enforce
     *                                  {@code Times} limits fleet-wide via
     *                                  backend CAS; {@code false} to fall
     *                                  back to node-local {@code Times}
     */
    public static void clusterSharedTimesEnabled(boolean clusterSharedTimesEnabled) {
        setProperty(MOCKSERVER_CLUSTER_SHARED_TIMES_ENABLED, String.valueOf(clusterSharedTimesEnabled));
    }

    /**
     * Returns whether {@code verify}/{@code retrieve} aggregate across cluster
     * members (scatter-gather fan-in). Default is {@code false} (per-node
     * behaviour). Only meaningful in a clustered deployment behind a load
     * balancer. See {@code org.mockserver.cluster.ClusterFanIn}.
     */
    public static boolean clusterVerifyFanIn() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_VERIFY_FAN_IN, "MOCKSERVER_CLUSTER_VERIFY_FAN_IN", "false"));
    }

    /**
     * Enables or disables cluster verify/retrieve fan-in.
     *
     * @param clusterVerifyFanIn {@code true} to aggregate verify/retrieve
     *                           across cluster peers
     */
    public static void clusterVerifyFanIn(boolean clusterVerifyFanIn) {
        setProperty(MOCKSERVER_CLUSTER_VERIFY_FAN_IN, String.valueOf(clusterVerifyFanIn));
    }

    /**
     * Returns the comma-separated list of peer control-plane base URLs queried
     * during verify/retrieve fan-in. Default is empty (no peers).
     */
    public static String clusterVerifyFanInPeers() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_VERIFY_FAN_IN_PEERS, "MOCKSERVER_CLUSTER_VERIFY_FAN_IN_PEERS", "");
    }

    /**
     * Sets the comma-separated list of peer control-plane base URLs for
     * verify/retrieve fan-in.
     *
     * @param clusterVerifyFanInPeers comma-separated peer base URLs
     */
    public static void clusterVerifyFanInPeers(String clusterVerifyFanInPeers) {
        setProperty(MOCKSERVER_CLUSTER_VERIFY_FAN_IN_PEERS, clusterVerifyFanInPeers != null ? clusterVerifyFanInPeers : "");
    }

    /**
     * Returns the credential the verify/retrieve fan-in peer accessor presents on
     * cross-node queries, sent verbatim as the control-plane {@code Authorization}
     * header (e.g. {@code Bearer <jwt>}). Default is empty (no credential — current
     * behaviour). Set this on every node so an authenticated cluster can fan in.
     */
    public static String clusterFanInPeerAuthToken() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLUSTER_FAN_IN_PEER_AUTH_TOKEN, "MOCKSERVER_CLUSTER_FAN_IN_PEER_AUTH_TOKEN", "");
    }

    /**
     * Sets the credential the verify/retrieve fan-in peer accessor presents on
     * cross-node queries (sent verbatim as the control-plane {@code Authorization}
     * header, so include the scheme, e.g. {@code Bearer <jwt>}).
     *
     * @param clusterFanInPeerAuthToken the control-plane Authorization header value
     */
    public static void clusterFanInPeerAuthToken(String clusterFanInPeerAuthToken) {
        setProperty(MOCKSERVER_CLUSTER_FAN_IN_PEER_AUTH_TOKEN, clusterFanInPeerAuthToken != null ? clusterFanInPeerAuthToken : "");
    }

    // verification

    public static Integer maximumNumberOfRequestToReturnInVerificationFailure() {
        return readIntegerProperty(MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE, "MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE", 10);
    }

    /**
     * The maximum number of requests to return in verification failure result, if more expectations are found the failure result does not list them separately
     *
     * @param maximumNumberOfRequestToReturnInVerification maximum number of expectations to return in verification failure result
     */
    public static void maximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerification) {
        setProperty(MOCKSERVER_MAXIMUM_NUMBER_OF_REQUESTS_TO_RETURN_IN_VERIFICATION_FAILURE, "" + maximumNumberOfRequestToReturnInVerification);
    }

    public static boolean detailedVerificationFailures() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DETAILED_VERIFICATION_FAILURES, "MOCKSERVER_DETAILED_VERIFICATION_FAILURES", "" + true));
    }

    /**
     * If true (the default) verification failure messages include a detailed diff showing which fields did not match for the closest matching request.
     *
     * @param enable enabled detailed verification failure messages
     */
    public static void detailedVerificationFailures(boolean enable) {
        setProperty(MOCKSERVER_DETAILED_VERIFICATION_FAILURES, "" + enable);
    }

    public static boolean failVerificationOnEvictedLog() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FAIL_VERIFICATION_ON_EVICTED_LOG, "MOCKSERVER_FAIL_VERIFICATION_ON_EVICTED_LOG", "" + true));
    }

    /**
     * If true (the default) a verification that asserts an upper bound (never(), atMost(n), exactly(n), between(a,b)) fails rather than passes if the event log has evicted entries, because absence cannot be proven once evidence has been discarded. Set to false to restore the previous behaviour where such verifications could pass on an incomplete log.
     *
     * @param enable enabled failing upper-bound verifications when the event log has evicted entries
     */
    public static void failVerificationOnEvictedLog(boolean enable) {
        setProperty(MOCKSERVER_FAIL_VERIFICATION_ON_EVICTED_LOG, "" + enable);
    }

    public static boolean attachMismatchDiagnosticToResponse() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ATTACH_MISMATCH_DIAGNOSTIC_TO_RESPONSE, "MOCKSERVER_ATTACH_MISMATCH_DIAGNOSTIC_TO_RESPONSE", "" + false));
    }

    /**
     * If true, when no expectation matches an incoming request the 404 response will include a diagnostic header (x-mockserver-closest-match)
     * and a JSON body describing which expectation was closest to matching and which fields differed. Defaults to false.
     *
     * @param enable enable mismatch diagnostic in unmatched responses
     */
    public static void attachMismatchDiagnosticToResponse(boolean enable) {
        setProperty(MOCKSERVER_ATTACH_MISMATCH_DIAGNOSTIC_TO_RESPONSE, "" + enable);
    }

    public static boolean closestMatchHintEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CLOSEST_MATCH_HINT_ENABLED, "MOCKSERVER_CLOSEST_MATCH_HINT_ENABLED", "" + true));
    }

    /**
     * If true (the default), when no expectation matches an incoming request the data-plane 404 response carries a
     * single concise diagnostic header (x-mockserver-closest-match-hint) naming the closest expectation and the first
     * field that differed. The hint is header-only and length-bounded — no expectation body is leaked. Set to false to
     * suppress it (for example if a test asserts unmatched 404 responses byte-for-byte).
     *
     * @param enable enable the closest-match hint header on unmatched 404 responses
     */
    public static void closestMatchHintEnabled(boolean enable) {
        setProperty(MOCKSERVER_CLOSEST_MATCH_HINT_ENABLED, "" + enable);
    }

    // proxy

    public static boolean attemptToProxyIfNoMatchingExpectation() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION, "MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION", "" + true));
    }

    /**
     * If true (the default) when no matching expectation is found, and the host header of the request does not match MockServer's host, then MockServer attempts to proxy the request if that fails then a 404 is returned.
     * If false when no matching expectation is found, and MockServer is not being used as a proxy, then MockServer always returns a 404 immediately.
     *
     * @param enable enables automatically attempted proxying of request that don't match an expectation and look like they should be proxied
     */
    public static void attemptToProxyIfNoMatchingExpectation(boolean enable) {
        setProperty(MOCKSERVER_ATTEMPT_TO_PROXY_IF_NO_MATCHING_EXPECTATION, "" + enable);
    }

    public static InetSocketAddress forwardHttpProxy() {
        return readInetSocketAddressProperty(MOCKSERVER_FORWARD_HTTP_PROXY, "MOCKSERVER_FORWARD_HTTP_PROXY");
    }

    /**
     * Use HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     */
    public static void forwardHttpProxy(String hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort, MOCKSERVER_FORWARD_HTTP_PROXY);
    }

    /**
     * Use HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     */
    public static void forwardHttpProxy(InetSocketAddress hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort.toString(), MOCKSERVER_FORWARD_HTTP_PROXY);
    }

    public static InetSocketAddress forwardHttpsProxy() {
        return readInetSocketAddressProperty(MOCKSERVER_FORWARD_HTTPS_PROXY, "MOCKSERVER_FORWARD_HTTPS_PROXY");
    }

    /**
     * Use HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests, supports TLS tunnelling of HTTPS requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests
     */
    public static void forwardHttpsProxy(String hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort, MOCKSERVER_FORWARD_HTTPS_PROXY);
    }

    /**
     * Use HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests, supports TLS tunnelling of HTTPS requests
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests
     */
    public static void forwardHttpsProxy(InetSocketAddress hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort.toString(), MOCKSERVER_FORWARD_HTTPS_PROXY);
    }

    public static InetSocketAddress forwardSocksProxy() {
        return readInetSocketAddressProperty(MOCKSERVER_FORWARD_SOCKS_PROXY, "MOCKSERVER_FORWARD_SOCKS_PROXY");
    }

    /**
     * Use SOCKS proxy for all outbound / forwarded requests, support TLS tunnelling of TCP connections
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for SOCKS proxy for all outbound / forwarded requests
     */
    public static void forwardSocksProxy(String hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort, MOCKSERVER_FORWARD_SOCKS_PROXY);
    }

    /**
     * Use SOCKS proxy for all outbound / forwarded requests, support TLS tunnelling of TCP connections
     * <p>
     * The default is null
     *
     * @param hostAndPort host and port for SOCKS proxy for all outbound / forwarded requests
     */
    public static void forwardSocksProxy(InetSocketAddress hostAndPort) {
        validateHostAndPortAndSetProperty(hostAndPort.toString(), MOCKSERVER_FORWARD_SOCKS_PROXY);
    }

    public static String forwardProxyAuthenticationUsername() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME, "MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME", "");
    }

    /**
     * <p>Username for proxy authentication when using HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is null
     *
     * @param forwardProxyAuthenticationUsername username for proxy authentication
     */
    public static void forwardProxyAuthenticationUsername(String forwardProxyAuthenticationUsername) {
        if (forwardProxyAuthenticationUsername != null) {
            setProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME, forwardProxyAuthenticationUsername);
        } else {
            clearProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_USERNAME);
        }
    }

    public static String forwardProxyAuthenticationPassword() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD, "MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD", "");
    }

    /**
     * <p>Password for proxy authentication when using HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is null
     *
     * @param forwardProxyAuthenticationPassword password for proxy authentication
     */
    public static void forwardProxyAuthenticationPassword(String forwardProxyAuthenticationPassword) {
        if (forwardProxyAuthenticationPassword != null) {
            setProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD, forwardProxyAuthenticationPassword);
        } else {
            clearProperty(MOCKSERVER_FORWARD_PROXY_AUTHENTICATION_PASSWORD);
        }
    }

    public static String proxyAuthenticationRealm() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_SERVER_REALM, "MOCKSERVER_PROXY_SERVER_REALM", "MockServer HTTP Proxy");
    }

    /**
     * The authentication realm for proxy authentication to MockServer
     *
     * @param proxyAuthenticationRealm the authentication realm for proxy authentication
     */
    public static void proxyAuthenticationRealm(String proxyAuthenticationRealm) {
        setProperty(MOCKSERVER_PROXY_SERVER_REALM, proxyAuthenticationRealm);
    }

    public static String proxyAuthenticationUsername() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_AUTHENTICATION_USERNAME, "MOCKSERVER_PROXY_AUTHENTICATION_USERNAME", "");
    }

    /**
     * <p>The required username for proxy authentication to MockServer</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is ""
     *
     * @param proxyAuthenticationUsername required username for proxy authentication to MockServer
     */
    public static void proxyAuthenticationUsername(String proxyAuthenticationUsername) {
        setProperty(MOCKSERVER_PROXY_AUTHENTICATION_USERNAME, proxyAuthenticationUsername);
    }

    public static String proxyAuthenticationPassword() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD, "MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD", "");
    }

    // -----------------------------------------------------------------------------------------
    // Data-plane (mocked endpoint) authentication — opt-in, default off. Mirrors the proxy /
    // control-plane authentication naming so all auth properties read consistently.
    // -----------------------------------------------------------------------------------------

    public static boolean dataPlaneAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DATA_PLANE_AUTHENTICATION_REQUIRED, "MOCKSERVER_DATA_PLANE_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * <p>Enable authentication of data-plane (mocked endpoint) requests. When {@code true}, every
     * request to a mocked endpoint must present credentials matching one of the configured
     * data-plane schemes (Basic, Bearer and/or API-key); requests that do not are rejected with
     * {@code 401 Unauthorized}. Control-plane ({@code /mockserver/*}) requests, health/status/ready
     * probes and {@code CONNECT} proxy requests are NOT affected by this setting.</p>
     * <p>If enabled but no scheme is configured the server fails closed (rejects every data-plane
     * request) rather than allowing all traffic.</p>
     * <p>The default is {@code false} (no data-plane authentication).</p>
     *
     * @param enable whether data-plane authentication is required
     */
    public static void dataPlaneAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_DATA_PLANE_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String dataPlaneBasicAuthenticationUsername() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_USERNAME, "MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_USERNAME", "");
    }

    /**
     * The username required for data-plane HTTP Basic authentication. Basic is only active when both
     * username and password are set. The default is "".
     *
     * @param dataPlaneBasicAuthenticationUsername the Basic username
     */
    public static void dataPlaneBasicAuthenticationUsername(String dataPlaneBasicAuthenticationUsername) {
        setProperty(MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_USERNAME, dataPlaneBasicAuthenticationUsername);
    }

    public static String dataPlaneBasicAuthenticationPassword() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_PASSWORD, "MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_PASSWORD", "");
    }

    /**
     * The password required for data-plane HTTP Basic authentication. Basic is only active when both
     * username and password are set. The default is "".
     *
     * @param dataPlaneBasicAuthenticationPassword the Basic password
     */
    public static void dataPlaneBasicAuthenticationPassword(String dataPlaneBasicAuthenticationPassword) {
        setProperty(MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_PASSWORD, dataPlaneBasicAuthenticationPassword);
    }

    public static String dataPlaneBasicAuthenticationRealm() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_REALM, "MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_REALM", "MockServer");
    }

    /**
     * The realm advertised in the {@code WWW-Authenticate: Basic realm="..."} challenge on a 401.
     * The default is "MockServer".
     *
     * @param dataPlaneBasicAuthenticationRealm the Basic realm
     */
    public static void dataPlaneBasicAuthenticationRealm(String dataPlaneBasicAuthenticationRealm) {
        setProperty(MOCKSERVER_DATA_PLANE_BASIC_AUTHENTICATION_REALM, dataPlaneBasicAuthenticationRealm);
    }

    public static String dataPlaneBearerAuthenticationToken() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DATA_PLANE_BEARER_AUTHENTICATION_TOKEN, "MOCKSERVER_DATA_PLANE_BEARER_AUTHENTICATION_TOKEN", "");
    }

    /**
     * The token required for data-plane Bearer authentication ({@code Authorization: Bearer <token>}).
     * Bearer is active when this value is set. The default is "".
     *
     * @param dataPlaneBearerAuthenticationToken the expected Bearer token
     */
    public static void dataPlaneBearerAuthenticationToken(String dataPlaneBearerAuthenticationToken) {
        setProperty(MOCKSERVER_DATA_PLANE_BEARER_AUTHENTICATION_TOKEN, dataPlaneBearerAuthenticationToken);
    }

    public static String dataPlaneApiKeyAuthenticationHeader() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_HEADER, "MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_HEADER", "");
    }

    /**
     * The name of the header carrying the data-plane API key (e.g. {@code X-API-Key}). API-key auth
     * is only active when both the header name and the value are set. The default is "".
     *
     * @param dataPlaneApiKeyAuthenticationHeader the API-key header name
     */
    public static void dataPlaneApiKeyAuthenticationHeader(String dataPlaneApiKeyAuthenticationHeader) {
        setProperty(MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_HEADER, dataPlaneApiKeyAuthenticationHeader);
    }

    public static String dataPlaneApiKeyAuthenticationValue() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_VALUE, "MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_VALUE", "");
    }

    /**
     * The expected value of the data-plane API-key header. API-key auth is only active when both the
     * header name and the value are set. The default is "".
     *
     * @param dataPlaneApiKeyAuthenticationValue the expected API-key value
     */
    public static void dataPlaneApiKeyAuthenticationValue(String dataPlaneApiKeyAuthenticationValue) {
        setProperty(MOCKSERVER_DATA_PLANE_API_KEY_AUTHENTICATION_VALUE, dataPlaneApiKeyAuthenticationValue);
    }

    /**
     * <p>The list of hostnames to not use the configured proxy. Several values may be present, seperated by comma (,)</p>
     * The default is ""
     *
     * @param noProxyHosts Comma-seperated list of hosts to not be proxied.
     */
    public static void noProxyHosts(String noProxyHosts) {
        setProperty(MOCKSERVER_NO_PROXY_HOSTS, noProxyHosts);
    }

    public static String noProxyHosts() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_NO_PROXY_HOSTS, "MOCKSERVER_NO_PROXY_HOSTS", "");
    }

    public static String proxyRemoteHost() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_REMOTE_HOST, "MOCKSERVER_PROXY_REMOTE_HOST", "");
    }

    public static void proxyRemoteHost(String proxyRemoteHost) {
        setProperty(MOCKSERVER_PROXY_REMOTE_HOST, proxyRemoteHost);
    }

    public static Integer proxyRemotePort() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_REMOTE_PORT, "MOCKSERVER_PROXY_REMOTE_PORT", "");
        if (isBlank(value)) {
            return null;
        }
        int port;
        try {
            port = Integer.parseInt(value);
        } catch (NumberFormatException nfe) {
            throw new IllegalArgumentException("proxyRemotePort must be an integer between 1 and 65535, got: " + value);
        }
        if (port < 1 || port > 65535) {
            throw new IllegalArgumentException("proxyRemotePort must be between 1 and 65535, got: " + port);
        }
        return port;
    }

    public static void proxyRemotePort(Integer proxyRemotePort) {
        setProperty(MOCKSERVER_PROXY_REMOTE_PORT, proxyRemotePort != null ? "" + proxyRemotePort : "");
    }

    public static boolean forwardAdjustHostHeader() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_ADJUST_HOST_HEADER, "MOCKSERVER_FORWARD_ADJUST_HOST_HEADER", "" + true));
    }

    /**
     * If true (the default) the Host header will be automatically adjusted to match the target server when forwarding requests.
     * This prevents HTTP 421 Misdirected Request errors when the target server validates Host headers.
     * If false the original Host header is preserved.
     *
     * @param enable enables automatic Host header adjustment for forwarded requests
     */
    public static void forwardAdjustHostHeader(boolean enable) {
        setProperty(MOCKSERVER_FORWARD_ADJUST_HOST_HEADER, "" + enable);
    }

    public static String forwardDefaultHostHeader() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER, "MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER", "");
    }

    public static void forwardDefaultHostHeader(String hostHeader) {
        setProperty(MOCKSERVER_FORWARD_DEFAULT_HOST_HEADER, hostHeader);
    }

    @SuppressWarnings("unchecked")
    public static List<ProxyPassMapping> proxyPass() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_PASS, "MOCKSERVER_PROXY_PASS", "");
        if (isBlank(value)) {
            return Collections.emptyList();
        }
        try {
            return ObjectMapperFactory.createObjectMapper().readValue(value, new TypeReference<List<ProxyPassMapping>>() {});
        } catch (Exception e) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("invalid proxyPass value: " + value)
                        .setThrowable(e)
                );
            }
            return Collections.emptyList();
        }
    }

    /**
     * Configure ProxyPass mappings that map incoming path prefixes to upstream servers with automatic path rewriting.
     * Value is a JSON array of objects with pathPrefix, targetUri, and optional preserveHost fields.
     *
     * @param proxyPassJson JSON array string, e.g. [{"pathPrefix":"/api/","targetUri":"https://backend:8443/services/"}]
     */
    public static void proxyPass(String proxyPassJson) {
        setProperty(MOCKSERVER_PROXY_PASS, proxyPassJson);
    }

    /**
     * Configure ProxyPass mappings that map incoming path prefixes to upstream servers with automatic path rewriting.
     *
     * @param mappings list of ProxyPassMapping objects
     */
    public static void proxyPass(List<ProxyPassMapping> mappings) {
        try {
            setProperty(MOCKSERVER_PROXY_PASS, ObjectMapperFactory.createObjectMapper().writeValueAsString(mappings));
        } catch (Exception e) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("failed to serialize proxyPass mappings")
                        .setThrowable(e)
                );
            }
        }
    }

    public static Long globalResponseDelayMillis() {
        String value = readPropertyHierarchically(PROPERTIES, MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS, "MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS", "");
        if (isBlank(value)) {
            return null;
        }
        try {
            long millis = Long.parseLong(value);
            if (millis < 0) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("invalid value {} for globalResponseDelayMillis, must be >= 0")
                        .setArguments(value)
                );
                return null;
            }
            return millis;
        } catch (NumberFormatException nfe) {
            return null;
        }
    }

    public static void globalResponseDelayMillis(Long millis) {
        if (millis != null) {
            if (millis < 0) {
                throw new IllegalArgumentException("globalResponseDelayMillis must be >= 0, got: " + millis);
            }
            setProperty(MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS, "" + millis);
        } else {
            clearProperty(MOCKSERVER_GLOBAL_RESPONSE_DELAY_MILLIS);
        }
    }

    /**
     * <p>The required password for proxy authentication to MockServer</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is ""
     *
     * @param proxyAuthenticationPassword required password for proxy authentication to MockServer
     */
    public static void proxyAuthenticationPassword(String proxyAuthenticationPassword) {
        setProperty(MOCKSERVER_PROXY_AUTHENTICATION_PASSWORD, proxyAuthenticationPassword);
    }

    // liveness

    public static String livenessHttpGetPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_LIVENESS_HTTP_GET_PATH, "MOCKSERVER_LIVENESS_HTTP_GET_PATH", "");
    }

    /**
     * Path to support HTTP GET requests for status response (also available on PUT /mockserver/status).
     * <p>
     * If this value is not modified then only PUT /mockserver/status but is a none blank value is provided for this value then GET requests to this path will return the 200 Ok status response showing the MockServer version and bound ports.
     * <p>
     * A GET request to this path will be matched before any expectation matching or proxying of requests.
     * <p>
     * The default is ""
     *
     * @param livenessPath path to support HTTP GET requests for status response
     */
    public static void livenessHttpGetPath(String livenessPath) {
        setProperty(MOCKSERVER_LIVENESS_HTTP_GET_PATH, livenessPath);
    }

    // expectation namespacing / multi-tenancy

    public static String matchNamespaceHeader() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_MATCH_NAMESPACE_HEADER, "MOCKSERVER_MATCH_NAMESPACE_HEADER", DEFAULT_MATCH_NAMESPACE_HEADER);
    }

    /**
     * The name of the request header used to scope expectation matching to a namespace (tenant),
     * enabling multiple teams or test-suites to share a single MockServer instance without their
     * expectations colliding.
     * <p>
     * When a request carries this header with value {@code T}, matching considers expectations whose
     * {@code namespace} equals {@code T} <em>plus</em> all global (no-namespace) expectations — and
     * never expectations belonging to other namespaces. A request with no namespace header matches
     * only global (no-namespace) expectations.
     * <p>
     * The default is {@code X-MockServer-Namespace}.
     *
     * @param matchNamespaceHeader the request header name carrying the namespace
     */
    public static void matchNamespaceHeader(String matchNamespaceHeader) {
        setProperty(MOCKSERVER_MATCH_NAMESPACE_HEADER, matchNamespaceHeader);
    }

    // control plane authentication

    public static boolean controlPlaneTLSMutualAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * Require mTLS (also called client authentication and two-way TLS) for all control plane requests
     *
     * @param enable TLS mutual authentication for all control plane requests
     */
    public static void controlPlaneTLSMutualAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String controlPlaneTLSMutualAuthenticationCAChain() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN", "");
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for control plane mTLS authentication
     * <p>
     * The X.509 Certificate Chain is for trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used for to performs mTLS (client authentication) for inbound TLS connections if controlPlaneTLSMutualAuthenticationRequired is enabled
     *
     * @param trustCertificateChain File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates
     */
    public static void controlPlaneTLSMutualAuthenticationCAChain(String trustCertificateChain) {
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "" + trustCertificateChain);
    }

    public static String controlPlanePrivateKeyPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH, "MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom private key for control plane connections using mTLS for authentication.
     * <p>
     * The private key must be a PKCS#8 or PKCS#1 PEM file and must be the private key corresponding to the controlPlaneX509CertificatePath X509 (public key) configuration.
     * The controlPlaneTLSMutualAuthenticationCAChain configuration must be the Certificate Authority for the corresponding X509 certificate (i.e. able to valid its signature).
     * <p>
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * This configuration will be ignored unless x509CertificatePath is also set.
     *
     * @param privateKeyPath location of the PKCS#8 PEM file containing the private key
     */
    public static void controlPlanePrivateKeyPath(String privateKeyPath) {
        fileExists(privateKeyPath);
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_PRIVATE_KEY_PATH, privateKeyPath);
    }


    public static String controlPlaneX509CertificatePath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH, "MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom X.509 Certificate for control plane connections using mTLS for authentication.
     * <p>
     * The certificate must be a X509 PEM file and must be the public key corresponding to the controlPlanePrivateKeyPath private key configuration.
     * The controlPlaneTLSMutualAuthenticationCAChain configuration must be the Certificate Authority for this certificate (i.e. able to valid its signature).
     * <p>
     * This configuration will be ignored unless privateKeyPath is also set.
     *
     * @param x509CertificatePath location of the PEM file containing the X509 certificate
     */
    public static void controlPlaneX509CertificatePath(String x509CertificatePath) {
        fileExists(x509CertificatePath);
        setProperty(MOCKSERVER_CONTROL_PLANE_TLS_X509_CERTIFICATE_PATH, x509CertificatePath);
    }

    public static boolean controlPlaneJWTAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * <p>
     * Require JWT authentication for all control plane requests
     * </p>
     *
     * @param enable TLS mutual authentication for all control plane requests
     */
    public static void controlPlaneJWTAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String controlPlaneJWTAuthenticationJWKSource() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE", "");
    }

    /**
     * <p>
     * JWK source used when JWT authentication is enabled for control plane requests
     * </p>
     * <p>
     * JWK source can be a file system path, classpath location or a URL
     * </p>
     * <p>
     * See: https://openid.net/specs/draft-jones-json-web-key-03.html
     * </p>
     *
     * @param controlPlaneJWTAuthenticationJWKSource file system path, classpath location or a URL of JWK source
     */
    public static void controlPlaneJWTAuthenticationJWKSource(String controlPlaneJWTAuthenticationJWKSource) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_JWK_SOURCE, "" + controlPlaneJWTAuthenticationJWKSource);
    }

    public static String controlPlaneJWTAuthenticationExpectedAudience() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE", "");
    }

    /**
     * <p>
     * Audience claim (i.e. aud) required when JWT authentication is enabled for control plane requests
     * </p>
     *
     * @param controlPlaneJWTAuthenticationExpectedAudience required value for audience claim (i.e. aud)
     */
    public static void controlPlaneJWTAuthenticationExpectedAudience(String controlPlaneJWTAuthenticationExpectedAudience) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_EXPECTED_AUDIENCE, "" + controlPlaneJWTAuthenticationExpectedAudience);
    }

    @SuppressWarnings("UnstableApiUsage")
    public static Map<String, String> controlPlaneJWTAuthenticationMatchingClaims() {
        String jwtAuthenticationMatchingClaims = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS", "");
        if (isNotBlank(jwtAuthenticationMatchingClaims)) {
            return Splitter.on(",").withKeyValueSeparator("=").split(jwtAuthenticationMatchingClaims);
        } else {
            return ImmutableMap.of();
        }
    }

    /**
     * <p>
     * Matching claims expected when JWT authentication is enabled for control plane requests
     * </p>
     * <p>
     * Value should be string with comma separated key=value items, for example: scope=internal public,sub=some_subject
     * </p>
     *
     * @param controlPlaneJWTAuthenticationMatchingClaims required values for claims
     */
    public static void controlPlaneJWTAuthenticationMatchingClaims(Map<String, String> controlPlaneJWTAuthenticationMatchingClaims) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_MATCHING_CLAIMS, Joiner.on(",").withKeyValueSeparator("=").join(controlPlaneJWTAuthenticationMatchingClaims));
    }

    public static Set<String> controlPlaneJWTAuthenticationRequiredClaims() {
        String jwtAuthenticationRequiredClaims = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS, "MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS", "");
        if (isNotBlank(jwtAuthenticationRequiredClaims)) {
            return Sets.newConcurrentHashSet(Arrays.asList(jwtAuthenticationRequiredClaims.split(",")));
        } else {
            return ImmutableSet.of();
        }
    }

    /**
     * <p>
     * Required claims that should exist (i.e. with any value) when JWT authentication is enabled for control plane requests
     * </p>
     * <p>
     * Value should be string with comma separated values, for example: scope,sub
     * </p>
     *
     * @param controlPlaneJWTAuthenticationRequiredClaims required claims
     */
    public static void controlPlaneJWTAuthenticationRequiredClaims(Set<String> controlPlaneJWTAuthenticationRequiredClaims) {
        setProperty(MOCKSERVER_CONTROL_PLANE_JWT_AUTHENTICATION_REQUIRED_CLAIMS, Joiner.on(",").join(controlPlaneJWTAuthenticationRequiredClaims));
    }

    public static boolean controlPlaneOidcAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_OIDC_AUTHENTICATION_REQUIRED, "MOCKSERVER_CONTROL_PLANE_OIDC_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * <p>
     * Require verified OIDC bearer-token authentication for all control plane requests, validating tokens issued by an external OIDC IdP
     * </p>
     *
     * @param enable verified OIDC authentication for all control plane requests
     */
    public static void controlPlaneOidcAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_CONTROL_PLANE_OIDC_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String controlPlaneOidcIssuer() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_OIDC_ISSUER, "MOCKSERVER_CONTROL_PLANE_OIDC_ISSUER", "");
    }

    /**
     * <p>
     * OIDC issuer (i.e. iss) required on control plane tokens; also used to discover the JWKS URI via {issuer}/.well-known/openid-configuration when controlPlaneOidcJwksUri is not set
     * </p>
     *
     * @param controlPlaneOidcIssuer required value for issuer claim (i.e. iss)
     */
    public static void controlPlaneOidcIssuer(String controlPlaneOidcIssuer) {
        setProperty(MOCKSERVER_CONTROL_PLANE_OIDC_ISSUER, "" + controlPlaneOidcIssuer);
    }

    public static String controlPlaneOidcJwksUri() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_OIDC_JWKS_URI, "MOCKSERVER_CONTROL_PLANE_OIDC_JWKS_URI", "");
    }

    /**
     * <p>
     * JWKS URI used to verify control plane OIDC token signatures; if not set it is discovered from the issuer's OIDC discovery document
     * </p>
     *
     * @param controlPlaneOidcJwksUri URL (or file/classpath path) of the JWK source
     */
    public static void controlPlaneOidcJwksUri(String controlPlaneOidcJwksUri) {
        setProperty(MOCKSERVER_CONTROL_PLANE_OIDC_JWKS_URI, "" + controlPlaneOidcJwksUri);
    }

    public static String controlPlaneOidcAudience() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_OIDC_AUDIENCE, "MOCKSERVER_CONTROL_PLANE_OIDC_AUDIENCE", "");
    }

    /**
     * <p>
     * Audience claim (i.e. aud) required on control plane OIDC tokens
     * </p>
     *
     * @param controlPlaneOidcAudience required value for audience claim (i.e. aud)
     */
    public static void controlPlaneOidcAudience(String controlPlaneOidcAudience) {
        setProperty(MOCKSERVER_CONTROL_PLANE_OIDC_AUDIENCE, "" + controlPlaneOidcAudience);
    }

    public static Set<String> controlPlaneOidcRequiredScopes() {
        String requiredScopes = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_OIDC_REQUIRED_SCOPES, "MOCKSERVER_CONTROL_PLANE_OIDC_REQUIRED_SCOPES", "");
        if (isNotBlank(requiredScopes)) {
            return Sets.newConcurrentHashSet(Arrays.asList(requiredScopes.split(",")));
        } else {
            return ImmutableSet.of();
        }
    }

    /**
     * <p>
     * Scopes that must all be present in a control plane OIDC token before it is accepted
     * </p>
     * <p>
     * Value should be a string with comma separated values, for example: mockserver.read,mockserver.write
     * </p>
     *
     * @param controlPlaneOidcRequiredScopes required scopes
     */
    public static void controlPlaneOidcRequiredScopes(Set<String> controlPlaneOidcRequiredScopes) {
        setProperty(MOCKSERVER_CONTROL_PLANE_OIDC_REQUIRED_SCOPES, Joiner.on(",").join(controlPlaneOidcRequiredScopes));
    }

    public static String controlPlaneOidcScopeClaim() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_OIDC_SCOPE_CLAIM, "MOCKSERVER_CONTROL_PLANE_OIDC_SCOPE_CLAIM", "scope");
    }

    /**
     * <p>
     * Name of the claim holding granted scopes on a control plane OIDC token, default "scope" (space-delimited); array claims such as scp, roles or groups are also supported
     * </p>
     *
     * @param controlPlaneOidcScopeClaim name of the scope claim
     */
    public static void controlPlaneOidcScopeClaim(String controlPlaneOidcScopeClaim) {
        setProperty(MOCKSERVER_CONTROL_PLANE_OIDC_SCOPE_CLAIM, "" + controlPlaneOidcScopeClaim);
    }

    public static boolean controlPlaneAuthorizationEnabled() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_AUTHORIZATION_ENABLED, "MOCKSERVER_CONTROL_PLANE_AUTHORIZATION_ENABLED", "false"));
    }

    /**
     * <p>
     * Enable coarse role-based authorization of control plane requests, mapping a verified principal's scopes/groups to read/mutate/admin roles via controlPlaneScopeMapping
     * </p>
     *
     * @param enable enforce control plane authorization
     */
    public static void controlPlaneAuthorizationEnabled(boolean enable) {
        setProperty(MOCKSERVER_CONTROL_PLANE_AUTHORIZATION_ENABLED, "" + enable);
    }

    public static Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> controlPlaneScopeMapping() {
        String mapping = readPropertyHierarchically(PROPERTIES, MOCKSERVER_CONTROL_PLANE_SCOPE_MAPPING, "MOCKSERVER_CONTROL_PLANE_SCOPE_MAPPING", "");
        return parseScopeMapping(mapping);
    }

    /**
     * Parses a comma-separated list of {@code value=role} pairs into a scope-to-role map.
     * Unrecognised roles (anything other than read/mutate/admin, case-insensitive) and
     * malformed pairs (missing {@code =} or a blank key/value) are skipped so a typo can
     * never silently widen access. Returns an empty (never null) map for a blank value.
     */
    static Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> parseScopeMapping(String mapping) {
        Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> result = new LinkedHashMap<>();
        if (isNotBlank(mapping)) {
            for (String pair : mapping.split(",")) {
                int eq = pair.indexOf('=');
                if (eq <= 0 || eq >= pair.length() - 1) {
                    continue;
                }
                String value = pair.substring(0, eq).trim();
                org.mockserver.authentication.authorization.ControlPlaneRole role =
                    org.mockserver.authentication.authorization.ControlPlaneRole.parse(pair.substring(eq + 1));
                if (isNotBlank(value) && role != null) {
                    result.put(value, role);
                }
            }
        }
        return result;
    }

    /**
     * <p>
     * Mapping from a verified scope/group value to a coarse control plane role (read, mutate or admin)
     * </p>
     * <p>
     * Value should be a comma separated list of value=role pairs, for example: platform-admins=admin,qa-team=mutate,viewers=read
     * </p>
     *
     * @param controlPlaneScopeMapping mapping from scope/group value to role
     */
    public static void controlPlaneScopeMapping(Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> controlPlaneScopeMapping) {
        StringBuilder serialized = new StringBuilder();
        if (controlPlaneScopeMapping != null) {
            for (Map.Entry<String, org.mockserver.authentication.authorization.ControlPlaneRole> entry : controlPlaneScopeMapping.entrySet()) {
                if (entry.getValue() == null) {
                    continue;
                }
                if (serialized.length() > 0) {
                    serialized.append(",");
                }
                serialized.append(entry.getKey()).append("=").append(entry.getValue().name().toLowerCase());
            }
        }
        setProperty(MOCKSERVER_CONTROL_PLANE_SCOPE_MAPPING, serialized.toString());
    }

    // TLS

    /**
     * <p>Proactively initialise TLS during start to ensure that if dynamicallyCreateCertificateAuthorityCertificate is enabled the Certificate Authority X.509 Certificate and Private Key will be created during start up and not when the first TLS connection is received.</p>
     * <p>This setting will also ensure any configured private key and X.509 will be loaded during start up and not when the first TLS connection is received to give immediate feedback on any related TLS configuration errors.</p>
     *
     * @param enable proactively initialise TLS at startup
     */
    public static void proactivelyInitialiseTLS(boolean enable) {
        setProperty(MOCKSERVER_PROACTIVELY_INITIALISE_TLS, "" + enable);
    }

    public static boolean proactivelyInitialiseTLS() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROACTIVELY_INITIALISE_TLS, "MOCKSERVER_PROACTIVELY_INITIALISE_TLS", "false"));
    }

    public static String tlsProtocols() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_PROTOCOLS, "MOCKSERVER_TLS_PROTOCOLS", "TLSv1,TLSv1.1,TLSv1.2");
    }

    /**
     * Comma seperated list of TLS protocols, by default TLSv1,TLSv1.1,TLSv1.2
     *
     * @param tlsProtocols comma seperated list of TLS protocols
     */
    public static  void tlsProtocols(String tlsProtocols) {
        setProperty(MOCKSERVER_TLS_PROTOCOLS, tlsProtocols);
    }

    public static boolean tlsAllowInsecureProtocols() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS, "MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS", "" + true));
    }

    /**
     * Whether to allow TLSv1 and TLSv1.1 in the effective TLS protocols list.
     * <p>
     * Both protocols are deprecated by RFC 8996 and vulnerable to BEAST and POODLE.
     * The default is true for backwards compatibility — MockServer's
     * {@link #tlsProtocols} default still includes them. Set this to false to opt
     * into a hardened profile: any "TLSv1" or "TLSv1.1" entries in
     * {@link #tlsProtocols} are filtered out before the SSL context is built.
     * <p>
     * A future major release is expected to flip this default to false.
     *
     * @param allow if true, TLSv1 and TLSv1.1 are honoured in {@link #tlsProtocols}; if false, they are stripped
     */
    public static void tlsAllowInsecureProtocols(boolean allow) {
        setProperty(MOCKSERVER_TLS_ALLOW_INSECURE_PROTOCOLS, "" + allow);
    }

    public static boolean dynamicallyCreateCertificateAuthorityCertificate() {
        // proxySetup is the convenience on-switch for a unique private CA: when enabled it forces
        // dynamic CA generation regardless of the dynamicallyCreateCertificateAuthorityCertificate value.
        return proxySetup() || Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE, "MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE", "false"));
    }

    public static boolean proxySetup() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_SETUP, "MOCKSERVER_PROXY_SETUP", "false"));
    }

    /**
     * Convenience on-switch for using MockServer as a TLS-intercepting proxy. When enabled MockServer
     * forces dynamic creation of a unique private Certificate Authority (so the public CA private key
     * published in the MockServer repository is not used) and prints an OS-specific copy-paste proxy
     * setup block on startup.
     *
     * @param enable enable proxy setup convenience mode
     */
    public static void proxySetup(boolean enable) {
        setProperty(MOCKSERVER_PROXY_SETUP, "" + enable);
    }

    public static boolean proxySetupLogging() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PROXY_SETUP_LOGGING, "MOCKSERVER_PROXY_SETUP_LOGGING", "false"));
    }

    /**
     * Whether to materialise the active CA to {@code mockserver-ca.pem} and print the OS-specific
     * copy-paste proxy setup block (CA path and proxy / CA environment-variable exports) on startup.
     * <p>
     * Default is false so embedded {@code ClientAndServer} usage (the large unit-test user base) stays
     * silent and never drops a {@code mockserver-ca.pem} file into the working directory. The standalone
     * CLI launcher ({@code org.mockserver.cli.Main}) enables it so jar/Docker/CLI launches always print
     * the block.
     *
     * @param enable enable the proxy setup startup logging block
     */
    public static void proxySetupLogging(boolean enable) {
        setProperty(MOCKSERVER_PROXY_SETUP_LOGGING, "" + enable);
    }

    /**
     * Whether {@code proxySetupLogging} has an explicit value from any user source — a system property
     * (incl. a programmatic set), the {@code mockserver.properties} file, or the
     * {@code MOCKSERVER_PROXY_SETUP_LOGGING} environment variable — as opposed to falling back to the
     * built-in default. The standalone CLI launcher uses this to auto-enable the proxy setup block only
     * when the user has not configured it themselves (in any source), so a properties-file opt-out is
     * honoured just like a {@code -D} / env-var one.
     *
     * @return true if an explicit value exists in any user source
     */
    public static boolean proxySetupLoggingConfigured() {
        return System.getProperty(MOCKSERVER_PROXY_SETUP_LOGGING) != null
            || (PROPERTIES != null && PROPERTIES.getProperty(MOCKSERVER_PROXY_SETUP_LOGGING) != null)
            || isNotBlank(System.getenv("MOCKSERVER_PROXY_SETUP_LOGGING"));
    }

    /**
     * Enable dynamic creation of Certificate Authority X509 certificate and private key.
     * <p>
     * Enable this property to increase the security of trusting the MockServer Certificate Authority X509 by ensuring a local dynamic value is used instead of the public value in the MockServer git repo.
     * <p>
     * These PEM files will be created and saved in the directory specified with configuration property directoryToSaveDynamicSSLCertificate.
     *
     * @param enable dynamic creation of Certificate Authority X509 certificate and private key.
     */
    public static void dynamicallyCreateCertificateAuthorityCertificate(boolean enable) {
        setProperty(MOCKSERVER_DYNAMICALLY_CREATE_CERTIFICATE_AUTHORITY_CERTIFICATE, "" + enable);
    }

    public static String directoryToSaveDynamicSSLCertificate() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE, "MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE", ".");
    }

    /**
     * Directory used to save the dynamically generated Certificate Authority X.509 Certificate and Private Key.
     *
     * @param directoryToSaveDynamicSSLCertificate directory to save Certificate Authority X.509 Certificate and Private Key
     */
    public static void directoryToSaveDynamicSSLCertificate(String directoryToSaveDynamicSSLCertificate) {
        fileExists(directoryToSaveDynamicSSLCertificate);
        setProperty(MOCKSERVER_CERTIFICATE_DIRECTORY_TO_SAVE_DYNAMIC_SSL_CERTIFICATE, directoryToSaveDynamicSSLCertificate);
    }

    /**
     * Prevent certificates from dynamically updating when domain list changes
     *
     * @param prevent prevent certificates from dynamically updating when domain list changes
     */
    public static void preventCertificateDynamicUpdate(boolean prevent) {
        setProperty(MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE, "" + prevent);
    }

    public static boolean preventCertificateDynamicUpdate() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE, "MOCKSERVER_PREVENT_CERTIFICATE_DYNAMIC_UPDATE", "false"));
    }

    public static String sslCertificateDomainName() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME, "MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME", KeyAndCertificateFactory.CERTIFICATE_DOMAIN);
    }

    /**
     * The domain name for auto-generate TLS certificates
     * <p>
     * The default is "localhost"
     *
     * @param domainName domain name for auto-generate TLS certificates
     */
    public static void sslCertificateDomainName(String domainName) {
        setProperty(MOCKSERVER_SSL_CERTIFICATE_DOMAIN_NAME, domainName);
    }

    /**
     * The Subject Alternative Name (SAN) domain names for auto-generate TLS certificates as a comma separated list
     * <p>
     * The default is "localhost"
     *
     * @param sslSubjectAlternativeNameDomains Subject Alternative Name (SAN) domain names for auto-generate TLS certificates
     */
    public static void sslSubjectAlternativeNameDomains(Set<String> sslSubjectAlternativeNameDomains) {
        setProperty(MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS, Joiner.on(",").join(sslSubjectAlternativeNameDomains));
    }

    public static Set<String> sslSubjectAlternativeNameDomains() {
        return Sets.newConcurrentHashSet(Arrays.asList(readPropertyHierarchically(PROPERTIES, MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS, "MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_DOMAINS", "localhost").split(",")));
    }

    /**
     * <p>The Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates as a comma separated list</p>
     *
     * <p>The default is "127.0.0.1,0.0.0.0"</p>
     *
     * @param sslSubjectAlternativeNameIps Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates
     */
    public static void sslSubjectAlternativeNameIps(Set<String> sslSubjectAlternativeNameIps) {
        setProperty(MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS, Joiner.on(",").join(sslSubjectAlternativeNameIps));
    }

    public static Set<String> sslSubjectAlternativeNameIps() {
        return Sets.newConcurrentHashSet(Arrays.asList(readPropertyHierarchically(PROPERTIES, MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS, "MOCKSERVER_SSL_SUBJECT_ALTERNATIVE_NAME_IPS", "127.0.0.1,0.0.0.0").split(",")));
    }

    public static String certificateAuthorityPrivateKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY, "MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY", DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY);
    }

    /**
     * File system path or classpath location of custom Private Key for Certificate Authority for TLS, the private key must be a PKCS#8 or PKCS#1 PEM file and must match the certificateAuthorityCertificate
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     *
     * @param certificateAuthorityPrivateKey location of the PEM file containing the certificate authority private key
     */
    public static void certificateAuthorityPrivateKey(String certificateAuthorityPrivateKey) {
        // Static setter validates eagerly because the value is expected to come from
        // user-supplied configuration (system property, env var, or programmatic call).
        // The instance setter on Configuration intentionally does NOT validate, since
        // BCKeyAndCertificateFactory uses it to store the destination path before the
        // dynamic CA file is written.
        fileExists(certificateAuthorityPrivateKey);
        setProperty(MOCKSERVER_CERTIFICATE_AUTHORITY_PRIVATE_KEY, certificateAuthorityPrivateKey);
    }

    public static String certificateAuthorityCertificate() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE, "MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE", DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE);
    }

    /**
     * File system path or classpath location of custom X.509 Certificate for Certificate Authority for TLS, the certificate must be a X509 PEM file and must match the certificateAuthorityPrivateKey
     *
     * @param certificateAuthorityCertificate location of the PEM file containing the certificate authority X509 certificate
     */
    public static void certificateAuthorityCertificate(String certificateAuthorityCertificate) {
        // See the comment on certificateAuthorityPrivateKey above for the rationale on
        // why the static setter validates and the matching instance setter does not.
        fileExists(certificateAuthorityCertificate);
        setProperty(MOCKSERVER_CERTIFICATE_AUTHORITY_X509_CERTIFICATE, certificateAuthorityCertificate);
    }

    public static String privateKeyPath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_PRIVATE_KEY_PATH, "MOCKSERVER_TLS_PRIVATE_KEY_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom private key for TLS connections into MockServer.
     * <p>
     * The private key must be a PKCS#8 or PKCS#1 PEM file and must be the private key corresponding to the x509CertificatePath X509 (public key) configuration.
     * The certificateAuthorityCertificate configuration must be the Certificate Authority for the corresponding X509 certificate (i.e. able to valid its signature), see: x509CertificatePath.
     * <p>
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * This configuration will be ignored unless x509CertificatePath is also set.
     *
     * @param privateKeyPath location of the PKCS#8 PEM file containing the private key
     */
    public static void privateKeyPath(String privateKeyPath) {
        // See the comment on certificateAuthorityPrivateKey above — instance setter is
        // intentionally lenient because dynamic SSL generation pre-stores the destination.
        fileExists(privateKeyPath);
        setProperty(MOCKSERVER_TLS_PRIVATE_KEY_PATH, privateKeyPath);
    }


    public static String x509CertificatePath() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_X509_CERTIFICATE_PATH, "MOCKSERVER_TLS_X509_CERTIFICATE_PATH", "");
    }

    /**
     * File system path or classpath location of a fixed custom X.509 Certificate for TLS connections into MockServer.
     * <p>
     * The certificate must be a X509 PEM file and must be the public key corresponding to the privateKeyPath private key configuration.
     * The certificateAuthorityCertificate configuration must be the Certificate Authority for this certificate (i.e. able to valid its signature).
     * <p>
     * This configuration will be ignored unless privateKeyPath is also set.
     *
     * @param x509CertificatePath location of the PEM file containing the X509 certificate
     */
    public static void x509CertificatePath(String x509CertificatePath) {
        // See the comment on certificateAuthorityPrivateKey above — instance setter is
        // intentionally lenient because dynamic SSL generation pre-stores the destination.
        fileExists(x509CertificatePath);
        setProperty(MOCKSERVER_TLS_X509_CERTIFICATE_PATH, x509CertificatePath);
    }

    public static boolean tlsMutualAuthenticationRequired() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED", "false"));
    }

    /**
     * Require mTLS (also called client authentication and two-way TLS) for all TLS connections / HTTPS requests to MockServer
     *
     * @param enable TLS mutual authentication
     */
    public static void tlsMutualAuthenticationRequired(boolean enable) {
        setProperty(MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_REQUIRED, "" + enable);
    }

    public static String tlsMutualAuthenticationCertificateChain() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN", "");
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used if MockServer performs mTLS (client authentication) for inbound TLS connections because tlsMutualAuthenticationRequired is enabled
     *
     * @param trustCertificateChain File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates
     */
    public static void tlsMutualAuthenticationCertificateChain(String trustCertificateChain) {
        fileExists(trustCertificateChain);
        setProperty(MOCKSERVER_TLS_MUTUAL_AUTHENTICATION_CERTIFICATE_CHAIN, "" + trustCertificateChain);
    }

    public static ForwardProxyTLSX509CertificatesTrustManager forwardProxyTLSX509CertificatesTrustManagerType() {
        String forwardProxyTlsX509CertificatesTrustManagerType = readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE, "MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE", "ANY");
        try {
            return ForwardProxyTLSX509CertificatesTrustManager.valueOf(forwardProxyTlsX509CertificatesTrustManagerType);
        } catch (Throwable ignore) {
            throw new IllegalArgumentException("Invalid value for ForwardProxyTLSX509CertificatesTrustManager \"" + forwardProxyTlsX509CertificatesTrustManagerType + "\" the only supported values are: " + Arrays.stream(ForwardProxyTLSX509CertificatesTrustManager.values()).map(Enum::name).collect(Collectors.toList()));
        }
    }

    /**
     * Configure trusted set of certificates for forwarded or proxied requests.
     * <p>
     * MockServer will only be able to establish a TLS connection to endpoints that have a trusted X509 certificate according to the trust manager type, as follows:
     * <p>
     * <p>
     * ANY - Insecure will trust all X509 certificates and not perform host name verification.
     * JVM - Will trust all X509 certificates trust by the JVM.
     * CUSTOM - Will trust all X509 certificates specified in forwardProxyTLSCustomTrustX509Certificates configuration value.
     *
     * @param trustManagerType trusted set of certificates for forwarded or proxied requests, allowed values: ANY, JVM, CUSTOM.
     */
    public static void forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager trustManagerType) {
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATES_TRUST_MANAGER_TYPE, trustManagerType.name());
    }

    public static boolean forwardProxyBlockPrivateNetworks() {
        return Boolean.parseBoolean(readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS, "MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS", "" + false));
    }

    /**
     * When set to true, MockServer rejects forward and proxy targets that resolve to
     * loopback, link-local, RFC 1918 private, or cloud metadata addresses
     * (such as 169.254.169.254). This blocks server-side request forgery (SSRF) attacks
     * where a malicious expectation forwards through MockServer to internal infrastructure.
     * <p>
     * The default is false because MockServer is primarily used to mock services in
     * private or loopback test networks (Docker bridges, Kubernetes service IPs,
     * localhost), so blocking those targets by default would break the common case.
     * Enable this in hardened or multi-tenant deployments where untrusted callers can
     * register expectations.
     *
     * @param block if true, block forwarding to private or metadata addresses
     */
    public static void forwardProxyBlockPrivateNetworks(boolean block) {
        setProperty(MOCKSERVER_FORWARD_PROXY_BLOCK_PRIVATE_NETWORKS, "" + block);
    }

    public static String forwardProxyTLSCustomTrustX509Certificates() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES, "MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES", "");
    }

    /**
     * File system path or classpath location of custom file for trusted X509 Certificate Authority roots for forwarded or proxied requests, the certificate chain must be a X509 PEM file.
     * <p>
     * MockServer will only be able to establish a TLS connection to endpoints that have an X509 certificate chain that is signed by one of the provided custom
     * certificates, i.e. where a path can be established from the endpoints X509 certificate to one or more of the custom X509 certificates provided.
     *
     * @param customX509Certificates custom set of trusted X509 certificate authority roots for forwarded or proxied requests in PEM format.
     */
    public static void forwardProxyTLSCustomTrustX509Certificates(String customX509Certificates) {
        fileExists(customX509Certificates);
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_CUSTOM_TRUST_X509_CERTIFICATES, customX509Certificates);
    }

    public static String forwardProxyPrivateKey() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY, "MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY", "");
    }

    /**
     * File system path or classpath location of custom Private Key for proxied TLS connections out of MockServer, the private key must be a PKCS#8 or PKCS#1 PEM file
     * <p>
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * This private key will be used if MockServer needs to perform mTLS (client authentication) for outbound TLS connections.
     *
     * @param privateKey location of the PEM file containing the private key
     */
    public static void forwardProxyPrivateKey(String privateKey) {
        fileExists(privateKey);
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_PRIVATE_KEY, privateKey);
    }

    public static String forwardProxyCertificateChain() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN, "MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN", "");
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used if MockServer needs to perform mTLS (client authentication) for outbound TLS connections.
     *
     * @param certificateChain location of the PEM file containing the certificate chain
     */
    public static void forwardProxyCertificateChain(String certificateChain) {
        fileExists(certificateChain);
        setProperty(MOCKSERVER_FORWARD_PROXY_TLS_X509_CERTIFICATE_CHAIN, certificateChain);
    }

    public static String forwardProxyClientCertificatesByHost() {
        return readPropertyHierarchically(PROPERTIES, MOCKSERVER_FORWARD_PROXY_CLIENT_CERTIFICATES_BY_HOST, "MOCKSERVER_FORWARD_PROXY_CLIENT_CERTIFICATES_BY_HOST", "");
    }

    /**
     * Per-host outbound mTLS (client authentication) certificate/key map for forwarded or proxied requests.
     * <p>
     * A comma-separated list of {@code host=certificateChainPath;privateKeyPath} entries, where each path is a
     * file system path or classpath location of an X.509 PEM certificate chain and a PKCS#8 or PKCS#1 PEM private
     * key. When MockServer opens an outbound TLS connection to a matching upstream host (case-insensitive), it
     * presents that host's certificate/key pair for mTLS; any host without an entry falls back to the global
     * {@code forwardProxyPrivateKey} / {@code forwardProxyCertificateChain} pair (or MockServer's own generated
     * key/cert). The default is empty (global pair only). Example:
     * {@code api.internal=/certs/api-chain.pem;/certs/api-key.pem,billing.internal=/certs/billing-chain.pem;/certs/billing-key.pem}
     *
     * @param clientCertificatesByHost comma-separated host=certificateChainPath;privateKeyPath entries
     */
    public static void forwardProxyClientCertificatesByHost(String clientCertificatesByHost) {
        setProperty(MOCKSERVER_FORWARD_PROXY_CLIENT_CERTIFICATES_BY_HOST, clientCertificatesByHost != null ? clientCertificatesByHost : "");
    }

    @SuppressWarnings("ConstantConditions")
    static void fileExists(String file) {
        try {
            if (isNotBlank(file) && FileReader.openStreamToFileFromClassPathOrPath(file) == null) {
                throw new RuntimeException(file + " does not exist or is not accessible");
            }
        } catch (FileNotFoundException e) {
            if (!new File(file).exists()) {
                throw new RuntimeException(file + " does not exist or is not accessible");
            }
        }
    }

    private static void validateHostAndPortAndSetProperty(String hostAndPort, String mockserverSocksProxy) {
        if (isNotBlank(hostAndPort)) {
            if (hostAndPort.startsWith("/")) {
                hostAndPort = StringUtils.substringAfter(hostAndPort, "/");
            }
            String errorMessage = "Invalid property value \"" + hostAndPort + "\" for \"" + mockserverSocksProxy + "\" must include <host>:<port> for example \"127.0.0.1:1090\" or \"localhost:1090\"";
            try {
                URI uri = new URI("https://" + hostAndPort);
                if (uri.getHost() == null || uri.getPort() == -1) {
                    throw new IllegalArgumentException(errorMessage);
                } else {
                    setProperty(mockserverSocksProxy, hostAndPort);
                }
            } catch (URISyntaxException ex) {
                throw new IllegalArgumentException(errorMessage);
            }
        } else {
            clearProperty(mockserverSocksProxy);
        }
    }

    private static InetSocketAddress readInetSocketAddressProperty(String key, String environmentVariableKey) {
        InetSocketAddress inetSocketAddress = null;
        String proxy = readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "");
        if (isNotBlank(proxy)) {
            String[] proxyParts = org.mockserver.model.HttpRequest.splitHostPort(proxy);
            if (proxyParts.length > 1) {
                try {
                    inetSocketAddress = new InetSocketAddress(proxyParts[0], Integer.parseInt(proxyParts[1]));
                } catch (NumberFormatException nfe) {
                    LoggerHolder.LOGGER.logEvent(
                        new LogEntry()
                            .setLogLevel(Level.ERROR)
                            .setMessageFormat("NumberFormatException converting value \"" + proxyParts[1] + "\" into an integer")
                            .setThrowable(nfe)
                    );
                }
            }
        }
        return inetSocketAddress;
    }

    private static Integer readIntegerProperty(String key, String environmentVariableKey, int defaultValue) {
        try {
            return Integer.parseInt(readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue));
        } catch (NumberFormatException nfe) {
            LoggerHolder.LOGGER.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("NumberFormatException converting " + key + " with value [" + readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue) + "]")
                    .setThrowable(nfe)
            );
            return defaultValue;
        }
    }

    private static Long readLongProperty(String key, String environmentVariableKey, long defaultValue) {
        try {
            return Long.parseLong(readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue));
        } catch (NumberFormatException nfe) {
            LoggerHolder.LOGGER.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("NumberFormatException converting " + key + " with value [" + readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue) + "]")
                    .setThrowable(nfe)
            );
            return defaultValue;
        }
    }

    /**
     * Reads a millisecond duration that may be configured under either the primary key (e.g.
     * {@code mockserver.maxSocketTimeout} — the name the programmatic setters and the rest of the code
     * use) or the unit-bearing {@code InMillis}-suffixed alias (e.g.
     * {@code mockserver.maxSocketTimeoutInMillis}) that matches {@link Configuration#maxSocketTimeoutInMillis()}
     * and the key reported in the {@code /mockserver/configuration} JSON. The two names are synonyms for
     * the same setting, so an operator who reaches for the alias is honoured rather than silently ignored.
     * <p>
     * The primary key is read first, so a value applied at runtime via the programmatic setter (which
     * writes the primary key) always takes effect — a launch-time alias can never silently override a
     * later programmatic set. The alias is consulted only when the primary key is not set by any source.
     * If both names are set at launch to different values (a contradictory configuration that cannot be
     * ordered), the primary key wins.
     */
    private static Long readLongPropertyWithMillisAlias(String key, String environmentVariableKey, String aliasKey, String aliasEnvironmentVariableKey, long defaultValue) {
        long primary = readLongProperty(key, environmentVariableKey, defaultValue);
        if (primary != defaultValue) {
            return primary;
        }
        return readLongProperty(aliasKey, aliasEnvironmentVariableKey, defaultValue);
    }

    private static double readDoubleProperty(String key, String environmentVariableKey, double defaultValue) {
        try {
            return Double.parseDouble(readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue));
        } catch (NumberFormatException nfe) {
            LoggerHolder.LOGGER.logEvent(
                new LogEntry()
                    .setLogLevel(Level.ERROR)
                    .setMessageFormat("NumberFormatException converting " + key + " with value [" + readPropertyHierarchically(PROPERTIES, key, environmentVariableKey, "" + defaultValue) + "]")
                    .setThrowable(nfe)
            );
            return defaultValue;
        }
    }

    /**
     * Returns {@code true} when the property name (with or without the
     * {@code mockserver.} prefix) indicates the value is a secret and must not be logged verbatim.
     *
     * <p>Two rules apply:
     * <ol>
     *   <li>the name contains one of {@link #SENSITIVE_SUBSTRINGS}, and</li>
     *   <li>the name ENDS in {@code key} — a name-shape rule, not an enumeration.</li>
     * </ol>
     *
     * <p>Rule 2 exists because the substring list omitted bare {@code key}, so
     * {@code mockserver.dashboardAnalyticsKey} matched none of the 13 substrings and its value was logged
     * in clear. Enumerating credential substrings cannot keep up with new properties: any future
     * {@code ...Key} property would silently leak the same way. {@link #NON_SENSITIVE_KEY_SUFFIXED_NAMES}
     * carries the documented exceptions.
     *
     * <p>public (was package-private) for the same reason as {@link #REDACTED_VALUE}: the guard over
     * {@code ConfigurationDTO} — the {@code GET /mockserver/configuration} surface, in another package
     * — must ask THIS predicate what counts as a credential rather than growing a second, divergent
     * notion of one. A leak closed on one endpoint and left open on its sibling is exactly what a
     * second notion produces.
     */
    public static boolean isSensitivePropertyName(String name) {
        if (name == null) {
            return false;
        }
        String lower = normalisePropertyName(name);
        for (String sensitive : SENSITIVE_SUBSTRINGS) {
            if (lower.contains(sensitive)) {
                return true;
            }
        }
        return lower.endsWith("key") && !NON_SENSITIVE_KEY_SUFFIXED_NAMES.contains(lower);
    }

    /** Lower-cased and {@code mockserver.}-stripped, so "mockserver.llmApiKey" matches "apikey". */
    private static String normalisePropertyName(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.startsWith("mockserver.") ? lower.substring("mockserver.".length()) : lower;
    }

    /**
     * The value to disclose for a configuration property — THE single redaction rule, shared by every
     * surface that hands a configuration value to a reader: {@code GET /mockserver/configuration} (via
     * {@code ConfigurationDTO}), {@code GET /mockserver/config} and {@code --print-config} (via
     * {@link #effectiveConfiguration()}), and the startup property-file log dump.
     *
     * <p>Three outcomes: a property that IS a credential ({@link #isSensitivePropertyName(String)}) is
     * masked whole; a property that CARRIES credentials inside a structured value is redacted per
     * header / per JSON field by {@link EmbeddedCredentialRedaction}, leaving the surrounding
     * configuration readable; anything else is returned unchanged.
     *
     * <p>Adding a second, endpoint-local notion of "credential" is exactly the failure this replaces —
     * a leak closed on one endpoint and left open on its sibling.
     */
    public static String redactSensitiveValue(String propertyName, String value) {
        if (value == null) {
            return null;
        }
        if (isSensitivePropertyName(propertyName)) {
            return REDACTED_VALUE;
        }
        if (value.isEmpty()) {
            return value;
        }
        String normalised = normalisePropertyName(propertyName);
        if (HEADER_LIST_CREDENTIAL_PROPERTIES.contains(normalised)) {
            return EmbeddedCredentialRedaction.redactHeaderList(value);
        }
        if (JSON_DOCUMENT_CREDENTIAL_PROPERTIES.contains(normalised)) {
            return EmbeddedCredentialRedaction.redactJsonDocument(value);
        }
        return value;
    }

    /**
     * The inverse of {@link #redactSensitiveValue(String, String)} for a value being written back:
     * each redacted header/field is restored from {@code heldValue} — the value the server already
     * holds — so a {@code GET}-then-{@code PUT} round trip cannot overwrite a working credential with
     * the mask, while the unmasked parts of the same value are applied normally.
     *
     * <p>The result is <strong>never</strong> a value still carrying the mask, and a value refused for
     * that reason is logged rather than dropped in silence. The check lives here so it covers every
     * caller of this method at once — a mask written into the live configuration destroys the real
     * credential AND makes the literal mask the outbound one, so it must be impossible for any present
     * or future caller, not just the two known ones.
     *
     * <p>Both credential shapes come through here, as they do through
     * {@link #redactSensitiveValue(String, String)} on the read side: a value-embedded credential is
     * reconciled header by header / field by field, while a WHOLE-VALUE credential (the whole value is
     * the secret) has no unmasked part to merge: an untouched round trip — the value is exactly the
     * mask — short-circuits silently, and everything else is decided by the mask check at the end,
     * refused if it still carries the mask anywhere and applied otherwise. Whole-value credentials must not
     * grow their own notion of "masked": the endpoint-local {@code equals(mask)} guard they used to
     * have let {@code "sk-***REDACTED***"} through as a real key, destroying the held credential and
     * making the literal mask the outbound one.
     *
     * <p>Note this is <strong>not</strong> the only write path for these properties.
     * {@code ConfigurationDTO.applyTo} merges an incoming value against the value the target holds and
     * comes through here; {@code ConfigurationDTO.buildObject} builds a FRESH configuration with
     * nothing to merge against and so applies the simpler rule directly — a value carrying the mask is
     * left unset, letting the static store resolve. A third credential property of either shape must be
     * wired into BOTH.
     *
     * <p>Note the two methods test {@link #isSensitivePropertyName(String)} at OPPOSITE ends —
     * {@code redactSensitiveValue} first, this one last — so a property that was both credential-NAMED
     * and structured would be masked whole on read but routed to the per-header/per-field restore on
     * write. The two categories must stay mutually exclusive; today they are.
     *
     * @return the value to write, or {@code null} to leave the held value untouched
     */
    public static String restoreRedactedValue(String propertyName, String incomingValue, String heldValue) {
        String normalised = normalisePropertyName(propertyName);
        String restored;
        if (HEADER_LIST_CREDENTIAL_PROPERTIES.contains(normalised)) {
            restored = EmbeddedCredentialRedaction.restoreHeaderList(propertyName, incomingValue, heldValue);
        } else if (JSON_DOCUMENT_CREDENTIAL_PROPERTIES.contains(normalised)) {
            restored = EmbeddedCredentialRedaction.restoreJsonDocument(propertyName, incomingValue, heldValue);
        } else if (isWholeValueMaskedOnRead(propertyName) && REDACTED_VALUE.equals(incomingValue)) {
            // the UNTOUCHED round trip for a WHOLE-VALUE credential: read back as the bare mask and sent
            // straight in again. That is the blessed GET-then-PUT flow, not an operator error, so the
            // held value is left alone SILENTLY. A config-as-code tool that GETs, edits one unrelated
            // property and PUTs the whole blob would otherwise emit a security-flavoured warning per
            // credential on every apply, and anyone alerting on WARN would read it as a credential
            // having been lost. The embedded shapes are already silent here by construction —
            // restoreHeaderList returns the held value verbatim when the incoming list is exactly its
            // masked form — so this makes one operator action log one way whatever the credential shape.
            // Only a value the operator EDITED around the mask falls through to the warning below.
            //
            // Keyed on the properties GET /mockserver/configuration ACTUALLY returns masked, not on
            // isSensitivePropertyName. That predicate matches every credential-SHAPED name, which is a
            // far larger set: the credentials that endpoint OMITS, and the ones it returns in clear,
            // have no round trip that can produce the bare mask, so for them it is always hand-typed —
            // copied out of GET /mockserver/config, --print-config or the Server Info tab, which are
            // diagnostic views and not PUT bodies. Silencing those was silencing the exact operator
            // mistake that destroys a working credential while the PUT answers 200 OK.
            return null;
        } else {
            restored = incomingValue;
        }
        if (containsRedactionMask(restored)) {
            // reachable for a property with no embedded-credential shape, and for a non-document value
            // carrying the mask, neither of which the restore* methods examine — so this is the point
            // that must tell the operator, or the enclosing PUT answers 200 OK having written nothing.
            // Cannot double-log: every restore* path that already logged returned null, and
            // containsRedactionMask(null) is false.
            //
            // It is ALSO the only check a WHOLE-VALUE credential gets once the untouched round trip has
            // been taken above: masked whole on the way out, it has no unmasked part to merge against on
            // the way back, so an EDITED value carrying the mask is not a real credential and the held
            // one is left alone. (A value carrying no mask is applied, including the empty value that
            // CLEARS it.) That path resolves nothing from the held value — both restore* methods are
            // skipped — so it gets its own reason rather than one claiming a merge that never ran.
            // dropUnmergeableValue adds the recovery step to both, because this warning is the only
            // thing the operator gets back from a PUT that otherwise answers 200 OK.
            return EmbeddedCredentialRedaction.dropUnmergeableValue(propertyName,
                isSensitivePropertyName(propertyName)
                    ? "the whole value is the credential, so a value carrying the mask is not one"
                    : "it still carries the mask once everything that could be resolved from the held value has been");
        }
        return restored;
    }

    /**
     * True when a value carries the redaction mask anywhere inside it, so it is not wholly real and
     * must not be stored as-is where there is no held value to restore the masked parts from.
     */
    public static boolean containsRedactionMask(String value) {
        return value != null && value.contains(REDACTED_VALUE);
    }

    @SuppressWarnings("ConstantConditions")
    private static Properties readPropertyFile() {

        Properties properties = new Properties();

        if (propertyFile().endsWith(".json")) {
            properties = readJsonPropertyFile();
        } else {
            try (InputStream inputStream = ConfigurationProperties.class.getClassLoader().getResourceAsStream(propertyFile())) {
                if (inputStream != null) {
                    try {
                        properties.load(inputStream);
                    } catch (IOException e) {
                        if (LoggerHolder.LOGGER != null) {
                            LoggerHolder.LOGGER.logEvent(
                                new LogEntry()
                                    .setAlwaysLog(true)
                                    .setLogLevel(Level.ERROR)
                                    .setMessageFormat("exception loading property file [" + propertyFile() + "]")
                                    .setThrowable(e)
                            );
                        }
                    }
                } else {
                    if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(DEBUG)) {
                        LoggerHolder.LOGGER.logEvent(
                            new LogEntry()
                                .setType(SERVER_CONFIGURATION)
                                .setLogLevel(DEBUG)
                                .setMessageFormat("property file not found on classpath using path [" + propertyFile() + "]")
                        );
                    }
                    try {
                        properties.load(new FileInputStream(propertyFile()));
                    } catch (FileNotFoundException e) {
                        if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(DEBUG)) {
                            LoggerHolder.LOGGER.logEvent(
                                new LogEntry()
                                    .setType(SERVER_CONFIGURATION)
                                    .setLogLevel(DEBUG)
                                    .setMessageFormat("property file not found using path [" + propertyFile() + "]")
                            );
                        }
                    } catch (IOException e) {
                        if (LoggerHolder.LOGGER != null) {
                            LoggerHolder.LOGGER.logEvent(
                                new LogEntry()
                                    .setAlwaysLog(true)
                                    .setLogLevel(Level.ERROR)
                                    .setMessageFormat("exception loading property file [" + propertyFile() + "]")
                                    .setThrowable(e)
                            );
                        }
                    }
                }
            } catch (IOException ioe) {
                // ignore
            }
        }

        if (!properties.isEmpty()) {
            Enumeration<?> propertyNames = properties.propertyNames();

            StringBuilder propertiesLogDump = new StringBuilder();
            propertiesLogDump.append("Reading properties from property file [").append(propertyFile()).append("]:").append(NEW_LINE);
            while (propertyNames.hasMoreElements()) {
                String propertyName = String.valueOf(propertyNames.nextElement());
                String displayValue = redactSensitiveValue(propertyName, properties.getProperty(propertyName));
                propertiesLogDump.append("  ").append(propertyName).append(" = ").append(displayValue).append(NEW_LINE);
            }

            Level logLevel = Level.valueOf(getSLF4JOrJavaLoggerToSLF4JLevelMapping().get(readPropertyHierarchically(properties, MOCKSERVER_LOG_LEVEL, "MOCKSERVER_LOG_LEVEL", DEFAULT_LOG_LEVEL).toUpperCase()));
            if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(Level.INFO, logLevel)) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setAlwaysLog(true)
                        .setType(SERVER_CONFIGURATION)
                        .setLogLevel(Level.INFO)
                        .setMessageFormat(propertiesLogDump.toString())
                );
            }
        }

        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Properties readJsonPropertyFile() {
        Properties properties = new Properties();
        InputStream inputStream = ConfigurationProperties.class.getClassLoader().getResourceAsStream(propertyFile());
        try {
            if (inputStream == null) {
                try {
                    inputStream = new FileInputStream(propertyFile());
                } catch (FileNotFoundException e) {
                    if (LoggerHolder.LOGGER != null && MockServerLogger.isEnabled(DEBUG)) {
                        LoggerHolder.LOGGER.logEvent(
                            new LogEntry()
                                .setType(SERVER_CONFIGURATION)
                                .setLogLevel(DEBUG)
                                .setMessageFormat("JSON property file not found using path [" + propertyFile() + "]")
                        );
                    }
                    return properties;
                }
            }
            com.fasterxml.jackson.databind.ObjectMapper objectMapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
            Map<String, Object> jsonMap = objectMapper.readValue(inputStream, Map.class);
            for (Map.Entry<String, Object> entry : jsonMap.entrySet()) {
                String key = "mockserver." + entry.getKey();
                Object value = entry.getValue();
                if (value != null) {
                    if (value instanceof Collection) {
                        properties.setProperty(key, Joiner.on(",").join((Collection<?>) value));
                    } else if (value instanceof Map) {
                        Map<?, ?> mapValue = (Map<?, ?>) value;
                        properties.setProperty(key, mapValue.entrySet().stream()
                            .map(e -> e.getKey() + "=" + e.getValue())
                            .collect(Collectors.joining(",")));
                    } else {
                        properties.setProperty(key, String.valueOf(value));
                    }
                }
            }
        } catch (IOException e) {
            if (LoggerHolder.LOGGER != null) {
                LoggerHolder.LOGGER.logEvent(
                    new LogEntry()
                        .setAlwaysLog(true)
                        .setLogLevel(Level.ERROR)
                        .setMessageFormat("exception loading JSON property file [" + propertyFile() + "]")
                        .setThrowable(e)
                );
            }
        } finally {
            if (inputStream != null) {
                try {
                    inputStream.close();
                } catch (IOException e) {
                    // ignore
                }
            }
        }
        return properties;
    }

    private static Map<String, String> propertyCache;

    // Keys written via a programmatic setter (setProperty). Used ONLY by the effective-configuration
    // diagnostic to distinguish a genuine runtime override from a value that was merely cached by a
    // first read (readPropertyHierarchically caches every value it resolves, including built-in
    // defaults). It never affects how any value resolves.
    private static Set<String> programmaticallySetKeys;

    private static Map<String, String> getPropertyCache() {
        if (propertyCache == null) {
            propertyCache = new ConcurrentHashMap<>();
        }
        return propertyCache;
    }

    private static Set<String> getProgrammaticallySetKeys() {
        if (programmaticallySetKeys == null) {
            programmaticallySetKeys = ConcurrentHashMap.newKeySet();
        }
        return programmaticallySetKeys;
    }

    private static void setProperty(String systemPropertyKey, String value) {
        getPropertyCache().put(systemPropertyKey, value);
        getProgrammaticallySetKeys().add(systemPropertyKey);
        System.setProperty(systemPropertyKey, value);
    }

    private static void clearProperty(String systemPropertyKey) {
        getPropertyCache().remove(systemPropertyKey);
        getProgrammaticallySetKeys().remove(systemPropertyKey);
        System.clearProperty(systemPropertyKey);
    }

    private static String readPropertyHierarchically(Properties properties, String systemPropertyKey, String environmentVariableKey, String defaultValue) {
        String cachedPropertyValue = getPropertyCache().get(systemPropertyKey);
        if (cachedPropertyValue != null) {
            return cachedPropertyValue;
        } else {
            if (isBlank(environmentVariableKey)) {
                throw new IllegalArgumentException("environment property name cannot be null for " + systemPropertyKey);
            }
            String defaultOrEnvironmentVariable = isNotBlank(System.getenv(environmentVariableKey)) ? System.getenv(environmentVariableKey) : defaultValue;
            String propertyValue = System.getProperty(systemPropertyKey, properties != null ? properties.getProperty(systemPropertyKey, defaultOrEnvironmentVariable) : defaultOrEnvironmentVariable);
            if (propertyValue != null && propertyValue.startsWith("\"") && propertyValue.endsWith("\"")) {
                propertyValue = propertyValue.replaceAll("^\"|\"$", "");
            }
            getPropertyCache().put(systemPropertyKey, propertyValue);
            return propertyValue;
        }
    }

    // ------------------------------------------------------------------------
    // Unknown-configuration-key detection
    //
    // Every recognised configuration property is declared in this class as a
    // "private static final String MOCKSERVER_XXX = "mockserver.xxx";" constant.
    // Two facts make the constants the single source of truth (no second list to
    // drift):
    //   * the constant VALUE (e.g. "mockserver.maxExpectations") is the
    //     mockserver.* system-property / properties-file key, and
    //   * the constant NAME  (e.g. MOCKSERVER_MAX_EXPECTATIONS) is, verbatim, the
    //     MOCKSERVER_* environment-variable key passed at every read site (the
    //     env-var resolution does not transform the property name — it looks up a
    //     literal MOCKSERVER_* string that equals the field name).
    // We therefore enumerate the constants reflectively to build both authoritative
    // sets, so adding a new property automatically extends recognition.
    // ------------------------------------------------------------------------

    private static volatile Set<String> recognisedSystemPropertyKeys;
    private static volatile Set<String> recognisedEnvironmentVariableKeys;

    private static void enumerateRecognisedKeys() {
        if (recognisedSystemPropertyKeys != null) {
            return;
        }
        Set<String> systemPropertyKeys = new HashSet<>();
        Set<String> environmentVariableKeys = new HashSet<>();
        for (Field field : ConfigurationProperties.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class
                && field.getName().startsWith("MOCKSERVER_")) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof String && ((String) value).startsWith("mockserver.")) {
                        systemPropertyKeys.add((String) value);
                        // The field NAME is the literal MOCKSERVER_* environment-variable key.
                        environmentVariableKeys.add(field.getName());
                    }
                } catch (Throwable throwable) {
                    // Ignore a single inaccessible constant rather than failing the whole scan.
                }
            }
        }
        // Union in the keys handled by the CLI launcher / launcher scripts rather than by a
        // MOCKSERVER_* constant here (see the allowlist declarations above for why each belongs).
        systemPropertyKeys.addAll(EXTRA_RECOGNISED_SYSTEM_PROPERTY_KEYS);
        environmentVariableKeys.addAll(EXTRA_RECOGNISED_ENV_KEYS);
        recognisedSystemPropertyKeys = systemPropertyKeys;
        recognisedEnvironmentVariableKeys = environmentVariableKeys;
    }

    /**
     * Recognised {@code mockserver.*} system-property / properties-file keys, derived
     * reflectively from the {@code MOCKSERVER_*} constants declared in this class.
     */
    static Set<String> recognisedSystemPropertyKeys() {
        enumerateRecognisedKeys();
        return Collections.unmodifiableSet(recognisedSystemPropertyKeys);
    }

    /**
     * Recognised {@code MOCKSERVER_*} environment-variable keys, derived reflectively
     * from the {@code MOCKSERVER_*} constant field names declared in this class.
     */
    static Set<String> recognisedEnvironmentVariableKeys() {
        enumerateRecognisedKeys();
        return Collections.unmodifiableSet(recognisedEnvironmentVariableKeys);
    }

    /**
     * Pure detection helper (no I/O, no global-state reads) used by both the startup
     * warning and the tests: returns, sorted, the description of every supplied key that
     * is in the {@code mockserver.}/{@code MOCKSERVER_} namespace but is not recognised.
     *
     * @param systemPropertyNames     names of JVM system properties (e.g. {@code System.getProperties()} keys)
     * @param environmentVariableNames names of environment variables (e.g. {@code System.getenv()} keys)
     * @param propertiesFile          loaded properties file (may be {@code null})
     */
    static List<String> findUnknownConfigurationKeys(Set<String> systemPropertyNames, Set<String> environmentVariableNames, Properties propertiesFile) {
        enumerateRecognisedKeys();
        Set<String> warnings = new TreeSet<>();

        if (systemPropertyNames != null) {
            for (String name : systemPropertyNames) {
                if (name != null && name.startsWith("mockserver.") && !recognisedSystemPropertyKeys.contains(name)) {
                    warnings.add("system property [" + name + "]");
                }
            }
        }

        if (environmentVariableNames != null) {
            for (String name : environmentVariableNames) {
                if (name != null && name.startsWith("MOCKSERVER_") && !recognisedEnvironmentVariableKeys.contains(name)) {
                    warnings.add("environment variable [" + name + "]");
                }
            }
        }

        if (propertiesFile != null) {
            Enumeration<?> propertyNames = propertiesFile.propertyNames();
            while (propertyNames.hasMoreElements()) {
                String name = String.valueOf(propertyNames.nextElement());
                if (name.startsWith("mockserver.")
                    && !recognisedSystemPropertyKeys.contains(name)
                    // mockserver.propertyFile itself can legitimately be set in a file; it is a recognised key,
                    // so this is covered by the recognised-set check above. No extra exclusions are needed.
                    && !warnings.contains("system property [" + name + "]")) {
                    warnings.add("properties-file key [" + name + "]");
                }
            }
        }

        return new ArrayList<>(warnings);
    }

    /**
     * Logs a WARN for every {@code mockserver.*} system property, {@code MOCKSERVER_*}
     * environment variable, or {@code mockserver.*} properties-file key that is not a
     * recognised configuration property — the common "I set it but nothing happened"
     * typo (e.g. {@code -Dmockserver.maxExpectatons=...}). Runs at most once and can
     * never throw, so it cannot break startup.
     */
    static void warnAboutUnknownConfigurationProperties() {
        // compareAndSet ensures the check runs at most once even under concurrent callers, so two
        // threads racing here can never both emit the same warning.
        if (!unknownConfigurationPropertiesChecked.compareAndSet(false, true)) {
            return;
        }
        try {
            Set<String> systemPropertyNames = System.getProperties().stringPropertyNames();
            Set<String> environmentVariableNames = System.getenv().keySet();
            List<String> unknownKeys = findUnknownConfigurationKeys(systemPropertyNames, environmentVariableNames, PROPERTIES);
            if (!unknownKeys.isEmpty() && LoggerHolder.LOGGER != null) {
                for (String unknownKey : unknownKeys) {
                    LoggerHolder.LOGGER.logEvent(
                        new LogEntry()
                            .setAlwaysLog(true)
                            .setType(SERVER_CONFIGURATION)
                            .setLogLevel(Level.WARN)
                            .setMessageFormat("unrecognised MockServer configuration " + unknownKey + " - it is not a known configuration property and will be ignored (check for a typo)")
                    );
                }
            }
        } catch (Throwable throwable) {
            // Never let configuration validation break startup.
        }
    }

    // ------------------------------------------------------------------------
    // Effective-configuration diagnostic (--print-config / GET /mockserver/config)
    //
    // Reports, for every recognised configuration property, the resolved value
    // together with the tier that supplied it. The reporting is PURELY OBSERVATIONAL:
    // it inspects the same three tiers in the same precedence order as
    // readPropertyHierarchically(...) (system property > properties file > env var,
    // then the built-in default) but NEVER mutates the property cache or changes how
    // any value resolves. Sensitive values are redacted via isSensitivePropertyName(...).
    // ------------------------------------------------------------------------

    /** Source-tier labels for a resolved configuration property. */
    public static final String SOURCE_SYSTEM_PROPERTY = "system-property";
    public static final String SOURCE_PROPERTIES_FILE = "properties-file";
    public static final String SOURCE_ENVIRONMENT_VARIABLE = "environment-variable";
    public static final String SOURCE_DEFAULT = "default";
    /**
     * The value was set at runtime (a programmatic {@code ConfigurationProperties} setter) and is
     * held in the in-memory property cache — which {@link #readPropertyHierarchically} consults
     * FIRST, so it is what MockServer actually uses regardless of the underlying tiers. Reported
     * only when the cached value does not also resolve from one of the static tiers.
     */
    public static final String SOURCE_RUNTIME_SET = "runtime-set";

    /**
     * A single resolved configuration property: its {@code mockserver.*} key, the value
     * actually in effect (already redacted when sensitive), and the {@link #source} tier
     * that supplied it (one of the {@code SOURCE_*} constants).
     */
    public static final class ResolvedProperty {
        private final String name;
        private final String value;
        private final String source;

        ResolvedProperty(String name, String value, String source) {
            this.name = name;
            this.value = value;
            this.source = source;
        }

        public String getName() {
            return name;
        }

        public String getValue() {
            return value;
        }

        public String getSource() {
            return source;
        }
    }

    /**
     * Reports which tier currently supplies the value for the given property WITHOUT
     * changing resolution. Mirrors the precedence in
     * {@link #readPropertyHierarchically(Properties, String, String, String)}:
     * system property &gt; properties file &gt; environment variable &gt; default.
     *
     * @param systemPropertyKey      the {@code mockserver.*} key
     * @param environmentVariableKey the {@code MOCKSERVER_*} environment-variable key
     * @return one of the {@code SOURCE_*} constants
     */
    static String resolveEffectiveSource(String systemPropertyKey, String environmentVariableKey) {
        return resolveEffectiveSource(
            System.getProperty(systemPropertyKey),
            PROPERTIES != null ? PROPERTIES.getProperty(systemPropertyKey) : null,
            isNotBlank(environmentVariableKey) ? System.getenv(environmentVariableKey) : null
        );
    }

    /**
     * Pure source-resolution: given the raw value found in each tier (any may be
     * {@code null}/blank), returns the tier that wins, in the SAME precedence order as
     * {@link #readPropertyHierarchically}: system property &gt; properties file &gt;
     * environment variable &gt; default. No I/O or global-state reads — used by both the
     * live {@link #resolveEffectiveSource(String, String)} and the tests.
     */
    static String resolveEffectiveSource(String systemPropertyValue, String propertiesFileValue, String environmentVariableValue) {
        if (isNotBlank(systemPropertyValue)) {
            return SOURCE_SYSTEM_PROPERTY;
        }
        if (isNotBlank(propertiesFileValue)) {
            return SOURCE_PROPERTIES_FILE;
        }
        if (isNotBlank(environmentVariableValue)) {
            return SOURCE_ENVIRONMENT_VARIABLE;
        }
        return SOURCE_DEFAULT;
    }

    /**
     * Returns the value an explicitly-set property would resolve to, reading the SAME
     * tiers in the SAME order as {@link #readPropertyHierarchically} but without touching
     * the property cache. Returns {@code null} when the key is at its built-in default
     * (the typed default is computed at the individual read sites, so it is reported as
     * {@code (default)} rather than guessed here).
     */
    private static String resolveExplicitValue(String systemPropertyKey, String environmentVariableKey) {
        return resolveExplicitValue(
            System.getProperty(systemPropertyKey),
            PROPERTIES != null ? PROPERTIES.getProperty(systemPropertyKey) : null,
            isNotBlank(environmentVariableKey) ? System.getenv(environmentVariableKey) : null
        );
    }

    /**
     * Pure value-resolution mirroring {@link #resolveEffectiveSource(String, String, String)}:
     * returns the first non-blank tier value (system property &gt; properties file &gt;
     * environment variable), or {@code null} for a default. Strips surrounding quotes exactly
     * as {@link #readPropertyHierarchically} does so the reported value matches what MockServer
     * actually uses.
     */
    static String resolveExplicitValue(String systemPropertyValue, String propertiesFileValue, String environmentVariableValue) {
        String value = null;
        if (isNotBlank(systemPropertyValue)) {
            value = systemPropertyValue;
        } else if (isNotBlank(propertiesFileValue)) {
            value = propertiesFileValue;
        } else if (isNotBlank(environmentVariableValue)) {
            value = environmentVariableValue;
        }
        if (value == null) {
            return null;
        }
        if (value.startsWith("\"") && value.endsWith("\"")) {
            value = value.replaceAll("^\"|\"$", "");
        }
        return value;
    }

    /**
     * The effective configuration: one {@link ResolvedProperty} per recognised
     * {@code mockserver.*} property, sorted by name, with sensitive values redacted.
     * Properties at their built-in default report a {@code (default)} value and the
     * {@link #SOURCE_DEFAULT} source.
     *
     * <p>Cache-first, exactly like {@link #readPropertyHierarchically}: if the in-memory property
     * cache holds a value for a key (populated by a programmatic setter or by a first read), that
     * is the value MockServer actually uses and is what is reported. When that cached value also
     * resolves from one of the static tiers, the originating tier is reported; otherwise it is a
     * runtime override ({@link #SOURCE_RUNTIME_SET}). Purely observational — reads the cache and
     * the tiers but never mutates them and never changes how any value resolves.</p>
     */
    public static List<ResolvedProperty> effectiveConfiguration() {
        Map<String, String> cache = getPropertyCache();
        // Build (systemPropertyKey -> environmentVariableKey) pairs from the MOCKSERVER_*
        // constants: the constant VALUE is the mockserver.* key and the constant NAME is
        // the literal MOCKSERVER_* environment-variable key (see enumerateRecognisedKeys()).
        Map<String, String> systemPropertyKeyToEnvKey = new TreeMap<>();
        for (Field field : ConfigurationProperties.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())
                && Modifier.isFinal(field.getModifiers())
                && field.getType() == String.class
                && field.getName().startsWith("MOCKSERVER_")) {
                try {
                    field.setAccessible(true);
                    Object value = field.get(null);
                    if (value instanceof String && ((String) value).startsWith("mockserver.")) {
                        systemPropertyKeyToEnvKey.put((String) value, field.getName());
                    }
                } catch (Throwable throwable) {
                    // Skip a single inaccessible constant rather than failing the whole report.
                }
            }
        }

        Set<String> programmaticKeys = getProgrammaticallySetKeys();
        List<ResolvedProperty> resolved = new ArrayList<>();
        for (Map.Entry<String, String> entry : systemPropertyKeyToEnvKey.entrySet()) {
            String systemPropertyKey = entry.getKey();
            String environmentVariableKey = entry.getValue();

            // The cache is what readPropertyHierarchically returns first, so it is authoritative.
            String cachedValue = cache.get(systemPropertyKey);
            String tierSource = resolveEffectiveSource(systemPropertyKey, environmentVariableKey);
            String tierValue = resolveExplicitValue(systemPropertyKey, environmentVariableKey);

            String source;
            String resolvedValue;
            if (cachedValue != null && programmaticKeys.contains(systemPropertyKey) && !cachedValue.equals(tierValue)) {
                // A programmatic setter changed the value to something none of the static tiers
                // supply: this is a genuine runtime override. (When the cached value DOES equal the
                // tier value, the setter agreed with the tier — fall through and attribute it to the
                // tier so e.g. a setter that also writes the system property reports system-property.)
                resolvedValue = cachedValue;
                source = SOURCE_RUNTIME_SET;
            } else if (!SOURCE_DEFAULT.equals(tierSource)) {
                // A static tier (system property > properties file > environment variable) supplies a
                // value. readPropertyHierarchically caches that same value on first read, so whether or
                // not it is cached the reported value and source are the tier's.
                resolvedValue = tierValue;
                source = tierSource;
            } else {
                // No tier supplies a value. A cached value here is the built-in default that
                // readPropertyHierarchically memoised on first read (it caches every value it resolves,
                // including defaults) — NOT a runtime override. Report it as the default.
                resolvedValue = null;
                source = SOURCE_DEFAULT;
            }

            String value;
            if (resolvedValue == null) {
                value = "(default)";
            } else {
                // one rule for every disclosing surface: whole-value credentials masked whole, and
                // credentials embedded in a structured value redacted per header / per JSON field
                value = redactSensitiveValue(systemPropertyKey, resolvedValue);
            }
            resolved.add(new ResolvedProperty(systemPropertyKey, value, source));
        }
        return resolved;
    }

    /**
     * Renders the effective configuration as plain text — one
     * {@code name = value   [source]} line per property — suitable for the
     * {@code --print-config} CLI flag.
     */
    public static String effectiveConfigurationAsText() {
        StringBuilder builder = new StringBuilder();
        builder.append("MockServer effective configuration (value and the source that supplied it):").append(NEW_LINE);
        for (ResolvedProperty property : effectiveConfiguration()) {
            builder.append("  ")
                .append(property.getName())
                .append(" = ")
                .append(property.getValue())
                .append("   [")
                .append(property.getSource())
                .append("]")
                .append(NEW_LINE);
        }
        return builder.toString();
    }

    /**
     * Renders the effective configuration as a JSON array of
     * {@code {"name":..,"value":..,"source":..}} objects, suitable for the
     * {@code GET /mockserver/config} control-plane endpoint. The envelope is hand-built rather
     * than produced by the MockServer {@code ObjectMapperFactory}, so this diagnostic does not
     * depend on the MockServer serializer CONFIGURATION (its modules, DTO bindings and inclusion
     * rules) and stays usable from the lowest module layers. Redacting an individual value may
     * still parse it with a plain, unconfigured {@link com.fasterxml.jackson.databind.ObjectMapper}
     * — see {@link EmbeddedCredentialRedaction} — which carries none of that coupling.
     */
    public static String effectiveConfigurationAsJson() {
        StringBuilder builder = new StringBuilder();
        builder.append("[");
        List<ResolvedProperty> properties = effectiveConfiguration();
        for (int i = 0; i < properties.size(); i++) {
            ResolvedProperty property = properties.get(i);
            if (i > 0) {
                builder.append(",");
            }
            builder.append("{\"name\":\"").append(jsonEscape(property.getName()))
                .append("\",\"value\":\"").append(jsonEscape(property.getValue()))
                .append("\",\"source\":\"").append(jsonEscape(property.getSource()))
                .append("\"}");
        }
        builder.append("]");
        return builder.toString();
    }

    private static String jsonEscape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder escaped = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char character = value.charAt(i);
            switch (character) {
                case '"':
                    escaped.append("\\\"");
                    break;
                case '\\':
                    escaped.append("\\\\");
                    break;
                case '\n':
                    escaped.append("\\n");
                    break;
                case '\r':
                    escaped.append("\\r");
                    break;
                case '\t':
                    escaped.append("\\t");
                    break;
                default:
                    if (character < 0x20) {
                        escaped.append(String.format("\\u%04x", (int) character));
                    } else {
                        escaped.append(character);
                    }
            }
        }
        return escaped.toString();
    }
}

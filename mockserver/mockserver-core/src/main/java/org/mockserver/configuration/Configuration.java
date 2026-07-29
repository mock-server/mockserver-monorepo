package org.mockserver.configuration;

import com.google.common.collect.Sets;
import com.google.common.net.InetAddresses;
import org.mockserver.log.model.LogEntry;
import org.mockserver.model.BinaryProxyListener;
import org.mockserver.model.Delay;
import org.mockserver.model.Header;
import org.mockserver.model.ProxyPassMapping;
import org.mockserver.responseheaders.DefaultResponseHeaders;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import static org.apache.commons.lang3.StringUtils.isNotBlank;
import static org.apache.commons.lang3.StringUtils.substringBefore;
import static org.mockserver.configuration.ConfigurationProperties.fileExists;

/**
 * @author jamesdbloom
 */
@SuppressWarnings({"UnusedReturnValue", "unused"})
public class Configuration {

    public static Configuration configuration() {
        return new Configuration();
    }

    // logging
    private Level logLevel;
    private Consumer<LogEntry> logEventListener;
    private Boolean disableSystemOut;
    private Boolean disableLogging;
    private Boolean detailedMatchFailures;
    private Boolean launchUIForLogLevelDebug;
    private Boolean metricsEnabled;
    private Boolean dashboardAnalyticsEnabled;
    private String dashboardAnalyticsEndpoint;
    private String dashboardAnalyticsKey;
    private String dashboardAnalyticsDistribution;
    private Long slowRequestThresholdMillis;
    private Boolean metricsRequestDurationRouteLabels;
    private Boolean chaosAutoHaltEnabled;
    private Long chaosAutoHaltErrorThreshold;
    private Long chaosAutoHaltWindowMillis;
    private Integer rateLimitMaxNamedQuotas;
    private Boolean connectionLifecycleChaosEnabled;
    private Long preemptionSimulationMaxDrainMillis;
    private Long stopDrainMillis;
    private Boolean connectionLifecycleAutoHaltCountsRst;
    private Boolean sloTrackingEnabled;
    private Long sloWindowRetentionMillis;
    private Integer sloWindowMaxSamples;
    private Boolean loadGenerationEnabled;
    private Boolean loadGenerationSuppressEventLog;
    private Integer loadGenerationMaxVirtualUsers;
    private Integer loadGenerationMaxInFlightRequests;
    private Integer loadGenerationMaxRequestsPerSecond;
    private Long loadGenerationMaxDurationMillis;
    private Integer loadGenerationMaxSteps;
    private Double loadGenerationMaxRate;
    private Integer loadGenerationMaxStages;
    private Integer loadGenerationMaxConcurrentScenarios;
    private java.util.List<String> loadGenerationMetricLabels;
    private String loadScenarioInitializationJsonPath;
    private Boolean llmMetricsEnabled;
    private Boolean perExpectationMetricsEnabled;
    private Boolean deduplicateRecordedExpectations;
    private Boolean templatizeRecordedValues;
    private Boolean redactSecretsInRecordedExpectations;
    private Boolean redactSecretsInLog;
    private Double llmCostBudgetUsd;
    private Boolean otelPropagateTraceContext;
    private Boolean otelGenerateTraceId;
    private Boolean mcpEnabled;
    private Long breakpointTimeoutMillis;
    private Integer breakpointMaxHeld;
    private Boolean wasmEnabled;
    private Integer wasmMaxMemoryPages;
    private Long wasmExecutionTimeoutMillis;
    private String grpcDescriptorDirectory;
    private String grpcProtoDirectory;
    private Boolean grpcEnabled;
    private String grpcProtocPath;
    private Boolean grpcBidiStreamingEnabled;
    private Boolean dnsEnabled;
    private Integer dnsPort;
    private Integer http3Port;
    private Long http3MaxIdleTimeout;
    private Long http3InitialMaxData;
    private Long http3InitialMaxStreamDataBidirectional;
    private Long http3InitialMaxStreamsBidirectional;
    private Long http3QpackMaxTableCapacity;
    private Boolean http3ConnectUdpEnabled;
    private String http3ConnectUdpAllowedTargets;
    private Long http3AltSvcMaxAge;
    private Boolean http3AdvertiseAltSvc;
    private Map<String, String> logLevelOverrides;
    private Boolean compactLogFormat;

    // dev mode
    private Boolean devMode;

    // memory usage
    private Integer maxExpectations;
    private Integer maxLogEntries;
    private Long maxEventLogSizeInBytes;
    private Integer maxLoggedBodyBytes;
    private Integer ringBufferSize;
    private Integer maxWebSocketExpectations;
    private Integer webSocketProxyMaxRecordedFrames;
    private Integer webSocketProxyIdleTimeoutSeconds;
    private Boolean outputMemoryUsageCsv;
    private String memoryUsageCsvDirectory;

    // scalability
    private Boolean useNativeTransport;
    private Integer nioEventLoopThreadCount;
    private Integer actionHandlerThreadCount;
    private Integer clientNioEventLoopThreadCount;
    private Integer webSocketClientEventLoopThreadCount;
    private Long maxFutureTimeoutInMillis;
    private Boolean matchersFailFast;
    private Boolean matchExactCase;
    private Boolean forwardConnectionPoolEnabled;
    private Integer forwardConnectionPoolMaxIdlePerKey;
    private Long forwardConnectionPoolIdleTimeoutMillis;
    private Boolean forwardConnectionPoolKeepAlive;
    private Integer forwardConnectionPoolMaxTotalPerKey;
    private Boolean forwardSocketKeepAlive;
    private Integer forwardSocketKeepAliveIdleSeconds;
    private Integer forwardSocketKeepAliveIntervalSeconds;
    private Integer forwardSocketKeepAliveCount;
    private Integer forwardProxyRetryCount;
    private Long forwardProxyRetryBackoffMillis;
    private Boolean forwardProxyHttp2Enabled;
    private Boolean forwardProxyHttp2Upgrade;
    private Boolean forwardProxyCircuitBreakerEnabled;
    private Integer forwardProxyCircuitBreakerFailureThreshold;
    private Long forwardProxyCircuitBreakerWindowMillis;
    private Boolean enforceResponseValidationForMocks;

    // socket
    private Long maxSocketTimeoutInMillis;
    private Long socketConnectionTimeoutInMillis;
    private Delay connectionDelay;
    private Boolean alwaysCloseSocketConnections;
    private String localBoundIP;

    // http request parsing
    private Integer maxInitialLineLength;
    private Integer maxHeaderSize;
    private Integer maxChunkSize;
    private Integer maxRequestBodySize;
    private Integer maxGrpcMessageSize;
    private Integer maxResponseBodySize;
    private Integer maxLlmConversationBodySize;
    private Boolean driftDetectionEnabled;
    private Double driftSampleRate;
    private Boolean driftSemanticAnalysisEnabled;
    private Long driftResponseTimeThresholdMs;
    private Boolean driftAlertWebhookEnabled;
    private String driftAlertWebhookUrl;
    private String driftAlertSeverityThreshold;
    private Long driftAlertCooldownMillis;
    private Boolean controlPlaneAuditEnabled;
    private Integer controlPlaneAuditMaxEntries;
    private Boolean controlPlaneAuditReads;
    private String auditLogFile;
    private Boolean useSemicolonAsQueryParameterSeparator;
    private Boolean startupWarmup;
    private Boolean assumeAllRequestsAreHttp;
    private Boolean http2Enabled;

    // matcher safety — global only (ConfigurationProperties), no per-instance override:
    // RegexStringMatcher and XPathEvaluator are constructed without a Configuration handle
    // and read directly from ConfigurationProperties, so per-instance setters would be dead API.

    // streaming proxy
    private Boolean streamingResponsesEnabled;
    private Integer maxStreamingCaptureBytes;
    private Integer streamIdleTimeoutSeconds;

    // non http proxying
    private Boolean forwardBinaryRequestsWithoutWaitingForResponse;
    private BinaryProxyListener binaryProxyListener;

    // CORS
    private Boolean enableCORSForAPI;
    private Boolean enableCORSForAllResponses;
    private String corsAllowOrigin;
    private String corsAllowMethods;
    private String corsAllowHeaders;
    private Boolean corsAllowCredentials;
    private Integer corsMaxAgeInSeconds;

    // default response headers
    private String defaultResponseHeaders;
    // memoised parse of defaultResponseHeaders() so the pipe-split parse runs once per distinct
    // resolved value rather than per HTTP request (DefaultResponseHeaders is constructed per request).
    // source and parsed result are held in a single volatile holder so they are always read/written
    // atomically together (no torn read of a result against a mismatched source).
    private volatile java.util.Map.Entry<String, List<Header>> parsedDefaultResponseHeaders;

    // template restrictions
    private String javascriptDisallowedClasses;
    private String javascriptAllowedClasses;
    private String javascriptDisallowedText;
    private Long javascriptTemplateExecutionTimeout;
    private Boolean velocityDisallowClassLoading;
    private String velocityDisallowedText;
    private String mustacheDisallowedText;
    private Long templateFakerSeed;

    // mock initialization
    private String initializationClass;
    private String initializationJsonPath;
    private String initializationOpenAPIPath;
    private String openAPIContextPathPrefix;
    private Boolean openAPIResponseValidation;
    private Boolean validateRequestsAgainstOpenApiSpec;
    private String validateProxyOpenAPISpec;
    private Boolean validateProxyEnforce;
    private Boolean generateRealisticExampleValues;
    private Boolean watchInitializationJson;
    private Boolean failOnInitializationError;

    // mock persistence
    private Boolean persistExpectations;
    private String persistedExpectationsPath;

    // recorded expectation persistence
    private Boolean persistRecordedExpectations;
    private String persistedRecordedExpectationsPath;

    private Boolean persistRecordedRequestsToDisk;
    private String persistedRecordedRequestsPath;

    // state backend (G10 phase 2a)
    private String stateBackend;
    private String blobStoreType;

    // cloud blob store configuration
    private String blobStoreBucket;
    private String blobStoreRegion;
    private String blobStoreEndpoint;
    private String blobStoreKeyPrefix;
    private String blobStoreAccessKeyId;
    private String blobStoreSecretAccessKey;
    private String blobStoreContainer;
    private String blobStoreConnectionString;
    private String blobStoreProjectId;
    private Integer blobStoreRestoreTimeoutSeconds;

    // clustering (G10 phase 2c) — opt-in, default OFF
    private Boolean clusterEnabled;
    private String clusterName;
    private String clusterTransportConfig;
    private Boolean clusterSharedTimesEnabled;
    private Boolean clusterVerifyFanIn;
    private String clusterVerifyFanInPeers;
    private String clusterFanInPeerAuthToken;

    // verification
    private Integer maximumNumberOfRequestToReturnInVerificationFailure;
    private Boolean detailedVerificationFailures;
    private Boolean failVerificationOnEvictedLog;
    private Boolean attachMismatchDiagnosticToResponse;
    private Boolean closestMatchHintEnabled;

    // proxy
    // volatile: mutated at runtime via PUT /mockserver/mode (control-plane thread) and read on the
    // Netty request path (HttpActionHandler), so the write must be visible across I/O threads
    private volatile Boolean attemptToProxyIfNoMatchingExpectation;
    private InetSocketAddress forwardHttpProxy;
    private InetSocketAddress forwardHttpsProxy;
    private InetSocketAddress forwardSocksProxy;
    private String forwardProxyAuthenticationUsername;
    private String forwardProxyAuthenticationPassword;
    private String proxyAuthenticationRealm;
    private String proxyAuthenticationUsername;
    private String proxyAuthenticationPassword;

    // data plane (mocked endpoint) authentication — opt-in, default off
    private Boolean dataPlaneAuthenticationRequired;
    private String dataPlaneBasicAuthenticationUsername;
    private String dataPlaneBasicAuthenticationPassword;
    private String dataPlaneBasicAuthenticationRealm;
    private String dataPlaneBearerAuthenticationToken;
    private String dataPlaneApiKeyAuthenticationHeader;
    private String dataPlaneApiKeyAuthenticationValue;

    private String noProxyHosts;
    // volatile: proxyRemoteHost/proxyRemotePort can be set at runtime via the
    // retrieve ?forwardUnmatchedTo= record-and-forward convenience (control-plane
    // thread) and are read on the Netty request path (HttpActionHandler), so the
    // write must be visible across I/O threads.
    private volatile String proxyRemoteHost;
    private volatile Integer proxyRemotePort;
    private Boolean forwardAdjustHostHeader;
    private String forwardDefaultHostHeader;
    private List<ProxyPassMapping> proxyPassMappings;

    // global response delay
    private Long globalResponseDelayMillis;

    // liveness
    private String livenessHttpGetPath;

    // expectation namespacing / multi-tenancy
    private String matchNamespaceHeader;

    // control plane authentication
    private Boolean controlPlaneTLSMutualAuthenticationRequired;
    private String controlPlaneTLSMutualAuthenticationCAChain;
    private String controlPlanePrivateKeyPath;
    private String controlPlaneX509CertificatePath;
    private Boolean controlPlaneJWTAuthenticationRequired;
    private String controlPlaneJWTAuthenticationJWKSource;
    private String controlPlaneJWTAuthenticationExpectedAudience;
    private Map<String, String> controlPlaneJWTAuthenticationMatchingClaims;
    private Set<String> controlPlaneJWTAuthenticationRequiredClaims;
    private Boolean controlPlaneOidcAuthenticationRequired;
    private String controlPlaneOidcIssuer;
    private String controlPlaneOidcJwksUri;
    private String controlPlaneOidcAudience;
    private Set<String> controlPlaneOidcRequiredScopes;
    private String controlPlaneOidcScopeClaim;
    private Boolean controlPlaneAuthorizationEnabled;
    private Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> controlPlaneScopeMapping;

    // TLS
    private Boolean proactivelyInitialiseTLS;
    private volatile boolean rebuildTLSContext;
    private volatile boolean rebuildServerTLSContext;
    private String tlsProtocols;
    private Boolean tlsAllowInsecureProtocols;

    // inbound - dynamic CA
    private Boolean dynamicallyCreateCertificateAuthorityCertificate;
    private String directoryToSaveDynamicSSLCertificate;

    // proxy setup convenience
    private Boolean proxySetup;
    private Boolean proxySetupLogging;

    // inbound - dynamic private key & x509
    private Boolean preventCertificateDynamicUpdate;
    private String sslCertificateDomainName;
    private Set<String> sslSubjectAlternativeNameDomains;
    private Set<String> sslSubjectAlternativeNameIps;

    // inbound - fixed CA
    private String certificateAuthorityPrivateKey;
    private String certificateAuthorityCertificate;

    // inbound - fixed private key & x509
    private String privateKeyPath;
    private String x509CertificatePath;

    // inbound - mTLS
    private Boolean tlsMutualAuthenticationRequired;
    private String tlsMutualAuthenticationCertificateChain;

    // outbound - CA
    private ForwardProxyTLSX509CertificatesTrustManager forwardProxyTLSX509CertificatesTrustManagerType;

    // outbound - SSRF protection
    private Boolean forwardProxyBlockPrivateNetworks;

    // outbound - fixed CA
    private String forwardProxyTLSCustomTrustX509Certificates;

    // outbound - fixed private key & x509
    private String forwardProxyPrivateKey;
    private String forwardProxyCertificateChain;
    private String forwardProxyClientCertificatesByHost;

    // service mesh / sidecar
    private Boolean transparentProxyEnabled;
    private Boolean transparentProxyTproxy;
    private Boolean transparentProxyEbpf;
    private String transparentProxyEbpfMapPath;

    // async messaging defaults
    private String asyncKafkaBootstrapServers;
    private String asyncMqttBrokerUrl;
    private String asyncAmqpUri;
    private Integer asyncRecordedMessageMaxEntries;

    // runtime LLM backend
    private String llmProvider;
    private String llmApiKey;
    private String llmModel;
    private String llmBaseUrl;
    private String llmBackendsConfig;
    private Long llmRequestTimeoutMillis;
    private Boolean llmSemanticMatchingEnabled;
    private Boolean llmInferUsageEnabled;
    private Boolean llmVcrStrict;
    private Integer llmOptimisationMaxCalls;
    private String fixtureBodyRedactFields;

    // OpenTelemetry
    private String otelEndpoint;
    private Boolean otelMetricsEnabled;
    private Boolean otelTracesEnabled;
    private Long otelMetricsExportIntervalSeconds;
    private String otelMetricsTemporality;

    // Prometheus remote write
    private Boolean prometheusRemoteWriteEnabled;
    private String prometheusRemoteWriteUrl;
    private Long prometheusRemoteWriteIntervalSeconds;
    private String prometheusRemoteWriteBearerToken;
    private String prometheusRemoteWriteBasicAuthUsername;
    private String prometheusRemoteWriteBasicAuthPassword;
    private String prometheusRemoteWriteHeaders;
    private String prometheusRemoteWriteProtocolVersion;

    // matching safety limits
    private Long regexMatchingTimeoutMillis;
    private Long xpathMatchingTimeoutMillis;
    private String customJsonUnitMatchersClass;


    public Level logLevel() {
        if (logLevel == null) {
            return ConfigurationProperties.logLevel();
        }
        return logLevel;
    }

    /**
     * Override the default logging level of INFO
     *
     * @param level the log level, which can be TRACE, DEBUG, INFO, WARN, ERROR, OFF, FINEST, FINE, INFO, WARNING, SEVERE
     */
    public Configuration logLevel(Level level) {
        this.logLevel = level;
        return this;
    }

    /**
     * Override the default logging level of INFO
     *
     * @param level the log level, which can be TRACE, DEBUG, INFO, WARN, ERROR, OFF, FINEST, FINE, INFO, WARNING, SEVERE
     */
    public Configuration logLevel(String level) {
        this.logLevel = Level.valueOf(level);
        return this;
    }

    public Consumer<LogEntry> logEventListener() {
        return logEventListener;
    }

    public Configuration logEventListener(Consumer<LogEntry> logEventListener) {
        this.logEventListener = logEventListener;
        return this;
    }

    public Boolean disableSystemOut() {
        if (disableSystemOut == null) {
            return ConfigurationProperties.disableSystemOut();
        }
        return disableSystemOut;
    }

    /**
     * Disable printing log to system out for JVM, default is enabled
     *
     * @param disableSystemOut printing log to system out for JVM
     */
    public Configuration disableSystemOut(Boolean disableSystemOut) {
        this.disableSystemOut = disableSystemOut;
        return this;
    }

    public Boolean disableLogging() {
        if (disableLogging == null) {
            return ConfigurationProperties.disableLogging();
        }
        return disableLogging;
    }

    /**
     * Disable all logging and processing of log events
     * <p>
     * The default is false
     *
     * @param disableLogging disable all logging
     */
    public Configuration disableLogging(Boolean disableLogging) {
        this.disableLogging = disableLogging;
        return this;
    }

    public Boolean detailedMatchFailures() {
        if (detailedMatchFailures == null) {
            return ConfigurationProperties.detailedMatchFailures();
        }
        return detailedMatchFailures;
    }

    /**
     * If true (the default) the log event recording that a request matcher did not match will include a detailed reason why each non-matching field did not match.
     *
     * @param detailedMatchFailures enabled detailed match failure log events
     */
    public Configuration detailedMatchFailures(Boolean detailedMatchFailures) {
        this.detailedMatchFailures = detailedMatchFailures;
        return this;
    }

    public Boolean launchUIForLogLevelDebug() {
        if (launchUIForLogLevelDebug == null) {
            return ConfigurationProperties.launchUIForLogLevelDebug();
        }
        return launchUIForLogLevelDebug;
    }

    /**
     * If true the ClientAndServer constructor will open the UI in the default browser when the log level is set to DEBUG. Default is false.
     *
     * @param launchUIForLogLevelDebug enabled ClientAndServer constructor launching UI when log level is DEBUG
     */
    public Configuration launchUIForLogLevelDebug(Boolean launchUIForLogLevelDebug) {
        this.launchUIForLogLevelDebug = launchUIForLogLevelDebug;
        return this;
    }

    public Boolean metricsEnabled() {
        if (metricsEnabled == null) {
            return ConfigurationProperties.metricsEnabled();
        }
        return metricsEnabled;
    }

    /**
     * Enable gathering of metrics, default is false
     *
     * @param metricsEnabled enable metrics
     */
    public Configuration metricsEnabled(Boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
        return this;
    }

    public Boolean dashboardAnalyticsEnabled() {
        if (dashboardAnalyticsEnabled == null) {
            return ConfigurationProperties.dashboardAnalyticsEnabled();
        }
        return dashboardAnalyticsEnabled;
    }

    /**
     * Master kill switch for browser dashboard usage analytics, default is true.
     * Analytics remains inert until an endpoint and key are also supplied.
     *
     * @param dashboardAnalyticsEnabled enable dashboard analytics
     */
    public Configuration dashboardAnalyticsEnabled(Boolean dashboardAnalyticsEnabled) {
        this.dashboardAnalyticsEnabled = dashboardAnalyticsEnabled;
        return this;
    }

    public String dashboardAnalyticsEndpoint() {
        if (dashboardAnalyticsEndpoint == null) {
            return ConfigurationProperties.dashboardAnalyticsEndpoint();
        }
        return dashboardAnalyticsEndpoint;
    }

    /**
     * Analytics endpoint (PostHog api_host) the browser dashboard sends usage analytics to, default is empty.
     *
     * @param dashboardAnalyticsEndpoint analytics endpoint host
     */
    public Configuration dashboardAnalyticsEndpoint(String dashboardAnalyticsEndpoint) {
        this.dashboardAnalyticsEndpoint = dashboardAnalyticsEndpoint;
        return this;
    }

    public String dashboardAnalyticsKey() {
        if (dashboardAnalyticsKey == null) {
            return ConfigurationProperties.dashboardAnalyticsKey();
        }
        return dashboardAnalyticsKey;
    }

    /**
     * Write-only analytics project key the browser dashboard uses when sending usage analytics, default is empty.
     *
     * @param dashboardAnalyticsKey analytics project key
     */
    public Configuration dashboardAnalyticsKey(String dashboardAnalyticsKey) {
        this.dashboardAnalyticsKey = dashboardAnalyticsKey;
        return this;
    }

    public String dashboardAnalyticsDistribution() {
        if (dashboardAnalyticsDistribution == null) {
            return ConfigurationProperties.dashboardAnalyticsDistribution();
        }
        return dashboardAnalyticsDistribution;
    }

    /**
     * Label identifying which MockServer distribution/artefact a dashboard analytics event came from, default is empty.
     *
     * @param dashboardAnalyticsDistribution analytics distribution label
     */
    public Configuration dashboardAnalyticsDistribution(String dashboardAnalyticsDistribution) {
        this.dashboardAnalyticsDistribution = dashboardAnalyticsDistribution;
        return this;
    }

    public Boolean llmMetricsEnabled() {
        if (llmMetricsEnabled == null) {
            return ConfigurationProperties.llmMetricsEnabled();
        }
        return llmMetricsEnabled;
    }

    /**
     * Enable LLM token and cost metrics.
     *
     * @param llmMetricsEnabled enable LLM metrics
     */
    public Configuration llmMetricsEnabled(Boolean llmMetricsEnabled) {
        this.llmMetricsEnabled = llmMetricsEnabled;
        return this;
    }

    public Boolean perExpectationMetricsEnabled() {
        if (perExpectationMetricsEnabled == null) {
            return ConfigurationProperties.perExpectationMetricsEnabled();
        }
        return perExpectationMetricsEnabled;
    }

    /**
     * Enable the opt-in per-expectation Prometheus match counter.
     *
     * @param perExpectationMetricsEnabled enable per-expectation metrics
     */
    public Configuration perExpectationMetricsEnabled(Boolean perExpectationMetricsEnabled) {
        this.perExpectationMetricsEnabled = perExpectationMetricsEnabled;
        return this;
    }

    public Boolean deduplicateRecordedExpectations() {
        if (deduplicateRecordedExpectations == null) {
            return ConfigurationProperties.deduplicateRecordedExpectations();
        }
        return deduplicateRecordedExpectations;
    }

    /**
     * Enable opt-in deduplication and templatization of retrieved recorded expectations.
     *
     * @param deduplicateRecordedExpectations enable deduplication of recorded expectations
     */
    public Configuration deduplicateRecordedExpectations(Boolean deduplicateRecordedExpectations) {
        this.deduplicateRecordedExpectations = deduplicateRecordedExpectations;
        return this;
    }

    public Boolean templatizeRecordedValues() {
        if (templatizeRecordedValues == null) {
            return ConfigurationProperties.templatizeRecordedValues();
        }
        return templatizeRecordedValues;
    }

    /**
     * Enable opt-in generalization of volatile-looking query parameter, header and JSON
     * body leaf values (ids, UUIDs, timestamps, tokens) into regex matchers when
     * retrieved recorded expectations are post-processed. Only takes effect when
     * {@link #deduplicateRecordedExpectations()} is also enabled (the post-processor only
     * runs then). Off by default so recorded output is unchanged unless explicitly enabled.
     *
     * @param templatizeRecordedValues enable value templatization of recorded expectations
     */
    public Configuration templatizeRecordedValues(Boolean templatizeRecordedValues) {
        this.templatizeRecordedValues = templatizeRecordedValues;
        return this;
    }

    public Boolean redactSecretsInRecordedExpectations() {
        if (redactSecretsInRecordedExpectations == null) {
            return ConfigurationProperties.redactSecretsInRecordedExpectations();
        }
        return redactSecretsInRecordedExpectations;
    }

    /**
     * Enable opt-in redaction of secrets in retrieved recorded expectations. When enabled,
     * sensitive header values (such as {@code Authorization}, {@code Cookie}, {@code x-api-key}
     * and bearer/token credentials) are masked before recorded expectations are returned,
     * generated as client code, or persisted to JSON.
     * <p>
     * Trade-off: a redacted recorded expectation can no longer replay against an upstream that
     * requires that credential, so this is off by default.
     *
     * @param redactSecretsInRecordedExpectations enable redaction of secrets in recorded expectations
     */
    public Configuration redactSecretsInRecordedExpectations(Boolean redactSecretsInRecordedExpectations) {
        this.redactSecretsInRecordedExpectations = redactSecretsInRecordedExpectations;
        return this;
    }

    public Boolean redactSecretsInLog() {
        if (redactSecretsInLog == null) {
            return ConfigurationProperties.redactSecretsInLog();
        }
        return redactSecretsInLog;
    }

    /**
     * Enable opt-in redaction of secrets in the live event log and dashboard. When enabled,
     * sensitive request/response header values (such as {@code Authorization}, {@code Cookie},
     * {@code x-api-key} and bearer/token credentials) are masked in the logged requests returned by
     * {@code retrieveLogMessages}/{@code retrieveRecordedRequests} and in the dashboard event view.
     * Redaction is applied only to the displayed/retrieved copies — matching and verification still
     * see the original values — so off by default.
     *
     * @param redactSecretsInLog enable redaction of secrets in the event log and dashboard
     */
    public Configuration redactSecretsInLog(Boolean redactSecretsInLog) {
        this.redactSecretsInLog = redactSecretsInLog;
        return this;
    }

    public Double llmCostBudgetUsd() {
        if (llmCostBudgetUsd == null) {
            return ConfigurationProperties.llmCostBudgetUsd();
        }
        return llmCostBudgetUsd;
    }

    /**
     * Set cumulative LLM cost budget in USD. Negative or null to disable.
     *
     * @param llmCostBudgetUsd the budget in USD
     */
    public Configuration llmCostBudgetUsd(Double llmCostBudgetUsd) {
        this.llmCostBudgetUsd = llmCostBudgetUsd;
        return this;
    }

    public Long slowRequestThresholdMillis() {
        if (slowRequestThresholdMillis == null) {
            return ConfigurationProperties.slowRequestThresholdMillis();
        }
        return slowRequestThresholdMillis;
    }

    /**
     * Threshold in milliseconds for flagging slow forwarded requests. When a forwarded
     * request's total time exceeds this threshold, a WARN-level log entry is emitted and
     * the {@code mock_server_slow_requests_total} Prometheus counter is incremented.
     * <p>
     * Default is 0 (disabled).
     *
     * @param slowRequestThresholdMillis threshold in milliseconds, 0 to disable
     */
    public Configuration slowRequestThresholdMillis(Long slowRequestThresholdMillis) {
        this.slowRequestThresholdMillis = slowRequestThresholdMillis;
        return this;
    }

    public Boolean metricsRequestDurationRouteLabels() {
        if (metricsRequestDurationRouteLabels == null) {
            return ConfigurationProperties.metricsRequestDurationRouteLabels();
        }
        return metricsRequestDurationRouteLabels;
    }

    /**
     * Enable per-route (HTTP method) labels on the request duration histogram.
     *
     * @param metricsRequestDurationRouteLabels enable method labels
     */
    public Configuration metricsRequestDurationRouteLabels(Boolean metricsRequestDurationRouteLabels) {
        this.metricsRequestDurationRouteLabels = metricsRequestDurationRouteLabels;
        return this;
    }

    public Boolean chaosAutoHaltEnabled() {
        if (chaosAutoHaltEnabled == null) {
            return ConfigurationProperties.chaosAutoHaltEnabled();
        }
        return chaosAutoHaltEnabled;
    }

    /**
     * Enable the chaos auto-halt circuit-breaker. When enabled, if the number of chaos-injected
     * errors within a sliding window exceeds the configured threshold, all active service-scoped
     * chaos profiles are automatically disabled. Default is false (feature off).
     *
     * @param chaosAutoHaltEnabled enable chaos auto-halt
     */
    public Configuration chaosAutoHaltEnabled(Boolean chaosAutoHaltEnabled) {
        this.chaosAutoHaltEnabled = chaosAutoHaltEnabled;
        return this;
    }

    public Long chaosAutoHaltErrorThreshold() {
        if (chaosAutoHaltErrorThreshold == null) {
            return ConfigurationProperties.chaosAutoHaltErrorThreshold();
        }
        return chaosAutoHaltErrorThreshold;
    }

    /**
     * The number of chaos-injected errors within the sliding window that triggers an
     * automatic halt of all active service-scoped chaos profiles. Default is 50.
     *
     * @param chaosAutoHaltErrorThreshold error count threshold
     */
    public Configuration chaosAutoHaltErrorThreshold(Long chaosAutoHaltErrorThreshold) {
        this.chaosAutoHaltErrorThreshold = chaosAutoHaltErrorThreshold;
        return this;
    }

    public Long chaosAutoHaltWindowMillis() {
        if (chaosAutoHaltWindowMillis == null) {
            return ConfigurationProperties.chaosAutoHaltWindowMillis();
        }
        return chaosAutoHaltWindowMillis;
    }

    public Integer rateLimitMaxNamedQuotas() {
        if (rateLimitMaxNamedQuotas == null) {
            return ConfigurationProperties.rateLimitMaxNamedQuotas();
        }
        return rateLimitMaxNamedQuotas;
    }

    /**
     * The maximum number of distinct named rate-limit counters held in the in-process
     * rate-limit registry. Once this cap is reached a request for a new counter key
     * fails open (is allowed). Default is 10000.
     *
     * @param rateLimitMaxNamedQuotas maximum number of distinct named rate-limit counters
     */
    public Configuration rateLimitMaxNamedQuotas(Integer rateLimitMaxNamedQuotas) {
        this.rateLimitMaxNamedQuotas = rateLimitMaxNamedQuotas;
        return this;
    }

    /**
     * The sliding window duration in milliseconds over which chaos-injected errors are
     * counted for the auto-halt circuit-breaker. Default is 60000 (60 seconds).
     *
     * @param chaosAutoHaltWindowMillis window duration in milliseconds
     */
    public Configuration chaosAutoHaltWindowMillis(Long chaosAutoHaltWindowMillis) {
        this.chaosAutoHaltWindowMillis = chaosAutoHaltWindowMillis;
        return this;
    }

    public Boolean connectionLifecycleChaosEnabled() {
        if (connectionLifecycleChaosEnabled == null) {
            return ConfigurationProperties.connectionLifecycleChaosEnabled();
        }
        return connectionLifecycleChaosEnabled;
    }

    /**
     * Master switch for connection-lifecycle / graceful-shutdown fault injection (mid-response RST,
     * host-scoped slow close, HTTP/2 GOAWAY, and the preemption/SIGTERM simulator). Default true.
     * The response-path lookups are gated on the active registration count, so when no
     * connection-lifecycle faults and no preemption are configured the feature adds nothing to the
     * hot path even when enabled — set this to false only to hard-disable the feature.
     *
     * @param connectionLifecycleChaosEnabled enable connection-lifecycle chaos
     */
    public Configuration connectionLifecycleChaosEnabled(Boolean connectionLifecycleChaosEnabled) {
        this.connectionLifecycleChaosEnabled = connectionLifecycleChaosEnabled;
        return this;
    }

    public Long preemptionSimulationMaxDrainMillis() {
        if (preemptionSimulationMaxDrainMillis == null) {
            return ConfigurationProperties.preemptionSimulationMaxDrainMillis();
        }
        return preemptionSimulationMaxDrainMillis;
    }

    /**
     * Hard upper bound (in milliseconds) on a preemption simulation's drain window and TTL. A
     * {@code PUT /mockserver/preemption} request asking for a larger value is clamped to this cap, so
     * a forgotten or runaway simulation cannot cordon the server indefinitely. Default is 86400000
     * (24 hours).
     *
     * @param preemptionSimulationMaxDrainMillis maximum drain/TTL milliseconds
     */
    public Configuration preemptionSimulationMaxDrainMillis(Long preemptionSimulationMaxDrainMillis) {
        this.preemptionSimulationMaxDrainMillis = preemptionSimulationMaxDrainMillis;
        return this;
    }

    public Long stopDrainMillis() {
        if (stopDrainMillis == null) {
            return ConfigurationProperties.stopDrainMillis();
        }
        // mirrors the clamp ConfigurationProperties.stopDrainMillis() applies, so a negative value can
        // never be read back as a negative drain budget regardless of which route set it
        return Math.max(0L, stopDrainMillis);
    }

    /**
     * Maximum time in milliseconds to wait for in-flight requests to complete when the server is
     * stopped (graceful shutdown connection drain). On stop, the server stops accepting new
     * connections and then waits up to this timeout for any requests still being processed to
     * finish before shutting down the event loops. If the timeout elapses a warning is logged with
     * the number of remaining in-flight requests and shutdown proceeds anyway. Default is 15000
     * (15 seconds). Set to 0 to disable draining (stop immediately, the pre-7.2 behaviour).
     *
     * @param stopDrainMillis drain timeout in milliseconds, 0 to disable draining
     */
    public Configuration stopDrainMillis(Long stopDrainMillis) {
        this.stopDrainMillis = stopDrainMillis;
        return this;
    }

    public Boolean connectionLifecycleAutoHaltCountsRst() {
        if (connectionLifecycleAutoHaltCountsRst == null) {
            return ConfigurationProperties.connectionLifecycleAutoHaltCountsRst();
        }
        return connectionLifecycleAutoHaltCountsRst;
    }

    /**
     * When true, a connection-lifecycle RST (the mid-response RST) counts as a destructive "drop"
     * fault for the chaos auto-halt circuit-breaker, so a RST storm trips the breaker and halts
     * chaos. Default true.
     *
     * @param connectionLifecycleAutoHaltCountsRst count lifecycle RSTs toward auto-halt
     */
    public Configuration connectionLifecycleAutoHaltCountsRst(Boolean connectionLifecycleAutoHaltCountsRst) {
        this.connectionLifecycleAutoHaltCountsRst = connectionLifecycleAutoHaltCountsRst;
        return this;
    }

    public Boolean sloTrackingEnabled() {
        if (sloTrackingEnabled == null) {
            return ConfigurationProperties.sloTrackingEnabled();
        }
        return sloTrackingEnabled;
    }

    /**
     * Enable SLO sample tracking. When enabled, MockServer records a windowed
     * sample (latency, error flag, scope, host) for each forwarded upstream
     * round-trip so that {@code PUT /mockserver/verifySLO} can compute resilience
     * verdicts. Off by default — when disabled the forward path records nothing.
     *
     * @param sloTrackingEnabled enable SLO sample tracking
     */
    public Configuration sloTrackingEnabled(Boolean sloTrackingEnabled) {
        this.sloTrackingEnabled = sloTrackingEnabled;
        return this;
    }

    public Long sloWindowRetentionMillis() {
        if (sloWindowRetentionMillis == null) {
            return ConfigurationProperties.sloWindowRetentionMillis();
        }
        return sloWindowRetentionMillis;
    }

    /**
     * The maximum age in milliseconds of SLO samples retained for verdict
     * evaluation. Samples older than this (relative to the newest sample) are
     * evicted. Default is 600000 (10 minutes).
     *
     * @param sloWindowRetentionMillis sample retention window in milliseconds
     */
    public Configuration sloWindowRetentionMillis(Long sloWindowRetentionMillis) {
        this.sloWindowRetentionMillis = sloWindowRetentionMillis;
        return this;
    }

    public Integer sloWindowMaxSamples() {
        if (sloWindowMaxSamples == null) {
            return ConfigurationProperties.sloWindowMaxSamples();
        }
        return sloWindowMaxSamples;
    }

    /**
     * The maximum number of SLO samples retained for verdict evaluation. When the
     * store is full the oldest sample is evicted. Default is 50000.
     *
     * @param sloWindowMaxSamples maximum number of retained samples
     */
    public Configuration sloWindowMaxSamples(Integer sloWindowMaxSamples) {
        this.sloWindowMaxSamples = sloWindowMaxSamples;
        return this;
    }

    public Boolean loadGenerationEnabled() {
        if (loadGenerationEnabled == null) {
            return ConfigurationProperties.loadGenerationEnabled();
        }
        return loadGenerationEnabled;
    }

    /**
     * Enable API-driven load generation. When enabled, {@code PUT /mockserver/loadScenario}
     * starts an in-process load scenario that drives templated request steps at a target
     * concurrency, producing latency/error samples for the SLO verdict feature. Off by
     * default — when disabled the endpoint returns 403 so MockServer never self-loads
     * unless explicitly opted in.
     *
     * @param loadGenerationEnabled enable load generation
     */
    public Configuration loadGenerationEnabled(Boolean loadGenerationEnabled) {
        this.loadGenerationEnabled = loadGenerationEnabled;
        return this;
    }

    public Boolean loadGenerationSuppressEventLog() {
        if (loadGenerationSuppressEventLog == null) {
            return ConfigurationProperties.loadGenerationSuppressEventLog();
        }
        return loadGenerationSuppressEventLog;
    }

    /**
     * Keep the server's own load-generation traffic out of the request event log. When
     * {@code true} (the default) requests generated by an in-process load scenario are
     * flagged with an in-process-only marker so they are skipped by the event log on the
     * driver, leaving the bounded log free for the requests under test. The marker is never
     * serialized to the wire, so it cannot reach an upstream target. Set to {@code false} to
     * record load-generation traffic in the driver's event log as well.
     *
     * @param loadGenerationSuppressEventLog suppress load-generation traffic in the event log
     */
    public Configuration loadGenerationSuppressEventLog(Boolean loadGenerationSuppressEventLog) {
        this.loadGenerationSuppressEventLog = loadGenerationSuppressEventLog;
        return this;
    }

    public Integer loadGenerationMaxVirtualUsers() {
        if (loadGenerationMaxVirtualUsers == null) {
            return ConfigurationProperties.loadGenerationMaxVirtualUsers();
        }
        return loadGenerationMaxVirtualUsers;
    }

    /**
     * Hard cap on the number of concurrent virtual users a load scenario may drive. A
     * scenario profile asking for more is rejected at validation. Default is 50.
     *
     * @param loadGenerationMaxVirtualUsers maximum concurrent virtual users
     */
    public Configuration loadGenerationMaxVirtualUsers(Integer loadGenerationMaxVirtualUsers) {
        this.loadGenerationMaxVirtualUsers = loadGenerationMaxVirtualUsers;
        return this;
    }

    public Integer loadGenerationMaxInFlightRequests() {
        if (loadGenerationMaxInFlightRequests == null) {
            return ConfigurationProperties.loadGenerationMaxInFlightRequests();
        }
        return loadGenerationMaxInFlightRequests;
    }

    /**
     * Hard cap on the number of in-flight (not-yet-completed) requests a load scenario may
     * have outstanding at once. Enforced live by an in-flight semaphore. Default is 200.
     *
     * @param loadGenerationMaxInFlightRequests maximum concurrent in-flight requests
     */
    public Configuration loadGenerationMaxInFlightRequests(Integer loadGenerationMaxInFlightRequests) {
        this.loadGenerationMaxInFlightRequests = loadGenerationMaxInFlightRequests;
        return this;
    }

    public Integer loadGenerationMaxRequestsPerSecond() {
        if (loadGenerationMaxRequestsPerSecond == null) {
            return ConfigurationProperties.loadGenerationMaxRequestsPerSecond();
        }
        return loadGenerationMaxRequestsPerSecond;
    }

    /**
     * Hard cap on the request rate (requests per second) a load scenario may dispatch.
     * Enforced live by a token bucket. Default is 500.
     *
     * @param loadGenerationMaxRequestsPerSecond maximum requests dispatched per second
     */
    public Configuration loadGenerationMaxRequestsPerSecond(Integer loadGenerationMaxRequestsPerSecond) {
        this.loadGenerationMaxRequestsPerSecond = loadGenerationMaxRequestsPerSecond;
        return this;
    }

    public Long loadGenerationMaxDurationMillis() {
        if (loadGenerationMaxDurationMillis == null) {
            return ConfigurationProperties.loadGenerationMaxDurationMillis();
        }
        return loadGenerationMaxDurationMillis;
    }

    /**
     * Hard cap on the duration (in milliseconds) a load scenario may run. A profile asking
     * for a longer run is rejected at validation. Default is 3600000 (1 hour).
     *
     * @param loadGenerationMaxDurationMillis maximum scenario duration in milliseconds
     */
    public Configuration loadGenerationMaxDurationMillis(Long loadGenerationMaxDurationMillis) {
        this.loadGenerationMaxDurationMillis = loadGenerationMaxDurationMillis;
        return this;
    }

    public Integer loadGenerationMaxSteps() {
        if (loadGenerationMaxSteps == null) {
            return ConfigurationProperties.loadGenerationMaxSteps();
        }
        return loadGenerationMaxSteps;
    }

    /**
     * Hard cap on the number of request steps a single load scenario may define. A scenario
     * with more steps is rejected at validation. Default is 50.
     *
     * @param loadGenerationMaxSteps maximum number of steps per scenario
     */
    public Configuration loadGenerationMaxSteps(Integer loadGenerationMaxSteps) {
        this.loadGenerationMaxSteps = loadGenerationMaxSteps;
        return this;
    }

    public Double loadGenerationMaxRate() {
        if (loadGenerationMaxRate == null) {
            return ConfigurationProperties.loadGenerationMaxRate();
        }
        return loadGenerationMaxRate;
    }

    /**
     * Hard cap on the arrival rate (iterations per second) a {@code RATE} load stage may request.
     * A stage asking for a higher rate is rejected at validation. Default is 5000.
     *
     * @param loadGenerationMaxRate maximum arrival rate in iterations per second
     */
    public Configuration loadGenerationMaxRate(Double loadGenerationMaxRate) {
        this.loadGenerationMaxRate = loadGenerationMaxRate;
        return this;
    }

    public Integer loadGenerationMaxStages() {
        if (loadGenerationMaxStages == null) {
            return ConfigurationProperties.loadGenerationMaxStages();
        }
        return loadGenerationMaxStages;
    }

    /**
     * Hard cap on the number of stages a single load profile may define. A profile with more stages
     * is rejected at validation. Default is 20.
     *
     * @param loadGenerationMaxStages maximum number of stages per profile
     */
    public Configuration loadGenerationMaxStages(Integer loadGenerationMaxStages) {
        this.loadGenerationMaxStages = loadGenerationMaxStages;
        return this;
    }

    public Integer loadGenerationMaxConcurrentScenarios() {
        if (loadGenerationMaxConcurrentScenarios == null) {
            return ConfigurationProperties.loadGenerationMaxConcurrentScenarios();
        }
        return loadGenerationMaxConcurrentScenarios;
    }

    /**
     * Hard cap on the number of concurrently active (PENDING or RUNNING) load scenarios. A start
     * trigger that would exceed this is rejected. Default is 10.
     *
     * @param loadGenerationMaxConcurrentScenarios maximum concurrently active load scenarios
     */
    public Configuration loadGenerationMaxConcurrentScenarios(Integer loadGenerationMaxConcurrentScenarios) {
        this.loadGenerationMaxConcurrentScenarios = loadGenerationMaxConcurrentScenarios;
        return this;
    }

    public String loadScenarioInitializationJsonPath() {
        if (loadScenarioInitializationJsonPath == null) {
            return ConfigurationProperties.loadScenarioInitializationJsonPath();
        }
        return loadScenarioInitializationJsonPath;
    }

    /**
     * Path to a JSON file containing an array of load scenario definitions to load (register) into the
     * registry in the {@code LOADED} state at startup. See
     * {@link ConfigurationProperties#loadScenarioInitializationJsonPath(String)}.
     *
     * @param loadScenarioInitializationJsonPath path to the load scenario definitions JSON file
     */
    public Configuration loadScenarioInitializationJsonPath(String loadScenarioInitializationJsonPath) {
        this.loadScenarioInitializationJsonPath = loadScenarioInitializationJsonPath;
        return this;
    }

    public java.util.List<String> loadGenerationMetricLabels() {
        if (loadGenerationMetricLabels == null) {
            return ConfigurationProperties.loadGenerationMetricLabels();
        }
        return loadGenerationMetricLabels;
    }

    /**
     * Allowlist of custom load-scenario label names exposed as extra fixed Prometheus labels on
     * the {@code mock_server_load_*} metrics. See
     * {@link ConfigurationProperties#loadGenerationMetricLabels(String)}.
     *
     * @param loadGenerationMetricLabels custom label names to expose as Prometheus labels
     */
    public Configuration loadGenerationMetricLabels(java.util.List<String> loadGenerationMetricLabels) {
        this.loadGenerationMetricLabels = loadGenerationMetricLabels;
        return this;
    }

    public Boolean otelPropagateTraceContext() {
        if (otelPropagateTraceContext == null) {
            return ConfigurationProperties.otelPropagateTraceContext();
        }
        return otelPropagateTraceContext;
    }

    /**
     * When true, MockServer copies the incoming W3C traceparent and tracestate
     * headers into mock responses. Off by default.
     *
     * @param otelPropagateTraceContext enable trace context propagation to responses
     */
    public Configuration otelPropagateTraceContext(Boolean otelPropagateTraceContext) {
        this.otelPropagateTraceContext = otelPropagateTraceContext;
        return this;
    }

    public Boolean otelGenerateTraceId() {
        if (otelGenerateTraceId == null) {
            return ConfigurationProperties.otelGenerateTraceId();
        }
        return otelGenerateTraceId;
    }

    /**
     * When true, MockServer generates a new W3C trace ID for incoming requests
     * that do not carry a traceparent header. Off by default.
     *
     * @param otelGenerateTraceId enable trace ID generation for requests without traceparent
     */
    public Configuration otelGenerateTraceId(Boolean otelGenerateTraceId) {
        this.otelGenerateTraceId = otelGenerateTraceId;
        return this;
    }

    public Boolean mcpEnabled() {
        if (mcpEnabled == null) {
            return ConfigurationProperties.mcpEnabled();
        }
        return mcpEnabled;
    }

    public Configuration mcpEnabled(Boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
        return this;
    }

    public Long breakpointTimeoutMillis() {
        if (breakpointTimeoutMillis == null) {
            return ConfigurationProperties.breakpointTimeoutMillis();
        }
        return breakpointTimeoutMillis;
    }

    /**
     * Maximum time in milliseconds a request may be held at a breakpoint before auto-continue.
     * Default is 30000 (30 seconds).
     */
    public Configuration breakpointTimeoutMillis(Long breakpointTimeoutMillis) {
        this.breakpointTimeoutMillis = breakpointTimeoutMillis;
        return this;
    }

    public Integer breakpointMaxHeld() {
        if (breakpointMaxHeld == null) {
            return ConfigurationProperties.breakpointMaxHeld();
        }
        return breakpointMaxHeld;
    }

    /**
     * Maximum number of requests that can be simultaneously held at breakpoints (DoS rail).
     * Default is 50.
     */
    public Configuration breakpointMaxHeld(Integer breakpointMaxHeld) {
        this.breakpointMaxHeld = breakpointMaxHeld;
        return this;
    }

    public Boolean wasmEnabled() {
        if (wasmEnabled == null) {
            return ConfigurationProperties.wasmEnabled();
        }
        return wasmEnabled;
    }

    public Configuration wasmEnabled(Boolean wasmEnabled) {
        this.wasmEnabled = wasmEnabled;
        return this;
    }

    public Integer wasmMaxMemoryPages() {
        if (wasmMaxMemoryPages == null) {
            return ConfigurationProperties.wasmMaxMemoryPages();
        }
        return wasmMaxMemoryPages;
    }

    public Configuration wasmMaxMemoryPages(Integer wasmMaxMemoryPages) {
        this.wasmMaxMemoryPages = wasmMaxMemoryPages;
        return this;
    }

    public Long wasmExecutionTimeoutMillis() {
        if (wasmExecutionTimeoutMillis == null) {
            return ConfigurationProperties.wasmExecutionTimeoutMillis();
        }
        return wasmExecutionTimeoutMillis;
    }

    /**
     * Maximum wall-clock time a single WASM module invocation may run before it is aborted and treated as
     * a non-match (fail closed). WASM custom rules run during request matching, so an unbounded loop would
     * otherwise pin the calling matcher thread permanently. Set to 0 to disable the limit.
     *
     * @param wasmExecutionTimeoutMillis maximum milliseconds a WASM invocation may run, or 0 for no limit
     */
    public Configuration wasmExecutionTimeoutMillis(Long wasmExecutionTimeoutMillis) {
        this.wasmExecutionTimeoutMillis = wasmExecutionTimeoutMillis;
        return this;
    }

    public String grpcDescriptorDirectory() {
        if (grpcDescriptorDirectory == null) {
            return ConfigurationProperties.grpcDescriptorDirectory();
        }
        return grpcDescriptorDirectory;
    }

    public Configuration grpcDescriptorDirectory(String grpcDescriptorDirectory) {
        this.grpcDescriptorDirectory = grpcDescriptorDirectory;
        return this;
    }

    public String grpcProtoDirectory() {
        if (grpcProtoDirectory == null) {
            return ConfigurationProperties.grpcProtoDirectory();
        }
        return grpcProtoDirectory;
    }

    public Configuration grpcProtoDirectory(String grpcProtoDirectory) {
        this.grpcProtoDirectory = grpcProtoDirectory;
        return this;
    }

    public Boolean grpcEnabled() {
        if (grpcEnabled == null) {
            return ConfigurationProperties.grpcEnabled();
        }
        return grpcEnabled;
    }

    public Configuration grpcEnabled(Boolean grpcEnabled) {
        this.grpcEnabled = grpcEnabled;
        return this;
    }

    public String grpcProtocPath() {
        if (grpcProtocPath == null) {
            return ConfigurationProperties.grpcProtocPath();
        }
        return grpcProtocPath;
    }

    public Configuration grpcProtocPath(String grpcProtocPath) {
        this.grpcProtocPath = grpcProtocPath;
        return this;
    }

    public Boolean grpcBidiStreamingEnabled() {
        if (grpcBidiStreamingEnabled == null) {
            return ConfigurationProperties.grpcBidiStreamingEnabled();
        }
        return grpcBidiStreamingEnabled;
    }

    /**
     * If true the HTTP/2 pipeline uses Http2FrameCodec + Http2MultiplexHandler instead of
     * HttpToHttp2ConnectionHandler + InboundHttp2ToHttpAdapter for connections where gRPC
     * descriptors are loaded. This is required for true client-streaming and bidirectional-streaming
     * gRPC in a future phase. In Phase 0 the multiplex branch re-aggregates frames so behaviour
     * is identical to the connection-level adapter.
     * <p>
     * Default is false
     *
     * @param grpcBidiStreamingEnabled enable the multiplex HTTP/2 pipeline for gRPC bidi-streaming support
     */
    public Configuration grpcBidiStreamingEnabled(Boolean grpcBidiStreamingEnabled) {
        this.grpcBidiStreamingEnabled = grpcBidiStreamingEnabled;
        return this;
    }

    public Boolean dnsEnabled() {
        if (dnsEnabled == null) {
            return ConfigurationProperties.dnsEnabled();
        }
        return dnsEnabled;
    }

    public Configuration dnsEnabled(Boolean dnsEnabled) {
        this.dnsEnabled = dnsEnabled;
        return this;
    }

    public Integer dnsPort() {
        if (dnsPort == null) {
            return ConfigurationProperties.dnsPort();
        }
        return dnsPort;
    }

    public Configuration dnsPort(Integer dnsPort) {
        this.dnsPort = dnsPort;
        return this;
    }

    public Integer http3Port() {
        if (http3Port == null) {
            return ConfigurationProperties.http3Port();
        }
        return http3Port;
    }

    public Configuration http3Port(Integer http3Port) {
        this.http3Port = http3Port;
        return this;
    }

    public Long http3MaxIdleTimeout() {
        if (http3MaxIdleTimeout == null) {
            return ConfigurationProperties.http3MaxIdleTimeout();
        }
        return Math.max(0, http3MaxIdleTimeout);
    }

    public Configuration http3MaxIdleTimeout(Long http3MaxIdleTimeout) {
        this.http3MaxIdleTimeout = http3MaxIdleTimeout;
        return this;
    }

    public Long http3InitialMaxData() {
        if (http3InitialMaxData == null) {
            return ConfigurationProperties.http3InitialMaxData();
        }
        return Math.max(0, http3InitialMaxData);
    }

    public Configuration http3InitialMaxData(Long http3InitialMaxData) {
        this.http3InitialMaxData = http3InitialMaxData;
        return this;
    }

    public Long http3InitialMaxStreamDataBidirectional() {
        if (http3InitialMaxStreamDataBidirectional == null) {
            return ConfigurationProperties.http3InitialMaxStreamDataBidirectional();
        }
        return Math.max(0, http3InitialMaxStreamDataBidirectional);
    }

    public Configuration http3InitialMaxStreamDataBidirectional(Long http3InitialMaxStreamDataBidirectional) {
        this.http3InitialMaxStreamDataBidirectional = http3InitialMaxStreamDataBidirectional;
        return this;
    }

    public Long http3InitialMaxStreamsBidirectional() {
        if (http3InitialMaxStreamsBidirectional == null) {
            return ConfigurationProperties.http3InitialMaxStreamsBidirectional();
        }
        return Math.max(0, http3InitialMaxStreamsBidirectional);
    }

    public Configuration http3InitialMaxStreamsBidirectional(Long http3InitialMaxStreamsBidirectional) {
        this.http3InitialMaxStreamsBidirectional = http3InitialMaxStreamsBidirectional;
        return this;
    }

    public Long http3QpackMaxTableCapacity() {
        if (http3QpackMaxTableCapacity == null) {
            return ConfigurationProperties.http3QpackMaxTableCapacity();
        }
        return Math.max(0, http3QpackMaxTableCapacity);
    }

    public Configuration http3QpackMaxTableCapacity(Long http3QpackMaxTableCapacity) {
        this.http3QpackMaxTableCapacity = http3QpackMaxTableCapacity;
        return this;
    }

    public Boolean http3ConnectUdpEnabled() {
        if (http3ConnectUdpEnabled == null) {
            return ConfigurationProperties.http3ConnectUdpEnabled();
        }
        return http3ConnectUdpEnabled;
    }

    public Configuration http3ConnectUdpEnabled(Boolean http3ConnectUdpEnabled) {
        this.http3ConnectUdpEnabled = http3ConnectUdpEnabled;
        return this;
    }

    public String http3ConnectUdpAllowedTargets() {
        if (http3ConnectUdpAllowedTargets == null) {
            return ConfigurationProperties.http3ConnectUdpAllowedTargets();
        }
        return http3ConnectUdpAllowedTargets;
    }

    public Configuration http3ConnectUdpAllowedTargets(String http3ConnectUdpAllowedTargets) {
        this.http3ConnectUdpAllowedTargets = http3ConnectUdpAllowedTargets;
        return this;
    }

    public Long http3AltSvcMaxAge() {
        if (http3AltSvcMaxAge == null) {
            return ConfigurationProperties.http3AltSvcMaxAge();
        }
        return Math.max(0, http3AltSvcMaxAge);
    }

    public Configuration http3AltSvcMaxAge(Long http3AltSvcMaxAge) {
        this.http3AltSvcMaxAge = http3AltSvcMaxAge;
        return this;
    }

    public Boolean http3AdvertiseAltSvc() {
        if (http3AdvertiseAltSvc == null) {
            return ConfigurationProperties.http3AdvertiseAltSvc();
        }
        return http3AdvertiseAltSvc;
    }

    public Configuration http3AdvertiseAltSvc(Boolean http3AdvertiseAltSvc) {
        this.http3AdvertiseAltSvc = http3AdvertiseAltSvc;
        return this;
    }

    public Map<String, String> logLevelOverrides() {
        if (logLevelOverrides == null) {
            return ConfigurationProperties.logLevelOverrides();
        }
        return logLevelOverrides;
    }

    public Configuration logLevelOverrides(Map<String, String> logLevelOverrides) {
        this.logLevelOverrides = logLevelOverrides;
        return this;
    }

    public Boolean compactLogFormat() {
        if (compactLogFormat == null) {
            return ConfigurationProperties.compactLogFormat();
        }
        return compactLogFormat;
    }

    public Configuration compactLogFormat(Boolean compactLogFormat) {
        this.compactLogFormat = compactLogFormat;
        return this;
    }

    public Boolean devMode() {
        if (devMode == null) {
            return ConfigurationProperties.devMode();
        }
        return devMode;
    }

    public Configuration devMode(Boolean devMode) {
        this.devMode = devMode;
        return this;
    }

    public Integer maxExpectations() {
        if (maxExpectations == null) {
            // Honour the instance devMode field so that
            // configuration.devMode(true) applies the dev default without
            // needing to set the global ConfigurationProperties.devMode.
            if (Boolean.TRUE.equals(devMode)) {
                return ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS;
            }
            return ConfigurationProperties.maxExpectations();
        }
        return maxExpectations;
    }

    /**
     * <p>
     * Maximum number of expectations stored in memory.  Expectations are stored in a circular queue so once this limit is reach the oldest and lowest priority expectations are overwritten
     * </p>
     * <p>
     * The default maximum depends on the available memory in the JVM with an upper limit of 15000
     * </p>
     *
     * @param maxExpectations maximum number of expectations to store
     */
    public Configuration maxExpectations(Integer maxExpectations) {
        this.maxExpectations = maxExpectations;
        return this;
    }

    public Integer maxLogEntries() {
        if (maxLogEntries == null) {
            // Honour the instance devMode field so that
            // configuration.devMode(true) applies the dev default without
            // needing to set the global ConfigurationProperties.devMode.
            if (Boolean.TRUE.equals(devMode)) {
                return ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES;
            }
            return ConfigurationProperties.maxLogEntries();
        }
        return maxLogEntries;
    }

    /**
     * <p>
     * Maximum number of log entries stored in memory.  Log entries are stored in a circular queue so once this limit is reach the oldest log entries are overwritten
     * </p>
     * <p>
     * The default maximum depends on the available memory in the JVM with an upper limit of 100000
     * </p>
     *
     * @param maxLogEntries maximum number of expectations to store
     */
    public Configuration maxLogEntries(Integer maxLogEntries) {
        this.maxLogEntries = maxLogEntries;
        return this;
    }

    public Long maxEventLogSizeInBytes() {
        if (maxEventLogSizeInBytes == null) {
            return ConfigurationProperties.maxEventLogSizeInBytes();
        }
        return maxEventLogSizeInBytes;
    }

    /**
     * <p>
     * Maximum total size in bytes of the in-memory event log before older entries are evicted from memory (the oldest first).
     * </p>
     * <p>
     * The default is 0, which disables the size-based limit (the event log is bounded only by {@link #maxLogEntries}).
     * </p>
     *
     * @param maxEventLogSizeInBytes maximum total size in bytes of the in-memory event log (0 disables the limit)
     */
    public Configuration maxEventLogSizeInBytes(Long maxEventLogSizeInBytes) {
        this.maxEventLogSizeInBytes = maxEventLogSizeInBytes;
        return this;
    }

    public Integer maxLoggedBodyBytes() {
        if (maxLoggedBodyBytes == null) {
            return ConfigurationProperties.maxLoggedBodyBytes();
        }
        return maxLoggedBodyBytes;
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
    public Configuration maxLoggedBodyBytes(Integer maxLoggedBodyBytes) {
        this.maxLoggedBodyBytes = maxLoggedBodyBytes;
        return this;
    }

    /**
     * <p>
     * Number of slots in the in-memory log event ring buffer (LMAX Disruptor) that buffers log events
     * between the producing Netty I/O threads and the single consumer thread. Independent of
     * {@link #maxLogEntries} (which bounds the retained event history) — the ring only needs to absorb
     * short bursts, so it can be much smaller than the retained history.
     * </p>
     * <p>
     * The value is rounded up to the next power of two (a Disruptor requirement). When unset the
     * default is {@code min(maxLogEntries, 16384)}.
     * </p>
     *
     * @param ringBufferSize number of slots in the log event ring buffer (rounded up to a power of two)
     */
    public Configuration ringBufferSize(Integer ringBufferSize) {
        this.ringBufferSize = ringBufferSize;
        return this;
    }

    public Integer maxWebSocketExpectations() {
        if (maxWebSocketExpectations == null) {
            return ConfigurationProperties.maxWebSocketExpectations();
        }
        return maxWebSocketExpectations;
    }

    /**
     * <p>
     * Maximum number of remote (not the same JVM) method callbacks (i.e. web sockets) registered for expectations.  The web socket client registry entries are stored in a circular queue so once this limit is reach the oldest are overwritten.
     * </p>
     * <p>
     * The default is 1500
     * </p>
     *
     * @param maxWebSocketExpectations maximum number of method callbacks (i.e. web sockets) registered for expectations
     */
    public Configuration maxWebSocketExpectations(Integer maxWebSocketExpectations) {
        this.maxWebSocketExpectations = maxWebSocketExpectations;
        return this;
    }

    public Integer webSocketProxyMaxRecordedFrames() {
        if (webSocketProxyMaxRecordedFrames == null) {
            return ConfigurationProperties.webSocketProxyMaxRecordedFrames();
        }
        return webSocketProxyMaxRecordedFrames;
    }

    /**
     * <p>
     * Maximum number of WebSocket frames recorded per proxied (passthrough) WebSocket connection. When MockServer
     * proxies a WebSocket upgrade to a real upstream server (no matching mock expectation), the relayed frames are
     * captured into a per-connection transcript written to the event log as a {@code FORWARDED_REQUEST} on close, so
     * {@code retrieveRecordedRequests} and the dashboard show the WebSocket traffic. Once this cap is reached the
     * remaining frames are relayed but not recorded (the transcript is flagged truncated), bounding memory.
     * </p>
     * <p>
     * The default is 1000. Set to 0 to disable frame recording (the upgrade handshake is still recorded).
     * </p>
     *
     * @param webSocketProxyMaxRecordedFrames maximum number of relayed WebSocket frames recorded per proxied connection
     */
    public Configuration webSocketProxyMaxRecordedFrames(Integer webSocketProxyMaxRecordedFrames) {
        this.webSocketProxyMaxRecordedFrames = webSocketProxyMaxRecordedFrames;
        return this;
    }

    public Integer webSocketProxyIdleTimeoutSeconds() {
        if (webSocketProxyIdleTimeoutSeconds == null) {
            return ConfigurationProperties.webSocketProxyIdleTimeoutSeconds();
        }
        return webSocketProxyIdleTimeoutSeconds;
    }

    /**
     * <p>
     * Idle timeout (in seconds) for a proxied (passthrough) WebSocket connection. When positive, a relayed WebSocket
     * connection whose two directions have both been idle for this many seconds is closed, reaping abandoned relays.
     * </p>
     * <p>
     * The default is 0 (disabled) — legitimately idle long-lived WebSocket connections are left to TCP keep-alive and
     * peer-close propagation.
     * </p>
     *
     * @param webSocketProxyIdleTimeoutSeconds idle timeout in seconds for proxied WebSocket relays (0 disables)
     */
    public Configuration webSocketProxyIdleTimeoutSeconds(Integer webSocketProxyIdleTimeoutSeconds) {
        this.webSocketProxyIdleTimeoutSeconds = webSocketProxyIdleTimeoutSeconds;
        return this;
    }

    public Boolean outputMemoryUsageCsv() {
        if (outputMemoryUsageCsv == null) {
            return ConfigurationProperties.outputMemoryUsageCsv();
        }
        return outputMemoryUsageCsv;
    }

    /**
     * <p>Output JVM memory usage metrics to CSV file periodically called <strong>memoryUsage_&lt;yyyy-MM-dd&gt;.csv</strong></p>
     *
     * @param outputMemoryUsageCsv output of JVM memory metrics
     */
    public Configuration outputMemoryUsageCsv(Boolean outputMemoryUsageCsv) {
        this.outputMemoryUsageCsv = outputMemoryUsageCsv;
        return this;
    }

    public String memoryUsageCsvDirectory() {
        if (memoryUsageCsvDirectory == null) {
            return ConfigurationProperties.memoryUsageCsvDirectory();
        }
        return memoryUsageCsvDirectory;
    }

    /**
     * <p>Directory to output JVM memory usage metrics CSV files to when outputMemoryUsageCsv enabled</p>
     *
     * @param memoryUsageCsvDirectory directory to save JVM memory metrics CSV files
     */
    public Configuration memoryUsageCsvDirectory(String memoryUsageCsvDirectory) {
        this.memoryUsageCsvDirectory = memoryUsageCsvDirectory;
        return this;
    }

    public Boolean useNativeTransport() {
        if (useNativeTransport == null) {
            return ConfigurationProperties.useNativeTransport();
        }
        return useNativeTransport;
    }

    /**
     * If true (the default) MockServer will use the native epoll transport on Linux
     * for higher performance and to enable transparent-proxy SO_ORIGINAL_DST resolution.
     * Set to false to force the NIO transport on all platforms.
     * <p>
     * This property is read at start-up only.
     *
     * @param useNativeTransport enable native transport when available
     */
    public Configuration useNativeTransport(Boolean useNativeTransport) {
        this.useNativeTransport = useNativeTransport;
        return this;
    }

    public Integer nioEventLoopThreadCount() {
        if (nioEventLoopThreadCount == null) {
            return ConfigurationProperties.nioEventLoopThreadCount();
        }
        return nioEventLoopThreadCount;
    }

    /**
     * <p>Netty worker thread pool size for handling requests and response.  These threads handle deserializing and serialising HTTP requests and responses and some other fast logic, long running tasks are done on the action handler thread pool.</p>
     *
     * @param nioEventLoopThreadCount Netty worker thread pool size
     */
    public Configuration nioEventLoopThreadCount(Integer nioEventLoopThreadCount) {
        this.nioEventLoopThreadCount = nioEventLoopThreadCount;
        return this;
    }

    public Integer actionHandlerThreadCount() {
        if (actionHandlerThreadCount == null) {
            return ConfigurationProperties.actionHandlerThreadCount();
        }
        return actionHandlerThreadCount;
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
     * @param actionHandlerThreadCount Netty worker thread pool size
     */
    public Configuration actionHandlerThreadCount(Integer actionHandlerThreadCount) {
        this.actionHandlerThreadCount = actionHandlerThreadCount;
        return this;
    }

    public Integer clientNioEventLoopThreadCount() {
        if (clientNioEventLoopThreadCount == null) {
            return ConfigurationProperties.clientNioEventLoopThreadCount();
        }
        return clientNioEventLoopThreadCount;
    }

    /**
     * <p>Client Netty worker thread pool size for handling requests and response.  These threads handle deserializing and serialising HTTP requests and responses and some other fast logic.</p>
     *
     * <p>Default is 5 threads</p>
     *
     * @param clientNioEventLoopThreadCount Client Netty worker thread pool size
     */
    public Configuration clientNioEventLoopThreadCount(Integer clientNioEventLoopThreadCount) {
        this.clientNioEventLoopThreadCount = clientNioEventLoopThreadCount;
        return this;
    }

    public Integer webSocketClientEventLoopThreadCount() {
        if (webSocketClientEventLoopThreadCount == null) {
            return ConfigurationProperties.webSocketClientEventLoopThreadCount();
        }
        return webSocketClientEventLoopThreadCount;
    }

    /**
     * <p>Client Netty worker thread pool size for handling requests and response.  These threads handle deserializing and serialising HTTP requests and responses and some other fast logic.</p>
     *
     * <p>Default is 5 threads</p>
     *
     * @param webSocketClientEventLoopThreadCount Client Netty worker thread pool size
     */
    public Configuration webSocketClientEventLoopThreadCount(Integer webSocketClientEventLoopThreadCount) {
        this.webSocketClientEventLoopThreadCount = webSocketClientEventLoopThreadCount;
        return this;
    }

    public Long maxFutureTimeoutInMillis() {
        if (maxFutureTimeoutInMillis == null) {
            return ConfigurationProperties.maxFutureTimeout();
        }
        return maxFutureTimeoutInMillis;
    }

    /**
     * Maximum time allowed in milliseconds for any future to wait, for example when waiting for a response over a web socket callback.
     * <p>
     * Default is 60,000 ms
     *
     * @param maxFutureTimeoutInMillis maximum time allowed in milliseconds
     */
    public Configuration maxFutureTimeoutInMillis(Long maxFutureTimeoutInMillis) {
        this.maxFutureTimeoutInMillis = maxFutureTimeoutInMillis;
        return this;
    }

    public Boolean matchersFailFast() {
        if (matchersFailFast == null) {
            return ConfigurationProperties.matchersFailFast();
        }
        return matchersFailFast;
    }

    /**
     * If true (the default) request matchers will fail on the first non-matching field, if false request matchers will compare all fields.
     * This is useful to see all mismatching fields in the log event recording that a request matcher did not match.
     *
     * @param matchersFailFast enabled request matchers failing fast
     */
    public Configuration matchersFailFast(Boolean matchersFailFast) {
        this.matchersFailFast = matchersFailFast;
        return this;
    }

    public Boolean matchExactCase() {
        if (matchExactCase == null) {
            return ConfigurationProperties.matchExactCase();
        }
        return matchExactCase;
    }

    /**
     * If false (the default) request matching for the method, path and string body is case-insensitive,
     * matching the historical behaviour. If true matching of those three fields becomes case-sensitive
     * (exact case). Header names and values, cookie names and values, and query string parameters are
     * always matched case-insensitively regardless of this setting.
     *
     * @param matchExactCase enabled exact-case (case-sensitive) matching of method, path and string body
     */
    public Configuration matchExactCase(Boolean matchExactCase) {
        this.matchExactCase = matchExactCase;
        return this;
    }

    public Boolean forwardConnectionPoolEnabled() {
        if (forwardConnectionPoolEnabled == null) {
            return ConfigurationProperties.forwardConnectionPoolEnabled();
        }
        return forwardConnectionPoolEnabled;
    }

    /**
     * If false (the default) every forwarded or proxied request opens a fresh upstream connection
     * that is closed once the response is received. If true idle keep-alive HTTP/1.1 upstream
     * connections are pooled (keyed by host, port and scheme) and reused for subsequent requests
     * to the same upstream. Only plain HTTP/1.1 keep-alive connections are pooled; HTTP/2, HTTP/3,
     * binary forwarding, streaming responses, proxy-tunnelled connections and connections the
     * upstream closed or that returned "Connection: close" are never pooled.
     *
     * @param forwardConnectionPoolEnabled enable pooling of idle keep-alive upstream HTTP/1.1 connections
     */
    public Configuration forwardConnectionPoolEnabled(Boolean forwardConnectionPoolEnabled) {
        this.forwardConnectionPoolEnabled = forwardConnectionPoolEnabled;
        return this;
    }

    public Integer forwardConnectionPoolMaxIdlePerKey() {
        if (forwardConnectionPoolMaxIdlePerKey == null) {
            return ConfigurationProperties.forwardConnectionPoolMaxIdlePerKey();
        }
        return forwardConnectionPoolMaxIdlePerKey;
    }

    /**
     * Maximum number of idle keep-alive upstream connections retained per upstream (host, port,
     * scheme) when {@code forwardConnectionPoolEnabled} is true. Surplus connections are closed
     * rather than pooled. Default is 8.
     *
     * @param forwardConnectionPoolMaxIdlePerKey maximum idle connections retained per upstream
     */
    public Configuration forwardConnectionPoolMaxIdlePerKey(Integer forwardConnectionPoolMaxIdlePerKey) {
        this.forwardConnectionPoolMaxIdlePerKey = forwardConnectionPoolMaxIdlePerKey;
        return this;
    }

    public Long forwardConnectionPoolIdleTimeoutMillis() {
        if (forwardConnectionPoolIdleTimeoutMillis == null) {
            return ConfigurationProperties.forwardConnectionPoolIdleTimeoutMillis();
        }
        return forwardConnectionPoolIdleTimeoutMillis;
    }

    /**
     * How long in milliseconds an idle pooled upstream connection is retained before it is closed
     * and evicted when {@code forwardConnectionPoolEnabled} is true. Default is 30,000 ms; 0
     * disables idle eviction.
     *
     * @param forwardConnectionPoolIdleTimeoutMillis idle retention time in milliseconds, 0 to disable
     */
    public Configuration forwardConnectionPoolIdleTimeoutMillis(Long forwardConnectionPoolIdleTimeoutMillis) {
        this.forwardConnectionPoolIdleTimeoutMillis = forwardConnectionPoolIdleTimeoutMillis;
        return this;
    }

    public Boolean forwardConnectionPoolKeepAlive() {
        if (forwardConnectionPoolKeepAlive == null) {
            return ConfigurationProperties.forwardConnectionPoolKeepAlive();
        }
        return forwardConnectionPoolKeepAlive;
    }

    /**
     * If true, idle keep-alive upstream connections are retained (kept warm) up to
     * {@code forwardConnectionPoolMaxTotalPerKey} per upstream instead of being closed back down to
     * {@code forwardConnectionPoolMaxIdlePerKey}, eliminating connection churn under sustained
     * high-rate, low-latency forwarding or load injection. Default false leaves the pool's
     * release-time close decision byte-for-byte unchanged. Only relevant when
     * {@code forwardConnectionPoolEnabled} is true.
     *
     * @param forwardConnectionPoolKeepAlive enable keep-warm retention of idle upstream connections
     */
    public Configuration forwardConnectionPoolKeepAlive(Boolean forwardConnectionPoolKeepAlive) {
        this.forwardConnectionPoolKeepAlive = forwardConnectionPoolKeepAlive;
        return this;
    }

    public Integer forwardConnectionPoolMaxTotalPerKey() {
        if (forwardConnectionPoolMaxTotalPerKey == null) {
            return ConfigurationProperties.forwardConnectionPoolMaxTotalPerKey();
        }
        return forwardConnectionPoolMaxTotalPerKey;
    }

    /**
     * Maximum number of warm (idle) keep-alive upstream connections retained per upstream when
     * {@code forwardConnectionPoolKeepAlive} is true. Bounds the warm set so it cannot grow without
     * limit. Has no effect unless keep-warm is enabled. Default 2000; the effective ceiling is never
     * below {@code forwardConnectionPoolMaxIdlePerKey}.
     *
     * @param forwardConnectionPoolMaxTotalPerKey maximum warm idle connections retained per upstream
     */
    public Configuration forwardConnectionPoolMaxTotalPerKey(Integer forwardConnectionPoolMaxTotalPerKey) {
        this.forwardConnectionPoolMaxTotalPerKey = forwardConnectionPoolMaxTotalPerKey;
        return this;
    }

    public Boolean forwardSocketKeepAlive() {
        if (forwardSocketKeepAlive == null) {
            return ConfigurationProperties.forwardSocketKeepAlive();
        }
        return forwardSocketKeepAlive;
    }

    /**
     * If true (the default) the forward/proxy HTTP client enables TCP keepalive on its upstream
     * connections so dead/half-open connections are detected faster and NAT/firewall mappings stay
     * warm. On epoll the keepalive timers are tuned via
     * {@code forwardSocketKeepAliveIdleSeconds}/{@code IntervalSeconds}/{@code Count}; on NIO only
     * SO_KEEPALIVE is set. Benign default-on hardening; set false to restore no socket keepalive.
     *
     * @param forwardSocketKeepAlive enable TCP keepalive on forward/proxy upstream connections
     */
    public Configuration forwardSocketKeepAlive(Boolean forwardSocketKeepAlive) {
        this.forwardSocketKeepAlive = forwardSocketKeepAlive;
        return this;
    }

    public Integer forwardSocketKeepAliveIdleSeconds() {
        if (forwardSocketKeepAliveIdleSeconds == null) {
            return ConfigurationProperties.forwardSocketKeepAliveIdleSeconds();
        }
        return forwardSocketKeepAliveIdleSeconds;
    }

    /**
     * Seconds an upstream connection may sit idle before the first TCP keepalive probe (epoll
     * {@code TCP_KEEPIDLE}); applied only on epoll when {@code forwardSocketKeepAlive} is true.
     * Default 60.
     *
     * @param forwardSocketKeepAliveIdleSeconds idle seconds before the first keepalive probe
     */
    public Configuration forwardSocketKeepAliveIdleSeconds(Integer forwardSocketKeepAliveIdleSeconds) {
        this.forwardSocketKeepAliveIdleSeconds = forwardSocketKeepAliveIdleSeconds;
        return this;
    }

    public Integer forwardSocketKeepAliveIntervalSeconds() {
        if (forwardSocketKeepAliveIntervalSeconds == null) {
            return ConfigurationProperties.forwardSocketKeepAliveIntervalSeconds();
        }
        return forwardSocketKeepAliveIntervalSeconds;
    }

    /**
     * Seconds between successive TCP keepalive probes (epoll {@code TCP_KEEPINTVL}); applied only on
     * epoll when {@code forwardSocketKeepAlive} is true. Default 15.
     *
     * @param forwardSocketKeepAliveIntervalSeconds interval seconds between keepalive probes
     */
    public Configuration forwardSocketKeepAliveIntervalSeconds(Integer forwardSocketKeepAliveIntervalSeconds) {
        this.forwardSocketKeepAliveIntervalSeconds = forwardSocketKeepAliveIntervalSeconds;
        return this;
    }

    public Integer forwardSocketKeepAliveCount() {
        if (forwardSocketKeepAliveCount == null) {
            return ConfigurationProperties.forwardSocketKeepAliveCount();
        }
        return forwardSocketKeepAliveCount;
    }

    /**
     * Number of unacknowledged TCP keepalive probes before the upstream connection is dropped (epoll
     * {@code TCP_KEEPCNT}); applied only on epoll when {@code forwardSocketKeepAlive} is true.
     * Default 4.
     *
     * @param forwardSocketKeepAliveCount failed keepalive probes before the connection is dropped
     */
    public Configuration forwardSocketKeepAliveCount(Integer forwardSocketKeepAliveCount) {
        this.forwardSocketKeepAliveCount = forwardSocketKeepAliveCount;
        return this;
    }

    public Integer forwardProxyRetryCount() {
        if (forwardProxyRetryCount == null) {
            return ConfigurationProperties.forwardProxyRetryCount();
        }
        return forwardProxyRetryCount;
    }

    /**
     * Maximum number of times a forwarded or proxied request to an upstream is retried after a
     * transient failure (connection refused/reset, timeout, or a 502/503/504 from the upstream).
     * Only idempotent HTTP methods (GET, HEAD, OPTIONS, PUT, DELETE, TRACE) are retried. Default is
     * 0 (no retry — each request is forwarded exactly once, as before).
     *
     * @param forwardProxyRetryCount maximum retries for idempotent forwarded/proxied requests, 0 to disable
     */
    public Configuration forwardProxyRetryCount(Integer forwardProxyRetryCount) {
        this.forwardProxyRetryCount = forwardProxyRetryCount;
        return this;
    }

    public Long forwardProxyRetryBackoffMillis() {
        if (forwardProxyRetryBackoffMillis == null) {
            return ConfigurationProperties.forwardProxyRetryBackoffMillis();
        }
        return forwardProxyRetryBackoffMillis;
    }

    /**
     * Base linear back-off in milliseconds between forward/proxy retry attempts (attempt n waits n
     * base delays). Only relevant when {@code forwardProxyRetryCount} is greater than 0. Default is
     * 100 ms; 0 retries immediately with no back-off.
     *
     * @param forwardProxyRetryBackoffMillis base back-off in milliseconds, 0 to disable
     */
    public Configuration forwardProxyRetryBackoffMillis(Long forwardProxyRetryBackoffMillis) {
        this.forwardProxyRetryBackoffMillis = forwardProxyRetryBackoffMillis;
        return this;
    }

    public Boolean forwardProxyHttp2Enabled() {
        if (forwardProxyHttp2Enabled == null) {
            return ConfigurationProperties.forwardProxyHttp2Enabled();
        }
        return forwardProxyHttp2Enabled;
    }

    /**
     * If false (the default) every forwarded or proxied request is sent to its upstream over HTTP/1.1,
     * matching the historical behaviour. If true the inbound request's protocol is preserved when
     * forwarding, so an HTTP/2 inbound request is forwarded to the upstream as HTTP/2.
     * <p>
     * Limitations: HTTP/2 upstream forwarding only happens over TLS with ALPN negotiation (no h2c
     * cleartext path — a non-secure HTTP/2 request is downgraded to HTTP/1.1). HTTP/2 forward
     * connections are not pooled or multiplexed across forwards; the forward connection pool is
     * HTTP/1.1-only.
     *
     * @param forwardProxyHttp2Enabled preserve the inbound request protocol (e.g. HTTP/2) when forwarding
     */
    public Configuration forwardProxyHttp2Enabled(Boolean forwardProxyHttp2Enabled) {
        this.forwardProxyHttp2Enabled = forwardProxyHttp2Enabled;
        return this;
    }

    public Boolean forwardProxyHttp2Upgrade() {
        if (forwardProxyHttp2Upgrade == null) {
            return ConfigurationProperties.forwardProxyHttp2Upgrade();
        }
        return forwardProxyHttp2Upgrade;
    }

    /**
     * If false (the default) a forwarded or proxied request is only sent upstream over HTTP/2 when the
     * inbound request itself used HTTP/2 and {@code forwardProxyHttp2Enabled} is set. If true, a secure
     * (TLS) forward is sent upstream over HTTP/2 via ALPN even when the inbound client is HTTP/1.1, with
     * automatic fallback to HTTP/1.1 if the upstream does not negotiate h2.
     * <p>
     * Useful when an upstream sends a streaming (Server-Sent Events) response head immediately over
     * HTTP/2 but withholds it over HTTP/1.1. HTTP/2 only flows over TLS+ALPN (no h2c cleartext path — a
     * non-secure request stays HTTP/1.1). HTTP/2 forward connections are not pooled.
     *
     * @param forwardProxyHttp2Upgrade forward a secure request upstream over HTTP/2 even when the inbound client is HTTP/1.1
     */
    public Configuration forwardProxyHttp2Upgrade(Boolean forwardProxyHttp2Upgrade) {
        this.forwardProxyHttp2Upgrade = forwardProxyHttp2Upgrade;
        return this;
    }

    public Boolean forwardProxyCircuitBreakerEnabled() {
        if (forwardProxyCircuitBreakerEnabled == null) {
            return ConfigurationProperties.forwardProxyCircuitBreakerEnabled();
        }
        return forwardProxyCircuitBreakerEnabled;
    }

    /**
     * If false (the default) every forwarded or proxied request is attempted against its upstream.
     * If true a per-upstream circuit breaker (keyed by host and port) trips open after
     * {@code forwardProxyCircuitBreakerFailureThreshold} consecutive failures, failing subsequent
     * requests fast with a 503 for {@code forwardProxyCircuitBreakerWindowMillis} before allowing a
     * single half-open trial request.
     *
     * @param forwardProxyCircuitBreakerEnabled enable the per-upstream forward/proxy circuit breaker
     */
    public Configuration forwardProxyCircuitBreakerEnabled(Boolean forwardProxyCircuitBreakerEnabled) {
        this.forwardProxyCircuitBreakerEnabled = forwardProxyCircuitBreakerEnabled;
        return this;
    }

    public Integer forwardProxyCircuitBreakerFailureThreshold() {
        if (forwardProxyCircuitBreakerFailureThreshold == null) {
            return ConfigurationProperties.forwardProxyCircuitBreakerFailureThreshold();
        }
        return forwardProxyCircuitBreakerFailureThreshold;
    }

    /**
     * Number of consecutive failures to a single upstream (host and port) that trips the
     * forward/proxy circuit breaker open. Only relevant when
     * {@code forwardProxyCircuitBreakerEnabled} is true. Default is 5.
     *
     * @param forwardProxyCircuitBreakerFailureThreshold consecutive failures that open the breaker
     */
    public Configuration forwardProxyCircuitBreakerFailureThreshold(Integer forwardProxyCircuitBreakerFailureThreshold) {
        this.forwardProxyCircuitBreakerFailureThreshold = forwardProxyCircuitBreakerFailureThreshold;
        return this;
    }

    public Long forwardProxyCircuitBreakerWindowMillis() {
        if (forwardProxyCircuitBreakerWindowMillis == null) {
            return ConfigurationProperties.forwardProxyCircuitBreakerWindowMillis();
        }
        return forwardProxyCircuitBreakerWindowMillis;
    }

    /**
     * How long in milliseconds the forward/proxy circuit breaker stays open (failing requests fast
     * with a 503) for an upstream before transitioning to half-open. Only relevant when
     * {@code forwardProxyCircuitBreakerEnabled} is true. Default is 30,000 ms.
     *
     * @param forwardProxyCircuitBreakerWindowMillis open-state duration in milliseconds
     */
    public Configuration forwardProxyCircuitBreakerWindowMillis(Long forwardProxyCircuitBreakerWindowMillis) {
        this.forwardProxyCircuitBreakerWindowMillis = forwardProxyCircuitBreakerWindowMillis;
        return this;
    }

    public Long maxSocketTimeoutInMillis() {
        if (maxSocketTimeoutInMillis == null) {
            return ConfigurationProperties.maxSocketTimeout();
        }
        return maxSocketTimeoutInMillis;
    }

    /**
     * Maximum time in milliseconds allowed for a response from a socket
     * <p>
     * Default is 20,000 ms
     *
     * @param maxSocketTimeoutInMillis maximum time in milliseconds allowed
     */
    public Configuration maxSocketTimeoutInMillis(Long maxSocketTimeoutInMillis) {
        this.maxSocketTimeoutInMillis = maxSocketTimeoutInMillis;
        return this;
    }

    public Long socketConnectionTimeoutInMillis() {
        if (socketConnectionTimeoutInMillis == null) {
            return ConfigurationProperties.socketConnectionTimeout();
        }
        return socketConnectionTimeoutInMillis;
    }

    /**
     * Maximum time in milliseconds allowed to connect to a socket
     * <p>
     * Default is 20,000 ms
     *
     * @param socketConnectionTimeoutInMillis maximum time allowed in milliseconds
     */
    public Configuration socketConnectionTimeoutInMillis(Long socketConnectionTimeoutInMillis) {
        this.socketConnectionTimeoutInMillis = socketConnectionTimeoutInMillis;
        return this;
    }

    public Delay connectionDelay() {
        return connectionDelay;
    }

    public Configuration connectionDelay(Delay connectionDelay) {
        this.connectionDelay = connectionDelay;
        return this;
    }

    public Boolean alwaysCloseSocketConnections() {
        if (alwaysCloseSocketConnections == null) {
            return ConfigurationProperties.alwaysCloseSocketConnections();
        }
        return alwaysCloseSocketConnections;
    }

    /**
     * <p>If true socket connections will always be closed after a response is returned, if false connection is only closed if request header indicate connection should be closed.</p>
     * <p>
     * Default is false
     *
     * @param alwaysCloseSocketConnections true socket connections will always be closed after a response is returned
     */
    public Configuration alwaysCloseSocketConnections(Boolean alwaysCloseSocketConnections) {
        this.alwaysCloseSocketConnections = alwaysCloseSocketConnections;
        return this;
    }

    public String localBoundIP() {
        if (localBoundIP == null) {
            return ConfigurationProperties.localBoundIP();
        }
        return localBoundIP;
    }

    /**
     * The local IP address to bind to for accepting new socket connections
     * <p>
     * Default is 0.0.0.0
     *
     * @param localBoundIP local IP address to bind to for accepting new socket connections
     */
    public Configuration localBoundIP(String localBoundIP) {
        this.localBoundIP = localBoundIP;
        return this;
    }

    public Integer maxInitialLineLength() {
        if (maxInitialLineLength == null) {
            return ConfigurationProperties.maxInitialLineLength();
        }
        return maxInitialLineLength;
    }

    /**
     * Maximum size of the first line of an HTTP request
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param maxInitialLineLength maximum size of the first line of an HTTP request
     */
    public Configuration maxInitialLineLength(Integer maxInitialLineLength) {
        this.maxInitialLineLength = maxInitialLineLength;
        return this;
    }

    public Integer maxHeaderSize() {
        if (maxHeaderSize == null) {
            return ConfigurationProperties.maxHeaderSize();
        }
        return maxHeaderSize;
    }

    /**
     * Maximum size of HTTP request headers
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param maxHeaderSize maximum size of HTTP request headers
     */
    public Configuration maxHeaderSize(Integer maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
        return this;
    }

    public Integer maxChunkSize() {
        if (maxChunkSize == null) {
            return ConfigurationProperties.maxChunkSize();
        }
        return maxChunkSize;
    }

    /**
     * Maximum size of HTTP chunks in request or responses
     * <p>
     * The default is Integer.MAX_VALUE
     *
     * @param maxChunkSize maximum size of HTTP chunks in request or responses
     */
    public Configuration maxChunkSize(Integer maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
        return this;
    }

    public Integer maxRequestBodySize() {
        if (maxRequestBodySize == null) {
            return ConfigurationProperties.maxRequestBodySize();
        }
        return maxRequestBodySize;
    }

    /**
     * Maximum aggregated body size (in bytes) accepted on inbound HTTP/1.1 and HTTP/2 requests.
     * <p>
     * The default is 10,485,760 bytes (10 MiB).
     *
     * @param maxRequestBodySize maximum inbound request body size in bytes
     */
    public Configuration maxRequestBodySize(Integer maxRequestBodySize) {
        this.maxRequestBodySize = maxRequestBodySize;
        return this;
    }

    public Integer maxGrpcMessageSize() {
        if (maxGrpcMessageSize == null) {
            return ConfigurationProperties.maxGrpcMessageSize();
        }
        return maxGrpcMessageSize;
    }

    /**
     * Maximum size (in bytes) of a single decoded gRPC message, before and after decompression.
     * <p>
     * A request message larger than this is rejected with {@code grpc-status: 8 RESOURCE_EXHAUSTED}.
     * The default is 4,194,304 bytes (4 MiB), matching grpc-java and grpc-go.
     *
     * @param maxGrpcMessageSize maximum decoded gRPC message size in bytes
     */
    public Configuration maxGrpcMessageSize(Integer maxGrpcMessageSize) {
        this.maxGrpcMessageSize = maxGrpcMessageSize;
        return this;
    }

    public Integer maxResponseBodySize() {
        if (maxResponseBodySize == null) {
            return ConfigurationProperties.maxResponseBodySize();
        }
        return maxResponseBodySize;
    }

    /**
     * Maximum aggregated body size (in bytes) accepted on responses received from upstream
     * servers when MockServer is acting as a proxy or forwarder.
     * <p>
     * The default is 52,428,800 bytes (50 MiB).
     *
     * @param maxResponseBodySize maximum upstream response body size in bytes
     */
    public Configuration maxResponseBodySize(Integer maxResponseBodySize) {
        this.maxResponseBodySize = maxResponseBodySize;
        return this;
    }

    public Integer maxLlmConversationBodySize() {
        if (maxLlmConversationBodySize == null) {
            return ConfigurationProperties.maxLlmConversationBodySize();
        }
        return maxLlmConversationBodySize;
    }

    /**
     * Maximum body size (in bytes) for LLM conversation request bodies.
     * <p>
     * The default is 1,048,576 bytes (1 MiB). Valid range is [16384, 67108864].
     *
     * @param maxLlmConversationBodySize maximum LLM conversation body size in bytes
     */
    public Configuration maxLlmConversationBodySize(Integer maxLlmConversationBodySize) {
        this.maxLlmConversationBodySize = maxLlmConversationBodySize;
        return this;
    }

    public Boolean driftDetectionEnabled() {
        if (driftDetectionEnabled == null) {
            return ConfigurationProperties.driftDetectionEnabled();
        }
        return driftDetectionEnabled;
    }

    /**
     * Master switch for mock-drift analysis on forwarded responses. When true (the
     * default), every eligible forwarded upstream response is compared against
     * matching response-type stub expectations to record drift. When false, drift
     * analysis (and its extra per-forward expectation lookup) is skipped entirely.
     *
     * @param driftDetectionEnabled true to enable mock-drift analysis
     */
    public Configuration driftDetectionEnabled(Boolean driftDetectionEnabled) {
        this.driftDetectionEnabled = driftDetectionEnabled;
        return this;
    }

    public Double driftSampleRate() {
        if (driftSampleRate == null) {
            return ConfigurationProperties.driftSampleRate();
        }
        return driftSampleRate;
    }

    /**
     * Fraction of eligible forwarded responses to analyse for drift, in [0.0, 1.0].
     * Default 1.0 (analyse every eligible forward). A value below 1.0 analyses only
     * that fraction of forwarded responses. Out-of-range values are treated safely by
     * the sampler (&le; 0 never analyses, &ge; 1 always analyses).
     *
     * @param driftSampleRate fraction of forwarded responses to analyse, in [0.0, 1.0]
     */
    public Configuration driftSampleRate(Double driftSampleRate) {
        this.driftSampleRate = driftSampleRate;
        return this;
    }

    public Boolean driftSemanticAnalysisEnabled() {
        if (driftSemanticAnalysisEnabled == null) {
            return ConfigurationProperties.driftSemanticAnalysisEnabled();
        }
        return driftSemanticAnalysisEnabled;
    }

    /**
     * Whether to enable LLM-powered semantic drift analysis. When enabled and
     * a runtime LLM backend is available, each structural drift record is enriched
     * with a severity classification (BREAKING/WARNING/INFORMATIONAL) and an
     * explanation. Default false (opt-in).
     *
     * @param driftSemanticAnalysisEnabled true to enable semantic drift analysis
     */
    public Configuration driftSemanticAnalysisEnabled(Boolean driftSemanticAnalysisEnabled) {
        this.driftSemanticAnalysisEnabled = driftSemanticAnalysisEnabled;
        return this;
    }

    public Boolean controlPlaneAuditEnabled() {
        if (controlPlaneAuditEnabled == null) {
            return ConfigurationProperties.controlPlaneAuditEnabled();
        }
        return controlPlaneAuditEnabled;
    }

    /**
     * Whether to record an append-only, bounded, in-memory audit log of
     * control-plane mutations (who/what/when/where/outcome). Off by default. When
     * disabled, control-plane operations behave byte-for-byte identically and no
     * audit entries are stored. The audit log never stores request headers or
     * bodies — only redacted, structural metadata. Retrieve via
     * {@code GET /mockserver/audit}.
     *
     * @param controlPlaneAuditEnabled true to enable control-plane audit logging
     */
    public Configuration controlPlaneAuditEnabled(Boolean controlPlaneAuditEnabled) {
        this.controlPlaneAuditEnabled = controlPlaneAuditEnabled;
        return this;
    }

    public Integer controlPlaneAuditMaxEntries() {
        if (controlPlaneAuditMaxEntries == null) {
            return ConfigurationProperties.controlPlaneAuditMaxEntries();
        }
        return controlPlaneAuditMaxEntries;
    }

    /**
     * Maximum number of control-plane audit entries retained in the bounded
     * in-memory ring buffer. Once full, the oldest entry is evicted on each new
     * record. Default 1000.
     * <p>
     * Note: the underlying {@code AuditStore} singleton reads this value once at
     * construction (fixed capacity, like {@code DriftStore}); changing it at
     * runtime via this setter does not resize an already-constructed store.
     *
     * @param controlPlaneAuditMaxEntries maximum retained audit entries
     */
    public Configuration controlPlaneAuditMaxEntries(Integer controlPlaneAuditMaxEntries) {
        this.controlPlaneAuditMaxEntries = controlPlaneAuditMaxEntries;
        return this;
    }

    public Boolean controlPlaneAuditReads() {
        if (controlPlaneAuditReads == null) {
            return ConfigurationProperties.controlPlaneAuditReads();
        }
        return controlPlaneAuditReads;
    }

    /**
     * Whether to also audit control-plane READ operations (e.g. GET requests and
     * read-only PUTs such as {@code /retrieve} and {@code /verify}). Default
     * false — only mutations (and {@code reset}) are audited, to keep the audit
     * log focused on state changes. Has no effect unless
     * {@code controlPlaneAuditEnabled} is true.
     *
     * @param controlPlaneAuditReads true to also audit control-plane reads
     */
    public Configuration controlPlaneAuditReads(Boolean controlPlaneAuditReads) {
        this.controlPlaneAuditReads = controlPlaneAuditReads;
        return this;
    }

    public String auditLogFile() {
        if (auditLogFile == null) {
            return ConfigurationProperties.auditLogFile();
        }
        return auditLogFile;
    }

    /**
     * Optional path to a durable control-plane audit log file. When set (and
     * {@code controlPlaneAuditEnabled} is true), each recorded audit entry is
     * additionally appended as one JSON object per line ("NDJSON") to this file — a
     * restart-surviving trail that outlives the bounded in-memory ring buffer.
     * Empty/null (the default) disables the file sink; behaviour is unchanged.
     * The path is resolved once, on the first entry written.
     *
     * @param auditLogFile path to append NDJSON audit entries to, or null/empty to disable
     */
    public Configuration auditLogFile(String auditLogFile) {
        this.auditLogFile = auditLogFile;
        return this;
    }

    public Long driftResponseTimeThresholdMs() {
        if (driftResponseTimeThresholdMs == null) {
            return ConfigurationProperties.driftResponseTimeThresholdMs();
        }
        return driftResponseTimeThresholdMs;
    }

    /**
     * p95 response time threshold (in milliseconds) for performance drift detection.
     * When positive, a PERFORMANCE drift record is emitted whenever the p95 response
     * time for an expectation exceeds this threshold. Default 0 (disabled).
     *
     * @param driftResponseTimeThresholdMs threshold in milliseconds, 0 to disable
     */
    public Configuration driftResponseTimeThresholdMs(Long driftResponseTimeThresholdMs) {
        this.driftResponseTimeThresholdMs = driftResponseTimeThresholdMs;
        return this;
    }

    public Boolean driftAlertWebhookEnabled() {
        if (driftAlertWebhookEnabled == null) {
            return ConfigurationProperties.driftAlertWebhookEnabled();
        }
        return driftAlertWebhookEnabled;
    }

    /**
     * Whether to fire a fire-and-forget HTTP POST webhook when a drift record of sufficient
     * severity is stored. Off by default (opt-in). A webhook failure never affects drift
     * analysis or the served response.
     *
     * @param driftAlertWebhookEnabled true to enable the drift-alert webhook
     */
    public Configuration driftAlertWebhookEnabled(Boolean driftAlertWebhookEnabled) {
        this.driftAlertWebhookEnabled = driftAlertWebhookEnabled;
        return this;
    }

    public String driftAlertWebhookUrl() {
        if (driftAlertWebhookUrl == null) {
            return ConfigurationProperties.driftAlertWebhookUrl();
        }
        return driftAlertWebhookUrl;
    }

    /**
     * The URL the drift-alert webhook POSTs to. Empty by default; an empty URL leaves the
     * webhook disabled even when enabled is true.
     *
     * @param driftAlertWebhookUrl the webhook URL
     */
    public Configuration driftAlertWebhookUrl(String driftAlertWebhookUrl) {
        this.driftAlertWebhookUrl = driftAlertWebhookUrl;
        return this;
    }

    public String driftAlertSeverityThreshold() {
        if (driftAlertSeverityThreshold == null) {
            return ConfigurationProperties.driftAlertSeverityThreshold();
        }
        return driftAlertSeverityThreshold;
    }

    /**
     * Minimum effective severity (BREAKING, WARNING or INFORMATIONAL) at which a stored drift
     * record fires the webhook. BREAKING is the most severe; INFORMATIONAL fires on every drift.
     * Default BREAKING.
     *
     * @param driftAlertSeverityThreshold the severity threshold name
     */
    public Configuration driftAlertSeverityThreshold(String driftAlertSeverityThreshold) {
        this.driftAlertSeverityThreshold = driftAlertSeverityThreshold;
        return this;
    }

    public Long driftAlertCooldownMillis() {
        if (driftAlertCooldownMillis == null) {
            return ConfigurationProperties.driftAlertCooldownMillis();
        }
        return driftAlertCooldownMillis;
    }

    /**
     * De-dup cooldown window in milliseconds: a webhook fires at most once per
     * expectation/driftType/field signature within this window. Default 60000 (60s).
     *
     * @param driftAlertCooldownMillis the cooldown window in milliseconds
     */
    public Configuration driftAlertCooldownMillis(Long driftAlertCooldownMillis) {
        this.driftAlertCooldownMillis = driftAlertCooldownMillis;
        return this;
    }

    public Boolean useSemicolonAsQueryParameterSeparator() {
        if (useSemicolonAsQueryParameterSeparator == null) {
            return ConfigurationProperties.useSemicolonAsQueryParameterSeparator();
        }
        return useSemicolonAsQueryParameterSeparator;
    }

    /**
     * If true semicolons are treated as a separator for a query parameter string, if false the semicolon is treated as a normal character that is part of a query parameter value.
     * <p>
     * The default is true
     *
     * @param useSemicolonAsQueryParameterSeparator if true semicolons are treated as a separator for a query parameter string
     */
    public Configuration useSemicolonAsQueryParameterSeparator(Boolean useSemicolonAsQueryParameterSeparator) {
        this.useSemicolonAsQueryParameterSeparator = useSemicolonAsQueryParameterSeparator;
        return this;
    }

    public Boolean startupWarmup() {
        if (startupWarmup == null) {
            return ConfigurationProperties.startupWarmup();
        }
        return startupWarmup;
    }

    /**
     * If true (the default) MockServer sends itself a single warm-up request immediately after it starts listening, on a background thread.
     * <p>
     * The very first request handled by a freshly started MockServer is noticeably slower than every request after it (typically a few hundred milliseconds) because the request-handling code (the HTTP codec, JSON serialisation and response writers) is only loaded and initialised when it is first used. The warm-up request pays that one-off cost in the background so the first request from your test or application is fast.
     * <p>
     * The warm-up runs in the background and never delays start up. Disable it (set to false) only if you want to avoid the single extra loopback request during start up — for example in a tightly locked-down environment where MockServer must not connect to itself.
     *
     * @param startupWarmup true (the default) to send a background warm-up request after start up
     */
    public Configuration startupWarmup(Boolean startupWarmup) {
        this.startupWarmup = startupWarmup;
        return this;
    }

    public Boolean assumeAllRequestsAreHttp() {
        if (assumeAllRequestsAreHttp == null) {
            return ConfigurationProperties.assumeAllRequestsAreHttp();
        }
        return assumeAllRequestsAreHttp;
    }

    /**
     * If false requests are assumed as binary if the method isn't one of "GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE" or "CONNECT"
     * <p>
     * The default is false
     *
     * @param assumeAllRequestsAreHttp if false requests are assumed as binary if the method isn't one of "GET", "POST", "PUT", "HEAD", "OPTIONS", "PATCH", "DELETE", "TRACE" or "CONNECT"
     */
    public Configuration assumeAllRequestsAreHttp(Boolean assumeAllRequestsAreHttp) {
        this.assumeAllRequestsAreHttp = assumeAllRequestsAreHttp;
        return this;
    }

    public Boolean http2Enabled() {
        if (http2Enabled == null) {
            return ConfigurationProperties.http2Enabled();
        }
        return http2Enabled;
    }

    /**
     * If false HTTP/2 is disabled and ALPN no longer advertises h2, so HTTP/2 capable clients are
     * forced to use HTTP/1.1 (and the HTTP/2 cleartext h2c upgrade is not detected)
     * <p>
     * The default is true
     *
     * @param http2Enabled if false HTTP/2 is disabled and clients are forced to use HTTP/1.1
     */
    public Configuration http2Enabled(Boolean http2Enabled) {
        this.http2Enabled = http2Enabled;
        return this;
    }

    public Boolean streamingResponsesEnabled() {
        if (streamingResponsesEnabled == null) {
            return ConfigurationProperties.streamingResponsesEnabled();
        }
        return streamingResponsesEnabled;
    }

    /**
     * If true (the default) streaming responses (Server-Sent Events with {@code Content-Type: text/event-stream})
     * received while proxying are relayed to the client incrementally as they arrive, instead of being fully
     * buffered before being forwarded. This keeps streaming APIs (such as LLM APIs) responsive when proxied.
     * Only SSE responses are detected as streaming; ordinary chunked responses are aggregated normally.
     * <p>
     * Default is true
     *
     * @param streamingResponsesEnabled enable incremental relay of streaming responses while proxying
     */
    public Configuration streamingResponsesEnabled(Boolean streamingResponsesEnabled) {
        this.streamingResponsesEnabled = streamingResponsesEnabled;
        return this;
    }

    public Integer maxStreamingCaptureBytes() {
        if (maxStreamingCaptureBytes == null) {
            return ConfigurationProperties.maxStreamingCaptureBytes();
        }
        return Math.max(0, maxStreamingCaptureBytes);
    }

    /**
     * The maximum number of bytes of a streaming response body captured into the event log while relaying it.
     * The full stream is always relayed to the client; this only bounds how much is retained for the dashboard
     * and retrieve API. Once exceeded the logged body is truncated and flagged.
     * <p>
     * Default is 262144 (256 KB)
     *
     * @param maxStreamingCaptureBytes maximum number of streaming response body bytes captured into the event log
     */
    public Configuration maxStreamingCaptureBytes(Integer maxStreamingCaptureBytes) {
        this.maxStreamingCaptureBytes = maxStreamingCaptureBytes;
        return this;
    }

    public Integer streamIdleTimeoutSeconds() {
        if (streamIdleTimeoutSeconds == null) {
            return ConfigurationProperties.streamIdleTimeoutSeconds();
        }
        return Math.max(0, streamIdleTimeoutSeconds);
    }

    /**
     * The maximum time in seconds a streaming response connection may be idle (no chunk received) before it is
     * considered dead and closed. This replaces the fixed socket timeout for streaming responses, which would
     * otherwise terminate long-lived streams.
     * <p>
     * Default is 60 seconds
     *
     * @param streamIdleTimeoutSeconds maximum idle time in seconds between streaming response chunks
     */
    public Configuration streamIdleTimeoutSeconds(Integer streamIdleTimeoutSeconds) {
        this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
        return this;
    }

    public Boolean forwardBinaryRequestsWithoutWaitingForResponse() {
        if (forwardBinaryRequestsWithoutWaitingForResponse == null) {
            return ConfigurationProperties.forwardBinaryRequestsWithoutWaitingForResponse();
        }
        return forwardBinaryRequestsWithoutWaitingForResponse;
    }

    /**
     * If true the BinaryProxyListener is called before a response is received from the
     * remote host. This enables the proxying of messages without a response.
     * <p>
     * The default is false
     *
     * @param forwardBinaryRequestsWithoutWaitingForResponse target value
     */
    public Configuration forwardBinaryRequestsWithoutWaitingForResponse(Boolean forwardBinaryRequestsWithoutWaitingForResponse) {
        this.forwardBinaryRequestsWithoutWaitingForResponse = forwardBinaryRequestsWithoutWaitingForResponse;
        return this;
    }

    public BinaryProxyListener binaryProxyListener() {
        return binaryProxyListener;
    }

    /**
     * Set a org.mockserver.model.BinaryProxyListener called when binary content is proxied
     *
     * @param binaryProxyListener a BinaryProxyListener called when binary content is proxied
     */
    public Configuration binaryProxyListener(BinaryProxyListener binaryProxyListener) {
        this.binaryProxyListener = binaryProxyListener;
        return this;
    }

    public Boolean enableCORSForAPI() {
        if (enableCORSForAPI == null) {
            return ConfigurationProperties.enableCORSForAPI();
        }
        return enableCORSForAPI;
    }

    /**
     * Enable CORS for MockServer REST API so that the API can be used for javascript running in browsers, such as selenium
     * <p>
     * The default is false
     *
     * @param enableCORSForAPI CORS for MockServer REST API
     */
    public Configuration enableCORSForAPI(Boolean enableCORSForAPI) {
        this.enableCORSForAPI = enableCORSForAPI;
        return this;
    }

    public Boolean enableCORSForAllResponses() {
        if (enableCORSForAllResponses == null) {
            return ConfigurationProperties.enableCORSForAllResponses();
        }
        return enableCORSForAllResponses;
    }

    /**
     * Enable CORS for all responses from MockServer, including the REST API and expectation responses
     * <p>
     * The default is false
     *
     * @param enableCORSForAllResponses CORS for all responses from MockServer
     */
    public Configuration enableCORSForAllResponses(Boolean enableCORSForAllResponses) {
        this.enableCORSForAllResponses = enableCORSForAllResponses;
        return this;
    }

    public String corsAllowOrigin() {
        if (corsAllowOrigin == null) {
            return ConfigurationProperties.corsAllowOrigin();
        }
        return corsAllowOrigin;
    }

    /**
     * <p>the value used for CORS in the access-control-allow-origin header.</p>
     * <p>The default is ""</p>
     *
     * @param corsAllowOrigin the value used for CORS in the access-control-allow-methods header
     */
    public Configuration corsAllowOrigin(String corsAllowOrigin) {
        this.corsAllowOrigin = corsAllowOrigin;
        return this;
    }

    public String corsAllowMethods() {
        if (corsAllowMethods == null) {
            return ConfigurationProperties.corsAllowMethods();
        }
        return corsAllowMethods;
    }

    /**
     * <p>the value used for CORS in the access-control-allow-methods header.</p>
     * <p>The default is ""</p>
     *
     * @param corsAllowMethods the value used for CORS in the access-control-allow-methods header
     */
    public Configuration corsAllowMethods(String corsAllowMethods) {
        this.corsAllowMethods = corsAllowMethods;
        return this;
    }

    public String corsAllowHeaders() {
        if (corsAllowHeaders == null) {
            return ConfigurationProperties.corsAllowHeaders();
        }
        return corsAllowHeaders;
    }

    /**
     * <p>the value used for CORS in the access-control-allow-headers and access-control-expose-headers headers.</p>
     * <p>In addition to this default value any headers specified in the request header access-control-request-headers also get added to access-control-allow-headers and access-control-expose-headers headers in a CORS response.</p>
     * <p>The default is ""</p>
     *
     * @param corsAllowHeaders the value used for CORS in the access-control-allow-headers and access-control-expose-headers headers
     */
    public Configuration corsAllowHeaders(String corsAllowHeaders) {
        this.corsAllowHeaders = corsAllowHeaders;
        return this;
    }

    public Boolean corsAllowCredentials() {
        if (corsAllowCredentials == null) {
            return ConfigurationProperties.corsAllowCredentials();
        }
        return corsAllowCredentials;
    }

    /**
     * The value used for CORS in the access-control-allow-credentials header.
     * <p>
     * The default is false
     *
     * @param corsAllowCredentials the value used for CORS in the access-control-allow-credentials header
     */
    public Configuration corsAllowCredentials(Boolean corsAllowCredentials) {
        this.corsAllowCredentials = corsAllowCredentials;
        return this;
    }

    public Integer corsMaxAgeInSeconds() {
        if (corsMaxAgeInSeconds == null) {
            return ConfigurationProperties.corsMaxAgeInSeconds();
        }
        return corsMaxAgeInSeconds;
    }

    public String defaultResponseHeaders() {
        if (defaultResponseHeaders == null) {
            return ConfigurationProperties.defaultResponseHeaders();
        }
        return defaultResponseHeaders;
    }

    /**
     * Returns the parsed {@code defaultResponseHeaders} as an immutable list of {@link Header}s,
     * memoised so the pipe-split parse runs once per distinct resolved value rather than on every
     * response. {@link org.mockserver.responseheaders.DefaultResponseHeaders} is constructed per
     * HTTP request, so without this cache the parse would run on the hot path for every response.
     *
     * <p>The cache is keyed on the resolved value returned by {@link #defaultResponseHeaders()}, so
     * it is transparently invalidated both when {@link #defaultResponseHeaders(String)} is set to a
     * new value and when the value resolves to the global {@code ConfigurationProperties} default
     * and that changes. The empty/default case returns a shared empty list, allocating nothing.</p>
     *
     * @return an immutable list of parsed default response headers (empty when none are configured)
     */
    public List<Header> parsedDefaultResponseHeaders() {
        String source = defaultResponseHeaders();
        // source and result are read together from a single volatile holder, so the validity check
        // can never see a result paired with a mismatched source. The parse is a pure deterministic
        // function of source, so a concurrent miss can at worst cause a redundant recompute (never a
        // wrong result) and is self-correcting.
        java.util.Map.Entry<String, List<Header>> cached = parsedDefaultResponseHeaders;
        if (cached == null || !Objects.equals(source, cached.getKey())) {
            cached = new java.util.AbstractMap.SimpleImmutableEntry<>(source, DefaultResponseHeaders.parse(source));
            parsedDefaultResponseHeaders = cached;
        }
        return cached.getValue();
    }

    /**
     * <p>Default response headers that MockServer stamps onto every response it returns (mock responses, control-plane / dashboard responses, and forwarded / proxied responses) using add-if-absent semantics, so a header explicitly set on the matched response always wins.</p>
     * <p>The format is a pipe (<code>|</code>) separated list of <code>name=value</code> pairs, e.g. <code>Server=MockServer|X-Trace-Id=abc123</code>. A header value may itself contain commas; only <code>|</code> separates headers and only the first <code>=</code> in each pair separates the name from the value.</p>
     * <p>The default is "" (no default response headers are added, so behaviour is unchanged).</p>
     * <p>Passing {@code null} clears the per-instance override so the value reverts to the global {@link ConfigurationProperties#defaultResponseHeaders()} property default (consistent with other nullable string properties such as {@code corsAllowOrigin} and {@code localBoundIP}).</p>
     *
     * @param defaultResponseHeaders pipe separated list of name=value header pairs added to responses if not already present
     */
    public Configuration defaultResponseHeaders(String defaultResponseHeaders) {
        this.defaultResponseHeaders = defaultResponseHeaders;
        // invalidate the memoised parse; parsedDefaultResponseHeaders() will recompute lazily,
        // also picking up any change in the global property when defaultResponseHeaders is null
        this.parsedDefaultResponseHeaders = null;
        return this;
    }

    /**
     * The value used for CORS in the access-control-max-age header.
     * <p>
     * The default is 0
     *
     * @param corsMaxAgeInSeconds the value used for CORS in the access-control-max-age header.
     */
    public Configuration corsMaxAgeInSeconds(Integer corsMaxAgeInSeconds) {
        this.corsMaxAgeInSeconds = corsMaxAgeInSeconds;
        return this;
    }

    // template restrictions

    public String javascriptDisallowedClasses() {
        if (javascriptDisallowedClasses == null) {
            return ConfigurationProperties.javascriptDisallowedClasses();
        }
        return javascriptDisallowedClasses;
    }

    /**
     * Set comma separate list of classes not allowed to be used by javascript templates
     * <p>
     * The default is empty, and an empty value means NO class is resolvable (see
     * {@link #javascriptAllowedClasses(String)}), not "all allowed"; setting a deny-list widens that
     * default to "everything except these" and is not a security boundary. Prefer the allow-list.
     *
     * @param javascriptDisallowedClasses comma separated list of classes not allowed to be used
     */
    public Configuration javascriptDisallowedClasses(String javascriptDisallowedClasses) {
        this.javascriptDisallowedClasses = javascriptDisallowedClasses;
        return this;
    }

    public String javascriptAllowedClasses() {
        if (javascriptAllowedClasses == null) {
            return ConfigurationProperties.javascriptAllowedClasses();
        }
        return javascriptAllowedClasses;
    }

    /**
     * Set comma separated ALLOW-list of classes (or package prefixes ending in {@code .*}) that javascript
     * templates may resolve via {@code Java.type(...)}. When set it takes precedence over
     * {@code javascriptDisallowedClasses} and nothing outside the list can be resolved.
     * <p>
     * The default is empty, which means NO class can be resolved by a JavaScript template.
     * The single entry {@code *} switches class restrictions off, letting a template resolve any class as
     * earlier versions allowed by default — that makes a reachable control plane an RCE path (GHSA-7pwj-xvc2-hfpc), so
     * use it only when every template comes from a source you fully trust.
     *
     * @param javascriptAllowedClasses comma separated list of classes / package prefixes templates may use
     */
    public Configuration javascriptAllowedClasses(String javascriptAllowedClasses) {
        this.javascriptAllowedClasses = javascriptAllowedClasses;
        return this;
    }

    public String javascriptDisallowedText() {
        if (javascriptDisallowedText == null) {
            return ConfigurationProperties.javascriptDisallowedText();
        }
        return javascriptDisallowedText;
    }

    /**
     * Set comma separate list of text not allowed to be contained in javascript templates
     * <p>
     * The default is all allowed
     *
     * @param javascriptDisallowedText comma separated list of text not allowed to be contained in javascript templates
     */
    public Configuration javascriptDisallowedText(String javascriptDisallowedText) {
        this.javascriptDisallowedText = javascriptDisallowedText;
        return this;
    }

    public Long javascriptTemplateExecutionTimeout() {
        if (javascriptTemplateExecutionTimeout == null) {
            return ConfigurationProperties.javascriptTemplateExecutionTimeout();
        }
        return javascriptTemplateExecutionTimeout;
    }

    /**
     * Maximum time in milliseconds a JavaScript response template is allowed to run before it is
     * cancelled. Prevents a runaway or malicious template (for example an infinite loop) from
     * pinning a data-plane worker thread indefinitely. Default is 5000 (5 seconds). Set to 0 (or a
     * negative value) to disable the timeout and restore the previous unbounded behaviour.
     *
     * @param javascriptTemplateExecutionTimeout template execution timeout in milliseconds, 0 or negative to disable
     */
    public Configuration javascriptTemplateExecutionTimeout(Long javascriptTemplateExecutionTimeout) {
        this.javascriptTemplateExecutionTimeout = javascriptTemplateExecutionTimeout;
        return this;
    }

    public Boolean velocityDisallowClassLoading() {
        if (velocityDisallowClassLoading == null) {
            return ConfigurationProperties.velocityDisallowClassLoading();
        }
        return velocityDisallowClassLoading;
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
    public Configuration velocityDisallowClassLoading(Boolean velocityDisallowClassLoading) {
        this.velocityDisallowClassLoading = velocityDisallowClassLoading;
        return this;
    }

    public String velocityDisallowedText() {
        if (velocityDisallowedText == null) {
            return ConfigurationProperties.velocityDisallowedText();
        }
        return velocityDisallowedText;
    }

    /**
     * Set comma separate list of text not allowed to be contained in velocity templates
     * <p>
     * The default is all allowed
     *
     * @param velocityDisallowedText comma separated list of text not allowed to be contained in velocity templates
     */
    public Configuration velocityDisallowedText(String velocityDisallowedText) {
        this.velocityDisallowedText = velocityDisallowedText;
        return this;
    }

    public String mustacheDisallowedText() {
        if (mustacheDisallowedText == null) {
            return ConfigurationProperties.mustacheDisallowedText();
        }
        return mustacheDisallowedText;
    }

    /**
     * Set comma separate list of text not allowed to be contained in mustache templates
     * <p>
     * The default is all allowed
     *
     * @param mustacheDisallowedText comma separated list of text not allowed to be contained in mustache templates
     */
    public Configuration mustacheDisallowedText(String mustacheDisallowedText) {
        this.mustacheDisallowedText = mustacheDisallowedText;
        return this;
    }

    public Long templateFakerSeed() {
        if (templateFakerSeed == null) {
            return ConfigurationProperties.templateFakerSeed();
        }
        return templateFakerSeed;
    }

    /**
     * Seed for the template {@code faker} sample-data helper (net.datafaker).
     * <p>
     * The default is 0, which leaves faker unseeded so it produces different, time/random-based
     * sample values on every render. Set a non-zero value to seed faker deterministically so
     * faker-driven templates generate reproducible fixtures across runs.
     *
     * @param templateFakerSeed faker seed, 0 to leave faker unseeded (random)
     */
    public Configuration templateFakerSeed(Long templateFakerSeed) {
        this.templateFakerSeed = templateFakerSeed;
        return this;
    }

    public String initializationClass() {
        if (initializationClass == null) {
            return ConfigurationProperties.initializationClass();
        }
        return initializationClass;
    }

    /**
     * The class (and package) used to initialize expectations in MockServer at startup, if set MockServer will load and call this class to initialize expectations when is starts.
     * <p>
     * The default is null
     *
     * @param initializationClass class (and package) used to initialize expectations in MockServer at startup
     */
    public Configuration initializationClass(String initializationClass) {
        this.initializationClass = initializationClass;
        return this;
    }

    public String initializationJsonPath() {
        if (initializationJsonPath == null) {
            return ConfigurationProperties.initializationJsonPath();
        }
        return initializationJsonPath;
    }

    /**
     * <p>The path to the json file used to initialize expectations in MockServer at startup, if set MockServer will load this file and initialise expectations for each item in the file when is starts.</p>
     * <p>The expected format of the file is a JSON array of expectations, as per the <a target="_blank" href="https://app.swaggerhub.com/apis/jamesdbloom/mock-server-openapi/5.15.x#/Expectations" target="_blank">REST API format</a></p>
     * <p>To watch multiple files use a file globs as documented here: https://mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_json_glob_patterns</p>
     *
     * @param initializationJsonPath path to the json file used to initialize expectations in MockServer at startup
     */
    public Configuration initializationJsonPath(String initializationJsonPath) {
        this.initializationJsonPath = initializationJsonPath;
        return this;
    }

    public String initializationOpenAPIPath() {
        if (initializationOpenAPIPath == null) {
            return ConfigurationProperties.initializationOpenAPIPath();
        }
        return initializationOpenAPIPath;
    }

    /**
     * <p>The path to the OpenAPI spec file used to initialize expectations in MockServer at startup, if set MockServer will load this file and create expectations for each operation when it starts.</p>
     * <p>The file can be a YAML (.yaml, .yml) or JSON (.json) OpenAPI v3 specification.</p>
     * <p>To watch multiple files use file globs as documented here: https://mock-server.com/mock_server/initializing_expectations.html#expectation_initializer_json_glob_patterns</p>
     *
     * @param initializationOpenAPIPath path to the OpenAPI spec file used to initialize expectations in MockServer at startup
     */
    public Configuration initializationOpenAPIPath(String initializationOpenAPIPath) {
        this.initializationOpenAPIPath = initializationOpenAPIPath;
        return this;
    }

    public String openAPIContextPathPrefix() {
        if (openAPIContextPathPrefix == null) {
            return ConfigurationProperties.openAPIContextPathPrefix();
        }
        return openAPIContextPathPrefix;
    }

    /**
     * <p>A path prefix to add to all paths generated from OpenAPI specifications.</p>
     * <p>For example, if set to "/api/v1" then a path "/pets" from the spec becomes "/api/v1/pets".</p>
     *
     * @param openAPIContextPathPrefix the path prefix to add to OpenAPI paths
     */
    public Configuration openAPIContextPathPrefix(String openAPIContextPathPrefix) {
        this.openAPIContextPathPrefix = openAPIContextPathPrefix;
        return this;
    }

    public Boolean openAPIResponseValidation() {
        if (openAPIResponseValidation == null) {
            return ConfigurationProperties.openAPIResponseValidation();
        }
        return openAPIResponseValidation;
    }

    /**
     * <p>If enabled MockServer will validate that mock responses conform to the OpenAPI spec schema they were generated from.</p>
     * <p>Validation is advisory only - responses are still returned to the client even if validation fails.</p>
     *
     * <p>The default is false</p>
     *
     * @param openAPIResponseValidation if enabled mock responses will be validated against the OpenAPI spec schema
     */
    public Configuration openAPIResponseValidation(Boolean openAPIResponseValidation) {
        this.openAPIResponseValidation = openAPIResponseValidation;
        return this;
    }

    public Boolean enforceResponseValidationForMocks() {
        if (enforceResponseValidationForMocks == null) {
            return ConfigurationProperties.enforceResponseValidationForMocks();
        }
        return enforceResponseValidationForMocks;
    }

    /**
     * <p>If false (the default) OpenAPI response validation of mock responses is advisory only -
     * validation failures are logged but the response is still returned to the client.</p>
     * <p>If true a mock response that fails OpenAPI response validation is replaced with a 502 error
     * describing the violations, matching the enforcement already available on the validation-proxy
     * path ({@code validateProxyEnforce}).</p>
     * <p>This flag only has any effect when {@code openAPIResponseValidation} is also enabled.</p>
     *
     * @param enforceResponseValidationForMocks if enabled mock responses that fail OpenAPI response validation are replaced with a 502 error
     */
    public Configuration enforceResponseValidationForMocks(Boolean enforceResponseValidationForMocks) {
        this.enforceResponseValidationForMocks = enforceResponseValidationForMocks;
        return this;
    }

    public Boolean validateRequestsAgainstOpenApiSpec() {
        if (validateRequestsAgainstOpenApiSpec == null) {
            return ConfigurationProperties.validateRequestsAgainstOpenApiSpec();
        }
        return validateRequestsAgainstOpenApiSpec;
    }

    /**
     * <p>If false (the default) incoming requests matched by an OpenAPI-backed mock expectation are
     * not validated against the spec — behaviour is exactly as before.</p>
     * <p>If true, when a request matches an expectation created from an OpenAPI spec
     * ({@code specUrlOrPayload}), the incoming request is validated against that spec before the
     * matched action is dispatched. A request that violates the spec is rejected with a 400 status
     * code describing the violations, instead of the mock response.</p>
     *
     * @param validateRequestsAgainstOpenApiSpec if enabled, requests matched by an OpenAPI-backed mock that violate the spec are rejected with a 400 error
     */
    public Configuration validateRequestsAgainstOpenApiSpec(Boolean validateRequestsAgainstOpenApiSpec) {
        this.validateRequestsAgainstOpenApiSpec = validateRequestsAgainstOpenApiSpec;
        return this;
    }

    public String validateProxyOpenAPISpec() {
        if (validateProxyOpenAPISpec == null) {
            return ConfigurationProperties.validateProxyOpenAPISpec();
        }
        return validateProxyOpenAPISpec;
    }

    /**
     * <p>When set to an OpenAPI spec URL, file path, or inline JSON/YAML, MockServer validates every forwarded/proxied
     * request and its upstream response against the spec and records violations as log events.</p>
     *
     * <p>The default is empty (disabled)</p>
     *
     * @param validateProxyOpenAPISpec the OpenAPI spec URL, file path, or inline payload to validate against
     */
    public Configuration validateProxyOpenAPISpec(String validateProxyOpenAPISpec) {
        this.validateProxyOpenAPISpec = validateProxyOpenAPISpec;
        return this;
    }

    public Boolean validateProxyEnforce() {
        if (validateProxyEnforce == null) {
            return ConfigurationProperties.validateProxyEnforce();
        }
        return validateProxyEnforce;
    }

    /**
     * <p>When enabled (and {@code validateProxyOpenAPISpec} is set), forwarded requests that violate the OpenAPI spec
     * are rejected with a 400 status code, and upstream responses that violate the spec are replaced with a 502.</p>
     *
     * <p>The default is false</p>
     *
     * @param validateProxyEnforce if enabled, non-conformant forwarded traffic is blocked
     */
    public Configuration validateProxyEnforce(Boolean validateProxyEnforce) {
        this.validateProxyEnforce = validateProxyEnforce;
        return this;
    }

    public Boolean generateRealisticExampleValues() {
        if (generateRealisticExampleValues == null) {
            return ConfigurationProperties.generateRealisticExampleValues();
        }
        return generateRealisticExampleValues;
    }

    /**
     * <p>If enabled, OpenAPI example generation uses realistic, schema/format-aware values (via Datafaker) instead of static placeholder strings.</p>
     * <p>When disabled (the default), the existing static example values are used (e.g. "some_string_value", "some_email@mockserver.com").</p>
     *
     * <p>The default is false</p>
     *
     * @param generateRealisticExampleValues if enabled OpenAPI examples will use realistic generated values
     */
    public Configuration generateRealisticExampleValues(Boolean generateRealisticExampleValues) {
        this.generateRealisticExampleValues = generateRealisticExampleValues;
        return this;
    }

    public Boolean watchInitializationJson() {
        if (watchInitializationJson == null) {
            return ConfigurationProperties.watchInitializationJson();
        }
        return watchInitializationJson;
    }

    /**
     * <p>If enabled the initialization json file will be watched for changes, any changes found will result in expectations being created, remove or updated by matching against their key.</p>
     * <p>If duplicate keys exist only the last duplicate key in the file will be processed and all duplicates except the last duplicate will be removed.</p>
     * <p>The order of expectations in the file is the order in which they are created if they are new, however, re-ordering existing expectations does not change the order they are matched against incoming requests.</p>
     *
     * <p>The default is false</p>
     *
     * @param watchInitializationJson if enabled the initialization json file will be watched for changes
     */
    public Configuration watchInitializationJson(Boolean watchInitializationJson) {
        this.watchInitializationJson = watchInitializationJson;
        return this;
    }

    public Boolean failOnInitializationError() {
        if (failOnInitializationError == null) {
            return ConfigurationProperties.failOnInitializationError();
        }
        return failOnInitializationError;
    }

    /**
     * <p>If enabled a failure to load any expectation initializer (a malformed initialization JSON / OpenAPI file or a broken initialization class) will fail server startup with an exception rather than logging a warning and continuing with zero expectations from that source.</p>
     *
     * <p>The default is false (a failed initializer is logged at WARN and startup continues).</p>
     *
     * @param failOnInitializationError if enabled a failed expectation initializer load fails server startup
     */
    public Configuration failOnInitializationError(Boolean failOnInitializationError) {
        this.failOnInitializationError = failOnInitializationError;
        return this;
    }

    public Boolean persistExpectations() {
        if (persistExpectations == null) {
            return ConfigurationProperties.persistExpectations();
        }
        return persistExpectations;
    }

    /**
     * Enable the persisting of expectations as json, which is updated whenever the expectation state is updated (i.e. add, clear, expires, etc.)
     * <p>
     * The default is false
     *
     * @param persistExpectations the persisting of expectations as json
     */
    public Configuration persistExpectations(Boolean persistExpectations) {
        this.persistExpectations = persistExpectations;
        return this;
    }

    public String persistedExpectationsPath() {
        if (persistedExpectationsPath == null) {
            return ConfigurationProperties.persistedExpectationsPath();
        }
        return persistedExpectationsPath;
    }

    /**
     * The file path used to save persisted expectations as json, which is updated whenever the expectation state is updated (i.e. add, clear, expires, etc.)
     * <p>
     * The default is "persistedExpectations.json"
     *
     * @param persistedExpectationsPath file path used to save persisted expectations as json
     */
    public Configuration persistedExpectationsPath(String persistedExpectationsPath) {
        this.persistedExpectationsPath = persistedExpectationsPath;
        return this;
    }

    public Boolean persistRecordedExpectations() {
        if (persistRecordedExpectations == null) {
            return ConfigurationProperties.persistRecordedExpectations();
        }
        return persistRecordedExpectations;
    }

    /**
     * Enable the persisting of recorded expectations (proxy traffic) as json, which is updated whenever a new request is forwarded
     * <p>
     * The default is false
     *
     * @param persistRecordedExpectations the persisting of recorded expectations as json
     */
    public Configuration persistRecordedExpectations(Boolean persistRecordedExpectations) {
        this.persistRecordedExpectations = persistRecordedExpectations;
        return this;
    }

    public String persistedRecordedExpectationsPath() {
        if (persistedRecordedExpectationsPath == null) {
            return ConfigurationProperties.persistedRecordedExpectationsPath();
        }
        return persistedRecordedExpectationsPath;
    }

    /**
     * The file path used to save persisted recorded expectations as json, which is updated whenever a new request is forwarded
     * <p>
     * The default is "persistedRecordedExpectations.json"
     *
     * @param persistedRecordedExpectationsPath file path used to save persisted recorded expectations as json
     */
    public Configuration persistedRecordedExpectationsPath(String persistedRecordedExpectationsPath) {
        this.persistedRecordedExpectationsPath = persistedRecordedExpectationsPath;
        return this;
    }

    public Boolean persistRecordedRequestsToDisk() {
        if (persistRecordedRequestsToDisk == null) {
            return ConfigurationProperties.persistRecordedRequestsToDisk();
        }
        return persistRecordedRequestsToDisk;
    }

    /**
     * Enable the persisting of recorded requests (captured traffic) to disk
     * <p>
     * The default is false
     *
     * @param persistRecordedRequestsToDisk the persisting of recorded requests to disk
     */
    public Configuration persistRecordedRequestsToDisk(Boolean persistRecordedRequestsToDisk) {
        this.persistRecordedRequestsToDisk = persistRecordedRequestsToDisk;
        return this;
    }

    public String persistedRecordedRequestsPath() {
        if (persistedRecordedRequestsPath == null) {
            return ConfigurationProperties.persistedRecordedRequestsPath();
        }
        return persistedRecordedRequestsPath;
    }

    /**
     * The file path used to save persisted recorded requests, which is updated whenever a new request is captured
     * <p>
     * The default is "recordedRequests.ndjson"
     *
     * @param persistedRecordedRequestsPath file path used to save persisted recorded requests
     */
    public Configuration persistedRecordedRequestsPath(String persistedRecordedRequestsPath) {
        this.persistedRecordedRequestsPath = persistedRecordedRequestsPath;
        return this;
    }

    /**
     * Returns the state backend type. Currently only "memory" is supported
     * (default). Phase 2b will add "infinispan" for clustered state.
     */
    public String stateBackend() {
        if (stateBackend == null) {
            return ConfigurationProperties.stateBackend();
        }
        return stateBackend;
    }

    /**
     * Sets the state backend type. Currently only "memory" is supported.
     *
     * @param stateBackend the backend type (e.g. "memory")
     */
    public Configuration stateBackend(String stateBackend) {
        this.stateBackend = stateBackend;
        return this;
    }

    /**
     * Returns the blob store type. "filesystem" (default) delegates to the
     * existing file persistence paths so on-disk behaviour is unchanged;
     * "memory" keeps blobs in-memory only (lost on process exit).
     */
    public String blobStoreType() {
        if (blobStoreType == null) {
            return ConfigurationProperties.blobStoreType();
        }
        return blobStoreType;
    }

    /**
     * Sets the blob store type.
     *
     * @param blobStoreType the blob store type (e.g. "memory", "filesystem")
     */
    public Configuration blobStoreType(String blobStoreType) {
        this.blobStoreType = blobStoreType;
        return this;
    }

    // --- cloud blob store configuration ---

    /**
     * Returns the cloud blob store bucket name (S3 bucket or GCS bucket).
     */
    public String blobStoreBucket() {
        if (blobStoreBucket == null) {
            return ConfigurationProperties.blobStoreBucket();
        }
        return blobStoreBucket;
    }

    public Configuration blobStoreBucket(String blobStoreBucket) {
        this.blobStoreBucket = blobStoreBucket;
        return this;
    }

    /**
     * Returns the cloud blob store region (e.g. "us-east-1" for S3).
     */
    public String blobStoreRegion() {
        if (blobStoreRegion == null) {
            return ConfigurationProperties.blobStoreRegion();
        }
        return blobStoreRegion;
    }

    public Configuration blobStoreRegion(String blobStoreRegion) {
        this.blobStoreRegion = blobStoreRegion;
        return this;
    }

    /**
     * Returns the cloud blob store endpoint override URL (e.g. MinIO
     * endpoint for S3-compatible stores, or fake-gcs-server URL).
     */
    public String blobStoreEndpoint() {
        if (blobStoreEndpoint == null) {
            return ConfigurationProperties.blobStoreEndpoint();
        }
        return blobStoreEndpoint;
    }

    public Configuration blobStoreEndpoint(String blobStoreEndpoint) {
        this.blobStoreEndpoint = blobStoreEndpoint;
        return this;
    }

    /**
     * Returns the key prefix for cloud blob store objects. All blob keys
     * are prefixed with this value (e.g. "mockserver/" to namespace
     * objects within a shared bucket).
     */
    public String blobStoreKeyPrefix() {
        if (blobStoreKeyPrefix == null) {
            return ConfigurationProperties.blobStoreKeyPrefix();
        }
        return blobStoreKeyPrefix;
    }

    public Configuration blobStoreKeyPrefix(String blobStoreKeyPrefix) {
        this.blobStoreKeyPrefix = blobStoreKeyPrefix;
        return this;
    }

    /**
     * Returns the explicit access key ID for cloud blob store
     * authentication (optional -- falls back to default credential chain).
     */
    public String blobStoreAccessKeyId() {
        if (blobStoreAccessKeyId == null) {
            return ConfigurationProperties.blobStoreAccessKeyId();
        }
        return blobStoreAccessKeyId;
    }

    public Configuration blobStoreAccessKeyId(String blobStoreAccessKeyId) {
        this.blobStoreAccessKeyId = blobStoreAccessKeyId;
        return this;
    }

    /**
     * Returns the explicit secret access key for cloud blob store
     * authentication (optional -- falls back to default credential chain).
     */
    public String blobStoreSecretAccessKey() {
        if (blobStoreSecretAccessKey == null) {
            return ConfigurationProperties.blobStoreSecretAccessKey();
        }
        return blobStoreSecretAccessKey;
    }

    public Configuration blobStoreSecretAccessKey(String blobStoreSecretAccessKey) {
        this.blobStoreSecretAccessKey = blobStoreSecretAccessKey;
        return this;
    }

    /**
     * Returns the Azure Blob Storage container name.
     */
    public String blobStoreContainer() {
        if (blobStoreContainer == null) {
            return ConfigurationProperties.blobStoreContainer();
        }
        return blobStoreContainer;
    }

    public Configuration blobStoreContainer(String blobStoreContainer) {
        this.blobStoreContainer = blobStoreContainer;
        return this;
    }

    /**
     * Returns the Azure Blob Storage connection string.
     */
    public String blobStoreConnectionString() {
        if (blobStoreConnectionString == null) {
            return ConfigurationProperties.blobStoreConnectionString();
        }
        return blobStoreConnectionString;
    }

    public Configuration blobStoreConnectionString(String blobStoreConnectionString) {
        this.blobStoreConnectionString = blobStoreConnectionString;
        return this;
    }

    /**
     * Returns the GCS project ID (optional -- falls back to default
     * project from application default credentials).
     */
    public String blobStoreProjectId() {
        if (blobStoreProjectId == null) {
            return ConfigurationProperties.blobStoreProjectId();
        }
        return blobStoreProjectId;
    }

    public Configuration blobStoreProjectId(String blobStoreProjectId) {
        this.blobStoreProjectId = blobStoreProjectId;
        return this;
    }

    /**
     * Returns the maximum number of seconds MockServer waits, during startup, for the
     * persisted-expectations document to be read back from a cloud blob store before
     * giving up and continuing to start. The default is 10 seconds; 0 or less skips the
     * startup restore entirely.
     */
    public Integer blobStoreRestoreTimeoutSeconds() {
        if (blobStoreRestoreTimeoutSeconds == null) {
            return ConfigurationProperties.blobStoreRestoreTimeoutSeconds();
        }
        return blobStoreRestoreTimeoutSeconds;
    }

    /**
     * Sets the startup blob-store restore deadline. The startup read happens before any
     * listening port is bound, so this bounds how long an unreachable blob-store endpoint
     * can delay startup.
     *
     * @param blobStoreRestoreTimeoutSeconds startup blob-store restore deadline, in seconds
     */
    public Configuration blobStoreRestoreTimeoutSeconds(Integer blobStoreRestoreTimeoutSeconds) {
        this.blobStoreRestoreTimeoutSeconds = blobStoreRestoreTimeoutSeconds;
        return this;
    }

    // --- clustering (G10 phase 2c) ---

    /**
     * Returns whether clustering is enabled. When {@code true} and
     * {@code stateBackend=infinispan}, the Infinispan backend starts
     * a JGroups transport for multi-node state replication. Default is
     * {@code false} (single-node LOCAL mode, identical to today).
     */
    public boolean clusterEnabled() {
        if (clusterEnabled == null) {
            return ConfigurationProperties.clusterEnabled();
        }
        return clusterEnabled;
    }

    /**
     * Enables or disables clustering.
     *
     * @param clusterEnabled true to enable JGroups transport
     */
    public Configuration clusterEnabled(boolean clusterEnabled) {
        this.clusterEnabled = clusterEnabled;
        return this;
    }

    /**
     * Returns the cluster name used as the JGroups cluster identifier.
     * All nodes with the same cluster name form a single cluster.
     * Default is {@code "mockserver-cluster"}.
     */
    public String clusterName() {
        if (clusterName == null) {
            return ConfigurationProperties.clusterName();
        }
        return clusterName;
    }

    /**
     * Sets the JGroups cluster name.
     *
     * @param clusterName the cluster identifier
     */
    public Configuration clusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
    }

    /**
     * Returns the optional path to a JGroups XML transport configuration
     * file. When set, this overrides the default in-JVM loopback stack.
     * When {@code null}, the Infinispan module uses its built-in
     * embedded-friendly JGroups configuration.
     */
    public String clusterTransportConfig() {
        if (clusterTransportConfig == null) {
            return ConfigurationProperties.clusterTransportConfig();
        }
        return clusterTransportConfig;
    }

    /**
     * Sets the path to a custom JGroups XML transport configuration.
     *
     * @param clusterTransportConfig path to JGroups XML, or null for default
     */
    public Configuration clusterTransportConfig(String clusterTransportConfig) {
        this.clusterTransportConfig = clusterTransportConfig;
        return this;
    }

    /**
     * Returns whether per-expectation {@code Times} limits are enforced
     * cluster-wide via a shared backend compare-and-set (CAS). Default is
     * {@code true} — a {@code Times.exactly(N)} expectation serves exactly N
     * times across the whole fleet. Only relevant when a clustered backend
     * is active.
     * <p>
     * When {@code false}, limited-{@code Times} matching falls back to the
     * node-local fast path (no synchronous backend round-trip on the request
     * worker thread), trading the fleet-wide exactly-N guarantee for lower,
     * more predictable matching latency. See
     * {@code RequestMatchers.consumeTimesViaBackendCas}.
     */
    public boolean clusterSharedTimesEnabled() {
        if (clusterSharedTimesEnabled == null) {
            return ConfigurationProperties.clusterSharedTimesEnabled();
        }
        return clusterSharedTimesEnabled;
    }

    /**
     * Enables or disables cluster-wide shared-{@code Times} CAS enforcement.
     *
     * @param clusterSharedTimesEnabled {@code true} (default) to enforce
     *                                  {@code Times} limits fleet-wide via
     *                                  backend CAS; {@code false} for
     *                                  node-local {@code Times}
     */
    public Configuration clusterSharedTimesEnabled(boolean clusterSharedTimesEnabled) {
        this.clusterSharedTimesEnabled = clusterSharedTimesEnabled;
        return this;
    }

    /**
     * Returns whether {@code verify}/{@code verifySequence} and
     * {@code retrieve} of {@code REQUESTS}/{@code REQUEST_RESPONSES} aggregate
     * (scatter-gather) across cluster members. Default is {@code false}
     * (per-node behaviour — each node sees only the traffic that hit it).
     * <p>
     * Only meaningful in a clustered deployment behind a load balancer, where
     * the event log is per-node. When {@code true}, a verify/retrieve on any
     * node queries every configured peer's LOCAL log (see
     * {@link #clusterVerifyFanInPeers()}), merges the results, and evaluates
     * the verification against the fleet-wide total. Count-based request
     * verification ({@code exactly}/{@code atLeast}/{@code atMost}/
     * {@code between}) and {@code REQUESTS}/{@code REQUEST_RESPONSES} retrieve
     * are aggregated; cross-node {@code verifySequence} ordering is a
     * documented boundary and stays node-local. See
     * {@code org.mockserver.cluster.ClusterFanIn}.
     */
    public boolean clusterVerifyFanIn() {
        if (clusterVerifyFanIn == null) {
            return ConfigurationProperties.clusterVerifyFanIn();
        }
        return clusterVerifyFanIn;
    }

    /**
     * Enables or disables cluster verify/retrieve fan-in.
     *
     * @param clusterVerifyFanIn {@code true} to aggregate verify/retrieve
     *                           across cluster peers; {@code false} (default)
     *                           for per-node behaviour
     */
    public Configuration clusterVerifyFanIn(boolean clusterVerifyFanIn) {
        this.clusterVerifyFanIn = clusterVerifyFanIn;
        return this;
    }

    /**
     * Returns the comma-separated list of peer control-plane base URLs (e.g.
     * {@code http://node-b:1080,http://node-c:1080}) queried when
     * {@link #clusterVerifyFanIn()} is enabled. Should list the OTHER nodes in
     * the fleet (excluding this node). Default is empty (no peers — fan-in is
     * a no-op even when enabled).
     */
    public String clusterVerifyFanInPeers() {
        if (clusterVerifyFanInPeers == null) {
            return ConfigurationProperties.clusterVerifyFanInPeers();
        }
        return clusterVerifyFanInPeers;
    }

    /**
     * Sets the comma-separated list of peer control-plane base URLs for
     * verify/retrieve fan-in.
     *
     * @param clusterVerifyFanInPeers comma-separated peer base URLs
     */
    public Configuration clusterVerifyFanInPeers(String clusterVerifyFanInPeers) {
        this.clusterVerifyFanInPeers = clusterVerifyFanInPeers;
        return this;
    }

    /**
     * Returns the credential the verify/retrieve fan-in peer accessor presents on
     * cross-node queries. It is sent verbatim as the control-plane
     * {@code Authorization} header (e.g. {@code Bearer <jwt>}), so an authenticated
     * cluster (control-plane bearer/JWT/OIDC) accepts the fan-in query rather than
     * rejecting it with 401/403. Default is empty (no credential — unchanged
     * behaviour). All nodes must share the same token / trust.
     */
    public String clusterFanInPeerAuthToken() {
        if (clusterFanInPeerAuthToken == null) {
            return ConfigurationProperties.clusterFanInPeerAuthToken();
        }
        return clusterFanInPeerAuthToken;
    }

    /**
     * Sets the credential the verify/retrieve fan-in peer accessor presents on
     * cross-node queries (sent verbatim as the control-plane {@code Authorization}
     * header, so include the scheme, e.g. {@code Bearer <jwt>}).
     *
     * @param clusterFanInPeerAuthToken the control-plane Authorization header value
     */
    public Configuration clusterFanInPeerAuthToken(String clusterFanInPeerAuthToken) {
        this.clusterFanInPeerAuthToken = clusterFanInPeerAuthToken;
        return this;
    }

    public Integer maximumNumberOfRequestToReturnInVerificationFailure() {
        if (maximumNumberOfRequestToReturnInVerificationFailure == null) {
            return ConfigurationProperties.maximumNumberOfRequestToReturnInVerificationFailure();
        }
        return maximumNumberOfRequestToReturnInVerificationFailure;
    }

    /**
     * The maximum number of requests to return in verification failure result, if more expectations are found the failure result does not list them separately
     *
     * @param maximumNumberOfRequestToReturnInVerificationFailure maximum number of expectations to return in verification failure result
     */
    public Configuration maximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerificationFailure) {
        this.maximumNumberOfRequestToReturnInVerificationFailure = maximumNumberOfRequestToReturnInVerificationFailure;
        return this;
    }

    public Boolean detailedVerificationFailures() {
        if (detailedVerificationFailures == null) {
            return ConfigurationProperties.detailedVerificationFailures();
        }
        return detailedVerificationFailures;
    }

    /**
     * If true (the default) verification failure messages include a detailed diff showing which fields did not match for the closest matching request.
     *
     * @param detailedVerificationFailures enabled detailed verification failure messages
     */
    public Configuration detailedVerificationFailures(Boolean detailedVerificationFailures) {
        this.detailedVerificationFailures = detailedVerificationFailures;
        return this;
    }

    public Boolean failVerificationOnEvictedLog() {
        if (failVerificationOnEvictedLog == null) {
            return ConfigurationProperties.failVerificationOnEvictedLog();
        }
        return failVerificationOnEvictedLog;
    }

    /**
     * If true (the default) a verification that asserts an upper bound (never(), atMost(n), exactly(n), between(a,b)) fails rather than passes if the event log has evicted entries, because absence cannot be proven once evidence has been discarded. Set to false to restore the previous behaviour where such verifications could pass on an incomplete log.
     *
     * @param failVerificationOnEvictedLog enabled failing upper-bound verifications when the event log has evicted entries
     */
    public Configuration failVerificationOnEvictedLog(Boolean failVerificationOnEvictedLog) {
        this.failVerificationOnEvictedLog = failVerificationOnEvictedLog;
        return this;
    }

    public Boolean attachMismatchDiagnosticToResponse() {
        if (attachMismatchDiagnosticToResponse == null) {
            return ConfigurationProperties.attachMismatchDiagnosticToResponse();
        }
        return attachMismatchDiagnosticToResponse;
    }

    /**
     * If true, when no expectation matches an incoming request the 404 response will include a diagnostic header (x-mockserver-closest-match)
     * and a JSON body describing which expectation was closest to matching and which fields differed. Defaults to false.
     *
     * @param attachMismatchDiagnosticToResponse enable mismatch diagnostic in unmatched responses
     */
    public Configuration attachMismatchDiagnosticToResponse(Boolean attachMismatchDiagnosticToResponse) {
        this.attachMismatchDiagnosticToResponse = attachMismatchDiagnosticToResponse;
        return this;
    }

    public Boolean closestMatchHintEnabled() {
        if (closestMatchHintEnabled == null) {
            return ConfigurationProperties.closestMatchHintEnabled();
        }
        return closestMatchHintEnabled;
    }

    /**
     * If true (the default), when no expectation matches an incoming request the data-plane 404 response carries a
     * single concise diagnostic header (x-mockserver-closest-match-hint) naming the closest expectation and the first
     * field that differed. The hint is header-only and length-bounded — no expectation body is leaked. Set to false to
     * suppress it.
     *
     * @param closestMatchHintEnabled enable the closest-match hint header on unmatched 404 responses
     */
    public Configuration closestMatchHintEnabled(Boolean closestMatchHintEnabled) {
        this.closestMatchHintEnabled = closestMatchHintEnabled;
        return this;
    }

    public Boolean attemptToProxyIfNoMatchingExpectation() {
        if (attemptToProxyIfNoMatchingExpectation == null) {
            return ConfigurationProperties.attemptToProxyIfNoMatchingExpectation();
        }
        return attemptToProxyIfNoMatchingExpectation;
    }

    /**
     * If true (the default) when no matching expectation is found, and the host header of the request does not match MockServer's host, then MockServer attempts to proxy the request if that fails then a 404 is returned.
     * If false when no matching expectation is found, and MockServer is not being used as a proxy, then MockServer always returns a 404 immediately.
     *
     * @param attemptToProxyIfNoMatchingExpectation enables automatically attempted proxying of request that don't match an expectation and look like they should be proxied
     */
    public Configuration attemptToProxyIfNoMatchingExpectation(Boolean attemptToProxyIfNoMatchingExpectation) {
        this.attemptToProxyIfNoMatchingExpectation = attemptToProxyIfNoMatchingExpectation;
        return this;
    }

    public InetSocketAddress forwardHttpProxy() {
        if (forwardHttpProxy == null) {
            return ConfigurationProperties.forwardHttpProxy();
        }
        return forwardHttpProxy;
    }

    /**
     * Use HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     * <p>
     * The default is null
     *
     * @param forwardHttpProxy host and port for HTTP proxy (i.e. via Host header) for all outbound / forwarded requests
     */
    public Configuration forwardHttpProxy(InetSocketAddress forwardHttpProxy) {
        this.forwardHttpProxy = forwardHttpProxy;
        return this;
    }

    public InetSocketAddress forwardHttpsProxy() {
        if (forwardHttpsProxy == null) {
            return ConfigurationProperties.forwardHttpsProxy();
        }
        return forwardHttpsProxy;
    }

    /**
     * Use HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests, supports TLS tunnelling of HTTPS requests
     * <p>
     * The default is null
     *
     * @param forwardHttpsProxy host and port for HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests
     */
    public Configuration forwardHttpsProxy(InetSocketAddress forwardHttpsProxy) {
        this.forwardHttpsProxy = forwardHttpsProxy;
        return this;
    }

    public InetSocketAddress forwardSocksProxy() {
        if (forwardSocksProxy == null) {
            return ConfigurationProperties.forwardSocksProxy();
        }
        return forwardSocksProxy;
    }

    /**
     * Use SOCKS proxy for all outbound / forwarded requests, support TLS tunnelling of TCP connections
     * <p>
     * The default is null
     *
     * @param forwardSocksProxy host and port for SOCKS proxy for all outbound / forwarded requests
     */
    public Configuration forwardSocksProxy(InetSocketAddress forwardSocksProxy) {
        this.forwardSocksProxy = forwardSocksProxy;
        return this;
    }

    public String forwardProxyAuthenticationUsername() {
        if (forwardProxyAuthenticationUsername == null) {
            return ConfigurationProperties.forwardProxyAuthenticationUsername();
        }
        return forwardProxyAuthenticationUsername;
    }

    /**
     * <p>Username for proxy authentication when using HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is null
     *
     * @param forwardProxyAuthenticationUsername username for proxy authentication
     */
    public Configuration forwardProxyAuthenticationUsername(String forwardProxyAuthenticationUsername) {
        this.forwardProxyAuthenticationUsername = forwardProxyAuthenticationUsername;
        return this;
    }

    public String forwardProxyAuthenticationPassword() {
        if (forwardProxyAuthenticationPassword == null) {
            return ConfigurationProperties.forwardProxyAuthenticationPassword();
        }
        return forwardProxyAuthenticationPassword;
    }

    /**
     * <p>Password for proxy authentication when using HTTPS proxy (i.e. HTTP CONNECT) for all outbound / forwarded requests</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is null
     *
     * @param forwardProxyAuthenticationPassword password for proxy authentication
     */
    public Configuration forwardProxyAuthenticationPassword(String forwardProxyAuthenticationPassword) {
        this.forwardProxyAuthenticationPassword = forwardProxyAuthenticationPassword;
        return this;
    }

    public String proxyAuthenticationRealm() {
        if (proxyAuthenticationRealm == null) {
            return ConfigurationProperties.proxyAuthenticationRealm();
        }
        return proxyAuthenticationRealm;
    }

    /**
     * The authentication realm for proxy authentication to MockServer
     *
     * @param proxyAuthenticationRealm the authentication realm for proxy authentication
     */
    public Configuration proxyAuthenticationRealm(String proxyAuthenticationRealm) {
        this.proxyAuthenticationRealm = proxyAuthenticationRealm;
        return this;
    }

    public String proxyAuthenticationUsername() {
        if (proxyAuthenticationUsername == null) {
            return ConfigurationProperties.proxyAuthenticationUsername();
        }
        return proxyAuthenticationUsername;
    }

    /**
     * <p>The required username for proxy authentication to MockServer</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is ""
     *
     * @param proxyAuthenticationUsername required username for proxy authentication to MockServer
     */
    public Configuration proxyAuthenticationUsername(String proxyAuthenticationUsername) {
        this.proxyAuthenticationUsername = proxyAuthenticationUsername;
        return this;
    }

    public String proxyAuthenticationPassword() {
        if (proxyAuthenticationPassword == null) {
            return ConfigurationProperties.proxyAuthenticationPassword();
        }
        return proxyAuthenticationPassword;
    }

    /**
     * <p>The required password for proxy authentication to MockServer</p>
     * <p><strong>Note:</strong> <a target="_blank" href="https://www.oracle.com/java/technologies/javase/8u111-relnotes.html">8u111 Update Release Notes</a> state that the Basic authentication scheme has been deactivated when setting up an HTTPS tunnel.  To resolve this clear or set to an empty string the following system properties: <code class="inline code">jdk.http.auth.tunneling.disabledSchemes</code> and <code class="inline code">jdk.http.auth.proxying.disabledSchemes</code>.</p>
     * <p>
     * The default is ""
     *
     * @param proxyAuthenticationPassword required password for proxy authentication to MockServer
     */
    public Configuration proxyAuthenticationPassword(String proxyAuthenticationPassword) {
        this.proxyAuthenticationPassword = proxyAuthenticationPassword;
        return this;
    }

    // -----------------------------------------------------------------------------------------
    // Data-plane (mocked endpoint) authentication — opt-in, default off.
    // -----------------------------------------------------------------------------------------

    public boolean dataPlaneAuthenticationRequired() {
        if (dataPlaneAuthenticationRequired == null) {
            return ConfigurationProperties.dataPlaneAuthenticationRequired();
        }
        return dataPlaneAuthenticationRequired;
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
     * @param dataPlaneAuthenticationRequired whether data-plane authentication is required
     */
    public Configuration dataPlaneAuthenticationRequired(Boolean dataPlaneAuthenticationRequired) {
        this.dataPlaneAuthenticationRequired = dataPlaneAuthenticationRequired;
        return this;
    }

    public String dataPlaneBasicAuthenticationUsername() {
        if (dataPlaneBasicAuthenticationUsername == null) {
            return ConfigurationProperties.dataPlaneBasicAuthenticationUsername();
        }
        return dataPlaneBasicAuthenticationUsername;
    }

    /**
     * The username required for data-plane HTTP Basic authentication. Basic is only active when both
     * username and password are set. The default is "".
     *
     * @param dataPlaneBasicAuthenticationUsername the Basic username
     */
    public Configuration dataPlaneBasicAuthenticationUsername(String dataPlaneBasicAuthenticationUsername) {
        this.dataPlaneBasicAuthenticationUsername = dataPlaneBasicAuthenticationUsername;
        return this;
    }

    public String dataPlaneBasicAuthenticationPassword() {
        if (dataPlaneBasicAuthenticationPassword == null) {
            return ConfigurationProperties.dataPlaneBasicAuthenticationPassword();
        }
        return dataPlaneBasicAuthenticationPassword;
    }

    /**
     * The password required for data-plane HTTP Basic authentication. Basic is only active when both
     * username and password are set. The default is "".
     *
     * @param dataPlaneBasicAuthenticationPassword the Basic password
     */
    public Configuration dataPlaneBasicAuthenticationPassword(String dataPlaneBasicAuthenticationPassword) {
        this.dataPlaneBasicAuthenticationPassword = dataPlaneBasicAuthenticationPassword;
        return this;
    }

    public String dataPlaneBasicAuthenticationRealm() {
        if (dataPlaneBasicAuthenticationRealm == null) {
            return ConfigurationProperties.dataPlaneBasicAuthenticationRealm();
        }
        return dataPlaneBasicAuthenticationRealm;
    }

    /**
     * The realm advertised in the {@code WWW-Authenticate: Basic realm="..."} challenge on a 401.
     * The default is "MockServer".
     *
     * @param dataPlaneBasicAuthenticationRealm the Basic realm
     */
    public Configuration dataPlaneBasicAuthenticationRealm(String dataPlaneBasicAuthenticationRealm) {
        this.dataPlaneBasicAuthenticationRealm = dataPlaneBasicAuthenticationRealm;
        return this;
    }

    public String dataPlaneBearerAuthenticationToken() {
        if (dataPlaneBearerAuthenticationToken == null) {
            return ConfigurationProperties.dataPlaneBearerAuthenticationToken();
        }
        return dataPlaneBearerAuthenticationToken;
    }

    /**
     * The token required for data-plane Bearer authentication ({@code Authorization: Bearer <token>}).
     * Bearer is active when this value is set. The default is "".
     *
     * @param dataPlaneBearerAuthenticationToken the expected Bearer token
     */
    public Configuration dataPlaneBearerAuthenticationToken(String dataPlaneBearerAuthenticationToken) {
        this.dataPlaneBearerAuthenticationToken = dataPlaneBearerAuthenticationToken;
        return this;
    }

    public String dataPlaneApiKeyAuthenticationHeader() {
        if (dataPlaneApiKeyAuthenticationHeader == null) {
            return ConfigurationProperties.dataPlaneApiKeyAuthenticationHeader();
        }
        return dataPlaneApiKeyAuthenticationHeader;
    }

    /**
     * The name of the header carrying the data-plane API key (e.g. {@code X-API-Key}). API-key auth
     * is only active when both the header name and the value are set. The default is "".
     *
     * @param dataPlaneApiKeyAuthenticationHeader the API-key header name
     */
    public Configuration dataPlaneApiKeyAuthenticationHeader(String dataPlaneApiKeyAuthenticationHeader) {
        this.dataPlaneApiKeyAuthenticationHeader = dataPlaneApiKeyAuthenticationHeader;
        return this;
    }

    public String dataPlaneApiKeyAuthenticationValue() {
        if (dataPlaneApiKeyAuthenticationValue == null) {
            return ConfigurationProperties.dataPlaneApiKeyAuthenticationValue();
        }
        return dataPlaneApiKeyAuthenticationValue;
    }

    /**
     * The expected value of the data-plane API-key header. API-key auth is only active when both the
     * header name and the value are set. The default is "".
     *
     * @param dataPlaneApiKeyAuthenticationValue the expected API-key value
     */
    public Configuration dataPlaneApiKeyAuthenticationValue(String dataPlaneApiKeyAuthenticationValue) {
        this.dataPlaneApiKeyAuthenticationValue = dataPlaneApiKeyAuthenticationValue;
        return this;
    }

    public String noProxyHosts() {
        if (noProxyHosts == null) {
            return ConfigurationProperties.noProxyHosts();
        }
        return noProxyHosts;
    }

    /**
     * <p>The list of hostnames to not use the configured proxy. Several values may be present, seperated by comma (,)</p>
     * The default is ""
     *
     * @param noProxyHosts Comma-seperated list of hosts to not be proxied.
     */
    public Configuration noProxyHosts(String noProxyHosts) {
        this.noProxyHosts = noProxyHosts;
        return this;
    }

    public String proxyRemoteHost() {
        if (proxyRemoteHost == null) {
            return ConfigurationProperties.proxyRemoteHost();
        }
        return proxyRemoteHost;
    }

    /**
     * The hostname of the remote server to proxy all requests to.
     * When set, unmatched requests are forwarded to this host.
     *
     * @param proxyRemoteHost the hostname to forward requests to
     */
    public Configuration proxyRemoteHost(String proxyRemoteHost) {
        this.proxyRemoteHost = proxyRemoteHost;
        return this;
    }

    public Integer proxyRemotePort() {
        if (proxyRemotePort == null) {
            return ConfigurationProperties.proxyRemotePort();
        }
        return proxyRemotePort;
    }

    /**
     * The port of the remote server to proxy all requests to.
     * Must be specified together with proxyRemoteHost.
     *
     * @param proxyRemotePort the port to forward requests to
     */
    public Configuration proxyRemotePort(Integer proxyRemotePort) {
        this.proxyRemotePort = proxyRemotePort;
        return this;
    }

    public Boolean forwardAdjustHostHeader() {
        if (forwardAdjustHostHeader == null) {
            return ConfigurationProperties.forwardAdjustHostHeader();
        }
        return forwardAdjustHostHeader;
    }

    /**
     * If true (the default) the Host header will be automatically adjusted to match the target server when forwarding requests.
     * This prevents HTTP 421 Misdirected Request errors when the target server validates Host headers.
     * If false the original Host header is preserved.
     *
     * @param forwardAdjustHostHeader enables automatic Host header adjustment for forwarded requests
     */
    public Configuration forwardAdjustHostHeader(Boolean forwardAdjustHostHeader) {
        this.forwardAdjustHostHeader = forwardAdjustHostHeader;
        return this;
    }

    public String forwardDefaultHostHeader() {
        if (forwardDefaultHostHeader == null) {
            return ConfigurationProperties.forwardDefaultHostHeader();
        }
        return forwardDefaultHostHeader;
    }

    /**
     * Set a default Host header value to use when forwarding requests.
     * When set, the Host header will be overridden with this value for all forwarded requests,
     * regardless of the target server's address. This is useful when the target server
     * routes requests based on the Host header.
     *
     * @param forwardDefaultHostHeader the Host header value to set on forwarded requests
     */
    public Configuration forwardDefaultHostHeader(String forwardDefaultHostHeader) {
        this.forwardDefaultHostHeader = forwardDefaultHostHeader;
        return this;
    }

    public List<ProxyPassMapping> proxyPassMappings() {
        if (proxyPassMappings == null) {
            return ConfigurationProperties.proxyPass();
        }
        return proxyPassMappings;
    }

    /**
     * Configure ProxyPass mappings that map incoming path prefixes to upstream servers with automatic path rewriting.
     *
     * @param proxyPassMappings list of ProxyPassMapping objects
     */
    public Configuration proxyPassMappings(List<ProxyPassMapping> proxyPassMappings) {
        this.proxyPassMappings = proxyPassMappings;
        return this;
    }

    public Long globalResponseDelayMillis() {
        if (globalResponseDelayMillis == null) {
            return ConfigurationProperties.globalResponseDelayMillis();
        }
        return globalResponseDelayMillis;
    }

    public Configuration globalResponseDelayMillis(Long globalResponseDelayMillis) {
        if (globalResponseDelayMillis != null && globalResponseDelayMillis < 0) {
            throw new IllegalArgumentException("globalResponseDelayMillis must be >= 0, got: " + globalResponseDelayMillis);
        }
        this.globalResponseDelayMillis = globalResponseDelayMillis;
        return this;
    }

    public String livenessHttpGetPath() {
        if (livenessHttpGetPath == null) {
            return ConfigurationProperties.livenessHttpGetPath();
        }
        return livenessHttpGetPath;
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
     * @param livenessHttpGetPath path to support HTTP GET requests for status response
     */
    public Configuration livenessHttpGetPath(String livenessHttpGetPath) {
        this.livenessHttpGetPath = livenessHttpGetPath;
        return this;
    }

    public String matchNamespaceHeader() {
        if (matchNamespaceHeader == null) {
            return ConfigurationProperties.matchNamespaceHeader();
        }
        return matchNamespaceHeader;
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
    public Configuration matchNamespaceHeader(String matchNamespaceHeader) {
        this.matchNamespaceHeader = matchNamespaceHeader;
        return this;
    }

    public Boolean controlPlaneTLSMutualAuthenticationRequired() {
        if (controlPlaneTLSMutualAuthenticationRequired == null) {
            return ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired();
        }
        return controlPlaneTLSMutualAuthenticationRequired;
    }

    /**
     * Require mTLS (also called client authentication and two-way TLS) for all control plane requests
     *
     * @param controlPlaneTLSMutualAuthenticationRequired TLS mutual authentication for all control plane requests
     */
    public Configuration controlPlaneTLSMutualAuthenticationRequired(Boolean controlPlaneTLSMutualAuthenticationRequired) {
        this.controlPlaneTLSMutualAuthenticationRequired = controlPlaneTLSMutualAuthenticationRequired;
        return this;
    }

    public String controlPlaneTLSMutualAuthenticationCAChain() {
        if (controlPlaneTLSMutualAuthenticationCAChain == null) {
            return ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain();
        }
        return controlPlaneTLSMutualAuthenticationCAChain;
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for control plane mTLS authentication
     * <p>
     * The X.509 Certificate Chain is for trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used for to performs mTLS (client authentication) for inbound TLS connections if controlPlaneTLSMutualAuthenticationRequired is enabled
     *
     * @param controlPlaneTLSMutualAuthenticationCAChain File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates
     */
    public Configuration controlPlaneTLSMutualAuthenticationCAChain(String controlPlaneTLSMutualAuthenticationCAChain) {
        fileExists(controlPlaneTLSMutualAuthenticationCAChain);
        this.controlPlaneTLSMutualAuthenticationCAChain = controlPlaneTLSMutualAuthenticationCAChain;
        return this;
    }

    public String controlPlanePrivateKeyPath() {
        if (controlPlanePrivateKeyPath == null) {
            return ConfigurationProperties.controlPlanePrivateKeyPath();
        }
        return controlPlanePrivateKeyPath;
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
     * @param controlPlanePrivateKeyPath location of the PKCS#8 PEM file containing the private key
     */
    public Configuration controlPlanePrivateKeyPath(String controlPlanePrivateKeyPath) {
        fileExists(controlPlanePrivateKeyPath);
        this.controlPlanePrivateKeyPath = controlPlanePrivateKeyPath;
        return this;
    }

    public String controlPlaneX509CertificatePath() {
        if (controlPlaneX509CertificatePath == null) {
            return ConfigurationProperties.controlPlaneX509CertificatePath();
        }
        return controlPlaneX509CertificatePath;
    }

    /**
     * File system path or classpath location of a fixed custom X.509 Certificate for control plane connections using mTLS for authentication.
     * <p>
     * The certificate must be a X509 PEM file and must be the public key corresponding to the controlPlanePrivateKeyPath private key configuration.
     * The controlPlaneTLSMutualAuthenticationCAChain configuration must be the Certificate Authority for this certificate (i.e. able to valid its signature).
     * <p>
     * This configuration will be ignored unless privateKeyPath is also set.
     *
     * @param controlPlaneX509CertificatePath location of the PEM file containing the X509 certificate
     */
    public Configuration controlPlaneX509CertificatePath(String controlPlaneX509CertificatePath) {
        fileExists(controlPlaneX509CertificatePath);
        this.controlPlaneX509CertificatePath = controlPlaneX509CertificatePath;
        return this;
    }

    public Boolean controlPlaneJWTAuthenticationRequired() {
        if (controlPlaneJWTAuthenticationRequired == null) {
            return ConfigurationProperties.controlPlaneJWTAuthenticationRequired();
        }
        return controlPlaneJWTAuthenticationRequired;
    }

    /**
     * <p>
     * Require JWT authentication for all control plane requests
     * </p>
     *
     * @param controlPlaneJWTAuthenticationRequired TLS mutual authentication for all control plane requests
     */
    public Configuration controlPlaneJWTAuthenticationRequired(Boolean controlPlaneJWTAuthenticationRequired) {
        this.controlPlaneJWTAuthenticationRequired = controlPlaneJWTAuthenticationRequired;
        return this;
    }

    public String controlPlaneJWTAuthenticationJWKSource() {
        if (controlPlaneJWTAuthenticationJWKSource == null) {
            return ConfigurationProperties.controlPlaneJWTAuthenticationJWKSource();
        }
        return controlPlaneJWTAuthenticationJWKSource;
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
    public Configuration controlPlaneJWTAuthenticationJWKSource(String controlPlaneJWTAuthenticationJWKSource) {
        this.controlPlaneJWTAuthenticationJWKSource = controlPlaneJWTAuthenticationJWKSource;
        return this;
    }

    public String controlPlaneJWTAuthenticationExpectedAudience() {
        if (controlPlaneJWTAuthenticationExpectedAudience == null) {
            return ConfigurationProperties.controlPlaneJWTAuthenticationExpectedAudience();
        }
        return controlPlaneJWTAuthenticationExpectedAudience;
    }

    /**
     * <p>
     * Audience claim (i.e. aud) required when JWT authentication is enabled for control plane requests
     * </p>
     *
     * @param controlPlaneJWTAuthenticationExpectedAudience required value for audience claim (i.e. aud)
     */
    public Configuration controlPlaneJWTAuthenticationExpectedAudience(String controlPlaneJWTAuthenticationExpectedAudience) {
        this.controlPlaneJWTAuthenticationExpectedAudience = controlPlaneJWTAuthenticationExpectedAudience;
        return this;
    }

    public Map<String, String> controlPlaneJWTAuthenticationMatchingClaims() {
        if (controlPlaneJWTAuthenticationMatchingClaims == null) {
            return ConfigurationProperties.controlPlaneJWTAuthenticationMatchingClaims();
        }
        return controlPlaneJWTAuthenticationMatchingClaims;
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
    public Configuration controlPlaneJWTAuthenticationMatchingClaims(Map<String, String> controlPlaneJWTAuthenticationMatchingClaims) {
        this.controlPlaneJWTAuthenticationMatchingClaims = controlPlaneJWTAuthenticationMatchingClaims;
        return this;
    }

    public Set<String> controlPlaneJWTAuthenticationRequiredClaims() {
        if (controlPlaneJWTAuthenticationRequiredClaims == null) {
            return ConfigurationProperties.controlPlaneJWTAuthenticationRequiredClaims();
        }
        return controlPlaneJWTAuthenticationRequiredClaims;
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
    public Configuration controlPlaneJWTAuthenticationRequiredClaims(Set<String> controlPlaneJWTAuthenticationRequiredClaims) {
        this.controlPlaneJWTAuthenticationRequiredClaims = controlPlaneJWTAuthenticationRequiredClaims;
        return this;
    }

    public Boolean controlPlaneOidcAuthenticationRequired() {
        if (controlPlaneOidcAuthenticationRequired == null) {
            return ConfigurationProperties.controlPlaneOidcAuthenticationRequired();
        }
        return controlPlaneOidcAuthenticationRequired;
    }

    /**
     * <p>
     * Require verified OIDC bearer-token authentication for all control plane requests, validating tokens issued by an external OIDC IdP
     * </p>
     *
     * @param controlPlaneOidcAuthenticationRequired verified OIDC authentication for all control plane requests
     */
    public Configuration controlPlaneOidcAuthenticationRequired(Boolean controlPlaneOidcAuthenticationRequired) {
        this.controlPlaneOidcAuthenticationRequired = controlPlaneOidcAuthenticationRequired;
        return this;
    }

    public String controlPlaneOidcIssuer() {
        if (controlPlaneOidcIssuer == null) {
            return ConfigurationProperties.controlPlaneOidcIssuer();
        }
        return controlPlaneOidcIssuer;
    }

    /**
     * <p>
     * OIDC issuer (i.e. iss) required on control plane tokens; also used to discover the JWKS URI via {issuer}/.well-known/openid-configuration when controlPlaneOidcJwksUri is not set
     * </p>
     *
     * @param controlPlaneOidcIssuer required value for issuer claim (i.e. iss)
     */
    public Configuration controlPlaneOidcIssuer(String controlPlaneOidcIssuer) {
        this.controlPlaneOidcIssuer = controlPlaneOidcIssuer;
        return this;
    }

    public String controlPlaneOidcJwksUri() {
        if (controlPlaneOidcJwksUri == null) {
            return ConfigurationProperties.controlPlaneOidcJwksUri();
        }
        return controlPlaneOidcJwksUri;
    }

    /**
     * <p>
     * JWKS URI used to verify control plane OIDC token signatures; if not set it is discovered from the issuer's OIDC discovery document
     * </p>
     *
     * @param controlPlaneOidcJwksUri URL (or file/classpath path) of the JWK source
     */
    public Configuration controlPlaneOidcJwksUri(String controlPlaneOidcJwksUri) {
        this.controlPlaneOidcJwksUri = controlPlaneOidcJwksUri;
        return this;
    }

    public String controlPlaneOidcAudience() {
        if (controlPlaneOidcAudience == null) {
            return ConfigurationProperties.controlPlaneOidcAudience();
        }
        return controlPlaneOidcAudience;
    }

    /**
     * <p>
     * Audience claim (i.e. aud) required on control plane OIDC tokens
     * </p>
     *
     * @param controlPlaneOidcAudience required value for audience claim (i.e. aud)
     */
    public Configuration controlPlaneOidcAudience(String controlPlaneOidcAudience) {
        this.controlPlaneOidcAudience = controlPlaneOidcAudience;
        return this;
    }

    public Set<String> controlPlaneOidcRequiredScopes() {
        if (controlPlaneOidcRequiredScopes == null) {
            return ConfigurationProperties.controlPlaneOidcRequiredScopes();
        }
        return controlPlaneOidcRequiredScopes;
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
    public Configuration controlPlaneOidcRequiredScopes(Set<String> controlPlaneOidcRequiredScopes) {
        this.controlPlaneOidcRequiredScopes = controlPlaneOidcRequiredScopes;
        return this;
    }

    public String controlPlaneOidcScopeClaim() {
        if (controlPlaneOidcScopeClaim == null) {
            return ConfigurationProperties.controlPlaneOidcScopeClaim();
        }
        return controlPlaneOidcScopeClaim;
    }

    /**
     * <p>
     * Name of the claim holding granted scopes on a control plane OIDC token, default "scope" (space-delimited); array claims such as scp, roles or groups are also supported
     * </p>
     *
     * @param controlPlaneOidcScopeClaim name of the scope claim
     */
    public Configuration controlPlaneOidcScopeClaim(String controlPlaneOidcScopeClaim) {
        this.controlPlaneOidcScopeClaim = controlPlaneOidcScopeClaim;
        return this;
    }

    public Boolean controlPlaneAuthorizationEnabled() {
        if (controlPlaneAuthorizationEnabled == null) {
            return ConfigurationProperties.controlPlaneAuthorizationEnabled();
        }
        return controlPlaneAuthorizationEnabled;
    }

    /**
     * <p>
     * Enable coarse role-based authorization of control plane requests, mapping a verified principal's scopes/groups to read/mutate/admin roles via controlPlaneScopeMapping; requires a verified principal (i.e. control plane OIDC authentication should be enabled). Default false.
     * </p>
     *
     * @param controlPlaneAuthorizationEnabled true to enforce control plane authorization
     */
    public Configuration controlPlaneAuthorizationEnabled(Boolean controlPlaneAuthorizationEnabled) {
        this.controlPlaneAuthorizationEnabled = controlPlaneAuthorizationEnabled;
        return this;
    }

    public Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> controlPlaneScopeMapping() {
        if (controlPlaneScopeMapping == null) {
            return ConfigurationProperties.controlPlaneScopeMapping();
        }
        return controlPlaneScopeMapping;
    }

    /**
     * <p>
     * Mapping from a verified scope/group value to a coarse control plane role (read, mutate or admin). Roles are hierarchical: admin satisfies mutate satisfies read.
     * </p>
     * <p>
     * Value should be a comma separated list of value=role pairs, for example: platform-admins=admin,qa-team=mutate,viewers=read
     * </p>
     *
     * @param controlPlaneScopeMapping mapping from scope/group value to role
     */
    public Configuration controlPlaneScopeMapping(Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> controlPlaneScopeMapping) {
        this.controlPlaneScopeMapping = controlPlaneScopeMapping;
        return this;
    }

    public Boolean proactivelyInitialiseTLS() {
        if (proactivelyInitialiseTLS == null) {
            return ConfigurationProperties.proactivelyInitialiseTLS();
        }
        return proactivelyInitialiseTLS;
    }

    /**
     * <p>Proactively initialise TLS during start to ensure that if dynamicallyCreateCertificateAuthorityCertificate is enabled the Certificate Authority X.509 Certificate and Private Key will be created during start up and not when the first TLS connection is received.</p>
     * <p>This setting will also ensure any configured private key and X.509 will be loaded during start up and not when the first TLS connection is received to give immediate feedback on any related TLS configuration errors.</p>
     *
     * @param proactivelyInitialiseTLS proactively initialise TLS at startup
     */
    public Configuration proactivelyInitialiseTLS(Boolean proactivelyInitialiseTLS) {
        this.proactivelyInitialiseTLS = proactivelyInitialiseTLS;
        return this;
    }

    public boolean rebuildTLSContext() {
        return rebuildTLSContext;
    }

    public Configuration rebuildTLSContext(boolean rebuildTLSContext) {
        this.rebuildTLSContext = rebuildTLSContext;
        return this;
    }

    public boolean rebuildServerTLSContext() {
        return rebuildServerTLSContext;
    }

    public Configuration rebuildServerTLSContext(boolean rebuildServerTLSContext) {
        this.rebuildServerTLSContext = rebuildServerTLSContext;
        return this;
    }

    public String tlsProtocols() {
        if (tlsProtocols == null) {
            return ConfigurationProperties.tlsProtocols();
        }
        return tlsProtocols;
    }

    /**
     * Comma seperated list of TLS protocols, by default TLSv1,TLSv1.1,TLSv1.2
     *
     * @param tlsProtocols comma seperated list of TLS protocols
     */
    public Configuration tlsProtocols(String tlsProtocols) {
        this.tlsProtocols = tlsProtocols;
        return this;
    }

    public Boolean tlsAllowInsecureProtocols() {
        if (tlsAllowInsecureProtocols == null) {
            return ConfigurationProperties.tlsAllowInsecureProtocols();
        }
        return tlsAllowInsecureProtocols;
    }

    /**
     * Whether to allow TLSv1 and TLSv1.1 in the effective TLS protocols list.
     * Both are deprecated by RFC 8996 and vulnerable to BEAST and POODLE.
     * The default is true for backwards compatibility; set to false to opt into
     * a hardened profile that filters TLSv1 and TLSv1.1 out of {@link #tlsProtocols}.
     *
     * @param tlsAllowInsecureProtocols if true, TLSv1 and TLSv1.1 are honoured; if false, they are stripped
     */
    public Configuration tlsAllowInsecureProtocols(Boolean tlsAllowInsecureProtocols) {
        this.tlsAllowInsecureProtocols = tlsAllowInsecureProtocols;
        return this;
    }

    public Boolean dynamicallyCreateCertificateAuthorityCertificate() {
        // proxySetup is the convenience on-switch: when enabled it forces dynamic CA generation so a
        // unique private CA is used instead of the public CA private key published in the repository.
        if (Boolean.TRUE.equals(proxySetup())) {
            return true;
        }
        if (dynamicallyCreateCertificateAuthorityCertificate == null) {
            return ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate();
        }
        return dynamicallyCreateCertificateAuthorityCertificate;
    }

    public Boolean proxySetup() {
        if (proxySetup == null) {
            return ConfigurationProperties.proxySetup();
        }
        return proxySetup;
    }

    /**
     * Convenience on-switch for using MockServer as a TLS-intercepting proxy. When enabled MockServer
     * forces dynamic creation of a unique private Certificate Authority and prints an OS-specific
     * copy-paste proxy setup block on startup.
     *
     * @param proxySetup enable proxy setup convenience mode
     */
    public Configuration proxySetup(Boolean proxySetup) {
        this.proxySetup = proxySetup;
        return this;
    }

    public Boolean proxySetupLogging() {
        if (proxySetupLogging == null) {
            return ConfigurationProperties.proxySetupLogging();
        }
        return proxySetupLogging;
    }

    /**
     * Whether to print the OS-specific copy-paste proxy setup block on startup. Enabled by default.
     *
     * @param proxySetupLogging enable the proxy setup startup logging block
     */
    public Configuration proxySetupLogging(Boolean proxySetupLogging) {
        this.proxySetupLogging = proxySetupLogging;
        return this;
    }

    /**
     * Enable dynamic creation of Certificate Authority X509 certificate and private key.
     * <p>
     * Enable this property to increase the security of trusting the MockServer Certificate Authority X509 by ensuring a local dynamic value is used instead of the public value in the MockServer git repo.
     * <p>
     * These PEM files will be created and saved in the directory specified with configuration property directoryToSaveDynamicSSLCertificate.
     *
     * @param dynamicallyCreateCertificateAuthorityCertificate dynamic creation of Certificate Authority X509 certificate and private key.
     */
    public Configuration dynamicallyCreateCertificateAuthorityCertificate(Boolean dynamicallyCreateCertificateAuthorityCertificate) {
        this.dynamicallyCreateCertificateAuthorityCertificate = dynamicallyCreateCertificateAuthorityCertificate;
        return this;
    }

    public String directoryToSaveDynamicSSLCertificate() {
        if (directoryToSaveDynamicSSLCertificate == null) {
            return ConfigurationProperties.directoryToSaveDynamicSSLCertificate();
        }
        return directoryToSaveDynamicSSLCertificate;
    }

    /**
     * Directory used to save the dynamically generated Certificate Authority X.509 Certificate and Private Key.
     *
     * @param directoryToSaveDynamicSSLCertificate directory to save Certificate Authority X.509 Certificate and Private Key
     */
    public Configuration directoryToSaveDynamicSSLCertificate(String directoryToSaveDynamicSSLCertificate) {
        this.directoryToSaveDynamicSSLCertificate = directoryToSaveDynamicSSLCertificate;
        return this;
    }

    public Boolean preventCertificateDynamicUpdate() {
        if (preventCertificateDynamicUpdate == null) {
            return ConfigurationProperties.preventCertificateDynamicUpdate();
        }
        return preventCertificateDynamicUpdate;
    }

    /**
     * Prevent certificates from dynamically updating when domain list changes
     *
     * @param preventCertificateDynamicUpdate prevent certificates from dynamically updating when domain list changes
     */
    public Configuration preventCertificateDynamicUpdate(Boolean preventCertificateDynamicUpdate) {
        this.preventCertificateDynamicUpdate = preventCertificateDynamicUpdate;
        return this;
    }

    public String sslCertificateDomainName() {
        if (sslCertificateDomainName == null) {
            return ConfigurationProperties.sslCertificateDomainName();
        }
        return sslCertificateDomainName;
    }

    /**
     * The domain name for auto-generate TLS certificates
     * <p>
     * The default is "localhost"
     *
     * @param sslCertificateDomainName domain name for auto-generate TLS certificates
     */
    public Configuration sslCertificateDomainName(String sslCertificateDomainName) {
        this.sslCertificateDomainName = sslCertificateDomainName;
        return this;
    }

    public Set<String> sslSubjectAlternativeNameDomains() {
        if (sslSubjectAlternativeNameDomains == null) {
            return ConfigurationProperties.sslSubjectAlternativeNameDomains();
        }
        return sslSubjectAlternativeNameDomains;
    }

    /**
     * The Subject Alternative Name (SAN) domain names for auto-generate TLS certificates
     * <p>
     * The default is "localhost"
     *
     * @param sslSubjectAlternativeNameDomains Subject Alternative Name (SAN) domain names for auto-generate TLS certificates
     */
    public Configuration sslSubjectAlternativeNameDomains(String... sslSubjectAlternativeNameDomains) {
        this.sslSubjectAlternativeNameDomains = Sets.newConcurrentHashSet(Arrays.asList(sslSubjectAlternativeNameDomains));
        return this;
    }

    /**
     * The Subject Alternative Name (SAN) domain names for auto-generate TLS certificates
     * <p>
     * The default is "localhost"
     *
     * @param sslSubjectAlternativeNameDomains Subject Alternative Name (SAN) domain names for auto-generate TLS certificates
     */
    public Configuration sslSubjectAlternativeNameDomains(Set<String> sslSubjectAlternativeNameDomains) {
        this.sslSubjectAlternativeNameDomains = sslSubjectAlternativeNameDomains;
        return this;
    }

    public Set<String> sslSubjectAlternativeNameIps() {
        if (sslSubjectAlternativeNameIps == null) {
            return ConfigurationProperties.sslSubjectAlternativeNameIps();
        }
        return sslSubjectAlternativeNameIps;
    }

    /**
     * <p>The Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates</p>
     *
     * <p>The default is 127.0.0.1, 0.0.0.0</p>
     *
     * @param sslSubjectAlternativeNameIps Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates
     */
    public Configuration sslSubjectAlternativeNameIps(String... sslSubjectAlternativeNameIps) {
        sslSubjectAlternativeNameIps(Sets.newConcurrentHashSet(Arrays.asList(sslSubjectAlternativeNameIps)));
        return this;
    }

    /**
     * <p>The Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates</p>
     *
     * <p>The default is 127.0.0.1, 0.0.0.0</p>
     *
     * @param sslSubjectAlternativeNameIps Subject Alternative Name (SAN) IP addresses for auto-generate TLS certificates
     */
    public Configuration sslSubjectAlternativeNameIps(Set<String> sslSubjectAlternativeNameIps) {
        this.sslSubjectAlternativeNameIps = sslSubjectAlternativeNameIps;
        return this;
    }

    public String certificateAuthorityPrivateKey() {
        if (certificateAuthorityPrivateKey == null) {
            return ConfigurationProperties.certificateAuthorityPrivateKey();
        }
        return certificateAuthorityPrivateKey;
    }

    /**
     * File system path or classpath location of custom Private Key for Certificate Authority for TLS, the private key must be a PKCS#8 or PKCS#1 PEM file and must match the certificateAuthorityCertificate
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * The path is not file-existence-checked here because dynamic CA generation
     * ({@link #dynamicallyCreateCertificateAuthorityCertificate}) sets this to the
     * destination path before the file is written. Typos in user-supplied paths are
     * surfaced by {@link org.mockserver.socket.tls.CertificateConfigurationValidator}
     * at TLS-init time.
     *
     * @param certificateAuthorityPrivateKey location of the PEM file containing the certificate authority private key
     */
    public Configuration certificateAuthorityPrivateKey(String certificateAuthorityPrivateKey) {
        this.certificateAuthorityPrivateKey = certificateAuthorityPrivateKey;
        return this;
    }

    public String certificateAuthorityCertificate() {
        if (certificateAuthorityCertificate == null) {
            return ConfigurationProperties.certificateAuthorityCertificate();
        }
        return certificateAuthorityCertificate;
    }

    /**
     * File system path or classpath location of custom X.509 Certificate for Certificate Authority for TLS, the certificate must be a X509 PEM file and must match the certificateAuthorityPrivateKey
     * <p>
     * The path is not file-existence-checked here because dynamic CA generation
     * ({@link #dynamicallyCreateCertificateAuthorityCertificate}) sets this to the
     * destination path before the file is written. Typos in user-supplied paths are
     * surfaced by {@link org.mockserver.socket.tls.CertificateConfigurationValidator}
     * at TLS-init time.
     *
     * @param certificateAuthorityCertificate location of the PEM file containing the certificate authority X509 certificate
     */
    public Configuration certificateAuthorityCertificate(String certificateAuthorityCertificate) {
        this.certificateAuthorityCertificate = certificateAuthorityCertificate;
        return this;
    }

    public String privateKeyPath() {
        if (privateKeyPath == null) {
            return ConfigurationProperties.privateKeyPath();
        }
        return privateKeyPath;
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
     * <p>
     * The path is not file-existence-checked here because dynamic SSL certificate
     * generation sets this to the destination path before the file is written. Typos
     * in user-supplied paths are surfaced by
     * {@link org.mockserver.socket.tls.CertificateConfigurationValidator} at TLS-init time.
     *
     * @param privateKeyPath location of the PKCS#8 PEM file containing the private key
     */
    public Configuration privateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
        return this;
    }

    public String x509CertificatePath() {
        if (x509CertificatePath == null) {
            return ConfigurationProperties.x509CertificatePath();
        }
        return x509CertificatePath;
    }

    /**
     * File system path or classpath location of a fixed custom X.509 Certificate for TLS connections into MockServer.
     * <p>
     * The certificate must be a X509 PEM file and must be the public key corresponding to the privateKeyPath private key configuration.
     * The certificateAuthorityCertificate configuration must be the Certificate Authority for this certificate (i.e. able to valid its signature).
     * <p>
     * This configuration will be ignored unless privateKeyPath is also set.
     * <p>
     * The path is not file-existence-checked here because dynamic SSL certificate
     * generation sets this to the destination path before the file is written. Typos
     * in user-supplied paths are surfaced by
     * {@link org.mockserver.socket.tls.CertificateConfigurationValidator} at TLS-init time.
     *
     * @param x509CertificatePath location of the PEM file containing the X509 certificate
     */
    public Configuration x509CertificatePath(String x509CertificatePath) {
        this.x509CertificatePath = x509CertificatePath;
        return this;
    }

    public Boolean tlsMutualAuthenticationRequired() {
        if (tlsMutualAuthenticationRequired == null) {
            return ConfigurationProperties.tlsMutualAuthenticationRequired();
        }
        return tlsMutualAuthenticationRequired;
    }

    /**
     * Require mTLS (also called client authentication and two-way TLS) for all TLS connections / HTTPS requests to MockServer
     *
     * @param tlsMutualAuthenticationRequired TLS mutual authentication
     */
    public Configuration tlsMutualAuthenticationRequired(Boolean tlsMutualAuthenticationRequired) {
        this.tlsMutualAuthenticationRequired = tlsMutualAuthenticationRequired;
        // The server SSL context bakes in clientAuth (REQUIRE vs OPTIONAL) and the trust manager, so a
        // cached context must be discarded when this changes — otherwise requiring mTLS at runtime is
        // accepted and silently ignored, and certificateless clients keep connecting. Mirrors
        // addSslSubjectAlternativeName*/clearSslSubjectAlternativeName*, which already do this.
        rebuildServerTLSContext(true);
        return this;
    }

    public String tlsMutualAuthenticationCertificateChain() {
        if (tlsMutualAuthenticationCertificateChain == null) {
            return ConfigurationProperties.tlsMutualAuthenticationCertificateChain();
        }
        return tlsMutualAuthenticationCertificateChain;
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used if MockServer performs mTLS (client authentication) for inbound TLS connections because tlsMutualAuthenticationRequired is enabled
     *
     * @param tlsMutualAuthenticationCertificateChain File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates
     */
    public Configuration tlsMutualAuthenticationCertificateChain(String tlsMutualAuthenticationCertificateChain) {
        fileExists(tlsMutualAuthenticationCertificateChain);
        this.tlsMutualAuthenticationCertificateChain = tlsMutualAuthenticationCertificateChain;
        // same reasoning as tlsMutualAuthenticationRequired: this feeds the server context's trust manager
        rebuildServerTLSContext(true);
        return this;
    }

    public ForwardProxyTLSX509CertificatesTrustManager forwardProxyTLSX509CertificatesTrustManagerType() {
        if (forwardProxyTLSX509CertificatesTrustManagerType == null) {
            return ConfigurationProperties.forwardProxyTLSX509CertificatesTrustManagerType();
        }
        return forwardProxyTLSX509CertificatesTrustManagerType;
    }

    /**
     * Configure trusted set of certificates for forwarded or proxied requests.
     * <p>
     * MockServer will only be able to establish a TLS connection to endpoints that have a trusted X509 certificate according to the trust manager type, as follows:
     * <p>
     * <p>
     * ALL - Insecure will trust all X509 certificates and not perform host name verification.
     * JVM - Will trust all X509 certificates trust by the JVM.
     * CUSTOM - Will trust all X509 certificates specified in forwardProxyTLSCustomTrustX509Certificates configuration value.
     *
     * @param forwardProxyTLSX509CertificatesTrustManagerType trusted set of certificates for forwarded or proxied requests, allowed values: ALL, JVM, CUSTOM.
     */
    public Configuration forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager forwardProxyTLSX509CertificatesTrustManagerType) {
        this.forwardProxyTLSX509CertificatesTrustManagerType = forwardProxyTLSX509CertificatesTrustManagerType;
        return this;
    }

    public Boolean forwardProxyBlockPrivateNetworks() {
        if (forwardProxyBlockPrivateNetworks == null) {
            return ConfigurationProperties.forwardProxyBlockPrivateNetworks();
        }
        return forwardProxyBlockPrivateNetworks;
    }

    /**
     * When set to true, MockServer rejects forward and proxy targets that resolve to
     * loopback, link-local, RFC 1918 private, or cloud metadata addresses
     * (such as 169.254.169.254), blocking server-side request forgery (SSRF) via
     * malicious expectations.
     * <p>
     * The default is false so that the common case of forwarding to localhost / Docker
     * bridge / Kubernetes service IPs continues to work. Enable this in hardened or
     * multi-tenant deployments where untrusted callers can register expectations.
     *
     * @param forwardProxyBlockPrivateNetworks if true, block forwarding to private or metadata addresses
     */
    public Configuration forwardProxyBlockPrivateNetworks(Boolean forwardProxyBlockPrivateNetworks) {
        this.forwardProxyBlockPrivateNetworks = forwardProxyBlockPrivateNetworks;
        return this;
    }

    public String forwardProxyTLSCustomTrustX509Certificates() {
        if (forwardProxyTLSCustomTrustX509Certificates == null) {
            return ConfigurationProperties.forwardProxyTLSCustomTrustX509Certificates();
        }
        return forwardProxyTLSCustomTrustX509Certificates;
    }

    /**
     * File system path or classpath location of custom file for trusted X509 Certificate Authority roots for forwarded or proxied requests, the certificate chain must be a X509 PEM file.
     * <p>
     * MockServer will only be able to establish a TLS connection to endpoints that have an X509 certificate chain that is signed by one of the provided custom
     * certificates, i.e. where a path can be established from the endpoints X509 certificate to one or more of the custom X509 certificates provided.
     *
     * @param forwardProxyTLSCustomTrustX509Certificates custom set of trusted X509 certificate authority roots for forwarded or proxied requests in PEM format.
     */
    public Configuration forwardProxyTLSCustomTrustX509Certificates(String forwardProxyTLSCustomTrustX509Certificates) {
        fileExists(forwardProxyTLSCustomTrustX509Certificates);
        this.forwardProxyTLSCustomTrustX509Certificates = forwardProxyTLSCustomTrustX509Certificates;
        return this;
    }

    public String forwardProxyPrivateKey() {
        if (forwardProxyPrivateKey == null) {
            return ConfigurationProperties.forwardProxyPrivateKey();
        }
        return forwardProxyPrivateKey;
    }

    /**
     * File system path or classpath location of custom Private Key for proxied TLS connections out of MockServer, the private key must be a PKCS#8 or PKCS#1 PEM file
     * <p>
     * To convert a PKCS#1 (i.e. default for Bouncy Castle) to a PKCS#8 the following command can be used: openssl pkcs8 -topk8 -inform PEM -in private_key_PKCS_1.pem -out private_key_PKCS_8.pem -nocrypt
     * <p>
     * This private key will be used if MockServer needs to perform mTLS (client authentication) for outbound TLS connections.
     *
     * @param forwardProxyPrivateKey location of the PEM file containing the private key
     */
    public Configuration forwardProxyPrivateKey(String forwardProxyPrivateKey) {
        fileExists(forwardProxyPrivateKey);
        this.forwardProxyPrivateKey = forwardProxyPrivateKey;
        return this;
    }

    public String forwardProxyCertificateChain() {
        if (forwardProxyCertificateChain == null) {
            return ConfigurationProperties.forwardProxyCertificateChain();
        }
        return forwardProxyCertificateChain;
    }

    /**
     * File system path or classpath location of custom mTLS (TLS client authentication) X.509 Certificate Chain for Trusting (i.e. signature verification of) Client X.509 Certificates, the certificate chain must be a X509 PEM file.
     * <p>
     * This certificate chain will be used if MockServer needs to perform mTLS (client authentication) for outbound TLS connections.
     *
     * @param forwardProxyCertificateChain location of the PEM file containing the certificate chain
     */
    public Configuration forwardProxyCertificateChain(String forwardProxyCertificateChain) {
        fileExists(forwardProxyCertificateChain);
        this.forwardProxyCertificateChain = forwardProxyCertificateChain;
        return this;
    }

    public String forwardProxyClientCertificatesByHost() {
        if (forwardProxyClientCertificatesByHost == null) {
            return ConfigurationProperties.forwardProxyClientCertificatesByHost();
        }
        return forwardProxyClientCertificatesByHost;
    }

    /**
     * Per-host outbound mTLS certificate/key map for forwarded or proxied requests, as a comma-separated list of
     * {@code host=certificateChainPath;privateKeyPath} entries. A matching upstream host (case-insensitive) is sent
     * that host's cert/key pair for client authentication; any host without an entry falls back to the global
     * {@code forwardProxyPrivateKey} / {@code forwardProxyCertificateChain} pair. The default is empty.
     *
     * @param forwardProxyClientCertificatesByHost comma-separated host=certificateChainPath;privateKeyPath entries
     */
    public Configuration forwardProxyClientCertificatesByHost(String forwardProxyClientCertificatesByHost) {
        this.forwardProxyClientCertificatesByHost = forwardProxyClientCertificatesByHost;
        return this;
    }

    public Boolean transparentProxyEnabled() {
        if (transparentProxyEnabled == null) {
            return ConfigurationProperties.transparentProxyEnabled();
        }
        return transparentProxyEnabled;
    }

    /**
     * Enable transparent HTTP proxy mode where all connections are treated as proxy
     * requests using the Host header as the forwarding target. This enables
     * iptables REDIRECT-based interception without CONNECT.
     * <p>
     * The default is false
     *
     * @param transparentProxyEnabled enable transparent proxy mode
     */
    public Configuration transparentProxyEnabled(Boolean transparentProxyEnabled) {
        this.transparentProxyEnabled = transparentProxyEnabled;
        return this;
    }

    public Boolean transparentProxyTproxy() {
        if (transparentProxyTproxy == null) {
            return ConfigurationProperties.transparentProxyTproxy();
        }
        return transparentProxyTproxy;
    }

    /**
     * Enable TPROXY (IP_TRANSPARENT) mode for transparent proxy original destination
     * resolution. When enabled, the listener socket is bound with IP_TRANSPARENT and
     * the original destination is read from the socket's local address. Requires
     * Linux + epoll + CAP_NET_ADMIN + TPROXY iptables rules.
     *
     * @param transparentProxyTproxy enable TPROXY mode
     */
    public Configuration transparentProxyTproxy(Boolean transparentProxyTproxy) {
        this.transparentProxyTproxy = transparentProxyTproxy;
        return this;
    }

    public Boolean transparentProxyEbpf() {
        if (transparentProxyEbpf == null) {
            return ConfigurationProperties.transparentProxyEbpf();
        }
        return transparentProxyEbpf;
    }

    /**
     * Enable eBPF-based original destination resolution for transparent proxy mode.
     * When enabled, the resolver reads from a pinned BPF hash map (populated by an
     * external cgroup/connect4 BPF program) keyed by socket cookie. Requires Linux,
     * CAP_BPF (or root), a BTF-enabled kernel, and an external BPF program that
     * populates the map. Default: false.
     *
     * @param transparentProxyEbpf enable eBPF original destination resolution
     */
    public Configuration transparentProxyEbpf(Boolean transparentProxyEbpf) {
        this.transparentProxyEbpf = transparentProxyEbpf;
        return this;
    }

    public String transparentProxyEbpfMapPath() {
        if (transparentProxyEbpfMapPath == null) {
            return ConfigurationProperties.transparentProxyEbpfMapPath();
        }
        return transparentProxyEbpfMapPath;
    }

    /**
     * Path to the pinned BPF map used by the eBPF original destination resolver.
     * The map must be a BPF hash map keyed by u64 (socket cookie) with a 6-byte
     * value (4-byte IPv4 address + 2-byte port, both in network byte order).
     * Default: {@code /sys/fs/bpf/mockserver_orig_dst}.
     *
     * @param transparentProxyEbpfMapPath path to the pinned BPF map
     */
    public Configuration transparentProxyEbpfMapPath(String transparentProxyEbpfMapPath) {
        this.transparentProxyEbpfMapPath = transparentProxyEbpfMapPath;
        return this;
    }

    // async messaging defaults

    public String asyncKafkaBootstrapServers() {
        if (asyncKafkaBootstrapServers == null) {
            return ConfigurationProperties.asyncKafkaBootstrapServers();
        }
        return asyncKafkaBootstrapServers;
    }

    /**
     * Default Kafka bootstrap servers for async messaging. Used when a
     * {@code PUT /mockserver/asyncapi} request omits {@code brokerConfig.kafkaBootstrapServers}.
     *
     * @param asyncKafkaBootstrapServers the default Kafka bootstrap servers
     */
    public Configuration asyncKafkaBootstrapServers(String asyncKafkaBootstrapServers) {
        this.asyncKafkaBootstrapServers = asyncKafkaBootstrapServers;
        return this;
    }

    public String asyncMqttBrokerUrl() {
        if (asyncMqttBrokerUrl == null) {
            return ConfigurationProperties.asyncMqttBrokerUrl();
        }
        return asyncMqttBrokerUrl;
    }

    /**
     * Default MQTT broker URL for async messaging. Used when a
     * {@code PUT /mockserver/asyncapi} request omits {@code brokerConfig.mqttBrokerUrl}.
     *
     * @param asyncMqttBrokerUrl the default MQTT broker URL
     */
    public Configuration asyncMqttBrokerUrl(String asyncMqttBrokerUrl) {
        this.asyncMqttBrokerUrl = asyncMqttBrokerUrl;
        return this;
    }

    public String asyncAmqpUri() {
        if (asyncAmqpUri == null) {
            return ConfigurationProperties.asyncAmqpUri();
        }
        return asyncAmqpUri;
    }

    /**
     * Default AMQP (RabbitMQ) connection URI for async messaging. Used when a
     * {@code PUT /mockserver/asyncapi} request omits {@code brokerConfig.amqpUri}.
     *
     * @param asyncAmqpUri the default AMQP connection URI
     */
    public Configuration asyncAmqpUri(String asyncAmqpUri) {
        this.asyncAmqpUri = asyncAmqpUri;
        return this;
    }

    public Integer asyncRecordedMessageMaxEntries() {
        if (asyncRecordedMessageMaxEntries == null) {
            return ConfigurationProperties.asyncRecordedMessageMaxEntries();
        }
        return asyncRecordedMessageMaxEntries;
    }

    /**
     * Maximum number of recorded messages retained per channel in async
     * messaging subscribers. Default is 1000.
     *
     * @param asyncRecordedMessageMaxEntries the maximum entries per channel
     */
    public Configuration asyncRecordedMessageMaxEntries(Integer asyncRecordedMessageMaxEntries) {
        this.asyncRecordedMessageMaxEntries = asyncRecordedMessageMaxEntries;
        return this;
    }

    public String llmProvider() {
        if (llmProvider == null) {
            return ConfigurationProperties.llmProvider();
        }
        return llmProvider;
    }

    /**
     * Provider type for the default runtime-LLM backend (one of the {@code org.mockserver.model.Provider}
     * enum names). Empty by default.
     *
     * @param llmProvider the runtime-LLM provider name
     */
    public Configuration llmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }

    public String llmApiKey() {
        if (llmApiKey == null) {
            return ConfigurationProperties.llmApiKey();
        }
        return llmApiKey;
    }

    /**
     * API key (secret) for the default runtime-LLM backend. WRITE-ONLY over the control plane: it can be
     * set via {@code PUT /mockserver/configuration}, but {@code GET /mockserver/configuration} returns
     * {@link ConfigurationProperties#REDACTED_VALUE} instead of the real value. Never logged.
     *
     * @param llmApiKey the runtime-LLM API key
     */
    public Configuration llmApiKey(String llmApiKey) {
        this.llmApiKey = llmApiKey;
        return this;
    }

    public String llmModel() {
        if (llmModel == null) {
            return ConfigurationProperties.llmModel();
        }
        return llmModel;
    }

    /**
     * Model for the default runtime-LLM backend; empty means the per-provider default applies.
     *
     * @param llmModel the runtime-LLM model name
     */
    public Configuration llmModel(String llmModel) {
        this.llmModel = llmModel;
        return this;
    }

    public String llmBaseUrl() {
        if (llmBaseUrl == null) {
            return ConfigurationProperties.llmBaseUrl();
        }
        return llmBaseUrl;
    }

    /**
     * Base URL override for the default runtime-LLM backend; empty means the per-provider default applies.
     *
     * @param llmBaseUrl the runtime-LLM base URL
     */
    public Configuration llmBaseUrl(String llmBaseUrl) {
        this.llmBaseUrl = llmBaseUrl;
        return this;
    }

    public String llmBackendsConfig() {
        if (llmBackendsConfig == null) {
            return ConfigurationProperties.llmBackendsConfig();
        }
        return llmBackendsConfig;
    }

    /**
     * Path to a JSON file declaring named runtime-LLM backends. Empty by default.
     *
     * @param llmBackendsConfig path to the backends JSON file
     */
    public Configuration llmBackendsConfig(String llmBackendsConfig) {
        this.llmBackendsConfig = llmBackendsConfig;
        return this;
    }

    public Long llmRequestTimeoutMillis() {
        if (llmRequestTimeoutMillis == null) {
            return ConfigurationProperties.llmRequestTimeoutMillis();
        }
        return llmRequestTimeoutMillis;
    }

    /**
     * Per-request timeout (milliseconds) for outbound runtime-LLM calls. A backend's own
     * {@code timeoutMillis} overrides this. Default 30000.
     *
     * @param llmRequestTimeoutMillis the per-request timeout in milliseconds
     */
    public Configuration llmRequestTimeoutMillis(Long llmRequestTimeoutMillis) {
        this.llmRequestTimeoutMillis = llmRequestTimeoutMillis;
        return this;
    }

    public Boolean llmSemanticMatchingEnabled() {
        if (llmSemanticMatchingEnabled == null) {
            return ConfigurationProperties.llmSemanticMatchingEnabled();
        }
        return llmSemanticMatchingEnabled;
    }

    /**
     * Opt-in switch for fuzzy, LLM-judged semantic prompt matching (the {@code semanticMatch}
     * conversation predicate). Off by default.
     *
     * @param llmSemanticMatchingEnabled enable semantic prompt matching
     */
    public Configuration llmSemanticMatchingEnabled(Boolean llmSemanticMatchingEnabled) {
        this.llmSemanticMatchingEnabled = llmSemanticMatchingEnabled;
        return this;
    }

    public Boolean llmInferUsageEnabled() {
        if (llmInferUsageEnabled == null) {
            return ConfigurationProperties.llmInferUsageEnabled();
        }
        return llmInferUsageEnabled;
    }

    /**
     * Opt-in switch for approximate LLM token-usage inference on mocked completions that do not declare
     * {@code usage}. Off by default.
     *
     * @param llmInferUsageEnabled enable token-usage inference
     */
    public Configuration llmInferUsageEnabled(Boolean llmInferUsageEnabled) {
        this.llmInferUsageEnabled = llmInferUsageEnabled;
        return this;
    }

    public Boolean llmVcrStrict() {
        if (llmVcrStrict == null) {
            return ConfigurationProperties.llmVcrStrict();
        }
        return llmVcrStrict;
    }

    /**
     * When true, loading LLM fixtures in strict VCR mode registers a low-priority catch-all per cassette
     * path so a request matching no recorded entry fails loudly (HTTP 599). Default false.
     *
     * @param llmVcrStrict enable strict VCR mode
     */
    public Configuration llmVcrStrict(Boolean llmVcrStrict) {
        this.llmVcrStrict = llmVcrStrict;
        return this;
    }

    public Integer llmOptimisationMaxCalls() {
        if (llmOptimisationMaxCalls == null) {
            return ConfigurationProperties.llmOptimisationMaxCalls();
        }
        return llmOptimisationMaxCalls;
    }

    /**
     * Upper bound on the number of captured LLM calls included in an optimisation report / brief.
     * Default 200.
     *
     * @param llmOptimisationMaxCalls the maximum number of calls in a report
     */
    public Configuration llmOptimisationMaxCalls(Integer llmOptimisationMaxCalls) {
        this.llmOptimisationMaxCalls = llmOptimisationMaxCalls;
        return this;
    }

    public String fixtureBodyRedactFields() {
        if (fixtureBodyRedactFields == null) {
            return ConfigurationProperties.fixtureBodyRedactFields();
        }
        return fixtureBodyRedactFields;
    }

    /**
     * Comma-separated JSON field names whose values are redacted from recorded fixture request/response
     * bodies. Empty by default.
     *
     * @param fixtureBodyRedactFields comma-separated JSON field names to redact
     */
    public Configuration fixtureBodyRedactFields(String fixtureBodyRedactFields) {
        this.fixtureBodyRedactFields = fixtureBodyRedactFields;
        return this;
    }

    public String otelEndpoint() {
        if (otelEndpoint == null) {
            return ConfigurationProperties.otelEndpoint();
        }
        return otelEndpoint;
    }

    /**
     * OTLP endpoint MockServer exports to. When unset, falls back to the MockServer property, then the
     * OpenTelemetry-standard {@code OTEL_EXPORTER_OTLP_ENDPOINT} env var, then empty.
     *
     * @param otelEndpoint the OTLP endpoint URL
     */
    public Configuration otelEndpoint(String otelEndpoint) {
        this.otelEndpoint = otelEndpoint;
        return this;
    }

    public Boolean otelMetricsEnabled() {
        if (otelMetricsEnabled == null) {
            return ConfigurationProperties.otelMetricsEnabled();
        }
        return otelMetricsEnabled;
    }

    /**
     * When true, MockServer's explicitly-defined metrics are also exported via OpenTelemetry OTLP.
     * Off by default.
     *
     * @param otelMetricsEnabled enable OTLP metric export
     */
    public Configuration otelMetricsEnabled(Boolean otelMetricsEnabled) {
        this.otelMetricsEnabled = otelMetricsEnabled;
        return this;
    }

    public Boolean otelTracesEnabled() {
        if (otelTracesEnabled == null) {
            return ConfigurationProperties.otelTracesEnabled();
        }
        return otelTracesEnabled;
    }

    /**
     * When true, MockServer emits explicit GenAI semantic-convention spans for LLM interactions.
     * Off by default.
     *
     * @param otelTracesEnabled enable OTLP trace export
     */
    public Configuration otelTracesEnabled(Boolean otelTracesEnabled) {
        this.otelTracesEnabled = otelTracesEnabled;
        return this;
    }

    public Long otelMetricsExportIntervalSeconds() {
        if (otelMetricsExportIntervalSeconds == null) {
            return ConfigurationProperties.otelMetricsExportIntervalSeconds();
        }
        // mirrors the clamp ConfigurationProperties.otelMetricsExportIntervalSeconds() applies, so a
        // zero/negative interval (which would make PeriodicMetricReader throw) can never be read back
        // regardless of which route set it
        return Math.max(1L, otelMetricsExportIntervalSeconds);
    }

    /**
     * How often (seconds) OTel metrics are exported. Default 60. Clamped to a minimum of 1 second.
     *
     * @param otelMetricsExportIntervalSeconds the export interval in seconds
     */
    public Configuration otelMetricsExportIntervalSeconds(Long otelMetricsExportIntervalSeconds) {
        this.otelMetricsExportIntervalSeconds = otelMetricsExportIntervalSeconds;
        return this;
    }

    public String otelMetricsTemporality() {
        if (otelMetricsTemporality == null) {
            return ConfigurationProperties.otelMetricsTemporality();
        }
        return otelMetricsTemporality;
    }

    /**
     * OTLP aggregation temporality for counter/histogram instruments: {@code cumulative} (default) or
     * {@code delta}. The exporter fails safe to cumulative on any unknown/blank value.
     *
     * @param otelMetricsTemporality the OTLP aggregation temporality
     */
    public Configuration otelMetricsTemporality(String otelMetricsTemporality) {
        this.otelMetricsTemporality = otelMetricsTemporality;
        return this;
    }

    public Boolean prometheusRemoteWriteEnabled() {
        if (prometheusRemoteWriteEnabled == null) {
            return ConfigurationProperties.prometheusRemoteWriteEnabled();
        }
        return prometheusRemoteWriteEnabled;
    }

    /**
     * When true, MockServer periodically pushes its Prometheus metrics to a Prometheus Remote-Write
     * endpoint. Off by default. Fail-soft — remote-write export never affects request handling.
     *
     * @param prometheusRemoteWriteEnabled enable Prometheus remote write
     */
    public Configuration prometheusRemoteWriteEnabled(Boolean prometheusRemoteWriteEnabled) {
        this.prometheusRemoteWriteEnabled = prometheusRemoteWriteEnabled;
        return this;
    }

    public String prometheusRemoteWriteUrl() {
        if (prometheusRemoteWriteUrl == null) {
            return ConfigurationProperties.prometheusRemoteWriteUrl();
        }
        return prometheusRemoteWriteUrl;
    }

    /**
     * The Prometheus Remote-Write endpoint URL to POST to. Empty by default; when remote-write is
     * enabled but this is blank the exporter logs a warning and does nothing.
     *
     * @param prometheusRemoteWriteUrl the remote-write endpoint URL
     */
    public Configuration prometheusRemoteWriteUrl(String prometheusRemoteWriteUrl) {
        this.prometheusRemoteWriteUrl = prometheusRemoteWriteUrl;
        return this;
    }

    public Long prometheusRemoteWriteIntervalSeconds() {
        if (prometheusRemoteWriteIntervalSeconds == null) {
            return ConfigurationProperties.prometheusRemoteWriteIntervalSeconds();
        }
        // mirrors the clamp ConfigurationProperties.prometheusRemoteWriteIntervalSeconds() applies, so a
        // zero/negative push interval can never be read back regardless of which route set it
        return Math.max(1L, prometheusRemoteWriteIntervalSeconds);
    }

    /**
     * How often (seconds) metrics are pushed to the remote-write endpoint. Default 60. Clamped to a
     * minimum of 1 second.
     *
     * @param prometheusRemoteWriteIntervalSeconds the push interval in seconds
     */
    public Configuration prometheusRemoteWriteIntervalSeconds(Long prometheusRemoteWriteIntervalSeconds) {
        this.prometheusRemoteWriteIntervalSeconds = prometheusRemoteWriteIntervalSeconds;
        return this;
    }

    public String prometheusRemoteWriteBearerToken() {
        if (prometheusRemoteWriteBearerToken == null) {
            return ConfigurationProperties.prometheusRemoteWriteBearerToken();
        }
        return prometheusRemoteWriteBearerToken;
    }

    /**
     * Optional bearer token for the remote-write endpoint. WRITE-ONLY over the control plane: it can be
     * set via {@code PUT /mockserver/configuration}, but {@code GET /mockserver/configuration} returns
     * {@link ConfigurationProperties#REDACTED_VALUE} instead of the real value.
     *
     * @param prometheusRemoteWriteBearerToken the remote-write bearer token
     */
    public Configuration prometheusRemoteWriteBearerToken(String prometheusRemoteWriteBearerToken) {
        this.prometheusRemoteWriteBearerToken = prometheusRemoteWriteBearerToken;
        return this;
    }

    public String prometheusRemoteWriteBasicAuthUsername() {
        if (prometheusRemoteWriteBasicAuthUsername == null) {
            return ConfigurationProperties.prometheusRemoteWriteBasicAuthUsername();
        }
        return prometheusRemoteWriteBasicAuthUsername;
    }

    /**
     * Optional HTTP basic-auth username for the remote-write endpoint. Used only when no bearer token
     * is set.
     *
     * @param prometheusRemoteWriteBasicAuthUsername the remote-write basic-auth username
     */
    public Configuration prometheusRemoteWriteBasicAuthUsername(String prometheusRemoteWriteBasicAuthUsername) {
        this.prometheusRemoteWriteBasicAuthUsername = prometheusRemoteWriteBasicAuthUsername;
        return this;
    }

    public String prometheusRemoteWriteBasicAuthPassword() {
        if (prometheusRemoteWriteBasicAuthPassword == null) {
            return ConfigurationProperties.prometheusRemoteWriteBasicAuthPassword();
        }
        return prometheusRemoteWriteBasicAuthPassword;
    }

    /**
     * Optional HTTP basic-auth password for the remote-write endpoint. WRITE-ONLY over the control
     * plane: it can be set via {@code PUT /mockserver/configuration}, but
     * {@code GET /mockserver/configuration} returns {@link ConfigurationProperties#REDACTED_VALUE}
     * instead of the real value.
     *
     * @param prometheusRemoteWriteBasicAuthPassword the remote-write basic-auth password
     */
    public Configuration prometheusRemoteWriteBasicAuthPassword(String prometheusRemoteWriteBasicAuthPassword) {
        this.prometheusRemoteWriteBasicAuthPassword = prometheusRemoteWriteBasicAuthPassword;
        return this;
    }

    public String prometheusRemoteWriteHeaders() {
        if (prometheusRemoteWriteHeaders == null) {
            return ConfigurationProperties.prometheusRemoteWriteHeaders();
        }
        return prometheusRemoteWriteHeaders;
    }

    /**
     * Optional extra HTTP headers added to each remote-write POST, as a comma-separated
     * {@code key=value} list. The raw string is carried here; the exporter parses it.
     *
     * @param prometheusRemoteWriteHeaders comma-separated key=value header list
     */
    public Configuration prometheusRemoteWriteHeaders(String prometheusRemoteWriteHeaders) {
        this.prometheusRemoteWriteHeaders = prometheusRemoteWriteHeaders;
        return this;
    }

    public String prometheusRemoteWriteProtocolVersion() {
        if (prometheusRemoteWriteProtocolVersion == null) {
            return ConfigurationProperties.prometheusRemoteWriteProtocolVersion();
        }
        return prometheusRemoteWriteProtocolVersion;
    }

    /**
     * The Prometheus remote-write protocol version to encode and push: {@code v1} (default) or
     * {@code v2}. Any unrecognised or blank value falls back to {@code v1}.
     *
     * @param prometheusRemoteWriteProtocolVersion the remote-write protocol version
     */
    public Configuration prometheusRemoteWriteProtocolVersion(String prometheusRemoteWriteProtocolVersion) {
        this.prometheusRemoteWriteProtocolVersion = prometheusRemoteWriteProtocolVersion;
        return this;
    }

    public Long regexMatchingTimeoutMillis() {
        if (regexMatchingTimeoutMillis == null) {
            return ConfigurationProperties.regexMatchingTimeoutMillis();
        }
        return regexMatchingTimeoutMillis;
    }

    /**
     * Maximum time (in milliseconds) allowed for evaluating a single regular expression during request
     * matching. Exceeding the budget is treated as a non-match, bounding pathological backtracking.
     * Default 5000. Set to 0 or negative to disable the timeout.
     *
     * @param regexMatchingTimeoutMillis regex evaluation timeout in milliseconds
     */
    public Configuration regexMatchingTimeoutMillis(Long regexMatchingTimeoutMillis) {
        this.regexMatchingTimeoutMillis = regexMatchingTimeoutMillis;
        return this;
    }

    public Long xpathMatchingTimeoutMillis() {
        if (xpathMatchingTimeoutMillis == null) {
            return ConfigurationProperties.xpathMatchingTimeoutMillis();
        }
        return xpathMatchingTimeoutMillis;
    }

    /**
     * Maximum time (in milliseconds) allowed for evaluating a single XPath expression during request
     * matching. Exceeding the budget is treated as a non-match. Default 5000. Set to 0 or negative to
     * disable the timeout.
     *
     * @param xpathMatchingTimeoutMillis XPath evaluation timeout in milliseconds
     */
    public Configuration xpathMatchingTimeoutMillis(Long xpathMatchingTimeoutMillis) {
        this.xpathMatchingTimeoutMillis = xpathMatchingTimeoutMillis;
        return this;
    }

    public String customJsonUnitMatchersClass() {
        if (customJsonUnitMatchersClass == null) {
            return ConfigurationProperties.customJsonUnitMatchersClass();
        }
        return customJsonUnitMatchersClass;
    }

    /**
     * Fully qualified name of a class implementing
     * {@code org.mockserver.matchers.CustomJsonUnitMatcherProvider}, whose matchers are registered with
     * the json-unit configuration used for JSON body matching. Empty by default (no custom matchers).
     *
     * @param customJsonUnitMatchersClass fully qualified provider class name
     */
    public Configuration customJsonUnitMatchersClass(String customJsonUnitMatchersClass) {
        this.customJsonUnitMatchersClass = customJsonUnitMatchersClass;
        return this;
    }

    public void addSubjectAlternativeName(String host) {
        if (isNotBlank(host)) {
            String hostWithoutPort = substringBefore(host, ":");
            if (isNotBlank(hostWithoutPort)) {
                if (InetAddresses.isInetAddress(hostWithoutPort)) {
                    addSslSubjectAlternativeNameIps(hostWithoutPort);
                } else {
                    addSslSubjectAlternativeNameDomains(hostWithoutPort);
                }
            }
        }
    }

    public void addSslSubjectAlternativeNameIps(String... additionalSubjectAlternativeNameIps) {
        boolean subjectAlternativeIpsModified = false;
        Set<String> sslSubjectAlternativeNameIps = sslSubjectAlternativeNameIps();
        for (String subjectAlternativeIp : additionalSubjectAlternativeNameIps) {
            if (sslSubjectAlternativeNameIps.add(subjectAlternativeIp.trim())) {
                subjectAlternativeIpsModified = true;
            }
        }
        if (subjectAlternativeIpsModified) {
            rebuildServerTLSContext(true);
            sslSubjectAlternativeNameIps(sslSubjectAlternativeNameIps);
        }
    }

    public void clearSslSubjectAlternativeNameIps() {
        sslSubjectAlternativeNameIps.clear();
        rebuildServerTLSContext(true);
    }

    public void addSslSubjectAlternativeNameDomains(String... additionalSubjectAlternativeNameDomains) {
        boolean subjectAlternativeDomainsModified = false;
        Set<String> sslSubjectAlternativeNameDomains = sslSubjectAlternativeNameDomains();
        for (String subjectAlternativeDomain : additionalSubjectAlternativeNameDomains) {
            if (sslSubjectAlternativeNameDomains.add(subjectAlternativeDomain.trim())) {
                subjectAlternativeDomainsModified = true;
            }
        }
        if (subjectAlternativeDomainsModified) {
            rebuildServerTLSContext(true);
            sslSubjectAlternativeNameDomains(sslSubjectAlternativeNameDomains);
        }
    }

    public void clearSslSubjectAlternativeNameDomains() {
        sslSubjectAlternativeNameDomains.clear();
        rebuildServerTLSContext(true);
    }

    public int ringBufferSize() {
        // Resolve the dedicated ring-buffer knob: instance field -> explicit mockserver.ringBufferSize
        // property -> default min(this.maxLogEntries(), 16384). The default cap is based on THIS
        // configuration's maxLogEntries (not the static/global one) so per-instance retention sizing is
        // honoured. The disruptor requires a power-of-two size, so the resolved value is rounded up.
        // This decouples the in-flight ring from maxLogEntries retention so a large retention setting
        // no longer forces a large pre-allocated ring.
        int size = ringBufferSize != null ? ringBufferSize : ConfigurationProperties.resolveRingBufferSize(maxLogEntries());
        return nextPowerOfTwo(size);
    }

    /**
     * Round up to a power of two, IDEMPOTENTLY — a value that is already a power of two is returned
     * unchanged.
     * <p>
     * This previously returned the next power of two STRICTLY GREATER than the input, which made
     * {@link #ringBufferSize()} non-idempotent: reading the resolved value and writing it back (as
     * {@code GET /mockserver/configuration} followed by {@code PUT} of the same blob does, via
     * {@code ConfigurationDTO}) doubled it on every round trip — 1024 became 2048, then 4096. The
     * documented contract is "rounded up to the next power of two", and an exact power of two is
     * already there, so it must be a no-op.
     */
    private int nextPowerOfTwo(int value) {
        for (int i = 0; i < 30; i++) {
            int powOfTwo = 1 << i;
            if (powOfTwo >= value) {
                return powOfTwo;
            }
        }
        return 1 << 30;
    }

}

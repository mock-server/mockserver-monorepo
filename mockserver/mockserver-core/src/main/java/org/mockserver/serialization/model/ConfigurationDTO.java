package org.mockserver.serialization.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.mockserver.configuration.Configuration;
import org.mockserver.configuration.ConfigurationProperties;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.slf4j.event.Level;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.Set;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class ConfigurationDTO implements DTO<Configuration> {

    private String logLevel;
    private Boolean disableSystemOut;
    private Boolean disableLogging;
    private Boolean detailedMatchFailures;
    private Boolean launchUIForLogLevelDebug;
    private Boolean metricsEnabled;
    private Boolean dashboardAnalyticsEnabled;
    private String dashboardAnalyticsEndpoint;
    private String dashboardAnalyticsKey;
    private String dashboardAnalyticsDistribution;
    private Boolean chaosAutoHaltEnabled;
    private Long chaosAutoHaltErrorThreshold;
    private Long chaosAutoHaltWindowMillis;
    private Boolean mcpEnabled;
    private Long breakpointTimeoutMillis;
    private Integer breakpointMaxHeld;
    private Map<String, String> logLevelOverrides;
    private Boolean compactLogFormat;

    private Boolean devMode;

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

    private Integer nioEventLoopThreadCount;
    private Integer actionHandlerThreadCount;
    private Integer clientNioEventLoopThreadCount;
    private Integer webSocketClientEventLoopThreadCount;
    private Long maxFutureTimeoutInMillis;
    private Boolean matchersFailFast;
    private Boolean matchExactCase;

    private Long maxSocketTimeoutInMillis;
    private Long socketConnectionTimeoutInMillis;
    private DelayDTO connectionDelay;
    private Boolean alwaysCloseSocketConnections;
    private String localBoundIP;

    private Integer maxInitialLineLength;
    private Integer maxHeaderSize;
    private Integer maxChunkSize;
    private Boolean useSemicolonAsQueryParameterSeparator;
    private Boolean startupWarmup;
    private Boolean assumeAllRequestsAreHttp;

    private Boolean forwardBinaryRequestsWithoutWaitingForResponse;

    private Boolean enableCORSForAPI;
    private Boolean enableCORSForAllResponses;
    private String corsAllowOrigin;
    private String corsAllowMethods;
    private String corsAllowHeaders;
    private Boolean corsAllowCredentials;
    private Integer corsMaxAgeInSeconds;

    private String defaultResponseHeaders;

    private String javascriptDisallowedClasses;
    private String javascriptAllowedClasses;
    private String javascriptDisallowedText;
    private Long javascriptTemplateExecutionTimeout;
    private Boolean velocityDisallowClassLoading;
    private String velocityDisallowedText;
    private String mustacheDisallowedText;
    private Long templateFakerSeed;

    private String initializationClass;
    private String initializationJsonPath;
    private String initializationOpenAPIPath;
    private String openAPIContextPathPrefix;
    private Boolean openAPIResponseValidation;
    private String validateProxyOpenAPISpec;
    private Boolean validateProxyEnforce;
    private Boolean generateRealisticExampleValues;
    private Boolean watchInitializationJson;
    private Long watchInitializationJsonPollPeriodMillis;
    private Boolean failOnInitializationError;

    private Boolean persistExpectations;
    private String persistedExpectationsPath;

    private String auditLogFile;

    private Boolean persistRecordedExpectations;
    private String persistedRecordedExpectationsPath;

    private Boolean persistRecordedRequestsToDisk;
    private String persistedRecordedRequestsPath;

    private Integer maximumNumberOfRequestToReturnInVerificationFailure;
    private Boolean attachMismatchDiagnosticToResponse;
    private Boolean closestMatchHintEnabled;

    private Boolean attemptToProxyIfNoMatchingExpectation;
    private String forwardHttpProxy;
    private String forwardHttpsProxy;
    private String forwardSocksProxy;
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
    private String proxyRemoteHost;
    private Integer proxyRemotePort;
    private java.util.List<org.mockserver.model.ProxyPassMapping> proxyPassMappings;

    private String livenessHttpGetPath;

    private String matchNamespaceHeader;

    private Boolean controlPlaneTLSMutualAuthenticationRequired;
    private String controlPlaneTLSMutualAuthenticationCAChain;
    private String controlPlanePrivateKeyPath;
    private String controlPlaneX509CertificatePath;
    private Boolean controlPlaneJWTAuthenticationRequired;
    private String controlPlaneJWTAuthenticationJWKSource;
    private String controlPlaneJWTAuthenticationExpectedAudience;
    private Map<String, String> controlPlaneJWTAuthenticationMatchingClaims;
    private Set<String> controlPlaneJWTAuthenticationRequiredClaims;

    private Boolean proactivelyInitialiseTLS;
    private String tlsProtocols;
    private Boolean dynamicallyCreateCertificateAuthorityCertificate;
    private String directoryToSaveDynamicSSLCertificate;
    private Boolean proxySetup;
    private Boolean proxySetupLogging;
    private Boolean preventCertificateDynamicUpdate;
    private String sslCertificateDomainName;
    private Set<String> sslSubjectAlternativeNameDomains;
    private Set<String> sslSubjectAlternativeNameIps;
    private Integer maxSubjectAlternativeNames;
    private Integer sslCertificateLeafValidityInDays;
    private String certificateAuthorityPrivateKey;
    private String certificateAuthorityCertificate;
    private String privateKeyPath;
    private String x509CertificatePath;
    private Boolean tlsMutualAuthenticationRequired;
    private String tlsMutualAuthenticationCertificateChain;

    private String forwardProxyTLSX509CertificatesTrustManagerType;
    private String forwardProxyTLSCustomTrustX509Certificates;
    private String forwardProxyPrivateKey;
    private String forwardProxyCertificateChain;
    private String forwardProxyClientCertificatesByHost;

    private Long slowRequestThresholdMillis;
    private Boolean metricsRequestDurationRouteLabels;
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
    private String loadScenarioInitializationJsonPath;
    private java.util.List<String> loadGenerationMetricLabels;
    private Boolean llmMetricsEnabled;
    private Boolean perExpectationMetricsEnabled;
    private Boolean deduplicateRecordedExpectations;
    private Boolean templatizeRecordedValues;
    private Boolean redactSecretsInRecordedExpectations;
    private Boolean redactSecretsInLog;
    private Double llmCostBudgetUsd;
    private Boolean otelPropagateTraceContext;
    private Boolean otelGenerateTraceId;
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
    private Boolean useNativeTransport;
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
    private Boolean http2Enabled;
    private Boolean streamingResponsesEnabled;
    private Integer maxStreamingCaptureBytes;
    private Integer streamIdleTimeoutSeconds;
    private Boolean validateRequestsAgainstOpenApiSpec;
    private Boolean detailedVerificationFailures;
    private Boolean failVerificationOnEvictedLog;
    private Long globalResponseDelayMillis;
    private Boolean forwardAdjustHostHeader;
    private String forwardDefaultHostHeader;
    private Boolean forwardProxyBlockPrivateNetworks;
    private Boolean forwardProxyTLSHostnameVerificationEnabled;
    private Boolean tlsAllowInsecureProtocols;
    private String stateBackend;
    private String blobStoreType;
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
    private Boolean clusterEnabled;
    private String clusterName;
    private String clusterTransportConfig;
    private Boolean clusterSharedTimesEnabled;
    private Boolean clusterVerifyFanIn;
    private String clusterVerifyFanInPeers;
    private String clusterFanInPeerAuthToken;
    private Boolean controlPlaneOidcAuthenticationRequired;
    private String controlPlaneOidcIssuer;
    private String controlPlaneOidcJwksUri;
    private String controlPlaneOidcAudience;
    private Set<String> controlPlaneOidcRequiredScopes;
    private String controlPlaneOidcScopeClaim;
    private Boolean controlPlaneAuthorizationEnabled;
    private Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> controlPlaneScopeMapping;
    private Boolean transparentProxyEnabled;
    private Boolean transparentProxyTproxy;
    private Boolean transparentProxyEbpf;
    private String transparentProxyEbpfMapPath;
    private String asyncKafkaBootstrapServers;
    private String asyncMqttBrokerUrl;
    private String asyncAmqpUri;
    private Integer asyncRecordedMessageMaxEntries;

    private String llmProvider;
    private String llmModel;
    private String llmBaseUrl;
    private String llmBackendsConfig;
    private Long llmRequestTimeoutMillis;
    private Boolean llmSemanticMatchingEnabled;
    private Boolean llmInferUsageEnabled;
    private Boolean llmVcrStrict;
    private Integer llmOptimisationMaxCalls;
    private String fixtureBodyRedactFields;
    private String otelEndpoint;
    private Boolean otelMetricsEnabled;
    private Boolean otelTracesEnabled;
    private Long otelMetricsExportIntervalSeconds;
    private String otelMetricsTemporality;
    private Boolean prometheusRemoteWriteEnabled;
    private String prometheusRemoteWriteUrl;
    private Long prometheusRemoteWriteIntervalSeconds;
    private String prometheusRemoteWriteBasicAuthUsername;
    private String prometheusRemoteWriteHeaders;
    private String prometheusRemoteWriteProtocolVersion;
    private Long regexMatchingTimeoutMillis;
    private Long xpathMatchingTimeoutMillis;
    private String customJsonUnitMatchersClass;

    // ---------------------------------------------------------------------------------------------
    // WRITE-ONLY CREDENTIALS
    //
    // These three fields hold the REAL secret in memory so buildObject()/applyTo() stay fully
    // functional, but their JSON getters return ConfigurationProperties.REDACTED_VALUE, so
    // GET /mockserver/configuration never emits the real value. See maskCredential(...) and
    // isMaskedCredential(...) below for the read mask and the round-trip guard respectively.
    // ---------------------------------------------------------------------------------------------
    private String llmApiKey;
    private String prometheusRemoteWriteBearerToken;
    private String prometheusRemoteWriteBasicAuthPassword;

    public ConfigurationDTO() {
    }

    public ConfigurationDTO(Configuration configuration) {
        if (configuration != null) {
            Level level = configuration.logLevel();
            if (level != null) {
                this.logLevel = level.name();
            }
            this.disableSystemOut = configuration.disableSystemOut();
            this.disableLogging = configuration.disableLogging();
            this.detailedMatchFailures = configuration.detailedMatchFailures();
            this.launchUIForLogLevelDebug = configuration.launchUIForLogLevelDebug();
            this.metricsEnabled = configuration.metricsEnabled();
            this.dashboardAnalyticsEnabled = configuration.dashboardAnalyticsEnabled();
            this.dashboardAnalyticsEndpoint = configuration.dashboardAnalyticsEndpoint();
            this.dashboardAnalyticsKey = configuration.dashboardAnalyticsKey();
            this.dashboardAnalyticsDistribution = configuration.dashboardAnalyticsDistribution();
            this.chaosAutoHaltEnabled = configuration.chaosAutoHaltEnabled();
            this.chaosAutoHaltErrorThreshold = configuration.chaosAutoHaltErrorThreshold();
            this.chaosAutoHaltWindowMillis = configuration.chaosAutoHaltWindowMillis();
            this.mcpEnabled = configuration.mcpEnabled();
            this.breakpointTimeoutMillis = configuration.breakpointTimeoutMillis();
            this.breakpointMaxHeld = configuration.breakpointMaxHeld();
            Map<String, String> overrides = configuration.logLevelOverrides();
            this.logLevelOverrides = overrides != null && !overrides.isEmpty() ? overrides : null;
            this.compactLogFormat = configuration.compactLogFormat();

            this.devMode = configuration.devMode();

            this.maxExpectations = configuration.maxExpectations();
            this.maxLogEntries = configuration.maxLogEntries();
            this.maxEventLogSizeInBytes = configuration.maxEventLogSizeInBytes();
            this.maxLoggedBodyBytes = configuration.maxLoggedBodyBytes();
            this.ringBufferSize = configuration.ringBufferSize();
            this.maxWebSocketExpectations = configuration.maxWebSocketExpectations();
            this.webSocketProxyMaxRecordedFrames = configuration.webSocketProxyMaxRecordedFrames();
            this.webSocketProxyIdleTimeoutSeconds = configuration.webSocketProxyIdleTimeoutSeconds();
            this.outputMemoryUsageCsv = configuration.outputMemoryUsageCsv();
            this.memoryUsageCsvDirectory = configuration.memoryUsageCsvDirectory();

            this.nioEventLoopThreadCount = configuration.nioEventLoopThreadCount();
            this.actionHandlerThreadCount = configuration.actionHandlerThreadCount();
            this.clientNioEventLoopThreadCount = configuration.clientNioEventLoopThreadCount();
            this.webSocketClientEventLoopThreadCount = configuration.webSocketClientEventLoopThreadCount();
            this.maxFutureTimeoutInMillis = configuration.maxFutureTimeoutInMillis();
            this.matchersFailFast = configuration.matchersFailFast();
            this.matchExactCase = configuration.matchExactCase();

            this.maxSocketTimeoutInMillis = configuration.maxSocketTimeoutInMillis();
            this.socketConnectionTimeoutInMillis = configuration.socketConnectionTimeoutInMillis();
            if (configuration.connectionDelay() != null) {
                this.connectionDelay = new DelayDTO(configuration.connectionDelay());
            }
            this.alwaysCloseSocketConnections = configuration.alwaysCloseSocketConnections();
            this.localBoundIP = configuration.localBoundIP();

            this.maxInitialLineLength = configuration.maxInitialLineLength();
            this.maxHeaderSize = configuration.maxHeaderSize();
            this.maxChunkSize = configuration.maxChunkSize();
            this.useSemicolonAsQueryParameterSeparator = configuration.useSemicolonAsQueryParameterSeparator();
            this.startupWarmup = configuration.startupWarmup();
            this.assumeAllRequestsAreHttp = configuration.assumeAllRequestsAreHttp();

            this.forwardBinaryRequestsWithoutWaitingForResponse = configuration.forwardBinaryRequestsWithoutWaitingForResponse();

            this.enableCORSForAPI = configuration.enableCORSForAPI();
            this.enableCORSForAllResponses = configuration.enableCORSForAllResponses();
            this.corsAllowOrigin = configuration.corsAllowOrigin();
            this.corsAllowMethods = configuration.corsAllowMethods();
            this.corsAllowHeaders = configuration.corsAllowHeaders();
            this.corsAllowCredentials = configuration.corsAllowCredentials();
            this.corsMaxAgeInSeconds = configuration.corsMaxAgeInSeconds();
            this.defaultResponseHeaders = configuration.defaultResponseHeaders();

            this.javascriptDisallowedClasses = configuration.javascriptDisallowedClasses();
            this.javascriptAllowedClasses = configuration.javascriptAllowedClasses();
            this.javascriptDisallowedText = configuration.javascriptDisallowedText();
            this.javascriptTemplateExecutionTimeout = configuration.javascriptTemplateExecutionTimeout();
            this.velocityDisallowClassLoading = configuration.velocityDisallowClassLoading();
            this.velocityDisallowedText = configuration.velocityDisallowedText();
            this.mustacheDisallowedText = configuration.mustacheDisallowedText();
            this.templateFakerSeed = configuration.templateFakerSeed();

            this.initializationClass = configuration.initializationClass();
            this.initializationJsonPath = configuration.initializationJsonPath();
            this.initializationOpenAPIPath = configuration.initializationOpenAPIPath();
            this.openAPIContextPathPrefix = configuration.openAPIContextPathPrefix();
            this.openAPIResponseValidation = configuration.openAPIResponseValidation();
            this.validateProxyOpenAPISpec = configuration.validateProxyOpenAPISpec();
            this.validateProxyEnforce = configuration.validateProxyEnforce();
            this.generateRealisticExampleValues = configuration.generateRealisticExampleValues();
            this.watchInitializationJson = configuration.watchInitializationJson();
            this.watchInitializationJsonPollPeriodMillis = configuration.watchInitializationJsonPollPeriodMillis();
            this.failOnInitializationError = configuration.failOnInitializationError();

            this.persistExpectations = configuration.persistExpectations();
            this.persistedExpectationsPath = configuration.persistedExpectationsPath();

            this.auditLogFile = configuration.auditLogFile();

            this.persistRecordedExpectations = configuration.persistRecordedExpectations();
            this.persistedRecordedExpectationsPath = configuration.persistedRecordedExpectationsPath();

            this.persistRecordedRequestsToDisk = configuration.persistRecordedRequestsToDisk();
            this.persistedRecordedRequestsPath = configuration.persistedRecordedRequestsPath();

            this.maximumNumberOfRequestToReturnInVerificationFailure = configuration.maximumNumberOfRequestToReturnInVerificationFailure();
            this.attachMismatchDiagnosticToResponse = configuration.attachMismatchDiagnosticToResponse();
            this.closestMatchHintEnabled = configuration.closestMatchHintEnabled();

            this.attemptToProxyIfNoMatchingExpectation = configuration.attemptToProxyIfNoMatchingExpectation();
            InetSocketAddress httpProxy = configuration.forwardHttpProxy();
            if (httpProxy != null) {
                this.forwardHttpProxy = httpProxy.getHostString() + ":" + httpProxy.getPort();
            }
            InetSocketAddress httpsProxy = configuration.forwardHttpsProxy();
            if (httpsProxy != null) {
                this.forwardHttpsProxy = httpsProxy.getHostString() + ":" + httpsProxy.getPort();
            }
            InetSocketAddress socksProxy = configuration.forwardSocksProxy();
            if (socksProxy != null) {
                this.forwardSocksProxy = socksProxy.getHostString() + ":" + socksProxy.getPort();
            }
            this.forwardProxyAuthenticationUsername = configuration.forwardProxyAuthenticationUsername();
            this.forwardProxyAuthenticationPassword = configuration.forwardProxyAuthenticationPassword();
            this.proxyAuthenticationRealm = configuration.proxyAuthenticationRealm();
            this.proxyAuthenticationUsername = configuration.proxyAuthenticationUsername();
            this.proxyAuthenticationPassword = configuration.proxyAuthenticationPassword();
            this.dataPlaneAuthenticationRequired = configuration.dataPlaneAuthenticationRequired();
            this.dataPlaneBasicAuthenticationUsername = configuration.dataPlaneBasicAuthenticationUsername();
            this.dataPlaneBasicAuthenticationPassword = configuration.dataPlaneBasicAuthenticationPassword();
            this.dataPlaneBasicAuthenticationRealm = configuration.dataPlaneBasicAuthenticationRealm();
            this.dataPlaneBearerAuthenticationToken = configuration.dataPlaneBearerAuthenticationToken();
            this.dataPlaneApiKeyAuthenticationHeader = configuration.dataPlaneApiKeyAuthenticationHeader();
            this.dataPlaneApiKeyAuthenticationValue = configuration.dataPlaneApiKeyAuthenticationValue();
            this.noProxyHosts = configuration.noProxyHosts();
            this.proxyRemoteHost = configuration.proxyRemoteHost();
            this.proxyRemotePort = configuration.proxyRemotePort();
            java.util.List<org.mockserver.model.ProxyPassMapping> proxyPassMappings = configuration.proxyPassMappings();
            this.proxyPassMappings = proxyPassMappings != null && !proxyPassMappings.isEmpty() ? proxyPassMappings : null;

            this.livenessHttpGetPath = configuration.livenessHttpGetPath();
            this.matchNamespaceHeader = configuration.matchNamespaceHeader();

            this.controlPlaneTLSMutualAuthenticationRequired = configuration.controlPlaneTLSMutualAuthenticationRequired();
            this.controlPlaneTLSMutualAuthenticationCAChain = configuration.controlPlaneTLSMutualAuthenticationCAChain();
            this.controlPlanePrivateKeyPath = configuration.controlPlanePrivateKeyPath();
            this.controlPlaneX509CertificatePath = configuration.controlPlaneX509CertificatePath();
            this.controlPlaneJWTAuthenticationRequired = configuration.controlPlaneJWTAuthenticationRequired();
            this.controlPlaneJWTAuthenticationJWKSource = configuration.controlPlaneJWTAuthenticationJWKSource();
            this.controlPlaneJWTAuthenticationExpectedAudience = configuration.controlPlaneJWTAuthenticationExpectedAudience();
            this.controlPlaneJWTAuthenticationMatchingClaims = configuration.controlPlaneJWTAuthenticationMatchingClaims();
            this.controlPlaneJWTAuthenticationRequiredClaims = configuration.controlPlaneJWTAuthenticationRequiredClaims();

            this.proactivelyInitialiseTLS = configuration.proactivelyInitialiseTLS();
            this.tlsProtocols = configuration.tlsProtocols();
            this.dynamicallyCreateCertificateAuthorityCertificate = configuration.dynamicallyCreateCertificateAuthorityCertificate();
            this.directoryToSaveDynamicSSLCertificate = configuration.directoryToSaveDynamicSSLCertificate();
            this.proxySetup = configuration.proxySetup();
            this.proxySetupLogging = configuration.proxySetupLogging();
            this.preventCertificateDynamicUpdate = configuration.preventCertificateDynamicUpdate();
            this.sslCertificateDomainName = configuration.sslCertificateDomainName();
            this.sslSubjectAlternativeNameDomains = configuration.sslSubjectAlternativeNameDomains();
            this.sslSubjectAlternativeNameIps = configuration.sslSubjectAlternativeNameIps();
            this.maxSubjectAlternativeNames = configuration.maxSubjectAlternativeNames();
            this.sslCertificateLeafValidityInDays = configuration.sslCertificateLeafValidityInDays();
            this.certificateAuthorityPrivateKey = configuration.certificateAuthorityPrivateKey();
            this.certificateAuthorityCertificate = configuration.certificateAuthorityCertificate();
            this.privateKeyPath = configuration.privateKeyPath();
            this.x509CertificatePath = configuration.x509CertificatePath();
            this.tlsMutualAuthenticationRequired = configuration.tlsMutualAuthenticationRequired();
            this.tlsMutualAuthenticationCertificateChain = configuration.tlsMutualAuthenticationCertificateChain();

            ForwardProxyTLSX509CertificatesTrustManager trustManagerType = configuration.forwardProxyTLSX509CertificatesTrustManagerType();
            if (trustManagerType != null) {
                this.forwardProxyTLSX509CertificatesTrustManagerType = trustManagerType.name();
            }
            this.forwardProxyTLSCustomTrustX509Certificates = configuration.forwardProxyTLSCustomTrustX509Certificates();
            this.forwardProxyPrivateKey = configuration.forwardProxyPrivateKey();
            this.forwardProxyCertificateChain = configuration.forwardProxyCertificateChain();
            this.forwardProxyClientCertificatesByHost = configuration.forwardProxyClientCertificatesByHost();

            this.slowRequestThresholdMillis = configuration.slowRequestThresholdMillis();
            this.metricsRequestDurationRouteLabels = configuration.metricsRequestDurationRouteLabels();
            this.rateLimitMaxNamedQuotas = configuration.rateLimitMaxNamedQuotas();
            this.connectionLifecycleChaosEnabled = configuration.connectionLifecycleChaosEnabled();
            this.preemptionSimulationMaxDrainMillis = configuration.preemptionSimulationMaxDrainMillis();
            this.stopDrainMillis = configuration.stopDrainMillis();
            this.connectionLifecycleAutoHaltCountsRst = configuration.connectionLifecycleAutoHaltCountsRst();
            this.sloTrackingEnabled = configuration.sloTrackingEnabled();
            this.sloWindowRetentionMillis = configuration.sloWindowRetentionMillis();
            this.sloWindowMaxSamples = configuration.sloWindowMaxSamples();
            this.loadGenerationEnabled = configuration.loadGenerationEnabled();
            this.loadGenerationSuppressEventLog = configuration.loadGenerationSuppressEventLog();
            this.loadGenerationMaxVirtualUsers = configuration.loadGenerationMaxVirtualUsers();
            this.loadGenerationMaxInFlightRequests = configuration.loadGenerationMaxInFlightRequests();
            this.loadGenerationMaxRequestsPerSecond = configuration.loadGenerationMaxRequestsPerSecond();
            this.loadGenerationMaxDurationMillis = configuration.loadGenerationMaxDurationMillis();
            this.loadGenerationMaxSteps = configuration.loadGenerationMaxSteps();
            this.loadGenerationMaxRate = configuration.loadGenerationMaxRate();
            this.loadGenerationMaxStages = configuration.loadGenerationMaxStages();
            this.loadGenerationMaxConcurrentScenarios = configuration.loadGenerationMaxConcurrentScenarios();
            this.loadScenarioInitializationJsonPath = configuration.loadScenarioInitializationJsonPath();
            this.loadGenerationMetricLabels = configuration.loadGenerationMetricLabels();
            this.llmMetricsEnabled = configuration.llmMetricsEnabled();
            this.perExpectationMetricsEnabled = configuration.perExpectationMetricsEnabled();
            this.deduplicateRecordedExpectations = configuration.deduplicateRecordedExpectations();
            this.templatizeRecordedValues = configuration.templatizeRecordedValues();
            this.redactSecretsInRecordedExpectations = configuration.redactSecretsInRecordedExpectations();
            this.redactSecretsInLog = configuration.redactSecretsInLog();
            this.llmCostBudgetUsd = configuration.llmCostBudgetUsd();
            this.otelPropagateTraceContext = configuration.otelPropagateTraceContext();
            this.otelGenerateTraceId = configuration.otelGenerateTraceId();
            this.wasmEnabled = configuration.wasmEnabled();
            this.wasmMaxMemoryPages = configuration.wasmMaxMemoryPages();
            this.wasmExecutionTimeoutMillis = configuration.wasmExecutionTimeoutMillis();
            this.grpcDescriptorDirectory = configuration.grpcDescriptorDirectory();
            this.grpcProtoDirectory = configuration.grpcProtoDirectory();
            this.grpcEnabled = configuration.grpcEnabled();
            this.grpcProtocPath = configuration.grpcProtocPath();
            this.grpcBidiStreamingEnabled = configuration.grpcBidiStreamingEnabled();
            this.dnsEnabled = configuration.dnsEnabled();
            this.dnsPort = configuration.dnsPort();
            this.http3Port = configuration.http3Port();
            this.http3MaxIdleTimeout = configuration.http3MaxIdleTimeout();
            this.http3InitialMaxData = configuration.http3InitialMaxData();
            this.http3InitialMaxStreamDataBidirectional = configuration.http3InitialMaxStreamDataBidirectional();
            this.http3InitialMaxStreamsBidirectional = configuration.http3InitialMaxStreamsBidirectional();
            this.http3QpackMaxTableCapacity = configuration.http3QpackMaxTableCapacity();
            this.http3ConnectUdpEnabled = configuration.http3ConnectUdpEnabled();
            this.http3ConnectUdpAllowedTargets = configuration.http3ConnectUdpAllowedTargets();
            this.http3AltSvcMaxAge = configuration.http3AltSvcMaxAge();
            this.http3AdvertiseAltSvc = configuration.http3AdvertiseAltSvc();
            this.useNativeTransport = configuration.useNativeTransport();
            this.forwardConnectionPoolEnabled = configuration.forwardConnectionPoolEnabled();
            this.forwardConnectionPoolMaxIdlePerKey = configuration.forwardConnectionPoolMaxIdlePerKey();
            this.forwardConnectionPoolIdleTimeoutMillis = configuration.forwardConnectionPoolIdleTimeoutMillis();
            this.forwardConnectionPoolKeepAlive = configuration.forwardConnectionPoolKeepAlive();
            this.forwardConnectionPoolMaxTotalPerKey = configuration.forwardConnectionPoolMaxTotalPerKey();
            this.forwardSocketKeepAlive = configuration.forwardSocketKeepAlive();
            this.forwardSocketKeepAliveIdleSeconds = configuration.forwardSocketKeepAliveIdleSeconds();
            this.forwardSocketKeepAliveIntervalSeconds = configuration.forwardSocketKeepAliveIntervalSeconds();
            this.forwardSocketKeepAliveCount = configuration.forwardSocketKeepAliveCount();
            this.forwardProxyRetryCount = configuration.forwardProxyRetryCount();
            this.forwardProxyRetryBackoffMillis = configuration.forwardProxyRetryBackoffMillis();
            this.forwardProxyHttp2Enabled = configuration.forwardProxyHttp2Enabled();
            this.forwardProxyHttp2Upgrade = configuration.forwardProxyHttp2Upgrade();
            this.forwardProxyCircuitBreakerEnabled = configuration.forwardProxyCircuitBreakerEnabled();
            this.forwardProxyCircuitBreakerFailureThreshold = configuration.forwardProxyCircuitBreakerFailureThreshold();
            this.forwardProxyCircuitBreakerWindowMillis = configuration.forwardProxyCircuitBreakerWindowMillis();
            this.enforceResponseValidationForMocks = configuration.enforceResponseValidationForMocks();
            this.maxRequestBodySize = configuration.maxRequestBodySize();
            this.maxGrpcMessageSize = configuration.maxGrpcMessageSize();
            this.maxResponseBodySize = configuration.maxResponseBodySize();
            this.maxLlmConversationBodySize = configuration.maxLlmConversationBodySize();
            this.driftDetectionEnabled = configuration.driftDetectionEnabled();
            this.driftSampleRate = configuration.driftSampleRate();
            this.driftSemanticAnalysisEnabled = configuration.driftSemanticAnalysisEnabled();
            this.driftResponseTimeThresholdMs = configuration.driftResponseTimeThresholdMs();
            this.driftAlertWebhookEnabled = configuration.driftAlertWebhookEnabled();
            this.driftAlertWebhookUrl = configuration.driftAlertWebhookUrl();
            this.driftAlertSeverityThreshold = configuration.driftAlertSeverityThreshold();
            this.driftAlertCooldownMillis = configuration.driftAlertCooldownMillis();
            this.controlPlaneAuditEnabled = configuration.controlPlaneAuditEnabled();
            this.controlPlaneAuditMaxEntries = configuration.controlPlaneAuditMaxEntries();
            this.controlPlaneAuditReads = configuration.controlPlaneAuditReads();
            this.http2Enabled = configuration.http2Enabled();
            this.streamingResponsesEnabled = configuration.streamingResponsesEnabled();
            this.maxStreamingCaptureBytes = configuration.maxStreamingCaptureBytes();
            this.streamIdleTimeoutSeconds = configuration.streamIdleTimeoutSeconds();
            this.validateRequestsAgainstOpenApiSpec = configuration.validateRequestsAgainstOpenApiSpec();
            this.detailedVerificationFailures = configuration.detailedVerificationFailures();
            this.failVerificationOnEvictedLog = configuration.failVerificationOnEvictedLog();
            this.globalResponseDelayMillis = configuration.globalResponseDelayMillis();
            this.forwardAdjustHostHeader = configuration.forwardAdjustHostHeader();
            this.forwardDefaultHostHeader = configuration.forwardDefaultHostHeader();
            this.forwardProxyBlockPrivateNetworks = configuration.forwardProxyBlockPrivateNetworks();
            this.forwardProxyTLSHostnameVerificationEnabled = configuration.forwardProxyTLSHostnameVerificationEnabled();
            this.tlsAllowInsecureProtocols = configuration.tlsAllowInsecureProtocols();
            this.stateBackend = configuration.stateBackend();
            this.blobStoreType = configuration.blobStoreType();
            this.blobStoreBucket = configuration.blobStoreBucket();
            this.blobStoreRegion = configuration.blobStoreRegion();
            this.blobStoreEndpoint = configuration.blobStoreEndpoint();
            this.blobStoreKeyPrefix = configuration.blobStoreKeyPrefix();
            this.blobStoreAccessKeyId = configuration.blobStoreAccessKeyId();
            this.blobStoreSecretAccessKey = configuration.blobStoreSecretAccessKey();
            this.blobStoreContainer = configuration.blobStoreContainer();
            this.blobStoreConnectionString = configuration.blobStoreConnectionString();
            this.blobStoreProjectId = configuration.blobStoreProjectId();
            this.blobStoreRestoreTimeoutSeconds = configuration.blobStoreRestoreTimeoutSeconds();
            this.clusterEnabled = configuration.clusterEnabled();
            this.clusterName = configuration.clusterName();
            this.clusterTransportConfig = configuration.clusterTransportConfig();
            this.clusterSharedTimesEnabled = configuration.clusterSharedTimesEnabled();
            this.clusterVerifyFanIn = configuration.clusterVerifyFanIn();
            this.clusterVerifyFanInPeers = configuration.clusterVerifyFanInPeers();
            this.clusterFanInPeerAuthToken = configuration.clusterFanInPeerAuthToken();
            this.controlPlaneOidcAuthenticationRequired = configuration.controlPlaneOidcAuthenticationRequired();
            this.controlPlaneOidcIssuer = configuration.controlPlaneOidcIssuer();
            this.controlPlaneOidcJwksUri = configuration.controlPlaneOidcJwksUri();
            this.controlPlaneOidcAudience = configuration.controlPlaneOidcAudience();
            this.controlPlaneOidcRequiredScopes = configuration.controlPlaneOidcRequiredScopes();
            this.controlPlaneOidcScopeClaim = configuration.controlPlaneOidcScopeClaim();
            this.controlPlaneAuthorizationEnabled = configuration.controlPlaneAuthorizationEnabled();
            Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> scopeMapping = configuration.controlPlaneScopeMapping();
            this.controlPlaneScopeMapping = scopeMapping != null && !scopeMapping.isEmpty() ? scopeMapping : null;
            this.transparentProxyEnabled = configuration.transparentProxyEnabled();
            this.transparentProxyTproxy = configuration.transparentProxyTproxy();
            this.transparentProxyEbpf = configuration.transparentProxyEbpf();
            this.transparentProxyEbpfMapPath = configuration.transparentProxyEbpfMapPath();
            this.asyncKafkaBootstrapServers = configuration.asyncKafkaBootstrapServers();
            this.asyncMqttBrokerUrl = configuration.asyncMqttBrokerUrl();
            this.asyncAmqpUri = configuration.asyncAmqpUri();
            this.asyncRecordedMessageMaxEntries = configuration.asyncRecordedMessageMaxEntries();

            this.llmProvider = configuration.llmProvider();
            this.llmModel = configuration.llmModel();
            this.llmBaseUrl = configuration.llmBaseUrl();
            this.llmBackendsConfig = configuration.llmBackendsConfig();
            this.llmRequestTimeoutMillis = configuration.llmRequestTimeoutMillis();
            this.llmSemanticMatchingEnabled = configuration.llmSemanticMatchingEnabled();
            this.llmInferUsageEnabled = configuration.llmInferUsageEnabled();
            this.llmVcrStrict = configuration.llmVcrStrict();
            this.llmOptimisationMaxCalls = configuration.llmOptimisationMaxCalls();
            this.fixtureBodyRedactFields = configuration.fixtureBodyRedactFields();
            this.otelEndpoint = configuration.otelEndpoint();
            this.otelMetricsEnabled = configuration.otelMetricsEnabled();
            this.otelTracesEnabled = configuration.otelTracesEnabled();
            this.otelMetricsExportIntervalSeconds = configuration.otelMetricsExportIntervalSeconds();
            this.otelMetricsTemporality = configuration.otelMetricsTemporality();
            this.prometheusRemoteWriteEnabled = configuration.prometheusRemoteWriteEnabled();
            this.prometheusRemoteWriteUrl = configuration.prometheusRemoteWriteUrl();
            this.prometheusRemoteWriteIntervalSeconds = configuration.prometheusRemoteWriteIntervalSeconds();
            this.prometheusRemoteWriteBasicAuthUsername = configuration.prometheusRemoteWriteBasicAuthUsername();
            this.prometheusRemoteWriteHeaders = configuration.prometheusRemoteWriteHeaders();
            this.prometheusRemoteWriteProtocolVersion = configuration.prometheusRemoteWriteProtocolVersion();
            this.regexMatchingTimeoutMillis = configuration.regexMatchingTimeoutMillis();
            this.xpathMatchingTimeoutMillis = configuration.xpathMatchingTimeoutMillis();
            this.customJsonUnitMatchersClass = configuration.customJsonUnitMatchersClass();

            // write-only credentials: the REAL value is copied into the DTO so buildObject()/applyTo()
            // remain fully functional; the masking happens on the JSON getters, not here
            this.llmApiKey = configuration.llmApiKey();
            this.prometheusRemoteWriteBearerToken = configuration.prometheusRemoteWriteBearerToken();
            this.prometheusRemoteWriteBasicAuthPassword = configuration.prometheusRemoteWriteBasicAuthPassword();
        }
    }

    private void validateFields() {
        if (logLevel != null) {
            try {
                Level.valueOf(logLevel);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid logLevel: \"" + logLevel + "\", valid values are TRACE, DEBUG, INFO, WARN, ERROR");
            }
        }
        if (maxExpectations != null && (maxExpectations < 0 || maxExpectations > 100000)) {
            throw new IllegalArgumentException("maxExpectations must be between 0 and 100000, got: " + maxExpectations);
        }
        if (maxLogEntries != null && (maxLogEntries < 0 || maxLogEntries > 1000000)) {
            throw new IllegalArgumentException("maxLogEntries must be between 0 and 1000000, got: " + maxLogEntries);
        }
        if (maxEventLogSizeInBytes != null && maxEventLogSizeInBytes < 0) {
            throw new IllegalArgumentException("maxEventLogSizeInBytes must be greater than or equal to 0, got: " + maxEventLogSizeInBytes);
        }
        if (maxLoggedBodyBytes != null && maxLoggedBodyBytes < 0) {
            throw new IllegalArgumentException("maxLoggedBodyBytes must be greater than or equal to 0, got: " + maxLoggedBodyBytes);
        }
        if (ringBufferSize != null && (ringBufferSize < 0 || ringBufferSize > 1073741824)) {
            throw new IllegalArgumentException("ringBufferSize must be between 0 and 1073741824, got: " + ringBufferSize);
        }
        if (maxWebSocketExpectations != null && (maxWebSocketExpectations < 0 || maxWebSocketExpectations > 100000)) {
            throw new IllegalArgumentException("maxWebSocketExpectations must be between 0 and 100000, got: " + maxWebSocketExpectations);
        }
        if (webSocketProxyMaxRecordedFrames != null && (webSocketProxyMaxRecordedFrames < 0 || webSocketProxyMaxRecordedFrames > 1000000)) {
            throw new IllegalArgumentException("webSocketProxyMaxRecordedFrames must be between 0 and 1000000, got: " + webSocketProxyMaxRecordedFrames);
        }
        if (webSocketProxyIdleTimeoutSeconds != null && (webSocketProxyIdleTimeoutSeconds < 0 || webSocketProxyIdleTimeoutSeconds > 86400)) {
            throw new IllegalArgumentException("webSocketProxyIdleTimeoutSeconds must be between 0 and 86400, got: " + webSocketProxyIdleTimeoutSeconds);
        }
        if (forwardProxyTLSX509CertificatesTrustManagerType != null) {
            try {
                ForwardProxyTLSX509CertificatesTrustManager.valueOf(forwardProxyTLSX509CertificatesTrustManagerType);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("Invalid forwardProxyTLSX509CertificatesTrustManagerType: \"" + forwardProxyTLSX509CertificatesTrustManagerType + "\"");
            }
        }
        if (forwardHttpProxy != null) {
            parseInetSocketAddress(forwardHttpProxy);
        }
        if (forwardHttpsProxy != null) {
            parseInetSocketAddress(forwardHttpsProxy);
        }
        if (forwardSocksProxy != null) {
            parseInetSocketAddress(forwardSocksProxy);
        }
    }

    @Override
    public Configuration buildObject() {
        validateFields();
        Configuration configuration = Configuration.configuration();
        if (logLevel != null) {
            configuration.logLevel(Level.valueOf(logLevel));
        }
        configuration.disableSystemOut(disableSystemOut);
        configuration.disableLogging(disableLogging);
        configuration.detailedMatchFailures(detailedMatchFailures);
        configuration.launchUIForLogLevelDebug(launchUIForLogLevelDebug);
        configuration.metricsEnabled(metricsEnabled);
        configuration.dashboardAnalyticsEnabled(dashboardAnalyticsEnabled);
        configuration.dashboardAnalyticsEndpoint(dashboardAnalyticsEndpoint);
        configuration.dashboardAnalyticsKey(maskFreeOrUnset(dashboardAnalyticsKey));
        configuration.dashboardAnalyticsDistribution(dashboardAnalyticsDistribution);
        configuration.chaosAutoHaltEnabled(chaosAutoHaltEnabled);
        configuration.chaosAutoHaltErrorThreshold(chaosAutoHaltErrorThreshold);
        configuration.chaosAutoHaltWindowMillis(chaosAutoHaltWindowMillis);
        configuration.mcpEnabled(mcpEnabled);
        configuration.breakpointTimeoutMillis(breakpointTimeoutMillis);
        configuration.breakpointMaxHeld(breakpointMaxHeld);
        configuration.logLevelOverrides(logLevelOverrides);
        configuration.compactLogFormat(compactLogFormat);

        configuration.devMode(devMode);

        configuration.maxExpectations(maxExpectations);
        configuration.maxLogEntries(maxLogEntries);
        configuration.maxEventLogSizeInBytes(maxEventLogSizeInBytes);
        configuration.maxLoggedBodyBytes(maxLoggedBodyBytes);
        configuration.ringBufferSize(ringBufferSize);
        configuration.maxWebSocketExpectations(maxWebSocketExpectations);
        configuration.webSocketProxyMaxRecordedFrames(webSocketProxyMaxRecordedFrames);
        configuration.webSocketProxyIdleTimeoutSeconds(webSocketProxyIdleTimeoutSeconds);
        configuration.outputMemoryUsageCsv(outputMemoryUsageCsv);
        configuration.memoryUsageCsvDirectory(memoryUsageCsvDirectory);

        configuration.nioEventLoopThreadCount(nioEventLoopThreadCount);
        configuration.actionHandlerThreadCount(actionHandlerThreadCount);
        configuration.clientNioEventLoopThreadCount(clientNioEventLoopThreadCount);
        configuration.webSocketClientEventLoopThreadCount(webSocketClientEventLoopThreadCount);
        configuration.maxFutureTimeoutInMillis(maxFutureTimeoutInMillis);
        configuration.matchersFailFast(matchersFailFast);
        configuration.matchExactCase(matchExactCase);

        configuration.maxSocketTimeoutInMillis(maxSocketTimeoutInMillis);
        configuration.socketConnectionTimeoutInMillis(socketConnectionTimeoutInMillis);
        if (connectionDelay != null) {
            configuration.connectionDelay(connectionDelay.buildObject());
        }
        configuration.alwaysCloseSocketConnections(alwaysCloseSocketConnections);
        configuration.localBoundIP(localBoundIP);

        configuration.maxInitialLineLength(maxInitialLineLength);
        configuration.maxHeaderSize(maxHeaderSize);
        configuration.maxChunkSize(maxChunkSize);
        configuration.useSemicolonAsQueryParameterSeparator(useSemicolonAsQueryParameterSeparator);
        configuration.startupWarmup(startupWarmup);
        configuration.assumeAllRequestsAreHttp(assumeAllRequestsAreHttp);

        configuration.forwardBinaryRequestsWithoutWaitingForResponse(forwardBinaryRequestsWithoutWaitingForResponse);

        configuration.enableCORSForAPI(enableCORSForAPI);
        configuration.enableCORSForAllResponses(enableCORSForAllResponses);
        configuration.corsAllowOrigin(corsAllowOrigin);
        configuration.corsAllowMethods(corsAllowMethods);
        configuration.corsAllowHeaders(corsAllowHeaders);
        configuration.corsAllowCredentials(corsAllowCredentials);
        configuration.corsMaxAgeInSeconds(corsMaxAgeInSeconds);

        configuration.defaultResponseHeaders(defaultResponseHeaders);

        configuration.javascriptDisallowedClasses(javascriptDisallowedClasses);
        configuration.javascriptAllowedClasses(javascriptAllowedClasses);
        configuration.javascriptDisallowedText(javascriptDisallowedText);
        configuration.javascriptTemplateExecutionTimeout(javascriptTemplateExecutionTimeout);
        configuration.velocityDisallowClassLoading(velocityDisallowClassLoading);
        configuration.velocityDisallowedText(velocityDisallowedText);
        configuration.mustacheDisallowedText(mustacheDisallowedText);
        configuration.templateFakerSeed(templateFakerSeed);

        configuration.initializationClass(initializationClass);
        configuration.initializationJsonPath(initializationJsonPath);
        configuration.initializationOpenAPIPath(initializationOpenAPIPath);
        configuration.openAPIContextPathPrefix(openAPIContextPathPrefix);
        configuration.openAPIResponseValidation(openAPIResponseValidation);
        configuration.validateProxyOpenAPISpec(validateProxyOpenAPISpec);
        configuration.validateProxyEnforce(validateProxyEnforce);
        configuration.generateRealisticExampleValues(generateRealisticExampleValues);
        configuration.watchInitializationJson(watchInitializationJson);
        configuration.watchInitializationJsonPollPeriodMillis(watchInitializationJsonPollPeriodMillis);
        configuration.failOnInitializationError(failOnInitializationError);

        configuration.persistExpectations(persistExpectations);
        configuration.persistedExpectationsPath(persistedExpectationsPath);

        configuration.auditLogFile(auditLogFile);

        configuration.persistRecordedExpectations(persistRecordedExpectations);
        configuration.persistedRecordedExpectationsPath(persistedRecordedExpectationsPath);

        configuration.persistRecordedRequestsToDisk(persistRecordedRequestsToDisk);
        configuration.persistedRecordedRequestsPath(persistedRecordedRequestsPath);

        configuration.maximumNumberOfRequestToReturnInVerificationFailure(maximumNumberOfRequestToReturnInVerificationFailure);
        configuration.attachMismatchDiagnosticToResponse(attachMismatchDiagnosticToResponse);
        configuration.closestMatchHintEnabled(closestMatchHintEnabled);

        configuration.attemptToProxyIfNoMatchingExpectation(attemptToProxyIfNoMatchingExpectation);
        if (forwardHttpProxy != null) {
            configuration.forwardHttpProxy(parseInetSocketAddress(forwardHttpProxy));
        }
        if (forwardHttpsProxy != null) {
            configuration.forwardHttpsProxy(parseInetSocketAddress(forwardHttpsProxy));
        }
        if (forwardSocksProxy != null) {
            configuration.forwardSocksProxy(parseInetSocketAddress(forwardSocksProxy));
        }
        configuration.forwardProxyAuthenticationUsername(forwardProxyAuthenticationUsername);
        configuration.forwardProxyAuthenticationPassword(maskFreeOrUnset(forwardProxyAuthenticationPassword));
        configuration.proxyAuthenticationRealm(proxyAuthenticationRealm);
        configuration.proxyAuthenticationUsername(proxyAuthenticationUsername);
        configuration.proxyAuthenticationPassword(maskFreeOrUnset(proxyAuthenticationPassword));
        configuration.dataPlaneAuthenticationRequired(dataPlaneAuthenticationRequired);
        configuration.dataPlaneBasicAuthenticationUsername(dataPlaneBasicAuthenticationUsername);
        configuration.dataPlaneBasicAuthenticationPassword(maskFreeOrUnset(dataPlaneBasicAuthenticationPassword));
        configuration.dataPlaneBasicAuthenticationRealm(dataPlaneBasicAuthenticationRealm);
        configuration.dataPlaneBearerAuthenticationToken(maskFreeOrUnset(dataPlaneBearerAuthenticationToken));
        configuration.dataPlaneApiKeyAuthenticationHeader(maskFreeOrUnset(dataPlaneApiKeyAuthenticationHeader));
        configuration.dataPlaneApiKeyAuthenticationValue(maskFreeOrUnset(dataPlaneApiKeyAuthenticationValue));
        configuration.noProxyHosts(noProxyHosts);
        configuration.proxyRemoteHost(proxyRemoteHost);
        configuration.proxyRemotePort(proxyRemotePort);
        configuration.proxyPassMappings(proxyPassMappings);

        configuration.livenessHttpGetPath(livenessHttpGetPath);

        if (matchNamespaceHeader != null) {
            configuration.matchNamespaceHeader(matchNamespaceHeader);
        }

        configuration.controlPlaneTLSMutualAuthenticationRequired(controlPlaneTLSMutualAuthenticationRequired);
        configuration.controlPlaneTLSMutualAuthenticationCAChain(controlPlaneTLSMutualAuthenticationCAChain);
        configuration.controlPlanePrivateKeyPath(maskFreeOrUnset(controlPlanePrivateKeyPath));
        configuration.controlPlaneX509CertificatePath(controlPlaneX509CertificatePath);
        configuration.controlPlaneJWTAuthenticationRequired(controlPlaneJWTAuthenticationRequired);
        configuration.controlPlaneJWTAuthenticationJWKSource(controlPlaneJWTAuthenticationJWKSource);
        configuration.controlPlaneJWTAuthenticationExpectedAudience(controlPlaneJWTAuthenticationExpectedAudience);
        configuration.controlPlaneJWTAuthenticationMatchingClaims(controlPlaneJWTAuthenticationMatchingClaims);
        configuration.controlPlaneJWTAuthenticationRequiredClaims(controlPlaneJWTAuthenticationRequiredClaims);

        configuration.proactivelyInitialiseTLS(proactivelyInitialiseTLS);
        configuration.tlsProtocols(tlsProtocols);
        configuration.dynamicallyCreateCertificateAuthorityCertificate(dynamicallyCreateCertificateAuthorityCertificate);
        configuration.directoryToSaveDynamicSSLCertificate(directoryToSaveDynamicSSLCertificate);
        configuration.proxySetup(proxySetup);
        configuration.proxySetupLogging(proxySetupLogging);
        configuration.preventCertificateDynamicUpdate(preventCertificateDynamicUpdate);
        configuration.sslCertificateDomainName(sslCertificateDomainName);
        if (sslSubjectAlternativeNameDomains != null) {
            configuration.sslSubjectAlternativeNameDomains(sslSubjectAlternativeNameDomains);
        }
        if (sslSubjectAlternativeNameIps != null) {
            configuration.sslSubjectAlternativeNameIps(sslSubjectAlternativeNameIps);
        }
        configuration.maxSubjectAlternativeNames(maxSubjectAlternativeNames);
        configuration.sslCertificateLeafValidityInDays(sslCertificateLeafValidityInDays);
        configuration.certificateAuthorityPrivateKey(maskFreeOrUnset(certificateAuthorityPrivateKey));
        configuration.certificateAuthorityCertificate(certificateAuthorityCertificate);
        configuration.privateKeyPath(maskFreeOrUnset(privateKeyPath));
        configuration.x509CertificatePath(x509CertificatePath);
        configuration.tlsMutualAuthenticationRequired(tlsMutualAuthenticationRequired);
        configuration.tlsMutualAuthenticationCertificateChain(tlsMutualAuthenticationCertificateChain);

        if (forwardProxyTLSX509CertificatesTrustManagerType != null) {
            configuration.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.valueOf(forwardProxyTLSX509CertificatesTrustManagerType));
        }
        configuration.forwardProxyTLSCustomTrustX509Certificates(forwardProxyTLSCustomTrustX509Certificates);
        configuration.forwardProxyPrivateKey(maskFreeOrUnset(forwardProxyPrivateKey));
        configuration.forwardProxyCertificateChain(forwardProxyCertificateChain);
        configuration.forwardProxyClientCertificatesByHost(forwardProxyClientCertificatesByHost);

        configuration.slowRequestThresholdMillis(slowRequestThresholdMillis);
        configuration.metricsRequestDurationRouteLabels(metricsRequestDurationRouteLabels);
        configuration.rateLimitMaxNamedQuotas(rateLimitMaxNamedQuotas);
        configuration.connectionLifecycleChaosEnabled(connectionLifecycleChaosEnabled);
        configuration.preemptionSimulationMaxDrainMillis(preemptionSimulationMaxDrainMillis);
        configuration.stopDrainMillis(stopDrainMillis);
        configuration.connectionLifecycleAutoHaltCountsRst(connectionLifecycleAutoHaltCountsRst);
        configuration.sloTrackingEnabled(sloTrackingEnabled);
        configuration.sloWindowRetentionMillis(sloWindowRetentionMillis);
        configuration.sloWindowMaxSamples(sloWindowMaxSamples);
        configuration.loadGenerationEnabled(loadGenerationEnabled);
        configuration.loadGenerationSuppressEventLog(loadGenerationSuppressEventLog);
        configuration.loadGenerationMaxVirtualUsers(loadGenerationMaxVirtualUsers);
        configuration.loadGenerationMaxInFlightRequests(loadGenerationMaxInFlightRequests);
        configuration.loadGenerationMaxRequestsPerSecond(loadGenerationMaxRequestsPerSecond);
        configuration.loadGenerationMaxDurationMillis(loadGenerationMaxDurationMillis);
        configuration.loadGenerationMaxSteps(loadGenerationMaxSteps);
        configuration.loadGenerationMaxRate(loadGenerationMaxRate);
        configuration.loadGenerationMaxStages(loadGenerationMaxStages);
        configuration.loadGenerationMaxConcurrentScenarios(loadGenerationMaxConcurrentScenarios);
        configuration.loadScenarioInitializationJsonPath(loadScenarioInitializationJsonPath);
        configuration.loadGenerationMetricLabels(loadGenerationMetricLabels);
        configuration.llmMetricsEnabled(llmMetricsEnabled);
        configuration.perExpectationMetricsEnabled(perExpectationMetricsEnabled);
        configuration.deduplicateRecordedExpectations(deduplicateRecordedExpectations);
        configuration.templatizeRecordedValues(templatizeRecordedValues);
        configuration.redactSecretsInRecordedExpectations(redactSecretsInRecordedExpectations);
        configuration.redactSecretsInLog(redactSecretsInLog);
        configuration.llmCostBudgetUsd(llmCostBudgetUsd);
        configuration.otelPropagateTraceContext(otelPropagateTraceContext);
        configuration.otelGenerateTraceId(otelGenerateTraceId);
        configuration.wasmEnabled(wasmEnabled);
        configuration.wasmMaxMemoryPages(wasmMaxMemoryPages);
        configuration.wasmExecutionTimeoutMillis(wasmExecutionTimeoutMillis);
        configuration.grpcDescriptorDirectory(grpcDescriptorDirectory);
        configuration.grpcProtoDirectory(grpcProtoDirectory);
        configuration.grpcEnabled(grpcEnabled);
        configuration.grpcProtocPath(grpcProtocPath);
        configuration.grpcBidiStreamingEnabled(grpcBidiStreamingEnabled);
        configuration.dnsEnabled(dnsEnabled);
        configuration.dnsPort(dnsPort);
        configuration.http3Port(http3Port);
        configuration.http3MaxIdleTimeout(http3MaxIdleTimeout);
        configuration.http3InitialMaxData(http3InitialMaxData);
        configuration.http3InitialMaxStreamDataBidirectional(http3InitialMaxStreamDataBidirectional);
        configuration.http3InitialMaxStreamsBidirectional(http3InitialMaxStreamsBidirectional);
        configuration.http3QpackMaxTableCapacity(http3QpackMaxTableCapacity);
        configuration.http3ConnectUdpEnabled(http3ConnectUdpEnabled);
        configuration.http3ConnectUdpAllowedTargets(http3ConnectUdpAllowedTargets);
        configuration.http3AltSvcMaxAge(http3AltSvcMaxAge);
        configuration.http3AdvertiseAltSvc(http3AdvertiseAltSvc);
        configuration.useNativeTransport(useNativeTransport);
        configuration.forwardConnectionPoolEnabled(forwardConnectionPoolEnabled);
        configuration.forwardConnectionPoolMaxIdlePerKey(forwardConnectionPoolMaxIdlePerKey);
        configuration.forwardConnectionPoolIdleTimeoutMillis(forwardConnectionPoolIdleTimeoutMillis);
        configuration.forwardConnectionPoolKeepAlive(forwardConnectionPoolKeepAlive);
        configuration.forwardConnectionPoolMaxTotalPerKey(forwardConnectionPoolMaxTotalPerKey);
        configuration.forwardSocketKeepAlive(forwardSocketKeepAlive);
        configuration.forwardSocketKeepAliveIdleSeconds(forwardSocketKeepAliveIdleSeconds);
        configuration.forwardSocketKeepAliveIntervalSeconds(forwardSocketKeepAliveIntervalSeconds);
        configuration.forwardSocketKeepAliveCount(forwardSocketKeepAliveCount);
        configuration.forwardProxyRetryCount(forwardProxyRetryCount);
        configuration.forwardProxyRetryBackoffMillis(forwardProxyRetryBackoffMillis);
        configuration.forwardProxyHttp2Enabled(forwardProxyHttp2Enabled);
        configuration.forwardProxyHttp2Upgrade(forwardProxyHttp2Upgrade);
        configuration.forwardProxyCircuitBreakerEnabled(forwardProxyCircuitBreakerEnabled);
        configuration.forwardProxyCircuitBreakerFailureThreshold(forwardProxyCircuitBreakerFailureThreshold);
        configuration.forwardProxyCircuitBreakerWindowMillis(forwardProxyCircuitBreakerWindowMillis);
        configuration.enforceResponseValidationForMocks(enforceResponseValidationForMocks);
        configuration.maxRequestBodySize(maxRequestBodySize);
        configuration.maxGrpcMessageSize(maxGrpcMessageSize);
        configuration.maxResponseBodySize(maxResponseBodySize);
        configuration.maxLlmConversationBodySize(maxLlmConversationBodySize);
        configuration.driftDetectionEnabled(driftDetectionEnabled);
        configuration.driftSampleRate(driftSampleRate);
        configuration.driftSemanticAnalysisEnabled(driftSemanticAnalysisEnabled);
        configuration.driftResponseTimeThresholdMs(driftResponseTimeThresholdMs);
        configuration.driftAlertWebhookEnabled(driftAlertWebhookEnabled);
        configuration.driftAlertWebhookUrl(driftAlertWebhookUrl);
        configuration.driftAlertSeverityThreshold(driftAlertSeverityThreshold);
        configuration.driftAlertCooldownMillis(driftAlertCooldownMillis);
        configuration.controlPlaneAuditEnabled(controlPlaneAuditEnabled);
        configuration.controlPlaneAuditMaxEntries(controlPlaneAuditMaxEntries);
        configuration.controlPlaneAuditReads(controlPlaneAuditReads);
        configuration.http2Enabled(http2Enabled);
        configuration.streamingResponsesEnabled(streamingResponsesEnabled);
        configuration.maxStreamingCaptureBytes(maxStreamingCaptureBytes);
        configuration.streamIdleTimeoutSeconds(streamIdleTimeoutSeconds);
        configuration.validateRequestsAgainstOpenApiSpec(validateRequestsAgainstOpenApiSpec);
        configuration.detailedVerificationFailures(detailedVerificationFailures);
        configuration.failVerificationOnEvictedLog(failVerificationOnEvictedLog);
        configuration.globalResponseDelayMillis(globalResponseDelayMillis);
        configuration.forwardAdjustHostHeader(forwardAdjustHostHeader);
        configuration.forwardDefaultHostHeader(forwardDefaultHostHeader);
        configuration.forwardProxyBlockPrivateNetworks(forwardProxyBlockPrivateNetworks);
        configuration.forwardProxyTLSHostnameVerificationEnabled(forwardProxyTLSHostnameVerificationEnabled);
        configuration.tlsAllowInsecureProtocols(tlsAllowInsecureProtocols);
        configuration.stateBackend(stateBackend);
        configuration.blobStoreType(blobStoreType);
        configuration.blobStoreBucket(blobStoreBucket);
        configuration.blobStoreRegion(blobStoreRegion);
        configuration.blobStoreEndpoint(blobStoreEndpoint);
        configuration.blobStoreKeyPrefix(blobStoreKeyPrefix);
        configuration.blobStoreAccessKeyId(maskFreeOrUnset(blobStoreAccessKeyId));
        configuration.blobStoreSecretAccessKey(maskFreeOrUnset(blobStoreSecretAccessKey));
        configuration.blobStoreContainer(blobStoreContainer);
        configuration.blobStoreConnectionString(maskFreeOrUnset(blobStoreConnectionString));
        configuration.blobStoreProjectId(blobStoreProjectId);
        configuration.blobStoreRestoreTimeoutSeconds(blobStoreRestoreTimeoutSeconds);
        if (clusterEnabled != null) {
            configuration.clusterEnabled(clusterEnabled);
        }
        configuration.clusterName(clusterName);
        configuration.clusterTransportConfig(clusterTransportConfig);
        if (clusterSharedTimesEnabled != null) {
            configuration.clusterSharedTimesEnabled(clusterSharedTimesEnabled);
        }
        if (clusterVerifyFanIn != null) {
            configuration.clusterVerifyFanIn(clusterVerifyFanIn);
        }
        configuration.clusterVerifyFanInPeers(clusterVerifyFanInPeers);
        configuration.clusterFanInPeerAuthToken(maskFreeOrUnset(clusterFanInPeerAuthToken));
        configuration.controlPlaneOidcAuthenticationRequired(controlPlaneOidcAuthenticationRequired);
        configuration.controlPlaneOidcIssuer(controlPlaneOidcIssuer);
        configuration.controlPlaneOidcJwksUri(controlPlaneOidcJwksUri);
        configuration.controlPlaneOidcAudience(controlPlaneOidcAudience);
        if (controlPlaneOidcRequiredScopes != null) {
            configuration.controlPlaneOidcRequiredScopes(controlPlaneOidcRequiredScopes);
        }
        configuration.controlPlaneOidcScopeClaim(controlPlaneOidcScopeClaim);
        configuration.controlPlaneAuthorizationEnabled(controlPlaneAuthorizationEnabled);
        configuration.controlPlaneScopeMapping(controlPlaneScopeMapping);
        configuration.transparentProxyEnabled(transparentProxyEnabled);
        configuration.transparentProxyTproxy(transparentProxyTproxy);
        configuration.transparentProxyEbpf(transparentProxyEbpf);
        configuration.transparentProxyEbpfMapPath(transparentProxyEbpfMapPath);
        configuration.asyncKafkaBootstrapServers(asyncKafkaBootstrapServers);
        configuration.asyncMqttBrokerUrl(asyncMqttBrokerUrl);
        configuration.asyncAmqpUri(asyncAmqpUri);
        configuration.asyncRecordedMessageMaxEntries(asyncRecordedMessageMaxEntries);

        configuration.llmProvider(llmProvider);
        configuration.llmModel(llmModel);
        configuration.llmBaseUrl(llmBaseUrl);
        // value-embedded credentials: buildObject() has no previously-held configuration to restore a
        // redacted field from, so a value carrying the mask is left UNSET rather than stored in a
        // reduced form that would then shadow the static store — exactly as a whole-value credential
        // is left unset below
        configuration.llmBackendsConfig(ConfigurationProperties.containsRedactionMask(llmBackendsConfig) ? null : llmBackendsConfig);
        configuration.llmRequestTimeoutMillis(llmRequestTimeoutMillis);
        configuration.llmSemanticMatchingEnabled(llmSemanticMatchingEnabled);
        configuration.llmInferUsageEnabled(llmInferUsageEnabled);
        configuration.llmVcrStrict(llmVcrStrict);
        configuration.llmOptimisationMaxCalls(llmOptimisationMaxCalls);
        configuration.fixtureBodyRedactFields(fixtureBodyRedactFields);
        configuration.otelEndpoint(otelEndpoint);
        configuration.otelMetricsEnabled(otelMetricsEnabled);
        configuration.otelTracesEnabled(otelTracesEnabled);
        configuration.otelMetricsExportIntervalSeconds(otelMetricsExportIntervalSeconds);
        configuration.otelMetricsTemporality(otelMetricsTemporality);
        configuration.prometheusRemoteWriteEnabled(prometheusRemoteWriteEnabled);
        configuration.prometheusRemoteWriteUrl(prometheusRemoteWriteUrl);
        configuration.prometheusRemoteWriteIntervalSeconds(prometheusRemoteWriteIntervalSeconds);
        configuration.prometheusRemoteWriteBasicAuthUsername(prometheusRemoteWriteBasicAuthUsername);
        configuration.prometheusRemoteWriteHeaders(ConfigurationProperties.containsRedactionMask(prometheusRemoteWriteHeaders) ? null : prometheusRemoteWriteHeaders);
        configuration.prometheusRemoteWriteProtocolVersion(prometheusRemoteWriteProtocolVersion);
        configuration.regexMatchingTimeoutMillis(regexMatchingTimeoutMillis);
        configuration.xpathMatchingTimeoutMillis(xpathMatchingTimeoutMillis);
        configuration.customJsonUnitMatchersClass(customJsonUnitMatchersClass);

        // write-only credentials: a value derived from a masked GET carries the literal mask — whether
        // echoed back untouched or with text typed around it — and must never become the credential.
        // buildObject() has no held configuration to restore it from (the value-embedded credentials
        // above are in the same position), so leave the field UNSET: the Configuration then falls back
        // to the static store, rather than being poisoned with a value carrying "***REDACTED***" or
        // pinned to an empty value that would shadow a property-file or environment credential.
        if (!isMaskedCredential(llmApiKey)) {
            configuration.llmApiKey(llmApiKey);
        }
        if (!isMaskedCredential(prometheusRemoteWriteBearerToken)) {
            configuration.prometheusRemoteWriteBearerToken(prometheusRemoteWriteBearerToken);
        }
        if (!isMaskedCredential(prometheusRemoteWriteBasicAuthPassword)) {
            configuration.prometheusRemoteWriteBasicAuthPassword(prometheusRemoteWriteBasicAuthPassword);
        }

        return configuration;
    }

    // ---------------------------------------------------------------------------------------------
    // THE WRITE-PATH MASK GUARD — every credential-SHAPED property, whatever it does on read
    //
    // What a property does on READ has three shapes, and it turns out to be irrelevant to the write
    // path — which is exactly the assumption that produced this bug twice:
    //
    //   masked   - the getter returns the mask (llmApiKey and the two prometheus credentials, above)
    //   omitted  - the getter is @JsonIgnore-d, so GET /mockserver/configuration never names it
    //              (proxyAuthenticationPassword, dataPlaneBearerAuthenticationToken, the blob-store
    //              credentials, clusterFanInPeerAuthToken, the private keys, ...)
    //   readable - deliberately returned in clear because the VALUE is not a secret even though the
    //              NAME is credential-shaped (privateKeyPath and controlPlanePrivateKeyPath are file
    //              paths, dataPlaneApiKeyAuthenticationHeader is a header NAME, dashboardAnalyticsKey
    //              is an ingest-only key the browser dashboard must receive)
    //
    // Omission and readability were both read as "the mask can never arrive here", so those write
    // paths carried no check. Neither holds. ConfigurationProperties.redactSensitiveValue masks every
    // credential-SHAPED NAME, so GET /mockserver/config, --print-config and the dashboard's Server
    // Info tab render ALL of them as "***REDACTED***" regardless of what /mockserver/configuration
    // does. An operator who reads "mockserver.dataPlaneBearerAuthenticationToken=***REDACTED***" from
    // one of those surfaces, edits the settings around it and PUTs the result supplies the literal
    // mask. Written verbatim it destroys the working credential, breaks every call authenticated with
    // it, logs nothing, and the PUT answers 200 OK.
    //
    // So the write paths refuse a value carrying the mask for the whole credential-shaped surface:
    //   applyTo     - routed through ConfigurationProperties.restoreRedactedValue(...), which refuses
    //                 the value and LOGS why (EmbeddedCredentialRedaction.dropUnmergeableValue), so the
    //                 credential in force is left untouched and the operator is told.
    //   buildObject - maskFreeOrUnset(...) leaves the field UNSET, so the static store / property file
    //                 / environment variable still resolves it; there is nothing held to restore from.
    //                 It does not log — see its javadoc.
    //
    // Containment, not equality: "***REDACTED***-my-new-key" reads as an operator typing a new value
    // over the mask, and welding the two together persists neither credential.
    //
    // Refusing BEFORE the setter also matters for the properties whose setter validates (the two
    // private-key paths call fileExists): they used to reject the mask by THROWING from deep inside
    // applyTo, abandoning it half-applied over a live Configuration and turning the PUT into a 400
    // after some neighbouring edits had already been committed.
    //
    // ConfigurationDTOCredentialMaskingTest section 4 enumerates this surface reflectively and holds
    // the invariant for all of it.
    // ---------------------------------------------------------------------------------------------

    /**
     * The value to store on a FRESH configuration for a credential-shaped property: {@code null}
     * whenever the incoming value carries the redaction mask anywhere inside it, leaving the field unset.
     *
     * <p>{@code buildObject()} has no previously-held configuration to restore a redacted value from, so
     * unset is the only safe outcome: storing the mask poisons the credential, and storing {@code ""}
     * pins an empty value on the instance whose getter then prefers it over
     * {@link ConfigurationProperties} — silently shadowing a property-file or environment value.
     *
     * <p>Unlike the {@code applyTo} path this does <strong>not</strong> log, matching the rule the
     * value-embedded credentials already apply in {@code buildObject()}. There is no held value being
     * discarded here and no live configuration being left inconsistent — the caller is constructing a
     * fresh object — so there is no "your PUT wrote nothing" to warn about.
     */
    private static String maskFreeOrUnset(String value) {
        return ConfigurationProperties.containsRedactionMask(value) ? null : value;
    }

    /**
     * True when an incoming credential value CARRIES the redaction mask emitted by a previous
     * {@code GET /mockserver/configuration}, so it is not wholly a real secret.
     *
     * <p>This is the round-trip guard: a client that does GET-then-PUT of the whole configuration blob
     * (the dashboard, an operator with curl, a config-as-code tool) would otherwise write the literal
     * {@code ***REDACTED***} over a working credential and silently break outbound auth.
     *
     * <p>CONTAINS, not EQUALS. An equals-only check handled the untouched round trip and nothing else:
     * an operator who typed around the mask — {@code "sk-***REDACTED***"}, {@code "Bearer ***REDACTED***"} —
     * produced a value that is not the mask, so it was written through VERBATIM, destroying the held
     * credential and making the literal mask the outbound one. That is exactly the failure closed for
     * value-embedded credentials, so it uses the same notion of "masked" they do,
     * {@link ConfigurationProperties#containsRedactionMask(String)}.
     */
    private static boolean isMaskedCredential(String value) {
        return ConfigurationProperties.containsRedactionMask(value);
    }

    /**
     * The value emitted over JSON for a write-only credential: the shared redaction mask whenever a
     * value is present, otherwise {@code null} (so an unset credential stays absent from the JSON under
     * {@code NON_NULL} inclusion rather than advertising a secret that does not exist).
     */
    private static String maskCredential(String value) {
        return value == null || value.isEmpty() ? null : ConfigurationProperties.REDACTED_VALUE;
    }

    public void applyTo(Configuration target) {
        validateFields();
        if (logLevel != null) {
            target.logLevel(Level.valueOf(logLevel));
        }
        if (disableSystemOut != null) {
            target.disableSystemOut(disableSystemOut);
        }
        if (disableLogging != null) {
            target.disableLogging(disableLogging);
        }
        if (detailedMatchFailures != null) {
            target.detailedMatchFailures(detailedMatchFailures);
        }
        if (launchUIForLogLevelDebug != null) {
            target.launchUIForLogLevelDebug(launchUIForLogLevelDebug);
        }
        if (metricsEnabled != null) {
            target.metricsEnabled(metricsEnabled);
        }
        if (dashboardAnalyticsEnabled != null) {
            target.dashboardAnalyticsEnabled(dashboardAnalyticsEnabled);
        }
        if (dashboardAnalyticsEndpoint != null) {
            target.dashboardAnalyticsEndpoint(dashboardAnalyticsEndpoint);
        }
        if (dashboardAnalyticsKey != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("dashboardAnalyticsKey", dashboardAnalyticsKey, target.dashboardAnalyticsKey());
            if (restored != null) {
                target.dashboardAnalyticsKey(restored);
            }
        }
        if (dashboardAnalyticsDistribution != null) {
            target.dashboardAnalyticsDistribution(dashboardAnalyticsDistribution);
        }
        if (chaosAutoHaltEnabled != null) {
            target.chaosAutoHaltEnabled(chaosAutoHaltEnabled);
        }
        if (chaosAutoHaltErrorThreshold != null) {
            target.chaosAutoHaltErrorThreshold(chaosAutoHaltErrorThreshold);
        }
        if (chaosAutoHaltWindowMillis != null) {
            target.chaosAutoHaltWindowMillis(chaosAutoHaltWindowMillis);
        }
        if (mcpEnabled != null) {
            target.mcpEnabled(mcpEnabled);
        }
        if (breakpointTimeoutMillis != null) {
            target.breakpointTimeoutMillis(breakpointTimeoutMillis);
        }
        if (breakpointMaxHeld != null) {
            target.breakpointMaxHeld(breakpointMaxHeld);
        }
        if (logLevelOverrides != null) {
            target.logLevelOverrides(logLevelOverrides);
        }
        if (compactLogFormat != null) {
            target.compactLogFormat(compactLogFormat);
        }
        if (devMode != null) {
            target.devMode(devMode);
        }
        if (maxExpectations != null) {
            target.maxExpectations(maxExpectations);
        }
        if (maxLogEntries != null) {
            target.maxLogEntries(maxLogEntries);
        }
        if (maxEventLogSizeInBytes != null) {
            target.maxEventLogSizeInBytes(maxEventLogSizeInBytes);
        }
        if (maxLoggedBodyBytes != null) {
            target.maxLoggedBodyBytes(maxLoggedBodyBytes);
        }
        if (ringBufferSize != null) {
            target.ringBufferSize(ringBufferSize);
        }
        if (maxWebSocketExpectations != null) {
            target.maxWebSocketExpectations(maxWebSocketExpectations);
        }
        if (webSocketProxyMaxRecordedFrames != null) {
            target.webSocketProxyMaxRecordedFrames(webSocketProxyMaxRecordedFrames);
        }
        if (webSocketProxyIdleTimeoutSeconds != null) {
            target.webSocketProxyIdleTimeoutSeconds(webSocketProxyIdleTimeoutSeconds);
        }
        if (outputMemoryUsageCsv != null) {
            target.outputMemoryUsageCsv(outputMemoryUsageCsv);
        }
        if (memoryUsageCsvDirectory != null) {
            target.memoryUsageCsvDirectory(memoryUsageCsvDirectory);
        }
        if (nioEventLoopThreadCount != null) {
            target.nioEventLoopThreadCount(nioEventLoopThreadCount);
        }
        if (actionHandlerThreadCount != null) {
            target.actionHandlerThreadCount(actionHandlerThreadCount);
        }
        if (clientNioEventLoopThreadCount != null) {
            target.clientNioEventLoopThreadCount(clientNioEventLoopThreadCount);
        }
        if (webSocketClientEventLoopThreadCount != null) {
            target.webSocketClientEventLoopThreadCount(webSocketClientEventLoopThreadCount);
        }
        if (maxFutureTimeoutInMillis != null) {
            target.maxFutureTimeoutInMillis(maxFutureTimeoutInMillis);
        }
        if (matchersFailFast != null) {
            target.matchersFailFast(matchersFailFast);
        }
        if (matchExactCase != null) {
            target.matchExactCase(matchExactCase);
        }
        if (maxSocketTimeoutInMillis != null) {
            target.maxSocketTimeoutInMillis(maxSocketTimeoutInMillis);
        }
        if (socketConnectionTimeoutInMillis != null) {
            target.socketConnectionTimeoutInMillis(socketConnectionTimeoutInMillis);
        }
        if (connectionDelay != null) {
            target.connectionDelay(connectionDelay.buildObject());
        }
        if (alwaysCloseSocketConnections != null) {
            target.alwaysCloseSocketConnections(alwaysCloseSocketConnections);
        }
        if (localBoundIP != null) {
            target.localBoundIP(localBoundIP);
        }
        if (maxInitialLineLength != null) {
            target.maxInitialLineLength(maxInitialLineLength);
        }
        if (maxHeaderSize != null) {
            target.maxHeaderSize(maxHeaderSize);
        }
        if (maxChunkSize != null) {
            target.maxChunkSize(maxChunkSize);
        }
        if (useSemicolonAsQueryParameterSeparator != null) {
            target.useSemicolonAsQueryParameterSeparator(useSemicolonAsQueryParameterSeparator);
        }
        if (startupWarmup != null) {
            target.startupWarmup(startupWarmup);
        }
        if (assumeAllRequestsAreHttp != null) {
            target.assumeAllRequestsAreHttp(assumeAllRequestsAreHttp);
        }
        if (forwardBinaryRequestsWithoutWaitingForResponse != null) {
            target.forwardBinaryRequestsWithoutWaitingForResponse(forwardBinaryRequestsWithoutWaitingForResponse);
        }
        if (enableCORSForAPI != null) {
            target.enableCORSForAPI(enableCORSForAPI);
        }
        if (enableCORSForAllResponses != null) {
            target.enableCORSForAllResponses(enableCORSForAllResponses);
        }
        if (corsAllowOrigin != null) {
            target.corsAllowOrigin(corsAllowOrigin);
        }
        if (corsAllowMethods != null) {
            target.corsAllowMethods(corsAllowMethods);
        }
        if (corsAllowHeaders != null) {
            target.corsAllowHeaders(corsAllowHeaders);
        }
        if (corsAllowCredentials != null) {
            target.corsAllowCredentials(corsAllowCredentials);
        }
        if (corsMaxAgeInSeconds != null) {
            target.corsMaxAgeInSeconds(corsMaxAgeInSeconds);
        }
        if (defaultResponseHeaders != null) {
            target.defaultResponseHeaders(defaultResponseHeaders);
        }
        if (javascriptDisallowedClasses != null) {
            target.javascriptDisallowedClasses(javascriptDisallowedClasses);
        }
        if (javascriptAllowedClasses != null) {
            target.javascriptAllowedClasses(javascriptAllowedClasses);
        }
        if (javascriptDisallowedText != null) {
            target.javascriptDisallowedText(javascriptDisallowedText);
        }
        if (javascriptTemplateExecutionTimeout != null) {
            target.javascriptTemplateExecutionTimeout(javascriptTemplateExecutionTimeout);
        }
        if (velocityDisallowClassLoading != null) {
            target.velocityDisallowClassLoading(velocityDisallowClassLoading);
        }
        if (velocityDisallowedText != null) {
            target.velocityDisallowedText(velocityDisallowedText);
        }
        if (mustacheDisallowedText != null) {
            target.mustacheDisallowedText(mustacheDisallowedText);
        }
        if (templateFakerSeed != null) {
            target.templateFakerSeed(templateFakerSeed);
        }
        if (initializationClass != null) {
            target.initializationClass(initializationClass);
        }
        if (initializationJsonPath != null) {
            target.initializationJsonPath(initializationJsonPath);
        }
        if (initializationOpenAPIPath != null) {
            target.initializationOpenAPIPath(initializationOpenAPIPath);
        }
        if (openAPIContextPathPrefix != null) {
            target.openAPIContextPathPrefix(openAPIContextPathPrefix);
        }
        if (openAPIResponseValidation != null) {
            target.openAPIResponseValidation(openAPIResponseValidation);
        }
        if (validateProxyOpenAPISpec != null) {
            target.validateProxyOpenAPISpec(validateProxyOpenAPISpec);
        }
        if (validateProxyEnforce != null) {
            target.validateProxyEnforce(validateProxyEnforce);
        }
        if (generateRealisticExampleValues != null) {
            target.generateRealisticExampleValues(generateRealisticExampleValues);
        }
        if (watchInitializationJson != null) {
            target.watchInitializationJson(watchInitializationJson);
        }
        if (watchInitializationJsonPollPeriodMillis != null) {
            target.watchInitializationJsonPollPeriodMillis(watchInitializationJsonPollPeriodMillis);
        }
        if (failOnInitializationError != null) {
            target.failOnInitializationError(failOnInitializationError);
        }
        if (persistExpectations != null) {
            target.persistExpectations(persistExpectations);
        }
        if (persistedExpectationsPath != null) {
            target.persistedExpectationsPath(persistedExpectationsPath);
        }
        if (auditLogFile != null) {
            target.auditLogFile(auditLogFile);
        }
        if (persistRecordedExpectations != null) {
            target.persistRecordedExpectations(persistRecordedExpectations);
        }
        if (persistedRecordedExpectationsPath != null) {
            target.persistedRecordedExpectationsPath(persistedRecordedExpectationsPath);
        }
        if (persistRecordedRequestsToDisk != null) {
            target.persistRecordedRequestsToDisk(persistRecordedRequestsToDisk);
        }
        if (persistedRecordedRequestsPath != null) {
            target.persistedRecordedRequestsPath(persistedRecordedRequestsPath);
        }
        if (maximumNumberOfRequestToReturnInVerificationFailure != null) {
            target.maximumNumberOfRequestToReturnInVerificationFailure(maximumNumberOfRequestToReturnInVerificationFailure);
        }
        if (attachMismatchDiagnosticToResponse != null) {
            target.attachMismatchDiagnosticToResponse(attachMismatchDiagnosticToResponse);
        }
        if (closestMatchHintEnabled != null) {
            target.closestMatchHintEnabled(closestMatchHintEnabled);
        }
        if (attemptToProxyIfNoMatchingExpectation != null) {
            target.attemptToProxyIfNoMatchingExpectation(attemptToProxyIfNoMatchingExpectation);
        }
        if (forwardHttpProxy != null) {
            target.forwardHttpProxy(parseInetSocketAddress(forwardHttpProxy));
        }
        if (forwardHttpsProxy != null) {
            target.forwardHttpsProxy(parseInetSocketAddress(forwardHttpsProxy));
        }
        if (forwardSocksProxy != null) {
            target.forwardSocksProxy(parseInetSocketAddress(forwardSocksProxy));
        }
        if (forwardProxyAuthenticationUsername != null) {
            target.forwardProxyAuthenticationUsername(forwardProxyAuthenticationUsername);
        }
        // credential-shaped property: refuse a value carrying the redaction mask — see THE WRITE-PATH
        // MASK GUARD above maskFreeOrUnset(...). The held value passed here is inert for every one of
        // these (none is in restoreRedactedValue's header-list or JSON-document sets, so it is never
        // read); it is passed anyway so every credential goes through the one shared entry point, and
        // so a property that later gains an embedded shape merges instead of silently refusing.
        if (forwardProxyAuthenticationPassword != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("forwardProxyAuthenticationPassword", forwardProxyAuthenticationPassword, target.forwardProxyAuthenticationPassword());
            if (restored != null) {
                target.forwardProxyAuthenticationPassword(restored);
            }
        }
        if (proxyAuthenticationRealm != null) {
            target.proxyAuthenticationRealm(proxyAuthenticationRealm);
        }
        if (proxyAuthenticationUsername != null) {
            target.proxyAuthenticationUsername(proxyAuthenticationUsername);
        }
        if (proxyAuthenticationPassword != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("proxyAuthenticationPassword", proxyAuthenticationPassword, target.proxyAuthenticationPassword());
            if (restored != null) {
                target.proxyAuthenticationPassword(restored);
            }
        }
        if (dataPlaneAuthenticationRequired != null) {
            target.dataPlaneAuthenticationRequired(dataPlaneAuthenticationRequired);
        }
        if (dataPlaneBasicAuthenticationUsername != null) {
            target.dataPlaneBasicAuthenticationUsername(dataPlaneBasicAuthenticationUsername);
        }
        if (dataPlaneBasicAuthenticationPassword != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("dataPlaneBasicAuthenticationPassword", dataPlaneBasicAuthenticationPassword, target.dataPlaneBasicAuthenticationPassword());
            if (restored != null) {
                target.dataPlaneBasicAuthenticationPassword(restored);
            }
        }
        if (dataPlaneBasicAuthenticationRealm != null) {
            target.dataPlaneBasicAuthenticationRealm(dataPlaneBasicAuthenticationRealm);
        }
        if (dataPlaneBearerAuthenticationToken != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("dataPlaneBearerAuthenticationToken", dataPlaneBearerAuthenticationToken, target.dataPlaneBearerAuthenticationToken());
            if (restored != null) {
                target.dataPlaneBearerAuthenticationToken(restored);
            }
        }
        if (dataPlaneApiKeyAuthenticationHeader != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("dataPlaneApiKeyAuthenticationHeader", dataPlaneApiKeyAuthenticationHeader, target.dataPlaneApiKeyAuthenticationHeader());
            if (restored != null) {
                target.dataPlaneApiKeyAuthenticationHeader(restored);
            }
        }
        if (dataPlaneApiKeyAuthenticationValue != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("dataPlaneApiKeyAuthenticationValue", dataPlaneApiKeyAuthenticationValue, target.dataPlaneApiKeyAuthenticationValue());
            if (restored != null) {
                target.dataPlaneApiKeyAuthenticationValue(restored);
            }
        }
        if (noProxyHosts != null) {
            target.noProxyHosts(noProxyHosts);
        }
        if (proxyRemoteHost != null) {
            target.proxyRemoteHost(proxyRemoteHost);
        }
        if (proxyRemotePort != null) {
            target.proxyRemotePort(proxyRemotePort);
        }
        if (proxyPassMappings != null) {
            target.proxyPassMappings(proxyPassMappings);
        }
        if (livenessHttpGetPath != null) {
            target.livenessHttpGetPath(livenessHttpGetPath);
        }
        if (matchNamespaceHeader != null) {
            target.matchNamespaceHeader(matchNamespaceHeader);
        }
        if (controlPlaneTLSMutualAuthenticationRequired != null) {
            target.controlPlaneTLSMutualAuthenticationRequired(controlPlaneTLSMutualAuthenticationRequired);
        }
        if (controlPlaneTLSMutualAuthenticationCAChain != null) {
            target.controlPlaneTLSMutualAuthenticationCAChain(controlPlaneTLSMutualAuthenticationCAChain);
        }
        if (controlPlanePrivateKeyPath != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("controlPlanePrivateKeyPath", controlPlanePrivateKeyPath, target.controlPlanePrivateKeyPath());
            if (restored != null) {
                target.controlPlanePrivateKeyPath(restored);
            }
        }
        if (controlPlaneX509CertificatePath != null) {
            target.controlPlaneX509CertificatePath(controlPlaneX509CertificatePath);
        }
        if (controlPlaneJWTAuthenticationRequired != null) {
            target.controlPlaneJWTAuthenticationRequired(controlPlaneJWTAuthenticationRequired);
        }
        if (controlPlaneJWTAuthenticationJWKSource != null) {
            target.controlPlaneJWTAuthenticationJWKSource(controlPlaneJWTAuthenticationJWKSource);
        }
        if (controlPlaneJWTAuthenticationExpectedAudience != null) {
            target.controlPlaneJWTAuthenticationExpectedAudience(controlPlaneJWTAuthenticationExpectedAudience);
        }
        if (controlPlaneJWTAuthenticationMatchingClaims != null) {
            target.controlPlaneJWTAuthenticationMatchingClaims(controlPlaneJWTAuthenticationMatchingClaims);
        }
        if (controlPlaneJWTAuthenticationRequiredClaims != null) {
            target.controlPlaneJWTAuthenticationRequiredClaims(controlPlaneJWTAuthenticationRequiredClaims);
        }
        if (proactivelyInitialiseTLS != null) {
            target.proactivelyInitialiseTLS(proactivelyInitialiseTLS);
        }
        if (tlsProtocols != null) {
            target.tlsProtocols(tlsProtocols);
        }
        if (dynamicallyCreateCertificateAuthorityCertificate != null) {
            target.dynamicallyCreateCertificateAuthorityCertificate(dynamicallyCreateCertificateAuthorityCertificate);
        }
        if (directoryToSaveDynamicSSLCertificate != null) {
            target.directoryToSaveDynamicSSLCertificate(directoryToSaveDynamicSSLCertificate);
        }
        if (proxySetup != null) {
            target.proxySetup(proxySetup);
        }
        if (proxySetupLogging != null) {
            target.proxySetupLogging(proxySetupLogging);
        }
        if (preventCertificateDynamicUpdate != null) {
            target.preventCertificateDynamicUpdate(preventCertificateDynamicUpdate);
        }
        if (sslCertificateDomainName != null) {
            target.sslCertificateDomainName(sslCertificateDomainName);
        }
        if (sslSubjectAlternativeNameDomains != null) {
            target.sslSubjectAlternativeNameDomains(sslSubjectAlternativeNameDomains);
        }
        if (sslSubjectAlternativeNameIps != null) {
            target.sslSubjectAlternativeNameIps(sslSubjectAlternativeNameIps);
        }
        if (maxSubjectAlternativeNames != null) {
            target.maxSubjectAlternativeNames(maxSubjectAlternativeNames);
        }
        if (sslCertificateLeafValidityInDays != null) {
            target.sslCertificateLeafValidityInDays(sslCertificateLeafValidityInDays);
        }
        if (certificateAuthorityPrivateKey != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("certificateAuthorityPrivateKey", certificateAuthorityPrivateKey, target.certificateAuthorityPrivateKey());
            if (restored != null) {
                target.certificateAuthorityPrivateKey(restored);
            }
        }
        if (certificateAuthorityCertificate != null) {
            target.certificateAuthorityCertificate(certificateAuthorityCertificate);
        }
        if (privateKeyPath != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("privateKeyPath", privateKeyPath, target.privateKeyPath());
            if (restored != null) {
                target.privateKeyPath(restored);
            }
        }
        if (x509CertificatePath != null) {
            target.x509CertificatePath(x509CertificatePath);
        }
        if (tlsMutualAuthenticationRequired != null) {
            target.tlsMutualAuthenticationRequired(tlsMutualAuthenticationRequired);
        }
        if (tlsMutualAuthenticationCertificateChain != null) {
            target.tlsMutualAuthenticationCertificateChain(tlsMutualAuthenticationCertificateChain);
        }
        if (forwardProxyTLSX509CertificatesTrustManagerType != null) {
            target.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.valueOf(forwardProxyTLSX509CertificatesTrustManagerType));
        }
        if (forwardProxyTLSCustomTrustX509Certificates != null) {
            target.forwardProxyTLSCustomTrustX509Certificates(forwardProxyTLSCustomTrustX509Certificates);
        }
        if (forwardProxyPrivateKey != null) {
            // the setter also rejects a non-existent file, so the mask was already refused here — but by
            // THROWING, which abandons applyTo half-applied. Refuse it first, and consistently.
            String restored = ConfigurationProperties.restoreRedactedValue("forwardProxyPrivateKey", forwardProxyPrivateKey, target.forwardProxyPrivateKey());
            if (restored != null) {
                target.forwardProxyPrivateKey(restored);
            }
        }
        if (forwardProxyCertificateChain != null) {
            target.forwardProxyCertificateChain(forwardProxyCertificateChain);
        }
        if (forwardProxyClientCertificatesByHost != null) {
            target.forwardProxyClientCertificatesByHost(forwardProxyClientCertificatesByHost);
        }
        if (slowRequestThresholdMillis != null) {
            target.slowRequestThresholdMillis(slowRequestThresholdMillis);
        }
        if (metricsRequestDurationRouteLabels != null) {
            target.metricsRequestDurationRouteLabels(metricsRequestDurationRouteLabels);
        }
        if (rateLimitMaxNamedQuotas != null) {
            target.rateLimitMaxNamedQuotas(rateLimitMaxNamedQuotas);
        }
        if (connectionLifecycleChaosEnabled != null) {
            target.connectionLifecycleChaosEnabled(connectionLifecycleChaosEnabled);
        }
        if (preemptionSimulationMaxDrainMillis != null) {
            target.preemptionSimulationMaxDrainMillis(preemptionSimulationMaxDrainMillis);
        }
        if (stopDrainMillis != null) {
            target.stopDrainMillis(stopDrainMillis);
        }
        if (connectionLifecycleAutoHaltCountsRst != null) {
            target.connectionLifecycleAutoHaltCountsRst(connectionLifecycleAutoHaltCountsRst);
        }
        if (sloTrackingEnabled != null) {
            target.sloTrackingEnabled(sloTrackingEnabled);
        }
        if (sloWindowRetentionMillis != null) {
            target.sloWindowRetentionMillis(sloWindowRetentionMillis);
        }
        if (sloWindowMaxSamples != null) {
            target.sloWindowMaxSamples(sloWindowMaxSamples);
        }
        if (loadGenerationEnabled != null) {
            target.loadGenerationEnabled(loadGenerationEnabled);
        }
        if (loadGenerationSuppressEventLog != null) {
            target.loadGenerationSuppressEventLog(loadGenerationSuppressEventLog);
        }
        if (loadGenerationMaxVirtualUsers != null) {
            target.loadGenerationMaxVirtualUsers(loadGenerationMaxVirtualUsers);
        }
        if (loadGenerationMaxInFlightRequests != null) {
            target.loadGenerationMaxInFlightRequests(loadGenerationMaxInFlightRequests);
        }
        if (loadGenerationMaxRequestsPerSecond != null) {
            target.loadGenerationMaxRequestsPerSecond(loadGenerationMaxRequestsPerSecond);
        }
        if (loadGenerationMaxDurationMillis != null) {
            target.loadGenerationMaxDurationMillis(loadGenerationMaxDurationMillis);
        }
        if (loadGenerationMaxSteps != null) {
            target.loadGenerationMaxSteps(loadGenerationMaxSteps);
        }
        if (loadGenerationMaxRate != null) {
            target.loadGenerationMaxRate(loadGenerationMaxRate);
        }
        if (loadGenerationMaxStages != null) {
            target.loadGenerationMaxStages(loadGenerationMaxStages);
        }
        if (loadGenerationMaxConcurrentScenarios != null) {
            target.loadGenerationMaxConcurrentScenarios(loadGenerationMaxConcurrentScenarios);
        }
        if (loadScenarioInitializationJsonPath != null) {
            target.loadScenarioInitializationJsonPath(loadScenarioInitializationJsonPath);
        }
        if (loadGenerationMetricLabels != null) {
            target.loadGenerationMetricLabels(loadGenerationMetricLabels);
        }
        if (llmMetricsEnabled != null) {
            target.llmMetricsEnabled(llmMetricsEnabled);
        }
        if (perExpectationMetricsEnabled != null) {
            target.perExpectationMetricsEnabled(perExpectationMetricsEnabled);
        }
        if (deduplicateRecordedExpectations != null) {
            target.deduplicateRecordedExpectations(deduplicateRecordedExpectations);
        }
        if (templatizeRecordedValues != null) {
            target.templatizeRecordedValues(templatizeRecordedValues);
        }
        if (redactSecretsInRecordedExpectations != null) {
            target.redactSecretsInRecordedExpectations(redactSecretsInRecordedExpectations);
        }
        if (redactSecretsInLog != null) {
            target.redactSecretsInLog(redactSecretsInLog);
        }
        if (llmCostBudgetUsd != null) {
            target.llmCostBudgetUsd(llmCostBudgetUsd);
        }
        if (otelPropagateTraceContext != null) {
            target.otelPropagateTraceContext(otelPropagateTraceContext);
        }
        if (otelGenerateTraceId != null) {
            target.otelGenerateTraceId(otelGenerateTraceId);
        }
        if (wasmEnabled != null) {
            target.wasmEnabled(wasmEnabled);
        }
        if (wasmMaxMemoryPages != null) {
            target.wasmMaxMemoryPages(wasmMaxMemoryPages);
        }
        if (wasmExecutionTimeoutMillis != null) {
            target.wasmExecutionTimeoutMillis(wasmExecutionTimeoutMillis);
        }
        if (grpcDescriptorDirectory != null) {
            target.grpcDescriptorDirectory(grpcDescriptorDirectory);
        }
        if (grpcProtoDirectory != null) {
            target.grpcProtoDirectory(grpcProtoDirectory);
        }
        if (grpcEnabled != null) {
            target.grpcEnabled(grpcEnabled);
        }
        if (grpcProtocPath != null) {
            target.grpcProtocPath(grpcProtocPath);
        }
        if (grpcBidiStreamingEnabled != null) {
            target.grpcBidiStreamingEnabled(grpcBidiStreamingEnabled);
        }
        if (dnsEnabled != null) {
            target.dnsEnabled(dnsEnabled);
        }
        if (dnsPort != null) {
            target.dnsPort(dnsPort);
        }
        if (http3Port != null) {
            target.http3Port(http3Port);
        }
        if (http3MaxIdleTimeout != null) {
            target.http3MaxIdleTimeout(http3MaxIdleTimeout);
        }
        if (http3InitialMaxData != null) {
            target.http3InitialMaxData(http3InitialMaxData);
        }
        if (http3InitialMaxStreamDataBidirectional != null) {
            target.http3InitialMaxStreamDataBidirectional(http3InitialMaxStreamDataBidirectional);
        }
        if (http3InitialMaxStreamsBidirectional != null) {
            target.http3InitialMaxStreamsBidirectional(http3InitialMaxStreamsBidirectional);
        }
        if (http3QpackMaxTableCapacity != null) {
            target.http3QpackMaxTableCapacity(http3QpackMaxTableCapacity);
        }
        if (http3ConnectUdpEnabled != null) {
            target.http3ConnectUdpEnabled(http3ConnectUdpEnabled);
        }
        if (http3ConnectUdpAllowedTargets != null) {
            target.http3ConnectUdpAllowedTargets(http3ConnectUdpAllowedTargets);
        }
        if (http3AltSvcMaxAge != null) {
            target.http3AltSvcMaxAge(http3AltSvcMaxAge);
        }
        if (http3AdvertiseAltSvc != null) {
            target.http3AdvertiseAltSvc(http3AdvertiseAltSvc);
        }
        if (useNativeTransport != null) {
            target.useNativeTransport(useNativeTransport);
        }
        if (forwardConnectionPoolEnabled != null) {
            target.forwardConnectionPoolEnabled(forwardConnectionPoolEnabled);
        }
        if (forwardConnectionPoolMaxIdlePerKey != null) {
            target.forwardConnectionPoolMaxIdlePerKey(forwardConnectionPoolMaxIdlePerKey);
        }
        if (forwardConnectionPoolIdleTimeoutMillis != null) {
            target.forwardConnectionPoolIdleTimeoutMillis(forwardConnectionPoolIdleTimeoutMillis);
        }
        if (forwardConnectionPoolKeepAlive != null) {
            target.forwardConnectionPoolKeepAlive(forwardConnectionPoolKeepAlive);
        }
        if (forwardConnectionPoolMaxTotalPerKey != null) {
            target.forwardConnectionPoolMaxTotalPerKey(forwardConnectionPoolMaxTotalPerKey);
        }
        if (forwardSocketKeepAlive != null) {
            target.forwardSocketKeepAlive(forwardSocketKeepAlive);
        }
        if (forwardSocketKeepAliveIdleSeconds != null) {
            target.forwardSocketKeepAliveIdleSeconds(forwardSocketKeepAliveIdleSeconds);
        }
        if (forwardSocketKeepAliveIntervalSeconds != null) {
            target.forwardSocketKeepAliveIntervalSeconds(forwardSocketKeepAliveIntervalSeconds);
        }
        if (forwardSocketKeepAliveCount != null) {
            target.forwardSocketKeepAliveCount(forwardSocketKeepAliveCount);
        }
        if (forwardProxyRetryCount != null) {
            target.forwardProxyRetryCount(forwardProxyRetryCount);
        }
        if (forwardProxyRetryBackoffMillis != null) {
            target.forwardProxyRetryBackoffMillis(forwardProxyRetryBackoffMillis);
        }
        if (forwardProxyHttp2Enabled != null) {
            target.forwardProxyHttp2Enabled(forwardProxyHttp2Enabled);
        }
        if (forwardProxyHttp2Upgrade != null) {
            target.forwardProxyHttp2Upgrade(forwardProxyHttp2Upgrade);
        }
        if (forwardProxyCircuitBreakerEnabled != null) {
            target.forwardProxyCircuitBreakerEnabled(forwardProxyCircuitBreakerEnabled);
        }
        if (forwardProxyCircuitBreakerFailureThreshold != null) {
            target.forwardProxyCircuitBreakerFailureThreshold(forwardProxyCircuitBreakerFailureThreshold);
        }
        if (forwardProxyCircuitBreakerWindowMillis != null) {
            target.forwardProxyCircuitBreakerWindowMillis(forwardProxyCircuitBreakerWindowMillis);
        }
        if (enforceResponseValidationForMocks != null) {
            target.enforceResponseValidationForMocks(enforceResponseValidationForMocks);
        }
        if (maxRequestBodySize != null) {
            target.maxRequestBodySize(maxRequestBodySize);
        }
        if (maxGrpcMessageSize != null) {
            target.maxGrpcMessageSize(maxGrpcMessageSize);
        }
        if (maxResponseBodySize != null) {
            target.maxResponseBodySize(maxResponseBodySize);
        }
        if (maxLlmConversationBodySize != null) {
            target.maxLlmConversationBodySize(maxLlmConversationBodySize);
        }
        if (driftDetectionEnabled != null) {
            target.driftDetectionEnabled(driftDetectionEnabled);
        }
        if (driftSampleRate != null) {
            target.driftSampleRate(driftSampleRate);
        }
        if (driftSemanticAnalysisEnabled != null) {
            target.driftSemanticAnalysisEnabled(driftSemanticAnalysisEnabled);
        }
        if (driftResponseTimeThresholdMs != null) {
            target.driftResponseTimeThresholdMs(driftResponseTimeThresholdMs);
        }
        if (driftAlertWebhookEnabled != null) {
            target.driftAlertWebhookEnabled(driftAlertWebhookEnabled);
        }
        if (driftAlertWebhookUrl != null) {
            target.driftAlertWebhookUrl(driftAlertWebhookUrl);
        }
        if (driftAlertSeverityThreshold != null) {
            target.driftAlertSeverityThreshold(driftAlertSeverityThreshold);
        }
        if (driftAlertCooldownMillis != null) {
            target.driftAlertCooldownMillis(driftAlertCooldownMillis);
        }
        if (controlPlaneAuditEnabled != null) {
            target.controlPlaneAuditEnabled(controlPlaneAuditEnabled);
        }
        if (controlPlaneAuditMaxEntries != null) {
            target.controlPlaneAuditMaxEntries(controlPlaneAuditMaxEntries);
        }
        if (controlPlaneAuditReads != null) {
            target.controlPlaneAuditReads(controlPlaneAuditReads);
        }
        if (http2Enabled != null) {
            target.http2Enabled(http2Enabled);
        }
        if (streamingResponsesEnabled != null) {
            target.streamingResponsesEnabled(streamingResponsesEnabled);
        }
        if (maxStreamingCaptureBytes != null) {
            target.maxStreamingCaptureBytes(maxStreamingCaptureBytes);
        }
        if (streamIdleTimeoutSeconds != null) {
            target.streamIdleTimeoutSeconds(streamIdleTimeoutSeconds);
        }
        if (validateRequestsAgainstOpenApiSpec != null) {
            target.validateRequestsAgainstOpenApiSpec(validateRequestsAgainstOpenApiSpec);
        }
        if (detailedVerificationFailures != null) {
            target.detailedVerificationFailures(detailedVerificationFailures);
        }
        if (failVerificationOnEvictedLog != null) {
            target.failVerificationOnEvictedLog(failVerificationOnEvictedLog);
        }
        if (globalResponseDelayMillis != null) {
            target.globalResponseDelayMillis(globalResponseDelayMillis);
        }
        if (forwardAdjustHostHeader != null) {
            target.forwardAdjustHostHeader(forwardAdjustHostHeader);
        }
        if (forwardDefaultHostHeader != null) {
            target.forwardDefaultHostHeader(forwardDefaultHostHeader);
        }
        if (forwardProxyBlockPrivateNetworks != null) {
            target.forwardProxyBlockPrivateNetworks(forwardProxyBlockPrivateNetworks);
        }
        if (forwardProxyTLSHostnameVerificationEnabled != null) {
            target.forwardProxyTLSHostnameVerificationEnabled(forwardProxyTLSHostnameVerificationEnabled);
        }
        if (tlsAllowInsecureProtocols != null) {
            target.tlsAllowInsecureProtocols(tlsAllowInsecureProtocols);
        }
        if (stateBackend != null) {
            target.stateBackend(stateBackend);
        }
        if (blobStoreType != null) {
            target.blobStoreType(blobStoreType);
        }
        if (blobStoreBucket != null) {
            target.blobStoreBucket(blobStoreBucket);
        }
        if (blobStoreRegion != null) {
            target.blobStoreRegion(blobStoreRegion);
        }
        if (blobStoreEndpoint != null) {
            target.blobStoreEndpoint(blobStoreEndpoint);
        }
        if (blobStoreKeyPrefix != null) {
            target.blobStoreKeyPrefix(blobStoreKeyPrefix);
        }
        if (blobStoreAccessKeyId != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("blobStoreAccessKeyId", blobStoreAccessKeyId, target.blobStoreAccessKeyId());
            if (restored != null) {
                target.blobStoreAccessKeyId(restored);
            }
        }
        if (blobStoreSecretAccessKey != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("blobStoreSecretAccessKey", blobStoreSecretAccessKey, target.blobStoreSecretAccessKey());
            if (restored != null) {
                target.blobStoreSecretAccessKey(restored);
            }
        }
        if (blobStoreContainer != null) {
            target.blobStoreContainer(blobStoreContainer);
        }
        if (blobStoreConnectionString != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("blobStoreConnectionString", blobStoreConnectionString, target.blobStoreConnectionString());
            if (restored != null) {
                target.blobStoreConnectionString(restored);
            }
        }
        if (blobStoreProjectId != null) {
            target.blobStoreProjectId(blobStoreProjectId);
        }
        if (blobStoreRestoreTimeoutSeconds != null) {
            target.blobStoreRestoreTimeoutSeconds(blobStoreRestoreTimeoutSeconds);
        }
        if (clusterEnabled != null) {
            target.clusterEnabled(clusterEnabled);
        }
        if (clusterName != null) {
            target.clusterName(clusterName);
        }
        if (clusterTransportConfig != null) {
            target.clusterTransportConfig(clusterTransportConfig);
        }
        if (clusterSharedTimesEnabled != null) {
            target.clusterSharedTimesEnabled(clusterSharedTimesEnabled);
        }
        if (clusterVerifyFanIn != null) {
            target.clusterVerifyFanIn(clusterVerifyFanIn);
        }
        if (clusterVerifyFanInPeers != null) {
            target.clusterVerifyFanInPeers(clusterVerifyFanInPeers);
        }
        if (clusterFanInPeerAuthToken != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("clusterFanInPeerAuthToken", clusterFanInPeerAuthToken, target.clusterFanInPeerAuthToken());
            if (restored != null) {
                target.clusterFanInPeerAuthToken(restored);
            }
        }
        if (controlPlaneOidcAuthenticationRequired != null) {
            target.controlPlaneOidcAuthenticationRequired(controlPlaneOidcAuthenticationRequired);
        }
        if (controlPlaneOidcIssuer != null) {
            target.controlPlaneOidcIssuer(controlPlaneOidcIssuer);
        }
        if (controlPlaneOidcJwksUri != null) {
            target.controlPlaneOidcJwksUri(controlPlaneOidcJwksUri);
        }
        if (controlPlaneOidcAudience != null) {
            target.controlPlaneOidcAudience(controlPlaneOidcAudience);
        }
        if (controlPlaneOidcRequiredScopes != null) {
            target.controlPlaneOidcRequiredScopes(controlPlaneOidcRequiredScopes);
        }
        if (controlPlaneOidcScopeClaim != null) {
            target.controlPlaneOidcScopeClaim(controlPlaneOidcScopeClaim);
        }
        if (controlPlaneAuthorizationEnabled != null) {
            target.controlPlaneAuthorizationEnabled(controlPlaneAuthorizationEnabled);
        }
        if (controlPlaneScopeMapping != null) {
            target.controlPlaneScopeMapping(controlPlaneScopeMapping);
        }
        if (transparentProxyEnabled != null) {
            target.transparentProxyEnabled(transparentProxyEnabled);
        }
        if (transparentProxyTproxy != null) {
            target.transparentProxyTproxy(transparentProxyTproxy);
        }
        if (transparentProxyEbpf != null) {
            target.transparentProxyEbpf(transparentProxyEbpf);
        }
        if (transparentProxyEbpfMapPath != null) {
            target.transparentProxyEbpfMapPath(transparentProxyEbpfMapPath);
        }
        if (asyncKafkaBootstrapServers != null) {
            target.asyncKafkaBootstrapServers(asyncKafkaBootstrapServers);
        }
        if (asyncMqttBrokerUrl != null) {
            target.asyncMqttBrokerUrl(asyncMqttBrokerUrl);
        }
        if (asyncAmqpUri != null) {
            target.asyncAmqpUri(asyncAmqpUri);
        }
        if (asyncRecordedMessageMaxEntries != null) {
            target.asyncRecordedMessageMaxEntries(asyncRecordedMessageMaxEntries);
        }
        if (llmProvider != null) {
            target.llmProvider(llmProvider);
        }
        if (llmModel != null) {
            target.llmModel(llmModel);
        }
        if (llmBaseUrl != null) {
            target.llmBaseUrl(llmBaseUrl);
        }
        if (llmBackendsConfig != null) {
            // value-embedded credentials: each redacted field is restored from the value the target
            // already holds, so an edit to the surrounding document is applied while a field the
            // client only ever saw masked keeps its real value instead of being overwritten by it
            String restored = ConfigurationProperties.restoreRedactedValue("llmBackendsConfig", llmBackendsConfig, target.llmBackendsConfig());
            if (restored != null) {
                target.llmBackendsConfig(restored);
            }
        }
        if (llmRequestTimeoutMillis != null) {
            target.llmRequestTimeoutMillis(llmRequestTimeoutMillis);
        }
        if (llmSemanticMatchingEnabled != null) {
            target.llmSemanticMatchingEnabled(llmSemanticMatchingEnabled);
        }
        if (llmInferUsageEnabled != null) {
            target.llmInferUsageEnabled(llmInferUsageEnabled);
        }
        if (llmVcrStrict != null) {
            target.llmVcrStrict(llmVcrStrict);
        }
        if (llmOptimisationMaxCalls != null) {
            target.llmOptimisationMaxCalls(llmOptimisationMaxCalls);
        }
        if (fixtureBodyRedactFields != null) {
            target.fixtureBodyRedactFields(fixtureBodyRedactFields);
        }
        if (otelEndpoint != null) {
            target.otelEndpoint(otelEndpoint);
        }
        if (otelMetricsEnabled != null) {
            target.otelMetricsEnabled(otelMetricsEnabled);
        }
        if (otelTracesEnabled != null) {
            target.otelTracesEnabled(otelTracesEnabled);
        }
        if (otelMetricsExportIntervalSeconds != null) {
            target.otelMetricsExportIntervalSeconds(otelMetricsExportIntervalSeconds);
        }
        if (otelMetricsTemporality != null) {
            target.otelMetricsTemporality(otelMetricsTemporality);
        }
        if (prometheusRemoteWriteEnabled != null) {
            target.prometheusRemoteWriteEnabled(prometheusRemoteWriteEnabled);
        }
        if (prometheusRemoteWriteUrl != null) {
            target.prometheusRemoteWriteUrl(prometheusRemoteWriteUrl);
        }
        if (prometheusRemoteWriteIntervalSeconds != null) {
            target.prometheusRemoteWriteIntervalSeconds(prometheusRemoteWriteIntervalSeconds);
        }
        if (prometheusRemoteWriteBasicAuthUsername != null) {
            target.prometheusRemoteWriteBasicAuthUsername(prometheusRemoteWriteBasicAuthUsername);
        }
        if (prometheusRemoteWriteHeaders != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("prometheusRemoteWriteHeaders", prometheusRemoteWriteHeaders, target.prometheusRemoteWriteHeaders());
            if (restored != null) {
                target.prometheusRemoteWriteHeaders(restored);
            }
        }
        if (prometheusRemoteWriteProtocolVersion != null) {
            target.prometheusRemoteWriteProtocolVersion(prometheusRemoteWriteProtocolVersion);
        }
        if (regexMatchingTimeoutMillis != null) {
            target.regexMatchingTimeoutMillis(regexMatchingTimeoutMillis);
        }
        if (xpathMatchingTimeoutMillis != null) {
            target.xpathMatchingTimeoutMillis(xpathMatchingTimeoutMillis);
        }
        if (customJsonUnitMatchersClass != null) {
            target.customJsonUnitMatchersClass(customJsonUnitMatchersClass);
        }
        // write-only credentials: null means "not supplied" (leave the existing credential alone) and a
        // value CARRYING the redaction mask means "this came from a masked GET" — which must ALSO leave
        // the existing credential alone, or a GET-then-PUT round trip silently destroys it. Reconciled
        // through the same door as the value-embedded credentials above, so the refusal is logged and
        // the "no value carrying the mask ever reaches the live configuration" invariant is enforced in
        // one place for both shapes.
        if (llmApiKey != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("llmApiKey", llmApiKey, target.llmApiKey());
            if (restored != null) {
                target.llmApiKey(restored);
            }
        }
        if (prometheusRemoteWriteBearerToken != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("prometheusRemoteWriteBearerToken", prometheusRemoteWriteBearerToken, target.prometheusRemoteWriteBearerToken());
            if (restored != null) {
                target.prometheusRemoteWriteBearerToken(restored);
            }
        }
        if (prometheusRemoteWriteBasicAuthPassword != null) {
            String restored = ConfigurationProperties.restoreRedactedValue("prometheusRemoteWriteBasicAuthPassword", prometheusRemoteWriteBasicAuthPassword, target.prometheusRemoteWriteBasicAuthPassword());
            if (restored != null) {
                target.prometheusRemoteWriteBasicAuthPassword(restored);
            }
        }
    }

    private InetSocketAddress parseInetSocketAddress(String hostAndPort) {
        try {
            java.net.URI uri = new java.net.URI("dummy://" + hostAndPort);
            String host = uri.getHost();
            int port = uri.getPort();
            if (host == null || port == -1) {
                throw new IllegalArgumentException("Invalid host:port format: \"" + hostAndPort + "\", expected format \"host:port\"");
            }
            return InetSocketAddress.createUnresolved(host, port);
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Invalid host:port format: \"" + hostAndPort + "\", expected format \"host:port\"");
        }
    }

    public String getLogLevel() {
        return logLevel;
    }

    public ConfigurationDTO setLogLevel(String logLevel) {
        this.logLevel = logLevel;
        return this;
    }

    public Boolean getDisableSystemOut() {
        return disableSystemOut;
    }

    public ConfigurationDTO setDisableSystemOut(Boolean disableSystemOut) {
        this.disableSystemOut = disableSystemOut;
        return this;
    }

    public Boolean getDisableLogging() {
        return disableLogging;
    }

    public ConfigurationDTO setDisableLogging(Boolean disableLogging) {
        this.disableLogging = disableLogging;
        return this;
    }

    public Boolean getDetailedMatchFailures() {
        return detailedMatchFailures;
    }

    public ConfigurationDTO setDetailedMatchFailures(Boolean detailedMatchFailures) {
        this.detailedMatchFailures = detailedMatchFailures;
        return this;
    }

    public Boolean getLaunchUIForLogLevelDebug() {
        return launchUIForLogLevelDebug;
    }

    public ConfigurationDTO setLaunchUIForLogLevelDebug(Boolean launchUIForLogLevelDebug) {
        this.launchUIForLogLevelDebug = launchUIForLogLevelDebug;
        return this;
    }

    public Boolean getMetricsEnabled() {
        return metricsEnabled;
    }

    public ConfigurationDTO setMetricsEnabled(Boolean metricsEnabled) {
        this.metricsEnabled = metricsEnabled;
        return this;
    }

    public Boolean getDashboardAnalyticsEnabled() {
        return dashboardAnalyticsEnabled;
    }

    public ConfigurationDTO setDashboardAnalyticsEnabled(Boolean dashboardAnalyticsEnabled) {
        this.dashboardAnalyticsEnabled = dashboardAnalyticsEnabled;
        return this;
    }

    public String getDashboardAnalyticsEndpoint() {
        return dashboardAnalyticsEndpoint;
    }

    public ConfigurationDTO setDashboardAnalyticsEndpoint(String dashboardAnalyticsEndpoint) {
        this.dashboardAnalyticsEndpoint = dashboardAnalyticsEndpoint;
        return this;
    }

    /**
     * DELIBERATELY READABLE, despite the credential-shaped name. This is a PostHog PROJECT key — an
     * ingest-only token designed to be embedded in a public web page — and the browser dashboard reads
     * it back from this endpoint to initialise its analytics client. The browser must receive this key
     * for analytics to function at all, and an ingest-only project key is designed for exactly that
     * exposure, so masking it would break the feature while protecting nothing.
     *
     * <p>{@code GET /mockserver/config} and the startup property-file log dump DO mask it — those are
     * diagnostic surfaces whose output gets pasted into issues. The exemption is asserted, with its
     * reasoning, in {@code ConfigurationDTOCredentialMaskingTest}; do not "fix" this without reading it.
     */
    public String getDashboardAnalyticsKey() {
        return dashboardAnalyticsKey;
    }

    public ConfigurationDTO setDashboardAnalyticsKey(String dashboardAnalyticsKey) {
        this.dashboardAnalyticsKey = dashboardAnalyticsKey;
        return this;
    }

    public String getDashboardAnalyticsDistribution() {
        return dashboardAnalyticsDistribution;
    }

    public ConfigurationDTO setDashboardAnalyticsDistribution(String dashboardAnalyticsDistribution) {
        this.dashboardAnalyticsDistribution = dashboardAnalyticsDistribution;
        return this;
    }

    public Boolean getChaosAutoHaltEnabled() {
        return chaosAutoHaltEnabled;
    }

    public ConfigurationDTO setChaosAutoHaltEnabled(Boolean chaosAutoHaltEnabled) {
        this.chaosAutoHaltEnabled = chaosAutoHaltEnabled;
        return this;
    }

    public Long getChaosAutoHaltErrorThreshold() {
        return chaosAutoHaltErrorThreshold;
    }

    public ConfigurationDTO setChaosAutoHaltErrorThreshold(Long chaosAutoHaltErrorThreshold) {
        this.chaosAutoHaltErrorThreshold = chaosAutoHaltErrorThreshold;
        return this;
    }

    public Long getChaosAutoHaltWindowMillis() {
        return chaosAutoHaltWindowMillis;
    }

    public ConfigurationDTO setChaosAutoHaltWindowMillis(Long chaosAutoHaltWindowMillis) {
        this.chaosAutoHaltWindowMillis = chaosAutoHaltWindowMillis;
        return this;
    }

    public Boolean getMcpEnabled() {
        return mcpEnabled;
    }

    public ConfigurationDTO setMcpEnabled(Boolean mcpEnabled) {
        this.mcpEnabled = mcpEnabled;
        return this;
    }

    public Long getBreakpointTimeoutMillis() {
        return breakpointTimeoutMillis;
    }

    public ConfigurationDTO setBreakpointTimeoutMillis(Long breakpointTimeoutMillis) {
        this.breakpointTimeoutMillis = breakpointTimeoutMillis;
        return this;
    }

    public Integer getBreakpointMaxHeld() {
        return breakpointMaxHeld;
    }

    public ConfigurationDTO setBreakpointMaxHeld(Integer breakpointMaxHeld) {
        this.breakpointMaxHeld = breakpointMaxHeld;
        return this;
    }

    public Map<String, String> getLogLevelOverrides() {
        return logLevelOverrides;
    }

    public ConfigurationDTO setLogLevelOverrides(Map<String, String> logLevelOverrides) {
        this.logLevelOverrides = logLevelOverrides;
        return this;
    }

    public Boolean getCompactLogFormat() {
        return compactLogFormat;
    }

    public ConfigurationDTO setCompactLogFormat(Boolean compactLogFormat) {
        this.compactLogFormat = compactLogFormat;
        return this;
    }

    public Boolean getDevMode() {
        return devMode;
    }

    public ConfigurationDTO setDevMode(Boolean devMode) {
        this.devMode = devMode;
        return this;
    }

    public Integer getMaxExpectations() {
        return maxExpectations;
    }

    public ConfigurationDTO setMaxExpectations(Integer maxExpectations) {
        this.maxExpectations = maxExpectations;
        return this;
    }

    public Integer getMaxLogEntries() {
        return maxLogEntries;
    }

    public ConfigurationDTO setMaxLogEntries(Integer maxLogEntries) {
        this.maxLogEntries = maxLogEntries;
        return this;
    }

    public Long getMaxEventLogSizeInBytes() {
        return maxEventLogSizeInBytes;
    }

    public ConfigurationDTO setMaxEventLogSizeInBytes(Long maxEventLogSizeInBytes) {
        this.maxEventLogSizeInBytes = maxEventLogSizeInBytes;
        return this;
    }

    public Integer getMaxLoggedBodyBytes() {
        return maxLoggedBodyBytes;
    }

    public ConfigurationDTO setMaxLoggedBodyBytes(Integer maxLoggedBodyBytes) {
        this.maxLoggedBodyBytes = maxLoggedBodyBytes;
        return this;
    }

    public Integer getRingBufferSize() {
        return ringBufferSize;
    }

    public ConfigurationDTO setRingBufferSize(Integer ringBufferSize) {
        this.ringBufferSize = ringBufferSize;
        return this;
    }

    public Integer getMaxWebSocketExpectations() {
        return maxWebSocketExpectations;
    }

    public ConfigurationDTO setMaxWebSocketExpectations(Integer maxWebSocketExpectations) {
        this.maxWebSocketExpectations = maxWebSocketExpectations;
        return this;
    }

    public Integer getWebSocketProxyMaxRecordedFrames() {
        return webSocketProxyMaxRecordedFrames;
    }

    public ConfigurationDTO setWebSocketProxyMaxRecordedFrames(Integer webSocketProxyMaxRecordedFrames) {
        this.webSocketProxyMaxRecordedFrames = webSocketProxyMaxRecordedFrames;
        return this;
    }

    public Integer getWebSocketProxyIdleTimeoutSeconds() {
        return webSocketProxyIdleTimeoutSeconds;
    }

    public ConfigurationDTO setWebSocketProxyIdleTimeoutSeconds(Integer webSocketProxyIdleTimeoutSeconds) {
        this.webSocketProxyIdleTimeoutSeconds = webSocketProxyIdleTimeoutSeconds;
        return this;
    }

    public Boolean getOutputMemoryUsageCsv() {
        return outputMemoryUsageCsv;
    }

    public ConfigurationDTO setOutputMemoryUsageCsv(Boolean outputMemoryUsageCsv) {
        this.outputMemoryUsageCsv = outputMemoryUsageCsv;
        return this;
    }

    public String getMemoryUsageCsvDirectory() {
        return memoryUsageCsvDirectory;
    }

    public ConfigurationDTO setMemoryUsageCsvDirectory(String memoryUsageCsvDirectory) {
        this.memoryUsageCsvDirectory = memoryUsageCsvDirectory;
        return this;
    }

    public Integer getNioEventLoopThreadCount() {
        return nioEventLoopThreadCount;
    }

    public ConfigurationDTO setNioEventLoopThreadCount(Integer nioEventLoopThreadCount) {
        this.nioEventLoopThreadCount = nioEventLoopThreadCount;
        return this;
    }

    public Integer getActionHandlerThreadCount() {
        return actionHandlerThreadCount;
    }

    public ConfigurationDTO setActionHandlerThreadCount(Integer actionHandlerThreadCount) {
        this.actionHandlerThreadCount = actionHandlerThreadCount;
        return this;
    }

    public Integer getClientNioEventLoopThreadCount() {
        return clientNioEventLoopThreadCount;
    }

    public ConfigurationDTO setClientNioEventLoopThreadCount(Integer clientNioEventLoopThreadCount) {
        this.clientNioEventLoopThreadCount = clientNioEventLoopThreadCount;
        return this;
    }

    public Integer getWebSocketClientEventLoopThreadCount() {
        return webSocketClientEventLoopThreadCount;
    }

    public ConfigurationDTO setWebSocketClientEventLoopThreadCount(Integer webSocketClientEventLoopThreadCount) {
        this.webSocketClientEventLoopThreadCount = webSocketClientEventLoopThreadCount;
        return this;
    }

    public Long getMaxFutureTimeoutInMillis() {
        return maxFutureTimeoutInMillis;
    }

    public ConfigurationDTO setMaxFutureTimeoutInMillis(Long maxFutureTimeoutInMillis) {
        this.maxFutureTimeoutInMillis = maxFutureTimeoutInMillis;
        return this;
    }

    public Boolean getMatchersFailFast() {
        return matchersFailFast;
    }

    public ConfigurationDTO setMatchersFailFast(Boolean matchersFailFast) {
        this.matchersFailFast = matchersFailFast;
        return this;
    }

    public Boolean getMatchExactCase() {
        return matchExactCase;
    }

    public ConfigurationDTO setMatchExactCase(Boolean matchExactCase) {
        this.matchExactCase = matchExactCase;
        return this;
    }

    public Long getMaxSocketTimeoutInMillis() {
        return maxSocketTimeoutInMillis;
    }

    public ConfigurationDTO setMaxSocketTimeoutInMillis(Long maxSocketTimeoutInMillis) {
        this.maxSocketTimeoutInMillis = maxSocketTimeoutInMillis;
        return this;
    }

    public Long getSocketConnectionTimeoutInMillis() {
        return socketConnectionTimeoutInMillis;
    }

    public ConfigurationDTO setSocketConnectionTimeoutInMillis(Long socketConnectionTimeoutInMillis) {
        this.socketConnectionTimeoutInMillis = socketConnectionTimeoutInMillis;
        return this;
    }

    public DelayDTO getConnectionDelay() {
        return connectionDelay;
    }

    public ConfigurationDTO setConnectionDelay(DelayDTO connectionDelay) {
        this.connectionDelay = connectionDelay;
        return this;
    }

    public Boolean getAlwaysCloseSocketConnections() {
        return alwaysCloseSocketConnections;
    }

    public ConfigurationDTO setAlwaysCloseSocketConnections(Boolean alwaysCloseSocketConnections) {
        this.alwaysCloseSocketConnections = alwaysCloseSocketConnections;
        return this;
    }

    public String getLocalBoundIP() {
        return localBoundIP;
    }

    public ConfigurationDTO setLocalBoundIP(String localBoundIP) {
        this.localBoundIP = localBoundIP;
        return this;
    }

    public Integer getMaxInitialLineLength() {
        return maxInitialLineLength;
    }

    public ConfigurationDTO setMaxInitialLineLength(Integer maxInitialLineLength) {
        this.maxInitialLineLength = maxInitialLineLength;
        return this;
    }

    public Integer getMaxHeaderSize() {
        return maxHeaderSize;
    }

    public ConfigurationDTO setMaxHeaderSize(Integer maxHeaderSize) {
        this.maxHeaderSize = maxHeaderSize;
        return this;
    }

    public Integer getMaxChunkSize() {
        return maxChunkSize;
    }

    public ConfigurationDTO setMaxChunkSize(Integer maxChunkSize) {
        this.maxChunkSize = maxChunkSize;
        return this;
    }

    public Boolean getUseSemicolonAsQueryParameterSeparator() {
        return useSemicolonAsQueryParameterSeparator;
    }

    public ConfigurationDTO setUseSemicolonAsQueryParameterSeparator(Boolean useSemicolonAsQueryParameterSeparator) {
        this.useSemicolonAsQueryParameterSeparator = useSemicolonAsQueryParameterSeparator;
        return this;
    }

    public Boolean getStartupWarmup() {
        return startupWarmup;
    }

    public ConfigurationDTO setStartupWarmup(Boolean startupWarmup) {
        this.startupWarmup = startupWarmup;
        return this;
    }

    public Boolean getAssumeAllRequestsAreHttp() {
        return assumeAllRequestsAreHttp;
    }

    public ConfigurationDTO setAssumeAllRequestsAreHttp(Boolean assumeAllRequestsAreHttp) {
        this.assumeAllRequestsAreHttp = assumeAllRequestsAreHttp;
        return this;
    }

    public Boolean getForwardBinaryRequestsWithoutWaitingForResponse() {
        return forwardBinaryRequestsWithoutWaitingForResponse;
    }

    public ConfigurationDTO setForwardBinaryRequestsWithoutWaitingForResponse(Boolean forwardBinaryRequestsWithoutWaitingForResponse) {
        this.forwardBinaryRequestsWithoutWaitingForResponse = forwardBinaryRequestsWithoutWaitingForResponse;
        return this;
    }

    public Boolean getEnableCORSForAPI() {
        return enableCORSForAPI;
    }

    public ConfigurationDTO setEnableCORSForAPI(Boolean enableCORSForAPI) {
        this.enableCORSForAPI = enableCORSForAPI;
        return this;
    }

    public Boolean getEnableCORSForAllResponses() {
        return enableCORSForAllResponses;
    }

    public ConfigurationDTO setEnableCORSForAllResponses(Boolean enableCORSForAllResponses) {
        this.enableCORSForAllResponses = enableCORSForAllResponses;
        return this;
    }

    public String getCorsAllowOrigin() {
        return corsAllowOrigin;
    }

    public ConfigurationDTO setCorsAllowOrigin(String corsAllowOrigin) {
        this.corsAllowOrigin = corsAllowOrigin;
        return this;
    }

    public String getCorsAllowMethods() {
        return corsAllowMethods;
    }

    public ConfigurationDTO setCorsAllowMethods(String corsAllowMethods) {
        this.corsAllowMethods = corsAllowMethods;
        return this;
    }

    public String getCorsAllowHeaders() {
        return corsAllowHeaders;
    }

    public ConfigurationDTO setCorsAllowHeaders(String corsAllowHeaders) {
        this.corsAllowHeaders = corsAllowHeaders;
        return this;
    }

    public Boolean getCorsAllowCredentials() {
        return corsAllowCredentials;
    }

    public ConfigurationDTO setCorsAllowCredentials(Boolean corsAllowCredentials) {
        this.corsAllowCredentials = corsAllowCredentials;
        return this;
    }

    public Integer getCorsMaxAgeInSeconds() {
        return corsMaxAgeInSeconds;
    }

    public ConfigurationDTO setCorsMaxAgeInSeconds(Integer corsMaxAgeInSeconds) {
        this.corsMaxAgeInSeconds = corsMaxAgeInSeconds;
        return this;
    }

    public String getDefaultResponseHeaders() {
        return defaultResponseHeaders;
    }

    public ConfigurationDTO setDefaultResponseHeaders(String defaultResponseHeaders) {
        this.defaultResponseHeaders = defaultResponseHeaders;
        return this;
    }

    public String getJavascriptDisallowedClasses() {
        return javascriptDisallowedClasses;
    }

    public ConfigurationDTO setJavascriptDisallowedClasses(String javascriptDisallowedClasses) {
        this.javascriptDisallowedClasses = javascriptDisallowedClasses;
        return this;
    }

    public String getJavascriptAllowedClasses() {
        return javascriptAllowedClasses;
    }

    public ConfigurationDTO setJavascriptAllowedClasses(String javascriptAllowedClasses) {
        this.javascriptAllowedClasses = javascriptAllowedClasses;
        return this;
    }

    public String getJavascriptDisallowedText() {
        return javascriptDisallowedText;
    }

    public ConfigurationDTO setJavascriptDisallowedText(String javascriptDisallowedText) {
        this.javascriptDisallowedText = javascriptDisallowedText;
        return this;
    }

    public Long getJavascriptTemplateExecutionTimeout() {
        return javascriptTemplateExecutionTimeout;
    }

    public ConfigurationDTO setJavascriptTemplateExecutionTimeout(Long javascriptTemplateExecutionTimeout) {
        this.javascriptTemplateExecutionTimeout = javascriptTemplateExecutionTimeout;
        return this;
    }

    public Boolean getVelocityDisallowClassLoading() {
        return velocityDisallowClassLoading;
    }

    public ConfigurationDTO setVelocityDisallowClassLoading(Boolean velocityDisallowClassLoading) {
        this.velocityDisallowClassLoading = velocityDisallowClassLoading;
        return this;
    }

    public String getVelocityDisallowedText() {
        return velocityDisallowedText;
    }

    public ConfigurationDTO setVelocityDisallowedText(String velocityDisallowedText) {
        this.velocityDisallowedText = velocityDisallowedText;
        return this;
    }

    public String getMustacheDisallowedText() {
        return mustacheDisallowedText;
    }

    public ConfigurationDTO setMustacheDisallowedText(String mustacheDisallowedText) {
        this.mustacheDisallowedText = mustacheDisallowedText;
        return this;
    }

    public Long getTemplateFakerSeed() {
        return templateFakerSeed;
    }

    public ConfigurationDTO setTemplateFakerSeed(Long templateFakerSeed) {
        this.templateFakerSeed = templateFakerSeed;
        return this;
    }

    public String getInitializationClass() {
        return initializationClass;
    }

    public ConfigurationDTO setInitializationClass(String initializationClass) {
        this.initializationClass = initializationClass;
        return this;
    }

    public String getInitializationJsonPath() {
        return initializationJsonPath;
    }

    public ConfigurationDTO setInitializationJsonPath(String initializationJsonPath) {
        this.initializationJsonPath = initializationJsonPath;
        return this;
    }

    public String getInitializationOpenAPIPath() {
        return initializationOpenAPIPath;
    }

    public ConfigurationDTO setInitializationOpenAPIPath(String initializationOpenAPIPath) {
        this.initializationOpenAPIPath = initializationOpenAPIPath;
        return this;
    }

    public String getOpenAPIContextPathPrefix() {
        return openAPIContextPathPrefix;
    }

    public ConfigurationDTO setOpenAPIContextPathPrefix(String openAPIContextPathPrefix) {
        this.openAPIContextPathPrefix = openAPIContextPathPrefix;
        return this;
    }

    public Boolean getOpenAPIResponseValidation() {
        return openAPIResponseValidation;
    }

    public ConfigurationDTO setOpenAPIResponseValidation(Boolean openAPIResponseValidation) {
        this.openAPIResponseValidation = openAPIResponseValidation;
        return this;
    }

    public String getValidateProxyOpenAPISpec() {
        return validateProxyOpenAPISpec;
    }

    public ConfigurationDTO setValidateProxyOpenAPISpec(String validateProxyOpenAPISpec) {
        this.validateProxyOpenAPISpec = validateProxyOpenAPISpec;
        return this;
    }

    public Boolean getValidateProxyEnforce() {
        return validateProxyEnforce;
    }

    public ConfigurationDTO setValidateProxyEnforce(Boolean validateProxyEnforce) {
        this.validateProxyEnforce = validateProxyEnforce;
        return this;
    }

    public Boolean getGenerateRealisticExampleValues() {
        return generateRealisticExampleValues;
    }

    public ConfigurationDTO setGenerateRealisticExampleValues(Boolean generateRealisticExampleValues) {
        this.generateRealisticExampleValues = generateRealisticExampleValues;
        return this;
    }

    public Boolean getWatchInitializationJson() {
        return watchInitializationJson;
    }

    public ConfigurationDTO setWatchInitializationJson(Boolean watchInitializationJson) {
        this.watchInitializationJson = watchInitializationJson;
        return this;
    }

    public Long getWatchInitializationJsonPollPeriodMillis() {
        return watchInitializationJsonPollPeriodMillis;
    }

    public ConfigurationDTO setWatchInitializationJsonPollPeriodMillis(Long watchInitializationJsonPollPeriodMillis) {
        this.watchInitializationJsonPollPeriodMillis = watchInitializationJsonPollPeriodMillis;
        return this;
    }

    public Boolean getFailOnInitializationError() {
        return failOnInitializationError;
    }

    public ConfigurationDTO setFailOnInitializationError(Boolean failOnInitializationError) {
        this.failOnInitializationError = failOnInitializationError;
        return this;
    }

    public Boolean getPersistExpectations() {
        return persistExpectations;
    }

    public ConfigurationDTO setPersistExpectations(Boolean persistExpectations) {
        this.persistExpectations = persistExpectations;
        return this;
    }

    public String getPersistedExpectationsPath() {
        return persistedExpectationsPath;
    }

    public ConfigurationDTO setPersistedExpectationsPath(String persistedExpectationsPath) {
        this.persistedExpectationsPath = persistedExpectationsPath;
        return this;
    }

    public String getAuditLogFile() {
        return auditLogFile;
    }

    public ConfigurationDTO setAuditLogFile(String auditLogFile) {
        this.auditLogFile = auditLogFile;
        return this;
    }

    public Boolean getPersistRecordedExpectations() {
        return persistRecordedExpectations;
    }

    public ConfigurationDTO setPersistRecordedExpectations(Boolean persistRecordedExpectations) {
        this.persistRecordedExpectations = persistRecordedExpectations;
        return this;
    }

    public String getPersistedRecordedExpectationsPath() {
        return persistedRecordedExpectationsPath;
    }

    public ConfigurationDTO setPersistedRecordedExpectationsPath(String persistedRecordedExpectationsPath) {
        this.persistedRecordedExpectationsPath = persistedRecordedExpectationsPath;
        return this;
    }

    public Boolean getPersistRecordedRequestsToDisk() {
        return persistRecordedRequestsToDisk;
    }

    public ConfigurationDTO setPersistRecordedRequestsToDisk(Boolean persistRecordedRequestsToDisk) {
        this.persistRecordedRequestsToDisk = persistRecordedRequestsToDisk;
        return this;
    }

    public String getPersistedRecordedRequestsPath() {
        return persistedRecordedRequestsPath;
    }

    public ConfigurationDTO setPersistedRecordedRequestsPath(String persistedRecordedRequestsPath) {
        this.persistedRecordedRequestsPath = persistedRecordedRequestsPath;
        return this;
    }

    public Integer getMaximumNumberOfRequestToReturnInVerificationFailure() {
        return maximumNumberOfRequestToReturnInVerificationFailure;
    }

    public ConfigurationDTO setMaximumNumberOfRequestToReturnInVerificationFailure(Integer maximumNumberOfRequestToReturnInVerificationFailure) {
        this.maximumNumberOfRequestToReturnInVerificationFailure = maximumNumberOfRequestToReturnInVerificationFailure;
        return this;
    }

    public Boolean getAttachMismatchDiagnosticToResponse() {
        return attachMismatchDiagnosticToResponse;
    }

    public ConfigurationDTO setAttachMismatchDiagnosticToResponse(Boolean attachMismatchDiagnosticToResponse) {
        this.attachMismatchDiagnosticToResponse = attachMismatchDiagnosticToResponse;
        return this;
    }

    public Boolean getClosestMatchHintEnabled() {
        return closestMatchHintEnabled;
    }

    public ConfigurationDTO setClosestMatchHintEnabled(Boolean closestMatchHintEnabled) {
        this.closestMatchHintEnabled = closestMatchHintEnabled;
        return this;
    }

    public Boolean getAttemptToProxyIfNoMatchingExpectation() {
        return attemptToProxyIfNoMatchingExpectation;
    }

    public ConfigurationDTO setAttemptToProxyIfNoMatchingExpectation(Boolean attemptToProxyIfNoMatchingExpectation) {
        this.attemptToProxyIfNoMatchingExpectation = attemptToProxyIfNoMatchingExpectation;
        return this;
    }

    public String getForwardHttpProxy() {
        return forwardHttpProxy;
    }

    public ConfigurationDTO setForwardHttpProxy(String forwardHttpProxy) {
        this.forwardHttpProxy = forwardHttpProxy;
        return this;
    }

    public String getForwardHttpsProxy() {
        return forwardHttpsProxy;
    }

    public ConfigurationDTO setForwardHttpsProxy(String forwardHttpsProxy) {
        this.forwardHttpsProxy = forwardHttpsProxy;
        return this;
    }

    public String getForwardSocksProxy() {
        return forwardSocksProxy;
    }

    public ConfigurationDTO setForwardSocksProxy(String forwardSocksProxy) {
        this.forwardSocksProxy = forwardSocksProxy;
        return this;
    }

    public String getForwardProxyAuthenticationUsername() {
        return forwardProxyAuthenticationUsername;
    }

    public ConfigurationDTO setForwardProxyAuthenticationUsername(String forwardProxyAuthenticationUsername) {
        this.forwardProxyAuthenticationUsername = forwardProxyAuthenticationUsername;
        return this;
    }

    @JsonIgnore
    public String getForwardProxyAuthenticationPassword() {
        return forwardProxyAuthenticationPassword;
    }

    @JsonProperty
    public ConfigurationDTO setForwardProxyAuthenticationPassword(String forwardProxyAuthenticationPassword) {
        this.forwardProxyAuthenticationPassword = forwardProxyAuthenticationPassword;
        return this;
    }

    public String getProxyAuthenticationRealm() {
        return proxyAuthenticationRealm;
    }

    public ConfigurationDTO setProxyAuthenticationRealm(String proxyAuthenticationRealm) {
        this.proxyAuthenticationRealm = proxyAuthenticationRealm;
        return this;
    }

    public String getProxyAuthenticationUsername() {
        return proxyAuthenticationUsername;
    }

    public ConfigurationDTO setProxyAuthenticationUsername(String proxyAuthenticationUsername) {
        this.proxyAuthenticationUsername = proxyAuthenticationUsername;
        return this;
    }

    @JsonIgnore
    public String getProxyAuthenticationPassword() {
        return proxyAuthenticationPassword;
    }

    @JsonProperty
    public ConfigurationDTO setProxyAuthenticationPassword(String proxyAuthenticationPassword) {
        this.proxyAuthenticationPassword = proxyAuthenticationPassword;
        return this;
    }

    public Boolean getDataPlaneAuthenticationRequired() {
        return dataPlaneAuthenticationRequired;
    }

    public ConfigurationDTO setDataPlaneAuthenticationRequired(Boolean dataPlaneAuthenticationRequired) {
        this.dataPlaneAuthenticationRequired = dataPlaneAuthenticationRequired;
        return this;
    }

    public String getDataPlaneBasicAuthenticationUsername() {
        return dataPlaneBasicAuthenticationUsername;
    }

    public ConfigurationDTO setDataPlaneBasicAuthenticationUsername(String dataPlaneBasicAuthenticationUsername) {
        this.dataPlaneBasicAuthenticationUsername = dataPlaneBasicAuthenticationUsername;
        return this;
    }

    @JsonIgnore
    public String getDataPlaneBasicAuthenticationPassword() {
        return dataPlaneBasicAuthenticationPassword;
    }

    @JsonProperty
    public ConfigurationDTO setDataPlaneBasicAuthenticationPassword(String dataPlaneBasicAuthenticationPassword) {
        this.dataPlaneBasicAuthenticationPassword = dataPlaneBasicAuthenticationPassword;
        return this;
    }

    public String getDataPlaneBasicAuthenticationRealm() {
        return dataPlaneBasicAuthenticationRealm;
    }

    public ConfigurationDTO setDataPlaneBasicAuthenticationRealm(String dataPlaneBasicAuthenticationRealm) {
        this.dataPlaneBasicAuthenticationRealm = dataPlaneBasicAuthenticationRealm;
        return this;
    }

    @JsonIgnore
    public String getDataPlaneBearerAuthenticationToken() {
        return dataPlaneBearerAuthenticationToken;
    }

    @JsonProperty
    public ConfigurationDTO setDataPlaneBearerAuthenticationToken(String dataPlaneBearerAuthenticationToken) {
        this.dataPlaneBearerAuthenticationToken = dataPlaneBearerAuthenticationToken;
        return this;
    }

    public String getDataPlaneApiKeyAuthenticationHeader() {
        return dataPlaneApiKeyAuthenticationHeader;
    }

    public ConfigurationDTO setDataPlaneApiKeyAuthenticationHeader(String dataPlaneApiKeyAuthenticationHeader) {
        this.dataPlaneApiKeyAuthenticationHeader = dataPlaneApiKeyAuthenticationHeader;
        return this;
    }

    @JsonIgnore
    public String getDataPlaneApiKeyAuthenticationValue() {
        return dataPlaneApiKeyAuthenticationValue;
    }

    @JsonProperty
    public ConfigurationDTO setDataPlaneApiKeyAuthenticationValue(String dataPlaneApiKeyAuthenticationValue) {
        this.dataPlaneApiKeyAuthenticationValue = dataPlaneApiKeyAuthenticationValue;
        return this;
    }

    public String getNoProxyHosts() {
        return noProxyHosts;
    }

    public ConfigurationDTO setNoProxyHosts(String noProxyHosts) {
        this.noProxyHosts = noProxyHosts;
        return this;
    }

    public String getProxyRemoteHost() {
        return proxyRemoteHost;
    }

    public ConfigurationDTO setProxyRemoteHost(String proxyRemoteHost) {
        this.proxyRemoteHost = proxyRemoteHost;
        return this;
    }

    public Integer getProxyRemotePort() {
        return proxyRemotePort;
    }

    public ConfigurationDTO setProxyRemotePort(Integer proxyRemotePort) {
        this.proxyRemotePort = proxyRemotePort;
        return this;
    }

    public java.util.List<org.mockserver.model.ProxyPassMapping> getProxyPassMappings() {
        return proxyPassMappings;
    }

    public ConfigurationDTO setProxyPassMappings(java.util.List<org.mockserver.model.ProxyPassMapping> proxyPassMappings) {
        this.proxyPassMappings = proxyPassMappings;
        return this;
    }

    public String getLivenessHttpGetPath() {
        return livenessHttpGetPath;
    }

    public ConfigurationDTO setLivenessHttpGetPath(String livenessHttpGetPath) {
        this.livenessHttpGetPath = livenessHttpGetPath;
        return this;
    }

    public String getMatchNamespaceHeader() {
        return matchNamespaceHeader;
    }

    public ConfigurationDTO setMatchNamespaceHeader(String matchNamespaceHeader) {
        this.matchNamespaceHeader = matchNamespaceHeader;
        return this;
    }

    public Boolean getControlPlaneTLSMutualAuthenticationRequired() {
        return controlPlaneTLSMutualAuthenticationRequired;
    }

    public ConfigurationDTO setControlPlaneTLSMutualAuthenticationRequired(Boolean controlPlaneTLSMutualAuthenticationRequired) {
        this.controlPlaneTLSMutualAuthenticationRequired = controlPlaneTLSMutualAuthenticationRequired;
        return this;
    }

    public String getControlPlaneTLSMutualAuthenticationCAChain() {
        return controlPlaneTLSMutualAuthenticationCAChain;
    }

    public ConfigurationDTO setControlPlaneTLSMutualAuthenticationCAChain(String controlPlaneTLSMutualAuthenticationCAChain) {
        this.controlPlaneTLSMutualAuthenticationCAChain = controlPlaneTLSMutualAuthenticationCAChain;
        return this;
    }

    public String getControlPlanePrivateKeyPath() {
        return controlPlanePrivateKeyPath;
    }

    public ConfigurationDTO setControlPlanePrivateKeyPath(String controlPlanePrivateKeyPath) {
        this.controlPlanePrivateKeyPath = controlPlanePrivateKeyPath;
        return this;
    }

    public String getControlPlaneX509CertificatePath() {
        return controlPlaneX509CertificatePath;
    }

    public ConfigurationDTO setControlPlaneX509CertificatePath(String controlPlaneX509CertificatePath) {
        this.controlPlaneX509CertificatePath = controlPlaneX509CertificatePath;
        return this;
    }

    public Boolean getControlPlaneJWTAuthenticationRequired() {
        return controlPlaneJWTAuthenticationRequired;
    }

    public ConfigurationDTO setControlPlaneJWTAuthenticationRequired(Boolean controlPlaneJWTAuthenticationRequired) {
        this.controlPlaneJWTAuthenticationRequired = controlPlaneJWTAuthenticationRequired;
        return this;
    }

    public String getControlPlaneJWTAuthenticationJWKSource() {
        return controlPlaneJWTAuthenticationJWKSource;
    }

    public ConfigurationDTO setControlPlaneJWTAuthenticationJWKSource(String controlPlaneJWTAuthenticationJWKSource) {
        this.controlPlaneJWTAuthenticationJWKSource = controlPlaneJWTAuthenticationJWKSource;
        return this;
    }

    public String getControlPlaneJWTAuthenticationExpectedAudience() {
        return controlPlaneJWTAuthenticationExpectedAudience;
    }

    public ConfigurationDTO setControlPlaneJWTAuthenticationExpectedAudience(String controlPlaneJWTAuthenticationExpectedAudience) {
        this.controlPlaneJWTAuthenticationExpectedAudience = controlPlaneJWTAuthenticationExpectedAudience;
        return this;
    }

    public Map<String, String> getControlPlaneJWTAuthenticationMatchingClaims() {
        return controlPlaneJWTAuthenticationMatchingClaims;
    }

    public ConfigurationDTO setControlPlaneJWTAuthenticationMatchingClaims(Map<String, String> controlPlaneJWTAuthenticationMatchingClaims) {
        this.controlPlaneJWTAuthenticationMatchingClaims = controlPlaneJWTAuthenticationMatchingClaims;
        return this;
    }

    public Set<String> getControlPlaneJWTAuthenticationRequiredClaims() {
        return controlPlaneJWTAuthenticationRequiredClaims;
    }

    public ConfigurationDTO setControlPlaneJWTAuthenticationRequiredClaims(Set<String> controlPlaneJWTAuthenticationRequiredClaims) {
        this.controlPlaneJWTAuthenticationRequiredClaims = controlPlaneJWTAuthenticationRequiredClaims;
        return this;
    }

    public Boolean getProactivelyInitialiseTLS() {
        return proactivelyInitialiseTLS;
    }

    public ConfigurationDTO setProactivelyInitialiseTLS(Boolean proactivelyInitialiseTLS) {
        this.proactivelyInitialiseTLS = proactivelyInitialiseTLS;
        return this;
    }

    public String getTlsProtocols() {
        return tlsProtocols;
    }

    public ConfigurationDTO setTlsProtocols(String tlsProtocols) {
        this.tlsProtocols = tlsProtocols;
        return this;
    }

    public Boolean getDynamicallyCreateCertificateAuthorityCertificate() {
        return dynamicallyCreateCertificateAuthorityCertificate;
    }

    public ConfigurationDTO setDynamicallyCreateCertificateAuthorityCertificate(Boolean dynamicallyCreateCertificateAuthorityCertificate) {
        this.dynamicallyCreateCertificateAuthorityCertificate = dynamicallyCreateCertificateAuthorityCertificate;
        return this;
    }

    public String getDirectoryToSaveDynamicSSLCertificate() {
        return directoryToSaveDynamicSSLCertificate;
    }

    public ConfigurationDTO setDirectoryToSaveDynamicSSLCertificate(String directoryToSaveDynamicSSLCertificate) {
        this.directoryToSaveDynamicSSLCertificate = directoryToSaveDynamicSSLCertificate;
        return this;
    }

    public Boolean getProxySetup() {
        return proxySetup;
    }

    public ConfigurationDTO setProxySetup(Boolean proxySetup) {
        this.proxySetup = proxySetup;
        return this;
    }

    public Boolean getProxySetupLogging() {
        return proxySetupLogging;
    }

    public ConfigurationDTO setProxySetupLogging(Boolean proxySetupLogging) {
        this.proxySetupLogging = proxySetupLogging;
        return this;
    }

    public Boolean getPreventCertificateDynamicUpdate() {
        return preventCertificateDynamicUpdate;
    }

    public ConfigurationDTO setPreventCertificateDynamicUpdate(Boolean preventCertificateDynamicUpdate) {
        this.preventCertificateDynamicUpdate = preventCertificateDynamicUpdate;
        return this;
    }

    public String getSslCertificateDomainName() {
        return sslCertificateDomainName;
    }

    public ConfigurationDTO setSslCertificateDomainName(String sslCertificateDomainName) {
        this.sslCertificateDomainName = sslCertificateDomainName;
        return this;
    }

    public Set<String> getSslSubjectAlternativeNameDomains() {
        return sslSubjectAlternativeNameDomains;
    }

    public ConfigurationDTO setSslSubjectAlternativeNameDomains(Set<String> sslSubjectAlternativeNameDomains) {
        this.sslSubjectAlternativeNameDomains = sslSubjectAlternativeNameDomains;
        return this;
    }

    public Set<String> getSslSubjectAlternativeNameIps() {
        return sslSubjectAlternativeNameIps;
    }

    public ConfigurationDTO setSslSubjectAlternativeNameIps(Set<String> sslSubjectAlternativeNameIps) {
        this.sslSubjectAlternativeNameIps = sslSubjectAlternativeNameIps;
        return this;
    }

    public Integer getMaxSubjectAlternativeNames() {
        return maxSubjectAlternativeNames;
    }

    public ConfigurationDTO setMaxSubjectAlternativeNames(Integer maxSubjectAlternativeNames) {
        this.maxSubjectAlternativeNames = maxSubjectAlternativeNames;
        return this;
    }

    public Integer getSslCertificateLeafValidityInDays() {
        return sslCertificateLeafValidityInDays;
    }

    public ConfigurationDTO setSslCertificateLeafValidityInDays(Integer sslCertificateLeafValidityInDays) {
        this.sslCertificateLeafValidityInDays = sslCertificateLeafValidityInDays;
        return this;
    }

    @JsonIgnore
    public String getCertificateAuthorityPrivateKey() {
        return certificateAuthorityPrivateKey;
    }

    @JsonProperty
    public ConfigurationDTO setCertificateAuthorityPrivateKey(String certificateAuthorityPrivateKey) {
        this.certificateAuthorityPrivateKey = certificateAuthorityPrivateKey;
        return this;
    }

    public String getCertificateAuthorityCertificate() {
        return certificateAuthorityCertificate;
    }

    public ConfigurationDTO setCertificateAuthorityCertificate(String certificateAuthorityCertificate) {
        this.certificateAuthorityCertificate = certificateAuthorityCertificate;
        return this;
    }

    public String getPrivateKeyPath() {
        return privateKeyPath;
    }

    public ConfigurationDTO setPrivateKeyPath(String privateKeyPath) {
        this.privateKeyPath = privateKeyPath;
        return this;
    }

    public String getX509CertificatePath() {
        return x509CertificatePath;
    }

    public ConfigurationDTO setX509CertificatePath(String x509CertificatePath) {
        this.x509CertificatePath = x509CertificatePath;
        return this;
    }

    public Boolean getTlsMutualAuthenticationRequired() {
        return tlsMutualAuthenticationRequired;
    }

    public ConfigurationDTO setTlsMutualAuthenticationRequired(Boolean tlsMutualAuthenticationRequired) {
        this.tlsMutualAuthenticationRequired = tlsMutualAuthenticationRequired;
        return this;
    }

    public String getTlsMutualAuthenticationCertificateChain() {
        return tlsMutualAuthenticationCertificateChain;
    }

    public ConfigurationDTO setTlsMutualAuthenticationCertificateChain(String tlsMutualAuthenticationCertificateChain) {
        this.tlsMutualAuthenticationCertificateChain = tlsMutualAuthenticationCertificateChain;
        return this;
    }

    public String getForwardProxyTLSX509CertificatesTrustManagerType() {
        return forwardProxyTLSX509CertificatesTrustManagerType;
    }

    public ConfigurationDTO setForwardProxyTLSX509CertificatesTrustManagerType(String forwardProxyTLSX509CertificatesTrustManagerType) {
        this.forwardProxyTLSX509CertificatesTrustManagerType = forwardProxyTLSX509CertificatesTrustManagerType;
        return this;
    }

    public String getForwardProxyTLSCustomTrustX509Certificates() {
        return forwardProxyTLSCustomTrustX509Certificates;
    }

    public ConfigurationDTO setForwardProxyTLSCustomTrustX509Certificates(String forwardProxyTLSCustomTrustX509Certificates) {
        this.forwardProxyTLSCustomTrustX509Certificates = forwardProxyTLSCustomTrustX509Certificates;
        return this;
    }

    @JsonIgnore
    public String getForwardProxyPrivateKey() {
        return forwardProxyPrivateKey;
    }

    @JsonProperty
    public ConfigurationDTO setForwardProxyPrivateKey(String forwardProxyPrivateKey) {
        this.forwardProxyPrivateKey = forwardProxyPrivateKey;
        return this;
    }

    public String getForwardProxyCertificateChain() {
        return forwardProxyCertificateChain;
    }

    public ConfigurationDTO setForwardProxyCertificateChain(String forwardProxyCertificateChain) {
        this.forwardProxyCertificateChain = forwardProxyCertificateChain;
        return this;
    }

    public String getForwardProxyClientCertificatesByHost() {
        return forwardProxyClientCertificatesByHost;
    }

    public ConfigurationDTO setForwardProxyClientCertificatesByHost(String forwardProxyClientCertificatesByHost) {
        this.forwardProxyClientCertificatesByHost = forwardProxyClientCertificatesByHost;
        return this;
    }

    public Long getSlowRequestThresholdMillis() {
        return slowRequestThresholdMillis;
    }

    public ConfigurationDTO setSlowRequestThresholdMillis(Long slowRequestThresholdMillis) {
        this.slowRequestThresholdMillis = slowRequestThresholdMillis;
        return this;
    }

    public Boolean getMetricsRequestDurationRouteLabels() {
        return metricsRequestDurationRouteLabels;
    }

    public ConfigurationDTO setMetricsRequestDurationRouteLabels(Boolean metricsRequestDurationRouteLabels) {
        this.metricsRequestDurationRouteLabels = metricsRequestDurationRouteLabels;
        return this;
    }

    public Integer getRateLimitMaxNamedQuotas() {
        return rateLimitMaxNamedQuotas;
    }

    public ConfigurationDTO setRateLimitMaxNamedQuotas(Integer rateLimitMaxNamedQuotas) {
        this.rateLimitMaxNamedQuotas = rateLimitMaxNamedQuotas;
        return this;
    }

    public Boolean getConnectionLifecycleChaosEnabled() {
        return connectionLifecycleChaosEnabled;
    }

    public ConfigurationDTO setConnectionLifecycleChaosEnabled(Boolean connectionLifecycleChaosEnabled) {
        this.connectionLifecycleChaosEnabled = connectionLifecycleChaosEnabled;
        return this;
    }

    public Long getPreemptionSimulationMaxDrainMillis() {
        return preemptionSimulationMaxDrainMillis;
    }

    public ConfigurationDTO setPreemptionSimulationMaxDrainMillis(Long preemptionSimulationMaxDrainMillis) {
        this.preemptionSimulationMaxDrainMillis = preemptionSimulationMaxDrainMillis;
        return this;
    }

    public Long getStopDrainMillis() {
        return stopDrainMillis;
    }

    public ConfigurationDTO setStopDrainMillis(Long stopDrainMillis) {
        this.stopDrainMillis = stopDrainMillis;
        return this;
    }

    public Boolean getConnectionLifecycleAutoHaltCountsRst() {
        return connectionLifecycleAutoHaltCountsRst;
    }

    public ConfigurationDTO setConnectionLifecycleAutoHaltCountsRst(Boolean connectionLifecycleAutoHaltCountsRst) {
        this.connectionLifecycleAutoHaltCountsRst = connectionLifecycleAutoHaltCountsRst;
        return this;
    }

    public Boolean getSloTrackingEnabled() {
        return sloTrackingEnabled;
    }

    public ConfigurationDTO setSloTrackingEnabled(Boolean sloTrackingEnabled) {
        this.sloTrackingEnabled = sloTrackingEnabled;
        return this;
    }

    public Long getSloWindowRetentionMillis() {
        return sloWindowRetentionMillis;
    }

    public ConfigurationDTO setSloWindowRetentionMillis(Long sloWindowRetentionMillis) {
        this.sloWindowRetentionMillis = sloWindowRetentionMillis;
        return this;
    }

    public Integer getSloWindowMaxSamples() {
        return sloWindowMaxSamples;
    }

    public ConfigurationDTO setSloWindowMaxSamples(Integer sloWindowMaxSamples) {
        this.sloWindowMaxSamples = sloWindowMaxSamples;
        return this;
    }

    public Boolean getLoadGenerationEnabled() {
        return loadGenerationEnabled;
    }

    public ConfigurationDTO setLoadGenerationEnabled(Boolean loadGenerationEnabled) {
        this.loadGenerationEnabled = loadGenerationEnabled;
        return this;
    }

    public Boolean getLoadGenerationSuppressEventLog() {
        return loadGenerationSuppressEventLog;
    }

    public ConfigurationDTO setLoadGenerationSuppressEventLog(Boolean loadGenerationSuppressEventLog) {
        this.loadGenerationSuppressEventLog = loadGenerationSuppressEventLog;
        return this;
    }

    public Integer getLoadGenerationMaxVirtualUsers() {
        return loadGenerationMaxVirtualUsers;
    }

    public ConfigurationDTO setLoadGenerationMaxVirtualUsers(Integer loadGenerationMaxVirtualUsers) {
        this.loadGenerationMaxVirtualUsers = loadGenerationMaxVirtualUsers;
        return this;
    }

    public Integer getLoadGenerationMaxInFlightRequests() {
        return loadGenerationMaxInFlightRequests;
    }

    public ConfigurationDTO setLoadGenerationMaxInFlightRequests(Integer loadGenerationMaxInFlightRequests) {
        this.loadGenerationMaxInFlightRequests = loadGenerationMaxInFlightRequests;
        return this;
    }

    public Integer getLoadGenerationMaxRequestsPerSecond() {
        return loadGenerationMaxRequestsPerSecond;
    }

    public ConfigurationDTO setLoadGenerationMaxRequestsPerSecond(Integer loadGenerationMaxRequestsPerSecond) {
        this.loadGenerationMaxRequestsPerSecond = loadGenerationMaxRequestsPerSecond;
        return this;
    }

    public Long getLoadGenerationMaxDurationMillis() {
        return loadGenerationMaxDurationMillis;
    }

    public ConfigurationDTO setLoadGenerationMaxDurationMillis(Long loadGenerationMaxDurationMillis) {
        this.loadGenerationMaxDurationMillis = loadGenerationMaxDurationMillis;
        return this;
    }

    public Integer getLoadGenerationMaxSteps() {
        return loadGenerationMaxSteps;
    }

    public ConfigurationDTO setLoadGenerationMaxSteps(Integer loadGenerationMaxSteps) {
        this.loadGenerationMaxSteps = loadGenerationMaxSteps;
        return this;
    }

    public Double getLoadGenerationMaxRate() {
        return loadGenerationMaxRate;
    }

    public ConfigurationDTO setLoadGenerationMaxRate(Double loadGenerationMaxRate) {
        this.loadGenerationMaxRate = loadGenerationMaxRate;
        return this;
    }

    public Integer getLoadGenerationMaxStages() {
        return loadGenerationMaxStages;
    }

    public ConfigurationDTO setLoadGenerationMaxStages(Integer loadGenerationMaxStages) {
        this.loadGenerationMaxStages = loadGenerationMaxStages;
        return this;
    }

    public Integer getLoadGenerationMaxConcurrentScenarios() {
        return loadGenerationMaxConcurrentScenarios;
    }

    public ConfigurationDTO setLoadGenerationMaxConcurrentScenarios(Integer loadGenerationMaxConcurrentScenarios) {
        this.loadGenerationMaxConcurrentScenarios = loadGenerationMaxConcurrentScenarios;
        return this;
    }

    public String getLoadScenarioInitializationJsonPath() {
        return loadScenarioInitializationJsonPath;
    }

    public ConfigurationDTO setLoadScenarioInitializationJsonPath(String loadScenarioInitializationJsonPath) {
        this.loadScenarioInitializationJsonPath = loadScenarioInitializationJsonPath;
        return this;
    }

    public java.util.List<String> getLoadGenerationMetricLabels() {
        return loadGenerationMetricLabels;
    }

    public ConfigurationDTO setLoadGenerationMetricLabels(java.util.List<String> loadGenerationMetricLabels) {
        this.loadGenerationMetricLabels = loadGenerationMetricLabels;
        return this;
    }

    public Boolean getLlmMetricsEnabled() {
        return llmMetricsEnabled;
    }

    public ConfigurationDTO setLlmMetricsEnabled(Boolean llmMetricsEnabled) {
        this.llmMetricsEnabled = llmMetricsEnabled;
        return this;
    }

    public Boolean getPerExpectationMetricsEnabled() {
        return perExpectationMetricsEnabled;
    }

    public ConfigurationDTO setPerExpectationMetricsEnabled(Boolean perExpectationMetricsEnabled) {
        this.perExpectationMetricsEnabled = perExpectationMetricsEnabled;
        return this;
    }

    public Boolean getDeduplicateRecordedExpectations() {
        return deduplicateRecordedExpectations;
    }

    public ConfigurationDTO setDeduplicateRecordedExpectations(Boolean deduplicateRecordedExpectations) {
        this.deduplicateRecordedExpectations = deduplicateRecordedExpectations;
        return this;
    }

    public Boolean getTemplatizeRecordedValues() {
        return templatizeRecordedValues;
    }

    public ConfigurationDTO setTemplatizeRecordedValues(Boolean templatizeRecordedValues) {
        this.templatizeRecordedValues = templatizeRecordedValues;
        return this;
    }

    public Boolean getRedactSecretsInRecordedExpectations() {
        return redactSecretsInRecordedExpectations;
    }

    public ConfigurationDTO setRedactSecretsInRecordedExpectations(Boolean redactSecretsInRecordedExpectations) {
        this.redactSecretsInRecordedExpectations = redactSecretsInRecordedExpectations;
        return this;
    }

    public Boolean getRedactSecretsInLog() {
        return redactSecretsInLog;
    }

    public ConfigurationDTO setRedactSecretsInLog(Boolean redactSecretsInLog) {
        this.redactSecretsInLog = redactSecretsInLog;
        return this;
    }

    public Double getLlmCostBudgetUsd() {
        return llmCostBudgetUsd;
    }

    public ConfigurationDTO setLlmCostBudgetUsd(Double llmCostBudgetUsd) {
        this.llmCostBudgetUsd = llmCostBudgetUsd;
        return this;
    }

    public Boolean getOtelPropagateTraceContext() {
        return otelPropagateTraceContext;
    }

    public ConfigurationDTO setOtelPropagateTraceContext(Boolean otelPropagateTraceContext) {
        this.otelPropagateTraceContext = otelPropagateTraceContext;
        return this;
    }

    public Boolean getOtelGenerateTraceId() {
        return otelGenerateTraceId;
    }

    public ConfigurationDTO setOtelGenerateTraceId(Boolean otelGenerateTraceId) {
        this.otelGenerateTraceId = otelGenerateTraceId;
        return this;
    }

    public Boolean getWasmEnabled() {
        return wasmEnabled;
    }

    public ConfigurationDTO setWasmEnabled(Boolean wasmEnabled) {
        this.wasmEnabled = wasmEnabled;
        return this;
    }

    public Integer getWasmMaxMemoryPages() {
        return wasmMaxMemoryPages;
    }

    public ConfigurationDTO setWasmMaxMemoryPages(Integer wasmMaxMemoryPages) {
        this.wasmMaxMemoryPages = wasmMaxMemoryPages;
        return this;
    }

    public Long getWasmExecutionTimeoutMillis() {
        return wasmExecutionTimeoutMillis;
    }

    public ConfigurationDTO setWasmExecutionTimeoutMillis(Long wasmExecutionTimeoutMillis) {
        this.wasmExecutionTimeoutMillis = wasmExecutionTimeoutMillis;
        return this;
    }

    public String getGrpcDescriptorDirectory() {
        return grpcDescriptorDirectory;
    }

    public ConfigurationDTO setGrpcDescriptorDirectory(String grpcDescriptorDirectory) {
        this.grpcDescriptorDirectory = grpcDescriptorDirectory;
        return this;
    }

    public String getGrpcProtoDirectory() {
        return grpcProtoDirectory;
    }

    public ConfigurationDTO setGrpcProtoDirectory(String grpcProtoDirectory) {
        this.grpcProtoDirectory = grpcProtoDirectory;
        return this;
    }

    public Boolean getGrpcEnabled() {
        return grpcEnabled;
    }

    public ConfigurationDTO setGrpcEnabled(Boolean grpcEnabled) {
        this.grpcEnabled = grpcEnabled;
        return this;
    }

    public String getGrpcProtocPath() {
        return grpcProtocPath;
    }

    public ConfigurationDTO setGrpcProtocPath(String grpcProtocPath) {
        this.grpcProtocPath = grpcProtocPath;
        return this;
    }

    public Boolean getGrpcBidiStreamingEnabled() {
        return grpcBidiStreamingEnabled;
    }

    public ConfigurationDTO setGrpcBidiStreamingEnabled(Boolean grpcBidiStreamingEnabled) {
        this.grpcBidiStreamingEnabled = grpcBidiStreamingEnabled;
        return this;
    }

    public Boolean getDnsEnabled() {
        return dnsEnabled;
    }

    public ConfigurationDTO setDnsEnabled(Boolean dnsEnabled) {
        this.dnsEnabled = dnsEnabled;
        return this;
    }

    public Integer getDnsPort() {
        return dnsPort;
    }

    public ConfigurationDTO setDnsPort(Integer dnsPort) {
        this.dnsPort = dnsPort;
        return this;
    }

    public Integer getHttp3Port() {
        return http3Port;
    }

    public ConfigurationDTO setHttp3Port(Integer http3Port) {
        this.http3Port = http3Port;
        return this;
    }

    public Long getHttp3MaxIdleTimeout() {
        return http3MaxIdleTimeout;
    }

    public ConfigurationDTO setHttp3MaxIdleTimeout(Long http3MaxIdleTimeout) {
        this.http3MaxIdleTimeout = http3MaxIdleTimeout;
        return this;
    }

    public Long getHttp3InitialMaxData() {
        return http3InitialMaxData;
    }

    public ConfigurationDTO setHttp3InitialMaxData(Long http3InitialMaxData) {
        this.http3InitialMaxData = http3InitialMaxData;
        return this;
    }

    public Long getHttp3InitialMaxStreamDataBidirectional() {
        return http3InitialMaxStreamDataBidirectional;
    }

    public ConfigurationDTO setHttp3InitialMaxStreamDataBidirectional(Long http3InitialMaxStreamDataBidirectional) {
        this.http3InitialMaxStreamDataBidirectional = http3InitialMaxStreamDataBidirectional;
        return this;
    }

    public Long getHttp3InitialMaxStreamsBidirectional() {
        return http3InitialMaxStreamsBidirectional;
    }

    public ConfigurationDTO setHttp3InitialMaxStreamsBidirectional(Long http3InitialMaxStreamsBidirectional) {
        this.http3InitialMaxStreamsBidirectional = http3InitialMaxStreamsBidirectional;
        return this;
    }

    public Long getHttp3QpackMaxTableCapacity() {
        return http3QpackMaxTableCapacity;
    }

    public ConfigurationDTO setHttp3QpackMaxTableCapacity(Long http3QpackMaxTableCapacity) {
        this.http3QpackMaxTableCapacity = http3QpackMaxTableCapacity;
        return this;
    }

    public Boolean getHttp3ConnectUdpEnabled() {
        return http3ConnectUdpEnabled;
    }

    public ConfigurationDTO setHttp3ConnectUdpEnabled(Boolean http3ConnectUdpEnabled) {
        this.http3ConnectUdpEnabled = http3ConnectUdpEnabled;
        return this;
    }

    public String getHttp3ConnectUdpAllowedTargets() {
        return http3ConnectUdpAllowedTargets;
    }

    public ConfigurationDTO setHttp3ConnectUdpAllowedTargets(String http3ConnectUdpAllowedTargets) {
        this.http3ConnectUdpAllowedTargets = http3ConnectUdpAllowedTargets;
        return this;
    }

    public Long getHttp3AltSvcMaxAge() {
        return http3AltSvcMaxAge;
    }

    public ConfigurationDTO setHttp3AltSvcMaxAge(Long http3AltSvcMaxAge) {
        this.http3AltSvcMaxAge = http3AltSvcMaxAge;
        return this;
    }

    public Boolean getHttp3AdvertiseAltSvc() {
        return http3AdvertiseAltSvc;
    }

    public ConfigurationDTO setHttp3AdvertiseAltSvc(Boolean http3AdvertiseAltSvc) {
        this.http3AdvertiseAltSvc = http3AdvertiseAltSvc;
        return this;
    }

    public Boolean getUseNativeTransport() {
        return useNativeTransport;
    }

    public ConfigurationDTO setUseNativeTransport(Boolean useNativeTransport) {
        this.useNativeTransport = useNativeTransport;
        return this;
    }

    public Boolean getForwardConnectionPoolEnabled() {
        return forwardConnectionPoolEnabled;
    }

    public ConfigurationDTO setForwardConnectionPoolEnabled(Boolean forwardConnectionPoolEnabled) {
        this.forwardConnectionPoolEnabled = forwardConnectionPoolEnabled;
        return this;
    }

    public Integer getForwardConnectionPoolMaxIdlePerKey() {
        return forwardConnectionPoolMaxIdlePerKey;
    }

    public ConfigurationDTO setForwardConnectionPoolMaxIdlePerKey(Integer forwardConnectionPoolMaxIdlePerKey) {
        this.forwardConnectionPoolMaxIdlePerKey = forwardConnectionPoolMaxIdlePerKey;
        return this;
    }

    public Long getForwardConnectionPoolIdleTimeoutMillis() {
        return forwardConnectionPoolIdleTimeoutMillis;
    }

    public ConfigurationDTO setForwardConnectionPoolIdleTimeoutMillis(Long forwardConnectionPoolIdleTimeoutMillis) {
        this.forwardConnectionPoolIdleTimeoutMillis = forwardConnectionPoolIdleTimeoutMillis;
        return this;
    }

    public Boolean getForwardConnectionPoolKeepAlive() {
        return forwardConnectionPoolKeepAlive;
    }

    public ConfigurationDTO setForwardConnectionPoolKeepAlive(Boolean forwardConnectionPoolKeepAlive) {
        this.forwardConnectionPoolKeepAlive = forwardConnectionPoolKeepAlive;
        return this;
    }

    public Integer getForwardConnectionPoolMaxTotalPerKey() {
        return forwardConnectionPoolMaxTotalPerKey;
    }

    public ConfigurationDTO setForwardConnectionPoolMaxTotalPerKey(Integer forwardConnectionPoolMaxTotalPerKey) {
        this.forwardConnectionPoolMaxTotalPerKey = forwardConnectionPoolMaxTotalPerKey;
        return this;
    }

    public Boolean getForwardSocketKeepAlive() {
        return forwardSocketKeepAlive;
    }

    public ConfigurationDTO setForwardSocketKeepAlive(Boolean forwardSocketKeepAlive) {
        this.forwardSocketKeepAlive = forwardSocketKeepAlive;
        return this;
    }

    public Integer getForwardSocketKeepAliveIdleSeconds() {
        return forwardSocketKeepAliveIdleSeconds;
    }

    public ConfigurationDTO setForwardSocketKeepAliveIdleSeconds(Integer forwardSocketKeepAliveIdleSeconds) {
        this.forwardSocketKeepAliveIdleSeconds = forwardSocketKeepAliveIdleSeconds;
        return this;
    }

    public Integer getForwardSocketKeepAliveIntervalSeconds() {
        return forwardSocketKeepAliveIntervalSeconds;
    }

    public ConfigurationDTO setForwardSocketKeepAliveIntervalSeconds(Integer forwardSocketKeepAliveIntervalSeconds) {
        this.forwardSocketKeepAliveIntervalSeconds = forwardSocketKeepAliveIntervalSeconds;
        return this;
    }

    public Integer getForwardSocketKeepAliveCount() {
        return forwardSocketKeepAliveCount;
    }

    public ConfigurationDTO setForwardSocketKeepAliveCount(Integer forwardSocketKeepAliveCount) {
        this.forwardSocketKeepAliveCount = forwardSocketKeepAliveCount;
        return this;
    }

    public Integer getForwardProxyRetryCount() {
        return forwardProxyRetryCount;
    }

    public ConfigurationDTO setForwardProxyRetryCount(Integer forwardProxyRetryCount) {
        this.forwardProxyRetryCount = forwardProxyRetryCount;
        return this;
    }

    public Long getForwardProxyRetryBackoffMillis() {
        return forwardProxyRetryBackoffMillis;
    }

    public ConfigurationDTO setForwardProxyRetryBackoffMillis(Long forwardProxyRetryBackoffMillis) {
        this.forwardProxyRetryBackoffMillis = forwardProxyRetryBackoffMillis;
        return this;
    }

    public Boolean getForwardProxyHttp2Enabled() {
        return forwardProxyHttp2Enabled;
    }

    public ConfigurationDTO setForwardProxyHttp2Enabled(Boolean forwardProxyHttp2Enabled) {
        this.forwardProxyHttp2Enabled = forwardProxyHttp2Enabled;
        return this;
    }

    public Boolean getForwardProxyHttp2Upgrade() {
        return forwardProxyHttp2Upgrade;
    }

    public ConfigurationDTO setForwardProxyHttp2Upgrade(Boolean forwardProxyHttp2Upgrade) {
        this.forwardProxyHttp2Upgrade = forwardProxyHttp2Upgrade;
        return this;
    }

    public Boolean getForwardProxyCircuitBreakerEnabled() {
        return forwardProxyCircuitBreakerEnabled;
    }

    public ConfigurationDTO setForwardProxyCircuitBreakerEnabled(Boolean forwardProxyCircuitBreakerEnabled) {
        this.forwardProxyCircuitBreakerEnabled = forwardProxyCircuitBreakerEnabled;
        return this;
    }

    public Integer getForwardProxyCircuitBreakerFailureThreshold() {
        return forwardProxyCircuitBreakerFailureThreshold;
    }

    public ConfigurationDTO setForwardProxyCircuitBreakerFailureThreshold(Integer forwardProxyCircuitBreakerFailureThreshold) {
        this.forwardProxyCircuitBreakerFailureThreshold = forwardProxyCircuitBreakerFailureThreshold;
        return this;
    }

    public Long getForwardProxyCircuitBreakerWindowMillis() {
        return forwardProxyCircuitBreakerWindowMillis;
    }

    public ConfigurationDTO setForwardProxyCircuitBreakerWindowMillis(Long forwardProxyCircuitBreakerWindowMillis) {
        this.forwardProxyCircuitBreakerWindowMillis = forwardProxyCircuitBreakerWindowMillis;
        return this;
    }

    public Boolean getEnforceResponseValidationForMocks() {
        return enforceResponseValidationForMocks;
    }

    public ConfigurationDTO setEnforceResponseValidationForMocks(Boolean enforceResponseValidationForMocks) {
        this.enforceResponseValidationForMocks = enforceResponseValidationForMocks;
        return this;
    }

    public Integer getMaxRequestBodySize() {
        return maxRequestBodySize;
    }

    public Integer getMaxGrpcMessageSize() {
        return maxGrpcMessageSize;
    }

    public ConfigurationDTO setMaxGrpcMessageSize(Integer maxGrpcMessageSize) {
        this.maxGrpcMessageSize = maxGrpcMessageSize;
        return this;
    }

    public ConfigurationDTO setMaxRequestBodySize(Integer maxRequestBodySize) {
        this.maxRequestBodySize = maxRequestBodySize;
        return this;
    }

    public Integer getMaxResponseBodySize() {
        return maxResponseBodySize;
    }

    public ConfigurationDTO setMaxResponseBodySize(Integer maxResponseBodySize) {
        this.maxResponseBodySize = maxResponseBodySize;
        return this;
    }

    public Integer getMaxLlmConversationBodySize() {
        return maxLlmConversationBodySize;
    }

    public ConfigurationDTO setMaxLlmConversationBodySize(Integer maxLlmConversationBodySize) {
        this.maxLlmConversationBodySize = maxLlmConversationBodySize;
        return this;
    }

    public Boolean getDriftDetectionEnabled() {
        return driftDetectionEnabled;
    }

    public ConfigurationDTO setDriftDetectionEnabled(Boolean driftDetectionEnabled) {
        this.driftDetectionEnabled = driftDetectionEnabled;
        return this;
    }

    public Double getDriftSampleRate() {
        return driftSampleRate;
    }

    public ConfigurationDTO setDriftSampleRate(Double driftSampleRate) {
        this.driftSampleRate = driftSampleRate;
        return this;
    }

    public Boolean getDriftSemanticAnalysisEnabled() {
        return driftSemanticAnalysisEnabled;
    }

    public ConfigurationDTO setDriftSemanticAnalysisEnabled(Boolean driftSemanticAnalysisEnabled) {
        this.driftSemanticAnalysisEnabled = driftSemanticAnalysisEnabled;
        return this;
    }

    public Long getDriftResponseTimeThresholdMs() {
        return driftResponseTimeThresholdMs;
    }

    public ConfigurationDTO setDriftResponseTimeThresholdMs(Long driftResponseTimeThresholdMs) {
        this.driftResponseTimeThresholdMs = driftResponseTimeThresholdMs;
        return this;
    }

    public Boolean getDriftAlertWebhookEnabled() {
        return driftAlertWebhookEnabled;
    }

    public ConfigurationDTO setDriftAlertWebhookEnabled(Boolean driftAlertWebhookEnabled) {
        this.driftAlertWebhookEnabled = driftAlertWebhookEnabled;
        return this;
    }

    public String getDriftAlertWebhookUrl() {
        return driftAlertWebhookUrl;
    }

    public ConfigurationDTO setDriftAlertWebhookUrl(String driftAlertWebhookUrl) {
        this.driftAlertWebhookUrl = driftAlertWebhookUrl;
        return this;
    }

    public String getDriftAlertSeverityThreshold() {
        return driftAlertSeverityThreshold;
    }

    public ConfigurationDTO setDriftAlertSeverityThreshold(String driftAlertSeverityThreshold) {
        this.driftAlertSeverityThreshold = driftAlertSeverityThreshold;
        return this;
    }

    public Long getDriftAlertCooldownMillis() {
        return driftAlertCooldownMillis;
    }

    public ConfigurationDTO setDriftAlertCooldownMillis(Long driftAlertCooldownMillis) {
        this.driftAlertCooldownMillis = driftAlertCooldownMillis;
        return this;
    }

    public Boolean getControlPlaneAuditEnabled() {
        return controlPlaneAuditEnabled;
    }

    public ConfigurationDTO setControlPlaneAuditEnabled(Boolean controlPlaneAuditEnabled) {
        this.controlPlaneAuditEnabled = controlPlaneAuditEnabled;
        return this;
    }

    public Integer getControlPlaneAuditMaxEntries() {
        return controlPlaneAuditMaxEntries;
    }

    public ConfigurationDTO setControlPlaneAuditMaxEntries(Integer controlPlaneAuditMaxEntries) {
        this.controlPlaneAuditMaxEntries = controlPlaneAuditMaxEntries;
        return this;
    }

    public Boolean getControlPlaneAuditReads() {
        return controlPlaneAuditReads;
    }

    public ConfigurationDTO setControlPlaneAuditReads(Boolean controlPlaneAuditReads) {
        this.controlPlaneAuditReads = controlPlaneAuditReads;
        return this;
    }

    public Boolean getHttp2Enabled() {
        return http2Enabled;
    }

    public ConfigurationDTO setHttp2Enabled(Boolean http2Enabled) {
        this.http2Enabled = http2Enabled;
        return this;
    }

    public Boolean getStreamingResponsesEnabled() {
        return streamingResponsesEnabled;
    }

    public ConfigurationDTO setStreamingResponsesEnabled(Boolean streamingResponsesEnabled) {
        this.streamingResponsesEnabled = streamingResponsesEnabled;
        return this;
    }

    public Integer getMaxStreamingCaptureBytes() {
        return maxStreamingCaptureBytes;
    }

    public ConfigurationDTO setMaxStreamingCaptureBytes(Integer maxStreamingCaptureBytes) {
        this.maxStreamingCaptureBytes = maxStreamingCaptureBytes;
        return this;
    }

    public Integer getStreamIdleTimeoutSeconds() {
        return streamIdleTimeoutSeconds;
    }

    public ConfigurationDTO setStreamIdleTimeoutSeconds(Integer streamIdleTimeoutSeconds) {
        this.streamIdleTimeoutSeconds = streamIdleTimeoutSeconds;
        return this;
    }

    public Boolean getValidateRequestsAgainstOpenApiSpec() {
        return validateRequestsAgainstOpenApiSpec;
    }

    public ConfigurationDTO setValidateRequestsAgainstOpenApiSpec(Boolean validateRequestsAgainstOpenApiSpec) {
        this.validateRequestsAgainstOpenApiSpec = validateRequestsAgainstOpenApiSpec;
        return this;
    }

    public Boolean getDetailedVerificationFailures() {
        return detailedVerificationFailures;
    }

    public ConfigurationDTO setDetailedVerificationFailures(Boolean detailedVerificationFailures) {
        this.detailedVerificationFailures = detailedVerificationFailures;
        return this;
    }

    public Boolean getFailVerificationOnEvictedLog() {
        return failVerificationOnEvictedLog;
    }

    public ConfigurationDTO setFailVerificationOnEvictedLog(Boolean failVerificationOnEvictedLog) {
        this.failVerificationOnEvictedLog = failVerificationOnEvictedLog;
        return this;
    }

    public Long getGlobalResponseDelayMillis() {
        return globalResponseDelayMillis;
    }

    public ConfigurationDTO setGlobalResponseDelayMillis(Long globalResponseDelayMillis) {
        this.globalResponseDelayMillis = globalResponseDelayMillis;
        return this;
    }

    public Boolean getForwardAdjustHostHeader() {
        return forwardAdjustHostHeader;
    }

    public ConfigurationDTO setForwardAdjustHostHeader(Boolean forwardAdjustHostHeader) {
        this.forwardAdjustHostHeader = forwardAdjustHostHeader;
        return this;
    }

    public String getForwardDefaultHostHeader() {
        return forwardDefaultHostHeader;
    }

    public ConfigurationDTO setForwardDefaultHostHeader(String forwardDefaultHostHeader) {
        this.forwardDefaultHostHeader = forwardDefaultHostHeader;
        return this;
    }

    public Boolean getForwardProxyBlockPrivateNetworks() {
        return forwardProxyBlockPrivateNetworks;
    }

    public ConfigurationDTO setForwardProxyBlockPrivateNetworks(Boolean forwardProxyBlockPrivateNetworks) {
        this.forwardProxyBlockPrivateNetworks = forwardProxyBlockPrivateNetworks;
        return this;
    }

    public Boolean getForwardProxyTLSHostnameVerificationEnabled() {
        return forwardProxyTLSHostnameVerificationEnabled;
    }

    public ConfigurationDTO setForwardProxyTLSHostnameVerificationEnabled(Boolean forwardProxyTLSHostnameVerificationEnabled) {
        this.forwardProxyTLSHostnameVerificationEnabled = forwardProxyTLSHostnameVerificationEnabled;
        return this;
    }

    public Boolean getTlsAllowInsecureProtocols() {
        return tlsAllowInsecureProtocols;
    }

    public ConfigurationDTO setTlsAllowInsecureProtocols(Boolean tlsAllowInsecureProtocols) {
        this.tlsAllowInsecureProtocols = tlsAllowInsecureProtocols;
        return this;
    }

    public String getStateBackend() {
        return stateBackend;
    }

    public ConfigurationDTO setStateBackend(String stateBackend) {
        this.stateBackend = stateBackend;
        return this;
    }

    public String getBlobStoreType() {
        return blobStoreType;
    }

    public ConfigurationDTO setBlobStoreType(String blobStoreType) {
        this.blobStoreType = blobStoreType;
        return this;
    }

    public String getBlobStoreBucket() {
        return blobStoreBucket;
    }

    public ConfigurationDTO setBlobStoreBucket(String blobStoreBucket) {
        this.blobStoreBucket = blobStoreBucket;
        return this;
    }

    public String getBlobStoreRegion() {
        return blobStoreRegion;
    }

    public ConfigurationDTO setBlobStoreRegion(String blobStoreRegion) {
        this.blobStoreRegion = blobStoreRegion;
        return this;
    }

    public String getBlobStoreEndpoint() {
        return blobStoreEndpoint;
    }

    public ConfigurationDTO setBlobStoreEndpoint(String blobStoreEndpoint) {
        this.blobStoreEndpoint = blobStoreEndpoint;
        return this;
    }

    public String getBlobStoreKeyPrefix() {
        return blobStoreKeyPrefix;
    }

    public ConfigurationDTO setBlobStoreKeyPrefix(String blobStoreKeyPrefix) {
        this.blobStoreKeyPrefix = blobStoreKeyPrefix;
        return this;
    }

    @JsonIgnore
    public String getBlobStoreAccessKeyId() {
        return blobStoreAccessKeyId;
    }

    @JsonProperty
    public ConfigurationDTO setBlobStoreAccessKeyId(String blobStoreAccessKeyId) {
        this.blobStoreAccessKeyId = blobStoreAccessKeyId;
        return this;
    }

    @JsonIgnore
    public String getBlobStoreSecretAccessKey() {
        return blobStoreSecretAccessKey;
    }

    @JsonProperty
    public ConfigurationDTO setBlobStoreSecretAccessKey(String blobStoreSecretAccessKey) {
        this.blobStoreSecretAccessKey = blobStoreSecretAccessKey;
        return this;
    }

    public String getBlobStoreContainer() {
        return blobStoreContainer;
    }

    public ConfigurationDTO setBlobStoreContainer(String blobStoreContainer) {
        this.blobStoreContainer = blobStoreContainer;
        return this;
    }

    @JsonIgnore
    public String getBlobStoreConnectionString() {
        return blobStoreConnectionString;
    }

    @JsonProperty
    public ConfigurationDTO setBlobStoreConnectionString(String blobStoreConnectionString) {
        this.blobStoreConnectionString = blobStoreConnectionString;
        return this;
    }

    public String getBlobStoreProjectId() {
        return blobStoreProjectId;
    }

    public ConfigurationDTO setBlobStoreProjectId(String blobStoreProjectId) {
        this.blobStoreProjectId = blobStoreProjectId;
        return this;
    }

    public Integer getBlobStoreRestoreTimeoutSeconds() {
        return blobStoreRestoreTimeoutSeconds;
    }

    public ConfigurationDTO setBlobStoreRestoreTimeoutSeconds(Integer blobStoreRestoreTimeoutSeconds) {
        this.blobStoreRestoreTimeoutSeconds = blobStoreRestoreTimeoutSeconds;
        return this;
    }

    public Boolean getClusterEnabled() {
        return clusterEnabled;
    }

    public ConfigurationDTO setClusterEnabled(Boolean clusterEnabled) {
        this.clusterEnabled = clusterEnabled;
        return this;
    }

    public String getClusterName() {
        return clusterName;
    }

    public ConfigurationDTO setClusterName(String clusterName) {
        this.clusterName = clusterName;
        return this;
    }

    public String getClusterTransportConfig() {
        return clusterTransportConfig;
    }

    public ConfigurationDTO setClusterTransportConfig(String clusterTransportConfig) {
        this.clusterTransportConfig = clusterTransportConfig;
        return this;
    }

    public Boolean getClusterVerifyFanIn() {
        return clusterVerifyFanIn;
    }

    public ConfigurationDTO setClusterVerifyFanIn(Boolean clusterVerifyFanIn) {
        this.clusterVerifyFanIn = clusterVerifyFanIn;
        return this;
    }

    public String getClusterVerifyFanInPeers() {
        return clusterVerifyFanInPeers;
    }

    public ConfigurationDTO setClusterVerifyFanInPeers(String clusterVerifyFanInPeers) {
        this.clusterVerifyFanInPeers = clusterVerifyFanInPeers;
        return this;
    }

    /**
     * WRITE-ONLY: the credential each node presents to its peers on cross-node fan-in queries, sent
     * verbatim as the control-plane {@code Authorization} header — so disclosing it hands any reader of
     * {@code GET /mockserver/configuration} a token granting MUTATE access to every node in the
     * cluster. @JsonIgnore keeps it off the response entirely (the @JsonProperty setter keeps it
     * settable over the control plane), which also makes a GET-then-PUT round trip safe: the property
     * is absent from the body a client echoes back, and applyTo() leaves an absent value alone. Same
     * pattern as dataPlaneBearerAuthenticationToken and the other credentials in this DTO.
     */
    @JsonIgnore
    public String getClusterFanInPeerAuthToken() {
        return clusterFanInPeerAuthToken;
    }

    @JsonProperty
    public ConfigurationDTO setClusterFanInPeerAuthToken(String clusterFanInPeerAuthToken) {
        this.clusterFanInPeerAuthToken = clusterFanInPeerAuthToken;
        return this;
    }

    public Boolean getClusterSharedTimesEnabled() {
        return clusterSharedTimesEnabled;
    }

    public ConfigurationDTO setClusterSharedTimesEnabled(Boolean clusterSharedTimesEnabled) {
        this.clusterSharedTimesEnabled = clusterSharedTimesEnabled;
        return this;
    }

    public Boolean getControlPlaneOidcAuthenticationRequired() {
        return controlPlaneOidcAuthenticationRequired;
    }

    public ConfigurationDTO setControlPlaneOidcAuthenticationRequired(Boolean controlPlaneOidcAuthenticationRequired) {
        this.controlPlaneOidcAuthenticationRequired = controlPlaneOidcAuthenticationRequired;
        return this;
    }

    public String getControlPlaneOidcIssuer() {
        return controlPlaneOidcIssuer;
    }

    public ConfigurationDTO setControlPlaneOidcIssuer(String controlPlaneOidcIssuer) {
        this.controlPlaneOidcIssuer = controlPlaneOidcIssuer;
        return this;
    }

    public String getControlPlaneOidcJwksUri() {
        return controlPlaneOidcJwksUri;
    }

    public ConfigurationDTO setControlPlaneOidcJwksUri(String controlPlaneOidcJwksUri) {
        this.controlPlaneOidcJwksUri = controlPlaneOidcJwksUri;
        return this;
    }

    public String getControlPlaneOidcAudience() {
        return controlPlaneOidcAudience;
    }

    public ConfigurationDTO setControlPlaneOidcAudience(String controlPlaneOidcAudience) {
        this.controlPlaneOidcAudience = controlPlaneOidcAudience;
        return this;
    }

    public Set<String> getControlPlaneOidcRequiredScopes() {
        return controlPlaneOidcRequiredScopes;
    }

    public ConfigurationDTO setControlPlaneOidcRequiredScopes(Set<String> controlPlaneOidcRequiredScopes) {
        this.controlPlaneOidcRequiredScopes = controlPlaneOidcRequiredScopes;
        return this;
    }

    public String getControlPlaneOidcScopeClaim() {
        return controlPlaneOidcScopeClaim;
    }

    public ConfigurationDTO setControlPlaneOidcScopeClaim(String controlPlaneOidcScopeClaim) {
        this.controlPlaneOidcScopeClaim = controlPlaneOidcScopeClaim;
        return this;
    }

    public Boolean getControlPlaneAuthorizationEnabled() {
        return controlPlaneAuthorizationEnabled;
    }

    public ConfigurationDTO setControlPlaneAuthorizationEnabled(Boolean controlPlaneAuthorizationEnabled) {
        this.controlPlaneAuthorizationEnabled = controlPlaneAuthorizationEnabled;
        return this;
    }

    public Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> getControlPlaneScopeMapping() {
        return controlPlaneScopeMapping;
    }

    public ConfigurationDTO setControlPlaneScopeMapping(Map<String, org.mockserver.authentication.authorization.ControlPlaneRole> controlPlaneScopeMapping) {
        this.controlPlaneScopeMapping = controlPlaneScopeMapping;
        return this;
    }

    public Boolean getTransparentProxyEnabled() {
        return transparentProxyEnabled;
    }

    public ConfigurationDTO setTransparentProxyEnabled(Boolean transparentProxyEnabled) {
        this.transparentProxyEnabled = transparentProxyEnabled;
        return this;
    }

    public Boolean getTransparentProxyTproxy() {
        return transparentProxyTproxy;
    }

    public ConfigurationDTO setTransparentProxyTproxy(Boolean transparentProxyTproxy) {
        this.transparentProxyTproxy = transparentProxyTproxy;
        return this;
    }

    public Boolean getTransparentProxyEbpf() {
        return transparentProxyEbpf;
    }

    public ConfigurationDTO setTransparentProxyEbpf(Boolean transparentProxyEbpf) {
        this.transparentProxyEbpf = transparentProxyEbpf;
        return this;
    }

    public String getTransparentProxyEbpfMapPath() {
        return transparentProxyEbpfMapPath;
    }

    public ConfigurationDTO setTransparentProxyEbpfMapPath(String transparentProxyEbpfMapPath) {
        this.transparentProxyEbpfMapPath = transparentProxyEbpfMapPath;
        return this;
    }

    public String getAsyncKafkaBootstrapServers() {
        return asyncKafkaBootstrapServers;
    }

    public ConfigurationDTO setAsyncKafkaBootstrapServers(String asyncKafkaBootstrapServers) {
        this.asyncKafkaBootstrapServers = asyncKafkaBootstrapServers;
        return this;
    }

    public String getAsyncMqttBrokerUrl() {
        return asyncMqttBrokerUrl;
    }

    public ConfigurationDTO setAsyncMqttBrokerUrl(String asyncMqttBrokerUrl) {
        this.asyncMqttBrokerUrl = asyncMqttBrokerUrl;
        return this;
    }

    public String getAsyncAmqpUri() {
        return asyncAmqpUri;
    }

    public ConfigurationDTO setAsyncAmqpUri(String asyncAmqpUri) {
        this.asyncAmqpUri = asyncAmqpUri;
        return this;
    }

    public Integer getAsyncRecordedMessageMaxEntries() {
        return asyncRecordedMessageMaxEntries;
    }

    public ConfigurationDTO setAsyncRecordedMessageMaxEntries(Integer asyncRecordedMessageMaxEntries) {
        this.asyncRecordedMessageMaxEntries = asyncRecordedMessageMaxEntries;
        return this;
    }

    public String getLlmProvider() {
        return llmProvider;
    }

    public ConfigurationDTO setLlmProvider(String llmProvider) {
        this.llmProvider = llmProvider;
        return this;
    }

    public String getLlmModel() {
        return llmModel;
    }

    public ConfigurationDTO setLlmModel(String llmModel) {
        this.llmModel = llmModel;
        return this;
    }

    public String getLlmBaseUrl() {
        return llmBaseUrl;
    }

    public ConfigurationDTO setLlmBaseUrl(String llmBaseUrl) {
        this.llmBaseUrl = llmBaseUrl;
        return this;
    }

    /**
     * Documented shape is a PATH to a backends JSON file, which holds no secret and is returned
     * unchanged. A value that is itself a JSON document has each credential-bearing field redacted —
     * by the same {@link ConfigurationProperties#redactSensitiveValue(String, String)} rule every
     * other disclosing surface uses.
     */
    public String getLlmBackendsConfig() {
        return ConfigurationProperties.redactSensitiveValue("llmBackendsConfig", llmBackendsConfig);
    }

    @JsonIgnore
    public String getLlmBackendsConfigRawValue() {
        return llmBackendsConfig;
    }

    public ConfigurationDTO setLlmBackendsConfig(String llmBackendsConfig) {
        this.llmBackendsConfig = llmBackendsConfig;
        return this;
    }

    public Long getLlmRequestTimeoutMillis() {
        return llmRequestTimeoutMillis;
    }

    public ConfigurationDTO setLlmRequestTimeoutMillis(Long llmRequestTimeoutMillis) {
        this.llmRequestTimeoutMillis = llmRequestTimeoutMillis;
        return this;
    }

    public Boolean getLlmSemanticMatchingEnabled() {
        return llmSemanticMatchingEnabled;
    }

    public ConfigurationDTO setLlmSemanticMatchingEnabled(Boolean llmSemanticMatchingEnabled) {
        this.llmSemanticMatchingEnabled = llmSemanticMatchingEnabled;
        return this;
    }

    public Boolean getLlmInferUsageEnabled() {
        return llmInferUsageEnabled;
    }

    public ConfigurationDTO setLlmInferUsageEnabled(Boolean llmInferUsageEnabled) {
        this.llmInferUsageEnabled = llmInferUsageEnabled;
        return this;
    }

    public Boolean getLlmVcrStrict() {
        return llmVcrStrict;
    }

    public ConfigurationDTO setLlmVcrStrict(Boolean llmVcrStrict) {
        this.llmVcrStrict = llmVcrStrict;
        return this;
    }

    public Integer getLlmOptimisationMaxCalls() {
        return llmOptimisationMaxCalls;
    }

    public ConfigurationDTO setLlmOptimisationMaxCalls(Integer llmOptimisationMaxCalls) {
        this.llmOptimisationMaxCalls = llmOptimisationMaxCalls;
        return this;
    }

    public String getFixtureBodyRedactFields() {
        return fixtureBodyRedactFields;
    }

    public ConfigurationDTO setFixtureBodyRedactFields(String fixtureBodyRedactFields) {
        this.fixtureBodyRedactFields = fixtureBodyRedactFields;
        return this;
    }

    public String getOtelEndpoint() {
        return otelEndpoint;
    }

    public ConfigurationDTO setOtelEndpoint(String otelEndpoint) {
        this.otelEndpoint = otelEndpoint;
        return this;
    }

    public Boolean getOtelMetricsEnabled() {
        return otelMetricsEnabled;
    }

    public ConfigurationDTO setOtelMetricsEnabled(Boolean otelMetricsEnabled) {
        this.otelMetricsEnabled = otelMetricsEnabled;
        return this;
    }

    public Boolean getOtelTracesEnabled() {
        return otelTracesEnabled;
    }

    public ConfigurationDTO setOtelTracesEnabled(Boolean otelTracesEnabled) {
        this.otelTracesEnabled = otelTracesEnabled;
        return this;
    }

    public Long getOtelMetricsExportIntervalSeconds() {
        return otelMetricsExportIntervalSeconds;
    }

    public ConfigurationDTO setOtelMetricsExportIntervalSeconds(Long otelMetricsExportIntervalSeconds) {
        this.otelMetricsExportIntervalSeconds = otelMetricsExportIntervalSeconds;
        return this;
    }

    public String getOtelMetricsTemporality() {
        return otelMetricsTemporality;
    }

    public ConfigurationDTO setOtelMetricsTemporality(String otelMetricsTemporality) {
        this.otelMetricsTemporality = otelMetricsTemporality;
        return this;
    }

    public Boolean getPrometheusRemoteWriteEnabled() {
        return prometheusRemoteWriteEnabled;
    }

    public ConfigurationDTO setPrometheusRemoteWriteEnabled(Boolean prometheusRemoteWriteEnabled) {
        this.prometheusRemoteWriteEnabled = prometheusRemoteWriteEnabled;
        return this;
    }

    public String getPrometheusRemoteWriteUrl() {
        return prometheusRemoteWriteUrl;
    }

    public ConfigurationDTO setPrometheusRemoteWriteUrl(String prometheusRemoteWriteUrl) {
        this.prometheusRemoteWriteUrl = prometheusRemoteWriteUrl;
        return this;
    }

    public Long getPrometheusRemoteWriteIntervalSeconds() {
        return prometheusRemoteWriteIntervalSeconds;
    }

    public ConfigurationDTO setPrometheusRemoteWriteIntervalSeconds(Long prometheusRemoteWriteIntervalSeconds) {
        this.prometheusRemoteWriteIntervalSeconds = prometheusRemoteWriteIntervalSeconds;
        return this;
    }

    public String getPrometheusRemoteWriteBasicAuthUsername() {
        return prometheusRemoteWriteBasicAuthUsername;
    }

    public ConfigurationDTO setPrometheusRemoteWriteBasicAuthUsername(String prometheusRemoteWriteBasicAuthUsername) {
        this.prometheusRemoteWriteBasicAuthUsername = prometheusRemoteWriteBasicAuthUsername;
        return this;
    }

    /**
     * The value of every credential-bearing header ({@code Authorization}, {@code Api-Key}, …) is
     * redacted; every other header is returned exactly as supplied — by the same
     * {@link ConfigurationProperties#redactSensitiveValue(String, String)} rule every other
     * disclosing surface uses.
     */
    public String getPrometheusRemoteWriteHeaders() {
        return ConfigurationProperties.redactSensitiveValue("prometheusRemoteWriteHeaders", prometheusRemoteWriteHeaders);
    }

    @JsonIgnore
    public String getPrometheusRemoteWriteHeadersRawValue() {
        return prometheusRemoteWriteHeaders;
    }

    public ConfigurationDTO setPrometheusRemoteWriteHeaders(String prometheusRemoteWriteHeaders) {
        this.prometheusRemoteWriteHeaders = prometheusRemoteWriteHeaders;
        return this;
    }

    public String getPrometheusRemoteWriteProtocolVersion() {
        return prometheusRemoteWriteProtocolVersion;
    }

    public ConfigurationDTO setPrometheusRemoteWriteProtocolVersion(String prometheusRemoteWriteProtocolVersion) {
        this.prometheusRemoteWriteProtocolVersion = prometheusRemoteWriteProtocolVersion;
        return this;
    }

    public Long getRegexMatchingTimeoutMillis() {
        return regexMatchingTimeoutMillis;
    }

    public ConfigurationDTO setRegexMatchingTimeoutMillis(Long regexMatchingTimeoutMillis) {
        this.regexMatchingTimeoutMillis = regexMatchingTimeoutMillis;
        return this;
    }

    public Long getXpathMatchingTimeoutMillis() {
        return xpathMatchingTimeoutMillis;
    }

    public ConfigurationDTO setXpathMatchingTimeoutMillis(Long xpathMatchingTimeoutMillis) {
        this.xpathMatchingTimeoutMillis = xpathMatchingTimeoutMillis;
        return this;
    }

    public String getCustomJsonUnitMatchersClass() {
        return customJsonUnitMatchersClass;
    }

    public ConfigurationDTO setCustomJsonUnitMatchersClass(String customJsonUnitMatchersClass) {
        this.customJsonUnitMatchersClass = customJsonUnitMatchersClass;
        return this;
    }

    // ---------------------------------------------------------------------------------------------
    // WRITE-ONLY CREDENTIAL ACCESSORS
    //
    // The getters are what Jackson serializes for GET /mockserver/configuration, so they return the
    // shared redaction mask and NEVER the real secret. The functional paths (buildObject()/applyTo())
    // read the private fields directly, so full functionality is preserved while the wire stays clean.
    // Each has an @JsonIgnore-d *Raw accessor for in-process callers that legitimately need the real
    // value; those are excluded from serialization so they cannot leak through a JSON round trip.
    //
    // Two further properties — prometheusRemoteWriteHeaders and llmBackendsConfig — carry credentials
    // INSIDE a structured value rather than as the whole value. They are masked per header / per JSON
    // field by ConfigurationProperties.redactSensitiveValue(...) — the same rule GET /mockserver/config
    // and the startup log dump use — which gives them the same never-leaked, never-clobbered
    // guarantee. See their getters, above, and the write paths in buildObject()/applyTo().
    // ---------------------------------------------------------------------------------------------

    public String getLlmApiKey() {
        return maskCredential(llmApiKey);
    }

    @JsonIgnore
    public String getLlmApiKeyRawValue() {
        return llmApiKey;
    }

    public ConfigurationDTO setLlmApiKey(String llmApiKey) {
        this.llmApiKey = llmApiKey;
        return this;
    }

    public String getPrometheusRemoteWriteBearerToken() {
        return maskCredential(prometheusRemoteWriteBearerToken);
    }

    @JsonIgnore
    public String getPrometheusRemoteWriteBearerTokenRawValue() {
        return prometheusRemoteWriteBearerToken;
    }

    public ConfigurationDTO setPrometheusRemoteWriteBearerToken(String prometheusRemoteWriteBearerToken) {
        this.prometheusRemoteWriteBearerToken = prometheusRemoteWriteBearerToken;
        return this;
    }

    public String getPrometheusRemoteWriteBasicAuthPassword() {
        return maskCredential(prometheusRemoteWriteBasicAuthPassword);
    }

    @JsonIgnore
    public String getPrometheusRemoteWriteBasicAuthPasswordRawValue() {
        return prometheusRemoteWriteBasicAuthPassword;
    }

    public ConfigurationDTO setPrometheusRemoteWriteBasicAuthPassword(String prometheusRemoteWriteBasicAuthPassword) {
        this.prometheusRemoteWriteBasicAuthPassword = prometheusRemoteWriteBasicAuthPassword;
        return this;
    }
}

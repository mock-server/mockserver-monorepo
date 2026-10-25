package org.mockserver.serialization.model;

import com.google.common.collect.ImmutableMap;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.model.Delay;
import org.slf4j.event.Level;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

public class ConfigurationDTOTest {

    @Test
    public void shouldBuildObjectFromDTO() {
        ConfigurationDTO dto = new ConfigurationDTO(configuration()
            .logLevel(Level.DEBUG)
            .maxExpectations(500)
            .metricsEnabled(true)
            .corsAllowOrigin("https://example.com")
            .controlPlaneCorsAllowOrigin("https://dashboard.example.com")
            .controlPlaneTrustedHostSuffixes("k8s.example"));

        Configuration config = dto.buildObject();

        assertThat(config.logLevel(), is(Level.DEBUG));
        assertThat(config.maxExpectations(), is(500));
        assertThat(config.metricsEnabled(), is(true));
        assertThat(config.corsAllowOrigin(), is("https://example.com"));
        assertThat(config.controlPlaneCorsAllowOrigin(), is("https://dashboard.example.com"));
        assertThat(config.controlPlaneTrustedHostSuffixes(), is("k8s.example"));
    }

    @Test
    public void shouldApplyOnlyNonNullFieldsToTarget() {
        Configuration target = configuration()
            .logLevel(Level.INFO)
            .maxExpectations(100)
            .metricsEnabled(false)
            .corsAllowOrigin("https://original.com");

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setLogLevel("WARN");
        dto.setMaxExpectations(999);

        dto.applyTo(target);

        assertThat(target.logLevel(), is(Level.WARN));
        assertThat(target.maxExpectations(), is(999));
        assertThat(target.metricsEnabled(), is(false));
        assertThat(target.corsAllowOrigin(), is("https://original.com"));
    }

    @Test
    public void shouldNotApplyControlPlaneCorsAllowOriginToARunningServer() {
        Configuration target = configuration().controlPlaneCorsAllowOrigin("https://app.example.com").controlPlaneTrustedHostSuffixes("k8s.example");

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setControlPlaneCorsAllowOrigin("*");
        dto.setControlPlaneTrustedHostSuffixes("com");
        dto.setCorsAllowOrigin("https://mocks.example.com");

        dto.applyTo(target);

        assertThat(target.controlPlaneCorsAllowOrigin(), is("https://app.example.com"));
        assertThat(target.controlPlaneTrustedHostSuffixes(), is("k8s.example"));
        assertThat(target.corsAllowOrigin(), is("https://mocks.example.com"));
    }

    @Test
    public void shouldNotApplyForwardProxyHostListsToARunningServer() {
        Configuration target = configuration().forwardProxyAllowedHosts("api.example.com").forwardProxyDeniedHosts("10.0.0.0/8");

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setForwardProxyAllowedHosts("");
        dto.setForwardProxyDeniedHosts("");
        dto.applyTo(target);

        assertThat(target.forwardProxyAllowedHosts(), is("api.example.com"));
        assertThat(target.forwardProxyDeniedHosts(), is("10.0.0.0/8"));
        assertThat(new ConfigurationDTO(target).getForwardProxyAllowedHosts(), is("api.example.com"));
        assertThat(new ConfigurationDTO().setForwardProxyDeniedHosts("*.internal").buildObject().forwardProxyDeniedHosts(), is("*.internal"));
    }

    @Test
    public void shouldNotApplyForwardProxyBlockPrivateNetworksToARunningServer() {
        Configuration target = configuration().forwardProxyBlockPrivateNetworks(true);

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setForwardProxyBlockPrivateNetworks(false);
        dto.applyTo(target);

        assertThat(target.forwardProxyBlockPrivateNetworks(), is(true));
        assertThat(new ConfigurationDTO(target).getForwardProxyBlockPrivateNetworks(), is(true));
        assertThat(new ConfigurationDTO().setForwardProxyBlockPrivateNetworks(true).buildObject().forwardProxyBlockPrivateNetworks(), is(true));
    }

    @Test
    public void shouldNotApplyRecordedBodyFilesDirectoryToARunningServer() {
        Configuration target = configuration().recordedBodyFilesDirectory("recorded-bodies");

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setRecordedBodyFilesDirectory("/etc");
        dto.applyTo(target);

        assertThat(target.recordedBodyFilesDirectory(), is("recorded-bodies"));
        assertThat(new ConfigurationDTO().setRecordedBodyFilesDirectory("dir").buildObject().recordedBodyFilesDirectory(), is("dir"));
    }

    @Test
    public void shouldNotApplyStaticFilesDirectoryToARunningServer() {
        Configuration target = configuration().staticFilesDirectory("__files");

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setStaticFilesDirectory("/");
        dto.applyTo(target);

        assertThat(target.staticFilesDirectory(), is("__files"));
        assertThat(new ConfigurationDTO(target).getStaticFilesDirectory(), is("__files"));
        assertThat(new ConfigurationDTO().setStaticFilesDirectory("dir").buildObject().staticFilesDirectory(), is("dir"));
    }

    @Test
    public void shouldNotApplyTemplateSystemValueAllowListToARunningServer() {
        Configuration target = configuration().templateSystemValueAllowList("APP_REGION");

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setTemplateSystemValueAllowList("AWS_*,PATH");
        dto.applyTo(target);

        assertThat(target.templateSystemValueAllowList(), is("APP_REGION"));
        assertThat(new ConfigurationDTO(target).getTemplateSystemValueAllowList(), is("APP_REGION"));
        assertThat(new ConfigurationDTO().setTemplateSystemValueAllowList("APP_*").buildObject().templateSystemValueAllowList(), is("APP_*"));
    }

    @Test
    public void shouldNotApplyFileWritingPathsToARunningServer() {
        Configuration target = configuration()
            .auditLogFile("audit.ndjson")
            .persistedExpectationsPath("expectations.json")
            .persistedRecordedExpectationsPath("recorded-expectations.json")
            .persistedRecordedRequestsPath("recorded-requests.ndjson")
            .memoryUsageCsvDirectory("csv")
            .directoryToSaveDynamicSSLCertificate("certificates")
            .grpcProtocPath("/usr/local/bin/protoc");

        new ConfigurationDTO()
            .setAuditLogFile("/extensions/audit.ndjson")
            .setPersistedExpectationsPath("/extensions/expectations.json")
            .setPersistedRecordedExpectationsPath("/extensions/recorded-expectations.json")
            .setPersistedRecordedRequestsPath("/extensions/recorded-requests.ndjson")
            .setMemoryUsageCsvDirectory("/extensions")
            .setDirectoryToSaveDynamicSSLCertificate("/extensions")
            .setGrpcProtocPath("/tmp/evil")
            .applyTo(target);

        assertThat(target.auditLogFile(), is("audit.ndjson"));
        assertThat(target.persistedExpectationsPath(), is("expectations.json"));
        assertThat(target.persistedRecordedExpectationsPath(), is("recorded-expectations.json"));
        assertThat(target.persistedRecordedRequestsPath(), is("recorded-requests.ndjson"));
        assertThat(target.memoryUsageCsvDirectory(), is("csv"));
        assertThat(target.directoryToSaveDynamicSSLCertificate(), is("certificates"));
        assertThat(target.grpcProtocPath(), is("/usr/local/bin/protoc"));
        assertThat(new ConfigurationDTO(target).getAuditLogFile(), is("audit.ndjson"));
        // a startup configuration (buildObject) still sets them
        Configuration built = new ConfigurationDTO().setAuditLogFile("a.ndjson").setPersistedExpectationsPath("e.json")
            .setMemoryUsageCsvDirectory("dir").setDirectoryToSaveDynamicSSLCertificate("certs").setGrpcProtocPath("protoc-3").buildObject();
        assertThat(built.auditLogFile(), is("a.ndjson"));
        assertThat(built.persistedExpectationsPath(), is("e.json"));
        assertThat(built.memoryUsageCsvDirectory(), is("dir"));
        assertThat(built.directoryToSaveDynamicSSLCertificate(), is("certs"));
        assertThat(built.grpcProtocPath(), is("protoc-3"));
    }

    @Test
    public void shouldNotApplyControlPlaneTrustToARunningServer() {
        Configuration target = configuration()
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationJWKSource("jwks.json")
            .controlPlaneOidcAuthenticationRequired(false)
            .controlPlaneOidcIssuer("https://issuer.example")
            .controlPlaneOidcJwksUri("https://issuer.example/jwks")
            .controlPlaneAuthorizationEnabled(true)
            .controlPlaneAuditEnabled(true)
            .dashboardAnalyticsEndpoint("https://analytics.example");

        new ConfigurationDTO()
            .setControlPlaneTLSMutualAuthenticationRequired(false)
            .setControlPlaneJWTAuthenticationRequired(true)
            .setControlPlaneJWTAuthenticationJWKSource("https://attacker.example/jwks")
            .setControlPlaneOidcAuthenticationRequired(true)
            .setControlPlaneOidcIssuer("https://attacker.example")
            .setControlPlaneOidcJwksUri("https://attacker.example/jwks")
            .setControlPlaneAuthorizationEnabled(false)
            .setControlPlaneAuditEnabled(false)
            .setDashboardAnalyticsEndpoint("https://attacker.example/beacon")
            .applyTo(target);

        assertThat(target.controlPlaneTLSMutualAuthenticationRequired(), is(true));
        assertThat(target.controlPlaneJWTAuthenticationRequired(), is(false));
        assertThat(target.controlPlaneJWTAuthenticationJWKSource(), is("jwks.json"));
        assertThat(target.controlPlaneOidcAuthenticationRequired(), is(false));
        assertThat(target.controlPlaneOidcIssuer(), is("https://issuer.example"));
        assertThat(target.controlPlaneOidcJwksUri(), is("https://issuer.example/jwks"));
        assertThat(target.controlPlaneAuthorizationEnabled(), is(true));
        assertThat(target.controlPlaneAuditEnabled(), is(true));
        assertThat(target.dashboardAnalyticsEndpoint(), is("https://analytics.example"));
        // a startup configuration (buildObject) still sets them
        Configuration built = new ConfigurationDTO().setControlPlaneJWTAuthenticationRequired(true).setControlPlaneOidcIssuer("https://built.example")
            .setControlPlaneAuditEnabled(true).setDashboardAnalyticsEndpoint("https://built.example/analytics").buildObject();
        assertThat(built.controlPlaneJWTAuthenticationRequired(), is(true));
        assertThat(built.controlPlaneOidcIssuer(), is("https://built.example"));
        assertThat(built.controlPlaneAuditEnabled(), is(true));
        assertThat(built.dashboardAnalyticsEndpoint(), is("https://built.example/analytics"));
    }

    @Test
    public void shouldNotApplyNoProxyHostsToARunningServer() {
        Configuration target = configuration().noProxyHosts("internal.example");

        new ConfigurationDTO().setNoProxyHosts("*.com,*.net").applyTo(target);

        assertThat(target.noProxyHosts(), is("internal.example"));
        // a startup configuration (buildObject) still sets it
        assertThat(new ConfigurationDTO().setNoProxyHosts("*.com").buildObject().noProxyHosts(), is("*.com"));
    }

    @Test
    public void shouldNotCarryTheHeldLlmApiKeyToANewLlmDestination() {
        Configuration target = configuration().llmProvider("OPENAI").llmBaseUrl("https://held.example").llmApiKey("sk-held");

        ConfigurationDTO repoint = new ConfigurationDTO().setLlmBaseUrl("https://other.example");
        repoint.applyTo(target);

        assertThat(target.llmBaseUrl(), is("https://other.example"));
        assertThat(target.llmApiKey(), is(""));
        assertThat(repoint.llmApiKeyClearedForNewDestination(), is(true));
        // a buildObject() at startup has no held key, so nothing is cleared
        assertThat(new ConfigurationDTO().setLlmBaseUrl("https://other.example").setLlmApiKey("sk-built").buildObject().llmApiKey(), is("sk-built"));
        // never serialized
        assertThat(org.mockserver.serialization.ObjectMapperFactory.createObjectMapper().valueToTree(repoint).has("llmApiKeyClearedForNewDestination"), is(false));
    }

    @Test
    public void shouldNotApplyExtensionPropertiesToARunningServer() {
        Configuration target = configuration()
            .extensions("com.example.Loaded")
            .extensionScanning(false)
            .extensionStartTimeoutMillis(1000L)
            .extensionListenerQueueSize(100)
            .extensionListenerMaxBytes(1_000_000L)
            .extensionHelperTimeoutMillis(500L)
            .extensionHelperMaxInFlight(8)
            .extensionDataPlaneMaxInFlight(4)
            .extensionDataPlaneTimeoutMillis(750L);

        target.extensionAdminMaxInFlight(8).extensionAdminTimeoutMillis(2000L);
        new ConfigurationDTO()
            .setExtensions("com.example.Injected")
            .setExtensionScanning(true)
            .setExtensionStartTimeoutMillis(5L)
            .setExtensionAdminMaxInFlight(1000)
            .setExtensionAdminTimeoutMillis(5L)
            .setExtensionListenerQueueSize(1)
            .setExtensionListenerMaxBytes(1L)
            .setExtensionHelperTimeoutMillis(60_000L)
            .setExtensionHelperMaxInFlight(10_000)
            .setExtensionDataPlaneMaxInFlight(10_000)
            .setExtensionDataPlaneTimeoutMillis(60_000L)
            .applyTo(target);

        assertThat(target.extensions(), is("com.example.Loaded"));
        assertThat(target.extensionScanning(), is(false));
        assertThat(target.extensionStartTimeoutMillis(), is(1000L));
        assertThat(target.extensionAdminMaxInFlight(), is(8));
        assertThat(target.extensionAdminTimeoutMillis(), is(2000L));
        assertThat(target.extensionListenerQueueSize(), is(100));
        assertThat(target.extensionListenerMaxBytes(), is(1_000_000L));
        assertThat(target.extensionHelperTimeoutMillis(), is(500L));
        assertThat(target.extensionHelperMaxInFlight(), is(8));
        assertThat(new ConfigurationDTO(target).getExtensionHelperTimeoutMillis(), is(500L));
        assertThat(new ConfigurationDTO().setExtensionHelperMaxInFlight(4).buildObject().extensionHelperMaxInFlight(), is(4));
        assertThat(target.extensionDataPlaneMaxInFlight(), is(4));
        assertThat(target.extensionDataPlaneTimeoutMillis(), is(750L));
        assertThat(new ConfigurationDTO(target).getExtensionDataPlaneMaxInFlight(), is(4));
        assertThat(new ConfigurationDTO(target).getExtensionDataPlaneTimeoutMillis(), is(750L));
        assertThat(new ConfigurationDTO().setExtensionDataPlaneMaxInFlight(2).buildObject().extensionDataPlaneMaxInFlight(), is(2));
        assertThat(new ConfigurationDTO().setExtensionDataPlaneTimeoutMillis(300L).buildObject().extensionDataPlaneTimeoutMillis(), is(300L));
        // still shown, read-only, by GET /mockserver/configuration
        assertThat(new ConfigurationDTO(target).getExtensions(), is("com.example.Loaded"));
        // and still settable at startup from a DTO
        assertThat(new ConfigurationDTO().setExtensions("com.example.Startup").buildObject().extensions(), is("com.example.Startup"));
    }

    @Test
    public void shouldNotApplyLogRawNetworkTrafficToARunningServer() {
        Configuration target = configuration().logRawNetworkTraffic(false);

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setLogRawNetworkTraffic(true);
        dto.applyTo(target);

        assertThat(target.logRawNetworkTraffic(), is(false));
        assertThat(new ConfigurationDTO(target).getLogRawNetworkTraffic(), is(false));
        assertThat(new ConfigurationDTO().setLogRawNetworkTraffic(true).buildObject().logRawNetworkTraffic(), is(true));
    }

    @Test
    public void shouldRoundTripDataPlaneAuthenticationFields() {
        Configuration original = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBasicAuthenticationUsername("user")
            .dataPlaneBasicAuthenticationPassword("secret")
            .dataPlaneBasicAuthenticationRealm("my-realm")
            .dataPlaneBearerAuthenticationToken("tok-123")
            .dataPlaneApiKeyAuthenticationHeader("X-API-Key")
            .dataPlaneApiKeyAuthenticationValue("key-abc");

        ConfigurationDTO dto = new ConfigurationDTO(original);

        assertThat(dto.getDataPlaneAuthenticationRequired(), is(true));
        assertThat(dto.getDataPlaneBasicAuthenticationUsername(), is("user"));
        assertThat(dto.getDataPlaneBasicAuthenticationPassword(), is("secret"));
        assertThat(dto.getDataPlaneBasicAuthenticationRealm(), is("my-realm"));
        assertThat(dto.getDataPlaneBearerAuthenticationToken(), is("tok-123"));
        assertThat(dto.getDataPlaneApiKeyAuthenticationHeader(), is("X-API-Key"));
        assertThat(dto.getDataPlaneApiKeyAuthenticationValue(), is("key-abc"));

        Configuration rebuilt = dto.buildObject();

        assertThat(rebuilt.dataPlaneAuthenticationRequired(), is(true));
        assertThat(rebuilt.dataPlaneBasicAuthenticationUsername(), is("user"));
        assertThat(rebuilt.dataPlaneBasicAuthenticationPassword(), is("secret"));
        assertThat(rebuilt.dataPlaneBasicAuthenticationRealm(), is("my-realm"));
        assertThat(rebuilt.dataPlaneBearerAuthenticationToken(), is("tok-123"));
        assertThat(rebuilt.dataPlaneApiKeyAuthenticationHeader(), is("X-API-Key"));
        assertThat(rebuilt.dataPlaneApiKeyAuthenticationValue(), is("key-abc"));
    }

    @Test
    public void shouldApplyOnlyNonNullDataPlaneFieldsToTarget() {
        Configuration target = configuration()
            .dataPlaneAuthenticationRequired(true)
            .dataPlaneBearerAuthenticationToken("original-token");

        // a fresh DTO with only the realm set must not overwrite the existing token (null fields skipped)
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setDataPlaneBasicAuthenticationRealm("changed-realm");

        dto.applyTo(target);

        assertThat(target.dataPlaneAuthenticationRequired(), is(true));
        assertThat(target.dataPlaneBearerAuthenticationToken(), is("original-token"));
        assertThat(target.dataPlaneBasicAuthenticationRealm(), is("changed-realm"));
    }

    @Test
    public void shouldCreateDTOFromConfiguration() {
        Configuration config = configuration()
            .logLevel(Level.ERROR)
            .maxExpectations(2000)
            .disableLogging(true);

        ConfigurationDTO dto = new ConfigurationDTO(config);

        assertThat(dto.getLogLevel(), is("ERROR"));
        assertThat(dto.getMaxExpectations(), is(2000));
        assertThat(dto.getDisableLogging(), is(true));
    }

    @Test
    public void shouldRoundTripDTOAndConfiguration() {
        Configuration original = configuration()
            .logLevel(Level.TRACE)
            .maxExpectations(42)
            .maxLogEntries(1234)
            .enableCORSForAPI(true)
            .validateProxyOpenAPISpec("https://example.com/spec.json")
            .validateProxyEnforce(true);

        ConfigurationDTO dto = new ConfigurationDTO(original);
        Configuration rebuilt = dto.buildObject();

        assertThat(rebuilt.logLevel(), is(original.logLevel()));
        assertThat(rebuilt.maxExpectations(), is(original.maxExpectations()));
        assertThat(rebuilt.maxLogEntries(), is(original.maxLogEntries()));
        assertThat(rebuilt.enableCORSForAPI(), is(original.enableCORSForAPI()));
        assertThat(rebuilt.validateProxyOpenAPISpec(), is(original.validateProxyOpenAPISpec()));
        assertThat(rebuilt.validateProxyEnforce(), is(original.validateProxyEnforce()));
    }

    @Test
    public void shouldRoundTripDashboardAnalyticsThroughJson() {
        Configuration original = configuration()
            .dashboardAnalyticsEnabled(false)
            .dashboardAnalyticsEndpoint("https://analytics.example.com")
            .dashboardAnalyticsKey("phc_test_key")
            .dashboardAnalyticsDistribution("docker");

        ConfigurationDTO dto = new ConfigurationDTO(original);
        assertThat(dto.getDashboardAnalyticsEnabled(), is(false));
        assertThat(dto.getDashboardAnalyticsEndpoint(), is("https://analytics.example.com"));
        assertThat(dto.getDashboardAnalyticsKey(), is("phc_test_key"));
        assertThat(dto.getDashboardAnalyticsDistribution(), is("docker"));

        String json = new org.mockserver.serialization.ConfigurationSerializer(new org.mockserver.logging.MockServerLogger())
            .serialize(original);
        assertThat(json, containsString("dashboardAnalyticsEnabled"));
        assertThat(json, containsString("dashboardAnalyticsEndpoint"));
        assertThat(json, containsString("dashboardAnalyticsKey"));
        assertThat(json, containsString("dashboardAnalyticsDistribution"));

        Configuration rebuilt = new org.mockserver.serialization.ConfigurationSerializer(new org.mockserver.logging.MockServerLogger())
            .deserialize(json);

        assertThat(rebuilt.dashboardAnalyticsEnabled(), is(false));
        assertThat(rebuilt.dashboardAnalyticsEndpoint(), is("https://analytics.example.com"));
        assertThat(rebuilt.dashboardAnalyticsKey(), is("phc_test_key"));
        assertThat(rebuilt.dashboardAnalyticsDistribution(), is("docker"));
    }

    @Test
    public void shouldRoundTripLoadGenerationMetricLabelsThroughJson() {
        // a List<String> property must survive a full serialize -> JSON -> deserialize cycle as a JSON array
        Configuration original = configuration()
            .loadGenerationMetricLabels(Arrays.asList("scenario", "region", "tier"));

        String json = new org.mockserver.serialization.ConfigurationSerializer(new org.mockserver.logging.MockServerLogger())
            .serialize(original);
        assertThat(json, containsString("loadGenerationMetricLabels"));

        Configuration rebuilt = new org.mockserver.serialization.ConfigurationSerializer(new org.mockserver.logging.MockServerLogger())
            .deserialize(json);

        assertThat(rebuilt.loadGenerationMetricLabels(), is(Arrays.asList("scenario", "region", "tier")));
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectInvalidLogLevel() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setLogLevel("INVALID_LEVEL");
        dto.applyTo(configuration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeMaxExpectations() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMaxExpectations(-1);
        dto.applyTo(configuration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeMaxLogEntries() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMaxLogEntries(-100);
        dto.applyTo(configuration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectNegativeMaxWebSocketExpectations() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMaxWebSocketExpectations(-1);
        dto.applyTo(configuration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectExcessiveMaxExpectations() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMaxExpectations(200000);
        dto.applyTo(configuration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectExcessiveMaxLogEntries() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMaxLogEntries(2000000);
        dto.applyTo(configuration());
    }

    @Test(expected = IllegalArgumentException.class)
    public void shouldRejectExcessiveMaxWebSocketExpectations() {
        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMaxWebSocketExpectations(200000);
        dto.applyTo(configuration());
    }

    @Test
    public void shouldNotPartiallyMutateOnValidationFailure() {
        Configuration target = configuration()
            .metricsEnabled(false)
            .maxExpectations(100);

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMetricsEnabled(true);
        dto.setMaxExpectations(200000);

        try {
            dto.applyTo(target);
        } catch (IllegalArgumentException e) {
            // expected
        }

        assertThat(target.metricsEnabled(), is(false));
        assertThat(target.maxExpectations(), is(100));
    }

    @Test
    public void shouldNotPartiallyMutateOnProxyParsingFailure() {
        Configuration target = configuration()
            .metricsEnabled(false);

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setMetricsEnabled(true);
        dto.setForwardHttpProxy("not_a_valid_host_port");

        try {
            dto.applyTo(target);
        } catch (IllegalArgumentException e) {
            // expected
        }

        assertThat(target.metricsEnabled(), is(false));
    }

    @Test
    public void shouldRoundTripLogLevelOverrides() {
        Map<String, String> overrides = ImmutableMap.of("MATCHING", "WARN", "EXPECTATION_MATCHED", "INFO");
        Configuration original = configuration()
            .logLevel(Level.DEBUG)
            .logLevelOverrides(overrides);

        ConfigurationDTO dto = new ConfigurationDTO(original);
        assertThat(dto.getLogLevelOverrides(), equalTo(overrides));

        Configuration rebuilt = dto.buildObject();
        assertThat(rebuilt.logLevelOverrides(), equalTo(overrides));
    }

    @Test
    public void shouldApplyLogLevelOverridesPartially() {
        Configuration target = configuration()
            .logLevel(Level.INFO)
            .logLevelOverrides(Collections.emptyMap());

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setLogLevelOverrides(ImmutableMap.of("MATCHING", "ERROR"));

        dto.applyTo(target);

        assertThat(target.logLevelOverrides(), equalTo(ImmutableMap.of("MATCHING", "ERROR")));
        assertThat(target.logLevel(), is(Level.INFO));
    }

    @Test
    public void shouldNotApplyLogLevelOverridesWhenNull() {
        Map<String, String> original = ImmutableMap.of("SERVER", "WARN");
        Configuration target = configuration()
            .logLevelOverrides(original);

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.applyTo(target);

        assertThat(target.logLevelOverrides(), equalTo(original));
    }

    @Test
    public void shouldSerializeEmptyLogLevelOverridesAsNull() {
        Configuration config = configuration()
            .logLevelOverrides(Collections.emptyMap());

        ConfigurationDTO dto = new ConfigurationDTO(config);
        assertThat(dto.getLogLevelOverrides(), nullValue());
    }

    @Test
    public void shouldRoundTripDevMode() {
        Configuration original = configuration()
            .devMode(true)
            .maxExpectations(500);

        ConfigurationDTO dto = new ConfigurationDTO(original);
        assertThat(dto.getDevMode(), is(true));

        Configuration rebuilt = dto.buildObject();
        assertThat(rebuilt.devMode(), is(true));
        assertThat(rebuilt.maxExpectations(), is(500));
    }

    @Test
    public void shouldApplyDevModePartially() {
        Configuration target = configuration()
            .devMode(false);

        ConfigurationDTO dto = new ConfigurationDTO();
        dto.setDevMode(true);

        dto.applyTo(target);

        assertThat(target.devMode(), is(true));
    }

    @Test
    public void shouldNotApplyDevModeWhenNull() {
        Configuration target = configuration()
            .devMode(true);

        ConfigurationDTO dto = new ConfigurationDTO();
        // devMode is null — should not overwrite
        dto.applyTo(target);

        assertThat(target.devMode(), is(true));
    }

    @Test
    public void shouldRoundTripBreakpointTimeoutAndMaxHeld() {
        Configuration original = configuration()
            .breakpointTimeoutMillis(5000L)
            .breakpointMaxHeld(10);

        ConfigurationDTO dto = new ConfigurationDTO(original);
        assertThat(dto.getBreakpointTimeoutMillis(), is(5000L));
        assertThat(dto.getBreakpointMaxHeld(), is(10));

        Configuration rebuilt = dto.buildObject();
        assertThat(rebuilt.breakpointTimeoutMillis(), is(5000L));
        assertThat(rebuilt.breakpointMaxHeld(), is(10));
    }

    // ---------------------------------------------------------------------------------------------
    // Reflection-driven drift guard.
    //
    // ConfigurationDTO hand-mirrors every Configuration property in four places (field,
    // copy-constructor, buildObject(), applyTo()). Missing a property in any of these silently drops
    // config on a /mockserver/configuration round-trip. This test reflects over every Configuration
    // property that has both a no-arg getter and a single-argument fluent setter, assigns it a
    // distinctive NON-DEFAULT value, round-trips the Configuration through
    //   new ConfigurationDTO(config).buildObject()
    // and asserts every covered property survives. A NEW Configuration property that is not mirrored
    // in the DTO will therefore fail this test automatically.
    //
    // A small, explicitly-documented set of properties is excluded because they are deliberately not
    // part of the serialized configuration DTO surface (runtime callbacks, internal volatile flags,
    // runtime-mutated proxy targets, and complex/aggregate types not carried by the DTO).
    // ---------------------------------------------------------------------------------------------

    /**
     * Properties that intentionally do NOT round-trip through ConfigurationDTO. Each is excluded for
     * a concrete reason (not a convenience to make the test pass):
     * <ul>
     *   <li>{@code logEventListener}, {@code binaryProxyListener} — runtime callback objects, not
     *       JSON-serializable config.</li>
     *   <li>{@code rebuildTLSContext}, {@code rebuildServerTLSContext} — internal volatile primitive
     *       flags driving TLS re-initialisation, not user configuration.</li>
     * </ul>
     * Note: {@code proxyRemoteHost}, {@code proxyRemotePort}, {@code proxyPassMappings} and
     * {@code controlPlaneScopeMapping} ARE carried by the DTO and are enforced by this guard (they
     * get a distinctive value via {@link #distinctiveValueFor}). They are deliberately NOT excluded.
     * If you add a new property to the DTO, do not add it here; if you intentionally leave one out of
     * the serialized config DTO, add it here with a documented reason so the guard stops enforcing it.
     */
    private static final Set<String> DTO_EXCLUDED_PROPERTIES = new HashSet<>(Arrays.asList(
        "logEventListener",
        "binaryProxyListener",
        "rebuildTLSContext",
        "rebuildServerTLSContext",
        // set by a running server whose extensions offer template helpers; internal state, never a property
        "extensionTemplateHelperStub",
        // ringBufferSize() is a DERIVED, rounded-to-the-next-power-of-two EFFECTIVE getter (the
        // disruptor requires a power-of-two ring), not a faithfully round-trippable raw setting:
        // ringBufferSize(N) then ringBufferSize() returns nextPowerOfTwo(N) >= N, so value-equality
        // round-trip cannot hold. It IS carried through the DTO (constructor/applyTo/buildObject) for
        // observability/parity — see ConfigurationDTO — but is excluded from this value-equality drift
        // guard for the rounding reason above.
        "ringBufferSize"
    ));

    @Test
    public void shouldRoundTripEveryMirroredConfigurationPropertyThroughDTO() throws Exception {
        Configuration defaults = configuration();
        Configuration original = configuration();
        List<PropertyAccessor> accessors = discoverProperties();
        List<String> covered = new ArrayList<>();
        AtomicInteger counter = new AtomicInteger(1);

        // assign a distinctive non-default value to every mirrored property
        for (PropertyAccessor accessor : accessors) {
            Object value = distinctiveValueFor(accessor, counter, accessor.getter.invoke(defaults));
            accessor.setter.invoke(original, value);
            covered.add(accessor.name);
        }

        // sanity: the guard must actually be exercising a large, representative property set, so a
        // regression that silently stops discovering properties does not pass vacuously
        assertThat("reflection-driven drift guard should cover a large property set",
            covered.size(), greaterThan(100));

        // round-trip through the DTO exactly as the /mockserver/configuration control plane does
        ConfigurationDTO dto = new ConfigurationDTO(original);
        Configuration rebuilt = dto.buildObject();

        // every covered property must survive the round-trip
        List<String> dropped = new ArrayList<>();
        for (PropertyAccessor accessor : accessors) {
            Object before = accessor.getter.invoke(original);
            Object after = accessor.getter.invoke(rebuilt);
            if (!equalsForRoundTrip(before, after)) {
                dropped.add(accessor.name + " (expected <" + before + "> but DTO round-trip produced <" + after + ">)");
            }
        }

        assertThat("Configuration properties dropped by ConfigurationDTO round-trip — they are missing "
                + "from the DTO field/constructor/buildObject (add them, or add to DTO_EXCLUDED_PROPERTIES "
                + "with a documented reason if intentionally not part of the serialized config): " + dropped,
            dropped, is(empty()));
    }

    // Properties a running server must never take from PUT /mockserver/configuration: applyTo skips them.
    private static final Set<String> STARTUP_ONLY_PROPERTIES = new HashSet<>(Arrays.asList(
        // decide which web sites may use the control plane, so a control-plane request must not widen them
        "controlPlaneCorsAllowOrigin",
        "controlPlaneTrustedHostSuffixes",
        // would let mTLS trust client certificates anyone can create with the bundled CA's published key
        "tlsMutualAuthenticationAllowBundledCa",
        // decides where the server writes recorded body files, so a control-plane request must not repoint it
        "recordedBodyFilesDirectory",
        // decides which server files a request can read, so a control-plane request must not repoint it
        "staticFilesDirectory",
        // decide where the server writes files, so a control-plane request must not repoint them
        "auditLogFile",
        "persistedExpectationsPath",
        "persistedRecordedExpectationsPath",
        "persistedRecordedRequestsPath",
        "memoryUsageCsvDirectory",
        "directoryToSaveDynamicSSLCertificate",
        // names an executable the server runs, so a control-plane request must not repoint it
        "grpcProtocPath",
        // decides which environment variables and system properties templates can read, so a control-plane
        // request must not widen it
        "templateSystemValueAllowList",
        // extensions are trusted code loaded once at startup, so a control-plane request must not add or reconfigure them
        "extensions",
        "extensionScanning",
        "extensionStartTimeoutMillis",
        "extensionAdminMaxInFlight",
        "extensionAdminTimeoutMillis",
        "extensionListenerQueueSize",
        "extensionListenerMaxBytes",
        "extensionHelperTimeoutMillis",
        "extensionHelperMaxInFlight",
        "extensionDataPlaneMaxInFlight",
        "extensionDataPlaneTimeoutMillis",
        // decide where the server may send requests, so a control-plane request must not widen them
        "forwardProxyBlockPrivateNetworks",
        "forwardProxyAllowedHosts",
        "forwardProxyDeniedHosts",
        // writes every connection's traffic, credentials and bodies included, to the log
        "logRawNetworkTraffic",
        // decide who may use the control plane, so a control-plane request must not change them
        "controlPlaneBasicAuthenticationRequired",
        "controlPlaneBasicAuthenticationCredentials",
        "controlPlaneBasicAuthenticationUsersFile",
        "controlPlaneBasicAuthenticationAllowInsecure",
        "controlPlaneTLSMutualAuthenticationRequired",
        "controlPlaneTLSMutualAuthenticationCAChain",
        "controlPlaneJWTAuthenticationRequired",
        "controlPlaneJWTAuthenticationJWKSource",
        "controlPlaneJWTAuthenticationExpectedAudience",
        "controlPlaneJWTAuthenticationMatchingClaims",
        "controlPlaneJWTAuthenticationRequiredClaims",
        "controlPlaneOidcAuthenticationRequired",
        "controlPlaneOidcIssuer",
        "controlPlaneOidcJwksUri",
        "controlPlaneOidcAudience",
        "controlPlaneOidcRequiredScopes",
        "controlPlaneOidcScopeClaim",
        // decide what each control-plane user may do, so a control-plane request must not change them
        "controlPlaneAuthorizationEnabled",
        "controlPlaneScopeMapping",
        // switching it off would hide the control-plane changes that follow
        "controlPlaneAuditEnabled",
        // decides where every dashboard viewer's browser sends usage events
        "dashboardAnalyticsEndpoint",
        // the peer authentication token is sent to every fan-in peer, so a control-plane request must not
        // choose the peers (and the peer client reads the token once, at startup)
        "clusterVerifyFanInPeers",
        "clusterFanInPeerAuthToken",
        // decides which forwarded requests bypass the upstream proxy
        "noProxyHosts"
    ));

    @Test
    public void shouldApplyEveryMirroredConfigurationPropertyToTarget() throws Exception {
        // a fully-populated source DTO applied onto a fresh target must transfer every covered
        // property (guards the applyTo() mirror specifically — buildObject() is covered above)
        Configuration defaults = configuration();
        Configuration source = configuration();
        List<PropertyAccessor> accessors = discoverProperties();
        AtomicInteger counter = new AtomicInteger(1000);
        for (PropertyAccessor accessor : accessors) {
            accessor.setter.invoke(source, distinctiveValueFor(accessor, counter, accessor.getter.invoke(defaults)));
        }

        ConfigurationDTO dto = new ConfigurationDTO(source);
        Configuration target = configuration();
        dto.applyTo(target);

        List<String> dropped = new ArrayList<>();
        for (PropertyAccessor accessor : accessors) {
            if (STARTUP_ONLY_PROPERTIES.contains(accessor.name)) {
                continue;
            }
            Object expected = accessor.getter.invoke(source);
            Object actual = accessor.getter.invoke(target);
            if (!equalsForRoundTrip(expected, actual)) {
                dropped.add(accessor.name + " (expected <" + expected + "> but applyTo() produced <" + actual + ">)");
            }
        }

        assertThat("Configuration properties not transferred by ConfigurationDTO.applyTo() — they are "
                + "missing from the applyTo() mirror (or DTO_EXCLUDED_PROPERTIES): " + dropped,
            dropped, is(empty()));
    }

    // package-private (not private) so the classification guard in
    // ConfigurationEnforcementClassificationTest can reuse the same reflective enumeration, keeping a
    // single source of truth for what counts as a Configuration property
    static final class PropertyAccessor {
        final String name;
        final Method getter;
        final Method setter;
        final Class<?> type;

        PropertyAccessor(String name, Method getter, Method setter, Class<?> type) {
            this.name = name;
            this.getter = getter;
            this.setter = setter;
            this.type = type;
        }
    }

    /**
     * Discover every Configuration property exposed as a no-arg getter {@code T name()} paired with a
     * single-argument fluent setter {@code Configuration name(T)}, minus the documented exclusions.
     */
    static List<PropertyAccessor> discoverProperties() {
        Method[] methods = Configuration.class.getDeclaredMethods();
        // index single-arg setters that return Configuration, grouped by name (a property may have
        // overloaded setters, e.g. logLevel(Level) and logLevel(String) — keep all and pick the one
        // whose parameter type matches the getter return type)
        java.util.Map<String, List<Method>> setters = new java.util.HashMap<>();
        for (Method m : methods) {
            if (m.getParameterCount() == 1
                && m.getReturnType().equals(Configuration.class)
                && !m.isSynthetic()) {
                setters.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
            }
        }
        List<PropertyAccessor> accessors = new ArrayList<>();
        Set<String> seen = new TreeSet<>();
        for (Method getter : methods) {
            if (getter.getParameterCount() != 0 || getter.isSynthetic()) {
                continue;
            }
            String name = getter.getName();
            if (DTO_EXCLUDED_PROPERTIES.contains(name) || seen.contains(name)) {
                continue;
            }
            Class<?> getterType = getter.getReturnType();
            if (getterType.equals(void.class)) {
                continue;
            }
            List<Method> candidateSetters = setters.get(name);
            if (candidateSetters == null) {
                continue;
            }
            // prefer the setter whose parameter type matches the getter return type (boxing aside)
            Method setter = null;
            for (Method candidate : candidateSetters) {
                if (compatible(getterType, candidate.getParameterTypes()[0])) {
                    setter = candidate;
                    break;
                }
            }
            if (setter == null) {
                continue;
            }
            seen.add(name);
            accessors.add(new PropertyAccessor(name, getter, setter, setter.getParameterTypes()[0]));
        }
        return accessors;
    }

    private static boolean compatible(Class<?> getterType, Class<?> setterType) {
        return box(getterType).equals(box(setterType));
    }

    private static Class<?> box(Class<?> type) {
        if (type.equals(boolean.class)) return Boolean.class;
        if (type.equals(int.class)) return Integer.class;
        if (type.equals(long.class)) return Long.class;
        if (type.equals(double.class)) return Double.class;
        if (type.equals(float.class)) return Float.class;
        if (type.equals(short.class)) return Short.class;
        if (type.equals(byte.class)) return Byte.class;
        return type;
    }

    /**
     * Produce a distinctive, valid, non-default value for the given property. A few properties have
     * value constraints (validated in ConfigurationDTO.buildObject()/applyTo() or by enum parsing),
     * so they are special-cased; everything else gets a type-driven distinctive value.
     */
    private static Object distinctiveValueFor(PropertyAccessor accessor, AtomicInteger counter, Object defaultValue) {
        String name = accessor.name;
        Class<?> type = box(accessor.type);

        // --- value-constrained properties (must be special-cased to satisfy validation/enums) ---
        switch (name) {
            case "logLevel":
                // setter accepts Level; pick a non-default level
                return Level.TRACE;
            case "forwardProxyTLSX509CertificatesTrustManagerType":
                // setter accepts the enum; round-trips via name()
                return org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager.CUSTOM;
            case "binaryMessageFraming":
                return org.mockserver.configuration.BinaryMessageFraming.POSTGRESQL;
            case "binaryMessageLengthPrefixBytes":
                return 2;               // validated 1, 2, 4 or 8 (default 4)
            case "binaryMessageLengthPrefixByteOrder":
                return java.nio.ByteOrder.LITTLE_ENDIAN;
            case "binaryMessageLengthPrefixOffset":
                return 3;               // validated zero or more (default 0)
            case "forwardHttpProxy":
                return java.net.InetSocketAddress.createUnresolved("http-proxy.example", 8081);
            case "forwardHttpsProxy":
                return java.net.InetSocketAddress.createUnresolved("https-proxy.example", 8082);
            case "forwardSocksProxy":
                return java.net.InetSocketAddress.createUnresolved("socks-proxy.example", 1080);
            case "connectionDelay":
                return Delay.milliseconds(1234);
            case "maxExpectations":
                return 4242;            // validated 0..100000
            case "maxLogEntries":
                return 424242;          // validated 0..1000000
            case "maxWebSocketExpectations":
                return 4243;            // validated 0..100000
            case "webSocketProxyMaxRecordedFrames":
                return 424244;          // validated 0..1000000
            case "webSocketProxyIdleTimeoutSeconds":
                return 4245;            // validated 0..86400
            case "driftSampleRate":
                return 0.5d;            // valid drift sample rate in [0.0, 1.0], non-default (default 1.0)
            case "driftAlertSeverityThreshold":
                return "WARNING";       // a valid severity name
            case "proxyPassMappings":
                // typed List<ProxyPassMapping> — the generic List<String> branch would not be a valid
                // argument to the typed setter, so build a real non-empty list of distinct mappings
                return new ArrayList<>(Arrays.asList(
                    org.mockserver.model.ProxyPassMapping.proxyPass("/api", "https://api.example.com"),
                    org.mockserver.model.ProxyPassMapping.proxyPass("/cdn", "http://cdn.example.com:8080")));
            case "controlPlaneScopeMapping":
                // typed Map<String, ControlPlaneRole> — build a real non-empty map of distinct roles
                return new java.util.LinkedHashMap<>(ImmutableMap.of(
                    "platform-admins", org.mockserver.authentication.authorization.ControlPlaneRole.ADMIN,
                    "qa-team", org.mockserver.authentication.authorization.ControlPlaneRole.MUTATE,
                    "viewers", org.mockserver.authentication.authorization.ControlPlaneRole.READ));
            // file-path properties whose setter calls fileExists(...) — must point at a real file
            case "controlPlaneTLSMutualAuthenticationCAChain":
            case "controlPlanePrivateKeyPath":
            case "controlPlaneX509CertificatePath":
            case "tlsMutualAuthenticationCertificateChain":
            case "forwardProxyTLSCustomTrustX509Certificates":
            case "forwardProxyPrivateKey":
            case "forwardProxyCertificateChain":
            case "controlPlaneBasicAuthenticationUsersFile":
                return existingFilePath();
            default:
                break;
        }

        // --- type-driven distinctive values ---
        int n = counter.getAndIncrement();
        if (type.equals(Boolean.class)) {
            // negate the property's own default so that if the DTO drops this property (reverting it
            // to its default on round-trip) the assertion reliably fails. If the default resolves to
            // null, true is non-null and distinctive.
            return !(Boolean.TRUE.equals(defaultValue));
        }
        if (type.equals(Integer.class)) {
            return 1_000_000 + n;
        }
        if (type.equals(Long.class)) {
            return 5_000_000_000L + n;
        }
        if (type.equals(Double.class)) {
            return 123.5d + n;
        }
        if (type.equals(String.class)) {
            return "distinctive-value-" + name + "-" + n;
        }
        if (List.class.isAssignableFrom(type)) {
            return new ArrayList<>(Arrays.asList("list-value-" + name + "-a-" + n, "list-value-" + name + "-b-" + n));
        }
        if (Set.class.isAssignableFrom(type)) {
            return new LinkedHashSet<>(Arrays.asList("set-value-" + name + "-a-" + n, "set-value-" + name + "-b-" + n));
        }
        if (Map.class.isAssignableFrom(type)) {
            // the only Map<String,String> property carried by the DTO
            return ImmutableMap.of("key-" + name + "-1", "val-" + n, "key-" + name + "-2", "val-" + (n + 1));
        }
        throw new AssertionError("Reflection drift guard has no distinctive-value strategy for property '"
            + name + "' of type " + accessor.type.getName()
            + " — add a strategy in distinctiveValueFor(...) or, if this property is intentionally not "
            + "part of the serialized config DTO, add it to DTO_EXCLUDED_PROPERTIES with a documented reason.");
    }

    private static volatile String existingFilePath;

    /**
     * An absolute path to a real, readable file, used as the distinctive value for the cert/key
     * file-path properties whose setter validates existence via {@code fileExists(...)}. These
     * properties cannot accept an arbitrary made-up string, so they share one real path; the
     * round-trip assertion still compares each property against its own original value.
     */
    private static String existingFilePath() {
        String path = existingFilePath;
        if (path == null) {
            try {
                java.io.File file = java.io.File.createTempFile("configuration-dto-drift-guard", ".pem");
                file.deleteOnExit();
                path = file.getAbsolutePath();
                existingFilePath = path;
            } catch (java.io.IOException e) {
                throw new RuntimeException("failed to create temp file for drift-guard cert-path properties", e);
            }
        }
        return path;
    }

    private static boolean equalsForRoundTrip(Object a, Object b) {
        if (a instanceof Set && b instanceof Set) {
            // order-insensitive comparison; the DTO may rebuild a set in a different iteration order
            return new HashSet<>((Set<?>) a).equals(new HashSet<>((Set<?>) b));
        }
        return java.util.Objects.equals(a, b);
    }
}

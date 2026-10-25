package org.mockserver.mock;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.llm.OpenAiResponsesStore;
import org.mockserver.llm.ParsedConversation;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.audit.AuditStore;
import org.mockserver.model.HttpRequest;
import org.mockserver.model.RequestDefinition;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.slf4j.event.Level;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.fail;
import static org.mockito.Mockito.mock;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.log.model.LogEntry.LogMessageType.RECEIVED_REQUEST;
import static org.mockserver.mock.listeners.MockServerMatcherNotifier.Cause.API;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Behavioural tests for {@link HttpState#applyConfigurationUpdate()} — the reconciliation run
 * immediately after {@code PUT /mockserver/configuration} mutates the live {@link Configuration}.
 *
 * <p>Two distinct contracts are covered:
 * <ul>
 *     <li><b>Resizable capacity properties actually resize the running subsystem.</b> Previously
 *     {@code maxLogEntries}, {@code maxExpectations} and {@code controlPlaneAuditMaxEntries} were
 *     consumed once at construction, so a PUT was accepted, echoed back and then ignored.</li>
 *     <li><b>Genuinely init-only properties are reported, not silently dropped.</b>
 *     {@code ringBufferSize} and {@code maxWebSocketExpectations} cannot be resized on a running
 *     server, so a differing value produces a WARN and the configuration field is reset to the
 *     value in force (making the echoed response and a later GET truthful). An unchanged value —
 *     the whole-blob PUT case — must warn about nothing.</li>
 * </ul>
 *
 * <p>State-mutating: asserts on the process-wide {@link AuditStore} singleton, so it must run in
 * the sequential Surefire phase. The singleton's capacity is restored in {@link #restoreAuditStore()}.
 */
public class HttpStateConfigurationUpdateTest {

    /**
     * Captures every event logged through this logger so the init-only WARN can be asserted without
     * touching the process-wide global log listener (which would not be parallel-safe).
     */
    private static class CapturingMockServerLogger extends MockServerLogger {
        /**
         * Snapshots (level, message) taken at log time. The {@link LogEntry} instances themselves
         * MUST NOT be retained: they are recycled and cleared once handed to the event log's
         * disruptor, so reading them later would see blanked fields.
         */
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, HttpStateConfigurationUpdateTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (logEntry.getLogLevel() == Level.WARN) {
                warnings.add(String.valueOf(logEntry.getMessage()));
            }
            super.logEvent(logEntry);
        }

        List<String> warnings() {
            return warnings;
        }

        /**
         * Discards everything captured so far, so a warning assertion sees only what the action
         * under test logged (and not, say, anything emitted while the fixture was built).
         */
        void forgetCapturedEvents() {
            warnings.clear();
        }
    }

    private Configuration configuration;
    private CapturingMockServerLogger logger;
    private HttpState httpState;
    private int originalAuditMaxSize;

    @Before
    public void prepareTestFixture() {
        originalAuditMaxSize = AuditStore.getInstance().getMaxSize();
        configuration = configuration();
        // The suite runs at logLevel=ERROR (mockserver.testLogLevel), which would suppress the
        // init-only WARN under test. Raise it on this INSTANCE configuration only — no global state.
        configuration.logLevel(Level.WARN);
        logger = new CapturingMockServerLogger(configuration);
        httpState = new HttpState(configuration, logger, mock(Scheduler.class));
    }

    @After
    public void restoreAuditStore() {
        AuditStore.getInstance().setMaxSize(originalAuditMaxSize);
        OpenAiResponsesStore.getInstance().reset();
        OpenAiResponsesStore.getInstance().setMaxSizeInBytes(configuration().maxOpenAiResponsesSizeInBytes());
        if (httpState != null) {
            httpState.stop();
        }
    }

    /**
     * Blocks until the asynchronous event log has drained the disruptor, so log-size assertions are
     * deterministic.
     */
    private void drainEventLog() {
        CompletableFuture<List<LogEntry>> future = new CompletableFuture<>();
        httpState.getMockServerLog().retrieveMessageLogEntries((RequestDefinition) null, future::complete);
        try {
            future.get(60, SECONDS);
        } catch (Exception e) {
            fail("timed out draining the event log: " + e.getMessage());
        }
    }

    private void addExpectation(String path) {
        httpState.getRequestMatchers().add(new Expectation(request(path)).thenRespond(response(path)), API);
    }

    @Test
    public void shouldResizeEventLogWhenMaxLogEntriesReduced() {
        // given - five entries in the event log
        for (int i = 0; i < 5; i++) {
            logger.logEvent(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/" + i)));
        }
        drainEventLog();
        assertThat(httpState.getMockServerLog().size(), is(5));

        // when - maxLogEntries is reduced, as a PUT /mockserver/configuration would
        configuration.maxLogEntries(2);
        httpState.applyConfigurationUpdate();

        // then - the running event log is resized immediately, not merely recorded in the config
        assertThat(httpState.getMockServerLog().size(), is(2));
    }

    @Test
    public void shouldBoundEventLogBySubsequentAddsAfterMaxLogEntriesReduced() {
        // given - maxLogEntries reduced on an empty log
        configuration.maxLogEntries(3);
        httpState.applyConfigurationUpdate();

        // when - more entries than the new bound arrive
        for (int i = 0; i < 8; i++) {
            logger.logEvent(new LogEntry().setType(RECEIVED_REQUEST).setHttpRequest(request("/" + i)));
        }
        drainEventLog();

        // then - the new bound is enforced on the running log
        assertThat(httpState.getMockServerLog().size(), is(3));
    }

    @Test
    public void shouldApplyAndResizeOpenAiResponsesByteBudget() {
        // given - the server's own budget was applied when it started
        assertThat(OpenAiResponsesStore.getInstance().getMaxSizeInBytes(), is(configuration.maxOpenAiResponsesSizeInBytes()));
        String body = "{\"tools\":\"" + new String(new char[10_000]).replace('\0', 't') + "\"}";
        for (int i = 0; i < 4; i++) {
            OpenAiResponsesStore.getInstance().put(new OpenAiResponsesStore.StoredResponse("resp_" + i, ParsedConversation.empty(), body));
        }
        assertThat(OpenAiResponsesStore.getInstance().size(), is(4));

        // when - the budget is lowered, as a PUT /mockserver/configuration would
        configuration.maxOpenAiResponsesSizeInBytes(25_000L);
        httpState.applyConfigurationUpdate();

        // then - the least recently used responses are evicted from the running store immediately
        assertThat(OpenAiResponsesStore.getInstance().getMaxSizeInBytes(), is(25_000L));
        assertThat(OpenAiResponsesStore.getInstance().size(), is(2));
        assertThat(OpenAiResponsesStore.getInstance().get("resp_3").isPresent(), is(true));
        assertThat(OpenAiResponsesStore.getInstance().get("resp_0").isPresent(), is(false));
    }

    @Test
    public void shouldResizeExpectationStoreWhenMaxExpectationsReduced() {
        // given - four expectations
        addExpectation("/one");
        addExpectation("/two");
        addExpectation("/three");
        addExpectation("/four");
        assertThat(httpState.getRequestMatchers().retrieveActiveExpectations(null), hasSize(4));

        // when - maxExpectations is reduced, as a PUT /mockserver/configuration would
        configuration.maxExpectations(2);
        httpState.applyConfigurationUpdate();

        // then - the eldest expectations are evicted from the running store immediately
        List<Expectation> remaining = httpState.getRequestMatchers().retrieveActiveExpectations(null);
        assertThat(remaining, hasSize(2));
        assertThat(
            remaining.stream().map(e -> ((HttpRequest) e.getHttpRequest()).getPath().getValue()).collect(Collectors.toList()),
            contains("/three", "/four")
        );
    }

    @Test
    public void shouldBoundExpectationStoreBySubsequentAddsAfterMaxExpectationsReduced() {
        // given - maxExpectations reduced on an empty store
        configuration.maxExpectations(2);
        httpState.applyConfigurationUpdate();

        // when - more expectations than the new bound are added
        addExpectation("/one");
        addExpectation("/two");
        addExpectation("/three");

        // then - the new bound is enforced on the running store
        assertThat(httpState.getRequestMatchers().retrieveActiveExpectations(null), hasSize(2));
    }

    @Test
    public void shouldResizeAuditStoreWhenControlPlaneAuditMaxEntriesChanged() {
        // given - a capacity distinct from whatever the singleton was constructed with. Deliberately
        // LARGER than the default so this test can never evict another test's audit entries.
        int newMaxSize = originalAuditMaxSize + 4321;

        // when
        configuration.controlPlaneAuditMaxEntries(newMaxSize);
        httpState.applyConfigurationUpdate();

        // then - the running audit store is resized, not just the Configuration field
        assertThat(AuditStore.getInstance().getMaxSize(), is(newMaxSize));
    }

    /**
     * Applies {@code supplied} exactly as {@code PUT /mockserver/configuration} does: mutate the
     * live Configuration, then reconcile.
     */
    private void put(ConfigurationDTO supplied) {
        logger.forgetCapturedEvents();
        supplied.applyTo(configuration);
        httpState.applyConfigurationUpdate(supplied);
    }

    @Test
    public void shouldWarnAndResetRingBufferSizeBecauseItIsFixedAtStartup() {
        // given - the value the disruptor was actually built with
        int inForce = httpState.getMockServerLog().getRingBufferSizeInForce();
        int supplied = inForce * 2;

        // when - a PUT explicitly supplies a different value
        put(new ConfigurationDTO().setRingBufferSize(supplied));

        // then - it is NOT silently accepted: a WARN names the property, the ignored value and the
        // value actually in force
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("ringBufferSize"));
        assertThat(warnings.get(0), containsString("fixed at startup"));
        assertThat(warnings.get(0), containsString(String.valueOf(supplied)));
        assertThat(warnings.get(0), containsString(String.valueOf(inForce)));

        // and - the configuration is reset to the truth, so the echoed response and a later
        // GET /mockserver/configuration do not report a value the server is not using
        assertThat(configuration.ringBufferSize(), is(inForce));
    }

    @Test
    public void shouldWarnAndResetMaxWebSocketExpectationsBecauseItIsFixedAtStartup() {
        // given
        int inForce = configuration.maxWebSocketExpectations();
        int supplied = inForce + 500;

        // when - a PUT explicitly supplies a different value
        put(new ConfigurationDTO().setMaxWebSocketExpectations(supplied));

        // then
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("maxWebSocketExpectations"));
        assertThat(warnings.get(0), containsString("fixed at startup"));
        assertThat(warnings.get(0), containsString(String.valueOf(supplied)));
        assertThat(warnings.get(0), containsString(String.valueOf(inForce)));
        assertThat(configuration.maxWebSocketExpectations(), is(inForce));
    }

    @Test
    public void shouldNotLetAPutWidenTheForwardProxyHostLists() {
        // given
        configuration.forwardProxyAllowedHosts("api.example.com").forwardProxyDeniedHosts("10.0.0.0/8");

        // when - a PUT tries to clear both lists
        put(new ConfigurationDTO().setForwardProxyAllowedHosts("").setForwardProxyDeniedHosts(""));

        // then - both stay in force, and each ignored value is reported
        assertThat(configuration.forwardProxyAllowedHosts(), is("api.example.com"));
        assertThat(configuration.forwardProxyDeniedHosts(), is("10.0.0.0/8"));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(2));
        assertThat(warnings.get(0), containsString("forwardProxyAllowedHosts is fixed at startup"));
        assertThat(warnings.get(1), containsString("forwardProxyDeniedHosts is fixed at startup"));
    }

    @Test
    public void shouldNotLetAPutChangeWhichHostsBypassTheUpstreamProxy() {
        // given - forwarded requests go through an upstream proxy except for one host
        configuration.noProxyHosts("internal.example");

        // when - a PUT tries to route every .com and .net destination around the proxy
        put(new ConfigurationDTO().setNoProxyHosts("*.com,*.net"));

        // then - the startup value stays in force, GET reports it, and the ignored value is reported
        assertThat(configuration.noProxyHosts(), is("internal.example"));
        assertThat(new ConfigurationDTO(configuration).getNoProxyHosts(), is("internal.example"));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("noProxyHosts is fixed at startup"));
        assertThat(warnings.get(0), containsString("the supplied value \"*.com,*.net\" has been IGNORED and noProxyHosts remains \"internal.example\""));
    }

    @Test
    public void shouldNotWarnWhenAPutEchoesTheNoProxyHostsInForce() {
        configuration.noProxyHosts("internal.example");

        put(new ConfigurationDTO().setNoProxyHosts("internal.example"));

        assertThat(logger.warnings(), hasSize(0));
    }

    @Test
    public void shouldNotLetAPutSwitchOffForwardProxyBlockPrivateNetworks() {
        // given - the SSRF guard is on
        configuration.forwardProxyBlockPrivateNetworks(true);

        // when - a PUT tries to switch it off
        put(new ConfigurationDTO().setForwardProxyBlockPrivateNetworks(false));

        // then - it stays on, and the ignored value is reported
        assertThat(configuration.forwardProxyBlockPrivateNetworks(), is(true));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("forwardProxyBlockPrivateNetworks is fixed at startup"));
        assertThat(warnings.get(0), containsString("the supplied value false has been IGNORED"));
    }

    @Test
    public void shouldNotLetAPutChangeControlPlaneBasicAuthentication() {
        // given
        String hash = org.mockserver.authentication.basic.PasswordHash.pbkdf2Sha256("in-force", 10_000);
        configuration.controlPlaneBasicAuthenticationRequired(true).controlPlaneBasicAuthenticationCredentials("admin:" + hash);

        // when - a PUT tries to switch Basic off, allow it without TLS and replace the users
        String replacement = "mallory:" + org.mockserver.authentication.basic.PasswordHash.pbkdf2Sha256("taken-over", 10_000);
        put(new ConfigurationDTO()
            .setControlPlaneBasicAuthenticationRequired(false)
            .setControlPlaneBasicAuthenticationAllowInsecure(true)
            .setControlPlaneBasicAuthenticationCredentials(replacement));

        // then - nothing changes, each ignored value is reported, and no password hash is logged
        assertThat(configuration.controlPlaneBasicAuthenticationRequired(), is(true));
        assertThat(configuration.controlPlaneBasicAuthenticationAllowInsecure(), is(false));
        assertThat(configuration.controlPlaneBasicAuthenticationCredentials(), is("admin:" + hash));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(3));
        assertThat(warnings.get(0), containsString("controlPlaneBasicAuthenticationRequired is fixed at startup"));
        assertThat(warnings.get(1), containsString("controlPlaneBasicAuthenticationAllowInsecure is fixed at startup"));
        assertThat(warnings.get(2), containsString("controlPlaneBasicAuthenticationCredentials is fixed at startup"));
        assertThat(String.join("\n", warnings), not(containsString("pbkdf2")));
    }

    @Test
    public void shouldNotLetAPutRepointTheStaticFilesDirectory() {
        // given
        configuration.staticFilesDirectory("__files");

        // when
        put(new ConfigurationDTO().setStaticFilesDirectory("/"));

        // then
        assertThat(configuration.staticFilesDirectory(), is("__files"));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("staticFilesDirectory is fixed at startup"));
    }

    @Test
    public void shouldNotLetAPutWidenTheTemplateSystemValueAllowList() {
        // given
        configuration.templateSystemValueAllowList("APP_REGION");

        // when
        put(new ConfigurationDTO().setTemplateSystemValueAllowList("*,AWS_SECRET_ACCESS_KEY"));

        // then
        assertThat(configuration.templateSystemValueAllowList(), is("APP_REGION"));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("templateSystemValueAllowList is fixed at startup"));
    }

    @Test
    public void shouldLogWhatTemplatesCanReadEachTimeAServerStarts() {
        for (int start = 0; start < 2; start++) {
            // given
            Configuration startupConfiguration = configuration().logLevel(Level.WARN).templateSystemValueAllowList("APP_*,MOCKSERVER_*");
            CapturingMockServerLogger startupLogger = new CapturingMockServerLogger(startupConfiguration);

            // when
            HttpState started = new HttpState(startupConfiguration, startupLogger, mock(Scheduler.class));
            try {
                // then
                List<String> warnings = startupLogger.warnings();
                assertThat(warnings.stream().filter(warning -> warning.contains("templates can read")).count(), is(1L));
                assertThat(warnings.stream().filter(warning -> warning.contains("MockServer's own settings")).count(), is(1L));
            } finally {
                started.stop();
            }
        }
    }

    @Test
    public void shouldNotLetAPutAddRemoveOrReconfigureExtensions() {
        // given
        configuration.extensions("com.example.Loaded").extensionScanning(false).extensionStartTimeoutMillis(1000L)
            .extensionListenerQueueSize(100).extensionListenerMaxBytes(1_000_000L)
            .extensionHelperTimeoutMillis(500L).extensionHelperMaxInFlight(8)
            .extensionDataPlaneMaxInFlight(4).extensionDataPlaneTimeoutMillis(750L);

        // when
        put(new ConfigurationDTO().setExtensions("com.example.Injected").setExtensionScanning(true).setExtensionStartTimeoutMillis(5L)
            .setExtensionListenerQueueSize(1).setExtensionListenerMaxBytes(1L)
            .setExtensionHelperTimeoutMillis(60_000L).setExtensionHelperMaxInFlight(10_000)
            .setExtensionDataPlaneMaxInFlight(10_000).setExtensionDataPlaneTimeoutMillis(60_000L));

        // then
        assertThat(configuration.extensions(), is("com.example.Loaded"));
        assertThat(configuration.extensionScanning(), is(false));
        assertThat(configuration.extensionStartTimeoutMillis(), is(1000L));
        assertThat(configuration.extensionListenerQueueSize(), is(100));
        assertThat(configuration.extensionListenerMaxBytes(), is(1_000_000L));
        assertThat(configuration.extensionHelperTimeoutMillis(), is(500L));
        assertThat(configuration.extensionHelperMaxInFlight(), is(8));
        assertThat(configuration.extensionDataPlaneMaxInFlight(), is(4));
        assertThat(configuration.extensionDataPlaneTimeoutMillis(), is(750L));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(9));
        assertThat(warnings.get(0), containsString("extensions is fixed at startup"));
        assertThat(warnings.get(1), containsString("extensionScanning is fixed at startup"));
        assertThat(warnings.get(2), containsString("extensionStartTimeoutMillis is fixed at startup"));
        assertThat(warnings.get(3), containsString("extensionListenerQueueSize is fixed at startup"));
        assertThat(warnings.get(4), containsString("extensionListenerMaxBytes is fixed at startup"));
        assertThat(warnings.get(5), containsString("extensionHelperTimeoutMillis is fixed at startup"));
        assertThat(warnings.get(6), containsString("extensionHelperMaxInFlight is fixed at startup"));
        assertThat(warnings.get(7), containsString("extensionDataPlaneMaxInFlight is fixed at startup"));
        assertThat(warnings.get(8), containsString("extensionDataPlaneTimeoutMillis is fixed at startup"));
    }

    @Test
    public void shouldNotLetAPutReconfigureExtensionAdminRoutes() {
        // given
        configuration.extensionAdminMaxInFlight(4).extensionAdminTimeoutMillis(1000L);

        // when
        put(new ConfigurationDTO().setExtensionAdminMaxInFlight(1000).setExtensionAdminTimeoutMillis(5L));

        // then
        assertThat(configuration.extensionAdminMaxInFlight(), is(4));
        assertThat(configuration.extensionAdminTimeoutMillis(), is(1000L));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(2));
        assertThat(warnings.get(0), containsString("extensionAdminMaxInFlight is fixed at startup"));
        assertThat(warnings.get(1), containsString("extensionAdminTimeoutMillis is fixed at startup"));
    }

    @Test
    public void shouldNotWarnWhenExtensionPropertiesAreEchoedBackUnchanged() {
        // given - GET then PUT of the whole configuration of a server with extensions
        configuration.extensions("com.example.Loaded").extensionScanning(true);

        // when
        put(new ConfigurationDTO(configuration));

        // then
        assertThat(logger.warnings(), is(empty()));
        assertThat(configuration.extensions(), is("com.example.Loaded"));
    }

    @Test
    public void shouldNotLetAPutRemoveExtensions() {
        // given
        configuration.extensions("com.example.Loaded");

        // when
        put(new ConfigurationDTO().setExtensions(""));

        // then
        assertThat(configuration.extensions(), is("com.example.Loaded"));
        assertThat(logger.warnings(), hasSize(1));
    }

    @Test
    public void shouldNotLetAPutTurnOnRawNetworkTrafficLogging() {
        // given
        configuration.logRawNetworkTraffic(false);

        // when
        put(new ConfigurationDTO().setLogRawNetworkTraffic(true));

        // then
        assertThat(configuration.logRawNetworkTraffic(), is(false));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("logRawNetworkTraffic is fixed at startup"));
    }

    @Test
    public void shouldNotLetAPutRepointWhereTheServerWritesFiles() {
        // given
        configuration
            .auditLogFile("in-force/audit.ndjson")
            .persistedExpectationsPath("in-force/expectations.json")
            .persistedRecordedExpectationsPath("in-force/recorded-expectations.json")
            .persistedRecordedRequestsPath("in-force/recorded-requests.ndjson")
            .memoryUsageCsvDirectory("in-force/csv")
            .directoryToSaveDynamicSSLCertificate("in-force/certificates")
            .grpcProtocPath("in-force/protoc");

        // when - a PUT tries to send every file the server writes somewhere else, and to run another protoc
        put(new ConfigurationDTO()
            .setAuditLogFile("/extensions/audit.ndjson")
            .setPersistedExpectationsPath("/extensions/expectations.json")
            .setPersistedRecordedExpectationsPath("/extensions/recorded-expectations.json")
            .setPersistedRecordedRequestsPath("/extensions/recorded-requests.ndjson")
            .setMemoryUsageCsvDirectory("/extensions")
            .setDirectoryToSaveDynamicSSLCertificate("/extensions")
            .setGrpcProtocPath("/tmp/evil"));

        // then - every value in force is unchanged and each ignored value is reported once
        assertThat(configuration.auditLogFile(), is("in-force/audit.ndjson"));
        assertThat(configuration.persistedExpectationsPath(), is("in-force/expectations.json"));
        assertThat(configuration.persistedRecordedExpectationsPath(), is("in-force/recorded-expectations.json"));
        assertThat(configuration.persistedRecordedRequestsPath(), is("in-force/recorded-requests.ndjson"));
        assertThat(configuration.memoryUsageCsvDirectory(), is("in-force/csv"));
        assertThat(configuration.directoryToSaveDynamicSSLCertificate(), is("in-force/certificates"));
        assertThat(configuration.grpcProtocPath(), is("in-force/protoc"));
        List<String> warnings = logger.warnings();
        assertThat(warnings, hasSize(7));
        assertThat(warnings.get(0), containsString("auditLogFile is fixed at startup"));
        assertThat(warnings.get(0), containsString("\"/extensions/audit.ndjson\" has been IGNORED and auditLogFile remains \"in-force/audit.ndjson\""));
        assertThat(warnings.get(1), containsString("persistedExpectationsPath is fixed at startup"));
        assertThat(warnings.get(2), containsString("persistedRecordedExpectationsPath is fixed at startup"));
        assertThat(warnings.get(3), containsString("persistedRecordedRequestsPath is fixed at startup"));
        assertThat(warnings.get(4), containsString("memoryUsageCsvDirectory is fixed at startup"));
        assertThat(warnings.get(5), containsString("directoryToSaveDynamicSSLCertificate is fixed at startup"));
        assertThat(warnings.get(6), containsString("grpcProtocPath is fixed at startup"));
    }

    private static final String CONTROL_PLANE_CA_CHAIN = "org/mockserver/authentication/mtls/ca.pem";

    private void startWithControlPlaneTrustInForce() {
        configuration
            .controlPlaneTLSMutualAuthenticationRequired(true)
            .controlPlaneTLSMutualAuthenticationCAChain(CONTROL_PLANE_CA_CHAIN)
            .controlPlaneJWTAuthenticationRequired(false)
            .controlPlaneJWTAuthenticationJWKSource("in-force/jwks.json")
            .controlPlaneJWTAuthenticationExpectedAudience("in-force-audience")
            .controlPlaneJWTAuthenticationMatchingClaims(java.util.Collections.singletonMap("iss", "in-force"))
            .controlPlaneJWTAuthenticationRequiredClaims(java.util.Collections.singleton("sub"))
            .controlPlaneOidcAuthenticationRequired(false)
            .controlPlaneOidcIssuer("https://in-force.example")
            .controlPlaneOidcJwksUri("https://in-force.example/jwks")
            .controlPlaneOidcAudience("in-force")
            .controlPlaneOidcRequiredScopes(java.util.Collections.singleton("in-force"))
            .controlPlaneOidcScopeClaim("scope")
            .controlPlaneAuthorizationEnabled(false)
            .controlPlaneScopeMapping(java.util.Collections.singletonMap("team", org.mockserver.authentication.authorization.ControlPlaneRole.READ))
            .controlPlaneAuditEnabled(true)
            .dashboardAnalyticsEndpoint("https://in-force.example/analytics");
    }

    @Test
    public void shouldNotLetAPutChangeWhoMayUseTheControlPlaneOrWhatIsAudited() {
        // given - control-plane mTLS in force, and its handler built as a first control-plane request would
        startWithControlPlaneTrustInForce();
        org.mockserver.authentication.AuthenticationHandler handlerInForce = httpState.getControlPlaneAuthenticationHandler();
        assertThat(handlerInForce, org.hamcrest.Matchers.instanceOf(org.mockserver.authentication.mtls.MTLSAuthenticationHandler.class));

        // when - a PUT tries to switch mTLS off, trust another CA and key set, and turn auditing off
        put(new ConfigurationDTO()
            .setControlPlaneTLSMutualAuthenticationRequired(false)
            .setControlPlaneTLSMutualAuthenticationCAChain("org/mockserver/authentication/mtls/separateca/ca.pem")
            .setControlPlaneJWTAuthenticationRequired(true)
            .setControlPlaneJWTAuthenticationJWKSource("https://attacker.example/jwks")
            .setControlPlaneJWTAuthenticationExpectedAudience("attacker")
            .setControlPlaneJWTAuthenticationMatchingClaims(java.util.Collections.singletonMap("iss", "attacker"))
            .setControlPlaneJWTAuthenticationRequiredClaims(java.util.Collections.emptySet())
            .setControlPlaneOidcAuthenticationRequired(true)
            .setControlPlaneOidcIssuer("https://attacker.example")
            .setControlPlaneOidcJwksUri("https://attacker.example/jwks")
            .setControlPlaneOidcAudience("attacker")
            .setControlPlaneOidcRequiredScopes(java.util.Collections.emptySet())
            .setControlPlaneOidcScopeClaim("groups")
            .setControlPlaneAuthorizationEnabled(true)
            .setControlPlaneScopeMapping(java.util.Collections.singletonMap("team", org.mockserver.authentication.authorization.ControlPlaneRole.ADMIN))
            .setControlPlaneAuditEnabled(false)
            .setDashboardAnalyticsEndpoint("https://attacker.example/beacon"));

        // then - every value in force is unchanged and GET reports it
        ConfigurationDTO get = new ConfigurationDTO(configuration);
        assertThat(get.getControlPlaneTLSMutualAuthenticationRequired(), is(true));
        assertThat(get.getControlPlaneTLSMutualAuthenticationCAChain(), is(CONTROL_PLANE_CA_CHAIN));
        assertThat(get.getControlPlaneJWTAuthenticationRequired(), is(false));
        assertThat(get.getControlPlaneJWTAuthenticationJWKSource(), is("in-force/jwks.json"));
        assertThat(get.getControlPlaneJWTAuthenticationExpectedAudience(), is("in-force-audience"));
        assertThat(get.getControlPlaneJWTAuthenticationMatchingClaims(), is(java.util.Collections.singletonMap("iss", "in-force")));
        assertThat(get.getControlPlaneJWTAuthenticationRequiredClaims(), is(java.util.Collections.singleton("sub")));
        assertThat(get.getControlPlaneOidcAuthenticationRequired(), is(false));
        assertThat(get.getControlPlaneOidcIssuer(), is("https://in-force.example"));
        assertThat(get.getControlPlaneOidcJwksUri(), is("https://in-force.example/jwks"));
        assertThat(get.getControlPlaneOidcAudience(), is("in-force"));
        assertThat(get.getControlPlaneOidcRequiredScopes(), is(java.util.Collections.singleton("in-force")));
        assertThat(get.getControlPlaneOidcScopeClaim(), is("scope"));
        assertThat(get.getControlPlaneAuthorizationEnabled(), is(false));
        assertThat(get.getControlPlaneScopeMapping(), is(java.util.Collections.singletonMap("team", org.mockserver.authentication.authorization.ControlPlaneRole.READ)));
        assertThat(get.getControlPlaneAuditEnabled(), is(true));
        assertThat(get.getDashboardAnalyticsEndpoint(), is("https://in-force.example/analytics"));

        // and - the derived authentication handler was not rebuilt
        assertThat(httpState.getControlPlaneAuthenticationHandler(), org.hamcrest.Matchers.sameInstance(handlerInForce));

        // and - each ignored value is reported once, in order
        List<String> warnings = logger.warnings();
        List<String> expected = java.util.Arrays.asList(
            "controlPlaneTLSMutualAuthenticationRequired", "controlPlaneTLSMutualAuthenticationCAChain",
            "controlPlaneJWTAuthenticationRequired", "controlPlaneJWTAuthenticationJWKSource", "controlPlaneJWTAuthenticationExpectedAudience",
            "controlPlaneJWTAuthenticationMatchingClaims", "controlPlaneJWTAuthenticationRequiredClaims",
            "controlPlaneOidcAuthenticationRequired", "controlPlaneOidcIssuer", "controlPlaneOidcJwksUri", "controlPlaneOidcAudience",
            "controlPlaneOidcRequiredScopes", "controlPlaneOidcScopeClaim",
            "controlPlaneAuthorizationEnabled", "controlPlaneScopeMapping",
            "controlPlaneAuditEnabled", "dashboardAnalyticsEndpoint");
        assertThat(String.join("\n", warnings), warnings, hasSize(expected.size()));
        for (int i = 0; i < expected.size(); i++) {
            assertThat(warnings.get(i), containsString(expected.get(i) + " is fixed at startup"));
        }
        assertThat(warnings.get(3), containsString("\"https://attacker.example/jwks\" has been IGNORED and controlPlaneJWTAuthenticationJWKSource remains \"in-force/jwks.json\""));
        assertThat(warnings.get(15), containsString("false has been IGNORED and controlPlaneAuditEnabled remains true"));
    }

    @Test
    public void shouldClearTheHeldLlmApiKeyWhenAPutChangesTheLlmBaseUrlWithoutOne() {
        // given
        configuration.llmProvider("OPENAI").llmBaseUrl("https://in-force.example").llmApiKey("sk-held");

        // when - a PUT repoints the backend and supplies no key
        put(new ConfigurationDTO().setLlmBaseUrl("https://elsewhere.example"));

        // then - the new destination applies but the held key is not carried to it
        assertThat(configuration.llmBaseUrl(), is("https://elsewhere.example"));
        assertThat(configuration.llmApiKey(), is(""));
        assertThat(new ConfigurationDTO(configuration).getLlmApiKey(), is(org.hamcrest.Matchers.nullValue()));
        assertThat(logger.warnings(), hasSize(1));
        assertThat(logger.warnings().get(0), containsString("llmApiKey has been CLEARED"));
        assertThat(logger.warnings().get(0), not(containsString("sk-held")));
    }

    @Test
    public void shouldClearTheHeldLlmApiKeyWhenAPutEchoesItMaskedWithANewLlmBaseUrl() {
        // given - a client GETs the configuration (the key comes back masked), edits the base URL and PUTs it all back
        configuration.llmProvider("OPENAI").llmBaseUrl("https://in-force.example").llmApiKey("sk-held");
        com.fasterxml.jackson.databind.ObjectMapper objectMapper = org.mockserver.serialization.ObjectMapperFactory.createObjectMapper();
        ConfigurationDTO edited;
        try {
            String getBody = objectMapper.writeValueAsString(new ConfigurationDTO(configuration));
            edited = objectMapper.readValue(getBody, ConfigurationDTO.class).setLlmBaseUrl("https://elsewhere.example");
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new AssertionError(e);
        }
        assertThat(edited.getLlmApiKeyRawValue(), is(org.mockserver.configuration.ConfigurationProperties.REDACTED_VALUE));

        // when
        put(edited);

        // then - the mask is not a key, so the held one is cleared rather than sent to the new host
        assertThat(configuration.llmBaseUrl(), is("https://elsewhere.example"));
        assertThat(configuration.llmApiKey(), is(""));
        assertThat(logger.warnings(), hasSize(1));
        assertThat(logger.warnings().get(0), containsString("llmApiKey has been CLEARED"));
    }

    @Test
    public void shouldClearTheHeldLlmApiKeyWhenAPutChangesTheProviderAndNoBaseUrlIsSet() {
        // given - no base URL, so the provider's own host is the destination
        configuration.llmProvider("OPENAI").llmBaseUrl("").llmApiKey("sk-held");

        // when
        put(new ConfigurationDTO().setLlmProvider("ANTHROPIC"));

        // then
        assertThat(configuration.llmProvider(), is("ANTHROPIC"));
        assertThat(configuration.llmApiKey(), is(""));
        assertThat(logger.warnings(), hasSize(1));
    }

    @Test
    public void shouldKeepTheHeldLlmApiKeyWhenTheDestinationDoesNotChange() {
        // given - an explicit base URL: a provider change alone does not change the host
        configuration.llmProvider("OPENAI").llmBaseUrl("https://in-force.example").llmApiKey("sk-held");

        // when
        put(new ConfigurationDTO().setLlmProvider("openai").setLlmBaseUrl(" https://in-force.example ").setLlmModel("gpt-4o"));
        put(new ConfigurationDTO().setLlmProvider("OLLAMA"));

        // then
        assertThat(configuration.llmApiKey(), is("sk-held"));
        assertThat(logger.warnings(), is(empty()));
    }

    @Test
    public void shouldApplyBothWhenAPutSuppliesANewLlmBaseUrlAndKey() {
        // given
        configuration.llmProvider("OPENAI").llmBaseUrl("https://in-force.example").llmApiKey("sk-held");

        // when
        put(new ConfigurationDTO().setLlmBaseUrl("https://elsewhere.example").setLlmApiKey("sk-new"));

        // then
        assertThat(configuration.llmBaseUrl(), is("https://elsewhere.example"));
        assertThat(configuration.llmApiKey(), is("sk-new"));
        assertThat(logger.warnings(), is(empty()));
    }

    @Test
    public void shouldNotWarnWhenInitOnlyPropertiesAreEchoedBackUnchanged() {
        // given - the whole-blob PUT case: a client GETs the configuration and PUTs it back
        // verbatim, including the init-only properties. That must not produce noise. Non-default values prove
        // the GET reports the value in force, not a default.
        configuration
            .auditLogFile("in-force/audit.ndjson")
            .persistedExpectationsPath("in-force/expectations.json")
            .persistedRecordedExpectationsPath("in-force/recorded-expectations.json")
            .persistedRecordedRequestsPath("in-force/recorded-requests.ndjson")
            .memoryUsageCsvDirectory("in-force/csv")
            .directoryToSaveDynamicSSLCertificate("in-force/certificates")
            .grpcProtocPath("in-force/protoc")
            .recordedBodyFilesDirectory("in-force/bodies")
            .llmProvider("OPENAI")
            .llmBaseUrl("https://in-force.example")
            .llmApiKey("sk-held");
        startWithControlPlaneTrustInForce();
        ConfigurationDTO wholeBlob = new ConfigurationDTO(configuration);

        // when
        put(wholeBlob);

        // then
        assertThat(logger.warnings(), is(empty()));
        assertThat(configuration.ringBufferSize(), is(httpState.getMockServerLog().getRingBufferSizeInForce()));
        assertThat(configuration.llmApiKey(), is("sk-held"));
    }

    @Test
    public void shouldWarnWhenPutLowersTlsPosture() {
        // given - a secure starting posture
        configuration
            .tlsMutualAuthenticationRequired(true)
            .forwardProxyTLSX509CertificatesTrustManagerType(org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager.JVM)
            .forwardProxyTLSHostnameVerificationEnabled(true);
        logger.forgetCapturedEvents();

        // when - a PUT downgrades trust to ANY, turns mTLS off and turns host name verification off
        ConfigurationDTO supplied = new ConfigurationDTO()
            .setForwardProxyTLSX509CertificatesTrustManagerType("ANY")
            .setTlsMutualAuthenticationRequired(false)
            .setForwardProxyTLSHostnameVerificationEnabled(false);
        // audit runs against the pre-change configuration, exactly as the PUT handler orders it
        httpState.warnIfLoweringTlsPosture(supplied);

        // then - a single audit WARN names each downgrade
        assertThat(logger.warnings(), hasSize(1));
        String warning = logger.warnings().get(0);
        assertThat(warning, containsString("lowers or alters security posture"));
        assertThat(warning, containsString("-> ANY"));
        assertThat(warning, containsString("tlsMutualAuthenticationRequired true -> false"));
        assertThat(warning, containsString("forwardProxyTLSHostnameVerificationEnabled true -> false"));
    }

    @Test
    public void shouldNotWarnWhenPutDoesNotLowerTlsPosture() {
        // given - a secure starting posture
        configuration
            .tlsMutualAuthenticationRequired(true)
            .forwardProxyTLSX509CertificatesTrustManagerType(org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager.JVM);
        logger.forgetCapturedEvents();

        // when - a PUT that keeps mTLS on and TIGHTENS nothing about TLS (raising trust is not a downgrade)
        ConfigurationDTO supplied = new ConfigurationDTO()
            .setTlsMutualAuthenticationRequired(true)
            .setMaxLogEntries(50);
        httpState.warnIfLoweringTlsPosture(supplied);

        // then - no posture WARN
        assertThat(logger.warnings(), is(empty()));
    }

    @Test
    public void shouldNotWarnAboutRingBufferSizeWhenOnlyMaxLogEntriesSupplied() {
        // given - ringBufferSize DERIVES from maxLogEntries when not set explicitly, so changing
        // maxLogEntries alone moves the resolved ringBufferSize. That must not be reported as an
        // ignored ringBufferSize change - the client never asked for one - and must not pin
        // ringBufferSize to a literal value (which would break the derivation permanently).
        // when
        put(new ConfigurationDTO().setMaxLogEntries(50));

        // then
        assertThat(logger.warnings(), is(empty()));
    }

    @Test
    public void shouldApplyPutOverloadLimitsToTheRunningScheduler() {
        // given - a real asynchronous scheduler behind the running state, with room for many delayed responses
        configuration.maxPendingDelayedResponses(100);
        Scheduler scheduler = new Scheduler(configuration, logger, false);
        httpState.stop();
        httpState = new HttpState(configuration, logger, scheduler);
        try {
            java.util.concurrent.atomic.AtomicInteger refused = new java.util.concurrent.atomic.AtomicInteger();
            Runnable delayedResponse = Scheduler.rejectable(() -> {
            }, refused::incrementAndGet);
            scheduler.schedule(delayedResponse, false, org.mockserver.model.Delay.seconds(30));
            scheduler.schedule(delayedResponse, false, org.mockserver.model.Delay.seconds(30));
            assertThat(refused.get(), is(0));

            // when - PUT /mockserver/configuration lowers the limit below the backlog
            put(new ConfigurationDTO().setMaxPendingDelayedResponses(2));
            scheduler.schedule(delayedResponse, false, org.mockserver.model.Delay.seconds(30));

            // then - the running scheduler enforces the new limit at once
            assertThat(refused.get(), is(1));
            assertThat(scheduler.getPendingDelayedResponseCount(), is(2));
        } finally {
            scheduler.shutdown();
        }
    }
}

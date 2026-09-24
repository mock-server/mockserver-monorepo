package org.mockserver.serialization.model;

import org.junit.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * Layer B of the configuration-reachability guard: every RISKY configuration property must be
 * consciously classified as either enforcement-verified or enforcement-exempt.
 *
 * <h2>Why this exists</h2>
 * <p>{@link ConfigurationDTOTest} proves the {@code Configuration} &lt;-&gt; {@code ConfigurationDTO}
 * mirror is complete, but it never leaves that pair — a property can round-trip perfectly and still
 * never reach an enforcement site. Layer A (the bytecode call-site guard in {@code mockserver-netty})
 * catches the mechanical version of that failure. This layer catches the human version: a property
 * that nobody has ever checked actually does anything.
 *
 * <h2>The rule</h2>
 * <p>Enumeration is REFLECTIVE, so it cannot go stale — it reuses
 * {@link ConfigurationDTOTest#discoverProperties()}, the same source of truth as the round-trip guard.
 * Only the classification is manual. Every property matching {@link #RISK_HEURISTIC} must appear in
 * EXACTLY ONE of {@link #ENFORCEMENT_VERIFIED} or {@link #ENFORCEMENT_EXEMPT}.
 *
 * <p><strong>Omission fails closed.</strong> Adding a new {@code somethingEnabled} or {@code maxSomething}
 * property without classifying it breaks the build. That is the point: the default answer to "does this
 * new limit/flag actually do anything?" should be "prove it", not silence.
 *
 * <h2>Reading the two maps</h2>
 * <ul>
 *   <li>{@link #ENFORCEMENT_VERIFIED} — property name to the test that proves setting the value ON A
 *       CONFIGURATION INSTANCE changes observable behaviour. A getter round-trip does NOT qualify.</li>
 *   <li>{@link #ENFORCEMENT_EXEMPT} — property name to a documented reason. Reasons beginning
 *       {@code NO BEHAVIOURAL TEST YET} are acknowledged debt, not settled exemptions; they are
 *       deliberately greppable so the backlog is visible rather than buried.</li>
 * </ul>
 */
public class ConfigurationEnforcementClassificationTest {

    /**
     * Properties whose name suggests they gate, cap, or redact something — i.e. where being silently
     * unenforced is a correctness or security problem rather than a cosmetic one.
     */
    private static final Pattern RISK_HEURISTIC =
        Pattern.compile("^max.*|.*Enabled$|.*Budget.*|.*Threshold.*|.*Limit.*|^redact.*|^slo.*|^chaos.*");

    /**
     * Property to the test proving an instance-set value changes observable behaviour.
     */
    private static final Map<String, String> ENFORCEMENT_VERIFIED = new TreeMap<>();

    static {
        // ---- end-to-end through PUT /mockserver/configuration (Layer C) ----
        ENFORCEMENT_VERIFIED.put("maxRequestBodySize",
            "org.mockserver.configuration.ConfigurationRestApiEnforcementIntegrationTest#shouldEnforceMaxRequestBodySizeSetOverTheConfigurationEndpoint");
        ENFORCEMENT_VERIFIED.put("wasmEnabled",
            "org.mockserver.configuration.ConfigurationRestApiEnforcementIntegrationTest#shouldEnforceWasmEnabledSetOverTheConfigurationEndpoint");
        ENFORCEMENT_VERIFIED.put("redactSecretsInLog",
            "org.mockserver.configuration.ConfigurationRestApiEnforcementIntegrationTest#shouldEnforceRedactSecretsInLogSetOverTheConfigurationEndpoint");

        // ---- chaos / SLO ----
        ENFORCEMENT_VERIFIED.put("chaosAutoHaltEnabled",
            "org.mockserver.mock.action.http.ChaosAutoHaltMonitorTest#shouldHaltWhenEnabledOnConfigurationInstanceButDisabledStatically");
        ENFORCEMENT_VERIFIED.put("chaosAutoHaltErrorThreshold",
            "org.mockserver.mock.action.http.ChaosAutoHaltMonitorTest#shouldUseErrorThresholdFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("chaosAutoHaltWindowMillis",
            "org.mockserver.mock.action.http.ChaosAutoHaltMonitorTest#shouldUseWindowMillisFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("sloTrackingEnabled",
            "org.mockserver.slo.SloSampleStoreTest#shouldRecordWhenTrackingEnabledOnConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("sloWindowMaxSamples",
            "org.mockserver.slo.SloSampleStoreTest#shouldBoundSamplesByMaxSamplesFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("sloWindowRetentionMillis",
            "org.mockserver.slo.SloSampleStoreTest#shouldEvictByRetentionFromConfigurationInstance");

        // ---- limits and budgets ----
        // wired independently on master (GrpcFrameCodec / IncrementalGrpcFrameDecoder both prefer the
        // instance value and fall back to the static store), with a behavioural test that proves an
        // instance-set limit actually rejects an oversized frame.
        ENFORCEMENT_VERIFIED.put("maxGrpcMessageSize",
            "org.mockserver.grpc.GrpcMessageSizeConfigurationTest#shouldEnforceTheLimitFromAConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("llmCostBudgetUsd",
            "org.mockserver.configuration.ConfigurationInstanceEnforcementTest#shouldEnforceLlmCostBudgetFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("maxLlmConversationBodySize",
            "org.mockserver.configuration.ConfigurationInstanceEnforcementTest#shouldEnforceMaxLlmConversationBodySizeFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("rateLimitMaxNamedQuotas",
            "org.mockserver.configuration.ConfigurationInstanceEnforcementTest#shouldEnforceRateLimitMaxNamedQuotasFromConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("maxEventLogSizeInBytes",
            "org.mockserver.log.MockServerEventLogCaptureTest#shouldCapRetainedEntriesByByteBudget");
        // certificate-hardening SAN cap (Wave 1): the enforcement site addSubjectAlternativeName(..) reads
        // the instance value, so an instance-set cap bounds the SAN set and evicts oldest-dynamic-first —
        // proven behaviourally rather than merely round-tripped through a getter.
        ENFORCEMENT_VERIFIED.put("maxSubjectAlternativeNames",
            "org.mockserver.socket.tls.CertificateHardeningResilienceTest#shouldEvictOldestDynamicSubjectAlternativeNameFirstAndNeverEvictConfigured");
        // both resized in place on the running server by HttpState#applyConfigurationUpdate (the event-log
        // deque and the expectation store respectively), so a PUT /mockserver/configuration that lowers them
        // evicts immediately — proven end-to-end here rather than merely round-tripped through a getter.
        ENFORCEMENT_VERIFIED.put("maxLogEntries",
            "org.mockserver.mock.HttpStateConfigurationUpdateTest#shouldResizeEventLogWhenMaxLogEntriesReduced");
        ENFORCEMENT_VERIFIED.put("maxExpectations",
            "org.mockserver.mock.HttpStateConfigurationUpdateTest#shouldResizeExpectationStoreWhenMaxExpectationsReduced");
        ENFORCEMENT_VERIFIED.put("maxExpectationsSizeInBytes",
            "org.mockserver.mock.RequestMatchersStateBackendTest#byteBudgetEvictsLargeExpectationsAndStaysUnderBudget");
        ENFORCEMENT_VERIFIED.put("maxLoggedBodyBytes",
            "org.mockserver.log.MockServerEventLogCaptureTest#shouldPersistFullBodyToHookWhileTruncatingInMemoryCopy");
        ENFORCEMENT_VERIFIED.put("maximumNumberOfRequestToReturnInVerificationFailure",
            "org.mockserver.log.MockServerEventLogRequestLogEntryVerificationTest#shouldFailVerificationWithLimitedReturnedRequestsViaConfiguration");
        ENFORCEMENT_VERIFIED.put("maxSocketTimeoutInMillis",
            "org.mockserver.httpclient.netty.NettyHttpClientConnectionPoolTest#shouldTimeOutAStalledReusedPooledConnectionInsteadOfHanging");
        // consumed when the forward client's HTTP/1.1 pipeline is built (it sizes the aggregator), so it
        // cannot be re-read per request — but an instance-set value still changes observable behaviour:
        // an upstream body over the limit fails the forward with a 502 instead of being relayed.
        ENFORCEMENT_VERIFIED.put("maxResponseBodySize",
            "org.mockserver.netty.integration.proxy.MaxResponseBodySizeIntegrationTest#shouldRejectUpstreamResponseBodyOverTheConfiguredMaxResponseBodySize");
        ENFORCEMENT_VERIFIED.put("forwardProxyCircuitBreakerFailureThreshold",
            "org.mockserver.mock.action.http.HttpForwardActionResilienceTest#shouldOpenCircuitAfterConsecutiveFailuresAndFailFast");

        // ---- feature gates ----
        ENFORCEMENT_VERIFIED.put("closestMatchHintEnabled",
            "org.mockserver.mock.action.http.HttpActionHandlerClosestMatchHintTest#whenEnabled_nearMiss_headerNamesExpectationAndField");
        ENFORCEMENT_VERIFIED.put("clusterEnabled",
            "org.mockserver.state.infinispan.ClusteredTwoNodeTest#expectationWrittenOnNodeAShouldBeVisibleOnNodeB");
        ENFORCEMENT_VERIFIED.put("clusterSharedTimesEnabled",
            "org.mockserver.mock.RequestMatchersStateBackendTest#clusteredLimitedTimesFallsBackToNodeLocalWhenSharedTimesDisabled");
        ENFORCEMENT_VERIFIED.put("controlPlaneAuditEnabled",
            "org.mockserver.mock.HttpStateAuditEndpointTest#returnsEntriesNewestFirst");
        ENFORCEMENT_VERIFIED.put("controlPlaneAuthorizationEnabled",
            "org.mockserver.mock.HttpStateAuthorizationTest#authorizationDisabledDoesNotForbid");
        ENFORCEMENT_VERIFIED.put("dnsEnabled",
            "org.mockserver.netty.EpollTransportIntegrationTest#shouldBootWithDnsEnabledAndNativeTransport");
        ENFORCEMENT_VERIFIED.put("forwardConnectionPoolEnabled",
            "org.mockserver.httpclient.netty.NettyHttpClientConnectionPoolTest#shouldReuseSingleConnectionForSequentialBurstWhenPoolingEnabled");
        ENFORCEMENT_VERIFIED.put("forwardProxyCircuitBreakerEnabled",
            "org.mockserver.mock.action.http.ForwardCircuitBreakerTest#shouldAllowAllRequestsWhenDisabled");
        ENFORCEMENT_VERIFIED.put("forwardProxyHttp2Enabled",
            "org.mockserver.mock.action.http.HttpForwardActionHttp2Test#shouldPreserveHttp2WhenFlagEnabledAndInboundIsHttp2");
        // Wave 3 item 1: an instance-set value flips HTTPS endpoint identification (host name verification)
        // on the outbound client engine for the JVM/CUSTOM validating trust managers
        ENFORCEMENT_VERIFIED.put("forwardProxyTLSHostnameVerificationEnabled",
            "org.mockserver.socket.tls.NettySslContextFactoryTest#shouldEnableHttpsEndpointIdentificationForJvmTrustWhenHostnameVerificationEnabledOnConfigurationInstance");
        ENFORCEMENT_VERIFIED.put("grpcBidiStreamingEnabled",
            "org.mockserver.netty.http3.Http3GrpcStreamingIntegrationTest#shouldHandleBidiStreamingGrpcOverHttp3");
        ENFORCEMENT_VERIFIED.put("http2Enabled",
            "org.mockserver.socket.tls.NettySslContextFactoryTest#shouldNotAdvertiseHttp2ViaAlpnWhenHttp2Disabled");
        ENFORCEMENT_VERIFIED.put("http3ConnectUdpEnabled",
            "org.mockserver.netty.http3.Http3ConnectUdpIntegrationTest#shouldRelayWhenTargetIsInAllowlist");
        ENFORCEMENT_VERIFIED.put("llmMetricsEnabled",
            "org.mockserver.metrics.LlmMetricsTest#shouldNotIncrementWhenLlmMetricsDisabled");
        ENFORCEMENT_VERIFIED.put("loadGenerationEnabled",
            "org.mockserver.mock.HttpStateLoadScenarioEndpointTest#startReturns403WhenGenerationDisabled");
        ENFORCEMENT_VERIFIED.put("metricsEnabled",
            "org.mockserver.metrics.MetricsHandlerTest#shouldReflectOriginWhenMetricsEnabled");
        ENFORCEMENT_VERIFIED.put("perExpectationMetricsEnabled",
            "org.mockserver.metrics.PerExpectationMetricsTest#propertyOnRegistersTheCounter");
        ENFORCEMENT_VERIFIED.put("redactSecretsInRecordedExpectations",
            "org.mockserver.mock.RecordedExpectationRedactionHttpStateTest#shouldRedactRecordedSecretsWhenFlagOn");
        ENFORCEMENT_VERIFIED.put("streamingResponsesEnabled",
            "org.mockserver.codec.StreamingAwareHttpObjectAggregatorTest#shouldStreamWhenDisableResponseStreamingAttributeIsNotSet");
        ENFORCEMENT_VERIFIED.put("transparentProxyEnabled",
            "org.mockserver.netty.proxy.TransparentProxyHandlerTest#shouldSetRemoteSocketWhenOriginalDstResolved");
    }

    /**
     * Property to a documented reason it is not enforcement-verified.
     *
     * <p>Two distinct kinds of entry live here, and the distinction matters:
     * <ul>
     *   <li><strong>Structural exemptions</strong> — the property genuinely cannot have a
     *       "change it at runtime and observe the difference" test: it is consumed once at bind time,
     *       it is init-only by design, or it has no server-side read site at all.</li>
     *   <li><strong>{@code NO BEHAVIOURAL TEST YET}</strong> — acknowledged debt. Layer A confirms the
     *       value is read through the {@link org.mockserver.configuration.Configuration} instance, so it
     *       is REACHABLE, but nothing yet proves the behaviour it is supposed to drive. Discharge these
     *       by writing the test and moving the entry to {@link #ENFORCEMENT_VERIFIED}.</li>
     * </ul>
     */
    private static final Map<String, String> ENFORCEMENT_EXEMPT = new TreeMap<>();

    static {
        // ---- structural: consumed during pipeline/server construction, not re-read per request ----
        ENFORCEMENT_EXEMPT.put("maxChunkSize",
            "consumed when the HTTP codec is installed during pipeline construction; there is no per-request "
                + "read site to observe a runtime change at");
        ENFORCEMENT_EXEMPT.put("maxHeaderSize",
            "consumed when the HTTP codec is installed during pipeline construction; there is no per-request "
                + "read site to observe a runtime change at");
        ENFORCEMENT_EXEMPT.put("maxInitialLineLength",
            "consumed when the HTTP codec is installed during pipeline construction; there is no per-request "
                + "read site to observe a runtime change at");
        ENFORCEMENT_EXEMPT.put("grpcEnabled",
            "bind-time protocol selection: decides which handlers are installed as the server binds, so it "
                + "cannot take effect on an already-bound server");
        ENFORCEMENT_EXEMPT.put("mcpEnabled",
            "bind-time protocol selection: decides which handlers are installed as the server binds, so it "
                + "cannot take effect on an already-bound server");

        // ---- structural: init-only by design, flagged as such by the server itself ----
        ENFORCEMENT_EXEMPT.put("maxWebSocketExpectations",
            "init-only property: pushed into the static LocalCallbackRegistry once by the HttpState "
                + "constructor and reported through warnInitOnlyProperty, so changing it post-start is "
                + "explicitly unsupported");

        // ---- structural: no server-side enforcement site exists ----
        ENFORCEMENT_EXEMPT.put("dashboardAnalyticsEnabled",
            "no Java read site exists: the value is only serialized out over the configuration endpoint and "
                + "consumed by the dashboard UI, so there is no server behaviour to assert");
        ENFORCEMENT_EXEMPT.put("maxFutureTimeoutInMillis",
            "drives await timeouts in the test-support module (mockserver-integration-testing), not server "
                + "request handling; it has no production enforcement site");

        // ---- acknowledged debt: reachable (Layer A) but behaviour not yet asserted ----
        ENFORCEMENT_EXEMPT.put("connectionLifecycleChaosEnabled",
            "NO BEHAVIOURAL TEST YET — read through the Configuration instance, but no test asserts that "
                + "disabling it suppresses lifecycle fault injection");
        ENFORCEMENT_EXEMPT.put("driftDetectionEnabled",
            "NO BEHAVIOURAL TEST YET — DriftDetectionConfigTest re-implements the production gate rather than "
                + "exercising HttpActionHandler, so it would pass even if the call site read the static store");
        ENFORCEMENT_EXEMPT.put("driftAlertSeverityThreshold",
            "NO BEHAVIOURAL TEST YET — no test asserts the threshold changes which drift alerts are emitted");
        ENFORCEMENT_EXEMPT.put("driftAlertWebhookEnabled",
            "NO BEHAVIOURAL TEST YET — no test asserts the flag gates drift webhook delivery");
        ENFORCEMENT_EXEMPT.put("driftResponseTimeThresholdMs",
            "NO BEHAVIOURAL TEST YET — no test asserts the threshold changes which responses are flagged as drifted");
        ENFORCEMENT_EXEMPT.put("driftSemanticAnalysisEnabled",
            "NO BEHAVIOURAL TEST YET — no test asserts the flag gates semantic drift analysis");
        ENFORCEMENT_EXEMPT.put("maxStreamingCaptureBytes",
            "NO BEHAVIOURAL TEST YET — no test asserts streaming capture is truncated at an instance-set limit");
        ENFORCEMENT_EXEMPT.put("slowRequestThresholdMillis",
            "NO BEHAVIOURAL TEST YET — only a getter round-trip exists; the production read site is "
                + "NettyHttpClient, and nothing asserts the slow-request classification follows the value");

        // ---- acknowledged debt: newly reachable via Configuration/ConfigurationDTO, enforcement sites
        // ---- still read the static ConfigurationProperties store and are wired separately
        ENFORCEMENT_EXEMPT.put("llmInferUsageEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but the token-usage inference gate is not yet asserted to follow an instance-set value");
        ENFORCEMENT_EXEMPT.put("llmSemanticMatchingEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts the semanticMatch predicate gate follows an instance-set value");
        ENFORCEMENT_EXEMPT.put("otelMetricsEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts OTLP metric export follows an instance-set value");
        ENFORCEMENT_EXEMPT.put("otelTracesEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts GenAI span emission follows an instance-set value");
        ENFORCEMENT_EXEMPT.put("prometheusRemoteWriteEnabled",
            "NO BEHAVIOURAL TEST YET — reachable through the Configuration instance and the configuration "
                + "DTO, but no test asserts remote-write export follows an instance-set value");
    }

    /**
     * Write-only credential properties: settable over the control plane, masked on read.
     *
     * <p>None of these match {@link #RISK_HEURISTIC}, so the classification guard above would say
     * nothing about them. That silence is the gap this constant closes: a credential's security
     * property is not "is it enforced?" but "is it masked on read?", which is asserted by
     * {@link ConfigurationDTOCredentialMaskingTest}. The list is duplicated there deliberately —
     * if a new credential is added to one and not the other,
     * {@link #shouldKeepTheWriteOnlyCredentialListInSyncWithTheMaskingGuard()} fails.
     *
     * <p>Two shapes of credential are covered. Three properties are credentials in their
     * <em>entirety</em> and are masked whole. Two carry credentials <em>embedded inside</em> a
     * structured value and are masked per field, by
     * {@code ConfigurationProperties.redactSensitiveValue(..)} — the one rule shared by this endpoint,
     * {@code GET /mockserver/config}, {@code --print-config} and the startup property-file log dump:
     * <ul>
     *   <li>{@code prometheusRemoteWriteHeaders} — an arbitrary {@code k=v,k2=v2} header list; the
     *       value of each credential-bearing header name ({@code Authorization}, {@code Api-Key}, …)
     *       is redacted and every other header is returned untouched.</li>
     *   <li>{@code llmBackendsConfig} — documented as a <em>path</em> to a backends JSON file (the
     *       secrets live in that file, which the configuration API never returns), so a path is
     *       returned unchanged; a value that is itself a JSON document has each {@code apiKey}-shaped
     *       field redacted as defence-in-depth.</li>
     * </ul>
     * Because a partially-masked value is still mostly real configuration, the masking guard asserts
     * the exact expected masked form for these two, not merely the presence of the mask.
     */
    static final java.util.Set<String> WRITE_ONLY_CREDENTIALS = new TreeSet<>(java.util.Arrays.asList(
        "llmApiKey",
        "llmBackendsConfig",
        "prometheusRemoteWriteBasicAuthPassword",
        "prometheusRemoteWriteBearerToken",
        "prometheusRemoteWriteHeaders"
    ));

    @Test
    public void shouldKeepTheWriteOnlyCredentialListInSyncWithTheMaskingGuard() {
        assertThat("the write-only credential list here and the one the masking guard actually asserts "
                + "against have diverged — a credential is masked-but-unclassified or classified-but-unmasked",
            new TreeSet<>(ConfigurationDTOCredentialMaskingTest.WRITE_ONLY_CREDENTIALS),
            is(WRITE_ONLY_CREDENTIALS));
    }

    @Test
    public void shouldNotClassifyWriteOnlyCredentialsAsEnforcementExempt() {
        // a credential must never be quietly parked in ENFORCEMENT_EXEMPT as a way of avoiding the
        // masking guard — the two mechanisms are complementary, not alternatives
        java.util.Set<String> parked = new TreeSet<>(WRITE_ONLY_CREDENTIALS);
        parked.retainAll(ENFORCEMENT_EXEMPT.keySet());

        assertThat("write-only credentials found in ENFORCEMENT_EXEMPT — they are covered by the masking "
                + "guard instead, so an exemption entry here only hides them: " + parked,
            parked, is(empty()));
    }

    @Test
    public void shouldClassifyEveryRiskyConfigurationPropertyAsVerifiedOrExempt() {
        List<String> riskyProperties = new ArrayList<>();
        for (ConfigurationDTOTest.PropertyAccessor accessor : ConfigurationDTOTest.discoverProperties()) {
            if (RISK_HEURISTIC.matcher(accessor.name).matches()) {
                riskyProperties.add(accessor.name);
            }
        }

        // sanity: reflective enumeration must actually be finding the risky set, so a regression that
        // silently stops discovering properties cannot make this guard pass vacuously
        assertThat("risk heuristic should match a substantial set of Configuration properties",
            riskyProperties.size(), greaterThan(40));

        List<String> unclassified = new ArrayList<>();
        List<String> doubleClassified = new ArrayList<>();
        for (String property : riskyProperties) {
            boolean verified = ENFORCEMENT_VERIFIED.containsKey(property);
            boolean exempt = ENFORCEMENT_EXEMPT.containsKey(property);
            if (verified && exempt) {
                doubleClassified.add(property);
            } else if (!verified && !exempt) {
                unclassified.add(property);
            }
        }

        assertThat("risky configuration properties with NO enforcement classification. Each gates, caps or "
                + "redacts something, so being silently unenforced is a correctness or security problem. "
                + "Add each to ENFORCEMENT_VERIFIED (naming the test that proves an instance-set value "
                + "changes observable behaviour) or to ENFORCEMENT_EXEMPT (with a documented reason): "
                + unclassified,
            unclassified, is(empty()));

        assertThat("properties classified as BOTH verified and exempt — pick one: " + doubleClassified,
            doubleClassified, is(empty()));
    }

    @Test
    public void shouldNotRetainClassificationsForPropertiesThatNoLongerExist() {
        java.util.Set<String> riskyProperties = new TreeSet<>();
        for (ConfigurationDTOTest.PropertyAccessor accessor : ConfigurationDTOTest.discoverProperties()) {
            if (RISK_HEURISTIC.matcher(accessor.name).matches()) {
                riskyProperties.add(accessor.name);
            }
        }

        java.util.Set<String> stale = new TreeSet<>();
        stale.addAll(ENFORCEMENT_VERIFIED.keySet());
        stale.addAll(ENFORCEMENT_EXEMPT.keySet());
        stale.removeAll(riskyProperties);

        assertThat("classifications retained for properties that no longer exist or no longer match the risk "
                + "heuristic — delete them so the maps stay an accurate picture of what is actually guarded: "
                + stale,
            stale, is(empty()));
    }

    /**
     * Properties whose evidence test lives OUTSIDE {@code mockserver-core}, and so can only be validated
     * by the source scan in {@link #shouldReferenceEnforcementTestsThatActuallyExist()} — the referenced
     * class is not on this module's test classpath.
     *
     * <p>This is an anti-vacuity ratchet, in the spirit of {@code ConfigurationCallSiteGuardTest}, and it
     * is asserted in BOTH directions:
     * <ul>
     *   <li>a cross-module pointer that the scan did NOT resolve means the scan has stopped working (or
     *       has silently narrowed to a subset of modules) — which would otherwise let dangling evidence
     *       pass green, the exact fail-open this set exists to prevent;</li>
     *   <li>a resolved-by-scan property missing from this set means a NEW cross-module pointer was added
     *       without being declared here, so the ratchet cannot quietly drift.</li>
     * </ul>
     * Move an entry out of this set only when its evidence test genuinely moves into {@code mockserver-core}.
     */
    private static final Set<String> CROSS_MODULE_EVIDENCE = new TreeSet<>(java.util.Arrays.asList(
        "clusterEnabled",
        "dnsEnabled",
        "grpcBidiStreamingEnabled",
        "http3ConnectUdpEnabled",
        "maxRequestBodySize",
        "maxResponseBodySize",
        "redactSecretsInLog",
        "transparentProxyEnabled",
        "wasmEnabled"
    ));

    /**
     * Every {@code Class#method} pointer in {@link #ENFORCEMENT_VERIFIED} must resolve to a test method
     * that actually exists — otherwise the "evidence" for a risky property is a dangling reference and the
     * classification above is a claim nobody can check.
     *
     * <h2>Why this does not just use the classloader</h2>
     * <p>This test runs in {@code mockserver-core}, but several pointers name tests in SIBLING modules
     * ({@code mockserver-netty}, {@code mockserver-state-infinispan}) which are not on this module's test
     * classpath. Skipping those on {@link ClassNotFoundException} — as this guard previously did — meant
     * the most valuable pointers, the Layer C end-to-end ones, were NEVER validated: renaming, moving or
     * deleting them left a dangling pointer and the guard still passed green. A control that certifies
     * evidence it cannot see is worse than no control.
     *
     * <p>So an unloadable class falls back to locating its {@code .java} source under any module's
     * {@code src/test/java} and asserting that the file declares both the class and the referenced method.
     * Source is always present in the working tree, so this works in a full reactor build and when only
     * some modules are built. <strong>It fails closed:</strong> a pointer resolvable by NEITHER route is a
     * failure, never a silent skip.
     */
    @Test
    public void shouldReferenceEnforcementTestsThatActuallyExist() throws IOException {
        List<Path> moduleTestSourceRoots = moduleTestSourceRoots();

        // sanity: the source fallback must actually be able to see sibling modules, or every cross-module
        // pointer would "fail to resolve" for an environmental reason rather than a real one
        assertThat("guard must be able to see sibling modules' test sources under the mockserver reactor "
                + "root — resolved roots: " + moduleTestSourceRoots,
            moduleTestSourceRoots.size(), greaterThan(1));

        List<String> broken = new ArrayList<>();
        Set<String> resolvedOnClasspath = new TreeSet<>();
        Set<String> resolvedFromSource = new TreeSet<>();
        Set<String> modulesProvidingEvidence = new TreeSet<>();

        for (Map.Entry<String, String> entry : ENFORCEMENT_VERIFIED.entrySet()) {
            String property = entry.getKey();
            String reference = entry.getValue();
            int separator = reference.indexOf('#');
            assertThat("ENFORCEMENT_VERIFIED values must be Class#method references, got: " + reference,
                separator > 0, is(true));
            String className = reference.substring(0, separator);
            String methodName = reference.substring(separator + 1);

            Class<?> testClass;
            try {
                testClass = Class.forName(className);
            } catch (ClassNotFoundException | LinkageError e) {
                testClass = null;
            }

            if (testClass != null) {
                if (declaresMethod(testClass, methodName)) {
                    resolvedOnClasspath.add(property);
                } else {
                    broken.add(property + " -> " + reference + " (class is on this module's test classpath "
                        + "but declares no method named " + methodName + ")");
                }
                continue;
            }

            Path source = findTestSource(moduleTestSourceRoots, className);
            if (source == null) {
                broken.add(property + " -> " + reference + " (class is neither loadable from this module's "
                    + "test classpath nor present as " + className.replace('.', '/') + ".java under any "
                    + "module's src/test/java)");
                continue;
            }
            String simpleName = className.substring(className.lastIndexOf('.') + 1);
            String body = new String(Files.readAllBytes(source), StandardCharsets.UTF_8);
            if (!declaresType(body, simpleName)) {
                broken.add(property + " -> " + reference + " (" + source + " does not declare type " + simpleName + ")");
            } else if (!declaresVoidMethod(body, methodName)) {
                broken.add(property + " -> " + reference + " (" + source + " declares no method "
                    + methodName + " — the evidence has been renamed or deleted)");
            } else {
                resolvedFromSource.add(property);
                modulesProvidingEvidence.add(moduleNameOf(source));
            }
        }

        assertThat("ENFORCEMENT_VERIFIED points at test methods that do not exist — the evidence for these "
                + "properties has been renamed, moved or deleted, so they are no longer actually verified:\n  "
                + String.join("\n  ", broken) + "\n",
            broken, is(empty()));

        // anti-vacuity: both resolution routes must have done real work, or an empty scan would pass
        assertThat("classpath resolution should validate the bulk of the pointers, which name mockserver-core "
                + "tests — resolving almost none means reflection has silently stopped working",
            resolvedOnClasspath.size(), greaterThan(20));
        assertThat("the cross-module source scan resolved a different set of pointers than the declared "
                + "ratchet. If it resolved FEWER, the scan has stopped seeing sibling modules and dangling "
                + "evidence would pass green; if it resolved MORE, a new cross-module pointer was added "
                + "without declaring it in CROSS_MODULE_EVIDENCE",
            resolvedFromSource, is(CROSS_MODULE_EVIDENCE));
        assertThat("mockserver-netty evidence must be validated by the source scan",
            modulesProvidingEvidence, hasItem("mockserver-netty"));
        assertThat("mockserver-state-infinispan evidence must be validated by the source scan",
            modulesProvidingEvidence, hasItem("mockserver-state-infinispan"));
    }

    private static boolean declaresMethod(Class<?> testClass, String methodName) {
        for (Method method : testClass.getDeclaredMethods()) {
            if (method.getName().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static boolean declaresType(String source, String simpleName) {
        return Pattern.compile("\\b(class|interface|enum)\\s+" + Pattern.quote(simpleName) + "\\b")
            .matcher(source).find();
    }

    private static boolean declaresVoidMethod(String source, String methodName) {
        return Pattern.compile("\\bvoid\\s+" + Pattern.quote(methodName) + "\\s*\\(")
            .matcher(source).find();
    }

    /**
     * Resolve {@code a.b.C} to {@code <module>/src/test/java/a/b/C.java}, checking each module in turn.
     * Targeted rather than a walk of the repository, so the fallback stays cheap.
     */
    private static Path findTestSource(List<Path> moduleTestSourceRoots, String className) {
        String relative = className.replace('.', '/') + ".java";
        for (Path root : moduleTestSourceRoots) {
            Path candidate = root.resolve(relative);
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    /** {@code <mockserver>/<module>/src/test/java/...} -&gt; {@code <module>}. */
    private static String moduleNameOf(Path testSource) {
        return mockserverRoot().relativize(testSource).getName(0).toString();
    }

    /** Every {@code <module>/src/test/java} directory present under the {@code mockserver/} reactor root. */
    private static List<Path> moduleTestSourceRoots() throws IOException {
        try (Stream<Path> modules = Files.list(mockserverRoot())) {
            return modules
                .map(module -> module.resolve("src/test/java"))
                .filter(Files::isDirectory)
                .sorted()
                .collect(Collectors.toList());
        }
    }

    /**
     * Locate the {@code mockserver/} reactor root from THIS test class's own output directory, so the guard
     * works regardless of the working directory the build runs from. Anchored on the test classes — which
     * are always exploded and always inside the working tree — rather than on a main class, which resolves
     * into {@code ~/.m2} when a sibling module is built alone.
     */
    private static Path mockserverRoot() {
        URL location = ConfigurationEnforcementClassificationTest.class
            .getProtectionDomain().getCodeSource().getLocation();
        Path testClasses = Paths.get(location.getPath());
        // <mockserver>/mockserver-core/target/test-classes -> <mockserver>
        Path root = testClasses.getParent().getParent().getParent();
        if (!Files.isDirectory(root.resolve("mockserver-core/src/main/java"))) {
            throw new IllegalStateException("could not locate the mockserver reactor root from " + testClasses
                + " (resolved " + root + ")");
        }
        return root;
    }
}

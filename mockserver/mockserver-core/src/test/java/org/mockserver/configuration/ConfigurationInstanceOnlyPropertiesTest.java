package org.mockserver.configuration;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.is;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * Reachability guard for the 27 properties that used to exist ONLY on {@link ConfigurationProperties}, plus the
 * startup-only extension properties, which must be read through the {@link Configuration} instance too.
 *
 * <p>Before this was wired, each of these had no {@code Configuration} accessor and no
 * {@code ConfigurationDTO} field, so it was unreachable from the instance / DTO / REST routes BY
 * CONSTRUCTION — {@code PUT /mockserver/configuration} could not set it at all, and code holding a
 * {@code Configuration} could not read a per-instance value.
 *
 * <p>Two properties of the wiring are asserted for every one of them:
 * <ol>
 *   <li><strong>Instance value wins</strong> — a value set on the instance is what the getter returns.</li>
 *   <li><strong>Static fallback intact</strong> — an unset instance still resolves through to
 *       {@link ConfigurationProperties}, so adding the instance field did not orphan the existing
 *       system-property / env-var / property-file routes.</li>
 * </ol>
 *
 * <p>This test never mutates the static store, so it is safe to run in the parallel phase.
 */
public class ConfigurationInstanceOnlyPropertiesTest {

    /**
     * The 27 newly-wired properties, mapped to the setter parameter type. Credentials are included:
     * they are fully settable on the instance — only the WIRE representation is masked (see
     * {@code ConfigurationDTOCredentialMaskingTest}).
     */
    private static final Map<String, Class<?>> NEWLY_WIRED_PROPERTIES = new LinkedHashMap<>();

    static {
        NEWLY_WIRED_PROPERTIES.put("customJsonUnitMatchersClass", String.class);
        NEWLY_WIRED_PROPERTIES.put("fixtureBodyRedactFields", String.class);
        NEWLY_WIRED_PROPERTIES.put("llmBackendsConfig", String.class);
        NEWLY_WIRED_PROPERTIES.put("llmBaseUrl", String.class);
        NEWLY_WIRED_PROPERTIES.put("llmInferUsageEnabled", Boolean.class);
        NEWLY_WIRED_PROPERTIES.put("llmModel", String.class);
        NEWLY_WIRED_PROPERTIES.put("llmOptimisationMaxCalls", Integer.class);
        NEWLY_WIRED_PROPERTIES.put("llmProvider", String.class);
        NEWLY_WIRED_PROPERTIES.put("llmRequestTimeoutMillis", Long.class);
        NEWLY_WIRED_PROPERTIES.put("llmSemanticMatchingEnabled", Boolean.class);
        NEWLY_WIRED_PROPERTIES.put("llmVcrStrict", Boolean.class);
        NEWLY_WIRED_PROPERTIES.put("otelEndpoint", String.class);
        NEWLY_WIRED_PROPERTIES.put("otelMetricsEnabled", Boolean.class);
        NEWLY_WIRED_PROPERTIES.put("otelMetricsExportIntervalSeconds", Long.class);
        NEWLY_WIRED_PROPERTIES.put("otelMetricsTemporality", String.class);
        NEWLY_WIRED_PROPERTIES.put("otelTracesEnabled", Boolean.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteBasicAuthUsername", String.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteEnabled", Boolean.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteHeaders", String.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteIntervalSeconds", Long.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteProtocolVersion", String.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteUrl", String.class);
        NEWLY_WIRED_PROPERTIES.put("regexMatchingTimeoutMillis", Long.class);
        NEWLY_WIRED_PROPERTIES.put("xpathMatchingTimeoutMillis", Long.class);
        // startup-only extension properties: read through the instance, never settable over the control plane
        NEWLY_WIRED_PROPERTIES.put("extensions", String.class);
        NEWLY_WIRED_PROPERTIES.put("extensionScanning", Boolean.class);
        NEWLY_WIRED_PROPERTIES.put("extensionStartTimeoutMillis", Long.class);
        NEWLY_WIRED_PROPERTIES.put("extensionAdminMaxInFlight", Integer.class);
        NEWLY_WIRED_PROPERTIES.put("extensionAdminTimeoutMillis", Long.class);
        NEWLY_WIRED_PROPERTIES.put("extensionListenerQueueSize", Integer.class);
        NEWLY_WIRED_PROPERTIES.put("extensionListenerMaxBytes", Long.class);
        NEWLY_WIRED_PROPERTIES.put("extensionHelperTimeoutMillis", Long.class);
        NEWLY_WIRED_PROPERTIES.put("extensionHelperMaxInFlight", Integer.class);
        NEWLY_WIRED_PROPERTIES.put("extensionDataPlaneMaxInFlight", Integer.class);
        NEWLY_WIRED_PROPERTIES.put("extensionDataPlaneTimeoutMillis", Long.class);
        // write-only credentials
        NEWLY_WIRED_PROPERTIES.put("llmApiKey", String.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteBasicAuthPassword", String.class);
        NEWLY_WIRED_PROPERTIES.put("prometheusRemoteWriteBearerToken", String.class);
    }

    /**
     * Properties whose {@link ConfigurationProperties} accessor clamps the resolved value. The
     * {@link Configuration} getter MUST apply the same clamp, or the instance route becomes a way to
     * smuggle past a bound the static route enforces.
     */
    private static final List<String> CLAMPED_TO_MINIMUM_ONE_SECOND = Arrays.asList(
        "otelMetricsExportIntervalSeconds",
        "prometheusRemoteWriteIntervalSeconds"
    );

    @Test
    public void shouldExposeEveryNewlyWiredPropertyAsAGetterAndFluentSetter() throws Exception {
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, Class<?>> property : NEWLY_WIRED_PROPERTIES.entrySet()) {
            try {
                Configuration.class.getMethod(property.getKey());
                Method setter = Configuration.class.getMethod(property.getKey(), property.getValue());
                if (!setter.getReturnType().equals(Configuration.class)) {
                    missing.add(property.getKey() + " (setter is not fluent)");
                }
            } catch (NoSuchMethodException e) {
                missing.add(property.getKey() + " (" + e.getMessage() + ")");
            }
        }

        assertThat("properties with no Configuration getter/fluent-setter pair — they remain unreachable "
                + "from the instance, DTO and REST routes: " + missing,
            missing, is(empty()));
        assertThat(NEWLY_WIRED_PROPERTIES.size(), is(38));
    }

    @Test
    public void shouldReturnTheValueSetOnTheConfigurationInstance() throws Exception {
        List<String> notHonoured = new ArrayList<>();
        for (Map.Entry<String, Class<?>> property : NEWLY_WIRED_PROPERTIES.entrySet()) {
            String name = property.getKey();
            Configuration configuration = configuration();
            Object value = distinctiveValueFor(name, property.getValue());

            Configuration.class.getMethod(name, property.getValue()).invoke(configuration, value);
            Object read = Configuration.class.getMethod(name).invoke(configuration);

            if (!value.equals(read)) {
                notHonoured.add(name + " (set <" + value + "> but read back <" + read + ">)");
            }
        }

        assertThat("newly-wired properties whose instance-set value was NOT honoured by the getter: "
                + notHonoured, notHonoured, is(empty()));
    }

    @Test
    public void shouldFallBackToTheStaticStoreWhenTheInstanceValueIsUnset() throws Exception {
        // adding an instance field must not orphan the existing system-property / env-var /
        // property-file routes: an unset instance still resolves through to ConfigurationProperties
        Configuration unset = configuration();

        List<String> broken = new ArrayList<>();
        for (String name : NEWLY_WIRED_PROPERTIES.keySet()) {
            Object viaInstance = Configuration.class.getMethod(name).invoke(unset);
            Object viaStatic = ConfigurationProperties.class.getMethod(name).invoke(null);
            if (!String.valueOf(viaInstance).equals(String.valueOf(viaStatic))) {
                broken.add(name + " (instance resolved <" + viaInstance + "> but the static store says <"
                    + viaStatic + ">)");
            }
        }

        assertThat("newly-wired properties whose unset instance does NOT fall back to the static store — "
                + "the existing property-file / env-var / system-property routes are orphaned: " + broken,
            broken, is(empty()));
    }

    @Test
    public void shouldApplyTheSameClampAsTheStaticAccessorOnInstanceSetValues() throws Exception {
        List<String> unclamped = new ArrayList<>();
        for (String name : CLAMPED_TO_MINIMUM_ONE_SECOND) {
            for (long outOfRange : new long[]{0L, -1L, Long.MIN_VALUE}) {
                Configuration configuration = configuration();
                Configuration.class.getMethod(name, Long.class).invoke(configuration, outOfRange);
                Object read = Configuration.class.getMethod(name).invoke(configuration);
                if (!Long.valueOf(1L).equals(read)) {
                    unclamped.add(name + " (set <" + outOfRange + "> resolved to <" + read + ">, expected 1)");
                }
            }
        }

        assertThat("interval properties where the instance route does NOT apply the clamp the static "
                + "accessor applies — a zero/negative export interval would reach the exporter: " + unclamped,
            unclamped, is(empty()));
    }

    private static Object distinctiveValueFor(String name, Class<?> type) {
        if (type.equals(Boolean.class)) {
            // every one of these defaults to false, so true is distinctive
            return Boolean.TRUE;
        }
        if (type.equals(Integer.class)) {
            return 4242;
        }
        if (type.equals(Long.class)) {
            return 424242L;
        }
        return "instance-only-value-" + name;
    }
}

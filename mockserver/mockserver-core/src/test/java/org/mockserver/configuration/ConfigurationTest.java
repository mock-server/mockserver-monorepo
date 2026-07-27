package org.mockserver.configuration;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.server.initialize.ExpectationInitializerExample;
import org.mockserver.socket.tls.ForwardProxyTLSX509CertificatesTrustManager;
import org.mockserver.socket.tls.KeyAndCertificateFactory;

import org.mockserver.log.model.LogEntry;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertThrows;
import static org.mockserver.configuration.ConfigurationProperties.logLevel;

public class ConfigurationTest {

    private Configuration configuration;

    @Before
    public void setupTest() {
        configuration = new Configuration();
        // ensure no leaked ringBufferSize override (in the cache-first ConfigurationProperties cache)
        // pollutes the default-resolution ring-buffer tests below
        clearRingBufferSizeOverride();
    }

    private static void clearRingBufferSizeOverride() {
        clearPropertyAndCache("mockserver.ringBufferSize");
    }

    private static void clearPropertyAndCache(String key) {
        System.clearProperty(key);
        // ConfigurationProperties resolves cache-first, so clearing the system property alone is not
        // enough — also drop the in-memory cache entry (mirror of the production clearProperty()).
        try {
            java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
            cacheField.setAccessible(true);
            Object cache = cacheField.get(null);
            if (cache instanceof Map) {
                ((Map<?, ?>) cache).remove(key);
            }
            java.lang.reflect.Field keysField = ConfigurationProperties.class.getDeclaredField("programmaticallySetKeys");
            keysField.setAccessible(true);
            Object keys = keysField.get(null);
            if (keys instanceof Set) {
                ((Set<?>) keys).remove(key);
            }
        } catch (Exception ignore) {
            // best effort — if the internals change, the System property clear above still helps
        }
    }

    private String tempFilePath() {
        try {
            return File.createTempFile("prefix", "suffix").getAbsolutePath();
        } catch (IOException ioe) {
            throw new RuntimeException(ioe.getMessage(), ioe);
        }
    }

    @Test
    public void shouldSetAndGetLogLevel() {
        String original = ConfigurationProperties.logLevel().name();
        try {
            // then - default value
            assertThat(configuration.logLevel().name(), anyOf(equalTo("INFO"), equalTo("ERROR")));

            // when - system property setter
            ConfigurationProperties.logLevel("TRACE");

            // then - system property getter
            assertThat(ConfigurationProperties.logLevel().name(), equalTo("TRACE"));
            assertThat(System.getProperty("mockserver.logLevel"), equalTo("TRACE"));
            assertThat(configuration.logLevel().name(), equalTo("TRACE"));

            // when - setter
            configuration.logLevel("DEBUG");

            // then - getter
            assertThat(configuration.logLevel().name(), equalTo("DEBUG"));

            // then - validate
            IllegalArgumentException illegalArgumentException = assertThrows(IllegalArgumentException.class, () -> logLevel("WRONG"));
            assertThat(illegalArgumentException.getMessage(), is("log level \"WRONG\" is not legal it must be one of SL4J levels: \"TRACE\", \"DEBUG\", \"INFO\", \"WARN\", \"ERROR\", \"OFF\", or the Java Logger levels: \"FINEST\", \"FINE\", \"INFO\", \"WARNING\", \"SEVERE\", \"OFF\""));
        } finally {
            ConfigurationProperties.logLevel(original);
        }
    }

    @Test
    public void shouldSetAndGetDisableSystemOut() {
        boolean original = ConfigurationProperties.disableSystemOut();
        try {
            // then - default value
            assertThat(configuration.disableSystemOut(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.disableSystemOut(true);

            // then - system property getter
            assertThat(ConfigurationProperties.disableSystemOut(), equalTo(true));
            assertThat(System.getProperty("mockserver.disableSystemOut"), equalTo("true"));
            assertThat(configuration.disableSystemOut(), equalTo(true));
            ConfigurationProperties.disableSystemOut(original);

            // when - setter
            configuration.disableSystemOut(true);

            // then - getter
            assertThat(configuration.disableSystemOut(), equalTo(true));
        } finally {
            ConfigurationProperties.disableSystemOut(original);
        }
    }

    @Test
    public void shouldSetAndGetDisableLogging() {
        boolean original = ConfigurationProperties.disableLogging();
        try {
            // then - default value
            assertThat(configuration.disableLogging(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.disableLogging(true);

            // then - system property getter
            assertThat(ConfigurationProperties.disableLogging(), equalTo(true));
            assertThat(System.getProperty("mockserver.disableLogging"), equalTo("true"));
            assertThat(configuration.disableLogging(), equalTo(true));
            ConfigurationProperties.disableLogging(original);

            // when - setter
            configuration.disableLogging(true);

            // then - getter
            assertThat(configuration.disableLogging(), equalTo(true));
        } finally {
            ConfigurationProperties.disableLogging(original);
        }
    }

    @Test
    public void shouldSetAndGetDetailedMatchFailures() {
        boolean original = ConfigurationProperties.detailedMatchFailures();
        try {
            // then - default value
            assertThat(configuration.detailedMatchFailures(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.detailedMatchFailures(false);

            // then - system property getter
            assertThat(ConfigurationProperties.detailedMatchFailures(), equalTo(false));
            assertThat(System.getProperty("mockserver.detailedMatchFailures"), equalTo("false"));
            assertThat(configuration.detailedMatchFailures(), equalTo(false));
            ConfigurationProperties.detailedMatchFailures(original);

            // when - setter
            configuration.detailedMatchFailures(false);

            // then - getter
            assertThat(configuration.detailedMatchFailures(), equalTo(false));
        } finally {
            ConfigurationProperties.detailedMatchFailures(original);
        }
    }

    @Test
    public void shouldSetAndGetCompactLogFormat() {
        boolean original = ConfigurationProperties.compactLogFormat();
        try {
            // then - default value
            assertThat(configuration.compactLogFormat(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.compactLogFormat(true);

            // then - system property getter
            assertThat(ConfigurationProperties.compactLogFormat(), equalTo(true));
            assertThat(System.getProperty("mockserver.compactLogFormat"), equalTo("true"));
            assertThat(configuration.compactLogFormat(), equalTo(true));
            ConfigurationProperties.compactLogFormat(original);

            // when - setter
            configuration.compactLogFormat(true);

            // then - getter
            assertThat(configuration.compactLogFormat(), equalTo(true));
        } finally {
            ConfigurationProperties.compactLogFormat(original);
        }
    }

    @Test
    public void shouldSetAndGetLaunchUIForLogLevelDebug() {
        boolean original = ConfigurationProperties.launchUIForLogLevelDebug();
        try {
            // then - default value
            assertThat(configuration.launchUIForLogLevelDebug(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.launchUIForLogLevelDebug(true);

            // then - system property getter
            assertThat(ConfigurationProperties.launchUIForLogLevelDebug(), equalTo(true));
            assertThat(System.getProperty("mockserver.launchUIForLogLevelDebug"), equalTo("true"));
            assertThat(configuration.launchUIForLogLevelDebug(), equalTo(true));
            ConfigurationProperties.launchUIForLogLevelDebug(original);

            // when - setter
            configuration.launchUIForLogLevelDebug(true);

            // then - getter
            assertThat(configuration.launchUIForLogLevelDebug(), equalTo(true));
        } finally {
            ConfigurationProperties.launchUIForLogLevelDebug(original);
        }
    }

    @Test
    public void shouldSetAndGetMetricsEnabled() {
        boolean original = ConfigurationProperties.metricsEnabled();
        try {
            // then - default value
            assertThat(configuration.metricsEnabled(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.metricsEnabled(true);

            // then - system property getter
            assertThat(ConfigurationProperties.metricsEnabled(), equalTo(true));
            assertThat(System.getProperty("mockserver.metricsEnabled"), equalTo("true"));
            assertThat(configuration.metricsEnabled(), equalTo(true));
            ConfigurationProperties.metricsEnabled(original);

            // when - setter
            configuration.metricsEnabled(true);

            // then - getter
            assertThat(configuration.metricsEnabled(), equalTo(true));
        } finally {
            ConfigurationProperties.metricsEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetDashboardAnalyticsEnabled() {
        boolean original = ConfigurationProperties.dashboardAnalyticsEnabled();
        try {
            // then - default value
            assertThat(configuration.dashboardAnalyticsEnabled(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.dashboardAnalyticsEnabled(false);

            // then - system property getter
            assertThat(ConfigurationProperties.dashboardAnalyticsEnabled(), equalTo(false));
            assertThat(System.getProperty("mockserver.dashboardAnalyticsEnabled"), equalTo("false"));
            assertThat(configuration.dashboardAnalyticsEnabled(), equalTo(false));
            ConfigurationProperties.dashboardAnalyticsEnabled(original);

            // when - setter
            configuration.dashboardAnalyticsEnabled(false);

            // then - getter
            assertThat(configuration.dashboardAnalyticsEnabled(), equalTo(false));
        } finally {
            ConfigurationProperties.dashboardAnalyticsEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetDashboardAnalyticsEndpoint() {
        String original = ConfigurationProperties.dashboardAnalyticsEndpoint();
        try {
            // then - default value
            assertThat(configuration.dashboardAnalyticsEndpoint(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.dashboardAnalyticsEndpoint("https://analytics.example.com");

            // then - system property getter
            assertThat(ConfigurationProperties.dashboardAnalyticsEndpoint(), equalTo("https://analytics.example.com"));
            assertThat(System.getProperty("mockserver.dashboardAnalyticsEndpoint"), equalTo("https://analytics.example.com"));
            assertThat(configuration.dashboardAnalyticsEndpoint(), equalTo("https://analytics.example.com"));
            ConfigurationProperties.dashboardAnalyticsEndpoint(original);

            // when - setter
            configuration.dashboardAnalyticsEndpoint("https://other.example.com");

            // then - getter
            assertThat(configuration.dashboardAnalyticsEndpoint(), equalTo("https://other.example.com"));
        } finally {
            ConfigurationProperties.dashboardAnalyticsEndpoint(original);
        }
    }

    @Test
    public void shouldSetAndGetDashboardAnalyticsKey() {
        String original = ConfigurationProperties.dashboardAnalyticsKey();
        try {
            // then - default value
            assertThat(configuration.dashboardAnalyticsKey(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.dashboardAnalyticsKey("phc_test_key");

            // then - system property getter
            assertThat(ConfigurationProperties.dashboardAnalyticsKey(), equalTo("phc_test_key"));
            assertThat(System.getProperty("mockserver.dashboardAnalyticsKey"), equalTo("phc_test_key"));
            assertThat(configuration.dashboardAnalyticsKey(), equalTo("phc_test_key"));
            ConfigurationProperties.dashboardAnalyticsKey(original);

            // when - setter
            configuration.dashboardAnalyticsKey("phc_other_key");

            // then - getter
            assertThat(configuration.dashboardAnalyticsKey(), equalTo("phc_other_key"));
        } finally {
            ConfigurationProperties.dashboardAnalyticsKey(original);
        }
    }

    @Test
    public void shouldSetAndGetDashboardAnalyticsDistribution() {
        String original = ConfigurationProperties.dashboardAnalyticsDistribution();
        try {
            // then - default value
            assertThat(configuration.dashboardAnalyticsDistribution(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.dashboardAnalyticsDistribution("docker");

            // then - system property getter
            assertThat(ConfigurationProperties.dashboardAnalyticsDistribution(), equalTo("docker"));
            assertThat(System.getProperty("mockserver.dashboardAnalyticsDistribution"), equalTo("docker"));
            assertThat(configuration.dashboardAnalyticsDistribution(), equalTo("docker"));
            ConfigurationProperties.dashboardAnalyticsDistribution(original);

            // when - setter
            configuration.dashboardAnalyticsDistribution("helm");

            // then - getter
            assertThat(configuration.dashboardAnalyticsDistribution(), equalTo("helm"));
        } finally {
            ConfigurationProperties.dashboardAnalyticsDistribution(original);
        }
    }

    @Test
    public void shouldSetAndGetChaosAutoHaltEnabled() {
        boolean original = ConfigurationProperties.chaosAutoHaltEnabled();
        try {
            // then - default value
            assertThat(configuration.chaosAutoHaltEnabled(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.chaosAutoHaltEnabled(true);

            // then - system property getter
            assertThat(ConfigurationProperties.chaosAutoHaltEnabled(), equalTo(true));
            assertThat(System.getProperty("mockserver.chaosAutoHaltEnabled"), equalTo("true"));
            assertThat(configuration.chaosAutoHaltEnabled(), equalTo(true));
            ConfigurationProperties.chaosAutoHaltEnabled(original);

            // when - setter
            configuration.chaosAutoHaltEnabled(true);

            // then - getter
            assertThat(configuration.chaosAutoHaltEnabled(), equalTo(true));
        } finally {
            ConfigurationProperties.chaosAutoHaltEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetChaosAutoHaltErrorThreshold() {
        long original = ConfigurationProperties.chaosAutoHaltErrorThreshold();
        try {
            // then - default value
            assertThat(configuration.chaosAutoHaltErrorThreshold(), equalTo(50L));

            // when - system property setter
            ConfigurationProperties.chaosAutoHaltErrorThreshold(100);

            // then - system property getter
            assertThat(ConfigurationProperties.chaosAutoHaltErrorThreshold(), equalTo(100L));
            assertThat(System.getProperty("mockserver.chaosAutoHaltErrorThreshold"), equalTo("100"));
            assertThat(configuration.chaosAutoHaltErrorThreshold(), equalTo(100L));
            ConfigurationProperties.chaosAutoHaltErrorThreshold(original);

            // when - setter
            configuration.chaosAutoHaltErrorThreshold(200L);

            // then - getter
            assertThat(configuration.chaosAutoHaltErrorThreshold(), equalTo(200L));
        } finally {
            ConfigurationProperties.chaosAutoHaltErrorThreshold(original);
        }
    }

    @Test
    public void shouldSetAndGetChaosAutoHaltWindowMillis() {
        long original = ConfigurationProperties.chaosAutoHaltWindowMillis();
        try {
            // then - default value
            assertThat(configuration.chaosAutoHaltWindowMillis(), equalTo(60_000L));

            // when - system property setter
            ConfigurationProperties.chaosAutoHaltWindowMillis(30_000);

            // then - system property getter
            assertThat(ConfigurationProperties.chaosAutoHaltWindowMillis(), equalTo(30_000L));
            assertThat(System.getProperty("mockserver.chaosAutoHaltWindowMillis"), equalTo("30000"));
            assertThat(configuration.chaosAutoHaltWindowMillis(), equalTo(30_000L));
            ConfigurationProperties.chaosAutoHaltWindowMillis(original);

            // when - setter
            configuration.chaosAutoHaltWindowMillis(120_000L);

            // then - getter
            assertThat(configuration.chaosAutoHaltWindowMillis(), equalTo(120_000L));
        } finally {
            ConfigurationProperties.chaosAutoHaltWindowMillis(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxExpectations() {
        int original = ConfigurationProperties.maxExpectations();
        try {
            // when - system property setter
            ConfigurationProperties.maxExpectations(10);

            // then - system property getter
            assertThat(ConfigurationProperties.maxExpectations(), equalTo(10));
            assertThat(System.getProperty("mockserver.maxExpectations"), equalTo("10"));
            assertThat(configuration.maxExpectations(), equalTo(10));

            // when - setter
            configuration.maxExpectations(20);

            // then - getter
            assertThat(configuration.maxExpectations(), equalTo(20));
        } finally {
            ConfigurationProperties.maxExpectations(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxLogEntries() {
        int original = ConfigurationProperties.maxLogEntries();
        try {
            // no explicit ring-buffer override (cleared in @Before): ring size is derived from
            // maxLogEntries (under the cap)

            // when - system property setter
            ConfigurationProperties.maxLogEntries(10);

            // then - system property getter
            assertThat(ConfigurationProperties.maxLogEntries(), equalTo(10));
            assertThat(System.getProperty("mockserver.maxLogEntries"), equalTo("10"));
            assertThat(configuration.maxLogEntries(), equalTo(10));

            // when - setter (small retention: ring still derived from maxLogEntries, under the cap)
            configuration.maxLogEntries(20);

            // then - getters
            assertThat(configuration.maxLogEntries(), equalTo(20));
            assertThat(configuration.ringBufferSize(), equalTo(32));

            // when - setter
            configuration.maxLogEntries(100);

            // then - ring buffer size
            assertThat(configuration.ringBufferSize(), equalTo(128));

            // when - setter
            configuration.maxLogEntries(1000);

            // then - ring buffer size
            assertThat(configuration.ringBufferSize(), equalTo(1024));
        } finally {
            ConfigurationProperties.maxLogEntries(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxEventLogSizeInBytes() {
        try {
            // default
            clearPropertyAndCache("mockserver.maxEventLogSizeInBytes");
            assertThat(ConfigurationProperties.maxEventLogSizeInBytes(), equalTo(0L));
            assertThat(new Configuration().maxEventLogSizeInBytes(), equalTo(0L));

            // system property -> property
            ConfigurationProperties.maxEventLogSizeInBytes(1048576L);
            assertThat(ConfigurationProperties.maxEventLogSizeInBytes(), equalTo(1048576L));
            assertThat(System.getProperty("mockserver.maxEventLogSizeInBytes"), equalTo("1048576"));
            assertThat(new Configuration().maxEventLogSizeInBytes(), equalTo(1048576L));

            // fluent setter -> property (instance value overrides the static one)
            assertThat(configuration.maxEventLogSizeInBytes(2097152L).maxEventLogSizeInBytes(), equalTo(2097152L));

            // validation: negative values clamp to 0 (>= 0, 0 = disabled)
            ConfigurationProperties.maxEventLogSizeInBytes(-5L);
            assertThat(ConfigurationProperties.maxEventLogSizeInBytes(), equalTo(0L));
        } finally {
            clearPropertyAndCache("mockserver.maxEventLogSizeInBytes");
        }
    }

    @Test
    public void shouldSetAndGetMaxLoggedBodyBytes() {
        try {
            // default
            clearPropertyAndCache("mockserver.maxLoggedBodyBytes");
            assertThat(ConfigurationProperties.maxLoggedBodyBytes(), equalTo(0));
            assertThat(new Configuration().maxLoggedBodyBytes(), equalTo(0));

            // system property -> property
            ConfigurationProperties.maxLoggedBodyBytes(4096);
            assertThat(ConfigurationProperties.maxLoggedBodyBytes(), equalTo(4096));
            assertThat(System.getProperty("mockserver.maxLoggedBodyBytes"), equalTo("4096"));
            assertThat(new Configuration().maxLoggedBodyBytes(), equalTo(4096));

            // fluent setter -> property (instance value overrides the static one)
            assertThat(configuration.maxLoggedBodyBytes(8192).maxLoggedBodyBytes(), equalTo(8192));

            // validation: negative values clamp to 0 (>= 0, 0 = unlimited)
            ConfigurationProperties.maxLoggedBodyBytes(-5);
            assertThat(ConfigurationProperties.maxLoggedBodyBytes(), equalTo(0));
        } finally {
            clearPropertyAndCache("mockserver.maxLoggedBodyBytes");
        }
    }

    @Test
    public void shouldSetAndGetPersistRecordedRequestsToDisk() {
        try {
            // default
            clearPropertyAndCache("mockserver.persistRecordedRequestsToDisk");
            assertThat(ConfigurationProperties.persistRecordedRequestsToDisk(), equalTo(false));
            assertThat(new Configuration().persistRecordedRequestsToDisk(), equalTo(false));

            // system property -> property
            ConfigurationProperties.persistRecordedRequestsToDisk(true);
            assertThat(ConfigurationProperties.persistRecordedRequestsToDisk(), equalTo(true));
            assertThat(System.getProperty("mockserver.persistRecordedRequestsToDisk"), equalTo("true"));
            assertThat(new Configuration().persistRecordedRequestsToDisk(), equalTo(true));

            // fluent setter -> property (instance value overrides the static one)
            assertThat(configuration.persistRecordedRequestsToDisk(false).persistRecordedRequestsToDisk(), equalTo(false));
        } finally {
            clearPropertyAndCache("mockserver.persistRecordedRequestsToDisk");
        }
    }

    @Test
    public void shouldSetAndGetBlobStoreRestoreTimeoutSeconds() {
        try {
            // default
            clearPropertyAndCache("mockserver.blobStoreRestoreTimeoutSeconds");
            assertThat(ConfigurationProperties.blobStoreRestoreTimeoutSeconds(), equalTo(10));
            assertThat(new Configuration().blobStoreRestoreTimeoutSeconds(), equalTo(10));

            // system property -> property
            ConfigurationProperties.blobStoreRestoreTimeoutSeconds(45);
            assertThat(ConfigurationProperties.blobStoreRestoreTimeoutSeconds(), equalTo(45));
            assertThat(System.getProperty("mockserver.blobStoreRestoreTimeoutSeconds"), equalTo("45"));
            assertThat(new Configuration().blobStoreRestoreTimeoutSeconds(), equalTo(45));

            // fluent setter -> property (instance value overrides the static one)
            assertThat(configuration.blobStoreRestoreTimeoutSeconds(0).blobStoreRestoreTimeoutSeconds(), equalTo(0));
        } finally {
            clearPropertyAndCache("mockserver.blobStoreRestoreTimeoutSeconds");
        }
    }

    @Test
    public void shouldRecogniseBlobStoreRestoreTimeoutSecondsEnvironmentVariableForm() {
        // The env-var form is what containerised deployments use, and it is advertised on the
        // consumer configuration page as MOCKSERVER_BLOB_STORE_RESTORE_TIMEOUT_SECONDS. It is
        // derived from the CONSTANT'S FIELD NAME (see ConfigurationProperties.enumerateRecognisedKeys),
        // so a constant renamed without renaming the documented variable silently stops resolving and
        // starts warning as an unknown key.
        assertThat(ConfigurationProperties.recognisedEnvironmentVariableKeys(),
            org.hamcrest.Matchers.hasItem("MOCKSERVER_BLOB_STORE_RESTORE_TIMEOUT_SECONDS"));
        assertThat(ConfigurationProperties.recognisedSystemPropertyKeys(),
            org.hamcrest.Matchers.hasItem("mockserver.blobStoreRestoreTimeoutSeconds"));
    }

    @Test
    public void shouldSetAndGetPersistedRecordedRequestsPath() {
        try {
            // default
            clearPropertyAndCache("mockserver.persistedRecordedRequestsPath");
            assertThat(ConfigurationProperties.persistedRecordedRequestsPath(), equalTo("recordedRequests.ndjson"));
            assertThat(new Configuration().persistedRecordedRequestsPath(), equalTo("recordedRequests.ndjson"));

            // system property -> property
            ConfigurationProperties.persistedRecordedRequestsPath("target/captured.ndjson");
            assertThat(ConfigurationProperties.persistedRecordedRequestsPath(), equalTo("target/captured.ndjson"));
            assertThat(System.getProperty("mockserver.persistedRecordedRequestsPath"), equalTo("target/captured.ndjson"));
            assertThat(new Configuration().persistedRecordedRequestsPath(), equalTo("target/captured.ndjson"));

            // fluent setter -> property (instance value overrides the static one)
            assertThat(configuration.persistedRecordedRequestsPath("target/other.ndjson").persistedRecordedRequestsPath(), equalTo("target/other.ndjson"));
        } finally {
            clearPropertyAndCache("mockserver.persistedRecordedRequestsPath");
        }
    }

    @Test
    public void shouldDecoupleRingBufferSizeFromMaxLogEntriesByDefault() {
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        try {
            // when - large retention is requested
            configuration.maxLogEntries(100000);

            // then - the ring is NOT slaved to retention: default ceiling is min(maxLogEntries, 16384)
            // rounded up to a power of two, so it no longer pre-allocates a 131072-slot ring.
            // 16384 is ALREADY a power of two, so rounding is a no-op and the cap is honoured
            // exactly. (This previously asserted 32768: the rounding helper returned the next power
            // STRICTLY greater than its input, so the documented 16384 cap actually allocated twice
            // as many slots, and a GET/PUT configuration round trip doubled the value each time.)
            assertThat(configuration.ringBufferSize(), equalTo(16384));
            assertThat(configuration.ringBufferSize(), not(equalTo(131072)));

            // when - retention below the cap, the ring still follows maxLogEntries (the computed
            // default is dynamic — not cached — so it re-resolves on each read)
            configuration.maxLogEntries(8000);
            assertThat(configuration.ringBufferSize(), equalTo(8192));
        } finally {
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
        }
    }

    @Test
    public void shouldSetAndGetRingBufferSize() {
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        try {
            configuration.maxLogEntries(100000);

            // when - explicit ConfigurationProperties (system property) setter
            ConfigurationProperties.ringBufferSize(1000);

            // then - honoured and rounded up to the next power of two
            assertThat(System.getProperty("mockserver.ringBufferSize"), equalTo("1000"));
            assertThat(configuration.ringBufferSize(), equalTo(1024));

            // when - explicit instance field setter (takes precedence over the property)
            configuration.ringBufferSize(2000);

            // then - honoured and rounded up to the next power of two, independent of maxLogEntries
            assertThat(configuration.ringBufferSize(), equalTo(2048));

            // when - field cleared, falls back to the property
            configuration.ringBufferSize(null);
            assertThat(configuration.ringBufferSize(), equalTo(1024));
        } finally {
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
            clearRingBufferSizeOverride();
        }
    }

    @Test
    public void shouldSetAndGetMaxWebSocketExpectations() {
        int original = ConfigurationProperties.maxWebSocketExpectations();
        try {
            // then - default value
            assertThat(configuration.maxWebSocketExpectations(), equalTo(1500));

            // when - system property setter
            ConfigurationProperties.maxWebSocketExpectations(10);

            // then - system property getter
            assertThat(ConfigurationProperties.maxWebSocketExpectations(), equalTo(10));
            assertThat(System.getProperty("mockserver.maxWebSocketExpectations"), equalTo("10"));
            assertThat(configuration.maxWebSocketExpectations(), equalTo(10));

            // when - setter
            configuration.maxWebSocketExpectations(20);

            // then - getter
            assertThat(configuration.maxWebSocketExpectations(), equalTo(20));
        } finally {
            ConfigurationProperties.maxWebSocketExpectations(original);
        }
    }

    @Test
    public void shouldSetAndGetOutputMemoryUsageCsv() {
        boolean original = ConfigurationProperties.outputMemoryUsageCsv();
        try {
            // then - default value
            assertThat(configuration.outputMemoryUsageCsv(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.outputMemoryUsageCsv(true);

            // then - system property getter
            assertThat(ConfigurationProperties.outputMemoryUsageCsv(), equalTo(true));
            assertThat(System.getProperty("mockserver.outputMemoryUsageCsv"), equalTo("true"));
            assertThat(configuration.outputMemoryUsageCsv(), equalTo(true));
            ConfigurationProperties.outputMemoryUsageCsv(original);

            // when - setter
            configuration.outputMemoryUsageCsv(true);

            // then - getter
            assertThat(configuration.outputMemoryUsageCsv(), equalTo(true));
        } finally {
            ConfigurationProperties.outputMemoryUsageCsv(original);
        }
    }

    @Test
    public void shouldSetAndGetMemoryUsageCsvDirectory() {
        String original = ConfigurationProperties.memoryUsageCsvDirectory();
        try {
            // then - default value
            assertThat(configuration.memoryUsageCsvDirectory(), equalTo("."));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.memoryUsageCsvDirectory(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.memoryUsageCsvDirectory(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.memoryUsageCsvDirectory"), equalTo(firstPath));
            assertThat(configuration.memoryUsageCsvDirectory(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.memoryUsageCsvDirectory(secondPath);

            // then - getter
            assertThat(configuration.memoryUsageCsvDirectory(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.memoryUsageCsvDirectory(original);
        }
    }

    @Test
    public void shouldSetAndGetNioEventLoopThreadCount() {
        int original = ConfigurationProperties.nioEventLoopThreadCount();
        try {
            // then - default value
            assertThat(configuration.nioEventLoopThreadCount(), equalTo(5));

            // when - system property setter
            ConfigurationProperties.nioEventLoopThreadCount(10);

            // then - system property getter
            assertThat(ConfigurationProperties.nioEventLoopThreadCount(), equalTo(10));
            assertThat(System.getProperty("mockserver.nioEventLoopThreadCount"), equalTo("10"));
            assertThat(configuration.nioEventLoopThreadCount(), equalTo(10));

            // when - setter
            configuration.nioEventLoopThreadCount(20);

            // then - getter
            assertThat(configuration.nioEventLoopThreadCount(), equalTo(20));
        } finally {
            ConfigurationProperties.nioEventLoopThreadCount(original);
        }
    }

    @Test
    public void shouldSetAndGetActionHandlerThreadCount() {
        int original = ConfigurationProperties.actionHandlerThreadCount();
        try {
            // then - default value
            assertThat(configuration.actionHandlerThreadCount(), equalTo(Math.max(5, Runtime.getRuntime().availableProcessors())));

            // when - system property setter
            ConfigurationProperties.actionHandlerThreadCount(10);

            // then - system property getter
            assertThat(ConfigurationProperties.actionHandlerThreadCount(), equalTo(10));
            assertThat(System.getProperty("mockserver.actionHandlerThreadCount"), equalTo("10"));
            assertThat(configuration.actionHandlerThreadCount(), equalTo(10));

            // when - setter
            configuration.actionHandlerThreadCount(20);

            // then - getter
            assertThat(configuration.actionHandlerThreadCount(), equalTo(20));
        } finally {
            ConfigurationProperties.actionHandlerThreadCount(original);
        }
    }

    @Test
    public void shouldSetAndGetWebSocketClientEventLoopThreadCount() {
        int original = ConfigurationProperties.webSocketClientEventLoopThreadCount();
        try {
            // then - default value
            assertThat(configuration.webSocketClientEventLoopThreadCount(), equalTo(5));

            // when - system property setter
            ConfigurationProperties.webSocketClientEventLoopThreadCount(10);

            // then - system property getter
            assertThat(ConfigurationProperties.webSocketClientEventLoopThreadCount(), equalTo(10));
            assertThat(System.getProperty("mockserver.webSocketClientEventLoopThreadCount"), equalTo("10"));
            assertThat(configuration.webSocketClientEventLoopThreadCount(), equalTo(10));

            // when - setter
            configuration.webSocketClientEventLoopThreadCount(20);

            // then - getter
            assertThat(configuration.webSocketClientEventLoopThreadCount(), equalTo(20));
        } finally {
            ConfigurationProperties.webSocketClientEventLoopThreadCount(original);
        }
    }

    @Test
    public void shouldSetAndGetClientNioEventLoopThreadCount() {
        int original = ConfigurationProperties.clientNioEventLoopThreadCount();
        try {
            // then - default value
            assertThat(configuration.clientNioEventLoopThreadCount(), equalTo(5));

            // when - system property setter
            ConfigurationProperties.clientNioEventLoopThreadCount(10);

            // then - system property getter
            assertThat(ConfigurationProperties.clientNioEventLoopThreadCount(), equalTo(10));
            assertThat(System.getProperty("mockserver.clientNioEventLoopThreadCount"), equalTo("10"));
            assertThat(configuration.clientNioEventLoopThreadCount(), equalTo(10));

            // when - setter
            configuration.clientNioEventLoopThreadCount(20);

            // then - getter
            assertThat(configuration.clientNioEventLoopThreadCount(), equalTo(20));
        } finally {
            ConfigurationProperties.clientNioEventLoopThreadCount(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxFutureTimeoutInMillis() {
        long original = ConfigurationProperties.maxFutureTimeout();
        try {
            // then - default value
            assertThat(configuration.maxFutureTimeoutInMillis(), equalTo(90000L));

            // when - system property setter
            ConfigurationProperties.maxFutureTimeout(10L);

            // then - system property getter
            assertThat(ConfigurationProperties.maxFutureTimeout(), equalTo(10L));
            assertThat(System.getProperty("mockserver.maxFutureTimeout"), equalTo("10"));
            assertThat(configuration.maxFutureTimeoutInMillis(), equalTo(10L));

            // when - setter
            configuration.maxFutureTimeoutInMillis(20L);

            // then - getter
            assertThat(configuration.maxFutureTimeoutInMillis(), equalTo(20L));
        } finally {
            ConfigurationProperties.maxFutureTimeout(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardConnectionPoolEnabled() {
        boolean original = ConfigurationProperties.forwardConnectionPoolEnabled();
        try {
            // then - default value (pooling is ON by default: it is safe because the forward client
            // runs on a dedicated event-loop group disjoint from the server workers and a channel is
            // only pooled when its codec is genuinely quiescent — raw/non-HTTP error() replies are
            // never pooled, and the disjoint group prevents loopback-callback self-deadlock)
            assertThat(configuration.forwardConnectionPoolEnabled(), equalTo(true));

            // when - system property setter disables it (the opt-out)
            ConfigurationProperties.forwardConnectionPoolEnabled(false);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardConnectionPoolEnabled(), equalTo(false));
            assertThat(System.getProperty("mockserver.forwardConnectionPoolEnabled"), equalTo("false"));
            assertThat(configuration.forwardConnectionPoolEnabled(), equalTo(false));
            ConfigurationProperties.forwardConnectionPoolEnabled(original);

            // when - setter
            configuration.forwardConnectionPoolEnabled(false);

            // then - getter
            assertThat(configuration.forwardConnectionPoolEnabled(), equalTo(false));
        } finally {
            ConfigurationProperties.forwardConnectionPoolEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardConnectionPoolMaxIdlePerKey() {
        int original = ConfigurationProperties.forwardConnectionPoolMaxIdlePerKey();
        try {
            // then - default value
            assertThat(configuration.forwardConnectionPoolMaxIdlePerKey(), equalTo(8));

            // when - system property setter
            ConfigurationProperties.forwardConnectionPoolMaxIdlePerKey(16);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardConnectionPoolMaxIdlePerKey(), equalTo(16));
            assertThat(System.getProperty("mockserver.forwardConnectionPoolMaxIdlePerKey"), equalTo("16"));
            assertThat(configuration.forwardConnectionPoolMaxIdlePerKey(), equalTo(16));
            ConfigurationProperties.forwardConnectionPoolMaxIdlePerKey(original);

            // when - setter
            configuration.forwardConnectionPoolMaxIdlePerKey(4);

            // then - getter
            assertThat(configuration.forwardConnectionPoolMaxIdlePerKey(), equalTo(4));
        } finally {
            ConfigurationProperties.forwardConnectionPoolMaxIdlePerKey(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardConnectionPoolIdleTimeoutMillis() {
        long original = ConfigurationProperties.forwardConnectionPoolIdleTimeoutMillis();
        try {
            // then - default value
            assertThat(configuration.forwardConnectionPoolIdleTimeoutMillis(), equalTo(30_000L));

            // when - system property setter
            ConfigurationProperties.forwardConnectionPoolIdleTimeoutMillis(60_000);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardConnectionPoolIdleTimeoutMillis(), equalTo(60_000L));
            assertThat(System.getProperty("mockserver.forwardConnectionPoolIdleTimeoutMillis"), equalTo("60000"));
            assertThat(configuration.forwardConnectionPoolIdleTimeoutMillis(), equalTo(60_000L));
            ConfigurationProperties.forwardConnectionPoolIdleTimeoutMillis(original);

            // when - setter
            configuration.forwardConnectionPoolIdleTimeoutMillis(15_000L);

            // then - getter
            assertThat(configuration.forwardConnectionPoolIdleTimeoutMillis(), equalTo(15_000L));
        } finally {
            ConfigurationProperties.forwardConnectionPoolIdleTimeoutMillis(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardConnectionPoolKeepAlive() {
        boolean original = ConfigurationProperties.forwardConnectionPoolKeepAlive();
        try {
            // then - default value (keep-warm retention is OFF by default: the pool's release-time
            // close decision is byte-for-byte the historical behaviour unless this is opted in)
            assertThat(configuration.forwardConnectionPoolKeepAlive(), equalTo(false));

            // when - system property setter enables it (the opt-in)
            ConfigurationProperties.forwardConnectionPoolKeepAlive(true);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardConnectionPoolKeepAlive(), equalTo(true));
            assertThat(System.getProperty("mockserver.forwardConnectionPoolKeepAlive"), equalTo("true"));
            assertThat(configuration.forwardConnectionPoolKeepAlive(), equalTo(true));
            ConfigurationProperties.forwardConnectionPoolKeepAlive(original);

            // when - setter
            configuration.forwardConnectionPoolKeepAlive(true);

            // then - getter
            assertThat(configuration.forwardConnectionPoolKeepAlive(), equalTo(true));
        } finally {
            ConfigurationProperties.forwardConnectionPoolKeepAlive(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardConnectionPoolMaxTotalPerKey() {
        int original = ConfigurationProperties.forwardConnectionPoolMaxTotalPerKey();
        try {
            // then - default value
            assertThat(configuration.forwardConnectionPoolMaxTotalPerKey(), equalTo(2000));

            // when - system property setter
            ConfigurationProperties.forwardConnectionPoolMaxTotalPerKey(4000);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardConnectionPoolMaxTotalPerKey(), equalTo(4000));
            assertThat(System.getProperty("mockserver.forwardConnectionPoolMaxTotalPerKey"), equalTo("4000"));
            assertThat(configuration.forwardConnectionPoolMaxTotalPerKey(), equalTo(4000));
            ConfigurationProperties.forwardConnectionPoolMaxTotalPerKey(original);

            // when - setter
            configuration.forwardConnectionPoolMaxTotalPerKey(500);

            // then - getter
            assertThat(configuration.forwardConnectionPoolMaxTotalPerKey(), equalTo(500));
        } finally {
            ConfigurationProperties.forwardConnectionPoolMaxTotalPerKey(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardSocketKeepAlive() {
        boolean original = ConfigurationProperties.forwardSocketKeepAlive();
        try {
            // then - default value (keepalive hardening is ON by default: standard for production HTTP
            // clients, negligible cost, improves half-open detection)
            assertThat(configuration.forwardSocketKeepAlive(), equalTo(true));

            // when - system property setter disables it (restores the historical no-SO_KEEPALIVE behaviour)
            ConfigurationProperties.forwardSocketKeepAlive(false);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardSocketKeepAlive(), equalTo(false));
            assertThat(System.getProperty("mockserver.forwardSocketKeepAlive"), equalTo("false"));
            assertThat(configuration.forwardSocketKeepAlive(), equalTo(false));
            ConfigurationProperties.forwardSocketKeepAlive(original);

            // when - setter
            configuration.forwardSocketKeepAlive(false);

            // then - getter
            assertThat(configuration.forwardSocketKeepAlive(), equalTo(false));
        } finally {
            ConfigurationProperties.forwardSocketKeepAlive(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardSocketKeepAliveIdleSeconds() {
        int original = ConfigurationProperties.forwardSocketKeepAliveIdleSeconds();
        try {
            // then - default value
            assertThat(configuration.forwardSocketKeepAliveIdleSeconds(), equalTo(60));

            // when - system property setter
            ConfigurationProperties.forwardSocketKeepAliveIdleSeconds(120);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardSocketKeepAliveIdleSeconds(), equalTo(120));
            assertThat(System.getProperty("mockserver.forwardSocketKeepAliveIdleSeconds"), equalTo("120"));
            assertThat(configuration.forwardSocketKeepAliveIdleSeconds(), equalTo(120));
            ConfigurationProperties.forwardSocketKeepAliveIdleSeconds(original);

            // when - setter
            configuration.forwardSocketKeepAliveIdleSeconds(30);

            // then - getter
            assertThat(configuration.forwardSocketKeepAliveIdleSeconds(), equalTo(30));
        } finally {
            ConfigurationProperties.forwardSocketKeepAliveIdleSeconds(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardSocketKeepAliveIntervalSeconds() {
        int original = ConfigurationProperties.forwardSocketKeepAliveIntervalSeconds();
        try {
            // then - default value
            assertThat(configuration.forwardSocketKeepAliveIntervalSeconds(), equalTo(15));

            // when - system property setter
            ConfigurationProperties.forwardSocketKeepAliveIntervalSeconds(30);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardSocketKeepAliveIntervalSeconds(), equalTo(30));
            assertThat(System.getProperty("mockserver.forwardSocketKeepAliveIntervalSeconds"), equalTo("30"));
            assertThat(configuration.forwardSocketKeepAliveIntervalSeconds(), equalTo(30));
            ConfigurationProperties.forwardSocketKeepAliveIntervalSeconds(original);

            // when - setter
            configuration.forwardSocketKeepAliveIntervalSeconds(5);

            // then - getter
            assertThat(configuration.forwardSocketKeepAliveIntervalSeconds(), equalTo(5));
        } finally {
            ConfigurationProperties.forwardSocketKeepAliveIntervalSeconds(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardSocketKeepAliveCount() {
        int original = ConfigurationProperties.forwardSocketKeepAliveCount();
        try {
            // then - default value
            assertThat(configuration.forwardSocketKeepAliveCount(), equalTo(4));

            // when - system property setter
            ConfigurationProperties.forwardSocketKeepAliveCount(8);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardSocketKeepAliveCount(), equalTo(8));
            assertThat(System.getProperty("mockserver.forwardSocketKeepAliveCount"), equalTo("8"));
            assertThat(configuration.forwardSocketKeepAliveCount(), equalTo(8));
            ConfigurationProperties.forwardSocketKeepAliveCount(original);

            // when - setter
            configuration.forwardSocketKeepAliveCount(2);

            // then - getter
            assertThat(configuration.forwardSocketKeepAliveCount(), equalTo(2));
        } finally {
            ConfigurationProperties.forwardSocketKeepAliveCount(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyRetryCount() {
        int original = ConfigurationProperties.forwardProxyRetryCount();
        try {
            assertThat(configuration.forwardProxyRetryCount(), equalTo(0));

            ConfigurationProperties.forwardProxyRetryCount(3);
            assertThat(ConfigurationProperties.forwardProxyRetryCount(), equalTo(3));
            assertThat(System.getProperty("mockserver.forwardProxyRetryCount"), equalTo("3"));
            assertThat(configuration.forwardProxyRetryCount(), equalTo(3));
            ConfigurationProperties.forwardProxyRetryCount(original);

            configuration.forwardProxyRetryCount(5);
            assertThat(configuration.forwardProxyRetryCount(), equalTo(5));
        } finally {
            ConfigurationProperties.forwardProxyRetryCount(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyRetryBackoffMillis() {
        long original = ConfigurationProperties.forwardProxyRetryBackoffMillis();
        try {
            assertThat(configuration.forwardProxyRetryBackoffMillis(), equalTo(100L));

            ConfigurationProperties.forwardProxyRetryBackoffMillis(250L);
            assertThat(ConfigurationProperties.forwardProxyRetryBackoffMillis(), equalTo(250L));
            assertThat(System.getProperty("mockserver.forwardProxyRetryBackoffMillis"), equalTo("250"));
            assertThat(configuration.forwardProxyRetryBackoffMillis(), equalTo(250L));
            ConfigurationProperties.forwardProxyRetryBackoffMillis(original);

            configuration.forwardProxyRetryBackoffMillis(75L);
            assertThat(configuration.forwardProxyRetryBackoffMillis(), equalTo(75L));
        } finally {
            ConfigurationProperties.forwardProxyRetryBackoffMillis(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyCircuitBreakerEnabled() {
        boolean original = ConfigurationProperties.forwardProxyCircuitBreakerEnabled();
        try {
            assertThat(configuration.forwardProxyCircuitBreakerEnabled(), equalTo(false));

            ConfigurationProperties.forwardProxyCircuitBreakerEnabled(true);
            assertThat(ConfigurationProperties.forwardProxyCircuitBreakerEnabled(), equalTo(true));
            assertThat(System.getProperty("mockserver.forwardProxyCircuitBreakerEnabled"), equalTo("true"));
            assertThat(configuration.forwardProxyCircuitBreakerEnabled(), equalTo(true));
            ConfigurationProperties.forwardProxyCircuitBreakerEnabled(original);

            configuration.forwardProxyCircuitBreakerEnabled(true);
            assertThat(configuration.forwardProxyCircuitBreakerEnabled(), equalTo(true));
        } finally {
            ConfigurationProperties.forwardProxyCircuitBreakerEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyCircuitBreakerFailureThreshold() {
        int original = ConfigurationProperties.forwardProxyCircuitBreakerFailureThreshold();
        try {
            assertThat(configuration.forwardProxyCircuitBreakerFailureThreshold(), equalTo(5));

            ConfigurationProperties.forwardProxyCircuitBreakerFailureThreshold(10);
            assertThat(ConfigurationProperties.forwardProxyCircuitBreakerFailureThreshold(), equalTo(10));
            assertThat(System.getProperty("mockserver.forwardProxyCircuitBreakerFailureThreshold"), equalTo("10"));
            assertThat(configuration.forwardProxyCircuitBreakerFailureThreshold(), equalTo(10));
            ConfigurationProperties.forwardProxyCircuitBreakerFailureThreshold(original);

            configuration.forwardProxyCircuitBreakerFailureThreshold(2);
            assertThat(configuration.forwardProxyCircuitBreakerFailureThreshold(), equalTo(2));
        } finally {
            ConfigurationProperties.forwardProxyCircuitBreakerFailureThreshold(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyCircuitBreakerWindowMillis() {
        long original = ConfigurationProperties.forwardProxyCircuitBreakerWindowMillis();
        try {
            assertThat(configuration.forwardProxyCircuitBreakerWindowMillis(), equalTo(30_000L));

            ConfigurationProperties.forwardProxyCircuitBreakerWindowMillis(45_000L);
            assertThat(ConfigurationProperties.forwardProxyCircuitBreakerWindowMillis(), equalTo(45_000L));
            assertThat(System.getProperty("mockserver.forwardProxyCircuitBreakerWindowMillis"), equalTo("45000"));
            assertThat(configuration.forwardProxyCircuitBreakerWindowMillis(), equalTo(45_000L));
            ConfigurationProperties.forwardProxyCircuitBreakerWindowMillis(original);

            configuration.forwardProxyCircuitBreakerWindowMillis(5_000L);
            assertThat(configuration.forwardProxyCircuitBreakerWindowMillis(), equalTo(5_000L));
        } finally {
            ConfigurationProperties.forwardProxyCircuitBreakerWindowMillis(original);
        }
    }

    @Test
    public void shouldSetAndGetMatchersFailFast() {
        boolean original = ConfigurationProperties.matchersFailFast();
        try {
            // then - default value
            assertThat(configuration.matchersFailFast(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.matchersFailFast(false);

            // then - system property getter
            assertThat(ConfigurationProperties.matchersFailFast(), equalTo(false));
            assertThat(System.getProperty("mockserver.matchersFailFast"), equalTo("false"));
            assertThat(configuration.matchersFailFast(), equalTo(false));
            ConfigurationProperties.matchersFailFast(original);

            // when - setter
            configuration.matchersFailFast(false);

            // then - getter
            assertThat(configuration.matchersFailFast(), equalTo(false));
        } finally {
            ConfigurationProperties.matchersFailFast(original);
        }
    }

    @Test
    public void shouldSetAndGetEnforceResponseValidationForMocks() {
        boolean original = ConfigurationProperties.enforceResponseValidationForMocks();
        try {
            // then - default value
            assertThat(configuration.enforceResponseValidationForMocks(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.enforceResponseValidationForMocks(true);

            // then - system property getter
            assertThat(ConfigurationProperties.enforceResponseValidationForMocks(), equalTo(true));
            assertThat(System.getProperty("mockserver.enforceResponseValidationForMocks"), equalTo("true"));
            assertThat(configuration.enforceResponseValidationForMocks(), equalTo(true));
            ConfigurationProperties.enforceResponseValidationForMocks(original);

            // when - instance setter overrides the system property
            ConfigurationProperties.enforceResponseValidationForMocks(false);
            configuration.enforceResponseValidationForMocks(true);

            // then - getter
            assertThat(configuration.enforceResponseValidationForMocks(), equalTo(true));
        } finally {
            ConfigurationProperties.enforceResponseValidationForMocks(original);
        }
    }

    @Test
    public void shouldSetAndGetValidateRequestsAgainstOpenApiSpec() {
        boolean original = ConfigurationProperties.validateRequestsAgainstOpenApiSpec();
        try {
            // then - default value
            assertThat(configuration.validateRequestsAgainstOpenApiSpec(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.validateRequestsAgainstOpenApiSpec(true);

            // then - system property getter
            assertThat(ConfigurationProperties.validateRequestsAgainstOpenApiSpec(), equalTo(true));
            assertThat(System.getProperty("mockserver.validateRequestsAgainstOpenApiSpec"), equalTo("true"));
            assertThat(configuration.validateRequestsAgainstOpenApiSpec(), equalTo(true));
            ConfigurationProperties.validateRequestsAgainstOpenApiSpec(original);

            // when - instance setter overrides the system property
            ConfigurationProperties.validateRequestsAgainstOpenApiSpec(false);
            configuration.validateRequestsAgainstOpenApiSpec(true);

            // then - getter
            assertThat(configuration.validateRequestsAgainstOpenApiSpec(), equalTo(true));
        } finally {
            ConfigurationProperties.validateRequestsAgainstOpenApiSpec(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxSocketTimeoutInMillis() {
        long original = ConfigurationProperties.maxSocketTimeout();
        try {
            // then - default value
            assertThat(configuration.maxSocketTimeoutInMillis(), equalTo(20000L));

            // when - system property setter
            ConfigurationProperties.maxSocketTimeout(10L);

            // then - system property getter
            assertThat(ConfigurationProperties.maxSocketTimeout(), equalTo(10L));
            assertThat(System.getProperty("mockserver.maxSocketTimeout"), equalTo("10"));
            assertThat(configuration.maxSocketTimeoutInMillis(), equalTo(10L));

            // when - setter
            configuration.maxSocketTimeoutInMillis(20L);

            // then - getter
            assertThat(configuration.maxSocketTimeoutInMillis(), equalTo(20L));
        } finally {
            ConfigurationProperties.maxSocketTimeout(original);
        }
    }

    @Test
    public void shouldSetAndGetSocketConnectionTimeoutInMillis() {
        long original = ConfigurationProperties.socketConnectionTimeout();
        try {
            // then - default value
            assertThat(configuration.socketConnectionTimeoutInMillis(), equalTo(20000L));

            // when - system property setter
            ConfigurationProperties.socketConnectionTimeout(10L);

            // then - system property getter
            assertThat(ConfigurationProperties.socketConnectionTimeout(), equalTo(10L));
            assertThat(System.getProperty("mockserver.socketConnectionTimeout"), equalTo("10"));
            assertThat(configuration.socketConnectionTimeoutInMillis(), equalTo(10L));

            // when - setter
            configuration.socketConnectionTimeoutInMillis(20L);

            // then - getter
            assertThat(configuration.socketConnectionTimeoutInMillis(), equalTo(20L));
        } finally {
            ConfigurationProperties.socketConnectionTimeout(original);
        }
    }

    @Test
    public void shouldHonourMaxSocketTimeoutInMillisAliasSystemProperty() {
        // Reproduces the silent-ignore bug: the Configuration API and the /mockserver/configuration JSON
        // expose this value as `maxSocketTimeoutInMillis`, so an operator naturally sets
        // -Dmockserver.maxSocketTimeoutInMillis — but ConfigurationProperties historically read only the
        // canonical `mockserver.maxSocketTimeout`, so the override was silently dropped and the 20s default
        // stood (the LLM-proxy first-byte 502 we chased). The InMillis alias must now be honoured.
        try {
            // given - neither key set: the 20s default
            clearPropertyAndCache("mockserver.maxSocketTimeout");
            clearPropertyAndCache("mockserver.maxSocketTimeoutInMillis");
            assertThat(configuration.maxSocketTimeoutInMillis(), equalTo(20000L));

            // when - only the InMillis alias is set (as a -D system property would be)
            clearPropertyAndCache("mockserver.maxSocketTimeout");
            clearPropertyAndCache("mockserver.maxSocketTimeoutInMillis");
            System.setProperty("mockserver.maxSocketTimeoutInMillis", "300000");

            // then - honoured end to end (static property reader AND Configuration instance)
            assertThat(ConfigurationProperties.maxSocketTimeout(), equalTo(300000L));
            assertThat(new Configuration().maxSocketTimeoutInMillis(), equalTo(300000L));

            // and - the two names are synonyms; the primary key is read first, so when both are set to
            // contradictory launch values the primary `mockserver.maxSocketTimeout` wins (and, in turn, a
            // programmatic/runtime set of the primary key can never be silently overridden by the alias)
            clearPropertyAndCache("mockserver.maxSocketTimeout");
            clearPropertyAndCache("mockserver.maxSocketTimeoutInMillis");
            System.setProperty("mockserver.maxSocketTimeoutInMillis", "300000");
            System.setProperty("mockserver.maxSocketTimeout", "90000");
            assertThat(ConfigurationProperties.maxSocketTimeout(), equalTo(90000L));
        } finally {
            clearPropertyAndCache("mockserver.maxSocketTimeout");
            clearPropertyAndCache("mockserver.maxSocketTimeoutInMillis");
        }
    }

    @Test
    public void shouldHonourSocketConnectionTimeoutInMillisAliasSystemProperty() {
        // Same naming footgun as maxSocketTimeout: accept the `InMillis`-suffixed alias.
        try {
            clearPropertyAndCache("mockserver.socketConnectionTimeout");
            clearPropertyAndCache("mockserver.socketConnectionTimeoutInMillis");
            System.setProperty("mockserver.socketConnectionTimeoutInMillis", "45000");

            assertThat(ConfigurationProperties.socketConnectionTimeout(), equalTo(45000L));
            assertThat(new Configuration().socketConnectionTimeoutInMillis(), equalTo(45000L));
        } finally {
            clearPropertyAndCache("mockserver.socketConnectionTimeout");
            clearPropertyAndCache("mockserver.socketConnectionTimeoutInMillis");
        }
    }

    @Test
    public void shouldHonourMaxFutureTimeoutInMillisAliasSystemProperty() {
        // Same naming footgun as maxSocketTimeout: the Configuration API and the /mockserver/configuration
        // JSON expose this as `maxFutureTimeoutInMillis`, but ConfigurationProperties read only the
        // unit-less `mockserver.maxFutureTimeout`, so the natural -Dmockserver.maxFutureTimeoutInMillis was
        // silently dropped and the 90s default stood. The InMillis alias must now be honoured.
        try {
            // when - only the InMillis alias is set
            clearPropertyAndCache("mockserver.maxFutureTimeout");
            clearPropertyAndCache("mockserver.maxFutureTimeoutInMillis");
            System.setProperty("mockserver.maxFutureTimeoutInMillis", "120000");

            assertThat(ConfigurationProperties.maxFutureTimeout(), equalTo(120000L));
            assertThat(new Configuration().maxFutureTimeoutInMillis(), equalTo(120000L));

            // and - the two names are synonyms; the primary key is read first, so when both are set to
            // contradictory launch values the primary `mockserver.maxFutureTimeout` wins
            clearPropertyAndCache("mockserver.maxFutureTimeout");
            clearPropertyAndCache("mockserver.maxFutureTimeoutInMillis");
            System.setProperty("mockserver.maxFutureTimeoutInMillis", "120000");
            System.setProperty("mockserver.maxFutureTimeout", "30000");
            assertThat(ConfigurationProperties.maxFutureTimeout(), equalTo(30000L));
        } finally {
            clearPropertyAndCache("mockserver.maxFutureTimeout");
            clearPropertyAndCache("mockserver.maxFutureTimeoutInMillis");
        }
    }

    @Test
    public void shouldSetAndGetAlwaysCloseSocketConnections() {
        boolean original = ConfigurationProperties.alwaysCloseSocketConnections();
        try {
            // then - default value
            assertThat(configuration.alwaysCloseSocketConnections(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.alwaysCloseSocketConnections(true);

            // then - system property getter
            assertThat(ConfigurationProperties.alwaysCloseSocketConnections(), equalTo(true));
            assertThat(System.getProperty("mockserver.alwaysCloseSocketConnections"), equalTo("true"));
            assertThat(configuration.alwaysCloseSocketConnections(), equalTo(true));
            ConfigurationProperties.alwaysCloseSocketConnections(original);

            // when - setter
            configuration.alwaysCloseSocketConnections(true);

            // then - getter
            assertThat(configuration.alwaysCloseSocketConnections(), equalTo(true));
        } finally {
            ConfigurationProperties.alwaysCloseSocketConnections(original);
        }
    }

    @Test
    public void shouldSetAndGetLocalBoundIP() {
        String original = ConfigurationProperties.localBoundIP();
        try {
            // then - default value
            assertThat(configuration.localBoundIP(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.localBoundIP("0.0.0.0");

            // then - system property getter
            assertThat(ConfigurationProperties.localBoundIP(), equalTo("0.0.0.0"));
            assertThat(System.getProperty("mockserver.localBoundIP"), equalTo("0.0.0.0"));
            assertThat(configuration.localBoundIP(), equalTo("0.0.0.0"));
            ConfigurationProperties.localBoundIP(original);

            // when - setter
            configuration.localBoundIP("0.0.0.0");

            // then - getter
            assertThat(configuration.localBoundIP(), equalTo("0.0.0.0"));
        } finally {
            ConfigurationProperties.localBoundIP(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxInitialLineLength() {
        int original = ConfigurationProperties.maxInitialLineLength();
        try {
            // then - default value
            assertThat(configuration.maxInitialLineLength(), equalTo(Integer.MAX_VALUE));

            // when - system property setter
            ConfigurationProperties.maxInitialLineLength(10);

            // then - system property getter
            assertThat(ConfigurationProperties.maxInitialLineLength(), equalTo(10));
            assertThat(System.getProperty("mockserver.maxInitialLineLength"), equalTo("10"));
            assertThat(configuration.maxInitialLineLength(), equalTo(10));

            // when - setter
            configuration.maxInitialLineLength(20);

            // then - getter
            assertThat(configuration.maxInitialLineLength(), equalTo(20));
        } finally {
            ConfigurationProperties.maxInitialLineLength(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxHeaderSize() {
        int original = ConfigurationProperties.maxHeaderSize();
        try {
            // then - default value
            assertThat(configuration.maxHeaderSize(), equalTo(Integer.MAX_VALUE));

            // when - system property setter
            ConfigurationProperties.maxHeaderSize(10);

            // then - system property getter
            assertThat(ConfigurationProperties.maxHeaderSize(), equalTo(10));
            assertThat(System.getProperty("mockserver.maxHeaderSize"), equalTo("10"));
            assertThat(configuration.maxHeaderSize(), equalTo(10));

            // when - setter
            configuration.maxHeaderSize(20);

            // then - getter
            assertThat(configuration.maxHeaderSize(), equalTo(20));
        } finally {
            ConfigurationProperties.maxHeaderSize(original);
        }
    }

    @Test
    public void shouldSetAndGetMaxChunkSize() {
        int original = ConfigurationProperties.maxChunkSize();
        try {
            // then - default value
            assertThat(configuration.maxChunkSize(), equalTo(Integer.MAX_VALUE));

            // when - system property setter
            ConfigurationProperties.maxChunkSize(10);

            // then - system property getter
            assertThat(ConfigurationProperties.maxChunkSize(), equalTo(10));
            assertThat(System.getProperty("mockserver.maxChunkSize"), equalTo("10"));
            assertThat(configuration.maxChunkSize(), equalTo(10));

            // when - setter
            configuration.maxChunkSize(20);

            // then - getter
            assertThat(configuration.maxChunkSize(), equalTo(20));
        } finally {
            ConfigurationProperties.maxChunkSize(original);
        }
    }

    @Test
    public void shouldSetAndGetUseSemicolonAsQueryParameterSeparator() {
        boolean original = ConfigurationProperties.useSemicolonAsQueryParameterSeparator();
        try {
            // then - default value
            assertThat(configuration.useSemicolonAsQueryParameterSeparator(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.useSemicolonAsQueryParameterSeparator(false);

            // then - system property getter
            assertThat(ConfigurationProperties.useSemicolonAsQueryParameterSeparator(), equalTo(false));
            assertThat(System.getProperty("mockserver.useSemicolonAsQueryParameterSeparator"), equalTo("false"));
            assertThat(configuration.useSemicolonAsQueryParameterSeparator(), equalTo(false));
            ConfigurationProperties.useSemicolonAsQueryParameterSeparator(original);

            // when - setter
            configuration.useSemicolonAsQueryParameterSeparator(false);

            // then - getter
            assertThat(configuration.useSemicolonAsQueryParameterSeparator(), equalTo(false));
        } finally {
            ConfigurationProperties.useSemicolonAsQueryParameterSeparator(original);
        }
    }

    @Test
    public void shouldSetAndGetStartupWarmup() {
        boolean original = ConfigurationProperties.startupWarmup();
        try {
            // then - default value
            assertThat(configuration.startupWarmup(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.startupWarmup(false);

            // then - system property getter
            assertThat(ConfigurationProperties.startupWarmup(), equalTo(false));
            assertThat(System.getProperty("mockserver.startupWarmup"), equalTo("false"));
            assertThat(configuration.startupWarmup(), equalTo(false));
            ConfigurationProperties.startupWarmup(original);

            // when - setter
            configuration.startupWarmup(false);

            // then - getter
            assertThat(configuration.startupWarmup(), equalTo(false));
        } finally {
            ConfigurationProperties.startupWarmup(original);
        }
    }

    @Test
    public void shouldSetAndGetAssumeAllRequestsAreHttp() {
        boolean original = ConfigurationProperties.assumeAllRequestsAreHttp();
        try {
            // then - default value
            assertThat(configuration.assumeAllRequestsAreHttp(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.assumeAllRequestsAreHttp(true);

            // then - system property getter
            assertThat(ConfigurationProperties.assumeAllRequestsAreHttp(), equalTo(true));
            assertThat(System.getProperty("mockserver.assumeAllRequestsAreHttp"), equalTo("true"));
            assertThat(configuration.assumeAllRequestsAreHttp(), equalTo(true));
            ConfigurationProperties.assumeAllRequestsAreHttp(original);

            // when - setter
            configuration.assumeAllRequestsAreHttp(true);

            // then - getter
            assertThat(configuration.assumeAllRequestsAreHttp(), equalTo(true));
        } finally {
            ConfigurationProperties.assumeAllRequestsAreHttp(original);
        }
    }

    @Test
    public void shouldSetAndGetHttp2Enabled() {
        boolean original = ConfigurationProperties.http2Enabled();
        try {
            // then - default value
            assertThat(configuration.http2Enabled(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.http2Enabled(false);

            // then - system property getter
            assertThat(ConfigurationProperties.http2Enabled(), equalTo(false));
            assertThat(System.getProperty("mockserver.http2Enabled"), equalTo("false"));
            assertThat(configuration.http2Enabled(), equalTo(false));
            ConfigurationProperties.http2Enabled(original);

            // when - setter
            configuration.http2Enabled(false);

            // then - getter
            assertThat(configuration.http2Enabled(), equalTo(false));
        } finally {
            ConfigurationProperties.http2Enabled(original);
        }
    }

    @Test
    public void shouldSetAndGetGrpcBidiStreamingEnabled() {
        boolean original = ConfigurationProperties.grpcBidiStreamingEnabled();
        try {
            // then - default value
            assertThat(configuration.grpcBidiStreamingEnabled(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.grpcBidiStreamingEnabled(true);

            // then - system property getter
            assertThat(ConfigurationProperties.grpcBidiStreamingEnabled(), equalTo(true));
            assertThat(System.getProperty("mockserver.grpcBidiStreamingEnabled"), equalTo("true"));
            assertThat(configuration.grpcBidiStreamingEnabled(), equalTo(true));
            ConfigurationProperties.grpcBidiStreamingEnabled(original);

            // when - setter
            configuration.grpcBidiStreamingEnabled(true);

            // then - getter
            assertThat(configuration.grpcBidiStreamingEnabled(), equalTo(true));
        } finally {
            ConfigurationProperties.grpcBidiStreamingEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardBinaryRequestsWithoutWaitingForResponse() {
        boolean original = ConfigurationProperties.forwardBinaryRequestsWithoutWaitingForResponse();
        try {
            // then - default value
            assertThat(configuration.forwardBinaryRequestsWithoutWaitingForResponse(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.forwardBinaryRequestsWithoutWaitingForResponse(true);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardBinaryRequestsWithoutWaitingForResponse(), equalTo(true));
            assertThat(System.getProperty("mockserver.forwardBinaryRequestsWithoutWaitingForResponse"), equalTo("true"));
            assertThat(configuration.forwardBinaryRequestsWithoutWaitingForResponse(), equalTo(true));
            ConfigurationProperties.forwardBinaryRequestsWithoutWaitingForResponse(original);

            // when - setter
            configuration.forwardBinaryRequestsWithoutWaitingForResponse(true);

            // then - getter
            assertThat(configuration.forwardBinaryRequestsWithoutWaitingForResponse(), equalTo(true));
        } finally {
            ConfigurationProperties.forwardBinaryRequestsWithoutWaitingForResponse(original);
        }
    }

    @Test
    public void shouldSetAndGetEnableCORSForAPI() {
        boolean original = ConfigurationProperties.enableCORSForAPI();
        try {
            // then - default value
            assertThat(configuration.enableCORSForAPI(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.enableCORSForAPI(true);

            // then - system property getter
            assertThat(ConfigurationProperties.enableCORSForAPI(), equalTo(true));
            assertThat(System.getProperty("mockserver.enableCORSForAPI"), equalTo("true"));
            assertThat(configuration.enableCORSForAPI(), equalTo(true));
            ConfigurationProperties.enableCORSForAPI(original);

            // when - setter
            configuration.enableCORSForAPI(true);

            // then - getter
            assertThat(configuration.enableCORSForAPI(), equalTo(true));
        } finally {
            ConfigurationProperties.enableCORSForAPI(original);
        }
    }

    @Test
    public void shouldSetAndGetEnableCORSForAllResponses() {
        boolean original = ConfigurationProperties.enableCORSForAllResponses();
        try {
            // then - default value
            assertThat(configuration.enableCORSForAllResponses(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.enableCORSForAllResponses(true);

            // then - system property getter
            assertThat(ConfigurationProperties.enableCORSForAllResponses(), equalTo(true));
            assertThat(System.getProperty("mockserver.enableCORSForAllResponses"), equalTo("true"));
            assertThat(configuration.enableCORSForAllResponses(), equalTo(true));
            ConfigurationProperties.enableCORSForAllResponses(original);

            // when - setter
            configuration.enableCORSForAllResponses(true);

            // then - getter
            assertThat(configuration.enableCORSForAllResponses(), equalTo(true));
        } finally {
            ConfigurationProperties.enableCORSForAllResponses(original);
        }
    }

    @Test
    public void shouldSetAndGetCorsAllowOrigin() {
        String original = ConfigurationProperties.corsAllowOrigin();
        try {
            // then - default value
            assertThat(configuration.corsAllowOrigin(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.corsAllowOrigin("*");

            // then - system property getter
            assertThat(ConfigurationProperties.corsAllowOrigin(), equalTo("*"));
            assertThat(System.getProperty("mockserver.corsAllowOrigin"), equalTo("*"));
            assertThat(configuration.corsAllowOrigin(), equalTo("*"));

            // when - setter
            configuration.corsAllowOrigin("www.mock-server.com");

            // then - getter
            assertThat(configuration.corsAllowOrigin(), equalTo("www.mock-server.com"));
        } finally {
            ConfigurationProperties.corsAllowOrigin(original);
        }
    }

    @Test
    public void shouldSetAndGetCorsAllowMethods() {
        String original = ConfigurationProperties.corsAllowMethods();
        try {
            // then - default value
            assertThat(configuration.corsAllowMethods(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.corsAllowMethods("CONNECT, DELETE");

            // then - system property getter
            assertThat(ConfigurationProperties.corsAllowMethods(), equalTo("CONNECT, DELETE"));
            assertThat(System.getProperty("mockserver.corsAllowMethods"), equalTo("CONNECT, DELETE"));
            assertThat(configuration.corsAllowMethods(), equalTo("CONNECT, DELETE"));

            // when - setter
            configuration.corsAllowMethods("CONNECT, DELETE, GET");

            // then - getter
            assertThat(configuration.corsAllowMethods(), equalTo("CONNECT, DELETE, GET"));
        } finally {
            ConfigurationProperties.corsAllowMethods(original);
        }
    }

    @Test
    public void shouldSetAndGetCorsAllowHeaders() {
        String original = ConfigurationProperties.corsAllowHeaders();
        try {
            // then - default value
            assertThat(configuration.corsAllowHeaders(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.corsAllowHeaders("Allow, Content-Encoding");

            // then - system property getter
            assertThat(ConfigurationProperties.corsAllowHeaders(), equalTo("Allow, Content-Encoding"));
            assertThat(System.getProperty("mockserver.corsAllowHeaders"), equalTo("Allow, Content-Encoding"));
            assertThat(configuration.corsAllowHeaders(), equalTo("Allow, Content-Encoding"));

            // when - setter
            configuration.corsAllowHeaders("Allow, Content-Encoding, Content-Length");

            // then - getter
            assertThat(configuration.corsAllowHeaders(), equalTo("Allow, Content-Encoding, Content-Length"));
        } finally {
            ConfigurationProperties.corsAllowHeaders(original);
        }
    }

    @Test
    public void shouldSetAndGetCorsAllowCredentials() {
        boolean original = ConfigurationProperties.corsAllowCredentials();
        try {
            // then - default value
            assertThat(configuration.corsAllowCredentials(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.corsAllowCredentials(true);

            // then - system property getter
            assertThat(ConfigurationProperties.corsAllowCredentials(), equalTo(true));
            assertThat(System.getProperty("mockserver.corsAllowCredentials"), equalTo("true"));
            assertThat(configuration.corsAllowCredentials(), equalTo(true));
            ConfigurationProperties.corsAllowCredentials(original);

            // when - setter
            configuration.corsAllowCredentials(true);

            // then - getter
            assertThat(configuration.corsAllowCredentials(), equalTo(true));
        } finally {
            ConfigurationProperties.corsAllowCredentials(original);
        }
    }

    @Test
    public void shouldSetAndGetCorsMaxAgeInSeconds() {
        int original = ConfigurationProperties.corsMaxAgeInSeconds();
        try {
            // then - default value
            assertThat(configuration.corsMaxAgeInSeconds(), equalTo(0));

            // when - system property setter
            ConfigurationProperties.corsMaxAgeInSeconds(10);

            // then - system property getter
            assertThat(ConfigurationProperties.corsMaxAgeInSeconds(), equalTo(10));
            assertThat(System.getProperty("mockserver.corsMaxAgeInSeconds"), equalTo("10"));
            assertThat(configuration.corsMaxAgeInSeconds(), equalTo(10));

            // when - setter
            configuration.corsMaxAgeInSeconds(20);

            // then - getter
            assertThat(configuration.corsMaxAgeInSeconds(), equalTo(20));
        } finally {
            ConfigurationProperties.corsMaxAgeInSeconds(original);
        }
    }

    @Test
    public void shouldSetAndGetJavaScriptDisallowedClasses() {
        String original = ConfigurationProperties.javascriptDisallowedClasses();
        try {
            // then - default value
            assertThat(configuration.javascriptDisallowedClasses(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.javascriptDisallowedClasses("java.lang.Runtime");

            // then - system property getter
            assertThat(ConfigurationProperties.javascriptDisallowedClasses(), equalTo("java.lang.Runtime"));
            assertThat(System.getProperty("mockserver.javascriptDisallowedClasses"), equalTo("java.lang.Runtime"));
            assertThat(configuration.javascriptDisallowedClasses(), equalTo("java.lang.Runtime"));

            // when - setter
            configuration.javascriptDisallowedClasses("java.lang.Class");

            // then - getter
            assertThat(configuration.javascriptDisallowedClasses(), equalTo("java.lang.Class"));
        } finally {
            ConfigurationProperties.javascriptDisallowedClasses(original);
        }
    }

    @Test
    public void shouldSetAndGetJavaScriptDisallowedText() {
        String original = ConfigurationProperties.javascriptDisallowedText();
        try {
            // then - default value
            assertThat(configuration.javascriptDisallowedText(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.javascriptDisallowedText("some_text");

            // then - system property getter
            assertThat(ConfigurationProperties.javascriptDisallowedText(), equalTo("some_text"));
            assertThat(System.getProperty("mockserver.javascriptDisallowedText"), equalTo("some_text"));
            assertThat(configuration.javascriptDisallowedText(), equalTo("some_text"));

            // when - setter
            configuration.javascriptDisallowedText("some_other_text");

            // then - getter
            assertThat(configuration.javascriptDisallowedText(), equalTo("some_other_text"));
        } finally {
            ConfigurationProperties.javascriptDisallowedText(original);
        }
    }

    @Test
    public void shouldSetAndGetVelocityDisallowClassLoading() {
        boolean original = ConfigurationProperties.velocityDisallowClassLoading();
        try {
            // then - default value is the sandbox being ON (templates cannot load Java classes)
            assertThat(configuration.velocityDisallowClassLoading(), equalTo(true));

            // when - system property setter opts out of the sandbox
            ConfigurationProperties.velocityDisallowClassLoading(false);

            // then - system property getter
            assertThat(ConfigurationProperties.velocityDisallowClassLoading(), equalTo(false));
            assertThat(System.getProperty("mockserver.velocityDisallowClassLoading"), equalTo("false"));
            assertThat(configuration.velocityDisallowClassLoading(), equalTo(false));

            // when - setter
            configuration.velocityDisallowClassLoading(true);

            // then - getter
            assertThat(configuration.velocityDisallowClassLoading(), equalTo(true));
        } finally {
            ConfigurationProperties.velocityDisallowClassLoading(original);
        }
    }

    @Test
    public void shouldSetAndGetVelocityDisallowedText() {
        String original = ConfigurationProperties.velocityDisallowedText();
        try {
            // then - default value
            assertThat(configuration.velocityDisallowedText(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.velocityDisallowedText("some_text");

            // then - system property getter
            assertThat(ConfigurationProperties.velocityDisallowedText(), equalTo("some_text"));
            assertThat(System.getProperty("mockserver.velocityDisallowedText"), equalTo("some_text"));
            assertThat(configuration.velocityDisallowedText(), equalTo("some_text"));

            // when - setter
            configuration.velocityDisallowedText("some_other_text");

            // then - getter
            assertThat(configuration.velocityDisallowedText(), equalTo("some_other_text"));
        } finally {
            ConfigurationProperties.velocityDisallowedText(original);
        }
    }

    @Test
    public void shouldSetAndGetMustacheDisallowedText() {
        String original = ConfigurationProperties.mustacheDisallowedText();
        try {
            // then - default value
            assertThat(configuration.mustacheDisallowedText(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.mustacheDisallowedText("some_text");

            // then - system property getter
            assertThat(ConfigurationProperties.mustacheDisallowedText(), equalTo("some_text"));
            assertThat(System.getProperty("mockserver.mustacheDisallowedText"), equalTo("some_text"));
            assertThat(configuration.mustacheDisallowedText(), equalTo("some_text"));

            // when - setter
            configuration.mustacheDisallowedText("some_other_text");

            // then - getter
            assertThat(configuration.mustacheDisallowedText(), equalTo("some_other_text"));
        } finally {
            ConfigurationProperties.mustacheDisallowedText(original);
        }
    }

    @Test
    public void shouldSetAndGetInitializationClass() {
        String original = ConfigurationProperties.initializationClass();
        try {
            // then - default value
            assertThat(configuration.initializationClass(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.initializationClass(ExpectationInitializerExample.class.getName());

            // then - system property getter
            assertThat(ConfigurationProperties.initializationClass(), equalTo(ExpectationInitializerExample.class.getName()));
            assertThat(System.getProperty("mockserver.initializationClass"), equalTo(ExpectationInitializerExample.class.getName()));
            assertThat(configuration.initializationClass(), equalTo(ExpectationInitializerExample.class.getName()));
            ConfigurationProperties.initializationClass(original);

            // when - setter
            configuration.initializationClass(ExpectationInitializerExample.class.getName());

            // then - getter
            assertThat(configuration.initializationClass(), equalTo(ExpectationInitializerExample.class.getName()));
        } finally {
            ConfigurationProperties.initializationClass(original);
        }
    }

    @Test
    public void shouldSetAndGetInitializationJsonPath() {
        String original = ConfigurationProperties.initializationJsonPath();
        try {
            // then - default value
            assertThat(configuration.initializationJsonPath(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.initializationJsonPath(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.initializationJsonPath(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.initializationJsonPath"), equalTo(firstPath));
            assertThat(configuration.initializationJsonPath(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.initializationJsonPath(secondPath);

            // then - getter
            assertThat(configuration.initializationJsonPath(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.initializationJsonPath(original);
        }
    }

    @Test
    public void shouldSetAndGetWatchInitializationJson() {
        boolean original = ConfigurationProperties.watchInitializationJson();
        try {
            // then - default value
            assertThat(configuration.watchInitializationJson(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.watchInitializationJson(true);

            // then - system property getter
            assertThat(ConfigurationProperties.watchInitializationJson(), equalTo(true));
            assertThat(System.getProperty("mockserver.watchInitializationJson"), equalTo("true"));
            assertThat(configuration.watchInitializationJson(), equalTo(true));
            ConfigurationProperties.watchInitializationJson(original);

            // when - setter
            configuration.watchInitializationJson(true);

            // then - getter
            assertThat(configuration.watchInitializationJson(), equalTo(true));
        } finally {
            ConfigurationProperties.watchInitializationJson(original);
        }
    }

    @Test
    public void shouldSetAndGetFailOnInitializationError() {
        boolean original = ConfigurationProperties.failOnInitializationError();
        try {
            // then - default value
            assertThat(configuration.failOnInitializationError(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.failOnInitializationError(true);

            // then - system property getter
            assertThat(ConfigurationProperties.failOnInitializationError(), equalTo(true));
            assertThat(System.getProperty("mockserver.failOnInitializationError"), equalTo("true"));
            assertThat(configuration.failOnInitializationError(), equalTo(true));
            ConfigurationProperties.failOnInitializationError(original);

            // when - setter
            configuration.failOnInitializationError(true);

            // then - getter
            assertThat(configuration.failOnInitializationError(), equalTo(true));
        } finally {
            ConfigurationProperties.failOnInitializationError(original);
        }
    }

    @Test
    public void shouldSetAndGetPersistExpectations() {
        boolean original = ConfigurationProperties.persistExpectations();
        try {
            // then - default value
            assertThat(configuration.persistExpectations(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.persistExpectations(true);

            // then - system property getter
            assertThat(ConfigurationProperties.persistExpectations(), equalTo(true));
            assertThat(System.getProperty("mockserver.persistExpectations"), equalTo("true"));
            assertThat(configuration.persistExpectations(), equalTo(true));
            ConfigurationProperties.persistExpectations(original);

            // when - setter
            configuration.persistExpectations(true);

            // then - getter
            assertThat(configuration.persistExpectations(), equalTo(true));
        } finally {
            ConfigurationProperties.persistExpectations(original);
        }
    }

    @Test
    public void shouldSetAndGetPersistedExpectationsPath() {
        String original = ConfigurationProperties.persistedExpectationsPath();
        try {
            // then - default value
            assertThat(configuration.persistedExpectationsPath(), equalTo("persistedExpectations.json"));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.persistedExpectationsPath(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.persistedExpectationsPath(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.persistedExpectationsPath"), equalTo(firstPath));
            assertThat(configuration.persistedExpectationsPath(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.persistedExpectationsPath(secondPath);

            // then - getter
            assertThat(configuration.persistedExpectationsPath(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.persistedExpectationsPath(original);
        }
    }

    @Test
    public void shouldSetAndGetMaximumNumberOfRequestToReturnInVerificationFailure() {
        int original = ConfigurationProperties.maximumNumberOfRequestToReturnInVerificationFailure();
        try {
            // then - default value
            assertThat(configuration.maximumNumberOfRequestToReturnInVerificationFailure(), equalTo(10));

            // when - system property setter
            ConfigurationProperties.maximumNumberOfRequestToReturnInVerificationFailure(5);

            // then - system property getter
            assertThat(ConfigurationProperties.maximumNumberOfRequestToReturnInVerificationFailure(), equalTo(5));
            assertThat(System.getProperty("mockserver.maximumNumberOfRequestToReturnInVerificationFailure"), equalTo("5"));
            assertThat(configuration.maximumNumberOfRequestToReturnInVerificationFailure(), equalTo(5));

            // when - setter
            String secondPath = tempFilePath();
            configuration.maximumNumberOfRequestToReturnInVerificationFailure(20);

            // then - getter
            assertThat(configuration.maximumNumberOfRequestToReturnInVerificationFailure(), equalTo(20));
        } finally {
            ConfigurationProperties.maximumNumberOfRequestToReturnInVerificationFailure(original);
        }
    }

    @Test
    public void shouldSetAndGetGenerateRealisticExampleValues() {
        boolean original = ConfigurationProperties.generateRealisticExampleValues();
        try {
            // then - default value
            assertThat(configuration.generateRealisticExampleValues(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.generateRealisticExampleValues(true);

            // then - system property getter
            assertThat(ConfigurationProperties.generateRealisticExampleValues(), equalTo(true));
            assertThat(System.getProperty("mockserver.generateRealisticExampleValues"), equalTo("true"));
            assertThat(configuration.generateRealisticExampleValues(), equalTo(true));
            ConfigurationProperties.generateRealisticExampleValues(original);

            // when - setter
            configuration.generateRealisticExampleValues(true);

            // then - getter
            assertThat(configuration.generateRealisticExampleValues(), equalTo(true));
        } finally {
            ConfigurationProperties.generateRealisticExampleValues(original);
        }
    }

    @Test
    public void shouldSetAndGetValidateProxyOpenAPISpec() {
        String original = ConfigurationProperties.validateProxyOpenAPISpec();
        try {
            // then - default value
            assertThat(configuration.validateProxyOpenAPISpec(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.validateProxyOpenAPISpec("https://example.com/spec.json");

            // then - system property getter
            assertThat(ConfigurationProperties.validateProxyOpenAPISpec(), equalTo("https://example.com/spec.json"));
            assertThat(System.getProperty("mockserver.validateProxyOpenAPISpec"), equalTo("https://example.com/spec.json"));
            assertThat(configuration.validateProxyOpenAPISpec(), equalTo("https://example.com/spec.json"));
            ConfigurationProperties.validateProxyOpenAPISpec(original);

            // when - setter
            configuration.validateProxyOpenAPISpec("inline-spec");

            // then - getter
            assertThat(configuration.validateProxyOpenAPISpec(), equalTo("inline-spec"));
        } finally {
            ConfigurationProperties.validateProxyOpenAPISpec(original);
        }
    }

    @Test
    public void shouldSetAndGetValidateProxyEnforce() {
        boolean original = ConfigurationProperties.validateProxyEnforce();
        try {
            // then - default value
            assertThat(configuration.validateProxyEnforce(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.validateProxyEnforce(true);

            // then - system property getter
            assertThat(ConfigurationProperties.validateProxyEnforce(), equalTo(true));
            assertThat(System.getProperty("mockserver.validateProxyEnforce"), equalTo("true"));
            assertThat(configuration.validateProxyEnforce(), equalTo(true));
            ConfigurationProperties.validateProxyEnforce(original);

            // when - setter
            configuration.validateProxyEnforce(true);

            // then - getter
            assertThat(configuration.validateProxyEnforce(), equalTo(true));
        } finally {
            ConfigurationProperties.validateProxyEnforce(original);
        }
    }

    @Test
    public void shouldSetAndGetAttachMismatchDiagnosticToResponse() {
        boolean original = ConfigurationProperties.attachMismatchDiagnosticToResponse();
        try {
            // then - default value (false)
            assertThat(configuration.attachMismatchDiagnosticToResponse(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.attachMismatchDiagnosticToResponse(true);

            // then - system property getter
            assertThat(ConfigurationProperties.attachMismatchDiagnosticToResponse(), equalTo(true));
            assertThat(System.getProperty("mockserver.attachMismatchDiagnosticToResponse"), equalTo("true"));
            assertThat(configuration.attachMismatchDiagnosticToResponse(), equalTo(true));
            ConfigurationProperties.attachMismatchDiagnosticToResponse(original);

            // when - setter
            configuration.attachMismatchDiagnosticToResponse(true);

            // then - getter
            assertThat(configuration.attachMismatchDiagnosticToResponse(), equalTo(true));
        } finally {
            ConfigurationProperties.attachMismatchDiagnosticToResponse(original);
        }
    }

    @Test
    public void shouldSetAndGetClosestMatchHintEnabled() {
        boolean original = ConfigurationProperties.closestMatchHintEnabled();
        try {
            // then - default value (true)
            assertThat(configuration.closestMatchHintEnabled(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.closestMatchHintEnabled(false);

            // then - system property getter
            assertThat(ConfigurationProperties.closestMatchHintEnabled(), equalTo(false));
            assertThat(System.getProperty("mockserver.closestMatchHintEnabled"), equalTo("false"));
            assertThat(configuration.closestMatchHintEnabled(), equalTo(false));
            ConfigurationProperties.closestMatchHintEnabled(original);

            // when - setter
            configuration.closestMatchHintEnabled(false);

            // then - getter
            assertThat(configuration.closestMatchHintEnabled(), equalTo(false));
        } finally {
            ConfigurationProperties.closestMatchHintEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetAttemptToProxyIfNoMatchingExpectation() {
        boolean original = ConfigurationProperties.attemptToProxyIfNoMatchingExpectation();
        try {
            // then - default value
            assertThat(configuration.attemptToProxyIfNoMatchingExpectation(), equalTo(true));

            // when - system property setter
            ConfigurationProperties.attemptToProxyIfNoMatchingExpectation(false);

            // then - system property getter
            assertThat(ConfigurationProperties.attemptToProxyIfNoMatchingExpectation(), equalTo(false));
            assertThat(System.getProperty("mockserver.attemptToProxyIfNoMatchingExpectation"), equalTo("false"));
            assertThat(configuration.attemptToProxyIfNoMatchingExpectation(), equalTo(false));
            ConfigurationProperties.attemptToProxyIfNoMatchingExpectation(original);

            // when - setter
            configuration.attemptToProxyIfNoMatchingExpectation(false);

            // then - getter
            assertThat(configuration.attemptToProxyIfNoMatchingExpectation(), equalTo(false));
        } finally {
            ConfigurationProperties.attemptToProxyIfNoMatchingExpectation(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardHttpProxy() {
        InetSocketAddress original = ConfigurationProperties.forwardHttpProxy();
        try {
            // then - default value
            assertThat(configuration.forwardHttpProxy(), equalTo(null));

            // when - system property setter
            ConfigurationProperties.forwardHttpProxy("127.0.0.1:1080");

            // then - system property getter
            assertThat(ConfigurationProperties.forwardHttpProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1080)));
            assertThat(System.getProperty("mockserver.forwardHttpProxy"), equalTo("127.0.0.1:1080"));
            assertThat(configuration.forwardHttpProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1080)));

            // when - setter
            configuration.forwardHttpProxy(new InetSocketAddress("127.0.0.1", 1090));

            // then - getter
            assertThat(configuration.forwardHttpProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1090)));
        } finally {
            ConfigurationProperties.forwardHttpProxy(original != null ? original.toString() : null);
        }
    }

    @Test
    public void shouldSetAndGetForwardHttpsProxy() {
        InetSocketAddress original = ConfigurationProperties.forwardHttpsProxy();
        try {
            // then - default value
            assertThat(configuration.forwardHttpsProxy(), equalTo(null));

            // when - system property setter
            ConfigurationProperties.forwardHttpsProxy("127.0.0.1:1080");

            // then - system property getter
            assertThat(ConfigurationProperties.forwardHttpsProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1080)));
            assertThat(System.getProperty("mockserver.forwardHttpsProxy"), equalTo("127.0.0.1:1080"));
            assertThat(configuration.forwardHttpsProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1080)));

            // when - setter
            configuration.forwardHttpsProxy(new InetSocketAddress("127.0.0.1", 1090));

            // then - getter
            assertThat(configuration.forwardHttpsProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1090)));
        } finally {
            ConfigurationProperties.forwardHttpsProxy(original != null ? original.toString() : null);
        }
    }

    @Test
    public void shouldSetAndGetForwardSocksProxy() {
        InetSocketAddress original = ConfigurationProperties.forwardSocksProxy();
        try {
            // then - default value
            assertThat(configuration.forwardSocksProxy(), equalTo(null));

            // when - system property setter
            ConfigurationProperties.forwardSocksProxy("127.0.0.1:1080");

            // then - system property getter
            assertThat(ConfigurationProperties.forwardSocksProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1080)));
            assertThat(System.getProperty("mockserver.forwardSocksProxy"), equalTo("127.0.0.1:1080"));
            assertThat(configuration.forwardSocksProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1080)));

            // when - setter
            configuration.forwardSocksProxy(new InetSocketAddress("127.0.0.1", 1090));

            // then - getter
            assertThat(configuration.forwardSocksProxy(), equalTo(new InetSocketAddress("127.0.0.1", 1090)));
        } finally {
            ConfigurationProperties.forwardSocksProxy(original != null ? original.toString() : null);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyAuthenticationUsername() {
        String original = ConfigurationProperties.forwardProxyAuthenticationUsername();
        try {
            // then - default value
            assertThat(configuration.forwardProxyAuthenticationUsername(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.forwardProxyAuthenticationUsername("john.doe");

            // then - system property getter
            assertThat(ConfigurationProperties.forwardProxyAuthenticationUsername(), equalTo("john.doe"));
            assertThat(System.getProperty("mockserver.forwardProxyAuthenticationUsername"), equalTo("john.doe"));
            assertThat(configuration.forwardProxyAuthenticationUsername(), equalTo("john.doe"));

            // when - setter
            configuration.forwardProxyAuthenticationUsername("fred.smith");

            // then - getter
            assertThat(configuration.forwardProxyAuthenticationUsername(), equalTo("fred.smith"));
        } finally {
            ConfigurationProperties.forwardProxyAuthenticationUsername(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyAuthenticationPassword() {
        String original = ConfigurationProperties.forwardProxyAuthenticationPassword();
        try {
            // then - default value
            assertThat(configuration.forwardProxyAuthenticationPassword(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.forwardProxyAuthenticationPassword("pa55w0rd");

            // then - system property getter
            assertThat(ConfigurationProperties.forwardProxyAuthenticationPassword(), equalTo("pa55w0rd"));
            assertThat(System.getProperty("mockserver.forwardProxyAuthenticationPassword"), equalTo("pa55w0rd"));
            assertThat(configuration.forwardProxyAuthenticationPassword(), equalTo("pa55w0rd"));

            // when - setter
            configuration.forwardProxyAuthenticationPassword("w0rdpa55");

            // then - getter
            assertThat(configuration.forwardProxyAuthenticationPassword(), equalTo("w0rdpa55"));
        } finally {
            ConfigurationProperties.forwardProxyAuthenticationPassword(original);
        }
    }

    @Test
    public void shouldSetAndGetProxyAuthenticationRealm() {
        String original = ConfigurationProperties.proxyAuthenticationRealm();
        try {
            // then - default value
            assertThat(configuration.proxyAuthenticationRealm(), equalTo("MockServer HTTP Proxy"));

            // when - system property setter
            ConfigurationProperties.proxyAuthenticationRealm("Some Realm");

            // then - system property getter
            assertThat(ConfigurationProperties.proxyAuthenticationRealm(), equalTo("Some Realm"));
            assertThat(System.getProperty("mockserver.proxyAuthenticationRealm"), equalTo("Some Realm"));
            assertThat(configuration.proxyAuthenticationRealm(), equalTo("Some Realm"));

            // when - setter
            configuration.proxyAuthenticationRealm("Some Other Realm");

            // then - getter
            assertThat(configuration.proxyAuthenticationRealm(), equalTo("Some Other Realm"));
        } finally {
            ConfigurationProperties.proxyAuthenticationRealm(original);
        }
    }

    @Test
    public void shouldSetAndGetProxyAuthenticationUsername() {
        String original = ConfigurationProperties.proxyAuthenticationUsername();
        try {
            // then - default value
            assertThat(configuration.proxyAuthenticationUsername(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.proxyAuthenticationUsername("john.doe");

            // then - system property getter
            assertThat(ConfigurationProperties.proxyAuthenticationUsername(), equalTo("john.doe"));
            assertThat(System.getProperty("mockserver.proxyAuthenticationUsername"), equalTo("john.doe"));
            assertThat(configuration.proxyAuthenticationUsername(), equalTo("john.doe"));

            // when - setter
            configuration.proxyAuthenticationUsername("fred.smith");

            // then - getter
            assertThat(configuration.proxyAuthenticationUsername(), equalTo("fred.smith"));
        } finally {
            ConfigurationProperties.proxyAuthenticationUsername(original);
        }
    }

    @Test
    public void shouldSetAndGetProxyAuthenticationPassword() {
        String original = ConfigurationProperties.proxyAuthenticationPassword();
        try {
            // then - default value
            assertThat(configuration.proxyAuthenticationPassword(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.proxyAuthenticationPassword("pa55w0rd");

            // then - system property getter
            assertThat(ConfigurationProperties.proxyAuthenticationPassword(), equalTo("pa55w0rd"));
            assertThat(System.getProperty("mockserver.proxyAuthenticationPassword"), equalTo("pa55w0rd"));
            assertThat(configuration.proxyAuthenticationPassword(), equalTo("pa55w0rd"));

            // when - setter
            configuration.proxyAuthenticationPassword("w0rdpa55");

            // then - getter
            assertThat(configuration.proxyAuthenticationPassword(), equalTo("w0rdpa55"));
        } finally {
            ConfigurationProperties.proxyAuthenticationPassword(original);
        }
    }

    @Test
    public void shouldSetAndGetLivenessHttpGetPath() {
        String original = ConfigurationProperties.livenessHttpGetPath();
        try {
            // then - default value
            assertThat(configuration.livenessHttpGetPath(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.livenessHttpGetPath("/liveness");

            // then - system property getter
            assertThat(ConfigurationProperties.livenessHttpGetPath(), equalTo("/liveness"));
            assertThat(System.getProperty("mockserver.livenessHttpGetPath"), equalTo("/liveness"));
            assertThat(configuration.livenessHttpGetPath(), equalTo("/liveness"));

            // when - setter
            configuration.livenessHttpGetPath("/livenessProbe");

            // then - getter
            assertThat(configuration.livenessHttpGetPath(), equalTo("/livenessProbe"));
        } finally {
            ConfigurationProperties.livenessHttpGetPath(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneTLSMutualAuthenticationRequired() {
        boolean original = ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired();
        try {
            // then - default value
            assertThat(configuration.controlPlaneTLSMutualAuthenticationRequired(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired(true);

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired(), equalTo(true));
            assertThat(System.getProperty("mockserver.controlPlaneTLSMutualAuthenticationRequired"), equalTo("true"));
            assertThat(configuration.controlPlaneTLSMutualAuthenticationRequired(), equalTo(true));
            ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired(original);

            // when - setter
            configuration.controlPlaneTLSMutualAuthenticationRequired(true);

            // then - getter
            assertThat(configuration.controlPlaneTLSMutualAuthenticationRequired(), equalTo(true));
        } finally {
            ConfigurationProperties.controlPlaneTLSMutualAuthenticationRequired(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneTLSMutualAuthenticationCAChain() {
        String original = ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain();
        try {
            // then - default value
            assertThat(configuration.controlPlaneTLSMutualAuthenticationCAChain(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.controlPlaneTLSMutualAuthenticationCAChain"), equalTo(firstPath));
            assertThat(configuration.controlPlaneTLSMutualAuthenticationCAChain(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.controlPlaneTLSMutualAuthenticationCAChain(secondPath);

            // then - getter
            assertThat(configuration.controlPlaneTLSMutualAuthenticationCAChain(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.controlPlaneTLSMutualAuthenticationCAChain(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlanePrivateKeyPath() {
        String original = ConfigurationProperties.controlPlanePrivateKeyPath();
        try {
            // then - default value
            assertThat(configuration.controlPlanePrivateKeyPath(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.controlPlanePrivateKeyPath(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlanePrivateKeyPath(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.controlPlanePrivateKeyPath"), equalTo(firstPath));
            assertThat(configuration.controlPlanePrivateKeyPath(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.controlPlanePrivateKeyPath(secondPath);

            // then - getter
            assertThat(configuration.controlPlanePrivateKeyPath(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.controlPlanePrivateKeyPath(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneX509CertificatePath() {
        String original = ConfigurationProperties.controlPlaneX509CertificatePath();
        try {
            // then - default value
            assertThat(configuration.controlPlaneX509CertificatePath(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.controlPlaneX509CertificatePath(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneX509CertificatePath(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.controlPlaneX509CertificatePath"), equalTo(firstPath));
            assertThat(configuration.controlPlaneX509CertificatePath(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.controlPlaneX509CertificatePath(secondPath);

            // then - getter
            assertThat(configuration.controlPlaneX509CertificatePath(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.controlPlaneX509CertificatePath(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneJWTAuthenticationRequired() {
        boolean original = ConfigurationProperties.controlPlaneJWTAuthenticationRequired();
        try {
            // then - default value
            assertThat(configuration.controlPlaneJWTAuthenticationRequired(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.controlPlaneJWTAuthenticationRequired(true);

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneJWTAuthenticationRequired(), equalTo(true));
            assertThat(System.getProperty("mockserver.controlPlaneJWTAuthenticationRequired"), equalTo("true"));
            assertThat(configuration.controlPlaneJWTAuthenticationRequired(), equalTo(true));
            ConfigurationProperties.controlPlaneJWTAuthenticationRequired(original);

            // when - setter
            configuration.controlPlaneJWTAuthenticationRequired(true);

            // then - getter
            assertThat(configuration.controlPlaneJWTAuthenticationRequired(), equalTo(true));
        } finally {
            ConfigurationProperties.controlPlaneJWTAuthenticationRequired(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneJWTAuthenticationJWKSource() {
        String original = ConfigurationProperties.controlPlaneJWTAuthenticationJWKSource();
        try {
            // then - default value
            assertThat(configuration.controlPlaneJWTAuthenticationJWKSource(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.controlPlaneJWTAuthenticationJWKSource(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneJWTAuthenticationJWKSource(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.controlPlaneJWTAuthenticationJWKSource"), equalTo(firstPath));
            assertThat(configuration.controlPlaneJWTAuthenticationJWKSource(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.controlPlaneJWTAuthenticationJWKSource(secondPath);

            // then - getter
            assertThat(configuration.controlPlaneJWTAuthenticationJWKSource(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.controlPlaneJWTAuthenticationJWKSource(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneJWTAuthenticationExpectedAudience() {
        String original = ConfigurationProperties.controlPlaneJWTAuthenticationExpectedAudience();
        try {
            // then - default value
            assertThat(configuration.controlPlaneJWTAuthenticationExpectedAudience(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.controlPlaneJWTAuthenticationExpectedAudience("https://mock-server.com");

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneJWTAuthenticationExpectedAudience(), equalTo("https://mock-server.com"));
            assertThat(System.getProperty("mockserver.controlPlaneJWTAuthenticationExpectedAudience"), equalTo("https://mock-server.com"));
            assertThat(configuration.controlPlaneJWTAuthenticationExpectedAudience(), equalTo("https://mock-server.com"));

            // when - setter
            configuration.controlPlaneJWTAuthenticationExpectedAudience("https://google.com");

            // then - getter
            assertThat(configuration.controlPlaneJWTAuthenticationExpectedAudience(), equalTo("https://google.com"));
        } finally {
            ConfigurationProperties.controlPlaneJWTAuthenticationExpectedAudience(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneJWTAuthenticationMatchingClaims() {
        Map<String, String> original = ConfigurationProperties.controlPlaneJWTAuthenticationMatchingClaims();
        try {
            // then - default value
            assertThat(configuration.controlPlaneJWTAuthenticationMatchingClaims(), equalTo(ImmutableMap.of()));

            // when - system property setter
            ConfigurationProperties.controlPlaneJWTAuthenticationMatchingClaims(ImmutableMap.of("sub", "john.doe", "scopes", "basic admin"));

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneJWTAuthenticationMatchingClaims(), equalTo(ImmutableMap.of("sub", "john.doe", "scopes", "basic admin")));
            assertThat(System.getProperty("mockserver.controlPlaneJWTAuthenticationMatchingClaims"), equalTo("sub=john.doe,scopes=basic admin"));
            assertThat(configuration.controlPlaneJWTAuthenticationMatchingClaims(), equalTo(ImmutableMap.of("sub", "john.doe", "scopes", "basic admin")));

            // when - setter
            configuration.controlPlaneJWTAuthenticationMatchingClaims(ImmutableMap.of("sub", "fred.smith"));

            // then - getter
            assertThat(configuration.controlPlaneJWTAuthenticationMatchingClaims(), equalTo(ImmutableMap.of("sub", "fred.smith")));
        } finally {
            ConfigurationProperties.controlPlaneJWTAuthenticationMatchingClaims(original);
        }
    }

    @Test
    public void shouldSetAndGetControlPlaneJWTAuthenticationRequiredClaims() {
        Set<String> original = ConfigurationProperties.controlPlaneJWTAuthenticationRequiredClaims();
        try {
            // then - default value
            assertThat(configuration.controlPlaneJWTAuthenticationRequiredClaims(), equalTo(ImmutableSet.of()));

            // when - system property setter
            ConfigurationProperties.controlPlaneJWTAuthenticationRequiredClaims(ImmutableSet.of("sub", "scopes"));

            // then - system property getter
            assertThat(ConfigurationProperties.controlPlaneJWTAuthenticationRequiredClaims(), equalTo(ImmutableSet.of("sub", "scopes")));
            assertThat(System.getProperty("mockserver.controlPlaneJWTAuthenticationRequiredClaims"), equalTo("sub,scopes"));
            assertThat(configuration.controlPlaneJWTAuthenticationRequiredClaims(), equalTo(ImmutableSet.of("sub", "scopes")));

            // when - setter
            configuration.controlPlaneJWTAuthenticationRequiredClaims(ImmutableSet.of("scopes"));

            // then - getter
            assertThat(configuration.controlPlaneJWTAuthenticationRequiredClaims(), equalTo(ImmutableSet.of("scopes")));
        } finally {
            ConfigurationProperties.controlPlaneJWTAuthenticationRequiredClaims(original);
        }
    }

    @Test
    public void shouldSetAndGetProactivelyInitialiseTLS() {
        boolean original = ConfigurationProperties.proactivelyInitialiseTLS();
        try {
            // then - default value
            assertThat(configuration.proactivelyInitialiseTLS(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.proactivelyInitialiseTLS(true);

            // then - system property getter
            assertThat(ConfigurationProperties.proactivelyInitialiseTLS(), equalTo(true));
            assertThat(System.getProperty("mockserver.proactivelyInitialiseTLS"), equalTo("true"));
            assertThat(configuration.proactivelyInitialiseTLS(), equalTo(true));
            ConfigurationProperties.proactivelyInitialiseTLS(original);

            // when - setter
            configuration.proactivelyInitialiseTLS(true);

            // then - getter
            assertThat(configuration.proactivelyInitialiseTLS(), equalTo(true));
        } finally {
            ConfigurationProperties.proactivelyInitialiseTLS(original);
        }
    }

    @Test
    public void shouldSetAndGetRebuildTLSContext() {
        // then - default value
        assertThat(configuration.rebuildTLSContext(), equalTo(false));

        // when - setter
        configuration.rebuildTLSContext(true);

        // then - getter
        assertThat(configuration.rebuildTLSContext(), equalTo(true));
    }

    @Test
    public void shouldSetAndGetRebuildServerTLSContext() {
        // then - default value
        assertThat(configuration.rebuildServerTLSContext(), equalTo(false));

        // when - setter
        configuration.rebuildServerTLSContext(true);

        // then - getter
        assertThat(configuration.rebuildServerTLSContext(), equalTo(true));
    }

    @Test
    public void shouldSetAndGetTlsProtocols() {
        String original = ConfigurationProperties.tlsProtocols();
        try {
            // then - default value
            assertThat(configuration.tlsProtocols(), equalTo("TLSv1,TLSv1.1,TLSv1.2"));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.tlsProtocols(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.tlsProtocols(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.tlsProtocols"), equalTo(firstPath));
            assertThat(configuration.tlsProtocols(), equalTo(firstPath));

            // when - setter
            configuration.tlsProtocols("TLSv1.2,TLSv1.3");

            // then - getter
            assertThat(configuration.tlsProtocols(), equalTo("TLSv1.2,TLSv1.3"));
        } finally {
            ConfigurationProperties.tlsProtocols(original);
        }
    }

    @Test
    public void shouldSetAndGetDynamicallyCreateCertificateAuthorityCertificate() {
        boolean original = ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate();
        try {
            // then - default value
            assertThat(configuration.dynamicallyCreateCertificateAuthorityCertificate(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate(true);

            // then - system property getter
            assertThat(ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate(), equalTo(true));
            assertThat(System.getProperty("mockserver.dynamicallyCreateCertificateAuthorityCertificate"), equalTo("true"));
            assertThat(configuration.dynamicallyCreateCertificateAuthorityCertificate(), equalTo(true));
            ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate(original);

            // when - setter
            configuration.dynamicallyCreateCertificateAuthorityCertificate(true);

            // then - getter
            assertThat(configuration.dynamicallyCreateCertificateAuthorityCertificate(), equalTo(true));
        } finally {
            ConfigurationProperties.dynamicallyCreateCertificateAuthorityCertificate(original);
        }
    }

    @Test
    public void shouldSetAndGetDirectoryToSaveDynamicSSLCertificate() {
        String original = ConfigurationProperties.directoryToSaveDynamicSSLCertificate();
        try {
            // then - default value
            assertThat(configuration.directoryToSaveDynamicSSLCertificate(), equalTo("."));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.directoryToSaveDynamicSSLCertificate(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.directoryToSaveDynamicSSLCertificate(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.directoryToSaveDynamicSSLCertificate"), equalTo(firstPath));
            assertThat(configuration.directoryToSaveDynamicSSLCertificate(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.directoryToSaveDynamicSSLCertificate(secondPath);

            // then - getter
            assertThat(configuration.directoryToSaveDynamicSSLCertificate(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.directoryToSaveDynamicSSLCertificate(original);
        }
    }

    @Test
    public void shouldSetAndGetPreventCertificateDynamicUpdate() {
        boolean original = ConfigurationProperties.preventCertificateDynamicUpdate();
        try {
            // then - default value
            assertThat(configuration.preventCertificateDynamicUpdate(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.preventCertificateDynamicUpdate(true);

            // then - system property getter
            assertThat(ConfigurationProperties.preventCertificateDynamicUpdate(), equalTo(true));
            assertThat(System.getProperty("mockserver.preventCertificateDynamicUpdate"), equalTo("true"));
            assertThat(configuration.preventCertificateDynamicUpdate(), equalTo(true));
            ConfigurationProperties.preventCertificateDynamicUpdate(original);

            // when - setter
            configuration.preventCertificateDynamicUpdate(true);

            // then - getter
            assertThat(configuration.preventCertificateDynamicUpdate(), equalTo(true));
        } finally {
            ConfigurationProperties.preventCertificateDynamicUpdate(original);
        }
    }

    @Test
    public void shouldSetAndGetSslCertificateDomainName() {
        String original = ConfigurationProperties.sslCertificateDomainName();
        try {
            // then - default value
            assertThat(configuration.sslCertificateDomainName(), equalTo(KeyAndCertificateFactory.CERTIFICATE_DOMAIN));

            // when - system property setter
            ConfigurationProperties.sslCertificateDomainName("mock-server.co.uk");

            // then - system property getter
            assertThat(ConfigurationProperties.sslCertificateDomainName(), equalTo("mock-server.co.uk"));
            assertThat(System.getProperty("mockserver.sslCertificateDomainName"), equalTo("mock-server.co.uk"));
            assertThat(configuration.sslCertificateDomainName(), equalTo("mock-server.co.uk"));

            // when - setter
            configuration.sslCertificateDomainName("mock-server.org");

            // then - getter
            assertThat(configuration.sslCertificateDomainName(), equalTo("mock-server.org"));
        } finally {
            ConfigurationProperties.sslCertificateDomainName(original);
        }
    }

    @Test
    public void shouldSetAndGetSslSubjectAlternativeNameDomains() {
        Set<String> original = ConfigurationProperties.sslSubjectAlternativeNameDomains();
        try {
            // then - default value
            assertThat(configuration.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of("localhost")));

            // when - system property setter
            ConfigurationProperties.sslSubjectAlternativeNameDomains(ImmutableSet.of("mock-server.co.uk"));

            // then - system property getter
            assertThat(ConfigurationProperties.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of("mock-server.co.uk")));
            assertThat(System.getProperty("mockserver.sslSubjectAlternativeNameDomains"), equalTo("mock-server.co.uk"));
            assertThat(configuration.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of("mock-server.co.uk")));

            // when - setter
            configuration.sslSubjectAlternativeNameDomains(ImmutableSet.of("mock-server.org"));

            // then - getter
            assertThat(configuration.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of("mock-server.org")));
        } finally {
            ConfigurationProperties.sslSubjectAlternativeNameDomains(original);
        }
    }

    @Test
    public void shouldSetAndGetSslSubjectAlternativeNameIps() {
        Set<String> original = ConfigurationProperties.sslSubjectAlternativeNameIps();
        try {
            // then - default value
            assertThat(configuration.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of("127.0.0.1", "0.0.0.0")));

            // when - system property setter
            ConfigurationProperties.sslSubjectAlternativeNameIps(ImmutableSet.of("1.2.3.4", "5.6.7.8"));

            // then - system property getter
            assertThat(ConfigurationProperties.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of("1.2.3.4", "5.6.7.8")));
            assertThat(System.getProperty("mockserver.sslSubjectAlternativeNameIps"), equalTo("1.2.3.4,5.6.7.8"));
            assertThat(configuration.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of("1.2.3.4", "5.6.7.8")));

            // when - setter
            configuration.sslSubjectAlternativeNameIps(ImmutableSet.of("10.20.30.40", "50.60.70.80"));

            // then - getter
            assertThat(configuration.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of("10.20.30.40", "50.60.70.80")));
        } finally {
            ConfigurationProperties.sslSubjectAlternativeNameIps(original);
        }
    }

    @Test
    public void shouldSetAndGetCertificateAuthorityPrivateKey() {
        String original = ConfigurationProperties.certificateAuthorityPrivateKey();
        try {
            // then - default value
            assertThat(configuration.certificateAuthorityPrivateKey(), equalTo(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_PRIVATE_KEY));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.certificateAuthorityPrivateKey(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.certificateAuthorityPrivateKey(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.certificateAuthorityPrivateKey"), equalTo(firstPath));
            assertThat(configuration.certificateAuthorityPrivateKey(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.certificateAuthorityPrivateKey(secondPath);

            // then - getter
            assertThat(configuration.certificateAuthorityPrivateKey(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.certificateAuthorityPrivateKey(original);
        }
    }

    @Test
    public void shouldSetAndGetCertificateAuthorityCertificate() {
        String original = ConfigurationProperties.certificateAuthorityCertificate();
        try {
            // then - default value
            assertThat(configuration.certificateAuthorityCertificate(), equalTo(ConfigurationProperties.DEFAULT_CERTIFICATE_AUTHORITY_X509_CERTIFICATE));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.certificateAuthorityCertificate(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.certificateAuthorityCertificate(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.certificateAuthorityCertificate"), equalTo(firstPath));
            assertThat(configuration.certificateAuthorityCertificate(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.certificateAuthorityCertificate(secondPath);

            // then - getter
            assertThat(configuration.certificateAuthorityCertificate(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.certificateAuthorityCertificate(original);
        }
    }

    @Test
    public void shouldSetAndGetPrivateKeyPath() {
        String original = ConfigurationProperties.privateKeyPath();
        try {
            // then - default value
            assertThat(configuration.privateKeyPath(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.privateKeyPath(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.privateKeyPath(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.privateKeyPath"), equalTo(firstPath));
            assertThat(configuration.privateKeyPath(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.privateKeyPath(secondPath);

            // then - getter
            assertThat(configuration.privateKeyPath(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.privateKeyPath(original);
        }
    }

    @Test
    public void shouldSetAndGetX509CertificatePath() {
        String original = ConfigurationProperties.x509CertificatePath();
        try {
            // then - default value
            assertThat(configuration.x509CertificatePath(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.x509CertificatePath(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.x509CertificatePath(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.x509CertificatePath"), equalTo(firstPath));
            assertThat(configuration.x509CertificatePath(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.x509CertificatePath(secondPath);

            // then - getter
            assertThat(configuration.x509CertificatePath(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.x509CertificatePath(original);
        }
    }

    @Test
    public void shouldSetAndGetTlsMutualAuthenticationRequired() {
        boolean original = ConfigurationProperties.tlsMutualAuthenticationRequired();
        try {
            // then - default value
            assertThat(configuration.tlsMutualAuthenticationRequired(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.tlsMutualAuthenticationRequired(true);

            // then - system property getter
            assertThat(ConfigurationProperties.tlsMutualAuthenticationRequired(), equalTo(true));
            assertThat(System.getProperty("mockserver.tlsMutualAuthenticationRequired"), equalTo("true"));
            assertThat(configuration.tlsMutualAuthenticationRequired(), equalTo(true));
            ConfigurationProperties.tlsMutualAuthenticationRequired(original);

            // when - setter
            configuration.tlsMutualAuthenticationRequired(true);

            // then - getter
            assertThat(configuration.tlsMutualAuthenticationRequired(), equalTo(true));
        } finally {
            ConfigurationProperties.tlsMutualAuthenticationRequired(original);
        }
    }

    @Test
    public void shouldSetAndGetTlsMutualAuthenticationCertificateChain() {
        String original = ConfigurationProperties.tlsMutualAuthenticationCertificateChain();
        try {
            // then - default value
            assertThat(configuration.tlsMutualAuthenticationCertificateChain(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.tlsMutualAuthenticationCertificateChain(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.tlsMutualAuthenticationCertificateChain(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.tlsMutualAuthenticationCertificateChain"), equalTo(firstPath));
            assertThat(configuration.tlsMutualAuthenticationCertificateChain(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.tlsMutualAuthenticationCertificateChain(secondPath);

            // then - getter
            assertThat(configuration.tlsMutualAuthenticationCertificateChain(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.tlsMutualAuthenticationCertificateChain(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyTLSX509CertificatesTrustManagerType() {
        ForwardProxyTLSX509CertificatesTrustManager original = ConfigurationProperties.forwardProxyTLSX509CertificatesTrustManagerType();
        try {
            // then - default value
            assertThat(configuration.forwardProxyTLSX509CertificatesTrustManagerType(), equalTo(ForwardProxyTLSX509CertificatesTrustManager.ANY));

            // when - system property setter
            ConfigurationProperties.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.JVM);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardProxyTLSX509CertificatesTrustManagerType(), equalTo(ForwardProxyTLSX509CertificatesTrustManager.JVM));
            assertThat(System.getProperty("mockserver.forwardProxyTLSX509CertificatesTrustManagerType"), equalTo("JVM"));
            assertThat(configuration.forwardProxyTLSX509CertificatesTrustManagerType(), equalTo(ForwardProxyTLSX509CertificatesTrustManager.JVM));

            // when - setter
            configuration.forwardProxyTLSX509CertificatesTrustManagerType(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM);

            // then - getter
            assertThat(configuration.forwardProxyTLSX509CertificatesTrustManagerType(), equalTo(ForwardProxyTLSX509CertificatesTrustManager.CUSTOM));
        } finally {
            ConfigurationProperties.forwardProxyTLSX509CertificatesTrustManagerType(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyTLSCustomTrustX509Certificates() {
        String original = ConfigurationProperties.forwardProxyTLSCustomTrustX509Certificates();
        try {
            // then - default value
            assertThat(configuration.forwardProxyTLSCustomTrustX509Certificates(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.forwardProxyTLSCustomTrustX509Certificates(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardProxyTLSCustomTrustX509Certificates(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.forwardProxyTLSCustomTrustX509Certificates"), equalTo(firstPath));
            assertThat(configuration.forwardProxyTLSCustomTrustX509Certificates(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.forwardProxyTLSCustomTrustX509Certificates(secondPath);

            // then - getter
            assertThat(configuration.forwardProxyTLSCustomTrustX509Certificates(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.forwardProxyTLSCustomTrustX509Certificates(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyPrivateKey() {
        String original = ConfigurationProperties.forwardProxyPrivateKey();
        try {
            // then - default value
            assertThat(configuration.forwardProxyPrivateKey(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.forwardProxyPrivateKey(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardProxyPrivateKey(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.forwardProxyPrivateKey"), equalTo(firstPath));
            assertThat(configuration.forwardProxyPrivateKey(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.forwardProxyPrivateKey(secondPath);

            // then - getter
            assertThat(configuration.forwardProxyPrivateKey(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.forwardProxyPrivateKey(original);
        }
    }

    @Test
    public void shouldSetAndGetForwardProxyCertificateChain() {
        String original = ConfigurationProperties.forwardProxyCertificateChain();
        try {
            // then - default value
            assertThat(configuration.forwardProxyCertificateChain(), equalTo(""));

            // when - system property setter
            String firstPath = tempFilePath();
            ConfigurationProperties.forwardProxyCertificateChain(firstPath);

            // then - system property getter
            assertThat(ConfigurationProperties.forwardProxyCertificateChain(), equalTo(firstPath));
            assertThat(System.getProperty("mockserver.forwardProxyCertificateChain"), equalTo(firstPath));
            assertThat(configuration.forwardProxyCertificateChain(), equalTo(firstPath));

            // when - setter
            String secondPath = tempFilePath();
            configuration.forwardProxyCertificateChain(secondPath);

            // then - getter
            assertThat(configuration.forwardProxyCertificateChain(), equalTo(secondPath));
        } finally {
            ConfigurationProperties.forwardProxyCertificateChain(original);
        }
    }

    @Test
    public void shouldSetAndGetAddSubjectAlternativeName() {
        Set<String> original = ConfigurationProperties.sslSubjectAlternativeNameDomains();
        try {
            // then - default value
            assertThat(configuration.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of("localhost")));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(false));

            // when - setter existing value
            configuration.addSubjectAlternativeName("localhost");

            // then - still default values
            assertThat(configuration.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of("localhost")));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(false));

            // when - setter
            configuration.addSubjectAlternativeName("mock-server.co.uk");

            // then - getter
            assertThat(configuration.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of("localhost", "mock-server.co.uk")));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(true));

            // when - clear
            configuration.rebuildServerTLSContext(false);
            configuration.clearSslSubjectAlternativeNameDomains();

            // then - getter
            assertThat(configuration.sslSubjectAlternativeNameDomains(), equalTo(ImmutableSet.of()));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(true));
        } finally {
            ConfigurationProperties.sslSubjectAlternativeNameDomains(original);
        }
    }

    @Test
    public void shouldSetAndGetAddSslSubjectAlternativeNameIps() {
        Set<String> original = ConfigurationProperties.sslSubjectAlternativeNameIps();
        try {
            // then - default value
            assertThat(configuration.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of("127.0.0.1", "0.0.0.0")));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(false));

            // when - setter existing value
            configuration.addSubjectAlternativeName("127.0.0.1");

            // then - still default values
            assertThat(configuration.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of("127.0.0.1", "0.0.0.0")));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(false));

            // when - setter
            configuration.addSubjectAlternativeName("1.2.3.4");

            // then - getter
            assertThat(configuration.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of("127.0.0.1", "0.0.0.0", "1.2.3.4")));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(true));

            // when - clear
            configuration.rebuildServerTLSContext(false);
            configuration.clearSslSubjectAlternativeNameIps();

            // then - getter
            assertThat(configuration.sslSubjectAlternativeNameIps(), equalTo(ImmutableSet.of()));
            assertThat(configuration.rebuildServerTLSContext(), equalTo(true));
        } finally {
            ConfigurationProperties.sslSubjectAlternativeNameIps(original);
        }
    }

    @Test
    public void shouldSetAndGetLogLevelOverrides() {
        Map<String, String> original = ConfigurationProperties.logLevelOverrides();
        try {
            assertThat(configuration.logLevelOverrides(), equalTo(Collections.emptyMap()));

            ConfigurationProperties.logLevelOverrides(ImmutableMap.of("MATCHING", "WARN", "EXPECTATION_MATCHED", "INFO"));

            assertThat(ConfigurationProperties.logLevelOverrides(), equalTo(ImmutableMap.of("MATCHING", "WARN", "EXPECTATION_MATCHED", "INFO")));
            assertThat(configuration.logLevelOverrides(), equalTo(ImmutableMap.of("MATCHING", "WARN", "EXPECTATION_MATCHED", "INFO")));

            configuration.logLevelOverrides(ImmutableMap.of("SERVER", "ERROR"));

            assertThat(configuration.logLevelOverrides(), equalTo(ImmutableMap.of("SERVER", "ERROR")));
        } finally {
            ConfigurationProperties.logLevelOverrides(original);
        }
    }

    @Test
    public void shouldSetAndGetLogLevelOverridesWithEmptyMap() {
        Map<String, String> original = ConfigurationProperties.logLevelOverrides();
        try {
            ConfigurationProperties.logLevelOverrides(ImmutableMap.of("MATCHING", "WARN"));
            assertThat(ConfigurationProperties.logLevelOverrides(), equalTo(ImmutableMap.of("MATCHING", "WARN")));

            ConfigurationProperties.logLevelOverrides(Collections.emptyMap());
            assertThat(ConfigurationProperties.logLevelOverrides(), equalTo(Collections.emptyMap()));
        } finally {
            ConfigurationProperties.logLevelOverrides(original);
        }
    }

    @Test
    public void shouldResolveEffectiveLevelWithEmptyOverrides() {
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                Collections.emptyMap(),
                Level.INFO
            ),
            equalTo(Level.INFO)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelWithNullOverrides() {
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                null,
                Level.INFO
            ),
            equalTo(Level.INFO)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelWithGroupOverride() {
        Map<String, String> overrides = ImmutableMap.of("MATCHING", "WARN");
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.WARN)
        );
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.WARN)
        );
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.NO_MATCH_RESPONSE,
                overrides,
                Level.INFO
            ),
            equalTo(Level.WARN)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelWithTypeOverrideTakesPrecedenceOverGroup() {
        Map<String, String> overrides = ImmutableMap.of("MATCHING", "WARN", "EXPECTATION_MATCHED", "DEBUG");
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.DEBUG)
        );
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.WARN)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelFallsBackToGlobalLevel() {
        Map<String, String> overrides = ImmutableMap.of("MATCHING", "WARN");
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.FORWARDED_REQUEST,
                overrides,
                Level.INFO
            ),
            equalTo(Level.INFO)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelCaseInsensitive() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put("matching", "warn");
        overrides.put("Expectation_Matched", "debug");
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.WARN)
        );
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.DEBUG)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelIgnoresInvalidLevelValues() {
        Map<String, String> overrides = ImmutableMap.of("MATCHING", "INVALID_LEVEL");
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.INFO)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelIgnoresUnknownKeys() {
        Map<String, String> overrides = ImmutableMap.of("FOOBAR", "WARN");
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.INFO)
        );
    }

    @Test
    public void shouldResolveEffectiveLevelIgnoresNullKeysAndValues() {
        Map<String, String> overrides = new HashMap<>();
        overrides.put(null, "WARN");
        overrides.put("MATCHING", null);
        overrides.put("VERIFICATION", "ERROR");
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.EXPECTATION_NOT_MATCHED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.INFO)
        );
        assertThat(
            LogEntry.LogMessageTypeCategory.resolveEffectiveLevel(
                LogEntry.LogMessageType.VERIFICATION_FAILED,
                overrides,
                Level.INFO
            ),
            equalTo(Level.ERROR)
        );
    }

    @Test
    public void shouldResolveCategoryForAllLogMessageTypes() {
        for (LogEntry.LogMessageType type : LogEntry.LogMessageType.values()) {
            if (type == LogEntry.LogMessageType.RUNNABLE) {
                assertThat(
                    LogEntry.LogMessageTypeCategory.categoryFor(type),
                    equalTo(null)
                );
            } else {
                assertThat(
                    "Expected category for " + type,
                    LogEntry.LogMessageTypeCategory.categoryFor(type) != null,
                    is(true)
                );
            }
        }
    }

    // Note: the 4 TLS-path fluent setters (certificateAuthorityPrivateKey,
    // certificateAuthorityCertificate, privateKeyPath, x509CertificatePath) intentionally
    // do NOT file-existence-check at setter time, because BCKeyAndCertificateFactory
    // sets these to the destination path BEFORE the dynamic CA / SSL files are written.
    // User typos are caught by CertificateConfigurationValidator at TLS-init time.

    @Test
    public void certificateAuthorityPrivateKeySetterStoresAnyValue() {
        // dynamic-generation paths may not exist yet — must be accepted without throwing
        String pending = "/does/not/exist/yet/ca-key.pem";
        configuration.certificateAuthorityPrivateKey(pending);
        assertThat(configuration.certificateAuthorityPrivateKey(), equalTo(pending));

        // null and "" must also be tolerated (matches the static-setter contract)
        configuration.certificateAuthorityPrivateKey(null);
        configuration.certificateAuthorityPrivateKey("");

        String valid = tempFilePath();
        configuration.certificateAuthorityPrivateKey(valid);
        assertThat(configuration.certificateAuthorityPrivateKey(), equalTo(valid));
    }

    @Test
    public void certificateAuthorityCertificateSetterStoresAnyValue() {
        String pending = "/does/not/exist/yet/ca.pem";
        configuration.certificateAuthorityCertificate(pending);
        assertThat(configuration.certificateAuthorityCertificate(), equalTo(pending));

        configuration.certificateAuthorityCertificate(null);
        configuration.certificateAuthorityCertificate("");

        String valid = tempFilePath();
        configuration.certificateAuthorityCertificate(valid);
        assertThat(configuration.certificateAuthorityCertificate(), equalTo(valid));
    }

    @Test
    public void privateKeyPathSetterStoresAnyValue() {
        String pending = "/does/not/exist/yet/private-key.pem";
        configuration.privateKeyPath(pending);
        assertThat(configuration.privateKeyPath(), equalTo(pending));

        configuration.privateKeyPath(null);
        configuration.privateKeyPath("");

        String valid = tempFilePath();
        configuration.privateKeyPath(valid);
        assertThat(configuration.privateKeyPath(), equalTo(valid));
    }

    @Test
    public void x509CertificatePathSetterStoresAnyValue() {
        String pending = "/does/not/exist/yet/cert.pem";
        configuration.x509CertificatePath(pending);
        assertThat(configuration.x509CertificatePath(), equalTo(pending));

        configuration.x509CertificatePath(null);
        configuration.x509CertificatePath("");

        String valid = tempFilePath();
        configuration.x509CertificatePath(valid);
        assertThat(configuration.x509CertificatePath(), equalTo(valid));
    }

    @Test
    public void shouldSetAndGetTransparentProxyEnabled() {
        boolean original = ConfigurationProperties.transparentProxyEnabled();
        try {
            // then - default value
            assertThat(configuration.transparentProxyEnabled(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.transparentProxyEnabled(true);

            // then - system property getter
            assertThat(ConfigurationProperties.transparentProxyEnabled(), equalTo(true));
            assertThat(System.getProperty("mockserver.transparentProxyEnabled"), equalTo("true"));
            assertThat(configuration.transparentProxyEnabled(), equalTo(true));

            // when - setter
            configuration.transparentProxyEnabled(false);

            // then - getter
            assertThat(configuration.transparentProxyEnabled(), equalTo(false));
        } finally {
            ConfigurationProperties.transparentProxyEnabled(original);
        }
    }

    @Test
    public void shouldSetAndGetAsyncKafkaBootstrapServers() {
        String original = ConfigurationProperties.asyncKafkaBootstrapServers();
        try {
            // then - default value
            assertThat(configuration.asyncKafkaBootstrapServers(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.asyncKafkaBootstrapServers("localhost:9092");

            // then - system property getter
            assertThat(ConfigurationProperties.asyncKafkaBootstrapServers(), equalTo("localhost:9092"));
            assertThat(System.getProperty("mockserver.asyncKafkaBootstrapServers"), equalTo("localhost:9092"));
            assertThat(configuration.asyncKafkaBootstrapServers(), equalTo("localhost:9092"));

            // when - setter
            configuration.asyncKafkaBootstrapServers("broker1:9093,broker2:9093");

            // then - getter
            assertThat(configuration.asyncKafkaBootstrapServers(), equalTo("broker1:9093,broker2:9093"));
        } finally {
            ConfigurationProperties.asyncKafkaBootstrapServers(original);
        }
    }

    @Test
    public void shouldSetAndGetAsyncMqttBrokerUrl() {
        String original = ConfigurationProperties.asyncMqttBrokerUrl();
        try {
            // then - default value
            assertThat(configuration.asyncMqttBrokerUrl(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.asyncMqttBrokerUrl("tcp://localhost:1883");

            // then - system property getter
            assertThat(ConfigurationProperties.asyncMqttBrokerUrl(), equalTo("tcp://localhost:1883"));
            assertThat(System.getProperty("mockserver.asyncMqttBrokerUrl"), equalTo("tcp://localhost:1883"));
            assertThat(configuration.asyncMqttBrokerUrl(), equalTo("tcp://localhost:1883"));

            // when - setter
            configuration.asyncMqttBrokerUrl("tcp://mqtt.example.com:1883");

            // then - getter
            assertThat(configuration.asyncMqttBrokerUrl(), equalTo("tcp://mqtt.example.com:1883"));
        } finally {
            ConfigurationProperties.asyncMqttBrokerUrl(original);
        }
    }

    @Test
    public void shouldSetAndGetAsyncAmqpUri() {
        String original = ConfigurationProperties.asyncAmqpUri();
        try {
            // then - default value
            assertThat(configuration.asyncAmqpUri(), equalTo(""));

            // when - system property setter
            ConfigurationProperties.asyncAmqpUri("amqp://guest:guest@localhost:5672/");

            // then - system property getter
            assertThat(ConfigurationProperties.asyncAmqpUri(), equalTo("amqp://guest:guest@localhost:5672/"));
            assertThat(System.getProperty("mockserver.asyncAmqpUri"), equalTo("amqp://guest:guest@localhost:5672/"));
            assertThat(configuration.asyncAmqpUri(), equalTo("amqp://guest:guest@localhost:5672/"));

            // when - setter
            configuration.asyncAmqpUri("amqp://user:pass@amqp.example.com:5672/vhost");

            // then - getter
            assertThat(configuration.asyncAmqpUri(), equalTo("amqp://user:pass@amqp.example.com:5672/vhost"));
        } finally {
            ConfigurationProperties.asyncAmqpUri(original);
        }
    }

    @Test
    public void shouldSetAndGetAsyncRecordedMessageMaxEntries() {
        int original = ConfigurationProperties.asyncRecordedMessageMaxEntries();
        try {
            // then - default value
            assertThat(configuration.asyncRecordedMessageMaxEntries(), equalTo(1000));

            // when - system property setter
            ConfigurationProperties.asyncRecordedMessageMaxEntries(500);

            // then - system property getter
            assertThat(ConfigurationProperties.asyncRecordedMessageMaxEntries(), equalTo(500));
            assertThat(System.getProperty("mockserver.asyncRecordedMessageMaxEntries"), equalTo("500"));
            assertThat(configuration.asyncRecordedMessageMaxEntries(), equalTo(500));

            // when - setter
            configuration.asyncRecordedMessageMaxEntries(2000);

            // then - getter
            assertThat(configuration.asyncRecordedMessageMaxEntries(), equalTo(2000));
        } finally {
            ConfigurationProperties.asyncRecordedMessageMaxEntries(original);
        }
    }

    // -- HTTP/3 QUIC transport parameters --

    @Test
    public void shouldSetAndGetHttp3MaxIdleTimeout() {
        // default value
        assertThat(configuration.http3MaxIdleTimeout(), equalTo(5000L));

        // when - instance setter
        configuration.http3MaxIdleTimeout(10000L);

        // then
        assertThat(configuration.http3MaxIdleTimeout(), equalTo(10000L));
    }

    @Test
    public void shouldSetAndGetHttp3InitialMaxData() {
        // default value
        assertThat(configuration.http3InitialMaxData(), equalTo(10000000L));

        // when - instance setter
        configuration.http3InitialMaxData(5000000L);

        // then
        assertThat(configuration.http3InitialMaxData(), equalTo(5000000L));
    }

    @Test
    public void shouldSetAndGetHttp3InitialMaxStreamDataBidirectional() {
        // default value
        assertThat(configuration.http3InitialMaxStreamDataBidirectional(), equalTo(1000000L));

        // when - instance setter
        configuration.http3InitialMaxStreamDataBidirectional(500000L);

        // then
        assertThat(configuration.http3InitialMaxStreamDataBidirectional(), equalTo(500000L));
    }

    @Test
    public void shouldSetAndGetHttp3InitialMaxStreamsBidirectional() {
        // default value
        assertThat(configuration.http3InitialMaxStreamsBidirectional(), equalTo(100L));

        // when - instance setter
        configuration.http3InitialMaxStreamsBidirectional(50L);

        // then
        assertThat(configuration.http3InitialMaxStreamsBidirectional(), equalTo(50L));
    }

    @Test
    public void shouldSetAndGetHttp3QpackMaxTableCapacity() {
        // default value (0 = dynamic table disabled)
        assertThat(configuration.http3QpackMaxTableCapacity(), equalTo(0L));

        // when - instance setter
        configuration.http3QpackMaxTableCapacity(4096L);

        // then
        assertThat(configuration.http3QpackMaxTableCapacity(), equalTo(4096L));
    }

    @Test
    public void shouldDelegateHttp3ConfigToConfigurationPropertiesWhenNull() {
        long originalTimeout = ConfigurationProperties.http3MaxIdleTimeout();
        long originalMaxData = ConfigurationProperties.http3InitialMaxData();
        long originalStreamData = ConfigurationProperties.http3InitialMaxStreamDataBidirectional();
        long originalMaxStreams = ConfigurationProperties.http3InitialMaxStreamsBidirectional();
        long originalQpack = ConfigurationProperties.http3QpackMaxTableCapacity();
        try {
            // when - set via system properties
            ConfigurationProperties.http3MaxIdleTimeout(15000L);
            ConfigurationProperties.http3InitialMaxData(20000000L);
            ConfigurationProperties.http3InitialMaxStreamDataBidirectional(2000000L);
            ConfigurationProperties.http3InitialMaxStreamsBidirectional(200L);
            ConfigurationProperties.http3QpackMaxTableCapacity(8192L);

            // then - Configuration delegates to ConfigurationProperties when field is null
            Configuration fresh = new Configuration();
            assertThat(fresh.http3MaxIdleTimeout(), equalTo(15000L));
            assertThat(fresh.http3InitialMaxData(), equalTo(20000000L));
            assertThat(fresh.http3InitialMaxStreamDataBidirectional(), equalTo(2000000L));
            assertThat(fresh.http3InitialMaxStreamsBidirectional(), equalTo(200L));
            assertThat(fresh.http3QpackMaxTableCapacity(), equalTo(8192L));
        } finally {
            ConfigurationProperties.http3MaxIdleTimeout(originalTimeout);
            ConfigurationProperties.http3InitialMaxData(originalMaxData);
            ConfigurationProperties.http3InitialMaxStreamDataBidirectional(originalStreamData);
            ConfigurationProperties.http3InitialMaxStreamsBidirectional(originalMaxStreams);
            ConfigurationProperties.http3QpackMaxTableCapacity(originalQpack);
        }
    }

    @Test
    public void shouldClampHttp3NumericPropertiesToNonNegative() {
        // when - set negative values via instance setters
        configuration.http3MaxIdleTimeout(-100L);
        configuration.http3InitialMaxData(-500L);
        configuration.http3InitialMaxStreamDataBidirectional(-200L);
        configuration.http3InitialMaxStreamsBidirectional(-10L);
        configuration.http3QpackMaxTableCapacity(-4096L);

        // then - all getters should clamp to 0
        assertThat("http3MaxIdleTimeout should be clamped to 0", configuration.http3MaxIdleTimeout(), equalTo(0L));
        assertThat("http3InitialMaxData should be clamped to 0", configuration.http3InitialMaxData(), equalTo(0L));
        assertThat("http3InitialMaxStreamDataBidirectional should be clamped to 0", configuration.http3InitialMaxStreamDataBidirectional(), equalTo(0L));
        assertThat("http3InitialMaxStreamsBidirectional should be clamped to 0", configuration.http3InitialMaxStreamsBidirectional(), equalTo(0L));
        assertThat("http3QpackMaxTableCapacity should be clamped to 0", configuration.http3QpackMaxTableCapacity(), equalTo(0L));
    }

    @Test
    public void shouldSetAndGetDevMode() {
        boolean original = ConfigurationProperties.devMode();
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        int originalMaxExpectations = ConfigurationProperties.maxExpectations();
        try {
            // then - default value
            assertThat(configuration.devMode(), equalTo(false));

            // when - system property setter
            ConfigurationProperties.devMode(true);

            // then - system property getter
            assertThat(ConfigurationProperties.devMode(), equalTo(true));
            assertThat(System.getProperty("mockserver.devMode"), equalTo("true"));
            assertThat(configuration.devMode(), equalTo(true));
            ConfigurationProperties.devMode(false);

            // when - setter
            configuration.devMode(true);

            // then - getter
            assertThat(configuration.devMode(), equalTo(true));
        } finally {
            ConfigurationProperties.devMode(original);
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
            ConfigurationProperties.maxExpectations(originalMaxExpectations);
        }
    }

    @Test
    public void shouldApplyDevModeDefaultsWhenEnabled() throws Exception {
        boolean originalDevMode = ConfigurationProperties.devMode();
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        int originalMaxExpectations = ConfigurationProperties.maxExpectations();
        try {
            // given - clear any explicitly-set maxLogEntries/maxExpectations (cache + system property) that
            // a prior or parallel test may have leaked into the shared static ConfigurationProperties state.
            // Dev mode only applies its defaults to properties the user has NOT explicitly set, so a leaked
            // value would otherwise make this assertion non-deterministic.
            java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> cache = (java.util.Map<String, String>) cacheField.get(null);
            cache.remove("mockserver.maxLogEntries");
            System.clearProperty("mockserver.maxLogEntries");
            cache.remove("mockserver.maxExpectations");
            System.clearProperty("mockserver.maxExpectations");

            // when
            ConfigurationProperties.devMode(true);

            // then
            assertThat("maxLogEntries should be dev default",
                ConfigurationProperties.maxLogEntries(), equalTo(ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES));
            assertThat("maxExpectations should be dev default",
                ConfigurationProperties.maxExpectations(), equalTo(ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS));
        } finally {
            ConfigurationProperties.devMode(originalDevMode);
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
            ConfigurationProperties.maxExpectations(originalMaxExpectations);
        }
    }

    @Test
    public void shouldNotOverrideExplicitMaxLogEntriesInDevMode() {
        boolean originalDevMode = ConfigurationProperties.devMode();
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        int originalMaxExpectations = ConfigurationProperties.maxExpectations();
        try {
            // given - user explicitly sets maxLogEntries (updates both cache and system property)
            ConfigurationProperties.maxLogEntries(5000);

            // when - dev mode is enabled
            ConfigurationProperties.devMode(true);

            // then - maxLogEntries should NOT be overridden (user's explicit value wins)
            assertThat("maxLogEntries should be the user's explicit value, not dev default",
                ConfigurationProperties.maxLogEntries(), equalTo(5000));
        } finally {
            ConfigurationProperties.devMode(originalDevMode);
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
            ConfigurationProperties.maxExpectations(originalMaxExpectations);
        }
    }

    @Test
    public void shouldApplyDevModeDefaultsViaSystemPropertyActivation() throws Exception {
        // This test verifies that setting devMode via system property (simulating
        // -Dmockserver.devMode=true or MOCKSERVER_DEV_MODE=true) applies the dev
        // defaults lazily in the getters, without requiring the devMode(boolean) setter.
        boolean originalDevMode = ConfigurationProperties.devMode();
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        int originalMaxExpectations = ConfigurationProperties.maxExpectations();
        try {
            // Access the private property cache via reflection to simulate a fresh JVM state
            java.lang.reflect.Field cacheField = ConfigurationProperties.class.getDeclaredField("propertyCache");
            cacheField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<String, String> cache = (java.util.Map<String, String>) cacheField.get(null);

            // 1. Set devMode=true directly via system property (NOT via the setter)
            //    to simulate -Dmockserver.devMode=true on the command line
            System.setProperty("mockserver.devMode", "true");
            cache.put("mockserver.devMode", "true");

            // 2. Clear maxLogEntries and maxExpectations from cache AND system property
            //    to simulate a fresh read (user has NOT set these explicitly)
            cache.remove("mockserver.maxLogEntries");
            System.clearProperty("mockserver.maxLogEntries");
            cache.remove("mockserver.maxExpectations");
            System.clearProperty("mockserver.maxExpectations");

            // 3. The lazy devModeDefaultOrHeapBased in the getters should apply dev defaults
            assertThat("maxLogEntries should be dev default when devMode set via system property",
                ConfigurationProperties.maxLogEntries(), equalTo(ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES));
            assertThat("maxExpectations should be dev default when devMode set via system property",
                ConfigurationProperties.maxExpectations(), equalTo(ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS));
        } finally {
            // Restore original values through the public setters (which update cache + system property)
            ConfigurationProperties.devMode(originalDevMode);
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
            ConfigurationProperties.maxExpectations(originalMaxExpectations);
        }
    }

    @Test
    public void shouldApplyDevModeDefaultsViaInstanceDevMode() {
        // Verify that setting devMode on a Configuration INSTANCE (not the global
        // ConfigurationProperties.devMode) makes maxExpectations/maxLogEntries
        // return the dev-mode defaults, without affecting the global state.
        boolean originalDevMode = ConfigurationProperties.devMode();
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        int originalMaxExpectations = ConfigurationProperties.maxExpectations();
        try {
            // Ensure global devMode is OFF
            ConfigurationProperties.devMode(false);

            // when - set devMode on the instance only
            Configuration cfg = new Configuration();
            cfg.devMode(true);

            // then - instance getters return dev defaults
            assertThat("instance maxExpectations should be dev default",
                cfg.maxExpectations(), equalTo(ConfigurationProperties.DEV_MODE_MAX_EXPECTATIONS));
            assertThat("instance maxLogEntries should be dev default",
                cfg.maxLogEntries(), equalTo(ConfigurationProperties.DEV_MODE_MAX_LOG_ENTRIES));

            // and - global getters are unaffected
            assertThat("global devMode should still be false",
                ConfigurationProperties.devMode(), equalTo(false));
        } finally {
            ConfigurationProperties.devMode(originalDevMode);
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
            ConfigurationProperties.maxExpectations(originalMaxExpectations);
        }
    }

    @Test
    public void shouldNotOverrideExplicitInstanceMaxInDevMode() {
        // Verify that an explicit maxExpectations/maxLogEntries set on the instance
        // takes priority over the instance devMode default.
        boolean originalDevMode = ConfigurationProperties.devMode();
        int originalMaxLogEntries = ConfigurationProperties.maxLogEntries();
        int originalMaxExpectations = ConfigurationProperties.maxExpectations();
        try {
            ConfigurationProperties.devMode(false);

            Configuration cfg = new Configuration();
            cfg.devMode(true);
            cfg.maxExpectations(42);
            cfg.maxLogEntries(99);

            assertThat("explicit instance maxExpectations wins over devMode default",
                cfg.maxExpectations(), equalTo(42));
            assertThat("explicit instance maxLogEntries wins over devMode default",
                cfg.maxLogEntries(), equalTo(99));
        } finally {
            ConfigurationProperties.devMode(originalDevMode);
            ConfigurationProperties.maxLogEntries(originalMaxLogEntries);
            ConfigurationProperties.maxExpectations(originalMaxExpectations);
        }
    }

}
package org.mockserver.configuration;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.mock.HttpState;
import org.mockserver.scheduler.Scheduler;
import org.mockserver.serialization.ObjectMapperFactory;
import org.mockserver.serialization.model.ConfigurationDTO;
import org.slf4j.event.Level;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockserver.configuration.Configuration.configuration;

/**
 * A caller who could name the MQTT keystore or truststore could make the MQTT client read any keystore-format file
 * the MockServer process can read, so the {@code asyncMqttSsl*} properties are startup-only, and so is
 * {@code asyncMqttBrokerUrl} while one is set, as it is then the only TLS broker a request may use:
 * {@code PUT /mockserver/configuration} never applies them (and says so, never showing a password), and
 * {@code GET /mockserver/configuration} never returns a password.
 */
// @ParallelStateGuardSuppress: isSensitivePropertyName(...) is a pure static predicate; every other value is set on a
// local Configuration instance, so the static store is never mutated
public class AsyncMqttSslStartupOnlyTest {

    private static class CapturingMockServerLogger extends MockServerLogger {
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, AsyncMqttSslStartupOnlyTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (logEntry.getLogLevel() == Level.WARN) {
                warnings.add(String.valueOf(logEntry.getMessage()));
            }
            super.logEvent(logEntry);
        }
    }

    // the location setters require the file to exist
    private static final String TRUSTSTORE = existingFile("truststore");
    private static final String KEYSTORE = existingFile("keystore");

    private ScheduledExecutorService schedulerExecutor;
    private Configuration configuration;
    private CapturingMockServerLogger logger;
    private HttpState httpState;

    @Before
    public void createHttpState() {
        configuration = configuration().logLevel(Level.WARN)
            .asyncMqttBrokerUrl("ssl://startup-broker:8883")
            .asyncMqttSslKeystoreLocation(KEYSTORE)
            .asyncMqttSslKeystorePassword("startup-keystore-secret")
            .asyncMqttSslKeystoreType("PKCS12")
            .asyncMqttSslTruststoreLocation(TRUSTSTORE)
            .asyncMqttSslTruststorePassword("startup-truststore-secret")
            .asyncMqttSslTruststoreType("JKS");
        logger = new CapturingMockServerLogger(configuration);
        schedulerExecutor = Executors.newScheduledThreadPool(1);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        httpState = new HttpState(configuration, logger, scheduler);
    }

    @After
    public void stop() {
        httpState.stop();
        schedulerExecutor.shutdownNow();
    }

    @Test
    public void shouldIgnoreAndWarnWhenAPutSuppliesALocationOrType() {
        put(new ConfigurationDTO()
            .setAsyncMqttSslKeystoreLocation("/root/.ssh/id_rsa")
            .setAsyncMqttSslKeystoreType("PKCS11")
            .setAsyncMqttSslTruststoreLocation("/proc/self/environ")
            .setAsyncMqttSslTruststoreType("BKS"));

        assertThat(configuration.asyncMqttSslKeystoreLocation(), is(KEYSTORE));
        assertThat(configuration.asyncMqttSslKeystoreType(), is("PKCS12"));
        assertThat(configuration.asyncMqttSslTruststoreLocation(), is(TRUSTSTORE));
        assertThat(configuration.asyncMqttSslTruststoreType(), is("JKS"));
        assertThat(logger.warnings, hasSize(4));
        assertThat(logger.warnings.get(0), containsString("asyncMqttSslKeystoreLocation is fixed at startup"));
        assertThat(logger.warnings.get(0), containsString("keystore-format file"));
        assertThat(logger.warnings.get(1), containsString("asyncMqttSslKeystoreType is fixed at startup"));
        assertThat(logger.warnings.get(2), containsString("asyncMqttSslTruststoreLocation is fixed at startup"));
        assertThat(logger.warnings.get(3), containsString("asyncMqttSslTruststoreType is fixed at startup"));
    }

    @Test
    public void shouldIgnoreAndWarnWhenAPutSuppliesTheBrokerUrl() {
        put(new ConfigurationDTO().setAsyncMqttBrokerUrl("ssl://attacker.example:8883"));

        assertThat(configuration.asyncMqttBrokerUrl(), is("ssl://startup-broker:8883"));
        assertThat(logger.warnings, hasSize(1));
        assertThat(logger.warnings.get(0), containsString("asyncMqttBrokerUrl is fixed at startup"));
        assertThat(logger.warnings.get(0), containsString("MQTT broker chosen at startup"));
    }

    @Test
    public void shouldApplyTheBrokerUrlWithoutWarningWhileNoMqttSslSettingIsSet() {
        Configuration plain = configuration().logLevel(Level.WARN).asyncMqttBrokerUrl("tcp://startup-broker:1883");
        CapturingMockServerLogger plainLogger = new CapturingMockServerLogger(plain);
        Scheduler scheduler = mock(Scheduler.class);
        when(scheduler.getExecutorService()).thenReturn(schedulerExecutor);
        HttpState plainState = new HttpState(plain, plainLogger, scheduler);
        try {
            ConfigurationDTO supplied = new ConfigurationDTO().setAsyncMqttBrokerUrl("ssl://elsewhere.example:8883");
            supplied.applyTo(plain);
            plainState.applyConfigurationUpdate(supplied);

            assertThat(plain.asyncMqttBrokerUrl(), is("ssl://elsewhere.example:8883"));
            assertThat(plainLogger.warnings, is(empty()));
        } finally {
            plainState.stop();
        }
    }

    @Test
    public void shouldCountAnyMqttSslSettingOrTheJvmKeystoreAsConfigured() {
        assertThat(AsyncMqttSsl.isConfigured(configuration(), null), is(false));
        assertThat(AsyncMqttSsl.isConfigured(configuration(), " "), is(false));
        assertThat(AsyncMqttSsl.isConfigured(configuration(), "/etc/jvm-keystore.p12"), is(true));
        assertThat(AsyncMqttSsl.isConfigured(configuration().asyncMqttSslTruststoreType("JKS"), null), is(true));
        assertThat(AsyncMqttSsl.isConfigured(configuration().asyncMqttSslKeystorePassword("p"), null), is(true));
        assertThat(AsyncMqttSsl.isConfigured(configuration, null), is(true));
    }

    @Test
    public void shouldIgnoreAndWarnWithoutShowingEitherValueWhenAPutSuppliesAPassword() throws Exception {
        put(ObjectMapperFactory.createObjectMapper().readValue(
            "{\"asyncMqttSslKeystorePassword\":\"put-keystore-secret\","
                + "\"asyncMqttSslTruststorePassword\":\"put-truststore-secret\"}",
            ConfigurationDTO.class));

        assertThat(configuration.asyncMqttSslKeystorePassword(), is("startup-keystore-secret"));
        assertThat(configuration.asyncMqttSslTruststorePassword(), is("startup-truststore-secret"));
        assertThat(logger.warnings, hasSize(2));
        assertThat(logger.warnings.get(0), containsString("asyncMqttSslKeystorePassword is fixed at startup"));
        assertThat(logger.warnings.get(1), containsString("asyncMqttSslTruststorePassword is fixed at startup"));
        for (String warning : logger.warnings) {
            assertThat(warning, not(containsString("secret")));
        }
    }

    @Test
    public void shouldNotWarnWhenAPutEchoesTheNonSecretValuesInForce() {
        put(new ConfigurationDTO()
            .setAsyncMqttBrokerUrl("ssl://startup-broker:8883")
            .setAsyncMqttSslKeystoreLocation(KEYSTORE)
            .setAsyncMqttSslKeystoreType("PKCS12")
            .setAsyncMqttSslTruststoreLocation(TRUSTSTORE)
            .setAsyncMqttSslTruststoreType("JKS"));

        assertThat(logger.warnings, is(empty()));
    }

    @Test
    public void shouldWarnEvenWhenAPutEchoesThePasswordsInForceSoAGuessCannotBeConfirmed() {
        put(new ConfigurationDTO()
            .setAsyncMqttSslKeystorePassword("startup-keystore-secret")
            .setAsyncMqttSslTruststorePassword("startup-truststore-secret"));

        assertThat(logger.warnings, hasSize(2));
        for (String warning : logger.warnings) {
            assertThat(warning, containsString("is fixed at startup"));
            assertThat(warning, not(containsString("secret")));
        }
    }

    @Test
    public void shouldNeverReturnAPasswordFromGetConfiguration() throws Exception {
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new ConfigurationDTO(configuration));

        assertThat(json, not(containsString("secret")));
        assertThat(json, not(containsString("asyncMqttSslKeystorePassword")));
        assertThat(json, not(containsString("asyncMqttSslTruststorePassword")));
        assertThat(json, containsString(KEYSTORE));
        assertThat(json.replaceAll("\\s", ""), containsString("\"asyncMqttSslTruststoreType\":\"JKS\""));
    }

    @Test
    public void shouldTreatThePasswordsAsSensitiveWhenPrinted() {
        assertThat(ConfigurationProperties.isSensitivePropertyName("mockserver.asyncMqttSslKeystorePassword"), is(true));
        assertThat(ConfigurationProperties.isSensitivePropertyName("mockserver.asyncMqttSslTruststorePassword"), is(true));
    }

    @Test
    public void shouldBuildAStartupConfigurationCarryingThem() {
        Configuration built = new ConfigurationDTO()
            .setAsyncMqttBrokerUrl("ssl://startup-broker:8883")
            .setAsyncMqttSslKeystoreLocation(KEYSTORE)
            .setAsyncMqttSslKeystorePassword("startup-keystore-secret")
            .setAsyncMqttSslKeystoreType("PKCS12")
            .setAsyncMqttSslTruststoreLocation(TRUSTSTORE)
            .setAsyncMqttSslTruststorePassword("startup-truststore-secret")
            .setAsyncMqttSslTruststoreType("JKS")
            .buildObject();

        assertThat(built.asyncMqttBrokerUrl(), is("ssl://startup-broker:8883"));
        assertThat(built.asyncMqttSslKeystoreLocation(), is(KEYSTORE));
        assertThat(built.asyncMqttSslKeystorePassword(), is("startup-keystore-secret"));
        assertThat(built.asyncMqttSslKeystoreType(), is("PKCS12"));
        assertThat(built.asyncMqttSslTruststoreLocation(), is(TRUSTSTORE));
        assertThat(built.asyncMqttSslTruststorePassword(), is("startup-truststore-secret"));
        assertThat(built.asyncMqttSslTruststoreType(), is("JKS"));
    }

    @Test
    public void shouldRefuseALocationThatDoesNotExist() {
        for (String property : new String[]{"keystore", "truststore"}) {
            RuntimeException refused = null;
            try {
                if ("keystore".equals(property)) {
                    configuration().asyncMqttSslKeystoreLocation("/does/not/exist/keystore.jks");
                } else {
                    configuration().asyncMqttSslTruststoreLocation("/does/not/exist/truststore.jks");
                }
            } catch (RuntimeException e) {
                refused = e;
            }

            assertThat(property, refused, is(notNullValue()));
            assertThat(property, refused.getMessage(), containsString("does not exist or is not accessible"));
        }
    }

    private static String existingFile(String prefix) {
        try {
            File file = Files.createTempFile(prefix, ".jks").toFile();
            file.deleteOnExit();
            return file.getAbsolutePath();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private void put(ConfigurationDTO supplied) {
        logger.warnings.clear();
        supplied.applyTo(configuration);
        httpState.applyConfigurationUpdate(supplied);
    }
}

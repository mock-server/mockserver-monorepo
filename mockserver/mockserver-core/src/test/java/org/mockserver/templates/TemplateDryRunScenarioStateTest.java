package org.mockserver.templates;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.Test;
import org.mockserver.mock.CrossProtocolEventBus;
import org.mockserver.mock.ScenarioManager;
import org.mockserver.templates.engine.ScenarioStateTemplateTestLock;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.startsWith;
import static org.mockserver.configuration.Configuration.configuration;
import static org.mockserver.templates.TemplateDryRunTest.body;
import static org.mockserver.templates.TemplateDryRunTest.dryRun;
import static org.mockserver.templates.TemplateDryRunTest.input;

/**
 * The template dry run against live scenario state. It swaps the {@link ScenarioManager} on the process-global
 * {@link CrossProtocolEventBus}, so it runs in the sequential Surefire phase, apart from {@link TemplateDryRunTest}.
 */
public class TemplateDryRunScenarioStateTest {

    @Test
    public void doesNotChangeScenarioStateAndSaysSo() throws Exception {
        ScenarioStateTemplateTestLock.LOCK.lock();
        ScenarioManager previous = CrossProtocolEventBus.getInstance().getScenarioManager();
        try {
            ScenarioManager scenarioManager = new ScenarioManager();
            CrossProtocolEventBus.getInstance().setScenarioManager(scenarioManager);

            JsonNode report = body(dryRun(new TemplateDryRun(configuration()),
                input("VELOCITY", "$scenario.set('checkout', 'paid'){ \"statusCode\": 200, \"body\": \"$scenario.get('checkout')\" }")));

            assertThat(report.path("valid").asBoolean(), is(true));
            assertThat(report.path("renderedResponse").path("body").asText(), is(ScenarioManager.STARTED));
            assertThat(report.path("warnings").get(0).path("message").asText(), startsWith("scenario.set(\"checkout\", \"paid\") was not applied"));
            assertThat(scenarioManager.getState("checkout"), is(ScenarioManager.STARTED));
        } finally {
            CrossProtocolEventBus.getInstance().setScenarioManager(previous);
            ScenarioStateTemplateTestLock.LOCK.unlock();
        }
    }
}

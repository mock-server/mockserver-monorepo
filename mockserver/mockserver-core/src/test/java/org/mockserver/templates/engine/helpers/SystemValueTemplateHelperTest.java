package org.mockserver.templates.engine.helpers;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockserver.log.model.LogEntry;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The names {@code systemValue} never reads, whatever the allow list says. Values come from fake lookups, so a
 * refused name is proven refused while it is set, without changing the JVM's own properties.
 */
public class SystemValueTemplateHelperTest {

    private static final Map<String, String> ENVIRONMENT = Map.of(
        "MOCKSERVER_CONTROL_PLANE_JWT_SECRET", "env-secret",
        "mockserver_lower_case", "env-secret",
        "MOCKSERVER", "not-a-setting",
        "APP_REGION", "eu-west-2",
        "APP_UPSTREAM_TOKEN", "Bearer sk-live-upstream"
    );
    private static final String FORWARD_CREDENTIALS = "api.example.com=Authorization:APP_UPSTREAM_TOKEN";
    private static final Map<String, String> PROPERTIES = Map.of(
        "mockserver.tlsMutualAuthenticationCertificateChain", "property-secret",
        "MockServer.mixedCase", "property-secret",
        "javax.net.ssl.keyStorePassword", "changeit",
        "javax.net.ssl.trustStorePassword", "changeit",
        "javax.net.ssl.keyStore", "/keystore.jks",
        "app.version", "1.4.2"
    );

    private static String uniqueAllowList(String names) {
        return names + ",UNUSED_" + UUID.randomUUID().toString().replace('-', '_');
    }

    private static SystemValueTemplateHelper helper(String allowList, MockServerLogger mockServerLogger) {
        return new SystemValueTemplateHelper(allowList, mockServerLogger, ENVIRONMENT::get, PROPERTIES::get);
    }

    private static MockServerLogger warnEnabledLogger() {
        MockServerLogger mockServerLogger = mock(MockServerLogger.class);
        when(mockServerLogger.isEnabledForInstance(any(Level.class))).thenReturn(true);
        return mockServerLogger;
    }

    private static List<String> warnings(MockServerLogger mockServerLogger) {
        ArgumentCaptor<LogEntry> logEntries = ArgumentCaptor.forClass(LogEntry.class);
        verify(mockServerLogger, atLeast(0)).logEvent(logEntries.capture());
        return logEntries.getAllValues().stream()
            .filter(logEntry -> logEntry.getLogLevel() == Level.WARN)
            .map(LogEntry::getMessage)
            .collect(Collectors.toList());
    }

    @Test
    public void shouldNeverReadMockServerSettingsOrStorePasswordsWhateverTheListSays() {
        SystemValueTemplateHelper helper = helper(uniqueAllowList("MOCKSERVER_*,mockserver*,M*,MockServer.mixedCase,javax.net.ssl.*,APP_*,app.version"), warnEnabledLogger());

        assertThat(helper.env("MOCKSERVER_CONTROL_PLANE_JWT_SECRET"), is(""));
        assertThat(helper.env("mockserver_lower_case"), is(""));
        assertThat(helper.property("mockserver.tlsMutualAuthenticationCertificateChain"), is(""));
        assertThat(helper.property("MockServer.mixedCase"), is(""));
        assertThat(helper.property("javax.net.ssl.keyStorePassword"), is(""));
        assertThat(helper.property("javax.net.ssl.trustStorePassword"), is(""));

        assertThat(helper.env("MOCKSERVER"), is("not-a-setting"));
        assertThat(helper.env("APP_REGION"), is("eu-west-2"));
        assertThat(helper.property("javax.net.ssl.keyStore"), is("/keystore.jks"));
        assertThat(helper.property("app.version"), is("1.4.2"));
    }

    @Test
    public void shouldWarnOnceThatAReservedNameIsNeverReadable() {
        MockServerLogger mockServerLogger = warnEnabledLogger();
        SystemValueTemplateHelper helper = helper(uniqueAllowList("MOCKSERVER_*"), mockServerLogger);

        for (int i = 0; i < 3; i++) {
            helper.env("MOCKSERVER_CONTROL_PLANE_JWT_SECRET");
        }

        List<String> warnings = warnings(mockServerLogger);
        assertThat(warnings, hasSize(1));
        assertThat(warnings.get(0), containsString("MOCKSERVER_CONTROL_PLANE_JWT_SECRET"));
        assertThat(warnings.get(0), containsString("never readable"));
        assertThat(warnings.get(0), not(containsString("env-secret")));
    }

    @Test
    public void shouldWarnAboutAllowListEntriesThatCoverReservedNames() {
        MockServerLogger mockServerLogger = warnEnabledLogger();

        SystemValueTemplateHelper.logAllowList(uniqueAllowList("MOCKSERVER_*,mock*,mockserver.logLevel,javax.net.ssl.*,javax.net.ssl.keyStorePassword,APP_*,app.version"), mockServerLogger);

        List<String> warnings = warnings(mockServerLogger);
        assertThat(warnings, hasSize(2));
        assertThat(warnings.get(0), containsString("MockServer's own settings"));
        assertThat(warnings.get(0), containsString("MOCKSERVER_*, mock*, mockserver.logLevel, javax.net.ssl.*, javax.net.ssl.keyStorePassword\n"));
        assertThat(warnings.get(1), containsString("templates can read"));
        assertThat(warnings.get(1), containsString("app.version"));
    }

    @Test
    public void shouldWarnAboutIgnoredEntries() {
        MockServerLogger mockServerLogger = warnEnabledLogger();

        SystemValueTemplateHelper.logAllowList(uniqueAllowList("*,P*TH,APP_*"), mockServerLogger);

        List<String> warnings = warnings(mockServerLogger);
        assertThat(warnings, hasSize(2));
        assertThat(warnings.get(0), containsString("*, P*TH\n"));
        assertThat(warnings.get(0), containsString("were ignored"));
        assertThat(warnings.get(1), containsString("templates can read"));
    }

    @Test
    public void shouldLogWhatTemplatesCanReadOnEveryServerStartWhateverParsedTheListFirst() {
        String allowList = uniqueAllowList("APP_*");
        helper(allowList, warnEnabledLogger()).env("APP_REGION");
        MockServerLogger firstStart = warnEnabledLogger();
        MockServerLogger secondStart = warnEnabledLogger();

        SystemValueTemplateHelper.logAllowList(allowList, firstStart);
        SystemValueTemplateHelper.logAllowList(allowList, secondStart);

        assertThat(warnings(firstStart), hasSize(1));
        assertThat(warnings(firstStart).get(0), containsString("templates can read"));
        assertThat(warnings(secondStart), hasSize(1));
    }

    @Test
    public void shouldLogNothingAtStartForAnEmptyList() {
        MockServerLogger mockServerLogger = warnEnabledLogger();

        SystemValueTemplateHelper.logAllowList("", mockServerLogger);
        SystemValueTemplateHelper.logAllowList(null, mockServerLogger);

        assertThat(warnings(mockServerLogger), is(empty()));
    }

    @Test
    public void shouldNotLetADryRunUseUpTheServersRefusalWarning() {
        String allowList = uniqueAllowList("APP_*");
        MockServerLogger dryRunLogger = warnEnabledLogger();
        MockServerLogger serverLogger = warnEnabledLogger();

        helper(allowList, dryRunLogger).env("HOME");
        helper(allowList, serverLogger).env("HOME");
        helper(allowList, serverLogger).env("HOME");

        assertThat(warnings(dryRunLogger), hasSize(1));
        assertThat(warnings(serverLogger), hasSize(1));
    }

    @Test
    public void shouldNeverReadAVariableForwardCredentialHeadersByHostReadsWhateverTheListSays() {
        MockServerLogger mockServerLogger = warnEnabledLogger();
        SystemValueTemplateHelper helper = new SystemValueTemplateHelper(uniqueAllowList("APP_*,APP_UPSTREAM_TOKEN"), FORWARD_CREDENTIALS, mockServerLogger, ENVIRONMENT::get, PROPERTIES::get);

        assertThat(helper.env("APP_UPSTREAM_TOKEN"), is(""));
        assertThat(helper.env("app_upstream_token"), is(""));
        assertThat(helper.env("APP_REGION"), is("eu-west-2"));
        List<String> warnings = warnings(mockServerLogger);
        assertThat(warnings, hasSize(2));
        assertThat(warnings.get(0), containsString("never readable"));
        assertThat(String.join("\n", warnings), not(containsString("sk-live-upstream")));
    }

    @Test
    public void shouldNeverReadAVariableNamedInAForwardCredentialEntryThePropertyIgnores() {
        SystemValueTemplateHelper helper = new SystemValueTemplateHelper(uniqueAllowList("APP_*"), "api.example.com Authorization:APP_UPSTREAM_TOKEN,http://api.example.com=X-Region:APP_REGION", null, ENVIRONMENT::get, PROPERTIES::get);

        assertThat(helper.env("APP_UPSTREAM_TOKEN"), is(""));
        assertThat(helper.env("APP_REGION"), is(""));
    }

    @Test
    public void shouldWarnAtStartWhenTheListCoversAForwardCredentialVariableWithoutNamingIt() {
        MockServerLogger mockServerLogger = warnEnabledLogger();

        SystemValueTemplateHelper.logAllowList(uniqueAllowList("app_*"), FORWARD_CREDENTIALS + ",b.example=X-Other:OTHER_TOKEN", mockServerLogger);

        List<String> warnings = warnings(mockServerLogger);
        assertThat(warnings, hasSize(2));
        assertThat(warnings.get(0), containsString("templateSystemValueAllowList covers the environment variables forwardCredentialHeadersByHost reads for"));
        assertThat(warnings.get(0), containsString("entry 1 header 1"));
        assertThat(warnings.get(0), not(containsString("api.example.com")));
        assertThat(warnings.get(0), not(containsString("X-Other")));
        assertThat(warnings.get(0), not(containsString("APP_UPSTREAM_TOKEN")));
        assertThat(warnings.get(1), containsString("templates can read"));
    }

    @Test
    public void shouldNotWarnAtStartWhenTheListCoversNoForwardCredentialVariable() {
        MockServerLogger mockServerLogger = warnEnabledLogger();

        SystemValueTemplateHelper.logAllowList(uniqueAllowList("REGION_*"), FORWARD_CREDENTIALS, mockServerLogger);

        assertThat(warnings(mockServerLogger), hasSize(1));
        assertThat(warnings(mockServerLogger).get(0), containsString("templates can read"));
    }
}

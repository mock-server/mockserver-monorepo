package org.mockserver.configuration;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static java.util.Collections.emptySet;
import static org.hamcrest.CoreMatchers.everyItem;
import static org.hamcrest.CoreMatchers.hasItem;
import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.CoreMatchers.not;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.mockserver.configuration.ConfigurationProperties.editDistance;
import static org.mockserver.configuration.ConfigurationProperties.findUnknownConfigurationKeys;
import static org.mockserver.configuration.ConfigurationProperties.findUnrecognisedConfigurationKeys;
import static org.mockserver.configuration.ConfigurationProperties.suggestRecognisedKeys;
import static org.mockserver.configuration.ConfigurationProperties.recognisedEnvironmentVariableKeys;
import static org.mockserver.configuration.ConfigurationProperties.recognisedSystemPropertyKeys;

/**
 * Verifies that unrecognised {@code mockserver.*} system properties, {@code MOCKSERVER_*}
 * environment variables, and {@code mockserver.*} properties-file keys are detected (so they
 * can be warned about), while recognised and unrelated keys are not flagged.
 *
 * <p>Uses the pure {@link ConfigurationProperties#findUnknownConfigurationKeys} helper with
 * explicit inputs, so it neither reads nor mutates global system-property / env state and is
 * safe in the parallel Surefire phase.</p>
 */
// @ParallelStateGuardSuppress: normaliseKeyForSuggestion(...) and findUnknownConfigurationKeys(...) are
// pure static functions over explicit inputs (the guard's setter pattern false-positives on them).
public class ConfigurationPropertiesUnknownKeyTest {

    private static Properties props(String... keys) {
        Properties properties = new Properties();
        for (String key : keys) {
            properties.setProperty(key, "value");
        }
        return properties;
    }

    // --- recognised-key enumeration (single source of truth, no drift) ---

    @Test
    public void recognisedKeysAreEnumeratedFromConstants() {
        Set<String> systemPropertyKeys = recognisedSystemPropertyKeys();
        Set<String> environmentVariableKeys = recognisedEnvironmentVariableKeys();

        // A representative selection of well-known properties must be recognised.
        assertThat(systemPropertyKeys, hasItem("mockserver.maxExpectations"));
        assertThat(systemPropertyKeys, hasItem("mockserver.metricsEnabled"));
        assertThat(systemPropertyKeys, hasItem("mockserver.logLevel"));

        assertThat(environmentVariableKeys, hasItem("MOCKSERVER_MAX_EXPECTATIONS"));
        assertThat(environmentVariableKeys, hasItem("MOCKSERVER_METRICS_ENABLED"));
        assertThat(environmentVariableKeys, hasItem("MOCKSERVER_LOG_LEVEL"));

        // Every recognised system-property key is in the mockserver. namespace.
        assertThat(systemPropertyKeys, everyItem(containsString("mockserver.")));
        // Every recognised environment-variable key is in the MOCKSERVER_ namespace.
        assertThat(environmentVariableKeys, everyItem(containsString("MOCKSERVER_")));
    }

    // --- (a) recognised key -> no warning ---

    @Test
    public void recognisedSystemPropertyProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.maxExpectations"), emptySet(), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void recognisedEnvironmentVariableProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("MOCKSERVER_MAX_EXPECTATIONS"), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void recognisedPropertiesFileKeyProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), emptySet(), props("mockserver.metricsEnabled", "mockserver.logLevel"));
        assertThat(unknown, is(empty()));
    }

    // --- (a2) launcher / CLI keys not declared as ConfigurationProperties constants -> no warning ---

    @Test
    public void serverPortKeysProduceNoWarning() {
        // mockserver.serverPort / MOCKSERVER_SERVER_PORT are the primary documented port knobs,
        // handled by the CLI launcher (Main.startServer), not by a MOCKSERVER_* constant.
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.serverPort"),
            Set.of("MOCKSERVER_SERVER_PORT"),
            props("mockserver.serverPort"));
        assertThat(unknown, is(empty()));
    }

    @Test
    public void launcherEnvironmentVariableProducesNoWarning() {
        // MOCKSERVER_LAUNCHER is exported by the binary-launcher scripts and explicitly excluded
        // from config loading by Main; it must not be flagged as an unknown configuration key.
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("MOCKSERVER_LAUNCHER"), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void otherLauncherAndPluginKeysProduceNoWarning() {
        // MOCKSERVER_JAVA_OPTS (launcher JVM opts), mockserver.mockServerPort (maven plugin),
        // and mockserver.launcherName (usage-text label) are all legitimate non-constant keys.
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.mockServerPort", "mockserver.launcherName"),
            Set.of("MOCKSERVER_JAVA_OPTS"),
            props());
        assertThat(unknown, is(empty()));
    }

    // --- (b) unrecognised key -> exactly one warning naming the key ---

    @Test
    public void unrecognisedSystemPropertyProducesExactlyOneWarningNamingTheKey() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.maxExpectatons"), emptySet(), props());
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("mockserver.maxExpectatons"));
        assertThat(unknown.get(0), containsString("system property"));
    }

    @Test
    public void unrecognisedEnvironmentVariableProducesExactlyOneWarningNamingTheKey() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("MOCKSERVER_METRICS_ENABLE"), props());
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("MOCKSERVER_METRICS_ENABLE"));
        assertThat(unknown.get(0), containsString("environment variable"));
    }

    @Test
    public void unrecognisedPropertiesFileKeyProducesExactlyOneWarningNamingTheKey() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), emptySet(), props("mockserver.maxExpectatons"));
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("mockserver.maxExpectatons"));
        assertThat(unknown.get(0), containsString("properties-file key"));
    }

    // --- (c) unrelated env var / system property -> no warning ---

    @Test
    public void unrelatedEnvironmentVariableProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            emptySet(), Set.of("JAVA_HOME", "PATH", "HOME", "MOCK_SERVER_NOT_NAMESPACED"), props());
        assertThat(unknown, is(empty()));
    }

    @Test
    public void unrelatedSystemPropertyProducesNoWarning() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("java.home", "user.dir", "mockserverish.typo"), emptySet(), props());
        assertThat(unknown, is(empty()));
    }

    // --- robustness ---

    @Test
    public void handlesNullPropertiesFile() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.typo"), emptySet(), null);
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("mockserver.typo"));
    }

    @Test
    public void reportsMultipleDistinctUnknownKeys() {
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.typoOne"),
            Set.of("MOCKSERVER_TYPO_TWO"),
            props("mockserver.typoThree"));
        assertThat(unknown, hasSize(3));
    }

    @Test
    public void doesNotDoubleReportSameKeyFromSystemPropertyAndPropertiesFile() {
        // A typo present in BOTH the system properties and the properties file is reported once.
        List<String> unknown = findUnknownConfigurationKeys(
            Set.of("mockserver.typo"), emptySet(), props("mockserver.typo"));
        assertThat(unknown, hasSize(1));
        assertThat(unknown.get(0), containsString("system property"));
        assertThat(unknown.get(0), not(containsString("properties-file key")));
    }

    // --- did you mean ---

    private static ConfigurationProperties.UnrecognisedProperty only(List<ConfigurationProperties.UnrecognisedProperty> unrecognised) {
        assertThat(unrecognised, hasSize(1));
        return unrecognised.get(0);
    }

    @Test
    public void suggestsTheClosestSystemPropertyForATypo() {
        ConfigurationProperties.UnrecognisedProperty typo = only(findUnrecognisedConfigurationKeys(Set.of("mockserver.maxExpectatons"), emptySet(), props()));
        assertThat(typo.getSuggestions(), contains("mockserver.maxExpectations"));
        assertThat(typo.getWarning(), containsString("system property [mockserver.maxExpectatons]"));
        assertThat(typo.getWarning(), containsString("(did you mean mockserver.maxExpectations?)"));
    }

    @Test
    public void suggestsTheClosestEnvironmentVariableInEnvironmentVariableForm() {
        ConfigurationProperties.UnrecognisedProperty typo = only(findUnrecognisedConfigurationKeys(emptySet(), Set.of("MOCKSERVER_METRICS_ENABLE"), props()));
        assertThat(typo.getSuggestions(), contains("MOCKSERVER_METRICS_ENABLED"));
        assertThat(typo.getWarning(), containsString("(did you mean MOCKSERVER_METRICS_ENABLED?)"));
    }

    @Test
    public void suggestsTheClosestKeyForAPropertiesFileTypo() {
        ConfigurationProperties.UnrecognisedProperty typo = only(findUnrecognisedConfigurationKeys(emptySet(), emptySet(), props("mockserver.logLevl")));
        assertThat(typo.getSetAs(), is("properties-file key"));
        assertThat(typo.getSuggestions(), contains("mockserver.logLevel"));
    }

    @Test
    public void suggestsTheKeyThatDiffersOnlyInCaseOrSeparators() {
        assertThat(suggestRecognisedKeys("mockserver.max_expectations", ConfigurationProperties.FORM_SYSTEM_PROPERTY), contains("mockserver.maxExpectations"));
        assertThat(suggestRecognisedKeys("mockserver.MAXEXPECTATIONS", ConfigurationProperties.FORM_SYSTEM_PROPERTY), contains("mockserver.maxExpectations"));
        assertThat(suggestRecognisedKeys("MOCKSERVER_MAXEXPECTATIONS", ConfigurationProperties.FORM_ENVIRONMENT_VARIABLE), contains("MOCKSERVER_MAX_EXPECTATIONS"));
        assertThat(suggestRecognisedKeys("MOCKSERVER_maxExpectations", ConfigurationProperties.FORM_ENVIRONMENT_VARIABLE), contains("MOCKSERVER_MAX_EXPECTATIONS"));
        // separators are not counted as edits, so a short name keeps its one-edit allowance for the real typo
        assertThat(ConfigurationProperties.normaliseKeyForSuggestion("MOCKSERVER_MAX_EXPECTATIONS"), is("maxexpectations"));
        assertThat(suggestRecognisedKeys("MOCKSERVER_DNS_PROT", ConfigurationProperties.FORM_ENVIRONMENT_VARIABLE), contains("MOCKSERVER_DNS_PORT"));
    }

    @Test
    public void suggestsNothingWhenNoKeyIsClose() {
        ConfigurationProperties.UnrecognisedProperty unrelated = only(findUnrecognisedConfigurationKeys(Set.of("mockserver.typoOne"), emptySet(), props()));
        assertThat(unrelated.getSuggestions(), is(empty()));
        assertThat(unrelated.getWarning(), containsString("(check for a typo)"));
        assertThat(unrelated.getWarning(), not(containsString("did you mean")));
        assertThat(suggestRecognisedKeys("mockserver.", ConfigurationProperties.FORM_SYSTEM_PROPERTY), is(empty()));
    }

    @Test
    public void suggestsTheServerPortKeyHandledByTheLauncher() {
        assertThat(suggestRecognisedKeys("MOCKSERVER_SERVERPORT", ConfigurationProperties.FORM_ENVIRONMENT_VARIABLE), contains("MOCKSERVER_SERVER_PORT"));
        assertThat(suggestRecognisedKeys("mockserver.serverPrt", ConfigurationProperties.FORM_SYSTEM_PROPERTY), contains("mockserver.serverPort"));
    }

    @Test
    public void systemPropertyOnlyTuningKnobsAreRecognisedOnlyAsSystemProperties() {
        assertThat(findUnknownConfigurationKeys(Set.of("mockserver.candidateIndexThreshold", "mockserver.jsonSchemaAllowRemoteRefs"), emptySet(), props()), is(empty()));
        assertThat(suggestRecognisedKeys("mockserver.candidateIndexThreshhold", ConfigurationProperties.FORM_SYSTEM_PROPERTY), contains("mockserver.candidateIndexThreshold"));
    }

    @Test
    public void aSystemPropertyOnlyKnobInThePropertiesFileWarnsThatItIsIgnored() {
        ConfigurationProperties.UnrecognisedProperty inFile = only(findUnrecognisedConfigurationKeys(emptySet(), emptySet(), props("mockserver.candidateIndexThreshold")));
        assertThat(inFile.isSystemPropertyOnly(), is(true));
        assertThat(inFile.getWarning(), containsString("properties-file key [mockserver.candidateIndexThreshold]"));
        assertThat(inFile.getWarning(), containsString("is read only as a system property, so this will be ignored (set -Dmockserver.candidateIndexThreshold instead)"));
        // set as a system property too, the system property is applied and nothing is reported
        assertThat(findUnknownConfigurationKeys(Set.of("mockserver.candidateIndexThreshold"), emptySet(), props("mockserver.candidateIndexThreshold")), is(empty()));
    }

    @Test
    public void aSystemPropertyOnlyKnobAsAnEnvironmentVariableWarnsThatItIsIgnored() {
        ConfigurationProperties.UnrecognisedProperty inEnvironment = only(findUnrecognisedConfigurationKeys(emptySet(), Set.of("MOCKSERVER_JSON_SCHEMA_ALLOW_REMOTE_REFS"), props()));
        assertThat(inEnvironment.isSystemPropertyOnly(), is(true));
        assertThat(inEnvironment.getWarning(), containsString("set -Dmockserver.jsonSchemaAllowRemoteRefs instead"));
    }

    @Test
    public void aMisspeltPropertiesFileKeyIsNeverPointedAtASystemPropertyOnlyKnob() {
        assertThat(suggestRecognisedKeys("mockserver.candidateIndexThreshhold", ConfigurationProperties.FORM_PROPERTIES_FILE), is(empty()));
        assertThat(suggestRecognisedKeys("MOCKSERVER_CANDIDATE_INDEX_THRESHHOLD", ConfigurationProperties.FORM_ENVIRONMENT_VARIABLE), is(empty()));
        ConfigurationProperties.UnrecognisedProperty typo = only(findUnrecognisedConfigurationKeys(emptySet(), emptySet(), props("mockserver.candidateIndexThreshhold")));
        assertThat(typo.getSuggestions(), is(empty()));
        assertThat(typo.isSystemPropertyOnly(), is(false));
    }

    @Test
    public void neverReportsTheValueOfAnUnrecognisedKey() {
        Properties file = new Properties();
        file.setProperty("mockserver.llmApiKeyy", "sk-live-do-not-disclose");
        ConfigurationProperties.UnrecognisedProperty typo = only(findUnrecognisedConfigurationKeys(emptySet(), emptySet(), file));
        assertThat(typo.getSuggestions(), contains("mockserver.llmApiKey"));
        assertThat(typo.getWarning(), not(containsString("sk-live-do-not-disclose")));
        assertThat(typo.getDescription(), not(containsString("sk-live-do-not-disclose")));
    }

    @Test
    public void aRemovedPropertyIsReportedAsRemovedAndIgnoredInEachForm() {
        List<ConfigurationProperties.UnrecognisedProperty> removed = findUnrecognisedConfigurationKeys(
            Set.of("mockserver.forwardBinaryRequestsWithoutWaitingForResponse"),
            Set.of("MOCKSERVER_FORWARD_BINARY_REQUESTS_USE_SINGLE_CONNECTION"),
            props("mockserver.forwardBinaryRequestsUseSingleConnection"));

        assertThat(removed, hasSize(3));
        for (ConfigurationProperties.UnrecognisedProperty property : removed) {
            assertThat(property.getName(), property.isRemoved(), is(true));
            assertThat(property.getSuggestions(), is(empty()));
            assertThat(property.getWarning(), containsString("[" + property.getName() + "] was removed in 9.0.0 and is ignored: a proxied binary connection is always relayed on one upstream connection"));
            assertThat(property.getWarning(), not(containsString("typo")));
            assertThat(property.getHint(), containsString("removed in 9.0.0"));
        }
    }

    @Test
    public void aPropertyRemovedInALaterReleaseNamesThatRelease() {
        // a key set both as a system property and in the file is reported once, so the file form is checked alone
        List<ConfigurationProperties.UnrecognisedProperty> removed = new ArrayList<>(findUnrecognisedConfigurationKeys(
            Set.of("mockserver.http1PipeliningInOrder"),
            Set.of("MOCKSERVER_HTTP1_PIPELINING_IN_ORDER"),
            props()));
        removed.addAll(findUnrecognisedConfigurationKeys(emptySet(), emptySet(), props("mockserver.http1PipeliningInOrder")));

        assertThat(removed, hasSize(3));
        for (ConfigurationProperties.UnrecognisedProperty property : removed) {
            assertThat(property.getName(), property.isRemoved(), is(true));
            assertThat(property.getSuggestions(), is(empty()));
            assertThat(property.getWarning(), containsString("[" + property.getName() + "] was removed in 9.3.0 and is ignored: HTTP/1.1 requests pipelined on one connection are always answered in the order they arrived"));
            assertThat(property.getHint(), containsString("removed in 9.3.0"));
        }
    }

    @Test
    public void aTypoIsNotTakenForARemovedProperty() {
        ConfigurationProperties.UnrecognisedProperty typo = only(findUnrecognisedConfigurationKeys(Set.of("mockserver.forwardBinaryRequestsWithoutWaitingForResponses"), emptySet(), props()));
        assertThat(typo.isRemoved(), is(false));
        assertThat(typo.getWarning(), containsString("it is not a known configuration property"));
    }

    @Test
    public void editDistanceCountsATranspositionAsOneEdit() {
        assertThat(editDistance("ab", "ba"), is(1));
        assertThat(editDistance("kitten", "sitting"), is(3));
        assertThat(editDistance("", "abc"), is(3));
        assertThat(editDistance("same", "same"), is(0));
    }
}

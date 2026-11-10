package org.mockserver.serialization;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.slf4j.event.Level;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.configuration.Configuration.configuration;

public class ConfigurationSerializerTest {

    private final ConfigurationSerializer serializer = new ConfigurationSerializer(new MockServerLogger());

    @Test
    public void shouldSerializeConfigurationWithSetFields() {
        Configuration configuration = configuration()
            .logLevel(Level.DEBUG)
            .maxExpectations(500)
            .metricsEnabled(true);

        String json = serializer.serialize(configuration);

        assertThat(json, containsString("\"logLevel\" : \"DEBUG\""));
        assertThat(json, containsString("\"maxExpectations\" : 500"));
        assertThat(json, containsString("\"metricsEnabled\" : true"));
    }

    @Test
    public void shouldDeserializeConfiguration() {
        String json = "{\"logLevel\":\"WARN\",\"maxExpectations\":200,\"metricsEnabled\":false}";

        Configuration configuration = serializer.deserialize(json);

        assertThat(configuration.logLevel(), is(Level.WARN));
        assertThat(configuration.maxExpectations(), is(200));
        assertThat(configuration.metricsEnabled(), is(false));
    }

    @Test
    public void shouldIgnoreTheRemovedBinaryForwardingSettingsInConfigurationJson() {
        String json = "{\"forwardBinaryRequestsWithoutWaitingForResponse\":true,\"forwardBinaryRequestsUseSingleConnection\":false,\"maxExpectations\":200}";

        Configuration configuration = serializer.deserialize(json);

        assertThat(configuration.maxExpectations(), is(200));
        assertThat(serializer.serialize(configuration), not(containsString("forwardBinaryRequestsWithoutWaitingForResponse")));
        assertThat(serializer.serialize(configuration), not(containsString("forwardBinaryRequestsUseSingleConnection")));
    }

    @Test
    public void shouldIgnoreTheRemovedHttp1PipeliningInOrderSettingInConfigurationJson() {
        String json = "{\"http1PipeliningInOrder\":false,\"maxExpectations\":200}";

        Configuration configuration = serializer.deserialize(json);

        assertThat(configuration.maxExpectations(), is(200));
        assertThat(serializer.serialize(configuration), not(containsString("http1PipeliningInOrder")));
    }

    @Test
    public void shouldRoundTripConfiguration() {
        Configuration original = configuration()
            .logLevel(Level.ERROR)
            .maxExpectations(1000)
            .maxLogEntries(5000)
            .disableLogging(true)
            .metricsEnabled(true)
            .enableCORSForAPI(false)
            .corsAllowOrigin("https://example.com")
            .controlPlaneCorsAllowOrigin("https://dashboard.example.com");

        String json = serializer.serialize(original);
        Configuration deserialized = serializer.deserialize(json);

        assertThat(deserialized.logLevel(), is(Level.ERROR));
        assertThat(deserialized.maxExpectations(), is(1000));
        assertThat(deserialized.maxLogEntries(), is(5000));
        assertThat(deserialized.disableLogging(), is(true));
        assertThat(deserialized.metricsEnabled(), is(true));
        assertThat(deserialized.enableCORSForAPI(), is(false));
        assertThat(deserialized.corsAllowOrigin(), is("https://example.com"));
        assertThat(deserialized.controlPlaneCorsAllowOrigin(), is("https://dashboard.example.com"));
    }

    @Test
    public void shouldIgnoreExtraFields() {
        String json = "{\"logLevel\":\"INFO\",\"unknownField\":\"value\"}";

        Configuration configuration = serializer.deserialize(json);

        assertThat(configuration.logLevel(), is(Level.INFO));
    }

    @Test
    public void shouldHandleNullInput() {
        Configuration configuration = serializer.deserialize(null);

        assertThat(configuration, is(nullValue()));
    }

    @Test
    public void shouldHandleEmptyInput() {
        Configuration configuration = serializer.deserialize("");

        assertThat(configuration, is(nullValue()));
    }

    @Test
    public void shouldSerializeAllFieldsWithDefaults() {
        Configuration configuration = configuration()
            .logLevel(Level.INFO);

        String json = serializer.serialize(configuration);

        assertThat(json, containsString("\"logLevel\" : \"INFO\""));
        assertThat(json, containsString("\"maxExpectations\""));
    }

    @Test
    public void shouldRedactSecretFieldsFromSerialization() {
        Configuration configuration = configuration()
            .logLevel(Level.INFO)
            .forwardProxyAuthenticationPassword("secret-fwd-password")
            .proxyAuthenticationPassword("secret-proxy-password");

        String json = serializer.serialize(configuration);

        assertThat(json, not(containsString("secret-fwd-password")));
        assertThat(json, not(containsString("secret-proxy-password")));
        assertThat(json, not(containsString("forwardProxyAuthenticationPassword")));
        assertThat(json, not(containsString("proxyAuthenticationPassword")));
        assertThat(json, not(containsString("certificateAuthorityPrivateKey")));
        assertThat(json, not(containsString("forwardProxyPrivateKey")));
    }
}

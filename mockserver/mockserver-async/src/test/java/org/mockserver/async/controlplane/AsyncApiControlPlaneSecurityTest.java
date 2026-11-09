package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.After;
import org.junit.Test;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.configuration.Configuration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Tests for the security scheme parsing in {@link AsyncApiControlPlaneImpl}.
 * These test the {@code parseBrokerConfig} method's handling of
 * {@code kafkaSecurity} and {@code mqttSecurity} JSON objects.
 */
public class AsyncApiControlPlaneSecurityTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // the location setters require the file to exist
    private static final String TRUSTSTORE = existingFile("truststore");
    private static final String KEYSTORE = existingFile("keystore");

    // a SASL request may only name the startup bootstrap servers; the Kafka SSL settings are startup-only
    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl(Configuration.configuration()
        .asyncKafkaBootstrapServers("broker:9093")
        .asyncKafkaSslTruststoreLocation(TRUSTSTORE)
        .asyncKafkaSslTruststorePassword("tpass")
        .asyncKafkaSslKeystoreLocation(KEYSTORE)
        .asyncKafkaSslKeystorePassword("kpass")
        .asyncKafkaSslKeyPassword("keypass"));

    @After
    public void tearDown() {
        controlPlane.reset();
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

    // ---- Kafka security parsing ----

    @Test
    public void shouldParseKafkaSecurityFromBrokerConfig() throws Exception {
        String json = "{\n" +
            "  \"kafkaBootstrapServers\": \"broker:9093\",\n" +
            "  \"kafkaSecurity\": {\n" +
            "    \"securityProtocol\": \"SASL_SSL\",\n" +
            "    \"saslMechanism\": \"PLAIN\"\n" +
            "  }\n" +
            "}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.kafkaBootstrapServers, is("broker:9093"));
        assertThat(config.kafkaSecurity, is(notNullValue()));
        assertThat(config.kafkaSecurity.isEmpty(), is(false));
        assertThat(config.kafkaSecurity.getSecurityProtocol(), is("SASL_SSL"));
        assertThat(config.kafkaSecurity.getSaslMechanism(), is("PLAIN"));
        // no startup JAAS configuration on this control plane, and a request can never supply one
        assertThat(config.kafkaSecurity.getSaslJaasConfig(), is(nullValue()));
        // the SSL settings come from the startup properties
        assertThat(config.kafkaSecurity.getSslTruststoreLocation(), is(TRUSTSTORE));
        assertThat(config.kafkaSecurity.getSslTruststorePassword(), is("tpass"));
        assertThat(config.kafkaSecurity.getSslKeystoreLocation(), is(KEYSTORE));
        assertThat(config.kafkaSecurity.getSslKeystorePassword(), is("kpass"));
        assertThat(config.kafkaSecurity.getSslKeyPassword(), is("keypass"));
    }

    @Test
    public void shouldReturnNullKafkaSecurityWhenAbsent() throws Exception {
        String json = "{\"kafkaBootstrapServers\": \"broker:9092\"}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.kafkaSecurity, is(nullValue()));
    }

    @Test
    public void shouldReturnNullKafkaSecurityWhenEmptyObject() throws Exception {
        String json = "{\"kafkaBootstrapServers\": \"broker:9092\", \"kafkaSecurity\": {}}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        // An empty kafkaSecurity object should result in null (isEmpty -> return null)
        assertThat(config.kafkaSecurity, is(nullValue()));
    }

    @Test
    public void shouldParsePartialKafkaSecurity() throws Exception {
        String json = "{\n" +
            "  \"kafkaBootstrapServers\": \"broker:9093\",\n" +
            "  \"kafkaSecurity\": {\n" +
            "    \"securityProtocol\": \"SSL\"\n" +
            "  }\n" +
            "}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.kafkaSecurity, is(notNullValue()));
        assertThat(config.kafkaSecurity.getSecurityProtocol(), is("SSL"));
        assertThat(config.kafkaSecurity.getSslTruststoreLocation(), is(TRUSTSTORE));
        assertThat(config.kafkaSecurity.getSaslMechanism(), is(nullValue()));
        assertThat(config.kafkaSecurity.getSaslJaasConfig(), is(nullValue()));
    }

    // ---- MQTT security parsing ----

    @Test
    public void shouldParseMqttSecurityFromBrokerConfig() throws Exception {
        String json = "{\n" +
            "  \"mqttBrokerUrl\": \"ssl://broker:8883\",\n" +
            "  \"mqttSecurity\": {\n" +
            "    \"username\": \"mqttuser\",\n" +
            "    \"password\": \"mqttpass\"\n" +
            "  }\n" +
            "}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.mqttBrokerUrl, is("ssl://broker:8883"));
        assertThat(config.mqttSecurity, is(notNullValue()));
        assertThat(config.mqttSecurity.isEmpty(), is(false));
        assertThat(config.mqttSecurity.getUsername(), is("mqttuser"));
        assertThat(config.mqttSecurity.getPassword(), is("mqttpass"));
        // the SSL properties come only from the startup asyncMqttSsl* properties, none of which is set here
        assertThat(config.mqttSecurity.getSslProperties(), is(anEmptyMap()));
    }

    @Test
    public void shouldReturnNullMqttSecurityWhenAbsent() throws Exception {
        String json = "{\"mqttBrokerUrl\": \"tcp://broker:1883\"}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.mqttSecurity, is(nullValue()));
    }

    @Test
    public void shouldReturnNullMqttSecurityWhenEmptyObject() throws Exception {
        String json = "{\"mqttBrokerUrl\": \"tcp://broker:1883\", \"mqttSecurity\": {}}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.mqttSecurity, is(nullValue()));
    }

    @Test
    public void shouldParseMqttSecurityWithUsernameOnly() throws Exception {
        String json = "{\n" +
            "  \"mqttBrokerUrl\": \"tcp://broker:1883\",\n" +
            "  \"mqttSecurity\": { \"username\": \"u\" }\n" +
            "}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.mqttSecurity, is(notNullValue()));
        assertThat(config.mqttSecurity.getUsername(), is("u"));
        assertThat(config.mqttSecurity.getPassword(), is(nullValue()));
        assertThat(config.mqttSecurity.getSslProperties(), is(anEmptyMap()));
    }

    // ---- Combined / null node ----

    @Test
    public void shouldHandleNullBrokerConfigNode() {
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(null);

        assertThat(config.kafkaSecurity, is(nullValue()));
        assertThat(config.mqttSecurity, is(nullValue()));
    }

    @Test
    public void shouldParseBothKafkaAndMqttSecurity() throws Exception {
        String json = "{\n" +
            "  \"kafkaBootstrapServers\": \"broker:9093\",\n" +
            "  \"kafkaSecurity\": { \"securityProtocol\": \"SASL_SSL\", \"saslMechanism\": \"PLAIN\" },\n" +
            "  \"mqttBrokerUrl\": \"ssl://broker:8883\",\n" +
            "  \"mqttSecurity\": { \"username\": \"u\", \"password\": \"p\" }\n" +
            "}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        assertThat(config.kafkaSecurity, is(notNullValue()));
        assertThat(config.kafkaSecurity.getSecurityProtocol(), is("SASL_SSL"));
        assertThat(config.kafkaSecurity.getSaslMechanism(), is("PLAIN"));

        assertThat(config.mqttSecurity, is(notNullValue()));
        assertThat(config.mqttSecurity.getUsername(), is("u"));
        assertThat(config.mqttSecurity.getPassword(), is("p"));
    }

    @Test
    public void shouldIgnoreNonObjectKafkaSecurityNode() throws Exception {
        String json = "{\"kafkaBootstrapServers\": \"b:9092\", \"kafkaSecurity\": \"invalid\"}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        // String value should be ignored (not an object)
        assertThat(config.kafkaSecurity, is(nullValue()));
    }

    @Test
    public void shouldIgnoreNonObjectMqttSecurityNode() throws Exception {
        String json = "{\"mqttBrokerUrl\": \"tcp://b:1883\", \"mqttSecurity\": 42}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        // Non-object value should be ignored
        assertThat(config.mqttSecurity, is(nullValue()));
    }

    // ---- Backward compatibility: existing fields still parse correctly ----

    @Test
    public void existingBrokerConfigFieldsShouldStillParseWhenSecurityPresent() throws Exception {
        String json = "{\n" +
            "  \"kafkaBootstrapServers\": \"broker:9093\",\n" +
            "  \"kafkaGroupId\": \"my-group\",\n" +
            "  \"mqttBrokerUrl\": \"ssl://broker:8883\",\n" +
            "  \"mqttClientId\": \"my-client\",\n" +
            "  \"publishOnLoad\": false,\n" +
            "  \"consume\": true,\n" +
            "  \"publishIntervalMillis\": 5000,\n" +
            "  \"mqttQos\": 2,\n" +
            "  \"kafkaSecurity\": { \"securityProtocol\": \"SASL_SSL\" },\n" +
            "  \"mqttSecurity\": { \"username\": \"u\" }\n" +
            "}";

        JsonNode node = MAPPER.readTree(json);
        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(node);

        // Existing fields
        assertThat(config.kafkaBootstrapServers, is("broker:9093"));
        assertThat(config.kafkaGroupId, is("my-group"));
        assertThat(config.mqttBrokerUrl, is("ssl://broker:8883"));
        assertThat(config.mqttClientId, is("my-client"));
        assertThat(config.publishOnLoad, is(false));
        assertThat(config.consume, is(true));
        assertThat(config.publishIntervalMillis, is(5000L));
        assertThat(config.mqttQos, is(2));

        // Security fields
        assertThat(config.kafkaSecurity, is(notNullValue()));
        assertThat(config.mqttSecurity, is(notNullValue()));
    }
}

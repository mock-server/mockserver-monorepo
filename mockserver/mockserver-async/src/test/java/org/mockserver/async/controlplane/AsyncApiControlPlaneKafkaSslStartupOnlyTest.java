package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Test;
import org.mockserver.async.security.KafkaSecurityProperties;
import org.mockserver.configuration.Configuration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Map;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A request that could name a Kafka keystore or truststore could make the Kafka client read any keystore-format file
 * the MockServer process can read, so {@code PUT /mockserver/asyncapi} refuses the {@code kafkaSecurity} SSL fields,
 * and the startup {@code asyncKafkaSsl*} properties reach the Kafka client of a request selecting {@code SSL} or
 * {@code SASL_SSL}, which while any is set may only use the startup {@code asyncKafkaBootstrapServers}.
 */
public class AsyncApiControlPlaneKafkaSslStartupOnlyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // the location setters require the file to exist
    private static final String TRUSTSTORE = existingFile("truststore");
    private static final String KEYSTORE = existingFile("keystore");

    private static final String SPEC = "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
        + "\"channels\":{\"orders\":{\"publish\":{\"message\":{\"payload\":{\"type\":\"string\"}}}}}}";

    private final Configuration configuration = Configuration.configuration().asyncKafkaBootstrapServers("broker:9093");
    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl(configuration);

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    @Test
    public void shouldRefuseEverySslFieldNamingItsStartupProperty() {
        Map<String, String[]> fields = Map.of(
            "sslTruststoreLocation", new String[]{"mockserver.asyncKafkaSslTruststoreLocation", "MOCKSERVER_ASYNC_KAFKA_SSL_TRUSTSTORE_LOCATION"},
            "sslTruststorePassword", new String[]{"mockserver.asyncKafkaSslTruststorePassword", "MOCKSERVER_ASYNC_KAFKA_SSL_TRUSTSTORE_PASSWORD"},
            "sslKeystoreLocation", new String[]{"mockserver.asyncKafkaSslKeystoreLocation", "MOCKSERVER_ASYNC_KAFKA_SSL_KEYSTORE_LOCATION"},
            "sslKeystorePassword", new String[]{"mockserver.asyncKafkaSslKeystorePassword", "MOCKSERVER_ASYNC_KAFKA_SSL_KEYSTORE_PASSWORD"},
            "sslKeyPassword", new String[]{"mockserver.asyncKafkaSslKeyPassword", "MOCKSERVER_ASYNC_KAFKA_SSL_KEY_PASSWORD"});
        for (Map.Entry<String, String[]> field : fields.entrySet()) {
            IllegalArgumentException refused = refusal(sslRequest(field.getKey(), "/proc/self/environ"));

            assertThat(field.getKey(), refused.getMessage(), containsString("'kafkaSecurity." + field.getKey() + "' cannot be set over the control plane"));
            assertThat(field.getKey(), refused.getMessage(), containsString(field.getValue()[0]));
            assertThat(field.getKey(), refused.getMessage(), containsString(field.getValue()[1]));
            assertThat("the refusal must not echo the value", refused.getMessage(), not(containsString("/proc/self/environ")));
        }
    }

    @Test
    public void shouldRefuseAnSslFieldOnLoadSoNoBrokerConnectionIsMade() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("spec", SPEC);
        body.set("brokerConfig", sslRequest("sslKeystoreLocation", "/root/keystore.p12"));

        IllegalArgumentException refused = null;
        try {
            controlPlane.load(MAPPER.writeValueAsString(body));
        } catch (IllegalArgumentException e) {
            refused = e;
        }

        assertThat("load must refuse the request with a client error (400)", refused, is(notNullValue()));
        assertThat(refused.getMessage(), containsString("mockserver.asyncKafkaSslKeystoreLocation"));
        assertThat(controlPlane.status().get("publishers").asInt(), is(0));
    }

    @Test
    public void shouldTreatAJsonNullSslFieldAsAbsent() {
        ObjectNode brokerConfig = sslRequest("sslKeyPassword", null);
        ((ObjectNode) brokerConfig.get("kafkaSecurity")).putNull("sslKeyPassword");

        assertThat(controlPlane.parseBrokerConfig(brokerConfig).kafkaSecurity.getSslKeyPassword(), is(nullValue()));
    }

    @Test
    public void shouldRefuseCaseVariantDottedAndPrefixedSslKeysNamingTheStartupProperty() {
        Map<String, String> variants = Map.of(
            "SslKeystoreLocation", "mockserver.asyncKafkaSslKeystoreLocation",
            "ssl.keystore.location", "mockserver.asyncKafkaSslKeystoreLocation",
            "SSL_TRUSTSTORE_LOCATION", "mockserver.asyncKafkaSslTruststoreLocation",
            "ssl.key.password", "mockserver.asyncKafkaSslKeyPassword",
            "kafkaSslKeystorePassword", "mockserver.asyncKafkaSslKeystorePassword");
        for (Map.Entry<String, String> variant : variants.entrySet()) {
            IllegalArgumentException refused = refusal(sslRequest(variant.getKey(), "/proc/self/environ"));

            assertThat(variant.getKey(), refused.getMessage(), containsString(variant.getValue()));
            assertThat(variant.getKey(), refused.getMessage(), not(containsString("/proc/self/environ")));
        }
    }

    @Test
    public void shouldRefuseANestedObjectInPlaceOfAnSslField() {
        ObjectNode brokerConfig = sslRequest("sslKeystoreLocation", null);
        ((ObjectNode) brokerConfig.get("kafkaSecurity")).putObject("sslKeystoreLocation").put("path", "/proc/self/environ");

        IllegalArgumentException refused = refusal(brokerConfig);

        assertThat(refused.getMessage(), containsString("mockserver.asyncKafkaSslKeystoreLocation"));
        assertThat(refused.getMessage(), not(containsString("/proc/self/environ")));
    }

    @Test
    public void shouldRefuseANestedSslObjectInKafkaSecurity() {
        ObjectNode brokerConfig = sslRequest("sslKeystoreLocation", null);
        ((ObjectNode) brokerConfig.get("kafkaSecurity")).putObject("ssl").putObject("keystore").put("location", "/proc/self/environ");

        IllegalArgumentException refused = refusal(brokerConfig);

        assertThat(refused.getMessage(), containsString("unknown field 'ssl' in kafkaSecurity"));
        assertThat(refused.getMessage(), not(containsString("/proc/self/environ")));
    }

    @Test
    public void shouldRefuseAnSslKeyMisplacedAtTheTopLevelOfBrokerConfig() {
        for (String name : Arrays.asList("sslKeystoreLocation", "ssl.truststore.location", "kafkaSslKeyPassword")) {
            ObjectNode brokerConfig = MAPPER.createObjectNode();
            brokerConfig.put("kafkaBootstrapServers", "broker:9093");
            brokerConfig.put(name, "/proc/self/environ");
            brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SSL");

            IllegalArgumentException refused = refusal(brokerConfig);

            assertThat(name, refused.getMessage(), containsString("cannot be set over the control plane"));
            assertThat(name, refused.getMessage(), containsString("mockserver.asyncKafkaSsl"));
            assertThat(name, refused.getMessage(), not(containsString("/proc/self/environ")));
        }
    }

    @Test
    public void shouldTreatAJsonNullSslKeyAtTheTopLevelAsAbsent() {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.putNull("sslKeystoreLocation");

        assertThat(controlPlane.parseBrokerConfig(brokerConfig).kafkaSecurity, is(nullValue()));
    }

    @Test
    public void shouldBindAnSslRequestToTheStartupBrokersWhileAStartupSslSettingIsSet() {
        startupSslSettings();
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", "attacker.example:9093");
        brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "ssl");

        IllegalArgumentException refused = refusal(brokerConfig);

        assertThat(refused.getMessage(), containsString("'kafkaBootstrapServers' must be omitted or match the startup mockserver.asyncKafkaBootstrapServers"));
        assertThat(refused.getMessage(), containsString("the SSL securityProtocol while a startup mockserver.asyncKafkaSsl* keystore or truststore setting is set"));
    }

    @Test
    public void shouldBindAnSslRequestEvenWhenOnlyTheTruststoreIsSetAtStartup() {
        configuration.asyncKafkaSslTruststoreLocation(TRUSTSTORE);
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", "attacker.example:9093");
        brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SSL");

        assertThat(refusal(brokerConfig).getMessage(), containsString("'kafkaBootstrapServers' must be omitted or match"));
    }

    @Test
    public void shouldAcceptAnSslRequestNamingTheStartupBrokers() {
        startupSslSettings();
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", " BROKER:9093 ");
        brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SSL");

        assertThat(controlPlane.parseBrokerConfig(brokerConfig).kafkaSecurity.getSslKeystoreLocation(), is(KEYSTORE));
    }

    @Test
    public void shouldRefuseAnSslRequestWhenAStartupSslSettingIsSetButNoStartupBrokers() {
        AsyncApiControlPlaneImpl withoutStartupBrokers = new AsyncApiControlPlaneImpl(Configuration.configuration()
            .asyncKafkaBootstrapServers("")
            .asyncKafkaSslKeystoreLocation(KEYSTORE));
        try {
            ObjectNode brokerConfig = MAPPER.createObjectNode();
            brokerConfig.put("kafkaBootstrapServers", "broker:9093");
            brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SSL");

            IllegalArgumentException refused = null;
            try {
                withoutStartupBrokers.parseBrokerConfig(brokerConfig);
            } catch (IllegalArgumentException e) {
                refused = e;
            }

            assertThat(refused, is(notNullValue()));
            assertThat(refused.getMessage(), containsString("needs mockserver.asyncKafkaBootstrapServers"));
        } finally {
            withoutStartupBrokers.reset();
        }
    }

    @Test
    public void shouldLetAnSslRequestNameAnyBrokerWhileNoStartupSslSettingIsSet() {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", "elsewhere.example:9093");
        brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SSL");

        AsyncApiControlPlaneImpl.BrokerConfig parsed = controlPlane.parseBrokerConfig(brokerConfig);

        assertThat(parsed.kafkaBootstrapServers, is("elsewhere.example:9093"));
        assertThat(parsed.kafkaSecurity.getSecurityProtocol(), is("SSL"));
    }

    @Test
    public void shouldGiveTheKafkaClientTheStartupSslSettingsForAnSslProtocol() {
        startupSslSettings();
        for (String protocol : Arrays.asList("SSL", "sasl_ssl")) {
            ObjectNode brokerConfig = MAPPER.createObjectNode();
            brokerConfig.putObject("kafkaSecurity").put("securityProtocol", protocol).put("saslMechanism", "PLAIN");

            Properties kafkaClientProperties = new Properties();
            KafkaSecurityProperties.applySecurity(kafkaClientProperties, controlPlane.parseBrokerConfig(brokerConfig).kafkaSecurity);

            assertThat(protocol, kafkaClientProperties.getProperty("ssl.truststore.location"), is(TRUSTSTORE));
            assertThat(protocol, kafkaClientProperties.getProperty("ssl.truststore.password"), is("truststore-secret"));
            assertThat(protocol, kafkaClientProperties.getProperty("ssl.keystore.location"), is(KEYSTORE));
            assertThat(protocol, kafkaClientProperties.getProperty("ssl.keystore.password"), is("keystore-secret"));
            assertThat(protocol, kafkaClientProperties.getProperty("ssl.key.password"), is("key-secret"));
        }
    }

    @Test
    public void shouldNotGiveTheStartupSslSettingsToAPlaintextProtocol() {
        startupSslSettings();
        for (String protocol : Arrays.asList("PLAINTEXT", "SASL_PLAINTEXT")) {
            ObjectNode brokerConfig = MAPPER.createObjectNode();
            brokerConfig.putObject("kafkaSecurity").put("securityProtocol", protocol).put("saslMechanism", "PLAIN");

            Properties kafkaClientProperties = new Properties();
            KafkaSecurityProperties.applySecurity(kafkaClientProperties, controlPlane.parseBrokerConfig(brokerConfig).kafkaSecurity);

            assertThat(protocol, kafkaClientProperties.stringPropertyNames(), not(hasItem(startsWith("ssl."))));
        }
    }

    @Test
    public void shouldLeaveTheSslSettingsUnsetWhenNoneAreSetAtStartup() {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SSL");

        Properties kafkaClientProperties = new Properties();
        KafkaSecurityProperties.applySecurity(kafkaClientProperties, controlPlane.parseBrokerConfig(brokerConfig).kafkaSecurity);

        assertThat(kafkaClientProperties.getProperty("security.protocol"), is("SSL"));
        assertThat(kafkaClientProperties.stringPropertyNames(), not(hasItem(startsWith("ssl."))));
    }

    private void startupSslSettings() {
        configuration
            .asyncKafkaSslTruststoreLocation(TRUSTSTORE)
            .asyncKafkaSslTruststorePassword("truststore-secret")
            .asyncKafkaSslKeystoreLocation(KEYSTORE)
            .asyncKafkaSslKeystorePassword("keystore-secret")
            .asyncKafkaSslKeyPassword("key-secret");
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

    private IllegalArgumentException refusal(ObjectNode brokerConfig) {
        try {
            controlPlane.parseBrokerConfig(brokerConfig);
        } catch (IllegalArgumentException refused) {
            return refused;
        }
        throw new AssertionError("expected the control plane to refuse " + brokerConfig);
    }

    private static ObjectNode sslRequest(String field, String value) {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", "broker:9093");
        ObjectNode kafkaSecurity = brokerConfig.putObject("kafkaSecurity");
        kafkaSecurity.put("securityProtocol", "SSL");
        if (value != null) {
            kafkaSecurity.put(field, value);
        }
        return brokerConfig;
    }
}

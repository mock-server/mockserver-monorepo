package org.mockserver.async.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.errors.AuthenticationException;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.async.controlplane.AsyncApiControlPlaneImpl;
import org.mockserver.async.publish.KafkaMessagePublisher;
import org.mockserver.async.security.KafkaSecurity;
import org.mockserver.configuration.Configuration;
import org.mockserver.test.DockerAvailability;
import org.mockserver.test.TestContainerImages;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Integration tests that prove MockServer's Kafka {@link KafkaMessagePublisher} security
 * wiring actually reaches, and is enforced by, a real broker — not merely that a
 * {@link Properties} map was assembled ({@link org.mockserver.async.publish.KafkaMessagePublisherSecurityTest}
 * covers the map in isolation).
 *
 * <p>The broker is a Testcontainers Kafka configured with a {@code SASL_PLAINTEXT} external
 * listener using the {@code PLAIN} mechanism and a broker-side JAAS config that knows a single
 * {@code alice} credential. Two claims are proven end-to-end over a real socket:
 * <ul>
 *   <li><b>Positive</b> — a publisher driven with MockServer's {@link KafkaSecurity} carrying the
 *       correct SASL credentials publishes successfully (the credentials reach the broker and are
 *       accepted), and the message is then read back by a matching credentialed consumer.</li>
 *   <li><b>Negative</b> — a publisher driven with the <em>wrong</em> SASL password fails with an
 *       authentication exception. This is what makes the positive case meaningful: it proves the
 *       broker is enforcing SASL, not merely that any plaintext connection works.</li>
 * </ul>
 *
 * <p>Docker-gated: SKIPS (not fails) when Docker is unavailable.
 */
public class KafkaSecurityLiveBrokerIntegrationTest {

    private static final String PLAIN_LOGIN_MODULE = "org.apache.kafka.common.security.plain.PlainLoginModule";

    /**
     * Broker-side JAAS for the SASL_PLAINTEXT listener: declares the single {@code alice} user the
     * broker will accept. Any other username/password is rejected at the SASL handshake.
     */
    private static final String BROKER_JAAS = PLAIN_LOGIN_MODULE + " required "
        + "username=\"admin\" password=\"admin-secret\" "
        + "user_alice=\"alice-secret\";";

    private static KafkaContainer kafka;

    @BeforeClass
    public static void checkDockerAndStartSaslKafka() {
        // Shared fail-safe wrapper (lambda, not a method reference) so a broken classpath surfacing
        // as an Error still SKIPS rather than ERRORs. See DockerAvailability and AGENTS.md.
        Assume.assumeTrue("Docker is not available — skipping Kafka SASL integration tests",
            DockerAvailability.isAvailable(
                () -> org.testcontainers.DockerClientFactory.instance().isDockerAvailable()));

        // Turn the external client listener (PLAINTEXT, the one getBootstrapServers() points at)
        // into a SASL_PLAINTEXT/PLAIN listener. The inter-broker BROKER listener stays PLAINTEXT so
        // no broker-to-broker or broker-to-ZooKeeper SASL is needed.
        kafka = new KafkaContainer(DockerImageName.parse(TestContainerImages.CP_KAFKA).asCompatibleSubstituteFor(TestContainerImages.publicRepository("cp-kafka")))
            .withEnv("KAFKA_LISTENER_SECURITY_PROTOCOL_MAP", "BROKER:PLAINTEXT,PLAINTEXT:SASL_PLAINTEXT")
            .withEnv("KAFKA_SASL_ENABLED_MECHANISMS", "PLAIN")
            .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_SASL_ENABLED_MECHANISMS", "PLAIN")
            .withEnv("KAFKA_LISTENER_NAME_PLAINTEXT_PLAIN_SASL_JAAS_CONFIG", BROKER_JAAS);
        kafka.start();
    }

    @AfterClass
    public static void stopKafka() {
        if (kafka != null && kafka.isRunning()) {
            kafka.stop();
        }
    }

    /**
     * The credentials configured on MockServer's publisher must reach the broker and be accepted:
     * publish succeeds and a matching credentialed consumer reads the message back.
     */
    @Test(timeout = 120_000)
    public void credentialedPublisherReachesSaslBrokerAndMessageIsConsumable() {
        String topic = "test-sasl-positive";

        KafkaMessagePublisher publisher =
            new KafkaMessagePublisher(kafka.getBootstrapServers(), saslSecurity("alice", "alice-secret"));
        publisher.publish(topic, "{\"orderId\":42}");
        // flush() drains in-flight sends and rethrows the first delivery failure — with correct
        // credentials it must NOT throw.
        publisher.flush();
        publisher.close();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
            consumerProps("test-sasl-positive-group", saslSecurity("alice", "alice-secret")))) {
            consumer.subscribe(Collections.singletonList(topic));

            String receivedPayload = null;
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (record.topic().equals(topic)) {
                        receivedPayload = record.value();
                        break;
                    }
                }
                if (receivedPayload != null) break;
            }
            assertThat("message published with valid SASL credentials should have been received",
                receivedPayload, is("{\"orderId\":42}"));
        }
    }

    /**
     * The startup-only {@code asyncKafkaSaslJaasConfig} reaches the broker through the control plane: a
     * {@code PUT /mockserver/asyncapi} that selects SASL_PLAINTEXT/PLAIN, without (and unable to send) a JAAS
     * configuration, authenticates with the startup one and its on-load message is consumable.
     */
    @Test(timeout = 120_000)
    public void controlPlaneAuthenticatesWithTheStartupJaasConfig() throws Exception {
        String topic = "test-sasl-control-plane";
        AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl(startupSaslConfiguration());
        try {
            JsonNode loaded = controlPlane.load(saslLoadRequest(topic, kafka.getBootstrapServers()));

            assertThat(loaded.get("publishers").asInt(), is(1));
            assertThat("the on-load publish must not have failed authentication: " + loaded,
                loaded.has("validationIssues"), is(false));
            assertThat(consumeOneWithSasl(topic), is("{\"orderId\":7}"));
        } finally {
            controlPlane.reset();
        }
    }

    /**
     * The startup JAAS is bound to the startup {@code asyncKafkaBootstrapServers}: a request naming another
     * bootstrap is refused before any Kafka client is built, so nothing ever connects to it.
     */
    @Test(timeout = 120_000)
    public void controlPlaneRefusesToSendTheStartupJaasConfigToAnotherBootstrap() throws Exception {
        AsyncApiControlPlaneImpl controlPlane = new AsyncApiControlPlaneImpl(startupSaslConfiguration());
        try (ServerSocket decoy = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            decoy.setSoTimeout(2_000);
            IllegalArgumentException refused = null;
            try {
                controlPlane.load(saslLoadRequest("test-sasl-refused", "127.0.0.1:" + decoy.getLocalPort()));
            } catch (IllegalArgumentException e) {
                refused = e;
            }

            assertThat("load must refuse the request with a client error (400)", refused, is(notNullValue()));
            assertThat(refused.getMessage(), containsString("'kafkaBootstrapServers' must be omitted or match"));
            assertThat(controlPlane.status().get("publishers").asInt(), is(0));
            try (Socket connected = decoy.accept()) {
                throw new AssertionError("the refused request connected to " + connected.getRemoteSocketAddress());
            } catch (SocketTimeoutException expected) {
                // nothing connected to the caller-chosen bootstrap
            }
        } finally {
            controlPlane.reset();
        }
    }

    private static Configuration startupSaslConfiguration() {
        return Configuration.configuration()
            .asyncKafkaBootstrapServers(kafka.getBootstrapServers())
            .asyncKafkaSaslJaasConfig(saslSecurity("alice", "alice-secret").getSaslJaasConfig());
    }

    private static String saslLoadRequest(String topic, String kafkaBootstrapServers) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode body = mapper.createObjectNode();
        body.put("spec", "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},\"channels\":{\""
            + topic + "\":{\"publish\":{\"message\":{\"payload\":{\"type\":\"object\",\"example\":{\"orderId\":7}}}}}}}");
        ObjectNode brokerConfig = body.putObject("brokerConfig");
        brokerConfig.put("kafkaBootstrapServers", kafkaBootstrapServers);
        brokerConfig.putObject("kafkaSecurity").put("securityProtocol", "SASL_PLAINTEXT").put("saslMechanism", "PLAIN");
        return mapper.writeValueAsString(body);
    }

    private String consumeOneWithSasl(String topic) {
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(
            consumerProps(topic + "-group", saslSecurity("alice", "alice-secret")))) {
            consumer.subscribe(Collections.singletonList(topic));
            long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(30);
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    return record.value();
                }
            }
        }
        return null;
    }

    /**
     * Enforcement proof: a publisher whose SASL password is wrong is rejected by the broker. The
     * authentication failure surfaces either synchronously from {@code publish(..)} (metadata wait)
     * or from {@code flush(..)} (async send drain); either way an {@link AuthenticationException}
     * must appear in the failure chain. Without this the positive test could pass merely because
     * plaintext works.
     */
    @Test(timeout = 120_000)
    public void publisherWithWrongCredentialsIsRejectedBySaslBroker() {
        String topic = "test-sasl-negative";

        KafkaMessagePublisher publisher =
            new KafkaMessagePublisher(kafka.getBootstrapServers(), saslSecurity("alice", "WRONG-secret"));

        Throwable caught = null;
        try {
            publisher.publish(topic, "{\"orderId\":99}");
            publisher.flush();
        } catch (Throwable t) {
            caught = t;
        } finally {
            publisher.close();
        }

        assertThat("a wrong-credential publish must be rejected, not silently accepted",
            caught, is(notNullValue()));
        assertThat("rejection must be an authentication failure (SASL enforced), not a generic error",
            hasAuthenticationCause(caught), is(true));
    }

    private static KafkaSecurity saslSecurity(String username, String password) {
        return KafkaSecurity.builder()
            .securityProtocol("SASL_PLAINTEXT")
            .saslMechanism("PLAIN")
            .saslJaasConfig(PLAIN_LOGIN_MODULE + " required "
                + "username=\"" + username + "\" password=\"" + password + "\";")
            .build();
    }

    private Properties consumerProps(String groupId, KafkaSecurity security) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, groupId);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put("security.protocol", security.getSecurityProtocol());
        props.put("sasl.mechanism", security.getSaslMechanism());
        props.put("sasl.jaas.config", security.getSaslJaasConfig());
        return props;
    }

    private static boolean hasAuthenticationCause(Throwable t) {
        for (Throwable current = t; current != null; current = current.getCause()) {
            if (current instanceof AuthenticationException
                || current.getClass().getName().contains("Authentication")) {
                return true;
            }
            if (current.getCause() == current) {
                break;
            }
        }
        return false;
    }
}

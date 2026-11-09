package org.mockserver.async.controlplane;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.paho.client.mqttv3.MqttConnectOptions;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.junit.After;
import org.junit.Test;
import org.mockserver.async.security.Mqtt5SecurityOptions;
import org.mockserver.async.security.MqttSecurity;
import org.mockserver.async.security.MqttSecurityOptions;
import org.mockserver.configuration.Configuration;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * A request that could name an MQTT keystore or truststore could make the MQTT client read any keystore-format file
 * the MockServer process can read, and the other Paho {@code com.ibm.ssl.*} keys could weaken its TLS, so
 * {@code PUT /mockserver/asyncapi} refuses every {@code mqttSecurity.sslProperties} entry. The startup
 * {@code asyncMqttSsl*} properties reach the MQTT client of an {@code ssl://} or {@code wss://} broker, which while
 * any is set (or the JVM's keystore is) may only be the startup {@code asyncMqttBrokerUrl}.
 */
public class AsyncApiControlPlaneMqttSslStartupOnlyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    // the location setters require the file to exist
    private static final String TRUSTSTORE = existingFile("truststore");
    private static final String KEYSTORE = existingFile("keystore");
    private static final String STARTUP_BROKER = "ssl://broker:8883";

    private static final String SPEC = "{\"asyncapi\":\"2.6.0\",\"info\":{\"title\":\"t\",\"version\":\"1\"},"
        + "\"channels\":{\"orders\":{\"publish\":{\"message\":{\"payload\":{\"type\":\"string\"}}}}}}";

    private final Configuration configuration = Configuration.configuration().asyncMqttBrokerUrl(STARTUP_BROKER);
    private final AsyncApiControlPlaneImpl controlPlane = controlPlane(configuration);

    @After
    public void tearDown() {
        controlPlane.reset();
    }

    @Test
    public void shouldRefuseEveryKeystoreAndTruststoreKeyNamingItsStartupProperty() {
        Map<String, String[]> keys = Map.of(
            "com.ibm.ssl.keyStore", new String[]{"mockserver.asyncMqttSslKeystoreLocation", "MOCKSERVER_ASYNC_MQTT_SSL_KEYSTORE_LOCATION"},
            "com.ibm.ssl.keyStorePassword", new String[]{"mockserver.asyncMqttSslKeystorePassword", "MOCKSERVER_ASYNC_MQTT_SSL_KEYSTORE_PASSWORD"},
            "com.ibm.ssl.keyStoreType", new String[]{"mockserver.asyncMqttSslKeystoreType", "MOCKSERVER_ASYNC_MQTT_SSL_KEYSTORE_TYPE"},
            "com.ibm.ssl.trustStore", new String[]{"mockserver.asyncMqttSslTruststoreLocation", "MOCKSERVER_ASYNC_MQTT_SSL_TRUSTSTORE_LOCATION"},
            "com.ibm.ssl.trustStorePassword", new String[]{"mockserver.asyncMqttSslTruststorePassword", "MOCKSERVER_ASYNC_MQTT_SSL_TRUSTSTORE_PASSWORD"},
            "com.ibm.ssl.trustStoreType", new String[]{"mockserver.asyncMqttSslTruststoreType", "MOCKSERVER_ASYNC_MQTT_SSL_TRUSTSTORE_TYPE"});
        for (Map.Entry<String, String[]> key : keys.entrySet()) {
            IllegalArgumentException refused = refusal(sslPropertiesRequest(key.getKey(), "/proc/self/environ"));

            assertThat(key.getKey(), refused.getMessage(), containsString("cannot set " + key.getKey() + " over the control plane"));
            assertThat(key.getKey(), refused.getMessage(), containsString(key.getValue()[0]));
            assertThat(key.getKey(), refused.getMessage(), containsString(key.getValue()[1]));
            assertThat("the refusal must not echo the value", refused.getMessage(), not(containsString("/proc/self/environ")));
        }
    }

    @Test
    public void shouldRefuseEveryKeyThatChoosesHowTheClientDoesTlsNamingTheJvmSetting() {
        Map<String, String> keys = Map.of(
            "com.ibm.ssl.protocol", "jdk.tls.client.protocols",
            "com.ibm.ssl.enabledCipherSuites", "jdk.tls.client.cipherSuites",
            "com.ibm.ssl.contextProvider", "security.provider.N",
            "com.ibm.ssl.keyStoreProvider", "security.provider.N",
            "com.ibm.ssl.trustStoreProvider", "security.provider.N",
            "com.ibm.ssl.keyManager", "ssl.KeyManagerFactory.algorithm",
            "com.ibm.ssl.trustManager", "ssl.TrustManagerFactory.algorithm");
        for (Map.Entry<String, String> key : keys.entrySet()) {
            IllegalArgumentException refused = refusal(sslPropertiesRequest(key.getKey(), "SSL_RSA_WITH_NULL_SHA"));

            assertThat(key.getKey(), refused.getMessage(), containsString("cannot set " + key.getKey() + " over the control plane"));
            assertThat(key.getKey(), refused.getMessage(), containsString(key.getValue()));
            assertThat(key.getKey(), refused.getMessage(), not(containsString("SSL_RSA_WITH_NULL_SHA")));
        }
    }

    @Test
    public void shouldRefuseTheServerOnlyClientAuthenticationKey() {
        IllegalArgumentException refused = refusal(sslPropertiesRequest("com.ibm.ssl.clientAuthentication", "true"));

        assertThat(refused.getMessage(), containsString("no effect on the MQTT client"));
    }

    @Test
    public void shouldRefuseAnUnknownKeyWithoutEchoingTheValue() {
        IllegalArgumentException refused = refusal(sslPropertiesRequest("javax.net.debug", "/proc/self/environ"));

        assertThat(refused.getMessage(), containsString("unknown key 'javax.net.debug' in mqttSecurity.sslProperties"));
        assertThat(refused.getMessage(), containsString("mockserver.asyncMqttSsl*"));
        assertThat(refused.getMessage(), not(containsString("/proc/self/environ")));
    }

    @Test
    public void shouldRefuseCaseVariantDottedAndPrefixedKeysNamingTheStartupProperty() {
        Map<String, String> variants = Map.of(
            "COM.IBM.SSL.KEYSTORE", "mockserver.asyncMqttSslKeystoreLocation",
            "com_ibm_ssl_trustStorePassword", "mockserver.asyncMqttSslTruststorePassword",
            "keyStore", "mockserver.asyncMqttSslKeystoreLocation",
            "javax.net.ssl.trustStore", "mockserver.asyncMqttSslTruststoreLocation",
            "ssl.keystore.location", "mockserver.asyncMqttSslKeystoreLocation",
            "KeyStoreType", "mockserver.asyncMqttSslKeystoreType");
        for (Map.Entry<String, String> variant : variants.entrySet()) {
            IllegalArgumentException refused = refusal(sslPropertiesRequest(variant.getKey(), "/proc/self/environ"));

            assertThat(variant.getKey(), refused.getMessage(), containsString(variant.getValue()));
            assertThat(variant.getKey(), refused.getMessage(), not(containsString("/proc/self/environ")));
        }
    }

    @Test
    public void shouldRefuseANonTextualValue() {
        ObjectNode brokerConfig = sslPropertiesRequest("com.ibm.ssl.keyStore", null);
        ((ObjectNode) brokerConfig.get("mqttSecurity").get("sslProperties")).putObject("com.ibm.ssl.keyStore").put("path", "/proc/self/environ");

        IllegalArgumentException refused = refusal(brokerConfig);

        assertThat(refused.getMessage(), containsString("mockserver.asyncMqttSslKeystoreLocation"));
        assertThat(refused.getMessage(), not(containsString("/proc/self/environ")));
    }

    @Test
    public void shouldRefuseSslPropertiesThatIsNotAnObject() {
        for (String value : Arrays.asList("\"com.ibm.ssl.keyStore=/proc/self/environ\"", "[\"/proc/self/environ\"]", "42")) {
            ObjectNode brokerConfig = MAPPER.createObjectNode();
            brokerConfig.put("mqttBrokerUrl", STARTUP_BROKER);
            try {
                brokerConfig.putObject("mqttSecurity").set("sslProperties", MAPPER.readTree(value));
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }

            IllegalArgumentException refused = refusal(brokerConfig);

            assertThat(value, refused.getMessage(), containsString("'mqttSecurity.sslProperties' cannot be set over the control plane"));
            assertThat(value, refused.getMessage(), not(containsString("/proc/self/environ")));
        }
    }

    @Test
    public void shouldTreatJsonNullsAsAbsent() {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("mqttBrokerUrl", "tcp://broker:1883");
        ObjectNode mqttSecurity = brokerConfig.putObject("mqttSecurity");
        mqttSecurity.put("username", "u");
        mqttSecurity.putObject("sslProperties").putNull("com.ibm.ssl.keyStore");
        brokerConfig.putNull("com.ibm.ssl.trustStore");

        MqttSecurity parsed = controlPlane.parseBrokerConfig(brokerConfig).mqttSecurity;

        assertThat(parsed.getUsername(), is("u"));
        assertThat(parsed.getSslProperties(), is(anEmptyMap()));

        ObjectNode nullSslProperties = MAPPER.createObjectNode();
        nullSslProperties.putObject("mqttSecurity").putNull("sslProperties");
        assertThat(controlPlane.parseBrokerConfig(nullSslProperties).mqttSecurity, is(nullValue()));
    }

    @Test
    public void shouldRefuseAnSslKeyMisplacedDirectlyInMqttSecurityOrAtTheTopLevelOfBrokerConfig() {
        ObjectNode inMqttSecurity = MAPPER.createObjectNode();
        inMqttSecurity.putObject("mqttSecurity").put("com.ibm.ssl.keyStore", "/proc/self/environ");
        ObjectNode atTopLevel = MAPPER.createObjectNode();
        atTopLevel.put("com.ibm.ssl.keyStorePassword", "/proc/self/environ");
        ObjectNode sslPropertiesAtTopLevel = MAPPER.createObjectNode();
        sslPropertiesAtTopLevel.putObject("sslProperties").put("com.ibm.ssl.keyStore", "/proc/self/environ");

        assertThat(refusal(inMqttSecurity).getMessage(), containsString("mockserver.asyncMqttSslKeystoreLocation"));
        assertThat(refusal(atTopLevel).getMessage(), containsString("mockserver.asyncMqttSslKeystorePassword"));
        assertThat(refusal(sslPropertiesAtTopLevel).getMessage(), containsString("'mqttSecurity.sslProperties' cannot be set"));
        for (ObjectNode brokerConfig : Arrays.asList(inMqttSecurity, atTopLevel, sslPropertiesAtTopLevel)) {
            assertThat(refusal(brokerConfig).getMessage(), not(containsString("/proc/self/environ")));
        }
    }

    @Test
    public void shouldStillAcceptTheOrdinaryMqttFields() {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("mqttBrokerUrl", "tcp://broker:1883");
        brokerConfig.put("mqttProtocolVersion", 5);
        brokerConfig.put("mqttClientId", "client");
        brokerConfig.putObject("mqttSecurity").put("username", "u").put("password", "p").putObject("sslProperties");

        AsyncApiControlPlaneImpl.BrokerConfig parsed = controlPlane.parseBrokerConfig(brokerConfig);

        assertThat(parsed.mqttProtocolVersion, is(5));
        assertThat(parsed.mqttSecurity.getPassword(), is("p"));
    }

    @Test
    public void shouldRefuseAnSslPropertyOnLoadSoNoBrokerConnectionIsMade() throws Exception {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("spec", SPEC);
        body.set("brokerConfig", sslPropertiesRequest("com.ibm.ssl.keyStore", "/root/keystore.p12"));

        IllegalArgumentException refused = null;
        try {
            controlPlane.load(MAPPER.writeValueAsString(body));
        } catch (IllegalArgumentException e) {
            refused = e;
        }

        assertThat("load must refuse the request with a client error (400)", refused, is(notNullValue()));
        assertThat(refused.getMessage(), containsString("mockserver.asyncMqttSslKeystoreLocation"));
        assertThat(controlPlane.status().get("publishers").asInt(), is(0));
    }

    @Test
    public void shouldBindATlsBrokerToTheStartupBrokerWhileAStartupSslSettingIsSet() {
        startupSslSettings();
        for (String url : Arrays.asList("ssl://attacker.example:8883", "wss://attacker.example/mqtt", "SSL://attacker.example:8883", "local://attacker")) {
            IllegalArgumentException refused = refusal(brokerUrlRequest(url));

            assertThat(url, refused.getMessage(), containsString("'mqttBrokerUrl' must be omitted or match the startup mockserver.asyncMqttBrokerUrl"));
        }
    }

    @Test
    public void shouldBindATlsBrokerEvenWhenOnlyTheTruststoreTypeIsSetAtStartup() {
        configuration.asyncMqttSslTruststoreType("PKCS12");

        assertThat(refusal(brokerUrlRequest("ssl://attacker.example:8883")).getMessage(), containsString("'mqttBrokerUrl' must be omitted or match"));
    }

    @Test
    public void shouldBindATlsBrokerWhileTheJvmKeystoreIsSet() {
        AsyncApiControlPlaneImpl withJvmKeyStore = new AsyncApiControlPlaneImpl(configuration, () -> "/etc/jvm-keystore.p12");
        try {
            IllegalArgumentException refused = null;
            try {
                withJvmKeyStore.parseBrokerConfig(brokerUrlRequest("ssl://attacker.example:8883"));
            } catch (IllegalArgumentException e) {
                refused = e;
            }

            assertThat(refused, is(notNullValue()));
            assertThat(refused.getMessage(), containsString("'mqttBrokerUrl' must be omitted or match"));
            assertThat("no startup MQTT SSL property is set, so none is given to the client",
                withJvmKeyStore.parseBrokerConfig(brokerUrlRequest(STARTUP_BROKER)).mqttSecurity, is(nullValue()));
        } finally {
            withJvmKeyStore.reset();
        }
    }

    @Test
    public void shouldRefuseANonAsciiBrokerUrlThatUnicodeCaseFoldingWouldMatchToTheStartupBroker() {
        configuration.asyncMqttBrokerUrl("ssl://ingest-broker:8883");
        startupSslSettings();
        // dotless i, dotted capital I, Kelvin sign and long s each equal an ASCII letter under String.equalsIgnoreCase
        for (String url : Arrays.asList(
            "ssl://\u0131ngest-broker:8883",
            "ssl://\u0130ngest-broker:8883",
            "ssl://ingest-bro\u212Aer:8883",
            "\u017Fsl://ingest-broker:8883")) {
            assertThat(url, refusal(brokerUrlRequest(url)).getMessage(), containsString("'mqttBrokerUrl' must be omitted or match"));
        }
    }

    @Test
    public void shouldAcceptTheStartupBrokerAndGiveTheClientTheStartupSslSettings() {
        startupSslSettings();
        for (AsyncApiControlPlaneImpl.BrokerConfig parsed : Arrays.asList(
            controlPlane.parseBrokerConfig(brokerUrlRequest(" SSL://BROKER:8883 ")),
            controlPlane.parseBrokerConfig(MAPPER.createObjectNode()),
            controlPlane.parseBrokerConfig(null))) {

            assertThat("the client connects to the startup spelling, not the request's", parsed.mqttBrokerUrl, is(STARTUP_BROKER));
            MqttConnectOptions v3 = MqttSecurityOptions.buildConnectOptions(parsed.mqttSecurity);
            MqttConnectionOptions v5 = Mqtt5SecurityOptions.buildConnectOptions(parsed.mqttSecurity);
            for (Properties sslProperties : Arrays.asList(v3.getSSLProperties(), v5.getSSLProperties())) {
                assertThat(sslProperties.getProperty("com.ibm.ssl.keyStore"), is(KEYSTORE));
                assertThat(sslProperties.getProperty("com.ibm.ssl.keyStorePassword"), is("keystore-secret"));
                assertThat(sslProperties.getProperty("com.ibm.ssl.keyStoreType"), is("PKCS12"));
                assertThat(sslProperties.getProperty("com.ibm.ssl.trustStore"), is(TRUSTSTORE));
                assertThat(sslProperties.getProperty("com.ibm.ssl.trustStorePassword"), is("truststore-secret"));
                assertThat(sslProperties.getProperty("com.ibm.ssl.trustStoreType"), is("JKS"));
                assertThat(sslProperties.stringPropertyNames(), hasSize(6));
            }
        }
    }

    @Test
    public void shouldKeepTheRequestCredentialsAlongsideTheStartupSslSettings() {
        startupSslSettings();
        ObjectNode brokerConfig = brokerUrlRequest(STARTUP_BROKER);
        brokerConfig.putObject("mqttSecurity").put("username", "u").put("password", "p");

        MqttSecurity parsed = controlPlane.parseBrokerConfig(brokerConfig).mqttSecurity;

        assertThat(parsed.getUsername(), is("u"));
        assertThat(parsed.getPassword(), is("p"));
        assertThat(parsed.getSslProperties(), hasEntry("com.ibm.ssl.keyStore", KEYSTORE));
    }

    @Test
    public void shouldNotGiveThePlainBrokersTheStartupSslSettingsAndLetThemNameAnyBroker() {
        startupSslSettings();
        for (String url : Arrays.asList("tcp://elsewhere.example:1883", "ws://elsewhere.example/mqtt", " TCP://elsewhere.example:1883")) {
            AsyncApiControlPlaneImpl.BrokerConfig parsed = controlPlane.parseBrokerConfig(brokerUrlRequest(url));

            assertThat(url, parsed.mqttBrokerUrl, is(url));
            assertThat(url, parsed.mqttSecurity, is(nullValue()));
        }
    }

    @Test
    public void shouldRefuseATlsBrokerWhenAStartupSslSettingIsSetButNoStartupBroker() {
        AsyncApiControlPlaneImpl withoutStartupBroker = controlPlane(Configuration.configuration()
            .asyncMqttBrokerUrl("")
            .asyncMqttSslKeystoreLocation(KEYSTORE));
        try {
            IllegalArgumentException refused = null;
            try {
                withoutStartupBroker.parseBrokerConfig(brokerUrlRequest(STARTUP_BROKER));
            } catch (IllegalArgumentException e) {
                refused = e;
            }

            assertThat(refused, is(notNullValue()));
            assertThat(refused.getMessage(), containsString("needs mockserver.asyncMqttBrokerUrl"));
        } finally {
            withoutStartupBroker.reset();
        }
    }

    @Test
    public void shouldLetATlsBrokerBeAnyBrokerWhileNoStartupSslSettingIsSet() {
        AsyncApiControlPlaneImpl.BrokerConfig parsed = controlPlane.parseBrokerConfig(brokerUrlRequest("ssl://elsewhere.example:8883"));

        assertThat(parsed.mqttBrokerUrl, is("ssl://elsewhere.example:8883"));
        assertThat(parsed.mqttSecurity, is(nullValue()));
    }

    @Test
    public void shouldTreatEverySchemeButTcpAndWsAsTls() {
        for (String plain : List.of("tcp://h:1883", "ws://h/mqtt", " Tcp://h:1883")) {
            assertThat(plain, AsyncApiControlPlaneImpl.usesTls(plain), is(false));
        }
        for (String tls : List.of("ssl://h:8883", "wss://h/mqtt", "local://h", "h:1883", "tcp:/h")) {
            assertThat(tls, AsyncApiControlPlaneImpl.usesTls(tls), is(true));
        }
    }

    private void startupSslSettings() {
        configuration
            .asyncMqttSslKeystoreLocation(KEYSTORE)
            .asyncMqttSslKeystorePassword("keystore-secret")
            .asyncMqttSslKeystoreType("PKCS12")
            .asyncMqttSslTruststoreLocation(TRUSTSTORE)
            .asyncMqttSslTruststorePassword("truststore-secret")
            .asyncMqttSslTruststoreType("JKS");
    }

    // the JVM keystore is pinned so the result does not depend on the test JVM's javax.net.ssl.keyStore
    private static AsyncApiControlPlaneImpl controlPlane(Configuration configuration) {
        return new AsyncApiControlPlaneImpl(configuration, () -> null);
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

    private static ObjectNode brokerUrlRequest(String url) {
        ObjectNode brokerConfig = MAPPER.createObjectNode();
        brokerConfig.put("mqttBrokerUrl", url);
        return brokerConfig;
    }

    private static ObjectNode sslPropertiesRequest(String key, String value) {
        ObjectNode brokerConfig = brokerUrlRequest(STARTUP_BROKER);
        ObjectNode sslProperties = brokerConfig.putObject("mqttSecurity").putObject("sslProperties");
        if (value != null) {
            sslProperties.put(key, value);
        }
        return brokerConfig;
    }
}

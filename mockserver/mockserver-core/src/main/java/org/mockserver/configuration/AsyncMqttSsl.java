package org.mockserver.configuration;

import javax.annotation.Nullable;

/**
 * Whether the AsyncAPI MQTT client holds a TLS identity or trust setting chosen at startup: any
 * {@code asyncMqttSsl*} property, or the JVM's {@code javax.net.ssl.keyStore}, which Paho uses when no keystore is
 * set. While it does, {@code asyncMqttBrokerUrl} is fixed at startup and is the only broker a
 * {@code PUT /mockserver/asyncapi} request may use over TLS (any scheme other than {@code tcp://} or
 * {@code ws://}), so that identity is only presented to that broker.
 */
public final class AsyncMqttSsl {

    public static final String JVM_KEY_STORE_PROPERTY = "javax.net.ssl.keyStore";

    private AsyncMqttSsl() {
    }

    public static boolean isConfigured(Configuration configuration) {
        return isConfigured(configuration, System.getProperty(JVM_KEY_STORE_PROPERTY));
    }

    public static boolean isConfigured(Configuration configuration, @Nullable String jvmKeyStore) {
        return isSet(jvmKeyStore)
            || isSet(configuration.asyncMqttSslKeystoreLocation())
            || isSet(configuration.asyncMqttSslKeystorePassword())
            || isSet(configuration.asyncMqttSslKeystoreType())
            || isSet(configuration.asyncMqttSslTruststoreLocation())
            || isSet(configuration.asyncMqttSslTruststorePassword())
            || isSet(configuration.asyncMqttSslTruststoreType());
    }

    private static boolean isSet(@Nullable String value) {
        return value != null && !value.isBlank();
    }
}

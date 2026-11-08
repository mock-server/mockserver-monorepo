package org.mockserver.httpclient;

import org.mockserver.configuration.Configuration;
import org.mockserver.logging.MockServerLogger;
import org.mockserver.socket.tls.NettySslContextFactory;

import java.util.function.Function;

/**
 * Builds {@link ForwardCredentialHeaders} for tests in other packages, as only the forward client builds them otherwise.
 */
public final class ForwardCredentialHeadersTestSupport {

    private ForwardCredentialHeadersTestSupport() {
    }

    public static ForwardCredentialHeaders resolve(String value, Function<String, String> environment, MockServerLogger mockServerLogger) {
        return ForwardCredentialHeaders.resolve(value, environment, mockServerLogger);
    }

    /**
     * As the forward client builds them, with their TLS context, reading each environment variable through
     * {@code environment}.
     */
    public static ForwardCredentialHeaders forConfiguration(Configuration configuration, Function<String, String> environment, MockServerLogger mockServerLogger) {
        return ForwardCredentialHeaders.forConfiguration(configuration, mockServerLogger, new NettySslContextFactory(configuration, mockServerLogger, false), environment);
    }
}

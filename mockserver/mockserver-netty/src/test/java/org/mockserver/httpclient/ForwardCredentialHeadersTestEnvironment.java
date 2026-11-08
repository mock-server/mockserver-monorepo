package org.mockserver.httpclient;

import java.util.function.Function;

/**
 * Lets integration tests in this module give {@link ForwardCredentialHeaders} environment variables, which a running
 * JVM cannot set; the hook itself is package-private so production code outside this package cannot reach it.
 */
public final class ForwardCredentialHeadersTestEnvironment {

    private ForwardCredentialHeadersTestEnvironment() {
    }

    public static void use(Function<String, String> lookup) {
        ForwardCredentialHeaders.environmentForTesting(lookup);
    }

    public static void restore() {
        ForwardCredentialHeaders.environmentForTesting(null);
    }
}

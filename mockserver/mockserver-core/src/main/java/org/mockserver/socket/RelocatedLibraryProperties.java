package org.mockserver.socket;

import io.netty.util.internal.PlatformDependent;

import java.util.List;
import java.util.Properties;

/**
 * Copies a user's system properties for bundled libraries to the names those libraries read in the shaded jars.
 * <p>
 * The no-dependencies jar and the Docker image jar relocate bundled libraries under {@code shaded_package}, and the
 * shade plugin also rewrites string literals that start with a relocated package, so relocated Netty reads
 * {@code shaded_package.io.netty.allocator.type} and ignores a user's {@code -Dio.netty.allocator.type}. The same
 * applies to the system properties of BouncyCastle, Kafka, Avro, Prometheus, OpenTelemetry and the other bundled
 * libraries under {@link #RELOCATED_ROOTS}. A property already set under the relocated name is left alone.
 * <p>
 * Libraries read most of these properties once, when they first initialise, so this is called first from the CLI
 * entry point, not from embedded use. Outside a shaded jar it does nothing.
 */
public final class RelocatedLibraryProperties {

    // Assembled at runtime: in the shaded jar a literal starting with a relocated package would itself be rewritten.
    private static final String PLATFORM_DEPENDENT = String.join(".", "io", "netty", "util", "internal", "PlatformDependent");
    static final List<String> RELOCATED_ROOTS = List.of(
        String.join(".", "io", "netty", ""),
        String.join(".", "io", "prometheus", ""),
        String.join(".", "io", "opentelemetry", ""),
        String.join(".", "org", "bouncycastle", ""),
        String.join(".", "org", "apache", ""),
        String.join(".", "com", "fasterxml", ""),
        String.join(".", "com", "google", ""),
        String.join(".", "jakarta", "activation", "")
    );

    private RelocatedLibraryProperties() {
        // utility class
    }

    /**
     * @return the number of properties copied; 0 outside a shaded jar
     */
    public static int forwardUserProperties() {
        try {
            return forward(System.getProperties(), relocationPrefix());
        } catch (RuntimeException | LinkageError e) {
            return 0;
        }
    }

    static int forward(Properties properties, String relocationPrefix) {
        if (relocationPrefix.isEmpty()) {
            return 0;
        }
        int copied = 0;
        for (String key : properties.stringPropertyNames()) {
            if (key.startsWith(relocationPrefix) || RELOCATED_ROOTS.stream().noneMatch(key::startsWith)) {
                continue;
            }
            String relocatedKey = relocationPrefix + key;
            if (properties.getProperty(relocatedKey) == null) {
                properties.setProperty(relocatedKey, properties.getProperty(key));
                copied++;
            }
        }
        return copied;
    }

    /**
     * @return the package prefix the shade plugin added to bundled libraries, e.g. "shaded_package.", or "" when unshaded
     */
    static String relocationPrefix() {
        String name = PlatformDependent.class.getName();
        return name.endsWith(PLATFORM_DEPENDENT) ? name.substring(0, name.length() - PLATFORM_DEPENDENT.length()) : "";
    }
}

package org.mockserver.test;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.Properties;

/**
 * Single source of truth for the container image references used by the
 * Docker-gated test suites, with optional indirection through an ECR
 * pull-through cache.
 * <p>
 * The public image names and tags live in ONE place —
 * {@code org/mockserver/test/test-container-images.properties} on the classpath,
 * read here by the Java suites and by {@code .buildkite/scripts/lib/test-images.sh}
 * on the CI host — so the test constants and the pre-pull call sites can no
 * longer drift out of sync (the drift risk flagged when the two were duplicated).
 * <p>
 * <strong>Registry indirection.</strong> When {@code MOCKSERVER_TEST_IMAGE_REGISTRY}
 * (environment variable) or {@code mockserver.test.image.registry} (system
 * property) is set to a registry host, every reference is rewritten to that
 * registry's pull-through cache repository. CI sets it to the {@code mockserver-build}
 * account's regional ECR host so pulls stay in-region and the upstream registries
 * (quay.io, Docker Hub, mcr.microsoft.com) are contacted only on a first cache
 * miss. Left unset — the default for local developers and forks, which have no
 * ECR access — the public names are used verbatim, so those environments are
 * completely unaffected.
 * <p>
 * The rewrite here MUST stay in lockstep with {@code resolve_test_image} in
 * {@code test-images.sh} and the {@code ecr_repository_prefix} values in
 * {@code terraform/buildkite-agents/ecr-pull-through-cache.tf}. The two CACHED
 * upstreams and their ECR prefixes, plus the one unsupported passthrough:
 * <pre>
 *   quay.io           -> quay/&lt;path&gt;         quay.io/minio/minio          -> &lt;reg&gt;/quay/minio/minio
 *   Docker Hub        -> docker-hub/&lt;path&gt;   fsouza/fake-gcs-server       -> &lt;reg&gt;/docker-hub/fsouza/fake-gcs-server
 *                                              rabbitmq (official)          -> &lt;reg&gt;/docker-hub/library/rabbitmq
 *   mcr.microsoft.com -> UNCHANGED           mcr.microsoft.com/.../azurite -> mcr.microsoft.com/azure-storage/azurite
 * </pre>
 * mcr.microsoft.com is not a supported ECR pull-through upstream, so azurite
 * always pulls direct from Microsoft (passthrough); every other unmapped host is
 * rejected so a new registry cannot be silently mis-cached.
 */
public final class TestContainerImages {

    /** Environment variable that, when set, points image references at an ECR pull-through cache registry host. */
    public static final String REGISTRY_ENV = "MOCKSERVER_TEST_IMAGE_REGISTRY";
    /** System-property equivalent of {@link #REGISTRY_ENV} (env takes precedence). */
    public static final String REGISTRY_PROPERTY = "mockserver.test.image.registry";

    private static final String RESOURCE = "/org/mockserver/test/test-container-images.properties";
    private static final Properties IMAGES = load();

    /** {@code adobe/s3mock} (Docker Hub) -- S3 API emulator used by the S3 blob-store suites. */
    public static final String S3MOCK = resolve("s3mock");
    /** {@code fsouza/fake-gcs-server}. */
    public static final String FAKE_GCS_SERVER = resolve("fake-gcs-server");
    /** {@code mcr.microsoft.com/azure-storage/azurite}. */
    public static final String AZURITE = resolve("azurite");
    /** {@code confluentinc/cp-kafka}. */
    public static final String CP_KAFKA = resolve("cp-kafka");
    /** {@code rabbitmq} (Docker Hub official image). */
    public static final String RABBITMQ = resolve("rabbitmq");
    /** {@code eclipse-mosquitto} (Docker Hub official image). */
    public static final String ECLIPSE_MOSQUITTO = resolve("eclipse-mosquitto");

    private TestContainerImages() {
    }

    /**
     * The image reference for {@code key}, rewritten to the configured ECR
     * pull-through cache registry when one is set, otherwise the public name.
     *
     * @throws IllegalArgumentException if {@code key} is not in the properties file
     */
    public static String resolve(String key) {
        String publicRef = IMAGES.getProperty(key);
        if (publicRef == null) {
            throw new IllegalArgumentException("No image mapped for key '" + key + "' in " + RESOURCE);
        }
        String registry = registry();
        return (registry == null || registry.isEmpty()) ? publicRef : toEcr(publicRef, registry);
    }

    /**
     * The PUBLIC repository (host/path without the tag) for {@code key}, for use
     * as the argument to Testcontainers' {@code asCompatibleSubstituteFor(...)}
     * so specialised containers (Kafka, RabbitMQ) accept a rewritten image name.
     */
    public static String publicRepository(String key) {
        String publicRef = IMAGES.getProperty(key);
        if (publicRef == null) {
            throw new IllegalArgumentException("No image mapped for key '" + key + "' in " + RESOURCE);
        }
        return stripTag(publicRef);
    }

    private static String registry() {
        String env = System.getenv(REGISTRY_ENV);
        if (env != null && !env.isEmpty()) {
            return env.trim();
        }
        String prop = System.getProperty(REGISTRY_PROPERTY);
        return prop == null ? null : prop.trim();
    }

    /**
     * Rewrite a public image reference to its ECR pull-through cache equivalent.
     * Package-private for unit testing (see {@code TestContainerImagesTest}).
     */
    static String toEcr(String ref, String registry) {
        String name = stripTag(ref);
        String tagSuffix = ref.substring(name.length()); // "" or ":<tag>"

        int firstSlash = name.indexOf('/');
        String host = firstSlash > 0 ? name.substring(0, firstSlash) : "";
        boolean hasRegistryHost = firstSlash > 0 && (host.indexOf('.') >= 0 || host.indexOf(':') >= 0);

        // mcr.microsoft.com is NOT a supported ECR pull-through upstream: creating
        // the rule fails with UnsupportedUpstreamRegistryException (confirmed on the
        // first real apply -- see ecr-pull-through-cache.tf). Azurite therefore
        // CANNOT be cached and must keep pulling direct from Microsoft even when a
        // cache registry IS configured. This is a deliberate known-unsupported
        // passthrough (return the public ref unchanged), NOT a silent fallthrough --
        // the else branch below still rejects any other unmapped host.
        if (hasRegistryHost && host.equals("mcr.microsoft.com")) {
            return ref;
        }

        String prefix;
        String path;
        if (!hasRegistryHost) {
            // Docker Hub. Official (single-segment) images live under library/.
            prefix = "docker-hub";
            path = (name.indexOf('/') < 0) ? "library/" + name : name;
        } else if (host.equals("quay.io")) {
            prefix = "quay";
            path = name.substring(firstSlash + 1);
        } else {
            throw new IllegalArgumentException(
                "No ECR pull-through cache prefix mapped for registry host '" + host + "' in image '" + ref
                    + "'. Add a rule in ecr-pull-through-cache.tf and map the host here and in test-images.sh "
                    + "(or, for an upstream ECR does not support, add an explicit passthrough like mcr.microsoft.com).");
        }
        return registry + "/" + prefix + "/" + path + tagSuffix;
    }

    /** Strip a trailing {@code :tag} (the ':' after the last '/'), leaving host/path. */
    private static String stripTag(String ref) {
        int lastSlash = ref.lastIndexOf('/');
        int tagColon = ref.indexOf(':', lastSlash + 1);
        return tagColon >= 0 ? ref.substring(0, tagColon) : ref;
    }

    private static Properties load() {
        Properties properties = new Properties();
        try (InputStream in = TestContainerImages.class.getResourceAsStream(RESOURCE)) {
            if (in == null) {
                throw new IllegalStateException("Missing classpath resource " + RESOURCE);
            }
            properties.load(in);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load " + RESOURCE, e);
        }
        return properties;
    }
}

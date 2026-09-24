package org.mockserver.state;

import org.mockserver.configuration.Configuration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Pluggable factory for the {@link StateBackend}.
 * <p>
 * Mirrors the {@link org.mockserver.mock.ExpectationStoreFactory} pattern:
 * a volatile-registry with a default in-memory implementation. An optional
 * clustered backend (phase 2b, in a separate module) can register a
 * {@link Factory} that returns a clustering-aware {@code StateBackend}
 * without {@code mockserver-core} depending on the data grid.
 * <p>
 * Auto-discovery: when {@code stateBackend=infinispan} is configured and
 * no custom factory has been registered, the factory attempts to load
 * {@code InfinispanStateBackendRegistrar} via reflection from the classpath.
 * If the class is found, its {@code register()} method is called, which
 * registers the Infinispan factory. This keeps mockserver-core free of any
 * compile-time dependency on the Infinispan module.
 * <p>
 * Cloud blob store discovery: when {@code blobStoreType} is set to a cloud
 * backend name (s3, gcs, azure) and no matching {@link BlobStoreFactory}
 * has been registered, the factory attempts to load the corresponding
 * registrar class via reflection. Each cloud module provides a registrar
 * that calls {@link #registerBlobStoreFactory(String, BlobStoreFactory)}.
 * <p>
 * Thread-safety: the factory reference is {@code volatile}; register/reset
 * are expected at startup. The blob store factory registry is a
 * {@code ConcurrentHashMap}.
 */
public final class StateBackendFactory {

    private static final Logger LOG = LoggerFactory.getLogger(StateBackendFactory.class);

    private static final String INFINISPAN_REGISTRAR_CLASS =
        "org.mockserver.state.infinispan.InfinispanStateBackendRegistrar";

    /**
     * Registrar class names for cloud blob store backends. When
     * {@code blobStoreType} matches one of these keys, the corresponding
     * registrar is loaded via reflection to register its
     * {@link BlobStoreFactory} without core depending on the cloud SDK.
     */
    private static final Map<String, String> BLOB_STORE_REGISTRARS = Map.of(
        "s3", "org.mockserver.blob.s3.S3BlobStoreRegistrar",
        "gcs", "org.mockserver.blob.gcs.GcsBlobStoreRegistrar",
        "azure", "org.mockserver.blob.azure.AzureBlobStoreRegistrar"
    );

    /**
     * Creates a {@link StateBackend} for an {@code HttpState} instance.
     */
    @FunctionalInterface
    public interface Factory {
        StateBackend create(Configuration configuration);
    }

    private static final Factory DEFAULT_FACTORY =
        configuration -> {
            BlobStore blobStore = createBlobStore(configuration);
            return new InMemoryStateBackend(configuration.maxExpectations(), configuration.maxExpectationsSizeInBytes(), blobStore);
        };

    private static volatile Factory factory = DEFAULT_FACTORY;

    /**
     * Registry of pluggable blob store factories, keyed by type name
     * (e.g. "s3", "gcs", "azure"). Cloud modules register here via
     * {@link #registerBlobStoreFactory(String, BlobStoreFactory)}.
     */
    private static final ConcurrentHashMap<String, BlobStoreFactory> blobStoreFactories =
        new ConcurrentHashMap<>();

    private StateBackendFactory() {
    }

    /**
     * Register a custom factory (e.g. a clustered backend). Passing
     * {@code null} resets to the default in-memory factory.
     */
    public static void register(Factory customFactory) {
        factory = (customFactory != null) ? customFactory : DEFAULT_FACTORY;
    }

    /**
     * Register a pluggable {@link BlobStoreFactory} for a given type name.
     * Cloud blob store modules call this from their registrar classes to
     * make themselves available when {@code blobStoreType} is configured.
     *
     * @param typeName the blob store type name (e.g. "s3", "gcs", "azure")
     * @param blobStoreFactory the factory that creates the blob store
     */
    public static void registerBlobStoreFactory(String typeName, BlobStoreFactory blobStoreFactory) {
        blobStoreFactories.put(typeName.toLowerCase(), blobStoreFactory);
        LOG.info("registered BlobStoreFactory for type '{}'", typeName);
    }

    /**
     * Reset to the default in-memory factory (primarily for tests).
     */
    public static void resetToDefault() {
        factory = DEFAULT_FACTORY;
        blobStoreFactories.clear();
    }

    /**
     * @return {@code true} if a non-default factory is registered.
     */
    public static boolean isCustomFactoryRegistered() {
        return factory != DEFAULT_FACTORY;
    }

    /**
     * @return {@code true} if a blob store factory is registered for the
     *         given type name.
     */
    public static boolean isBlobStoreFactoryRegistered(String typeName) {
        return blobStoreFactories.containsKey(typeName.toLowerCase());
    }

    /**
     * Create the state backend via the registered factory. If
     * {@code stateBackend=infinispan} is configured and no custom factory
     * has been registered yet, attempts auto-discovery of the Infinispan
     * module from the classpath via reflection.
     */
    public static StateBackend create(Configuration configuration) {
        warnIfClusteredWithDynamicCertificateAuthority(configuration);
        String backendName = configuration.stateBackend();
        if ("infinispan".equalsIgnoreCase(backendName) && !isCustomFactoryRegistered()) {
            discoverInfinispanBackend();
        }
        return factory.create(configuration);
    }

    /**
     * Whether the configuration is in the broken combination of a clustered
     * (multi-node) deployment AND per-node dynamic Certificate Authority
     * generation. In that combination every node mints its OWN CA, so a TLS
     * client that trusts one node's CA fails validation against the others —
     * an intermittent trust error behind a load balancer that rotates nodes.
     * <p>
     * The cluster-configuration layer (this factory) is the architecturally
     * correct home for the check: {@code clusterEnabled} is the state-backend
     * concern, and this is the single place every {@code HttpState} obtains its
     * backend at start up. The fix is to supply ONE shared CA to every node
     * ({@code certificateAuthorityCertificate} + {@code certificateAuthorityPrivateKey})
     * and set {@code dynamicallyCreateCertificateAuthorityCertificate=false}.
     *
     * @return {@code true} when {@code clusterEnabled} and
     *         {@code dynamicallyCreateCertificateAuthorityCertificate} are both on
     */
    static boolean isClusteredWithDynamicCertificateAuthority(Configuration configuration) {
        return configuration.clusterEnabled()
            && Boolean.TRUE.equals(configuration.dynamicallyCreateCertificateAuthorityCertificate());
    }

    private static void warnIfClusteredWithDynamicCertificateAuthority(Configuration configuration) {
        if (isClusteredWithDynamicCertificateAuthority(configuration)) {
            LOG.warn("clusterEnabled=true together with dynamic Certificate Authority generation "
                + "(dynamicallyCreateCertificateAuthorityCertificate=true): each node generates its OWN CA, "
                + "so a TLS client that trusts one node's CA will fail certificate validation against the "
                + "other nodes (an intermittent trust error behind a load balancer that rotates nodes). "
                + "Supply one shared CA to every node via certificateAuthorityCertificate + "
                + "certificateAuthorityPrivateKey and set dynamicallyCreateCertificateAuthorityCertificate=false. "
                + "See docs/code/clustered-state.md (Per-Node Dynamic CA).");
        }
    }

    /**
     * Creates the appropriate {@link BlobStore} based on the
     * {@code blobStoreType} configuration property.
     * <ul>
     *   <li>{@code "filesystem"} (default) -- creates a {@link FilesystemBlobStore}
     *       that writes to the filesystem using the same I/O patterns as the
     *       pre-existing persistence classes, so on-disk behaviour is
     *       byte-for-byte identical.</li>
     *   <li>{@code "memory"} -- creates an {@link InMemoryBlobStore} that holds
     *       blobs in-process only (lost on exit).</li>
     *   <li>{@code "s3"}, {@code "gcs"}, {@code "azure"} -- discovers and
     *       delegates to the matching cloud blob store module via the
     *       {@link BlobStoreFactory} registry.</li>
     * </ul>
     */
    static BlobStore createBlobStore(Configuration configuration) {
        String blobStoreType = configuration.blobStoreType();
        if ("filesystem".equalsIgnoreCase(blobStoreType)) {
            return new FilesystemBlobStore(null);
        }
        if ("memory".equalsIgnoreCase(blobStoreType)) {
            return new InMemoryBlobStore();
        }
        // Cloud blob store types: discover and delegate
        String typeKey = blobStoreType.toLowerCase();
        if (!blobStoreFactories.containsKey(typeKey)) {
            discoverBlobStoreBackend(typeKey);
        }
        BlobStoreFactory blobStoreFactory = blobStoreFactories.get(typeKey);
        if (blobStoreFactory == null) {
            throw new IllegalStateException(
                "blobStoreType=" + blobStoreType + " configured but no BlobStoreFactory registered for it"
            );
        }
        return blobStoreFactory.create(configuration);
    }

    /**
     * Attempt to load and register the Infinispan backend via reflection.
     * If the class is not on the classpath or registration fails, throws
     * {@link IllegalStateException} -- silently falling through to in-memory
     * would cause split-brain: a node the operator believes is clustered
     * would run isolated in-memory.
     */
    private static synchronized void discoverInfinispanBackend() {
        if (isCustomFactoryRegistered()) {
            return; // another thread already registered
        }
        try {
            Class<?> registrarClass = Class.forName(INFINISPAN_REGISTRAR_CLASS);
            Method registerMethod = registrarClass.getMethod("register");
            registerMethod.invoke(null);
            LOG.info("auto-discovered Infinispan state backend from classpath");
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "stateBackend=infinispan configured but " + INFINISPAN_REGISTRAR_CLASS
                    + " is not on the classpath; add the mockserver-state-infinispan dependency", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                "stateBackend=infinispan configured but failed to register the Infinispan state backend", e);
        }
    }

    /**
     * Attempt to load and register a cloud blob store backend via reflection.
     * The registrar class is looked up from {@link #BLOB_STORE_REGISTRARS}.
     * If the class is not on the classpath, throws {@link IllegalStateException}
     * with a helpful message about the missing module dependency.
     */
    private static synchronized void discoverBlobStoreBackend(String typeKey) {
        if (blobStoreFactories.containsKey(typeKey)) {
            return; // another thread already registered
        }
        String registrarClassName = BLOB_STORE_REGISTRARS.get(typeKey);
        if (registrarClassName == null) {
            throw new IllegalStateException(
                "blobStoreType=" + typeKey + " is not a recognised blob store type; "
                    + "supported types: memory, filesystem, s3, gcs, azure"
            );
        }
        try {
            Class<?> registrarClass = Class.forName(registrarClassName);
            Method registerMethod = registrarClass.getMethod("register");
            registerMethod.invoke(null);
            LOG.info("auto-discovered blob store backend '{}' from classpath", typeKey);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException(
                "blobStoreType=" + typeKey + " configured but " + registrarClassName
                    + " is not on the classpath; add the mockserver-blob-" + typeKey + " dependency", e);
        } catch (Exception e) {
            throw new IllegalStateException(
                "blobStoreType=" + typeKey + " configured but failed to register the blob store backend", e);
        }
    }
}

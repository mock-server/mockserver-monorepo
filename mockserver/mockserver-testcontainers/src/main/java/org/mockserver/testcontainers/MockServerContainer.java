package org.mockserver.testcontainers;

import com.github.dockerjava.api.model.Capability;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.InternetProtocol;
import org.mockserver.client.MockServerClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;
import java.util.Map;

/**
 * An enriched Testcontainers module for MockServer.
 * <p>
 * Unlike the upstream {@code org.testcontainers:mockserver} module, this module:
 * <ul>
 *   <li>Derives the Docker image tag from the client jar version so it stays in lockstep</li>
 *   <li>Supports DNS, transparent proxy, HTTP/3, initialization JSON, and other configuration helpers</li>
 *   <li>Provides direct {@link MockServerClient} wiring via {@link #getClient()}</li>
 * </ul>
 */
public class MockServerContainer extends GenericContainer<MockServerContainer> {

    private static final Logger LOG = LoggerFactory.getLogger(MockServerContainer.class);

    /**
     * Default MockServer port (HTTP, HTTPS, SOCKS, and HTTP CONNECT are all served on a single unified port).
     */
    public static final int PORT = 1080;

    /** Ceiling for the readiness wait; see the rationale in the constructor. */
    private static final Duration STARTUP_TIMEOUT = Duration.ofMinutes(3);

    private static final String IMAGE_NAME = "mockserver/mockserver";
    private static final DockerImageName DEFAULT_IMAGE = resolveDefaultImage();

    private int serverPort = PORT;
    private MockServerClient client;

    /**
     * Creates a MockServerContainer with the default image tag derived from the client jar version.
     */
    public MockServerContainer() {
        this(DEFAULT_IMAGE);
    }

    /**
     * Creates a MockServerContainer with a custom Docker image.
     *
     * @param dockerImageName the Docker image to use (must be compatible with {@code mockserver/mockserver})
     */
    public MockServerContainer(DockerImageName dockerImageName) {
        super(dockerImageName);
        dockerImageName.assertCompatibleWith(DockerImageName.parse(IMAGE_NAME));
        addExposedPort(PORT);
        // Wait until MockServer is actually SERVING, not merely until the port is bound.
        //
        // A listening-port wait is satisfied the instant the mapped host port accepts a TCP
        // connection. Under Docker that happens the moment the container is created (the userland
        // proxy binds the mapped port immediately), and MockServer's own Netty listener also binds
        // early — but it ACCEPTS-THEN-RESETS connections until initialisation completes. So a
        // listening-port wait returns while the server is still resetting requests: measured
        // against mockserver/mockserver:mockserver-7.6.0, the port accepts TCP ~0.06s after start
        // yet PUT requests are reset for a further ~0.2–0.3s before the first 200. start() would
        // return inside that window and a request issued immediately after would be reset
        // (surfacing as "Channel handler removed before valid response has been received").
        //
        // /mockserver/status only answers PUT — but Wait.forHttp(...).withMethod("PUT") is fully
        // supported, and a 200 from it means the request pipeline is fully initialised and serving,
        // which is exactly the readiness signal we want.
        //
        // The startup timeout is set explicitly rather than left at the framework default of 60s.
        // Measured on a developer machine with every core saturated, this test took 51.9s and 55.8s
        // end-to-end versus ~4s idle — a ~14x slowdown that leaves the 60s default uncomfortably
        // close, and CI agents are exactly the loaded hosts where that matters. A longer ceiling is
        // close to free because the wait returns as soon as the probe answers, so it only bounds the
        // pathological case (a cold image pull on a contended host) instead of turning it into a
        // confusing timeout.
        waitForServing(PORT);
        // Set the default server port env var so the Docker entrypoint picks it up
        withEnv("SERVER_PORT", String.valueOf(PORT));
    }

    /**
     * <p>Note this waits for MockServer to be SERVING, not for expectation seeding to finish. A
     * container configured with {@link #withInitializationJson(String)} answers {@code /status}
     * before its seeded expectations exist ({@code /ready} is the endpoint gated on seeding), so a
     * caller that depends on seeded data may still need to wait for it. {@code /status} is used
     * deliberately: it exists in every MockServer version, whereas {@code /ready} was only added in
     * 7.2.0, and this container accepts arbitrary custom images.</p>
     *
     * Installs an HTTP readiness wait against {@code PUT /mockserver/status} on the given internal
     * container port. Used at construction time for the default port and re-installed by
     * {@link #withServerPort(int)} when the server port (and hence the exposed port) changes, so the
     * probe always targets the port MockServer is actually listening on.
     */
    private void waitForServing(int internalPort) {
        waitingFor(
            Wait.forHttp("/mockserver/status")
                .forPort(internalPort)
                .withMethod("PUT")
                .forStatusCode(200)
                .withStartupTimeout(STARTUP_TIMEOUT)
        );
    }

    /**
     * Returns the mapped server port on the host.
     *
     * @return the host port mapped to the MockServer container port
     */
    public int getServerPort() {
        return getMappedPort(serverPort);
    }

    /**
     * Returns the HTTP endpoint URL for the MockServer container.
     *
     * @return the HTTP endpoint in the form {@code http://host:port}
     */
    public String getEndpoint() {
        return "http://" + getHost() + ":" + getServerPort();
    }

    /**
     * Returns the HTTPS endpoint URL for the MockServer container.
     * MockServer serves HTTP and HTTPS on the same unified port.
     *
     * @return the HTTPS endpoint in the form {@code https://host:port}
     */
    public String getSecureEndpoint() {
        return "https://" + getHost() + ":" + getServerPort();
    }

    /**
     * Returns a {@link MockServerClient} connected to this container. The client is created lazily
     * on first call and cached; it is closed automatically when the container stops.
     *
     * @return a MockServerClient connected to the running container
     */
    public synchronized MockServerClient getClient() {
        if (client == null) {
            client = new MockServerClient(getHost(), getServerPort());
        }
        return client;
    }

    /**
     * Stops the cached client (if any) and the container. The cached client is stopped gracefully,
     * which sends {@code PUT /mockserver/stop} and waits up to 10 seconds for confirmation before the
     * container itself is stopped.
     */
    @Override
    public synchronized void close() {
        if (client != null) {
            try {
                client.stop();
            } catch (Exception e) {
                LOG.debug("Exception closing cached MockServerClient", e);
            } finally {
                client = null;
            }
        }
        super.close();
    }

    /**
     * Sets a custom server port. This changes the {@code SERVER_PORT} env var, replaces the exposed
     * port, and re-targets the HTTP readiness wait at the new port so it probes the port MockServer
     * is actually listening on.
     *
     * @param port the port MockServer should listen on inside the container
     * @return this container instance for chaining
     */
    public MockServerContainer withServerPort(int port) {
        this.serverPort = port;
        // Replace the exposed ports rather than appending — leaving the previous (default) port
        // exposed would leave a mapped port MockServer never binds. withExposedPorts() replaces the
        // backing list (getExposedPorts() returns a copy, so clearing it has no effect).
        withExposedPorts(port);
        // Re-install the readiness wait against the new internal port; the constructor bound it to
        // the default PORT, which is no longer exposed once it has been replaced above.
        waitForServing(port);
        withEnv("SERVER_PORT", String.valueOf(port));
        return self();
    }

    /**
     * Enables DNS resolution on the specified UDP port.
     * <p>
     * Sets {@code MOCKSERVER_DNS_ENABLED=true} and {@code MOCKSERVER_DNS_PORT} to the given port.
     * The DNS port is exposed as UDP.
     *
     * @param dnsPort the UDP port for DNS resolution
     * @return this container instance for chaining
     */
    public MockServerContainer withDnsPort(int dnsPort) {
        withEnv("MOCKSERVER_DNS_ENABLED", "true");
        withEnv("MOCKSERVER_DNS_PORT", String.valueOf(dnsPort));
        exposeUdpPort(dnsPort);
        return self();
    }

    /**
     * Enables transparent proxy mode and adds the {@code NET_ADMIN} Linux capability.
     * <p>
     * Note: iptables/redirect rules are the operator's responsibility; this helper only
     * enables the mode and grants the required capability.
     *
     * @return this container instance for chaining
     */
    public MockServerContainer withTransparentProxy() {
        withEnv("MOCKSERVER_TRANSPARENT_PROXY_ENABLED", "true");
        withCreateContainerCmdModifier(cmd ->
            cmd.getHostConfig().withCapAdd(Capability.NET_ADMIN)
        );
        return self();
    }

    /**
     * Sets multiple MockServer properties as environment variables.
     * <p>
     * Keys must be in the MockServer environment variable form (e.g. {@code MOCKSERVER_LOG_LEVEL}).
     *
     * @param properties a map of MockServer env-var keys to their values
     * @return this container instance for chaining
     */
    public MockServerContainer withProperties(Map<String, String> properties) {
        properties.forEach(this::withEnv);
        return self();
    }

    /**
     * Sets a single MockServer property as an environment variable.
     * <p>
     * The key must be in the MockServer environment variable form (e.g. {@code MOCKSERVER_LOG_LEVEL}).
     *
     * @param key   the MockServer env-var key
     * @param value the value to set
     * @return this container instance for chaining
     */
    public MockServerContainer withProperty(String key, String value) {
        withEnv(key, value);
        return self();
    }

    /**
     * Copies an initialization JSON file into the container and configures MockServer to load its
     * expectations at startup.
     * <p>
     * This is one-shot startup loading (MockServer's {@code initializationJsonPath}), not ongoing
     * persistence of expectations across restarts (which is the separate {@code persistExpectations}
     * setting). The file is copied to {@code /config/initializerJson.json} and
     * {@code MOCKSERVER_INITIALIZATION_JSON_PATH} is pointed at it.
     *
     * @param hostInitJsonPath the path on the host to the initialization JSON file
     * @return this container instance for chaining
     */
    public MockServerContainer withInitializationJson(String hostInitJsonPath) {
        withCopyFileToContainer(
            MountableFile.forHostPath(hostInitJsonPath),
            "/config/initializerJson.json"
        );
        withEnv("MOCKSERVER_INITIALIZATION_JSON_PATH", "/config/initializerJson.json");
        return self();
    }

    /**
     * Sets the MockServer log level.
     *
     * @param level the log level (e.g. "INFO", "DEBUG", "WARN", "ERROR", "TRACE")
     * @return this container instance for chaining
     */
    public MockServerContainer withLogLevel(String level) {
        withEnv("MOCKSERVER_LOG_LEVEL", level);
        return self();
    }

    /**
     * Enables experimental HTTP/3 (QUIC) support on the specified UDP port.
     * <p>
     * Sets {@code MOCKSERVER_HTTP3_PORT} and exposes the port as UDP.
     *
     * @param udpPort the UDP port for HTTP/3
     * @return this container instance for chaining
     */
    public MockServerContainer withHttp3(int udpPort) {
        withEnv("MOCKSERVER_HTTP3_PORT", String.valueOf(udpPort));
        exposeUdpPort(udpPort);
        return self();
    }

    /**
     * Exposes an additional container port over UDP. {@link #addExposedPort(Integer)} only handles
     * TCP, so UDP ports are added via a create-container command modifier.
     */
    private void exposeUdpPort(int port) {
        withCreateContainerCmdModifier(cmd -> {
            ExposedPort udpPort = new ExposedPort(port, InternetProtocol.UDP);
            ExposedPort[] existing = cmd.getExposedPorts();
            ExposedPort[] updated;
            if (existing != null) {
                updated = new ExposedPort[existing.length + 1];
                System.arraycopy(existing, 0, updated, 0, existing.length);
                updated[existing.length] = udpPort;
            } else {
                updated = new ExposedPort[]{udpPort};
            }
            cmd.withExposedPorts(updated);
        });
    }

    /**
     * Resolves the default Docker image name from the client jar's Implementation-Version so the
     * container image stays in lockstep with the client library.
     * <p>
     * When the version cannot be resolved (typically when running from an unpackaged classes
     * directory, e.g. in an IDE or on a reactor test classpath without a built jar manifest), this
     * falls back to {@code mockserver/mockserver:latest} and logs a warning. {@code :latest} is a
     * mutable tag that may not match the client on the classpath — for reproducible builds, pass an
     * explicit pinned image to {@link #MockServerContainer(DockerImageName)}.
     */
    static DockerImageName resolveDefaultImage() {
        String version = MockServerClient.class.getPackage().getImplementationVersion();
        if (version != null && !version.isEmpty()) {
            return DockerImageName.parse(IMAGE_NAME + ":mockserver-" + version);
        }
        LOG.warn("Could not resolve MockServer client version from the jar manifest; falling back to " +
            "'{}:latest'. This mutable tag may not match the client library — pass an explicit pinned " +
            "image to the MockServerContainer(DockerImageName) constructor for reproducible builds.", IMAGE_NAME);
        return DockerImageName.parse(IMAGE_NAME + ":latest");
    }

    /**
     * Returns the default resolved image with {@code -aot} appended to its tag, selecting the
     * experimental JDK 25 AOT-cache image variant.
     * <p>
     * This variant bakes a Project Leyden ahead-of-time cache (JEP 483/514) into the image via a
     * training run at build time, roughly halving container time-to-ready versus the standard image
     * while remaining the real HotSpot JVM (100% feature parity — unlike GraalVM native-image). The
     * {@code -aot} tags are published as an opt-in variant (like {@code -graaljs}) to Docker Hub and
     * ECR Public from the next release.
     * <p>
     * Usage:
     * <pre>{@code
     * try (MockServerContainer container = new MockServerContainer(MockServerContainer.aotImage())) {
     *     container.start();
     *     // ...
     * }
     * }</pre>
     * The returned image uses the same tag as the default image (resolved once at class-load time
     * by {@link #resolveDefaultImage()}), so it stays in lockstep with the client jar version
     * (or {@code latest-aot} when the version cannot be resolved).
     *
     * @return the default resolved image with {@code -aot} appended to its tag, compatible with
     * {@code mockserver/mockserver}
     */
    public static DockerImageName aotImage() {
        return DockerImageName.parse(IMAGE_NAME + ":" + DEFAULT_IMAGE.getVersionPart() + "-aot")
            .asCompatibleSubstituteFor(IMAGE_NAME);
    }
}

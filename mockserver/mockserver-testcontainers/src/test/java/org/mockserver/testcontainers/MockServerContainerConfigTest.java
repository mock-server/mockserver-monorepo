package org.mockserver.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.Map;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;

/**
 * Unit tests for {@link MockServerContainer} configuration.
 * These tests verify container configuration WITHOUT starting Docker.
 * Testcontainers builds config lazily — exposed ports, env vars, and image name
 * are all introspectable pre-start.
 */
class MockServerContainerConfigTest {

    @Test
    void defaultImageMatchesClientVersionOrFallback() {
        DockerImageName image = MockServerContainer.resolveDefaultImage();
        String fullImage = image.asCanonicalNameString();
        // In test classpath without a packaged jar, Implementation-Version is null,
        // so we expect the :latest fallback. If running against a packaged jar,
        // we'd expect :mockserver-<version>.
        String version = org.mockserver.client.MockServerClient.class.getPackage().getImplementationVersion();
        if (version != null && !version.isEmpty()) {
            assertThat(fullImage, containsString("mockserver-" + version));
        } else {
            assertThat(fullImage, containsString(":latest"));
        }
        assertThat(fullImage, startsWith("mockserver/mockserver:"));
    }

    @Test
    void defaultContainerExposesPort1080() {
        try (MockServerContainer container = new MockServerContainer()) {
            List<Integer> exposedPorts = container.getExposedPorts();
            assertThat(exposedPorts, hasItem(MockServerContainer.PORT));
            assertThat(MockServerContainer.PORT, is(1080));
        }
    }

    @Test
    void defaultContainerSetsServerPortEnv() {
        try (MockServerContainer container = new MockServerContainer()) {
            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("SERVER_PORT", "1080"));
        }
    }

    @Test
    void withServerPortSetsEnvAndExposesPort() {
        try (MockServerContainer container = new MockServerContainer()) {
            container.withServerPort(9090);

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("SERVER_PORT", "9090"));

            List<Integer> exposedPorts = container.getExposedPorts();
            assertThat(exposedPorts, hasItem(9090));
            // The default port must be replaced, not appended — leaving it exposed would map a
            // host port MockServer never binds to. withServerPort() also re-targets the HTTP
            // readiness wait at the new port.
            assertThat(exposedPorts, not(hasItem(MockServerContainer.PORT)));
        }
    }

    @Test
    void withDnsPortSetsDnsEnvVars() {
        try (MockServerContainer container = new MockServerContainer()) {
            container.withDnsPort(53);

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_DNS_ENABLED", "true"));
            assertThat(env, hasEntry("MOCKSERVER_DNS_PORT", "53"));
        }
    }

    @Test
    void withTransparentProxySetsEnv() {
        try (MockServerContainer container = new MockServerContainer()) {
            container.withTransparentProxy();

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_TRANSPARENT_PROXY_ENABLED", "true"));
        }
    }

    @Test
    void withPropertySetsEnv() {
        try (MockServerContainer container = new MockServerContainer()) {
            container.withProperty("MOCKSERVER_MAX_EXPECTATIONS", "500");

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_MAX_EXPECTATIONS", "500"));
        }
    }

    @Test
    void withPropertiesSetsMultipleEnvVars() {
        try (MockServerContainer container = new MockServerContainer()) {
            container.withProperties(Map.of(
                "MOCKSERVER_MAX_EXPECTATIONS", "500",
                "MOCKSERVER_LOG_LEVEL", "DEBUG"
            ));

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_MAX_EXPECTATIONS", "500"));
            assertThat(env, hasEntry("MOCKSERVER_LOG_LEVEL", "DEBUG"));
        }
    }

    @Test
    void withLogLevelSetsEnv() {
        try (MockServerContainer container = new MockServerContainer()) {
            container.withLogLevel("WARN");

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_LOG_LEVEL", "WARN"));
        }
    }

    @Test
    void withInitializationJsonSetsInitializationJsonPathEnv() {
        try (MockServerContainer container = new MockServerContainer()) {
            // Use a path that doesn't need to exist since we're not starting the container
            container.withInitializationJson("/tmp/init.json");

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_INITIALIZATION_JSON_PATH", "/config/initializerJson.json"));
        }
    }

    @Test
    void withHttp3SetsEnv() {
        try (MockServerContainer container = new MockServerContainer()) {
            container.withHttp3(8443);

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_HTTP3_PORT", "8443"));
        }
    }

    @Test
    void fluentChainingWorksAcrossMultipleHelpers() {
        try (MockServerContainer container = new MockServerContainer()) {
            MockServerContainer result = container
                .withLogLevel("DEBUG")
                .withServerPort(9090)
                .withProperty("MOCKSERVER_MAX_EXPECTATIONS", "100");

            assertThat(result, is(sameInstance(container)));

            Map<String, String> env = container.getEnvMap();
            assertThat(env, hasEntry("MOCKSERVER_LOG_LEVEL", "DEBUG"));
            assertThat(env, hasEntry("SERVER_PORT", "9090"));
            assertThat(env, hasEntry("MOCKSERVER_MAX_EXPECTATIONS", "100"));
        }
    }

    @Test
    void customImageIsAccepted() {
        // Verify that a compatible substitute image can be constructed without throwing.
        // We cannot call getDockerImageName() without Docker, so we just verify construction succeeds.
        DockerImageName custom = DockerImageName.parse("my-registry/mockserver:custom")
            .asCompatibleSubstituteFor("mockserver/mockserver");
        try (MockServerContainer container = new MockServerContainer(custom)) {
            // If we reach here, the image was accepted as a compatible substitute.
            // Verify the container is configured with the default port.
            assertThat(container.getExposedPorts(), hasItem(MockServerContainer.PORT));
        }
    }

    @Test
    void aotImageHasAotSuffixAndIsCompatible() {
        DockerImageName aot = MockServerContainer.aotImage();
        // The tag must select the -aot variant.
        assertThat(aot.getVersionPart(), endsWith("-aot"));
        // It must be recognised as a compatible substitute for the base image (so the
        // MockServerContainer(DockerImageName) constructor's assertCompatibleWith passes). We do
        // NOT start a container here — the -aot tag is not published until the next release.
        aot.assertCompatibleWith(DockerImageName.parse("mockserver/mockserver"));
        assertThat(aot.asCanonicalNameString(), startsWith("mockserver/mockserver:"));
    }

    @Test
    void aotImageTagDerivesFromDefaultImage() {
        // aotImage() is just the default resolved image tag with -aot appended.
        String defaultTag = MockServerContainer.resolveDefaultImage().getVersionPart();
        assertThat(MockServerContainer.aotImage().getVersionPart(), is(defaultTag + "-aot"));
    }

    @Test
    void incompatibleImageIsRejected() {
        DockerImageName incompatible = DockerImageName.parse("postgres:15");
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> new MockServerContainer(incompatible)
        );
    }
}

package org.mockserver.testcontainers;

import org.testcontainers.utility.DockerImageName;

/**
 * Single source of truth for the pinned MockServer Docker image used by the tests in this module.
 * <p>
 * The tag is pinned to an explicit released version rather than the mutable {@code :latest} tag so
 * the integration test's outcome does not depend on whatever was last pushed to Docker Hub (see the
 * rationale on {@link MockServerContainerIntegrationTest}). It MUST be bumped on every release in
 * lockstep with the project version — {@link PinnedImageVersionTest} enforces that (the pin must
 * have the project's major.minor, or be one minor behind while a release is in flight), so a stale
 * pin fails a cheap unit test in seconds instead of a confusing client-vs-server version exception
 * 10s into a container start. The release tooling ({@code scripts/release/update-version-references.sh}) also
 * rewrites the tag on this line automatically, so both a human miss and an automation miss are
 * caught.
 */
final class TestcontainersImages {

    /**
     * The pinned {@code mockserver/mockserver:mockserver-<version>} image. Keep this on one line in
     * the {@code mockserver/mockserver:mockserver-<version>} form the release script's targeted
     * rewrite matches.
     */
    static final String PINNED_MOCKSERVER_IMAGE = "mockserver/mockserver:mockserver-9.2.0";

    /**
     * System property naming an image to run instead of the pin. CI sets it on any non-SNAPSHOT
     * (release-window) commit, whose version may not be published as an image yet, to an image
     * built from the same commit (see
     * {@code .buildkite/scripts/steps/java-testcontainers-test.sh}).
     */
    static final String IMAGE_UNDER_TEST_PROPERTY = "testcontainers.mockserver.image";

    private static final String MOCKSERVER_IMAGE_NAME = "mockserver/mockserver";

    private TestcontainersImages() {
    }

    static DockerImageName imageUnderTest() {
        return imageUnderTest(System.getProperty(IMAGE_UNDER_TEST_PROPERTY));
    }

    /**
     * The pinned image, or {@code override} when it is non-blank. A locally built override (for
     * example {@code mockserver-under-test:local}) is declared a substitute for
     * {@code mockserver/mockserver} so {@link MockServerContainer} accepts it.
     */
    static DockerImageName imageUnderTest(String override) {
        if (override == null || override.isBlank()) {
            return DockerImageName.parse(PINNED_MOCKSERVER_IMAGE);
        }
        return DockerImageName.parse(override.trim()).asCompatibleSubstituteFor(MOCKSERVER_IMAGE_NAME);
    }
}

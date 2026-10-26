package org.mockserver.testcontainers;

/**
 * Single source of truth for the pinned MockServer Docker image used by the tests in this module.
 * <p>
 * The tag is pinned to an explicit released version rather than the mutable {@code :latest} tag so
 * the integration test's outcome does not depend on whatever was last pushed to Docker Hub (see the
 * rationale on {@link MockServerContainerIntegrationTest}). It MUST be bumped on every release in
 * lockstep with the project version — {@link PinnedImageVersionTest} enforces exactly that
 * (major.minor of this pin must equal major.minor of the project version), so a stale pin fails a
 * cheap unit test in seconds instead of a confusing client-vs-server version exception 10s into a
 * container start. The release tooling ({@code scripts/release/update-version-references.sh}) also
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

    private TestcontainersImages() {
    }
}

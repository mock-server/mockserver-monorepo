package org.mockserver.testcontainers;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.mockserver.client.MockServerClient;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.*;
import static org.mockserver.model.HttpRequest.request;
import static org.mockserver.model.HttpResponse.response;

/**
 * Integration tests for {@link MockServerContainer} that require a running Docker daemon.
 * <p>
 * The class is named {@code *IntegrationTest} so the inherited Failsafe plugin (configured for
 * {@code **}{@code /*IntegrationTest.java}) actually collects and runs it. An earlier revision named
 * it {@code *IT}, which matched neither Surefire ({@code **}{@code /*Test.java}) nor Failsafe, so it
 * silently ran nowhere.
 * <p>
 * The Docker gate goes through {@link org.mockserver.test.DockerAvailability} rather than calling
 * {@code DockerClientFactory.instance().isDockerAvailable()} directly, because the raw probe throws
 * rather than returning {@code false} for anything other than {@code IllegalStateException}.
 */
class MockServerContainerIntegrationTest {

    @Test
    void containerStartsAndAcceptsMockExpectation() throws Exception {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning false
        // for post-connection failures (e.g. Ryuk rejected by a user-namespace remapped
        // daemon), which would turn this skip into a hard ERROR.
        Assumptions.assumeTrue(
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable()),
            "Docker is not available — skipping integration test"
        );

        try (MockServerContainer container = new MockServerContainer(
            // Pin an explicit released image rather than the mutable :latest tag. A :latest pin
            // makes this test's outcome depend on whatever was last pushed to Docker Hub, so it can
            // go red with no change in this repo, and it bakes in an unbounded client-vs-server skew
            // (an arbitrary SNAPSHOT client on the classpath against an arbitrary server). The pin
            // is the latest released version — the client here is one patch ahead (this repo is on
            // <released>.<n+1>-SNAPSHOT) so their major.minor match, which is all MockServerClient
            // requires. It uses the exact tag format MockServerContainer.resolveDefaultImage()
            // derives for a real client release (mockserver/mockserver:mockserver-<version>), so it
            // stays self-consistent as the project moves forward. The pin lives in one place
            // (TestcontainersImages.PINNED_MOCKSERVER_IMAGE); it is bumped on each release in
            // lockstep with the project version — automatically by the release tooling and enforced
            // by PinnedImageVersionTest (same major.minor, or one minor behind mid-release) so a
            // stale pin fails fast instead of a client-vs-server version exception 10s into
            // container start. On a release-window commit, before that version's image exists, CI
            // overrides the pin with an image built from the same commit
            // (TestcontainersImages.IMAGE_UNDER_TEST_PROPERTY).
            TestcontainersImages.imageUnderTest()
        )) {
            container.start();

            // Create an expectation via the client
            MockServerClient client = container.getClient();
            client.when(
                request().withMethod("GET").withPath("/hello")
            ).respond(
                response().withStatusCode(200).withBody("world")
            );

            // Issue a matching HTTP request
            HttpClient httpClient = HttpClient.newHttpClient();
            HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(container.getEndpoint() + "/hello"))
                .GET()
                .build();

            HttpResponse<String> httpResponse = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

            assertThat(httpResponse.statusCode(), is(200));
            assertThat(httpResponse.body(), is("world"));

            // Verify endpoint format
            assertThat(container.getEndpoint(), startsWith("http://"));
            assertThat(container.getSecureEndpoint(), startsWith("https://"));
        }
    }
}

package org.mockserver.testcontainers;

import org.junit.jupiter.api.Test;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Docker-free checks of how {@link TestcontainersImages} picks the image the integration test runs.
 */
class TestcontainersImagesTest {

    @Test
    void usesPinnedImageWhenNoOverrideIsGiven() {
        assertThat(TestcontainersImages.imageUnderTest(null).asCanonicalNameString(),
            is(TestcontainersImages.PINNED_MOCKSERVER_IMAGE));
        assertThat(TestcontainersImages.imageUnderTest("  ").asCanonicalNameString(),
            is(TestcontainersImages.PINNED_MOCKSERVER_IMAGE));
    }

    @Test
    void usesOverrideImageWhenGiven() {
        DockerImageName image = TestcontainersImages.imageUnderTest(" mockserver-under-test:local ");

        assertThat(image.asCanonicalNameString(), is("mockserver-under-test:local"));
        assertThat(image.isCompatibleWith(DockerImageName.parse("mockserver/mockserver")), is(true));
    }

    @Test
    void containerAcceptsLocallyBuiltOverrideImage() {
        DockerImageName image = TestcontainersImages.imageUnderTest("mockserver-under-test:local");

        // The constructor rejects an image not compatible with mockserver/mockserver; resolving the
        // image name would pull it, so this only constructs the container.
        try (MockServerContainer container = new MockServerContainer(image)) {
            assertThat(container.getExposedPorts().contains(MockServerContainer.PORT), is(true));
        }
    }
}

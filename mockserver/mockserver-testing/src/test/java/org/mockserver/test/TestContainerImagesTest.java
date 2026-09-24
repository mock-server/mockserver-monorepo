package org.mockserver.test;

import org.junit.Test;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

/**
 * Locks the public-&gt;ECR pull-through image-name transform. This is a pure,
 * Docker-free unit test that guards the contract shared with
 * {@code resolve_test_image} in {@code test-images.sh} and the
 * {@code ecr_repository_prefix} values in {@code ecr-pull-through-cache.tf}: if
 * any of the three prefixes or the {@code library/} rule changes on one side
 * without the others, this test (or its shell mirror) fails.
 */
public class TestContainerImagesTest {

    private static final String REGISTRY = "123456789012.dkr.ecr.eu-west-2.amazonaws.com";

    @Test
    public void rewritesQuayImageStrippingHost() {
        assertThat(
            TestContainerImages.toEcr("quay.io/minio/minio:RELEASE.2024-11-07T00-52-20Z", REGISTRY),
            is(REGISTRY + "/quay/minio/minio:RELEASE.2024-11-07T00-52-20Z"));
    }

    @Test
    public void passesThroughMcrImageUnchangedBecauseEcrCannotCacheMcr() {
        // mcr.microsoft.com is NOT a supported ECR pull-through upstream
        // (UnsupportedUpstreamRegistryException on rule creation), so even with a
        // cache registry configured azurite must keep its public name and pull
        // direct from Microsoft. This is a deliberate passthrough, not a rewrite.
        assertThat(
            TestContainerImages.toEcr("mcr.microsoft.com/azure-storage/azurite:3.36.0", REGISTRY),
            is("mcr.microsoft.com/azure-storage/azurite:3.36.0"));
    }

    @Test
    public void rewritesDockerHubNamespacedImage() {
        assertThat(
            TestContainerImages.toEcr("fsouza/fake-gcs-server:1.49.3", REGISTRY),
            is(REGISTRY + "/docker-hub/fsouza/fake-gcs-server:1.49.3"));
        assertThat(
            TestContainerImages.toEcr("confluentinc/cp-kafka:7.6.1", REGISTRY),
            is(REGISTRY + "/docker-hub/confluentinc/cp-kafka:7.6.1"));
        assertThat(
            TestContainerImages.toEcr("adobe/s3mock:5.2.3", REGISTRY),
            is(REGISTRY + "/docker-hub/adobe/s3mock:5.2.3"));
    }

    @Test
    public void insertsLibraryForDockerHubOfficialImage() {
        assertThat(
            TestContainerImages.toEcr("rabbitmq:3.13-management", REGISTRY),
            is(REGISTRY + "/docker-hub/library/rabbitmq:3.13-management"));
        assertThat(
            TestContainerImages.toEcr("eclipse-mosquitto:2.0.22", REGISTRY),
            is(REGISTRY + "/docker-hub/library/eclipse-mosquitto:2.0.22"));
    }

    @Test
    public void preservesImageWithoutTag() {
        assertThat(
            TestContainerImages.toEcr("rabbitmq", REGISTRY),
            is(REGISTRY + "/docker-hub/library/rabbitmq"));
        assertThat(
            TestContainerImages.toEcr("quay.io/minio/minio", REGISTRY),
            is(REGISTRY + "/quay/minio/minio"));
    }

    @Test
    public void rejectsUnmappedRegistryHost() {
        assertThrows(IllegalArgumentException.class,
            () -> TestContainerImages.toEcr("gcr.io/distroless/base:latest", REGISTRY));
    }

    @Test
    public void resolvesEveryKeyInThePropertiesFileToItsPublicName() {
        // No registry configured in a plain unit-test JVM, so resolve() returns
        // the public reference verbatim and never NPEs on a missing key.
        assertThat(TestContainerImages.S3MOCK, is("adobe/s3mock:5.2.3"));
        assertThat(TestContainerImages.FAKE_GCS_SERVER, is("fsouza/fake-gcs-server:1.49.3"));
        assertThat(TestContainerImages.AZURITE, is("mcr.microsoft.com/azure-storage/azurite:3.36.0"));
        assertThat(TestContainerImages.CP_KAFKA, is("confluentinc/cp-kafka:7.6.1"));
        assertThat(TestContainerImages.RABBITMQ, is("rabbitmq:3.13-management"));
        assertThat(TestContainerImages.ECLIPSE_MOSQUITTO, is("eclipse-mosquitto:2.0.22"));
    }

    @Test
    public void publicRepositoryStripsTheTag() {
        assertThat(TestContainerImages.publicRepository("cp-kafka"), is("confluentinc/cp-kafka"));
        assertThat(TestContainerImages.publicRepository("rabbitmq"), is("rabbitmq"));
    }
}

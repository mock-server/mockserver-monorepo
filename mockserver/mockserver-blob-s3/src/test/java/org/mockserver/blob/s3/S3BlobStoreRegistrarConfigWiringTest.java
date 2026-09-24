package org.mockserver.blob.s3;

import org.junit.Test;
import org.mockserver.configuration.Configuration;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3ServiceClientConfiguration;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.Optional;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

/**
 * Behavioural unit tests for {@link S3BlobStoreRegistrar#createS3BlobStore(Configuration)} —
 * the config-property to S3-client/store wiring.
 * <p>
 * No network, no Docker, no S3 emulator: {@link S3Client#builder()} performs no I/O at
 * build time, so the resulting client's observable configuration
 * ({@link S3Client#serviceClientConfiguration()}) and the store's own fields can be asserted
 * directly. This is the layer the {@code S3BlobStoreContractTest} deliberately bypasses by
 * hand-building a client, so a mis-wired property (wrong default region, ignored endpoint,
 * dropped keyPrefix, unused credentials) is otherwise invisible.
 */
public class S3BlobStoreRegistrarConfigWiringTest {

    private static S3ServiceClientConfiguration clientConfig(S3BlobStore store) {
        return s3Client(store).serviceClientConfiguration();
    }

    private static S3Client s3Client(S3BlobStore store) {
        return (S3Client) readField(store, "s3Client");
    }

    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError("could not read field '" + name + "' from " + target.getClass(), e);
        }
    }

    @Test
    public void shouldThrowWhenBucketMissing() {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
            () -> S3BlobStoreRegistrar.createS3BlobStore(new Configuration()));
        assertThat(ex.getMessage(), is("blobStoreType=s3 requires blobStoreBucket to be configured"));
    }

    @Test
    public void shouldThrowWhenBucketEmpty() {
        assertThrows(IllegalStateException.class,
            () -> S3BlobStoreRegistrar.createS3BlobStore(new Configuration().blobStoreBucket("")));
    }

    @Test
    public void shouldDefaultRegionToUsEast1WhenUnset() {
        // DEFAULT-path assertion: with no region configured the factory must fall back to
        // us-east-1 rather than leaving the client region unset or picking something else.
        try (S3BlobStore store = S3BlobStoreRegistrar.createS3BlobStore(
            new Configuration().blobStoreBucket("some-bucket"))) {
            assertEquals(Region.US_EAST_1, clientConfig(store).region());
        }
    }

    @Test
    public void shouldUseExplicitRegion() {
        try (S3BlobStore store = S3BlobStoreRegistrar.createS3BlobStore(
            new Configuration().blobStoreBucket("some-bucket").blobStoreRegion("eu-west-2"))) {
            assertEquals(Region.of("eu-west-2"), clientConfig(store).region());
        }
    }

    @Test
    public void shouldApplyEndpointOverrideWhenConfigured() {
        try (S3BlobStore store = S3BlobStoreRegistrar.createS3BlobStore(
            new Configuration().blobStoreBucket("some-bucket").blobStoreEndpoint("http://s3.local:9000"))) {
            Optional<URI> endpoint = clientConfig(store).endpointOverride();
            assertTrue("endpoint override should be present when blobStoreEndpoint is configured",
                endpoint.isPresent());
            assertEquals(URI.create("http://s3.local:9000"), endpoint.get());
        }
    }

    @Test
    public void shouldNotOverrideEndpointWhenUnset() {
        // DEFAULT-path assertion: guards the endpoint branch so it is only taken when configured.
        try (S3BlobStore store = S3BlobStoreRegistrar.createS3BlobStore(
            new Configuration().blobStoreBucket("some-bucket"))) {
            assertThat("no endpoint override expected when blobStoreEndpoint is unset",
                clientConfig(store).endpointOverride().isPresent(), is(false));
        }
    }

    @Test
    public void shouldUseStaticCredentialsWhenProvided() {
        try (S3BlobStore store = S3BlobStoreRegistrar.createS3BlobStore(
            new Configuration()
                .blobStoreBucket("some-bucket")
                .blobStoreAccessKeyId("AKIAEXAMPLE")
                .blobStoreSecretAccessKey("secretExample"))) {
            var provider = clientConfig(store).credentialsProvider();
            assertThat(provider, instanceOf(StaticCredentialsProvider.class));
            // StaticCredentialsProvider.resolveCredentials() is purely in-memory — no network.
            AwsCredentials resolved = ((StaticCredentialsProvider) provider).resolveCredentials();
            assertThat(resolved, instanceOf(AwsBasicCredentials.class));
            assertEquals("AKIAEXAMPLE", resolved.accessKeyId());
            assertEquals("secretExample", resolved.secretAccessKey());
        }
    }

    @Test
    public void shouldFallBackToDefaultCredentialsWhenUnset() {
        // DEFAULT-path assertion: with no static credentials the factory must use the AWS
        // default credential chain, not the static provider. Do NOT resolve() here — the
        // default chain reaches out to the environment/filesystem/network.
        try (S3BlobStore store = S3BlobStoreRegistrar.createS3BlobStore(
            new Configuration().blobStoreBucket("some-bucket"))) {
            assertThat(clientConfig(store).credentialsProvider(),
                instanceOf(DefaultCredentialsProvider.class));
        }
    }

    @Test
    public void shouldApplyBucketAndKeyPrefix() {
        try (S3BlobStore store = S3BlobStoreRegistrar.createS3BlobStore(
            new Configuration()
                .blobStoreBucket("my-bucket")
                .blobStoreKeyPrefix("mockserver/prefix/"))) {
            assertEquals("my-bucket", readField(store, "bucket"));
            assertEquals("mockserver/prefix/", readField(store, "keyPrefix"));
        }
    }
}

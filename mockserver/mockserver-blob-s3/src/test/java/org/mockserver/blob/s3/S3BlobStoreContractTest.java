package org.mockserver.blob.s3;

import org.junit.AfterClass;
import org.junit.Assume;
import org.junit.BeforeClass;
import org.junit.Test;
import org.mockserver.state.Blob;
import org.mockserver.state.BlobStore;
import org.mockserver.state.contract.BlobStoreContract;
import org.mockserver.test.DockerAvailability;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.HttpWaitStrategy;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.Optional;
import java.util.UUID;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * Runs the shared {@link BlobStoreContract} against a real MinIO
 * instance via Testcontainers. Docker-gated: skips if Docker is
 * not available.
 */
public class S3BlobStoreContractTest extends BlobStoreContract {

    // quay.io, not Docker Hub: minio/minio is no longer pullable from Docker Hub (the registry
    // returns 401 for every tag, and `docker pull` reports "pull access denied ... repository does
    // not exist"), which broke this suite on master with ContainerFetchException. quay.io is MinIO's
    // other official registry and serves this exact tag - same manifest digest
    // sha256:ac591851803a79aee64bc37f66d77c56b0a4b6e12d9e5356380f4105510f2332 - so the image under
    // test is unchanged.
    private static final String MINIO_IMAGE = "quay.io/minio/minio:RELEASE.2024-11-07T00-52-20Z";
    private static final String ACCESS_KEY = "minioadmin";
    private static final String SECRET_KEY = "minioadmin";
    private static final String TEST_BUCKET = "mockserver-test";

    @SuppressWarnings("resource")
    private static GenericContainer<?> minioContainer;
    private static S3Client s3Client;

    @BeforeClass
    public static void startMinIO() {
        // Wrapped: DockerClientFactory.isDockerAvailable() THROWS rather than returning
        // false for post-connection failures (e.g. Ryuk rejected by a user-namespace
        // remapped daemon), which would turn this skip into a hard ERROR.
        Assume.assumeTrue(
            "Docker is not available -- skipping S3 integration test",
            DockerAvailability.isAvailable(() -> DockerClientFactory.instance().isDockerAvailable())
        );

        minioContainer = new GenericContainer<>(MINIO_IMAGE)
            .withExposedPorts(9000)
            .withEnv("MINIO_ROOT_USER", ACCESS_KEY)
            .withEnv("MINIO_ROOT_PASSWORD", SECRET_KEY)
            .withCommand("server", "/data")
            .waitingFor(new HttpWaitStrategy()
                .forPath("/minio/health/live")
                .forPort(9000)
                .withStartupTimeout(Duration.ofSeconds(30)));

        minioContainer.start();

        String endpoint = "http://" + minioContainer.getHost() + ":" + minioContainer.getMappedPort(9000);

        s3Client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.US_EAST_1)
            .credentialsProvider(StaticCredentialsProvider.create(
                AwsBasicCredentials.create(ACCESS_KEY, SECRET_KEY)))
            .forcePathStyle(true)
            .build();

        // Create the test bucket
        s3Client.createBucket(CreateBucketRequest.builder()
            .bucket(TEST_BUCKET)
            .build());
    }

    @AfterClass
    public static void stopMinIO() {
        if (s3Client != null) {
            s3Client.close();
        }
        if (minioContainer != null) {
            minioContainer.stop();
        }
    }

    /**
     * Every {@code blobStoreKeyPrefix} shape a user can configure must compose a VALID S3
     * object name and round-trip through put/get/list/delete.
     * <p>
     * Before the key normalisation this was not true: a prefix ending in {@code /} (the shape
     * the documentation recommends, {@code blobStoreKeyPrefix="mockserver/"}) concatenated with
     * a key beginning with {@code /} (the persistence layer passed an absolute local path)
     * produced a {@code //} object name that MinIO rejects with HTTP 400, "Object name contains
     * unsupported characters".
     */
    @Test
    public void shouldRoundTripThroughS3ForEveryKeyPrefixShape() {
        String namespace = "prefix-shapes-" + UUID.randomUUID();
        String[] prefixes = {
            "",                          // unset
            namespace,                   // no trailing slash
            namespace + "/",             // trailing slash -- the documented shape
            "/" + namespace + "/",       // leading AND trailing slash
        };

        for (String prefix : prefixes) {
            String key = "persistedExpectations-" + UUID.randomUUID() + ".json";
            BlobStore prefixedStore = new S3BlobStore(s3Client, TEST_BUCKET, prefix);

            prefixedStore.put(key, "payload".getBytes(StandardCharsets.UTF_8), Collections.emptyMap());

            Optional<Blob> read = prefixedStore.get(key);
            assertTrue("prefix '" + prefix + "': blob must be readable back", read.isPresent());
            assertThat("prefix '" + prefix + "'",
                new String(read.get().getData(), StandardCharsets.UTF_8), is("payload"));
            assertThat("prefix '" + prefix + "': list must report the key without the prefix",
                prefixedStore.list(key), hasItem(key));
            assertTrue("prefix '" + prefix + "': delete must find the same object", prefixedStore.delete(key));
            assertFalse("prefix '" + prefix + "': blob must be gone after delete", prefixedStore.get(key).isPresent());
        }
    }

    @Override
    protected BlobStore createStore() {
        // Use a unique key prefix per test to isolate test data
        String prefix = "test-" + UUID.randomUUID() + "/";
        return new S3BlobStore(s3Client, TEST_BUCKET, prefix);
    }

    /**
     * S3 carries user metadata in {@code x-amz-meta-*} HTTP headers, and header field names are
     * ASCII tokens (RFC 9110 &sect;5.1), so a key containing characters above {@code U+00FF}
     * cannot be represented. S3 correctly refuses the write rather than storing something else.
     */
    @Override
    protected boolean supportsNonAsciiMetadataKeys() {
        return false;
    }

    /**
     * Pins the rejection to a genuine "this request is invalid" response from S3, so the contract
     * cannot be satisfied by an unrelated failure (a stopped container, a bad credential, a
     * connection reset) that merely happens to throw.
     */
    @Override
    protected void assertNonAsciiMetadataKeyRejection(RuntimeException rejection) {
        assertThat("expected S3 to reject the unrepresentable metadata key, but got: " + rejection,
            rejection, instanceOf(S3Exception.class));
        assertThat("expected a 4xx invalid-request rejection, not a transport or auth failure",
            ((S3Exception) rejection).statusCode(), is(400));
    }
}
